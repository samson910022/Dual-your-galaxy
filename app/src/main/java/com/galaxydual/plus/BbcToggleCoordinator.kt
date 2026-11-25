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
// PR #90 Phase C: the SAME single consumer now also owns the consent lane
// (submitConsentWrite / submitConsentRead). Consent I/O therefore never runs on
// the Main thread and is serialized against the runtime opt-out writes by the
// very same global consumer. A newer OFF can supersede a queued consent write
// (CONFLATED), and the holder's sequence fence drops a result that arrives
// after a newer intent.
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
 * Process-wide serializer for BBC preference writes and consent reads.
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
 * [BbcToggleRuntimeState.nextSequence]) AND enqueues inside ONE critical
 * section. Allocation and enqueue MUST be atomic: if a caller could allocate
 * sequence N, be descheduled, and let another caller allocate N+1 and enqueue
 * it first, CONFLATED would keep the LAST SEND — the older sequence — and the
 * stale value could be written to disk last. Sequence numbers stay monotonic
 * across rebuilds. A tap replaced in the CONFLATED slot before it is dequeued
 * never gets a result callback: its outcome is irrelevant because a newer
 * intent superseded it.
 *
 * #81: the pre-commit sequence fence below remains as defense in depth. It is
 * what makes [submitWithExplicitSequenceForTests] (test-only) safe and it
 * protects any future caller that bypasses the atomic path. A write whose
 * sequence is older than the latest issued intent is discarded wholesale (no
 * commit, no mark, no callback), because state.mark()'s result-only fence is
 * too late to protect the persisted preference.
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
    /**
     * Consent (opt-in key) commit seam. Runs on [io] inside the SAME app-lifetime
     * consumer as [commit]. Defaults fail closed so a caller that forgot to wire
     * it can never grant consent.
     */
    private val consentCommit: suspend () -> BbcWriteOutcome = { BbcWriteOutcome.Unknown },
    /**
     * Authoritative durable read seam for bind/resync. Always executes on [io];
     * the generation argument comes from the app-scoped holder at execution
     * time so a stale service instance cannot certify consent.
     */
    private val consentRead: suspend (Long) -> BbcConsentAuthoritativeRead = { generation ->
        BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
    },
    /** The one app-scoped authorization holder; shared across all UI rebuilds. */
    internal val consentHolder: BbcActivationConsentHolder = BbcActivationConsentHolder(),
) {
    private sealed interface Work {
        data class Toggle(
            val sequence: Long,
            val value: Boolean,
            val onResult: (BbcWriteOutcome) -> Unit,
        ) : Work

        /** The opt-in grant write. Sequence/generation come from the app holder. */
        data class ConsentWrite(
            val sequence: Long,
            val generation: Long,
            val onResult: (BbcWriteOutcome) -> Unit,
        ) : Work

        /** Bind/resync read. Never called from an OFF toggle path. */
        data class ConsentRead(
            val onResult: (BbcConsentAuthoritativeRead) -> Unit,
        ) : Work
    }

    // CONFLATED: a burst keeps just the newest not-yet-dequeued intent. There
    // is exactly one consumer below, so commits are serialized process-wide.
    private val writes = Channel<Work>(Channel.CONFLATED)

    /**
     * Sequence allocation and channel enqueue share ONE critical section.
     * Without it, two callers can interleave as allocate(N) / allocate(N+1) /
     * send(N+1) / send(N); CONFLATED keeps the last SEND, so the stale lower
     * sequence would overwrite the newer value on disk and never notify the
     * newest intent's caller (#81 follow-up B).
     */
    private val enqueueLock = Any()

    /**
     * Test seam only — invoked INSIDE [enqueueLock], after the sequence is
     * allocated but before trySend. Must not throw and is null in production.
     * A test can park here to observe that a concurrent [submit] cannot make
     * progress (and cannot allocate a higher sequence) until the lock is freed.
     */
    internal var afterBbcSequenceAllocatedForTests: ((Long) -> Unit)? = null

    /** Test seam only: writes dropped by the pre-commit sequence fence (C1/#81). */
    @Volatile
    private var skippedWrites: Int = 0

    private val consumer: Job = scope.launch {
        for (work in writes) {
            when (work) {
                is Work.Toggle -> {
                    // #81: the sequence belongs to the USER INTENT (assigned before
                    // submit), not to the queue position. A multi-threaded caller can
                    // nextSequence() in one order and submit() in the opposite order,
                    // and CONFLATED keeps the last SEND, which may be the older intent.
                    // Re-check against the latest ISSUED sequence right here, before the
                    // non-cancellable commit — state.mark() below only fences the
                    // result, which is too late to protect the persisted preference.
                    // latestSequence() starts at 0 and nextSequence() increments it, so
                    // the first write (sequence 1) always passes; only strictly older
                    // writes are dropped. This fence is for the TOGGLE sequence domain
                    // only; consent writes are fenced by consentHolder below.
                    if (work.sequence < state.latestSequence()) {
                        skippedWrites++ // test seam; skipped writes commit/mark/callback nothing
                        continue
                    }

                    val outcome = try {
                        // A synchronous, non-cancellable commit is never abandoned
                        // mid-call by UI disposal: the app scope outlives every UI.
                        withContext(io) { commit(work.value) }
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
                    val applied = state.mark(outcome, work.sequence)
                    if (!applied) continue
                    try {
                        work.onResult(outcome)
                    } catch (_: Throwable) {
                        // A UI callback must never kill the process-wide consumer.
                    }
                }

                is Work.ConsentWrite -> {
                    val outcome = try {
                        withContext(io) { consentCommit() }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        BbcWriteOutcome.Unknown
                    }
                    // Durable fence / grant is recorded HERE, on the app IO lane,
                    // before any UI callback sees the outcome. A result callback
                    // for a stale intent is dropped entirely.
                    val applied = consentHolder.markConsentWriteOutcome(
                        outcome, work.sequence, work.generation,
                    )
                    if (!applied) continue
                    try {
                        work.onResult(outcome)
                    } catch (_: Throwable) {
                        // A UI callback must never kill the process-wide consumer.
                    }
                }

                is Work.ConsentRead -> {
                    val generation = consentHolder.currentGeneration()
                    val read = try {
                        withContext(io) { consentRead(generation) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
                    }
                    // Resolve on the IO consumer thread: no Main-thread binder
                    // read, and a local mMap value can never clear the fence
                    // (the holder enforces the generation rule).
                    consentHolder.resolve(read)
                    try {
                        work.onResult(read)
                    } catch (_: Throwable) {
                        // A UI callback must never kill the process-wide consumer.
                    }
                }
            }
        }
    }

    /**
     * Atomic user-intent submit: allocate the sequence AND enqueue in one
     * critical section, then return the allocated sequence for caller
     * bookkeeping/telemetry. A controller must fence its result callbacks on a
     * token captured BEFORE this call (see BbcInjectionToggleController.onToggle),
     * because an inline consumer can deliver the result before we return.
     * Never blocks on the consumer; superseded
     * CONFLATED intents simply never get a callback. This is the ONLY submit
     * overload production code may call.
     *
     * P1-3: the accepted intent is recorded app-scoped BEFORE enqueueing (inside
     * the same lock) so a rebuilt View can project it while the commit is still
     * in flight (or after an Unknown outcome) instead of replaying a stale
     * Confirmed effect.
     */
    fun submit(value: Boolean, onResult: (BbcWriteOutcome) -> Unit): Long =
        synchronized(enqueueLock) {
            val seq = state.nextSequence()
            afterBbcSequenceAllocatedForTests?.invoke(seq)
            state.noteToggleIntent(seq, value)
            writes.trySend(Work.Toggle(seq, value, onResult))
            seq
        }

    /**
     * TEST-ONLY / explicit-sequence overload, kept for back-compat and for the
     * #81 race test that deliberately allocates in one order and submits in the
     * opposite order. It does NOT allocate a sequence and does NOT take
     * [enqueueLock]; the pre-commit fence above is what keeps stale explicit
     * sequences harmless. Production callers must use [submit] above — the
     * explicit name makes any accidental production use obvious in review.
     */
    @androidx.annotation.VisibleForTesting
    fun submitWithExplicitSequenceForTests(
        sequence: Long,
        value: Boolean,
        onResult: (BbcWriteOutcome) -> Unit,
    ) {
        writes.trySend(Work.Toggle(sequence, value, onResult))
    }

    /**
     * Schedule the pre-activation opt-in write on the app-scoped IO lane.
     * [sequence] is the dialog intent sequence; [generation] the service
     * generation captured at user-intent time. Both are fenced by the holder.
     */
    fun submitConsentWrite(
        sequence: Long,
        generation: Long,
        onResult: (BbcWriteOutcome) -> Unit,
    ) {
        writes.trySend(Work.ConsentWrite(sequence, generation, onResult))
    }

    /** Schedule an authoritative durable read/resync on the app-scoped IO lane. */
    fun submitConsentRead(onResult: (BbcConsentAuthoritativeRead) -> Unit = {}) {
        writes.trySend(Work.ConsentRead(onResult))
    }

    /** Last user-intent sequence issued in this app process (for tests/telemetry). */
    fun latestSequence(): Long = state.latestSequence()

    /** Test seam: the app-lifetime consumer must survive controller disposal. */
    internal fun isConsumerActive(): Boolean = consumer.isActive

    /** Test seam: how many dequeued writes the sequence fence rejected before commit. */
    internal fun skippedWrites(): Int = skippedWrites
}
