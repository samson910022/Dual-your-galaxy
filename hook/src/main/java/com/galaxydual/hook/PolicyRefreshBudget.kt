// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

// Pure (framework-free) bounded-refresh helper for the hook hot path (P1-2).
//
// Problem: a cache miss ran getFrameworkProperties + getRemotePreferences +
// getStringSet synchronously on the hooked whitelist-query thread (binder
// IPC with no timeout). A slow/absent daemon would stall system_server /
// daagent query threads.
//
// Policy: run the fallible reader on a bounded executor and wait at most
// [timeoutMs]. On timeout / exception / null the caller treats the result as
// "read failed" (null) and falls back to seed / last-known-good (fail-closed
// observe-only); the slow task is cancelled best-effort and never blocks the
// query thread beyond the budget.
//
// Stall budget: [DEFAULT_STALL_BUDGET_MS] (50ms) is the END-TO-END total for
// one effectivePolicy() call (Phase7): capability probe + prefs read share
// this single budget (remaining = total - elapsed; each segment uses
// min(50, remaining), remaining <= 0 skips fail-closed to null), plus a
// budget-aware follower grace (min(50, remaining), exhausted forces 0).
// It keeps a whitelist query (a local binder query normally sub-ms to a few
// ms) from stalling on IPC. Documented in docs/HOOK.md §1.1 (end-to-end
// total); DualHook uses this for every effective-policy refresh.
// JVM-tested via injected ExecutorService (see PolicyRefreshBudgetTest);
// production passes a shared daemon executor.
// Note: tests pin the FALLBACK (timeout/exception/null -> null, caller falls
// back to seed/last-known-good), not the exact 50ms value; the constant may
// be retuned without breaking the contract as long as the fallback holds.
//
// K4 P2#6 (before-gate blocker): the old production executor was
// newSingleThreadExecutor (unbounded LinkedBlockingQueue). cancel(true) does
// NOT guarantee an uninterruptible Binder read stops, and does NOT remove a
// queued task; repeated 5s-cooldown misses behind a stuck worker grew the
// queue without bound. Fixed by: zero-queue executor (SynchronousQueue) +
// at-most-one outstanding guard (Semaphore.tryAcquire) + stuck-worker
// circuit breaker (see PolicyRefreshGate). Contention/rejection short-circuits
// to null (seed fallback) with droppedRefreshCount + throttled log.
object PolicyRefreshBudget {
    const val DEFAULT_STALL_BUDGET_MS = 50L

    // Stuck-worker circuit breaker tuning (K4 P2#6). All retunable without
    // breaking the caller contract (timeout/exception/rejection -> null).
    const val MAX_CONSECUTIVE_TIMEOUTS = 3
    const val STUCK_OPEN_MS = 30_000L
    // Half-open probe backoff (M4 P2#6 fix): bounded retry after cooldown
    // (initial 30s, x2 exponential to 5min cap, reset on success). While
    // stuck-open the gate allows ONE probe per cooldown window (single-flight
    // guard + zero queue kept) so recovery without an invalidate signal
    // (no cross-process push on API 102.0.0) is still possible.
    const val HALF_OPEN_INITIAL_MS = 30_000L
    const val HALF_OPEN_MAX_MS = 300_000L
    // Throttled drop log: at most one log per window to avoid hot-path spam.
    const val DROP_LOG_THROTTLE_MS = 5_000L

    // H5: the bounded-read logic lives in exactly ONE place per class of
    // executor: the gate (PolicyRefreshGate.readBoundedOrThrow) for the managed
    // zero-queue path, and readBoundedOrThrow below for a caller-supplied
    // ExecutorService. readBounded is a thin compatibility wrapper over
    // readBoundedOrThrow (rejection -> null) so there is no second copy of the
    // submit/get/cancel logic to drift. Broad Throwable is intentional: the
    // reader runs hostile/framework code that may throw Error (not just
    // Exception), and submit/get may throw RejectedExecutionException/
    // InterruptedException on shutdown races. All map to null (failed-read) so
    // the caller falls back to seed/last-known-good; narrowing to Exception
    // would let an Error escape onto the hooked query thread.
    // Caller contract for readBoundedOrThrow: rejection (all attempts busy)
    // throws PolicyRefreshContentionException; everything else returns null
    // (failed read). timeoutMs <= 0 never submits: fail-closed null.
    fun readBoundedOrThrow(
        reader: () -> Set<String>?,
        timeoutMs: Long = DEFAULT_STALL_BUDGET_MS,
        executor: ExecutorService,
    ): Set<String>? {
        // Zero/negative budget never submits: fail-closed null without
        // touching the executor (parity with PolicyRefreshGate 0ms guard).
        if (timeoutMs <= 0L) return null
        val future: Future<Set<String>?> = try {
            executor.submit(Callable<Set<String>?> { reader() })
        } catch (t: Throwable) {
            if (t is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            // Same rejection semantics as the gate: a busy/refused executor
            // is contention (never evidence of a stuck reader).
            throw PolicyRefreshContentionException("submit-rejected")
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            null
        } catch (t: Throwable) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            if (t is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            null
        }
    }

    // Thin wrapper: rejection -> null (legacy contract, kept for JVM tests).
    fun readBounded(
        reader: () -> Set<String>?,
        timeoutMs: Long = DEFAULT_STALL_BUDGET_MS,
        executor: ExecutorService,
    ): Set<String>? = try {
        readBoundedOrThrow(reader, timeoutMs, executor)
    } catch (_: PolicyRefreshContentionException) {
        null
    }

    // Zero-queue daemon executor for policy refresh (K4 P2#6).
    // ThreadPoolExecutor(0, 1, 60s, SynchronousQueue, daemon, AbortPolicy):
    // zero queue means a submit while the single worker is busy throws
    // RejectedExecutionException instead of queueing (bounded to <=1
    // outstanding, no growth). Daemon so it never blocks process exit;
    // 60s keep-alive lets the idle thread exit. Never shut down in
    // production (process-lifetime, like the old executor).
    fun newPolicyRefreshExecutor(threadName: String = "GalaxyDual-policy-refresh"): ThreadPoolExecutor =
        ThreadPoolExecutor(
            0,
            1,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { r -> Thread(r, threadName).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
}

// Pure end-to-end deadline arithmetic for one effectivePolicy() call
// (Phase7). Single source of truth for DualHook production path and
// EffectivePolicyDeadlineTest: remaining = total - elapsed, grace =
// remaining coerced to [0, maxGrace]. All Throwable -> fail-closed
// (0/empty), never throws. Pure (t0/now injected, no clock read inside)
// so JVM tests exercise the exact production function.
//
// H8 (sentinel discipline): t0Nanos == 0L ("NO_START") is the fail-closed
// backstop for "the caller had no usable t0 sample" (clock broke at entry).
// nowNanos == NO_START_NANOS (0L) is treated the SAME way: a broken clock
// sample handed in as `now` never unlocks a fresh full budget — remaining
// and grace both degrade to ZERO, matching the follower-grace fail-closed
// arm in EffectivePolicyCache (grace 0). A backwards clock (now < t0Nanos)
// degrades to zero budget as well, and the result is coerced into
// [0, totalMs]. The pure functions cannot carry a validity flag, so the
// sentinel is value-conflated INSIDE them by construction; callers that
// need the distinction use sampleClock() (nanos + clockOk travel together)
// and stop reading the sentinel themselves. nanoTime() stays wrapped in
// try/catch; "no start" (t0 == 0) and "no now" (now == 0) are both
// indistinguishable from a genuine epoch-0 nanoTime sample, so both are
// pinned fail-closed here.
object DeadlineBudget {
    // Fail-closed sentinel for "no valid t0 sample". Never grants budget.
    const val NO_START_NANOS: Long = 0L

    // Explicit clock sample + ok flag (H8): travels as one value so callers
    // act on clockOk instead of comparing against the raw sentinel. nanos
    // carries NO_START_NANOS iff clockOk == false.
    data class ClockSample(val nanos: Long, val clockOk: Boolean)

    fun sampleClock(): ClockSample = try {
        ClockSample(System.nanoTime(), true)
    } catch (_: Throwable) {
        ClockSample(NO_START_NANOS, false)
    }

    fun remainingMs(
        t0Nanos: Long,
        nowNanos: Long,
        totalMs: Long = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
    ): Long = try {
        if (t0Nanos == NO_START_NANOS || nowNanos == NO_START_NANOS) 0L
        else if (nowNanos < t0Nanos) 0L
        else (totalMs - (nowNanos - t0Nanos) / 1_000_000L).coerceIn(0L, totalMs)
    } catch (_: Throwable) {
        0L
    }

    fun graceAtEntry(
        t0Nanos: Long,
        nowNanos: Long,
        totalMs: Long = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
        maxGraceMs: Long = EffectivePolicyCache.DEFAULT_FOLLOWER_GRACE_MS,
    ): Long = try {
        remainingMs(t0Nanos, nowNanos, totalMs).coerceIn(0L, maxGraceMs)
    } catch (_: Throwable) {
        0L
    }

    fun nowNanos(): Long = try {
        System.nanoTime()
    } catch (_: Throwable) {
        NO_START_NANOS
    }
}

// Bounded single-flight refresh gate (K4 P2#6).
//
// Combines three defenses so an uninterruptible (interrupt-ignoring) Binder
// reader cannot grow an unbounded backlog:
//
// 1. At-most-one outstanding guard: Semaphore(1).tryAcquire() — a second
//    concurrent readBounded short-circuits to null (droppedRefreshCount++,
//    throttled log) without touching the executor.
// 2. Zero-queue executor: production executor is SynchronousQueue-based (see
//    newPolicyRefreshExecutor), so even a guard race that reaches submit gets
//    RejectedExecutionException — retried strictly within the call's own
//    timeoutMs with an H2 time-floor fail-fast (fail fast only once >=2ms
//    have elapsed since the first rejection in the call, effective floor
//    capped at timeoutMs; see submitHandoffRetry) and close tracking of
//    whether ANY submit succeeded (see submitHandoffRetry).
//    If none did, the failure is a busy-worker signal (H1): accounted as a
//    timeout for the stuck-worker breaker (consecutiveTimeouts++ via
//    onTimeout()/onProbeFail()), not as anonymous herd contention.
// 3. Stuck-worker circuit breaker: consecutive TimeoutExceptions (and now
//    busy-worker rejections) trip the
//    breaker (≥3 consecutive timeouts, or 1 timeout when last success ≥30s
//    ago (requires a prior success)). While open, submit is short-circuited to null
//    (stuckShortCircuits++) until invalidate() (explicit policy-change signal)
//    or a half-open probe succeeds (M4 P2#6 fix: no cross-process push on API
//    102.0.0 means invalidate may never arrive; without a timed retry the
//    breaker would stick open forever since success is impossible while
//    blocking). After HALF_OPEN_INITIAL_MS (30s, x2 exponential to 5min cap,
//    reset on success) the gate allows ONE bounded probe (single-flight guard
//    + zero-queue executor kept, at most one worker; probe only when the
//    previous stuck work has drained — executor idle — or a reinit/invalidate
//    made retry safe). Probe success closes the breaker + resets backoff;
//    probe fail re-opens + backoff++ (lastStuckOpenMs=now). Metrics:
//    halfOpenProbes + currentHalfOpenCooldownMs().
//
// Caller contract is unchanged: timeout / exception / rejection / contention /
// short-circuit all return null so the caller falls back to seed /
// last-known-good; timeoutMs stays retunable.
//
// Thread-safety: all counters are AtomicLong; the semaphore serializes
// submits; breaker state is volatile + atomic counters only (no lock on the
// hot path beyond the semaphore).
// Distinguishes gate contention (at-most-one guard busy, stuck-breaker
// short-circuit, zero-queue rejection) from a genuine IO failure
// (timeout / reader exception). EffectivePolicyCache catches this and returns
// seed-only WITHOUT refreshing its failureCooldown, so a herd contending on
// the single worker does not renew the 5s cooldown forever (herd permanent
// seed-only fix). All paths stay fail-closed (seed / last-known-good).
open class PolicyRefreshContentionException(message: String? = null) : RuntimeException(message)

// H1 busy-worker rejection (distinguishes "our submit attempts were busy in
// the ENTIRE retry window" — zero submits succeeded — from a one-off herd
// guard collision or a budget-exhausted handoff). A subclass of
// PolicyRefreshContentionException so the cache layer keeps the same
// fail-closed contract (contention: no failureCooldown renewal); call sites
// additionally account the streak for the stuck-worker breaker.
class PolicyRefreshBusyWorkerException(message: String? = null) : PolicyRefreshContentionException(message)

private fun tripleSaturated(v: Long): Long =
    if (v > Long.MAX_VALUE / 3L) Long.MAX_VALUE else v * 3L

class PolicyRefreshGate(
    @Volatile private var executor: ExecutorService,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxConsecutiveTimeouts: Int = PolicyRefreshBudget.MAX_CONSECUTIVE_TIMEOUTS,
    private val stuckOpenMs: Long = PolicyRefreshBudget.STUCK_OPEN_MS,
    private val halfOpenInitialMs: Long = PolicyRefreshBudget.HALF_OPEN_INITIAL_MS,
    private val halfOpenMaxMs: Long = PolicyRefreshBudget.HALF_OPEN_MAX_MS,
    private val log: (String) -> Unit = {},
) {
    companion object {
        // Epoch budget (recovery throttle): at most MAX_WORKER_REBUILDS
        // forced executor rebuilds per invalidate-epoch (invalidate() resets
        // the counter; onSuccess does not). This bounds rebuild CHURN within
        // one epoch and is a recovery throttle ONLY — it is NOT the
        // process-lifetime abandoned-worker residency bound. See
        // MAX_PROCESS_ABANDONED_WORKER_REBUILDS below and tryRebuildStuckWorker
        // for the stuck-thread residency note.
        const val MAX_WORKER_REBUILDS = 1L

        /**
         * PR#60 r6 (Finding #3): hard process-lifetime bound on abandoned
         * (interrupt-ignoring) worker rebuilds. Independent of invalidate()/
         * probe epochs. Per GATE instance; two gates per process
         * (policyRefreshGate + capabilityGate in DualHook) => at most
         * 2x this many resident stuck daemons. Never reset by invalidate()/
         * onSuccess(); while the abandoned worker remains BUSY, recovery
         * beyond the cap has no forced-replacement path and is
         * process-restart-bound (see docs note — deliberately NOT
         * implemented here). If the stuck read ever completes, the
         * half-open probe recovers normally without consuming this budget.
         *
         * The per-epoch MAX_WORKER_REBUILDS budget is a recovery THROTTLE:
         * after invalidate() it allows one fresh rebuild per epoch so a
         * post-policy-change stuck-worker episode can recover. It says
         * nothing about how many abandoned (shutdownNow-refused,
         * interrupt-ignoring) daemon threads can accumulate across epochs in
         * a long-lived system_server/daagent process. THIS constant is the
         * hard residency bound: it counts every forced executor replacement
         * of a still-busy worker, is never reset, and caps the total number
         * of such stuck daemons this gate can leave resident.
         *
         * At rebuild time an `activeCount <= 0` probe consumes NOTHING from
         * this budget — when budget remains the gate reports "drained" and
         * consumes nothing (the worker provably drained; the executor is
         * reclaimable). Only a REAL abandon (activeCount > 0 and a fresh
         * executor is created) consumes one unit.
         *
         * Beyond the cap a forced rebuild is REFUSED (checked before the
         * activeCount() probe): the caller fail-closes (drop + bounded log +
         * half-open backoff renewal) and no new worker thread is created.
         * While the abandoned worker remains BUSY there is no forced
         * replacement and the gate stays fail-closed until a process restart.
         * If the stuck read ever completes, the half-open probe recovers
         * without consuming this budget (no rebuild needed). Restart-only for
         * the still-busy case; a bounded rebind, should one ever be
         * designed, would be documented here — none is implemented, and no
         * additional threads are ever spawned to "help" past the residency
         * bound.
         */
        const val MAX_PROCESS_ABANDONED_WORKER_REBUILDS = 4L
        // H3 get-timeout floor: a future.get() that waited < this proves nothing
        // about the reader (it is a scheduling race or a handoff that ate the
        // budget, not a stuck read) and must NOT be counted as a reader timeout
        // for the stuck-worker breaker. Chosen as an ABSOLUTE 5ms rather than
        // 50% of timeoutMs: (a) it is jitter-proof across budget retunes; (b) a
        // wait shorter than one typical scheduler slice cannot evidence a stuck
        // reader on any realistic load; (c) it is only 10% of the default 50ms
        // budget so breaker semantics for the default path are unchanged.
        // Consequence, accepted: budgets below 5ms can never accrue a
        // reader-timeout streak (every timeout is by definition thin there).
        const val GET_TIMEOUT_FLOOR_MS = 5L
        // H2 fail-fast time floor: a healthy serial-reuse handoff succeeds
        // within a few 20µs rounds (the retry exists for a ~1% race window),
        // so rejections that keep occurring across a real-time window wider
        // than the scheduler can stretch one round to are conclusive evidence
        // of a busy worker. We fail fast only once MIN_ELAPSED_BEFORE_FAILFAST_MS
        // have elapsed since the FIRST rejection in the call AND rejections
        // keep occurring, instead of after a fixed rejection count: a count is
        // machine-speed-dependent (20µs parks stretch arbitrarily on a loaded
        // box), while a wall-clock floor is not. Chosen as 2ms (not 1ms): 1ms
        // is within one scheduler slice of the few-20µs/100µs healthy-race
        // envelope on a loaded CI box, so a healthy retry could trip the floor
        // spuriously; 2ms sits an order of magnitude past the healthy race
        // (~120µs) yet is only 4% of the default 50ms budget. The effective
        // floor is capped at timeoutMs (min(timeoutMs, floor)) so tiny budgets
        // are unchanged and the deadlineNanos re-check in the loop stays as
        // the backstop.
        const val MIN_ELAPSED_BEFORE_FAILFAST_MS = 2L
    }

    private val outstanding = Semaphore(1)
    private val rebuildLock = Any()

    val droppedRefreshCount = AtomicLong(0L)
    val stuckShortCircuits = AtomicLong(0L)
    val consecutiveTimeouts = AtomicLong(0L)
    // M4 P2#6 half-open metrics: cumulative bounded probes attempted while
    // stuck-open (cooldown elapsed + drained). Cooldown itself via
    // halfOpenCooldownMs() (exponential, reset on success/invalidate).
    val halfOpenProbes = AtomicLong(0L)
    // Phase3: stuck-busy worker rebuild metrics. workerRebuilds counts forced
    // executor replacements after a busy worker outlived k*cooldown or
    // HALF_OPEN_MAX_MS (EPOCH budget: invalidate() resets it; onSuccess does
    // not). abandonedWorkerRebuilds (PR#60 r6, Finding #3) is the
    // process-lifetime HARD residency bound: every forced replacement of a
    // still-busy (interrupt-ignoring) worker increments it, and it is never
    // reset — not by invalidate(), not by onSuccess(). busyStuckDrops counts
    // busy short-circuits below the rebuild threshold.
    val workerRebuilds = AtomicLong(0L)
    val abandonedWorkerRebuilds = AtomicLong(0L)
    val busyStuckDrops = AtomicLong(0L)
    // PR#60 r6 (Finding #3): forensics only — how many times a forced rebuild
    // was REFUSED because the process-lifetime abandoned-worker budget was
    // exhausted. Never reset. One AtomicLong increment per denial (cheap).
    internal val processCapDenials = AtomicLong(0L)
    private val halfOpenFailures = AtomicLong(0L)
    private val lastStuckOpenMs = AtomicLong(Long.MIN_VALUE)

    @Volatile
    private var lastSuccessMs: Long? = null

    @Volatile
    private var stuckOpen = false

    @Volatile
    private var lastDropLogMs: Long = Long.MIN_VALUE

    fun isStuck(): Boolean = stuckOpen

    // Queue depth for tests: 0 for SynchronousQueue executors (zero queue);
    // bounded (<=1) for any executor. Returns -1 when the executor does not
    // expose a queue (custom ExecutorService).
    fun queueDepth(): Int = try {
        val e = executor
        if (e is ThreadPoolExecutor) e.queue.size else -1
    } catch (_: Throwable) {
        -1
    }

    fun activeCount(): Int = try {
        val e = executor
        if (e is ThreadPoolExecutor) e.activeCount else -1
    } catch (_: Throwable) {
        -1
    }

    fun outstandingPermitsAvailable(): Int = try {
        outstanding.availablePermits()
    } catch (_: Throwable) {
        -1
    }

    // Current half-open cooldown (exponential: initial x2^failures to cap).
    // Exposed for tests/metrics; retunable via constructor.
    fun halfOpenCooldownMs(): Long {
        val f = halfOpenFailures.get().coerceAtLeast(0L).coerceAtMost(10L)
        var c = halfOpenInitialMs.coerceAtLeast(0L)
        repeat(f.toInt()) {
            if (c >= halfOpenMaxMs) return halfOpenMaxMs
            c = (c * 2L).coerceAtMost(halfOpenMaxMs)
        }
        return c.coerceAtMost(halfOpenMaxMs)
    }

    fun halfOpenFailureCount(): Long = halfOpenFailures.get()

    fun lastStuckOpenMs(): Long = lastStuckOpenMs.get()

    // Test seam: current executor identity (rebuild swaps this reference).
    fun currentExecutorForTest(): ExecutorService = executor

    // Previous stuck work drained? Only a ThreadPoolExecutor exposes liveness;
    // custom executors are treated as idle (probe allowed — guard + zero queue
    // still bound it to at most one worker). A busy worker means the prior
    // uninterruptible Binder read is still occupying the single thread, so a
    // probe would only hit RejectedExecutionException — keep short-circuiting
    // unless the busy age exceeds the rebuild threshold (see below).
    // Custom ExecutorService (non-ThreadPoolExecutor): queueDepth/activeCount
    // return -1 and busy is always false (fail-closed probe allowed).
    private fun isExecutorBusy(): Boolean = try {
        val e = executor as? ThreadPoolExecutor ?: return false
        e.activeCount > 0
    } catch (_: Throwable) {
        false
    }

    fun workerRebuildCount(): Long = workerRebuilds.get()

    // JVM-test visible (mirrors workerRebuildCount()): process-lifetime
    // count of forced rebuilds that left an abandoned (interrupt-ignoring)
    // worker resident. Never reset.
    fun abandonedWorkerRebuildCount(): Long = abandonedWorkerRebuilds.get()

    fun busyStuckDropCount(): Long = busyStuckDrops.get()

    // Bounded busy threshold (shared by readBounded/readBoundedOrThrow stuck
    // paths — keep both call sites in sync): 3*cooldown capped at
    // HALF_OPEN_MAX_MS. The old minOf(rebuildAfter, HALF_OPEN_MAX_MS) was
    // redundant (rebuildAfter is already capped), simplified here.
    private fun rebuildThresholdMs(cooldown: Long): Long =
        tripleSaturated(cooldown).coerceAtMost(halfOpenMaxMs).coerceAtLeast(0L)

    // Forced worker rebuild (Phase3 breaker self-lock fix, bounded leak):
    // a single-thread executor stuck on an uninterruptible Binder read never
    // drains, so a stuck-open breaker with a permanently busy worker would
    // short-circuit forever (observe-only lock-in). When the busy age exceeds
    // rebuildThresholdMs(cooldown) (3*cooldown capped at HALF_OPEN_MAX_MS),
    // shutdown the stuck worker best-effort and atomically replace the gate's
    // executor reference with a fresh zero-queue single-thread executor.
    // Custom executors cannot be rebuilt (unknown factory) — counted/logged
    // only. Caller must hold the outstanding guard.
    //
    // Leak bound is TWO independent bounds (PR#60 r6, Finding #3):
    // (1) EPOCH budget MAX_WORKER_REBUILDS per invalidate-epoch — a recovery
    //     throttle (invalidate() resets the counter; onSuccess does not), not
    //     a residency bound;
    // (2) PROCESS budget MAX_PROCESS_ABANDONED_WORKER_REBUILDS per gate
    //     instance — the hard residency bound (never reset by invalidate()/
    //     onSuccess()). Two gates per process (policyRefreshGate +
    //     capabilityGate) => at most 2 * MAX_PROCESS_ABANDONED_WORKER_REBUILDS
    //     resident stuck daemons. The old interrupt-ignoring worker thread is
    //     a daemon that never exits, so it stays resident permanently once
    //     abandoned. Past the process cap the gate only short-circuits +
    //     counts (fail-closed drop + bounded log + backoff renewal; no new
    //     threads). While the abandoned worker remains BUSY there is no
    //     recovery beyond the cap short of a process restart — documented,
    //     not implemented; a completed read recovers via the half-open
    //     probe without consuming the budget.
    // Fresh thread names carry the rebuild count suffix for lspd forensics.
    private fun tryRebuildStuckWorker(): Boolean {
        synchronized(rebuildLock) {
            // Re-check under rebuild lock: a peer may have rebuilt or the
            // worker may have drained while we acquired the guard.
            try {
                if (workerRebuilds.get() >= MAX_WORKER_REBUILDS) return false
                val cur = executor as? ThreadPoolExecutor ?: return false
                // PR#60 r6 (Finding #3): hard process-lifetime residency
                // bound, checked BEFORE the activeCount() probe so a refusal
                // is never misread as a drained worker. This consumes no
                // budget by itself (only the real-abandon path below
                // increments abandonedWorkerRebuilds). When budget remains,
                // a drained worker (activeCount <= 0) still returns "true"
                // below and consumes nothing; when the cap is exhausted the
                // refusal fires here, fail-closed, even if the worker just
                // drained (only the still-busy case is restart-bound; a
                // completed read recovers via the half-open probe without
                // consuming this budget).
                if (abandonedWorkerRebuilds.get() >= MAX_PROCESS_ABANDONED_WORKER_REBUILDS) {
                    val denial = processCapDenials.incrementAndGet()
                    if (denial <= 4L) {
                        // Bounded forensics: at most 4 denial lines per gate
                        // instance per process (≤8 per process with
                        // policyRefreshGate + capabilityGate).
                        throttledLog(
                            "policy refresh worker rebuild refused: process-lifetime abandoned-worker " +
                                "budget exhausted (${abandonedWorkerRebuilds.get()}/$MAX_PROCESS_ABANDONED_WORKER_REBUILDS)",
                            force = true,
                        )
                    }
                    return false
                }
                if (cur.activeCount <= 0) return true
                try {
                    cur.shutdownNow()
                } catch (_: Throwable) {
                }
                val n = workerRebuilds.get() + 1L
                val fresh = try {
                    PolicyRefreshBudget.newPolicyRefreshExecutor(
                        "GalaxyDual-policy-refresh-rebuilt-$n",
                    )
                } catch (_: Throwable) {
                    return false
                }
                executor = fresh
                workerRebuilds.incrementAndGet()
                abandonedWorkerRebuilds.incrementAndGet()
                throttledLog(
                    "policy refresh worker rebuilt after stuck busy " +
                        "(rebuild $n/$MAX_WORKER_REBUILDS, fail-closed probe continues)",
                    force = true,
                )
                return true
            } catch (_: Throwable) {
                return false
            }
        }
    }

    // Contention-signalling thin wrapper (H5): identical stall/breaker
    // semantics to readBoundedOrThrow below, expressed as
    // null-on-contention for the legacy call sites and JVM tests. Rejection
    // mapping lives in exactly one place (readBoundedOrThrow).
    fun readBounded(
        reader: () -> Set<String>?,
        timeoutMs: Long = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
    ): Set<String>? = try {
        readBoundedOrThrow(reader, timeoutMs)
    } catch (_: PolicyRefreshContentionException) {
        null
    }

    // Contention-signalling variant (Phase3 herd fix): gate contention (guard
    // busy, stuck short-circuit, zero-queue rejection) throws
    // PolicyRefreshContentionException instead of returning null, so
    // EffectivePolicyCache can return seed-only WITHOUT refreshing its
    // failureCooldown. Genuine IO failures (timeout / reader Error/Exception /
    // interrupt) still return null (failed read → cache cooldown applies).
    // Fail-closed in all cases (null or Contention both map to seed fallback
    // in the cache layer). readBounded above is a thin wrapper over this;
    // the stuck-busy decision is shared via rebuildThresholdMs.
    // H1: a zero-submit-success retry window (the worker was busy the whole
    // time) throws PolicyRefreshBusyWorkerException (a Contention subtype)
    // after accounting the streak via onTimeout()/onProbeFail() so the
    // stuck-worker breaker can open — the old code mapped it silently to the
    // same plain Contention as herd guard contention, so consecutiveTimeouts
    // pinned below MAX_CONSECUTIVE_TIMEOUTS and the breaker never opened.
    fun readBoundedOrThrow(
        reader: () -> Set<String>?,
        timeoutMs: Long = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
    ): Set<String>? {
        if (timeoutMs <= 0L) return null
        if (stuckOpen) {
            val now = try {
                nowMs()
            } catch (_: Throwable) {
                stuckShortCircuits.incrementAndGet()
                droppedRefreshCount.incrementAndGet()
                throw PolicyRefreshContentionException("clock")
            }
            val last = lastStuckOpenMs.get()
            val cooldown = halfOpenCooldownMs()
            if (last == Long.MIN_VALUE || now - last < cooldown) {
                stuckShortCircuits.incrementAndGet()
                droppedRefreshCount.incrementAndGet()
                throttledLog("policy refresh stuck short-circuit (timeouts=${consecutiveTimeouts.get()})")
                throw PolicyRefreshContentionException("stuck")
            }
            if (isExecutorBusy()) {
                val age = now - last
                // Shared stuck-busy decision (see rebuildThresholdMs): only
                // past 3*cooldown (capped at HALF_OPEN_MAX_MS) forces a
                // rebuild; below that the caller short-circuits.
                val forceAt = rebuildThresholdMs(cooldown)
                if (age < forceAt) {
                    busyStuckDrops.incrementAndGet()
                    stuckShortCircuits.incrementAndGet()
                    droppedRefreshCount.incrementAndGet()
                    throttledLog("policy refresh stuck short-circuit (timeouts=${consecutiveTimeouts.get()})")
                    throw PolicyRefreshContentionException("busy")
                }
                if (!outstanding.tryAcquire()) {
                    // Same metric as its sibling busy arm above: the refusal
                    // is a stuck-busy drop, not a plain guard contention.
                    busyStuckDrops.incrementAndGet()
                    stuckShortCircuits.incrementAndGet()
                    droppedRefreshCount.incrementAndGet()
                    throttledLog("policy refresh stuck short-circuit (timeouts=${consecutiveTimeouts.get()})")
                    throw PolicyRefreshContentionException("guard")
                }
                try {
                    if (!stuckOpen) {
                        return doSubmitOrThrow(reader, timeoutMs, isProbe = false)
                    }
                    if (!isExecutorBusy()) {
                        halfOpenProbes.incrementAndGet()
                        return doProbeSubmitOrThrow(reader, timeoutMs, now)
                    }
                    val rebuilt = tryRebuildStuckWorker()
                    if (!rebuilt) {
                        busyStuckDrops.incrementAndGet()
                        stuckShortCircuits.incrementAndGet()
                        droppedRefreshCount.incrementAndGet()
                        // H7: renew the half-open backoff like a probe failure
                        // (lastStuckOpenMs=now, halfOpenFailures++, streak++) so
                        // eligible queries do not re-enter
                        // synchronized(rebuildLock) on every query without
                        // cooldown. Previously no backoff was renewed here, so
                        // rebuild attempts were unbounded.
                        onProbeFail(now)
                        throw PolicyRefreshBusyWorkerException("rebuild-skipped")
                    }
                    halfOpenProbes.incrementAndGet()
                    return doProbeSubmitOrThrow(reader, timeoutMs, now)
                } finally {
                    try {
                        outstanding.release()
                    } catch (_: Throwable) {
                    }
                }
            }
            if (!outstanding.tryAcquire()) {
                stuckShortCircuits.incrementAndGet()
                droppedRefreshCount.incrementAndGet()
                throttledLog("policy refresh stuck short-circuit (timeouts=${consecutiveTimeouts.get()})")
                throw PolicyRefreshContentionException("guard")
            }
            try {
                if (!stuckOpen) {
                    return doSubmitOrThrow(reader, timeoutMs, isProbe = false)
                }
                halfOpenProbes.incrementAndGet()
                return doProbeSubmitOrThrow(reader, timeoutMs, now)
            } finally {
                try {
                    outstanding.release()
                } catch (_: Throwable) {
                }
            }
        }
        if (!outstanding.tryAcquire()) {
            droppedRefreshCount.incrementAndGet()
            throttledLog("policy refresh dropped: outstanding in flight")
            throw PolicyRefreshContentionException("guard")
        }
        try {
            if (stuckOpen) {
                stuckShortCircuits.incrementAndGet()
                droppedRefreshCount.incrementAndGet()
                throttledLog("policy refresh stuck short-circuit (timeouts=${consecutiveTimeouts.get()})")
                throw PolicyRefreshContentionException("stuck")
            }
            return doSubmitOrThrow(reader, timeoutMs, isProbe = false)
        } finally {
            try {
                outstanding.release()
            } catch (_: Throwable) {
            }
        }
    }

    // Shared submit body for the normal (non-half-open) path. Caller holds the
    // at-most-one guard. isProbe is always false here; the half-open probe has
    // its own body (doProbeSubmitOrThrow) so probe outcomes update backoff.
    // Handoff result for the deadline-bounded SynchronousQueue retry below:
    // success carries the future + the time left for get(); failure carries
    // the cause for the caller's drop/contention accounting (never a timeout:
    // a consumed handoff budget is contention, not a stuck-reader signal).
    // submitSucceeded tracks whether ANY submit in the retry window returned
    // a future: false means every attempt was rejected (busy worker across
    // the whole window), which call sites account as stuck-worker breaker
    // input (H1); true (e.g. handoff-budget-exhausted) is never breaker
    // input. A healthy serial-reuse race virtually always succeeds within a
    // few 20µs rounds, so zero-success is never a misclassified race.
    private data class HandoffResult(
        val future: Future<Set<String>?>?,
        val failure: Throwable?,
        val remainingMs: Long,
        val submitSucceeded: Boolean,
    )

    // Deadline-bounded non-queueing handoff retry (serial-reuse race fix).
    //
    // Root cause: ThreadPoolExecutor(core=0, max=1, SynchronousQueue,
    // AbortPolicy) signals Future completion (FutureTask.set wakes the
    // getter) BEFORE the worker parks back on the SynchronousQueue handoff.
    // An immediate second submit — e.g. the post-invalidate reload right
    // after a successful loadOnce — can therefore hit
    // RejectedExecutionException even though no work is running (~1% of
    // tight back-to-back gate submits; ~28% of raw executor submits). The
    // gate mapped that spurious rejection to contention/drop, so the second
    // loadOnce returned seed-only [] instead of the truth.
    //
    // Fix: retry the HANDOFF (submit only, never queueing — the queue stays
    // SynchronousQueue, zero storage) strictly within the call's own
    // timeoutMs: handoff + get <= timeoutMs, so the end-to-end deadline is
    // preserved and a genuinely stuck worker still fails inside the budget
    // (same as a normal read timeout). No blind loops (every iteration
    // re-checks a real-time nanoTime deadline), no fixed sleeps (only a 20us
    // cooperative park between attempts; total wait never exceeds
    // timeoutMs), no extra threads (the at-most-one guard is still held, one
    // worker max). Only RejectedExecutionException retries; any other submit
    // failure fails fast. Uses raw System.nanoTime — never the injectable
    // nowMs breaker clock (frozen test clocks must not govern a real-time
    // handoff race, or the loop could never terminate).
    // H2 fail-fast time floor (replaces the old fixed 6-rejection bound): a
    // healthy serial-reuse handoff succeeds within a few 20µs rounds (the
    // retry exists for a ~1% race window), so rejections that keep occurring
    // across >= MIN_ELAPSED_BEFORE_FAILFAST_MS of real time since the first
    // rejection in the call are conclusive evidence of a busy worker. Fail
    // fast instead of parking until the deadline; the effective floor is
    // capped at timeoutMs so tiny budgets behave as before, and total
    // per-call deadline stays <= timeoutMs (the loop's deadline re-check
    // remains as the backstop). See companion object for the value rationale.

    private fun submitHandoffRetry(
        reader: () -> Set<String>?,
        timeoutMs: Long,
    ): HandoffResult {
        if (timeoutMs <= 0L) return HandoffResult(null, null, 0L, submitSucceeded = false)
        val startNanos: Long = try {
            System.nanoTime()
        } catch (_: Throwable) {
            // Clock broken: single attempt, no retry (fail-closed).
            return try {
                HandoffResult(executor.submit(Callable<Set<String>?> { reader() }), null, timeoutMs, submitSucceeded = true)
            } catch (t: Throwable) {
                if (t is InterruptedException) try {
                    Thread.currentThread().interrupt()
                } catch (_: Throwable) {
                }
                HandoffResult(null, t, 0L, submitSucceeded = false)
            }
        }
        val cappedMs = timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L - 1L) // keep start+add strictly < Long.MAX_VALUE
        val deadlineNanos: Long = try {
            val add = cappedMs * 1_000_000L
            if (startNanos > Long.MAX_VALUE - add) Long.MAX_VALUE else startNanos + add
        } catch (_: Throwable) {
            Long.MAX_VALUE
        }
        var submitSucceeded = false
        var firstRejectionNanos = -1L
        // Effective floor for this call: capped at timeoutMs so a tiny budget
        // is unchanged (min(timeoutMs, FLOOR)); the deadlineNanos re-check
        // below stays as the backstop.
        val failFastFloorNanos: Long =
            timeoutMs.coerceAtMost(MIN_ELAPSED_BEFORE_FAILFAST_MS) * 1_000_000L
        while (true) {
            // Fail fast on interrupt: parkNanos returns immediately on interrupt
            // without throwing, so check explicitly to avoid spinning to deadline.
            if (try { Thread.currentThread().isInterrupted } catch (_: Throwable) { false }) {
                try { Thread.currentThread().interrupt() } catch (_: Throwable) { }
                return HandoffResult(null, InterruptedException("handoff-interrupted"), 0L, submitSucceeded)
            }
            try {
                val f = executor.submit(Callable<Set<String>?> { reader() })
                submitSucceeded = true
                val remainingMs: Long = try {
                    (deadlineNanos - System.nanoTime()) / 1_000_000L
                } catch (_: Throwable) {
                    timeoutMs
                }
                if (remainingMs <= 0L) {
                    // Handoff consumed the whole budget: no time left for get.
                    // Contention (not a reader timeout): cancel best-effort.
                    try {
                        f.cancel(true)
                    } catch (_: Throwable) {
                    }
                    return HandoffResult(
                        null,
                        RejectedExecutionException("handoff-budget-exhausted"),
                        0L,
                        submitSucceeded = true,
                    )
                }
                return HandoffResult(f, null, remainingMs, submitSucceeded = true)
            } catch (re: RejectedExecutionException) {
                // H1/H2: every attempt has rejected so far. Fail fast once the
                // real-time floor has elapsed since the first rejection in this
                // call (consecutive rejections keep occurring — the catch arm is
                // only reached via another rejection) instead of burning the
                // whole budget. (The deadline check below would catch it too,
                // just later — and backstops tiny capped budgets.)
                val nowN: Long = try {
                    System.nanoTime()
                } catch (_: Throwable) {
                    return HandoffResult(null, re, 0L, submitSucceeded)
                }
                if (firstRejectionNanos < 0L) firstRejectionNanos = nowN
                if (nowN - firstRejectionNanos >= failFastFloorNanos) {
                    return HandoffResult(null, re, 0L, submitSucceeded)
                }
                if (nowN >= deadlineNanos) return HandoffResult(null, re, 0L, submitSucceeded)
                try {
                    LockSupport.parkNanos(20_000L)
                } catch (_: Throwable) {
                }
                continue
            } catch (t: Throwable) {
                if (t is InterruptedException) try {
                    Thread.currentThread().interrupt()
                } catch (_: Throwable) {
                }
                return HandoffResult(null, t, 0L, submitSucceeded)
            }
        }
    }

    private fun doSubmitOrThrow(
        reader: () -> Set<String>?,
        timeoutMs: Long,
        isProbe: Boolean,
    ): Set<String>? {
        if (timeoutMs <= 0L) return null
        @Suppress("UNUSED_PARAMETER")
        val _probe = isProbe
        val handoff = submitHandoffRetry(reader, timeoutMs)
        val future = handoff.future
        if (future == null) {
            val cause = handoff.failure
            // H1: every handoff attempt in the retry window was rejected — the
            // worker was busy for the whole window. Account it with timeout
            // semantics (onTimeout(): streak++ and, at MAX_CONSECUTIVE_TIMEOUTS
            // or the stale-success arm, trip the stuck-worker breaker) and
            // surface the distinguishable BusyWorker subtype. This is neither
            // herd guard contention (that path throws before any submit) nor
            // budget-exhaustion (submitSucceeded=true), so a healthy
            // serial-reuse race is never misclassified. Drop accounting is
            // done here (exactly one), not in onTimeout, so it holds even when
            // the breaker is already open (O5).
            if (cause is RejectedExecutionException && !handoff.submitSucceeded) {
                throttledLog("policy refresh busy worker (submit rejected across retry window)")
                // O5: count one drop unconditionally (matching the probe path),
                // independent of trip state — the breaker may already be open,
                // in which case onTimeout's !trip arm never runs. onTimeout
                // suppresses its own drop accounting for this path so the call
                // counts exactly one drop.
                droppedRefreshCount.incrementAndGet()
                onTimeout(accountDrop = false)
                throw PolicyRefreshBusyWorkerException("busy-worker")
            }
            droppedRefreshCount.incrementAndGet()
            // A SynchronousQueue rejection outside the retry window, or a race
            // that settled during handoff: contention (not a failed read), so
            // never a cooldown-renewing IO failure.
            if (cause is RejectedExecutionException) {
                throttledLog("policy refresh rejected: ${HookLog.shortEx(cause)}")
                throw PolicyRefreshContentionException("budget-exhausted")
            }
            if (cause is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            // Non-rejection submit failure (clock/SecurityException/etc.):
            // contention for the cache layer, not a stuck-worker signal
            // (no submit capacity existed to begin with) and never a
            // cooldown-renewing IO failure.
            throw PolicyRefreshContentionException("submit-failed")
        }
        return try {
            val v = future.get(handoff.remainingMs, TimeUnit.MILLISECONDS)
            onSuccess()
            v
        } catch (_: TimeoutException) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            // H3: only a wait >= the floor is a reader timeout for the
            // breaker. A thin remaining budget means the handoff ate the
            // call's budget and the timeout is a scheduling race — pure
            // contention, never breaker input.
            if (handoff.remainingMs >= GET_TIMEOUT_FLOOR_MS) {
                onTimeout()
                return null
            }
            droppedRefreshCount.incrementAndGet()
            throttledLog(
                "policy refresh get-timeout below floor " +
                    "(remainingMs=${handoff.remainingMs}), treated as busy-contention",
            )
            throw PolicyRefreshContentionException("thin-get")
        } catch (t: Throwable) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            if (t is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            null
        }
    }

    private fun doProbeSubmitOrThrow(
        reader: () -> Set<String>?,
        timeoutMs: Long,
        now: Long,
    ): Set<String>? {
        if (timeoutMs <= 0L) return null
        val handoff = submitHandoffRetry(reader, timeoutMs)
        val future = handoff.future
        if (future == null) {
            val cause = handoff.failure
            // H1: mirror the normal path — zero-success in the retry window
            // means the worker was busy throughout; a probe that proves
            // nothing increments consecutiveTimeouts and backs off again
            // (onProbeFail: streak++, halfOpenFailures++, lastStuckOpenMs).
            if (cause is RejectedExecutionException && !handoff.submitSucceeded) {
                droppedRefreshCount.incrementAndGet()
                throttledLog(
                    "policy refresh half-open probe rejected across retry window " +
                        "(busy worker): ${HookLog.shortEx(cause)}",
                )
                onProbeFail(now)
                throw PolicyRefreshBusyWorkerException("probe-busy-worker")
            }
            // Budget-exhausted handoff: the probe consumed its own budget, so
            // nothing was learned about the worker. Contention, but NOT a
            // stuck-worker signal — no backoff renewal (H3 spirit).
            if (cause is RejectedExecutionException) {
                droppedRefreshCount.incrementAndGet()
                throttledLog(
                    "policy refresh half-open probe budget-exhausted: ${HookLog.shortEx(cause)}",
                )
                throw PolicyRefreshContentionException("probe-budget-exhausted")
            }
            droppedRefreshCount.incrementAndGet()
            throttledLog("policy refresh half-open probe rejected: ${HookLog.shortEx(cause ?: RejectedExecutionException("rejected"))}")
            onProbeFail(now)
            if (cause is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            // Keep the historical probe contract: any other failure to obtain a
            // value means "not recovered" (stays open + backs off).
            throw PolicyRefreshContentionException("probe-submit-failed")
        }
        return try {
            val v = future.get(handoff.remainingMs, TimeUnit.MILLISECONDS)
            onProbeSuccess()
            v
        } catch (_: TimeoutException) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            // H3: a wait below the floor proves nothing about the reader —
            // not a probe failure; no streak, no backoff renewal. A wait at
            // or above the floor IS breaker input (probe fails, backs off);
            // count the drop here like every sibling probe arm (submit
            // rejected, budget-exhausted, below-floor thin-get, quick
            // failure) so droppedRefreshCount stays an accurate count of
            // lost refreshes.
            if (handoff.remainingMs >= GET_TIMEOUT_FLOOR_MS) {
                droppedRefreshCount.incrementAndGet()
                onProbeFail(now)
                return null
            }
            droppedRefreshCount.incrementAndGet()
            throttledLog(
                "policy refresh half-open probe get-timeout below floor " +
                    "(remainingMs=${handoff.remainingMs}), treated as busy-contention",
            )
            throw PolicyRefreshContentionException("thin-get")
        } catch (t: Throwable) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            if (t is InterruptedException) try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
            // Any quick failure still proves nothing about recovery: stay
            // open + back off (only a returned value, even null, closes).
            // Count the drop here like every sibling probe arm (submit
            // rejected, budget-exhausted, thin-get, get-timeout); this arm
            // owns exactly one drop, so droppedRefreshCount stays an
            // accurate count of lost refreshes.
            droppedRefreshCount.incrementAndGet()
            onProbeFail(now)
            null
        }
    }

    // Explicit policy-change signal (DualHook listener / cache invalidate):
    // clears the breaker so the next miss retries immediately (recovery path
    // pinned by tests). Does NOT touch the executor (a stuck worker thread
    // still drains on its own; the zero-queue + guard bounds it to <=1).
    // Also resets half-open backoff so the next failure starts from the
    // initial cooldown again.
    // H6: resets the EPOCH budget ONLY (workerRebuilds -> 0) — an
    // operator/policy change is the explicit "start fresh" signal for the
    // recovery throttle, so a post-invalidate stuck-worker episode may
    // rebuild once more. The PROCESS budget (abandonedWorkerRebuilds /
    // processCapDenials) is deliberately NOT touched: it is the hard
    // residency bound (see MAX_PROCESS_ABANDONED_WORKER_REBUILDS), and
    // invalidate() must not resurrect abandoned-worker capacity.
    // onSuccess() also deliberately does NOT reset the EPOCH counter (that
    // would defeat MAX_WORKER_REBUILDS as a bounded leak within each
    // invalidate-epoch: success+re-stuck cycles would then rebuild
    // forever). Neither path ever resets the PROCESS counter.
    fun invalidate() {
        consecutiveTimeouts.set(0L)
        stuckOpen = false
        halfOpenFailures.set(0L)
        lastStuckOpenMs.set(Long.MIN_VALUE)
        workerRebuilds.set(0L)
        // NOTE (PR#60 r6, Finding #3): abandonedWorkerRebuilds and
        // processCapDenials are intentionally absent here — they are
        // process-lifetime and never reset.
    }

    private fun onSuccess() {
        consecutiveTimeouts.set(0L)
        stuckOpen = false
        halfOpenFailures.set(0L)
        try {
            lastSuccessMs = nowMs()
        } catch (_: Throwable) {
        }
    }

    private fun onProbeSuccess() {
        // A returned value (even null) proves the worker is responsive again:
        // close the breaker and reset both streak and backoff.
        onSuccess()
    }

    private fun onProbeFail(now: Long) {
        consecutiveTimeouts.incrementAndGet()
        halfOpenFailures.incrementAndGet()
        lastStuckOpenMs.set(now)
        stuckOpen = true
    }

    private fun onTimeout(accountDrop: Boolean = true) {
        val n = consecutiveTimeouts.incrementAndGet()
        var trip = n >= maxConsecutiveTimeouts
        if (!trip) {
            // Time-since-last-success arm: even a single timeout trips when
            // the last success is >= stuckOpenMs ago (requires a prior
            // success; stale worker).
            try {
                val last = lastSuccessMs
                if (last != null && nowMs() - last >= stuckOpenMs) trip = true
            } catch (_: Throwable) {
            }
        }
        if (trip && !stuckOpen) {
            stuckOpen = true
            // Deliberately NO halfOpenFailures reset here: the breaker opening
            // is carried by stuckOpen=true and halfOpenCooldownMs() derives
            // from the counter. A racing onProbeFail may have just incremented
            // it; zeroing here would erase that increment and regress the
            // half-open backoff schedule to its initial cooldown (30s) for a
            // still-failing upstream. Success is the only legitimate resetter
            // (same discipline as the invalidate()/onSuccess() resets).
            try {
                lastStuckOpenMs.set(nowMs())
            } catch (_: Throwable) {
                try {
                    lastStuckOpenMs.set(System.nanoTime() / 1_000_000L)
                } catch (_: Throwable) {
                }
            }
            throttledLog("policy refresh stuck open (consecutiveTimeouts=$n)", force = true)
        } else if (!trip) {
            if (accountDrop) droppedRefreshCount.incrementAndGet()
        }
        // Note: when trip && already stuckOpen (a probe timeout racing the
        // normal path, or consecutive timeouts beyond the trip), the probe
        // path owns lastStuckOpenMs/backoff via onProbeFail; the normal path
        // must not clobber the half-open schedule, so it only counts drops
        // when NOT tripping.
        if (trip) {
            // Count the tripping timeout itself as a short-circuit too so
            // stuckShortCircuits is non-zero whenever the breaker is open.
            stuckShortCircuits.incrementAndGet()
        }
    }

    private fun throttledLog(msg: String, force: Boolean = false) {
        try {
            val now = try {
                nowMs()
            } catch (_: Throwable) {
                return
            }
            val last = lastDropLogMs
            if (!force && last != Long.MIN_VALUE &&
                now - last < PolicyRefreshBudget.DROP_LOG_THROTTLE_MS
            ) {
                return
            }
            lastDropLogMs = now
            log(msg)
        } catch (_: Throwable) {
        }
    }
}
