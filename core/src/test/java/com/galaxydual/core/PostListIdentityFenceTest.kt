// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #66 Phase B regression tests: the write/verify entry points pin the
// verified target BEFORE the `pm list packages` probe but used to trust the
// probe's result without re-checking identity AFTER it. The fake gateway here
// flips the verified incarnation (95,15,0) -> (95,16,0) at the moment the
// listing command runs, so every entry point must fail closed with an Unknown
// outcome instead of Success/Installed.
class PostListIdentityFenceTest {
    private class DriftGateway(
        // Set on the unknown-after-send arms: write reply is abandoned, so
        // the read-only probe decides and the post-list fence still applies.
        var writeTimedOut: Boolean = false,
        // When true, identity channels stop returning a usable target after
        // the listing (models evidence loss at the post-list re-verify).
        var identityLostAfterList: Boolean = false,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        var flipped = false
        var listPackagesCount = 0
        var writeCount = 0

        private fun serial(): Int = if (flipped) 16 else 15

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} running:\n" +
                "    Serial: ${serial()}\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=${serial()}:\n" +
                "    parentId=0\n" +
                "    userType: android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS -> CmdResult(
                    exit = 0,
                    out = "Users:\n" +
                        "\tUserInfo{0:Owner:13} running\n" +
                        "\tUserInfo{95:DUAL_APP:400} running\n",
                    err = "",
                )
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    if (flipped && identityLostAfterList) CmdResult(exit = 1, out = "", err = "denied")
                    else CmdResult(exit = 0, out = "95\n", err = "")
                cmd.startsWith("pm install-existing") || cmd.startsWith("pm uninstall") -> {
                    writeCount++
                    if (writeTimedOut) {
                        CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after 60000ms", timedOut = true)
                    } else {
                        CmdResult(exit = 0, out = "Success", err = "")
                    }
                }
                cmd.startsWith("pm list packages") -> {
                    listPackagesCount++
                    // The incarnation switch happens WHILE the listing runs:
                    // every identity fetch after this point sees (95,16,0).
                    flipped = true
                    CmdResult(exit = 0, out = "package:com.example.chat\n", err = "")
                }
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        fun writeRuns(): Int = writeCount
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

    private fun assertPostListDrift(detail: String, label: String) {
        assertTrue(
            "$label detail must name the post-list check, got: '$detail'",
            detail.contains("post-list", ignoreCase = true),
        )
        assertTrue(
            "$label detail must name the identity drift, got: '$detail'",
            detail.contains("identity", ignoreCase = true),
        )
    }

    @Test
    fun cloneVerified_serialFlipsDuringListing_returnsVerifyUnknown() {
        val gateway = DriftGateway()
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val unknown = outcome as CloneOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "cloneVerified")
        assertEquals("no write resend may occur, got: ${gateway.commands}", 1, gateway.writeRuns())
        assertEquals("probe must still run exactly once, got: ${gateway.commands}", 1, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerified_unknownAfterSend_serialFlipsDuringListing_returnsVerifyUnknown() {
        val gateway = DriftGateway(writeTimedOut = true)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertPostListDrift((outcome as CloneOutcome.VerifyUnknown).detail, "cloneVerified/unknown-after-send")
        assertEquals(1, gateway.writeRuns())
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerifiedExpected_serialFlipsDuringListing_returnsVerifyUnknown() {
        val gateway = DriftGateway()
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val unknown = outcome as CloneOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "cloneVerifiedExpected")
        assertEquals(1, gateway.writeRuns())
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun removeVerified_serialFlipsDuringListing_returnsVerifyUnknown() {
        val gateway = DriftGateway()
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val unknown = outcome as RemoveOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "removeVerified")
        assertEquals(1, gateway.writeRuns())
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun removeVerifiedExpected_serialFlipsDuringListing_returnsVerifyUnknown() {
        val gateway = DriftGateway()
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val unknown = outcome as RemoveOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "removeVerifiedExpected")
        assertEquals(1, gateway.writeRuns())
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun verifyState_serialFlipsDuringListing_returnsUnknown() {
        val gateway = DriftGateway()
        val outcome = runSuspend { DualRepository(gateway).verifyState("com.example.chat", 95) }
        assertTrue("expected Unknown, never Installed, got: $outcome", outcome is VerifyState.Unknown)
        assertPostListDrift((outcome as VerifyState.Unknown).detail, "verifyState")
        assertEquals("verify is read-only, got: ${gateway.commands}", 0, gateway.writeRuns())
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun verify_autoPath_serialFlipsDuringListing_returnsFalse() {
        val gateway = DriftGateway()
        val outcome = runSuspend { DualRepository(gateway).verify("com.example.chat") }
        assertTrue("auto verify must fail closed on post-list drift, got: $outcome", !outcome)
        assertEquals(0, gateway.writeRuns())
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerified_identityUnavailableAfterListing_returnsVerifyUnknown() {
        val gateway = DriftGateway(identityLostAfterList = true)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown on evidence loss, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must be fail-closed, got: '$detail'", detail.contains("post-list"))
        assertTrue("detail must name unavailable identity, got: '$detail'", detail.contains("unavailable"))
    }
}
