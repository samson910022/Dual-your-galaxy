package com.galaxydual.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// LSPosed entry point (Modern Xposed API, API 102, interceptor-chain model).
//
// Entry class listed in META-INF/xposed/java_init.list (exactly one line).
// Scopes mirrored in META-INF/xposed/scope.list.
//
// All code below is original (MIT) written for this project: it drives only
// public LibXposed APIs plus reflection against Samsung ROM classes. No code
// is copied from OnePlus/GPL hook projects (see docs/CREDITS.md).
class DualHook : XposedModule() {

    // System server entry: hook the Samsung dual-app service whitelist queries.
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        hookDualAppManagerService(param.classLoader)
    }

    // Called for every loaded package; filters to recommended scopes only.
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName !in SUPPORTED_SCOPES) {
            // Non-target process: detach so this entry stops receiving callbacks here.
            // Only lifecycle callbacks are affected; installed hooks keep working.
            detach()
            return
        }
        if (param.packageName == DA_AGENT_PACKAGE) {
            hookWhiteListApps(param.defaultClassLoader)
        }
        // "system" itself is handled via onSystemServerStarting; settings needs no
        // method hooks yet (kept in scope for future settings-side checks).
    }

    // Hooks DualAppManagerService#getAllWhitelistedPackages and
    // #isInstalledWhitelistedPackage when present on this Samsung ROM.
    private fun hookDualAppManagerService(classLoader: ClassLoader) {
        val target = findFirstAvailableClass(classLoader, SERVICE_CLASS_CANDIDATES)
            ?: return logi("DualAppManagerService not present, skip system_server hooks")
        // Hook whitelist query methods only; install/remove paths are never hooked.
        for (methodName in SERVICE_WHITELIST_METHODS) {
            val overloads = target.declaredMethods.filter { it.name == methodName }
            if (overloads.isEmpty()) {
                logi("method not found: ${target.name}#$methodName")
                continue
            }
            overloads.forEach { hookWithDeoptimize(it, HOOK_ID_PREFIX + methodName) }
        }
    }

    // Hooks the daagent-side whitelist holder reflectively.
    private fun hookWhiteListApps(classLoader: ClassLoader) {
        val target = try {
            classLoader.loadClass(WHITE_LIST_APPS_CLASS)
        } catch (_: Throwable) {
            return logi("$WHITE_LIST_APPS_CLASS not present, skip daagent hooks")
        }
        // Best-effort diagnostics: confirm the expected constant exists.
        try {
            val field = target.getDeclaredField(WHITELIST_FIELD_NAME)
            logi("found $WHITELIST_FIELD_NAME : ${field.type.name} static=${Modifier.isStatic(field.modifiers)}")
        } catch (_: Throwable) {
            logi("field $WHITELIST_FIELD_NAME not found on $WHITE_LIST_APPS_CLASS")
        }
        // Patch the static whitelist right after <clinit> finishes.
        try {
            hookClassInitializer(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(HOOK_ID_WHITELIST_CLINIT)
                .intercept { chain ->
                    chain.proceed()
                    patchWhitelistField(target)
                    null
                }
        } catch (t: Throwable) {
            logi("hook <clinit> failed: ${t.message}")
        }
        // Hook any *hitelist* query methods (e.g. isWhitelisted) if they exist.
        target.declaredMethods
            .filter { it.name.contains("hitelist", ignoreCase = true) }
            .forEach { hookWithDeoptimize(it, HOOK_ID_PREFIX + it.name) }
    }

    // Installs one hook with inlining protection and fail-safe exception mode.
    private fun hookWithDeoptimize(method: Method, hookId: String) {
        try {
            // Deoptimize so ART callers cannot inline past the hooked callee;
            // returns false when nothing needed deoptimizing (safe to ignore).
            try {
                deoptimize(method)
            } catch (_: Throwable) {
                // Non-fatal: hooking still works when the target is not inlined.
            }
            hook(method)
                // PROTECTIVE placeholder: hooker bugs are logged and skipped
                // instead of crashing system_server / daagent.
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(hookId)
                .intercept { chain -> interceptWhitelistCall(chain) }
            logi("hooked ${method.declaringClass.name}#${method.name}")
        } catch (t: Throwable) {
            logi("hook failed ${method.declaringClass.name}#${method.name}: ${t.message}")
        }
    }

    // Shared whitelist policy: extend-only, never shrink.
    // Booleans keep an original "true"; collections/arrays get EXTRA entries appended.
    // EXTRA_WHITELIST_PACKAGES is empty by default, so hooks are observe-only placeholders
    // until a real policy source (e.g. RemotePreferences) is wired in.
    private fun interceptWhitelistCall(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (result is Boolean) {
            if (result) return true
            val pkg = chain.args.firstOrNull() as? String
            return pkg != null && EXTRA_WHITELIST_PACKAGES.contains(pkg)
        }
        if (EXTRA_WHITELIST_PACKAGES.isEmpty()) return result
        if (result is List<*>) {
            return result + EXTRA_WHITELIST_PACKAGES.filter { !result.contains(it) }
        }
        if (result is Collection<*>) {
            val merged = result.toMutableList()
            merged.addAll(EXTRA_WHITELIST_PACKAGES.filter { !merged.contains(it) })
            return merged
        }
        if (result != null && result.javaClass.isArray &&
            result.javaClass.componentType == String::class.java
        ) {
            @Suppress("UNCHECKED_CAST")
            val current = result as Array<String>
            return (current.toList() + EXTRA_WHITELIST_PACKAGES.filter { !current.contains(it) })
                .toTypedArray()
        }
        return result
    }

    // Merges EXTRA entries into DUAL_APP_WHITELIST_PACKAGES after class init.
    private fun patchWhitelistField(target: Class<*>) {
        if (EXTRA_WHITELIST_PACKAGES.isEmpty()) return
        try {
            val field = target.getDeclaredField(WHITELIST_FIELD_NAME)
            if (!Modifier.isStatic(field.modifiers)) return
            field.isAccessible = true
            val current = field.get(null)
            if (current is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                (current as MutableList<String>).addAll(
                    EXTRA_WHITELIST_PACKAGES.filter { !current.contains(it) },
                )
            } else if (current != null && current.javaClass.isArray &&
                current.javaClass.componentType == String::class.java
            ) {
                @Suppress("UNCHECKED_CAST")
                val arr = current as Array<String>
                field.set(
                    null,
                    (arr.toList() + EXTRA_WHITELIST_PACKAGES.filter { !arr.contains(it) }).toTypedArray(),
                )
            }
        } catch (_: Throwable) {
            // Leave the original whitelist untouched on any reflection mismatch.
        }
    }

    private fun findFirstAvailableClass(classLoader: ClassLoader, names: List<String>): Class<*>? {
        for (name in names) {
            try {
                return classLoader.loadClass(name)
            } catch (_: Throwable) {
                // Try the next candidate.
            }
        }
        return null
    }

    private fun logi(msg: String) {
        try {
            log(Log.INFO, TAG, msg)
        } catch (_: Throwable) {
            // Logging must never break the hook path.
        }
    }

    companion object {
        private const val TAG = "GalaxyDualHook"

        // Minimum supported Xposed API version for this module.
        const val MIN_API_VERSION = 102

        const val SYSTEM_SCOPE = "system"
        const val DA_AGENT_PACKAGE = "com.samsung.android.da.daagent"
        const val SETTINGS_PACKAGE = "com.android.settings"

        // Recommended LSPosed scopes, mirrored in META-INF/xposed/scope.list.
        val SUPPORTED_SCOPES = setOf(
            SYSTEM_SCOPE,
            DA_AGENT_PACKAGE,
            SETTINGS_PACKAGE,
        )

        // System-service candidates tried in order; first loadable class wins.
        // Class names differ across One UI releases, so all access is reflective.
        private val SERVICE_CLASS_CANDIDATES = listOf(
            "com.android.server.DualAppManagerService",
            "com.samsung.android.server.dual.DualAppManagerService",
            "com.android.server.dual.DualAppManagerService",
        )

        // Whitelist query methods only; install/remove paths are never hooked.
        private val SERVICE_WHITELIST_METHODS = listOf(
            "getAllWhitelistedPackages",
            "isInstalledWhitelistedPackage",
        )

        private const val WHITE_LIST_APPS_CLASS = "com.samsung.android.da.daagent.WhiteListApps"
        private const val WHITELIST_FIELD_NAME = "DUAL_APP_WHITELIST_PACKAGES"

        private const val HOOK_ID_PREFIX = "galaxydual:"
        private const val HOOK_ID_WHITELIST_CLINIT = HOOK_ID_PREFIX + "WhiteListApps.<clinit>"

        // Placeholder policy set: empty = observe-only. Wire to RemotePreferences
        // (or a managed config) when a real allowlist source is added.
        @JvmField
        val EXTRA_WHITELIST_PACKAGES: Set<String> = emptySet()
    }
}
