// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase C (issue #3/#4/#5)：政策覆寫 delta 警告＋修訂失配作廢＋Unknown 重讀＋編輯器空集導引。
// JVM 可測，不碰裝置：純函數＋VM 綁定門控＋權威重讀。
package com.galaxydual.plus

import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyPhaseCTest {

    private class FakePolicyStore(initialDraft: Set<String> = setOf("com.a.app", "com.b.app")) {
        var saved: Set<String>? = initialDraft
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)
        var saveSucceeds = true
        val store: HookPolicyStore = HookPolicyStore(
            loadRaw = { loadCalls.incrementAndGet(); saved },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw == null) {
                    clearCalls.incrementAndGet()
                    saved = emptySet()
                    true
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
                throw AssertionError("timeout waiting for condition after ${timeoutMs}ms state=${try { vms.lastOrNull()?.state?.value } catch (_: Exception) { "?" }}")
            }
            Thread.sleep(20)
        }
    }

    private fun makeVm(
        policyStore: HookPolicyStore?,
        userId: Int = 0,
        initialDraft: Set<String>? = setOf("com.a.app", "com.b.app"),
        coordinator: PolicyTxCoordinator? = null,
    ): DualViewModel {
        if (coordinator != null && !coords.contains(coordinator)) coords.add(coordinator)
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
            // R4-4: legacy no-consent callers; production keeps the fail-safe default.
            requirePolicyWriteConsent = false,
            currentUserId = { userId },
            policyIoTimeoutMs = 500L,
            policyTxCoordinator = coordinator,
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

    // {A,B}->{B} delta 警告：old-minus-candidate = {A}，installed 非空即警告。
    @Test
    fun delta_ab_to_b_yieldsSingleRemovalAndWarnsWhenInstalled() {
        val old = setOf("com.a.app", "com.b.app")
        val candidate = setOf("com.b.app")
        val delta = policyRemovalDelta(old, candidate)
        assertEquals(setOf("com.a.app"), delta)
        // delta installed 非空→警告。
        assertTrue(shouldWarnForDelta(listOf("com.a.app")))
        // Ok(empty)→不警告直接放行。
        assertFalse(shouldWarnForDelta(emptyList()))
        // delta 空（無移除）→不警告。
        val noRemoval = policyRemovalDelta(old, setOf("com.a.app", "com.b.app", "com.c.app"))
        assertTrue(noRemoval.isEmpty())
        assertFalse(shouldWarnForDelta(emptyList()))
    }

    // 修訂失配作廢：綁定快照後修訂推進，舊綁定即失配，request 作廢要求重探。
    @Test
    fun warningBinding_staleAfterRevisionAdvance_voidsRequestRequiringRefresh() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store)
        // 快照當下綁定（新鮮）。
        val fresh = vm.currentWarningBinding()
        assertTrue(vm.isWarningBindingFresh(fresh))
        // 純函數對照：全等即新鮮。
        assertTrue(isWarningBindingFreshPure(fresh.rev, fresh.draftVersion, fresh.freshGen, fresh))
        val staleLike = fresh.copy(rev = fresh.rev + 1)
        // coordinator==null legacy 路徑 rev 恆 -1，此處 rev+1 必失配（除非皆 -1？此測 VM 無 coordinator，
        // observed -1，fresh.rev=-1，staleLike.rev=0→失配，符合預期）。
        // 若環境 rev 非 -1（coordinator 傳入），同樣失配。
        assertFalse(isWarningBindingFreshPure(fresh.rev, fresh.draftVersion, fresh.freshGen, staleLike))

        // 以新鮮綁定建 pending 成功。
        vm.replacePolicyDraft(setOf("com.b.app"), fresh)
        assertNotNull(vm.state.value.pendingPolicyWrite)
        val pending = vm.state.value.pendingPolicyWrite!!
        // confirm 成功會 bump version/freshGen（修訂推進）。
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(setOf("com.b.app"), vm.state.value.policyDraft)

        // 舊綁定已失配（version/freshGen 已推進），再以舊綁定請求即作廢。
        vm.replacePolicyDraft(setOf("com.a.app"), fresh)
        // 作廢：無新 pending（舊 pending 已結清，此處應仍無 pending），msg 為 UNKNOWN_REFRESH。
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    // Unknown 重讀：Known 快照後遠端變 Unknown，confirm 提交前權威重讀即 abort，不碰 save。
    @Test
    fun confirm_authoritativeRereadUnknown_abortsWithoutSave() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        val vm = makeVm(fake.store)
        assertTrue(vm.state.value.policyDraftKnown)
        val binding = vm.currentWarningBinding()
        // 建 pending（N→M 覆寫，候選 {com.b.app}，delta={com.a.app}）。
        vm.replacePolicyDraft(setOf("com.b.app"), binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        // 在 confirm 提交前把遠端翻為 Unknown（Unavailable）。
        fake.saved = null
        val savesBefore = fake.saveCalls.get()
        vm.confirmPolicyWrite(pending!!, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        // 權威重讀 abort：未調 save，不寫入。
        assertEquals(savesBefore, fake.saveCalls.get())
        assertEquals(false, vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        // 草稿維持舊值（未被 Unknown 蓋回），msg 為 UNKNOWN_REFRESH 或 CORRUPT（此處 Unavailable→UNKNOWN_REFRESH）。
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
    }

    // 編輯器空集導引：空集即導引，不警告；非空不導引。
    @Test
    fun editorEmpty_shouldGuideWhenTicksEmpty() {
        assertTrue(editorEmptyShouldGuide(true))
        assertFalse(editorEmptyShouldGuide(false))
        // 空集 delta 即 old 全量，但編輯器 A 案禁清到底：導引優先於警告。
        // 此斷言語義：ticks 空即導引，呼叫端不得再走 probe/警告路徑。
        val ticksEmpty = emptySet<String>()
        assertTrue(editorEmptyShouldGuide(ticksEmpty.isEmpty()))
        val ticksNonEmpty = setOf("com.b.app")
        assertFalse(editorEmptyShouldGuide(ticksNonEmpty.isEmpty()))
    }

    // 清除綁定失配作廢：快照後修訂推進，舊綁定清除即作廢。
    @Test
    fun clear_staleBinding_voidsRequiringRefresh() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app"))
        val vm = makeVm(fake.store)
        val fresh = vm.currentWarningBinding()
        // 先以新鮮綁定做一次覆寫使修訂推進。
        vm.replacePolicyDraft(setOf("com.b.app"), fresh)
        val p = vm.state.value.pendingPolicyWrite!!
        vm.confirmPolicyWrite(p, p.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        // 舊綁定已失配，清除即作廢（UNKNOWN_REFRESH，不碰 clear）。
        val clearsBefore = fake.clearCalls.get()
        vm.clearPolicyDraft(warningBinding = fresh)
        assertEquals(clearsBefore, fake.clearCalls.get())
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
    }
}
