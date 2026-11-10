// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR64：批次兩步確認 Dialog 互動回歸（instrumentation，零真實寫入）。
// 使用 FakeHarnessGateway + 注入 testViewModel：驗證開啟/ack 門控/取消零寫入，
// 以及旋轉後同世代請求與 ack 保留（世代契約）。
package com.galaxydual.plus

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxydual.plus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchConfirmDialogTest {

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun checkbox(view: View): CheckBox? {
        if (view is CheckBox) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                checkbox(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun prepareWritableSelection(vm: DualViewModel) {
        // 先 refresh 取得已驗證目標，再載入 catalog 並勾選（確保可批次）。
        vm.refresh()
        HarnessPoll.await(15_000) {
            val s = vm.state.value
            !s.loading && s.inventory.targetId == 95 && s.inventory.targetSerial == 15
        }
        vm.setSourceCatalog(listOf(SourceAppEntry("com.example.chat", "Chat", 0, false)))
        vm.toggleSelection(sourceAppKey(0, "com.example.chat"))
        HarnessPoll.await(10_000) {
            val s = vm.state.value
            s.sourceCatalog.isNotEmpty() &&
                s.selectedKeys.contains(sourceAppKey(0, "com.example.chat")) &&
                batchEnabled(s)
        }
    }

    @Test
    fun open_ack_cancel_zeroWrite_and_rotationPreservesAck() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            prepareWritableSelection(vm)
            val key = sourceAppKey(0, "com.example.chat")
            assertTrue(vm.state.value.selectedKeys.contains(key))
            assertTrue(batchEnabled(vm.state.value))

            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // 開啟確認對話框（不直接執行 clone）：正向路徑入口。
                HarnessPoll.await(10_000) {
                    var enabled = false
                    scenario.onActivity { activity ->
                        val btn = activity.findViewById<Button>(R.id.batch_btn)
                        enabled = btn != null && btn.isEnabled
                    }
                    enabled
                }
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.batch_btn).performClick()
                }
                HarnessPoll.await(10_000) {
                    var showing = false
                    scenario.onActivity { activity ->
                        val dialog = field(activity, "batchConfirmDialog") as? AlertDialog
                        showing = dialog?.isShowing == true
                    }
                    showing
                }

                // 未勾選 ack：positive 禁用；勾選後啟用（live 門控）；全程零寫入。
                scenario.onActivity { activity ->
                    val dialog = field(activity, "batchConfirmDialog") as AlertDialog
                    val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val check = checkbox(dialog.window!!.decorView)!!
                    assertFalse("positive must be disabled before ack", positive.isEnabled)
                    check.isChecked = false
                    check.isChecked = true
                    assertTrue(check.isChecked)
                    assertTrue("positive must enable after ack when batch confirmable", positive.isEnabled)
                }
                assertEquals("open/ack must not write", 0, gw.installCalls)
                assertEquals("open/ack must not write", 0, gw.writeCalls)

                // 旋轉：同世代請求與 ack 保留（未確認執行，仍零寫入）。
                scenario.recreate()
                HarnessPoll.await(10_000) {
                    var ok = false
                    scenario.onActivity { activity ->
                        val dialog = field(activity, "batchConfirmDialog") as? AlertDialog
                        val check = dialog?.window?.decorView?.let { checkbox(it) }
                        val pending = field(activity, "pendingBatchRequest")
                        @Suppress("UNCHECKED_CAST")
                        val keys = pending?.let { field(it, "selectedKeys") as? Set<String> }
                        val targetId = pending?.let { field(it, "targetId") as? Int }
                        val targetSerial = pending?.let { field(it, "targetSerial") as? Int }
                        ok = dialog?.isShowing == true &&
                            check?.isChecked == true &&
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled &&
                            keys == setOf(key) &&
                            targetId == 95 &&
                            targetSerial == 15
                    }
                    ok
                }
                scenario.onActivity { activity ->
                    val dialog = field(activity, "batchConfirmDialog") as AlertDialog
                    val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val check = checkbox(dialog.window!!.decorView)!!
                    val pending = field(activity, "pendingBatchRequest")!!
                    @Suppress("UNCHECKED_CAST")
                    val keys = field(pending, "selectedKeys") as Set<String>
                    val targetId = field(pending, "targetId") as Int
                    val targetSerial = field(pending, "targetSerial") as Int
                    assertTrue(check.isChecked)
                    assertTrue(positive.isEnabled)
                    assertEquals(setOf(key), keys)
                    assertEquals(95, targetId)
                    assertEquals(15, targetSerial)
                    // 取消：清除請求，不觸發 clone（零寫入）。
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                HarnessPoll.await(10_000) {
                    var cleared = false
                    scenario.onActivity { activity ->
                        val dialog = field(activity, "batchConfirmDialog") as? AlertDialog
                        val pending = field(activity, "pendingBatchRequest")
                        cleared = (dialog == null || !dialog.isShowing) && pending == null
                    }
                    cleared
                }
                assertEquals("cancel must not install", 0, gw.installCalls)
                assertEquals("cancel must not write", 0, gw.writeCalls)
                assertNull(vm.state.value.batchReport)
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }
}
