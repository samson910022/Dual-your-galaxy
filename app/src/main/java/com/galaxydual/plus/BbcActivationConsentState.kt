// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase C (P1-03 / P1-04 / P2-05): ONE app-scoped activation/consent
// state model for the BBC pre-activation opt-in.
//
// Framework-free on purpose: this file only uses the shared hook vocabulary
// (PolicySource) plus kotlinx.coroutines' StateFlow, so JVM tests drive the
// exact same state machine production uses — no Robolectric, no Activity.
//
// AXES ARE SEPARATE, NEVER CONFLATED:
//  * [BbcActivationConsentState] is the AUTHORIZATION axis (may we open the
//    gate without asking?). It is resolved from durable classification / opt-in
//    keys and from *confirmed* consent commits only.
//  * [BbcEffectStatus] is the EFFECT axis (what the app last saw acknowledged
//    by the daemon for the runtime opt-out preference). A preference read, a
//    classification read, or a consent commit is NEVER proof of a live hook
//    effect. Reuse BbcEffectStatus for presentation; do not invent a "confirmed
//    effect" state here.
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Authorization state of the BBC pre-activation consent.
 *
 * [Initializing] is the neutral seed: no authoritative durable read for the
 * current service generation has landed yet. It renders retryable/neutral and
 * NEVER claims effect ON or OFF. Turning ON from it still demands consent
 * (fail-closed): an unresolved state can never be used as proof of consent.
 *
 * [NotGranted] is a resolved fresh install (NEW_GENERATION_NOT_CONSENTED):
 * the first user-initiated ON goes straight to the consent dialog.
 *
 * [Legacy] authorizes through the durable migration classification, not a
 * consent dialog. The CheckBox still shows the *existing runtime opt-out*
 * value captured with the classification — this is a persisted preference
 * value, not an effect confirmation.
 *
 * [Granted] means a consent commit returned Confirmed, or an authoritative
 * resync from a fresh service instance found the opt-in key persisted. A raw
 * local mMap read can never produce it.
 *
 * [Unknown] is a DURABLE FENCE: a consent commit returned Unknown or a service
 * generation changed mid-write. It survives store/controller/Activity rebuilds
 * and binder death/reconnect, and the next ON must re-ask consent. Only a
 * Confirmed commit or an authoritative resync from a NEW service generation
 * may clear it.
 */
internal sealed interface BbcActivationConsentState {
    data object Initializing : BbcActivationConsentState
    data object NotGranted : BbcActivationConsentState
    data class Legacy(val runtimeEnabled: Boolean) : BbcActivationConsentState
    data object Granted : BbcActivationConsentState
    data object Unknown : BbcActivationConsentState
}

/**
 * The three durable reads needed to resolve authorization, taken from the
 * CURRENT service generation's prefs instance on the app's IO lane.
 *
 * [optInGranted] `null` means the read could not be performed authoritatively
 * (binder absent / throw). It must never be coerced to "granted".
 * [classification] `null` means the key is absent or unreadable.
 */
internal data class BbcConsentAuthoritativeRead(
    val serviceGeneration: Long,
    val optInGranted: Boolean?,
    val classification: String?,
    val legacyRuntimeEnabled: Boolean,
)

/** Pure UI projection. `checked == null` means "leave the CheckBox as-is". */
internal data class BbcActivationToggleUi(
    val checked: Boolean?,
    val neutral: Boolean,
)

/** Fail-closed gate: an unresolved/unknown state still demands consent on ON. */
internal fun BbcActivationConsentState.requiresConsentFor(checked: Boolean): Boolean =
    checked && this !is BbcActivationConsentState.Granted &&
        this !is BbcActivationConsentState.Legacy

/** Never fabricates an OFF/ON for [Initializing]/[Unknown]: checked == null. */
internal fun BbcActivationConsentState.toggleUi(): BbcActivationToggleUi = when (this) {
    BbcActivationConsentState.NotGranted -> BbcActivationToggleUi(false, false)
    is BbcActivationConsentState.Legacy -> BbcActivationToggleUi(runtimeEnabled, false)
    BbcActivationConsentState.Granted -> BbcActivationToggleUi(true, false)
    BbcActivationConsentState.Initializing,
    BbcActivationConsentState.Unknown,
    -> BbcActivationToggleUi(null, true)
}

// Classification vocabulary shared with the Hook gate. EXPLICITLY_GRANTED is
// RESERVED (Phase A): we only ever READ it; the grant path writes the opt-in
// key and never fabricates that classification value.
private val LEGACY_CLASSIFICATIONS = setOf(
    PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
    PolicySource.INSTALL_CLASSIFICATION_MIGRATED,
)

/**
 * App-process-local holder for the authorization state.
 *
 * Constructed ONCE by [GalaxyApp] and shared by every store/controller/Activity
 * rebuild. All mutations are `@Synchronized` so the state, service generation
 * and applied-intent fence move together.
 *
 * Trust rules (explicit and conservative):
 *  - Only [markConsentWriteOutcome] with Confirmed (and matching generation)
 *    or [resolve] with `optInGranted == true` from the *current* service
 *    generation may produce [Granted].
 *  - A same-generation read can NEVER upgrade [Unknown] — the local mMap may
 *    have been optimistically polluted by a failed commit. Only a read from a
 *    newer service generation (after [onServiceGenerationChanged]) may, via
 *    [resolve], clear the fence.
 *  - A generation change drops Granted/Legacy to [Initializing] (must resync)
 *    but PRESERVES [Unknown] (the fence outlives binder death/reconnect).
 *  - [abandonIntent] advances the outcome fence (cancel / OFF / show failure)
 *    and invalidates the pending enable for that intent: zero writes, zero
 *    state claims, and a late commit result can no longer land.
 *  - An ENABLE handed off to the effect controller is recorded app-scoped at
 *    accept time and consumed exactly once (see [notePendingEnable],
 *    [consumePendingEnable], [consumePendingEnableForReplay]). This is what
 *    keeps the checkbox and the runtime preference converged across a UI
 *    rebuild; a grant alone is never treated as effect proof.
 */
internal class BbcActivationConsentHolder {

    private data class Fenced(
        val state: BbcActivationConsentState,
        val generation: Long,
        val appliedIntentSequence: Long,
        /**
         * Service generation that installed the current Unknown fence. A read
         * from this same generation may be the polluted local mMap and can
         * NEVER clear it; a read from a NEWER generation is an authoritative
         * resync from a different prefs instance and may.
         */
        val fenceGeneration: Long,
    )

    private val fenced = AtomicReference(
        Fenced(BbcActivationConsentState.Initializing, 0L, 0L, -1L),
    )
    private val sequence = AtomicLong(0L)

    /**
     * PR #90 Phase C (P2): an ENABLE that was enqueued with a Confirmed consent
     * write but whose handoff to the effect controller has not been consumed
     * yet. App-scoped on purpose: a rotation that drops the submitting
     * Activity's UI callback must not lose the intent. Consuming it is
     * EXACTLY-ONCE and atomic, so a rebuilt UI and a late UI callback can never
     * both dispatch the effect toggle.
     */
    private data class PendingEnable(val sequence: Long, val generation: Long)

    /** Guarded by every method below being @Synchronized. */
    private var pendingEnable: PendingEnable? = null

    private val _state = MutableStateFlow<BbcActivationConsentState>(BbcActivationConsentState.Initializing)
    val state: StateFlow<BbcActivationConsentState> = _state.asStateFlow()

    /** Synchronous read for the Activity's initial (non-blocking) checkbox seed. */
    fun currentState(): BbcActivationConsentState = fenced.get().state

    fun currentGeneration(): Long = fenced.get().generation

    /** Monotonic, app-wide user-intent sequence (never reset by UI rebuilds). */
    fun nextIntentSequence(): Long = sequence.incrementAndGet()

    /**
     * P2: record the ENABLE app-scoped BEFORE the consent write is enqueued.
     * The grant alone is only AUTHORIZATION — it is not proof that the runtime
     * opt-out preference was ever written. A rebuilt UI uses the recorded
     * intent to converge the effect exactly once.
     */
    @Synchronized
    fun notePendingEnable(sequence: Long, generation: Long) {
        pendingEnable = PendingEnable(sequence, generation)
    }

    /**
     * P2: handoff from the consent-write UI callback. Returns true exactly once
     * for a matching (sequence, generation); a rebuilt UI that already replayed
     * the intent gets false and must NOT toggle a second time.
     */
    @Synchronized
    fun consumePendingEnable(sequence: Long, generation: Long): Boolean {
        val pending = pendingEnable ?: return false
        if (pending.sequence != sequence || pending.generation != generation) return false
        pendingEnable = null
        return true
    }

    /**
     * P2: replay hook for a rebuilt Activity that observes [Granted] without a
     * matching confirmed effect mark. Generation must still be current (a
     * rebind invalidates the old instance's intent) and the intent is consumed
     * exactly once.
     */
    @Synchronized
    fun consumePendingEnableForReplay(generation: Long): Boolean {
        val pending = pendingEnable ?: return false
        if (pending.generation != generation) return false
        if (fenced.get().state !is BbcActivationConsentState.Granted) return false
        pendingEnable = null
        return true
    }

    /**
     * Binder (re)bind / death. A NEW service instance exposes a different
     * remote-prefs instance, so cached Granted/Legacy is no longer trustworthy.
     * The durable Unknown fence is deliberately preserved.
     */
    @Synchronized
    fun onServiceGenerationChanged(generation: Long) {
        val cur = fenced.get()
        if (generation == cur.generation) return
        // A dead service instance cannot certify an enable handoff either.
        pendingEnable = null
        val next = when (cur.state) {
            is BbcActivationConsentState.Granted,
            is BbcActivationConsentState.Legacy,
            -> BbcActivationConsentState.Initializing
            else -> cur.state
        }
        fenced.set(Fenced(next, generation, cur.appliedIntentSequence, cur.fenceGeneration))
        _state.value = next
    }

    /**
     * Authoritative resolution from durable keys. A read from an older service
     * generation is ignored outright (never trust a stale instance). A
     * same-generation read cannot clear [Unknown]: that is exactly the polluted
     * local-mMap case the durable fence exists for. Only a read from a NEWER
     * generation (a genuinely different prefs instance) may clear the fence.
     */
    @Synchronized
    fun resolve(read: BbcConsentAuthoritativeRead) {
        val cur = fenced.get()
        if (read.serviceGeneration != cur.generation) return
        val fenceIsCurrent =
            cur.state is BbcActivationConsentState.Unknown &&
                read.serviceGeneration <= cur.fenceGeneration
        val next = when {
            fenceIsCurrent -> cur.state
            read.optInGranted == true -> BbcActivationConsentState.Granted
            read.classification == PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED ->
                BbcActivationConsentState.Granted
            read.classification in LEGACY_CLASSIFICATIONS ->
                BbcActivationConsentState.Legacy(read.legacyRuntimeEnabled)
            read.classification == PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED ->
                BbcActivationConsentState.NotGranted
            else -> cur.state // unresolved: stay neutral rather than invent a state
        }
        if (next === cur.state) return
        val fenceGeneration = if (next is BbcActivationConsentState.Unknown) read.serviceGeneration else -1L
        fenced.set(Fenced(next, cur.generation, cur.appliedIntentSequence, fenceGeneration))
        _state.value = next
    }

    /**
     * Fold a consent commit outcome into the durable state. [generation] is the
     * service generation captured at user-intent time; a mismatch (service died
     * mid-write) maps even a Confirmed reply to the Unknown fence — the new
     * service instance has not confirmed anything.
     *
     * @return false for an older/already-applied intent; its callback is stale.
     */
    @Synchronized
    fun markConsentWriteOutcome(
        outcome: BbcWriteOutcome,
        intentSequence: Long,
        generation: Long,
    ): Boolean {
        val cur = fenced.get()
        val stale = intentSequence <= cur.appliedIntentSequence
        val grants = !stale && outcome is BbcWriteOutcome.Confirmed &&
            generation == cur.generation
        // Only a Confirmed grant on the CURRENT generation keeps the enable
        // intent alive for the UI handoff/replay. Unknown, a stale intent, or a
        // reply from a dead service drops it — that is OFF-wins / fail-closed.
        val pending = pendingEnable
        if (pending != null && pending.sequence <= intentSequence && !grants) {
            pendingEnable = null
        }
        if (stale) return false
        val next = if (grants) BbcActivationConsentState.Granted else BbcActivationConsentState.Unknown
        val fenceGeneration = if (next is BbcActivationConsentState.Unknown) {
            if (cur.state is BbcActivationConsentState.Unknown) {
                cur.fenceGeneration
            } else {
                // A generation mismatch means the only evidence came from the
                // DEAD service generation, so an authoritative read from the
                // CURRENT (newer) instance may clear this fence on resync.
                minOf(cur.generation, generation)
            }
        } else -1L
        fenced.set(Fenced(next, cur.generation, intentSequence, fenceGeneration))
        _state.value = next
        return true
    }

    /**
     * Cancel / OFF / dialog-show-failure: invalidate one intent with ZERO
     * writes and no state claim. A late commit result for that sequence (or any
     * older one) is dropped; newer intents still advance normally.
     */
    @Synchronized
    fun abandonIntent(intentSequence: Long) {
        val cur = fenced.get()
        // OFF/cancel invalidates the enable handoff even if the fence was
        // already advanced by an earlier outcome for this same intent.
        val pending = pendingEnable
        if (pending != null && pending.sequence <= intentSequence) pendingEnable = null
        if (intentSequence <= cur.appliedIntentSequence) return
        fenced.set(Fenced(cur.state, cur.generation, intentSequence, cur.fenceGeneration))
    }
}
