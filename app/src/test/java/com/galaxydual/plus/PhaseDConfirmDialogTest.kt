// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase D: shared policy-confirm dialog state machine + ROOT uninstall post-verify.
// JVM-only (no Android runtime / su / device): covers generation/ack(in-memory
// session-scoped)/expanded/ SAVING/RECONCILING cancel semantics, rotation
// (map round-trip mirror of Bundle), and uninstall verify decision
// (residual non-empty = fail, failure never auto-proceeds).
//
// DEVICE-UNVERIFIED (needs Robolectric / real device; JVM cannot cover):
// - Real android.os.Bundle round-trip (saveToBundle/restoreFromBundle with a
//   real Bundle): JVM uses android stub ("not mocked"); map round-trip below
//   mirrors the same keys/semantics. MANUAL: (1) Main: check ack -> rotate ->
//   ack cleared (must re-check), expanded toggle retained same-gen; kill process
//   (Don't keep activities / process death) -> ack cleared, expanded/dismissed
//   restored. (2) Editor: same steps; both screens identical.
//   手測步驟見本檔尾 MATRIX 註解.
// - showUninstallVerifyDialog widget: positive isEnabled=false + click no-op
//   (Residual/Failed), neutral (force-clear) / negative / Back / touch-outside =
//   cancel, isFinishing/isDestroyed early onCancel, resource text in 3 locales.
//   JVM locks pure predicates + truncation + click-guard simulation below.
//   MANUAL: 見本檔尾 MATRIX 註解 (emulator, zh/zh-Hant/en).
// - syncPolicyWriteDialog / syncPolicyConfirmDialog widget rebuild/suppression
//   branches (rotation re-show, RECONCILING suppression true branch UI).
//   MANUAL: 見本檔尾 MATRIX 註解.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseDConfirmDialogTest {

    private fun pending(gen: Long, vararg pkgs: String): PendingPolicyWrite =
        PendingPolicyWrite(
            packages = pkgs.toSet(),
            requestedAtMs = 0L,
            generation = gen,
        )

    // --- Shared state machine: generation + ack (in-memory session-scoped) ---

    @Test
    fun ack_sameGenerationRetained_crossGenerationReset() {
        val st = PolicyConfirmDialogState()
        val p1 = pending(11L, "com.a.app")
        st.onDialogShown(p1, saving = false)
        st.onAckChanged(true, p1.generation)
        assertTrue(st.acknowledged)
        assertEquals(11L, st.ackGeneration)

        // Same generation re-show (in-memory, no recreation): retained.
        st.onDialogShown(p1, saving = false)
        assertTrue(st.acknowledged)

        // Cross generation: reset.
        val p2 = pending(12L, "com.b.app")
        st.onDialogShown(p2, saving = false)
        assertFalse(st.acknowledged)
        assertNull(st.ackGeneration)
        // Pure predicate parity.
        assertTrue(policyAckShouldReset(11L, 12L))
        assertFalse(policyAckShouldReset(12L, 12L))
    }

    @Test
    fun expanded_sameGenerationRetained_crossGenerationReset() {
        val st = PolicyConfirmDialogState()
        val p1 = pending(21L, "com.a.app")
        st.onDialogShown(p1, saving = false)
        st.onExpandedChanged(true, p1.generation)
        assertTrue(st.expanded)
        st.onDialogShown(p1, saving = false)
        assertTrue(st.expanded)
        st.onDialogShown(pending(22L, "com.a.app"), saving = false)
        assertFalse(st.expanded)
        assertNull(st.expandedGeneration)
        assertTrue(policyWriteExpandedShouldReset(21L, 22L))
        assertFalse(policyWriteExpandedShouldReset(22L, 22L))
    }

    @Test
    fun savingAndReconciling_bothBlockConfirm_queuedAllows() {
        // QUEUED (not saving): ack + in-cap allows confirm (no deadlock).
        assertTrue(policyWriteCanConfirmInspectable(total = 3, acknowledged = true, policySaving = false))
        // SAVING blocks even with ack.
        assertFalse(policyWriteCanConfirmInspectable(total = 3, acknowledged = true, policySaving = true))
        // Over-cap blocks even when queued.
        assertFalse(policyWriteCanConfirmInspectable(total = POLICY_DIALOG_DISPLAY_MAX + 1, acknowledged = true, policySaving = false))
        // No ack blocks.
        assertFalse(policyWriteCanConfirmInspectable(total = 3, acknowledged = false, policySaving = false))
    }

    // Phase D: confirmSaving = saving || reconciling 映射鎖定 (Main/Editor single source).
    @Test
    fun confirmSaving_mapping_truthTable() {
        assertFalse(policyConfirmSaving(saving = false, reconciling = false))
        assertTrue(policyConfirmSaving(saving = true, reconciling = false))
        assertTrue(policyConfirmSaving(saving = false, reconciling = true))
        assertTrue(policyConfirmSaving(saving = true, reconciling = true))
        // Mapping feeds the confirm gate: any saving blocks even with ack.
        assertTrue(
            policyWriteCanConfirmInspectable(
                total = 3,
                acknowledged = true,
                policySaving = policyConfirmSaving(false, false),
            ),
        )
        assertFalse(
            policyWriteCanConfirmInspectable(
                total = 3,
                acknowledged = true,
                policySaving = policyConfirmSaving(true, false),
            ),
        )
        assertFalse(
            policyWriteCanConfirmInspectable(
                total = 3,
                acknowledged = true,
                policySaving = policyConfirmSaving(false, true),
            ),
        )
        assertFalse(
            policyWriteCanConfirmInspectable(
                total = 3,
                acknowledged = true,
                policySaving = policyConfirmSaving(true, true),
            ),
        )
    }

    @Test
    fun cancelSemantics_fullTruthTable_savingLocked_reconcilingLocalDismiss() {
        // Full 2x2 truth table: (!saving || reconciling).
        // SAVING + not RECONCILING: not cancelable (VM cancel is no-op; UI toasts).
        assertFalse(policyDialogShouldBeCancelable(saving = true, reconciling = false))
        // RECONCILING: cancelable as UI-only local dismiss (VM fence retained).
        assertTrue(policyDialogShouldBeCancelable(saving = false, reconciling = true))
        // QUEUED: cancelable via VM cancel.
        assertTrue(policyDialogShouldBeCancelable(saving = false, reconciling = false))
        // Critical (true, true): RECONCILING wins — local dismiss stays allowed
        // even while the SAVING flag is also set (suppression path).
        assertTrue(policyDialogShouldBeCancelable(saving = true, reconciling = true))
        // Legacy single-arg parity: saving blocks.
        assertFalse(policyDialogShouldBeCancelable(policySaving = true))
        assertTrue(policyDialogShouldBeCancelable(policySaving = false))
    }

    @Test
    fun reconcilingLocalDismiss_suppressesRebuild_clearedOnTerminal() {
        val st = PolicyConfirmDialogState()
        val p = pending(31L, "com.a.app")
        st.onDialogShown(p, saving = true)
        st.onLocalDismissForReconciling(p)
        // Suppression true branch: same generation + reconciling suppresses rebuild.
        assertTrue(st.shouldSuppressForReconciling(p, reconciling = true))
        assertFalse(st.shouldSuppressForReconciling(p, reconciling = false))
        // Cross generation does not suppress.
        assertFalse(st.shouldSuppressForReconciling(pending(32L, "com.a.app"), reconciling = true))
        // Non-reconciling clears the suppression.
        st.clearDismissIfNotReconciling(false)
        assertFalse(st.shouldSuppressForReconciling(p, reconciling = true))
    }

    @Test
    fun pendingCleared_resetsAllGenerations() {
        val st = PolicyConfirmDialogState()
        val p = pending(41L, "com.a.app")
        st.onDialogShown(p, saving = true)
        st.onAckChanged(true, p.generation)
        st.onExpandedChanged(true, p.generation)
        st.onLocalDismissForReconciling(p)
        st.onPendingCleared()
        assertFalse(st.acknowledged)
        assertNull(st.ackGeneration)
        assertFalse(st.expanded)
        assertNull(st.expandedGeneration)
        assertNull(st.dismissedForReconcilingGen)
        assertNull(st.shownPending)
        assertFalse(st.shownSaving)
    }

    // --- Rotation / process-death: ack session-scoped (restore clears ack) ---

    @Test
    fun rotation_mapRoundTrip_ackSessionScoped_notPreserved_expandedPreserved() {
        val st = PolicyConfirmDialogState()
        val p = pending(51L, "com.a.app")
        st.onDialogShown(p, saving = false)
        st.onAckChanged(true, p.generation)
        st.onExpandedChanged(true, p.generation)
        st.onLocalDismissForReconciling(p)
        // toMap 永不含 ack/shown (session-scoped/transient 不存).
        val saved = st.toMap()
        assertFalse(saved.containsKey("policyConfirm.ack"))
        assertFalse(saved.containsKey("policyConfirm.ackGen"))
        assertFalse(saved.containsKey("policyConfirm.shownSaving"))

        val restored = PolicyConfirmDialogState()
        restored.restoreFromMap(saved)
        // ack 強制清除 (旋轉／process-death 皆需重新勾選).
        assertFalse(restored.acknowledged)
        assertNull(restored.ackGeneration)
        // expanded/dismissed 世代保留.
        assertTrue(restored.expanded)
        assertEquals(51L, restored.expandedGeneration)
        assertEquals(51L, restored.dismissedForReconcilingGen)
        // shown 不還原 (VM 重播／下次 sync 重算).
        assertNull(restored.shownPending)
        assertFalse(restored.shownSaving)

        // Re-attach same generation after rotation: expanded retained, ack stays cleared.
        restored.onDialogShown(p, saving = false)
        assertFalse(restored.acknowledged)
        assertTrue(restored.expanded)
        // Cross generation still resets expanded after rotation.
        restored.onDialogShown(pending(52L, "com.a.app"), saving = false)
        assertFalse(restored.acknowledged)
        assertFalse(restored.expanded)
    }

    @Test
    fun restore_emptyMap_nullOmission_defaults() {
        // 空 map：全部回預設 (null generations 省略).
        val st = PolicyConfirmDialogState()
        st.restoreFromMap(emptyMap())
        assertFalse(st.acknowledged)
        assertNull(st.ackGeneration)
        assertFalse(st.expanded)
        assertNull(st.expandedGeneration)
        assertNull(st.dismissedForReconcilingGen)
        assertNull(st.shownPending)
        assertFalse(st.shownSaving)

        // toMap on fresh state: expanded=false + null generations omitted (no crash).
        val fresh = PolicyConfirmDialogState().toMap()
        assertEquals(false, fresh[PolicyConfirmDialogState.KEY_EXPANDED])
        assertNull(fresh[PolicyConfirmDialogState.KEY_EXPANDED_GEN])
        assertNull(fresh[PolicyConfirmDialogState.KEY_DISMISSED_GEN])
    }

    @Test
    fun restore_legacyAckKeys_ignored_ackStaysCleared() {
        // 舊 Bundle/map 殘留 legacy ack/shown 鍵：一律忽略，ack/shown 強制清除.
        val legacy = mapOf<String, Any?>(
            "policyConfirm.ack" to true,
            "policyConfirm.ackGen" to 99L,
            PolicyConfirmDialogState.KEY_EXPANDED to true,
            PolicyConfirmDialogState.KEY_EXPANDED_GEN to 99L,
            PolicyConfirmDialogState.KEY_DISMISSED_GEN to 99L,
            "policyConfirm.shownSaving" to true,
        )
        val st = PolicyConfirmDialogState()
        st.restoreFromMap(legacy)
        assertFalse(st.acknowledged)
        assertNull(st.ackGeneration)
        assertTrue(st.expanded)
        assertEquals(99L, st.expandedGeneration)
        assertNull(st.shownPending)
        assertFalse(st.shownSaving)
    }

    // --- ROOT uninstall post-verify: residual non-empty = fail ---

    @Test
    fun uninstallVerify_verifiedEmpty_onlySuccess() {
        val ok = decideUninstallVerifyResult(DualProbeResult.Ok(emptyList()))
        assertTrue(ok is UninstallVerifyOutcome.VerifiedEmpty)
        assertTrue(uninstallVerifyPositiveEnabled(ok))
        assertTrue(uninstallVerifyPositiveClickShouldProceed(ok))
    }

    @Test
    fun uninstallVerify_sameSetResidual_isFailure_positiveDisabled() {
        // 同一 warned set 重探殘留非空即失敗 (排序後 Residual).
        val warned = listOf("com.b.app", "com.a.app")
        val outcome = decideUninstallVerifyResult(DualProbeResult.Ok(warned))
        assertTrue(outcome is UninstallVerifyOutcome.Residual)
        assertEquals(listOf("com.a.app", "com.b.app"), (outcome as UninstallVerifyOutcome.Residual).remaining)
        // Failure never auto-proceeds: verified-clear stays disabled; user must
        // explicitly choose force-clear or cancel.
        assertFalse(uninstallVerifyPositiveEnabled(outcome))
        assertFalse(uninstallVerifyPositiveClickShouldProceed(outcome))
        // Click guard no-op simulation: Residual 下點擊不 proceed.
        var proceeded = false
        if (uninstallVerifyPositiveClickShouldProceed(outcome)) proceeded = true
        assertFalse(proceeded)
        val msg = uninstallVerifyMessageFallback(outcome)
        assertTrue(msg.contains("殘留"))
        assertTrue(msg.contains("com.a.app"))
    }

    @Test
    fun uninstallVerify_probeFailed_isFailure_positiveDisabled_clickNoOp() {
        // Exception 路徑 (exec-failed) 即 Failed；Failed 即驗證失敗.
        val outcome = decideUninstallVerifyResult(DualProbeResult.Failed("exec-failed: su boom"))
        assertTrue(outcome is UninstallVerifyOutcome.ProbeFailed)
        assertFalse(uninstallVerifyPositiveEnabled(outcome))
        assertFalse(uninstallVerifyPositiveClickShouldProceed(outcome))
        var proceeded = false
        if (uninstallVerifyPositiveClickShouldProceed(outcome)) proceeded = true
        assertFalse(proceeded)
        assertTrue(uninstallVerifyMessageFallback(outcome).contains("無法完成"))
    }

    @Test
    fun uninstallVerify_residual_truncation_bounded() {
        // 截斷：殘留清單顯示封頂 UNINSTALL_VERIFY_LIST_MAX (20)，溢出計數保留.
        val big = (1..UNINSTALL_VERIFY_LIST_MAX + 5).map { "com.example.p$it" }
        val (shown, rest) = uninstallVerifyResidualDisplay(big)
        assertEquals(UNINSTALL_VERIFY_LIST_MAX, shown.size)
        assertEquals(5, rest)
        assertEquals(big.take(UNINSTALL_VERIFY_LIST_MAX), shown)
        // 與 production dialog 上界同值 (MainActivity.PRE_CLEAR_LIST_MAX).
        assertEquals(PRE_CLEAR_LIST_MAX, UNINSTALL_VERIFY_LIST_MAX)
        // Fallback 文案截斷後綴標記溢出.
        val outcome = decideUninstallVerifyResult(DualProbeResult.Ok(big))
        assertTrue(outcome is UninstallVerifyOutcome.Residual)
        val msg = uninstallVerifyMessageFallback(outcome)
        assertTrue(msg.contains("僅顯示前"))
        assertTrue(msg.contains("com.example.p1"))
        // 不溢出時無截斷後綴.
        val small = uninstallVerifyResidualDisplay(listOf("com.a.app"))
        assertEquals(listOf("com.a.app"), small.first)
        assertEquals(0, small.second)
    }

    @Test
    fun uninstallVerify_successMessage_mentionsNoResidual() {
        val msg = uninstallVerifyMessageFallback(UninstallVerifyOutcome.VerifiedEmpty)
        assertTrue(msg.contains("無殘留"))
    }

    @Test
    fun uninstallVerify_positiveDisabledOnFailure_neverAutoProceeds() {
        // 失敗 outcome (Residual / Failed) 的 positive 一律禁用且點擊 no-op；
        // 僅 VerifiedEmpty 放行。uninstallVerifyPositiveClickShouldProceed 是 dialog
        // positive onClick 的單一守衛，下列以真實純謂詞鎖定其規格 —
        // 「失敗不鎖死選擇、亦不自動 proceed」。
        val residual = decideUninstallVerifyResult(DualProbeResult.Ok(listOf("com.a.app")))
        val failed = decideUninstallVerifyResult(DualProbeResult.Failed("timeout: unknown"))
        val verifiedEmpty = decideUninstallVerifyResult(DualProbeResult.Ok(emptyList()))
        assertTrue(verifiedEmpty is UninstallVerifyOutcome.VerifiedEmpty)
        // 失敗態：positive 不得 proceed — Residual / Failed 兩個 outcome 皆 false。
        assertFalse(uninstallVerifyPositiveEnabled(residual))
        assertFalse(uninstallVerifyPositiveClickShouldProceed(residual))
        assertFalse(uninstallVerifyPositiveEnabled(failed))
        assertFalse(uninstallVerifyPositiveClickShouldProceed(failed))
        // 成功態：只有 VerifiedEmpty 放行 positive click。
        assertTrue(uninstallVerifyPositiveEnabled(verifiedEmpty))
        assertTrue(uninstallVerifyPositiveClickShouldProceed(verifiedEmpty))
        // dialog 本體不自動 proceed (showUninstallVerifyDialog 內無任何
        // 直接呼叫 onClearVerified/onForceClear 的路徑；Back/觸外走 onCancel).
        // Neutral (仍要清除) / Negative (取消) 的 widget 層 isEnabled/click/dismiss
        // 需真機驗證，見尾 MATRIX M2 (DEVICE-UNVERIFIED)；此處不宣稱其可用。
    }
}

// ===== Phase D MANUAL MATRIX (DEVICE-UNVERIFIED; needs Robolectric/emulator or real device) =====
// 本檔 JVM 覆蓋純謂詞；以下 widget/資源行為 JVM 樁 ("not mocked") 無法執行，
// 保留給手測／instrumentation (PR CI 無 emulator，見 android.yml 註解).
//
// M1. Bundle round-trip (real android.os.Bundle):
//   M1a. Main：勾選 ack -> 旋轉 -> ack 已清除 (需重新勾選)；展開 Toggle 開 -> 旋轉 ->
//     同世代保留；切新世代 -> 重置. process-death (Don't keep activities 或 adb
//     am kill) 後恢復：ack 清除，expanded/dismissed 世代保留，pending 由 VM 重播.
//   M1b. Editor：同 M1a 三步；兩屏行為一致.
//   M1c. 空 Bundle：首次 onCreate (savedInstanceState=null) 不崩潰，dialog 不預建.
// M2. Verify dialog widget (MainActivity.showUninstallVerifyDialog):
//   M2a. Residual/Failed：positive 按鈕 disabled 視覺＋點擊 no-op (不 dismiss、不清除)；
//     neutral「仍要清除」可點 -> 清除；negative/Back/觸外 -> 取消 (pending 保留，旗標重置).
//   M2b. VerifiedEmpty：positive enabled 可點 -> 清除；neutral 同樣可點.
//   M2c. isFinishing/isDestroyed：直接走 onCancel，不建 dialog，不崩潰.
//   M2d. 三語文案：系統語言切 zh / zh-Hant / en，殘留／失敗／成功三態文案皆顯示對應語言，
//     殘留清單截斷 20 項＋"…" 標記 (25 項殘留驗證).
// M3. suppression true 分支 widget：RECONCILING 期本地 dismiss 後，同世代 sync 不重建
//   dialog (VM fence 保留)；終態 IDLE 或新世代 -> 抑制清除，dialog 可重建.
// M4. postUninstallReprobe 真機：ROOT 裝置 warned 集卸載後重探 — 殘留非空彈三選一
//   (非自動清除)；gateway 拋 exception -> ProbeFailed 三選一；超 512 包 -> Failed.
