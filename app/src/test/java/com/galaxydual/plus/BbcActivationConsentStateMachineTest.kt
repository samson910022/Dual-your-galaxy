// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase C (C1-C6): executable state-machine tests for the app-scoped BBC
// activation/consent model. All order/IO is driven by TestScheduler gates or
// real-thread latches — no sleeps, no Robolectric, no source-substring pins.
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BbcActivationConsentStateMachineTest {

    /** Boolean-ish helper: true/false/null map to TRUE/ABSENT/UNAVAILABLE. */
    private fun read(
        generation: Long,
        optIn: Boolean?,
        classification: String? = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        runtime: Boolean? = false,
        migrated: Boolean? = null,
        coherent: Boolean = true,
        enablePending: Boolean? = null,
    ) = bbcConsentAuthoritativeReadLegacyBoolean(
        generation, optIn, classification, migrated, runtime, coherent,
    ).copy(enablePending = enablePending)

    /** Explicit enum helper for the ABSENT-vs-FALSE-vs-UNAVAILABLE parity cases. */
    private fun read(
        generation: Long,
        optIn: BbcOptInValue,
        classification: String? = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        runtime: Boolean? = false,
        migrated: Boolean? = null,
        coherent: Boolean = true,
        enablePending: Boolean? = null,
    ) = BbcConsentAuthoritativeRead(
        generation, optIn, classification, migrated, runtime, coherent, enablePending,
    )

    /** Shared app-scoped coordinator on one TestScheduler, as in BbcToggleConcurrencyTest. */
    private fun TestScope.newCoordinator(
        holder: BbcActivationConsentHolder,
        consentCommit: suspend () -> BbcWriteOutcome,
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        effectCommits: MutableList<Boolean>? = null,
    ): BbcToggleCoordinator {
        val appScope = CoroutineScope(SupervisorJob() + io)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        return BbcToggleCoordinator(
            scope = appScope,
            state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { value -> effectCommits?.add(value); BbcWriteOutcome.Confirmed(value) },
            io = io,
            consentCommit = consentCommit,
            consentRead = { generation -> read(generation, false) },
            consentHolder = holder,
        )
    }

    private fun TestScope.harness(holder: BbcActivationConsentHolder, coordinator: BbcToggleCoordinator) =
        BbcConsentTestHarness(holder, coordinator, StandardTestDispatcher(testScheduler))

    @Test
    fun freshInit_isNotGrantedUnchecked_andFirstOnGoesStraightToDialog() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false)) // fresh classification, no opt-in
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())
        val ui = holder.currentState().toggleUi()
        assertEquals(false, ui.checked)
        assertFalse("fresh install must not be styled neutral forever", ui.neutral)
        assertTrue(holder.currentState().requiresConsentFor(true))
        assertFalse(holder.currentState().requiresConsentFor(false))

        val h = harness(holder, newCoordinator(holder, { BbcWriteOutcome.Unknown }))
        assertEquals("fresh checkbox must be unchecked", false, h.lastSettledChecked)
        h.userToggle(true)
        assertEquals("first ON must show exactly one consent dialog", 1, h.dialogsShown.size)
        assertEquals("no effect toggle before consent", 0, h.toggleCalls.size)
        h.dispose()
    }

    @Test
    fun acceptConfirmed_grantsAndAppliesEffectExactlyOnce() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val commits = mutableListOf<Boolean>()
        val coordinator = newCoordinator(holder, { BbcWriteOutcome.Confirmed(true) }, effectCommits = commits)
        val h = harness(holder, coordinator)

        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()

        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertEquals(listOf(true), h.toggleCalls)
        assertEquals("consent grant must trigger exactly one effect intent", listOf(true), commits)
        assertEquals(1, h.dialogsShown.size)
        h.dispose()
    }

    @Test
    fun acceptUnknown_installsFence_rendersNeutral_andNeverToggles() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val commits = mutableListOf<Boolean>()
        val h = harness(
            holder,
            newCoordinator(holder, { BbcWriteOutcome.Unknown }, effectCommits = commits),
        )

        h.userToggle(true)
        val seq = h.dialogsShown.single()
        h.accept(seq)
        testScheduler.advanceUntilIdle()

        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertTrue(holder.currentState().toggleUi().neutral)
        assertNull("neutral state must never fabricate a checked value", holder.currentState().toggleUi().checked)
        assertEquals(0, h.toggleCalls.size)
        assertEquals(0, commits.size)
        assertEquals(listOf(seq), h.failureToasts)

        // A repeat of the SAME failed intent must not double-toast.
        h.accept(seq)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(seq), h.failureToasts)
        h.dispose()
    }

    @Test
    fun cancel_restoresPriorStateWithZeroWrites_andReopenStillAsks() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        var consentWrites = 0
        val commits = mutableListOf<Boolean>()
        val h = harness(
            holder,
            newCoordinator(holder, { consentWrites++; BbcWriteOutcome.Confirmed(true) }, effectCommits = commits),
        )

        h.userToggle(true)
        h.cancel()
        assertEquals("cancel must restore the confirmable pre-dialog OFF", false, h.lastSettledChecked)
        assertEquals(false, h.controller.currentLastGood())
        assertEquals(0, consentWrites)
        assertEquals(0, commits.size)
        assertEquals(0, h.toggleCalls.size)
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        // Activity/controller-like rebuild sharing the app-scoped holder.
        h.dispose()
        val h2 = harness(holder, newCoordinator(holder, { BbcWriteOutcome.Confirmed(true) }))
        h2.seedFromHolder()
        assertEquals(false, h2.lastSettledChecked)
        h2.userToggle(true)
        assertEquals("reopen must still require consent", 1, h2.dialogsShown.size)
        h2.dispose()
    }

    @Test
    fun legacyInit_usesRuntimeValueAndNeverAsks() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.markInstallClassificationConfirmed(1L)
        holder.resolve(
            read(
                1L, optIn = false,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = true, // the Hook-equivalent durable marker is required
                runtime = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Legacy(true), holder.currentState())
        assertEquals(true, holder.currentState().toggleUi().checked)
        assertFalse(holder.currentState().requiresConsentFor(true))

        val h = harness(holder, newCoordinator(holder, { BbcWriteOutcome.Confirmed(true) }))
        assertEquals(true, h.lastSettledChecked)
        h.userToggle(false)
        h.userToggle(true)
        assertEquals("legacy installs never see the consent dialog", 0, h.dialogsShown.size)
        assertEquals(listOf(false, true), h.toggleCalls)
        h.dispose()
    }

    @Test
    fun preUpgradeWithoutDurableMarker_neverAuthorizesLegacy() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(
            read(
                1L, optIn = false,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = null, // marker absent/unreadable: same rule as the Hook gate
                runtime = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Initializing, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))
        holder.resolve(
            read(
                1L, optIn = false,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = false,
                runtime = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Initializing, holder.currentState())

        // MIGRATED opens without a marker, but only once the App has confirmed
        // the durable classification for this generation.
        holder.markInstallClassificationConfirmed(1L)
        holder.resolve(
            read(
                1L, optIn = false,
                classification = PolicySource.INSTALL_CLASSIFICATION_MIGRATED,
                runtime = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Legacy(true), holder.currentState())
        assertFalse(holder.currentState().requiresConsentFor(true))
    }

    @Test
    fun failedCommitOnCurrentGeneration_advancesExistingUnknownFence() {
        val h = BbcActivationConsentHolder()
        h.onServiceGenerationChanged(1)
        h.markConsentWriteOutcome(BbcWriteOutcome.Unknown, 1, 1) // fence@1
        assertEquals(BbcActivationConsentState.Unknown, h.currentState())
        h.onServiceGenerationChanged(2) // Unknown preserved, fence@1
        h.markConsentWriteOutcome(BbcWriteOutcome.Unknown, 2, 2) // failed commit on CURRENT gen
        h.resolve(read(2, true, null, migrated = true)) // polluted read must not clear it
        assertEquals(
            "a same-generation read must never clear a fence raised on that generation",
            BbcActivationConsentState.Unknown,
            h.currentState(),
        )

        // Only a Confirmed commit on gen 2 clears that fence.
        assertTrue(h.markConsentWriteOutcome(BbcWriteOutcome.Confirmed(true), 3, 2))
        assertEquals(BbcActivationConsentState.Granted, h.currentState())

        // The other clearing path: a NEWER-generation authoritative read.
        val g = BbcActivationConsentHolder()
        g.onServiceGenerationChanged(1)
        g.markConsentWriteOutcome(BbcWriteOutcome.Unknown, 1, 1)
        g.onServiceGenerationChanged(2)
        g.markConsentWriteOutcome(BbcWriteOutcome.Unknown, 2, 2)
        g.resolve(read(2, true))
        assertEquals(BbcActivationConsentState.Unknown, g.currentState())
        g.onServiceGenerationChanged(3)
        g.resolve(read(3, true))
        assertEquals(BbcActivationConsentState.Granted, g.currentState())
    }

    @Test
    fun lateBindClassificationFailure_doesNotDowngradeGrantedOrLegacy() {
        val granted = BbcActivationConsentHolder()
        granted.onServiceGenerationChanged(1L)
        granted.resolve(read(1L, optIn = true))
        assertEquals(BbcActivationConsentState.Granted, granted.currentState())
        granted.markInstallClassificationFailed(1L)
        assertEquals("a late bind-time failure must not undo a confirmed grant",
            BbcActivationConsentState.Granted, granted.currentState())

        val legacy = BbcActivationConsentHolder()
        legacy.onServiceGenerationChanged(2L)
        legacy.markInstallClassificationConfirmed(2L)
        legacy.resolve(
            read(
                2L, optIn = false,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = true,
                runtime = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Legacy(true), legacy.currentState())
        legacy.markInstallClassificationFailed(2L)
        assertEquals("a late bind-time failure must not undo a durable Legacy resync",
            BbcActivationConsentState.Legacy(true), legacy.currentState())
        assertFalse(legacy.currentState().requiresConsentFor(true))
    }

    @Test
    fun unknownState_reAsksConsentOnNextOn() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val h = harness(holder, newCoordinator(holder, { BbcWriteOutcome.Unknown }))

        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        h.userToggle(true)
        assertEquals("Unknown must re-ask consent", 2, h.dialogsShown.size)
        h.dispose()
    }

    @Test
    fun inheritedConfirmedWhileConsentDialogPending_mustNotOverwriteCheckbox() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val runtime = BbcToggleRuntimeState(initialConfirmed = false)
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime, commit = { BbcWriteOutcome.Confirmed(it) },
            io = StandardTestDispatcher(testScheduler), consentCommit = { BbcWriteOutcome.Unknown },
            consentHolder = holder,
        )
        val h = BbcConsentTestHarness(holder, coordinator, StandardTestDispatcher(testScheduler))
        h.userToggle(true) // dialog pending => setConsentPending(true)
        assertTrue(h.dialogPending)
        testScheduler.runCurrent() // let the collector subscribe

        // An inherited Confirmed lands while the dialog is pending. It must
        // DISAGREE with the seeded checkbox (false), otherwise the
        // `status.value != lastUiValue` condition would hide a removed gate.
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(true)))
        testScheduler.advanceUntilIdle()
        assertEquals(
            "inherited Confirmed must not settle a pending-consent CheckBox",
            0, h.restored.size,
        )
        assertFalse(h.controller.currentLastGood())

        // After the dialog is cancelled the gate is released again: the same
        // inherited value now legitimately restores the box.
        h.cancel()
        assertFalse(h.dialogPending)
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(false)))
        testScheduler.advanceUntilIdle() // let the collector observe the intermediate value
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(true)))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true), h.restored)
        assertTrue(h.controller.currentLastGood())
        h.dispose()
    }

    @Test
    fun offShortCircuitsWithZeroConsentReadsOrWrites() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        var consentWrites = 0
        var authoritativeReads = 0
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { BbcWriteOutcome.Confirmed(it) },
            io = StandardTestDispatcher(testScheduler),
            consentCommit = { consentWrites++; BbcWriteOutcome.Confirmed(true) },
            consentRead = { gen -> authoritativeReads++; read(gen, false) },
            consentHolder = holder,
        )
        val h = BbcConsentTestHarness(holder, coordinator, StandardTestDispatcher(testScheduler))

        h.userToggle(false)
        h.userToggle(false)
        assertEquals(listOf(false, false), h.toggleCalls)
        assertEquals("OFF must never read authorization state", 0, h.consentStateReads)
        assertEquals(0, consentWrites)
        assertEquals(0, authoritativeReads)
        assertEquals(0, h.dialogsShown.size)
        h.dispose()
    }

    @Test
    fun staleAcceptAfterNewerOff_isDropped() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        var consentWrites = 0
        val h = harness(
            holder,
            newCoordinator(holder, { consentWrites++; BbcWriteOutcome.Confirmed(true) }),
        )
        h.userToggle(true)
        val stale = h.dialogsShown.single()
        h.userToggle(false) // newer OFF invalidates the pending dialog
        h.accept(stale)      // late positive callback from the old dialog
        testScheduler.advanceUntilIdle()

        assertEquals(0, consentWrites)
        assertEquals(listOf(false), h.toggleCalls)
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())
        h.dispose()
    }

    @Test
    fun consentWriteRunsOnIoDispatcherNeverOnMain() = runBlocking {
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-consent-io") }.asCoroutineDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        var commitThread = ""
        var readThread = ""
        try {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, optIn = false))
            val effectCommits = mutableListOf<Boolean>()
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
                // Oracle integrity: the phase-2 enable must reach a REAL effect
                // commit. A no-op driveConsumerEnable can no longer satisfy the
                // UI-observation list alone.
                commit = { value -> effectCommits.add(value); BbcWriteOutcome.Confirmed(value) }, io = io,
                consentCommit = {
                    commitThread = Thread.currentThread().name
                    entered.countDown()
                    release.await() // genuinely blocking binder-style seam
                    BbcWriteOutcome.Confirmed(true)
                },
                consentRead = { generation ->
                    readThread = Thread.currentThread().name
                    readEntered.countDown()
                    read(generation, false)
                },
                consentHolder = holder,
            )
            val h = BbcConsentTestHarness(holder, coordinator, Dispatchers.Unconfined)
            val mainThread = Thread.currentThread().name

            h.userToggle(true)
            h.accept() // called on the test/Main thread
            assertTrue("consent commit must start", entered.await(5, TimeUnit.SECONDS))
            assertTrue("consent commit must run on the IO lane: $commitThread",
                commitThread.contains("bbc-consent-io"))
            assertFalse("consent commit must not run on Main", commitThread == mainThread)
            assertEquals("no effect toggle while the consent write is blocked", 0, h.toggleCalls.size)

            release.countDown()
            withTimeout(5_000L) { while (holder.currentState() !is BbcActivationConsentState.Granted) yield() }
            withTimeout(5_000L) { while (h.toggleCalls.isEmpty()) yield() }
            assertEquals(listOf(true), h.toggleCalls)
            // The UI observation is emitted by the holder BEFORE the consumer
            // enqueues the effect write, so wait for the REAL commit separately.
            withTimeout(5_000L) { while (effectCommits.isEmpty()) yield() }
            assertEquals("the observed enable must be a REAL effect commit",
                listOf(true), effectCommits)

            // The authoritative consent READ lane must be just as off-Main as
            // the write, and must share the same app-scoped IO consumer.
            coordinator.submitConsentRead {}
            assertTrue("consent read must start", readEntered.await(5, TimeUnit.SECONDS))
            assertTrue("consent read must run on the IO lane: $readThread",
                readThread.contains("bbc-consent-io"))
            assertFalse("consent read must not run on Main", readThread == mainThread)
            h.dispose()
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    @Test
    fun disposedUi_dropsLateConsentCallback_butDurableFenceStillLands() = runBlocking {
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-consent-teardown-io") }.asCoroutineDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val settled = CountDownLatch(1)
        try {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, optIn = false))
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
                commit = { BbcWriteOutcome.Confirmed(it) }, io = io,
                consentCommit = {
                    entered.countDown()
                    release.await()
                    BbcWriteOutcome.Unknown
                },
                consentHolder = holder,
            )
            val h = BbcConsentTestHarness(holder, coordinator, Dispatchers.Unconfined)
            h.userToggle(true)
            val seq = h.dialogsShown.single()
            h.accept(seq)
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            // Activity teardown while the consent commit is still blocked.
            h.dispose()
            assertFalse(h.alive)
            val checkedAtTeardown = h.lastSettledChecked

            release.countDown()
            // A read queued behind the write gives a deterministic happens-after
            // signal for the late consent callback on the same IO consumer.
            coordinator.submitConsentRead { settled.countDown() }
            assertTrue(settled.await(5, TimeUnit.SECONDS))

            assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
            assertEquals("no effect toggle on a disposed UI", 0, h.toggleCalls.size)
            assertEquals("no failure toast on a disposed UI", 0, h.failureToasts.size)
            assertEquals("no dialog state mutation on a disposed UI", listOf(seq), h.dialogsShown)
            assertEquals(checkedAtTeardown, h.lastSettledChecked)
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    @Test
    fun pendingEnable_isConsumedExactlyOnce_afterGrant() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val seq = holder.nextIntentSequence()
        holder.notePendingEnable(seq, 1L)

        assertFalse("a different intent cannot consume the enable", holder.consumePendingEnable(seq - 1, 1L))
        assertFalse("an unauthorized state cannot replay the enable", holder.consumePendingEnableForReplay(1L))
        assertTrue(holder.markConsentWriteOutcome(BbcWriteOutcome.Confirmed(true), seq, 1L))
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())

        assertTrue("the rebuilt UI consumes the enable once", holder.consumePendingEnableForReplay(1L))
        assertFalse("a second replay must be dropped", holder.consumePendingEnableForReplay(1L))
        assertFalse("a late UI callback must not toggle again", holder.consumePendingEnable(seq, 1L))
    }

    @Test
    fun pendingEnable_isInvalidatedByOffUnknownAndGenerationChange() {
        // OFF / cancel: abandon kills the handoff, so a late Confirmed cannot
        // resurrect an enable the user explicitly rejected.
        val off = BbcActivationConsentHolder()
        off.onServiceGenerationChanged(1L)
        off.resolve(read(1L, optIn = false))
        val offSeq = off.nextIntentSequence()
        off.notePendingEnable(offSeq, 1L)
        off.abandonIntent(offSeq)
        assertFalse(off.consumePendingEnable(offSeq, 1L))
        assertTrue("a stale reply must be dropped", off.markConsentWriteOutcome(BbcWriteOutcome.Confirmed(true), offSeq, 1L).not())
        assertEquals(BbcActivationConsentState.NotGranted, off.currentState())

        // Unknown commit: drop the enable and keep the durable fence.
        val failed = BbcActivationConsentHolder()
        failed.onServiceGenerationChanged(1L)
        val failedSeq = failed.nextIntentSequence()
        failed.notePendingEnable(failedSeq, 1L)
        failed.markConsentWriteOutcome(BbcWriteOutcome.Unknown, failedSeq, 1L)
        assertEquals(BbcActivationConsentState.Unknown, failed.currentState())
        assertFalse(failed.consumePendingEnable(failedSeq, 1L))
        assertFalse(failed.consumePendingEnableForReplay(1L))

        // Binder death: an enable recorded on the dead instance cannot be
        // replayed against a new service generation.
        val rebound = BbcActivationConsentHolder()
        rebound.onServiceGenerationChanged(1L)
        val staleSeq = rebound.nextIntentSequence()
        rebound.notePendingEnable(staleSeq, 1L)
        rebound.onServiceGenerationChanged(2L)
        assertFalse(rebound.consumePendingEnable(staleSeq, 1L))
        assertFalse(rebound.consumePendingEnableForReplay(2L))
    }

    /**
     * Process death / rebind convergence: a NEW holder + coordinator sees the
     * durable opt-in key but a runtime preference of false. No Activity, no
     * callback, no state-flow replay — the app consumer that owns the opt-in
     * lane must still write the effect enable exactly once.
     */
    @Test
    fun freshProcessOptInTrueRuntimeFalse_convergesViaConsumerExactlyOnce() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = true))
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())

        val effectCommits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> effectCommits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { generation -> BbcConsentWriteResult(BbcWriteOutcome.Unknown, generation) },
            consentRead = { generation ->
                read(generation, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = true)
            },
            consentHolder = holder,
        )

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("durable opt-in + pending marker must drive exactly one runtime enable",
            listOf(true), effectCommits)
        assertEquals(true, runtime.confirmedOrUnknown)
        assertEquals("no UI/abandon was involved", 0L, holder.latestAbandonSequence())

        // Repeated resyncs (or a rebuilt UI replay) must not write it again.
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true), effectCommits)
    }

    /**
     * Phase 2 fixer (BLOCKING 1): a durable OFF writes ONLY the effect pref,
     * so a cold-start resync with optin=true must NOT re-enable it. The
     * app-only enable-pending marker is cleared in the SAME commit as the OFF
     * and survives process death; without it the resync reverses the user.
     */
    private class ColdStartDurable {
        var optIn: Boolean = false
        var pending: Boolean = false
        var runtimeEnabled: Boolean = false

        fun read(generation: Long) = BbcConsentAuthoritativeRead(
            serviceGeneration = generation,
            optIn = if (optIn) BbcOptInValue.TRUE else BbcOptInValue.ABSENT,
            classification = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
            migrated = null,
            legacyRuntimeEnabled = runtimeEnabled,
            coherent = true,
            enablePending = pending,
        )
    }

    @Test
    fun coldStartPendingMarkerTrue_runtimeFalse_enablesExactlyOnce() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = true))
        val effectCommits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> effectCommits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { generation -> BbcConsentWriteResult(BbcWriteOutcome.Unknown, generation) },
            consentRead = { generation ->
                read(generation, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = true)
            },
            consentHolder = holder,
        )

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("fresh process + pending marker -> exactly one enable", listOf(true), effectCommits)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a repeated resync must not enable twice", listOf(true), effectCommits)
    }

    @Test
    fun coldStartOptInTrueButNoPendingMarker_neverCommitsAnyEnable() = runTest {
        val holder = BbcActivationConsentHolder()
        val runtime = BbcToggleRuntimeState(initialConfirmed = false)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = false))
        val effectCommits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> effectCommits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { generation -> BbcConsentWriteResult(BbcWriteOutcome.Unknown, generation) },
            consentRead = { generation ->
                read(generation, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = false)
            },
            consentHolder = holder,
        )

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("optin=true alone is not a pending enable", BbcActivationConsentState.Granted, holder.currentState())
        assertTrue("no enable commit may be enqueued", effectCommits.isEmpty())
        assertEquals("the provisional effect seed must stay untouched",
            BbcEffectStatus.Provisional, runtime.effectStatus.value)
    }

    @Test
    fun grantThenOffThenColdStart_neverReenables() = runTest {
        val durable = ColdStartDurable()
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(durable.read(1L))
        val firstCommits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            // ONE atomic runtime commit: value + marker(false). This is what
            // HookPolicyStore.setBbcRuntimeAndEnablePendingDetailed does.
            commit = { value ->
                durable.runtimeEnabled = value
                durable.pending = false
                firstCommits.add(value)
                BbcWriteOutcome.Confirmed(value)
            },
            io = dispatcher,
            consentCommitAt = { generation ->
                durable.optIn = true
                durable.pending = true
                BbcConsentWriteResult(BbcWriteOutcome.Confirmed(true), generation)
            },
            consentRead = { generation -> durable.read(generation) },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)

        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true), firstCommits)
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())

        // The user's OFF clears the marker in the SAME commit as runtime=false.
        h.userToggle(false)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true, false), firstCommits)
        assertFalse("OFF must clear the durable enable-pending marker", durable.pending)
        h.dispose()

        // Cold start: a NEW holder sees optin=true + runtime=false, but the
        // marker the OFF cleared did not survive -> zero commits.
        val coldSequence = AtomicLong(0L)
        val coldHolder = BbcActivationConsentHolder(coldSequence)
        val coldRuntime = BbcToggleRuntimeState(initialConfirmed = false, sequence = coldSequence)
        coldHolder.onServiceGenerationChanged(2L)
        coldHolder.resolve(durable.read(2L))
        val coldCommits = mutableListOf<Boolean>()
        val coldScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { coldScope.cancel() }
        val coldCoordinator = BbcToggleCoordinator(
            scope = coldScope, state = coldRuntime,
            commit = { value -> coldCommits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { generation -> BbcConsentWriteResult(BbcWriteOutcome.Unknown, generation) },
            consentRead = { generation -> durable.read(generation) },
            consentHolder = coldHolder,
        )
        coldCoordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, coldHolder.currentState())
        assertTrue("a durable user OFF must never be reversed on cold start", coldCommits.isEmpty())
    }

    /**
     * Re-review P1-A: accept() queues the opt-in commit, then the user taps OFF
     * BEFORE the IO consumer is driven (no scheduler advance in between). The
     * consumer drains the toggle lane first, so the now-superseded grant must
     * NOT enter the store at all: its atomic optin=true + marker=true write
     * would re-arm a cold-start enable and reverse the user's OFF.
     */
    @Test
    fun supersededConsentWriteNeverPersistsGrantMarker_coldStartStaysOff() = runTest {
        val durable = ColdStartDurable()
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(durable.read(1L)) // fresh install: NotGranted, runtime=false
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        val effectCommits = mutableListOf<Boolean>()
        var consentCommits = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            // ONE atomic runtime commit: value + marker(false), as in production.
            commit = { value ->
                durable.runtimeEnabled = value
                durable.pending = false
                effectCommits.add(value)
                BbcWriteOutcome.Confirmed(value)
            },
            io = dispatcher,
            consentCommitAt = { generation ->
                consentCommits++
                durable.optIn = true
                durable.pending = true
                BbcConsentWriteResult(BbcWriteOutcome.Confirmed(true), generation)
            },
            consentRead = { generation -> durable.read(generation) },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)

        h.userToggle(true) // dialog intent S=1
        val grantIntent = h.dialogsShown.single()
        h.accept(grantIntent) // queued consent write; NO scheduler advance yet
        h.userToggle(false) // newer OFF supersedes S=1 before the consumer drains
        testScheduler.advanceUntilIdle()

        assertEquals("the user's OFF must be the only effect commit",
            listOf(false), effectCommits)
        assertEquals("a superseded grant must never enter the consent store",
            0, consentCommits)
        assertFalse("superseded grant must not persist optin=true", durable.optIn)
        assertFalse("superseded grant must not re-arm the enable marker", durable.pending)
        assertFalse(durable.runtimeEnabled)

        // Cold start with a FRESH holder on a NEW generation: no in-process OFF
        // guard exists, only the durable keys. A stale marker would claim an
        // owed enable and reverse the user's OFF.
        val coldSequence = AtomicLong(0L)
        val coldHolder = BbcActivationConsentHolder(coldSequence)
        val coldRuntime = BbcToggleRuntimeState(initialConfirmed = false, sequence = coldSequence)
        coldHolder.onServiceGenerationChanged(2L)
        coldHolder.resolve(durable.read(2L))
        val coldCommits = mutableListOf<Boolean>()
        val coldScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { coldScope.cancel() }
        val coldCoordinator = BbcToggleCoordinator(
            scope = coldScope, state = coldRuntime,
            commit = { value -> coldCommits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { gen -> BbcConsentWriteResult(BbcWriteOutcome.Unknown, gen) },
            consentRead = { gen -> durable.read(gen) },
            consentHolder = coldHolder,
        )
        coldCoordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertTrue("cold start must not re-enable a durable OFF", coldCommits.isEmpty())
        assertFalse(durable.runtimeEnabled)
        assertFalse("the enable marker must not survive the OFF", durable.pending)
        h.dispose()
    }

    /**
     * Phase 2 fixer (BLOCKING 6): a resync may claim a NEWER enable ownership R
     * than a still-pending consent intent S. When S later lands Confirmed it
     * must not regress R to S; otherwise a second enable is enqueued.
     */
    @Test
    fun resyncClaimNewerThanGrant_isNotRegressedByLaterConfirmed() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val durable = ColdStartDurable()
        val commits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { generation ->
                durable.optIn = true
                BbcConsentWriteResult(BbcWriteOutcome.Confirmed(true), generation)
            },
            consentRead = { generation -> durable.read(generation).copy(enablePending = true) },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)
        h.userToggle(true)
        val stale = h.dialogsShown.single()

        // Rebind: the resync owns a fresh enable for gen 2 (R > S).
        durable.optIn = true
        holder.onServiceGenerationChanged(2L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("the resync enable must run", listOf(true), commits)
        val owner = holder.latestEnableDrivenSequence()
        assertTrue("resync claim must be newer than the stale dialog", owner > stale)

        // The stale dialog's Confirmed reply (S < R) arrives afterwards.
        holder.notePendingEnable(stale, 2L)
        coordinator.submitConsentWrite(stale, 2L) {}
        testScheduler.advanceUntilIdle()
        assertEquals("a regressed S must not enqueue a second enable", listOf(true), commits)
        assertEquals("enable ownership must stay at R", owner, holder.latestEnableDrivenSequence())
        h.dispose()
    }

    /**
     * Re-review P1-B: a Confirmed grant at gen1 drives an enable whose commit
     * returns Unknown (the durable marker therefore stays true). After a rebind
     * to gen2 the SAME owed enable must be claimable ONCE — the gen1 ownership
     * token is generation-scoped and cannot block a new generation — and a
     * repeated resync at gen2 must not enqueue a second enable.
     */
    @Test
    fun rebindClaimsOwedEnableExactlyOnce_afterUnknownEnable() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val durable = ColdStartDurable()
        val commits = mutableListOf<Boolean>()
        var firstEnable = true
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value ->
                commits.add(value)
                // The FIRST ENABLE fails (not the older OFF): that commit never
                // landed, so the daemon never clears the enable-pending marker.
                val outcome = if (value && firstEnable) {
                    firstEnable = false
                    BbcWriteOutcome.Unknown
                } else BbcWriteOutcome.Confirmed(value)
                if (outcome is BbcWriteOutcome.Confirmed) {
                    durable.runtimeEnabled = value
                    durable.pending = false // one atomic runtime+marker commit
                }
                outcome
            },
            io = dispatcher,
            consentCommitAt = { gen ->
                durable.optIn = true
                durable.pending = true
                BbcConsentWriteResult(BbcWriteOutcome.Confirmed(true), gen)
            },
            // A rebind always sees the STILL-OWED marker on its own instance.
            consentRead = { gen ->
                durable.read(gen).copy(optIn = BbcOptInValue.TRUE, enablePending = true)
            },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)
        // R5-04: a REAL older Confirmed(false) must be present; the old setup
        // started with no effect mark and accidentally dodged the veto branch.
        h.userToggle(false)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        assertEquals(BbcEffectStatus.Confirmed(false), runtime.effectStatus.value)
        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals("the gen1 enable was attempted", listOf(false, true), commits)
        assertTrue("an Unknown enable is not an acknowledgement",
            runtime.effectStatus.value is BbcEffectStatus.Unknown)
        assertTrue(durable.pending)
        h.dispose()

        // Binder death / rebind: new prefs instance, same app-scoped holder.
        holder.onServiceGenerationChanged(2L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("the owed enable must be claimed once on the NEW generation",
            listOf(false, true, true), commits)

        repeat(2) {
            coordinator.submitConsentRead {}
            testScheduler.advanceUntilIdle()
        }
        assertEquals("repeated resyncs at gen2 must not enable twice",
            listOf(false, true, true), commits)
    }

    /**
     * R5-04 (P1): an OLD Confirmed(false) must not permanently veto a NEWER
     * accepted grant whose consent commit landed durably but whose reply was
     * lost to a rebind. The fresh session's authoritative read VERIFIES
     * optin=true + enablePending=true, so the owed phase-2 enable must be
     * driven ONCE by the app consumer — no UI/Settings reopen, no restart.
     */
    @Test
    fun oldConfirmedFalseNeverVetoesNewerVerifiedGrantAfterRebind() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        val durable = ColdStartDurable()
        holder.resolve(durable.read(1L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        val s1 = BbcConsentSessionIdentity(1L, Any(), Any())
        val s2 = BbcConsentSessionIdentity(2L, Any(), Any())
        var current: BbcConsentSessionIdentity = s1
        val commits = Collections.synchronizedList(mutableListOf<Boolean>())
        val sessions = Collections.synchronizedList(
            mutableListOf<BbcConsentSessionIdentity?>(),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            commitWithSession = { value, session ->
                sessions.add(session)
                commits.add(value)
                durable.runtimeEnabled = value
                durable.pending = false // every runtime commit clears the marker atomically
                BbcWriteOutcome.Confirmed(value)
            },
            io = dispatcher,
            consentCommitAt = { gen ->
                // The grant REALLY lands, but the reply is lost (rebind gen1->gen2).
                durable.optIn = true
                durable.pending = true
                BbcConsentWriteResult(BbcWriteOutcome.Unknown, gen, s1)
            },
            consentRead = { gen ->
                durable.read(gen).copy(session = if (gen == 1L) s1 else s2)
            },
            currentSession = { current },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)

        // gen1: the user's OFF succeeds -> a REAL, older Confirmed(false) mark.
        h.userToggle(false)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        assertEquals(BbcEffectStatus.Confirmed(false), runtime.effectStatus.value)

        // Later: a NEW, explicit ON/consent. The optin+marker commit lands, but
        // the Unknown reply must NOT fabricate a grant or drive phase 2 yet.
        h.userToggle(true)
        val grant = h.dialogsShown.single()
        h.accept(grant)
        testScheduler.advanceUntilIdle()
        assertEquals("an Unknown grant reply must not drive phase 2",
            listOf(false), commits)
        assertTrue(durable.optIn)
        assertTrue(durable.pending)
        assertFalse(holder.currentState() is BbcActivationConsentState.Granted)

        // Rebind before the grant reply is processed; fresh stamped snapshot.
        current = s2
        holder.onServiceGenerationChanged(2L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()

        assertTrue("a fresh authoritative read must resolve Granted",
            holder.currentState() is BbcActivationConsentState.Granted)
        assertEquals("exactly ONE phase-2 ON, despite the old Confirmed(false)",
            listOf(false, true), commits)
        assertEquals("no UI/Settings reopen was needed", 1, h.dialogsShown.size)
        assertEquals(listOf<BbcConsentSessionIdentity?>(null, s2), sessions)
        assertFalse("the enable cleared the durable marker", durable.pending)
        assertEquals(BbcEffectStatus.Confirmed(true), runtime.effectStatus.value)

        repeat(2) {
            coordinator.submitConsentRead {}
            testScheduler.advanceUntilIdle()
        }
        assertEquals("repeated resyncs must never enable twice",
            listOf(false, true), commits)
        h.dispose()
    }

    /**
     * R5-04: the SAME flow, but a genuinely NEWER OFF lands before the resync.
     * The old grant must not be replayed and the durable OFF must stay final.
     */
    @Test
    fun newerOffAfterVerifiedGrant_rebindResyncNeverReenables() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        val durable = ColdStartDurable()
        holder.resolve(durable.read(1L))
        val s1 = BbcConsentSessionIdentity(1L, Any(), Any())
        val s2 = BbcConsentSessionIdentity(2L, Any(), Any())
        var current: BbcConsentSessionIdentity = s1
        val commits = Collections.synchronizedList(mutableListOf<Boolean>())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            commitWithSession = { value, _ ->
                commits.add(value)
                durable.runtimeEnabled = value
                durable.pending = false // every runtime commit clears the marker
                BbcWriteOutcome.Confirmed(value)
            },
            io = dispatcher,
            consentCommitAt = { gen ->
                durable.optIn = true
                durable.pending = true
                BbcConsentWriteResult(BbcWriteOutcome.Unknown, gen, s1)
            },
            consentRead = { gen -> durable.read(gen).copy(session = s2) },
            currentSession = { current },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)

        h.userToggle(false) // old Confirmed(false), then a newer accepted ON
        testScheduler.advanceUntilIdle()
        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        assertTrue(durable.pending)

        current = s2
        holder.onServiceGenerationChanged(2L)
        // The user turns OFF AGAIN in the new generation: newer than the grant,
        // and it clears the marker in the same atomic runtime commit.
        h.userToggle(false)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, false), commits)
        assertFalse(durable.pending)
        assertFalse(durable.runtimeEnabled)

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a newer OFF must never be replayed into an enable",
            listOf(false, false), commits)
        assertEquals(false, runtime.confirmedOrUnknown)
        h.dispose()
    }

    /**
     * R5-04: Confirmed(true) is already authoritative. Even with durable
     * optin=true + marker=true on a fresh session, the resync must not write a
     * SECOND enable (monotonic, generation-scoped ownership).
     */
    @Test
    fun priorConfirmedTrue_resyncNeverWritesSecondEnable() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val grant = holder.startConsentIntent()
        assertTrue(holder.markConsentWriteOutcome(
            BbcWriteOutcome.Confirmed(true), grant, 1L, consumerOwnsEnable = true,
        ))
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(true), runtime.nextSequence()))

        val commits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentRead = { gen ->
                read(gen, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = true)
            },
            consentHolder = holder,
        )

        holder.onServiceGenerationChanged(2L)
        repeat(2) {
            coordinator.submitConsentRead {}
            testScheduler.advanceUntilIdle()
        }
        assertEquals("no second enable after Confirmed(true)", 0, commits.size)
        assertEquals(BbcEffectStatus.Confirmed(true), runtime.effectStatus.value)
    }

    /**
     * R5-04: a failed/incoherent rebind read must not consume the owed enable;
     * the NEXT fresh authoritative read converges it exactly once. No sleeps,
     * no polling — the read seam count is deterministic on the TestScheduler.
     */
    @Test
    fun failedResyncReadThenFreshRead_convergesOwedEnableExactlyOnce() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        val durable = ColdStartDurable()
        holder.resolve(durable.read(1L))
        val commits = mutableListOf<Boolean>()
        var reads = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { gen ->
                durable.optIn = true
                durable.pending = true
                BbcConsentWriteResult(BbcWriteOutcome.Unknown, gen)
            },
            consentRead = { gen ->
                reads++
                // First rebind read is an authoritative READ FAILURE: no value,
                // no marker, no authorization may be produced from it.
                if (reads == 1) BbcConsentDurableSnapshot.failClosed().forGeneration(gen)
                else durable.read(gen)
            },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)

        h.userToggle(false) // old Confirmed(false)
        testScheduler.advanceUntilIdle()
        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        h.dispose()

        holder.onServiceGenerationChanged(2L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a failed read may not claim the owed enable", listOf(false), commits)
        assertFalse(holder.currentState() is BbcActivationConsentState.Granted)

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("the next authoritative read converges exactly once",
            listOf(false, true), commits)
        assertTrue(holder.currentState() is BbcActivationConsentState.Granted)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, true), commits)
    }

    /**
     * R5-04: process death simulation. A brand-new holder has NO grant-attempt
     * record; with no marker it must never enable, and with a verified marker
     * it must converge exactly once even though no UI ever reopens.
     */
    @Test
    fun processDeathFreshHolder_markerAbsentNeverEnables_markerTrueEnablesOnce() = runTest {
        val sequence = AtomicLong(0L)
        val durable = ColdStartDurable()
        durable.optIn = true // durable opt-in survived the process death
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        assertEquals(BbcEffectStatus.Provisional, runtime.effectStatus.value)
        holder.onServiceGenerationChanged(7L)
        val commits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentRead = { gen -> durable.read(gen) },
            consentHolder = holder,
        )

        // Marker absent: optin=true alone is NOT an owed enable.
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertTrue(commits.isEmpty())
        assertEquals(0L, holder.effectiveGrantSequence())

        // The daemon's marker appears on a genuinely fresh session: owed enable.
        durable.pending = true
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a verified marker drives exactly one enable", listOf(true), commits)
        repeat(2) {
            coordinator.submitConsentRead {}
            testScheduler.advanceUntilIdle()
        }
        assertEquals(listOf(true), commits)
    }

    /**
     * R5-04, holder level: an OFF recorded BEFORE the grant is the prior setting
     * and must not block a verified grant at the SAME generation; a NEWER OFF
     * must keep blocking after a rebind, and an unverified attempt promotes to
     * no reason at all.
     */
    @Test
    fun verifiedGrantAttempt_promotesReasonAndClearsOnlyOlderOffBlock() {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))

        val oldOff = holder.supersedeActiveIntent()
        val grant = holder.startConsentIntent()
        holder.notePendingEnable(grant, 1L)
        assertEquals("an unverified attempt is not a grant reason",
            0L, holder.effectiveGrantSequence())
        assertEquals("the OFF is OLDER than the later grant", true, oldOff < grant)

        // Fresh read verifies durable optin=true + marker: promote and unblock.
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, enablePending = true))
        assertEquals(grant, holder.effectiveGrantSequence())
        assertTrue("an OFF OLDER than the verified grant may not block resync",
            holder.tryClaimResyncEnable(1L, grant, true, holder.nextIntentSequence()))
        assertTrue("the old OFF stayed older", oldOff < grant)

        // A genuinely newer OFF keeps winning after a rebind.
        val newerOff = holder.supersedeActiveIntent()
        assertTrue(newerOff > grant)
        holder.onServiceGenerationChanged(2L)
        holder.resolve(read(2L, optIn = BbcOptInValue.TRUE, enablePending = true))
        assertFalse("a newer OFF must still block the owed enable",
            holder.tryClaimResyncEnable(2L, grant, true, holder.nextIntentSequence()))
        assertEquals("promotion never fabricates a Confirmed grant",
            0L, holder.confirmedGrantSequence())

        // R5 review: a holder ALREADY Granted plus an INCOHERENT read that
        // optimistically reports optin=true + marker=true must NOT promote a
        // newer attempt or clear the OFF block. All-or-nothing: an incoherent
        // snapshot verifies nothing, so no "verified" grant may be derived from
        // it — the Granted state was inherited from cur.state, not this read.
        val beforeIncoherent = holder.effectiveGrantSequence()
        val olderOff = holder.supersedeActiveIntent() // fresh OFF block at gen 2
        val attemptAfterOff = holder.startConsentIntent()
        holder.notePendingEnable(attemptAfterOff, 2L)
        assertTrue(olderOff < attemptAfterOff)
        holder.resolve(
            read(2L, optIn = BbcOptInValue.TRUE, coherent = false, enablePending = true),
        )
        assertEquals("an incoherent read fabricates no verified grant",
            beforeIncoherent, holder.effectiveGrantSequence())
        assertFalse("an incoherent read may not clear the OFF block",
            holder.tryClaimResyncEnable(2L, attemptAfterOff, true, holder.nextIntentSequence()))
    }

    /**
     * Re-review P1-B, holder level: enable ownership is a (generation, sequence)
     * token — an old generation's Confirmed drive cannot block the new instance,
     * the new instance may claim only once, and a newer OFF keeps the block.
     */
    @Test
    fun resyncEnableOwnership_isGenerationScopedAndOncePerGeneration() {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val grant = holder.startConsentIntent()
        assertTrue(holder.markConsentWriteOutcome(
            BbcWriteOutcome.Confirmed(true), grant, 1L, consumerOwnsEnable = true,
        ))
        assertEquals(grant, holder.latestEnableDrivenSequence())
        assertTrue(holder.isEnableOwner(grant, 1L))
        assertFalse("no second enable in the granting generation",
            holder.tryClaimResyncEnable(1L, grant, true, holder.nextIntentSequence()))

        // Rebind: the gen-1 token is evidence for gen 1 only.
        holder.onServiceGenerationChanged(2L)
        holder.resolve(read(2L, optIn = BbcOptInValue.TRUE, enablePending = true))
        assertFalse("a dead generation's token is not ownership of gen 2",
            holder.isEnableOwner(grant, 2L))
        val claim = holder.nextIntentSequence()
        assertTrue("the owed enable is claimable once on the NEW generation",
            holder.tryClaimResyncEnable(2L, grant, true, claim))
        assertEquals(claim, holder.latestEnableDrivenSequence())
        assertTrue(holder.isEnableOwner(claim, 2L))
        assertFalse("the same generation cannot claim twice",
            holder.tryClaimResyncEnable(2L, grant, true, holder.nextIntentSequence()))

        // A newer OFF at gen 3 still wins over the owed marker.
        holder.onServiceGenerationChanged(3L)
        holder.resolve(read(3L, optIn = BbcOptInValue.TRUE, enablePending = true))
        holder.supersedeActiveIntent()
        assertFalse("a newer OFF at the new generation blocks the resync",
            holder.tryClaimResyncEnable(3L, grant, true, holder.nextIntentSequence()))
    }

    /**
     * Re-review P1-B OFF guard: the generation-scoped ownership re-arm must not
     * weaken the newer-OFF rule. A fresh OFF at gen2 installs a higher abandon
     * sequence and blocks that generation's resync even though the durable
     * marker still reads true and the effect axis is still Unknown.
     */
    @Test
    fun newerOffAfterRebind_blocksOwedResyncEnable() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val commits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Unknown },
            io = dispatcher,
            consentCommitAt = { gen -> BbcConsentWriteResult(BbcWriteOutcome.Confirmed(true), gen) },
            consentRead = { gen ->
                read(gen, optIn = BbcOptInValue.TRUE, runtime = false, enablePending = true)
            },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)
        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true), commits) // gen1 enable, Unknown
        h.dispose()

        holder.onServiceGenerationChanged(2L)
        // The user turns OFF in the NEW generation: app-scoped abandon + block.
        holder.supersedeActiveIntent()
        assertTrue(holder.latestAbandonSequence() > holder.confirmedGrantSequence())

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a newer OFF at gen2 must block the owed enable", listOf(true), commits)
    }

    /** A newer OFF after a grant wins: the resync must not re-enable the effect. */
    @Test
    fun newerOffAfterGrant_suppressesResyncDrivenEnable() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = true, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = true))
        val commits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { generation -> BbcConsentWriteResult(BbcWriteOutcome.Unknown, generation) },
            consentRead = { generation -> read(generation, optIn = BbcOptInValue.TRUE, runtime = true) },
            consentHolder = holder,
        )

        // The user's OFF supersedes the grant in the shared sequence domain.
        holder.supersedeActiveIntent()
        coordinator.submit(runtime.nextSequence(), false) {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a newer OFF must never be overridden by a resync enable",
            listOf(false), commits)
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
    }

    /**
     * FINAL RACE (grant path): the TEST-ONLY seam parks the consumer right after
     * the effect-sequence mint. Production's atomic submit mints the OFF at
     * submission time on the shared counter, so the OFF recorded here is
     * strictly NEWER than the enable: it must win the slot/fence and be the
     * only runtime commit. (The original test's older-explicit-sequence premise
     * is no longer expressible through the production API; see inline comment.)
     */
    @Test
    fun consumerEnableCannotDisplaceOffEnqueuedAfterEffectSequenceMint() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val commits = Collections.synchronizedList(mutableListOf<Boolean>())

        val minted = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentCommitAt = { gen -> BbcConsentWriteResult(BbcWriteOutcome.Confirmed(true), gen) },
            consentRead = { gen -> read(gen, false) },
            consentHolder = holder,
        )
        coordinator.effectSequenceMintHook = { effectSequence ->
            minted.complete(effectSequence)
            release.await()
        }

        val grant = holder.startConsentIntent() // AUTHORISATION sequence
        coordinator.submitConsentWrite(grant, 1L) {}

        testScheduler.advanceUntilIdle() // consumer parks in the seam
        assertTrue("the consumer must be paused after the mint", minted.isCompleted)

        // The old premise ("OFF enqueued with an explicit sequence OLDER than
        // the enable") is not expressible through the production API: atomic
        // submit(value, onResult) mints at SUBMISSION time from the shared
        // counter, so an OFF submitted after the mint is strictly newer. Assert
        // the reachable invariant instead: the newer OFF wins the slot/fence and
        // is the ONLY runtime commit; the enable must not displace it.
        holder.supersedeActiveIntent()
        runtime.noteLatestUserIntent(false)
        val offSequence = coordinator.submit(false) {}
        assertTrue("the OFF must be enqueued after the effect-sequence mint",
            offSequence > minted.getCompleted())
        release.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("the OFF must be the only runtime commit", listOf(false), commits)
        assertEquals(false, runtime.confirmedOrUnknown)
        // With the atomic submit the OFF's own Confirmed mark acknowledges its
        // intent at the SAME sequence, so latestIntentProjectedValue() is null
        // (the old "newer intent over a stale OFF commit" premise is gone). The
        // single UI projection still renders UNCHECKED from Confirmed(false).
        assertNull("a confirmed OFF acknowledges its own atomic intent",
            runtime.latestIntentProjectedValue())
        assertEquals(false, bbcToggleUiProjection(
            holder.currentState(), runtime.effectStatus.value,
            runtime.latestIntentProjectedValue(),
        ).checked)
        assertEquals(BbcEffectStatus.Confirmed(false), runtime.effectStatus.value)
    }

    /**
     * FINAL RACE (resync path): same mint ordering, but the enable is claimed by
     * a cold-start resync instead of a Confirmed grant. The OFF recorded inside
     * the test-only pause must stay the single runtime commit.
     */
    @Test
    fun resyncEnableCannotDisplaceOffEnqueuedAfterEffectSequenceMint() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val grant = holder.startConsentIntent()
        assertTrue(holder.markConsentWriteOutcome(
            BbcWriteOutcome.Confirmed(true), grant, 1L, consumerOwnsEnable = true,
        ))
        holder.onServiceGenerationChanged(2L) // new instance still owes the enable
        holder.resolve(read(2L, optIn = BbcOptInValue.TRUE, enablePending = true))

        val commits = Collections.synchronizedList(mutableListOf<Boolean>())
        val minted = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentRead = { gen -> read(gen, optIn = BbcOptInValue.TRUE, enablePending = true) },
            consentHolder = holder,
        )
        coordinator.effectSequenceMintHook = { effectSequence ->
            minted.complete(effectSequence)
            release.await()
        }

        coordinator.submitConsentRead {}

        testScheduler.advanceUntilIdle()
        assertTrue("the consumer must be paused after the mint", minted.isCompleted)

        // Production submits OFF atomically (mint at submission time on the
        // shared counter), so the old "OFF enqueued with an older explicit
        // sequence" premise cannot be expressed here. An OFF recorded inside the
        // mint hook is strictly NEWER than the parked enable: it wins the slot
        // and the pre-commit fence and is the only runtime commit.
        holder.supersedeActiveIntent() // OFF also blocks this generation's resync
        runtime.noteLatestUserIntent(false)
        val offSequence = coordinator.submit(false) {}
        assertTrue("the OFF must be enqueued after the effect-sequence mint",
            offSequence > minted.getCompleted())
        release.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("the OFF must be the only runtime commit", listOf(false), commits)
        assertEquals(false, runtime.confirmedOrUnknown)
        // Atomic submit pairs the OFF intent with its commit sequence, so a
        // Confirmed(false) mark clears the pending intent (null, not false).
        // The one UI projection still resolves to UNCHECKED via Confirmed(false).
        assertNull("a confirmed OFF acknowledges its own atomic intent",
            runtime.latestIntentProjectedValue())
        assertEquals(false, bbcToggleUiProjection(
            holder.currentState(), runtime.effectStatus.value,
            runtime.latestIntentProjectedValue(),
        ).checked)
        assertEquals(BbcEffectStatus.Confirmed(false), runtime.effectStatus.value)
    }

    /**
     * Rebase review counterexample 2: a resync read that returns WITHOUT
     * enqueueing an enable must not mint from the shared intent counter. The
     * old mint-above-the-early-returns raised latestSequence() past an OFF that
     * was already queued at a lower sequence; the #81 pre-commit fence then
     * dropped that OFF. The OFF is submitted while the authoritative read is
     * parked and must be the only runtime commit.
     */
    @Test
    fun nonEnqueueingResyncRead_doesNotBurnSequenceOrDropQueuedOff() = runTest {
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val commits = Collections.synchronizedList(mutableListOf<Boolean>())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) },
            io = dispatcher,
            consentRead = { gen ->
                entered.complete(Unit)
                release.await()
                read(gen, BbcOptInValue.TRUE, runtime = false, enablePending = false)
            },
            consentHolder = holder,
        )

        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertTrue("the authoritative read must be parked", entered.isCompleted)

        val offSequence = coordinator.submit(false) {}
        release.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("a no-op resync must not drop the OFF queued during the read",
            listOf(false), commits)
        assertEquals("no non-enqueueing bookkeeping mint may advance the fence",
            offSequence, runtime.latestSequence())
        assertEquals(BbcEffectStatus.Confirmed(false), runtime.effectStatus.value)
    }

    /**
     * Deterministic rotation: ONE virtual TestScheduler drives the app IO
     * consumer AND both UI collectors. `commits` is synchronized, and the
     * completion barrier is the FULL effect pipeline — `effectStatus` is
     * published by `state.mark()` after its atomic state update, not by the
     * raw commit list. No real thread spin / no happens-before race remains.
     */
    @Test
    fun rotationDuringBlockedConsentWrite_convergesWithExactlyOneEffectToggle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val effectConfirmed = CompletableDeferred<Unit>()
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        try {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, optIn = false))
            val commits = Collections.synchronizedList(mutableListOf<Boolean>())
            val runtime = BbcToggleRuntimeState(initialConfirmed = false)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = runtime,
                commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) }, io = dispatcher,
                consentCommit = {
                    entered.complete(Unit)
                    release.await()
                    BbcWriteOutcome.Confirmed(true)
                },
                consentHolder = holder,
            )
            // Barrier tied to the published mark, not to the commit seam.
            val effectObserver = launch(dispatcher) {
                runtime.effectStatus.first { it == BbcEffectStatus.Confirmed(true) }
                effectConfirmed.complete(Unit)
            }
            val a = BbcConsentTestHarness(holder, coordinator, dispatcher)

            // Fresh install: first tap is durable runtime OFF.
            a.userToggle(false)
            withTimeout(5_000L) { testScheduler.advanceUntilIdle() }
            commits.clear() // below we count only the ENABLE handoff

            a.userToggle(true) // first ON -> consent dialog
            a.accept()         // enqueue consent write; A's callback will be dropped
            withTimeout(5_000L) { testScheduler.advanceUntilIdle() }
            assertTrue("the consent commit must be blocked before rotation", entered.isCompleted)
            a.dispose()        // rotation: UI-only teardown; the commit survives
            assertFalse("a disposed UI must be dead", a.alive)

            // Rebuilt Activity shares only the app-scoped holder/coordinator.
            val b = BbcConsentTestHarness(holder, coordinator, dispatcher)
            b.seedFromHolder() // still NotGranted while the consent write is blocked
            assertEquals(0, b.toggleCalls.size)

            release.complete(Unit)
            withTimeout(5_000L) { testScheduler.advanceUntilIdle() }
            withTimeout(5_000L) { effectConfirmed.await() }

            assertEquals(BbcActivationConsentState.Granted, holder.currentState())
            assertEquals("rebuilt UI must agree with the effect instead of showing a false ON",
                true, b.lastSettledChecked)
            assertEquals("exactly one effect toggle across the rotation", listOf(true), b.toggleCalls)
            assertEquals("exactly one effect enable commit", listOf(true), commits)
            assertEquals(true, runtime.confirmedOrUnknown)
            assertEquals("the dead UI must never dispatch the enable", listOf(false), a.toggleCalls)
            assertEquals("granted install must not re-ask after reopen", 0, b.dialogsShown.size)
            b.dispose()
            effectObserver.cancel()
        } finally {
            appScope.cancel()
        }
    }

    /**
     * Same deterministic virtual-time model as the rotation test above: the
     * old consent write is parked on a CompletableDeferred, and the post-release
     * barrier waits for the published `Confirmed(true)` effect mark before any
     * UI assertion. No real-thread scheduling remains in this test.
     */
    @Test
    fun rotationThenReopenedDialog_doesNotDoubleToggle_thePendingEnable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val effectConfirmed = CompletableDeferred<Unit>()
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        try {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, optIn = false))
            val runtime = BbcToggleRuntimeState(initialConfirmed = false)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = runtime,
                commit = { BbcWriteOutcome.Confirmed(it) }, io = dispatcher,
                consentCommit = {
                    entered.complete(Unit)
                    release.await()
                    BbcWriteOutcome.Confirmed(true)
                },
                consentHolder = holder,
            )
            val effectObserver = launch(dispatcher) {
                runtime.effectStatus.first { it == BbcEffectStatus.Confirmed(true) }
                effectConfirmed.complete(Unit)
            }
            val a = BbcConsentTestHarness(holder, coordinator, dispatcher)
            a.userToggle(true)
            a.accept()
            withTimeout(5_000L) { testScheduler.advanceUntilIdle() }
            assertTrue("the consent commit must be blocked before rotation", entered.isCompleted)
            a.dispose()

            val b = BbcConsentTestHarness(holder, coordinator, dispatcher)
            b.seedFromHolder()
            b.userToggle(true) // a NEW dialog is pending before the old write lands
            assertEquals(1, b.dialogsShown.size)

            release.complete(Unit)
            withTimeout(5_000L) { testScheduler.advanceUntilIdle() }
            withTimeout(5_000L) { effectConfirmed.await() }
            assertEquals("a pending dialog must suppress the replay", 0, b.toggleCalls.size)

            b.cancel() // OFF-wins invalidates the stale enable intent
            assertEquals(0, b.toggleCalls.size)
            assertEquals(BbcActivationConsentState.Granted, holder.currentState())

            b.userToggle(true) // Granted: this one explicit tap is the single effect intent
            assertEquals(listOf(true), b.toggleCalls)
            b.dispose()
            effectObserver.cancel()
        } finally {
            appScope.cancel()
        }
    }

    // ---- PR #90 review: ONE UI projection / authorization parity -------------

    private fun TestScope.coordinatorWithEffect(
        holder: BbcActivationConsentHolder,
        confirmedEffect: Boolean,
    ): BbcToggleCoordinator {
        val runtime = BbcToggleRuntimeState(initialConfirmed = false)
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(confirmedEffect), 1L))
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        return BbcToggleCoordinator(
            scope = appScope,
            state = runtime,
            commit = { BbcWriteOutcome.Confirmed(it) },
            io = StandardTestDispatcher(testScheduler),
            consentCommit = { BbcWriteOutcome.Unknown },
            consentHolder = holder,
        )
    }

    @Test
    fun coldStartOptInTrueWithRuntimeFalse_rendersUnchecked() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = false))
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertNull("authorization must never fabricate a checkbox value",
            holder.currentState().toggleUi().checked)

        val h = harness(holder, coordinatorWithEffect(holder, confirmedEffect = false))
        testScheduler.advanceUntilIdle()
        assertEquals("consent=true + runtime=false must render UNCHECKED", false, h.lastSettledChecked)
        assertEquals(0, h.toggleCalls.size)
        h.dispose()

        // Even with no effect mark at all (Provisional), a grant alone must not
        // fabricate ON: the neutral seed leaves the box unchecked.
        val h2 = harness(holder, newCoordinator(holder, { BbcWriteOutcome.Unknown }))
        testScheduler.advanceUntilIdle()
        assertEquals(false, h2.lastSettledChecked)
        h2.dispose()
    }

    @Test
    fun grantedEmissionAfterConfirmedFalse_doesNotRecheckTheBox() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = false)) // NotGranted, effect OFF
        val h = harness(holder, coordinatorWithEffect(holder, confirmedEffect = false))
        testScheduler.advanceUntilIdle()
        assertEquals(false, h.lastSettledChecked)

        // A rebind/resync finds durable opt-in=true. The newer authorization is
        // Granted, but the confirmed effect is still OFF: OFF wins.
        holder.resolve(read(1L, optIn = BbcOptInValue.TRUE, runtime = false))
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertEquals(false, h.lastSettledChecked)
        assertEquals("Granted authorization must not dispatch an effect by itself", 0, h.toggleCalls.size)
        h.dispose()
    }

    /**
     * PR #90 P1-3: a rebuilt View must NOT flip a user's in-flight OFF back ON.
     * The effect axis replays Confirmed(true) to the new per-View locals while
     * the OFF commit has not landed. Only the APP-SCOPED latest intent (recorded
     * at coordinator submit) can keep the box OFF — including after an Unknown
     * outcome, which is not an acknowledgement.
     */
    @Test
    fun rebuildDuringInFlightOff_keepsUserOffAfterReplayAndUnknown() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = true)) // Granted; OFF needs no consent
        val runtime = BbcToggleRuntimeState(initialConfirmed = false)
        val inheritedSeq = runtime.nextSequence() // reserve the inherited mark's sequence
        assertTrue("seed the inherited effect ON", runtime.mark(BbcWriteOutcome.Confirmed(true), inheritedSeq))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val outcome = CompletableDeferred<BbcWriteOutcome>()
        val coordinator = BbcToggleCoordinator(
            scope = appScope,
            state = runtime,
            commit = { outcome.await() }, // in flight until released below
            io = dispatcher,
            consentCommit = { BbcWriteOutcome.Unknown },
            consentHolder = holder,
        )

        val a = BbcConsentTestHarness(holder, coordinator, dispatcher)
        testScheduler.advanceUntilIdle()
        assertEquals(true, a.lastSettledChecked) // Confirmed(true) replay

        // Tap OFF: intent is recorded app-scoped; the commit blocks.
        a.userToggle(false)
        testScheduler.advanceUntilIdle()
        assertEquals(false, a.lastSettledChecked)

        // Rebuild the View BEFORE the OFF result lands. Its per-View locals are
        // fresh (pendingIntentChecked = null) but the app-scoped intent survives.
        val b = BbcConsentTestHarness(holder, coordinator, dispatcher)
        b.seedFromHolder()
        testScheduler.advanceUntilIdle()
        assertEquals("a rebuilt View must keep the in-flight OFF", false, b.lastSettledChecked)
        assertEquals("the old View must also stay OFF", false, a.lastSettledChecked)

        // A stale/inherited Confirmed(true) replay cannot supersede the newer
        // OFF intent (its sequence is older, so the fence drops it).
        assertFalse(runtime.mark(BbcWriteOutcome.Confirmed(true), inheritedSeq))
        testScheduler.advanceUntilIdle()
        assertEquals(false, b.lastSettledChecked)

        // The OFF commit reports Unknown: the user's last intent still wins.
        outcome.complete(BbcWriteOutcome.Unknown)
        testScheduler.advanceUntilIdle()
        assertEquals("Unknown must not revert to the stale Confirmed ON", false, b.lastSettledChecked)
        assertEquals(false, a.lastSettledChecked)
        a.dispose()
        b.dispose()
    }

    /**
     * P1-A counterexample 1: the newest OFF tap must be what the checkbox
     * renders, even while an older ON intent is still projected app-scoped
     * because its commit is blocked in the single writer.
     */
    @Test
    fun newestOffTapWinsOverStillProjectedOlderOnIntent() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(read(1L, optIn = true)) // Granted: no consent dialog on ON
        val runtime = BbcToggleRuntimeState(initialConfirmed = false)
        val confirmedSeq = runtime.nextSequence()
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(false), confirmedSeq))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val blocked = CompletableDeferred<BbcWriteOutcome>()
        val coordinator = BbcToggleCoordinator(
            scope = appScope,
            state = runtime,
            commit = { blocked.await() }, // ON enters the writer and parks here
            io = dispatcher,
            consentCommit = { BbcWriteOutcome.Unknown },
            consentHolder = holder,
        )
        val h = BbcConsentTestHarness(holder, coordinator, dispatcher)
        testScheduler.advanceUntilIdle()
        assertEquals(false, h.lastSettledChecked)

        h.userToggle(true) // ON@seq2: submitted, but the commit is blocked
        testScheduler.advanceUntilIdle()
        assertEquals(true, h.lastSettledChecked)
        assertEquals(0, h.dialogsShown.size)

        h.userToggle(false) // OFF@seq3 must be authoritative at render time
        testScheduler.advanceUntilIdle()
        assertEquals("the newest tap must not be rendered with the previous intent",
            false, h.lastSettledChecked)
        assertEquals(listOf(true, false), h.toggleCalls)

        blocked.complete(BbcWriteOutcome.Unknown)
        testScheduler.advanceUntilIdle()
        assertEquals(false, h.lastSettledChecked)
        h.dispose()
    }

    /**
     * P1-A counterexample 2: after the user accepts the dialog, the checkbox
     * must immediately show the chosen ON, not the older app-scoped OFF intent
     * left behind by the blocked preceding tap.
     */
    @Test
    fun acceptRendersChosenOnOverStaleProjectedOff() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        // An Unknown fence demands consent on ON but keeps the config gate open
        // (R5-02 closes it only for NotGranted), so the inherited confirmed ON
        // is still projectable while the dialog is being tested.
        holder.markConsentWriteOutcome(BbcWriteOutcome.Unknown, 1L, 1L)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false)
        val inherited = runtime.nextSequence()
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(true), inherited))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val blocked = CompletableDeferred<BbcWriteOutcome>()
        val coordinator = BbcToggleCoordinator(
            scope = appScope,
            state = runtime,
            commit = { blocked.await() }, // OFF's commit is still in flight
            io = dispatcher,
            consentCommit = { BbcWriteOutcome.Confirmed(true) },
            consentHolder = holder,
        )
        val h = BbcConsentTestHarness(holder, coordinator, dispatcher)
        testScheduler.advanceUntilIdle()
        assertEquals(true, h.lastSettledChecked) // inherited Confirmed(true)

        h.userToggle(false) // OFF@seq2 blocked in the writer
        testScheduler.advanceUntilIdle()
        assertEquals(false, h.lastSettledChecked)

        h.userToggle(true)
        assertEquals(1, h.dialogsShown.size)
        h.accept() // user just chose ON; the stale OFF must not repaint the box
        assertEquals("accept must render the user's ON immediately",
            true, h.lastSettledChecked)
        // Release the blocked OFF commit so the queued consent write can land;
        // neither it nor its Unknown outcome may undo the chosen ON.
        blocked.complete(BbcWriteOutcome.Unknown)
        testScheduler.advanceUntilIdle()
        assertEquals(true, h.lastSettledChecked)
        assertEquals(listOf(false, true), h.toggleCalls)
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        h.dispose()
    }

    /**
     * P1-A cancel case: cancel restores the pre-dialog value with ZERO effect
     * writes, and a later render (triggered by an async effect emission) must
     * not be hijacked by a stale app-scoped intent from a dead UI.
     */
    @Test
    fun cancelRestoreIsNotOverwrittenByStaleAppIntent() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        // Unknown fence: ON still asks consent, but the config gate stays open
        // so an inherited Confirmed(true) is a legitimate pre-dialog value.
        holder.markConsentWriteOutcome(BbcWriteOutcome.Unknown, 1L, 1L)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false)
        val inherited = runtime.nextSequence()
        assertTrue(runtime.mark(BbcWriteOutcome.Confirmed(true), inherited))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { BbcWriteOutcome.Confirmed(it) }, io = dispatcher,
            consentCommit = { BbcWriteOutcome.Unknown }, consentHolder = holder,
        )
        val h = BbcConsentTestHarness(holder, coordinator, dispatcher)
        testScheduler.advanceUntilIdle()
        assertEquals(true, h.lastSettledChecked)

        // A dead UI's older OFF intent is still app-scoped/projected.
        runtime.noteToggleIntent(inherited + 1L, false)
        h.userToggle(true) // opens the dialog; the box still shows the pre-dialog ON
        assertEquals(true, h.lastSettledChecked)

        h.cancel()
        assertEquals("cancel must restore the pre-dialog ON", true, h.lastSettledChecked)
        assertEquals(0, h.toggleCalls.size)

        // An async effect emission re-renders: the stale OFF intent must not
        // settle the restored value, and no effect write may be issued.
        assertTrue(runtime.mark(BbcWriteOutcome.Unknown))
        testScheduler.advanceUntilIdle()
        assertEquals("stale app intent must not overwrite the cancel restore",
            true, h.lastSettledChecked)
        assertEquals(0, h.toggleCalls.size)
        h.dispose()
    }

    @Test
    fun projection_pendingIntentWins_thenConfirmedEffect_thenNeutral() {
        assertEquals(
            "an unacknowledged user intent wins over a stale confirmed OFF",
            true,
            bbcToggleUiProjection(BbcActivationConsentState.Granted, BbcEffectStatus.Confirmed(false), true).checked,
        )
        // P1-3 explicit directions: a pending OFF must beat a Confirmed ON,
        // and a pending intent must beat both Provisional and Unknown effects.
        assertEquals(
            "a pending OFF wins over a replayed Confirmed ON",
            false,
            bbcToggleUiProjection(BbcActivationConsentState.Granted, BbcEffectStatus.Confirmed(true), false).checked,
        )
        assertEquals(true, bbcToggleUiProjection(
            BbcActivationConsentState.Granted, BbcEffectStatus.Provisional, true,
        ).checked)
        assertEquals(false, bbcToggleUiProjection(
            BbcActivationConsentState.Granted, BbcEffectStatus.Provisional, false,
        ).checked)
        assertEquals(true, bbcToggleUiProjection(
            BbcActivationConsentState.Granted, BbcEffectStatus.Unknown, true,
        ).checked)
        assertEquals(false, bbcToggleUiProjection(
            BbcActivationConsentState.Granted, BbcEffectStatus.Unknown, false,
        ).checked)
        assertEquals(
            false,
            bbcToggleUiProjection(BbcActivationConsentState.Granted, BbcEffectStatus.Confirmed(false), null).checked,
        )
        val initializing = bbcToggleUiProjection(
            BbcActivationConsentState.Initializing, BbcEffectStatus.Provisional, null,
        )
        assertNull(initializing.checked)
        assertTrue(initializing.neutral)
        val unknown = bbcToggleUiProjection(
            BbcActivationConsentState.Granted, BbcEffectStatus.Unknown, null,
        )
        assertNull("an unknown effect supplies no checkbox value", unknown.checked)
        assertTrue(unknown.neutral)
    }

    @Test
    fun explicitFalseOptIn_deniesEvenWithConfirmedLegacyMarker() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.markInstallClassificationConfirmed(1L)
        holder.resolve(
            read(
                1L, optIn = BbcOptInValue.FALSE,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = true, runtime = true,
            ),
        )
        assertEquals("explicit opt-out wins exactly like the Hook gate",
            BbcActivationConsentState.NotGranted, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))
    }

    @Test
    fun unreadableAuthoritativeSnapshot_neverAuthorizesLegacy() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.markInstallClassificationConfirmed(1L)
        holder.resolve(
            read(
                1L, optIn = BbcOptInValue.UNAVAILABLE,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = true, runtime = true, coherent = false,
            ),
        )
        assertEquals(BbcActivationConsentState.Initializing, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))

        // A same-generation read cannot lift the durable Unknown fence either.
        holder.markConsentWriteOutcome(BbcWriteOutcome.Unknown, 1L, 1L)
        holder.resolve(
            read(
                1L, optIn = BbcOptInValue.UNAVAILABLE,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                migrated = true, runtime = true, coherent = false,
            ),
        )
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
    }

    @Test
    fun legacyRequiresClassificationConfirmedForTheGeneration() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val legacyRead = read(
            1L, optIn = BbcOptInValue.ABSENT,
            classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
            migrated = true, runtime = false,
        )
        holder.resolve(legacyRead)
        assertEquals("an unconfirmed read must stay retryable/neutral",
            BbcActivationConsentState.Initializing, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))

        holder.markInstallClassificationConfirmed(1L)
        holder.resolve(legacyRead)
        assertEquals(BbcActivationConsentState.Legacy(false), holder.currentState())
        assertEquals(false, holder.currentState().toggleUi().checked)
        assertFalse(holder.currentState().requiresConsentFor(true))
    }
}
