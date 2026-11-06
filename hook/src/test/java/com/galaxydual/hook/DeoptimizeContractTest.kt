// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #10 (deoptimize callee!=caller protection): JVM call-contract lock
// for the hook install path.
//
// What this test IS: a contract mirror of DualHook.hookWithDeoptimize
// (hook/src/main/java/com/galaxydual/hook/DualHook.kt). It locks the current
// install-path contract with a hand-written fake host (same style as the
// hand-rolled inputs in WhitelistPolicyTest -- no Mockito, no framework):
// every hooked callee method gets exactly one deoptimize(method) call BEFORE
// hook(method); all three deoptimize outcomes (true / false / throw) are
// logged; and a `false` return is NEVER read as "deoptimization was not
// needed" -- the hook still installs and the effect stays "unverified".
//
// What this test IS NOT (honesty boundary, do not over-claim):
// - DualHook itself cannot load on the JVM unit-test classpath (compileOnly
//   LibXposed + android.util.Log; see the ScopeParityTest header), so this
//   file exercises FakeXposedHost -- which copies the production branching
//   shape -- not the production install path. If DualHook.hookWithDeoptimize
//   changes branches, this file must be updated by hand to match.
// - Pure JVM cannot reproduce ART method inlining: a caller (e.g.
//   system_server code) that already inlined the callee keeps its own
//   compiled copy, which no callee-side deoptimize() can reach. The real
//   caller->callee inlining fixture (LSPosed + an already-optimized caller)
//   runs on device/emulator only; see docs/HOOK.md (callee-only note) and
//   the manual matrix docs/ACCEPTANCE.md row H1.
class DeoptimizeContractTest {

    // Mirrors the three deoptimize(Executable) outcomes the production path
    // must handle (DualHook.hookWithDeoptimize: true / false / throw).
    private sealed interface DeoptBehavior {
        data object Success : DeoptBehavior
        data object NoOp : DeoptBehavior
        data class Throw(val error: RuntimeException) : DeoptBehavior
    }

    // Hand-written fake standing in for the LSPosed XposedInterface surface
    // used by the install path: deoptimize(callee) + hook(callee). Counts
    // calls, records call order, and collects log lines. installWithDeoptimize
    // copies the production branching shape (deoptimize exactly-once first,
    // outcome-specific log, hook proceeds on all three outcomes); it is a
    // contract mirror, not the production code under test (see header).
    private class FakeXposedHost(var behavior: DeoptBehavior) {
        var deoptimizeCalls = 0
        var hookCalls = 0
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()

        fun deoptimize(method: Method): Boolean {
            deoptimizeCalls++
            events.add("deoptimize:${method.name}")
            return when (val b = behavior) {
                DeoptBehavior.Success -> true
                DeoptBehavior.NoOp -> false
                is DeoptBehavior.Throw -> throw b.error
            }
        }

        fun hook(method: Method) {
            hookCalls++
            events.add("hook:${method.name}")
        }

        fun installWithDeoptimize(method: Method): Boolean {
            try {
                try {
                    val deoptimized = deoptimize(method)
                    if (deoptimized) {
                        logs.add("deoptimized ${method.declaringClass.name}#${method.name}")
                    } else {
                        // Contract: false is a no-op report, NOT proof that no
                        // deoptimization was needed. The hook proceeds and the
                        // anti-inline effect stays explicitly unverified.
                        logs.add(
                            "deoptimize no-op ${method.declaringClass.name}#${method.name} " +
                                "(already interpreted or unsupported; hook proceeds, " +
                                "effect unverified)",
                        )
                    }
                } catch (t: Throwable) {
                    // Non-fatal: hooking still works when the target is not inlined.
                    logs.add(
                        "deoptimize failed ${method.declaringClass.name}#${method.name}: " +
                            "${t.message}; hook proceeds, effect unverified",
                    )
                }
                hook(method)
                logs.add("hooked ${method.declaringClass.name}#${method.name}")
                return true
            } catch (t: Throwable) {
                logs.add("hook failed ${method.declaringClass.name}#${method.name}: ${t.message}")
                return false
            }
        }
    }

    private fun calleeA(): Method = String::class.java.getMethod("length")

    private fun calleeB(): Method = String::class.java.getMethod("isEmpty")

    // Contract: each hooked callee gets exactly one deoptimize() call, and it
    // happens BEFORE the hook install (per-method ordering).
    @Test
    fun deoptimize_calledExactlyOncePerCalleeBeforeHook() {
        val host = FakeXposedHost(DeoptBehavior.Success)
        assertTrue(host.installWithDeoptimize(calleeA()))
        assertTrue(host.installWithDeoptimize(calleeB()))
        assertEquals(2, host.deoptimizeCalls)
        assertEquals(2, host.hookCalls)
        assertEquals(
            listOf("deoptimize:length", "hook:length", "deoptimize:isEmpty", "hook:isEmpty"),
            host.events,
        )
    }

    // Outcome 1/3: deoptimize success is logged and the hook installs.
    @Test
    fun deoptimizeTrue_logsDeoptimizedAndStillHooks() {
        val host = FakeXposedHost(DeoptBehavior.Success)
        assertTrue(host.installWithDeoptimize(calleeA()))
        assertEquals(1, host.deoptimizeCalls)
        assertEquals(1, host.hookCalls)
        assertTrue(
            "success must log deoptimized, was: ${host.logs}",
            host.logs.any { it.contains("deoptimized java.lang.String#length") },
        )
        assertTrue(
            "hook install must be logged, was: ${host.logs}",
            host.logs.any { it.contains("hooked java.lang.String#length") },
        )
    }

    // Outcome 2/3 + semantic lock: a `false` return means "no-op report",
    // never "deoptimization was not needed". The hook still installs, the
    // log keeps "hook proceeds" + "effect unverified", and no log line may
    // claim the no-op proves anything about need.
    @Test
    fun deoptimizeFalse_isNeverReadAsNotNeeded_hookProceedsEffectUnverified() {
        val host = FakeXposedHost(DeoptBehavior.NoOp)
        assertTrue("hook must still install on deoptimize no-op", host.installWithDeoptimize(calleeA()))
        assertEquals(1, host.deoptimizeCalls)
        assertEquals(1, host.hookCalls)
        val noOpLogs = host.logs.filter { it.contains("deoptimize no-op") }
        assertEquals("exactly one no-op diagnostic expected, was: ${host.logs}", 1, noOpLogs.size)
        val single = noOpLogs.single()
        assertTrue("no-op must say the hook proceeds, was: $single", single.contains("hook proceeds"))
        assertTrue(
            "no-op must keep the effect unverified, was: $single",
            single.contains("effect unverified"),
        )
        assertTrue(
            "false must not be read as 'not needed', was: ${host.logs}",
            host.logs.none {
                it.contains("not needed", ignoreCase = true) ||
                    it.contains("unnecessary", ignoreCase = true) ||
                    it.contains("not required", ignoreCase = true)
            },
        )
    }

    // Outcome 3/3: deoptimize throwing is logged as a failure diagnostic and
    // the hook still installs (best-effort inlining protection).
    @Test
    fun deoptimizeThrow_logsFailureAndStillHooks() {
        val host = FakeXposedHost(DeoptBehavior.Throw(RuntimeException("art unavailable")))
        assertTrue("hook must still install on deoptimize throw", host.installWithDeoptimize(calleeA()))
        assertEquals(1, host.deoptimizeCalls)
        assertEquals(1, host.hookCalls)
        assertTrue(
            "throw must log deoptimize failure, was: ${host.logs}",
            host.logs.any { it.contains("deoptimize failed") && it.contains("hook proceeds") },
        )
        assertTrue(
            "throw path must keep the effect unverified, was: ${host.logs}",
            host.logs.any { it.contains("effect unverified") },
        )
    }
}
