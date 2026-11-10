// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Exercises production orchestration, not the DualHook/LSPosed adapter: the
// framework API is compileOnly and a JVM fake cannot verify its real runtime
// behavior. ART inlining and adapter integration require an LSPosed device test.
class DeoptimizeContractTest {
    private class Host(
        val deoptResult: () -> Boolean = { true },
        val hookResult: () -> Unit = {},
        val logResult: (String) -> Unit = {},
    ) {
        val events = mutableListOf<Pair<String, Method>>()
        val ids = mutableListOf<String>()
        val logs = mutableListOf<String>()

        fun install(method: Method, id: String) = HookInstaller.install(
            method, id,
            { target -> events.add("deoptimize" to target); deoptResult() },
            { target, hookId -> events.add("hook" to target); ids.add(hookId); hookResult() },
            { message -> logs.add(message); logResult(message) },
        )
    }

    private fun method(name: String): Method = String::class.java.getMethod(name)

    @Test
    fun eachExactMethodIsDeoptimizedOnceBeforeItsHook() {
        val host = Host()
        val first = method("length")
        val second = method("isEmpty")
        assertTrue(host.install(first, "first"))
        assertTrue(host.install(second, "second"))
        assertEquals(listOf("deoptimize", "hook", "deoptimize", "hook"), host.events.map { it.first })
        listOf(first, first, second, second).zip(host.events).forEach { (expected, event) ->
            assertSame(expected, event.second)
        }
        assertEquals(listOf("first", "second"), host.ids)
        assertEquals(
            listOf("deoptimized java.lang.String#length()->int", "hooked java.lang.String#length()->int",
                "deoptimized java.lang.String#isEmpty()->boolean", "hooked java.lang.String#isEmpty()->boolean"),
            host.logs,
        )
    }

    @Test
    fun falseIsReportedWithoutInferringCauseAndHookStillInstalls() {
        val host = Host(deoptResult = { false })
        val target = method("length")
        assertTrue(host.install(target, "id"))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        host.events.forEach { assertSame(target, it.second) }
        assertEquals(listOf("id"), host.ids)
        assertEquals(
            listOf("deoptimize returned false java.lang.String#length()->int (hook proceeds, effect unverified)",
                "hooked java.lang.String#length()->int"), host.logs,
        )
    }

    @Test
    fun deoptimizeThrowIsNonFatal() {
        val host = Host(deoptResult = { throw AssertionError("art unavailable") })
        val target = method("length")
        assertTrue(host.install(target, "id"))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        host.events.forEach { assertSame(target, it.second) }
        assertEquals(
            listOf("deoptimize failed java.lang.String#length()->int: java.lang.AssertionError: art unavailable; hook proceeds, effect unverified",
                "hooked java.lang.String#length()->int"), host.logs,
        )
    }

    @Test
    fun hookFailureReturnsFalseAndLogsFailureForEveryDeoptimizeOutcome() {
        val outcomes: List<() -> Boolean> = listOf({ true }, { false }, { throw Error("deopt error") })
        outcomes.forEach { outcome ->
            val host = Host(deoptResult = outcome, hookResult = { throw Error("install error") })
            val target = method("length")
            assertFalse(host.install(target, "id"))
            assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
            host.events.forEach { assertSame(target, it.second) }
            assertEquals(listOf("id"), host.ids)
            assertEquals(2, host.logs.size)
            assertEquals("hook failed java.lang.String#length()->int: java.lang.Error: install error", host.logs.last())
        }
    }

    @Test
    fun loggerFailureAtAnyDeoptimizeStageDoesNotChangeHookResult() {
        val outcomes: List<() -> Boolean> = listOf({ true }, { false }, { throw Error("deopt error") })
        val prefixes = listOf("deoptimized ", "deoptimize returned false ", "deoptimize failed ")
        outcomes.zip(prefixes).forEach { (outcome, prefix) ->
            val host = Host(deoptResult = outcome, logResult = { if (it.startsWith(prefix)) throw Error("logger") })
            val target = method("length")
            assertTrue(host.install(target, "id"))
            assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
            host.events.forEach { assertSame(target, it.second) }
            assertEquals(listOf("id"), host.ids)
            assertEquals(2, host.logs.size)
            assertTrue(host.logs.first().startsWith(prefix))
            assertEquals("hooked java.lang.String#length()->int", host.logs.last())
        }
    }

    @Test
    fun loggerFailureAfterHookSuccessDoesNotReportFailure() {
        val host = Host(logResult = { if (it.startsWith("hooked ")) throw Error("logger") })
        assertTrue(host.install(method("length"), "id"))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        assertEquals(listOf("deoptimized java.lang.String#length()->int", "hooked java.lang.String#length()->int"), host.logs)
    }

    @Test
    fun loggerFailureAfterHookFailureStillReturnsFalse() {
        val host = Host(
            hookResult = { throw Error("install error") },
            logResult = { if (it.startsWith("hook failed ")) throw Error("logger") },
        )
        assertFalse(host.install(method("length"), "id"))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        assertEquals("hook failed java.lang.String#length()->int: java.lang.Error: install error", host.logs.last())
    }
}
