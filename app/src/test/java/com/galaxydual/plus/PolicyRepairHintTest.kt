// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R5-1 (F02): app-scoped hook-restart hint tests. Framework-free (JVM):
// a fake HookPolicyStore + a shared PolicyTxCoordinator stand in for the
// GalaxyApp singleton; two DualViewModels with DIFFERENT stores model the
// Editor (repairing) VM and the Main (fresh / recreated) VM.
//
// The Invalid->v1 store-side signal is armed directly here (the production
// saveRaw path that detects Invalid and calls noteInvalidVersionRepaired() is
// covered by HookPolicyRemoteStoreTest). This file only proves the signal is
// plumbed into app-scoped state, is sticky until explicit acknowledgement, and
// never rises on a failed or a normal monotonic write.
// Real hint TextView rendering/tap in Main+Editor is DEVICE-UNVERIFIED.
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyRepairHintTest {
    private class FakeStore(
        initialDraft: Set<String>? = setOf("com.a.app"),
        private val saveOk: Boolean = true,
    ) {
        var saved: Set<String>? = initialDraft
        val store: HookPolicyStore = HookPolicyStore(
            loadRaw = { saved },
            saveRaw = { raw ->
                if (!saveOk) {
                    false
                } else {
                    saved = if (raw == null) emptySet() else HashSet(raw)
                    true
                }
            },
        )

        fun armRepairSignal() {
            val f = HookPolicyStore::class.java
                .getDeclaredField("invalidVersionRepaired").apply { isAccessible = true }
            (f.get(store) as AtomicBoolean).set(true)
        }
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
            try { vm.dispose() } catch (_: Exception) { }
            try { vm.viewModelScope.cancel() } catch (_: Exception) { }
        }
        TestDrainSupport.drainBeforeResetMain(vms, coords)
        vms.clear()
        for (c in coords) {
            try { c.close() } catch (_: Exception) { }
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
            Thread.sleep(20)
        }
    }

    private fun makeVm(store: HookPolicyStore, coord: PolicyTxCoordinator?): DualViewModel {
        if (coord != null && !coords.contains(coord)) coords.add(coord)
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
            requirePolicyWriteConsent = false,
            currentUserId = { 0 },
            policyIoTimeoutMs = 500L,
            policyTxCoordinator = coord,
        )
        vms.add(vm)
        await { vm.state.value.policyDraftKnown }
        await { !vm.state.value.loading }
        return vm
    }

    private fun completeSuccessfulWrite(vm: DualViewModel, next: Set<String>) {
        vm.replacePolicyDraft(next, vm.currentWarningBinding())
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null && vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
    }

    @Test
    fun repairHintIsAppScopedAcrossVms() {
        val coord = PolicyTxCoordinator()
        // VM1: Editor role — its store carries the Invalid->v1 repair signal.
        val editorStore = FakeStore(setOf("com.a.app"))
        editorStore.armRepairSignal()
        val vm1 = makeVm(editorStore.store, coord)
        assertFalse(vm1.repairHintFromApp.value)
        completeSuccessfulWrite(vm1, setOf("com.b.app"))
        assertTrue("editor VM per-VM mirror raised", vm1.state.value.policyRepairNeedsHookRestart)
        assertTrue("app-scoped hint raised", coord.repairHint.value)

        // VM2: Main role / VM recreation — DIFFERENT store, no repair signal,
        // sharing only the App-scoped coordinator. It still sees the hint.
        val mainStore = FakeStore(setOf("com.x.app"))
        val vm2 = makeVm(mainStore.store, coord)
        assertTrue("fresh VM observes app-scoped hint", vm2.repairHintFromApp.value)
        assertFalse("fresh VM's own store signal is false", vm2.state.value.policyRepairNeedsHookRestart)
        assertFalse(mainStore.store.consumeInvalidVersionRepair())
        assertTrue(
            "visibility ORs VM mirror and app signal",
            shouldShowRepairHint(vm2.state.value.policyRepairNeedsHookRestart, vm2.repairHintFromApp.value),
        )
    }

    @Test
    fun productionAckHidesHintEvenWhenOtherVmMirrorIsStale() {
        val coord = PolicyTxCoordinator()
        // VM1 (Editor) repairs Invalid->v1: its own mirror AND the app signal rise.
        val editorStore = FakeStore(setOf("com.a.app"))
        editorStore.armRepairSignal()
        val vm1 = makeVm(editorStore.store, coord)
        completeSuccessfulWrite(vm1, setOf("com.b.app"))
        assertTrue(vm1.hasPolicyCoordinator)
        assertTrue("VM1 mirror raised", vm1.state.value.policyRepairNeedsHookRestart)
        assertTrue(coord.repairHint.value)

        // Production visibility masks the mirror whenever a coordinator exists.
        fun visible(vm: DualViewModel): Boolean {
            val maskedVmFlag = vm.state.value.policyRepairNeedsHookRestart && !vm.hasPolicyCoordinator
            return shouldShowRepairHint(maskedVmFlag, vm.repairHintFromApp.value)
        }
        assertTrue("hint visible before ack", visible(vm1))

        // VM2 (Main, still alive) acknowledges the app-scoped signal. VM1's
        // per-VM mirror is untouched (still true) — the stale mirror must NOT
        // keep the hint visible now that the app signal is authoritative.
        val vm2 = makeVm(FakeStore(setOf("com.x.app")).store, coord)
        vm2.acknowledgeRepairHint()
        assertFalse(coord.repairHint.value)
        assertFalse(vm2.repairHintFromApp.value)
        assertTrue("VM1 mirror is stale after VM2 ack", vm1.state.value.policyRepairNeedsHookRestart)
        assertFalse("hint hidden in BOTH VMs after app ack", visible(vm1))
        assertFalse(visible(vm2))
    }

    @Test
    fun repairHintSticksThroughCleanWriteUntilAcknowledge() {
        val coord = PolicyTxCoordinator()
        val store = FakeStore(setOf("com.a.app"))
        store.armRepairSignal()
        val vm = makeVm(store.store, coord)
        completeSuccessfulWrite(vm, setOf("com.b.app"))
        assertTrue(coord.repairHint.value)

        // A normal Present version increment (no repair signal) must not lower it.
        completeSuccessfulWrite(vm, setOf("com.c.app"))
        assertTrue("sticky across a clean write", coord.repairHint.value)
        assertTrue(vm.repairHintFromApp.value)

        // Explicit acknowledgement clears both app signal and the VM mirror.
        vm.acknowledgeRepairHint()
        assertFalse(coord.repairHint.value)
        assertFalse(vm.repairHintFromApp.value)
        assertFalse(vm.state.value.policyRepairNeedsHookRestart)

        // A fresh VM seeded false observes no hint.
        val fresh = makeVm(FakeStore(setOf("com.z.app")).store, coord)
        assertFalse(fresh.repairHintFromApp.value)
        assertFalse(fresh.state.value.policyRepairNeedsHookRestart)
    }

    @Test
    fun normalPresentVersionUpgradeDoesNotRaiseHint() {
        val coord = PolicyTxCoordinator()
        val store = FakeStore(setOf("com.a.app")) // no repair signal armed
        val vm = makeVm(store.store, coord)
        completeSuccessfulWrite(vm, setOf("com.b.app"))
        assertFalse(vm.state.value.policyRepairNeedsHookRestart)
        assertFalse("app hint stays false on a normal write", coord.repairHint.value)
        assertFalse(vm.repairHintFromApp.value)
    }

    @Test
    fun failedRepairCommitDoesNotRaiseHint() {
        val coord = PolicyTxCoordinator()
        val store = FakeStore(setOf("com.a.app"), saveOk = false)
        store.armRepairSignal()
        val vm = makeVm(store.store, coord)
        vm.replacePolicyDraft(setOf("com.b.app"), vm.currentWarningBinding())
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null && vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertFalse("failed commit must not raise the app hint", coord.repairHint.value)
        assertFalse(vm.state.value.policyRepairNeedsHookRestart)
        assertTrue(
            "repair signal is NOT consumed on a failed commit",
            store.store.consumeInvalidVersionRepair(),
        )
    }

    @Test
    fun shouldShowRepairHintTruthTable() {
        assertFalse(shouldShowRepairHint(false, false))
        assertTrue(shouldShowRepairHint(true, false))
        assertTrue(shouldShowRepairHint(false, true))
        assertTrue(shouldShowRepairHint(true, true))
    }
}
