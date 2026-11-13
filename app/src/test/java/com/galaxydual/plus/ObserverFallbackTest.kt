// Phase6 (P1 observer 雙路徑 false-authority) barrier tests.
//
// Covers the shared resolveObserverFallback used symmetrically by
// launchLoserSync and observeSettledFor:
// - T1 inherited-VM never-completing Deferred + Known old/new stays
//   RECONCILING+Unknown with pending/fence alive (no false SUCCESS);
//   completing true with matching read adopts SUCCESS, completing false
//   with mismatched read adopts FAILED.
// - T2 CAS-loser late-failure never converges to IDLE+Known(B)+REFRESHED:
//   a proven failure with ground B must adopt FAILED+Unknown, never Known B.
// - T3 I1 discard: winner takeover (activeTx swap + version bump) discards a
//   would-be SUCCESS (RECONCILING + draft untouched, only loser key removed).
// - T4 TOCTOU discard: version/ownership bump inside await() discards a
//   would-be SUCCESS (same contract as T3, active stays loser).
// - T5 canJoinTx false (unarmed + incomplete) aborts to IDLE+Unknown+
//   UNKNOWN_REFRESH, never joins.
// - T6 Unknown branches: completed false adopts FAILED+Unknown; completed true
//   retains RECONCILING+Unknown+REFRESH_FAILED (never trusts ack).
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.InternalForInheritanceCoroutinesApi::class,
)
class ObserverFallbackTest {
    private val vms = mutableListOf<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        resetRemotePoisonForTests()
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try { vm.dispose() } catch (_: Exception) { }
            try { vm.viewModelScope.cancel() } catch (_: Exception) { }
        }
        vms.clear()
        Dispatchers.resetMain()
        resetRemotePoisonForTests()
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout after ${timeoutMs}ms")
            }
            Thread.sleep(20)
        }
    }

    private fun makeVm(
        policyStore: HookPolicyStore,
        coord: PolicyTxCoordinator,
        policyIoTimeoutMs: Long = 200L,
        awaitKnown: Boolean = true,
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
            hookPolicyStore = policyStore,
            currentUserId = { 0 },
            policyIoTimeoutMs = policyIoTimeoutMs,
            policyTxCoordinator = coord,
        )
        vms.add(vm)
        await { !vm.state.value.loading }
        if (awaitKnown) {
            await { vm.state.value.policyDraftKnown }
        }
        return vm
    }

    @Suppress("UNCHECKED_CAST")
    private fun setReconciling(
        vm: DualViewModel,
        tx: DualViewModel.PolicyTx,
        pending: PendingPolicyWrite?,
        draft: Set<String>,
        coord: PolicyTxCoordinator? = null,
        corrupt: Boolean = tx.isRepair,
    ) {
        val stateF = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = stateF.get(vm) as MutableStateFlow<UiState>
        state.value = state.value.copy(
            pendingPolicyWrite = pending,
            policyWritePhase = PolicyWritePhase.RECONCILING,
            policyEpoch = state.value.policyEpoch + 1,
            policyDraft = draft,
            policyDraftKnown = false,
            policyDraftCorrupt = corrupt,
            loading = false,
            msg = PolicyText(PolicyTextKind.WRITE_TIMEOUT).defaultText(),
            msgKind = MsgKind.CHANNEL,
        )
        val txF = DualViewModel::class.java.getDeclaredField("activePolicyTx").apply { isAccessible = true }
        (txF.get(vm) as java.util.concurrent.atomic.AtomicReference<DualViewModel.PolicyTx?>).set(tx)
        val recF = DualViewModel::class.java.getDeclaredField("policyReconciling").apply { isAccessible = true }
        (recF.get(vm) as MutableSet<String>).add(tx.key)
        if (coord != null) {
            coord.activeTx.set(tx)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun activeTxOf(vm: DualViewModel): DualViewModel.PolicyTx? {
        val f = DualViewModel::class.java.getDeclaredField("activePolicyTx").apply { isAccessible = true }
        return (f.get(vm) as java.util.concurrent.atomic.AtomicReference<DualViewModel.PolicyTx?>).get()
    }

    @Suppress("UNCHECKED_CAST")
    private fun reconcilingHas(vm: DualViewModel, key: String): Boolean {
        val f = DualViewModel::class.java.getDeclaredField("policyReconciling").apply { isAccessible = true }
        return (f.get(vm) as MutableSet<String>).contains(key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setActiveTx(vm: DualViewModel, tx: DualViewModel.PolicyTx?) {
        val f = DualViewModel::class.java.getDeclaredField("activePolicyTx").apply { isAccessible = true }
        (f.get(vm) as java.util.concurrent.atomic.AtomicReference<DualViewModel.PolicyTx?>).set(tx)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addReconcilingKey(vm: DualViewModel, key: String) {
        val f = DualViewModel::class.java.getDeclaredField("policyReconciling").apply { isAccessible = true }
        (f.get(vm) as MutableSet<String>).add(key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeReconcilingKey(vm: DualViewModel, key: String) {
        val f = DualViewModel::class.java.getDeclaredField("policyReconciling").apply { isAccessible = true }
        (f.get(vm) as MutableSet<String>).remove(key)
    }

    private fun lastRevisionSeenRevOf(vm: DualViewModel): Long {
        val f = DualViewModel::class.java.getDeclaredField("lastRevisionSeenRev").apply { isAccessible = true }
        return (f.get(vm) as java.util.concurrent.atomic.AtomicLong).get()
    }

    private fun revisionStaleOf(vm: DualViewModel): Boolean {
        val f = DualViewModel::class.java.getDeclaredField("policyRevisionStale").apply { isAccessible = true }
        return (f.get(vm) as AtomicBoolean).get()
    }

    private fun bumpVersionsForToctou(vm: DualViewModel) {
        // policyDraftVersion/policyOwnershipGen/policyFreshnessGen are file-private
        // top-level counters (DualViewModelKt facade, shared across VMs), not
        // DualViewModel members — reflect on the Kt facade (static fields).
        try {
            val kt = Class.forName("com.galaxydual.plus.DualViewModelKt")
            val vF = kt.getDeclaredField("policyDraftVersion").apply { isAccessible = true }
            (vF.get(null) as java.util.concurrent.atomic.AtomicLong).incrementAndGet()
            val oF = kt.getDeclaredField("policyOwnershipGen").apply { isAccessible = true }
            (oF.get(null) as java.util.concurrent.atomic.AtomicLong).incrementAndGet()
            try {
                val fF = kt.getDeclaredField("policyFreshnessGen").apply { isAccessible = true }
                (fF.get(null) as java.util.concurrent.atomic.AtomicLong).incrementAndGet()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
            // Fallback if counters ever move inside the VM (best effort).
            try {
                val vF2 = DualViewModel::class.java.getDeclaredField("policyDraftVersion").apply { isAccessible = true }
                (vF2.get(vm) as java.util.concurrent.atomic.AtomicLong).incrementAndGet()
            } catch (_: Exception) {
            }
            try {
                val oF2 = DualViewModel::class.java.getDeclaredField("policyOwnershipGen").apply { isAccessible = true }
                (oF2.get(vm) as java.util.concurrent.atomic.AtomicLong).incrementAndGet()
            } catch (_: Exception) {
            }
        }
    }

    // T1: inherited-VM never-completing Deferred + Known old/new retains;
    // completing true adopts SUCCESS, completing false adopts FAILED.
    @Test
    fun t1_inheritedVm_incompleteKnownRetains_thenSuccessOrFailed() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)
            // Inherited Tx: sanitized B (new), backend A (old). Armed, never completes.
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pending = PendingPolicyWrite(
                packages = setOf("com.B"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val tx = DualViewModel.PolicyTx(
                coord.nextTxId(), "t1",
                deferred, setOf("com.B"), store, false, pending, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, tx, pending, setOf("com.A"))

            // Incomplete + Known old (A) -> Retain: no IDLE, no SUCCESS.
            runBlocking { vm.resolveObserverFallback(tx, LoadResult.Known(setOf("com.A")), false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertNotNull(vm.state.value.pendingPolicyWrite)
            assertTrue(activeTxOf(vm) === tx)
            assertTrue(reconcilingHas(vm, "t1"))
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertEquals(
                PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText(),
                vm.state.value.msg,
            )
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)

            // Incomplete + Known new (B == sanitized) -> still Retain (never trust ack early).
            runBlocking { vm.resolveObserverFallback(tx, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertNotNull(vm.state.value.pendingPolicyWrite)
            assertTrue(activeTxOf(vm) === tx)
            assertTrue(reconcilingHas(vm, "t1"))
            assertFalse(vm.state.value.msg.contains("已寫入"))

            // Complete true + Known matching -> Adopt SUCCESS.
            assertTrue(deferred.complete(true))
            runBlocking { vm.resolveObserverFallback(tx, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vm.state.value.policyDraft)
            assertEquals(MsgKind.INFO, vm.state.value.msgKind)
            assertTrue(vm.state.value.msg.contains("已寫入"))
            assertNull(vm.state.value.pendingPolicyWrite)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            assertFalse(reconcilingHas(vm, "t1"))

            // Separate Tx for the false branch: sanitized B, ground A, proven failure.
            val deferred2 = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pending2 = PendingPolicyWrite(
                packages = setOf("com.B"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val tx2 = DualViewModel.PolicyTx(
                coord.nextTxId(), "t1f",
                deferred2, setOf("com.B"), store, false, pending2, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, tx2, pending2, setOf("com.A"), coord)
            runBlocking { vm.resolveObserverFallback(tx2, LoadResult.Known(setOf("com.A")), false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertTrue(activeTxOf(vm) === tx2)
            assertTrue(deferred2.complete(false))
            runBlocking { vm.resolveObserverFallback(tx2, LoadResult.Known(setOf("com.A")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(
                PolicyText(PolicyTextKind.WRITE_FAILED).defaultText(),
                vm.state.value.msg,
            )
            assertNull(vm.state.value.pendingPolicyWrite)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            assertFalse(reconcilingHas(vm, "t1f"))
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // T2: CAS-loser late-failure never adopts IDLE+Known(B)+REFRESHED.
    // Ground B with a proven-failed deferred must adopt FAILED+Unknown,
    // even though B is readable; ok=true mismatch adopts REFRESHED+ground B
    // but never SUCCESS.
    @Test
    fun t2_casLoser_lateFailureNeverKnownB() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.B"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)
            // Loser Tx sanitized C, ground B. Incomplete -> Retain (not mismatch).
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pending = PendingPolicyWrite(
                packages = setOf("com.C"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val tx = DualViewModel.PolicyTx(
                coord.nextTxId(), "loser",
                deferred, setOf("com.C"), store, false, pending, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, tx, pending, setOf("com.A"), coord)

            runBlocking { vm.resolveObserverFallback(tx, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(activeTxOf(vm) === tx)
            assertTrue(coord.activeTx.get() === tx)
            assertTrue(reconcilingHas(vm, "loser"))

            // Late failure: deferred completes false, ground still B.
            // Must adopt FAILED+Unknown, never IDLE+Known(B)+REFRESHED.
            assertTrue(deferred.complete(false))
            runBlocking { vm.resolveObserverFallback(tx, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(
                PolicyText(PolicyTextKind.WRITE_FAILED).defaultText(),
                vm.state.value.msg,
            )
            assertFalse(vm.state.value.policyDraftKnown && vm.state.value.policyDraft == setOf("com.B"))
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            assertFalse(reconcilingHas(vm, "loser"))

            // Mismatch branch: ok=true with ground B != sanitized C adopts
            // REFRESHED+ground B but never SUCCESS.
            val deferred3 = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pending3 = PendingPolicyWrite(
                packages = setOf("com.C"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val tx3 = DualViewModel.PolicyTx(
                coord.nextTxId(), "loser3",
                deferred3, setOf("com.C"), store, false, pending3, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, tx3, pending3, setOf("com.A"), coord)
            assertTrue(deferred3.complete(true))
            runBlocking { vm.resolveObserverFallback(tx3, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vm.state.value.policyDraft)
            assertEquals(MsgKind.INFO, vm.state.value.msgKind)
            assertEquals(
                PolicyText(PolicyTextKind.REFRESHED).defaultText(),
                vm.state.value.msg,
            )
            assertFalse(vm.state.value.msg.contains("已寫入"))
            assertNull(coord.activeTx.get())

            // Timed-out read retains even when the deferred already completed.
            val deferred4 = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pending4 = PendingPolicyWrite(
                packages = setOf("com.C"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val tx4 = DualViewModel.PolicyTx(
                coord.nextTxId(), "loser4",
                deferred4, setOf("com.C"), store, false, pending4, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, tx4, pending4, setOf("com.A"), coord)
            assertTrue(deferred4.complete(true))
            runBlocking { vm.resolveObserverFallback(tx4, LoadResult.Known(setOf("com.C")), true) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(activeTxOf(vm) === tx4)
            assertTrue(coord.activeTx.get() === tx4)
            assertTrue(reconcilingHas(vm, "loser4"))
            // Cancel the orphaned deferred4 path state: settle via success read without timeout.
            runBlocking { vm.resolveObserverFallback(tx4, LoadResult.Known(setOf("com.C")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertNull(coord.activeTx.get())
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // T3: I1 discard — loser Adopt must not overwrite a newer winner.
    // setReconciling loser, then winner takes over (activeTx swap + version bump
    // + winner key), then resolve loser with Known matching (would-be SUCCESS).
    // Must discard: RECONCILING + draft/msg untouched, only loser key removed.
    @Test
    fun t3_i1_winnerTakeover_discardsLoserAdopt() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)
            val deferredLoser = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pendingLoser = PendingPolicyWrite(
                packages = setOf("com.C"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val loser = DualViewModel.PolicyTx(
                coord.nextTxId(), "loser",
                deferredLoser, setOf("com.C"), store, false, pendingLoser, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, loser, pendingLoser, setOf("com.A"), coord)
            assertTrue(deferredLoser.complete(true))

            // Winner takes over: swap active (local + coordinator), bump versions,
            // add winner key so reconciling holds both before loser resolve.
            val deferredWinner = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pendingWinner = PendingPolicyWrite(
                packages = setOf("com.B"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val winner = DualViewModel.PolicyTx(
                coord.nextTxId(), "winner",
                deferredWinner, setOf("com.B"), store, false, pendingWinner, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setActiveTx(vm, winner)
            try { coord.activeTx.set(winner) } catch (_: Exception) { }
            addReconcilingKey(vm, "winner")
            bumpVersionsForToctou(vm)

            val draftBefore = vm.state.value.policyDraft
            // Would-be SUCCESS (completed true + Known == sanitized) must discard via I1.
            runBlocking { vm.resolveObserverFallback(loser, LoadResult.Known(setOf("com.C")), false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(draftBefore, vm.state.value.policyDraft)
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)
            // Discard never overwrites msg with the loser terminal: neither SUCCESS
            // (contains 已寫入) nor Mismatch REFRESHED nor Failed WRITE_FAILED.
            // Exact TIMEOUT equality is NOT asserted: an unrelated background probe
            // may legitimately reword msg (e.g. AUTO_DEGRADE_*), draft/phase/fence
            // are the pinned discard contract.
            assertFalse(vm.state.value.msg.contains("已寫入"))
            assertFalse(vm.state.value.msg == PolicyText(PolicyTextKind.REFRESHED).defaultText())
            assertFalse(vm.state.value.msg == PolicyText(PolicyTextKind.WRITE_FAILED).defaultText())
            assertTrue(vm.state.value.pendingPolicyWrite === pendingLoser)
            assertTrue(activeTxOf(vm) === winner)
            assertFalse(reconcilingHas(vm, "loser"))
            assertTrue(reconcilingHas(vm, "winner"))
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // T4: TOCTOU discard — version/ownership moving mid-resolve must discard.
    // Active stays loser (I1 passes) but a concurrent winner bump lands inside
    // await(); the would-be SUCCESS must discard like I1: RECONCILING + draft/msg
    // untouched, only loser key removed.
    @Test
    fun t4_toctou_bumpDuringAwait_discardsLoserAdopt() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)
            val pending = PendingPolicyWrite(
                packages = setOf("com.C"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val inner = kotlinx.coroutines.CompletableDeferred<Boolean>().apply { complete(true) }
            // Bumping await: snapshot versionAtStart first, bump during await, then
            // report ok=true so decide yields Success; TOCTOU check must discard.
            val bumping: kotlinx.coroutines.Deferred<Boolean> =
                object : kotlinx.coroutines.Deferred<Boolean> by inner {
                    override suspend fun await(): Boolean {
                        bumpVersionsForToctou(vm)
                        return inner.await()
                    }
                }
            val loser = DualViewModel.PolicyTx(
                coord.nextTxId(), "loser",
                bumping, setOf("com.C"), store, false, pending, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, loser, pending, setOf("com.A"), coord)
            runBlocking { vm.resolveObserverFallback(loser, LoadResult.Known(setOf("com.C")), false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)
            // Same discard contract as T3: never adopts the loser terminal.
            // (Exact TIMEOUT not asserted — background probe may reword msg.)
            assertFalse(vm.state.value.msg.contains("已寫入"))
            assertFalse(vm.state.value.msg == PolicyText(PolicyTextKind.REFRESHED).defaultText())
            assertFalse(vm.state.value.msg == PolicyText(PolicyTextKind.WRITE_FAILED).defaultText())
            assertTrue(vm.state.value.pendingPolicyWrite === pending)
            // TOCTOU discard keeps the (stale) activeTx but drops only its key.
            assertTrue(activeTxOf(vm) === loser)
            assertFalse(reconcilingHas(vm, "loser"))
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // T5: canJoinTx false (unarmed + incomplete) must abort, never join.
    // Unarmed abort publishes IDLE + Unknown + UNKNOWN_REFRESH (fail-closed).
    @Test
    fun t5_unarmed_abortIdlesUnknownRefresh() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pending = PendingPolicyWrite(
                packages = setOf("com.B"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val tx = DualViewModel.PolicyTx(
                coord.nextTxId(), "unarmed",
                deferred, setOf("com.B"), store, false, pending, false,
                java.util.concurrent.atomic.AtomicBoolean(false),
            )
            setReconciling(vm, tx, pending, setOf("com.A"), coord)
            // Even a matching Known must abort (never start an unvalidated LAZY).
            runBlocking { vm.resolveObserverFallback(tx, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertEquals(
                PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(),
                vm.state.value.msg,
            )
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            assertFalse(reconcilingHas(vm, "unarmed"))
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // T6: Unknown branches — completed false adopts FAILED, completed true retains.
    // Unknown+ok=false -> FAILED+Unknown (fail-closed IDLE, never Known);
    // Unknown+ok=true -> Unresolved Retain (RECONCILING + REFRESH_FAILED).
    @Test
    fun t6_unknown_falseFailed_trueRetain() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)
            // Unknown + completed false -> Adopt FAILED.
            val deferredF = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pendingF = PendingPolicyWrite(
                packages = setOf("com.B"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val txF = DualViewModel.PolicyTx(
                coord.nextTxId(), "unkF",
                deferredF, setOf("com.B"), store, false, pendingF, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, txF, pendingF, setOf("com.A"), coord)
            assertTrue(deferredF.complete(false))
            runBlocking { vm.resolveObserverFallback(txF, LoadResult.Unavailable, false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(
                PolicyText(PolicyTextKind.WRITE_FAILED).defaultText(),
                vm.state.value.msg,
            )
            assertNull(vm.state.value.pendingPolicyWrite)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            assertFalse(reconcilingHas(vm, "unkF"))
            // Failed keeps the old draft snapshot (never adopts Unknown as Known).
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)

            // Unknown + completed true -> Retain (Unresolved, never trust ack).
            val deferredT = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pendingT = PendingPolicyWrite(
                packages = setOf("com.B"),
                requestedAtMs = System.currentTimeMillis(),
            )
            val txT = DualViewModel.PolicyTx(
                coord.nextTxId(), "unkT",
                deferredT, setOf("com.B"), store, false, pendingT, false,
                java.util.concurrent.atomic.AtomicBoolean(true),
            )
            setReconciling(vm, txT, pendingT, setOf("com.A"), coord)
            assertTrue(deferredT.complete(true))
            runBlocking { vm.resolveObserverFallback(txT, LoadResult.Unavailable, false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertEquals(
                PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText(),
                vm.state.value.msg,
            )
            assertNotNull(vm.state.value.pendingPolicyWrite)
            assertTrue(activeTxOf(vm) === txT)
            assertTrue(coord.activeTx.get() === txT)
            assertTrue(reconcilingHas(vm, "unkT"))
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // PR #1: Inherited VM with coord.activeTx.set(tx), completed deferred, trusted read,
    // NO alternate reconciler running -> both coord.activeTx AND UI converge (IDLE +
    // coord.activeTx == null), and a subsequent new write C is NOT blocked.
    // Covers Success, Mismatch, and Failed.
    @Test
    fun pr1_inheritedCoordinatorActiveTx_convergesAndUnblocksWrite_successMismatchFailed() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) backend.set(emptySet()) else backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)

            // 1. Success branch -> both coord.activeTx and UI settle, new write C succeeds.
            val pendingS = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            val txS = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr1-succ",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), store, false, pendingS, false,
                AtomicBoolean(true),
            )
            setReconciling(vm, txS, pendingS, setOf("com.A"), coord)
            backend.set(setOf("com.B"))
            runBlocking { vm.resolveObserverFallback(txS, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vm.state.value.policyDraft)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            vm.setSourceCatalog(listOf(SourceAppEntry("com.C", "C", 0, false)))
            vm.toggleSelection("0:com.C")
            vm.requestPolicyWrite()
            val pC = vm.state.value.pendingPolicyWrite
            assertNotNull("write C request must not be blocked after observer Success", pC)
            vm.confirmPolicyWrite(pC!!)
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm.state.value.policyDraft == setOf("com.C")
            }
            assertEquals(setOf("com.C"), backend.get())

            // 2. Mismatch branch -> both coord.activeTx and UI settle, new write D succeeds.
            val pendingM = PendingPolicyWrite(setOf("com.Z"), System.currentTimeMillis())
            val txM = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr1-mismatch",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.Z"), store, false, pendingM, false,
                AtomicBoolean(true),
            )
            setReconciling(vm, txM, pendingM, setOf("com.C"), coord)
            backend.set(setOf("com.X"))
            runBlocking { vm.resolveObserverFallback(txM, LoadResult.Known(setOf("com.X")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.X"), vm.state.value.policyDraft)
            assertEquals(PolicyText(PolicyTextKind.REFRESHED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            vm.setSourceCatalog(listOf(SourceAppEntry("com.D", "D", 0, false)))
            vm.toggleSelection("0:com.D")
            vm.requestPolicyWrite()
            val pD = vm.state.value.pendingPolicyWrite
            assertNotNull("write D request must not be blocked after observer Mismatch", pD)
            vm.confirmPolicyWrite(pD!!)
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm.state.value.policyDraft == setOf("com.D")
            }
            assertEquals(setOf("com.D"), backend.get())

            // 3. Failed branch -> both coord.activeTx and UI settle, refresh + new write E succeeds.
            val pendingF = PendingPolicyWrite(setOf("com.FAIL"), System.currentTimeMillis())
            val txF = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr1-failed",
                kotlinx.coroutines.CompletableDeferred(false), setOf("com.FAIL"), store, false, pendingF, false,
                AtomicBoolean(true),
            )
            setReconciling(vm, txF, pendingF, setOf("com.D"), coord)
            runBlocking { vm.resolveObserverFallback(txF, LoadResult.Known(setOf("com.D")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            vm.refreshPolicyDraft()
            await(timeoutMs = 5_000L) { vm.state.value.policyDraftKnown && !vm.state.value.policyRefreshing }
            vm.setSourceCatalog(listOf(SourceAppEntry("com.E", "E", 0, false)))
            vm.toggleSelection("0:com.E")
            vm.requestPolicyWrite()
            val pE = vm.state.value.pendingPolicyWrite
            assertNotNull("write E request must not be blocked after observer Failed", pE)
            vm.confirmPolicyWrite(pE!!)
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm.state.value.policyDraft == setOf("com.E")
            }
            assertEquals(setOf("com.E"), backend.get())

            // 4. End-to-end inherited VM path: observeSettledFor times out -> runObserverFallback ->
            // resolveObserverFallback with coord.activeTx.set(tx) and NO alternate reconciler running.
            val pendingE2E = PendingPolicyWrite(setOf("com.F"), System.currentTimeMillis())
            val txE2E = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr1-e2e-obs",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.F"), store, false, pendingE2E, false,
                AtomicBoolean(true),
            )
            setReconciling(vm, txE2E, pendingE2E, setOf("com.E"), coord)
            backend.set(setOf("com.F"))
            vm.observeSettledFor(txE2E, timeoutMs = 40L)
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    coord.activeTx.get() == null &&
                    coord.pendingPublish.get() == null
            }
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.F"), vm.state.value.policyDraft)
            assertNull(activeTxOf(vm))
            assertFalse(reconcilingHas(vm, "pr1-e2e-obs"))
            vm.setSourceCatalog(listOf(SourceAppEntry("com.G", "G", 0, false)))
            vm.toggleSelection("0:com.G")
            vm.requestPolicyWrite()
            val pG = vm.state.value.pendingPolicyWrite
            assertNotNull("write G request must not be blocked after e2e observeSettledFor", pG)
            vm.confirmPolicyWrite(pG!!)
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm.state.value.policyDraft == setOf("com.G")
            }
            assertEquals(setOf("com.G"), backend.get())
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // PR #2: Stale authority TOCTOU barrier test covering BOTH launchLoserSync and observeSettledFor:
    // 1. Pre-completion read avoided when !tx.deferred.isCompleted (no stale A read becomes truth).
    // 2. Read old A paused mid-IO while save B completes on coordinator -> NO Known(A)/IDLE published.
    @Test
    fun pr2_launchLoserSync_and_observeSettledFor_staleAuthorityBarrier() {
        val coord = PolicyTxCoordinator()
        val readReleaseB = AtomicBoolean(false)
        val obsRelease = AtomicBoolean(false)
        try {
            val blockRead = AtomicBoolean(false)
            val readEntered = java.util.concurrent.CountDownLatch(1)
            val readRelease = AtomicBoolean(true)
            val readFinished = java.util.concurrent.CountDownLatch(1)
            val fallbackReadCount = java.util.concurrent.atomic.AtomicInteger(0)
            val returnValue = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))

            val store = HookPolicyStore(
                loadRaw = {
                    if (blockRead.get()) {
                        fallbackReadCount.incrementAndGet()
                        readEntered.countDown()
                        while (!readRelease.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                        readFinished.countDown()
                    }
                    returnValue.get()
                },
                saveRaw = { raw ->
                    if (raw != null) returnValue.set(raw)
                    true
                },
            )
            val vm = makeVm(store, coord, policyIoTimeoutMs = 80L)

            // Part A (launchLoserSync): incomplete deferred -> must NOT read or adopt A.
            returnValue.set(setOf("com.A"))
            blockRead.set(true)
            val incompleteDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pendingA = PendingPolicyWrite(setOf("com.targetA"), System.currentTimeMillis())
            val txInc = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr2-loser-inc",
                incompleteDeferred, setOf("com.targetA"), store, false, pendingA, false,
                AtomicBoolean(true),
            )
            setReconciling(vm, txInc, pendingA, setOf("com.seed"), coord)
            vm.launchLoserSync(txInc)
            await { vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText() }
            assertEquals(0, fallbackReadCount.get())
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            incompleteDeferred.cancel()
            coord.tryCompleteClaim(txInc)
            removeReconcilingKey(vm, "pr2-loser-inc")
            setActiveTx(vm, null)

            // Part B (launchLoserSync): completed deferred, read old A paused while save B completes.
            // Use policyIoTimeoutMs = 1_500L so boundedReconcileRead actually completes with Known(com.A)
            // after release rather than timing out to Unavailable.
            val readEnteredB = java.util.concurrent.CountDownLatch(1)
            val readFinishedB = java.util.concurrent.CountDownLatch(1)
            val readReturnedAB = AtomicBoolean(false)
            val storeLoserB = HookPolicyStore(
                loadRaw = {
                    if (readReleaseB.get() && readEnteredB.count == 0L) {
                        setOf("com.A")
                    } else {
                        readEnteredB.countDown()
                        while (!readReleaseB.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                        readReturnedAB.set(true)
                        readFinishedB.countDown()
                        setOf("com.A")
                    }
                },
                saveRaw = { true },
            )
            val vmMid = makeVm(store, coord, policyIoTimeoutMs = 1_500L)
            val pendingA2 = PendingPolicyWrite(setOf("com.targetA2"), System.currentTimeMillis())
            val txLoserB = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr2-loser-mid",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.targetA2"), storeLoserB,
                false, pendingA2, false, AtomicBoolean(true),
            )
            setReconciling(vmMid, txLoserB, pendingA2, setOf("com.seed"), coord)
            vmMid.launchLoserSync(txLoserB)
            assertTrue(readEnteredB.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Start & complete save B while fallback read of A is in flight.
            val txB = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr2-saveB",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), store, false, null, false,
                AtomicBoolean(true),
            )
            coord.activeTx.set(txB)
            runBlocking {
                assertTrue(
                    coord.completeWithRevision(
                        txB,
                        PolicySettled(txB.txId, txB.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false),
                    ),
                )
                val realB = PolicySettled(txB.txId, txB.key, setOf("com.B"), true, MsgKind.INFO, "okB", false)
                val stampedB = coord.stageRealPayload(realB)
                assertNotNull(stampedB)
                coord.emitStaged(stampedB!!)
            }

            readReleaseB.set(true)
            assertTrue(readFinishedB.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("storeLoserB must return Known(com.A) without timing out", readReturnedAB.get())
            await(timeoutMs = 5_000L) {
                activeTxOf(vmMid) == null && vmMid.state.value.policyWritePhase == PolicyWritePhase.IDLE
            }
            assertFalse(
                "launchLoserSync must never publish stale Known(A)/IDLE",
                vmMid.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vmMid.state.value.policyDraftKnown &&
                    vmMid.state.value.policyDraft == setOf("com.A"),
            )
            assertTrue(
                "launchLoserSync must converge to UNKNOWN_REFRESH or com.B, was msg=${vmMid.state.value.msg} draft=${vmMid.state.value.policyDraft}",
                vmMid.state.value.msg == PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText() ||
                    vmMid.state.value.policyDraft == setOf("com.B"),
            )

            // Part C (observeSettledFor): incomplete deferred -> must NOT read or adopt A.
            val obsReads = java.util.concurrent.atomic.AtomicInteger(0)
            val storeObsInc = HookPolicyStore(
                loadRaw = {
                    obsReads.incrementAndGet()
                    setOf("com.A")
                },
                saveRaw = { true },
            )
            val obsIncDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val txObsInc = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr2-obs-inc",
                obsIncDeferred, setOf("com.targetObs"), storeObsInc,
                false, pendingA, false, AtomicBoolean(true),
            )
            setReconciling(vm, txObsInc, pendingA, setOf("com.seed"), coord)
            vm.observeSettledFor(txObsInc, timeoutMs = 60L)
            await(timeoutMs = 5_000L) { vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText() }
            assertEquals(0, obsReads.get())
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            obsIncDeferred.cancel()
            coord.tryCompleteClaim(txObsInc)
            removeReconcilingKey(vm, "pr2-obs-inc")
            setActiveTx(vm, null)

            // Part D (observeSettledFor): completed deferred, read old A paused while save B2 completes.
            val obsEntered = java.util.concurrent.CountDownLatch(1)
            val obsFinished = java.util.concurrent.CountDownLatch(1)
            val obsReturnedA = AtomicBoolean(false)
            val storeObsMid = HookPolicyStore(
                loadRaw = {
                    obsEntered.countDown()
                    while (!obsRelease.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                    obsReturnedA.set(true)
                    obsFinished.countDown()
                    setOf("com.A")
                },
                saveRaw = { true },
            )
            val vmMid2 = makeVm(store, coord, policyIoTimeoutMs = 1_500L)
            val txObsMid = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr2-obs-mid",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.targetObs2"), storeObsMid,
                false, pendingA2, false, AtomicBoolean(true),
            )
            setReconciling(vmMid2, txObsMid, pendingA2, setOf("com.seed"), coord)
            vmMid2.observeSettledFor(txObsMid, timeoutMs = 40L)
            assertTrue(obsEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val txB2 = DualViewModel.PolicyTx(
                coord.nextTxId(), "pr2-saveB2",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B2"), store, false, null, false,
                AtomicBoolean(true),
            )
            coord.activeTx.set(txB2)
            runBlocking {
                assertTrue(
                    coord.completeWithRevision(
                        txB2,
                        PolicySettled(txB2.txId, txB2.key, setOf("com.B2"), false, MsgKind.CHANNEL, "", false),
                    ),
                )
                val realB2 = PolicySettled(txB2.txId, txB2.key, setOf("com.B2"), true, MsgKind.INFO, "okB2", false)
                val stampedB2 = coord.stageRealPayload(realB2)
                assertNotNull(stampedB2)
                coord.emitStaged(stampedB2!!)
            }

            obsRelease.set(true)
            assertTrue(obsFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("storeObsMid must return Known(com.A) without timing out", obsReturnedA.get())
            await(timeoutMs = 5_000L) {
                activeTxOf(vmMid2) == null && vmMid2.state.value.policyWritePhase == PolicyWritePhase.IDLE
            }
            assertFalse(
                "observeSettledFor must never publish stale Known(A)/IDLE",
                vmMid2.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vmMid2.state.value.policyDraftKnown &&
                    vmMid2.state.value.policyDraft == setOf("com.A"),
            )
            assertTrue(
                "observeSettledFor must converge to UNKNOWN_REFRESH or com.B2, was msg=${vmMid2.state.value.msg} draft=${vmMid2.state.value.policyDraft}",
                vmMid2.state.value.msg == PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText() ||
                    vmMid2.state.value.policyDraft == setOf("com.B2"),
            )
        } finally {
            readReleaseB.set(true)
            obsRelease.set(true)
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Covers isClear = true, isRepair = true, and LoadResult.Corrupt observer fallback paths.
    @Test
    fun observerFallback_clear_repair_and_corruptPaths() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, coord)

            // 1. isClear = true: Success (emptySet) -> IDLE + CLEAR_SUCCESS
            val txClearOk = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-clear-ok",
                kotlinx.coroutines.CompletableDeferred(true), emptySet(), store,
                isClear = true, pending = null, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txClearOk, null, setOf("com.A"), coord)
            runBlocking { vm.resolveObserverFallback(txClearOk, LoadResult.Known(emptySet()), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertTrue(vm.state.value.policyDraft.isEmpty())
            assertEquals(PolicyText(PolicyTextKind.CLEAR_SUCCESS).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 2. isClear = true: Mismatch (non-empty) -> IDLE + REFRESHED
            val txClearMis = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-clear-mis",
                kotlinx.coroutines.CompletableDeferred(true), emptySet(), store,
                isClear = true, pending = null, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txClearMis, null, setOf("com.A"), coord)
            runBlocking { vm.resolveObserverFallback(txClearMis, LoadResult.Known(setOf("com.X")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.X"), vm.state.value.policyDraft)
            assertEquals(PolicyText(PolicyTextKind.REFRESHED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 3. isClear = true: Failed -> IDLE + CLEAR_FAILED + Unknown
            val txClearFail = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-clear-fail",
                kotlinx.coroutines.CompletableDeferred(false), emptySet(), store,
                isClear = true, pending = null, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txClearFail, null, setOf("com.X"), coord)
            runBlocking { vm.resolveObserverFallback(txClearFail, LoadResult.Known(setOf("com.X")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(PolicyText(PolicyTextKind.CLEAR_FAILED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 4. isRepair = true: Success -> IDLE + CORRUPT_REPAIRED + corrupt=false
            val txRepOk = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-rep-ok",
                kotlinx.coroutines.CompletableDeferred(true), emptySet(), store,
                isClear = true, pending = null, isRepair = true, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txRepOk, null, setOf("com.X"), coord, corrupt = true)
            runBlocking { vm.resolveObserverFallback(txRepOk, LoadResult.Known(emptySet()), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertFalse(vm.state.value.policyDraftCorrupt)
            assertEquals(PolicyText(PolicyTextKind.CORRUPT_REPAIRED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 5. isRepair = true + LoadResult.Corrupt + deferred false -> IDLE + CORRUPT_REPAIR_FAILED + corrupt=true
            val txRepFail = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-rep-fail",
                kotlinx.coroutines.CompletableDeferred(false), emptySet(), store,
                isClear = true, pending = null, isRepair = true, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txRepFail, null, emptySet(), coord, corrupt = true)
            runBlocking { vm.resolveObserverFallback(txRepFail, LoadResult.Corrupt, false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(vm.state.value.policyDraftCorrupt)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 6. Normal (non-repair) Tx + LoadResult.Corrupt + deferred true -> Unresolved (RECONCILING, corrupt=true)
            val pendingN = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            val txCorruptUnres = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-corrupt-unres",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), store,
                isClear = false, pending = pendingN, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txCorruptUnres, pendingN, emptySet(), coord)
            runBlocking { vm.resolveObserverFallback(txCorruptUnres, LoadResult.Corrupt, false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(vm.state.value.policyDraftCorrupt)
            assertEquals(PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText(), vm.state.value.msg)
            assertTrue(activeTxOf(vm) === txCorruptUnres)
            assertTrue(coord.activeTx.get() === txCorruptUnres)
            removeReconcilingKey(vm, "obs-corrupt-unres")

            // 7. Normal Tx + LoadResult.Corrupt + deferred false -> IDLE + WRITE_FAILED
            val txCorruptFail = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-corrupt-fail",
                kotlinx.coroutines.CompletableDeferred(false), setOf("com.B"), store,
                isClear = false, pending = pendingN, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txCorruptFail, pendingN, emptySet(), coord)
            runBlocking { vm.resolveObserverFallback(txCorruptFail, LoadResult.Corrupt, false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(PolicyText(PolicyTextKind.WRITE_FAILED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 8. isRepair = true + Mismatch (non-empty Known) -> IDLE + REFRESHED + corrupt=false + pending cleared
            val txRepMis = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-rep-mis",
                kotlinx.coroutines.CompletableDeferred(true), emptySet(), store,
                isClear = true, pending = pendingN, isRepair = true, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txRepMis, pendingN, emptySet(), coord, corrupt = true)
            runBlocking { vm.resolveObserverFallback(txRepMis, LoadResult.Known(setOf("com.Y")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.Y"), vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyDraftCorrupt)
            assertEquals(PolicyText(PolicyTextKind.REFRESHED).defaultText(), vm.state.value.msg)
            assertEquals(MsgKind.INFO, vm.state.value.msgKind)
            assertNull(vm.state.value.pendingPolicyWrite)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 9. isRepair = true + Known(empty) + deferred=false -> ground truth repaired -> IDLE + CORRUPT_REPAIRED
            val txRepEmptyFalse = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-rep-empty-false",
                kotlinx.coroutines.CompletableDeferred(false), emptySet(), store,
                isClear = true, pending = null, isRepair = true, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txRepEmptyFalse, null, setOf("com.Y"), coord, corrupt = true)
            runBlocking { vm.resolveObserverFallback(txRepEmptyFalse, LoadResult.Known(emptySet()), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
            assertTrue(vm.state.value.policyDraft.isEmpty())
            assertFalse(vm.state.value.policyDraftCorrupt)
            assertEquals(PolicyText(PolicyTextKind.CORRUPT_REPAIRED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 10. isRepair = true + Corrupt + deferred=true -> Unresolved (RECONCILING, corrupt=true, REFRESH_FAILED)
            val txRepUnres = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-rep-unres",
                kotlinx.coroutines.CompletableDeferred(true), emptySet(), store,
                isClear = true, pending = null, isRepair = true, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txRepUnres, null, emptySet(), coord, corrupt = true)
            runBlocking { vm.resolveObserverFallback(txRepUnres, LoadResult.Corrupt, false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(vm.state.value.policyDraftCorrupt)
            assertEquals(PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText(), vm.state.value.msg)
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertTrue(activeTxOf(vm) === txRepUnres)
            assertTrue(coord.activeTx.get() === txRepUnres)
            // Resolve txRepUnres before next sub-case.
            runBlocking { vm.resolveObserverFallback(txRepUnres, LoadResult.Known(emptySet()), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 11. isClear = true + Unavailable + deferred=false -> IDLE + CLEAR_FAILED + Unknown
            val txClearUnavailFail = DualViewModel.PolicyTx(
                coord.nextTxId(), "obs-clear-unavail-fail",
                kotlinx.coroutines.CompletableDeferred(false), emptySet(), store,
                isClear = true, pending = null, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm, txClearUnavailFail, null, setOf("com.Z"), coord)
            runBlocking { vm.resolveObserverFallback(txClearUnavailFail, LoadResult.Unavailable, false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertFalse(vm.state.value.policyDraftCorrupt)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(PolicyText(PolicyTextKind.CLEAR_FAILED).defaultText(), vm.state.value.msg)
            assertNull(activeTxOf(vm))
            assertNull(coord.activeTx.get())
            await { coord.pendingPublish.get() == null }

            // 12. Subsequent operations unblocked after clear/repair/corrupt fallback paths.
            backend.set(setOf("com.A"))
            vm.refreshPolicyDraft()
            await(timeoutMs = 5_000L) { vm.state.value.policyDraftKnown && !vm.state.value.policyRefreshing }
            vm.setSourceCatalog(listOf(SourceAppEntry("com.NEXT", "NEXT", 0, false)))
            vm.toggleSelection("0:com.NEXT")
            vm.requestPolicyWrite()
            val pendingNext = vm.state.value.pendingPolicyWrite
            assertNotNull("subsequent write must not be blocked", pendingNext)
            vm.confirmPolicyWrite(pendingNext!!)
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm.state.value.policyDraft == setOf("com.NEXT")
            }
            assertEquals(setOf("com.NEXT"), backend.get())
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Critical #1 regression: runObserverFallback must NEVER pass LoadResult.Unavailable with timedOut=false
    // to resolveObserverFallback when !tx.deferred.isCompleted, afterClaim==null, or revMovedFb without terminal.
    @Test
    fun runObserverFallback_pendingPublishForSelf_or_revMoved_retainsUnresolved_neverPrematureFailed() {
        val coord = PolicyTxCoordinator()
        try {
            // Sub-case 1: revMovedFb + coord.pendingPublish holds self + awaitSettled times out (afterClaim == null)
            // even though tx.deferred already completed false -> must retain RECONCILING + REFRESH_FAILED, never FAILED.
            val enableClaimInRead = AtomicBoolean(false)
            val claimedInRead = AtomicBoolean(false)
            lateinit var tx1: DualViewModel.PolicyTx
            val store1 = HookPolicyStore(
                loadRaw = {
                    if (enableClaimInRead.get() && claimedInRead.compareAndSet(false, true)) {
                        runBlocking {
                            coord.completeWithRevision(
                                tx1,
                                PolicySettled(tx1.txId, tx1.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false),
                            )
                        }
                    }
                    setOf("com.seed")
                },
                saveRaw = { true },
            )
            val vm1 = makeVm(store1, coord, policyIoTimeoutMs = 120L)
            val pending1 = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            tx1 = DualViewModel.PolicyTx(
                coord.nextTxId(), "crit1-self-pend",
                kotlinx.coroutines.CompletableDeferred(false), setOf("com.B"), store1,
                isClear = false, pending = pending1, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm1, tx1, pending1, setOf("com.seed"), coord)
            enableClaimInRead.set(true)
            vm1.observeSettledFor(tx1, timeoutMs = 30L)
            await(timeoutMs = 5_000L) {
                vm1.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText() &&
                    coord.pendingPublish.get()?.txId == tx1.txId
            }
            assertEquals(PolicyWritePhase.RECONCILING, vm1.state.value.policyWritePhase)
            assertFalse(vm1.state.value.policyDraftKnown)
            assertEquals(MsgKind.CHANNEL, vm1.state.value.msgKind)
            assertTrue(activeTxOf(vm1) === tx1)
            assertTrue(vm1.state.value.pendingPolicyWrite === pending1)
            // Cleanup placeholder & tx1 before sub-case 2.
            runBlocking {
                val real1 = PolicySettled(tx1.txId, tx1.key, setOf("com.seed"), false, MsgKind.FAILED, "fail1", false)
                val s1 = coord.stageRealPayload(real1)
                if (s1 != null) coord.emitStaged(s1)
            }
            vm1.observeSettledFor(tx1, timeoutMs = 200L)
            await(timeoutMs = 5_000L) { activeTxOf(vm1) == null && coord.pendingPublish.get() == null }

            // Sub-case 2: revMovedFb without matching terminal or matching pendingPublish while tx2.deferred=false ->
            // must retain RECONCILING + REFRESH_FAILED, never premature WRITE_FAILED.
            val enableBumpInRead = AtomicBoolean(false)
            val bumpedInRead = AtomicBoolean(false)
            val store2 = HookPolicyStore(
                loadRaw = {
                    if (enableBumpInRead.get() && bumpedInRead.compareAndSet(false, true)) {
                        coord.bumpRevisionForTests()
                    }
                    setOf("com.seed")
                },
                saveRaw = { true },
            )
            val vm2 = makeVm(store2, coord, policyIoTimeoutMs = 120L)
            val pending2 = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
            val tx2 = DualViewModel.PolicyTx(
                coord.nextTxId(), "crit1-rev-moved",
                kotlinx.coroutines.CompletableDeferred(false), setOf("com.C"), store2,
                isClear = false, pending = pending2, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm2, tx2, pending2, setOf("com.seed"), coord)
            enableBumpInRead.set(true)
            vm2.observeSettledFor(tx2, timeoutMs = 30L)
            await(timeoutMs = 5_000L) {
                vm2.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText() &&
                    bumpedInRead.get()
            }
            Thread.sleep(150)
            assertEquals(PolicyWritePhase.RECONCILING, vm2.state.value.policyWritePhase)
            assertFalse(vm2.state.value.policyDraftKnown)
            assertEquals(MsgKind.CHANNEL, vm2.state.value.msgKind)
            assertTrue(activeTxOf(vm2) === tx2)
            assertTrue(vm2.state.value.pendingPolicyWrite === pending2)

            // Sub-case 3: !tx.deferred.isCompleted -> retains RECONCILING + REFRESH_FAILED.
            val deferred3 = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val pending3 = PendingPolicyWrite(setOf("com.D"), System.currentTimeMillis())
            val tx3 = DualViewModel.PolicyTx(
                coord.nextTxId(), "crit1-incomplete",
                deferred3, setOf("com.D"), store2,
                isClear = false, pending = pending3, isRepair = true, executionArmed = AtomicBoolean(true),
            )
            setReconciling(vm2, tx3, pending3, setOf("com.seed"), coord, corrupt = true)
            vm2.observeSettledFor(tx3, timeoutMs = 30L)
            await(timeoutMs = 5_000L) {
                vm2.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
            }
            assertEquals(PolicyWritePhase.RECONCILING, vm2.state.value.policyWritePhase)
            assertFalse(vm2.state.value.policyDraftKnown)
            assertTrue(vm2.state.value.policyDraftCorrupt)
            assertTrue(activeTxOf(vm2) === tx3)
            deferred3.cancel()
            coord.tryCompleteClaim(tx3)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Critical #2 regression: Two-VM liveness wedge when VM1 holds activePolicyTx === tx1 locally
    // while coord.activeTx has moved to foreign tx2 -> VM1 must clear obsolete local tx1 (IDLE + UNKNOWN_REFRESH)
    // without touching coord.activeTx(tx2), and later converge cleanly when tx2 finishes.
    @Test
    fun twoVm_localActiveMovedToForeignTx2_doesNotWedge() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, coord, policyIoTimeoutMs = 150L)
            val pending1 = PendingPolicyWrite(setOf("com.A"), System.currentTimeMillis())
            val tx1 = DualViewModel.PolicyTx(
                coord.nextTxId(), "crit2-tx1",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.A"), store,
                isClear = false, pending = pending1, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            val tx2 = DualViewModel.PolicyTx(
                coord.nextTxId(), "crit2-tx2",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), store,
                isClear = false, pending = null, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            // VM1 holds tx1 locally while coordinator moved to foreign tx2.
            setReconciling(vm1, tx1, pending1, setOf("com.seed"), coord)
            coord.activeTx.set(tx2)

            vm1.observeSettledFor(tx1, timeoutMs = 40L)
            await(timeoutMs = 5_000L) {
                vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    activeTxOf(vm1) == null
            }
            assertFalse(reconcilingHas(vm1, "crit2-tx1"))
            assertFalse(vm1.state.value.policyDraftKnown)
            assertNull(vm1.state.value.pendingPolicyWrite)
            assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm1.state.value.msg)
            assertEquals(MsgKind.CHANNEL, vm1.state.value.msgKind)
            assertTrue(revisionStaleOf(vm1))
            // Foreign tx2 in coordinator must remain untouched.
            assertTrue(coord.activeTx.get() === tx2)

            // Now tx2 settles on coordinator -> unblocked VM1 converges cleanly to Known(com.B).
            backend.set(setOf("com.B"))
            runBlocking {
                assertTrue(
                    coord.completeWithRevision(
                        tx2,
                        PolicySettled(tx2.txId, tx2.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false),
                    ),
                )
                val real2 = PolicySettled(tx2.txId, tx2.key, setOf("com.B"), true, MsgKind.INFO, "okB", false)
                val s2 = coord.stageRealPayload(real2)
                assertNotNull(s2)
                coord.emitStaged(s2!!)
            }
            await(timeoutMs = 5_000L) {
                vm1.state.value.policyDraftKnown && vm1.state.value.policyDraft == setOf("com.B")
            }
            assertFalse(revisionStaleOf(vm1))

            // Verify VM1 can run a fresh write without wedging.
            vm1.setSourceCatalog(listOf(SourceAppEntry("com.C", "C", 0, false)))
            vm1.toggleSelection("0:com.C")
            vm1.requestPolicyWrite()
            val pC = vm1.state.value.pendingPolicyWrite
            assertNotNull("VM1 must not be wedged after foreign tx2 settled", pC)
            vm1.confirmPolicyWrite(pC!!)
            await(timeoutMs = 10_000L) {
                vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm1.state.value.policyDraft == setOf("com.C")
            }
            assertEquals(setOf("com.C"), backend.get())

            // Also verify SAVING -> obsolete cleanup in confirmRepairCorruptDraft when coord.activeTx moved to foreign tx.
            val coordRepair = PolicyTxCoordinator()
            val repairStarted = java.util.concurrent.CountDownLatch(1)
            val repairRelease = AtomicBoolean(false)
            val corruptFlag = AtomicBoolean(true)
            val repairStore = HookPolicyStore(
                loadRaw = {
                    if (corruptFlag.get()) throw ClassCastException("corrupt")
                    emptySet()
                },
                saveRaw = { raw ->
                    if (raw == null) {
                        repairStarted.countDown()
                        while (!repairRelease.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                        corruptFlag.set(false)
                    }
                    true
                },
                repairProbe = { LoadResult.Corrupt },
            )
            val vmRepair = makeVm(repairStore, coordRepair, policyIoTimeoutMs = 5_000L, awaitKnown = false)
            try {
                await(timeoutMs = 5_000L) { !vmRepair.state.value.loading && vmRepair.state.value.policyDraftCorrupt }
                vmRepair.requestRepairCorruptDraft()
                vmRepair.confirmRepairCorruptDraft()
                assertTrue(repairStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                await(timeoutMs = 5_000L) { vmRepair.state.value.policyWritePhase == PolicyWritePhase.SAVING }
                val foreignRepairTx = DualViewModel.PolicyTx(
                    coordRepair.nextTxId(), "crit2-foreign-repair",
                    kotlinx.coroutines.CompletableDeferred(), setOf("com.F"), store,
                    isClear = false, pending = null, isRepair = false, executionArmed = AtomicBoolean(true),
                )
                coordRepair.activeTx.set(foreignRepairTx)
                repairRelease.set(true)
                await(timeoutMs = 5_000L) {
                    vmRepair.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                        !vmRepair.state.value.loading &&
                        activeTxOf(vmRepair) == null
                }
                assertFalse(vmRepair.state.value.policyDraftKnown)
                assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vmRepair.state.value.msg)
                assertTrue(coordRepair.activeTx.get() === foreignRepairTx)
            } finally {
                repairRelease.set(true)
                try { coordRepair.close() } catch (_: Exception) { }
            }
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Critical #3 regression: When coord.latest.value already holds a newer foreign terminal (txB, rev=2),
    // falling back to txA's replayCache entry (rev=1) must NEVER apply stale Known(A) or regress lastRevisionSeenRev (2 -> 1).
    @Test
    fun runObserverFallback_supersededReplayCacheBehindNewerLatest_neverRegressesRevOrAppliesStaleKnown() {
        val coord = PolicyTxCoordinator()
        val releaseRead = AtomicBoolean(false)
        try {
            val enteredRead = java.util.concurrent.CountDownLatch(1)
            val blockRead = AtomicBoolean(false)
            val store = HookPolicyStore(
                loadRaw = {
                    if (blockRead.get()) {
                        enteredRead.countDown()
                        while (!releaseRead.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                        setOf("com.A")
                    } else {
                        setOf("com.seed")
                    }
                },
                saveRaw = { true },
            )
            val vm = makeVm(store, coord, policyIoTimeoutMs = 1_500L)
            val pendingA = PendingPolicyWrite(setOf("com.A"), System.currentTimeMillis())
            val txA = DualViewModel.PolicyTx(
                coord.nextTxId(), "crit3-txA",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.A"), store,
                isClear = false, pending = pendingA, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            val txB = DualViewModel.PolicyTx(
                coord.nextTxId(), "crit3-txB",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), store,
                isClear = false, pending = null, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            // Start runObserverFallback for txA while coord has no terminal yet.
            setReconciling(vm, txA, pendingA, setOf("com.seed"), coord)
            coord.activeTx.set(null)
            blockRead.set(true)
            vm.observeSettledFor(txA, timeoutMs = 30L)
            assertTrue(enteredRead.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Emit txA (rev=1, Known(A)) into replayCache + _latest, then supersede it with txB (rev=2, Known(B)).
            val settledA = PolicySettled(
                txA.txId, txA.key, setOf("com.A"), true, MsgKind.INFO, "okA", false,
            )
            val settledB = PolicySettled(
                txB.txId, txB.key, setOf("com.B"), true, MsgKind.INFO, "okB", false,
            )
            assertTrue(coord.tryEmitSettled(settledA))
            assertTrue(coord.tryEmitSettled(settledB))
            assertEquals(2L, coord.latest.value?.rev)
            assertTrue(coord.settled.replayCache.any { it.txId == txA.txId && it.key == txA.key && it.rev == 1L })
            // Wait until VM's _latest collector records rev=2 while local activeTx===txA.
            await(timeoutMs = 5_000L) { lastRevisionSeenRevOf(vm) == 2L }

            // Release read (returning stale com.A): runObserverFallback finds txA in replayCache behind txB in _latest.
            releaseRead.set(true)
            await(timeoutMs = 5_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    activeTxOf(vm) == null
            }
            assertEquals(2L, lastRevisionSeenRevOf(vm))
            assertFalse(
                "superseded replayCache entry must never publish Known(A)",
                vm.state.value.policyDraftKnown && vm.state.value.policyDraft == setOf("com.A"),
            )
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
            assertTrue(revisionStaleOf(vm))

            // Direct applyAuthoritativeSettled checks (both rev=1L and rev=-1L behind latest rev=2L):
            // must never regress lastRevisionSeenRev or apply stale Known(A).
            val applyMethod = DualViewModel::class.java.getDeclaredMethod(
                "applyAuthoritativeSettled",
                DualViewModel.PolicyTx::class.java,
                PolicySettled::class.java,
            ).apply { isAccessible = true }
            for (staleRev in listOf(1L, -1L)) {
                setReconciling(vm, txA, pendingA, setOf("com.seed"), coord)
                coord.activeTx.set(null)
                applyMethod.invoke(vm, txA, settledA.copy(rev = staleRev))
                assertEquals(2L, lastRevisionSeenRevOf(vm))
                assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
                assertFalse(vm.state.value.policyDraftKnown)
                assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
                assertNull(activeTxOf(vm))
                assertTrue(revisionStaleOf(vm))
            }
        } finally {
            releaseRead.set(true)
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Round 2 Required #1 regression:
    // Part A: tombstoned tx (coord.isTombstoned == true, coord.activeTx == null, pendingPublish == null, latest == null)
    // fails closed to IDLE + !policyDraftKnown + policyRevisionStale + UNKNOWN_REFRESH (never adopts Known).
    // Part B: pendingPublish already cleared into coord.latest holding tx itself (winnerForTx and coordNow initially null,
    // versionAtStart already bumped) converges RECONCILING -> IDLE and clears activePolicyTx without wedging.
    @Test
    fun observerFallback_pendClearedIntoOwnLatestOrTombstoned_convergesWithoutWedging() {
        // Part A1: resolveObserverFallback directly with tombstoned tx.
        val coordA = PolicyTxCoordinator()
        try {
            val backendA = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
            val storeA = HookPolicyStore(
                loadRaw = { backendA.get() },
                saveRaw = { raw -> if (raw != null) backendA.set(raw); true },
            )
            val vmA = makeVm(storeA, coordA, policyIoTimeoutMs = 120L)
            val pendingA = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            val txTomb1 = DualViewModel.PolicyTx(
                coordA.nextTxId(), "r2-tomb-1",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), storeA,
                isClear = false, pending = pendingA, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            coordA.activeTx.set(txTomb1)
            runBlocking {
                assertTrue(
                    coordA.completeWithRevision(
                        txTomb1,
                        PolicySettled(txTomb1.txId, txTomb1.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false),
                    ),
                )
                assertTrue(coordA.discardOverrunPlaceholder(txTomb1.txId, txTomb1.key))
            }
            assertTrue(coordA.isTombstoned(txTomb1.txId, txTomb1.key))
            assertNull(coordA.activeTx.get())
            assertNull(coordA.pendingPublish.get())
            assertNull(coordA.latest.value)

            setReconciling(vmA, txTomb1, pendingA, setOf("com.A"), coord = null)
            backendA.set(setOf("com.B"))
            runBlocking { vmA.resolveObserverFallback(txTomb1, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.IDLE, vmA.state.value.policyWritePhase)
            assertFalse(vmA.state.value.policyDraftKnown)
            assertTrue(revisionStaleOf(vmA))
            assertEquals(MsgKind.CHANNEL, vmA.state.value.msgKind)
            assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vmA.state.value.msg)
            assertEquals(setOf("com.A"), vmA.state.value.policyDraft)
            assertNull(activeTxOf(vmA))
            assertFalse(reconcilingHas(vmA, "r2-tomb-1"))

            // Part A2: runObserverFallback via observeSettledFor with tombstoned tx.
            val coordA2 = PolicyTxCoordinator()
            try {
                val vmA2 = makeVm(storeA, coordA2, policyIoTimeoutMs = 120L)
                val txTomb2 = DualViewModel.PolicyTx(
                    coordA2.nextTxId(), "r2-tomb-2",
                    kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), storeA,
                    isClear = false, pending = pendingA, isRepair = false, executionArmed = AtomicBoolean(true),
                )
                coordA2.activeTx.set(txTomb2)
                runBlocking {
                    assertTrue(
                        coordA2.completeWithRevision(
                            txTomb2,
                            PolicySettled(txTomb2.txId, txTomb2.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false),
                        ),
                    )
                    assertTrue(coordA2.discardOverrunPlaceholder(txTomb2.txId, txTomb2.key))
                }
                assertTrue(coordA2.isTombstoned(txTomb2.txId, txTomb2.key))
                assertNull(coordA2.activeTx.get())
                assertNull(coordA2.pendingPublish.get())
                assertNull(coordA2.latest.value)

                setReconciling(vmA2, txTomb2, pendingA, setOf("com.A"), coord = null)
                vmA2.observeSettledFor(txTomb2, timeoutMs = 30L)
                await(timeoutMs = 5_000L) {
                    vmA2.state.value.policyWritePhase == PolicyWritePhase.IDLE && activeTxOf(vmA2) == null
                }
                assertFalse(vmA2.state.value.policyDraftKnown)
                assertTrue(revisionStaleOf(vmA2))
                assertEquals(MsgKind.CHANNEL, vmA2.state.value.msgKind)
                assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vmA2.state.value.msg)
                assertEquals(setOf("com.A"), vmA2.state.value.policyDraft)
                assertFalse(reconcilingHas(vmA2, "r2-tomb-2"))
            } finally {
                try { coordA2.close() } catch (_: Exception) { }
            }
        } finally {
            try { coordA.close() } catch (_: Exception) { }
        }

        // Part B1: runObserverFallback when pendingPublish clears into coord.latest holding tx itself
        // between latestAnyBefore/curCoordAfter and latestAny (with revMovedFb == true).
        val coordB1 = PolicyTxCoordinator()
        try {
            val backendB1 = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
            val storeB1 = HookPolicyStore(
                loadRaw = { backendB1.get() },
                saveRaw = { raw -> if (raw != null) backendB1.set(raw); true },
            )
            val vmB1 = makeVm(storeB1, coordB1, policyIoTimeoutMs = 200L)
            val pendingB1 = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            val txB1 = DualViewModel.PolicyTx(
                coordB1.nextTxId(), "r2-own-latest-run",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), storeB1,
                isClear = false, pending = pendingB1, isRepair = false, executionArmed = AtomicBoolean(true),
            )
            coordB1.activeTx.set(txB1)
            runBlocking {
                assertTrue(
                    coordB1.completeWithRevision(
                        txB1,
                        PolicySettled(txB1.txId, txB1.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false),
                    ),
                )
            }
            setReconciling(vmB1, txB1, pendingB1, setOf("com.A"), coord = null)
            backendB1.set(setOf("com.B"))
            val sampleCalls = java.util.concurrent.atomic.AtomicInteger(0)
            vmB1.revisionSamplerForTests = {
                val n = sampleCalls.incrementAndGet()
                if (n == 2) {
                    runBlocking {
                        val realB1 = PolicySettled(
                            txB1.txId, txB1.key, setOf("com.B"), true, MsgKind.INFO, "okB1", false,
                        )
                        val stamped = coordB1.stageRealPayload(realB1)
                        assertNotNull(stamped)
                        coordB1.emitStaged(stamped!!)
                    }
                    bumpVersionsForToctou(vmB1)
                    coordB1.bumpRevisionForTests()
                }
                coordB1.currentRevision()
            }
            runBlocking { vmB1.runObserverFallback(txB1, coordB1) }
            vmB1.revisionSamplerForTests = null
            assertEquals(PolicyWritePhase.IDLE, vmB1.state.value.policyWritePhase)
            assertTrue(vmB1.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vmB1.state.value.policyDraft)
            assertNull(activeTxOf(vmB1))
            assertNull(coordB1.activeTx.get())
            assertFalse(reconcilingHas(vmB1, "r2-own-latest-run"))
        } finally {
            try { coordB1.close() } catch (_: Exception) { }
        }

        // Part B2: resolveObserverFallback when winnerForTx and coordNow were initially null,
        // pendingPublish clears into coord.latest holding tx itself (with versionAtStart already bumped)
        // -> must always converge RECONCILING -> IDLE and clear activePolicyTx without wedging.
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            for (iter in 0 until 15) {
                val coordR = PolicyTxCoordinator()
                val backendR = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
                val storeR = HookPolicyStore(
                    loadRaw = { backendR.get() },
                    saveRaw = { raw -> if (raw != null) backendR.set(raw); true },
                )
                val vmR = makeVm(storeR, coordR, policyIoTimeoutMs = 150L)
                try {
                    val pendingR = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
                    val txR = DualViewModel.PolicyTx(
                        coordR.nextTxId(), "r2-own-latest-res-$iter",
                        kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), storeR,
                        isClear = false, pending = pendingR, isRepair = false, executionArmed = AtomicBoolean(true),
                    )
                    coordR.activeTx.set(txR)
                    runBlocking {
                        assertTrue(
                            coordR.completeWithRevision(
                                txR,
                                PolicySettled(txR.txId, txR.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false),
                            ),
                        )
                    }
                    setReconciling(vmR, txR, pendingR, setOf("com.A"), coord = null)
                    bumpVersionsForToctou(vmR)
                    backendR.set(setOf("com.B"))
                    val realR = PolicySettled(
                        txR.txId, txR.key, setOf("com.B"), true, MsgKind.INFO, "okR-$iter", false,
                    )
                    val emitDone = java.util.concurrent.CountDownLatch(1)
                    exec.submit {
                        try {
                            if (iter % 3 != 0) Thread.sleep(0, (iter % 5) * 1000)
                            runBlocking {
                                val stamped = coordR.stageRealPayload(realR)
                                if (stamped != null) coordR.emitStaged(stamped)
                            }
                        } finally {
                            emitDone.countDown()
                        }
                    }
                    runBlocking { vmR.resolveObserverFallback(txR, LoadResult.Known(setOf("com.B")), false) }
                    assertTrue(emitDone.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(PolicyWritePhase.IDLE, vmR.state.value.policyWritePhase)
                    assertTrue(vmR.state.value.policyDraftKnown)
                    assertEquals(setOf("com.B"), vmR.state.value.policyDraft)
                    assertNull(activeTxOf(vmR))
                    assertNull(coordR.activeTx.get())
                    assertFalse(reconcilingHas(vmR, "r2-own-latest-res-$iter"))
                } finally {
                    try { vmR.dispose() } catch (_: Exception) { }
                    try { coordR.close() } catch (_: Exception) { }
                }
            }
        } finally {
            try { exec.shutdownNow() } catch (_: Exception) { }
        }
    }
}
