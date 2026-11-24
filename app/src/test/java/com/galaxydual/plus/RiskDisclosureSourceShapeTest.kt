// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-2 (M1)：風險揭露 UI wiring 的來源形狀鎖定（JVM，無 Robolectric）。
// mirrors RemoveConfirmMessageTest / HookSubtitleResTest：直接讀 Production .kt 文字，
// 用輕量 functionBlock 解析函式本體，確認 gate 只在使用者觸發的實驗性操作入口、
// 取消不標記 ack、既有破壞性守衛文案未被移除、且沒有『Hook 生效前已取得同意』宣稱。
package com.galaxydual.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RiskDisclosureSourceShapeTest {

    private fun prodFile(name: String): File {
        val candidates = listOf(
            File("src/main/java/com/galaxydual/plus/$name"),
            File("app/src/main/java/com/galaxydual/plus/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing $name (tried ${candidates.map { it.path }})")
    }

    private fun block(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("$signature must open a block", brace >= 0)
        var depth = 0
        for (i in brace until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("$signature block never closes")
    }

    private val main by lazy { prodFile("MainActivity.kt").readText() }
    private val editor by lazy { prodFile("PolicyActivity.kt").readText() }
    private val riskNotice by lazy { prodFile("RiskNotice.kt").readText() }

    @Test
    fun persistentNotice_andDetailsEntry_taggedInBothScreens() {
        assertTrue("main must carry persistent risk_notice tag", main.contains("tag = \"risk_notice\""))
        assertTrue("main must carry risk_details_entry tag", main.contains("tag = \"risk_details_entry\""))
        assertTrue("editor must carry risk_details_entry tag", editor.contains("tag = \"risk_details_entry\""))
        // 提示文字一律走 R7b-1 三語系資源，不硬編字串。
        assertTrue("main short notice must use resource", main.contains("R.string.risk_notice_short"))
        assertTrue("both entries must use risk_details_entry resource",
            main.contains("R.string.risk_details_entry") && editor.contains("R.string.risk_details_entry"))
    }

    @Test
    fun detailsDialog_reopenableFromMainAndEditor() {
        val mainDetails = block(main, "private fun onRiskDetailsClicked()")
        assertTrue("main details entry must open risk dialog", mainDetails.contains("showRiskDetailsDialog("))
        assertFalse("details dialog must NOT acknowledge risk", mainDetails.contains("markAcknowledged"))
        // editor 的 intro 旁入口直接呼叫共用 helper（PolicyActivity 無同名的 member wrapper）。
        val editorCtor = block(editor, "override fun onCreate(")
        assertTrue("editor details entry must open risk dialog", editorCtor.contains("showRiskDetailsDialog("))
        assertFalse("editor details entry must NOT acknowledge risk", editorCtor.contains("markAcknowledged"))
    }

    @Test
    fun oneTimeGate_usedAtAllThreeExperimentalEntryPoints() {
        val clone = block(main, "private fun onCloneClicked()")
        assertTrue("manual clone must consult gate", clone.contains("shouldShowRiskGate("))
        assertTrue("manual clone must call showRiskAckGate", clone.contains("showRiskAckGate("))
        assertTrue("manual clone's original vm.clone must run after gate", clone.contains("vm.clone("))

        val batch = block(main, "private fun onBatchClicked()")
        assertTrue("batch must consult gate", batch.contains("shouldShowRiskGate("))
        assertTrue("batch must call showRiskAckGate", batch.contains("showRiskAckGate("))
        assertTrue("existing batch confirm must still run after gate", batch.contains("requestBatchConfirm()"))

        val policyWrite = block(main, "private fun onWritePolicyEntryClicked()")
        assertTrue("main policy write must consult gate", policyWrite.contains("shouldShowRiskGate("))
        assertTrue("main policy write must call showRiskAckGate", policyWrite.contains("showRiskAckGate("))
        assertTrue("existing pre-write warning must still run after gate", policyWrite.contains("startWriteWarning()"))

        val editorWrite = block(editor, "private fun onWritePolicyClicked()")
        assertTrue("editor policy write must consult gate", editorWrite.contains("shouldShowRiskGate("))
        assertTrue("editor policy write must call showRiskAckGate", editorWrite.contains("showRiskAckGate("))
        assertTrue("editor original candidate/warning flow must remain", editorWrite.contains("startEditorWriteWarning("))

        // 共用 store 必須由正確的 app-private prefs 名稱建立（Reviewer B 的 prefs-name 注意）。
        assertTrue("main must build store from RISK_ACK_PREFS_NAME",
            main.contains("getSharedPreferences(RISK_ACK_PREFS_NAME, MODE_PRIVATE).riskAckStore()"))
        assertTrue("editor must build store from RISK_ACK_PREFS_NAME",
            editor.contains("getSharedPreferences(RISK_ACK_PREFS_NAME, MODE_PRIVATE).riskAckStore()"))
    }

    @Test
    fun gate_notInvokedFromLifecycleOrReadOnlyRenderPaths() {
        for (signature in listOf(
            "override fun onCreate(",
            "override fun onResume()",
            "private fun buildUi()",
            "private fun render(",
        )) {
            val body = try {
                block(main, signature)
            } catch (_: AssertionError) {
                continue // 簽章不存在即略過。
            }
            assertFalse("$signature must not call shouldShowRiskGate", body.contains("shouldShowRiskGate("))
            assertFalse("$signature must not call showRiskAckGate", body.contains("showRiskAckGate("))
        }
        // 唯讀路徑關鍵字只出現在按鈕 listener／gated helper，不在 onCreate/onResume。
        val onCreate = block(main, "override fun onCreate(")
        assertFalse("onCreate must not show gate dialog", onCreate.contains("showRiskAckGate("))
    }

    @Test
    fun cancelPath_doesNotAcknowledgeOrContinue() {
        // MainActivity 的對話框 wrapper：negative 為 null listener，ack 只在 positive。
        val gate = block(main, "private fun showRiskAckGate(")
        assertTrue("cancel must be a null negative listener",
            gate.contains("setNegativeButton(R.string.dialog_cancel, null)"))
        val positive = block(gate, ".setPositiveButton(R.string.risk_ack_positive)")
        // D2a:ack 是非阻塞提交，UI 不得再等待 durability/queue backlog。
        assertTrue("positive must acknowledge without blocking", positive.contains("markAcknowledgedAsync()"))
        assertFalse("positive must not call the blocking markAcknowledged()", positive.contains("markAcknowledged()"))
        assertTrue("positive must continue", positive.contains("onContinue()"))
        // R4:consent 前後都必須確認 host 仍 alive，避免對 dying Activity 繼續流程。
        assertTrue("positive must re-check a live host before continuing",
            positive.contains("!activity.isFinishing && !activity.isDestroyed"))
        // R7b-1 共用 helper 同樣：取消不標記、不繼續。
        val sharedGate = block(riskNotice, "internal fun showRiskAckGate(")
        assertTrue("shared cancel must be a null negative listener",
            sharedGate.contains("setNegativeButton(R.string.dialog_cancel, null)"))
        val sharedPositive = block(sharedGate, ".setPositiveButton(R.string.risk_ack_positive)")
        assertTrue("shared positive must acknowledge without blocking",
            sharedPositive.contains("store.markAcknowledgedAsync()"))
        assertFalse("shared positive must not call the blocking markAcknowledged()",
            sharedPositive.contains("store.markAcknowledged()"))
        assertTrue("shared positive must continue", sharedPositive.contains("onContinue()"))
        assertTrue("shared positive must re-check a live host before continuing",
            sharedPositive.contains("!activity.isFinishing && !activity.isDestroyed"))
        // 兩個對話框實作（MainActivity wrapper 與 R7b-1 共用 helper）內容/語意必須一致，
        // 避免日後改了其中一邊造成風險揭露漂移。
        for (b in listOf(gate, sharedGate)) {
            assertTrue("gate must use shared risk title resource", b.contains("R.string.risk_details_title"))
            assertTrue("gate must use shared full-risk resource", b.contains("R.string.risk_details_full"))
            assertTrue("gate must be cancelable", b.contains("setCancelable(true)"))
            assertTrue("gate must guard against a dying Activity", b.contains("isFinishing || activity.isDestroyed"))
        }
    }

    @Test
    fun riskDialogHandle_dismissedOnDestroy() {
        assertTrue("main must declare a riskDialog handle", main.contains("private var riskDialog: AlertDialog? = null"))
        val onDestroy = block(main, "override fun onDestroy()")
        assertTrue("onDestroy must dismiss riskDialog", onDestroy.contains("riskDialog?.dismiss()"))
        assertTrue("onDestroy must clear riskDialog", onDestroy.contains("riskDialog = null"))
    }

    // #78: the shared helpers must hand the dialog back to the caller so the
    // host Activity can release it. Returning Unit leaks the window on destroy.
    @Test
    fun sharedRiskHelpers_returnDismissableHandle() {
        val details = block(riskNotice, "internal fun showRiskDetailsDialog(")
        assertTrue("shared details helper must return AlertDialog?", details.contains("AlertDialog?"))
        assertTrue("shared details helper must return the show() handle", details.contains("return try {"))

        val gate = block(riskNotice, "internal fun showRiskAckGate(")
        assertTrue("shared ack gate must return AlertDialog?", gate.contains("AlertDialog?"))
        assertTrue("shared ack gate must return the show() handle", gate.contains("return try {"))
        // Phase A ack-store contract must survive this change (D2a:non-blocking).
        assertTrue("ack gate must still persist acknowledgement",
            gate.contains("store.markAcknowledgedAsync()"))
    }

    // #78: PolicyActivity has the same handle/lifetime obligation as MainActivity,
    // for BOTH the risk dialogs and the newly found editor empty-guide dialog.
    @Test
    fun editorRiskAndEmptyGuideHandles_dismissedOnDestroy() {
        assertTrue("editor must declare a riskDialog handle", editor.contains("private var riskDialog: AlertDialog? = null"))
        assertTrue(
            "editor must declare an empty-guide handle",
            editor.contains("private var editorEmptyGuideDialog: AlertDialog? = null"),
        )

        val onCreate = block(editor, "override fun onCreate(")
        assertTrue("editor details entry must capture the shared handle", onCreate.contains("riskDialog = showRiskDetailsDialog("))

        val write = block(editor, "private fun onWritePolicyClicked()")
        assertTrue("editor ack gate must capture the shared handle", write.contains("riskDialog = showRiskAckGate("))
        assertTrue("editor empty-guide must capture its handle", write.contains("showEditorEmptyGuide("))
        val emptyGuide = block(editor, "private fun showEditorEmptyGuide()")
        assertTrue("showEditorEmptyGuide must store its handle", emptyGuide.contains("editorEmptyGuideDialog ="))

        val onDestroy = block(editor, "override fun onDestroy()")
        assertTrue("onDestroy must dismiss editor riskDialog", onDestroy.contains("riskDialog?.dismiss()"))
        assertTrue("onDestroy must clear editor riskDialog", onDestroy.contains("riskDialog = null"))
        assertTrue(
            "onDestroy must dismiss editor empty-guide dialog",
            onDestroy.contains("editorEmptyGuideDialog?.dismiss()"),
        )
        assertTrue("onDestroy must clear editor empty-guide dialog", onDestroy.contains("editorEmptyGuideDialog = null"))
    }

    @Test
    fun existingDestructiveGuardsRemainReferenced() {
        for (token in listOf(
            "R.string.remove_confirm_message",
            "R.string.pre_clear_message",
            "R.string.pre_clear_uninstall_verify_title",
            "R.string.pre_clear_uninstall_verify_success",
            "R.string.pre_clear_uninstall_verify_residual",
            "R.string.pre_clear_uninstall_verify_failed",
        )) {
            assertTrue("destructive guard $token must remain referenced in MainActivity", main.contains(token))
        }
    }

    @Test
    fun noProductionClaimOfPreActivationHookConsent() {
        for ((name, src) in listOf(
            "MainActivity.kt" to main,
            "PolicyActivity.kt" to editor,
            "RiskNotice.kt" to riskNotice,
        )) {
            assertFalse("$name must not claim Hook 已取得同意", src.contains("Hook 已取得同意"))
            assertFalse("$name must not claim obtained consent before activation",
                src.contains("obtained consent before activation"))
            assertFalse("$name must not claim 已於生效前取得同意", src.contains("已於生效前取得同意"))
        }
    }
}
