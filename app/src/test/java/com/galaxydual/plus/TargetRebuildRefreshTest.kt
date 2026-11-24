// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase A P1-1 回歸測試：FRESH 95 → rebuild 96 → successful
// clone/remove，斷言新 target 全量重查且舊包不混入。
//
// Harness 沿用 DiagnoseFreshnessPhase5Test：DualViewModel 跑在 viewModelScope
// (Dispatchers.Main 換成 Unconfined)，repo I/O 在 Dispatchers.IO，poll
// StateFlow + timeout 等待；exit-0 語意避 retry settle sleep。
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
class TargetRebuildRefreshTest {
    // 分目標 fake：identity 通道跟 dualId；各 user 獨立已安裝集合，
    // install/uninstall 只動當前 dualId 的集合；list 按 --user 參數回對應集合。
    private class RebuildFake(
        var dualId: Int = 95,
        val pkgsByUser: MutableMap<Int, MutableSet<String>> = mutableMapOf(
            95 to mutableSetOf("com.example.chat", "com.example.old"),
            96 to mutableSetOf("com.example.chat"),
        ),
        var rootOk: Boolean = true,
        // 本地可變 serial（預設 15，舊測試行為不變）：#68 需要「同 id 換 serial」
        // 的 delete+recreate 世界，不能改別的檔案的 RebuildFake（hardcoded 15）。
        var serial: Int = 15,
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
                "    Serial: $serial\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=$serial:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        private fun userOfListCmd(cmd: String): Int {
            val m = Regex("--user\\s+(\\d+)").find(cmd)
            return m?.groupValues?.get(1)?.toIntOrNull() ?: dualId
        }

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
            cmd.startsWith("pm install-existing") -> {
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                pkgsByUser.getOrPut(dualId) { mutableSetOf() }.add(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                pkgsByUser[dualId]?.remove(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val uid = userOfListCmd(cmd)
                val pkgs = pkgsByUser[uid] ?: emptySet()
                CmdResult(exit = 0, out = pkgs.map { "package:$it" }.joinToString("\n"), err = "")
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
        // CI #306: drain outstanding viewModelScope children before Main is reset.
        TestDrainSupport.drainBeforeResetMain(vms)
        vms.clear()
        Dispatchers.resetMain()
    }

    private class JumpClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }
    }

    private fun makeVm(gw: RebuildFake, clock: JumpClock): DualViewModel {
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
            // C1 雙時鐘測試同餵：wall/mono 共用同一 fake，沿既有 JumpClock/FakeClock 模式。
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

    // FRESH 95 → rebuild 96 → successful clone：新 target 全量重查，
    // 舊包（僅 95 有的 com.example.old）不混入新清單。
    @Test
    fun cloneAfterRebuild_refreshesNewTargetWithoutOldPkgs() {
        val gw = RebuildFake()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertTrue(vm.state.value.cloned.contains("com.example.old"))

        // target 重建：96 的清單沒有 com.example.old。
        gw.dualId = 96
        vm.clone("com.example.fresh")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.targetId == 96 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(96, s.lastOperation?.pinnedTarget)
        assertTrue("new pkg must be listed, got: ${s.cloned}", s.cloned.contains("com.example.fresh"))
        assertTrue(
            "old-target-only pkg must not mix into the new list, got: ${s.cloned}",
            !s.cloned.contains("com.example.old"),
        )
        assertNull("success after refresh must clear diagnose", s.diagnose)
    }

    // FRESH 95 → rebuild 96 → successful remove：新 target 全量重查，
    // 舊包不混入，已移除包亦不在新清單。
    @Test
    fun removeAfterRebuild_refreshesNewTargetWithoutOldPkgs() {
        val gw = RebuildFake(
            pkgsByUser = mutableMapOf(
                95 to mutableSetOf("com.example.chat", "com.example.old"),
                96 to mutableSetOf("com.example.chat", "com.example.victim"),
            ),
        )
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)

        gw.dualId = 96
        vm.requestRemove("com.example.victim")
        await { vm.state.value.pendingRemove?.dualId == 96 }

        vm.confirmRemove()
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.targetId == 96 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(96, s.lastOperation?.pinnedTarget)
        assertTrue(
            "removed pkg must be gone, got: ${s.cloned}",
            !s.cloned.contains("com.example.victim"),
        )
        assertTrue(
            "old-target-only pkg must not mix into the new list, got: ${s.cloned}",
            !s.cloned.contains("com.example.old"),
        )
        assertNull("remove success after refresh must clear diagnose", s.diagnose)
        assertNull("confirm must drop pending", s.pendingRemove)
    }

    // RED regression for issue #68 (same userId, fresh serial -> SUCCESS
    // merged into the old-serial FRESH inventory). With id pinned at 95,
    // delete+recreate of user 95 as (95,41) makes every identity channel
    // report the new serial. CloneOutcome.Success carries only dualId
    // (DualRepository.kt:119), and the clone Success branch
    // (DualViewModel.kt:5256-5259) checks only
    // `freshness == FRESH && inventory.targetId != newTarget`; both ids are
    // 95 so needRebuildRefresh is false, refreshLocked() never runs, and
    // the new package is appended to the old FRESH(95,15) list while
    // targetSerial stays 15. Fix locus: under opMutex, fetch a fresh
    // verified target (repo.fetchFreshVerifiedTarget() / the VM's public
    // fetchFreshVerifiedTarget() wrapper) and compare BOTH (id, serial)
    // against inventory's pin; on mismatch mark the old inventory STALE
    // and re-probe the new incarnation whole (as the existing id-rebuild
    // branch does with refreshLocked()). This test is intentionally RED
    // on current code; production is NOT modified here.
    @Test
    fun cloneAfterSameIdSerialRebuild_doesNotKeepOldSerialFreshInventory() {
        val gw = RebuildFake()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)
        assertTrue(vm.state.value.cloned.contains("com.example.old"))

        // The profile was deleted and recreated with the SAME id but a fresh
        // platform serial: all identity channels now agree on (95, 41).
        gw.serial = 41

        vm.clone("com.example.new")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS
        }

        val s = vm.state.value
        assertEquals(
            "same-id serial rebuild must be detected: inventory must carry " +
                "the verified (95,41) pin, not the stale (95,15) pin",
            41,
            s.inventory.targetSerial,
        )
        assertTrue(
            "the write result must never be presented under a stale " +
                "FRESH(95,15) snapshot; got: ${s.inventory}, cloned=${s.cloned}",
            !(s.inventory.freshness == InventoryFreshness.FRESH &&
                s.inventory.targetSerial == 15 &&
                s.cloned.contains("com.example.new")),
        )
    }

    // Same #68 regression through the remove Success branch
    // (DualViewModel.kt:6454-6456): removeVerifiedExpected pins the new
    // serial in pendingRemove.dualSerial and succeeds, but the VM again
    // compares only TargetMismatch-by-id, so the removal result is merged
    // into the stale FRESH(95,15) inventory. Same fix locus: compare the
    // fresh verified (id, serial) under opMutex and re-probe the whole
    // new incarnation. Intentionally RED on current code.
    @Test
    fun removeAfterSameIdSerialRebuild_doesNotKeepOldSerialFreshInventory() {
        val gw = RebuildFake()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)
        assertTrue(vm.state.value.cloned.contains("com.example.old"))

        // Same id, new serial: the pending remove is pinned to (95,41),
        // which is the incarnation the uninstall actually targets.
        gw.serial = 41

        vm.requestRemove("com.example.old")
        await { vm.state.value.pendingRemove?.dualId == 95 }
        assertEquals(41, vm.state.value.pendingRemove?.dualSerial)

        vm.confirmRemove()
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.pendingRemove == null
        }

        val s = vm.state.value
        assertEquals(
            "same-id serial rebuild must be detected: inventory must carry " +
                "the verified (95,41) pin, not the stale (95,15) pin",
            41,
            s.inventory.targetSerial,
        )
        assertTrue(
            "the remove result must never be presented under a stale " +
                "FRESH(95,15) snapshot; got: ${s.inventory}, cloned=${s.cloned}",
            !(s.inventory.freshness == InventoryFreshness.FRESH &&
                s.inventory.targetSerial == 15 &&
                !s.cloned.contains("com.example.old")),
        )
    }
}
