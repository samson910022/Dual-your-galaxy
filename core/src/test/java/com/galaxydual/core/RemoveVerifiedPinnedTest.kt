// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// PR-33 R4 regression tests: final-boundary race on removeVerifiedExpected.
// The pinned remove re-fetches the serial-bound target AFTER the cancel gate
// and BEFORE any uninstall; a serial/id change landing in that window must
// refuse with zero uninstalls (SerialMismatch / TargetMoved). Uses a
// FakeGateway with mutable dualId/serial (no device).
class RemoveVerifiedPinnedTest {
    private class FakeGateway(
        var dualId: Int = 95,
        var serial: Int = 15,
        var uninstallOut: String = "Success",
        var uninstallExit: Int = 0,
        var listPackagesOut: String = "",
        var listPackagesExit: Int = 0,
        // Serial-unavailable simulation (PR-33 R2 P1): omits the serial line
        // on both verbose channels (dumpsys loses serialNo=, verbose loses
        // Serial:) so fetchDualTarget maps to SerialUnavailable.
        var noSerial: Boolean = false,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        var uninstallRuns = 0

        // Final-boundary race hook: fires at the second verbose read (the
        // fetchDualTarget round inside removeVerifiedExpected), i.e. after
        // the cancel gate and before any uninstall could run. Mutating
        // dualId/serial here simulates a profile recreated mid-confirm.
        var onBeforeUninstall: (() -> Unit)? = null
        private var verboseCalls = 0
        private var hookFired = false

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                (if (noSerial) "" else "    Serial: $serial\n") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400}" + (if (noSerial) "" else " serialNo=$serial") + ":\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        private fun listUsersOut(): String =
            "Users:\n" +
                "\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DUAL_APP:400} running\n"

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = listUsersOut(), err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                    verboseCalls++
                    if (verboseCalls == 2 && !hookFired) {
                        hookFired = true
                        onBeforeUninstall?.invoke()
                    }
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                }
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = "$dualId\n", err = "")
                cmd.startsWith("pm uninstall") -> {
                    uninstallRuns++
                    CmdResult(exit = uninstallExit, out = uninstallOut, err = "")
                }
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOut, err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }
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

    // Final boundary: serial flips 15 -> 41 between the pinned snapshot and
    // the serial-bound re-fetch -> SerialMismatch, zero uninstalls.
    @Test
    fun finalBoundary_serialFlip_returnsSerialMismatchWithZeroUninstall() {
        val gateway = FakeGateway()
        gateway.onBeforeUninstall = { gateway.serial = 41 }
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected SerialMismatch, got: $outcome", outcome is RemoveOutcome.SerialMismatch)
        assertEquals(15, (outcome as RemoveOutcome.SerialMismatch).expected)
        assertEquals(41, outcome.actual)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run on serial mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Final boundary: dual id flips 95 -> 96 (verbose/dumpsys/property follow,
    // label untouched) -> TargetMoved, zero uninstalls.
    @Test
    fun finalBoundary_idFlip_returnsTargetMovedWithZeroUninstall() {
        val gateway = FakeGateway()
        gateway.onBeforeUninstall = { gateway.dualId = 96 }
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected TargetMoved, got: $outcome", outcome is RemoveOutcome.TargetMoved)
        assertEquals(95, (outcome as RemoveOutcome.TargetMoved).expected)
        assertEquals(96, outcome.verified)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run on target move, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Cancel gate: isStillValid false -> Cancelled before any uninstall.
    @Test
    fun cancelledGate_skipsUninstall() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected(
                "com.example.chat",
                95,
                15,
                isStillValid = { false },
            )
        }
        assertTrue("expected Cancelled, got: $outcome", outcome is RemoveOutcome.Cancelled)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run after cancel, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Second gate: first isStillValid passes, second (after the serial
    // gate, before uninstall) refuses -> Cancelled with zero uninstalls.
    // No state flip: serial 15 stays pinned, so only the second gate can
    // refuse. Proves the serial-gate -> uninstall check-to-use window is
    // narrowed (still non-atomic; the user path is covered by the UI
    // non-cancellable guard).
    @Test
    fun secondGate_cancelBetweenGates_returnsCancelled() {
        val gateway = FakeGateway()
        var calls = 0
        val gate = { ++calls == 1 }
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected(
                "com.example.chat",
                95,
                15,
                isStillValid = gate,
            )
        }
        assertTrue("expected Cancelled, got: $outcome", outcome is RemoveOutcome.Cancelled)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run after second-gate cancel, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Wrong pinned serial is refused without running uninstall.
    @Test
    fun wrongExpectedSerial_refuses() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 999)
        }
        assertTrue("expected SerialMismatch, got: $outcome", outcome is RemoveOutcome.SerialMismatch)
        assertEquals(999, (outcome as RemoveOutcome.SerialMismatch).expected)
        assertEquals(15, outcome.actual)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run on serial mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Happy path: pinned id+serial agree through the final boundary ->
    // one uninstall, clean-empty post-check -> Success.
    @Test
    fun happyPath_pinnedSuccess() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(95, (outcome as RemoveOutcome.Success).dualId)
        assertEquals(1, gateway.uninstallRuns)
        assertEquals(
            "pm uninstall --user 95 com.example.chat",
            gateway.commands.single { it.startsWith("pm uninstall") },
        )
    }

    // Null expected serial means the caller cannot verify at all: refused
    // directly as SerialMismatch(null, actual) with zero uninstalls.
    @Test
    fun nullExpectedSerial_refusesWithoutUninstall() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, null)
        }
        assertTrue("expected SerialMismatch, got: $outcome", outcome is RemoveOutcome.SerialMismatch)
        assertEquals(null, (outcome as RemoveOutcome.SerialMismatch).expected)
        assertEquals(15, outcome.actual)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run on serial mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Both verbose channels carry no serial (id confirmed, serial missing):
    // SerialMismatch(expected, null) with zero uninstalls.
    @Test
    fun bothSerialMissing_returnsSerialMismatchWithZeroUninstall() {
        val gateway = FakeGateway(noSerial = true)
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected SerialMismatch, got: $outcome", outcome is RemoveOutcome.SerialMismatch)
        assertEquals(15, (outcome as RemoveOutcome.SerialMismatch).expected)
        assertEquals(null, outcome.actual)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run on serial mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }
}
