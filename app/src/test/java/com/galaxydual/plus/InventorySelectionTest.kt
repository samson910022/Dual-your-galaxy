// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-7 P2 (issue #26, 階段 1):個人來源 catalog + 選取持久化 JVM 測試。
// - 搜尋/篩選/排序 (SourceInventory 純函數)。
// - AppKey = sourceSerial:package (同包名不同來源為不同 key；圖示載入狀態
//   永不進入身分鍵)。
// - 選取持久化：保存後新 client 可重讀；重啟恢復只打勾不重送寫入；profile
//   變動失效舊選取；損壞恢復；取消勾選/取消保存 ≠ 卸載 (零 gateway 呼叫)。
// - 批次 (cloneVerifiedExpected 原子 pin 逐項)：逐項結果/部分成功/unknown
//   分流；唯讀拒絕；空選取拒絕；綁定目標不符拒絕；漂移停止＋partial。
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
import kotlinx.coroutines.flow.update
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
        // Phase B #2 測試鉤子：當 installCalls 達到此數後，身份通道整體切到
        // 新 dualId（模擬批次中途 95→96；-1 停用）。users/verbose/dumpsys/
        // prop 四通道同步切（否則 cloneVerified 的 snapshot 檢查會先以 NoDual
        // 擋掉，測不到跨 user 寫入的防線）。
        var switchDualIdAfterInstalls: Int = -1,
        var switchDualIdTo: Int = 96,
        // B2 測試鉤子：install 分支在 IO 執行緒上停留此毫秒數，讓首批慢批次
        // 在持有 inFlight key 時可被併發第二批/單筆觀測到。預設 0（無行為變化）。
        var installDelayMs: Long = 0L,
        // PR-37 P3 Phase A #6 測試鉤子：profilesSnapshot 起始 I/O 拋出
        //（模擬 snapshot 通道異常；true 即 CMD_LIST_USERS 直接拋）。
        var profilesSnapshotThrows: Boolean = false,
        // PR-37 P3 Phase A #4 0/2 drift 測試鉤子：當 verbose 呼叫次數超過此閾值後，
        // 身份通道整體切到 switchDualIdTo（-1 停用）。與 install-based 切換共用
        // switchDualIdTo，四通道同步切。測試在 refresh＋綁定後將閾值設為
        // verboseCalls+1，使批次 pin 仍見舊目標、首項 preflight 即見新目標
        //（0 項完成即 drift，零 install）。
        var resolveSwitchAfterVerbose: Int = -1,
        // PR-37 Phase B #2 serial 綁定測試鉤子：雙 verbose/dumpsys 通道回報的
        // serial（預設 15；改寫即模擬同 id 重建的新 serial，id 不變）。
        var dualSerial: Int = 15,
        // PR-37 Phase C #5 測試鉤子：當 installCalls 達到此數後，verbose/
        // dumpsys 身分通道回 exit!=0（模擬批次中途身分通道暫時無法判定；
        // -1 停用）。typed 解析走 Unknown → core 回 ChannelError（非 NoDual），
        // 批次應計入 unknown、不短路、不宣稱 no dual。
        var failIdentityAfterInstalls: Int = -1,
        // PR-37 Stage 1（gh review #3）負向測試鉤子：僅翻 verbose/dumpsys
        //（owner-only），getprop 與 pm 快照仍指向 dualId（矛盾證據 → typed
        // Unknown → 第二項 ChannelError，不短路）。-1 停用。
        var partialVanishAfterInstalls: Int = -1,
        // PR-37 Phase B #1 原子 pin 測試鉤子：當 CMD_LIST_USERS 呼叫次數超過此
        // 閾值後，身份通道整體切到 switchDualIdTo（-1 停用）。批次 pin
        //（fetchDualTarget：verbose/dumpsys/prop）不經此通道，故 pin 仍見舊
        // 目標、首項 snapshot 即見新目標（零 install，斷言 drift partial）。
        var switchDualIdAfterListUsers: Int = -1,
        // Stage 2 batch SerialUnavailable 測試鉤子：verbose/dumpsys 保留 CLONE
        // 類型但省略 serial 行（id 確認、serial 缺失 → pin 為 SerialUnavailable）。
        var omitSerial: Boolean = false,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        val installCalls = mutableListOf<String>()
        val uninstallCalls = mutableListOf<String>()
        var verboseCalls: Int = 0
        var listUsersCalls: Int = 0

        private fun vanishActive(): Boolean =
            vanishDualAfterInstalls >= 0 && installCalls.size >= vanishDualAfterInstalls

        private fun partialVanishActive(): Boolean =
            partialVanishAfterInstalls >= 0 && installCalls.size >= partialVanishAfterInstalls

        private fun noCloneChannels(): Boolean = vanishActive() || partialVanishActive()

        private fun effectiveDualId(): Int {
            if (switchDualIdAfterListUsers >= 0 && listUsersCalls > switchDualIdAfterListUsers) {
                return switchDualIdTo
            }
            if (switchDualIdAfterInstalls >= 0 && installCalls.size >= switchDualIdAfterInstalls) {
                return switchDualIdTo
            }
            if (resolveSwitchAfterVerbose >= 0 && verboseCalls > resolveSwitchAfterVerbose) {
                return switchDualIdTo
            }
            return dualId
        }

        private fun usersOut(): String {
            // vanish 生效時 pm 快照亦乾淨（owner-only，無 95），與 typed
            // Absent 一致；partial vanish 刻意保留 95（pm 反證 → Unknown）。
            if (vanishActive()) {
                return "Users:\n\tUserInfo{0:Owner:13} running\n"
            }
            val id = effectiveDualId()
            return "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$id:DualApp:400} running\n"
        }

        private fun verboseOut(): String {
            val id = effectiveDualId()
            if (noCloneChannels()) {
                return "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n"
            }
            return "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$id:DualApp:400} running:\n" +
                (if (omitSerial) "" else "    Serial: $dualSerial\n") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"
        }

        private fun dumpsysOut(): String {
            val id = effectiveDualId()
            if (noCloneChannels()) {
                return "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n"
            }
            return "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$id:DualApp:400}" + (if (omitSerial) ":\n" else " serialNo=$dualSerial:\n") +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        }

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS -> {
                if (profilesSnapshotThrows) throw RuntimeException("boom snapshot")
                listUsersCalls += 1
                CmdResult(exit = 0, out = usersOut(), err = "")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                verboseCalls += 1
                if (failIdentityAfterInstalls >= 0 && installCalls.size >= failIdentityAfterInstalls) {
                    CmdResult(exit = 1, out = "", err = "identity down")
                } else {
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                }
            }
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                if (failIdentityAfterInstalls >= 0 && installCalls.size >= failIdentityAfterInstalls) {
                    CmdResult(exit = 1, out = "", err = "identity down")
                } else {
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                }
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                // vanish 生效時 property 無聲明（空字串，typed Absent 前提）；
                // partial vanish 刻意仍指向 dualId（property 反證 → Unknown）。
                if (vanishActive()) CmdResult(exit = 0, out = "", err = "")
                else CmdResult(exit = 0, out = "${effectiveDualId()}\n", err = "")
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
    // R4 #2 hold/release：blockLoad=true 時首個 loadRaw 在 IO 執行緒上阻塞
    //（loadEntered 倒數→測試執行緒手動操作→loadRelease 放行），精確模擬
    // restore I/O 懸掛途中使用者仍可操作（toggle 不持 mutex，可交錯；mutex
    // 持有者 restore 的提交守衛必須放棄）。預設 false，既有測試行為不變。
    private class MapBackend {
        val map = mutableMapOf<String, String?>()
        @Volatile var blockLoad: Boolean = false
        val loadEntered = java.util.concurrent.CountDownLatch(1)
        val loadRelease = java.util.concurrent.CountDownLatch(1)
        fun store(): SelectionStore = SelectionStore(
            loadRaw = {
                if (blockLoad) {
                    loadEntered.countDown()
                    loadRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    map[SELECTION_KEY]
                } else {
                    map[SELECTION_KEY]
                }
            },
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
            nowWallMs = { clock.now() },
            selectionStore = store,
            blockText = zhTestBlockText(),
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
        // Phase B #1：恢復需已驗證目標，先 refresh 驗證 95（refresh 自動補觸恢復；
        // 顯式 restore 為冪等重試）。
        val vm2 = makeVm(gw, clock, backend.store())
        await { vm2.state.value.channelReady }
        vm2.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat App", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
                SourceAppEntry("com.example.extra", "Extra", 0, false),
            ),
        )
        vm2.refresh()
        await { "0:com.example.chat" in vm2.state.value.selectedKeys }
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
        // setSourceCatalog 的自動補恢復），restore 必須是 no-op（未驗證亦拒絕，
        // 已驗證亦不覆蓋非空勾選）。
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
        vm3.refresh()
        await { !vm3.state.value.loading && vm3.state.value.inventory.targetId == 95 }
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
        // Phase B #1：未驗證 inventory 下亦拒絕（不燒 marker），故此處仍為空。
        vm.restoreSelection(95)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        // 後載非空 catalog：Phase B #1 要求先驗證（refresh）再恢復；
        // setSourceCatalog 在未驗證時不再用 snapshot 回退，需 refresh 補觸。
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { "0:com.example.chat" in vm.state.value.selectedKeys }
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
    // PR-37 P3 Phase A #1：批次要求已綁定選取，故先 refresh 驗證 95 再勾選綁定。
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
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
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
    // PR-37 P3 Phase A #1：先 refresh 綁定（failList 下 refresh 得 UNKNOWN target 95，
    // 仍可綁定；綁定只看 targetId>0，不看新鮮度）。
    @Test
    fun batch_verifyUnknown_reportsUnknown() {
        val backend = MapBackend()
        val gw = FakeRepo(failListPackages = true)
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.myst", "Myst", 0, false)))
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
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
        // M1/M2: unknown-only 批次 phase UNKNOWN + VERIFY_UNKNOWN diagnose。
        // Phase B #3：VerifyUnknown 可能=已寫入未驗證（install 成功但重查通道
        // 失敗），故 unknown 亦標 STALE。此例先 refresh 綁定（failList 下 refresh 得
        // UNKNOWN target 95），批次後 UNKNOWN→STALE（舊資料不謊稱新鮮）。
        val lastU = vm.state.value.lastOperation
        assertNotNull(lastU)
        assertEquals("batch:clone", lastU!!.opId)
        assertEquals(OpPhase.UNKNOWN, lastU.phase)
        assertEquals("批次 1 項：成功 0/失敗 0/未知 1", lastU.detail)
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
        assertEquals(
            "unknown may mean written-but-unverified, must not stay FRESH",
            InventoryFreshness.STALE,
            vm.state.value.inventory.freshness,
        )
    }

    // M1 全成功清 diagnose： failed==0 && unknown==0 則 diagnose=null，
    // 批次級 LastOperation phase SUCCESS，並標 STALE。
    // PR-37 P3 Phase A #1：先 refresh 驗證再勾選綁定。
    @Test
    fun batch_allSuccess_clearsDiagnoseAndMarksStale() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
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
    // PR-37 P3 Phase A #1：先 refresh 綁定再勾選。
    @Test
    fun batch_missingCatalogKey_usesDisplayFallback() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
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
    // PR-37 P3 Phase A #1：先 refresh 綁定再勾選。
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
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
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
    // PR-37 P3 Phase A #1：先 refresh 綁定再勾選（缺失 key 仍可綁定執行，記帳 failed）。
    @Test
    fun batch_missingKey_recordsInvalidPackageWithoutDeviceCall() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
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

    // (c/M13) 首個 NoDual 後短路：首項寫後目標消失記 VerifyUnknown（Stage3
    // unknown-after-send 語義，2c24078；非 Success），剩餘項首見 NoDual 即短路，
    // 同記 NoDual，不再打裝置。
    // PR-37 P3 Phase A #1：先 refresh 綁定（vanish 在首個 install 後才生效，
    // refresh 時仍見 95，可正常綁定）。
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
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.a3")
        vm.toggleSelection("0:com.example.a2")
        vm.toggleSelection("0:com.example.a1")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals(3, report.items.size)
        assertTrue(report.items[0].outcome is CloneOutcome.VerifyUnknown)
        assertTrue(report.items[1].outcome is CloneOutcome.NoDual)
        assertTrue(report.items[2].outcome is CloneOutcome.NoDual)
        assertEquals("short-circuit must stop device calls after the first NoDual",
            1, gw.installCalls.size)
        assertEquals(0, report.successCount)
        assertEquals(2, report.failedCount)
        assertEquals(1, report.unknownCount)
    }

    // PR-37 Stage 1 (gh review #3)：部分消失——僅翻 verbose/dumpsys
    //（owner-only），getprop/pm 仍指向 95（矛盾證據）。Stage3 unknown-after-send
    // 語義下首項寫後身份矛盾 → VerifyUnknown（非 Success）；次項起 typed
    // Unknown → ChannelError（unknown、不短路、不宣稱 NoDual）。每項仍嘗試
    // 裝置（無 M13 短路），最終 0 成功＋3 未知。
    @Test
    fun batch_partialVanish_reportsChannelErrorWithoutShortCircuit() {
        val backend = MapBackend()
        val gw = FakeRepo()
        // 首個 install 完成後僅 typed 通道消失（getprop/pm 仍指 95）：矛盾。
        gw.partialVanishAfterInstalls = 1
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
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.a3")
        vm.toggleSelection("0:com.example.a2")
        vm.toggleSelection("0:com.example.a1")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals(3, report.items.size)
        assertTrue(report.items[0].outcome is CloneOutcome.VerifyUnknown)
        assertTrue(
            "partial vanish must be ChannelError (unknown), never NoDual, got: " +
                report.items[1].outcome,
            report.items[1].outcome is CloneOutcome.ChannelError,
        )
        assertTrue(
            "no M13 short-circuit on unknown: third item must also run, got: " +
                report.items[2].outcome,
            report.items[2].outcome is CloneOutcome.ChannelError,
        )
        assertEquals(0, report.successCount)
        assertEquals(0, report.failedCount)
        assertEquals(3, report.unknownCount)
        // Stage4 首個未知項選碼：首個未知為 VerifyUnknown → VERIFY_UNKNOWN。
        assertEquals(OpPhase.UNKNOWN, vm.state.value.lastOperation?.phase)
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
    }

    // (d/B2) 併發第二批：慢批次持有 batch:clone 期間，第二批整批拒絕。
    // PR-37 P3 Phase A #1：先 refresh 綁定再勾選。
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
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
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
    // PR-37 P3 Phase A #1：先 refresh 綁定再勾選。
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
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.good")
        vm.toggleSelection("0:com.example.other")
        vm.cloneSelected()
        vm.clone("com.example.good")
        // 同步斷言：慢批次仍在 IO 停留，單筆拒絕訊息不可能被覆寫。
        // 新 base 口徑：msg 回音用 sanitizeUserEcho（不遮蔽包名）。
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

    // Phase B #1a：未驗證 catalog 到達不恢復；驗證後 refresh 補觸恢復。
    @Test
    fun catalogUnverified_noRestore_refreshRestores() {
        val backend = MapBackend()
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95,
                setOf("0:com.example.chat", "0:com.example.mail"), 1000L,
            ),
        )
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        // 此時 inventory 仍未驗證（probe 不寫 inventory，需 refresh 才驗證）。
        assertTrue(vm.state.value.inventory.targetId <= 0)
        vm.setSourceCatalog(catalog())
        // Stage5 註解：sleep 300ms 為非同步 no-op 證明——restore 經 viewModelScope＋
        // opMutex 非同步執行，sleep 確保若有 stray 恢復早已完成；仍空即真 no-op，
        // 非「尚未執行」。改 latch 需重構自動補觸路徑，風險大，僅加註解 (最小改動)。
        Thread.sleep(300)
        assertTrue(
            "unverified catalog must not restore via snapshot fallback",
            vm.state.value.selectedKeys.isEmpty(),
        )
        vm.refresh()
        await { "0:com.example.chat" in vm.state.value.selectedKeys }
        assertEquals(
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm.state.value.selectedKeys,
        )
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // Phase B #1b：95→96，refresh 清空舊勾選＋可恢復新目標存檔。
    @Test
    fun targetChange_clearsOldSelectionAndRestoresNew() {
        val backend = MapBackend()
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.chat"), 1000L,
            ),
        )
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { "0:com.example.chat" in vm.state.value.selectedKeys }
        // 新目標存檔（覆寫舊檔，模擬 96 的保存）＋裝置切到 96。
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 96, setOf("0:com.example.mail"), 2000L,
            ),
        )
        gw.dualId = 96
        vm.refresh()
        await {
            !vm.state.value.loading &&
                "0:com.example.mail" in vm.state.value.selectedKeys
        }
        assertEquals(
            "old target selection must not survive",
            setOf("0:com.example.mail"),
            vm.state.value.selectedKeys,
        )
        assertTrue(vm.state.value.msg.contains("目標已變更"))
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // Phase B #1c：無 dual（未知）時保存/恢復拒絕，已驗證後正常。
    @Test
    fun noDualThenDual_unknownRefuses_verifiedWorks() {
        val backend = MapBackend()
        // 預存 95 快照：未驗證時 restoreSelection(95) 必須是 no-op（舊碼會恢復）。
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.chat"), 1000L,
            ),
        )
        val gw = FakeRepo()
        gw.vanishDualAfterInstalls = 0
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId <= 0 }
        // inventory 未驗證時即使傳入已知目標亦不恢復（待驗證後由 refresh 補觸）。
        // Stage5 註解：同上 sleep 為非同步 no-op 證明 (restore 已在 opMutex 後，
        // sleep 確保完成；仍空即真 no-op)。
        vm.restoreSelection(95)
        Thread.sleep(200)
        assertTrue(
            "unverified inventory must not restore even for a known target",
            vm.state.value.selectedKeys.isEmpty(),
        )
        // 未知目標讀取永不破壞已存資料；顯式未知 target 恢復是 no-op。
        // Stage5 註解：同上 sleep 為非同步 no-op 證明。
        vm.restoreSelection(-1)
        Thread.sleep(200)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        // 回到空倉：後續 persist(0)/assertNull 仍驗空倉語義；亦避免 refresh
        // 自動恢復預存快照干擾尾段驗證路徑（toggle 會反選已恢復的勾）。
        assertTrue(backend.store().clear())
        // 前置守衛：targetId<=0 直接拒絕（非未知語義拒絕）。
        vm.persistSelection(0)
        await { vm.state.value.msg.isNotEmpty() }
        assertNull(
            "unknown save must not create a snapshot",
            backend.store().loadSnapshot(),
        )
        // 分身出現：恢復驗證路徑。
        gw.vanishDualAfterInstalls = -1
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.chat")
        vm.persistSelection(95)
        await { vm.state.value.msg.startsWith("已保存選取") }
        // Phase B #2：VM 持久化寫入 v2 快照（id＋serial），id-only 讀取不再
        // 匹配（拒絕靜默降級），須攜帶當下 serial 才有效。
        assertEquals(setOf("0:com.example.chat"), backend.store().loadSelected(95, 15))
    }

    // Phase B #2：批次中途 95→96，首項寫後漂移記 VerifyUnknown（Stage3
    // unknown-after-send 語義；非 Success）即停，第二項零裝置寫入。
    // PR-37 P3 Phase A #4：drift 無條件 STALE＋視為目標變更（inventory 更新為新目標
    // STALE，清空選取＋重置 marker，與 handle 一致；partial 保留，msgKind 維持 FAILED）。
    @Test
    fun batch_targetDrift_stopsWithPartial() {
        val backend = MapBackend()
        val gw = FakeRepo()
        gw.switchDualIdAfterInstalls = 1
        gw.switchDualIdTo = 96
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.a1", "A1", 0, false),
                SourceAppEntry("com.example.a2", "A2", 0, false),
            ),
        )
        vm.toggleSelection("0:com.example.a1")
        vm.toggleSelection("0:com.example.a2")
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals(
            "drift must stop before the second item: no fake outcome",
            1, report.items.size,
        )
        assertTrue(report.items.single().outcome is CloneOutcome.VerifyUnknown)
        assertEquals(0, report.successCount)
        assertEquals(1, report.unknownCount)
        assertEquals(
            "second item must never reach the device",
            listOf("com.example.a1"), gw.installCalls,
        )
        val last = vm.state.value.lastOperation
        assertNotNull(last)
        assertEquals("batch:clone", last!!.opId)
        assertEquals(OpPhase.FAILED, last.phase)
        assertTrue("detail must name the target change: ${last.detail}", last.detail.contains("目標已變更"))
        assertTrue("msg must name the target change: ${vm.state.value.msg}", vm.state.value.msg.contains("目標已變更"))
        assertTrue("msg must show progress X/Y: ${vm.state.value.msg}", vm.state.value.msg.contains("已完成 1/共 2"))
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        // #4：無條件 STALE＋新目標＋已清空選取（與 handle 同口徑）。
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
        assertEquals(96, vm.state.value.inventory.targetId)
        assertTrue("drift must clear selection", vm.state.value.selectedKeys.isEmpty())
        assertTrue(vm.state.value.msg.contains("已清空舊勾選"))
    }

    // Phase B #3：FRESH→unknown，全 unknown 批次後 freshness 非 FRESH。
    @Test
    fun freshThenUnknown_marksStale() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.myst", "Myst", 0, false)))
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        gw.failListPackages = true
        vm.toggleSelection("0:com.example.myst")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals(1, report.unknownCount)
        assertTrue(
            "VerifyUnknown may mean written-but-unverified, must mark STALE",
            vm.state.value.inventory.freshness != InventoryFreshness.FRESH,
        )
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
    }

    // PR-37 P3 Phase A #1：已綁定 95 的勾選，live 已切 96，批次整批拒絕。
    // 鎖內 pin 96 ≠ bound 95 → 清空選取＋batchReport、重置 marker、INVALID 明示、
    // loading=false、零裝置寫入。禁止自動重新領養舊選取。
    @Test
    fun batch_staleBound_rejectsWholeBatch() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.a1", "A1", 0, false),
                SourceAppEntry("com.example.a2", "A2", 0, false),
            ),
        )
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.a1")
        vm.toggleSelection("0:com.example.a2")
        assertEquals(2, vm.state.value.selectedKeys.size)
        val queryBefore = vm.state.value.inventory.queryTimeMs
        assertTrue("refresh must stamp queryTime", queryBefore > 0)
        // live 已切到 96＋新 serial，不經 refresh（模擬 95 勾選殘留到 96 前的空窗）。
        gw.dualId = 96
        gw.dualSerial = 41
        vm.cloneSelected()
        await {
            !vm.state.value.loading &&
                vm.state.value.msg.contains("選取已綁定舊目標，已清空，請重新勾選確認")
        }
        assertEquals("stale bound must block all device writes", 0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
        assertTrue("stale selection must be cleared", vm.state.value.selectedKeys.isEmpty())
        assertNull("stale batch must clear batchReport", vm.state.value.batchReport)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertFalse(vm.state.value.loading)
        // PR-37 P3 Phase A #3：bound 拒絕亦 STALE——inventory 無條件 STALE
        //（FRESH 不得殘留）＋更新為 live 目標（pin 的 expectedTarget），
        // queryTime 保留供追溯。
        assertTrue(
            "bound rejection must not keep inventory FRESH",
            vm.state.value.inventory.freshness != InventoryFreshness.FRESH,
        )
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
        assertEquals(96, vm.state.value.inventory.targetId)
        assertEquals(41, vm.state.value.inventory.targetSerial)
        assertEquals(
            "bound rejection must keep queryTime for traceability",
            queryBefore, vm.state.value.inventory.queryTimeMs,
        )
    }

    // PR-37 P3 Phase A #1：從未綁定（bound==NONE 哨兵）的勾選亦整批拒絕。
    // 未經 refresh 驗證即批次（inventory UNKNOWN、bound NONE）→ 同上拒絕口徑。
    // 此測試鎖定「未綁定不可批次」新契約；舊批次測試已全數改為先 refresh 綁定。
    @Test
    fun batch_unboundSelection_rejectsWholeBatch() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        assertTrue(vm.state.value.inventory.targetId <= 0)
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.good")
        assertEquals(1, vm.state.value.selectedKeys.size)
        vm.cloneSelected()
        await {
            !vm.state.value.loading &&
                vm.state.value.msg.contains("選取已綁定舊目標，已清空，請重新勾選確認")
        }
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertNull(vm.state.value.batchReport)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertFalse(vm.state.value.loading)
    }

    // PR-37 P3 Phase A #4：0/2 項完成即 drift（起始 pin 95、首項 preflight 即見 96）。
    // 零 install、inventory 無條件 STALE（新目標 96＋已清空選取），partial 0 項、
    // FAILED 口徑正確。
    @Test
    fun batch_targetDrift_zeroCompleted_marksStaleWithNoInstall() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.a1", "A1", 0, false),
                SourceAppEntry("com.example.a2", "A2", 0, false),
            ),
        )
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.a1")
        vm.toggleSelection("0:com.example.a2")
        // pin 仍見 95、首項 preflight 即見 96（resolve 次數閾值，見 FakeRepo 註記）。
        gw.resolveSwitchAfterVerbose = gw.verboseCalls + 1
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals("0 completed drift must keep empty partial", 0, report.items.size)
        assertEquals(0, report.successCount)
        assertEquals(0, report.failedCount)
        assertEquals(0, report.unknownCount)
        assertTrue("zero-completed drift must never reach the device", gw.installCalls.isEmpty())
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
        assertEquals(96, vm.state.value.inventory.targetId)
        assertTrue("drift must clear selection", vm.state.value.selectedKeys.isEmpty())
        val last = vm.state.value.lastOperation
        assertNotNull(last)
        assertEquals("batch:clone", last!!.opId)
        assertEquals(OpPhase.FAILED, last.phase)
        assertTrue(last.detail.contains("目標已變更"))
        assertTrue(last.detail.contains("已完成 0/共 2"))
        assertTrue(vm.state.value.msg.contains("目標已變更"))
        assertTrue(vm.state.value.msg.contains("已完成 0/共 2"))
        assertTrue(vm.state.value.msg.contains("已清空舊勾選"))
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertFalse(vm.state.value.loading)
    }

    // PR-37 P3 Phase A #6：起始 snapshot 拋出 → loading=false＋錯誤 msg＋釋放已認領 keys。
    // 無殘留 inFlight（下一批可正常執行）、msg 非空、零 install。
    @Test
    fun batch_startSnapshotThrows_recoversCleanly() {
        val backend = MapBackend()
        val gw = FakeRepo()
        gw.vanishDualAfterInstalls = 0
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        // 起始 I/O 拋出只針對批次階段：probe 完成後才打開，避免探測期同步被炸
        //（Fake 與探測共用同一 CMD_LIST_USERS 通道）。
        gw.profilesSnapshotThrows = true
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.good")
        vm.cloneSelected()
        await { !vm.state.value.loading && vm.state.value.msg.isNotEmpty() }
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.msg.isNotEmpty())
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertTrue("snapshot throw must never reach install", gw.installCalls.isEmpty())
        assertNull("start failure must not produce a batch report", vm.state.value.batchReport)
        // 選取保留（起始失敗不清勾選），已認領 keys 必須釋放：下一批可正常執行。
        assertEquals(setOf("0:com.example.good"), vm.state.value.selectedKeys)
        gw.vanishDualAfterInstalls = -1
        gw.profilesSnapshotThrows = false
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        assertEquals(1, vm.state.value.batchReport!!.successCount)
        assertEquals(listOf("com.example.good"), gw.installCalls)
    }

    // PR-37 Phase B #2 serial 綁定（store 層）：v2 快照（serial 行）須
    // (id,serial) 相符才有效；legacy 快照（無 serial 行）僅比 id（恢復綁定
    // id-only，不靜默提升為完全體）；不符即清除陳舊條目。
    @Test
    fun store_serialBinding_v2RequiresSerialMatch_legacyIdOnly() {
        val backend = MapBackend()
        // legacy 行（無 serial 行）：id-only 有效，serial 參數不影響。
        assertTrue(
            backend.store().save(SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.a"), 1000L),
        )
        assertEquals(setOf("0:com.example.a"), backend.store().loadSelected(95))
        assertEquals(
            "legacy snapshot stays id-only even when a serial is offered",
            setOf("0:com.example.a"), backend.store().loadSelected(95, 999),
        )
        assertNull(
            "legacy serial must parse as null",
            backend.store().loadSnapshot()?.knownTargetSerial,
        )
        // v2 行（serial 已知）：必須 (id,serial) 相符。
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.b"), 2000L, 15,
            ),
        )
        val snap = backend.store().loadSnapshot()
        assertEquals(95, snap?.knownTargetId)
        assertEquals(15, snap?.knownTargetSerial)
        assertTrue(
            "serial line must round-trip",
            backend.map[SELECTION_KEY]!!.contains("serial=15"),
        )
        assertEquals(setOf("0:com.example.b"), backend.store().loadSelected(95, 15))
        assertEquals(
            "same id with a fresh serial must not reuse the old selection",
            emptySet<String>(), backend.store().loadSelected(95, 41),
        )
        assertNull("mismatch must clear the stale entry", backend.store().loadSnapshot())
        // id-only 讀取遇到 v2 行：失效但不清除（Stage5 unverifiable-no-clear；
        // 拒絕靜默降級仍回空，僅已驗證不符才自清，見上 41 分支）。
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.c"), 3000L, 15,
            ),
        )
        assertEquals(emptySet<String>(), backend.store().loadSelected(95))
        assertEquals(
            "unverifiable v2 must be preserved for later verified restore",
            95, backend.store().loadSnapshot()?.knownTargetId,
        )
        assertEquals(15, backend.store().loadSnapshot()?.knownTargetSerial)
        // serial 可用後仍可恢復。
        assertEquals(setOf("0:com.example.c"), backend.store().loadSelected(95, 15))
    }

    // PR-37 P3 Phase A #4：serial 行存在但無效（非整數/空/超界）或重複出現
    // ——整筆拒絕（損壞自清），不得降級為 legacy（id-only 有效）。
    // 直接測 SelectionStore parse/load 層：五種形狀皆 parse null、
    // loadSelected 回空、損壞條目自清、之後可正常保存。
    @Test
    fun store_invalidOrDuplicateSerialLine_rejectsWholeEntry() {
        val raws = mapOf(
            "malformed" to
                "v1\nsource=catalog\ntarget=95\nserial=garbage\nat=1000\npkg=0:com.example.a\n",
            "empty" to
                "v1\nsource=catalog\ntarget=95\nserial=\nat=1000\npkg=0:com.example.a\n",
            "overflow" to
                "v1\nsource=catalog\ntarget=95\nserial=9999999999\nat=1000\npkg=0:com.example.a\n",
            "duplicate-same" to
                "v1\nsource=catalog\ntarget=95\nserial=15\nserial=15\nat=1000\npkg=0:com.example.a\n",
            "duplicate-different" to
                "v1\nsource=catalog\ntarget=95\nserial=15\nserial=41\nat=1000\npkg=0:com.example.a\n",
        )
        for ((name, raw) in raws) {
            val backend = MapBackend()
            backend.map[SELECTION_KEY] = raw
            val store = backend.store()
            assertNull("parse must reject $name serial line", store.loadSnapshot())
            assertEquals(
                "loadSelected must fail closed on $name",
                emptySet<String>(), store.loadSelected(95, 15),
            )
            assertNull("$name damage must self-clear", backend.map[SELECTION_KEY])
            // legacy id-only 讀取亦不得復活已損壞條目。
            assertEquals(
                emptySet<String>(), backend.store().loadSelected(95),
            )
            // 自清後可正常保存，store 恢復可用。
            assertTrue(store.save(SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.a"), 1L))
            assertEquals(setOf("0:com.example.a"), backend.store().loadSelected(95))
        }
    }

    // PR-37 Phase B #1 原子 pin：批次 pin（fetchDualTarget，不經
    // CMD_LIST_USERS）見 95，首項 snapshot 即見 96——core 在同一流程內
    // snapshot＋resolve＋比對並拒絕（TargetMismatch），零 `--user 96`
    // install，partial/drift 口徑與舊 VM preflight 一致。
    @Test
    fun batch_pinnedClone_snapshotSwitch_zeroInstall() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.a1", "A1", 0, false),
                SourceAppEntry("com.example.a2", "A2", 0, false),
            ),
        )
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.a1")
        vm.toggleSelection("0:com.example.a2")
        // pin（fetchDualTarget）不經 CMD_LIST_USERS 故仍見 95；首項 snapshot
        // 即見 96（批次起始走 Confirmed 快捷路徑，不另跑鑑別 snapshot）。
        gw.switchDualIdAfterListUsers = gw.listUsersCalls
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals("drift must stop before the first item: no fake outcome", 0, report.items.size)
        assertEquals(0, report.successCount)
        assertEquals(0, report.failedCount)
        assertEquals(0, report.unknownCount)
        assertTrue("zero-completed drift must never reach the device", gw.installCalls.isEmpty())
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
        assertEquals(96, vm.state.value.inventory.targetId)
        assertTrue("drift must clear selection", vm.state.value.selectedKeys.isEmpty())
        val last = vm.state.value.lastOperation
        assertNotNull(last)
        assertEquals("batch:clone", last!!.opId)
        assertEquals(OpPhase.FAILED, last.phase)
        assertTrue(last.detail.contains("目標已變更"))
        assertTrue(last.detail.contains("已完成 0/共 2"))
        assertTrue(vm.state.value.msg.contains("目標已變更"))
        assertTrue(vm.state.value.msg.contains("已完成 0/共 2"))
        assertTrue(vm.state.value.msg.contains("已清空舊勾選"))
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertFalse(vm.state.value.loading)
    }

    // PR-37 Phase B #2 serial 綁定：同 id 不同 serial 重建——v2 舊選取不作用
    // 到新 profile（恢復無效＋條目清除），refresh 明示目標變更並清空舊勾選。
    @Test
    fun serialRebuild_v2Selection_doesNotApplyToNewProfile() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        await { vm.state.value.inventory.targetSerial == 15 }
        vm.toggleSelection("0:com.example.chat")
        vm.persistSelection(95)
        await { vm.state.value.msg.startsWith("已保存選取") }
        assertEquals(15, backend.store().loadSnapshot()?.knownTargetSerial)
        // 同 id 重建：serial 15 → 41（id 不變）。
        gw.dualSerial = 41
        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.inventory.targetId == 95 &&
                vm.state.value.inventory.targetSerial == 41
        }
        // 舊勾選已清空（bound (95,15) ≠ (95,41)），舊持久選取已失效清除。
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertTrue(vm.state.value.msg.contains("目標已變更"))
        // 舊選取不作用到新 profile：顯式恢復亦無效。
        // Stage5 註解：sleep 為非同步 no-op 證明 (同上)；此處為已驗證不符，
        // 故自清為預期 (與 unverifiable-no-clear 對照，見 store_unverifiable 測試)。
        vm.restoreSelection(95)
        Thread.sleep(300)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertNull(
            "stale v2 entry must be cleared on serial mismatch",
            backend.store().loadSnapshot(),
        )
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // PR-37 Phase B #2 遷移 (a)：legacy 快照（無 serial 行）恢復後綁定 id-only，
    // 批次拒絕靜默提升為完全體（整批拒絕、零 install），重新勾選即綁完全體。
    @Test
    fun serialRebuild_legacySelection_bindsIdOnly_batchRefuses() {
        val backend = MapBackend()
        // 舊版存檔：無 serial 行。
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.chat"), 1000L,
            ),
        )
        assertNull(backend.store().loadSnapshot()?.knownTargetSerial)
        val gw = FakeRepo()
        gw.dualSerial = 41
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { "0:com.example.chat" in vm.state.value.selectedKeys }
        // legacy 恢復：勾選回來了（id-only 容忍），但批次必須拒絕（bound
        // id-only ≠ live 完全體），零 install。
        vm.cloneSelected()
        await {
            !vm.state.value.loading &&
                vm.state.value.msg.contains("選取已綁定舊目標，已清空，請重新勾選確認")
        }
        assertEquals(
            "id-only bound must never silently upgrade to a full batch",
            0, gw.installCalls.size,
        )
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertNull(vm.state.value.batchReport)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        // 重新勾選即綁完全體，批次可執行。
        vm.toggleSelection("0:com.example.chat")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        assertEquals(1, vm.state.value.batchReport!!.successCount)
        assertEquals(listOf("com.example.chat"), gw.installCalls)
    }

    // PR-37 P3 Phase A #4（VM 層）：損壞 v2 存檔（serial 行無效）restore
    // 不恢復——parse 整筆拒絕走損壞自清（刪檔回空），舊勾選不復活，
    // 零裝置寫入。
    @Test
    fun restore_corruptSerialLine_restoresNothingAndSelfClears() {
        val backend = MapBackend()
        backend.map[SELECTION_KEY] =
            "v1\nsource=catalog\ntarget=95\nserial=garbage\nat=1000\npkg=0:com.example.chat\n"
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        // refresh 內補觸恢復已因損壞回空；顯式 restore 亦必須無效。
        // Stage5 註解：sleep 為非同步 no-op 證明 (同上)；損壞自清為預期。
        vm.restoreSelection(95)
        Thread.sleep(300)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertNull(
            "corrupt v2 entry must self-clear on restore",
            backend.store().loadSnapshot(),
        )
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // PR-37 Phase C (review R4 #5)：批次中途身分通道暫時無法判定（首項寫入後
    // verbose/dumpsys exit!=0）→ 首項 VerifyUnknown（Stage3 unknown-after-send
    // 語義；非 Success），次項 typed Unknown → ChannelError（非 NoDual），零寫入、
    // 不觸發 M13 短路；彙總計入 unknown（非 failed），STALE＋VERIFY_UNKNOWN
    //（Stage4：純未知批次按首個未知項選碼，首個未知為 VerifyUnknown），
    // msg/diagnose 永不宣稱 no dual。
    @Test
    fun batch_identityUnknown_countsUnknownWithoutShortCircuit() {
        val backend = MapBackend()
        val gw = FakeRepo()
        // 首個 install 完成後身分通道失效：首項成功，次項 Unknown。
        gw.failIdentityAfterInstalls = 1
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.a1", "A1", 0, false),
                SourceAppEntry("com.example.a2", "A2", 0, false),
            ),
        )
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        vm.toggleSelection("0:com.example.a1")
        vm.toggleSelection("0:com.example.a2")
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        val report = vm.state.value.batchReport!!
        assertEquals(2, report.items.size)
        assertTrue(report.items[0].outcome is CloneOutcome.VerifyUnknown)
        assertTrue(
            "identity Unknown must surface as ChannelError, got: ${report.items[1].outcome}",
            report.items[1].outcome is CloneOutcome.ChannelError,
        )
        assertTrue(
            "identity Unknown must never be reported as NoDual",
            report.items[1].outcome !is CloneOutcome.NoDual,
        )
        assertEquals(
            "unknown item must never reach the device",
            listOf("com.example.a1"), gw.installCalls,
        )
        assertEquals(0, report.successCount)
        assertEquals(0, report.failedCount)
        assertEquals(2, report.unknownCount)
        assertFalse(vm.state.value.cloned.contains("com.example.a1"))
        assertFalse(vm.state.value.cloned.contains("com.example.a2"))
        val last = vm.state.value.lastOperation
        assertNotNull(last)
        assertEquals("batch:clone", last!!.opId)
        assertEquals(OpPhase.UNKNOWN, last.phase)
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, vm.state.value.diagnose?.code)
        assertTrue(
            "msg must never claim no dual: ${vm.state.value.msg}",
            !vm.state.value.msg.contains("no dual", ignoreCase = true),
        )
        assertTrue(vm.state.value.msg.contains("成功 0 / 失敗 0 / 未知 2"))
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
    }

    // PR-37 Phase B R4 #2：restore lost-update——hold 住 restore I/O 時手動勾選
    // B，放棄恢復（仍為 B），批次只裝 B 絕不裝 A；放棄不燒 marker（清空後重試
    // 可恢復 A）。hold 經 MapBackend loadRaw 阻塞 latch（IO 執行緒），toggle
    // 不持 mutex 故可交錯；batch 經 opMutex 排在 restore 之後，天然證明 restore
    // 已完成（deterministic，無 sleep 競態）。
    @Test
    fun restore_lostUpdate_manualSelectionWinsAndBatchOnlyB() {
        val backend = MapBackend()
        val chatA = "0:com.example.chat"
        val mailB = "0:com.example.mail"
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
            ),
        )
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        // refresh 已驗證後再存檔，避免 refresh 自動補觸提前恢復 A。
        assertTrue(
            backend.store().save(SELECTION_SOURCE_CATALOG, 95, setOf(chatA), 2000L, 15),
        )
        backend.blockLoad = true
        vm.restoreSelection(95)
        assertTrue(
            "restore must reach blocking I/O",
            backend.loadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
        vm.toggleSelection(mailB)
        assertEquals(setOf(mailB), vm.state.value.selectedKeys)
        backend.loadRelease.countDown()
        // batch 排在 restore 之後：restore 放棄後 batch 才執行。
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        assertEquals(
            "manual B must survive lost-update restore",
            setOf(mailB), vm.state.value.selectedKeys,
        )
        assertEquals(listOf("com.example.mail"), gw.installCalls)
        assertFalse(
            "stale A must never reach the device",
            gw.installCalls.contains("com.example.chat"),
        )
        assertEquals(1, vm.state.value.batchReport!!.successCount)
        // 不燒 marker：清空後重試可恢復 A。
        backend.blockLoad = false
        vm.clearSelectionLocal()
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        vm.restoreSelection(95)
        await { chatA in vm.state.value.selectedKeys }
        assertEquals(setOf(chatA), vm.state.value.selectedKeys)
    }

    // R4 #2 ABA：hold 途中「選 B→清除→空」回到空，isEmpty 無法區分「一直空」，
    // 必須以 selectionGen 放棄（仍空，不寫 A）；不燒 marker，故重試可恢復 A。
    @Test
    fun restore_aba_selectThenClear_abortsAndMarkerNotBurned() {
        val backend = MapBackend()
        val chatA = "0:com.example.chat"
        val mailB = "0:com.example.mail"
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
            ),
        )
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        assertTrue(
            backend.store().save(SELECTION_SOURCE_CATALOG, 95, setOf(chatA), 2000L, 15),
        )
        backend.blockLoad = true
        vm.restoreSelection(95)
        assertTrue(
            backend.loadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
        vm.toggleSelection(mailB)
        vm.clearSelectionLocal()
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        backend.loadRelease.countDown()
        // persist(-1) 經 opMutex 排在 restore 之後：其完成即 restore 已完成。
        vm.persistSelection(-1)
        await { vm.state.value.msg.contains("no dual profile") }
        assertTrue(
            "ABA empty must not be overwritten by stale restore",
            vm.state.value.selectedKeys.isEmpty(),
        )
        assertEquals(0, gw.installCalls.size)
        backend.blockLoad = false
        vm.restoreSelection(95)
        await { chatA in vm.state.value.selectedKeys }
        assertEquals(setOf(chatA), vm.state.value.selectedKeys)
    }

    // R4 #2 catalog 途中更新：hold 途中 setSourceCatalog 新引用（A 缺失），
    // 提交前引用比對放棄（仍空）；自動補觸亦因交集空不燒 marker；回 V1 重試可恢復。
    @Test
    fun restore_catalogMidUpdate_abortsAndMarkerNotBurned() {
        val backend = MapBackend()
        val chatA = "0:com.example.chat"
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
            ),
        )
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        assertTrue(
            backend.store().save(SELECTION_SOURCE_CATALOG, 95, setOf(chatA), 2000L, 15),
        )
        backend.blockLoad = true
        vm.restoreSelection(95)
        assertTrue(
            backend.loadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
        // 途中 catalog 新引用且 A 缺失：提交必須放棄。
        vm.setSourceCatalog(
            listOf(SourceAppEntry("com.example.mail", "Mail", 0, false)),
        )
        backend.loadRelease.countDown()
        // refresh 排在 hold＋自動補觸之後：完成即前序皆完成。
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.targetId == 95 }
        assertTrue(
            "catalog-changed restore must abort, stale A must not appear",
            vm.state.value.selectedKeys.isEmpty(),
        )
        assertEquals(0, gw.installCalls.size)
        // 不燒 marker：回含 A 的 catalog 重試可恢復。
        backend.blockLoad = false
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
            ),
        )
        vm.restoreSelection(95)
        await { chatA in vm.state.value.selectedKeys }
        assertEquals(setOf(chatA), vm.state.value.selectedKeys)
    }

    // R4 #2 (id,serial) 途中變更：hold 途中 verified 身份 95#15→96#41（直接
    // 寫 StateFlow 模擬 refresh 已遷移，繞過 mutex 排隊以注入真正的 I/O 途中
    // 變更；生產中 refresh 經 mutex 串行，此處僅隔離驗證提交守衛 (c)），提交
    // 必須放棄（仍空）；新目標存檔重試可恢復（marker 未被舊目標燒）。
    @Test
    fun restore_identityMidChange_abortsAndMarkerNotBurned() {
        val backend = MapBackend()
        val chatA = "0:com.example.chat"
        val mailB = "0:com.example.mail"
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(
            listOf(
                SourceAppEntry("com.example.chat", "Chat", 0, false),
                SourceAppEntry("com.example.mail", "Mail", 0, false),
            ),
        )
        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.inventory.targetId == 95 &&
                vm.state.value.inventory.targetSerial == 15
        }
        assertTrue(
            backend.store().save(SELECTION_SOURCE_CATALOG, 95, setOf(chatA), 2000L, 15),
        )
        backend.blockLoad = true
        vm.restoreSelection(95)
        assertTrue(
            backend.loadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
        // 途中身份遷移：生產為 refresh 持 mutex 寫入，此處為測試確定性直接寫
        // StateFlow（restore 持 mutex 期間其他 mutex 寫者本就排隊，直接寫即
        // 模擬「I/O 懸掛途中已遷移」的最新 StateFlow）。
        val f = vm.javaClass.getDeclaredField("_state")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sf = f.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<UiState>
        sf.update {
            it.copy(inventory = it.inventory.copy(targetId = 96, targetSerial = 41))
        }
        backend.loadRelease.countDown()
        vm.persistSelection(-1)
        await { vm.state.value.msg.contains("no dual profile") }
        assertTrue(
            "identity-changed restore must abort",
            vm.state.value.selectedKeys.isEmpty(),
        )
        assertEquals(0, gw.installCalls.size)
        // 不燒 marker：新目標存檔可恢復。
        backend.blockLoad = false
        assertTrue(
            backend.store().save(SELECTION_SOURCE_CATALOG, 96, setOf(mailB), 3000L, 41),
        )
        vm.restoreSelection(96)
        await { mailB in vm.state.value.selectedKeys }
        assertEquals(setOf(mailB), vm.state.value.selectedKeys)
    }

    // Stage2 身份診斷一致性（批次起始 null target 分支）：verbose 通道
    // exit!=0（不可判定）但 pm 快照正常有戶 → 必須報 CHANNEL unknown、
    // 零 install、msg 不含 "no dual"（typed Absent＋快照可用才可 NO_DUAL）。
    // 舊碼僅看快照有戶即 NO_DUAL，此測試鎖定新鑑別。
    @Test
    fun batch_startIdentityUnknown_reportsChannelWithoutInstall() {
        val backend = MapBackend()
        val gw = FakeRepo()
        // 起始即身分通道失敗（verbose/dumpsys exit!=0），pm 快照仍正常。
        gw.failIdentityAfterInstalls = 0
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.good")
        vm.cloneSelected()
        await { !vm.state.value.loading && vm.state.value.msg.isNotEmpty() }
        val st = vm.state.value
        assertEquals(MsgKind.CHANNEL, st.msgKind)
        assertEquals(DiagnoseCode.CHANNEL_ERROR, st.diagnose?.code)
        assertTrue(
            "channel-unknown must never claim no dual, got msg=${st.msg} detail=${st.diagnose?.techDetail}",
            !st.msg.contains("no dual", ignoreCase = true) &&
                !(st.diagnose?.techDetail ?: "").contains("no dual", ignoreCase = true),
        )
        assertTrue(
            "unconfirmable identity must never reach install, got: ${gw.installCalls}",
            gw.installCalls.isEmpty(),
        )
        assertNull("start failure must not produce a batch report", st.batchReport)
        // STALE 語意：FRESH 不得殘留（UNKNOWN 起點保持非 FRESH）。
        assertTrue(
            "identity-unknown start must not leave inventory FRESH",
            st.inventory.freshness != InventoryFreshness.FRESH,
        )
    }

    // Stage 2 同區一致性：批次起始 SerialUnavailable（id 確認但 serial 缺失）
    // 與 request 同情境一致走 VERIFY_MISMATCH + INVALID（非 CHANNEL），零
    // install、FRESH 降 STALE、不產 batchReport。
    @Test
    fun batch_startSerialUnavailable_mapsVerifyMismatch() {
        val backend = MapBackend()
        val gw = FakeRepo(omitSerial = true)
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.toggleSelection("0:com.example.good")
        vm.cloneSelected()
        await { !vm.state.value.loading && vm.state.value.msg.isNotEmpty() }
        val st = vm.state.value
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, st.diagnose?.code)
        assertEquals(MsgKind.INVALID, st.msgKind)
        assertTrue(
            "techDetail must carry the serial prefix, got: ${st.diagnose?.techDetail}",
            (st.diagnose?.techDetail ?: "").startsWith("serial unavailable"),
        )
        assertTrue(
            "serial-unavailable must never reach install, got: ${gw.installCalls}",
            gw.installCalls.isEmpty(),
        )
        assertNull("serial-unavailable start must not produce a batch report", st.batchReport)
        assertTrue(
            "serial-unavailable start must not leave inventory FRESH",
            st.inventory.freshness != InventoryFreshness.FRESH,
        )
    }

    // Stage5 (SerialUnavailable 誤刪修正)：v2＋currentSerial==null 僅回空、
    // 不清除 (unverifiable vs verified-mismatch 區分)。已驗證不符仍自清
    // (見 serialRebuild 對照測試)。
    @Test
    fun store_unverifiableV2_returnsEmptyWithoutClearing() {
        val backend = MapBackend()
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.a"), 1000L, 15,
            ),
        )
        // 不可驗證：回空但保留存檔，待 serial 可用後仍可恢復。
        assertEquals(emptySet<String>(), backend.store().loadSelected(95, null))
        assertEquals(95, backend.store().loadSnapshot()?.knownTargetId)
        assertEquals(15, backend.store().loadSnapshot()?.knownTargetSerial)
        // serial 可用後仍可恢復 (未被誤刪)。
        assertEquals(setOf("0:com.example.a"), backend.store().loadSelected(95, 15))
        // 已驗證不符 (95#15 vs 41)：回空＋自清。
        assertEquals(emptySet<String>(), backend.store().loadSelected(95, 41))
        assertNull(backend.store().loadSnapshot())
    }

    // Stage5：serial<=0 (0/-1/-5) 視損壞 (與 target<=0 同級)，整筆拒絕＋自清。
    @Test
    fun store_nonPositiveSerial_rejectsWholeEntry() {
        val raws = mapOf(
            "zero" to
                "v1\nsource=catalog\ntarget=95\nserial=0\nat=1000\npkg=0:com.example.a\n",
            "negative-one" to
                "v1\nsource=catalog\ntarget=95\nserial=-1\nat=1000\npkg=0:com.example.a\n",
            "negative-five" to
                "v1\nsource=catalog\ntarget=95\nserial=-5\nat=1000\npkg=0:com.example.a\n",
        )
        for ((name, raw) in raws) {
            val backend = MapBackend()
            backend.map[SELECTION_KEY] = raw
            val store = backend.store()
            assertNull("parse must reject $name serial", store.loadSnapshot())
            assertEquals(
                "loadSelected must fail closed on $name",
                emptySet<String>(), store.loadSelected(95, 15),
            )
            assertNull("$name damage must self-clear", backend.map[SELECTION_KEY])
            assertTrue(store.save(SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.a"), 1L))
            assertEquals(setOf("0:com.example.a"), backend.store().loadSelected(95))
        }
    }

    // Stage5：重複 target 行視損壞 (與 serial 重複同級，舊 last-wins 已廢除)。
    @Test
    fun store_duplicateTargetLine_rejectsWholeEntry() {
        val raws = mapOf(
            "duplicate-same" to
                "v1\nsource=catalog\ntarget=95\ntarget=95\nat=1000\npkg=0:com.example.a\n",
            "duplicate-different" to
                "v1\nsource=catalog\ntarget=95\ntarget=96\nat=1000\npkg=0:com.example.a\n",
        )
        for ((name, raw) in raws) {
            val backend = MapBackend()
            backend.map[SELECTION_KEY] = raw
            val store = backend.store()
            assertNull("parse must reject $name target line", store.loadSnapshot())
            assertEquals(
                emptySet<String>(), store.loadSelected(95),
            )
            assertNull("$name damage must self-clear", backend.map[SELECTION_KEY])
            assertTrue(store.save(SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.a"), 1L))
            assertEquals(setOf("0:com.example.a"), backend.store().loadSelected(95))
        }
    }

    // Stage5 縱深：save 側過濾非法 pkg token (與 parse 同白名單)，不連坐整筆。
    @Test
    fun store_save_filtersIllegalPkgToken() {
        val backend = MapBackend()
        val store = backend.store()
        assertTrue(
            store.save(
                SELECTION_SOURCE_CATALOG, 95,
                setOf("0:com.example.good", "bad pkg with spaces", "evil\nline"),
                1000L, 15,
            ),
        )
        assertEquals(
            setOf("0:com.example.good"),
            backend.store().loadSelected(95, 15),
        )
    }

    // Stage5 外來 id 誤刪修正：存 96-entry、inventory(96,41)、調
    // restoreSelection(95) → 選取空＋96-entry 保留 (VM 層 no-op，不進 I/O)。
    @Test
    fun restore_foreignId_noOpPreservesEffectiveEntry() {
        val backend = MapBackend()
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 96, setOf("0:com.example.mail"), 2000L, 41,
            ),
        )
        val gw = FakeRepo()
        gw.dualId = 96
        gw.dualSerial = 41
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.inventory.targetId == 96 &&
                vm.state.value.inventory.targetSerial == 41
        }
        // refresh 已對 96 自動恢復 (mail 在勾選中)；先清空以構造「選取空」起點，
        // 再以外來 95 請求驗證 no-op：既不寫入選取，也不刪除 96 有效條目。
        await { "0:com.example.mail" in vm.state.value.selectedKeys }
        vm.clearSelectionLocal()
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        // 外來目標請求：no-op (sleep 證明非同步完成後仍空，非尚未執行)。
        vm.restoreSelection(95)
        Thread.sleep(300)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertEquals(
            "foreign-id restore must not delete the effective entry",
            96, backend.store().loadSnapshot()?.knownTargetId,
        )
        assertEquals(
            41, backend.store().loadSnapshot()?.knownTargetSerial,
        )
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    // Stage5 新鮮度閘：僅 FRESH 允許保存；STALE 拒絕並提示重查。
    @Test
    fun persist_staleInventory_refusesWithRequeryHint() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.good", "Good", 0, false)))
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        vm.toggleSelection("0:com.example.good")
        vm.persistSelection(95)
        await { vm.state.value.msg.startsWith("已保存選取") }
        val snapBefore = backend.store().loadSnapshot()
        assertEquals(setOf("0:com.example.good"), snapBefore?.selectedPackages)
        // 批次成功 → 庫存 STALE。
        vm.cloneSelected()
        await { vm.state.value.batchReport != null && !vm.state.value.loading }
        assertEquals(InventoryFreshness.STALE, vm.state.value.inventory.freshness)
        // STALE 下保存必須拒絕，不覆寫快照。
        vm.persistSelection(95)
        await { vm.state.value.msg.contains("已過期") }
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(
            "stale persist must not overwrite the snapshot",
            snapBefore?.selectedPackages, backend.store().loadSnapshot()?.selectedPackages,
        )
    }

    // Stage5 不降級：known.serial==null 時拒絕以 legacy 覆寫既有 v2。
    @Test
    fun persist_unverifiableDoesNotDowngradeV2() {
        val backend = MapBackend()
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.chat"), 1000L, 15,
            ),
        )
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.inventory.targetId == 95 &&
                vm.state.value.inventory.targetSerial == 15
        }
        // 模擬 SerialUnavailable：inventory serial 未知 (FRESH 保留，供追溯)。
        val f = vm.javaClass.getDeclaredField("_state")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sf = f.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<UiState>
        sf.update {
            it.copy(inventory = it.inventory.copy(targetSerial = null))
        }
        vm.toggleSelection("0:com.example.mail")
        vm.persistSelection(95)
        await { vm.state.value.msg.contains("serial 缺失") }
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
        assertEquals(
            "unverifiable persist must not downgrade the v2 entry",
            15, backend.store().loadSnapshot()?.knownTargetSerial,
        )
        assertEquals(
            setOf("0:com.example.chat"),
            backend.store().loadSnapshot()?.selectedPackages,
        )
    }

    // Stage5 (id,serial) 配對參數：新 id 必須原子傳入 serial，不沿用舊值。
    @Test
    fun toStaleOrUnknown_pairing_isAtomic() {
        val fresh = InventorySnapshot(
            source = "cmd",
            targetId = 95,
            targetSerial = 15,
            queryTimeMs = 1000L,
            freshness = InventoryFreshness.FRESH,
            count = 1,
        )
        // 同 id 省略 serial：沿用舊 serial。
        val same = fresh.toStaleOrUnknown(now = 2000L, source = "cmd2", targetId = 95)
        assertEquals(InventoryFreshness.STALE, same.freshness)
        assertEquals(95, same.targetId)
        assertEquals(15, same.targetSerial)
        // 新 id 原子傳 null：舊 serial 永不殘留。
        val drift = fresh.toStaleOrUnknown(
            now = 2000L, source = "cmd2", targetId = 96, targetSerial = null,
        )
        assertEquals(96, drift.targetId)
        assertNull(drift.targetSerial)
        assertEquals(InventoryFreshness.STALE, drift.freshness)
        // UNKNOWN 分支同理配對。
        val unknownBase = InventorySnapshot(
            source = "cmd",
            targetId = 95,
            targetSerial = 15,
            queryTimeMs = 1000L,
            freshness = InventoryFreshness.UNKNOWN,
            count = 2,
        )
        val unknownDrift = unknownBase.toStaleOrUnknown(
            now = 3000L, source = "cmd2", targetId = 96, targetSerial = null,
        )
        assertEquals(InventoryFreshness.UNKNOWN, unknownDrift.freshness)
        assertEquals(96, unknownDrift.targetId)
        assertNull(unknownDrift.targetSerial)
    }
}
