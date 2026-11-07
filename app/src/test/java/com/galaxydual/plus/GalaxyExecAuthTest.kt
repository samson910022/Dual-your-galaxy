// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase A1 (P0 security): JVM unit tests for the GalaxyExecService caller
// gate. The Binder/Parcel round-trip is a framework stub on the JVM and is
// NOT covered here -- only the pure isAllowedCaller() seam plus the frozen
// denied-reply wire text are pinned.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyExecAuthTest {
    @Test fun allowedCaller_ownerUidIsTrue() {
        assertTrue(isAllowedCaller(10234, 10234))
        // Degenerate owner-is-root case: the == holds when expected is 0.
        assertTrue(isAllowedCaller(0, 0))
    }

    @Test fun allowedCaller_foreignAppUidIsFalse() {
        assertFalse(isAllowedCaller(10245, 10234))
    }

    @Test fun allowedCaller_shellPeerIsFalse() {
        // Service runs as shell (2000); a shell-peer caller is NOT the owner.
        assertFalse(isAllowedCaller(2000, 10234))
    }

    @Test fun allowedCaller_rootPeerIsFalse() {
        // Service runs as root (0); a root-peer caller is NOT the owner
        // unless the expected owner itself is 0.
        assertFalse(isAllowedCaller(0, 10234))
    }

    @Test fun allowedCaller_negativeExpectedAlwaysFalse() {
        // Daemon default-constructor path (expectedUid = -1): fail closed.
        assertFalse(isAllowedCaller(10234, -1))
        assertFalse(isAllowedCaller(2000, -1))
        assertFalse(isAllowedCaller(0, -1))
        assertFalse(isAllowedCaller(-1, -1))
    }

    @Test fun deniedReply_isFrozenExitOneEmptyOutWithPrefix() {
        assertEquals("denied: foreign caller", ShizukuExecProtocol.DENIED_FOREIGN_CALLER)
        assertTrue(
            ShizukuExecProtocol.DENIED_FOREIGN_CALLER.startsWith("denied:"),
        )
    }

    @Test fun deniedReply_decodesAsDefiniteFailureNotUnknown() {
        // The denied reply must decode as a plain definite failure (exit=1,
        // no truncation marker) and must NOT collide with the unknown-after-
        // send backstops: it carries neither the post-spawn transport prefix
        // ("service exec failed: ...", see GalaxyExecService catch) nor the
        // client-wait timeout text, so the repository never reconciles a
        // refused call as "maybe executed".
        val decoded = decodeExecReply(1, "", ShizukuExecProtocol.DENIED_FOREIGN_CALLER)
        assertEquals(1, decoded.exit)
        assertFalse(decoded.truncated)
        assertFalse(decoded.transportUnknown)
        assertFalse(decoded.timedOut)
        assertFalse(
            "denied err must not match the post-spawn transport prefix, got: ${decoded.err}",
            decoded.err.startsWith("service exec failed: "),
        )
        assertFalse(
            "denied err must not match the service timeout text, got: ${decoded.err}",
            decoded.err.startsWith("shizuku: exec timed out"),
        )
        assertFalse(
            "denied err must not equal the timeout error, got: ${decoded.err}",
            decoded.err == ShizukuExecErrors.timedOut().err,
        )
    }
}
