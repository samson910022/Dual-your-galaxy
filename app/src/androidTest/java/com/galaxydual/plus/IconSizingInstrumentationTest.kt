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
// - 設備／density 實測記錄（2026-11-09）：
//   DONE：API 29 emulator-5554（mdpi 160，density 1.0）：connectedDebugAndroidTest
//   IconSizingInstrumentationTest 5/5 PASS（5 tests，failures 0，含 Layer2/3
//   snapshot-aware；僅 mdpi 實測，不代表其他 density）；API 34 真機
//   613f60b7（23013PC75G，density 560／3.5）：單機運行 2/2 PASS（舊 2-test 版，
//   當時未含 Layer1/2/3）。先前三機並行時真機因屏幕熄滅／process crashed
//   失敗，單跑＋喚醒後 PASS，屬環境爭用非代碼 bug。
//   TODO(matrix)：420dpi（density 2.625，非標準四捨五入對照機）未單獨跑：本檔
//   一律以 dpPx(dp, 裝置 density) 對拍不同 density 下 dp(24)／dp(36) 的 px
//   （不寫死 px），預期可移植但僅為預期、未實測，仍屬 TODO。截圖 TODO：仍無
//   畫面截圖，commit／PR 截圖 TODO 仍成立；深色模式與不同字體縮放亦仍 TODO，
//   不得宣稱圖示尺寸視覺驗證通過。未覆蓋 adaptive glyph 等大、真實
//   PackageManager 未知 intrinsic（-1）以外的損壞 Drawable。
// - 寫入安全：全程經 FakeHarnessGateway 注入（零寫入），不點 Clone／移除；
//   圖示載入打真實 PackageManager 時用真實框架包名 android（虛構包名在
//   裝置上必然 NameNotFound 而永無 compoundDrawable）。
// - 時序改法說明（PR #51 P2-3 對應，Phase B）：舊寫法三次 onActivity 分開
//   輪詢（來源存在 → 分身存在 → 最終斷言），其間 reloadCatalog 的真實 catalog
//   可替換測試 catalog（android 為 system app，預設 includeSystemApps=false
//   時來源列被移除），最終斷言可能在新圖示回填前失效致偶發失敗。新寫法改為
//   單次 HarnessPoll.await(15000) 內 capture＋readiness 同時判斷
//   （sourceIcon!=null && clonedDrawable!=null），poll 內只回布林、不斷言，
//   poll 外才對拷貝快照斷言（bounds/layout/scaleType/non-null）；poll 內加
//   catalog 重注入守衛（sourceCatalog 內容相等比對，缺失則 setSourceCatalog
//  ＋快照置 null 後繼續輪詢），避免跨 callback 沿用已被重建的 readiness 結果。
//   finder 同時精確化：來源以 tag=="0:android"（sourceAppKey）定位、分身以
//   vm.state.inventory.targetSerial 衍生 tag（na fallback）定位，不再以
//   「任一帶圖 CheckBox／任一帶 tag ImageView」近似。
package com.galaxydual.plus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// 圖示 dp 分組（測試複本，見檔頭常量分組說明；MainActivity 單一來源為
// companion private const SOURCE_ICON_DP／CLONED_ICON_DP／ICON_CONTAINER_DP）。
private const val EXPECTED_SOURCE_ICON_DP = 24
private const val EXPECTED_CLONED_ICON_DP = 36

// 精確 finder 鍵：來源 sourceSerial=0 對應 "0:android"（sourceAppKey），
// 分身由 vm.state.inventory.targetSerial 衍生（null 用 "na"，見 capture）。
private const val SOURCE_APP_KEY = "0:android"

// Phase B P2-3 快照：在主執行緒 capture 後供 poll 外斷言的拷貝。drawable 本身
// 仍保留引用以驗實例隔離，但 bounds 一律另存 Rect 拷貝（自身 bounds 拷貝不改
// 驗證對象），pixel 以 drawable bounds 尺寸繪到 Bitmap 後存 Bitmap＋不透明統計。
private data class IconSnapshot(
    val density: Float,
    val sourceBox: CheckBox?,
    val sourceIcon: Drawable?,
    val sourceBounds: Rect?,
    val sourceTag: String?,
    val sourceBitmap: Bitmap?,
    val sourceOpaquePixels: Int,
    val clonedView: ImageView?,
    val clonedDrawable: Drawable?,
    val clonedTag: String?,
    val clonedLayoutPx: Int?,
    val clonedLayoutHeightPx: Int?,
    val clonedBitmap: Bitmap?,
    val clonedOpaquePixels: Int,
    val placeholder: Drawable?,
    val sourceCached: Drawable?,
    val clonedCached: Drawable?,
    val sourceCachedBounds: Rect?,
    val clonedCachedBounds: Rect?,
)

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
    // Phase B：單次 poll 內 capture＋readiness（sourceIcon 與 clonedDrawable
    // 同時非空），poll 外對拷貝斷言；poll 內含 catalog 重注入守衛。另覆蓋
    // query 切換重綁（snap/snap2/snap3 每次來源新實例＋bounds 正確）。
    @Test
    fun activity_sourceCheckBoxAndClonedImageView_sizingPolicy() {
        val gw = FakeHarnessGateway(dualId = 95)
        gw.installedPkgs.clear()
        // 真實框架包名：裝置 PackageManager 能回真圖示（虛構包名必 NameNotFound）。
        gw.installedPkgs.add("android")
        val vm = newHarnessViewModel(gw)
        val expectedCatalog = listOf(SourceAppEntry("android", "Android", 0, false))
        try {
            awaitHarnessReady(vm)
            vm.setSourceCatalog(expectedCatalog)
            vm.refresh()
            HarnessPoll.await { vm.state.value.cloned.contains("android") }
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val snap1 = awaitSnapshot(vm, expectedCatalog, scenario)
                assertSnapshot(snap1)

                // query 切換 1：先以不匹配 query 隱藏來源列，待重建完成（列消失）
                // 後再清掉 query 觸發重綁，取 snap2 驗新實例＋bounds 正確。
                vm.setSourceQuery("no_such_app_xyz_zzz")
                awaitSourceGone(vm, expectedCatalog, scenario)
                vm.setSourceQuery("")
                val snap2 = awaitSnapshot(vm, expectedCatalog, scenario)
                assertSnapshot(snap2)
                assertNotSame("snap2 sourceIcon must be new instance after query rebind", snap1.sourceIcon, snap2.sourceIcon)
                assertNotSame("snap2 sourceBox must be new instance after query rebind", snap1.sourceBox, snap2.sourceBox)
                assertEquals("snap1 bounds copy must not mutate after snap2", snap1.sourceBounds, Rect(0, 0, dpPx(EXPECTED_SOURCE_ICON_DP, snap1.density), dpPx(EXPECTED_SOURCE_ICON_DP, snap1.density)))

                // query 切換 2：換另一個不匹配字串同樣先隱藏再顯示，取 snap3。
                vm.setSourceQuery("zzz_another_no_match")
                awaitSourceGone(vm, expectedCatalog, scenario)
                vm.setSourceQuery("")
                val snap3 = awaitSnapshot(vm, expectedCatalog, scenario)
                assertSnapshot(snap3)
                assertNotSame("snap3 sourceIcon must be new instance after query rebind", snap2.sourceIcon, snap3.sourceIcon)
                assertNotSame("snap3 sourceBox must be new instance after query rebind", snap2.sourceBox, snap3.sourceBox)
                assertEquals("snap1 bounds copy must not mutate after snap3", snap1.sourceBounds, Rect(0, 0, dpPx(EXPECTED_SOURCE_ICON_DP, snap1.density), dpPx(EXPECTED_SOURCE_ICON_DP, snap1.density)))
                assertEquals("snap2 bounds copy must not mutate after snap3", snap2.sourceBounds, Rect(0, 0, dpPx(EXPECTED_SOURCE_ICON_DP, snap2.density), dpPx(EXPECTED_SOURCE_ICON_DP, snap2.density)))
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    // Layer1 直接 seam：48x24 紅底藍圓 BitmapDrawable（保持 FILL）經反射
    // sizedIcon／freshIconDrawable 取得實例後畫到 Bitmap，斷中心藍、邊紅且
    // 非透明（不鎖死精確色值，僅驗主色 dominant＋alpha>0，抗縮放插值）。
    // 此層覆蓋的正是 Phase A 快照前的原始 seam：direct sizedIcon 仍是
    // setBounds 強制方形、fresh 仍是 newDrawable 拷貝，中心／邊顏色應保留；
    // 真正的等比判別留到下方 Layer2（snapshot-aware，已啟用）。
    @Test
    fun directSeam_sizedAndFresh_redBlueCircle_centerBlueEdgeRed() {
        val gw = FakeHarnessGateway(dualId = 95)
        gw.installedPkgs.clear()
        gw.installedPkgs.add("android")
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var sizedBitmap: Bitmap? = null
                var freshBitmap: Bitmap? = null
                var sizedSizePx = 0
                var sizedRef: Drawable? = null
                var freshRef: Drawable? = null
                var fixtureRef: Drawable? = null
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    sizedSizePx = dpPx(EXPECTED_SOURCE_ICON_DP, density)
                    val srcBmp = createRedBlueBitmap48x24()
                    val fixture = BitmapDrawable(activity.resources, srcBmp).apply {
                        setGravity(Gravity.FILL)
                        setBounds(0, 0, 48, 24)
                    }
                    fixtureRef = fixture
                    val sized = invokeSizedIcon(activity, fixture, sizedSizePx)
                    sizedRef = sized
                    sizedBitmap = drawableToBitmapCopy(sized)

                    val fresh = invokeFreshIconDrawable(activity, fixture)
                    freshRef = fresh
                    freshBitmap = drawableToBitmapCopy(fresh)
                }
                assertNotNull("sized bitmap must exist", sizedBitmap)
                assertNotNull("fresh bitmap must exist", freshBitmap)
                assertNotNull("sized drawable ref must exist", sizedRef)
                assertNotNull("fresh drawable ref must exist", freshRef)
                assertNotNull("fixture ref must exist", fixtureRef)
                assertNotSame("sized must be new instance, never cached fixture", fixtureRef, sizedRef)
                assertNotSame("fresh must be new instance, never cached fixture", fixtureRef, freshRef)
                assertEquals(sizedSizePx, sizedBitmap!!.width)
                assertEquals(sizedSizePx, sizedBitmap!!.height)
                assertBitmapCenterBlueEdgeRed(sizedBitmap!!, "sized")
                assertBitmapCenterBlueEdgeRed(freshBitmap!!, "fresh")
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    // Layer2 snapshot-aware：48x24 紅底藍圓 fixture（FILL）經反射
    // bitmapSnapshotDrawable 等比快照（dst 固定 dp(36) 方形，實作忽略
    // 傳入 dstPx 以 dp(36) 規範化，故此處傳 dp(36)，文案畫 24x24 已改）。
    // 判別：fitCenterRect 留邊使 top 25% 透明（FILL 拉伸則紅色不透明），
    // 斷 snapshot 非空／BitmapDrawable／bounds 為 dp(36) 方形、中心藍
    // dominant、中心 x 且 y<h*0.25 處 alpha==0（letterbox），再經
    // sizedIcon(snapshot, dp24)／freshIconDrawable(snapshot) 仍可見且
    // 頂部透明、四方互異。勿只打 direct sizedIcon（仍是拉伸，必然紅）。
    @Test
    fun snapshotAware_redBlue48x24_fitCenterLetterboxAndCopiesVisible() {
        val gw = FakeHarnessGateway(dualId = 95)
        gw.installedPkgs.clear()
        gw.installedPkgs.add("android")
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var snapshotBitmap: Bitmap? = null
                var sizedBitmap: Bitmap? = null
                var freshBitmap: Bitmap? = null
                var expected36Px = 0
                var expected24Px = 0
                var snapshotBoundsCopy: Rect? = null
                var snapshotRef: Drawable? = null
                var sizedRef: Drawable? = null
                var freshRef: Drawable? = null
                var fixtureRef: Drawable? = null
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    expected36Px = dpPx(EXPECTED_CLONED_ICON_DP, density)
                    expected24Px = dpPx(EXPECTED_SOURCE_ICON_DP, density)
                    val srcBmp = createRedBlueBitmap48x24()
                    val fixture = BitmapDrawable(activity.resources, srcBmp).apply {
                        setGravity(Gravity.FILL)
                        setBounds(0, 0, 48, 24)
                    }
                    fixtureRef = fixture
                    val snapshot = invokeBitmapSnapshot(activity, fixture, expected36Px)
                    snapshotRef = snapshot
                    snapshotBoundsCopy = snapshot?.bounds?.let { Rect(it) }
                    snapshotBitmap = snapshot?.let { drawableToBitmapCopy(it) }
                    if (snapshot != null) {
                        val sized = invokeSizedIcon(activity, snapshot, expected24Px)
                        sizedRef = sized
                        sizedBitmap = drawableToBitmapCopy(sized)
                        val fresh = invokeFreshIconDrawable(activity, snapshot)
                        freshRef = fresh
                        freshBitmap = drawableToBitmapCopy(fresh)
                    }
                }
                assertNotNull("snapshot must exist", snapshotRef)
                assertNotNull("snapshot bitmap must exist", snapshotBitmap)
                assertNotNull("snapshot bounds copy must exist", snapshotBoundsCopy)
                assertNotNull("sized bitmap must exist", sizedBitmap)
                assertNotNull("fresh bitmap must exist", freshBitmap)
                assertTrue("snapshot must be BitmapDrawable", snapshotRef is BitmapDrawable)
                val snapshotDraw = snapshotRef!!
                val snapshotBmp = snapshotBitmap!!
                val snapshotBounds = snapshotBoundsCopy!!
                assertEquals(Rect(0, 0, expected36Px, expected36Px), snapshotBounds)
                assertEquals(expected36Px, snapshotBounds.width())
                assertEquals(expected36Px, snapshotBounds.height())
                assertEquals(expected36Px, snapshotBmp.width)
                assertEquals(expected36Px, snapshotBmp.height)
                assertTrue("snapshot must have visible opaque pixels, got ${countOpaquePixels(snapshotBmp)}", countOpaquePixels(snapshotBmp) > 0)
                assertBitmapCenterBlueDominant(snapshotBmp, "snapshot")
                assertBitmapTopLetterboxTransparent(snapshotBmp, "snapshot")
                assertTrue("snapshot top band must be transparent but middle opaque (letterbox, not blank)", countOpaquePixels(snapshotBmp) < snapshotBmp.width * snapshotBmp.height)
                assertTrue("sized(snapshot) must be visible, got ${countOpaquePixels(sizedBitmap)}", countOpaquePixels(sizedBitmap) > 0)
                assertTrue("fresh(snapshot) must be visible, got ${countOpaquePixels(freshBitmap)}", countOpaquePixels(freshBitmap) > 0)
                assertBitmapCenterBlueDominant(sizedBitmap!!, "sized(snapshot)")
                assertBitmapTopLetterboxTransparent(sizedBitmap!!, "sized(snapshot)")
                assertBitmapCenterBlueDominant(freshBitmap!!, "fresh(snapshot)")
                assertBitmapTopLetterboxTransparent(freshBitmap!!, "fresh(snapshot)")
                assertNotSame("snapshot must be new instance, never cached fixture", fixtureRef, snapshotRef)
                assertNotSame("sized(snapshot) must be distinct from snapshot", snapshotRef, sizedRef)
                assertNotSame("fresh(snapshot) must be distinct from snapshot", snapshotRef, freshRef)
                assertNotSame("sized and fresh of snapshot must be distinct", sizedRef, freshRef)
                assertNotSame("sized(snapshot) must be new instance, never cached fixture", fixtureRef, sizedRef)
                assertNotSame("fresh(snapshot) must be new instance, never cached fixture", fixtureRef, freshRef)
                assertNotSame("fixture/snapshot/sized/fresh sanity (drawable identity)", snapshotDraw, sizedRef)
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    private class PatternDrawable : Drawable() {
        private val paint = Paint().apply { color = Color.MAGENTA }
        override fun draw(canvas: Canvas) {
            canvas.drawColor(Color.MAGENTA)
            canvas.drawCircle(bounds.width() / 2f, bounds.height() / 2f, bounds.width() / 6f, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.OPAQUE
        override fun getIntrinsicWidth(): Int = 48
        override fun getIntrinsicHeight(): Int = 24
        // 刻意不覆寫 getConstantState，constantState 保持 null（見下方 Layer3）。
    }

    // Layer3 snapshot-aware：PatternDrawable 不覆寫 getConstantState
    // （constantState==null），模擬 AnimatedImageDrawable 等框架圖示。
    // Phase A 快照後，經 bitmapSnapshotDrawable 轉成普通 BitmapDrawable
    // 才可 fresh/sized 可見；勿直接打 raw PatternDrawable 的 sized/fresh
    // （null ConstantState 會走透明占位，屬 P2-1 已知路徑，不在此驗）。
    @Test
    fun snapshotAware_patternNoConstantState_visibleAndDistinct() {
        val gw = FakeHarnessGateway(dualId = 95)
        gw.installedPkgs.clear()
        gw.installedPkgs.add("android")
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var snapshotBitmap: Bitmap? = null
                var sizedBitmap: Bitmap? = null
                var freshBitmap: Bitmap? = null
                var expected36Px = 0
                var expected24Px = 0
                var patternConstantStateNull = false
                var snapshotRef: Drawable? = null
                var sizedRef: Drawable? = null
                var freshRef: Drawable? = null
                var patternRef: Drawable? = null
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    expected36Px = dpPx(EXPECTED_CLONED_ICON_DP, density)
                    expected24Px = dpPx(EXPECTED_SOURCE_ICON_DP, density)
                    val pattern = PatternDrawable()
                    patternRef = pattern
                    patternConstantStateNull = pattern.constantState == null
                    val snapshot = invokeBitmapSnapshot(activity, pattern, expected36Px)
                    snapshotRef = snapshot
                    snapshotBitmap = snapshot?.let { drawableToBitmapCopy(it) }
                    if (snapshot != null) {
                        val sized = invokeSizedIcon(activity, snapshot, expected24Px)
                        sizedRef = sized
                        sizedBitmap = drawableToBitmapCopy(sized)
                        val fresh = invokeFreshIconDrawable(activity, snapshot)
                        freshRef = fresh
                        freshBitmap = drawableToBitmapCopy(fresh)
                    }
                }
                assertTrue("PatternDrawable premise: constantState must be null", patternConstantStateNull)
                assertNotNull("snapshot of PatternDrawable must exist", snapshotRef)
                assertNotNull("snapshot bitmap must exist", snapshotBitmap)
                assertNotNull("sized bitmap must exist", sizedBitmap)
                assertNotNull("fresh bitmap must exist", freshBitmap)
                assertTrue("snapshot must be BitmapDrawable", snapshotRef is BitmapDrawable)
                assertTrue("snapshot opaque pixels must be >0, got ${countOpaquePixels(snapshotBitmap)}", countOpaquePixels(snapshotBitmap) > 0)
                assertTrue("sized(snapshot) must be visible, got ${countOpaquePixels(sizedBitmap)}", countOpaquePixels(sizedBitmap) > 0)
                assertTrue("fresh(snapshot) must be visible, got ${countOpaquePixels(freshBitmap)}", countOpaquePixels(freshBitmap) > 0)
                assertNotSame("snapshot must be new instance, never raw pattern", patternRef, snapshotRef)
                assertNotSame("sized(snapshot) must be distinct from snapshot", snapshotRef, sizedRef)
                assertNotSame("fresh(snapshot) must be distinct from snapshot", snapshotRef, freshRef)
                assertNotSame("sized and fresh of snapshot must be distinct", sizedRef, freshRef)
                assertNotSame("sized(snapshot) must be new instance, never raw pattern", patternRef, sizedRef)
                assertNotSame("fresh(snapshot) must be new instance, never raw pattern", patternRef, freshRef)
            }
        } finally {
            MainActivity.testViewModel = null
            vm.dispose()
        }
    }

    // 單次 poll 包裝：poll 內只布林（capture＋雙非空），poll 外回拷貝。
    // poll 內 catalog 重注入守衛：sourceCatalog 內容不等即
    // setSourceCatalog＋snapshot 置 null 後繼續（返回 false），避免沿用
    // 已被 reloadCatalog 真實 catalog 替換後的 stale 就緒結果。
    private fun awaitSnapshot(
        vm: DualViewModel,
        expectedCatalog: List<SourceAppEntry>,
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 15_000L,
    ): IconSnapshot {
        var snapshot: IconSnapshot? = null
        HarnessPoll.await(timeoutMs) {
            if (vm.state.value.sourceCatalog != expectedCatalog) {
                vm.setSourceCatalog(expectedCatalog)
                snapshot = null
                return@await false
            }
            scenario.onActivity { activity ->
                snapshot = captureSnapshot(activity, vm)
            }
            val snap = snapshot
            snap != null && snap.sourceIcon != null && snap.clonedDrawable != null
        }
        return snapshot!!
    }

    // 等待來源列被 query 隱藏（重建後 tag 找不到），確保下一次 show 是真重綁
    // 而非舊實例回讀。同樣帶 catalog 重注入守衛。
    private fun awaitSourceGone(
        vm: DualViewModel,
        expectedCatalog: List<SourceAppEntry>,
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 15_000L,
    ) {
        HarnessPoll.await(timeoutMs) {
            if (vm.state.value.sourceCatalog != expectedCatalog) {
                vm.setSourceCatalog(expectedCatalog)
                return@await false
            }
            var gone = false
            scenario.onActivity { activity ->
                gone = findCheckBoxByTag(activity, SOURCE_APP_KEY) == null
            }
            gone
        }
    }

    // 主執行緒 capture：來源以 sourceAppKey、分身以
    // inventory.targetSerial 衍生 tag（na fallback）精確定位；
    // drawable bounds 尺寸畫 pixel 存 Bitmap＋不透明統計；占位與快取實例
    // 一併拷貝，供 poll 外斷言（poll 內不做任何 assert）。
    private fun captureSnapshot(activity: MainActivity, vm: DualViewModel): IconSnapshot {
        val density = activity.resources.displayMetrics.density
        val sourceBox = findCheckBoxByTag(activity, SOURCE_APP_KEY)
        val sourceIcon = sourceBox?.compoundDrawables?.getOrNull(0)
        val sourceBounds = sourceIcon?.bounds?.let { Rect(it) }
        val sourceBitmap = sourceIcon?.let { drawableToBitmapCopy(it) }
        val clonedTag = "${vm.state.value.inventory.targetSerial ?: "na"}:android"
        val clonedView = findImageViewByTag(activity, clonedTag)
        val clonedDrawable = clonedView?.drawable
        val clonedLayoutPx = try { clonedView?.layoutParams?.width } catch (_: Exception) { null }
        val clonedLayoutHeightPx = try { clonedView?.layoutParams?.height } catch (_: Exception) { null }
        val clonedBitmap = clonedDrawable?.let { drawableToBitmapCopy(it) }
        val placeholder = invokeTransparentPlaceholder(activity, 0)
        val drawables = readIconDrawables(activity)
        val sourceCached = drawables[SOURCE_APP_KEY]
        val clonedCached = drawables[clonedTag]
        val sourceCachedBounds = sourceCached?.bounds?.let { Rect(it) }
        val clonedCachedBounds = clonedCached?.bounds?.let { Rect(it) }
        return IconSnapshot(
            density = density,
            sourceBox = sourceBox,
            sourceIcon = sourceIcon,
            sourceBounds = sourceBounds,
            sourceTag = sourceBox?.tag?.toString(),
            sourceBitmap = sourceBitmap,
            sourceOpaquePixels = countOpaquePixels(sourceBitmap),
            clonedView = clonedView,
            clonedDrawable = clonedDrawable,
            clonedTag = clonedView?.tag?.toString(),
            clonedLayoutPx = clonedLayoutPx,
            clonedLayoutHeightPx = clonedLayoutHeightPx,
            clonedBitmap = clonedBitmap,
            clonedOpaquePixels = countOpaquePixels(clonedBitmap),
            placeholder = placeholder,
            sourceCached = sourceCached,
            clonedCached = clonedCached,
            sourceCachedBounds = sourceCachedBounds,
            clonedCachedBounds = clonedCachedBounds,
        )
    }

    // poll 外斷言：bounds/layout/scaleType/non-null＋pixel 非全透明＋實例隔離。
    // 真機 android 圖示只斷不透明 pixel 數 >0，不鎖死顏色（裝置差異容忍）。
    private fun assertSnapshot(snap: IconSnapshot) {
        assertNotNull("source CheckBox with tag=$SOURCE_APP_KEY must exist", snap.sourceBox)
        assertNotNull("source compound icon must exist", snap.sourceIcon)
        assertNotNull("source bounds copy must exist", snap.sourceBounds)
        assertNotNull("cloned ImageView must exist", snap.clonedView)
        assertNotNull("cloned drawable must exist", snap.clonedDrawable)
        assertNotNull("placeholder must exist", snap.placeholder)
        assertEquals(SOURCE_APP_KEY, snap.sourceTag)
        val expectedSourcePx = dpPx(EXPECTED_SOURCE_ICON_DP, snap.density)
        val bounds = snap.sourceBounds!!
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(expectedSourcePx, bounds.right)
        assertEquals(expectedSourcePx, bounds.bottom)
        assertEquals(expectedSourcePx, bounds.width())
        assertEquals(expectedSourcePx, bounds.height())
        assertTrue(ph_is(snap.placeholder))
        val ph = snap.placeholder!!
        assertEquals(1, ph.bounds.width())
        assertEquals(1, ph.bounds.height())
        val imageView = snap.clonedView!!
        assertEquals(ImageView.ScaleType.FIT_CENTER, imageView.scaleType)
        assertFalse(imageView.adjustViewBounds)
        val expectedClonedPx = dpPx(EXPECTED_CLONED_ICON_DP, snap.density)
        assertEquals(expectedClonedPx, snap.clonedLayoutPx)
        assertEquals(expectedClonedPx, snap.clonedLayoutHeightPx)
        assertTrue("source icon pixel must be non-transparent (>0), got ${snap.sourceOpaquePixels}", snap.sourceOpaquePixels > 0)
        assertTrue("cloned drawable pixel must be non-transparent (>0), got ${snap.clonedOpaquePixels}", snap.clonedOpaquePixels > 0)
        // 實例隔離（cached null-tolerant：快取未命中／淘汰時不硬斷）。
        if (snap.sourceCached != null) {
            assertNotSame("source view icon must not be cached instance", snap.sourceCached, snap.sourceIcon)
        }
        if (snap.clonedCached != null) {
            assertNotSame("cloned view drawable must not be cached instance", snap.clonedCached, snap.clonedDrawable)
        }
        assertNotSame("source and cloned must be distinct instances", snap.sourceIcon, snap.clonedDrawable)
    }

    private fun ph_is(d: Drawable?): Boolean = d is ColorDrawable

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

    // 精確找來源 CheckBox（tag 精確等於 sourceAppKey）。
    private fun findCheckBoxByTag(activity: MainActivity, tag: String): CheckBox? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        return findFirst(root) {
            it is CheckBox && it.tag == tag
        } as? CheckBox
    }

    // 精確找分身 ImageView（tag 精確等於分身 appKey）。
    private fun findImageViewByTag(activity: MainActivity, tag: String): ImageView? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        return findFirst(root) {
            it is ImageView && it.tag == tag
        } as? ImageView
    }

    // 以 drawable bounds 尺寸畫到 Bitmap（bounds 空時退回 intrinsic）。
    // 暫存舊 bounds（Rect 拷貝）供畫後還原，避免擾動 View 上綁定的 drawable。
    private fun drawableToBitmapCopy(drawable: Drawable): Bitmap? {
        val saved = Rect(drawable.bounds)
        val w = saved.width().takeIf { it > 0 } ?: drawable.intrinsicWidth
        val h = saved.height().takeIf { it > 0 } ?: drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bmp))
        } finally {
            try {
                drawable.setBounds(saved)
            } catch (_: Exception) {}
        }
        return bmp
    }

    private fun countOpaquePixels(bitmap: Bitmap?): Int {
        if (bitmap == null) return 0
        var count = 0
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
            }
        }
        return count
    }

    private fun createRedBlueBitmap48x24(): Bitmap {
        val bmp = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.RED)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLUE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(24f, 12f, 6f, paint)
        return bmp
    }

    private fun assertBitmapCenterBlueEdgeRed(bmp: Bitmap, label: String) {
        val w = bmp.width
        val h = bmp.height
        val center = bmp.getPixel(w / 2, h / 2)
        val edge = bmp.getPixel(2, 2)
        assertTrue("$label center must be non-transparent", Color.alpha(center) > 0)
        assertTrue("$label edge must be non-transparent", Color.alpha(edge) > 0)
        assertTrue("$label center must be blue-dominant (blue=${Color.blue(center)} red=${Color.red(center)})", Color.blue(center) > Color.red(center))
        assertTrue("$label edge must be red-dominant (red=${Color.red(edge)} blue=${Color.blue(edge)})", Color.red(edge) > Color.blue(edge))
    }

    // Layer2 snapshot 專用：中心藍 dominant（不查邊緣，邊緣為 letterbox 透明）。
    private fun assertBitmapCenterBlueDominant(bmp: Bitmap, label: String) {
        val w = bmp.width
        val h = bmp.height
        val center = bmp.getPixel(w / 2, h / 2)
        assertTrue("$label center must be non-transparent", Color.alpha(center) > 0)
        assertTrue("$label center must be blue-dominant (blue=${Color.blue(center)} red=${Color.red(center)})", Color.blue(center) > Color.red(center))
    }

    // Layer2 snapshot 專用：頂部 letterbox。中心 x、y<h*0.25 處必須透明
    // （等比置中留邊）；FILL 拉伸才會紅色不透明。取 y=h/8（<h*0.25）
    // 避開邊界插值。另查圖非空白（中心非透明由 assertBitmapCenterBlueDominant）。
    private fun assertBitmapTopLetterboxTransparent(bmp: Bitmap, label: String) {
        val w = bmp.width
        val h = bmp.height
        val sampleY = (h / 8).coerceIn(0, h - 1)
        assertTrue("sampleY must be < h*0.25 (letterbox band), sampleY=$sampleY h=$h", sampleY < h * 0.25f)
        val topPixel = bmp.getPixel(w / 2, sampleY)
        assertEquals("$label top center (y=$sampleY < h*0.25) must be transparent (letterbox)", 0, Color.alpha(topPixel))
    }

    // 反射讀快取 drawable 表（by appKey；失敗回空表，呼叫端 null-tolerant）。
    private fun readIconDrawables(activity: MainActivity): Map<String, Drawable> {
        return try {
            val field = MainActivity::class.java.getDeclaredField("iconDrawables")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(activity) as? Map<String, Drawable>)?.toMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // 私有 transparentPlaceholder 反射呼叫（簽名漂移即紅，見 ReflectionSeamTest）。
    private fun invokeTransparentPlaceholder(activity: MainActivity, sizePx: Int): Drawable {
        val method = MainActivity::class.java.getDeclaredMethod(
            "transparentPlaceholder",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(activity, sizePx) as Drawable
    }

    private fun invokeSizedIcon(activity: MainActivity, drawable: Drawable, sizePx: Int): Drawable {
        val method = MainActivity::class.java.getDeclaredMethod(
            "sizedIcon",
            Drawable::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(activity, drawable, sizePx) as Drawable
    }

    private fun invokeFreshIconDrawable(activity: MainActivity, drawable: Drawable): Drawable {
        val method = MainActivity::class.java.getDeclaredMethod(
            "freshIconDrawable",
            Drawable::class.java,
        )
        method.isAccessible = true
        return method.invoke(activity, drawable) as Drawable
    }

    // 私有 bitmapSnapshotDrawable 反射呼叫（MainActivity:4104）。實作忽略
    // 傳入 dstPx，以 clampSnapshotPx(dp(36), CAP) 規範化，故呼叫端一律傳
    // dp(36)（即 dpPx(36, density)）；簽名漂移即紅。回值可空（失敗回 null）。
    private fun invokeBitmapSnapshot(activity: MainActivity, drawable: Drawable, dstPx: Int): Drawable? {
        val method = MainActivity::class.java.getDeclaredMethod(
            "bitmapSnapshotDrawable",
            Drawable::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(activity, drawable, dstPx) as? Drawable
    }
}
