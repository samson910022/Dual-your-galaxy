// Tests Android's compiled resource selection, not the source XML files.
package com.galaxydual.plus

import android.content.res.Configuration
import android.os.LocaleList
import android.test.InstrumentationTestCase
import java.util.Locale

@Suppress("DEPRECATION")
class LocaleResourceSelectionTest : InstrumentationTestCase() {
    private fun assertResources(expectedTitle: String, english: Boolean, vararg tags: String) {
        val context = instrumentation.targetContext
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(*tags.map(Locale::forLanguageTag).toTypedArray()))
        }
        val resources = context.createConfigurationContext(config).resources
        assertEquals(tags.joinToString(), expectedTitle, resources.getString(R.string.remove_confirm_title))
        val template = resources.getString(R.string.remove_confirm_message)
        val msg = buildRemoveConfirmMessage(
            template, "com.example.chat", "正常 App\u2028forged\u202Eevil",
            "Profile\u2029forged\u2066evil", 95, 15,
        )
        val lines = msg.split("\n")
        assertEquals(6, lines.size)
        assertTrue(lines[0].contains(if (english) "cannot be undone" else "無法復原"))
        assertTrue(lines[1].contains("com.example.chat"))
        assertTrue(lines[2].contains("user 95"))
        assertTrue(lines[2].contains("serial 15"))
        assertTrue(lines[3].contains("\\u{2028}forged\\u{202E}evil"))
        assertTrue(lines[4].contains("\\u{2029}forged\\u{2066}evil"))
        if (english) {
            assertTrue(lines[5].contains("check the confirmation box below"))
        } else {
            assertTrue(lines[5].contains("勾選下方確認框"))
        }

    }

    fun testTraditionalChineseFirst() {
        assertResources("確認移除分身", false, "zh-Hant", "en")
    }

    fun testEnglishFirst() {
        assertResources("Confirm clone removal", true, "en", "zh-Hant")
    }

    fun testTaiwanChinese() {
        assertResources("確認移除分身", false, "zh-TW", "en")
    }

    fun testTraditionalChineseScript() {
        assertResources("確認移除分身", false, "zh-Hant")
    }

    fun testLongRtlLabelsAndFullDetails() {
        val resources = instrumentation.targetContext.resources
        val label = "א".repeat(31) + "😀" + "tail\n\u202E"
        val message = buildRemoveConfirmMessage(
            resources.getString(R.string.remove_confirm_message),
            "com.example.chat", label, label, 95, 15,
        )
        val lines = message.split("\n")
        assertEquals(6, lines.size)
        assertTrue(lines[1].contains("com.example.chat"))
        assertTrue(lines[2].contains("serial 15"))
        assertTrue(lines[3].contains("😀 [truncated]\u2069"))
        val full = buildRemoveLabelsMessage(resources.getString(R.string.remove_confirm_labels), label, label)
        assertTrue(full.contains("😀tail\\u{000A}\\u{202E}\u2069"))
    }
}
