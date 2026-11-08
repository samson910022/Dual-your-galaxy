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
        // Extreme uids: equality plus the appId gate decides (appIdOf strips
        // the user offset via remainder; no overflow surprises in the pure
        // comparison). Int.MAX_VALUE appIdOf = 83647 (>= 10000), so the pair
        // is true -- overflow-safe remainder property, not a realism claim
        // (no real uid is Int.MAX_VALUE).
        assertTrue(isAllowedCaller(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(isAllowedCaller(Int.MAX_VALUE, 10234))
        assertFalse(isAllowedCaller(Int.MIN_VALUE, Int.MIN_VALUE))
    }

    @Test fun appIdOf_stripsUserOffset() {
        // Full uid = userId*100000 + appId; appIdOf keeps only appId.
        assertEquals(10234, appIdOf(10234)) // owner, no offset
        assertEquals(10234, appIdOf(1010234)) // user 10
        assertEquals(10234, appIdOf(9510234)) // user 95
        assertEquals(0, appIdOf(1000000)) // user 10 root
        assertEquals(1000, appIdOf(1001000)) // user 10 system
        assertEquals(2000, appIdOf(1002000)) // user 10 shell
        assertEquals(9999, appIdOf(1009999)) // just below AID_APP_START
        assertEquals(10000, appIdOf(1010000)) // user 10 app start
        assertEquals(83647, appIdOf(Int.MAX_VALUE)) // overflow-safe remainder
        // Kotlin % remainder sign follows the dividend (never floorMod):
        // negatives stay negative and fail the appId gate (fail closed).
        assertEquals(-1, appIdOf(-1))
        assertEquals(-1, appIdOf(-100001))
    }

    @Test fun isApplicationUid_boundary() {
        // Boundary (owner): AID_APP_START is the smallest application appId.
        assertTrue(isApplicationUid(10000))
        assertFalse(isApplicationUid(9999))
        assertFalse(isApplicationUid(0)) // root
        assertFalse(isApplicationUid(1000)) // system
        assertFalse(isApplicationUid(2000)) // shell
        // Secondary user (user 10 offset = 10*100000 = 1000000): gate strips
        // the user offset first, so privileged appIds stay denied regardless
        // of user while app uids succeed.
        assertTrue(isApplicationUid(1010000)) // user 10 app start
        assertTrue(isApplicationUid(1010234)) // user 10 app
        assertFalse(isApplicationUid(1009999)) // user 10 just below start
        assertFalse(isApplicationUid(1001000)) // user 10 system
        assertFalse(isApplicationUid(1002000)) // user 10 shell
        assertFalse(isApplicationUid(1000000)) // user 10 root
        // Negatives stay false (uid >= 0 leg + remainder sign).
        assertFalse(isApplicationUid(-1))
        assertFalse(isApplicationUid(-100001))
        assertFalse(isApplicationUid(Int.MIN_VALUE))
    }

    @Test fun allowedCaller_secondaryPrivilegedDenied() {
        // Secondary-user (user 10) privileged/system full-uid pairs must be
        // denied even when equal: appIds 0/1000/2000 are always < 10000
        // regardless of user offset.
        assertFalse(isAllowedCaller(1001000, 1001000)) // user 10 system
        assertFalse(isAllowedCaller(1002000, 1002000)) // user 10 shell
        assertFalse(isAllowedCaller(1000000, 1000000)) // user 10 root
        assertFalse(isAllowedCaller(1009999, 1009999)) // just below start
        // Secondary-user application uids succeed on exact full-uid match.
        assertTrue(isAllowedCaller(1010000, 1010000)) // user 10 app start
        assertTrue(isAllowedCaller(1010234, 1010234)) // user 10 app
    }

    @Test fun allowedCaller_crossUserAntiWeakening() {
        // Equality stays full-UID (never appId equality): a same-appId uid
        // from a different user never matches, in both directions.
        assertFalse(isAllowedCaller(10234, 1010234)) // owner vs user 10
        assertFalse(isAllowedCaller(1010234, 10234)) // user 10 vs owner
        assertFalse(isAllowedCaller(10234, 9510234)) // owner vs user 95
    }

    @Test fun resolveExpectedAppUid_cases() {
        // Fail-closed expected-uid resolution: full uid kept only when it is
        // an application uid (non-negative, appId >= AID_APP_START).
        assertEquals(-1, resolveExpectedAppUid(1001000)) // user 10 system
        assertEquals(1010234, resolveExpectedAppUid(1010234)) // user 10 app
        assertEquals(-1, resolveExpectedAppUid(-1)) // negative
        assertEquals(-1, resolveExpectedAppUid(0)) // root
        assertEquals(-1, resolveExpectedAppUid(2000)) // shell
        assertEquals(-1, resolveExpectedAppUid(9999)) // just below start
        assertEquals(10000, resolveExpectedAppUid(10000)) // owner app start
        assertEquals(1010000, resolveExpectedAppUid(1010000)) // user 10 app start
        assertEquals(-1, resolveExpectedAppUid(Int.MIN_VALUE)) // negative extreme
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

    @Test fun supportedVersion_boundary() {
        // Phase C3 (P2 pre-v13 gate): MIN_SHIZUKU_VERSION = 13 (first server
        // API with the Context constructor, Changelog 13.0.0); -1/0/old
        // rejected, 13/14 accepted. Pure isSupportedShizukuVersion() seam.
        assertEquals(13, ShizukuExecProtocol.MIN_SHIZUKU_VERSION)
        assertFalse(isSupportedShizukuVersion(-1))
        assertFalse(isSupportedShizukuVersion(0))
        assertFalse(isSupportedShizukuVersion(11))
        assertFalse(isSupportedShizukuVersion(12))
        assertTrue(isSupportedShizukuVersion(13))
        assertTrue(isSupportedShizukuVersion(14))
    }

    @Test fun unsupportedVersion_isDefiniteFailure() {
        // Pre-dispatch refusal: exit != 0, echoes version + v13 requirement,
        // neither unknown flag, and must not hit any unknown-after-send
        // backstop nor the DENIED wire text.
        val res = ShizukuExecErrors.unsupportedVersion(12)
        assertNotEquals(0, res.exit)
        assertTrue("must contain 'unsupported', got: ${res.err}", res.err.contains("unsupported"))
        assertTrue("must contain 'v13', got: ${res.err}", res.err.contains("v13"))
        assertTrue("must echo version 12, got: ${res.err}", res.err.contains("12"))
        assertFalse(res.timedOut)
        assertFalse(res.transportUnknown)
        for (prefix in listOf("service exec failed: ", "interrupted: ", "timeout after ", "shizuku: exec timed out after ", "may have executed")) {
            assertFalse(
                "unsupportedVersion err must not start with backstop prefix '$prefix', got: ${res.err}",
                res.err.startsWith(prefix),
            )
        }
        assertNotEquals(ShizukuExecProtocol.DENIED_FOREIGN_CALLER, res.err)
    }
}
