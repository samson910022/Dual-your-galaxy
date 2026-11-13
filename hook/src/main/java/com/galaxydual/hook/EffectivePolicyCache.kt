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
//
// Phase7: follower grace is budget-aware + process-tunable via
// [get]'s followerGraceMs param (default [DEFAULT_FOLLOWER_GRACE_MS] 50ms;
// system_server may pass 0 to skip the grace sleep entirely).
import java.util.concurrent.atomic.AtomicLong

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

    // Redundant safety net for freshness: on LSPosed API 102.0.0 there is NO
    // cross-process push (IXposedService has no remote-preference callback
    // transaction; RemotePreferences fetches mMap once and only notifies
    // local listeners; XposedService.getRemotePreferences is computeIfAbsent
    // cached forever). A same-process listener therefore never fires for an
    // app-process write. Freshness here is POLL ONLY: this TTL forces a
    // re-read of the cached RemotePreferences instance after maxCacheAgeMs.
    // Because the instance is cached forever, even the poll may return stale
    // data until reboot (reboot semantics: daemon restart rebuilds the map).
    // See docs/HOOK.md §1.1 (poll/TTL + version/sequence key + reboot) and
    // the JVM contract test pinning no cross-process notify
    // (CrossProcessFreshnessContractTest).
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

    // Stage C (P2 single-flight): non-null while a leader read is in flight,
    // holding the generation it claimed under. Guarded by [lock] (like
    // [generation], never volatile-read on the hot path). A follower that
    // observes non-null returns without calling reader (seed-only or a valid
    // snapshot). Cleared by the leader's finally (generation-matched, so a
    // stale leader never clears a successor) and by invalidate().
    private var inFlightGen: Long? = null

    // Follower grace (Phase3 herd fix, Phase7 budget-aware): a follower that
    // arrives while a leader read is in flight waits briefly on [lock], then
    // re-checks for a committed value before falling back to seed-only.
    // When the leader finishes (commits or aborts), it wakes all followers
    // immediately via notifyAll (~2-3ms instead of waiting full grace).
    // Fail-closed in all cases (seed-only fallback, never stale). Phase7: grace
    // is min(50, remaining end-to-end budget) passed by DualHook; 0 skips the
    // wait entirely (system_server tunable). Hits counted in [followerGraceHits]
    // for observability.
    companion object {
        const val DEFAULT_FOLLOWER_GRACE_MS = 50L
    }

    // Phase7 observability: follower grace waits taken (grace > 0 path).
    // Skipped (grace <= 0) fast-paths do not increment. Monotonic, never
    // reset (process-lifetime; tests read only).
    val followerGraceHits = AtomicLong(0L)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitGrace(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        (lock as java.lang.Object).wait(timeoutMs)
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun notifyFollowers() {
        (lock as java.lang.Object).notifyAll()
    }

    fun get(
        seed: Set<String>,
        nonEmptyPolicyGateOpen: Boolean = PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE,
        followerGraceMs: Long = DEFAULT_FOLLOWER_GRACE_MS,
        deadlineMs: Long = -1L,
        reader: () -> Set<String>?,
    ): Set<String> {
        // Stage C: gate-closed short-circuit. Returns empty without touching
        // the reader or any cache state (no lock, no nowMs, no mutation), so
        // observe-only callers never trigger IPC reads.
        if (!nonEmptyPolicyGateOpen) return emptySet()
        // Phase 4: lock only for state check + generation snapshot. The
        // reader() IPC/sanitize runs OUTSIDE the lock (hot-path fix: every
        // whitelist query previously held the lock across binder IPC).
        // nowMs() throwing fails closed to cached/seed (no clock, no retry).
        val now0: Long = try {
            nowMs()
        } catch (_: Throwable) {
            synchronized(lock) {
                return if (valid) cached.toSet()
                else PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
            }
        }
        val gen0: Long
        var isFollower = false
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
            // Stage C single-flight claim: a second concurrent miss becomes a
            // follower. Phase3: followers do NOT return seed-only immediately;
            // they take the grace path below (sleep outside the lock, then
            // re-check valid) so a just-committed leader value is observed.
            // Only the leader proceeds to the IO below.
            if (inFlightGen != null) {
                // Re-check valid under the same guard before leaving (a peer
                // may have committed between the valid check above and here).
                if (valid) return cached.toSet()
                isFollower = true
                // Fall through to the grace wait outside the lock.
                // gen0 unused for followers; assign a placeholder to satisfy
                // definite assignment (never read on this path).
                gen0 = -1L
            } else {
                inFlightGen = generation
                gen0 = generation
            }
        }
        if (isFollower) {
            // OUTSIDE lock: wait on [lock] with remaining grace budget so
            // in-flight leader can wake followers immediately via notifyAll().
            // If remainingGrace <= 0L, do not wait; fall back to cached/seed immediately.
            val now1 = try {
                nowMs()
            } catch (_: Throwable) {
                now0
            }
            val elapsed = try {
                (now1 - now0).coerceAtLeast(0L)
            } catch (_: Throwable) {
                0L
            }
            val remainingGrace = if (deadlineMs > 0L) {
                try {
                    minOf(followerGraceMs - elapsed, deadlineMs - now1).coerceIn(0L, DEFAULT_FOLLOWER_GRACE_MS)
                } catch (_: Throwable) {
                    0L
                }
            } else {
                try {
                    (followerGraceMs - elapsed).coerceIn(0L, DEFAULT_FOLLOWER_GRACE_MS)
                } catch (_: Throwable) {
                    0L
                }
            }
            if (remainingGrace <= 0L) {
                synchronized(lock) {
                    if (valid) return cached.toSet()
                    return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
                }
            }
            try {
                followerGraceHits.incrementAndGet()
            } catch (_: Throwable) {
            }
            synchronized(lock) {
                if (!valid && inFlightGen != null) {
                    try {
                        waitGrace(remainingGrace)
                    } catch (t: Throwable) {
                        if (t is InterruptedException) {
                            try {
                                Thread.currentThread().interrupt()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                if (valid) return cached.toSet()
                return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
            }
        }
        // OUTSIDE lock: fallible IO + merge (sanitize may traverse hostile input).
        // Phase3: gate contention (PolicyRefreshContentionException) is NOT a
        // failed read — it returns seed-only WITHOUT refreshing failureCooldown
        // (no cooldown renewal, so the next query retries immediately instead
        // of being throttled another 5s by a herd collision).
        var wasContention = false
        val remote: Set<String>? = try {
            reader()
        } catch (e: PolicyRefreshContentionException) {
            wasContention = true
            null
        } catch (t: Throwable) {
            // Reader Error (including AssertionError) / Exception / interrupt:
            // failed read (not cached); cooldown applies below. Restore the
            // interrupt bit so callers can observe cancellation.
            if (t is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            null
        }
        // Contention short-circuits before merge: no failureCooldown update.
        if (wasContention) {
            synchronized(lock) {
                try {
                    // A peer may have committed while we were in IO; prefer it.
                    if (valid) return cached.toSet()
                    // Loser-drop still applies: an invalidate during IO means
                    // seed-only (never resurrect stale).
                    if (gen0 != generation) {
                        return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
                    }
                    return if (hadGoodValue && !invalidated) cached.toSet()
                    else PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
                } finally {
                    if (inFlightGen == gen0) {
                        inFlightGen = null
                        notifyFollowers()
                    }
                }
            }
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
        val now1: Long = try {
            nowMs()
        } catch (_: Throwable) {
            now0
        }
        synchronized(lock) {
            try {
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
            } finally {
                // Stage C: release the single-flight claim, but only if it is
                // still ours -- an invalidate() (which clears inFlightGen) or
                // a successor leader must never be cleared by a stale leader.
                // Wake all waiting followers immediately via notifyAll().
                if (inFlightGen == gen0) {
                    inFlightGen = null
                    notifyFollowers()
                }
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
        // Stage C: release any in-flight single-flight claim so the next miss
        // elects a fresh leader under the new generation (the stale leader
        // discards via the gen0 != generation loser-drop above).
        // Wake all waiting followers immediately via notifyAll().
        inFlightGen = null
        generation++
        notifyFollowers()
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
