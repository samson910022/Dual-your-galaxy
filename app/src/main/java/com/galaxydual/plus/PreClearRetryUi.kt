// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// R4-1 (E01/E02): framework-free helpers shared by MainActivity and PolicyActivity.
// No android.* import on purpose, so both the consent gating model and the
// failed-probe dialog *configuration* are JVM-testable.
//
// DEVICE-UNVERIFIED: real AlertDialog rendering, button placement and rotation
// behavior are NOT exercised by JVM tests; instrumentation (androidTest) is
// still needed to confirm the on-device UI shape. These helpers only lock the
// configuration that both Activities hand to the dialog.
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for opting a fast path into the additive/no-removal
 * consent flag (R4/E02).
 *
 * [snapKnown] is the snapshot's Known bit and [delta] is the removal set that is
 * actually handed to [buildFastPathConsent]. `true` requires a KNOWN snapshot
 * AND an empty removal set: `noRemovalKnown = snap.known && delta.isEmpty()`.
 *
 * An Unknown snapshot computes `delta = emptySet()` only as an artifact of the
 * failed re-read, so it MUST NEVER opt in — otherwise a "no removal" consent
 * would be manufactured from a snapshot we cannot trust (D03 fail-open).
 *
 * Note for the write path's `candidate.isEmpty()` branch: an empty candidate is
 * NOT evidence of additivity. With candidate=∅ the removal set is `delta = old`,
 * so a non-empty persisted draft is still destructive. Callers must pass that
 * real [delta], never `true` merely because the candidate is empty.
 */
internal fun noRemovalAllowed(snapKnown: Boolean, delta: Set<String>): Boolean =
    snapKnown && delta.isEmpty()

/**
 * Framework-free description of the Failed-probe / Unknown warning dialog.
 *
 * [showProceed] is permanently `false`: a Failed probe can never authorize a
 * proceed at either the Activity fence or the VM fence, so the dialog offers
 * only [retryLabelRes] (re-run the probe / refresh the snapshot) and
 * [cancelLabelRes]. The dialog builder wires ONLY these two buttons, so the
 * shape is safe by construction even if a caller tried to forge the flag.
 */
internal data class ProbeFailedDialogConfig(
    val titleRes: Int,
    val messageRes: Int,
    val retryLabelRes: Int,
    val cancelLabelRes: Int,
    val showProceed: Boolean = false,
)

/**
 * [write] selects the clear vs. write wording; [unknown] selects the "refresh"
 * label for an Unknown snapshot instead of the "retry probe" label for a Failed
 * probe. Both message variants keep the cold-start DELETE risk sentences
 * verbatim (see the three locale `strings.xml` files).
 */
internal fun probeFailedDialogConfig(write: Boolean, unknown: Boolean): ProbeFailedDialogConfig =
    ProbeFailedDialogConfig(
        titleRes = if (write) {
            R.string.pre_write_probe_failed_title
        } else {
            R.string.pre_clear_probe_failed_title
        },
        messageRes = if (write) {
            R.string.pre_write_probe_failed_message
        } else {
            R.string.pre_clear_probe_failed_message
        },
        retryLabelRes = if (unknown) {
            R.string.pre_clear_unknown_refresh_retry
        } else {
            R.string.pre_clear_probe_failed_retry
        },
        cancelLabelRes = R.string.pre_clear_cancel,
    )

// R5-0 (F01): framework-free decision model for the Unknown-warning Retry.
// The retry is an authoritative re-read, NOT a destructive re-entry: only a
// settled KNOWN snapshot may continue into a fresh warning. Corrupt and
// still-unavailable snapshots get non-recursive guidance (no dialog restart).
internal enum class PolicyWarningRetryDecision {
    CONTINUE,
    CORRUPT_GUIDE,
    UNAVAILABLE_GUIDE,
}

internal fun policyWarningRetryDecision(settled: PolicyRefreshSettled): PolicyWarningRetryDecision =
    if (settled.corrupt) {
        PolicyWarningRetryDecision.CORRUPT_GUIDE
    } else if (settled.known) {
        PolicyWarningRetryDecision.CONTINUE
    } else {
        PolicyWarningRetryDecision.UNAVAILABLE_GUIDE
    }

/**
 * Dispatches exactly one arm of [decision], and NEVER when the host is gone.
 * In particular `CONTINUE` requires BOTH a Known settle AND a live host, so a
 * late settle after Activity finish cannot re-enter the warning pipeline.
 */
internal fun applyWarningRetryDecision(
    decision: PolicyWarningRetryDecision,
    hostAlive: Boolean,
    onContinue: () -> Unit,
    onCorrupt: () -> Unit,
    onUnavailable: () -> Unit,
) {
    if (!hostAlive) return
    when (decision) {
        PolicyWarningRetryDecision.CONTINUE -> onContinue()
        PolicyWarningRetryDecision.CORRUPT_GUIDE -> onCorrupt()
        PolicyWarningRetryDecision.UNAVAILABLE_GUIDE -> onUnavailable()
    }
}

// R6-1 (G01): framework-free, Activity-scoped one-shot token that ties the
// "re-entry allowed" decision to the SPECIFIC warning whose Retry tap earned
// it. A settled retry may consume only the token captured at its own tap; a
// late or duplicate settle for an older warning is ignored. Installing a new
// warning (user tap OR auto re-entry) advances the generation and re-arms the
// token, so a SECOND cycle within one action is allowed without weakening the
// duplicate-settle guard. No android.* import on purpose (JVM-testable).
internal data class WarningRetryToken(val generation: Int, val consumed: Boolean = false)

internal class WarningRetryOneShot {
    private val ref = AtomicReference(WarningRetryToken(generation = 0))

    /** Install a new warning: advance the generation and re-arm the token. */
    fun onWarningInstalled() {
        ref.updateAndGet { it.copy(generation = it.generation + 1, consumed = false) }
    }

    /** Capture the token at Retry tap, BEFORE the authoritative refresh. */
    fun capture(): WarningRetryToken = ref.get()

    /** Single CAS covers BOTH a generation change and an already-consumed token. */
    fun tryConsume(token: WarningRetryToken): Boolean =
        ref.compareAndSet(token, token.copy(consumed = true))

    /** Test accessor: the generation advances once per installed warning. */
    val currentGeneration: Int get() = ref.get().generation
}

/**
 * #77 (H02): arm the per-warning one-shot ONLY when a new warning request is
 * actually accepted by the re-entry guard (`consentResetAllowedForNewWarning`,
 * i.e. `!pendingClearConfirm`). The old code called [WarningRetryOneShot.onWarningInstalled]
 * unconditionally at every `start*Warning` entry, including stale auto-restarts
 * that the guard then rejected. Such a rejected restart burned the generation,
 * so the live Retry tap's captured token no longer matched and its legitimate
 * re-entry was silently dropped. Gating the re-arm on the same accept decision
 * keeps one retry per *installed* warning without weakening the duplicate guard.
 *
 * Framework-free (no android.* import) so the rejected-restart contract is
 * directly JVM-testable; both Activities call this at their accept sites.
 */
internal fun tryAcceptWarning(
    oneShot: WarningRetryOneShot,
    newWarningAllowed: Boolean,
): Boolean {
    if (!newWarningAllowed) return false
    oneShot.onWarningInstalled()
    return true
}

// ---------------------------------------------------------------------------
// Follow-up A (PR #89): identity-bound warning restart / retry controller.
// ---------------------------------------------------------------------------
//
// The old Activities kept ONE mutable `pendingWarningRestart: (() -> Unit)?`
// slot, written at `start*Warning` ENTRY (before the re-entry guard) and read
// later at Handler delivery and from Retry callbacks. That made a late settle
// for an old action invoke a NEW action's callback: tap WRITE -> Unknown
// warning (gen G) -> Retry refresh in flight -> tap CLEAR (entry overwrites the
// slot, guard rejects) -> refresh settles Known -> the consumed token invokes
// the CLEAR callback, i.e. a destructive clear during a write retry.
//
// This controller removes the slot entirely. Every live restart is a
// [WarningRestartRequest] with a MONOTONIC id, installed ONLY at the accept
// seam (after `tryAcceptWarning` succeeds). Installing a new request bumps the
// token id and clears the scheduled state, so an already-posted restart token
// from the previous action can never deliver again. Both auto restarts and
// Retry taps are bound to the captured request id + a live host.
//
// IMPORTANT: [WarningRequestIdentity.generation] is the generation of the
// WarningRetryOneShot at the moment the warning was ACCEPTED. It is NOT the
// WarningRetryOneShot generation itself: that one advances once per installed
// warning and guards the one retry token per warning, whereas this generation
// is copied into the immutable request identity for correlation/diagnostics.
// No android.* import on purpose (fully JVM-testable).

/** Which destructive entry installed the request (used only for correlation). */
internal enum class WarningRestartAction { WRITE, CLEAR, EDITOR_WRITE }

internal data class WarningRequestIdentity(
    val id: Long,
    val action: WarningRestartAction,
    val candidateKey: String?,
    val generation: Int,
)

internal data class WarningRestartRequest(
    val identity: WarningRequestIdentity,
    val callback: () -> Unit,
)

internal class WarningRestartController {
    private data class State(
        val request: WarningRestartRequest? = null,
        /** Bumped on every install/clear so an older token is stale by construction. */
        val tokenId: Long = 0L,
        val scheduledTokenId: Long? = null,
        val scheduledRequestId: Long? = null,
        val autoRestartsUsed: Int = 0,
        val consumedRetryRequestId: Long? = null,
    )

    private val nextRequestId = AtomicLong(0L)
    private val state = AtomicReference(State())

    /** Test/telemetry accessor: the currently live request identity, if any. */
    val currentIdentity: WarningRequestIdentity? get() = state.get().request?.identity

    /** Test/telemetry accessor: the currently scheduled token, if any. */
    val scheduledTokenId: Long? get() = state.get().scheduledTokenId

    /**
     * Install a new live request. MUST be called only at an accept seam — a
     * request rejected by `consentResetAllowedForNewWarning` must not call
     * this, otherwise it would steal the previous action's restart identity.
     * Any previously queued token is invalidated (token id bumped, scheduled
     * state cleared) and the new request gets a fresh Retry token.
     *
     * [resetBudget] is `true` ONLY for a genuine user entry tap. Auto-restart
     * delivery and Retry re-entry pass `false`: they still install a NEW
     * identity (so a settled token from the old request cannot be reused), but
     * they CARRY OVER the consumed auto-restart budget. Without that carry-over
     * an auto-restart would re-install with a fresh budget and defeat
     * [MAX_AUTO_WARNING_RESTARTS], producing an unbounded re-prompt loop.
     *
     * FAST-PATH CALLERS: an accepted destructive action can settle with NO
     * warning at all (a Known snapshot / empty removal set, i.e. a no-op). Such
     * a call still goes through this seam with `resetBudget = true` so the
     * accepted action owns a fresh identity and budget; it passes
     * `generation = WarningRetryOneShot.currentGeneration` for CORRELATION ONLY.
     * This method NEVER touches [WarningRetryOneShot] (it never did): the
     * one-shot generation advances only for warnings actually shown, via
     * [tryAcceptWarning]. A no-warning fast path must therefore pass through
     * here without calling [WarningRetryOneShot.onWarningInstalled].
     *
     * CANCELLED REQUESTS: a cancel/no-op must call [revoke] with the captured
     * request id, NOT [clear], so a newer accepted action is never dropped.
     */
    fun install(
        action: WarningRestartAction,
        candidateKey: String?,
        generation: Int,
        callback: () -> Unit,
        resetBudget: Boolean,
    ): WarningRestartRequest {
        val request = WarningRestartRequest(
            identity = WarningRequestIdentity(
                id = nextRequestId.incrementAndGet(),
                action = action,
                candidateKey = candidateKey,
                generation = generation,
            ),
            callback = callback,
        )
        while (true) {
            val current = state.get()
            val next = State(
                request = request,
                tokenId = current.tokenId + 1L,
                autoRestartsUsed = if (resetBudget) 0 else current.autoRestartsUsed,
            )
            if (state.compareAndSet(current, next)) return request
        }
    }

    /**
     * Capture the current request + a fresh token and hand an invokable body to
     * [post]. Fails (without posting) when there is no live request, the host is
     * dying, or this request has already used its auto-restart budget. The
     * budget is consumed here, at schedule time, so repeated aborts for the
     * same warning cannot loop forever; a new accepted request resets it.
     *
     * The posted [body] closes over the host-liveness snapshot taken here; the
     * Activity also re-checks `isFinishing/isDestroyed` immediately before
     * invoking it, so a host that dies in between never delivers.
     *
     * [expectedRequestId] is the identity whose abort/WRITE is being handled.
     * When non-null, this method fails WITHOUT posting or consuming budget if
     * the live request is a different one — this is what stops a stale abort
     * (e.g. a cancelled CLEAR) from scheduling a new restart for a live WRITE.
     * Passing `null` keeps the original behavior for pre-existing callers.
     *
     * COMPATIBILITY: the 3-parameter overload keeps the [post] lambda LAST, so
     * every existing `schedule(...) { tokenId, requestId, body -> ... }` trailing
     * lambda call still compiles unchanged. Only callers that need identity
     * binding pass the 4th [expectedRequestId] argument.
     */
    fun schedule(
        hostAlive: Boolean,
        maxRestarts: Int,
        post: (tokenId: Long, requestId: Long, body: () -> Unit) -> Unit,
    ): Boolean = schedule(hostAlive, maxRestarts, post, expectedRequestId = null)

    fun schedule(
        hostAlive: Boolean,
        maxRestarts: Int,
        post: (tokenId: Long, requestId: Long, body: () -> Unit) -> Unit,
        expectedRequestId: Long? = null,
    ): Boolean {
        if (!hostAlive || maxRestarts <= 0) return false
        var capturedRequest: WarningRestartRequest
        var tokenId: Long
        while (true) {
            val current = state.get()
            val request = current.request ?: return false
            // Identity-bound abort: a stale caller must not touch a newer
            // request's token, budget or scheduled state (checked inside the
            // CAS so a concurrent install cannot be raced past it).
            if (expectedRequestId != null && request.identity.id != expectedRequestId) return false
            // Same semantics as canAutoRestart(previousCount): one auto restart
            // per accepted request; ONLY a genuine entry tap re-arms via
            // install(resetBudget = true). Re-entry from a posted restart or a
            // Retry tap installs with resetBudget = false and carries this count.
            if (current.autoRestartsUsed >= maxRestarts) return false
            tokenId = current.tokenId + 1L
            capturedRequest = request
            val next = current.copy(
                tokenId = tokenId,
                scheduledTokenId = tokenId,
                scheduledRequestId = request.identity.id,
                autoRestartsUsed = current.autoRestartsUsed + 1,
            )
            if (state.compareAndSet(current, next)) break
        }
        val aliveAtSchedule = hostAlive
        post(tokenId, capturedRequest.identity.id) {
            deliver(tokenId, capturedRequest.identity.id, aliveAtSchedule)
        }
        return true
    }

    /**
     * Deliver a posted auto restart. The callback runs ONLY when the token is
     * the currently scheduled one, the request id still matches the live
     * request, and the host is alive. A new user tap (install) or [clear]
     * invalidates the token, so a stale post is a silent no-op.
     */
    fun deliver(tokenId: Long, requestId: Long, hostAlive: Boolean): Boolean {
        var callback: () -> Unit
        while (true) {
            val current = state.get()
            val request = current.request
            if (request == null || request.identity.id != requestId) return false
            if (current.scheduledTokenId != tokenId || current.scheduledRequestId != requestId) return false
            callback = request.callback
            // Consume the token even when the host is gone: a dead-host
            // delivery must not remain resumable by a later call.
            val next = current.copy(scheduledTokenId = null, scheduledRequestId = null)
            if (state.compareAndSet(current, next)) break
        }
        if (!hostAlive) return false
        callback()
        return true
    }

    /** Request id to capture at a Retry tap (or when installing a dialog). */
    fun retryRequestId(): Long? = state.get().request?.identity?.id

    /** Test/telemetry accessor: the live request id, if any. */
    fun currentRequestId(): Long? = state.get().request?.identity?.id

    /** True only for a non-null id that is still the live request identity. */
    fun isLive(requestId: Long?): Boolean {
        if (requestId == null) return false
        val live = state.get().request ?: return false
        return live.identity.id == requestId
    }

    /**
     * Identity-bound equivalent of [deliver] for Retry taps: the captured id
     * must still be the live request, no Retry for this request may have been
     * consumed yet, and the host must be alive. Re-installing a new warning
     * (e.g. the accepted auto-restart) gives the next cycle a fresh id.
     */
    fun consumeRetry(requestId: Long, hostAlive: Boolean): Boolean {
        if (!hostAlive) return false
        var callback: () -> Unit
        while (true) {
            val current = state.get()
            val request = current.request
            if (request == null || request.identity.id != requestId) return false
            if (current.consumedRetryRequestId == requestId) return false
            callback = request.callback
            val next = current.copy(consumedRetryRequestId = requestId)
            if (state.compareAndSet(current, next)) break
        }
        callback()
        return true
    }

    /**
     * CANCEL / NO-OP seam: conditionally drop ONLY [requestId]'s request.
     *
     * Unlike [clear], this never globally drops state: a stale or null id is a
     * no-op that returns `false` with ZERO state mutation, so a cancel arriving
     * late can never revoke a newer accepted action. On success the request is
     * cleared, the token id is bumped (so any already-posted restart token is
     * stale), the scheduled token/request ids and the consumed-Retry mark are
     * cleared. [autoRestartsUsed] is deliberately RETAINED — a cancel is not a
     * genuine entry tap and must not hand a fresh auto-restart budget to the
     * next action.
     */
    fun revoke(requestId: Long?): Boolean {
        if (requestId == null) return false
        while (true) {
            val current = state.get()
            val live = current.request ?: return false
            if (live.identity.id != requestId) return false
            val next = current.copy(
                request = null,
                tokenId = current.tokenId + 1L,
                scheduledTokenId = null,
                scheduledRequestId = null,
                consumedRetryRequestId = null,
                // autoRestartsUsed intentionally carried, NOT reset.
            )
            if (state.compareAndSet(current, next)) return true
        }
    }

    /** onDestroy: drop the live request and invalidate any queued token. */
    fun clear() {
        while (true) {
            val current = state.get()
            if (state.compareAndSet(current, State(tokenId = current.tokenId + 1L))) return
        }
    }
}
