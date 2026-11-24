// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.util.concurrent.atomic.AtomicLong

// Pure (framework-free) whitelist-policy source.
//
// DualHook cannot depend on :core (D1) and must not do IPC inside
// intercept(); this object owns the only policy decision: which package set
// is effective for the current call. Today the effective set is the compiled
// static seed (still empty = observe-only) plus a remote snapshot supplied by
// the injected [remotePolicyReader]. DualHook wires a RemotePreferences-backed
// reader, and the app writes the whitelist draft to the same LSPosed
// remote-prefs group; until an on-device run proves the write/read round-trip,
// the snapshot may still be empty (fail-closed, identical to the seed alone).
// Union-only: the remote side can only add entries, never shrink the seed.
//
// Package grammar mirrors core ShellEscaper ([a-zA-Z0-9._-]+) without
// importing it, keeping :hook free of app-side types. It is a superset of
// the Android package grammar by design; see the equality-only CONTRACT
// comment at isValidPackage before reusing these tokens elsewhere.
object PolicySource {

    private val PKG = Regex("^[a-zA-Z0-9._-]+$")

    // Compiled static seed. Empty = observe-only placeholder (current state).
    // A future change may add well-known Samsung-safe defaults here, but only
    // after on-device proof; until then it stays empty.
    val STATIC_SEED: Set<String> = emptySet()

    // Code gate for non-empty effective policy. Remains false until HOOK.md §1.4 hard gate is satisfied with device evidence (signatures/returns, hook-install counts, callback hits, user/profile/install-state, controlled empty vs non-empty). This is a code gate only, not device verification.
    // Phase4 note: compile-time constant — flipping it requires a source change
    // + recompile; there is NO runtime switch/flag/remote kill-switch that can
    // open the gate on a shipped build. Single source of truth for TTL/budget
    // numbers paired with this gate is docs/USAGE.md §政策 TTL 對照 (App 5min /
    // hook cache 60s / failure-cooldown 5s).
    const val NON_EMPTY_POLICY_EFFECTIVE_GATE: Boolean = false

    // BBC 注入器效應開關：只放寬 BBC 產生的「Dual Messenger 可用的應用程式」
    // 清單（union-only append，fail-closed），不影響 Stage-1 白名單查詢／
    // system_server／<clinit> 路徑（封緘不變）。
    // PR5 Phase A (#80)：此 compile-time 常數**不再作為「預設開」的依據**；它只
    // 保留為最終 AND 的緊急開關（設 false 即 BBC 零合併）。真正的生效前 opt-in
    // 由 dual_policy/bbc_preactivation_optin（新安裝 fail-closed）與既有安裝的
    // 安全遷移規則決定，見 BbcPreactivationGate.kt 與
    // docs/ADR_bbc_preactivation_optin.md。與 Stage-1 閘相互獨立
    // （docs/HOOK.md §1.3a／§1.4）。
    // 注意：關閉不是 Samsung 冷啟動刪除的防護（DELETE 早於 BBC 回調，issue #75）。
    const val BBC_AVAILABLE_APPS_INJECTION: Boolean = true

    // Remote policy prefs group/key, single-sourced here and referenced by the
    // app-side writer and the hook-side reader. Renaming either abandons any
    // persisted draft (breaking change; review required).
    const val POLICY_PREFS_NAME = "dual_policy"
    const val POLICY_KEY_EXTRA_PACKAGES = "extra_whitelist_packages"
    // Phase A P0 (BBC sticky last-good): explicit-empty version key. Written
    // atomically with POLICY_KEY_EXTRA_PACKAGES by HookPolicyStore.save/clear
    // (same commit, monotonic +1 per write). Hook side treats empty+version as
    // intentional Cleared (shrink allowed); empty without version as Unknown
    // (sticky, never shrink). Non-empty without version stays Good for old-
    // draft backward compat (pre-version drafts still merge). Renaming either
    // abandons persisted drafts (breaking change; review required).
    const val POLICY_KEY_EXTRA_VERSION = "extra_whitelist_version"
    // PR #74 G1: persisted owner-controlled BBC injection opt-out (same
    // dual_policy group). App writes a boolean via HookPolicyStore; the hook
    // reads it on the BBC path and ANDs it with the pre-activation gate and
    // BBC_AVAILABLE_APPS_INJECTION. FALSE is always an explicit opt-out.
    // PR5 Phase A (#80): this key is NO LONGER the "default on" source — an
    // absent key still maps to true via its adapter defValue (no explicit
    // OFF), but a READ FAILURE (null) now FAILS CLOSED in the hybrid gate.
    // The real "off until opted in" comes from the new keys below.
    // This is a UI convenience, NOT a proven guard against Samsung's
    // system-side cold-start deletion (DELETE precedes the BBC callback;
    // issue #75 / docs/BBC_COLDSTART_DELETE_INCIDENT.md). Renaming abandons
    // persisted preference (breaking change; review required).
    const val POLICY_KEY_BBC_INJECTION_ENABLED = "bbc_injection_enabled"

    // PR5 Phase A (#80): the real pre-activation opt-in — BREAKING-CHANGE-
    // SENSITIVE keys. The hook treats an absent/unreadable
    // POLICY_KEY_BBC_PREACTIVATION_OPTIN as OFF for new installs; the app writes
    // true ONLY AFTER an explicit user pre-activation consent. For existing
    // installs, POLICY_KEY_BBC_LEGACY_MIGRATED records the one-time safe
    // migration of a non-empty legacy policy draft (legacy opted-in => effect
    // stays ON; the draft itself is NEVER deleted/cleared/rewritten). Both live
    // in the same dual_policy remote-prefs group. RENAMING EITHER KEY ABANDONS
    // PERSISTED CONSENT / MIGRATION STATE (breaking change; review required).
    // Full decision table: BbcPreactivationGate.kt and
    // docs/ADR_bbc_preactivation_optin.md.
    const val POLICY_KEY_BBC_PREACTIVATION_OPTIN = "bbc_preactivation_optin"
    const val POLICY_KEY_BBC_LEGACY_MIGRATED = "bbc_legacy_migrated"

    // Pluggable remote-policy reader (Stage H2-P1 seam; Phase 4 hardened).
    //
    // Default is empty (observe-only). DualHook wires a RemotePreferences-backed
    // reader that returns null on a failed read (NOT empty: null preserves the
    // fail-closed "read failed" signal so the cache layer can keep last-known-
    // good instead of caching an empty observe-only set). JVM tests inject
    // fakes here (including null and throwing readers).
    // The reader MAY return unsanitized tokens (remotePolicySnapshot()
    // sanitizes); it MUST be assumed fallible -- null or any Throwable
    // (including Error) fails closed to empty inside remotePolicySnapshot().
    // Phase4 visibility: module-internal (VisibleForTesting discipline without
    // a new annotation dependency in :hook). Production assigns this exactly
    // once at hook init (DualHook wiring below); external modules (app) never
    // touch it. Tests mutate ONLY via save/restore (see PolicySourceTest
    // @Before/@After global restore plus per-test try/finally).
    @Volatile
    internal var remotePolicyReader: () -> Set<String>? = { emptySet() }

    // CONTRACT (equality-only, S7): tokens are used for equality matching /
    // set membership against the (seed ∪ sanitized remote) policy sets, and —
    // since 2026-11-18 — for the BBC injector only: (a)
    // PackageManager.getApplicationInfo name lookup (never a path/command/
    // URI), and (b) a once-per-process, ≤4-token logcat diagnostic. This
    // grammar is a deliberate SUPERSET of the Android package grammar (it
    // admits '-', '..' and separator oddities such as "a-", "..", "com..x"),
    // which is safe only under those two consumers and equality-only use —
    // nothing else feeds a token to PackageManager, a URI, a file path, a
    // shell, an Intent/ComponentName, or display output on the strength of
    // this check. ANY future reuse beyond these MUST re-validate against a
    // real Android package grammar (e.g. dot-separated segments of
    // [A-Za-z][A-Za-z0-9_]*) and MUST NOT rely on this regex. Do not loosen
    // it further without re-checking every consumer of effective()/sanitize().
    fun isValidPackage(pkg: String): Boolean =
        pkg.isNotEmpty() && PKG.matches(pkg)

    // Strict Android package grammar for values that become SHELL ARGV TOKENS
    // (pre-clear dual probe/uninstall builders in :app). Unlike isValidPackage
    // (equality-only superset that admits '-', '..', "com..x", leading digits),
    // this rejects anything that is not a dot-separated sequence of
    // [A-Za-z][A-Za-z0-9_]* segments with at least one dot, and bounds the
    // length to MAX_PACKAGE_LENGTH. Use this — never isValidPackage — for any
    // token interpolated into a pm command or re-filtered out of probe output.
    private val SHELL_PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidShellPackage(pkg: String): Boolean =
        pkg.length <= MAX_PACKAGE_LENGTH && SHELL_PKG.matches(pkg)

    // Drops invalid tokens and any non-String element (defensively:
    // RemotePreferences snapshots cross process boundaries as Java collections,
    // and a corrupt prefs entry can carry any object); never throws.
    // Bounds defend against a hostile/corrupt remote prefs entry exhausting
    // memory on the hook side (system_server/daagent). A trusted writer is
    // bounded by the UI (<= 200 source rows), so these are generous.
    const val MAX_PACKAGE_LENGTH = 256
    const val MAX_POLICY_ENTRIES = 512

    // H4: process-lifetime count of policy entries dropped by the caps
    // (sanitize valid-entry cap / effective merged-set truncation). Never
    // drives logic; observability only, never reset. Internal for tests.
    // Counts CUMULATIVE drop events across polls (a re-offered oversized
    // draft is re-counted on each refresh); not distinct entries.
    // sanitize() adds raw.size - kept at the valid-cap break (hostile size =>
    // best-effort +1, clamped to 4096 for huge-lie sizes); effective() adds
    // the exact seed-first extras it truncates. The MAX_POLICY_ENTRIES*8
    // traversal bound is a defense for invalid-token floods; entries beyond
    // the traversal bound (incl. possibly valid ones after a mixed invalid
    // flood) are dropped without counting; the valid tail is unknowable
    // without unbounded traversal.
    internal val truncationDrops = AtomicLong(0L)

    fun sanitize(raw: Set<*>): Set<String> {
        val out = LinkedHashSet<String>()
        var seen = 0
        val iter: Iterator<*> = try {
            raw.iterator()
        } catch (_: Throwable) {
            // Hostile set whose iterator() throws: fail closed to empty
            // (standalone-safe; never propagates out of sanitize).
            return emptySet()
        }
        try {
            while (true) {
                val hasNext: Boolean = try {
                    iter.hasNext()
                } catch (_: Throwable) {
                    break
                }
                if (!hasNext) break
                val it: Any? = try {
                    iter.next()
                } catch (_: Throwable) {
                    break
                }
                // Bound total input traversal (defense against huge invalid-token
                // payloads that never reach the valid-entry cap).
                if (seen++ >= MAX_POLICY_ENTRIES * 8) break
                if (out.size >= MAX_POLICY_ENTRIES) {
                    // Valid entry beyond the cap: count the unkept remainder
                    // (exact for sane writers with an all-valid tail; a mixed tail
                    // over-counts, since invalid tokens that would never become
                    // policy entries are included by construction; a hostile/huge-lie
                    // size read is clamped to at least +1 and at most 4096).
                    // Never throws.
                    val dropped = try {
                        raw.size - out.size
                    } catch (_: Throwable) {
                        1
                    }
                    truncationDrops.addAndGet(dropped.coerceAtLeast(1).coerceAtMost(4096).toLong())
                    break
                }
                if (it is String &&
                    it.length <= MAX_PACKAGE_LENGTH &&
                    isValidPackage(it)
                ) out.add(it)
            }
        } catch (_: Throwable) {
            // Hostile iterator machinery (hasNext/next throwing mid-walk):
            // keep the valid prefix collected so far (fail-closed partial).
            // Callers that need all-or-nothing use effective(), which applies
            // the gate on the sanitized result.
        }
        return out
    }

    // Remote snapshot through the injected reader: sanitized, never throws.
    // Empty reader (default / service absent), a null reader result (failed
    // read), or a throwing reader (any Throwable including Error) all yield
    // empty (fail-closed, observe-only); the seed is never shrunk.
    fun remotePolicySnapshot(): Set<String> =
        try {
            val raw = remotePolicyReader() ?: return emptySet()
            sanitize(raw)
        } catch (_: Throwable) {
            emptySet()
        }

    // Effective policy: sanitized seed + sanitized remote snapshot.
    // Union-only: entries are never removed by the remote side.
    // Fail-closed: a sanitized non-empty merge is returned only when the
    // explicit code gate is open; otherwise returns empty. Stage 1 default
    // gate is closed, so behavior is observe-only (identical to empty inputs).
    // Caching is the hook side's job (EffectivePolicyCache wraps this merge and
    // invalidates on remote-prefs changes); this function stays pure and keeps
    // its empty fast path.
    fun effective(
        seed: Set<String?> = STATIC_SEED,
        remote: Set<String?> = emptySet(),
        nonEmptyPolicyGateOpen: Boolean = NON_EMPTY_POLICY_EFFECTIVE_GATE,
    ): Set<String> {
        // Standalone Throwable-safety (P2-3): hostile set views (iterator /
        // hasNext / next / contains throwing, including Error) must fail
        // closed here instead of propagating out of the hook chain. Each side
        // is sanitized independently so a hostile remote can never poison the
        // seed side (fail-closed seed-only) and a hostile seed fails to empty.
        val seedEmpty: Boolean = try {
            seed.isEmpty()
        } catch (_: Throwable) {
            false
        }
        val remoteEmpty0: Boolean = try {
            remote.isEmpty()
        } catch (_: Throwable) {
            false
        }
        if (seedEmpty && remoteEmpty0) return emptySet()
        val seedPart: Set<String> = try {
            @Suppress("UNCHECKED_CAST")
            sanitize(seed as Set<*>)
        } catch (_: Throwable) {
            emptySet()
        }
        // isEmpty() itself runs on caller-supplied machinery and may throw.
        val remoteEmpty: Boolean = try {
            remote.isEmpty()
        } catch (_: Throwable) {
            false
        }
        val remotePart: Set<String> = if (remoteEmpty) {
            emptySet()
        } else {
            try {
                @Suppress("UNCHECKED_CAST")
                sanitize(remote as Set<*>)
            } catch (_: Throwable) {
                emptySet()
            }
        }
        val out = LinkedHashSet<String>()
        out.addAll(seedPart)
        out.addAll(remotePart)
        if (out.isNotEmpty() && !nonEmptyPolicyGateOpen) return emptySet()
        // Enforce a single global cap on the merged set (seed + remote), so a
        // hostile split between the two sides cannot double the bound.
        // Truncation is seed-priority by intent: seed entries were added first
        // so LinkedHashSet iteration keeps them ahead of remote entries; the
        // first MAX_POLICY_ENTRIES survivors are therefore seed-first. This is
        // fail-closed (never shrinks the seed to make room for remote).
        if (out.size > MAX_POLICY_ENTRIES) {
            truncationDrops.addAndGet((out.size - MAX_POLICY_ENTRIES).toLong())
            val truncated = LinkedHashSet<String>(MAX_POLICY_ENTRIES)
            out.take(MAX_POLICY_ENTRIES).forEach { truncated.add(it) }
            return truncated
        }
        return out
    }
}
