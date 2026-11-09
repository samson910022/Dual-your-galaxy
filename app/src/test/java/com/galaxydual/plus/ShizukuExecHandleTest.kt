// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// DEVICE-UNVERIFIED：無裝置，未經實機驗證。
// Phase B JVM pure extraction (owner P2 test gap, wire-neutral, no
// SERVICE_VERSION bump -- still 6, side effects still pending
// instrumentation): JVM unit tests for the pure EXEC decision seam
// handleExecRequest() plus the pure post-spawn failure / stale-cache drop
// decisions extracted from GalaxyExecService.onTransact and transactExec.
// The Binder/Parcel round-trip itself (android.os.Parcel / Binder are
// android.jar stubs on the JVM that throw "Stub!"), the actual process
// spawn, and the real rebind stay device-only and are NOT covered or
// claimed verified here -- these tests pin only the pure decisions the
// wiring onTransact/transactExec delegate to, never the wiring itself
// (test names deliberately avoid onTransact_*/transactExec_* prefixes so
// JVM coverage is never misread as binder verification).
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import com.galaxydual.core.PmOutputParser
import com.galaxydual.core.TRUNCATION_MARKER
import com.galaxydual.core.TrustedList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuExecHandleTest {
    // Foreign caller is denied before any execution: exec must never be
    // called, and the denied result is the frozen definite failure
    // (exit=1, out="", err=DENIED_FOREIGN_CALLER, no flags, no backstop
    // text) -- the writeDenied equivalent as a CmdResult.
    @Test fun handleExecRequest_foreignCaller_neverCallsExec() {
        var calls = 0
        val res = handleExecRequest(callingUid = 10245, expectedAppUid = 10234, rawCmd = "pm list packages") {
            calls++
            CmdResult(exit = 0, out = "", err = "")
        }
        assertEquals("foreign caller must never reach exec", 0, calls)
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertEquals(ShizukuExecProtocol.DENIED_FOREIGN_CALLER, res.err)
        assertFalse("denied must not carry truncated, got: $res", res.truncated)
        assertFalse("denied must not carry timedOut, got: $res", res.timedOut)
        assertFalse("denied must not carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse(
            "denied err must not hit the post-spawn backstop, got: ${res.err}",
            res.err.startsWith("service exec failed: "),
        )
    }

    // Null/blank wire cmd (incl. mixed whitespace) is a pre-spawn bad
    // request for the owner caller: exec must never be called, exit=1,
    // no flags, err contains "bad request".
    @Test fun handleExecRequest_nullOrBlank_neverCallsExec() {
        for (blank in listOf<String?>(null, "", "   ", "\n\t")) {
            var calls = 0
            val res = handleExecRequest(callingUid = 10234, expectedAppUid = 10234, rawCmd = blank) {
                calls++
                CmdResult(exit = 0, out = "", err = "")
            }
            assertEquals("blank cmd must never reach exec (rawCmd=$blank)", 0, calls)
            assertEquals(1, res.exit)
            assertEquals("", res.out)
            assertTrue(
                "bad request err must contain 'bad request' (rawCmd=$blank), got: ${res.err}",
                res.err.contains("bad request"),
            )
            assertFalse("bad request must not carry truncated (rawCmd=$blank), got: $res", res.truncated)
            assertFalse("bad request must not carry timedOut (rawCmd=$blank), got: $res", res.timedOut)
            assertFalse("bad request must not carry transportUnknown (rawCmd=$blank), got: $res", res.transportUnknown)
        }
    }

    // A usable command reaches exec exactly once and verbatim (no trim,
    // no re-quote): the cmd string is passed through byte-for-byte.
    @Test fun handleExecRequest_validCmd_callsExecOnceVerbatim() {
        var calls = 0
        var seen: String? = null
        val raw = "  pm list packages --user 95  "
        val res = handleExecRequest(callingUid = 10234, expectedAppUid = 10234, rawCmd = raw) { cmd ->
            calls++
            seen = cmd
            CmdResult(exit = 0, out = "package:com.example.app\n", err = "")
        }
        assertEquals(1, calls)
        assertEquals("cmd must reach exec verbatim (no trim)", raw, seen)
        assertEquals(0, res.exit)
        assertEquals("package:com.example.app\n", res.out)
        assertEquals("", res.err)
    }

    // exec throwing a generic (Runtime) exception is post-spawn unknown
    // by construction: exit=1, anchored "service exec failed: " prefix,
    // truncated=true, transportUnknown=true, trailing marker. Round-trip
    // through encode/decode re-derives truncated from the marker (the
    // anchored prefix check below is the JVM-visible stand-in for the
    // core-internal isPostSpawnTransportText, which is not visible from
    // :app tests), and the parsed result is Unknown, never TrustedFull.
    @Test fun handleExecRequest_execThrows_mapsToPostSpawnBackstop() {
        var calls = 0
        val res = handleExecRequest(callingUid = 10234, expectedAppUid = 10234, rawCmd = "pm list packages") {
            calls++
            throw RuntimeException("boom")
        }
        assertEquals(1, calls)
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertTrue(
            "post-spawn err must start with the anchored backstop, got: ${res.err}",
            res.err.startsWith("service exec failed: "),
        )
        assertTrue("post-spawn err must carry the detail, got: ${res.err}", res.err.contains("boom"))
        assertTrue("post-spawn err must end with the marker, got: ${res.err}", res.err.endsWith("\n$TRUNCATION_MARKER"))
        assertTrue("post-spawn must carry truncated, got: $res", res.truncated)
        assertTrue("post-spawn must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("post-spawn must not carry timedOut, got: $res", res.timedOut)
        val (exit, out, err) = encodeExecReply(res)
        val cli = decodeExecReply(exit, out, err)
        assertEquals(1, cli.exit)
        assertEquals(res.err, cli.err)
        assertTrue("marker must re-derive truncated over the wire, got: $cli", cli.truncated)
        assertTrue(
            "anchored prefix must still hit isPostSpawnTransportText (via startsWith), got: ${cli.err}",
            cli.err.startsWith("service exec failed: "),
        )
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("post-spawn reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    // exec throwing InterruptedException maps to the interrupted
    // post-spawn backstop ("service exec failed: interrupted: ...") with
    // the same marker/flags, and restores the interrupt flag on the
    // calling (binder) thread -- the original onTransact catch performed
    // Thread.currentThread().interrupt(); handleExecRequest preserves it
    // on the onTransact call path. The flag is consumed here so later
    // tests in the same thread stay clean.
    @Test fun handleExecRequest_execThrowsInterrupted_mapsToInterruptedBackstop() {
        var calls = 0
        val res = handleExecRequest(callingUid = 10234, expectedAppUid = 10234, rawCmd = "pm list packages") {
            calls++
            throw InterruptedException("pool interrupt")
        }
        // Consume-and-check the restored flag so it cannot leak into
        // sibling tests sharing this thread.
        val flagRestored = Thread.interrupted()
        assertEquals(1, calls)
        assertTrue("interrupt flag must be restored on the binder thread", flagRestored)
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertTrue(
            "interrupted err must start with the anchored backstop, got: ${res.err}",
            res.err.startsWith("service exec failed: "),
        )
        assertTrue("interrupted err must carry 'interrupted: ', got: ${res.err}", res.err.contains("interrupted: "))
        assertTrue("interrupted err must carry the detail, got: ${res.err}", res.err.contains("pool interrupt"))
        assertTrue("interrupted err must end with the marker, got: ${res.err}", res.err.endsWith("\n$TRUNCATION_MARKER"))
        assertTrue("interrupted must carry truncated, got: $res", res.truncated)
        assertTrue("interrupted must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("interrupted must not carry timedOut, got: $res", res.timedOut)
        val (exit, out, err) = encodeExecReply(res)
        val cli = decodeExecReply(exit, out, err)
        assertTrue("marker must re-derive truncated over the wire, got: $cli", cli.truncated)
        assertTrue(
            "anchored prefix must still hit isPostSpawnTransportText (via startsWith), got: ${cli.err}",
            cli.err.startsWith("service exec failed: "),
        )
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("interrupted reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    // Direct pin of the pure generic post-spawn mapper (Phase 1 generic
    // catch, extracted 1:1): exact wire text plus marker tail and flags,
    // round-trip Unknown.
    @Test fun servicePostSpawnFailure_carriesMarkerAndUnknownFlags() {
        val res = servicePostSpawnFailure(RuntimeException("boom"))
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertEquals("service exec failed: boom\n$TRUNCATION_MARKER", res.err)
        assertTrue("must carry truncated, got: $res", res.truncated)
        assertTrue("must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("must not carry timedOut, got: $res", res.timedOut)
        val (exit, out, err) = encodeExecReply(res)
        val cli = decodeExecReply(exit, out, err)
        assertTrue("marker must re-derive truncated over the wire, got: $cli", cli.truncated)
        assertTrue(cli.err.startsWith("service exec failed: "))
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("post-spawn reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    // Direct pin of the pure Interrupted post-spawn mapper (Phase 1
    // Interrupted catch, extracted 1:1): exact wire text with the
    // "interrupted: " infix plus marker tail and flags. Pure: calling it
    // must not set/clear the thread interrupt flag (that restoration
    // lives in handleExecRequest on the binder thread).
    @Test fun serviceInterruptedFailure_carriesMarkerAndUnknownFlags() {
        Thread.interrupted() // clear any leaked flag before the purity check
        val res = serviceInterruptedFailure(InterruptedException("stop"))
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertEquals("service exec failed: interrupted: stop\n$TRUNCATION_MARKER", res.err)
        assertTrue("must carry truncated, got: $res", res.truncated)
        assertTrue("must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("must not carry timedOut, got: $res", res.timedOut)
        // Purity: the mapper itself never touches the interrupt flag.
        assertFalse("pure mapper must not set the interrupt flag", Thread.interrupted())
        val (exit, out, err) = encodeExecReply(res)
        val cli = decodeExecReply(exit, out, err)
        assertTrue("marker must re-derive truncated over the wire, got: $cli", cli.truncated)
        assertTrue(cli.err.startsWith("service exec failed: "))
        val parsed = PmOutputParser.parsePackagesTrusted(cli)
        assertTrue("interrupted reply must parse Unknown, got $parsed", parsed is TrustedList.Unknown)
    }

    // Null-reply derived transportUnknown must drop the stale cached
    // binding (transactExec rebinds fresh): covers null out, null err,
    // and both-null (each is a post-dispatch reply loss, never clean).
    @Test fun shouldDropCachedBinding_nullReplyDerivedDropsBinding() {
        val cases = listOf(
            decodeExecReplyNullable(0, null, ""),
            decodeExecReplyNullable(0, "package:com.example.app\n", null),
            decodeExecReplyNullable(0, null, null),
        )
        for (res in cases) {
            assertTrue("null-reply result must carry transportUnknown, got: $res", res.transportUnknown)
            assertTrue("null-reply result must drop the cached binding, got: $res", shouldDropCachedBinding(res))
        }
        // Direct transportUnknown factory result drops as well.
        assertTrue(shouldDropCachedBinding(ShizukuExecErrors.transportUnknown("reply lost")))
    }

    // Normal replies must never drop the cached binding: clean success,
    // clean empty, explicit pm failure, and marker-truncated (decode
    // re-derives truncated but never transportUnknown) all keep it.
    @Test fun shouldDropCachedBinding_normalKeepsBinding() {
        val cases = listOf(
            CmdResult(exit = 0, out = "package:com.example.app\n", err = ""),
            CmdResult(exit = 0, out = "", err = ""),
            CmdResult(exit = 1, out = "", err = "Failure [INSTALL_FAILED_INVALID_APK]"),
            decodeExecReplyNullable(0, "partial\n$TRUNCATION_MARKER", ""),
        )
        for (res in cases) {
            assertFalse("normal result must not carry transportUnknown, got: $res", res.transportUnknown)
            assertFalse("normal result must keep the cached binding, got: $res", shouldDropCachedBinding(res))
        }
    }

    // The decision is exactly transportUnknown: a timedOut result (client
    // wait expiry, dropped on its own timeout path in transactExec) is
    // not a shouldDropCachedBinding drop -- pinning the equality so a
    // future `|| timedOut` weakening cannot sneak in unnoticed.
    @Test fun shouldDropCachedBinding_timedOutAloneKeepsBinding() {
        val res = ShizukuExecErrors.timedOut()
        assertTrue(res.timedOut)
        assertFalse(res.transportUnknown)
        assertFalse("timedOut alone must not be a shouldDropCachedBinding drop, got: $res", shouldDropCachedBinding(res))
    }
}
