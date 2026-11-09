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
    // Marker semantics: install evidence only, not proof policy took effect;
    // empty policy => observe-only today (see WhitelistPolicy.apply).
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
            // Marker semantics: install evidence only (at least one daagent
            // hook installed: WhiteListApps <clinit> and/or a query-method
            // hook below), not proof policy took effect. Empty policy =>
            // no-op today (early return, semantically skipped; no Outcome
            // emitted, returns before reflection; WhitelistPolicy.apply
            // returns the original result unchanged); even with non-empty
            // policy, readable static-final holder fields => ObserveOnly,
            // only a method-level query hook can carry the policy
            // (see DualHook.patchWhitelistField + WhitelistPatch.patchFields/apply;
            // chain patchWhitelistField->patchFields->apply).
            // DEVICE-UNVERIFIED: the daagent query-method hooks below
            // (hookDaagentQueryMethods) run only inside the LSPosed daagent
            // process; JVM tests cover the WhitelistPolicy.apply decision
            // only, never the on-device install/callback.
            var installed = hookWhiteListApps(param.defaultClassLoader)
            installed += hookDaagentQueryMethods(param.defaultClassLoader)
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
            try {
                val overloads = target.declaredMethods.filter { it.name == methodName }
                if (overloads.isEmpty()) {
                    logi("method not found: ${target.name}#$methodName")
                    continue
                }
                overloads.forEach { if (hookWithDeoptimize(it, HOOK_ID_PREFIX + methodName)) installed++ }
            } catch (t: Throwable) {
                logi("hook lookup failed: ${target.name}#$methodName: ${t.message}")
            }
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
    // methods), so the <clinit> patch is install evidence only
    // (static-final => ObserveOnly, never force-written; see
    // WhitelistPatch.apply isFinal branch) and the fuzzy *hitelist* sweep
    // correctly installs nothing here. The effective policy path is the
    // method-level query hooks in hookDaagentQueryMethods below: when one
    // of those query methods is hit, WhitelistPolicy.apply carries a
    // non-empty policy (false->true for a listed package, collection/array
    // union); when none is hit -- or the policy is empty -- behavior stays
    // observe-only. This holder stays as the <clinit> patch point only.
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
        // Best-effort diagnostics: report every candidate field's presence
        // (both standard and _FOR_CHINA names are patch targets below, so the
        // log shows which ones this ROM actually carries).
        val presentFields = WHITELIST_FIELD_CANDIDATES.filter { name ->
            try {
                target.getDeclaredField(name)
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (presentFields.isEmpty()) {
            logi("field $WHITELIST_FIELD_NAME not found on ${target.name}")
        } else {
            for (diagnosticField in presentFields) {
                try {
                    val field = target.getDeclaredField(diagnosticField)
                    logi(
                        "found $diagnosticField : ${field.type.name} " +
                            "static=${Modifier.isStatic(field.modifiers)} " +
                            "final=${Modifier.isFinal(field.modifiers)}",
                    )
                } catch (_: Throwable) {
                    logi("field $diagnosticField not found on ${target.name}")
                }
            }
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
            // Install evidence only (see patchWhitelistField below for effect);
            // deferred/observe-only reasoning lives in comments, not in the log.
            logi("hooked ${target.name}#<clinit> (install evidence only)")
        } catch (t: Throwable) {
            logi("hook <clinit> failed: ${t.message}")
        }
        // Hook any *hitelist* query methods (e.g. isWhitelisted) if they exist.
        // Deliberately fuzzy: without on-device evidence the exact Samsung
        // method names are unknown, so narrowing the match could miss the real
        // query. Install logging is best-effort, and signatures the shared policy
        // does not understand stay observe-only (see WhitelistPolicy.apply).
        try {
            target.declaredMethods
                .filter { it.name.contains("hitelist", ignoreCase = true) }
                .forEach { if (hookWithDeoptimize(it, HOOK_ID_PREFIX + it.name)) installed++ }
        } catch (t: Throwable) {
            logi("hook *hitelist* sweep failed on ${target.name}: ${t.message}")
        }
        return installed
    }

    // Hooks the daagent-side read-query methods reflectively. Returns the
    // number of installed hooks (0 = nothing hooked).
    //
    // Stage1 method-level candidates (DEVICE-UNVERIFIED): S24 dexdump names
    // the holder (WhiteListApps, static-final String[] => ObserveOnly) but
    // does not pin the query-holder package layout, so every class in
    // DaagentQueryHooks.DAAGENT_QUERY_CLASS_CANDIDATES that loads is tried
    // and every DaagentQueryHooks.DAAGENT_QUERY_METHOD_NAMES entry present
    // on it is hooked with the shared extend-only WhitelistPolicy.apply
    // interceptor (interceptWhitelistCall). Read queries only --
    // install/remove paths are never hooked. With a non-empty policy a hit
    // flips/adds the listed packages; with an empty policy (today) the
    // interceptor returns the original result unchanged (observe-only). If
    // no candidate class/method exists on this ROM, this returns 0 and the
    // daagent side stays observe-only via the holder patch above.
    // JVM tests cover only the WhitelistPolicy.apply decision; install and
    // callback behavior require an LSPosed daagent process and are NOT
    // covered by JVM tests.
    private fun hookDaagentQueryMethods(classLoader: ClassLoader): Int {
        var installed = 0
        for (className in DaagentQueryHooks.DAAGENT_QUERY_CLASS_CANDIDATES) {
            val target = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                continue
            }
            var classInstalled = 0
            for (methodName in DaagentQueryHooks.DAAGENT_QUERY_METHOD_NAMES) {
                val overloads = try {
                    target.declaredMethods.filter { it.name == methodName }
                } catch (_: Throwable) {
                    continue
                }
                if (overloads.isEmpty()) {
                    continue
                }
                overloads.forEach {
                    try {
                        if (hookWithDeoptimize(it, HOOK_ID_PREFIX + methodName)) classInstalled++
                    } catch (t: Throwable) {
                        logi("hook install failed: ${target.name}#${methodName}: ${t.message}")
                    }
                }
            }
            if (classInstalled > 0) {
                logi("using ${target.name} for daagent query hooks (+$classInstalled)")
                installed += classInstalled
            }
        }
        if (installed == 0) {
            logi("daagent query hooks: no candidate class/method present (observe-only)")
        }
        return installed
    }

    // Installs one hook with best-effort inlining protection and a request for
    // framework exception protection. Returns true only when the hook installed (the P2
    // active marker counts these; a failed install keeps the module honestly
    // "not detected" instead of claiming enable state it never earned).
    //
    // Honesty note: deoptimize(Executable) exists, but without caller identity
    // info we can only deoptimize the hooked callee itself (best-effort);
    // this does not guarantee an effect on callers that already inlined it, so a short method
    // inlined before this runs may still bypass the hook. The anti-inline
    // effect is unverified on-device; the return value is logged best-effort without
    // inferring why the framework returned it.
    private fun hookWithDeoptimize(method: Method, hookId: String): Boolean =
        HookInstaller.install(method, hookId, ::deoptimize, { target, id ->
            hook(target)
                // Request framework protection for hook exceptions; this does not
                // guarantee system_server / daagent cannot crash in every case.
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(id)
                .intercept { chain -> interceptWhitelistCall(chain) }
        }, ::logi)

    // Thin interceptor: runs the original, then applies the shared
    // extend-only policy (WhitelistPolicy, unit-tested). Unknown shapes stay
    // unchanged; non-Boolean results return early without unknown-shape logging
    // when the policy is empty. Boolean true returns immediately; unexpected
    // arguments for Boolean false are logged best-effort even with empty policy.
    // For Boolean false rewrites, DualHook resolves the package argument only
    // for allowlisted signatures (WhitelistSignatureGuard) and uses strict
    // signature mode so unverified overloads remain observe-only.
    // H2: the effective set is PolicySource.effective(seed + remote). No remote
    // source is wired, so behavior today is identical to the static seed
    // (observe-only when empty).
    private fun interceptWhitelistCall(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val executable = try {
            chain.executable
        } catch (_: Throwable) {
            null
        }
        val desc = try {
            executable?.let { WhitelistPolicy.describe(it) } ?: "unknown-method"
        } catch (_: Throwable) {
            "unknown-method"
        }
        val args: List<Any?> = try {
            chain.args
        } catch (_: Throwable) {
            emptyList()
        }
        return try {
            val packageArgIndex = executable?.let {
                WhitelistSignatureGuard.resolveBooleanPackageArgIndex(it)
            }
            val declaredReturnType = (executable as? Method)?.returnType
            WhitelistPolicy.apply(
                result = result,
                args = args,
                extra = effectivePolicy(),
                methodDesc = desc,
                onLog = ::logi,
                packageArgIndex = packageArgIndex,
                declaredReturnType = declaredReturnType,
                strictSignature = true,
            )
        } catch (t: Throwable) {
            try {
                logi(
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $desc: policy apply failed " +
                        "(${t.javaClass.name}); returning original",
                )
            } catch (_: Throwable) {
                // Logging must never break the hook path.
            }
            result
        }
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

    // Merges policy entries into the WhiteListApps static whitelists after
    // class init. Thin shell over WhitelistPatch.patchFields (unit-tested):
    // BOTH candidate fields are attempted (the ROM may read the _FOR_CHINA
    // field per region/sales code, so first-wins would silently miss region
    // devices); per-field outcomes are logged best-effort for diagnostics.
    // The original whitelist is never shrunk.
    //
    // Empty policy today (STATIC_SEED and the remote snapshot are both
    // empty; no policy source wired): returns before touching reflection,
    // so current behavior is observe-only and unaffected by the final-field
    // / dual-field handling below.
    private fun patchWhitelistField(target: Class<*>) {
        val extra = effectivePolicy()
        if (extra.isEmpty()) {
            logi(
                "skip patch on ${target.name}: empty policy " +
                    "(${WhitelistPatch.OBSERVE_ONLY_MARKER})",
            )
            return
        }
        val results = WhitelistPatch.patchFields(target, WHITELIST_FIELD_CANDIDATES, extra)
        for ((fieldName, outcome) in results) {
            when (outcome) {
                is WhitelistPatch.Outcome.PatchedInPlace ->
                    logi("patched $fieldName on ${target.name} (MutableList): +${outcome.added} entries")
                is WhitelistPatch.Outcome.Replaced -> {
                    if (outcome.added == 0) {
                        logi("patch $fieldName on ${target.name} already complete, no write")
                    } else {
                        logi("patched $fieldName on ${target.name} (String[]): +${outcome.added} entries")
                    }
                }
                is WhitelistPatch.Outcome.Skipped -> logi(outcome.reason)
                is WhitelistPatch.Outcome.ObserveOnly -> logi(outcome.reason)
            }
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

        // Stage1 daagent read-query candidates moved to
        // DaagentQueryHooks (framework-free) so JVM contract tests can lock
        // them without loading DualHook/Xposed. See DaagentQueryHooks.

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
