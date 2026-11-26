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
 * Authoritative state of the RUNTIME opt-out preference key. This is a
 * DIFFERENT axis from [BbcOptInValue] (authorization) and from
 * [BbcActivationConsentState] (permission to skip the dialog):
 *  * [ABSENT] — `contains()` was false. The Hook gate reads this as "no
 *    explicit opt-out", so CONFIGURATION permits ON; it is NOT a write
 *    acknowledgement and renders UNVERIFIED.
 *  * [FALSE] / [TRUE] — definite persisted values from the same coherent read.
 *  * [UNAVAILABLE] — wrong type, read failure or binder absence. It must never
 *    become TRUE (no default-true fabrication) and never render checked.
 */
enum class BbcRuntimePrefValue {
    ABSENT, FALSE, TRUE, UNAVAILABLE;

    companion object {
        /** Bridge for legacy `Boolean?` call sites; `null` means ABSENT there. */
        internal fun fromNullable(value: Boolean?): BbcRuntimePrefValue = when (value) {
            true -> TRUE
            false -> FALSE
            null -> ABSENT
        }
    }
}

/**
 * PR #90 R5-03: immutable identity of the app-side I/O session that performed a
 * durable read or write. A generation NUMBER is not an I/O identity: a repeated
 * bind of the SAME service / SAME cached SharedPreferences with a HIGHER
 * generation is a different session but NOT fresh, clean evidence. The three
 * identities are captured together and never change for the lifetime of the
 * session.
 *
 * [serviceIdentity] and [prefsIdentity] are compared by reference (identity),
 * never by equals(): two instances with equal contents are still different I/O
 * sessions. This is what stops an optimistic local mMap from clearing its own
 * Unknown fence merely because the bind generation advanced.
 */
internal class BbcConsentSessionIdentity(
    val generation: Long,
    val serviceIdentity: Any,
    val prefsIdentity: Any,
) {
    /** Same bound instance in every dimension — used to gate auto-enable work. */
    infix fun isSameSession(other: BbcConsentSessionIdentity?): Boolean =
        other != null && generation == other.generation &&
            serviceIdentity === other.serviceIdentity &&
            prefsIdentity === other.prefsIdentity

    /**
     * Same prefs map regardless of generation. A read from the SAME prefs
     * instance may be an optimistic local map polluted by a failed commit, so
     * it can never clear a fence raised on that instance.
     */
    infix fun isSamePrefs(other: BbcConsentSessionIdentity?): Boolean =
        other != null && prefsIdentity === other.prefsIdentity
}

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
    /**
     * The same runtime key as a four-state read. This is what the effect-read
     * seam projects: ABSENT is "configuration permits ON" (unverified), while
     * UNAVAILABLE is a failure and never claims ON. Defaults keep legacy
     * Boolean-only callers compiling without fabricating a value.
     */
    val runtime: BbcRuntimePrefValue = if (coherent) {
        BbcRuntimePrefValue.fromNullable(legacyRuntimeEnabled)
    } else {
        BbcRuntimePrefValue.UNAVAILABLE
    },
) {
    companion object {
        fun failClosed(): BbcConsentDurableSnapshot = BbcConsentDurableSnapshot(
            optIn = BbcOptInValue.UNAVAILABLE,
            classification = null,
            migrated = null,
            legacyRuntimeEnabled = null,
            coherent = false,
            enablePending = null,
            runtime = BbcRuntimePrefValue.UNAVAILABLE,
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
            runtime = runtime,
        )

    /**
     * R5-03: stamp the snapshot with the FULL session identity (generation +
     * service + prefs), not just a number. Production reads use this so the
     * holder can tell a genuinely different, clean prefs instance from a
     * higher generation bound to the same polluted instance.
     */
    internal fun forSession(session: BbcConsentSessionIdentity): BbcConsentAuthoritativeRead =
        BbcConsentAuthoritativeRead(
            serviceGeneration = session.generation,
            optIn = optIn,
            classification = classification,
            migrated = migrated,
            legacyRuntimeEnabled = legacyRuntimeEnabled,
            coherent = coherent,
            enablePending = enablePending,
            runtime = runtime,
            session = session,
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
    runtime = if (coherent) {
        BbcRuntimePrefValue.fromNullable(legacyRuntimeEnabled)
    } else {
        BbcRuntimePrefValue.UNAVAILABLE
    },
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
    /** Four-state runtime key read; see [BbcRuntimePrefValue]. */
    val runtime: BbcRuntimePrefValue = if (coherent) {
        BbcRuntimePrefValue.fromNullable(legacyRuntimeEnabled)
    } else {
        BbcRuntimePrefValue.UNAVAILABLE
    },
    /**
     * R5-03: the session that ACTUALLY produced this read. `null` keeps the
     * legacy generation-only behavior for framework-free callers; production
     * always stamps it so a same-prefs higher generation cannot clear a fence.
     */
    val session: BbcConsentSessionIdentity? = null,
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
 * Rule, in priority order (R5-02 keeps Phase 3's intent-first ordering):
 *  1. a pending user intent wins (the user's latest, still-unverified choice);
 *  2. [NotGranted] (including an explicit opt-in FALSE) CLOSES the config gate:
 *     no confirmed/provisional persisted value may render checked=true;
 *  3. [BbcEffectStatus.Confirmed] supplies the acknowledged effect value;
 *  4. [BbcEffectStatus.ProvisionalRead] supplies a definite persisted value;
 *  5. [BbcEffectStatus.ProvisionalRuntimeRead] supplies the four-state reason:
 *     ABSENT means "configuration permits ON" for an AUTHORIZED install
 *     (checked=true, UNVERIFIED), UNAVAILABLE never claims ON, and TRUE/FALSE
 *     are definite values;
 *  6. Provisional/Unknown supplies NO value and renders neutral/unverified.
 *
 * Authorization is a separate axis from the runtime read: [Granted] is
 * permission to skip the consent dialog, NOT proof that the effect is on. It
 * only decides whether an ABSENT key permits the checked seed, plus the
 * "unresolved" neutral styling and, via [requiresConsentFor], whether an ON
 * tap asks for consent. An explicit always-reachable OFF never asks consent.
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
    val configGateOff = authorization is BbcActivationConsentState.NotGranted
    // A config gate closes the checkbox only when a runtime value exists. With
    // no value (Provisional/Unknown/UNAVAILABLE) it supplies NOTHING, so the
    // single writer leaves the box untouched instead of fabricating an OFF.
    val hasRuntimeValue = effect is BbcEffectStatus.Confirmed ||
        effect is BbcEffectStatus.ProvisionalRead ||
        (effect is BbcEffectStatus.ProvisionalRuntimeRead &&
            effect.runtime != BbcRuntimePrefValue.UNAVAILABLE)
    val checked = when {
        pendingIntent != null -> pendingIntent
        configGateOff -> if (hasRuntimeValue) false else null
        effect is BbcEffectStatus.Confirmed -> effect.value
        effect is BbcEffectStatus.ProvisionalRead -> effect.value
        effect is BbcEffectStatus.ProvisionalRuntimeRead -> when (effect.runtime) {
            BbcRuntimePrefValue.TRUE -> true
            BbcRuntimePrefValue.FALSE -> false
            // "No explicit opt-out" permits ON ONLY for an install the app has
            // actually authorized. An unresolved (Initializing/Unknown) state
            // stays fail-closed and supplies no checkbox value.
            BbcRuntimePrefValue.ABSENT ->
                if (authorization is BbcActivationConsentState.Granted ||
                    authorization is BbcActivationConsentState.Legacy
                ) true else null
            BbcRuntimePrefValue.UNAVAILABLE -> null
        }
        else -> null
    }
    // A provisional read is UNVERIFIED even when it supplies a checked value:
    // the absence of an explicit opt-out is configuration permission, not an
    // acknowledgement and not effect proof (issue #75 caveat is unchanged).
    val unverifiedRead = effect is BbcEffectStatus.ProvisionalRead ||
        effect is BbcEffectStatus.ProvisionalRuntimeRead
    val neutral = checked == null || unverifiedRead ||
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
 *  - A read from the SAME prefs instance can NEVER upgrade [Unknown] — even at a
 *    higher generation the local mMap may have been optimistically polluted by
 *    a failed commit. Only a stamped read from a genuinely DIFFERENT prefs
 *    instance may, via [resolve], clear a stamped fence; a stamped read meeting
 *    a sessionless fence fails closed too (generation alone never clears it).
 *    A FAILED bind-time classification COMMIT ([markInstallClassificationFailed])
 *    installs that fence so a polluted PRE_UPGRADE + marker local read can never
 *    be mistaken for [Legacy]. A NO-COMMIT bind failure (prefs unresolvable OR a
 *    fail-closed classification plan that creates no editor) installs NO fence
 *    at all: the holder stays neutral/Initializing for the next bind's
 *    authoritative resync. A NO-COMMIT consent-write outcome (the 0L sentinel,
 *    no commit entered) is treated the SAME way: it never installs the
 *    sessionless fence that would permanently fail closed for stamped reads.
 *  - A stamped fence is PERMANENT for stamped resyncs. Recovery requires a
 *    DIFFERENT prefs instance (a stamped authoritative read) OR a fresh
 *    [markConsentWriteOutcome] with Confirmed on the current generation: the
 *    latter clears fenceGeneration/fenceSession and grants, owning the phase-2
 *    enable. Unstamped (framework-free) reads keep the older generation-only
 *    rule, but production always stamps and is held to the stricter one.
 *  - [Legacy] mirrors the Hook gate: PRE_UPGRADE_EXISTING needs the durable
 *    migration marker too; only MIGRATED/EXPLICITLY_GRANTED open without it.
 *  - A generation change drops Granted/Legacy to [Initializing] (must resync)
 *    but PRESERVES [Unknown] (the fence outlives binder death/reconnect).
 *  - [abandonIntent] advances the outcome fence (cancel / OFF / show failure)
 *    and invalidates the pending enable for that intent: zero writes, zero
 *    state claims, and a late commit result can no longer land.
 *  - An ENABLE is recorded app-scoped at accept time. [notePendingEnable] is
 *    PRODUCTION: Main calls it to record the app-scoped GRANT ATTEMPT sequence
 *    (sequence bookkeeping only — it never writes the effect preference or
 *    acknowledges a consent commit). The consume seams ([consumePendingEnable],
 *    [consumePendingEnableForReplay], [isEnableHandledBy]) remain TEST-ONLY: no
 *    production code consumes that handoff record. Phase 2: the app IO consumer
 *    that confirms the opt-in owns the actual effect write; UI callbacks and
 *    state replay are presentation-only and can never toggle it a second time.
 *    A grant alone is never treated as effect proof.
 *  - Cold-start convergence ALSO requires the app-only durable enable-pending
 *    marker from the SAME coherent read as optin=true. The marker is cleared
 *    in the same commit as a user OFF, so an authoritative resync can never
 *    reverse a durable OFF; it is NOT a Hook authorization key.
 *  - Enable ownership is a GENERATION-SCOPED monotonic token shared by the
 *    consumer and the resync. A newer claim is never regressed by a later
 *    Confirmed intent on the same generation, but a rebind may claim the same
 *    still-owed enable once on a NEW generation.
 *  - R5-04: a grant ATTEMPT is recorded at accept time in the app-scoped intent
 *    domain even when its reply is Unknown/lost. It is promoted to an EFFECTIVE
 *    resync reason ONLY by a fresh authoritative read that verifies durable
 *    optin=true AND the enable-pending marker. No Confirmed consent/effect
 *    state is fabricated from an unverified local mMap. An OFF OLDER than such
 *    a verified grant is the prior setting and cannot block resync; a NEWER
 *    OFF still does.
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
         * from the SAME prefs instance is optimistic-local evidence and can NEVER
         * clear it, even at a higher generation; only a genuinely different
         * prefs instance may (see [fenceSession]).
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
        /**
         * R5-03: identity of the session whose failed durable COMMIT installed
         * the current Unknown fence. A stamped read from the SAME prefs instance
         * (even at a higher generation) is optimistic-local evidence and can
         * never clear it; clearing requires a genuinely DIFFERENT prefs instance
         * or a fresh Confirmed consent write. A stamped read meeting a
         * sessionless fence ALSO fails closed — a generation number alone may
         * never lift it. `null` is the legacy generation-only case for unstamped
         * callers only: production stamps the identity whenever a commit
         * actually entered, and a no-commit bind failure (unresolved prefs or a
         * fail-closed plan) never installs a fence at all (there is nothing
         * optimistic to fence).
         */
        val fenceSession: BbcConsentSessionIdentity? = null,
    )

    private val fenced = AtomicReference(
        Fenced(BbcActivationConsentState.Initializing, 0L, 0L, -1L, -1L, null),
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
     * Sequence of the last CONFIRMED consent grant (a Confirmed reply only; a
     * verified accept-time attempt lives in [verifiedGrantSequence]). The
     * AUTHORITATIVE grant reason a resync compares OFF/cancel evidence against
     * is [effectiveGrantSequence] — the max of this and [verifiedGrantSequence]
     * — never a freshly minted sequence that would be newer than every recorded
     * abandon and make the OFF guard inert.
     */
    @Volatile
    private var confirmedGrantSequence: Long = 0L

    /**
     * R5-04: highest consent-grant ATTEMPT recorded at accept time in the SAME
     * app-scoped intent domain (via [notePendingEnable], which Main calls). It
     * is kept even when the commit's reply is Unknown or never returns, because
     * the commit may still have landed durably before a rebind. It becomes an
     * effective resync reason ONLY after [resolve] verifies durable optin=true
     * AND the enable-pending marker from a FRESH session's coherent read.
     */
    @Volatile
    private var pendingGrantAttemptSequence: Long = 0L

    /**
     * R5-04: the grant attempt promoted by such a verified read. Deliberately
     * separate from [confirmedGrantSequence] so an unverified reply can never
     * masquerade as a Confirmed consent grant.
     */
    @Volatile
    private var verifiedGrantSequence: Long = 0L

    /** The single generation whose resync is allowed to drive a runtime enable. */
    @Volatile
    private var resyncEnableGeneration: Long = -1L

    /** Generation in which the user (or cancel) said OFF: resync may not re-enable it. */
    @Volatile
    private var resyncEnableBlockedGeneration: Long = -1L

    /**
     * R5-04: sequence of the OFF/cancel that installed
     * [resyncEnableBlockedGeneration]. A NEWER verified grant clears the
     * generation block; an OFF that is truly newer than the grant keeps it.
     */
    @Volatile
    private var resyncEnableBlockedSequence: Long = -1L

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

    /**
     * Test/telemetry seam: the CONFIRMED grant sequence only. The authoritative
     * grant reason a cold-start resync actually compares OFFs against is
     * [effectiveGrantSequence], which also includes verified accept-time
     * attempts promoted from a coherent durable snapshot.
     */
    internal fun confirmedGrantSequence(): Long = confirmedGrantSequence

    /**
     * R5-04: the effective grant reason for sequence-aware resync comparison.
     * A Confirmed grant wins; otherwise a grant attempt promoted by a verified
     * durable optin+marker read is used. The promoted reason is the HIGHEST
     * recorded attempt, not necessarily the exact attempt that wrote the marker;
     * the OFF guards ([latestAbandonSequence] and
     * [resyncEnableBlockedSequence]) still bound it. An unverified local read
     * contributes nothing (both fields stay at `0` for a fresh process).
     */
    internal fun effectiveGrantSequence(): Long =
        maxOf(confirmedGrantSequence, verifiedGrantSequence)

    /** Test/telemetry seam: the generation owning the current (Unknown) fence. */
    internal fun currentFenceGeneration(): Long = fenced.get().fenceGeneration

    /** R5-03 test/telemetry seam: the session that owns the current fence. */
    internal fun currentFenceSession(): BbcConsentSessionIdentity? = fenced.get().fenceSession

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
     * [reasonSequence] is the AUTHORITATIVE grant reason (a Confirmed reply OR a
     * verified accept-time attempt from [effectiveGrantSequence]) — OFF/cancel
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
     * Handoff bookkeeping from accept(). The phase-2 effect write is owned and
     * driven by the app IO consumer in [BbcToggleCoordinator], never by this
     * record; the grant alone is only AUTHORIZATION, not proof that the runtime
     * preference was written. R5-04: the same call ALSO records the app-scoped
     * GRANT ATTEMPT sequence, so a rebind that loses the commit reply can still
     * recognise a newer verified grant in the fresh session's resync. That is
     * sequence bookkeeping only — it never grants or acknowledges a write.
     */
    @Synchronized
    fun notePendingEnable(sequence: Long, generation: Long) {
        pendingEnable = PendingEnable(sequence, generation)
        if (sequence > pendingGrantAttemptSequence) pendingGrantAttemptSequence = sequence
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
        fenced.set(
            Fenced(
                next, generation, cur.appliedIntentSequence, cur.fenceGeneration, -1L,
                cur.fenceSession,
            ),
        )
        _state.value = next
    }

    /**
     * Authoritative resolution from durable keys. A read from an older service
     * generation is ignored outright (never trust a stale instance). A stamped
     * read from the SAME prefs instance cannot clear [Unknown], even at a higher
     * generation: that is exactly the polluted local-mMap case the fence exists
     * for. A stamped read may clear a stamped fence ONLY from a genuinely
     * DIFFERENT prefs instance; meeting a sessionless fence it fails closed too
     * (generation alone never clears it). The other explicit recovery is a fresh
     * Confirmed consent write ([markConsentWriteOutcome]) on the current
     * generation. No-commit bind failures install no fence, so the next bind's
     * authoritative resync decides freely from durable keys. Unstamped
     * (framework-free) reads keep the older generation-only rule.
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
        // R5-03: a generation number alone is NOT proof of fresh evidence. When
        // the READ is stamped, the fence may be lifted ONLY by a genuinely
        // DIFFERENT prefs instance. A repeated bind of the same prefs with a
        // higher generation reads the same optimistic local mMap and must stay
        // fenced. A stamped read meeting an Unknown fence with NO session stamp
        // fails closed too: generation alone may never clear such a fence.
        // Legacy callers (unstamped reads) keep the old generation rule.
        val stampedRead = read.session
        val stampedFence = cur.fenceSession
        val fenceIsCurrent = when {
            cur.state !is BbcActivationConsentState.Unknown -> false
            stampedRead != null ->
                stampedFence == null ||
                    stampedRead isSamePrefs stampedFence ||
                    stampedRead.generation <= cur.fenceGeneration
            else -> read.serviceGeneration <= cur.fenceGeneration
        }
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
        // R5-04 review: `next is Granted` ALONE is not proof of a fresh grant.
        // Granted is a singleton, so the `fenceIsCurrent -> cur.state`,
        // `!coherent || UNAVAILABLE -> cur.state` and unresolved `else` branches
        // all yield the SAME object when the holder was already Granted. This
        // boolean mirrors ONLY the `optin == TRUE` branch of a COHERENT,
        // unfenced read: promotion must be derived from THIS read, never
        // inherited from cur.state or an optimistic local mMap.
        val grantedFromThisRead = !fenceIsCurrent && read.coherent &&
            read.optIn == BbcOptInValue.TRUE
        // R5-04: promote the app-scoped GRANT ATTEMPT only when this FRESH,
        // coherent read verifies BOTH durable optin=true and the enable-pending
        // marker, and the resolution actually authorizes. This is the case the
        // old Confirmed grant reply was lost to a rebind but the commit landed.
        // No Confirmed grant/effect is fabricated from an optimistic local map:
        // only the effective resync sequence moves. An OFF OLDER than the
        // verified grant was the prior setting and must stop blocking; a NEWER
        // OFF is also fenced by latestAbandonSequence and keeps the block.
        if (grantedFromThisRead &&
            read.enablePending == true &&
            pendingGrantAttemptSequence > verifiedGrantSequence
        ) {
            verifiedGrantSequence = pendingGrantAttemptSequence
            if (resyncEnableBlockedSequence < verifiedGrantSequence) {
                resyncEnableBlockedGeneration = -1L
                resyncEnableBlockedSequence = -1L
            }
        }
        if (next === cur.state) return
        val becomesUnknown = next is BbcActivationConsentState.Unknown
        val fenceGeneration = if (becomesUnknown) read.serviceGeneration else -1L
        val fenceSession = if (becomesUnknown) read.session else null
        fenced.set(
            Fenced(
                next, cur.generation, cur.appliedIntentSequence,
                fenceGeneration, cur.classificationConfirmedGeneration, fenceSession,
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
     * Install an Unknown fence attributed to this prefs instance instead: no read
     * from the same prefs can lift it, and no consent is skipped. Only a stamped
     * authoritative resync from a DIFFERENT prefs instance may clear it; a fresh
     * Confirmed consent write on the current generation is the explicit recovery.
     * Callers MUST invoke this only when an edit()/commit() actually ENTERED and
     * failed. A no-commit bind failure (prefs unresolvable, or a fail-closed
     * classification plan that creates no editor) has no optimistic map to fence
     * and must leave the holder neutral; calling here with a null [session] would
     * install a sessionless fence that no stamped resync can ever clear.
     * An already-authorized [BbcActivationConsentState.Granted] /
     * [BbcActivationConsentState.Legacy] is left untouched: a late bind-time
     * failure must not regress a confirmed grant or a durable Legacy resync.
     * This does NOT touch the user-intent sequence — a bind-time bookkeeping
     * failure is not a consent-write outcome and must not invalidate a newer
     * user intent.
     */
    @Synchronized
    fun markInstallClassificationFailed(
        generation: Long,
        session: BbcConsentSessionIdentity? = null,
    ) {
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
                generation, cur.classificationConfirmedGeneration, session,
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
     * A NO-COMMIT reply (`touchedGeneration <= 0`, outcome not Confirmed) is
     * NEITHER concern: no commit entered, so there is no pollution evidence and
     * no reason to claim Unknown. It only invalidates the pending UI handoff and
     * resolves the intent sequence; state, fence generation and fence session
     * are left exactly as they were (an existing stronger fence is preserved).
     * This keeps stamped authoritative resyncs from failing closed forever on
     * a sessionless fence installed by a failed grant that never wrote anything.
     *
     * @param consumerOwnsEnable when true (the app coordinator consumer), a
     *   Confirmed grant consumes the pending UI handoff and records that the
     *   effect enable is driven by the consumer, BEFORE Granted is published.
     *   Direct holder callers keep the legacy exactly-once UI handoff.
     * @return false for an older/already-applied intent; its callback is stale.
     *   A fresh no-commit outcome returns true so the caller receives an honest
     *   Unknown result (never a grant), without any holder mutation beyond the
     *   intent sequence and pending-handoff invalidation.
     */
    @Synchronized
    fun markConsentWriteOutcome(
        outcome: BbcWriteOutcome,
        intentSequence: Long,
        touchedGeneration: Long,
        consumerOwnsEnable: Boolean = false,
        session: BbcConsentSessionIdentity? = null,
    ): Boolean {
        // A skipped/superseded write NEVER entered a store commit: no pollution
        // evidence, no fence, no state claim and no enable ownership. Reporting
        // it here keeps the callback honest without inventing durability facts.
        if (outcome is BbcWriteOutcome.Skipped) return false
        val cur = fenced.get()
        val stale = intentSequence <= cur.appliedIntentSequence
        val grants = !stale && outcome is BbcWriteOutcome.Confirmed &&
            touchedGeneration == cur.generation

        // A NO-COMMIT reply (GalaxyApp's 0L sentinel) means NO edit()/commit()
        // entered: there is no optimistic local mMap to fence. Letting it fall
        // through to the Unknown transition below would install a SESSIONLESS
        // fence (touchedGeneration <= 0 keeps fenceGeneration/fenceSession at
        // their prior values) that EVERY stamped production read fails closed
        // against — permanently, across rebinds, even with durable optin=true.
        // Instead: invalidate the pending UI handoff so a failed grant cannot
        // drive phase2, resolve the intent in the sequence domain, and leave
        // the state AND any existing (stronger) fence completely untouched.
        // A fresh no-commit outcome is still reported to its caller (an honest
        // Unknown callback, never a grant); a stale one gets no callback.
        if (touchedGeneration <= 0L && outcome !is BbcWriteOutcome.Confirmed) {
            val pending = pendingEnable
            if (pending != null && pending.sequence <= intentSequence) pendingEnable = null
            if (stale) return false
            fenced.set(cur.copy(appliedIntentSequence = intentSequence))
            return true
        }
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
                        fenceSessionFor(cur, touchedGeneration, session),
                    ),
                )
                if (evidenceState !== cur.state) _state.value = evidenceState
            }
        }

        // (2) UI-intent outcome: no grant, no callback for a stale sequence.
        if (stale) return false
        val next = if (grants) BbcActivationConsentState.Granted else BbcActivationConsentState.Unknown
        val fenceGeneration = if (grants) -1L else fenceForTouchedGeneration(cur, touchedGeneration)
        val fenceSession = if (grants) null else fenceSessionFor(cur, touchedGeneration, session)
        if (grants) {
            confirmedGrantSequence = intentSequence
            // A direct holder caller need not go through notePendingEnable; keep
            // the attempt domain in sync so a later verified resync can use it.
            if (intentSequence > pendingGrantAttemptSequence) {
                pendingGrantAttemptSequence = intentSequence
            }
        }
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
                cur.classificationConfirmedGeneration, fenceSession,
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
     * R5-03: the fence's SESSION identity moves with the (monotonic) fence
     * generation. A no-commit reply or a reply from a DEAD generation must keep
     * the identity of the instance that actually owns the fence.
     */
    private fun fenceSessionFor(
        cur: Fenced,
        touchedGeneration: Long,
        session: BbcConsentSessionIdentity?,
    ): BbcConsentSessionIdentity? {
        if (touchedGeneration <= 0L || session == null) return cur.fenceSession
        if (cur.fenceSession != null && cur.fenceGeneration > touchedGeneration) return cur.fenceSession
        return session
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
        // R5-04: remember the OFF's sequence too, so a NEWER verified grant can
        // clear this block while an older grant cannot.
        resyncEnableBlockedGeneration = cur.generation
        resyncEnableBlockedSequence = maxOf(resyncEnableBlockedSequence, intentSequence)
        val pending = pendingEnable
        if (pending != null && pending.sequence <= intentSequence) pendingEnable = null
        if (intentSequence <= cur.appliedIntentSequence) return
        fenced.set(
            Fenced(
                cur.state, cur.generation, intentSequence, cur.fenceGeneration,
                cur.classificationConfirmedGeneration, cur.fenceSession,
            ),
        )
    }
}
