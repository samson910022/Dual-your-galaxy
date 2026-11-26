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
import java.util.concurrent.atomic.AtomicBoolean
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
        reprobe: DualProbeResult = DualProbeResult.Ok(listOf("com.a.app")),
        private val capturedInstalled: Set<String> = setOf("com.a.app"),
        private val actionAlive: () -> Boolean = { true },
    ) {
        val gw = FakeGateway(CmdResult(0, "", ""))
        var liveTarget: PreClearDualTarget? = live
        var freshTarget: VerifiedTarget? = fresh
        var freshError: Throwable? = null
        // G4: flip the originating action's liveness from inside the fresh
        // fetch (a real suspend point) to exercise the POST-await re-check.
        var onFreshFetch: () -> Unit = {}
        // R2-5 (N10)：Continue 重驗的 reprobe seam + proceed／政策寫入計數。
        var reprobeResult: DualProbeResult = reprobe
        var reprobeError: Throwable? = null
        var reprobeCalls = 0
        var proceedCalls = 0
        var storeWrites = 0
        var cancelCalls = 0

        suspend fun run(pkgs: List<String> = listOf("com.a.app")): Int? =
            rootUninstallIfTargetStillValid(
                installed = pkgs,
                captured = captured,
                resolveLiveTarget = { liveTarget },
                fetchFreshVerifiedTarget = {
                    freshError?.let { throw it }
                    onFreshFetch()
                    freshTarget
                },
                // FakeGateway call log：abort 路徑必須 0 呼叫 uninstall。
                uninstall = { p, uid -> uninstallDualApps(p, uid, gw) },
                actionAlive = actionAlive,
            )

        // 仿真 showPreClearWarning Positive 的 dispatch：只有 ProceedOk 才 onProceed
        // （呼叫端據此寫政策）；其餘一律 onCancel、零寫入。
        suspend fun continueCheck(): PreClearContinueOutcome {
            val outcome = revalidatePreClearContinue(
                captured = captured,
                capturedInstalled = capturedInstalled,
                resolveLiveTarget = { liveTarget },
                fetchFreshVerifiedTarget = {
                    freshError?.let { throw it }
                    freshTarget
                },
                reprobe = {
                    reprobeCalls++
                    reprobeError?.let { throw it }
                    reprobeResult
                },
            )
            when (outcome) {
                is PreClearContinueOutcome.ProceedOk -> {
                    proceedCalls++
                    storeWrites++
                }
                else -> cancelCalls++
            }
            return outcome
        }

        // D03a：同一組注入 seam 改走 consent 適配器（复用 revalidatePreClearContinue）。
        suspend fun consentCheck(consent: PolicyWriteConsent): PreClearContinueOutcome {
            val outcome = revalidatePolicyWriteConsent(
                consent = consent,
                resolveLiveTarget = { liveTarget },
                fetchFreshVerifiedTarget = {
                    freshError?.let { throw it }
                    freshTarget
                },
                reprobe = {
                    reprobeCalls++
                    reprobeError?.let { throw it }
                    reprobeResult
                },
            )
            when (outcome) {
                is PreClearContinueOutcome.ProceedOk -> {
                    proceedCalls++
                    storeWrites++
                }
                else -> cancelCalls++
            }
            return outcome
        }
    }

    private fun testConsent(
        userId: Int? = 95,
        serial: String? = "7",
        installed: Set<String> = setOf("com.a.app"),
        delta: Set<String> = setOf("com.a.app"),
        fromFailedProbe: Boolean = false,
        rootVerifiedEmpty: Boolean = false,
        additiveNoRemoval: Boolean = false,
    ): PolicyWriteConsent = PolicyWriteConsent(
        -1L, delta, userId, serial, installed, fromFailedProbe, rootVerifiedEmpty, additiveNoRemoval,
    )

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

    @Test
    fun rootUninstall_deadOriginatingAction_abortsWithoutUninstall() {
        // G4: the confirm's action is already dead by the time the fence runs.
        // The trailing identity re-check must fail closed with ZERO uninstall.
        val h = FenceHarness(actionAlive = { false })
        assertNull(runBlocking { h.run() })
        assertEquals("dead action must never reach the irreversible uninstall", 0, h.gw.calls)
    }

    @Test
    fun rootUninstall_actionSupersededDuringFreshFetch_abortsWithoutUninstall() {
        // G4 race: the action is live when the fence starts, but a newer
        // action supersedes it WHILE the fresh fetch suspends. A check placed
        // only before the await would miss this and still uninstall.
        val alive = AtomicBoolean(true)
        val h = FenceHarness(actionAlive = { alive.get() })
        h.onFreshFetch = { alive.set(false) }
        assertNull(runBlocking { h.run() })
        assertEquals("superseded action must never reach uninstall", 0, h.gw.calls)
    }

    // ---- R2-5 (N10)：非 ROOT Continue 的 serial-bound + membership 重驗 ----

    @Test
    fun preClearContinue_sameTargetAndUnchangedInstalled_proceedsOnce() {
        val h = FenceHarness()
        val outcome = runBlocking { h.continueCheck() }
        assertTrue("unchanged target/installed must proceed", outcome is PreClearContinueOutcome.ProceedOk)
        assertEquals(1, h.proceedCalls)
        assertEquals(1, h.storeWrites)
        assertEquals(0, h.cancelCalls)
        assertEquals(1, h.reprobeCalls)
        assertEquals(0, h.gw.calls)
    }

    @Test
    fun preClearContinue_profileRemapDifferentId_abortsNoProceed() {
        // 警告顯示 95→Continue 時 remap 到 96（device hypothesis）；live fence 必攔。
        val h = FenceHarness(live = PreClearDualTarget(96, 7))
        val outcome = runBlocking { h.continueCheck() }
        assertTrue(outcome is PreClearContinueOutcome.TargetChanged)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.cancelCalls)
        assertEquals(0, h.gw.calls)
    }

    @Test
    fun preClearContinue_sameIdNewSerial_abortsNoProceed() {
        // 同 id 重建換 serial（continuityBreach）；fresh fetch fence 必攔。
        val h = FenceHarness(fresh = VerifiedTarget(95, 8, 0))
        val outcome = runBlocking { h.continueCheck() }
        assertTrue(outcome is PreClearContinueOutcome.TargetChanged)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.cancelCalls)
        assertEquals(0, h.gw.calls)
    }

    @Test
    fun preClearContinue_freshToStaleAtContinue_abortsNoProceed() {
        val stale = freshState().copy(
            inventory = freshState().inventory.copy(freshness = InventoryFreshness.STALE),
        )
        assertNull(resolvePreClearDualTarget(stale))
        val h = FenceHarness()
        h.liveTarget = resolvePreClearDualTarget(stale)
        val outcome = runBlocking { h.continueCheck() }
        assertTrue(outcome is PreClearContinueOutcome.TargetChanged)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(0, h.gw.calls)
    }

    @Test
    fun preClearContinue_newCloneInstalledDuringDialog_reportsMembershipChanged_noProceed() {
        // 對話框期間新裝分身：同一 delta 重探的 installed 集合比凍結快照多一筆。
        val h = FenceHarness(
            reprobe = DualProbeResult.Ok(listOf("com.a.app", "com.b.app")),
            capturedInstalled = setOf("com.a.app"),
        )
        val outcome = runBlocking { h.continueCheck() }
        assertTrue(outcome is PreClearContinueOutcome.CloneMembershipChanged)
        assertEquals(1, h.reprobeCalls)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.cancelCalls)
        assertEquals(0, h.gw.calls)
    }

    @Test
    fun preClearContinue_reprobeFails_reportsCannotDetermine_noProceed() {
        // Failed 結果（truncated／marker 矛盾等）與例外／取消皆收斂 CannotDetermine。
        val h = FenceHarness(reprobe = DualProbeResult.Failed("probe-rc: 1"))
        val outcome = runBlocking { h.continueCheck() }
        assertTrue(outcome is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.cancelCalls)

        val h2 = FenceHarness()
        h2.reprobeError = CancellationException("reprobe-cancel-seam")
        val outcome2 = runBlocking { h2.continueCheck() }
        assertTrue("cancellation must fail closed", outcome2 is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h2.proceedCalls)
        assertEquals(0, h2.storeWrites)
        assertEquals(0, h2.gw.calls)
    }

    @Test
    fun preClearContinue_capturedNull_abortsNoProceed() {
        // 警告在沒有 verified target 的情況下顯示 ⇒ 無從重驗，直接 fail closed。
        val h = FenceHarness(captured = null)
        val outcome = runBlocking { h.continueCheck() }
        assertTrue(outcome is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h.reprobeCalls)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.cancelCalls)
        assertEquals(0, h.gw.calls)
    }

    // ---- D03a：consent 適配器（复用 revalidatePreClearContinue 的四道關卡）----

    @Test
    fun consent_proceedOk_control() {
        // 對照組：身份一致、installed 未變 → ProceedOk（唯一放行態）。
        val h = FenceHarness()
        val outcome = runBlocking { h.consentCheck(testConsent()) }
        assertTrue(outcome is PreClearContinueOutcome.ProceedOk)
        assertEquals(1, h.proceedCalls)
        assertEquals(1, h.storeWrites)
        assertEquals(1, h.reprobeCalls)
        assertEquals(0, h.cancelCalls)
    }

    @Test
    fun consent_okEmptyThenNewClone_failsClosed() {
        // Ok(empty) 警告路徑捕獲空 installed（合法）；對話框後重探卻多一筆 → 攔。
        val h = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.a.app")))
        val outcome = runBlocking { h.consentCheck(testConsent(installed = emptySet())) }
        assertTrue(outcome is PreClearContinueOutcome.CloneMembershipChanged)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.reprobeCalls)
        assertEquals(1, h.cancelCalls)
    }

    @Test
    fun consent_serialChange_failsClosed() {
        // 同 id 換 serial（delete+recreate）：fresh 身分失配 → TargetChanged。
        val h = FenceHarness(fresh = VerifiedTarget(95, 8, 0))
        val outcome = runBlocking { h.consentCheck(testConsent(serial = "7")) }
        assertTrue(outcome is PreClearContinueOutcome.TargetChanged)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(0, h.gw.calls)
        assertEquals(1, h.cancelCalls)
    }

    @Test
    fun consent_probeFailure_failsClosed() {
        // 重探Failed → CannotDetermine（不寫入）。
        val h = FenceHarness(reprobe = DualProbeResult.Failed("probe-rc: 1"))
        val outcome = runBlocking { h.consentCheck(testConsent()) }
        assertTrue(outcome is PreClearContinueOutcome.CannotDetermine)
        assertEquals(1, h.reprobeCalls)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.cancelCalls)
    }

    @Test
    fun consent_nullIdentity_failsClosed() {
        // null id/serial ⇒ 無法重建捕獲身份，adapter guard 直接 CannotDetermine
        // （zero reprobe／zero write）。
        val h1 = FenceHarness()
        val outcome1 = runBlocking { h1.consentCheck(testConsent(userId = null)) }
        assertTrue(outcome1 is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h1.reprobeCalls)
        assertEquals(0, h1.proceedCalls)
        assertEquals(0, h1.storeWrites)
        assertEquals(1, h1.cancelCalls)

        val h2 = FenceHarness()
        val outcome2 = runBlocking { h2.consentCheck(testConsent(serial = null)) }
        assertTrue(outcome2 is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h2.reprobeCalls)
        assertEquals(0, h2.proceedCalls)
        assertEquals(0, h2.storeWrites)
        assertEquals(1, h2.cancelCalls)
    }

    // ---- D03b PART 1：最終 confirm 的四態派遣器（framework-free，JVM 可測，無 Robolectric）----

    private class DispatchProbe(hostAlive: Boolean = true) {
        // AtomicBoolean so hostAlive is genuinely re-evaluated at dispatch time
        // (can be flipped between computing the outcome and dispatching it).
        val alive = AtomicBoolean(hostAlive)
        var confirms = 0
        var revokes = 0
        var notifies = 0

        suspend fun run(outcome: PreClearContinueOutcome) = dispatchConsentGatedConfirm(
            outcome = outcome,
            confirm = { confirms++ },
            revoke = { revokes++ },
            notify = { notifies++ },
            hostAlive = { alive.get() },
        )
    }

    @Test
    fun confirmDispatch_proceedOk_callsConfirmOnce() = runBlocking {
        // 唯一放行態：ProceedOk + host 存活 ⇒ confirm 恰一次，零 revoke／零 notify。
        val p = DispatchProbe(hostAlive = true)
        p.run(PreClearContinueOutcome.ProceedOk)
        assertEquals(1, p.confirms)
        assertEquals(0, p.revokes)
        assertEquals(0, p.notifies)
    }

    @Test
    fun confirmDispatch_targetChanged_revokesAndNotifies_noConfirm() = runBlocking {
        // 身份／新鮮度在警告↔confirm 間改變 ⇒ revoke+notify，零寫入。
        val p = DispatchProbe(hostAlive = true)
        p.run(PreClearContinueOutcome.TargetChanged)
        assertEquals(0, p.confirms)
        assertEquals(1, p.revokes)
        assertEquals(1, p.notifies)
    }

    @Test
    fun confirmDispatch_membershipChanged_revokesAndNotifies_noConfirm() = runBlocking {
        // 新裝分身讓 installed 集合改變 ⇒ revoke+notify，零寫入。
        val p = DispatchProbe(hostAlive = true)
        p.run(PreClearContinueOutcome.CloneMembershipChanged)
        assertEquals(0, p.confirms)
        assertEquals(1, p.revokes)
        assertEquals(1, p.notifies)
    }

    @Test
    fun confirmDispatch_cannotDetermine_revokesAndNotifies_noConfirm() = runBlocking {
        // 重探失敗／捕獲缺失 ⇒ CannotDetermine ⇒ revoke+notify，
        // 不再有「確認失敗仍強制放行」的靜默路徑（carry-forward #6）。
        val p = DispatchProbe(hostAlive = true)
        p.run(PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, p.confirms)
        assertEquals(1, p.revokes)
        assertEquals(1, p.notifies)
    }

    @Test
    fun confirmDispatch_hostGone_revokesNoConfirm() = runBlocking {
        // host 已 destroying ⇒ 即便 ProceedOk 也不 confirm。
        val p = DispatchProbe(hostAlive = false)
        p.run(PreClearContinueOutcome.ProceedOk)
        assertEquals(0, p.confirms)
        assertEquals(1, p.revokes)
        assertEquals(1, p.notifies)
    }

    @Test
    fun confirmDispatch_hostGoneBeforeDispatch_noConfirm() = runBlocking {
        // Outcome computed while host alive, host gone at dispatch time.
        // dispatchConsentGatedConfirm has NO suspension point, so the flip below
        // happens BEFORE dispatch: hostAlive() is re-evaluated as false and the
        // ProceedOk outcome is refused (a stale host must not be allowed through).
        // 結果在 host 仍活時算出，host 已於 dispatch 前消失（helper 無 suspension
        // point）⇒ dispatch 當下重新求值 hostAlive 為 false，零 confirm。
        val p = DispatchProbe(hostAlive = true)
        val outcomeBeforeDispatch = PreClearContinueOutcome.ProceedOk
        assertTrue(p.alive.get())
        p.alive.set(false) // host 在 dispatch 前消失
        assertFalse(p.alive.get())
        p.run(outcomeBeforeDispatch)
        assertEquals(0, p.confirms)
        assertEquals(1, p.revokes)
        assertEquals(1, p.notifies)
    }

    @Test
    fun reentrantWarningRequest_doesNotResetConsentWhenPendingClear() {
        // pendingClearConfirm=true（probe／對話框存活期）的 re-entrant 要求不得清空 consent。
        assertFalse(consentResetAllowedForNewWarning(true))
        // 真正的新警告（非 re-entrant）才清空。
        assertTrue(consentResetAllowedForNewWarning(false))
        // 限制：Activity 私欄位 pendingWarningConsent 不可從 JVM 觀測；此純函數鎖住
        // 「是否允許重置」語義，兩個 Activity 的 reset 皆改走此 guard（carry-forward #2）。
    }

    // ---- D03b FIXER M1/M2/M3/M4/M5/M6：回歸防護 ----

    @Test
    fun consent_fromFailedProbe_neverProceeds_evenIfReprobeSucceeds() = runBlocking {
        // M6：警告 probe  Failed 的 consent（force-proceed）即便重探成功也不可放行；
        // 必須在任何 seam（含 reprobe）之前 fail closed，零寫入。
        val h = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.a.app")))
        val outcome = h.consentCheck(testConsent(fromFailedProbe = true))
        assertTrue(outcome is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h.reprobeCalls)
        assertEquals(0, h.proceedCalls)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.cancelCalls)
    }

    @Test
    fun consent_rootVerifiedEmpty_skipsOnlyMembership_identityStillEnforced() = runBlocking {
        // M2a：身分一致但 installed 集合已變（卸載後合法為空）⇒ 只跳 membership ⇒ ProceedOk。
        val h1 = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.a.app", "com.b.app")))
        val out1 = h1.consentCheck(testConsent(installed = setOf("com.a.app"), rootVerifiedEmpty = true))
        assertTrue(out1 is PreClearContinueOutcome.ProceedOk)
        assertEquals(1, h1.proceedCalls)

        // M2b：fresh gateway (id,serial) 改動（同 id 換 serial）⇒ 仍 TargetChanged。
        val h2 = FenceHarness(fresh = VerifiedTarget(95, 8, 0))
        val out2 = h2.consentCheck(testConsent(rootVerifiedEmpty = true))
        assertTrue(out2 is PreClearContinueOutcome.TargetChanged)
        assertEquals(0, h2.proceedCalls)

        // M2c：live (id,serial) remap ⇒ 仍 TargetChanged（沒有 ROOT⇒ProceedOk 捷徑）。
        val h3 = FenceHarness(live = PreClearDualTarget(96, 7))
        val out3 = h3.consentCheck(testConsent(rootVerifiedEmpty = true))
        assertTrue(out3 is PreClearContinueOutcome.TargetChanged)
        assertEquals(0, h3.proceedCalls)
    }

    @Test
    fun buildFastPathConsent_deltaEmpty_buildsRealConsentAndFencePasses() = runBlocking {
        // M1/M3：空草稿清除／delta-empty 寫入現場建的是真實 consent（fresh resolve＋probe），
        // 不是 null blanket bypass；空 delta 兩次 probe 皆空 ⇒ membership 相等 ⇒ ProceedOk。
        var probeCalls = 0
        val consent = buildFastPathConsent(
            delta = emptySet(),
            resolveLiveTarget = { PreClearDualTarget(95, 7) },
            reprobe = { pkgs, uid ->
                probeCalls++
                assertTrue("delta must be empty", pkgs.isEmpty())
                assertEquals(95, uid)
                DualProbeResult.Ok(emptyList())
            },
        )
        assertEquals(emptySet<String>(), consent.delta)
        assertEquals(95, consent.capturedUserId)
        assertEquals("7", consent.capturedSerial)
        assertEquals(emptySet<String>(), consent.capturedInstalled)
        assertFalse(consent.fromFailedProbe)
        assertFalse(consent.rootVerifiedEmpty)
        assertEquals(1, probeCalls)

        // 把建出的 consent 真正餵进 fence（單次呼叫，無 restart callback ⇒ 無遞迴）。
        val h = FenceHarness(reprobe = DualProbeResult.Ok(emptyList()))
        val outcome = h.consentCheck(consent)
        assertTrue(outcome is PreClearContinueOutcome.ProceedOk)
        assertEquals(1, h.reprobeCalls) // reprobe 恰一次，無重入
    }

    @Test
    fun buildFastPathConsent_failedProbe_marksFlagAndFailsClosed() = runBlocking {
        // M1/M6：fast path 的 probe 失敗 ⇒ fromFailedProbe=true ⇒ fence CannotDetermine，
        // 不會把「探測失敗」偷偷當成 ProceedOk。
        val consent = buildFastPathConsent(
            delta = setOf("com.a.app"),
            resolveLiveTarget = { PreClearDualTarget(95, 7) },
            reprobe = { _, _ -> DualProbeResult.Failed("probe-rc: 1") },
        )
        assertTrue(consent.fromFailedProbe)
        val h = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.a.app")))
        val outcome = h.consentCheck(consent)
        assertTrue(outcome is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h.reprobeCalls)
        assertEquals(0, h.storeWrites)
    }

    // ---- R4 (E02)：additive/no-removal 與破壞性操作的 fences 分離 ----

    @Test
    fun buildFastPathConsent_additiveNoTarget_fenceProceeds() = runBlocking {
        // KNOWN 不移除 + 空 delta + 無 verified target ⇒ additiveNoRemoval；
        // 即使 fast-path reprobe 回 Failed 也不標 fromFailedProbe。fence 兩條件守衛
        // 直接放行（identity/membership 對可證明不移除的操作不適用）：零 reprobe、一次寫入。
        var buildProbeCalls = 0
        val consent = buildFastPathConsent(
            delta = emptySet(),
            resolveLiveTarget = { null },
            reprobe = { _, _ ->
                buildProbeCalls++
                DualProbeResult.Failed("exec-failed")
            },
            noRemovalKnown = true,
        )
        assertTrue(consent.additiveNoRemoval)
        assertFalse(consent.fromFailedProbe)
        assertNull(consent.capturedUserId)
        assertEquals(1, buildProbeCalls) // probe seam 保留做可觀測性

        val h = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.a.app")))
        val outcome = h.consentCheck(consent)
        assertTrue(outcome is PreClearContinueOutcome.ProceedOk)
        assertEquals(1, h.storeWrites)
        assertEquals(0, h.reprobeCalls)
    }

    @Test
    fun buildFastPathConsent_unknownSnapshot_neverAdditive_evenWhenDeltaEmpty() = runBlocking {
        // 對照組：noRemovalKnown=false（Unknown snapshot）⇒ 永不 additive；
        // fast-path reprobe Failed ⇒ fromFailedProbe=true ⇒ fence CannotDetermine，零寫入。
        val consent = buildFastPathConsent(
            delta = emptySet(),
            resolveLiveTarget = { null },
            reprobe = { _, _ -> DualProbeResult.Failed("exec-failed") },
            noRemovalKnown = false,
        )
        assertFalse(consent.additiveNoRemoval)
        assertTrue(consent.fromFailedProbe)
        val h = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.a.app")))
        val outcome = h.consentCheck(consent)
        assertTrue(outcome is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h.storeWrites)
        assertEquals(0, h.reprobeCalls)
    }

    @Test
    fun buildFastPathConsent_flagOnUnknownSnapshot_neverAdditive() = runBlocking {
        // R4-1 (HARD CONSTRAINT) D03 fail-open regression: an Unknown snapshot
        // computes delta=∅ only as an artifact of the failed re-read. The production
        // call-site model is noRemovalAllowed = snapKnown && delta.isEmpty(), so it
        // MUST refuse to opt in even though delta looks empty.
        val snapKnown = false
        val delta: Set<String> = emptySet() // Unknown-style seam: stale/empty draft, not authoritative
        assertFalse(noRemovalAllowed(snapKnown, delta))
        assertTrue(noRemovalAllowed(true, delta)) // Known control group with the same empty delta

        // Candidate-empty pitfall (write path): candidate=∅ ⇒ delta=old, so a
        // non-empty persisted draft is destructive and must never opt in merely
        // because the candidate is empty.
        assertFalse(noRemovalAllowed(true, setOf("com.a.app")))

        // Feed the *gated* value into the real builder: no additive flag; the
        // failed-path reprobe keeps fromFailedProbe=true and the fence fails closed.
        val noRemovalKnown = noRemovalAllowed(snapKnown, delta)
        val consent = buildFastPathConsent(
            delta = delta,
            resolveLiveTarget = { null },
            reprobe = { _, _ -> DualProbeResult.Failed("exec-failed") },
            noRemovalKnown = noRemovalKnown,
        )
        assertFalse(consent.additiveNoRemoval)
        assertTrue(consent.fromFailedProbe)
        val h = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.a.app")))
        val outcome = h.consentCheck(consent)
        assertTrue(outcome is PreClearContinueOutcome.CannotDetermine)
        assertEquals(0, h.storeWrites)
        assertEquals(0, h.reprobeCalls)
    }

    @Test
    fun consent_additiveFlagOnDestructiveDelta_neverBypassesFence() = runBlocking {
        // 偽造旗標防禦：破壞性（非空）delta 即使帶 additiveNoRemoval=true，
        // fence 交叉檢查 delta.isEmpty() 失敗 ⇒ 仍走完整四道關卡；installed 集合
        // 改變 ⇒ CloneMembershipChanged，零寫入。
        val h = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.b.app")))
        val outcome = h.consentCheck(
            testConsent(delta = setOf("com.a.app"), additiveNoRemoval = true, fromFailedProbe = false)
        )
        assertTrue(outcome is PreClearContinueOutcome.CloneMembershipChanged)
        assertEquals(0, h.storeWrites)
        assertEquals(1, h.reprobeCalls)
    }

    @Test
    fun consent_additiveFlag_ignoredWhenRootOverrideSet() = runBlocking {
        // 明確優先序：additive 分支在 fromFailedProbe 之後、身份重建之前，故
        // additiveNoRemoval + rootVerifiedEmpty 同設且 delta 空 ⇒ additive 先放行
        //（ProceedOk、零 reprobe），root 覆寫未被動用到（沒有新的 bypass）。
        val h1 = FenceHarness(reprobe = DualProbeResult.Ok(listOf("com.b.app")))
        val out1 = h1.consentCheck(
            testConsent(delta = emptySet(), additiveNoRemoval = true, rootVerifiedEmpty = true)
        )
        assertTrue(out1 is PreClearContinueOutcome.ProceedOk)
        assertEquals(0, h1.reprobeCalls)
        assertEquals(1, h1.storeWrites)

        // 破壞性 delta 時 additive 旗標被忽略，root 覆寫仍「只」跳 membership，
        // 身分失配照樣 TargetChanged ⇒ 旗標組合沒有產生新 bypass。
        val h2 = FenceHarness(live = PreClearDualTarget(96, 7))
        val out2 = h2.consentCheck(
            testConsent(delta = setOf("com.a.app"), additiveNoRemoval = true, rootVerifiedEmpty = true)
        )
        assertTrue(out2 is PreClearContinueOutcome.TargetChanged)
        assertEquals(0, h2.proceedCalls)
        assertEquals(0, h2.storeWrites)
    }

    @Test
    fun inFlightGuard_dropsSecondConfirm() {
        // M5：double-tap——第二段 confirm 在 in-flight 時被丟棄，完成後可再用。
        val g = InFlightGuard()
        assertTrue(g.tryEnter())
        assertFalse(g.tryEnter())
        g.exit()
        assertTrue(g.tryEnter())
    }

    @Test
    fun oneShotRestartGuard_dropsReentrantSchedule() {
        // M3：abort→restart→再 abort 的同步遞迴被 one-shot guard 擋下。
        val g = OneShotRestartGuard()
        assertTrue(g.trySchedule())
        assertFalse(g.trySchedule())
        g.reset()
        assertTrue(g.trySchedule())
    }

    @Test
    fun autoRestartCap_allowsOneThenStops_untilUserReTap() {
        // Availability contract：每次使用者真正點入口只自動重警 1 次；結構性
        // fail-closed 原因（空草稿 + 目標不存在／probe 持續失敗）否則會跨
        // Handler post 無限循環。Drive the LIVE controller: the old helper-only
        // assertion (`canAutoRestart`) no longer reflects the shipped budget,
        // which now lives in WarningRestartController per accepted request.
        assertEquals(1, MAX_AUTO_WARNING_RESTARTS)
        assertTrue(canAutoRestart(0)) // pure spec of the cap (kept as documentation)
        assertFalse(canAutoRestart(1))
        assertFalse(canAutoRestart(2))

        val controller = WarningRestartController()
        val posted = mutableListOf<Pair<Long, Long>>()
        val post: (Long, Long, () -> Unit) -> Unit = { tokenId, requestId, _ ->
            posted.add(tokenId to requestId)
        }

        val first = controller.install(
            action = WarningRestartAction.CLEAR,
            candidateKey = null,
            generation = 1,
            callback = {},
            resetBudget = true, // genuine entry tap
        )
        assertTrue("first auto restart allowed", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, post))
        assertFalse("cap reached: user must re-tap the entry", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, post))
        assertEquals(1, posted.size)

        // A genuine user entry tap re-arms the budget for the new request.
        val second = controller.install(
            action = WarningRestartAction.CLEAR,
            candidateKey = null,
            generation = 2,
            callback = {},
            resetBudget = true,
        )
        assertTrue(second.identity.id > first.identity.id)
        assertTrue("fresh tap restores the budget", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, post))
        assertEquals(2, posted.size)
    }

    @Test
    fun confirmDispatch_callbackException_isContained() = runBlocking {
        // M4：confirm 拋錯不得逸出，且要補做 revoke+notify（fail-closed 清理）。
        var revokes = 0
        var notifies = 0
        dispatchConsentGatedConfirm(
            outcome = PreClearContinueOutcome.ProceedOk,
            confirm = { throw IllegalStateException("confirm boom") },
            revoke = { revokes++ },
            notify = { notifies++ },
            hostAlive = { true },
        )
        assertEquals(1, revokes)
        assertEquals(1, notifies)

        // revoke / notify 自身拋錯同样不得逸出。
        dispatchConsentGatedConfirm(
            outcome = PreClearContinueOutcome.CannotDetermine,
            confirm = { fail("confirm must not run for non-ProceedOk") },
            revoke = { throw IllegalStateException("revoke boom") },
            notify = { throw IllegalStateException("notify boom") },
            hostAlive = { true },
        )
    }
}
