// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Security review LOW  pin：診斷匯出標題必須保留人工確認警告
// (「分享前請人工確認」)，不得只寫「已遮蔽」使人誤信絕對乾淨。
// 註：buildDiagnosticExport 標題為程式碼寫死繁中 (DiagnoseFreshness.kt，
// scope-limited 非雙語)，非 res/locale 解析 — 此字面是穩定的程式碼契約，
// 非 locale 相關邏輯斷言。第二測證明四道遮蔽管線不會把警告本身洗掉。
package com.galaxydual.plus

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportWarningTest {

    @Test
    fun exportTitle_warnsHumanCheckBeforeSharing_defaultState() {
        val export = buildDiagnosticExport(UiState())
        assertTrue(
            "diagnostic export title must warn 人工確認, got first line: ${export.lines().firstOrNull()}",
            export.contains("分享前請人工確認"),
        )
    }

    @Test
    fun exportTitle_survivesRedaction_populatedState() {
        val state = UiState(
            inventory = InventorySnapshot(
                source = "pm list packages --user 95",
                targetId = 95,
                targetSerial = 15,
                queryTimeMs = 123456789L,
                freshness = InventoryFreshness.FRESH,
                count = 2,
            ),
            lastOperation = LastOperation(
                opId = "clone:com.example.a",
                appKey = "15:com.example.a",
                pinnedTarget = 95,
                phase = OpPhase.UNKNOWN,
                timeMs = 123456789L,
                detail = "cannot verify for user 95: denied",
            ),
            diagnose = diagnoseEvent(
                DiagnoseCode.VERIFY_UNKNOWN,
                techDetail = "cannot verify com.example.a for user 95: denied",
            ),
            hookStatus = HookStatus.ACTIVE,
            hookScopes = setOf("system_server"),
            hookQueriedAtMs = 123456789L,
            hookMarkerTimeMs = 123400000L,
        )
        val export = buildDiagnosticExport(state)
        assertTrue(
            "redaction pipeline must not strip the 人工確認 warning, got first line: ${export.lines().firstOrNull()}",
            export.contains("分享前請人工確認"),
        )
    }
}
