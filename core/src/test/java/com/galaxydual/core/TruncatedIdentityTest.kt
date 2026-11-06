// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Review follow-up (cross-PR risk, review lines 49-51): identity evidence
// truncation must fail closed. isSuccess()/isIdentityChannelOk (#21 gates)
// only judge exit + error-semantic lines, so an exit-0 truncated capture
// would otherwise parse as a partial candidate set and could hide a
// conflicting CLONE candidate yet still confirm a single id. These tests lock
// that every truncated identity capture refuses without parsing:
// `pm list users` truncation -> profilesSnapshot ChannelError (downstream
// verify/list/clone/remove take their existing fail-closed paths), and any
// verified-channel (`cmd user list -v` / `dumpsys user` / getprop) truncation
// -> fetchVerifiedDualId -1 (unknown/refused). isSuccess() itself is
// unchanged (install/uninstall small outputs keep the exit + error-line rule);
// #21 owns WHO may be targeted, #22 owns HOW a capture is trusted.
class TruncatedIdentityTest {
    private class FakeGateway(
        var listUsersOut: String = DEFAULT_USERS,
        var listUsersExit: Int = 0,
        var listUsersTruncated: Boolean = false,
        var verboseOut: String = DEFAULT_VERBOSE_95,
        var verboseExit: Int = 0,
        var verboseTruncated: Boolean = false,
        var dumpsysOut: String = DEFAULT_DUMPSYS_95,
        var dumpsysExit: Int = 0,
        var dumpsysTruncated: Boolean = false,
        var propOut: String = DEFAULT_PROP_95,
        var propExit: Int = 0,
        var propTruncated: Boolean = false,
        var listPackagesOut: String = "package:com.example.chat\n",
        var listPackagesExit: Int = 0,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = listUsersExit, out = listUsersOut, err = "", truncated = listUsersTruncated)
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = verboseExit, out = verboseOut, err = "", truncated = verboseTruncated)
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = dumpsysExit, out = dumpsysOut, err = "", truncated = dumpsysTruncated)
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = propExit, out = propOut, err = "", truncated = propTruncated)
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOut, err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        companion object {
            const val DEFAULT_USERS =
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

    // `pm list users` truncation is identity-evidence truncation: the snapshot
    // must refuse without parsing, and every downstream entry takes its
    // existing fail-closed path (verify Unknown, list null, pinned
    // clone/remove ChannelError, no writes).
    @Test
    fun profilesSnapshot_truncatedListUsers_isChannelError_downstreamFailClosed() {
        val gateway = FakeGateway(listUsersTruncated = true)
        val repo = DualRepository(gateway)

        val snap = runSuspend { repo.profilesSnapshot() }
        assertTrue("truncated snapshot must be ChannelError, got: $snap", snap is ProfilesSnapshot.ChannelError)
        assertTrue(
            "detail must name truncation, got: \"${(snap as ProfilesSnapshot.ChannelError).detail}\"",
            snap.detail.contains("truncat", ignoreCase = true),
        )

        assertTrue(
            runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Unknown,
        )
        assertNull(runSuspend { repo.listClonedPackages() })
        assertTrue(
            runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.ChannelError,
        )
        assertTrue(
            runSuspend { repo.removeVerified("com.example.chat") } is RemoveOutcome.ChannelError,
        )
        assertTrue(
            "no write may run on truncated snapshot, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") || it.startsWith("pm uninstall") },
        )
    }

    // Truncated `cmd user list -v` hides possible conflicting candidates, so
    // the shared fetch must refuse (-1) even though exit is 0 and the visible
    // prefix confirms 95. Downstream: auto resolution -1, explicit verify
    // Unknown, listing null, pinned flows NoDual.
    @Test
    fun fetchVerifiedDualId_truncatedVerbose_isMinusOne_downstreamNoDual() {
        val gateway = FakeGateway(verboseTruncated = true)
        val repo = DualRepository(gateway)

        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        assertEquals(-1, runSuspend { repo.resolveDualUserIdVerified() })
        assertTrue(
            runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Unknown,
        )
        assertNull(runSuspend { repo.listClonedPackages() })
        assertTrue(runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual)
        assertTrue(runSuspend { repo.removeVerified("com.example.chat") } is RemoveOutcome.NoDual)
        // Both elevator probes share the same fetcher: same refusal.
        assertEquals(-1, runSuspend { ShizukuElevator(gateway).probe() }.dualUserId)
    }

    // Truncated `dumpsys user` likewise refuses, even with a clean exit and a
    // confirming visible prefix.
    @Test
    fun fetchVerifiedDualId_truncatedDumpsys_isMinusOne() {
        val gateway = FakeGateway(dumpsysTruncated = true)
        val repo = DualRepository(gateway)

        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        assertTrue(
            runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Unknown,
        )
        assertNull(runSuspend { repo.listClonedPackages() })
        assertTrue(runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual)
        assertTrue(runSuspend { repo.removeVerified("com.example.chat") } is RemoveOutcome.NoDual)
    }

    // A truncated property is NOT "no claim": the cut payload may hide a
    // mismatch, so it refuses (-1) instead of letting two agreeing channels
    // confirm.
    @Test
    fun fetchVerifiedDualId_truncatedProp_isMinusOne() {
        val gateway = FakeGateway(propTruncated = true)
        val repo = DualRepository(gateway)

        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        assertTrue(
            runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Unknown,
        )
        assertNull(runSuspend { repo.listClonedPackages() })
    }
}
