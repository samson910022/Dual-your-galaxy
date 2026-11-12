// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) latch for the remote-capability probe (P2-1).
//
// Problem: DualHook latched `remoteCapable=false` forever. A transient early
// probe (framework not yet attached, getFrameworkProperties()==0) would pin
// the process to observe-only for its lifetime.
//
// Policy: only a definitive `true` is latched forever (a framework that
// reports remote-capable never loses the capability in-process). A `false`
// is treated as a transient/cooldown-gated value: the caller may re-probe
// after [cooldownMs] or immediately after an explicit [onInvalidate].
// The class is pure (injected clock) so JVM tests can pin the flip without
// loading DualHook / LibXposed.
//
// Scope (TTL-gated inner gate): this latch alone does NOT set the effective
// retry interval. DualHook maps a `false` probe to `emptySet()` (a successful
// empty read), which EffectivePolicyCache caches for `maxCacheAgeMs` (60s).
// `isRemoteCapable()` is only reached on a cache miss (TTL expiry or
// explicit invalidate), so the 5s latch here is an INNER gate inside the
// 60s TTL: effective false retry = max(60s TTL, 5s latch). Invalidate clears
// both (cache.invalidate + latch.onInvalidate) for an immediate retry.
class RemoteCapabilityLatch(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    @Volatile
    private var latchedTrue = false

    @Volatile
    private var lastFalseMs: Long? = null

    fun isLatchedTrue(): Boolean = latchedTrue

    fun reportTrue() {
        latchedTrue = true
        lastFalseMs = null
    }

    // Throttled: high-frequency false reports within the cooldown do NOT
    // extend the window (fixed cooldown from the first false). Without this
    // a hot-path false storm would keep pushing lastFalseMs forward and
    // shouldProbe() would never go true again. Effective false retry stays
    // max(60s TTL, 5s latch) — see class docs.
    fun reportFalse() {
        if (latchedTrue) return
        try {
            val now = nowMs()
            val last = lastFalseMs
            if (last != null && now - last < cooldownMs) return
            lastFalseMs = now
        } catch (_: Throwable) {
            // Clock broken: fail-closed without touching latch state.
        }
    }

    // Test seam: report false at an explicit timestamp (avoids clock injection
    // ordering issues in tests). Same throttling as reportFalse(): a report
    // inside the cooldown window is ignored so throw-storm throttling via
    // reportFalseAt(now) cannot slide the window forever.
    fun reportFalseAt(ms: Long) {
        if (latchedTrue) return
        try {
            val last = lastFalseMs
            if (last != null && ms - last < cooldownMs) return
            lastFalseMs = ms
        } catch (_: Throwable) {
        }
    }

    fun onInvalidate() {
        // An explicit policy-change signal clears a transient false so the
        // next query re-probes immediately instead of waiting out the
        // cooldown. A latched true is unaffected.
        if (!latchedTrue) lastFalseMs = null
    }

    fun shouldProbe(): Boolean {
        // Fast path: latched true never re-probes (no clock call); no prior
        // false also probes immediately (no clock call).
        if (latchedTrue) return false
        val last = lastFalseMs ?: return true
        return try {
            nowMs() - last >= cooldownMs
        } catch (_: Throwable) {
            // Clock broken: fail-closed to no-probe (observe-only cached value).
            false
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5_000L
    }
}
