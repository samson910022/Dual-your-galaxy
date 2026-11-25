// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase C (C1-C6): executable state-machine tests for the app-scoped BBC
// activation/consent model. All order/IO is driven by TestScheduler gates or
// real-thread latches — no sleeps, no Robolectric, no source-substring pins.
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    ) = bbcConsentAuthoritativeReadLegacyBoolean(generation, optIn, classification, migrated, runtime, coherent)

    /** Explicit enum helper for the ABSENT-vs-FALSE-vs-UNAVAILABLE parity cases. */
    private fun read(
        generation: Long,
        optIn: BbcOptInValue,
        classification: String? = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        runtime: Boolean? = false,
        migrated: Boolean? = null,
        coherent: Boolean = true,
    ) = BbcConsentAuthoritativeRead(generation, optIn, classification, migrated, runtime, coherent)

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
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
                commit = { BbcWriteOutcome.Confirmed(it) }, io = io,
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

    @Test
    fun rotationDuringBlockedConsentWrite_convergesWithExactlyOneEffectToggle() = runBlocking {
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-consent-rotation-io") }.asCoroutineDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder = BbcActivationConsentHolder()
            holder.onServiceGenerationChanged(1L)
            holder.resolve(read(1L, optIn = false))
            val commits = mutableListOf<Boolean>()
            val runtime = BbcToggleRuntimeState(initialConfirmed = false)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = runtime,
                commit = { value -> commits.add(value); BbcWriteOutcome.Confirmed(value) }, io = io,
                consentCommit = {
                    entered.countDown()
                    release.await()
                    BbcWriteOutcome.Confirmed(true)
                },
                consentHolder = holder,
            )
            val a = BbcConsentTestHarness(holder, coordinator, Dispatchers.Unconfined)

            // Fresh install: first tap is durable runtime OFF.
            a.userToggle(false)
            withTimeout(5_000L) { while (commits.isEmpty()) yield() }
            commits.clear() // below we count only the ENABLE handoff

            a.userToggle(true) // first ON -> consent dialog
            a.accept()         // enqueue consent write; A's callback will be dropped
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            a.dispose()        // rotation: UI-only teardown; the commit survives
            assertFalse("a disposed UI must be dead", a.alive)

            // Rebuilt Activity shares only the app-scoped holder/coordinator.
            val b = BbcConsentTestHarness(holder, coordinator, Dispatchers.Unconfined)
            b.seedFromHolder() // still NotGranted while the consent write is blocked
            assertEquals(0, b.toggleCalls.size)

            release.countDown()
            withTimeout(5_000L) { while (holder.currentState() !is BbcActivationConsentState.Granted) yield() }
            withTimeout(5_000L) { while (commits.isEmpty()) yield() }

            assertEquals(BbcActivationConsentState.Granted, holder.currentState())
            assertEquals("rebuilt UI must agree with the effect instead of showing a false ON",
                true, b.lastSettledChecked)
            assertEquals("exactly one effect toggle across the rotation", listOf(true), b.toggleCalls)
            assertEquals("exactly one effect enable commit", listOf(true), commits)
            assertEquals(true, runtime.confirmedOrUnknown)
            assertEquals("the dead UI must never dispatch the enable", listOf(false), a.toggleCalls)
            assertEquals("granted install must not re-ask after reopen", 0, b.dialogsShown.size)
            b.dispose()
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    @Test
    fun rotationThenReopenedDialog_doesNotDoubleToggle_thePendingEnable() = runBlocking {
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-consent-reopen-io") }.asCoroutineDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
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
                    BbcWriteOutcome.Confirmed(true)
                },
                consentHolder = holder,
            )
            val a = BbcConsentTestHarness(holder, coordinator, Dispatchers.Unconfined)
            a.userToggle(true)
            a.accept()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            a.dispose()

            val b = BbcConsentTestHarness(holder, coordinator, Dispatchers.Unconfined)
            b.seedFromHolder()
            b.userToggle(true) // a NEW dialog is pending before the old write lands
            assertEquals(1, b.dialogsShown.size)

            release.countDown()
            withTimeout(5_000L) { while (holder.currentState() !is BbcActivationConsentState.Granted) yield() }
            yield()
            assertEquals("a pending dialog must suppress the replay", 0, b.toggleCalls.size)

            b.cancel() // OFF-wins invalidates the stale enable intent
            assertEquals(0, b.toggleCalls.size)
            assertEquals(BbcActivationConsentState.Granted, holder.currentState())

            b.userToggle(true) // Granted: this one explicit tap is the single effect intent
            assertEquals(listOf(true), b.toggleCalls)
            b.dispose()
        } finally {
            appScope.cancel()
            io.close()
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
        holder.resolve(read(1L, optIn = false)) // NotGranted; OFF needs no consent
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
        holder.resolve(read(1L, optIn = false)) // NotGranted: ON demands consent
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
        holder.resolve(read(1L, optIn = false)) // NotGranted
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
