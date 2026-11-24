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

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        override fun save(state: RepairHintState): Boolean {
            map[REPAIR_HINT_KEY_ACTIVE] = state.active
            map[REPAIR_HINT_KEY_GENERATION] = state.generation
            map[REPAIR_HINT_KEY_REPAIRED_VERSION] = state.repairedVersion
            return true
        }
    }

    // PR4 review (Required 1): deterministic interleaving fake. The FIRST save
    // (the mark's active=true write) re-enters the coordinator from inside save()
    // to perform the ack, then reports false (commit(false)). JVM monitors are
    // reentrant, so the nested ack can take the SAME ioLock the mark already
    // holds. This pins the exact preemption window of the review scenario: the
    // mark's save RESULT is only reported to the coordinator AFTER a newer ack
    // has fully settled (writeId=2), so a stale mark outcome (writeId=1) could
    // otherwise re-raise the acknowledged hint.
    private class InterleavingRepairHintStore : RepairHintStore {
        private val map = HashMap<String, Any>()
        var coordinator: PolicyTxCoordinator? = null
        var saveCount = 0
            private set
        var markCommitFailed = false
            private set

        override fun load(): RepairHintState = synchronized(this) {
            RepairHintState(
                active = map[REPAIR_HINT_KEY_ACTIVE] as? Boolean ?: false,
                generation = map[REPAIR_HINT_KEY_GENERATION] as? Long ?: 0L,
                repairedVersion = map[REPAIR_HINT_KEY_REPAIRED_VERSION] as? Long ?: -1L,
            )
        }

        override fun save(state: RepairHintState): Boolean = synchronized(this) {
            saveCount++
            if (saveCount == 1) {
                markCommitFailed = true
                // A later ack linearizes BEFORE this failed result is handed back.
                coordinator?.acknowledgeRepairHint()
                false
            } else {
                // Ack's save: durable, exactly like a successful commit().
                map[REPAIR_HINT_KEY_ACTIVE] = state.active
                map[REPAIR_HINT_KEY_GENERATION] = state.generation
                map[REPAIR_HINT_KEY_REPAIRED_VERSION] = state.repairedVersion
                true
            }
        }
    }

    // PR #89: a reentrant fake that lets a NEWER mark fully settle (including a
    // FAILED save) inside the OLDER mark's successful save() before that older
    // save reports success. The older writer then reaches the post-save fence
    // with a STALE ticket; a stale success must never certify the newer pending
    // state (otherwise the next mark would early-return and skip its retry).
    private class ReentrantStaleSuccessStore : RepairHintStore {
        private val map = HashMap<String, Any>()
        val writes = CopyOnWriteArrayList<RepairHintState>()
        var coordinator: PolicyTxCoordinator? = null
        var calls = 0
            private set

        override fun load(): RepairHintState = synchronized(this) {
            RepairHintState(
                active = map[REPAIR_HINT_KEY_ACTIVE] as? Boolean ?: false,
                generation = map[REPAIR_HINT_KEY_GENERATION] as? Long ?: 0L,
                repairedVersion = map[REPAIR_HINT_KEY_REPAIRED_VERSION] as? Long ?: -1L,
            )
        }

        override fun save(state: RepairHintState): Boolean = synchronized(this) {
            calls++
            writes.add(state)
            when (calls) {
                1 -> {
                    // Older mark's physical write succeeds...
                    map[REPAIR_HINT_KEY_ACTIVE] = state.active
                    map[REPAIR_HINT_KEY_GENERATION] = state.generation
                    map[REPAIR_HINT_KEY_REPAIRED_VERSION] = state.repairedVersion
                    // ...but a newer mark settles reentrantly before this result
                    // is handed back. Its save is call #2 and FAILS.
                    coordinator?.markRepairHint()
                    true
                }
                2 -> false // newer mark's save fails -> durability stays UNKNOWN
                else -> {
                    map[REPAIR_HINT_KEY_ACTIVE] = state.active
                    map[REPAIR_HINT_KEY_GENERATION] = state.generation
                    map[REPAIR_HINT_KEY_REPAIRED_VERSION] = state.repairedVersion
                    true
                }
            }
        }
    }

    // PR4 review (Required 1, follow-up): NON-reentrant variant. The mark thread
    // is parked by the production seam AFTER writeId=1 is issued but BEFORE it
    // takes repairHintIoLock; a REAL second thread then completes the ack
    // (writeId=2) and lands its clear on disk. The reentrant fake above cannot
    // cover this window because it re-enters from inside save(), i.e. after the
    // IO lock is already held.
    // PR #89: with the fused decision this seam fires AFTER the decision (ticket
    // issued, durability pending, mirror installed) but BEFORE any IO, so both
    // interleavings below can be pinned deterministically with two real threads.
    private class GatedInterleavingRepairHintStore : RepairHintStore {
        private val map = HashMap<String, Any>()
        val writes = CopyOnWriteArrayList<RepairHintState>()

        override fun load(): RepairHintState = synchronized(this) {
            RepairHintState(
                active = map[REPAIR_HINT_KEY_ACTIVE] as? Boolean ?: false,
                generation = map[REPAIR_HINT_KEY_GENERATION] as? Long ?: 0L,
                repairedVersion = map[REPAIR_HINT_KEY_REPAIRED_VERSION] as? Long ?: -1L,
            )
        }

        override fun save(state: RepairHintState): Boolean = synchronized(this) {
            writes.add(state)
            map[REPAIR_HINT_KEY_ACTIVE] = state.active
            map[REPAIR_HINT_KEY_GENERATION] = state.generation
            map[REPAIR_HINT_KEY_REPAIRED_VERSION] = state.repairedVersion
            true
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

    // =====================================================================
    // #76 RED REGRESSION TESTS - RepairHintStore durability (historical)
    //
    // Historical note: these were authored as RED tests at 5621769a. Against the
    // PRE-FIX API/behavior they failed on assertion because:
    //   * `applyRepairHint()` set `persisted = next` BEFORE `save()` and ignored
    //     the commit Boolean, so a failed save still left the live coordinator
    //     "active" in memory while nothing was durable; a new coordinator loaded
    //     inactive and stopped warning.
    //   * `load()` caught ANY Throwable and returned RepairHintState(false,0,-1),
    //     hiding an ALREADY-PERSISTED active hint after restart.
    // With the #76 fix applied they are now GREEN. The current API already has
    // `RepairHintStore.save()` returning Boolean and `loadWasRecovered()`.
    //
    // #76 fix direction (now implemented): verify durability (propagate
    // commit(false)/exceptions), treat unknown durability as UNKNOWN-active (bias
    // toward repeating the warning), never equate in-memory `persisted.active`
    // with a durable save, and serialize repair-hint commits with
    // `repairHintIoLock` (physical commit stays OFF the coordinator monitor;
    // it is NOT dispatched to a background IO owner — callers are the VM IO
    // dispatcher / UI thread).
    // =====================================================================

    private open class DurabilityPrefs(
        initial: Map<String, Any?> = emptyMap(),
        var commitOk: Boolean = true,
    ) : SharedPreferences {
        val durable = LinkedHashMap<String, Any?>(initial)
        var commitCount = 0
            private set
        var applyCount = 0
            private set
        var lastCommitted: Map<String, Any?> = emptyMap()
            private set

        protected open fun readBooleanValue(key: String?, defValue: Boolean): Boolean =
            (durable[key] as? Boolean) ?: defValue
        protected open fun readLongValue(key: String?, defValue: Long): Long =
            (durable[key] as? Long) ?: defValue

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(durable)
        override fun getString(key: String?, defValue: String?): String? = durable[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            durable[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = durable[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = readLongValue(key, defValue)
        override fun getFloat(key: String?, defValue: Float): Float = durable[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = readBooleanValue(key, defValue)
        override fun contains(key: String?): Boolean = durable.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { pending.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { pending.clear(); return this }
            override fun commit(): Boolean {
                commitCount++
                lastCommitted = LinkedHashMap(pending)
                // commit(false): the pending write is DISCARDED, like a real
                // SharedPreferences whose disk write failed. This is the signal
                // the current production save() throws away.
                if (commitOk) durable.putAll(pending)
                pending.clear()
                return commitOk
            }
            override fun apply() {
                applyCount++
                durable.putAll(pending)
                pending.clear()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    // Models a corrupt `active` key: getBoolean(REPAIR_HINT_KEY_ACTIVE, ...) throws.
    // PR4 review (Required 2): the fail-closed `active = true` arm of load() had
    // no test — a mutation to `false` went undetected by all five RED tests.
    private class ThrowingActivePrefs(initial: Map<String, Any?>) : DurabilityPrefs(initial) {
        override fun readBooleanValue(key: String?, defValue: Boolean): Boolean {
            if (key == REPAIR_HINT_KEY_ACTIVE) {
                throw IllegalStateException("simulated corrupt active-key read")
            }
            return super.readBooleanValue(key, defValue)
        }
    }

    // Models a durable `active=true` marker whose optional `repaired_version`
    // key is corrupt, so getLong(REPAIR_HINT_KEY_REPAIRED_VERSION, ...) throws.
    private class ThrowingRepairedVersionPrefs(initial: Map<String, Any?>) : DurabilityPrefs(initial) {
        override fun readLongValue(key: String?, defValue: Long): Long {
            if (key == REPAIR_HINT_KEY_REPAIRED_VERSION) {
                throw IllegalStateException("simulated corrupt SharedPreferences read")
            }
            return super.readLongValue(key, defValue)
        }
    }

    @Test
    fun commitFalseBeforeMark_keepsWarningInNewCoordinatorSameProcess() {
        // #76 RED: commit(false) is ignored. Coordinator 1 marks active IN MEMORY
        // (persisted = next before save()) but durable store stays inactive.
        // A NEW COORDINATOR IN THE SAME PROCESS, sharing the same prefs instance,
        // then loads inactive and stops warning. Fix: failed/UNKNOWN durability
        // must remain UNKNOWN-active so the warning survives a coordinator swap —
        // in-memory active must never be confused with a verified durable save.
        //
        // LIMITATION: this reuses the same in-memory prefs/store instance, so it
        // models a new coordinator in the same process, NOT a real process restart.
        // When NO write ever landed durably (commit(false) discarded the pending
        // edit), a genuine process restart has nothing on disk to recover from —
        // durability cannot survive a cold start without a successful commit.
        // The honest contract here is UNKNOWN-active within the process; true
        // cross-process survival is covered by the successful-commit tests above.
        val prefs = DurabilityPrefs(commitOk = false)
        val store = prefs.repairHintStore()

        val coord1 = PolicyTxCoordinator(repairHintStore = store)
        coord1.markRepairHint(repairedVersion = 1L)
        assertTrue("live coordinator warns right after the repair", coord1.repairHint.value)
        assertEquals("a durable save was attempted", 1, prefs.commitCount)
        assertFalse(
            "commit(false) left nothing durable",
            prefs.durable[REPAIR_HINT_KEY_ACTIVE] as? Boolean ?: false,
        )

        // New coordinator IN THE SAME PROCESS, same app-private prefs instance.
        val coord2 = PolicyTxCoordinator(repairHintStore = store)
        assertTrue(
            "#76 RED: warning must stay visible in the new coordinator even though the durable save failed " +
                "(unknown durability must stay UNKNOWN-active, never silently inactive)",
            coord2.repairHint.value,
        )
    }

    @Test
    fun activePersistedThenGetLongThrows_stillWarns() {
        // #76 RED: `load()` swallows ANY Throwable and returns the DEFAULT
        // inactive state, hiding an ALREADY-PERSISTED active hint. Here active=true
        // and generation=1 ARE durable, but reading repaired_version throws.
        // A fresh coordinator must still warn (UNKNOWN-active bias toward
        // repeating the warning); a corrupt optional read must not auto-clear a
        // hint that was already durably active.
        val prefs = ThrowingRepairedVersionPrefs(
            mapOf(
                REPAIR_HINT_KEY_ACTIVE to true,
                REPAIR_HINT_KEY_GENERATION to 1L,
            ),
        )
        val store = prefs.repairHintStore()

        val coord = PolicyTxCoordinator(repairHintStore = store)
        assertTrue(
            "#76 RED: a corrupt field read must not downgrade a persisted active hint to inactive",
            coord.repairHint.value,
        )
        assertTrue(
            "#76 RED: the coordinator's persisted mirror must stay active (UNKNOWN-active) after the read failure",
            coord.repairHintPersistedState.active,
        )
    }

    @Test
    fun activeKeyReadThrows_failClosedToWarning() {
        // PR4 review (Required 2): mutation M3 flipped load()'s fail-closed
        // `active = true` to `false` and all five RED tests stayed green. This
        // pins that branch directly: a throwing getBoolean(active) must be
        // reported active, must mark the load as recovered, and must seed a
        // fresh coordinator with a visible warning (a corrupt read never
        // auto-clears an existing hint).
        val prefs = ThrowingActivePrefs(emptyMap())
        val store = prefs.repairHintStore()

        val loaded = store.load()
        assertTrue(
            "#76: a throwing getBoolean(active) must fail closed to active=true",
            loaded.active,
        )
        assertTrue(
            "#76: a throwing getBoolean(active) must mark the load as recovered",
            store.loadWasRecovered(),
        )

        val coord = PolicyTxCoordinator(repairHintStore = store)
        assertTrue(
            "#76: a new coordinator seeded from a failed active read must warn",
            coord.repairHint.value,
        )
        assertTrue("UNKNOWN-active mirror must stay active", coord.repairHintPersistedState.active)
    }

    @Test
    fun staleMarkFailure_doesNotResurrectHintAfterNewerAckSucceeds() {
        // PR4 review (Required 1): A(mark, writeId=1) save(false) is preempted
        // after releasing the IO lock; B(ack, writeId=2) save(true) settles
        // first; A's stale failure result must NOT re-raise the hint, flip the
        // persisted mirror, or clobber the newer durability verdict.
        val store = InterleavingRepairHintStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)
        store.coordinator = coord

        coord.markRepairHint(repairedVersion = 1L)

        assertEquals("mark save then ack save", 2, store.saveCount)
        assertTrue("the mark's save really reported failure", store.markCommitFailed)
        assertFalse(
            "ack's durable clear must win: no resurrected warning",
            coord.repairHint.value,
        )
        assertFalse("the in-memory mirror must stay cleared", coord.repairHintPersistedState.active)
        assertFalse("the durable store must stay cleared", store.load().active)
        val fresh = PolicyTxCoordinator(repairHintStore = store)
        assertFalse("the warning must not revive in a later coordinator either", fresh.repairHint.value)
    }

    @Test
    fun staleMarkCannotWriteActiveOnDiskAfterNewerAckOnAnotherThread() {
        // PR4 review (Required 1, follow-up): non-reentrant interleaving of two
        // REAL threads. The mark is parked after writeId=1 is issued but before
        // it takes repairHintIoLock; a separate ack thread then issues writeId=2
        // and commits active=false. Releasing the mark must skip its physical
        // save — the old code only fenced the in-memory RESULT, so the stale
        // active=true could still land on disk last.
        val store = GatedInterleavingRepairHintStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)
        coords.add(coord)

        val markEnteredSeam = CountDownLatch(1)
        val releaseMark = CountDownLatch(1)
        val seamArmed = AtomicBoolean(true)
        coord.beforeRepairHintIoLockSeamForTests = {
            // Only the FIRST writer (the mark) parks; the later ack passes through.
            if (seamArmed.compareAndSet(true, false)) {
                markEnteredSeam.countDown()
                try {
                    releaseMark.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val markThread = Thread {
            coord.markRepairHint(repairedVersion = 1L)
        }.apply { isDaemon = true; name = "repair-hint-mark" }

        val ackDone = CountDownLatch(1)
        try {
            markThread.start()
            assertTrue("mark must reach the pre-IO-lock seam", markEnteredSeam.await(3, TimeUnit.SECONDS))

            // A DIFFERENT real thread settles the ack while the mark is parked.
            val ackThread = Thread {
                try {
                    coord.acknowledgeRepairHint()
                } finally {
                    ackDone.countDown()
                }
            }.apply { isDaemon = true; name = "repair-hint-ack" }
            ackThread.start()
            assertTrue("ack must complete while the mark is parked", ackDone.await(3, TimeUnit.SECONDS))
            ackThread.join(3_000L)
            assertFalse("ack thread must have finished", ackThread.isAlive)

            releaseMark.countDown()
            markThread.join(3_000L)
            assertFalse("mark thread must have finished", markThread.isAlive)

            assertEquals("only the ack may physically commit", 1, store.writes.size)
            assertFalse("the durable value must stay the ack's false", store.load().active)
            assertFalse("no resurrected warning", coord.repairHint.value)
            assertFalse("the in-memory mirror must stay cleared", coord.repairHintPersistedState.active)
            val fresh = PolicyTxCoordinator(repairHintStore = store)
            coords.add(fresh)
            assertFalse("later coordinator must stay quiet too", fresh.repairHint.value)
        } finally {
            releaseMark.countDown()
            coord.beforeRepairHintIoLockSeamForTests = null
            markThread.join(3_000L)
        }
    }

    @Test
    fun markDecidesFirst_ackOnAnotherThreadWinsEverywhere() {
        // PR #89 ordering (a): the mark linearizes first (active=true, gen=1) and
        // parks after its FUSED decision, before IO. A separate real thread then
        // runs a FULL acknowledge (clear + disk commit). Releasing the mark must
        // leave the ack as the last decision AND the last physical write: no
        // resurrected warning, not even in a fresh (cold-start) coordinator.
        val store = GatedInterleavingRepairHintStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)
        coords.add(coord)

        val firstDecisionParked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val armFirst = AtomicBoolean(true)
        coord.beforeRepairHintIoLockSeamForTests = {
            if (armFirst.compareAndSet(true, false)) {
                firstDecisionParked.countDown()
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val markThread = Thread {
            coord.markRepairHint(repairedVersion = 1L)
        }.apply { isDaemon = true; name = "repair-hint-mark-first" }

        try {
            markThread.start()
            assertTrue("mark must reach the post-decision pre-IO seam", firstDecisionParked.await(3, TimeUnit.SECONDS))

            val ackDone = CountDownLatch(1)
            val ackThread = Thread {
                try {
                    coord.acknowledgeRepairHint()
                } finally {
                    ackDone.countDown()
                }
            }.apply { isDaemon = true; name = "repair-hint-ack-second" }
            ackThread.start()
            assertTrue("ack must fully settle while the mark is parked", ackDone.await(3, TimeUnit.SECONDS))
            ackThread.join(3_000L)
            assertFalse("ack thread must have finished", ackThread.isAlive)
            assertTrue(
                "a successful ack must flip the REAL durability verdict to true",
                coord.repairHintDurableForTests,
            )

            releaseFirst.countDown()
            markThread.join(3_000L)
            assertFalse("mark thread must have finished", markThread.isAlive)

            assertEquals("the stale mark must skip its physical save", 1, store.writes.size)
            assertFalse("last disk write is the ack's clear", store.writes.last().active)
            assertEquals("the clear keeps the mark's generation", 1L, store.writes.last().generation)
            assertFalse("live hint stays cleared", coord.repairHint.value)
            assertFalse("in-memory mirror stays cleared", coord.repairHintPersistedState.active)
            assertEquals("generation must not regress", 1L, coord.repairHintPersistedState.generation)
            assertTrue(
                "a stale writer must not clobber the newest write's durability verdict",
                coord.repairHintDurableForTests,
            )
            assertFalse("durable value stays cleared", store.load().active)

            val fresh = PolicyTxCoordinator(repairHintStore = store)
            coords.add(fresh)
            assertFalse("cold start must not resurrect an acknowledged warning", fresh.repairHint.value)
            assertFalse(fresh.repairHintPersistedState.active)
            assertEquals(1L, fresh.repairHintPersistedState.generation)
        } finally {
            releaseFirst.countDown()
            coord.beforeRepairHintIoLockSeamForTests = null
            markThread.join(3_000L)
        }
    }

    @Test
    fun ackDecidesFirst_newerMarkOnAnotherThreadWinsAndGenerationNeverRegresses() {
        // PR #89 ordering (b): mirror the review scenario with the roles swapped.
        // Start from a DURABLE active marker (gen=3); park a full clear after its
        // fused decision; a newer mark settles from another thread (gen=4); then
        // release the ack. The ack's later ticket must be stale for BOTH its
        // physical save and its outcome fence, so disk stays active=true and a
        // fresh coordinator still warns.
        val store = GatedInterleavingRepairHintStore()
        store.save(RepairHintState(active = true, generation = 3L, repairedVersion = 1L))
        val coord = PolicyTxCoordinator(repairHintStore = store)
        coords.add(coord)
        assertTrue(coord.repairHint.value)
        assertEquals(3L, coord.repairHintPersistedState.generation)

        val firstDecisionParked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val armFirst = AtomicBoolean(true)
        coord.beforeRepairHintIoLockSeamForTests = {
            if (armFirst.compareAndSet(true, false)) {
                firstDecisionParked.countDown()
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val ackThread = Thread {
            coord.acknowledgeRepairHint()
        }.apply { isDaemon = true; name = "repair-hint-ack-first" }

        try {
            ackThread.start()
            assertTrue("ack must reach the post-decision pre-IO seam", firstDecisionParked.await(3, TimeUnit.SECONDS))

            val markDone = CountDownLatch(1)
            val markThread = Thread {
                try {
                    coord.markRepairHint(repairedVersion = 2L)
                } finally {
                    markDone.countDown()
                }
            }.apply { isDaemon = true; name = "repair-hint-mark-second" }
            markThread.start()
            assertTrue("newer mark must settle while the ack is parked", markDone.await(3, TimeUnit.SECONDS))
            markThread.join(3_000L)
            assertFalse("mark thread must have finished", markThread.isAlive)

            releaseFirst.countDown()
            ackThread.join(3_000L)
            assertFalse("ack thread must have finished", ackThread.isAlive)

            // One preloaded write + the newer mark; the stale ack never touches disk.
            assertEquals(2, store.writes.size)
            assertTrue("last disk write is the newer mark's active=true", store.writes.last().active)
            assertEquals("generation must not regress", 4L, store.writes.last().generation)
            assertTrue("live hint stays raised", coord.repairHint.value)
            assertTrue("in-memory mirror stays active", coord.repairHintPersistedState.active)
            assertEquals(4L, coord.repairHintPersistedState.generation)
            assertTrue("durable value stays active", store.load().active)

            val fresh = PolicyTxCoordinator(repairHintStore = store)
            coords.add(fresh)
            assertTrue("cold start must still warn about the live repair", fresh.repairHint.value)
            assertEquals("generation must not regress across restart", 4L, fresh.repairHintPersistedState.generation)
        } finally {
            releaseFirst.countDown()
            coord.beforeRepairHintIoLockSeamForTests = null
            ackThread.join(3_000L)
        }
    }

    @Test
    fun staleSuccessfulSave_cannotCertifyNewerPendingState() {
        // PR #89: the older mark's save() physically succeeds, but a NEWER mark
        // (a retry of the still-pending active intent) fully settles reentrantly
        // before the older result is reported. The older writer's ticket is then
        // stale at the post-save fence: it must NOT certify durability for the
        // newer state. Observable proof: a subsequent mark still retries the
        // write (3 commit calls) with the SAME generation (retry verbatim).
        val store = ReentrantStaleSuccessStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)
        coords.add(coord)
        store.coordinator = coord

        coord.markRepairHint(repairedVersion = 1L)
        assertEquals("mark plus reentrant retry", 2, store.calls)
        assertFalse(
            "the newest (failed) save must leave real durability UNKNOWN, not certified",
            coord.repairHintDurableForTests,
        )

        // If the stale success had been applied, this mark would see
        // active && durable and early-return (no third save).
        coord.markRepairHint(repairedVersion = 2L)
        assertEquals("stale success must not skip the newer mark's retry", 3, store.calls)
        assertEquals(1L, store.writes[0].generation)
        assertEquals("reentrant retry is verbatim", 1L, store.writes[1].generation)
        assertEquals("later retry is verbatim", 1L, store.writes[2].generation)
        assertTrue("live hint stays raised", coord.repairHint.value)
        assertTrue("the retried write landed durably", store.load().active)
        assertTrue(
            "the newest successful retry certifies real durability",
            coord.repairHintDurableForTests,
        )

        // Cold start over the same store must still raise the durable hint.
        val fresh = PolicyTxCoordinator(repairHintStore = store)
        coords.add(fresh)
        assertTrue("cold start must still warn about the retried repair", fresh.repairHint.value)
        assertTrue(fresh.repairHintPersistedState.active)
        assertEquals(1L, fresh.repairHintPersistedState.generation)
    }

    @Test
    fun failedFirstSave_thenSecondMark_retriesPersist() {
        // #76 RED: markRepairHint() early-returns when the in-memory
        // `persisted.active` is true, so a second notification after a FAILED
        // first save never retries the durable write. Fix: a failed/undurable
        // save must not count as persisted-active; the second mark must retry
        // and eventually land (verified durability, no in-memory=durable).
        val prefs = DurabilityPrefs(commitOk = false)
        val store = prefs.repairHintStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)

        coord.markRepairHint(repairedVersion = 1L)
        assertEquals("first mark attempts exactly one commit", 1, prefs.commitCount)
        assertFalse(
            "first commit(false) is not durable",
            prefs.durable[REPAIR_HINT_KEY_ACTIVE] as? Boolean ?: false,
        )

        // Storage is writable now; a later repair notification must re-persist.
        prefs.commitOk = true
        coord.markRepairHint(repairedVersion = 2L)

        assertEquals(
            "#76 RED: a second mark after a failed save must retry persistence " +
                "(in-memory active must not be treated as durable)",
            2,
            prefs.commitCount,
        )
        assertEquals(
            "#76 RED: the retried save must actually land",
            true,
            prefs.durable[REPAIR_HINT_KEY_ACTIVE] as? Boolean ?: false,
        )
    }

    // =====================================================================
    // PR #89 FIXER: discriminating seams for the FUSED decision.
    //
    // The earlier tests park at beforeRepairHintIoLockSeamForTests, which in the
    // UNFIXED base already sat AFTER ticket issuance (inside persistRepairHint)
    // and therefore passed against the bug. The seam below is in the CALLER,
    // immediately after the decision block and before publish/save: in a split
    // implementation the ticket is issued later, so the parked writer has NO
    // ticket yet and a newer ack can overtake it. That is the regression this
    // test pins.
    // =====================================================================
    @Test
    fun decisionBeforeTicket_mustNotLetOlderIntentLandLast() {
        val store = GatedInterleavingRepairHintStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)
        coords.add(coord)

        val firstDecisionParked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val armFirst = AtomicBoolean(true)
        coord.afterRepairHintDecisionSeamForTests = {
            // ONE-SHOT: the ack's own decision must NOT park, otherwise the
            // main thread deadlocks waiting for the ack it is running itself.
            if (armFirst.compareAndSet(true, false)) {
                firstDecisionParked.countDown()
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val markThread = Thread {
            coord.markRepairHint(repairedVersion = 1L)
        }.apply { isDaemon = true; name = "repair-hint-decision-first" }

        try {
            markThread.start()
            assertTrue("mark must reach the post-decision seam", firstDecisionParked.await(3, TimeUnit.SECONDS))

            // Full ack on the test thread: decides active=false with a NEWER
            // ticket, publishes it, commits false, and certifies durability.
            coord.acknowledgeRepairHint()
            assertTrue("the ack must certify durability", coord.repairHintDurableForTests)
            assertFalse(coord.repairHint.value)

            releaseFirst.countDown()
            markThread.join(3_000L)
            assertFalse("mark thread must have finished", markThread.isAlive)

            assertEquals("the stale decision must not write active on disk", 1, store.writes.size)
            assertFalse("final disk value is the ack's false", store.load().active)
            assertFalse("final live flow stays cleared", coord.repairHint.value)
            assertFalse("final in-memory mirror stays cleared", coord.repairHintPersistedState.active)
            assertTrue("stale writer must not clobber the ack's durability verdict", coord.repairHintDurableForTests)

            val fresh = PolicyTxCoordinator(repairHintStore = store)
            coords.add(fresh)
            assertFalse("a fresh coordinator must stay quiet", fresh.repairHint.value)
            assertFalse(fresh.repairHintPersistedState.active)
        } finally {
            releaseFirst.countDown()
            coord.afterRepairHintDecisionSeamForTests = null
            markThread.join(3_000L)
        }
    }

    @Test
    fun olderPublishDecision_cannotOverwriteNewerPublishedClear() {
        // The publisher seam sits at ENTRY, before repairHintPublishLock, so a
        // parked older publisher cannot block the newer one. Only the monotonic
        // `intent.ticket <= lastPublishedRepairHintTicket` check keeps the older
        // publication from re-raising the flow after the newer clear landed.
        val store = GatedInterleavingRepairHintStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)
        coords.add(coord)

        val olderParked = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val armFirst = AtomicBoolean(true)
        coord.beforeRepairHintPublishSeamForTests = {
            if (armFirst.compareAndSet(true, false)) {
                olderParked.countDown()
                try {
                    releaseOlder.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val olderThread = Thread {
            coord.markRepairHint(repairedVersion = 1L)
        }.apply { isDaemon = true; name = "repair-hint-older-publisher" }

        try {
            olderThread.start()
            assertTrue("older publisher must reach the entry seam", olderParked.await(3, TimeUnit.SECONDS))

            coord.acknowledgeRepairHint() // newer ticket publishes false and commits false
            assertFalse(coord.repairHint.value)

            releaseOlder.countDown()
            olderThread.join(3_000L)
            assertFalse("older thread must have finished", olderThread.isAlive)

            assertFalse("a stale publication must not re-raise the live flow", coord.repairHint.value)
            assertFalse("in-memory mirror stays cleared", coord.repairHintPersistedState.active)
            assertFalse("disk stays cleared", store.load().active)

            val fresh = PolicyTxCoordinator(repairHintStore = store)
            coords.add(fresh)
            assertFalse("cold start stays quiet", fresh.repairHint.value)
        } finally {
            releaseOlder.countDown()
            coord.beforeRepairHintPublishSeamForTests = null
            olderThread.join(3_000L)
        }
    }

    @Test
    fun resetForTest_resetsPublicationTicket_soNextMarkPublishes() {
        val store = GatedInterleavingRepairHintStore()
        val coord = PolicyTxCoordinator(repairHintStore = store)
        coords.add(coord)

        coord.markRepairHint(repairedVersion = 1L)
        assertTrue("first mark publishes active", coord.repairHint.value)

        coord.resetForTest()
        assertFalse("reset publishes inactive", coord.repairHint.value)
        assertFalse("reset clears the mirror", coord.repairHintPersistedState.active)
        assertEquals(0L, coord.repairHintPersistedState.generation)

        // If resetForTest forgot lastPublishedRepairHintTicket, the next mark
        // would reissue ticket=1 and the monotonic publish fence would swallow
        // its active=true publication.
        coord.markRepairHint(repairedVersion = 2L)
        assertTrue("next mark must publish active after reset", coord.repairHint.value)
        assertTrue(coord.repairHintPersistedState.active)
        assertEquals(1L, coord.repairHintPersistedState.generation)
        assertTrue("next mark must land durably after reset", store.load().active)
    }
}
