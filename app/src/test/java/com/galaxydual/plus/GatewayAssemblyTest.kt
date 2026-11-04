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

import com.galaxydual.core.Privilege
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
            ShizukuExecErrors.timedOut() to "timed out",
        )
        for ((res, needle) in cases) {
            assertNotEquals("exit must be != 0 for $res", 0, res.exit)
            assertTrue("err '$res' should contain '$needle'", res.err.contains(needle))
        }
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
}
