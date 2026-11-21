// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// Phase E 效能基準：BbcInjector.mergeAvailableList 於 N=50/200/512 的合併耗時基線。
// 純內存線性合併（O(original+policy)），不在 hook 50ms stall 預算內（該預算治理 IPC＋等待，
// 見 PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS）；PM 解析（PmOutputParser）同樣不在
// 50ms 內，兩者預算獨立。此測試鎖定三檔規模皆在有界時間內完成（CI 寬鬆上界 2s，實測微秒級），
// 避免未來回歸為二次方或阻塞行為。
class BbcMergeTimingTest {

    private val allInstalled: (String) -> Boolean = { true }
    private val allEnabled: (String) -> Boolean = { true }

    private fun timedMerge(n: Int): Pair<List<String>, Long> {
        val original = (1..10).map { "com.samsung.base$it" }
        val policy = (1..n).map { "com.policy.p$it" }.toCollection(LinkedHashSet())
        // Warmup once so JIT settles before measurement.
        BbcInjector.mergeAvailableList(original, policy, allInstalled, allEnabled)
        val start = System.nanoTime()
        val merged = BbcInjector.mergeAvailableList(original, policy, allInstalled, allEnabled)
        val elapsedNs = System.nanoTime() - start
        return merged to elapsedNs
    }

    @Test
    fun merge50_withinBound() {
        val (merged, ns) = timedMerge(50)
        assertEquals(60, merged.size)
        assertTrue("N=50 merge must complete within 2s, took ${ns / 1_000_000}ms", ns < 2_000_000_000L)
    }

    @Test
    fun merge200_withinBound() {
        val (merged, ns) = timedMerge(200)
        assertEquals(210, merged.size)
        assertTrue("N=200 merge must complete within 2s, took ${ns / 1_000_000}ms", ns < 2_000_000_000L)
    }

    @Test
    fun merge512_withinBound() {
        val (merged, ns) = timedMerge(512)
        // 10 original + 512 policy (cap is 512 additions, all survive here).
        assertEquals(522, merged.size)
        assertTrue("N=512 merge must complete within 2s, took ${ns / 1_000_000}ms", ns < 2_000_000_000L)
    }

    @Test
    fun mergeTiming_scalesLinearly_notQuadratically() {
        // 確定性線性守護（取代原先 avg512 < 100x avg50 的 wall-clock 比值 — 該比值
        // 受 JIT/GC/CI 抖動影響而 flaky）。mergeAvailableList 對每個 policy 元素至多
        // 掃描一次，並只在 valid 且不在 original 時各呼叫 installed/enabled 一次；
        // 故這兩個呼叫次數正是「已完成的工作量」的確定性計數器。線性合併的工作量
        // 必正比於 policy.size；若退化為二次方（例如對 original 每個元素重掃 policy），
        // 呼叫次數會隨 N² 增長而被下列精確斷言抓到。絕對耗時上界仍由前三個 smoke
        // 測試各自以 2s 守護。
        fun callCounts(
            original: List<String>,
            policy: Set<String>,
        ): Pair<Int, Int> {
            val installedCalls = AtomicInteger(0)
            val enabledCalls = AtomicInteger(0)
            BbcInjector.mergeAvailableList(
                original,
                policy,
                { installedCalls.incrementAndGet(); true },
                { enabledCalls.incrementAndGet(); true },
            )
            return installedCalls.get() to enabledCalls.get()
        }
        val baseOriginal = (1..10).map { "com.samsung.base$it" }
        // 全部 valid 且不在 original：兩謂詞各恰呼叫 policy.size 次（線性工作）。
        for (n in listOf(50, 200, 512)) {
            val policy = (1..n).map { "com.policy.p$it" }.toCollection(LinkedHashSet())
            val (installed, enabled) = callCounts(baseOriginal, policy)
            assertEquals("installed calls must equal policy.size (N=$n)", policy.size, installed)
            assertEquals("enabled calls must equal policy.size (N=$n)", policy.size, enabled)
        }
        // 空 policy：early return，零呼叫。
        val emptyCounts = callCounts(baseOriginal, emptySet())
        assertEquals(0, emptyCounts.first)
        assertEquals(0, emptyCounts.second)
        // 全部已在 original：seen 命中直接 continue，兩謂詞零呼叫。
        val alreadyCounts = callCounts(baseOriginal, baseOriginal.toSet())
        assertEquals(0, alreadyCounts.first)
        assertEquals(0, alreadyCounts.second)
    }
}
