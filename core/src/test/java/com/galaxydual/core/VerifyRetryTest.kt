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
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected Success after settling, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(3, gateway.listCalls)
    }

    // Persistent channel failure: all three list probes non-zero -> Unknown,
    // and the empty-out/err detail carries the exit code for diagnosis.
    @Test
    fun removeVerified_persistentListFailure_isUnknownWithExitDetail() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(3, gateway.listCalls)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must carry exit code, got: \"$detail\"", detail.contains("exit=1"))
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
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(2, gateway.listCalls)
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
        val outcome = runSuspend { DualRepository(gateway).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyFailed, got: $outcome", outcome is CloneOutcome.VerifyFailed)
        assertEquals(2, gateway.listCalls)
    }

    // PR-2 Phase 3 (T6): retry exhausted into a dirty final -- [exit 1,
    // exit-0 error-semantic] -- is Unknown whose detail names the error line
    // (not the exit code), after exactly 2 probes. Costs one 1500 ms settle
    // sleep (single retry on the exit-1 head; the exit-0 dirty tail never
    // retries -- see T7 below).
    @Test
    fun removeVerified_retryThenDirtyFinal_isUnknownWithErrorDetail() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(
                    CmdResult(exit = 1, out = "", err = "timeout"),
                    CmdResult(
                        exit = 0,
                        out = "Failure [DELETE_FAILED_INTERNAL_ERROR: System error]",
                        err = "",
                    ),
                ),
            ),
        )
        val outcome = runSuspend { DualRepository(gateway).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(2, gateway.listCalls)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the error-semantic line, got: \"$detail\"", detail.contains("Failure"))
    }

    // PR-2 Phase 3 (T7): exit-0 captures never retry -- truncated and dirty
    // finals each cost exactly 1 probe. Retry fires only on exit != 0.
    @Test
    fun verifyState_truncatedExitZero_doesNotRetry() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat\n", err = "", truncated = true),
        )
        val state = runSuspend { DualRepository(gateway).verifyState("com.example.chat", 95) }
        assertTrue("truncated exit-0 listing must be Unknown, got: $state", state is VerifyState.Unknown)
        assertEquals(1, gateway.listCalls)
    }

    @Test
    fun verifyState_dirtyExitZero_doesNotRetry() {
        val gateway = SeqGateway(
            listFallback = CmdResult(
                exit = 0,
                out = "",
                err = "java.lang.SecurityException: Neither user 2000 nor current process has permission",
            ),
        )
        val state = runSuspend { DualRepository(gateway).verifyState("com.example.chat", 95) }
        assertTrue("dirty exit-0 listing must be Unknown, got: $state", state is VerifyState.Unknown)
        assertEquals(1, gateway.listCalls)

    // PR-3 stage 1 (issue #24): the settle wait runs through the injectable
    // delayFn, so tests can go virtual -- same 3-probe retry contract as the
    // persistent-failure test above but without the ~3 s of real sleep stalls.
    @Test
    fun removeVerified_persistentFailure_withVirtualDelay_retriesThriceWithoutSleep() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        val repo = DualRepository(gateway)
        val delays = mutableListOf<Long>()
        repo.delayFn = { delays.add(it) }
        val start = System.nanoTime()
        val outcome = runSuspend { repo.removeVerified("com.example.chat") }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(3, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
        assertTrue(
            "virtual delay must skip the real sleep, took ${elapsedMs}ms",
            elapsedMs < DualRepository.VERIFY_RETRY_DELAY_MS,
        )
    }
}
