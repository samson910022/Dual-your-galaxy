// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 2 regression tests (issue #21): shared identity fetch plus channel
// semantics. Locks that the single fetcher (CmdGateway.fetchVerifiedDualId,
// shared by DualRepository and both Elevator probes) gates the verbose,
// dumpsys AND property channels with isIdentityChannelOk (no pm-semantic
// exemptions), while the pm-list probe gate uses isListUsersOutputOk (same as
// profilesSnapshot). Uses a FakeGateway in the DualSafetyTest style.
class ElevatorVerifiedTest {
    // In-memory CmdGateway: canned identity outputs with stderr/exit control
    // per channel, plus an optional prop throw.
    private class FakeGateway(
        var listUsersOutput: String = DEFAULT_USERS_95,
        var listUsersErr: String = "",
        var listUsersExit: Int = 0,
        var listUsersTruncated: Boolean = false,
        var userListVerboseOutput: String = DEFAULT_VERBOSE_95,
        var userListVerboseErr: String = "",
        var userListVerboseExit: Int = 0,
        var dumpsysOutput: String = DEFAULT_DUMPSYS_95,
        var dumpsysErr: String = "",
        var dumpsysExit: Int = 0,
        var propOutput: String = DEFAULT_PROP_95,
        var propErr: String = "",
        var propExit: Int = 0,
        var propThrows: Boolean = false,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL

        override suspend fun run(cmd: String): CmdResult {
            if (cmd == DualRepository.CMD_GETPROP_DUAL && propThrows) {
                throw RuntimeException("getprop unavailable")
            }
            return when (cmd) {
                DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = listUsersExit, out = listUsersOutput, err = listUsersErr, truncated = listUsersTruncated)
                DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = userListVerboseExit, out = userListVerboseOutput, err = userListVerboseErr)
                DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = dumpsysExit, out = dumpsysOutput, err = dumpsysErr)
                DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = propExit, out = propOutput, err = propErr)
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
                    "    Flags: 0x00000013\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    Flags: 0x00000190\n" +
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

    private fun suGateway(delegate: FakeGateway): CmdGateway = object : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        override suspend fun run(cmd: String): CmdResult =
            if (cmd == "id -u") CmdResult(0, "0", "") else delegate.run(cmd)
    }

    // Non-zero verbose exit fails the shared fetch: dual -1 on the repo and
    // both elevator probes (channel failure, fail closed).
    @Test
    fun verboseNonZeroExit_resolvesMinusOneOnBothElevators() {
        val gateway = FakeGateway(userListVerboseExit = 1, userListVerboseOutput = "")
        assertEquals(-1, runSuspend { DualRepository(gateway).resolveDualUserId() })
        assertEquals(-1, runSuspend { DualRepository(gateway).resolveDualUserIdVerified() })
        val shizuku = runSuspend { ShizukuElevator(gateway).probe() }
        assertEquals(Privilege.SHELL, shizuku.level)
        assertEquals(-1, shizuku.dualUserId)
        val su = runSuspend { SuElevator(suGateway(gateway)).probe() }
        assertEquals(Privilege.ROOT, su.level)
        assertEquals(-1, su.dualUserId)
    }

    // A dumpsys channel that exits 0 but carries a Failure line fails the
    // identity-ok gate: -1 everywhere (the old isSuccess gate agreed here;
    // this locks the behavior under the new shared fetcher).
    @Test
    fun dumpsysWithFailureLine_exitZero_stillMinusOne() {
        val gateway = FakeGateway(
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95 +
                "\nFailure [INSTALL_FAILED_INVALID_APK: bad]",
        )
        assertEquals(-1, runSuspend { DualRepository(gateway).resolveDualUserId() })
        val shizuku = runSuspend { ShizukuElevator(gateway).probe() }
        assertEquals(Privilege.SHELL, shizuku.level)
        assertEquals(-1, shizuku.dualUserId)
        val su = runSuspend { SuElevator(suGateway(gateway)).probe() }
        assertEquals(Privilege.ROOT, su.level)
        assertEquals(-1, su.dualUserId)
    }

    // install_failed_already_exists is a pm idempotent-success semantic, NOT
    // an identity exemption: a verbose channel carrying it (with a Failure
    // line) still fails identity-ok -> -1. Under the old isSuccess gate this
    // same payload confirmed 95; the new gate must refuse.
    @Test
    fun alreadyExistsTextDoesNotExemptIdentityChannel() {
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95 +
                "\nFailure [INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install " +
                "com.example.chat without first uninstalling.]",
        )
        // Sanity: the pm gate still treats this payload as success.
        assertTrue(
            PmOutputParser.isSuccess(gateway.userListVerboseOutput, 0),
        )
        assertEquals(-1, runSuspend { DualRepository(gateway).resolveDualUserId() })
        assertEquals(-1, runSuspend { ShizukuElevator(gateway).probe() }.dualUserId)
        assertEquals(-1, runSuspend { SuElevator(suGateway(gateway)).probe() }.dualUserId)
    }

    // Phase C P2-2: a throwing property channel is "no claim" (null) and
    // now yields Unknown (property required); probe levels are unchanged.
    @Test
    fun propThrow_resolvesMinusOneOnTwoChannels() {
        val gateway = FakeGateway(propThrows = true)
        assertEquals(-1, runSuspend { DualRepository(gateway).resolveDualUserId() })
        assertEquals(-1, runSuspend { DualRepository(gateway).resolveDualUserIdVerified() })
        val shizuku = runSuspend { ShizukuElevator(gateway).probe() }
        assertEquals(Privilege.SHELL, shizuku.level)
        assertEquals(-1, shizuku.dualUserId)
        val su = runSuspend { SuElevator(suGateway(gateway)).probe() }
        assertEquals(Privilege.ROOT, su.level)
        assertEquals(-1, su.dualUserId)
    }

    // Multi-line dirty property output parses to null (no claim), so two
    // agreeing CLONE channels now yield Unknown (property required).
    @Test
    fun propDirtyMultiline_resolvesMinusOne() {
        val gateway = FakeGateway(propOutput = "95\n95\n")
        assertEquals(null, PmOutputParser.parseDualPropertyId(gateway.propOutput))
        assertEquals(-1, runSuspend { DualRepository(gateway).resolveDualUserId() })
        assertEquals(-1, runSuspend { ShizukuElevator(gateway).probe() }.dualUserId)
        assertEquals(-1, runSuspend { SuElevator(suGateway(gateway)).probe() }.dualUserId)
    }

    // pm-list probe gate shares isListUsersOutputOk with profilesSnapshot: exit 0 with
    // a Failure line fails the gate. Shizuku drops to NONE/-1; su keeps ROOT
    // (root proof stands) with dual -1.
    @Test
    fun pmListUsersWithFailureLine_shizukuNone_suRootMinusOne() {
        val gateway = FakeGateway(
            listUsersOutput = FakeGateway.DEFAULT_USERS_95,
            listUsersErr = "Failure [INSTALL_FAILED_INVALID_APK: bad]",
            listUsersExit = 0,
        )
        // Sanity: the pm channel itself sees the failure.
        assertTrue(runSuspend { DualRepository(gateway).listProfiles() }.isEmpty())

        val shizuku = runSuspend { ShizukuElevator(gateway).probe() }
        assertEquals(Privilege.NONE, shizuku.level)
        assertEquals(false, shizuku.hasShizuku)
        assertEquals(-1, shizuku.dualUserId)

        val su = runSuspend { SuElevator(suGateway(gateway)).probe() }
        assertEquals(Privilege.ROOT, su.level)
        assertEquals(true, su.hasRoot)
        assertEquals(-1, su.dualUserId)
    }

    // Truncated `pm list users` fails closed on both elevator probes (same as
    // profilesSnapshot truncated -> ChannelError): Shizuku drops to NONE/-1;
    // su keeps ROOT (root proof stands) with dual -1.
    @Test
    fun pmListUsersTruncated_shizukuNone_suRootMinusOne() {
        val gateway = FakeGateway(listUsersTruncated = true)

        val shizuku = runSuspend { ShizukuElevator(gateway).probe() }
        assertEquals(Privilege.NONE, shizuku.level)
        assertEquals(false, shizuku.hasShizuku)
        assertEquals(-1, shizuku.dualUserId)

        val su = runSuspend { SuElevator(suGateway(gateway)).probe() }
        assertEquals(Privilege.ROOT, su.level)
        assertEquals(true, su.hasRoot)
        assertEquals(-1, su.dualUserId)
    }

    // Pure-function contract of the new gate: realistic full verbose/dumpsys
    // dumps pass; any Failure line (or non-zero exit) fails.
    @Test
    fun isIdentityChannelOk_realisticDumps() {
        assertTrue(PmOutputParser.isIdentityChannelOk(FakeGateway.DEFAULT_VERBOSE_95, 0))
        assertTrue(PmOutputParser.isIdentityChannelOk(FakeGateway.DEFAULT_DUMPSYS_95, 0))
        assertFalse(
            PmOutputParser.isIdentityChannelOk(
                FakeGateway.DEFAULT_VERBOSE_95 + "\nFailure [INSTALL_FAILED_INVALID_APK: bad]",
                0,
            ),
        )
        assertFalse(
            PmOutputParser.isIdentityChannelOk(
                FakeGateway.DEFAULT_DUMPSYS_95 + "\nFailure [INSTALL_FAILED_INVALID_APK: bad]",
                0,
            ),
        )
        assertFalse(PmOutputParser.isIdentityChannelOk(FakeGateway.DEFAULT_VERBOSE_95, 1))
        // No ALREADY_EXISTS exemption on the identity channel.
        assertFalse(
            PmOutputParser.isIdentityChannelOk(
                "Failure [INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install " +
                    "com.example.chat without first uninstalling.]",
                0,
            ),
        )
    }
}
