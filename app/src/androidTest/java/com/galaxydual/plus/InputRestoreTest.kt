// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29): InputRestoreTest (instrumentation).
//
// 旋轉/重建後輸入恢復：
// - ViewModel 層（FakeHarnessGateway，零寫入）：sourceQuery、selectedKeys、
//   pendingRemove 在同實例（旋轉語義）下保留；取消勾選只清選取不卸載。
// - Activity 層：搜尋框輸入 + 通道偏好行在 recreate 後仍渲染（無寫入）。
package com.galaxydual.plus

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import android.widget.EditText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
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
            vm.refresh()
            HarnessPoll.await { !vm.state.value.loading }
            assertEquals(ChannelPreference.AUTO, vm.state.value.channel)
            assertEquals(0, gw.uninstallCalls)
            assertEquals(0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // Activity：搜尋輸入 + 通道行在 recreate 後仍渲染（無寫入）。
    @Test
    fun activity_recreate_restoresInputAndChannelRow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("搜尋應用名稱 / 包名")).perform(replaceText("mail"))
            Espresso.onIdle()
            // 通道偏好行：前綴「通道偏好」穩定存在（後綴隨裝置通道而異，只匹配前綴）。
            onView(withText(containsString("通道偏好"))).check(matches(isDisplayed()))
            scenario.recreate()
            onView(allOf(isAssignableFrom(EditText::class.java), withText("mail"))).check(matches(isDisplayed()))
            onView(withHint("搜尋應用名稱 / 包名")).check(matches(isDisplayed()))
            onView(withText(containsString("通道偏好"))).check(matches(isDisplayed()))
        }
    }
}
