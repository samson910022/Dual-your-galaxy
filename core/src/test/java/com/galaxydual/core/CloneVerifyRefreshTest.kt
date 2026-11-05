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
        var listPackagesTruncated: Boolean = false,
        // Throw switches (PR-2 Phase 3, R5): when set, run() throws a
        // RuntimeException for that command family instead of returning a
        // result. CancellationException is deliberately NOT covered here:
        // testing the rethrow needs runTest cancellation semantics, the
        // hand-rolled runSuspend above cannot model it.
        var throwOnListUsers: Boolean = false,
        var throwOnListPackages: Boolean = false,
        var throwMessage: String = "binder died",
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS -> {
                    if (throwOnListUsers) throw RuntimeException(throwMessage)
                    CmdResult(exit = listUsersExit, out = listUsersOut, err = listUsersErr)
                }
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
                cmd.startsWith("pm list packages") -> {
                    if (throwOnListPackages) throw RuntimeException(throwMessage)
                    CmdResult(
                        exit = listPackagesExit,
                        out = listPackagesOut,
                        err = listPackagesErr,
                        truncated = listPackagesTruncated,
                    )
                }
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
    // Untrusted means: truncated capture, non-zero exit (after the retained
    // retries), or error-semantic text with exit 0 (PR-2 stage 2, issue #22).
    // Only a format-clean listing that lacks the package is confirmed-absent.
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

    // (a) remove: uninstall ok + list exit 0 whose output carries an
    // absent-package error text ("Unknown package: ...", no package: line) is
    // UNTRUSTED -> VerifyUnknown, never Success. PR-2 stage 2 (issue #22):
    // only a format-clean listing is absence evidence; error-semantic text
    // with exit 0 no longer counts as confirmed-absent (stage-1 flip of the
    // old exit-only rule). The clean-empty twin below still proves the
    // preserved confirmed-absent path.
    @Test
    fun removeVerified_absentErrorText_isVerifyUnknown() {
        val gateway = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "Unknown package: com.example.chat",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertTrue(
            "detail must name the error-semantic line, got: \"${(outcome as RemoveOutcome.VerifyUnknown).detail}\"",
            outcome.detail.contains("Unknown package"),
        )
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
    // channel -> VerifyUnknown (detail keeps the exit code for diagnosis),
    // never Success.
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
        assertTrue(
            "detail must carry exit code, got: \"${(outcome as RemoveOutcome.VerifyUnknown).detail}\"",
            outcome.detail.contains("exit=1"),
        )
    }

    // (d) clone: install ok + list exit 0 that carries an absent-package error
    // text (no package: line) is UNTRUSTED -> VerifyUnknown, never
    // VerifyFailed. PR-2 stage 2 (issue #22): an error-semantic listing must
    // not masquerade as "confirmed not installed".
    @Test
    fun cloneVerified_absentErrorText_isVerifyUnknown() {
        val gateway = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            installExit = 0,
            listPackagesOut = "Unknown package: com.example.chat",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertTrue(
            "detail must name the error-semantic line, got: \"${(outcome as CloneOutcome.VerifyUnknown).detail}\"",
            outcome.detail.contains("Unknown package"),
        )
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
    // Trusted tri-state on the list probe (PR-2 stage 2, issue #22): clean
    // listing + contains -> Installed, clean listing + lacks -> NotInstalled,
    // untrusted capture (truncated / non-zero (x3, see VerifyRetryTest) /
    // error-semantic exit-0 text) -> Unknown. Guard refusal (owner id) is
    // Unknown, never a probe of user 0.
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

    // PR-2 stage 2 (issue #22): filtered-style clean listing WITHOUT the
    // target (TrustedFull of other packages) is confirmed-absent
    // (NotInstalled), distinct from Unknown. The pm filter is substring-based
    // (see UX_REVIEW_S24 §5), so only an exact membership hit counts.
    @Test
    fun verifyState_trustedFullWithoutTarget_isNotInstalled() {
        val gateway = FakeGateway(
            listPackagesOut = "package:com.example.other\npackage:com.example.app2\n",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val state = runSuspend { repo.verifyState("com.example.app", 95) }
        assertTrue("clean listing without target must be NotInstalled, got: $state", state is VerifyState.NotInstalled)
    }

    // PR-2 stage 2 (issue #22): full-listing clean empty (TrustedEmpty) is
    // confirmed-absent for verify (NotInstalled). Twin of the refresh twin
    // below, which maps the same TrustedEmpty to authoritative emptyList.
    @Test
    fun verifyState_trustedEmptyFullListing_isNotInstalled() {
        val gateway = FakeGateway(
            listPackagesOut = "",
            listPackagesErr = "",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        val state = runSuspend { repo.verifyState("com.example.chat", 95) }
        assertTrue("clean empty full listing must be NotInstalled, got: $state", state is VerifyState.NotInstalled)
    }

    // PR-2 stage 2 (issue #22): exit-0 dirty captures are Unknown across the
    // whole error-semantic family -- SecurityException / Failure[] / Error: /
    // stack lines / Caused by: -- even though the exit code is 0. Each keeps
    // a non-empty diagnostic detail. No retry sleep fires here (exit == 0).
    @Test
    fun verifyState_exitZeroDirtyOutputs_areUnknown() {
        val dirtyCases = listOf(
            "securityException" to FakeGateway(
                listPackagesOut = "",
                listPackagesErr = "java.lang.SecurityException: Neither user 2000 nor current process has permission",
                listPackagesExit = 0,
            ),
            "failureLine" to FakeGateway(
                listPackagesOut = "Failure [INSTALL_FAILED_INVALID_APK: bad]",
                listPackagesExit = 0,
            ),
            "errorLine" to FakeGateway(
                listPackagesOut = "",
                listPackagesErr = "Error: could not list packages",
                listPackagesExit = 0,
            ),
            "stackLine" to FakeGateway(
                listPackagesOut = "package:com.example.chat\n\tat com.android.server.pm.PackageManagerService.getPackages(Unknown)",
                listPackagesExit = 0,
            ),
            "causedBy" to FakeGateway(
                listPackagesOut = "Caused by: java.lang.IllegalStateException: binder died",
                listPackagesExit = 0,
            ),
        )
        for ((name, gateway) in dirtyCases) {
            val repo = DualRepository(gateway)
            val state = runSuspend { repo.verifyState("com.example.chat", 95) }
            assertTrue("[$name] dirty exit-0 listing must be Unknown, got: $state", state is VerifyState.Unknown)
            assertTrue(
                "[$name] Unknown detail must be non-empty",
                (state as VerifyState.Unknown).detail.isNotEmpty(),
            )
        }
    }

    // PR-2 stage 2 (issue #22): a truncated capture proves nothing in either
    // direction -- truncated + package line present is NOT Installed, and
    // truncated + empty is NOT NotInstalled. Both are Unknown with a
    // truncation diagnostic. Covers the explicit flag and the legacy
    // TRUNCATION_MARKER text.
    @Test
    fun verifyState_truncatedListing_isUnknown() {
        val flaggedPresent = FakeGateway(
            listPackagesOut = "package:com.example.chat\n",
            listPackagesExit = 0,
            listPackagesTruncated = true,
        )
        val presentState = runSuspend { DualRepository(flaggedPresent).verifyState("com.example.chat", 95) }
        assertTrue("truncated listing with package must be Unknown, got: $presentState", presentState is VerifyState.Unknown)
        assertTrue(
            "detail must name truncation, got: \"${(presentState as VerifyState.Unknown).detail}\"",
            presentState.detail.contains("truncat", ignoreCase = true),
        )

        val flaggedEmpty = FakeGateway(
            listPackagesOut = "",
            listPackagesExit = 0,
            listPackagesTruncated = true,
        )
        val emptyState = runSuspend { DualRepository(flaggedEmpty).verifyState("com.example.chat", 95) }
        assertTrue("truncated empty listing must be Unknown, got: $emptyState", emptyState is VerifyState.Unknown)

        val markerOnly = FakeGateway(
            listPackagesOut = "package:com.example.chat\n$TRUNCATION_MARKER",
            listPackagesExit = 0,
        )
        val markerState = runSuspend { DualRepository(markerOnly).verifyState("com.example.chat", 95) }
        assertTrue("marker-text listing must be Unknown, got: $markerState", markerState is VerifyState.Unknown)
    }

    // PR-2 stage 2 (issue #22) refresh contract: TrustedFull rebuilds,
    // TrustedEmpty is authoritative empty (confirmed-empty, not unknown), and
    // every untrusted capture -- Failure / SecurityException / truncated /
    // non-zero -- yields null (unknown), never an authoritative empty list.
    // The ViewModel keeps the previous `cloned` on null (fail closed).
    @Test
    fun refresh_untrustedListing_isNull_neverAuthoritativeEmpty() {
        val gateway = FakeGateway(
            listPackagesOut = "package:com.example.chat\npackage:com.example.mail\n",
            listPackagesExit = 0,
        )
        val repo = DualRepository(gateway)
        assertEquals(
            listOf("com.example.chat", "com.example.mail"),
            runSuspend { repo.listClonedPackages() },
        )

        gateway.listPackagesOut = ""
        gateway.listPackagesErr = ""
        gateway.listPackagesExit = 0
        gateway.listPackagesTruncated = false
        assertEquals(
            "clean empty full listing is confirmed-empty",
            emptyList<String>(),
            runSuspend { repo.listClonedPackages() },
        )

        gateway.listPackagesOut = "Failure [INSTALL_FAILED_INVALID_APK: bad]"
        gateway.listPackagesErr = ""
        assertNull(
            "exit-0 Failure text must be unknown (null), not empty",
            runSuspend { repo.listClonedPackages() },
        )

        gateway.listPackagesOut = ""
        gateway.listPackagesErr = "java.lang.SecurityException: Neither user 2000 nor current process has permission"
        assertNull(
            "exit-0 SecurityException must be unknown (null), not empty",
            runSuspend { repo.listClonedPackages() },
        )

        gateway.listPackagesOut = "package:com.example.chat\n"
        gateway.listPackagesErr = ""
        gateway.listPackagesExit = 0
        gateway.listPackagesTruncated = true
        assertNull(
            "truncated listing must be unknown (null), even with a package line",
            runSuspend { repo.listClonedPackages() },
        )

        gateway.listPackagesTruncated = false
        gateway.listPackagesOut = ""
        gateway.listPackagesErr = "error: closed"
        gateway.listPackagesExit = 1
        assertNull(
            "non-zero listing must be unknown (null), not empty",
            runSuspend { repo.listClonedPackages() },
        )
    }

    // PR-2 stage 2 (issue #22) remove flow: uninstall ok + exit-0 dirty or
    // truncated re-check is VerifyUnknown (fail closed: the caller must NOT
    // drop the entry from the UI), never Success.
    @Test
    fun removeVerified_exitZeroDirtyRecheck_isVerifyUnknown() {
        val dirty = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "",
            listPackagesErr = "java.lang.SecurityException: Neither user 2000 nor current process has permission",
            listPackagesExit = 0,
        )
        val dirtyOutcome = runSuspend { DualRepository(dirty).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $dirtyOutcome", dirtyOutcome is RemoveOutcome.VerifyUnknown)

        val truncated = FakeGateway(
            uninstallOut = "Success",
            listPackagesOut = "",
            listPackagesExit = 0,
            listPackagesTruncated = true,
        )
        val truncatedOutcome = runSuspend { DualRepository(truncated).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $truncatedOutcome", truncatedOutcome is RemoveOutcome.VerifyUnknown)
    }

    // PR-2 stage 2 (issue #22) clone flow: install ok + exit-0 dirty re-check
    // is VerifyUnknown (must not masquerade as VerifyFailed/not-installed),
    // and install ok + truncated re-check that even contains the package is
    // VerifyUnknown (must not masquerade as Success).
    @Test
    fun cloneVerified_exitZeroDirtyRecheck_isVerifyUnknown() {
        val dirty = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            installExit = 0,
            listPackagesOut = "Failure [DELETE_FAILED_INTERNAL_ERROR: System error]",
            listPackagesExit = 0,
        )
        val dirtyOutcome = runSuspend { DualRepository(dirty).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $dirtyOutcome", dirtyOutcome is CloneOutcome.VerifyUnknown)

        val truncatedPresent = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            installExit = 0,
            listPackagesOut = "package:com.example.chat\n",
            listPackagesExit = 0,
            listPackagesTruncated = true,
        )
        val truncatedOutcome = runSuspend { DualRepository(truncatedPresent).cloneVerified("com.example.chat") }
        assertTrue(
            "truncated re-check with package must be VerifyUnknown, got: $truncatedOutcome",
            truncatedOutcome is CloneOutcome.VerifyUnknown,
        )
    }

    // PR-2 Phase 3 (T1): a channel error on the profiles snapshot maps verify
    // to Unknown (fail closed), and no list probe may run.
    @Test
    fun verifyState_channelError_isUnknown() {
        val gateway = FakeGateway(listUsersExit = 1, listUsersErr = "denied: no privilege")
        val repo = DualRepository(gateway)
        val state = runSuspend { repo.verifyState("com.example.chat", 95) }
        assertTrue("channel error must be Unknown, got: $state", state is VerifyState.Unknown)
        assertTrue(
            "no probe may run on channel error, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm list packages") },
        )
    }

    // PR-2 Phase 3 (T3): an invalid package token maps verify to Unknown
    // (fail closed) without ever emitting a probe command.
    @Test
    fun verifyState_invalidPackage_isUnknown() {
        val gateway = FakeGateway()
        val repo = DualRepository(gateway)
        val state = runSuspend { repo.verifyState("bad pkg; rm -rf", 95) }
        assertTrue("invalid package must be Unknown, got: $state", state is VerifyState.Unknown)
        assertTrue(
            "no probe may run for an invalid package, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm list packages") },
        )
    }

    // PR-2 Phase 3 (T4): explicit-id listing with a refused id (owner 0, or
    // an id absent from the snapshot) yields null (unknown) without probing.
    @Test
    fun listClonedPackages_invalidId_isNull() {
        val gateway = FakeGateway()
        val repo = DualRepository(gateway)
        assertNull(runSuspend { repo.listClonedPackages(0) })
        assertNull(runSuspend { repo.listClonedPackages(999) })
        assertTrue(
            "no probe may run for refused ids, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm list packages") },
        )
    }

    // PR-2 Phase 3 (T-extra): the refresh listing path never retries -- a
    // non-zero probe yields null (unknown) after exactly one gateway call,
    // so the ViewModel keeps the previous `cloned` on one stale frame.
    @Test
    fun refresh_nonZeroExit_probesOnce_isNull() {
        val gateway = FakeGateway(
            listPackagesOut = "",
            listPackagesErr = "error: closed",
            listPackagesExit = 1,
        )
        val repo = DualRepository(gateway)
        assertNull(runSuspend { repo.listClonedPackages() })
        assertEquals(1, gateway.commands.count { it.startsWith("pm list packages") })
    }

    // PR-2 Phase 3 (R5): a gateway that throws mid-probe (spawn failure,
    // SecurityException, binder death surfaced as RuntimeException) maps to
    // Unknown("channel error: ..."), fail closed, never a crash. Identity
    // channels stay healthy here so the throw is proven to come from the
    // list probe itself (no sleep fires: throws bypass the retry loop).
    @Test
    fun verifyState_throwingListProbe_isUnknownChannelError() {
        val gateway = FakeGateway(
            listPackagesOut = "package:com.example.chat\n",
            throwOnListPackages = true,
            throwMessage = "binder died",
        )
        val repo = DualRepository(gateway)
        val state = runSuspend { repo.verifyState("com.example.chat", 95) }
        assertTrue("throwing probe must be Unknown, got: $state", state is VerifyState.Unknown)
        assertTrue(
            "detail must carry the channel error, got: \"${(state as VerifyState.Unknown).detail}\"",
            state.detail.contains("channel error") && state.detail.contains("binder died"),
        )
    }

    // PR-2 Phase 3 (R5): a throwing refresh listing maps to null (unknown),
    // never a crash and never an authoritative empty list.
    @Test
    fun listClonedPackages_throwingProbe_isNull() {
        val gateway = FakeGateway(
            listPackagesOut = "package:com.example.chat\n",
            throwOnListPackages = true,
            throwMessage = "binder died",
        )
        val repo = DualRepository(gateway)
        assertNull(runSuspend { repo.listClonedPackages() })
    }

    // PR-2 Phase 3 (R5): a throwing `pm list users` maps profilesSnapshot to
    // ChannelError (fail closed); listProfiles collapses it to emptyList by
    // design (C2 contract, kept). Simplest directly-observable collapse
    // path, no clone/remove flow needed.
    // CancellationException rethrow is NOT covered here: it needs runTest
    // cancellation semantics, which the hand-rolled runSuspend above cannot
    // model.
    @Test
    fun profilesSnapshot_throwingGateway_isChannelError_listProfilesEmpty() {
        val gateway = FakeGateway(
            throwOnListUsers = true,
            throwMessage = "spawn failed: su",
        )
        val repo = DualRepository(gateway)
        val snap = runSuspend { repo.profilesSnapshot() }
        assertTrue("throwing snapshot must be ChannelError, got: $snap", snap is ProfilesSnapshot.ChannelError)
        assertTrue(
            "detail must carry the channel error, got: \"${(snap as ProfilesSnapshot.ChannelError).detail}\"",
            snap.detail.contains("channel error"),
        )
        assertEquals(
            "listProfiles collapses channel failure to empty by design",
            emptyList<DualProfile>(),
            runSuspend { repo.listProfiles() },
        )
    }
}
