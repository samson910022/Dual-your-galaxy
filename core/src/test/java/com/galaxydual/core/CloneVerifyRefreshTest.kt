// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase C3 regression tests (issue #9): clone verification + refresh rebuild.
// Uses a FakeGateway (no device). The three issue scenarios:
//  1. fake success is never added (install ok, verify absent -> VerifyFailed),
//  2. a fresh client discovers pre-existing clones via list packages,
//  3. an external removal syncs away on the next listing.
// Plus: single-snapshot pinning, remove-needs-confirmed-absence, tri-state
// verify, and channel-error handling. Pure Kotlin, no device required.
class CloneVerifyRefreshTest {
    // In-memory gateway: canned results per command family, full command log
    // so tests can prove install and re-check shared one pinned target.
    private class FakeGateway(
        var listUsersOut: String = DEFAULT_USERS,
        var listUsersErr: String = "",
        var listUsersExit: Int = 0,
        var userListVerboseOut: String = DEFAULT_VERBOSE_95,
        var userListVerboseExit: Int = 0,
        var dumpsysOut: String = DEFAULT_DUMPSYS_95,
        var dumpsysExit: Int = 0,
        var propOut: String = DEFAULT_PROP_95,
        var propExit: Int = 0,
        var installOut: String = "Package com.example.chat installed for user: 95",
        var installErr: String = "",
        var installExit: Int = 0,
        var uninstallOut: String = "Success",
        var uninstallErr: String = "",
        var uninstallExit: Int = 0,
        var pathOut: String = "",
        var pathErr: String = "",
        var pathExit: Int = 0,
        var listPackagesOut: String = "",
        var listPackagesErr: String = "",
        var listPackagesExit: Int = 0,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = listUsersExit, out = listUsersOut, err = listUsersErr)
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = userListVerboseExit, out = userListVerboseOut, err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = dumpsysExit, out = dumpsysOut, err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = propExit, out = propOut, err = "")
                cmd.startsWith("pm install-existing") ->
                    CmdResult(exit = installExit, out = installOut, err = installErr)
                cmd.startsWith("pm uninstall") ->
                    CmdResult(exit = uninstallExit, out = uninstallOut, err = uninstallErr)
                cmd.startsWith("pm path") ->
                    CmdResult(exit = pathExit, out = pathOut, err = pathErr)
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOut, err = listPackagesErr)
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        fun listUsersCalls(): Int = commands.count { it == DualRepository.CMD_LIST_USERS }

        // Identity reads are not writes: only install/uninstall/list-packages
        // count here so pinning assertions keep their meaning.
        fun writeCommands(): List<String> = commands.filter {
            it != DualRepository.CMD_LIST_USERS &&
                it != DualRepository.CMD_USER_LIST_VERBOSE &&
                it != DualRepository.CMD_DUMPSYS_USER &&
                it != DualRepository.CMD_GETPROP_DUAL
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
            // Verified-absent identity: Owner + Work, both non-CLONE (for the
            // NoDual test below). Serials are distinct from userIds.
            const val NODUAL_USERS =
                "Users:\n" +
                    "\tUserInfo{0:Owner:13} running\n" +
                    "\tUserInfo{10:Work profile:30} running\n"
            const val NODUAL_VERBOSE =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{10:Work profile:30} running:\n" +
                    "    Serial: 11\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.MANAGED\n"
            const val NODUAL_DUMPSYS =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{10:Work profile:30} serialNo=11:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.MANAGED\n"
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

    // Issue #9 scenario 1: install reports success but the pinned re-check
    // finds nothing -> VerifyFailed, never Success. The ViewModel rule (only
    // Success joins `cloned`) therefore leaves the list untouched; mirror it
    // here since app has no test harness (repo semantics are the contract).
    @Test
    fun fakeSuccess_isVerifyFailed_neverAdded() {
        val gateway = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            installExit = 0,
            // Clean empty listing: exit 0, parsed set lacks the package.
            listPackagesOut = "",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("fake success must be VerifyFailed, got: $outcome", outcome is CloneOutcome.VerifyFailed)
        assertEquals(95, (outcome as CloneOutcome.VerifyFailed).dualId)

        // ViewModel rule replica: only Success appends. A fresh typed alias
        // widens outcome back to CloneOutcome (the `as VerifyFailed` cast above
        // narrows it, which made the `is Success` check an always-false warning).
        val cloned = mutableListOf<String>()
        val ruleInput: CloneOutcome = outcome
        when (ruleInput) {
            is CloneOutcome.Success -> cloned.add("com.example.chat")
            else -> Unit
        }
        assertTrue("fake success must not join cloned", cloned.isEmpty())
    }

    // Issue #9 scenario 2: a fresh client (empty local state) discovers
    // pre-existing clones from the device ground truth.
    @Test
    fun freshClient_discoversExistingClones() {
        val gateway = FakeGateway(
            listPackagesOut = "package:com.example.chat\npackage:com.example.mail\n",
        )
        val repo = DualRepository(gateway)
        assertEquals(
            listOf("com.example.chat", "com.example.mail"),
            runSuspend { repo.listClonedPackages() },
        )
    }

    // Issue #9 scenario 3: a removal done outside the app syncs away on the
    // next listing (refresh rebuilds instead of trusting local state).
    @Test
    fun externalRemoval_syncsOnNextListing() {
        val gateway = FakeGateway(
            listPackagesOut = "package:com.example.chat\npackage:com.example.mail\n",
        )
        val repo = DualRepository(gateway)
        assertEquals(
            listOf("com.example.chat", "com.example.mail"),
            runSuspend { repo.listClonedPackages() },
        )
        // External uninstall of mail; next refresh must drop it.
        gateway.listPackagesOut = "package:com.example.chat\n"
        assertEquals(
            listOf("com.example.chat"),
            runSuspend { repo.listClonedPackages() },
        )
    }

    // Pinning (issue #9 req 1; Phase D before/fresh/after target pin): one
    // `pm list users` snapshot serves the flow plus verified-target checks
    // around the write, and install and re-check target the same user.
    // legacy name: cloneVerified_pinsSingleSnapshotForInstallAndVerify
    @Test
    fun cloneVerified_pinsSnapshotAndBeforeAfterTargetForInstallAndVerify() {
        val gateway = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            listPackagesOut = "package:com.example.chat\n",
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(95, (outcome as CloneOutcome.Success).dualId)
        assertEquals("single snapshot must serve the whole flow", 1, gateway.listUsersCalls())
        val writes = gateway.writeCommands()
        assertEquals(
            listOf(
                "pm install-existing --user 95 com.example.chat",
                "pm list packages --user 95",
            ),
            writes,
        )
    }

    // Same pinning for remove: uninstall and re-check share one snapshot id
    // plus before/fresh/after verified-target pin.
    // legacy name: removeVerified_pinsSingleSnapshotForUninstallAndRecheck
    @Test
    fun removeVerified_pinsSnapshotAndBeforeAfterTargetForUninstallAndRecheck() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            // Absent after removal: clean empty listing.
            listPackagesOut = "",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(1, gateway.listUsersCalls())
        assertEquals(
            listOf(
                "pm uninstall --user 95 com.example.chat",
                "pm list packages --user 95",
            ),
            gateway.writeCommands(),
        )
    }

    // Remove counts only on confirmed absence (issue #9 req 5): uninstall ok
    // but still present -> StillInstalled, not Success.
    @Test
    fun removeVerified_stillPresent_isNotSuccess() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "package:com.example.chat\n",
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected StillInstalled, got: $outcome", outcome is RemoveOutcome.StillInstalled)
    }

    // Remove with an uncheckable re-check channel is unknown (fail closed),
    // and a failed uninstall stays failed even if probed later via refresh.
    // List-probe channel failure is exit != 0 only: exit-0 output that simply
    // lacks the package (even error-ish text) is confirmed-absent, not
    // unknown (exit-only semantics; isSuccess is not applied to the probe).
    @Test
    fun removeVerified_unknownRecheck_isNotSuccess() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "",
            listPackagesErr = "error: closed",
            listPackagesExit = 1,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
    }

    // (a) remove: uninstall ok + list exit 0 whose output lacks the package
    // (here an absent-package error text, no package: line) is confirmed
    // absent -> Success, not VerifyUnknown. Proves isSuccess is NOT applied
    // to the probe: absence text is absence evidence.
    @Test
    fun removeVerified_absentErrorText_isSuccess() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "Unknown package: com.example.chat",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
    }

    // (b) remove: uninstall ok + list exit 0 empty is confirmed absent
    // -> Success.
    @Test
    fun removeVerified_emptyList_isSuccess() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "",
            listPackagesErr = "",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
    }

    // (c) remove: uninstall ok + list non-zero exit is an uncheckable
    // channel -> VerifyUnknown (detail kept), never Success.
    @Test
    fun removeVerified_nonZeroExit_isVerifyUnknown() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "",
            listPackagesErr = "error: closed",
            listPackagesExit = 1,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals("error: closed", (outcome as RemoveOutcome.VerifyUnknown).detail)
    }

    // (d) clone: install ok + list exit 0 that lacks the package (absent
    // text, no package: line) is confirmed absent -> VerifyFailed, never
    // VerifyUnknown.
    @Test
    fun cloneVerified_absentErrorText_isVerifyFailed() {
        val gateway = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            installExit = 0,
            listPackagesOut = "Unknown package: com.example.chat",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("expected VerifyFailed, got: $outcome", outcome is CloneOutcome.VerifyFailed)
    }

    // (e) clone: install ok + list exit 0 that contains the package is
    // confirmed present -> Success.
    @Test
    fun cloneVerified_packageLine_isSuccess() {
        val gateway = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            installExit = 0,
            listPackagesOut = "package:com.example.chat\n",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
    }

    // (f) exact match: the probe is `pkg in parsePackages(out)`, never a
    // substring search -- a listing that contains only com.example.app2 must
    // NOT verify com.example.app (clone -> VerifyFailed, remove -> Success).
    @Test
    fun recheck_exactMatch_noSubstringHit() {
        val cloneGateway = FakeGateway(
            installOut = "Package com.example.app installed for user: 95",
            installExit = 0,
            listPackagesOut = "package:com.example.app2\n",
            listPackagesExit = 0,
        )
        val cloneOutcome = runSuspend { DualRepository(cloneGateway).cloneVerified("com.example.app") }
        assertTrue("superstring must not verify, got: $cloneOutcome", cloneOutcome is CloneOutcome.VerifyFailed)

        val removeGateway = FakeGateway(
            uninstallOut = "Success",
            uninstallExit = 0,
            listPackagesOut = "package:com.example.app2\n",
            listPackagesExit = 0,
        )
        val removeOutcome = runSuspend { DualRepository(removeGateway).removeVerified("com.example.app") }
        assertTrue("superstring must not block removal, got: $removeOutcome", removeOutcome is RemoveOutcome.Success)

        // And the reverse direction still verifies: exact entry present.
        val presentGateway = FakeGateway(
            listPackagesOut = "package:com.example.app\npackage:com.example.app2\n",
            listPackagesExit = 0,
        )
        assertTrue(
            runSuspend { DualRepository(presentGateway).verifyState("com.example.app", 95) } is VerifyState.Installed,
        )
    }

    @Test
    fun removeVerified_failedUninstall_staysFailed() {
        val gateway = FakeGateway(
            uninstallOut = "Failure [DELETE_FAILED_INTERNAL_ERROR: System error]",
            uninstallExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected RemoveFailed, got: $outcome", outcome is RemoveOutcome.RemoveFailed)
    }

    // Channel failure short-circuits before any write, on both flows.
    @Test
    fun channelError_runsNoWrite() {
        val gateway = FakeGateway(listUsersExit = 1, listUsersErr = "denied: no privilege")
        val repo = DualRepository(gateway)
        val cloneOutcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("expected ChannelError, got: $cloneOutcome", cloneOutcome is CloneOutcome.ChannelError)
        val removeOutcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected ChannelError, got: $removeOutcome", removeOutcome is RemoveOutcome.ChannelError)
        assertTrue(
            "no write may run on channel error, got: ${gateway.writeCommands()}",
            gateway.writeCommands().isEmpty(),
        )
        assertNull(runSuspend { repo.listClonedPackages() })
    }

    // No DUAL profile: NoDual outcomes, no writes, null listing.
    // Stage 2: verified-absent (Owner SYSTEM + Work MANAGED, no CLONE claim).
    @Test
    fun noDual_runsNoWrite() {
        val gateway = FakeGateway(
            listUsersOut = FakeGateway.NODUAL_USERS,
            userListVerboseOut = FakeGateway.NODUAL_VERBOSE,
            dumpsysOut = FakeGateway.NODUAL_DUMPSYS,
            propOut = "",
        )
        val repo = DualRepository(gateway)
        assertTrue(runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual)
        assertTrue(runSuspend { repo.removeVerified("com.example.chat") } is RemoveOutcome.NoDual)
        assertNull(runSuspend { repo.listClonedPackages() })
        assertTrue(gateway.writeCommands().isEmpty())
    }

    // Tri-state verify: installed / confirmed-absent / uncheckable.
    // Exit-only semantics on the list probe: exit 0 + contains -> Installed,
    // exit 0 + lacks -> NotInstalled, non-zero (x3, see VerifyRetryTest) ->
    // Unknown. Guard refusal (owner id) is Unknown, never a probe of user 0.
    @Test
    fun verifyState_distinguishesAbsentFromUnknown() {
        val gateway = FakeGateway(listPackagesOut = "package:com.example.chat\n")
        val repo = DualRepository(gateway)
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Installed)

        gateway.listPackagesOut = ""
        gateway.listPackagesExit = 0
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.NotInstalled)

        gateway.listPackagesExit = 1
        gateway.listPackagesErr = "error"
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Unknown)

        // Guard refusal (owner id) is Unknown, never a probe of user 0.
        gateway.listPackagesExit = 0
        gateway.listPackagesErr = ""
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 0) } is VerifyState.Unknown)
        assertTrue(gateway.commands.none { it.contains("--user 0") })
    }

    // Dual exists but owns no clones: confirmed empty (Empty state), not null.
    @Test
    fun listClonedPackages_confirmedEmpty_isEmptyList() {
        val gateway = FakeGateway(listPackagesOut = "")
        val repo = DualRepository(gateway)
        assertEquals(emptyList<String>(), runSuspend { repo.listClonedPackages() })
    }

    // profilesSnapshot keeps the channel signal listProfiles() collapses.
    @Test
    fun profilesSnapshot_keepsChannelSignal() {
        val bad = FakeGateway(
            listUsersOut = "Users:\n\tUserInfo{0:Owner:13} running\n",
            listUsersErr = "Failure [INSTALL_FAILED_INVALID_APK: bad]",
            listUsersExit = 0,
        )
        val badSnap = runSuspend { DualRepository(bad).profilesSnapshot() }
        assertTrue(badSnap is ProfilesSnapshot.ChannelError)

        val good = FakeGateway()
        val goodSnap = runSuspend { DualRepository(good).profilesSnapshot() }
        assertTrue(goodSnap is ProfilesSnapshot.Loaded)
        assertEquals(2, (goodSnap as ProfilesSnapshot.Loaded).profiles.size)
    }

    // Gateway builder for the ground-truth listing (+ owner guard).
    @Test
    fun buildListPackages_formatsCommand() {
        assertEquals("pm list packages --user 95", PmOutputParser.buildListPackages(95))
        try {
            PmOutputParser.buildListPackages(0)
            throw AssertionError("buildListPackages(0) must throw")
        } catch (expected: IllegalArgumentException) {
            // expected: owner profile is off-limits
        }
    }

    // Clone with an uninstallable token is InvalidPackage, never executed.
    @Test
    fun cloneVerified_invalidPackage_neverExecutes() {
        val gateway = FakeGateway()
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("bad pkg; rm -rf") }
        assertTrue("expected InvalidPackage, got: $outcome", outcome is CloneOutcome.InvalidPackage)
        assertTrue(gateway.writeCommands().isEmpty())
    }
}
