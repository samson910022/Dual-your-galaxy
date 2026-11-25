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
    /**
     * [runtimeEnabled] is the persisted opt-out value captured with the
     * durable classification. It is a SEED for the checkbox, never proof of a
     * live effect: a confirmed [BbcEffectStatus] always wins over it. `null`
     * means the key was authoritatively absent (no default-true fabrication).
     */
    data class Legacy(val runtimeEnabled: Boolean?) : BbcActivationConsentState
    data object Granted : BbcActivationConsentState
    data object Unknown : BbcActivationConsentState
}

/**
 * Authoritative opt-in key, read the same way the Hook gate reads it:
 * `contains()` first, then a guarded typed get. ABSENT and FALSE are both
 * definite answers (but only FALSE explicitly denies), while UNAVAILABLE is a
 * read failure / wrong type / torn snapshot and must never authorize.
 */
enum class BbcOptInValue { ABSENT, FALSE, TRUE, UNAVAILABLE }

/**
 * ALL authoritative durable inputs of a consent resolution, taken from ONE
 * coherent snapshot of the CURRENT service generation's prefs instance on the
 * app's IO lane. Mirrors the Hook's all-or-nothing `BbcCoherentPrefsSnapshot`:
 * [coherent] == false means at least one read failed / saw a wrong type, so
 * NO authorization may be produced (Granted or Legacy).
 */
data class BbcConsentDurableSnapshot(
    val optIn: BbcOptInValue,
    val classification: String?,
    val migrated: Boolean?,
    /** Authoritative `contains()`-based read; `null` = absent (not default true). */
    val legacyRuntimeEnabled: Boolean?,
    val coherent: Boolean = true,
) {
    companion object {
        fun failClosed(): BbcConsentDurableSnapshot = BbcConsentDurableSnapshot(
            optIn = BbcOptInValue.UNAVAILABLE,
            classification = null,
            migrated = null,
            legacyRuntimeEnabled = null,
            coherent = false,
        )
    }

    internal fun forGeneration(generation: Long): BbcConsentAuthoritativeRead =
        BbcConsentAuthoritativeRead(
            serviceGeneration = generation,
            optIn = optIn,
            classification = classification,
            migrated = migrated,
            legacyRuntimeEnabled = legacyRuntimeEnabled,
            coherent = coherent,
        )
}

/**
 * LEGACY Boolean-ish compatibility factory. It deliberately maps `false` to
 * [BbcOptInValue.ABSENT] (the opt-out key was not written), which is the old
 * "no explicit opt-out" meaning — it does NOT mean an explicit opt-out. Any
 * caller that can observe a definite `false` value (an authoritative read, a
 * parity case) MUST use [BbcConsentDurableSnapshot.forGeneration] or construct
 * [BbcConsentAuthoritativeRead] directly with [BbcOptInValue.FALSE], otherwise
 * an explicit opt-out would silently be treated as "no key" and could be
 * overridden by a legacy fallback. The name is intentionally explicit so a
 * plain Boolean can never be mistaken for a three-way opt-in signal.
 */
internal fun bbcConsentAuthoritativeReadLegacyBoolean(
    generation: Long,
    optIn: Boolean?,
    classification: String?,
    migrated: Boolean?,
    legacyRuntimeEnabled: Boolean?,
    coherent: Boolean = true,
): BbcConsentAuthoritativeRead = BbcConsentAuthoritativeRead(
    serviceGeneration = generation,
    optIn = when (optIn) {
        true -> BbcOptInValue.TRUE
        false -> BbcOptInValue.ABSENT
        null -> BbcOptInValue.UNAVAILABLE
    },
    classification = classification,
    migrated = migrated,
    legacyRuntimeEnabled = legacyRuntimeEnabled,
    coherent = coherent,
)

/**
 * The durable reads needed to resolve authorization, taken from the CURRENT
 * service generation's prefs instance on the app's IO lane.
 *
 * [optIn] distinguishes a definite FALSE/UNAVAILABLE from a normal ABSENT key
 * (the old `optInGranted: Boolean?` collapsed all three and could not mirror
 * the Hook's "explicit opt-out wins" rule).
 * [classification] `null` means the key is genuinely absent; an unreadable /
 * unknown value makes [coherent] false.
 * [migrated] is the authoritative, `contains()`-based read of the one-time
 * migration marker. `PRE_UPGRADE_EXISTING` authorizes ONLY when this is `true`
 * (the same rule the Hook gate applies); `null`/`false` fails closed.
 * [legacyRuntimeEnabled] is nullable: `null` is a definite absence, never a
 * fabricated default-true seed.
 */
internal data class BbcConsentAuthoritativeRead(
    val serviceGeneration: Long,
    val optIn: BbcOptInValue,
    val classification: String?,
    val migrated: Boolean?,
    val legacyRuntimeEnabled: Boolean?,
    val coherent: Boolean = true,
)

/** Authorization-only seed for a freshly built UI. Never a final value. */
internal data class BbcActivationToggleUi(
    val checked: Boolean?,
    val neutral: Boolean,
)

/**
 * The ONE checkbox projection, fed by BOTH app collectors (authorization and
 * effect status). [checked] == null means "leave the CheckBox as-is" — neither
 * authorization nor a provisional read may fabricate a value.
 *
 * Rule, in priority order:
 *  1. a pending user intent wins (the user's latest, still-unverified choice);
 *  2. [BbcEffectStatus.Confirmed] supplies the acknowledged effect value;
 *  3. Provisional/Unknown supplies NO value and renders neutral/unverified.
 *
 * Authorization does not decide the checkbox value: [Granted] is permission
 * to skip the consent dialog, NOT proof that the effect is on. It only affects
 * the "unresolved" neutral styling and, via [requiresConsentFor], whether an
 * ON tap asks for consent.
 */
internal data class BbcToggleUiProjection(
    val checked: Boolean?,
    val neutral: Boolean,
)

internal fun bbcToggleUiProjection(
    authorization: BbcActivationConsentState,
    effect: BbcEffectStatus,
    pendingIntent: Boolean?,
): BbcToggleUiProjection {
    val checked = when {
        pendingIntent != null -> pendingIntent
        effect is BbcEffectStatus.Confirmed -> effect.value
        else -> null
    }
    val neutral = checked == null ||
        authorization is BbcActivationConsentState.Initializing ||
        authorization is BbcActivationConsentState.Unknown
    return BbcToggleUiProjection(checked, neutral)
}

/** Fail-closed gate: an unresolved/unknown state still demands consent on ON. */
internal fun BbcActivationConsentState.requiresConsentFor(checked: Boolean): Boolean =
    checked && this !is BbcActivationConsentState.Granted &&
        this !is BbcActivationConsentState.Legacy

/**
 * Authorization-only SEED for a freshly built UI. A [Granted] permission must
 * not fabricate a checked ON: the Hook gate may be closed because the runtime
 * preference is false. The final value always comes from [bbcToggleUiProjection].
 */
internal fun BbcActivationConsentState.toggleUi(): BbcActivationToggleUi = when (this) {
    BbcActivationConsentState.NotGranted -> BbcActivationToggleUi(false, false)
    is BbcActivationConsentState.Legacy -> BbcActivationToggleUi(runtimeEnabled, false)
    BbcActivationConsentState.Granted -> BbcActivationToggleUi(null, false)
    BbcActivationConsentState.Initializing,
    BbcActivationConsentState.Unknown,
    -> BbcActivationToggleUi(null, true)
}

// Classification vocabulary shared with the Hook gate. EXPLICITLY_GRANTED is
// RESERVED (Phase A): we only ever READ it; the grant path writes the opt-in
// key and never fabricates that classification value.
// PRE_UPGRADE_EXISTING is deliberately NOT here: like BbcPreactivationGate it
// needs the migration marker as a second durable proof. MIGRATED and
// EXPLICITLY_GRANTED open without a marker, but EVERY classification-based
// authorization (including PRE_UPGRADE) still requires a classification
// confirmed for the current service generation — see resolve() and
// markInstallClassificationConfirmed().
private val LEGACY_CLASSIFICATIONS = setOf(
    PolicySource.INSTALL_CLASSIFICATION_MIGRATED,
    PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED,
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
 *    or [resolve] with `BbcOptInValue.TRUE` from the *current* service
 *    generation may produce [Granted].
 *  - A same-generation read can NEVER upgrade [Unknown] — the local mMap may
 *    have been optimistically polluted by a failed commit. Only a read from a
 *    newer service generation (after [onServiceGenerationChanged]) may, via
 *    [resolve], clear the fence. A failed bind-time classification commit
 *    ([markInstallClassificationFailed]) installs that fence so a polluted
 *    PRE_UPGRADE + marker local read can never be mistaken for [Legacy].
 *  - [Legacy] mirrors the Hook gate: PRE_UPGRADE_EXISTING needs the durable
 *    migration marker too; only MIGRATED/EXPLICITLY_GRANTED open without it.
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
        /**
         * Service generation whose bind-time classification the owner App
         * CONFIRMED durable. Only a FORCED, SUCCESSFUL commit confirms it — a
         * NoWrite inferred from a polluted local mMap is never enough. A
         * classification read is only allowed to authorize Legacy for this
         * exact generation: a queued read can otherwise observe an optimistic
         * local mMap polluted by a FAILED commit before
         * [markInstallClassificationFailed] fences it.
         */
        val classificationConfirmedGeneration: Long,
    )

    private val fenced = AtomicReference(
        Fenced(BbcActivationConsentState.Initializing, 0L, 0L, -1L, -1L),
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

    /** True only when the owner App confirmed classification for THIS generation. */
    internal fun isInstallClassificationConfirmed(generation: Long): Boolean =
        fenced.get().classificationConfirmedGeneration == generation

    /**
     * Bind-time classification was confirmed by a FORCED, SUCCESSFUL durable
     * commit (never by a NoWrite/local-mMap inference). Only then may a legacy
     * classification read authorize Legacy; see [resolve]. A dead generation
     * cannot certify a new instance.
     */
    @Synchronized
    internal fun markInstallClassificationConfirmed(generation: Long) {
        val cur = fenced.get()
        if (generation != cur.generation) return
        fenced.set(cur.copy(classificationConfirmedGeneration = generation))
    }

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
        // The confirmation belongs to a prefs instance, not to the app process:
        // a rebind must re-confirm classification for the new generation.
        fenced.set(Fenced(next, generation, cur.appliedIntentSequence, cur.fenceGeneration, -1L))
        _state.value = next
    }

    /**
     * Authoritative resolution from durable keys. A read from an older service
     * generation is ignored outright (never trust a stale instance). A
     * same-generation read cannot clear [Unknown]: that is exactly the polluted
     * local-mMap case the durable fence exists for. Only a read from a NEWER
     * generation (a genuinely different prefs instance) may clear the fence.
     *
     * Decision order mirrors the read-only Hook gate: explicit opt-out wins
     * over every legacy fallback, an unreadable snapshot authorizes nothing,
     * and legacy classification needs BOTH a confirmed generation and (for
     * PRE_UPGRADE_EXISTING) the durable migration marker.
     */
    @Synchronized
    fun resolve(read: BbcConsentAuthoritativeRead) {
        val cur = fenced.get()
        if (read.serviceGeneration != cur.generation) return
        val fenceIsCurrent =
            cur.state is BbcActivationConsentState.Unknown &&
                read.serviceGeneration <= cur.fenceGeneration
        val classificationConfirmed =
            isInstallClassificationConfirmed(read.serviceGeneration)
        val legacy = when {
            read.classification == PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING ->
                read.migrated == true
            else -> read.classification in LEGACY_CLASSIFICATIONS
        }
        val next = when {
            fenceIsCurrent -> cur.state
            // ALL-OR-NOTHING: any failed authoritative read masks the snapshot.
            !read.coherent || read.optIn == BbcOptInValue.UNAVAILABLE -> cur.state
            // Highest priority, exactly like bbcPreactivationGate's optIn == false.
            read.optIn == BbcOptInValue.FALSE -> BbcActivationConsentState.NotGranted
            read.optIn == BbcOptInValue.TRUE -> BbcActivationConsentState.Granted
            legacy && classificationConfirmed ->
                BbcActivationConsentState.Legacy(read.legacyRuntimeEnabled)
            read.classification == PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED ->
                BbcActivationConsentState.NotGranted
            else -> cur.state // unresolved: stay neutral rather than invent a state
        }
        if (next === cur.state) return
        val fenceGeneration = if (next is BbcActivationConsentState.Unknown) read.serviceGeneration else -1L
        fenced.set(
            Fenced(
                next, cur.generation, cur.appliedIntentSequence,
                fenceGeneration, cur.classificationConfirmedGeneration,
            ),
        )
        _state.value = next
    }

    /**
     * The owner App's bind-time durable install-classification commit FAILED.
     * The optimistic local mMap may now contain PRE_UPGRADE_EXISTING plus the
     * migration marker while the daemon persisted neither; feeding that read
     * into [resolve] would falsely authorize [BbcActivationConsentState.Legacy].
     *
     * Install a same-generation Unknown fence instead: no local read from this
     * generation can lift it, and no consent is skipped. Only an authoritative
     * resync from a NEWER service instance (after a successful retry) may
     * clear it. An already-authorized [BbcActivationConsentState.Granted] /
     * [BbcActivationConsentState.Legacy] is left untouched: a late bind-time
     * failure must not regress a confirmed grant or a durable Legacy resync.
     * This does NOT touch the user-intent sequence — a bind-time bookkeeping
     * failure is not a consent-write outcome and must not invalidate a newer
     * user intent.
     */
    @Synchronized
    fun markInstallClassificationFailed(generation: Long) {
        val cur = fenced.get()
        if (generation != cur.generation) return // a dead instance cannot pollute this one
        // A late async failure (e.g. an older bind's commit completing after a
        // Confirmed consent grant or a durable Legacy resync) must never
        // DOWNGRADE an already-authorized state. This is fail-closed UX only:
        // it can never turn a denied/unknown state into a grant.
        if (cur.state is BbcActivationConsentState.Granted ||
            cur.state is BbcActivationConsentState.Legacy
        ) return
        if (cur.state is BbcActivationConsentState.Unknown &&
            cur.fenceGeneration >= generation
        ) return
        fenced.set(
            Fenced(
                BbcActivationConsentState.Unknown, cur.generation, cur.appliedIntentSequence,
                generation, cur.classificationConfirmedGeneration,
            ),
        )
        _state.value = BbcActivationConsentState.Unknown
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
            if (generation == cur.generation) {
                // A failed commit on the CURRENT generation polluted THIS
                // prefs instance's local mMap. Even if an older Unknown fence
                // was preserved across a rebind, it must advance to the
                // current generation: only a NEWER instance may now clear it.
                cur.generation
            } else if (cur.state is BbcActivationConsentState.Unknown) {
                // Dead-generation reply while already fenced: keep the older
                // fence rather than weakening it.
                cur.fenceGeneration
            } else {
                // A generation mismatch means the only evidence came from the
                // DEAD service generation, so an authoritative read from the
                // CURRENT (newer) instance may clear this fence on resync.
                minOf(cur.generation, generation)
            }
        } else -1L
        fenced.set(
            Fenced(
                next, cur.generation, intentSequence, fenceGeneration,
                cur.classificationConfirmedGeneration,
            ),
        )
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
        fenced.set(
            Fenced(
                cur.state, cur.generation, intentSequence, cur.fenceGeneration,
                cur.classificationConfirmedGeneration,
            ),
        )
    }
}
