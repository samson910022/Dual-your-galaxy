// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// R4-1 (E01): JVM-testable configuration of the Failed-probe / Unknown warning
// dialog. REAL AlertDialog rendering, button placement, taps and rotation
// behavior are DEVICE-UNVERIFIED / NOT exercised here — those need the
// androidTest instrumentation suite. This only locks the descriptor both
// Activities hand to showPreClearProbeFailedWarning.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProbeFailedDialogConfigTest {

    @Test
    fun probeFailedDialogConfig_hasRetryAndCancel_noProceed() {
        val clearFailed = probeFailedDialogConfig(write = false, unknown = false)
        val clearUnknown = probeFailedDialogConfig(write = false, unknown = true)
        val writeFailed = probeFailedDialogConfig(write = true, unknown = false)
        val writeUnknown = probeFailedDialogConfig(write = true, unknown = true)

        // No variant offers a proceed affordance. A Failed probe can never
        // complete a proceed at either the Activity or the VM fence, so the
        // only positive action is retry/refresh; cancel is always present.
        listOf(clearFailed, clearUnknown, writeFailed, writeUnknown).forEach { cfg ->
            assertFalse(cfg.showProceed)
            assertEquals(R.string.pre_clear_cancel, cfg.cancelLabelRes)
        }

        assertEquals(R.string.pre_clear_probe_failed_title, clearFailed.titleRes)
        assertEquals(R.string.pre_clear_probe_failed_message, clearFailed.messageRes)
        assertEquals(R.string.pre_clear_probe_failed_retry, clearFailed.retryLabelRes)
        // Unknown snapshot uses the refresh label, not the retry-probe label.
        assertEquals(R.string.pre_clear_unknown_refresh_retry, clearUnknown.retryLabelRes)
        assertEquals(R.string.pre_clear_probe_failed_title, clearUnknown.titleRes)
        assertEquals(R.string.pre_clear_probe_failed_message, clearUnknown.messageRes)

        assertEquals(R.string.pre_write_probe_failed_title, writeFailed.titleRes)
        assertEquals(R.string.pre_write_probe_failed_message, writeFailed.messageRes)
        assertEquals(R.string.pre_clear_probe_failed_retry, writeFailed.retryLabelRes)
        assertEquals(R.string.pre_write_probe_failed_title, writeUnknown.titleRes)
        assertEquals(R.string.pre_write_probe_failed_message, writeUnknown.messageRes)
        assertEquals(R.string.pre_clear_unknown_refresh_retry, writeUnknown.retryLabelRes)

        // Only two message variants. This JVM test checks descriptor resource
        // IDs only — it does NOT verify locale string contents/parity; those
        // strings are NOT verified in this suite (DEVICE-UNVERIFIED / needs
        // locale-instrumentation or a source-scan guard).
        assertEquals(
            2,
            setOf(
                clearFailed.messageRes,
                clearUnknown.messageRes,
                writeFailed.messageRes,
                writeUnknown.messageRes,
            ).size,
        )
    }
}
