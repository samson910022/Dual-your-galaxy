// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 3 / D05: app-scoped, cross-lifecycle serializer for BBC opt-out
// writes. Round-2 N05 fixed ordering *inside* one controller, but Main disposed
// that controller and built a new one on every dialog rebuild / Activity
// destroy. A synchronous RemotePreferences commit already entered by the old
// actor is non-cancellable and could land AFTER the new controller's write —
// overwriting the user's last intent. This coordinator owns the ONLY writer:
// one conflated channel drained by ONE app-lifetime consumer.
//
// Framework-free on purpose (only kotlinx.coroutines): JVM tests can drive it
// without Robolectric.
package com.galaxydual.plus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide serializer for BBC injection opt-out preference writes.
 *
 * **App-process-local.** Constructed ONCE for the Application lifetime and
 * shared by every [BbcInjectionToggleController], including controllers created
 * by later dialog/Activity rebuilds. The single conflated channel + single
 * consumer means every commit that actually entered the queue is serialized
 * GLOBALLY: an old, already-running commit finishes before a newer one starts,
 * so the last user intent is the last value written to storage — across UI
 * lifetimes, not just within one controller.
 *
 * [submit] assigns its sequence at USER-INTENT time (via
 * [BbcToggleRuntimeState.nextSequence], called by the controller). Sequence
 * numbers therefore stay monotonic across rebuilds. A tap replaced in the
 * CONFLATED slot before it is dequeued never gets a result callback: its
 * outcome is irrelevant because a newer intent superseded it.
 *
 * Cancellation of a controller's UI must NOT cancel this consumer or an
 * in-flight commit; `dispose()` on the controller only detaches UI callbacks.
 * A [CancellationException] from [commit] is propagated to the app scope (and
 * produces no UI callback), matching the previous controller behavior. Every
 * other throwable maps to [BbcWriteOutcome.Unknown].
 *
 * This is a write-ordering guarantee for the persisted preference only. It is
 * NOT the hook process's `bbcConfirmedOffAtMs`, NOT a statement about immediate
 * hook behavior (the hook may read a cached value), and NOT a Samsung cold-start
 * deletion guard — the BBC opt-out does not protect against that (issue #75).
 */
internal class BbcToggleCoordinator(
    /** App lifetime scope (NOT lifecycleScope); owns the single consumer. */
    internal val scope: CoroutineScope,
    /** App-scoped outcome holder + monotonic intent sequence. */
    internal val state: BbcToggleRuntimeState,
    /** Commit seam; never throws except CancellationException. */
    private val commit: suspend (Boolean) -> BbcWriteOutcome,
    /** Commit dispatcher; IO in production, an injected scheduler in JVM tests. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Write(
        val sequence: Long,
        val value: Boolean,
        val onResult: (BbcWriteOutcome) -> Unit,
    )

    // CONFLATED: a burst keeps just the newest not-yet-dequeued intent. There
    // is exactly one consumer below, so commits are serialized process-wide.
    private val writes = Channel<Write>(Channel.CONFLATED)

    private val consumer: Job = scope.launch {
        for (write in writes) {
            val outcome = try {
                // A synchronous, non-cancellable commit is never abandoned
                // mid-call by UI disposal: the app scope outlives every UI.
                withContext(io) { commit(write.value) }
            } catch (ce: CancellationException) {
                // App-scope cancellation: no UI callback, let it propagate.
                throw ce
            } catch (_: Throwable) {
                BbcWriteOutcome.Unknown
            }

            // mark() is sequence-aware: an outcome older than one already
            // applied is ignored. Defensive — the single consumer already
            // serializes in submit order — but it also covers a multi-threaded
            // caller racing nextSequence() against submit().
            val applied = state.mark(outcome, write.sequence)
            if (!applied) continue
            try {
                write.onResult(outcome)
            } catch (_: Throwable) {
                // A UI callback must never kill the process-wide consumer.
            }
        }
    }

    /** Never blocks. Superseded CONFLATED intents simply never get a callback. */
    fun submit(sequence: Long, value: Boolean, onResult: (BbcWriteOutcome) -> Unit) {
        writes.trySend(Write(sequence, value, onResult))
    }

    /** Last user-intent sequence issued in this app process (for tests/telemetry). */
    fun latestSequence(): Long = state.latestSequence()

    /** Test seam: the app-lifetime consumer must survive controller disposal. */
    internal fun isConsumerActive(): Boolean = consumer.isActive
}
