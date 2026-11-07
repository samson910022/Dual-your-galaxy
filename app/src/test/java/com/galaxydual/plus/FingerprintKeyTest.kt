// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase 1b：列表指紋/快取鍵語意鎖定 (純 JVM 層)。
// 背景：buildSourceListKey / syncLabelCache 皆為 private
// Activity 方法，無 Activity 實例即無法在 JVM 單測直接呼叫 (存在性見
// ReflectionSeamTest)。catalog-hash 內容語意 / loading-穩定鍵輸入曾在此以
// List.copy/hashCode 語意佔位，但該等斷言零生產呼叫 (tautology) 卻檔頭宣稱
// 生產政策鎖定，故刪除以免誤導。cloned 指紋鍵已抽為 JVM 可呼叫 seam
// clonedListKeyFor（直接呼叫覆蓋，見下）；label waiter epoch 機制存在性
// 另見 labelWaiters_carryCatalogEpoch（丟棄行為語意由 instrumentation 覆蓋）。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintKeyTest {

    private fun catalog() = listOf(
        SourceAppEntry(packageName = "com.example.a", label = "Alpha", sourceSerial = 15, isSystemApp = false),
        SourceAppEntry(packageName = "com.example.b", label = "Beta", sourceSerial = 15, isSystemApp = false),
    )

    // Phase 1a 政策釘：loading 翻轉不得換 cloned 指紋 (在途重建會閃爍/丟捲動)。
    // 現況：Phase 1a 公式已抽為 JVM 可呼叫 seam clonedListKeyFor（loading 非輸入，
    // 結構上不可能影響指紋；Activity.buildClonedListKey 僅委派）。此處經 UiState
    // 投影呼叫 seam：兩 UiState 僅差 loading（其餘 cloned／inventory／hook 皆同），
    // 各自以 buildClonedListKey 同源投影 (s.cloned／s.inventory.targetSerial／
    // s.inventory.targetId／hookLiveProven(s) 實算／catalogKey) 求鍵，兩鍵必須相等。
    // hookLiveProven 走真實純函數（同包頂層 direct call），非寫死哨兵。
    @Test
    fun clonedListKey_stableAcrossLoadingToggle() {
        val catalogKey = catalog().hashCode()
        val base = UiState(
            cloned = listOf("com.example.a"),
            inventory = InventorySnapshot(
                targetId = 95,
                targetSerial = 15,
            ),
            channel = ChannelPreference.LSPOSED,
            hookStatus = HookStatus.ACTIVE,
            hookActive = true,
            hookScopes = setOf("system_server"),
            hookBootFresh = true,
            loading = false,
        )
        val idle = base.copy(loading = false)
        val inflight = base.copy(loading = true)
        fun keyOf(s: UiState): String = clonedListKeyFor(
            cloned = s.cloned,
            targetSerial = s.inventory.targetSerial,
            targetId = s.inventory.targetId,
            hookLiveProven = hookLiveProven(s),
            catalogKey = catalogKey,
        )
        assertEquals(
            "cloned list key must not change on loading toggle alone",
            keyOf(idle),
            keyOf(inflight),
        )
        // 敏感性釘（防空洞卷）：cloned 內容不同即換鍵——若 seam 退化為常數
        // (如 return "")，此斷言即紅，證明上式非空洞自等。
        val renamed = base.copy(cloned = listOf("com.example.b"))
        assertNotEquals(
            "cloned list key must change when cloned content changes",
            keyOf(base),
            keyOf(renamed),
        )
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
