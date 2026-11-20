// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase E 孤兒條目 UI：orphanPackagesOf 純判定＋三語系資源鍵齊備。
// - orphanPackagesOf：ticks∩catalog 補集；空 catalog 回空（載入中不誤判全孤兒）。
// - 計數行／確認框複用 policy_dropped 後綴（見 PolicyActivity.updateCounter／
//   policyEditorConfirmMessage；MainActivity.policyWriteMessage 同口徑）。
// - catalog 缺失 ticks 顯示「不在來源清單（N）」區段＋一鍵移除（見 PolicyActivity.renderList 孤兒區段）。
package com.galaxydual.plus

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEditorOrphanTest {

    @Test
    fun orphan_emptyCatalog_returnsEmpty_noFalseOrphan() {
        assertEquals(emptySet<String>(), orphanPackagesOf(setOf("com.a.app"), emptySet()))
        assertEquals(emptySet<String>(), orphanPackagesOf(emptySet(), setOf("com.a.app")))
        assertEquals(emptySet<String>(), orphanPackagesOf(emptySet(), emptySet()))
    }

    @Test
    fun orphan_allPresent_returnsEmpty() {
        val catalog = setOf("com.a.app", "com.b.app")
        assertEquals(emptySet<String>(), orphanPackagesOf(setOf("com.a.app", "com.b.app"), catalog))
    }

    @Test
    fun orphan_missingSubset_returned() {
        val catalog = setOf("com.a.app")
        assertEquals(setOf("com.b.app"), orphanPackagesOf(setOf("com.a.app", "com.b.app"), catalog))
    }

    @Test
    fun orphan_allMissing_allReturned() {
        val catalog = setOf("com.x.app")
        assertEquals(
            setOf("com.a.app", "com.b.app"),
            orphanPackagesOf(setOf("com.a.app", "com.b.app"), catalog),
        )
    }

    @Test
    fun orphan_duplicateTicks_deduped() {
        // Set 語義：重複 ticks 不重複計數。
        val catalog = setOf("com.a.app")
        assertEquals(setOf("com.b.app"), orphanPackagesOf(setOf("com.b.app", "com.b.app"), catalog))
    }

    // 三語系資源鍵齊備：計數行 plurals＋帶參 strings＋孤兒區段＋截斷模板＋TTL 文案。
    @Test
    fun strings_phaseEKeys_threeLocales() {
        val locales = listOf("values", "values-en", "values-b+zh+Hant")
        val stringMaps = locales.associateWith { stringMap(resFile("$it/strings.xml")) }
        val pluralMaps = locales.associateWith { pluralNames(resFile("$it/strings.xml")) }
        val requiredStrings = listOf(
            "policy_editor_back",
            "policy_editor_title",
            "policy_editor_show_system",
            "policy_editor_loading",
            "policy_editor_empty_catalog",
            "policy_editor_empty_filtered",
            "policy_editor_empty_nomatch_format",
            "policy_editor_toggle_cd_format",
            "policy_editor_hint_capped_format",
            "policy_editor_hint_format",
            "policy_editor_write_draft_format",
            "policy_editor_orphan_header_format",
            "policy_editor_orphan_remove_format",
            "policy_editor_orphan_item_cd_format",
            "pre_clear_list_truncated_format",
            "pre_clear_uninstall_verify_truncated_suffix_format",
        )
        for (k in requiredStrings) {
            for ((locale, map) in stringMaps) {
                assertTrue("$locale missing/empty $k", !map[k].isNullOrEmpty())
            }
        }
        for ((locale, plurals) in pluralMaps) {
            assertTrue("$locale missing policy_editor_counter plural", plurals.contains("policy_editor_counter"))
        }
        // 帶參槽位：孤兒標題／移除按鈕／截斷模板須帶 %1$d；nomatch／toggle 須帶 %1$s。
        for ((locale, map) in stringMaps) {
            assertTrue("$locale orphan header needs %1\$d", map.getValue("policy_editor_orphan_header_format").contains("%1\$d"))
            assertTrue("$locale orphan remove needs %1\$d", map.getValue("policy_editor_orphan_remove_format").contains("%1\$d"))
            assertTrue("$locale trunc needs %1\$d", map.getValue("pre_clear_list_truncated_format").contains("%1\$d"))
            assertTrue("$locale nomatch needs %1\$s", map.getValue("policy_editor_empty_nomatch_format").contains("%1\$s"))
            assertTrue("$locale toggle cd needs %1\$s", map.getValue("policy_editor_toggle_cd_format").contains("%1\$s"))
        }
        // TTL 口徑：三語系 intro／write_message 皆須含 Phase E 約定文案（無即時推送／no push；
        // zh 含「最快約60秒」＋「重啟後再看」，en 含 earliest ~60s＋reboot and check again）。
        val zhIntro = stringMaps.getValue("values").getValue("policy_editor_intro")
        assertTrue("zh intro must carry 最快約60秒", zhIntro.contains("最快約60秒"))
        assertTrue("zh intro must carry 重啟後再看", zhIntro.contains("重啟後再看"))
        assertTrue("zh intro must carry 無即時推送", zhIntro.contains("無即時推送"))
        val hantIntro = stringMaps.getValue("values-b+zh+Hant").getValue("policy_editor_intro")
        assertEquals("Hant intro must mirror zh", zhIntro, hantIntro)
        val enIntro = stringMaps.getValue("values-en").getValue("policy_editor_intro")
        assertTrue("en intro must carry earliest ~60s", enIntro.contains("earliest ~60s"))
        assertTrue("en intro must carry no push", enIntro.contains("no push"))
        // write_message 為 plurals：直接解析 XML 取文本，確認兩 quantity 皆含 TTL 口徑。
        for (locale in locales) {
            val text = pluralText(resFile("$locale/strings.xml"), "policy_write_message")
            if (locale == "values-en") {
                assertTrue("$locale write_message must carry earliest ~60s", text.contains("earliest ~60s"))
                assertTrue("$locale write_message must carry no push", text.contains("no push"))
            } else {
                assertTrue("$locale write_message must carry 最快約60秒", text.contains("最快約60秒"))
                assertTrue("$locale write_message must carry 無即時推送", text.contains("無即時推送"))
            }
        }
    }

    private fun resFile(rel: String): File {
        val candidates = listOf(File("src/main/res", rel), File("app/src/main/res", rel))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing res file: $rel")
    }

    private fun stringMap(f: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            out[n.attributes.getNamedItem("name").nodeValue] = n.textContent.trim()
        }
        return out
    }

    private fun pluralNames(f: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableSetOf<String>()
        val nodes = doc.getElementsByTagName("plurals")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            out.add(n.attributes.getNamedItem("name").nodeValue)
        }
        return out
    }

    private fun pluralText(f: File, name: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("plurals")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.attributes.getNamedItem("name").nodeValue == name) return n.textContent
        }
        throw AssertionError("missing plurals $name in ${f.path}")
    }
}
