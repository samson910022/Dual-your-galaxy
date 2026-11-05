// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-7 P2 (issue #26, 階段 1):選取持久化 store (app-private,原子單鍵)。
//
// 契約:
// - 保存內容:schema 版本 + 選取來源 + 已知目標身分 (verified dualId) +
//   selected packages + 保存時間。重啟後新建 client 可重讀同一檔案。
// - 寫入原子性:單一 SharedPreferences key 一次 commit (同步落盤,同行程/
//   重啟後皆可讀;payload 僅數 KB,主執行緒 commit 可接受)。
// - 取消保存 (clear):只刪持久化條目,永不觸及裝置寫入路徑 (≠卸載)。
// - 損壞恢復:無法解析 -> 視為無選取 (不拋出);若原始字串非空則主動清除
//   損壞條目,下一次 save 覆寫即恢復可用。
// - profile 變動失效:loadSelected(currentTargetId) 僅在
//   saved.knownTargetId == currentTargetId (>0) 時回傳;不符則回傳空集合
//   並清除陳舊條目 (舊選取永不復活到新目標,需重新勾選確認)。
//
// JVM 可測:序列化/解析/失效規則皆為純函數;SelectionStore 只依賴注入的
// loadRaw/saveRaw lambdas (測試用 HashMap 後端,生產用 SharedPreferences
// 綁定見底部 selectionStore())。
package com.galaxydual.plus

import android.annotation.SuppressLint
import android.content.SharedPreferences

// 持久化檔名/鍵名 (改名即遺棄舊選取,視為破壞性變更,需審查)。
const val SELECTION_PREFS_NAME = "dual_selection"
const val SELECTION_KEY = "saved_selection"

// Schema 版本:解析時首行必須完全相符,否則視為損壞 (不做跨版遷移猜測)。
// PR-37 Phase B #2 serial 綁定：serial 以可選 `serial=` 行攜帶（header 維持
// v1，不升級）：舊快照無此行即 legacy（id-only，恢復綁定 id-only，見下）；
// 新寫入在 serial 已知時帶 serial（v2 內容），未知時省略（legacy 形狀）。
// serial 行一旦出現必須唯一且為有效整數，否則整筆視為損壞（見 parseSelection）。
const val SELECTION_SCHEMA = 1
private const val SELECTION_HEADER = "v1"

// 選取來源標記 (穩定字串,UI/測試共用)。
const val SELECTION_SOURCE_MANUAL = "manual"
const val SELECTION_SOURCE_CATALOG = "catalog"

// 已保存的一次選取快照。
data class SavedSelection(
    val schema: Int = SELECTION_SCHEMA,
    // 選取來源 (manual/catalog),僅追溯用,不參與授權。
    val source: String = SELECTION_SOURCE_MANUAL,
    // 保存時刻已知的 verified dualId (寫入目標身分,恢復時必須相符)。
    val knownTargetId: Int = -1,
    // 保存時刻已知的平台 serial (PR-37 Phase B #2 serial 綁定)：null=legacy
    // 快照（v1 無 serial 行，僅 id-only 有效）；非 null 即 v2 內容，恢復時
    // 必須與當下 serial 相符，否則舊選取永不作用到重建後的新 profile。
    val knownTargetSerial: Int? = null,
    // 已勾選 AppKey 集合 ("<serial>:<package>",見 sourceAppKey；排序後保存,
    // 跨重啟位元組穩定。舊版包名格式 (無冒號) 僅恢復側向前容忍,新寫入一律 AppKey)。
    val selectedPackages: Set<String> = emptySet(),
    // 保存時刻 wall-clock (nowMs 注入,測試決定性)。
    val savedAtMs: Long = 0L,
)

// AppKey/包名 token 白名單 (core ShellEscaper 字集 + ":" 供 AppKey 的
// "serial:package" 前綴；serial 為數字，包名仍為 dotted token。失敗行直接
// 跳過,不污染整筆)。
private val SELECTION_PKG_TOKEN = Regex("^[a-zA-Z0-9._\\-:]+$")

// 序列化 (純函數):首行為版本頭,後續 key=value / pkg= 行。
// 未知行忽略 (向前容忍),但首行版本不符整筆作廢。
// serial 行可選 (PR-37 Phase B #2)：已知才寫，legacy 快照無此行。
fun serializeSelection(s: SavedSelection): String = buildString {
    appendLine(SELECTION_HEADER)
    appendLine("source=${s.source.lines().firstOrNull().orEmpty()}")
    appendLine("target=${s.knownTargetId}")
    if (s.knownTargetSerial != null) appendLine("serial=${s.knownTargetSerial}")
    appendLine("at=${s.savedAtMs}")
    for (pkg in s.selectedPackages.sorted()) {
        appendLine("pkg=$pkg")
    }
}

// 解析 (純函數):null/空白/版本不符/target 缺失或非法 -> null (損壞)。
// 個別非法 pkg 行跳過 (不連坐整筆);target <= 0 視為損壞 (無目標的選取無意義)。
// serial 行缺失 -> legacy 快照 (僅 id-only 有效,serial 為 null)；serial 行
// 存在但無效 (非整數/空字串/超 Int 範圍/<=0) 或重複出現 -> 整筆 null (損壞，
// 不得降級為 legacy；loadSelected 對此走既有損壞自清)。
// target 行重複出現亦整筆 null (損壞，與 serial 同級；舊 last-wins 已廢除，
// 理由：重複行表寫入撕裂/手改，不可猜測以何者為準，fail closed)。
// Stage5：serial<=0 (0/負值) 與 target 重複皆視損壞 (與 target<=0 同級)。
fun parseSelection(raw: String?): SavedSelection? {
    if (raw.isNullOrBlank()) return null
    val lines = raw.lines()
    if (lines.firstOrNull()?.trim() != SELECTION_HEADER) return null
    var source = SELECTION_SOURCE_MANUAL
    var target: Int? = null
    var targetSeen = false
    var targetBroken = false
    var serial: Int? = null
    var serialSeen = false
    var serialBroken = false
    var at = 0L
    val pkgs = mutableSetOf<String>()
    for (line in lines.drop(1)) {
        val t = line.trim()
        if (t.isEmpty()) continue
        when {
            t.startsWith("source=") -> {
                val v = t.removePrefix("source=").trim()
                if (v.isNotEmpty()) source = v
            }
            t.startsWith("target=") -> {
                if (targetSeen) {
                    // 重複 target 行 (值相同亦拒)：整筆損壞，與 serial 同級。
                    targetBroken = true
                } else {
                    targetSeen = true
                    target = t.removePrefix("target=").trim().toIntOrNull()
                }
            }
            t.startsWith("serial=") -> {
                if (serialSeen) {
                    // 重複 serial 行 (值相同亦拒)：整筆損壞。
                    serialBroken = true
                } else {
                    serialSeen = true
                    val v = t.removePrefix("serial=").trim().toIntOrNull()
                    // Stage5：serial<=0 (0/負值) 視損壞 (與 target<=0 同級)，
                    // 不得接受為有效 serial。
                    if (v == null || v <= 0) serialBroken = true else serial = v
                }
            }
            t.startsWith("at=") -> {
                at = t.removePrefix("at=").trim().toLongOrNull() ?: 0L
            }
            t.startsWith("pkg=") -> {
                val pkg = t.removePrefix("pkg=").trim()
                if (SELECTION_PKG_TOKEN.matches(pkg)) pkgs.add(pkg)
            }
            // 未知行:向前容忍,忽略。
        }
    }
    if (serialBroken) return null
    if (targetBroken) return null
    val targetId = target ?: return null
    if (targetId <= 0) return null
    return SavedSelection(
        schema = SELECTION_SCHEMA,
        source = source,
        knownTargetId = targetId,
        knownTargetSerial = serial,
        selectedPackages = pkgs,
        savedAtMs = at,
    )
}

// 恢復有效性 (純函數):保存的已知目標必須等於當前 verified 目標,
// 且當前目標必須為合法 dualId (>0,owner 永不接受)。
// PR-37 Phase B #2 serial 綁定：v2 快照（knownTargetSerial 非 null）另須
// 當下 serial 相符（null 當下視為不可驗證，直接無效）；legacy 快照
// （serial null）僅比 id（恢復側綁定 id-only，批次拒絕靜默提升為完全體）。
// 無效/重複 serial 行不產生 SavedSelection（parse 側整筆拒絕），故此處
// null 分支嚴格等於「無 serial 行」的 legacy，不含任何損壞降級。
fun isSelectionValidFor(
    saved: SavedSelection,
    currentTargetId: Int,
    currentSerial: Int? = null,
): Boolean {
    if (currentTargetId <= 0 || saved.knownTargetId != currentTargetId) return false
    val savedSerial = saved.knownTargetSerial ?: return true
    return currentSerial != null && savedSerial == currentSerial
}

// 原子選取 store。loadRaw/saveRaw 注入:
// - 生產:SharedPreferences 單鍵 commit (見底部綁定)。
// - 測試:HashMap 後端。
// saveRaw 回傳寫入是否成功;任何拋出由呼叫端 (ViewModel) 捕捉轉為訊息,
// 本類自身方法除 saveRaw 拋出外永不拋出。
class SelectionStore(
    private val loadRaw: () -> String?,
    private val saveRaw: (String?) -> Boolean,
) {
    // 讀原始快照 (損壞 -> null)。
    fun loadSnapshot(): SavedSelection? = try {
        parseSelection(loadRaw())
    } catch (_: Exception) {
        null
    }

    // 讀已勾選 AppKey:損壞/目標不符皆回傳空集合 (fail closed)。
    // 副作用 (fail-closed 已文件化,有意為之):
    // - 損壞 (有原始字串但解析失敗):主動清除損壞條目,下次 save 覆寫即恢復。
    // - 目標不符 (profile 變動/serial 重建):清除陳舊條目,舊選取永不復活。
    // - currentTargetId <= 0 (無目標):直接回空且不觸碰存檔 (early return 在
    //   讀取前,絕不刪除；未知目標的讀取永不破壞已存資料)。
    // - PR-37 Phase B #2：currentSerial 為當下 verified serial（null=不可驗證）；
    //   v2 快照須相符才有效（同 id 重建的新 serial 永不沿用舊選取），legacy
    //   快照僅比 id（恢復側綁定 id-only，見 ViewModel，不靜默提升）。
    // - Stage5 (SerialUnavailable 誤刪修正)：currentSerial==null 對 v2 僅回空、
    //   不清除 (unverifiable vs verified-mismatch 區分；不可驗證時保留存檔待
    //   serial 可用後再判定，僅已驗證不符才清除)。
    fun loadSelected(currentTargetId: Int, currentSerial: Int? = null): Set<String> =
        loadValidSnapshot(currentTargetId, currentSerial)?.selectedPackages ?: emptySet()

    // 單次讀取有效快照 (Stage5 併發收斂：restore 單次 I/O 用此入口，
    // selectedPackages＋knownTargetSerial 同快照取得，免雙重 I/O；
    // loadSelected 亦委派至此，單一真相來源)。
    // 語義與 loadSelected 完全一致 (含自清)：損壞/已驗證不符清除回空；
    // 唯 v2＋currentSerial==null (不可驗證) 回 null 且不清除。
    fun loadValidSnapshot(currentTargetId: Int, currentSerial: Int? = null): SavedSelection? {
        if (currentTargetId <= 0) return null
        val raw: String? = try {
            loadRaw()
        } catch (_: Exception) {
            return null
        }
        val saved = try {
            parseSelection(raw)
        } catch (_: Exception) {
            null
        }
        if (saved == null) {
            if (!raw.isNullOrBlank()) {
                try {
                    saveRaw(null)
                } catch (_: Exception) {
                    // Best effort:下次 save 覆寫即恢復。
                }
            }
            return null
        }
        if (!isSelectionValidFor(saved, currentTargetId, currentSerial)) {
            // Stage5：不可驗證 (v2＋當下 serial 未知) 不清除，僅回空。
            if (saved.knownTargetSerial != null && currentSerial == null) return null
            try {
                saveRaw(null)
            } catch (_: Exception) {
                // Best effort:陳舊條目下次 save 覆寫。
            }
            return null
        }
        return saved
    }

    // 保存 (原子單鍵 commit)。targetId <= 0 拒絕 (無目標不存)。
    // PR-37 Phase B #2：targetSerial 為保存時刻 verified serial（null=未知，
    // 寫 legacy 形狀，恢復綁定 id-only）；已知才寫 serial 行（v2 內容）。
    // Stage5 縱深：save 側過濾非法 pkg token (與 parse 同白名單)，VM 層已有
    // catalog 過濾，生產安全，此為第二道 (非法行直接捨去，不連坐整筆)。
    fun save(
        source: String,
        targetId: Int,
        selected: Set<String>,
        nowMs: Long,
        targetSerial: Int? = null,
    ): Boolean {
        if (targetId <= 0) return false
        // Stage5：非正 serial 永不落盤 (與 parse 同級；呼叫端誤傳 0/負值時
        // 退為 legacy 形狀，不寫出自毀條目)。
        val safeSerial = if (targetSerial != null && targetSerial <= 0) null else targetSerial
        val filtered = selected.filterTo(mutableSetOf()) { SELECTION_PKG_TOKEN.matches(it) }
        val payload = serializeSelection(
            SavedSelection(
                schema = SELECTION_SCHEMA,
                source = source.ifBlank { SELECTION_SOURCE_MANUAL },
                knownTargetId = targetId,
                knownTargetSerial = safeSerial,
                selectedPackages = filtered.toSet(),
                savedAtMs = nowMs,
            ),
        )
        return try {
            saveRaw(payload)
        } catch (_: Exception) {
            false
        }
    }

    // 取消保存:只刪持久化條目,不碰任何裝置寫入路徑 (≠卸載,見 ViewModel
    // clearSelectionLocal/cancelSavedSelection 註解)。
    fun clear(): Boolean = try {
        saveRaw(null)
    } catch (_: Exception) {
        false
    }
}

// 生產綁定:app-private SharedPreferences (MODE_PRIVATE),單鍵同步 commit。
// 寫入後同檔新建 client (新 SelectionStore 實例) 可重讀 (同一 prefs 檔)。
// 有意用 commit() 而非 apply():需同步落盤語義 (取消保存/損壞自清後立即一致);
// payload 僅數 KB,且 DualViewModel 經 onIo (Dispatchers.IO) 呼叫 save/clear/
// loadSelected,故同步落盤不阻塞主執行緒。
@SuppressLint("ApplySharedPref")
fun SharedPreferences.selectionStore(): SelectionStore = SelectionStore(
    loadRaw = { getString(SELECTION_KEY, null) },
    saveRaw = { raw ->
        if (raw == null) edit().remove(SELECTION_KEY).commit()
        else edit().putString(SELECTION_KEY, raw).commit()
    },
)
