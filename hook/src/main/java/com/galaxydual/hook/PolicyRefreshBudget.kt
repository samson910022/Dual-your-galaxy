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
// Stall budget: [DEFAULT_STALL_BUDGET_MS] (50ms) keeps a whitelist query
// (a local binder query normally sub-ms to a few ms) from stalling on IPC.
// Documented in docs/HOOK.md §1.1; DualHook uses this for every effective-
// policy refresh. JVM-tested via injected ExecutorService (see
// PolicyRefreshBudgetTest); production passes a shared daemon executor.
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
    // Throttled drop log: at most one log per window to avoid hot-path spam.
    const val DROP_LOG_THROTTLE_MS = 5_000L

    fun readBounded(
        reader: () -> Set<String>?,
        timeoutMs: Long = DEFAULT_STALL_BUDGET_MS,
        executor: ExecutorService,
    ): Set<String>? {
        // Broad Throwable is intentional: the reader runs hostile/framework
        // code that may throw Error (not just Exception), and submit/get may
        // throw RejectedExecutionException/InterruptedException on shutdown
        // races. All map to null (failed-read) so the caller falls back to
        // seed/last-known-good; narrowing to Exception would let an Error
        // escape onto the hooked query thread.
        val future: Future<Set<String>?> = try {
            executor.submit(Callable<Set<String>?> { reader() })
        } catch (_: Throwable) {
            return null
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            null
        } catch (_: Throwable) {
            try {
                future.cancel(true)
            } catch (_: Throwable) {
            }
            null
        }
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
//    RejectedExecutionException -> null (droppedRefreshCount++).
// 3. Stuck-worker circuit breaker: consecutive TimeoutExceptions trip the
//    breaker (≥3 consecutive timeouts, or 1 timeout when last success ≥30s
//    ago (requires a prior success)). While open, submit is short-circuited to null
//    (stuckShortCircuits++) until invalidate() (explicit policy-change signal)
//    or a success resets it.
//
// Caller contract is unchanged: timeout / exception / rejection / contention /
// short-circuit all return null so the caller falls back to seed /
// last-known-good; timeoutMs stays retunable.
//
// Thread-safety: all counters are AtomicLong; the semaphore serializes
// submits; breaker state is volatile + atomic counters only (no lock on the
// hot path beyond the semaphore).
class PolicyRefreshGate(
    private val executor: ExecutorService,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxConsecutiveTimeouts: Int = PolicyRefreshBudget.MAX_CONSECUTIVE_TIMEOUTS,
    private val stuckOpenMs: Long = PolicyRefreshBudget.STUCK_OPEN_MS,
    private val log: (String) -> Unit = {},
) {
    private val outstanding = Semaphore(1)

    val droppedRefreshCount = AtomicLong(0L)
    val stuckShortCircuits = AtomicLong(0L)
    val consecutiveTimeouts = AtomicLong(0L)

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
        if (executor is ThreadPoolExecutor) executor.queue.size else -1
    } catch (_: Throwable) {
        -1
    }

    fun activeCount(): Int = try {
        if (executor is ThreadPoolExecutor) executor.activeCount else -1
    } catch (_: Throwable) {
        -1
    }

    fun outstandingPermitsAvailable(): Int = try {
        outstanding.availablePermits()
    } catch (_: Throwable) {
        -1
    }

    fun readBounded(
        reader: () -> Set<String>?,
        timeoutMs: Long = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
    ): Set<String>? {
        // Stuck-worker short-circuit: no submit, no semaphore, immediate null.
        if (stuckOpen) {
            stuckShortCircuits.incrementAndGet()
            droppedRefreshCount.incrementAndGet()
            throttledLog("policy refresh stuck short-circuit (timeouts=${consecutiveTimeouts.get()})")
            return null
        }
        // At-most-one outstanding guard (non-blocking).
        if (!outstanding.tryAcquire()) {
            droppedRefreshCount.incrementAndGet()
            throttledLog("policy refresh dropped: outstanding in flight")
            return null
        }
        try {
            // Re-check breaker under guard (a concurrent timeout may have
            // tripped it between the first check and acquire).
            if (stuckOpen) {
                stuckShortCircuits.incrementAndGet()
                droppedRefreshCount.incrementAndGet()
                throttledLog("policy refresh stuck short-circuit (timeouts=${consecutiveTimeouts.get()})")
                return null
            }
            val future: Future<Set<String>?> = try {
                executor.submit(Callable<Set<String>?> { reader() })
            } catch (re: RejectedExecutionException) {
                droppedRefreshCount.incrementAndGet()
                throttledLog("policy refresh rejected: ${re.javaClass.simpleName}")
                return null
            } catch (_: Throwable) {
                droppedRefreshCount.incrementAndGet()
                return null
            }
            return try {
                val v = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                onSuccess()
                v
            } catch (_: TimeoutException) {
                try {
                    future.cancel(true)
                } catch (_: Throwable) {
                }
                onTimeout()
                null
            } catch (_: Throwable) {
                try {
                    future.cancel(true)
                } catch (_: Throwable) {
                }
                // Non-timeout failure (exception/interrupt): not a stuck
                // signal, but a null result still counts as "no success" for
                // the time-since-last-success arm of the breaker (checked on
                // the next timeout). Do not reset consecutiveTimeouts here:
                // only a success clears the streak.
                null
            }
        } finally {
            try {
                outstanding.release()
            } catch (_: Throwable) {
            }
        }
    }

    // Explicit policy-change signal (DualHook listener / cache invalidate):
    // clears the breaker so the next miss retries immediately (recovery path
    // pinned by tests). Does NOT touch the executor (a stuck worker thread
    // still drains on its own; the zero-queue + guard bounds it to <=1).
    fun invalidate() {
        consecutiveTimeouts.set(0L)
        stuckOpen = false
    }

    private fun onSuccess() {
        consecutiveTimeouts.set(0L)
        stuckOpen = false
        try {
            lastSuccessMs = nowMs()
        } catch (_: Throwable) {
        }
    }

    private fun onTimeout() {
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
            throttledLog("policy refresh stuck open (consecutiveTimeouts=$n)", force = true)
        } else {
            droppedRefreshCount.incrementAndGet()
        }
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
