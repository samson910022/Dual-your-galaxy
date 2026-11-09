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

    // Hooks DualAppManagerService whitelist read-query methods when present on
    // this Samsung ROM (Stage 1 safest set; install-state composite queries are
    // excluded until device-verified). Returns the number of installed hooks
    // (0 = nothing hooked, so the caller skips the active-marker write and
    // stays honestly undetected).
    //
    // Device evidence (S24 OneUI 9, services.jar dexdump-verified):
    // com.android.server.DualAppManagerService exists with whitelist methods
    // (e.g. getAllWhitelistedPackages, isInstalledWhitelistedPackage/Internal
    // variants). Stage 1 hooks only the pure whitelist-list query from
    // SERVICE_WHITELIST_METHODS (install-state-sensitive methods excluded,
    // fail-closed); lspd logs on devices that match candidate 1 should still
    // show deoptimize+hook success for the hooked method(s). Candidates 2-3
    // stay as fallback.
    private fun hookDualAppManagerService(classLoader: ClassLoader): Int {
        val target = findFirstAvailableClass(classLoader, SERVICE_CLASS_CANDIDATES)
        if (target == null) {
            logi("DualAppManagerService not present, skip system_server hooks")
            return 0
        }
        // Hook whitelist query methods only; install/remove paths are never hooked.
        var installed = 0
        var guardSkipped = 0
        var installFailures = 0
        for (methodName in SERVICE_WHITELIST_METHODS) {
            try {
                val overloads = target.declaredMethods.filter { it.name == methodName }
                if (overloads.isEmpty()) {
                    logi("method not found: ${target.name}#$methodName (no overloads)")
                    continue
                }
                overloads.forEach { method ->
                    val decision = try {
                        WhitelistHookEligibility.evaluate(method)
                    } catch (t: Throwable) {
                        guardSkipped++
                        try {
                            logi("skip hook ${target.name}#${method.name}: eligibility evaluation failed (${t.message})")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    if (decision is WhitelistHookEligibility.Skip) {
                        guardSkipped++
                        try {
                            logi("skip hook ${target.name}#${method.name}: ${decision.reason}")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    try {
                        if (hookWithDeoptimize(method, HOOK_ID_PREFIX + method.name)) {
                            installed++
                        } else {
                            installFailures++
                        }
                    } catch (t: Throwable) {
                        installFailures++
                        try {
                            logi("hook install failed: ${target.name}#${method.name}: ${t.message}")
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                try {
                    logi("hook lookup failed: ${target.name}#$methodName: ${t.message}")
                } catch (_: Throwable) {
                }
            }
        }
        if (installed == 0 && (guardSkipped > 0 || installFailures > 0)) {
            logi(
                "system_server whitelist hooks: installed=0, guardSkipped=$guardSkipped, " +
                    "installFailures=$installFailures on ${target.name} (observe-only)",
            )
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
        // query. Eligibility gating (WhitelistHookEligibility) is applied as a
        // security backstop so installed-composite queries on ROM variants are
        // not hooked; unknown/ambiguous signatures are skipped fail-closed.
        // Install logging is best-effort.
        try {
            target.declaredMethods
                .filter { it.name.contains("hitelist", ignoreCase = true) }
                .forEach { method ->
                    val decision = try {
                        WhitelistHookEligibility.evaluate(method)
                    } catch (t: Throwable) {
                        try {
                            logi("skip hook ${target.name}#${method.name}: eligibility evaluation failed (${t.message})")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    if (decision is WhitelistHookEligibility.Skip) {
                        try {
                            logi("skip hook ${target.name}#${method.name}: ${decision.reason}")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    try {
                        if (hookWithDeoptimize(method, HOOK_ID_PREFIX + method.name)) installed++
                    } catch (t: Throwable) {
                        try {
                            logi("hook install failed: ${target.name}#${method.name}: ${t.message}")
                        } catch (_: Throwable) {
                        }
                    }
                }
        } catch (t: Throwable) {
            try {
                logi("hook *hitelist* sweep failed on ${target.name}: ${t.message}")
            } catch (_: Throwable) {
            }
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
    // on it is eligibility-gated (WhitelistHookEligibility) and hooked with
    // the shared extend-only WhitelistPolicy.apply interceptor
    // (interceptWhitelistCall). Read queries only -- install/remove paths
    // are never hooked. Installed-composite queries and unknown/ambiguous
    // Boolean signatures are skipped (fail-closed). With a non-empty policy
    // a hit flips/adds the listed packages; with an empty policy (today) the
    // interceptor returns the original result unchanged (observe-only).
    // JVM tests cover only the WhitelistPolicy.apply decision; install and
    // callback behavior require an LSPosed daagent process and are NOT
    // covered by JVM tests.
    private fun hookDaagentQueryMethods(classLoader: ClassLoader): Int {
        var hooksInstalled = 0
        var classesLoaded = 0
        var classLookupMisses = 0
        var methodLookupFailures = 0
        var overloadsMatched = 0
        var guardSkipped = 0
        var installFailures = 0
        val totalCandidates = DaagentQueryHooks.DAAGENT_QUERY_CLASS_CANDIDATES.size
        for (className in DaagentQueryHooks.DAAGENT_QUERY_CLASS_CANDIDATES) {
            val target = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                classLookupMisses++
                continue
            }
            classesLoaded++
            var classInstalled = 0
            for (methodName in DaagentQueryHooks.DAAGENT_QUERY_METHOD_NAMES) {
                val overloads = try {
                    target.declaredMethods.filter { it.name == methodName }
                } catch (_: Throwable) {
                    methodLookupFailures++
                    continue
                }
                if (overloads.isEmpty()) {
                    continue
                }
                overloadsMatched += overloads.size
                overloads.forEach { method ->
                    val decision = try {
                        WhitelistHookEligibility.evaluate(method)
                    } catch (t: Throwable) {
                        // Fail-closed at call site (privileged hook path).
                        guardSkipped++
                        try {
                            logi("skip hook ${target.name}#${method.name}: eligibility evaluation failed (${t.message})")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    if (decision is WhitelistHookEligibility.Skip) {
                        guardSkipped++
                        try {
                            logi("skip hook ${target.name}#${method.name}: ${decision.reason}")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    try {
                        if (hookWithDeoptimize(method, HOOK_ID_PREFIX + method.name)) {
                            classInstalled++
                        } else {
                            installFailures++
                        }
                    } catch (t: Throwable) {
                        installFailures++
                        try {
                            logi("hook install failed: ${target.name}#${method.name}: ${t.message}")
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            if (classInstalled > 0) {
                logi("using ${target.name} for daagent query hooks (+$classInstalled)")
                hooksInstalled += classInstalled
            }
        }
        if (hooksInstalled == 0) {
            val cause = when {
                classesLoaded == 0 -> "no candidate class loaded"
                overloadsMatched == 0 && methodLookupFailures == 0 -> "candidate classes loaded but no queried method present"
                overloadsMatched == 0 && methodLookupFailures > 0 -> "method lookups failed on loaded classes"
                guardSkipped > 0 && installFailures == 0 -> "all matched overloads skipped by eligibility guard"
                installFailures > 0 -> "hook install failed for eligible overloads"
                else -> "no hooks installed"
            }
            logi(
                "daagent query hooks: installed=0 ($cause, observe-only), " +
                    "classesLoaded=$classesLoaded/$totalCandidates, classLookupMisses=$classLookupMisses, " +
                    "overloadsMatched=$overloadsMatched, guardSkipped=$guardSkipped, " +
                    "methodLookupFailures=$methodLookupFailures, installFailures=$installFailures",
            )
        } else {
            logi(
                "daagent query hooks: installed=$hooksInstalled, " +
                    "classesLoaded=$classesLoaded/$totalCandidates, classLookupMisses=$classLookupMisses, " +
                    "overloadsMatched=$overloadsMatched, guardSkipped=$guardSkipped, " +
                    "methodLookupFailures=$methodLookupFailures, installFailures=$installFailures",
            )
        }
        return hooksInstalled
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
    // unchanged. Stage 1 (empty effective policy, default): composite queries
    // still hit the runtime fail-closed backstop, but all other calls return
    // the original without describe/args/guard/apply overhead (chain.proceed
    // already ran). Boolean true returns immediately when policy is applied;
    // unexpected arguments for Boolean false are logged best-effort by apply.
    // For Boolean false rewrites, DualHook resolves the package argument only
    // for allowlisted signatures (WhitelistSignatureGuard) and uses strict
    // signature mode so unverified overloads remain observe-only.
    // H2: the effective set is PolicySource.effective(seed + remote). No remote
    // source is wired, so behavior today is identical to the static seed
    // (observe-only when empty).
    private fun interceptWhitelistCall(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        return try {
            val extra = effectivePolicy()
            val executable = try {
                chain.executable
            } catch (_: Throwable) {
                null
            }
            val methodName = try {
                executable?.name
            } catch (_: Throwable) {
                null
            }
            // Defense-in-depth: installed-and-whitelist composite queries are
            // excluded in Stage 1 (install-state/userId ambiguity). Skip hook
            // install paths gate these, but keep a runtime fail-closed backstop
            // regardless of policy.
            if (methodName != null &&
                WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery(methodName)
            ) {
                val desc = try {
                    executable?.let { WhitelistPolicy.describe(it) } ?: "unknown-method"
                } catch (_: Throwable) {
                    "unknown-method"
                }
                try {
                    logi(
                        "${WhitelistPatch.OBSERVE_ONLY_MARKER} $desc: " +
                            "installed-whitelist composite query excluded " +
                            "(Stage 1 fail-closed); returning original",
                    )
                } catch (_: Throwable) {
                    // Logging must never break the hook path.
                }
                return@try result
            }
            if (extra.isEmpty()) {
                return@try result
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
            val packageArgIndex = executable?.let {
                WhitelistSignatureGuard.resolveBooleanPackageArgIndex(it)
            }
            val declaredReturnType = (executable as? Method)?.returnType
            WhitelistPolicy.apply(
                result = result,
                args = args,
                extra = extra,
                methodDesc = desc,
                onLog = ::logi,
                packageArgIndex = packageArgIndex,
                declaredReturnType = declaredReturnType,
                strictSignature = true,
            )
        } catch (t: Throwable) {
            val desc = try {
                chain.executable?.let { WhitelistPolicy.describe(it) } ?: "unknown-method"
            } catch (_: Throwable) {
                "unknown-method"
            }
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
        //
        // Stage 1 (safest): isInstalledWhitelistedPackage is install-state-
        // sensitive (installed AND whitelisted composite, userId ambiguity) and
        // is EXCLUDED from rewrite hooks until device-verified (fail-closed).
        // getAllWhitelistedPackages remains (pure whitelist list) and is guarded
        // by signature/return guards at runtime (Phase 3 wiring).
        private val SERVICE_WHITELIST_METHODS = listOf(
            "getAllWhitelistedPackages",
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
