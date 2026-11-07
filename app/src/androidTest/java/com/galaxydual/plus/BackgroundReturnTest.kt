// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29): BackgroundReturnTest (instrumentation).
//
// 退後台返回不重送：
// - ViewModel 層（FakeHarnessGateway，零寫入）：pending 在前/後台切換語義下保留，
//   不自動 confirm；前台 refresh 不重放 uninstall。
// - Activity 層：moveToState(CREATED) 再回 RESUMED，不崩潰，按鈕仍可見
//   （全程不點任何寫入按鈕）。
package com.galaxydual.plus

import android.view.View
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxydual.plus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundReturnTest {

    // 後台往返不自動送出：pending 保留，零 uninstall。
    @Test
    fun backgroundReturn_doesNotResend_zeroWrite() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await(30_000L) { vm.state.value.pendingRemove != null }
            // 模擬退後台返回：不呼叫 confirm，pending 必須保留且零寫入。
            val pending = vm.state.value.pendingRemove
            assertNotNull("pending must survive background round-trip", pending)
            assertEquals("background must not resend", 0, gw.uninstallCalls)
            assertEquals("background must not write", 0, gw.writeCalls)
            // 前台重查（refresh）只讀列表，不重放 uninstall。
            val refreshBaseline = gw.commandCounter.get()
            vm.refresh()
            HarnessPoll.await(30_000L) { gw.commandCounter.get() > refreshBaseline && !vm.state.value.loading }
            assertEquals("foreground refresh must not uninstall", 0, gw.uninstallCalls)
            assertEquals("foreground refresh must not write", 0, gw.writeCalls)
            // pending 仍在（refresh 不吞確認），等待使用者顯式決策。
            assertNotNull(vm.state.value.pendingRemove)
        } finally {
            vm.dispose()
        }
    }

    // 連續前台事件不疊加寫入：兩次 refresh 零 uninstall。
    @Test
    fun repeatedForeground_noDuplicateWrites() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            val refreshBaseline1 = gw.commandCounter.get()
            vm.refresh()
            HarnessPoll.await(30_000L) { gw.commandCounter.get() > refreshBaseline1 && !vm.state.value.loading }
            val refreshBaseline2 = gw.commandCounter.get()
            vm.refresh()
            HarnessPoll.await(30_000L) { gw.commandCounter.get() > refreshBaseline2 && !vm.state.value.loading }
            assertEquals("repeated foreground must not uninstall", 0, gw.uninstallCalls)
            assertEquals("repeated foreground must not install", 0, gw.installCalls)
            assertEquals("repeated foreground must not write", 0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // Activity 後台往返：不崩潰，返回後按鈕仍可見（無寫入）。
    // One UI 6 雙 tab：clone/remove 在預設 TAB_DUAL_APPS，refresh 在
    // TAB_CORE_ENGINE（預設 GONE）。前後兩次皆 tab-aware 斷言；
    // currentTab 經 KEY_CURRENT_TAB 存取，往返前停在 Apps tab，
    // 返回後先斷言 Apps tab 再切 Engine tab。
    @Test
    fun activity_backgroundReturn_renders() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // 預設 Apps tab：clone/remove 可見。
                scenario.onActivity { activity ->
                    val cloneBtn = activity.findViewById<Button>(R.id.clone_btn)
                    assertNotNull(cloneBtn)
                    assertTrue(cloneBtn is Button)
                    assertEquals(View.VISIBLE, cloneBtn.visibility)
                    assertTrue(cloneBtn.isShown)
                    val removeBtn = activity.findViewById<Button>(R.id.remove_btn)
                    assertNotNull(removeBtn)
                    assertTrue(removeBtn is Button)
                    assertEquals(View.VISIBLE, removeBtn.visibility)
                    assertTrue(removeBtn.isShown)
                }
                // 切到 Engine tab 斷言 refresh。
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_CORE_ENGINE, announce = false)
                }
                HarnessPoll.await {
                    var shown = false
                    scenario.onActivity { activity ->
                        val refreshBtn = activity.findViewById<Button>(R.id.refresh_btn)
                        shown = refreshBtn != null &&
                            refreshBtn.visibility == View.VISIBLE &&
                            refreshBtn.isShown
                    }
                    shown
                }
                scenario.onActivity { activity ->
                    val refreshBtn = activity.findViewById<Button>(R.id.refresh_btn)
                    assertNotNull(refreshBtn)
                    assertTrue(refreshBtn is Button)
                    assertEquals(View.VISIBLE, refreshBtn.visibility)
                    assertTrue(refreshBtn.isShown)
                }
                // 切回 Apps tab（currentTab 存回 TAB_DUAL_APPS），再退後台。
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_DUAL_APPS, announce = false)
                }
                HarnessPoll.await {
                    var shown = false
                    scenario.onActivity { activity ->
                        val cloneBtn = activity.findViewById<Button>(R.id.clone_btn)
                        shown = cloneBtn != null &&
                            cloneBtn.visibility == View.VISIBLE &&
                            cloneBtn.isShown
                    }
                    shown
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                // 返回後仍是 Apps tab：clone/remove 可見。
                scenario.onActivity { activity ->
                    val cloneBtn = activity.findViewById<Button>(R.id.clone_btn)
                    assertNotNull(cloneBtn)
                    assertTrue(cloneBtn is Button)
                    assertEquals(View.VISIBLE, cloneBtn.visibility)
                    assertTrue(cloneBtn.isShown)
                    val removeBtn = activity.findViewById<Button>(R.id.remove_btn)
                    assertNotNull(removeBtn)
                    assertTrue(removeBtn is Button)
                    assertEquals(View.VISIBLE, removeBtn.visibility)
                    assertTrue(removeBtn.isShown)
                }
                // 返回後切 Engine tab 斷言 refresh，再切回 Apps tab。
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_CORE_ENGINE, announce = false)
                }
                HarnessPoll.await {
                    var shown = false
                    scenario.onActivity { activity ->
                        val refreshBtn = activity.findViewById<Button>(R.id.refresh_btn)
                        shown = refreshBtn != null &&
                            refreshBtn.visibility == View.VISIBLE &&
                            refreshBtn.isShown
                    }
                    shown
                }
                scenario.onActivity { activity ->
                    val refreshBtn = activity.findViewById<Button>(R.id.refresh_btn)
                    assertNotNull(refreshBtn)
                    assertTrue(refreshBtn is Button)
                    assertEquals(View.VISIBLE, refreshBtn.visibility)
                    assertTrue(refreshBtn.isShown)
                    activity.switchTab(MainActivity.TAB_DUAL_APPS, announce = false)
                }
                HarnessPoll.await {
                    var shown = false
                    scenario.onActivity { activity ->
                        val cloneBtn = activity.findViewById<Button>(R.id.clone_btn)
                        shown = cloneBtn != null &&
                            cloneBtn.visibility == View.VISIBLE &&
                            cloneBtn.isShown
                    }
                    shown
                }
                scenario.onActivity { activity ->
                    val cloneBtn = activity.findViewById<Button>(R.id.clone_btn)
                    assertNotNull(cloneBtn)
                    assertTrue(cloneBtn is Button)
                    assertEquals(View.VISIBLE, cloneBtn.visibility)
                    assertTrue(cloneBtn.isShown)
                    val removeBtn = activity.findViewById<Button>(R.id.remove_btn)
                    assertNotNull(removeBtn)
                    assertTrue(removeBtn is Button)
                    assertEquals(View.VISIBLE, removeBtn.visibility)
                    assertTrue(removeBtn.isShown)
                }
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }
}
