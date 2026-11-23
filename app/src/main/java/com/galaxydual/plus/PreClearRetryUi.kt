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
