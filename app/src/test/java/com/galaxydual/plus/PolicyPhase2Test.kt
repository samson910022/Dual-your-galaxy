// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicyPhase2Test {

    private val vms = CopyOnWriteArrayList<DualViewModel>()
    private val coords = CopyOnWriteArrayList<PolicyTxCoordinator>()
    private val testCleanup = AtomicBoolean(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        testCleanup.set(false)
        resetRemotePoisonForTests()
    }

    @After
    fun tearDown() {
        testCleanup.set(true)
        for (vm in vms) {
            try { vm.viewModelScope.cancel() } catch (_: Throwable) { }
        }
        vms.clear()
        for (coord in coords) {
            try { coord.close() } catch (_: Throwable) { }
        }
        coords.clear()
        resetRemotePoisonForTests()
        Dispatchers.resetMain()
    }

    private fun await(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (testCleanup.get() || System.currentTimeMillis() >= deadline) {
                throw AssertionError("timeout waiting for condition after ${timeoutMs}ms")
            }
            Thread.sleep(15)
        }
    }

    private fun makeVm(
        store: HookPolicyStore,
        coord: PolicyTxCoordinator,
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
            hookPolicyStore = store,
            currentUserId = { 0 },
            policyIoTimeoutMs = 500L,
            policyTxCoordinator = coord,
        )
        vms.add(vm)
        coords.add(coord)
        await { !vm.state.value.loading }
        if (awaitKnown) {
            await { vm.state.value.policyDraftKnown }
        }
        return vm
    }

    @Test
    fun refreshPolicyDraft_withCorruptEvidence_routesViaLoadForRepair_andSetsCorruptState() {
        var isCorrupt = true
        var liveSet = mutableSetOf<String>()
        val store = HookPolicyStore(
            loadRaw = {
                if (isCorrupt) throw ClassCastException("Corrupt prefs")
                liveSet
            },
            saveRaw = { newSet ->
                if (newSet != null) liveSet = newSet.toMutableSet()
                else liveSet.clear()
                isCorrupt = false
                true
            },
            repairProbe = {
                if (isCorrupt) LoadResult.Corrupt
                else LoadResult.Known(liveSet)
            },
        )
        val coord = PolicyTxCoordinator()
        val vm = makeVm(store, coord, awaitKnown = false)

        await { !vm.state.value.policyDraftKnown }
        assertTrue("Corrupt store on startup must mark corrupt", vm.state.value.policyDraftCorrupt)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)

        // Calling refreshPolicyDraft must route through loadForRepair and keep corrupt state
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyRefreshing }
        assertTrue("refreshPolicyDraft on corrupt store must preserve policyDraftCorrupt=true", vm.state.value.policyDraftCorrupt)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("損壞") || vm.state.value.msg.contains("Corrupt") || vm.state.value.msg.isNotEmpty())

        // Now repair the store
        isCorrupt = false
        liveSet.add("com.example.repaired")
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyRefreshing }
        await { vm.state.value.policyDraftKnown }
        assertFalse("Repaired store must clear policyDraftCorrupt", vm.state.value.policyDraftCorrupt)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.example.repaired"), vm.state.value.policyDraft)
    }

    @Test
    fun refreshPolicyDraft_whenAlreadyCorrupt_andNextReadUnavailable_retainsCorruptState() {
        var mode = 0 // 0: Corrupt, 1: Unavailable
        val store = HookPolicyStore(
            loadRaw = {
                when (mode) {
                    0 -> throw ClassCastException("Corrupt prefs")
                    else -> null // Unavailable
                }
            },
            saveRaw = { true },
            repairProbe = {
                when (mode) {
                    0 -> LoadResult.Corrupt
                    else -> LoadResult.Unavailable
                }
            },
        )
        val coord = PolicyTxCoordinator()
        val vm = makeVm(store, coord, awaitKnown = false)

        await { !vm.state.value.policyDraftKnown }
        assertTrue(vm.state.value.policyDraftCorrupt)

        // Next read is transiently Unavailable
        mode = 1
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyRefreshing }

        // Must retain policyDraftCorrupt=true, NOT reset to false!
        assertTrue("Unavailable read on previously corrupt store must retain policyDraftCorrupt=true", vm.state.value.policyDraftCorrupt)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    @Test
    fun orphanDropGen_gateInConfirmPolicyWrite_failsClosed_whenOrphanDroppedBetweenRequestAndConfirm() {
        val coord = PolicyTxCoordinator()
        val store = HookPolicyStore(
            loadRaw = { setOf("com.example.a") },
            saveRaw = { true },
        )
        val vm = makeVm(store, coord, awaitKnown = true)

        // Select an item from catalog
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        vm.requestPolicyWrite()

        val pending = vm.state.value.pendingPolicyWrite
        assertTrue("requestPolicyWrite must create pending", pending != null)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)

        // Between request and confirm, an orphan placeholder is dropped on coordinator
        val placeholder = PolicySettled(
            txId = 999L,
            key = "orphan-placeholder",
            draft = emptySet(),
            known = false,
            msgKind = MsgKind.CHANNEL,
            msg = "",
            isClear = false,
            rev = 7L,
        )
        coord.pendingPublish.set(placeholder)
        coord.pendingPublishAtNanos.set(System.nanoTime() - 5_000_000_000L)
        val dropped = runBlocking { coord.tryRecoverStalePending(System.nanoTime(), 0) }
        assertTrue(dropped != null)
        assertTrue(coord.orphanDropGen.get() > 0L)

        // Now user confirms the pending write: must fail closed via checkOrphanDropAndRevisionStaleLocked()
        vm.confirmPolicyWrite(pending!!, pending!!.generation)

        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertFalse("Draft must be marked unknown on stale orphan drop", vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    @Test
    fun markObservedKnownAt_atomicSnapshot_isConsistent() {
        val coord = PolicyTxCoordinator()
        val store = HookPolicyStore(
            loadRaw = { emptySet() },
            saveRaw = { true },
        )
        val vm = makeVm(store, coord, awaitKnown = true)

        vm.markObservedKnownAt(rev = 42L, dropGen = 7L)
        assertEquals(42L, vm.observedRevForTests())

        // Access observedSnapshot via reflection to verify atomic snapshot
        val field = DualViewModel::class.java.getDeclaredField("observedSnapshot").apply {
            isAccessible = true
        }
        val snapshotRef = field.get(vm) as java.util.concurrent.atomic.AtomicReference<*>
        val snap = snapshotRef.get() as DualViewModel.ObservedPolicySnapshot
        assertEquals(42L, snap.rev)
        assertEquals(7L, snap.dropGen)
    }

    @Test
    fun orphanDropGen_dropRevEqualsObserved_failsClosed() {
        val coord = PolicyTxCoordinator()
        val store = HookPolicyStore(
            loadRaw = { setOf("com.example.a") },
            saveRaw = { true },
        )
        val vm = makeVm(store, coord, awaitKnown = true)
        // Advance coordinator to rev 7 and mark observed at 7.
        repeat(7) { coord.bumpRevisionForTests() }
        assertEquals(7L, coord.currentRevision())
        vm.markObservedKnownAt(rev = 7L, dropGen = 0L)
        assertEquals(7L, vm.observedRevForTests())

        // Stage orphan placeholder at rev 7 and drop it (dropRev == observed).
        val placeholder = PolicySettled(
            txId = 1001L,
            key = "orphan-eq",
            draft = emptySet(),
            known = false,
            msgKind = MsgKind.CHANNEL,
            msg = "",
            isClear = false,
            rev = 7L,
        )
        coord.pendingPublish.set(placeholder)
        coord.pendingPublishAtNanos.set(System.nanoTime() - 5_000_000_000L)
        val dropped = runBlocking { coord.tryRecoverStalePending(System.nanoTime(), 0) }
        assertTrue(dropped != null)
        assertEquals(7L, coord.orphanDropRev.get())

        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        vm.requestPolicyWrite()
        // dropRev (7) >= observed (7) must fail closed: no pending, Unknown.
        assertTrue("dropRev==observed must fail closed", vm.state.value.pendingPolicyWrite == null)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    @Test
    fun orphanDropGen_olderDropRev_catchesUpSilently() {
        val coord = PolicyTxCoordinator()
        val store = HookPolicyStore(
            loadRaw = { setOf("com.example.a") },
            saveRaw = { true },
        )
        val vm = makeVm(store, coord, awaitKnown = true)
        // Coordinator at rev 10, observed at 10.
        repeat(10) { coord.bumpRevisionForTests() }
        assertEquals(10L, coord.currentRevision())
        vm.markObservedKnownAt(rev = 10L, dropGen = 0L)

        // Older orphan drop rev=5 <= observed 10: must catch up silently.
        val oldPlaceholder = PolicySettled(
            txId = 101L,
            key = "old-drop",
            draft = emptySet(),
            known = false,
            msgKind = MsgKind.CHANNEL,
            msg = "",
            isClear = false,
            rev = 5L,
        )
        coord.pendingPublish.set(oldPlaceholder)
        coord.pendingPublishAtNanos.set(System.nanoTime() - 5_000_000_000L)
        val dropped = runBlocking { coord.tryRecoverStalePending(System.nanoTime(), 0) }
        assertTrue(dropped != null)
        assertEquals(5L, coord.orphanDropRev.get())
        assertTrue(coord.orphanDropGen.get() > 0L)

        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        vm.requestPolicyWrite()
        val pending = vm.state.value.pendingPolicyWrite
        assertTrue("Older drop must catch up silently and allow pending", pending != null)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        // Silent-proof: stale drop caught up without marking stale — observed rev
        // stays 10 and the snapshot dropGen converges to the coordinator's gen.
        assertEquals(10L, vm.observedRevForTests())
        val snapField = DualViewModel::class.java.getDeclaredField("observedSnapshot").apply {
            isAccessible = true
        }
        val snapRef = snapField.get(vm) as java.util.concurrent.atomic.AtomicReference<*>
        val snapAfter = snapRef.get() as DualViewModel.ObservedPolicySnapshot
        assertEquals(coord.orphanDropGen.get(), snapAfter.dropGen)
    }

    @Test
    fun orphanDropGen_dropRevAboveObserved_failsClosed() {
        val coord = PolicyTxCoordinator()
        val store = HookPolicyStore(
            loadRaw = { setOf("com.example.a") },
            saveRaw = { true },
        )
        val vm = makeVm(store, coord, awaitKnown = true)
        // Coordinator at rev 8, observed at 7; orphan drop rev=8 (> observed).
        repeat(8) { coord.bumpRevisionForTests() }
        assertEquals(8L, coord.currentRevision())
        vm.markObservedKnownAt(rev = 7L, dropGen = 0L)
        assertEquals(7L, vm.observedRevForTests())

        val placeholder = PolicySettled(
            txId = 1002L,
            key = "orphan-above",
            draft = emptySet(),
            known = false,
            msgKind = MsgKind.CHANNEL,
            msg = "",
            isClear = false,
            rev = 8L,
        )
        coord.pendingPublish.set(placeholder)
        coord.pendingPublishAtNanos.set(System.nanoTime() - 5_000_000_000L)
        val dropped = runBlocking { coord.tryRecoverStalePending(System.nanoTime(), 0) }
        assertTrue(dropped != null)
        assertEquals(8L, coord.orphanDropRev.get())

        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        vm.requestPolicyWrite()
        // dropRev (8) >= observed (7) must fail closed: no pending, Unknown.
        assertTrue("dropRev==observed+1 must fail closed", vm.state.value.pendingPolicyWrite == null)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    @Test
    fun orphanDropGen_unknownDropRev_failsClosed() {
        val coord = PolicyTxCoordinator()
        val store = HookPolicyStore(
            loadRaw = { setOf("com.example.a") },
            saveRaw = { true },
        )
        val vm = makeVm(store, coord, awaitKnown = true)
        // Coordinator at rev 7, observed at 7, but the drop generation moved
        // without any recorded drop rev (orphanDropRev stays -1L): the
        // dropRev == -1L first disjunct (DualViewModel.kt checkOrphanDrop...)
        // must fail closed.
        repeat(7) { coord.bumpRevisionForTests() }
        assertEquals(7L, coord.currentRevision())
        vm.markObservedKnownAt(rev = 7L, dropGen = 0L)
        assertEquals(7L, vm.observedRevForTests())
        coord.orphanDropGen.incrementAndGet()
        assertEquals(-1L, coord.orphanDropRev.get())

        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        vm.requestPolicyWrite()
        // Unknown drop rev with a moved generation must fail closed.
        assertTrue("dropRev==-1 must fail closed", vm.state.value.pendingPolicyWrite == null)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }
}
