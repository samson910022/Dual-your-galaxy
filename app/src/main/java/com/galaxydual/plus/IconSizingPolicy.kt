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
