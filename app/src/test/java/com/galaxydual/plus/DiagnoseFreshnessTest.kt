// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-6 P2 (issue #27, 後半狀態模型): 診斷分離/新鮮度/歷史 marker。
// - unknown -> probe 成功不洗掉 (lastOperation UNKNOWN 需重新查證才清除)。
// - target 重建 (dualId 改變) 舊資料標 STALE (保留上次結果，不謊稱新鮮)。
// - 錯誤碼區分 (穩定碼每類 outcome 不同)。
// - 遮蔽 (匯出/預覽經 redactPii，大小受限，無自動上傳)。
// - Hook 時間證據 (scope 沿用 HookStateProvider + marker timeMs + 查詢時間)。
//
// Harness mirrors AutoDegradeTest/RemoveConfirmTest: DualViewModel runs on
// viewModelScope (Dispatchers.Main replaced with Unconfined), repo I/O on
// Dispatchers.IO, tests poll StateFlow with a timeout. No device, no Android
// runtime; error-semantic exit-0 listing failures avoid the core retry settle
// sleep (retry only triggers on non-zero exit).
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.RemoveOutcome
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import com.galaxydual.hook.HookMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnoseFreshnessTest {
    // Fake repo gateway: identity channels follow `dualId`; the
    // `pm list packages` probe succeeds or fails on `failListPackages`
    // (exit 0 + error-semantic text -> Unknown without retry sleeps).
    private class FakeRepo(
        var dualId: Int = 95,
        var failListPackages: Boolean = false,
        var listExtra: List<String> = emptyList(),
        val rootOk: Boolean = true,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS ->
                CmdResult(exit = 0, out = usersOut(), err = "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm install-existing") ->
                CmdResult(exit = 0, out = "Success", err = "")
            cmd.startsWith("pm uninstall") ->
                CmdResult(exit = 0, out = "Success", err = "")
            cmd.startsWith("pm list packages") ->
                if (failListPackages) CmdResult(exit = 0, out = "Failure [list failed]", err = "")
                else CmdResult(
                    exit = 0,
                    out = (listOf("package:com.example.chat") + listExtra.map { "package:$it" })
                        .joinToString("\n"),
                    err = "",
                )
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
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
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    // Monotonic fake clock: every nowMs() call advances, so probe vs
    // operation timestamps are deterministically ordered.
    private class FakeClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }
    }

    private fun makeVm(
        gw: FakeRepo,
        clock: FakeClock,
        directReads: () -> List<MarkerRead> = {
            listOf(MarkerRead.Readable(null), MarkerRead.Readable(null))
        },
    ): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(gw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = gw,
            shizukuGateway = ShizukuCmdGateway("com.example.test"),
            activeGateway = gw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = directReads,
            hookDiagnosticGateways = { emptyList() },
            nowMs = { clock.now() },
        )
        vms.add(vm)
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

    // unknown -> probe 成功不洗掉：clone VerifyUnknown 後 lastOperation 為
    // UNKNOWN；一次成功的 recheckPrivilege (probe) 不得清除它，需重新查證才清。
    @Test
    fun unknownOperation_survivesSuccessfulProbe() {
        val gw = FakeRepo(dualId = 95, failListPackages = true)
        val clock = FakeClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation != null }

        val last = vm.state.value.lastOperation
        assertNotNull("clone VerifyUnknown must record lastOperation", last)
        assertEquals(OpPhase.UNKNOWN, last!!.phase)
        assertEquals("clone:com.example.chat", last.opId)
        assertEquals("com.example.chat", last.appKey)
        assertEquals(95, last.pinnedTarget)
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
        val recordedTime = last.timeMs

        vm.recheckPrivilege()
        await { vm.state.value.hookQueriedAtMs > recordedTime }

        val after = vm.state.value.lastOperation
        assertNotNull("successful probe must not clear lastOperation", after)
        assertEquals("probe success must not rewrite lastOperation", last.opId, after!!.opId)
        assertEquals(recordedTime, after.timeMs)
        assertEquals(OpPhase.UNKNOWN, after.phase)
        assertEquals(
            "probe success must not clear the UNKNOWN diagnose either",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
    }

    // target 重建舊資料標上次結果：dualId 95 FRESH 後 target 換成 96 且
    // listing 未知 -> inventory STALE (保留舊 target/筆數)，cloned 保留舊資料。
    @Test
    fun targetRebuild_marksOldInventoryStale() {
        val gw = FakeRepo(dualId = 95, failListPackages = false)
        val clock = FakeClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        val fresh = vm.state.value.inventory
        assertEquals(95, fresh.targetId)
        assertEquals(1, fresh.count)
        assertNull("fresh listing clears the diagnose event", vm.state.value.diagnose)
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))

        gw.dualId = 96
        gw.failListPackages = true
        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.inventory.freshness == InventoryFreshness.STALE
        }

        val stale = vm.state.value.inventory
        assertEquals(
            "stale inventory keeps the old target identity",
            95,
            stale.targetId,
        )
        assertTrue(
            "old cloned entries are kept, not wiped",
            vm.state.value.cloned.contains("com.example.chat"),
        )
        assertEquals(
            "stale listing reports VERIFY_UNKNOWN",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
    }

    // 錯誤碼區分：每類 outcome 有自己的穩定碼；文案為繁中且非空；
    // 技術 detail 超長截斷 (大小受限)。
    @Test
    fun diagnoseCodes_distinguishOutcomesAndCapDetail() {
        assertEquals(DiagnoseCode.CHANNEL_ERROR, CloneOutcome.ChannelError("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.NO_DUAL, CloneOutcome.NoDual.toDiagnoseCode())
        assertEquals(DiagnoseCode.INVALID_INPUT, CloneOutcome.InvalidPackage("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.OP_FAILED, CloneOutcome.InstallFailed("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, CloneOutcome.VerifyFailed(95).toDiagnoseCode())
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, CloneOutcome.VerifyUnknown(95, "x").toDiagnoseCode())

        assertEquals(DiagnoseCode.STILL_INSTALLED, RemoveOutcome.StillInstalled(95).toDiagnoseCode())
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, RemoveOutcome.VerifyUnknown(95, "x").toDiagnoseCode())
        assertEquals(DiagnoseCode.CHANNEL_ERROR, RemoveOutcome.ChannelError("x").toDiagnoseCode())

        val codes = setOf(
            CloneOutcome.ChannelError("x").toDiagnoseCode(),
            CloneOutcome.NoDual.toDiagnoseCode(),
            CloneOutcome.InvalidPackage("x").toDiagnoseCode(),
            CloneOutcome.InstallFailed("x").toDiagnoseCode(),
            CloneOutcome.VerifyFailed(95).toDiagnoseCode(),
            CloneOutcome.VerifyUnknown(95, "x").toDiagnoseCode(),
            RemoveOutcome.StillInstalled(95).toDiagnoseCode(),
        )
        assertEquals("each failure class must have a distinct stable code", 7, codes.size)

        val stableIds = codes.map { it.stableId }
        assertEquals(stableIds.size, stableIds.toSet().size)

        val event = diagnoseEvent(
            DiagnoseCode.VERIFY_UNKNOWN,
            techDetail = "x".repeat(MAX_DIAG_DETAIL_CHARS + 500),
        )
        assertTrue("summary must be Traditional Chinese", event.summaryZh.isNotBlank())
        assertTrue("next step must be Traditional Chinese", event.nextStepZh.isNotBlank())
        assertTrue(
            "tech detail must be capped at $MAX_DIAG_DETAIL_CHARS, got ${event.techDetail.length}",
            event.techDetail.length <= MAX_DIAG_DETAIL_CHARS,
        )
    }

    // 遮蔽：匯出文字不含原始包名 (以 <pkg>/*** 取代)，大小受限，純函數可重複呼叫。
    @Test
    fun diagnosticExport_redactsPiiAndCapsSize() {
        val rawPkg = "com.example.secretchat"
        val state = UiState(
            inventory = InventorySnapshot(
                source = "pm list packages --user 95",
                targetId = 95,
                queryTimeMs = 123456789L,
                freshness = InventoryFreshness.FRESH,
                count = 1,
            ),
            lastOperation = LastOperation(
                opId = "clone:$rawPkg",
                appKey = rawPkg,
                pinnedTarget = 95,
                phase = OpPhase.UNKNOWN,
                timeMs = 123456789L,
                detail = "cannot verify $rawPkg for user 95: denied",
            ),
            diagnose = diagnoseEvent(
                DiagnoseCode.VERIFY_UNKNOWN,
                techDetail = "cannot verify $rawPkg for user 95: denied",
            ),
            hookStatus = HookStatus.ACTIVE,
            hookScopes = setOf(HookMarker.SCOPE_SYSTEM_SERVER),
            hookQueriedAtMs = 123456789L,
            hookMarkerTimeMs = 123400000L,
        )
        val first = buildDiagnosticExport(state)
        val second = buildDiagnosticExport(state)
        assertEquals("export must be a pure function (no upload side effects)", first, second)
        assertFalse("export must not contain the raw package name", first.contains(rawPkg))
        assertTrue("export keeps a redacted marker", first.contains("<pkg>") || first.contains("***"))
        assertTrue("export keeps the stable code", first.contains(DiagnoseCode.VERIFY_UNKNOWN.stableId))
        assertTrue(
            "export must be capped at $MAX_DIAG_EXPORT_CHARS, got ${first.length}",
            first.length <= MAX_DIAG_EXPORT_CHARS,
        )
    }

    // Hook 時間證據：scope 沿用 HookStateProvider 解析，另帶 marker timeMs
    // 與查詢時間；UNKNOWN 狀態顯示永不謊稱「未偵測到」。
    @Test
    fun hookEvidence_carriesScopeAndTime() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 1710000000000L)
        val clock = FakeClock()
        val vm = makeVm(
            FakeRepo(),
            clock,
            directReads = { listOf(MarkerRead.Readable(marker), MarkerRead.Readable(null)) },
        )
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        val s = vm.state.value
        assertTrue(s.hookActive)
        assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER), s.hookScopes)
        assertEquals(1710000000000L, s.hookMarkerTimeMs)
        assertTrue("queriedAtMs must record the probe wall-clock", s.hookQueriedAtMs > 0L)
        assertFalse(s.hookViaGateway)

        val evidence = HookEvidence(
            status = s.hookStatus,
            scopes = s.hookScopes,
            queriedAtMs = s.hookQueriedAtMs,
            markerTimeMs = s.hookMarkerTimeMs,
            viaGateway = s.hookViaGateway,
        )
        val text = evidence.displayText()
        assertTrue(text.contains(HookMarker.SCOPE_SYSTEM_SERVER))
        assertTrue(text.contains("僅存在性證據"))

        val unknownText = HookEvidence(status = HookStatus.UNKNOWN_NO_DIAGNOSTIC).displayText()
        assertTrue(unknownText.contains("狀態未知"))
        assertFalse(unknownText.contains("未偵測到"))
    }
}
