// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Method

// Caller-side deoptimize plan (issue #10, Stage1 JVM-testable part).
//
// Pure framework-free decision: given the hooked callee plus a list of known
// callers (callers observed or proven to have inlined the callee on a specific
// ROM/version), decide which callers to deoptimize before hooking the callee.
//
// Research note: deoptimizing the callee alone does not remove copies already
// inlined into callers, so the effective fix is either to deoptimize each
// inlined caller or to pick a hook point that inline copies cannot bypass.
// Caller identities require on-device ART evidence per ROM/version; without
// such evidence this plan is empty (= today's callee-only best-effort).
// Do NOT compensate by blanket-deoptimizing the whole system_server: keep the
// proven-minimal scope (known callers + hooked callee).
//
// ART effect itself stays DEVICE-UNVERIFIED; only the decision logic here is
// locked by JVM tests (see CallerDeoptPlanTest).
object CallerDeoptPlan {
    data class Plan(
        val callee: Method,
        val callersToDeoptimize: List<Method>,
    )

    // Dedupe (first-seen order kept), drop any entry equal to the callee
    // itself (the callee is deoptimized by HookInstaller.install, not as a
    // caller), never mutate the input list.
    fun plan(callee: Method, knownCallers: List<Method>): Plan {
        val callers = knownCallers.filter { it != callee }.distinct()
        return Plan(callee, callers)
    }
}
