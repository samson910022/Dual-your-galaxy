// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R6-2 (G02):App-private 維修提示持久化 store。
//
// 契約 (mirrors SelectionStore.kt conventions):
// - 保存內容:提示是否作用中 (active) + 單調世代 (generation) + 觸發修復的
//   版本號 (repairedVersion)。只有這三個鍵，位於 app-private 檔案，
//   絕不寫入 dual_policy remote prefs (不產生 poison fence / delta 污染)。
// - 寫入原子性:三個鍵在同一次 SharedPreferences edit commit 同步落盤。
//   有意用 commit() 而非 apply():重啟後新建 coordinator 必須立即讀到同一標記。
// - 讀取失敗 (含任何 Throwable) -> 回預設 inactive (fail-safe 偏向「重複警告」)；
//   讀取端永不因讀失敗而自動清除已存在的提示。
// - 本 store 是純粹的持久化適配器，不含任何 hook PID / high-water 推導
//   (無 IPC surface；此類推導需 binder 合約 + 裝置驗證，明確非本輪範圍)。
//
// JVM 可測:介面 load/save 由 Map-backed fake 實作；生產綁定見底部 repairHintStore()。
package com.galaxydual.plus

import android.annotation.SuppressLint
import android.content.SharedPreferences

// 持久化檔名/鍵名 (改名即遺棄舊標記,視為破壞性變更,需審查)。
internal const val REPAIR_HINT_PREFS_NAME = "dual_policy_repair_hint"
internal const val REPAIR_HINT_KEY_ACTIVE = "active"
internal const val REPAIR_HINT_KEY_GENERATION = "generation"
internal const val REPAIR_HINT_KEY_REPAIRED_VERSION = "repaired_version"

// 持久化標記。generation 單調 (ack 不遞減)；repairedVersion = -1 表示未提供版本
// (production DualViewModel 目前以無參數 markRepairHint() 呼叫)。
internal data class RepairHintState(
    val active: Boolean,
    val generation: Long,
    val repairedVersion: Long = -1L,
)

// 持久化埠。null store (預設) = 純記憶體 coordinator,既有測試呼叫點行為不變。
internal interface RepairHintStore {
    fun load(): RepairHintState
    fun save(state: RepairHintState)
}

// 生產綁定:app-private SharedPreferences (MODE_PRIVATE),三鍵同步 commit。
// load 攔截一切 Throwable -> 預設 inactive；save best-effort (失敗由 coordinator
// 以記憶體 live 狀態兜底,偏向重複警告)。
@SuppressLint("ApplySharedPref")
internal fun SharedPreferences.repairHintStore(): RepairHintStore = object : RepairHintStore {
    override fun load(): RepairHintState = try {
        RepairHintState(
            active = getBoolean(REPAIR_HINT_KEY_ACTIVE, false),
            generation = getLong(REPAIR_HINT_KEY_GENERATION, 0L),
            repairedVersion = getLong(REPAIR_HINT_KEY_REPAIRED_VERSION, -1L),
        )
    } catch (_: Throwable) {
        // fail-safe:讀取失敗視為未確認,絕不因讀失敗而自動清除提示。
        RepairHintState(active = false, generation = 0L, repairedVersion = -1L)
    }

    override fun save(state: RepairHintState) {
        try {
            edit()
                .putBoolean(REPAIR_HINT_KEY_ACTIVE, state.active)
                .putLong(REPAIR_HINT_KEY_GENERATION, state.generation)
                .putLong(REPAIR_HINT_KEY_REPAIRED_VERSION, state.repairedVersion)
                .commit()
        } catch (_: Throwable) {
            // Best effort:下一次 save 覆寫即恢復。
        }
    }
}
