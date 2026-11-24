// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR5 Phase B2 (#80): BBC toggle 首次開啟的 pre-activation opt-in consent。
// - 純決策 helper（framework-free，JVM 可測）：ON 且未 opt-in 才問；opt-in 寫入
//   Confirmed 才繼續 onToggle，Unknown 绝不繼續。
// - source-shape 鎖定 MainActivity 接線：取消零寫入並回復最後已知狀態、show() 失敗的
//   catch 亦 fail-closed 走 onCancel（且不寫 opt-in／不 onToggle）、接受才寫 opt-in、
//   已 opt-in 直接 onToggle、對話框 handle 追蹤並在 onDestroy 釋放。
// - 三語系字串鎖定：文案如實說明 opt-in 不是 Samsung 冷啟動刪除防護（#75），
//   BBC 效應仍 experimental／未實機驗證。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BbcPreactivationConsentTest {

    // ---- the consent gate is now the app-scoped state machine (framework-free) ----

    @Test
    fun consentStateMachineIsFrameworkFree() {
        val src = prodFile("BbcActivationConsentState.kt").readText()
        assertFalse("consent state machine must stay JVM-testable without Android",
            src.contains("import android."))
        assertFalse("consent state machine must not reference UI widgets", src.contains("AlertDialog"))
        assertTrue("the fail-closed gate must live in the shared state machine",
            src.contains("fun BbcActivationConsentState.requiresConsentFor"))
    }

    // ---- MainActivity wiring (source shape; mirrors RiskDisclosureSourceShapeTest) ----

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

    @Test
    fun toggleListener_offShortCircuitsAndOnUsesAppScopedConsentState() {
        val listener = block(main, "toggle.setOnCheckedChangeListener")
        assertTrue("must guard programmatic restore", listener.contains("if (restoring)"))
        assertTrue("OFF must short-circuit BEFORE any consent read",
            listener.contains("if (!checked)") && listener.contains("return@setOnCheckedChangeListener"))
        assertTrue("ON must derive from the app-scoped authorization state",
            listener.contains("consentHolder.currentState()"))
        assertTrue("must use the fail-closed state-machine gate",
            listener.contains("requiresConsentFor(checked)"))
        assertTrue("ungranted ON must show consent", listener.contains("showBbcPreactivationOptInDialog("))
        assertTrue("already-authorized ON must still reach the unchanged controller",
            listener.contains("bbcToggleController?.onToggle(checked)"))
        assertFalse("no blocking preference read on the Main-thread tap path",
            listener.contains("isBbcPreactivationOptIn()"))
        assertFalse("the old raw Boolean gate must be gone",
            listener.contains("shouldRequestPreactivationOptIn("))
        assertTrue("OFF must invalidate any pending consent dialog",
            listener.contains("dismissBbcConsentDialogSilently()"))
    }

    @Test
    fun consentDialog_cancelsWithZeroWritesAndTracksHandle() {
        val dialog = block(main, "private fun showBbcPreactivationOptInDialog(")
        assertTrue("must guard a dying Activity", dialog.contains("isFinishing || isDestroyed"))
        assertTrue(dialog.contains("R.string.settings_bbc_preactivation_dialog_title"))
        assertTrue(dialog.contains("R.string.settings_bbc_preactivation_dialog_message"))
        assertTrue(dialog.contains("R.string.settings_bbc_preactivation_dialog_positive"))
        assertTrue(dialog.contains("setNegativeButton("))
        assertTrue("back/tap-outside cancel must also roll back",
            dialog.contains("setOnCancelListener { onCancel() }"))
        assertTrue("an older dialog must be dismissed before showing a new one",
            dialog.contains("dismissBbcConsentDialogSilently()"))
        assertTrue("callbacks carry the captured intent sequence",
            dialog.contains("onAccept(intentSequence)"))
        assertTrue(dialog.contains("bbcPreactivationDialog = dlg"))
        assertTrue("dismiss must clear only its own handle",
            dialog.contains("if (bbcPreactivationDialog === dlg) bbcPreactivationDialog = null"))
        assertTrue(dialog.contains(".show()"))
        assertFalse("the dialog itself must never write the opt-in",
            dialog.contains("setBbcPreactivationOptIn"))
        assertFalse("the dialog itself must never toggle BBC",
            dialog.contains("onToggle("))

        val positive = block(dialog, ".setPositiveButton(R.string.settings_bbc_preactivation_dialog_positive)")
        assertTrue("positive must accept with the captured intent sequence",
            positive.contains("onAccept(intentSequence)"))
        assertFalse("positive must not roll back", positive.contains("onCancel()"))
        val negative = block(dialog, ".setNegativeButton(")
        assertTrue("negative must cancel", negative.contains("onCancel()"))
        assertFalse("negative must not accept", negative.contains("onAccept()"))

        assertTrue("must declare a tracked handle",
            main.contains("private var bbcPreactivationDialog: AlertDialog? = null"))
        val onDestroy = block(main, "override fun onDestroy()")
        assertTrue("onDestroy must dismiss the consent dialog",
            onDestroy.contains("bbcPreactivationDialog?.dismiss()"))
        assertTrue("onDestroy must clear the consent handle",
            onDestroy.contains("bbcPreactivationDialog = null"))
    }

    @Test
    fun consentDialog_showFailureCatchRollsBackWithZeroWrites() {
        val dialog = block(main, "private fun showBbcPreactivationOptInDialog(")
        val catch = block(dialog, "} catch (_: Exception) {")
        assertTrue("show() failure must roll back through onCancel",
            catch.contains("onCancel()"))
        assertFalse("show() failure must never write the opt-in",
            catch.contains("setBbcPreactivationOptIn"))
        assertFalse("show() failure must never toggle BBC",
            catch.contains("onToggle("))
    }

    @Test
    fun consentFlow_usesAppScopedIOLaneAndDurableFence() {
        // MainActivity no longer performs a blocking binder commit. The opt-in
        // commit lives in the app-scoped coordinator (GalaxyApp) and its write
        // key is still pinned there as a legitimate literal/contract check.
        val accept = block(main, "fun acceptPreactivationOptIn(")
        assertTrue("accept must enqueue on the app-scoped IO lane",
            accept.contains("submitConsentWrite("))
        assertTrue("accept must fence with the captured service generation",
            accept.contains("consentHolder.currentGeneration()"))
        assertTrue("confirmed opt-in must continue to the unchanged controller",
            accept.contains("bbcToggleController?.onToggle(checked)"))
        assertTrue("Unknown must render the durable-fence failure path",
            accept.contains("BbcWriteOutcome.Unknown -> markPreactivationOptInFailed(seq)"))
        assertTrue("result must hop back to Main with a sequence guard",
            accept.contains("mainHandler.post") && accept.contains("seq != activeBbcConsentSequence"))
        assertFalse("no blocking preference write on Main",
            accept.contains("setBbcPreactivationOptInDetailed("))
        assertTrue("the opt-in write is ordered BEFORE the effect toggle",
            accept.indexOf("submitConsentWrite(") < accept.indexOf("onToggle("))

        val galaxy = prodFile("GalaxyApp.kt").readText()
        assertTrue("the owner app must own the opt-in commit seam",
            galaxy.contains("setBbcPreactivationOptInDetailed(true)"))
        assertTrue("consent I/O must share the app serializer",
            galaxy.contains("consentCommit = {"))

        val store = prodFile("HookPolicyStore.kt").readText()
        assertTrue("the durable opt-in key must still be written",
            store.contains("PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN"))
        assertFalse("grant must NOT fabricate the reserved classification",
            store.contains("INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED"))
    }

    @Test
    fun cancelRestoresConfirmableStateWithZeroWrites() {
        val cancel = block(main, "fun cancelPreactivationOptIn(")
        assertTrue("cancel must invalidate the intent so a late write is dropped",
            cancel.contains("consentHolder.nextIntentSequence()"))
        assertTrue(cancel.contains("consentHolder.abandonIntent(seq)"))
        assertTrue("cancel must atomically restore the controller UI state",
            cancel.contains("bbcToggleController?.restoreConsentUi(lastSettledChecked)"))
        assertTrue(cancel.contains("setChecked(lastSettledChecked)"))
        assertFalse("cancel must never write the opt-in", cancel.contains("setBbcPreactivationOptIn"))
        assertFalse("cancel must never claim an effect toggle", cancel.contains("onToggle("))
        assertFalse("cancel must never fabricate an OFF via a raw checkbox set",
            cancel.contains("toggle.isChecked = false"))

        val failed = block(main, "fun markPreactivationOptInFailed(")
        assertTrue("failure must be rendered as Unknown", failed.contains("BbcEffectStatus.Unknown"))
        assertTrue("failure must show an honest toast",
            failed.contains("R.string.settings_bbc_preactivation_write_failed"))
        assertTrue("failure toast must be deduped per intent",
            failed.contains("consentFailureToastSequence != seq"))
        assertFalse("failure path must not write the effect key", failed.contains("setBbcInjectionEnabled"))
    }

    // ---- i18n / honest copy ----

    private val locales = listOf("values", "values-en", "values-b+zh+Hant")
    private val newKeys = listOf(
        "settings_bbc_preactivation_dialog_title",
        "settings_bbc_preactivation_dialog_message",
        "settings_bbc_preactivation_dialog_positive",
        "settings_bbc_preactivation_dialog_negative",
        "settings_bbc_preactivation_write_failed",
    )

    private fun resFile(locale: String): File {
        val candidates = listOf(File("src/main/res/$locale/strings.xml"), File("app/src/main/res/$locale/strings.xml"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing res file: $locale (tried ${candidates.map { it.path }})")
    }

    private fun stringMap(locale: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resFile(locale))
        val out = linkedMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            out[n.attributes.getNamedItem("name").nodeValue] = n.textContent.trim()
        }
        return out
    }

    @Test
    fun allThreeLocalesCarryNonEmptyConsentStrings() {
        val maps = locales.associateWith(::stringMap)
        for (locale in locales) {
            for (key in newKeys) {
                assertTrue("$locale missing $key", maps.getValue(locale).containsKey(key))
                assertTrue("$locale empty $key", maps.getValue(locale).getValue(key).isNotBlank())
            }
        }
        assertEquals("Hant text must match the default locale",
            maps.getValue("values").filterKeys { it in newKeys },
            maps.getValue("values-b+zh+Hant").filterKeys { it in newKeys })
    }

    @Test
    fun consentCopyIsHonestAboutColdStartAndExperimentalEffect() {
        val zh = stringMap("values").getValue("settings_bbc_preactivation_dialog_message")
        val en = stringMap("values-en").getValue("settings_bbc_preactivation_dialog_message")
        assertTrue("zh copy must state experimental", zh.contains("實驗性"))
        assertTrue("zh copy must state it is not a cold-start deletion guard", zh.contains("冷啟動"))
        assertTrue("zh copy must cite issue #75", zh.contains("#75"))
        assertTrue("en copy must state experimental", en.contains("xperimental"))
        assertTrue("en copy must state cold-start", en.contains("cold-start"))
        assertTrue("en copy must cite issue #75", en.contains("#75"))
        assertTrue("zh copy must disclaim guarantees", zh.contains("不保證"))
        assertTrue("en copy must disclaim guarantees",
            en.contains("not device-verified") && en.contains("no general effectiveness"))
    }

    // ---- honest-copy contract (PR #90 Phase D / P2-07) ----
    //
    // These are JVM resource-content checks that parse the ACTUAL strings.xml
    // values (no device/runtime reachability is claimed here, nor source-shape
    // pinning). They fail if any locale reintroduces the old fail-open /
    // confirmed-off / "cancel means OFF" claims.

    private val honestCopyKeys = listOf(
        "risk_details_full",
        "settings_bbc_preactivation_write_failed",
        "settings_bbc_preactivation_dialog_message",
    )

    private fun honestCopyMap(locale: String): Map<String, String> {
        val map = stringMap(locale)
        for (key in honestCopyKeys) {
            assertTrue("$locale missing honest-copy key $key", map.containsKey(key))
            assertTrue("$locale empty honest-copy key $key", map.getValue(key).isNotBlank())
        }
        return map
    }

    @Test
    fun riskDetailsInAllLocalesNoLongerClaimsCompileTimeDefaultOn() {
        val maps = locales.associateWith(::honestCopyMap)
        val banned = listOf(
            "預先 Hook 的現實限制",
            "BBC_AVAILABLE_APPS_INJECTION 預設為 true",
            "須另行設計",
            "可能早於本 App 首次風險警告",
            "Pre-activation Hook limitation",
            "defaults to true",
            "would require a separate design",
            "take effect BEFORE this App",
        )
        for (locale in locales) {
            val copy = maps.getValue(locale).getValue("risk_details_full")
            for (phrase in banned) {
                assertFalse("$locale risk_details_full must not claim '$phrase'", copy.contains(phrase))
            }
            assertTrue("$locale risk_details_full must keep the #75 caveat", copy.contains("#75"))
            if (locale == "values-en") {
                assertTrue("en risk copy must keep the no-data-safety caveat",
                    copy.contains("data safety cannot be guaranteed"))
                assertTrue("en risk copy must describe fail-closed reads", copy.contains("fail closed"))
            } else {
                assertTrue("$locale risk copy must keep the no-data-safety caveat",
                    copy.contains("無法保證資料安全"))
                assertTrue("$locale risk copy must describe fail-closed reads", copy.contains("fail-closed"))
            }
        }
    }

    @Test
    fun consentWriteFailureCopyReportsUnknownNotConfirmedOff() {
        val maps = locales.associateWith(::honestCopyMap)
        val banned = listOf("未持久化", "效應未啟用", "was not persisted", "was not enabled")
        for (locale in locales) {
            val copy = maps.getValue(locale).getValue("settings_bbc_preactivation_write_failed")
            for (phrase in banned) {
                assertFalse("$locale write-failed copy must not claim '$phrase'", copy.contains(phrase))
            }
            // The UNKNOWN state must be stated explicitly. A retry/re-verify
            // instruction is only supplementary evidence, never the sole
            // UNKNOWN marker (old honest-looking copy also mentioned retry).
            val mandatoryUnknownMarkers = if (locale == "values-en") {
                listOf("UNKNOWN")
            } else {
                listOf("結果未知", "未知")
            }
            assertTrue("$locale write-failed copy must state UNKNOWN explicitly",
                mandatoryUnknownMarkers.any(copy::contains))
            val retryMarkers = if (locale == "values-en") {
                listOf("retry", "re-verify", "hook-process restart")
            } else {
                listOf("重試", "重新核對", "重啟 Hook 行程")
            }
            assertTrue("$locale write-failed copy must also offer a retry/re-verify path",
                retryMarkers.any(copy::contains))
            assertTrue("$locale write-failed copy must say no further write was performed",
                if (locale == "values-en") copy.contains("no further write was performed")
                else copy.contains("本次未再進行其他寫入"))
            assertTrue("$locale write-failed copy must keep the cold-start guard disclaimer",
                if (locale == "values-en") copy.contains("NOT a Samsung cold-start deletion guard")
                else copy.contains("不是 Samsung 冷啟動刪除防護"))
        }
    }

    @Test
    fun consentDialogCopyNeverClaimsCancelTurnsEffectOff() {
        val maps = locales.associateWith(::honestCopyMap)
        val banned = listOf("取消則維持關閉", "leaves the effect OFF")
        for (locale in locales) {
            val copy = maps.getValue(locale).getValue("settings_bbc_preactivation_dialog_message")
            for (phrase in banned) {
                assertFalse("$locale dialog copy must not claim '$phrase'", copy.contains(phrase))
            }
            assertTrue("$locale dialog copy must describe the two-phase grant/enable flow",
                if (locale == "values-en") copy.contains("two phases") else copy.contains("兩階段"))
            assertTrue("$locale dialog copy must say the UI returns to its last known state",
                if (locale == "values-en") copy.contains("last known state") else copy.contains("最後已知狀態"))
            assertTrue("$locale dialog copy must not promise the effect is off",
                if (locale == "values-en") copy.contains("does NOT prove the Hook effect is off")
                else copy.contains("不證明 Hook 效應已關閉"))
            assertTrue("$locale dialog copy must keep the #75 / cold-start caveat",
                copy.contains("#75") && (if (locale == "values-en") copy.contains("cold-start")
                else copy.contains("冷啟動")))
        }
    }

    @Test
    fun consentDialogNegativeButtonNeverClaimsCancelMeansOff() {
        // MainActivity wires this key to setNegativeButton(...) — the cancel path
        // is zero writes and restores the last known state, NOT necessarily OFF.
        val negativeKey = "settings_bbc_preactivation_dialog_negative"
        val banned = listOf("維持關閉", "stay OFF")
        for (locale in locales) {
            val map = stringMap(locale)
            assertTrue("$locale missing $negativeKey", map.containsKey(negativeKey))
            val label = map.getValue(negativeKey)
            assertTrue("$locale empty $negativeKey", label.isNotBlank())
            for (phrase in banned) {
                assertFalse("$locale $negativeKey must not claim '$phrase'", label.contains(phrase))
            }
            assertTrue("$locale $negativeKey must describe the last known state",
                if (locale == "values-en") label.contains("last known state")
                else label.contains("最後已知狀態"))
        }
    }

    @Test
    fun honestCopyKeysAreByteIdenticalBetweenHantAndDefault() {
        assertEquals(
            "Hant/default parity for the edited resource keys",
            stringMap("values").filterKeys { it in honestCopyKeys },
            stringMap("values-b+zh+Hant").filterKeys { it in honestCopyKeys },
        )
    }
}
