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
//   從此「記憶體 active」不再等於「已證實落盤」。
// - PR #89 Phase B (B1):Android SharedPreferences.commit() 在 commitToMemory()
//   階段先更新行程內 mMap，且磁碟寫失敗時不回滾。一次失敗的 CLEAR
//   (save(active=false) -> commit(false)) 因此會留下「記憶體 inactive、磁碟仍
//   active」——只看 state.active 的舊 uncertainActive 無法標記這種危險狀態。
//   改用單一 durabilityUnknown:任何未證實的 save (commit(false) 或拋出) 都讓
//   整份標記變成 UNKNOWN，與寫入的 active 值無關；只有下一次成功的 commit
//   才清除。load() 只要 durabilityUnknown 或任一鍵讀取拋出，就 fail-closed
//   回 active=true + recovered=true。
// - 讀取失敗 (含任何 Throwable) -> fail-closed toward warning:active=true
//   (舊版回 inactive 會藏起一則已持久化警告，與「偏向重複警告」矛盾)；
//   讀取端永不因讀失敗而自動清除已存在的提示。
// - 本 store 是純粹的持久化適配器，不含任何 hook PID / high-water 推導
//   (無 IPC surface；此類推導需 binder 合約 + 裝置驗證，明確非本輪範圍)。
//
// JVM 可測:介面 load/save 由 Map-backed fake 實作；生產綁定見底部 repairHintStore()。
// 生產儲存完全不可用時用 failClosedRepairHintStore() (與 JVM 的 null/no-store 模式不同)。
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

// 單次 load() 的決定。verified 與 state 放在同一個值裡，讓「猜測讀取」不可能
// 被當成已證實的 inactive:verified=false 時 active 必為 true (fail-closed)。
// asState() 是唯一出口，caller 無需自行重導這個不變量。
internal data class RecoveryResult(
    val active: Boolean,
    val generation: Long,
    val repairedVersion: Long,
    val verified: Boolean,
) {
    fun asState(): RepairHintState = RepairHintState(active, generation, repairedVersion)
}

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
// #76:不再吞掉 commit(false)/例外。行程內的 durabilityUnknown 與最後一次嘗試的
// state 只作為失敗時的 fallback，絕不寫入 prefs 以外的持久化位置。
@SuppressLint("ApplySharedPref")
internal fun SharedPreferences.repairHintStore(): RepairHintStore = object : RepairHintStore {
    // 最近一次 save 的意圖 (不論成功與否)；只用於讀失敗/未證實時保留單調 generation。
    private var lastKnown: RepairHintState? = null
    // B1:任何未證實的 save 都讓「整份標記」未知，而不是只有 active=true 才未知。
    // 失敗的 CLEAR 是最危險的情形:SharedPreferences 已把 mMap 改為 false，但
    // 磁碟仍是 true；若標記為已證實 inactive，行程內重建/冷載入都會藏起警告。
    private var durabilityUnknown = false
    // 最近一次 load 是否為猜測 (協調器因此不得 early-return 跳過重試)。
    private var recoveredOnLoad = false

    @Synchronized
    override fun load(): RepairHintState {
        val remembered = lastKnown
        var readThrew = false

        val active = try {
            getBoolean(REPAIR_HINT_KEY_ACTIVE, false)
        } catch (_: Throwable) {
            readThrew = true
            // 佔位值；verified=false 時會被 fail-closed 的 true 覆寫。
            false
        }
        val generation = try {
            getLong(REPAIR_HINT_KEY_GENERATION, 0L)
        } catch (_: Throwable) {
            readThrew = true
            remembered?.generation ?: 0L
        }
        val repairedVersion = try {
            getLong(REPAIR_HINT_KEY_REPAIRED_VERSION, -1L)
        } catch (_: Throwable) {
            readThrew = true
            remembered?.repairedVersion ?: -1L
        }

        // 任一鍵讀取失敗 OR 先前有未證實的 save -> 整份標記 UNKNOWN。
        val verified = !readThrew && !durabilityUnknown
        val result = RecoveryResult(
            active = if (verified) active else true, // fail-closed unknown-active
            generation = maxOf(generation, remembered?.generation ?: 0L),
            repairedVersion = repairedVersion,
            verified = verified,
        )
        recoveredOnLoad = !result.verified
        return result.asState()
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
            // 只有成功 commit 才證實；commit(false) 的 mMap 更新不代表落盤。
            durabilityUnknown = !committed
            committed
        } catch (t: Throwable) {
            durabilityUnknown = true
            // 如實傳播:呼叫端 (coordinator) 將其視為未證實並保留 live 警告。
            throw t
        }
    }

    @Synchronized
    override fun loadWasRecovered(): Boolean = recoveredOnLoad || durabilityUnknown
}

// B3:生產環境取得 app-private prefs 失敗時的 fail-closed stub。
// 與 Coordinator 的 null store 完全不同:null 是「明確的 JVM/in-memory/no-store
// 模式」(沒有持久化意圖)；本 stub 代表「生產儲存不可用」，必須偏向警告、不得
// 讓 hint 在重啟後靜默消失，且 save() 如實回傳 false (未證實、可重試)。
internal fun failClosedRepairHintStore(): RepairHintStore = object : RepairHintStore {
    @Synchronized
    override fun load(): RepairHintState = RepairHintState(active = true, generation = 0L, repairedVersion = -1L)

    @Synchronized
    override fun save(state: RepairHintState): Boolean = false // 沒有磁碟可寫；永不證實

    @Synchronized
    override fun loadWasRecovered(): Boolean = true
}

// B3: Acquisition boundary (framework-free, so JVM tests can pin it). Returns the
// acquired store verbatim when acquisition succeeds; a null result OR ANY throwable
// (including Error) maps to [failClosedRepairHintStore]. Production acquisition via
// SharedPreferences is the only intended caller, but the seam deliberately knows
// nothing about Android: callers supply their own `acquire` lambda.
//
// Returning null on acquisition failure would silently degrade the coordinator to
// its no-store mode (persistRepairHint early-return), dropping every warning on
// restart; that is fail-open and must never happen in production.
internal fun repairHintStoreOrFailClosed(acquire: () -> RepairHintStore?): RepairHintStore =
    try {
        acquire() ?: failClosedRepairHintStore()
    } catch (_: Throwable) {
        failClosedRepairHintStore()
    }
