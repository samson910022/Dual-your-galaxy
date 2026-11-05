// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Third-round regression tests: list-based re-check. Real-device proven:
// right after `pm uninstall` succeeds, `pm path` can fail consecutively
// (non-zero exit, empty out/err) inside the teardown settling window --
// three back-to-back probes all non-zero, so no retry count saves it --
// while `pm list packages --user` on the same window stays deterministic
// ground truth (exit 0, package simply absent). verifyOnProfiles therefore
// probes `pm list packages` plus exact `pkg in parsePackages(out)`
// membership; a non-zero list probe is still retried at most twice
// (1500 ms settle wait, covering transient binder/timeout blips) before
// reporting Unknown, and Unknown with empty out/err carries the exit code.
//
// Speed/policy split (PR-3 stage 2, issue #24): every retry test below runs
// with a virtual delayFn (no real Thread.sleep); retry POLICY (attempt
// count, wait value) is locked by verifyRetryPolicy_constantsAreBounded
// alone, so a policy change fails there, not in timing.
class VerifyRetryTest {
    // In-memory gateway with a sequenced `pm list packages` queue: each list
    // call pops the head; when empty, listFallback is returned (so a
    // persistent failure is modeled by a short queue + failing fallback).
    private class SeqGateway(
        val listQueue: ArrayDeque<CmdResult> = ArrayDeque(),
        var listFallback: CmdResult = CmdResult(exit = 0, out = "", err = ""),
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        var listCalls = 0

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = DEFAULT_USERS, err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = DEFAULT_VERBOSE_95, err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = DEFAULT_DUMPSYS_95, err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = DEFAULT_PROP_95, err = "")
                cmd.startsWith("pm install-existing") ->
                    CmdResult(exit = 0, out = "Package com.example.chat installed for user: 95", err = "")
                cmd.startsWith("pm uninstall") ->
                    CmdResult(exit = 0, out = "Success", err = "")
                cmd.startsWith("pm list packages") -> {
                    listCalls++
                    if (listQueue.isNotEmpty()) listQueue.removeFirst() else listFallback
                }
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

    // Virtual-delay repo: records every settle wait instead of sleeping.
    private fun virtualRepo(gateway: SeqGateway, delays: MutableList<Long>): DualRepository {
        val repo = DualRepository(gateway)
        repo.delayFn = { delays.add(it) }
        return repo
    }

    // Teardown case that motivated the list probe: first two list probes fail
    // transiently (non-zero, empty), third is clean-empty -> confirmed absent
    // -> remove Success.
    @Test
    fun removeVerified_transientListFailure_retriesToSuccess() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(
                    CmdResult(exit = 1, out = "", err = ""),
                    CmdResult(exit = 1, out = "", err = ""),
                    CmdResult(exit = 0, out = "", err = ""),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected Success after settling, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
    }

    // Persistent channel failure: all three list probes non-zero -> Unknown,
    // and the empty-out/err detail carries the exit code for diagnosis.
    @Test
    fun removeVerified_persistentListFailure_isUnknownWithExitDetail() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must carry exit code, got: \"$detail\"", detail.contains("exit=1"))
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
    }

    // Clone path keeps its semantics under retry: a transient non-zero list
    // probe followed by a listing that contains the package is Success.
    @Test
    fun cloneVerified_transientListFailure_retriesToSuccess() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(
                    CmdResult(exit = 1, out = "", err = ""),
                    CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(2, gateway.listCalls)
        assertEquals(listOf(DualRepository.VERIFY_RETRY_DELAY_MS), delays)
    }

    // Single transient failure on clone followed by a clean listing without
    // the package is still VerifyFailed (fake success): retry masks blips,
    // never absence.
    @Test
    fun cloneVerified_transientThenAbsent_isVerifyFailed() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(
                    CmdResult(exit = 1, out = "", err = ""),
                    CmdResult(exit = 0, out = "", err = ""),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyFailed, got: $outcome", outcome is CloneOutcome.VerifyFailed)
        assertEquals(2, gateway.listCalls)
        assertEquals(listOf(DualRepository.VERIFY_RETRY_DELAY_MS), delays)
    }

    // PR-3 stage 1 (issue #24): the settle wait runs through the injectable
    // delayFn, so tests can go virtual -- same 3-probe retry contract as the
    // persistent-failure test above but without the real sleep stalls.
    // No wall-clock assertion: speed is proven by the virtual delays list
    // (exactly 2 waits recorded, zero Thread.sleep executed).
    @Test
    fun removeVerified_persistentFailure_withVirtualDelay_retriesThriceWithoutSleep() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        val repo = DualRepository(gateway)
        val delays = mutableListOf<Long>()
        repo.delayFn = { delays.add(it) }
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must carry exit code, got: \"$detail\"", detail.contains("exit=1"))
    }

    // Retry policy lock (no sleep asserted here): 1 initial + 2 retries with
    // a 1500 ms settle wait between attempts. Speed is covered by the
    // virtual-delay tests above; this test only pins the constants.
    @Test
    fun verifyRetryPolicy_constantsAreBounded() {
        assertEquals(1500L, DualRepository.VERIFY_RETRY_DELAY_MS)
        assertEquals(3, DualRepository.VERIFY_MAX_ATTEMPTS)
        // The injectable delay slot exists (tests replace it; production
        // never overwrites it at runtime): round-trip the getter/setter.
        val repo = DualRepository(SeqGateway())
        val original: suspend (Long) -> Unit = repo.delayFn
        repo.delayFn = original
    }

    // Boundary (a): clean exit-0 probe stops immediately -- zero waits,
    // single list call, confirmed absent -> remove Success.
    @Test
    fun removeVerified_cleanProbe_stopsImmediatelyWithoutDelay() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(1, gateway.listCalls)
        assertTrue("no settle wait on a clean probe, got: $delays", delays.isEmpty())
    }

    // Boundary (b): success on the 3rd (last) attempt stops -- exactly
    // 2 waits, neither more nor fewer; clone path proves the counter is
    // shared with removeVerified.
    @Test
    fun cloneVerified_thirdAttemptSuccess_stopsWithTwoWaits() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(
                    CmdResult(exit = 1, out = "", err = ""),
                    CmdResult(exit = 1, out = "", err = ""),
                    CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
    }

    // Boundary (c1): exit-0 dirty text ("Unknown package") is Unknown, not
    // confirmed-absent -- and needs no retry (single list call, zero waits).
    // Locked to the existing trusted-parse rule; production code unchanged.
    @Test
    fun removeVerified_exitZeroUnknownPackage_isUnknownWithoutRetry() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(
                    CmdResult(exit = 0, out = "Unknown package: com.example.chat", err = ""),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(1, gateway.listCalls)
        assertTrue("error-semantic exit-0 must not retry, got: $delays", delays.isEmpty())
    }

    // Boundary (c2): truncated capture is Unknown, not authoritative-empty
    // -- single list call, zero waits. Same lock as (c1).
    @Test
    fun removeVerified_truncatedListing_isUnknownWithoutRetry() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(
                    CmdResult(
                        exit = 0,
                        out = "package:com.example.chat\n[truncated]",
                        err = "",
                        truncated = true,
                    ),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(1, gateway.listCalls)
        assertTrue("truncated exit-0 must not retry, got: $delays", delays.isEmpty())
    }

    // Boundary (d): Shizuku timeout truth value (exit=1,
    // "shizuku: exec timed out after 60000ms") retried like any non-zero
    // probe -> VerifyUnknown after exactly VERIFY_MAX_ATTEMPTS calls.
    // Core tests cannot depend on the app-module ShizukuExecErrors, so the
    // literal CmdResult is inlined here.
    @Test
    fun removeVerified_shizukuTimeoutTruth_retriesToUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(
                exit = 1,
                out = "",
                err = "shizuku: exec timed out after 60000ms",
            ),
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must carry exit code, got: \"$detail\"", detail.contains("exit=1"))
    }
}
