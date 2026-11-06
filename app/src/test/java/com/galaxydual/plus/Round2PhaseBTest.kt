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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        // Phase B P0 閘控：首個 package-list 阻塞於 gate（leader 持有 mutex），
        // burst 在此期間觸發只記 pending；trailing 放行（n>1 不阻塞）。
        // 沿用 ReconnectCoalesceTest 閘控手法（既有 Fake 形狀擴充，非重寫）。
        @Volatile var packageListGate: CompletableDeferred<Unit>? = null
        @Volatile var idUGate: CompletableDeferred<Unit>? = null
        // B3 clone/remove 取消測閘控：install/uninstall 首輪阻塞於 gate，
        // 取消 in-flight waiter 後斷言 loading==false（沿 b3_cancelledRefresh 手法）。
        @Volatile var installGate: CompletableDeferred<Unit>? = null
        @Volatile var uninstallGate: CompletableDeferred<Unit>? = null
        var idUCalls: Int = 0
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
            cmd.trim() == "id -u" -> {
                val n = ++idUCalls
                if (n == 1) idUGate?.await()
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            }
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
                // 僅首輪阻塞：取消測 waiter 持有 mutex，取消後放行收尾。
                if (installCalls == 1) installGate?.await()
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                uninstallCalls++
                if (uninstallCalls == 1) uninstallGate?.await()
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val n = ++packageListCalls
                // 僅首輪阻塞：trailing 放行，drain 才能完成。
                if (n == 1) packageListGate?.await()
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

    // ---- P0 fail-closed：malformed prop 不得誤判 Absent 清空舊單 ----

    @Test
    fun p27_malformedProp_keepsOldList() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))

        // 第二輪：typed 雙源 OTHER {0,10}＋pm {0,10}（子集、無否決）＋
        // malformed prop "abc\n"（exit 0、無截斷、無 ERROR_LINE，但 parse 失敗）。
        // pm 切到 {0,10} 使 pm 否決不觸發，Unknown 只能來自 malformed 閘；
        // 未修復前會誤收斂 Absent 並報 NO_DUAL 清空舊單。
        suGw.dualId = 10
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
        suGw.propOverride = "abc\n"
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val t = vm.state.value
        assertTrue("malformed prop must keep the old list", t.cloned.contains("com.example.chat"))
        assertEquals("FRESH degrades to STALE, not cleared", InventoryFreshness.STALE, t.inventory.freshness)
        assertEquals("STALE keeps the old target identity", 95, t.inventory.targetId)
        assertEquals(MsgKind.CHANNEL, t.msgKind)
    }

    // ---- Phase B P0 (B2)：onForeground 無單飛門 → 連打 5 次各跑 probe+refresh ----
    // 修後：foregroundJob + foregroundPending 單飛，burst 收斂為 leader + 至多一次
    // trailing（package-list 增長：leader 1 + trailing 1 = 2；trailing 增長 <=1）。
    // FRESH 過期（此處以 STALE 常駐模擬：failListPackages 使每次 refresh 皆
    // VERIFY_UNKNOWN→STALE，inventory 永非 FRESH，故每次前景皆全量 refresh；
    // 無門則 5 waiter 各跑一次，單飛則 leader + trailing = 2）。
    @Test
    fun b2_foregroundBurst_collapsesToSingleTrailing() {
        val suGw = FakeRepo()
        val shGw = FakeRepo(priv = Privilege.SHELL)
        val vm = makeVm(suGw, shGw, JumpClock(), shizukuGw = shGw)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        val seedLists = suGw.packageListCalls + shGw.packageListCalls
        assertTrue("seed refresh must have listed once", seedLists > 0)

        // 常駐 STALE：後續每次 refresh 皆 UNKNOWN（不清單、保 STALE），
        // 使 burst 內每次前景皆符合全量 refresh 條件（inventory != FRESH）。
        suGw.failListPackages = true
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.STALE }

        suGw.packageListCalls = 0
        shGw.packageListCalls = 0
        suGw.packageListGate = CompletableDeferred()

        vm.onForeground()
        await { suGw.packageListCalls >= 1 }
        // leader 持有 mutex（首個 package-list 阻塞於 gate）時連打 4 次。
        repeat(4) { vm.onForeground() }
        // Main 是 Unconfined：burst 旗標已同步設定，放行即確定覆蓋全部。
        suGw.packageListGate?.complete(Unit)
        suGw.packageListGate = null

        await { !vm.state.value.loading && suGw.packageListCalls >= 2 }
        Thread.sleep(500)
        await { !vm.state.value.loading }

        val rounds = suGw.packageListCalls + shGw.packageListCalls
        assertEquals(
            "foreground burst must collapse to leader + one trailing (2 rounds), got $rounds",
            2,
            rounds,
        )
        // 任務契約字面：trailing 增長 <=1（leader 1 + trailing 1）。
        val trailing = rounds - 1
        assertTrue("foreground trailing growth must be <=1, got $trailing", trailing <= 1)
    }

    // ---- Phase B P0 (B3)：取消路徑 loading 殘 true ----
    // refreshLocked 取消即透傳不寫 loading=false，取消後 loading 卡 true。
    // 修後：取消路徑補 loading=false。閘控首個 package-list，啟動可取消
    // refresh 後 cancel job，斷言 loading==false。
    @Test
    fun b3_cancelledRefresh_clearsLoading() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        // 清零後閘控首個 package-list（refresh tail），leader 持有 mutex。
        suGw.packageListCalls = 0
        suGw.packageListGate = CompletableDeferred()

        vm.refresh()
        await { suGw.packageListCalls >= 1 }
        await { vm.state.value.loading }

        // 取消 in-flight refresh child（沿 DualViewModelTest 既有手法：
        // 取 scope children 差集定位 waiter，不殺整個 scope）。
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val waiter = scopeJob.children.firstOrNull { it.isActive }
        assertTrue("refresh must be in-flight while gated", waiter != null)
        waiter!!.cancel()
        await { waiter.isCompleted }

        // 取消路徑須即清 loading（即使 gate 仍阻塞）。
        await { !vm.state.value.loading }
        assertEquals(
            "cancelled refresh must clear loading, got ${vm.state.value.loading}",
            false,
            vm.state.value.loading,
        )

        suGw.packageListGate?.complete(Unit)
        suGw.packageListGate = null
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
    }

    // ---- Phase B P0 (B3)：clone 取消路徑 loading 殘 true ----
    // cloneVerified 取消即透傳不寫 loading=false，取消後 loading 卡 true。
    // 修後：取消路徑補 loading=false。閘控首個 install（clone 持有 mutex
    // 阻塞於 gate），啟動可取消 clone 後 cancel job，斷言 loading==false。
    // 沿 b3_cancelledRefresh 手法（閘控 gateway + cancel job）。
    @Test
    fun b3_cancelledClone_clearsLoading() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        // 閘控首個 install（clone 寫入段），leader 持有 mutex。
        suGw.installCalls = 0
        suGw.installGate = CompletableDeferred()

        vm.clone("com.example.chat")
        await { suGw.installCalls >= 1 }
        await { vm.state.value.loading }

        // 取消 in-flight clone child（沿 b3 手法：取 scope children 定位 waiter）。
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val waiter = scopeJob.children.firstOrNull { it.isActive }
        assertTrue("clone must be in-flight while gated", waiter != null)
        waiter!!.cancel()
        await { waiter.isCompleted }

        // 取消路徑須即清 loading（即使 gate 仍阻塞）。
        await { !vm.state.value.loading }
        assertEquals(
            "cancelled clone must clear loading, got ${vm.state.value.loading}",
            false,
            vm.state.value.loading,
        )

        suGw.installGate?.complete(Unit)
        suGw.installGate = null
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
    }

    // ---- Phase B P0 (B3)：confirmRemove 取消路徑 loading 殘 true ----
    // confirmRemove 取消即透傳不寫 loading=false，取消後 loading 卡 true。
    // 修後：取消路徑補 loading=false。兩段式：先 request 取得 pending，
    // 再閘控首個 uninstall（confirm 持有 mutex 阻塞於 gate），啟動可取消
    // confirm 後 cancel job，斷言 loading==false。沿 b3 手法。
    @Test
    fun b3_cancelledConfirmRemove_clearsLoading() {
        val suGw = FakeRepo(listExtra = listOf("com.example.victim"))
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        // 兩段式前置：request 建立 pending（零 uninstall，不閘控）。
        vm.requestRemove("com.example.victim")
        await { vm.state.value.pendingRemove != null }

        // 閘控首個 uninstall（confirm 執行段），leader 持有 mutex。
        suGw.uninstallCalls = 0
        suGw.uninstallGate = CompletableDeferred()

        vm.confirmRemove()
        await { suGw.uninstallCalls >= 1 }
        await { vm.state.value.loading }

        // 取消 in-flight confirm child（沿 b3 手法）。
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val waiter = scopeJob.children.firstOrNull { it.isActive }
        assertTrue("confirmRemove must be in-flight while gated", waiter != null)
        waiter!!.cancel()
        await { waiter.isCompleted }

        // 取消路徑須即清 loading（即使 gate 仍阻塞）。
        await { !vm.state.value.loading }
        assertEquals(
            "cancelled confirmRemove must clear loading, got ${vm.state.value.loading}",
            false,
            vm.state.value.loading,
        )

        suGw.uninstallGate?.complete(Unit)
        suGw.uninstallGate = null
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
    }

    // ---- Phase B P0 (B3)：requestRemove 取消路徑 loading 殘 true ----
    // requestRemove 取消即透傳不寫 loading=false，取消後 loading 卡 true
    // (clone/confirm/refresh/probe 皆已補取消清 loading，僅此路遺漏)。
    // 修後：取消路徑補 loading=false。閘控首個 package-list
    // (request verifyState 終探，leader 持有 mutex 阻塞於 gate)，啟動可取消
    // request 後 cancel job，斷言 loading==false。沿 b3 手法。
    @Test
    fun b3_cancelledRequestRemove_clearsLoading() {
        val suGw = FakeRepo(listExtra = listOf("com.example.victim"))
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }

        // 閘控首個 package-list (request verifyState 終探)，leader 持有 mutex。
        suGw.packageListCalls = 0
        suGw.packageListGate = CompletableDeferred()

        vm.requestRemove("com.example.victim")
        await { suGw.packageListCalls >= 1 }
        await { vm.state.value.loading }

        // 取消 in-flight request child（沿 b3 手法）。
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val waiter = scopeJob.children.firstOrNull { it.isActive }
        assertTrue("requestRemove must be in-flight while gated", waiter != null)
        waiter!!.cancel()
        await { waiter.isCompleted }

        // 取消路徑須即清 loading（即使 gate 仍阻塞）。
        await { !vm.state.value.loading }
        assertEquals(
            "cancelled requestRemove must clear loading, got ${vm.state.value.loading}",
            false,
            vm.state.value.loading,
        )

        suGw.packageListGate?.complete(Unit)
        suGw.packageListGate = null
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
    }

    // P27 reconnectLocked 修復回歸：AUTO reconnect 必須 probe 後完成完整
    // package refresh（refreshLocked 移出 else 共用）。健康 ROOT 下
    // reconnect 後 package-list IO 必須發生且 inventory queryTime 前進
    //（非舊快照），cloned 保留。
    @Test
    fun autoReconnect_runsFullPackageRefresh() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock(), preference = ChannelPreference.AUTO, shizukuGw = shGw)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        val qtBefore = vm.state.value.inventory.queryTimeMs

        suGw.packageListCalls = 0
        shGw.packageListCalls = 0
        vm.reconnectAndRefresh()
        await { !vm.state.value.loading && (suGw.packageListCalls + shGw.packageListCalls) >= 1 }
        await { !vm.state.value.loading }

        val lists = suGw.packageListCalls + shGw.packageListCalls
        assertTrue("AUTO reconnect must run package-list refresh, got $lists", lists >= 1)
        val qtAfter = vm.state.value.inventory.queryTimeMs
        assertTrue(
            "AUTO reconnect must refresh inventory (qt $qtBefore -> $qtAfter)",
            qtAfter > qtBefore,
        )
        assertEquals("refresh must converge to FRESH", InventoryFreshness.FRESH, vm.state.value.inventory.freshness)
        assertEquals("refresh must keep target identity", 95, vm.state.value.inventory.targetId)
        assertTrue(
            "refresh must keep cloned entries",
            vm.state.value.cloned.contains("com.example.chat"),
        )
    }

    // ---- 階段J J1a：onForeground burst 收斂 + queued 等待中 busy（特徵測試） ----
    // 防退化特徵測試，非 leading-busy/trailing-handoff 間隙的孤立回歸測試：
    // 在 Unconfined 調度下 leader 的 leading busy 與 foregroundJob 指派同步執行，
    // 第二個 tap 無法排在 leader 置 busy 之前（結構上不允許），且其自身入口亦置
    // busy，故等待中讀 busy 含第二 tap 自身 leading 置位的貢獻。真正的 trailing
    // handoff 間隙（leader 清位後、trailing 取鎖前）在此形狀下不可觀測，覆蓋靠
    // J1b（swap 懸掛窗口）+ 程式碼審查鎖定 trailing 預置 busy；此測試鎖定 burst
    // 收斂為 leader + 至多一次 trailing（2 輪 package-list）與等待中不斷 busy。
    @Test
    fun j1_foregroundBurst_coalescesAndHoldsBusyWhileQueued() {
        val suGw = FakeRepo()
        val shGw = FakeRepo(priv = Privilege.SHELL)
        val vm = makeVm(suGw, shGw, JumpClock(), shizukuGw = shGw)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        // 常駐 STALE 使每次前景皆走全量 refresh（package-list 可閘控）。
        suGw.failListPackages = true
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.STALE }

        suGw.packageListCalls = 0
        shGw.packageListCalls = 0
        suGw.packageListGate = CompletableDeferred()

        vm.onForeground()
        await { suGw.packageListCalls >= 1 }
        await { vm.state.value.loading }
        // 第二 tap 排在 leader 已 busy 之後（結構限制，見函數頭註解）：此斷言為
        // 特徵鎖定（queued 等待中不斷 busy），不孤立證明 trailing handoff 預置。
        vm.onForeground()
        Thread.sleep(100)
        assertTrue(
            "queued onForeground must read loading=true while waiting on opMutex, " +
                "got ${vm.state.value}",
            vm.state.value.loading,
        )

        suGw.packageListGate?.complete(Unit)
        suGw.packageListGate = null
        await { !vm.state.value.loading && (suGw.packageListCalls + shGw.packageListCalls) >= 2 }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        assertEquals(
            "foreground leader + trailing must run 2 rounds, got " +
                "${suGw.packageListCalls + shGw.packageListCalls}",
            2,
            suGw.packageListCalls + shGw.packageListCalls,
        )
        assertEquals(false, vm.state.value.loading)
    }

    // ---- 階段J J1b：swapAutoLocked 懸掛窗口 loading 維持 true ----
    // 閘 su 首個 `id -u`（swap 探測阻塞於 gate，比照 b3_cancelledRefresh 手法）：
    // 無 early-busy 時此窗口 loading=false（舊值）且按鈕啟用；修後維持 true。
    @Test
    fun j1_foregroundSwapBlocked_holdsBusy() {
        val suGw = FakeRepo()
        val shGw = FakeRepo(priv = Privilege.SHELL)
        val vm = makeVm(suGw, shGw, JumpClock(), shizukuGw = shGw)
        await { vm.state.value.channelReady }

        // 重置計數後閘控下一次 probe（swap 首個 `id -u` 阻塞於 gate）。
        suGw.idUCalls = 0
        suGw.idUGate = CompletableDeferred()

        vm.onForeground()
        await { suGw.idUCalls >= 1 }
        await { vm.state.value.loading }
        assertTrue(
            "onForeground must hold loading=true while swapAutoLocked id -u is gated",
            vm.state.value.loading,
        )
        // 持續 busy 採樣：整個阻塞窗口按鈕保持禁用（防中途閃斷）。
        val busyDeadline = System.currentTimeMillis() + 200
        while (System.currentTimeMillis() < busyDeadline) {
            assertTrue(
                "foreground swap block must keep loading=true, state=${vm.state.value}",
                vm.state.value.loading,
            )
            Thread.sleep(10)
        }

        suGw.idUGate?.complete(Unit)
        suGw.idUGate = null
        await(timeoutMs = 10_000L) { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
    }

    // ---- 階段J J2：probeChannels 外層 catch（時鐘/拷貝類非預期拋錯）降 inventory ----
    // 此處以一次性 nowWallMs 拋錯模擬時鐘/拷貝類非預期 throw（外層 catch 覆蓋範圍）：
    // probeOnce 內 elevator/hook 拋錯已就地吞掉走 !ready 正常路徑（CHANNEL），
    // binder dead 等通道失敗歸正常路徑，不進此外層 catch。
    // J3c 意圖鎖定：外層非預期拋錯 -> FAILED；正常 !ready（預期未就緒）-> CHANNEL。
    @Test
    fun j2_probeThrow_downgradesFreshToStale() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val clock = JumpClock()
        var bombArmed = false
        val vm = DualViewModel(
            repo = DualRepository(suGw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suGw), ShizukuElevator(shGw)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = suGw,
            shizukuGateway = shGw,
            activeGateway = suGw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = {
                if (bombArmed) {
                    bombArmed = false
                    throw RuntimeException("binder dead")
                }
                clock.now()
            },
            nowMonoMs = { clock.now() },
        )
        vms.add(vm)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)

        // 引爆 probeNow（probeChannels 內取時拋錯 → 外層 catch；一次性，catch 內
        // throwNow 取時已恢復）。時鐘/拷貝類非預期 throw 後 FRESH 不得續留。
        bombArmed = true
        vm.recheckPrivilege()
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertEquals(
            "probe throw must downgrade FRESH to STALE",
            InventoryFreshness.STALE,
            s.inventory.freshness,
        )
        assertEquals("STALE keeps the old target identity", 95, s.inventory.targetId)
        assertEquals("throw path msgKind must be FAILED (unexpected throw)", MsgKind.FAILED, s.msgKind)
        assertEquals(DiagnoseCode.PROBE_FAILED, s.diagnose?.code)
        assertNull("probe throw expires pmReadable", s.pmReadable)
        assertTrue("probe throw keeps old cloned entries", s.cloned.contains("com.example.chat"))
    }

    // ---- 階段K K1：前景前置 nowWallMs 非取消拋錯不得卡 loading；下次前景可恢復 ----
    // markStaleIfAgedLocked 內一次性時鐘炸彈 (RuntimeException)：非取消例外須走
    // 新增 catch (loading=false + 舊資料保留 + FRESH→STALE + FAILED)，
    // 不得逃出 coroutine 致 loading 楔住；炸彈解除後下次 onForeground 照常執行。
    @Test
    fun k1_foregroundClockThrow_clearsLoadingAndRecovers() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val clock = JumpClock()
        var bombArmed = false
        val vm = DualViewModel(
            repo = DualRepository(suGw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suGw), ShizukuElevator(shGw)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = suGw,
            shizukuGateway = shGw,
            activeGateway = suGw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = {
                if (bombArmed) {
                    bombArmed = false
                    throw RuntimeException("clock unavailable")
                }
                clock.now()
            },
            nowMonoMs = { clock.now() },
        )
        vms.add(vm)
        await { vm.state.value.channelReady }
        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)

        // 引爆前置時鐘炸彈：markStaleIfAgedLocked 內拋非取消例外。
        bombArmed = true
        vm.onForeground()
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertEquals("clock throw must clear loading, got ${s.loading}", false, s.loading)
        assertEquals(
            "clock throw must degrade FRESH to STALE",
            InventoryFreshness.STALE,
            s.inventory.freshness,
        )
        assertEquals("STALE keeps the old target identity", 95, s.inventory.targetId)
        assertTrue("old cloned entries are kept", s.cloned.contains("com.example.chat"))
        assertEquals("throw path msgKind must be FAILED (unexpected throw)", MsgKind.FAILED, s.msgKind)
        assertTrue("msg must carry the failure reason, got: ${s.msg}", s.msg.contains("clock unavailable"))

        // 下次前景 (時鐘已恢復) 仍可執行並回到 FRESH。
        vm.onForeground()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(false, vm.state.value.loading)
        assertEquals(95, vm.state.value.inventory.targetId)
        assertTrue(vm.state.value.cloned.contains("com.example.chat"))
    }

    // ---- 階段K K2：launch 預取 (opMutex 外層等待) 取消窗口不得殘 loading ----
    // clone 持有 mutex 阻塞於 installGate 時，refresh waiter 在外層等待被取消：
    // CE 在進入 refreshLocked 體前拋出，內層清位覆蓋不到；外層補清 loading。
    // 沿 b3 手法 (gate 阻塞 mutex + cancel + 斷言 loading=false)。
    @Test
    fun k2_cancelledMutexWaiter_clearsLoading() {
        val suGw = FakeRepo()
        val shGw = FakeRepo()
        val vm = makeVm(suGw, shGw, JumpClock())
        await { vm.state.value.channelReady }
        // clone 持有 mutex (首個 install 阻塞於 gate)。
        suGw.installCalls = 0
        suGw.installGate = CompletableDeferred()

        vm.clone("com.example.chat")
        await { suGw.installCalls >= 1 }
        await { vm.state.value.loading }

        // refresh 在外層等待 mutex (預取窗口，尚未進入 refreshLocked 體)。
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val before = scopeJob.children.toList()
        vm.refresh()
        await { (scopeJob.children.toList() - before).any { it.isActive } }
        val waiter = (scopeJob.children.toList() - before).firstOrNull { it.isActive }
        assertTrue("refresh must be waiting on opMutex while clone holds it", waiter != null)

        // 取消 waiter：即使 gate 仍阻塞，loading 須即清。
        waiter!!.cancel()
        await { waiter.isCompleted }
        await { !vm.state.value.loading }
        assertEquals(
            "cancelled pre-acquire waiter must clear loading, got ${vm.state.value.loading}",
            false,
            vm.state.value.loading,
        )

        suGw.installGate?.complete(Unit)
        suGw.installGate = null
        await { !vm.state.value.loading && vm.state.value.lastOperation != null }
        assertEquals(false, vm.state.value.loading)
    }
}
