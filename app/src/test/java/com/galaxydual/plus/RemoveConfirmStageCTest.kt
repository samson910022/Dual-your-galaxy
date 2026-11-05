// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-27 Stage C (P1 一致性/種類錯位)：request SerialUnavailable 種類、C2 第二段
// id-drift 漏碼、C5 confirm-NoDual typed 回退。Harness 沿用 RemoveConfirmTest
// (Unconfined Main + IO poll)，identity 通道加計數以支援確定性 mid-confirm 漂移。
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoveConfirmStageCTest {
    // Counting gateway：identity 通道 (verbose/dumpsys/prop) 共用 identityCalls
    // 計數，支援確定性 mid-confirm 漂移 (C2/C5)；listUsers 另計，不影響 identity。
    private class DriftGateway(var baseId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        var uninstallCalls = 0
        var listPackagesOut = ""
        val installedPkgs = mutableSetOf("com.example.chat")
        var serial: Int = 15
        var noSerial: Boolean = false
        var driftId: Int = 96
        var identityCalls = 0
        var ownerOnlyFrom = Int.MAX_VALUE
        var driftFrom = Int.MAX_VALUE
        var listUsersCalls = 0

        private fun effId(): Int {
            return when {
                identityCalls >= driftFrom -> driftId
                identityCalls >= ownerOnlyFrom -> -1
                else -> baseId
            }
        }

        private fun usersOut(id: Int): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                (if (id < 0) "" else "\tUserInfo{$id:DualApp:400} running\n")

        private fun verboseOut(id: Int): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                (if (id < 0) "" else "  UserInfo{$id:DualApp:400} running:\n" +
                    (if (noSerial) "" else "    Serial: $serial\n") +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n")

        private fun dumpsysOut(id: Int): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                (if (id < 0) "" else "  UserInfo{$id:DualApp:400}" + (if (noSerial) "" else " serialNo=$serial") + ":\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n")

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" -> CmdResult(exit = 0, out = "0\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls++
                // Stage C：listUsers 亦跟 identity 漂移 (以當前 identityCalls
                // 判 effId)，使新鮮快照 pmIds 與 typed 同步，免舊 pm 否決遮蔽真漂移。
                CmdResult(exit = 0, out = usersOut(effId()), err = "")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                identityCalls++
                CmdResult(exit = 0, out = verboseOut(effId()), err = "")
            }
            cmd == DualRepository.CMD_DUMPSYS_USER -> {
                identityCalls++
                CmdResult(exit = 0, out = dumpsysOut(effId()), err = "")
            }
            cmd == DualRepository.CMD_GETPROP_DUAL -> {
                identityCalls++
                val id = effId()
                CmdResult(exit = 0, out = if (id < 0) "" else "$id\n", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                uninstallCalls++
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                installedPkgs.remove(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val base = installedPkgs.map { "package:$it" }.joinToString("\n")
                val out = if (base.isEmpty()) listPackagesOut else if (listPackagesOut.isEmpty()) base else "$base\n$listPackagesOut"
                CmdResult(exit = 0, out = out, err = "")
            }
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

    private fun makeVm(gw: DriftGateway): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(gw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = SuCmdGateway(),
            shizukuGateway = ShizukuCmdGateway("com.example.test"),
            activeGateway = gw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        return vm
    }

    private fun awaitVm(vm: DualViewModel, timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (!cond()) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("timeout")
                Thread.sleep(20)
            }
        } catch (e: AssertionError) {
            throw AssertionError("timeout waiting for condition; state=${vm.state.value}")
        }
    }

    // C1：verbose 缺 serial 行 → SerialUnavailable 須為 VERIFY_MISMATCH + INVALID
    // (與 confirm 內 serial 缺失/不一致一致)，非 CHANNEL_ERROR/NO_DUAL。
    @Test
    fun requestSerialUnavailable_mapsVerifyMismatch() {
        val gw = DriftGateway(baseId = 95)
        gw.noSerial = true
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        vm.requestRemove("com.example.chat")
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("serial") }
        assertNull(vm.state.value.pendingRemove)
        assertEquals(0, gw.uninstallCalls)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, vm.state.value.diagnose?.code)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertTrue(
            "techDetail must carry the serial prefix, got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").startsWith("serial unavailable"),
        )
    }

    // C2：兩次 fetchDualTarget 間改 dualId (第二段 id-drift) 須補
    // diagnose=VERIFY_MISMATCH (與第一段同形)，零卸載。
    @Test
    fun confirmSecondFetchIdDrift_setsMismatch() {
        val gw = DriftGateway(baseId = 95).apply { driftId = 96 }
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }
        // request 完成後的 identityCalls 為 R；confirm 首段 typed 用 R+1..R+3 (base)，
        // 第二段 fetch 用 R+4.. (drift)，故閾值設 R+4。
        gw.driftFrom = gw.identityCalls + 4
        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }
        assertEquals("drifted confirm must not uninstall", 0, gw.uninstallCalls)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, vm.state.value.diagnose?.code)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertTrue(
            "techDetail must carry verified-id drift, got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").startsWith("verified id 96 != 95"),
        )
    }

    // C5：confirm-NoDual 後備分支先走 typed 入口；Confirmed(otherId) → MISMATCH，
    // 不落入 targetListed 啟發式。repo 內 verified 先見 owner-only 而回 NoDual，
    // 後備 typed 再見 drift 96 即判 MISMATCH。
    @Test
    fun confirmNoDualButTypedDrift_mapsMismatch() {
        val gw = DriftGateway(baseId = 95).apply { driftId = 96 }
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }
        // R 為 request 後計數；首段 (R+1..R+3) 與第二段 (R+4..R+6) 見 base 95
        // (預檢通過)；verifyState 重驗 (R+7..R+9) 見 base (通過)；repo 內
        // verified (R+10..R+12) 見 owner-only 而回 NoDual；後備新鮮快照見
        // owner-only {0} (pmIds 子集免否決)，後備 typed (R+13..) 見 drift 96
        // → MISMATCH。
        val r = gw.identityCalls
        gw.ownerOnlyFrom = r + 10
        gw.driftFrom = r + 13
        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }
        assertEquals("drifted NoDual fallback must not uninstall", 0, gw.uninstallCalls)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, vm.state.value.diagnose?.code)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertTrue(
            "fallback drift must name the ids, got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").startsWith("verified id 96 != 95"),
        )
    }
}
