// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Stage J5 polish JVM predicate tests (no Android framework needed):
// - policy draft row dedup (TalkBack re-speak guard),
// - expanded dialog display cap (write path keeps the full set),
// - VM hardcoded-string -> resource mapping (vmMessageRes).
class StageJ5PolishTest {

    @Test
    fun policyDraftDedup_sameTextNoUpdate() {
        assertFalse(shouldUpdatePolicyDraftText("草稿：3 項", "草稿：3 項"))
        assertTrue(shouldUpdatePolicyDraftText("草稿：3 項", "草稿：4 項"))
        assertTrue(shouldUpdatePolicyDraftText(null, "草稿：0 項"))
    }

    @Test
    fun policyDraftLiveRegion_noneWhenUnchanged() {
        assertEquals(
            android.view.View.ACCESSIBILITY_LIVE_REGION_NONE,
            policyDraftLiveRegion(false),
        )
        assertEquals(
            android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE,
            policyDraftLiveRegion(true),
        )
    }

    @Test
    fun expandedDisplayCapsAt200WhileWriteKeepsFull() {
        // 512-item pending (hook cap): display shows first 200 + count.
        val sorted512 = (1..512).map { "com.example.%03d".format(it) }
        val (shown, rest) = policyWriteVisibleList(sorted512, true)
        assertEquals(POLICY_DIALOG_DISPLAY_MAX, shown.size)
        assertEquals(200, shown.size)
        assertEquals(sorted512.take(200), shown)
        assertEquals(312, rest)
        assertEquals(sorted512.size - shown.size, rest)
        // Small lists still display fully.
        val sorted51 = (1..51).map { "com.example.%03d".format(it) }
        val (shown51, rest51) = policyWriteVisibleList(sorted51, true)
        assertEquals(sorted51, shown51)
        assertEquals(0, rest51)
        // Collapsed path unchanged (50 preview).
        val (collapsed, collapsedRest) = policyWriteVisibleList(sorted512, false)
        assertEquals(POLICY_DIALOG_PREVIEW_MAX, collapsed.size)
        assertEquals(512 - 50, collapsedRest)
    }

    // Stage L4 P2#2 (b) 200/201 邊界：display≡inspectability。
    // 200 可確認（門控 true＋完整 CD），201 超限禁用＋截斷 CD＋capped 文案。
    @Test
    fun inspectabilityBound_200_201_boundary() {
        val sorted200 = (1..200).map { "com.example.%03d".format(it) }
        val (shown200, rest200) = policyWriteVisibleList(sorted200, true)
        assertEquals(200, shown200.size)
        assertEquals(0, rest200)
        // 200 可確認。
        assertTrue(policyWriteCanConfirmInspectable(total = 200, acknowledged = true, policySaving = false))
        assertTrue(policyDialogCanConfirm(policySaving = false, acknowledged = true, total = 200))
        assertFalse(policyWriteIsOverCap(200))
        // 完整 CD（expanded＋total<=200 即完整）。
        assertEquals("政策包名列表，共 200 項", policyWriteListCdFallback(total = 200, shown = 200, expanded = true))

        val sorted201 = (1..201).map { "com.example.%03d".format(it) }
        val (shown201, rest201) = policyWriteVisibleList(sorted201, true)
        assertEquals(200, shown201.size)
        assertEquals(1, rest201)
        // 201 超限禁用（即使 ack＋非 saving）。
        assertFalse(policyWriteCanConfirmInspectable(total = 201, acknowledged = true, policySaving = false))
        assertFalse(policyDialogCanConfirm(policySaving = false, acknowledged = true, total = 201))
        assertTrue(policyWriteIsOverCap(201))
        // 超限展開仍視截斷（display≡inspectability）。
        val cd201 = policyWriteListCdFallback(total = 201, shown = 200, expanded = true)
        assertTrue(cd201.contains("201"))
        assertTrue(cd201.contains("200"))
        assertTrue(cd201.contains("僅顯示前"))
    }

    // Stage L4 gate unit：total/ack/saving 三維。
    @Test
    fun inspectableGate_totalAckSaving() {
        // 200 內：!saving && ack 才 true。
        assertTrue(policyWriteCanConfirmInspectable(50, true, false))
        assertFalse(policyWriteCanConfirmInspectable(50, false, false))
        assertFalse(policyWriteCanConfirmInspectable(50, true, true))
        assertFalse(policyWriteCanConfirmInspectable(50, false, true))
        // 舊二參相容（不含 total 檢查，保既有測試綠）。
        assertTrue(policyDialogCanConfirm(policySaving = false, acknowledged = true))
        assertFalse(policyDialogCanConfirm(policySaving = true, acknowledged = true))
        // 512 sanitizer 上界仍可寫（write-full 契約），但門控擋下（須分批）。
        assertFalse(policyWriteCanConfirmInspectable(512, true, false))
        assertTrue(policyWriteIsOverCap(512))
        val (shown512, rest512) = policyWriteVisibleList((1..512).map { "com.example.%03d".format(it) }, true)
        assertEquals(200, shown512.size)
        assertEquals(312, rest512)
    }

    // Stage L4 201＋512 capped 文案：Toggle/播報超限變體含雙計數；write-full
    // 契約保留（pending.packages 仍完整，僅顯示截斷＋門控禁用）。
    @Test
    fun cappedVariants_201_512_containCounts() {
        for (total in listOf(201, 512)) {
            val shown = 200
            val toggleCapped = policyWriteShowAllCappedFallback(total, shown)
            assertTrue(toggleCapped.contains(total.toString()))
            assertTrue(toggleCapped.contains(shown.toString()))
            assertTrue(toggleCapped.contains("分批"))
            val announceCapped = policyWriteExpandedAnnounceCappedFallback(total, shown)
            assertTrue(announceCapped.contains(total.toString()))
            assertTrue(announceCapped.contains(shown.toString()))
            assertTrue(announceCapped.contains("分批"))
            val warning = policyWriteOverCapWarningFallback(total, shown)
            assertTrue(warning.contains(total.toString()))
            assertTrue(warning.contains(shown.toString()))
            // 超限展開 CD 複用截斷語義（非完整）。
            val cd = policyWriteListCdFallback(total, shown, expanded = true)
            assertTrue(cd.contains("僅顯示前"))
            // 門控禁用（positive disabled），即使 ack＋非 saving。
            assertFalse(policyWriteCanConfirmInspectable(total, true, false))
        }
        // 200 內 capped 不觸發（完整文案無「分批」）。
        val normalToggle = policyWriteToggleLabelFallback(expanded = false, total = 50, shown = 50)
        assertFalse(normalToggle.contains("分批"))
    }

    @Test
    fun vmMessageRes_exactLiterals() {
        assertEquals(R.string.invalid_package to null, vmMessageRes("invalid package"))
        assertEquals(R.string.vm_no_dual_profile to null, vmMessageRes("no dual profile"))
        assertEquals(R.string.vm_no_pending_remove to null, vmMessageRes("沒有待確認的移除"))
        assertEquals(R.string.policy_empty_selection to null, vmMessageRes("未勾選任何應用"))
        assertEquals(R.string.vm_busy_batch_clone to null, vmMessageRes("already in progress: batch clone"))
        assertEquals(R.string.vm_selection_cleared to null, vmMessageRes("已清除勾選（未卸載任何分身）"))
    }

    @Test
    fun vmMessageRes_prefixesCarryRemainder() {
        assertEquals(R.string.vm_cloned to "com.example.app", vmMessageRes("cloned: com.example.app"))
        assertEquals(R.string.vm_removed to "com.example.app", vmMessageRes("removed: com.example.app"))
        assertEquals(
            R.string.vm_remove_unknown to "boom detail",
            vmMessageRes("remove result unknown: boom detail"),
        )
        assertEquals(
            R.string.vm_clone_failed to "boom",
            vmMessageRes("clone failed: boom"),
        )
        assertEquals(
            R.string.vm_in_progress to "com.example.app",
            vmMessageRes("already in progress: com.example.app"),
        )
        // Longest-prefix wins: verified-id before the generic label prefix.
        val vid = vmMessageRes("移除目標已變更（verified id 5 ≠ 3），請重新確認")
        assertNotNull(vid)
        assertEquals(R.string.vm_target_changed_verified_id, vid!!.first)
        val serial = vmMessageRes("移除目標已變更（serial 7 ≠ 8），請重新確認")
        assertEquals(R.string.vm_target_changed_serial, serial!!.first)
        val label = vmMessageRes("移除目標已變更（foo ≠ bar），請重新確認")
        assertEquals(R.string.vm_target_changed_label, label!!.first)
    }

    @Test
    fun vmMessageRes_unknownFallsBackNull() {
        assertNull(vmMessageRes("some future message nobody mapped"))
        assertNull(vmMessageRes(""))
    }

    @Test
    fun vmMessageRes_inputTooLongMapsToResource() {
        // VM interpolates the actual limit; the mapping keys on the prefix and
        // the caller resolves with MAX_PACKAGE_INPUT_CHARS.
        assertEquals(R.string.input_too_long to null, vmMessageRes("輸入過長（超過 256 字元），已拒絕"))
    }

    // Stage L4 fix(a) 清除鈕路由 single source（純函數，統一謂詞 !(!known&&corrupt)）：
    // 僅 !known&&corrupt 走修復（未 arm→REQUEST，已 arm→CONFIRM）；其餘一律 CLEAR
    // （Known 單步／Unavailable 盲清；Known+Corrupt 殘留亦 CLEAR，與 VM
    // onClearPolicyButtonTap 同一判定，防 corrupt-alone 與 !known&&corrupt 漂移）。
    // 穷舉 known×corrupt×armed 八組合鎖定唯一謂詞。
    @Test
    fun clearPolicyButtonRoute_corruptTwoTaps() {
        // Non-repair: Known 或 Unavailable 一律 CLEAR（armed 無關）。
        assertEquals(ClearPolicyButtonRoute.CLEAR, clearPolicyButtonRoute(known = true, corrupt = false, repairArmed = false))
        assertEquals(ClearPolicyButtonRoute.CLEAR, clearPolicyButtonRoute(known = true, corrupt = false, repairArmed = true))
        assertEquals(ClearPolicyButtonRoute.CLEAR, clearPolicyButtonRoute(known = false, corrupt = false, repairArmed = false))
        assertEquals(ClearPolicyButtonRoute.CLEAR, clearPolicyButtonRoute(known = false, corrupt = false, repairArmed = true))
        // Known+Corrupt 殘留亦 CLEAR（與舊 corrupt-alone 謂詞之分汧點）。
        assertEquals(ClearPolicyButtonRoute.CLEAR, clearPolicyButtonRoute(known = true, corrupt = true, repairArmed = false))
        assertEquals(ClearPolicyButtonRoute.CLEAR, clearPolicyButtonRoute(known = true, corrupt = true, repairArmed = true))
        // 唯一修復：!known&&corrupt，以 armed 分流兩 tap。
        assertEquals(ClearPolicyButtonRoute.REPAIR_REQUEST, clearPolicyButtonRoute(known = false, corrupt = true, repairArmed = false))
        assertEquals(ClearPolicyButtonRoute.REPAIR_CONFIRM, clearPolicyButtonRoute(known = false, corrupt = true, repairArmed = true))
    }

    // Stage L4 fix(a) 修復模式標籤：Corrupt 下按鈕重貼 repair 文案＋CD，
    // 非 Corrupt 回原清除文案。
    @Test
    fun clearPolicyButtonRes_corruptUsesRepair() {
        assertEquals(R.string.action_repair_policy_draft, clearPolicyButtonTextRes(true))
        assertEquals(R.string.repair_policy_draft_cd, clearPolicyButtonCdRes(true))
        assertEquals(R.string.action_clear_policy_draft, clearPolicyButtonTextRes(false))
        assertEquals(R.string.clear_policy_draft_cd, clearPolicyButtonCdRes(false))
    }

    @Test
    fun vmMessageRes_prefixRemainderIsRawChineseByDesign() {
        // Known limitation (locale-mixed by design, see MainActivity.vmMessageRes
        // KDoc): the EN prefix template is localized but the %1$s remainder is
        // the raw VM suffix verbatim, so structured Chinese messages stay mixed
        // under EN ("EN prefix + Chinese remainder"). Pin the mixed remainder
        // here so a future VM structured-params split must update this test.
        val vid = vmMessageRes("移除目標已變更（verified id 5 ≠ 3），請重新確認")
        assertNotNull(vid)
        assertEquals(R.string.vm_target_changed_verified_id, vid!!.first)
        assertTrue(vid.second!!.contains("），請重新確認"))

        val serial = vmMessageRes("移除目標已變更（serial 7 ≠ 8），請重新確認")
        assertNotNull(serial)
        assertEquals(R.string.vm_target_changed_serial, serial!!.first)
        assertTrue(serial.second!!.contains("），請重新確認"))

        val selectUser = vmMessageRes("選取目標已變更（user 95 ≠ 96），請重新確認後再保存")
        assertNotNull(selectUser)
        assertEquals(R.string.vm_select_target_user, selectUser!!.first)
        assertTrue(selectUser.second!!.contains("），請重新確認後再保存"))

        val saved = vmMessageRes("已保存選取 3 項（user 0,重啟可恢復；只保存勾選，不寫入分身）")
        assertNotNull(saved)
        assertEquals(R.string.vm_saved_selection, saved!!.first)
        // Remainder carries the Chinese count clause verbatim.
        assertTrue(saved.second!!.contains("項（user 0"))

        val rebound = vmMessageRes("選取已綁定舊目標，已清空，請重新勾選確認（user 95 → user 96）")
        assertNotNull(rebound)
        assertEquals(R.string.vm_target_rebound, rebound!!.first)
        assertTrue(rebound.second!!.contains("，已清空"))

        val batchPartial = vmMessageRes("批次中斷：已完成 2 項 (成功 1 / 失敗 0 / 未知 1): boom")
        assertNotNull(batchPartial)
        assertEquals(R.string.vm_batch_partial, batchPartial!!.first)
        assertTrue(batchPartial.second!!.contains("項 (成功"))

        val batchTarget = vmMessageRes("批次中斷：目標已變更（user 95 → user 96），已完成 1/共 2 項")
        assertNotNull(batchTarget)
        assertEquals(R.string.vm_batch_target_changed, batchTarget!!.first)
        assertTrue(batchTarget.second!!.contains("→"))

        // Pure package/tech remainders are locale-neutral and stay fully
        // localized (no Chinese clause in the remainder).
        val cloned = vmMessageRes("cloned: com.example.app")
        assertEquals(R.string.vm_cloned, cloned!!.first)
        assertEquals("com.example.app", cloned.second)
    }
}
