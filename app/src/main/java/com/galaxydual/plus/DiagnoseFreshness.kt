// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-6 P2 (issue #27, 後半狀態模型): 診斷分離/新鮮度/歷史 marker。
//
// 本檔案只放純 Kotlin 狀態模型 (JVM 可測, 無 Android 依賴):
// - InventorySnapshot: 清單 ground truth 的來源/目標身份/查詢時間/新鮮度
//   (FRESH / STALE / UNKNOWN)。refresh 成功才 FRESH；通道失敗/查不到為
//   UNKNOWN；目標重建 (dualId 改變) 或通道全失時舊資料標 STALE (不謊稱新鮮)。
// - LastOperation: 最近一次 clone/remove 的歷史結果 (opId/appKey/pinnedTarget/
//   phase/time)。普通 probe 成功不清 UNKNOWN，需重新查證 (下一次 pinned
//   clone/remove verify) 才覆寫；probe/refresh 只保留不清除。
// - Hook 時間證據: 沿用 HookStateProvider 的四態 + scope，另加 queriedAtMs
//   (App 何時查的) 與 markerTimeMs (marker 內 timeMs 行，HookMarker.parseTimeMs)。
// - DiagnoseEvent: 穩定錯誤碼 + 繁中摘要/下一步 + 可展開 exit/技術 detail
//   (大小受限，純顯示)。匯出經 redactPii 遮蔽個資 (包名等 dotted token)，
//   僅使用者預覽/主動匯出，無自動上傳 (本檔案無任何網路呼叫)。
package com.galaxydual.plus

import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.ProfilesSnapshot
import com.galaxydual.core.RemoveOutcome
import com.galaxydual.hook.HookMarker

// 清單新鮮度：FRESH=本次查詢可信；STALE=舊資料 (目標已變/通道已失/過期)；
// UNKNOWN=從未成功查到或上次查詢不可信。
enum class InventoryFreshness {
    FRESH,
    STALE,
    UNKNOWN,
}

// 清單快照：refresh 的 ground truth 附帶何時、何地查的。
// - source: 查詢命令 (如 "pm list packages --user 95")，失敗時為 "pm list users"。
// - targetId: 查證過的 dualId (-1=無目標)。
// - queryTimeMs: wall-clock (nowMs)，0=從未查詢。
// - freshness/count: 見上；count 為成功列舉筆數 (UNKNOWN/STALE 仍保留舊筆數語意由呼叫端決定)。
data class InventorySnapshot(
    val source: String = "",
    val targetId: Int = -1,
    val queryTimeMs: Long = 0L,
    val freshness: InventoryFreshness = InventoryFreshness.UNKNOWN,
    val count: Int = 0,
) {
    companion object {
        fun unknown(): InventorySnapshot = InventorySnapshot()
    }
}

// 新鮮度是否需要重查：UNKNOWN/STALE 直接要；FRESH 依 maxAge 過期也要。
// 純函數，MainActivity.onResume 的自動重查判斷共用。
const val INVENTORY_STALE_AFTER_MS: Long = 5L * 60L * 1000L

fun InventorySnapshot.needsRefresh(nowMs: Long, maxAgeMs: Long = INVENTORY_STALE_AFTER_MS): Boolean =
    when (freshness) {
        InventoryFreshness.UNKNOWN -> true
        InventoryFreshness.STALE -> true
        InventoryFreshness.FRESH -> (nowMs - queryTimeMs) > maxAgeMs || queryTimeMs <= 0L
    }

// 舊資料標 STALE (FRESH->STALE，原 target/queryTime 保留供追溯)；
// 否則新建 UNKNOWN (記錄這次查詢的 source/target，不謊稱新鮮)。
// DualViewModel.refreshLocked 各失敗分支共用。
fun InventorySnapshot.staleOrUnknownLocked(
    now: Long,
    source: String,
    targetId: Int = -1,
): InventorySnapshot =
    if (freshness == InventoryFreshness.FRESH) {
        copy(freshness = InventoryFreshness.STALE)
    } else {
        InventorySnapshot(
            source = source,
            targetId = targetId,
            queryTimeMs = now,
            freshness = InventoryFreshness.UNKNOWN,
            count = 0,
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
    val pinnedTarget: Int = -1,
    val phase: OpPhase = OpPhase.UNKNOWN,
    val timeMs: Long = 0L,
    val detail: String = "",
)

// Hook 時間證據 (沿用 HookStateProvider 四態 + scope)：
// - queriedAtMs: App 何時跑的診斷 (wall-clock)。
// - markerTimeMs: marker 內 timeMs 行 (HookMarker.parseTimeMs(raw))，null=無/不可解析。
// - viaGateway: 是否走 privileged `cat` (診斷用)。
data class HookEvidence(
    val status: HookStatus = HookStatus.UNKNOWN_NO_DIAGNOSTIC,
    val scopes: Set<String> = emptySet(),
    val queriedAtMs: Long = 0L,
    val markerTimeMs: Long? = null,
    val viaGateway: Boolean = false,
)

// HookDiagnosis -> HookEvidence (scope/time 證據沿用 HookStateProvider/HookMarker)。
fun HookDiagnosis.toEvidence(queriedAtMs: Long): HookEvidence = HookEvidence(
    status = status,
    scopes = scopes,
    queriedAtMs = queriedAtMs,
    markerTimeMs = HookMarker.parseTimeMs(raw),
    viaGateway = viaGateway,
)

// Hook 時間證據純文字 (歷史 marker 語意不變，另加時間行)。
fun HookEvidence.displayText(): String {
    val base = HookDiagnosis(status, scopes).displayText()
    val timePart = if (markerTimeMs != null) "；marker timeMs=$markerTimeMs" else "；marker 時間未知"
    val qPart = if (queriedAtMs > 0L) "；查詢於 $queriedAtMs" else ""
    return base + timePart + qPart
}

// 診斷穩定錯誤碼：一旦發布永不改名/重用 (字串即契約，UI/匯出/測試皆以此為準)。
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
data class DiagnoseEvent(
    val code: DiagnoseCode,
    val summaryZh: String,
    val nextStepZh: String,
    val exit: Int? = null,
    val techDetail: String = "",
)

const val MAX_DIAG_DETAIL_CHARS: Int = 2000
const val MAX_DIAG_EXPORT_CHARS: Int = 4000

private fun capDetail(raw: String): String =
    if (raw.length <= MAX_DIAG_DETAIL_CHARS) raw else raw.take(MAX_DIAG_DETAIL_CHARS)

// 穩定碼 -> 繁中摘要/下一步 (純函數，測試鎖定文案關鍵字)。
fun diagnoseEvent(
    code: DiagnoseCode,
    exit: Int? = null,
    techDetail: String = "",
): DiagnoseEvent {
    val (summary, next) = when (code) {
        DiagnoseCode.OK -> "就緒" to "無需處理"
        DiagnoseCode.CHANNEL_ERROR -> "通道錯誤：命令執行失敗" to "檢查授權後點「重新連線並更新」重試"
        DiagnoseCode.NO_DUAL -> "找不到雙開 profile" to "確認雙開已建立後點「重新連線並更新」"
        DiagnoseCode.VERIFY_MISMATCH -> "驗證不一致：寫入回報成功但重查不符" to "點「重新連線並更新」重查，不要重複寫入"
        DiagnoseCode.VERIFY_UNKNOWN -> "結果未知：寫入後無法查證" to "點「重新連線並更新」重新查證後再決定"
        DiagnoseCode.STILL_INSTALLED -> "移除未生效：套件仍在" to "點「重新連線並更新」重查後再試一次移除"
        DiagnoseCode.OP_FAILED -> "操作失敗：命令回報失敗" to "檢查訊息後重試，或點「重新連線並更新」"
        DiagnoseCode.INVALID_INPUT -> "輸入無效或拒絕寫入" to "修正輸入或切換到可用通道後重試"
        DiagnoseCode.PROBE_FAILED -> "探測未通過：通道唯讀" to "檢查授權後點「重新連線並更新」重試"
    }
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
fun CloneOutcome.toDiagnoseEvent(): DiagnoseEvent? {
    val code = toDiagnoseCode()
    if (code == DiagnoseCode.OK) return null
    val tech = when (this) {
        is CloneOutcome.ChannelError -> detail
        is CloneOutcome.InstallFailed -> detail
        is CloneOutcome.VerifyFailed -> "pkg not installed for user $dualId"
        is CloneOutcome.VerifyUnknown -> detail
        else -> ""
    }
    return diagnoseEvent(code, techDetail = tech)
}

// RemoveOutcome -> DiagnoseEvent。
fun RemoveOutcome.toDiagnoseEvent(): DiagnoseEvent? {
    val code = toDiagnoseCode()
    if (code == DiagnoseCode.OK) return null
    val tech = when (this) {
        is RemoveOutcome.ChannelError -> detail
        is RemoveOutcome.RemoveFailed -> detail
        is RemoveOutcome.StillInstalled -> "pkg still installed for user $dualId"
        is RemoveOutcome.VerifyUnknown -> detail
        is RemoveOutcome.TargetMoved -> "verified id $verified != expected $expected"
        is RemoveOutcome.SerialMismatch -> "serial ${actual ?: "?"} != expected ${expected ?: "?"}"
        else -> ""
    }
    return diagnoseEvent(code, techDetail = tech)
}

// profilesSnapshot 失敗 -> DiagnoseEvent (refresh 第一跳)。
fun ProfilesSnapshot.toDiagnoseEvent(): DiagnoseEvent? = when (this) {
    is ProfilesSnapshot.Loaded -> null
    is ProfilesSnapshot.ChannelError -> diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = detail)
}

// 個資遮蔽 (純函數)：把 dotted 包名 token 換成 <pkg>，數字 uid/serial 不動
// (exit code 與 userId 皆為小整數，混淆數字會破壞可讀性；包名才是個資)。
// 匯出/預覽一律先過此函數；無自動上傳 (本模組無網路呼叫)。
private val PKG_TOKEN = Regex("\\b[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+\\b")

fun redactPii(text: String): String = PKG_TOKEN.replace(text, "<pkg>")

// 包名單筆遮蔽：保留首段供除錯分組，其餘隱藏 (com.example.chat -> com.***）。
fun redactPackage(pkg: String): String {
    val head = pkg.trim().substringBefore(".").takeIf { it.isNotEmpty() } ?: return "***"
    return if ("." in pkg.trim()) "$head.***" else "***"
}

// 診斷匯出 (使用者預覽/主動匯出用，已遮蔽、大小受限、無自動上傳)。
// 輸入 UiState，輸出多行繁中文字；呼叫端 (MainActivity 匯出按鈕) 只負責顯示，
// 不做任何網路上傳 (契約見檔頭與 MainActivity 註解)。
fun buildDiagnosticExport(state: UiState): String {
    val sb = StringBuilder()
    sb.appendLine("【診斷匯出（已遮蔽，僅本機預覽）】")
    val diag = state.diagnose
    if (diag != null) {
        sb.appendLine("錯誤碼：${diag.code.stableId}")
        sb.appendLine("摘要：${diag.summaryZh}")
        sb.appendLine("下一步：${diag.nextStepZh}")
        if (diag.exit != null) sb.appendLine("exit：${diag.exit}")
        if (diag.techDetail.isNotEmpty()) sb.appendLine("技術細節：${diag.techDetail}")
    } else {
        sb.appendLine("錯誤碼：OK（無異常）")
    }
    sb.appendLine(
        "清單：${state.inventory.freshness} target=${state.inventory.targetId} " +
            "筆數=${state.inventory.count} 來源=${state.inventory.source} " +
            "查詢於=${state.inventory.queryTimeMs}",
    )
    val last = state.lastOperation
    if (last != null) {
        sb.appendLine(
            "上次操作：${last.opId} ${redactPackage(last.appKey)} " +
                "target=${last.pinnedTarget} 相位=${last.phase} 時間=${last.timeMs}",
        )
        if (last.detail.isNotEmpty()) sb.appendLine("操作細節：${last.detail}")
    } else {
        sb.appendLine("上次操作：無")
    }
    sb.appendLine(
        "Hook：${state.hookStatus} 範圍=${state.hookScopes.sorted().joinToString(",")} " +
            "marker時間=${state.hookMarkerTimeMs ?: "未知"} 查詢於=${state.hookQueriedAtMs}",
    )
    sb.appendLine("通道：偏好=${state.channel} 實際=${state.resolvedGateway} 執行身份=${state.execIdentity}")
    val redacted = redactPii(sb.toString())
    return if (redacted.length <= MAX_DIAG_EXPORT_CHARS) redacted else redacted.take(MAX_DIAG_EXPORT_CHARS)
}
