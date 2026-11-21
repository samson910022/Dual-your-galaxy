// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 3 / R3-1 + D05: app-process-local holder for the BBC toggle's
// latest KNOWN write outcome. Framework-free so JVM tests can exercise it
// directly.
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
     * `null` means UNKNOWN. The getter reads the value from the latest state
     * successfully committed by [mark].
     */
    val confirmedOrUnknown: Boolean?
        get() = state.get().value

    /** Globally monotonic intent sequence for this app process (never resets on UI rebuild). */
    fun nextSequence(): Long = sequence.incrementAndGet()

    /** The last sequence issued by [nextSequence]; used for cross-rebuild assertions. */
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
    fun mark(outcome: BbcWriteOutcome, sequence: Long): Boolean {
        val value = when (outcome) {
            is BbcWriteOutcome.Confirmed -> outcome.value
            BbcWriteOutcome.Unknown -> null
        }
        while (true) {
            val cur = state.get()
            if (sequence <= cur.sequence) return false
            if (state.compareAndSet(cur, MarkedState(sequence, value))) return true
        }
    }

    /**
     * Compatibility overload for legacy callers with no intent sequence. It
     * applies unconditionally and does NOT advance the sequence fence (so
     * [nextSequence] stays meaningful for the global writer), while still
     * folding the value in via the same atomic CAS.
     *
     * @return always true.
     */
    fun mark(outcome: BbcWriteOutcome): Boolean {
        val value = when (outcome) {
            is BbcWriteOutcome.Confirmed -> outcome.value
            BbcWriteOutcome.Unknown -> null
        }
        while (true) {
            val cur = state.get()
            if (state.compareAndSet(cur, MarkedState(cur.sequence, value))) return true
        }
    }
}
