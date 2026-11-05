// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// 階段D (隱私 P1) 新增鎖定測試：只覆蓋 D1–D5，不碰架構。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnoseFreshnessStageDTest {
    // D1 相對路徑註解矛盾鎖實測：sdcard/a.png 中 "/a.png" 命中 PATH_TOKEN。
    @Test
    fun d1_relativePathSubsegment_masked() {
        assertEquals("sdcard<path>", redactPaths("sdcard/a.png"))
    }

    // D2 郵件：local-part 不得殘留；順序置於包名之前 (郵件先走，否則碎成 foo@<pkg>)。
    @Test
    fun d2_email_redactedBeforePkg() {
        val viaPii = redactPii("contact foo@example.com")
        assertFalse("local-part must not leak, got: $viaPii", viaPii.contains("foo"))
        assertFalse("raw email must not leak, got: $viaPii", viaPii.contains("foo@example.com"))
        assertFalse("domain must not leak as pkg remnant, got: $viaPii", viaPii.contains("example.com"))
        assertTrue("email marker must appear, got: $viaPii", viaPii.contains("<email>"))

        assertEquals("contact <email>", redactEmail("contact foo@example.com"))

        val viaSanitize = sanitizeForDisplay("contact foo@example.com")
        assertFalse("sanitize must not leak email, got: $viaSanitize", viaSanitize.contains("foo"))
        assertTrue("sanitize must carry email marker, got: $viaSanitize", viaSanitize.contains("<email>"))
    }

    // D2 匯出：含 email 的 ChannelError tech 不洩 raw。
    @Test
    fun d2_export_emailTech_noRawLeak() {
        val raw = "foo@example.com"
        val state = UiState(
            diagnose = diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = "UserInfo $raw failed"),
        )
        val text = buildDiagnosticExport(state)
        assertFalse("export must not leak raw email, got: $text", text.contains(raw))
        assertFalse("export must not leak local-part, got: $text", text.contains("foo@"))
        assertTrue("export must carry email marker, got: $text", text.contains("<email>"))
    }

    // D3 IPv4 直通鎖定 + 數字可讀性保留。
    @Test
    fun d3_ipv4_masked_numbersKept() {
        val viaIp = redactIp("ip 192.168.0.5 port 5555")
        assertFalse("ipv4 must not leak, got: $viaIp", viaIp.contains("192.168"))
        assertTrue("ip marker must appear, got: $viaIp", viaIp.contains("<ip>"))

        val joint = sanitizeForDisplay("ip 192.168.0.5 port 5555")
        assertFalse("joint pipeline must not leak ipv4, got: $joint", joint.contains("192.168"))
        assertTrue("joint pipeline must carry ip marker, got: $joint", joint.contains("<ip>"))

        assertEquals("user 95", redactPii("user 95"))
        assertEquals("serial 15", redactPii("serial 15"))
        assertEquals("exit=0", redactPii("exit=0"))
        assertEquals("user 95", sanitizeForDisplay("user 95"))
        assertEquals("serial 15", sanitizeForDisplay("serial 15"))
        assertEquals("exit=0", sanitizeForDisplay("exit=0"))
    }

    // D3 IPv6 壓縮形 + 完整形遮蔽，數字可讀性保留。
    @Test
    fun d3_ipv6_compressedAndFull_masked_numbersKept() {
        val loop = redactIp("loop ::1 end")
        assertFalse("ipv6 ::1 must not leak, got: $loop", loop.contains("::1"))
        assertTrue("ip marker must appear, got: $loop", loop.contains("<ip>"))

        val comp1 = redactIp("addr fe80::1 end")
        assertFalse("ipv6 fe80::1 must not leak, got: $comp1", comp1.contains("fe80::1"))
        assertTrue("ip marker must appear, got: $comp1", comp1.contains("<ip>"))

        val comp2 = redactIp("addr 2001:db8::1 end")
        assertFalse("ipv6 2001:db8::1 must not leak, got: $comp2", comp2.contains("2001:db8::1"))
        assertTrue("ip marker must appear, got: $comp2", comp2.contains("<ip>"))

        val full = redactIp("addr 2001:0db8:85a3:0000:0000:8a2e:0370:7334 end")
        assertFalse(
            "ipv6 full form must not leak, got: $full",
            full.contains("2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
        )
        assertTrue("ip marker must appear, got: $full", full.contains("<ip>"))

        val joint = sanitizeForDisplay("ping fe80::1 user 95 serial 15 exit=0")
        assertFalse("joint pipeline must not leak ipv6, got: $joint", joint.contains("fe80::1"))
        assertTrue("joint pipeline must carry ip marker, got: $joint", joint.contains("<ip>"))
        assertTrue("user id must stay readable, got: $joint", joint.contains("95"))
        assertTrue("serial must stay readable, got: $joint", joint.contains("15"))
        assertTrue("exit must stay readable, got: $joint", joint.contains("exit=0"))

        assertEquals("user 95", redactPii("user 95"))
        assertEquals("serial 15", redactPii("serial 15"))
        assertEquals("exit=0", redactPii("exit=0"))

        assertEquals("time 12:34 stays readable", "time 12:34", redactIp("time 12:34"))
        assertEquals("version stays readable", "v1.2.3", redactIp("v1.2.3"))
    }

    // D3 匯出 IPv4 不洩 raw。
    @Test
    fun d3_export_ipTech_noRawLeak() {
        val state = UiState(
            diagnose = diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = "ip 192.168.0.5 port 5555"),
        )
        val text = buildDiagnosticExport(state)
        assertFalse("export must not leak ipv4, got: $text", text.contains("192.168"))
        assertTrue("export must carry ip marker, got: $text", text.contains("<ip>"))
    }

    // D4 剪貼簿：預覽所見即複製所得 (防分叉) + 文案靜態斷言。
    @Test
    fun d4_copyEqualsPreview_andClipboardWording() {
        val state = UiState(
            inventory = InventorySnapshot(
                source = "pm list packages --user 95",
                targetId = 95,
                queryTimeMs = 123456789L,
                freshness = InventoryFreshness.FRESH,
                count = 1,
            ),
            diagnose = diagnoseEvent(DiagnoseCode.VERIFY_UNKNOWN, techDetail = "cannot verify"),
        )
        assertEquals(
            "預覽所見即複製所得，兩者一字不差",
            buildDiagnosticExport(state),
            diagnosticExportCopyText(state),
        )
        assertTrue(
            "複製成功訊息須含僅本機剪貼簿，got: $DIAGNOSTIC_COPY_SUCCESS_TEXT",
            DIAGNOSTIC_COPY_SUCCESS_TEXT.contains("僅本機剪貼簿"),
        )
        assertTrue(
            "複製成功訊息須補用後請清除剪貼簿，got: $DIAGNOSTIC_COPY_SUCCESS_TEXT",
            DIAGNOSTIC_COPY_SUCCESS_TEXT.contains("用後請清除剪貼簿"),
        )
        assertTrue(
            "複製按鈕須含分享前請人工確認，got: $DIAGNOSTIC_COPY_BUTTON_TEXT",
            DIAGNOSTIC_COPY_BUTTON_TEXT.contains("分享前請人工確認"),
        )
    }

    // D5 碎裂語意鎖定：禁 raw，二選一 (整遮 <path> 或碎裂 <path><pkg><path>)。
    @Test
    fun d5_orderFragmentation_eitherOr() {
        val raw = "com.example.chat"
        val r = sanitizeForDisplay("/data/data/$raw/files")
        assertFalse("fragmented output must not leak raw pkg, got: $r", r.contains(raw))
        assertTrue(
            "order output must be whole <path> or fragmented <path><pkg><path>, got: $r",
            r == "<path>" || r == "<path><pkg><path>",
        )
    }

    // D5 TAB 精確化：延展僅含 U+0020 空格，TAB (\t) 止。
    @Test
    fun d5_tabStops_pathExtension() {
        val r = redactPaths("/sdcard/a\tb")
        assertFalse("path head must be masked, got: $r", r.contains("/sdcard/a"))
        assertTrue("path marker must appear, got: $r", r.contains("<path>"))
        assertTrue("TAB must stop extension, got: $r", r.contains("\t"))
    }
}
