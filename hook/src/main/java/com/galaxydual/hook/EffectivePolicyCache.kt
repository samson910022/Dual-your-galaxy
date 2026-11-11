// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) cache for the effective whitelist-policy set.
//
// The hook calls effectivePolicy() on every whitelist query; the remote
// snapshot read + sanitize + merge is cached and recomputed only when
// invalidated (remote-prefs change listener) or after a failed read.
// A null reader result (or a reader/merge that throws) means "read failed":
// it is NOT cached. While a good value is cached it is returned (last known
// good stays applied); after an invalidation a failed read falls back to the
// seed alone (fail-closed observe-only), since the cached value may be stale.
class EffectivePolicyCache(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val lock = Any()
    private var cached: Set<String> = emptySet()
    private var valid = false

    // True once a good value has ever been cached; never reset on invalidate, so
    // the reader can distinguish "never had a good value" from "had one, then
    // invalidated/failed" for rate-limiting failure logging.
    @Volatile
    private var hadGoodValue: Boolean = false

    // Tracks whether a failure has already been logged, so each real
    // good->failed transition (or first-ever failure) is logged once.
    @Volatile
    private var failureLogged: Boolean = false

    // True after an explicit invalidate() until the next successful read. While
    // set, the cooldown path must NOT resurrect a (possibly stale) pre-
    // invalidation cached value; it falls back to the seed alone (fail-closed).
    @Volatile
    private var invalidated: Boolean = false

    // Cooldown after a failed remote read: avoid retrying (and logging) on
    // every query. Phase 5: comment language unified to English (was Chinese).
    private val failureCooldownMs = 5_000L

    // Redundant safety net for freshness: LSPosed delivers remote-prefs
    // changes to the hook process via IRemotePreferenceCallback, which the
    // DualHook listener uses to invalidate this cache. This TTL only forces a
    // re-read of the same cached RemotePreferences instance (it cannot force a
    // fresh daemon fetch if the framework ever stops pushing), so it is a
    // bounded redundancy, not the primary freshness mechanism.
    private val maxCacheAgeMs = 60_000L
    private var lastRefreshMs = 0L
    // Null means "no failure recorded yet" (also used after invalidate), so the
    // cooldown gate is not accidentally disabled when nowMs() reports 0.
    @Volatile
    private var lastFailureMs: Long? = null

    // Phase 4: hot-path lock-free IO generation. Bumped only by invalidate()
    // so an in-flight read started before an explicit invalidation is
    // discarded on submit (loser drops) instead of resurrecting stale state.
    // TTL expiry does NOT bump: a pre-expiry in-flight read already did a
    // fresh read and may satisfy the expiry.
    private var generation = 0L

    fun get(
        seed: Set<String>,
        nonEmptyPolicyGateOpen: Boolean = PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE,
        reader: () -> Set<String>?,
    ): Set<String> {
        // Phase 4: lock only for state check + generation snapshot. The
        // reader() IPC/sanitize runs OUTSIDE the lock (hot-path fix: every
        // whitelist query previously held the lock across binder IPC).
        val now0 = nowMs()
        val gen0: Long
        synchronized(lock) {
            // TTL expiry: fail closed to the seed on the next read (invalidated
            // true), so a stale cached value is never resurrected after expiry.
            if (valid && now0 - lastRefreshMs >= maxCacheAgeMs) {
                valid = false
                invalidated = true
            }
            if (valid) return cached.toSet()
            // During the cooldown, do not retry the reader; return the cache/seed.
            // Phase 5: comment language unified to English (was Chinese).
            val lf = lastFailureMs
            if (lf != null && now0 - lf < failureCooldownMs) {
                // After an explicit invalidation, never resurrect a stale
                // pre-invalidation cached value: fall back to the seed alone.
                return if (hadGoodValue && !invalidated) cached.toSet()
                else PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
            }
            gen0 = generation
        }
        // OUTSIDE lock: fallible IO + merge (sanitize may traverse hostile input).
        val remote: Set<String>? = try {
            reader()
        } catch (_: Throwable) {
            null
        }
        var merged: Set<String>? = null
        var mergeOk = false
        if (remote != null) {
            try {
                merged = PolicySource.effective(seed, remote, nonEmptyPolicyGateOpen)
                mergeOk = true
            } catch (_: Throwable) {
                // Corrupt/hostile remote set: treat as a failed read (not cached).
                mergeOk = false
            }
        }
        val now1 = nowMs()
        synchronized(lock) {
            // Loser discards: invalidated while IO was in flight, or a peer
            // already committed a good value.
            if (gen0 != generation) {
                return if (valid) cached.toSet() else PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
            }
            if (valid) return cached.toSet()
            if (mergeOk && merged != null) {
                // Defensive snapshot: callers receive copies (see returns
                // above), so a caller-held mutable view can never shrink or
                // poison the cached value.
                cached = merged.toSet()
                valid = true
                hadGoodValue = true
                invalidated = false
                lastFailureMs = null
                failureLogged = false
                lastRefreshMs = now1
                return cached.toSet()
            } else {
                // Sliding cooldown: refresh lastFailureMs on every failure
                // unconditionally; consecutive failures stay throttled (see
                // cooldownThrottlesConsecutiveFailures). Success/invalidate clears
                // it (branch above). Phase 5: unified to English.
                lastFailureMs = now1
                return if (hadGoodValue && !invalidated) cached.toSet()
                else PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
            }
        }
    }

    fun invalidate() = synchronized(lock) {
        valid = false
        // Policy change is an explicit signal, allow immediate retry (reset cooldown).
        lastFailureMs = null
        // Mark invalidated so the cooldown path cannot resurrect a stale
        // pre-invalidation cached value (fail-closed to seed).
        invalidated = true
        cached = emptySet()
        failureLogged = false
        generation++
    }

    // True only while a good value is cached; used by the reader to rate-limit
    // failure logging to the good->failed transition.
    fun isCached(): Boolean = synchronized(lock) { valid }

    // True once a good value has ever been cached (not reset by invalidate);
    // used to gate failure logging on the good->failed transition.
    fun hadGoodValue(): Boolean = hadGoodValue

    // Returns true once per failure transition (first failure or after a
    // successful recovery); used to rate-limit failure logging.
    fun shouldLogFailure(): Boolean = synchronized(lock) {
        if (!failureLogged) {
            failureLogged = true
            true
        } else {
            false
        }
    }
}
