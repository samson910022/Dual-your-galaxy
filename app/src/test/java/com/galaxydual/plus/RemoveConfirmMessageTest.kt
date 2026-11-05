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
}
