// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase A P1-3 回歸測試：正常 probe 失敗（非 throw）與 write-guard
// 拒絕產生 diagnose；冷啟動無通道時匯出不得宣稱 OK；sticky VERIFY_UNKNOWN
// 不被 probe 失敗洗掉。階段F 例外：LSPOSED 佔位寫入拒絕在無 sticky 歷史時
// 記 CHANNEL_ERROR（政策拒寫，非探測失敗），零探測本身仍永不寫 E_PROBE。
//
// Harness 沿用 DiagnoseFreshnessPhase5Test。
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProbeDiagnoseTest {
    private class FakeRepo(
        var dualId: Int = 95,
        var failListPackages: Boolean = false,
        var rootOk: Boolean = true,
        var shizukuOk: Boolean = false,
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
            cmd.startsWith("pm list packages") ->
                if (failListPackages) CmdResult(exit = 0, out = "Failure [list failed]", err = "")
                else CmdResult(exit = 0, out = "package:com.example.chat", err = "")
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    // Shizuku 側沿用 NoOp（探測永遠失敗）：rootOk=false 時兩通道皆未通過，
    // 冷啟動即 ready=false。
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

    private class JumpClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }
    }

    private fun makeVm(gw: FakeRepo, clock: JumpClock): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(gw),
            gatewayPrivilege = Privilege.ROOT,
            // su 探測走真實 SuElevator（rootOk=false 即失敗），Shizuku 走 NoOp
            //（永遠失敗）：兩通道皆未通過，冷啟動即 ready=false。
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
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            // C1 雙時鐘測試同餵：wall/mono 共用同一 fake，沿既有 JumpClock/FakeClock 模式。
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
            blockText = zhTestBlockText(),
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

    // 兩通道皆未通過 → 正常 probe 失敗產生 diagnose，匯出不含 OK。
    @Test
    fun probeFailureWithoutChannel_setsDiagnoseAndExportNotOk() {
        val gw = FakeRepo(rootOk = false)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        // 冷啟動 probe 完成且通道未就緒。
        await { !vm.state.value.loading }
        assertEquals(false, vm.state.value.channelReady)

        val diag = vm.state.value.diagnose
        assertNotNull("cold-start probe failure must produce a diagnose", diag)
        assertEquals(DiagnoseCode.PROBE_FAILED, diag!!.code)

        val export = buildDiagnosticExport(vm.state.value)
        assertFalse(
            "export must not claim OK when no channel passed, got: $export",
            export.contains("OK（無異常）"),
        )
        assertTrue(
            "export must carry the probe stable id, got: $export",
            export.contains(DiagnoseCode.PROBE_FAILED.stableId),
        )
    }

    // sticky VERIFY_UNKNOWN 不被後續 probe 失敗洗掉。
    @Test
    fun stickyVerifyUnknown_survivesProbeFailure() {
        val gw = FakeRepo(failListPackages = true, rootOk = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.phase == OpPhase.UNKNOWN }
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
        val baseOp = vm.state.value.lastOperation!!

        // 通道全失後的 probe 失敗不得洗掉 sticky 歷史。
        gw.rootOk = false
        gw.failListPackages = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && !vm.state.value.channelReady }

        assertEquals(
            "probe failure must not overwrite sticky VERIFY_UNKNOWN",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
        assertEquals("probe failure must not rewrite lastOperation", baseOp.opId, vm.state.value.lastOperation?.opId)
        assertEquals(baseOp.timeMs, vm.state.value.lastOperation?.timeMs)
    }

    // P2-3 通道恢復清過時 E_PROBE：雙通道全失得 PROBE_FAILED 後恢復，
    // recheck 輕探（ready=false→true，不經 full refresh）須清 diagnose。
    // 無 sticky UNKNOWN lastOperation 才清；規則見 probeChannels 註解。
    @Test
    fun recoveredChannel_clearsStaleProbeFailed() {
        val gw = FakeRepo(rootOk = false)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { !vm.state.value.loading }
        assertEquals(false, vm.state.value.channelReady)
        assertEquals(
            "雙通道全失須先得 PROBE_FAILED",
            DiagnoseCode.PROBE_FAILED,
            vm.state.value.diagnose?.code,
        )
        assertNull("前置：無操作歷史才測清除", vm.state.value.lastOperation)

        gw.rootOk = true
        vm.recheckPrivilege()
        await { !vm.state.value.loading && vm.state.value.channelReady }

        assertNull(
            "通道恢復的輕探須清除過時 PROBE_FAILED，got: ${vm.state.value.diagnose}",
            vm.state.value.diagnose,
        )
        val export = buildDiagnosticExport(vm.state.value)
        assertTrue(
            "清除後匯出須回 OK（無異常），got: $export",
            export.contains("OK（無異常）"),
        )
    }

    // P2-3 sticky VERIFY_UNKNOWN 下恢復仍保留：操作歷史優先於清除，
    // 通道恢復（ready=true）的輕探不得洗掉待重新查證的歷史。
    @Test
    fun recoveredChannel_retainsStickyVerifyUnknown() {
        val gw = FakeRepo(failListPackages = true, rootOk = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.phase == OpPhase.UNKNOWN }
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
        val baseOp = vm.state.value.lastOperation!!

        // 先全失（失敗不洗 sticky），再恢復（成功亦不洗 sticky）。
        gw.rootOk = false
        gw.failListPackages = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && !vm.state.value.channelReady }
        assertEquals(
            "全失不得洗 sticky",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )

        gw.rootOk = true
        vm.recheckPrivilege()
        await { !vm.state.value.loading && vm.state.value.channelReady }

        assertEquals(
            "恢復的輕探須保留 sticky VERIFY_UNKNOWN，不得清為 null",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
        assertEquals("恢復不得改寫 lastOperation", baseOp.opId, vm.state.value.lastOperation?.opId)
        assertEquals(baseOp.timeMs, vm.state.value.lastOperation?.timeMs)
    }

    // Phase B P0 (B1)：LSPOSED 零探測永不誤寫 E_PROBE。
    // 反例：preference=LSPOSED 冷啟動 → ready=false 恆成立 → 舊碼
    // cur.diagnose ?: PROBE_FAILED 寫 E_PROBE，但 probeResultMark 顯示
    // 「未探測」，同畫面「未探測」+「探測未通過」矛盾。
    // 契約 121-126 / 3534-3538：scope NONE=未探測，絕不報未通過。
    @Test
    fun lsposedZeroProbe_neverWritesProbeFailed() {
        val suFake = FakeRepo(rootOk = true)
        val clock = JumpClock()
        val vm = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.LSPOSED,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { false },
            suGateway = suFake,
            shizukuGateway = NoOpGateway(),
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
            blockText = zhTestBlockText(),
        )
        vms.add(vm)
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertEquals("LSPOSED 冷啟動永為未就緒", false, s.channelReady)
        assertEquals("LSPOSED 零探測 scope 須為 NONE", ProbeScope.NONE, s.probeScope)
        assertTrue(
            "LSPOSED 零探測須顯示未探測，got: ${s.probeResultMark()}",
            s.probeResultMark().contains("未探測"),
        )
        assertNotEquals(
            "LSPOSED 零探測絕不寫 PROBE_FAILED/CHANNEL_ERROR，got: ${s.diagnose}",
            DiagnoseCode.PROBE_FAILED,
            s.diagnose?.code,
        )
        assertNotEquals(
            "LSPOSED 零探測絕不寫 CHANNEL_ERROR，got: ${s.diagnose}",
            DiagnoseCode.CHANNEL_ERROR,
            s.diagnose?.code,
        )
        // isWritable：佔位拒絕寫入不得寫 PROBE_FAILED（零探測規則僅限
        // 探測路徑）；階段F 起，使用者明確寫入遭政策拒絕時，若無 sticky
        // 歷史則記 CHANNEL_ERROR（政策拒寫，非探測失敗），以免匯出宣稱 OK。
        vm.clone("com.example.chat")
        await { !vm.state.value.loading }
        assertNotEquals(
            "LSPOSED 寫入拒絕亦不得寫 PROBE_FAILED，got: ${vm.state.value.diagnose}",
            DiagnoseCode.PROBE_FAILED,
            vm.state.value.diagnose?.code,
        )
        assertEquals(
            "LSPOSED 明確寫入遭政策拒絕須記 CHANNEL_ERROR（非 PROBE），got: ${vm.state.value.diagnose}",
            DiagnoseCode.CHANNEL_ERROR,
            vm.state.value.diagnose?.code,
        )
        assertTrue(
            "CHANNEL_ERROR techDetail 須標註佔位/政策拒寫，got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").contains("LSPOSED placeholder / writes unsupported"),
        )
        assertTrue(
            "LSPOSED 仍須顯示未探測，got: ${vm.state.value.probeResultMark()}",
            vm.state.value.probeResultMark().contains("未探測"),
        )
    }

    // 舊 sticky 保留：LSPOSED 冷啟動後以 refresh 預置 CHANNEL_ERROR
    //（NoOp repo 快照失敗分支），其後 probe（recheck 輕探）須保留舊碼，
    //不被覆寫為 PROBE_FAILED/CHANNEL_ERROR 新碼。
    @Test
    fun lsposedProbe_retainsStickyChannelError() {
        val suFake = FakeRepo(rootOk = true)
        val clock = JumpClock()
        val vm = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.LSPOSED,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { false },
            suGateway = suFake,
            shizukuGateway = NoOpGateway(),
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
            blockText = zhTestBlockText(),
        )
        vms.add(vm)
        await { !vm.state.value.loading }

        // 預置：NoOp repo 的 refresh 必走 ChannelError，寫入 CHANNEL_ERROR。
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals(
            "前置：refresh 須預置 CHANNEL_ERROR",
            DiagnoseCode.CHANNEL_ERROR,
            vm.state.value.diagnose?.code,
        )

        // LSPOSED 輕探不得覆寫舊 sticky。
        vm.recheckPrivilege()
        await { !vm.state.value.loading }
        assertEquals(
            "LSPOSED probe 須保留舊 CHANNEL_ERROR，不得覆寫，got: ${vm.state.value.diagnose}",
            DiagnoseCode.CHANNEL_ERROR,
            vm.state.value.diagnose?.code,
        )
        assertNotEquals(
            "LSPOSED probe 保留舊碼時不得寫 PROBE_FAILED，got: ${vm.state.value.diagnose}",
            DiagnoseCode.PROBE_FAILED,
            vm.state.value.diagnose?.code,
        )
        assertEquals("LSPOSED probe 後 scope 仍為 NONE", ProbeScope.NONE, vm.state.value.probeScope)
        assertTrue(
            "LSPOSED probe 後仍須顯示未探測，got: ${vm.state.value.probeResultMark()}",
            vm.state.value.probeResultMark().contains("未探測"),
        )
    }

    // throw 路徑 LSPOSED 測：probe 過程 throw（以外層 catch 覆蓋為準，
    // 此處以時鐘異常模擬 gateway/IO 拋錯）時 LSPOSED 仍保留舊 diagnose，
    // 不寫 PROBE_FAILED。
    @Test
    fun lsposedThrow_retainsStickyDiagnose() {
        val suFake = FakeRepo(rootOk = true)
        val clock = JumpClock()
        var clockThrows = false
        val vm = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.LSPOSED,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { false },
            suGateway = suFake,
            shizukuGateway = NoOpGateway(),
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = {
                if (clockThrows) throw RuntimeException("boom")
                clock.now()
            },
            nowMonoMs = { clock.now() },
            blockText = zhTestBlockText(),
        )
        vms.add(vm)
        await { !vm.state.value.loading }

        // 預置舊 sticky：同上以 refresh 寫入 CHANNEL_ERROR。
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals(
            "前置：throw 前須預置 CHANNEL_ERROR",
            DiagnoseCode.CHANNEL_ERROR,
            vm.state.value.diagnose?.code,
        )

        // 外層 throw（probeNow 取時拋錯 → probeChannels 外層 catch LSPOSED 分支）。
        clockThrows = true
        vm.recheckPrivilege()
        await { !vm.state.value.loading }
        clockThrows = false

        assertEquals(
            "LSPOSED throw 路徑須保留舊 CHANNEL_ERROR，got: ${vm.state.value.diagnose}",
            DiagnoseCode.CHANNEL_ERROR,
            vm.state.value.diagnose?.code,
        )
        assertNotEquals(
            "LSPOSED throw 路徑不得寫 PROBE_FAILED，got: ${vm.state.value.diagnose}",
            DiagnoseCode.PROBE_FAILED,
            vm.state.value.diagnose?.code,
        )
        assertEquals(false, vm.state.value.loading)
    }

    private fun makeLsposedNoopVm(clock: JumpClock): DualViewModel {
        val suFake = FakeRepo(rootOk = true)
        val vm = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.LSPOSED,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { false },
            suGateway = suFake,
            shizukuGateway = NoOpGateway(),
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
            blockText = zhTestBlockText(),
        )
        vms.add(vm)
        return vm
    }

    // 階段F：冷啟動 LSPOSED/NoOp、無先行 refresh（diagnose==null）、探測完成後，
    // 使用者明確 clone 遭政策拒絕 → 記 CHANNEL_ERROR（非 PROBE），匯出非 OK。
    @Test
    fun lsposedExplicitClone_refusedWritesChannelError() {
        val clock = JumpClock()
        val vm = makeLsposedNoopVm(clock)
        // 探測完成：冷啟動 probe 跑完（loading 收斂），零探測 scope=NONE，
        // diagnose 仍 null（探測路徑永不寫碼）。
        await { !vm.state.value.loading && vm.state.value.probeScope == ProbeScope.NONE }
        assertNull(
            "前置：探測完成後 diagnose 須仍為 null，got: ${vm.state.value.diagnose}",
            vm.state.value.diagnose,
        )
        assertNull("前置：冷啟動無操作歷史", vm.state.value.lastOperation)

        vm.clone("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        // 拒寫：零安裝（cloned 未動、無 lastOperation），msg 明確拒寫。
        assertTrue(
            "拒寫後 cloned 須為空（零 install），got: ${vm.state.value.cloned}",
            vm.state.value.cloned.isEmpty(),
        )
        assertNull(
            "拒寫不得建立 lastOperation，got: ${vm.state.value.lastOperation}",
            vm.state.value.lastOperation,
        )
        assertTrue(
            "拒寫 msg 須明確拒寫，got: ${vm.state.value.msg}",
            vm.state.value.msg.contains("寫入已拒絕"),
        )
        // 診斷碼真實性：CHANNEL_ERROR，非 PROBE；techDetail 明確佔位/政策拒寫。
        assertEquals(
            "明確寫入遭拒須記 CHANNEL_ERROR，got: ${vm.state.value.diagnose}",
            DiagnoseCode.CHANNEL_ERROR,
            vm.state.value.diagnose?.code,
        )
        assertNotEquals(
            "政策拒寫絕非探測失敗，got: ${vm.state.value.diagnose}",
            DiagnoseCode.PROBE_FAILED,
            vm.state.value.diagnose?.code,
        )
        assertTrue(
            "techDetail 須標註佔位/政策拒寫，got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").contains("LSPOSED placeholder / writes unsupported"),
        )
        assertTrue(
            "techDetail 須明確非探測失敗，got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").contains("not a probe failure"),
        )
        // 零探測顯示不變：仍顯示未探測。
        assertTrue(
            "拒寫後仍須顯示未探測，got: ${vm.state.value.probeResultMark()}",
            vm.state.value.probeResultMark().contains("未探測"),
        )
        // 匯出一致性：非 OK，攜帶 CHANNEL 穩定碼（或佔位 techDetail）。
        val export = buildDiagnosticExport(vm.state.value)
        assertFalse(
            "拒寫後匯出不得宣稱 OK，got: $export",
            export.contains("OK（無異常）"),
        )
        assertTrue(
            "拒寫後匯出須攜帶 CHANNEL 穩定碼，got: $export",
            export.contains(DiagnoseCode.CHANNEL_ERROR.stableId),
        )
    }

    // 階段F remove 路徑：requestRemove 遭政策拒絕 → diagnose 非 null、匯出非 OK。
    @Test
    fun lsposedExplicitRemove_refusedWritesChannelError() {
        val clock = JumpClock()
        val vm = makeLsposedNoopVm(clock)
        await { !vm.state.value.loading && vm.state.value.probeScope == ProbeScope.NONE }
        assertNull(
            "前置：探測完成後 diagnose 須仍為 null，got: ${vm.state.value.diagnose}",
            vm.state.value.diagnose,
        )

        vm.requestRemove("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose != null }

        assertEquals(
            "remove 拒寫須記 CHANNEL_ERROR（非 PROBE），got: ${vm.state.value.diagnose}",
            DiagnoseCode.CHANNEL_ERROR,
            vm.state.value.diagnose?.code,
        )
        assertNotEquals(
            "remove 政策拒寫絕非探測失敗，got: ${vm.state.value.diagnose}",
            DiagnoseCode.PROBE_FAILED,
            vm.state.value.diagnose?.code,
        )
        assertTrue(
            "remove 拒寫 techDetail 須標註佔位/政策拒寫，got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").contains("LSPOSED placeholder / writes unsupported"),
        )
        assertTrue(
            "remove 拒寫後 pendingRemove 須為 null（未建確認），got: ${vm.state.value.pendingRemove}",
            vm.state.value.pendingRemove == null,
        )
        val export = buildDiagnosticExport(vm.state.value)
        assertFalse(
            "remove 拒寫後匯出不得宣稱 OK，got: $export",
            export.contains("OK（無異常）"),
        )
        assertTrue(
            "remove 拒寫後匯出須攜帶 CHANNEL 穩定碼，got: $export",
            export.contains(DiagnoseCode.CHANNEL_ERROR.stableId),
        )
    }

    // 階段F review 補測：LSPOSED 已有 sticky 診斷時，佔位 clone 拒絕須保留原值，
    // 不得被佔位 CHANNEL_ERROR 覆寫（同值，非新事件）。
    @Test
    fun lsposedCloneRefused_retainsStickyChannelError() {
        val clock = JumpClock()
        val vm = makeLsposedNoopVm(clock)
        await { !vm.state.value.loading && vm.state.value.probeScope == ProbeScope.NONE }
        assertNull(
            "前置：探測完成後 diagnose 須仍為 null，got: ${vm.state.value.diagnose}",
            vm.state.value.diagnose,
        )

        // 先製造既有診斷：NoOp repo 的 refresh 必走 ChannelError。
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        val base = vm.state.value.diagnose!!
        // refresh 經 NoOp 快照失敗，detail 來自 gateway，不含佔位拒寫字串。
        assertFalse(
            "前置：refresh 預置的 CHANNEL_ERROR 不得已含佔位字串，got: ${base.techDetail}",
            base.techDetail.contains("LSPOSED placeholder / writes unsupported"),
        )

        // 佔位 clone 遭政策拒絕：須保留原值（同值），msg 仍報拒寫。
        vm.clone("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.msg.contains("寫入已拒絕") }

        val after = vm.state.value.diagnose
        assertNotNull("sticky 保留後 diagnose 不得為 null", after)
        assertEquals(
            "LSPOSED 拒寫須保留原診斷（同值），got: $after, want: $base",
            base,
            after,
        )
        assertEquals(DiagnoseCode.CHANNEL_ERROR, after!!.code)
        assertFalse(
            "保留原值時不得被佔位 techDetail 覆寫，got: ${after.techDetail}",
            after.techDetail.contains("LSPOSED placeholder / writes unsupported"),
        )
        assertTrue(
            "拒寫後 cloned 須仍為空，got: ${vm.state.value.cloned}",
            vm.state.value.cloned.isEmpty(),
        )
        assertNull(
            "拒寫不得建立 lastOperation，got: ${vm.state.value.lastOperation}",
            vm.state.value.lastOperation,
        )
        assertEquals("拒寫後 scope 仍為 NONE", ProbeScope.NONE, vm.state.value.probeScope)
    }

    // 階段F review 補測：lastOperation==UNKNOWN sticky 下，write-guard 拒寫
    //（通道未就緒）須保留 VERIFY_UNKNOWN 與原 lastOperation，不寫新碼。
    @Test
    fun writeGuardRefused_retainsStickyVerifyUnknown() {
        val gw = FakeRepo(failListPackages = true, rootOk = true)
        val clock = JumpClock()
        // 明選 ROOT（非 AUTO）：拒寫理由為「所選模式…寫入已禁用」，與輕探
        // 失敗訊息不同，拒寫可觀測；AUTO 全失時兩者皆為 degradeNote 會無變化。
        val vm = DualViewModel(
            repo = DualRepository(gw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.ROOT,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = gw,
            shizukuGateway = ShizukuCmdGateway("com.example.test"),
            activeGateway = gw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
            blockText = zhTestBlockText(),
        )
        vms.add(vm)
        await { vm.state.value.channelReady }

        // 既有 UNKNOWN 製造手法：clone 後 verify 查無 → UNKNOWN。
        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.phase == OpPhase.UNKNOWN }
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
        val baseOp = vm.state.value.lastOperation!!
        val baseDiag = vm.state.value.diagnose!!

        // 通道全失，輕探保留 sticky（與既有用例同），再觸發 write-guard 拒寫。
        gw.rootOk = false
        gw.failListPackages = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && !vm.state.value.channelReady }
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)

        // 第二次 clone 走 !channelReady 拒寫分支（非 LSPOSED），須保留 sticky。
        // 明選 ROOT 拒寫理由含「寫入已禁用」，與輕探失敗訊息可區分。
        vm.clone("com.example.other")
        await { !vm.state.value.loading && vm.state.value.msg.contains("寫入已禁用") }

        assertTrue(
            "write-guard 拒寫 msg 須明確拒寫，got: ${vm.state.value.msg}",
            vm.state.value.msg.contains("寫入已禁用"),
        )
        assertEquals(
            "write-guard 拒寫不得覆寫 sticky VERIFY_UNKNOWN",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
        assertEquals(
            "write-guard 拒寫須保留原診斷同值，got: ${vm.state.value.diagnose}, want: $baseDiag",
            baseDiag,
            vm.state.value.diagnose,
        )
        assertEquals("拒寫不得改寫 lastOperation", baseOp.opId, vm.state.value.lastOperation?.opId)
        assertEquals(baseOp.timeMs, vm.state.value.lastOperation?.timeMs)
        assertEquals(OpPhase.UNKNOWN, vm.state.value.lastOperation?.phase)
    }
}
