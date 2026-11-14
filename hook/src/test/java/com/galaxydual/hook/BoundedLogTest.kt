// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

// P2-4/H13: mismatch logs are bounded twice: per-method (first 8 per distinct
// methodDesc fire) and process-global (64 max). Before the H13 fix a single
// process-global 8 let the noisiest method drain every other method's budget.
class BoundedLogTest {

    // Reset the shared static log budgets before and after each test so a
    // hard failure cannot leak counter state into other tests in the shared
    // hook-JVM fork. No System.* mutation here — only WhitelistPolicy's
    // static counters are reset.
    @Before
    fun setUp() {
        WhitelistPolicy.resetUnexpectedArgsLogsForTest()
    }

    @After
    fun tearDown() {
        WhitelistPolicy.resetUnexpectedArgsLogsForTest()
    }

    @Test
    fun mismatchLogsAreBoundedToEightPerMethod() {
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
        assertEquals(
            WhitelistPolicy.MAX_UNEXPECTED_ARGS_GLOBAL_LOGS - 8,
            WhitelistPolicy.remainingUnexpectedArgsBudget(),
        )
    }

    @Test
    fun differentMethodsGetIndependentBudgets() {
        var logs = 0
        val onLog: (String) -> Unit = { logs++ }
        val extra = setOf("com.policy.app")
        // Two distinct mismatched methods: each logs up to its own 8.
        repeat(2) { method ->
            repeat(10) {
                WhitelistPolicy.apply(
                    result = false,
                    args = listOf("a", "b"),
                    extra = extra,
                    methodDesc = "method$method",
                    onLog = onLog,
                    packageArgIndex = null,
                    strictSignature = true,
                )
            }
        }
        // 2 methods x 8 per-method logs = 16 (global cap 64 not reached).
        assertEquals(16, logs)
    }

    @Test
    fun globalCapBackstopsManyMethods() {
        var logs = 0
        val onLog: (String) -> Unit = { logs++ }
        val extra = setOf("com.policy.app")
        // 9 distinct methods x 10 attempts: 72 would pass the per-method
        // cap; the global cap must hold emission at MAX_UNEXPECTED_ARGS_GLOBAL_LOGS.
        repeat(9) { method ->
            repeat(10) {
                WhitelistPolicy.apply(
                    result = false,
                    args = listOf("a", "b"),
                    extra = extra,
                    methodDesc = "global$method",
                    onLog = onLog,
                    packageArgIndex = null,
                    strictSignature = true,
                )
            }
        }
        assertEquals(WhitelistPolicy.MAX_UNEXPECTED_ARGS_GLOBAL_LOGS, logs)
        assertEquals(0, WhitelistPolicy.remainingUnexpectedArgsBudget())
    }

    @Test
    fun logBoundedHelperReturnsFalseWhenSuppressed() {
        var logs = 0
        val onLog: (String) -> Unit = { logs++ }
        // Unkeyed callers share one bucket: first 8 fire, rest stay silent.
        repeat(8) {
            assertTrue(WhitelistPolicy.logBounded(onLog, "m$it"))
        }
        assertEquals(8, logs)
        // 9th is suppressed (same, unkeyed, bucket).
        assertEquals(false, WhitelistPolicy.logBounded(onLog, "m8"))
        assertEquals(8, logs)
        // A distinct methodKey gets its own budget.
        assertTrue(WhitelistPolicy.logBounded(onLog, "n0", methodKey = "other"))
        assertEquals(9, logs)
    }
}
