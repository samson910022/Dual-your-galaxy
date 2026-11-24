// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-2 (M1)：風險揭露 UI 回歸（instrumentation，手動執行；CI emulator matrix SKIPPED）。
// mirrors BatchConfirmDialogTest：ActivityScenario + reflection field() + FakeHarnessGateway
// + MainActivity.testViewModel，全程零真實寫入。鎖定：持續提示可見但不自動彈窗、
// 詳細風險可重開、一次性 gate 的取消零寫入／不標記、通過後克隆抵達、重進不再彈。
package com.galaxydual.plus

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RiskDisclosureUiTest {

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun findByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByTag(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun riskPrefs() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences(RISK_ACK_PREFS_NAME, Context.MODE_PRIVATE)

    private fun clearAck() {
        riskPrefs().edit().clear().commit()
        // D1: prefs 只是 durable seed；行程級 owner 的 latch 必須同步重設，
        // 否則第一個 store 建構時 latch 住的狀態會留整個 process。
        ProcessRiskAckState.reseedForTests(false)
    }

    private fun isAcked(): Boolean = try {
        riskPrefs().getBoolean(RISK_ACK_KEY, false)
    } catch (_: Throwable) {
        false
    }

    // PR #89 D2b:MainActivity.riskDialog was replaced by DialogSlot<AlertDialog>.
    // Read the slot's current handle instead of the removed field; the slot's
    // identity-guarded dismiss callback nulls it, so null still means "not
    // tracked / not showing" exactly as the old nullable field did.
    private fun riskDialogOn(activity: MainActivity): AlertDialog? =
        (field(activity, "riskDialogSlot") as? DialogSlot<*>)?.current as? AlertDialog

    private fun riskDialog(scenario: ActivityScenario<MainActivity>): AlertDialog? {
        var dialog: AlertDialog? = null
        scenario.onActivity { activity -> dialog = riskDialogOn(activity) }
        return dialog
    }

    private fun awaitRiskDialog(scenario: ActivityScenario<MainActivity>, showing: Boolean) {
        HarnessPoll.await(10_000) {
            var ok = false
            scenario.onActivity { activity ->
                val d = riskDialogOn(activity)
                ok = if (showing) d?.isShowing == true else (d == null || !d.isShowing)
            }
            ok
        }
    }

    private fun awaitButtonEnabled(scenario: ActivityScenario<MainActivity>, id: Int) {
        HarnessPoll.await(15_000) {
            var enabled = false
            scenario.onActivity { activity ->
                val btn = activity.findViewById<Button>(id)
                enabled = btn != null && btn.isEnabled
            }
            enabled
        }
    }

    private fun prepareWritable(vm: DualViewModel) {
        awaitHarnessReady(vm)
        vm.refresh()
        HarnessPoll.await(15_000) {
            val s = vm.state.value
            !s.loading && s.inventory.targetId == 95 && s.inventory.targetSerial == 15
        }
    }

    private fun tapClone(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.pkg_input).setText("com.example.chat")
            activity.findViewById<Button>(R.id.clone_btn).performClick()
        }
    }

    @Test
    fun persistentNotice_visible_noAutoDialog() {
        clearAck()
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            prepareWritable(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // 持續提示兩個 tag 都在，且沒有自動彈出任何風險對話框。
                HarnessPoll.await(10_000) {
                    var ok = false
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        ok = findByTag(root, "risk_notice") != null &&
                            findByTag(root, "risk_details_entry") != null
                    }
                    ok
                }
                assertNull("read-only idle must not auto-show risk dialog", riskDialog(scenario))
                assertFalse("read-only browsing must not acknowledge", isAcked())
                assertEquals("read-only idle must not write", 0, gw.installCalls)
                assertEquals("read-only idle must not write", 0, gw.writeCalls)
            }
        } finally {
            MainActivity.testViewModel = null
            clearAck()
            vm.dispose()
        }
    }

    @Test
    fun detailsReopenable() {
        clearAck()
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            prepareWritable(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun tapDetails() {
                    scenario.onActivity { activity ->
                        val entry = findByTag(activity.window.decorView, "risk_details_entry")
                        assertNotNull("risk_details_entry must exist", entry)
                        entry!!.performClick()
                    }
                }
                // 第一次：詳細對話框可開。
                tapDetails()
                awaitRiskDialog(scenario, showing = true)
                scenario.onActivity { activity ->
                    riskDialogOn(activity)?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                }
                awaitRiskDialog(scenario, showing = false)
                // 第二次：可再次開啟（無狀態、不消耗 acknowledgement）。
                tapDetails()
                awaitRiskDialog(scenario, showing = true)
                assertFalse("details dialog must never acknowledge", isAcked())
                scenario.onActivity { activity ->
                    riskDialogOn(activity)?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                }
                awaitRiskDialog(scenario, showing = false)
                assertEquals("details must not write", 0, gw.installCalls)
            }
        } finally {
            MainActivity.testViewModel = null
            clearAck()
            vm.dispose()
        }
    }

    @Test
    fun firstClone_gate_cancel_zeroWrite_thenContinue_andReentry() {
        clearAck()
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            prepareWritable(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.pkg_input).setText("com.example.chat")
                }
                awaitButtonEnabled(scenario, R.id.clone_btn)

                // 第一次：gate 出現 → 取消 → 零寫入、不標記。
                tapClone(scenario)
                awaitRiskDialog(scenario, showing = true)
                assertFalse("gate before acceptance must leave ack false", isAcked())
                scenario.onActivity { activity ->
                    riskDialogOn(activity)!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                awaitRiskDialog(scenario, showing = false)
                assertEquals("gate cancel must not install", 0, gw.installCalls)
                assertFalse("gate cancel must not acknowledge", isAcked())

                // 再點：gate 再現 → 接受 → ack 設定 + clone 抵達（installCalls 增加）。
                tapClone(scenario)
                awaitRiskDialog(scenario, showing = true)
                scenario.onActivity { activity ->
                    riskDialogOn(activity)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                HarnessPoll.await(15_000) { gw.installCalls > 0 }
                assertTrue("acceptance must acknowledge", isAcked())

                // 重進 Activity：已確認 → 不再彈 gate。
                scenario.recreate()
                awaitButtonEnabled(scenario, R.id.clone_btn)
                tapClone(scenario)
                Thread.sleep(500) // gate 不存在時，短暫觀察是否誤彈。
                assertNull("re-entry must not show gate after ack", riskDialog(scenario))
                assertTrue("ack must persist across recreate", isAcked())
            }
        } finally {
            MainActivity.testViewModel = null
            clearAck()
            vm.dispose()
        }
    }

    @Test
    fun batchAndPolicyWrite_gateReachable() {
        clearAck()
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            prepareWritable(vm)
            vm.setSourceCatalog(listOf(SourceAppEntry("com.example.chat", "Chat", 0, false)))
            vm.toggleSelection(sourceAppKey(0, "com.example.chat"))
            HarnessPoll.await(10_000) {
                val s = vm.state.value
                s.selectedKeys.contains(sourceAppKey(0, "com.example.chat")) && batchEnabled(s)
            }
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // --- 批次：gate 取消零寫入；接受後既有批次確認對話框仍出現。 ---
                awaitButtonEnabled(scenario, R.id.batch_btn)
                scenario.onActivity { it.findViewById<Button>(R.id.batch_btn).performClick() }
                awaitRiskDialog(scenario, showing = true)
                scenario.onActivity { activity ->
                    riskDialogOn(activity)!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                awaitRiskDialog(scenario, showing = false)
                assertEquals("batch gate cancel must not write", 0, gw.installCalls)
                assertFalse("batch gate cancel must not acknowledge", isAcked())

                scenario.onActivity { it.findViewById<Button>(R.id.batch_btn).performClick() }
                awaitRiskDialog(scenario, showing = true)
                scenario.onActivity { activity ->
                    riskDialogOn(activity)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                // 既有兩步確認對話框必須照原流程出現（gate 為附加，不取代守衛）。
                HarnessPoll.await(10_000) {
                    var showing = false
                    scenario.onActivity { activity ->
                        showing = (field(activity, "batchConfirmDialog") as? AlertDialog)?.isShowing == true
                    }
                    showing
                }
                assertTrue("batch acceptance must acknowledge", isAcked())
                // 取消既有確認框，避免任何真實寫入。
                scenario.onActivity { activity ->
                    (field(activity, "batchConfirmDialog") as? AlertDialog)
                        ?.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick()
                }

                // --- BBC 政策寫入：清掉 ack 後同一 gate 亦可抵達。 ---
                clearAck()
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_CORE_ENGINE, announce = false)
                }
                scenario.onActivity { activity ->
                    // writePolicyBtn 沒有 R.id；用既有 reflection seam，不依賴預設語系文字。
                    val btn = field(activity, "writePolicyBtn") as Button
                    btn.performClick()
                }
                awaitRiskDialog(scenario, showing = true)
                scenario.onActivity { activity ->
                    riskDialogOn(activity)!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                awaitRiskDialog(scenario, showing = false)
                assertFalse("policy gate cancel must not acknowledge", isAcked())
                assertEquals("policy gate cancel must not write", 0, gw.writeCalls)
            }
        } finally {
            MainActivity.testViewModel = null
            clearAck()
            vm.dispose()
        }
    }
}
