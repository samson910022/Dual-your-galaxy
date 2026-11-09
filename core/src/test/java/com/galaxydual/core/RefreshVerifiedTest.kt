// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase D P2-D regression tests (issue #21): single-target refresh.
// refreshVerified() pins snapshot + target + listing to one id with a
// post-list fence (snapshot 1 + target 3 + list 1 + re-verify 3 = 8), so
// resolve-to-list drift fails closed to Unknown. Uses a FakeGateway (no device).
class RefreshVerifiedTest {
    // In-memory gateway with per-channel counters so the command budget
    // is locked. Optional second-incarnation outputs + per-channel flip
    // thresholds model an intra-refresh world switch: after N calls on a
    // channel the gateway serves the second incarnation's output (list
    // output likewise keyed to the incarnation so old vs new packages
    // are distinguishable).
    private class FakeGateway(
        var listUsersOutput: String = DEFAULT_USERS_95,
        var listUsersExit: Int = 0,
        var listUsersErr: String = "",
        var userListVerboseOutput: String = DEFAULT_VERBOSE_95,
        var userListVerboseExit: Int = 0,
        var userListVerboseTruncated: Boolean = false,
        var dumpsysOutput: String = DEFAULT_DUMPSYS_95,
        var dumpsysExit: Int = 0,
        var propOutput: String = DEFAULT_PROP_95,
        var propExit: Int = 0,
        var verboseThrows: Boolean = false,
        var listPackagesOutput: String = "package:com.example.chat\npackage:com.example.mail\n",
        var listPackagesExit: Int = 0,
        var listPackagesErr: String = "",
        var secondUserListVerboseOutput: String? = null,
        var secondDumpsysOutput: String? = null,
        var secondPropOutput: String? = null,
        var secondListPackagesOutput: String? = null,
        var verboseFlipAfter: Int = Int.MAX_VALUE,
        var dumpsysFlipAfter: Int = Int.MAX_VALUE,
        var propFlipAfter: Int = Int.MAX_VALUE,
        var listPackagesFlipAfter: Int = Int.MAX_VALUE,
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
                    if (verboseThrows) throw IOException("boom: $cmd")
                    val second = secondUserListVerboseOutput
                    val out = if (verboseCount > verboseFlipAfter && second != null) {
                        second
                    } else {
                        userListVerboseOutput
                    }
                    CmdResult(exit = userListVerboseExit, out = out, err = "", truncated = userListVerboseTruncated)
                }
                cmd == DualRepository.CMD_DUMPSYS_USER -> {
                    dumpsysCount++
                    val second = secondDumpsysOutput
                    val out = if (dumpsysCount > dumpsysFlipAfter && second != null) {
                        second
                    } else {
                        dumpsysOutput
                    }
                    CmdResult(exit = dumpsysExit, out = out, err = "")
                }
                cmd == DualRepository.CMD_GETPROP_DUAL -> {
                    propCount++
                    val second = secondPropOutput
                    val out = if (propCount > propFlipAfter && second != null) {
                        second
                    } else {
                        propOutput
                    }
                    CmdResult(exit = propExit, out = out, err = "")
                }
                cmd.startsWith("pm list packages") -> {
                    listPackagesCount++
                    val second = secondListPackagesOutput
                    val out = if (listPackagesCount > listPackagesFlipAfter && second != null) {
                        second
                    } else {
                        listPackagesOutput
                    }
                    CmdResult(exit = listPackagesExit, out = out, err = listPackagesErr)
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
            const val VERBOSE_95_41 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    Serial: 41\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
            const val DUMPSYS_95_41 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} serialNo=41:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.CLONE\n"
            const val VERBOSE_NO_SERIAL_95 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
            const val DUMPSYS_NO_SERIAL_95 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400}:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.CLONE\n"
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
        val gateway = FakeGateway(
            listUsersOutput = "Users:\n\tUserInfo{0:Owner:13} running\n",
            userListVerboseOutput = FakeGateway.VERBOSE_NO_SERIAL_95,
            dumpsysOutput = FakeGateway.DUMPSYS_NO_SERIAL_95,
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

    // One refreshVerified is exactly 8 commands: pm-list-users 1 + verbose 2
    // + dumpsys 2 + prop 2 + pm-list-packages 1 (snapshot 1 + first triple 3
    // + list 1 + post-list re-verify triple 3).
    @Test
    fun commandCount_isExactlyEight() {
        val gateway = FakeGateway()
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("expected GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        assertEquals(1, gateway.listUsersCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(8, gateway.commands.size)
    }

    // List channel failure maps to GroundTruth with cloned == null (unknown,
    // caller keeps the old display list). Note: refreshVerified uses the
    // no-retry listProbe (single gateway.run, no sleep), so this test is fast
    // by construction; the retry+sleep path belongs to verifyListProbe
    // (covered by VerifyRetryTest) and is intentionally not exercised here.
    // A null list with a passing post-list fence still publishes GroundTruth
    // with cloned == null.
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

    // Post-list serial switch (95,15 -> 95,41 between the first triple and
    // the post-list re-verify; the list itself already serves the new
    // incarnation's packages): the fence proves the new target, so it
    // returns TargetMoved with the live (95,41,0) -- never publish the
    // fictional FRESH(95,15,newList); the listed payload is discarded.
    @Test
    fun postListSwitchToNewSerial_returnsTargetMoved() {
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            listPackagesOutput = "package:com.example.old\n",
            secondUserListVerboseOutput = FakeGateway.VERBOSE_95_41,
            secondDumpsysOutput = FakeGateway.DUMPSYS_95_41,
            secondPropOutput = "95\n",
            secondListPackagesOutput = "package:com.example.new\n",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
            listPackagesFlipAfter = 0,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("post-list serial switch must be TargetMoved, got: $outcome", outcome is RefreshOutcome.TargetMoved)
        val moved = outcome as RefreshOutcome.TargetMoved
        assertEquals(95, moved.liveTarget.userId)
        assertEquals(41, moved.liveTarget.serial)
        assertEquals(0, moved.liveTarget.parentId)
        assertTrue("profiles must still be carried, got: ${moved.profiles}", moved.profiles.any { it.userId == 95 })
        assertTrue("detail must be non-blank, got: '${moved.detail}'", moved.detail.isNotBlank())
        assertTrue("detail must name post-list drift, got: ${moved.detail}", moved.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listUsersCount)
    }

    // Switch between list and re-verify: first triple AND list see
    // (95,15)+oldList, the post-list re-verify sees (95,41). The distinct
    // Confirmed triple proves the move -> TargetMoved (95,41,0); the list
    // payload is discarded and never becomes GroundTruth.
    @Test
    fun postListSwitchBetweenListAndReverify_returnsTargetMoved() {
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            listPackagesOutput = "package:com.example.old\n",
            secondUserListVerboseOutput = FakeGateway.VERBOSE_95_41,
            secondDumpsysOutput = FakeGateway.DUMPSYS_95_41,
            secondPropOutput = "95\n",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("list->reverify switch must be TargetMoved, got: $outcome", outcome is RefreshOutcome.TargetMoved)
        val moved = outcome as RefreshOutcome.TargetMoved
        assertEquals(95, moved.liveTarget.userId)
        assertEquals(41, moved.liveTarget.serial)
        assertEquals(0, moved.liveTarget.parentId)
        assertTrue("profiles must still be carried, got: ${moved.profiles}", moved.profiles.any { it.userId == 95 })
        assertTrue("detail must be non-blank, got: '${moved.detail}'", moved.detail.isNotBlank())
        assertTrue("detail must name post-list drift, got: ${moved.detail}", moved.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listUsersCount)
    }

    // Post-list id switch (95 -> 96 between first triple and re-verify):
    // re-verify Confirmed(96,41) differs from pinned (95,15) -> TargetMoved
    // with the live (96,41,0), never FRESH/NoDual on the stale snapshot.
    @Test
    fun postListSwitchToDifferentId_returnsTargetMoved() {
        val verbose96_41 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{96:DualApp:400} running:\n" +
                "    Serial: 41\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"
        val dumpsys96_41 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{96:DualApp:400} serialNo=41:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            listPackagesOutput = "package:com.example.old\n",
            secondUserListVerboseOutput = verbose96_41,
            secondDumpsysOutput = dumpsys96_41,
            secondPropOutput = "96\n",
            secondListPackagesOutput = "package:com.example.new96\n",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
            listPackagesFlipAfter = 0,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("post-list id switch must be TargetMoved, got: $outcome", outcome is RefreshOutcome.TargetMoved)
        val moved = outcome as RefreshOutcome.TargetMoved
        assertEquals(96, moved.liveTarget.userId)
        assertEquals(41, moved.liveTarget.serial)
        assertEquals(0, moved.liveTarget.parentId)
        assertTrue("profiles must still be carried, got: ${moved.profiles}", moved.profiles.any { it.userId == 95 })
        assertTrue("detail must be non-blank, got: '${moved.detail}'", moved.detail.isNotBlank())
        assertTrue("detail must name post-list drift, got: ${moved.detail}", moved.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listUsersCount)
    }

    // Post-list parent drift: first triple agrees on parent 0, the re-verify
    // triple sees verbose 0 vs dumpsys 1 and refuses -> Unknown.
    @Test
    fun postListParentDrift_returnsUnknown() {
        val dumpsysParent1 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                "    parentId=1\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            secondDumpsysOutput = dumpsysParent1,
            verboseFlipAfter = Int.MAX_VALUE,
            dumpsysFlipAfter = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("post-list parent drift must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue("profiles must still be carried, got: ${unknown.profiles}", unknown.profiles.any { it.userId == 95 })
        assertTrue("detail must name post-list drift, got: ${unknown.detail}", unknown.detail.contains("post-list"))
        assertTrue("detail must name the parent conflict, got: ${unknown.detail}", unknown.detail.contains("parent"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
    }

    // Post-list vanish: first triple confirms (95,15), the re-verify sees an
    // owner-only world (dual gone). This is Unknown, not Absent: the reused
    // pmIds {0,95} hit the pm-veto (DualRepository resolveDualTargetTyped:
    // pm snapshot contains ids missing from typed channels) because the
    // typed channels now claim only {0}. The fence maps any re-verify
    // Absent/Unknown to Unknown -- never NoDual, never FRESH; the Absent ->
    // false fence arm is defensive/unreachable with reused pmIds.
    @Test
    fun postListVanish_returnsUnknownNeverNoDual() {
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
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            secondUserListVerboseOutput = ownerVerbose,
            secondDumpsysOutput = ownerDumpsys,
            secondPropOutput = "",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("post-list vanish must be Unknown (never NoDual), got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue("profiles must still be carried, got: ${unknown.profiles}", unknown.profiles.any { it.userId == 95 })
        assertTrue("detail must be non-blank, got: '${unknown.detail}'", unknown.detail.isNotBlank())
        assertTrue("detail must name post-list drift, got: ${unknown.detail}", unknown.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
    }

    // Confirmed -> SerialUnavailable flip across the list: first triple pins
    // (95,15), the re-verify sees the same id with no serial. Strict
    // null-sensitive equality rejects the flip -> Unknown.
    @Test
    fun postListSerialBecomesUnavailable_returnsUnknown() {
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            secondUserListVerboseOutput = FakeGateway.VERBOSE_NO_SERIAL_95,
            secondDumpsysOutput = FakeGateway.DUMPSYS_NO_SERIAL_95,
            secondPropOutput = "95\n",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("serial flip must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue("profiles must still be carried, got: ${unknown.profiles}", unknown.profiles.any { it.userId == 95 })
        assertTrue("detail must name post-list drift, got: ${unknown.detail}", unknown.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listUsersCount)
    }

    // Null-list fence-fail: list channel fails (null) but the re-verify shows
    // drift. The fence must win over the null-list GroundTruth: TargetMoved
    // with the live (95,41,0), not GroundTruth with a new targetId.
    @Test
    fun postListDriftWithNullList_returnsTargetMoved() {
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            listPackagesOutput = "",
            listPackagesErr = "error: closed",
            listPackagesExit = 1,
            secondUserListVerboseOutput = FakeGateway.VERBOSE_95_41,
            secondDumpsysOutput = FakeGateway.DUMPSYS_95_41,
            secondPropOutput = "95\n",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("null-list drift must be TargetMoved, got: $outcome", outcome is RefreshOutcome.TargetMoved)
        val moved = outcome as RefreshOutcome.TargetMoved
        assertEquals(95, moved.liveTarget.userId)
        assertEquals(41, moved.liveTarget.serial)
        assertEquals(0, moved.liveTarget.parentId)
        assertTrue("profiles must still be carried, got: ${moved.profiles}", moved.profiles.any { it.userId == 95 })
        assertTrue("detail must be non-blank, got: '${moved.detail}'", moved.detail.isNotBlank())
        assertTrue("detail must name post-list drift, got: ${moved.detail}", moved.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listUsersCount)
    }

    // Post-list SerialUnavailable with a different id: pinned Confirmed
    // (95,15), the re-verify sees SerialUnavailable(96,null,0). A different
    // proven id is a move -> TargetMoved with the live (96,null,0), while
    // the same-id serial loss (postListSerialBecomesUnavailable) stays
    // Unknown. The pm snapshot still lists {0,95}; the re-verify runs with
    // the reused pmIds, and the typed 96/null world still resolves as
    // SerialUnavailable without tripping the pm veto check for this shape.
    @Test
    fun postListSerialUnavailableDifferentId_returnsTargetMoved() {
        val verbose96NoSerial =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{96:DualApp:400} running:\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"
        val dumpsys96NoSerial =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{96:DualApp:400}:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            propOutput = "95\n",
            secondUserListVerboseOutput = verbose96NoSerial,
            secondDumpsysOutput = dumpsys96NoSerial,
            secondPropOutput = "96\n",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("serial-unavailable different-id must be TargetMoved, got: $outcome", outcome is RefreshOutcome.TargetMoved)
        val moved = outcome as RefreshOutcome.TargetMoved
        assertEquals(96, moved.liveTarget.userId)
        assertNull("live serial must stay null for a serial-unavailable target, got: ${moved.liveTarget}", moved.liveTarget.serial)
        assertEquals(0, moved.liveTarget.parentId)
        assertTrue("profiles must still be carried, got: ${moved.profiles}", moved.profiles.any { it.userId == 95 })
        assertTrue("detail must be non-blank, got: '${moved.detail}'", moved.detail.isNotBlank())
        assertTrue("detail must name post-list drift, got: ${moved.detail}", moved.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listUsersCount)
    }

    // Reverse flip: SerialUnavailable -> Confirmed across the list. First
    // triple pins (95, null, 0) (no agreed serial), the re-verify sees
    // Confirmed(95,15). Strict null-sensitive equality rejects the flip
    // (15 != null) -> Unknown.
    @Test
    fun postListSerialBecomesConfirmed_returnsUnknown() {
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.VERBOSE_NO_SERIAL_95,
            dumpsysOutput = FakeGateway.DUMPSYS_NO_SERIAL_95,
            propOutput = "95\n",
            secondUserListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95,
            secondDumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95,
            secondPropOutput = "95\n",
            verboseFlipAfter = 1,
            dumpsysFlipAfter = 1,
            propFlipAfter = 1,
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("serial-unavailable -> confirmed flip must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue("profiles must still be carried, got: ${unknown.profiles}", unknown.profiles.any { it.userId == 95 })
        assertTrue("detail must name post-list drift, got: ${unknown.detail}", unknown.detail.contains("post-list"))
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listUsersCount)
    }

    // ChannelError 0-ident lock: `pm list users` itself fails (exit 1) ->
    // RefreshOutcome.ChannelError, and no identity/list channel is probed.
    @Test
    fun listUsersFailure_returnsChannelErrorWithZeroIdentityProbes() {
        val gateway = FakeGateway(listUsersExit = 1)
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("list-users failure must be ChannelError, got: $outcome", outcome is RefreshOutcome.ChannelError)
        assertTrue("detail must be non-blank, got: '${(outcome as RefreshOutcome.ChannelError).detail}'", outcome.detail.isNotBlank())
        assertEquals(1, gateway.listUsersCount)
        assertEquals(0, gateway.verboseCount)
        assertEquals(0, gateway.dumpsysCount)
        assertEquals(0, gateway.propCount)
        assertEquals(0, gateway.listPackagesCount)
    }

    // B1: SerialUnavailable in the snapshot returns GroundTruth with a null
    // serial and the true parentId 0. Both triples see the no-serial world
    // (first triple AND post-list re-verify), so the strict null-sensitive
    // fence matches: GroundTruth(95, null, 0) with the listed cloned.
    // Budget is 1 snapshot + 2x triples + 1 list = 8 commands, never FRESH
    // on a fictional serial spliced from another window.
    @Test
    fun serialUnavailableInSnapshot_returnsGroundTruthWithNullSerialAndParent0() {
        val gateway = FakeGateway(
            userListVerboseOutput = FakeGateway.VERBOSE_NO_SERIAL_95,
            dumpsysOutput = FakeGateway.DUMPSYS_NO_SERIAL_95,
            propOutput = "95\n",
        )
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("serial-unavailable in snapshot must be GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        val ground = outcome as RefreshOutcome.GroundTruth
        assertEquals(95, ground.target.userId)
        assertNull("serial must stay null, never a fictional spliced serial, got: ${ground.target}", ground.target.serial)
        assertEquals(0, ground.target.parentId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), ground.cloned)
        assertTrue("profiles must contain the target for display", ground.profiles.any { it.userId == 95 })
        assertEquals(1, gateway.listUsersCount)
        assertEquals(2, gateway.verboseCount)
        assertEquals(2, gateway.dumpsysCount)
        assertEquals(2, gateway.propCount)
        assertEquals(1, gateway.listPackagesCount)
        assertEquals(8, gateway.commands.size)
    }

    // S3(a): Truncated identity evidence refuses the first triple. A
    // truncated `cmd user list -v` capture is untrusted, never
    // absence/presence evidence: RefreshOutcome.Unknown with the pm
    // profiles carried, zero list probes, never FRESH (and never
    // NoDual). Non-Refused Unknown-through-refreshVerified path.
    @Test
    fun truncatedIdentity_returnsUnknownWithProfilesAndZeroProbes() {
        val gateway = FakeGateway(userListVerboseTruncated = true)
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("truncated identity must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue(
            "profiles must still be carried for display, got: ${unknown.profiles}",
            unknown.profiles.any { it.userId == 95 },
        )
        assertTrue(
            "detail must name the truncated channel, got: ${unknown.detail}",
            unknown.detail.contains("truncated"),
        )
        assertEquals(
            "no list probe may run once the identity refuses, got: ${gateway.commands}",
            0,
            gateway.listPackagesCount,
        )
        assertEquals(1, gateway.listUsersCount)
        assertEquals(1, gateway.verboseCount)
        assertEquals(1, gateway.dumpsysCount)
        assertEquals(0, gateway.propCount)
    }

    // S3(b): Throwing identity gateway refuses the first triple. A gateway
    // that throws on an identity channel (spawn failure / binder death)
    // maps to Unknown (fail closed), never a crash: RefreshOutcome.Unknown
    // with the pm profiles carried, zero list probes, never FRESH.
    // Non-Refused Unknown-through-refreshVerified path.
    @Test
    fun throwingIdentityGateway_returnsUnknownWithProfilesAndZeroProbes() {
        val gateway = FakeGateway(verboseThrows = true)
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("throwing identity gateway must be Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        val unknown = outcome as RefreshOutcome.Unknown
        assertTrue(
            "profiles must still be carried for display, got: ${unknown.profiles}",
            unknown.profiles.any { it.userId == 95 },
        )
        assertTrue(
            "detail must name the throw, got: ${unknown.detail}",
            unknown.detail.contains("threw"),
        )
        assertEquals(
            "no list probe may run once the identity throws, got: ${gateway.commands}",
            0,
            gateway.listPackagesCount,
        )
        assertEquals(1, gateway.listUsersCount)
        assertEquals(1, gateway.verboseCount)
        assertEquals(0, gateway.dumpsysCount)
        assertEquals(0, gateway.propCount)
    }
}
