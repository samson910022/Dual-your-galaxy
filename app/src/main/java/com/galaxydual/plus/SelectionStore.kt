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
    // 已勾選包名集合 (排序後保存,跨重啟位元組穩定)。
    val selectedPackages: Set<String> = emptySet(),
    // 保存時刻 wall-clock (nowMs 注入,測試決定性)。
    val savedAtMs: Long = 0L,
)

// 包名 token 白名單 (與 core ShellEscaper 同字集:失敗行直接跳過,不污染整筆)。
private val SELECTION_PKG_TOKEN = Regex("^[a-zA-Z0-9._-]+$")

// 序列化 (純函數):首行為版本頭,後續 key=value / pkg= 行。
// 未知行忽略 (向前容忍),但首行版本不符整筆作廢。
fun serializeSelection(s: SavedSelection): String = buildString {
    appendLine(SELECTION_HEADER)
    appendLine("source=${s.source.lines().firstOrNull().orEmpty()}")
    appendLine("target=${s.knownTargetId}")
    appendLine("at=${s.savedAtMs}")
    for (pkg in s.selectedPackages.sorted()) {
        appendLine("pkg=$pkg")
    }
}

// 解析 (純函數):null/空白/版本不符/target 缺失或非法 -> null (損壞)。
// 個別非法 pkg 行跳過 (不連坐整筆);target <= 0 視為損壞 (無目標的選取無意義)。
fun parseSelection(raw: String?): SavedSelection? {
    if (raw.isNullOrBlank()) return null
    val lines = raw.lines()
    if (lines.firstOrNull()?.trim() != SELECTION_HEADER) return null
    var source = SELECTION_SOURCE_MANUAL
    var target: Int? = null
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
                target = t.removePrefix("target=").trim().toIntOrNull()
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
    val targetId = target ?: return null
    if (targetId <= 0) return null
    return SavedSelection(
        schema = SELECTION_SCHEMA,
        source = source,
        knownTargetId = targetId,
        selectedPackages = pkgs,
        savedAtMs = at,
    )
}

// 恢復有效性 (純函數):保存的已知目標必須等於當前 verified 目標,
// 且當前目標必須為合法 dualId (>0,owner 永不接受)。
fun isSelectionValidFor(saved: SavedSelection, currentTargetId: Int): Boolean =
    currentTargetId > 0 && saved.knownTargetId == currentTargetId

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

    // 讀已勾選包名:損壞/目標不符皆回傳空集合 (fail closed)。
    // - 損壞 (有原始字串但解析失敗):主動清除損壞條目,下次 save 即恢復。
    // - 目標不符 (profile 變動):清除陳舊條目,舊選取永不復活。
    fun loadSelected(currentTargetId: Int): Set<String> {
        val raw: String? = try {
            loadRaw()
        } catch (_: Exception) {
            return emptySet()
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
            return emptySet()
        }
        if (!isSelectionValidFor(saved, currentTargetId)) {
            try {
                saveRaw(null)
            } catch (_: Exception) {
                // Best effort:陳舊條目下次 save 覆寫。
            }
            return emptySet()
        }
        return saved.selectedPackages
    }

    // 保存 (原子單鍵 commit)。targetId <= 0 拒絕 (無目標不存)。
    fun save(source: String, targetId: Int, selected: Set<String>, nowMs: Long): Boolean {
        if (targetId <= 0) return false
        val payload = serializeSelection(
            SavedSelection(
                schema = SELECTION_SCHEMA,
                source = source.ifBlank { SELECTION_SOURCE_MANUAL },
                knownTargetId = targetId,
                selectedPackages = selected.toSet(),
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
// payload 僅數 KB,呼叫方已在 IO dispatcher,主執行緒阻塞可接受。
@SuppressLint("ApplySharedPref")
fun SharedPreferences.selectionStore(): SelectionStore = SelectionStore(
    loadRaw = { getString(SELECTION_KEY, null) },
    saveRaw = { raw ->
        if (raw == null) edit().remove(SELECTION_KEY).commit()
        else edit().putString(SELECTION_KEY, raw).commit()
    },
)
