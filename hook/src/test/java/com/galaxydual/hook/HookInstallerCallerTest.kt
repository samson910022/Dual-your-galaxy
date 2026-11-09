// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Locks caller->callee orchestration (issue #10 Stage1): known callers are
// deoptimized before the callee hook, each with preserved failure
// diagnostics (false/exception logged, never "not needed", hook proceeds).
// Framework ops stay injected fakes; ART effect remains DEVICE-UNVERIFIED.
class HookInstallerCallerTest {
    private class Host(
        val deopt: (Method) -> Boolean = { true },
        val hookResult: () -> Unit = {},
        val logResult: (String) -> Unit = {},
    ) {
        val events = mutableListOf<Pair<String, Method>>()
        val ids = mutableListOf<String>()
        val logs = mutableListOf<String>()

        fun installWithCallers(callee: Method, callers: List<Method>, id: String) =
            HookInstaller.installWithCallers(
                callee, id, callers,
                { target -> events.add("deoptimize" to target); deopt(target) },
                { target, hookId -> events.add("hook" to target); ids.add(hookId); hookResult() },
                { message -> logs.add(message); logResult(message) },
            )
    }

    private fun method(name: String): Method = String::class.java.getMethod(name)

    @Test
    fun emptyCallersBehavesLikeCalleeOnlyInstall() {
        val host = Host()
        val callee = method("length")
        assertTrue(host.installWithCallers(callee, emptyList(), "id"))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        host.events.forEach { assertSame(callee, it.second) }
        assertEquals(
            listOf("deoptimized java.lang.String#length", "hooked java.lang.String#length"),
            host.logs,
        )
    }

    @Test
    fun callersDeoptimizedBeforeCalleeHookWithIdentity() {
        val host = Host()
        val callee = method("length")
        val callerA = method("isEmpty")
        val callerB = method("hashCode")
        assertTrue(host.installWithCallers(callee, listOf(callerA, callerB), "id"))
        assertEquals(listOf("deoptimize", "deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(callerA, host.events[0].second)
        assertSame(callerB, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertSame(callee, host.events[3].second)
        assertEquals(
            listOf(
                "deoptimized caller java.lang.String#isEmpty",
                "deoptimized caller java.lang.String#hashCode",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun callerFalseIsReportedAndHookProceeds() {
        val host = Host(deopt = { false })
        val callee = method("length")
        val caller = method("isEmpty")
        assertTrue(host.installWithCallers(callee, listOf(caller), "id"))
        assertEquals(
            listOf(
                "deoptimize caller returned false java.lang.String#isEmpty (hook proceeds, effect unverified)",
                "deoptimize returned false java.lang.String#length (hook proceeds, effect unverified)",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun callerThrowIsNonFatalAndHookProceeds() {
        val host = Host(deopt = { target ->
            if (target.name == "isEmpty") throw AssertionError("art unavailable") else true
        })
        val callee = method("length")
        assertTrue(host.installWithCallers(callee, listOf(method("isEmpty")), "id"))
        assertEquals(
            listOf(
                "deoptimize caller failed java.lang.String#isEmpty: art unavailable; hook proceeds, effect unverified",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun failureDiagnosticsNeverClaimNotNeeded() {
        // Issue #10: false must not read as "not needed"/no-op. Lock exact
        // wording on both caller and callee paths across all outcomes.
        val outcomes: List<(Method) -> Boolean> = listOf({ true }, { false }, { throw Error("x") })
        outcomes.forEach { deopt ->
            val host = Host(deopt = deopt)
            assertTrue(host.installWithCallers(method("length"), listOf(method("isEmpty")), "id"))
            assertFalse(host.logs.any {
                it.contains("not needed", ignoreCase = true) ||
                    it.contains("no-op", ignoreCase = true) ||
                    it.contains("不需要")
            })
            // Every deoptimize attempt (caller + callee) leaves a diagnostic.
            assertEquals(3, host.logs.size)
            assertTrue(host.logs.last().startsWith("hooked "))
        }
    }

    @Test
    fun hookFailureStillReturnsFalse() {
        val callee = method("length")
        val caller = method("isEmpty")
        val host = Host(hookResult = { throw Error("install error") })
        assertFalse(host.installWithCallers(callee, listOf(caller), "galaxydual:custom"))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf("galaxydual:custom"), host.ids)
        assertEquals(3, host.logs.size)
        assertEquals("hook failed java.lang.String#length: install error", host.logs.last())
    }

    @Test
    fun loggerFailureOnDeoptimizedCallerDoesNotChangeResult() {
        val callee = method("length")
        val caller = method("isEmpty")
        val host = Host(logResult = { if (it.startsWith("deoptimized caller ")) throw Error("logger") })
        assertTrue(host.installWithCallers(callee, listOf(caller), "galaxydual:custom"))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf("galaxydual:custom"), host.ids)
        assertEquals(
            listOf(
                "deoptimized caller java.lang.String#isEmpty",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun loggerFailureOnCallerReturnedFalseDoesNotChangeResult() {
        val callee = method("length")
        val caller = method("isEmpty")
        val host = Host(
            deopt = { target -> target != caller },
            logResult = { if (it.startsWith("deoptimize caller returned false ")) throw Error("logger") },
        )
        assertTrue(host.installWithCallers(callee, listOf(caller), "galaxydual:custom"))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf("galaxydual:custom"), host.ids)
        assertEquals(
            listOf(
                "deoptimize caller returned false java.lang.String#isEmpty (hook proceeds, effect unverified)",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun loggerFailureOnCallerFailedDoesNotChangeResult() {
        val callee = method("length")
        val caller = method("isEmpty")
        val host = Host(
            deopt = { target -> if (target == caller) throw AssertionError("art unavailable") else true },
            logResult = { if (it.startsWith("deoptimize caller failed ")) throw Error("logger") },
        )
        assertTrue(host.installWithCallers(callee, listOf(caller), "galaxydual:custom"))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf("galaxydual:custom"), host.ids)
        assertEquals(
            listOf(
                "deoptimize caller failed java.lang.String#isEmpty: art unavailable; hook proceeds, effect unverified",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun forwardsNonTrivialHookIdToHook() {
        val host = Host()
        assertTrue(host.installWithCallers(method("length"), listOf(method("isEmpty")), "galaxydual:custom"))
        assertEquals(listOf("galaxydual:custom"), host.ids)
    }

    @Test
    fun duplicateCallersDeoptimizedOnceThroughOrchestration() {
        val host = Host()
        val callee = method("length")
        val caller = method("isEmpty")
        assertTrue(host.installWithCallers(callee, listOf(caller, caller, caller), "id"))
        val deoptTargets = host.events.filter { it.first == "deoptimize" }.map { it.second }
        assertEquals(listOf(caller, callee), deoptTargets)
    }

    @Test
    fun multipleCallersFailDifferentlyAndHookStillProceeds() {
        val callee = method("length")
        val first = method("isEmpty")
        val second = method("hashCode")
        val third = method("toString")
        val host = Host(deopt = { target ->
            when (target) {
                first -> false
                second -> throw AssertionError("art unavailable")
                else -> true
            }
        })
        assertTrue(host.installWithCallers(callee, listOf(first, second, third), "galaxydual:custom"))
        assertEquals(
            listOf("deoptimize", "deoptimize", "deoptimize", "deoptimize", "hook"),
            host.events.map { it.first },
        )
        assertSame(first, host.events[0].second)
        assertSame(second, host.events[1].second)
        assertSame(third, host.events[2].second)
        assertSame(callee, host.events[3].second)
        assertSame(callee, host.events[4].second)
        assertEquals(listOf("galaxydual:custom"), host.ids)
        assertEquals(
            listOf(
                "deoptimize caller returned false java.lang.String#isEmpty (hook proceeds, effect unverified)",
                "deoptimize caller failed java.lang.String#hashCode: art unavailable; hook proceeds, effect unverified",
                "deoptimized caller java.lang.String#toString",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun callerSucceedsButCalleeDeoptReturnsFalse() {
        val callee = method("length")
        val caller = method("isEmpty")
        val host = Host(deopt = { target -> target != callee })
        assertTrue(host.installWithCallers(callee, listOf(caller), "galaxydual:custom"))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertEquals(listOf("galaxydual:custom"), host.ids)
        assertEquals(
            listOf(
                "deoptimized caller java.lang.String#isEmpty",
                "deoptimize returned false java.lang.String#length (hook proceeds, effect unverified)",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun callerSucceedsButCalleeDeoptThrows() {
        val callee = method("length")
        val caller = method("isEmpty")
        val host = Host(deopt = { target -> if (target == callee) throw AssertionError("art unavailable") else true })
        assertTrue(host.installWithCallers(callee, listOf(caller), "galaxydual:custom"))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertEquals(listOf("galaxydual:custom"), host.ids)
        assertEquals(
            listOf(
                "deoptimized caller java.lang.String#isEmpty",
                "deoptimize failed java.lang.String#length: art unavailable; hook proceeds, effect unverified",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }
}
