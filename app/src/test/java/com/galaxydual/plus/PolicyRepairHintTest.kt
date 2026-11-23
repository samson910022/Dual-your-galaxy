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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyRepairHintTest {
    // Passive fake of the hook side. App writes never touch it; the R6-2 tests
    // assert it is UNCHANGED to prove no PID/high-water-based auto-clear exists.
    private data class FakeHookRuntime(var pid: Int = 777, var highWater: Long = 50L)

    private class FakeStore(
        initialDraft: Set<String>? = setOf("com.a.app"),
        private val saveOk: Boolean = true,
        initialVersion: Long = 50L,
    ) {
        var saved: Set<String>? = initialDraft
        var version: Long = initialVersion
            private set
        val store: HookPolicyStore = HookPolicyStore(
            loadRaw = { saved },
            saveRaw = { raw ->
                if (!saveOk) {
                    false
                } else {
                    saved = if (raw == null) emptySet() else HashSet(raw)
                    // Mirror production saveRaw's version bump: Invalid(0) -> v1,
                    // otherwise monotonic +1. Models "Present v50 -> v51" and
                    // "Invalid -> v1" for the R6-2 persistence tests.
                    version = if (version <= 0L) 1L else version + 1L
                    true
                }
            },
        )

        fun armRepairSignal() {
            // Simulate the Invalid version key that the next successful commit repairs.
            version = 0L
            val f = HookPolicyStore::class.java
                .getDeclaredField("invalidVersionRepaired").apply { isAccessible = true }
            (f.get(store) as AtomicBoolean).set(true)
        }
    }

    // Map-backed fake mirroring the production SharedPreferences keys. Two
    // coordinators sharing ONE instance model an App process restart (new
    // coordinator, same app-private prefs file).
    private class FakeRepairHintStore : RepairHintStore {
        private val map = HashMap<String, Any>()
        override fun load(): RepairHintState = RepairHintState(
            active = map[REPAIR_HINT_KEY_ACTIVE] as? Boolean ?: false,
            generation = map[REPAIR_HINT_KEY_GENERATION] as? Long ?: 0L,
            repairedVersion = map[REPAIR_HINT_KEY_REPAIRED_VERSION] as? Long ?: -1L,
        )
        override fun save(state: RepairHintState) {
            map[REPAIR_HINT_KEY_ACTIVE] = state.active
            map[REPAIR_HINT_KEY_GENERATION] = state.generation
            map[REPAIR_HINT_KEY_REPAIRED_VERSION] = state.repairedVersion
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

        // Explicit acknowledgement clears both app signal and the VM mirror
        // (and the persisted marker; generation stays monotonic).
        vm.acknowledgeRepairHint()
        assertFalse(coord.repairHint.value)
        assertFalse(coord.repairHintPersistedState.active)
        assertEquals(1L, coord.repairHintPersistedState.generation)
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

    // ---- PR #74 R6-2 (G02): persistence across App process restart ----

    @Test
    fun repairHintSurvivesAppProcessRestartWhenHookPidUnchanged() {
        // Hook side: pid 777, version high-water 50. The app's repaired v1 write
        // does NOT make the hook reload; nothing in the fix observes this process.
        val hook = FakeHookRuntime(pid = 777, highWater = 50L)
        val hintStore = FakeRepairHintStore()

        // Phase 1: Editor VM repairs an Invalid(v0) key -> v1 with a successful
        // commit. The real VM write below drives the production ok && repaired
        // path: DualViewModel calls no-arg markRepairHint(), so this exercises the
        // inactive -> active persistence transition (the exact G02 scenario).
        val coord1 = PolicyTxCoordinator(repairHintStore = hintStore)
        val editorStore = FakeStore(setOf("com.a.app"))
        editorStore.armRepairSignal()
        val vm1 = makeVm(editorStore.store, coord1)
        completeSuccessfulWrite(vm1, setOf("com.b.app"))
        assertTrue(coord1.repairHint.value)
        assertTrue(vm1.state.value.policyRepairNeedsHookRestart)
        assertEquals(1L, editorStore.version)
        val persisted1 = hintStore.load()
        assertTrue("persisted active", persisted1.active)
        assertEquals(1L, persisted1.generation)
        assertEquals(-1L, persisted1.repairedVersion)

        // Simulate App process restart: a NEW coordinator, same app-private prefs.
        val coord2 = PolicyTxCoordinator(repairHintStore = hintStore)
        assertTrue("hint live immediately at construction after restart", coord2.repairHint.value)

        // Main VM: DIFFERENT store, no repair signal. It must warn from the
        // coordinator alone, with its own mirror/signal still untouched.
        val mainStore = FakeStore(setOf("com.x.app"))
        val vm2 = makeVm(mainStore.store, coord2)
        assertTrue(vm2.repairHintFromApp.value)
        assertTrue(
            "shouldShowRepairHint via app signal",
            shouldShowRepairHint(vm2.state.value.policyRepairNeedsHookRestart, vm2.repairHintFromApp.value),
        )
        assertFalse("VM2 mirror stays false", vm2.state.value.policyRepairNeedsHookRestart)
        assertFalse("VM2 store signal not consumed", mainStore.store.consumeInvalidVersionRepair())

        // A second screen sharing coord2 warns too.
        val vm3 = makeVm(FakeStore(setOf("com.y.app")).store, coord2)
        assertTrue(vm3.repairHintFromApp.value)
        assertTrue(shouldShowRepairHint(false, vm3.repairHintFromApp.value))

        // No PID / high-water re-derivation: the hook side is untouched.
        assertEquals(777, hook.pid)
        assertEquals(50L, hook.highWater)
    }

    @Test
    fun monotonicVersionConfirmationDoesNotRaiseRepairHint() {
        val hintStore = FakeRepairHintStore()
        val coord1 = PolicyTxCoordinator(repairHintStore = hintStore)
        // Present v50 -> a normal monotonic write yields v51 (no repair signal).
        val store1 = FakeStore(setOf("com.a.app"), initialVersion = 50L)
        val vm1 = makeVm(store1.store, coord1)
        completeSuccessfulWrite(vm1, setOf("com.b.app"))
        assertEquals(51L, store1.version)
        assertFalse(coord1.repairHint.value)
        assertFalse(vm1.repairHintFromApp.value)
        val p1 = hintStore.load()
        assertFalse("persisted inactive", p1.active)
        assertEquals("generation unchanged", 0L, p1.generation)

        // Restart with the same prefs: still quiet, and another monotonic write
        // must not raise it in either the old or the new flow.
        val coord2 = PolicyTxCoordinator(repairHintStore = hintStore)
        assertFalse(coord2.repairHint.value)
        val store2 = FakeStore(setOf("com.b.app"), initialVersion = 51L)
        val vm2 = makeVm(store2.store, coord2)
        completeSuccessfulWrite(vm2, setOf("com.c.app"))
        assertEquals(52L, store2.version)
        assertFalse(coord2.repairHint.value)
        assertFalse(vm2.repairHintFromApp.value)
        assertFalse("old flow stays quiet", vm1.repairHintFromApp.value)
        val p2 = hintStore.load()
        assertFalse(p2.active)
        assertEquals(0L, p2.generation)
    }

    @Test
    fun acknowledgedRepairStaysClearedAcrossRestartAndCrossScreen() {
        val hintStore = FakeRepairHintStore()
        val coord1 = PolicyTxCoordinator(repairHintStore = hintStore)
        val editorStore = FakeStore(setOf("com.a.app"))
        editorStore.armRepairSignal()
        val vm1 = makeVm(editorStore.store, coord1)
        completeSuccessfulWrite(vm1, setOf("com.b.app"))
        assertTrue(vm1.hasPolicyCoordinator)
        assertTrue(coord1.repairHint.value)

        // The ONLY production clear: explicit acknowledgement.
        vm1.acknowledgeRepairHint()
        assertFalse(coord1.repairHint.value)
        assertFalse("persisted cleared", hintStore.load().active)
        assertEquals("generation monotonic after ack", 1L, hintStore.load().generation)

        // Restart + two screens sharing the new coordinator: all quiet.
        val coord2 = PolicyTxCoordinator(repairHintStore = hintStore)
        val vm2 = makeVm(FakeStore(setOf("com.x.app")).store, coord2)
        val vm3 = makeVm(FakeStore(setOf("com.y.app")).store, coord2)
        assertFalse(vm2.repairHintFromApp.value)
        assertFalse(vm3.repairHintFromApp.value)
        assertFalse(vm2.state.value.policyRepairNeedsHookRestart)
        assertFalse(vm3.state.value.policyRepairNeedsHookRestart)

        // A monotonic write after ack must not silently re-raise the hint.
        completeSuccessfulWrite(vm2, setOf("com.z.app"))
        assertFalse(coord2.repairHint.value)
        assertFalse(vm2.repairHintFromApp.value)

        // hasPolicyCoordinator masking: a stale per-VM mirror cannot resurrect it.
        val masked2 = vm2.state.value.policyRepairNeedsHookRestart && !vm2.hasPolicyCoordinator
        val masked3 = vm3.state.value.policyRepairNeedsHookRestart && !vm3.hasPolicyCoordinator
        assertFalse(shouldShowRepairHint(masked2, vm2.repairHintFromApp.value))
        assertFalse(shouldShowRepairHint(masked3, vm3.repairHintFromApp.value))
    }

    @Test
    fun repairHintIsNotClearedByHookIdentityChangeWithoutAck() {
        val hook = FakeHookRuntime(pid = 777, highWater = 50L)
        val hintStore = FakeRepairHintStore()
        val coord1 = PolicyTxCoordinator(repairHintStore = hintStore)
        coord1.markRepairHint(repairedVersion = 1L)
        assertTrue(coord1.repairHint.value)

        // Only the fake hook identity changes; no acknowledgement anywhere.
        // A new app coordinator must still warn (no PID-based auto-clear seam).
        hook.pid = 999
        hook.highWater = 1L
        val coord2 = PolicyTxCoordinator(repairHintStore = hintStore)
        assertTrue("new app coordinator still warns", coord2.repairHint.value)
        val vm = makeVm(FakeStore(setOf("com.x.app")).store, coord2)
        assertTrue(vm.repairHintFromApp.value)
        assertTrue(hintStore.load().active)
    }
}
