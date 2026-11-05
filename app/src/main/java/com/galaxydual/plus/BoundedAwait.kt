// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// P1-2 (PR-32 round 2 Phase B): bounded client-side await for blocking Binder
// transact. Pure Kotlin, no Android import, so JVM unit tests cover it.
//
// Why this exists: ShizukuCmdGateway.transactExec used to wrap a synchronous
// binder.transact(flags=0) in withTimeoutOrNull + withContext(Dispatchers.IO).
// Dispatchers.IO never interrupts a non-cooperative blocking transact, so the
// outer timeout did NOT bound the client wait in practice (local repro:
// timeout=100ms against a 450ms blocking transact returned in ~466ms). The
// caller appeared to "stop waiting" but the coroutine stayed suspended until
// the transact really returned.
//
// Design: run the block on a per-call daemon single-thread executor and wait
// with Future.get(timeout). On expiry cancel(true) + shutdown + return null
// (caller maps null to the existing dropBindingLocked + timedOut() path; the
// late reply, if any, is discarded -- its return value is dropped and it must
// not touch shared state). Binder work and the client wait are therefore
// separated: the client wait is always bounded, the Binder call itself is
// never force-stopped (Binder has no safe cancel).
//
// Cost (deliberate): a hung transact leaks one blocked executor thread until
// the transact itself returns -- at most one thread per hung call. The thread
// is daemon ("shizuku-transact-N") so it never blocks process exit, and the
// executor is shut down on every path so no pool accumulates. The timeout
// value is supplied by the caller; transactExec passes
// ShizukuExecProtocol.EXEC_TIMEOUT_MS (unchanged).
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

// Sequence for "shizuku-transact-N" daemon names so concurrent hung calls
// remain distinguishable in thread dumps (at most one thread per hung call).
private val transactThreadSeq = AtomicLong(0)

// Runs [block] with a bounded client wait. Returns the block value on time,
// null on timeout (caller maps null to timedOut). Exceptions from [block]
// propagate unwrapped to the caller (transactExec maps them to remoteFailed;
// CancellationException passes through the caller's existing rethrow).
// A Future.get interrupt restores the interrupt flag and rethrows so the
// caller's cancellation/error path observes it.
fun awaitBounded(timeoutMs: Long, block: () -> CmdResult): CmdResult? {
    val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-transact-${transactThreadSeq.incrementAndGet()}").apply { isDaemon = true }
    }
    val future = executor.submit<CmdResult> { block() }
    try {
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw e
        }
    } finally {
        executor.shutdown()
    }
}
