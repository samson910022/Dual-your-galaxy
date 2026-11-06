// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase A P1-2：移除確認框必須顯示完整 package + label + user/serial
//（本地確認畫面 by-design 例外，不經 sanitizeForDisplay/redact）。
// 鎖定 buildRemoveConfirmMessage 純函數：label 成功（真實顯示名）與失敗
//（fallback 用 pkg）兩種都含完整 pkg + user/serial。
package com.galaxydual.plus

import org.junit.Assert.assertTrue
import org.junit.Test

class RemoveConfirmMessageTest {
    private val pkg = "com.example.chat"
    private val profileLabel = "DualApp"
    private val dualId = 95
    private val serial = 15

    @Test
    fun labelResolved_showsFullPkgAndUserSerial() {
        val msg = buildRemoveConfirmMessage(
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
    fun newlineInput_doesNotBreakDialogLines() {
        // m3 行注入：欄位內換行經 sanitizeUserEcho 壓空白，結構換行只剩模板自帶三行。
        val evilPkg = "com.example.chat\n偽造行"
        val msg = buildRemoveConfirmMessage(
            pkg = evilPkg,
            appLabel = "聊天應用\n偽造行",
            profileLabel = "DualApp\n偽造行",
            dualId = dualId,
            serial = serial,
        )
        assertTrue("field newline must be neutralized, got: $msg", !msg.contains(evilPkg))
        assertTrue("field newline must collapse to space, got: $msg", msg.contains("偽造行"))
        // 結構換行：模板自帶兩處 \n（三行），欄位內不得再斷行。
        val lines = msg.split("\n")
        assertTrue("dialog must stay 3 structural lines, got ${lines.size}: $msg", lines.size == 3)
        assertTrue("raw pkg must not be redacted, got: $msg", !msg.contains("<pkg>"))
    }
}
