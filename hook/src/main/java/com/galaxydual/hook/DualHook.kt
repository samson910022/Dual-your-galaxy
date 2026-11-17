// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
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
        // Warm the remote-policy snapshot FIRST (before installing any hook)
        // so system_server's first hooked call already sees a warmed cache.
        // Fail-closed: failures leave the snapshot empty (no behavior change).
        ensureRemotePolicyState()
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
            // hook below and/or the BBC getPossibleDualAppPackages hook), not
            // proof policy took effect. Empty policy =>
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
            // Warm the remote-policy snapshot BEFORE installing any daagent
            // hook (same first-call reasoning as onSystemServerStarting).
            ensureRemotePolicyState()
            var installed = hookWhiteListApps(param.defaultClassLoader)
            installed += hookDaagentQueryMethods(param.defaultClassLoader)
            // BBC (com.samsung.bbc) injector: install count feeds the same
            // active-marker write condition as the two hook paths above.
            installed += installBbcHooks(param.defaultClassLoader)
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
    // number of installed hooks (<clinit> patch hook only from this holder;
    // query-method hooks are installed by hookDaagentQueryMethods);
    // 0 keeps the caller from writing the active marker.
    //
    // Device evidence (S24 OneUI 9, DAAgent v3.7.00.0, dexdump-verified):
    // provider.WhiteListApps is a PUBLIC ABSTRACT constants-only holder
    // (3 static-final String[] + <clinit> + Base64 helper, zero query
    // methods), so the <clinit> patch is install evidence only
    // (static-final => ObserveOnly, never force-written; see
    // WhitelistPatch.apply isFinal branch). The fuzzy *hitelist* sweep is
    // Stage 1 diagnose-only (no hook installs) due to mutation/read-only
    // ambiguity on ROM variants (S24 has zero query methods; no functional
    // loss). The effective policy path is the method-level query hooks in
    // hookDaagentQueryMethods below: when one of those query methods is hit,
    // WhitelistPolicy.apply carries a non-empty policy (false->true for a
    // listed package, collection/array union); when none is hit -- or the
    // policy is empty -- behavior stays observe-only. This holder stays as
    // the <clinit> patch point only.
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
        // Stage 1 diagnose-only *hitelist* sweep (no hook installs): mutation/
        // read-only ambiguity on ROM variants makes fuzzy install unsafe (e.g.
        // removeWhitelistedPackage/addWhitelist*/setWhitelist*/clearWhitelist).
        // S24 WhiteListApps has zero query methods, so no functional loss.
        // Named read-query hooks use explicit allowlists/eligibility elsewhere
        // (hookDaagentQueryMethods). <clinit> hook/count unchanged. Best-effort.
        try {
            target.declaredMethods
                .filter { it.name.contains("hitelist", ignoreCase = true) }
                .forEach { method ->
                    val decision = try {
                        WhitelistHookEligibility.evaluate(method)
                    } catch (t: Throwable) {
                        try {
                            logi(
                                "diagnose-only sweep ${target.name}#${method.name}: " +
                                    "eligibility evaluation failed (${t.message}); " +
                                    "no hook installed (Stage 1 fail-closed)",
                            )
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    val mutationShaped = try {
                        WhitelistSignatureGuard.isPotentialMutationMethod(method.name)
                    } catch (_: Throwable) {
                        true // fail-closed classification for diagnosis safety
                    }
                    val safety = if (mutationShaped) "mutation-shaped/unsafe-to-hook" else "read-like-name"
                    try {
                        val reason = when (decision) {
                            is WhitelistHookEligibility.Eligible -> decision.reason
                            is WhitelistHookEligibility.Skip -> decision.reason
                        }
                        logi(
                            "diagnose-only sweep ${target.name}#${method.name}: " +
                                "safety=$safety, eligibility=${reason}; " +
                                "no hook installed (Stage 1 fail-closed, diagnose-only)",
                        )
                    } catch (_: Throwable) {
                    }
                }
        } catch (t: Throwable) {
            try {
                logi("diagnose-only *hitelist* sweep failed on ${target.name}: ${t.message}")
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
    //
    // Caller-side research note (issue #10 Stage1): the effective fix needs
    // either (a) identifying callers that already inlined the callee on the
    // supported ROM/version and deoptimizing THOSE callers, or (b) choosing a
    // hook point the inline copies cannot bypass. Caller identities require
    // on-device ART evidence and are unknown today, so this path stays
    // callee-only. Do NOT blanket-deoptimize the whole system_server to
    // compensate; keep the proven-minimal scope (known callers + hooked
    // callee). JVM-testable decision lives in CallerDeoptPlan with
    // orchestration in HookInstaller.installWithCallers; the device wiring
    // point is knownCallersFor() below (DEVICE-UNVERIFIED, currently empty).
    private fun hookWithDeoptimize(method: Method, hookId: String): Boolean =
        HookInstaller.install(method, hookId, ::deoptimize, ::installInterceptHook, ::logi)

    // Thin interceptor: runs the original, then applies the shared extend-only
    // policy (WhitelistPolicy, unit-tested). Unknown shapes stay unchanged.
    //
    // Phase 4 fast paths / fail-closed guards:
    // - Installed-whitelist composite queries (WhitelistSignatureGuard) are
    //   excluded at runtime regardless of policy (Stage 1 fail-closed backstop;
    //   install paths also gate these) and return the original.
    // - Empty effective policy (Stage 1 default): returns original with lazy
    //   desc/args/guard/apply overhead avoided for non-composite calls
    //   (chain.proceed already ran).
    // - Non-empty policy: resolves method desc/args lazily, uses strict
    //   allowlisted package-arg index (WhitelistSignatureGuard) for Boolean
    //   rewrites so unverified overloads remain observe-only.
    //
    // H2: effective set = PolicySource.effective(seed + remote); no remote
    // source wired, so behavior today is identical to the static seed.
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
                return result
            }
            if (extra.isEmpty()) {
                // Empty-policy fast path stays silent by design.
                return result
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
            val genericReturnType = (executable as? Method)?.genericReturnType
            WhitelistPolicy.apply(
                result = result,
                args = args,
                extra = extra,
                methodDesc = desc,
                onLog = ::logi,
                packageArgIndex = packageArgIndex,
                declaredReturnType = declaredReturnType,
                strictSignature = true,
                genericReturnType = genericReturnType,
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

    // Effective policy for this process: static seed + the RemotePreferences
    // snapshot (RemotePolicyState; warmed by ensureRemotePolicyState() at the
    // injection points). IPC-free. With NON_EMPTY_POLICY_EFFECTIVE_GATE open
    // (shipped default, on-device verified), a non-empty effective policy
    // reaches the injection points here; with the gate closed, effective()
    // returns emptySet() regardless of the inputs (fail-closed seam). The
    // remote snapshot is always sanitized inside PolicySource.effective.
    // Single seam for any future policy work. NOTE: not covered by JVM tests
    // (instantiating DualHook needs the framework); the decision logic inside
    // PolicySource/WhitelistPatch is what tests lock.
    internal fun effectivePolicy(): Set<String> =
        PolicySource.effective(EXTRA_WHITELIST_PACKAGES, remotePolicySnapshot())

    // Remote snapshot hook: process-wide volatile cache of the LibXposed
    // RemotePreferences group "galaxydual_policy" (RemotePolicyState), warmed
    // by ensureRemotePolicyState() at onSystemServerStarting/onPackageLoaded.
    // Field is READ from the @Volatile snapshot only — no IPC, no binder on
    // the intercept hot path. While the snapshot is empty (no policy source
    // wired, or gate closed), behavior is identical to the static seed.
    internal fun remotePolicySnapshot(): Set<String> = RemotePolicyState.get()

    // Caller-side wiring point (issue #10 Stage1, DEVICE-UNVERIFIED).
    //
    // Future device work resolves the inlined callers of [callee] for the
    // running ROM/version (ART evidence required) and returns them here as
    // Executables (methods or constructors) so hookWithCallerDeoptimize() can
    // deoptimize callers + hook callee via
    // CallerDeoptPlan/HookInstaller.installWithCallers. Returns empty today,
    // which is exactly the current callee-only best-effort behavior -- no
    // blanket deoptimization of system_server. Not covered by JVM tests
    // beyond the empty default (instantiating DualHook needs the framework).
    @Suppress("UNUSED_PARAMETER")
    internal fun knownCallersFor(callee: Method): List<Executable> = emptyList()

    // Caller-aware install seam (issue #10 Stage1, DEVICE-UNVERIFIED).
    // Wired to knownCallersFor(); with the current empty list it behaves
    // identically to hookWithDeoptimize(). Call sites stay on
    // hookWithDeoptimize() until per-ROM caller evidence exists, so behavior
    // is unchanged today. Callers may be methods or constructors (Executables).
    // Planning/resolver failures (including knownCallersFor or installWithCallers
    // throwing) fall back to callee-only hookWithDeoptimize(); the callee hook
    // is still attempted (best-effort, honest diagnostics).
    @Suppress("unused")
    private fun hookWithCallerDeoptimize(method: Method, hookId: String): Boolean =
        try {
            HookInstaller.installWithCallers(
                method,
                hookId,
                knownCallersFor(method),
                ::deoptimize,
                ::installInterceptHook,
                ::logi,
            )
        } catch (t: Throwable) {
            logi(
                "caller plan failed [$hookId] ${HookInstaller.executableSignature(method)}: " +
                    "${t::class.java.name}${if (t.message != null) ": ${t.message}" else ""}; " +
                    "falling back to callee-only hook, effect unverified",
            )
            hookWithDeoptimize(method, hookId)
        }

    // Shared hook builder config (behavior-preserving): used by both callee-only
    // and caller-aware install paths so the intercept setup stays identical.
    private fun installInterceptHook(target: Method, id: String) {
        hook(target)
            // Request framework protection for hook exceptions; this does not
            // guarantee system_server / daagent cannot crash in every case.
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId(id)
            .intercept { chain -> interceptWhitelistCall(chain) }
    }

    // --- BBC (com.samsung.bbc) injector: Dual Messenger available-apps list ---
    // UNION-only, fail-closed. With an empty effective policy (the common case:
    // user has not ticked anything in the policy editor) effectivePolicy()
    // returns emptySet() and the hook is a no-op returning Samsung's original
    // result object, identical to not hooking at all. Any unexpected ROM shape,
    // null, exception, or non-String element also returns the ORIGINAL result
    // object. Device-verified evidence lives in docs/TAB7_BBC_INJECTION_PLAN.md.

    private val REMOTE_POLICY_GROUP = RemotePolicyState.GROUP

    // Per-process one-line rate limits for the BBC entry/merge evidence logs.
    private val bbcHitLog = java.util.Collections.synchronizedSet(java.util.HashSet<String>())
    private val bbcMergedLog = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    // Warms the process-wide volatile remote-policy snapshot. Called at the
    // injection points (onSystemServerStarting / onPackageLoaded) BEFORE any
    // hook is installed, so the first hooked call already sees a (possibly
    // empty) warmed cache. [prefsProvider] is invoked at most once per
    // process; any failure stays fail-closed (empty snapshot, no change).
    private fun ensureRemotePolicyState() {
        RemotePolicyState.ensureInitialized {
            try {
                getRemotePreferences(REMOTE_POLICY_GROUP)
            } catch (_: Throwable) {
                null
            }
        }
    }

    // BBC install path: probe (BBC shared library resolvable on this ROM,
    // method present), resolve the callee reflectively, resolve the caller
    // Executables (best-effort, may be empty), then installWithCallers
    // (caller deopt best-effort, callee deopt + hook). Returns 1 when the
    // callee hook installed, else 0; BOTH outcomes emit an `BBC-HOOK
    // installed=N` ledger line (pid included, per runbook/spec §12.2).
    // Any unexpected failure stays fail-closed (log + 0, zero behavior
    // change, zero hooks installed).
    private fun installBbcHooks(classLoader: ClassLoader): Int {
        // android.os.Process.myPid() is expected not to throw, but keep the
        // install ledger line total anyway.
        val pid = try {
            android.os.Process.myPid()
        } catch (_: Throwable) {
            -1
        }
        return try {
            if (!BbcInjector.probeBbcAvailable(classLoader, ::logi)) {
                logi("BBC-HOOK installed=0 (probe failed, pid=$pid)")
                return 0
            }
            val wrapperClass = classLoader.loadClass(BbcInjector.BBC_PM_WRAPPER_CLASS)
            val method = wrapperClass.getDeclaredMethod(
                BbcInjector.BBC_PM_GET_POSSIBLE_METHOD,
                android.content.Context::class.java,
            )
            val callers = BbcInjector.resolveCallers(classLoader, ::logi)
            if (HookInstaller.installWithCallers(
                    method,
                    BbcInjector.BBC_HOOK_ID,
                    callers,
                    ::deoptimize,
                    ::installBbcInterceptHook,
                    ::logi,
                )
            ) {
                logi("BBC-HOOK installed=1 (daagent, pid=$pid)")
                1
            } else {
                logi("BBC-HOOK installed=0 (hook install returned false, pid=$pid)")
                0
            }
        } catch (t: Throwable) {
            // Fail-closed: BBC absent/unexpected shape/hook failure => no hook,
            // no behavior change; the daagent keeps Samsung's original list.
            try {
                logi("BBC-HOOK install failed: ${t.message}")
            } catch (_: Throwable) {
                // Logging must never break the hook path.
            }
            try {
                logi("BBC-HOOK installed=0 (install exception, pid=$pid)")
            } catch (_: Throwable) {
                // Logging must never break the hook path.
            }
            0
        }
    }

    // BBC interceptor builder: identical protective setup to
    // installInterceptHook (framework protection + stable id).
    private fun installBbcInterceptHook(target: Method, id: String) {
        hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId(id)
            .intercept { chain -> interceptBbcCall(chain) }
    }

    // BBC interceptor: run the original first (never skipped, never replaced),
    // then — only with a non-empty effective policy — UNION-append the policy
    // packages that this device reports installed+enabled to the END of
    // Samsung's own list (order of the original list preserved, entries never
    // removed/reordered). Fail-closed at every step: policy lookup error,
    // unexpected result type, or any non-String element => the ORIGINAL
    // result object is returned unchanged.
    private fun interceptBbcCall(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val extra = try {
            effectivePolicy()
        } catch (_: Throwable) {
            return result
        }
        val pid = try {
            android.os.Process.myPid()
        } catch (_: Throwable) {
            -1
        }
        if (extra.isEmpty()) {
            // Empty policy: one entry line per process at most. No return-value
            // change (observe-only).
            logBbcHitOnce("BBC-HOOK callback getPossibleDualAppPackages (pid=$pid)")
            return result
        }
        logBbcHitOnce("BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=$pid)")
        return try {
            val raw = result as? List<*> ?: run {
                logi("BBC-HOOK unexpected result type; returning original (pid=$pid)")
                return result
            }
            val original = ArrayList<String>(raw.size)
            for (e in raw) {
                original.add(e as? String ?: return result)
            }
            // F-06: the BBC callee's first argument IS the Context (the
            // daagent passes its own); prefer it for the PackageManager. The
            // reflective ActivityThread path is kept ONLY as the fallback for
            // a missing/non-Context argument.
            val pm: android.content.pm.PackageManager? = try {
                val ctx = chain.args.getOrNull(0) as? android.content.Context
                if (ctx != null) {
                    try {
                        ctx.packageManager
                    } catch (_: Throwable) {
                        null // arg present but PM unavailable: probes stay false, fail-closed
                    }
                } else {
                    bbcCurrentPackageManager()
                }
            } catch (_: Throwable) {
                null
            }
            // Resolve ApplicationInfo ONCE per package per merge (one binder
            // round-trip instead of one per predicate); the memo is local to
            // this call and never cached beyond it. Hits and misses (incl.
            // exceptions, mapped to null) are both memoized.
            val applicationInfoMemo = LinkedHashMap<String, android.content.pm.ApplicationInfo?>()
            fun applicationInfo(pkg: String): android.content.pm.ApplicationInfo? {
                if (applicationInfoMemo.containsKey(pkg)) return applicationInfoMemo[pkg]
                val info = try {
                    pm?.getApplicationInfo(pkg, 0)
                } catch (_: Throwable) {
                    null
                }
                applicationInfoMemo[pkg] = info
                return info
            }
            val merged = BbcInjector.mergeAvailableList(
                original,
                extra,
                { pkg -> try { applicationInfo(pkg) != null } catch (_: Throwable) { false } },
                { pkg -> try { applicationInfo(pkg)?.enabled == true } catch (_: Throwable) { false } },
            )
            // Identity-preserving fast path: nothing merged => original object.
            if (merged === original) {
                if (logBbcNoMergeOnce(pid)) {
                    val preds = extra.take(4).joinToString(", ") { pkg ->
                        "$pkg: installed=${applicationInfo(pkg) != null}, enabled=${applicationInfo(pkg)?.enabled == true}"
                    } + if (extra.size > 4) ", …(+${extra.size - 4} more)" else ""
                    logi("BBC-HOOK merge added 0 packages (policy=${extra.size}: $preds) (pid=$pid)")
                }
                return result
            }
            if (logBbcMergedOnce(pid)) {
                logi("BBC-HOOK merged +${merged.size - original.size} packages (total ${merged.size}, pid=$pid)")
            }
            // mergeAvailableList already returns a fresh ArrayList on merge;
            // do not wrap it in a second copy.
            merged
        } catch (t: Throwable) {
            // Fail-closed: merge errors never surface to the caller.
            result
        }
    }

    // android.app.ActivityThread is @hide-excluded from the compile-time SDK
    // stub (compileSdk 34), so the current Application is reached through a
    // cached reflective method; the framework class is present at runtime in
    // every app process. Memoized Method only — currentApplication() is
    // re-invoked per probe so an early null never sticks. Kept ONLY as the
    // fallback for a missing/non-Context BBC callee argument (F-06).
    private val activityThreadCurrentApplication: java.lang.reflect.Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication")
        } catch (_: Throwable) {
            null
        }
    }

    private fun bbcCurrentPackageManager(): android.content.pm.PackageManager? = try {
        val app = activityThreadCurrentApplication?.invoke(null) as? android.app.Application
        app?.packageManager
    } catch (_: Throwable) {
        null
    }

    // One line per process for the empty-policy entry evidence.
    private fun logBbcHitOnce(msg: String) {
        if (bbcHitLog.add(msg)) {
            try {
                logi(msg)
            } catch (_: Throwable) {
                // Logging must never break the hook path.
            }
        }
    }

    // First merge per process logs; subsequent merges stay silent.
    private fun logBbcMergedOnce(pid: Int): Boolean = bbcMergedLog.add("pid:$pid")
    private fun logBbcNoMergeOnce(pid: Int): Boolean = bbcMergedLog.add("nomerge:$pid")

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
