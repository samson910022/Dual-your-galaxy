// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// DEVICE-UNVERIFIED：無裝置，未經實機驗證。
// Phase F4a (issue #6): JVM unit tests for the parts of the true channels
// that do not need Android or a device -- su argv assembly, the shared
// process-capture helper (via /bin/sh on the host), the Shizuku error
// mapping, and the binder-protocol code reservation. Binder round-trips,
// permission flows, and pm semantics all need a real device (see file headers
// of SuCmdGateway / ShizukuCmdGateway).
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import com.galaxydual.core.PmOutputParser
import com.galaxydual.core.Privilege
import com.galaxydual.core.TRUNCATION_MARKER
import com.galaxydual.core.TrustedList
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayAssemblyTest {
    // suArgv must carry the core-built command verbatim as ONE `-c` argument.
    @Test fun suArgv_wrapsCmdVerbatim() {
        assertArrayEquals(
            arrayOf("su", "-c", "pm install-existing --user 95 com.example.app"),
            suArgv("pm install-existing --user 95 com.example.app"),
        )
    }

    // Metacharacters must NOT gain an extra shell layer: argv form keeps them
    // inside the single -c element (quoting safety comes from core's
    // ShellEscaper plus this argv shape, not from re-quoting here).
    @Test fun suArgv_keepsMetaCharsInsideSingleArg() {
        val argv = suArgv("pm list users; id -u $(whoami)")
        assertEquals(3, argv.size)
        assertEquals("su", argv[0])
        assertEquals("-c", argv[1])
        assertEquals("pm list users; id -u $(whoami)", argv[2])
    }

    // captureProcess against the host shell: happy path captures exit/stdout.
    @Test fun captureProcess_echoSucceeds() {
        val res = captureProcess(arrayOf("sh", "-c", "echo hello-dual"))
        assertEquals(0, res.exit)
        assertEquals("hello-dual\n", res.out)
        assertEquals("", res.err)
    }

    // Non-zero exits are reported, not thrown; stderr is captured.
    @Test fun captureProcess_reportsFailure() {
        val res = captureProcess(arrayOf("sh", "-c", "echo oops >&2; exit 3"))
        assertEquals(3, res.exit)
        assertTrue(res.err.contains("oops"))
    }

    // Missing binary maps to an explicit non-zero CmdResult (the su-missing path).
    // Pre-spawn definite (Phase A1): exec threw, process never started, so no
    // unknown flags -- DualRepository stays on definite failure.
    @Test fun captureProcess_missingBinaryIsExplicitError() {
        val res = captureProcess(arrayOf("definitely-not-a-real-binary-xyz", "-c", "id"))
        assertNotEquals(0, res.exit)
        assertTrue(res.err.startsWith("spawn failed:"))
        assertFalse("pre-spawn spawn failure must not carry timedOut, got: $res", res.timedOut)
        assertFalse("pre-spawn spawn failure must not carry transportUnknown, got: $res", res.transportUnknown)
    }

    // Phase A1 wire-frozen backstop preservation: the service-side
    // post-spawn texts ("interrupted: ...", "service exec failed: ...")
    // survive the (exit, out, err) parcel verbatim so the repository text
    // backstop can recognize them (flags are dropped by encode).
    @Test fun execReply_postSpawnTextsRoundTripVerbatim() {
        for (err in listOf("interrupted: wait interrupted", "service exec failed: boom")) {
            val (exit, out, replyErr) = encodeExecReply(CmdResult(exit = 1, out = "", err = err))
            val cli = decodeExecReply(exit, out, replyErr)
            assertEquals(1, cli.exit)
            assertEquals(err, cli.err)
        }
        // Normal pm Failure lines survive too but must never be confused
        // with the anchored post-spawn prefixes (anchoring is startsWith).
        val (_, _, failureErr) = encodeExecReply(CmdResult(exit = 1, out = "Failure [INSTALL_FAILED_INVALID_APK]", err = ""))
        val failureCli = decodeExecReply(1, "Failure [INSTALL_FAILED_INVALID_APK]", failureErr)
        assertFalse(failureCli.err.startsWith("interrupted: "))
        assertFalse(failureCli.err.startsWith("service exec failed: "))
    }

    // Every Shizuku error mapping must be non-zero with a greppable message.
    @Test fun shizukuErrors_areExplicitNonZero() {
        val cases = listOf(
            ShizukuExecErrors.noBinder() to "binder not connected",
            ShizukuExecErrors.binderDead() to "binder died",
            ShizukuExecErrors.notAuthorized() to "not authorized",
            ShizukuExecErrors.bindFailed("boom") to "boom",
            ShizukuExecErrors.remoteFailed("boom") to "boom",
            ShizukuExecErrors.transportUnknown("reply decode failed") to "transport unknown",
            ShizukuExecErrors.timedOut() to "timed out",
            ShizukuExecErrors.unsupportedVersion(12) to "unsupported Shizuku version 12",
            ShizukuExecErrors.legacyCleanupFailed("cleanup failed") to "legacy cleanup failed",
        )
        for ((res, needle) in cases) {
            assertNotEquals("exit must be != 0 for $res", 0, res.exit)
            assertTrue("err '$res' should contain '$needle'", res.err.contains(needle))
        }
    }

    // PR-32 P1-1 + P1-2 tri-state: only the client-side wait timeout carries
    // timedOut=true (unknown-after-send); only the post-dispatch reply loss
    // carries transportUnknown=true (unknown-after-send, timedOut=false);
    // every pre-dispatch refusal (never sent) carries neither flag and stays
    // a definite failure.
    @Test fun shizukuErrors_triStateFlagsAreExclusive() {
        assertTrue(ShizukuExecErrors.timedOut().timedOut)
        assertTrue(!ShizukuExecErrors.timedOut().transportUnknown)
        val lost = ShizukuExecErrors.transportUnknown("reply decode failed")
        assertTrue(lost.transportUnknown)
        assertTrue(!lost.timedOut)
        assertTrue(lost.err.contains("may have executed"))
        for (pre in listOf(
            ShizukuExecErrors.noBinder(),
            ShizukuExecErrors.binderDead(),
            ShizukuExecErrors.notAuthorized(),
            ShizukuExecErrors.bindFailed("boom"),
            ShizukuExecErrors.remoteFailed("boom"),
            ShizukuExecErrors.unsupportedVersion(12),
            ShizukuExecErrors.legacyCleanupFailed("cleanup failed"),
        )) {
            assertTrue(!pre.timedOut)
            assertTrue(!pre.transportUnknown)
        }
    }

    // E2 P1-2 follow-up: transactExec needs a real IBinder (synchronous
    // binder.transact on a Parcel pair), so its post-dispatch catch chain has
    // zero JVM execution -- only the pure mapPostDispatchError() extraction
    // is covered here. These tests pin the extraction 1:1 to the catch chain:
    // Cancellation rethrows (never swallowed into a result), Interrupted and
    // generic both map to transportUnknown (unknown-after-send, timedOut
    // stays false). The stale-cache dropBinding side effect stays in
    // transactExec and is NOT asserted here (needs a real binder).
    @Test fun postDispatchError_cancellationPropagates() {
        try {
            mapPostDispatchError(CancellationException("cancel"))
            fail("CancellationException must propagate, not map to a CmdResult")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
    }

    // InterruptedException (the block's own interrupt unwrapped from the
    // future by awaitBounded) is post-dispatch: transportUnknown, never a
    // definite failure. Pure mapper touches no thread flag, so calling it
    // here is side-effect free (the Thread.interrupted() consume stays in
    // transactExec's catch block).
    @Test fun postDispatchError_interruptedMapsToTransportUnknown() {
        val res = mapPostDispatchError(InterruptedException("pool interrupt"))
        assertNotEquals(0, res.exit)
        assertTrue("interrupted post-dispatch must carry transportUnknown, got: $res", res.transportUnknown)
        assertTrue(!res.timedOut)
        assertTrue(res.err.contains("transport unknown"))
        assertTrue(res.err.contains("pool interrupt"))
        assertTrue(res.err.contains("may have executed"))
    }

    // Generic branch (transact RemoteException / binder death mid-call):
    // android.os.RemoteException is an android.jar stub that throws "Stub!"
    // on instantiation, so it cannot be constructed on the JVM -- but it
    // extends Exception and shares transactExec's generic catch (e:
    // Exception) branch with the RuntimeException below, so this pins the
    // same mapping the wire RemoteException takes (transportUnknown, never
    // remoteFailed/definite failure).
    @Test fun postDispatchError_genericRuntimeMapsToTransportUnknown() {
        val res = mapPostDispatchError(RuntimeException("binder died"))
        assertNotEquals(0, res.exit)
        assertTrue("generic post-dispatch must carry transportUnknown, got: $res", res.transportUnknown)
        assertTrue(!res.timedOut)
        assertTrue(res.err.contains("transport unknown"))
        assertTrue(res.err.contains("binder died"))
        assertTrue(res.err.contains("may have executed"))
    }

    // Generic branch, decode-failure representative (reply readInt/readString
    // throwing mid-decode): same transportUnknown mapping as above.
    @Test fun postDispatchError_decodeFailureIoMapsToTransportUnknown() {
        val res = mapPostDispatchError(IOException("reply decode failed"))
        assertNotEquals(0, res.exit)
        assertTrue("decode failure post-dispatch must carry transportUnknown, got: $res", res.transportUnknown)
        assertTrue(!res.timedOut)
        assertTrue(res.err.contains("transport unknown"))
        assertTrue(res.err.contains("reply decode failed"))
    }

    // PR-32 P1-1: Su client-side wait timeout (SU_EXEC_TIMEOUT path) carries
    // timedOut=true so the repository verifies read-only instead of claiming
    // definite failure. Short budget keeps the JVM test fast.
    // Phase A1衔接: the err text must ALSO match the isServiceTimeoutText
    // backstop (anchored "timeout after " producer prefix) so the frozen-wire
    // service-side timeout (flags lost on the parcel) still reconciles
    // read-only. isServiceTimeoutText itself is internal to :core (not
    // visible from :app tests); the startsWith below pins the exact prefix
    // it matches (see core/CmdGateway.kt).
    @Test fun captureProcess_timeoutCarriesTimedOutFlag() {
        val res = captureProcess(arrayOf("sh", "-c", "sleep 5"), timeoutMs = 200L)
        assertNotEquals(0, res.exit)
        assertTrue("timeout reply must carry timedOut, got: $res", res.timedOut)
        assertTrue("timeout err must hit the isServiceTimeoutText backstop, got: \"${res.err}\"", res.err.startsWith("timeout after "))
    }

    // The custom exec code must never collide with the framework-reserved
    // UserService destroy codes (16777114 aidl spelling / 16777115 on wire).
    @Test fun execCode_avoidsReservedDestroyCodes() {
        assertNotEquals(16777114, ShizukuExecProtocol.TRANSACTION_EXEC)
        assertNotEquals(16777115, ShizukuExecProtocol.TRANSACTION_EXEC)
        assertEquals(16777115, ShizukuExecProtocol.TRANSACTION_DESTROY)
    }

    // Gateways declare the privilege their channel provides; constructors
    // must stay free of Android calls so this runs on the JVM.
    @Test fun gateways_declarePrivilege() {
        assertEquals(Privilege.ROOT, SuCmdGateway().privilege)
        assertEquals(Privilege.SHELL, ShizukuCmdGateway("com.example.test").privilege)
    }

    // drainCapped: a short stream drains fully and reports clean EOF.
    @Test fun drainCapped_shortStreamReturnsEof() {
        val sink = StringBuilder()
        val status = drainCapped(ByteArrayInputStream("package:com.example.app\n".toByteArray()), sink)
        assertEquals(DrainStatus.EOF, status)
        assertEquals("package:com.example.app\n", sink.toString())
    }

    // drainCapped (P1 BLOCKER #2): an IOException mid-read must surface as
    // READ_ERROR (never silent EOF) while preserving what was already read.
    // Fixture delivers exactly 10 bytes, then throws. The available() guard
    // stops BufferedReader's same-call readahead once the failure point is
    // reached, so the first reader.read() completes with 10 chars and the
    // throw surfaces deterministically on the next call (verified: without
    // the guard, the in-flight chunk is discarded inside the reader and the
    // sink stays empty -- a BufferedReader artifact, not drainCapped's).
    @Test fun drainCapped_readErrorPreservesPartial() {
        val payload = "package:com.example.app\n".toByteArray()
        val failing = object : ByteArrayInputStream(payload) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= 10) throw IOException("boom-after-10")
                return super.read(b, off, minOf(len, 10 - pos))
            }
            override fun available(): Int = if (pos >= 10) 0 else super.available()
        }
        val sink = StringBuilder()
        val status = drainCapped(failing, sink)
        assertEquals(DrainStatus.READ_ERROR, status)
        assertEquals(String(payload, 0, 10), sink.toString())
    }

    // drainCapped: output past the cap reports CAPPED with the sink held
    // exactly at MAX_CAPTURE_CHARS.
    @Test fun drainCapped_overCapReportsCapped() {
        val big = ByteArray(MAX_CAPTURE_CHARS + 1_000) { 'x'.code.toByte() }
        val sink = StringBuilder()
        val status = drainCapped(ByteArrayInputStream(big), sink)
        assertEquals(DrainStatus.CAPPED, status)
        assertEquals(MAX_CAPTURE_CHARS, sink.length)
    }

    // buildResult: clean EOF on both streams without join expiry is not
    // truncated and carries no marker.
    @Test fun buildResult_cleanCaptureHasNoMarker() {
        val res = buildResult(
            0, StringBuilder("package:com.example.app\n"), StringBuilder(""),
            DrainStatus.EOF, DrainStatus.EOF, false,
        )
        assertFalse(res.truncated)
        assertFalse(res.out.contains(TRUNCATION_MARKER))
        assertFalse(res.err.contains(TRUNCATION_MARKER))
    }

    // buildResult: a capped stream forces truncated=true with a marker on the
    // affected stream only -- the clean stream stays marker-free so the hint
    // parser can still use its content.
    @Test fun buildResult_cappedStreamTruncatesWithMarker() {
        val res = buildResult(
            0, StringBuilder("partial"), StringBuilder(""),
            DrainStatus.CAPPED, DrainStatus.EOF, false,
        )
        assertTrue(res.truncated)
        assertTrue(res.out.contains(TRUNCATION_MARKER))
        assertFalse(res.err.contains(TRUNCATION_MARKER))
    }

    // buildResult: a read error on either stream forces truncated=true with
    // a marker on the affected stream only (fail-closed per P1 BLOCKER #2).
    @Test fun buildResult_readErrorTruncatesWithMarker() {
        val res = buildResult(
            0, StringBuilder("partial"), StringBuilder(""),
            DrainStatus.EOF, DrainStatus.READ_ERROR, false,
        )
        assertTrue(res.truncated)
        assertFalse(res.out.contains(TRUNCATION_MARKER))
        assertTrue(res.err.contains(TRUNCATION_MARKER))
    }

    // buildResult (P2 #3): join expiry forces truncated=true with a marker
    // on BOTH streams (it may have cut either side) even when both reached
    // clean EOF -- previously only hitCap appended a marker, so the Shizuku
    // client sniffed truncated=false.
    @Test fun buildResult_joinExpiredTruncatesWithMarker() {
        val res = buildResult(
            0, StringBuilder("package:com.example.app\n"), StringBuilder(""),
            DrainStatus.EOF, DrainStatus.EOF, true,
        )
        assertTrue(res.truncated)
        assertTrue(res.out.contains(TRUNCATION_MARKER))
        assertTrue(res.err.contains(TRUNCATION_MARKER))
    }

    // Shizuku wire round-trips below exercise ONLY the pure encode/decode
    // mapping: android.os.Parcel is a framework stub on the JVM and is not
    // covered here (see the note on encodeExecReply/decodeExecReply).

    // Cap truncation survives the service -> wire -> client mapping.
    @Test fun execReply_capRoundTripPreservesTruncated() {
        val svc = buildResult(
            0, StringBuilder("partial"), StringBuilder(""),
            DrainStatus.CAPPED, DrainStatus.EOF, false,
        )
        assertTrue(svc.truncated)
        assertTrue(svc.out.contains(TRUNCATION_MARKER))
        assertFalse(svc.err.contains(TRUNCATION_MARKER))
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(0, cli.exit)
        assertEquals(svc.out, cli.out)
        assertEquals(svc.err, cli.err)
        assertTrue(cli.truncated)
    }

    // Join-expiry truncation survives the mapping (the P2 #3 regression).
    // Join expiry marks both streams, so both round-trip verbatim.
    @Test fun execReply_joinExpiryRoundTripPreservesTruncated() {
        val svc = buildResult(
            0, StringBuilder("package:com.example.app\n"), StringBuilder(""),
            DrainStatus.EOF, DrainStatus.EOF, true,
        )
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(0, cli.exit)
        assertEquals(svc.out, cli.out)
        assertEquals(svc.err, cli.err)
        assertTrue(cli.truncated)
    }

    // Read-error truncation survives the mapping, symmetric with cap/join:
    // only the affected stream carries the marker, and decode re-derives
    // truncated=true from it.
    @Test fun execReply_readErrorRoundTripPreservesTruncated() {
        val svc = buildResult(
            0, StringBuilder("partial"), StringBuilder(""),
            DrainStatus.EOF, DrainStatus.READ_ERROR, false,
        )
        assertTrue(svc.truncated)
        assertFalse(svc.out.contains(TRUNCATION_MARKER))
        assertTrue(svc.err.contains(TRUNCATION_MARKER))
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(0, cli.exit)
        assertEquals(svc.out, cli.out)
        assertEquals(svc.err, cli.err)
        assertTrue(cli.truncated)
    }

    // A clean empty capture round-trips as truncated=false with no marker.
    @Test fun execReply_cleanEmptyRoundTripHasNoTruncated() {
        val svc = buildResult(
            0, StringBuilder(""), StringBuilder(""),
            DrainStatus.EOF, DrainStatus.EOF, false,
        )
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(0, cli.exit)
        assertFalse(cli.truncated)
        assertFalse(cli.out.contains(TRUNCATION_MARKER))
        assertFalse(cli.err.contains(TRUNCATION_MARKER))
    }

    // A non-zero exit round-trips its code and stays untruncated.
    @Test fun execReply_nonZeroExitRoundTrip() {
        val svc = buildResult(
            3, StringBuilder(""), StringBuilder("oops"),
            DrainStatus.EOF, DrainStatus.EOF, false,
        )
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(3, cli.exit)
        assertEquals("oops", cli.err)
        assertFalse(cli.truncated)
    }

    // UserService implementation version pin: v1 = cap-only marker (old
    // service semantics), v2 = whenever-truncated marker (prior contract,
    // EXEC ungated), v3 = v2 marker plus owner-uid EXEC gate, v4 = v3
    // marker+gate with appId normalization, v5 = v4 marker+gate with appId
    // upper bound; v6 = v5 + concurrency gate (current contract).
    // bindUserService uses .version(SERVICE_VERSION) so Shizuku/Sui
    // rebuilds the resident UserService on upgrade (v6 invalidates v5
    // resident services, as v5 invalidated v4 resident services and v4 invalidated v3 resident services and v3
    // invalidated ungated v2 resident services);
    // the bind tag is per-UID derived
    // ("GalaxyDualExec-v1-u<callerUid>", see galaxyExecServiceTag) and the
    // legacy fixed tag "GalaxyDualExec-v1" record is removed by the D1
    // migration on the first run (retryable); if cleanup never succeeds
    // until restart, the orphan sits inert (daemon restart reaps).
    // SERVICE_VERSION is 6 (#54 bumped it
    // for concurrency gate + drain sync; service code changed so daemon must rebuild).
    @Test fun serviceVersion_isV6() {
        assertEquals(6, ShizukuExecProtocol.SERVICE_VERSION)
    }

    // P1 UserService version gap -- read-only reproducible verification, OLD
    // side (JVM): an old v1 service reply for a truncated capture (exit=0,
    // partial package lines, NO marker -- v1 only marked cap) decodes as
    // truncated=false and parses as TrustedFull. This asserts the gap EXISTS
    // at the wire-mapping layer; it is closed at the deployment layer by the
    // SERVICE_VERSION bump (v3 forces the daemon to rebuild the resident
    // service; v2 did the same for the v1->v2 marker gap, and v3 likewise
    // invalidates ungated v2 resident services), not by client sniffing.
    @Test fun execReply_oldServicePartialWithoutMarkerParsesTrustedFull() {
        val cli = decodeExecReply(0, "package:com.example.app\n", "")
        assertFalse(cli.truncated)
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("old v1 reply without marker must parse TrustedFull, got $parsed", parsed is TrustedList.TrustedFull)
        assertEquals(
            listOf("com.example.app"),
            (parsed as TrustedList.TrustedFull).packages,
        )
    }

    // P1 UserService version gap -- read-only reproducible verification, NEW
    // side (JVM): the same partial content from a new v3 service (join-expiry
    // path built by buildResult, marker on both streams) decodes as
    // truncated=true and parses as Unknown. Paired with the OLD test above,
    // this pins the v1-vs-v2 contract difference without touching a device.
    // Device upgrade verification (S24 real-device item, NOT JVM-testable):
    // start the old resident service (v1), upgrade the APK without restarting
    // Shizuku, then confirm the bound service runs the new capture/marker
    // contract (this path arrives WITH marker -> Unknown). v2 -> v3 note:
    // ungated v2 resident services are invalidated by the v3 bump; confirm
    // owner EXEC succeeds and a foreign uid is denied. v5 -> v6 note: v5 resident services are invalidated by the v6 bump; confirm EXEC concurrency gate (4 concurrent EXECs hold gate, 5th returns serviceBusy definite failure, foreign uid denied without consuming permit, DESTROY rebinds when gate full).
    @Test fun execReply_newServicePartialWithMarkerParsesUnknown() {
        val svc = buildResult(
            0, StringBuilder("package:com.example.app\n"), StringBuilder(""),
            DrainStatus.EOF, DrainStatus.EOF, true,
        )
        assertTrue(svc.truncated)
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(0, cli.exit)
        assertTrue(cli.truncated)
        assertTrue(cli.out.contains(TRUNCATION_MARKER))
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("new v2 reply with marker must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    // P2-1: probe vs exec budget split (pure). `id -u` (SuElevator.whoAmICmd)
    // is read-only uid check, so the short probe budget bounds a hung su auth
    // prompt to 10 s; every other command (pm writes, list/identity probes)
    // keeps the full 60 s exec budget.
    @Test fun suTimeoutFor_probeUsesShortBudgetOthersUseFull() {
        assertEquals(SU_PROBE_TIMEOUT_MS, suTimeoutFor("id -u"))
        assertEquals(SU_PROBE_TIMEOUT_MS, suTimeoutFor("  id -u  "))
        assertEquals(SU_EXEC_TIMEOUT_MS, suTimeoutFor("pm list users"))
        assertEquals(SU_EXEC_TIMEOUT_MS, suTimeoutFor("pm install-existing --user 95 com.example.app"))
        assertEquals(10_000L, SU_PROBE_TIMEOUT_MS)
        assertEquals(60_000L, SU_EXEC_TIMEOUT_MS)
    }

    // P2-1: real blocking subprocess wait is cancellable. A `su -c` stuck on
    // its auth prompt is modelled by an actual `sleep` Process (true waitFor
    // block, non-cooperative: coroutine cancel cannot interrupt it, only the
    // poll+ensureActive checkpoint surfaces it). Cancel must stop the waiter
    // at once with CancellationException (never convert cancel to a result),
    // destroy the hung child best effort, and leave the gateway usable for an
    // immediate handover call. The 2 s bound is deliberately loose (poll is
    // 50 ms); it falsifies the old blocking captureProcess, which held the
    // caller to the full sleep/timeout.
    @Test fun suGateway_cancelHungCapture_stopsWaitAtOnceWithCancellation() = runBlocking {
        val started = CountDownLatch(1)
        val hungProc = java.util.concurrent.atomic.AtomicReference<Process?>(null)
        val gw = SuCmdGateway { _ ->
            started.countDown()
            ProcessBuilder("sh", "-c", "sleep 20").start().also { hungProc.set(it) }
        }
        val job = async(Dispatchers.Default) { gw.run("id -u") }
        assertTrue("hung capture must be in flight before cancelling", started.await(5, TimeUnit.SECONDS))
        val startNs = System.nanoTime()
        job.cancel()
        try {
            job.await()
            fail("cancelled hung su capture must throw CancellationException")
        } catch (_: CancellationException) {
            // Expected: caller retreats at once; remote state unknown.
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue(
            "cancelled su wait must end at once, not at the deadline (elapsed=${elapsedMs}ms)",
            elapsedMs < 2_000L,
        )
        // Best-effort local kill: the hung child must not linger as a live
        // process after cancel (unknown-after-send still holds -- the kill is
        // local only, pm may already have executed, so no definite claim).
        val deadlineMs = System.currentTimeMillis() + 5_000L
        while (hungProc.get()?.isAlive == true && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(10L)
        }
        assertEquals("cancelled capture must destroy its child best effort", false, hungProc.get()?.isAlive)
        // Handover: a fresh call on the same gateway completes immediately
        // (no wedge, no resend of the cancelled capture).
        val fastStartNs = System.nanoTime()
        val fast = SuCmdGateway { _ ->
            ProcessBuilder("sh", "-c", "echo 0").start()
        }.run("id -u")
        val fastElapsedMs = (System.nanoTime() - fastStartNs) / 1_000_000L
        assertTrue("handover call must complete immediately (elapsed=${fastElapsedMs}ms)", fastElapsedMs < 5_000L)
        assertEquals(0, fast.exit)
    }
}
