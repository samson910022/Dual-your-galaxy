// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase A P1-2：移除確認框必須顯示完整 package + label + user/serial
//（本地確認畫面 by-design 例外，不經 sanitizeForDisplay/redact）。
// 鎖定 buildRemoveConfirmMessage 純函數：label 成功（真實顯示名）與失敗
//（fallback 用 pkg）兩種都含完整 pkg + user/serial。
package com.galaxydual.plus

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class RemoveConfirmMessageTest {
    private val pkg = "com.example.chat"
    private val profileLabel = "DualApp"
    private val dualId = 95
    private val serial = 15
    private fun template(directory: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val node = strings.item(i)
            if (node.attributes.getNamedItem("name").nodeValue == "remove_confirm_message") {
                return node.textContent.replace("\\n", "\n")
            }
        }
        error("missing remove confirmation in $directory")
    }

    @Test
    fun labelResolved_showsFullPkgAndUserSerial() {
        val msg = buildRemoveConfirmMessage(
            template = template("values"),
            pkg = pkg,
            appLabel = "聊天應用",
            profileLabel = profileLabel,
            dualId = dualId,
            serial = serial,
        )
        assertTrue("must show full pkg, got: $msg", msg.contains(pkg))
        assertTrue("must show app label, got: $msg", msg.contains("聊天應用"))
        assertTrue("must show profile label, got: $msg", msg.contains(profileLabel))
        assertTrue("must show user id, got: $msg", msg.contains("user $dualId"))
        assertTrue("must show serial, got: $msg", msg.contains("$serial"))
        // 原文顯示：完整包名不得被遮蔽為 <pkg>。
        assertTrue("raw pkg must not be redacted, got: $msg", !msg.contains("<pkg>"))
    }

    @Test
    fun labelFallback_usesPkgAndStillShowsFullPkgAndUserSerial() {
        // resolveAppLabel 失敗回退：appLabel == pkg，仍須含完整 pkg + user/serial。
        val msg = buildRemoveConfirmMessage(
            template = template("values"),
            pkg = pkg,
            appLabel = pkg,
            profileLabel = profileLabel,
            dualId = dualId,
            serial = serial,
        )
        assertTrue("fallback must still show full pkg, got: $msg", msg.contains(pkg))
        assertTrue("fallback must show user id, got: $msg", msg.contains("user $dualId"))
        assertTrue("fallback must show serial, got: $msg", msg.contains("$serial"))
        assertTrue("fallback must show profile label, got: $msg", msg.contains(profileLabel))
        assertTrue("raw pkg must not be redacted, got: $msg", !msg.contains("<pkg>"))
    }

    @Test
    fun controlsAreVisibleDistinctAndRtlIsIsolated() {
        val field = sanitizeRemoveConfirmField("אב\n\u202E\\u{000A}")
        assertEquals("\u2068אב\\u{000A}\\u{202E}\\\\u{000A}\u2069", field)
        assertEquals("x\u2028\u202Ey", sanitizeUserEcho("x\u2028\u202Ey"))
    }

    @Test
    fun longLabelsAreMarkedAndFullLabelsRemainAvailable() {
        val label = "a".repeat(31) + "😀" + "end"
        val msg = buildRemoveConfirmMessage(template("values-en"), pkg, label, label, dualId, serial)
        val lines = msg.split("\n")
        assertTrue(lines[0].contains("cannot be undone"))
        assertTrue(lines[1].contains(pkg))
        assertTrue(lines[2].contains("user 95, serial 15"))
        assertTrue(lines[3].contains("😀 [truncated]"))
        assertTrue(!lines[3].contains("end"))
        assertEquals(6, lines.size)
        assertTrue(lines[5].contains("check the confirmation box below"))
        val full = buildRemoveLabelsMessage("%1\$s | %2\$s", label, label)
        assertTrue(full.contains(label))
        assertTrue(!full.contains(" [truncated]"))
    }

    @Test
    fun hostileLabelsHaveBoundedEscapedOutput() {
        val controls = "\u202E".repeat(100_000)
        val summary = sanitizeRemoveConfirmField(controls, 32, 96)
        assertTrue(summary.length <= 96)
        assertTrue(summary.endsWith(" [truncated]\u2069"))
        val details = buildRemoveLabelsMessage("%1\$s | %2\$s", controls, "😀".repeat(100_000))
        assertTrue(details.length <= 512 * 2 + 3)
        assertTrue(details.contains(" [truncated]\u2069"))
        assertEquals("\u2068\\u{000A}\\\\\u2069", sanitizeRemoveConfirmField("\n\\", 256, 512))
    }

    @Test
    fun outputBudgetOnlyTruncatesWhenEscapedValueDoesNotFit() {
        val marker = " [truncated]"
        val budget = marker.length + 2
        assertEquals("\u2068${"a".repeat(budget - 2)}\u2069", sanitizeRemoveConfirmField("a".repeat(budget - 2), null, budget))
        assertEquals("\u2068$marker\u2069", sanitizeRemoveConfirmField("a".repeat(budget - 1), null, budget))
        assertEquals("\u2068\\u{000A}😀\u2069", sanitizeRemoveConfirmField("\n😀", null, budget))
        assertEquals("\u2068$marker\u2069", sanitizeRemoveConfirmField("\n😀", 1, budget))
    }

    @Test
    fun shapingAndLiteralTruncationTextRemainDistinct() {
        assertEquals("\u2068क्\u200Dष ن\u200Cا … \\[truncated]\u2069", sanitizeRemoveConfirmField("क्\u200Dष ن\u200Cا … [truncated]"))
        assertTrue(sanitizeRemoveConfirmField("abc", 2, 96).endsWith(" [truncated]\u2069"))
        assertEquals("\u2068\\u{202E}\\u{2066}\u2069", sanitizeRemoveConfirmField("\u202E\u2066"))
    }

    @Test
    fun confirmationRequiresAcknowledgementAndIdleState() {
        assertTrue(!canConfirmRemove(false, false))
        assertTrue(!canConfirmRemove(true, false))
        assertTrue(!canConfirmRemove(true, true))
        assertTrue(canConfirmRemove(false, true))
    }

    @Test
    fun localizedMessagesPreserveIdentityAndNeutralizeInjection() {
        for (directory in listOf("values", "values-en", "values-b+zh+Hant")) {
            val msg = buildRemoveConfirmMessage(template(directory), pkg, "App\nforged", "אב\u202Eforged", dualId, serial)
            val lines = msg.split("\n")
            assertEquals(directory, 6, lines.size)
            assertTrue(lines[1].contains(pkg))
            assertTrue(lines[2].contains("user 95"))
            assertTrue(lines[2].contains("serial 15"))
            assertTrue(lines[3].contains("App\\u{000A}forged"))
            assertTrue(lines[4].contains("\\u{202E}forged"))
            // D 操作指示：末行須提示需勾選下方確認框才啟用移除（中英＋繁中同步）。
            if (directory == "values-en") {
                assertTrue(lines[5].contains("check the confirmation box below"))
                assertTrue(lines[5].contains("enable Remove"))
            } else {
                assertTrue(lines[5].contains("勾選下方確認框"))
                assertTrue(lines[5].contains("確認移除"))
            }
        }
    }

    @Test
    fun traditionalChineseResources_matchCompleteDefault() {
        val default = File("src/main/res/values/strings.xml").readText()
        for (directory in listOf("values-b+zh+Hant")) {
            org.junit.Assert.assertEquals(default, File("src/main/res/$directory/strings.xml").readText())
        }
    }
}
