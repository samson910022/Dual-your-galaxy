// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 3 / R3-1: app-process-local holder for the BBC toggle's latest
// KNOWN write outcome. Framework-free so JVM tests can exercise it directly.
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicLong

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
 * All methods are thread-safe and never throw.
 *
 * @param initialConfirmed confirmed preference value at construction (the
 *   persisted value on app start); null = Unknown.
 */
internal class BbcToggleRuntimeState(
    initialConfirmed: Boolean? = null,
) {
    @Volatile
    var confirmedOrUnknown: Boolean? = initialConfirmed
        private set

    private val sequence = AtomicLong(0L)

    /** Globally monotonic intent sequence for this app process (never resets on UI rebuild). */
    fun nextSequence(): Long = sequence.incrementAndGet()

    /** Fold a write outcome into the app-scoped state; Unknown clears confirmation. */
    fun mark(outcome: BbcWriteOutcome) {
        when (outcome) {
            is BbcWriteOutcome.Confirmed -> confirmedOrUnknown = outcome.value
            BbcWriteOutcome.Unknown -> confirmedOrUnknown = null
        }
    }
}
