// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase A1 (P0 security): JVM unit tests for the GalaxyExecService caller
// gate. The Binder/Parcel round-trip is a framework stub on the JVM and is
// NOT covered here -- only the pure isAllowedCaller() seam plus the frozen
// denied-reply wire text are pinned.
// Gate-before-read ordering (onTransact reads Binder.getCallingUid() and
// denies BEFORE reading the cmd Parcel), the BuildConfig.DEBUG log line,
// and the writeDenied Parcel write order are device-only (android.os.Parcel /
// Binder are android.jar stubs on the JVM that throw "Stub!") and are NOT
// JVM-testable here; only the encodeExecReply/decodeExecReply mapping is
// pinned.
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import com.galaxydual.core.TRUNCATION_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyExecAuthTest {
    @Test fun allowedCaller_ownerUidIsTrue() {
        assertTrue(isAllowedCaller(10234, 10234))
        // Degenerate root owner must fail closed: root (0) is a shared
        // privileged uid and never a legitimate expected owner, so (0, 0)
        // is denied (only expected >= 10000, AID_APP_START, can match).
        assertFalse(isAllowedCaller(0, 0))
    }

    @Test fun allowedCaller_foreignAppUidIsFalse() {
        assertFalse(isAllowedCaller(10245, 10234))
    }

    @Test fun allowedCaller_shellPeerIsFalse() {
        // Service runs as shell (2000); a shell-peer caller is NOT the owner.
        assertFalse(isAllowedCaller(2000, 10234))
    }

    @Test fun allowedCaller_rootPeerIsFalse() {
        // Service runs as root (0); a root-peer caller is NOT the owner,
        // and a root expected is never legitimate (shared privileged uid,
        // only expected >= 10000/AID_APP_START can match).
        assertFalse(isAllowedCaller(0, 10234))
    }

    @Test fun allowedCaller_negativeExpectedAlwaysFalse() {
        // Daemon default-constructor path (expectedAppUid = -1): fail closed.
        assertFalse(isAllowedCaller(10234, -1))
        assertFalse(isAllowedCaller(2000, -1))
        assertFalse(isAllowedCaller(0, -1))
        assertFalse(isAllowedCaller(-1, -1))
    }

    @Test fun allowedCaller_negativeCallingUidAlwaysFalse() {
        // Negative calling uid is never a legitimate app uid, even against
        // a boundary-legal expected owner.
        assertFalse(isAllowedCaller(-1, 10234))
        assertFalse(isAllowedCaller(-1, 10000))
        assertFalse(isAllowedCaller(-1, -1))
    }

    @Test fun allowedCaller_privilegedMatchingPairsDenied() {
        // Shared privileged/system uids must never match even when equal:
        // system (1000) and shell (2000) are shared across processes, so
        // uid equality is not authentication for them.
        assertFalse(isAllowedCaller(1000, 1000))
        assertFalse(isAllowedCaller(2000, 2000))
        // Just below AID_APP_START (10000): never a legitimate owner.
        assertFalse(isAllowedCaller(9999, 9999))
    }

    @Test fun allowedCaller_aidAppStartBoundaryMatches() {
        // Boundary: AID_APP_START (10000) is the smallest legitimate
        // expected owner uid, so the exact pair matches.
        assertTrue(isAllowedCaller(10000, 10000))
    }

    @Test fun allowedCaller_intExtremes() {
        // Extreme uids: equality plus the >= 10000 leg decides, no overflow
        // surprises in the pure comparison.
        assertTrue(isAllowedCaller(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(isAllowedCaller(Int.MAX_VALUE, 10234))
        assertFalse(isAllowedCaller(Int.MIN_VALUE, Int.MIN_VALUE))
    }

    @Test fun galaxyTag_sameUidStableRepeated() {
        // Same full uid yields the identical tag on every call (stable service
        // identity across binds for that user instance).
        assertEquals("GalaxyDualExec-v1-u10134", galaxyExecServiceTag(10134))
        assertEquals("GalaxyDualExec-v1-u10134", galaxyExecServiceTag(10134))
        assertEquals("GalaxyDualExec-v1-u10134", galaxyExecServiceTag(10134))
        assertEquals(galaxyExecServiceTag(10134), galaxyExecServiceTag(10134))
    }

    @Test fun galaxyTag_ownerVsSecondaryDistinct() {
        // Owner (user 0) vs secondary users must derive distinct tags so
        // Shizuku keeps separate service records per user instance.
        val owner = galaxyExecServiceTag(10234)
        assertNotEquals(owner, galaxyExecServiceTag(1010234)) // user 10
        assertNotEquals(owner, galaxyExecServiceTag(9510234)) // user 95
        assertNotEquals(owner, galaxyExecServiceTag(10245)) // different app
        assertNotEquals(galaxyExecServiceTag(1010234), galaxyExecServiceTag(9510234))
    }

    @Test fun galaxyTag_embedsFullUidNotAppId() {
        // Full uid (userId*100000 + appId) is embedded, never the appId alone:
        // user 10 uid 1010234 must not collapse to the owner appId tag.
        assertEquals("GalaxyDualExec-v1-u1010234", galaxyExecServiceTag(1010234))
        assertNotEquals("GalaxyDualExec-v1-u10234", galaxyExecServiceTag(1010234))
        assertEquals("GalaxyDualExec-v1-u9510234", galaxyExecServiceTag(9510234))
    }

    @Test fun galaxyTag_legacyFixedTagNeverProduced() {
        // The legacy fixed tag is never bound by the derived-tag path.
        for (uid in listOf(10134, 10234, 10245, 10000, 1010234, 9510234, 2000, 0)) {
            assertNotEquals("GalaxyDualExec-v1", galaxyExecServiceTag(uid))
            assertNotEquals(LEGACY_GALAXY_EXEC_TAG, galaxyExecServiceTag(uid))
        }
    }

    @Test fun galaxyTag_hasPerUidPrefix() {
        // Every derived tag carries the per-UID prefix.
        for (uid in listOf(10134, 10234, 1010234, 9510234, 10245)) {
            assertTrue(
                "tag for uid $uid must start with GalaxyDualExec-v1-u, got: ${galaxyExecServiceTag(uid)}",
                galaxyExecServiceTag(uid).startsWith("GalaxyDualExec-v1-u"),
            )
            assertTrue(galaxyExecServiceTag(uid).startsWith(GALAXY_EXEC_TAG_PREFIX))
        }
    }

    @Test fun deniedReply_isFrozenExitOneEmptyOutWithPrefix() {
        assertEquals("denied: foreign caller", ShizukuExecProtocol.DENIED_FOREIGN_CALLER)
        assertTrue(
            ShizukuExecProtocol.DENIED_FOREIGN_CALLER.startsWith("denied:"),
        )
        // Frozen wire triple: exit=1, out="", err=DENIED (the exact layout
        // writeDenied writes to the reply Parcel).
        assertEquals(
            Triple(1, "", ShizukuExecProtocol.DENIED_FOREIGN_CALLER),
            encodeExecReply(CmdResult(1, "", ShizukuExecProtocol.DENIED_FOREIGN_CALLER)),
        )
    }

    @Test fun deniedReply_decodesAsDefiniteFailureNotUnknown() {
        // The denied reply must decode as a plain definite failure (exit=1,
        // no truncation marker) and must NOT collide with the unknown-after-
        // send backstops: it carries neither the post-spawn transport
        // prefixes nor the service/client timeout texts, so the repository
        // never reconciles a refused call as "maybe executed".
        val decoded = decodeExecReply(1, "", ShizukuExecProtocol.DENIED_FOREIGN_CALLER)
        assertEquals(1, decoded.exit)
        assertEquals("", decoded.out)
        assertEquals(ShizukuExecProtocol.DENIED_FOREIGN_CALLER, decoded.err)
        assertFalse(decoded.truncated)
        assertFalse(decoded.transportUnknown)
        assertFalse(decoded.timedOut)
        assertFalse(
            "denied err must not match the post-spawn transport prefix, got: ${decoded.err}",
            decoded.err.startsWith("service exec failed: "),
        )
        assertFalse(
            "denied err must not match the interrupted backstop, got: ${decoded.err}",
            decoded.err.startsWith("interrupted: "),
        )
        assertFalse(
            "denied err must not match the service timeout text, got: ${decoded.err}",
            decoded.err.startsWith("timeout after "),
        )
        assertFalse(
            "denied err must not match the client timeout text, got: ${decoded.err}",
            decoded.err.startsWith("shizuku: exec timed out after "),
        )
        assertFalse(decoded.out.contains(TRUNCATION_MARKER))
        assertFalse(decoded.err.contains(TRUNCATION_MARKER))
    }

    @Test fun deniedReply_doesNotCollideWithAnyUnknownBackstop() {
        // Anchored prefixes mirrored exactly from core/CmdGateway.kt (trailing
        // spaces included): isServiceTimeoutText ("timeout after ",
        // "shizuku: exec timed out after ") and isPostSpawnTransportText
        // ("interrupted: ", "service exec failed: "). The denied text must
        // hit none of them, so isUnknownAfterSend stays false.
        val denied = ShizukuExecProtocol.DENIED_FOREIGN_CALLER
        for (prefix in listOf("timeout after ", "shizuku: exec timed out after ", "interrupted: ", "service exec failed: ")) {
            assertFalse(
                "denied err must not start with unknown backstop prefix '$prefix', got: $denied",
                denied.startsWith(prefix),
            )
        }
        assertFalse(denied.contains(TRUNCATION_MARKER))
        val decoded = decodeExecReply(1, "", denied)
        assertFalse(decoded.truncated)
        assertFalse(decoded.transportUnknown)
        assertFalse(decoded.timedOut)
        // Equality negatives against the lost-reply error factories: the
        // denied wire text is a refusal, never a lost reply.
        assertNotEquals(denied, ShizukuExecErrors.timedOut().err)
        assertNotEquals(denied, ShizukuExecErrors.transportUnknown("boom").err)
    }

    @Test fun deniedReply_encodeDecodeRoundTripPreservesDefiniteFailure() {
        // writeDenied delegates to encodeExecReply, so encode -> decode must
        // preserve the frozen definite-failure triple exactly.
        val (exit, out, err) = encodeExecReply(CmdResult(1, "", ShizukuExecProtocol.DENIED_FOREIGN_CALLER))
        assertEquals(1, exit)
        assertEquals("", out)
        assertEquals(ShizukuExecProtocol.DENIED_FOREIGN_CALLER, err)
        val decoded = decodeExecReply(exit, out, err)
        assertEquals(1, decoded.exit)
        assertEquals("", decoded.out)
        assertEquals(ShizukuExecProtocol.DENIED_FOREIGN_CALLER, decoded.err)
        assertFalse(decoded.truncated)
        assertFalse(decoded.transportUnknown)
        assertFalse(decoded.timedOut)
    }
}
