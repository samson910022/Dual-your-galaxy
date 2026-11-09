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
    ) {
        val events = mutableListOf<Pair<String, Method>>()
        val logs = mutableListOf<String>()

        fun installWithCallers(callee: Method, callers: List<Method>, id: String) =
            HookInstaller.installWithCallers(
                callee, id, callers,
                { target -> events.add("deoptimize" to target); deopt(target) },
                { target, _ -> events.add("hook" to target); hookResult() },
                { message -> logs.add(message) },
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
        val host = Host(hookResult = { throw Error("install error") })
        assertFalse(host.installWithCallers(method("length"), listOf(method("isEmpty")), "id"))
        assertEquals("hook failed java.lang.String#length: install error", host.logs.last())
    }
}
