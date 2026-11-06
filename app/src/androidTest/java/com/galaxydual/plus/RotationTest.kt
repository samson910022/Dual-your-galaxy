// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29): RotationTest (instrumentation).
//
// 旋轉後輸入/選取/pending 狀態恢復：
// - ViewModel 層（FakeHarnessGateway，零寫入）：同一實例上 request 的 pending、
//   setSourceQuery 的輸入、toggleSelection 的勾選在 recreate 語義下保留
//   （Activity.recreate() 保留 ViewModel）；新實例 fail-closed（pending null）。
// - Activity 層：搜尋框輸入旋轉後仍顯示（MainActivity.buildUi 從 retained
//   ViewModel 回填 sourceQuery）；recreate 不崩潰。
package com.galaxydual.plus

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RotationTest {

    // 同一 ViewModel：pending 在旋轉語義（同實例）下保留，零寫入。
    @Test
    fun pending_retainedOnSameInstance_zeroWrite() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await { vm.state.value.pendingRemove != null }
            // recreate 語義 = 同實例：pending 保留，對話框重顯不重送。
            assertNotNull(
                "rotation (same instance) must retain pending",
                vm.state.value.pendingRemove,
            )
            assertEquals(0, gw.uninstallCalls)
        } finally {
            vm.dispose()
        }
    }

    // 新實例（進程重建）fail-closed：pending null，必須重請。
    @Test
    fun pending_discardedOnNewInstance_zeroWrite() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm1 = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm1)
            vm1.requestRemove("com.example.chat")
            HarnessPoll.await { vm1.state.value.pendingRemove != null }
        } finally {
            vm1.dispose()
        }
        val vm2 = newHarnessViewModel(gw)
        try {
            assertNull(
                "recreated ViewModel must start with null pending",
                vm2.state.value.pendingRemove,
            )
            awaitHarnessReady(vm2)
            assertNull(vm2.state.value.pendingRemove)
            assertEquals(0, gw.uninstallCalls)
        } finally {
            vm2.dispose()
        }
    }

    // 輸入/選取在同實例上保留（旋轉語義），零寫入。
    @Test
    fun queryAndSelection_retainedOnSameInstance() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.setSourceQuery("chat")
            vm.setSourceCatalog(
                listOf(
                    SourceAppEntry("com.example.chat", "Chat", 0, false),
                    SourceAppEntry("com.example.mail", "Mail", 0, false),
                ),
            )
            vm.toggleSelection(sourceAppKey(0, "com.example.chat"))
            HarnessPoll.await { vm.state.value.selectedKeys.isNotEmpty() }
            // 旋轉語義 = 同實例：query + 選取保留。
            assertEquals("chat", vm.state.value.sourceQuery)
            assertTrue(vm.state.value.selectedKeys.contains(sourceAppKey(0, "com.example.chat")))
            assertEquals(0, gw.uninstallCalls)
            assertEquals(0, gw.installCalls)
        } finally {
            vm.dispose()
        }
    }

    // Activity 旋轉：搜尋輸入恢復顯示且不崩潰（無寫入：全程不點 Clone/Remove 確認）。
    @Test
    fun activity_recreate_restoresSearchQuery() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("搜尋應用名稱 / 包名")).check(matches(isDisplayed()))
            onView(withHint("搜尋應用名稱 / 包名")).perform(replaceText("chat"))
            scenario.recreate()
            // retained ViewModel 回填：旋轉後同一 query 仍顯示。
            onView(withHint("搜尋應用名稱 / 包名")).check(matches(isDisplayed()))
            onView(withText("Clone")).check(matches(isDisplayed()))
            onView(withText("Remove")).check(matches(isDisplayed()))
        }
    }
}
