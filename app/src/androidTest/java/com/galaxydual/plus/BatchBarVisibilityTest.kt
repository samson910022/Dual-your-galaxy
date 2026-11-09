// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage1：sticky batch bar 只屬雙開應用頁。切到 Engine tab 時其容器（batchBtn
// 的 parent）必須 GONE，切回雙開應用頁恢復 VISIBLE；全程不點任何寫入按鈕。
package com.galaxydual.plus

import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxydual.plus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchBarVisibilityTest {

    private fun batchBar(activity: MainActivity): View {
        val batchBtn = activity.findViewById<Button>(R.id.batch_btn)
        return batchBtn.parent as View
    }

    @Test
    fun batchBar_onlyVisibleOnDualAppsTab() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // 預設雙開應用頁：sticky batch bar 可見。
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, batchBar(activity).visibility)
                    assertTrue(activity.findViewById<Button>(R.id.batch_btn).isShown)
                }
                // 切到 Engine tab：batch bar 隱藏（避免命令列引擎頁出現批次 CTA）。
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_CORE_ENGINE, announce = false)
                }
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, batchBar(activity).visibility)
                    assertFalse(activity.findViewById<Button>(R.id.batch_btn).isShown)
                }
                // 切回雙開應用頁：batch bar 恢復可見。
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_DUAL_APPS, announce = false)
                }
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, batchBar(activity).visibility)
                    assertTrue(activity.findViewById<Button>(R.id.batch_btn).isShown)
                }
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }
}
