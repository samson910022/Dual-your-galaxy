// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-1 (M1)：分身資料風險揭露的一次性 acknowledgement store 與兩個對話框。
//
// 契約 (mirrors RepairHintStore.kt conventions):
// - 保存內容:單一 boolean (acknowledged)，位於 app-private 檔案
//   "dual_policy_risk_disclosure"，絕不寫入 dual_policy remote prefs
//   (不產生 poison fence / delta 污染)。
// - 寫入原子性:單鍵 edit().putBoolean(...).commit() 落盤。#76:改用
//   commit() 的 Boolean 如實回報 durable 與否，不再吞掉 false/例外。
// - #76 IO ownership:commit() 在行程級單執行緒 daemon executor 上執行
//   (serialized IO owner)，不在呼叫執行緒 (UI) 上做磁碟寫入；呼叫端只做一個
//   有上限的短等待以取得落盤結果，逾時/失敗一律回 false (偏向再顯示風險)。
// - 讀取失敗 (含任何 Throwable) -> false (fail-safe:偏向「再顯示一次風險」)；
//   讀取端永不因讀失敗而自動標記已確認。
// - RiskAckStore 介面與 shouldShowRiskGate 為純 Kotlin seam (無 Android 型別)，
//   JVM 可用 Map-backed fake 直接測試；Android framework 僅出現在 SharedPreferences
//   綁定 (以下以完整名稱限定，不進 import) 與兩個對話框函式。
// - #78:兩個對話框 helper 回傳 AlertDialog?（show 的 handle，失敗/dying host 為
//   null），由呼叫的 Activity 持有並在 onDestroy best-effort dismiss。Main 仍用
//   同語意的 private wrapper 持有 handle，Editor 直接保存本檔 helper 的回傳值。
// - PR #89 Phase D1 (R2, fail-open):SharedPreferences 是行程內快取。AOSP 的
//   EditorImpl.commit() 先 commitToMemory() 更新 mMap (getBoolean 立刻變 true)，
//   之後才寫磁碟；commit(false)/寫入失敗/逾時都不回滾 mMap。兩個 Activity 共用
//   同一個 prefs instance，所以舊版 isAcknowledged() 直接 getBoolean() 會在
//   「ack 已失敗」之後的下一次進入偷偷跳過風險 gate —— fail-open。改由行程級
//   [ProcessRiskAckState] 單一來源決定，prefs 只負責 durable seed 與 commit。
package com.galaxydual.plus

import android.app.Activity
import android.app.AlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 持久化檔名/鍵名 (改名即遺棄舊標記,視為破壞性變更,需審查)。
internal const val RISK_ACK_PREFS_NAME = "dual_policy_risk_disclosure"
internal const val RISK_ACK_KEY = "acknowledged"

// PR #89 Phase D1 (R2):行程級 acknowledgement owner，完全不含 Android 型別
// (JVM 測試可直接驅動)。所有 riskAckStore() wrapper 共用同一個實例 —— gate 的
// 狀態不能由任一個 SharedPreferences wrapper 的行程內 mMap 推導。
//
// WHY (AOSP 驗證):android.content.SharedPreferencesImpl.EditorImpl.commit()
// 先 commitToMemory() 更新行程內 mMap，再寫磁碟；磁碟寫失敗 (commit(false)) 或
// 例外都不回滾 mMap。所以失敗的 ack 會留下「記憶體 true、磁碟 false」，下一次
// getBoolean(RISK_ACK_KEY) 直接回 true，跳過風險 gate —— 與「失敗保持 gate 開啟」
// 的契約相反。本 owner 只把「已證實落盤」當成 CONFIRMED。
//
// 狀態機 (所有轉換在同一把 lock 下完成，見各方法的 @Synchronized)：
//   UNINITIALIZED — 尚無 store 被建構；isAcknowledged() = false (fail closed)。
//   UNKNOWN       — durable seed 為 false，或最新一筆寫入失敗/逾時。gate 保持開啟。
//   PENDING       — 有一筆寫入進行中。gate 保持開啟。
//   CONFIRMED     — commit() 回 true 已證實落盤 (或遲到成功且無更新寫入)。gate 關閉。
//
// 轉換語意：
//   seedFromDurable(durable) — LATCHED，只在「本行程第一個 store 建構時」生效，
//       讀的是本行程寫任何東西之前的 durable 值。之後的呼叫一律忽略，避免被
//       失敗 commit 污染的 mMap 重新 seed 成 CONFIRMED。
//   beginWrite() — 單調遞增 writeId 並進入 PENDING；回傳的 id 是這次寫入的令牌。
//   confirm(id)  — 只有「當前 id」能 PENDING -> CONFIRMED。遲到成功 (呼叫端已因
//       逾時回 false、owner 已在 UNKNOWN) 在 id 仍是當前值時也被接受：沒有更新的
//       寫入，代表磁碟現在確實是 true，下次進入可以安全地關閉 gate。若其間已有
//       更新的寫入 (id 已推進)，遲到成功一律忽略 —— 不能讓舊寫入覆蓋較新狀態。
//   fail(id)     — 只有當前 id 能 PENDING -> UNKNOWN；CONFIRMED 不被舊的 fail 降級。
//   isAcknowledged() — 只有 CONFIRMED 回 true。
internal object ProcessRiskAckState {
    private enum class State { UNINITIALIZED, UNKNOWN, PENDING, CONFIRMED }

    private var state: State = State.UNINITIALIZED
    private var seeded = false
    private var currentWriteId = 0L

    @Synchronized
    internal fun seedFromDurable(durable: Boolean) {
        if (seeded) return // latched:本行程只認第一次 (寫入前) 的 durable seed。
        seeded = true
        state = if (durable) State.CONFIRMED else State.UNKNOWN
    }

    @Synchronized
    internal fun beginWrite(): Long {
        if (!seeded) {
            // 防禦:沒有 durable seed 之前不得寫入；一律先當成未確認 (fail closed)。
            seeded = true
            state = State.UNKNOWN
        }
        currentWriteId++
        state = State.PENDING
        return currentWriteId
    }

    @Synchronized
    internal fun confirm(writeId: Long) {
        if (writeId <= 0L || writeId != currentWriteId) return // 過期/未知 id。
        // PENDING = 正常如時成功；UNKNOWN = 逾時/失敗後的遲到成功，且沒有更新的
        // 寫入開始。兩種情況都代表磁碟現在是 true，可以證實。
        if (state == State.PENDING || state == State.UNKNOWN) {
            state = State.CONFIRMED
        }
    }

    @Synchronized
    internal fun fail(writeId: Long) {
        if (writeId <= 0L || writeId != currentWriteId) return // 過期/未知 id。
        // 只降級進行中的寫入；已 CONFIRMED 的狀態不能被舊 fail 覆蓋。
        if (state == State.PENDING) {
            state = State.UNKNOWN
        }
    }

    @Synchronized
    internal fun isAcknowledged(): Boolean = state == State.CONFIRMED

    /** 測試用 seam:觀察是否曾配置寫入令牌 (cancel 路徑必須保持 0)。 */
    @Synchronized
    internal fun currentWriteIdForTests(): Long = currentWriteId

    /**
     * 測試專用 seam:回到 uninitialized (JVM 單元測試的 @Before/@After)。
     * 生產程式碼不得呼叫 —— gate 的單一來源不能在執行中被人為清空。
     */
    @Synchronized
    internal fun resetForTests() {
        state = State.UNINITIALIZED
        seeded = false
        currentWriteId = 0L
    }

    /**
     * 測試專用 seam:完整重置 (含 latch 與 write id)，並立刻以給定的 durable 值
     * 重新 seed —— 等同於「以該 durable 值冷啟動」。
     *
     * androidTest 的 helper 直接改 SharedPreferences，不會經過 riskAckStore()
     * 的 latched seed；若不同步重設本 owner，第一個 store 建構時 latch 住的
     * CONFIRMED/UNKNOWN 會留存整個 app process，使 gate 測試無法重置。
     * 呼叫點必須在 prefs edit() 之後立刻呼叫，讓 durable 與 owner 保持一致。
     * 生產程式碼不得呼叫。
     */
    @Synchronized
    internal fun reseedForTests(durable: Boolean) {
        resetForTests()
        seedFromDurable(durable)
    }
}

// 單執行緒 serialized IO owner。daemon + 永不 shutdown (與 transactExecutor 相同
// 的 process-lifetime 模式)，故單一卡住的 commit 只會佔住這條 IO 執行緒，
// 不會阻塞 UI 執行緒，也不會讓每個 ack 各自建立 executor。
internal object RiskAckIoOwner {
    val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "risk-ack-io").apply { isDaemon = true }
    }
}

// 呼叫端等待落盤報告的上限 (ms)。真正磁碟寫作在 IO owner 上；此上限只用來
// 決定要不要把結果當成「已證實」。逾時即回 false，偏向再次顯示風險。
//
// jank trade-off (PR4 review Optional 4):這「不是 zero-blocking」—— 呼叫端 (UI)
// 最多同步等待約 400ms (RISK_ACK_IO_WAIT_MS) 以取得真實的 commit 結果。#76 的
// RED test 要求 truthful result (不能 fire-and-forget 後一律回 true)，故此等待
// 是「如實結果」下的最小取舍：再短會把正常磁碟寫入誤判為失敗 (偏向重複顯示風險)，
// 再長只徒增 UI jank。磁碟寫本身在 [RiskAckIoOwner] 的單執行緒上，不阻塞 UI 的
// 其它工作；超過上限即如實回 false，下次進入仍會再顯示風險。
internal const val RISK_ACK_IO_WAIT_MS = 400L

// 持久化埠:不含任何 Android 型別,JVM 可以 Map-backed fake 直接實作。
internal interface RiskAckStore {
    fun isAcknowledged(): Boolean

    /** true = commit() 證實落盤；false = 未證實 (失敗/例外/逾時)，風險仍須再顯示。 */
    fun markAcknowledged(): Boolean
}

// 純函式 seam:單一來源,進入點與測試共用。未確認即顯示 gate。
internal fun shouldShowRiskGate(acknowledged: Boolean): Boolean = !acknowledged

// 生產綁定:app-private SharedPreferences (MODE_PRIVATE 由呼叫端 getSharedPreferences
// 建立),單鍵 commit 在 background IO owner 上執行。gate 狀態由行程級
// [ProcessRiskAckState] 單一來源決定，絕不直接讀 prefs (其 mMap 會被失敗的
// commit 污染)。`dispatch` / `ioWaitMs` 為 JVM 測試 seam:
//   dispatch = { it() } 可讓測試同步執行寫入並如實拿到 commit 結果；
//   預設使用 RiskAckIoOwner 的單執行緒 executor，呼叫端只做有上限的短等待。
@android.annotation.SuppressLint("ApplySharedPref")
internal fun android.content.SharedPreferences.riskAckStore(
    dispatch: ((() -> Unit) -> Unit)? = null,
    ioWaitMs: Long = RISK_ACK_IO_WAIT_MS,
): RiskAckStore {
    // PR #89 D1:durable seed 必須在「本行程任何 commit 之前」、第一個 store 建構時
    // 讀取。讀取失敗 -> false (fail closed)。owner 的 latch 會忽略之後的建構，
    // 所以即使失敗 commit 已把 mMap 改成 true，也不會重新 seed 成 CONFIRMED。
    val durableAtConstruction = try {
        getBoolean(RISK_ACK_KEY, false)
    } catch (_: Throwable) {
        false
    }
    ProcessRiskAckState.seedFromDurable(durableAtConstruction)

    return object : RiskAckStore {
        // 刻意不讀 prefs:AOSP commitToMemory() 在磁碟寫之前就更新 mMap，且失敗
        // 不回滾；直接 getBoolean() 會讓失敗的 ack 在下次進入時 fail-open。
        override fun isAcknowledged(): Boolean = ProcessRiskAckState.isAcknowledged()

        override fun markAcknowledged(): Boolean {
            val writeId = ProcessRiskAckState.beginWrite()
            val done = CountDownLatch(1)
            val committed = AtomicBoolean(false)
            val submit = dispatch ?: { task: () -> Unit -> RiskAckIoOwner.executor.execute(task) }
            val submitted = try {
                submit {
                    try {
                        val ok = edit().putBoolean(RISK_ACK_KEY, true).commit()
                        committed.set(ok)
                        // 只有證實落盤才能確認；false/例外一律保持 gate 開啟。
                        // 遲到成功 (呼叫端已逾時) 由 owner 的 current-id 規則處理。
                        if (ok) ProcessRiskAckState.confirm(writeId) else ProcessRiskAckState.fail(writeId)
                    } catch (_: Throwable) {
                        committed.set(false)
                        ProcessRiskAckState.fail(writeId)
                    } finally {
                        done.countDown()
                    }
                }
                true
            } catch (_: Throwable) {
                // executor 拒絕/背景派發失敗:如實回未證實並釋放本次 write id。
                false
            }
            if (!submitted) {
                ProcessRiskAckState.fail(writeId)
                return false
            }
            val settled = try {
                done.await(ioWaitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!settled || !committed.get()) {
                // 逾時/中斷/未證實:本次進入保持 gate 開啟 (失敗導向)。若稍後
                // commit 真的成功，owner 仍可能接受遲到成功 (見 ProcessRiskAckState)。
                ProcessRiskAckState.fail(writeId)
                return false
            }
            return true
        }
    }
}

// 可重新開啟的完整風險說明:無狀態、不標記 acknowledgement。
// #78:回傳 show() 的 handle 供宿主 Activity 在 onDestroy 釋放；失敗即 null。
internal fun showRiskDetailsDialog(activity: Activity): AlertDialog? {
    if (activity.isFinishing || activity.isDestroyed) return null
    return try {
        AlertDialog.Builder(activity)
            .setTitle(R.string.risk_details_title)
            .setMessage(R.string.risk_details_full)
            .setPositiveButton(R.string.dialog_close, null)
            .setCancelable(true)
            .show()
    } catch (_: Exception) {
        // Activity window 在 guard 與 show 之間消失:fail-safe,不改變任何狀態。
        null
    }
}

// 首次建立分身／BBC 政策寫入前的風險 gate。
// positive -> 標記 acknowledgement 並繼續；negative (含返回鍵取消) -> 只關閉,
// 不標記、不 onContinue。取消語意維持零寫入、零授權。
// #76:markAcknowledged() 回報的是 durable 與否；回 false 時下次進入仍會顯示
// (durable 未證實，偏向再顯示風險)。dialog 本身仍舊關閉、onContinue 仍舊執行 ——
// 使用者已明確 consent，本函式刻意忽略回傳值，不阻塞這次已授權的流程。
internal fun showRiskAckGate(
    activity: Activity,
    store: RiskAckStore,
    onContinue: () -> Unit,
): AlertDialog? {
    if (activity.isFinishing || activity.isDestroyed) return null
    return try {
        AlertDialog.Builder(activity)
            .setTitle(R.string.risk_details_title)
            .setMessage(R.string.risk_details_full)
            .setPositiveButton(R.string.risk_ack_positive) { _, _ ->
                store.markAcknowledged()
                onContinue()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setCancelable(true)
            .show()
    } catch (_: Exception) {
        // show 失敗即不標記、不繼續 (fail-closed)。
        null
    }
}
