// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #89 Phase D2b (#78)：單一對話框 handle slot 的所有權契約。
//
// Framework-free / JVM-testable：本檔不含任何 Android 型別（連 import 都沒有），
// handle 型別 T 與 isShowing / bindDismiss / discard / dismiss 都是注入的 lambda，
// 所以 null 不覆寫、single-flight、identity-guard、clear 先清空後 dismiss 等語意
// 都能用假 handle 在 JVM 單元測試直接驗證。AlertDialog 的綁定留在 Activity 呼叫端。
//
// 統一由 slot 持有 handle 同時修掉三個 #78 問題：
// 1. show() 失敗/dying host 回傳 null 時不再把既有有效 handle 覆寫成 null
//    （舊程式 `riskDialog = show...(...)?.also { ... }` 會丟掉 onDestroy 的 cleanup 目標）。
// 2. 第二個對話框不再覆寫第一個 handle（孤兒視窗）；正在顯示的 handle 會擋下新候選，
//    並 best-effort discard 新候選。
// 3. dismiss callback 的綁定由 slot 強制執行，並以 identity 守護，過期 callback
//    不能清掉較新的 handle。
package com.galaxydual.plus

internal class DialogSlot<T : Any> {
    private var slot: T? = null

    /** 目前被追蹤的 handle；沒有則 null。 */
    val current: T?
        @Synchronized get() = slot

    /**
     * 嘗試把 [candidate] 裝入 slot。回傳 true = 已安裝（呼叫端可視為「已追蹤」）。
     *
     * - [candidate] 為 null（show() 失敗/dying host）：永遠不覆寫現有 handle，回 false。
     * - 現有 handle 仍在顯示且與 candidate 不同（single-flight）：best-effort discard
     *   新候選、保留現有 handle、回 false，避免第一個對話框變成無人追蹤的孤兒視窗。
     * - 否則先綁身份守護的 dismiss callback（`current === candidate` 才清空），
     *   綁定成功後才安裝。綁定失敗代表 handle 不可追蹤，不安裝並 best-effort discard。
     *
     * 所有外部 lambda 的 Throwable 都在此吞掉（含 Error）：呼叫端是 UI 執行緒上的
     * onDestroy / click listener，單一對話框失敗不得中斷其餘流程。
     */
    @Synchronized
    fun install(
        candidate: T?,
        isShowing: (T) -> Boolean,
        bindDismiss: (T, () -> Unit) -> Unit,
        discard: (T) -> Unit,
    ): Boolean {
        if (candidate == null) return false

        val existing = slot
        if (existing != null && existing !== candidate) {
            val stillShowing = try {
                isShowing(existing)
            } catch (_: Throwable) {
                // fail-safe：狀態不明時當作仍在顯示，寧可擋下新對話框也不要留孤兒視窗。
                true
            }
            if (stillShowing) {
                bestEffort(discard, candidate)
                return false
            }
        }

        try {
            bindDismiss(candidate) {
                // identity guard：只有「slot 仍 holding 同一個 handle」的 dismiss
                // 才能清空。舊 handle 遲到的 callback 不得清掉較新的 handle。
                // callback 可能由另一條執行緒送達，必須走 synchronized entry point
                // 才能和 install/clear 在同一個 monitor 上原子操作。
                onDismissed(candidate)
            }
        } catch (_: Throwable) {
            // 沒綁上 dismiss callback 的 handle 不能被追蹤，不安裝；順手關掉候選。
            bestEffort(discard, candidate)
            return false
        }

        slot = candidate
        return true
    }

    /**
     * 清空 slot 並 best-effort dismiss 原 handle。先清空再 dismiss，這樣 dismiss
     * 觸發的 OnDismissListener（identity guard 會看到 slot 已空）不會造成遞迴。
     * 使用傳入的 [dismiss]（Activity 端一律傳 `dismiss()`，絕不傳 `cancel()`，
     * 避免快銷毀中的 Activity 仍收到 onCancel）。dismiss 拋出的 Throwable 會吞掉。
     */
    @Synchronized
    fun clear(dismiss: (T) -> Unit) {
        val previous = slot
        slot = null
        if (previous != null) bestEffort(dismiss, previous)
    }

    /**
     * Dismiss callback 的 synchronized entry point：Android 的 OnDismissListener
     * 可能從非 UI 執行緒送達，直接讀寫 [slot] 會讓 identity guard 的
     * check-then-set 與 install/clear 交錯。經由同一個 monitor 序列化後，
     * 只有仍被追蹤的 handle 才會被清掉；同一執行緒同步觸發 callback 時，
     * Java/Kotlin monitor 可重入，不會死鎖（clear 先清空再 dismiss 亦同理）。
     */
    @Synchronized
    private fun onDismissed(candidate: T) {
        if (slot === candidate) slot = null
    }

    private inline fun bestEffort(action: (T) -> Unit, handle: T) {
        try {
            action(handle)
        } catch (_: Throwable) {
            // Best effort: the window is going away anyway.
        }
    }
}
