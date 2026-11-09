// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase 4 圖示尺寸策略（純函數、JVM 可測 seam，無 Android 依賴、僅 stdlib）：
// - 本檔只放換算、占位與 fallback 決策的純邏輯，不碰 Drawable/View，
//   不引入 android.*；呼叫端仍在 MainActivity，由其持有 Drawable 生命週期。
// - 為何抽出：MainActivity 私有 dp()/transparentPlaceholder()/
//   freshIconDrawable()/sizedIcon() 無法 JVM 直測（android.jar not mocked，
//   無 Robolectric），故把數值邏輯抽為同包頂層 internal fun，比照
//   SourceIconState、pickIconEvictionKey 的純 stdlib seam 模式。
// - 常量分組：圖示 dp 分組（MainActivity companion object 的
//   SOURCE_ICON_DP=24、CLONED_ICON_DP=36、ICON_CONTAINER_DP=44）仍由
//   MainActivity 單一管理，本檔不重複定義 dp 常數，避免雙源漂移；
//   本檔只負責 dp 換算、占位方形尺寸與取捨決策。
// - 撰寫語言統一繁中：以「圖示」為準，不用簡體或異體字。
package com.galaxydual.plus

// 圖示占位 px 保底（與 MainActivity.transparentPlaceholder(sizePx) 對應）：
// 透明占位必須非 0 尺寸才可見，故以 maxOf(1, sizePx) 收斂；0／負值回 1，
// 正值不變。sizedIcon／freshIconDrawable 的 fallback 回占位時皆經此。
internal fun safeIconPx(sizePx: Int): Int = maxOf(1, sizePx)

// dp 轉 px（與 MainActivity.dp(v) 對齊）：公式 (vDp * density + 0.5f).toInt()。
// WHY 四捨五入：非標準 density（如 2.625）下直接 toInt() 截斷會系統性偏小約 1px；
// vDp 均為正數時 +0.5f 等價四捨五入。呼叫端原樣傳入
// resources.displayMetrics.density，公式與舊 dp() 一字不差。
internal fun dpPx(vDp: Int, density: Float): Int = (vDp * density + 0.5f).toInt()

// 方形占位尺寸（與 MainActivity.freshIconDrawable 末段對應）：
// 取較大邊並以 1 保底，保證占位為方形且可見。intrinsic 未知回 -1
// 或為 0 時仍收斂為 1，不產生 0 尺寸不可見圖。呼叫端傳原始
// intrinsicW/intrinsicH（讀取異常以 -1 補齊），此處一次收斂為
// maxOf(1, maxOf(intrinsicW, intrinsicH))，與舊逐項
// maxOf(1, w)／maxOf(1, h) 再取 max 的結果等價。
internal fun placeholderDim(intrinsicW: Int, intrinsicH: Int): Int =
    maxOf(1, maxOf(intrinsicW, intrinsicH))

// 是否改用透明占位（與 MainActivity.freshIconDrawable／sizedIcon 的
// fallback 決策對應）：鎖死「永不回快取實例」語義——newDrawable 成功
// （newDrawableAvailable 為真）→ 用 fresh 實例，回 false；失敗（為空）→
// 用透明占位，回 true。呼叫端以 shouldUsePlaceholder(fresh != null)／
// shouldUsePlaceholder(copied != null) 判斷，JVM 以此謂詞鎖死邏輯，
// Android 層只負責 newDrawable／mutate 與 Drawable 生命週期。
internal fun shouldUsePlaceholder(newDrawableAvailable: Boolean): Boolean =
    !newDrawableAvailable

// 等比置中矩形（與 MainActivity.bitmapSnapshotDrawable 的 Canvas 變換對應）：
// 表達來源圖示以等比縮放後置中於 dst 方框內的目標矩形（left/top/right/bottom）。
// Review P2-2 拉伸回歸即指只 setBounds 方形會使 48x24 被拉成 24x24，
// 本矩形配合 fitScale 的 Canvas translate/scale 保證 48x24 在 24 方框內
// 為置中 24x12（等比、不拉伸）。left/right/top/bottom 為像素座標。
internal data class FitRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

// 等比置中矩形計算（與 MainActivity.bitmapSnapshotDrawable 對應）：
// dstPx 為方形外框邊長，dst<=0 先經 safeIconPx 收斂為 1；
// srcW/srcH 任一 <=0 時未知尺寸回方形填滿 FitRect(0, 0, dst, dst)；
// 寬高經 fitScale 縮放後以 clamp [1, dst] 收斂並置中，
// 呼叫端以此矩形的 left/top 做 Canvas.translate、scale 做縮放。
internal fun fitCenterRect(srcW: Int, srcH: Int, dstPx: Int): FitRect {
    val dst = safeIconPx(dstPx)
    if (srcW <= 0 || srcH <= 0) return FitRect(0, 0, dst, dst)
    val scale = fitScale(srcW, srcH, dst)
    val w = (srcW * scale).toInt().coerceIn(1, dst)
    val h = (srcH * scale).toInt().coerceIn(1, dst)
    val left = (dst - w) / 2
    val top = (dst - h) / 2
    return FitRect(left, top, left + w, top + h)
}

// 等比縮放係數（與 MainActivity.bitmapSnapshotDrawable 的 Canvas.scale 對應）：
// 回 min(dst/srcW, dst/srcH)，保證來源圖示完整置入 dst 方框且不拉伸
// （Review P2-2）；dst<=0 先經 safeIconPx 收斂；src 任一 <=0 時回 1f
//（不縮放，由 fitCenterRect 方形填滿兜底，快照呼叫端據此決定是否回 null）。
internal fun fitScale(srcW: Int, srcH: Int, dstPx: Int): Float {
    val dst = safeIconPx(dstPx)
    if (srcW <= 0 || srcH <= 0) return 1f
    return minOf(dst.toFloat() / srcW.toFloat(), dst.toFloat() / srcH.toFloat())
}

// 是否需位圖快照（與 MainActivity.loadIcon 在 withContext(IO) 內
// getApplicationIcon 後的判斷對應）：真值表依序為 無 ConstantState 回 true
//（須快照治 P2-1 透明回歸）、BitmapDrawable 非 FILL 重力回 true（方形 CENTER
// 不保證 setBounds 縮放，須快照治 P2-1 方形 CENTER 漏網）、FILL 下任一 <=0
// 回 false（尺寸未知不快照；無 CS／非 FILL 已於前行命中，屆時快照以 eff=dst
// 兜底）、非正方形回 true（等比快照治 P2-2 拉伸）、其餘（方形＋FILL）回 false
//（可走既有 newDrawable，無需快照）。呼叫端傳 normalized 後的
// hasConstantState＝(constantState != null 安全讀取）、
// intrinsicW/H（讀取異常以 -1 補齊）與 isBitmapNonFillGravity（Main 以
// raw is BitmapDrawable && raw.gravity != Gravity.FILL 換算，安全讀取），
// 此處僅做純決策，Android 讀取留在 Main；本檔不 import android.*，gravity
// 不以 android.Gravity 傳入，一律以布林表示。
internal fun shouldUseSnapshot(hasConstantState: Boolean, intrinsicW: Int, intrinsicH: Int, isBitmapNonFillGravity: Boolean): Boolean {
    if (!hasConstantState) return true
    if (isBitmapNonFillGravity) return true
    if (intrinsicW <= 0 || intrinsicH <= 0) return false
    return intrinsicW != intrinsicH
}

// 快照 px 限位（與 MainActivity.bitmapSnapshotDrawable 的 dst Px 計算對應）：
// 以 min(safeIconPx(sizePx), maxOf(1, capPx)) 限位，防 OOM 全尺寸位圖
//（如 512 原圖直接建位圖）；sizePx 經 safeIconPx 保 1 可見，capPx 經
// maxOf(1, capPx) 保底，避免 cap 傳 0／負值致非法尺寸。
internal fun clampSnapshotPx(sizePx: Int, capPx: Int): Int =
    minOf(safeIconPx(sizePx), maxOf(1, capPx))
