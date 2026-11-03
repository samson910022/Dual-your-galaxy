package com.galaxydual.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker

// LSPosed entry point (Modern Xposed API, apiVersion 102).
// Entry class listed in resources/META-INF/xposed/java_init.list.
// Scopes listed in resources/META-INF/xposed/scope.list.
// Original skeleton code (MIT, self-authored).
class DualHook(base: XposedInterface, param: XposedModule.ModuleLoadedParam) : XposedModule(base, param) {

    // System server entry: hook DualAppManagerService placeholder.
    override fun onSystemServerStarting(param: XposedModule.SystemServerLoadedParam) {
        // Placeholder: resolve DualAppManagerService only when present on Samsung ROM.
        // deoptimize placeholder: deoptimize callers of DualAppManagerService methods
        // before hooking so inlined callers fall back to the hooked path.
        try {
            val target = param.classLoader.loadClass("com.samsung.android.server.dual.DualAppManagerService")
            val method = target.declaredMethods.firstOrNull { it.name == "getDualAppList" } ?: return
            // deoptimize(method) // enable once real target signatures are confirmed on-device.
            hook(method, ManagerHooker::class.java)
        } catch (_: Throwable) {
            // Ignore missing service outside supported One UI versions.
        }
    }

    // Called for every loaded package; filters to recommended scopes only.
    override fun onPackageLoaded(param: XposedModule.PackageLoadedParam) {
        // Only hook Samsung dual-app agent, settings, and system server.
        if (param.packageName !in SUPPORTED_SCOPES) {
            // Non-target process (e.g. unrelated daagent sub-process): detach to stop callbacks.
            detach()
            return
        }
        hookDualWhitelist(param)
    }

    // Hooks whitelist queries so supported apps can be dual-cloned (skeleton).
    private fun hookDualWhitelist(param: XposedModule.PackageLoadedParam) {
        // Placeholder: real class/method names are resolved on-device via research.
        // Example target: WhiteListApps / DualAppManagerService whitelist methods.
        try {
            val target = param.classLoader.loadClass("com.samsung.android.da.daagent.WhiteListApps") ?: return
            val method = target.getDeclaredMethod("isWhitelisted", String::class.java)
            // deoptimize placeholder: deoptimize whitelist callers if hook has no effect due to inlining.
            hook(method, WhitelistHooker::class.java)
        } catch (_: Throwable) {
            // Ignore missing classes on non-Samsung ROMs.
        }
    }

    companion object {
        // Recommended LSPosed scopes, mirrored in resources/META-INF/xposed/scope.list.
        val SUPPORTED_SCOPES = setOf(
            "system",
            "com.samsung.android.da.daagent",
            "com.android.settings"
        )

        // Minimum supported Xposed API version for this module.
        const val MIN_API_VERSION = 102
    }

    // Forces whitelisted result for dual-app eligibility checks (skeleton logic).
    @XposedHooker
    class WhitelistHooker : Hooker {
        companion object {
            // Runs before the original whitelist check.
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback) {
                // Skeleton policy: allow queried package; real policy will filter safely.
                callback.returnAndSkip(true)
            }
        }
    }

    // Observes dual-app list queries without altering data yet.
    @XposedHooker
    class ManagerHooker : Hooker {
        companion object {
            // Runs before the manager service returns its list.
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback) {
                // No-op observer placeholder; logging will be added after research.
            }
        }
    }
}
