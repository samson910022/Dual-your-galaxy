// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase C Required補測（code+test兩份review Required）：
// - confirm重讀Corrupt/超時/已知漂移
// - 跨屏雙VM舊綁定作廢
// - blindClear rev推進重arm＋armedRev==-1旁路
// - legacy豁免（null綁定＋baseFreshGen==-1）
// - delta接線（空集/安裝空/Editor指引/clear作廢disarm＋BUSY優先/coordinator失配/reset作廢）
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

    // 2b. Known漂移不abort靠fence：權威重讀Known內容漂移仍放行（不以內容比對作廢）。
    @Test
    fun confirmReread_knownDrift_doesNotAbortReliesOnFence() {
        val fake = FakePolicyStore(initialDraft = setOf("com.a.app", "com.b.app"))
        // 無coordinator：本地fence不變，僅後端內容漂移。
        val vm = makeVm(fake.store, policyIoTimeoutMs = 500L, coordinator = null)
        val binding = vm.currentWarningBinding()
        vm.replacePolicyDraft(setOf("com.b.app"), binding)
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        // 提交前遠端漂移為另一Known（非Unknown），重讀不得abort。
        fake.saved = setOf("com.x.app")
        vm.confirmPolicyWrite(pending!!, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null }
        // 放行：確實寫入候選（fence未失配即不攔）。
        assertEquals(1, fake.saveCalls.get())
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.b.app"), vm.state.value.policyDraft)
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
        // 若已收斂，本地三元組失配；若未收斂，coordinator失配。兩者皆false。
        // 給予短暫收斂窗口後斷言（若仍未收斂，coordinator分支仍保證false）。
        Thread.sleep(300)
        assertFalse(vmA.isWarningBindingFresh(bindingA))
        val savesBefore = saveCalls.get()
        vmA.replacePolicyDraft(setOf("com.c.app"), bindingA)
        assertNull(vmA.state.value.pendingPolicyWrite)
        assertEquals(savesBefore, saveCalls.get())
        val clearsBefore = clearCalls.get()
        vmA.clearPolicyDraft(bindingA)
        assertEquals(clearsBefore, clearCalls.get())
    }

    // 4a. blindClear第二tap rev推進重arm＋BLIND_CLEAR_FIRST零clear。
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
        // 第一tap arm。
        vm.clearPolicyDraft()
        assertTrue(isBlindClearArmed(vm))
        assertEquals(PolicyText(PolicyTextKind.BLIND_CLEAR_FIRST).defaultText(), vm.state.value.msg)
        assertEquals(0, fake.clearCalls.get())
        // rev推進（僅observed，不動version/freshGen，精確覆蓋rev門控）。
        vm.markObservedKnownAt(observedBefore + 10L)
        // TTL內第二tap：rev失配→重arm＋FIRST，零clear。
        now = 10_000L + 1_000L
        vm.clearPolicyDraft()
        assertEquals(PolicyText(PolicyTextKind.BLIND_CLEAR_FIRST).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(0, fake.clearCalls.get())
        assertTrue(isBlindClearArmed(vm))
    }

    // 4b. armedRev==-1旁路釘住：從未觀察（armed -1）時，cur推進不經rev重arm。
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
        assertTrue(isBlindClearArmed(vm))
        assertEquals(PolicyText(PolicyTextKind.BLIND_CLEAR_FIRST).defaultText(), vm.state.value.msg)
        // 推進observed至非-1（僅rev，不動version/freshGen；armed仍-1）。
        vm.markObservedKnownAt(5L)
        assertEquals(5L, vm.observedRevForTests())
        now = 20_000L + 1_000L
        vm.clearPolicyDraft()
        // armedRev==-1旁路：不得因rev失配而重arm為FIRST；應落到正常清除路徑
        // （Unknown＋lastKnownNull經典語義：pre-IO重讀仍Unknown→REFRESH_FAILED，中止零clear）。
        // 關鍵釘住：msg不是BLIND_CLEAR_FIRST（證明rev未觸發重arm）。
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
        // 先製造Unknown＋arm（blindClear第一tap）。
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }
        vm.clearPolicyDraft()
        assertTrue(isBlindClearArmed(vm))
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
        // 先Unknown＋arm。
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }
        vm.clearPolicyDraft()
        assertTrue(isBlindClearArmed(vm))
        // 注入pending（模擬併發寫入在途），同時構造失配綁定。
        val pending = PendingPolicyWrite(setOf("com.b.app"), System.currentTimeMillis())
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val s = f.get(vm) as MutableStateFlow<UiState>
        s.value = s.value.copy(pendingPolicyWrite = pending)
        val staleBinding = PolicyWarningBinding(rev = 9999L, draftVersion = 9999L, freshGen = 9999L)
        assertFalse(vm.isWarningBindingFresh(staleBinding))
        vm.clearPolicyDraft(staleBinding)
        // BUSY優先：msg為BUSY而非UNKNOWN_REFRESH，pending保留，arm保留。
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertNotNull(vm.state.value.pendingPolicyWrite)
        assertTrue(isBlindClearArmed(vm))
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
}
