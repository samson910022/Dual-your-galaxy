// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // 粗略線性檢查：N=512 耗時應遠小於 N=50 的 100 倍（二次方回歸即超標）。
        // 取 3 次平均以降抖；CI 上界寬鬆，只防數量級回歸。
        fun avgNs(n: Int): Long {
            var sum = 0L
            repeat(3) { sum += timedMerge(n).second }
            return sum / 3
        }
        val avg50 = avgNs(50).coerceAtLeast(1L)
        val avg512 = avgNs(512)
        assertTrue(
            "N=512 avg ${avg512}ns must not exceed 100x N=50 avg ${avg50}ns (linear, not quadratic)",
            avg512 < avg50 * 100,
        )
    }
}
