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
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        val installCalls = mutableListOf<String>()
        val uninstallCalls = mutableListOf<String>()

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
            cmd.startsWith("pm install-existing") -> {
                val pkg = cmd.trim().split(" ").last()
                installCalls.add(pkg)
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
    @Test
    fun selection_persistsAcrossClients() {
        val backend = MapBackend()
        val s1 = backend.store()
        assertTrue(s1.save(SELECTION_SOURCE_CATALOG, 95, setOf("com.a", "com.b"), 1000L))
        val s2 = backend.store()
        assertEquals(setOf("com.a", "com.b"), s2.loadSelected(95))
        assertEquals(95, s2.loadSnapshot()?.knownTargetId)
        assertEquals(SELECTION_SOURCE_CATALOG, s2.loadSnapshot()?.source)
    }

    // 重啟恢復：新 ViewModel + 同一 store，恢復只打勾（不重送寫入），
    // 且只恢復仍在 catalog 的包。
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

        // 重啟：新 ViewModel，同一 store，catalog 缺 mail（已卸載）。
        val vm2 = makeVm(gw, clock, backend.store())
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
            "only packages still in the catalog are restored",
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm2.state.value.selectedKeys,
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
                setOf("com.example.chat", "com.example.mail"),
                1000L,
            ),
        )
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        assertTrue(vm.state.value.sourceCatalog.isEmpty())
        // 空 catalog 上恢復：無勾選可恢復，且不得毒化同 target 的後續重試。
        vm.restoreSelection(95)
        Thread.sleep(300)
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

    // 唯讀通道：整批拒絕，零安裝呼叫。
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
        vm.cloneSelected()
        await { vm.state.value.msg.isNotEmpty() && !vm.state.value.loading }
        assertNull("read-only must not produce a batch report", vm.state.value.batchReport)
        assertEquals(0, gw.installCalls.size)
    }
}
