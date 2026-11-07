// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase C：About 對話框 channel-aware 副標題（卡片＋About 單一來源 hookSubtitleResFor）。
// hookSubtitleResFor 為 MainActivity.kt 底部的純 shared seam（JVM 可測）：
// 真可寫走 _ops（特權通道直寫、無需 Hook），其餘走白名單原文案。此檔鎖定：
// (a) helper 直映射；(b) 經真實 writeBlockReason 的端到端（可寫→_ops，被擋全集→base）；
// (c) 三語系資源鍵齊備＋_ops 無需 Hook 語意＋about 格式槽；(d) 來源形狀（About %5$s＋
// 卡片同源 helper，非裸 base 鍵）；(e) 欄位初值仍為 base 鍵（首幀前 fail-closed）。
package com.galaxydual.plus

import com.galaxydual.core.Privilege
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookSubtitleResTest {

    // (a) helper 直映射：真可寫→_ops，其餘→base。
    @Test
    fun hookSubtitleResFor_mapsWritableToOps() {
        assertEquals(R.string.hook_mode_subtitle_ops, hookSubtitleResFor(true))
        assertEquals(R.string.hook_mode_subtitle, hookSubtitleResFor(false))
    }

    // (b) 端到端：可寫 UiState（mirror ReflectionSeamTest:110-117）經真實
    // writeBlockReason 回 null→_ops；被擋全集（mirror ReflectionSeamTest:74-106）
    // 回非 null→base。
    @Test
    fun hookSubtitle_endToEndViaWriteBlockReason() {
        val writable = UiState(
            channel = ChannelPreference.ROOT,
            resolvedGateway = ResolvedChannel.ROOT,
            execIdentity = Privilege.ROOT,
            privilege = Privilege.ROOT,
            writeReady = true,
            pmReadable = true,
        )
        assertNull("fully ready state must yield no BlockReason", writeBlockReason(writable))
        assertEquals(
            "writable state must map to _ops subtitle",
            R.string.hook_mode_subtitle_ops,
            hookSubtitleResFor(writeBlockReason(writable) == null),
        )

        val blocked = listOf(
            "default NoOp fallback" to UiState(),
            "explicit channel unproven" to UiState(
                channel = ChannelPreference.ROOT,
                resolvedGateway = ResolvedChannel.ROOT,
                execIdentity = Privilege.ROOT,
                writeReady = false,
            ),
            "pm unreadable despite ready channel" to UiState(
                channel = ChannelPreference.ROOT,
                resolvedGateway = ResolvedChannel.ROOT,
                execIdentity = Privilege.ROOT,
                privilege = Privilege.ROOT,
                writeReady = true,
                pmReadable = false,
            ),
            "lsposed placeholder short-circuits everything" to UiState(
                resolvedGateway = ResolvedChannel.LSPOSED_PLACEHOLDER,
            ),
            "auto degrade explanation wins while unready" to UiState(
                channel = ChannelPreference.AUTO,
                writeReady = false,
                degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU),
            ),
            "ready channel but no privilege" to UiState(
                channel = ChannelPreference.ROOT,
                resolvedGateway = ResolvedChannel.ROOT,
                execIdentity = Privilege.ROOT,
                privilege = Privilege.NONE,
                writeReady = true,
                pmReadable = true,
            ),
        )
        assertEquals("blocked table must cover all 6 writeBlockReason branches", 6, blocked.size)
        for ((name, s) in blocked) {
            assertNotNull("blocked state [$name] must yield a BlockReason", writeBlockReason(s))
            assertEquals(
                "blocked state [$name] must map to base subtitle",
                R.string.hook_mode_subtitle,
                hookSubtitleResFor(writeBlockReason(s) == null),
            )
        }
    }

    // (c) 語系 XML：兩鍵於三語系齊備非空；_ops 帶無需 Hook 語意；
    // settings_about_format 保留 %1$s..%6$s。
    @Test
    fun strings_subtitleKeys_threeLocalesAndAboutSlots() {
        val locales = listOf("values", "values-en", "values-b+zh+Hant")
        val maps = locales.associateWith { stringMap(resFile("$it/strings.xml")) }
        for ((locale, map) in maps) {
            assertTrue("$locale missing hook_mode_subtitle", !map["hook_mode_subtitle"].isNullOrEmpty())
            assertTrue("$locale missing hook_mode_subtitle_ops", !map["hook_mode_subtitle_ops"].isNullOrEmpty())
            assertTrue("$locale missing settings_about_format", !map["settings_about_format"].isNullOrEmpty())
        }
        assertTrue(
            "en _ops must carry no-Hook semantics",
            maps.getValue("values-en").getValue("hook_mode_subtitle_ops").contains("no Hook required"),
        )
        for (locale in listOf("values", "values-b+zh+Hant")) {
            assertTrue(
                "$locale _ops must carry 無需 Hook semantics",
                maps.getValue(locale).getValue("hook_mode_subtitle_ops").contains("無需 Hook"),
            )
        }
        for ((locale, map) in maps) {
            val fmt = map.getValue("settings_about_format")
            for (slot in listOf("%1\$s", "%2\$s", "%3\$s", "%4\$s", "%5\$s", "%6\$s")) {
                assertTrue("$locale settings_about_format must keep $slot", fmt.contains(slot))
            }
        }
    }

    // (d) 來源形狀：About %5$s 經 hookSubtitleResFor（非裸 base 鍵），卡片同源。
    @Test
    fun sourceShape_aboutAndCard_shareHelper() {
        val src = activityFile().readText()
        val about = functionBlock(src, "private fun showSettingsDialog()")
        assertTrue("showSettingsDialog must reference hookSubtitleResFor(", about.contains("hookSubtitleResFor("))
        assertFalse(
            "showSettingsDialog %5\$s must not use a bare base key",
            bareBaseKey.containsMatchIn(about),
        )
        assertTrue(
            "card must share the same helper (single-source)",
            src.contains("hookDescView.text = getString(hookSubtitleResFor("),
        )
    }

    // (e) 欄位初值仍為 base 鍵（首幀前 fail-closed），非 _ops。
    @Test
    fun sourceShape_fieldInit_staysBaseKey() {
        val src = activityFile().readText()
        val start = src.indexOf("hookDescView = TextView")
        assertTrue("hookDescView init block must exist", start >= 0)
        val init = src.substring(start, minOf(start + 600, src.length))
        assertTrue(
            "hookDescView init must stay on the base key (fail-closed pre-first-render)",
            bareBaseKey.containsMatchIn(init),
        )
        assertFalse(
            "hookDescView init must not default to _ops",
            init.contains("hook_mode_subtitle_ops"),
        )
    }

    // ---- 小工具（測試專用，不進生產） ----

    private val bareBaseKey = Regex("R\\.string\\.hook_mode_subtitle(?!_ops)")

    private fun activityFile(): File {
        val candidates = listOf(
            File("src/main/java/com/galaxydual/plus/MainActivity.kt"),
            File("app/src/main/java/com/galaxydual/plus/MainActivity.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing MainActivity.kt (tried ${candidates.map { it.path }})")
    }

    private fun functionBlock(src: String, signature: String): String {
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

    private fun resFile(rel: String): File {
        val candidates = listOf(File("src/main/res", rel), File("app/src/main/res", rel))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing res file: $rel (tried ${candidates.map { it.path }})")
    }

    private fun stringMap(f: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            val name = n.attributes.getNamedItem("name").nodeValue
            out[name] = n.textContent.trim()
        }
        return out
    }
}
