// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29): InputRestoreTest (instrumentation).
//
// 旋轉/重建後輸入恢復：
// - ViewModel 層（FakeHarnessGateway，零寫入）：sourceQuery、selectedKeys、
//   pendingRemove 在同實例（旋轉語義）下保留；取消勾選只清選取不卸載。
// - Activity 層：搜尋框輸入 + 通道行（AUTO）在 recreate 後仍渲染（無寫入）。
package com.galaxydual.plus

import android.widget.EditText
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxydual.plus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputRestoreTest {

    // 搜尋輸入在同實例上保留（旋轉語義），零寫入。
    @Test
    fun searchQuery_retained_zeroWrite() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.setSourceQuery("mail")
            HarnessPoll.await { vm.state.value.sourceQuery == "mail" }
            assertEquals("mail", vm.state.value.sourceQuery)
            assertEquals(0, gw.uninstallCalls)
            assertEquals(0, gw.installCalls)
            assertEquals(0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // 勾選/清除只動選取：toggle 後 clear，不卸載。
    @Test
    fun selectionToggleAndClear_noUninstall() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.setSourceCatalog(
                listOf(
                    SourceAppEntry("com.example.chat", "Chat", 0, false),
                    SourceAppEntry("com.example.mail", "Mail", 0, false),
                ),
            )
            val key = sourceAppKey(0, "com.example.chat")
            vm.toggleSelection(key)
            HarnessPoll.await { vm.state.value.selectedKeys.contains(key) }
            assertTrue(vm.state.value.selectedKeys.contains(key))
            vm.clearSelectionLocal()
            HarnessPoll.await { vm.state.value.selectedKeys.isEmpty() }
            assertTrue(vm.state.value.selectedKeys.isEmpty())
            assertEquals("toggle/clear must never uninstall", 0, gw.uninstallCalls)
            assertEquals("toggle/clear must never write", 0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // same-instance preference stable (cross-rotation persistence is Activity/factory-level, covered by manual matrix I1).
    @Test
    fun channelPreference_retained_zeroWrite() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            // 旋轉語義 = 同實例：偏好不變（預設 AUTO）。
            assertEquals(ChannelPreference.AUTO, vm.state.value.channel)
            val baseline = gw.commandCounter.get()
            vm.refresh()
            HarnessPoll.await { gw.commandCounter.get() > baseline && !vm.state.value.loading }
            assertEquals(ChannelPreference.AUTO, vm.state.value.channel)
            assertEquals(0, gw.uninstallCalls)
            assertEquals(0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // Activity：非預設通道偏好在 recreate 後仍顯示（注入 fake-backed VM）。
    @Test
    fun activity_recreate_retainsNonDefaultChannelPreference() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw, preference = ChannelPreference.SHIZUKU)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<RadioButton>(R.id.channel_shizuku).isChecked)
                    assertFalse(activity.findViewById<RadioButton>(R.id.channel_auto).isChecked)
                    assertTrue(HarnessViews.containsText(activity.window.decorView, "SHIZUKU"))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<RadioButton>(R.id.channel_shizuku).isChecked)
                    assertFalse(activity.findViewById<RadioButton>(R.id.channel_auto).isChecked)
                    assertTrue(HarnessViews.containsText(activity.window.decorView, "SHIZUKU"))
                }
                assertEquals(0, gw.uninstallCalls)
                assertEquals(0, gw.writeCalls)
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    // Activity：搜尋輸入 + 通道行在 recreate 後仍渲染（無寫入）。
    @Test
    fun activity_recreate_restoresInputAndChannelRow() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val input = activity.findViewById<EditText>(R.id.search_input)
                    input.setText("mail")
                }
                HarnessPoll.await { vm.state.value.sourceQuery == "mail" }

                scenario.recreate()

                var mailOk = false
                val recreateDeadline = System.currentTimeMillis() + 5000L
                while (System.currentTimeMillis() < recreateDeadline && !mailOk) {
                    scenario.onActivity { activity ->
                        mailOk = HarnessViews.hasEditTextText(activity.window.decorView, "mail")
                    }
                    if (!mailOk) Thread.sleep(200)
                }
                assertTrue("hasEditTextText(..., \"mail\") after recreate", mailOk)

                assertEquals(0, gw.uninstallCalls)
                assertEquals(0, gw.writeCalls)
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }
}
