// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

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
    // first (best-effort, diagnostics preserved per caller), then run the
    // standard callee install above (callee deopt + hook). Caller failures
    // never block the callee hook; only the callee hook result decides the
    // return value. Pure orchestration over CallerDeoptPlan -- framework ops
    // stay injected, so JVM tests lock ordering/identity/failure handling
    // while the ART effect remains DEVICE-UNVERIFIED.
    fun installWithCallers(
        callee: Method,
        hookId: String,
        knownCallers: List<Method>,
        deoptimize: (Method) -> Boolean,
        hook: (Method, String) -> Unit,
        log: (String) -> Unit,
    ): Boolean {
        val plan = CallerDeoptPlan.plan(callee, knownCallers)
        for (caller in plan.callersToDeoptimize) {
            try {
                if (deoptimize(caller)) {
                    safeLog(log, "deoptimized caller ${caller.declaringClass.name}#${caller.name}")
                } else {
                    safeLog(
                        log,
                        "deoptimize caller returned false ${caller.declaringClass.name}#${caller.name} " +
                            "(hook proceeds, effect unverified)",
                    )
                }
            } catch (t: Throwable) {
                safeLog(
                    log,
                    "deoptimize caller failed ${caller.declaringClass.name}#${caller.name}: " +
                        "${t.message}; hook proceeds, effect unverified",
                )
            }
        }
        return install(callee, hookId, deoptimize, hook, log)
    }
}
