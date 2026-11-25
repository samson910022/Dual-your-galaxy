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
    /**
     * APP-ONLY durable "enable pending" marker, read from the SAME coherent
     * copy as [optIn]. A Confirmed consent grant writes optin=true and this
     * marker=true in ONE commit; every runtime preference commit (the consumer
     * enable AND any user OFF) clears it in ONE commit. `null`/false means no
     * resync-driven enable is allowed. The Hook gate NEVER reads this key.
     */
    val enablePending: Boolean? = null,
) {
    companion object {
        fun failClosed(): BbcConsentDurableSnapshot = BbcConsentDurableSnapshot(
            optIn = BbcOptInValue.UNAVAILABLE,
            classification = null,
            migrated = null,
            legacyRuntimeEnabled = null,
            coherent = false,
            enablePending = null,
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
            enablePending = enablePending,
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
    /** APP-ONLY enable-pending marker; see [BbcConsentDurableSnapshot.enablePending]. */
    val enablePending: Boolean? = null,
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
 * Rule, in priority order (PR #90 Phase 3 keeps Phase 2's ordering intact):
 *  1. a pending user intent wins (the user's latest, still-unverified choice);
 *  2. [BbcEffectStatus.Confirmed] supplies the acknowledged effect value;
 *  3. [BbcEffectStatus.ProvisionalRead] supplies the persisted value read on the
 *     IO lane (a neutral seed, NOT a write acknowledgement);
 *  4. Provisional/Unknown supplies NO value and renders neutral/unverified.
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
        effect is BbcEffectStatus.ProvisionalRead -> effect.value
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
 *  - An ENABLE was recorded app-scoped at accept time and the consume seams
 *    ([notePendingEnable], [consumePendingEnable],
 *    [consumePendingEnableForReplay], [isEnableHandledBy]) are TEST-ONLY: no
 *    production code consumes that record. Phase 2: the app IO consumer that
 *    confirms the opt-in owns the actual effect write; UI callbacks and state
 *    replay are presentation-only and can never toggle it a second time. A
 *    grant alone is never treated as effect proof.
 *  - Cold-start convergence ALSO requires the app-only durable enable-pending
 *    marker from the SAME coherent read as optin=true. The marker is cleared
 *    in the same commit as a user OFF, so an authoritative resync can never
 *    reverse a durable OFF; it is NOT a Hook authorization key.
 *  - Enable ownership is a GENERATION-SCOPED monotonic token shared by the
 *    consumer and the resync. A newer claim is never regressed by a later
 *    Confirmed intent on the same generation, but a rebind may claim the same
 *    still-owed enable once on a NEW generation.
 */
internal class BbcActivationConsentHolder(
    /**
     * Shared with [BbcToggleRuntimeState] in production so an OFF toggle and a
     * pending consent intent live in ONE sequence domain. Tests that construct
     * the holder alone get their own monotonic counter.
     */
    private val sequence: AtomicLong = AtomicLong(),
) {

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

    /**
     * App-scoped dialog intent sequence (replaces MainActivity's per-Activity
     * field): a rebuilt Activity still recognises/supersedes an older pending
     * grant, so a late accept can never win over a newer OFF.
     */
    @Volatile
    private var activeIntentSequence: Long = 0L

    /**
     * Highest OFF/cancel sequence in the shared domain. The consumer compares
     * this with a grant's sequence before writing the phase-2 enable, so an OFF
     * that arrived while the opt-in commit was in flight always wins.
     */
    @Volatile
    private var latestAbandonSequence: Long = 0L

    /**
     * Generation-scoped enable ownership. A Confirmed grant owns the effect
     * enable in the generation whose prefs instance it actually touched; a
     * rebind to a NEW generation re-arms the SAME owed enable exactly once.
     * Tokens are compared lexicographically (generation first, then sequence):
     * an older generation's token is evidence for that dead instance only and
     * can never block a new generation, while within one generation a newer
     * claim is never regressed by a later Confirmed S<R.
     */
    private data class EnableOwner(val generation: Long, val sequence: Long) {
        fun outranks(other: EnableOwner?): Boolean =
            other == null || generation > other.generation ||
                (generation == other.generation && sequence > other.sequence)
    }

    /** Current enable owner, published atomically with the grant/claim itself. */
    @Volatile
    private var enableOwner: EnableOwner? = null

    /**
     * Sequence of the last CONFIRMED consent grant. A resync-driven enable
     * compares OFF/cancel evidence against THIS sequence (the grant's own
     * intent), never against a freshly minted sequence that would be newer
     * than every recorded abandon and make the OFF guard inert.
     */
    @Volatile
    private var confirmedGrantSequence: Long = 0L

    /** The single generation whose resync is allowed to drive a runtime enable. */
    @Volatile
    private var resyncEnableGeneration: Long = -1L

    /** Generation in which the user (or cancel) said OFF: resync may not re-enable it. */
    @Volatile
    private var resyncEnableBlockedGeneration: Long = -1L

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

    /** True only for the app-scoped dialog intent currently awaiting an answer. */
    fun isActiveIntent(intentSequence: Long): Boolean =
        intentSequence != 0L && intentSequence == activeIntentSequence

    /** Start a new consent dialog intent, superseding any older one app-wide. */
    @Synchronized
    fun startConsentIntent(): Long {
        activeIntentSequence = nextIntentSequence()
        return activeIntentSequence
    }

    /**
     * OFF / cancel / show-failure supersedes the active dialog intent. Called
     * UNCONDITIONALLY by the OFF branch: a rebuilt Activity must abandon an
     * older pending grant even when its own local field was never set.
     */
    @Synchronized
    fun supersedeActiveIntent(): Long {
        val next = nextIntentSequence()
        activeIntentSequence = next
        abandonIntent(next)
        return next
    }

    /** Highest abandon/OFF sequence in the shared intent domain. */
    fun latestAbandonSequence(): Long = latestAbandonSequence

    /**
     * Execution-time authority check for a QUEUED consent write. Accept() may
     * enqueue the opt-in commit and the user may then tap OFF before the app IO
     * consumer drains the slot. Such a grant must not enter the store AT ALL:
     * its atomic optin=true + marker=true write would re-arm a cold-start
     * enable after the OFF already cleared the marker, reversing the user.
     *
     * Does NOT consult [activeIntentSequence]: framework-free callers may submit
     * a consent write without opening a dialog, and a rebind clears the active
     * intent while durable authority is still checked by generation below.
     */
    internal fun isConsentWriteStillAuthoritative(sequence: Long, generation: Long): Boolean {
        if (sequence <= 0L) return false
        val cur = fenced.get()
        return generation == cur.generation &&
            sequence > latestAbandonSequence &&
            sequence > cur.appliedIntentSequence
    }

    /**
     * TEST-ONLY seam (no production caller): true when the app-consumer enable
     * ownership token already covers this intent. [generation] is part of the
     * check — a token belonging to a dead prefs instance is NOT evidence that
     * this generation handled the intent. The default keeps existing
     * framework-free call sites compiling while asking about the current
     * generation, which is the only one production could care about.
     */
    fun isEnableHandledBy(
        intentSequence: Long,
        generation: Long = currentGeneration(),
    ): Boolean {
        val owner = enableOwner ?: return false
        return owner.generation == generation &&
            owner.sequence >= intentSequence && owner.sequence >= 0L
    }

    /** Test/telemetry seam: the current monotonic enable-ownership token. */
    internal fun latestEnableDrivenSequence(): Long = enableOwner?.sequence ?: -1L

    /**
     * True only when THIS intent is the current enable owner FOR THIS
     * GENERATION. A resync that claimed a newer sequence R keeps ownership at R;
     * a later Confirmed S<R must not be treated as a fresh, second enable. A
     * token from a dead generation is not ownership of the new instance.
     */
    internal fun isEnableOwner(intentSequence: Long, generation: Long): Boolean {
        val owner = enableOwner ?: return false
        return owner.generation == generation &&
            owner.sequence == intentSequence && intentSequence >= 0L
    }

    /**
     * True when an enable for [sequence] is already owned in [generation].
     * Ownership from an OLDER generation is deliberately NOT authority here:
     * the whole point of generation scoping is that a rebind may claim the same
     * still-owed enable once on its new prefs instance. Ownership from a NEWER
     * generation still blocks going backwards.
     */
    private fun enableAlreadyOwned(generation: Long, sequence: Long): Boolean {
        val owner = enableOwner ?: return false
        return when {
            owner.generation > generation -> true
            owner.generation == generation -> owner.sequence >= sequence
            else -> false
        }
    }

    /** The grant sequence that a cold-start resync must compare OFFs against. */
    internal fun confirmedGrantSequence(): Long = confirmedGrantSequence

    /** Test/telemetry seam: the generation owning the current (Unknown) fence. */
    internal fun currentFenceGeneration(): Long = fenced.get().fenceGeneration

    /**
     * Atomic, once-per-generation claim for a resync-driven enable.
     *
     * COLD-START CONVERGENCE WITHOUT REVERSING A DURABLE OFF: the durable
     * opt-in key alone is not enough. The app-only [enablePending] marker is
     * written in the SAME commit as optin=true on a Confirmed grant and cleared
     * in the SAME commit as every runtime write (including a user OFF). It is
     * therefore true after process death only when an enable is genuinely
     * still owed; an OFF that survived the daemon cleared it. The marker is
     * NOT a Hook authorization key.
     *
     * [reasonSequence] is the CONFIRMED grant's intent sequence — OFF/cancel
     * evidence is compared against it, not against a freshly minted sequence
     * that would outrank every recorded abandon. [claimSequence] is a fresh
     * monotonic ownership token so a later Confirmed S cannot regress it into
     * a second enable.
     */
    @Synchronized
    fun tryClaimResyncEnable(
        generation: Long,
        reasonSequence: Long,
        enablePending: Boolean,
        claimSequence: Long = reasonSequence,
    ): Boolean {
        val cur = fenced.get()
        if (generation != cur.generation) return false
        // Only an explicit/authoritative GRANT authorises converging the
        // runtime enable. Legacy(false) means the durable opt-out is OFF:
        // auto-enabling there would override the user's persisted choice.
        if (cur.state !is BbcActivationConsentState.Granted) return false
        if (!enablePending) return false
        if (resyncEnableGeneration >= generation) return false
        if (resyncEnableBlockedGeneration >= generation) return false
        // Generation-aware token: a grant the PREVIOUS generation already drove
        // (possibly with an Unknown outcome) must not block the NEW generation
        // from claiming the still-owed enable. Within THIS generation the
        // maxOf-equivalent below still prevents a second enable.
        if (enableAlreadyOwned(generation, reasonSequence)) return false
        if (latestAbandonSequence > reasonSequence) return false
        resyncEnableGeneration = generation
        // Never regress a newer ownership token (the dialog's Confirmed S may
        // arrive after a resync claimed R>S). Because the token carries its
        // generation, a new generation legitimately re-arms its own sequence.
        val candidate = EnableOwner(generation, claimSequence)
        if (candidate.outranks(enableOwner)) enableOwner = candidate
        return true
    }

    /**
     * TEST-ONLY handoff bookkeeping (no production consumer). Production still
     * records the intent at accept time, but the phase-2 effect write is owned
     * and driven by the app IO consumer in [BbcToggleCoordinator], never by this
     * record; new production code must not rely on it. Kept for the
     * framework-free exactly-once handoff tests. The grant alone is only
     * AUTHORIZATION — it is not proof that the runtime preference was written.
     */
    @Synchronized
    fun notePendingEnable(sequence: Long, generation: Long) {
        pendingEnable = PendingEnable(sequence, generation)
    }

    /**
     * TEST-ONLY seam (no production caller): handoff from a consent-write UI
     * callback. Returns true exactly once for a matching (sequence, generation);
     * a rebuilt UI that already replayed the intent gets false and must NOT
     * toggle a second time.
     */
    @Synchronized
    fun consumePendingEnable(sequence: Long, generation: Long): Boolean {
        val pending = pendingEnable ?: return false
        if (pending.sequence != sequence || pending.generation != generation) return false
        pendingEnable = null
        return true
    }

    /**
     * TEST-ONLY seam (no production caller): replay hook for a rebuilt Activity
     * in the framework-free tests. Generation must still be current (a rebind
     * invalidates the old instance's intent) and the intent is consumed exactly
     * once. Production convergence never uses a state-flow replay.
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
        // A dead service instance cannot certify an enable handoff either. A
        // dialog callback from the dead binder is stale in the app-wide domain.
        pendingEnable = null
        activeIntentSequence = 0L
        // A durable OFF is now carried by the APP-ONLY marker (cleared in the
        // same commit as runtime=false), so a rebind must NOT discard the
        // in-process OFF guard. Clearing it here was one reason both guards
        // were inert after a rebind.
        resyncEnableGeneration = -1L
        // The enable OWNERSHIP token is deliberately kept: it is generation-
        // scoped, so the dead instance's sequence can neither block nor be
        // duplicated on this generation. A new resync claims one token here.
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
     * Fold a consent commit outcome into the durable state.
     *
     * [touchedGeneration] is the generation of the prefs instance the commit
     * ACTUALLY touched (reported by the generation-aware seam), not merely the
     * generation captured at user-intent time. Only a Confirmed reply on the
     * CURRENT generation may grant; a generation mismatch maps even Confirmed
     * to the durable Unknown fence.
     *
     * TWO SEPARATE CONCERNS, in this order:
     *  1. Durability evidence: any commit that entered and returned Unknown (or
     *     Confirmed from a dead instance) may have polluted the local mMap of
     *     [touchedGeneration]. That instance is fenced BEFORE the stale check,
     *     so a later OFF/abandon cannot swallow the fence and let a same-gen
     *     resync falsely resolve Granted.
     *  2. UI-intent outcome: a stale sequence still gets no grant and no
     *     callback; it must never advance the applied-intent fence either.
     *
     * @param consumerOwnsEnable when true (the app coordinator consumer), a
     *   Confirmed grant consumes the pending UI handoff and records that the
     *   effect enable is driven by the consumer, BEFORE Granted is published.
     *   Direct holder callers keep the legacy exactly-once UI handoff.
     * @return false for an older/already-applied intent; its callback is stale.
     */
    @Synchronized
    fun markConsentWriteOutcome(
        outcome: BbcWriteOutcome,
        intentSequence: Long,
        touchedGeneration: Long,
        consumerOwnsEnable: Boolean = false,
    ): Boolean {
        // A skipped/superseded write NEVER entered a store commit: no pollution
        // evidence, no fence, no state claim and no enable ownership. Reporting
        // it here keeps the callback honest without inventing durability facts.
        if (outcome is BbcWriteOutcome.Skipped) return false
        val cur = fenced.get()
        val stale = intentSequence <= cur.appliedIntentSequence
        val grants = !stale && outcome is BbcWriteOutcome.Confirmed &&
            touchedGeneration == cur.generation
        // Only a Confirmed grant on the CURRENT generation keeps the enable
        // intent alive for the UI handoff/replay. Unknown, a stale intent, or a
        // reply from a dead service drops it — that is OFF-wins / fail-closed.
        val pending = pendingEnable
        if (pending != null && pending.sequence <= intentSequence && !grants) {
            pendingEnable = null
        }

        // (1) Durability evidence FIRST — never behind `if (stale) return false`.
        // A stale Confirmed for the CURRENT generation is not pollution: a
        // successful daemon commit cannot poison the local mMap. Unknown and a
        // reply from a DEAD instance are the cases that need a fence.
        if (!grants && (outcome is BbcWriteOutcome.Unknown || touchedGeneration != cur.generation)) {
            val fence = fenceForTouchedGeneration(cur, touchedGeneration)
            // A stale/late failure must not DOWNGRADE a newer authorisation, but
            // its pollution evidence is still recorded on the fence generation.
            val evidenceState = if (cur.state is BbcActivationConsentState.Granted ||
                cur.state is BbcActivationConsentState.Legacy
            ) cur.state else BbcActivationConsentState.Unknown
            if (evidenceState is BbcActivationConsentState.Unknown ||
                fence != cur.fenceGeneration
            ) {
                fenced.set(
                    Fenced(
                        evidenceState, cur.generation, cur.appliedIntentSequence,
                        fence, cur.classificationConfirmedGeneration,
                    ),
                )
                if (evidenceState !== cur.state) _state.value = evidenceState
            }
        }

        // (2) UI-intent outcome: no grant, no callback for a stale sequence.
        if (stale) return false
        val next = if (grants) BbcActivationConsentState.Granted else BbcActivationConsentState.Unknown
        val fenceGeneration = if (grants) -1L else fenceForTouchedGeneration(cur, touchedGeneration)
        if (grants) confirmedGrantSequence = intentSequence
        if (grants && consumerOwnsEnable) {
            // Record app-consumer ownership before publishing Granted: a rebuilt
            // UI's collector must not dispatch a second effect toggle. NEVER
            // regress a newer resync claim R when a stale Confirmed S<R lands;
            // the token now also records WHICH generation granted the enable.
            val candidate = EnableOwner(cur.generation, intentSequence)
            if (candidate.outranks(enableOwner)) enableOwner = candidate
            pendingEnable = null
        }
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
     * Fence the prefs instance the commit ACTUALLY touched. When that is the
     * current instance, the fence is the CURRENT generation, so a same-gen read
     * (which may be the polluted local mMap) can never clear it.
     *
     * A fence records POLLUTION EVIDENCE: it is cumulative and may only move
     * forward. `maxOf(cur.fenceGeneration, touchedGeneration)` is therefore
     * required for EVERY state, including Granted/Legacy: a reply from a DEAD
     * generation (touched < cur.fence) must never lower the fence while the
     * authorisation is preserved, otherwise a later same-gen read could clear
     * the weakened fence into a false grant. `<= 0` is the no-commit sentinel:
     * keep the existing fence (no commit entered, so no new evidence).
     */
    private fun fenceForTouchedGeneration(cur: Fenced, touchedGeneration: Long): Long {
        if (touchedGeneration <= 0L) return cur.fenceGeneration
        return maxOf(cur.fenceGeneration, touchedGeneration)
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
        // already advanced by an earlier outcome for this same intent. Record
        // it app-wide EVEN when the outcome fence is already newer: the app
        // consumer uses it to reject an enable driven by a stale grant.
        latestAbandonSequence = maxOf(latestAbandonSequence, intentSequence)
        // OFF/cancel in this generation blocks resync-driven enabling: a later
        // authoritative read must not resurrect the effect the user turned off.
        resyncEnableBlockedGeneration = fenced.get().generation
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
