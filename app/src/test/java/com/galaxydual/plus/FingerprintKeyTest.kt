// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase 1b：列表指紋/快取鍵語意鎖定 (純 JVM 層)。
// 背景：buildClonedListKey / buildSourceListKey / syncLabelCache 皆為 private
// Activity 方法，無 Activity 實例即無法在 JVM 單測直接呼叫 (存在性見
// ReflectionSeamTest)。catalog-hash 內容語意 / loading-穩定鍵輸入曾在此以
// List.copy/hashCode 語意佔位，但該等斷言零生產呼叫 (tautology) 卻檔頭宣稱
// 生產政策鎖定，故刪除以免誤導；政策待 Phase 1a 提供 JVM 可呼叫 seam 後以
// 直接呼叫覆蓋。此檔現僅釘 label waiter epoch 機制存在性 (丟棄行為語意由
// instrumentation 覆蓋) 與 @Ignore 的 cloned 指紋政策 (待 seam 後啟用)。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

class FingerprintKeyTest {

    private fun catalog() = listOf(
        SourceAppEntry(packageName = "com.example.a", label = "Alpha", sourceSerial = 15, isSystemApp = false),
        SourceAppEntry(packageName = "com.example.b", label = "Beta", sourceSerial = 15, isSystemApp = false),
    )

    private fun keyedState(loading: Boolean) = UiState(
        cloned = listOf("com.example.a"),
        loading = loading,
        sourceCatalog = catalog(),
        inventory = InventorySnapshot(
            source = "pm list packages --user 95",
            targetId = 95,
            targetSerial = 15,
            queryTimeMs = 123456789L,
            freshness = InventoryFreshness.FRESH,
            count = 1,
        ),
    )

    // Phase 1a 政策釘：loading 翻轉不得換 cloned 指紋 (在途重建會閃爍/丟捲動)。
    // 現況：Phase 1a 公式修正已在 worktree 落地 (buildClonedListKey 不再含
    // s.loading，見 MainActivity diff)，但方法仍為 private Activity 方法，
    // JVM 單測無實例可呼叫，故仍 @Ignore；待 key 抽成 JVM 可呼叫 seam 後，
    // 改寫為直接呼叫並啟用。
    @Ignore("TODO(phase1a-seam): enable (rewrite as direct call) once the cloned-list key is extracted to a JVM-callable seam")
    @Test
    fun clonedListKey_stableAcrossLoadingToggle() {
        val m = MainActivity::class.java.getDeclaredMethod(
            "buildClonedListKey",
            UiState::class.java,
            Int::class.javaPrimitiveType,
        )
        m.isAccessible = true
        val activity = try {
            MainActivity::class.java.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            fail("cloned-key policy needs a JVM-callable seam (cannot instantiate MainActivity on JVM): $e")
            return
        }
        val catalogKey = catalog().hashCode()
        val idle = m.invoke(activity, keyedState(loading = false), catalogKey) as String
        val inflight = m.invoke(activity, keyedState(loading = true), catalogKey) as String
        assertEquals("cloned list key must not change on loading toggle alone", idle, inflight)
    }

    // Stale-catalog race 政策釘 (Phase 1a，已在 worktree 落地)：
    // requestLabelAsync 必須在呼叫時捕獲 catalog epoch，過期 catalog 的 onLoaded
    // 結果整批丟棄 (labelCallbacks waiter 為 Pair<epoch, callback>，epoch 源為
    // labelCacheCatalogKey)。JVM 單測無 Activity/Looper 可執行完整丟棄行為
    // (由 instrumentation 覆蓋)，此處釘住機制存在性：回退為無 epoch 回調列表
    // (MutableList<Function1>) 即紅。
    @Test
    fun labelWaiters_carryCatalogEpoch() {
        val callbacksField = MainActivity::class.java.getDeclaredField("labelCallbacks")
        val generic = callbacksField.genericType.toString()
        // 完整形狀：Map<String, List<Pair<epoch, callback>>>，epoch 為 Int?
        // (JVM 層 Kotlin MutableList 擦除為 java.util.List，故斷 "List" 而非
        // "MutableList"——後者在此永不出現；Int? 呈現為 java.lang.Integer，
        // 仍含 "Int")。僅斷 "Pair" 太鬆：回退為無 epoch 回調列表
        // (MutableList<Function1>) 即應紅。
        assertTrue(
            "labelCallbacks waiters must be epoch-tagged (Map<String, List<Pair<Int?, callback>>>), got: $generic",
            generic.contains("List") && generic.contains("Pair") && generic.contains("Int"),
        )
        val epochField = MainActivity::class.java.getDeclaredField("labelCacheCatalogKey")
        assertEquals(Integer::class.java, epochField.type)
    }
}
