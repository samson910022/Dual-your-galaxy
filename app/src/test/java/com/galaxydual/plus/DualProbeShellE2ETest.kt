// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// R02 regression: REAL `sh -c` execution of buildDualProbeCommand with a fake
// `pm` planted at the front of PATH. No Android runtime / su / device needed —
// only a POSIX shell (precedent: GatewayAssemblyTest / SuCancellableTest).
//
// Covers the fail-open defect: a `pm path` permission error / unknown user /
// ROM output variant used to be classified "not installed" -> Ok(empty) ->
// no warning -> Samsung deletes duals silently. Now the deterministic
// `pm list packages --user <uid>` + PROBE_RC/INST/CLEAN/FAIL markers are used.
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class DualProbeShellE2ETest {

    // Creates a temp bin dir with an executable fake `pm` running `script`.
    private fun fakePmBin(script: String): File {
        val dir = Files.createTempDirectory("dual-probe-fakepm").toFile()
        val pm = File(dir, "pm")
        pm.writeText("#!/bin/sh\n$script\n", StandardCharsets.UTF_8)
        assertTrue("failed to make fake pm executable", pm.setExecutable(true))
        return dir
    }

    private fun runShell(cmd: String, binDir: File): CmdResult {
        val pb = ProcessBuilder("sh", "-c", cmd)
        pb.environment()["PATH"] = binDir.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: "")
        val proc = pb.start()
        // Drain both streams before waitFor (no pipe-deadlock; scripts are tiny).
        val out = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val err = proc.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exit = proc.waitFor()
        return CmdResult(exit, out, err)
    }

    private fun probeViaShell(
        pkgs: List<String>,
        userId: Int,
        script: String,
    ): Pair<DualProbeResult, CmdResult> {
        val bin = fakePmBin(script)
        try {
            val cmd = buildDualProbeCommand(pkgs, userId)
            val raw = runShell(cmd, bin)
            return decideDualProbeResult(raw, pkgs.toSet()) to raw
        } finally {
            File(bin, "pm").delete()
            bin.delete()
        }
    }

    // (a) fake pm prints "Permission denied" to stderr and exits 1 => Failed.
    @Test
    fun e2e_pmPermissionDenied_failsClosed() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app", "com.b.app"),
            95,
            "echo 'Permission denied' >&2; exit 1",
        )
        assertTrue("permission-denied must be Failed, got: $res / raw=$raw", res is DualProbeResult.Failed)
        assertFalse("never Ok(empty) on pm error", res is DualProbeResult.Ok)
    }

    // (b) fake pm prints a package: line but exits 1 => Failed (rc wins over text).
    @Test
    fun e2e_packageLineButNonZeroExit_failsClosed() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app", "com.b.app"),
            95,
            "echo 'package:/data/app/com.a.app/base.apk'; exit 1",
        )
        assertTrue("exit 1 + package: line must be Failed, got: $res / raw=$raw", res is DualProbeResult.Failed)
        assertFalse((raw.out.contains("INST")))
    }

    // (c) all packages confirmed absent (pm rc=0, no matching lines) => Ok(empty).
    @Test
    fun e2e_allAbsent_okEmpty() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app", "com.b.app"),
            95,
            "exit 0",
        )
        assertEquals("", raw.err)
        assertTrue("all-absent must be Ok, got: $res / raw=$raw", res is DualProbeResult.Ok)
        assertEquals(emptyList<String>(), (res as DualProbeResult.Ok).installed)
        assertTrue("shell must emit CLEAN markers", raw.out.contains("CLEAN com.a.app") && raw.out.contains("CLEAN com.b.app"))
    }

    // (d) mixed installed/absent => Ok(installed subset only).
    @Test
    fun e2e_mixedInstalledAbsent_okInstalledSubset() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app", "com.b.app"),
            95,
            "echo 'package:com.a.app'; exit 0",
        )
        assertTrue("mixed must be Ok, got: $res / raw=$raw", res is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app"), (res as DualProbeResult.Ok).installed)
        assertTrue(raw.out.contains("INST com.a.app"))
        assertTrue(raw.out.contains("CLEAN com.b.app"))
    }

    // Phase1 review(MEDIUM): CRLF in pm output used to pass the _BAD prefix check
    // but fail the exact grep -> false CLEAN -> Ok(empty) -> no warning (fail-open).
    @Test
    fun e2e_crlfPackageLine_stillDetectedInstalled() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app"),
            95,
            "printf 'package:com.a.app\\r\\n'; exit 0",
        )
        assertTrue("CRLF package line must be INST, got: $res / raw=$raw", res is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app"), (res as DualProbeResult.Ok).installed)
        assertTrue("emitted marker must reflect normalized data", raw.out.contains("INST com.a.app"))
    }

    // Phase1 review(LOW): _BAD 用 word-split（去掉行尾空白/tab）判 package:*，
    // 但引號版精確 grep 會因行尾空白落空 → 對已安裝雙開誤發 CLEAN（fail-open）。
    // 未引號 $_PN 展開的 grep 必須消耗同一份 token 集，仍判定 INST。
    @Test
    fun e2e_whitespacePaddedPackageLine_stillDetectedInstalled() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app"),
            95,
            "printf 'package:com.a.app \\n'; exit 0",
        )
        assertTrue("whitespace-padded package line must be INST, got: $res / raw=$raw", res is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app"), (res as DualProbeResult.Ok).installed)
        assertTrue("emitted marker must be INST, not CLEAN", raw.out.contains("INST com.a.app"))
        assertFalse("must not emit CLEAN for an installed dual", raw.out.contains("CLEAN com.a.app"))
    }

    // Same fail-open, mixed case: a whitespace-padded installed line plus an
    // absent package. Only the absent package may be CLEAN.
    @Test
    fun e2e_whitespacePaddedInstalledAndAbsent_mixedStillOk() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app", "com.b.app"),
            95,
            "printf 'package:com.a.app \\n'; exit 0",
        )
        assertTrue("mixed must be Ok, got: $res / raw=$raw", res is DualProbeResult.Ok)
        assertEquals(listOf("com.a.app"), (res as DualProbeResult.Ok).installed)
        assertTrue(raw.out.contains("INST com.a.app"))
        assertFalse(raw.out.contains("CLEAN com.a.app"))
        assertTrue(raw.out.contains("CLEAN com.b.app"))
    }

    // ROM error text on stdout with exit 0 must not be read as "not installed".
    @Test
    fun e2e_romErrorTextOnStdout_failsClosed() {
        val (res, raw) = probeViaShell(
            listOf("com.a.app"),
            95,
            "echo 'Error: unknown user 95'; exit 0",
        )
        assertTrue("non-package stdout must be Failed, got: $res / raw=$raw", res is DualProbeResult.Failed)
        assertFalse(raw.out.contains("CLEAN com.a.app"))
    }

    // Requested package with no marker (truncated output) => Failed.
    @Test
    fun e2e_missingMarker_failsClosed() {
        val (_, raw) = probeViaShell(
            listOf("com.a.app", "com.b.app"),
            95,
            "echo 'package:com.a.app'; exit 0",
        )
        val truncated = raw.copy(out = raw.out.substringBefore("CLEAN com.b.app"))
        val res = decideDualProbeResult(truncated, setOf("com.a.app", "com.b.app"))
        assertTrue("missing marker must be Failed, got: $res / raw=$truncated", res is DualProbeResult.Failed)
        assertTrue((res as DualProbeResult.Failed).reason.startsWith("missing-marker:"))
    }

    // Injected INST token not in requested set is dropped (F4 cross-check).
    @Test
    fun e2e_injectedInstNotRequested_isDropped() {
        val (_, raw) = probeViaShell(listOf("com.a.app"), 95, "exit 0")
        val injected = raw.copy(out = raw.out + "INST evil.pkg\n")
        val res = decideDualProbeResult(injected, setOf("com.a.app"))
        assertTrue(res is DualProbeResult.Ok)
        assertEquals(emptyList<String>(), (res as DualProbeResult.Ok).installed)
    }

    // R05/NEW-1 + Phase1 review(HIGH): verified id resolution (FRESH only) + ROOT button gating.
    @Test
    fun verifiedTargetResolution_andRootButtonGating() {
        val verified = UiState(
            inventory = InventorySnapshot(targetId = 101, targetSerial = 42, freshness = InventoryFreshness.FRESH),
        )
        val unverifiedSerial = UiState(
            inventory = InventorySnapshot(targetId = 101, targetSerial = null, freshness = InventoryFreshness.FRESH),
        )
        val unknownId = UiState(
            inventory = InventorySnapshot(targetId = -1, targetSerial = 42, freshness = InventoryFreshness.FRESH),
        )
        val verified95 = UiState(
            inventory = InventorySnapshot(targetId = 95, targetSerial = 7, freshness = InventoryFreshness.FRESH),
        )
        // STALE/UNKNOWN retain id+serial but must never authorize destructive actions.
        val stale = UiState(
            inventory = InventorySnapshot(targetId = 95, targetSerial = 7, freshness = InventoryFreshness.STALE),
        )

        assertEquals(101, resolvePreClearDualUserId(verified))
        assertEquals(null, resolvePreClearDualUserId(unverifiedSerial))
        assertEquals(null, resolvePreClearDualUserId(unknownId))
        assertEquals(null, resolvePreClearDualUserId(stale))
        // 95 is allowed only when it is the actual verified id AND fresh.
        assertEquals(95, resolvePreClearDualUserId(verified95))

        assertTrue(preClearRootUninstallAllowed(101))
        assertFalse(preClearRootUninstallAllowed(null))
        assertFalse(preClearRootUninstallAllowed(-1))
        assertFalse(preClearRootUninstallAllowed(0))
        assertFalse(preClearRootUninstallAllowed(resolvePreClearDualUserId(unverifiedSerial)))
        assertFalse(preClearRootUninstallAllowed(resolvePreClearDualUserId(stale)))
    }
}
