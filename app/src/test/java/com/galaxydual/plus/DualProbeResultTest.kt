// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase B: probeDualAppsInUser95 -> DualProbeResult (Ok/Failed) JVM matrix.
// No Android runtime / su / pm / device: all via CmdGateway seam fake gateway;
// verifies exit!=0 / stderr / Failure lines / truncation (flag, [truncated] marker,
// over-cap clamp), gateway throws all map to Failed; only clean output is Ok.
// CancellationException still propagates.
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.Privilege
import com.galaxydual.core.TRUNCATION_MARKER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DualProbeResultTest {

    private class FakeGateway(
        private val result: CmdResult? = null,
        private val error: Throwable? = null,
    ) : CmdGateway {
        var calls = 0
        var lastCmd: String? = null
        override val privilege: Privilege = Privilege.ROOT
        override suspend fun run(cmd: String): CmdResult {
            calls++
            lastCmd = cmd
            error?.let { throw it }
            return result ?: CmdResult(exit = 0, out = "", err = "")
        }
    }

    private fun probe(pkgs: Collection<String>, gw: CmdGateway): DualProbeResult =
        runBlocking { probeDualAppsInUser95(pkgs, gw) }

    @Test
    fun emptyInput_returnsOkEmptyWithoutGatewayCall() {
        val gw = FakeGateway(CmdResult(exit = 1, out = "INST x", err = "boom"))
        val res = probe(emptyList(), gw)
        assertTrue(res is DualProbeResult.Ok)
        assertEquals(emptyList<String>(), (res as DualProbeResult.Ok).installed)
        assertEquals(0, gw.calls)
    }

    @Test
    fun cleanOutput_returnsOkInstalled() {
        // Phase1 review(P2): requested set requires a PROBE_RC line.
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nINST com.a.app\nINST com.b.app\n", err = ""))
        val res = probe(listOf("com.a.app", "com.b.app"), gw)
        assertTrue(res is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app", "com.b.app"), (res as DualProbeResult.Ok).installed)
    }

    @Test
    fun missingProbeRc_mapsToFailed() {
        // Phase1 review(P2): all markers present but no PROBE_RC => Failed (not Ok).
        val res = decideDualProbeResult(
            CmdResult(0, "CLEAN com.a.app\n", ""),
            setOf("com.a.app"),
        )
        assertTrue("missing PROBE_RC must be Failed, got: $res", res is DualProbeResult.Failed)
        assertEquals("probe-rc: missing", (res as DualProbeResult.Failed).reason)
        // Legacy pure seam (no requested set) still tolerates absent PROBE_RC.
        val legacy = decideDualProbeResult(CmdResult(0, "INST com.a.app\n", ""))
        assertTrue(legacy is DualProbeResult.Ok)
    }

    @Test
    fun cleanEmptyOutput_returnsOkEmpty() {
        // R02: Ok(empty) only when every requested pkg has a CLEAN marker.
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nCLEAN com.a.app\n", err = ""))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue(res is DualProbeResult.Ok)
        assertEquals(emptyList<String>(), (res as DualProbeResult.Ok).installed)
    }

    @Test
    fun missingMarker_mapsToFailed() {
        // Requested com.b.app has no marker at all -> FAIL (never Ok(empty)).
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nCLEAN com.a.app\n", err = ""))
        val res = probe(listOf("com.a.app", "com.b.app"), gw)
        assertTrue("missing marker must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("missing-marker:"))
        assertEquals(1, gw.calls)
    }

    @Test
    fun injectedInstNotRequested_isDropped() {
        // F4 cross-check: INST evil.pkg is not in requested set -> dropped;
        // com.a.app is CLEAN -> Ok(empty) with no leaked marker token.
        val gw = FakeGateway(
            CmdResult(exit = 0, out = "PROBE_RC=0\nCLEAN com.a.app\nINST evil.pkg\n", err = ""),
        )
        val res = probe(listOf("com.a.app"), gw)
        assertTrue(res is DualProbeResult.Ok)
        assertEquals(emptyList<String>(), (res as DualProbeResult.Ok).installed)
    }

    @Test
    fun ambiguousMarker_mapsToFailed() {
        val res = decideDualProbeResult(
            CmdResult(0, "PROBE_RC=0\nINST com.a.app\nCLEAN com.a.app\n", ""),
            setOf("com.a.app"),
        )
        assertTrue("contradictory markers must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("ambiguous-marker:"))
    }

    @Test
    fun failMarkerAndProbeRc_mapsToFailed() {
        val failMarker = decideDualProbeResult(
            CmdResult(0, "PROBE_RC=0\nCLEAN com.a.app\nFAIL com.b.app\n", ""),
            setOf("com.a.app", "com.b.app"),
        )
        assertTrue(failMarker is DualProbeResult.Failed)
        assertTrue((failMarker as DualProbeResult.Failed).reason.startsWith("probe-failed:"))
        val rc = decideDualProbeResult(
            CmdResult(0, "PROBE_RC=1\nCLEAN com.a.app\n", ""),
            setOf("com.a.app"),
        )
        assertTrue(rc is DualProbeResult.Failed)
        assertTrue((rc as DualProbeResult.Failed).reason.startsWith("probe-rc:"))
    }

    @Test
    fun invalidTokensFiltered_stillOk() {
        // Invalid tokens are dropped by isValidPackage but the probe stays trustworthy -> Ok.
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nINST com.a.app\nINST bad token!\n", err = ""))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("invalid tokens must be filtered but stay Ok, got: $res", res is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app"), (res as DualProbeResult.Ok).installed)
    }

    @Test
    fun gatewayException_mapsToFailed() {
        val gw = FakeGateway(error = IllegalStateException("su boom"))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue(res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("exec-failed:"))
    }

    @Test
    fun nonZeroExit_mapsToFailed() {
        val gw = FakeGateway(CmdResult(exit = 1, out = "INST com.a.app\n", err = ""))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("exit!=0 must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("exit="))
    }

    @Test
    fun stderrNonBlank_mapsToFailed() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = "permission denied"))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("stderr must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("stderr:"))
    }

    @Test
    fun failureLine_mapsToFailed() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", err = ""))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("Failure line must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("failure-line:"))
    }

    @Test
    fun truncatedFlag_mapsToFailed() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "INST com.a.app\n", err = "", truncated = true))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("truncated flag must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("truncated:"))
    }

    @Test
    fun truncationMarker_mapsToFailed() {
        val gw = FakeGateway(
            CmdResult(exit = 0, out = "INST com.a.app\n$TRUNCATION_MARKER\n", err = ""),
        )
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("marker must be Failed, got: $res", res is DualProbeResult.Failed)
    }

    @Test
    fun timedOut_mapsToFailed() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "INST com.a.app\n", err = "", timedOut = true))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("timedOut must be Failed, got: $res", res is DualProbeResult.Failed)
    }

    @Test
    fun transportUnknown_mapsToFailed() {
        val gw = FakeGateway(
            CmdResult(exit = 0, out = "INST com.a.app\n", err = "su failed post-spawn: boom", transportUnknown = true),
        )
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("transportUnknown must be Failed, got: $res", res is DualProbeResult.Failed)
    }

    @Test
    fun cappedInput_mapsToFailedWithoutGatewayCall() {
        // Over-cap input is a partial probe (build takes only first N): Failed directly, no gateway call.
        val big = (1..MAX_DUAL_PROBE_PKGS + 1).map { "com.example.p$it" }
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = ""))
        val res = probe(big, gw)
        assertTrue("capped input must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("truncated:"))
        assertEquals(0, gw.calls)
    }

    @Test
    fun cancellation_stillPropagates() {
        val gw = FakeGateway(error = CancellationException("cancel-seam"))
        try {
            probe(listOf("com.a.app"), gw)
            fail("CancellationException must propagate, not map to Failed")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test
    fun decidePure_exitStderrFailureTruncatedAllFailed() {
        assertTrue(decideDualProbeResult(CmdResult(1, "", "")) is DualProbeResult.Failed)
        assertTrue(decideDualProbeResult(CmdResult(0, "", "x")) is DualProbeResult.Failed)
        assertTrue(decideDualProbeResult(CmdResult(0, "Failure oops", "")) is DualProbeResult.Failed)
        assertTrue(decideDualProbeResult(CmdResult(0, "INST a", "", truncated = true)) is DualProbeResult.Failed)
        val ok = decideDualProbeResult(CmdResult(0, "INST com.a.app\n", ""))
        assertTrue(ok is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app"), (ok as DualProbeResult.Ok).installed)
    }

    // Critical: MAX boundary (512) stays Ok with exactly one gateway call.
    @Test
    fun maxBoundary_512_returnsOkWithOneCall() {
        val pkgs = (1..MAX_DUAL_PROBE_PKGS).map { "com.example.p$it" }
        val cleanLines = pkgs.joinToString("\n") { "CLEAN $it" }
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\n$cleanLines\n", err = ""))
        val res = probe(pkgs, gw)
        assertTrue("MAX boundary must be Ok, got: $res", res is DualProbeResult.Ok)
        assertEquals(1, gw.calls)
        assertTrue("probe cmd must target user 95", (gw.lastCmd ?: "").contains("--user 95"))
    }

    // Critical: INST mixed with a Failure line is still Failed (fail-closed).
    @Test
    fun instMixedWithFailureLine_mapsToFailed() {
        val gw = FakeGateway(
            CmdResult(exit = 0, out = "INST com.a.app\nFailure [DELETE_FAILED_INTERNAL_ERROR]\n", err = ""),
        )
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("INST+Failure mix must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("failure-line:"))
    }

    // Critical: marker on the err side also fails closed.
    @Test
    fun errSideMarker_mapsToFailed() {
        val gw = FakeGateway(
            CmdResult(exit = 0, out = "INST com.a.app\n", err = "partial\n$TRUNCATION_MARKER"),
        )
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("err-side marker must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("truncated:"))
    }

    // Critical: whitespace-only stderr is blank -> Ok.
    @Test
    fun whitespaceStderr_returnsOk() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nINST com.a.app\n", err = "   "))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue("whitespace stderr must be Ok, got: $res", res is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app"), (res as DualProbeResult.Ok).installed)
    }

    // Critical: DELETE_FAILED case / whitespace variants all fail closed.
    @Test
    fun deleteFailedVariants_allMapToFailed() {
        val variants = listOf(
            "DELETE_FAILED_INTERNAL_ERROR",
            "delete_failed_internal_error",
            "  Delete_Failed_Internal_Error  ",
            "  FAILURE [DELETE_FAILED_INTERNAL_ERROR]",
            "  failure: something broke",
            "Failure: boom",
        )
        for (v in variants) {
            val res = decideDualProbeResult(CmdResult(exit = 0, out = "$v\n", err = ""))
            assertTrue("variant must be Failed: [$v] got: $res", res is DualProbeResult.Failed)
            assertTrue((res as DualProbeResult.Failed).reason.startsWith("failure-line:"))
        }
    }

    // High: every Failed reason carries its stable prefix.
    @Test
    fun reasonPrefixes_allStable() {
        val cases = listOf(
            decideDualProbeResult(CmdResult(0, "x", "", timedOut = true)) to "timeout:",
            decideDualProbeResult(CmdResult(0, "x", "boom", transportUnknown = true)) to "transport-unknown:",
            decideDualProbeResult(CmdResult(0, "x", "", truncated = true)) to "truncated:",
            decideDualProbeResult(CmdResult(1, "", "boom")) to "exit=",
            decideDualProbeResult(CmdResult(0, "", "boom")) to "stderr:",
            decideDualProbeResult(CmdResult(0, "Failure boom", "")) to "failure-line:",
        )
        for ((res, prefix) in cases) {
            assertTrue(res is DualProbeResult.Failed)
            assertTrue("reason [$res] must start with [$prefix]", (res as DualProbeResult.Failed).reason.startsWith(prefix))
        }
        val gw = FakeGateway(error = IllegalStateException("boom"))
        val exec = probe(listOf("com.a.app"), gw)
        assertTrue(exec is DualProbeResult.Failed)
        assertTrue((exec as DualProbeResult.Failed).reason.startsWith("exec-failed:"))
    }

    // High: priority order is timeout > transportUnknown > truncated > exit > stderr > failure-line.
    @Test
    fun priorityCombo_timeoutWinsOverAll() {
        val res = decideDualProbeResult(
            CmdResult(
                exit = 1,
                out = "Failure boom\n$TRUNCATION_MARKER",
                err = "boom",
                truncated = true,
                timedOut = true,
                transportUnknown = true,
            ),
        )
        assertTrue(res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("timeout:"))
        val res2 = decideDualProbeResult(
            CmdResult(exit = 1, out = "Failure boom", err = "boom", transportUnknown = true),
        )
        assertTrue((res2 as DualProbeResult.Failed).reason.startsWith("transport-unknown:"))
        val res3 = decideDualProbeResult(
            CmdResult(exit = 1, out = "Failure boom", err = "boom", truncated = true),
        )
        assertTrue((res3 as DualProbeResult.Failed).reason.startsWith("truncated:"))
    }

    // High: dirty input (whitespace / blanks / dupes / invalid) is sanitized, valid still probes.
    @Test
    fun dirtyInput_sanitizedAndProbesValid() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nINST com.a.app\nCLEAN com.b.app\n", err = ""))
        val res = probe(listOf("  com.a.app  ", "", "   ", "com.a.app", "bad pkg!", "com.b.app"), gw)
        assertTrue("dirty input must stay Ok for valid subset, got: $res", res is DualProbeResult.Ok)
        assertEquals(1, gw.calls)
        assertTrue((gw.lastCmd ?: "").contains("--user 95"))
    }

    // High: probe gateway command always targets the passed (test-wrapper) user 95.
    @Test
    fun fakeGateway_cmdContainsUser95() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nCLEAN com.a.app\n", err = ""))
        probe(listOf("com.a.app"), gw)
        assertTrue((gw.lastCmd ?: "").contains("--user 95"))
        assertTrue((gw.lastCmd ?: "").contains("pm list packages"))
    }

    // Medium: decide is a pure split seam (no gateway touch).
    @Test
    fun decideSplit_pureNoGateway() {
        val ok = decideDualProbeResult(CmdResult(exit = 0, out = "INST com.a.app\n", err = ""))
        assertTrue(ok is DualProbeResult.Ok)
        val failed = decideDualProbeResult(CmdResult(exit = 1, out = "", err = "x"))
        assertTrue(failed is DualProbeResult.Failed)
    }

    // Medium: reasons stay bounded (take(200)).
    @Test
    fun reasonBounded_longErrCapped() {
        val long = "x".repeat(500)
        val res = decideDualProbeResult(CmdResult(exit = 1, out = "", err = long))
        assertTrue(res is DualProbeResult.Failed)
        val reason = (res as DualProbeResult.Failed).reason
        assertTrue("reason must stay bounded, len=${reason.length}", reason.length <= 220)
        val res2 = decideDualProbeResult(CmdResult(exit = 0, out = "Failure ${"y".repeat(500)}", err = ""))
        assertTrue((res2 as DualProbeResult.Failed).reason.length <= 220)
    }

    // Medium: exec-failed falls back to class name when message is null.
    @Test
    fun execFailedFallback_nullMessageUsesClassName() {
        val gw = FakeGateway(error = RuntimeException(null as String?))
        val res = probe(listOf("com.a.app"), gw)
        assertTrue(res is DualProbeResult.Failed)
        val reason = (res as DualProbeResult.Failed).reason
        assertTrue(reason.startsWith("exec-failed:"))
        assertTrue(reason.length > "exec-failed: ".length)
    }

    // Timeout with empty err falls back to unknown.
    @Test
    fun timeoutEmptyErr_givesUnknown() {
        val res = decideDualProbeResult(CmdResult(exit = 0, out = "", err = "", timedOut = true))
        assertTrue(res is DualProbeResult.Failed)
        assertEquals("timeout: unknown", (res as DualProbeResult.Failed).reason)
    }

    @Test
    fun exitBeatsStderrAndFailure() {
        val exitWins = decideDualProbeResult(CmdResult(1, "Failure boom", "boom"))
        assertTrue(exitWins is DualProbeResult.Failed)
        assertTrue((exitWins as DualProbeResult.Failed).reason.startsWith("exit="))
        val stderrWins = decideDualProbeResult(CmdResult(0, "Failure boom", "boom"))
        assertTrue(stderrWins is DualProbeResult.Failed)
        assertTrue((stderrWins as DualProbeResult.Failed).reason.startsWith("stderr:"))
    }

    @Test
    fun allInvalidProbe_mapsToFailed() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "", err = ""))
        val res = probe(listOf("bad pkg!"), gw)
        assertTrue("all-invalid must be Failed, got: $res", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("failure-line:"))
        assertEquals(0, gw.calls)
    }

    @Test
    fun dirtyInput_cmdSanitized() {
        val gw = FakeGateway(CmdResult(exit = 0, out = "PROBE_RC=0\nINST com.a.app\nCLEAN com.b.app\n", err = ""))
        val res = probe(listOf("  com.a.app  ", "", "   ", "com.a.app", "bad pkg!", "com.b.app"), gw)
        assertTrue(res is DualProbeResult.Ok)
        val cmd = gw.lastCmd ?: ""
        assertTrue("cmd must not contain invalid token: $cmd", !cmd.contains("bad pkg!"))
        assertTrue("cmd must contain deduped pkgs: $cmd", cmd.contains("com.a.app com.b.app"))
    }

    @Test
    fun reasonBounded_allBranches() {
        val long = "z".repeat(500)
        val timeout = decideDualProbeResult(CmdResult(0, "x", long, timedOut = true))
        assertTrue((timeout as DualProbeResult.Failed).reason.length <= 220)
        val transport = decideDualProbeResult(CmdResult(0, "x", long, transportUnknown = true))
        assertTrue((transport as DualProbeResult.Failed).reason.length <= 220)
        val gw = FakeGateway(error = IllegalStateException(long))
        val exec = probe(listOf("com.a.app"), gw)
        assertTrue((exec as DualProbeResult.Failed).reason.length <= 220)
    }
}
