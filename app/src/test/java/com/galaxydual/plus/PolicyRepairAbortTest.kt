// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
// Phase B P2: repair revalidation abort retention (pre + final loadForRepair
// Unavailable must retain Corrupt, never wash to false).
package com.galaxydual.plus

import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicyRepairAbortTest {
    private val vms = mutableListOf<DualViewModel>()
    private val coords = mutableListOf<PolicyTxCoordinator>()

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
        for (c in coords) {
            try { c.close() } catch (_: Exception) { }
        }
        coords.clear()
        Dispatchers.resetMain()
        resetRemotePoisonForTests()
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout waiting for condition after ${timeoutMs}ms")
            }
            Thread.sleep(15)
        }
    }

    private fun makeCorruptVm(
        store: HookPolicyStore,
        coord: PolicyTxCoordinator,
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
        await(timeoutMs = 5_000L) { vm.state.value.policyDraftCorrupt }
        assertFalse(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.policyDraftCorrupt)
        return vm
    }

    private val refreshFailedText: String
        get() = PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
    private val repairFirstText: String
        get() = PolicyText(PolicyTextKind.CORRUPT_REPAIR_FIRST).defaultText()
    private val refreshedText: String
        get() = PolicyText(PolicyTextKind.REFRESHED).defaultText()

    @Test
    fun preRepairUnavailable_retainsCorrupt_noDelete_routesToRepairRequest() {
        val corrupt = AtomicBoolean(true)
        val backend = AtomicReference<Set<String>?>(null)
        val deleteCalls = AtomicInteger(0)
        val saveCalls = AtomicInteger(0)
        val probeCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = {
                if (corrupt.get()) throw ClassCastException("corrupt prefs")
                backend.get()
            },
            saveRaw = { raw ->
                if (raw == null) deleteCalls.incrementAndGet() else saveCalls.incrementAndGet()
                if (raw == null) {
                    // Must never be reached in this test (abort before delete).
                    corrupt.set(false)
                    backend.set(emptySet())
                } else {
                    backend.set(raw)
                }
                true
            },
            repairProbe = {
                probeCalls.incrementAndGet()
                LoadResult.Unavailable
            },
        )
        val coord = PolicyTxCoordinator()
        val vm = makeCorruptVm(store, coord)
        val versionBefore = vm.policyDraftVersionForTests()

        // First tap arms repair.
        vm.requestRepairCorruptDraft()
        assertTrue(vm.isCorruptRepairArmed())
        assertEquals(repairFirstText, vm.state.value.msg)

        // Second tap executes repair; pre-read is transiently Unavailable.
        vm.confirmRepairCorruptDraft()
        await(timeoutMs = 5_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                vm.state.value.msg == refreshFailedText
        }

        // Zero repair/delete backend calls: abort happens before store.repairCorrupt().
        assertEquals(0, deleteCalls.get())
        assertEquals(0, saveCalls.get())
        assertEquals(1, probeCalls.get())
        // Terminal: IDLE + Unknown + Corrupt retained + REFRESH_FAILED/CHANNEL.
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertFalse(vm.state.value.policyDraftKnown)
        assertTrue("Unavailable must retain Corrupt=true", vm.state.value.policyDraftCorrupt)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(refreshFailedText, vm.state.value.msg)
        assertFalse(vm.state.value.loading)
        // Version/ownership discipline: fail-closed fence must bump generations.
        assertTrue(
            "Unavailable abort must bump generations",
            vm.policyDraftVersionForTests() > versionBefore,
        )
        // Repair was disarmed before IO; retained corrupt + disarmed => request flow.
        assertFalse(vm.isCorruptRepairArmed())
        assertEquals(
            ClearPolicyButtonRoute.REPAIR_REQUEST,
            clearPolicyButtonRoute(
                vm.state.value.policyDraftKnown,
                vm.state.value.policyDraftCorrupt,
                vm.isCorruptRepairArmed(),
            ),
        )
        // End-to-end route: next tap re-arms via repair request (no blind clear).
        val deletesBeforeTap = deleteCalls.get()
        vm.onClearPolicyButtonTap()
        assertTrue(vm.isCorruptRepairArmed())
        assertEquals(repairFirstText, vm.state.value.msg)
        assertEquals(deletesBeforeTap, deleteCalls.get())
        assertFalse(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.policyDraftCorrupt)
    }

    @Test
    fun finalRepairUnavailable_retainsCorrupt_noDelete_routesToRepairRequest() {
        val corrupt = AtomicBoolean(true)
        val backend = AtomicReference<Set<String>?>(null)
        val deleteCalls = AtomicInteger(0)
        val saveCalls = AtomicInteger(0)
        val probeCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = {
                if (corrupt.get()) throw ClassCastException("corrupt prefs")
                backend.get()
            },
            saveRaw = { raw ->
                if (raw == null) deleteCalls.incrementAndGet() else saveCalls.incrementAndGet()
                if (raw == null) {
                    corrupt.set(false)
                    backend.set(emptySet())
                } else {
                    backend.set(raw)
                }
                true
            },
            repairProbe = {
                val n = probeCalls.incrementAndGet()
                if (n == 1) LoadResult.Corrupt
                else LoadResult.Unavailable
            },
        )
        val coord = PolicyTxCoordinator()
        val vm = makeCorruptVm(store, coord)
        val versionBefore = vm.policyDraftVersionForTests()

        vm.requestRepairCorruptDraft()
        assertTrue(vm.isCorruptRepairArmed())
        vm.confirmRepairCorruptDraft()
        // Pre passes (Corrupt), final is Unavailable => abort before delete.
        await(timeoutMs = 5_000L) {
            probeCalls.get() >= 2 &&
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                vm.state.value.msg == refreshFailedText
        }

        assertEquals(2, probeCalls.get())
        assertEquals(0, deleteCalls.get())
        assertEquals(0, saveCalls.get())
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertFalse(vm.state.value.policyDraftKnown)
        assertTrue("Final Unavailable must retain Corrupt=true", vm.state.value.policyDraftCorrupt)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(refreshFailedText, vm.state.value.msg)
        assertFalse(vm.state.value.loading)
        assertTrue(
            "Final Unavailable abort must bump generations",
            vm.policyDraftVersionForTests() > versionBefore,
        )
        assertFalse(vm.isCorruptRepairArmed())
        assertEquals(
            ClearPolicyButtonRoute.REPAIR_REQUEST,
            clearPolicyButtonRoute(
                vm.state.value.policyDraftKnown,
                vm.state.value.policyDraftCorrupt,
                vm.isCorruptRepairArmed(),
            ),
        )
        val deletesBeforeTap = deleteCalls.get()
        vm.onClearPolicyButtonTap()
        assertTrue(vm.isCorruptRepairArmed())
        assertEquals(repairFirstText, vm.state.value.msg)
        assertEquals(deletesBeforeTap, deleteCalls.get())
    }

    @Test
    fun preRepairKnown_clearsCorrupt_staysUnknownUntilRefresh() {
        val corrupt = AtomicBoolean(true)
        val backend = AtomicReference<Set<String>?>(null)
        val deleteCalls = AtomicInteger(0)
        val probeCalls = AtomicInteger(0)
        val healed = setOf("com.healed")
        val store = HookPolicyStore(
            loadRaw = {
                if (corrupt.get()) throw ClassCastException("corrupt prefs")
                backend.get()
            },
            saveRaw = { raw ->
                if (raw == null) deleteCalls.incrementAndGet()
                if (raw == null) {
                    corrupt.set(false)
                    backend.set(emptySet())
                }
                true
            },
            repairProbe = {
                probeCalls.incrementAndGet()
                LoadResult.Known(healed)
            },
        )
        val coord = PolicyTxCoordinator()
        val vm = makeCorruptVm(store, coord)

        vm.requestRepairCorruptDraft()
        assertTrue(vm.isCorruptRepairArmed())
        vm.confirmRepairCorruptDraft()
        // Verified Known may clear corrupt (fail-closed): abort stays Unknown
        // with Corrupt=false, zero deletes; explicit refresh adopts the value.
        await(timeoutMs = 5_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                vm.state.value.msg == PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText()
        }

        assertEquals(0, deleteCalls.get())
        assertEquals(1, probeCalls.get())
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertFalse(vm.state.value.policyDraftKnown)
        assertFalse("Verified Known may clear Corrupt", vm.state.value.policyDraftCorrupt)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
    }

    @Test
    fun finalRepairKnown_clearsCorrupt_staysUnknownUntilRefresh() {
        val corrupt = AtomicBoolean(true)
        val backend = AtomicReference<Set<String>?>(null)
        val deleteCalls = AtomicInteger(0)
        val probeCalls = AtomicInteger(0)
        val healed = setOf("com.healed2")
        val store = HookPolicyStore(
            loadRaw = {
                if (corrupt.get()) throw ClassCastException("corrupt prefs")
                backend.get()
            },
            saveRaw = { raw ->
                if (raw == null) deleteCalls.incrementAndGet()
                if (raw == null) {
                    corrupt.set(false)
                    backend.set(emptySet())
                }
                true
            },
            repairProbe = {
                val n = probeCalls.incrementAndGet()
                if (n == 1) LoadResult.Corrupt
                else LoadResult.Known(healed)
            },
        )
        val coord = PolicyTxCoordinator()
        val vm = makeCorruptVm(store, coord)

        vm.requestRepairCorruptDraft()
        vm.confirmRepairCorruptDraft()
        await(timeoutMs = 5_000L) {
            probeCalls.get() >= 2 &&
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                vm.state.value.msg == PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText()
        }

        assertEquals(2, probeCalls.get())
        assertEquals(0, deleteCalls.get())
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertFalse(vm.state.value.policyDraftKnown)
        assertFalse("Verified Known may clear Corrupt", vm.state.value.policyDraftCorrupt)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
    }

    @Test
    fun subsequentKnown_afterUnavailableRetention_recoversViaRefresh() {
        val corrupt = AtomicBoolean(true)
        val backend = AtomicReference<Set<String>?>(null)
        val deleteCalls = AtomicInteger(0)
        val mode = AtomicReference<String>("unavailable")
        val probeCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = {
                if (corrupt.get()) throw ClassCastException("corrupt prefs")
                backend.get()
            },
            saveRaw = { raw ->
                if (raw == null) deleteCalls.incrementAndGet()
                if (raw == null) {
                    corrupt.set(false)
                    backend.set(emptySet())
                } else {
                    backend.set(raw)
                }
                true
            },
            repairProbe = {
                probeCalls.incrementAndGet()
                when (mode.get()) {
                    "known" -> LoadResult.Known(setOf("com.recovered"))
                    else -> LoadResult.Unavailable
                }
            },
        )
        // Remote evidence probe: refresh retention needs hasCorruptEvidence()=true
        // while corrupt, so refresh routes via loadForRepair (not loadResult).
        store.setCorruptEvidenceProbe { corrupt.get() }
        val coord = PolicyTxCoordinator()
        val vm = makeCorruptVm(store, coord)

        // Step 1: repair pre-read Unavailable => retention.
        vm.requestRepairCorruptDraft()
        vm.confirmRepairCorruptDraft()
        await(timeoutMs = 5_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                vm.state.value.msg == refreshFailedText
        }
        assertTrue(vm.state.value.policyDraftCorrupt)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(0, deleteCalls.get())

        // Step 2: genuine subsequent verified Known (via refresh) recovers.
        mode.set("known")
        corrupt.set(false)
        backend.set(setOf("com.recovered"))
        vm.refreshPolicyDraft()
        await(timeoutMs = 5_000L) { vm.state.value.policyDraftKnown }

        assertTrue(vm.state.value.policyDraftKnown)
        assertFalse(vm.state.value.policyDraftCorrupt)
        assertEquals(setOf("com.recovered"), vm.state.value.policyDraft)
        assertEquals(0, deleteCalls.get())
    }
}
