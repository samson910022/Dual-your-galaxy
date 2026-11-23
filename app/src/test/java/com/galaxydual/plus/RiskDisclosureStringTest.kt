// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-1 (M1): JVM 資源鎖定 — 五個風險揭露字串在三語系齊備、
// 繁中預設與 b+zh+Hant 逐值相等 (RemoveConfirmMessageTest 另鎖整檔位元一致)，
// 完整揭露含強制項目、不得出現安全宣稱，且誠實揭露 Hook 可能早於首次警告生效。
// XML 解析方式 mirrors UiSemanticsP28Test.stringMap (DocumentBuilderFactory)。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class RiskDisclosureStringTest {
    private val locales = listOf("values", "values-en", "values-b+zh+Hant")
    private val newKeys = listOf(
        "risk_notice_short",
        "risk_details_entry",
        "risk_details_title",
        "risk_ack_positive",
        "risk_details_full",
    )

    private val maps: Map<String, Map<String, String>> by lazy { locales.associateWith { stringMap(it) } }

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
    fun allFiveRiskKeysPresentNonEmptyAndKeySetEqualAcrossLocales() {
        val riskKeySets = mutableListOf<Set<String>>()
        for (locale in locales) {
            val m = maps.getValue(locale)
            for (k in newKeys) {
                assertTrue("$locale missing $k", m.containsKey(k))
                assertTrue("$locale empty $k", m.getValue(k).isNotBlank())
            }
            riskKeySets.add(m.keys.filter { it.startsWith("risk_") }.toSet())
        }
        assertEquals("risk_* key set en must equal default", riskKeySets[0], riskKeySets[1])
        assertEquals("risk_* key set hant must equal default", riskKeySets[0], riskKeySets[2])
        assertTrue("default must contain exactly the mandated new keys", newKeys.all { it in riskKeySets[0] })
    }

    @Test
    fun defaultAndHantRiskTextsAreIdentical() {
        val def = maps.getValue("values")
        val hant = maps.getValue("values-b+zh+Hant")
        for (k in newKeys) {
            assertEquals("Hant mismatch for $k", def.getValue(k), hant.getValue(k))
        }
    }

    @Test
    fun shortRiskStringsMatchMandatedText() {
        val expectedZh = mapOf(
            "risk_notice_short" to "⚠️ 實驗性分身功能：無法保證資料安全。Samsung 可能自動卸載分身並永久刪除分身內資料；今天正常不代表未來安全。請先備份重要資料，不要把唯一重要資料只存在分身。",
            "risk_details_entry" to "詳細風險 ›",
            "risk_details_title" to "分身資料風險（完整說明）",
            "risk_ack_positive" to "我已了解風險，繼續",
        )
        val expectedEn = mapOf(
            "risk_notice_short" to "⚠️ Experimental clone feature: data safety cannot be guaranteed. Samsung may automatically uninstall clones and permanently delete their data; working today does not mean safe tomorrow. Back up important data first; do not keep your only important data solely in a clone.",
            "risk_details_entry" to "Risk details ›",
            "risk_details_title" to "Clone data risk (full details)",
            "risk_ack_positive" to "I understand the risk, continue",
        )
        for (locale in listOf("values", "values-b+zh+Hant")) {
            for ((k, v) in expectedZh) assertEquals("$locale $k", v, maps.getValue(locale).getValue(k))
        }
        for ((k, v) in expectedEn) assertEquals("values-en $k", v, maps.getValue("values-en").getValue(k))
    }

    @Test
    fun riskDetailsFull_zhCoversMandatedDisclosure() {
        val zh = maps.getValue("values").getValue("risk_details_full")
        val required = listOf(
            "永久刪除",
            "無法還原",
            "根因仍未完全證實",
            "Root／Shizuku",
            "Shizuku／ADB shell",
            "Keystore",
            "已安裝",
            "政策已存",
            "BBC ON/OFF",
            "不代表 Hook 在生效前已取得你的同意",
        )
        for (token in required) assertTrue("zh full missing $token", zh.contains(token))
        assertTrue("zh full must name BBC_AVAILABLE_APPS_INJECTION", zh.contains("BBC_AVAILABLE_APPS_INJECTION"))
        assertTrue("zh must use 請特別注意 (traditional 別)", zh.contains("請特別注意"))
        assertFalse("zh must not claim pre-activation consent", zh.contains("已於生效前取得同意"))
    }

    @Test
    fun riskDetailsFull_enCoversMandatedDisclosure() {
        val en = maps.getValue("values-en").getValue("risk_details_full")
        val required = listOf(
            "permanently delete",
            "Root/Shizuku",
            "other profiles",
            "Keystore",
            "BBC ON/OFF",
            "BEFORE this App",
            "do NOT mean",
            "consent",
        )
        for (token in required) assertTrue("en full missing $token", en.contains(token))
        assertTrue("en full must state restoration is impossible", en.contains("cannot restore") || en.contains("cannot be fully restored"))
        assertTrue("en full must name BBC_AVAILABLE_APPS_INJECTION", en.contains("BBC_AVAILABLE_APPS_INJECTION"))
        assertFalse("en must not claim consent before activation", en.contains("obtained consent before activation"))
    }

    @Test
    fun riskDetailsFull_noUnsafeClaimsAndRequiresNegation() {
        val zh = maps.getValue("values").getValue("risk_details_full")
        val en = maps.getValue("values-en").getValue("risk_details_full")
        assertTrue("zh must state 無法保證資料安全", zh.contains("無法保證資料安全"))
        assertTrue("en must state data safety cannot be guaranteed", en.contains("data safety cannot be guaranteed"))

        // 保證資料安全 是 無法保證資料安全 的子字串；先移除強制否定句再查不安全宣稱。
        val zhWithoutRequiredNegation = zh.replace("無法保證資料安全", "")
        for (claim in listOf("零風險", "保證可還原", "保證資料安全", "完全安全", "絕對安全", "安全無虞")) {
            assertFalse("zh must not claim $claim", zhWithoutRequiredNegation.contains(claim))
        }
        for (claim in listOf("zero risk", "guaranteed restorable", "completely safe", "absolutely safe", "data is safe")) {
            assertFalse("en must not claim $claim", en.contains(claim, ignoreCase = true))
        }
    }

    @Test
    fun riskStrings_zhContainNoSimplified须() {
        for (locale in listOf("values", "values-b+zh+Hant")) {
            val m = maps.getValue(locale)
            for (k in newKeys) {
                assertFalse("$locale $k contains simplified 须", m.getValue(k).contains("须"))
            }
        }
    }
}
