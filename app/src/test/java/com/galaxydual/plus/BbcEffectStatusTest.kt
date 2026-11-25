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

    /**
     * BbcWriteOutcome.Skipped means NO durable key was touched and NO pollution
     * evidence exists. It must be a true no-op on the effect lane: no sequence
     * fence advance, no erasure of a prior Confirmed mark, and no _effectStatus
     * emission (an Unknown emission would show a false "effect unverified" toast).
     *
     * The same-sequence Confirmed(false) below is the deterministic fence probe:
     * it can only apply if Skipped left the applied fence at seq1.
     */
    @Test
    fun skippedOutcome_isTrueNoOpAndDoesNotPoisonFence() = runTest {
        val holder = BbcToggleRuntimeState(initialConfirmed = true)
        val statuses = mutableListOf<BbcEffectStatus>()
        val collector = launch(Dispatchers.Unconfined) {
            holder.effectStatus.collect { statuses.add(it) }
        }

        assertTrue(holder.mark(BbcWriteOutcome.Confirmed(true), 1L))
        val afterConfirmed = listOf<BbcEffectStatus>(BbcEffectStatus.Provisional, BbcEffectStatus.Confirmed(true))
        assertEquals(afterConfirmed, statuses)

        // seq2 is a skipped/superseded intent: nothing durable happened.
        assertTrue("Skipped reports applied so the enqueuing caller still gets its callback",
            holder.mark(BbcWriteOutcome.Skipped, 2L))
        assertEquals("mark() never mints an issued sequence", 0L, holder.latestSequence())
        assertEquals("Skipped must not erase a prior confirmation", true, holder.confirmedOrUnknown)
        assertEquals("Skipped must emit no status at all", afterConfirmed, statuses)

        // Fence probe: seq2 still applies; a poisoned fence at seq2 would drop it.
        assertTrue("a later Confirmed at the skipped sequence must still apply",
            holder.mark(BbcWriteOutcome.Confirmed(false), 2L))
        assertEquals(false, holder.confirmedOrUnknown)
        assertEquals(afterConfirmed + BbcEffectStatus.Confirmed(false), statuses)

        // A genuinely newer sequence is applied normally as well.
        assertTrue(holder.mark(BbcWriteOutcome.Confirmed(false), 3L))
        assertEquals(0L, holder.latestSequence())
        collector.cancel()
    }

    /**
     * PR #90 Phase 3 (R3-06a/E2): an authoritative persisted value read on the
     * IO lane supplies the checkbox value but is NEVER a write acknowledgement.
     * It must not emit Confirmed, must not move the write-outcome fence, and a
     * later real Confirmed mark always wins (a late read cannot downgrade it).
     */
    @Test
    fun provisionalPersistedRead_isNeutralSeedNotAWriteConfirmation() = runTest {
        val holder = BbcToggleRuntimeState() // production seed: NEUTRAL
        val statuses = mutableListOf<BbcEffectStatus>()
        val collector = launch(Dispatchers.Unconfined) {
            holder.effectStatus.collect { statuses.add(it) }
        }
        assertEquals(listOf<BbcEffectStatus>(BbcEffectStatus.Provisional), statuses)
        assertNull(holder.confirmedOrUnknown)

        // The IO read lands: the row may show the persisted value, but the
        // status stays PROVISIONAL (unverified) and no write is acknowledged.
        holder.seedEffectFromPersistedRead(false)
        assertEquals(
            listOf<BbcEffectStatus>(BbcEffectStatus.Provisional, BbcEffectStatus.ProvisionalRead(false)),
            statuses,
        )
        assertNull("a read is not a Confirmed write outcome", holder.confirmedOrUnknown)
        assertEquals("a read must not advance the intent fence", 0L, holder.latestSequence())

        // Phase 2 projection ordering is preserved: the provisional value fills
        // the neutral tier, but an app-scoped user intent still wins over it.
        val neutral = bbcToggleUiProjection(
            BbcActivationConsentState.Initializing, holder.effectStatus.value, null,
        )
        assertEquals(false, neutral.checked)
        assertTrue("an IO read alone is still neutral/unverified", neutral.neutral)
        val withIntent = bbcToggleUiProjection(
            BbcActivationConsentState.Granted, holder.effectStatus.value, true,
        )
        assertEquals(true, withIntent.checked)

        // A real Confirmed mark overrides the provisional read, and a later
        // read of the same preference cannot downgrade it back.
        assertTrue(holder.mark(BbcWriteOutcome.Confirmed(true), holder.nextSequence()))
        assertEquals(BbcEffectStatus.Confirmed(true), holder.effectStatus.value)
        holder.seedEffectFromPersistedRead(false)
        assertEquals("a late read must never downgrade a Confirmed write",
            BbcEffectStatus.Confirmed(true), holder.effectStatus.value)
        collector.cancel()
    }

    /** ABSENT / unreadable reads supply no value (no default-true fabrication). */
    @Test
    fun provisionalPersistedRead_nullKeepsNeutral() = runTest {
        val holder = BbcToggleRuntimeState()
        holder.seedEffectFromPersistedRead(null)
        assertEquals(BbcEffectStatus.Provisional, holder.effectStatus.value)
        assertNull(
            bbcToggleUiProjection(
                BbcActivationConsentState.NotGranted, holder.effectStatus.value, null,
            ).checked,
        )
    }

    @Test
    fun bbcEffectPresentation_unconfirmedRendersUnverifiedConfirmedKeepsCaveat() {
        val unknown = bbcEffectPresentationFor(BbcEffectStatus.Unknown)
        assertEquals(R.string.settings_bbc_injection_effect_unknown, unknown.summaryRes)
        assertTrue(unknown.dimmed)
        assertTrue(unknown.italic)

        // A fresh/unconfirmed process is UNVERIFIED, never a confirmation: the
        // Provisional seed renders identically to Unknown (dimmed + italic).
        val provisional = bbcEffectPresentationFor(BbcEffectStatus.Provisional)
        assertEquals(R.string.settings_bbc_injection_effect_unknown, provisional.summaryRes)
        assertTrue(provisional.dimmed)
        assertTrue(provisional.italic)

        // An IO-lane persisted read renders IDENTICALLY to unverified: a read
        // is not a write acknowledgement and never claims the effect is live.
        val provisionalRead = bbcEffectPresentationFor(BbcEffectStatus.ProvisionalRead(true))
        assertEquals(R.string.settings_bbc_injection_effect_unknown, provisionalRead.summaryRes)
        assertTrue(provisionalRead.dimmed)
        assertTrue(provisionalRead.italic)

        // Confirmed keeps its caveat-bearing summary (a persisted preference
        // acknowledgement only, NOT an effect-success claim).
        val confirmed = bbcEffectPresentationFor(BbcEffectStatus.Confirmed(false))
        assertEquals(R.string.settings_bbc_injection_summary, confirmed.summaryRes)
        assertFalse(confirmed.dimmed)
        assertFalse(confirmed.italic)
    }
}
