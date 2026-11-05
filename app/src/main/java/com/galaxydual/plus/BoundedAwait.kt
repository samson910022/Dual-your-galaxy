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
// Design (P2 Job-cancellation-safe, issue #24): the block runs on a SHARED
// bounded daemon pool ("shizuku-transact-N", at most TRANSACT_MAX_THREADS
// threads, never per-call executors) while the caller SUSPENDS -- no caller
// thread (in particular no Dispatchers.IO worker, and no viewModelScope
// thread) is ever blocked in Future.get. The wait is a cancellable
// suspension (CompletableFuture callback + withTimeoutOrNull):
//   - timeout: the future is cancelled and null is returned (the caller maps
//     null to the existing dropBindingLocked + timedOut() path; a late
//     reply, if any, is discarded -- its return value is dropped and it must
//     not touch shared state);
//   - coroutine cancellation (e.g. selectChannel latest-wins cancelling the
//     previous select job while it sits in a hung transact): invokeOnCancellation
//     cancels the future and the CancellationException propagates, so the
//     caller stops waiting immediately and releases opMutex for the next
//     select -- the new select takes over without waiting out the deadline.
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
// Cancel semantics (supplyAsync): future.cancel(true) does NOT interrupt a
// running worker and does NOT remove a queued task from the pool queue. A
// running hung transact simply keeps its thread until it returns (only the
// caller stops waiting); a queued-but-cancelled task stays in the queue as a
// tiny no-op shell -- its supplier never runs -- until a worker dequeues and
// discards it. Permits therefore bound admission, not the dequeued-shell
// count: under a sustained hung storm where workers never free, each
// timed-out queued submission leaves one no-op shell behind after releasing
// its permit, so the queue can grow slowly (one shell per such timeout).
// Each shell holds no thread, runs no block, and drains instantly once any
// worker frees, so there is no thread-per-hung-call accumulation as the old
// per-call single-thread executors had, and no deadlock / OOM urgency.
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
import java.util.concurrent.CompletionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
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
// timeout so faults never pin the process. A cancelled wait leaves only a
// tiny no-op shell in the queue (supplier never runs) until a worker dequeues
// and discards it; permits bound admission so live threads never exceed the
// cap.
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

// Global outstanding-transact bound: at most TRANSACT_MAX_THREADS blocks are
// admitted (running + pool-queued and not yet exited) no matter how many hung
// calls pile up.
// Callers past the cap suspend on the permit WITHOUT holding any thread, and
// that wait is itself bounded by the caller's own deadline (the permit gate
// sits INSIDE withTimeoutOrNull in awaitBounded) and cancellable
// (Semaphore.acquire is a cancellable suspension, so a selectChannel
// latest-wins cancel releases a permit waiter at once instead of parking it
// to the deadline). Non-fair: handover latency matters more than FIFO order
// here, and every waiter carries its own deadline so no waiter starves past
// its timeout.
// Why a semaphore ON TOP of the fixed pool: the pool alone caps RUNNING
// threads, but every admitted call still submits a future that queues behind
// hung workers -- a sustained fault storm of never-cancelled hung calls
// (e.g. repeated user taps, each with the 60s EXEC_TIMEOUT_MS) would park an
// unbounded queue of futures behind 4 pinned workers. The semaphore caps
// outstanding (not-yet-exited) submissions at TRANSACT_MAX_THREADS: at most 4
// callers hold a running-or-queued future at once, the rest suspend on the
// permit holding no thread. A timed-out queued submission still leaves its
// cancelled no-op shell in the queue after releasing its permit (supplier
// never runs; shell discarded at dequeue), so the shell count can creep up
// while workers stay pinned -- but each shell is tiny, runs nothing, and the
// live-thread count never exceeds the cap. Fault recovery stays in the
// gateway: every timeout / transport-loss path drops the cached binding
// (dropBindingLocked) so the next run() rebinds fresh; this layer never
// retries internally.
internal val transactPermits = Semaphore(TRANSACT_MAX_THREADS)

// Runs [block] with a bounded, cancellable client wait. Returns the block
// value on time, null on timeout (caller maps null to timedOut). Job
// cancellation stops the wait at once (CancellationException, future
// cancelled) instead of holding the caller to the deadline. Exceptions from
// [block] propagate unwrapped to the caller (transactExec maps them to
// transportUnknown -- post-dispatch reply loss, never a definite failure;
// CancellationException passes through the caller's existing rethrow).
// Interrupt hygiene (defensive): supplyAsync cancel does NOT interrupt the
// worker, so a hung block simply runs to completion on its thread; the
// wrapper still clears the thread's interrupt flag after the block settles
// in case the block itself used interrupts internally (or the platform ever
// delivers one) -- otherwise a stale flag would leak into whatever task
// reuses the pooled thread next.
suspend fun awaitBounded(timeoutMs: Long, block: () -> CmdResult): CmdResult? {
    // Holds the submitted future once past the permit gate, so the timeout
    // path below can cancel a running/queued task even though the future is
    // created inside the withPermit scope. Null while still queued on the
    // permit (nothing submitted yet -- nothing to cancel).
    val futureRef = AtomicReference<CompletableFuture<CmdResult>?>(null)
    try {
        // The deadline covers the permit wait AND the block wait: a caller
        // stuck behind hung permit holders still returns by its own timeout
        // instead of queueing past it. withTimeoutOrNull owns the deadline
        // via cancellable delay (no thread blocked); a timeout cancels the
        // suspension below, whose invokeOnCancellation cancels the future --
        // one cancel path for timeout, Job cancellation, AND permit-wait
        // cancellation (withPermit acquisition is itself cancellable).
        val settled = withTimeoutOrNull(timeoutMs) {
            transactPermits.withPermit {
                val future = CompletableFuture.supplyAsync(
                    Supplier {
                        try {
                            block()
                        } finally {
                            // See above: never hand a pooled thread to the next
                            // task with a stale interrupt flag left by the
                            // block's own interrupt use.
                            Thread.interrupted()
                        }
                    },
                    transactExecutor,
                )
                futureRef.set(future)
                try {
                    awaitFuture(future)
                } catch (e: CancellationException) {
                    future.cancel(true)
                    throw e
                }
            }
        }
        if (settled == null) futureRef.get()?.cancel(true)
        return settled
    } catch (e: CancellationException) {
        futureRef.get()?.cancel(true)
        throw e
    }
}

// Cancellable suspension on a CompletableFuture with no thread blocked: the
// callback resumes the coroutine, and coroutine cancellation cancels the
// future. Cancel does NOT interrupt a running block nor remove a queued task:
// running work runs to completion on its thread (caller just stops waiting),
// queued work turns into a no-op shell (supplier never runs) discarded at
// dequeue. CompletionException from supplyAsync is unwrapped one level so the
// caller's catch sees the block's real exception (InterruptedException
// included).
private suspend fun <T> awaitFuture(future: CompletableFuture<T>): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { value, ex ->
            if (ex == null) {
                cont.resume(value)
            } else {
                val cause = (ex as? CompletionException)?.cause ?: ex
                cont.resumeWithException(cause)
            }
        }
    }
