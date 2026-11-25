// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 3 / D05: app-scoped, cross-lifecycle serializer for BBC opt-out
// writes. Main disposed that controller and built a new one on every dialog
// rebuild / Activity destroy. A synchronous RemotePreferences commit already
// entered by the old actor is non-cancellable and could land AFTER the new
// controller's write — overwriting the user's last intent. This coordinator owns
// the ONLY writer: one app-lifetime consumer.
//
// PR #90 Phase C: the SAME single consumer also owns the consent lane
// (submitConsentWrite / submitConsentRead), so consent I/O never runs on the
// Main thread and is serialized against runtime writes by the same consumer.
//
// PR #90 Phase 2 (review A1/B3): the lanes are SEPARATE. A background
// ConsentRead is coalesced with other reads ONLY; it can never replace a queued
// user Toggle/ConsentWrite. Durable user intents live in per-axis pending slots
// with explicit sequence supersession (a NEWER same-axis intent wins; an OFF is
// never dropped by a read or by an older ON handoff). trySend/enqueue success
// now means the item will be executed — or was explicitly superseded by a newer
// same-axis intent — and the report is returned to the caller.
//
// Framework-free on purpose (only kotlinx.coroutines): JVM tests can drive it
// without Robolectric.
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result reported to a caller that enqueued work on the app IO lane. */
internal enum class BbcWorkEnqueue {
    /** Accepted: the work will run unless a NEWER intent on the SAME axis replaces it. */
    Accepted,
    /** Accepted, but an older pending same-axis intent was dropped (its onReplaced fired). */
    ReplacedOlder,
    /** Rejected: a NEWER same-axis intent already owns the pending slot. */
    SupersededByNewer,
    /** Rejected: the app-lifetime consumer is closed/cancelled, so nothing will run. */
    Rejected,
}

/**
 * Result of a generation-aware consent commit.
 *
 * [touchedGeneration] is the generation of the prefs instance the commit
 * ACTUALLY touched — never merely the generation captured when the user
 * accepted the dialog. A binder death mid-commit may make those differ; the
 * holder fences [touchedGeneration], so a newer instance can authoritatively
 * resync while the polluted instance cannot clear its own fence.
 */
internal data class BbcConsentWriteResult(
    val outcome: BbcWriteOutcome,
    val touchedGeneration: Long,
)

/**
 * Process-wide serializer for BBC preference writes and consent reads.
 *
 * **App-process-local.** Constructed ONCE for the Application lifetime and
 * shared by every [BbcInjectionToggleController], including controllers created
 * by later dialog/Activity rebuilds. ONE consumer serializes every commit on
 * [io]; no binder call ever runs on Main.
 *
 * [submit] assigns its sequence at USER-INTENT time (via
 * [BbcToggleRuntimeState.nextSequence]) AND enqueues inside ONE critical
 * section (the 2-arg [submit] overload). Allocation and enqueue MUST be atomic:
 * a caller that minted sequence N must not be descheduled and let another
 * caller mint N+1 and enqueue it first, which could let a stale lower sequence
 * displace the newer intent. The phase-2 per-axis slot additionally rejects any
 * work whose sequence is not strictly newer than the one it owns, so a minted
 * sequence and its enqueue stay paired under one lock. The explicit-sequence
 * [submit] overload is for callers that already minted the intent sequence.
 * Sequence numbers stay monotonic across rebuilds.
 *
 * #81: the pre-commit sequence fence in [executeToggle] remains as defense in
 * depth. It is what makes [submitWithExplicitSequenceForTests] (test-only) safe
 * and it protects any future caller that bypasses the atomic path. A write whose
 * sequence is older than the latest issued intent is discarded wholesale (no
 * commit, no mark, no callback), because state.mark()'s result-only fence is
 * too late to protect the persisted preference.
 *
 * Cancellation of a controller's UI must NOT cancel this consumer or an
 * in-flight commit; `dispose()` on the controller only detaches UI callbacks.
 * A [CancellationException] from a commit maps to [BbcWriteOutcome.Unknown]
 * while the APP scope is alive: killing the single lifetime consumer would
 * leave later submits Accepted-but-never-run. It is propagated ONLY when the
 * whole app scope is terminating. Every other throwable also maps to Unknown.
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
     * Legacy no-argument consent commit seam. Runs on [io] inside the SAME
     * app-lifetime consumer as [commit]. Defaults fail closed so a caller that
     * forgot to wire it can never grant consent. It cannot carry the durable
     * enable-pending marker, so resync convergence deliberately ignores reads
     * unless that marker came from the coherent snapshot. Production uses
     * [consentCommitAt]; kept only for source compatibility with existing
     * framework-free tests. New code MUST NOT use it.
     */
    @Deprecated(
        "test compatibility only; use setBbcConsentGrantDetailed / consentCommitAt",
        ReplaceWith("consentCommitAt"),
    )
    private val consentCommit: suspend () -> BbcWriteOutcome = { BbcWriteOutcome.Unknown },
    /**
     * Generation-aware consent commit seam. Receives the generation captured at
     * user-intent time and MUST report the generation actually touched. Defaults
     * to the legacy seam, assuming the captured instance was touched (the
     * conservative fail-closed interpretation for an Unknown legacy outcome).
     */
    private val consentCommitAt: suspend (Long) -> BbcConsentWriteResult = { generation ->
        BbcConsentWriteResult(consentCommit(), generation)
    },
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
    /**
     * PR #90 Phase 3 (R3-06a/E2): authoritative persisted effect-value read.
     * Always executes on [io] inside the SAME app-lifetime consumer, never on
     * Main. Returns `null` for ABSENT / unreadable / disconnected (no fabricated
     * default-true seed). The value is folded into [BbcToggleRuntimeState] as a
     * PROVISIONAL read, never as a Confirmed write acknowledgement. Defaults to
     * fail closed so a caller that forgot to wire it leaves the row neutral.
     */
    private val effectRead: suspend () -> Boolean? = { null },
) {
    /**
     * TEST-ONLY seam (mirrors GalaxyApp.bbcClassificationPrefsProvider): invoked
     * with the freshly minted effect-axis sequence by the two consumer-owned
     * enable paths, AFTER the mint and BEFORE any OFF-authority check. Production
     * leaves it null. A test suspends here to force a user OFF to be recorded
     * and enqueued strictly after the mint; the enable must then lose.
     */
    internal var effectSequenceMintHook: (suspend (Long) -> Unit)? = null

    private sealed interface Work {
        data class Toggle(
            val sequence: Long,
            val value: Boolean,
            val onResult: (BbcWriteOutcome) -> Unit,
            val onReplaced: () -> Unit,
        ) : Work

        /** The opt-in grant write. Sequence/generation come from the app holder. */
        data class ConsentWrite(
            val sequence: Long,
            val generation: Long,
            val onResult: (BbcWriteOutcome) -> Unit,
            val onReplaced: () -> Unit,
        ) : Work

        /** Bind/resync read. Never called from an OFF toggle path. */
        data class ConsentRead(
            val onResult: (BbcConsentAuthoritativeRead) -> Unit,
            val onCoalesced: () -> Unit,
        ) : Work

        /**
         * PR #90 Phase 3: authoritative persisted EFFECT read. Separate from
         * [ConsentRead] on purpose — a consent resync must not fabricate a
         * write acknowledgement, and a UI effect read must not disturb the
         * consent holder's generation/fence resolution.
         */
        data class EffectRead(
            val onResult: (Boolean?) -> Unit,
            val onCoalesced: () -> Unit,
        ) : Work
    }

    // Per-axis pending slots. A newer intent of the SAME axis may replace the
    // older one (its onReplaced is invoked, so no caller ever assumes it ran).
    // No CROSS-axis replacement is possible: a read can never evict an OFF or a
    // consent grant, and a consent write can never evict a queued toggle.
    private val slotLock = Any()
    private val pendingToggle = AtomicReference<Work.Toggle?>(null)
    private val pendingConsentWrite = AtomicReference<Work.ConsentWrite?>(null)
    private val pendingRead = AtomicReference<Work.ConsentRead?>(null)
    private val pendingEffectRead = AtomicReference<Work.EffectRead?>(null)

    // CONFLATED wake-up only: after every signal the consumer drains ALL lanes
    // (user work first, reads last, then the effect read). A signal arriving
    // during draining is buffered, so no work is lost.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // #81 atomic-submit lane: the 2-arg submit() mints its sequence and enqueues
    // its Work here INSIDE enqueueLock, so mint+enqueue are one critical section.
    // The phase-2 per-axis slots below carry the explicit-sequence overloads.
    // Both paths are drained by the SAME single consumer below, so all commits
    // remain serialized process-wide.
    private val writes = Channel<Work.Toggle>(Channel.CONFLATED)

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
        for (unit in wake) {
            drainUserWork()
            drainRead()
            drainEffectRead()
        }
    }

    private suspend fun drainUserWork() {
        while (true) {
            // Pop ONE lane at a time: OFF/high-priority toggles run first, but
            // a consent write that was pending alongside a toggle must still be
            // executed in this same drain (not lost on `continue`).
            val toggle = synchronized(slotLock) { pendingToggle.getAndSet(null) }
            if (toggle != null) {
                executeToggle(toggle)
                continue
            }
            // #81 atomic-submit lane: CONFLATED keeps only the newest minted
            // intent; the fence in executeToggle drops anything older.
            val atomicToggle = writes.tryReceive().getOrNull()
            if (atomicToggle != null) {
                executeToggle(atomicToggle)
                continue
            }
            val consentWrite = synchronized(slotLock) { pendingConsentWrite.getAndSet(null) }
            if (consentWrite != null) {
                executeConsentWrite(consentWrite)
                continue
            }
            return
        }
    }

    private suspend fun drainRead() {
        val read = pendingRead.getAndSet(null) ?: return
        val generation = consentHolder.currentGeneration()
        val result = try {
            withContext(io) { consentRead(generation) }
        } catch (ce: CancellationException) {
            // Only a terminating APP scope may kill the single lifetime
            // consumer. A seam-level interruption is not an outcome, but it
            // must not leave the consumer dead with queued work stranded.
            if (!scope.isActive) throw ce
            BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
        } catch (_: Throwable) {
            BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
        }
        // Resolve on the IO consumer thread: no Main-thread binder read, and a
        // local mMap value can never clear the fence (the holder enforces the
        // generation rule).
        consentHolder.resolve(result)
        // Phase-2 convergence: a fresh process / rebind with durable opt-in=true
        // but runtime=false must write the effect enable without waiting for a
        // UI callback or a state-flow replay. Claimed once per generation.
        maybeDriveResyncEnable(generation, result.enablePending == true)
        try {
            read.onResult(result)
        } catch (_: Throwable) {
            // A UI callback must never kill the process-wide consumer.
        }
    }

    /**
     * PR #90 Phase 3: run the authoritative persisted-effect read on [io].
     * The result is a PROVISIONAL seed only — [BbcToggleRuntimeState] records it
     * without advancing the intent fence and without emitting Confirmed, so no
     * binder read ever happens on Main and no read impersonates a write
     * acknowledgement. The consent holder is untouched (its own [drainRead]
     * resolves authorization from the coherent snapshot).
     */
    private suspend fun drainEffectRead() {
        val read = pendingEffectRead.getAndSet(null) ?: return
        val value = try {
            withContext(io) { effectRead() }
        } catch (ce: CancellationException) {
            if (!scope.isActive) throw ce
            null
        } catch (_: Throwable) {
            null
        }
        state.seedEffectFromPersistedRead(value)
        try {
            read.onResult(value)
        } catch (_: Throwable) {
            // A UI callback must never kill the process-wide consumer.
        }
    }

    private suspend fun executeToggle(work: Work.Toggle) {
        // #81: pre-commit sequence fence (defense in depth). A write whose
        // sequence is older than the latest issued intent must never reach the
        // non-cancellable commit — state.mark()'s result-only fence is too late
        // to protect the persisted preference. This is the TOGGLE sequence
        // domain only; consent writes are fenced by consentHolder. Skipped
        // writes commit/mark/callback nothing.
        if (work.sequence < state.latestSequence()) {
            skippedWrites++
            return
        }

        val outcome = try {
            // A synchronous, non-cancellable commit is never abandoned
            // mid-call by UI disposal: the app scope outlives every UI.
            withContext(io) { commit(work.value) }
        } catch (ce: CancellationException) {
            // A commit seam may throw CancellationException without the APP
            // scope terminating (binder interruption / a caller's scope). Do
            // NOT kill the single app-lifetime consumer — that would leave
            // later submits Accepted-but-never-run. Report honest Unknown and
            // keep draining. Propagate ONLY when the whole app scope is ending.
            if (!scope.isActive) throw ce
            BbcWriteOutcome.Unknown
        } catch (_: Throwable) {
            BbcWriteOutcome.Unknown
        }

        // mark() is sequence-aware: an outcome older than one already applied
        // is ignored. Defensive — the single consumer already serializes in
        // submit order — but it also covers a multi-threaded caller racing
        // nextSequence() against submit().
        val applied = state.mark(outcome, work.sequence)
        if (!applied) return
        try {
            work.onResult(outcome)
        } catch (_: Throwable) {
            // A UI callback must never kill the process-wide consumer.
        }
    }

    private suspend fun executeConsentWrite(work: Work.ConsentWrite) {
        // P1-A: authority is checked at EXECUTION time, not only at submit time.
        // The toggle lane is drained FIRST, so an OFF queued after accept() but
        // before this drain has already superseded/abandoned the grant. It MUST
        // not enter the store: the atomic optin=true + marker=true commit would
        // re-arm a cold-start enable after that OFF cleared the marker.
        if (!consentHolder.isConsentWriteStillAuthoritative(work.sequence, work.generation)) {
            // No durability evidence exists — markConsentWriteOutcome treats
            // Skipped as a no-op — but the caller still gets an honest result.
            consentHolder.markConsentWriteOutcome(
                BbcWriteOutcome.Skipped, work.sequence, work.generation,
                consumerOwnsEnable = true,
            )
            try {
                work.onResult(BbcWriteOutcome.Skipped)
            } catch (_: Throwable) {
                // A UI callback must never kill the process-wide consumer.
            }
            return
        }
        val result = try {
            withContext(io) { consentCommitAt(work.generation) }
        } catch (ce: CancellationException) {
            // Same rule as the effect lane: only a terminating APP scope may
            // kill the consumer. Otherwise the interrupted commit is Unknown
            // and its instance is still fenced (fail closed).
            if (!scope.isActive) throw ce
            BbcConsentWriteResult(BbcWriteOutcome.Unknown, work.generation)
        } catch (_: Throwable) {
            // A non-cancellation throw may have polluted the local mMap: fence
            // the instance the captured intent was addressing (fail closed).
            BbcConsentWriteResult(BbcWriteOutcome.Unknown, work.generation)
        }
        // The durable fence / grant is recorded HERE, on the app IO lane, before
        // any UI callback sees the outcome. consumerOwnsEnable=true makes the
        // holder consume the pending UI handoff and remember that the app
        // consumer (not an Activity callback) owns the phase-2 effect write.
        val applied = consentHolder.markConsentWriteOutcome(
            result.outcome, work.sequence, result.touchedGeneration,
            consumerOwnsEnable = true,
        )
        if (!applied) return
        try {
            work.onResult(result.outcome)
        } catch (_: Throwable) {
            // A UI callback must never kill the process-wide consumer.
        }
        // Phase-2 enable: the SAME consumer that confirmed the opt-in key also
        // converges the runtime opt-out preference. Exactly-once and OFF-safe.
        if (result.outcome is BbcWriteOutcome.Confirmed) {
            driveConsumerEnable(work.sequence, result.touchedGeneration)
        }
    }

    /**
     * Atomic user-intent submit: allocate the sequence AND enqueue in one
     * critical section, then return the allocated sequence for caller
     * bookkeeping/telemetry. A controller must fence its result callbacks on a
     * token captured BEFORE this call (see BbcInjectionToggleController.onToggle),
     * because an inline consumer can deliver the result before we return.
     * Never blocks on the consumer; superseded intents simply never get a
     * callback. Mint + enqueue under [enqueueLock] is the #81 atomicity
     * invariant; [enqueueToggle]'s sequence-aware slot is the phase-2 lane rule.
     */
    fun submit(value: Boolean, onResult: (BbcWriteOutcome) -> Unit): Long =
        synchronized(enqueueLock) {
            val seq = state.nextSequence()
            afterBbcSequenceAllocatedForTests?.invoke(seq)
            state.noteToggleIntent(seq, value)
            writes.trySend(Work.Toggle(seq, value, onResult, {}))
            wake.trySend(Unit) // same single consumer drains the atomic lane
            seq
        }

    /**
     * TEST-ONLY / explicit-sequence overload, kept for the #81 race test that
     * deliberately allocates in one order and submits in the opposite order.
     * It does NOT mint a sequence and does NOT take [enqueueLock]; the
     * pre-commit fence in [executeToggle] keeps stale explicit sequences
     * harmless. Production callers must use the atomic [submit] above.
     */
    @androidx.annotation.VisibleForTesting
    fun submitWithExplicitSequenceForTests(
        sequence: Long,
        value: Boolean,
        onResult: (BbcWriteOutcome) -> Unit,
    ) {
        enqueueToggle(sequence, value, onResult, {})
    }

    /**
     * Enqueue the runtime ENABLE on the consumer's own authority. [reason] is
     * in the app-scoped intent sequence domain, so a newer OFF (higher sequence,
     * or an abandon fence) always wins. [generation] is the instance the grant
     * actually touched: enable ownership is generation-scoped so a rebind can
     * claim the same still-owed enable once without duplicating it here. The
     * same-axis slot rule additionally rejects this handoff if a newer toggle
     * is already pending.
     */
    private suspend fun driveConsumerEnable(reason: Long, generation: Long): Boolean {
        // Mint the effect-axis sequence FIRST, before any authority check. The
        // holder sequence identifies the AUTHORISATION intent (for OFF
        // comparison) while the effect state has its own mark fence; in
        // production both counters are shared, so every OFF recorded from here
        // on takes a strictly newer number. That OFF either fails the abandon
        // check below or owns the same-axis slot, so the enable can never
        // displace it. Burning a number on a rejected attempt is harmless: the
        // counter is monotonic and gaps have no semantic meaning.
        val effectSequence = state.nextSequence()
        effectSequenceMintHook?.invoke(effectSequence)
        // STRICT ownership: a resync may have claimed a NEWER enable token R.
        // `isEnableHandledBy(S)` would still be true for S<R and would enqueue
        // a second enable; only the exact current owner may drive one.
        if (!consentHolder.isEnableOwner(reason, generation)) return false
        if (consentHolder.latestAbandonSequence() > reason) return false
        if (state.latestIntentProjectedValue() == false) return false
        // ReplacedOlder is still a committed enable: an older pending ON was
        // explicitly superseded by this newer, same-axis handoff. Only a newer
        // intent that already owns the slot rejects the enable.
        return when (enqueueToggle(effectSequence, true, {}, {})) {
            BbcWorkEnqueue.Accepted, BbcWorkEnqueue.ReplacedOlder -> true
            BbcWorkEnqueue.SupersededByNewer, BbcWorkEnqueue.Rejected -> false
        }
    }

    private suspend fun maybeDriveResyncEnable(generation: Long, enablePending: Boolean) {
        // Mint the effect-axis sequence FIRST for the same OFF-wins reason as
        // driveConsumerEnable: an OFF recorded afterwards has a newer sequence
        // and cannot be displaced by this enable. A rejected claim simply burns
        // a monotonic number, which is harmless.
        val effectSequence = state.nextSequence()
        effectSequenceMintHook?.invoke(effectSequence)
        // Cold-start convergence requires the APP-ONLY durable marker from the
        // SAME coherent snapshot as optin=true. optin=true alone is NOT enough:
        // a durable user OFF only writes the effect pref, and the OFF cleared
        // this marker in the same commit. Without the marker a cold-start
        // resync would silently flip the user's OFF back ON.
        if (!enablePending) return
        // A confirmed effect is already authoritative: Confirmed(true) needs no
        // second enable, Confirmed(false) is a user OFF that must never be
        // overridden by a background resync.
        if (state.effectStatus.value is BbcEffectStatus.Confirmed) return
        if (state.latestIntentProjectedValue() == false) return
        // The OFF/cancel comparison uses the GRANT's intent sequence, not a
        // freshly minted one (which would outrank every recorded abandon).
        // The claim token is fresh and monotonic so a later Confirmed S cannot
        // regress a resync-owned enable R into a second write.
        val reason = consentHolder.confirmedGrantSequence()
        val claim = consentHolder.nextIntentSequence()
        if (!consentHolder.tryClaimResyncEnable(generation, reason, enablePending, claim)) return
        enqueueToggle(effectSequence, true, {}, {})
    }

    private fun enqueueToggle(
        sequence: Long,
        value: Boolean,
        onResult: (BbcWriteOutcome) -> Unit,
        onReplaced: () -> Unit,
    ): BbcWorkEnqueue {
        // Callbacks are invoked AFTER leaving slotLock: they are foreign code
        // and must never be able to re-enter this serializer while it holds
        // the slot invariant. Callers must not rely on re-entrant enqueueing
        // from within onReplaced/onCoalesced.
        var displaced: (() -> Unit)? = null
        val result = synchronized(slotLock) {
            val old = pendingToggle.get()
            if (old != null && old.sequence >= sequence) {
                BbcWorkEnqueue.SupersededByNewer
            } else {
                val work = Work.Toggle(sequence, value, onResult, onReplaced)
                pendingToggle.set(work)
                // Accepted must mean SOMETHING WILL RUN. A closed channel or a
                // dead consumer invalidates the promise: restore the slot and
                // report Rejected. No replaced callback fires (nothing was
                // displaced). The slotLock guard keeps the consumer from
                // popping the slot between set and wake.
                if (!wakeConsumer()) {
                    pendingToggle.compareAndSet(work, old)
                    BbcWorkEnqueue.Rejected
                } else {
                    state.noteToggleIntent(sequence, value)
                    if (old == null) BbcWorkEnqueue.Accepted else {
                        displaced = old.onReplaced
                        BbcWorkEnqueue.ReplacedOlder
                    }
                }
            }
        }
        displaced?.let(::notifyReplaced)
        return result
    }

    private fun enqueueConsentWrite(
        sequence: Long,
        generation: Long,
        onResult: (BbcWriteOutcome) -> Unit,
        onReplaced: () -> Unit,
    ): BbcWorkEnqueue {
        var displaced: (() -> Unit)? = null
        val result = synchronized(slotLock) {
            val old = pendingConsentWrite.get()
            if (old != null && old.sequence >= sequence) {
                BbcWorkEnqueue.SupersededByNewer
            } else {
                val work = Work.ConsentWrite(sequence, generation, onResult, onReplaced)
                pendingConsentWrite.set(work)
                if (!wakeConsumer()) {
                    pendingConsentWrite.compareAndSet(work, old)
                    BbcWorkEnqueue.Rejected
                } else if (old == null) {
                    BbcWorkEnqueue.Accepted
                } else {
                    displaced = old.onReplaced
                    BbcWorkEnqueue.ReplacedOlder
                }
            }
        }
        displaced?.let(::notifyReplaced)
        return result
    }

    /**
     * A wake-up is only a promise when the single lifetime consumer is still
     * running and the CONFLATED channel accepted the signal. Both conditions
     * are checked before any enqueue reports Accepted/ReplacedOlder.
     */
    private fun wakeConsumer(): Boolean =
        consumer.isActive && wake.trySend(Unit).isSuccess

    private fun notifyReplaced(callback: () -> Unit) {
        try {
            callback()
        } catch (_: Throwable) {
            // A replaced caller's bookkeeping must never break the writer.
        }
    }

    /**
     * Never blocks. [BbcWorkEnqueue] tells the caller whether this intent will
     * run, replaced an older same-axis intent, was rejected because a newer
     * same-axis intent owns the slot, or was rejected because the app-lifetime
     * consumer is gone. P1-3: the accepted intent is
     * recorded app-scoped BEFORE enqueueing so a rebuilt View can project it
     * while the commit is still in flight (or after an Unknown outcome) instead
     * of replaying a stale Confirmed effect.
     */
    fun submit(
        sequence: Long,
        value: Boolean,
        onReplaced: () -> Unit = {},
        onResult: (BbcWriteOutcome) -> Unit,
    ): BbcWorkEnqueue = enqueueToggle(sequence, value, onResult, onReplaced)

    /**
     * Schedule the pre-activation opt-in write on the app-scoped IO lane.
     * [sequence] is the dialog intent sequence; [generation] the service
     * generation captured at user-intent time. Both are fenced by the holder.
     */
    fun submitConsentWrite(
        sequence: Long,
        generation: Long,
        onReplaced: () -> Unit = {},
        onResult: (BbcWriteOutcome) -> Unit,
    ): BbcWorkEnqueue = enqueueConsentWrite(sequence, generation, onResult, onReplaced)

    /**
     * Schedule an authoritative durable read/resync on the app-scoped IO lane.
     * Reads coalesce with OTHER READS ONLY: a queued user toggle or consent
     * write is never touched, and the displaced read's callback is reported via
     * [onCoalesced] rather than an outcome callback.
     */
    fun submitConsentRead(
        onCoalesced: () -> Unit = {},
        onResult: (BbcConsentAuthoritativeRead) -> Unit = {},
    ): BbcWorkEnqueue {
        var coalesced: (() -> Unit)? = null
        val result = synchronized(slotLock) {
            val old = pendingRead.get()
            val work = Work.ConsentRead(onResult, onCoalesced)
            pendingRead.set(work)
            if (!wakeConsumer()) {
                pendingRead.compareAndSet(work, old)
                BbcWorkEnqueue.Rejected
            } else if (old == null) {
                BbcWorkEnqueue.Accepted
            } else {
                coalesced = old.onCoalesced
                BbcWorkEnqueue.ReplacedOlder
            }
        }
        coalesced?.let(::notifyReplaced)
        return result
    }

    /**
     * PR #90 Phase 3: schedule the authoritative persisted-effect read on the
     * app IO lane (never a Main-thread binder call). Reads coalesce with OTHER
     * effect reads only; a displaced read's callback is reported via
     * [onCoalesced]. User writes are never touched.
     */
    fun submitEffectRead(
        onCoalesced: () -> Unit = {},
        onResult: (Boolean?) -> Unit = {},
    ): BbcWorkEnqueue {
        var coalesced: (() -> Unit)? = null
        val result = synchronized(slotLock) {
            val old = pendingEffectRead.get()
            val work = Work.EffectRead(onResult, onCoalesced)
            pendingEffectRead.set(work)
            if (!wakeConsumer()) {
                pendingEffectRead.compareAndSet(work, old)
                BbcWorkEnqueue.Rejected
            } else if (old == null) {
                BbcWorkEnqueue.Accepted
            } else {
                coalesced = old.onCoalesced
                BbcWorkEnqueue.ReplacedOlder
            }
        }
        coalesced?.let(::notifyReplaced)
        return result
    }

    /** Last user-intent sequence issued in this app process (for tests/telemetry). */
    fun latestSequence(): Long = state.latestSequence()

    /** Test seam: the app-lifetime consumer must survive controller disposal. */
    internal fun isConsumerActive(): Boolean = consumer.isActive

    /** Test seam: how many dequeued writes the sequence fence rejected before commit. */
    internal fun skippedWrites(): Int = skippedWrites
}
