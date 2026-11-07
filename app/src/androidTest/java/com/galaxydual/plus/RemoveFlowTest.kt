// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29): RemoveFlowTest (instrumentation).
//
// 補齊 requestRemove 拒絕分支與 confirmRemove 成功路徑（皆在 FakeHarnessGateway
// 上、零真機寫入；confirm 成功只動 in-memory set）。
package com.galaxydual.plus

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoveFlowTest {

    // NotInstalled：套件不在 installedPkgs -> 零 uninstall、pending null。
    @Test
    fun requestRemove_notInstalled_noPending() {
        val gw = FakeHarnessGateway(dualId = 95)
        gw.installedPkgs.clear()
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await(30_000L) { vm.state.value.msg.startsWith("未安裝，無需移除") }
            assertNull(vm.state.value.pendingRemove)
            assertTrue(vm.state.value.msg.startsWith("未安裝，無需移除"))
            assertEquals(0, gw.uninstallCalls)
            assertEquals(0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // SerialUnavailable：id 確認但 serial 缺失 -> 拒絕、零 uninstall。
    @Test
    fun requestRemove_serialUnavailable_noPending() {
        val gw = FakeHarnessGateway(dualId = 95)
        gw.serialAvailable = false
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await(30_000L) { vm.state.value.msg == "無法驗證分身身分（serial 缺失），請重試" }
            assertNull(vm.state.value.pendingRemove)
            assertEquals("無法驗證分身身分（serial 缺失），請重試", vm.state.value.msg)
            assertEquals(0, gw.uninstallCalls)
            assertEquals(0, gw.writeCalls)
        } finally {
            vm.dispose()
        }
    }

    // confirm 成功：單次 uninstall、package 移除、pending 清除、msg "removed: ..."。
    @Test
    fun confirmRemove_success_uninstallsOnce() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.requestRemove("com.example.chat")
            HarnessPoll.await(30_000L) { vm.state.value.pendingRemove != null }
            vm.confirmRemove()
            HarnessPoll.await(30_000L) { vm.state.value.msg.startsWith("removed: ") }
            assertEquals(1, gw.uninstallCalls)
            assertEquals(1, gw.writeCalls)
            assertTrue(gw.commandLog.any { it == "pm uninstall --user 95 com.example.chat" })
            assertNull(vm.state.value.pendingRemove)
            assertTrue(!gw.installedPkgs.contains("com.example.chat"))
            // 重複 confirm 跑空（終態成功釘住），不追加 uninstall。
            vm.confirmRemove()
            HarnessPoll.await(30_000L) { !vm.state.value.loading && vm.state.value.msg.startsWith("removed: ") }
            assertEquals(1, gw.uninstallCalls)
            assertTrue(vm.state.value.msg.startsWith("removed: "))
        } finally {
            vm.dispose()
        }
    }
}
