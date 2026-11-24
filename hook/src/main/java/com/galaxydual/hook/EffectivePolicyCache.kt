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
// H8: the caller degrades the follower grace to 0 when the deadline basis is
// broken (DualHook passes followerGraceMs = 0 and deadlineMs = -1 on a broken
// nanoTime sample); a zero grace falls back to cached/seed synchronously —
// the same fail-closed shape DeadlineBudget gives broken clocks (no budget).
import java.util.concurrent.atomic.AtomicLong

class EffectivePolicyCache(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onLog: (String) -> Unit = {},
    // Phase A P0 (BBC sticky last-good): when true, failed reads keep the
    // last successful snapshot across TTL expiry / invalidate / cooldown /
    // contention / clock / deadline instead of falling back to seed.
    // Whitelist path uses false (fail-closed, zero behavior change); BBC path
    // uses a separate instance with true (see DualHook.bbcCache).
    private val stickyLastGood: Boolean = false,
) {
    // lock is a plain monitor guard for synchronized(lock); wait/notify need
    // the java.lang.Object surface, which kotlin.Any does not expose.
    private val lock = java.lang.Object()
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
        // Phase A P0 sticky cap: last-good is retained at most this long.
        // Past the cap the sticky snapshot expires to empty (fail-closed) so a
        // dead daemon cannot hide a policy clear indefinitely. 30 min >> 60s
        // TTL so normal polls never hit it; only a sustained failure storm
        // (no successful read for 30 min) expires. Clock skew or a dead clock
        // fails closed (discards sticky) instead of widening the cap.
        // Retunable without breaking the contract as long as it stays >> TTL + cooldown.
        const val BBC_STICKY_MAX_AGE_MS = 30L * 60L * 1000L
    }

    // Phase A P0 BBC explicit-empty token. Good = successful non-empty read
    // (mergeable); Cleared = successful empty read WITH version proof
    // (intentional clear, shrink allowed); Unknown = failed read (null /
    // throw / contention / deadline / clock) OR successful empty WITHOUT
    // version proof (indistinguishable from "never written", never shrink).
    // Unknown carries the sticky last-good (possibly empty) for the caller to
    // merge (non-empty) or no-op (empty) + one-time keep-last-good logging.
    sealed interface BbcPolicy {
        data class Good(val packages: Set<String>) : BbcPolicy
        data class Cleared(val version: Long) : BbcPolicy
        data class Unknown(val lastGood: Set<String>) : BbcPolicy
    }

    // Phase7 observability: follower grace waits taken. Counted inside the
    // lock immediately before waitGrace(), so hits==1 implies the follower
    // was parked (or about to park) in the wait; fast-paths that return
    // without waiting never increment — neither do grace <= 0 skips, nor a
    // follower that finds the leader already done on entry (no wait).
    // Monotonic, never reset (process-lifetime; tests read only).
    val followerGraceHits = AtomicLong(0L)

    // H9 observability: monotonic counters for the seed-only fallback shapes
    // that used to return silently, plus a process-wide throttled failure
    // log (at most one line per FALLBACK_LOG_THROTTLE_MS, including the live
    // counter values) so a dead daemon cannot spam system_server on every
    // query. Counters are plain increments; the log path is allocation-light
    // and never throws.
    val contentionFallbackSeedOnly = AtomicLong(0L)
    val invalidatedDiscardSeedOnly = AtomicLong(0L)
    val graceExpiredSeedOnly = AtomicLong(0L)
    // O3: the failureCooldownMs-window seed-only fallback (no prior good
    // value, or an explicit invalidation since) is a distinct shape from an
    // invalidate-during-IO discard; counted separately so H9 sees it.
    val cooldownFallbackSeedOnly = AtomicLong(0L)
    // CR-O4: the merge-failed / failed-read fallback (reader returned null or
    // threw, or the merged set could not be built) with nothing good cached
    // and nothing to resurrect — the last H9-silent seed-only arm, counted
    // like its siblings. (hadGoodValue && !invalidated still returns the
    // last-known-good snapshot and never counts here.)
    val failedReadSeedOnly = AtomicLong(0L)
    private val lastFallbackLogMs = AtomicLong(Long.MIN_VALUE)
    private val FALLBACK_LOG_THROTTLE_MS = 5_000L

    // Phase A P0 BBC sticky state (independent lastGoodBbc; whitelist path
    // with stickyLastGood=false never touches these). Guarded by [lock] like
    // [cached]/[valid]; counters are process-lifetime observability only.
    // lastGoodSticky holds the last SUCCESSFUL BBC snapshot (Good non-empty
    // or Cleared empty-with-version); Unknown never updates it. It survives
    // TTL expiry + invalidate (unlike [cached]/[valid]/[invalidated]) until
    // the sticky cap expires it.
    private var lastGoodSticky: Set<String>? = null
    private var lastGoodStickyAtMs: Long = 0L
    // Trusted-clock high-water mark (guarded by [lock], like the sticky state).
    // Every nowMs() sample used for a policy decision in getBbc() is validated
    // against this mark; a sample BELOW it is a backwards jump / skew and is
    // treated as untrusted (fail closed). Sampling and the high-water update are
    // a single atomic step under [lock], so a thread that samples earlier but
    // takes the lock later is NOT misclassified: samples are totally ordered by
    // lock acquisition and only a genuine decrease is rejected.
    private var lastTrustedClockMs: Long? = null
    private var lastBbcClearedVersion: Long = -1L
    // Phase A P0 Security MEDIUM: monotonic version gate. Tracks the highest
    // accepted BBC version (Good-with-version or Cleared) to reject replays:
    // Cleared requires strictly increasing (reject 0/negative, reject
    // duplicate/replay), with a wraparound exception (writer wraps to 1 after
    // Long.MAX_VALUE-1/MAX). Updated only on accepted Good/Cleared, never on
    // Unknown/rejected-Cleared, under [lock].
    private var lastBbcSeenVersion: Long = -1L
    val bbcStickyFallbackCount = AtomicLong(0L)
    val bbcStickyExpiredCount = AtomicLong(0L)
    val bbcUnknownCount = AtomicLong(0L)
    private val lastBbcLogMs = AtomicLong(Long.MIN_VALUE)

    private fun waitGrace(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        lock.wait(timeoutMs)
    }

    private fun notifyFollowers() {
        lock.notifyAll()
    }

    // H9: one throttled failure line per FALLBACK_LOG_THROTTLE_MS across all
    // seed-only causes. Logging must never break the hook path and
    // must never let a dead daemon spam the log on every whitelist query.
    // O2/CR-N2: a broken clock (nowMs() throws => Long.MIN_VALUE sentinel,
    // matching lastStuckOpenMs/lastFallbackLogMs — -1L is a legitimate
    // negative millisecond sample and must never be mistaken for it) skips
    // the log entirely — including the throttle-guard update — so a dead
    // clock cannot bypass the 5s throttle and spam; counters at the call
    // sites may still increment (observability only).
    private fun noteSeedOnlyFallback(cause: String) {
        val now = try {
            nowMs()
        } catch (_: Throwable) {
            Long.MIN_VALUE
        }
        if (now == Long.MIN_VALUE) return
        val last = lastFallbackLogMs.get()
        if (last != Long.MIN_VALUE && now - last < FALLBACK_LOG_THROTTLE_MS) return
        if (!lastFallbackLogMs.compareAndSet(last, now)) return
        try {
            onLog(
                "policy cache seed-only fallback ($cause): " +
                    "contention=${contentionFallbackSeedOnly.get()} " +
                    "invalidatedDiscard=${invalidatedDiscardSeedOnly.get()} " +
                    "graceExpired=${graceExpiredSeedOnly.get()} " +
                    "cooldown=${cooldownFallbackSeedOnly.get()} " +
                    "failedRead=${failedReadSeedOnly.get()}",
            )
        } catch (_: Throwable) {
            // Diagnostics only.
        }
    }

    // Phase A P0: throttled BBC sticky-fallback log (same 5s throttle shape as
    // noteSeedOnlyFallback, separate gate so BBC + whitelist storms do not
    // starve each other). Every getBbc() fallback arm calls this; a broken
    // clock skips the log (no throttle bypass) like the seed-only sibling.
    private fun noteBbcFallback(cause: String) {
        val now = try {
            nowMs()
        } catch (_: Throwable) {
            Long.MIN_VALUE
        }
        if (now == Long.MIN_VALUE) return
        val last = lastBbcLogMs.get()
        if (last != Long.MIN_VALUE && now - last < FALLBACK_LOG_THROTTLE_MS) return
        if (!lastBbcLogMs.compareAndSet(last, now)) return
        try {
            onLog(
                "BBC sticky fallback ($cause): " +
                    "stickyFallback=${bbcStickyFallbackCount.get()} " +
                    "stickyExpired=${bbcStickyExpiredCount.get()} " +
                    "unknown=${bbcUnknownCount.get()}",
            )
        } catch (_: Throwable) {
            // Diagnostics only.
        }
    }

    // ---------------------------------------------------------------------
    // Trusted clock sampling (getBbc only; the whitelist get() path keeps its
    // existing clock handling untouched).
    //
    // There is NO stale fallback anywhere in this family: an unreadable clock
    // or a backwards sample returns null, and the caller MUST route through
    // failClosedUntrustedClockLocked(). That is what makes the invariant
    // "untrusted/bounded clock => fail closed" true for the WHOLE read, not
    // just the entry sample — no later sticky/TTL/freshness decision can be
    // made with a laundered `catch { now0 }` / `catch { now1 }` value.
    //
    // sampleTrustedClockLocked() is called while holding [lock] (or via the
    // synchronized wrapper). Sampling AND validating/advancing the high-water
    // in the same critical section is deliberate: two threads that sample at
    // different times but take the lock in the opposite order cannot make a
    // healthy interleaving look like clock skew.
    private fun sampleClockOrNull(): Long? = try {
        nowMs()
    } catch (_: Throwable) {
        null
    }

    private fun sampleTrustedClockLocked(): Long? {
        val sample = sampleClockOrNull() ?: return null
        val highWater = lastTrustedClockMs
        if (highWater != null && sample < highWater) return null
        lastTrustedClockMs = sample
        return sample
    }

    private fun sampleTrustedClock(): Long? = synchronized(lock) {
        sampleTrustedClockLocked()
    }

    // The ONE fail-closed route for an untrusted clock at any point in
    // getBbc(): the entry catch, a mid-read skew, or a dead completion sample.
    // Drops ONLY the sticky snapshot (option (a): cached freshness is kept, so
    // a later healthy call may still serve a genuinely valid cached value
    // within TTL — the comment on the old entry catch claiming a later call
    // "cannot resurrect it" was misleading). Counts one sticky expiry (iff a
    // sticky existed), one sticky fallback and one Unknown; emits the
    // clock-free diagnostic. No reader, version gate, valid/cached/
    // lastRefreshMs or failure-cooldown state is touched, and a single call
    // returns immediately so no arm can double-count.
    private fun failClosedUntrustedClockLocked(): BbcPolicy {
        val hadSticky = lastGoodSticky != null
        if (hadSticky) {
            lastGoodSticky = null
            try {
                bbcStickyExpiredCount.incrementAndGet()
            } catch (_: Throwable) {
            }
        }
        try {
            bbcStickyFallbackCount.incrementAndGet()
        } catch (_: Throwable) {
        }
        try {
            bbcUnknownCount.incrementAndGet()
        } catch (_: Throwable) {
        }
        // A dead clock suppresses noteBbcFallback entirely (no throttle
        // bypass), so this path uses its own CLOCK-FREE diagnostic line.
        if (hadSticky) noteUntrustedClockDiscard() else noteBbcFallback("clock-no-sticky")
        return BbcPolicy.Unknown(emptySet())
    }

    // Uniform Unknown accounting for every NON-clock fallback arm of getBbc:
    // one sticky-fallback count, one Unknown count, one throttled log with the
    // caller's existing cause. Callers no longer wrap stickyIfValidLocked() in
    // `catch { lastGoodSticky }`: a throwing helper can never return an
    // unchecked sticky — a fail-closed empty set is the only fallback.
    private fun unknownWithStickyLocked(sticky: Set<String>?, cause: String): BbcPolicy {
        try {
            bbcStickyFallbackCount.incrementAndGet()
        } catch (_: Throwable) {
        }
        try {
            bbcUnknownCount.incrementAndGet()
        } catch (_: Throwable) {
        }
        noteBbcFallback(cause)
        return BbcPolicy.Unknown(sticky ?: emptySet())
    }

    // A dead clock suppresses noteBbcFallback entirely (no throttle bypass),
    // so the untrusted-clock sticky discard gets its own CLOCK-FREE diagnostic
    // line. Counters are the primary signal on every hit. The flag is per-cache
    // instance: production has exactly one long-lived BBC cache
    // (DualHook.bbcCache), so this is once per process there while each test
    // gets an independent, deterministic log budget.
    private val untrustedClockDiscardLogged = AtomicLong(0L)
    private fun noteUntrustedClockDiscard() {
        if (!untrustedClockDiscardLogged.compareAndSet(0L, 1L)) return
        try {
            onLog(
                "BBC sticky fallback (clock-untrusted-sticky-discarded): " +
                    "stickyFallback=${bbcStickyFallbackCount.get()} " +
                    "stickyExpired=${bbcStickyExpiredCount.get()} " +
                    "unknown=${bbcUnknownCount.get()}",
            )
        } catch (_: Throwable) {
            // Diagnostics only.
        }
    }

    // Phase A P0: sticky helpers. Must be called under [lock].
    // Returns the sticky snapshot if present and within the cap; expires it
    // (fail-closed to null) past BBC_STICKY_MAX_AGE_MS with a counted +
    // throttled log so a clear can never hide indefinitely. A backwards clock
    // (now < lastGoodStickyAtMs) yields a negative age and would otherwise
    // never expire — mirror the whitelist TTL rule (:420) and treat skew as
    // expired. The 30-min cap is therefore never widened by a time jump.
    private fun stickyIfValidLocked(now: Long): Set<String>? {
        val g = lastGoodSticky ?: return null
        val age = now - lastGoodStickyAtMs
        if (age >= BBC_STICKY_MAX_AGE_MS || age < 0L) {
            lastGoodSticky = null
            try {
                bbcStickyExpiredCount.incrementAndGet()
            } catch (_: Throwable) {
            }
            // Throttled expiry log (called under lock; noteBbcFallback reads
            // the clock again — never throws, never deadlocks).
            try {
                noteBbcFallback("sticky-expired")
            } catch (_: Throwable) {
            }
            return null
        }
        return g
    }

    private fun updateStickyLocked(snapshot: Set<String>, now: Long) {
        lastGoodSticky = snapshot
        lastGoodStickyAtMs = now
    }

    // Phase A P0 Security MEDIUM: version monotonicity helpers. Must be called
    // under [lock].
    //
    // F3 (documented limitation — option (b)): the writer's version counter is
    // a bare Long with no on-disk epoch (the key format is fixed and shared
    // cross-process, so an epoch/floor cannot be persisted here without a
    // writer-side format change). When the counter wraps from MAX-1/MAX to 1,
    // [observeBbcVersionLocked] resets the high-water to 1 to admit the new
    // 1,2,3... sequence. From that point a replayed mid-range value (e.g. 500)
    // is greater than the new high-water and therefore ACCEPTED — wraparound
    // reopens the replay window until the new sequence itself passes that
    // value. This is practically unreachable in production (it needs ~9.22e18
    // committed writes on one install); see the pinning test
    // `bbcVersionWraparoundReopensReplayWindowDocumentedBbc`. Do not "fix" this
    // by storing an epoch in the version key — that changes the key format.
    private fun isBbcVersionWraparound(last: Long, v: Long): Boolean =
        last >= Long.MAX_VALUE - 1L && v == 1L

    private fun isClearedVersionAcceptableLocked(v: Long): Boolean {
        if (v < 1L) return false
        val last = lastBbcSeenVersion
        if (last < 0L) return true
        if (v > last) return true
        if (v == last) return false
        return isBbcVersionWraparound(last, v)
    }

    // R06 (P1) / F2: version gate for Good(non-empty). Deliberate rule choice:
    // reject NON-INCREASING versions (v <= last high-water) with two narrow
    // exceptions so existing fail-open-for-Good semantics are preserved:
    //   1. writer wraparound (v==1 after MAX-1/MAX) — identical to the Cleared
    //      path;
    //   2. a same-version Good when the high-water was set by a GOOD (lastBbc-
    //      ClearedVersion != last) — preserves the existing same-version Good
    //      re-acceptance after an intervening failed read. F2: this branch is
    //      now CONTENT-AWARE (see below). A same-version Cleared->Good replay
    //      IS still rejected (lastBbcClearedVersion == last), which is the
    //      revoke-then-re-attach case this gate must stop.
    // Strictly older versions (except wraparound) are always rejected.
    //
    // F2 (same-version content): when v == last and the high-water came from a
    // Good, accept ONLY an identical-content re-acceptance — the candidate set
    // must equal the last accepted good snapshot (cached, or sticky last-good
    // after invalidate/TTL), compared as the merged effective set (post
    // STATIC_SEED / 512-cap sanitize). At an equal version ANY content change —
    // including a shrink — is a stale replay and is REJECTED as Unknown: the
    // writer bumps extra_whitelist_version on every successful save/clear, so
    // a genuine policy change must carry a strictly newer version. Comparing
    // against BOTH cached and sticky keeps the legacy
    // successEmptyWithoutVersionKeepsLastGoodBbc (same-version same-content
    // re-acceptance after a failed read) working, as long as a genuine
    // snapshot exists. If BOTH are absent (e.g. the sticky cap expired after a
    // sustained failure storm), content equality cannot be proven and the
    // same-version Good is REJECTED (fail-closed) instead of being accepted
    // unconditionally. The wraparound and no-canonical fail-closed branches
    // are unchanged.
    private fun isGoodVersionAcceptableLocked(v: Long, candidate: Set<String>): Boolean {
        if (v < 1L) return false
        val last = lastBbcSeenVersion
        if (last < 0L) return true
        if (v > last) return true
        if (isBbcVersionWraparound(last, v)) return true
        if (v == last && lastBbcClearedVersion != last) {
            val canonical = cached.takeIf { it.isNotEmpty() }
                ?: lastGoodSticky?.takeIf { it.isNotEmpty() }
                ?: return false // FAIL-CLOSED: no canonical snapshot to compare against.
            // Byte-for-byte equal merged content (a re-read of the current
            // state) is the ONLY acceptable same-version case. Any content
            // change — including a shrink — needs a strictly newer version;
            // at an equal version it is a stale replay.
            return canonical == candidate
        }
        return false
    }

    private fun observeBbcVersionLocked(v: Long?) {
        if (v == null || v < 1L) return
        val last = lastBbcSeenVersion
        // On wraparound this intentionally lowers the high-water to 1 (see F3
        // note on isBbcVersionWraparound: the replay window reopens, documented
        // and pinned by test rather than fixed with a key-format change).
        if (last < 0L || v > last || isBbcVersionWraparound(last, v)) {
            lastBbcSeenVersion = v
        }
    }

    // NOTE (code-reviewer Major, kept by design): the stickyLastGood arms
    // inside get() below are currently unreachable in production (whitelist
    // instance constructs with false; the sticky BBC instance only calls
    // getBbc()). They are kept — not deleted — as defensive parity: a future
    // sticky caller of get() stays fail-sticky instead of silently regressing
    // to fail-closed, at zero runtime cost (one boolean check per arm).
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
        // nowMs() throwing fails closed to the seed (no clock, no TTL, no
        // retry): the timeout/grace arms rely on the clock, so with no clock
        // TTL cannot be enforced and the cached value must not be served
        // unbounded-stale. Mirrors the sibling broken-clock arms, which
        // degrade to seed/0.
        val now0: Long = try {
            nowMs()
        } catch (_: Throwable) {
            // H9: seed-only fallback, routed like the sibling broken-clock
            // degrades (a no-op log when the clock is dead).
            // Phase A P0: sticky instances keep last-good on clock failure
            // (no TTL/expiry check possible without a clock — serve sticky
            // as-is, never unbounded seed-only churn).
            if (stickyLastGood) {
                val sticky: Set<String>? = synchronized(lock) { lastGoodSticky }
                if (sticky != null) {
                    try {
                        bbcStickyFallbackCount.incrementAndGet()
                    } catch (_: Throwable) {
                    }
                    try {
                        bbcUnknownCount.incrementAndGet()
                    } catch (_: Throwable) {
                    }
                    noteBbcFallback("clock")
                    return sticky
                }
            }
            noteSeedOnlyFallback("clock")
            return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
        }
        val gen0: Long
        var isFollower = false
        synchronized(lock) {
            // TTL expiry: fail closed to the seed on the next read (invalidated
            // true), so a stale cached value is never resurrected after expiry.
            // A backwards clock (now0 < lastRefreshMs, e.g. a device time jump
            // between refreshes) makes the subtraction negative forever and
            // would otherwise never expire — treat skew as expired.
            if (valid && (now0 - lastRefreshMs >= maxCacheAgeMs || now0 - lastRefreshMs < 0L)) {
                valid = false
                invalidated = true
            }
            if (valid) return cached
            // During the cooldown, do not retry the reader; return the cache/seed.
            // A negative delta means the clock moved backwards since the last
            // failure record; treat as expired cooldown (fail open to retrying,
            // never toward serving a stale cache).
            val lf = lastFailureMs
            if (lf != null && now0 - lf < failureCooldownMs && now0 - lf >= 0L) {
                // Phase A P0: sticky instances ignore [invalidated] here —
                // TTL expiry / invalidate never discard BBC last-good; the
                // cooldown path serves sticky when present (even after an
                // explicit invalidation), expiring only via the sticky cap.
                if (stickyLastGood) {
                    val sticky = stickyIfValidLocked(now0)
                    if (sticky != null) {
                        try {
                            bbcStickyFallbackCount.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        try {
                            bbcUnknownCount.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        noteBbcFallback("cooldown")
                        return sticky
                    }
                    // Sticky absent/expired: fail-closed to seed (counted as
                    // BBC sticky fallback for observability, throttled log).
                    try {
                        bbcStickyFallbackCount.incrementAndGet()
                    } catch (_: Throwable) {
                    }
                    noteBbcFallback("cooldown-no-sticky")
                    return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
                }
                // After an explicit invalidation, never resurrect a stale
                // pre-invalidation cached value: fall back to the seed alone.
                if (hadGoodValue && !invalidated) return cached
                // H9: cooldown-throttled seed-only fallback (no good value
                // yet, or explicit invalidation since).
                cooldownFallbackSeedOnly.incrementAndGet()
                noteSeedOnlyFallback("cooldown")
                return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
            }
            // Stage C single-flight claim: a second concurrent miss becomes a
            // follower. Phase3: followers do NOT return seed-only immediately;
            // they take the grace path below (sleep outside the lock, then
            // re-check valid) so a just-committed leader value is observed.
            // Only the leader proceeds to the IO below.
            if (inFlightGen != null) {
                // Re-check valid under the same guard before leaving (a peer
                // may have committed between the valid check above and here).
                if (valid) return cached
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
            // Phase7: an explicit followerGraceMs is honored even when it
            // exceeds the process default (tests use longer graces to pin
            // notifyAll semantics); the clamp only enforces the lower bound 0.
            // Warning: deadlineMs is interpreted on the cache's nowMs() basis
            // (the same basis as elapsed/now1), NOT wall-clock millis — a
            // wall-clock deadlineMs silently mis-clamps the grace; callers on
            // a custom nowMs must sample deadlineMs from that same clock.
            val remainingGrace = if (deadlineMs > 0L) {
                try {
                    minOf(followerGraceMs - elapsed, deadlineMs - now1).coerceAtLeast(0L)
                } catch (_: Throwable) {
                    0L
                }
            } else {
                try {
                    (followerGraceMs - elapsed).coerceAtLeast(0L)
                } catch (_: Throwable) {
                    0L
                }
            }
            if (remainingGrace <= 0L) {
                synchronized(lock) {
                    if (valid) return cached
                    // Phase A P0: sticky followers keep last-good on
                    // grace-exhausted short-circuit (deadline already spent).
                    if (stickyLastGood) {
                        val nowG = try {
                            nowMs()
                        } catch (_: Throwable) {
                            now1
                        }
                        val sticky = stickyIfValidLocked(nowG)
                        if (sticky != null) {
                            try {
                                bbcStickyFallbackCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            try {
                                bbcUnknownCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            noteBbcFallback("grace-expired")
                            return sticky
                        }
                        try {
                            bbcStickyFallbackCount.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        noteBbcFallback("grace-expired-no-sticky")
                    }
                    // H9: grace-expired follower fell back to seed-only.
                    graceExpiredSeedOnly.incrementAndGet()
                    noteSeedOnlyFallback("grace-expired")
                    return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
                }
            }
            synchronized(lock) {
                // P1-2 (herd parking): re-sample nowMs() and recompute the
                // remaining wait budget INSIDE the parking lock — time spent
                // acquiring it (or being preempted between the entry-time
                // computation above and this point) must not overshoot the
                // follower grace or the end-to-end deadline. A zero/negative
                // budget skips waitGrace entirely (seed-only fallback), so a
                // follower can never park for its full entry-time window after
                // its deadline has already lapsed.
                val now2: Long = try {
                    nowMs()
                } catch (_: Throwable) {
                    now1
                }
                val elapsed2: Long = try {
                    (now2 - now0).coerceAtLeast(0L)
                } catch (_: Throwable) {
                    0L
                }
                val budget2: Long = try {
                    if (deadlineMs > 0L) {
                        minOf(remainingGrace, followerGraceMs - elapsed2, deadlineMs - now2)
                    } else {
                        minOf(remainingGrace, followerGraceMs - elapsed2)
                    }
                } catch (_: Throwable) {
                    0L
                }
                if (budget2 > 0L && !valid && inFlightGen != null) {
                    // TT-P3-1: count the hit only once we are parked (or about
                    // to park) inside the lock — a pre-park preemption must not
                    // yield a false hits==1 for a follower that never waits.
                    try {
                        followerGraceHits.incrementAndGet()
                    } catch (_: Throwable) {
                    }
                    try {
                        waitGrace(budget2)
                    } catch (t: Throwable) {
                        if (t is InterruptedException) {
                            try {
                                Thread.currentThread().interrupt()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                if (valid) return cached
                // H9: the grace arm fired — the follower's grace expired (it
                // either parked for its full (recomputed) budget, or never
                // parked because the budget had already lapsed / the leader
                // released before this follower re-acquired the lock) with no
                // committed value. graceExpiredSeedOnly counts this arm, not
                // proof of parking.
                // Phase A P0: sticky followers keep last-good here as well.
                if (stickyLastGood) {
                    val sticky = stickyIfValidLocked(now2)
                    if (sticky != null) {
                        try {
                            bbcStickyFallbackCount.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        try {
                            bbcUnknownCount.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        noteBbcFallback("grace-expired")
                        return sticky
                    }
                    try {
                        bbcStickyFallbackCount.incrementAndGet()
                    } catch (_: Throwable) {
                    }
                    noteBbcFallback("grace-expired-no-sticky")
                }
                graceExpiredSeedOnly.incrementAndGet()
                noteSeedOnlyFallback("grace-expired")
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
                    if (valid) return cached
                    // Phase A P0: sticky keeps last-good across invalidate-
                    // during-IO and contention (TTL/invalidate never discard
                    // BBC last-good; only the sticky cap expires it).
                    if (stickyLastGood) {
                        // Loser-drop still releases the claim, but serves
                        // sticky instead of seed-only.
                        if (gen0 != generation) {
                            val sticky = stickyIfValidLocked(now0)
                            if (sticky != null) {
                                try {
                                    bbcStickyFallbackCount.incrementAndGet()
                                } catch (_: Throwable) {
                                }
                                try {
                                    bbcUnknownCount.incrementAndGet()
                                } catch (_: Throwable) {
                                }
                                noteBbcFallback("invalidated-discard")
                                return sticky
                            }
                            try {
                                bbcStickyFallbackCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            noteBbcFallback("invalidated-discard-no-sticky")
                            // Fall through to seed-only counting below.
                        } else {
                            val sticky2 = stickyIfValidLocked(now0)
                            if (sticky2 != null) {
                                try {
                                    bbcStickyFallbackCount.incrementAndGet()
                                } catch (_: Throwable) {
                                }
                                try {
                                    bbcUnknownCount.incrementAndGet()
                                } catch (_: Throwable) {
                                }
                                noteBbcFallback("contention")
                                return sticky2
                            }
                            try {
                                bbcStickyFallbackCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            noteBbcFallback("contention-no-sticky")
                            // Fall through to seed-only counting below for
                            // H9 parity when no sticky exists.
                        }
                    }
                    // Loser-drop still applies: an invalidate during IO means
                    // seed-only (never resurrect stale).
                    if (gen0 != generation) {
                        // H9: invalidate-during-IO discard -> seed-only.
                        invalidatedDiscardSeedOnly.incrementAndGet()
                        noteSeedOnlyFallback("invalidated-discard")
                        return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
                    }
                    if (hadGoodValue && !invalidated) return cached
                    // H9: contention fallback with nothing committed yet.
                    contentionFallbackSeedOnly.incrementAndGet()
                    noteSeedOnlyFallback("contention")
                    return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
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
                    if (valid) return cached
                    // Phase A P0: sticky survives invalidate-during-IO.
                    if (stickyLastGood) {
                        val stickyL = stickyIfValidLocked(now1)
                        if (stickyL != null) {
                            try {
                                bbcStickyFallbackCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            try {
                                bbcUnknownCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            noteBbcFallback("invalidated-discard")
                            return stickyL
                        }
                        try {
                            bbcStickyFallbackCount.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        noteBbcFallback("invalidated-discard-no-sticky")
                    }
                    // H9: invalidate-during-IO discard -> seed-only.
                    invalidatedDiscardSeedOnly.incrementAndGet()
                    noteSeedOnlyFallback("invalidated-discard")
                    return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
                }
                if (valid) return cached
                if (mergeOk && merged != null) {
                    // H4: commit-time snapshot — merged is a fresh set the
                    // cache solely owns; wrap it once, share it on every
                    // hot-path return (no per-call toSet() copy). The cached
                    // view is unmodifiable, so a caller that tries to mutate
                    // the returned set fails fast instead of corrupting
                    // policy state; PolicySource.effective returns a plain
                    // LinkedHashSet, which we never hand out mutable again.
                    cached = java.util.Collections.unmodifiableSet(merged)
                    valid = true
                    hadGoodValue = true
                    invalidated = false
                    lastFailureMs = null
                    failureLogged = false
                    lastRefreshMs = now1
                    // Phase A P0: sticky tracks every success (including
                    // empty) so a later transient keeps last-good; TTL /
                    // invalidate never clear it (only the sticky cap does).
                    if (stickyLastGood) {
                        try {
                            updateStickyLocked(
                                java.util.Collections.unmodifiableSet(
                                    LinkedHashSet(merged),
                                ),
                                now1,
                            )
                        } catch (_: Throwable) {
                            try {
                                updateStickyLocked(merged, now1)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    return cached
                } else {
                    // Sliding cooldown: refresh lastFailureMs on every failure
                    // unconditionally; consecutive failures stay throttled (see
                    // cooldownThrottlesConsecutiveFailures). Success/invalidate clears
                    // it (branch above). Phase 5: unified to English.
                    lastFailureMs = now1
                    // Phase A P0: sticky failure keeps last-good (even after
                    // invalidate/TTL); only the sticky cap expires it.
                    if (stickyLastGood) {
                        val stickyF = stickyIfValidLocked(now1)
                        if (stickyF != null) {
                            try {
                                bbcStickyFallbackCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            try {
                                bbcUnknownCount.incrementAndGet()
                            } catch (_: Throwable) {
                            }
                            noteBbcFallback("failed-read")
                            return stickyF
                        }
                        try {
                            bbcStickyFallbackCount.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        noteBbcFallback("failed-read-no-sticky")
                    }
                    if (hadGoodValue && !invalidated) return cached
                    // CR-O4: H9 — failed read with nothing good to resurrect:
                    // seed-only fallback, counted like the sibling arms.
                    failedReadSeedOnly.incrementAndGet()
                    noteSeedOnlyFallback("failed-read")
                    return PolicySource.effective(seed, emptySet(), nonEmptyPolicyGateOpen)
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

    // Phase A P0 BBC version-aware sticky read.
    //
    // Reader contract: Pair(packages, version) where packages==null means a
    // failed read (null / throw / contention / deadline / clock upstream);
    // packages non-empty means Good — a null version is the UNGATED legacy
    // old-draft path (trusted only before any versioned state exists), while a
    // PRESENT version is monotonic-gated against downgrade/replay (R06 + F1/F2
    // — see isGoodVersionAcceptableLocked; equal version requires identical
    // merged content, any content change needs a strictly newer version);
    // packages empty + version!=null means Cleared (intentional clear, shrink
    // allowed and cached); packages empty + version==null means Unknown
    // (indistinguishable from "never written", never cached, sticky served).
    // Only Good(non-empty) and Cleared(empty+version) update the sticky
    // snapshot; Unknown never shrinks. TTL expiry / invalidate never discard
    // sticky (only BBC_STICKY_MAX_AGE_MS expires it, counted + throttled).
    // A backwards sample (clock skew) or an unreadable clock fails closed and
    // discards sticky: freshness is then unprovable, so the cap is not widened.
    // Gate-closed short-circuits to Cleared(-1) with zero IPC/state (BBC
    // injection disabled => empty, no Unknown log). N06(b): the gate is a LAZY
    // provider evaluated after now0 so a slow read is charged to the same
    // bounded 50ms deadline budget.
    fun getBbc(
        seed: Set<String>,
        bbcGateOpen: () -> Boolean = { PolicySource.BBC_AVAILABLE_APPS_INJECTION },
        followerGraceMs: Long = DEFAULT_FOLLOWER_GRACE_MS,
        deadlineMs: Long = -1L,
        reader: () -> Pair<Set<String>?, Long?>,
    ): BbcPolicy {
        // Entry sample goes through the SAME trusted sampler as every later
        // sample: null means the clock is dead OR skewed vs the high-water.
        // No caller ever substitutes a stale `now0` for a failed read.
        val now0 = sampleTrustedClock()
        if (now0 == null) {
            // Preserve the gate-closed invariant even when the clock sample is
            // untrusted: re-evaluate the opt-out provider (wrapped, never
            // throws) and still short-circuit to Cleared(-1) with ZERO reader
            // calls and ZERO state/counter changes. A broken provider cannot
            // mask the existing clock fallback.
            val gateClosed = try {
                !bbcGateOpen()
            } catch (_: Throwable) {
                false
            }
            if (gateClosed) return BbcPolicy.Cleared(-1L)
            return synchronized(lock) { failClosedUntrustedClockLocked() }
        }
        // N06(b): gate read happens AFTER now0/deadline sampling, inside the
        // guarded section, so a slow pref read consumes the same deadline
        // budget (visible to the follower elapsed clamp below). Gate-closed
        // still returns the exact Cleared(-1) sentinel with ZERO reader calls.
        if (!bbcGateOpen()) return BbcPolicy.Cleared(-1L)
        val gen0: Long
        var isFollower = false
        synchronized(lock) {
            if (valid && (now0 - lastRefreshMs >= maxCacheAgeMs || now0 - lastRefreshMs < 0L)) {
                valid = false
                // NOTE: sticky intentionally NOT cleared here (unlike the
                // whitelist fail-closed path which sets invalidated=true to
                // suppress resurrection). invalidated is still set for the
                // shared cooldown bookkeeping, but getBbc() fallback arms
                // prefer sticky regardless of it.
                invalidated = true
            }
            if (valid) {
                return if (cached.isEmpty()) {
                    BbcPolicy.Cleared(lastBbcClearedVersion)
                } else {
                    BbcPolicy.Good(cached)
                }
            }
            // Resample immediately before the cooldown/sticky decision: a clock
            // that died or skewed after the entry sample must fail closed here
            // rather than reuse a stale sample to serve sticky.
            val nowC = sampleTrustedClockLocked() ?: return failClosedUntrustedClockLocked()
            val lf = lastFailureMs
            if (lf != null && nowC - lf < failureCooldownMs && nowC - lf >= 0L) {
                val sticky = stickyIfValidLocked(nowC)
                return if (sticky != null) {
                    unknownWithStickyLocked(sticky, "cooldown")
                } else {
                    unknownWithStickyLocked(null, "cooldown-no-sticky")
                }
            }
            if (inFlightGen != null) {
                if (valid) {
                    return if (cached.isEmpty()) {
                        BbcPolicy.Cleared(lastBbcClearedVersion)
                    } else {
                        BbcPolicy.Good(cached)
                    }
                }
                isFollower = true
                gen0 = -1L
            } else {
                inFlightGen = generation
                gen0 = generation
            }
        }
        if (isFollower) {
            val now1 = sampleTrustedClock()
                ?: return synchronized(lock) { failClosedUntrustedClockLocked() }
            val elapsed = try {
                (now1 - now0).coerceAtLeast(0L)
            } catch (_: Throwable) {
                0L
            }
            val remainingGrace = if (deadlineMs > 0L) {
                try {
                    minOf(followerGraceMs - elapsed, deadlineMs - now1).coerceAtLeast(0L)
                } catch (_: Throwable) {
                    0L
                }
            } else {
                try {
                    (followerGraceMs - elapsed).coerceAtLeast(0L)
                } catch (_: Throwable) {
                    0L
                }
            }
            if (remainingGrace <= 0L) {
                synchronized(lock) {
                    if (valid) {
                        return if (cached.isEmpty()) {
                            BbcPolicy.Cleared(lastBbcClearedVersion)
                        } else {
                            BbcPolicy.Good(cached)
                        }
                    }
                    // Fresh trusted sample for the sticky decision (no reuse of
                    // the pre-wait/deadline sample).
                    val nowG = sampleTrustedClockLocked()
                        ?: return failClosedUntrustedClockLocked()
                    val sticky = stickyIfValidLocked(nowG)
                    return unknownWithStickyLocked(sticky, "grace-expired")
                }
            }
            synchronized(lock) {
                val now2 = sampleTrustedClockLocked()
                    ?: return failClosedUntrustedClockLocked()
                val elapsed2: Long = try {
                    (now2 - now0).coerceAtLeast(0L)
                } catch (_: Throwable) {
                    0L
                }
                val budget2: Long = try {
                    if (deadlineMs > 0L) {
                        minOf(remainingGrace, followerGraceMs - elapsed2, deadlineMs - now2)
                    } else {
                        minOf(remainingGrace, followerGraceMs - elapsed2)
                    }
                } catch (_: Throwable) {
                    0L
                }
                if (budget2 > 0L && !valid && inFlightGen != null) {
                    try {
                        followerGraceHits.incrementAndGet()
                    } catch (_: Throwable) {
                    }
                    try {
                        waitGrace(budget2)
                    } catch (t: Throwable) {
                        if (t is InterruptedException) {
                            try {
                                Thread.currentThread().interrupt()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                if (valid) {
                    return if (cached.isEmpty()) {
                        BbcPolicy.Cleared(lastBbcClearedVersion)
                    } else {
                        BbcPolicy.Good(cached)
                    }
                }
                // Resample AFTER the grace wait: now2 was taken before parking
                // and is stale for a cap/skew decision if the clock moved (or
                // died) while the follower was waiting.
                val nowAfterWait = sampleTrustedClockLocked()
                    ?: return failClosedUntrustedClockLocked()
                val sticky = stickyIfValidLocked(nowAfterWait)
                return unknownWithStickyLocked(sticky, "grace-expired")
            }
        }
        var wasContention = false
        var readPair: Pair<Set<String>?, Long?>? = null
        var readFailed = false
        try {
            readPair = reader()
        } catch (e: PolicyRefreshContentionException) {
            wasContention = true
        } catch (t: Throwable) {
            if (t is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            readFailed = true
        }
        if (wasContention) {
            synchronized(lock) {
                try {
                    if (valid) {
                        return if (cached.isEmpty()) {
                            BbcPolicy.Cleared(lastBbcClearedVersion)
                        } else {
                            BbcPolicy.Good(cached)
                        }
                    }
                    // Resample under the commit lock: the pre-read now0 is
                    // stale after a slow contended reader, and an untrusted
                    // clock here must fail closed instead of serving sticky.
                    val nowC = sampleTrustedClockLocked()
                        ?: return failClosedUntrustedClockLocked()
                    // Sticky survives contention + invalidate-during-IO.
                    val sticky = stickyIfValidLocked(nowC)
                    return unknownWithStickyLocked(
                        sticky,
                        if (gen0 != generation) "invalidated-discard" else "contention",
                    )
                } finally {
                    if (inFlightGen == gen0) {
                        inFlightGen = null
                        notifyFollowers()
                    }
                }
            }
        }
        // Classify the versioned read: null pair / null packages / throw =>
        // Unknown (failed); empty + null version => Unknown (no proof, never
        // shrink); empty + version => Cleared candidate (monotonicity checked
        // under lock: reject 0/duplicate/replay except writer wraparound to 1);
        // non-empty => Good. Only version == null is the ungated legacy draft;
        // a PRESENT version is monotonic-gated under lock (reject 0/downgrade/
        // replay; at equal version only identical merged content is accepted —
        // any content change, including shrink, needs a newer version; see
        // isGoodVersionAcceptableLocked) and, when accepted, advances the gate.
        var unknownRead = readFailed || readPair == null
        var remoteForMerge: Set<String>? = null
        var clearedVersion: Long? = null
        var goodVersion: Long? = null
        if (!unknownRead) {
            val pkgs = readPair!!.first
            val ver = readPair!!.second
            if (pkgs == null) {
                unknownRead = true
            } else if (pkgs.isEmpty() && ver == null) {
                unknownRead = true
            } else if (pkgs.isEmpty()) {
                remoteForMerge = emptySet()
                clearedVersion = ver
            } else {
                remoteForMerge = pkgs
                goodVersion = ver
            }
        }
        var merged: Set<String>? = null
        var mergeOk = false
        if (!unknownRead && remoteForMerge != null) {
            try {
                merged = PolicySource.effective(seed, remoteForMerge, true)
                mergeOk = true
            } catch (_: Throwable) {
                mergeOk = false
                unknownRead = true
            }
        }
        synchronized(lock) {
            try {
                // Completion sample: trusted or null. This is taken INSIDE the
                // commit critical section so an unreadable / skewed clock here
                // fails closed BEFORE any valid/cached/lastRefreshMs/sticky/
                // version publish from this read. The finally below still
                // releases the single-flight claim on this early return.
                val now1 = sampleTrustedClockLocked()
                    ?: return failClosedUntrustedClockLocked()
                if (gen0 != generation) {
                    if (valid) {
                        return if (cached.isEmpty()) {
                            BbcPolicy.Cleared(lastBbcClearedVersion)
                        } else {
                            BbcPolicy.Good(cached)
                        }
                    }
                    val sticky = stickyIfValidLocked(now1)
                    return unknownWithStickyLocked(sticky, "invalidated-discard")
                }
                if (valid) {
                    return if (cached.isEmpty()) {
                        BbcPolicy.Cleared(lastBbcClearedVersion)
                    } else {
                        BbcPolicy.Good(cached)
                    }
                }
                if (!unknownRead && mergeOk && merged != null) {
                    // Security MEDIUM: Cleared monotonicity gate (strictly
                    // increasing; reject 0/duplicate/replay, wraparound to 1
                    // after MAX-1/MAX only). Rejected Cleared is Unknown
                    // (sticky, never cached/shrunk), counted like failed-read.
                    if (clearedVersion != null && !isClearedVersionAcceptableLocked(clearedVersion)) {
                        lastFailureMs = now1
                        val sticky = stickyIfValidLocked(now1)
                        return unknownWithStickyLocked(sticky, "cleared-rejected")
                    }
                    // R06 (P1): BBC Good(non-empty) with a version must pass the
                    // monotonic + content gate (isGoodVersionAcceptableLocked) —
                    // previously only Cleared(empty) did, so a stale/replayed
                    // versioned Good (e.g. Good({A},v5) -> Cleared(v6) -> stale
                    // Good({A},v5)) was accepted and re-attached revoked A,
                    // persisting via sticky.
                    //
                    // F1: a non-empty read with NO version (goodVersion == null)
                    // is only trusted BEFORE any versioned state exists
                    // (lastBbcSeenVersion < 0 — the legacy old-draft path, kept
                    // working exactly as today). Once a versioned Good/Cleared
                    // has been accepted — including every versioned Cleared
                    // (lastBbcClearedVersion >= 1) — an unversioned non-empty
                    // read may be a stale pre-version draft or a corrupt/CCE
                    // version key replayed from the cached RemotePreferences
                    // map, so it MUST be rejected as Unknown(sticky) just like a
                    // bad version. Checked before any cached/sticky/high-water
                    // mutation, so rejection overwrites nothing.
                    val noVersionGoodRejected =
                        goodVersion == null && clearedVersion == null && // unversioned non-empty Good only (not a Cleared candidate)
                            (lastBbcSeenVersion >= 0L || lastBbcClearedVersion >= 1L)
                    if ((goodVersion != null && !isGoodVersionAcceptableLocked(goodVersion, merged)) ||
                        noVersionGoodRejected
                    ) {
                        lastFailureMs = now1
                        val sticky = stickyIfValidLocked(now1)
                        return unknownWithStickyLocked(
                            sticky,
                            if (goodVersion == null) "good-rejected-no-version" else "good-rejected",
                        )
                    }
                    val unmod = try {
                        java.util.Collections.unmodifiableSet(merged)
                    } catch (_: Throwable) {
                        merged
                    }
                    cached = unmod
                    valid = true
                    hadGoodValue = true
                    invalidated = false
                    lastFailureMs = null
                    failureLogged = false
                    lastRefreshMs = now1
                    if (clearedVersion != null) {
                        lastBbcClearedVersion = clearedVersion
                        lastBbcSeenVersion = clearedVersion
                        try {
                            updateStickyLocked(
                                try {
                                    java.util.Collections.unmodifiableSet(LinkedHashSet(merged))
                                } catch (_: Throwable) {
                                    merged
                                },
                                now1,
                            )
                        } catch (_: Throwable) {
                        }
                        return BbcPolicy.Cleared(clearedVersion)
                    } else {
                        // A higher-version Good takes over the high-water from
                        // any earlier Cleared. Drop the stale Cleared marker so
                        // the same-version Good gate classifies this version as
                        // Good-owned (content-aware against this Good's own
                        // snapshot) instead of comparing a replay against a
                        // canonical from the earlier Cleared version. This does
                        // NOT lower lastBbcSeenVersion: observeBbcVersionLocked
                        // only advances it (except the documented wraparound).
                        if (goodVersion != null && goodVersion > lastBbcSeenVersion) {
                            lastBbcClearedVersion = -1L
                        }
                        try {
                            observeBbcVersionLocked(goodVersion)
                        } catch (_: Throwable) {
                        }
                        try {
                            updateStickyLocked(
                                try {
                                    java.util.Collections.unmodifiableSet(LinkedHashSet(merged))
                                } catch (_: Throwable) {
                                    merged
                                },
                                now1,
                            )
                        } catch (_: Throwable) {
                        }
                        return BbcPolicy.Good(unmod)
                    }
                } else {
                    lastFailureMs = now1
                    val sticky = stickyIfValidLocked(now1)
                    return unknownWithStickyLocked(sticky, "failed-read")
                }
            } finally {
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
