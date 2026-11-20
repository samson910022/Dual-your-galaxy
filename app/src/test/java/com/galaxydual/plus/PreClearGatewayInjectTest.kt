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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val gw = FakeGateway(CmdResult(exit = 0, out = "INST com.a.app\n", err = ""), delayMs = 5)
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
}
