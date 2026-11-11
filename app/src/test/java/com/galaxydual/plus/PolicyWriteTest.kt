// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1, 階段 2):政策草稿兩階段寫入 ViewModel 單測。
// 沿用 RemoveConfirmTest harness:named-argument 構造、注入假 store/gateway、
// Dispatchers.setMain。涵蓋 owner-user 門控 (request/confirm/clear)、非法
// token 過濾後的 policyDraft/持久化內容、以及 confirm/clear 的 defense-in-depth
// 門控。
package com.galaxydual.plus

import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import android.content.SharedPreferences
import com.galaxydual.hook.PolicySource
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyWriteTest {
    // 假政策 store:記錄 save/clear 呼叫,並保有可重讀的記憶體後端。
    private class FakePolicyStore(initialDraft: Set<String> = setOf("com.seed")) {
        var saved: Set<String>? = initialDraft
        var saveCalls = 0
        var clearCalls = 0
        // 測試開關:模擬 save/clear 失敗 (既有測試預設成功,行為不變)。
        var saveSucceeds = true
        var clearSucceeds = true
        val store: HookPolicyStore = HookPolicyStore(
            loadRaw = { saved },
            saveRaw = { raw ->
                saveCalls++
                if (raw == null) {
                    clearCalls++
                    // Stage J3:成功清除後端為確認空（Known empty），非 Unknown。
                    // 真實綁定中 prefs 非空＋key 缺席即 Known 空；Fake 以 emptySet
                    // 精確建模，使快徑 read-after-ack 能以 ground truth 驗證
                    // CLEAR_SUCCESS（Unknown 永不信 ack，見 DualViewModel）。
                    // 以 saved=null 模擬 Unknown 的測試仍經直接賦值達成，不受影響。
                    if (clearSucceeds) saved = emptySet()
                    clearSucceeds
                } else {
                    if (saveSucceeds) saved = raw
                    saveSucceeds
                }
            },
        )
    }

    private val vms = mutableListOf<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try {
                vm.viewModelScope.cancel()
            } catch (_: Exception) {
                // Best effort: test process is discarded anyway.
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun makeVm(
        policyStore: HookPolicyStore?,
        userId: Int = 0,
        initialDraft: Set<String>? = setOf("com.seed"),
        nowMonoMs: () -> Long = { System.currentTimeMillis() },
        selectionStore: SelectionStore? = null,
        policyIoTimeoutMs: Long = DualViewModel.POLICY_IO_TIMEOUT_MS,
        policyText: (PolicyText) -> String = { it.defaultText() },
        policyTxCoordinator: PolicyTxCoordinator? = null,
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
            nowMonoMs = nowMonoMs,
            selectionStore = selectionStore,
            policyIoTimeoutMs = policyIoTimeoutMs,
            policyText = policyText,
            policyTxCoordinator = policyTxCoordinator,
        )
        vms.add(vm)
        // 等 init 的政策草稿載入完成,避免其 _state.update 與 confirm 競爭。
        // Phase 5：以 Known 為準（前身以 draft==initialDraft 判定，確認空 emptySet
        // 初值即等致 vacuous 起步）：Known 翻轉與 draft 同一 emission，await Known
        // 即等價等待載入完成，且空/非空皆須真實載入。
        if (policyStore != null && initialDraft != null) {
            await { vm.state.value.policyDraftKnown }
        }
        // 等 init probe 的 terminal state (loading=false 與 probe 的最終
        // _state.update 同一原子更新,故此條件即代表 probe 已走完):避免後續
        // request/confirm/clear 與 probe 的 state 更新競爭。
        // 注意:不可先 await loading=true——probe 在 init 同步設 true,且可能
        // 在草稿載入期間已走完 (loading 變 false),那會等到 timeout。
        await { !vm.state.value.loading }
        return vm
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "timeout waiting for condition: state=${vms.lastOrNull()?.state?.value}",
                )
            }
            Thread.sleep(20)
        }
    }

    // 直接注入 pending (確認/清除測試需要指定的世代與要求的集合)。
    private fun setPendingPolicyWrite(vm: DualViewModel, pending: PendingPolicyWrite?) {
        val field = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<UiState>
        state.value = state.value.copy(pendingPolicyWrite = pending)
    }

    // 種下已確認草稿狀態 (讓 storeNull 失敗轉換測試非 vacuous:先 known 再驗證變 unknown)。
    private fun seedPolicyDraftKnown(vm: DualViewModel, draft: Set<String> = setOf("com.seed")) {
        val field = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<UiState>
        state.value = state.value.copy(policyDraft = draft, policyDraftKnown = true)
    }

    private fun seedSelection(vm: DualViewModel) {
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
    }

    // owner-user 門控:非 owner 不得建立 pending,且不得寫入 store。
    @Test
    fun requestPolicyWrite_nonOwnerRefusesWithoutPendingOrStoreWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 10)
        seedSelection(vm)

        vm.requestPolicyWrite()

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(
            "政策草稿必須由主用戶（owner user）寫入，否則 hook 讀不到",
            vm.state.value.msg,
        )
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(0, fake.saveCalls)
        assertEquals(0, fake.clearCalls)
    }

    // 門控優先級:空勾選 + 非 owner 時,owner 訊息必須壓過「未勾選任何應用」。
    @Test
    fun requestPolicyWrite_nonOwnerPrecedenceOverEmptySelection() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 10)

        vm.requestPolicyWrite()

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(
            "政策草稿必須由主用戶（owner user）寫入，否則 hook 讀不到",
            vm.state.value.msg,
        )
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    // confirm 的非法 token 過濾:policyDraft 與 store 實際持久化皆只含合法包名,
    // 且成功訊息的計數與持久化值一致。
    @Test
    fun confirmPolicyWrite_sanitizesDraftAndPersistsOnlyValidTokens() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        val pending = PendingPolicyWrite(
            setOf("com.ok.app", "bad pkg"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite()
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(setOf("com.ok.app"), vm.state.value.policyDraft)
        assertEquals(setOf("com.ok.app"), fake.saved)
        assertTrue(vm.state.value.msg.contains("1 項"))
    }

    // owner-user 門控 (defense-in-depth):confirm/clear 對非 owner 不寫 store。
    @Test
    fun confirmAndClear_nonOwnerRefuseWithoutStoreWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 10)
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite()
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(0, fake.saveCalls)

        vm.clearPolicyDraft()
        assertEquals(0, fake.clearCalls)
        assertEquals(
            "政策草稿必須由主用戶（owner user）操作，否則 hook 讀不到",
            vm.state.value.msg,
        )
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    // owner request 成功:勾選映射出 catalog 包名,建 pending 但不寫 store。
    @Test
    fun requestPolicyWrite_ownerSuccessMapsCatalogPackagesWithoutStoreWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        seedSelection(vm)

        vm.requestPolicyWrite()

        assertEquals(
            setOf("com.example.a"),
            vm.state.value.pendingPolicyWrite?.packages,
        )
        assertEquals(0, fake.saveCalls)
        assertEquals(0, fake.clearCalls)
    }

    // confirm 的 store saveRaw 回 false:fail-closed 清 pending、回報 FAILED,
    // 顯示草稿不得被偷偷更新 (維持原值)。
    @Test
    fun confirmPolicyWrite_storeSaveFails_reportsFailedAndClearsPending() {
        val fake = FakePolicyStore()
        fake.saveSucceeds = false
        val vm = makeVm(fake.store, userId = 0)
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite()
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("寫入政策失敗"))
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        // fail-closed:saveRaw 只被呼叫一次,不得重試或改寫草稿。
        assertEquals(1, fake.saveCalls)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // clear 的 store saveRaw(null) 回 false:fail-closed 回報 FAILED。
    @Test
    fun clearPolicyDraft_storeClearFails_reportsFailed() {
        val fake = FakePolicyStore()
        fake.clearSucceeds = false
        val vm = makeVm(fake.store, userId = 0)

        vm.clearPolicyDraft()
        await { vm.state.value.msgKind == MsgKind.FAILED }

        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("清除草稿失敗"))
        // fail-closed:saveRaw(null) 只被呼叫一次,且草稿不得被偷偷清空。
        assertEquals(1, fake.clearCalls)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // owner clear 成功:走 store.clear、草稿清空、訊息為 INFO。
    @Test
    fun clearPolicyDraft_ownerSuccessClearsStoreAndDraft() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)

        vm.clearPolicyDraft()
        // 等 VM 的 state 更新 (clearCalls 在 store 端先翻,狀態更新在其後)。
        await { vm.state.value.policyDraft.isEmpty() }

        assertEquals(1, fake.clearCalls)
        assertTrue(vm.state.value.msg.contains("已清除政策草稿"))
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
    }

    // cancel:只清 pending,不寫 store,訊息為 INFO。
    @Test
    fun cancelPolicyWrite_clearsPendingWithoutStoreWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        seedSelection(vm)
        vm.requestPolicyWrite()
        assertNotNull(vm.state.value.pendingPolicyWrite)

        vm.cancelPolicyWrite()

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(0, fake.saveCalls)
        assertEquals(0, fake.clearCalls)
        assertTrue(vm.state.value.msg.contains("已取消寫入政策"))
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
    }

    // cancel 後再 confirm：不得寫 store，pending 保持 null。
    @Test
    fun cancelThenConfirm_noWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.cancelPolicyWrite(pending)
        assertNull(vm.state.value.pendingPolicyWrite)

        vm.confirmPolicyWrite(pending)

        assertEquals(0, fake.saveCalls)
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // clear 後再 confirm 舊 pending：不得寫 store，pending 保持 null。
    @Test
    fun clearThenConfirm_noWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.clearPolicyDraft()
        await { vm.state.value.policyDraft.isEmpty() }

        assertNull(vm.state.value.pendingPolicyWrite)

        val savesBeforeConfirm = fake.saveCalls
        vm.confirmPolicyWrite(pending)

        assertEquals(savesBeforeConfirm, fake.saveCalls)
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // 晚到的 init 載入不得覆寫較新的 save 所寫入的 policyDraft。
    // Stage J3:ackRead 須 Known（Unknown 不信 ack）。harness 以 backend＋計數區分
    // init 阻塞舊讀（stale A）與 ackRead 新值（B），使快徑以 ground truth 驗證 SUCCESS。
    @Test
    fun confirmPolicyWrite_stalePendingLoadDoesNotOverwriteNewerSave() {
        val loadStarted = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadCompleted = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.B"))
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    // Handshake: signal that the init load has entered its blocked
                    // section *before* capturing versionAtStart, so the version guard
                    // is exercised rather than relying on scheduling.
                    loadStarted.countDown()
                    while (!released.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    loadCompleted.countDown()
                    setOf("com.A")
                } else {
                    backend.get()
                }
            },
            saveRaw = { raw ->
                if (raw != null) backend.set(raw)
                true
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = null)

        // Wait until the init load is actually blocked before saving.
        assertTrue(loadStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // P2 #3:confirm 在 policyDraftKnown==false 時拒絕寫入；本測試驗證版本
        // 守衛 (stale load 不得覆寫較新 save)，故先種下已確認草稿以通過未知門控
        // (版本號不變，守衛語意不變)。
        seedPolicyDraftKnown(vm, emptySet())

        val pending = PendingPolicyWrite(
            setOf("com.B"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(setOf("com.B"), vm.state.value.policyDraft)

        // Release the blocked init load and wait deterministically for it to
        // finish; the version guard must prevent it from overwriting the newer
        // save's policyDraft.
        released.set(true)
        assertTrue(loadCompleted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
    }

    // 部分勾選被捨去時顯示 info 訊息,pending 只含有效包名。
    @Test
    fun requestPolicyWrite_partialSelectionDrop_showsWarning() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        vm.toggleSelection("0:com.example.missing")

        vm.requestPolicyWrite()

        assertTrue(vm.state.value.msg.contains("已捨去 1 項不在來源清單的勾選"))
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(
            setOf("com.example.a"),
            vm.state.value.pendingPolicyWrite?.packages,
        )
    }

    // 取消在寫入進行中到達:post-IO re-check 必須中止 commit,且不覆寫
    // pending/msg/loading 狀態。
    @Test
    fun confirmPolicyWrite_cancelDuringSave_noStateCommit() {
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakePolicyStore()
        // 用阻塞式 saveRaw 取代 fake 的同步後端。
        val blockingStore = HookPolicyStore(
            loadRaw = { fake.saved },
            saveRaw = { raw ->
                fake.saveCalls++
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw == null) {
                    fake.clearCalls++
                    fake.saved = null
                } else {
                    fake.saved = raw
                }
                true
            },
        )
        val vm = makeVm(blockingStore, userId = 0)
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(vm.policyWriteCommitInProgress)

        // 5.1.1:寫入進行中取消被拒絕 (pending 保留,不產生「已取消」訊息)。
        vm.cancelPolicyWrite(pending)
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.msg.contains("已取消寫入政策"))
        assertTrue(vm.state.value.loading)

        saveRelease.set(true)
        await { !vm.state.value.loading }

        // 釋放後寫入成功完成。
        assertEquals(1, fake.saveCalls)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
    }

    // refreshPolicyDraft 在較新 save 之後晚到,不得覆寫 policyDraft。
    // Stage F1 誠實化：save 落盤更新 backend，非阻塞讀回 backend（快徑
    // read-after-ack 回新值才 WRITE_SUCCESS）；阻塞 refresh 仍回 stale A 以
    // 演練丟棄（版本已推進，晚到不得覆寫）。
    // Stage J3:ackRead 須 Known（Unknown 不信 ack）。harness 以 blockedOnce 確保
    // 僅 refresh 阻塞（stale A），ackRead 皆回 backend（Known），使快徑 SUCCESS。
    @Test
    fun refreshPolicyDraft_stalePendingLoadDoesNotOverwriteNewerSave() {
        val blockLoad = java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseLoad = java.util.concurrent.atomic.AtomicBoolean(true)
        val refreshStarted = java.util.concurrent.CountDownLatch(1)
        val refreshCompleted = java.util.concurrent.CountDownLatch(1)
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.A"))
        val blockedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (blockLoad.get() && !blockedOnce.getAndSet(true)) {
                    refreshStarted.countDown()
                    while (!releaseLoad.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    refreshCompleted.countDown()
                    setOf("com.A")
                } else {
                    backend.get()
                }
            },
            saveRaw = { raw ->
                if (raw != null) backend.set(raw)
                true
            },
        )
        // 初始載入不阻塞,完成後 policyDraft = com.A。
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.A"))

        // 較新 save:com.B。
        val pending = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)

        // 阻塞 refresh 載入。
        blockLoad.set(true)
        releaseLoad.set(false)
        vm.refreshPolicyDraft()
        assertTrue(refreshStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 在 refresh 被阻塞時再 save com.C (version 再 +1)。
        val pending2 = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending2)
        vm.confirmPolicyWrite(pending2)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(setOf("com.C"), vm.state.value.policyDraft)

        // 釋放 refresh 載入;版本已變,不得覆寫。
        releaseLoad.set(true)
        assertTrue(refreshCompleted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(setOf("com.C"), vm.state.value.policyDraft)
    }

    // policyFreshnessGen 世代守衛:重疊的兩次 refresh,慢速 A 不得覆寫快速 B。
    // A 先發但阻塞在 loadRaw,B 後發但立即回較新值並先發布;晚到的 A 必須被
    // 世代檢查丟棄 (version 不變,僅 generation 決定)。
    @Test
    fun refreshPolicyDraft_overlappingLoads_staleFirstDoesNotOverwriteSecond() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val aStarted = java.util.concurrent.CountDownLatch(1)
        val aFinished = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    // call 1:init 載入 (makeVm 等其穩定)。
                    1 -> setOf("com.INIT")
                    // call 2 (A,慢速):阻塞直到釋放,返回舊值。
                    2 -> {
                        aStarted.countDown()
                        while (!releaseA.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        aFinished.countDown()
                        setOf("com.OLD")
                    }
                    // call 3+ (B,快速):立即回較新值。
                    else -> setOf("com.NEW")
                }
            },
            saveRaw = { _ -> true },
        )
        // init 載入不阻塞,完成後 policyDraft = com.INIT。
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.INIT"))
        assertEquals(1, calls.get())
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.policyDraftKnown)

        // A 先發並阻塞在 loadRaw。
        vm.refreshPolicyDraft()
        assertTrue(aStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(2, calls.get())

        // B 後發但快速完成,先發布較新值。
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraft == setOf("com.NEW") }
        assertTrue(vm.state.value.policyDraftKnown)

        // 釋放晚到的 A;世代已變,不得覆寫 B。aFinished 在 loadRaw 返回前
        // 觸發、早於 Main 的 _state.update,故以穩定窗口斷言 (抖動即失敗)，
        // 而非單次 sleep 取樣。
        releaseA.set(true)
        assertTrue(aFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertEquals(setOf("com.NEW"), vm.state.value.policyDraft)
            Thread.sleep(20)
        }
        assertTrue(vm.state.value.policyDraftKnown)
    }

    // 全部勾選皆不在來源清單時,應拒絕並顯示 INVALID 訊息。
    @Test
    fun requestPolicyWrite_allInvalid_showsInvalidMessage() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.missing")

        vm.requestPolicyWrite()

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals("勾選的應用已不在來源清單，請重選", vm.state.value.msg)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(0, fake.saveCalls)
    }

    // 無捨去時不應顯示 info 警告。
    @Test
    fun requestPolicyWrite_noDrop_doesNotShowWarning() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")

        vm.requestPolicyWrite()

        assertNotNull(vm.state.value.pendingPolicyWrite)
        // msg should not be the dropped-items info message.
        assertFalse(vm.state.value.msg.contains("已捨去"))
    }

    // 4.1 #1 TTL 過期：不得寫入。
    @Test
    fun confirmPolicyWrite_ttlExpired_refusesWithoutWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0, nowMonoMs = { 10_000L })
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            10_000L - DualViewModel.REMOVE_CONFIRM_TTL_MS - 1,
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertTrue(vm.state.value.msg.contains("確認已逾時"))
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(0, fake.saveCalls)
    }

    // 4.1 #2 世代不匹配：不寫入，pending 保持 B。
    @Test
    fun confirmPolicyWrite_generationMismatch_doesNotWrite() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        val pendingA = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingA)
        val pendingB = PendingPolicyWrite(setOf("com.example.b"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingA)

        assertEquals(0, fake.saveCalls)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
    }

    // 4.1 #3 無 pending：INVALID。
    @Test
    fun confirmPolicyWrite_noPending_reportsInvalid() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)

        vm.confirmPolicyWrite()

        assertEquals("沒有待確認的政策寫入", vm.state.value.msg)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // 4.1 #4 null store：CHANNEL。
    @Test
    fun confirmPolicyWrite_nullStore_reportsChannel() {
        val vm = makeVm(null, userId = 0)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals("無政策 store，無法寫入政策", vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // 4.1 #5 store saveRaw 拋出：FAILED。
    @Test
    fun confirmPolicyWrite_storeThrowing_reportsFailed() {
        val throwingStore = HookPolicyStore(
            loadRaw = { setOf("com.seed") },
            saveRaw = { throw RuntimeException("boom") },
        )
        val vm = makeVm(throwingStore, userId = 0)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("寫入政策失敗"))
        assertNull(vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
    }

    // Stage A:confirm 超時轉 RECONCILING + Unknown fence（可注入超時鎖定）。
    // fake save 阻塞（latch）遠超注入超時 (200ms)；超時走 RECONCILING/CHANNEL
    //（pending 保留，請勿重試），mutex 即釋放，reconciler 接手核對。
    // 順序鎖定：TimeoutCancellationException 須先於 CancellationException
    // 捕捉（前者為後者子類）——若順序顛倒，超時會被重拋為取消而非 RECONCILING，
    // 本測試（RECONCILING＋CHANNEL＋Unknown＋pending 保留）即失敗。
    // Load latch（首調即 init 即時回，後續 reconciler/refresh 阻塞）使
    // RECONCILING 穩定可觀測，釋放後 reconciler 終態回 IDLE。
    @Test
    fun confirmPolicyWrite_timeout_entersReconcilingWithChannelAndUnknown() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadStarted = java.util.concurrent.CountDownLatch(1)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val slowStore = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    loadStarted.countDown()
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(slowStore, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        // Stage A 終態：CHANNEL（未知）＋pending 保留＋Unknown＋TIMEOUT 文案。
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("逾時"))
        assertTrue(vm.state.value.msg.contains("結果未知"))
        assertTrue(vm.state.value.msg.contains("正在核對"))
        assertTrue(vm.state.value.msg.contains("請勿重試"))
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.loading)

        // 清理：放行 save＋load，reconciler 終態回 IDLE（成功，draft=新值）。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        loadRelease.set(true)
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // Stage A:clear 超時轉 RECONCILING + Unknown fence（同 confirm，順序鎖定同上）。
    // clear 起始即清 pending，故逾時斷言 pending==null；其餘同 confirm。
    @Test
    fun clearPolicyDraft_timeout_entersReconcilingWithChannelAndUnknown() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val slowStore = HookPolicyStore(
            loadRaw = {
                // Stage J3:pre-read（call2）直通 Known，使 clear 得以啟動 save 並逾時
                // 進 RECONCILING；reconciler／ack 後續讀（calls>=3）仍阻塞待放行。
                if (calls.incrementAndGet() <= 2) {
                    backend.get()
                } else {
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw == null) backend.set(emptySet()) else backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(slowStore, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }

        vm.clearPolicyDraft()
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("逾時"))
        assertTrue(vm.state.value.msg.contains("結果未知"))
        assertTrue(vm.state.value.msg.contains("正在核對"))
        assertTrue(vm.state.value.msg.contains("請勿重試"))
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertFalse(vm.state.value.loading)

        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        loadRelease.set(true)
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
    }

    // Stage A latch 版 confirm-timeout（4 維斷言：backend/UI/後續/重複）。
    // - backend：逾時時 save 仍在途（saveCalls==1、後端未更新、mutex 已釋放）。
    // - UI：RECONCILING＋pending 保留＋Known=false＋TIMEOUT/CHANNEL＋loading=false。
    // - 後續：核對中 request/clear 拒 BUSY、cancel 靜默 no-op（pending 保留）。
    // - 重複：核對中重複 confirm 拒 IN_PROGRESS，不耗第二次 save。
    // 放行後 reconciler 終態回 IDLE（Known＋新值＋pending 清空），後續 request 可用。
    @Test
    fun confirmPolicyWrite_timeout_latch_entersReconcilingAndReconciles() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val loadStarted = java.util.concurrent.CountDownLatch(1)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    loadStarted.countDown()
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        assertTrue(loadStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // backend 維：save 仍在途，後端未更新。
        assertEquals(1, saveCalls.get())
        assertEquals(setOf("com.seed"), backend.get())
        assertEquals(1, saveFinished.count)
        // UI 維：RECONCILING＋pending 保留＋Unknown＋TIMEOUT/CHANNEL＋不持 loading。
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("逾時"))
        assertTrue(vm.state.value.msg.contains("正在核對"))
        assertTrue(vm.state.value.msg.contains("請勿重試"))
        assertFalse(vm.state.value.loading)
        assertEquals(
            PolicyText(PolicyTextKind.WRITE_TIMEOUT).defaultText(),
            vm.state.value.msg,
        )

        // 重複維：核對中重複 confirm 不耗第二次 save。
        vm.confirmPolicyWrite(pending)
        Thread.sleep(100)
        assertEquals(PolicyText(PolicyTextKind.IN_PROGRESS).defaultText(), vm.state.value.msg)
        // IN_PROGRESS 覆寫 TIMEOUT 後，pending/相位仍保留（僅 msg 變化）。
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(1, saveCalls.get())

        // 後續維：核對中 request/clear 拒 BUSY、cancel 靜默 no-op。
        // 注意：IN_PROGRESS 已覆寫 TIMEOUT，此處先記 msg 再比 BUSY 覆寫。
        vm.requestPolicyWrite()
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        vm.clearPolicyDraft()
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        val msgBeforeCancel = vm.state.value.msg
        vm.cancelPolicyWrite(pending)
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertEquals(msgBeforeCancel, vm.state.value.msg)

        // 放行：save 完成＋reconciler 讀到新值，終態 IDLE＋Known＋新值。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        loadRelease.set(true)
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        assertEquals(setOf("com.example.a"), backend.get())
        assertEquals(1, saveCalls.get())
    }

    // Stage A clear 變體：clear 逾時進 RECONCILING（pending 本無），重複 clear 拒
    // BUSY，放行後終態 IDLE＋Known-empty＋CLEAR_SUCCESS。
    @Test
    fun clearPolicyDraft_timeout_latch_entersReconcilingAndReconciles() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                // Stage J3:pre-read（call2）直通 Known，使 clear 得以啟動 save 並逾時
                // 進 RECONCILING；後續核對讀（calls>=3）仍阻塞待放行。
                if (calls.incrementAndGet() <= 2) {
                    backend.get()
                } else {
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw == null) backend.set(emptySet()) else backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }

        vm.clearPolicyDraft()
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        assertEquals(1, clearCalls.get())
        assertEquals(setOf("com.seed"), backend.get())
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(
            PolicyText(PolicyTextKind.CLEAR_TIMEOUT).defaultText(),
            vm.state.value.msg,
        )
        assertFalse(vm.state.value.policyDraftKnown)
        assertFalse(vm.state.value.loading)

        // 重複 clear 拒 BUSY，不耗第二次 clear。
        vm.clearPolicyDraft()
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertEquals(1, clearCalls.get())

        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        loadRelease.set(true)
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已清除政策草稿"))
    }

    // Stage A fail 變體：已證明失敗（save 回 false）仍走 FAILED＋IDLE，不進
    // RECONCILING（逾時未知才核對；失敗已知不核對）。
    @Test
    fun confirmPolicyWrite_storeFalse_staysFailedNotReconciling() {
        val fake = FakePolicyStore()
        fake.saveSucceeds = false
        val vm = makeVm(fake.store, userId = 0, policyIoTimeoutMs = 5_000)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(1, fake.saveCalls)
    }

    // Stage A mismatch 變體：reconciler 以 ground truth 為準（不謊稱寫入成功）。
    // save 新值完成後，外部併發寫入覆蓋為他值；reconciler 讀到他值即發布他值＋
    // REFRESHED（INFO），而非 WRITE_SUCCESS。
    @Test
    fun confirmPolicyWrite_timeout_mismatchReconcilesToLoadedValue() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        // save 先完成（後端=新值），再由外部覆寫為他值，reconciler 只能見他值。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        backend.set(setOf("com.other"))
        loadRelease.set(true)
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.other"), vm.state.value.policyDraft)
        assertEquals(setOf("com.other"), backend.get())
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(
            PolicyText(PolicyTextKind.REFRESHED).defaultText(),
            vm.state.value.msg,
        )
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // 4.1 #6 重複 confirm 去重。
    @Test
    fun confirmPolicyWrite_duplicateConfirm_dedups() {
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakePolicyStore()
        val blockingStore = HookPolicyStore(
            loadRaw = { fake.saved },
            saveRaw = { raw ->
                fake.saveCalls++
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw == null) {
                    fake.clearCalls++
                    fake.saved = null
                } else {
                    fake.saved = raw
                }
                true
            },
        )
        val vm = makeVm(blockingStore, userId = 0)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 第二個 confirm 在 in-flight 期間到達，應被去重。
        vm.confirmPolicyWrite(pending)
        assertEquals(PolicyText(PolicyTextKind.IN_PROGRESS).defaultText(), vm.state.value.msg)
        assertEquals(1, fake.saveCalls)

        saveRelease.set(true)
        await { !vm.state.value.loading }
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // 4.1 #7 null store clear：CHANNEL。
    @Test
    fun clearPolicyDraft_nullStore_reportsChannel() {
        val vm = makeVm(null, userId = 0)

        vm.clearPolicyDraft()
        await { vm.state.value.msgKind == MsgKind.CHANNEL }

        assertEquals("無政策 store，無法清除草稿", vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    // 4.1 #8 空勾選：INVALID。
    @Test
    fun requestPolicyWrite_emptySelection_owner_reportsInvalid() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)

        vm.requestPolicyWrite()

        assertEquals("未勾選任何應用", vm.state.value.msg)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // 4.1 #9 catalog 缺失：INVALID。
    @Test
    fun requestPolicyWrite_catalogMissing_showsInvalid() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.missing")

        vm.requestPolicyWrite()

        assertEquals("勾選的應用已不在來源清單，請重選", vm.state.value.msg)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // 4.1 #10 cancel mismatched expected：no-op。
    @Test
    fun cancelPolicyWrite_mismatchedExpected_isNoOp() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        val beforeMsg = vm.state.value.msg

        vm.cancelPolicyWrite(pending.copy(generation = pending.generation + 1))

        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertEquals(beforeMsg, vm.state.value.msg)
    }


    // 政策寫入進行中時 requestPolicyWrite 被拒絕 (inadmissible；僅政策
    // 寫入/清除 key 在途才擋，非全域 loading——refresh/其他操作進行中仍可開對話框)。
    @Test
    fun requestPolicyWrite_whilePolicyWriteInFlight_isInadmissible() {
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakePolicyStore()
        val blockingStore = HookPolicyStore(
            loadRaw = { fake.saved },
            saveRaw = { raw ->
                fake.saveCalls++
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw == null) {
                    fake.clearCalls++
                    fake.saved = null
                } else {
                    fake.saved = raw
                }
                true
            },
        )
        val vm = makeVm(blockingStore, userId = 0)
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 寫入進行中呼叫 requestPolicyWrite 應被拒絕。
        vm.requestPolicyWrite()
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertEquals("政策寫入進行中，請稍候", vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.loading)

        saveRelease.set(true)
        await { !vm.state.value.loading }
    }

    // Phase 5：harness 已改以 Known 為準（見 makeVm），此處鎖定 Known 載入的
    // 終態：Known＋草稿內容一致（非僅 harness 起步即真）。
    @Test
    fun refreshPolicyDraft_knownLoad_marksKnownWithDraft() {
        val fake = FakePolicyStore(initialDraft = setOf("com.seed"))
        val vm = makeVm(fake.store, userId = 0, initialDraft = setOf("com.seed"))
        await { vm.state.value.policyDraftKnown }
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
    }

    @Test
    fun refreshPolicyDraft_unknownLoad_marksUnknownPreservingDraft() {
        val fake = FakePolicyStore(initialDraft = setOf("com.seed"))
        val vm = makeVm(fake.store, userId = 0, initialDraft = setOf("com.seed"))
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    @Test
    fun refreshPolicyDraft_confirmedEmptyLoad_marksKnownEmpty() {
        val fake = FakePolicyStore(initialDraft = emptySet())
        val vm = makeVm(fake.store, userId = 0, initialDraft = emptySet())
        await { vm.state.value.policyDraftKnown }
        assertTrue(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.policyDraft.isEmpty())
    }

    @Test
    fun confirmPolicyWrite_storeNull_marksDraftUnknown() {
        val vm = makeVm(null, userId = 0, initialDraft = null)
        seedPolicyDraftKnown(vm)
        assertTrue(vm.state.value.policyDraftKnown)
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite()
        await { vm.state.value.pendingPolicyWrite == null }

        assertFalse(vm.state.value.policyDraftKnown)
        // 失敗不得改寫已確認草稿。
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
    }

    @Test
    fun clearPolicyDraft_storeNull_marksDraftUnknown() {
        val vm = makeVm(null, userId = 0, initialDraft = null)
        seedPolicyDraftKnown(vm)
        assertTrue(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        await { vm.state.value.msg.contains("無政策 store") }

        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
    }

    // P1 #1 queued-confirm cancel race:慢速非政策操作持有 opMutex 時，
    // 已排隊 (queued、save 尚未開始) 的政策 confirm 仍可取消；取消後排隊的
    // confirm 在取到 mutex 時 abort，不得寫入 store。
    @Test
    fun cancelQueuedConfirmBehindSlowUnrelatedOp_abortsWithoutWrite() {
        val fake = FakePolicyStore()
        val blockerEntered = java.util.concurrent.CountDownLatch(1)
        val blockerRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        // 慢速非政策 mutex 持有者：cancelSavedSelection 的 store.clear 路徑
        // (selectionStore 可注入、無政策 key、不設 loading，最小屏障)。
        val blockingSelectionStore = SelectionStore(
            loadRaw = { null },
            saveRaw = { _ ->
                blockerEntered.countDown()
                while (!blockerRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                true
            },
        )
        val vm = makeVm(fake.store, userId = 0, selectionStore = blockingSelectionStore)
        seedSelection(vm)
        vm.requestPolicyWrite()
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        val savesBefore = fake.saveCalls

        // 慢速無關操作先持有 opMutex (跨 onIo 阻塞)。
        vm.cancelSavedSelection()
        assertTrue(blockerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 政策 confirm 在 mutex 後排隊 (key 已進 inFlightWrites，但 save 未開始)。
        vm.confirmPolicyWrite(pending!!)
        await { vm.policyOperationInProgress }
        assertFalse(vm.policyWriteCommitInProgress)

        // 僅排隊時取消必須生效 (清 pending)，而非 no-op。
        vm.cancelPolicyWrite(pending)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.msg.contains("已取消寫入政策"))
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)

        // 釋放 blocker；排隊的 confirm 取到 mutex 後必須 abort，不寫入。
        blockerRelease.set(true)
        await { !vm.policyOperationInProgress }

        assertEquals(savesBefore, fake.saveCalls)
        assertEquals(setOf("com.seed"), fake.saved)
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // P2 #3 (a):未知草稿下 confirm 拒絕覆寫 (fail closed)。
    @Test
    fun confirmPolicyWrite_stalePendingUnknownDraft_refusesWithoutWrite() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)
        val draftBefore = vm.state.value.policyDraft
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(0, fake.saveCalls)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(draftBefore, vm.state.value.policyDraft)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("未知"))
        assertTrue(vm.state.value.msg.contains("重新整理"))
    }

    // P2 #3:未知草稿下 request 提前拒絕 (不建 pending)。
    @Test
    fun requestPolicyWrite_stalePendingUnknownDraft_refusesEarly() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)
        seedSelection(vm)

        vm.requestPolicyWrite()

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("未知"))
        assertTrue(vm.state.value.msg.contains("重新整理"))
        assertEquals(0, fake.saveCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // P2 #3 (b):不可用→服務恢復→寫入：未知時拒絕，refresh 已知後寫入成功。
    @Test
    fun confirmPolicyWrite_stalePendingUnavailableThenRestored_refreshThenSucceeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(null)
        var saveCalls = 0
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls++
                if (raw == null) {
                    false
                } else {
                    backend.set(raw)
                    true
                }
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        // 未知時 confirm 被拒，不寫 store。
        val pending1 = PendingPolicyWrite(
            setOf("com.example.a"),
            System.currentTimeMillis(),
        )
        setPendingPolicyWrite(vm, pending1)
        vm.confirmPolicyWrite(pending1)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(0, saveCalls)
        assertFalse(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.msg.contains("未知"))

        // 服務恢復：遠端已有確認值，refresh 後已知。
        backend.set(setOf("com.remote"))
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.remote"), vm.state.value.policyDraft)

        // 已知後 request+confirm 成功覆寫。
        seedSelection(vm)
        vm.requestPolicyWrite()
        val pending2 = vm.state.value.pendingPolicyWrite
        assertNotNull(pending2)
        vm.confirmPolicyWrite(pending2!!)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(1, saveCalls)
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
    }

    // Blind clear (Phase 2, Stage J3)：unknown draft → 第一次僅告警不執行（不清 pending、
    // 不碰 store）；第二次重讀仍 Unknown→abort（不刪未見內容，clear 起始清 pending）。
    @Test
    fun clearPolicyDraft_unknownDraft_requiresDoubleConfirm() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        // 第一次：僅告警，不執行。
        vm.clearPolicyDraft()
        // 同步 _state.update 警語（不經 IO），直接可斷言；pending 必須保留。
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertTrue(vm.state.value.msg.contains("清除將刪除未見內容"))
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertEquals(0, fake.clearCalls)

        // 第二次：重讀仍 Unknown→abort，不執行、不耗 clear；clear 起始即清 pending
        // （P2 #6，作廢確認），故 pending 為 null（J3）。
        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
    }

    // TOCTOU (#2):慢速 refresh 載入在途 → 成功 save 先提交 → 釋放舊載入不得覆寫。
    // Stage F1 誠實化：save 落盤更新 backend；call3+（快徑 read-after-ack 以
    // 後）回 backend 新值（WRITE_SUCCESS），阻塞舊載入仍回 stale OLD 以演練丟棄。
    @Test
    fun refreshPolicyDraft_saveDuringSlowRefresh_staleRefreshDoesNotOverwriteSavedDraft() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val aStarted = java.util.concurrent.CountDownLatch(1)
        val aFinished = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.atomic.AtomicBoolean(false)
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.INIT"))
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    1 -> backend.get()
                    2 -> {
                        aStarted.countDown()
                        while (!releaseA.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        aFinished.countDown()
                        setOf("com.OLD")
                    }
                    else -> backend.get()
                }
            },
            saveRaw = { raw ->
                if (raw != null) backend.set(raw)
                true
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.INIT"))
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.policyDraftKnown)

        // 慢速 refresh (A) 先發並阻塞在 loadRaw。
        vm.refreshPolicyDraft()
        assertTrue(aStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(2, calls.get())

        // 在途中有成功 save (draft=saved)。
        val pending = PendingPolicyWrite(setOf("com.SAVED"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(setOf("com.SAVED"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.policyDraftKnown)

        // 釋放晚到的 refresh；版本+世代皆已推進，不得覆寫 fresh Known。
        releaseA.set(true)
        assertTrue(aFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertEquals(setOf("com.SAVED"), vm.state.value.policyDraft)
            Thread.sleep(20)
        }
        assertTrue(vm.state.value.policyDraftKnown)
    }

    // VM↔binding 整合 (#3):真實 remoteHookPolicyStore + 最小 SharedPreferences 假實現。
    private class MinimalPrefs(var commitSucceeds: Boolean = true) : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        var commitCalls = 0
        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            val stored = data[key] as? Set<String>
            return stored?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MinimalEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        private inner class MinimalEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key!!] = values?.toMutableSet()
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun remove(key: String?): SharedPreferences.Editor {
                removed.add(key!!)
                pending.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor =
                throw UnsupportedOperationException()
            override fun commit(): Boolean {
                commitCalls++
                // 模擬 RemotePreferences 樂觀更新：先 flush 行程內快取再回布林。
                for (k in removed) data.remove(k)
                for ((k, v) in pending) data[k] = v
                pending.clear()
                removed.clear()
                return commitSucceeds
            }
            override fun apply() = throw UnsupportedOperationException()
        }
    }

    // VM↔binding 整合 (#3, Phase 2 兩步確認)：真實 store 鏈路 — commit 失敗 →
    // FAILED+unknown；未知門控零寫入；翻轉成功旗標後 blind clear 需兩步
    // （第一次告警、第二次執行；成功 clear 清 needsRefetch），再
    // refresh→Known→request+confirm 成功。
    @Test
    fun realStoreChain_failedSaveUnknownGateThenClearRecoveryAndSaveSucceeds() {
        val prefs = MinimalPrefs(commitSucceeds = false)
        val store = remoteHookPolicyStore { prefs }
        val vm = makeVm(store, userId = 0, initialDraft = emptySet())
        await { vm.state.value.policyDraftKnown }
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)

        // 1) commit-false save → FAILED + unknown。
        val pending1 = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending1)
        val commitsBefore = prefs.commitCalls
        vm.confirmPolicyWrite(pending1)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(commitsBefore + 1, prefs.commitCalls)

        // 2) 未知門控拒絕第二次 confirm，零額外 save 效果。
        val pending2 = PendingPolicyWrite(setOf("com.example.b"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending2)
        val commitsBeforeSecond = prefs.commitCalls
        vm.confirmPolicyWrite(pending2)
        await { vm.state.value.pendingPolicyWrite == null }
        assertTrue(vm.state.value.msg.contains("未知"))
        assertEquals(commitsBeforeSecond, prefs.commitCalls)
        assertFalse(vm.state.value.policyDraftKnown)

        // 3) Stage J3:翻轉成功旗標後，blind clear 仍不得刪未見內容——同實例毒旗標下
        //    重讀仍 Unknown→abort（REFRESH_FAILED＋Unknown＋0 commit），不恢復 sticky。
        //    （明確刪除亦須 Known；毒旗標須經新身份穿透或成功寫入恢復，同實例不清。）
        prefs.commitSucceeds = true
        val commitsBeforeClear = prefs.commitCalls
        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertTrue(vm.state.value.msg.contains("清除將刪除未見內容"))
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(commitsBeforeClear, prefs.commitCalls)
        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(commitsBeforeClear, prefs.commitCalls)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)

        // 4) 新身份恢復（DEVICE-UNVERIFIED 實驗路徑：第 1 層穿透）：以乾淨 prefs
        //    新建 VM，refresh 即 Known，後續 request+confirm 成功（真實 store→VM 鏈）。
        val cleanPrefs = MinimalPrefs(commitSucceeds = true)
        val cleanStore = remoteHookPolicyStore { cleanPrefs }
        val vm2 = makeVm(cleanStore, userId = 0, initialDraft = emptySet())
        await { vm2.state.value.policyDraftKnown }
        vm2.refreshPolicyDraft()
        await { vm2.state.value.policyDraftKnown }

        // 已知後 request+confirm 成功 (真實 store→VM 鏈)。
        seedSelection(vm2)
        vm2.requestPolicyWrite()
        val pending3 = vm2.state.value.pendingPolicyWrite
        assertNotNull(pending3)
        vm2.confirmPolicyWrite(pending3!!)
        await { vm2.state.value.pendingPolicyWrite == null }
        assertEquals(setOf("com.example.a"), vm2.state.value.policyDraft)
        assertTrue(vm2.state.value.policyDraftKnown)
        assertTrue(vm2.state.value.msg.contains("已寫入政策草稿"))
        // 持久化經真實綁定落到同一 prefs 實例。
        assertEquals(
            setOf("com.example.a"),
            cleanPrefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)?.toSet(),
        )
    }

    // Phase 1 fail-closed fence：寫入失敗後的 Unknown 必須作廢在途舊 load，
    // 晚到的 Known 不得把 Unknown 蓋回（保留失敗前快照）。
    // 未修復碼：confirm-fail 不 bump，晚到 load 直接覆寫 → Known==true 而 FAIL。
    @Test
    fun failedSave_thenLateKnownLoad_mustStayUnknown() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val aStarted = java.util.concurrent.CountDownLatch(1)
        val aFinished = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    1 -> setOf("com.INIT")
                    2 -> {
                        aStarted.countDown()
                        while (!releaseA.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        aFinished.countDown()
                        setOf("com.LATE")
                    }
                    else -> setOf("com.LATE")
                }
            },
            saveRaw = { _ ->
                saveCalls.incrementAndGet()
                false
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.INIT"))
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)

        // 舊 load 先發並阻塞在 loadRaw。
        vm.refreshPolicyDraft()
        assertTrue(aStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 在途中有寫入失敗：FAILED + Unknown（保留失敗前快照）。
        val pending = PendingPolicyWrite(setOf("com.NEW"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)
        assertEquals(1, saveCalls.get())

        // 釋放晚到的 Known load；fence 必須將其丟棄（穩定窗口斷言）。
        releaseA.set(true)
        assertTrue(aFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)
            Thread.sleep(20)
        }
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(1, saveCalls.get())
    }

    // Phase 1 fail-closed fence（clear 路徑）：清除失敗後的 Unknown 同樣必須
    // 作廢在途舊 load。未修復碼：clear-fail 不 bump，晚到 load 蓋回 Known 而 FAIL。
    @Test
    fun failedClear_thenLateKnownLoad_mustStayUnknown() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val aStarted = java.util.concurrent.CountDownLatch(1)
        val aFinished = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    1 -> setOf("com.INIT")
                    2 -> {
                        aStarted.countDown()
                        while (!releaseA.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        aFinished.countDown()
                        setOf("com.LATE")
                    }
                    else -> setOf("com.LATE")
                }
            },
            saveRaw = { raw ->
                if (raw == null) {
                    clearCalls.incrementAndGet()
                    false
                } else {
                    true
                }
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.INIT"))
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)

        // 舊 load 先發並阻塞在 loadRaw。
        vm.refreshPolicyDraft()
        assertTrue(aStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 在途中有清除失敗：FAILED + Unknown（保留失敗前快照）。
        vm.clearPolicyDraft()
        await { vm.state.value.msgKind == MsgKind.FAILED }
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)
        assertEquals(1, clearCalls.get())

        // 釋放晚到的 Known load；fence 必須將其丟棄（穩定窗口斷言）。
        releaseA.set(true)
        assertTrue(aFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)
            Thread.sleep(20)
        }
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(1, clearCalls.get())
    }

    // Phase 1 fail-closed fence（unknown-gate 路徑）：saved=null 冷啟 Unknown 後，
    // 阻塞式 refresh 在途時 confirm 觸 unknown-gate 重申 Unknown（CHANNEL、不碰
    // store），釋放晚到的 Known load 必須被丟棄，終態仍為 Unknown 且 draft 保留。
    // 未修復碼：unknown-gate 不 bump，晚到 load 直接覆寫 → Known==true 而 FAIL。
    @Test
    fun unknownGate_thenLateKnownLoad_mustStayUnknown() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val aStarted = java.util.concurrent.CountDownLatch(1)
        val aFinished = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    // saved=null：冷啟 init 即 Unknown。
                    1 -> null
                    2 -> {
                        aStarted.countDown()
                        while (!releaseA.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        aFinished.countDown()
                        setOf("com.LATE")
                    }
                    else -> setOf("com.LATE")
                }
            },
            saveRaw = { _ ->
                saveCalls.incrementAndGet()
                true
            },
        )
        // saved=null 冷啟：init load 即 Unknown，draft 留空。
        val vm = makeVm(store, userId = 0, initialDraft = null)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)

        // 阻塞式 refresh 在途（舊 load 先發並卡在 loadRaw）。
        vm.refreshPolicyDraft()
        assertTrue(aStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // confirm 觸 unknown-gate：CHANNEL + Unknown，不碰 store。
        val pending = PendingPolicyWrite(setOf("com.NEW"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
        assertEquals(0, saveCalls.get())

        // 釋放晚到的 Known load；fence 必須將其丟棄（穩定窗口斷言）。
        releaseA.set(true)
        assertTrue(aFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(emptySet<String>(), vm.state.value.policyDraft)
            Thread.sleep(20)
        }
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(0, saveCalls.get())
    }

    // Phase 1 非對稱鎖定：慢速 Unknown load 在途時成功 save NEW，釋放後終態仍為
    // Known NEW（load 結果本身的 Unknown 不得 bump 丟棄新寫入；寫入成功側的 bump
    // 作廢舊 load，晚到的 Unknown 被丟棄）。
    // Stage J3：快徑 ack 須 Known（Unknown 永不信 ack）。本測試 harness 以 backend
    // 使 ackRead 回 Known NEW（SUCCESS），慢速 Unknown 仍被丟棄，保原意圖。
    @Test
    fun slowUnknownLoad_doesNotDiscardNewerKnown() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val aStarted = java.util.concurrent.CountDownLatch(1)
        val aFinished = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.INIT"))
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    1 -> setOf("com.INIT")
                    2 -> {
                        aStarted.countDown()
                        while (!releaseA.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        aFinished.countDown()
                        null
                    }
                    // Stage J3:ackRead 及後續讀回 backend（save 已更新為 NEW），
                    // 使快徑以 ground truth 驗證 SUCCESS，而非依賴 Unknown 信 ack。
                    else -> backend.get()
                }
            },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw != null) backend.set(raw)
                true
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.INIT"))
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)

        // 慢速 Unknown load 先發並阻塞在 loadRaw。
        vm.refreshPolicyDraft()
        assertTrue(aStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 在途中有寫入成功：Known NEW。
        val pending = PendingPolicyWrite(setOf("com.NEW"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.NEW"), vm.state.value.policyDraft)
        assertEquals(1, saveCalls.get())

        // 釋放晚到的 Unknown load；必須被丟棄（穩定窗口斷言仍為 Known NEW）。
        releaseA.set(true)
        assertTrue(aFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.NEW"), vm.state.value.policyDraft)
            Thread.sleep(20)
        }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(1, saveCalls.get())
    }

    // Phase 2 VM：throw-provider transient（不設旗標）→ 恢復後 refresh 即 Known
    // (old) → request+confirm 直接成功無需 clear（與 1215 sticky 腿對照：該腿為
    // commit-false 同實例，需 blind clear 兩步；本腿 load-only 恢復，無需 clear）。
    @Test
    fun throwProviderTransient_refreshRecoversThenSaveSucceedsWithoutClear() {
        val prefs = MinimalPrefs(commitSucceeds = true)
        prefs.data[PolicySource.POLICY_KEY_EXTRA_PACKAGES] = setOf("com.remote")
        var throwOnce = true
        val store = remoteHookPolicyStore {
            if (throwOnce) throw RuntimeException("binder dead") else prefs
        }
        // 首載 provider 拋出 → Unknown（冷啟）。
        val vm = makeVm(store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        // provider 恢復後手動重新整理即 Known（舊值），可觀測位最終回 false。
        throwOnce = false
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.remote"), vm.state.value.policyDraft)
        await { !vm.state.value.policyRefreshing }

        // 已知後 request+confirm 直接成功，無需 clear。
        seedSelection(vm)
        vm.requestPolicyWrite()
        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        vm.confirmPolicyWrite(pending!!)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
        assertEquals(
            setOf("com.example.a"),
            prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)?.toSet(),
        )
    }

    // Phase 2 VM：failed-clear 重試恢復（clear-fail → Unknown → 重試 clear 成功
    // → Known 空）。重試需經兩步確認（第一次告警不耗 store，第二次執行）。
    @Test
    fun failedClear_retrySucceedsAfterDoubleConfirm() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0, initialDraft = setOf("com.seed"))
        await { vm.state.value.policyDraftKnown }

        // 已知下單步清除但底層失敗 → FAILED + Unknown。
        fake.clearSucceeds = false
        vm.clearPolicyDraft()
        await { vm.state.value.msgKind == MsgKind.FAILED }
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(1, fake.clearCalls)

        // 重試：第一次僅告警（不耗 store），第二次執行成功。
        fake.clearSucceeds = true
        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(1, fake.clearCalls)

        vm.clearPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已清除政策草稿"))
        assertEquals(2, fake.clearCalls)
    }

    // Phase 2 VM：手動重新整理失敗可觀測（Unknown + CHANNEL 一次 + refreshing 回位）。
    @Test
    fun refreshPolicyDraft_unknownFailure_reportsChannelAndClearsRefreshing() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0, initialDraft = setOf("com.seed"))
        await { vm.state.value.policyDraftKnown }

        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }

        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("重新整理"))
        await { !vm.state.value.policyRefreshing }
        assertFalse(vm.state.value.policyRefreshing)
    }

    // Critical 3：stale-arm 洩漏迴歸——arm→Known→Unknown→單次 clear 必須僅告警。
    // Known 成功路徑（refresh/confirm/clear）須同步 disarm，否則舊 arm 殘留會讓
    // 下一次 Unknown 的單次 clear 直接執行（靜默刪除未見內容）。
    @Test
    fun staleArmClearedByKnown_singleClearAfterReUnknownOnlyWarns() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        // Unknown 下第一次 clear 僅 arm（不耗 store）。
        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)

        // refresh Known 成功必須 disarm。
        fake.saved = setOf("com.remote")
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.remote"), vm.state.value.policyDraft)

        // 再次 Unknown（遠端又不可讀）。
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }

        // 單次 clear 必須僅告警（arm 已被 Known 清掉），不得執行。
        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertTrue(vm.state.value.msg.contains("清除將刪除未見內容"))
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // Required 4：疊加 refresh——A 丟棄、B 生效、計數器最後清位。
    @Test
    fun overlappingRefresh_staleFirstDiscardedAndCounterCleared() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val aStarted = java.util.concurrent.CountDownLatch(1)
        val aFinished = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                when (calls.incrementAndGet()) {
                    1 -> setOf("com.INIT")
                    2 -> {
                        aStarted.countDown()
                        while (!releaseA.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        aFinished.countDown()
                        setOf("com.OLD")
                    }
                    else -> setOf("com.NEW")
                }
            },
            saveRaw = { _ -> true },
        )
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.INIT"))
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.INIT"), vm.state.value.policyDraft)

        // A 先發並阻塞。
        vm.refreshPolicyDraft()
        assertTrue(aStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await { vm.state.value.policyRefreshing }

        // B 後發但快速完成，先發布較新值。
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraft == setOf("com.NEW") }
        assertTrue(vm.state.value.policyDraftKnown)
        // B 完成但 A 仍在途：計數器未歸零，refreshing 仍 true。
        assertTrue(vm.state.value.policyRefreshing)

        // 釋放晚到的 A；世代已變不得覆寫 B，且最後完成者清位。
        releaseA.set(true)
        assertTrue(aFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await { !vm.state.value.policyRefreshing }
        assertFalse(vm.state.value.policyRefreshing)
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertEquals(setOf("com.NEW"), vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyRefreshing)
            Thread.sleep(20)
        }
        assertTrue(vm.state.value.policyDraftKnown)
    }

    // Required 4：refresh Known 成功洗掉舊 FAILED/CHANNEL（免舊錯誤洗屏）。
    @Test
    fun refreshKnown_washesFailedAndChannelMsg() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0, initialDraft = setOf("com.seed"))
        await { vm.state.value.policyDraftKnown }

        // 先製造 FAILED：save 失敗 → Unknown + FAILED。
        fake.saveSucceeds = false
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        fake.saveSucceeds = true

        // 底層仍為舊 seed（save 失敗未覆寫）：refresh Known 必須洗掉 FAILED。
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已重新整理"))
        await { !vm.state.value.policyRefreshing }

        // 再製造 CHANNEL：底層清空 → refresh Unknown + CHANNEL。
        fake.saved = null
        vm.refreshPolicyDraft()
        await { !vm.state.value.policyDraftKnown }
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)

        // 底層恢復 → refresh Known 必須洗掉 CHANNEL。
        fake.saved = setOf("com.remote")
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.remote"), vm.state.value.policyDraft)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已重新整理"))
        await { !vm.state.value.policyRefreshing }
    }

    // Stage B P2 dialog 完整清單（方案 A Toggle）：截斷邊界 51/exact50＋算術。
    @Test
    fun stageB_truncate_boundaries_51_exact50_arithmetic() {
        // 51 項：顯示前 50，溢出 1（Toggle 出現條件 rest>0）。
        val sorted51 = (1..51).map { "com.example.%03d".format(it) }
        val (shown51, rest51) = truncatePolicyPreview(sorted51)
        assertEquals(50, shown51.size)
        assertEquals(sorted51.take(50), shown51)
        assertEquals(1, rest51)
        assertEquals(sorted51.size - shown51.size, rest51)
        // 可見列：收合走截斷，展開走完整（rest=0）。
        val (collapsedShown, collapsedRest) = policyWriteVisibleList(sorted51, false)
        assertEquals(shown51, collapsedShown)
        assertEquals(1, collapsedRest)
        val (expandedShown, expandedRest) = policyWriteVisibleList(sorted51, true)
        assertEquals(sorted51, expandedShown)
        assertEquals(0, expandedRest)

        // exact50：無溢出（Toggle 隱藏條件 rest==0）。
        val sorted50 = (1..50).map { "com.example.%03d".format(it) }
        val (shown50, rest50) = truncatePolicyPreview(sorted50)
        assertEquals(50, shown50.size)
        assertEquals(0, rest50)
        assertEquals(sorted50.size - shown50.size, rest50)
        val (collapsed50, collapsedRest50) = policyWriteVisibleList(sorted50, false)
        assertEquals(sorted50, collapsed50)
        assertEquals(0, collapsedRest50)

        // 算術：200 項＝50＋150（與 Phase5 200 鎖定一致，溢出行計數不斷言資源文案）。
        val sorted200 = (1..200).map { "com.example.%03d".format(it) }
        val (shown200, rest200) = truncatePolicyPreview(sorted200)
        assertEquals(POLICY_DIALOG_PREVIEW_MAX, shown200.size)
        assertEquals(150, rest200)
        assertEquals(sorted200.size - shown200.size, rest200)
    }

    // Stage B P2：maxHeight 分檔（小螢幕 SMALL，否則 NORMAL；非法 fail-closed SMALL）。
    @Test
    fun stageB_maxHeight_buckets_small_normal_invalid() {
        val small = policyDialogListMaxHeightDp(320, 800)
        val normal = policyDialogListMaxHeightDp(400, 800)
        // SMALL 必須小於 NORMAL（ack 在小螢幕仍可見）。
        assertTrue(small < normal)
        assertEquals(240, small)
        assertEquals(320, normal)
        // 邊界：<360 或 <640 即 SMALL；(360,640) 恰為 NORMAL 下限。
        assertEquals(240, policyDialogListMaxHeightDp(359, 800))
        assertEquals(240, policyDialogListMaxHeightDp(400, 639))
        assertEquals(320, policyDialogListMaxHeightDp(360, 640))
        assertEquals(320, policyDialogListMaxHeightDp(411, 900))
        // 非法輸入 fail-closed 回 SMALL（保守限高，不擠出 ack）。
        assertEquals(240, policyDialogListMaxHeightDp(0, 0))
        assertEquals(240, policyDialogListMaxHeightDp(-1, 800))
        assertEquals(240, policyDialogListMaxHeightDp(400, -10))
    }

    // Stage B P2：expanded 世代重置契約（同 ack：stored != pending 即收合）。
    @Test
    fun stageB_expandedReset_generationContract() {
        assertTrue(policyWriteExpandedShouldReset(null, 1L))
        assertTrue(policyWriteExpandedShouldReset(1L, 2L))
        assertFalse(policyWriteExpandedShouldReset(2L, 2L))
        // 與 ack 契約同構（防殘留污染下一世代，同世代旋轉保留）。
        assertEquals(
            policyAckShouldReset(null, 1L),
            policyWriteExpandedShouldReset(null, 1L),
        )
        assertEquals(
            policyAckShouldReset(2L, 2L),
            policyWriteExpandedShouldReset(2L, 2L),
        )
    }

    // Stage B P2：CD/文案 fallback 含計數（資源缺失時中文兜底不斷言資源文案本身）。
    @Test
    fun stageB_listCd_toggle_fallback_containsCounts() {
        // 收合 CD 含總數＋預覽數；展開 CD 僅含總數。
        val collapsedCd = policyWriteListCdFallback(total = 51, shown = 50, expanded = false)
        assertTrue(collapsedCd.contains("51"))
        assertTrue(collapsedCd.contains("50"))
        val expandedCd = policyWriteListCdFallback(total = 51, shown = 51, expanded = true)
        assertTrue(expandedCd.contains("51"))
        // 無溢出時即使 expanded=false 亦回完整 CD（total<=shown）。
        val noOverflowCd = policyWriteListCdFallback(total = 10, shown = 10, expanded = false)
        assertTrue(noOverflowCd.contains("10"))
        assertFalse(noOverflowCd.contains("僅顯示前"))
        // Toggle 文案：收合鈕含總數，展開鈕含預覽數；播報同式。
        val showAll = policyWriteToggleLabelFallback(expanded = false, total = 51, shown = 50)
        assertTrue(showAll.contains("51"))
        val showLess = policyWriteToggleLabelFallback(expanded = true, total = 51, shown = 50)
        assertTrue(showLess.contains("50"))
        val expandedAnnounce = policyWriteToggleAnnounceFallback(expanded = true, total = 51, shown = 50)
        assertTrue(expandedAnnounce.contains("51"))
        val collapsedAnnounce = policyWriteToggleAnnounceFallback(expanded = false, total = 51, shown = 50)
        assertTrue(collapsedAnnounce.contains("50"))
    }

    // Phase 3：dialog 純門控（無需 Robolectric；鎖定 syncPolicyWriteDialog 語義）。
    @Test
    fun phase3_policyDialogGating_pureFunctions() {
        // positive：僅非 saving＋已 ack 才允許（SAVING 禁用防重送；QUEUED 可確認，
        // 避免 request 即 QUEUED 導致恆禁用死鎖）。
        assertTrue(policyDialogCanConfirm(policySaving = false, acknowledged = true))
        assertFalse(policyDialogCanConfirm(policySaving = true, acknowledged = true))
        assertFalse(policyDialogCanConfirm(policySaving = false, acknowledged = false))
        assertFalse(policyDialogCanConfirm(policySaving = true, acknowledged = false))
        // negative/Back：僅 saving 禁止（queued 仍可取消，P1 #1 語義）。
        assertTrue(policyDialogShouldBeCancelable(policySaving = false))
        assertFalse(policyDialogShouldBeCancelable(policySaving = true))
        // ack 世代：跨世代重置（含首世代 null），同世代保留（防殘留污染下一世代）。
        assertTrue(policyAckShouldReset(null, 1L))
        assertTrue(policyAckShouldReset(1L, 2L))
        assertFalse(policyAckShouldReset(2L, 2L))
        // busy 後綴三態：idle 無後綴，queued 等待中，saving 寫入中。
        assertEquals("", policyWriteBusySuffix(policyOp = false, policySaving = false))
        assertTrue(policyWriteBusySuffix(policyOp = true, policySaving = false).contains("等待"))
        assertTrue(policyWriteBusySuffix(policyOp = true, policySaving = true).contains("寫入"))
    }

    // Stage 3 P0 死鎖回歸：request 建 pending 即 QUEUED，dialog positive 必須仍可確認
    //（門控讀 SAVING 而非任一 op 在途；QUEUED＋ack 即 true，SAVING＋ack 才 false）。
    @Test
    fun stage3_queuedDialogCanConfirm_noDeadlock() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        seedSelection(vm)

        vm.requestPolicyWrite()

        assertNotNull(vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        // render 派生：QUEUED 即 policySaving=false，positive 門控必須放行。
        val savingAfterRequest = vm.state.value.policyWritePhase == PolicyWritePhase.SAVING
        assertFalse(savingAfterRequest)
        assertTrue(policyDialogCanConfirm(policySaving = savingAfterRequest, acknowledged = true))
        // SAVING 才禁用；未勾選一律禁用。
        assertFalse(policyDialogCanConfirm(policySaving = true, acknowledged = true))
        assertFalse(policyDialogCanConfirm(policySaving = savingAfterRequest, acknowledged = false))
    }

    // Phase 3：request 建 pending 即 QUEUED＋epoch 推進；cancel 回 IDLE。
    @Test
    fun phase3_requestQueued_cancelIdle() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        seedSelection(vm)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        val epochBefore = vm.state.value.policyEpoch

        vm.requestPolicyWrite()

        val pending = vm.state.value.pendingPolicyWrite
        assertNotNull(pending)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        assertTrue(vm.state.value.policyEpoch > epochBefore)

        vm.cancelPolicyWrite(pending!!)

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
    }

    // Phase 3：SAVING 中取消為 no-op（Back/取消禁用，pending 保留，可重試）。
    @Test
    fun phase3_savingCancel_isNoOp() {
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakePolicyStore()
        val blockingStore = HookPolicyStore(
            loadRaw = { fake.saved },
            saveRaw = { raw ->
                fake.saveCalls++
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                fake.saved = raw
                true
            },
        )
        val vm = makeVm(blockingStore, userId = 0)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await { vm.state.value.policyWritePhase == PolicyWritePhase.SAVING }
        assertTrue(vm.state.value.loading)

        // saving 中取消/Back 一律禁用：cancel 為 no-op，pending 同一實例保留。
        assertFalse(policyDialogShouldBeCancelable(policySaving = true))
        vm.cancelPolicyWrite(pending)
        assertSame(pending, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.SAVING, vm.state.value.policyWritePhase)

        saveRelease.set(true)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertFalse(vm.state.value.loading)
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
    }

    // Phase 3：blocked request 保留 dialog（pending 同一實例、op 在途）。
    @Test
    fun phase3_blockedRequest_retainsSamePendingInstance() {
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakePolicyStore()
        val blockingStore = HookPolicyStore(
            loadRaw = { fake.saved },
            saveRaw = { raw ->
                fake.saveCalls++
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                fake.saved = raw
                true
            },
        )
        val vm = makeVm(blockingStore, userId = 0)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val live = vm.state.value.pendingPolicyWrite
        assertNotNull(live)

        // 寫入進行中 request 被拒：pending 同一實例保留（dialog 不關）。
        vm.requestPolicyWrite()

        assertSame(live, vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyWritePhase != PolicyWritePhase.IDLE)
        assertEquals("政策寫入進行中，請稍候", vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)

        saveRelease.set(true)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
    }

    // Phase 3：catalog 空守衛（與「勾選已不在清單」區分，免誤導重選）。
    @Test
    fun phase3_requestPolicyWrite_emptyCatalog_reportsNotLoaded() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        // catalog 仍空（預設），直接勾選。
        vm.toggleSelection("0:com.example.a")

        vm.requestPolicyWrite()

        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.msg.contains("來源清單尚未載入"))
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertEquals(0, fake.saveCalls)
    }

    // Phase 3：legacy 無冒號 key 走 byPackageFirst（與 restoreSelectionLocked 一致）。
    @Test
    fun phase3_requestPolicyWrite_legacyKeyWithoutColon_mapsByPackage() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("com.example.a")

        vm.requestPolicyWrite()

        assertEquals(setOf("com.example.a"), vm.state.value.pendingPolicyWrite?.packages)
        assertEquals(0, vm.state.value.pendingPolicyWrite?.droppedCount)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        assertEquals(0, fake.saveCalls)
    }

    // Phase 3：TTL 過期不碰 loading（sibling busy 守衛；本 op 未持有 loading）。
    @Test
    fun phase3_ttlExpired_preservesSiblingLoading() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0, nowMonoMs = { 10_000L })
        val pending = PendingPolicyWrite(
            setOf("com.example.a"),
            10_000L - DualViewModel.POLICY_CONFIRM_TTL_MS - 1,
        )
        setPendingPolicyWrite(vm, pending)
        // 模擬 sibling（refresh）持有 busy。
        setLoading(vm, true)

        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.loading)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertTrue(vm.state.value.msg.contains("確認已逾時"))
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(0, fake.saveCalls)
    }

    // Phase 3：世代單調（request→cancel→request 跨世代，舊世代永不復活）。
    @Test
    fun phase3_generations_monotonicAcrossRequests() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        seedSelection(vm)

        vm.requestPolicyWrite()
        val first = vm.state.value.pendingPolicyWrite
        assertNotNull(first)
        vm.cancelPolicyWrite(first!!)

        vm.requestPolicyWrite()
        val second = vm.state.value.pendingPolicyWrite
        assertNotNull(second)

        assertTrue(second!!.generation != first.generation)
        assertTrue(second.generation > first.generation)
    }

    // Phase 3 helper：直接置放全域 loading（模擬 sibling holder 持有 busy）。
    private fun setLoading(vm: DualViewModel, loading: Boolean) {
        val field = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<UiState>
        state.value = state.value.copy(loading = loading)
    }

    // Phase 5 (P2-5)：rapid double-clear inadmissible——第一次 clear 在途時
    // 第二次 clear 必須被拒（「政策寫入進行中，請稍候」INFO），且底層只執行一次。
    @Test
    fun clearPolicyDraft_rapidDoubleClear_secondIsInadmissible() {
        val clearStarted = java.util.concurrent.CountDownLatch(1)
        val clearRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakePolicyStore()
        val blockingStore = HookPolicyStore(
            loadRaw = { fake.saved },
            saveRaw = { raw ->
                if (raw == null) {
                    fake.clearCalls++
                    clearStarted.countDown()
                    while (!clearRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    // Stage J3:成功清除後端為確認空（Known empty），使 ackRead 以
                    // ground truth 驗證 CLEAR_SUCCESS（Unknown 永不信 ack）。
                    fake.saved = emptySet()
                    true
                } else {
                    fake.saveCalls++
                    fake.saved = raw
                    true
                }
            },
        )
        val vm = makeVm(blockingStore, userId = 0)

        vm.clearPolicyDraft()
        assertTrue(clearStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 第二次 clear 在途到達：拒絕，不排隊，不耗第二次底層呼叫。
        vm.clearPolicyDraft()
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(1, fake.clearCalls)

        clearRelease.set(true)
        await { vm.state.value.policyDraft.isEmpty() }
        assertEquals(1, fake.clearCalls)
        assertTrue(vm.state.value.policyDraftKnown)
    }

    // Phase 5 i18n：policyText 注入 single source——VM 訊息經注入解析，
    // 預設繁中 defaultText，注入假值即改寫（生產 Context 解析同鍵資源）。
    @Test
    fun policyText_injection_usedForMessages() {
        val fake = FakePolicyStore()
        val vm = makeVm(
            fake.store,
            userId = 0,
            policyText = { pt -> "INJECTED:${pt.kind.name}:${pt.count}" },
        )
        // 空勾選觸發 EMPTY_SELECTION。
        vm.requestPolicyWrite()
        assertEquals("INJECTED:EMPTY_SELECTION:0", vm.state.value.msg)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)

        // dropped 計數經 count 傳遞。
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.a", "A", 0, false)))
        vm.toggleSelection("0:com.example.a")
        vm.toggleSelection("0:com.example.missing")
        vm.requestPolicyWrite()
        assertEquals("INJECTED:DROPPED:1", vm.state.value.msg)
        assertEquals(setOf("com.example.a"), vm.state.value.pendingPolicyWrite?.packages)
    }

    // Phase 5 dialog：包名預覽僅渲染前 50 項＋溢出數（200 項不擠出 ack）。
    @Test
    fun truncatePolicyPreview_truncatesAtFiftyWithOverflow() {
        val sorted = (1..200).map { "com.example.%03d".format(it) }
        val (shown, rest) = truncatePolicyPreview(sorted)
        assertEquals(POLICY_DIALOG_PREVIEW_MAX, shown.size)
        assertEquals(sorted.take(50), shown)
        assertEquals(150, rest)

        val (shownSmall, restSmall) = truncatePolicyPreview(listOf("a", "b"))
        assertEquals(listOf("a", "b"), shownSmall)
        assertEquals(0, restSmall)
    }

    // Phase 5：request 建 pending 前先消毒——非法文法包名計入 dropped，
    // pending 僅含合法集合（顯示數與寫入數一致）。
    @Test
    fun requestPolicyWrite_sanitizesBeforePending_invalidFiltered() {
        val fake = FakePolicyStore()
        val vm = makeVm(fake.store, userId = 0)
        vm.setSourceCatalog(
            listOf(SourceAppEntry("bad pkg", "Bad", 0, false)),
        )
        vm.toggleSelection("0:bad pkg")

        vm.requestPolicyWrite()

        assertNull(vm.state.value.pendingPolicyWrite)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(0, fake.saveCalls)
    }

    // 反射讀核對中/在途集合（Stage A review 補測：斷言無殘留）。
    private fun reconcilingKeysOf(vm: DualViewModel): Set<String> {
        val f = DualViewModel::class.java.getDeclaredField("policyReconciling").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(vm) as MutableSet<String>).toSet()
    }

    private fun inFlightKeysOf(vm: DualViewModel): Set<String> {
        val f = DualViewModel::class.java.getDeclaredField("inFlightWrites").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(vm) as MutableSet<String>).toSet()
    }

    private fun savingKeysOf(vm: DualViewModel): Set<String> {
        val f = DualViewModel::class.java.getDeclaredField("policyWriteSaving").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(vm) as MutableSet<String>).toSet()
    }

    // Stage G1 (T3)：refresh 期中阻塞保留 fence——RECONCILING 期間 refresh 只
    // yielding（不推 ownership），reconciler 晚到 stale 讀不得丟棄至 IDLE，
    // 須保留 fence（RECONCILING＋pending＋Tx 存活＋Known=false）。
    // 定序：save 阻塞觸逾時→RECONCILING；reconciler 首延 500ms 空窗內 refresh
    // 先讀到 FRESH 並 yielding（draft=FRESH、Known=false，不碰 phase/pending）；
    // 再放行 reconciler 讀到 stale OLD（Known、deferred 未完成→stale 續留，
    // 不得 mismatch-settle）；終態續留 RECONCILING（REFRESH_FAILED＋pending
    // 保留＋Tx 存活），穩定窗口斷言；放行 save 後 refresh late-join 結清 IDLE。
    @Test
    fun reconcilerStalePublish_discardedWhenRefreshAdvancesGeneration() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val reconcilerStarted = java.util.concurrent.CountDownLatch(1)
        val reconcilerFinished = java.util.concurrent.CountDownLatch(1)
        val reconcilerRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                when (val n = calls.incrementAndGet()) {
                    1 -> backend.get()
                    // call 2：空窗內的 refresh 即時讀（FRESH），不阻塞。
                    2 -> backend.get()
                    // calls 3-5：reconciler 三輪晚到讀 stale OLD（Known），
                    // 首輪阻塞待放行（覆蓋 500ms 首延＋三輪間隔），後續即時。
                    3 -> {
                        reconcilerStarted.countDown()
                        while (!reconcilerRelease.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        reconcilerFinished.countDown()
                        setOf("com.stale")
                    }
                    4, 5 -> setOf("com.stale")
                    // call 6+：清理期 refresh late-join 讀權威後端（B）。
                    else -> backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        // 等 reconciler 交接完成（外層 finally 在 mutex 釋放後啟動，取號先於
        // refresh），仍在 500ms 首延空窗內，再做 refresh（只推 freshness）。
        Thread.sleep(200)
        backend.set(setOf("com.fresh"))
        val msgBeforeYield = vm.state.value.msg
        val draftBeforeYield = vm.state.value.policyDraft
        vm.refreshPolicyDraft()
        // Stage H2 yielding 誠實化：Tx 存活＋deferred 未完成→不更新 draft（保留舊
        // 快照），只設 Known=false，不碰 phase/pending，不 disarm，不洗 msg；
        // 生產穿透分支同視 Unverified。
        await(timeoutMs = 10_000L) { !vm.state.value.policyDraftKnown }
        assertEquals(draftBeforeYield, vm.state.value.policyDraft)
        assertEquals(msgBeforeYield, vm.state.value.msg)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertNotNull(activeTxOf(vm))
        assertTrue(reconcilerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 放行晚到的 stale 讀（三輪皆 deferred 未完成＋Known→inconclusive 續留，
        // 不得 mismatch-settle）；終態續留 RECONCILING（保留 fence），不得回 IDLE。
        reconcilerRelease.set(true)
        assertTrue(reconcilerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // 三輪＋退避約 2.5s 後達 unresolved（REFRESH_FAILED＋RECONCILING＋保留）。
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertEquals(draftBeforeYield, vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertEquals(pending, vm.state.value.pendingPolicyWrite)
            assertNotNull(activeTxOf(vm))
            Thread.sleep(20)
        }
        // 保留終態：續留 RECONCILING＋pending 保留＋Tx 存活＋reconciling 存活。
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertNotNull(activeTxOf(vm))
        await(timeoutMs = 5_000L) { reconcilingKeysOf(vm).isNotEmpty() }
        assertTrue(savingKeysOf(vm).isEmpty())

        // 清理：放行脫鉤慢 save（後端=B，deferred 完成 true），再 refresh late-join
        // 結清（讀 B==B→WRITE_SUCCESS＋IDLE＋Tx 結清）。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("com.example.a"), backend.get())
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
        assertNull(activeTxOf(vm))
        await(timeoutMs = 5_000L) { reconcilingKeysOf(vm).isEmpty() }

        // 結清後可新 request。
        seedSelection(vm)
        vm.requestPolicyWrite()
        val pending2 = vm.state.value.pendingPolicyWrite
        assertNotNull(pending2)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
    }

    // Stage A review (b) 全 Unknown 終態：load 恆 null（3 次重試皆 Unknown）→
    // IDLE＋Unknown＋REFRESH_FAILED/CHANNEL＋pending 清空＋reconciling 清空；
    // 之後 refresh Known 即可新 request（證明未卡 RECONCILING）。
    @Test
    fun reconcilerAllUnknown_reportsChannelRefreshFailedAndAllowsNewRequest() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        // 恆 Unknown：init 後即撤掉後端，後續 3 次重讀皆 null。
        backend.set(null)
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        // 3 次重試皆 Unknown（首延 500ms＋間隔 1s×2，10s 內必達終態）。
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(
            PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText(),
            vm.state.value.msg,
        )
        assertNull(vm.state.value.pendingPolicyWrite)
        // 重試至少 3 次：init 1＋reconciler 3（只取下界，自動重查可能追加讀數，
        // 精確等式在高負載下 flaky，不設上界；同 g1_T2 的 >=4 寫法）。
        assertTrue("load calls=" + calls.get() + ", expected >=4", calls.get() >= 4)
        assertTrue(reconcilingKeysOf(vm).isEmpty())
        assertTrue(savingKeysOf(vm).isEmpty())
        assertTrue(inFlightKeysOf(vm).none { it.startsWith("policy-") })

        // 清理脫鉤慢 save，再以 refresh 恢復 Known 證明可新 request。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        backend.set(setOf("com.remote"))
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.remote"), vm.state.value.policyDraft)

        seedSelection(vm)
        vm.requestPolicyWrite()
        val pending2 = vm.state.value.pendingPolicyWrite
        assertNotNull(pending2)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
    }

    // Stage G1 T1：blocked-B→Known(A)→試C/clear→放B（confirm/clear 變體同測）。
    // B 慢 save 阻塞觸逾時→RECONCILING＋Tx 存活＋pending(B)保留＋Unknown；
    // refresh 讀 Known(A=seed，deferred 未完成) 即 yielding（draft=A、Known=false，
    // phase 續留 RECONCILING、pending 保留、Tx 存活，不 settle、不 mismatch）；
    // 試 confirm C（異世代→靜默保留 TIMEOUT/REFRESHED，不耗 save，pending 續為 B，
    // Tx 同一）；試 clear（BUSY 拒絕，不耗 clear，pending 續留）；放行 B 後
    // join(true)＋驗證讀 B==B 即 WRITE_SUCCESS＋IDLE＋Tx 結清。
    @Test
    fun g1_T1_blockedB_knownA_tryC_clear_releaseB_succeeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet() else saveCalls.incrementAndGet()
                if (raw == null) {
                    // clear 在 RECONCILING 期間應被 BUSY 拒絕，不應走到此處；
                    // 若走到即 fail（回 false 使測試失敗，非靜默）。
                    assertTrue("clear must be BUSY-rejected during Tx", false)
                    false
                } else {
                    saveStarted.countDown()
                    while (!saveRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    if (raw != null) backend.set(raw)
                    saveFinished.countDown()
                    true
                }
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertNotNull(activeTxOf(vm))
        val txBefore = activeTxOf(vm)

        // Known(A)：refresh 讀 A（deferred 未完成）→yielding，不 settle。
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyDraft == setOf("com.A") }
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertSame(txBefore, activeTxOf(vm))
        assertEquals(1, saveCalls.get())

        // 試 confirm C（異世代→靜默保留，不耗 save，pending 續為 B，Tx 同一）。
        val pendingC = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
        val msgBeforeC = vm.state.value.msg
        vm.confirmPolicyWrite(pendingC)
        Thread.sleep(200)
        assertEquals(msgBeforeC, vm.state.value.msg)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(1, saveCalls.get())
        assertSame(txBefore, activeTxOf(vm))

        // 試 clear：BUSY 拒絕，不耗 clear，pending 續留。
        vm.clearPolicyDraft()
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(0, clearCalls.get())
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertSame(txBefore, activeTxOf(vm))

        // 放行 B：join(true)＋讀 B==B 即 WRITE_SUCCESS＋IDLE＋Tx 結清。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
        assertNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isEmpty())
        assertEquals(1, saveCalls.get())
    }

    // Stage G1 T2：樂觀B→doCommit fail（三輪 B 仍 RECONCILING，放行後 FAILED）。
    // save 阻塞（deferred 未完成），reconciler 三輪皆讀 Known B（樂觀污染，
    // ==sanitized）→永 inconclusive（刪末輪特例），不搶結，續留 RECONCILING＋
    // pending＋Tx 存活＋Known=false；放行 save（回 false，已證失敗）後 refresh
    // late-join（讀 A，deferred false）即 FAILED＋Unknown＋IDLE＋pending 清空＋
    // Tx 結清（樂觀讀 B 不得翻案）。
    @Test
    fun g1_T2_optimisticB_threeRoundsRetain_thenFailed() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val reads = java.util.Collections.synchronizedList(mutableListOf<Set<String>?>())
        val store = HookPolicyStore(
            // init 首讀回真值 A（Known）；之後未放行回樂觀 B（Known，
            // ==sanitized，deferred 未完成→inconclusive）；放行後讀權威
            // A（後端仍 A，save 回 false 未覆寫）。按狀態而非計數決定，
            // 避免輪詢抖動造成 flaky。
            loadRaw = {
                calls.incrementAndGet()
                // 首讀特判：reads 仍空即 init 載入。
                val v: Set<String>? = if (reads.isEmpty()) {
                    backend.get()
                } else if (!saveRelease.get()) {
                    setOf("com.B")
                } else {
                    backend.get()
                }
                reads.add(v)
                v
            },
            saveRaw = { _ ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                saveFinished.countDown()
                false
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        assertNotNull(activeTxOf(vm))

        // 三輪 B（首延 500ms＋間隔 1s×2，約 2.5s）仍 RECONCILING（不結至 IDLE＋
        // SUCCESS），pending 保留，Tx 存活，Known=false。
        // 等 unresolved（REFRESH_FAILED＋RECONCILING＋保留）即證明三輪未搶結。
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertNotNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isNotEmpty())
        // 三輪讀＋init 至少 4 次（reconciler 至少 3 讀，未提前結；只取下界，
        // 避免輪詢抖動造成 flaky，不設上界）。
        assertTrue("load calls=" + calls.get() + ", expected >=4", calls.get() >= 4)

        // 放行 save（回 false，已證失敗；後端不變仍 A），再 refresh late-join
        // （讀 A＋deferred false）即 FAILED＋Unknown＋IDLE＋pending 清空＋Tx 結清。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("com.A"), backend.get())
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("寫入政策失敗"))
        assertNull(activeTxOf(vm))
        await(timeoutMs = 5_000L) { reconcilingKeysOf(vm).isEmpty() }
    }

    // Stage G1 T4：stale-A reconciler 續留（deferred 未完成＋Known(A≠B)→不
    // mismatch-settle，視 stale 續留；放行後 SUCCESS）。
    // save 阻塞，reconciler 三輪皆讀 Known A（stale 舊值，!=sanitized B）→
    // 續留 RECONCILING（不得 REFRESHED 誤結）；放行 save（回 true，後端=B）後
    // refresh late-join（讀 B==B）即 WRITE_SUCCESS＋IDLE。
    @Test
    fun g1_T4_staleA_reconcilerRetains_thenSucceeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            // call 1：init 回 A；calls 2-4：reconciler 三輪讀 stale A（!=B）；
            // call 5+：清理期讀後端（B）。
            loadRaw = {
                when (calls.incrementAndGet()) {
                    1 -> backend.get()
                    2, 3, 4 -> setOf("com.A")
                    else -> backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        assertNotNull(activeTxOf(vm))

        // 三輪 stale A 仍 RECONCILING（不得 mismatch REFRESHED 誤結至 IDLE）。
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertNotNull(activeTxOf(vm))
        // 三輪讀＋init 至少 4 次（只取下界；索引分支在 calls>4 時走 else 回後端，
        // 斷言時後端仍為 A，與 stale A 同值，語義不變；避免自動重查抖動 flaky）。
        assertTrue("load calls=" + calls.get() + ", expected >=4", calls.get() >= 4)

        // 放行 save（回 true，後端=B），再 refresh late-join（讀 B==B）即 SUCCESS。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("com.B"), backend.get())
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
        assertNull(activeTxOf(vm))
    }

    // Stage J3 blind-clear：Unknown 下第二 tap 亦須重讀＋abort（永不刪未見內容）。
    // TTL 內第二 tap 因 store 仍 Unknown（null）而 abort，不耗 clear，需顯式 refresh
    // Known 後方可清除。
    @Test
    fun blindClear_secondTapWithinTtl_executes() {
        var now = 10_000L
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null, nowMonoMs = { now })
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)

        // Stage J3:第二 tap 重讀仍 Unknown→abort（REFRESH_FAILED＋Unknown＋IDLE），
        // 不耗 clear、不刪未見內容。
        now = 10_000L + DualViewModel.BLIND_CLEAR_TTL_MS - 1_000L
        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("重新整理"))

        // 顯式 refresh Known（遠端恢復為確認空）後，單步清除即成功。
        fake.saved = emptySet()
        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        vm.clearPolicyDraft()
        await(timeoutMs = 5_000L) { fake.clearCalls == 1 }
        assertEquals(1, fake.clearCalls)
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已清除政策草稿"))
    }

    // Stage D：延遲 tap（超過 TTL）→重 arm＋EXPIRED 文案，不執行。
    @Test
    fun blindClear_secondTapAfterTtl_showsExpiredAndRearms() {
        var now = 20_000L
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null, nowMonoMs = { now })
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)

        now = 20_000L + DualViewModel.BLIND_CLEAR_TTL_MS + 1L
        vm.clearPolicyDraft()

        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已逾時"))
        assertTrue(vm.state.value.msg.contains("重新清除"))
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(
            PolicyText(PolicyTextKind.BLIND_CLEAR_EXPIRED).defaultText(),
            vm.state.value.msg,
        )
    }

    // Stage D：過期重 arm 後、TTL 內第三 tap 執行（重 arm 鏈）。
    // Stage J3：第三 tap 重讀仍 Unknown→abort，不執行（0 clear）。
    @Test
    fun blindClear_expiredRearm_thirdTapWithinTtl_executes() {
        var now = 30_000L
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null, nowMonoMs = { now })
        await { !vm.state.value.loading }

        vm.clearPolicyDraft()
        assertEquals(0, fake.clearCalls)

        now = 30_000L + DualViewModel.BLIND_CLEAR_TTL_MS + 1L
        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("已逾時"))
        assertEquals(0, fake.clearCalls)

        // Stage J3:第三 tap（同世代、TTL 內）重讀仍 Unknown→abort，不執行。
        now += 1_000L
        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // Stage D：斷開/重連 disarm——arm 後 reconnect，再次 clear 僅告警不執行。
    @Test
    fun blindClear_reconnect_disarms() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)

        vm.reconnectAndRefresh()
        await { !vm.state.value.loading }

        vm.clearPolicyDraft()
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertTrue(vm.state.value.msg.contains("清除將刪除未見內容"))
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)

        // Stage J3:重 arm 後第二 tap 重讀仍 Unknown→abort，不執行。
        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // Stage D：foreground disarm——arm 後 onForeground，再次 clear 僅告警。
    // Stage J3:第二 tap 重讀仍 Unknown→abort，不執行。
    @Test
    fun blindClear_foreground_disarms() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)

        vm.onForeground()
        await { !vm.state.value.loading }

        vm.clearPolicyDraft()
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // Stage D：世代失配重 arm——arm 後 unknown-gate confirm 推進版本，
    // 第二 tap 視為新的第一次（FIRST，不執行），第三 tap 執行。
    // Stage J3:第三 tap 重讀仍 Unknown→abort，不執行。
    @Test
    fun blindClear_generationMismatch_rearms() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)

        // unknown-gate confirm：不碰 store 但 bump 版本＋世代。
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(0, fake.saveCalls)

        // 世代已變：第二 tap 重 arm（FIRST），不執行。
        vm.clearPolicyDraft()
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(
            PolicyText(PolicyTextKind.BLIND_CLEAR_FIRST).defaultText(),
            vm.state.value.msg,
        )
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)

        // 第三 tap（同世代、TTL 內）重讀仍 Unknown→abort，不執行。
        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // Stage D：busy 保留 arm——inFlight 期間 clear 拒 BUSY 不耗 arm，
    // 解除後第二 tap 仍執行（非重 arm）。
    // Stage J3:解除後第二 tap 重讀仍 Unknown→abort，不執行。
    @Test
    fun blindClear_busy_preservesArm() {
        val fake = FakePolicyStore()
        fake.saved = null
        val vm = makeVm(fake.store, userId = 0, initialDraft = null)
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertEquals(0, fake.clearCalls)

        // 以反射注入政策在途 key，模擬寫入進行中（不經 mutex，同步可觀測）。
        val f = DualViewModel::class.java.getDeclaredField("inFlightWrites").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val keys = f.get(vm) as MutableSet<String>
        keys.add("policy-write:fake-busy")
        try {
            vm.clearPolicyDraft()
            assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
            assertEquals(MsgKind.INFO, vm.state.value.msgKind)
            assertEquals(0, fake.clearCalls)
        } finally {
            keys.remove("policy-write:fake-busy")
        }

        // arm 被保留，但重讀仍 Unknown→abort，不執行。
        vm.clearPolicyDraft()
        Thread.sleep(300)
        assertEquals(0, fake.clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

    // Stage A review (c) 外部取消透傳：await 期間 scope 取消→CE 重拋，不進
    // RECONCILING（handover 空），不殘留 inFlight/saving/reconciling key，
    // 且不啟動 reconciler（load 呼叫數不增）。
    @Test
    fun confirmAwait_externalCancel_rethrowsWithoutReconcilingOrResidue() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                calls.incrementAndGet()
                backend.get()
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                true
            },
        )
        // 超時設大：取消先於逾時，排除 TIMEOUT 路徑干擾。
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 10_000)
        await { vm.state.value.policyDraftKnown }
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.SAVING }
        val loadsBeforeCancel = calls.get()

        vm.viewModelScope.cancel(kotlinx.coroutines.CancellationException("test-cancel"))
        Thread.sleep(500)

        // 不進 RECONCILING、不發 TIMEOUT、不啟 reconciler、不殘留 key。
        assertFalse(vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING)
        assertFalse(vm.state.value.msg.contains("正在核對"))
        assertEquals(loadsBeforeCancel, calls.get())
        assertTrue(reconcilingKeysOf(vm).isEmpty())
        assertTrue(savingKeysOf(vm).isEmpty())
        assertTrue(inFlightKeysOf(vm).none { it.startsWith("policy-") })

        // 清理阻塞 save（scope 已取消，殘留迴圈僅免執行緒洩漏）。
        saveRelease.set(true)
        Thread.sleep(200)
    }

    // Stage F1 反射讀 Tx（交易所有權唯一真相；null=無在途交易）。
    private fun activeTxOf(vm: DualViewModel): Any? {
        val f = DualViewModel::class.java.getDeclaredField("activePolicyTx").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ref = f.get(vm) as java.util.concurrent.atomic.AtomicReference<Any?>
        return ref.get()
    }

    // Stage F1-1：慢B→超時→早Known(A)→試C→放B（confirm 變體）。
    // B 慢 save 阻塞觸逾時→RECONCILING＋Tx 存活＋pending(B)保留＋Unknown；
    // 早讀 Known(A=seed) 下試 confirm C 即 IN_PROGRESS 拒絕（不耗 save，
    // pending 續為 B，Tx 同一）；放行 B 後 join(true)＋驗證讀 B==B 即
    // WRITE_SUCCESS＋IDLE＋Tx 結清。
    @Test
    fun f1_slowB_timeout_earlyKnownA_tryConfirmC_releaseB_succeeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = HookPolicyStore(
            loadRaw = {
                while (!loadRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                backend.get()
            },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        // 逾時終態：TIMEOUT/CHANNEL＋pending(B)保留＋Unknown＋Tx 存活。
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("逾時"))
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        assertNotNull(activeTxOf(vm))
        val txBefore = activeTxOf(vm)
        assertFalse(vm.state.value.loading)

        // 早 Known(A) 下試 C：拒絕（不耗第二次 save，pending 續為 B，Tx 同一）。
        // 世代失配即靜默保留 TIMEOUT（不覆寫核對提示，見 confirm 入口守衛）。
        val pendingC = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
        val timeoutMsgBefore = vm.state.value.msg
        vm.confirmPolicyWrite(pendingC)
        Thread.sleep(200)
        assertEquals(timeoutMsgBefore, vm.state.value.msg)
        assertTrue(vm.state.value.msg.contains("逾時"))
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(1, saveCalls.get())
        assertSame(txBefore, activeTxOf(vm))

        // 同世代重試即 IN_PROGRESS 明示（沿用既有 latch 語義）。
        vm.confirmPolicyWrite(pendingB)
        Thread.sleep(200)
        assertEquals(PolicyText(PolicyTextKind.IN_PROGRESS).defaultText(), vm.state.value.msg)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(1, saveCalls.get())

        // 放行 B：join(true)＋讀 B==B 即 WRITE_SUCCESS＋IDLE＋Tx 結清。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
        assertNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isEmpty())
        assertEquals(1, saveCalls.get())
    }

    // Stage F1-2：慢B→超時→早Known(A)→試 clear→放B（clear 變體）。
    // clear 在途即 BUSY 拒絕（不耗 clear），pending(B)保留；放行後同 F1-1 成功。
    @Test
    fun f1_slowB_timeout_earlyKnownA_tryClear_releaseB_succeeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertNotNull(activeTxOf(vm))

        // 試 clear：BUSY 拒絕，不耗 clear，pending 續留。
        vm.clearPolicyDraft()
        assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(0, clearCalls.get())
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)

        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertNull(activeTxOf(vm))
        assertEquals(0, clearCalls.get())
    }

    // Stage F1-3：late clear（Tx 結清後的新 clear 即執行成功）。
    // F1-1 結清 IDLE(B) 後，late clear 單步執行→Known-empty＋CLEAR_SUCCESS。
    @Test
    fun f1_lateClear_afterTxSettle_succeeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw == null) backend.set(emptySet()) else backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertNull(activeTxOf(vm))

        // late clear：Tx 已空，新 op 即執行（read-after-ack 回 empty 即 CLEAR）。
        vm.clearPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyDraftKnown && vm.state.value.policyDraft.isEmpty() }
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已清除政策草稿"))
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
    }

    // Stage F1-4：樂觀B→doCommit fail 快徑（commit 回 false 即 FAILED+Unknown，
    // 樂觀污染 B 不得掩蓋已證失敗；無 Tx，不進 RECONCILING）。
    @Test
    fun f1_optimisticB_fastCommitFalse_reportsFailedWithoutTx() {
        // 模擬 RemotePreferences 樂觀污染：commit 回 false 但同行程 mMap 已有 B；
        // needsRefetch 旗標使同實例 load 回 Unknown（fail-closed），VM 快徑
        // ok==false 即 FAILED+Unknown，不採納污染值。
        var commitSucceeds = false
        val prefs = MinimalPrefs(commitSucceeds = false)
        val store = remoteHookPolicyStore { prefs }
        val vm = makeVm(store, userId = 0, initialDraft = emptySet())
        await { vm.state.value.policyDraftKnown }

        val pending = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)
        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("寫入政策失敗"))
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isEmpty())
        commitSucceeds = true
    }

    // Stage F1-5：樂觀B→doCommit fail 慢路徑（逾時後句柄完成 false，join 即
    // FAILED+Unknown 結清；樂觀讀 B 不得翻案）。
    @Test
    fun f1_optimisticB_slowCommitFalse_joinReportsFailed() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            // J2: 放行前 B（在途 inconclusive），放行後權威 A（ground truth !=B）。
            // 先等 reconciler 三輪 B 後 unresolved（REFRESH_FAILED＋RECONCILING 續留），
            // 再放行使 autoRecheck 讀 A＋已證失敗 → FAILED，避免 reconciler/autoRecheck
            // 雙發布競態覆寫為 REFRESHED。
            loadRaw = {
                val n = calls.incrementAndGet()
                if (n == 1) backend.get()
                else if (!saveRelease.get()) setOf("com.B")
                else backend.get()
            },
            saveRaw = { _ ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                saveFinished.countDown()
                false
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.A"), backend.get())
        val pending = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        assertNotNull(activeTxOf(vm))
        // 先等 reconciler 三輪 B 後 unresolved（免與 autoRecheck 雙發布競態）。
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }

        // 放行：句柄回 false（已證失敗），autoRecheck 讀權威 A＋已證失敗即 FAILED+Unknown，
        // 不採納樂觀讀 B，draft 續留 A。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("寫入政策失敗"))
        assertNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isEmpty())
    }

    // Stage F1-6：refresh 期中阻塞（Tx 存活時全域 refresh 併發丟棄：phase 恆
    // RECONCILING，draft 不被覆寫，pending/TIMEOUT 保留，loading 不搶）。
    @Test
    fun f1_globalRefresh_duringTx_discarded_phaseStaysReconciling() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        val timeoutMsg = vm.state.value.msg

        // 期中全域 refresh：丟棄，無聲 no-op（不碰 loading/msg/phase/draft）。
        vm.refresh()
        Thread.sleep(500)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(timeoutMsg, vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.loading)
        assertNotNull(activeTxOf(vm))

        // 放行後正常結清，證明丟棄未終止 Tx。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertNull(activeTxOf(vm))
    }

    // Stage F1-7：快徑收斂（ok==true 先 fresh read-after-ack：讀到他值即
    // REFRESHED 誠實呈現，不謊稱 WRITE_SUCCESS）。
    @Test
    fun f1_fastAck_mismatch_reportsRefreshedWithGroundTruth() {
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.remote"))
        val store = HookPolicyStore(
            // 外部併發覆寫：save 落盤 B 後，讀到他值 OTHER（ground truth）。
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw != null) backend.set(raw)
                backend.set(setOf("com.other"))
                true
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = setOf("com.remote"))
        await { vm.state.value.policyDraftKnown }
        val pending = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        await { vm.state.value.pendingPolicyWrite == null }

        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.other"), vm.state.value.policyDraft)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertEquals(
            PolicyText(PolicyTextKind.REFRESHED).defaultText(),
            vm.state.value.msg,
        )
        assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertNull(activeTxOf(vm))
    }

    // Stage F2-1：無限阻塞讀有界＋誠實 unresolved（P2#4）。
    // - 讀跑獨立單執行緒池（執行緒名含 policyReconcile，不佔共用 IO 池）。
    // - 每次讀 withTimeout(200ms)＋最多 3 次：Binder 不可中斷（無視中斷續跑），
    //   逾時執行緒滯留獨立池，呼叫側計預算；總耗時有界（join＋3 讀＋退避 < 10s）。
    // - 共用池對照：核對期間 Dispatchers.IO 任務仍即時完成（未被拖住）。
    // - 終態誠實 unresolved：REFRESH_FAILED/CHANNEL＋Known=false＋pending 保留＋
    //   phase 續留 RECONCILING（不回 IDLE）；3 次 Unknown 不解 fence（Tx 存活＋
    //   reconciling key 存活，late-join 可見）。
    @Test
    fun f2_infiniteBlockingRead_boundedUnresolvedRetainsFence() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val readThreads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    // 無限阻塞：無視中斷續跑（模擬不可中斷 binder）。
                    readThreads.add(Thread.currentThread().name)
                    while (true) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        val t0 = System.currentTimeMillis()

        // 共用池對照：核對讀阻塞獨立池期間，共用 IO 池任務必須即時完成。
        val control = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { 42 }
        }
        assertEquals(42, control)

        // 終態：由 TIMEOUT 翻為 REFRESH_FAILED（仍 RECONCILING，非 IDLE）。
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                !vm.state.value.policyDraftKnown &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        val elapsed = System.currentTimeMillis() - t0
        // 總耗時有界：join(200ms)＋首延(500ms)＋3 讀(3×200ms)＋2 退避(2×1s)
        // 約 3.3s；10s 上限含 CI 抖動餘量（無界實作將永不到終態而超時失敗）。
        assertTrue("reconcile total must be bounded, was ${elapsed}ms", elapsed < 10_000L)

        // 誠實 unresolved：CHANNEL＋Known=false＋pending 保留＋RECONCILING。
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        // 3 次 Unknown 不解 fence（late-join）：Tx 存活＋reconciling key 存活。
        assertNotNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isNotEmpty())
        assertTrue(savingKeysOf(vm).isEmpty())

        // 獨立池：實際執行的讀執行緒名含 policyReconcile（非共用池執行緒）。
        assertTrue(readThreads.isNotEmpty())
        assertTrue(readThreads.all { it.contains("policyReconcile") })

        // 清理脫鉤慢 save（讀執行緒無限滯留獨立池，隨行程結束回收）。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    // Stage F2 late-join 成功：unresolved（有界讀耗盡，Tx 滯留）→放行 save
    // （deferred 完成 true，後端=B）→refresh 讀到 Known B→結清 IDLE＋
    // WRITE_SUCCESS＋Known(B)＋pending 清空＋Tx 結清，後續可新 request。
    // 鎖定 Critical 修復：reconciler 退出後 refresh 為唯一恢復路徑，不再永久 BUSY。
    @Test
    fun f2_unresolved_releaseSave_refreshLateJoinSettlesToIdle() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        // 等誠實 unresolved（TIMEOUT→REFRESH_FAILED，仍 RECONCILING，Tx 滯留）。
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertNotNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isNotEmpty())

        // 放行 save（後端=B，deferred 完成 true），再放行讀（回 B）。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("com.B"), backend.get())
        loadRelease.set(true)

        // refresh late-join：讀到 Known B＋deferred 已完成→結清。
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
        assertNull(activeTxOf(vm))
        await(timeoutMs = 5_000L) { reconcilingKeysOf(vm).isEmpty() }

        // 恢復後可新 request（不再永久 BUSY）。
        seedSelection(vm)
        vm.requestPolicyWrite()
        val pending2 = vm.state.value.pendingPolicyWrite
        assertNotNull(pending2)
        assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
    }

    // Stage F2 late-join 失敗：unresolved→放行 save（回 false，已證失敗）→
    // refresh 讀到 Known（樂觀/舊值）→FAILED＋Unknown＋IDLE＋pending 清空＋
    // Tx 結清（樂觀讀不得翻案）。
    @Test
    fun f2_unresolved_releaseSaveFalse_refreshLateJoinReportsFailed() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { _ ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                saveFinished.countDown()
                false
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        assertNotNull(activeTxOf(vm))

        // 放行 save（回 false，已證失敗；後端不變仍為 seed），放行讀（回 seed Known）。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("com.seed"), backend.get())
        loadRelease.set(true)

        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("寫入政策失敗"))
        assertNull(activeTxOf(vm))
        await(timeoutMs = 5_000L) { reconcilingKeysOf(vm).isEmpty() }
    }

    // Stage F2 總預算截斷：policyIoTimeoutMs=6000 下 join＋讀皆 6s，合計超
    // 15s 預算即截斷（有界，不無限等）＋滯留 RECONCILING＋Tx 存活＋pending 保留。
    @Test
    fun f2_totalBudget_truncatesBoundedRetainingFence() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    // 無限阻塞（無視中斷，模擬不可中斷 binder；逾時執行緒滯留獨立池）。
                    while (true) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 6_000)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // save 6s 逾時→RECONCILING（TIMEOUT）。
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        val t0 = System.currentTimeMillis()

        // 總預算 15s 截斷→REFRESH_FAILED（仍 RECONCILING，非 IDLE）。
        await(timeoutMs = 40_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                !vm.state.value.policyDraftKnown &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        val elapsed = System.currentTimeMillis() - t0
        // 有界：預算 15s＋抖動餘量（無界實作將永不到終態而超時失敗）。
        assertTrue("reconcile total budget must truncate, was ${elapsed}ms", elapsed < 25_000L)

        // 滯留：CHANNEL＋Known=false＋pending 保留＋Tx 存活＋reconciling 存活。
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertNotNull(activeTxOf(vm))
        assertTrue(reconcilingKeysOf(vm).isNotEmpty())

        // 清理脫鉤慢 save（讀執行緒無限滯留，隨行程結束回收）。
        saveRelease.set(true)
        assertTrue(saveFinished.await(10, java.util.concurrent.TimeUnit.SECONDS))
    }

    // Stage F2 通道/前景守衛：Tx 存活時 selectChannel／onForeground 跳過
    // probe＋refresh（不碰 msg/phase/draft/pending/loading，Tx 存活），放行後
    // 正常結清（證明守衛未終止 Tx）。
    @Test
    fun f2_selectAndForeground_duringTx_skippedPreservingFence() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)

        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        val timeoutMsg = vm.state.value.msg
        assertTrue(timeoutMsg.contains("逾時"))
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertNotNull(activeTxOf(vm))

        // selectChannel（同 pref，無 swap）：跳過，不碰政策欄位/loading。
        vm.selectChannel(ChannelPreference.AUTO)
        Thread.sleep(500)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(timeoutMsg, vm.state.value.msg)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.loading)
        assertNotNull(activeTxOf(vm))

        // onForeground：跳過，同上。
        vm.onForeground()
        Thread.sleep(500)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(timeoutMsg, vm.state.value.msg)
        assertFalse(vm.state.value.loading)
        assertNotNull(activeTxOf(vm))

        // 放行後正常結清，證明守衛未終止 Tx。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        loadRelease.set(true)
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertEquals(setOf("com.B"), vm.state.value.policyDraft)
        assertNull(activeTxOf(vm))
    }

    // Stage G2 跨 VM 反射輔助（P3：認領 fence／舊 settle 不清／新 settle 雙清）。
    private fun coordinatorTxOf(coord: PolicyTxCoordinator): Any? = coord.activeTx.get()

    private fun setLocalTx(vm: DualViewModel, tx: Any?) {
        val f = DualViewModel::class.java.getDeclaredField("activePolicyTx").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ref = f.get(vm) as java.util.concurrent.atomic.AtomicReference<Any?>
        ref.set(tx)
    }

    private fun callSettlePolicyTx(vm: DualViewModel, tx: Any?): Boolean {
        val m = DualViewModel::class.java.getDeclaredMethod("settlePolicyTx", DualViewModel.PolicyTx::class.java)
            .apply { isAccessible = true }
        return m.invoke(vm, tx) as Boolean
    }

    private fun callTryClaim(vm: DualViewModel, tx: Any?): Boolean {
        val m = DualViewModel::class.java.getDeclaredMethod(
            "tryClaimCoordinatorPublish",
            DualViewModel.PolicyTx::class.java,
        ).apply { isAccessible = true }
        return m.invoke(vm, tx) as Boolean
    }

    private fun newTestTx(
        store: HookPolicyStore,
        sanitized: Set<String> = setOf("com.B"),
        isClear: Boolean = false,
    ): DualViewModel.PolicyTx {
        return DualViewModel.PolicyTx(
            txId = -1L,
            key = "policy-test",
            deferred = kotlinx.coroutines.CompletableDeferred(true),
            sanitized = sanitized,
            store = store,
            isClear = isClear,
        )
    }

    // Stage G2 P3-1：跨 VM 認領 fence——舊 VM 逾時 handover 後重建，新 VM init
    // 認領同一 Tx（RECONCILING＋pending 保留＋Unknown），init load 不覆寫 fence；
    // 放行 save＋load 後新 VM 終態 IDLE＋Known(B)＋雙清（本地＋coordinator）。
    @Test
    fun g2_claimFence_newVmInheritsAndSettles() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    while (!loadRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 200, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            val field = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(vm1) as MutableStateFlow<UiState>).value =
                (field.get(vm1) as MutableStateFlow<UiState>).value.copy(pendingPolicyWrite = pendingB)

            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            assertEquals(pendingB, vm1.state.value.pendingPolicyWrite)
            val tx1 = activeTxOf(vm1)
            assertNotNull(tx1)
            assertSame(tx1, coordinatorTxOf(coord))

            // 模擬旋轉：舊 VM scope 取消（reconciler 取消、settle=false 保留 coordinator；
            // 慢 save 跑在 coordinator 域故存活）。
            vm1.viewModelScope.cancel(kotlinx.coroutines.CancellationException("rotation"))
            Thread.sleep(300)
            // coordinator 仍持 Tx（舊 VM 未清），本地仍持（取消不清除）。
            assertSame(tx1, coordinatorTxOf(coord))

            // 新 VM 同一 coordinator＋store 建構，init 同步認領（不經 makeVm 的 Known 等待）。
            val vm2 = DualViewModel(
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
                policyIoTimeoutMs = 200,
                policyTxCoordinator = coord,
            )
            vms.add(vm2)
            // 認領 fence：RECONCILING＋pending(B)＋Unknown，不被 init load 覆寫。
            await(timeoutMs = 10_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            assertEquals(pendingB, vm2.state.value.pendingPolicyWrite)
            assertFalse(vm2.state.value.policyDraftKnown)
            assertSame(tx1, activeTxOf(vm2))
            assertSame(tx1, coordinatorTxOf(coord))

            // 放行：save 落盤 B＋讀回 B，新 VM 終態 IDLE＋Known(B)＋雙清。
            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            loadRelease.set(true)
            await(timeoutMs = 15_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertTrue(vm2.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vm2.state.value.policyDraft)
            assertNull(vm2.state.value.pendingPolicyWrite)
            assertNull(activeTxOf(vm2))
            assertNull(coordinatorTxOf(coord))
            await(timeoutMs = 5_000L) { reconcilingKeysOf(vm2).isEmpty() }
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage G2 P3-2：舊 VM settle 不清——coordinator 已持新 Tx 時，舊 Tx 的 settle
    // （含 tryClaim）不得清掉新 Tx（CAS 失配回 false，coordinator 仍為新 Tx）。
    @Test
    fun g2_oldSettle_doesNotClearNewTx() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(fake.store, userId = 0, policyTxCoordinator = coord)
            val oldTx = newTestTx(fake.store, setOf("com.OLD"))
            val newTx = newTestTx(fake.store, setOf("com.NEW"))
            coord.activeTx.set(newTx)
            setLocalTx(vm, oldTx)

            // 舊 settle：本地失配清除（回 true）但 coordinator 不得被清（仍新 Tx）。
            assertTrue(callSettlePolicyTx(vm, oldTx))
            assertSame(newTx, coordinatorTxOf(coord))
            // tryClaim 同理：敗者回 false、不碰 coordinator（仍新 Tx），僅清本地。
            setLocalTx(vm, oldTx)
            assertFalse(callTryClaim(vm, oldTx))
            assertSame(newTx, coordinatorTxOf(coord))
            assertNull(activeTxOf(vm))
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage G2 P3-3：新 VM settle 雙清——coordinator＋本地同為當前 Tx 時 settle
    // 成功回 true，雙欄同清＋reconciling 移除（舊測試僅斷本地，此處鎖定雙清）。
    @Test
    fun g2_newSettle_clearsBothLocalAndCoordinator() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(fake.store, userId = 0, policyTxCoordinator = coord)
            val tx = newTestTx(fake.store, setOf("com.B"))
            setLocalTx(vm, tx)
            coord.activeTx.set(tx)
            val rf = DualViewModel::class.java.getDeclaredField("policyReconciling").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (rf.get(vm) as MutableSet<String>).add(tx.key)

            assertTrue(callSettlePolicyTx(vm, tx))
            assertNull(activeTxOf(vm))
            assertNull(coordinatorTxOf(coord))
            assertTrue(reconcilingKeysOf(vm).isEmpty())
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1 輔助：不經 makeVm Known 等待、直接以同一 coordinator＋store 建 VM2
    //（pre-timeout SAVING 認領：VM1 仍 SAVING 未逾時即銷毀，VM2 init 同步認領）。
    private fun makeVm2Direct(
        store: HookPolicyStore,
        coord: PolicyTxCoordinator,
        policyIoTimeoutMs: Long = 1_000,
    ): DualViewModel {
        val vm2 = DualViewModel(
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
            policyIoTimeoutMs = policyIoTimeoutMs,
            policyTxCoordinator = coord,
        )
        vms.add(vm2)
        return vm2
    }

    private fun callRegisterHandover(vm: DualViewModel, tx: Any?, isClear: Boolean) {
        val m = DualViewModel::class.java.getDeclaredMethod(
            "registerHandoverTx",
            DualViewModel.PolicyTx::class.java,
            Boolean::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        m.invoke(vm, tx, isClear)
    }

    // Stage H1-1：pre-timeout destroy 繼承（confirm 變體）。
    // VM1 SAVING（未逾時，save 阻塞）即銷毀（scope cancel，不 cancel 已 claim
    // deferred，只取消 await；finally 不碰 coordinator）；VM2 認領 pre-timeout
    // SAVING（RECONCILING＋Unknown＋pending＋世代bump＋reconciler join 同一
    // deferred）；init load 丟棄（後端已換 fresh，fence 仍 seed＋Unknown，不被覆寫）。
    // 放行 save 後新 VM 終態 IDLE＋Known(B)＋雙清。
    @Test
    fun h1_preTimeoutDestroy_confirm_inheritsAndSucceeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 1_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm1.state.value.policyDraft)
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)

            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            // 前置已認領：coordinator＋本地同為同一 Tx，writeMutex 在途持有。
            val tx1 = activeTxOf(vm1)
            assertNotNull(tx1)
            assertSame(tx1, coordinatorTxOf(coord))
            assertTrue(coord.writeMutex.isLocked)

            // 後端換 fresh（若 init load 未丟棄即誤設 fresh＋Known，fence 即破）。
            backend.set(setOf("com.fresh"))

            //  pre-timeout 銷毀（未逾時，SAVING 未進 RECONCILING）：已 claim
            // deferred 不被 cancel（只取消 await），coordinator 保留供認領。
            vm1.viewModelScope.cancel(kotlinx.coroutines.CancellationException("rotation"))
            Thread.sleep(300)
            assertSame(tx1, coordinatorTxOf(coord))

            // 新 VM 認領 pre-timeout SAVING。
            val vm2 = makeVm2Direct(store, coord, policyIoTimeoutMs = 1_000)
            await(timeoutMs = 10_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            assertEquals(pendingB, vm2.state.value.pendingPolicyWrite)
            assertFalse(vm2.state.value.policyDraftKnown)
            // init load 丟棄：後端 fresh 未覆寫 fence（VM2 初值 empty＋Unknown，
            // 若未丟棄即誤設 fresh＋Known，fence 即破）。
            assertEquals(emptySet<String>(), vm2.state.value.policyDraft)
            assertSame(tx1, activeTxOf(vm2))
            assertSame(tx1, coordinatorTxOf(coord))

            // 放行：save 落盤 B（同一 deferred 完成 true），reconciler join＋讀 B==B
            // 即 WRITE_SUCCESS＋IDLE＋雙清。
            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 15_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertTrue(vm2.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vm2.state.value.policyDraft)
            assertNull(vm2.state.value.pendingPolicyWrite)
            assertTrue(vm2.state.value.msg.contains("已寫入政策草稿"))
            assertNull(activeTxOf(vm2))
            assertNull(coordinatorTxOf(coord))
            await(timeoutMs = 5_000L) { reconcilingKeysOf(vm2).isEmpty() }
            assertEquals(1, saveCalls.get())
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1-2：pre-timeout destroy 繼承（clear 變體，pending 本無）。
    @Test
    fun h1_preTimeoutDestroy_clear_inheritsAndSucceeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw == null) backend.set(emptySet()) else backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 1_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }

            vm1.clearPolicyDraft()
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            val tx1 = activeTxOf(vm1)
            assertNotNull(tx1)
            assertSame(tx1, coordinatorTxOf(coord))

            backend.set(setOf("com.fresh"))

            vm1.viewModelScope.cancel(kotlinx.coroutines.CancellationException("rotation"))
            Thread.sleep(300)
            assertSame(tx1, coordinatorTxOf(coord))

            val vm2 = makeVm2Direct(store, coord, policyIoTimeoutMs = 1_000)
            await(timeoutMs = 10_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            // clear 本無 pending；fence 為 Unknown＋RECONCILING＋draft 初值 empty
            // （fresh 未覆寫，若未丟棄即誤設 fresh＋Known）。
            assertNull(vm2.state.value.pendingPolicyWrite)
            assertFalse(vm2.state.value.policyDraftKnown)
            assertEquals(emptySet<String>(), vm2.state.value.policyDraft)
            assertSame(tx1, activeTxOf(vm2))

            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 15_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertTrue(vm2.state.value.policyDraftKnown)
            assertEquals(emptySet<String>(), vm2.state.value.policyDraft)
            assertTrue(vm2.state.value.msg.contains("已清除政策草稿"))
            assertNull(activeTxOf(vm2))
            assertNull(coordinatorTxOf(coord))
            assertEquals(1, clearCalls.get())
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1-3：pre-timeout destroy 繼承（失敗變體：save 回 false，已證失敗）。
    // VM2 認領後放行即 join(false) 短路 FAILED＋Unknown＋IDLE＋pending 清空＋雙清；
    // 樂觀讀不得翻案（draft 續留 seed）。
    @Test
    fun h1_preTimeoutDestroy_fail_inheritsAndReportsFailed() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { _ ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                saveFinished.countDown()
                false
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 1_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)

            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            val tx1 = activeTxOf(vm1)
            assertNotNull(tx1)
            assertSame(tx1, coordinatorTxOf(coord))

            vm1.viewModelScope.cancel(kotlinx.coroutines.CancellationException("rotation"))
            Thread.sleep(300)
            assertSame(tx1, coordinatorTxOf(coord))

            val vm2 = makeVm2Direct(store, coord, policyIoTimeoutMs = 1_000)
            await(timeoutMs = 10_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            assertEquals(pendingB, vm2.state.value.pendingPolicyWrite)
            assertFalse(vm2.state.value.policyDraftKnown)
            assertSame(tx1, activeTxOf(vm2))

            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 15_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertNull(vm2.state.value.pendingPolicyWrite)
            assertFalse(vm2.state.value.policyDraftKnown)
            // 失敗保留 VM2 初值 empty（不謊稱 seed，不採納樂觀值）。
            assertEquals(emptySet<String>(), vm2.state.value.policyDraft)
            assertEquals(MsgKind.FAILED, vm2.state.value.msgKind)
            assertTrue(vm2.state.value.msg.contains("寫入政策失敗"))
            assertNull(activeTxOf(vm2))
            assertNull(coordinatorTxOf(coord))
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1-4：雙VM併發 save/save——第二寫 fail-fast（IN_PROGRESS），不啟第二 IO。
    // VM1 慢 save B 在途（SAVING＋writeMutex 持有＋coordinator 已認領），VM2 試 C
    // 即拒絕（IN_PROGRESS），save 僅 1 次；放行後 VM1 成功 IDLE＋雙清。
    @Test
    fun h1_concurrent_saveSave_secondRejectedNoSecondIo() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val vm2 = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm2.state.value.policyDraftKnown }

            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)
            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            assertTrue(coord.writeMutex.isLocked)

            // VM2 試 C：fail-fast，不啟第二 IO。
            val pendingC = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
            setPendingPolicyWrite(vm2, pendingC)
            vm2.confirmPolicyWrite(pendingC)
            Thread.sleep(300)
            assertEquals(PolicyText(PolicyTextKind.IN_PROGRESS).defaultText(), vm2.state.value.msg)
            assertEquals(MsgKind.INFO, vm2.state.value.msgKind)
            assertEquals(pendingC, vm2.state.value.pendingPolicyWrite)
            assertEquals(1, saveCalls.get())

            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertEquals(setOf("com.B"), vm1.state.value.policyDraft)
            assertNull(activeTxOf(vm1))
            assertNull(coordinatorTxOf(coord))
            assertFalse(coord.writeMutex.isLocked)
            assertEquals(1, saveCalls.get())
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1-5：雙VM併發 save/clear——clear 側 BUSY，不耗 clear IO。
    @Test
    fun h1_concurrent_saveClear_clearRejectedNoClearIo() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet() else saveCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val vm2 = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm2.state.value.policyDraftKnown }

            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)
            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.SAVING }

            vm2.clearPolicyDraft()
            assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm2.state.value.msg)
            assertEquals(MsgKind.INFO, vm2.state.value.msgKind)
            assertEquals(0, clearCalls.get())
            assertEquals(1, saveCalls.get())
            assertEquals(pendingB, vm1.state.value.pendingPolicyWrite)

            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertEquals(setOf("com.B"), vm1.state.value.policyDraft)
            assertEquals(0, clearCalls.get())
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1-6：逆序完成——先勝者結清後，後試者重試即成功（序列化＋可恢復，不死鎖）。
    // VM1 B 在途時 VM2 C 被拒；VM1 成功 IDLE 後 VM2 重試 C 成功，終態 C（後寫勝，
    // 依序落盤，非併發覆寫）。
    @Test
    fun h1_concurrent_reverseCompletion_sequentialSucceeds() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                // 僅首寫阻塞（B），重試 C 即時落盤（免第二 latch）。
                if (raw == setOf("com.B")) {
                    saveStarted.countDown()
                    while (!saveRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val vm2 = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm2.state.value.policyDraftKnown }

            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)
            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.SAVING }

            val pendingC = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
            setPendingPolicyWrite(vm2, pendingC)
            vm2.confirmPolicyWrite(pendingC)
            Thread.sleep(300)
            assertEquals(1, saveCalls.get())

            // 先勝者完成。
            saveRelease.set(true)
            await(timeoutMs = 10_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertEquals(setOf("com.B"), vm1.state.value.policyDraft)
            assertNull(coordinatorTxOf(coord))

            // Stage J3:VM2 在 VM1 成功期間持有 pending C，外來終態已記 stale＋Unknown
            // （防盲覆寫）。重試前須顯式 refresh 洗白（Known B），否則 stale 門控拒寫。
            vm2.refreshPolicyDraft()
            await(timeoutMs = 10_000L) { vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.B"), vm2.state.value.policyDraft)

            // 後試者重試即成功（終態 C）。
            vm2.confirmPolicyWrite(pendingC)
            await(timeoutMs = 10_000L) { vm2.state.value.pendingPolicyWrite == null }
            assertEquals(setOf("com.C"), vm2.state.value.policyDraft)
            assertTrue(vm2.state.value.policyDraftKnown)
            assertEquals(2, saveCalls.get())
            await(timeoutMs = 10_000L) { coordinatorTxOf(coord) == null }
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1-7：registerHandoverTx set->CAS——loser 清本地不碰 winner，不啟 reconciler。
    // coordinator 已持 winner 時，loser handover 即返回（本地殘留 loser 即清，
    // coordinator 仍 winner，reconciling 不加 loser key，phase 不動）。
    @Test
    fun h1_registerHandover_setCas_loserClearsLocalNotWinner() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(fake.store, userId = 0, policyTxCoordinator = coord)
            val winner = newTestTx(fake.store, setOf("com.WINNER"))
            val loser = DualViewModel.PolicyTx(
                txId = -2L,
                key = "policy-test-loser",
                deferred = kotlinx.coroutines.CompletableDeferred(true),
                sanitized = setOf("com.LOSER"),
                store = fake.store,
                isClear = false,
            )
            coord.activeTx.set(winner)
            setLocalTx(vm, null)

            // loser 登記：本地 CAS 成功（null->loser）後 coordinator CAS 失配
            // （已持 winner），即清本地 loser、不碰 winner、不加 key、不啟 reconciler。
            callRegisterHandover(vm, loser, false)
            assertSame(winner, coordinatorTxOf(coord))
            assertNull(activeTxOf(vm))
            assertTrue(reconcilingKeysOf(vm).none { it == loser.key })
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)

            // winner 登記（本地空）：雙 CAS 成功，正常加 key＋Tx 同一。
            callRegisterHandover(vm, winner, false)
            assertSame(winner, activeTxOf(vm))
            assertSame(winner, coordinatorTxOf(coord))
            assertTrue(reconcilingKeysOf(vm).contains(winner.key))
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H1-8：writeMutex 單寫者——SAVING 在途持有，結清釋放（後續可新寫）。
    @Test
    fun h1_writeMutex_singleWriterLockedDuringSavingReleasedAfterSettle() {
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakePolicyStore()
        val blockingStore = HookPolicyStore(
            loadRaw = { fake.saved },
            saveRaw = { raw ->
                fake.saveCalls++
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                fake.saved = raw
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            assertFalse(coord.writeMutex.isLocked)
            val vm = makeVm(blockingStore, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
            setPendingPolicyWrite(vm, pending)

            vm.confirmPolicyWrite(pending)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            // 在途持有：第二寫 tryLock 即失敗（不啟 IO 由 H1-4 鎖定，此處鎖定持有本身）。
            assertTrue(coord.writeMutex.isLocked)
            assertFalse(coord.writeMutex.tryLock())
            assertNotNull(activeTxOf(vm))
            assertNotNull(coordinatorTxOf(coord))

            saveRelease.set(true)
            await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertFalse(coord.writeMutex.isLocked)
            assertTrue(coord.writeMutex.tryLock())
            // 測試借鎖即還，免殘留影響後續（resetForTest 亦兜底）。
            try {
                coord.writeMutex.unlock()
            } catch (_: Exception) {
            }
            assertNull(activeTxOf(vm))
            assertNull(coordinatorTxOf(coord))
            assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H2-1：雙活競爭敗者不卡死——同一 Tx 雙 reconciler 競爭發布權，勝者經
    // settled 廣播終態（IDLE+Known(B)+WRITE_SUCCESS），敗者有界跟隨即應用本地
    // IDLE 終態，不永久卡死 RECONCILING。
    @Test
    fun h2_dualActive_loserSyncsToIdle() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store, userId = 0, policyIoTimeoutMs = 200, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)
            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            val tx1 = activeTxOf(vm1)
            assertNotNull(tx1)

            // 雙活：VM1 未銷毀（reconciler 存活）即建 VM2 認領同一 Tx，雙 reconciler 競爭。
            val vm2 = makeVm2Direct(store, coord, policyIoTimeoutMs = 200)
            await(timeoutMs = 10_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            assertSame(tx1, activeTxOf(vm2))
            assertSame(tx1, coordinatorTxOf(coord))

            // 放行：deferred 完成 true＋讀 B==B，勝者 WRITE_SUCCESS＋IDLE＋雙清＋廣播；
            // 敗者跟隨即 IDLE，不卡死。
            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 15_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            await(timeoutMs = 15_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            for (vm in listOf(vm1, vm2)) {
                assertTrue(vm.state.value.policyDraftKnown)
                assertEquals(setOf("com.B"), vm.state.value.policyDraft)
                assertNull(vm.state.value.pendingPolicyWrite)
                assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
                assertNull(activeTxOf(vm))
            }
            assertNull(coordinatorTxOf(coord))
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H2-2：敗者 refresh 不覆寫勝者——VM2 stale refresh（阻塞回 OLD）始於勝者
    // 終態前，放行時版本已推進（勝者＋跟隨 bump），即丟棄，draft 續留 B。
    @Test
    fun h2_loserRefresh_doesNotOverwriteWinner() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val refreshStarted = java.util.concurrent.CountDownLatch(1)
        val refreshFinished = java.util.concurrent.CountDownLatch(1)
        val blockRefresh = java.util.concurrent.atomic.AtomicBoolean(false)
        val refreshRelease = java.util.concurrent.atomic.AtomicBoolean(true)
        // VM1 store：reconciler 讀直透 backend（Tx 攜此 store，雙 reconciler 共用）。
        val store1 = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        // VM2 store：refresh 讀可阻塞回 stale OLD（與 reconciler 讀分離）。
        val store2 = HookPolicyStore(
            loadRaw = {
                if (blockRefresh.get()) {
                    refreshStarted.countDown()
                    while (!refreshRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    refreshFinished.countDown()
                    setOf("com.OLD")
                } else {
                    backend.get()
                }
            },
            saveRaw = { _ -> true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store1, userId = 0, policyIoTimeoutMs = 500, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)
            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

            val vm2 = makeVm2Direct(store2, coord, policyIoTimeoutMs = 500)
            await(timeoutMs = 10_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

            // VM2 stale refresh 先發並阻塞（versionAtStart 為舊，待勝者 bump 後丟棄）。
            blockRefresh.set(true)
            refreshRelease.set(false)
            vm2.refreshPolicyDraft()
            assertTrue(refreshStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // 放行 save：勝者 B＋IDLE＋廣播，敗者跟隨即 B＋IDLE（bump 版本）。
            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 15_000L) { vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            await(timeoutMs = 15_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertEquals(setOf("com.B"), vm2.state.value.policyDraft)
            assertTrue(vm2.state.value.policyDraftKnown)

            // 釋放 stale refresh（回 OLD）：版本已推進，即丟棄，不覆寫 B。
            refreshRelease.set(true)
            assertTrue(refreshFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val stableUntil = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < stableUntil) {
                assertEquals(setOf("com.B"), vm2.state.value.policyDraft)
                assertTrue(vm2.state.value.policyDraftKnown)
                assertEquals(PolicyWritePhase.IDLE, vm2.state.value.policyWritePhase)
                Thread.sleep(20)
            }
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage H2-3：blocked-Binder yielding 不污染 draft——Tx 存活＋deferred 未完成時
    // refresh（Binder 阻塞回 FRESH）僅 Known=false，保留舊快照，不碰 phase/pending，
    // 不 disarm，不洗 msg。
    @Test
    fun h2_blockedBinder_yielding_preservesDraft() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val refreshStarted = java.util.concurrent.CountDownLatch(1)
        val refreshFinished = java.util.concurrent.CountDownLatch(1)
        val blockRefresh = java.util.concurrent.atomic.AtomicBoolean(false)
        val refreshRelease = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = HookPolicyStore(
            loadRaw = {
                if (blockRefresh.get()) {
                    refreshStarted.countDown()
                    while (!refreshRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    refreshFinished.countDown()
                    setOf("com.FRESH")
                } else {
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                true
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)
        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        val draftBefore = vm.state.value.policyDraft
        val msgBefore = vm.state.value.msg
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)

        // Binder 阻塞 refresh：Tx 存活＋deferred 未完成→yielding 誠實（不污染）。
        blockRefresh.set(true)
        refreshRelease.set(false)
        vm.refreshPolicyDraft()
        assertTrue(refreshStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        refreshRelease.set(true)
        assertTrue(refreshFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { !vm.state.value.policyDraftKnown }
        // 保留舊快照，不更新為 FRESH；不碰 phase/pending；不洗 msg。
        assertEquals(draftBefore, vm.state.value.policyDraft)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertEquals(msgBefore, vm.state.value.msg)
        assertNotNull(activeTxOf(vm))

        // 清理：放行 save，終態可達 IDLE（不卡死）。
        saveRelease.set(true)
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(activeTxOf(vm))
    }

    // Stage H2-4：已證失敗樂觀不翻案——deferred 已證失敗（false）時，即使權威讀回
    // 樂觀 B（==sanitized），仍走 FAILED＋Unknown＋IDLE（不翻案為 SUCCESS），draft
    // 續留舊 A。
    @Test
    fun h2_provenFailed_optimisticDoesNotOverturn() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            // call 1：init 回真值 A；放行前恆回樂觀 B（==sanitized，在途 inconclusive）；
            // 放行後回權威 A（ground truth !=B，late-join 仍 FAILED）。J2 語義同上。
            loadRaw = {
                val n = calls.incrementAndGet()
                if (n == 1) backend.get()
                else if (!saveRelease.get()) setOf("com.B")
                else backend.get()
            },
            saveRaw = { _ ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                saveFinished.countDown()
                false
            },
        )
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200)
        await { vm.state.value.policyDraftKnown }
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pendingB)
        vm.confirmPolicyWrite(pendingB)
        assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }

        // 三輪樂觀 B（deferred 未完成）皆 inconclusive，不搶結為 SUCCESS。
        await(timeoutMs = 15_000L) {
            vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                vm.state.value.msg == PolicyText(PolicyTextKind.REFRESH_FAILED).defaultText()
        }
        assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertNotNull(activeTxOf(vm))

        // 放行已證失敗＋late-join（讀樂觀 B＋deferred false）→FAILED，不翻案。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.A"), vm.state.value.policyDraft)
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("寫入政策失敗"))
        assertNull(activeTxOf(vm))
    }

    // Stage I1 T1：A→重建→B碰撞→C訂閱阻塞（舊碼敗新碼過）。
    // VM-A 快寫 B 結清（settled 留 replay）後銷毀；VM-B 同一 coordinator 新寫 C
    // 逾時（RECONCILING）：舊碼以 VM 本地計數器發號→TxB txId 撞 TxA（皆為 1），
    // txId-only 訂閱立得 stale A 終態（誤同步）；新碼全域發號→TxB txId==2，
    // txId+key 精確比對下訂閱阻塞待勝者（回 null），放行後得 C 終態。
    @Test
    fun i1_rebuildTxIdCollision_staleSettledIgnored() {
        val coord = PolicyTxCoordinator()
        try {
            coord.resetForTest()
            // A：快寫 B 結清（全域首號）。
            val fakeA = FakePolicyStore()
            val vmA = makeVm(fakeA.store, userId = 0, policyTxCoordinator = coord)
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vmA, pendingB)
            vmA.confirmPolicyWrite(pendingB)
            await { vmA.state.value.pendingPolicyWrite == null }
            assertEquals(setOf("com.B"), vmA.state.value.policyDraft)
            assertTrue(vmA.state.value.policyDraftKnown)
            // 旋轉銷毀 A（慢 save 無，replay 殘留 A 終態）。
            vmA.viewModelScope.cancel(kotlinx.coroutines.CancellationException("rotation"))
            Thread.sleep(200)

            // B：同 coordinator 新寫 C，save 阻塞＋短超時→RECONCILING。
            val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.B"))
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val saveStarted = java.util.concurrent.CountDownLatch(1)
            val saveFinished = java.util.concurrent.CountDownLatch(1)
            val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
            val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
            val storeB = HookPolicyStore(
                loadRaw = {
                    if (calls.incrementAndGet() == 1) {
                        backend.get()
                    } else {
                        while (!loadRelease.get()) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                            }
                        }
                        backend.get()
                    }
                },
                saveRaw = { raw ->
                    saveStarted.countDown()
                    while (!saveRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    if (raw != null) backend.set(raw)
                    saveFinished.countDown()
                    true
                },
            )
            val vmB = makeVm(
                storeB,
                userId = 0,
                initialDraft = setOf("com.B"),
                policyIoTimeoutMs = 200,
                policyTxCoordinator = coord,
            )
            val pendingC = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
            setPendingPolicyWrite(vmB, pendingC)
            vmB.confirmPolicyWrite(pendingC)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vmB.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            val txB = activeTxOf(vmB) as DualViewModel.PolicyTx
            // 全域發號：B 不撞 A（舊碼本地計數器下皆為 1，本斷言舊碼敗）。
            assertEquals(2L, txB.txId)

            // C 訂閱：等 TxB 終態（短預算）→阻塞中（回 null），不誤取 A 的 stale settled。
            // （舊碼 txId-only 比對：立得 A 終態，本斷言舊碼敗。）
            val stale = kotlinx.coroutines.runBlocking { coord.awaitSettled(txB.txId, txB.key, 500) }
            assertNull(stale)

            // 放行：勝者結清 C，訂閱即得 C 終態（txId+key 精確命中）。
            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            loadRelease.set(true)
            await(timeoutMs = 15_000L) { vmB.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertTrue(vmB.state.value.policyDraftKnown)
            assertEquals(setOf("com.C"), vmB.state.value.policyDraft)
            assertNull(vmB.state.value.pendingPolicyWrite)
            val got = kotlinx.coroutines.runBlocking { coord.awaitSettled(txB.txId, txB.key, 5_000) }
            assertNotNull(got)
            assertEquals(setOf("com.C"), got!!.draft)
            assertTrue(got.known)
            assertNull(activeTxOf(vmB))
            assertNull(coordinatorTxOf(coord))
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage I1 T1b：key 精確比對——同 txId 異 key 不匹配（舊碼 txId-only 誤配），
    // 且 resetForTest 清計數器（重置後首號回 1）。
    @Test
    fun i1_settledKeyMismatch_doesNotMatchAndResetClearsCounter() {
        val coord = PolicyTxCoordinator()
        try {
            coord.resetForTest()
            val txId = coord.nextTxId()
            assertEquals(1L, txId)
            kotlinx.coroutines.runBlocking {
                coord.tryEmitSettled(
                    PolicySettled(txId, "k1", setOf("com.X"), true, MsgKind.INFO, "x", false),
                )
                val hit = coord.awaitSettled(txId, "k1", 2_000)
                assertNotNull(hit)
                assertEquals(setOf("com.X"), hit!!.draft)
                // 同 txId 異 key：阻塞至超時（回 null），不誤取。
                val miss = coord.awaitSettled(txId, "k2", 300)
                assertNull(miss)
            }
            // resetForTest 清計數器。
            coord.resetForTest()
            assertEquals(1L, coord.nextTxId())
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage I1 T2：雙活快徑 vs reconciler 衝突——恰一勝者（舊碼敗新碼過）。
    // VM 慢 save 在途（SAVING＋前置認領＋writeMutex 持有）；併發勝者（模擬雙活
    // 對端 reconciler 先勝，經 settle 結清認領）後放行 save：快徑 ok==true＋
    // read-after-ack 到達時認領已失→敗者 early-return＋RECONCILING（不發布：
    // 無終態廣播），cancel 讀 job＋解鎖 writeMutex，再經 loserSync 兜底收斂為
    // 落盤真值（IDLE＋Known＋pending 清除）；舊碼
    // publish-then-settle 直接覆寫勝者終態（pending 清空＋draft=B＋廣播，
    // 以下斷言舊碼敗）。
    @Test
    fun i1_fastPathLoser_earlyReturnPreservesWinner() {
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm, pendingB)

            vm.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            val tx = activeTxOf(vm) as DualViewModel.PolicyTx
            assertNotNull(coordinatorTxOf(coord))
            assertTrue(coord.writeMutex.isLocked)

            // 模擬雙活對端先勝結清（同一 Tx；勝者終態已發布，認領清空）。
            assertTrue(callSettlePolicyTx(vm, tx))
            assertNull(coordinatorTxOf(coord))
            assertNull(activeTxOf(vm))

            // 放行 save：快徑 ok==true，但認領已失→敗者 early-return＋RECONCILING，
            // 不發布；經 loserSync 兜底（無勝者終態→可信重讀 ground truth）收斂。
            saveRelease.set(true)
            await { backend.get() == setOf("com.B") }
            await { inFlightKeysOf(vm).none { it.startsWith("policy-") } }

            // 恰一勝者＋敗者收斂：快徑敗者未發布（無第二發射），終態經跟隨兜底
            // 收斂為落盤真值 B（IDLE＋Known(B)＋pending 清除），writeMutex 解鎖。
            await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertNull(vm.state.value.pendingPolicyWrite)
            assertEquals(setOf("com.B"), vm.state.value.policyDraft)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(1, saveCalls.get())
            // 無終態廣播（勝者之外無第二發射）。
            val noDouble = kotlinx.coroutines.runBlocking { coord.awaitSettled(tx.txId, tx.key, 300) }
            assertNull(noDouble)
            // 解鎖：單寫者已釋放，後續可新寫。
            assertFalse(coord.writeMutex.isLocked)
        } finally {
            saveRelease.set(true)
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage J1-1：快徑成功分支 guard-then-lose（ack 讀阻塞期間勝者搶 CAS 並發布
    //  勝者終態 X；放行 ack 後敗者須 CAS 失敗 early-return＋RECONCILING，經
    // loserSync 跟隨收斂為勝者 X（不被 divergent ack 覆寫）、恰一終態發射、
    // writeMutex 解鎖）。
    @Test
    fun j1_fastPathGuardThenLose_noSecondEmit() {
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.seed"))
        val divergent = java.util.concurrent.atomic.AtomicReference(setOf("com.DIVERGENT"))
        val blockAck = java.util.concurrent.atomic.AtomicBoolean(false)
        val ackStarted = java.util.concurrent.CountDownLatch(1)
        val ackRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = {
                if (blockAck.get()) {
                    ackStarted.countDown()
                    while (!ackRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    divergent.get()
                } else {
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        var collectScope: CoroutineScope? = null
        var collectJob: kotlinx.coroutines.Job? = null
        try {
            val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm, pendingB)

            // ack 讀即將阻塞：先開旗再 confirm（init 已走完，save 前無讀）。
            blockAck.set(true)
            vm.confirmPolicyWrite(pendingB)
            assertTrue(ackStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            val tx = activeTxOf(vm) as DualViewModel.PolicyTx
            assertTrue(coord.writeMutex.isLocked)

            // 勝者（同 Tx，跨 VM 層面）：CAS 搶發布權＋發布勝者終態 X；刻意不碰
            // 本 VM _state（還原真實雙活交錯；敗者經 loserSync 跟隨收斂）。
            assertTrue(callTryClaim(vm, tx))
            val winnerDraft = setOf("com.WINNER")
            val winnerMsg = PolicyText(PolicyTextKind.WRITE_SUCCESS, winnerDraft.size).defaultText()
            val seen = java.util.concurrent.CopyOnWriteArrayList<PolicySettled>()
            collectScope = CoroutineScope(Dispatchers.Default)
            collectJob = collectScope.launch {
                coord.settled.collect { s ->
                    if (s.txId == tx.txId && s.key == tx.key) seen.add(s)
                }
            }
            assertTrue(coord.tryEmitSettled(PolicySettled(tx.txId, tx.key, winnerDraft, true, MsgKind.INFO, winnerMsg, false)))

            // 放行 ack（讀到 DIVERGENT≠B）：敗者 CAS 失敗即 early-return＋
            // RECONCILING，經 loserSync 跟隨勝者收斂（不發射第二終態）。
            ackRelease.set(true)
            await { saveCalls.get() == 1 && inFlightKeysOf(vm).none { it.startsWith("policy-") } }
            await(timeoutMs = 5_000L) { !coord.writeMutex.isLocked }
            // 敗者經跟隨收斂為勝者值：draft/pending/phase/msg 皆為勝者終態，
            // 未被 DIVERGENT ack 覆寫。
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm.state.value.pendingPolicyWrite == null &&
                    vm.state.value.policyDraft == winnerDraft
            }
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertNull(vm.state.value.pendingPolicyWrite)
            assertEquals(winnerMsg, vm.state.value.msg)
            assertEquals(1, saveCalls.get())
            // 恰一終態：靜默窗口內敗者無第二發射（replay 仍為勝者）。
            Thread.sleep(500)
            assertEquals(1, seen.size)
            assertEquals(winnerDraft, seen[0].draft)
            val replay = kotlinx.coroutines.runBlocking { coord.awaitSettled(tx.txId, tx.key, 300) }
            assertNotNull(replay)
            assertEquals(winnerDraft, replay!!.draft)
            assertFalse(coord.writeMutex.isLocked)
        } finally {
            ackRelease.set(true)
            try {
                collectJob?.cancel()
            } catch (_: Exception) {
            }
            try {
                collectScope?.cancel()
            } catch (_: Exception) {
            }
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage J1-2：快徑失敗分支 guard-then-lose（save 阻塞期間跨 VM 勝者在協調器
    // 層面搶 CAS 並發布 FAILED 終態，不碰本 VM _state；放行 save（回 false）後敗者
    // 須先過 pending 一致性再於 CAS 失敗 early-return：本地 mark（Unknown，與勝者
    // 一致）＋RECONCILING，經 loserSync 跟隨勝者收斂（不發射，pending 清除、
    // 恰一發射、writeMutex 解鎖）。
    // 註：勝者終態必清 pending，若勝者先落本 VM _state，VM1 將提前以
    // cancel-during-race abort（QUEUED）而到不了失敗守衛；跨 VM 勝者只動協調器，
    // 故本測試採協調器層面勝出，還原真實雙活交錯。
    @Test
    fun j1_fastPathFailGuardThenLose_convergesToWinnerFailed() {
        val backend = java.util.concurrent.atomic.AtomicReference(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { _ ->
                saveCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                false
            },
        )
        val coord = PolicyTxCoordinator()
        var collectScope: CoroutineScope? = null
        var collectJob: kotlinx.coroutines.Job? = null
        try {
            val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm, pendingB)

            vm.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.SAVING }
            val tx = activeTxOf(vm) as DualViewModel.PolicyTx
            assertTrue(coord.writeMutex.isLocked)

            // 勝者（同 Tx，跨 VM 層面）：CAS 搶發布權＋發布 FAILED 終態（保留舊快照
            // seed＋Unknown）；刻意不碰本 VM _state（pending 續為 pendingB，使 VM1
            // 能通過 pending 一致性直達失敗守衛，還原真實雙活交錯）。
            assertTrue(callTryClaim(vm, tx))
            val failMsg = PolicyText(PolicyTextKind.WRITE_FAILED).defaultText()
            val seen = java.util.concurrent.CopyOnWriteArrayList<PolicySettled>()
            collectScope = CoroutineScope(Dispatchers.Default)
            collectJob = collectScope.launch {
                coord.settled.collect { s ->
                    if (s.txId == tx.txId && s.key == tx.key) seen.add(s)
                }
            }
            assertTrue(
                coord.tryEmitSettled(
                    PolicySettled(tx.txId, tx.key, setOf("com.seed"), false, MsgKind.FAILED, failMsg, false),
                ),
            )

            // 放行 save（已證失敗）：pending 一致性通過，敗者於 CAS 失敗 early-return，
            // 本地 mark（Unknown，與勝者一致）＋RECONCILING，經 loserSync 跟隨勝者
            // FAILED 終態收斂（不發射第二終態）。
            saveRelease.set(true)
            await { saveCalls.get() == 1 && inFlightKeysOf(vm).none { it.startsWith("policy-") } }
            await(timeoutMs = 5_000L) { !coord.writeMutex.isLocked }

            // 敗者經跟隨收斂為勝者 FAILED 終態：IDLE＋Unknown（draft 保留舊快照
            // seed）＋pending 清除＋FAILED 文案。
            await(timeoutMs = 10_000L) {
                vm.state.value.policyWritePhase == PolicyWritePhase.IDLE &&
                    vm.state.value.pendingPolicyWrite == null
            }
            assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            assertNull(vm.state.value.pendingPolicyWrite)
            assertEquals(failMsg, vm.state.value.msg)
            assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
            assertEquals(1, saveCalls.get())
            Thread.sleep(500)
            assertEquals(1, seen.size)
            assertFalse(seen[0].known)
            val replay = kotlinx.coroutines.runBlocking { coord.awaitSettled(tx.txId, tx.key, 300) }
            assertNotNull(replay)
            assertFalse(replay!!.known)
            assertFalse(coord.writeMutex.isLocked)
        } finally {
            saveRelease.set(true)
            try {
                collectJob?.cancel()
            } catch (_: Exception) {
            }
            try {
                collectScope?.cancel()
            } catch (_: Exception) {
            }
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage I2 輔助：讀陳舊修訂 stale 旗標（true=後續 request/confirm 須強制重讀）。
    private fun revisionStaleOf(vm: DualViewModel): Boolean {
        val f = DualViewModel::class.java.getDeclaredField("policyRevisionStale").apply { isAccessible = true }
        return (f.get(vm) as java.util.concurrent.atomic.AtomicBoolean).get()
    }

    // Stage I2-1：雙VM陳舊修訂同步＋stale 覆寫鎖定。
    // VM2 阻塞 refresh（將回 OLD）在途時，VM1 快寫 B 結清廣播；VM2（無 activeTx）
    // 經常駐收集 bump＋更新為 B（phase 保持 IDLE），晚到的 OLD refresh 因世代失配
    // 被丟棄（draft 續留 B，不被覆寫）。
    @Test
    fun i2_dualVm_revisionSyncsIdleAndDiscardsStaleRefresh() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val blockRefresh = java.util.concurrent.atomic.AtomicBoolean(false)
        val refreshRelease = java.util.concurrent.atomic.AtomicBoolean(true)
        val refreshStarted = java.util.concurrent.CountDownLatch(1)
        val refreshFinished = java.util.concurrent.CountDownLatch(1)
        val store1 = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw != null) backend.set(raw)
                true
            },
        )
        val store2 = HookPolicyStore(
            loadRaw = {
                if (blockRefresh.get()) {
                    refreshStarted.countDown()
                    while (!refreshRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    refreshFinished.countDown()
                    setOf("com.OLD")
                } else {
                    backend.get()
                }
            },
            saveRaw = { raw ->
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store1, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm1.state.value.policyDraft)
            val vm2 = makeVm(store2, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm2.state.value.policyDraft)
            assertFalse(revisionStaleOf(vm2))

            // VM2 阻塞 refresh 先發（versionAtStart 為舊）。
            blockRefresh.set(true)
            refreshRelease.set(false)
            vm2.refreshPolicyDraft()
            assertTrue(refreshStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // VM1 快寫 B 結清＋廣播。
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)
            vm1.confirmPolicyWrite(pendingB)
            await { vm1.state.value.pendingPolicyWrite == null }
            assertEquals(setOf("com.B"), vm1.state.value.policyDraft)
            assertTrue(vm1.state.value.policyDraftKnown)

            // VM2 常駐收集即同步為 B（無需手動 refresh，phase 保持 IDLE）。
            await(timeoutMs = 10_000L) { vm2.state.value.policyDraft == setOf("com.B") }
            assertTrue(vm2.state.value.policyDraftKnown)
            assertEquals(PolicyWritePhase.IDLE, vm2.state.value.policyWritePhase)
            assertFalse(revisionStaleOf(vm2))

            // 釋放晚到的 OLD refresh：版本已推進，即丟棄，不覆寫 B。
            refreshRelease.set(true)
            assertTrue(refreshFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val stableUntil = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < stableUntil) {
                assertEquals(setOf("com.B"), vm2.state.value.policyDraft)
                assertTrue(vm2.state.value.policyDraftKnown)
                assertEquals(PolicyWritePhase.IDLE, vm2.state.value.policyWritePhase)
                Thread.sleep(20)
            }
        } finally {
            refreshRelease.set(true)
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage I2-2：activeTx 存活期外來 settled 不覆寫＋記 stale 使後續強制重讀。
    // VM 慢 save 逾時 RECONCILING（TxB 存活）時，外來異 Tx 終態（FOREIGN）即丟棄
    // （draft/pending/phase 不動）＋記 stale；放行自 Tx 結清為 B 後 stale 續留，
    // 後續 request/confirm 皆 UNKNOWN_REFRESH 拒絕，直至顯式 refresh Known 洗白。
    @Test
    fun i2_activeTx_foreignSettledDiscardedAndForcesReread() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm, pendingB)
            vm.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            assertFalse(revisionStaleOf(vm))

            // 外來異 Tx 終態（不同 txId+key，Known FOREIGN）。
            coord.tryEmitSettled(
                PolicySettled(9999L, "foreign-key", setOf("com.FOREIGN"), true, MsgKind.INFO, "foreign-msg", false),
            )
            await(timeoutMs = 5_000L) { revisionStaleOf(vm) }

            // 丟棄：RECONCILING＋pending＋draft 皆不動，不被 FOREIGN 覆寫。
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
            assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyDraftKnown)
            assertNotNull(activeTxOf(vm))
            val stableUntil = System.currentTimeMillis() + 300
            while (System.currentTimeMillis() < stableUntil) {
                assertEquals(setOf("com.seed"), vm.state.value.policyDraft)
                assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
                Thread.sleep(20)
            }

            // 放行自 Tx：join(true)＋讀 B==B 即 SUCCESS＋IDLE＋Known(B)，但 stale 續留。
            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertNull(vm.state.value.pendingPolicyWrite)
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vm.state.value.policyDraft)
            assertNull(activeTxOf(vm))
            assertTrue(revisionStaleOf(vm))

            // 後續 request 強制重讀（UNKNOWN_REFRESH，不建 pending）。
            vm.requestPolicyWrite()
            assertNull(vm.state.value.pendingPolicyWrite)
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertTrue(vm.state.value.msg.contains("未知"))
            assertTrue(vm.state.value.msg.contains("重新整理"))

            // 後續 confirm 同樣強制重讀（清 pending、不寫 store）。
            val pendingC = PendingPolicyWrite(setOf("com.C"), System.currentTimeMillis())
            setPendingPolicyWrite(vm, pendingC)
            vm.confirmPolicyWrite(pendingC)
            await { vm.state.value.pendingPolicyWrite == null }
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertTrue(vm.state.value.msg.contains("未知"))
            assertTrue(revisionStaleOf(vm))

            // 顯式 refresh Known（讀 B）即洗白，後續 request 恢復 QUEUED。
            vm.refreshPolicyDraft()
            await(timeoutMs = 10_000L) { !revisionStaleOf(vm) }
            assertTrue(vm.state.value.policyDraftKnown)
            assertEquals(setOf("com.B"), vm.state.value.policyDraft)
            seedSelection(vm)
            vm.requestPolicyWrite()
            val pending2 = vm.state.value.pendingPolicyWrite
            assertNotNull(pending2)
            assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
        } finally {
            saveRelease.set(true)
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage I2-3：hold writeMutex 無 activeTx 時 clear 回 IDLE（＋epoch），confirm
    // 保持 QUEUED（有 pending 可重試，不耗 IO）。
    @Test
    fun i2_clearFailFast_holdMutexReturnsIdleAndConfirmKeepsQueued() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(fake.store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            assertNull(activeTxOf(vm))
            assertNull(coordinatorTxOf(coord))
            assertTrue(coord.writeMutex.tryLock())
            try {
                // clear：外層 QUEUED→內層 tryLock 失敗即條件回 IDLE＋epoch＋BUSY。
                val epochBefore = vm.state.value.policyEpoch
                vm.clearPolicyDraft()
                await(timeoutMs = 5_000L) {
                    vm.state.value.msg == PolicyText(PolicyTextKind.BUSY).defaultText()
                }
                assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
                assertTrue(vm.state.value.policyEpoch > epochBefore)
                assertEquals(0, fake.clearCalls)

                // confirm：同條件下保持 QUEUED（pending 保留可重試，不耗 save）。
                val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
                setPendingPolicyWrite(vm, pending)
                val savesBefore = fake.saveCalls
                vm.confirmPolicyWrite(pending)
                await(timeoutMs = 5_000L) {
                    vm.state.value.msg == PolicyText(PolicyTextKind.IN_PROGRESS).defaultText()
                }
                assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
                assertEquals(pending, vm.state.value.pendingPolicyWrite)
                assertEquals(savesBefore, fake.saveCalls)

                // QUEUED 可取消回 IDLE（證明未卡死）。
                vm.cancelPolicyWrite(pending)
                assertNull(vm.state.value.pendingPolicyWrite)
                assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            } finally {
                try {
                    coord.writeMutex.unlock()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage I2-4：coordinator 佔位（CAS 失配同理）回 IDLE（無本地 Tx）。
    // 以慢速無關 op 先佔 opMutex，使 clear 外層先 QUEUED 排隊，再設 holder，
    // 放行後內層見 reconciling 即條件回 IDLE＋epoch＋BUSY（不耗 clear）。
    @Test
    fun i2_clearFailFast_casFailureReturnsIdle() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        try {
            val blockerEntered = java.util.concurrent.CountDownLatch(1)
            val blockerRelease = java.util.concurrent.atomic.AtomicBoolean(false)
            val blockingSelectionStore = SelectionStore(
                loadRaw = { null },
                saveRaw = { _ ->
                    blockerEntered.countDown()
                    while (!blockerRelease.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    true
                },
            )
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
                hookPolicyStore = fake.store,
                currentUserId = { 0 },
                selectionStore = blockingSelectionStore,
                policyTxCoordinator = coord,
            )
            vms.add(vm)
            await { vm.state.value.policyDraftKnown }
            await { !vm.state.value.loading }
            assertNull(activeTxOf(vm))
            assertNull(coordinatorTxOf(coord))

            // 慢速無關 op 先佔 opMutex。
            vm.cancelSavedSelection()
            assertTrue(blockerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // clear 外層 QUEUED 排隊（opMutex 後）。
            val epochBefore = vm.state.value.policyEpoch
            vm.clearPolicyDraft()
            await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.QUEUED }

            // 排隊期間他 VM 佔位 coordinator（CAS 失配同理）。
            val holder = newTestTx(fake.store, setOf("com.HOLDER"))
            coord.activeTx.set(holder)
            try {
                blockerRelease.set(true)
                await(timeoutMs = 5_000L) {
                    vm.state.value.msg == PolicyText(PolicyTextKind.BUSY).defaultText()
                }
                assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
                assertTrue(vm.state.value.policyEpoch > epochBefore)
                assertEquals(0, fake.clearCalls)
                assertSame(holder, coordinatorTxOf(coord))
                assertNull(activeTxOf(vm))
            } finally {
                coord.activeTx.compareAndSet(holder, null)
            }
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Stage I2-5：Tx 存活（RECONCILING）時 clear 不誤回 IDLE（保留 fence＋pending）。
    @Test
    fun i2_clearDuringReconciling_preservesPhase() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) clearCalls.incrementAndGet()
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 200, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm, pendingB)
            vm.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            val txBefore = activeTxOf(vm)
            assertNotNull(txBefore)
            val epochBefore = vm.state.value.policyEpoch

            // RECONCILING 期間 clear：BUSY 拒絕，不耗 clear，不動 phase/epoch/pending/Tx。
            vm.clearPolicyDraft()
            assertEquals(PolicyText(PolicyTextKind.BUSY).defaultText(), vm.state.value.msg)
            assertEquals(MsgKind.INFO, vm.state.value.msgKind)
            assertEquals(PolicyWritePhase.RECONCILING, vm.state.value.policyWritePhase)
            assertEquals(epochBefore, vm.state.value.policyEpoch)
            assertEquals(pendingB, vm.state.value.pendingPolicyWrite)
            assertSame(txBefore, activeTxOf(vm))
            assertEquals(0, clearCalls.get())

            // 放行後正常結清，證明 fence 未被誤清。
            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertEquals(setOf("com.B"), vm.state.value.policyDraft)
            assertNull(activeTxOf(vm))
            assertEquals(0, clearCalls.get())
        } finally {
            saveRelease.set(true)
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }
    // Stage J2-1: 外來失敗廣播不得忽略——idle Known VM 收到 known==false 終態即 Unknown。
    @Test
    fun j2_failedBroadcast_idleBecomesUnknown() {
        val fake = FakePolicyStore()
        val coord = PolicyTxCoordinator()
        try {
            val vmB = makeVm(fake.store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vmB.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vmB.state.value.policyDraft)
            assertNull(activeTxOf(vmB))

            coord.tryEmitSettled(
                PolicySettled(9999L, "foreign-key", setOf("com.FOREIGN"), false, MsgKind.FAILED, "foreign-fail", false),
            )
            await(timeoutMs = 5_000L) { !vmB.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vmB.state.value.policyDraft)
            assertEquals(MsgKind.FAILED, vmB.state.value.msgKind)
            assertNull(activeTxOf(vmB))
            // Unknown 門控：後續 request 須強制重讀，不得建 pending。
            seedSelection(vmB)
            vmB.requestPolicyWrite()
            assertNull(vmB.state.value.pendingPolicyWrite)
            assertEquals(MsgKind.CHANNEL, vmB.state.value.msgKind)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Stage J2-2: 雙 VM 樂觀污染＋失敗——VM1 阻塞 save（樂觀 B 可見，Tx 在途），
    // VM2 併發 refresh 不得誤報 Known(B)（Unverified/Unknown）；放行失敗後雙方
    // Unknown，且 VM2 寫 C 前須顯式重讀驗證。
    @Test
    fun j2_twoVm_savePoisonThenFail_invalidatesAndRequiresReread() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveEntered = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store1 = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw != null) {
                    backend.set(raw)
                    saveEntered.countDown()
                    while (!saveRelease.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                    backend.set(setOf("com.seed"))
                    false
                } else {
                    backend.set(null)
                    saveEntered.countDown()
                    while (!saveRelease.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                    backend.set(setOf("com.seed"))
                    false
                }
            },
        )
        val store2 = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) backend.set(null) else backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store1, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val vm2 = makeVm(store2, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm2.state.value.policyDraft)

            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vm1, pendingB)
            vm1.confirmPolicyWrite(pendingB)
            assertTrue(saveEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { backend.get() == setOf("com.B") }

            // VM2 併發 refresh：Tx 在途（coordinator activeTx != null），即使底層為 B
            // 亦不得 Known，須 Unknown（Unverified），draft 續留 seed。
            vm2.refreshPolicyDraft()
            await(timeoutMs = 5_000L) { !vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm2.state.value.policyDraft)

            // 放行失敗：VM1 結清 FAILED+Unknown 並廣播，VM2 續留 Unknown。
            saveRelease.set(true)
            await(timeoutMs = 10_000L) { vm1.state.value.pendingPolicyWrite == null && !vm1.state.value.policyDraftKnown }
            assertEquals(MsgKind.FAILED, vm1.state.value.msgKind)
            await(timeoutMs = 5_000L) { !vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm2.state.value.policyDraft)

            // VM2 寫 C 前須重讀：Unknown 下 request 拒絕，不建 pending、不耗 save。
            seedSelection(vm2)
            vm2.requestPolicyWrite()
            assertNull(vm2.state.value.pendingPolicyWrite)
            assertEquals(MsgKind.CHANNEL, vm2.state.value.msgKind)

            // 顯式重讀驗證（底層已回 seed，無在途 Tx）即 Known，後續可寫。
            vm2.refreshPolicyDraft()
            await(timeoutMs = 5_000L) { vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm2.state.value.policyDraft)
            vm2.requestPolicyWrite()
            assertNotNull(vm2.state.value.pendingPolicyWrite)
        } finally {
            saveRelease.set(true)
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Stage J2-3: clear(false) 對稱——樂觀刪鍵＋失敗後跨 VM Unknown，須重讀。
    @Test
    fun j2_twoVm_clearPoisonThenFail_invalidatesAndRequiresReread() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val clearEntered = java.util.concurrent.CountDownLatch(1)
        val clearRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val store1 = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) {
                    backend.set(null)
                    clearEntered.countDown()
                    while (!clearRelease.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                    backend.set(setOf("com.seed"))
                    false
                } else {
                    backend.set(raw)
                    true
                }
            },
        )
        val store2 = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) backend.set(null) else backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm1 = makeVm(store1, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm1.state.value.policyDraftKnown }
            val vm2 = makeVm(store2, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm2.state.value.policyDraftKnown }

            vm1.clearPolicyDraft()
            assertTrue(clearEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 5_000L) { backend.get() == null }

            // 併發 refresh 不得把樂觀空誤報為確認空。
            vm2.refreshPolicyDraft()
            await(timeoutMs = 5_000L) { !vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm2.state.value.policyDraft)

            clearRelease.set(true)
            await(timeoutMs = 10_000L) { !vm1.state.value.policyDraftKnown && vm1.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertEquals(MsgKind.FAILED, vm1.state.value.msgKind)
            await(timeoutMs = 5_000L) { !vm2.state.value.policyDraftKnown }

            vm2.refreshPolicyDraft()
            await(timeoutMs = 5_000L) { vm2.state.value.policyDraftKnown }
            assertEquals(setOf("com.seed"), vm2.state.value.policyDraft)
        } finally {
            clearRelease.set(true)
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Stage J2-4: 刷新期中有任一 app-scoped Tx 即 Unverified——本地無 Tx 但 coordinator
    // 有他 VM 在途 Tx 時，Known 讀不得發布為確認，須 Unknown。
    @Test
    fun j2_refreshDuringForeignTx_isUnverifiedNotKnown() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val storeSlow = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveStarted.countDown()
                while (!saveRelease.get()) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { }
                }
                if (raw != null) backend.set(raw)
                saveFinished.countDown()
                true
            },
        )
        val storeFast = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vmSlow = makeVm(storeSlow, userId = 0, policyIoTimeoutMs = 200, policyTxCoordinator = coord)
            await { vmSlow.state.value.policyDraftKnown }
            val vmFast = makeVm(storeFast, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vmFast.state.value.policyDraftKnown }

            // slow VM 慢 save 逾時進 RECONCILING（coordinator Tx 存活）。
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vmSlow, pendingB)
            vmSlow.confirmPolicyWrite(pendingB)
            assertTrue(saveStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await(timeoutMs = 10_000L) { vmSlow.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
            assertNotNull(activeTxOf(vmSlow))
            assertNotNull(coordinatorTxOf(coord))

            // fast VM（本地無 Tx）此時 refresh：即使底層可讀，也不得 Known。
            backend.set(setOf("com.OTHER"))
            vmFast.refreshPolicyDraft()
            // 最終須 Unknown（yielding），不得 Known(OTHER)。
            await(timeoutMs = 5_000L) { !vmFast.state.value.policyDraftKnown }
            assertFalse(vmFast.state.value.policyDraftKnown)

            saveRelease.set(true)
            assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            saveRelease.set(true)
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Stage J3-1 pending-vs-foreign：request 建 pending（綁定 base 修訂）後，外來
    // Known(C) 經 tryEmit 到達→無 activeTx＋有 pending 即跳過 draft 覆寫＋記 stale＋
    // Unknown；後續 confirm 須拒 UNKNOWN_REFRESH，store 零觸碰（pending 清除重請）。
    @Test
    fun j3_pendingVsForeign_confirmRefusesUnknownRefresh() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)

            // request 建 pending（stamped base＝當下版本）。
            seedSelection(vm)
            vm.requestPolicyWrite()
            val pending = vm.state.value.pendingPolicyWrite
            assertNotNull(pending)
            assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)

            // 外來修訂 Known(C)（異 Tx 終態）。
            coord.tryEmitSettled(
                PolicySettled(9999L, "foreign-key", setOf("com.C"), true, MsgKind.INFO, "foreign-msg", false),
            )
            await(timeoutMs = 5_000L) { revisionStaleOf(vm) }
            // 跳過 draft 覆寫（續留 A）、翻 Unknown、pending 保留（待 confirm 拒寫）。
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(pending, vm.state.value.pendingPolicyWrite)

            // confirm 須拒 UNKNOWN_REFRESH，store 零觸碰，pending 清除。
            vm.confirmPolicyWrite(pending!!)
            await { vm.state.value.pendingPolicyWrite == null }
            assertEquals(0, saveCalls.get())
            assertEquals(setOf("com.A"), backend.get())
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertTrue(vm.state.value.msg.contains("未知"))
            assertTrue(vm.state.value.msg.contains("重新整理"))
            assertFalse(vm.state.value.policyDraftKnown)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Stage J3-4 version-skew-after-wash：stale==false && baseMismatch==true 路徑
    //（confirm 內 pre-IO 版本門控）。request 綁定 base V0 後，外來 settled 記 stale
    //＋Unknown（mark bump→V1），再顯式 refresh Known(C) 洗白 stale（版本續留 V1，
    //不回退）；舊 pending 的 base(V0) 與當下(V1)失配，confirm 須經版本門控 abort
    //（UNKNOWN_REFRESH＋清 pending），store 零觸碰。與 J3-1（stale 直拒）／J3-2
    //（legacy -1 經 stale 門控）互補，覆蓋洗白後純版本失配。
    @Test
    fun j3_versionSkewAfterWash_abortsWithoutIO() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.A"))
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vm.state.value.policyDraftKnown }
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)

            // request 建 pending（stamped base＝當下版本，非 legacy -1）。
            seedSelection(vm)
            vm.requestPolicyWrite()
            val pending = vm.state.value.pendingPolicyWrite
            assertNotNull(pending)
            assertTrue(pending!!.baseDraftVersion != -1L)
            assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)

            // 外來修訂 Known(C)：pending 存活即跳過 draft 覆寫＋記 stale＋Unknown。
            coord.tryEmitSettled(
                PolicySettled(9999L, "foreign-key", setOf("com.C"), true, MsgKind.INFO, "foreign-msg", false),
            )
            await(timeoutMs = 5_000L) { revisionStaleOf(vm) }
            assertEquals(setOf("com.A"), vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(pending, vm.state.value.pendingPolicyWrite)

            // 外來寫入已落盤，顯式重讀 Known(C) 洗白 stale（pending 保留）。
            backend.set(setOf("com.C"))
            vm.refreshPolicyDraft()
            await(timeoutMs = 5_000L) { vm.state.value.policyDraftKnown }
            assertEquals(setOf("com.C"), vm.state.value.policyDraft)
            assertFalse(revisionStaleOf(vm))
            assertEquals(pending, vm.state.value.pendingPolicyWrite)

            // 舊 pending confirm：stale 已洗白但 base(V0)!=當下(V1)，須版本門控 abort。
            vm.confirmPolicyWrite(pending)
            await(timeoutMs = 5_000L) { vm.state.value.pendingPolicyWrite == null }
            assertEquals(0, saveCalls.get())
            assertEquals(setOf("com.C"), backend.get())
            assertEquals(setOf("com.C"), vm.state.value.policyDraft)
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            assertTrue(vm.state.value.msg.contains("未知"))
            assertTrue(vm.state.value.msg.contains("重新整理"))
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Stage J3-2 stale-base abort（queued）：VM-A confirm 排隊（opMutex 被慢速無關
    // op 持有、尚未啟 IO）期間，VM-B clear 成功（{}）＋settled；放行後 VM-A 因 stale
    // abort（UNKNOWN_REFRESH＋清 pending），store 仍 {}（零覆寫）。
    // 註：此處 pending 經 setPendingPolicyWrite 直接注入（legacy base=-1，版本門控
    // 跳過），故走 stale/unknown 門控 abort；stamped base 的純版本失配
    //（stale 已洗白＋baseMismatch）由 j3_versionSkewAfterWash_abortsWithoutIO 覆蓋。
    @Test
    fun j3_staleBaseAbort_queuedConfirmAbortsWithoutOverwrite() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == null) backend.set(emptySet()) else backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            // VM-A 慢速無關 op 先佔自身 opMutex（selectionStore 阻塞）。
            val blockerEntered = java.util.concurrent.CountDownLatch(1)
            val blockerRelease = java.util.concurrent.atomic.AtomicBoolean(false)
            val blockingSelection = SelectionStore(
                loadRaw = { null },
                saveRaw = { _ ->
                    blockerEntered.countDown()
                    while (!blockerRelease.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                    true
                },
            )
            val vmA = DualViewModel(
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
                selectionStore = blockingSelection,
                policyTxCoordinator = coord,
            )
            vms.add(vmA)
            await { vmA.state.value.policyDraftKnown }
            await { !vmA.state.value.loading }
            val vmB = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { vmB.state.value.policyDraftKnown }

            // VM-A 慢速無關 op 持有其 opMutex。
            vmA.cancelSavedSelection()
            assertTrue(blockerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // VM-A confirm 排隊（pending B，尚未啟 IO；H1 未 claim，writeMutex 空閒）。
            val pendingB = PendingPolicyWrite(setOf("com.B"), System.currentTimeMillis())
            setPendingPolicyWrite(vmA, pendingB)
            vmA.confirmPolicyWrite(pendingB)
            // 排隊中（opMutex 被 blocker 持有，尚未取鎖）。
            await(timeoutMs = 5_000L) { inFlightKeysOf(vmA).any { it.startsWith("policy-") } }

            // VM-B clear 成功（{}）＋ settled 廣播（共享 coordinator＋backend）。
            vmB.clearPolicyDraft()
            await(timeoutMs = 10_000L) { backend.get() == emptySet<String>() }
            await(timeoutMs = 5_000L) { revisionStaleOf(vmA) }

            // 放行 blocker，VM-A 取鎖後因 stale abort，不啟 IO，store 仍 {}。
            blockerRelease.set(true)
            await(timeoutMs = 10_000L) { vmA.state.value.pendingPolicyWrite == null }
            assertEquals(emptySet<String>(), backend.get())
            assertEquals(MsgKind.CHANNEL, vmA.state.value.msgKind)
            assertTrue(vmA.state.value.msg.contains("未知"))
            assertFalse(vmA.state.value.policyDraftKnown)
            assertEquals(PolicyWritePhase.IDLE, vmA.state.value.policyWritePhase)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // Stage J3-3 stalled-read-no-SUCCESS：save=true 但 ackRead 阻塞超 1s bound→
    // Unknown→RECONCILING＋Known=false，永不發 SUCCESS（即使 save 已證成功）。
    @Test
    fun j3_stalledAckRead_entersReconcilingWithoutSuccess() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        var saveCalls = 0
        val store = HookPolicyStore(
            loadRaw = {
                if (calls.incrementAndGet() == 1) {
                    backend.get()
                } else {
                    // ackRead＋reconciler 讀皆阻塞（>1s bound），放行後回 backend。
                    while (!loadRelease.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                    backend.get()
                }
            },
            saveRaw = { raw ->
                saveCalls++
                if (raw != null) backend.set(raw)
                true
            },
        )
        // policyIoTimeout 5s（save 不超時），ack bound min(5s,1s)=1s；load 阻塞 3s＋。
        val vm = makeVm(store, userId = 0, policyIoTimeoutMs = 5_000)
        await { vm.state.value.policyDraftKnown }
        val pending = PendingPolicyWrite(setOf("com.example.a"), System.currentTimeMillis())
        setPendingPolicyWrite(vm, pending)

        vm.confirmPolicyWrite(pending)
        // ack 超 1s  bound→Unknown→RECONCILING（非 SUCCESS），pending 保留。
        await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING }
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(pending, vm.state.value.pendingPolicyWrite)
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.msg.contains("已寫入政策草稿"))
        assertEquals(1, saveCalls)

        // 清理：放行 load，reconciler 讀到 backend（B）即 SUCCESS 結清。
        loadRelease.set(true)
        await(timeoutMs = 15_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
        assertNull(vm.state.value.pendingPolicyWrite)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
    }

    // Stage J3-5 blind-clear-requires-Known：load→Unknown，TTL 內 double clear→
    // store.clear 永不呼叫（不刪未見內容）。
    @Test
    fun j3_blindClearRequiresKnown_doubleClearNeverCallsStore() {
        var now = 50_000L
        var clearCalls = 0
        val store = HookPolicyStore(
            loadRaw = { null },
            saveRaw = { raw ->
                if (raw == null) clearCalls++
                true
            },
        )
        val vm = makeVm(store, userId = 0, initialDraft = null, nowMonoMs = { now })
        await { !vm.state.value.loading }
        assertFalse(vm.state.value.policyDraftKnown)

        vm.clearPolicyDraft()
        assertEquals(0, clearCalls)

        now = 50_000L + DualViewModel.BLIND_CLEAR_TTL_MS - 1_000L
        vm.clearPolicyDraft()
        await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE && !vm.state.value.loading }
        assertEquals(0, clearCalls)
        assertFalse(vm.state.value.policyDraftKnown)
    }

}
