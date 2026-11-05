// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
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
    //
    // Phase F4b-P2 (issue #6): when at least one hook installs, best-effort
    // record the hook-active marker (HookMarker, scope "system_server") so
    // the app UI can show LSPosed enable state without any IPC. markActive
    // never throws by contract; the extra try/catch is a defensive seam only.
    // Honesty: a stale marker from a previous boot (file survives reboots)
    // can read "active" until rewritten -- the app labels the signal as
    // existence+scope evidence, staleness hardening stays DEVICE-UNVERIFIED.
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val installed = hookDualAppManagerService(param.classLoader)
        if (installed > 0) {
            try {
                HookMarker.markActive(HookMarker.SCOPE_SYSTEM_SERVER, onLog = ::logi)
            } catch (_: Throwable) {
                // Defensive only: markActive never throws by contract.
            }
        }
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
            // Phase F4b-P2: same marker contract as system_server above,
            // scope "daagent". Both processes read-merge-write the same file,
            // so each installed scope survives the other's write.
            val installed = hookWhiteListApps(param.defaultClassLoader)
            if (installed > 0) {
                try {
                    HookMarker.markActive(HookMarker.SCOPE_DAAGENT, onLog = ::logi)
                } catch (_: Throwable) {
                    // Defensive only: markActive never throws by contract.
                }
            }
        }
    }

    // Hooks DualAppManagerService#getAllWhitelistedPackages and
    // #isInstalledWhitelistedPackage when present on this Samsung ROM.
    // Returns the number of installed hooks (0 = nothing hooked, so the
    // caller skips the active-marker write and stays honestly undetected).
    //
    // Device evidence (S24 OneUI 9, services.jar dexdump-verified):
    // com.android.server.DualAppManagerService exists with both methods
    // (getAllWhitelistedPackages x2 refs, isInstalledWhitelistedPackage x4
    // + Internal variant); lspd logs confirm candidate 1 wins with
    // deoptimize+hook success on all boots. Candidates 2-3 stay as fallback.
    private fun hookDualAppManagerService(classLoader: ClassLoader): Int {
        val target = findFirstAvailableClass(classLoader, SERVICE_CLASS_CANDIDATES)
        if (target == null) {
            logi("DualAppManagerService not present, skip system_server hooks")
            return 0
        }
        // Hook whitelist query methods only; install/remove paths are never hooked.
        var installed = 0
        for (methodName in SERVICE_WHITELIST_METHODS) {
            val overloads = target.declaredMethods.filter { it.name == methodName }
            if (overloads.isEmpty()) {
                logi("method not found: ${target.name}#$methodName")
                continue
            }
            overloads.forEach { if (hookWithDeoptimize(it, HOOK_ID_PREFIX + methodName)) installed++ }
        }
        return installed
    }

    // Hooks the daagent-side whitelist holder reflectively. Returns the
    // number of installed hooks (clinit patch hook + query-method hooks);
    // 0 keeps the caller from writing the active marker.
    //
    // Device evidence (S24 OneUI 9, DAAgent v3.7.00.0, dexdump-verified):
    // provider.WhiteListApps is a PUBLIC ABSTRACT constants-only holder
    // (3 static-final String[] + <clinit> + Base64 helper, zero query
    // methods), so the <clinit> patch is the effective daagent hook and the
    // fuzzy *hitelist* sweep correctly installs nothing here. Read queries
    // live in DualAppDBHelper.getAllInstalledWhitelistedPackages,
    // DAUtility.getWhiteListPkgs/*
    // (*InSystemServer), and DualAppManager.*WhitelistPackage* -- future
    // non-empty-policy work should target those with method-level evidence,
    // keeping this holder as the patch point.
    private fun hookWhiteListApps(classLoader: ClassLoader): Int {
        val target = findFirstAvailableClass(classLoader, WHITE_LIST_APPS_CLASS_CANDIDATES)
        if (target == null) {
            logi("WhiteListApps not present, skip daagent hooks")
            return 0
        }
        // Attribution for on-device forensics: the winning candidate disambiguates
        // provider path vs legacy fallback in lspd logs (field-found lines alone
        // cannot, both candidates carry the same field names).
        logi("using ${target.name} for daagent hooks")
        // Best-effort diagnostics: confirm the expected constant exists.
        // First candidate present wins; CHINA variant is accepted as fallback.
        val diagnosticField = WHITELIST_FIELD_CANDIDATES.firstOrNull { name ->
            try {
                target.getDeclaredField(name)
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (diagnosticField != null) {
            try {
                val field = target.getDeclaredField(diagnosticField)
                logi("found $diagnosticField : ${field.type.name} static=${Modifier.isStatic(field.modifiers)}")
            } catch (_: Throwable) {
                logi("field $diagnosticField not found on ${target.name}")
            }
        } else {
            logi("field $WHITELIST_FIELD_NAME not found on ${target.name}")
        }
        // Patch the static whitelist right after <clinit> finishes.
        var installed = 0
        try {
            hookClassInitializer(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(HOOK_ID_WHITELIST_CLINIT)
                .intercept { chain ->
                    chain.proceed()
                    patchWhitelistField(target)
                    null
                }
            installed++
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
            .forEach { if (hookWithDeoptimize(it, HOOK_ID_PREFIX + it.name)) installed++ }
        return installed
    }

    // Installs one hook with best-effort inlining protection and fail-safe
    // exception mode. Returns true only when the hook installed (the P2
    // active marker counts these; a failed install keeps the module honestly
    // "not detected" instead of claiming enable state it never earned).
    //
    // Honesty note: deoptimize(Executable) exists, but without caller identity
    // info we can only deoptimize the hooked callee itself (best-effort);
    // callers that already inlined it are NOT touched, so a short method
    // inlined before this runs may still bypass the hook. The anti-inline
    // effect is unverified on-device (no Samsung test unit); the return value
    // is logged so a no-op is visible instead of silently swallowed.
    private fun hookWithDeoptimize(method: Method, hookId: String): Boolean {
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
            return true
        } catch (t: Throwable) {
            logi("hook failed ${method.declaringClass.name}#${method.name}: ${t.message}")
            return false
        }
    }

    // Thin interceptor: runs the original, then applies the shared
    // extend-only policy (WhitelistPolicy, unit-tested). Unknown signatures
    // stay observe-only with a log line; nothing is ever narrowed here.
    // H2: the effective set is PolicySource.effective(seed + remote). No remote
    // source is wired, so behavior today is identical to the static seed
    // (observe-only when empty).
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
        return WhitelistPolicy.apply(result, args, effectivePolicy(), desc, ::logi)
    }

    // Effective policy for this process: static seed (+ a RemotePreferences
    // snapshot if a policy source is ever wired -- none is today, so the
    // remote snapshot stays empty and behavior is identical to the static
    // seed). Single seam for any future policy work; IPC-free. NOTE: not
    // covered by JVM tests (instantiating DualHook needs the framework); the
    // decision logic inside PolicySource/WhitelistPatch is what tests lock.
    internal fun effectivePolicy(): Set<String> =
        PolicySource.effective(EXTRA_WHITELIST_PACKAGES, remotePolicySnapshot())

    // Remote snapshot hook: empty (no policy source wired; a future change
    // would read LibXposed RemotePreferences here -- hook-side read-only +
    // change listener into a volatile cache).
    // Kept as a separate function so that future work touches exactly one seam.
    internal fun remotePolicySnapshot(): Set<String> = emptySet()

    // Merges policy entries into DUAL_APP_WHITELIST_PACKAGES after class init.
    // Thin reflective shell over the pure WhitelistPatch decision object
    // (unit-tested): every give-up path logs why (locatable diagnostics);
    // success logs how many entries were added. The original whitelist is
    // never shrunk.
    private fun patchWhitelistField(target: Class<*>) {
        val extra = effectivePolicy()
        if (extra.isEmpty()) return
        // First existing field wins: standard name preferred, CHINA variant fallback.
        val fieldName = WHITELIST_FIELD_CANDIDATES.firstOrNull { name ->
            try {
                target.getDeclaredField(name)
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (fieldName == null) {
            logi("patch skipped on ${target.name}: no whitelist field found")
            return
        }
        try {
            val field = target.getDeclaredField(fieldName)
            // Static check BEFORE get(null): an instance field's get(null)
            // throws IllegalArgumentException, which would mask the precise
            // "not static" diagnosis. WhitelistPatch keeps its own isStatic
            // guard as defense-in-depth for direct callers/tests.
            if (!Modifier.isStatic(field.modifiers)) {
                logi("skip patch $fieldName on ${target.name}: field is not static")
                return
            }
            field.isAccessible = true
            val current = try {
                field.get(null)
            } catch (t: Throwable) {
                logi("patch $fieldName on ${target.name} failed: ${t.message}")
                return
            }
            when (val outcome = WhitelistPatch.apply(current, true, extra, fieldName, target.name)) {
                is WhitelistPatch.Outcome.PatchedInPlace ->
                    logi("patched $fieldName on ${target.name} (MutableList): +${outcome.added} entries")
                is WhitelistPatch.Outcome.Replaced -> {
                    try {
                        field.set(null, outcome.value)
                    } catch (t: Throwable) {
                        logi("patch $fieldName on ${target.name} failed: ${t.message}")
                        return
                    }
                    logi("patched $fieldName on ${target.name} (String[]): +${outcome.added} entries")
                }
                is WhitelistPatch.Outcome.Skipped -> logi(outcome.reason)
            }
        } catch (t: Throwable) {
            // Leave the original whitelist untouched on any reflection mismatch.
            logi("patch $fieldName on ${target.name} failed: ${t.message}")
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

        // Scope constants are single-sourced in HookScopes (pure JVM, unit-
        // testable). Kept here as aliases so existing call sites and the CI
        // scope-parity grep keep working.
        const val SYSTEM_SCOPE = HookScopes.SYSTEM_SCOPE

        // Real framework package name LSPosed delivers for the system_server
        // process via onPackageLoaded. Listed here only so the early-return
        // above stays in sync; it is NOT a hook target and must never detach.
        const val ANDROID_PACKAGE = HookScopes.ANDROID_PACKAGE
        const val DA_AGENT_PACKAGE = HookScopes.DA_AGENT_PACKAGE

        // Recommended LSPosed scopes, mirrored in META-INF/xposed/scope.list.
        // H4: self-scope packages (H3) were evaluated and retired; the F4b-P2
        // file marker is the single hook-active signal (see HOOK.md §1.4).
        // NOTE: com.android.settings stays out (D2): no settings-side hook
        // exists, so scoping it would only widen the injection surface.
        // SYSTEM_SCOPE never matches onPackageLoaded; it is kept here for
        // scope.list parity.
        val SUPPORTED_SCOPES: Set<String> = HookScopes.SUPPORTED_SCOPES

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

        private val WHITE_LIST_APPS_CLASS_CANDIDATES = listOf(
            "com.samsung.android.da.daagent.provider.WhiteListApps",
            "com.samsung.android.da.daagent.WhiteListApps",
        )
        private const val WHITELIST_FIELD_NAME = "DUAL_APP_WHITELIST_PACKAGES"
        private val WHITELIST_FIELD_CANDIDATES = listOf(
            "DUAL_APP_WHITELIST_PACKAGES",
            "DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA",
        )

        private const val HOOK_ID_PREFIX = "galaxydual:"
        private const val HOOK_ID_WHITELIST_CLINIT = HOOK_ID_PREFIX + "WhiteListApps.<clinit>"

        // Static policy seed: empty = observe-only. H2 routes it through
        // PolicySource.effective(seed, remote); no remote source is wired,
        // so behavior is identical to the static seed. Union-only: entries
        // are never removed.
        @JvmField
        val EXTRA_WHITELIST_PACKAGES: Set<String> = PolicySource.STATIC_SEED
    }
}
