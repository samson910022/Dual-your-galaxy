// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-6 P2 (issue #27, 後半狀態模型): 診斷分離/新鮮度/歷史 marker。
//
// 本檔案只放純 Kotlin 狀態模型 (JVM 可測, 無 Android 依賴):
// - InventorySnapshot: 清單 ground truth 的來源/目標身份/查詢時間/新鮮度
//   (FRESH / STALE / UNKNOWN)。refresh 成功才 FRESH；通道失敗/查不到為
//   UNKNOWN；目標重建 (dualId 改變)、通道全失、過期，或選定通道失效
//   (P2-4：選定且應可執行的通道不可用——resolved 改變或 ready 變 false——
//   即使他通道 probe 成功，舊資料照樣標 STALE，不謊稱新鮮) 時舊資料標 STALE。
// - LastOperation: 最近一次 clone/remove 的歷史結果 (opId/appKey/pinnedTarget/
//   phase/time)。普通 probe 成功不清 UNKNOWN，需重新查證 (下一次 pinned
//   clone/remove verify) 才覆寫；probe/refresh 只保留不清除。
// - Hook 時間證據: 沿用 HookStateProvider 的四態 + scope，另加 queriedAtMs
//   (App 何時查的) 與 markerTimeMs (marker 內 timeMs 行，HookMarker.parseTimeMs)。
// - DiagnoseEvent: 穩定錯誤碼 + 繁中摘要/下一步 + 可展開 exit/技術 detail
//   (大小受限，純顯示)。匯出經四道遮蔽 (契約)：先 redactEmail (郵件 →
//   <email>)，再 redactPii 內包名 (dotted token -> <pkg>)+IP (-> <ip>)，
//   最後 redactPaths (含 / 路徑 token -> <path>)；
//   順序固定 (先郵件後包名後 IP，最後路徑)，僅使用者預覽/主動匯出，無自動上傳
//   (本檔案無任何網路呼叫)。本機展開顯示亦用同一遮蔽 (見 MainActivity)。
// - 時間一律 wall-clock (System.currentTimeMillis)：有回撥/偽造風險，僅為
//   存在性證據，絕不作授權依據。needsRefresh 把未來時間戳 (now < queryTime)
//   視為需重查；marker 時間遠舊於查詢時間者顯示加註舊資料提醒。
// - i18n 缺口 (刻意保留現狀)：診斷摘要/下一步皆為硬編碼繁中常數
//   (DiagnoseStrings)，未抽 res/values string resources；未來需在地化時
//   把該表換成資源查詢即可，穩定碼 (stableId) 不受影響。
package com.galaxydual.plus

import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.ProfilesSnapshot
import com.galaxydual.core.RemoveOutcome
import com.galaxydual.hook.HookMarker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 無目標哨兵：verified dualId 未知時 (resolveDualUserId 回 -1，見
// DualRepository/PmOutputParser 契約：Confirmed 以外一律 -1) 的佔位值。
// 取代散落的 -1 字面量 (targetId/pinnedTarget 預設與比較處)；語意不變。
// P2-5 補充：-1（typed Unknown/Absent 皆折疊於此）一律視為「無法確認」——
// 呼叫端不得據此報 NO_DUAL/清空舊單；真正確認無 dual 改走 repo 的 typed
// 入口 resolveDualUserIdTyped() -> DualIdResolution.Absent。
const val NO_TARGET_ID: Int = -1

// 使用者包名輸入上限 (P2-8)：trim() 後超過此長度即 INVALID_INPUT 拒絕執行
// （取代舊 take(256) 靜默截斷：截斷會把超長輸入變形成另一個包名再執行，
// 誠實做法是拒絕）。clone/requestRemove 共用；confirmRemove 沿用 request
// 時已驗證的 pending，不再檢查。
const val MAX_PACKAGE_INPUT_CHARS: Int = 256

// 清單新鮮度：FRESH=本次查詢可信；STALE=舊資料 (目標已變/通道已失/過期)；
// UNKNOWN=從未成功查到或上次查詢不可信。
enum class InventoryFreshness {
    FRESH,
    STALE,
    UNKNOWN,
}

// 清單快照：refresh 的 ground truth 附帶何時、何地查的。
// - source: 查詢命令 (如 "pm list packages --user 95")，失敗時為 "pm list users"。
// - targetId: 查證過的 dualId (NO_TARGET_ID=無目標)。
// - queryTimeMs: wall-clock (nowWallMs)，0=從未查詢。
// - freshness/count: 見上；count 為成功列舉筆數 (UNKNOWN/STALE 仍保留舊筆數語意由呼叫端決定)。
data class InventorySnapshot(
    val source: String = "",
    val targetId: Int = NO_TARGET_ID,
    val queryTimeMs: Long = 0L,
    val freshness: InventoryFreshness = InventoryFreshness.UNKNOWN,
    val count: Int = 0,
) {
    companion object {
        fun unknown(): InventorySnapshot = InventorySnapshot()
    }
}

// 新鮮度是否需要重查：UNKNOWN/STALE 直接要；FRESH 依 maxAge 過期也要。
// 呼叫者：DualViewModel.markStaleIfAgedLocked (經 onForeground) 與
// probeChannels 的 FRESH 補標 STALE (舊資料不謊稱新鮮)。
// 清單过期閾值 (5 分鐘)：前後台切換/短暫離開期間的 FRESH 仍視為可用，
// 達閾值即視為過期重查，避免陳舊目標誤導後續寫入。
const val INVENTORY_STALE_AFTER_MS: Long = 5L * 60L * 1000L

// maxAgeMs 參數保留供測試/特殊呼叫端覆寫；生產唯一用途為預設值
// (INVENTORY_STALE_AFTER_MS)，現行生產呼叫端皆用預設。
fun InventorySnapshot.needsRefresh(now: Long, maxAgeMs: Long = INVENTORY_STALE_AFTER_MS): Boolean =
    when (freshness) {
        InventoryFreshness.UNKNOWN -> true
        InventoryFreshness.STALE -> true
        // FRESH 仍受零時間/時鐘偏移/过期三重保護：從未查詢、未來時間戳
        // (wall-clock 回撥或偽造，見檔頭限制)、或存活達 maxAgeMs 者皆需重查。
        InventoryFreshness.FRESH -> queryTimeMs <= 0L || now < queryTimeMs ||
            (now - queryTimeMs) >= maxAgeMs
    }

// 舊資料標 STALE (FRESH->STALE，queryTime 保持舊值供追溯，理由：沿用現行
// FRESH 保留語意，STALE 只降新鮮度不謊稱重新查詢)；source 沿用傳入 (理由：
// 呼叫端傳本次命令，匯出可追溯本次失敗的查詢命令)；targetId 有效
// (>NO_TARGET_ID) 即覆寫 (理由：drift 新目標如 96 不再丟棄，UI 可見新目標)；
// 無效時回退保留舊身份。count 一律保留舊筆數 (舊資料不謊稱新鮮亦不丟失)。
// 否則新建 UNKNOWN (記錄這次查詢的 source，不謊稱新鮮)。
// UNKNOWN 分支保留舊身份/筆數供追溯：呼叫端傳入有效 target (非哨兵) 才覆寫
// (傳 NO_TARGET_ID 表未知，回退保留 this.targetId，傳入的 targetId 不再丟棄)；
// count 一律保留舊筆數，與 FRESH->STALE 一致 (舊資料不謊稱新鮮亦不丟失)。
// DualViewModel.refreshLocked 各失敗分支共用；寫前拒絕分支 (M3) 亦經此降級。
// (原名 staleOrUnknownLocked；測試無引用故改名，語意/行為不變。)
fun InventorySnapshot.toStaleOrUnknown(
    now: Long,
    source: String,
    targetId: Int = NO_TARGET_ID,
): InventorySnapshot =
    if (freshness == InventoryFreshness.FRESH) {
        // 理由見上：target 有效即覆寫 (drift 不丟)、source 沿用傳入、queryTime 保持舊值。
        copy(
            source = source,
            targetId = if (targetId > NO_TARGET_ID) targetId else this.targetId,
            freshness = InventoryFreshness.STALE,
        )
    } else {
        InventorySnapshot(
            source = source,
            targetId = if (targetId > NO_TARGET_ID) targetId else this.targetId,
            queryTimeMs = now,
            freshness = InventoryFreshness.UNKNOWN,
            count = this.count,
        )
    }

// 最近一次寫操作的歷史相位：SUCCESS/FAILED/UNKNOWN (UNKNOWN=裝了但查證不了)。
enum class OpPhase {
    SUCCESS,
    FAILED,
    UNKNOWN,
}

// 最近一次操作：普通 probe 永不清除 (成功也不清 UNKNOWN)，只有下一次
// pinned clone/remove 的 verify 結果才能覆寫 (見 DualViewModel)。
data class LastOperation(
    val opId: String = "",
    val appKey: String = "",
    val pinnedTarget: Int = NO_TARGET_ID,
    val phase: OpPhase = OpPhase.UNKNOWN,
    val timeMs: Long = 0L,
    val detail: String = "",
)

// wall-clock 顯示格式 (純函數，僅顯示層用)：刻意用 yyyy-MM-dd HH:mm：
// 避開匯出 redactPaths 對 / 的改寫 (SHORT 格式含 / 會被誤傷為 <path>)。
// 0/負值表從未查詢，null (可空多載) 表未知。輸出只含數字/-/:/空格，不含 /；
// 測試不得鎖定確切字串 (契約只保證「從未查詢」分支與非空)。
// 使用 java.text/java.util (JVM/Android 內建)，不加其他依賴。
internal fun formatTimeMs(ms: Long): String =
    if (ms <= 0L) {
        "從未查詢"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    }

internal fun formatTimeMs(ms: Long?): String =
    if (ms == null) "從未查詢" else formatTimeMs(ms)

// Hook 時間證據 (沿用 HookStateProvider 四態 + scope)：
// - queriedAtMs: App 何時跑的診斷 (wall-clock)。
// - markerTimeMs: marker 內 timeMs 行 (HookMarker.parseTimeMs(raw))，null=無/不可解析，<=0 視為未知。
// - viaGateway: 是否走 privileged `cat` (診斷用)。
data class HookEvidence(
    val status: HookStatus = HookStatus.UNKNOWN_NO_DIAGNOSTIC,
    val scopes: Set<String> = emptySet(),
    val queriedAtMs: Long = 0L,
    val markerTimeMs: Long? = null,
    val viaGateway: Boolean = false,
)

// HookDiagnosis -> HookEvidence (scope/time 證據沿用 HookStateProvider/HookMarker)。
// markerTimeMs<=0 拒收 (視為無/不可解析，回 null 時間，避免 0/負值污染顯示)。
fun HookDiagnosis.toEvidence(queriedAtMs: Long): HookEvidence = HookEvidence(
    status = status,
    scopes = scopes,
    queriedAtMs = queriedAtMs,
    markerTimeMs = HookMarker.parseTimeMs(raw)?.takeIf { it > 0 },
    viaGateway = viaGateway,
)

// Hook 時間證據純文字 (有意變更：歷史 marker 狀態/範圍語意不變，時間行由 raw epoch 改為格式化顯示)。
// marker 遠舊於查詢時間 (差值超過清單过期閾值) 多半是重啟前舊資料：
// 附加提醒，僅存在性證據，不代表本次開機生效。
// 時間行經 formatTimeMs (有意變更：0/null 顯示「從未查詢」為空態明確化；
// marker 時間文案由 raw 數字改為格式化時間為可讀性/可追溯性)；
// viaGateway 為真時附加「（經真通道讀取）」(MainActivity 內聯寫法統一至此，理由：單一來源免分叉)。
fun HookEvidence.displayText(): String {
    val base = HookDiagnosis(status, scopes).displayText()
    return base + timeEvidenceText()
}

// 時間證據後綴 (internal 供 MainActivity hook 行共用，免內聯重寫)。
internal fun HookEvidence.timeEvidenceText(): String {
    val timePart = if (markerTimeMs != null) "；marker時間=${formatTimeMs(markerTimeMs)}" else "；marker 時間未知"
    val qPart = "；查詢於 ${formatTimeMs(queriedAtMs)}"
    val stalePart = if (markerTimeMs != null && markerTimeMs > 0L && queriedAtMs > 0L &&
        (queriedAtMs - markerTimeMs) > INVENTORY_STALE_AFTER_MS
    ) {
        "（可能為重啟前舊資料，僅存在性證據）"
    } else {
        ""
    }
    val viaPart = if (viaGateway) "（經真通道讀取）" else ""
    return timePart + qPart + stalePart + viaPart
}

// 診斷穩定錯誤碼：一旦發布永不改名/重用 (字串即契約，UI/匯出/測試皆以此為準)。
// OK vs null 契約 (寫死)：null 表「無事件」(UI 顯示就緒、匯出寫 OK)；
// DiagnoseCode.OK 保留給映射完整性 (toDiagnoseCode 的 Success 分支)，
// toDiagnoseEvent 遇 OK 一律回 null，diagnoseEvent(OK) 只供文案表自查，
// 不得寫入 UiState.diagnose。
// Stage C C3：E_CHANNEL 桶碼 techDetail 前綴契約 (保持 9 碼不變，不新增碼)：
// CHANNEL_ERROR 只用以下前綴 (VM 各分支寫死，測試鎖定 startsWith)：
// - "verified dual id unknown" 系：
//   "verified dual id unknown" (裸)、"verified dual id unknown: <typed/channel detail>"、
//   "verified dual id unknown and profiles snapshot empty"、
//   "verified dual id unknown for expected <id>"、
//   "verified dual id unknown for listed target <id>"；
// - "verified dual id <id> absent and profiles snapshot empty"
//   (空快照不可信，CHANNEL_ERROR，非 NO_DUAL)；
// - "profiles snapshot empty despite typed absent"
//   (refresh 首跳 Absent 但快照空)；
// - "profiles snapshot channel failed: <detail>"
//   (listCloned null 鑑別重查通道失敗)；
// - "LSPOSED placeholder / writes unsupported ..."
//   (使用者明確寫入遭 NoOp/LSPOSED 政策拒絕，非探測失敗；僅
//   isWritable 寫入拒絕路徑使用，零探測 probeChannels 永不寫此碼)；
// - gateway 原始 detail (ProfilesSnapshot.ChannelError / CloneOutcome.ChannelError /
//   RemoveOutcome.ChannelError 經 toDiagnoseEvent 透傳，無統一前綴)。
// 非 CHANNEL 桶辨析 (免誤歸)：
// - "serial unavailable ..." / "serial <a> != <b> ..." 一律 VERIFY_MISMATCH
//   (身份不可驗證，Stage C C1 與 confirm 一致)，絕非 E_CHANNEL；
// - "verified id <a> != <b>" / "verified id drifted: ..." 一律 VERIFY_MISMATCH；
// - "typed resolution absent" 一律 NO_DUAL (Absent+快照可用才報)。
enum class DiagnoseCode(val stableId: String) {
    OK("OK"),
    CHANNEL_ERROR("E_CHANNEL"),
    NO_DUAL("E_NO_DUAL"),
    VERIFY_MISMATCH("E_VERIFY_MISMATCH"),
    VERIFY_UNKNOWN("E_VERIFY_UNKNOWN"),
    STILL_INSTALLED("E_STILL_INSTALLED"),
    OP_FAILED("E_OP_FAILED"),
    INVALID_INPUT("E_INVALID"),
    PROBE_FAILED("E_PROBE"),
}

// 診斷事件：穩定碼 + 繁中摘要/下一步 + 可展開 exit/技術 detail。
// techDetail 大小受限 (MAX_DIAG_DETAIL_CHARS)，建構一律經 diagnoseEvent() 截斷。
// exit 前向相容佔位：Phase 1 起透傳 (outcome 若攜帶結構化 exit 經 exit 參數
// 傳入)，現行 outcome 尚無 exit 欄位、尚無呼叫端填真實 exit；欄位保留不刪。
data class DiagnoseEvent(
    val code: DiagnoseCode,
    val summaryZh: String,
    val nextStepZh: String,
    val exit: Int? = null,
    val techDetail: String = "",
)

const val MAX_DIAG_DETAIL_CHARS: Int = 2000
const val MAX_DIAG_EXPORT_CHARS: Int = 4000

internal fun capDetail(raw: String): String =
    if (raw.length <= MAX_DIAG_DETAIL_CHARS) raw else raw.take(MAX_DIAG_DETAIL_CHARS)

// 繁中摘要/下一步常數表 (同檔頂層 object)：9 組穩定碼各一對，diagnoseEvent
// 改引用此表 (文案一字不差)；i18n 缺口見檔頭 (無 res/values)。
object DiagnoseStrings {
    // 契約：每碼恰一對 (summary, nextStep)；diagnoseEvent 以 getValue 取，
    // 缺碼即拋 (fail-fast，缺文案不可靜默空字串)。
    val TABLE: Map<DiagnoseCode, Pair<String, String>> = mapOf(
        DiagnoseCode.OK to ("就緒" to "無需處理"),
        DiagnoseCode.CHANNEL_ERROR to ("通道錯誤：命令執行失敗" to "檢查授權後點「重新連線並更新」重試"),
        DiagnoseCode.NO_DUAL to ("找不到雙開 profile" to "確認雙開已建立後點「重新連線並更新」"),
        DiagnoseCode.VERIFY_MISMATCH to ("驗證不一致：寫入回報成功但重查不符" to "點「重新連線並更新」重查，不要重複寫入"),
        DiagnoseCode.VERIFY_UNKNOWN to ("結果未知：寫入後無法查證" to "點「重新連線並更新」重新查證後再決定"),
        DiagnoseCode.STILL_INSTALLED to ("移除未生效：套件仍在" to "點「重新連線並更新」重查後再試一次移除"),
        DiagnoseCode.OP_FAILED to ("操作失敗：命令回報失敗" to "檢查訊息後重試，或點「重新連線並更新」"),
        DiagnoseCode.INVALID_INPUT to ("輸入無效或拒絕寫入" to "修正輸入或切換到可用通道後重試"),
        DiagnoseCode.PROBE_FAILED to ("探測未通過：通道唯讀" to "檢查授權後點「重新連線並更新」重試"),
    )
}

// 穩定碼 -> 繁中摘要/下一步 (純函數，測試鎖定文案關鍵字；文案源自
// DiagnoseStrings，OK 分支保留僅供完整性，見 DiagnoseCode 契約註解)。
// exit 同 DiagnoseEvent 註解：前向相容佔位，現行呼叫端皆傳預設 null。
fun diagnoseEvent(
    code: DiagnoseCode,
    exit: Int? = null,
    techDetail: String = "",
): DiagnoseEvent {
    val (summary, next) = DiagnoseStrings.TABLE.getValue(code)
    return DiagnoseEvent(code, summary, next, exit, capDetail(techDetail))
}

// CloneOutcome -> 穩定碼映射 (錯誤碼區分：每類 outcome 不同碼)。
fun CloneOutcome.toDiagnoseCode(): DiagnoseCode = when (this) {
    is CloneOutcome.Success -> DiagnoseCode.OK
    is CloneOutcome.NoDual -> DiagnoseCode.NO_DUAL
    is CloneOutcome.ChannelError -> DiagnoseCode.CHANNEL_ERROR
    is CloneOutcome.InvalidPackage -> DiagnoseCode.INVALID_INPUT
    is CloneOutcome.InstallFailed -> DiagnoseCode.OP_FAILED
    is CloneOutcome.VerifyFailed -> DiagnoseCode.VERIFY_MISMATCH
    is CloneOutcome.VerifyUnknown -> DiagnoseCode.VERIFY_UNKNOWN
}

// RemoveOutcome -> 穩定碼映射。
// TargetMoved/SerialMismatch 皆為「目標已變更、零卸載」，與 VERIFY_MISMATCH
// 同式 (呼叫方皆以 INVALID + 重新確認處理)；Cancelled 非錯誤 (零卸載、
// 只清 loading)，映射為 OK (toDiagnoseEvent 回 null，不覆寫診斷)。
fun RemoveOutcome.toDiagnoseCode(): DiagnoseCode = when (this) {
    is RemoveOutcome.Success -> DiagnoseCode.OK
    is RemoveOutcome.NoDual -> DiagnoseCode.NO_DUAL
    is RemoveOutcome.ChannelError -> DiagnoseCode.CHANNEL_ERROR
    is RemoveOutcome.InvalidPackage -> DiagnoseCode.INVALID_INPUT
    is RemoveOutcome.RemoveFailed -> DiagnoseCode.OP_FAILED
    is RemoveOutcome.StillInstalled -> DiagnoseCode.STILL_INSTALLED
    is RemoveOutcome.VerifyUnknown -> DiagnoseCode.VERIFY_UNKNOWN
    is RemoveOutcome.TargetMoved -> DiagnoseCode.VERIFY_MISMATCH
    is RemoveOutcome.SerialMismatch -> DiagnoseCode.VERIFY_MISMATCH
    is RemoveOutcome.Cancelled -> DiagnoseCode.OK
}

// CloneOutcome -> DiagnoseEvent (exit/techDetail 可展開，大小受限)。
// 契約：outcome 若攜帶 exit 經 exit 參數透傳 (前向相容佔位；現行 outcome
// 尚無 exit 欄位，呼叫端有結構化 exit 時傳入，預設 null 不斷舊呼叫端)；
// OK 遇碼即回 null (見 DiagnoseCode 契約)；VerifyFailed 的 tech
// 改用 redactPackage() 後包名+dualId (包名由呼叫端經 pkg 傳入，缺省空字串
// 時沿用舊泛稱)。
fun CloneOutcome.toDiagnoseEvent(pkg: String = "", exit: Int? = null): DiagnoseEvent? {
    val code = toDiagnoseCode()
    if (code == DiagnoseCode.OK) return null
    val tech = when (this) {
        is CloneOutcome.ChannelError -> detail
        is CloneOutcome.InstallFailed -> detail
        is CloneOutcome.VerifyFailed ->
            if (pkg.isBlank()) "pkg not installed for user $dualId"
            else "${redactPackage(pkg)} not installed for user $dualId"
        is CloneOutcome.VerifyUnknown -> detail
        else -> ""
    }
    return diagnoseEvent(code, exit = exit, techDetail = tech)
}

// RemoveOutcome -> DiagnoseEvent (契約同上：exit 前向相容佔位，現行尚無
// 呼叫端填真實 exit；OK 遇碼即回 null；StillInstalled 的 tech 改用
// redactPackage() 後包名+dualId)。
fun RemoveOutcome.toDiagnoseEvent(pkg: String = "", exit: Int? = null): DiagnoseEvent? {
    val code = toDiagnoseCode()
    if (code == DiagnoseCode.OK) return null
    val tech = when (this) {
        is RemoveOutcome.ChannelError -> detail
        is RemoveOutcome.RemoveFailed -> detail
        is RemoveOutcome.StillInstalled ->
            if (pkg.isBlank()) "pkg still installed for user $dualId"
            else "${redactPackage(pkg)} still installed for user $dualId"
        is RemoveOutcome.VerifyUnknown -> detail
        is RemoveOutcome.TargetMoved -> "verified id $verified != expected $expected"
        is RemoveOutcome.SerialMismatch -> "serial ${actual ?: "?"} != expected ${expected ?: "?"}"
        else -> ""
    }
    return diagnoseEvent(code, exit = exit, techDetail = tech)
}

// profilesSnapshot 失敗 -> DiagnoseEvent (refresh 第一跳)。
fun ProfilesSnapshot.toDiagnoseEvent(): DiagnoseEvent? = when (this) {
    is ProfilesSnapshot.Loaded -> null
    is ProfilesSnapshot.ChannelError -> diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = detail)
}

// 個資遮蔽 (純函數)：四道——郵件→包名→IP→路徑 (順序固定)。
// - redactEmail：[\w.+-]+@[\w.-]+ → <email> (須置於包名之前：
//   foo@example.com 的 example.com 會被 PKG_TOKEN 誤判為包名，若包名先走
//   會碎成 foo@<pkg> 殘留 local-part；郵件先走整段吞入，不殘留)。
//   PKG_TOKEN/ChannelError tech 可含 UserInfo 整行，故自由文字 label 的
//   郵件亦須遮 (PATH_TOKEN 不處理無斜線郵件)。
// - redactPii：dotted 包名 token → <pkg>，數字 uid/serial/exit 不動
//   (exit code 與 userId 皆為小整數，混淆數字會破壞可讀性；包名才是個資)。
// - redactIp：IPv4 ((?:\d{1,3}\.){3}\d{1,3} → <ip>) + IPv6 五分支
//   (順序 embedded 含`::`優先 → 未壓縮 embedded → IPv4 → 壓縮`::` → 完整8段 → 含字母；JVM java.util.regex 合法)：
//   embedded [0-9A-Fa-f:.]*::(?:[0-9A-Fa-f]{1,4}:){0,6}(?:\d{1,3}\.){3}\d{1,3} 須置最前——否則純 IPv4
//   分支先吃掉尾段留下 `::ffff:<ip>` (或壓縮分支在 `.` 止殘留 `<ip>.168.1.25`)；
//   覆蓋 ::ffff:192.168.1.25/::192.168.1.25/2001:db8::ffff:192.168.1.25
//   /::ffff:0:192.168.1.25/2001:db8::abcd:192.168.1.25/2001:db8::1:2:192.168.1.25
//   (前綴+`::`+0–6 個 hex 群+IPv4；舊 `(?i:ffff:)?` 已併入 middle 通式——ffff/FFFF 僅為
//   middle 的一例 ([0-9A-Fa-f] 已大小寫無關)，故刪除原子串不另保留；middle 含 ffff/0/abcd/1:2)；
//   總群組數有界 (middle {0,6} 每群 {1,4}，前綴短字串) 防回溯；未壓縮 embedded (?:[0-9A-Fa-f]{1,4}:){6}(?:\d{1,3}\.){3}\d{1,3}
//   置於純 IPv4 與一般冒號分支之前——覆蓋 0:0:0:0:0:ffff:192.168.1.25/
//   2001:db8:0:0:0:ffff:192.168.1.25 (無 `::`，恰 6 個冒號分隔群+IPv4 尾；否則含字母分支
//   先在 `.` 止吃掉 `0:…:ffff:192` 殘留 `<ip>.168.1.25`，純 IPv4 分支亦先吃尾段)；
//   大小寫由 [0-9A-Fa-f] 字符集覆蓋 (ffff/FFFF 皆可，無需 (?i))；無額外 token boundary——
//   既有全部分支皆無 boundary 慣例，保持一致 (更長 token 部分吞入風險與既有行為一致)；
//   回溯風險可忽略 (診斷字串短，嵌套量詞僅 {1,4}x{6} 有界)；純 IPv4 不含 `::`，不受 embedded 分支影響；
//   壓縮形 (?=[0-9A-Fa-f:.]*::)(?:[0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f]{0,4}
//   覆蓋 ::1/fe80::1/2001:db8::1；完整8段 (?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}
//   (恰 7 個冒號，全數字 2001:0:0:0:0:0:0:1 與 1:2:3:4:5:6:7:8 命中，
//   12:34:56 僅 2 個冒號不命中)；含字母 (?=[0-9A-Fa-f:.]*[A-Fa-f])(?:[0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f]{0,4}
//   保留既有行為 (如 ab:cd:ef 類)；時分 12:34 (僅 1 個冒號) 不誤傷；數字 uid/serial/exit
//   (user 95/serial 15/exit=0) 刻意保留可讀性，不動。
// 匯出/預覽一律先過郵件再過包名+IP (見 redactPii 實作) 最後過 redactPaths；
// 無自動上傳 (本模組無網路呼叫)。
private val PKG_TOKEN = Regex("\\b[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+\\b")
private val EMAIL_TOKEN = Regex("[\\w.+-]+@[\\w.-]+")
private val IP_TOKEN = Regex("[0-9A-Fa-f:.]*::(?:[0-9A-Fa-f]{1,4}:){0,6}(?:\\d{1,3}\\.){3}\\d{1,3}|(?:[0-9A-Fa-f]{1,4}:){6}(?:\\d{1,3}\\.){3}\\d{1,3}|(?:\\d{1,3}\\.){3}\\d{1,3}|(?=[0-9A-Fa-f:.]*::)(?:[0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f]{0,4}|(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|(?=[0-9A-Fa-f:.]*[A-Fa-f])(?:[0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f]{0,4}")

internal fun redactEmail(text: String): String = EMAIL_TOKEN.replace(text, "<email>")

internal fun redactIp(text: String): String = IP_TOKEN.replace(text, "<ip>")

fun redactPii(text: String): String = redactIp(PKG_TOKEN.replace(redactEmail(text), "<pkg>"))

// 路徑遮蔽 (純函數，末道)：把含 / 的完整路徑延展換成 <path>。
// 保守遮蔽（fail-closed，寧可過度遮蔽不洩漏）：由前斜線起延展，含 U+0020
// 空格（裝置路徑可含空格，如 /sdcard/My Wedding/Photo 001.png，舊 Regex
// "/[\w./-]+" 遇空格即斷裂殘留 "My Wedding/Photo 001.png"，故延展須含
// 空格；精確化：僅 U+0020 空格，TAB (\t, U+0009) 屬 \u0000-\u001F 止集不吞），
// 遇換行/控制字元/引號（"'`）/特定分隔（<>|*?）才止；未列入止集
// 者一律吞入（含 U+0020 空格與標點，同一行尾隨文案可能被連帶遮蔽，可接受）。
// Unicode 支援：延展採否定字元集（未排除者全收），自然含 \p{L}（CJK 中文
// 如 /sdcard/照片/婚禮照 001.png 全遮）；\w（[A-Za-z0-9_]，Kotlin Regex
// \w 不含中文）已棄用，刻意不用白名單（白名單本身即外洩面）。
// 心智驗證："/sdcard/My Wedding/Photo 001.png" 全遮；Unicode 路徑全遮；
// 引號路徑（"/sdcard/a b.png"）止於引號、內部全遮；日期 "2026-11-05 12:34"
// 無斜線不受影響；純中文文案無斜線不受影響；"exit=0" 無斜線不受影響。
// 順序固定為先郵件後包名後 IP，最後路徑 (見 redactPii + sanitizeForDisplay)。
// 已知取捨（保留現狀，僅文件化，採 (a) 不改 regex 只補註解+鎖單測）：
// - 遮蔽順序碎裂：先包名後路徑，故 "/data/data/com.example.chat/files"
//   先包名得 "/data/data/<pkg>/files" (PKG 吞掉中段)，再路徑因止集含 <>
//   切成 "<path><pkg><path>" 三段碎裂；無 raw 外洩但洩結構訊號 (三段式，
//   暗示中段為 dotted token)，可接受；不得為此調順序 (路徑先走會整吞包名
//   雖整潔，但郵件域名先遮才能保 local-part 不殘留，順序以郵件優先為準)。
//   單測鎖定二選一 (整遮 <path> 或碎裂 <path><pkg><path> 皆可，禁 raw)。
// - "a/b" 類正常文案會被誤傷為 <path>（fail-closed，可接受：
//   遮蔽過度只損可讀性，不洩個資；白名單本身即外洩面，刻意不做）。
// - 反斜線（C:\...）仍超出 Android 威脅模型，不處理
//   （裝置路徑皆為正斜線；桌面風格路徑不進本機顯示/匯出）。
// - 同行尾隨文案（如 "denied /sdcard/a.png 殘留" 的 " 殘留"）會被連帶
//   遮蔽為 <path>（fail-closed，可接受，不謊稱精確）。
// - *? 後副檔名尾巴殘留：止集含 *?（glob/查詢語法分隔），故 "/sdcard/a*b"
//   遮為 "<path>*b"（*b 殘留）；前段大部已遮，殘留僅副檔名尾巴級，可接受，
//   不為此放寬止集（放寬即外洩面）。
// - 裸 / 非敏感不斷言：單一 "/"（如 "a / b"）雖命中 regex，但非路徑個資，
//   單測不斷言裸 / 被遮（見 redactPaths_globTail_residual 鎖定殘留語意）。
// - 相對路徑中 / 起子段仍會被遮（實測 sdcard/a.png → sdcard<path>，
//   因其中 "/a.png" 命中 PATH_TOKEN），屬過遮可接受；裝置路徑皆為絕對路徑，
//   相對路徑不另設模型；不處理反斜線 (見上)。
// - 遮蔽仍非全面保證（見匯出文案「分享前請人工確認」），不得宣稱絕對乾淨。
private val PATH_TOKEN = Regex("/\\S[^\n\r\u0000-\u001F\u007F\"'`<>|*?]*")

internal fun redactPaths(text: String): String = PATH_TOKEN.replace(text, "<path>")

// 顯示前淨化 (純函數)：壓 control 字元 (含換行) 為單空白，再走四道遮蔽
// (先郵件後包名後 IP，最後路徑：redactPii 內含郵件→包名→IP，外層再套路徑)，最後 take(500) 有界。所有直接 catch 的 e.message 插值
// (VM 5 處 + Activity 2 處) 顯示前一律經此函數；經 gateway detail 進 msg
// 的間接鏈 (snap.detail/res.detail/channelDetail) 亦經此函數覆蓋。
// techDetail 顯示前同理 (之後再 take MAX_DIAG_DETAIL_CHARS)。
private val CONTROL_CHARS = Regex("[\r\n\u0000-\u001F\u007F]+")

internal fun sanitizeForDisplay(raw: String): String =
    redactPaths(redactPii(raw.replace(CONTROL_CHARS, " "))).take(500)

// 使用者自輸包名的本機回音淨化 (純函數)：只壓 control 字元並有界，
// 不做包名遮蔽。理由：這是使用者剛親手輸入的字串，在自己螢幕上回顯
// (msg/確認對話框) 必須是原文——新 base RemoveConfirmTest 鎖定
// "removed: <真包名>"，Round2 的 dialog-raw 測試亦同；遮蔽只發生在
// 診斷 techDetail/匯出 (redactPii/redactPaths)，那些會離開當下畫面
// (預覽/複製/匯出) 才需遮。儲存的 opId/appKey/pending.pkg 本就保持 raw。
internal fun sanitizeUserEcho(raw: String): String =
    raw.replace(CONTROL_CHARS, " ").take(500)

// 包名單筆遮蔽：保留首段供除錯分組，其餘隱藏 (com.example.chat -> com.***)。
// 已知取捨 (保留現狀)：首段 vendor 辨識殘留 (如 com 可辨識生態系，
// 但無完整包名已大幅降低個資精度)；另 "android.permission.X" 這類
// dotted 系統常數亦會被 redactPii 視為包名誤傷 (顯示為 <pkg>，可讀性稍損)。
// 兩者皆為可讀性/隱私折衷，刻意不做更細白名單 (白名單本身即外洩面)。
fun redactPackage(pkg: String): String {
    val head = pkg.trim().substringBefore(".").takeIf { it.isNotEmpty() } ?: return "***"
    return if ("." in pkg.trim()) "$head.***" else "***"
}

// 診斷匯出 (使用者預覽/主動匯出用，已遮蔽可辨識資料、大小受限、無自動上傳)。
// 誠實文案：遮蔽仍非全面保證，標題明示「分享前請人工確認」，不得宣稱絕對乾淨。
// 輸入 UiState，輸出多行繁中文字；呼叫端 (MainActivity 匯出按鈕) 只負責顯示，
// 不做任何網路上傳 (契約見檔頭與 MainActivity 註解)。
fun buildDiagnosticExport(state: UiState): String {
    // m2 匯出行注入防護 (入口全量清洗，與 redact 同層；顯示側不動)：
    // 使用者/通道可控字串 (source/techDetail/detail/scopes) 先過
    // CONTROL_CHARS→" " (全量，不截斷)，再進四道遮蔽 (郵件→包名→IP→路徑)；結構換行
    // (appendLine 自身) 保留，只壓欄位內換行，避免偽造匯出行。
    fun clean(s: String): String = s.replace(CONTROL_CHARS, " ")
    val sb = StringBuilder()
    sb.appendLine("【診斷匯出（已遮蔽可辨識資料，分享前請人工確認，僅本機預覽）】")
    val diag = state.diagnose
    if (diag != null) {
        sb.appendLine("錯誤碼：${diag.code.stableId}")
        sb.appendLine("摘要：${diag.summaryZh}")
        sb.appendLine("下一步：${diag.nextStepZh}")
        if (diag.exit != null) sb.appendLine("exit：${diag.exit}")
        if (diag.techDetail.isNotEmpty()) sb.appendLine("技術細節：${clean(diag.techDetail)}")
    } else {
        sb.appendLine("錯誤碼：OK（無異常）")
    }
    sb.appendLine(
        "清單：${state.inventory.freshness} target=${state.inventory.targetId} " +
            "筆數=${state.inventory.count} 來源=${clean(state.inventory.source)} " +
            "查詢於=${formatTimeMs(state.inventory.queryTimeMs)}",
    )
    val last = state.lastOperation
    if (last != null) {
        // 理由 (security-low)：不再顯示 raw opId (含完整包名)，改顯示
        // redactPackage(appKey)+phase (+targetId 若有效)；儲存的 opId/appKey 保持 raw。
        val targetPart =
            if (last.pinnedTarget > NO_TARGET_ID) " target=${last.pinnedTarget}" else ""
        sb.appendLine(
            "上次操作：${redactPackage(clean(last.appKey))}$targetPart " +
                "相位=${last.phase} 時間=${formatTimeMs(last.timeMs)}",
        )
        if (last.detail.isNotEmpty()) sb.appendLine("操作細節：${clean(last.detail)}")
    } else {
        sb.appendLine("上次操作：無")
    }
    sb.appendLine(
        "Hook：${state.hookStatus} 範圍=${clean(state.hookScopes.sorted().joinToString(","))} " +
            "marker時間=${state.hookMarkerTimeMs?.let { formatTimeMs(it) } ?: "未知"} " +
            "查詢於=${formatTimeMs(state.hookQueriedAtMs)}",
    )
    sb.appendLine("通道：偏好=${state.channel} 實際=${state.resolvedGateway} 執行身份=${state.execIdentity}")
    // 四道遮蔽 (順序固定：先郵件後包名後 IP，最後路徑)，與 sanitizeForDisplay/本機顯示一致。
    val redacted = redactPaths(redactPii(sb.toString()))
    return if (redacted.length <= MAX_DIAG_EXPORT_CHARS) redacted else redacted.take(MAX_DIAG_EXPORT_CHARS)
}

// 匯出複製用純文字 (P2-7)：對話框預覽與剪貼簿複製共用同一字串——直接回傳
// buildDiagnosticExport(state)，保證「預覽所見即複製所得」，兩者一字不差。
// MainActivity 的複製按鈕只拷貝此函數結果（使用者主動觸發，不做自動傳輸）。
fun diagnosticExportCopyText(state: UiState): String = buildDiagnosticExport(state)
