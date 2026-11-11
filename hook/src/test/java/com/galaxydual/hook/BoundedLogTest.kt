// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// P2-4: dead log rate-limiter must be wired.
//
// Pins that WhitelistPolicy mismatch logs are bounded: the first N fire,
// the rest stay silent. Before the fix MAX_UNEXPECTED_ARGS_LOGS /
// unexpectedArgsLogs were declared but never read (every mismatch logged).
class BoundedLogTest {

    @Test
    fun mismatchLogsAreBoundedToEight() {
        WhitelistPolicy.resetUnexpectedArgsLogsForTest()
        var logs = 0
        val onLog: (String) -> Unit = { logs++ }
        val extra = setOf("com.policy.app")
        // Unverified package arg (strict): every call would log without the cap.
        repeat(20) {
            WhitelistPolicy.apply(
                result = false,
                args = listOf("a", "b"),
                extra = extra,
                methodDesc = "m",
                onLog = onLog,
                packageArgIndex = null,
                strictSignature = true,
            )
        }
        assertEquals(8, logs)
        assertEquals(20, WhitelistPolicy.unexpectedArgsLogCount())
        assertEquals(0, WhitelistPolicy.remainingUnexpectedArgsBudget())
    }

    @Test
    fun logBoundedHelperReturnsFalseWhenSuppressed() {
        WhitelistPolicy.resetUnexpectedArgsLogsForTest()
        var logs = 0
        val onLog: (String) -> Unit = { logs++ }
        repeat(8) {
            assertTrue(WhitelistPolicy.logBounded(onLog, "m$it"))
        }
        assertEquals(8, logs)
        // 9th is suppressed.
        assertEquals(false, WhitelistPolicy.logBounded(onLog, "m8"))
        assertEquals(8, logs)
    }
}
