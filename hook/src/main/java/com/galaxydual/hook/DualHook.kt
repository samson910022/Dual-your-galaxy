// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences
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

    init {
        // Stage H2-P1: plug the RemotePreferences-backed snapshot into the
        // pure policy source. Assignment only (no IPC here). NOTE: production
        // reads go through EffectivePolicyCache (see effectivePolicy), which
        // calls readRemotePolicy() directly and preserves its null
        // fail-closed signal ("read failed" keeps last-known-good). This seam
        // exists ONLY for JVM tests that drive PolicySource in isolation; it
        // preserves null (no null->empty compression) so
        // remotePolicySnapshot() fail-closed semantics stay testable.
        // Phase 4: null is the failed-read signal -- never compress to empty
        // here (empty is a successful read and would be cached as policy).
        PolicySource.remotePolicyReader = { readRemotePolicy() }
    }

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
                    logi("method not found: ${shortClass(target)}#${methodName.take(200)} (no overloads)")
                    continue
                }
                overloads.forEach { method ->
                    val decision = try {
                        WhitelistHookEligibility.evaluate(method)
                    } catch (t: Throwable) {
                        guardSkipped++
                        try {
                            logi("skip hook ${shortClass(target)}#${method.name.take(200)}: eligibility evaluation failed (${shortEx(t)}: ${shortMsg(t)})")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    if (decision is WhitelistHookEligibility.Skip) {
                        guardSkipped++
                        try {
                            logi("skip hook ${shortClass(target)}#${method.name.take(200)}: ${decision.reason.take(200)}")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    try {
                        if (hookWithDeoptimize(method, HOOK_ID_PREFIX + method.name.take(200))) {
                            installed++
                        } else {
                            installFailures++
                        }
                    } catch (t: Throwable) {
                        installFailures++
                        try {
                            logi("hook install failed: ${shortClass(target)}#${method.name.take(200)}: ${shortEx(t)}: ${shortMsg(t)}")
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                try {
                    logi("hook lookup failed: ${shortClass(target)}#${methodName.take(200)}: ${shortEx(t)}: ${shortMsg(t)}")
                } catch (_: Throwable) {
                }
            }
        }
        if (installed == 0 && (guardSkipped > 0 || installFailures > 0)) {
            logi(
                "system_server whitelist hooks: installed=0, guardSkipped=$guardSkipped, " +
                    "installFailures=$installFailures on ${shortClass(target)} (observe-only)",
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
        logi("using ${shortClass(target)} for daagent hooks")
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
            logi("field $WHITELIST_FIELD_NAME not found on ${shortClass(target)}")
        } else {
            for (diagnosticField in presentFields) {
                try {
                    val field = target.getDeclaredField(diagnosticField)
                    logi(
                        "found ${diagnosticField.take(200)} : ${shortClass(field.type)} " +
                            "static=${Modifier.isStatic(field.modifiers)} " +
                            "final=${Modifier.isFinal(field.modifiers)}",
                    )
                } catch (_: Throwable) {
                    logi("field ${diagnosticField.take(200)} not found on ${shortClass(target)}")
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
                    try {
                        patchWhitelistField(target)
                    } catch (t: Throwable) {
                        logi("patch field error: ${shortEx(t)}: ${shortMsg(t)}")
                    }
                    null
                }
            installed++
            // Install evidence only (see patchWhitelistField below for effect);
            // deferred/observe-only reasoning lives in comments, not in the log.
            logi("hooked ${shortClass(target)}#<clinit> (install evidence only)")
        } catch (t: Throwable) {
            logi("hook <clinit> failed: ${shortEx(t)}: ${shortMsg(t)}")
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
                                "diagnose-only sweep ${shortClass(target)}#${method.name.take(200)}: " +
                                    "eligibility evaluation failed (${shortEx(t)}: ${shortMsg(t)}); " +
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
                            "diagnose-only sweep ${shortClass(target)}#${method.name.take(200)}: " +
                                "safety=$safety, eligibility=${reason.take(200)}; " +
                                "no hook installed (Stage 1 fail-closed, diagnose-only)",
                        )
                    } catch (_: Throwable) {
                    }
                }
        } catch (t: Throwable) {
            try {
                logi("diagnose-only *hitelist* sweep failed on ${shortClass(target)}: ${shortEx(t)}: ${shortMsg(t)}")
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
                            logi("skip hook ${shortClass(target)}#${method.name.take(200)}: eligibility evaluation failed (${shortEx(t)}: ${shortMsg(t)})")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    if (decision is WhitelistHookEligibility.Skip) {
                        guardSkipped++
                        try {
                            logi("skip hook ${shortClass(target)}#${method.name.take(200)}: ${decision.reason.take(200)}")
                        } catch (_: Throwable) {
                        }
                        return@forEach
                    }
                    try {
                        if (hookWithDeoptimize(method, HOOK_ID_PREFIX + method.name.take(200))) {
                            classInstalled++
                        } else {
                            installFailures++
                        }
                    } catch (t: Throwable) {
                        installFailures++
                        try {
                            logi("hook install failed: ${shortClass(target)}#${method.name.take(200)}: ${shortEx(t)}: ${shortMsg(t)}")
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            if (classInstalled > 0) {
                logi("using ${shortClass(target)} for daagent query hooks (+$classInstalled)")
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
    // H2: effective set = seed + cached remote snapshot (read through
    // EffectivePolicyCache, see effectivePolicy). The app writes the draft to
    // the same LSPosed remote-prefs group
    // (dual_policy/extra_whitelist_packages); DEVICE-UNVERIFIED end-to-end, so
    // the snapshot may still be empty and behavior stays observe-only when
    // empty.
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
                        "(${shortEx(t)}: ${shortMsg(t)}); returning original",
                )
            } catch (_: Throwable) {
                // Logging must never break the hook path.
            }
            result
        }
    }

    // Effective policy for this process: static seed + remote snapshot. The
    // RemotePreferences-backed reader is called through EffectivePolicyCache,
    // which caches the merged set and recomputes when invalidated, after a
    // failed read, or once the bounded freshness TTL expires. A failed read
    // keeps the last known good value applied (or the seed alone when none is
    // cached), so a transient service absence does not silently empty the
    // policy; with no cached value the policy stays observe-only. Single seam
    // for any future policy work. NOTE: not covered by JVM tests (instantiating
    // DualHook needs the framework); the cache + budget + latch are pure and
    // unit-tested (EffectivePolicyCacheTest / PolicyRefreshBudgetTest /
    // RemoteCapabilityLatchTest).
    //
    // Freshness model (P1-1, corrected for LSPosed API 102.0.0): there is NO
    // cross-process push. IXposedService 102.0.0 exposes no remote-preference
    // callback transaction; RemotePreferences fetches mMap once and only
    // notifies LOCAL listeners; XposedService.getRemotePreferences is
    // computeIfAbsent cached forever. The registered listener below is therefore
    // same-process only and never fires for an app-process write. Freshness is
    // POLL ONLY: the cache TTL (60s) re-reads the cached instance; even the
    // poll may return stale data until reboot (daemon restart rebuilds the
    // map). See docs/HOOK.md §1.1 + CrossProcessFreshnessContractTest.
    //
    // Hot-path budget (P1-2, hardened K4 P2#6, half-open M4 P2#6, Phase3 herd):
    // the reader below runs through PolicyRefreshGate (at-most-one guard +
    // stuck-worker breaker with timed half-open probe + stuck-busy rebuild) on
    // a zero-queue executor with a 50ms stall budget
    // (PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS); on timeout / reader
    // failure the query falls back to seed/last-known-good (cache
    // failureCooldown applies); on contention (guard busy / stuck
    // short-circuit / zero-queue rejection) the gate throws
    // PolicyRefreshContentionException so the cache returns seed-only WITHOUT
    // renewing its failureCooldown (herd collapse without permanent seed-only).
    // Queue stays bounded (<=1, no growth) even when cancel(true) cannot stop
    // an uninterruptible Binder read; breaker resets on invalidate or on a
    // half-open probe success (30s initial, x2 to 5min cap) so silent upstream
    // recovery still reopens without a push signal.
    // Critical nesting fix: the capability probe runs on its own gate+executor
    // (capabilityGate) OUTSIDE the policy gate. The policy gate's reader wraps
    // only the prefs getStringSet (readRemotePrefs); the probe is sequenced
    // before it and never nests inside the outer guard. Sharing one
    // non-reentrant Semaphore(1) gate for both would self-contend: the outer
    // leader holds the permit while the worker thread tries the inner probe on
    // the same gate, tryAcquire always fails, the first true can never latch
    // (permanent seed-only). Separate gates keep the probe latchable.
    internal fun effectivePolicy(): Set<String> {
        // Phase7 P2 end-to-end deadline: single 50ms total budget shared by
        // capability probe + prefs read (plus budget-aware follower grace).
        // t0 sampled at entry with true System.nanoTime (never mocked);
        // arithmetic via pure DeadlineBudget (same function JVM tests use):
        // remaining = total - elapsed; each segment uses min(50, remaining),
        // remaining <= 0 skips fail-closed to null (deadlineExhaustedCount++,
        // monotonic, never reset). The null skip is a failed read for the
        // cache, so its 5s failureCooldown throttles the next retry.
        // Follower grace = min(50, remaining at entry), exhausted forces 0.
        // H8 (sentinel discipline): sample via sampleClock() so a broken
        // nanoTime is explicit (clockOk=false), never a fresh full budget.
        // Budget segments and the follower grace degrade the SAME way:
        // clockOk=false -> no budget spend (reader never runs) and grace 0.
        val t0Sample = DeadlineBudget.sampleClock()
        val t0 = t0Sample.nanos
        val clockOk = t0Sample.clockOk
        fun remainingMs(): Long = if (!clockOk) {
            0L
        } else try {
            DeadlineBudget.remainingMs(t0, DeadlineBudget.nowNanos())
        } catch (_: Throwable) {
            0L
        }
        val graceAtEntry: Long = if (!clockOk) {
            0L
        } else try {
            DeadlineBudget.graceAtEntry(t0, DeadlineBudget.nowNanos())
        } catch (_: Throwable) {
            0L
        }
        val deadlineMs: Long = if (!clockOk) {
            -1L
        } else try {
            (t0 / 1_000_000L) + PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS
        } catch (_: Throwable) {
            -1L
        }
        return effectiveCache.get(
            EXTRA_WHITELIST_PACKAGES,
            nonEmptyPolicyGateOpen = PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE,
            followerGraceMs = graceAtEntry,
            deadlineMs = deadlineMs,
        ) {
            var rem = remainingMs()
            if (rem <= 0) {
                try {
                    deadlineExhaustedCount.incrementAndGet()
                } catch (_: Throwable) {
                }
                return@get null
            }
            val capTimeout = minOf(PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS, rem)
            val capable = isRemoteCapable(capTimeout) ?: return@get null
            if (!capable) return@get emptySet()
            rem = remainingMs()
            if (rem <= 0) {
                try {
                    deadlineExhaustedCount.incrementAndGet()
                } catch (_: Throwable) {
                }
                return@get null
            }
            policyRefreshGate.readBoundedOrThrow(
                reader = { readRemotePrefs() },
                timeoutMs = rem,
            )
        }
    }

    // RemotePreferences-backed policy reader (Stage H2-P1 plumbing, hardened in
    // H2-P2), reached through EffectivePolicyCache via a bounded executor
    // (see effectivePolicy).
    //
    // Reads the same LSPosed remote-prefs group/key the app writes via
    // XposedService.getRemotePreferences(PolicySource.POLICY_PREFS_NAME)
    // (owner-user draft; hook reads user 0). Never throws: the not-capable path
    // returns emptySet() (a successful empty read, cached), while a failed read
    // (framework not attached / group missing / IPC failure) returns null and is
    // logged best-effort only on the good->failed transition. Null is NOT
    // cached: the last known good value stays applied, or the seed alone when
    // none is cached (fail-closed observe-only). A successful read (even empty)
    // returns a set and IS cached.
    //
    // NO cross-process push on API 102.0.0 (P1-1 correction): LSPosed does NOT
    // deliver remote-prefs changes to this process via IRemotePreferenceCallback
    // (no such transaction exists); the listener registered below is
    // same-process only (best-effort hygiene, never the freshness mechanism).
    // Freshness is poll-only via the cache TTL; a version/sequence key bump by
    // the writer + reboot semantics are documented in docs/HOOK.md §1.1.
    // DEVICE-UNVERIFIED: end-to-end read-after-write still needs on-device proof.
    private fun readRemotePolicy(): Set<String>? {
        // Seam for PolicySource.remotePolicyReader (JVM tests): probe +
        // prefs, never throws. Production effectivePolicy() sequences the same
        // steps but keeps the probe OUTSIDE the policy gate (see above); this
        // helper shares the pieces so the seam stays behavior-identical.
        val capable = isRemoteCapable() ?: return null
        if (!capable) return emptySet()
        return readRemotePrefs()
    }

    // Prefs-only read (outer-gate reader): no capability probe inside, so the
    // outer policy gate never nests a probe on the same guard. Never throws:
    // the not-capable path is handled by the caller (emptySet, cached), while
    // a failed read (framework not attached / group missing / IPC failure)
    // returns null and is logged best-effort only on the good->failed
    // transition. Null is NOT cached: the last known good value stays applied,
    // or the seed alone when none is cached (fail-closed observe-only). A
    // successful read (even empty) returns a set and IS cached.
    private fun readRemotePrefs(): Set<String>? {
        return try {
            val prefs = getRemotePreferences(PolicySource.POLICY_PREFS_NAME)
            registerPolicyChangeListener(prefs)
            prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, emptySet()).orEmpty()
        } catch (t: Throwable) {
            // Log only on the good->failed transition; a persistently absent
            // service would otherwise spam the hot path. Null is not cached
            // (see cache).
            if (effectiveCache.shouldLogFailure()) {
                logi("remote policy read failed: ${shortEx(t)}: ${shortMsg(t)}")
            }
            null
        }
    }

    // PROP_CAP_REMOTE probe via RemoteCapabilityLatch (P2-1). A framework
    // without remote prefs stays observe-only with a TTL-gated retry (never
    // a forever latch): a definitive false returns emptySet() (successful
    // empty read, cached 60s by EffectivePolicyCache), so the latch 5s
    // cooldown is an INNER gate reached only on a cache miss (TTL expiry or
    // explicit invalidate). Effective false retry = max(60s TTL, 5s latch).
    // A probe that throws / times out / contends is NOT latched false: it is
    // throttled via reportFalseAt (fixed cooldown, no window sliding) and
    // retried on the next call (returns null, not cached, so the cache
    // 5s failure cooldown -- not the TTL -- governs throw retries). Only a
    // definitive true is latched forever. Latch state is cleared for false
    // on invalidate() so a policy-change signal re-probes immediately
    // (cache.invalidate + latch.onInvalidate together).
    // Phase3: the Binder probe itself runs inside its OWN gate budget
    // (capabilityGate, 50ms stall budget + at-most-one guard + stuck breaker
    // on a dedicated single-thread executor), so a slow
    // getFrameworkProperties() can never pin the query thread and never
    // self-contends with the outer policy gate (independent Semaphore(1)).
    // Phase7: timeoutMs is the remaining end-to-end budget
    // (min(50, remaining) computed by effectivePolicy); default 50 preserves
    // the standalone seam behavior (readRemotePolicy tests).
    private fun isRemoteCapable(
        timeoutMs: Long = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
    ): Boolean? {
        // Fast path: latched true never re-probes (no clock, no gate).
        if (capabilityLatch.isLatchedTrue()) return true
        if (!capabilityLatch.shouldProbe()) return remoteCapableFalseCached
        // Gate-bounded probe: maps the Long property to a Set signal so the
        // existing Set-based gate can bound it (non-empty = capable).
        // Phase7: uses the caller's remaining end-to-end budget (already
        // min(50, remaining)); coerced to >= 0, 0 means immediate timeout
        // path (fail-closed null) without submitting.
        val boundedTimeout = try {
            timeoutMs.coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
        if (boundedTimeout <= 0L) {
            return null
        }
        val probe: Set<String>? = try {
            capabilityGate.readBoundedOrThrow(
                reader = {
                    val v = getFrameworkProperties()
                    if ((v and XposedInterface.PROP_CAP_REMOTE) != 0L) setOf("capable")
                    else emptySet()
                },
                timeoutMs = boundedTimeout,
            )
        } catch (_: PolicyRefreshContentionException) {
            // Gate contention (guard busy / stuck short-circuit / rejection):
            // throttle the next probe via the latch (fixed window, no slide)
            // and fail closed to null. Null IS a failed read for the outer
            // EffectivePolicyCache, so its 5s failureCooldown DOES apply on top
            // of the latch 5s (bounded dual 5s throttle, never a forever pin:
            // the next cooldown expiry retries the probe). Mapping to null is
            // intentional: isRemoteCapable's Boolean?/null contract keeps the
            // cache's single failure path instead of propagating Contention
            // through the loader.
            try {
                capabilityLatch.reportFalseAt(System.nanoTime() / 1_000_000L)
            } catch (_: Throwable) {
            }
            remoteCapableFalseCached = null
            return null
        } catch (_: Throwable) {
            // Submit failure outside the gate state machine: same dual
            // throttle (latch 5s fixed window + outer cache 5s via the null
            // below, both bounded).
            try {
                capabilityLatch.reportFalseAt(System.nanoTime() / 1_000_000L)
            } catch (_: Throwable) {
            }
            remoteCapableFalseCached = null
            return null
        }
        if (probe == null) {
            // Timeout / reader Error (including AssertionError) / interrupt:
            // throttle the throw storm via the latch, log once, fail to null.
            try {
                capabilityLatch.reportFalseAt(System.nanoTime() / 1_000_000L)
            } catch (_: Throwable) {
            }
            remoteCapableFalseCached = null
            if (!remoteCapabilityProbeFailureLogged) {
                remoteCapabilityProbeFailureLogged = true
                logi("remote capability probe failed: TimeoutOrError")
            }
            return null
        }
        return try {
            val capable = probe.isNotEmpty()
            if (capable) {
                capabilityLatch.reportTrue()
                remoteCapableFalseCached = null
            } else {
                capabilityLatch.reportFalse()
                remoteCapableFalseCached = false
            }
            remoteCapabilityProbeFailureLogged = false
            capable
        } catch (t: Throwable) {
            // Latch bookkeeping itself threw (hostile clock): throttle + null.
            try {
                capabilityLatch.reportFalseAt(System.nanoTime() / 1_000_000L)
            } catch (_: Throwable) {
            }
            remoteCapableFalseCached = null
            if (!remoteCapabilityProbeFailureLogged) {
                remoteCapabilityProbeFailureLogged = true
                logi("remote capability probe failed: ${shortEx(t)}: ${shortMsg(t)}")
            }
            null
        }
    }

    // Same-process listener hygiene only (P1-1 correction): on API 102.0.0
    // this listener NEVER fires for a cross-process (app -> hook) write --
    // RemotePreferences only notifies local listeners. Registration is kept
    // best-effort (prevents a stale-instance leak and preserves the JVM seam)
    // but freshness never depends on it; the TTL poll is the mechanism.
    //
    // Phase 4: synchronized + compareAndSet with unregister of the old
    // instance. Phase3 order: register the NEW instance first; only on success
    // unregister the OLD (never lose the listener on a failed register, and
    // never leak a stale instance on success). Assignment only after a
    // successful registration so a failed registration is retried with the
    // next prefs instance instead of being skipped.
    private fun registerPolicyChangeListener(prefs: SharedPreferences) {
        synchronized(listenerLock) {
            if (registeredPrefs === prefs) return
            val old = registeredPrefs
            try {
                prefs.registerOnSharedPreferenceChangeListener(remotePolicyListener)
            } catch (_: Throwable) {
                // Failed registration: keep the old listener (if any) so the
                // next query retries with the next prefs instance; never clear
                // to null and never touch the old registration on failure.
                return
            }
            // New registration succeeded: best-effort drop the old instance.
            if (old != null && old !== prefs) {
                try {
                    old.unregisterOnSharedPreferenceChangeListener(remotePolicyListener)
                } catch (_: Throwable) {
                    // Best effort: a hostile prefs impl must not break the hook.
                }
            }
            registeredPrefs = prefs
        }
    }

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
                "caller plan failed [$hookId] ${shortClass(method.declaringClass)}#${method.name.take(200)}: " +
                    "${shortEx(t)}: ${shortMsg(t)}; " +
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

    // Merges policy entries into the WhiteListApps static whitelists after
    // class init. Thin shell over WhitelistPatch.patchFields (unit-tested):
    // BOTH candidate fields are attempted (the ROM may read the _FOR_CHINA
    // field per region/sales code, so first-wins would silently miss region
    // devices); per-field outcomes are logged best-effort for diagnostics.
    // The original whitelist is never shrunk.
    //
    // Empty policy (STATIC_SEED is empty and the remote draft is not yet
    // persisted / not yet proven end-to-end): returns before touching
    // reflection, so behavior stays observe-only and unaffected by the
    // final-field / dual-field handling below.
    private fun patchWhitelistField(target: Class<*>) {
        val extra = effectivePolicy()
        if (extra.isEmpty()) {
            logi(
                "skip patch on ${shortClass(target)}: empty policy " +
                    "(${WhitelistPatch.OBSERVE_ONLY_MARKER})",
            )
            return
        }
        val results = WhitelistPatch.patchFields(target, WHITELIST_FIELD_CANDIDATES, extra)
        for ((fieldName, outcome) in results) {
            when (outcome) {
                is WhitelistPatch.Outcome.PatchedInPlace ->
                    logi("patched ${fieldName.take(200)} on ${shortClass(target)} (MutableList): +${outcome.added} entries")
                is WhitelistPatch.Outcome.Replaced -> {
                    if (outcome.added == 0) {
                        logi("patch ${fieldName.take(200)} on ${shortClass(target)} already complete, no write")
                    } else {
                        logi("patched ${fieldName.take(200)} on ${shortClass(target)} (String[]): +${outcome.added} entries")
                    }
                }
                is WhitelistPatch.Outcome.Skipped -> logi(outcome.reason.take(200))
                is WhitelistPatch.Outcome.ObserveOnly -> logi(outcome.reason.take(200))
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

    // Log hygiene (shared HookLog): short simple names + truncated messages only;
    // package-qualified names never enter the log. Delegates so all hook logs
    // share one desensitized helper (see HookLog).
    private fun shortEx(t: Throwable): String = HookLog.shortEx(t)

    private fun shortMsg(t: Throwable): String = HookLog.shortMsg(t)

    private fun shortClass(c: Class<*>): String = HookLog.shortClass(c)

    private fun logi(msg: String) {
        try {
            log(Log.INFO, TAG, msg)
        } catch (_: Throwable) {
            // Logging must never break the hook path.
        }
    }

    companion object {
        private const val TAG = "GalaxyDualHook"

        // Stage H2-P2: effective-policy cache + same-process prefs listener
        // (listener is local-only hygiene on API 102.0.0, never cross-process).
        // Companion (static) so a hot-reload instance replacement cannot leak
        // the listener through the instance; the cache survives reloads.
        // Production reads go through this cache via a bounded executor
        // (effectivePolicy); the PolicySource.remotePolicyReader seam remains
        // for JVM tests.
        // H9: wire the cache's seed-only fallback diagnostics. The cache is
        // companion-static while log()/logi() are instance members, so this
        // logger uses android.util.Log directly (same TAG) behind a
        // never-throw guard.
        private val effectiveCache = EffectivePolicyCache(
            onLog = { msg ->
                try {
                    Log.i(TAG, msg)
                } catch (_: Throwable) {
                    // Diagnostics only.
                }
            },
        )

        // Bounded refresh executor (P1-2, hardened K4 P2#6): zero-queue single
        // daemon thread shared by all policy refreshes so a slow binder read
        // never pins the hooked query thread beyond
        // PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS. SynchronousQueue =
        // zero queue: a submit while the worker is busy throws
        // RejectedExecutionException instead of queueing (bounded to <=1
        // outstanding, no growth even when cancel(true) cannot stop an
        // uninterruptible Binder read). Daemon so it never blocks process
        // exit; 60s keep-alive lets the idle thread exit. Single thread
        // bounds IPC fan-out under query herds (EffectivePolicyCache
        // single-flight already collapses concurrent misses to one leader;
        // PolicyRefreshGate adds an at-most-one guard on top).
        // Intentionally never shut down: process-lifetime executor for
        // system_server/daagent (shutdown would strand future refreshes;
        // daemon thread exits with the process).
        private val policyRefreshExecutor: java.util.concurrent.ThreadPoolExecutor =
            PolicyRefreshBudget.newPolicyRefreshExecutor()

        // K4 P2#6 single-flight gate (+M4 half-open probe): at-most-one
        // outstanding guard + stuck-worker circuit breaker in front of the
        // executor. Contention / rejection / short-circuit all map to null
        // (seed fallback) with droppedRefreshCount + throttled log; counters
        // (+halfOpenProbes/cooldown) exposed for tests. Invalidate resets the
        // breaker (recovery path) alongside the cache; a timed half-open probe
        // also recovers silently-recovered upstreams without any push signal.
        private val policyRefreshGate = PolicyRefreshGate(
            executor = policyRefreshExecutor,
            log = { msg ->
                try {
                    android.util.Log.i(TAG, msg)
                } catch (_: Throwable) {
                }
            },
        )

        // Dedicated capability-probe executor+gate (Critical nesting fix):
        // independent Semaphore(1) + independent single-thread executor, never
        // shared with the policy refresh path. The probe (getFrameworkProperties)
        // is sequenced OUTSIDE the outer policy gate (see effectivePolicy), so
        // even the thread-nesting is gone; sharing would self-contend on the
        // non-reentrant guard and pin the first true forever (seed-only lock-in).
        // Intentionally never shut down (process-lifetime, like the policy executor).
        private val capabilityRefreshExecutor: java.util.concurrent.ThreadPoolExecutor =
            PolicyRefreshBudget.newPolicyRefreshExecutor("GalaxyDual-capability-probe")

        private val capabilityGate = PolicyRefreshGate(
            executor = capabilityRefreshExecutor,
            log = { msg ->
                try {
                    android.util.Log.i(TAG, msg)
                } catch (_: Throwable) {
                }
            },
        )

        // Remote-capability latch (P2-1): only true is latched forever; false
        // is cooldown-gated and re-probed (see RemoteCapabilityLatch). The
        // last-observed false is cached here for the cooldown fast path.
        private val capabilityLatch = RemoteCapabilityLatch()
        @Volatile
        private var remoteCapableFalseCached: Boolean? = null

        // Phase7 observability (optional): end-to-end deadline exhaustions
        // (remaining <= 0 skip before capability or prefs segment). Never
        // drives logic; counts fail-closed skips only. Monotonic, never
        // reset (process-lifetime; tests read only).
        internal val deadlineExhaustedCount = java.util.concurrent.atomic.AtomicLong(0L)

        // Rate-limits the capability-probe failure log: a persistently throwing
        // probe would otherwise spam the hot path on every query.
        @Volatile
        private var remoteCapabilityProbeFailureLogged = false

        // Listener registration is per SharedPreferences instance; the
        // framework caches the instance per group, so this is stable. Assigned
        // only after a successful registration (see registerPolicyChangeListener).
        // Phase 4: guarded by listenerLock (synchronized + compareAndSet);
        // re-registration unregisters the old instance best-effort.
        // P1-1: same-process hygiene only -- invalidating here never proves a
        // cross-process write arrived (API 102.0.0 has no push).
        private val listenerLock = Any()
        @Volatile
        private var registeredPrefs: SharedPreferences? = null
        private val remotePolicyListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                capabilityLatch.onInvalidate()
                remoteCapableFalseCached = null
                remoteCapabilityProbeFailureLogged = false
                effectiveCache.invalidate()
                try {
                    policyRefreshGate.invalidate()
                } catch (_: Throwable) {
                }
                try {
                    capabilityGate.invalidate()
                } catch (_: Throwable) {
                }
            }

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
        // file marker is the single hook-active signal (see HOOK.md §1.5).
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
        // EffectivePolicyCache (seed + remote snapshot); the app writes the
        // draft to the same remote-prefs group, so the effective set is seed +
        // draft. Union-only: entries are never removed.
        @JvmField
        val EXTRA_WHITELIST_PACKAGES: Set<String> = PolicySource.STATIC_SEED
    }
}
