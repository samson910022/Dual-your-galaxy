// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable
import java.lang.reflect.Method

// Framework-free orchestration; the caller supplies the actual framework operations.
internal object HookInstaller {
    private inline fun safeLog(log: (String) -> Unit, message: String) {
        try {
            log(message)
        } catch (_: Throwable) {
            // Diagnostics must not affect installation or its result.
        }
    }

    fun install(
        method: Method,
        hookId: String,
        deoptimize: (Method) -> Boolean,
        hook: (Method, String) -> Unit,
        log: (String) -> Unit,
    ): Boolean {
        try {
            try {
                if (deoptimize(method)) {
                    safeLog(log, "deoptimized ${method.declaringClass.name}#${method.name}")
                } else {
                    safeLog(
                        log,
                        "deoptimize returned false ${method.declaringClass.name}#${method.name} " +
                            "(hook proceeds, effect unverified)",
                    )
                }
            } catch (t: Throwable) {
                safeLog(
                    log,
                    "deoptimize failed ${method.declaringClass.name}#${method.name}: " +
                        "${t.message}; hook proceeds, effect unverified",
                )
            }
            hook(method, hookId)
            safeLog(log, "hooked ${method.declaringClass.name}#${method.name}")
            return true
        } catch (t: Throwable) {
            safeLog(log, "hook failed ${method.declaringClass.name}#${method.name}: ${t.message}")
            return false
        }
    }

    // Caller-aware variant (issue #10 Stage1): deoptimize each known caller
    // (Method or Constructor, as Executable) first (best-effort, diagnostics
    // preserved per caller with hookId and full signature), then run the
    // standard callee install above (callee deopt + hook). Caller failures
    // never block the callee hook; only the callee hook result decides the
    // return value. Pure orchestration over CallerDeoptPlan -- framework ops
    // stay injected, so JVM tests lock ordering/identity/failure handling
    // while the ART effect remains DEVICE-UNVERIFIED.
    fun installWithCallers(
        callee: Method,
        hookId: String,
        knownCallers: List<Executable?>,
        deoptimize: (Executable) -> Boolean,
        hook: (Method, String) -> Unit,
        log: (String) -> Unit,
    ): Boolean {
        val plan = CallerDeoptPlan.plan(callee, knownCallers)
        val callersAttempted = plan.callersToDeoptimize.size
        var callersDeoptimized = 0
        for (caller in plan.callersToDeoptimize) {
            val sig = caller.toGenericString()
            try {
                if (deoptimize(caller)) {
                    callersDeoptimized++
                    safeLog(log, "deoptimized caller [$hookId] $sig")
                } else {
                    safeLog(
                        log,
                        "deoptimize caller [$hookId] returned false $sig " +
                            "(hook proceeds, effect unverified)",
                    )
                }
            } catch (t: Throwable) {
                safeLog(
                    log,
                    "deoptimize caller [$hookId] failed $sig: " +
                        "${t::class.java.name}${if (t.message != null) ": ${t.message}" else ""}; " +
                        "hook proceeds, effect unverified",
                )
            }
        }
        val result = install(callee, hookId, deoptimize, hook, log)
        if (result && callersAttempted > 0 && callersDeoptimized < callersAttempted) {
            safeLog(
                log,
                "caller deopt incomplete: $callersDeoptimized/$callersAttempted deoptimized; " +
                    "hook proceeds, inline bypass may remain (effect unverified)",
            )
        }
        return result
    }
}
