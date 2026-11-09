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
    // serialNo 99): resolver Refuses -> typed Unknown -> RefreshOutcome
    // Unknown (fail closed: keep the old list, never claim NoDual / never
    // publish FRESH). The pm-list snapshot is still carried for display.
    @Test
    fun serialConflict_returnsUnknownWithProfiles() {
        val dumpsysSerial99 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=99:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val gateway = FakeGateway(dumpsysOutput = dumpsysSerial99)
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("serial conflict must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue(
            "profiles must still be carried for display, got: ${unknown.profiles}",
            unknown.profiles.any { it.userId == 95 },
        )
        assertTrue(
            "detail must name the serial conflict, got: ${unknown.detail}",
            unknown.detail.contains("serial"),
        )
        assertEquals(
            "no list probe may run once the target refuses, got: ${gateway.commands}",
            0,
            gateway.listPackagesCount,
        )
    }

    // Parent drift across verbose channels (verbose parentId=0 vs dumpsys
    // parentId=1): the resolver refuses the conflicting parent (#53), so
    // the single snapshot can never publish a FRESH listing on the drifted
    // identity -- it fails closed to Unknown with zero list probes. The
    // pinned target carries its true parentId (== 0 by policy) on the
    // happy path instead of a hardcoded null.
    @Test
    fun parentDrift_returnsUnknownAndNeverLists() {
        val dumpsysParent1 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                "    parentId=1\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val gateway = FakeGateway(dumpsysOutput = dumpsysParent1)
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("parent drift must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue(
            "detail must name the parent conflict, got: ${unknown.detail}",
            unknown.detail.contains("parent"),
        )
        assertEquals(
            "parent drift must never reach the list probe, got: ${gateway.commands}",
            0,
            gateway.listPackagesCount,
        )
        assertEquals(1, gateway.listUsersCount)
    }

    // Happy-path pin carries the resolver's true parentId (0, never null):
    // a later expected-vs-fresh continuity comparison can see parent drift
    // instead of skipping it with a null parent.
    @Test
    fun groundTruthTarget_carriesTrueParentId() {
        val gateway = FakeGateway()
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("expected GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        assertEquals(0, (outcome as RefreshOutcome.GroundTruth).target.parentId)
    }

    // An empty snapshot with a clean typed absence stays Unknown (empty
    // `pm list users` is untrustworthy -- same gate as the old
    // refreshLocked Absent check): a bare absence must never be claimed
    // as NoDual from an empty snapshot.
    @Test
    fun emptySnapshotTypedAbsent_mapsUnknown() {
        val ownerVerbose =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n"
        val ownerDumpsys =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n"
        val gateway = FakeGateway(
            listUsersOutput = "Users:\n",
            userListVerboseOutput = ownerVerbose,
            dumpsysOutput = ownerDumpsys,
            propOutput = "",
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("empty snapshot must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        assertEquals(
            "no list probe may run without a target, got: ${gateway.commands}",
            0,
            gateway.listPackagesCount,
        )
    }

    // A confirmed id missing from the pm snapshot (typed channels confirm
    // 95, but `pm list users` only lists 0) must stay Unknown -- never
    // NoDual -- so the VM keeps the old cloned table instead of clearing
    // it. Same fail-closed shape as Absent + empty snapshot above. Zero
    // list probes may run once the id is missing from the snapshot.
    @Test
    fun confirmedNotInSnapshot_mapsUnknown() {
        val gateway = FakeGateway(
            listUsersOutput = "Users:\n\tUserInfo{0:Owner:13} running\n",
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("confirmed id missing from snapshot must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertEquals("confirmed id missing from pm snapshot", unknown.detail)
        assertTrue(
            "profiles snapshot must still be carried for display, got: ${unknown.profiles}",
            unknown.profiles.any { it.userId == 0 } && unknown.profiles.none { it.userId == 95 },
        )
        assertEquals(
            "no list probe may run once the confirmed id is missing from the snapshot, got: ${gateway.commands}",
            0,
            gateway.listPackagesCount,
        )
    }

    // A serial-unavailable id missing from the pm snapshot (typed channels
    // confirm 95 with no agreed serial, but `pm list users` only lists 0)
    // must stay Unknown -- never NoDual -- so the VM keeps the old cloned
    // table instead of clearing it. 保舊表語意由 VM 保證：此處只斷言 outcome
    // Unknown 且零 list 探測。Same snapshot gate shape as Confirmed above.
    @Test
    fun serialUnavailableNotInSnapshot_mapsUnknown() {
        val verboseNoSerial95 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} running:\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"
        val dumpsysNoSerial95 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400}:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val gateway = FakeGateway(
            listUsersOutput = "Users:\n\tUserInfo{0:Owner:13} running\n",
            userListVerboseOutput = verboseNoSerial95,
            dumpsysOutput = dumpsysNoSerial95,
            propOutput = "95\n",
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("serial-unavailable id missing from snapshot must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        assertEquals(
            "no list probe may run once the serial-unavailable id is missing from the snapshot, got: ${gateway.commands}",
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
