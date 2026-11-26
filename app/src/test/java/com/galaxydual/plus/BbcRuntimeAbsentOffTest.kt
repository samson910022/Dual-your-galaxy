// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// R5-02 (P1): an ABSENT runtime opt-out key is NOT a fabricated default-true,
// but for an AUTHORIZED install it means "configuration permits ON" and the
// checkbox must seed checked (unverified) instead of defaulting UNCHECKED.
// UNAVAILABLE must never claim ON. NotGranted must keep the configuration gate
// OFF no matter what the runtime preference says. An always-reachable EXPLICIT
// OFF must commit runtime=false directly, with ZERO consent demand and ZERO
// true writes. All cases run through the PRODUCTION holder/coordinator/controller
// reducer via BbcConsentTestHarness on a virtual scheduler (no sleeps/polling).
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BbcRuntimeAbsentOffTest {

    private fun read(
        generation: Long,
        optIn: BbcOptInValue = BbcOptInValue.ABSENT,
        classification: String? = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        runtime: BbcRuntimePrefValue = BbcRuntimePrefValue.ABSENT,
        migrated: Boolean? = null,
        enablePending: Boolean? = null,
    ) = BbcConsentAuthoritativeRead(
        serviceGeneration = generation,
        optIn = optIn,
        classification = classification,
        migrated = migrated,
        legacyRuntimeEnabled = when (runtime) {
            BbcRuntimePrefValue.TRUE -> true
            BbcRuntimePrefValue.FALSE -> false
            else -> null
        },
        coherent = runtime != BbcRuntimePrefValue.UNAVAILABLE,
        enablePending = enablePending,
        runtime = runtime,
    )

    private fun TestScope.newCoordinator(
        holder: BbcActivationConsentHolder,
        commits: MutableList<Boolean>,
        runtimePref: BbcRuntimePrefValue,
        consentWrites: MutableList<Long> = mutableListOf(),
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): BbcToggleCoordinator {
        val appScope = CoroutineScope(SupervisorJob() + io)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        return BbcToggleCoordinator(
            scope = appScope,
            state = BbcToggleRuntimeState(), // production-neutral seed
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = io,
            consentCommitAt = { generation ->
                consentWrites.add(holder.currentGeneration())
                BbcConsentWriteResult(BbcWriteOutcome.Confirmed(true), generation)
            },
            consentRead = { generation -> read(generation) },
            consentHolder = holder,
            effectRead = { runtimePref },
        )
    }

    private fun TestScope.harness(
        holder: BbcActivationConsentHolder,
        coordinator: BbcToggleCoordinator,
    ) = BbcConsentTestHarness(holder, coordinator, StandardTestDispatcher(testScheduler))

    private fun legacyAbsentHolder(): BbcActivationConsentHolder {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.markInstallClassificationConfirmed(1L)
        holder.resolve(
            read(
                1L,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = true,
                runtime = BbcRuntimePrefValue.ABSENT,
            ),
        )
        return holder
    }

    private fun TestScope.seedEffect(coordinator: BbcToggleCoordinator) {
        coordinator.submitEffectRead()
        testScheduler.advanceUntilIdle()
    }

    // ---- 1. Legacy(PRE_UPGRADE + marker) + ABSENT ------------------------------

    @Test
    fun legacyAbsentRuntime_seedsCheckedUnverified_firstExplicitOffCommitsFalseOnly() = runTest {
        val holder = legacyAbsentHolder()
        assertEquals(BbcActivationConsentState.Legacy(null), holder.currentState())
        val commits = mutableListOf<Boolean>()
        val consentWrites = mutableListOf<Long>()
        val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.ABSENT, consentWrites)
        val h = harness(holder, coordinator)

        seedEffect(coordinator)
        assertEquals("authorized + ABSENT must seed CHECKED, not default UNCHECKED", true, h.lastSettledChecked)
        assertTrue("the ABSENT seed must be unverified", h.lastProjectionNeutral)
        assertTrue(
            "the effect status must carry the ABSENT reason",
            coordinator.state.effectStatus.value is BbcEffectStatus.ProvisionalRuntimeRead &&
                (coordinator.state.effectStatus.value as BbcEffectStatus.ProvisionalRuntimeRead).runtime ==
                BbcRuntimePrefValue.ABSENT,
        )
        // No consent on opening: the checkbox merely reflects an authorized install.
        assertEquals(0, h.dialogsShown.size)

        // The FIRST OFF is a direct commit(false), never a true write first.
        h.explicitOff()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        assertFalse("ZERO true writes", commits.any { it })
        assertEquals(0, consentWrites.size)
        assertEquals(0, h.dialogsShown.size)
        assertEquals(false, h.lastSettledChecked)
        assertEquals(BbcEffectStatus.Confirmed(false), coordinator.state.effectStatus.value)

        // Reopen / rebind keeps the OFF; the ABSENT seed must not resurrect ON.
        h.dispose()
        val reopened = harness(holder, coordinator)
        reopened.seedFromHolder()
        testScheduler.advanceUntilIdle()
        assertEquals(false, reopened.lastSettledChecked)
        holder.onServiceGenerationChanged(2L)
        reopened.seedFromHolder()
        testScheduler.advanceUntilIdle()
        assertEquals(false, reopened.lastSettledChecked)
        assertEquals("reopen/rebind must not duplicate writes", listOf(false), commits)
        reopened.dispose()
    }

    // ---- 2. Granted + ABSENT ---------------------------------------------------

    @Test
    fun grantedAbsentRuntime_seedsCheckedUnverified_firstExplicitOffCommitsFalseOnly() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = BbcRuntimePrefValue.ABSENT))
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        val commits = mutableListOf<Boolean>()
        val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.ABSENT)
        val h = harness(holder, coordinator)

        seedEffect(coordinator)
        assertEquals(true, h.lastSettledChecked)
        assertTrue(h.lastProjectionNeutral)

        h.explicitOff()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        assertFalse(commits.any { it })
        assertEquals(false, h.lastSettledChecked)
        h.dispose()
    }

    // ---- 3. NotGranted must close the CONFIG gate for every runtime value -----

    @Test
    fun notGranted_withRuntimeTrueOrProvisionalOrConfirmed_neverRendersChecked() = runTest {
        // (a) definite read TRUE
        run {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, runtime = BbcRuntimePrefValue.TRUE))
            val commits = mutableListOf<Boolean>()
            val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.TRUE)
            val h = harness(holder, coordinator)
            seedEffect(coordinator)
            assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())
            assertEquals("config gate OFF beats runtime TRUE", false, h.lastSettledChecked)
            h.explicitOff()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(false), commits)
            assertEquals(0, h.dialogsShown.size)
            h.dispose()
        }
        // (b) provisional TRUE folded in directly (same reducer path as a read)
        run {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, runtime = BbcRuntimePrefValue.TRUE))
            val commits = mutableListOf<Boolean>()
            val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.TRUE)
            val h = harness(holder, coordinator)
            coordinator.state.seedEffectFromPersistedRead(BbcRuntimePrefValue.TRUE)
            testScheduler.advanceUntilIdle()
            assertEquals(BbcEffectStatus.ProvisionalRead(true), coordinator.state.effectStatus.value)
            assertEquals(false, h.lastSettledChecked)
            h.explicitOff()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(false), commits)
            h.dispose()
        }
        // (c) a confirmed TRUE from an older generation cannot authorize either
        run {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, runtime = BbcRuntimePrefValue.TRUE))
            val commits = mutableListOf<Boolean>()
            val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.TRUE)
            val h = harness(holder, coordinator)
            assertTrue(coordinator.state.mark(
                BbcWriteOutcome.Confirmed(true), coordinator.state.nextSequence(),
            ))
            testScheduler.advanceUntilIdle()
            assertEquals(BbcEffectStatus.Confirmed(true), coordinator.state.effectStatus.value)
            assertEquals("confirmed runtime value still cannot open the config gate", false, h.lastSettledChecked)
            h.explicitOff()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(false), commits)
            h.dispose()
        }
    }

    @Test
    fun explicitOptInFalse_withRuntimeTrue_NotGrantedWins() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.markInstallClassificationConfirmed(1L)
        holder.resolve(
            read(
                1L,
                optIn = BbcOptInValue.FALSE,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = true,
                runtime = BbcRuntimePrefValue.TRUE,
            ),
        )
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())
        val commits = mutableListOf<Boolean>()
        val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.TRUE)
        val h = harness(holder, coordinator)
        seedEffect(coordinator)
        assertEquals(false, h.lastSettledChecked)
        h.explicitOff()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        assertEquals(0, h.dialogsShown.size)
        h.dispose()
    }

    // ---- 4. UNAVAILABLE is neutral and never ON; OFF remains reachable --------

    @Test
    fun runtimeUnavailable_isNeutralNeverChecked_butExplicitOffStillCommits() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = BbcRuntimePrefValue.UNAVAILABLE))
        val commits = mutableListOf<Boolean>()
        val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.UNAVAILABLE)
        val h = harness(holder, coordinator)

        seedEffect(coordinator)
        assertFalse("UNAVAILABLE must never claim ON", h.lastSettledChecked)
        assertTrue("UNAVAILABLE must be unverified", h.lastProjectionNeutral)
        val status = coordinator.state.effectStatus.value as BbcEffectStatus.ProvisionalRuntimeRead
        assertEquals(BbcRuntimePrefValue.UNAVAILABLE, status.runtime)
        assertNull(status.value)
        assertNull("a failed read is not a write outcome", coordinator.state.confirmedOrUnknown)

        h.explicitOff()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        assertEquals(false, h.lastSettledChecked)
        h.dispose()
    }

    @Test
    fun unresolvedAuthorization_absentRuntime_staysFailClosedNeutral() = runTest {
        // Initializing: an absent runtime key is not proof of authorization.
        val initializing = bbcToggleUiProjection(
            BbcActivationConsentState.Initializing,
            BbcEffectStatus.ProvisionalRuntimeRead(BbcRuntimePrefValue.ABSENT, null),
            null,
        )
        assertNull(initializing.checked)
        assertTrue(initializing.neutral)
        // Unknown fence: still no fabricated checked ON, and ON still asks.
        val fenced = bbcToggleUiProjection(
            BbcActivationConsentState.Unknown,
            BbcEffectStatus.ProvisionalRuntimeRead(BbcRuntimePrefValue.ABSENT, null),
            null,
        )
        assertNull(fenced.checked)
        assertTrue(fenced.neutral)
        assertTrue(BbcActivationConsentState.Unknown.requiresConsentFor(true))

        // Through the real reducer: no checkbox write, but the explicit OFF is
        // still available.
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val commits = mutableListOf<Boolean>()
        val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.ABSENT)
        val h = harness(holder, coordinator)
        seedEffect(coordinator)
        assertFalse(h.lastSettledChecked)
        assertTrue(h.lastProjectionNeutral)
        h.explicitOff()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        h.dispose()
    }

    // ---- 5. Pending intents win; exactly-once enable; OFF kills pending enable -

    @Test
    fun pendingIntentsWinOverAbsentAndUnavailable_andExplicitOffInvalidatesPendingEnable() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = BbcRuntimePrefValue.ABSENT))
        val commits = mutableListOf<Boolean>()
        val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.ABSENT)
        val h = harness(holder, coordinator)
        seedEffect(coordinator)
        assertEquals(true, h.lastSettledChecked)

        // A pending OFF wins over the ABSENT => checked seed.
        coordinator.state.noteLatestUserIntent(false)
        h.render() // MainActivity renders immediately after recording a tap
        assertEquals(false, h.lastSettledChecked)
        // A pending ON wins again (config gate remains open for a Granted install).
        coordinator.state.noteLatestUserIntent(true)
        h.render()
        assertEquals(true, h.lastSettledChecked)

        // A recorded-but-undelivered enable must be invalidated by explicit OFF.
        val seq = holder.startConsentIntent()
        holder.notePendingEnable(seq, 1L)
        h.explicitOff()
        testScheduler.advanceUntilIdle()
        assertFalse("explicit OFF must kill the pending enable handoff",
            holder.consumePendingEnableForReplay(1L))
        assertEquals(listOf(false), commits)
        assertFalse(commits.any { it })
        h.dispose()
    }

    @Test
    fun incompatiblePendingIntentsWinOverNotGrantedAndUnavailable() = runTest {
        // Pending ON wins over a NotGranted config gate (explicit user intent).
        run {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, runtime = BbcRuntimePrefValue.TRUE))
            val commits = mutableListOf<Boolean>()
            val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.TRUE)
            val h = harness(holder, coordinator)
            seedEffect(coordinator)
            coordinator.state.noteLatestUserIntent(true)
            h.render()
            assertEquals(true, h.lastSettledChecked)
            h.explicitOff()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(false), commits)
            h.dispose()
        }
        // UNAVAILABLE never claims ON even for a Granted install, but a pending
        // OFF of a previously checked row is still projected.
        run {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, optIn = BbcOptInValue.TRUE))
            val commits = mutableListOf<Boolean>()
            val coordinator = newCoordinator(holder, commits, BbcRuntimePrefValue.UNAVAILABLE)
            val h = harness(holder, coordinator)
            assertTrue(coordinator.state.mark(
                BbcWriteOutcome.Confirmed(true), coordinator.state.nextSequence(),
            ))
            testScheduler.advanceUntilIdle()
            assertEquals(true, h.lastSettledChecked)
            coordinator.state.seedEffectFromPersistedRead(BbcRuntimePrefValue.UNAVAILABLE)
            testScheduler.advanceUntilIdle()
            assertEquals("a Confirmed write is never downgraded by a failed read",
                BbcEffectStatus.Confirmed(true), coordinator.state.effectStatus.value)
            h.explicitOff()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(false), commits)
            h.dispose()
        }
    }

    @Test
    fun coldStartWithSharedSequence_absentSeedThenExplicitOffIsExactlyOnce() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val commits = mutableListOf<Boolean>()
        val io = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + io)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope,
            state = BbcToggleRuntimeState(sequence = sequence),
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = io,
            consentHolder = holder,
            effectRead = { BbcRuntimePrefValue.ABSENT },
        )
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = BbcRuntimePrefValue.ABSENT))

        val h = harness(holder, coordinator)
        seedEffect(coordinator)
        assertEquals(true, h.lastSettledChecked)
        h.explicitOff()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)

        // A rebuilt UI on the SAME app-scoped holder/state sees the OFF.
        h.dispose()
        val h2 = harness(holder, coordinator)
        h2.seedFromHolder()
        testScheduler.advanceUntilIdle()
        assertEquals(false, h2.lastSettledChecked)
        assertEquals(listOf(false), commits)
        h2.dispose()
    }
}
