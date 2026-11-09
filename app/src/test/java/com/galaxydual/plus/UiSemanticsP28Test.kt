// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// p28-1 UI 語意階段測試（JVM 可測 seam，不碰 Android runtime）：
// - writeBlockReason：分支覆蓋（資源鍵斷言，不碰 Context）+ 與 VM isWritable
//   實際拒絕同 key（single source，經測試假值解析比對）。
// - packageInputError：空白/超長/字元集/有效輸入（資源鍵斷言）。
// - 資源鎖定：繁中預設 + 英文 values-en 同鍵、Clone/Remove 統一字面、
//   寫入拒絕/輸入驗證/診斷複製鍵雙語齊備（含格式佔位）、兩繁中檔（values/
//   b+zh+Hant）鎖一致（Phase D 起刪除 legacy values-zh-rTW：minSdk 29 下
//   BCP-47 b+zh+Hant 在 API 24+ 永遠勝出，zh-rTW 永不勝出，故只留 b+）；
//   語意色亮/暗對假設背景 #FFFFFF/#121212 的數學檢查皆至少 4.5:1
//   （非實際主題聲稱；Manifest 未宣告 theme，實際 surface 未驗證；#B26A00 已移除）。
//   雙語範圍誠實化：僅上述短句為中英雙語；render*/DiagnoseStrings 狀態行保留
//   繁中（scope-limited，非全量雙語）。
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiSemanticsP28Test {
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 最小 fake：id -u 非 0（無 ROOT）+ list users 失敗（無 Shizuku），其餘空成功。
    private class FakeGateway(
        var idUOut: String = "2000",
        var failListUsers: Boolean = true,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        override suspend fun run(cmd: String): CmdResult = when {
            cmd == "id -u" -> CmdResult(0, idUOut, "")
            cmd == DualRepository.CMD_LIST_USERS ->
                if (failListUsers) CmdResult(1, "", "no binder") else CmdResult(0, "", "")
            else -> CmdResult(0, "", "")
        }
    }

    private fun pumpUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (cond()) return
            if (System.currentTimeMillis() >= deadline) {
                testDispatcher.scheduler.advanceUntilIdle()
                if (cond()) return
                throw AssertionError("timed out waiting for condition")
            }
            Thread.sleep(10)
        }
    }

    private fun readOnlyVm(fake: FakeGateway): DualViewModel = DualViewModel(
        repo = DualRepository(fake),
        gatewayPrivilege = Privilege.NONE,
        elevators = listOf(SuElevator(fake), ShizukuElevator(fake)),
        preference = ChannelPreference.ROOT,
        suGateway = NoOpGateway(),
        shizukuGateway = NoOpGateway(),
        activeGateway = NoOpGateway(),
        currentChannel = ResolvedChannel.NOOP,
        hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
        hookDiagnosticGateways = { emptyList() },
        blockText = zhTestBlockText(),
    )

    // writeBlockReason 分支：預設（未就緒 NoOp 回退）——回傳資源鍵。
    @Test
    fun writeBlockReason_defaultState_reportsNoOpFallback() {
        val reason = writeBlockReason(UiState())
        val keyed = reason as BlockReason.Keyed
        assertEquals(WriteBlockKind.NOOP_FALLBACK, keyed.block.kind)
        assertTrue("NOOP fallback takes no args, got: ${keyed.block.args}", keyed.block.args.isEmpty())
        assertEquals(R.string.write_block_noop, keyed.block.stringRes())
    }

    // writeBlockReason 分支：明選模式探測未通過——鍵 + 模式/身份參數。
    @Test
    fun writeBlockReason_explicitFailure_namesMode() {
        val s = UiState(
            channel = ChannelPreference.ROOT,
            resolvedGateway = ResolvedChannel.ROOT,
            execIdentity = Privilege.ROOT,
            writeReady = false,
        )
        val reason = writeBlockReason(s) as BlockReason.Keyed
        assertEquals(WriteBlockKind.EXPLICIT_FAILURE, reason.block.kind)
        assertEquals(listOf("ROOT", "ROOT"), reason.block.args)
        assertEquals(R.string.write_block_explicit, reason.block.stringRes())
    }

    // writeBlockReason 分支：AUTO 降級 typed Keyed（P2-3，經資源解析）。
    @Test
    fun writeBlockReason_autoDegrade_returnsDegradeKeyed() {
        val degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU)
        val s = UiState(
            channel = ChannelPreference.AUTO,
            resolvedGateway = ResolvedChannel.SHIZUKU,
            execIdentity = Privilege.SHELL,
            writeReady = false,
            degrade = degrade,
        )
        val reason = writeBlockReason(s) as BlockReason.Keyed
        assertEquals(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU, reason.block.kind)
        assertEquals(R.string.auto_degrade_su_to_shizuku, reason.block.stringRes())
    }

    // writeBlockReason 分支：LSPOSED 佔位——鍵 + Hook 證據參數。
    @Test
    fun writeBlockReason_lsposedPlaceholder_refusesWithHookState() {
        val s = UiState(
            channel = ChannelPreference.LSPOSED,
            resolvedGateway = ResolvedChannel.LSPOSED_PLACEHOLDER,
            execIdentity = Privilege.NONE,
            writeReady = false,
        )
        val reason = writeBlockReason(s) as BlockReason.Keyed
        assertEquals(WriteBlockKind.LSPOSED_PLACEHOLDER, reason.block.kind)
        assertEquals(R.string.write_block_lsposed, reason.block.stringRes())
        assertEquals("hook evidence must ride as arg(0)", 1, reason.block.args.size)
        assertTrue(
            "hook arg must carry hook state, got: ${reason.block.args[0]}",
            reason.block.args[0].isNotEmpty(),
        )
    }

    // writeBlockReason 分支：PM 不可讀門——鍵斷言。
    @Test
    fun writeBlockReason_pmUnreadable_namesPm() {
        val s = UiState(
            channel = ChannelPreference.ROOT,
            resolvedGateway = ResolvedChannel.ROOT,
            execIdentity = Privilege.ROOT,
            privilege = Privilege.ROOT,
            writeReady = true,
            pmReadable = false,
        )
        val reason = writeBlockReason(s) as BlockReason.Keyed
        assertEquals(WriteBlockKind.PM_UNREADABLE, reason.block.kind)
        assertEquals(R.string.write_block_pm, reason.block.stringRes())
    }

    // writeBlockReason 分支：可寫回 null。
    @Test
    fun writeBlockReason_ready_returnsNull() {
        val s = UiState(
            channel = ChannelPreference.ROOT,
            resolvedGateway = ResolvedChannel.ROOT,
            execIdentity = Privilege.ROOT,
            privilege = Privilege.ROOT,
            writeReady = true,
            pmReadable = true,
        )
        assertNull(writeBlockReason(s))
    }

    // writeBlockReason 分支：不可達 NONE 權限守衛仍 fail-closed——鍵斷言。
    @Test
    fun writeBlockReason_noPrivilege_refusesReadOnly() {
        val s = UiState(
            resolvedGateway = ResolvedChannel.NOOP,
            execIdentity = Privilege.NONE,
            privilege = Privilege.NONE,
            writeReady = true,
            pmReadable = true,
        )
        val reason = writeBlockReason(s) as BlockReason.Keyed
        assertEquals(WriteBlockKind.NO_PRIVILEGE, reason.block.kind)
        assertEquals(R.string.write_block_readonly, reason.block.stringRes())
    }

    // Single source：唯讀 VM 的 clone 拒絕必須等於同狀態的 writeBlockReason
    //（同 key 經同一解析；測試假值代行生產 Context 解析）。
    @Test
    fun writeBlockReason_matchesLiveIsWritableRefusal() {
        val fake = FakeGateway()
        val vm = readOnlyVm(fake)
        pumpUntil { !vm.state.value.loading }
        vm.clone("com.example.z")
        pumpUntil { !vm.state.value.loading && vm.state.value.msg.isNotEmpty() }
        val s = vm.state.value
        val liveReason = writeBlockReason(s)
        assertTrue("blocked state must carry a reason, got: $s", liveReason != null)
        assertEquals(resolveZhTest(liveReason!!), s.msg)
        val keyed = liveReason as BlockReason.Keyed
        assertEquals(WriteBlockKind.NOOP_FALLBACK, keyed.block.kind)
    }

    // Single source：LSPOSED 佔位的實際拒絕亦同 key。
    @Test
    fun writeBlockReason_matchesLiveLsposedRefusal() {
        val fake = FakeGateway()
        val vm = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = listOf(SuElevator(fake), ShizukuElevator(fake)),
            preference = ChannelPreference.LSPOSED,
            suGateway = NoOpGateway(),
            shizukuGateway = NoOpGateway(),
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            blockText = zhTestBlockText(),
        )
        pumpUntil { !vm.state.value.loading }
        vm.clone("com.example.z")
        pumpUntil { !vm.state.value.loading && vm.state.value.msg.isNotEmpty() }
        val s = vm.state.value
        val liveReason = writeBlockReason(s)
        assertTrue("blocked state must carry a reason, got: $s", liveReason != null)
        assertEquals(resolveZhTest(liveReason!!), s.msg)
        val keyed = liveReason as BlockReason.Keyed
        assertEquals(WriteBlockKind.LSPOSED_PLACEHOLDER, keyed.block.kind)
    }

    // packageInputError：空白不報錯。
    @Test
    fun packageInputError_blank_returnsNull() {
        assertNull(packageInputError(""))
        assertNull(packageInputError("   "))
    }

    // packageInputError：有效包名通過。
    @Test
    fun packageInputError_valid_returnsNull() {
        assertNull(packageInputError("com.example.app"))
        assertNull(packageInputError("  com.example.app  "))
    }

    // packageInputError：超長回傳 TOO_LONG 鍵（上限值由資源模板代入）。
    @Test
    fun packageInputError_overlong_matchesVmRefusal() {
        assertEquals(
            PackageInputProblem.TOO_LONG,
            packageInputError("a".repeat(MAX_PACKAGE_INPUT_CHARS + 1)),
        )
        assertEquals(R.string.input_too_long, PackageInputProblem.TOO_LONG.stringRes())
        // 邊界：恰上限通過。
        assertNull(packageInputError("a".repeat(MAX_PACKAGE_INPUT_CHARS)))
    }

    // packageInputError：非法字元回傳 INVALID 鍵（沿用 core 政策）。
    @Test
    fun packageInputError_badCharset_reportsInvalid() {
        assertEquals(PackageInputProblem.INVALID, packageInputError("com.example;rm"))
        assertEquals(PackageInputProblem.INVALID, packageInputError("com example"))
        assertEquals(R.string.invalid_package, PackageInputProblem.INVALID.stringRes())
    }

    // 資源：繁中預設 Clone/Remove 統一為「建立分身／移除分身」，英文為 Clone/Remove。
    @Test
    fun strings_cloneRemove_unifiedAcrossLocales() {
        val zh = stringMap(resFile("values/strings.xml"))
        val en = stringMap(resFile("values-en/strings.xml"))
        assertEquals("建立分身", zh["action_clone"])
        assertEquals("移除分身", zh["action_remove"])
        assertEquals("Clone", en["action_clone"])
        assertEquals("Remove", en["action_remove"])
        // 兩語系鍵集合一致（防 MissingTranslation）。
        assertEquals(zh.keys + pluralNames(resFile("values/strings.xml")), en.keys + pluralNames(resFile("values-en/strings.xml")))
    }

    // 資源：寫入拒絕理由 + 包名驗證 + 診斷複製鍵範圍雙語齊備（scope-limited 雙語，
    // 非全量雙語；key/雙語斷言，不鎖死字面以外的文案）。
    @Test
    fun strings_blockAndInputKeys_scopedBilingual() {
        val zh = stringMap(resFile("values/strings.xml"))
        val en = stringMap(resFile("values-en/strings.xml"))
        val keys = listOf(
            "write_block_lsposed",
            "write_block_noop",
            "write_block_explicit",
            "write_block_pm",
            "write_block_readonly",
            "auto_degrade_su_to_shizuku",
            "auto_degrade_both_failed",
            "auto_degrade_no_channel",
            "input_too_long",
            "invalid_package",
            "pkg_input_required",
            "channel_group_cd",
            "diagnostic_copy_button",
            "diagnostic_copy_success",
            "batch_target_unavailable",
            "batch_confirm_title",
            "batch_confirm_message",
            "batch_confirm_message_stale",
            "batch_confirm_positive",
            "batch_confirm_acknowledge",
        )
        for (k in keys) {
            assertTrue("zh missing/empty $k", !zh[k].isNullOrEmpty())
            assertTrue("en missing/empty $k", !en[k].isNullOrEmpty())
        }
        // 拒絕語意關鍵字：zh 寫入已禁用/寫入已拒絕，en writes are disabled/refused。
        val zhDisable = listOf("write_block_noop", "write_block_explicit", "write_block_pm", "write_block_readonly")
        assertTrue(
            "zh block reasons must name 寫入已禁用",
            zhDisable.all { zh.getValue(it).contains("寫入已禁用") },
        )
        assertTrue(
            "zh lsposed must name 寫入已拒絕",
            zh.getValue("write_block_lsposed").contains("寫入已拒絕"),
        )
        val enDisable = listOf("write_block_noop", "write_block_explicit", "write_block_pm", "write_block_readonly")
        assertTrue(
            "en block reasons must name writes are disabled",
            enDisable.all { en.getValue(it).contains("writes are disabled") },
        )
        assertTrue(
            "en lsposed must name refused",
            en.getValue("write_block_lsposed").contains("refused"),
        )
        // P2-3 AUTO 降級語意：zh 含降級/唯讀，en carries degraded/read-only。
        assertTrue(
            "zh su->shizuku must name 降級",
            zh.getValue("auto_degrade_su_to_shizuku").contains("降級"),
        )
        assertTrue(
            "zh both-fail/no-channel must name 唯讀",
            zh.getValue("auto_degrade_both_failed").contains("唯讀") &&
                zh.getValue("auto_degrade_no_channel").contains("唯讀"),
        )
        assertTrue(
            "zh no-channel must name su 不存在",
            zh.getValue("auto_degrade_no_channel").contains("su 不存在"),
        )
        assertTrue(
            "en degrade must name degraded/read-only",
            en.getValue("auto_degrade_su_to_shizuku").contains("degraded") &&
                en.getValue("auto_degrade_both_failed").contains("read-only") &&
                en.getValue("auto_degrade_no_channel").contains("read-only"),
        )
        // 格式佔位：explicit 雙參、lsposed/上限各一參。
        assertTrue("explicit zh needs %1\$s/%2\$s", zh.getValue("write_block_explicit").contains("%1\$s") && zh.getValue("write_block_explicit").contains("%2\$s"))
        assertTrue("explicit en needs %1\$s/%2\$s", en.getValue("write_block_explicit").contains("%1\$s") && en.getValue("write_block_explicit").contains("%2\$s"))
        assertTrue("lsposed zh needs %1\$s", zh.getValue("write_block_lsposed").contains("%1\$s"))
        assertTrue("input_too_long zh needs %1\$d", zh.getValue("input_too_long").contains("%1\$d"))
        assertTrue("input_too_long en needs %1\$d", en.getValue("input_too_long").contains("%1\$d"))
        // 包名無效：zh 已中文化（不再沿用英文原文），en 維持 invalid package。
        assertEquals("包名無效", zh.getValue("invalid_package"))
        assertEquals("invalid package", en.getValue("invalid_package"))
        // 診斷複製：zh 須含分享前請人工確認/僅本機剪貼簿/用後請清除剪貼簿，
        // en 須含 review before sharing/local clipboard only/clear clipboard。
        assertTrue(
            "zh copy button must name 分享前請人工確認",
            zh.getValue("diagnostic_copy_button").contains("分享前請人工確認"),
        )
        assertTrue(
            "zh copy success must name 僅本機剪貼簿/用後請清除剪貼簿",
            zh.getValue("diagnostic_copy_success").contains("僅本機剪貼簿") &&
                zh.getValue("diagnostic_copy_success").contains("用後請清除剪貼簿") &&
                zh.getValue("diagnostic_copy_success").contains("分享前請人工確認"),
        )
        assertTrue(
            "en copy must name review before sharing",
            en.getValue("diagnostic_copy_button").contains("review before sharing") &&
                en.getValue("diagnostic_copy_success").contains("review before sharing"),
        )
        assertTrue(
            "en copy success must name local clipboard/clear clipboard",
            en.getValue("diagnostic_copy_success").contains("local clipboard") &&
                en.getValue("diagnostic_copy_success").contains("clear clipboard"),
        )
        // JVM 常數與 zh 資源同字（UI 以資源為準，const 僅為測試 seam）。
        assertEquals(zh.getValue("diagnostic_copy_button"), DIAGNOSTIC_COPY_BUTTON_TEXT)
        assertEquals(zh.getValue("diagnostic_copy_success"), DIAGNOSTIC_COPY_SUCCESS_TEXT)
        // A 批次目標不可見原因：中英皆須命名禁用＋重查入口（VM 守衛不動，僅 UI 提示）。
        assertTrue(
            "zh batch target must name 已禁用",
            zh.getValue("batch_target_unavailable").contains("已禁用"),
        )
        assertTrue(
            "zh batch target must name 重新連線並更新",
            zh.getValue("batch_target_unavailable").contains("重新連線並更新"),
        )
        assertTrue(
            "en batch target must name disabled",
            en.getValue("batch_target_unavailable").contains("disabled"),
        )
        assertTrue(
            "en batch target must name Reconnect and refresh",
            en.getValue("batch_target_unavailable").contains("Reconnect and refresh"),
        )
        // Stage1 批次建立兩步確認：sticky 勾選數（plural）＋確認對話框字串雙語齊備。
        val hant = stringMap(resFile("values-b+zh+Hant/strings.xml"))
        assertTrue(
            "zh batch confirm message must name 警告/已驗證目標/勾選下方確認框",
            zh.getValue("batch_confirm_message").contains("警告") &&
                zh.getValue("batch_confirm_message").contains("已驗證目標") &&
                zh.getValue("batch_confirm_message").contains("勾選下方確認框"),
        )
        assertTrue(
            "en batch confirm message must name Warning/Verified target/check the confirmation box below",
            en.getValue("batch_confirm_message").contains("Warning") &&
                en.getValue("batch_confirm_message").contains("Verified target") &&
                en.getValue("batch_confirm_message").contains("check the confirmation box below"),
        )
        // 格式佔位：batch_confirm_message(_stale) 依序帶入 count/userId/serial（Locale.ROOT）。
        for ((locale, map) in listOf("zh" to zh, "en" to en, "hant" to hant)) {
            for (key in listOf("batch_confirm_message", "batch_confirm_message_stale")) {
                val msg = map.getValue(key)
                assertTrue("$locale $key needs %1\$d", msg.contains("%1\$d"))
                assertTrue("$locale $key needs %2\$d", msg.contains("%2\$d"))
                assertTrue("$locale $key needs %3\$d", msg.contains("%3\$d"))
            }
            assertTrue(
                "$locale stale batch confirm must not claim 已驗證目標/Verified target",
                !map.getValue("batch_confirm_message_stale").contains("已驗證目標") &&
                    !map.getValue("batch_confirm_message_stale").contains("Verified target"),
            )
        }
        assertTrue(
            "batch_selected_count plural must exist in zh/en",
            pluralNames(resFile("values/strings.xml")).contains("batch_selected_count") &&
                pluralNames(resFile("values-en/strings.xml")).contains("batch_selected_count"),
        )
        // Stage1 批次 CTA 無障礙描述三語皆標註需二次確認（zh/hant 需二次確認；
        // en requires two-step confirmation）。
        assertTrue(
            "zh batch_create_cd must name 需二次確認",
            pluralText(resFile("values/strings.xml"), "batch_create_cd").contains("需二次確認"),
        )
        assertTrue(
            "hant batch_create_cd must name 需二次確認",
            pluralText(resFile("values-b+zh+Hant/strings.xml"), "batch_create_cd").contains("需二次確認"),
        )
        assertTrue(
            "en batch_create_cd must name requires two-step confirmation",
            pluralText(resFile("values-en/strings.xml"), "batch_create_cd").contains("requires two-step confirmation"),
        )
        // 空白禁用可感知理由：zh 須命名已禁用＋輸入包名，en 須命名 disabled＋Enter a package name。
        assertTrue(
            "zh required must name 已禁用",
            zh.getValue("pkg_input_required").contains("已禁用"),
        )
        assertTrue(
            "zh required must name 輸入包名",
            zh.getValue("pkg_input_required").contains("輸入包名"),
        )
        assertTrue(
            "en required must name disabled",
            en.getValue("pkg_input_required").contains("disabled"),
        )
        assertTrue(
            "en required must name Enter a package name",
            en.getValue("pkg_input_required").contains("Enter a package name"),
        )
    }

    // D 移除確認模板操作指示：三語系檔同步（預設/英/b+zh+Hant），末行須提示勾選確認框。
    // （Phase D 起刪除 legacy values-zh-rTW，見 strings_zhVariants_consistent。）
    @Test
    fun strings_removeConfirmMessage_hasAcknowledgeHint() {
        val zh = stringMap(resFile("values/strings.xml"))
        val en = stringMap(resFile("values-en/strings.xml"))
        val hant = stringMap(resFile("values-b+zh+Hant/strings.xml"))
        val zhMsg = zh.getValue("remove_confirm_message")
        assertTrue("zh must name 勾選下方確認框", zhMsg.contains("勾選下方確認框"))
        assertTrue("zh must name 確認移除", zhMsg.contains("確認移除"))
        val enMsg = en.getValue("remove_confirm_message")
        assertTrue("en must name check the confirmation box below", enMsg.contains("check the confirmation box below"))
        assertTrue("en must name enable Remove", enMsg.contains("enable Remove"))
        assertEquals(zhMsg, hant.getValue("remove_confirm_message"))
    }

    // 資源：兩繁中檔（values/b+zh+Hant）鎖一致（key 集＋逐值相等；
    // minSdk 29，b+zh+Hant 在 API24+ 覆蓋 zh-TW；legacy values-zh-rTW 已於
    // Phase D 刪除（BCP-47 b+ 永遠勝出，legacy 永不勝出），預設亦為繁中故
    // instrumentation zh-TW 回退仍為繁中；此處鎖語意一致，
    // RemoveConfirmMessageTest 另鎖位元一致）。
    @Test
    fun strings_zhVariants_consistent() {
        val def = stringMap(resFile("values/strings.xml"))
        val hant = stringMap(resFile("values-b+zh+Hant/strings.xml"))
        assertEquals(def.keys, hant.keys)
        for (k in def.keys) {
            assertEquals("Hant mismatch $k", def.getValue(k), hant.getValue(k))
        }
        assertEquals(
            def.keys + pluralNames(resFile("values/strings.xml")),
            hant.keys + pluralNames(resFile("values-b+zh+Hant/strings.xml")),
        )
    }

    // 資源：語意色亮/暗對假設背景的數學檢查皆至少 4.5:1（非實際主題聲稱），
    // 且舊 #B26A00 已移除。假設背景 #FFFFFF（淺）/#121212（深）僅為數學常數：
    // Manifest 未宣告 android:theme，Activity 為程式化建構、預設視窗背景
    // （隨裝置/系統而異），實際 surface 對比未驗證；完整主題綁定為 p28-3 範圍。
    @Test
    fun msgColors_contrastAtLeast45_assumedBackgrounds() {
        val light = colorMap(resFile("values/colors.xml"))
        val dark = colorMap(resFile("values-night/colors.xml"))
        val keys = listOf("msg_info", "msg_invalid", "msg_no_dual", "msg_verify", "msg_failed", "msg_channel")
        for (k in keys) {
            assertTrue("light $k missing", light.containsKey(k))
            assertTrue("dark $k missing", dark.containsKey(k))
            assertTrue("light $k vs assumed #FFFFFF contrast < 4.5:1 (${light[k]})", contrast(light[k]!!, "#FFFFFF") >= 4.5)
            assertTrue("dark $k vs assumed #121212 contrast < 4.5:1 (${dark[k]})", contrast(dark[k]!!, "#121212") >= 4.5)
        }
        val raw = resFile("values/colors.xml").readText() + resFile("values-night/colors.xml").readText()
        assertTrue("舊 #B26A00 對假設白底未達 4.5:1，須移除", !raw.contains("B26A00", ignoreCase = true))
    }

    // Phase D: warning *text* token（文字用）對其容器＋假設背景皆至少 4.5:1。
    // oneui_warning 本體僅留給圖示/描邊（非文字 3:1），文字一律走 warning_text。
    @Test
    fun warningText_contrastAtLeast45() {
        val light = colorMap(resFile("values/colors.xml"))
        val dark = colorMap(resFile("values-night/colors.xml"))
        val lt = light.getValue("oneui_warning_text")
        val lc = light.getValue("oneui_warning_container")
        assertTrue("light warning_text on container < 4.5 ($lt on $lc)", contrast(lt, lc) >= 4.5)
        assertTrue("light warning_text on assumed #FFFFFF < 4.5 ($lt)", contrast(lt, "#FFFFFF") >= 4.5)
        val dt = dark.getValue("oneui_warning_text")
        val dc = dark.getValue("oneui_warning_container")
        assertTrue("night warning_text on container < 4.5 ($dt on $dc)", contrast(dt, dc) >= 4.5)
        assertTrue("night warning_text on assumed #121212 < 4.5 ($dt)", contrast(dt, "#121212") >= 4.5)
    }

    // ---- 資源讀取/對比小工具（測試專用，不進生產） ----

    private fun resFile(rel: String): File {
        val candidates = listOf(File("src/main/res", rel), File("app/src/main/res", rel))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing res file: $rel (tried ${candidates.map { it.path }})")
    }

    private fun stringMap(f: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            val name = n.attributes.getNamedItem("name").nodeValue
            out[name] = n.textContent.trim()
        }
        return out
    }

    private fun pluralNames(f: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableSetOf<String>()
        val nodes = doc.getElementsByTagName("plurals")
        for (i in 0 until nodes.length) {
            out.add(nodes.item(i).attributes.getNamedItem("name").nodeValue)
        }
        return out
    }

    // 取指定 plural 所有 item 文字（串接），供批次文案斷言鎖定。
    private fun pluralText(f: File, name: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("plurals")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.attributes.getNamedItem("name").nodeValue == name) {
                val items = n.childNodes
                val sb = StringBuilder()
                for (j in 0 until items.length) {
                    val item = items.item(j)
                    if (item.nodeName == "item") sb.append(item.textContent.trim())
                }
                return sb.toString()
            }
        }
        throw AssertionError("missing plural: $name")
    }

    private fun colorMap(f: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableMapOf<String, String>()
        val nodes = doc.getElementsByTagName("color")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            out[n.attributes.getNamedItem("name").nodeValue] = n.textContent.trim()
        }
        return out
    }

    private fun srgbToLin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(hex: String): Double {
        val h = hex.trimStart('#')
        val r = h.substring(0, 2).toInt(16)
        val g = h.substring(2, 4).toInt(16)
        val b = h.substring(4, 6).toInt(16)
        return 0.2126 * srgbToLin(r) + 0.7152 * srgbToLin(g) + 0.0722 * srgbToLin(b)
    }

    private fun contrast(a: String, b: String): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
