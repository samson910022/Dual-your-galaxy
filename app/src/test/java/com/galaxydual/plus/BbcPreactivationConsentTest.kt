// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR5 Phase B2 (#80): BBC toggle 首次開啟的 pre-activation opt-in consent。
// - 純決策 helper（framework-free，JVM 可測）：ON 且未 opt-in 才問；opt-in 寫入
//   Confirmed 才繼續 onToggle，Unknown 绝不繼續。
// - source-shape 鎖定 MainActivity 接線：取消零寫入並回復 OFF、show() 失敗的
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

    // ---- pure decision seams (no Android framework) ----

    @Test
    fun consentGate_onlyAsksWhenTurningOnWithoutPriorOptIn() {
        assertFalse(shouldRequestPreactivationOptIn(false, false))
        assertFalse(shouldRequestPreactivationOptIn(false, true))
        assertTrue("first ON on an unopted-in install must show the consent dialog",
            shouldRequestPreactivationOptIn(true, false))
        assertFalse("already opted-in installs must never be asked again",
            shouldRequestPreactivationOptIn(true, true))
    }

    @Test
    fun toggleProceedsOnlyWhenOptInCommitWasConfirmed() {
        assertTrue(shouldApplyBbcToggleAfterOptIn(true))
        assertFalse("Unknown opt-in write must never proceed to onToggle",
            shouldApplyBbcToggleAfterOptIn(false))
    }

    @Test
    fun decisionHelperIsFrameworkFree() {
        val src = prodFile("BbcPreactivationConsent.kt").readText()
        assertFalse("decision helper must stay JVM-testable without Android", src.contains("import android."))
        assertFalse("decision helper must not reference UI widgets", src.contains("AlertDialog"))
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
    fun toggleListener_consultsOptInBeforeFirstOn() {
        val listener = block(main, "toggle.setOnCheckedChangeListener")
        assertTrue("must guard programmatic restore", listener.contains("if (restoring)"))
        assertTrue("must consult the pure gate", listener.contains("shouldRequestPreactivationOptIn(checked,"))
        assertTrue("must read the persisted opt-in from the store",
            listener.contains("isBbcPreactivationOptIn()"))
        assertTrue("ungranted ON must show consent", listener.contains("showBbcPreactivationOptInDialog("))
        assertTrue("already opted-in (or OFF) must still reach the unchanged controller",
            listener.contains("bbcToggleController?.onToggle(checked)"))
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
        assertTrue(dialog.contains("bbcPreactivationDialog = dlg"))
        assertTrue("dismiss must clear only its own handle",
            dialog.contains("if (bbcPreactivationDialog === dlg) bbcPreactivationDialog = null"))
        assertTrue(dialog.contains(".show()"))
        assertFalse("the dialog itself must never write the opt-in",
            dialog.contains("setBbcPreactivationOptIn"))
        assertFalse("the dialog itself must never toggle BBC",
            dialog.contains("onToggle("))

        val positive = block(dialog, ".setPositiveButton(R.string.settings_bbc_preactivation_dialog_positive)")
        assertTrue("positive must accept", positive.contains("onAccept()"))
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
    fun acceptWritesOptInThenToggles_cancelRevertsOffWithoutWriting() {
        val accept = block(main, "fun acceptPreactivationOptIn(")
        assertTrue("accept must write the opt-in key",
            accept.contains("setBbcPreactivationOptInDetailed(true)"))
        assertTrue("accept must gate on the tri-state outcome",
            accept.contains("shouldApplyBbcToggleAfterOptIn("))
        assertTrue("confirmed opt-in must continue to the unchanged controller",
            accept.contains("bbcToggleController?.onToggle(checked)"))
        assertTrue("failed opt-in write must be marked Unknown and not toggle",
            accept.contains("markPreactivationOptInFailed()"))
        assertTrue("the opt-in commit must happen BEFORE onToggle",
            accept.indexOf("setBbcPreactivationOptInDetailed") < accept.indexOf("onToggle("))

        val cancel = block(main, "fun cancelPreactivationOptIn(")
        assertTrue(cancel.contains("restoring = true"))
        assertTrue(cancel.contains("toggle.isChecked = false"))
        assertTrue(cancel.contains("restoring = false"))
        assertFalse("cancel must never write the opt-in", cancel.contains("setBbcPreactivationOptIn"))
        assertFalse("cancel must never call the effect toggle", cancel.contains("onToggle("))

        val failed = block(main, "fun markPreactivationOptInFailed(")
        assertTrue("failure must be rendered as Unknown", failed.contains("BbcEffectStatus.Unknown"))
        assertTrue("failure must show an honest toast",
            failed.contains("R.string.settings_bbc_preactivation_write_failed"))
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
}
