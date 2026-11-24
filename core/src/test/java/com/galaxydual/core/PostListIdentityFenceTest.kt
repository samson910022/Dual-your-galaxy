// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Issue #66 Phase B regression tests: the write/verify entry points pin the
// verified target BEFORE the `pm list packages` probe but used to trust the
// probe's result without re-checking identity AFTER it. The fake gateway here
// flips the verified incarnation (95,15,0) -> (95,16,0) at the moment the
// listing command runs, so every entry point must fail closed with an Unknown
// outcome instead of Success/Installed.
//
// PR #88 F1 additions: a serial can become observable AFTER the first fetch.
// With `firstFetchSerialHidden`, the identity schedule is
// null -> 15 -> 15 -> (16 or null) across before/fresh/after/post-list; the
// old code pinned `before` (serial null), which tolerates both null->16 and
// null->null, and returned a false Success. The fence must instead pin the
// freshest already-observed identity (`fresh` for the after-write check,
// `after` for the post-list fence).
class PostListIdentityFenceTest {
    private class DriftGateway(
        // Set on the unknown-after-send arms: write reply is abandoned, so
        // the read-only probe decides and the post-list fence still applies.
        var writeTimedOut: Boolean = false,
        // When true, identity channels stop returning a usable target after
        // the listing (models evidence loss at the post-list re-verify).
        var identityLostAfterList: Boolean = false,
        // First identity triple has NO serial line (before.serial == null);
        // later pre-flip triples report `serialBeforeList`.
        var firstFetchSerialHidden: Boolean = false,
        var serialBeforeList: Int? = 15,
        // Serial seen once the incarnation flips; null models known->unknown.
        var serialAfterList: Int? = 16,
        // Listing normally performs the flip; the retry-wait test flips from
        // the injected delayFn instead so the drift happens DURING the wait.
        var listFlipsIdentity: Boolean = true,
        var listFailureCount: Int = 0,
        var listContainsPackage: Boolean = true,
        // Flip on the first identity fetch AFTER the write (the `after` pin
        // fetch), with no listing involved. Isolates the after-write drift
        // check from the post-list fence.
        var flipOnAfterFetch: Boolean = false,
        // Throw on the identity re-fetch performed by the post-list fence
        // (the first identity fetch after the listing). The fence must let the
        // CancellationException through instead of swallowing it as Unknown.
        var cancelOnPostListIdentityFetch: Boolean = false,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        var flipped = false
        var listPackagesCount = 0
        var writeCount = 0
        var identityFetchCount = 0
        private var currentSerial: Int? = 15

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} running:\n" +
                (currentSerial?.let { "    Serial: $it\n" } ?: "") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400}" +
                (currentSerial?.let { " serialNo=$it" } ?: "") + ":\n" +
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
                cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                    // writeCount > 0 means this is the post-write `after` pin
                    // fetch; !flipped keeps the flip to exactly one fetch.
                    if (flipOnAfterFetch && writeCount > 0 && !flipped) flipped = true
                    if (cancelOnPostListIdentityFetch && listPackagesCount > 0) {
                        throw CancellationException("cancelled during post-list identity re-fetch")
                    }
                    identityFetchCount++
                    currentSerial = when {
                        firstFetchSerialHidden && identityFetchCount == 1 -> null
                        flipped -> serialAfterList
                        else -> serialBeforeList
                    }
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                }
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
                    if (listPackagesCount <= listFailureCount) {
                        return CmdResult(exit = 1, out = "", err = "Failure: transient binder")
                    }
                    // The incarnation switch can happen WHILE the listing
                    // runs: every identity fetch after this point sees the
                    // post-flip serial (or loses it entirely).
                    if (listFlipsIdentity) flipped = true
                    CmdResult(
                        exit = 0,
                        out = if (listContainsPackage) "package:com.example.chat\n" else "",
                        err = "",
                    )
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

    private fun assertNoWriteResend(label: String, gateway: DriftGateway) {
        assertEquals("$label must never resend the write, got: ${gateway.commands}", 1, gateway.writeRuns())
    }

    @Test
    fun cloneVerified_serialFlipsDuringListing_returnsVerifyUnknown() {
        val gateway = DriftGateway()
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val unknown = outcome as CloneOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "cloneVerified")
        assertNoWriteResend("cloneVerified", gateway)
        assertEquals("probe must still run exactly once, got: ${gateway.commands}", 1, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerified_unknownAfterSend_serialFlipsDuringListing_returnsVerifyUnknown() {
        val gateway = DriftGateway(writeTimedOut = true)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertPostListDrift((outcome as CloneOutcome.VerifyUnknown).detail, "cloneVerified/unknown-after-send")
        assertNoWriteResend("cloneVerified/unknown-after-send", gateway)
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
        assertNoWriteResend("cloneVerifiedExpected", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerifiedExpected_unknownAfterSend_serialFlipsDuringListing_returnsVerifyUnknown() {
        // Timeout arm (DualRepository.kt:1325): the write reply is abandoned,
        // so the read-only probe decides; the post-list fence still applies.
        val gateway = DriftGateway(writeTimedOut = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val unknown = outcome as CloneOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "cloneVerifiedExpected/unknown-after-send")
        assertNoWriteResend("cloneVerifiedExpected/unknown-after-send", gateway)
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
        assertNoWriteResend("removeVerified", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun removeVerified_unknownAfterSend_serialFlipsDuringListing_returnsVerifyUnknown() {
        // Timeout arm (DualRepository.kt:1451): a clean absent listing would
        // otherwise be Success; the fence must turn it into VerifyUnknown.
        val gateway = DriftGateway(writeTimedOut = true, listContainsPackage = false)
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val unknown = outcome as RemoveOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "removeVerified/unknown-after-send")
        assertNoWriteResend("removeVerified/unknown-after-send", gateway)
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
        assertNoWriteResend("removeVerifiedExpected", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun removeVerifiedExpected_unknownAfterSend_serialFlipsDuringListing_returnsVerifyUnknown() {
        // Timeout arm (DualRepository.kt:1648): same contract as clone's
        // timeout arm; the absent listing alone must not become Success.
        val gateway = DriftGateway(writeTimedOut = true, listContainsPackage = false)
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected VerifyUnknown, never Success, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val unknown = outcome as RemoveOutcome.VerifyUnknown
        assertEquals(95, unknown.dualId)
        assertPostListDrift(unknown.detail, "removeVerifiedExpected/unknown-after-send")
        assertNoWriteResend("removeVerifiedExpected/unknown-after-send", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    // ---- Cancellation propagation through the post-list fence -------------

    @Test
    fun cloneVerified_postListIdentityFetchCancellation_propagatesWithoutWriteResend() {
        // The fence's identity re-fetch throws; it must propagate instead of
        // being folded into VerifyUnknown by the probe's thrown-defense.
        val gateway = DriftGateway(cancelOnPostListIdentityFetch = true)
        try {
            runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
            fail("CancellationException from the post-list re-fetch must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled during post-list identity re-fetch", e.message)
        }
        assertNoWriteResend("cloneVerified/post-list cancellation", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun removeVerifiedExpected_postListIdentityFetchCancellation_propagatesWithoutWriteResend() {
        val gateway = DriftGateway(
            cancelOnPostListIdentityFetch = true,
            listContainsPackage = false,
        )
        try {
            runSuspend {
                DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
            }
            fail("CancellationException from the post-list re-fetch must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled during post-list identity re-fetch", e.message)
        }
        assertNoWriteResend("removeVerifiedExpected/post-list cancellation", gateway)
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
        assertNoWriteResend("cloneVerified/evidence loss", gateway)
    }

    // ---- PR #88 F1: serial only becomes observable AFTER `before` ---------

    @Test
    fun cloneVerified_beforeNull_then15_then16_returnsVerifyUnknown() {
        val gateway = DriftGateway(firstFetchSerialHidden = true, serialAfterList = 16)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("null -> 15 -> 15 -> 16 must fail closed, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertPostListDrift((outcome as CloneOutcome.VerifyUnknown).detail, "cloneVerified/null->15->15->16")
        assertNoWriteResend("cloneVerified/null->15->15->16", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerified_beforeNull_then15_thenNull_returnsVerifyUnknown() {
        val gateway = DriftGateway(firstFetchSerialHidden = true, serialAfterList = null)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("null -> 15 -> 15 -> null must fail closed, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertPostListDrift(detail, "cloneVerified/null->15->15->null")
        assertTrue("known -> unknown must be named, got: '$detail'", detail.contains("continuity lost"))
        assertNoWriteResend("cloneVerified/null->15->15->null", gateway)
    }

    @Test
    fun cloneVerifiedUnknownAfterSend_beforeNull_then15_then16_returnsVerifyUnknown() {
        val gateway = DriftGateway(writeTimedOut = true, firstFetchSerialHidden = true, serialAfterList = 16)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertPostListDrift((outcome as CloneOutcome.VerifyUnknown).detail, "cloneVerified/unknown/null->15->15->16")
        assertNoWriteResend("cloneVerified/unknown/null->15->15->16", gateway)
    }

    @Test
    fun cloneVerifiedUnknownAfterSend_beforeNull_then15_thenNull_returnsVerifyUnknown() {
        val gateway = DriftGateway(writeTimedOut = true, firstFetchSerialHidden = true, serialAfterList = null)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertPostListDrift((outcome as CloneOutcome.VerifyUnknown).detail, "cloneVerified/unknown/null->15->15->null")
        assertNoWriteResend("cloneVerified/unknown/null->15->15->null", gateway)
    }

    @Test
    fun removeVerified_beforeNull_then15_then16_returnsVerifyUnknown() {
        val gateway = DriftGateway(firstFetchSerialHidden = true, serialAfterList = 16)
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertPostListDrift((outcome as RemoveOutcome.VerifyUnknown).detail, "removeVerified/null->15->15->16")
        assertNoWriteResend("removeVerified/null->15->15->16", gateway)
    }

    @Test
    fun removeVerified_beforeNull_then15_thenNull_returnsVerifyUnknown() {
        val gateway = DriftGateway(firstFetchSerialHidden = true, serialAfterList = null)
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertPostListDrift(detail, "removeVerified/null->15->15->null")
        assertTrue("known -> unknown must be named, got: '$detail'", detail.contains("continuity lost"))
        assertNoWriteResend("removeVerified/null->15->15->null", gateway)
    }

    @Test
    fun removeVerifiedUnknownAfterSend_beforeNull_then15_then16_returnsVerifyUnknown() {
        val gateway = DriftGateway(writeTimedOut = true, firstFetchSerialHidden = true, serialAfterList = 16)
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertPostListDrift((outcome as RemoveOutcome.VerifyUnknown).detail, "removeVerified/unknown/null->15->15->16")
        assertNoWriteResend("removeVerified/unknown/null->15->15->16", gateway)
    }

    @Test
    fun removeVerifiedUnknownAfterSend_beforeNull_then15_thenNull_returnsVerifyUnknown() {
        val gateway = DriftGateway(writeTimedOut = true, firstFetchSerialHidden = true, serialAfterList = null)
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertPostListDrift((outcome as RemoveOutcome.VerifyUnknown).detail, "removeVerified/unknown/null->15->15->null")
        assertNoWriteResend("removeVerified/unknown/null->15->15->null", gateway)
    }

    // ---- Defense in depth: drift on the post-write `after` fetch ----------
    // The after-write drift check must pin `fresh` (serial 15), not `before`
    // (serial null here). Reverting it to `before` would tolerate null->16 and
    // let the listing report a false Success, so these tests go RED on that
    // mutation even though the post-list fence is still present.

    @Test
    fun cloneVerified_serialFlipsOnAfterFetch_afterWriteDriftCheckFailsClosed() {
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            flipOnAfterFetch = true,
            listFlipsIdentity = false,
            serialAfterList = 16,
        )
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("after-fetch drift must fail closed, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("after-write drift check must name identity change, got: '$detail'", detail.contains("identity-changed"))
        assertTrue("detail must name serial reuse, got: '$detail'", detail.contains("serial reuse"))
        assertFalse("must be caught BEFORE the listing, got: '$detail'", detail.contains("post-list"))
        assertNoWriteResend("cloneVerified/after-fetch drift", gateway)
        assertEquals("drift check runs before the probe, got: ${gateway.commands}", 0, gateway.listPackagesCount)
    }

    @Test
    fun removeVerified_serialFlipsOnAfterFetch_afterWriteDriftCheckFailsClosed() {
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            flipOnAfterFetch = true,
            listFlipsIdentity = false,
            serialAfterList = 16,
            listContainsPackage = false,
        )
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("after-fetch drift must fail closed, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("after-write drift check must name identity change, got: '$detail'", detail.contains("identity-changed"))
        assertTrue("detail must name serial reuse, got: '$detail'", detail.contains("serial reuse"))
        assertFalse("must be caught BEFORE the listing, got: '$detail'", detail.contains("post-list"))
        assertNoWriteResend("removeVerified/after-fetch drift", gateway)
        assertEquals("drift check runs before the probe, got: ${gateway.commands}", 0, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerified_beforeNull_absentListingStillFailsClosed() {
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            serialAfterList = 16,
            listContainsPackage = false,
        )
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("absent listing must not bypass the post-list fence, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertPostListDrift((outcome as CloneOutcome.VerifyUnknown).detail, "cloneVerified/absent listing")
        assertNoWriteResend("cloneVerified/absent listing", gateway)
    }

    @Test
    fun removeVerified_beforeNull_absentListingStillFailsClosed() {
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            serialAfterList = 16,
            listContainsPackage = false,
        )
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("absent listing must not bypass the post-list fence, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertPostListDrift((outcome as RemoveOutcome.VerifyUnknown).detail, "removeVerified/absent listing")
        assertNoWriteResend("removeVerified/absent listing", gateway)
    }

    @Test
    fun cloneVerified_beforeNull_serialStaysKnown_returnsSuccessWithVerifiedSerial() {
        val gateway = DriftGateway(firstFetchSerialHidden = true, listFlipsIdentity = false)
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("stable serial must not be fenced as drift, got: $outcome", outcome is CloneOutcome.Success)
        val success = outcome as CloneOutcome.Success
        assertEquals(95, success.dualId)
        assertEquals("Success must carry the freshest observed serial", 15, success.verifiedSerial)
        assertNoWriteResend("cloneVerified/stable serial", gateway)
    }

    @Test
    fun removeVerified_beforeNull_serialStaysKnown_returnsSuccessWithVerifiedSerial() {
        // Remove confirms absence, so the (otherwise clean) listing is empty.
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            listFlipsIdentity = false,
            listContainsPackage = false,
        )
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("stable serial must not be fenced as drift, got: $outcome", outcome is RemoveOutcome.Success)
        val success = outcome as RemoveOutcome.Success
        assertEquals(95, success.dualId)
        assertEquals("Success must carry the freshest observed serial", 15, success.verifiedSerial)
        assertNoWriteResend("removeVerified/stable serial", gateway)
    }

    // ---- Pinned (Expected) Success carries the verified serial (Phase 1) --

    @Test
    fun cloneVerifiedExpected_serialStaysKnown_returnsSuccessWithVerifiedSerial() {
        val gateway = DriftGateway(listFlipsIdentity = false)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("stable serial must not be fenced as drift, got: $outcome", outcome is CloneOutcome.Success)
        val success = outcome as CloneOutcome.Success
        assertEquals(95, success.dualId)
        assertEquals("Expected Success must carry the pinned serial", 15, success.verifiedSerial)
        assertNoWriteResend("cloneVerifiedExpected/stable serial", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun removeVerifiedExpected_serialStaysKnown_returnsSuccessWithVerifiedSerial() {
        val gateway = DriftGateway(listFlipsIdentity = false, listContainsPackage = false)
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("stable serial must not be fenced as drift, got: $outcome", outcome is RemoveOutcome.Success)
        val success = outcome as RemoveOutcome.Success
        assertEquals(95, success.dualId)
        assertEquals("Expected Success must carry the pinned serial", 15, success.verifiedSerial)
        assertNoWriteResend("removeVerifiedExpected/stable serial", gateway)
        assertEquals(1, gateway.listPackagesCount)
    }

    @Test
    fun cloneVerified_serialNeverObservable_staysSuccessWithNullSerial() {
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            serialBeforeList = null,
            listFlipsIdentity = false,
        )
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("documented serial==null limit must be preserved, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(null, (outcome as CloneOutcome.Success).verifiedSerial)
        assertNoWriteResend("cloneVerified/serial never observable", gateway)
    }

    @Test
    fun removeVerified_serialNeverObservable_staysSuccessWithNullSerial() {
        // Mirror of the clone all-null case: remove confirms absence (empty
        // listing), and the documented serial==null capability limit holds on
        // both sides (all-null serials are tolerated by continuityBreach).
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            serialBeforeList = null,
            listFlipsIdentity = false,
            listContainsPackage = false,
        )
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("documented serial==null limit must be preserved, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(null, (outcome as RemoveOutcome.Success).verifiedSerial)
        assertNoWriteResend("removeVerified/serial never observable", gateway)
    }

    // The drift can land inside the listing's retry settle wait, not only at
    // the first list command. Uses runBlocking + a suspending injected delayFn
    // (the synchronous runSuspend above cannot drive a real suspension).
    @Test
    fun cloneVerified_driftDuringRetryWait_returnsVerifyUnknownWithTwoListsOneWrite() = runBlocking {
        val gateway = DriftGateway(
            firstFetchSerialHidden = true,
            listFlipsIdentity = false,
            listFailureCount = 1,
            serialAfterList = 16,
        )
        val repo = DualRepository(gateway)
        repo.delayFn = {
            gateway.flipped = true
            yield() // real suspension point, no wall-clock sleep
        }
        val outcome = repo.cloneVerified("com.example.chat")
        assertTrue("drift during retry wait must fail closed, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertPostListDrift((outcome as CloneOutcome.VerifyUnknown).detail, "cloneVerified/retry-wait drift")
        assertEquals("first list fails, second succeeds, got: ${gateway.commands}", 2, gateway.listPackagesCount)
        assertNoWriteResend("cloneVerified/retry-wait drift", gateway)
    }
}
