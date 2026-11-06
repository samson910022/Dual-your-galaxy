// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-27 Stage C：C3 E_CHANNEL 前綴契約 + C4 FAILED+FRESH 三者一致。
// Harness 沿用 DiagnoseFreshnessPhase5Test (Unconfined Main + poll)。
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
class DiagnoseFreshnessStageCTest {
    private class FakeRepo(
        var dualId: Int = 95,
        var failListPackages: Boolean = false,
        var failInstall: Boolean = false,
        var failListUsers: Boolean = false,
        var emptyUsers: Boolean = false,
        var ownerOnlyUsers: Boolean = false,
        var ownerOnlyTyped: Boolean = false,
        var divergentPmExtra: Boolean = false,
        var listExtra: List<String> = emptyList(),
        var rootOk: Boolean = true,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT

        private fun usersOut(id: Int): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$id:DualApp:400} running\n"

        private fun ownerUsersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n"

        private fun extraUsersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{10:Work profile:30} running\n" +
                "\tUserInfo{95:DualApp:400} running\n"

        private fun nodualVerboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{10:Work profile:30} running:\n" +
                "    Serial: 11\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.MANAGED\n"

        private fun nodualDumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{10:Work profile:30} serialNo=11:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.MANAGED\n"

        private fun verboseOut(id: Int): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$id:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun ownerVerboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n"

        private fun dumpsysOut(id: Int): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$id:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        private fun ownerDumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n"

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS -> when {
                failListUsers -> CmdResult(exit = 0, out = "Failure [users failed]", err = "")
                emptyUsers -> CmdResult(exit = 0, out = "Users:\n", err = "")
                divergentPmExtra -> CmdResult(exit = 0, out = extraUsersOut(), err = "")
                ownerOnlyUsers -> CmdResult(exit = 0, out = ownerUsersOut(), err = "")
                else -> CmdResult(exit = 0, out = usersOut(dualId), err = "")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(
                    exit = 0,
                    out = if (divergentPmExtra) nodualVerboseOut() else if (ownerOnlyTyped) ownerVerboseOut() else verboseOut(dualId),
                    err = "",
                )
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(
                    exit = 0,
                    out = if (divergentPmExtra) nodualDumpsysOut() else if (ownerOnlyTyped) ownerDumpsysOut() else dumpsysOut(dualId),
                    err = "",
                )
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                if (divergentPmExtra || ownerOnlyTyped) CmdResult(exit = 0, out = "", err = "")
                else CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm install-existing") ->
                if (failInstall) CmdResult(exit = 1, out = "", err = "Failure [install failed]")
                else CmdResult(exit = 0, out = "Success", err = "")
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
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
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

    // C3：9 碼不變 + 前綴契約鎖定 (純測，不碰裝置)。
    @Test
    fun channelPrefixes_contractLocked() {
        assertEquals(9, DiagnoseCode.values().size)
        // CHANNEL 桶前綴：建構後原樣保留，碼為 CHANNEL_ERROR。
        val channelPrefixes = listOf(
            "verified dual id unknown",
            "verified dual id unknown: boom",
            "verified dual id unknown and profiles snapshot empty",
            "verified dual id unknown for expected 95",
            "verified dual id unknown for listed target 95",
            "verified dual id 95 absent and profiles snapshot empty",
            "profiles snapshot empty despite typed absent",
            "profiles snapshot channel failed: boom",
        )
        for (p in channelPrefixes) {
            val e = diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = p)
            assertEquals(DiagnoseCode.CHANNEL_ERROR, e.code)
            assertTrue("prefix must survive capDetail, got: ${e.techDetail}", e.techDetail.startsWith(p))
        }
        // 非 CHANNEL 桶辨析：serial/verified-id 前綴一律 VERIFY_MISMATCH。
        val mismatchPrefixes = listOf(
            "serial unavailable for verified target",
            "serial unavailable for user 95 (expected 15)",
            "serial 41 != 15 for user 95",
            "verified id 96 != 95",
            "verified id drifted: listed 95 but now 96",
        )
        for (p in mismatchPrefixes) {
            val e = diagnoseEvent(DiagnoseCode.VERIFY_MISMATCH, techDetail = p)
            assertEquals(DiagnoseCode.VERIFY_MISMATCH, e.code)
            assertTrue(e.techDetail.startsWith(p))
        }
        // NO_DUAL 前綴獨立。
        val absent = diagnoseEvent(DiagnoseCode.NO_DUAL, techDetail = "typed resolution absent")
        assertEquals(DiagnoseCode.NO_DUAL, absent.code)
        assertTrue(absent.techDetail.startsWith("typed resolution absent"))
    }

    // C3 VM：divergent pm 否決 → typed Unknown，refresh 報
    // "verified dual id unknown: ..." (CHANNEL_ERROR)。
    @Test
    fun channelPrefix_vmDivergentPm_mapsUnknownPrefix() {
        val gw = FakeRepo(divergentPmExtra = true)
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals(DiagnoseCode.CHANNEL_ERROR, vm.state.value.diagnose?.code)
        assertTrue(
            "techDetail must start with verified-unknown prefix, got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").startsWith("verified dual id unknown"),
        )
    }

    // C3 VM：空快照 + typed Absent → "profiles snapshot empty despite typed absent"。
    @Test
    fun channelPrefix_vmEmptySnapshotDespiteAbsent() {
        val gw = FakeRepo(emptyUsers = true, ownerOnlyTyped = true)
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals(
            "profiles snapshot empty despite typed absent",
            vm.state.value.diagnose?.techDetail,
        )
    }

    // C3 VM：request Confirmed 但快照空 → "verified dual id <id> absent and profiles
    // snapshot empty" (CHANNEL_ERROR，非 NO_DUAL)。
    @Test
    fun channelPrefix_vmRequestAbsentAndEmpty() {
        val gw = FakeRepo(emptyUsers = true)
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        vm.requestRemove("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertTrue(
            "techDetail must name absent+empty, got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").startsWith("verified dual id 95 absent and profiles snapshot empty"),
        )
    }

    // C4 (選 c)：clone InstallFailed (FAILED) 後 refresh FRESH 成功須同步清除
    // FAILED 歷史，三者一致 (diagnose null + 無 FAILED + 就緒 msg + FRESH)。
    @Test
    fun failedHistory_clearedByFreshSuccess() {
        val gw = FakeRepo(failInstall = true)
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.phase == OpPhase.FAILED }
        assertEquals(DiagnoseCode.OP_FAILED, vm.state.value.diagnose?.code)

        gw.failInstall = false
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertNull("FRESH 成功須清 diagnose，got: ${vm.state.value.diagnose}", vm.state.value.diagnose)
        assertNull("FRESH 成功須同步清 FAILED 歷史，got: ${vm.state.value.lastOperation}", vm.state.value.lastOperation)
        assertTrue(
            "msg 須就緒 (NONE/INFO)，got: ${vm.state.value.msgKind} ${vm.state.value.msg}",
            vm.state.value.msgKind == MsgKind.NONE || vm.state.value.msgKind == MsgKind.INFO,
        )
        assertEquals(
            "OK 判據：diagnose null 且無 FAILED 歷史",
            true,
            vm.state.value.diagnose == null &&
                (vm.state.value.lastOperation == null || vm.state.value.lastOperation?.phase != OpPhase.FAILED),
        )
    }
}
