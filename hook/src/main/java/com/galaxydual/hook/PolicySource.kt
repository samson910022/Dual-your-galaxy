// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) whitelist-policy source.
//
// DualHook cannot depend on :core (D1) and must not do IPC inside
// intercept(); this object owns the only policy decision: which package set
// is effective for the current call. The effective set is the remote
// RemotePreferences snapshot the user writes in the Galaxy Dual Plus policy
// editor, merged over the (empty) compiled static seed, never shrinking.
//
// Package grammar mirrors core ShellEscaper ([a-zA-Z0-9._-]+) without
// importing it, keeping :hook free of app-side types.
object PolicySource {

    private val PKG = Regex("^[a-zA-Z0-9._-]+$")

    // Static seed. Stays empty by design even with the gate open: effective
    // policy comes only from the RemotePreferences snapshot the user writes.
    val STATIC_SEED: Set<String> = emptySet()

    // Code gate for non-empty effective policy. Ships TRUE since on-device
    // verification on Galaxy Tab S7 (One UI 5 / Android 13) and Galaxy S23
    // Ultra (One UI 9 / Android 17): policy packages confirmed to appear as
    // toggleable entries in Samsung's Dual Messenger settings page, and dual
    // instances install+launch in the user-95 dual profile (see
    // docs/TAB7_BBC_INJECTION_PLAN.md). The code gate remains a kill switch:
    // flipping this (or the explicit seam argument) to false restores exact
    // prior fail-closed behavior (verified by unit tests).
    const val NON_EMPTY_POLICY_EFFECTIVE_GATE: Boolean = true

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
    // explicit code gate is open; otherwise returns empty. The shipped default
    // is now open (verified on-device with controlled empty vs non-empty A/B).
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
