// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// PR-37 Phase B (P1 #2) regression tests: cloneVerifiedExpected atomic pin.
// The expected (id, serial) is pinned inside a single flow (snapshot +
// resolve + serial-bound compare); any mismatch refuses with TargetMismatch
// and zero installs. Uses a FakeGateway with mutable dualId/serial (no device).
class CloneVerifiedExpectedTest {
    private class FakeGateway(
        var dualId: Int = 95,
        var serial: Int = 15,
        // When true, the verbose/dumpsys blocks keep the CLONE type but carry
        // no serial line (id confirms, serial does not -> SerialUnavailable).
        var omitSerial: Boolean = false,
        var listUsersExit: Int = 0,
        var installOut: String = "Package com.example.chat installed for user: 95",
        var installExit: Int = 0,
        var listPackagesOut: String = "package:com.example.chat\n",
        var listPackagesExit: Int = 0,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                // omitSerial keeps the header + CLONE type but drops the
                // serial line (id confirms, serial does not).
                (if (omitSerial) "" else "    Serial: $serial\n") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String {
            // omitSerial keeps the header + CLONE type but drops the serialNo
            // (id confirms, serial does not).
            val header95 = if (omitSerial) "  UserInfo{$dualId:DualApp:400}:\n"
            else "  UserInfo{$dualId:DualApp:400} serialNo=$serial:\n"
            return "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                header95 +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        }

        private fun listUsersOut(): String =
            "Users:\n" +
                "\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DUAL_APP:400} running\n"

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    if (listUsersExit != 0) CmdResult(exit = listUsersExit, out = "", err = "denied")
                    else CmdResult(exit = 0, out = listUsersOut(), err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = "$dualId\n", err = "")
                cmd.startsWith("pm install-existing") ->
                    CmdResult(exit = installExit, out = installOut, err = "")
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOut, err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        fun installRuns(): Int = commands.count { it.startsWith("pm install-existing") }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(r: Result<T>) {
                    result = r
                }
            },
        )
        return (result ?: error("suspend block never resumed")).getOrThrow()
    }

    // Happy path: pinned (id, serial) agrees through the whole flow ->
    // one install on the pinned id, clean post-check -> Success.
    @Test
    fun happyPath_pinnedMatch_installsOnce() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(95, (outcome as CloneOutcome.Success).dualId)
        assertEquals(1, gateway.installRuns())
        assertEquals(
            "pm install-existing --user 95 com.example.chat",
            gateway.commands.single { it.startsWith("pm install-existing") },
        )
    }

    // Id moved (95 pinned, 96 live): TargetMismatch at the id gate,
    // zero installs.
    @Test
    fun idFlip_returnsTargetMismatchWithZeroInstall() {
        val gateway = FakeGateway(dualId = 96, serial = 15)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        val mismatch = outcome as CloneOutcome.TargetMismatch
        assertEquals(95, mismatch.expectedId)
        assertEquals(96, mismatch.actualId)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run on target mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Same id, fresh serial (recreated profile): TargetMismatch at the serial
    // gate, zero installs.
    @Test
    fun serialFlip_returnsTargetMismatchWithZeroInstall() {
        val gateway = FakeGateway(dualId = 95, serial = 41)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        val mismatch = outcome as CloneOutcome.TargetMismatch
        assertEquals(95, mismatch.expectedId)
        assertEquals(95, mismatch.actualId)
        assertEquals(15, mismatch.expectedSerial)
        assertEquals(41, mismatch.actualSerial)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run on serial mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Null expected serial (caller cannot verify): refused without installing.
    @Test
    fun nullExpectedSerial_refusesWithZeroInstall() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, null)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run without a verifiable expected serial, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Live serial unavailable (CLONE type confirmed, no serial line):
    // TargetMismatch with a null actual serial, zero installs.
    @Test
    fun serialUnavailable_returnsTargetMismatchWithZeroInstall() {
        val gateway = FakeGateway(omitSerial = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        val mismatch = outcome as CloneOutcome.TargetMismatch
        assertEquals(95, mismatch.expectedId)
        assertEquals(null, mismatch.actualSerial)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run when the live serial is unavailable, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Channel failure short-circuits before any write.
    @Test
    fun channelError_runsNoWrite() {
        val gateway = FakeGateway(listUsersExit = 1)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError, got: $outcome", outcome is CloneOutcome.ChannelError)
        assertTrue(
            "no install may run on channel error, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Bad package token with a matching pin: InvalidPackage, never executed.
    @Test
    fun invalidPackage_neverExecutes() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("bad pkg; rm -rf", 95, 15)
        }
        assertTrue("expected InvalidPackage, got: $outcome", outcome is CloneOutcome.InvalidPackage)
        assertTrue(
            "no install may run for an invalid package, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }
}
