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
    private val dialogSlot by lazy { prodFile("DialogSlot.kt").readText() }

    /** 確認宣告的前一個 token 就是 @Synchronized（換行/空白無所謂）。 */
    private fun assertSynchronizedDeclaration(signature: String) {
        val index = dialogSlot.indexOf(signature)
        assertTrue("$signature must exist", index >= 0)
        val preceding = dialogSlot.substring(0, index).trimEnd()
        assertTrue(
            "$signature must be annotated @Synchronized " +
                "(cross-thread dismiss callbacks must serialize with install/clear)",
            preceding.endsWith("@Synchronized"),
        )
    }

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
        assertTrue(
            "existing pre-write warning must still run after gate with genuine-tap provenance",
            policyWrite.contains("startWriteWarning(fromUserTap = true)"),
        )

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

    // #89 D2b (#78)：handle 所有權由 framework-free DialogSlot 統一管理。
    // 這裡只鎖來源形狀；null 不覆寫／single-flight／identity guard／clear 語意
    // 由 DialogSlotTest 的行為測試驗證（見該檔 DEVICE-UNVERIFIED 註記）。
    @Test
    fun riskDialogSlot_dismissedOnDestroy() {
        assertTrue("main must use a DialogSlot for risk dialogs",
            main.contains("private val riskDialogSlot = DialogSlot<AlertDialog>()"))
        assertFalse("main must not keep a nullable riskDialog field",
            main.contains("private var riskDialog: AlertDialog? = null"))

        val onDestroy = block(main, "override fun onDestroy()")
        assertTrue("onDestroy must clear the risk dialog slot",
            onDestroy.contains("riskDialogSlot.clear { it.dismiss() }"))
        assertFalse("onDestroy must not cancel() the risk dialog", onDestroy.contains("riskDialogSlot.clear { it.cancel() }"))

        for (signature in listOf("private fun showRiskDetailsDialog(", "private fun showRiskAckGate(")) {
            val b = block(main, signature)
            assertTrue("$signature must install its handle into the slot", b.contains("riskDialogSlot.install("))
            assertTrue("$signature must supply isShowing", b.contains("isShowing = { it.isShowing }"))
            assertTrue("$signature must bind a dismiss callback",
                b.contains("bindDismiss = { d, cb -> d.setOnDismissListener { cb() } }"))
            assertTrue("$signature must discard candidates with dismiss()",
                b.contains("discard = { it.dismiss() }"))
            assertFalse("$signature must not null-assign the old field", b.contains("riskDialog = "))
        }
    }

    @Test
    fun dialogSlotSource_preservesLeakGuards() {
        assertTrue("null candidate must be rejected before any write",
            dialogSlot.contains("if (candidate == null) return false"))
        assertTrue("single-flight must compare identities and isShowing",
            dialogSlot.contains("existing !== candidate") && dialogSlot.contains("isShowing(existing)"))
        assertTrue("single-flight must best-effort discard the new candidate",
            dialogSlot.contains("bestEffort(discard, candidate)"))
        assertTrue("dismiss callback must be identity-guarded",
            dialogSlot.contains("if (slot === candidate) slot = null"))
        assertTrue("clear must catch dismiss throwables", dialogSlot.contains("bestEffort(dismiss, previous)"))
        val clearNull = dialogSlot.indexOf("slot = null", dialogSlot.indexOf("fun clear("))
        val clearDismiss = dialogSlot.indexOf("bestEffort(dismiss, previous)")
        assertTrue("clear must null the field before dismissing", clearNull >= 0 && clearDismiss > clearNull)
        assertFalse("framework-free slot must not import Android types", dialogSlot.contains("import android."))

        // #89 D2b REVIEW CHANGES：identity guard 的 check-then-set 必須和 install/clear
        // 在同一個 monitor 上序列化，否則從非 UI 執行緒送達的過期 callback 可以在
        // install 持有 monitor 時插進來，把較新的 handle 清掉。DialogSlotTest 的
        // latch interleaving 是行為證據；這裡鎖住來源形狀，避免註解被無聲移除。
        assertSynchronizedDeclaration("private fun onDismissed(")
        assertSynchronizedDeclaration("fun install(")
        assertSynchronizedDeclaration("fun clear(")
        val currentIndex = dialogSlot.indexOf("val current:")
        assertTrue("current must exist", currentIndex >= 0)
        assertTrue("current getter must be @Synchronized",
            dialogSlot.substring(currentIndex, (currentIndex + 80).coerceAtMost(dialogSlot.length))
                .contains("@Synchronized get()"))
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
    fun editorRiskAndEmptyGuideSlots_dismissedOnDestroy() {
        assertTrue("editor must use a risk dialog slot",
            editor.contains("private val riskDialogSlot = DialogSlot<AlertDialog>()"))
        assertTrue("editor must use an empty-guide slot",
            editor.contains("private val editorEmptyGuideDialogSlot = DialogSlot<AlertDialog>()"))
        assertFalse("editor must not keep a nullable riskDialog field",
            editor.contains("private var riskDialog: AlertDialog? = null"))
        assertFalse("editor must not keep a nullable empty-guide field",
            editor.contains("private var editorEmptyGuideDialog: AlertDialog? = null"))

        val onCreate = block(editor, "override fun onCreate(")
        assertTrue("editor details entry must install the shared handle",
            onCreate.contains("val dlg = showRiskDetailsDialog(this@PolicyActivity)") &&
            onCreate.contains("riskDialogSlot.install("))

        val write = block(editor, "private fun onWritePolicyClicked()")
        assertTrue("editor ack gate must install the shared handle",
            write.contains("val dlg = showRiskAckGate(this, riskAckStore) { proceed() }") &&
            write.contains("riskDialogSlot.install("))
        assertTrue("editor empty-guide must still be shown", write.contains("showEditorEmptyGuide("))
        val emptyGuide = block(editor, "private fun showEditorEmptyGuide()")
        assertTrue("showEditorEmptyGuide must install its handle",
            emptyGuide.contains("val dlg = AlertDialog.Builder(this)") &&
            emptyGuide.contains("editorEmptyGuideDialogSlot.install("))
        assertFalse("showEditorEmptyGuide must not null-assign a failed show",
            emptyGuide.contains("editorEmptyGuideDialog ="))

        val onDestroy = block(editor, "override fun onDestroy()")
        assertTrue("onDestroy must clear both slots with dismiss()",
            onDestroy.contains("riskDialogSlot.clear { it.dismiss() }") &&
            onDestroy.contains("editorEmptyGuideDialogSlot.clear { it.dismiss() }"))
        assertFalse("onDestroy must not cancel() either dialog", onDestroy.contains(".cancel()"))
    }

    // #89 D2b follow-up: the instrumentation test reaches the Activity handle by
    // reflection, so a renamed field compiles fine but throws NoSuchFieldException
    // on device. Lock the seam in a JVM test so CI catches the drift too.
    @Test
    fun instrumentationRiskTest_readsSlotNotRemovedField() {
        val candidates = listOf(
            File("src/androidTest/java/com/galaxydual/plus/RiskDisclosureUiTest.kt"),
            File("app/src/androidTest/java/com/galaxydual/plus/RiskDisclosureUiTest.kt"),
        )
        val src = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing RiskDisclosureUiTest.kt (tried ${candidates.map { it.path }})")
        val text = src.readText()
        assertTrue("instrumentation must reflect the DialogSlot field",
            text.contains("field(activity, \"riskDialogSlot\")"))
        assertFalse("the removed nullable riskDialog field must not be reflected",
            text.contains("field(activity, \"riskDialog\")"))
        assertTrue("awaitRiskDialog must reuse the same slot accessor",
            text.contains("val d = riskDialogOn(activity)"))
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
