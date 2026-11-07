// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase H live-health 誠實門控 (Codex P1 #2732)：ACTIVE 只是歷史存在性證據
// (marker survives reboots，無 boot-identity/freshness)，live 徽章一律再經
// hookBootFresh 門控 (今日恆 false)。此測試鎖定：ACTIVE 且 hookBootFresh=false
// 時 history 為真但 live 全否 -- 六徽章 GONE (hookLiveBadgesVisible=false)、
// telemetry/hero/summary 走 UNKNOWN (amber/灰＋狀態未知，永不給綠)；僅
// hookBootFresh=true 才點亮 LIVE。參照 ShizukuCardStatusTest 純函數模式。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookLiveGateTest {
    private fun lsposedActive(bootFresh: Boolean): UiState = UiState(
        channel = ChannelPreference.LSPOSED,
        hookStatus = HookStatus.ACTIVE,
        hookActive = true,
        hookScopes = setOf("system_server"),
        hookBootFresh = bootFresh,
    )

    @Test fun activeWithoutBootFreshIsHistoryOnly() {
        val s = lsposedActive(bootFresh = false)
        assertTrue("existence evidence stays visible in history copy", hookHistoryActive(s))
        assertFalse("no live claim without boot freshness", hookLiveProven(s))
        assertFalse("six badges + row tag + checklist badges stay GONE", hookLiveBadgesVisible(s))
    }

    @Test fun activeUnknownDisplayWithoutBootFresh() {
        val s = lsposedActive(bootFresh = false)
        assertEquals(
            "telemetry/hero/summary must reuse unknown, never green",
            HookLiveDisplay.UNKNOWN,
            hookLiveDisplay(s),
        )
    }

    @Test fun bootFreshLightsLive() {
        val s = lsposedActive(bootFresh = true)
        assertTrue(hookHistoryActive(s))
        assertTrue(hookLiveProven(s))
        assertTrue(hookLiveBadgesVisible(s))
        assertEquals(HookLiveDisplay.LIVE, hookLiveDisplay(s))
    }

    @Test fun inactiveStaysInactive() {
        val s = UiState(
            channel = ChannelPreference.LSPOSED,
            hookStatus = HookStatus.INACTIVE,
            hookBootFresh = false,
        )
        assertFalse(hookHistoryActive(s))
        assertFalse(hookLiveProven(s))
        assertFalse(hookLiveBadgesVisible(s))
        assertEquals(HookLiveDisplay.INACTIVE, hookLiveDisplay(s))
    }

    @Test fun unknownStaysUnknown() {
        for (status in listOf(HookStatus.UNKNOWN_UNREADABLE, HookStatus.UNKNOWN_NO_DIAGNOSTIC)) {
            val s = UiState(
                channel = ChannelPreference.LSPOSED,
                hookStatus = status,
                hookBootFresh = false,
            )
            assertFalse(hookHistoryActive(s))
            assertFalse(hookLiveProven(s))
            assertEquals(HookLiveDisplay.UNKNOWN, hookLiveDisplay(s))
        }
    }

    @Test fun nonLsposedChannelNeverLive() {
        val s = UiState(
            channel = ChannelPreference.AUTO,
            hookStatus = HookStatus.ACTIVE,
            hookActive = true,
            hookBootFresh = true,
        )
        assertFalse("hook row is hidden off the LSPosed channel", hookHistoryActive(s))
        assertFalse(hookLiveProven(s))
        assertFalse(hookLiveBadgesVisible(s))
    }

    @Test fun diagnosisNeverProvesBootFresh() {
        val base = UiState(channel = ChannelPreference.LSPOSED)
        val diagnosis = HookDiagnosis(HookStatus.ACTIVE, setOf("system_server"))
        val mapped = base.withHookDiagnosis(diagnosis, queriedAtMs = 1L)
        assertEquals(HookStatus.ACTIVE, mapped.hookStatus)
        assertFalse("diagnoseHookState is existence-only, fail closed", mapped.hookBootFresh)
        assertFalse(hookLiveProven(mapped))
    }

    @Test fun hookBootFreshDefaultsFalse() {
        assertFalse("new field must default fail-closed", UiState().hookBootFresh)
    }
}
