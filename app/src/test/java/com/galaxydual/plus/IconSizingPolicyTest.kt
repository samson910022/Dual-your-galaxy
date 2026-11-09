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
}
