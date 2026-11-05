// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase C2 regression tests (issue #8): isSuccess line-level semantics.
// Covers: false-positive kills (success payloads echoing failure/exception-named
// packages), false-negative misses (real pm error modes), the
// INSTALL_FAILED_ALREADY_EXISTS idempotent-success policy, and listProfiles()
// sharing the isSuccess semantics. Pure Kotlin, no device required.
class PmIsSuccessTest {
    // In-memory gateway serving canned outputs per command prefix, with stderr
    // support so listProfiles()-with-Failure-on-stderr can be reproduced.
    // Verified-identity channels default to a Confirmed 95 dual target.
    private class FakeGateway(
        var listUsersOut: String = DEFAULT_USERS_95,
        var listUsersErr: String = "",
        var listUsersExit: Int = 0,
        var userListVerboseOut: String = DEFAULT_VERBOSE_95,
        var dumpsysOut: String = DEFAULT_DUMPSYS_95,
        var propOut: String = DEFAULT_PROP_95,
        var listPackagesOutput: String = "package:com.example.chat",
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL

        override suspend fun run(cmd: String): CmdResult =
            when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = listUsersExit, out = listUsersOut, err = listUsersErr)
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = userListVerboseOut, err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut, err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = propOut, err = "")
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = 0, out = listPackagesOutput, err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
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

    // Issue #8 repro 1: success payload echoing a "failure"-named package must
    // not be misjudged. Old code matched the "failure" substring anywhere.
    @Test
    fun successEcho_withFailureNamedPackage_isSuccess() {
        assertTrue(
            PmOutputParser.isSuccess(
                "Package com.example.failureapp installed for user: 95",
                0,
            ),
        )
        assertTrue(
            PmOutputParser.isSuccess(
                "package:/data/app/com.failure.app/base.apk",
                0,
            ),
        )
        assertTrue(PmOutputParser.isSuccess("Success", 0))
        // Uninstall success echo next to a failure-named path line.
        assertTrue(
            PmOutputParser.isSuccess(
                "Success\npackage:/data/app/com.failure.helper/base.apk",
                0,
            ),
        )
    }

    // Issue #8 repro 2: same for "exception"-named packages.
    @Test
    fun successEcho_withExceptionNamedPackage_isSuccess() {
        assertTrue(
            PmOutputParser.isSuccess(
                "Package com.example.exceptionapp installed for user: 95",
                0,
            ),
        )
        assertTrue(
            PmOutputParser.isSuccess(
                "package:/data/app/com.exception.app/base.apk",
                0,
            ),
        )
    }

    // Word-boundary guards: error keywords glued to package-name letters must
    // not trigger, even on non-package lines is covered via the exempt success
    // echoes; bare "errorapp"/"failedapp"/"deniedapp" echoes stay success.
    @Test
    fun keywordGluedToLetters_isNotFailure() {
        assertTrue(
            PmOutputParser.isSuccess(
                "Package com.errorapp installed for user: 95",
                0,
            ),
        )
        assertTrue(
            PmOutputParser.isSuccess(
                "Package com.failedapp installed for user: 95",
                0,
            ),
        )
        assertTrue(
            PmOutputParser.isSuccess(
                "Package com.deniedapp installed for user: 95",
                0,
            ),
        )
        assertTrue(
            PmOutputParser.isSuccess(
                "package:/data/app/com.error.app/base.apk",
                0,
            ),
        )
    }

    // True pm errors (exit 0 with error text) must still be failures --
    // including on lines that also mention a failure-named package.
    @Test
    fun truePmErrors_areFailures() {
        // Failure [...] lines.
        assertFalse(
            PmOutputParser.isSuccess(
                "Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]",
                0,
            ),
        )
        assertFalse(
            PmOutputParser.isSuccess(
                "Failure [DELETE_FAILED_INTERNAL_ERROR: System error]",
                0,
            ),
        )
        assertFalse(
            PmOutputParser.isSuccess(
                "Failure [INSTALL_FAILED_INVALID_APK: Failed to extract native libraries]",
                0,
            ),
        )
        // Error: / unknown-package / not-installed statements.
        assertFalse(
            PmOutputParser.isSuccess(
                "Error: Unknown package: com.example.chat",
                0,
            ),
        )
        assertFalse(
            PmOutputParser.isSuccess("Unknown package: com.failure.app", 0),
        )
        assertFalse(
            PmOutputParser.isSuccess(
                "Package com.example.chat is not installed for user 95",
                0,
            ),
        )
        // Exception headers and stack lines.
        assertFalse(
            PmOutputParser.isSuccess(
                "Exception occurred while executing 'install':\n" +
                    "java.lang.IllegalArgumentException: Unknown package\n" +
                    "\tat com.android.server.pm.PackageManagerShellCommand.runInstall(PackageManagerShellCommand.java:123)",
                0,
            ),
        )
        assertFalse(
            PmOutputParser.isSuccess(
                "Caused by: java.lang.SecurityException: Permission denied",
                0,
            ),
        )
        assertFalse(
            PmOutputParser.isSuccess(
                "java.lang.SecurityException: Neither user 2000 nor current process has permission",
                0,
            ),
        )
        // failed / error / denied / cannot / abort / invalid modes.
        assertFalse(PmOutputParser.isSuccess("Failed to install for user 95", 0))
        assertFalse(PmOutputParser.isSuccess("Error: Failed to create session", 0))
        assertFalse(PmOutputParser.isSuccess("Permission denied", 0))
        assertFalse(
            PmOutputParser.isSuccess(
                "Permission Denial: broadcast from null asks to run as user -1",
                0,
            ),
        )
        assertFalse(PmOutputParser.isSuccess("Cannot install on managed profile", 0))
        assertFalse(PmOutputParser.isSuccess("Can't install for user 95", 0))
        assertFalse(PmOutputParser.isSuccess("Install aborted: user request", 0))
        assertFalse(PmOutputParser.isSuccess("Invalid APK file", 0))
        // SCREAMING_SNAKE codes use underscores: boundaries must still match.
        assertFalse(
            PmOutputParser.isSuccess("Failure [PERMISSION_DENIED: no such user]", 0),
        )
    }

    // Non-zero exit is failure regardless of text (unchanged from before C2).
    @Test
    fun nonZeroExit_isFailure() {
        assertFalse(PmOutputParser.isSuccess("", 1))
        assertFalse(PmOutputParser.isSuccess("Success", 1))
        assertFalse(
            PmOutputParser.isSuccess(
                "Package com.example.chat installed for user: 95",
                1,
            ),
        )
    }

    // INSTALL_FAILED_ALREADY_EXISTS policy: idempotent success, regardless of
    // exit code (ROMs differ: some report exit 0, some non-zero with the same
    // Failure line). The clone goal state already holds.
    @Test
    fun alreadyExists_isIdempotentSuccess() {
        val payload =
            "Failure [INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install " +
                "com.example.chat without first uninstalling.]"
        assertTrue(PmOutputParser.isSuccess(payload, 0))
        assertTrue(PmOutputParser.isSuccess(payload, 1))
        assertTrue(PmOutputParser.isSuccess(payload.lowercase(), 0))
    }

    // listProfiles() shares isSuccess: exit 0 but Failure on stderr -> empty.
    @Test
    fun listProfiles_withFailureOnStderr_returnsEmpty() {
        val gateway = FakeGateway(
            listUsersOut = "Users:\n\tUserInfo{0:Owner:13} running\n",
            listUsersErr = "Failure [INSTALL_FAILED_INVALID_APK: bad]",
            listUsersExit = 0,
        )
        val repo = DualRepository(gateway)
        assertTrue(runSuspend { repo.listProfiles() }.isEmpty())
    }

    // listProfiles() happy path unaffected: normal UserInfo output still parses.
    @Test
    fun listProfiles_normalOutput_stillParses() {
        val gateway = FakeGateway(
            listUsersOut = "Users:\n" +
                "\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{95:DUAL_APP:400} running\n",
        )
        val repo = DualRepository(gateway)
        val profiles = runSuspend { repo.listProfiles() }
        assertEquals(2, profiles.size)
        assertEquals(95, runSuspend { repo.resolveDualUserId() })
    }

    // verify() end to end with a failure-named package: exact list membership
    // still verifies true (no substring/isSuccess misjudgment).
    @Test
    fun verify_withFailureNamedPackagePath_isTrue() {
        val gateway = FakeGateway(
            listUsersOut = "Users:\n" +
                "\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{95:DUAL_APP:400} running\n",
            listPackagesOutput = "package:com.failure.app",
        )
        val repo = DualRepository(gateway)
        assertTrue(runSuspend { repo.verify("com.failure.app", 95) })
    }

    // verify() is false when the clean listing lacks the package: absence
    // text is absence evidence (the list probe uses exit-only semantics,
    // never isSuccess).
    @Test
    fun verify_withPmError_isFalse() {
        val gateway = FakeGateway(
            listUsersOut = "Users:\n" +
                "\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{95:DUAL_APP:400} running\n",
            listPackagesOutput = "Failure [INSTALL_FAILED_INVALID_APK: bad]",
        )
        val repo = DualRepository(gateway)
        // No package:com.example.chat line, so confirmed absent.
        assertFalse(runSuspend { repo.verify("com.example.chat", 95) })
    }
}
