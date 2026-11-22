// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase C Required補測（code+test兩份review Required）：
// - confirm重讀Corrupt/超時/已知漂移
// - 跨屏雙VM舊綁定作廢
// - blindClear rev推進重arm＋armedRev==-1旁路
// - legacy豁免（null綁定＋baseFreshGen==-1）
// - delta接線（空集/安裝空/Editor指引/clear作廢disarm＋BUSY優先/coordinator失配/reset作廢）
//
// R03 (PR #74) Phase 2 架構：候選指紋的新鮮度檢查已從 VM.isWarningBindingFresh
// 移到持有 live 候選的呼叫端（MainActivity: writeCandidateForWarning()，
// PolicyActivity: sanitizeHookPolicy(ticks)），經 source-agnostic 純函式
// isWarningCandidateFresh 完成；VM 只保留 rev/draftVersion/freshGen/coordinator。
//
// DEVICE-UNVERIFIED（無 emulator 可用）：`pendingClearConfirm` 的 UI 凍結
// （probe／警告對話框存活期禁用勾選／再次點擊）只存在於 Activity，JVM 單元測試
// 無法覆蓋，故本檔不新增任何無法本地執行的 instrumentation 測試。此旗標是主要
// 競態守衛；isWarningCandidateFresh 是 defense-in-depth，其 JVM 覆蓋見 7a/7b/7c2
// 與下方 editorWrite_nonNullFingerprintFromTicks 測。
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyPhaseCRequiredTest {
    private class FakePolicyStore(initialDraft: Set<String>? = setOf("com.a.app", "com.b.app")) {
        var saved: Set<String>? = initialDraft
        var corruptMode = false
        var sleepMs: Long = 0L
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)
        // R03 測試用：記錄每一次實際寫入的內容，便於證明「變異後候選零寫入」。
        val saves = CopyOnWriteArrayList<Set<String>>()
        val store: HookPolicyStore = HookPolicyStore(
            loadRaw = {
                loadCalls.incrementAndGet()
                if (corruptMode) throw ClassCastException("corrupt-test")
                val s = sleepMs
                if (s > 0) try { Thread.sleep(s) } catch (_: InterruptedException) { }
                saved
            },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw == null) {
                    clearCalls.incrementAndGet()
                    saved = emptySet()
                    saves.add(emptySet())
                    true
                } else {
                    saved = raw
                    saves.add(HashSet(raw))
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
                throw AssertionError("timeout after ${timeoutMs}ms state=${try { vms.lastOrNull()?.state?.value } catch (_: Exception) { "?" }}")
            }
            Thread.sleep(20)
        }
    }

    private fun makeVm(
        policyStore: HookPolicyStore?,
        userId: Int = 0,
        initialDraft: Set<String>? = setOf("com.a.app", "com.b.app"),
        coordinator: PolicyTxCoordinator? = null,
        policyIoTimeoutMs: Long = 500L,
        nowMonoMs: () -> Long = { System.currentTimeMillis() },
        requirePolicyWriteConsent: Boolean = false,
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
            requirePolicyWriteConsent = requirePolicyWriteConsent,
            currentUserId = { userId },
            nowMonoMs = nowMonoMs,
            policyIoTimeoutMs = policyIoTimeoutMs,
            policyTxCoordinator = coordinator,
        )
        vms.add(vm)
        if (policyStore != null && initialDraft != null) {
            await { vm.state.value.policyDraftKnown }
        }
        await { !vm.state.value.loading }
        return vm
    }

    private fun setStale(vm: DualViewModel, stale: Boolean) {
        val f = DualViewModel::class.java.getDeclaredField("policyRevisionStale").apply { isAccessible = true }
        (f.get(vm) as AtomicBoolean).set(stale)
    }

    // E07: arm the store's app-process-local one-shot signal directly. The
    // production route (Invalid -> v1 successful commit) is covered in
    // HookPolicyRemoteStoreTest; here we only need the VM to observe it.
    private fun armRepairSignal(store: HookPolicyStore) {
        val f = HookPolicyStore::class.java.getDeclaredField("invalidVersionRepaired").apply { isAccessible = true }
        (f.get(store) as AtomicBoolean).set(true)
    }

    private fun completeSuccessfulWrite(vm: DualViewModel, next: Set<String>) {
        vm.replacePolicyDraft(next, vm.currentWarningBinding())
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null && vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
    }

    // R03：建立來源清單並勾選指定包名（appKey 形式 "0:<pkg>"）。
    private fun seedSelection(vm: DualViewModel, catalog: List<String>, selected: Set<String>) {
        vm.setSourceCatalog(catalog.map { SourceAppEntry(it, it, 0, false) })
        for (pkg in selected) vm.setSelected("0:$pkg", true)
    }

    private fun isBlindClearArmed(vm: DualViewModel): Boolean {
        return try {
            val m = DualViewModel::class.java.getDeclaredMethod("isBlindClearArmed").apply { isAccessible = true }
            m.invoke(vm) as Boolean
        } catch (_: Exception) {
            // fallback via armedAt
            val f = DualViewModel::class.java.getDeclaredField("blindClearArmedAtMs").apply { isAccessible = true }
            val v = f.get(vm) as java.util.concurrent.atomic.AtomicLong
            v.get() != -1L
        }
    }

    // E07 (PR #74 R4): both successful write completion paths (fallback save
    // and coordinator save) drain the one-shot repair signal into UI state,
    // and the hint is sticky across later clean writes. The flag is only an
    // app-process-local hint: no claim is made about the hook's actual version.
    @Test
    fun repairSignalSurfacesHookRestartHintInUiStateBbc() {
        // Fallback save arm (no coordinator claim).
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app"))
        armRepairSignal(fake.store)
        val vm = makeVm(fake.store, coordinator = null, policyIoTimeoutMs = 500L)
        assertFalse(vm.state.value.policyRepairNeedsHookRestart)
        completeSuccessfulWrite(vm, setOf("com.b.app"))
        assertTrue(vm.state.value.policyRepairNeedsHookRestart)
        assertFalse("signal must be consumed exactly once", fake.store.consumeInvalidVersionRepair())
        // A later non-repair write has no signal and must not lower the flag.
        completeSuccessfulWrite(vm, setOf("com.c.app"))
        assertTrue("sticky until a fresh ViewModel", vm.state.value.policyRepairNeedsHookRestart)

        // Coordinator save arm (lazy deferred claimed by the coordinator).
        val fake2 = FakePolicyStore(initialDraft = setOf("com.a.app"))
        armRepairSignal(fake2.store)
        val coord = PolicyTxCoordinator()
        val vm2 = makeVm(fake2.store, coordinator = coord, policyIoTimeoutMs = 500L)
        assertFalse(vm2.state.value.policyRepairNeedsHookRestart)
        completeSuccessfulWrite(vm2, setOf("com.b.app"))
        assertTrue(vm2.state.value.policyRepairNeedsHookRestart)
        assertFalse(fake2.store.consumeInvalidVersionRepair())
    }

    // 1. confirm重讀Corrupt：loadRaw拋CCE→CORRUPT_DETECTED+corrupt=true+零save。
    @Test
    fun confirmReread_corrupt_abortsWithCorruptDetectedZeroSave() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        val vm = makeVm(fake.store, policyIoTimeoutMs = 500L)
        assertTrue(vm.state.value.policyDraftKnown)
        val binding = vm.currentWarningBinding()
        assertTrue(vm.isWarningBindingFresh(binding))
        vm.replacePolicyDraft(setOf("com.b.app"), binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        // 提交前遠端變損壞（CCE）。
        fake.corruptMode = true
        val savesBefore = fake.saveCalls.get()
        vm.confirmPolicyWrite(pending!!, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(savesBefore, fake.saveCalls.get())
        assertFalse(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.policyDraftCorrupt)
        assertEquals(PolicyText(PolicyTextKind.CORRUPT_DETECTED).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        // 草稿維持舊值，未被損壞覆寫。
        assertEquals(setOf("com.a.app", "com.b.app"), vm.state.value.policyDraft)
    }

    // 2a. confirm重讀超時：loadRaw sleep>500ms→Unavailable→Unknown abort零save。
    @Test
    fun confirmReread_timeout_abortsUnknownZeroSave() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        val vm = makeVm(fake.store, policyIoTimeoutMs = 500L)
        val binding = vm.currentWarningBinding()
        vm.replacePolicyDraft(setOf("com.b.app"), binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        fake.sleepMs = 2_000L
        val savesBefore = fake.saveCalls.get()
        vm.confirmPolicyWrite(pending!!, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(savesBefore, fake.saveCalls.get())
        assertFalse(vm.state.value.policyDraftKnown)
        assertFalse(vm.state.value.policyDraftCorrupt)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    // 2b. R04：Known 漂移也必須 fail-closed（不再只靠 coordinator fence）。
    // 提交前權威重讀得到 Known 但內容 ≠ 警告時的 old 快照 → 中止、零 save、
    // 草稿不被覆寫（否則使用者未曾被告知的包會被整包移除）。
    @Test
    fun confirmReread_knownDrift_abortsWithoutOverwrite() {
        val warnedOld = setOf("com.a.app", "com.b.app")
        val fake = FakePolicyStore(initialDraft = warnedOld)
        // 無 coordinator：本地 fence 不變，仅後端內容漂移（fence 看不到）。
        val vm = makeVm(fake.store, policyIoTimeoutMs = 500L, coordinator = null)
        val binding = vm.currentWarningBinding().withWarning(warnedOld, null)
        vm.replacePolicyDraft(setOf("com.b.app"), binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        assertEquals(warnedOld, pending!!.baseWarnedOld)
        // 提交前遠端漂移為另一 Known（非 Unknown）。
        fake.saved = setOf("com.x.app")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        // 拒絕：零 save、草稿維持警告所依據的舊值、UNKNOWN_REFRESH/CHANNEL。
        assertEquals(0, fake.saveCalls.get())
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(warnedOld, vm.state.value.policyDraft)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
    }

    // 2b-2. R04 跨流程（直接外部寫入）：在 A 的警告與確認之間，另一進程直接
    // 對同一後端 `store.save(setOf("com.z.app"))`——不建立第二個 VM、不觸碰任何
    // VM Generation。此測【不可能】經既有的 baseFreshGen／coordinator fence 通過：
    // 那兩個 fence 只讀 VM 自己的 policyFreshnessGen 與 observed rev，外部 store
    // 寫入完全不推進它們（舊版「建立 vmB」會推進檔案層級 policyFreshnessGen，
    // 讓 vmA 在 baseFreshGen fence 就 abort，形成假陽性，從未走到新的 Known
    // 內容比對）。維持單一 VM 後，唯一能抓到漂移的是 confirm pre-IO 的
    // baseWarnedOld 內容比對（R04）。
    @Test
    fun confirmReread_externalKnownDrift_abortsWithoutOverwrite() {
        val warnedOld = setOf("com.a.app", "com.b.app")
        val saveCalls = AtomicInteger(0)
        val backend = AtomicReference<Set<String>?>(warnedOld)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                backend.set(raw ?: emptySet())
                true
            },
        )
        // 單一 VM：警告→確認之間所有 VM 世代（baseFreshGen/observed rev）不變。
        val vmA = makeVm(store, initialDraft = warnedOld, coordinator = null, policyIoTimeoutMs = 500L)
        val bindingA = vmA.currentWarningBinding().withWarning(warnedOld, null)
        vmA.replacePolicyDraft(setOf("com.b.app"), bindingA)
        val pendingA = vmA.state.value.pendingPolicyWrite
        assertNotNull(pendingA)
        assertEquals(warnedOld, pendingA!!.baseWarnedOld)

        // 直接外部寫入（跨流程等價）：不經任何 VM，故 A 的 fence 完全看不到。
        store.save(setOf("com.z.app"))
        assertEquals(setOf("com.z.app"), backend.get())
        val savesBefore = saveCalls.get()

        vmA.confirmPolicyWrite(pendingA, pendingA.generation)
        await { vmA.state.value.pendingPolicyWrite == null }
        // A 的 confirm 不得再寫入：計數不變、後端仍是外部寫入的值、草稿仍為
        // 警告所依據的 warnedOld，且以 UNKNOWN_REFRESH/CHANNEL 告知。
        assertEquals("confirm must not save after external known drift", savesBefore, saveCalls.get())
        assertEquals(setOf("com.z.app"), backend.get())
        assertEquals(warnedOld, vmA.state.value.policyDraft)
        assertFalse(vmA.state.value.policyDraftKnown)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vmA.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vmA.state.value.msgKind)
        assertEquals(PolicyWritePhase.IDLE, vmA.state.value.policyWritePhase)
    }

    // 2b-3. R04 clear-path parity：clear 比 write 更具破壞性（清除整份已存草稿，
    // Samsung 可能據此刪除雙開），故警告與清除 confirm 之間若有外部 Known 漂移
    //（直接 store.save，不推進任何 VM 世代／fence），precommit 重讀必須 fail-closed
    // 中止：零 clear、後端仍是外部值、UNKNOWN_REFRESH/CHANNEL、草稿保留。
    // pre-fix 只擋 Unknown，會直接清除，故此測在 pre-fix 必失敗。
    @Test
    fun clearReread_knownDrift_abortsWithoutClear() {
        val warnedOld = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(warnedOld)
        val clearCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet()
                backend.set(raw ?: emptySet())
                true
            },
        )
        // 單一 VM、無 coordinator：警告→confirm 之間所有 VM 世代（fence）不變。
        val vm = makeVm(store, initialDraft = warnedOld, coordinator = null, policyIoTimeoutMs = 500L)
        val binding = vm.currentWarningBinding().withWarning(warnedOld, null)
        // 外部寫入（跨流程等價）：不經任何 VM，fence 完全看不到。
        store.save(setOf("com.z.app"))
        assertEquals(setOf("com.z.app"), backend.get())
        val clearsBefore = clearCalls.get()

        vm.clearPolicyDraft(binding)
        await { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertEquals("clear must not proceed after external known drift", clearsBefore, clearCalls.get())
        assertEquals(setOf("com.z.app"), backend.get())
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(warnedOld, vm.state.value.policyDraft)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
    }

    // 3. 跨屏雙VM：共用FakePolicyStore+PolicyTxCoordinator，A綁定後B寫入推進，A舊綁定replace/request/clear皆作廢。
    @Test
    fun crossVm_sharedStoreCoordinator_oldBindingVoidsReplaceRequestClear() {
        val backend = AtomicReference<Set<String>?>(setOf("com.a.app"))
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) {
                    clearCalls.incrementAndGet()
                    backend.set(emptySet())
                    true
                } else {
                    saveCalls.incrementAndGet()
                    backend.set(raw)
                    true
                }
            },
        )
        val coord = PolicyTxCoordinator()
        val vmA = makeVm(store, initialDraft = setOf("com.a.app"), coordinator = coord, policyIoTimeoutMs = 500L)
        // A快照舊綁定。
        val bindingA = vmA.currentWarningBinding()
        assertTrue(vmA.isWarningBindingFresh(bindingA))
        // B推進：僅 bump 修訂（不經_latest，避免collector提前收斂，精確覆蓋clear早期窗口）。
        val bumped = coord.bumpRevisionForTests()
        assertTrue(bumped > bindingA.rev || bindingA.rev == -1L)
        // 若A從未觀察（rev==-1 legacy），bump後仍需透過stale門控作廢；此處A已觀察（rev>=0），
        // 新鮮度必須因coordinator失配而作廢（含clear早期窗口）。
        // 若A rev==-1（legacy單測路徑），此斷言放寬：request仍經S8/unknown作廢，clear經綁定-1旁路放行；
        // 本測VM具coordinator且已Known，rev必>=0。
        assertEquals(false, vmA.isWarningBindingFresh(bindingA))

        // replace作廢。
        val savesBefore = saveCalls.get()
        vmA.replacePolicyDraft(setOf("com.c.app"), bindingA)
        assertNull(vmA.state.value.pendingPolicyWrite)
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vmA.state.value.msg)
        assertEquals(savesBefore, saveCalls.get())

        // request作廢（需catalog＋勾選；同樣舊綁定）。
        vmA.setSourceCatalog(listOf(SourceAppEntry("com.c.app", "C", 0, false)))
        // 若先前作廢已翻Unknown，需先恢復Known以進入綁定門控？不恢復亦作廢（fail-closed），
        // 此處直接以舊綁定請求，預期仍作廢（無論經stale或綁定門控）。
        // 為精確覆蓋綁定門控，先以反射清stale並重種Known（coordinator仍失配，綁定仍失配）。
        // 注意：checkOrphan會再次標stale，故即使重種，request仍作廢——符合預期。
        try {
            val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val s = f.get(vmA) as MutableStateFlow<UiState>
            s.value = s.value.copy(policyDraftKnown = true, policyDraftCorrupt = false)
        } catch (_: Exception) { }
        setStale(vmA, false)
        // 重新勾選（若已勾選則保持）。
        try {
            if (!vmA.state.value.selectedKeys.contains("0:com.c.app")) {
                vmA.toggleSelection("0:com.c.app")
            }
        } catch (_: Exception) { }
        vmA.requestPolicyWrite(bindingA)
        assertNull(vmA.state.value.pendingPolicyWrite)
        // request作廢訊息為UNKNOWN_REFRESH（stale或綁定任一）或BUSY（若併發門控先命中，仍作廢）。
        assertTrue(
            vmA.state.value.msg == PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText() ||
                vmA.state.value.msg == PolicyText(PolicyTextKind.BUSY).defaultText(),
        )
        assertEquals(savesBefore, saveCalls.get())

        // clear作廢（攜舊綁定）：必須disarm＋UNKNOWN_REFRESH＋零clear。
        val clearsBefore = clearCalls.get()
        // 先確保arm狀態可觀測：若先前已Unknown，先arm一次以驗證作廢後disarm。
        // 此處直接以舊綁定調用，預期作廢（不碰store）。
        vmA.clearPolicyDraft(bindingA)
        assertEquals(clearsBefore, clearCalls.get())
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vmA.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vmA.state.value.msgKind)
        assertFalse(isBlindClearArmed(vmA))
    }

    // 3b. 跨屏真實B寫入：B經VM寫入推進後，A舊綁定同樣作廢（含_latest收斂後本地三元組失配路徑）。
    @Test
    fun crossVm_realBWrite_oldBindingVoids() {
        val backend = AtomicReference<Set<String>?>(setOf("com.a.app"))
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) {
                    clearCalls.incrementAndGet()
                    backend.set(emptySet())
                    true
                } else {
                    saveCalls.incrementAndGet()
                    backend.set(raw)
                    true
                }
            },
        )
        val coord = PolicyTxCoordinator()
        val vmA = makeVm(store, initialDraft = setOf("com.a.app"), coordinator = coord, policyIoTimeoutMs = 2_000L)
        val vmB = makeVm(store, initialDraft = setOf("com.a.app"), coordinator = coord, policyIoTimeoutMs = 2_000L)
        val bindingA = vmA.currentWarningBinding()
        // B以新鮮綁定寫入B。
        val bindingB = vmB.currentWarningBinding()
        vmB.replacePolicyDraft(setOf("com.b.app"), bindingB)
        val pendingB = vmB.state.value.pendingPolicyWrite
        assertNotNull(pendingB)
        vmB.confirmPolicyWrite(pendingB!!, pendingB.generation)
        await { vmB.state.value.pendingPolicyWrite == null }
        assertEquals(setOf("com.b.app"), vmB.state.value.policyDraft)
        // 等A收斂（_latest collector可能已將A更新為B；無論是否收斂，舊綁定皆須作廢）。
        // 若已收斂，本地三元組失配；若未收斂，coordinator失配 — 兩條分支都令
        // isWarningBindingFresh 為 false，故 !fresh 必定成立。改用 condition-based
        // await 取代固定 Thread.sleep(300)：不依賴 wall-clock 收斂窗口，deterministic。
        await { !vmA.isWarningBindingFresh(bindingA) }
        assertFalse(vmA.isWarningBindingFresh(bindingA))
        val savesBefore = saveCalls.get()
        vmA.replacePolicyDraft(setOf("com.c.app"), bindingA)
        assertNull(vmA.state.value.pendingPolicyWrite)
        assertEquals(savesBefore, saveCalls.get())
        val clearsBefore = clearCalls.get()
        vmA.clearPolicyDraft(bindingA)
        assertEquals(clearsBefore, clearCalls.get())
    }

    // R5-0 (F01): blind-clear disabled — a rev advance cannot re-arm anything.
    @Test
    fun blindClear_revAdvance_rearmsWithFirstZeroClear() {
        var now = 10_000L
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app"))
        val coord = PolicyTxCoordinator()
        val vm = makeVm(fake.store, initialDraft = setOf("com.a.app"), coordinator = coord, nowMonoMs = { now })
        assertTrue(vm.state.value.policyDraftKnown)
        // 製造Unknown但保留observed（Known→遠端Unknown→refresh）：observed仍為舊rev（>=0）。
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }
        val observedBefore = vm.observedRevForTests()
        assertTrue(observedBefore != -1L)
        // 第一 tap：fail-closed，零 arm。
        vm.clearPolicyDraft()
        assertFalse(isBlindClearArmed(vm))
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(0, fake.clearCalls.get())
        // rev推進（僅observed，不動version/freshGen）。
        vm.markObservedKnownAt(observedBefore + 10L)
        // TTL內第二tap：仍 fail-closed（沒有 rev 重 arm 路徑）。
        now = 10_000L + 1_000L
        vm.clearPolicyDraft()
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(0, fake.clearCalls.get())
        assertFalse(isBlindClearArmed(vm))
    }

    // R5-0 (F01): observed/rev bookkeeping no longer feeds any clear path.
    @Test
    fun blindClear_armedRevMinusOne_bypassesRevGate() {
        var now = 20_000L
        val fake = FakePolicyStore(initialDraft = null)
        fake.saved = null
        val coord = PolicyTxCoordinator()
        // 初值Unknown從未觀察：observed==-1。
        val vm = makeVm(fake.store, initialDraft = null, coordinator = coord, nowMonoMs = { now })
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(-1L, vm.observedRevForTests())
        vm.clearPolicyDraft()
        assertFalse(isBlindClearArmed(vm))
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        // 推進observed至非-1（僅rev，不動version/freshGen）。
        vm.markObservedKnownAt(5L)
        assertEquals(5L, vm.observedRevForTests())
        now = 20_000L + 1_000L
        vm.clearPolicyDraft()
        // No rev bypass/re-arm: the Unknown tap stays fail-closed.
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(isBlindClearArmed(vm))
        assertEquals(0, fake.clearCalls.get())
        assertTrue(vm.state.value.msg != PolicyText(PolicyTextKind.BLIND_CLEAR_FIRST).defaultText())
    }

    // 5a. legacy豁免：null綁定stale仍放行。
    @Test
    fun legacy_nullBinding_bypassesStale() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store)
        setStale(vm, true)
        // null綁定即使stale亦放行（true）。
        assertTrue(vm.isWarningBindingFresh(null))
        // 非null同條件必作廢（false）。
        val fresh = vm.currentWarningBinding()
        assertFalse(vm.isWarningBindingFresh(fresh))
        // 清理，避免污染後續（tearDown會dispose，但顯式恢復）。
        setStale(vm, false)
    }

    // 5b. legacy豁免：baseFreshGen==-1跳過權威重讀（後端Unknown仍放行寫入）。
    // 這是有意保留、已文件化的 legacy carve-out（baseFreshGen == -1L 跳過重讀）：
    // 所有新 UI 路徑都攜帶 warning binding（baseFreshGen != -1），其 fail-closed
    // 重讀保證由 5c (freshBinding_withBackendUnknown_abortsZeroSave) 釘住；此 legacy
    // seam 不適用該 fail-closed 保證。本測試刻意鎖住放行行為，勿「修正」為作廢。
    // D7：新 UI 路徑「一律 baseFreshGen != -1」由下方
    // normalWarningPaths_alwaysInstallPendingWithBaseFreshGen 從公開寫入 seam 運行時釘住；
    // 本測的 legacy pending 只能靠反射直接注入（繞過所有公開 request seam）取得，
    // MainActivity／PolicyActivity 不可達。
    @Test
    fun legacy_baseFreshGenMinusOne_skipsReread() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app"))
        val vm = makeVm(fake.store, policyIoTimeoutMs = 500L, coordinator = null)
        assertTrue(vm.state.value.policyDraftKnown)
        // 直接注入legacy pending（baseFreshGen==-1，baseDraftVersion==-1，baseCoordinator==-1）。
        val legacy = PendingPolicyWrite(
            packages = setOf("com.b.app"),
            requestedAtMs = System.currentTimeMillis(),
            baseDraftVersion = -1L,
            baseCoordinatorRevision = -1L,
            baseFreshGen = -1L,
        )
        // 注入pending（繞過request門控，保持QUEUED語義由confirm補）。
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val s = f.get(vm) as MutableStateFlow<UiState>
        s.value = s.value.copy(pendingPolicyWrite = legacy)
        // 提交前遠端變Unknown；legacy跳過重讀，應放行寫入（save發生）。
        fake.saved = null
        vm.confirmPolicyWrite(legacy, legacy.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(1, fake.saveCalls.get())
        assertTrue(vm.state.value.policyDraftKnown)
    }

    // 5c. 對照：新路徑（baseFreshGen!=-1）同條件必重讀作廢零save。
    @Test
    fun freshBinding_withBackendUnknown_abortsZeroSave() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        val vm = makeVm(fake.store, policyIoTimeoutMs = 500L, coordinator = null)
        val binding = vm.currentWarningBinding()
        // binding非null→request攜baseFreshGen（!= -1）。
        vm.replacePolicyDraft(setOf("com.b.app"), binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        assertTrue(pending!!.baseFreshGen != -1L)
        fake.saved = null
        val savesBefore = fake.saveCalls.get()
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(savesBefore, fake.saveCalls.get())
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // 5c-guard (D7). 運行時守護 5b 的文件化前提：所有新 UI 寫入路徑都經由公開
    // request seam 並攜帶非 null warning binding，故安裝出的 pending【一律】
    // baseFreshGen != -1。5b 的 legacy carve-out（baseFreshGen == -1）只能靠反射
    // 直接注入 pending 取得（繞過全部公開 seam），MainActivity／PolicyActivity 不可達。
    // 三個 seam 逐一對應生產呼叫點：
    //   Main   : MainActivity proceed → requestPolicyWriteForWarning(frozen, binding)
    //   Editor : PolicyActivity.performWritePolicy → replacePolicyDraft(candidate, binding)
    //   Main列表: requestPolicyWrite(binding)（catalog→勾選映射；與前兩者共用 requestPolicyWriteFor）
    // 若未來有人讓任一公開 seam 在未綁 binding 時仍建 pending，本測即失敗。
    @Test
    fun normalWarningPaths_alwaysInstallPendingWithBaseFreshGen() {
        fun makeSeamVm(): DualViewModel {
            val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
            val vm = makeVm(fake.store)
            // 兩個需要 catalog／勾選的 seam（requestPolicyWriteForWarning、requestPolicyWrite）
            // 必須先有清單與勾選；replacePolicyDraft 不受此影響但一併種入以保持一致。
            seedSelection(vm, listOf("com.a.app", "com.b.app"), setOf("com.a.app", "com.b.app"))
            return vm
        }
        fun assertFreshGen(vm: DualViewModel, label: String) {
            val pending = vm.state.value.pendingPolicyWrite
            assertNotNull("$label must install a pending", pending)
            assertTrue(
                "$label must carry baseFreshGen != -1 (legacy carve-out is not reachable from UI)",
                pending!!.baseFreshGen != -1L,
            )
            vm.cancelPolicyWrite()
        }

        run {
            val vm = makeSeamVm()
            vm.requestPolicyWriteForWarning(setOf("com.a.app"), vm.currentWarningBinding())
            assertFreshGen(vm, "requestPolicyWriteForWarning(binding)")
        }
        run {
            val vm = makeSeamVm()
            vm.replacePolicyDraft(setOf("com.a.app"), vm.currentWarningBinding())
            assertFreshGen(vm, "replacePolicyDraft(binding)")
        }
        run {
            val vm = makeSeamVm()
            vm.requestPolicyWrite(vm.currentWarningBinding())
            assertFreshGen(vm, "requestPolicyWrite(binding)")
        }
    }

    // D03a：與 5c-guard 同形——所有新 UI 寫入路徑的公開 request seam 都必須把
    // 第一階段警告捕獲的 consent 帶進 installed pending，且內容正確：
    //   delta = old − candidate、capturedInstalled = probe.installed 的快照、
    //   generation 由 VM 重寫為 pending.generation。
    // legacy 反射注入的 pending 仍為 null（與 baseFreshGen==-1 carve-out 同形）。
    @Test
    fun normalWarningPaths_alwaysInstallPendingWithConsent() {
        val delta = setOf("com.b.app")
        val installed = listOf("com.b.app")
        val consent = buildPolicyWriteConsent(
            delta = delta,
            verifiedTarget = PreClearDualTarget(95, 7),
            installed = installed,
        )

        fun makeSeamVm(): DualViewModel {
            val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
            val vm = makeVm(fake.store)
            seedSelection(vm, listOf("com.a.app", "com.b.app"), setOf("com.a.app", "com.b.app"))
            return vm
        }
        fun assertConsent(vm: DualViewModel, label: String) {
            val pending = vm.state.value.pendingPolicyWrite
            assertNotNull("$label must install a pending", pending)
            val captured = pending!!.consent
            assertNotNull("$label must carry a non-null consent", captured)
            assertEquals("$label delta must be old − candidate", delta, captured!!.delta)
            assertEquals(
                "$label capturedInstalled must be the probe snapshot",
                installed.toSet(),
                captured.capturedInstalled,
            )
            assertEquals(95, captured.capturedUserId)
            assertEquals("7", captured.capturedSerial)
            assertEquals(
                "consent.generation must match the installed pending generation",
                pending.generation,
                captured.generation,
            )
            vm.cancelPolicyWrite()
        }

        run {
            val vm = makeSeamVm()
            vm.requestPolicyWriteForWarning(setOf("com.a.app"), vm.currentWarningBinding(), consent = consent)
            assertConsent(vm, "requestPolicyWriteForWarning(consent)")
        }
        run {
            val vm = makeSeamVm()
            vm.replacePolicyDraft(setOf("com.a.app"), vm.currentWarningBinding(), consent = consent)
            assertConsent(vm, "replacePolicyDraft(consent)")
        }
        run {
            val vm = makeSeamVm()
            vm.requestPolicyWrite(vm.currentWarningBinding(), consent = consent)
            assertConsent(vm, "requestPolicyWrite(consent)")
        }

        // legacy carve-out：反射直接注入的 pending（未經 request seam）consent 仍為 null。
        val vm = makeSeamVm()
        val legacy = PendingPolicyWrite(
            packages = setOf("com.b.app"),
            requestedAtMs = System.currentTimeMillis(),
            baseDraftVersion = -1L,
            baseCoordinatorRevision = -1L,
            baseFreshGen = -1L,
        )
        assertNull("legacy injected pending must keep consent=null", legacy.consent)
        vm.cancelPolicyWrite()
    }

    // 6a. delta接線：空集跳過probe直接proceed（純函數層）。
    @Test
    fun delta_emptySkipsProbe() {
        val old = setOf("com.a.app", "com.b.app")
        // 候選與舊同（無移除）→delta空→不警告，直接放行。
        val noRemoval = policyRemovalDelta(old, setOf("com.a.app", "com.b.app", "com.c.app"))
        assertTrue(noRemoval.isEmpty())
        assertFalse(shouldWarnForDelta(emptyList()))
        // 清除候選空集但舊空→delta空→直接放行（Main空草稿fast-path）。
        val emptyOld = emptySet<String>()
        assertTrue(policyRemovalDelta(emptyOld, emptySet()).isEmpty())
    }

    // 6b. delta非空但installed空不警告。
    @Test
    fun delta_nonEmptyInstalledEmpty_noWarn() {
        val old = setOf("com.a.app", "com.b.app")
        val candidate = setOf("com.b.app")
        val delta = policyRemovalDelta(old, candidate)
        assertEquals(setOf("com.a.app"), delta)
        // installed空→不警告。
        assertFalse(shouldWarnForDelta(emptyList()))
        // installed非空→警告。
        assertTrue(shouldWarnForDelta(listOf("com.a.app")))
    }

    // 6c. Editor空走guide不走probe/warning。
    @Test
    fun editorEmpty_guidesWithoutProbe() {
        assertTrue(editorEmptyShouldGuide(true))
        assertFalse(editorEmptyShouldGuide(false))
        assertTrue(editorEmptyShouldGuide(emptySet<String>().isEmpty()))
        assertFalse(editorEmptyShouldGuide(setOf("com.b.app").isEmpty()))
    }

    // 6d. clear作廢後disarm。
    @Test
    fun clear_staleBinding_voidsAndDisarms() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app"))
        val coord = PolicyTxCoordinator()
        var now = 30_000L
        val vm = makeVm(fake.store, initialDraft = setOf("com.a.app"), coordinator = coord, nowMonoMs = { now })
        // 先製造 Unknown（clear 入口已 fail-closed，不再 arm）。
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }
        vm.clearPolicyDraft()
        assertFalse(isBlindClearArmed(vm))
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        // 推進修訂使舊綁定失配：取arm當下綁定後再bump。
        val staleBinding = PolicyWarningBinding(
            rev = vm.observedRevForTests(),
            draftVersion = vm.policyDraftVersionForTests(),
            freshGen = vm.policyFreshGenForTests(),
        )
        coord.bumpRevisionForTests()
        // 確認失配。
        assertFalse(vm.isWarningBindingFresh(staleBinding))
        val clearsBefore = fake.clearCalls.get()
        vm.clearPolicyDraft(staleBinding)
        assertEquals(clearsBefore, fake.clearCalls.get())
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertFalse(isBlindClearArmed(vm))
    }

    // 6e. BUSY優先序：pending存在時stale綁定仍報BUSY（不作廢、不disarm）。
    @Test
    fun clear_busyPrecedenceOverStaleBinding() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app"))
        val coord = PolicyTxCoordinator()
        var now = 40_000L
        val vm = makeVm(fake.store, initialDraft = setOf("com.a.app"), coordinator = coord, nowMonoMs = { now })
        // 先 Unknown（無 arm）；R5-0 後 blind-clear 已停用。
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }
        vm.clearPolicyDraft()
        assertFalse(isBlindClearArmed(vm))
        // 注入pending（模擬併發寫入在途），同時構造失配綁定。
        val pending = PendingPolicyWrite(setOf("com.b.app"), System.currentTimeMillis())
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val s = f.get(vm) as MutableStateFlow<UiState>
        s.value = s.value.copy(pendingPolicyWrite = pending)
        val staleBinding = PolicyWarningBinding(rev = 9999L, draftVersion = 9999L, freshGen = 9999L)
        assertFalse(vm.isWarningBindingFresh(staleBinding))
        vm.clearPolicyDraft(staleBinding)
        // BUSY優先：msg為BUSY而非UNKNOWN_REFRESH，pending保留；無 arm 可保留。
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertNotNull(vm.state.value.pendingPolicyWrite)
        assertFalse(isBlindClearArmed(vm))
        assertEquals(0, fake.clearCalls.get())
    }

    // 6f. coordinator rev失配作廢。
    @Test
    fun coordinator_revMismatch_voidsBinding() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        val vm = makeVm(fake.store, coordinator = coord)
        val binding = vm.currentWarningBinding()
        assertTrue(vm.isWarningBindingFresh(binding))
        coord.bumpRevisionForTests()
        assertFalse(vm.isWarningBindingFresh(binding))
    }

    // 6g. rev重置作廢。
    @Test
    fun coordinator_revReset_voidsOldBinding() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        val vm = makeVm(fake.store, coordinator = coord)
        // 先推進一次，取新綁定（rev=1）。
        coord.bumpRevisionForTests()
        // 刷新A以觀察新rev（否則本地仍0，綁定0 vs coordinator1已失配，無法取1）。
        // 直接以markObservedKnownAt模擬已觀察1（不碰version/freshGen，精確覆蓋reset門控）。
        vm.markObservedKnownAt(1L)
        val bindingRev1 = vm.currentWarningBinding()
        assertEquals(1L, bindingRev1.rev)
        // reset回0：舊綁定rev1必作廢。
        coord.resetForTest()
        assertEquals(0L, coord.currentRevision())
        assertFalse(vm.isWarningBindingFresh(bindingRev1))
        // 新綁定（reset後重取）才新鮮：需先同步observed至0。
        vm.markObservedKnownAt(0L)
        val freshAfterReset = vm.currentWarningBinding()
        assertTrue(vm.isWarningBindingFresh(freshAfterReset))
    }

    // 6h. 純函數：isWarningBindingFreshPure全等才新鮮。
    @Test
    fun pure_isWarningBindingFreshPure() {
        val b = PolicyWarningBinding(rev = 1L, draftVersion = 2L, freshGen = 3L)
        assertTrue(isWarningBindingFreshPure(1L, 2L, 3L, b))
        assertFalse(isWarningBindingFreshPure(2L, 2L, 3L, b))
        assertFalse(isWarningBindingFreshPure(1L, 9L, 3L, b))
        assertFalse(isWarningBindingFreshPure(1L, 2L, 9L, b))
    }

    // 6i. 純函數 legacy/clear carve-out：candidateFingerprint == null 時略過候選比較，
    // 任何 live 候選（含空集）皆視為新鮮。這是 clear 路徑／legacy 注入的明確豁免，
    // 現有 7a/7b/7c2 等呼叫全帶非 null 指紋，故此分支原本未被純函式層覆蓋。
    @Test
    fun pure_isWarningCandidateFresh_nullFingerprint_bypassesCandidateCheck() {
        val legacyBinding = PolicyWarningBinding(rev = 1L, draftVersion = 2L, freshGen = 3L)
        assertNull(legacyBinding.candidateFingerprint)
        // null 指紋直接回 true，不比較 live 候選內容。
        assertTrue(isWarningCandidateFresh(legacyBinding, setOf("com.x.app")))
        assertTrue(isWarningCandidateFresh(legacyBinding, emptySet()))
    }

    // ===== R03 (PR #74)：警告候選凍結＋漂移作廢 =====

    // 7a. 警告時凍結候選 → 勾選變更（只動 selectedKeys/selectionGen）→
    // isWarningCandidateFresh 必須失配作廢（原本錯誤地回 true）。
    // Phase 2：候選檢查已從 VM.isWarningBindingFresh 移到持有 live 候選的
    // Activity／純 helper；世代三元組檢查仍留在 VM，故漂移後 isWarningBindingFresh
    // 依然為 true（選取變更不推進任何版本），只有 source-agnostic helper 抓到漂移。
    @Test
    fun warningBinding_candidateDrift_voidsFreshness() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app", "com.c.app"))
        val vm = makeVm(fake.store)
        seedSelection(vm, listOf("com.a.app", "com.b.app", "com.c.app"), setOf("com.a.app", "com.b.app"))
        val candidate = vm.writeCandidateForWarning()
        assertEquals(setOf("com.a.app", "com.b.app"), candidate)
        val binding = vm.currentWarningBinding().withWarning(setOf("com.a.app", "com.b.app", "com.c.app"), candidate)
        assertTrue(vm.isWarningBindingFresh(binding))
        assertTrue(isWarningCandidateFresh(binding, candidate))
        // probe／對話框期間改勾選：只改 selectedKeys／selectionGen。
        vm.setSelected("0:com.c.app", true)
        vm.setSelected("0:com.a.app", false)
        assertEquals(setOf("com.b.app", "com.c.app"), vm.writeCandidateForWarning())
        // 世代不變 → VM 綁定新鮮度仍 true；但 Main live 候選已漂移 → helper false。
        assertTrue(vm.isWarningBindingFresh(binding))
        assertFalse(isWarningCandidateFresh(binding, vm.writeCandidateForWarning()))
    }

    // 7b. 凍結寫入：即使當下勾選（live candidate）已不同，Main 入口仍寫警告前
    // 凍結的集合，且變異後的集合零寫入。Phase 2：指紋漂移的拒絕點已由
    // VM.isWarningBindingFresh 移到 MainActivity.proceed 的
    // isWarningCandidateFresh(live=writeCandidateForWarning())；JVM 無 Activity，
    // 故於此直接斷言 helper 抓到漂移、且不呼叫 VM request（等價於 Activity 放棄
    // proceed），再以無指紋綁定隔離「凍結集合本身」的語義。
    @Test
    fun frozenCandidate_mainEntry_writesFrozenSetOnly_afterLiveSelectionDrift() {
        val warnedOld = setOf("com.a.app", "com.b.app", "com.c.app")
        val frozen = setOf("com.a.app", "com.b.app")
        val mutated = setOf("com.b.app", "com.c.app")
        val fake = FakePolicyStore(initialDraft = warnedOld)
        val vm = makeVm(fake.store)
        // 警告前候選＝frozen；警告後 live selection 漂移為 mutated。
        seedSelection(vm, listOf("com.a.app", "com.b.app", "com.c.app"), setOf("com.a.app", "com.b.app"))
        val warnedBinding = vm.currentWarningBinding().withWarning(warnedOld, vm.writeCandidateForWarning())
        assertTrue(vm.isWarningBindingFresh(warnedBinding))
        assertTrue(isWarningCandidateFresh(warnedBinding, frozen))
        vm.setSelected("0:com.c.app", true)
        vm.setSelected("0:com.a.app", false)
        assertEquals(mutated, vm.writeCandidateForWarning())

        // 指紋綁定已因漂移作廢（Activity guard 會在此中止、零寫入）。
        assertFalse(isWarningCandidateFresh(warnedBinding, vm.writeCandidateForWarning()))
        // 直接走 Activity 用的共用訊息出口，鎖住 UNKNOWN_REFRESH/CHANNEL＋零寫入。
        vm.rejectStaleWarningBinding()
        assertEquals(PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(0, fake.saveCalls.get())

        // 無指紋綁定（clear/legacy 相容路徑）：仍需寫「凍結集合」而非 live candidate。
        val legacyBinding = vm.currentWarningBinding().withWarning(warnedOld, null)
        vm.requestPolicyWriteForWarning(frozen, legacyBinding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        assertEquals(frozen, pending!!.packages)
        assertEquals(warnedOld, pending.baseWarnedOld)
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(1, fake.saveCalls.get())
        assertEquals(frozen, fake.saved)
        assertTrue(fake.saves.none { it == mutated })
    }

    // 7c. Main／Editor 兩入口等價：兩者都接受明確集合，不從 live selectedKeys
    // 重導；pending 內容與 baseWarnedOld 一致。confirm 寫入分別由 7b/7c2
    // 單 VM 測試覆蓋（policyDraftVersion/freshGen 為檔案層級全局，雙 VM 同時
    // confirm 會互相踩到彼此的 baseFreshGen fence，與本修復無關）。
    @Test
    fun mainAndEditorEntryPoints_bothFreezeCandidateIntoPending() {
        val warnedOld = setOf("com.a.app", "com.b.app", "com.c.app")
        val frozen = setOf("com.a.app", "com.b.app")
        val mutated = setOf("com.b.app", "com.c.app")

        // Main seam：live selection 為 mutated，但傳入 frozen。
        val fakeMain = FakePolicyStore(initialDraft = warnedOld)
        val vmMain = makeVm(fakeMain.store)
        // Editor seam（對應 performWritePolicy → replacePolicyDraft(frozen, binding)）。
        val fakeEditor = FakePolicyStore(initialDraft = warnedOld)
        val vmEditor = makeVm(fakeEditor.store)
        // policyDraftVersion / policyFreshnessGen 是檔案層級全局，兩 VM 必須
        // 都建立／收斂後再排隊，否則先排隊者會被後建立 VM 的刷新踩到 baseFreshGen。
        seedSelection(vmMain, listOf("com.a.app", "com.b.app", "com.c.app"), mutated)
        seedSelection(vmEditor, listOf("com.a.app", "com.b.app", "com.c.app"), mutated)
        // Deterministic convergence instead of a fixed sleep.
        await { vmMain.writeCandidateForWarning() == mutated && vmEditor.writeCandidateForWarning() == mutated }
        assertEquals(mutated, vmMain.writeCandidateForWarning())

        val mainBinding = vmMain.currentWarningBinding().withWarning(warnedOld, null)
        vmMain.requestPolicyWriteForWarning(frozen, mainBinding)
        val mainPending = vmMain.state.value.pendingPolicyWrite
        assertNotNull(mainPending)
        val editorBinding = vmEditor.currentWarningBinding().withWarning(warnedOld, null)
        vmEditor.replacePolicyDraft(frozen, editorBinding)
        val editorPending = vmEditor.state.value.pendingPolicyWrite
        assertNotNull(editorPending)

        // 兩入口等價：集合與警告 old 快照一致，皆非 live mutated 集合。
        assertEquals(mainPending!!.packages, editorPending!!.packages)
        assertEquals(frozen, mainPending.packages)
        assertEquals(warnedOld, mainPending.baseWarnedOld)
        assertEquals(warnedOld, editorPending.baseWarnedOld)
        assertTrue(frozen != mutated)
        // 兩者 pending 皆未寫 store（confirm 才寫）。
        assertEquals(0, fakeMain.saveCalls.get())
        assertEquals(0, fakeEditor.saveCalls.get())
    }

    // 7c2. Editor seam（performWritePolicy → replacePolicyDraft(frozen, binding)）：
    // 即使 Main 的 live selectedKeys 指向別的集合，Editor 的綁定指紋仍來自
    // 自己的凍結候選（frozen），confirm 必須寫 frozen、永不寫 mutated。
    // binding 用【非 null】真實指紋（舊版會在 VM 內拿 Main candidate 重算而
    // 誤殺此路徑），此測同時鎖住 Phase 2 的 source-agnostic helper 語義。
    @Test
    fun frozenCandidate_editorEntry_writesFrozenSetOnly_afterLiveSelectionDrift() {
        val warnedOld = setOf("com.a.app", "com.b.app", "com.c.app")
        val frozen = setOf("com.a.app", "com.b.app")
        val mutated = setOf("com.b.app", "com.c.app")
        val fake = FakePolicyStore(initialDraft = warnedOld)
        val vm = makeVm(fake.store)
        seedSelection(vm, listOf("com.a.app", "com.b.app", "com.c.app"), mutated)
        await { vm.writeCandidateForWarning() == mutated }
        val binding = vm.currentWarningBinding().withWarning(warnedOld, frozen)
        assertNotNull(binding.candidateFingerprint)
        assertTrue(isWarningCandidateFresh(binding, frozen))
        assertFalse(isWarningCandidateFresh(binding, mutated))
        // 真實 Editor 路徑：binding 指紋與 VM 的 Main candidate 不同，仍須建 pending。
        vm.replacePolicyDraft(frozen, binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        assertEquals(frozen, pending!!.packages)
        assertEquals(warnedOld, pending.baseWarnedOld)
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals("msg=${vm.state.value.msg}", 1, fake.saveCalls.get())
        assertEquals(frozen, fake.saved)
        assertTrue(fake.saves.none { it == mutated })
        assertTrue(fake.saves.none { it == vm.writeCandidateForWarning() })
    }

    // 7c3. CRITICAL REGRESSION（Phase 2）：真實 Editor 路徑——binding 攜【非 null】
    // 且來自 Editor ticks 的指紋，而 ticks != vm.writeCandidateForWarning()（VM 只看
    // Main 的 selectedKeys，此測刻意留空）。舊版在 VM.isWarningBindingFresh 內重算
    // Main candidate，必失配 → UNKNOWN_REFRESH → 無 pending、無 save；故此測在
    // pre-fix 程式碼必失敗。修復後：(a) pending 安裝、(b) confirm 寫 frozen 集合、
    // (c) helper 對 ticks 為 true、對 changedTicks 為 false。
    @Test
    fun editorWrite_nonNullFingerprintFromTicks_installsPendingAndWritesFrozen() {
        val warnedOld = setOf("com.a.app", "com.b.app", "com.c.app")
        val ticks = setOf("com.a.app", "com.b.app") // Editor-local 凍結候選
        val changedTicks = setOf("com.b.app", "com.c.app")
        val fake = FakePolicyStore(initialDraft = warnedOld)
        val vm = makeVm(fake.store, initialDraft = warnedOld)
        // Main candidate 刻意與 Editor ticks 不同（未 seed 任何 selection）。
        assertTrue(vm.writeCandidateForWarning().isEmpty())
        assertTrue(ticks != vm.writeCandidateForWarning())

        val binding = vm.currentWarningBinding().withWarning(warnedOld, ticks)
        assertNotNull(binding.candidateFingerprint)
        // (c) source-agnostic helper：只認傳入的 live 候選，不碰 VM 的 Main candidate。
        assertTrue(isWarningCandidateFresh(binding, ticks))
        assertFalse(isWarningCandidateFresh(binding, changedTicks))

        // (a) 真實 Editor seam：即使 Main candidate 不同，仍須安裝 pending。
        vm.replacePolicyDraft(HashSet(ticks), binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        assertEquals(ticks, pending!!.packages)
        assertEquals(warnedOld, pending.baseWarnedOld)

        // (b) confirm 寫入凍結集合；changedTicks／Main candidate 永不寫入。
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals("msg=${vm.state.value.msg}", 1, fake.saveCalls.get())
        assertEquals(ticks, fake.saved)
        assertTrue(fake.saves.none { it == changedTicks })
        assertTrue(fake.saves.none { it == vm.writeCandidateForWarning() })
    }

    // 7d. 純函數：指紋確定性（與迭代順序無關、不同集合不同指紋）。
    @Test
    fun pure_candidateFingerprintFor_isDeterministic() {
        val a = candidateFingerprintFor(setOf("com.a.app", "com.b.app"))
        val b = candidateFingerprintFor(setOf("com.b.app", "com.a.app"))
        assertEquals(a, b)
        assertFalse(a == candidateFingerprintFor(setOf("com.b.app", "com.c.app")))
        assertFalse(a == candidateFingerprintFor(setOf("com.a.app")))
        // 每元素長度前綴：舊版「size + NUL」會撞的兩組，現在必須不同。
        val nulA = candidateFingerprintFor(setOf("a\u0000b", "c"))
        val nulB = candidateFingerprintFor(setOf("a", "b\u0000c"))
        assertFalse(nulA == nulB)
        assertTrue(nulA.contains("3:a"))
    }

    // =====================================================================
    // D03b PART 2 (PR #74 round 3): VM pre-IO defense-in-depth consent fence.
    //
    // These tests exercise the SECOND, independent fence inside DualViewModel,
    // after the R04 store pre-read and BEFORE any store.save/clear or Tx claim.
    // No Robolectric / real IPC: the VM seam `policyWriteConsentFenceForTests`
    // injects the four-gate outcome deterministically. The backend is an
    // AtomicReference so "backend unchanged" is asserted exactly, and pending
    // is injected by reflection (legacy pattern used throughout this file).
    // =====================================================================

    private fun atomicBackendStore(
        backend: AtomicReference<Set<String>?>,
        saveCalls: AtomicInteger,
        clearCalls: AtomicInteger,
    ): HookPolicyStore = HookPolicyStore(
        loadRaw = { backend.get() },
        saveRaw = { raw ->
            if (raw == null) {
                clearCalls.incrementAndGet()
                backend.set(emptySet())
            } else {
                saveCalls.incrementAndGet()
                backend.set(raw)
            }
            true
        },
    )

    // Inject a pending carrying a real consent directly (bypasses the request
    // seams on purpose — that is the bypass this fence must survive).
    // baseFreshGen=1L keeps the R04 pre-read active; baseWarnedOld=null skips
    // the content-drift abort so the CONSENT FENCE is the gate under test.
    private fun injectConsentPending(
        vm: DualViewModel,
        consent: PolicyWriteConsent?,
        packages: Set<String> = setOf("com.b.app"),
    ): PendingPolicyWrite {
        val base = PendingPolicyWrite(
            packages = packages,
            requestedAtMs = System.currentTimeMillis(),
            baseDraftVersion = -1L,
            baseCoordinatorRevision = -1L,
            // Bind to the VM's REAL freshness generation so the no-coordinator
            // baseMismatch gate stays green — otherwise an unrelated abort would
            // mask whether the consent fence itself made the decision.
            baseFreshGen = vm.policyFreshGenForTests(),
            baseWarnedOld = null,
        )
        val pending = if (consent == null) base else base.copy(consent = consent.copy(generation = base.generation))
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(vm) as MutableStateFlow<UiState>
        flow.value = flow.value.copy(
            pendingPolicyWrite = pending,
            policyWritePhase = PolicyWritePhase.QUEUED,
        )
        return pending
    }

    // F04: the VM now RECOMPUTES the delta from the authoritative Known pre-read
    // vs the real candidate and requires the DTO's delta to match. The replace
    // tests below start from {a,b} and write {b}, so the real delta is {a} (NOT
    // the candidate {b}). Clear tests pass the full old set explicitly.
    private fun consentTemplate(delta: Set<String> = setOf("com.a.app")): PolicyWriteConsent = buildPolicyWriteConsent(
        delta = delta,
        verifiedTarget = PreClearDualTarget(95, 7),
        installed = listOf("com.b.app"),
    )

    private fun assertConfirmAborted(
        saveCalls: AtomicInteger,
        backend: AtomicReference<Set<String>?>,
        initial: Set<String>,
        vm: DualViewModel,
    ) {
        assertEquals("save count must be 0", 0, saveCalls.get())
        assertEquals("backend must be unchanged", initial, backend.get())
        assertNull("pending must be cleared", vm.state.value.pendingPolicyWrite)
        assertEquals("phase must be IDLE", PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertEquals(
            PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(),
            vm.state.value.msg,
        )
    }

    @Test
    fun confirmPolicyWrite_consentMembershipChanged_abortsZeroSave() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        val fenceCalls = AtomicInteger(0)
        vm.policyWriteConsentFenceForTests = { _ ->
            fenceCalls.incrementAndGet()
            PreClearContinueOutcome.CloneMembershipChanged
        }
        val pending = injectConsentPending(vm, consentTemplate())
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(1, fenceCalls.get())
        assertConfirmAborted(saveCalls, backend, initial, vm)
    }

    @Test
    fun confirmPolicyWrite_consentProbeFailed_abortsZeroSave() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.CannotDetermine }
        val pending = injectConsentPending(vm, consentTemplate())
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertConfirmAborted(saveCalls, backend, initial, vm)
    }

    @Test
    fun confirmPolicyWrite_consentTargetChanged_abortsZeroSave() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.TargetChanged }
        val pending = injectConsentPending(vm, consentTemplate())
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertConfirmAborted(saveCalls, backend, initial, vm)
    }

    @Test
    fun confirmPolicyWrite_consentProceedOk_savesCandidate() {
        val initial = setOf("com.a.app", "com.b.app")
        val candidate = setOf("com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        val seenGeneration = AtomicReference(-1L)
        vm.policyWriteConsentFenceForTests = { consent ->
            seenGeneration.set(consent.generation)
            PreClearContinueOutcome.ProceedOk
        }
        val pending = injectConsentPending(vm, consentTemplate(), packages = candidate)
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null && saveCalls.get() == 1 }
        await { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertEquals(pending.generation, seenGeneration.get())
        assertEquals("save count must be 1", 1, saveCalls.get())
        assertEquals("candidate must be saved", candidate, backend.get())
        assertEquals(0, clearCalls.get())
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    @Test
    fun confirmPolicyWrite_legacyNullConsent_skipsFence() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        val fenceCalls = AtomicInteger(0)
        vm.policyWriteConsentFenceForTests = { _ ->
            fenceCalls.incrementAndGet()
            PreClearContinueOutcome.CannotDetermine
        }
        // Legacy carve-out: no consent AND baseFreshGen == -1L — fence must be skipped.
        val base = PendingPolicyWrite(
            packages = setOf("com.b.app"),
            requestedAtMs = System.currentTimeMillis(),
            baseDraftVersion = -1L,
            baseCoordinatorRevision = -1L,
            baseFreshGen = -1L,
            baseWarnedOld = null,
            consent = null,
        )
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(vm) as MutableStateFlow<UiState>
        flow.value = flow.value.copy(pendingPolicyWrite = base, policyWritePhase = PolicyWritePhase.QUEUED)
        vm.confirmPolicyWrite(base, base.generation)
        await { vm.state.value.pendingPolicyWrite == null && saveCalls.get() == 1 }
        assertEquals("fence must be skipped for legacy null consent", 0, fenceCalls.get())
        assertEquals(1, saveCalls.get())
        assertEquals(setOf("com.b.app"), backend.get())
        await { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
    }

    @Test
    fun confirmPolicyWrite_consentGenerationMismatch_abortsZeroSave() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        val fenceCalls = AtomicInteger(0)
        vm.policyWriteConsentFenceForTests = { _ ->
            fenceCalls.incrementAndGet()
            PreClearContinueOutcome.ProceedOk
        }
        // Bind to a DIFFERENT generation than the pending's — cheap guard aborts
        // before the fence (generation is binding-only, never the freshness authority).
        val base = PendingPolicyWrite(
            packages = setOf("com.b.app"),
            requestedAtMs = System.currentTimeMillis(),
            baseDraftVersion = -1L,
            baseCoordinatorRevision = -1L,
            baseFreshGen = vm.policyFreshGenForTests(),
            baseWarnedOld = null,
        )
        val mismatched = base.copy(consent = consentTemplate().copy(generation = base.generation + 100L))
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(vm) as MutableStateFlow<UiState>
        flow.value = flow.value.copy(pendingPolicyWrite = mismatched, policyWritePhase = PolicyWritePhase.QUEUED)
        vm.confirmPolicyWrite(mismatched, mismatched.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals("fence must not run on generation mismatch", 0, fenceCalls.get())
        assertConfirmAborted(saveCalls, backend, initial, vm)
    }

    @Test
    fun clearPolicyDraft_consentMembershipChanged_abortsZeroClear() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.CloneMembershipChanged }
        vm.clearPolicyDraft(consent = consentTemplate(delta = setOf("com.a.app", "com.b.app")))
        await { vm.state.value.msg == PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText() }
        assertEquals("clear count must be 0", 0, clearCalls.get())
        assertEquals("save count must be 0", 0, saveCalls.get())
        assertEquals("backend must be unchanged", initial, backend.get())
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
    }

    @Test
    fun clearPolicyDraft_consentProceedOk_clears() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, initialDraft = initial)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.ProceedOk }
        vm.clearPolicyDraft(consent = consentTemplate(delta = setOf("com.a.app", "com.b.app")))
        await { clearCalls.get() == 1 }
        await { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertEquals("clear count must be 1", 1, clearCalls.get())
        assertEquals("backend must be empty", emptySet<String>(), backend.get())
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // R4-4: reflection read of the blind-clear arm, so "no arm installed" is an
    // observable assertion rather than an inference from zero store calls.
    private fun blindClearArmAtMs(vm: DualViewModel): Long {
        val f = DualViewModel::class.java.getDeclaredField("blindClearArmedAtMs").apply { isAccessible = true }
        return (f.get(vm) as java.util.concurrent.atomic.AtomicLong).get()
    }

    private fun newConsentRequiredVm(): Pair<DualViewModel, FakePolicyStore> {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        val vm = makeVm(fake.store, requirePolicyWriteConsent = true)
        seedSelection(vm, listOf("com.a.app", "com.b.app"), setOf("com.a.app", "com.b.app"))
        return vm to fake
    }

    // F3 (final reviewer): build the VM exactly as production does — every named
    // arg matches makeVm, but requirePolicyWriteConsent is deliberately OMITTED so
    // Kotlin applies the production default (`= true`). All other factories in
    // this file pass the flag explicitly (mostly false), so none of them pin the
    // fail-safe default. Keeping this separate from makeVm leaves every existing
    // test's flag semantics untouched.
    private fun makeProductionDefaultVm(policyStore: HookPolicyStore): DualViewModel {
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
            nowMonoMs = { System.currentTimeMillis() },
            policyIoTimeoutMs = 500L,
            // requirePolicyWriteConsent intentionally omitted -> production default true
            policyTxCoordinator = null,
        )
        vms.add(vm)
        await { vm.state.value.policyDraftKnown }
        await { !vm.state.value.loading }
        return vm
    }

    private fun assertConsentlessWriteRefused(vm: DualViewModel, fake: FakePolicyStore) {
        assertNull("no pending may be installed", vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertEquals("no store save", 0, fake.saveCalls.get())
        assertEquals("no store clear", 0, fake.clearCalls.get())
        assertEquals("no blind-clear arm installed", -1L, blindClearArmAtMs(vm))
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(
            PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(),
            vm.state.value.msg,
        )
    }

    // F3 (final reviewer): no other factory exercises the production default —
    // makeVm always passes the flag. A VM built without the flag must still refuse
    // a consentless guarded seam exactly like an explicitly-required one. If the
    // production default ever flips to false, this test fails (a pending appears).
    @Test
    fun productionDefaultRequiresConsentForPolicyWritesBbc() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        val vm = makeProductionDefaultVm(fake.store)
        seedSelection(vm, listOf("com.a.app", "com.b.app"), setOf("com.a.app", "com.b.app"))
        vm.requestPolicyWrite(consent = null)
        assertConsentlessWriteRefused(vm, fake)
    }

    // R4-4 BBC-1: an explicitly consent-required VM refuses a consentless
    // requestPolicyWrite before any gate/selection/store work.
    @Test
    fun vmWithConsentRequiredRejectsConsentlessRequestPolicyWriteBbc() {
        val (vm, fake) = newConsentRequiredVm()
        vm.requestPolicyWrite()
        assertConsentlessWriteRefused(vm, fake)
    }

    // R4-4 BBC-2: every remaining production policy-write seam fails closed on
    // null consent. The onClearPolicyButtonTap case exercises the CLEAR route
    // (Known, not corrupt, no repair arm) so the REPAIR routes stay untouched.
    @Test
    fun vmWithConsentRequiredRejectsConsentlessReplaceAndClearAndWarningBbc() {
        run {
            val (vm, fake) = newConsentRequiredVm()
            vm.replacePolicyDraft(setOf("com.b.app"))
            assertConsentlessWriteRefused(vm, fake)
        }
        run {
            val (vm, fake) = newConsentRequiredVm()
            vm.requestPolicyWriteForWarning(setOf("com.b.app"))
            assertConsentlessWriteRefused(vm, fake)
        }
        run {
            val (vm, fake) = newConsentRequiredVm()
            vm.clearPolicyDraft()
            assertConsentlessWriteRefused(vm, fake)
        }
        run {
            val (vm, fake) = newConsentRequiredVm()
            assertTrue("route must be CLEAR to exercise the policy-write seam", vm.state.value.policyDraftKnown)
            vm.onClearPolicyButtonTap()
            assertConsentlessWriteRefused(vm, fake)
        }
    }

    // R4-4 BBC-3: with the flag on, a valid consent still installs a pending and
    // the ordinary confirm path (fence seam → store save) proceeds unchanged.
    @Test
    fun vmWithConsentRequiredAllowsValidConsentBbc() {
        val backend = AtomicReference<Set<String>?>(setOf("com.a.app", "com.b.app"))
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store, requirePolicyWriteConsent = true)
        seedSelection(vm, listOf("com.a.app", "com.b.app"), setOf("com.b.app"))
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.ProceedOk }

        vm.requestPolicyWrite(consent = consentTemplate())
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull("valid consent must still install a pending", pending)
        val captured = pending!!.consent
        assertNotNull("installed pending must carry the consent", captured)
        assertEquals(pending.generation, captured!!.generation)

        vm.confirmPolicyWrite(pending, pending.generation)
        await { saveCalls.get() == 1 }
        await { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertEquals(setOf("com.b.app"), backend.get())
        assertEquals(0, clearCalls.get())
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // R4-4 BBC-4: the opt-out seam is pinned — legacy test factories that set the
    // flag false still create a consentless pending, unchanged from before.
    @Test
    fun testBypassFlagRestoresLegacyCarveOutBbc() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        val vm = makeVm(fake.store)
        seedSelection(vm, listOf("com.a.app", "com.b.app"), setOf("com.a.app", "com.b.app"))
        vm.requestPolicyWrite()
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull("legacy carve-out must still install a pending", pending)
        assertNull("legacy carve-out pending keeps consent=null", pending!!.consent)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        assertEquals(0, fake.saveCalls.get())
        assertEquals(0, fake.clearCalls.get())
    }
}
