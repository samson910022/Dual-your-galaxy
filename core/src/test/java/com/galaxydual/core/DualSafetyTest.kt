// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Safety regression tests: verified dual-profile identity boundary
// (PR-1 stage 2, issue #21; supersedes the C1 label-only boundary from #2).
// Uses a FakeGateway (no device) to prove writes/reads are refused rather than
// mis-targeted. Identity now requires cross-source CLONE agreement
// (`cmd user list -v` + `dumpsys user`, plus property when present);
// the `pm list users` label is display-only and never authorizes.
//
// Scenarios: legacy no-DUAL refusal, owner refusal, ambiguous/stale refusal
// (kept, updated to verified fixtures), plus stage-2 additions: synthetic
// Owner/Work/Secondary/SecureFolder entries carrying DUAL substrings but
// non-CLONE types are refused; existing-but-non-Dual explicit ids are refused
// on reads and writes; a renamed/reordered Dual is still recognized without
// fixed ids/flags/order; property missing/mismatch maps to Unknown/Refused;
// insufficient capability refuses without target commands.
class DualSafetyTest {
    // In-memory CmdGateway: serves canned identity outputs and records every
    // command so tests can prove no target command ever ran for a bad id.
    private class FakeGateway(
        var listUsersOutput: String = DEFAULT_USERS_95,
        var listUsersExit: Int = 0,
        var userListVerboseOutput: String = DEFAULT_VERBOSE_95,
        var userListVerboseExit: Int = 0,
        var dumpsysOutput: String = DEFAULT_DUMPSYS_95,
        var dumpsysExit: Int = 0,
        var propOutput: String = DEFAULT_PROP_95,
        var propExit: Int = 0,
        var listPackagesOutput: String = "package:com.example.chat",
        var listPackagesExit: Int = 0,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = listUsersExit, out = listUsersOutput, err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = userListVerboseExit, out = userListVerboseOutput, err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = dumpsysExit, out = dumpsysOutput, err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = propExit, out = propOutput, err = "")
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOutput, err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        // Target-affecting commands only: identity reads are excluded so legacy
        // assertions keep their meaning (no write/probe ran for a bad id).
        fun writeCommands(): List<String> = commands.filter {
            it != DualRepository.CMD_LIST_USERS &&
                it != DualRepository.CMD_USER_LIST_VERBOSE &&
                it != DualRepository.CMD_DUMPSYS_USER &&
                it != DualRepository.CMD_GETPROP_DUAL
        }

        fun targetCommandsFor(userId: Int): List<String> = commands.filter {
            it.contains("--user $userId")
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

    // Scenario 1 (kept, verified fixtures): no CLONE-typed profile exists
    // (Owner SYSTEM + Work MANAGED). The old "first non-zero user" fallback
    // would have picked user 10; now resolution must be -1 and clone/remove
    // must refuse without issuing any target command. The pm-list label is
    // irrelevant: even a DUAL-looking Work label without CLONE type refuses.
    @Test
    fun noDualLabel_neverFallsBackToFirstNonZeroUser() {
        val gateway = FakeGateway(
            listUsersOutput = """
                Users:
                    UserInfo{0:Owner:13} running
                    UserInfo{10:Work profile:30} running
            """.trimIndent(),
            userListVerboseOutput = """
                Users:
                  UserInfo{0:Owner:13} running:
                    Serial number: 0
                    User type: android.os.usertype.full.SYSTEM
                  UserInfo{10:Work profile:30} running:
                    Serial: 11
                    parentId=0
                    User type: android.os.usertype.profile.MANAGED
            """.trimIndent(),
            dumpsysOutput = """
                Users:
                  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                    userType=android.os.usertype.full.SYSTEM
                  UserInfo{10:Work profile:30} serialNo=11:
                    parentId=0
                    userType=android.os.usertype.profile.MANAGED
            """.trimIndent(),
            propOutput = "",
        )
        val repo = DualRepository(gateway)

        assertEquals(-1, runSuspend { repo.resolveDualUserId() })

        val cloneRes = runSuspend { repo.clone("com.example.chat") }
        assertEquals(1, cloneRes.exit)
        assertEquals("no dual profile", cloneRes.err)

        val removeRes = runSuspend { repo.remove("com.example.chat") }
        assertEquals(1, removeRes.exit)
        assertEquals("no dual profile", removeRes.err)

        assertTrue(
            "no target command may run without a Confirmed dual, got: ${gateway.writeCommands()}",
            gateway.writeCommands().isEmpty(),
        )

        // Same guarantee through both elevator probes sharing the verified source.
        val shizuku = ShizukuElevator(gateway)
        assertEquals(-1, runSuspend { shizuku.probe() }.dualUserId)
        val su = SuElevator(suGateway(gateway))
        assertEquals(-1, runSuspend { su.probe() }.dualUserId)
    }

    // Scenario 2 (kept): explicit dualId == 0 (owner) is refused on every entry,
    // and no command line containing the owner id is ever executed.
    @Test
    fun explicitUserZero_isRefusedOnAllEntries() {
        val gateway = FakeGateway()
        val repo = DualRepository(gateway)

        val cloneRes = runSuspend { repo.clone("com.example.chat", 0) }
        assertEquals(1, cloneRes.exit)
        assertTrue(cloneRes.err.startsWith("refused:"))

        val removeRes = runSuspend { repo.remove("com.example.chat", 0) }
        assertEquals(1, removeRes.exit)
        assertTrue(removeRes.err.startsWith("refused:"))

        assertTrue(
            "no command may target user 0, got: ${gateway.commands}",
            gateway.commands.none { it.contains("--user 0") },
        )
        assertTrue(
            "verifyState(0) must be Unknown, got: ${runSuspend { repo.verifyState("com.example.chat", 0) }}",
            runSuspend { repo.verifyState("com.example.chat", 0) } is VerifyState.Unknown,
        )
        assertNull(runSuspend { repo.listClonedPackages(0) })

        // Belt and suspenders: the command builder layer rejects 0 as well.
        try {
            ShellEscaper.escapeUserId(0)
            throw AssertionError("escapeUserId(0) must throw")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // Scenario 3 (kept, verified fixtures): two CLONE candidates (95 + 96)
    // resolve to Refused -> -1, and an explicit id not present in the current
    // snapshot (stale/mistyped) is refused.
    @Test
    fun ambiguousDualLabels_andStaleExplicitId_areRefused() {
        val gateway = FakeGateway(
            listUsersOutput = """
                Users:
                    UserInfo{0:Owner:13} running
                    UserInfo{95:DUAL_APP:400} running
                    UserInfo{96:dual_secondary:400} running
            """.trimIndent(),
            userListVerboseOutput = """
                Users:
                  UserInfo{0:Owner:13} running:
                    Serial number: 0
                    User type: android.os.usertype.full.SYSTEM
                  UserInfo{95:DualApp:400} running:
                    Serial: 15
                    parentId=0
                    User type: android.os.usertype.profile.CLONE
                  UserInfo{96:Other:400} running:
                    Serial: 16
                    parentId=0
                    User type: android.os.usertype.profile.CLONE
            """.trimIndent(),
            dumpsysOutput = """
                Users:
                  UserInfo{0:Owner:13} serialNo=0:
                    userType=android.os.usertype.full.SYSTEM
                  UserInfo{95:DualApp:400} serialNo=15:
                    parentId=0
                    userType=android.os.usertype.profile.CLONE
                  UserInfo{96:Other:400} serialNo=16:
                    parentId=0
                    userType=android.os.usertype.profile.CLONE
            """.trimIndent(),
            propOutput = "",
        )
        val repo = DualRepository(gateway)

        // Two CLONE candidates -> ambiguous Refused -> -1, no auto-clone.
        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        val autoRes = runSuspend { repo.clone("com.example.chat") }
        assertEquals(1, autoRes.exit)
        assertEquals("no dual profile", autoRes.err)
        assertTrue(runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual)
        assertTrue(runSuspend { repo.removeVerified("com.example.chat") } is RemoveOutcome.NoDual)
        assertNull(runSuspend { repo.listClonedPackages() })

        // Explicit id 150 is not in this snapshot -> refused, never executed.
        val staleRes = runSuspend { repo.clone("com.example.chat", 150) }
        assertEquals(1, staleRes.exit)
        assertTrue(staleRes.err.startsWith("refused:"))

        val staleRemove = runSuspend { repo.remove("com.example.chat", 150) }
        assertEquals(1, staleRemove.exit)
        assertTrue(staleRemove.err.startsWith("refused:"))

        assertTrue(
            "stale id 150 must never be executed, got: ${gateway.writeCommands()}",
            gateway.writeCommands().none { it.contains("--user 150") },
        )

        // Sanity: the unambiguous single-CLONE case still resolves and clones.
        gateway.listUsersOutput = FakeGateway.DEFAULT_USERS_95
        gateway.userListVerboseOutput = FakeGateway.DEFAULT_VERBOSE_95
        gateway.dumpsysOutput = FakeGateway.DEFAULT_DUMPSYS_95
        gateway.propOutput = FakeGateway.DEFAULT_PROP_95
        gateway.commands.clear()
        assertEquals(95, runSuspend { repo.resolveDualUserId() })
        val okRes = runSuspend { repo.clone("com.example.chat", 95) }
        assertEquals(0, okRes.exit)
        assertEquals(
            listOf("pm install-existing --user 95 com.example.chat"),
            gateway.writeCommands(),
        )
        // Both elevator probes agree on the same verified id.
        assertEquals(95, runSuspend { ShizukuElevator(gateway).probe() }.dualUserId)
        assertEquals(95, runSuspend { SuElevator(suGateway(gateway)).probe() }.dualUserId)
    }

    // Scenario 4 (kept, verified): explicit verify/list share the verified-dual
    // guard with clone/remove. Explicit 0 and stale ids yield Unknown/null
    // gracefully (no throw, never probe the bad id); the happy path still runs
    // `pm list packages --user <dualId>` and checks exact membership.
    // Note: the legacy Boolean verify(pkg, dualId) is deprecated in favor of
    // verifyState; it is exercised here only to lock its fail-closed mapping.
    @Test
    @Suppress("DEPRECATION")
    fun explicitVerify_isGuardedLikeCloneRemove() {
        val gateway = FakeGateway()
        val repo = DualRepository(gateway)

        // dualId 0: graceful Unknown/false/null, never probes user 0.
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 0) } is VerifyState.Unknown)
        assertFalse(runSuspend { repo.verify("com.example.chat", 0) })
        assertNull(runSuspend { repo.listClonedPackages(0) })

        // Stale id 150 (not in snapshot): graceful Unknown/false/null, never probed.
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 150) } is VerifyState.Unknown)
        assertFalse(runSuspend { repo.verify("com.example.chat", 150) })
        assertNull(runSuspend { repo.listClonedPackages(150) })

        assertTrue(
            "verify/list must never probe user 0 or a stale id, got: ${gateway.commands}",
            gateway.commands.none { it.contains("--user 0") || it.contains("--user 150") },
        )

        // Happy path still works: `pm list packages` containing the package
        // verifies Installed/true.
        gateway.commands.clear()
        gateway.listPackagesOutput = "package:com.example.chat"
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Installed)
        gateway.commands.clear()
        assertTrue(runSuspend { repo.verify("com.example.chat", 95) })
        assertEquals(
            listOf("pm list packages --user 95"),
            gateway.writeCommands().filter { it.startsWith("pm list packages") },
        )
    }

    // parseUsers retains the flag/type field via the new parseUsersDetailed().
    @Test
    fun parseUsers_retainsFlagField() {
        val output = """
            Users:
                UserInfo{0:Owner:13} running
                UserInfo{95:DUAL_APP:400} running
        """.trimIndent()
        val detailed = PmOutputParser.parseUsersDetailed(output)
        assertEquals("13", detailed[0].flags)
        assertEquals("400", detailed[1].flags)
        // parseUsers delegates, so existing callers see the same data.
        assertEquals(detailed, PmOutputParser.parseUsers(output))
    }

    // Stage-2 G1: synthetic Owner/Work/Secondary/SecureFolder entries whose
    // labels all contain DUAL substrings -- but whose types are non-CLONE --
    // must all refuse. Fake gateway proves zero subsequent target commands
    // on every surface (clone/remove/verify/list/pinned/elevators).
    @Test
    fun syntheticNonCloneDualLabels_areRefusedOnAllSurfaces() {
        val listUsers = """
            Users:
                UserInfo{0:Owner:13} running
                UserInfo{10:DUAL_Work:30} running
                UserInfo{12:dual_secondary:1030} running
                UserInfo{97:MY_DUAL_SPACE:400} running
                UserInfo{150:DUAL_Secure:800} running
        """.trimIndent()
        val verbose = """
            Users:
              UserInfo{0:Owner:13} running:
                Serial number: 0
                User type: android.os.usertype.full.SYSTEM
              UserInfo{10:DUAL_Work:30} running:
                Serial: 11
                parentId=0
                User type: android.os.usertype.profile.MANAGED
              UserInfo{12:dual_secondary:1030} running:
                Serial: 13
                parentId=0
                User type: android.os.usertype.full.SECONDARY
              UserInfo{97:MY_DUAL_SPACE:400} running:
                Serial: 20
                parentId=0
                User type: android.os.usertype.full.SECONDARY
              UserInfo{150:DUAL_Secure:800} running:
                Serial: 21
                parentId=0
                User type: android.os.usertype.profile.MANAGED
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{10:DUAL_Work:30} serialNo=11:
                parentId=0
                userType=android.os.usertype.profile.MANAGED
              UserInfo{12:dual_secondary:1030} serialNo=13:
                parentId=0
                userType=android.os.usertype.full.SECONDARY
              UserInfo{97:MY_DUAL_SPACE:400} serialNo=20:
                parentId=0
                userType=android.os.usertype.full.SECONDARY
              UserInfo{150:DUAL_Secure:800} serialNo=21:
                parentId=0
                userType=android.os.usertype.profile.MANAGED
        """.trimIndent()
        val gateway = FakeGateway(
            listUsersOutput = listUsers,
            userListVerboseOutput = verbose,
            dumpsysOutput = dumpsys,
            propOutput = "",
        )
        val repo = DualRepository(gateway)

        // Auto resolution: no CLONE claim anywhere -> Unknown -> -1.
        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        assertEquals(-1, runSuspend { ShizukuElevator(gateway).probe() }.dualUserId)
        assertEquals(-1, runSuspend { SuElevator(suGateway(gateway)).probe() }.dualUserId)

        // Auto writes refuse without target commands.
        assertEquals(1, runSuspend { repo.clone("com.example.chat") }.exit)
        assertEquals(1, runSuspend { repo.remove("com.example.chat") }.exit)
        assertTrue(runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual)
        assertTrue(runSuspend { repo.removeVerified("com.example.chat") } is RemoveOutcome.NoDual)
        assertNull(runSuspend { repo.listClonedPackages() })

        // Every synthetic id is present but non-Dual: explicit reads and writes
        // refuse identically (fail closed), never probing the id.
        for (badId in listOf(10, 12, 97, 150)) {
            val c = runSuspend { repo.clone("com.example.chat", badId) }
            assertEquals("id $badId must be refused", 1, c.exit)
            assertTrue(c.err.startsWith("refused:"))
            val r = runSuspend { repo.remove("com.example.chat", badId) }
            assertEquals(1, r.exit)
            assertTrue(r.err.startsWith("refused:"))
            assertTrue(
                "id $badId verifyState must be Unknown",
                runSuspend { repo.verifyState("com.example.chat", badId) } is VerifyState.Unknown,
            )
            assertNull(runSuspend { repo.listClonedPackages(badId) })
        }
        assertTrue(
            "no target command may run for DUAL-labeled non-CLONE ids, got: ${gateway.commands}",
            gateway.targetCommandsFor(10).isEmpty() &&
                gateway.targetCommandsFor(12).isEmpty() &&
                gateway.targetCommandsFor(97).isEmpty() &&
                gateway.targetCommandsFor(150).isEmpty(),
        )
    }

    // Stage-2 G1: an existing but non-Dual id (Work 10 / Secure Folder 150)
    // alongside a real Dual 95 is refused on every entry even though it is
    // present in the snapshot. Existence alone never authorizes.
    @Test
    fun explicitNonDualId_isRefusedOnReadsAndWrites() {
        val gateway = FakeGateway(
            listUsersOutput = """
                Users:
                    UserInfo{0:Owner:13} running
                    UserInfo{10:Work profile:30} running
                    UserInfo{95:DUAL_APP:400} running
                    UserInfo{150:Secure Folder:800} running
            """.trimIndent(),
            userListVerboseOutput = """
                Users:
                  UserInfo{0:Owner:13} running:
                    Serial number: 0
                    User type: android.os.usertype.full.SYSTEM
                  UserInfo{10:Work profile:30} running:
                    Serial: 11
                    parentId=0
                    User type: android.os.usertype.profile.MANAGED
                  UserInfo{95:DualApp:400} running:
                    Serial: 15
                    parentId=0
                    User type: android.os.usertype.profile.CLONE
                  UserInfo{150:Secure Folder:800} running:
                    Serial: 21
                    parentId=0
                    User type: android.os.usertype.profile.MANAGED
            """.trimIndent(),
            dumpsysOutput = """
                Users:
                  UserInfo{0:Owner:13} serialNo=0:
                    userType=android.os.usertype.full.SYSTEM
                  UserInfo{10:Work profile:30} serialNo=11:
                    parentId=0
                    userType=android.os.usertype.profile.MANAGED
                  UserInfo{95:DualApp:400} serialNo=15:
                    parentId=0
                    userType=android.os.usertype.profile.CLONE
                  UserInfo{150:Secure Folder:800} serialNo=21:
                    parentId=0
                    userType=android.os.usertype.profile.MANAGED
            """.trimIndent(),
            propOutput = "95\n",
        )
        val repo = DualRepository(gateway)

        // The real Dual still resolves.
        assertEquals(95, runSuspend { repo.resolveDualUserId() })

        for (badId in listOf(10, 150)) {
            val c = runSuspend { repo.clone("com.example.chat", badId) }
            assertEquals(1, c.exit)
            assertTrue("id $badId: ${c.err}", c.err.startsWith("refused:"))
            val r = runSuspend { repo.remove("com.example.chat", badId) }
            assertEquals(1, r.exit)
            assertTrue(r.err.startsWith("refused:"))
            assertTrue(
                runSuspend { repo.verifyState("com.example.chat", badId) } is VerifyState.Unknown,
            )
            assertNull(runSuspend { repo.listClonedPackages(badId) })
        }
        assertTrue(
            "non-Dual ids must never be targeted, got: ${gateway.commands}",
            gateway.targetCommandsFor(10).isEmpty() && gateway.targetCommandsFor(150).isEmpty(),
        )
        // The verified id still works.
        gateway.commands.clear()
        assertEquals(0, runSuspend { repo.clone("com.example.chat", 95) }.exit)
        assertEquals(
            listOf("pm install-existing --user 95 com.example.chat"),
            gateway.writeCommands(),
        )
    }

    // Stage-2 G2: a legitimate Dual keeps working after a rename (label no
    // longer contains DUAL), a reorder, a different id/flags mix, and a serial
    // distinct from the userId. Neither a fixed user95/user150 nor a fixed
    // flags/label/order may be assumed.
    @Test
    fun renamedReorderedDual_stillRecognized() {
        val gateway = FakeGateway(
            // Dual listed first with a non-DUAL label and different flags.
            listUsersOutput = """
                Users:
                    UserInfo{98:PersonalSpace:504} running
                    UserInfo{0:Owner:13} running
            """.trimIndent(),
            userListVerboseOutput = """
                Users:
                  UserInfo{98:PersonalSpace:504} running:
                    Flags: 0x00000200
                    Serial: 77
                    parentId=0
                    User type: android.os.usertype.profile.CLONE
                  UserInfo{0:Owner:13} running:
                    Serial number: 0
                    User type: android.os.usertype.full.SYSTEM
            """.trimIndent(),
            // Dumpsys in the opposite order: order must not matter.
            dumpsysOutput = """
                Users:
                  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                    userType=android.os.usertype.full.SYSTEM
                  UserInfo{98:PersonalSpace:504} serialNo=77:
                    parentId=0
                    userType=android.os.usertype.profile.CLONE
            """.trimIndent(),
            propOutput = "98\n",
        )
        val repo = DualRepository(gateway)

        // serial (77) != userId (98) is normal and must not confuse resolution.
        assertEquals(98, runSuspend { repo.resolveDualUserId() })
        assertEquals(98, runSuspend { ShizukuElevator(gateway).probe() }.dualUserId)
        assertEquals(98, runSuspend { SuElevator(suGateway(gateway)).probe() }.dualUserId)

        gateway.commands.clear()
        assertEquals(0, runSuspend { repo.clone("com.example.chat", 98) }.exit)
        gateway.commands.clear()
        gateway.listPackagesOutput = "package:com.example.chat"
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 98) } is VerifyState.Installed)
        assertEquals(listOf("com.example.chat"), runSuspend { repo.listClonedPackages() })
    }

    // Stage-2 G2: property contradictions refuse; a missing/garbage property
    // is "no claim" (two channels may still confirm, a single channel never
    // does). All Refused/Unknown map fail-closed (NoDual/Unknown/null, never
    // a write or a probe of the wrong user).
    @Test
    fun propertyMismatch_isRefused_propertyMissingNeedsTwoChannels() {
        // Mismatch: both channels agree on 95, property says 96 -> Refused.
        val mismatch = FakeGateway(propOutput = "96\n")
        val mismatchRepo = DualRepository(mismatch)
        assertEquals(-1, runSuspend { mismatchRepo.resolveDualUserId() })
        assertTrue(
            runSuspend { mismatchRepo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual,
        )
        assertTrue(
            runSuspend { mismatchRepo.verifyState("com.example.chat", 95) } is VerifyState.Unknown,
        )
        assertNull(runSuspend { mismatchRepo.listClonedPackages() })
        assertEquals(-1, runSuspend { ShizukuElevator(mismatch).probe() }.dualUserId)
        assertTrue(
            "mismatch must not target user 95 or 96, got: ${mismatch.commands}",
            mismatch.targetCommandsFor(95).isEmpty() && mismatch.targetCommandsFor(96).isEmpty(),
        )

        // Contradictory property pointing at a nonexistent user -> Refused.
        val ghost = FakeGateway(propOutput = "150\n")
        assertEquals(-1, runSuspend { DualRepository(ghost).resolveDualUserId() })

        // Missing property: two agreeing CLONE channels still confirm (serial
        // 15 != 95 proves serial is not mistaken for the id).
        val missing = FakeGateway(propOutput = "")
        assertEquals(95, runSuspend { DualRepository(missing).resolveDualUserId() })

        // Garbage property format is treated as missing (no claim), same rule.
        val garbage = FakeGateway(propOutput = "unknown\n")
        assertEquals(95, runSuspend { DualRepository(garbage).resolveDualUserId() })

        // Property alone (single CLONE channel + prop) never authorizes:
        // drop the 95 block from dumpsys entirely -> only one source knows 95
        // -> Unknown even with a matching prop.
        val singleChannel = FakeGateway(
            dumpsysOutput = """
                Users:
                  UserInfo{0:Owner:13} serialNo=0:
                    userType=android.os.usertype.full.SYSTEM
            """.trimIndent(),
            propOutput = "95\n",
        )
        assertEquals(-1, runSuspend { DualRepository(singleChannel).resolveDualUserId() })

        // Direct parser mapping: Confirmed -> id, Refused/Unknown -> -1.
        val verbose95 = FakeGateway.DEFAULT_VERBOSE_95
        val dumpsys95 = FakeGateway.DEFAULT_DUMPSYS_95
        assertEquals(95, PmOutputParser.resolveDualUserIdVerified(verbose95, dumpsys95, 95))
        assertEquals(95, PmOutputParser.resolveDualUserIdVerified(verbose95, dumpsys95, null))
        assertEquals(-1, PmOutputParser.resolveDualUserIdVerified(verbose95, dumpsys95, 96))
        assertEquals(null, PmOutputParser.parseDualPropertyId(""))
        assertEquals(null, PmOutputParser.parseDualPropertyId("unknown\n"))
        assertEquals(95, PmOutputParser.parseDualPropertyId("95\n"))
    }

    // Stage-2: insufficient shell capability refuses without touching a target.
    // A failed verbose/dumpsys channel is Unknown (fail closed), never a label
    // guess; the property alone cannot rescue it.
    @Test
    fun insufficientCapability_refusesWithoutTargetCommands() {
        val gateway = FakeGateway(
            userListVerboseExit = 1,
            userListVerboseOutput = "",
            propOutput = "95\n",
        )
        val repo = DualRepository(gateway)
        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        assertEquals(1, runSuspend { repo.clone("com.example.chat") }.exit)
        assertTrue(runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual)
        assertNull(runSuspend { repo.listClonedPackages() })
        assertEquals(-1, runSuspend { ShizukuElevator(gateway).probe() }.dualUserId)
        assertEquals(-1, runSuspend { SuElevator(suGateway(gateway)).probe() }.dualUserId)
        assertTrue(
            "no target command may run when capability is insufficient, got: ${gateway.commands}",
            gateway.commands.none { it.contains("--user 95") },
        )
    }
}
