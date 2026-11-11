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

    fun reportFalse() {
        if (latchedTrue) return
        lastFalseMs = nowMs()
    }

    // Test seam: report false at an explicit timestamp (avoids clock injection
    // ordering issues in tests).
    fun reportFalseAt(ms: Long) {
        if (latchedTrue) return
        lastFalseMs = ms
    }

    fun onInvalidate() {
        // An explicit policy-change signal clears a transient false so the
        // next query re-probes immediately instead of waiting out the
        // cooldown. A latched true is unaffected.
        if (!latchedTrue) lastFalseMs = null
    }

    fun shouldProbe(): Boolean {
        if (latchedTrue) return false
        val last = lastFalseMs ?: return true
        return nowMs() - last >= cooldownMs
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5_000L
    }
}
