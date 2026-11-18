// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Issue #73（BBC6）：清除政策前雙開提醒——純函數指令建構／輸出解析 JVM 測試。
// 覆蓋 buildDualProbeCommand / parseDualProbeOutput / buildDualUninstallCommand
// （MAX_DUAL_PROBE_PKGS 上限箝制、排序去重、shell 安全 token 內插、
// 解析器僅採信 "INST <pkg>" 行並去重）；不觸碰 Android runtime／su／pm／裝置。
// Dialog／probe／uninstall 的實機語義（REAL_su、pm path 輸出格式、BBC6 自動刪分身）
// 無法在 JVM 覆蓋；實機語義以 DEVICE-UNVERIFIED 標記，未在本測試中宣稱。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreClearWarningTest {

    // 單一測試覆蓋三個純 seam（指令建構×2、解析器×1）與上限箝制。
    @Test
    fun preClearProbeUninstallCommandsAndParser() {
        // --- probe 指令：排序＋去重＋以空格 join；pm path --user 95；case 判定 ---
        val cmd = buildDualProbeCommand(listOf("com.b.app", "com.a.app", "com.a.app"))
        assertTrue(cmd.startsWith("for pkg in com.a.app com.b.app; do "))
        assertTrue(cmd.contains("pm path --user 95"))
        assertTrue(cmd.contains("2>&1"))
        // 判定必須是 package: 前綴（不得退回 [ -n "$out" ]：2>&1 合併後未安裝套件的
        // 錯誤輸出會讓 $out 非空 → 全部假 positive）。
        assertTrue(cmd.contains("case \"\$out\" in package:*"))
        assertTrue(cmd.contains("INST"))

        // --- 上限箝制：超過 MAX_DUAL_PROBE_PKGS 只保留前 N（排序後） ---
        val big = (1..MAX_DUAL_PROBE_PKGS + 5).map { "com.example.p$it" }
        val cappedPkgs = buildDualProbeCommand(big)
            .removePrefix("for pkg in ")
            .substringBefore("; do")
            .split(" ")
        assertEquals(MAX_DUAL_PROBE_PKGS, cappedPkgs.size)

        // --- 解析器：僅採信 INST 行；pm path 回顯（package: 路徑）不列入 ---
        assertEquals(
            listOf("com.b.app", "com.a.app"),
            parseDualProbeOutput("INST com.b.app\nINST com.a.app\nINST com.b.app\n"),
        )
        assertEquals(
            emptyList<String>(),
            parseDualProbeOutput(
                "package:/data/app/com.a.app/base.apk\n" +
                    "Error: package not installed for user 95\n",
            ),
        )
        // 截斷尾行／雜訊行不誤判；空白與空 pkg 丟棄。
        assertEquals(
            listOf("com.a.app"),
            parseDualProbeOutput("random noise\nINST com.a.app\nINST \nINST\n"),
        )

        // --- uninstall 指令：同款 for 迴圈，逐個 pm uninstall --user 95 ---
        val uninstall = buildDualUninstallCommand(listOf("com.b.app", "com.a.app", "com.a.app"))
        assertEquals(
            "for pkg in com.a.app com.b.app; do pm uninstall --user 95 \"\$pkg\"; done",
            uninstall,
        )
    }
}
