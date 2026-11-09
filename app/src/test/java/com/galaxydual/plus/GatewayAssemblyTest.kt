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

    // v6 P1: post-spawn catch marker crosses the wire honestly (round-trip).
    // GalaxyExecService.onTransact generic catch builds
    // CmdResult(1, "", "service exec failed: boom\n[truncated]",
    // truncated=true, transportUnknown=true) local-only; encode drops the
    // flags, so the marker text is the only truncated signal on the wire.
    // decode must re-derive truncated=true, the anchored post-spawn prefix
    // must still hold (isPostSpawnTransportText in core/CmdGateway.kt is
    // internal to :core, not visible from :app tests -- pinned here by the
    // exact startsWith it matches, same reason as the timeout-prefix pin in
    // captureProcess_timeoutCarriesTimedOutFlag), and parse must be Unknown.
    @Test fun execReply_postSpawnGenericCatchMarkerRoundTrip() {
        val svc = CmdResult(exit = 1, out = "", err = "service exec failed: boom\n$TRUNCATION_MARKER", truncated = true, transportUnknown = true)
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(1, cli.exit)
        assertEquals("", cli.out)
        assertEquals(svc.err, cli.err)
        assertTrue("marker must re-derive truncated over the wire, got: $cli", cli.truncated)
        assertTrue("anchored prefix must still hit isPostSpawnTransportText, got: \"${cli.err}\"", cli.err.startsWith("service exec failed: "))
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("post-spawn catch reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    // v6 P1: Interrupted-branch variant (same honesty, interrupted text).
    // onTransact Interrupted catch builds "service exec failed: interrupted:
    // ..." plus the marker; asserted identically (exit/out/err verbatim,
    // truncated re-derived, Unknown parse).
    @Test fun execReply_postSpawnInterruptedCatchMarkerRoundTrip() {
        val svc = CmdResult(exit = 1, out = "", err = "service exec failed: interrupted: boom\n$TRUNCATION_MARKER", truncated = true, transportUnknown = true)
        val (exit, out, err) = encodeExecReply(svc)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(1, cli.exit)
        assertEquals("", cli.out)
        assertEquals(svc.err, cli.err)
        assertTrue("marker must re-derive truncated over the wire, got: $cli", cli.truncated)
        assertTrue("anchored prefix must still hit isPostSpawnTransportText, got: \"${cli.err}\"", cli.err.startsWith("service exec failed: "))
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("interrupted catch reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
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

    // Parcel-null P0 (availability): a null wire cmd must never spawn
    // `sh -c ""` (empty shell exit=0 misjudged as success). Pure helper
    // badRequestForBlankCommand is what GalaxyExecService.onTransact checks
    // after data.readString(); Parcel itself is a JVM stub and not covered.
    @Test fun execRequest_nullCmdIsBadRequest() {
        val res = badRequestForBlankCommand(null)
        assertTrue("null cmd must be bad request, got: $res", res != null)
        assertEquals(1, res!!.exit)
        assertEquals("", res.out)
        assertTrue("bad request err must contain 'bad request', got: \"${res.err}\"", res.err.contains("bad request"))
        // Pre-spawn definite failure: never sent, so neither unknown flag.
        assertFalse("bad request must not carry timedOut, got: $res", res.timedOut)
        assertFalse("bad request must not carry transportUnknown, got: $res", res.transportUnknown)
    }

    // Blank (empty / whitespace-only) wire cmd is the same bad request:
    // onTransact must reply exit=1 without calling captureProcess.
    @Test fun execRequest_blankCmdIsBadRequest() {
        for (blank in listOf("", "   ", "\n\t")) {
            val res = badRequestForBlankCommand(blank)
            assertTrue("blank cmd '$blank' must be bad request, got: $res", res != null)
            assertEquals(1, res!!.exit)
            assertEquals("", res.out)
            assertTrue(res.err.contains("bad request"))
            assertFalse(res.truncated)
            assertFalse(res.timedOut)
            assertFalse(res.transportUnknown)
        }
    }

    // A usable command passes through verbatim (no bad request).
    @Test fun execRequest_validCmdIsNotBadRequest() {
        assertEquals(null, badRequestForBlankCommand("pm list packages"))
        assertEquals(null, badRequestForBlankCommand("  pm list packages  "))
    }

    // Phase C1 (4KB bound, pure helper): badRequestForCommand pins
    // null/blank -> blank bad request, 4096 chars -> pass (null), 4097 ->
    // "command too long" bad request (exit=1, no flags, no backstop).
    // badRequestForBlankCommand stays blank-only (8000-char pass-through
    // retained) so its existing pins do not silently change meaning;
    // production gates use badRequestForCommand.
    @Test fun execRequest_commandLengthBound_4096passes_4097rejects() {
        assertEquals(4096, ShizukuExecProtocol.MAX_CMD_CHARS)
        assertEquals(null, badRequestForCommand("x".repeat(4096)))
        assertEquals(null, badRequestForCommand("pm list packages"))
        // Blank helper preserved: an 8000-char cmd is still not a blank
        // bad request (validEdgeCases pin), only a length bad request.
        assertEquals(null, badRequestForBlankCommand("x".repeat(8000)))
        for (raw in listOf<String?>(null, "", "   ", "x".repeat(4097), "x".repeat(8000))) {
            val res = badRequestForCommand(raw)
            assertTrue("raw len=${raw?.length} must be bad request, got: $res", res != null)
            assertEquals(1, res!!.exit)
            assertEquals("", res.out)
            assertTrue("err must contain 'bad request', got: \"${res.err}\"", res.err.contains("bad request"))
            assertFalse("bad request must not carry truncated, got: $res", res.truncated)
            assertFalse("bad request must not carry timedOut, got: $res", res.timedOut)
            assertFalse("bad request must not carry transportUnknown, got: $res", res.transportUnknown)
            for (prefix in listOf("interrupted: ", "service exec failed: ", "timeout after ", "shizuku: exec timed out after ")) {
                assertFalse(
                    "bad request err must not start with backstop prefix '$prefix', got: \"${res.err}\"",
                    res.err.startsWith(prefix),
                )
            }
        }
        val tooLong = badRequestForCommand("x".repeat(4097))
        assertTrue("too-long err must name the bound, got: \"${tooLong!!.err}\"", tooLong.err.contains("too long") && tooLong.err.contains("4096"))
        assertEquals("bad request: blank command", badRequestForCommand(null)!!.err)
        assertEquals("bad request: blank command", badRequestForCommand("  ")!!.err)
    }

    // Parcel-null P0: a null reply string is post-dispatch reply loss, NOT
    // clean-empty. Pure helper decodeExecReplyNullable is what transactExec
    // feeds reply.readString() into; folding null to "" would forge a
    // TrustedFull empty. Must be transportUnknown (timedOut=false).
    // Parcel/Binder wiring (onTransact no-spawn guard, drop-rebind, and
    // service-catch truncated paths) is covered in-process by
    // ShizukuBinderParcelTest on API29 fake (PR locale-instrumentation green,
    // no spawn, zero pm writes); S24 real-device matrix stays
    // [待實機驗證] DEVICE-UNVERIFIED.
    @Test fun execReply_nullOutIsTransportUnknown() {
        val res = decodeExecReplyNullable(0, null, "")
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertFalse("null reply must not carry truncated, got: $res", res.truncated)
        assertTrue("null reply out must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("null reply must not carry timedOut, got: $res", res.timedOut)
        assertTrue(res.err.contains("transport unknown"))
        assertTrue(res.err.contains("may have executed"))
        assertTrue(res.err.contains("null reply string"))
        // Must not be forgeable as clean-empty TrustedFull: parser must see Unknown.
        val parsed = PmOutputParser.parsePackagesTrusted(res)
        assertTrue("null reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    @Test fun execReply_nullErrIsTransportUnknown() {
        val res = decodeExecReplyNullable(0, "package:com.example.app\n", null)
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertFalse("null reply must not carry truncated, got: $res", res.truncated)
        assertTrue("null reply err must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("null reply must not carry timedOut, got: $res", res.timedOut)
        assertTrue(res.err.contains("transport unknown"))
        assertTrue(res.err.contains("may have executed"))
        assertTrue(res.err.contains("null reply string"))
    }

    @Test fun execReply_nullBothIsTransportUnknown() {
        val res = decodeExecReplyNullable(0, null, null)
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertFalse("null reply must not carry truncated, got: $res", res.truncated)
        assertTrue("null reply both must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("null reply must not carry timedOut, got: $res", res.timedOut)
        assertTrue(res.err.contains("transport unknown"))
        assertTrue(res.err.contains("may have executed"))
        assertTrue(res.err.contains("null reply string"))
    }

    // #1: bad request survives the frozen (exit, out, err) encode/decode as a
    // pre-spawn definite failure (all three flags false, never a backstop text).
    @Test fun execRequest_badRequestSurvivesEncodeRoundTrip() {
        val bad = badRequestForBlankCommand(null)
        assertTrue("null cmd must be bad request, got: $bad", bad != null)
        val encoded = encodeExecReply(bad!!)
        assertEquals(Triple(1, "", "bad request: blank command"), encoded)
        val cli = decodeExecReply(encoded.first, encoded.second, encoded.third)
        assertEquals(1, cli.exit)
        assertEquals("", cli.out)
        assertEquals("bad request: blank command", cli.err)
        assertFalse("bad request round-trip must not carry truncated, got: $cli", cli.truncated)
        assertFalse("bad request round-trip must not carry timedOut, got: $cli", cli.timedOut)
        assertFalse("bad request round-trip must not carry transportUnknown, got: $cli", cli.transportUnknown)
        for (prefix in listOf("interrupted: ", "service exec failed: ", "timeout after ", "shizuku: exec timed out after ")) {
            assertFalse("bad request err must not start with backstop prefix '$prefix', got: \"${cli.err}\"", cli.err.startsWith(prefix))
        }
    }

    // #2: null reply strings parse as Unknown, never as TrustedEmpty/TrustedFull.
    @Test fun execReply_nullStringsParseAsUnknownNotTrustedEmpty() {
        val cases = listOf(
            "outNull" to decodeExecReplyNullable(0, null, ""),
            "errNull" to decodeExecReplyNullable(0, "package:com.example.app\n", null),
            "bothNull" to decodeExecReplyNullable(0, null, null),
        )
        for ((label, res) in cases) {
            val parsed = PmOutputParser.parsePackagesTrusted(res)
            assertTrue("null reply ($label) must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
        }
    }

    // #3: non-null normal listing stays TrustedFull (no marker, no null).
    @Test fun nonNullNormalListingStaysTrustedFull() {
        val cli = decodeExecReplyNullable(0, "package:com.example.app\n", "")
        assertEquals(0, cli.exit)
        assertFalse(cli.truncated)
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("normal listing must parse TrustedFull, got $parsed", parsed is TrustedList.TrustedFull)
        assertEquals(
            listOf("com.example.app"),
            (parsed as TrustedList.TrustedFull).packages,
        )
    }

    // #4: non-null clean empty stays TrustedEmpty (confirmed empty, not Unknown).
    @Test fun nonNullCleanEmptyStaysTrustedEmpty() {
        val cli = decodeExecReplyNullable(0, "", "")
        assertEquals(0, cli.exit)
        assertFalse(cli.truncated)
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("clean empty must parse TrustedEmpty, got $parsed", parsed is TrustedList.TrustedEmpty)
    }

    // #5: marker on both streams yields truncated=true (never transportUnknown).
    @Test fun markerBothStreams() {
        val cli = decodeExecReplyNullable(0, "partial\n$TRUNCATION_MARKER", "warn\n$TRUNCATION_MARKER")
        assertEquals(0, cli.exit)
        assertEquals("partial\n$TRUNCATION_MARKER", cli.out)
        assertEquals("warn\n$TRUNCATION_MARKER", cli.err)
        assertTrue(cli.truncated)
        assertFalse(cli.timedOut)
        assertFalse(cli.transportUnknown)
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("truncated reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    // #6: non-zero exit without marker stays untruncated (explicit pm failure).
    @Test fun nonZeroExitUntruncated() {
        val cli = decodeExecReplyNullable(1, "", "oops")
        assertEquals(1, cli.exit)
        assertEquals("", cli.out)
        assertEquals("oops", cli.err)
        assertFalse(cli.truncated)
        assertFalse(cli.timedOut)
        assertFalse(cli.transportUnknown)
    }

    // #7: the detail identifies which side was null (with the wire exit).
    @Test fun detailIdentifiesNullSide() {
        val outNull = decodeExecReplyNullable(0, null, "")
        assertTrue(outNull.err.contains("null reply string"))
        assertTrue(outNull.err.contains("exit=0"))
        assertTrue(outNull.err.contains("outNull=true"))
        assertTrue(outNull.err.contains("errNull=false"))
        val errNull = decodeExecReplyNullable(0, "package:com.example.app\n", null)
        assertTrue(errNull.err.contains("null reply string"))
        assertTrue(errNull.err.contains("exit=0"))
        assertTrue(errNull.err.contains("outNull=false"))
        assertTrue(errNull.err.contains("errNull=true"))
        val bothNull = decodeExecReplyNullable(0, null, null)
        assertTrue(bothNull.err.contains("null reply string"))
        assertTrue(bothNull.err.contains("exit=0"))
        assertTrue(bothNull.err.contains("outNull=true"))
        assertTrue(bothNull.err.contains("errNull=true"))
    }

    // #8: any wire exit with a null string is discarded to exit=1 + transportUnknown.
    @Test fun discardsWireExit() {
        val cases = listOf(
            decodeExecReplyNullable(3, null, ""),
            decodeExecReplyNullable(-1, "", null),
            decodeExecReplyNullable(Int.MAX_VALUE, null, null),
        )
        for (res in cases) {
            assertEquals("wire exit must be discarded to 1, got: $res", 1, res.exit)
            assertEquals("", res.out)
            assertFalse(res.truncated)
            assertTrue(res.transportUnknown)
            assertFalse(res.timedOut)
        }
    }

    // #9: plausible commands (even marker-like or exotic whitespace) pass through.
    @Test fun validEdgeCases() {
        val valids = listOf(
            "a",
            "0",
            "echo [truncated]",
            "pm list packages\npm list users",
            "x".repeat(8000),
            "\u200B",
        )
        for (cmd in valids) {
            assertEquals("valid cmd must pass through, got: $cmd", null, badRequestForBlankCommand(cmd))
        }
        // Sole NBSP pin (deviation from report's Java-isBlank assumption, verified
        // on Kotlin/JVM): NBSP isSpaceChar=true so isBlank=true -> bad request,
        // not a pass-through. Pinned here so the real behavior never regresses.
        val nbsp = badRequestForBlankCommand("\u00A0")
        assertTrue("sole NBSP must be blank (Kotlin isSpaceChar), got: $nbsp", nbsp != null)
        assertEquals(1, nbsp!!.exit)
        assertEquals("", nbsp.out)
        assertTrue(nbsp.err.contains("bad request"))
        assertFalse(nbsp.timedOut)
        assertFalse(nbsp.transportUnknown)
    }

    // #10: mixed whitespace is still blank (bad request, pre-spawn definite).
    @Test fun mixedWhitespace() {
        val res = badRequestForBlankCommand(" \t\n\r ")
        assertTrue("mixed whitespace must be bad request, got: $res", res != null)
        assertEquals(1, res!!.exit)
        assertEquals("", res.out)
        assertTrue(res.err.contains("bad request"))
        assertFalse(res.timedOut)
        assertFalse(res.transportUnknown)
    }

    // Marker logic unaffected: non-null replies still delegate to
    // decodeExecReply, so clean-empty stays truncated=false and a marker
    // still yields truncated=true (never transportUnknown).
    @Test fun execReply_nonNullStillUsesMarkerTruncated() {
        val clean = decodeExecReplyNullable(0, "", "")
        assertEquals(0, clean.exit)
        assertFalse(clean.truncated)
        assertFalse(clean.transportUnknown)
        assertFalse(clean.timedOut)
        val marked = decodeExecReplyNullable(0, "partial\n$TRUNCATION_MARKER", "")
        assertEquals(0, marked.exit)
        assertTrue(marked.truncated)
        assertFalse(marked.transportUnknown)
        val markedErr = decodeExecReplyNullable(0, "", "partial\n$TRUNCATION_MARKER")
        assertTrue(markedErr.truncated)
        assertFalse(markedErr.transportUnknown)
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
    // upper bound, v6 = v5 marker+gate with parcel-null guards + post-spawn
    // catch marker (current contract).
    // bindUserService uses .version(SERVICE_VERSION) so Shizuku/Sui
    // rebuilds the resident UserService on upgrade (v6 invalidates v5
    // resident services, as v5 invalidated v4 resident services, v4
    // invalidated v3 resident services and v3 invalidated ungated v2
    // resident services);
    // the bind tag is per-UID derived
    // ("GalaxyDualExec-v1-u<callerUid>", see galaxyExecServiceTag) and the
    // legacy fixed tag "GalaxyDualExec-v1" record is removed by the D1
    // migration on the first run (retryable); if cleanup never succeeds
    // until restart, the orphan sits inert (daemon restart reaps).
    // SERVICE_VERSION is 6 (v6 bumped it
    // for parcel-null guards + the post-spawn catch marker; service-side
    // capture/encode code changed so the daemon must rebuild).
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
    // owner EXEC succeeds and a foreign uid is denied.
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
