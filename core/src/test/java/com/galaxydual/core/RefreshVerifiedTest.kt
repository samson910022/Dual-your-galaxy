// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase D P2-D regression tests (issue #21): single-target refresh.
// refreshVerified() pins snapshot + target + listing to one id
// (snapshot 1 + target 3 cmds + list 1 = 5), so resolve-to-list drift is
// inexpressible. Uses a FakeGateway (no device).
class RefreshVerifiedTest {
    // In-memory gateway with per-channel counters so the 9->5 convergence
    // (old resolve-then-reverify listing needed 9 commands) is locked.
    private class FakeGateway(
        var listUsersOutput: String = DEFAULT_USERS_95,
        var listUsersExit: Int = 0,
        var listUsersErr: String = "",
        var userListVerboseOutput: String = DEFAULT_VERBOSE_95,
        var userListVerboseExit: Int = 0,
        var dumpsysOutput: String = DEFAULT_DUMPSYS_95,
        var dumpsysExit: Int = 0,
        var propOutput: String = DEFAULT_PROP_95,
        var propExit: Int = 0,
        var listPackagesOutput: String = "package:com.example.chat\npackage:com.example.mail\n",
        var listPackagesExit: Int = 0,
        var listPackagesErr: String = "",
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        var listUsersCount: Int = 0
        var verboseCount: Int = 0
        var dumpsysCount: Int = 0
        var propCount: Int = 0
        var listPackagesCount: Int = 0

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS -> {
                    listUsersCount++
                    CmdResult(exit = listUsersExit, out = listUsersOutput, err = listUsersErr)
                }
                cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                    verboseCount++
                    CmdResult(exit = userListVerboseExit, out = userListVerboseOutput, err = "")
                }
                cmd == DualRepository.CMD_DUMPSYS_USER -> {
                    dumpsysCount++
                    CmdResult(exit = dumpsysExit, out = dumpsysOutput, err = "")
                }
                cmd == DualRepository.CMD_GETPROP_DUAL -> {
                    propCount++
                    CmdResult(exit = propExit, out = propOutput, err = "")
                }
                cmd.startsWith("pm list packages") -> {
                    listPackagesCount++
                    CmdResult(exit = listPackagesExit, out = listPackagesOutput, err = listPackagesErr)
                }
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        companion object {
            const val DEFAULT_USERS_95 =
                "Users:\n" +
                    "\tUserInfo{0:Owner:13} running\n" +
                    "\tUserInfo{95:DUAL_APP:400} running\n"
            const val DEFAULT_VERBOSE_95 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    Serial: 15\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
            const val DEFAULT_DUMPSYS_95 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.CLONE\n"
            const val DEFAULT_PROP_95 = "95\n"
        }
    }

    // Minimal runBlocking substitute (core has no coroutines dependency):
    // works because FakeGateway never actually suspends.
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

    // Normal path: single target 95, ground-truth listing returned.
    @Test
    fun normal_returnsGroundTruth95() {
        val gateway = FakeGateway()
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("expected GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        val ground = outcome as RefreshOutcome.GroundTruth
        assertEquals(95, ground.target.userId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), ground.cloned)
        assertTrue("profiles must contain the target for display", ground.profiles.any { it.userId == 95 })
    }

    // Serial conflict across verbose channels (verbose serial 15 vs dumpsys
    // serialNo 99): resolver Refuses -> fetchVerifiedTarget null -> NoDual,
    // but the pm-list snapshot is still carried for display.
    @Test
    fun serialConflict_returnsNoDualWithProfiles() {
        val dumpsysSerial99 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=99:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val gateway = FakeGateway(dumpsysOutput = dumpsysSerial99)
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("serial conflict must be NoDual, got: $outcome", outcome is RefreshOutcome.NoDual)
        val noDual = outcome as RefreshOutcome.NoDual
        assertTrue(
            "profiles must still be carried for display, got: ${noDual.profiles}",
            noDual.profiles.any { it.userId == 95 },
        )
        assertEquals(
            "no list probe may run once the target refuses, got: ${gateway.commands}",
            0,
            gateway.listPackagesCount,
        )
    }

    // One refreshVerified is exactly 5 commands: pm-list-users 1 + verbose 1
    // + dumpsys 1 + prop 1 + pm-list-packages 1 (old resolve-then-reverify
    // listing path needed 9: snapshot 1 + target 3 + snapshot 1 + target 3 +
    // list 1). Locks the 9->5 convergence.
    @Test
    fun commandCount_isExactlyFive() {
        val gateway = FakeGateway()
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("expected GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        assertEquals(1, gateway.listUsersCount)
        assertEquals(1, gateway.verboseCount)
        assertEquals(1, gateway.dumpsysCount)
        assertEquals(1, gateway.propCount)
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(5, gateway.commands.size)
    }

    // List channel failure maps to GroundTruth with cloned == null (unknown,
    // caller keeps the old display list). Note: refreshVerified uses the
    // no-retry listProbe (single gateway.run, no sleep), so this test is fast
    // by construction; the retry+sleep path belongs to verifyListProbe
    // (covered by VerifyRetryTest) and is intentionally not exercised here.
    @Test
    fun listChannelFailure_returnsGroundTruthWithNullCloned() {
        val gateway = FakeGateway(
            listPackagesOutput = "",
            listPackagesErr = "error: closed",
            listPackagesExit = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("expected GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        val ground = outcome as RefreshOutcome.GroundTruth
        assertEquals(95, ground.target.userId)
        assertNull(ground.cloned)
        assertTrue(ground.profiles.any { it.userId == 95 })
    }
}
