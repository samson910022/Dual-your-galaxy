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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
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

    private fun makeVm(
        policyStore: HookPolicyStore,
        coord: PolicyTxCoordinator,
        policyIoTimeoutMs: Long = 200L,
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
        return vm
    }

    @Suppress("UNCHECKED_CAST")
    private fun setReconciling(
        vm: DualViewModel,
        tx: DualViewModel.PolicyTx,
        pending: PendingPolicyWrite?,
        draft: Set<String>,
    ) {
        val stateF = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = stateF.get(vm) as MutableStateFlow<UiState>
        state.value = state.value.copy(
            pendingPolicyWrite = pending,
            policyWritePhase = PolicyWritePhase.RECONCILING,
            policyEpoch = state.value.policyEpoch + 1,
            policyDraft = draft,
            policyDraftKnown = false,
            policyDraftCorrupt = false,
            loading = false,
            msg = PolicyText(PolicyTextKind.WRITE_TIMEOUT).defaultText(),
            msgKind = MsgKind.CHANNEL,
        )
        val txF = DualViewModel::class.java.getDeclaredField("activePolicyTx").apply { isAccessible = true }
        (txF.get(vm) as java.util.concurrent.atomic.AtomicReference<DualViewModel.PolicyTx?>).set(tx)
        val recF = DualViewModel::class.java.getDeclaredField("policyReconciling").apply { isAccessible = true }
        (recF.get(vm) as MutableSet<String>).add(tx.key)
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
            setReconciling(vm, tx2, pending2, setOf("com.A"))
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
            setReconciling(vm, tx, pending, setOf("com.A"))

            runBlocking { vm.resolveObserverFallback(tx, LoadResult.Known(setOf("com.B")), false) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(activeTxOf(vm) === tx)
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
            setReconciling(vm, tx3, pending3, setOf("com.A"))
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
            setReconciling(vm, tx4, pending4, setOf("com.A"))
            assertTrue(deferred4.complete(true))
            runBlocking { vm.resolveObserverFallback(tx4, LoadResult.Known(setOf("com.C")), true) }
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(activeTxOf(vm) === tx4)
            assertTrue(reconcilingHas(vm, "loser4"))
            // Cancel the orphaned deferred4 path state: settle via success read without timeout.
            runBlocking { vm.resolveObserverFallback(tx4, LoadResult.Known(setOf("com.C")), false) }
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertTrue(vm.state.value.policyDraftKnown)
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
            setReconciling(vm, loser, pendingLoser, setOf("com.A"))
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
            setReconciling(vm, loser, pending, setOf("com.A"))
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
            setReconciling(vm, tx, pending, setOf("com.A"))
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
            setReconciling(vm, txF, pendingF, setOf("com.A"))
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
            setReconciling(vm, txT, pendingT, setOf("com.A"))
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
            assertTrue(reconcilingHas(vm, "unkT"))
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }
}
