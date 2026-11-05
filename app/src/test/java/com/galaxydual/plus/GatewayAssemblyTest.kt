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

import com.galaxydual.core.PmOutputParser
import com.galaxydual.core.Privilege
import com.galaxydual.core.TRUNCATION_MARKER
import com.galaxydual.core.TrustedList
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    @Test fun captureProcess_missingBinaryIsExplicitError() {
        val res = captureProcess(arrayOf("definitely-not-a-real-binary-xyz", "-c", "id"))
        assertNotEquals(0, res.exit)
        assertTrue(res.err.startsWith("spawn failed:"))
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
    @Test fun captureProcess_timeoutCarriesTimedOutFlag() {
        val res = captureProcess(arrayOf("sh", "-c", "sleep 5"), timeoutMs = 200L)
        assertNotEquals(0, res.exit)
        assertTrue("timeout reply must carry timedOut, got: $res", res.timedOut)
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
    // service semantics), v2 = whenever-truncated marker (current contract).
    // bindUserService uses .version(SERVICE_VERSION) so Shizuku/Sui rebuilds
    // the resident UserService on upgrade; the tag stays "GalaxyDualExec-v1".
    @Test fun serviceVersion_isV2() {
        assertEquals(2, ShizukuExecProtocol.SERVICE_VERSION)
    }

    // P1 UserService version gap -- read-only reproducible verification, OLD
    // side (JVM): an old v1 service reply for a truncated capture (exit=0,
    // partial package lines, NO marker -- v1 only marked cap) decodes as
    // truncated=false and parses as TrustedFull. This asserts the gap EXISTS
    // at the wire-mapping layer; it is closed at the deployment layer by the
    // SERVICE_VERSION bump (v2 forces the daemon to rebuild the resident
    // service), not by client sniffing.
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
    // side (JVM): the same partial content from a new v2 service (join-expiry
    // path built by buildResult, marker on both streams) decodes as
    // truncated=true and parses as Unknown. Paired with the OLD test above,
    // this pins the v1-vs-v2 contract difference without touching a device.
    // Device upgrade verification (S24 real-device item, NOT JVM-testable):
    // start the old resident service (v1), upgrade the APK without restarting
    // Shizuku, then confirm the bound service runs the new capture/marker
    // contract (this path arrives WITH marker -> Unknown).
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
}
