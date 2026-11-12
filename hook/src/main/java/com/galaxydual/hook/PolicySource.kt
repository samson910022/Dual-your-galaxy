// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

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
// importing it, keeping :hook free of app-side types.
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

    // Remote policy prefs group/key, single-sourced here and referenced by the
    // app-side writer and the hook-side reader. Renaming either abandons any
    // persisted draft (breaking change; review required).
    const val POLICY_PREFS_NAME = "dual_policy"
    const val POLICY_KEY_EXTRA_PACKAGES = "extra_whitelist_packages"

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

    fun isValidPackage(pkg: String): Boolean =
        pkg.isNotEmpty() && PKG.matches(pkg)

    // Drops invalid tokens and any non-String element (defensively:
    // RemotePreferences snapshots cross process boundaries as Java collections,
    // and a corrupt prefs entry can carry any object); never throws.
    // Bounds defend against a hostile/corrupt remote prefs entry exhausting
    // memory on the hook side (system_server/daagent). A trusted writer is
    // bounded by the UI (<= 200 source rows), so these are generous.
    const val MAX_PACKAGE_LENGTH = 256
    const val MAX_POLICY_ENTRIES = 512

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
                if (out.size >= MAX_POLICY_ENTRIES) break
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
            val truncated = LinkedHashSet<String>(MAX_POLICY_ENTRIES)
            out.take(MAX_POLICY_ENTRIES).forEach { truncated.add(it) }
            return truncated
        }
        return out
    }
}
