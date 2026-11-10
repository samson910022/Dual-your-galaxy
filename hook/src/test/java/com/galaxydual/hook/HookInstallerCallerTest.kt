// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Locks caller->callee orchestration (issue #10 Stage1): known callers
// (methods or constructors, as Executables) are deoptimized before the callee
// hook, each with preserved failure diagnostics (hookId + full signature;
// false/exception logged with throwable class, never "not needed", hook
// proceeds). Framework ops stay injected fakes; ART effect remains
// DEVICE-UNVERIFIED.
class HookInstallerCallerTest {
    private class Host(
        val deopt: (Executable) -> Boolean = { true },
        val hookResult: () -> Unit = {},
        val logResult: (String) -> Unit = {},
    ) {
        val events = mutableListOf<Pair<String, Executable>>()
        val ids = mutableListOf<String>()
        val logs = mutableListOf<String>()

        fun installWithCallers(callee: Method, callers: List<Executable?>, id: String) =
            HookInstaller.installWithCallers(
                callee, id, callers,
                { target -> events.add("deoptimize" to target); deopt(target) },
                { target, hookId -> events.add("hook" to target); ids.add(hookId); hookResult() },
                { message -> logs.add(message); logResult(message) },
            )
    }

    private fun method(name: String): Method = String::class.java.getMethod(name)

    private fun sig(e: Executable): String = HookInstaller.executableSignature(e)

    private fun incompleteSummary(id: String, callee: Executable, deoptimized: Int, attempted: Int) =
        "caller deopt incomplete [$id] ${sig(callee)}: $deoptimized/$attempted deoptimized; " +
            "hook proceeds, inline bypass may remain (effect unverified)"

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
        val id = "id"
        assertTrue(host.installWithCallers(callee, listOf(callerA, callerB), id))
        assertEquals(listOf("deoptimize", "deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(callerA, host.events[0].second)
        assertSame(callerB, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertSame(callee, host.events[3].second)
        assertEquals(
            listOf(
                "deoptimized caller [$id] ${sig(callerA)}",
                "deoptimized caller [$id] ${sig(callerB)}",
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
        val id = "id"
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(
            listOf(
                "deoptimize caller [$id] returned false ${sig(caller)} (hook proceeds, effect unverified)",
                "deoptimize returned false java.lang.String#length (hook proceeds, effect unverified)",
                "hooked java.lang.String#length",
                incompleteSummary(id, callee, 0, 1),
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
        val caller = method("isEmpty")
        val id = "id"
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(
            listOf(
                "deoptimize caller [$id] failed ${sig(caller)}: java.lang.AssertionError: art unavailable; hook proceeds, effect unverified",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
                incompleteSummary(id, callee, 0, 1),
            ),
            host.logs,
        )
    }

    @Test
    fun failureDiagnosticsNeverClaimNotNeeded() {
        // Issue #10: false must not read as "not needed"/no-op. Lock exact
        // wording on both caller and callee paths across all outcomes.
        // A caller deopt incomplete summary (added for diagnostics) may follow
        // the hooked entry when callers did not all deoptimize; it must also
        // never claim not-needed/no-op/不需要.
        val outcomes: List<Pair<String, (Executable) -> Boolean>> = listOf(
            "true" to { true },
            "false" to { false },
            "throw" to { throw Error("x") },
        )
        outcomes.forEach { (label, deopt) ->
            val callee = method("length")
            val host = Host(deopt = deopt)
            assertTrue(host.installWithCallers(callee, listOf(method("isEmpty")), "id"))
            assertFalse("$label: claimed not needed", host.logs.any {
                it.contains("not needed", ignoreCase = true) ||
                    it.contains("no-op", ignoreCase = true) ||
                    it.contains("不需要")
            })
            // Every deoptimize attempt (caller + callee) leaves a diagnostic.
            assertTrue("$label: missing hooked entry", host.logs.any { it.startsWith("hooked ") })
            if (label == "true") {
                assertEquals("$label: unexpected log count", 3, host.logs.size)
                assertTrue(host.logs.last().startsWith("hooked "))
            } else {
                assertEquals("$label: unexpected log count", 4, host.logs.size)
                assertEquals(
                    "$label: expected incomplete summary",
                    incompleteSummary("id", callee, 0, 1),
                    host.logs.last(),
                )
            }
        }
    }

    @Test
    fun hookFailureStillReturnsFalse() {
        val callee = method("length")
        val caller = method("isEmpty")
        val id = "galaxydual:custom"
        val host = Host(hookResult = { throw Error("install error") })
        assertFalse(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf(id), host.ids)
        assertEquals(3, host.logs.size)
        assertEquals("hook failed java.lang.String#length: install error", host.logs.last())
    }

    @Test
    fun incompleteCallersAndHookFailureReportsFailureWithoutMisleadingSummary() {
        // Issue #10 review: caller mitigation is moot when the callee hook
        // fails, so no "caller deopt incomplete ... hook proceeds" summary.
        val callee = method("length")
        val caller = method("isEmpty")
        val id = "galaxydual:custom"
        val host = Host(
            deopt = { false },
            hookResult = { throw Error("install error") },
        )
        assertFalse(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf(id), host.ids)
        // Caller + callee deopt attempts logged (each "returned false"),
        // then hook-failure diagnostic from install(); no incomplete summary.
        assertTrue(host.logs.any { it == "hook failed java.lang.String#length: install error" })
        assertEquals("hook failed java.lang.String#length: install error", host.logs.last())
        assertFalse(host.logs.any { it.startsWith("caller deopt incomplete") })
        assertFalse(host.logs.any {
            it.contains("not needed", ignoreCase = true) ||
                it.contains("no-op", ignoreCase = true) ||
                it.contains("不需要")
        })
    }

    @Test
    fun loggerFailureOnDeoptimizedCallerDoesNotChangeResult() {
        val callee = method("length")
        val caller = method("isEmpty")
        val id = "galaxydual:custom"
        val host = Host(logResult = { if (it.startsWith("deoptimized caller ")) throw Error("logger") })
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf(id), host.ids)
        assertEquals(
            listOf(
                "deoptimized caller [$id] ${sig(caller)}",
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
        val id = "galaxydual:custom"
        val host = Host(
            deopt = { target -> target != caller },
            logResult = { if (it.startsWith("deoptimize caller [") && it.contains("] returned false ")) throw Error("logger") },
        )
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf(id), host.ids)
        assertEquals(
            listOf(
                "deoptimize caller [$id] returned false ${sig(caller)} (hook proceeds, effect unverified)",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
                incompleteSummary(id, callee, 0, 1),
            ),
            host.logs,
        )
    }

    @Test
    fun loggerFailureOnCallerFailedDoesNotChangeResult() {
        val callee = method("length")
        val caller = method("isEmpty")
        val id = "galaxydual:custom"
        val host = Host(
            deopt = { target -> if (target == caller) throw AssertionError("art unavailable") else true },
            logResult = { if (it.startsWith("deoptimize caller [") && it.contains("] failed ")) throw Error("logger") },
        )
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(caller, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf(id), host.ids)
        assertEquals(
            listOf(
                "deoptimize caller [$id] failed ${sig(caller)}: java.lang.AssertionError: art unavailable; hook proceeds, effect unverified",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
                incompleteSummary(id, callee, 0, 1),
            ),
            host.logs,
        )
    }

    @Test
    fun forwardsNonTrivialHookIdToHook() {
        val host = Host()
        val caller = method("isEmpty")
        val id = "galaxydual:custom"
        assertTrue(host.installWithCallers(method("length"), listOf(caller), id))
        assertEquals(listOf(id), host.ids)
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
        val id = "galaxydual:custom"
        val host = Host(deopt = { target ->
            when (target) {
                first -> false
                second -> throw AssertionError("art unavailable")
                else -> true
            }
        })
        assertTrue(host.installWithCallers(callee, listOf(first, second, third), id))
        assertEquals(
            listOf("deoptimize", "deoptimize", "deoptimize", "deoptimize", "hook"),
            host.events.map { it.first },
        )
        assertSame(first, host.events[0].second)
        assertSame(second, host.events[1].second)
        assertSame(third, host.events[2].second)
        assertSame(callee, host.events[3].second)
        assertSame(callee, host.events[4].second)
        assertEquals(listOf(id), host.ids)
        assertEquals(
            listOf(
                "deoptimize caller [$id] returned false ${sig(first)} (hook proceeds, effect unverified)",
                "deoptimize caller [$id] failed ${sig(second)}: java.lang.AssertionError: art unavailable; hook proceeds, effect unverified",
                "deoptimized caller [$id] ${sig(third)}",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
                incompleteSummary(id, callee, 1, 3),
            ),
            host.logs,
        )
    }

    @Test
    fun callerSucceedsButCalleeDeoptReturnsFalse() {
        val callee = method("length")
        val caller = method("isEmpty")
        val id = "galaxydual:custom"
        val host = Host(deopt = { target -> target != callee })
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertEquals(listOf(id), host.ids)
        assertEquals(
            listOf(
                "deoptimized caller [$id] ${sig(caller)}",
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
        val id = "galaxydual:custom"
        val host = Host(deopt = { target -> if (target == callee) throw AssertionError("art unavailable") else true })
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertEquals(listOf(id), host.ids)
        assertEquals(
            listOf(
                "deoptimized caller [$id] ${sig(caller)}",
                "deoptimize failed java.lang.String#length: art unavailable; hook proceeds, effect unverified",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    @Test
    fun summaryLoggedWhenCallersIncompleteAndOmittedWhenAllSucceedOrEmpty() {
        val callee = method("length")
        val a = method("isEmpty")
        val b = method("hashCode")

        val partial = Host(deopt = { target -> target != a })
        assertTrue(partial.installWithCallers(callee, listOf(a, b), "id"))
        assertEquals(incompleteSummary("id", callee, 1, 2), partial.logs.last())

        val total = Host(deopt = { target -> target == callee })
        assertTrue(total.installWithCallers(callee, listOf(a, b), "id"))
        assertEquals(incompleteSummary("id", callee, 0, 2), total.logs.last())

        val allSucceed = Host(deopt = { true })
        assertTrue(allSucceed.installWithCallers(callee, listOf(a, b), "id"))
        assertFalse(allSucceed.logs.any { it.startsWith("caller deopt incomplete") })
        assertTrue(allSucceed.logs.last().startsWith("hooked "))

        val empty = Host(deopt = { true })
        assertTrue(empty.installWithCallers(callee, emptyList(), "id"))
        assertFalse(empty.logs.any { it.startsWith("caller deopt incomplete") })
        assertTrue(empty.logs.last().startsWith("hooked "))
    }

    // Review #1 (P2): same-name overloads must be distinguishable in caller diagnostics.
    @Test
    fun overloadCallersProduceDistinguishableSignatures() {
        val callee = method("length")
        val intOverload = String::class.java.getMethod("valueOf", Int::class.javaPrimitiveType)
        val boolOverload = String::class.java.getMethod("valueOf", Boolean::class.javaPrimitiveType)
        val id = "galaxydual:custom"
        val host = Host(deopt = { target -> target == intOverload })
        assertTrue(host.installWithCallers(callee, listOf(intOverload, boolOverload), id))
        assertEquals(
            listOf("deoptimize", "deoptimize", "deoptimize", "hook"),
            host.events.map { it.first },
        )
        val intSig = sig(intOverload)
        val boolSig = sig(boolOverload)
        assertFalse("overload signatures must differ", intSig == boolSig)
        assertTrue(host.logs.any { it == "deoptimized caller [$id] $intSig" })
        assertTrue(host.logs.any {
            it == "deoptimize caller [$id] returned false $boolSig (hook proceeds, effect unverified)"
        })
        assertTrue(host.logs.filter { it.contains("caller [") }.all { "[$id]" in it })
    }

    @Test
    fun throwableWithNullMessageOmitsNullText() {
        val callee = method("length")
        val caller = method("isEmpty")
        val id = "id"
        val host = Host(deopt = { target -> if (target == caller) throw RuntimeException() else true })
        assertTrue(host.installWithCallers(callee, listOf(caller), id))
        assertTrue(host.logs.any {
            it == "deoptimize caller [$id] failed ${sig(caller)}: java.lang.RuntimeException; hook proceeds, effect unverified"
        })
        assertFalse(host.logs.any { it.contains("null") && it.contains("failed") })
    }

    @Test
    fun constructorCallerDeoptimizedBeforeCalleeHookWithFullSignature() {
        val host = Host()
        val callee = method("length")
        val ctor = String::class.java.getConstructor(ByteArray::class.java)
        val id = "galaxydual:ctor"
        assertTrue(host.installWithCallers(callee, listOf(ctor), id))
        assertEquals(listOf("deoptimize", "deoptimize", "hook"), host.events.map { it.first })
        assertSame(ctor, host.events[0].second)
        assertSame(callee, host.events[1].second)
        assertSame(callee, host.events[2].second)
        assertEquals(listOf(id), host.ids)
        assertEquals(
            listOf(
                "deoptimized caller [$id] ${sig(ctor)}",
                "deoptimized java.lang.String#length",
                "hooked java.lang.String#length",
            ),
            host.logs,
        )
    }

    // Phase 5 (P3 #3): planning/resolver traversal errors must not abort before
    // callee hook installation; fall back to callee-only best-effort.
    private class ThrowingCallersList(
        private val failure: RuntimeException = RuntimeException("resolver boom"),
    ) : java.util.AbstractList<Executable?>() {
        override val size: Int get() = throw failure
        override fun get(index: Int): Executable? = throw failure
        override fun iterator(): MutableIterator<Executable?> =
            throw RuntimeException(failure.message, failure)
    }

    @Test
    fun planningFailureFallsBackToCalleeOnlyHookStillSucceeds() {
        val callee = method("length")
        val id = "galaxydual:planfail"
        val host = Host()
        assertTrue(host.installWithCallers(callee, ThrowingCallersList(), id))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        host.events.forEach { assertSame(callee, it.second) }
        assertEquals(listOf(id), host.ids)
        assertEquals(
            "caller plan failed [$id] ${sig(callee)}: java.lang.RuntimeException: resolver boom; falling back to callee-only hook, effect unverified",
            host.logs.first(),
        )
        assertEquals("deoptimized java.lang.String#length", host.logs[1])
        assertEquals("hooked java.lang.String#length", host.logs[2])
        assertEquals(3, host.logs.size)
        assertFalse(host.logs.any {
            it.contains("not needed", ignoreCase = true) ||
                it.contains("no-op", ignoreCase = true) ||
                it.contains("不需要")
        })
    }

    @Test
    fun planningFailureWithNullMessageOmitsNullAndHookStillSucceeds() {
        val callee = method("length")
        val id = "id"
        val host = Host()
        assertTrue(host.installWithCallers(callee, ThrowingCallersList(RuntimeException()), id))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        assertEquals(listOf(id), host.ids)
        assertTrue(host.logs.any {
            it == "caller plan failed [$id] ${sig(callee)}: java.lang.RuntimeException; falling back to callee-only hook, effect unverified"
        })
        assertTrue(host.logs.last().startsWith("hooked "))
        assertFalse(host.logs.any { it.contains("null") && it.contains("caller plan failed") })
    }

    @Test
    fun planningFailureFallsBackToCalleeOnlyHookFailureStillReturnsFalse() {
        val callee = method("length")
        val id = "galaxydual:planfail-hookfail"
        val host = Host(hookResult = { throw Error("install error") })
        assertFalse(host.installWithCallers(callee, ThrowingCallersList(), id))
        assertEquals(listOf("deoptimize", "hook"), host.events.map { it.first })
        host.events.forEach { assertSame(callee, it.second) }
        assertEquals(listOf(id), host.ids)
        assertEquals(
            "caller plan failed [$id] ${sig(callee)}: java.lang.RuntimeException: resolver boom; falling back to callee-only hook, effect unverified",
            host.logs[0],
        )
        assertEquals("hook failed java.lang.String#length: install error", host.logs.last())
        assertFalse(host.logs.any { it.startsWith("caller deopt incomplete") })
        assertFalse(host.logs.any {
            it.contains("not needed", ignoreCase = true) ||
                it.contains("no-op", ignoreCase = true) ||
                it.contains("不需要")
        })
    }
}
