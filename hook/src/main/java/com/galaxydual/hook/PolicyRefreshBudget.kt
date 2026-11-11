// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
object PolicyRefreshBudget {
    const val DEFAULT_STALL_BUDGET_MS = 50L

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
}
