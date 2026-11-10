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

    @Suppress("UNCHECKED_CAST")
    private fun setInventoryFreshness(vm: DualViewModel, freshness: InventoryFreshness) {
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val flow = f.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<UiState>
        val cur = flow.value
        flow.value = cur.copy(inventory = cur.inventory.copy(freshness = freshness))
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

    private fun openDialogAndAck(
        scenario: androidx.test.core.app.ActivityScenario<MainActivity>,
    ) {
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
        scenario.onActivity { activity ->
            val dialog = field(activity, "batchConfirmDialog") as AlertDialog
            val check = checkbox(dialog.window!!.decorView)!!
            check.isChecked = true
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
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
            assertEquals(InventoryFreshness.FRESH, vm.state.value.inventory.freshness)

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

                // 旋轉：同語境(FRESH)同世代請求與 ack 保留（未確認執行，仍零寫入）。
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
                        val wasFresh = pending?.let { field(it, "wasFresh") as? Boolean }
                        ok = dialog?.isShowing == true &&
                            check?.isChecked == true &&
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled &&
                            keys == setOf(key) &&
                            targetId == 95 &&
                            targetSerial == 15 &&
                            wasFresh == true
                    }
                    ok
                }
                scenario.onActivity { activity ->
                    val dialog = field(activity, "batchConfirmDialog") as AlertDialog
                    val check = checkbox(dialog.window!!.decorView)!!
                    val pending = field(activity, "pendingBatchRequest")!!
                    @Suppress("UNCHECKED_CAST")
                    val keys = field(pending, "selectedKeys") as Set<String>
                    assertTrue(check.isChecked)
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                    assertEquals(setOf(key), keys)
                    assertEquals(true, field(pending, "wasFresh"))
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

    // Review #4：同 key/target 下 FRESH→STALE 跨 recreate，舊 ack 必須失效。
    @Test
    fun freshnessFreshToStale_recreate_clearsAck_andDisablesPositive() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            prepareWritableSelection(vm)
            assertEquals(InventoryFreshness.FRESH, vm.state.value.inventory.freshness)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openDialogAndAck(scenario)
                scenario.onActivity { setInventoryFreshness(vm, InventoryFreshness.STALE) }
                HarnessPoll.await(10_000) { vm.state.value.inventory.freshness == InventoryFreshness.STALE }
                scenario.recreate()
                HarnessPoll.await(10_000) {
                    var ok = false
                    scenario.onActivity { activity ->
                        val dialog = field(activity, "batchConfirmDialog") as? AlertDialog
                        val check = dialog?.window?.decorView?.let { checkbox(it) }
                        val acked = (field(activity, "batchAcknowledged") as? Boolean) == true
                        ok = dialog?.isShowing == true &&
                            check?.isChecked == false &&
                            !acked &&
                            !dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled
                    }
                    ok
                }
                assertEquals(0, gw.installCalls)
                assertEquals(0, gw.writeCalls)
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    // Review #4：同 key/target 下 STALE→FRESH 跨 recreate，舊 ack 必須失效。
    @Test
    fun freshnessStaleToFresh_recreate_clearsAck_andDisablesPositive() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            prepareWritableSelection(vm)
            scenarioSetStaleThenTest(vm, gw, toFresh = true)
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    private fun scenarioSetStaleThenTest(vm: DualViewModel, gw: FakeHarnessGateway, toFresh: Boolean) {
        if (!toFresh) return
        // 先降為 STALE 再開啟並勾選，然後升回 FRESH 驗證反向失效。
        setInventoryFreshness(vm, InventoryFreshness.STALE)
        HarnessPoll.await(10_000) { vm.state.value.inventory.freshness == InventoryFreshness.STALE }
        MainActivity.testViewModel = vm
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDialogAndAck(scenario)
            scenario.onActivity { setInventoryFreshness(vm, InventoryFreshness.FRESH) }
            HarnessPoll.await(10_000) { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
            scenario.recreate()
            HarnessPoll.await(10_000) {
                var ok = false
                scenario.onActivity { activity ->
                    val dialog = field(activity, "batchConfirmDialog") as? AlertDialog
                    val check = dialog?.window?.decorView?.let { checkbox(it) }
                    val acked = (field(activity, "batchAcknowledged") as? Boolean) == true
                    ok = dialog?.isShowing == true &&
                        check?.isChecked == false &&
                        !acked &&
                        !dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled
                }
                ok
            }
            assertEquals(0, gw.installCalls)
            assertEquals(0, gw.writeCalls)
        }
    }

    // Review #4：live freshness 已變但 render 尚未同步時，positive click 必須 fail-closed（零寫入）。
    @Test
    fun freshnessChangesBeforeRenderSync_positiveClick_failClosed_zeroWrite() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            prepareWritableSelection(vm)
            assertEquals(InventoryFreshness.FRESH, vm.state.value.inventory.freshness)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openDialogAndAck(scenario)
                // 同一 UI 事件序列內：先改 live freshness，再立即點 positive（render 尚未必同步）。
                scenario.onActivity { activity ->
                    setInventoryFreshness(vm, InventoryFreshness.STALE)
                    val dialog = field(activity, "batchConfirmDialog") as AlertDialog
                    val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    if (positive.isEnabled) {
                        positive.performClick()
                    }
                }
                // click 路徑必須 fail-closed：不寫入；語境失效後 ack 清除且不可確認。
                HarnessPoll.await(10_000) {
                    var ok = false
                    scenario.onActivity { activity ->
                        val dialog = field(activity, "batchConfirmDialog") as? AlertDialog
                        val check = dialog?.window?.decorView?.let { checkbox(it) }
                        val acked = (field(activity, "batchAcknowledged") as? Boolean) == true
                        val positiveDisabled = dialog == null || !dialog.isShowing ||
                            !dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled
                        ok = !acked && (check == null || !check.isChecked) && positiveDisabled
                    }
                    ok
                }
                assertEquals("mismatch click must not install", 0, gw.installCalls)
                assertEquals("mismatch click must not write", 0, gw.writeCalls)
                assertNull(vm.state.value.batchReport)
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }
}
