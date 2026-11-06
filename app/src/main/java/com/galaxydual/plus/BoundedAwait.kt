// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// P1-2 (PR-32 round 2 Phase B): bounded client-side await for blocking Binder
// transact. Pure Kotlin, no Android import, so JVM unit tests cover it.
//
// Why this exists: ShizukuCmdGateway.transactExec used to wrap a synchronous
// binder.transact(flags=0) with withTimeoutOrNull + withContext(Dispatchers.IO).
// Dispatchers.IO never interrupts a non-cooperative blocking transact, so the
// outer timeout did NOT bound the client wait in practice (local repro:
// timeout=100ms against a 450ms blocking transact returned in ~466ms). The
// caller appeared to "stop waiting" but the coroutine stayed suspended until
// the transact really returned.
//
// Design (P2 Job-cancellation-safe, issue #24): the block runs on a SHARED
// bounded daemon pool ("shizuku-transact-N", at most TRANSACT_MAX_THREADS
// threads, never per-call executors) while the caller SUSPENDS -- no caller
// thread (in particular no Dispatchers.IO worker, and no viewModelScope
// thread) is ever blocked in Future.get. The wait is a cancellable
// suspension (CompletableFuture callback + withTimeoutOrNull):
//   - timeout: the future is cancelled, the queued runner (if still queued)
//     is removed from the pool queue, and null is returned (the caller maps
//     null to the existing dropBindingLocked + timedOut() path; a late
//     reply, if any, is discarded -- its return value is dropped and it must
//     not touch shared state);
//   - coroutine cancellation (e.g. selectChannel latest-wins cancelling the
//     previous select job while it sits in a hung transact): invokeOnCancellation
//     cancels the future, removes the queued runner, and the CancellationException
//     propagates, so the caller stops waiting immediately and releases opMutex
//     for the next select -- the new select takes over without waiting out
//     the deadline.
// Binder work and the client wait are therefore separated: the client wait
// is always bounded AND cancellable, the Binder call itself is never
// force-stopped (Binder has no safe cancel; a non-cooperative transact keeps
// its pool thread until it returns -- the REMOTE side runs to completion,
// only the caller stops waiting).
//
// Resource bound (hung-transact accumulation): outstanding transact
// submissions are capped at TRANSACT_MAX_THREADS by transactPermits no matter
// how many hung calls pile up -- at most 4 futures are ever admitted
// (running + pool-queued and not yet exited); further callers suspend on the
// permit holding no thread, bounded by their own deadline, releasable by Job
// cancellation.
// Cancel semantics (execute+remove, P2-2): the block is submitted via
// executor.execute(runner) with the runner reference retained. Timeout / Job
// cancellation calls future.cancel(true) AND executor.remove(runner): a
// still-queued submission is dequeued at once so its block never runs and no
// shell is left behind; a RUNNING submission is unaffected (remove is a
// no-op, the hung transact keeps its thread until it returns -- only the
// caller stops waiting). A dequeue race (worker dequeued just before remove)
// is covered by the runner's entry guard (skips the block when the future is
// already done), so no orphan shell can accumulate. Permits therefore bound
// admission AND the queue stays drained: under a sustained hung storm where
// workers never free, timed-out callers leave nothing behind after releasing
// their permit, so the queue size stays 0 instead of growing one shell per
// timeout (the old supplyAsync AsyncSupply shells could not be removed and
// crept up under the same storm; ThreadPoolExecutor.purge() does not help
// there). Each running hung call still pins at most one pool thread until it
// returns; the live-thread count never exceeds the cap.
// Idle pool threads time out (60s keep-alive) and the threads are daemon, so
// neither a fault burst nor process exit is ever held up. Fault recovery
// stays in the gateway: every timeout / transport-loss path drops the cached
// binding (dropBindingLocked) so the next run() rebinds fresh; this layer
// never retries internally.
// The timeout value is supplied by the caller; transactExec passes
// ShizukuExecProtocol.EXEC_TIMEOUT_MS (unchanged).
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

// Cap on concurrent transact worker threads. One hung transact pins at most
// one pool thread, so worst-case accumulation is this constant -- never one
// thread per hung call. Sized for a single Binder service: real traffic is
// serialized through DualViewModel.opMutex (1 in flight), so 4 leaves ample
// headroom for a hung worker plus a fresh rebind racing it, without inviting
// Binder-thread stampedes on a fault burst.
internal const val TRANSACT_MAX_THREADS = 4

// Sequence for "shizuku-transact-N" daemon names so hung workers remain
// distinguishable in thread dumps. Increments only when the pool actually
// creates a thread (at most TRANSACT_MAX_THREADS live), never per call.
private val transactThreadSeq = AtomicLong(0)

// Shared process-lifetime pool for transact blocks. Fixed size (never grows
// past TRANSACT_MAX_THREADS), unbounded handoff queue, daemon threads + idle
// timeout so faults never pin the process. Timeout / Job cancellation removes
// the still-queued runner via remove() (see awaitBounded), so the queue stays
// drained; a running hung block simply keeps its thread until it returns.
// Never shut down: per-call executors were the leak this replaces.
internal val transactExecutor = ThreadPoolExecutor(
    TRANSACT_MAX_THREADS,
    TRANSACT_MAX_THREADS,
    60L,
    TimeUnit.SECONDS,
    LinkedBlockingQueue(),
    { r ->
        Thread(r, "shizuku-transact-${transactThreadSeq.incrementAndGet()}").apply { isDaemon = true }
    },
).apply { allowCoreThreadTimeOut(true) }

// Test-only isolated pool with the same shape (fixed size, daemon threads).
// Truly-stuck tests must use this (via the injectable awaitBounded overload)
// instead of the process-global transactExecutor, so pinned workers never
// leak into other tests.
internal fun newIsolatedTransactExecutor(): ThreadPoolExecutor =
    ThreadPoolExecutor(
        TRANSACT_MAX_THREADS,
        TRANSACT_MAX_THREADS,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        { r ->
            Thread(r, "shizuku-awaittest-${transactThreadSeq.incrementAndGet()}").apply { isDaemon = true }
        },
    ).apply { allowCoreThreadTimeOut(true) }

// Global outstanding-transact bound: at most TRANSACT_MAX_THREADS blocks are
// admitted (running + pool-queued and not yet exited) no matter how many hung
// calls pile up.
// Callers past the cap suspend on the permit WITHOUT holding any thread, and
// that wait is itself bounded by the caller's own deadline (the permit gate
// sits INSIDE withTimeoutOrNull in awaitBounded) and cancellable
// (Semaphore.acquire is a cancellable suspension, so a selectChannel
// latest-wins cancel releases a permit waiter at once instead of parking it
// to the deadline). FIFO: kotlinx.coroutines Semaphore hands out permits in
// acquisition order, so waiters are served fairly; every waiter also carries
// its own deadline so no waiter starves past its timeout.
// Why a semaphore ON TOP of the fixed pool: the pool alone caps RUNNING
// threads, but every admitted call still submits a runner that queues behind
// hung workers -- a sustained fault storm of never-cancelled hung calls
// (e.g. repeated user taps, each with the 60s EXEC_TIMEOUT_MS) would park an
// unbounded queue of runners behind 4 pinned workers. The semaphore caps
// outstanding (not-yet-exited) submissions at TRANSACT_MAX_THREADS: at most 4
// callers hold a running-or-queued runner at once, the rest suspend on the
// permit holding no thread. A timed-out / cancelled queued submission is
// removed from the queue at once (executor.remove(runner)), so the shell
// count cannot creep up while workers stay pinned -- the queue stays drained
// and the live-thread count never exceeds the cap. Fault recovery stays in
// the gateway: every timeout / transport-loss path drops the cached binding
// (dropBindingLocked) so the next run() rebinds fresh; this layer never
// retries internally.
internal val transactPermits = Semaphore(TRANSACT_MAX_THREADS)

// Runs [block] with a bounded, cancellable client wait. Returns the block
// value on time, null on timeout (caller maps null to timedOut). Job
// cancellation stops the wait at once (CancellationException, future
// cancelled + queued runner removed) instead of holding the caller to the
// deadline. Exceptions from [block] propagate unwrapped to the caller
// (transactExec maps them to transportUnknown -- post-dispatch reply loss,
// never a definite failure; CancellationException passes through the caller's
// existing rethrow).
// Interrupt hygiene (defensive): cancel does NOT interrupt the worker, so a
// hung block simply runs to completion on its thread; the wrapper still
// clears the thread's interrupt flag after the block settles in case the
// block itself used interrupts internally (or the platform ever delivers
// one) -- otherwise a stale flag would leak into whatever task reuses the
// pooled thread next.
// Process-global entry point (transactExec and existing tests use this).
suspend fun awaitBounded(timeoutMs: Long, block: () -> CmdResult): CmdResult? =
    awaitBounded(timeoutMs, transactExecutor, transactPermits, block)

// Injectable overload for tests: same logic against an isolated
// executor + semaphore so truly-stuck (never-returning) blocks never pin the
// process-global pool and pollute other tests.
internal suspend fun awaitBounded(
    timeoutMs: Long,
    executor: ThreadPoolExecutor,
    permits: Semaphore,
    block: () -> CmdResult,
): CmdResult? {
    // Holds the submitted future + runner once past the permit gate, so the
    // timeout / cancellation paths below can cancel a running task and remove
    // a still-queued one. Null while queued on the permit (nothing submitted
    // yet -- nothing to cancel).
    val futureRef = AtomicReference<CompletableFuture<CmdResult>?>(null)
    val runnerRef = AtomicReference<Runnable?>(null)
    // One cancel path for timeout, Job cancellation, AND permit-wait
    // cancellation: future.cancel stops the wait; executor.remove dequeues a
    // still-queued runner (no-op when running -- correct, the hung block keeps
    // its thread and only the caller stops waiting).
    fun cancelPending() {
        futureRef.get()?.cancel(true)
        runnerRef.get()?.let { executor.remove(it) }
    }
    try {
        // The deadline covers the permit wait AND the block wait: a caller
        // stuck behind hung permit holders still returns by its own timeout
        // instead of queueing past it. withTimeoutOrNull owns the deadline
        // via cancellable delay (no thread blocked); a timeout cancels the
        // suspension below, whose invokeOnCancellation removes the runner --
        // one cancel path for timeout, Job cancellation, and permit-wait
        // cancellation (withPermit acquisition is itself cancellable).
        val settled = withTimeoutOrNull(timeoutMs) {
            permits.withPermit {
                val future = CompletableFuture<CmdResult>()
                val runner = Runnable {
                    // Dequeue race guard: remove() just missed (the worker
                    // dequeued between cancel and remove). The caller already
                    // stopped waiting, so skip the block -- the late value
                    // must be discarded and touch no shared state.
                    if (future.isDone) return@Runnable
                    try {
                        future.complete(block())
                    } catch (t: Throwable) {
                        future.completeExceptionally(t)
                    } finally {
                        // Never hand a pooled thread to the next task with a
                        // stale interrupt flag left by the block's own
                        // interrupt use.
                        Thread.interrupted()
                    }
                }
                futureRef.set(future)
                runnerRef.set(runner)
                try {
                    executor.execute(runner)
                } catch (e: RuntimeException) {
                    future.cancel(true)
                    throw e
                }
                try {
                    awaitFuture(future) { cancelPending() }
                } catch (e: CancellationException) {
                    cancelPending()
                    throw e
                }
            }
        }
        if (settled == null) cancelPending()
        return settled
    } catch (e: CancellationException) {
        cancelPending()
        throw e
    }
}

// Cancellable wait helper: suspends until the future settles.
private suspend fun <T> awaitFuture(future: CompletableFuture<T>, onCancel: () -> Unit): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { onCancel() }
        future.whenComplete { value, ex ->
            if (ex == null) {
                cont.resume(value)
            } else {
                // Manual completeExceptionally delivers the block's real
                // exception directly (no CompletionException wrapper); the
                // unwrap below is kept for tolerance (InterruptedException
                // included).
                val cause = (ex as? java.util.concurrent.CompletionException)?.cause ?: ex
                cont.resumeWithException(cause)
            }
        }
    }
