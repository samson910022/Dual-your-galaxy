// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
// Phase D shared policy-confirm dialog state machine (Main + Editor共用).
//
// Extracts the UI-only dialog state that was previously private to MainActivity
// (pending + generation + ack + expanded + SAVING/RECONCILING cancel semantics
// + rotation retain + onDestroy dismiss) into an Android-free holder so both
// MainActivity and PolicyActivity share one truth. The holder never touches
// AlertDialog / Context / resources: Activities own the dialog widget, this
// class only owns the generations and the pure transitions. All decision
// predicates delegate to the existing MainActivity top-level pure functions
// (same package, JVM-testable without Robolectric).
//
// Bundle persistence contract (兩屏統一；ack 不存；shown 不還原；session-scoped 註解):
// - PERSISTED (rotation + process-death 皆還原): expanded / expandedGeneration /
//   dismissedForReconcilingGen. 純 UI 開關，跨世代由 shouldReset 守衛清除，
//   還原舊值是安全的 (新世代首個 sync 即重置).
// - SESSION-SCOPED (永不寫入 Bundle；restore 強制清除): acknowledged /
//   ackGeneration. Consent 必須是當次會話的明確勾選：per-process 世代計數器在
//   process-death 後重置，舊 Bundle 的 gen 會誤配新世代而造成撞代誤保留；
//   且旋轉重建也要求使用者重新勾選 (fail-closed，比 Main 舊行為更嚴但兩屏一致).
//   saveToBundle 不寫 ack 鍵；restoreFromBundle 無條件清 ack (即使舊 Bundle 殘留
//   legacy ack 鍵亦忽略). 本次選 (a) restore 後清 ack；世代跨 process 唯一為
//   備選 (b)，未採用.
// - TRANSIENT (永不寫入；restore 強制清除): shownPending / shownSaving.
//   shownPending 本體由 retained VM 重播 (pending 對象)，不經 Bundle；
//   shownSaving 只是 QUEUED->SAVING 播報邊沿快照，下次 sync 重算.
//   空 Bundle / 缺鍵省略 (null generations 不寫鍵)；restore 對缺鍵回預設.
package com.galaxydual.plus

import android.os.Bundle

// UI-only dialog state for one policy-confirm dialog instance. Field-for-field
// parity with MainActivity's previous private vars:
// - acknowledged / ackGeneration: ack checkbox bound to pending.generation
//   (same-generation retain, cross-generation reset).
// - expanded / expandedGeneration: full-list Toggle, same contract as ack.
// - dismissedForReconcilingGen: Stage J4 local dismiss during RECONCILING
//   (UI-only suppression, never clears VM fence; cleared on terminal/cross-gen).
// - shownPending / shownSaving: already-on-screen instance + SAVING snapshot
//   for the QUEUED->SAVING announce edge (播報一次).
internal class PolicyConfirmDialogState {
    // SESSION-SCOPED (in-memory only, 永不進 Bundle；restore 強制清除):
    // - acknowledged / ackGeneration: ack checkbox bound to pending.generation
    //   (same-generation retain in-memory rotation, cross-generation reset;
    //   任何 Bundle 還原皆清除，需重新勾選).
    // PERSISTED (Bundle 還原):
    // - expanded / expandedGeneration: full-list Toggle, same contract as ack
    //   in-memory, 但允許 Bundle 還原 (純顯示開關，安全).
    // - dismissedForReconcilingGen: Stage J4 local dismiss during RECONCILING
    //   (UI-only suppression, never clears VM fence; cleared on terminal/cross-gen).
    // TRANSIENT (in-memory only, 永不進 Bundle；restore 強制清除):
    // - shownPending / shownSaving: already-on-screen instance + SAVING snapshot
    //   for the QUEUED->SAVING announce edge (播報一次).
    var acknowledged: Boolean = false
    var ackGeneration: Long? = null
    var expanded: Boolean = false
    var expandedGeneration: Long? = null
    var dismissedForReconcilingGen: Long? = null
    var shownPending: PendingPolicyWrite? = null
    var shownSaving: Boolean = false

    // New pending on screen: generation-guard ack + expanded (rotation keeps
    // same-gen, cross-gen resets). Mirrors MainActivity syncPolicyWriteDialog
    // new-dialog branch (policyAckShouldReset / policyWriteExpandedShouldReset).
    fun onDialogShown(pending: PendingPolicyWrite, saving: Boolean) {
        if (policyAckShouldReset(ackGeneration, pending.generation)) {
            acknowledged = false
            ackGeneration = null
        }
        if (policyWriteExpandedShouldReset(expandedGeneration, pending.generation)) {
            expanded = false
            expandedGeneration = null
        }
        shownPending = pending
        shownSaving = saving
    }

    fun onPendingCleared() {
        acknowledged = false
        ackGeneration = null
        expanded = false
        expandedGeneration = null
        dismissedForReconcilingGen = null
        shownPending = null
        shownSaving = false
    }

    fun onAckChanged(checked: Boolean, pendingGeneration: Long) {
        acknowledged = checked
        ackGeneration = if (checked) pendingGeneration else null
    }

    fun onExpandedChanged(newExpanded: Boolean, pendingGeneration: Long) {
        expanded = newExpanded
        expandedGeneration = if (newExpanded) pendingGeneration else null
    }

    // Stage J4: RECONCILING local dismiss (Back/觸外/取消/稍後刷新): record the
    // generation so sync suppresses rebuild; VM fence untouched.
    fun onLocalDismissForReconciling(pending: PendingPolicyWrite) {
        dismissedForReconcilingGen = pending.generation
        shownPending = null
        shownSaving = false
    }

    fun clearDismissIfNotReconciling(reconciling: Boolean) {
        if (!reconciling) dismissedForReconcilingGen = null
    }

    fun shouldSuppressForReconciling(pending: PendingPolicyWrite, reconciling: Boolean): Boolean =
        reconciling && dismissedForReconcilingGen == pending.generation

    fun onSavingFlip(savingNow: Boolean) {
        shownSaving = savingNow
    }

    // Rotation retain: Bundle keys are file-private; Activities call these from
    // onSaveInstanceState / onCreate. Null generations are omitted (Bundle has
    // no null-long). SESSION-SCOPED 分隔：ack 永不寫入 (見檔案頭)；TRANSIENT
    // 分隔：shownPending / shownSaving 永不寫入 (VM 重播／下次 sync 重算).
    fun saveToBundle(out: Bundle) {
        out.putBoolean(KEY_EXPANDED, expanded)
        expandedGeneration?.let { out.putLong(KEY_EXPANDED_GEN, it) }
        dismissedForReconcilingGen?.let { out.putLong(KEY_DISMISSED_GEN, it) }
    }

    fun restoreFromBundle(saved: Bundle?) {
        if (saved == null) {
            onPendingCleared()
            return
        }
        // SESSION-SCOPED: ack 永不清單寫入、還原強制清除 (舊 Bundle 殘留 legacy
        // "policyConfirm.ack"/"policyConfirm.ackGen" 鍵一律忽略，不讀取).
        acknowledged = false
        ackGeneration = null
        expanded = saved.getBoolean(KEY_EXPANDED, false)
        expandedGeneration = if (saved.containsKey(KEY_EXPANDED_GEN)) saved.getLong(KEY_EXPANDED_GEN) else null
        dismissedForReconcilingGen =
            if (saved.containsKey(KEY_DISMISSED_GEN)) saved.getLong(KEY_DISMISSED_GEN) else null
        // TRANSIENT: shownPending 由 retained VM 重播，不經 Bundle；shownSaving 為
        // 播報邊沿快照，不還原 (舊 Bundle 殘留 "policyConfirm.shown*" 鍵一律忽略).
        shownPending = null
        shownSaving = false
    }

    // JVM-testable snapshot without Android Bundle (Bundle is an Android stub
    // on JVM unit tests). Map round-trip mirrors saveToBundle/restoreFromBundle:
    // 只含 PERSISTED 鍵；ack / shown 永不寫入、還原強制清除 (與 Bundle 語義一致).
    fun toMap(): Map<String, Any?> = mapOf(
        KEY_EXPANDED to expanded,
        KEY_EXPANDED_GEN to expandedGeneration,
        KEY_DISMISSED_GEN to dismissedForReconcilingGen,
    )

    fun restoreFromMap(map: Map<String, Any?>) {
        // SESSION-SCOPED / TRANSIENT: 強制清除 (即使 map 殘留 legacy ack / shown
        // 鍵亦忽略，與 restoreFromBundle 一致).
        acknowledged = false
        ackGeneration = null
        expanded = map[KEY_EXPANDED] as? Boolean ?: false
        expandedGeneration = map[KEY_EXPANDED_GEN] as? Long
        dismissedForReconcilingGen = map[KEY_DISMISSED_GEN] as? Long
        shownPending = null
        shownSaving = false
    }

    companion object {
        // PERSISTED 鍵 (兩屏 Bundle 格式統一；Main 舊手寫 "policyExpanded*" 已刪，
        // 改走 holder；舊 Bundle 的舊鍵名自然忽略).
        internal const val KEY_EXPANDED = "policyConfirm.expanded"
        internal const val KEY_EXPANDED_GEN = "policyConfirm.expandedGen"
        internal const val KEY_DISMISSED_GEN = "policyConfirm.dismissedGen"
        // NOTE: ack ("policyConfirm.ack"/"policyConfirm.ackGen") 與 shown
        // ("policyConfirm.shownGen"/"policyConfirm.shownSaving") 為 legacy 鍵名，
        // 現語義下永不寫入；restore 忽略殘留舊鍵. 常數已刪除，避免誤用.
    }
}

// ===== Phase D: ROOT uninstall post-verify (re-probe, residual non-empty = fail) =====
//
// After a ROOT uninstall (pm uninstall --user <verified uid> over the warned set),
// the caller MUST re-run probeDualApps over the same set with that verified uid: any residual
// non-empty install is a verification failure. The outcome feeds a three-choice
// dialog ([verify success -> clear] / [still clear at own risk] / [cancel]);
// a failure never auto-proceeds (fail-closed, explicit choice required).
internal sealed interface UninstallVerifyOutcome {
    data object VerifiedEmpty : UninstallVerifyOutcome
    data class Residual(val remaining: List<String>) : UninstallVerifyOutcome
    data class ProbeFailed(val reason: String) : UninstallVerifyOutcome
}

// Pure decision: post-uninstall probe -> verify outcome. Ok(empty) is the only
// success; Ok(non-empty) is residual failure; Failed is probe failure.
internal fun decideUninstallVerifyResult(postProbe: DualProbeResult): UninstallVerifyOutcome =
    when (postProbe) {
        is DualProbeResult.Ok ->
            if (postProbe.installed.isEmpty()) UninstallVerifyOutcome.VerifiedEmpty
            else UninstallVerifyOutcome.Residual(postProbe.installed.sorted())
        is DualProbeResult.Failed -> UninstallVerifyOutcome.ProbeFailed(postProbe.reason)
    }

// Positive (verify-success-then-clear) is enabled ONLY on VerifiedEmpty.
// The force-clear (still clear at own risk) and cancel buttons stay enabled in
// all outcomes; the dialog never auto-proceeds.
internal fun uninstallVerifyPositiveEnabled(outcome: UninstallVerifyOutcome): Boolean =
    outcome is UninstallVerifyOutcome.VerifiedEmpty

// Click guard (single source for the dialog positive onClick no-op):
// Residual / ProbeFailed 下點擊一律 no-op (不 dismiss、不 proceed)；僅
// VerifiedEmpty 放行. Dialog 側 `if (!...Enabled) return` 即此語義，JVM 以此鎖定.
internal fun uninstallVerifyPositiveClickShouldProceed(outcome: UninstallVerifyOutcome): Boolean =
    uninstallVerifyPositiveEnabled(outcome)

// Residual list truncation (display≡inspectability 上界；JVM 可測):
// shown = 前 max 項，rest = 溢出數. Production dialog (MainActivity
// showUninstallVerifyDialog) 與 fallback 共用同一切分；dialog 以 "\n" join +
// "\n…" 溢出標記，fallback 以 ", " join + 同式溢出後綴.
internal const val UNINSTALL_VERIFY_LIST_MAX = 20

internal fun uninstallVerifyResidualDisplay(
    remaining: List<String>,
    max: Int = UNINSTALL_VERIFY_LIST_MAX,
): Pair<List<String>, Int> {
    val shown = remaining.take(max.coerceAtLeast(0))
    return shown to (remaining.size - shown.size)
}

// JVM seam for the verify dialog message (production resolves resources;
// fallback keeps the same three-way semantics for tests).
internal fun uninstallVerifyMessageFallback(outcome: UninstallVerifyOutcome): String = when (outcome) {
    is UninstallVerifyOutcome.VerifiedEmpty ->
        "卸載後驗證成功：雙開分身已無殘留，可繼續清除。"
    is UninstallVerifyOutcome.Residual -> {
        val (shown, rest) = uninstallVerifyResidualDisplay(outcome.remaining)
        val tail = if (rest > 0) "…（僅顯示前 ${shown.size} 項，共 ${outcome.remaining.size} 項）" else ""
        "卸載後驗證失敗：雙開分身仍有殘留分身（${outcome.remaining.size}）：${shown.joinToString(", ")}$tail。請選「仍要清除」自擔風險，或取消後手動關閉雙開。"
    }
    is UninstallVerifyOutcome.ProbeFailed ->
        "卸載後驗證無法完成（探測失敗：${outcome.reason}）。殘留未知：請選「仍要清除」自擔風險，或取消後重試。"
}
