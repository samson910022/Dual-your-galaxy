// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-7 P2 (issue #26, 階段 1):個人來源 catalog + 選取持久化 JVM 測試。
// - 搜尋/篩選/排序 (SourceInventory 純函數)。
// - AppKey = sourceSerial:package (同包名不同來源為不同 key；圖示載入狀態
//   永不進入身分鍵)。
// - 選取持久化：保存後新 client 可重讀；重啟恢復只打勾不重送寫入；profile
//   變動失效舊選取；損壞恢復；取消勾選/取消保存 ≠ 卸載 (零 gateway 呼叫)。
// - 批次 (階段 1 仍走單筆 cloneVerified 逐項)：逐項結果/部分成功/unknown
//   分流；唯讀拒絕；空選取拒絕。
//
// Harness mirrors DiagnoseFreshnessTest: DualViewModel runs on viewModelScope
// (Dispatchers.Main replaced with Unconfined), repo I/O on Dispatchers.IO,
// tests poll StateFlow with a timeout. No device, no Android runtime; the
// fake gateway serves verified-identity channels plus controllable
// install/list probes.
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CloneOutcome
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InventorySelectionTest {
    // Fake repo gateway: identity channels follow `dualId`; install adds to
    // `installedForUser` unless in `installFail`; the list probe reports
    // `installedForUser` minus `hideFromList`, or Unknown when
    // `failListPackages` (exit-0 error text avoids the core retry settle sleep).
    private class FakeRepo(
        var dualId: Int = 95,
        val rootOk: Boolean = true,
        var failListPackages: Boolean = false,
        val installFail: MutableSet<String> = mutableSetOf(),
        val hideFromList: MutableSet<String> = mutableSetOf(),
        val installedForUser: MutableSet<String> = mutableSetOf(),
        // M13 測試鉤子：當 installCalls 達到此數後，verbose/dumpsys 不再回報
        // 分身用戶（模擬批次中途分身消失；-1 停用）。identity 解析在 install
        // 之前，故首項仍打一次裝置，後續項才 NoDual。
        var vanishDualAfterInstalls: Int = -1,
        // B2 測試鉤子：install 分支在 IO 執行緒上停留此毫秒數，讓首批慢批次
        // 在持有 inFlight key 時可被併發第二批/單筆觀測到。預設 0（無行為變化）。
        var installDelayMs: Long = 0L,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        val installCalls = mutableListOf<String>()
        val uninstallCalls = mutableListOf<String>()

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String {
            if (vanishDualAfterInstalls >= 0 && installCalls.size >= vanishDualAfterInstalls) {
                return "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n"
            }
            return "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"
        }

        private fun dumpsysOut(): String {
            if (vanishDualAfterInstalls >= 0 && installCalls.size >= vanishDualAfterInstalls) {
                return "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n"
            }
            return "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
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
                val pkg = cmd.trim().split(" ").last()
                installCalls.add(pkg)
                if (installDelayMs > 0) Thread.sleep(installDelayMs)
                if (pkg in installFail) {
                    CmdResult(exit = 1, out = "", err = "Failure [INSTALL_FAILED]")
                } else {
                    installedForUser.add(pkg)
                    CmdResult(exit = 0, out = "Success", err = "")
                }
            }
            cmd.startsWith("pm uninstall") -> {
                val pkg = cmd.trim().split(" ").last()
                uninstallCalls.add(pkg)
                installedForUser.remove(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") ->
                if (failListPackages) CmdResult(exit = 0, out = "Failure [list failed]", err = "")
                else CmdResult(
                    exit = 0,
                    out = (installedForUser - hideFromList).map { "package:$it" }
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

    private class FakeClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }
    }

    // HashMap-backed SelectionStore: models the app-private prefs file; a new
    // SelectionStore over the same map models a restarted process client.
    private class MapBackend {
        val map = mutableMapOf<String, String?>()
        fun store(): SelectionStore = SelectionStore(
            loadRaw = { map[SELECTION_KEY] },
            saveRaw = { raw ->
                if (raw == null) map.remove(SELECTION_KEY) else map[SELECTION_KEY] = raw
                true
            },
        )
    }

    private fun makeVm(
        gw: FakeRepo,
        clock: FakeClock,
        store: SelectionStore?,
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
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowMs = { clock.now() },
            selectionStore = store,
        )
        vms.add(vm)
        return vm
    }

    private fun catalog(): List<SourceAppEntry> = listOf(
        SourceAppEntry("com.example.chat", "Chat App", 0, false),
        SourceAppEntry("com.example.mail", "Mail", 0, false),
        SourceAppEntry("com.android.settings", "Settings", 0, true),
    )

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

    // 搜尋：大小寫無關，比對 label 或包名子字串；系統 App 預設剔除。
    @Test
    fun search_filtersByLabelAndPackage() {
        val apps = catalog()
        assertEquals(
            listOf("com.example.chat"),
            filterSourceApps(apps, "chat", includeSystem = false).map { it.packageName },
        )
        assertEquals(
            listOf("com.example.mail"),
            filterSourceApps(apps, "MAIL", includeSystem = false).map { it.packageName },
        )
        assertEquals(
            listOf("com.example.chat", "com.example.mail").sorted(),
            filterSourceApps(apps, "com.example.", includeSystem = false)
                .map { it.packageName }.sorted(),
        )
        // 系統 App 預設隱藏，開關開啟後才出現。
        assertTrue(
            filterSourceApps(apps, "", includeSystem = false)
                .none { it.packageName == "com.android.settings" },
        )
        assertTrue(
            filterSourceApps(apps, "", includeSystem = true)
                .any { it.packageName == "com.android.settings" },
        )
        assertTrue(filterSourceApps(apps, "zzz-no-match", includeSystem = true).isEmpty())
    }

    // 排序：label 大小寫無關升冪，同名再比包名（全序，渲染/批次確定）。
    @Test
    fun sort_ordersByLabelThenPackage() {
        val apps = listOf(
            SourceAppEntry("com.example.b2", "beta", 0, false),
            SourceAppEntry("com.example.a", "Alpha", 0, false),
            SourceAppEntry("com.example.b1", "Beta", 0, false),
        )
        assertEquals(
            listOf("com.example.a", "com.example.b1", "com.example.b2"),
            sortSourceApps(apps).map { it.packageName },
        )
    }

    // AppKey = serial:package；同包名不同來源為不同 key；
    // 圖示載入記憶不影響 key/篩選/排序。
    @Test
    fun appKey_includesSourceSerial_iconStateNeverKeys() {
        val a = SourceAppEntry("com.example.chat", "Chat", 0, false)
        val b = SourceAppEntry("com.example.chat", "Chat", 7, false)
        assertEquals("0:com.example.chat", a.appKey)
        assertEquals("7:com.example.chat", b.appKey)
        assertTrue(a.appKey != b.appKey)

        val memo = SourceIconState()
        memo.markLoaded(a.appKey)
        assertTrue(memo.isLoaded(a.appKey))
        assertFalse("icon memo of one source must not leak to another", memo.isLoaded(b.appKey))
        // 記憶不參與 key：同一 key 仍同一 key，不同 key 仍不同。
        assertEquals(a.appKey, sourceAppKey(a.sourceSerial, a.packageName))
        assertEquals(
            listOf(a, b).map { it.appKey }.toSet().size,
            2,
        )
    }

    // 保存後新 client 可重讀（同一後端新建 SelectionStore）。
    // M7: 新寫入一律 AppKey ("serial:package")；白名單容納 ":"。
    @Test
    fun selection_persistsAcrossClients() {
        val backend = MapBackend()
        val s1 = backend.store()
        assertTrue(s1.save(SELECTION_SOURCE_CATALOG, 95, setOf("0:com.a", "0:com.b"), 1000L))
        val s2 = backend.store()
        assertEquals(setOf("0:com.a", "0:com.b"), s2.loadSelected(95))
        assertEquals(95, s2.loadSnapshot()?.knownTargetId)
        assertEquals(SELECTION_SOURCE_CATALOG, s2.loadSnapshot()?.source)
    }

    // 重啟恢復：新 ViewModel + 同一 store，恢復只打勾（不重送寫入），
    // 只恢復仍在 catalog 的 key（catalog 缺失的殘留 key 被捨去）；
    // 已有手動勾選時 restore 永不覆蓋。
    @Test
    fun selection_restartRecovery_checksButNeverClones() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm1 = makeVm(gw, clock, backend.store())
        await { vm1.state.value.channelReady }
        vm1.setSourceCatalog(catalog())
        vm1.toggleSelection("0:com.example.chat")
        vm1.toggleSelection("0:com.example.mail")
        vm1.persistSelection(95)
        await { vm1.state.value.msg.startsWith("已保存選取 2 項") }
        assertEquals(0, gw.installCalls.size)
        // catalog 已無的殘留 key（已解除安裝的 gone-pkg）：直接寫入 store
        // 模擬舊存檔（persistSelection 只存 catalog 內 key，寫不進 gone-pkg）。
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG,
                95,
                setOf("0:com.example.chat", "0:com.example.mail", "0:com.example.gone"),
                2000L,
            ),
        )

        // 重啟：新 ViewModel，同一 store；catalog 含 chat/mail/extra，不含 gone。
        val vm2 = makeVm(gw, clock, backend.store())
        await { vm2.state.value.channelReady }
        vm2.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat App", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
                SourceAppEntry("com.example.extra", "Extra", 0, false),
            ),
        )
        vm2.restoreSelection(95)
        await { "0:com.example.chat" in vm2.state.value.selectedKeys }
        assertEquals(
            "only keys still in the catalog are restored (gone-pkg dropped)",
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm2.state.value.selectedKeys,
        )
        assertEquals("restore must not clone", 0, gw.installCalls.size)
        assertEquals("restore must not uninstall", 0, gw.uninstallCalls.size)

        // 已有手動勾選時 restore 永不覆蓋：先勾選再設 catalog（同步阻斷
        // setSourceCatalog 的自動補恢復），restore 必須是同步 no-op。
        val vm3 = makeVm(gw, clock, backend.store())
        await { vm3.state.value.channelReady }
        vm3.toggleSelection("0:com.example.extra")
        vm3.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat App", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
                SourceAppEntry("com.example.extra", "Extra", 0, false),
            ),
        )
        vm3.restoreSelection(95)
        await { vm3.state.value.selectedKeys == setOf("0:com.example.extra") }
        assertEquals(
            "restore must never overwrite existing manual selection",
            setOf("0:com.example.extra"),
            vm3.state.value.selectedKeys,
        )
        assertEquals("restore must not clone", 0, gw.installCalls.size)
        assertEquals("restore must not uninstall", 0, gw.uninstallCalls.size)
    }

    // 空 catalog 恢復不毒化 marker：先空 catalog restore（no-op），後載非空
    // catalog 後仍能恢復（重啟生產路徑：refresh 先跑空 catalog，後載 catalog）。
    @Test
    fun restore_emptyCatalogDoesNotPoisonMarker() {
        val backend = MapBackend()
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG,
                95,
                setOf("0:com.example.chat", "0:com.example.mail"),
                1000L,
            ),
        )
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        assertTrue(vm.state.value.sourceCatalog.isEmpty())
        // 空 catalog 上恢復是同步 no-op：early-return 發生在任何 I/O 懸掛點
        // 之前，且此時 opMutex 空閒（channelReady 已在探測退出時寫入），故在
        // Unconfined 下呼叫執行緒內即完成——直接斷言，不 sleep 輪詢。
        vm.restoreSelection(95)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        // 後載非空 catalog：顯式重試仍能恢復（setSourceCatalog 亦會補觸一次）。
        vm.setSourceCatalog(catalog())
        vm.restoreSelection(95)
        await { "0:com.example.chat" in vm.state.value.selectedKeys }
        assertEquals(
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm.state.value.selectedKeys,
        )
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // targetId 0 與負值一致拒絕：save(0) 永不落盤（與 isSelectionValidFor >0 一致）。
    @Test
    fun save_rejectsNonPositiveTargetId() {
        val backend = MapBackend()
        val store = backend.store()
        assertFalse(store.save(SELECTION_SOURCE_CATALOG, 0, setOf("com.a"), 1000L))
        assertFalse(store.save(SELECTION_SOURCE_CATALOG, -1, setOf("com.a"), 1000L))
        assertNull(store.loadSnapshot())
        assertEquals(emptySet<String>(), store.loadSelected(0))
    }

    // profile 變動失效：保存目標 95，新目標 96 讀到空，且陳舊條目被清除
    //（之後連 95 也讀不到，舊選取永不復活）。
    @Test
    fun profileChange_invalidatesSavedSelection() {
        val backend = MapBackend()
        val store = backend.store()
        assertTrue(store.save(SELECTION_SOURCE_CATALOG, 95, setOf("com.a"), 1000L))
        assertEquals(emptySet<String>(), store.loadSelected(96))
        assertNull("stale entry must be cleared on target change", store.loadSnapshot())
        assertEquals(emptySet<String>(), store.loadSelected(95))
    }

    // 損壞恢復：垃圾字串 -> 空集合、不拋出、損壞條目被清除，之後可正常保存。
    @Test
    fun corruptSnapshot_recoversGracefully() {
        val backend = MapBackend()
        backend.map[SELECTION_KEY] = "###garbage-not-a-selection###\nzzz"
        val store = backend.store()
        assertEquals(emptySet<String>(), store.loadSelected(95))
        assertNull(store.loadSnapshot())
        assertTrue(store.save(SELECTION_SOURCE_MANUAL, 95, setOf("com.a"), 1L))
        assertEquals(setOf("com.a"), backend.store().loadSelected(95))
    }

    // 取消勾選/取消保存 ≠ 卸載：零 gateway 寫入呼叫。
    @Test
    fun cancel_isNotUninstall() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.toggleSelection("0:com.example.chat")
        vm.toggleSelection("0:com.example.mail")
        assertEquals(2, vm.state.value.selectedKeys.size)

        vm.clearSelectionLocal()
        assertTrue(vm.state.value.selectedKeys.isEmpty())

        vm.toggleSelection("0:com.example.chat")
        vm.persistSelection(95)
        await { vm.state.value.msg.startsWith("已保存選取") }
        vm.cancelSavedSelection()
        await { vm.state.value.msg.startsWith("已取消保存選取") }
        assertNull(backend.store().loadSnapshot())

        assertEquals("no install may run", 0, gw.installCalls.size)
        assertEquals("cancel must never uninstall", 0, gw.uninstallCalls.size)
        assertTrue(vm.state.value.msg.contains("不影響已安裝分身"))
    }

    // 批次部分成功：成功/失敗/未知分流，cloned 只收成功，逐項列出。
    @Test
    fun batch_partialSuccess_reportsPerItem() {
        val backend = MapBackend()
        val gw = FakeRepo()
        gw.installFail.add("com.example.bad")
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.good", "Good", 0, false),
                SourceAppEntry("com.example.bad", "Bad", 0, false),
            ),
        )
        vm.toggleSelection("0:com.example.good")
        vm.toggleSelection("0:com.example.bad")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }

        val report = vm.state.value.batchReport!!
        assertEquals(2, report.items.size)
        assertEquals(1, report.successCount)
        assertEquals(1, report.failedCount)
        assertEquals(0, report.unknownCount)
        assertTrue(vm.state.value.cloned.contains("com.example.good"))
        assertFalse(vm.state.value.cloned.contains("com.example.bad"))
        assertTrue(vm.state.value.msg.contains("成功 1 / 失敗 1 / 未知 0"))
        assertEquals(
            setOf("com.example.good", "com.example.bad"),
            gw.installCalls.toSet(),
        )
        assertEquals("batch must not uninstall", 0, gw.uninstallCalls.size)
        // M1/M2/M10: 最終彙總覆寫批次級 LastOperation + diagnose，≥1 成功標 STALE。
        val last = vm.state.value.lastOperation
        assertNotNull("batch must leave a batch-level LastOperation", last)
        assertEquals("batch:clone", last!!.opId)
        assertEquals(OpPhase.FAILED, last.phase)
        assertEquals("批次 2 項：成功 1/失敗 1/未知 0", last.detail)
        assertEquals(DiagnoseCode.OP_FAILED, vm.state.value.diagnose?.code)
        assertEquals(
            "any success must mark inventory STALE (no auto refresh)",
            InventoryFreshness.STALE,
            vm.state.value.inventory.freshness,
        )
    }

    // 批次 unknown：安裝成功但查證通道失敗 -> unknown，不算失敗，cloned 不收。
    @Test
    fun batch_verifyUnknown_reportsUnknown() {
        val backend = MapBackend()
        val gw = FakeRepo(failListPackages = true)
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.myst", "Myst", 0, false)))
        vm.toggleSelection("0:com.example.myst")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }

        val report = vm.state.value.batchReport!!
        assertEquals(1, report.items.size)
        assertEquals(0, report.successCount)
        assertEquals(0, report.failedCount)
        assertEquals(1, report.unknownCount)
        assertTrue(report.items.single().outcome is CloneOutcome.VerifyUnknown)
        assertFalse(vm.state.value.cloned.contains("com.example.myst"))
        assertTrue(vm.state.value.msg.contains("未知 1"))
        // M1/M2: unknown-only 批次 phase UNKNOWN + VERIFY_UNKNOWN diagnose，無成功不標 STALE。
        val lastU = vm.state.value.lastOperation
        assertNotNull(lastU)
        assertEquals("batch:clone", lastU!!.opId)
        assertEquals(OpPhase.UNKNOWN, lastU.phase)
        assertEquals("批次 1 項：成功 0/失敗 0/未知 1", lastU.detail)
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
    }

    // M1 全成功清 diagnose： failed==0 && unknown==0 則 diagnose=null，
    // 批次級 LastOperation phase SUCCESS，並標 STALE。
    @Test
    fun batch_allSuccess_clearsDiagnoseAndMarksStale() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.good")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }

        val report = vm.state.value.batchReport!!
        assertEquals(1, report.successCount)
        assertEquals(0, report.failedCount)
        assertEquals(0, report.unknownCount)
        assertNull("all-success batch must clear diagnose (single-clone parity)", vm.state.value.diagnose)
        val last = vm.state.value.lastOperation
        assertNotNull(last)
        assertEquals("batch:clone", last!!.opId)
        assertEquals(OpPhase.SUCCESS, last.phase)
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
    }

    // m1 显示回退：catalog 缺失 key 的 BatchItemResult.pkg 不含 "serial:" 前綴。
    @Test
    fun batch_missingCatalogKey_usesDisplayFallback() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.good")
        vm.toggleSelection("0:com.example.ghost")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }

        val report = vm.state.value.batchReport!!
        assertEquals(2, report.items.size)
        val ghost = report.items.first { it.appKey == "0:com.example.ghost" }
        assertEquals(
            "missing-key display pkg must strip the serial prefix",
            "com.example.ghost",
            ghost.pkg,
        )
        assertTrue(ghost.outcome is CloneOutcome.InvalidPackage)
        // InvalidPackage 計入 failed（非 unknown）。
        assertEquals(1, report.successCount)
        assertEquals(1, report.failedCount)
        assertEquals(0, report.unknownCount)
        // 缺失 key 永不打裝置（install 側記的是裸包名，此處必須比裸包名）。
        assertTrue(gw.installCalls.none { it == "com.example.ghost" })
        assertEquals(listOf("com.example.good"), gw.installCalls)
    }

    // 空選取：拒絕，不碰裝置。
    @Test
    fun batch_emptySelection_refusesWithoutDevice() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.cloneSelected()
        await { vm.state.value.msg == "未勾選任何應用" }
        assertEquals(0, gw.installCalls.size)
    }

    // 唯讀通道：整批拒絕，零安裝呼叫。哨兵法：probe 靜默後先把 msg 壓成
    // 已知哨兵（clearSelectionLocal 同步改寫，不碰裝置），再重建勾選並呼叫
    // cloneSelected；之後的 msg 變化只能來自本次拒絕，消除「呼叫前已成立」
    // 的空洞通過。
    @Test
    fun batch_readOnly_refusesWholeBatch() {
        val backend = MapBackend()
        val gw = FakeRepo(rootOk = false)
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { !vm.state.value.loading && vm.state.value.hookQueriedAtMs > 0L }
        assertFalse(vm.state.value.channelReady)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.good")
        vm.clearSelectionLocal()
        assertTrue(vm.state.value.msg.startsWith("已清除勾選"))
        vm.toggleSelection("0:com.example.good")
        val before = vm.state.value.msg
        vm.cloneSelected()
        await { !vm.state.value.loading && vm.state.value.msg != before }
        val st = vm.state.value
        assertEquals(MsgKind.CHANNEL, st.msgKind)
        assertTrue(
            "read-only refusal must name the cause: ${st.msg}",
            st.msg.contains("探測未通過") || st.msg.contains("唯讀") ||
                st.msg.contains("read-only") || st.msg.contains("寫入已禁用"),
        )
        assertNull("read-only must not produce a batch report", st.batchReport)
        assertEquals("selection must survive refusal", setOf("0:com.example.good"), st.selectedKeys)
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // (a) 批次執行順序：包名升冪，與勾選順序無關；裝置呼叫順序一致。
    @Test
    fun batch_executesInPackageNameAscendingOrder() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.zeta", "Zeta", 0, false),
                SourceAppEntry("com.example.alpha", "Alpha", 0, false),
                SourceAppEntry("com.example.mid", "Mid", 0, false),
            ),
        )
        vm.toggleSelection("0:com.example.zeta")
        vm.toggleSelection("0:com.example.mid")
        vm.toggleSelection("0:com.example.alpha")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val pkgs = listOf("com.example.alpha", "com.example.mid", "com.example.zeta")
        assertEquals(pkgs, vm.state.value.batchReport!!.items.map { it.pkg })
        assertEquals("device calls must follow the same ascending order", pkgs, gw.installCalls)
    }

    // (b) 單一 catalog 缺失 key：InvalidPackage、計 failed（非 unknown）、零裝置呼叫。
    @Test
    fun batch_missingKey_recordsInvalidPackageWithoutDeviceCall() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.ghost")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals(1, report.items.size)
        assertTrue(report.items.single().outcome is CloneOutcome.InvalidPackage)
        assertEquals(0, report.successCount)
        assertEquals(1, report.failedCount)
        assertEquals(0, report.unknownCount)
        assertTrue(gw.installCalls.isEmpty())
    }

    // (c/M13) 首個 NoDual 後短路：首項成功，剩餘項同記 NoDual，不再打裝置。
    @Test
    fun batch_noDualShortCircuit_skipsRemainingDeviceCalls() {
        val backend = MapBackend()
        val gw = FakeRepo()
        // 首個 install 完成後分身身份消失：後續解析皆 NoDual。
        gw.vanishDualAfterInstalls = 1
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.a1", "A1", 0, false),
                SourceAppEntry("com.example.a2", "A2", 0, false),
                SourceAppEntry("com.example.a3", "A3", 0, false),
            ),
        )
        vm.toggleSelection("0:com.example.a3")
        vm.toggleSelection("0:com.example.a2")
        vm.toggleSelection("0:com.example.a1")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals(3, report.items.size)
        assertTrue(report.items[0].outcome is CloneOutcome.Success)
        assertTrue(report.items[1].outcome is CloneOutcome.NoDual)
        assertTrue(report.items[2].outcome is CloneOutcome.NoDual)
        assertEquals("short-circuit must stop device calls after the first NoDual",
            1, gw.installCalls.size)
        assertEquals(1, report.successCount)
        assertEquals(2, report.failedCount)
        assertEquals(0, report.unknownCount)
    }

    // (d/B2) 併發第二批：慢批次持有 batch:clone 期間，第二批整批拒絕。
    @Test
    fun batch_concurrentSecondBatch_reportsAlreadyInProgress() {
        val backend = MapBackend()
        val gw = FakeRepo()
        gw.installDelayMs = 500L
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.good", "Good", 0, false),
                SourceAppEntry("com.example.other", "Other", 0, false),
            ),
        )
        vm.toggleSelection("0:com.example.good")
        vm.toggleSelection("0:com.example.other")
        vm.cloneSelected()
        vm.cloneSelected()
        // 同步斷言：慢批次仍在 IO 停留，首批完成訊息不可能搶先覆寫。
        assertEquals("already in progress: batch clone", vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        assertEquals(2, vm.state.value.batchReport!!.successCount)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // (d/B2) 批次認領方向：慢批次持有 clone:<pkg> 期間，遲到單筆報 already in progress 且零裝置呼叫。
    @Test
    fun batch_claimsRejectLateSingleClone() {
        val backend = MapBackend()
        val gw = FakeRepo()
        gw.installDelayMs = 500L
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.good", "Good", 0, false),
                SourceAppEntry("com.example.other", "Other", 0, false),
            ),
        )
        vm.toggleSelection("0:com.example.good")
        vm.toggleSelection("0:com.example.other")
        vm.cloneSelected()
        vm.clone("com.example.good")
        // 同步斷言：慢批次仍在 IO 停留，單筆拒絕訊息不可能被覆寫。
        assertEquals("already in progress: com.example.good", vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        assertEquals(2, vm.state.value.batchReport!!.successCount)
        assertEquals("refused single must never reach the device",
            listOf("com.example.good", "com.example.other"), gw.installCalls)
    }

    // (f) loadSelected(-1/0)：回空且不刪檔（未知目標讀取永不破壞已存資料）。
    @Test
    fun loadSelected_nonPositiveTarget_neverDeletesFile() {
        val backend = MapBackend()
        val store = backend.store()
        assertTrue(store.save(SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.a"), 1000L))
        assertEquals(emptySet<String>(), store.loadSelected(-1))
        assertEquals(emptySet<String>(), store.loadSelected(0))
        assertEquals(
            "negative/zero target reads must not delete the file",
            setOf("0:com.example.a"),
            backend.store().loadSelected(95),
        )
    }

    // (h) selectVisible：VM 層為純合併（union），不截斷；200 上限在
    // MainActivity.currentVisibleKeys（UI 層，private，不可 VM 單測）。
    @Test
    fun selectVisible_mergesWithoutTruncation() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        vm.toggleSelection("0:com.example.chat")
        vm.selectVisible(setOf("0:com.example.mail", "0:com.example.chat"))
        assertEquals(
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm.state.value.selectedKeys,
        )
        vm.selectVisible(emptySet())
        assertEquals(2, vm.state.value.selectedKeys.size)
        val many = (0 until 201).map { "0:com.example.app$it" }.toSet()
        vm.selectVisible(many)
        assertTrue(vm.state.value.selectedKeys.containsAll(many))
    }
}
