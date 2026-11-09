// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase 4 圖示尺寸策略 JVM 單測（純函數直呼，無 Android 依賴、無裝置）：
// - 鎖定 IconSizingPolicy 四個純函數：safeIconPx、dpPx、placeholderDim、
//   shouldUsePlaceholder（對應 MainActivity 的 transparentPlaceholder、dp、
//   freshIconDrawable、sizedIcon／fresh fallback，比照 SourceIconState／
//   pickIconEvictionKey 的 JVM 可測 seam 模式）。
// - 命名沿 InventorySelectionTest 慣例（英文小寫下劃線），註解統一繁中，
//   以「圖示」為準。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSizingPolicyTest {

    // 占位 保底：0／負值收斂為 1（保可見），正值不變。
    @Test
    fun safeIconPx_clampsNonPositiveToOne() {
        assertEquals(1, safeIconPx(0))
        assertEquals(1, safeIconPx(-1))
        assertEquals(1, safeIconPx(Int.MIN_VALUE))
        assertEquals(1, safeIconPx(1))
        assertEquals(24, safeIconPx(24))
    }

    // 占位 保底：來源 24dp 的 px 必為正（所有 density 下皆 >0）。
    @Test
    fun safeIconPx_positiveStaysPositive() {
        assertTrue(safeIconPx(24) > 0)
        assertTrue(safeIconPx(dpPx(24, 0.75f)) > 0)
        assertTrue(safeIconPx(dpPx(24, 3.0f)) > 0)
    }

    // dp 四捨五入：24 × 2.625 = 63（官方範例密度）；另以小數部分 >= 0.5 的
    // 6dp 證明非截斷（15.75 → 16，截斷為 15），守住非標準 density 不系統性偏小。
    @Test
    fun dpPx_roundsHalfUp() {
        assertEquals(63, dpPx(24, 2.625f))
        assertEquals(16, dpPx(6, 2.625f))
        assertEquals(37, dpPx(14, 2.625f))
    }

    // 低密度最小單位：1dp × 0.75 + 0.5 = 1.25 → 1（截斷為 0，不可見）。
    @Test
    fun dpPx_lowDensityMinimumIsOne() {
        assertEquals(1, dpPx(1, 0.75f))
    }

    // 整數 density 不變：公式在整數 density 下還原精確 px。
    @Test
    fun dpPx_integerDensityExact() {
        assertEquals(48, dpPx(48, 1.0f))
        assertEquals(48, dpPx(24, 2.0f))
        assertEquals(72, dpPx(24, 3.0f))
        assertEquals(108, dpPx(36, 3.0f))
        assertEquals(16, dpPx(16, 1.0f))
    }

    // 方形占位：取較大邊；intrinsic 未知（-1）／0 收斂為 1。
    @Test
    fun placeholderDim_takesSquareMaxClampedToOne() {
        assertEquals(48, placeholderDim(48, 24))
        assertEquals(48, placeholderDim(24, 48))
        assertEquals(24, placeholderDim(24, 24))
        assertEquals(36, placeholderDim(0, 36))
        assertEquals(12, placeholderDim(-1, 12))
        assertEquals(1, placeholderDim(-1, -1))
        assertEquals(1, placeholderDim(0, 0))
    }

    // 永不回快取實例：newDrawable 成功用 fresh（不占位），失敗用占位。
    @Test
    fun shouldUsePlaceholder_locksNeverReturnCachedSemantics() {
        assertFalse(shouldUsePlaceholder(true))
        assertTrue(shouldUsePlaceholder(false))
    }

    // 等比置中：48x24 在 24 方框內為置中 24x12（P2-2 拉伸回歸）。
    @Test
    fun fitCenterRect_nonSquareCentered() {
        assertEquals(FitRect(0, 6, 24, 18), fitCenterRect(48, 24, 24))
        assertEquals(FitRect(6, 0, 18, 24), fitCenterRect(24, 48, 24))
        assertEquals(FitRect(0, 0, 24, 24), fitCenterRect(24, 24, 24))
    }

    // 負數／非正方形填滿：dst<=0 先 safeIconPx，src<=0 方形填滿。
    @Test
    fun fitCenterRect_nonPositiveSquareFill() {
        assertEquals(FitRect(0, 0, 1, 1), fitCenterRect(48, 24, 0))
        assertEquals(FitRect(0, 0, 1, 1), fitCenterRect(48, 24, -10))
        assertEquals(FitRect(0, 0, 24, 24), fitCenterRect(0, 24, 24))
        assertEquals(FitRect(0, 0, 24, 24), fitCenterRect(24, 0, 24))
        assertEquals(FitRect(0, 0, 24, 24), fitCenterRect(-5, -5, 24))
    }

    // 等比縮放：48x24 在 24 方框內 scale 0.5，正方形 scale 1.0。
    @Test
    fun fitScale_equalRatio() {
        assertEquals(0.5f, fitScale(48, 24, 24), 0.001f)
        assertEquals(0.5f, fitScale(24, 48, 24), 0.001f)
        assertEquals(1.0f, fitScale(24, 24, 24), 0.001f)
        assertEquals(0.5f, fitScale(96, 96, 48), 0.001f)
        assertEquals(1.0f, fitScale(0, 24, 24), 0.001f)
    }

    // 快照真值表：無 CS 任一 true、非 FILL 任一 true、有 CS＋<=0＋FILL false、
    // 有 CS＋非方形＋FILL true、方形＋FILL false（P2-1 方形 CENTER 須 true）。
    @Test
    fun shouldUseSnapshot_truthTable() {
        assertFalse(shouldUseSnapshot(true, 24, 24, false))
        assertTrue(shouldUseSnapshot(true, 48, 24, false))
        assertTrue(shouldUseSnapshot(true, 24, 48, false))
        assertFalse(shouldUseSnapshot(true, 0, 24, false))
        assertFalse(shouldUseSnapshot(true, -1, -1, false))
        assertTrue(shouldUseSnapshot(false, 24, 24, false))
        assertTrue(shouldUseSnapshot(false, 48, 24, false))
        assertTrue(shouldUseSnapshot(false, 0, 0, false))
        // 方形＋非 FILL（CENTER）必須快照，FILL 不需。
        assertTrue(shouldUseSnapshot(true, 48, 48, true))
        assertFalse(shouldUseSnapshot(true, 48, 48, false))
        // 非 FILL 優先於 <=0（尺寸未知仍快照，以 dst 兜底）。
        assertTrue(shouldUseSnapshot(true, 0, 24, true))
        assertTrue(shouldUseSnapshot(true, -1, -1, true))
        // 無 CS 任一皆快照（含非 FILL）。
        assertTrue(shouldUseSnapshot(false, 48, 48, true))
    }

    // 快照 OOM cap：min(safeIconPx(size), max(1, cap))。
    @Test
    fun clampSnapshotPx_capsOOM() {
        assertEquals(36, clampSnapshotPx(36, 192))
        assertEquals(192, clampSnapshotPx(500, 192))
        assertEquals(1, clampSnapshotPx(0, 192))
        assertEquals(1, clampSnapshotPx(-10, 192))
        assertEquals(1, clampSnapshotPx(100, 0))
        assertEquals(1, clampSnapshotPx(100, -5))
    }

    // 高 DPI：36dp 在 6.0 density 下 dpPx=216，需 cap 至 192；同密度下
    // ImageView layout 的 36dp 仍為 216（JVM 不對 layout 限位，僅快照限位，
    // 區分 snapshot bounds 與 layout 尺寸）。
    @Test
    fun clampSnapshotPx_highDensityCapsSnapshotButNotLayout() {
        assertEquals(216, dpPx(36, 6.0f))
        assertEquals(192, clampSnapshotPx(dpPx(36, 6.0f), 192))
        assertEquals(192, clampSnapshotPx(216, 192))
        assertEquals(144, dpPx(24, 6.0f))
        assertEquals(144, clampSnapshotPx(dpPx(24, 6.0f), 192))
        assertEquals(126, dpPx(36, 3.5f))
        assertEquals(126, clampSnapshotPx(dpPx(36, 3.5f), 192))
        assertEquals(95, dpPx(36, 2.625f))
        assertEquals(95, clampSnapshotPx(dpPx(36, 2.625f), 192))
    }
}
