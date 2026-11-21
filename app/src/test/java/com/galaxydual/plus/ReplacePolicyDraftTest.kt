// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// ReplacePolicyDraftTest：DualViewModel.replacePolicyDraft() 單測。
// 政策編輯頁（PolicyActivity）直接提供完整的新政策候選集合，與 requestPolicyWrite()
// 走同一個 pending／CAS／確認對話框流程；本檔鎖定兩者的差異（policy 集合由參數
// 給定、不推導 catalog、不影響 selectedKeys）與共同 gates。
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReplacePolicyDraftTest {

    // 假政策 store：記錄 save/clear 呼叫，並保有可重讀的記憶體後端。
    private class FakePolicyStore(initialDraft: Set<String> = setOf("com.seed")) {
        var saved: Set<String>? = initialDraft
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store: HookPolicyStore = HookPolicyStore(
            loadRaw = { saved },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw == null) {
                    clearCalls.incrementAndGet()
                    saved = emptySet()
                    true
                } else {
                    saved = raw
                    true
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
            try {
                vm.dispose()
            } catch (_: Exception) {
                // Best effort: test process is discarded anyway.
            }
            try {
                vm.viewModelScope.cancel()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        // CI #306: drain outstanding viewModelScope/coordinator children before Main is reset.
        TestDrainSupport.drainBeforeResetMain(vms, coords)
        vms.clear()
        for (coord in coords) {
            try { coord.close() } catch (_: Exception) { }
        }
        coords.clear()
        resetRemotePoisonForTests()
        Dispatchers.resetMain()
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout waiting for condition after ${timeoutMs}ms")
            }
            Thread.sleep(20)
        }
    }

    private fun makeVm(
        policyStore: HookPolicyStore?,
        userId: Int = 0,
        initialDraft: Set<String>? = setOf("com.seed"),
        policyText: (PolicyText) -> String = { it.defaultText() },
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
            currentUserId = { userId },
            policyIoTimeoutMs = 500L,
            policyText = policyText,
        )
        vms.add(vm)
        if (policyStore != null && initialDraft != null) {
            await { vm.state.value.policyDraftKnown }
        }
        await { !vm.state.value.loading }
        return vm
    }

    private fun setPendingPolicyWrite(vm: DualViewModel, pending: PendingPolicyWrite?) {
        val field = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<UiState>
        state.value = state.value.copy(pendingPolicyWrite = pending)
    }

    private fun setPolicyWritePhase(vm: DualViewModel, phase: PolicyWritePhase) {
        val field = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<UiState>
        state.value = state.value.copy(policyWritePhase = phase)
    }

    // (a) RECONCILING 中拒絕（BUSY），不建 pending、不寫 store。
    @Test
    fun replacePolicyDraft_reconcilingRejectsBusyWithoutPendingOrStoreWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store)
        setPolicyWritePhase(vm, PolicyWritePhase.RECONCILING)

        vm.replacePolicyDraft(setOf("com.example.app"))

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(0, fake.saveCalls.get())
        assertEquals(0, fake.clearCalls.get())
    }

    // (b) 已有 pending 時拒絕（BUSY），保持既有 pending 不被 supersede。
    @Test
    fun replacePolicyDraft_pendingKeepsExistingPendingAndReportsBusy() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store)
        val pending = PendingPolicyWrite(setOf("com.example.kept"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.replacePolicyDraft(setOf("com.example.intruder"))

        assertSame(
            "活躍 QUEUED pending 不得被 supersede",
            pending,
            vm.state.value.pendingPolicyWrite,
        )
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(0, fake.saveCalls.get())
    }

    // (c) 政策草稿未知（policyDraftKnown=false）時拒絕（fail-closed），不寫 store。
    @Test
    fun replacePolicyDraft_policyDraftUnknownRejectsWithoutPendingOrStoreWrite() {
        // loadRaw 恆回傳 null＝讀取失敗＝Unknown（不視為確認空）。
        val store = HookPolicyStore(
            loadRaw = { null },
            saveRaw = { _ -> true },
        )
        val vm = makeVm(store, initialDraft = null)
        assertEquals(false, vm.state.value.policyDraftKnown)

        vm.replacePolicyDraft(setOf("com.example.app"))

        assertNull(vm.state.value.pendingPolicyWrite)
        // Stage L4：Corrupt/Unavailable 文案分離，皆為 CHANNEL；保守鎖定訊息族。
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.isNotEmpty())
    }

    // (d) 成功：pending 安裝成功、packages == sanitized 輸入、selectedKeys 不被觸碰。
    @Test
    fun replacePolicyDraft_successInstallsPendingWithSanitizedInputAndLeavesSelectionUntouched() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        val selectionBefore = vm.state.value.selectedKeys

        vm.replacePolicyDraft(linkedSetOf("com.example.a", "com.example.b", "bad token!"))

        assertEquals(selectionBefore, vm.state.value.selectedKeys)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        assertEquals(setOf("com.example.a", "com.example.b"), pending!!.packages)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        assertEquals(0, fake.saveCalls.get())
    }

    // (e) sanitize 丟棄非法 token 並計數 dropped（message 走 DROPPED 路徑，count 正確）。
    @Test
    fun replacePolicyDraft_sanitizeDropsInvalidAndReportsDroppedCount() {
        val fake = FakePolicyStore()
        val vm = makeVm(
            fake.store,
            policyText = { pt -> "INJECTED:${pt.kind.name}:${pt.count}" },
        )

        vm.replacePolicyDraft(setOf("com.ok.app", "bad pkg", "semi;colon", "slash/x"))

        assertEquals("INJECTED:DROPPED:3", vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(setOf("com.ok.app"), vm.state.value.pendingPolicyWrite?.packages)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        assertEquals(0, fake.saveCalls.get())
    }
}
