// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase E: probe/uninstall gateway 可注入＋error/partial/Unknown/cancel/race 矩陣。
// probeDualAppsInUser95 早已可注入（DualProbeResultTest 覆蓋）；本檔鎖定新注入的
// uninstallDualAppsInUser95(gateway) —— error（拋例外→false）、partial（截斷/timeout/
// transportUnknown→false）、Unknown（exit!=0／stderr／Failure 行→false）、cancel
// （CancellationException 透傳）、race（併發卸載皆收斂）＋probe race（併發探測皆收斂）。
// 隱私：gateway 回包不含包名原文斷言；log 只記計數（見 MainActivity preClearLog 計數口徑）。
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.Privilege
import com.galaxydual.core.TRUNCATION_MARKER
import com.galaxydual.core.VerifiedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PreClearGatewayInjectTest {

    private class FakeGateway(
        private val result: CmdResult? = null,
        private val error: Throwable? = null,
        private val delayMs: Long = 0L,
    ) : CmdGateway {
        var calls = 0
        var lastCmd: String? = null
        override val privilege: Privilege = Privilege.ROOT
        override suspend fun run(cmd: String): CmdResult {
            calls++
            lastCmd = cmd
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            error?.let { throw it }
            return result ?: CmdResult(exit = 0, out = "", err = "")
        }
    }

    // ---- uninstall: success / empty fast-path ----

    @Test
    fun uninstall_cleanOutput_returnsTrue() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = ""))
        val ok = runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) }
        assertTrue(ok)
        assertEquals(1, gw.calls)
        assertTrue((gw.lastCmd ?: "").contains("--user 95"))
        assertTrue((gw.lastCmd ?: "").contains("pm uninstall"))
    }

    @Test
    fun uninstall_emptyInput_returnsTrueWithoutGatewayCall() {
        val gw = FakeGateway(CmdResult(exit = 1, out = "boom", err = "boom"))
        val ok = runBlocking { uninstallDualAppsInUser95(emptyList(), gw) }
        assertTrue(ok)
        assertEquals(0, gw.calls)
    }

    // ---- uninstall: error / Unknown arms (all fail-closed false) ----

    @Test
    fun uninstall_nonZeroExit_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 1, out = "", err = ""))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    @Test
    fun uninstall_stderrNonBlank_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = "permission denied"))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    @Test
    fun uninstall_failureLine_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", err = ""))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    @Test
    fun uninstall_deleteFailedVariant_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "DELETE_FAILED_INTERNAL_ERROR\n", err = ""))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    // ---- uninstall: partial arms (truncated / timeout / transport) ----

    @Test
    fun uninstall_truncatedFlag_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = "", truncated = true))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    @Test
    fun uninstall_truncationMarker_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 0, out = TRUNCATION_MARKER, err = ""))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
        val gw2 = FakeGateway(CmdResult(exit = 0, out = "", err = "x $TRUNCATION_MARKER"))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw2) })
    }

    @Test
    fun uninstall_timedOut_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = "", timedOut = true))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    @Test
    fun uninstall_transportUnknown_returnsFalse() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = "boom", transportUnknown = true))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    @Test
    fun uninstall_gatewayThrows_returnsFalse() {
        val gw = FakeGateway(error = IllegalStateException("su boom"))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    @Test
    fun uninstall_gatewayThrowsNullMessage_returnsFalse() {
        val gw = FakeGateway(error = RuntimeException(null as String?))
        assertFalse(runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) })
    }

    // ---- uninstall: cancel propagates ----

    @Test
    fun uninstall_cancellation_propagates() {
        val gw = FakeGateway(error = CancellationException("cancel-seam"))
        try {
            runBlocking { uninstallDualAppsInUser95(listOf("com.a.app"), gw) }
            fail("CancellationException must propagate, not map to false")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test
    fun uninstall_cmdSanitized_noInvalidToken() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = ""))
        runBlocking { uninstallDualAppsInUser95(listOf("  com.a.app  ", "", "com.b.app"), gw) }
        val cmd = gw.lastCmd ?: ""
        assertTrue(cmd.contains("--user 95"))
        assertTrue(cmd.contains("com.a.app"))
        assertTrue(cmd.contains("com.b.app"))
    }

    // ---- race: concurrent uninstalls all settle ----

    @Test
    fun uninstall_race_concurrentAllSettle() {
        val counter = AtomicInteger(0)
        val gw = object : CmdGateway {
            override val privilege: Privilege = Privilege.ROOT
            override suspend fun run(cmd: String): CmdResult {
                counter.incrementAndGet()
                kotlinx.coroutines.delay(10)
                return CmdResult(exit = 0, out = "", err = "")
            }
        }
        val results = runBlocking {
            (1..10).map { async { uninstallDualAppsInUser95(listOf("com.a.app"), gw) } }.awaitAll()
        }
        assertEquals(10, results.size)
        assertTrue("all concurrent uninstalls must succeed", results.all { it })
        assertEquals(10, counter.get())
    }

    // ---- race: concurrent probes all settle (gateway injectable parity) ----

    @Test
    fun probe_race_concurrentAllSettle() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nINST com.a.app\n", err = ""), delayMs = 5)
        val results = runBlocking {
            (1..10).map { async { probeDualAppsInUser95(listOf("com.a.app"), gw) } }.awaitAll()
        }
        assertEquals(10, results.size)
        assertTrue("all concurrent probes must be Ok", results.all { it is DualProbeResult.Ok })
        assertEquals(10, gw.calls)
    }

    @Test
    fun probe_cancel_propagates_viaInjectedGateway() {
        val gw = FakeGateway(error = CancellationException("probe-cancel-seam"))
        try {
            runBlocking { probeDualAppsInUser95(listOf("com.a.app"), gw) }
            fail("probe CancellationException must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    // ---- R05/NEW-1: verified uid threading + unverified fail-closed ----

    @Test
    fun probe_unverifiedTarget_failsClosedWithoutGatewayCall() {
        val gw = FakeGateway(CmdResult(0, "PROBE_RC=0\nCLEAN com.a.app\n", ""))
        val res = runBlocking { probeDualApps(listOf("com.a.app"), null, gw) }
        assertTrue("unverified probe must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("target-unverified:"))
        assertEquals(0, gw.calls)
    }

    @Test
    fun uninstall_unverifiedTarget_refusedWithoutGatewayCall() {
        val gw = FakeGateway(CmdResult(0, "", ""))
        assertFalse(runBlocking { uninstallDualApps(listOf("com.a.app"), null, gw) })
        assertEquals(0, gw.calls)
    }

    @Test
    fun probe_andUninstall_useExplicitVerifiedUid() {
        val probeGw = FakeGateway(CmdResult(0, "PROBE_RC=0\nCLEAN com.a.app\n", ""))
        val probe = runBlocking { probeDualApps(listOf("com.a.app"), 101, probeGw) }
        assertTrue(probe is DualProbeResult.Ok)
        assertTrue((probeGw.lastCmd ?: "").contains("--user 101"))
        assertFalse((probeGw.lastCmd ?: "").contains("--user 95"))

        val uninstallGw = FakeGateway(CmdResult(0, "", ""))
        assertTrue(runBlocking { uninstallDualApps(listOf("com.a.app"), 101, uninstallGw) })
        assertTrue((uninstallGw.lastCmd ?: "").contains("--user 101"))
        assertFalse((uninstallGw.lastCmd ?: "").contains("--user 95"))
    }

    // ---- Phase1 review(HIGH): stale target gate must not authorize destructive actions ----

    @Test
    fun resolvePreClearDualUserId_staleOrUnknown_returnsNull_freshReturnsId() {
        // STALE/UNKNOWN explicitly retain targetId/targetSerial — a remapped profile
        // (95 -> 96) would otherwise keep yielding a non-null stale id.
        val stale = UiState(
            inventory = InventorySnapshot(
                targetId = 95,
                targetSerial = 7,
                freshness = InventoryFreshness.STALE,
            ),
        )
        assertNull("STALE must fail closed", resolvePreClearDualUserId(stale))
        val unknown = UiState(
            inventory = InventorySnapshot(
                targetId = 95,
                targetSerial = 7,
                freshness = InventoryFreshness.UNKNOWN,
            ),
        )
        assertNull("UNKNOWN must fail closed", resolvePreClearDualUserId(unknown))
        val fresh = UiState(
            inventory = InventorySnapshot(
                targetId = 95,
                targetSerial = 7,
                freshness = InventoryFreshness.FRESH,
            ),
        )
        assertEquals(95, resolvePreClearDualUserId(fresh))
        val freshRemapped = UiState(
            inventory = InventorySnapshot(
                targetId = 96,
                targetSerial = 7,
                freshness = InventoryFreshness.FRESH,
            ),
        )
        assertEquals(96, resolvePreClearDualUserId(freshRemapped))
        // serial null / id<=0 remain fail-closed even when FRESH.
        assertNull(
            resolvePreClearDualUserId(
                UiState(inventory = InventorySnapshot(targetId = 95, freshness = InventoryFreshness.FRESH)),
            ),
        )
        assertNull(
            resolvePreClearDualUserId(
                UiState(inventory = InventorySnapshot(targetId = -1, targetSerial = 7, freshness = InventoryFreshness.FRESH)),
            ),
        )
    }

    @Test
    fun preClearUidStillValid_nullOrMismatch_failsClosed() {
        assertTrue(preClearUidStillValid(95, 95))
        assertTrue(preClearUidStillValid(96, 96))
        assertFalse(preClearUidStillValid(null, 95))
        assertFalse(preClearUidStillValid(95, null))
        assertFalse(preClearUidStillValid(95, 96)) // remap 95 -> 96 at click time
        assertFalse(preClearUidStillValid(null, null))
        assertFalse(preClearUidStillValid(0, 0))
        assertFalse(preClearUidStillValid(-1, -1))
    }

    // ---- PR-74 A2 (P2): id+serial 綁定的 TOCTOU fence（同 id 換 serial 必拒） ----

    private fun freshState(id: Int = 95, serial: Int? = 7): UiState = UiState(
        inventory = InventorySnapshot(
            targetId = id,
            targetSerial = serial,
            freshness = InventoryFreshness.FRESH,
        ),
    )

    private class FenceHarness(
        private val captured: PreClearDualTarget? = PreClearDualTarget(95, 7),
        live: PreClearDualTarget? = PreClearDualTarget(95, 7),
        fresh: VerifiedTarget? = VerifiedTarget(95, 7, 0),
    ) {
        val gw = FakeGateway(CmdResult(0, "", ""))
        var liveTarget: PreClearDualTarget? = live
        var freshTarget: VerifiedTarget? = fresh
        var freshError: Throwable? = null

        suspend fun run(pkgs: List<String> = listOf("com.a.app")): Int? =
            rootUninstallIfTargetStillValid(
                installed = pkgs,
                captured = captured,
                resolveLiveTarget = { liveTarget },
                fetchFreshVerifiedTarget = {
                    freshError?.let { throw it }
                    freshTarget
                },
                // FakeGateway call log：abort 路徑必須 0 呼叫 uninstall。
                uninstall = { p, uid -> uninstallDualApps(p, uid, gw) },
            )
    }

    @Test
    fun resolvePreClearDualTarget_capturesSerial_freshOnly() {
        assertEquals(PreClearDualTarget(95, 7), resolvePreClearDualTarget(freshState()))
        assertEquals(PreClearDualTarget(101, 8), resolvePreClearDualTarget(freshState(101, 8)))
        // 與 int 薄別名一致（既有呼叫端/測試不破壞）。
        assertEquals(95, resolvePreClearDualUserId(freshState()))
        // STALE/UNKNOWN/serial-null/id<=0 一律 null（fail closed）。
        val stale = freshState().copy(
            inventory = freshState().inventory.copy(freshness = InventoryFreshness.STALE),
        )
        assertNull(resolvePreClearDualTarget(stale))
        assertNull(resolvePreClearDualTarget(freshState(serial = null)))
        assertNull(resolvePreClearDualTarget(freshState(id = -1)))
    }

    @Test
    fun preClearTargetStillValid_serialBinding_failsClosed() {
        val captured = PreClearDualTarget(95, 7)
        assertTrue(preClearTargetStillValid(captured, PreClearDualTarget(95, 7)))
        // 同 id 換 serial（delete+recreate）＝身份重用，必須拒絕。
        assertFalse(preClearTargetStillValid(captured, PreClearDualTarget(95, 8)))
        // known serial -> null（continuity lost）必須拒絕。
        assertFalse(preClearTargetStillValid(captured, PreClearDualTarget(95, null)))
        // remap / null / 非正 id 皆拒絕。
        assertFalse(preClearTargetStillValid(captured, PreClearDualTarget(96, 7)))
        assertFalse(preClearTargetStillValid(captured, null))
        assertFalse(preClearTargetStillValid(null, PreClearDualTarget(95, 7)))
        assertFalse(preClearTargetStillValid(PreClearDualTarget(-1, 7), PreClearDualTarget(-1, 7)))
    }

    @Test
    fun rootUninstall_sameIdSameSerial_uninstallsOnce() {
        val h = FenceHarness()
        assertEquals(95, runBlocking { h.run() })
        assertEquals("fresh 通過後應執行一次 uninstall", 1, h.gw.calls)
        assertTrue((h.gw.lastCmd ?: "").contains("--user 95"))
    }

    @Test
    fun rootUninstall_sameIdDifferentSerial_abortsWithoutUninstall() {
        // 精確事故形狀：id 重用但 serial 翻新（live 仍舊、fresh 是新 serial）。
        val h = FenceHarness(fresh = VerifiedTarget(95, 8, 0))
        assertNull(runBlocking { h.run() })
        assertEquals("serial 翻新絕不 uninstall", 0, h.gw.calls)
    }

    @Test
    fun rootUninstall_liveSerialChanged_abortsWithoutUninstall() {
        // inventory 在警告顯示後被改寫為同 id 新 serial（recreate），点击當下即攔。
        val h = FenceHarness(live = PreClearDualTarget(95, 8))
        assertNull(runBlocking { h.run() })
        assertEquals(0, h.gw.calls)
    }

    @Test
    fun rootUninstall_knownSerialToNull_abortsWithoutUninstall() {
        // continuity lost：fresh 查不到 serial（expected known -> fresh null）。
        val h = FenceHarness(fresh = VerifiedTarget(95, null, 0))
        assertNull(runBlocking { h.run() })
        assertEquals(0, h.gw.calls)
        // live 的 serial 消失亦同。
        val h2 = FenceHarness(live = PreClearDualTarget(95, null))
        assertNull(runBlocking { h2.run() })
        assertEquals(0, h2.gw.calls)
    }

    @Test
    fun rootUninstall_freshToStaleAtClick_abortsWithoutUninstall() {
        // 警告顯示時 FRESH，点击瞬間 inventory 已被標 STALE（resolveLive 回 null）。
        val stale = freshState().copy(
            inventory = freshState().inventory.copy(freshness = InventoryFreshness.STALE),
        )
        assertNull(resolvePreClearDualTarget(stale))
        val h = FenceHarness()
        h.liveTarget = resolvePreClearDualTarget(stale)
        assertNull(runBlocking { h.run() })
        assertEquals(0, h.gw.calls)
    }

    @Test
    fun rootUninstall_nullOrUnverified_abortsWithoutUninstall() {
        // captured null（警告顯示時 unverified）。
        val h1 = FenceHarness(captured = null)
        assertNull(runBlocking { h1.run() })
        assertEquals(0, h1.gw.calls)
        // fresh fetch 回 null（Unknown/Refused/通道失敗）。
        val h2 = FenceHarness(fresh = null)
        assertNull(runBlocking { h2.run() })
        assertEquals(0, h2.gw.calls)
        // live 回 null（unverified / remap 已丟目標）。
        val h3 = FenceHarness(live = null)
        assertNull(runBlocking { h3.run() })
        assertEquals(0, h3.gw.calls)
    }

    @Test
    fun rootUninstall_freshFetchThrows_abortsWithoutUninstall() {
        // gateway 查詢例外被 fence 吞成 null（取消除外），仍 fail closed 零 uninstall。
        val h = FenceHarness()
        h.freshError = IllegalStateException("identity channel boom")
        assertNull(runBlocking { h.run() })
        assertEquals(0, h.gw.calls)
    }
}
