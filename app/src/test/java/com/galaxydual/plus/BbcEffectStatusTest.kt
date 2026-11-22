// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 4 / E04: framework-free JVM tests for the app-scoped effect
// status projection. No Robolectric, no wall-clock waits: the collector runs on
// Dispatchers.Unconfined so StateFlow replay/emission is observed synchronously.
// DEVICE-UNVERIFIED: real View styling in MainActivity.renderEffect is not exercised.
package com.galaxydual.plus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BbcEffectStatusTest {

    @Test
    fun holderStatusFlow_replaysThenEmitsUnknownThenConfirmed() = runTest {
        val holder = BbcToggleRuntimeState(initialConfirmed = true)
        val statuses = mutableListOf<BbcEffectStatus>()
        val collector = launch(Dispatchers.Unconfined) {
            holder.effectStatus.collect { statuses.add(it) }
        }

        // A new subscriber always sees the Provisional seed first; the initial
        // preference read is NOT a confirmation.
        assertEquals(listOf<BbcEffectStatus>(BbcEffectStatus.Provisional), statuses)

        assertTrue(holder.mark(BbcWriteOutcome.Unknown, holder.nextSequence()))
        assertEquals(
            listOf<BbcEffectStatus>(BbcEffectStatus.Provisional, BbcEffectStatus.Unknown),
            statuses,
        )
        assertNull(holder.confirmedOrUnknown)

        // A later successful Confirmed clears the "effect unknown" styling.
        assertTrue(holder.mark(BbcWriteOutcome.Confirmed(true), holder.nextSequence()))
        assertEquals(
            listOf<BbcEffectStatus>(
                BbcEffectStatus.Provisional,
                BbcEffectStatus.Unknown,
                BbcEffectStatus.Confirmed(true),
            ),
            statuses,
        )
        assertEquals(true, holder.confirmedOrUnknown)
        assertTrue(collector.isActive)
        collector.cancel()
    }

    @Test
    fun holderStatusFlow_staleOutcomeEmitsNothing() = runTest {
        val holder = BbcToggleRuntimeState(initialConfirmed = true)
        val statuses = mutableListOf<BbcEffectStatus>()
        val collector = launch(Dispatchers.Unconfined) {
            holder.effectStatus.collect { statuses.add(it) }
        }
        val seq = holder.nextSequence()
        assertTrue(holder.mark(BbcWriteOutcome.Unknown, seq))
        val afterCurrentFailure =
            listOf<BbcEffectStatus>(BbcEffectStatus.Provisional, BbcEffectStatus.Unknown)
        assertEquals(afterCurrentFailure, statuses)

        // A stale in-flight failure (same or older sequence) returns false and
        // must NOT publish a status: it cannot grey the UI after a newer result.
        assertFalse(holder.mark(BbcWriteOutcome.Unknown, seq))
        assertFalse(holder.mark(BbcWriteOutcome.Unknown, seq - 1))
        // A stale success is equally silent: no false "confirmed" either.
        assertFalse(holder.mark(BbcWriteOutcome.Confirmed(false), seq))
        assertEquals(afterCurrentFailure, statuses)
        assertNull(holder.confirmedOrUnknown)
        collector.cancel()
    }

    @Test
    fun bbcEffectPresentation_confirmedRestoresCaveatBearingSummary() {
        val unknown = bbcEffectPresentationFor(BbcEffectStatus.Unknown)
        assertEquals(R.string.settings_bbc_injection_effect_unknown, unknown.summaryRes)
        assertTrue(unknown.dimmed)
        assertTrue(unknown.italic)

        val confirmed = bbcEffectPresentationFor(BbcEffectStatus.Confirmed(false))
        assertEquals(R.string.settings_bbc_injection_summary, confirmed.summaryRes)
        assertFalse(confirmed.dimmed)
        assertFalse(confirmed.italic)

        // The seed renders normally but is still not a write confirmation.
        val provisional = bbcEffectPresentationFor(BbcEffectStatus.Provisional)
        assertEquals(R.string.settings_bbc_injection_summary, provisional.summaryRes)
        assertFalse(provisional.dimmed)
        assertFalse(provisional.italic)
    }
}
