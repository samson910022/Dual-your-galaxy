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

    // --- Caller-identification read-only diagnostics (2026-11-21, Plan §4) ---
    // Pure + total helpers for the interceptBbcCall entry log. Framework-free
    // so JVM tests lock them; the hook path only interpolates their output
    // into one throttled logi line and never branches on it (no merge /
    // gate / write / return-semantics change). Privacy: only sizes + one
    // boolean + Samsung/ROM class+method simple names enter the log — never
    // package-name literals, never PII. Line numbers are intentionally
    // omitted (class+method is enough to pin the caller; keeps the line
    // bounded and avoids leaking build-specific line tables).
    internal const val BBC_CALLER_MAX_FRAMES = 8
    internal const val BBC_CALLER_MAX_CHARS = 600
    internal const val BBC_ENTRY_MAX_CHARS = 1000

    // Short caller snippet from a stack trace: drops hook-infra frames, keeps
    // the first maxFrames caller frames as SimpleClass#method joined by
    // " <- " (innermost first). Never throws; null/empty/hostile => "" and
    // the call site skips the entry log.
    internal fun callerSnippet(
        trace: Array<StackTraceElement>?,
        maxFrames: Int = BBC_CALLER_MAX_FRAMES,
    ): String {
        return try {
            if (trace == null || trace.isEmpty()) return ""
            val cap = try {
                maxFrames.coerceAtLeast(0)
            } catch (_: Throwable) {
                0
            }
            if (cap <= 0) return ""
            val parts = ArrayList<String>(cap)
            for (el in trace) {
                if (parts.size >= cap) break
                val cls: String = try {
                    el.className
                } catch (_: Throwable) {
                    continue
                }
                // Skip our own + framework-dispatch frames so the first kept
                // frame is the Samsung/ROM caller, not the hook itself.
                if (cls == "java.lang.Thread" ||
                    cls.startsWith("com.galaxydual.hook.") ||
                    cls.startsWith("io.github.libxposed.")
                ) {
                    continue
                }
                val method: String = try {
                    el.methodName ?: ""
                } catch (_: Throwable) {
                    ""
                }
                if (method.isEmpty()) continue
                val shortCls = try {
                    HookLog.shortName(cls)
                } catch (_: Throwable) {
                    ""
                }
                if (shortCls.isEmpty()) continue
                val safeMethod = try {
                    HookLog.normalizeLogPart(method).take(100)
                } catch (_: Throwable) {
                    ""
                }
                if (safeMethod.isEmpty()) continue
                parts.add("$shortCls#$safeMethod")
            }
            try {
                parts.joinToString(" <- ").take(BBC_CALLER_MAX_CHARS)
            } catch (_: Throwable) {
                ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    // Read-only membership probe: does Samsung's original list already contain
    // any policy entry? Never throws; hostile collections fail closed to
    // false (the entry log then records containsPolicy=false). Bounded
    // traversal (4096) so a hostile oversized list cannot pin the hook.
    internal fun originalContainsPolicy(original: List<*>, policy: Set<String>): Boolean {
        return try {
            if (original.isEmpty()) return false
            if (policy.isEmpty()) return false
            var seen = 0
            for (e in original) {
                if (seen++ >= 4096) break
                val pkg = try {
                    e as? String
                } catch (_: Throwable) {
                    continue
                } ?: continue
                val hit = try {
                    policy.contains(pkg)
                } catch (_: Throwable) {
                    false
                }
                if (hit) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    // Single entry-diagnosis line. Inputs are already desensitized (size +
    // boolean + caller snippet); this function takes no package strings by
    // construction, so package literals cannot leak. Never throws; bounded
    // to BBC_ENTRY_MAX_CHARS after control-char sanitizing.
    internal fun formatBbcEntryDiag(
        tsMs: Long,
        pid: Int,
        caller: String,
        resultSize: Int,
        containsPolicy: Boolean,
    ): String {
        return try {
            val safeCaller = try {
                HookLog.normalizeLogPart(caller)
            } catch (_: Throwable) {
                ""
            }.take(BBC_CALLER_MAX_CHARS)
            "BBC-ENTRY ts=$tsMs pid=$pid size=$resultSize containsPolicy=$containsPolicy caller=$safeCaller"
                .take(BBC_ENTRY_MAX_CHARS)
        } catch (_: Throwable) {
            "BBC-ENTRY pid=$pid"
        }
    }

    // R10 (P2): once-per-pid entry logger with a LAZY caller supplier. The
    // expensive stack capture (Thread.currentThread().stackTrace) is deferred:
    // the supplier is invoked only after the once-per-pid ledger claim, so a
    // hot path that exits early — or a pid already logged — never pays for it.
    // `ledger` and `sink` are injected so JVM tests can swap them; production
    // passes DualHook.bbcEntryLog + logi. Format/desensitization are unchanged
    // (still formatBbcEntryDiag; no package literals ever reach this seam).
    internal class BbcEntryLogger(
        private val ledger: MutableSet<String>,
        private val sink: (String) -> Unit,
    ) {
        // Cheap guard: at most ONE IN-FLIGHT stack capture per pid. On an empty
        // caller (unresolvable snippet) the guard is released together with the
        // ledger claim (see F5 in logOnce), so a later real-caller attempt can
        // still capture; at most one SUCCESSFUL capture/log per pid is emitted.
        private val captureGuard = java.util.Collections.synchronizedSet(HashSet<String>())

        fun logOnce(
            pid: Int,
            tsMs: Long,
            resultSize: Int,
            containsPolicy: Boolean,
            callerSupplier: () -> String,
        ): Boolean {
            return try {
                // At most ONE capture attempt per pid (cheap guard first, so a
                // repeat call never re-adds the ledger entry it is about to
                // decline). Capture is gated by the once-per-process ledger
                // claim below; both guards must pass before the supplier runs.
                if (!captureGuard.add("entry:$pid")) return false
                val added = try {
                    ledger.add("entry:$pid")
                } catch (_: Throwable) {
                    false
                }
                if (!added) return false
                // Capture ONLY after the ledger claim succeeds.
                val caller = try {
                    callerSupplier()
                } catch (_: Throwable) {
                    ""
                }
                if (caller.isEmpty()) {
                    // Same as the old behavior: an empty caller never leaves a
                    // ledger entry. F5: also release the capture guard — if we
                    // kept it, a later real-caller attempt for this pid would
                    // return early at `captureGuard.add` and never log. At most
                    // one SUCCESSFUL capture/log per pid still holds (the
                    // ledger claim is re-checked on every attempt).
                    try {
                        ledger.remove("entry:$pid")
                    } catch (_: Throwable) {
                    }
                    try {
                        captureGuard.remove("entry:$pid")
                    } catch (_: Throwable) {
                    }
                    return false
                }
                val line = try {
                    BbcInjector.formatBbcEntryDiag(tsMs, pid, caller, resultSize, containsPolicy)
                } catch (_: Throwable) {
                    return false
                }
                return try {
                    sink(line)
                    true
                } catch (_: Throwable) {
                    false
                }
            } catch (_: Throwable) {
                // Diagnostics never break the hook path.
                false
            }
        }
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
