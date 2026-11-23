// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-1 (M1)：分身資料風險揭露的一次性 acknowledgement store 與兩個對話框。
//
// 契約 (mirrors RepairHintStore.kt conventions):
// - 保存內容:單一 boolean (acknowledged)，位於 app-private 檔案
//   "dual_policy_risk_disclosure"，絕不寫入 dual_policy remote prefs
//   (不產生 poison fence / delta 污染)。
// - 寫入原子性:單鍵 edit().putBoolean(...).commit() 同步落盤。有意用 commit()
//   而非 apply():下一次進入必須立即讀到已確認狀態。
// - 讀取失敗 (含任何 Throwable) -> false (fail-safe:偏向「再顯示一次風險」)；
//   讀取端永不因讀失敗而自動標記已確認。
// - RiskAckStore 介面與 shouldShowRiskGate 為純 Kotlin seam (無 Android 型別)，
//   JVM 可用 Map-backed fake 直接測試；Android framework 僅出現在 SharedPreferences
//   綁定 (以下以完整名稱限定，不進 import) 與兩個對話框函式。
// - 風險揭露 UI wiring 的共用部分是 RiskAckStore／shouldShowRiskGate 等純 seam。
//   MainActivity 自行持有對話框 handle 並以同名 private helper 建構；
//   PolicyActivity 呼叫本檔的全域對話框 helper。兩者對話框建構／生命週期
//   尚未統一（追蹤於 issue #78）；本檔 helper 亦不回傳 handle。
package com.galaxydual.plus

import android.app.Activity
import android.app.AlertDialog

// 持久化檔名/鍵名 (改名即遺棄舊標記,視為破壞性變更,需審查)。
internal const val RISK_ACK_PREFS_NAME = "dual_policy_risk_disclosure"
internal const val RISK_ACK_KEY = "acknowledged"

// 持久化埠:不含任何 Android 型別,JVM 可以 Map-backed fake 直接實作。
internal interface RiskAckStore {
    fun isAcknowledged(): Boolean
    fun markAcknowledged()
}

// 純函式 seam:單一來源,進入點與測試共用。未確認即顯示 gate。
internal fun shouldShowRiskGate(acknowledged: Boolean): Boolean = !acknowledged

// 生產綁定:app-private SharedPreferences (MODE_PRIVATE 由呼叫端 getSharedPreferences
// 建立),單鍵同步 commit。load 攔截一切 Throwable -> false (fail-safe:重新顯示風險)。
@android.annotation.SuppressLint("ApplySharedPref")
internal fun android.content.SharedPreferences.riskAckStore(): RiskAckStore = object : RiskAckStore {
    override fun isAcknowledged(): Boolean = try {
        getBoolean(RISK_ACK_KEY, false)
    } catch (_: Throwable) {
        // fail-safe:讀取失敗視為未確認,絕不因讀失敗而自動跳過風險揭露。
        false
    }

    override fun markAcknowledged() {
        try {
            edit().putBoolean(RISK_ACK_KEY, true).commit()
        } catch (_: Throwable) {
            // Best effort:下一次 markAcknowledged 覆寫即恢復；失敗維持未確認
            // (偏向再顯示風險)。
        }
    }
}

// 可重新開啟的完整風險說明:無狀態、不標記 acknowledgement。
internal fun showRiskDetailsDialog(activity: Activity) {
    if (activity.isFinishing || activity.isDestroyed) return
    try {
        AlertDialog.Builder(activity)
            .setTitle(R.string.risk_details_title)
            .setMessage(R.string.risk_details_full)
            .setPositiveButton(R.string.dialog_close, null)
            .setCancelable(true)
            .show()
    } catch (_: Exception) {
        // Activity window 在 guard 與 show 之間消失:fail-safe,不改變任何狀態。
    }
}

// 首次建立分身／BBC 政策寫入前的風險 gate。
// positive -> 標記 acknowledgement 並繼續；negative (含返回鍵取消) -> 只關閉,
// 不標記、不 onContinue。取消語意維持零寫入、零授權。
internal fun showRiskAckGate(
    activity: Activity,
    store: RiskAckStore,
    onContinue: () -> Unit,
) {
    if (activity.isFinishing || activity.isDestroyed) return
    try {
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
    }
}
