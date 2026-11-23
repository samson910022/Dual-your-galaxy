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

    fun testExperimentalNoticeKeepsVersionPlaceholderInAllLocales() {
        // PR #74 R7b-3: the version-tagged experimental/BBC-not-verified label
        // must resolve non-empty and keep its %1$s placeholder in the default
        // (values), English (values-en) and Traditional Chinese (values-b+zh+Hant)
        // resources. "fr" has no matching locale dir, so it falls back to values/.
        for (tags in arrayOf(
            arrayOf("fr"),
            arrayOf("en"),
            arrayOf("zh-Hant"),
        )) {
            val context = instrumentation.targetContext
            val config = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(*tags.map(Locale::forLanguageTag).toTypedArray()))
            }
            val resources = context.createConfigurationContext(config).resources
            val notice = resources.getString(R.string.settings_bbc_injection_experimental_notice)
            assertTrue("${tags.joinToString()} notice must be non-empty", notice.isNotEmpty())
            assertTrue("${tags.joinToString()} notice must keep %1\$s", notice.contains("%1\$s"))
            // Honesty guard: a softened/verified claim with the placeholder intact must fail.
            when (tags[0]) {
                "en" -> {
                    assertTrue(notice.contains("Experimental"))
                    assertTrue(notice.contains("not device-verified"))
                    assertTrue(notice.contains("No general effectiveness or data-safety guarantee"))
                }
                // "fr" resolves the default values/ (zh-Hant text); both share these tokens.
                "fr", "zh-Hant" -> {
                    assertTrue(notice.contains("實驗性"))
                    assertTrue(notice.contains("實機確認"))
                    assertTrue(notice.contains("不保證"))
                }
            }
        }
    }
}
