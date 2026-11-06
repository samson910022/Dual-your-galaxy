// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Method

// Framework-free orchestration; the caller supplies the actual framework operations.
internal object HookInstaller {
    fun install(
        method: Method,
        hookId: String,
        deoptimize: (Method) -> Boolean,
        hook: (Method, String) -> Unit,
        log: (String) -> Unit,
    ): Boolean {
        fun safeLog(message: String) {
            try {
                log(message)
            } catch (_: Throwable) {
                // Diagnostics must not affect installation or its result.
            }
        }

        try {
            try {
                if (deoptimize(method)) {
                    safeLog("deoptimized ${method.declaringClass.name}#${method.name}")
                } else {
                    safeLog(
                        "deoptimize returned false ${method.declaringClass.name}#${method.name} " +
                            "(hook proceeds, effect unverified)",
                    )
                }
            } catch (t: Throwable) {
                safeLog(
                    "deoptimize failed ${method.declaringClass.name}#${method.name}: " +
                        "${t.message}; hook proceeds, effect unverified",
                )
            }
            hook(method, hookId)
            safeLog("hooked ${method.declaringClass.name}#${method.name}")
            return true
        } catch (t: Throwable) {
            safeLog("hook failed ${method.declaringClass.name}#${method.name}: ${t.message}")
            return false
        }
    }
}
