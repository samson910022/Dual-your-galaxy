// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase A P1-3 回歸測試：正常 probe 失敗（非 throw）與 write-guard
// 拒絕產生 diagnose；冷啟動無通道時匯出不得宣稱 OK；sticky VERIFY_UNKNOWN
// 不被 probe 失敗洗掉。
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
import org.junit.Assert.assertNotNull
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
}
