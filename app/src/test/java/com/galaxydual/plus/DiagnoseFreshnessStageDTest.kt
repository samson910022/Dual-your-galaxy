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

    // K3：全數字時:分:秒 (雙冒號) 不得誤遮為 <ip>；既有 IPv6 形仍遮。
    @Test
    fun k3_hmsTimestamp_notRedacted_ipv6StillMasked() {
        assertEquals("12:34:56", redactIp("12:34:56"))
        assertEquals("time 12:34:56 stays readable", "time 12:34:56", redactIp("time 12:34:56"))

        val loop = redactIp("loop ::1 end")
        assertTrue("ipv6 ::1 must still mask, got: $loop", loop.contains("<ip>"))

        val comp1 = redactIp("addr fe80::1 end")
        assertTrue("ipv6 fe80::1 must still mask, got: $comp1", comp1.contains("<ip>"))

        val comp2 = redactIp("addr 2001:db8::1 end")
        assertTrue("ipv6 2001:db8::1 must still mask, got: $comp2", comp2.contains("<ip>"))

        val full = redactIp("addr 2001:0db8:85a3:0000:0000:8a2e:0370:7334 end")
        assertTrue("ipv6 full form must still mask, got: $full", full.contains("<ip>"))
    }

    // M：純數字完整 8 段 IPv6 不得穿透；短冒號/版本/數字仍保留可讀性。
    @Test
    fun m_pureNumericFullIpv6_masked_shortColonsKept() {
        assertEquals("<ip>", redactIp("2001:0:0:0:0:0:0:1"))
        assertEquals("<ip>", redactIp("1:2:3:4:5:6:7:8"))
        assertEquals("addr <ip> end", redactIp("addr 2001:0:0:0:0:0:0:1 end"))
        assertEquals("addr <ip> end", redactIp("addr 1:2:3:4:5:6:7:8 end"))

        assertEquals("12:34:56", redactIp("12:34:56"))
        assertEquals("12:34", redactIp("12:34"))
        assertEquals("v1.2.3", redactIp("v1.2.3"))
        assertEquals("user 95", redactIp("user 95"))
    }

    // M 匯出端到端：含純數字完整 8 段 IPv6 的 techDetail 不洩 raw。
    @Test
    fun m_export_pureNumericIpv6Tech_noRawLeak() {
        val raw = "2001:0:0:0:0:0:0:1"
        val state = UiState(
            diagnose = diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = "ip $raw port 5555"),
        )
        val text = buildDiagnosticExport(state)
        assertFalse("export must not leak pure-numeric ipv6, got: $text", text.contains(raw))
        assertTrue("export must carry ip marker, got: $text", text.contains("<ip>"))
    }

    // P2：IPv4-embedded IPv6 須整段遮蔽 (embedded 分支優先於純 IPv4/壓縮分支，
    // 否則純 IPv4 先吃尾段留 `::ffff:<ip>`，或壓縮分支在 `.` 止殘留 `<ip>.168.1.25`)。
    @Test
    fun p2_ipv4EmbeddedIpv6_maskedWhole() {
        assertEquals("<ip>", redactIp("::ffff:192.168.1.25"))
        assertEquals("<ip>", redactIp("2001:db8::ffff:192.168.1.25"))
        assertEquals("<ip>", redactIp("::192.168.1.25"))
        // 大寫 FFFF 須整段吞入 (embedded (?i:ffff:)?)，否則殘留 `.168.1.25`。
        assertEquals("<ip>", redactIp("::FFFF:192.168.1.25"))
        assertEquals("<ip>", redactIp("2001:db8::FFFF:192.168.1.25"))
        // NAT64 鎖定 (前綴+`::`+IPv4，不經 ffff 分支；大小寫皆整段吞入)。
        assertEquals("<ip>", redactIp("64:ff9b::192.168.1.25"))
        assertEquals("<ip>", redactIp("64:FF9B::192.168.1.25"))
        assertEquals("addr <ip> end", redactIp("addr ::ffff:192.168.1.25 end"))
        // 純 IPv4 不含 `::`，不受 embedded 分支影響。
        assertEquals("ip <ip> port 5555", redactIp("ip 192.168.0.5 port 5555"))
        // 既有形仍遮；時間/版本/短冒號仍原樣。
        assertEquals("loop <ip> end", redactIp("loop ::1 end"))
        assertEquals("addr <ip> end", redactIp("addr 2001:db8::1 end"))
        assertEquals("time 12:34", redactIp("time 12:34"))
        assertEquals("12:34:56", redactIp("12:34:56"))
        assertEquals("v1.2.3", redactIp("v1.2.3"))
        assertEquals("user 95", redactIp("user 95"))
    }

    // P2 匯出端到端：含 IPv4-embedded IPv6 的 techDetail 不洩 raw。
    @Test
    fun p2_export_embeddedIpv6Tech_noRawLeak() {
        val raw = "::ffff:192.168.1.25"
        val state = UiState(
            diagnose = diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = "ip $raw port 5555"),
        )
        val text = buildDiagnosticExport(state)
        assertFalse("export must not leak embedded ipv6, got: $text", text.contains(raw))
        assertFalse("export must not leak ipv4 tail, got: $text", text.contains("192.168.1.25"))
        assertTrue("export must carry ip marker, got: $text", text.contains("<ip>"))
    }

    // O：未壓縮 IPv4-embedded IPv6 須整段遮蔽 (無 `::`，恰 6 群+IPv4 尾的分支出現在
    // 純 IPv4 與一般冒號分支之前；否則含字母分支在 `.` 止吃掉 `0:…:ffff:192` 殘留 `<ip>.168.1.25`)。
    // 只查全址不在輸出中不足以排除後綴洩漏：舊實作全址字串已不在輸出中，仍洩漏 `.168.1.25`
    // 後綴，故須另斷言不含 `.168.1.25` 尾。
    @Test
    fun o_uncompressedEmbeddedIpv6_maskedWhole() {
        val raws = listOf(
            "0:0:0:0:0:ffff:192.168.1.25",
            "0:0:0:0:0:FFFF:192.168.1.25",
            "2001:db8:0:0:0:ffff:192.168.1.25",
        )
        for (raw in raws) {
            assertEquals("uncompressed embedded must mask whole: $raw", "<ip>", redactIp(raw))
            assertEquals("pii pipeline must mask whole: $raw", "<ip>", redactPii(raw))
            val framed = redactIp("addr $raw end")
            assertEquals("framed must mask whole: $raw", "addr <ip> end", framed)
            assertFalse("must not leak ipv4 tail, got: $framed", framed.contains(".168.1.25"))
            val viaPii = redactPii("addr $raw end")
            assertFalse("pii must not leak ipv4 tail, got: $viaPii", viaPii.contains(".168.1.25"))
        }
        // 既有全保留：embedded 含`::`/NAT64/壓縮/完整8段/含字母仍遮；短冒號/版本/數字仍原樣。
        assertEquals("<ip>", redactIp("::ffff:192.168.1.25"))
        assertEquals("<ip>", redactIp("::192.168.1.25"))
        assertEquals("<ip>", redactIp("64:ff9b::192.168.1.25"))
        assertEquals("loop <ip> end", redactIp("loop ::1 end"))
        assertEquals("addr <ip> end", redactIp("addr 2001:db8::1 end"))
        assertEquals("<ip>", redactIp("2001:0:0:0:0:0:0:1"))
        assertEquals("<ip>", redactIp("1:2:3:4:5:6:7:8"))
        assertEquals("addr <ip> end", redactIp("addr ab:cd:ef end"))
        assertEquals("12:34:56", redactIp("12:34:56"))
        assertEquals("12:34", redactIp("12:34"))
        assertEquals("v1.2.3", redactIp("v1.2.3"))
        assertEquals("user 95", redactIp("user 95"))
        assertEquals("ip <ip> port 5555", redactIp("ip 192.168.0.5 port 5555"))
    }

    // O 匯出端到端：含未壓縮 embedded 的 techDetail 不洩 raw。
    // 只查全址不在輸出中不足以排除後綴洩漏：另斷言不含 `.168.1.25` 尾。
    @Test
    fun o_export_uncompressedEmbeddedIpv6Tech_noRawLeak() {
        val raws = listOf(
            "0:0:0:0:0:ffff:192.168.1.25",
            "0:0:0:0:0:FFFF:192.168.1.25",
            "2001:db8:0:0:0:ffff:192.168.1.25",
        )
        for (raw in raws) {
            val state = UiState(
                diagnose = diagnoseEvent(DiagnoseCode.CHANNEL_ERROR, techDetail = "ip $raw port 5555"),
            )
            val text = buildDiagnosticExport(state)
            assertFalse("export must not leak raw uncompressed embedded, got: $text", text.contains(raw))
            assertFalse("export must not leak ipv4 tail, got: $text", text.contains(".168.1.25"))
            assertTrue("export must carry ip marker, got: $text", text.contains("<ip>"))
        }
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
