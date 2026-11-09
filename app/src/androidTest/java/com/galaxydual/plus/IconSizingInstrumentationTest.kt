// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase 4 圖示尺寸儀器骨架（AndroidJUnit4，需 emulator／裝置，本機 JVM 不跑）：
// - 骨架僅做「可編譯＋可執行」準備，不宣稱視覺已驗證。MainActivity 私有
//   sizedIcon／freshIconDrawable／transparentPlaceholder 無法 JVM 直測，故
//   JVM 層由 IconSizingPolicy 純函數鎖定（見 IconSizingPolicyTest），本檔
//   在裝置層對拍真實 View 與真實 density。
// - 斷言：來源 CheckBox compoundDrawable bounds == dp(SOURCE_ICON_DP)、
//   分身 ImageView scaleType=FIT_CENTER 且 adjustViewBounds=false 且
//   layout 為 36dp 方形；另以 ColorDrawable 打底的占位 bounds 骨架（儀器環境
//   下 ColorDrawable 為真 Android 實作，非 JVM stub）。
// - 常量分組（重複提示）：本檔頂層 EXPECTED_SOURCE_ICON_DP=24、
//   EXPECTED_CLONED_ICON_DP=36 為測試複本，對應 MainActivity companion
//   private const SOURCE_ICON_DP=24、CLONED_ICON_DP=36（private 無法直引，
//   故複本＋下方註明；常量改動時須人工同步兩處，否則裝置斷言才會紅）。
//   ICON_CONTAINER_DP=44 為外框非圖形，不在本檔斷言（勿混用）。
// - 設備／density 覆蓋說明（TODO 設備 matrix，commit 前須補實測）：
//   TODO(matrix)：API 29（mdpi 160，density 1.0，PR CI instrumentation）＋
//   API 34（xxhdpi 480，density 3.0，manual workflow_dispatch）＋
//   420dpi（density 2.625，非標準四捨五入對照機）三格；不同 density 下
//   dp(24)/dp(36) 的 px 不同，故本檔一律以 dpPx(dp, 裝置 density) 對拍，
//   不寫死 px。截圖 TODO：commit 訊息可用的畫面截圖需在上述三格各補一張
//   來源列與分身列特寫，否則不得宣稱圖示尺寸視覺驗證通過。
// - 無測試明示：本環境無 emulator，本檔未實際執行；僅保證
//   :app:assembleDebugAndroidTest 編譯通過。無測試指「裝置實測未跑」：
//   未覆蓋 adaptive glyph 等大、深色模式、不同字體縮放下的行高；
//   未覆蓋真實 PackageManager 未知 intrinsic（-1）以外的損壞 Drawable。
// - 寫入安全：全程經 FakeHarnessGateway 注入（零寫入），不點 Clone／移除；
//   圖示載入打真實 PackageManager 時用真實框架包名 android（虛構包名在
//   裝置上必然 NameNotFound 而永無 compoundDrawable）。
package com.galaxydual.plus

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 圖示 dp 分組（測試複本，見檔頭常量分組說明；MainActivity 單一來源為
// companion private const SOURCE_ICON_DP／CLONED_ICON_DP／ICON_CONTAINER_DP）。
private const val EXPECTED_SOURCE_ICON_DP = 24
private const val EXPECTED_CLONED_ICON_DP = 36

@RunWith(AndroidJUnit4::class)
class IconSizingInstrumentationTest {

    // 占位骨架：ColorDrawable 真實 setBounds 經 safeIconPx／placeholderDim
    // 收斂（0 → 1 保可見；-1 → 1；24 → 24），不啟 Activity 即可跑。
    @Test
    fun placeholder_colorDrawableBounds_clampedToOne() {
        val phZero = ColorDrawable(0)
        phZero.setBounds(0, 0, safeIconPx(0), safeIconPx(0))
        assertEquals(1, phZero.bounds.width())
        assertEquals(1, phZero.bounds.height())

        val phMinus = ColorDrawable(0)
        val dim = placeholderDim(-1, -1)
        phMinus.setBounds(0, 0, dim, dim)
        assertEquals(1, phMinus.bounds.width())
        assertEquals(1, phMinus.bounds.height())

        val ph24 = ColorDrawable(0)
        ph24.setBounds(0, 0, safeIconPx(24), safeIconPx(24))
        assertEquals(24, ph24.bounds.width())
        assertEquals(24, ph24.bounds.height())
    }

    // 裝置層：來源 CheckBox 左圖 bounds 為 dp(SOURCE_ICON_DP) 方形；分身
    // ImageView 為 FIT_CENTER、adjustViewBounds=false、36dp 方形 layout。
    // 以 dpPx(dp, 裝置 density) 對拍真實 density，不寫死 px。
    @Test
    fun activity_sourceCheckBoxAndClonedImageView_sizingPolicy() {
        val gw = FakeHarnessGateway(dualId = 95)
        gw.installedPkgs.clear()
        // 真實框架包名：裝置 PackageManager 能回真圖示（虛構包名必 NameNotFound）。
        gw.installedPkgs.add("android")
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            vm.setSourceCatalog(listOf(SourceAppEntry("android", "Android", 0, false)))
            vm.refresh()
            HarnessPoll.await { vm.state.value.cloned.contains("android") }
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // 等來源列圖示貼上（圖示載入為非同步 IO，活躍列可能被
                // reloadCatalog 的真實 catalog 重建後重貼，輪詢至任一
                // CheckBox 帶 compoundDrawable 為止）。
                HarnessPoll.await(15_000L) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = findCheckBoxWithIcon(activity) != null
                    }
                    ready
                }
                // 等分身 ImageView 建立（layout 在 renderClonedApps 建時固定）。
                HarnessPoll.await(10_000L) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = findClonedIconView(activity) != null
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    // 來源：dp(SOURCE_ICON_DP) 以 IconSizingPolicy.dpPx 對拍
                    // MainActivity.dp（SOURCE_ICON_DP 為 private const，複本見檔頭）。
                    val expectedSourcePx = dpPx(EXPECTED_SOURCE_ICON_DP, density)
                    val box = findCheckBoxWithIcon(activity)
                    assertNotNull("source CheckBox with compound icon must exist", box)
                    val icon = box!!.compoundDrawables[0]
                    assertNotNull("compound icon must exist", icon)
                    val bounds = icon!!.bounds
                    assertEquals(0, bounds.left)
                    assertEquals(0, bounds.top)
                    assertEquals(expectedSourcePx, bounds.right)
                    assertEquals(expectedSourcePx, bounds.bottom)
                    assertEquals(expectedSourcePx, bounds.width())
                    assertEquals(expectedSourcePx, bounds.height())
                    // 私有占位 seam：transparentPlaceholder(0) 經反射回 1×1
                    // ColorDrawable（經 safeIconPx 保可見）。
                    val ph = invokeTransparentPlaceholder(activity, 0)
                    assertTrue(ph is ColorDrawable)
                    assertEquals(1, ph.bounds.width())
                    assertEquals(1, ph.bounds.height())
                    // 分身：FIT_CENTER、adjustViewBounds=false、36dp 方形 layout。
                    val imageView = findClonedIconView(activity)
                    assertNotNull("cloned ImageView must exist", imageView)
                    assertEquals(ImageView.ScaleType.FIT_CENTER, imageView!!.scaleType)
                    assertFalse(imageView.adjustViewBounds)
                    val expectedClonedPx = dpPx(EXPECTED_CLONED_ICON_DP, density)
                    assertEquals(expectedClonedPx, imageView.layoutParams.width)
                    assertEquals(expectedClonedPx, imageView.layoutParams.height)
                }
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    // 遞迴找第一個符合謂詞的 View（Activity 層級版，供上述輪詢使用）。
    private fun findFirst(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirst(root.getChildAt(i), predicate)
                if (found != null) return found
            }
        }
        return null
    }

    // 找第一個帶 compound 左圖的 CheckBox（來源列；任一列即可，圖示
    // 非同步貼上故輪詢層呼叫）。
    private fun findCheckBoxWithIcon(activity: MainActivity): CheckBox? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        return findFirst(root) {
            it is CheckBox && it.compoundDrawables[0] != null
        } as? CheckBox
    }

    // 找分身 ImageView（tag 為 appKey 的 ImageView 唯有分身列圖示；其餘
    // ImageView 皆無 tag）。
    private fun findClonedIconView(activity: MainActivity): ImageView? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        return findFirst(root) {
            it is ImageView && it.tag != null
        } as? ImageView
    }

    // 私有 transparentPlaceholder 反射呼叫（簽名漂移即紅，見 ReflectionSeamTest）。
    private fun invokeTransparentPlaceholder(activity: MainActivity, sizePx: Int): android.graphics.drawable.Drawable {
        val method = MainActivity::class.java.getDeclaredMethod(
            "transparentPlaceholder",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(activity, sizePx) as android.graphics.drawable.Drawable
    }
}
