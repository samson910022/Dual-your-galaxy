// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Issue #73（BBC6）：清除政策前雙開提醒——純函數指令建構／輸出解析 JVM 測試。
// 覆蓋 buildDualProbeCommand / parseDualProbeOutput / buildDualUninstallCommand
// （MAX_DUAL_PROBE_PKGS 上限箝制、排序去重、shell 安全 token 內插、
// 解析器僅採信 "INST <pkg>" 行並去重）；不觸碰 Android runtime／su／pm／裝置。
// R02：probe 指令改為 `pm list packages --user <uid>` + PROBE_RC/INST/CLEAN/FAIL
// markers；R05/NEW-1：uid 由 caller 顯式傳入；F4：token 走 isValidShellPackage。
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreClearWarningTest {

    // 單一測試覆蓋三個純 seam（指令建構×2、解析器×1）與上限箝制。
    @Test
    fun preClearProbeUninstallCommandsAndParser() {
        // --- probe 指令：排序＋去重＋以空格 join；pm list packages --user <uid> ---
        val cmd = buildDualProbeCommand(listOf("com.b.app", "com.a.app", "com.a.app"), 95)
        assertTrue(cmd.startsWith("_PL=\$(pm list packages --user 95); _RC=\$?; "))
        assertTrue(cmd.contains("echo \"PROBE_RC=\$_RC\""))
        assertTrue("for pkg in com.a.app com.b.app; do " in cmd)
        // R02：三選一 marker 必須齊備；不再用 2>&1 / package:* 判定。
        assertTrue(cmd.contains("echo \"FAIL \$pkg\""))
        assertTrue(cmd.contains("echo \"INST \$pkg\""))
        assertTrue(cmd.contains("echo \"CLEAN \$pkg\""))
        assertFalse("must not fold pm errors into parsed stdout", cmd.contains("2>&1"))
        // Phase1 review(MEDIUM): CR normalize once, use for BOTH _BAD check and grep.
        assertTrue("pm output must be CR-normalized", cmd.contains("tr -d '\\r'"))
        assertTrue("grep must match normalized copy", cmd.contains("_PN\" | grep -Fxq"))

        // --- 上限箝制：超過 MAX_DUAL_PROBE_PKGS 只保留前 N（排序後） ---
        val big = (1..MAX_DUAL_PROBE_PKGS + 5).map { "com.example.p$it" }
        val cappedPkgs = buildDualProbeCommand(big, 95)
            .substringAfter("for pkg in ")
            .substringBefore("; do")
            .split(" ")
        assertEquals(MAX_DUAL_PROBE_PKGS, cappedPkgs.size)

        // --- 解析器：僅採信 INST 行；pm list 回顯（package: 路徑）不列入 ---
        assertEquals(
            listOf("com.b.app", "com.a.app"),
            parseDualProbeOutput("INST com.b.app\nINST com.a.app\nINST com.b.app\n"),
        )
        assertEquals(
            emptyList<String>(),
            parseDualProbeOutput(
                "PROBE_RC=0\n" +
                    "package:/data/app/com.a.app/base.apk\n" +
                    "CLEAN com.a.app\n",
            ),
        )
        // 截斷尾行／雜訊行不誤判；空白與空 pkg 丟棄。
        assertEquals(
            listOf("com.a.app"),
            parseDualProbeOutput("random noise\nINST com.a.app\nINST \nINST\n"),
        )

        // --- marker parser：INST/CLEAN/FAIL 三類 + PROBE_RC ---
        val markers = parseDualProbeMarkers(
            "PROBE_RC=0\nINST com.a.app\nCLEAN com.b.app\nFAIL com.c.app\n",
        )
        assertEquals(setOf("com.a.app"), markers.installed)
        assertEquals(setOf("com.b.app"), markers.clean)
        assertEquals(setOf("com.c.app"), markers.failed)
        assertEquals(0, markers.probeRc)

        // --- uninstall 指令：同款 for 迴圈，逐個 pm uninstall --user <uid> ---
        val uninstall = buildDualUninstallCommand(listOf("com.b.app", "com.a.app", "com.a.app"), 95)
        assertEquals(
            "for pkg in com.a.app com.b.app; do pm uninstall --user 95 \"\$pkg\"; done",
            uninstall,
        )
    }

    // R05/NEW-1：verified uid（可與 95 不同）必須出現在兩個 builder 中。
    @Test
    fun builders_useExplicitVerifiedUserId_notHardcoded95() {
        val probe = buildDualProbeCommand(listOf("com.a.app"), 101)
        assertTrue(probe.contains("--user 101"))
        assertFalse(probe.contains("--user 95"))
        val uninstall = buildDualUninstallCommand(listOf("com.a.app"), 101)
        assertTrue(uninstall.contains("--user 101"))
        assertFalse(uninstall.contains("--user 95"))
    }

    // F4：shell argv 只接受嚴格 Android grammar。
    @Test
    fun shellPackageValidator_grammarAndBounds() {
        for (pkg in listOf("..", "-x", "a-", "com..x", "com.1x.app", "1com.app", "com.a-b", "a", "")) {
            assertFalse("must reject [$pkg]", PolicySource.isValidShellPackage(pkg))
        }
        for (pkg in listOf("com.example.app", "a.b", "com.A_9.app")) {
            assertTrue("must accept [$pkg]", PolicySource.isValidShellPackage(pkg))
        }
        assertFalse("over-long must reject", PolicySource.isValidShellPackage("com." + "a".repeat(300)))
    }

    // F4：builder 擋掉不安全 token，不得內插進 shell 指令。
    @Test
    fun builders_dropShellUnsafeTokens() {
        val probe = buildDualProbeCommand(listOf("com.a.app", "com..x", "..", "a-"), 95)
        assertTrue(probe.contains("com.a.app"))
        assertFalse(probe.contains("com..x"))
        assertFalse(probe.contains("a-"))
        val uninstall = buildDualUninstallCommand(listOf("com.a.app", "com..x"), 95)
        assertTrue(uninstall.contains("com.a.app"))
        assertFalse(uninstall.contains("com..x"))
    }
}
