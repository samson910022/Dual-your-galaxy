// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// p28-1 owner review P2-1/P2-2 JVM 可測 seam：
// - P2-1：msgView/writeBlockView 同文去重（isWriteBlockDuplicate）+
//   原因未變不重設 text（shouldUpdateWriteBlockText）。只留 msgView POLITE
//   播報，writeBlockView 同文時降 NONE；可見文字保留。
// - P2-2：clone/remove 納入 manualInputActionable（非空且就地驗證通過）；
//   batch 另納入目前 verified target 可用（與 cloneSelected 起始 pin
//   fail-closed 一致）。VM isWritable/輸入/起始 pin 守衛保留為縱深。
// B1 阻塞修復：
// - 同文去重鍵去時間（stripWriteBlockTimeParams/writeBlockDedupKey），LSPosed
//   displayText 時間戳漂移仍判重；可見文字保留完整時間。
// - 顯示新鮮度與播報去重分離：顯示比完整文字（時間戳僅變亦刷新），播報僅比
//   穩定去重鍵（shouldAnnounceWriteBlock/shouldAnnounceMsg 控制 liveRegion）。
// - msgView 守衛（shouldUpdateMsgText + lastMsgText + View 實字雙比對）。
// - busy 打字競態一律禁用三鈕（讀 vm.state 最新，不用 stale lastState）。
// - 旋轉恢復後重算 enablement（Activity 側 onRestore/onResume 補呼叫；JVM 鎖純函數）。
// - verifiedTargetAvailable 閾值統一 >0 且 serial 非 null（0 永不可寫；serial null 即 SerialUnavailable，VM pin 為準）。
// - factory blockText 與 resolveBlockReason 同資源（同 stringRes）。
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiSemanticsP28P2Test {
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeGateway : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        override suspend fun run(cmd: String): CmdResult = when (cmd) {
            "id -u" -> CmdResult(0, "2000", "")
            DualRepository.CMD_LIST_USERS -> CmdResult(1, "", "no binder")
            else -> CmdResult(0, "", "")
        }
    }

    private fun pumpUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (cond()) return
            if (System.currentTimeMillis() >= deadline) {
                testDispatcher.scheduler.advanceUntilIdle()
                if (cond()) return
                throw AssertionError("timed out waiting for condition")
            }
            Thread.sleep(10)
        }
    }

    private fun writableState(): UiState = UiState(
        channel = ChannelPreference.ROOT,
        resolvedGateway = ResolvedChannel.ROOT,
        execIdentity = Privilege.ROOT,
        privilege = Privilege.ROOT,
        writeReady = true,
        pmReadable = true,
        loading = false,
        inventory = InventorySnapshot(
            source = "pm list packages --user 10",
            targetId = 10,
            targetSerial = 1,
            queryTimeMs = 1L,
            freshness = InventoryFreshness.FRESH,
            count = 1,
        ),
    )

    // ---- P2-2 manualInputActionable ----

    @Test
    fun manualInputActionable_blank_isFalse() {
        assertFalse(manualInputActionable(""))
        assertFalse(manualInputActionable("   "))
    }

    @Test
    fun manualInputActionable_valid_isTrue() {
        assertTrue(manualInputActionable("com.example.app"))
        assertTrue(manualInputActionable("  com.example.app  "))
    }

    @Test
    fun manualInputActionable_invalidCharset_isFalse() {
        assertFalse(manualInputActionable("com.example;rm"))
        assertFalse(manualInputActionable("com example"))
    }

    @Test
    fun manualInputActionable_overlong_isFalse() {
        assertFalse(manualInputActionable("a".repeat(MAX_PACKAGE_INPUT_CHARS + 1)))
        assertTrue(manualInputActionable("a".repeat(MAX_PACKAGE_INPUT_CHARS)))
    }

    // ---- P2-2 verifiedTargetAvailable ----

    @Test
    fun verifiedTargetAvailable_unknown_isFalse() {
        assertFalse(verifiedTargetAvailable(UiState()))
        assertFalse(verifiedTargetAvailable(UiState(inventory = InventorySnapshot.unknown(), loading = false)))
    }

    @Test
    fun verifiedTargetAvailable_known_isTrue() {
        assertTrue(verifiedTargetAvailable(writableState()))
    }

    // ---- P2-2 cloneRemoveEnabled ----

    @Test
    fun cloneRemoveEnabled_happy_isTrue() {
        assertTrue(cloneRemoveEnabled(writableState(), true))
    }

    @Test
    fun cloneRemoveEnabled_emptyInput_isFalse() {
        assertFalse(cloneRemoveEnabled(writableState(), false))
    }

    @Test
    fun cloneRemoveEnabled_blocked_isFalseEvenWithValidInput() {
        // 預設唯讀（NOOP 回退）：VM guard 縱深，UI 亦禁用。
        val blocked = UiState(loading = false)
        assertTrue(writeBlockReason(blocked) != null)
        assertFalse(cloneRemoveEnabled(blocked, true))
    }

    @Test
    fun cloneRemoveEnabled_busy_isFalse() {
        assertFalse(cloneRemoveEnabled(writableState().copy(loading = true), true))
    }

    // ---- P2-2 batchEnabled ----

    @Test
    fun batchEnabled_happy_isTrue() {
        val s = writableState().copy(selectedKeys = setOf("1:com.example.a"))
        assertTrue(batchEnabled(s))
    }

    @Test
    fun batchEnabled_emptySelection_isFalse() {
        assertFalse(batchEnabled(writableState().copy(selectedKeys = emptySet())))
    }

    @Test
    fun batchEnabled_noTarget_isFalse() {
        // 與 cloneSelected 起始 pin fail-closed 一致：無 verified target 即禁用。
        val s = writableState().copy(
            selectedKeys = setOf("1:com.example.a"),
            inventory = InventorySnapshot.unknown(),
        )
        assertFalse(verifiedTargetAvailable(s))
        assertFalse(batchEnabled(s))
    }

    @Test
    fun batchEnabled_blocked_isFalse() {
        val s = writableState().copy(
            selectedKeys = setOf("1:com.example.a"),
            writeReady = false,
        )
        assertTrue(writeBlockReason(s) != null)
        assertFalse(batchEnabled(s))
    }

    @Test
    fun batchEnabled_busy_isFalse() {
        val s = writableState().copy(
            loading = true,
            selectedKeys = setOf("1:com.example.a"),
        )
        assertFalse(batchEnabled(s))
    }

    // ---- Stage1 批次建立兩步確認（canConfirmBatch / buildBatchConfirmMessage） ----

    @Test
    fun canConfirmBatch_happy_isTrue() {
        val s = writableState().copy(selectedKeys = setOf("1:com.example.a"))
        assertTrue(canConfirmBatch(s, acknowledged = true))
    }

    @Test
    fun canConfirmBatch_unacknowledged_isFalse() {
        val s = writableState().copy(selectedKeys = setOf("1:com.example.a"))
        assertFalse(canConfirmBatch(s, acknowledged = false))
    }

    @Test
    fun canConfirmBatch_blockedBusyOrNoTarget_isFalseEvenAcknowledged() {
        val blocked = writableState().copy(
            selectedKeys = setOf("1:com.example.a"),
            writeReady = false,
        )
        assertTrue(writeBlockReason(blocked) != null)
        assertFalse(canConfirmBatch(blocked, acknowledged = true))
        val busy = writableState().copy(loading = true, selectedKeys = setOf("1:com.example.a"))
        assertFalse(canConfirmBatch(busy, acknowledged = true))
        val noTarget = writableState().copy(
            selectedKeys = setOf("1:com.example.a"),
            inventory = InventorySnapshot.unknown(),
        )
        assertFalse(canConfirmBatch(noTarget, acknowledged = true))
        val serialMissing = writableState().copy(
            selectedKeys = setOf("1:com.example.a"),
            inventory = writableState().inventory.copy(targetSerial = null),
        )
        assertFalse(canConfirmBatch(serialMissing, acknowledged = true))
        val emptySelection = writableState().copy(selectedKeys = emptySet())
        assertFalse(canConfirmBatch(emptySelection, acknowledged = true))
    }

    @Test
    fun buildBatchConfirmMessage_includesCountAndVerifiedTarget() {
        val text = buildBatchConfirmMessage(
            template = "將建立：%1\$d 個分身\n已驗證目標：user %2\$d，serial %3\$d\n須勾選下方確認框。",
            count = 3,
            targetId = 10,
            targetSerial = 7,
        )
        assertTrue(text.contains("3 個分身"))
        assertTrue(text.contains("user 10"))
        assertTrue(text.contains("serial 7"))
    }

    // PR64 P1：同數量換選取 (A→B) 必須視為快照失效；目標變更亦然。
    @Test
    fun batchRequestMatchesLive_sameCountDifferentKeys_isFalse() {
        val s = writableState().copy(selectedKeys = setOf("1:com.example.b"))
        assertFalse(
            batchRequestMatchesLive(
                requestKeys = setOf("1:com.example.a"),
                requestTargetId = 10,
                requestTargetSerial = 1,
                live = s,
            ),
        )
    }

    @Test
    fun batchRequestMatchesLive_targetOrExactKeysChange_isFalse() {
        val s = writableState().copy(selectedKeys = setOf("1:com.example.a"))
        assertFalse(batchRequestMatchesLive(setOf("1:com.example.a"), 11, 1, s))
        assertFalse(batchRequestMatchesLive(setOf("1:com.example.a"), 10, 2, s))
        assertFalse(batchRequestMatchesLive(setOf("1:com.example.a", "1:com.example.b"), 10, 1, s))
        assertTrue(batchRequestMatchesLive(setOf("1:com.example.a"), 10, 1, s))
    }

    // Review #4：同 key/target 但 freshness 語境跨 FRESH↔非-FRESH 時快照失效。
    @Test
    fun batchRequestMatchesLive_freshnessContextMismatch_isFalse() {
        val fresh = writableState().copy(
            inventory = writableState().inventory.copy(freshness = InventoryFreshness.FRESH),
            selectedKeys = setOf("1:com.example.a"),
        )
        val stale = fresh.copy(
            inventory = fresh.inventory.copy(freshness = InventoryFreshness.STALE),
        )
        assertFalse(batchRequestMatchesLive(setOf("1:com.example.a"), 10, 1, stale, requestWasFresh = true))
        assertFalse(batchRequestMatchesLive(setOf("1:com.example.a"), 10, 1, fresh, requestWasFresh = false))
        assertTrue(batchRequestMatchesLive(setOf("1:com.example.a"), 10, 1, fresh, requestWasFresh = true))
        assertTrue(batchRequestMatchesLive(setOf("1:com.example.a"), 10, 1, stale, requestWasFresh = false))
    }

    // ---- P2-1 isWriteBlockDuplicate ----

    @Test
    fun writeBlockDuplicate_sameText_isTrue() {
        assertTrue(isWriteBlockDuplicate("寫入已禁用", "寫入已禁用", false))
    }

    @Test
    fun writeBlockDuplicate_differentText_isFalse() {
        assertFalse(isWriteBlockDuplicate("寫入已禁用A", "寫入已禁用B", false))
    }

    @Test
    fun writeBlockDuplicate_emptyMsg_isFalse() {
        assertFalse(isWriteBlockDuplicate("寫入已禁用", "", false))
    }

    @Test
    fun writeBlockDuplicate_nullBlock_isFalse() {
        assertFalse(isWriteBlockDuplicate(null, "寫入已禁用", false))
    }

    @Test
    fun writeBlockDuplicate_busy_isFalse() {
        assertFalse(isWriteBlockDuplicate("寫入已禁用", "寫入已禁用", true))
    }

    // ---- P2-1 shouldUpdateWriteBlockText ----

    @Test
    fun shouldUpdateWriteBlockText_same_isFalse() {
        assertFalse(shouldUpdateWriteBlockText("原因A", "原因A"))
        assertFalse(shouldUpdateWriteBlockText(null, null))
    }

    @Test
    fun shouldUpdateWriteBlockText_changed_isTrue() {
        assertTrue(shouldUpdateWriteBlockText("原因A", "原因B"))
        assertTrue(shouldUpdateWriteBlockText(null, "原因B"))
        assertTrue(shouldUpdateWriteBlockText("原因A", null))
    }

    // ---- P2-1 single-source：VM 實際拒絕與禁用原因同文即去重 ----

    @Test
    fun writeBlockDuplicate_matchesLiveVmRefusal() {
        val fake = FakeGateway()
        val vm = DualViewModel(
            repo = DualRepository(fake),
            gatewayPrivilege = Privilege.NONE,
            elevators = listOf(SuElevator(fake), ShizukuElevator(fake)),
            preference = ChannelPreference.ROOT,
            suGateway = NoOpGateway(),
            shizukuGateway = NoOpGateway(),
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.NOOP,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            blockText = zhTestBlockText(),
        )
        pumpUntil { !vm.state.value.loading }
        vm.clone("com.example.z")
        pumpUntil { !vm.state.value.loading && vm.state.value.msg.isNotEmpty() }
        val s = vm.state.value
        val reason = writeBlockReason(s)
        assertTrue("blocked state must carry a reason, got: $s", reason != null)
        val resolved = resolveZhTest(reason!!)
        assertEquals(resolved, s.msg)
        // 同文 → writeBlockView 應降 NONE（只留 msgView 播報）。
        assertTrue(isWriteBlockDuplicate(resolved, s.msg, s.loading))
        assertEquals(resolved, resolveZhTest(reason))
    }

    // ---- B1：busy + 打字競態（一律禁用三鈕，打字不得繞過 busy） ----

    @Test
    fun busy_typingRace_staysDisabled() {
        // 同一可寫+有效輸入+有勾選：非 busy 全開，busy 則三鈕全關。
        val idle = writableState().copy(
            loading = false,
            selectedKeys = setOf("1:com.example.a"),
        )
        assertTrue(cloneRemoveEnabled(idle, true))
        assertTrue(batchEnabled(idle))
        val busy = idle.copy(loading = true)
        // TextWatcher 輕量路徑讀最新 vm.state（busy）即全關；stale 快取不得繞過。
        assertFalse(cloneRemoveEnabled(busy, true))
        assertFalse(batchEnabled(busy))
    }

    // ---- B1：verifiedTargetAvailable 閾值統一 >0 且 serial 非 null（0 永不可寫；serial null 即 SerialUnavailable，VM pin 必拒絕） ----

    @Test
    fun verifiedTargetAvailable_zero_isFalse() {
        val zero = writableState().copy(
            inventory = writableState().inventory.copy(targetId = 0),
        )
        assertFalse(verifiedTargetAvailable(zero))
        assertFalse(batchEnabled(zero.copy(selectedKeys = setOf("1:com.example.a"))))
    }

    @Test
    fun verifiedTargetAvailable_idOnlySerialNull_isFalse() {
        // owner 複審 P2：targetId>0 但 targetSerial==null（SerialUnavailable 殘留
        // 或 legacy 未知）時，cloneSelected 起始 pin 需 Confirmed(id+serial) 必拒絕，
        // 故 UI 亦視為不可用（batch 禁用），不取代 VM 權威判定。
        val idOnly = writableState().copy(
            inventory = writableState().inventory.copy(targetSerial = null),
        )
        assertTrue(idOnly.inventory.targetId > 0)
        assertFalse(verifiedTargetAvailable(idOnly))
        assertFalse(batchEnabled(idOnly.copy(selectedKeys = setOf("1:com.example.a"))))
    }

    // ---- B1：旋轉重算（恢復後顯式重算 enablement，避免卡 disabled） ----

    @Test
    fun rotation_restoredInput_recomputesEnablement() {
        // 模擬旋轉後 pkgInput 回填有效文字：同一純函數重算即恢復可用。
        val s = writableState()
        assertTrue("restored valid input must re-enable", cloneRemoveEnabled(s, manualInputActionable("com.example.app")))
        assertFalse("restored empty input stays disabled", cloneRemoveEnabled(s, manualInputActionable("")))
        // 去重快取在新 Activity 為 null 時首寫必須發生（View 為空）。
        assertTrue(shouldUpdateWriteBlockText(null, "原因X"))
        assertTrue(shouldUpdateMsgText(null, "訊息X"))
        assertFalse(shouldUpdateWriteBlockText("原因X", "原因X"))
        assertFalse(shouldUpdateMsgText("訊息X", "訊息X"))
    }

    // ---- B1：msgView 守衛 ----

    @Test
    fun shouldUpdateMsgText_same_isFalse() {
        assertFalse(shouldUpdateMsgText("訊息A", "訊息A"))
        assertFalse(shouldUpdateMsgText(null, null))
    }

    @Test
    fun shouldUpdateMsgText_changed_isTrue() {
        assertTrue(shouldUpdateMsgText("訊息A", "訊息B"))
        assertTrue(shouldUpdateMsgText(null, "訊息B"))
        assertTrue(shouldUpdateMsgText("訊息A", null))
    }

    // ---- B1：LSPosed 時間漂移下去重（可見文字保留時間，僅去重鍵去時間） ----

    @Test
    fun writeBlockDuplicate_lsposedTimeDrift_isTrue() {
        val e1 = HookEvidence(status = HookStatus.INACTIVE, queriedAtMs = 1_700_000_000_000)
        val e2 = HookEvidence(status = HookStatus.INACTIVE, queriedAtMs = 1_700_000_060_000)
        val d1 = e1.displayText()
        val d2 = e2.displayText()
        // 時間戳漂移確實造成原文不同（bug 前提）。
        assertTrue("hook display must drift with queriedAt, got: $d1 vs $d2", d1 != d2)
        val t1 = zhTestBlockText()(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(d1)))
        val t2 = zhTestBlockText()(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(d2)))
        assertTrue("block text must drift with hook time, got: $t1 vs $t2", t1 != t2)
        // 可見文字保留時間（不相等），但去重鍵去時間後相等 → 判重。
        assertEquals(stripWriteBlockTimeParams(t1), stripWriteBlockTimeParams(t2))
        assertTrue(isWriteBlockDuplicate(t1, t2, false))
        // Kind 鍵亦相等（同 kind + 同狀態範圍，去時間）。
        val k1 = writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(d1))))
        val k2 = writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(d2))))
        assertEquals(k1, k2)
    }

    // ---- P2 顯示新鮮度與播報去重分離：時間戳僅變仍刷新顯示、抑制播報 ----

    @Test
    fun writeBlockDisplay_lsposedTimeDrift_stillRefreshes_suppressesAnnounce() {
        val e1 = HookEvidence(status = HookStatus.INACTIVE, queriedAtMs = 1_700_000_000_000)
        val e2 = HookEvidence(status = HookStatus.INACTIVE, queriedAtMs = 1_700_000_060_000)
        val t1 = zhTestBlockText()(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(e1.displayText())))
        val t2 = zhTestBlockText()(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(e2.displayText())))
        assertTrue("time-only drift must differ in full text", t1 != t2)
        // 顯示：完整文字變化即刷新（render 以全文比快取 + View 實字，不跳過 setText）。
        assertTrue(shouldUpdateWriteBlockText(t1, t2))
        assertTrue(shouldUpdateMsgText(t1, t2))
        // 播報：穩定去重鍵相等 → 抑制（render 降 liveRegion 為 NONE，不阻止顯示刷新）。
        val k1 = writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(e1.displayText()))))
        val k2 = writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(e2.displayText()))))
        assertEquals(k1, k2)
        assertFalse(shouldAnnounceWriteBlock(k1, k2, crossDuplicate = false))
        assertFalse(shouldAnnounceMsg(stripWriteBlockTimeParams(t1), stripWriteBlockTimeParams(t2)))
        // 跨節點同文（writeBlock 與 msg 同源）亦抑制 writeBlock 播報，只留 msg 節點。
        assertTrue(isWriteBlockDuplicate(t1, t2, false))
        assertFalse(shouldAnnounceWriteBlock(k1, k2, crossDuplicate = true))
    }

    @Test
    fun announce_realContentChange_announces() {
        // 去重鍵變化（真內容變化）→ 播報；跨節點同文時 writeBlock 仍抑制（只留 msg）。
        assertTrue(shouldAnnounceWriteBlock("K:A", "K:B", crossDuplicate = false))
        assertFalse(shouldAnnounceWriteBlock("K:B", "K:B", crossDuplicate = false))
        assertFalse(shouldAnnounceWriteBlock("K:A", "K:B", crossDuplicate = true))
        assertFalse(shouldAnnounceWriteBlock(null, null, crossDuplicate = false))
        assertTrue(shouldAnnounceWriteBlock(null, "K:B", crossDuplicate = false))
        assertTrue(shouldAnnounceMsg("去時間A", "去時間B"))
        assertFalse(shouldAnnounceMsg("去時間A", "去時間A"))
        assertFalse(shouldAnnounceMsg(null, null))
        assertTrue(shouldAnnounceMsg(null, "去時間B"))
        // 顯示：全文相同不刷新，相異刷新（batch/catalog 保留同式全文去重）。
        assertFalse(shouldUpdateWriteBlockText("同文", "同文"))
        assertTrue(shouldUpdateWriteBlockText("同文A", "同文B"))
        assertFalse(shouldUpdateBatchResultText("同文", "同文"))
        assertTrue(shouldUpdateBatchResultText("同文A", "同文B"))
        assertFalse(shouldUpdateCatalogErrorText("同文", "同文"))
        assertTrue(shouldUpdateCatalogErrorText("同文A", "同文B"))
    }

    @Test
    fun writeBlockDuplicate_differentKind_isFalseAfterStrip() {
        val noop = zhTestBlockText()(WriteBlock(WriteBlockKind.NOOP_FALLBACK))
        val pm = zhTestBlockText()(WriteBlock(WriteBlockKind.PM_UNREADABLE))
        assertFalse(isWriteBlockDuplicate(noop, pm, false))
        assertFalse(
            writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.NOOP_FALLBACK))) ==
                writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.PM_UNREADABLE))),
        )
    }

    // ---- B1：factory blockText 與 resolveBlockReason 同資源 ----
    // P2-3：AUTO 降級亦為 Keyed 同資源（主路徑無 Verbatim）。

    @Test
    fun factoryAndActivity_shareSameBlockResource() {
        // 兩側皆經 WriteBlock.stringRes() 取同一資源 id（factory 以 Application
        // Context 解析，Activity 以 Context.resolveBlockReason 解析；JVM 鎖 id）。
        assertEquals(R.string.write_block_lsposed, WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf("x")).stringRes())
        assertEquals(R.string.write_block_noop, WriteBlock(WriteBlockKind.NOOP_FALLBACK).stringRes())
        assertEquals(R.string.write_block_explicit, WriteBlock(WriteBlockKind.EXPLICIT_FAILURE, listOf("A", "B")).stringRes())
        assertEquals(R.string.write_block_pm, WriteBlock(WriteBlockKind.PM_UNREADABLE).stringRes())
        assertEquals(R.string.write_block_readonly, WriteBlock(WriteBlockKind.NO_PRIVILEGE).stringRes())
        assertEquals(R.string.auto_degrade_su_to_shizuku, WriteBlock(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU).stringRes())
        assertEquals(R.string.auto_degrade_both_failed, WriteBlock(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED).stringRes())
        assertEquals(R.string.auto_degrade_no_channel, WriteBlock(WriteBlockKind.AUTO_DEGRADE_NO_CHANNEL).stringRes())
        // Keyed 兩側同 fake 解析同文（含 AUTO 降級 Keyed）。
        val block = WriteBlock(WriteBlockKind.EXPLICIT_FAILURE, listOf("ROOT", "ROOT"))
        assertEquals(zhTestBlockText()(block), resolveZhTest(BlockReason.Keyed(block)))
        val degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU)
        assertEquals(zhTestBlockText()(degrade), resolveZhTest(BlockReason.Keyed(degrade)))
    }

    // ---- 雙源時間正規化：probe（HookDiagnosis.displayText 無時間）vs
    // writeBlock（HookEvidence.displayText 有時間）收斂為同一去重鍵 ----

    @Test
    fun strip_normalizesProbeBaseAndEvidenceTime_noTimeVsTimed() {
        // 無時間基底（probe 側 HookDiagnosis.displayText）。
        val base = HookDiagnosis(HookStatus.INACTIVE).displayText()
        // 有時間證據（writeBlock 側 HookEvidence.displayText）：無 marker、當次查詢時間。
        val timed = HookEvidence(
            status = HookStatus.INACTIVE,
            queriedAtMs = 1_700_000_000_000,
            markerTimeMs = null,
            viaGateway = false,
        ).displayText()
        assertTrue("timed must carry time suffix, base=$base timed=$timed", timed != base)
        assertTrue("timed must carry 查詢於", timed.contains("查詢於"))
        assertEquals(stripWriteBlockTimeParams(base), stripWriteBlockTimeParams(timed))
    }

    @Test
    fun strip_normalizesMarkerUnknownAndViaGateway() {
        val base = HookDiagnosis(HookStatus.ACTIVE, setOf("scope-a")).displayText()
        val timed = HookEvidence(
            status = HookStatus.ACTIVE,
            scopes = setOf("scope-a"),
            queriedAtMs = 1_700_000_000_000,
            markerTimeMs = null,
            viaGateway = true,
        ).displayText()
        assertTrue(timed.contains("經真通道讀取"))
        assertEquals(stripWriteBlockTimeParams(base), stripWriteBlockTimeParams(timed))
    }

    @Test
    fun strip_normalizesQueriedAtZeroVsTimed() {
        // 查詢時間 0（從未查詢）vs 有時間：去重鍵亦收斂（空態明確化不污染去重）。
        val never = HookEvidence(
            status = HookStatus.UNKNOWN_NO_DIAGNOSTIC,
            queriedAtMs = 0L,
            markerTimeMs = null,
            viaGateway = false,
        ).displayText()
        val timed = HookEvidence(
            status = HookStatus.UNKNOWN_NO_DIAGNOSTIC,
            queriedAtMs = 1_700_000_000_000,
            markerTimeMs = null,
            viaGateway = false,
        ).displayText()
        assertTrue(never.contains("從未查詢"))
        assertTrue(never != timed)
        assertEquals(stripWriteBlockTimeParams(never), stripWriteBlockTimeParams(timed))
    }

    @Test
    fun dedup_lsposedProbeVsWriteBlock_isDuplicateAndSameKey() {
        // 端到端：probe 文（無時間經 block 包裝）vs writeBlock 文（有時間）同文判重，
        // 去重鍵相等（時間戳漂移不拆鍵）。
        val probeBase = HookDiagnosis(HookStatus.INACTIVE).displayText()
        val evidenceTimed = HookEvidence(
            status = HookStatus.INACTIVE,
            queriedAtMs = 1_700_000_060_000,
            markerTimeMs = null,
            viaGateway = false,
        ).displayText()
        val probeText = zhTestBlockText()(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(probeBase)))
        val writeText = zhTestBlockText()(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(evidenceTimed)))
        assertTrue(probeText != writeText)
        assertTrue(isWriteBlockDuplicate(writeText, probeText, false))
        assertEquals(
            writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(probeBase)))),
            writeBlockDedupKey(BlockReason.Keyed(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(evidenceTimed)))),
        )
        assertFalse(shouldAnnounceMsg(stripWriteBlockTimeParams(writeText), stripWriteBlockTimeParams(probeText)))
    }

    // ---- 空白禁用可感知理由（VM 守衛不動，純函數門控） ----

    @Test
    fun blankReason_onlyBlankWritableIdle_shows() {
        assertTrue(shouldShowBlankInputReason(isBlank = true, busy = false, writeBlocked = false))
        assertFalse(shouldShowBlankInputReason(isBlank = true, busy = true, writeBlocked = false))
        assertFalse(shouldShowBlankInputReason(isBlank = true, busy = false, writeBlocked = true))
        assertFalse(shouldShowBlankInputReason(isBlank = false, busy = false, writeBlocked = false))
    }
}
