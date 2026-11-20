// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable
import java.lang.reflect.Modifier

// BBC (Samsung proprietary shared library, com.samsung.bbc) injector for the
// Dual Messenger "available apps" list. Device-verified on One UI 5
// (Tab S7 / SM-T870, DAAgent 3.3.00.0) and One UI 9 (S23 Ultra / SM-S9180,
// One UI 9 `90000`, DAAgent 3.7.00.0) on 2026-11-17 — see
// docs/TAB7_BBC_INJECTION_PLAN.md addendum A.
//
// The eligibility list is produced inside DualAppActivity.getAvailableApps()
// -> com.samsung.android.da.daagent.fwwrapper.PmWrapper.getPossibleDualAppPackages
// (installed ∩ whitelist, enabled check) — a BBC shared-library class invoked
// from the DAAgent process. Hooking THAT callee is the whole point:
//
// - UNION-only: policy packages are APPENDED after Samsung's own order, never
//   removed, never reordered.
// - Fail-closed: any mismatch (class missing, unexpected return type, BBC
//   absent, merge error) -> return the ORIGINAL list unchanged.
// - Never touches mutation paths, never writes fields, never changes Samsung
//   semantics beyond append-at-end of a read-only list.
// - BBC absence => zero hooks installed and zero behavior change (observational).
//
// The policy source is SINGLE: the same dual_policy/extra_whitelist_packages
// remote-prefs group the whitelist query path reads, served through
// EffectivePolicyCache (TTL 60s / failureCooldown 5s / single-flight / 50ms
// budget) under PolicySource.BBC_AVAILABLE_APPS_INJECTION. The Stage-1 sealed
// gate (NON_EMPTY_POLICY_EFFECTIVE_GATE — whitelist query / system_server /
// <clinit> paths) is untouched and stays closed.
//
// Framework-free constants + pure decision logic so JVM unit tests can lock
// the contract. Framework operations are injected by the call site
// (DualHook.installBbcHooks); install logs BBC-SPIKE / BBC-HOOK evidence.
internal object BbcInjector {

    // BBC anchor (PUBLIC STATIC; verified signature on Tab S7, offset 006310).
    internal const val BBC_PM_WRAPPER_CLASS = "com.samsung.android.da.daagent.fwwrapper.PmWrapper"
    internal const val BBC_PM_GET_POSSIBLE_METHOD = "getPossibleDualAppPackages"

    // Caller chain of the eligibility list (daagent, DualAppActivity).
    internal const val DUAL_APP_ACTIVITY_CLASS = "com.samsung.android.da.daagent.activity.DualAppActivity"
    internal const val CALLER_GET_AVAILABLE = "getAvailableApps"
    internal const val CALLER_UPDATE_LIST = "updateDualAppList"

    internal const val BBC_HOOK_ID = "galaxydual:bbc:getPossibleDualAppPackages"

    // Union-only merge of a policy set into Samsung's available-apps list.
    // - `original` is returned BY IDENTITY when nothing was added (no new
    //   allocation unless a policy entry survived all guards).
    // - Policy entries already present in `original`, already seen, malformed
    //   (incl. over MAX_PACKAGE_LENGTH / over MAX_POLICY_ENTRIES added),
    //   not installed, or not enabled are dropped silently.
    // - Samsung's own list is copied verbatim — duplicates it contains are
    //   preserved; only the policy side is deduplicated.
    // - `enabled`/`installed` lookups are caller-injected so JVM tests can
    //   verify the pure decision without PackageManager.
    // - Never throws (call site wraps try/catch, but we keep it total anyway).
    //
    // Phase E 效能基準（JVM，見 BbcMergeTimingTest）：N=50/200/512 政策合併耗時皆為
    // 微秒級（線性 O(original+policy)，N=512 亦遠小於 50ms）；hook 熱路徑 50ms 預算
    // （PolicyRefreshBudget）治理的是 IPC＋等待，不是此純內存合併——PM 解析
    // （PmOutputParser）同樣不在 50ms 內，兩者預算獨立（見 PmOutputParser 檔頭聲明）。
    internal fun mergeAvailableList(
        original: List<String>,
        policy: Set<String>,
        installed: (String) -> Boolean,
        enabled: (String) -> Boolean,
    ): List<String> {
        // Non-String container ⇒ observe-only: return Samsung's object untouched.
        // (Callers guarantee all-String today — interceptBbcCall bails before
        // calling us on any non-String element — but an unchecked cast at a
        // future call site must not silently drop elements on merge.)
        if (original.any { it !is String }) return original
        if (policy.isEmpty()) return original
        // Membership set for the union semantics only; Samsung's own list is
        // copied verbatim (duplicates included) so the merged result mirrors
        // what BBC produced bit-for-bit before appending policy entries.
        val seen = HashSet<String>()
        for (pkg in original) {
            if (pkg is String) seen.add(pkg)
        }
        val added = ArrayList<String>()
        for (pkg in policy) {
            if (pkg in seen) continue
            if (pkg.length > PolicySource.MAX_PACKAGE_LENGTH) continue
            if (!PolicySource.isValidPackage(pkg)) continue
            val i = try {
                installed(pkg)
            } catch (_: Throwable) {
                false
            }
            if (!i) continue
            val e = try {
                enabled(pkg)
            } catch (_: Throwable) {
                false
            }
            if (!e) continue
            seen.add(pkg)
            added.add(pkg)
            if (added.size >= PolicySource.MAX_POLICY_ENTRIES) break
        }
        return if (added.isEmpty()) original else ArrayList(original).apply { addAll(added) }
    }

    // Probe whether the BBC shared library resolves on this ROM. Never throws.
    internal fun probeBbcAvailable(classLoader: ClassLoader, log: (String) -> Unit): Boolean {
        return try {
            val wrapper = classLoader.loadClass(BBC_PM_WRAPPER_CLASS)
            // Force resolution of the method too: BBC may be absent but package-present.
            val m = wrapper.getDeclaredMethod(BBC_PM_GET_POSSIBLE_METHOD, android.content.Context::class.java)
            // ROM-drift safety net (BBC_INJECTOR_DESIGN §3): same name+params but
            // changed shape must not be hooked silently.
            if (!Modifier.isPublic(m.modifiers) ||
                !Modifier.isStatic(m.modifiers) ||
                m.returnType != java.util.ArrayList::class.java
            ) {
                log("BBC-HOOK skip getPossibleDualAppPackages: signature mismatch (${HookInstaller.executableSignature(m)})")
                return false
            }
            log("BBC-SPIKE ok ${wrapper.name}#$BBC_PM_GET_POSSIBLE_METHOD")
            true
        } catch (t: Throwable) {
            log("BBC-SPIKE FAIL: BBC library not resolvable on this ROM (${t.message}); no BBC hooks installed")
            false
        }
    }

    // Resolve the caller Executables (best-effort; may be empty). Never throws.
    internal fun resolveCallers(classLoader: ClassLoader, log: (String) -> Unit): List<Executable?> {
        val activity = try {
            classLoader.loadClass(DUAL_APP_ACTIVITY_CLASS)
        } catch (t: Throwable) {
            log("BBC-SPIKE caller: $DUAL_APP_ACTIVITY_CLASS not resolvable (${t.message}); callee-only deopt")
            return emptyList()
        }
        val callers = ArrayList<Executable?>()
        for (name in listOf(CALLER_GET_AVAILABLE, CALLER_UPDATE_LIST)) {
            try {
                callers.add(activity.getDeclaredMethod(name))
            } catch (_: Throwable) {
                log("BBC-SPIKE caller: $DUAL_APP_ACTIVITY_CLASS#$name not found; skipped")
            }
        }
        return callers
    }
}
