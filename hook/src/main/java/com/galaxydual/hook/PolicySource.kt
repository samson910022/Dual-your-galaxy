// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) whitelist-policy source.
//
// DualHook cannot depend on :core (D1) and must not do IPC inside
// intercept(); this object owns the only policy decision: which package set
// is effective for the current call. Today the effective set is the compiled
// static seed (still empty = observe-only). A future change could pass a
// snapshot in as [remote]: validated the same way, merged over the seed,
// never shrinking it.
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
    const val NON_EMPTY_POLICY_EFFECTIVE_GATE: Boolean = false

    fun isValidPackage(pkg: String): Boolean =
        pkg.isNotEmpty() && PKG.matches(pkg)

    // Drops invalid (and null, defensively: RemotePreferences snapshots cross
    // process boundaries as Java collections) tokens; never throws.
    fun sanitize(raw: Set<String?>): Set<String> {
        val out = LinkedHashSet<String>()
        raw.forEach { if (it != null && isValidPackage(it)) out.add(it) }
        return out
    }

    // Effective policy: sanitized seed + sanitized remote snapshot.
    // Union-only: entries are never removed by the remote side.
    // Fail-closed: a sanitized non-empty merge is returned only when the
    // explicit code gate is open; otherwise returns empty. Stage 1 default
    // gate is closed, so behavior is observe-only (identical to empty inputs).
    // TODO(future-perf): cache the merged set and recompute only when the
    // remote snapshot changes; today the seed is empty so the fast path below
    // short-circuits with zero allocation.
    fun effective(
        seed: Set<String?> = STATIC_SEED,
        remote: Set<String?> = emptySet(),
        nonEmptyPolicyGateOpen: Boolean = NON_EMPTY_POLICY_EFFECTIVE_GATE,
    ): Set<String> {
        if (seed.isEmpty() && remote.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        out.addAll(sanitize(seed))
        out.addAll(sanitize(remote))
        if (out.isNotEmpty() && !nonEmptyPolicyGateOpen) return emptySet()
        return out
    }
}
