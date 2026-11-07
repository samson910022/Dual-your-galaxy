package com.galaxydual.plus

import android.app.AlertDialog
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import kotlinx.coroutines.flow.MutableStateFlow

@Suppress("DEPRECATION")
class RemoveConfirmationUiTest : InstrumentationTestCase() {
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun checkbox(view: View): CheckBox? {
        if (view is CheckBox) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            checkbox(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    fun testAcknowledgementDetailsBusyAndReplacement() {
        val activity = launchActivity(instrumentation.targetContext.packageName, MainActivity::class.java, null)
        try {
            instrumentation.waitForIdleSync()
            // 取 VM 引用（主執行緒），後續在測試執行緒輪詢 loading，避免與 init probe 競態。
            var vmRef: DualViewModel? = null
            instrumentation.runOnMainSync {
                vmRef = activity.javaClass.getDeclaredMethod("getVm").apply { isAccessible = true }.invoke(activity) as DualViewModel
            }
            val vm = vmRef!!
            // 與 init probe/onForeground 競態隔離：植入 pending 前等待非 loading，
            // 否則初查的 loading=true/false 翻轉會覆寫植入狀態或觸發非預期 render。
            val deadline = System.currentTimeMillis() + 10_000
            while (vm.state.value.loading) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("timed out waiting for init probe idle")
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(50)
            }
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val state = field(vm, "_state") as MutableStateFlow<UiState>
                val sync = activity.javaClass.getDeclaredMethod("syncRemoveDialog", PendingRemove::class.java, Boolean::class.javaPrimitiveType, BlockReason::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                val catalogKey = activity.javaClass.getDeclaredMethod("labelCacheKey").apply { isAccessible = true }.invoke(activity) as Int
                val old = PendingRemove("com.example.old", 95, 15, "Profile", 1)
                val replacement = PendingRemove("com.example.new", 95, 15, "Profile", 2)
                state.value = state.value.copy(pendingRemove = old, loading = false)
                // 上一行在主執行緒直接改 state，lifecycleScope（Main.immediate）的
                // collector 會同步重入 render() 並先用 live 參數建好對話框；此處
                // 若直接 sync 會走 early-return，沿用舊 listener 的 blockKey。
                // 先解散，強制下面的明確 sync 走重建路徑並掛上測試參數的 listener。
                try {
                    (field(activity, "removeConfirmDialog") as AlertDialog?)?.dismiss()
                } catch (_: Exception) {
                }
                sync.invoke(activity, old, false, null, catalogKey)
                val dialog = field(activity, "removeConfirmDialog") as AlertDialog
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val check = checkbox(dialog.window!!.decorView)!!
                assertFalse(positive.isEnabled)
                // 以明確設值驅動勾選（等價於一次點選觸發的 setChecked(true)→listener
                // 路徑）；先清零再置位，無論初始狀態為何都確保 listener 以
                // checked=true 觸發一次。
                check.isChecked = false
                check.isChecked = true
                assertTrue(check.isChecked)
                assertTrue(positive.isEnabled)
                sync.invoke(activity, old, true, null, catalogKey)
                assertFalse(positive.isEnabled)
                assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
                sync.invoke(activity, old, false, null, catalogKey)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                val details = field(activity, "removeLabelsDialog") as AlertDialog
                assertTrue(details.isShowing)
                // Neutral（標籤詳情）不解散父確認框、不重置勾選（世代綁定保留）。
                assertTrue(dialog.isShowing)
                assertTrue(check.isChecked)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertTrue(check.isChecked)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                // Replace state before the collector can render: the old confirmed
                // dialog must not authorize or cancel the new generation.
                state.value = state.value.copy(pendingRemove = replacement, loading = false)
                positive.performClick()
                assertEquals(replacement, state.value.pendingRemove)
                sync.invoke(activity, replacement, false, null, catalogKey)
                val newDialog = field(activity, "removeConfirmDialog") as AlertDialog
                assertFalse(newDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertFalse(checkbox(newDialog.window!!.decorView)!!.isChecked)
                newDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertNull(state.value.pendingRemove)
            }
        } finally {
            activity.finish()
        }
    }

    fun testBlockedChannelDisablesConfirmEvenWhenAcknowledged() {
        val activity = launchActivity(instrumentation.targetContext.packageName, MainActivity::class.java, null)
        try {
            instrumentation.waitForIdleSync()
            var vmRef: DualViewModel? = null
            instrumentation.runOnMainSync {
                vmRef = activity.javaClass.getDeclaredMethod("getVm").apply { isAccessible = true }.invoke(activity) as DualViewModel
            }
            val vm = vmRef!!
            val deadline = System.currentTimeMillis() + 10_000
            while (vm.state.value.loading) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("timed out waiting for init probe idle")
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(50)
            }
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val state = field(vm, "_state") as MutableStateFlow<UiState>
                val sync = activity.javaClass.getDeclaredMethod("syncRemoveDialog", PendingRemove::class.java, Boolean::class.javaPrimitiveType, BlockReason::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                val catalogKey = activity.javaClass.getDeclaredMethod("labelCacheKey").apply { isAccessible = true }.invoke(activity) as Int
                val old = PendingRemove("com.example.old", 95, 15, "Profile", 1)
                state.value = state.value.copy(pendingRemove = old, loading = false)
                // 上一行在主執行緒直接改 state，lifecycleScope（Main.immediate）的
                // collector 會同步重入 render() 並先用 live 參數建好對話框；此處
                // 若直接 sync 會走 early-return，沿用舊 listener 的 blockKey。
                // 先解散，強制下面的明確 sync 走重建路徑並掛上測試參數的 listener。
                try {
                    (field(activity, "removeConfirmDialog") as AlertDialog?)?.dismiss()
                } catch (_: Exception) {
                }
                sync.invoke(activity, old, false, null, catalogKey)
                val dialog = field(activity, "removeConfirmDialog") as AlertDialog
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val check = checkbox(dialog.window!!.decorView)!!
                assertFalse(positive.isEnabled)
                // 以明確設值驅動勾選（等價於一次點選觸發的 setChecked(true)→listener
                // 路徑）；先清零再置位，無論初始狀態為何都確保 listener 以
                // checked=true 觸發一次。
                check.isChecked = false
                check.isChecked = true
                assertTrue(check.isChecked)
                assertTrue(positive.isEnabled)
                // 四參 dialog 門控零覆蓋：blocked 通道即使已勾選，confirm 仍禁用。
                val blocked = BlockReason.Keyed(WriteBlock(WriteBlockKind.NO_PRIVILEGE))
                sync.invoke(activity, old, false, blocked, catalogKey)
                assertTrue(check.isChecked)
                assertFalse(positive.isEnabled)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertNull(state.value.pendingRemove)
            }
        } finally {
            activity.finish()
        }
    }
}
