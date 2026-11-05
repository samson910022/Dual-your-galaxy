// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase B (P2 五項) 新案例：不動既有測試檔，各項獨立斷言。
// - P2-4：選定通道失效即失效快照（明選 ROOT + AUTO 降級兩路）。
// - P2-5：verified -1 一律無法確認（typed 失敗保舊單；listing-null 複查 -1 走 UNKNOWN）。
// - P2-6：hook UNKNOWN 只重 probe，多次 onForeground 不增 package-list 呼叫。
// - P2-7：複製內容 == 預覽文本（同字串），按鈕繁中文案。
// - P2-8：超長輸入拒絕執行（零 install/uninstall，lastOperation 不變；256 邊界放行）。
//
// Harness 沿用 Phase5：DualViewModel 跑在 viewModelScope
// (Dispatchers.Main 換成 Unconfined)，repo I/O 在 Dispatchers.IO，poll
// StateFlow + timeout 等待；exit-0 語意避 retry settle sleep。
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Round2PhaseBTest {
    // 雙 fake：suGw 跑 repo + SuElevator；shGw 只跑 ShizukuElevator
    // (`pm list users` exit 0 即 hasShizuku，見 ShizukuElevator.probe)。
    // typed 失敗開關走 listUsersCalls 分代（沿用 Phase5 drift 機制）：
    // refresh 內 #1 snap/#2 listCloned snap/#3 nullSnap 計數，typed  runs
    // 穿插其間，故 typedFailFromListUsers=3 即「前兩次 typed 成功、
    // listing-null 後複查失敗」。
    private class FakeRepo(
        var dualId: Int = 95,
        var failListPackages: Boolean = false,
        var listExtra: List<String> = emptyList(),
        var rootOk: Boolean = true,
        var failListUsers: Boolean = false,
        var failTyped: Boolean = false,
        var typedFailFromListUsers: Int = Int.MAX_VALUE,
        var packageListCalls: Int = 0,
        var installCalls: Int = 0,
        var uninstallCalls: Int = 0,
        priv: Privilege = Privilege.ROOT,
    ) : CmdGateway {
        override val privilege: Privilege = priv
        var listUsersCalls: Int = 0
        // P1-1 VM 整合：允許覆寫 typed 通道原文以重現 False Absent 反例。
        var verboseOverride: String? = null
        var dumpsysOverride: String? = null
        var propOverride: String? = null

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

        private fun typedFailing(): Boolean = failTyped || listUsersCalls >= typedFailFromListUsers

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls++
                if (failListUsers) CmdResult(exit = 1, out = "", err = "denied")
                else CmdResult(exit = 0, out = usersOut(), err = "")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                if (typedFailing()) CmdResult(exit = 1, out = "", err = "typed denied")
                else CmdResult(exit = 0, out = verboseOverride ?: verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                if (typedFailing()) CmdResult(exit = 1, out = "", err = "typed denied")
                else CmdResult(exit = 0, out = dumpsysOverride ?: dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = propOverride ?: "$dualId\n", err = "")
            cmd.startsWith("pm install-existing") -> {
                installCalls++
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                uninstallCalls++
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                packageListCalls++
                if (failListPackages) CmdResult(exit = 0, out = "Failure [list failed]", err = "")
                else CmdResult(
                    exit = 0,
                    out = (listOf("package:com.example.chat") + listExtra.map { "package:$it" })
                        .joinToString("\n"),
                    err = "",
                )
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

    private class JumpClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }
    }

    private fun makeVm(
        suGw: FakeRepo,
        shGw: FakeRepo,
        clock: JumpClock,
        preference: ChannelPreference = ChannelPreference.AUTO,
        directReads: () -> List<MarkerRead> = {
            listOf(MarkerRead.Readable(null), MarkerRead.Readable(null))
        },
        // P2-6b: AUTO swap 後 repo 切到此 gateway；預設沿用真實 ShizukuCmdGateway
        //（既有測試只 probe 不 refresh，故不受影響），需 swap 後 refresh 的測試傳入 fake。
        shizukuGw: CmdGateway = ShizukuCmdGateway("com.example.test"),
    ): DualViewModel {
        val initialChannel = ResolvedChannel.ROOT
        val vm = DualViewModel(
            repo = DualRepository(suGw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suGw), ShizukuElevator(shGw)),
            preference = preference,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = suGw,
            shizukuGateway = shizukuGw,
            activeGateway = suGw,
            currentChannel = initialChannel,
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

    // ---- P2-4a：明選 ROOT 取 FRESH → ROOT 失效 + Shizuku 成功 → STALE ----

    @Test
    fun p24_explicitRootLosesReady_marksFreshStale() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock(), preference = ChannelPreference.ROOT)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)

        // ROOT 探測失效（id -u 不再為 0），Shizuku 通道仍成功。
        suGw.rootOk = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.STALE }

        val s = vm.state.value
        // P25 Item 3 新語意：明選只探自己通道 (ROOT→su only)，他通道
        // (Shizuku) 故意不探，故 anyProbePassed == writeReady == false；
        // 不再是舊 readCap 的 device-wide 含義。
        assertFalse("explicit probes only the selected channel", s.anyProbePassed)
        assertFalse("selected ROOT channel lost readiness", s.writeReady)
        assertEquals("STALE keeps the old target identity", 95, s.inventory.targetId)
        assertTrue("old cloned entries are kept", s.cloned.contains("com.example.chat"))
    }

    // ---- P2-4b：AUTO 降級（resolved ROOT→SHIZUKU）→ STALE ----

    @Test
    fun p24_autoDegrade_marksFreshStale() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock(), preference = ChannelPreference.AUTO)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        suGw.rootOk = false
        vm.recheckPrivilege()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU &&
                vm.state.value.inventory.freshness == InventoryFreshness.STALE
        }
        assertEquals("STALE keeps the old target identity", 95, vm.state.value.inventory.targetId)
        assertTrue("old cloned entries are kept", vm.state.value.cloned.contains("com.example.chat"))
    }

    // ---- P2-5a：FRESH 後 typed 指令失敗 → 保舊單 + CHANNEL_ERROR ----

    @Test
    fun p25_typedFailure_keepsOldListAsChannelError() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))

        suGw.failTyped = true
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val s = vm.state.value
        assertTrue("typed failure must keep the old list", s.cloned.contains("com.example.chat"))
        assertEquals("FRESH degrades to STALE, not UNKNOWN", InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals("STALE keeps the old target identity", 95, s.inventory.targetId)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertTrue("msg must say unconfirmable, got: ${s.msg}", s.msg.contains("cannot confirm"))
    }

    // ---- P2-5b：listing 失敗 + 複查 -1 → UNKNOWN，非 MISMATCH ----

    @Test
    fun p25_listingNullAndReresolveUnknown_reportsUnknownNotMismatch() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        // 清單失敗 + 第三次 listUsers 起 typed 失敗：初查 Confirmed(95)，
        // 複查 Unknown（舊 Int -1）→ 必須走 UNKNOWN，不報 drift。
        // 計數在 seed 後歸零（init probe 與 seed refresh 的 listUsers 不計入）。
        suGw.failListPackages = true
        suGw.listUsersCalls = 0
        suGw.typedFailFromListUsers = 3
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.VERIFY_UNKNOWN }

        val s = vm.state.value
        assertEquals("reresolve unknown keeps the listed target", 95, s.inventory.targetId)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertTrue("old cloned entries are kept", s.cloned.contains("com.example.chat"))
        assertEquals(MsgKind.CHANNEL, s.msgKind)
    }

    // ---- P2-6：hook 不可讀 + FRESH 時多次 onForeground 只 probe 不 refresh ----

    @Test
    fun p26_hookUnknownForeground_onlyProbesWithoutPackageList() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val deniedReads: () -> List<MarkerRead> = { listOf(MarkerRead.Denied, MarkerRead.Denied) }
        val vm = makeVm(suGw, shGw, JumpClock(), directReads = deniedReads)
        await { vm.state.value.channelReady }
        assertTrue(
            "hook must be UNKNOWN for this test, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC,
        )
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        val packageListsBefore = suGw.packageListCalls
        assertTrue("seed refresh must have listed once", packageListsBefore > 0)

        repeat(3) {
            val q = vm.state.value.hookQueriedAtMs
            vm.onForeground()
            await { vm.state.value.hookQueriedAtMs > q && !vm.state.value.loading }
        }

        assertEquals(
            "hook-UNKNOWN foreground must not run package-list again (probe only)",
            packageListsBefore,
            suGw.packageListCalls,
        )
        assertEquals("light probe keeps FRESH", InventoryFreshness.FRESH, vm.state.value.inventory.freshness)
        assertTrue(
            "hook stays UNKNOWN, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC,
        )
    }

    // ---- P2-6b：AUTO swap 後 onForeground 走全量 refresh（package-list 增長、重回 FRESH） ----

    @Test
    fun p26_autoSwapForeground_runsRefresh() {
        val suGw = FakeRepo()
        val shGw = FakeRepo(priv = Privilege.SHELL)
        val vm = makeVm(suGw, shGw, JumpClock(), shizukuGw = shGw)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        val listsBefore = suGw.packageListCalls + shGw.packageListCalls
        assertTrue("seed refresh must have listed once", listsBefore > 0)

        // ROOT 失效使 AUTO 由 ROOT 切到 SHIZUKU：前景必須走全量 refresh（通道已換），
        // 而非輕探（P2-6 輕探只免 hook-UNKNOWN 的重查，通道變化仍重查）。
        suGw.rootOk = false
        vm.onForeground()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val listsAfter = suGw.packageListCalls + shGw.packageListCalls
        assertTrue(
            "AUTO-swap foreground must run package-list again (full refresh)," +
                " before=$listsBefore after=$listsAfter",
            listsAfter > listsBefore,
        )
        assertTrue("swapped channel lists again", shGw.packageListCalls > 0)
        assertEquals("refresh keeps the same dual target", 95, vm.state.value.inventory.targetId)
    }

    // ---- P2-7：複製內容 == 預覽文本 ----

    @Test
    fun p27_copyTextEqualsPreview() {
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
            channel = ChannelPreference.ROOT,
            resolvedGateway = ResolvedChannel.ROOT,
            execIdentity = Privilege.ROOT,
        )
        assertEquals(
            "複製內容必須等於預覽文本（同字串）",
            buildDiagnosticExport(state),
            diagnosticExportCopyText(state),
        )
        assertFalse(
            "複製內容不得洩漏原始包名",
            diagnosticExportCopyText(state).contains(rawPkg),
        )
        // P2-4 誠實文案：複製按鈕須附「分享前請人工確認」，不得只寫已遮蔽。
        assertEquals("複製已遮蔽內容（分享前請人工確認）", DIAGNOSTIC_COPY_BUTTON_TEXT)
    }

    // ---- P2-8a：超長 clone 拒絕（零 install，歷史不變） ----

    @Test
    fun p28_overlongClone_rejectedWithoutInstall() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        vm.clone("com.example.chat")
        await { vm.state.value.lastOperation?.opId == "clone:com.example.chat" }
        val base = vm.state.value.lastOperation!!
        val invBefore = vm.state.value.inventory
        suGw.installCalls = 0

        val longPkg = "com.example." + "x".repeat(500)
        assertTrue(longPkg.trim().length > MAX_PACKAGE_INPUT_CHARS)
        vm.clone(longPkg)
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.INVALID_INPUT }

        assertEquals("overlong clone must run zero installs", 0, suGw.installCalls)
        assertEquals("overlong clone must not touch lastOperation", base, vm.state.value.lastOperation)
        assertEquals("overlong clone must not touch inventory", invBefore, vm.state.value.inventory)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // ---- P2-8b：超長 remove 拒絕（零 uninstall，無 pending，歷史不變） ----

    @Test
    fun p28_overlongRemove_rejectedWithoutUninstall() {
        val suGw = FakeRepo(listExtra = listOf("com.example.victim"))
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }

        val longPkg = "com.example." + "x".repeat(500)
        assertTrue(longPkg.trim().length > MAX_PACKAGE_INPUT_CHARS)
        vm.requestRemove(longPkg)
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.INVALID_INPUT }

        assertEquals("overlong request must run zero uninstalls", 0, suGw.uninstallCalls)
        assertNull("overlong request must set no pending", vm.state.value.pendingRemove)
        assertNull("overlong request must not touch lastOperation", vm.state.value.lastOperation)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // ---- P2-8c：256 邊界放行（仍會走到裝置） ----

    @Test
    fun p28_exactLimitClone_reachesDevice() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        // 恰 256 字元且字元集合法：必須通過長度門檻走到裝置（install 執行；
        // 清單無此包故回 VERIFY_MISMATCH，證明非拒絕路徑）。
        val pkg256 = "com.example." + "x".repeat(256 - "com.example.".length)
        assertEquals(MAX_PACKAGE_INPUT_CHARS, pkg256.trim().length)
        vm.clone(pkg256)
        await { !vm.state.value.loading && vm.state.value.lastOperation != null }
        assertEquals("exact-limit input must reach the device", 1, suGw.installCalls)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, vm.state.value.diagnose?.code)
    }

    // ---- P1-1 VM 整合：False Absent 反例（兩源有戶但型別缺失）不得清空舊單 ----

    @Test
    fun p11_falseAbsentTypeMissing_keepsOldList() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))

        // 兩源都有 owner+95 但 95 型別缺失→UNKNOWN；快照仍可用（pm-list 正常）。
        suGw.verboseOverride =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n"
        suGw.dumpsysOverride =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n"
        suGw.propOverride = ""
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val s = vm.state.value
        assertTrue("false-absent must keep the old list", s.cloned.contains("com.example.chat"))
        assertEquals("FRESH degrades to STALE, not cleared", InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals("STALE keeps the old target identity", 95, s.inventory.targetId)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
    }

    // ---- P1-1 VM 整合：property=95 但 typed 無 clone 聲稱不得清空舊單 ----

    @Test
    fun p11_falseAbsentPropPointsToClone_keepsOldList() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))

        // 兩源乾淨 OTHER、集合一致，但 property=95 指向仍可能存在的 clone。
        suGw.verboseOverride =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{10:Work profile:30} running:\n" +
                "    Serial: 11\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.MANAGED\n"
        suGw.dumpsysOverride =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{10:Work profile:30} serialNo=11:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.MANAGED\n"
        suGw.propOverride = "95\n"
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val s = vm.state.value
        assertTrue("prop-pointing-to-clone must keep the old list", s.cloned.contains("com.example.chat"))
        assertEquals("FRESH degrades to STALE, not cleared", InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals("STALE keeps the old target identity", 95, s.inventory.targetId)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
    }
}
