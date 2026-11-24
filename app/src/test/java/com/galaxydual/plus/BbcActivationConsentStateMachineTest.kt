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

    private fun read(
        generation: Long,
        optIn: Boolean?,
        classification: String? = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        runtime: Boolean = false,
    ) = BbcConsentAuthoritativeRead(generation, optIn, classification, runtime)

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
        holder.resolve(
            read(
                1L, optIn = false,
                classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
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
        var commitThread = ""
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
}
