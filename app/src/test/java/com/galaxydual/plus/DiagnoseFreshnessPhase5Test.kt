// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-6 P5 (issue #27, Phase 5 測試補強)：新檔案收納新增案例，不動既有
// DiagnoseFreshnessTest.kt（其 :325 維持原單斷言，本檔另做雙斷言覆蓋）。
//
// Harness 沿用 DiagnoseFreshnessTest：DualViewModel 跑在 viewModelScope
// (Dispatchers.Main 換成 Unconfined)，repo I/O 在 Dispatchers.IO，以
// poll StateFlow + timeout 等待；exit-0 error-semantic 避 retry settle
// sleep；只用 Thread.sleep 輪詢，不引入新等待機制。
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ProfilesSnapshot
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
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnoseFreshnessPhase5Test {
    // 可調 fake：identity 通道跟 dualId/世代走；listUsers 計數供 verified
    // 漂移測試分代（refresh 內第 3 次 listUsers 起切 96，見 drift 測試註解）。
    private class FakeRepo(
        var dualId: Int = 95,
        var failListPackages: Boolean = false,
        var listExtra: List<String> = emptyList(),
        var rootOk: Boolean = true,
        var failListUsers: Boolean = false,
        var emptyUsers: Boolean = false,
        var ownerOnlyUsers: Boolean = false,
        var ownerOnlyTyped: Boolean = false,
        var throwAll: Boolean = false,
        var driftAfterListUsers: Int = Int.MAX_VALUE,
        var driftId: Int = 96,
        // P2 安裝門檻適配 (新 base requestRemove/confirmRemove 經 verifyState
        // 查已安裝才開 dialog)：已卸载集合供 uninstall 成功後排除，
        // post-verify 即見 NotInstalled；failListAfterUninstall 供
        // confirm 寫後查證失敗 (VerifyUnknown) 場景。
        val uninstalledPkgs: MutableSet<String> = mutableSetOf(),
        var failListAfterUninstall: Boolean = false,
        var sawUninstall: Boolean = false,
        // P2-8：超長輸入拒絕不斷言 install/uninstall 用。
        var sawInstall: Boolean = false,
        // M2 pm 否決測試用：divergentPmExtra=true 時 pm 快照 {0,10,95}，
        // typed 雙通道 {0,10} OTHER（乾淨無 clone），prop 乾淨空聲明。
        // 無否決會誤判 Absent→NO_DUAL 清空；有否決須 Unknown→CHANNEL_ERROR 不清空。
        var divergentPmExtra: Boolean = false,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        var listUsersCalls = 0

        private fun effId(): Int = if (listUsersCalls >= driftAfterListUsers) driftId else dualId

        private fun usersOut(id: Int): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$id:DualApp:400} running\n"

        private fun ownerUsersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n"

        // M2 分歧 pm 快照：{0,10,95}（typed 漏掉 95）。
        private fun extraUsersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{10:Work profile:30} running\n" +
                "\tUserInfo{95:DualApp:400} running\n"

        // M2 分歧 typed：{0,10} OTHER（乾淨無 clone，與 core NODUAL 同形）。
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
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n"

        override suspend fun run(cmd: String): CmdResult {
            if (throwAll) throw IOException("boom: $cmd")
            return when {
                cmd.trim() == "id -u" ->
                    if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                    else CmdResult(exit = 0, out = "1000\n", err = "")
                cmd == DualRepository.CMD_LIST_USERS -> {
                    listUsersCalls++
                    when {
                        failListUsers ->
                            CmdResult(exit = 0, out = "Failure [users failed]", err = "")
                        emptyUsers -> CmdResult(exit = 0, out = "Users:\n", err = "")
                        divergentPmExtra -> CmdResult(exit = 0, out = extraUsersOut(), err = "")
                        ownerOnlyUsers -> CmdResult(exit = 0, out = ownerUsersOut(), err = "")
                        else -> CmdResult(exit = 0, out = usersOut(effId()), err = "")
                    }
                }
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(
                        exit = 0,
                        out = if (divergentPmExtra) nodualVerboseOut() else if (ownerOnlyTyped) ownerVerboseOut() else verboseOut(effId()),
                        err = "",
                    )
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(
                        exit = 0,
                        out = if (divergentPmExtra) nodualDumpsysOut() else if (ownerOnlyTyped) ownerDumpsysOut() else dumpsysOut(effId()),
                        err = "",
                    )
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    if (divergentPmExtra) CmdResult(exit = 0, out = "", err = "")
                    else if (ownerOnlyTyped) CmdResult(exit = 0, out = "", err = "")
                    else CmdResult(exit = 0, out = "${effId()}\n", err = "")
                cmd.startsWith("pm install-existing") ->
                    CmdResult(exit = 0, out = "Success", err = "").also { sawInstall = true }
                cmd.startsWith("pm uninstall") ->
                    CmdResult(exit = 0, out = "Success", err = "").also {
                        sawUninstall = true
                        val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                        uninstalledPkgs.add(pkg)
                    }
                cmd.startsWith("pm list packages") ->
                    if (failListPackages || (failListAfterUninstall && sawUninstall)) {
                        CmdResult(exit = 0, out = "Failure [list failed]", err = "")
                    } else {
                        CmdResult(
                            exit = 0,
                            out = (listOf("package:com.example.chat") + listExtra.map { "package:$it" })
                                .filterNot { it.removePrefix("package:") in uninstalledPkgs }
                                .joinToString("\n"),
                            err = "",
                        )
                    }
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
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

    // 可跳躍 fake clock：平時每次 wall/mono 取時 +1s；advance() 整段快轉供過期測試。
    private class JumpClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }

        fun advance(ms: Long) {
            tick += ms / 1000L
        }
    }

    private fun makeVm(
        gw: FakeRepo,
        clock: JumpClock,
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

    // ---- 1. needsRefresh 邊界純測 ----

    @Test
    fun needsRefresh_unknownAndStaleAlwaysTrue() {
        val maxAge = 60_000L
        val unknown = InventorySnapshot(source = "s", queryTimeMs = 9_000L)
        assertTrue("UNKNOWN freshness must always need refresh", unknown.needsRefresh(9_001L, maxAge))
        val stale = unknown.copy(freshness = InventoryFreshness.STALE, count = 2)
        assertTrue("STALE freshness must always need refresh", stale.needsRefresh(9_001L, maxAge))
    }

    @Test
    fun needsRefresh_freshBoundaryUsesGte() {
        // 實作語意：(now - queryTime) >= maxAge 即過期，故恰等時為 true；
        // 未達閾值（-1）才為 false。
        val maxAge = 60_000L
        val fresh = InventorySnapshot(
            source = "s",
            targetId = 95,
            queryTimeMs = 1_000L,
            freshness = InventoryFreshness.FRESH,
            count = 1,
        )
        assertFalse(
            "FRESH age=maxAge-1 must stay fresh",
            fresh.needsRefresh(now = 1_000L + maxAge - 1, maxAgeMs = maxAge),
        )
        assertTrue(
            "FRESH age=maxAge hits the >= threshold and must refresh",
            fresh.needsRefresh(now = 1_000L + maxAge, maxAgeMs = maxAge),
        )
        assertTrue(
            "FRESH age=maxAge+1 must refresh",
            fresh.needsRefresh(now = 1_000L + maxAge + 1, maxAgeMs = maxAge),
        )
    }

    @Test
    fun needsRefresh_zeroAndFutureQueryTime() {
        val maxAge = 60_000L
        val zero = InventorySnapshot(source = "s", targetId = 95, queryTimeMs = 0L, freshness = InventoryFreshness.FRESH)
        assertTrue("FRESH with queryTime 0 (never queried) must refresh", zero.needsRefresh(60_000L, maxAge))
        val future = zero.copy(queryTimeMs = 500_000L)
        assertTrue("FRESH with future queryTime (clock skew) must refresh", future.needsRefresh(60_000L, maxAge))
    }

    // ---- 2. onForeground ----

    @Test
    fun onForeground_expiredRunsFullRefresh() {
        val gw = FakeRepo()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        val before = vm.state.value.inventory.queryTimeMs
        assertTrue("fresh queryTime must be recorded", before > 0L)
        val countBefore = vm.state.value.inventory.count

        clock.advance(INVENTORY_STALE_AFTER_MS + 60_000L)
        vm.onForeground()
        await {
            !vm.state.value.loading &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH &&
                vm.state.value.inventory.queryTimeMs > before
        }
        assertEquals("full refresh keeps the same count", countBefore, vm.state.value.inventory.count)
        assertNull("refresh must not invent a lastOperation", vm.state.value.lastOperation)
    }

    @Test
    fun onForeground_freshRunsLightProbeOnly() {
        val gw = FakeRepo()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        val qt0 = vm.state.value.inventory.queryTimeMs
        val c0 = vm.state.value.inventory.count
        val q0 = vm.state.value.hookQueriedAtMs

        vm.onForeground()
        await { vm.state.value.hookQueriedAtMs > q0 && !vm.state.value.loading }

        assertEquals("light probe must not touch queryTime", qt0, vm.state.value.inventory.queryTimeMs)
        assertEquals("light probe must not touch count", c0, vm.state.value.inventory.count)
        assertEquals("light probe keeps FRESH", InventoryFreshness.FRESH, vm.state.value.inventory.freshness)
        assertNull("light probe must not touch lastOperation", vm.state.value.lastOperation)
    }

    // ---- 3. refresh 成功保留 UNKNOWN 歷史 ----

    @Test
    fun refreshSuccess_preservesUnknownLastOperation() {
        val gw = FakeRepo(failListPackages = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation != null }
        val base = vm.state.value.lastOperation!!
        assertEquals("clone VerifyUnknown pins UNKNOWN", OpPhase.UNKNOWN, base.phase)
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)

        gw.failListPackages = false
        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }
        val after = vm.state.value.lastOperation
        assertNotNull("FRESH refresh must not clear UNKNOWN history", after)
        assertEquals("lastOperation opId must survive refresh", base.opId, after!!.opId)
        assertEquals("lastOperation time must survive refresh", base.timeMs, after.timeMs)
        assertEquals(OpPhase.UNKNOWN, after.phase)
        assertEquals(
            "FRESH refresh must retain the UNKNOWN diagnose",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
    }

    // ---- 4. remove ----

    @Test
    fun removeSuccess_clearsDiagnoseAndRecordsSuccess() {
        // 新 base P2 安裝門檻：requestRemove 經 verifyState 查已安裝才開
        // dialog，故待移除包須先列入 fake 清單；卸載後 fake 即排除，
        // post-verify 見 NotInstalled -> Success。
        val gw = FakeRepo(listExtra = listOf("com.example.other"))
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        // 先留一個可清除的 diagnose：空白輸入 -> INVALID_INPUT。
        vm.requestRemove("   ")
        await { vm.state.value.diagnose?.code == DiagnoseCode.INVALID_INPUT }

        vm.requestRemove("com.example.other")
        await { vm.state.value.pendingRemove != null }
        vm.confirmRemove()
        await {
            !vm.state.value.loading &&
                (vm.state.value.lastOperation?.opId
                    ?.startsWith("remove:com.example.other@95#15#") == true)
        }
        val last = vm.state.value.lastOperation!!
        assertEquals(OpPhase.SUCCESS, last.phase)
        assertTrue(
            "remove opId 沿用新 base serial 世代格式，got ${last.opId}",
            last.opId.startsWith("remove:com.example.other@95#15#"),
        )
        assertEquals("com.example.other", last.appKey)
        assertEquals(95, last.pinnedTarget)
        assertNull("remove Success must clear diagnose", vm.state.value.diagnose)
        assertNull("confirm must drop pending", vm.state.value.pendingRemove)
    }

    @Test
    fun removeUnknown_survivesProbe() {
        // request 時已安裝 (開 dialog)，confirm 寫後查證失敗 -> UNKNOWN：
        // failListAfterUninstall 只讓 uninstall 之後的 list 失敗，
        // confirm 前 verifyState 仍見 Installed。
        val gw = FakeRepo(listExtra = listOf("com.example.other"))
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.requestRemove("com.example.other")
        await { vm.state.value.pendingRemove != null }
        gw.failListAfterUninstall = true
        vm.confirmRemove()
        await { vm.state.value.lastOperation?.phase == OpPhase.UNKNOWN }
        val base = vm.state.value.lastOperation!!
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
        val q0 = vm.state.value.hookQueriedAtMs

        gw.failListPackages = false
        vm.recheckPrivilege()
        await { vm.state.value.hookQueriedAtMs > q0 && !vm.state.value.loading }
        val after = vm.state.value.lastOperation
        assertNotNull("probe must not clear remove UNKNOWN history", after)
        assertEquals("probe must not rewrite remove opId", base.opId, after!!.opId)
        assertEquals(base.timeMs, after.timeMs)
        assertEquals(
            "probe must retain the remove UNKNOWN diagnose",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
    }

    // ---- 5. 通道全失補 STALE ----

    @Test
    fun probeWithoutChannel_marksFreshStale() {
        val gw = FakeRepo(failListPackages = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.phase == OpPhase.UNKNOWN }

        gw.failListPackages = false
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        gw.rootOk = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.STALE }

        val stale = vm.state.value.inventory
        assertEquals("STALE keeps the old target identity", 95, stale.targetId)
        assertTrue("old cloned entries are kept", vm.state.value.cloned.contains("com.example.chat"))
        assertEquals(OpPhase.UNKNOWN, vm.state.value.lastOperation?.phase)
        assertEquals("clone:com.example.chat", vm.state.value.lastOperation?.opId)
        assertEquals(
            "UNKNOWN history diagnose survives the channel loss",
            DiagnoseCode.VERIFY_UNKNOWN,
            vm.state.value.diagnose?.code,
        )
    }

    // ---- 6. 映射全覆蓋 ----

    @Test
    fun mapping_removeSevenOutcomes() {
        assertEquals(DiagnoseCode.OK, RemoveOutcome.Success(95).toDiagnoseCode())
        assertEquals(DiagnoseCode.NO_DUAL, RemoveOutcome.NoDual.toDiagnoseCode())
        assertEquals(DiagnoseCode.CHANNEL_ERROR, RemoveOutcome.ChannelError("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.INVALID_INPUT, RemoveOutcome.InvalidPackage("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.OP_FAILED, RemoveOutcome.RemoveFailed("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.STILL_INSTALLED, RemoveOutcome.StillInstalled(95).toDiagnoseCode())
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, RemoveOutcome.VerifyUnknown(95, "x").toDiagnoseCode())
    }

    @Test
    fun mapping_cloneSevenOutcomes() {
        assertEquals(DiagnoseCode.OK, CloneOutcome.Success(95).toDiagnoseCode())
        assertEquals(DiagnoseCode.NO_DUAL, CloneOutcome.NoDual.toDiagnoseCode())
        assertEquals(DiagnoseCode.CHANNEL_ERROR, CloneOutcome.ChannelError("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.INVALID_INPUT, CloneOutcome.InvalidPackage("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.OP_FAILED, CloneOutcome.InstallFailed("x").toDiagnoseCode())
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, CloneOutcome.VerifyFailed(95).toDiagnoseCode())
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, CloneOutcome.VerifyUnknown(95, "x").toDiagnoseCode())
    }

    @Test
    fun mapping_nullEventsAndTechDetailCarryDualId() {
        assertNull("Clone Success maps to no event", CloneOutcome.Success(95).toDiagnoseEvent())
        assertNull("Remove Success maps to no event", RemoveOutcome.Success(95).toDiagnoseEvent())
        assertNull("Loaded snapshot maps to no event", ProfilesSnapshot.Loaded(emptyList()).toDiagnoseEvent())

        val vf = CloneOutcome.VerifyFailed(95).toDiagnoseEvent("com.example.chat")
        assertNotNull("VerifyFailed must produce an event", vf)
        assertTrue("VerifyFailed tech must carry dualId, got: ${vf!!.techDetail}", vf.techDetail.contains("95"))
        assertFalse("VerifyFailed tech must redact the pkg", vf.techDetail.contains("com.example.chat"))

        val si = RemoveOutcome.StillInstalled(95).toDiagnoseEvent("com.example.chat")
        assertNotNull("StillInstalled must produce an event", si)
        assertTrue("StillInstalled tech must carry dualId, got: ${si!!.techDetail}", si.techDetail.contains("95"))
        assertFalse("StillInstalled tech must redact the pkg", si.techDetail.contains("com.example.chat"))
    }

    @Test
    fun mapping_stableIdsUniqueAndTableComplete() {
        val codes = DiagnoseCode.values().toList()
        assertEquals(9, codes.size)
        val ids = codes.map { it.stableId }
        assertEquals("every DiagnoseCode needs a unique stableId", ids.size, ids.toSet().size)
        assertEquals(
            "DiagnoseStrings must cover every code exactly",
            codes.toSet(),
            DiagnoseStrings.TABLE.keys,
        )
        for (code in codes) {
            val pair = DiagnoseStrings.TABLE.getValue(code)
            assertTrue("$code summary must be non-blank", pair.first.isNotBlank())
            assertTrue("$code nextStep must be non-blank", pair.second.isNotBlank())
        }
    }

    // ---- 7. export ----

    @Test
    fun export_nullBranchWritesOkAndNoLastOp() {
        val text = buildDiagnosticExport(UiState())
        assertTrue("null diagnose must export OK, got: $text", text.contains("OK"))
        assertTrue("null lastOperation must export 無, got: $text", text.contains("上次操作：無"))
    }

    @Test
    fun export_exitLineAndDoubleRedactMarker() {
        // 雙斷言收緊：<pkg>（包名遮蔽）與 ***（單筆包名遮蔽）皆須出現。
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
        )
        val text = buildDiagnosticExport(state)
        assertEquals("export must be pure", text, buildDiagnosticExport(state))
        assertFalse("export must not leak the raw pkg", text.contains(rawPkg))
        assertTrue("export must carry the <pkg> redact marker, got: $text", text.contains("<pkg>"))
        assertTrue("export must carry the *** redact marker, got: $text", text.contains("***"))
        assertTrue(text.contains(DiagnoseCode.VERIFY_UNKNOWN.stableId))
        assertTrue(text.length <= MAX_DIAG_EXPORT_CHARS)
    }

    @Test
    fun export_exitCodeLineAndHugeInputCapped() {
        val withExit = UiState(
            diagnose = diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, exit = 3, techDetail = "boom"),
        )
        val exitText = buildDiagnosticExport(withExit)
        assertTrue("exit must be exported, got: $exitText", exitText.contains("exit"))
        assertTrue("exit value must be exported, got: $exitText", exitText.contains("3"))

        val rawPkg = "com.example.secretchat"
        val huge = UiState(
            lastOperation = LastOperation(
                opId = "clone:$rawPkg",
                appKey = rawPkg,
                pinnedTarget = 95,
                phase = OpPhase.UNKNOWN,
                timeMs = 123456789L,
                detail = "cannot verify $rawPkg for user 95: " + "x".repeat(10000),
            ),
            diagnose = diagnoseEvent(
                DiagnoseCode.VERIFY_UNKNOWN,
                techDetail = "cannot verify $rawPkg: " + "x".repeat(10000),
            ),
        )
        val capped = buildDiagnosticExport(huge)
        assertTrue(
            "export must cap at $MAX_DIAG_EXPORT_CHARS, got ${capped.length}",
            capped.length <= MAX_DIAG_EXPORT_CHARS,
        )
        assertFalse("capped export must not leak the raw pkg", capped.contains(rawPkg))
    }

    @Test
    fun export_timeSubstringSurvivesRedactAndFormatBranches() {
        val queryMs = 123456789L
        val text = buildDiagnosticExport(UiState(inventory = InventorySnapshot(queryTimeMs = queryMs)))
        assertTrue(
            "formatted time must survive redact unchanged",
            text.contains(formatTimeMs(queryMs)),
        )
        val positive = formatTimeMs(queryMs)
        assertTrue("positive time must render non-blank", positive.isNotBlank())
        assertFalse("time format must avoid / (redactPaths would eat it), got: $positive", positive.contains("/"))
        assertEquals("從未查詢", formatTimeMs(0L))
        assertEquals("從未查詢", formatTimeMs(-5L))
        val none: Long? = null
        assertEquals("從未查詢", formatTimeMs(none))
    }

    // ---- 8. redact ----

    @Test
    fun redactPii_unit() {
        val mixed = "pkg com.foo.bar and com.baz.qux user 95"
        val redacted = redactPii(mixed)
        assertFalse("all dotted pkgs must go, got: $redacted", redacted.contains("com.foo.bar"))
        assertFalse("all dotted pkgs must go, got: $redacted", redacted.contains("com.baz.qux"))
        assertTrue("redact marker must appear, got: $redacted", redacted.contains("<pkg>"))
        assertTrue("numeric ids must stay readable, got: $redacted", redacted.contains("95"))
        assertEquals("plain text without dots must pass through", "hello world", redactPii("hello world"))
        assertEquals(
            "numbers/exit codes must pass through",
            "user 95 exit=0 serial 15",
            redactPii("user 95 exit=0 serial 15"),
        )
    }

    @Test
    fun redactPackage_unit() {
        assertEquals("com.***", redactPackage("com.example.chat"))
        assertEquals("***", redactPackage("single"))
        assertEquals("***", redactPackage(""))
    }

    // P2-4 空白/Unicode/引號路徑全遮：舊 "/[\w./-]+" 遇空格即斷裂
    // （denied /sdcard/My Wedding/Photo 001.png 殘留 "My Wedding..."），
    // 新延展由前斜線起含空白、遇換行/引號/特定分隔止（fail-closed）。
    // 另：日期/純中文無斜線須原樣通過；"a/b" 誤傷可接受故不斷言其保留。
    @Test
    fun redactPaths_spaceUnicodeQuote_fullyMasked() {
        val spaced = "denied /sdcard/My Wedding/Photo 001.png 殘留"
        val r1 = redactPaths(spaced)
        assertFalse("空白路徑前段不得殘留，got: $r1", r1.contains("/sdcard/"))
        assertFalse("空白路徑中段不得殘留，got: $r1", r1.contains("My Wedding"))
        assertFalse("空白路徑檔名不得殘留，got: $r1", r1.contains("Photo 001.png"))
        assertTrue("遮蔽標記須出現，got: $r1", r1.contains("<path>"))
        assertFalse("斜線本體不得殘留，got: $r1", r1.contains("/sdcard/My"))

        val uni = "denied /sdcard/照片/婚禮照 001.png 結尾"
        val r2 = redactPaths(uni)
        assertFalse("Unicode 路徑前段不得殘留，got: $r2", r2.contains("/sdcard/"))
        assertFalse("Unicode 路徑中文段不得殘留，got: $r2", r2.contains("照片"))
        assertFalse("Unicode 路徑中文段不得殘留，got: $r2", r2.contains("婚禮照"))
        assertTrue("遮蔽標記須出現，got: $r2", r2.contains("<path>"))

        val quoted = "denied \"/sdcard/My Wedding/Photo 001.png\""
        val r3 = redactPaths(quoted)
        assertFalse("引號內路徑不得殘留，got: $r3", r3.contains("/sdcard/"))
        assertFalse("引號內空白段不得殘留，got: $r3", r3.contains("My Wedding"))
        assertTrue("遮蔽標記須出現，got: $r3", r3.contains("<path>"))

        val singleQuoted = "denied '/sdcard/照片 001.png'"
        val r4 = redactPaths(singleQuoted)
        assertFalse("單引號內路徑不得殘留，got: $r4", r4.contains("/sdcard/"))
        assertTrue("遮蔽標記須出現，got: $r4", r4.contains("<path>"))
    }

    // P2-4 日期/中文不斷裂誤傷：無斜線內容須原樣通過。
    // 注意：不斷言 "a/b" 的保留位置（fail-closed 誤傷可接受，見註解）。
    @Test
    fun redactPaths_dateAndChinese_passthrough() {
        val date = "2026-11-05 12:34"
        assertEquals("日期無斜線須原樣通過", date, redactPaths(date))
        val zh = "分享前請人工確認"
        assertEquals("純中文無斜線須原樣通過", zh, redactPaths(zh))
        val plain = "exit=0 user 95"
        assertEquals("無斜線診斷文案須原樣通過", plain, redactPaths(plain))
        val timeFmt = formatTimeMs(123456789L)
        assertEquals("格式化時間須原樣通過", timeFmt, redactPaths(timeFmt))
    }

    // m4 regex 殘留鎖定（採 (a) 不改 regex）："/sdcard/a*b" 止於 *，殘留 "*b"，
    // 但前段大部已遮（不得殘留 "/sdcard/a"）；不斷言裸 / 被遮（非敏感）。
    @Test
    fun redactPaths_globTail_residual() {
        val r = redactPaths("denied /sdcard/a*b 結尾")
        assertFalse("前段大部須遮，got: $r", r.contains("/sdcard/a"))
        assertTrue("遮蔽標記須出現，got: $r", r.contains("<path>"))
        assertTrue("星號尾巴殘留可接受並鎖定，got: $r", r.contains("*b"))
    }

    // P2-4 匯出端到端：含空白/Unicode/引號路徑的技術細節經匯出不得洩 raw，
    // 且標題須含誠實文案「分享前請人工確認」（遮蔽非全面保證）。
    @Test
    fun export_spaceUnicodePaths_noRawLeak() {
        val rawSpaced = "/sdcard/My Wedding/Photo 001.png"
        val rawUni = "/sdcard/照片/婚禮照 001.png"
        val state = UiState(
            inventory = InventorySnapshot(
                source = "pm list packages --user 95",
                targetId = 95,
                queryTimeMs = 123456789L,
                freshness = InventoryFreshness.FRESH,
                count = 1,
            ),
            lastOperation = LastOperation(
                opId = "clone:com.example.secretchat",
                appKey = "com.example.secretchat",
                pinnedTarget = 95,
                phase = OpPhase.UNKNOWN,
                timeMs = 123456789L,
                detail = "denied \"$rawSpaced\" for user 95",
            ),
            diagnose = diagnoseEvent(
                DiagnoseCode.VERIFY_UNKNOWN,
                techDetail = "denied $rawSpaced and $rawUni for user 95",
            ),
        )
        val text = buildDiagnosticExport(state)
        assertFalse("匯出不得洩空白路徑 raw，got: $text", text.contains(rawSpaced))
        assertFalse("匯出不得洩空白片段，got: $text", text.contains("My Wedding"))
        assertFalse("匯出不得洩 Unicode 路徑 raw，got: $text", text.contains(rawUni))
        assertFalse("匯出不得洩 Unicode 片段，got: $text", text.contains("婚禮照"))
        assertTrue("匯出須含路徑遮蔽標記，got: $text", text.contains("<path>"))
        assertTrue(
            "匯出標題須含誠實文案，got: $text",
            text.contains("分享前請人工確認"),
        )
    }

    // ---- 9. staleOrUnknown + refresh 失敗分支 ----

    @Test
    fun staleOrUnknown_pure() {
        val fresh = InventorySnapshot(
            source = "cmd",
            targetId = 95,
            queryTimeMs = 5000L,
            freshness = InventoryFreshness.FRESH,
            count = 3,
        )
        val staled = fresh.toStaleOrUnknown(now = 6000L, source = "new")
        assertEquals(InventoryFreshness.STALE, staled.freshness)
        assertEquals("STALE keeps identity", 95, staled.targetId)
        assertEquals("STALE keeps queryTime", 5000L, staled.queryTimeMs)
        assertEquals("STALE keeps count", 3, staled.count)

        val rebuilt = InventorySnapshot.unknown().toStaleOrUnknown(now = 6000L, source = "src")
        assertEquals(InventoryFreshness.UNKNOWN, rebuilt.freshness)
        assertEquals("fresh UNKNOWN starts at count 0", 0, rebuilt.count)
        assertEquals(6000L, rebuilt.queryTimeMs)
        assertEquals("src", rebuilt.source)

        val fromStale = InventorySnapshot(
            source = "old",
            targetId = 95,
            queryTimeMs = 5000L,
            freshness = InventoryFreshness.STALE,
            count = 2,
        ).toStaleOrUnknown(now = 6000L, source = "s2", targetId = 96)
        assertEquals(InventoryFreshness.UNKNOWN, fromStale.freshness)
        assertEquals("non-FRESH takes the passed target", 96, fromStale.targetId)
        assertEquals("non-FRESH keeps the old count", 2, fromStale.count)
    }

    @Test
    fun refresh_noDual_marksUnknown() {
        val gw = FakeRepo(ownerOnlyUsers = true, ownerOnlyTyped = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.NO_DUAL }
        val inv = vm.state.value.inventory
        assertEquals(InventoryFreshness.UNKNOWN, inv.freshness)
        assertEquals(NO_TARGET_ID, inv.targetId)
        assertEquals(0, inv.count)
        assertTrue("no-dual clears cloned", vm.state.value.cloned.isEmpty())
        assertEquals("no dual profile", vm.state.value.msg)
    }

    @Test
    fun refresh_emptySnapshot_mapsChannelError() {
        val gw = FakeRepo(emptyUsers = true, ownerOnlyTyped = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals(
            "empty snapshot is untrustworthy, not fresh",
            InventoryFreshness.UNKNOWN,
            vm.state.value.inventory.freshness,
        )
    }

    @Test
    fun refresh_pmExtraId_vetoesAbsent_keepsCloned() {
        // M2 pm 否決：先在 95 世界 refresh 建立 cloned，再切分歧世界
        //（verbose/dumpsys {0,10} OTHER＋prop 空，pm {0,10,95}）→須
        // CHANNEL_ERROR 且不清空 cloned（無否決會誤報 NO_DUAL 清空）。
        val gw = FakeRepo()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertTrue("前置 refresh 須建立 cloned", vm.state.value.cloned.isNotEmpty())
        gw.divergentPmExtra = true
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertTrue("pm 漏戶不得清空 cloned", vm.state.value.cloned.isNotEmpty())
    }

    @Test
    fun refresh_singleSnapshot_driftLandsWholeNextRefresh() {
        // Single-snapshot #53: refresh takes ONE listUsers snapshot, so a
        // mid-refresh 95->96 rebuild cannot be spliced into a VERIFY_MISMATCH
        // (there is no second snapshot/triple to disagree with the first).
        // The 95 world publishes FRESH(95, 15) as a whole; the 96 world
        // publishes FRESH(96, 15) as a whole on the next refresh -- never
        // a mixed (95, ...) pair with 96's list.
        // 計數在 ready 後歸零（init probe 的 listUsers 不計入）。
        val gw = FakeRepo(driftAfterListUsers = 2, driftId = 96)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        gw.listUsersCalls = 0
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))
        // Refresh #2 takes its own single snapshot, now in the 96 world.
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 96 }
        assertEquals(InventoryFreshness.FRESH, vm.state.value.inventory.freshness)
        assertEquals(15, vm.state.value.inventory.targetSerial)
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))
    }

    // ---- 10. hook ----

    @Test
    fun hook_displayMatrix() {
        val active = HookEvidence(
            status = HookStatus.ACTIVE,
            scopes = setOf(HookMarker.SCOPE_SYSTEM_SERVER),
        )
        val activeText = active.displayText()
        assertTrue("ACTIVE shows scope, got: $activeText", activeText.contains(HookMarker.SCOPE_SYSTEM_SERVER))
        assertTrue("ACTIVE is existence-only evidence, got: $activeText", activeText.contains("僅存在性證據"))
        assertFalse("ACTIVE must not read as absent, got: $activeText", activeText.contains("未偵測到"))

        val inactiveText = HookEvidence(status = HookStatus.INACTIVE).displayText()
        assertTrue("INACTIVE reads as not-detected, got: $inactiveText", inactiveText.contains("未偵測到"))

        for (s in listOf(HookStatus.UNKNOWN_UNREADABLE, HookStatus.UNKNOWN_NO_DIAGNOSTIC)) {
            val t = HookEvidence(status = s).displayText()
            assertTrue("$s must read as unknown, got: $t", t.contains("狀態未知"))
            assertFalse("$s must never read as not-detected, got: $t", t.contains("未偵測到"))
        }
    }

    @Test
    fun hook_timeSuffixes() {
        val q = 1_800_000_000_000L
        val noMarker = HookEvidence(
            status = HookStatus.ACTIVE,
            scopes = setOf(HookMarker.SCOPE_SYSTEM_SERVER),
            queriedAtMs = q,
            markerTimeMs = null,
        )
        assertTrue("null marker reads unknown, got: ${noMarker.displayText()}", noMarker.displayText().contains("marker 時間未知"))

        val neverQueried = HookEvidence(status = HookStatus.INACTIVE, queriedAtMs = 0L)
        assertTrue("queried 0 reads never-queried, got: ${neverQueried.displayText()}", neverQueried.displayText().contains("從未查詢"))

        val via = HookEvidence(
            status = HookStatus.ACTIVE,
            scopes = setOf(HookMarker.SCOPE_SYSTEM_SERVER),
            queriedAtMs = q,
            markerTimeMs = q - 60_000L,
            viaGateway = true,
        )
        assertTrue("gateway read must be labeled, got: ${via.displayText()}", via.displayText().contains("經真通道讀取"))
        assertFalse("fresh marker must not warn, got: ${via.displayText()}", via.displayText().contains("重啟前"))

        val stale = HookEvidence(
            status = HookStatus.ACTIVE,
            scopes = setOf(HookMarker.SCOPE_SYSTEM_SERVER),
            queriedAtMs = q,
            markerTimeMs = q - 600_000L,
        )
        assertTrue("old marker must warn, got: ${stale.displayText()}", stale.displayText().contains("重啟前"))
    }

    @Test
    fun hook_toEvidenceMapping() {
        val raw = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 1710000000000L)
        val ev = HookDiagnosis(
            status = HookStatus.ACTIVE,
            scopes = setOf(HookMarker.SCOPE_SYSTEM_SERVER),
            raw = raw,
            viaGateway = true,
        ).toEvidence(424242L)
        assertEquals(HookStatus.ACTIVE, ev.status)
        assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER), ev.scopes)
        assertEquals(424242L, ev.queriedAtMs)
        assertEquals(1710000000000L, ev.markerTimeMs)
        assertTrue(ev.viaGateway)

        val bad = HookDiagnosis(status = HookStatus.ACTIVE, raw = "not a marker").toEvidence(1L)
        assertNull("unparseable marker time must map to null", bad.markerTimeMs)
    }

    @Test
    fun render_pureFunctionsStructure() {
        val fresh = UiState(
            inventory = InventorySnapshot(
                source = "pm list packages --user 95",
                targetId = 95,
                queryTimeMs = 123456789L,
                freshness = InventoryFreshness.FRESH,
                count = 1,
            ),
        )
        val invText = renderInventoryText(fresh)
        assertTrue("inventory line names itself, got: $invText", invText.contains("清單"))
        assertTrue("inventory line shows freshness, got: $invText", invText.contains("FRESH"))
        val staleText = renderInventoryText(fresh.copy(inventory = fresh.inventory.copy(freshness = InventoryFreshness.STALE)))
        assertTrue("stale line warns expiry, got: $staleText", staleText.contains("過期"))

        assertEquals("上次操作：無", renderLastOpText(UiState()))
        val opText = renderLastOpText(
            UiState(
                lastOperation = LastOperation(
                    opId = "clone:com.example.chat",
                    appKey = "com.example.chat",
                    pinnedTarget = 95,
                    phase = OpPhase.SUCCESS,
                    timeMs = 1L,
                ),
            ),
        )
        // 理由 (security-low)：不再顯示 raw opId，改顯示 redactPackage(appKey)+phase
        // (+targetId 若有效)；儲存的 opId 保持 raw (另斷言)。
        assertFalse("op line must not leak raw pkg, got: $opText", opText.contains("com.example.chat"))
        assertTrue("op line shows redacted pkg, got: $opText", opText.contains("com.***"))
        assertTrue("op line shows phase, got: $opText", opText.contains("SUCCESS"))
        assertTrue("op line shows target when valid, got: $opText", opText.contains("95"))

        assertEquals("診斷：就緒", renderDiagnoseText(UiState(), false))
        val diagState = UiState(diagnose = diagnoseEvent(DiagnoseCode.VERIFY_UNKNOWN, techDetail = "d"))
        val collapsed = renderDiagnoseText(diagState, false)
        assertTrue("diagnose line shows stableId, got: $collapsed", collapsed.contains(DiagnoseCode.VERIFY_UNKNOWN.stableId))
        val expanded = renderDiagnoseText(diagState, true)
        assertTrue("expanded diagnose shows detail, got: $expanded", expanded.contains("細節"))

        assertNull("non-LSPosed has no hook row", renderHookText(UiState()))
        val hookRow = renderHookText(
            UiState(
                channel = ChannelPreference.LSPOSED,
                hookStatus = HookStatus.ACTIVE,
                hookScopes = setOf(HookMarker.SCOPE_SYSTEM_SERVER),
            ),
        )
        assertNotNull("LSPosed mode must render a hook row", hookRow)
        assertTrue("hook row names itself, got: $hookRow", hookRow!!.contains("LSPosed Hook"))
        assertTrue("hook row shows scope, got: $hookRow", hookRow.contains(HookMarker.SCOPE_SYSTEM_SERVER))
    }

    // ---- 11. Phase 1 語意 (p25-rebase 適配)：拋錯 gateway 由新 base
    // Gateway 防禦在 profilesSnapshot 內收斂為 ChannelError，不再外拋到
    // refresh 的 OP_FAILED/"refresh(exception)" 分支（該分支僅覆蓋非
    // gateway 拋錯）。例外文字仍進 techDetail 保可追溯；寫前拒絕不碰裝置。

    @Test
    fun phase1_cloneThrow_mapsChannelErrorUnknown() {
        val gw = FakeRepo()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        gw.throwAll = true
        vm.clone("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals(DiagnoseCode.CHANNEL_ERROR, vm.state.value.diagnose?.code)
        assertTrue(
            "unknown result must say so, got: ${vm.state.value.msg}",
            vm.state.value.msg.startsWith("clone result unknown"),
        )
        assertTrue(
            "exception text must stay traceable, got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").contains("boom"),
        )
        assertFalse("pre-write refusal must not touch the device", gw.sawInstall)
        assertNull("unverifiable write records no history", vm.state.value.lastOperation)
    }

    @Test
    fun phase1_refreshException_mapsChannelError() {
        val gw = FakeRepo()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        gw.throwAll = true
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals(
            "exception path source must be distinguishable",
            "pm list users",
            vm.state.value.inventory.source,
        )
        assertTrue(
            "exception text must stay traceable, got: ${vm.state.value.diagnose?.techDetail}",
            (vm.state.value.diagnose?.techDetail ?: "").contains("boom"),
        )
    }

    private fun seedSuccessBaseline(): Triple<FakeRepo, DualViewModel, LastOperation> {
        val gw = FakeRepo()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.opId == "clone:com.example.chat" }
        return Triple(gw, vm, vm.state.value.lastOperation!!)
    }

    @Test
    fun phase1_cloneNoDual_preservesLastOperation() {
        val (gw, vm, base) = seedSuccessBaseline()
        gw.ownerOnlyUsers = true
        gw.ownerOnlyTyped = true
        vm.clone("com.example.other")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.NO_DUAL }
        assertEquals("NoDual must not overwrite history", base, vm.state.value.lastOperation)
    }

    @Test
    fun phase1_cloneChannelError_preservesLastOperation() {
        val (gw, vm, base) = seedSuccessBaseline()
        gw.failListUsers = true
        vm.clone("com.example.other")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertEquals("ChannelError must not overwrite history", base, vm.state.value.lastOperation)
    }

    @Test
    fun phase1_cloneInvalidPackage_preservesLastOperation() {
        val (_, vm, base) = seedSuccessBaseline()
        vm.clone("!!bad pkg!!")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.INVALID_INPUT }
        assertEquals("InvalidPackage must not overwrite history", base, vm.state.value.lastOperation)
    }

    // ---- 12. Phase 3 ----

    // P2-8：超長輸入拒絕執行（取代舊 take(256) 靜默截斷）：trim 後超過
    // MAX_PACKAGE_INPUT_CHARS 即 INVALID_INPUT，不建 op、不調 repo
    //（零 install）、不寫 lastOperation，只設 diagnose+msg。
    @Test
    fun phase3_longCloneInput_rejectedAsInvalid() {
        val gw = FakeRepo()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.opId == "clone:com.example.chat" }
        val base = vm.state.value.lastOperation!!
        gw.sawInstall = false

        val longPkg = "com.example." + "x".repeat(500)
        assertTrue("test input must exceed the limit", longPkg.trim().length > MAX_PACKAGE_INPUT_CHARS)
        vm.clone(longPkg)
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.INVALID_INPUT }
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertFalse("overlong clone must run zero installs", gw.sawInstall)
        assertEquals("overlong clone must not touch lastOperation", base, vm.state.value.lastOperation)
    }

    // P2-8：破壞性 remove 同式——超長 requestRemove 即 INVALID_INPUT
    // 拒絕（零 uninstall、無 pending、lastOperation 不變）。
    @Test
    fun phase3_longRemoveInput_rejectedAsInvalid() {
        val longPkg = "com.example." + "x".repeat(500)
        assertTrue("test input must exceed the limit", longPkg.trim().length > MAX_PACKAGE_INPUT_CHARS)
        val gw = FakeRepo(listExtra = listOf("com.example.chat"))
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }
        vm.requestRemove(longPkg)
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.INVALID_INPUT }
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertNull("overlong request must set no pending", vm.state.value.pendingRemove)
        assertFalse("overlong request must run zero uninstalls", gw.sawUninstall)
        assertNull("overlong request must not touch lastOperation", vm.state.value.lastOperation)
    }

    @Test
    fun phase3_sanitize_collapsesControlsAndTruncates() {
        assertEquals("a b c d", sanitizeForDisplay("a\nb\r\nc\td"))
        val long = sanitizeForDisplay("y".repeat(600))
        assertTrue("sanitized output must cap at 500, got ${long.length}", long.length <= 500)
        assertFalse("sanitized output must carry no newlines", long.contains("\n"))
    }

    @Test
    fun requestRemove_blank_mapsInvalidInput() {
        val vm = makeVm(FakeRepo(), JumpClock())
        await { vm.state.value.channelReady }
        vm.requestRemove("   ")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.INVALID_INPUT }
        assertNull("blank input sets no pending", vm.state.value.pendingRemove)
    }

    @Test
    fun requestRemove_channelError_mapsCode() {
        val gw = FakeRepo(failListUsers = true)
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        vm.requestRemove("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertNull("channel failure sets no pending", vm.state.value.pendingRemove)
    }

    @Test
    fun requestRemove_noDual_mapsCode() {
        val gw = FakeRepo(ownerOnlyUsers = true, ownerOnlyTyped = true)
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        vm.requestRemove("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.NO_DUAL }
        assertNull("no-dual sets no pending", vm.state.value.pendingRemove)
        assertEquals("no dual profile", vm.state.value.msg)
    }

    @Test
    fun requestRemove_labelAbsent_mapsNoDual() {
        // StageG P2：verified 96（typed 走 96）但快照只有 owner → 跨來源矛盾，
        // 不再是 NO_DUAL，改為 VERIFY_MISMATCH + INVALID（要求重查，零 uninstall）。
        // 真 NO_DUAL 只走 typed Absent + 快照可用（見 Round2PhaseB / StageC/G 新測）。
        val gw = FakeRepo(dualId = 96, ownerOnlyUsers = true)
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        vm.requestRemove("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.VERIFY_MISMATCH }
        assertNull("conflict sets no pending", vm.state.value.pendingRemove)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    @Test
    fun requestRemove_exception_mapsChannelError() {
        val gw = FakeRepo()
        val vm = makeVm(gw, JumpClock())
        await { vm.state.value.channelReady }
        gw.throwAll = true
        vm.requestRemove("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }
        assertNull("exception sets no pending", vm.state.value.pendingRemove)
    }
}
