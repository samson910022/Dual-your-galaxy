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
