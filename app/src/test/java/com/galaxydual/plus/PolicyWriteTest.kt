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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
                    if (clearSucceeds) saved = null
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
    @Test
    fun confirmPolicyWrite_stalePendingLoadDoesNotOverwriteNewerSave() {
        val loadStarted = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadCompleted = java.util.concurrent.CountDownLatch(1)
        val store = HookPolicyStore(
            loadRaw = {
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
            },
            saveRaw = { _ -> true },
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
    @Test
    fun refreshPolicyDraft_stalePendingLoadDoesNotOverwriteNewerSave() {
        val blockLoad = java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseLoad = java.util.concurrent.atomic.AtomicBoolean(true)
        val refreshStarted = java.util.concurrent.CountDownLatch(1)
        val refreshCompleted = java.util.concurrent.CountDownLatch(1)
        val store = HookPolicyStore(
            loadRaw = {
                if (blockLoad.get()) {
                    refreshStarted.countDown()
                    while (!releaseLoad.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    }
                    refreshCompleted.countDown()
                }
                setOf("com.A")
            },
            saveRaw = { _ -> true },
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

    // policyLoadGeneration 世代守衛:重疊的兩次 refresh,慢速 A 不得覆寫快速 B。
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
    fun confirmPolicyWrite_timeout_reportsFailedAndUnknown() {
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
    fun clearPolicyDraft_timeout_reportsFailedAndUnknown() {
        val backend = java.util.concurrent.atomic.AtomicReference<Set<String>?>(setOf("com.seed"))
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val saveStarted = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        val slowStore = HookPolicyStore(
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

    // Blind clear (Phase 2)：unknown draft → 第一次僅告警不執行（不清 pending、
    // 不碰 store），第二次才執行（防靜默單步刪除未見內容）。
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

        // 第二次：執行清除並作廢 pending。
        vm.clearPolicyDraft()
        await { vm.state.value.policyDraftKnown }

        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
        assertTrue(vm.state.value.policyDraftKnown)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        assertTrue(vm.state.value.msg.contains("已清除政策草稿"))
        assertEquals(1, fake.clearCalls)
        assertNull(vm.state.value.pendingPolicyWrite)
    }

    // TOCTOU (#2):慢速 refresh 載入在途 → 成功 save 先提交 → 釋放舊載入不得覆寫。
    @Test
    fun refreshPolicyDraft_saveDuringSlowRefresh_staleRefreshDoesNotOverwriteSavedDraft() {
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
                    else -> setOf("com.OLD")
                }
            },
            saveRaw = { _ -> true },
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

        // 3) 翻轉成功旗標 → blind clear 恢復 sticky 旗標 (明確刪除≠覆寫未見內容，
        //    故不受未知門控阻擋；成功 clear 清 needsRefetch)。Phase 2 需兩步：
        //    第一次僅告警不耗 commit，第二次才執行。
        prefs.commitSucceeds = true
        val commitsBeforeClear = prefs.commitCalls
        vm.clearPolicyDraft()
        assertTrue(vm.state.value.msg.contains("遠端內容未知"))
        assertTrue(vm.state.value.msg.contains("清除將刪除未見內容"))
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.policyDraftKnown)
        assertEquals(commitsBeforeClear, prefs.commitCalls)
        vm.clearPolicyDraft()
        await { vm.state.value.policyDraftKnown }
        assertEquals(emptySet<String>(), vm.state.value.policyDraft)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)

        vm.refreshPolicyDraft()
        await { vm.state.value.policyDraftKnown }

        // 4) 已知後 request+confirm 成功 (真實 store→VM 鏈)。
        seedSelection(vm)
        vm.requestPolicyWrite()
        val pending3 = vm.state.value.pendingPolicyWrite
        assertNotNull(pending3)
        vm.confirmPolicyWrite(pending3!!)
        await { vm.state.value.pendingPolicyWrite == null }
        assertEquals(setOf("com.example.a"), vm.state.value.policyDraft)
        assertTrue(vm.state.value.policyDraftKnown)
        assertTrue(vm.state.value.msg.contains("已寫入政策草稿"))
        // 持久化經真實綁定落到同一 prefs 實例。
        assertEquals(
            setOf("com.example.a"),
            prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)?.toSet(),
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
    @Test
    fun slowUnknownLoad_doesNotDiscardNewerKnown() {
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
                        null
                    }
                    else -> null
                }
            },
            saveRaw = { _ ->
                saveCalls.incrementAndGet()
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
                    fake.saved = null
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

    // Stage A review (a) TOCTOU 丟棄：RECONCILING 期間 refresh 推進 generation，
    // reconciler 的晚到發布必須被丟棄（最新者勝，不覆寫新事實）。
    // 定序：save 阻塞觸逾時→RECONCILING；reconciler 首延 500ms 空窗內 refresh
    // 先讀到 NEW 並發布；再放行 reconciler 讀到 stale OLD（Known），世代已變即
    // 丟棄（draft 續為 NEW，穩定窗口斷言）。
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
                when (calls.incrementAndGet()) {
                    1 -> backend.get()
                    // call 2：空窗內的 refresh 即時讀（NEW），不阻塞。
                    2 -> backend.get()
                    // call 3+：reconciler 晚到讀 stale OLD（Known），阻塞待放行。
                    else -> {
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
        // refresh），仍在 500ms 首延空窗內，再做 refresh 使其世代更新。
        Thread.sleep(200)
        backend.set(setOf("com.fresh"))
        vm.refreshPolicyDraft()
        await(timeoutMs = 10_000L) { vm.state.value.policyDraft == setOf("com.fresh") }
        assertTrue(vm.state.value.policyDraftKnown)
        assertTrue(reconcilerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 放行晚到的 stale 讀；世代已變必須丟棄（穩定窗口斷言續為 NEW）。
        reconcilerRelease.set(true)
        assertTrue(reconcilerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stableUntil = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < stableUntil) {
            assertEquals(setOf("com.fresh"), vm.state.value.policyDraft)
            assertTrue(vm.state.value.policyDraftKnown)
            Thread.sleep(20)
        }
        // reconciler 終態 finally 已清 key（發布被丟棄，相位保持 RECONCILING 待
        // 終態——本測試僅鎖定 draft 不被覆寫，不鎖定相位回位）。
        await(timeoutMs = 5_000L) { reconcilingKeysOf(vm).isEmpty() }

        // 清理：放行脫鉤慢 save（結果不再採納，僅免執行緒殘留）。
        saveRelease.set(true)
        assertTrue(saveFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    // Stage A review (b) 全 Unknown 終態：load 恆 null（3 次重試皆 Unknown）→
    // IDLE＋Unknown＋REFRESH_FAILED/CHANNEL＋pending 清空＋reconciling 清空；
    // 之後 refresh Known 即可新 request（證明未卡 RECONCILING）。
    @Test
    fun reconcilerAllUnknown_reportsRefreshFailedAndAllowsNewRequest() {
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
        // 重試恰 3 次：init 1＋reconciler 3。
        assertEquals(4, calls.get())
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
}
