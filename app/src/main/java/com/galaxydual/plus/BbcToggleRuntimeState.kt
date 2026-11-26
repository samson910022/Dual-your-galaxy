// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 3 / R3-1 + D05: app-process-local holder for the BBC toggle's
// latest KNOWN write outcome. Framework-free so JVM tests can exercise it
// directly.
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-process-local record of the BBC opt-out toggle's last write outcome.
 *
 * `confirmedOrUnknown == null` means UNKNOWN (a failed commit): it must never
 * be interpreted as "restored to the old value". The value here is only what
 * THIS app process last saw confirmed, not a re-read of the persisted source.
 *
 * This holder is scoped to the Application process, NOT persisted. After an
 * app restart it is reinitialized from the persisted preference (default true
 * on read failure). A dialog/Activity rebuild must reuse the app-scoped
 * instance so [nextSequence] stays monotonic across rebuilds.
 *
 * This is NOT the hook process's `bbcConfirmedOffAtMs`, which governs BBC gate
 * decisions when the hook cannot read the preference. It is also NOT a
 * deletion guard: the BBC opt-out does not protect against Samsung's cold-start
 * deletion (issue #75).
 *
 * All methods are thread-safe and never throw: [mark] advances the sequence
 * fence and the confirmed/unknown value together via a single compare-and-set
 * over an immutable state snapshot, so concurrent marks cannot let an older
 * sequence's value win.
 *
 * @param initialConfirmed **Provisional UI seed only** — the value read from
 *   `isBbcInjectionEnabled()` at construction. It is NOT a write confirmation
 *   and must not be treated as acknowledged until the first
 *   `mark(Confirmed(...))` for a submitted intent. A read of the preference
 *   cannot tell whether a prior write was acknowledged (the local mMap may be
 *   optimistically polluted, and the hook may still serve a cached value).
 */
internal class BbcToggleRuntimeState(
    initialConfirmed: Boolean? = null,
) {
    /** Immutable snapshot so the sequence fence and value can move in one CAS. */
    private data class MarkedState(val sequence: Long, val value: Boolean?)

    /** User intents issued in this process (never reset by a UI rebuild). */
    private val sequence = AtomicLong(0L)

    /**
     * Highest applied sequence and confirmed/unknown value held together.
     * Keeping both in one [AtomicReference] lets [mark] advance them atomically:
     * a concurrent older mark can never overwrite a newer sequence's value.
     */
    private val state = AtomicReference(MarkedState(0L, initialConfirmed))

    /**
     * E04: read-only, app-scoped projection of the last APPLIED mark for the UI.
     * Seeded with [BbcEffectStatus.Provisional] so "Confirmed" still literally
     * means a mark(Confirmed) happened. StateFlow replays the current value to a
     * newly subscribed dialog, so a rebuilt dialog learns the outcome of an
     * in-flight app-scoped commit; a live dialog sees every later mark.
     *
     * Emitted ONLY from [mark] (via [applyOutcome]), never from submit()/tap
     * paths: D05's global serialization stays the single ordering authority.
     */
    private val _effectStatus = MutableStateFlow<BbcEffectStatus>(BbcEffectStatus.Provisional)
    val effectStatus: StateFlow<BbcEffectStatus> = _effectStatus.asStateFlow()

    /**
     * `null` means UNKNOWN. The getter reads the value from the latest state
     * successfully committed by [mark].
     */
    val confirmedOrUnknown: Boolean?
        get() = state.get().value

    /** Globally monotonic intent sequence for this app process (never resets on UI rebuild). */
    fun nextSequence(): Long = sequence.incrementAndGet()

    /**
     * The last sequence ISSUED by [nextSequence] (0 before the first intent),
     * not the last one applied by [mark]. The coordinator reads this before
     * committing (#81) to drop a write whose user intent was superseded while
     * it waited in the CONFLATED channel; [mark] separately fences the result.
     */
    fun latestSequence(): Long = sequence.get()

    /**
     * Fold a write outcome into the app-scoped state; Unknown clears confirmation.
     *
     * Sequence-aware: an outcome whose [sequence] is `<=` the highest sequence
     * already applied is ignored (a stale in-flight commit must not overwrite
     * a newer intent's outcome). The CAS over the combined sequence+value state
     * keeps two concurrent marks from letting an older sequence's value win.
     *
     * @return true iff this outcome was applied; the caller uses that to decide
     *   whether to deliver the result callback.
     */
    fun mark(outcome: BbcWriteOutcome, sequence: Long): Boolean =
        applyOutcome(outcome, sequence)

    /**
     * Compatibility overload for legacy callers with no intent sequence. It
     * applies unconditionally and does NOT advance the sequence fence (so
     * [nextSequence] stays meaningful for the global writer), while still
     * folding the value in via the same atomic CAS.
     *
     * @return always true.
     */
    fun mark(outcome: BbcWriteOutcome): Boolean = applyOutcome(outcome, null)

    /**
     * Single CAS applier shared by both [mark] overloads: the sequence fence
     * and value still move together in the existing atomic state, and only a
     * CAS winner publishes the UI projection. A stale outcome returns false
     * before publishing, so a late/duplicate failure can never grey out the UI
     * and a late success can never falsely clear it.
     *
     * @param targetSequence non-null for the sequence-aware overload; `null`
     *   for the legacy overload, which leaves the fence untouched.
     */
    @Synchronized // keep the CAS state and the UI projection in one critical section
    private fun applyOutcome(outcome: BbcWriteOutcome, targetSequence: Long?): Boolean {
        val value = when (outcome) {
            is BbcWriteOutcome.Confirmed -> outcome.value
            BbcWriteOutcome.Unknown -> null
        }
        val status = when (outcome) {
            is BbcWriteOutcome.Confirmed -> BbcEffectStatus.Confirmed(outcome.value)
            BbcWriteOutcome.Unknown -> BbcEffectStatus.Unknown
        }
        while (true) {
            val cur = state.get()
            val next = if (targetSequence == null) {
                MarkedState(cur.sequence, value)
            } else {
                if (targetSequence <= cur.sequence) return false
                MarkedState(targetSequence, value)
            }
            if (state.compareAndSet(cur, next)) {
                _effectStatus.value = status
                return true
            }
        }
    }
}
