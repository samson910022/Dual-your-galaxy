// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase E 孤兒條目 UI：orphanPackagesOf 純判定＋三語系資源鍵齊備。
// - orphanPackagesOf：ticks∩catalog 補集；空 catalog 回空（載入中不誤判全孤兒）。
// - R11：計數行用 policy_orphan_pending（孤兒仍在政策草稿中）、確認框用
//   policy_dropped_future（未來式「將捨去」）；policy_dropped 保留給實際捨去結果
//   （見 PolicyActivity.updateCounter／policyEditorConfirmMessage；MainActivity.policyWriteMessage 同口徑）。
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
            assertTrue("$locale missing policy_dropped plural", plurals.contains("policy_dropped"))
            assertTrue("$locale missing policy_orphan_pending plural (R11)", plurals.contains("policy_orphan_pending"))
            assertTrue("$locale missing policy_dropped_future plural (R11)", plurals.contains("policy_dropped_future"))
        }
        // 帶參槽位：孤兒標題／移除按鈕／截斷模板須帶 %1$d；nomatch／toggle 須帶 %1$s。
        for ((locale, map) in stringMaps) {
            assertTrue("$locale orphan header needs %1\$d", map.getValue("policy_editor_orphan_header_format").contains("%1\$d"))
            assertTrue("$locale orphan remove needs %1\$d", map.getValue("policy_editor_orphan_remove_format").contains("%1\$d"))
            assertTrue("$locale trunc needs %1\$d", map.getValue("pre_clear_list_truncated_format").contains("%1\$d"))
            assertTrue("$locale nomatch needs %1\$s", map.getValue("policy_editor_empty_nomatch_format").contains("%1\$s"))
            assertTrue("$locale toggle cd needs %1\$s", map.getValue("policy_editor_toggle_cd_format").contains("%1\$s"))
        }
        // 誠實 TTL 口徑（R08/R09）：60s 僅本地快取 TTL、底層 mMap 可能陳舊、不保證 60s／下次 poll 可見、
        // 重啟 hook 行程才保證（無即時推送）；另須帶 11-20/11-21 非空政策仍遭冷啟動刪除的警告（R01）。
        val zhIntro = stringMaps.getValue("values").getValue("policy_editor_intro")
        assertTrue("zh intro must state local cache TTL", zhIntro.contains("60 秒僅為 hook 行程本地快取 TTL"))
        assertTrue("zh intro must state stale mMap", zhIntro.contains("底層 mMap 可能仍陳舊"))
        assertTrue("zh intro must deny 60s/next-poll guarantee", zhIntro.contains("不保證 60s 內或下一次 poll 看到新集合"))
        assertTrue("zh intro must require hook restart", zhIntro.contains("重啟 hook 行程後才保證讀到新值"))
        assertTrue("zh intro must carry 無即時推送", zhIntro.contains("無即時推送"))
        assertTrue("zh intro must carry non-empty-policy warning", zhIntro.contains("非空不保證安全"))
        val hantIntro = stringMaps.getValue("values-b+zh+Hant").getValue("policy_editor_intro")
        assertEquals("Hant intro must mirror zh", zhIntro, hantIntro)
        val enIntro = stringMaps.getValue("values-en").getValue("policy_editor_intro")
        assertTrue("en intro must state local cache TTL", enIntro.contains("only the hook process’s local cache TTL"))
        assertTrue("en intro must state stale mMap", enIntro.contains("mMap may still be stale"))
        assertTrue("en intro must deny 60s/next-poll guarantee", enIntro.contains("no guarantee of seeing the new set within 60s or on the next poll"))
        assertTrue("en intro must require hook restart", enIntro.contains("only restarting the hook process guarantees"))
        assertTrue("en intro must carry no push", enIntro.contains("no cross-process push"))
        assertTrue("en intro must carry non-empty-policy warning", enIntro.contains("a non-empty policy is not guaranteed safe"))
        // write_message 為 plurals：兩 quantity 皆含誠實 TTL 口徑＋清除後 30 分鐘有界降解（無立即停止保證）。
        for (locale in locales) {
            val text = pluralText(resFile("$locale/strings.xml"), "policy_write_message")
            if (locale == "values-en") {
                assertTrue("$locale write_message must state local cache TTL", text.contains("only the hook process’s local cache TTL"))
                assertTrue("$locale write_message must deny guarantee", text.contains("no guarantee of seeing the new set within 60s or on the next poll"))
                assertTrue("$locale write_message must carry 30-minute bounded degradation", text.contains("up to 30 minutes"))
                assertTrue("$locale write_message must deny immediate stop", text.contains("no immediate stop is guaranteed"))
            } else {
                assertTrue("$locale write_message must state local cache TTL", text.contains("60 秒僅為 hook 行程本地快取 TTL"))
                assertTrue("$locale write_message must deny guarantee", text.contains("不保證 60s 內或下一次 poll 看到新集合"))
                assertTrue("$locale write_message must carry 30-minute bounded degradation", text.contains("最長 30 分鐘"))
                assertTrue("$locale write_message must deny immediate stop", text.contains("不保證立即停止放寬"))
            }
        }
        // R11 新鍵：三語系齊備、保留 %1$d 槽位、時態語意正確（孤兒仍在草稿／confirm 為未來式／結果仍為過去式）。
        for (locale in locales) {
            val orphan = pluralText(resFile("$locale/strings.xml"), "policy_orphan_pending")
            val future = pluralText(resFile("$locale/strings.xml"), "policy_dropped_future")
            val dropped = pluralText(resFile("$locale/strings.xml"), "policy_dropped")
            assertTrue("$locale policy_orphan_pending needs %1\$d", orphan.contains("%1\$d"))
            assertTrue("$locale policy_dropped_future needs %1\$d", future.contains("%1\$d"))
            assertTrue("$locale policy_dropped keeps %1\$d", dropped.contains("%1\$d"))
            if (locale == "values-en") {
                assertTrue("$locale orphan must say still in policy draft", orphan.contains("still in the policy draft"))
                assertTrue("$locale dropped-future must be future tense", future.contains("Will drop"))
                assertTrue("$locale dropped result stays past tense", dropped.contains("Dropped"))
            } else {
                assertTrue("$locale orphan must say 仍在政策草稿中", orphan.contains("仍在政策草稿中"))
                assertTrue("$locale dropped-future must say 將捨去", future.contains("將捨去"))
                assertTrue("$locale dropped result stays 已捨去", dropped.contains("已捨去"))
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
