// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29): CancelZeroWriteTest (instrumentation).
//
// 開確認框後取消/返回，斷言零 uninstall：requestRemove() 只記 pending（零寫入），
// cancelRemove() 清除 pending（零寫入），取消後再 confirm 跑空（零寫入）。
// 全程走 FakeHarnessGateway：uninstallCalls 是唯一寫入計數，測試永不碰真裝置寫入。
// Activity 側另做無寫入 smoke：啟動 MainActivity 只斷言 Clone/Remove 按鈕可見，
// 不點確認移除（真機/模擬器上無 verified dual 目標，request 會 fail-closed 拒絕）。
package com.galaxydual.plus

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CancelZeroWriteTest {

    // Phase 1 只記 pending：零 uninstall。
    @Test
    fun request_setsPendingWithoutUninstall() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await { vm.state.value.pendingRemove != null }
            assertNotNull(vm.state.value.pendingRemove)
            assertEquals("request must not uninstall", 0, gw.uninstallCalls)
            assertEquals("request must not write", 0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // 取消清除 pending：零 uninstall（等價於返回鍵取消：同走 cancelRemove 路徑，
    // Activity 的 setOnCancelListener 亦只調 vm.cancelRemove()）。
    @Test
    fun cancel_clearsPendingWithoutUninstall() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await { vm.state.value.pendingRemove != null }
            vm.cancelRemove()
            HarnessPoll.await { vm.state.value.pendingRemove == null }
            assertNull(vm.state.value.pendingRemove)
            assertEquals("cancel must not uninstall", 0, gw.uninstallCalls)
            assertEquals("cancel must not write", 0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // 取消後再 confirm：跑空（INVALID 無需確認），零 uninstall。
    @Test
    fun confirm_afterCancel_runsNothing() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await { vm.state.value.pendingRemove != null }
            vm.cancelRemove()
            HarnessPoll.await { vm.state.value.pendingRemove == null }
            vm.confirmRemove()
            HarnessPoll.await { vm.state.value.msg.contains("沒有待確認") }
            assertEquals("confirm after cancel must not uninstall", 0, gw.uninstallCalls)
            assertEquals("confirm after cancel must not write", 0, gw.writeCalls)
            assertNull(vm.state.value.pendingRemove)
        } finally {
            vm.dispose()
        }
    }

    // Activity smoke（無寫入）：啟動只斷言按鈕渲染，不觸發任何寫入路徑。
    @Test
    fun activity_launchesWithoutWrite() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Clone")).check(matches(isDisplayed()))
            onView(withText("Remove")).check(matches(isDisplayed()))
            onView(withText("重新連線並更新")).check(matches(isDisplayed()))
        }
    }
}
