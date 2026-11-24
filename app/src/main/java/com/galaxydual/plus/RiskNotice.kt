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
// 建立),單鍵 commit 在 background IO owner 上執行。load 攔截一切 Throwable -> false
// (fail-safe:重新顯示風險)。`dispatch` / `ioWaitMs` 為 JVM 測試 seam:
//   dispatch = { it() } 可讓測試同步執行寫入並如實拿到 commit 結果；
//   預設使用 RiskAckIoOwner 的單執行緒 executor，呼叫端只做有上限的短等待。
@android.annotation.SuppressLint("ApplySharedPref")
internal fun android.content.SharedPreferences.riskAckStore(
    dispatch: ((() -> Unit) -> Unit)? = null,
    ioWaitMs: Long = RISK_ACK_IO_WAIT_MS,
): RiskAckStore = object : RiskAckStore {
    override fun isAcknowledged(): Boolean = try {
        getBoolean(RISK_ACK_KEY, false)
    } catch (_: Throwable) {
        // fail-safe:讀取失敗視為未確認,絕不因讀失敗而自動跳過風險揭露。
        false
    }

    override fun markAcknowledged(): Boolean {
        val done = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val submit = dispatch ?: { task: () -> Unit -> RiskAckIoOwner.executor.execute(task) }
        try {
            submit {
                try {
                    committed.set(edit().putBoolean(RISK_ACK_KEY, true).commit())
                } catch (_: Throwable) {
                    // 未證實:冷啟動後仍會再顯示風險 (isAcknowledged 讀不到 true)。
                    committed.set(false)
                } finally {
                    done.countDown()
                }
            }
        } catch (_: Throwable) {
            // executor 拒絕/背景派發失敗:同樣如實回未證實。
            return false
        }
        val settled = try {
            done.await(ioWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return settled && committed.get()
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
