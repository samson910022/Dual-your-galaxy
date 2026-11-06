// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage4：批次結果文字 formatter 測試。鎖定 formatBatchReportText 與
// DualViewModel.isBatchUnknownOutcome 同一未知口徑（VerifyUnknown 與
// ChannelError 皆計未知；NoDual 計失敗），防彙總計數/明細文案漂移；另鎖定
// 明細截斷（前 BATCH_REPORT_MAX_ROWS 項＋「…等 N 項」）與空報告契約。
package com.galaxydual.plus

import com.galaxydual.core.CloneOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchReportTextTest {
    private fun item(pkg: String, outcome: CloneOutcome) = BatchItemResult(
        appKey = "0:$pkg",
        pkg = pkg,
        outcome = outcome,
    )

    @Test
    fun nullOrEmpty_rendersEmpty() {
        assertEquals("", formatBatchReportText(null))
        assertEquals("", formatBatchReportText(BatchReport()))
    }

    @Test
    fun channelError_rendersUnknown_notFailed() {
        // 與 isBatchUnknownOutcome 一致：ChannelError 計未知，永不計失敗。
        val report = BatchReport(
            items = listOf(
                item("com.example.ok", CloneOutcome.Success(95)),
                item("com.example.chan", CloneOutcome.ChannelError("boom")),
                item("com.example.nodual", CloneOutcome.NoDual),
            ),
            successCount = 1,
            failedCount = 1,
            unknownCount = 1,
        )
        val text = formatBatchReportText(report)
        assertTrue("summary must match counts, got: $text", text.contains("成功 1 / 失敗 1 / 未知 1"))
        assertTrue("success mark, got: $text", text.contains("com.example.ok：成功"))
        assertTrue(
            "ChannelError must render unknown with retry hint, got: $text",
            text.contains("com.example.chan：未知（通道無法查證，請重試）"),
        )
        assertTrue("NoDual must render failed, got: $text", text.contains("com.example.nodual：失敗"))
    }

    @Test
    fun verifyUnknown_rendersUnknown_needsRecheck() {
        val report = BatchReport(
            items = listOf(
                item("com.example.u", CloneOutcome.VerifyUnknown(95, "no list")),
            ),
            successCount = 0,
            failedCount = 0,
            unknownCount = 1,
        )
        val text = formatBatchReportText(report)
        assertTrue("got: $text", text.contains("成功 0 / 失敗 0 / 未知 1"))
        assertTrue("got: $text", text.contains("com.example.u：未知（需重查）"))
    }

    @Test
    fun failedOutcomes_allRenderFailed() {
        val report = BatchReport(
            items = listOf(
                item("com.example.inv", CloneOutcome.InvalidPackage("bad")),
                item("com.example.inst", CloneOutcome.InstallFailed("Failure")),
                item("com.example.vf", CloneOutcome.VerifyFailed(95)),
                item("com.example.tm", CloneOutcome.TargetMismatch(95, 96, 15, 41)),
            ),
            successCount = 0,
            failedCount = 4,
            unknownCount = 0,
        )
        val text = formatBatchReportText(report)
        assertTrue("got: $text", text.contains("成功 0 / 失敗 4 / 未知 0"))
        for (pkg in listOf("com.example.inv", "com.example.inst", "com.example.vf", "com.example.tm")) {
            assertTrue("$pkg must render failed, got: $text", text.contains("$pkg：失敗"))
        }
    }

    @Test
    fun overLimit_truncatesWithRemainderNote() {
        val total = BATCH_REPORT_MAX_ROWS + 10
        val items = (1..total).map { i ->
            item("com.example.a$i", CloneOutcome.Success(95))
        }
        val report = BatchReport(
            items = items,
            successCount = total,
            failedCount = 0,
            unknownCount = 0,
        )
        val text = formatBatchReportText(report)
        assertTrue("summary keeps full total, got: $text", text.contains("共 $total 項"))
        assertTrue("first item shown, got: $text", text.contains("com.example.a1：成功"))
        assertTrue(
            "over-limit item hidden, got tail: ${text.takeLast(200)}",
            !text.contains("com.example.a$total：成功"),
        )
        assertTrue(
            "remainder note, got tail: ${text.takeLast(200)}",
            text.contains("…等 10 項（共 $total 項，僅顯示前 $BATCH_REPORT_MAX_ROWS 項）"),
        )
    }
}
