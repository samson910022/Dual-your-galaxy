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
// - #76 可靠性:save() 如實回傳 commit() 的 Boolean，並讓例外原樣傳播給 caller；
//   從此「記憶體 active」不再等於「已證實落盤」。commit(false)/例外時，本 store
//   於行程內記住 UNKNOWN-active，讓下一次 load() 偏向重複警告，下一次 mark 可重試。
// - 讀取失敗 (含任何 Throwable) -> fail-closed toward warning:active=true
//   (舊版回 inactive 會藏起一則已持久化警告，與「偏向重複警告」矛盾)；
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
// 不含 Android 型別:JVM fake 仍可直接實作 (save 回報是否證實落盤)。
internal interface RepairHintStore {
    fun load(): RepairHintState

    /** true = commit() 證實落盤；false = 未證實 (caller 必須偏向警告並可重試)。 */
    fun save(state: RepairHintState): Boolean

    /**
     * true = 本次 load() 曾猜測 (讀取例外或先前 save 未證實)，呼叫端不得把
     * [load] 的 active 當成已驗證的 durable 狀態。JVM fake 預設為 false。
     */
    fun loadWasRecovered(): Boolean = false
}

// 生產綁定:app-private SharedPreferences (MODE_PRIVATE),三鍵同步 commit。
// #76:不再吞掉 commit(false)/例外。行程內的 UNKNOWN-active 與最後一次嘗試的
// state 只作為失敗時的 fallback，絕不寫入 prefs 以外的持久化位置。
@SuppressLint("ApplySharedPref")
internal fun SharedPreferences.repairHintStore(): RepairHintStore = object : RepairHintStore {
    // 最近一次 save 的意圖 (不論成功與否)；只用於讀失敗/未證實時保留單調 generation。
    private var lastKnown: RepairHintState? = null
    // 曾有一次 active=true 的 save 未證實落盤 -> 重啟後仍要警告，直到成功 save 覆寫。
    private var uncertainActive = false
    // 最近一次 load 是否為猜测 (協調器因此不得 early-return 跳過重試)。
    private var recoveredOnLoad = false

    @Synchronized
    override fun load(): RepairHintState {
        val remembered = lastKnown
        var recovered = false

        val active = try {
            getBoolean(REPAIR_HINT_KEY_ACTIVE, false)
        } catch (_: Throwable) {
            recovered = true
            // fail-closed:讀不到不代表可以隱藏警告。
            true
        }
        val generation = try {
            getLong(REPAIR_HINT_KEY_GENERATION, 0L)
        } catch (_: Throwable) {
            recovered = true
            remembered?.generation ?: 0L
        }
        val repairedVersion = try {
            getLong(REPAIR_HINT_KEY_REPAIRED_VERSION, -1L)
        } catch (_: Throwable) {
            recovered = true
            remembered?.repairedVersion ?: -1L
        }

        recoveredOnLoad = recovered || uncertainActive
        return RepairHintState(
            active = active || uncertainActive,
            generation = maxOf(generation, remembered?.generation ?: 0L),
            repairedVersion = repairedVersion,
        )
    }

    @Synchronized
    override fun save(state: RepairHintState): Boolean {
        lastKnown = state
        return try {
            val committed = edit()
                .putBoolean(REPAIR_HINT_KEY_ACTIVE, state.active)
                .putLong(REPAIR_HINT_KEY_GENERATION, state.generation)
                .putLong(REPAIR_HINT_KEY_REPAIRED_VERSION, state.repairedVersion)
                .commit()
            if (committed) {
                uncertainActive = false
            } else if (state.active) {
                uncertainActive = true
            }
            committed
        } catch (t: Throwable) {
            if (state.active) uncertainActive = true
            // 如實傳播:呼叫端 (coordinator) 將其視為未證實並保留 live 警告。
            throw t
        }
    }

    @Synchronized
    override fun loadWasRecovered(): Boolean = recoveredOnLoad
}
