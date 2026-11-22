// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// R5-0 (F01): Unknown warning Retry = a REAL authoritative refresh + fail-closed
// blind-clear. Framework-free decisions live in PreClearRetryUi.kt; this suite
// pins the VM seam (settled callback, one-shot guard) and the pure routing
// model with JVM tests only. Real AlertDialog/refresh UX remains DEVICE-
// UNVERIFIED (see residual-risk note in the implementer report).
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyUnknownRefreshRetryTest {
    // Same FakePolicyStore/makeVm seam shape as PolicyWriteTest, scoped to this
    // file so the two suites stay independent.
    private class FakePolicyStore(initialDraft: Set<String>? = setOf("com.seed")) {
        var saved: Set<String>? = initialDraft
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)
        var saveSucceeds = true
        var clearSucceeds = true
        val store: HookPolicyStore = HookPolicyStore(
            loadRaw = { loadCalls.incrementAndGet(); saved },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw == null) {
                    clearCalls.incrementAndGet()
                    if (clearSucceeds) saved = emptySet()
                    clearSucceeds
                } else {
                    if (saveSucceeds) saved = raw
                    saveSucceeds
                }
            },
        )
    }

    private val vms = CopyOnWriteArrayList<DualViewModel>()
    private val coords = CopyOnWriteArrayList<PolicyTxCoordinator>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        resetRemotePoisonForTests()
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try { vm.dispose() } catch (_: Throwable) { }
            try { vm.viewModelScope.cancel() } catch (_: Throwable) { }
        }
        TestDrainSupport.drainBeforeResetMain(vms, coords)
        vms.clear()
        for (c in coords) {
            try { c.close() } catch (_: Throwable) { }
        }
        coords.clear()
        resetRemotePoisonForTests()
        Dispatchers.resetMain()
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout after ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    private fun makeVm(
        store: HookPolicyStore?,
        initialDraft: Set<String>? = setOf("com.seed"),
        requirePolicyWriteConsent: Boolean = false,
    ): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = emptyList(),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { false },
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.NOOP,
            hookDirectReads = { listOf(MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            hookPolicyStore = store,
            requirePolicyWriteConsent = requirePolicyWriteConsent,
            currentUserId = { 0 },
            nowMonoMs = { System.currentTimeMillis() },
            policyText = { it.defaultText() },
        )
        vms.add(vm)
        if (store != null && initialDraft != null) {
            await { vm.state.value.policyDraftKnown }
        }
        await { !vm.state.value.loading }
        return vm
    }

    private fun blindClearArmAtMs(vm: DualViewModel): Long {
        val f = DualViewModel::class.java.getDeclaredField("blindClearArmedAtMs").apply { isAccessible = true }
        return (f.get(vm) as AtomicLong).get()
    }

    // Reflection lets the test wait for the one-shot guard's finally-release
    // instead of racing the settled callback's own bookkeeping.
    private fun refreshRetryGuardHeld(vm: DualViewModel): Boolean {
        val f = DualViewModel::class.java.getDeclaredField("refreshRetryOneShot").apply { isAccessible = true }
        return (f.get(vm) as AtomicBoolean).get()
    }

    private fun unknownVm(fake: FakePolicyStore): DualViewModel {
        val vm = makeVm(fake.store, initialDraft = null)
        await { !vm.state.value.policyDraftKnown && !vm.state.value.loading }
        return vm
    }

    // ---------------------------------------------------------------------
    // VM seam: a retry really re-reads the store and settles exactly once.
    // ---------------------------------------------------------------------

    @Test
    fun unknownRefreshRetry_storeBecomesKnown_invokesOnceAndContinues() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = unknownVm(fake)
        val loadsBefore = fake.loadCalls.get()
        val settled = CountDownLatch(1)
        val decisions = synchronizedListOf<PolicyRefreshSettled>()

        fake.saved = setOf("com.authoritative")
        vm.refreshPolicyDraftForWarningRetry { outcome ->
            decisions.add(outcome)
            settled.countDown()
        }

        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals(1, decisions.size)
        assertEquals(PolicyRefreshSettled(known = true, corrupt = false), decisions[0])
        assertEquals(PolicyWarningRetryDecision.CONTINUE, policyWarningRetryDecision(decisions[0]))
        assertTrue("a real load must have happened", fake.loadCalls.get() > loadsBefore)
        assertEquals(0, fake.saveCalls.get())
        assertEquals(0, fake.clearCalls.get())
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.authoritative"), vm.state.value.policyDraft)
    }

    @Test
    fun unknownRefreshRetry_stillUnavailable_invokesOnce_noReentry_noClear() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = unknownVm(fake)
        val loadsBefore = fake.loadCalls.get()
        val settled = CountDownLatch(1)
        val decisions = synchronizedListOf<PolicyRefreshSettled>()

        vm.refreshPolicyDraftForWarningRetry { outcome ->
            decisions.add(outcome)
            settled.countDown()
        }

        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals(1, decisions.size)
        assertEquals(PolicyRefreshSettled(known = false, corrupt = false), decisions[0])
        assertEquals(PolicyWarningRetryDecision.UNAVAILABLE_GUIDE, policyWarningRetryDecision(decisions[0]))
        assertTrue(fake.loadCalls.get() > loadsBefore)
        assertEquals(0, fake.saveCalls.get())
        assertEquals(0, fake.clearCalls.get())
        assertFalse(vm.state.value.policyDraftKnown)

        // The pure dispatcher must not call onContinue for this settle.
        val continues = AtomicInteger(0)
        applyWarningRetryDecision(
            decision = policyWarningRetryDecision(decisions[0]),
            hostAlive = true,
            onContinue = { continues.incrementAndGet() },
            onCorrupt = { },
            onUnavailable = { },
        )
        assertEquals(0, continues.get())
    }

    @Test
    fun unknownRefreshRetry_corruptStore_routesCorruptGuide_zeroClear() {
        var isCorrupt = true
        var liveSet = mutableSetOf<String>()
        val clearCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { if (isCorrupt) throw ClassCastException("corrupt") else liveSet },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet()
                if (raw != null) liveSet = raw.toMutableSet() else liveSet.clear()
                isCorrupt = false
                true
            },
            repairProbe = { if (isCorrupt) LoadResult.Corrupt else LoadResult.Known(liveSet) },
        )
        val vm = makeVm(store, initialDraft = null)
        await { vm.state.value.policyDraftCorrupt && !vm.state.value.loading }
        val settled = CountDownLatch(1)
        val decisions = synchronizedListOf<PolicyRefreshSettled>()

        vm.refreshPolicyDraftForWarningRetry { outcome ->
            decisions.add(outcome)
            settled.countDown()
        }

        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals(1, decisions.size)
        assertTrue("corrupt bit must be reported: ${decisions[0]}", decisions[0].corrupt)
        assertFalse(decisions[0].known)
        assertEquals(PolicyWarningRetryDecision.CORRUPT_GUIDE, policyWarningRetryDecision(decisions[0]))
        assertEquals(0, clearCalls.get())
    }

    @Test
    fun unknownRefreshRetry_doubleTap_droppedByOneShotGuard() {
        val loadCalls = AtomicInteger(0)
        val firstRefreshStarted = CountDownLatch(1)
        val releaseFirstRefresh = AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                val n = loadCalls.incrementAndGet()
                if (n == 2) {
                    firstRefreshStarted.countDown()
                    while (!releaseFirstRefresh.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                }
                setOf("com.authoritative")
            },
            saveRaw = { true },
        )
        val vm = makeVm(store, initialDraft = setOf("com.authoritative"))
        val settled = AtomicInteger(0)

        try {
            vm.refreshPolicyDraftForWarningRetry { settled.incrementAndGet() }
            vm.refreshPolicyDraftForWarningRetry { settled.incrementAndGet() }
            assertTrue(firstRefreshStarted.await(5, TimeUnit.SECONDS))
            // Exactly one refresh reached the store; the second tap was dropped.
            assertEquals(2, loadCalls.get())
        } finally {
            releaseFirstRefresh.set(true)
        }
        await { settled.get() == 1 }
        assertEquals(1, settled.get())
        assertEquals(2, loadCalls.get())

        // Guard is released in the settled callback's finally: a later, genuine
        // tap still performs a fresh refresh.
        await { !refreshRetryGuardHeld(vm) }
        vm.refreshPolicyDraftForWarningRetry { settled.incrementAndGet() }
        await { settled.get() == 2 }
        assertTrue("later tap must re-read", loadCalls.get() > 2)
    }

    @Test
    fun unknownRefreshRetry_settledCallbackSurvivesEarlyReturnPaths() {
        val calls = AtomicInteger(0)
        val staleStarted = CountDownLatch(1)
        val releaseStale = AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    1 -> setOf("com.INIT")
                    2 -> {
                        staleStarted.countDown()
                        while (!releaseStale.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                        setOf("com.OLD")
                    }
                    else -> setOf("com.NEW")
                }
            },
            saveRaw = { true },
        )
        val vm = makeVm(store, initialDraft = setOf("com.INIT"))
        val settledA = synchronizedListOf<PolicyRefreshSettled>()
        val settledB = synchronizedListOf<PolicyRefreshSettled>()

        try {
            vm.refreshPolicyDraftForWarningRetry { settledA.add(it) } // A: parked, later discarded
            assertTrue(staleStarted.await(5, TimeUnit.SECONDS))
            vm.refreshPolicyDraft { settledB.add(it) } // B: fresh, publishes Known
            await { vm.state.value.policyDraft == setOf("com.NEW") }
        } finally {
            releaseStale.set(true)
        }

        // A hit the stale-generation early exit; B completed normally. BOTH
        // settled callbacks must fire exactly once, and both must report the
        // state-sampled Known bit — NOT a freshness-gen-equality artifact.
        await { settledA.size == 1 && settledB.size == 1 }
        assertEquals(1, settledA.size)
        assertEquals(1, settledB.size)
        assertEquals(PolicyRefreshSettled(known = true, corrupt = false), settledA[0])
        assertEquals(PolicyRefreshSettled(known = true, corrupt = false), settledB[0])
        assertEquals(setOf("com.NEW"), vm.state.value.policyDraft)
    }

    // ---------------------------------------------------------------------
    // Pure decision model (no Activity / no dialogs).
    // ---------------------------------------------------------------------

    @Test
    fun pure_policyWarningRetryDecision_continuesOnlyOnKnown() {
        assertEquals(
            PolicyWarningRetryDecision.CONTINUE,
            policyWarningRetryDecision(PolicyRefreshSettled(known = true, corrupt = false)),
        )
        assertEquals(
            PolicyWarningRetryDecision.UNAVAILABLE_GUIDE,
            policyWarningRetryDecision(PolicyRefreshSettled(known = false, corrupt = false)),
        )
        assertEquals(
            PolicyWarningRetryDecision.CORRUPT_GUIDE,
            policyWarningRetryDecision(PolicyRefreshSettled(known = false, corrupt = true)),
        )
        // Corrupt wins over Known (fail-closed).
        assertEquals(
            PolicyWarningRetryDecision.CORRUPT_GUIDE,
            policyWarningRetryDecision(PolicyRefreshSettled(known = true, corrupt = true)),
        )

        for (decision in enumValues<PolicyWarningRetryDecision>()) {
            val hits = IntArray(3)
            applyWarningRetryDecision(
                decision = decision,
                hostAlive = true,
                onContinue = { hits[0]++ },
                onCorrupt = { hits[1]++ },
                onUnavailable = { hits[2]++ },
            )
            val expected = when (decision) {
                PolicyWarningRetryDecision.CONTINUE -> intArrayOf(1, 0, 0)
                PolicyWarningRetryDecision.CORRUPT_GUIDE -> intArrayOf(0, 1, 0)
                PolicyWarningRetryDecision.UNAVAILABLE_GUIDE -> intArrayOf(0, 0, 1)
            }
            assertTrue("decision=$decision hits=${hits.toList()}", hits.toList() == expected.toList())
        }

        // hostAlive gating: a late settle after Activity finish does nothing.
        var calls = 0
        applyWarningRetryDecision(
            decision = PolicyWarningRetryDecision.CONTINUE,
            hostAlive = false,
            onContinue = { calls++ },
            onCorrupt = { calls++ },
            onUnavailable = { calls++ },
        )
        assertEquals(0, calls)
    }

    // ---------------------------------------------------------------------
    // Fail-closed clear + no manufactured consent.
    // ---------------------------------------------------------------------

    @Test
    fun blindClearDisabled_unavailableTwoTapsNeverClear() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = unknownVm(fake)

        vm.clearPolicyDraft()
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(0, fake.clearCalls.get())
        assertEquals(-1L, blindClearArmAtMs(vm))

        vm.clearPolicyDraft()
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(0, fake.clearCalls.get())
        assertEquals(0, fake.saveCalls.get())
        assertEquals(-1L, blindClearArmAtMs(vm))
        assertFalse(vm.state.value.policyDraftKnown)
    }

    @Test
    fun unknownRetry_neverManufacturesNoRemovalConsent() {
        assertFalse("Unknown delta=∅ is a pseudo-empty set", noRemovalAllowed(false, emptySet()))
        assertTrue(noRemovalAllowed(true, emptySet()))

        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, initialDraft = null, requirePolicyWriteConsent = true)
        await { !vm.state.value.policyDraftKnown && !vm.state.value.loading }

        vm.replacePolicyDraft(setOf("com.x"), consent = null)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(0, fake.saveCalls.get())
        assertEquals(0, fake.clearCalls.get())
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(
            PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(),
            vm.state.value.msg,
        )
    }

    // Small helper keeping the settled-result lists thread-safe across the IO
    // refresh thread and the JVM test thread.
    private fun <T> synchronizedListOf(): MutableList<T> =
        java.util.Collections.synchronizedList(mutableListOf())
}
