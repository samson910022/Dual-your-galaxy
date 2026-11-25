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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// --- R07 (P1): torn {packages, version} prefs read guard (framework-free seam) ---
// Minimal view of the SharedPreferences calls the BBC versioned read needs.
// Production adapts the real SharedPreferences exactly once (in
// readRemotePrefsVersioned); JVM tests supply a fake that can swap values
// BETWEEN the two version reads to prove the seqlock double read fails closed.
internal interface BbcVersionedPrefsView {
    fun contains(key: String): Boolean
    fun getLong(key: String, defValue: Long): Long
    fun getStringSet(key: String, defValue: MutableSet<String>): MutableSet<String>?
}

// --- PR #74 G1: runtime BBC injection opt-out (framework-free seam) ---
// Minimal view of the ONE boolean the legacy bounded helper needs, so JVM
// tests can inject a fake (absent key / read throws) without an Android
// runtime. Phase B production reads this key from the SAME coherent getAll()
// copy as every other gate input (see BbcPreactivationHookReader); this seam
// and readBbcInjectionEnabledBounded below are retained for framework-free
// JVM pins of the hybrid rule's bounded-read contract.
internal interface BbcInjectionPrefsView {
    fun getBoolean(key: String, defValue: Boolean): Boolean
}

// Returns null on any read failure (key absent is handled by defValue=true in
// the adapter). null is fed to the hybrid rule in bbcInjectionGateOpen, where
// PR5 Phase A (#80) made it FAIL CLOSED: a failed read can never open the BBC
// effect; only a confirmed true can. Once OFF has been confirmed a failure
// also keeps it CLOSED for BBC_STICKY_MAX_AGE_MS (process-local sticky state).
// This legacy key is the opt-out, NOT the consent source — the pre-activation
// decision lives in BbcPreactivationGate.kt. This reader never throws.
internal fun readBbcInjectionEnabled(view: BbcInjectionPrefsView): Boolean? = try {
    view.getBoolean(PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED, true)
} catch (_: Throwable) {
    null
}

// --- D06 (PR#74 r3): bounded BBC gate pref read ---
// The raw adapter above is a synchronous SharedPreferences boolean lookup. On
// the hooked caller thread an IPC/Binder stall could pin the thread for the
// FULL duration of the stall (the later 50ms deadline only clamped the policy
// reader, not this gate read). Wrap it in a REAL wall-time ceiling via
// PolicyRefreshBudget.readBoundedValue (submit + future.get(timeout)), so a
// stalled gate read can never block the caller beyond timeoutMs.
//
// All failure modes map to null (failed read) and the helper NEVER throws:
//   - timeoutMs <= 0            -> null (no submit; spent/zero budget)
//   - timeout / reader throw    -> null
//   - submit rejection / gate contention -> null (Contention mapped here)
// Boolean? keeps false (confirmed OFF) distinct from null (failed read); the
// hybrid rule in bbcInjectionGateOpen consumes that distinction: a failed read
// -> CLOSED since PR5 Phase A (#80), and a confirmed OFF inside
// BBC_STICKY_MAX_AGE_MS also keeps the gate CLOSED (sticky).
// Framework-free and executor/reader injected so JVM tests exercise the real
// wall-time path (see BbcGateBoundedWaitTest).
internal fun readBbcInjectionEnabledBounded(
    executor: java.util.concurrent.ExecutorService,
    timeoutMs: Long,
    read: () -> Boolean?,
): Boolean? = try {
    PolicyRefreshBudget.readBoundedValue(
        reader = read,
        timeoutMs = timeoutMs,
        executor = executor,
    )
} catch (_: Throwable) {
    null
}

// --- PR5 Phase B1 (#80) / PR #90 Phase A (P0): bounded pre-activation read ---
// Same bounded-read mechanism as readBbcInjectionEnabledBounded: submit on a
// caller-injected zero-queue executor with a REAL wall-time ceiling, so an IPC
// stall can never pin the hooked caller. The worker is STRICTLY READ-ONLY
// (snapshot read + Phase A decision); there is no marker write on the hot path
// any more. null (timeout / task throw / submit contention / zero budget)
// means "no usable result" -> fail closed. Framework-free and executor-injected
// so JVM tests exercise the real wall-time path (see BbcPreactivationBoundedTest).
// Never throws.
internal fun <T> readBbcPreactivationBounded(
    executor: java.util.concurrent.ExecutorService,
    timeoutMs: Long,
    read: () -> T,
): T? = try {
    PolicyRefreshBudget.readBoundedValue(
        reader = read,
        timeoutMs = timeoutMs,
        executor = executor,
    )
} catch (_: Throwable) {
    null
}

// Applies the P0 decision to one READ-ONLY snapshot. There is deliberately no
// write callback: PR #90 removed the Hook-side migration marker writer entirely
// (libxposed API 102 remote prefs are read-only in hooked apps). The owner App
// persists the durable classification + marker atomically before any draft
// write; this function only reads the snapshot and returns the gate result.
internal fun bbcPreactivationDecisionOpen(
    compileTimeGate: Boolean,
    prefs: BbcPreactivationPrefs,
): Boolean = BbcPreactivationHookReader.effectOpen(compileTimeGate, prefs)

// Final BBC effect AND, ordered so the compile-time emergency switch is judged
// exactly ONCE:
//   compileTimeGate (false => ZERO pref reads / ZERO marker writes, preserving
//                    the historic zero-runtime-read pin)
//   AND preactivationOpen (opt-in / legacy migration / migrated marker — the
//                          Phase A decision on the bounded prefs snapshot)
//   AND hybridOpen (legacy bbc_injection_enabled opt-out with sticky/CAS/clock
//                   fail-closed; Phase A already made every failure CLOSED)
// The hybrid gate also accepts a compileTimeGate; the production call site
// passes `true` because this helper already owns the switch, so the same
// constant is NOT ANDed twice. Every stage is lazy and Throwable-safe.
internal inline fun bbcEffectGateOpen(
    compileTimeGate: Boolean,
    preactivationOpen: () -> Boolean,
    hybridOpen: () -> Boolean,
): Boolean {
    if (!compileTimeGate) return false
    val pre = try {
        preactivationOpen()
    } catch (_: Throwable) {
        false
    }
    if (!pre) return false
    return try {
        hybridOpen()
    } catch (_: Throwable) {
        false
    }
}

// Phase B (B3): production's FINAL BBC effect decision. Both stages are
// evaluated from ONE coherent snapshot (one getAll() copy, one fence) so a
// torn opt-in/runtime tuple can never be combined into an OPEN gate. This
// helper is pure with respect to preferences: no IPC, no read, no write. The
// hybrid rule still owns sticky/CAS/clock, but its runtime value is supplied
// by the caller from the SAME generation — bbcInjectionGateOpen's
// runtimeEnabled lambda must NOT touch remote prefs again.
//
// Fail-closed in all cases: kill switch false => zero pref reads; a null /
// inconsistent snapshot => CLOSED; opt-out wins; any stage throw => CLOSED.
internal fun bbcCoherentEffectDecision(
    compileTimeGate: Boolean,
    snapshot: BbcCoherentPrefsSnapshot?,
    hybridOpen: (runtimeEnabled: Boolean?) -> Boolean,
): Boolean {
    if (!compileTimeGate) return false
    if (snapshot == null || !snapshot.coherent) return false
    val pre = try {
        bbcPreactivationDecisionOpen(true, snapshot.preactivationPrefs())
    } catch (_: Throwable) {
        false
    }
    if (!pre) return false
    return try {
        hybridOpen(snapshot.runtimeEnabled)
    } catch (_: Throwable) {
        false
    }
}

// Bounded single-read wrapper used by production (and JVM tests that inject a
// counting executor). compileTimeGate is checked BEFORE the bounded read is
// requested: a disabled emergency switch performs ZERO executor submissions
// and ZERO pref reads. Exactly one readBbcPreactivationBounded call happens on
// the open path, so a single deadline = min(BBC_GATE_MAX_MS, remainingBudget())
// covers both stages; there is no second 15ms wait.
internal fun bbcCoherentEffectGateBounded(
    compileTimeGate: Boolean,
    readBoundedSnapshot: () -> BbcCoherentPrefsSnapshot?,
    hybridOpen: (runtimeEnabled: Boolean?) -> Boolean = { runtime ->
        bbcInjectionGateOpen(compileTimeGate = true) { runtime }
    },
): Boolean {
    if (!compileTimeGate) return false
    val snapshot = try {
        readBoundedSnapshot()
    } catch (_: Throwable) {
        null
    }
    return bbcCoherentEffectDecision(true, snapshot, hybridOpen)
}

// R5-01: BBC policy composition used by bbcPolicy() (and pinned verbatim by the
// JVM tests). The ENTRY clock sample is taken by the caller BEFORE this helper;
// getBbc() samples the clock AGAIN inside itself, and those are two different
// observations. Reporting a fake OPEN gate from the compile-time switch used to
// let a healthy cache-clock sample serve cached/sticky Good without ever
// reading the coherent consent/opt-out gate — an authorization bypass. So the
// entry decision is made HERE, not smuggled through the gate provider:
//   * entry clock untrusted + switch ON  -> explicit fail-closed path (drop
//     sticky, bump epoch, invalidate freshness, Unknown(emptySet()), ZERO
//     coherent reads / policy reads / merge);
//   * entry clock untrusted + switch OFF -> Cleared(-1) with ZERO cache mutation
//     (the historic gate-closed zero-touch invariant);
//   * entry clock healthy                -> getBbc() with the REAL coherent
//     gate provider, unchanged (one bounded getAll snapshot, one bounded read).
// No fake-open semantics exist anywhere on this path.
internal fun bbcPolicyWithEntryClock(
    cache: EffectivePolicyCache,
    clockOk: Boolean,
    compileTimeGate: Boolean,
    coherentGate: () -> Boolean,
    seed: Set<String> = DualHook.EXTRA_WHITELIST_PACKAGES,
    followerGraceMs: Long = EffectivePolicyCache.DEFAULT_FOLLOWER_GRACE_MS,
    deadlineMs: Long = -1L,
    reader: () -> Pair<Set<String>?, Long?>,
): EffectivePolicyCache.BbcPolicy {
    if (!clockOk) {
        if (!compileTimeGate) return EffectivePolicyCache.BbcPolicy.Cleared(-1L)
        return cache.failClosedEntryClockUntrusted()
    }
    return cache.getBbc(
        seed,
        bbcGateOpen = coherentGate,
        followerGraceMs = followerGraceMs,
        deadlineMs = deadlineMs,
        reader = reader,
    )
}

// N06(a): process-local confirmed-OFF timestamp (millis on the injected clock;
// null = no confirmed OFF in this process). compareAndSet in the gate makes the
// get -> decide -> write transition atomic/visibility-safe under concurrent BBC
// queries (no lock is held around the prefs read). It is deliberately NOT
// device-verified and NOT cross-process: a daemon restart clears it, so a
// stale OFF cannot outlive the process that observed it.
internal val bbcConfirmedOffAtMs = AtomicReference<Long?>(null)

// Pure, Android-free statement of the hybrid rule. `runtime` is the already
// observed boolean read: false = confirmed OFF, true = confirmed ON,
// null = read failure/absence. Returns the gate decision plus the state to
// store (null = no confirmed OFF). No I/O, no shared state — JVM tests pin the
// full decision table through this helper.
//
// PR5 Phase A (#80): the `null` arm is now FAIL CLOSED — only a confirmed true
// opens the gate, so a transient IPC failure can never be treated as consent.
// This is the second independent fail-closed layer (the first is the
// pre-activation opt-in decision in BbcPreactivationGate.kt). Confirmed-OFF
// sticky semantics are preserved: a null read inside the TTL keeps the
// existing OFF stamp; an absent/expired stamp is cleared instead.
internal data class BbcGateDecision(val open: Boolean, val confirmedOffAtMs: Long?)

internal fun bbcInjectionGateOpenWithSticky(
    compileTimeGate: Boolean,
    runtime: Boolean?,
    confirmedOffAtMs: Long?,
    nowMs: Long,
    ttlMs: Long = EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS,
): BbcGateDecision {
    // Compile-time gate stays authoritative and never mutates sticky state.
    if (!compileTimeGate) return BbcGateDecision(false, confirmedOffAtMs)
    val (open, nextOffAtMs) = when (runtime) {
        false -> false to nowMs // confirmed OFF: stamp it and keep the gate closed
        true -> true to null // confirmed ON: clear any prior OFF
        null -> {
            val off = confirmedOffAtMs
            if (off != null && nowMs >= off && nowMs - off < ttlMs) {
                false to off // sticky OFF within TTL (bounded, process-local)
            } else {
                false to null // first failure / expired TTL => FAIL CLOSED (#80)
            }
        }
    }
    return BbcGateDecision(open, nextOffAtMs)
}

// This is the HYBRID runtime rule only. The FINAL production BBC effect AND is
// bbcCoherentEffectGateBounded -> bbcCoherentEffectDecision: one coherent
// getAll() snapshot feeds BOTH the pre-activation gate and this hybrid rule,
// behind one bounded read (no second pref read, no marker write).
// The runtime read is LAZY so a compile-time-closed gate keeps
// its historic zero-pref-read / zero-IPC path. nowMs/ttlMs/stickyState are
// injectable for JVM tests; production uses the process-local AtomicReference
// above and wall-clock millis. Never throws into the hook caller: since PR5
// Phase A (#80) every failure degrades to CLOSED, not to the old shipped ON.
internal inline fun bbcInjectionGateOpen(
    compileTimeGate: Boolean,
    nowMs: () -> Long = { System.currentTimeMillis() },
    ttlMs: Long = EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS,
    stickyState: AtomicReference<Long?> = bbcConfirmedOffAtMs,
    // Kept last so the historic trailing-lambda call sites still bind here.
    runtimeEnabled: () -> Boolean?,
): Boolean {
    if (!compileTimeGate) return false // ZERO runtime read (existing pin)
    val runtime = try {
        runtimeEnabled()
    } catch (_: Throwable) {
        null
    }
    while (true) {
        val committedOpen: Boolean? = try {
            val cur = stickyState.get()
            val d = bbcInjectionGateOpenWithSticky(
                compileTimeGate = true,
                runtime = runtime,
                confirmedOffAtMs = cur,
                nowMs = nowMs(),
                ttlMs = ttlMs,
            )
            // Atomic CAS keeps the transition linearizable: if another query
            // changed the state after our get(), retry from the fresh value.
            // NO lock is held here (the prefs read above is lock-free).
            if (stickyState.compareAndSet(cur, d.confirmedOffAtMs)) d.open else null
        } catch (_: Throwable) {
            // Defensive last resort (#80): a clock/state failure must not
            // re-open the effect through the back door. Never throw into hook.
            return false
        }
        if (committedOpen != null) return committedOpen
    }
}

// Outcome of the self-consistent read. `failed` is true on a torn snapshot
// (version-before != version-after) or any read failure; the caller maps that
// to the Unknown signal (null packages, null version) with throttled logging.
internal data class BbcVersionedRead(
    val packages: Set<String>?,
    val version: Long?,
    val failed: Boolean,
    // F4 (optional diagnostic): the two seqlock reads, retained only to restore
    // mismatch detail in the torn-snapshot log. Defaults keep existing callers
    // (and tests) source-compatible; values are package/version numbers only.
    val versionBefore: Long? = null,
    val versionAfter: Long? = null,
)

// Seqlock-style snapshot: version-before -> defensive COPY of the package set
// (never trust a live cross-process collection) -> version-after. The pair is
// accepted ONLY when both probes agree AND neither is Invalid/Unavailable
// (both-Absent == confirmed legacy absence). Empty+version remains a Cleared
// candidate, empty+null stays Unknown, non-empty is Good (version may be null
// for the legacy no-version draft). Never throws.
internal fun readBbcVersionedPrefs(view: BbcVersionedPrefsView): BbcVersionedRead {
    // N03: adapt the view ONCE per probe to the shared framework-free model.
    // A read failure / corrupt value is now a distinct outcome instead of
    // collapsing to "absent" (which used to allow a legacy replay).
    fun probe(): VersionProbeResult = probeVersion(object : VersionPrefsView {
        override fun containsVersion(): Boolean =
            view.contains(PolicySource.POLICY_KEY_EXTRA_VERSION)

        override fun readVersion(defValue: Long): Long =
            view.getLong(PolicySource.POLICY_KEY_EXTRA_VERSION, defValue)
    })
    var before: VersionProbeResult = VersionProbeResult.Unavailable
    var after: VersionProbeResult = VersionProbeResult.Unavailable
    return try {
        before = probe()
        val live = view.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, LinkedHashSet())
        // Defensive snapshot: a concurrent commit may mutate the live set while
        // we iterate; a CTE/CME here fails closed via the outer catch.
        val pkgs: Set<String> = if (live == null) emptySet() else LinkedHashSet(live)
        after = probe()
        val failed = before is VersionProbeResult.Unavailable ||
            before is VersionProbeResult.Invalid ||
            after is VersionProbeResult.Unavailable ||
            after is VersionProbeResult.Invalid ||
            before != after
        if (failed) {
            BbcVersionedRead(
                null, null, failed = true,
                versionBefore = (before as? VersionProbeResult.Present)?.value,
                versionAfter = (after as? VersionProbeResult.Present)?.value,
            )
        } else {
            // Both probes equal and neither failed => both Absent (version=null,
            // confirmed legacy absence) or both Present(v).
            BbcVersionedRead(pkgs, (before as? VersionProbeResult.Present)?.value, failed = false)
        }
    } catch (_: Throwable) {
        BbcVersionedRead(null, null, failed = true)
    }
}

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
            // scope "daagent". This process writes ONLY its per-scope file
            // (`.active.daagent`); the app unions all six marker paths, so
            // this scope is honored regardless of the peer's write.
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
                        if (hookWithDeoptimize(method, HOOK_ID_PREFIX + HookLog.normalizeLogPart(method.name).take(200))) {
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
                                "diagnose-only sweep ${shortClass(target)}#${HookLog.normalizeLogPart(method.name).take(200)}: " +
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
                            "diagnose-only sweep ${shortClass(target)}#${HookLog.normalizeLogPart(method.name).take(200)}: " +
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
                        if (hookWithDeoptimize(method, HOOK_ID_PREFIX + HookLog.normalizeLogPart(method.name).take(200))) {
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
            // regardless of policy. Sequenced BEFORE the effectivePolicy()
            // resolution below on purpose: composite (Stage-1-excluded)
            // queries return the original without ever spending the 50ms
            // policy-refresh budget. Side effect of this reorder: composite
            // queries no longer drive the policy-refresh TTL, so poll-only
            // freshness now relies on non-composite queries — a
            // composite-dominated ROM stays seed-only until a non-composite
            // query or an invalidate() occurs.
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
            // Resolved AFTER the composite backstop (see above) and BEFORE
            // the empty check: Stage-1-excluded composite queries skip the
            // 50ms budget entirely; everyone else pays it exactly here.
            val extra = effectivePolicy()
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
        // 3.3 (sealed-gate short-circuit): with the code gate closed the
        // cache returns emptySet() before any lock/IPC, discarding everything
        // below — skip sampling the clock at all.
        if (!PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE) return emptySet()
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
        // The reader body itself lives in policyRead() (shared with the BBC
        // injector path); this function keeps the cache-call shape.
        val t0Sample = DeadlineBudget.sampleClock()
        val t0 = t0Sample.nanos
        val clockOk = t0Sample.clockOk
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
            // Same monotonic basis as the cache's deadline clamp, sampled a
            // few instructions AFTER t0 (a one-instant relationship, not an
            // epoch): nanoTime milliseconds anchor the absolute deadline
            // instead of t0 / 1e6, which can go ≤ 0 on a negative-nanotime
            // origin and silently drop the clamp (deadlineMs > 0L gate).
            // nowNanos() is the same clock read t0 came from, so the deadline
            // is t0 + budget in practice (microseconds late). On supported
            // runtimes (Linux/ART report CLOCK_MONOTONIC ≥ 0) this deadline
            // is non-positive only when the nanoTime origin sits at or below
            // −DEFAULT_STALL_BUDGET_MS of monotonic time — no supported
            // clock reports such an origin, so while clockOk the value is a
            // positive absolute deadline in practice. The −1L sentinel is
            // reserved for the clockOk=false / exception cases above.
            // A broken clock reports NO_START_NANOS; do not fabricate a
            // small positive "deadline" from that sentinel.
            val n = DeadlineBudget.nowNanos()
            if (n == DeadlineBudget.NO_START_NANOS) -1L
            else (n / 1_000_000L) + PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS
        } catch (_: Throwable) {
            -1L
        }
        val policy = effectiveCache.get(
            EXTRA_WHITELIST_PACKAGES,
            nonEmptyPolicyGateOpen = PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE,
            followerGraceMs = graceAtEntry,
            deadlineMs = deadlineMs,
        ) {
            policyRead()
        }
        // H4: one throttled best-effort line when policy truncation advanced
        // (unsealed path only — the gate-closed arm returned above).
        maybeLogPolicyTruncationDrops()
        return policy
    }

    // BBC 路徑專屬：以獨立 EffectivePolicyCache(bbcCache, sticky)讀取政策，
    // reader body 與白名單共用 policyRead()（單一實作：capability probe +
    // 單一 50ms end-to-end deadline budget + readRemotePrefs()）。
    // 最終 BBC 效應 = compile-time 緊急開關 BBC_AVAILABLE_APPS_INJECTION ×
    // pre-activation opt-in／durable classification（legacy 遷移標記；
    // BbcPreactivationGate.kt；remote prefs I/O 已於 PR5 Phase B1 接線）×
    // 執行期 dual_policy opt-out × hybrid fail-closed（偏好讀取首次失敗即
    // fail-closed；已確認 OFF 後 30 分鐘內的失敗亦維持關閉）。compile-time
    // 常數不再是「預設開」的依據；opt-out 鍵缺席只表示「沒有明確 opt-out」，
    // 不是預設開的依據。Stage-1 封緘不受影響；白名單 effectiveCache 維持
    // fail-closed，零影響；gate-closed 不碰 reader。
    // Phase B（B1/B3）coherent 唯讀快照：opt-in、legacy marker／durable
    // classification、執行期 opt-out 等所有輸入皆來自同一次 getAll() 複本
    // （單一 generation）；最終 gate 只做一次 bounded 讀取、只有一個
    // min(BBC_GATE_MAX_MS, remainingBudget()) deadline，不再有兩階段各自 15ms
    // 取樣。compile-time 常數關閉時完全不碰 pref／executor（zero reads /
    // zero submissions，沿用 historic zero-runtime-read pin）。getRemotePreferences
    // 失敗、IPC／getAll 停頓、錯型別或 generation 不一致一律 fail-closed；
    // 此路徑全程唯讀（無 edit()/put*/commit()），永不拋出到 system_server／
    // daagent，也不會釘住呼叫執行緒。
    // Phase A P0 sticky last-good（僅限非 clock 失敗）：null／拋／contention／
    // deadline 失敗回上次成功非空快照；只有成功讀到空（帶版本證明 Cleared）
    // 方可收縮；TTL 過期／invalidate 不丟 BBC last-good（僅 sticky cap 到期才
    // 過期，計數＋節流日誌）。**Clock 失效／倒退取樣不同**：新鮮度無法證明，
    // 一律 fail-closed 丟棄 sticky 並回 Unknown(emptySet())，上限不放寬。Clock
    // distrust 同時是 fail-closed 的 linearization point：作廢 cached freshness、
    // bump clock epoch 以 fence 既有 in-flight read；single-flight 用 fresh
    // flightSeq TOKEN（非 generation 序號）由 owner token-matched 釋放；所有
    // cached/sticky 回傳都經 trusted-clock 的 fresh TTL sample 重新驗證。
    // version evidence（high-water／cleared marker／
    // lastAcceptedBbcCanonical+Version）獨立於可回傳快照保留，invalidate／
    // clock distrust 都不清空，合法同版本 recovery 仍可通過，不開 replay window。
    // TTL 60s／failureCooldown 5s／single-flight／50ms budget 語義與白名單
    // 一致；grace/deadline 取樣比照 effectivePolicy：僅 trusted clock 取樣
    // followerGraceMs／deadlineMs，clock 失效或例外時 grace=0、deadline=-1
    // （deadline 以同一單調時鐘的 nowNanos() 毫秒作絕對基準，非 t0/1e6，
    // 避免負 nanotime origin 把 clamp 丟掉），不漂移。
    //
    // Phase B (B1/B3): ONE strictly read-only coherent snapshot for the final
    // BBC effect gate. The PUBLIC adapter calls getAll() exactly once and
    // copies the map; opt-in, durable classification, migration marker AND the
    // hybrid runtime opt-out therefore all come from the same generation.
    // getRemotePreferences / getAll / a wrong type / a torn fence => the whole
    // fail-closed snapshot. No edit()/put*/commit() anywhere on this path.
    // Never throws; bbcInjectionGateOpen's sticky/CAS/clock rule consumes the
    // snapshot's runtime value with NO second pref read.
    private fun bbcCoherentPrefsNow(): BbcCoherentPrefsSnapshot = try {
        BbcPreactivationHookReader.readCoherent(
            getRemotePreferences(PolicySource.POLICY_PREFS_NAME),
        )
    } catch (_: Throwable) {
        BbcCoherentPrefsSnapshot.failClosed()
    }

    private fun bbcPolicy(): EffectivePolicyCache.BbcPolicy = try {
        val t0Sample = DeadlineBudget.sampleClock()
        val clockOk = t0Sample.clockOk
        val t0 = t0Sample.nanos
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
            val n = DeadlineBudget.nowNanos()
            if (n == DeadlineBudget.NO_START_NANOS) -1L else (n / 1_000_000L) + PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS
        } catch (_: Throwable) {
            -1L
        }
        fun remainingBudget(): Long = if (!clockOk) {
            0L
        } else try {
            DeadlineBudget.remainingMs(t0, DeadlineBudget.nowNanos())
        } catch (_: Throwable) {
            0L
        }
        // R5-01: the ENTRY clock decision is made by bbcPolicyWithEntryClock(),
        // never by faking the coherent gate OPEN from the compile-time switch.
        // When clockOk is false its two fail-closed arms run with ZERO pref /
        // reader / merge work; only the healthy-clock arm below ever evaluates
        // the coherent provider. The provider is a real coherent consent/opt-out
        // read (no fake-open wrapper): N06(b) lazy evaluation inside getBbc
        // charges a slow pref read to the same bounded 50ms deadline budget.
        //
        // Phase B (B3) FINAL BBC EFFECT AND — ONE coherent read, ONE deadline:
        // bbcCoherentEffectGateBounded checks the compile-time emergency
        // switch FIRST (false => ZERO executor submissions / ZERO pref reads,
        // keeping the historic zero-runtime-read pin), then performs exactly
        // ONE readBbcPreactivationBounded task on the dedicated zero-queue
        // executor with min(BBC_GATE_MAX_MS, remaining-of-50ms). That task
        // reads a SINGLE getAll() copy and returns the coherent snapshot;
        // the pure decision helper evaluates BOTH stages from it:
        //   1. preactivationOpen = bbcPreactivationGate's READ-ONLY decision
        //      (optin / durable classification / trusted migration marker);
        //   2. hybridOpen        = bbcInjectionGateOpen's runtime rule on the
        //      SAME copy's bbc_injection_enabled value (sticky/CAS/clock
        //      preserved; runtimeEnabled lambda performs NO second read).
        // The compile-time constant is NOT ANDed twice (hybrid gets true).
        // A Binder stall, wrong type or torn generation degrades to CLOSED;
        // ZERO writes on every path (libxposed API 102 read-only prefs).
        bbcPolicyWithEntryClock(
            cache = bbcCache,
            clockOk = clockOk,
            compileTimeGate = PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            coherentGate = {
                bbcCoherentEffectGateBounded(
                    compileTimeGate = PolicySource.BBC_AVAILABLE_APPS_INJECTION,
                    readBoundedSnapshot = {
                        readBbcPreactivationBounded(
                            bbcPreactivationExecutor,
                            minOf(BBC_GATE_MAX_MS, remainingBudget()),
                        ) { bbcCoherentPrefsNow() }
                    },
                )
            },
            followerGraceMs = graceAtEntry,
            deadlineMs = deadlineMs,
            reader = { policyReadBbcVersioned() },
        )
    } catch (_: Throwable) {
        EffectivePolicyCache.BbcPolicy.Unknown(emptySet())
    }

    // Policy read shared by effectivePolicy() (whitelist query path, reached
    // only with the Stage-1 gate open) and bbcPolicy() (BBC injector path):
    // ONE implementation — capability probe + single 50ms end-to-end deadline
    // budget + readRemotePrefs(). The t0/clockOk/remainingMs arithmetic is
    // identical to what effectivePolicy() sampled inline before the
    // extraction, but sampled at this reader's entry (microseconds after the
    // caller's t0 on the leader path; followers never run the reader).
    // deadlineExhaustedCount is incremented on the fail-closed skip arms.
    private fun policyRead(): Set<String>? {
        // t0 sampled at entry with true System.nanoTime (never mocked);
        // arithmetic via pure DeadlineBudget (same function JVM tests use).
        // H8 (sentinel discipline): sample via sampleClock() so a broken
        // nanoTime is explicit (clockOk=false), never a fresh full budget.
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
        var rem = remainingMs()
        if (rem <= 0) {
            try {
                deadlineExhaustedCount.incrementAndGet()
            } catch (_: Throwable) {
            }
            return null
        }
        val capTimeout = minOf(PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS, rem)
        val capable = isRemoteCapable(capTimeout) ?: return null
        if (!capable) return emptySet()
        rem = remainingMs()
        if (rem <= 0) {
            try {
                deadlineExhaustedCount.incrementAndGet()
            } catch (_: Throwable) {
            }
            return null
        }
        return policyRefreshGate.readBoundedOrThrow(
            reader = { readRemotePrefs() },
            timeoutMs = rem,
        )
    }

    // H4: PolicySource.truncationDrops is a process-lifetime monotonic
    // counter; this only paces the log (at most one line per
    // TRUNCATION_LOG_THROTTLE_MS, and only when the total advanced since the
    // last emit). Mirrors EffectivePolicyCache.noteSeedOnlyFallback's
    // throttle shape: nanoTime millis, Long.MIN_VALUE sentinel, CAS-claimed,
    // never throws — a broken clock skips the log instead of bypassing the
    // throttle.
    private fun maybeLogPolicyTruncationDrops() {
        val total = try {
            PolicySource.truncationDrops.get()
        } catch (_: Throwable) {
            return
        }
        if (total <= 0L) return
        val lastEmitted = truncationDropsAtLastLog.get()
        if (total <= lastEmitted) return
        val now = try {
            System.nanoTime() / 1_000_000L
        } catch (_: Throwable) {
            return
        }
        val last = lastTruncationLogMs.get()
        if (last != Long.MIN_VALUE && now - last < TRUNCATION_LOG_THROTTLE_MS) return
        if (!lastTruncationLogMs.compareAndSet(last, now)) return
        if (!truncationDropsAtLastLog.compareAndSet(lastEmitted, total)) return
        try {
            logi(
                "policy truncation dropped extras beyond the " +
                    "${PolicySource.MAX_POLICY_ENTRIES}-entry cap (merge keeps seed-priority order; total=$total)",
            )
        } catch (_: Throwable) {
            // Diagnostics only.
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

    // Phase A P0 BBC versioned prefs read: returns Pair(packages, version)
    // where packages==null signals a failed read (framework absent / IPC
    // failure, throttled log via bbcCache.shouldLogFailure) and version==null
    // signals "no version proof" (key absent or unreadable). Never throws.
    // Empty + version!=null is the ONLY intentional-clear signal the BBC
    // cache may shrink on; empty + version==null is Unknown (sticky).
    private fun readRemotePrefsVersioned(): Pair<Set<String>?, Long?> {
        return try {
            val prefs = getRemotePreferences(PolicySource.POLICY_PREFS_NAME)
            if (prefs == null) return Pair(null, null)
            registerPolicyChangeListener(prefs)
            // R07 (P1): the writer commits packages+version in one atomic commit,
            // but a reader-side snapshot must be self-consistent. Adapt the real
            // SharedPreferences to the framework-free view ONCE here; the seam
            // does version-before / defensive set copy / version-after and fails
            // closed on any mismatch or read failure.
            val read = readBbcVersionedPrefs(object : BbcVersionedPrefsView {
                override fun contains(key: String): Boolean = prefs.contains(key)
                override fun getLong(key: String, defValue: Long): Long = prefs.getLong(key, defValue)
                override fun getStringSet(key: String, defValue: MutableSet<String>): MutableSet<String>? =
                    prefs.getStringSet(key, defValue)
            })
            if (read.failed && bbcCache.shouldLogFailure()) {
                try {
                    // F4: restore the before/after mismatch detail (numbers
                    // only, no package strings); throttling is unchanged —
                    // still gated by shouldLogFailure() above.
                    logi(
                        "BBC remote policy read failed: torn/inconsistent snapshot " +
                            "versionBefore=${read.versionBefore} versionAfter=${read.versionAfter}",
                    )
                } catch (_: Throwable) {
                }
            }
            Pair(read.packages, read.version)
        } catch (t: Throwable) {
            if (bbcCache.shouldLogFailure()) {
                try {
                    logi("BBC remote policy read failed: ${shortEx(t)}: ${shortMsg(t)}")
                } catch (_: Throwable) {
                }
            }
            Pair(null, null)
        }
    }

    // Phase A P0 BBC versioned policy read (shared 50ms end-to-end budget):
    // capability probe + single 50ms total + versioned prefs read. Maps
    // capability-false to Unknown (null,null) — a missing capability is a
    // failure for BBC (keep last-good), never an intentional clear (whitelist
    // maps it to emptySet/cached; BBC must not shrink on it). Deadline
    // exhaustion maps to Unknown as well (failed read => 5s cooldown +
    // sticky). Contention propagates as PolicyRefreshContentionException so
    // the BBC cache serves sticky WITHOUT renewing its cooldown (herd fix).
    private fun policyReadBbcVersioned(): Pair<Set<String>?, Long?> {
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
        var rem = remainingMs()
        if (rem <= 0) {
            try {
                deadlineExhaustedCount.incrementAndGet()
            } catch (_: Throwable) {
            }
            return Pair(null, null)
        }
        val capTimeout = minOf(PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS, rem)
        val capable = isRemoteCapable(capTimeout) ?: return Pair(null, null)
        if (!capable) return Pair(null, null)
        rem = remainingMs()
        if (rem <= 0) {
            try {
                deadlineExhaustedCount.incrementAndGet()
            } catch (_: Throwable) {
            }
            return Pair(null, null)
        }
        // Gate-bounded versioned read: the gate only supports Set<String>?,
        // so the version travels via an AtomicReference set inside the
        // worker lambda (happens-before via Future.get).
        val versionHolder = AtomicReference<Long?>(null)
        val versionPresent = AtomicBoolean(false)
        val pkgs: Set<String>? = policyRefreshGate.readBoundedOrThrow(
            reader = {
                val (p, v) = readRemotePrefsVersioned()
                if (v != null) {
                    versionHolder.set(v)
                    versionPresent.set(true)
                }
                p
            },
            timeoutMs = rem,
        )
        if (pkgs == null) return Pair(null, null)
        val ver = if (versionPresent.get()) versionHolder.get() else null
        return Pair(pkgs, ver)
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

    // --- BBC (com.samsung.bbc) injector: Dual Messenger available-apps list ---
    // UNION-only, fail-closed. With an empty BBC injection policy (the common
    // case: user has not ticked anything in the policy editor) bbcPolicy()
    // returns emptySet() and the hook is a no-op returning Samsung's original
    // result object, identical to not hooking at all. Any unexpected ROM shape,
    // null, exception, or non-String element also returns the ORIGINAL result
    // object. Device-verified evidence lives in docs/TAB7_BBC_INJECTION_PLAN.md.

    // Per-process one-line rate limits for the BBC entry/merge evidence logs.
    private val bbcHitLog = java.util.Collections.synchronizedSet(java.util.HashSet<String>())
    private val bbcMergedLog = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    // Caller-identification entry ledger (once per process, pid-keyed like
    // bbcMergedLog): the BBC-ENTRY read-only diagnosis fires at most once per
    // process so the open-page hot path never spams logcat.
    private val bbcEntryLog = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    // R10 (P2): the caller stack snippet is captured LAZILY through this seam —
    // only after the once-per-pid ledger claim succeeds. Same ledger + log sink
    // as before; format/desensitization unchanged.
    private val bbcEntryLogger = BbcInjector.BbcEntryLogger(bbcEntryLog, ::logi)

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
    // then — only with a non-empty BBC injection policy — UNION-append the
    // policy packages that this device reports installed+enabled to the END of
    // Samsung's own list (order of the original list preserved, entries never
    // removed/reordered). Fail-closed at every step: policy lookup error,
    // unexpected result type, or any non-String element => the ORIGINAL
    // result object is returned unchanged.
    private fun interceptBbcCall(chain: XposedInterface.Chain): Any? {
        // Read-only entry timestamp FIRST (cheap, no semantics). R10 (P2): the
        // caller STACK capture is deferred behind a lazy supplier — it is only
        // materialized by the entry logger AFTER the once-per-pid ledger claim
        // succeeds, so fast-path exits and repeated calls never pay for it.
        val entryTsMs: Long = try {
            System.currentTimeMillis()
        } catch (_: Throwable) {
            0L
        }
        fun entryCaller(): String = try {
            BbcInjector.callerSnippet(Thread.currentThread().stackTrace)
        } catch (_: Throwable) {
            ""
        }
        val result = chain.proceed()
        val pid = try {
            android.os.Process.myPid()
        } catch (_: Throwable) {
            -1
        }
        // Fail-fast on unexpected ROM shapes before paying for the policy
        // lookup (type-check is cheaper than a binder read).
        if (result !is List<*>) {
            // Read-only entry diagnosis for the bad-type shape (size unknown).
            logBbcEntryOnce(pid, entryTsMs, ::entryCaller, -1, false)
            if (logBbcBadTypeOnce(pid)) {
                logi("BBC-HOOK unexpected result type; returning original (pid=$pid)")
            }
            return result
        }
        val bbcResult = try {
            bbcPolicy()
        } catch (_: Throwable) {
            return result
        }
        // Phase A P0 explicit-empty token: Good(non-empty) merges; Cleared
        // (empty+version) is an intentional clear (no merge, no Unknown log);
        // Unknown (null/version-absent/contention/deadline) keeps last-good
        // with a once-per-process keep-last-good log. An untrusted/dead clock
        // is the exception: its Unknown carries an EMPTY last-good (sticky
        // discarded fail-closed), so no policy is merged.
        val extra: Set<String> = when (bbcResult) {
            is EffectivePolicyCache.BbcPolicy.Cleared -> {
                // Read-only entry: intentional-clear path (empty policy).
                val entrySize = try {
                    result.size
                } catch (_: Throwable) {
                    -1
                }
                logBbcEntryOnce(pid, entryTsMs, ::entryCaller, entrySize, false)
                logBbcHitOnce("BBC-HOOK callback getPossibleDualAppPackages (pid=$pid)")
                return result
            }
            is EffectivePolicyCache.BbcPolicy.Good -> bbcResult.packages
            is EffectivePolicyCache.BbcPolicy.Unknown -> {
                if (logBbcUnknownOnce(pid)) {
                    val kept = try {
                        bbcResult.lastGood.size
                    } catch (_: Throwable) {
                        -1
                    }
                    logi("BBC-HOOK unknown-policy keep-last-good (kept=$kept, pid=$pid)")
                }
                if (bbcResult.lastGood.isEmpty()) {
                    // Read-only entry: unknown-policy with nothing kept.
                    val entrySize = try {
                        result.size
                    } catch (_: Throwable) {
                        -1
                    }
                    logBbcEntryOnce(pid, entryTsMs, ::entryCaller, entrySize, false)
                    logBbcHitOnce("BBC-HOOK callback getPossibleDualAppPackages (pid=$pid)")
                    return result
                }
                bbcResult.lastGood
            }
        }
        // Good is non-empty by contract (empty-with-proof is Cleared, empty
        // without proof is Unknown-empty and returned above), so no empty
        // guard is needed here: BbcInjector returns the original for empty.
        // Read-only entry diagnosis (once per process, never branches merge):
        // result size + whether Samsung's list already contains a policy
        // entry (boolean only, no package literals). Any failure skips the
        // line; merge/gate/write/return semantics below are untouched.
        val entrySize = try {
            result.size
        } catch (_: Throwable) {
            -1
        }
        val entryContains = try {
            BbcInjector.originalContainsPolicy(result, extra)
        } catch (_: Throwable) {
            false
        }
        logBbcEntryOnce(pid, entryTsMs, ::entryCaller, entrySize, entryContains)
        logBbcHitOnce("BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=$pid)")
        return try {
            // result is already known to be a List<*> (checked above).
            @Suppress("UNCHECKED_CAST")
            val raw = result as List<*>
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
            if (logBbcMergeFailOnce(pid)) {
                logi("BBC-HOOK merge failed (${t.javaClass.name}); returning original (pid=$pid)")
            }
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

    // One line per process for the empty-policy entry evidence. Fully total:
    // a broken log store drops the line instead of breaking the hook path.
    private fun logBbcHitOnce(msg: String) {
        val added = try {
            bbcHitLog.add(msg)
        } catch (_: Throwable) {
            false
        }
        if (added) {
            logi(msg)
        }
    }

    // Caller-identification entry diagnosis (once per process, pid-keyed).
    // Read-only: formats one BBC-ENTRY line (ts/pid/size/containsPolicy
    // boolean/caller snippet) and never affects merge/gate/write/return
    // semantics. R10 (P2): the caller stack snippet is now supplied LAZILY and
    // captured only after the once-per-pid ledger claim succeeds (delegated to
    // BbcEntryLogger). An empty caller snippet or any failure skips the line.
    private fun logBbcEntryOnce(
        pid: Int,
        tsMs: Long,
        callerSupplier: () -> String,
        resultSize: Int,
        containsPolicy: Boolean,
    ) {
        bbcEntryLogger.logOnce(pid, tsMs, resultSize, containsPolicy, callerSupplier)
    }

    // First merge per process logs; subsequent merges stay silent.
    private fun logBbcMergedOnce(pid: Int): Boolean = try {
        bbcMergedLog.add("pid:$pid")
    } catch (_: Throwable) {
        false
    }

    private fun logBbcNoMergeOnce(pid: Int): Boolean = try {
        bbcMergedLog.add("nomerge:$pid")
    } catch (_: Throwable) {
        false
    }

    // Merge-path failure ledger (logged once per process, never swallowed).
    private fun logBbcMergeFailOnce(pid: Int): Boolean = try {
        bbcMergedLog.add("mergefail:$pid")
    } catch (_: Throwable) {
        false
    }

    // Bad-result-type ledger (logged once per process, never swallowed).
    private fun logBbcBadTypeOnce(pid: Int): Boolean = try {
        bbcMergedLog.add("badtype:$pid")
    } catch (_: Throwable) {
        false
    }

    // Phase A P0: Unknown-policy keep-last-good ledger (once per process).
    // Fires when bbcPolicy() is Unknown (null/version-absent/contention/
    // deadline/clock) and the interceptor keeps serving the sticky snapshot.
    private fun logBbcUnknownOnce(pid: Int): Boolean = try {
        bbcMergedLog.add("unknown:$pid")
    } catch (_: Throwable) {
        false
    }

    // Merges policy entries into the WhiteListApps static whitelists after
    // class init. Thin shell over WhitelistPatch.patchFields (unit-tested):
    // BOTH candidate fields are attempted (the ROM may read the _FOR_CHINA
    // field per region/sales code, so first-wins would silently miss region
    // devices); per-field outcomes are logged best-effort for diagnostics.
    // The original whitelist is never shrunk.
    //
    // Empty policy (STATIC_SEED is empty and the remote draft is not yet
    // persisted / not yet proven end-to-end): returns after the shape probe
    // (no policy IPC), so behavior stays observe-only and unaffected by the
    // final-field / dual-field handling below.
    private fun patchWhitelistField(target: Class<*>) {
        // Reflection FIRST (pre-IPC fast-out): on a ROM where every candidate
        // field is static final (or absent/wrong-shaped) the patch goes
        // ObserveOnly and the policy IPC through the cache/gate would be
        // discarded — skip it entirely. A non-empty policy still cannot make
        // a final field patchable, so no policy signal is lost by probing
        // before consulting it.
        if (!WhitelistPatch.hasPatchableFieldShape(target, WHITELIST_FIELD_CANDIDATES)) {
            logi(
                "skip patch on ${shortClass(target)}: no patchable field shape on device " +
                    "(${WhitelistPatch.OBSERVE_ONLY_MARKER})",
            )
            return
        }
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

        // D06 (PR#74 r3): real wall-time ceiling for the BBC opt-out gate pref
        // read. A small slice of the 50ms end-to-end stall budget so the policy
        // reader still has room; the call site uses
        // min(BBC_GATE_MAX_MS, remaining-of-50ms). TUNABLE: real device Binder
        // latency is UNMEASURED — this is a safety bound, NOT a device go/no-go
        // claim. The caller is pinned to this ceiling by BbcGateBoundedWaitTest
        // and BbcPreactivationBoundedTest (both bounded gate reads share it).
        // A 15ms timeout yields a failed read, which FAILS CLOSED since PR5
        // Phase A (#80): BBC does not inject on a timeout. This is a safety
        // bound, NOT a device SLA; real Binder latency and DAAgent UI/ANR are
        // UNMEASURED (docs/ACCEPTANCE N08).
        internal const val BBC_GATE_MAX_MS = 15L

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

        // Phase A P0 BBC independent cache (sticky last-good). Separate
        // instance from the whitelist effectiveCache so the whitelist path
        // (NON_EMPTY_POLICY_EFFECTIVE_GATE=false, fail-closed) sees zero
        // behavior change; BBC TTL/invalidate never discard last-good here
        // (only the sticky cap does). Same 60s TTL / 5s cooldown /
        // single-flight shape, independent counters + throttled BBC log.
        private val bbcCache = EffectivePolicyCache(
            onLog = { msg ->
                try {
                    Log.i(TAG, msg)
                } catch (_: Throwable) {
                    // Diagnostics only.
                }
            },
            stickyLastGood = true,
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

        // Phase B (B3): SINGLE dedicated zero-queue daemon executor for the
        // final BBC effect gate. Kept separate from policyRefreshExecutor and
        // capabilityRefreshExecutor so a stuck BBC gate reader cannot
        // self-contend with the policy reader it guards, nor starve it.
        // SynchronousQueue + AbortPolicy: a second concurrent submit is
        // REJECTED -> null -> fail closed, never queued behind a stuck worker,
        // so at most one worker can ever be resident. ONE task per gate call
        // reads the whole coherent snapshot and both stages decide from it;
        // there is no second executor / second 15ms wait any more. Never shut
        // down (process lifetime); a stuck uninterruptible worker leaves later
        // gate reads REJECTED -> CLOSED (with the old sticky-OFF TTL preserved
        // by bbcInjectionGateOpen). Recovery is process-restart-bound; this is
        // a reliability limitation, not a safety guarantee.
        private val bbcPreactivationExecutor: java.util.concurrent.ThreadPoolExecutor =
            PolicyRefreshBudget.newPolicyRefreshExecutor("GalaxyDual-bbc-preactivation-read")

        // Remote-capability latch (P2-1): only true is latched forever; false
        // is cooldown-gated and re-probed (see RemoteCapabilityLatch). The
        // last-observed false is cached here for the cooldown fast path.
        private val capabilityLatch = RemoteCapabilityLatch()
        @Volatile
        private var remoteCapableFalseCached: Boolean? = null

        // H4: throttle state for the policy-truncation log (see
        // maybeLogPolicyTruncationDrops). Process-lifetime, never reset; the
        // counter it observes (PolicySource.truncationDrops) is the source
        // of truth — this only paces the one log line.
        private val lastTruncationLogMs =
            java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        private val truncationDropsAtLastLog =
            java.util.concurrent.atomic.AtomicLong(0L)
        private const val TRUNCATION_LOG_THROTTLE_MS = 5_000L

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
                // Phase A P0: BBC cache has its own generation/single-flight;
                // invalidate it too so the next BBC query re-reads, but its
                // sticky last-good survives (unlike the whitelist seed-only).
                try {
                    bbcCache.invalidate()
                } catch (_: Throwable) {
                }
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
