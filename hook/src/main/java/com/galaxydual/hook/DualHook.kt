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
        // system vs android: "system" is the LSPosed scope pseudo-name for
        // system_server (served exclusively via onSystemServerStarting above),
        // while "android" is the real framework package name LSPosed delivers
        // here for that same process. Neither means "a hookable app", so
        // return WITHOUT detach(): detaching would silence later lifecycle
        // callbacks in system_server itself.
        if (param.packageName == SYSTEM_SCOPE || param.packageName == ANDROID_PACKAGE) return
        if (param.packageName !in SUPPORTED_SCOPES) {
            // Non-target process: detach so this entry stops receiving callbacks here.
            // Only lifecycle callbacks are affected; installed hooks keep working.
            detach()
            return
        }
        if (param.packageName == DA_AGENT_PACKAGE) {
            hookWhiteListApps(param.defaultClassLoader)
        }
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
        // Deliberately fuzzy: without on-device evidence the exact Samsung
        // method names are unknown, so narrowing the match could miss the real
        // query. Every install is logged, and signatures the shared policy
        // does not understand stay observe-only (see WhitelistPolicy.apply).
        target.declaredMethods
            .filter { it.name.contains("hitelist", ignoreCase = true) }
            .forEach { hookWithDeoptimize(it, HOOK_ID_PREFIX + it.name) }
    }

    // Installs one hook with best-effort inlining protection and fail-safe
    // exception mode.
    //
    // Honesty note: deoptimize(Executable) exists, but without caller identity
    // info we can only deoptimize the hooked callee itself (best-effort);
    // callers that already inlined it are NOT touched, so a short method
    // inlined before this runs may still bypass the hook. The anti-inline
    // effect is unverified on-device (no Samsung test unit); the return value
    // is logged so a no-op is visible instead of silently swallowed.
    private fun hookWithDeoptimize(method: Method, hookId: String) {
        try {
            try {
                val deoptimized = deoptimize(method)
                if (deoptimized) {
                    logi("deoptimized ${method.declaringClass.name}#${method.name}")
                } else {
                    logi(
                        "deoptimize no-op ${method.declaringClass.name}#${method.name} " +
                            "(already interpreted or unsupported; hook proceeds, effect unverified)",
                    )
                }
            } catch (t: Throwable) {
                // Non-fatal: hooking still works when the target is not inlined.
                logi(
                    "deoptimize failed ${method.declaringClass.name}#${method.name}: " +
                        "${t.message}; hook proceeds, effect unverified",
                )
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

    // Thin interceptor: runs the original, then applies the shared
    // extend-only policy (WhitelistPolicy, unit-tested). Unknown signatures
    // stay observe-only with a log line; nothing is ever narrowed here.
    private fun interceptWhitelistCall(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val desc = try {
            WhitelistPolicy.describe(chain.executable)
        } catch (_: Throwable) {
            "unknown-method"
        }
        val args: List<Any?> = try {
            chain.args
        } catch (_: Throwable) {
            emptyList()
        }
        return WhitelistPolicy.apply(result, args, EXTRA_WHITELIST_PACKAGES, desc, ::logi)
    }

    // Merges EXTRA entries into DUAL_APP_WHITELIST_PACKAGES after class init.
    // Every give-up path logs why (locatable diagnostics); success logs how
    // many entries were added. The original whitelist is never shrunk.
    private fun patchWhitelistField(target: Class<*>) {
        if (EXTRA_WHITELIST_PACKAGES.isEmpty()) return
        try {
            val field = target.getDeclaredField(WHITELIST_FIELD_NAME)
            if (!Modifier.isStatic(field.modifiers)) {
                logi("skip patch $WHITELIST_FIELD_NAME on ${target.name}: field is not static")
                return
            }
            field.isAccessible = true
            val current = field.get(null)
            if (current is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val list = current as MutableList<String>
                val add = EXTRA_WHITELIST_PACKAGES.filter { !list.contains(it) }
                list.addAll(add)
                logi("patched $WHITELIST_FIELD_NAME on ${target.name} (MutableList): +${add.size} entries")
            } else if (current != null && current.javaClass.isArray &&
                current.javaClass.componentType == String::class.java
            ) {
                @Suppress("UNCHECKED_CAST")
                val arr = current as Array<String>
                val add = EXTRA_WHITELIST_PACKAGES.filter { !arr.contains(it) }
                field.set(
                    null,
                    (arr.toList() + add).toTypedArray(),
                )
                logi("patched $WHITELIST_FIELD_NAME on ${target.name} (String[]): +${add.size} entries")
            } else {
                logi(
                    "skip patch $WHITELIST_FIELD_NAME on ${target.name}: unsupported type " +
                        (current?.javaClass?.name ?: "null"),
                )
            }
        } catch (t: Throwable) {
            // Leave the original whitelist untouched on any reflection mismatch.
            logi("patch $WHITELIST_FIELD_NAME on ${target.name} failed: ${t.message}")
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

        // LSPosed scope pseudo-name for system_server (served via
        // onSystemServerStarting; never arrives as an onPackageLoaded name).
        const val SYSTEM_SCOPE = "system"

        // Real framework package name LSPosed delivers for the system_server
        // process via onPackageLoaded. Listed here only so the early-return
        // above stays in sync; it is NOT a hook target and must never detach.
        const val ANDROID_PACKAGE = "android"
        const val DA_AGENT_PACKAGE = "com.samsung.android.da.daagent"

        // Recommended LSPosed scopes, mirrored in META-INF/xposed/scope.list.
        // NOTE: com.android.settings was removed (D2): no settings-side hook
        // exists, so scoping it only widened the injection surface. SYSTEM_SCOPE
        // never matches onPackageLoaded; it is kept here for scope.list parity.
        val SUPPORTED_SCOPES = setOf(
            SYSTEM_SCOPE,
            DA_AGENT_PACKAGE,
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
