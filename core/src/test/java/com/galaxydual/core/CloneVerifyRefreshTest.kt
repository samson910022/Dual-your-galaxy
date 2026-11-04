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

        fun writeCommands(): List<String> = commands.filter { it != DualRepository.CMD_LIST_USERS }

        companion object {
            const val DEFAULT_USERS =
                "Users:\n" +
                    "\tUserInfo{0:Owner:13} running\n" +
                    "\tUserInfo{95:DUAL_APP:400} running\n"
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
            // Clean empty probe: isSuccess true, no package: line -> absent.
            pathOut = "",
            pathExit = 0,
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

    // Pinning (issue #9 req 1): one `pm list users` snapshot serves both the
    // install and the re-check, and both target the same user.
    @Test
    fun cloneVerified_pinsSingleSnapshotForInstallAndVerify() {
        val gateway = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            pathOut = "package:/data/app/com.example.chat/base.apk",
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
                "pm path --user 95 com.example.chat",
            ),
            writes,
        )
    }

    // Same pinning for remove: uninstall and re-check share the snapshot id.
    @Test
    fun removeVerified_pinsSingleSnapshotForUninstallAndRecheck() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            // Absent after removal: clean empty probe.
            pathOut = "",
            pathExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(1, gateway.listUsersCalls())
        assertEquals(
            listOf(
                "pm uninstall --user 95 com.example.chat",
                "pm path --user 95 com.example.chat",
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
            pathOut = "package:/data/app/com.example.chat/base.apk",
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected StillInstalled, got: $outcome", outcome is RemoveOutcome.StillInstalled)
    }

    // Remove with an uncheckable re-check channel is unknown (fail closed),
    // and a failed uninstall stays failed even if probed later via refresh.
    @Test
    fun removeVerified_unknownRecheck_isNotSuccess() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            pathOut = "Failure [INSTALL_FAILED_INVALID_APK: bad]",
            pathExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
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
    @Test
    fun noDual_runsNoWrite() {
        val gateway = FakeGateway(
            listUsersOut = "Users:\n" +
                "\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{10:Work profile:30} running\n",
        )
        val repo = DualRepository(gateway)
        assertTrue(runSuspend { repo.cloneVerified("com.example.chat") } is CloneOutcome.NoDual)
        assertTrue(runSuspend { repo.removeVerified("com.example.chat") } is RemoveOutcome.NoDual)
        assertNull(runSuspend { repo.listClonedPackages() })
        assertTrue(gateway.writeCommands().isEmpty())
    }

    // Tri-state verify: installed / confirmed-absent / uncheckable.
    @Test
    fun verifyState_distinguishesAbsentFromUnknown() {
        val gateway = FakeGateway(pathOut = "package:/data/app/com.example.chat/base.apk")
        val repo = DualRepository(gateway)
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Installed)

        gateway.pathOut = ""
        gateway.pathExit = 0
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.NotInstalled)

        gateway.pathExit = 1
        gateway.pathErr = "error"
        assertTrue(runSuspend { repo.verifyState("com.example.chat", 95) } is VerifyState.Unknown)

        // Guard refusal (owner id) is Unknown, never a probe of user 0.
        gateway.pathExit = 0
        gateway.pathErr = ""
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
