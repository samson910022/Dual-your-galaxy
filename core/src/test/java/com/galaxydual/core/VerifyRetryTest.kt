// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        // Phase 3 review (issue #24): when non-null, the `pm list packages`
        // probe throws instead of returning -- models a rogue gateway.
        var listThrowable: Throwable? = null,
        // PR-32 P1-1 (unknown-after-send): when non-null, the write phase
        // (`pm install-existing` / `pm uninstall`) returns this instead of
        // the canned success -- models an install/uninstall whose reply was
        // abandoned (e.g. timedOut=true) while the remote may have executed.
        var writeResult: CmdResult? = null,
        // Phase E P1-1 (delayed landing): when armed, the write does NOT
        // immediately change the list view -- the first
        // [landingAfterListCalls] list probes issued AFTER the write still
        // see the pre-land queue/fallback, and only later probes see
        // [postLandingListResult]. Models "write lands after the immediate
        // post-timeout probe" deterministically. Counted by
        // [postWriteListCalls] (NIT-2: independent of any pre-write list
        // probe, so a future pre-write list probe cannot shift the landing;
        // only probes with writeCalls > 0 advance the count). Null
        // (default) disables the mode.
        var landingAfterListCalls: Int? = null,
        var postLandingListResult: CmdResult? = null,
        // Phase E P1-1 (wait-window identity change): when non-null, every
        // verbose/dumpsys identity fetch after the first write returns this
        // serial instead of 15 -- models a profile recreated (same id, fresh
        // serial) while the client was waiting on the write reply.
        var serialAfterWrite: Int? = null,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        var listCalls = 0
        var writeCalls = 0
        // NIT-2: post-write list-probe count -- advanced only when
        // writeCalls > 0, so pre-write list probes (none today; the pinned
        // flows snapshot `pm list users`, not `pm list packages`, before
        // the write) never shift the delayed-landing arm above.
        var postWriteListCalls = 0

        private fun verbosePayload(): String {
            val s = if (writeCalls > 0) serialAfterWrite ?: 15 else 15
            return DEFAULT_VERBOSE_95.replace("Serial: 15", "Serial: $s")
        }

        private fun dumpsysPayload(): String {
            val s = if (writeCalls > 0) serialAfterWrite ?: 15 else 15
            return DEFAULT_DUMPSYS_95.replace("serialNo=15", "serialNo=$s")
        }

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = DEFAULT_USERS, err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verbosePayload(), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysPayload(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = DEFAULT_PROP_95, err = "")
                cmd.startsWith("pm install-existing") -> {
                    writeCalls++
                    writeResult ?: CmdResult(exit = 0, out = "Package com.example.chat installed for user: 95", err = "")
                }
                cmd.startsWith("pm uninstall") -> {
                    writeCalls++
                    writeResult ?: CmdResult(exit = 0, out = "Success", err = "")
                }
                cmd.startsWith("pm list packages") -> {
                    listCalls++
                    if (writeCalls > 0) postWriteListCalls++
                    listThrowable?.let { throw it }
                    val pre = if (listQueue.isNotEmpty()) listQueue.removeFirst() else listFallback
                    val arm = landingAfterListCalls
                    val landed = postLandingListResult
                    if (arm != null && landed != null && postWriteListCalls > arm) landed else pre
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
        val outcome = runSuspend { virtualRepo(gateway, mutableListOf()).removeVerified("com.example.chat") }
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

    // Phase 3 review (issue #24): a rogue gateway that THROWS (non-cancel)
    // on the list probe is Unknown, never a guess -- verify path collapses
    // to VerifyUnknown with zero settle waits (single-shot throw, no retry).
    @Test
    fun verifyListProbe_throwingGateway_isUnknownWithoutRetry() {
        val gateway = SeqGateway(listThrowable = IllegalStateException("boom"))
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        assertEquals(1, gateway.listCalls)
        assertTrue("throwing probe must not retry, got: $delays", delays.isEmpty())
    }

    // Same rogue gateway on the refresh path: listProbe is single-shot
    // unknown (null), never an authoritative empty list.
    @Test
    fun listProbe_throwingGateway_isNull() {
        val gateway = SeqGateway(listThrowable = IllegalStateException("boom"))
        val result = runSuspend { virtualRepo(gateway, mutableListOf()).listClonedPackages() }
        assertNull("throwing list probe must be unknown (null), got: $result", result)
        assertEquals(1, gateway.listCalls)
    }

    // Cancellation must propagate: a CancellationException from the list
    // probe is rethrown, never folded into Unknown. Uses the stdlib
    // kotlin.coroutines.cancellation.CancellationException (same JVM class
    // as kotlinx.coroutines.CancellationException: both are typealiases of
    // java.util.concurrent.CancellationException).
    @Test
    fun verifyListProbe_cancellationException_propagates() {
        val gateway = SeqGateway(listThrowable = CancellationException("cancel"))
        val delays = mutableListOf<Long>()
        try {
            runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
            fail("expected CancellationException to propagate, not fold into Unknown")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
        assertEquals(1, gateway.listCalls)
        assertTrue("cancelled probe must not retry, got: $delays", delays.isEmpty())
    }

    // Same propagation guarantee on the refresh path: listProbe rethrows
    // CancellationException instead of returning null.
    @Test
    fun listProbe_cancellationException_propagates() {
        val gateway = SeqGateway(listThrowable = CancellationException("cancel"))
        try {
            runSuspend { virtualRepo(gateway, mutableListOf()).listClonedPackages() }
            fail("expected CancellationException to propagate, not fold into null")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
        assertEquals(1, gateway.listCalls)
    }

    // PR-32 P1-1 (a): install reply timed out (exit/out untrusted) but the
    // read-only probe confirms the package present -> Success. The failure-
    // looking exit=1 must NOT become InstallFailed.
    @Test
    fun cloneVerified_writeTimedOut_probeInstalled_isSuccess() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after 60000ms", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-1 (b): uninstall reply timed out but the probe confirms the
    // package absent -> Success (ground truth backs the removal).
    @Test
    fun removeVerified_writeTimedOut_probeAbsent_isSuccess() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after 60000ms", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-1 (c): write timed out AND the read-only probe is itself
    // unknown (persistent non-zero) -> VerifyUnknown whose detail names the
    // write timeout. The UI list is preserved by the caller; the next
    // refresh converges. Single write (no resend) + bounded probe retries.
    @Test
    fun removeVerified_writeTimedOut_probeUnknown_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after 60000ms", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must keep the probe cause, got: \"$detail\"", detail.contains("exit=1"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
    }

    // Same unknown-convergence on the clone path: write timed out, probe
    // unknown -> VerifyUnknown (never InstallFailed/VerifyFailed).
    @Test
    fun cloneVerified_writeTimedOut_probeUnknown_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "timeout after 60000ms: su", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
    }

    // Timeout table, absent side (Phase E P1-1): clone write timed out but
    // the immediate probe cleanly lacks the package -> VerifyUnknown (NOT
    // VerifyFailed -- the probe ran right after the timeout and the remote
    // install may still land after it; unknown-after-send never claims
    // "not cloned" while the device may hold the clone). Never InstallFailed.
    @Test
    fun cloneVerified_writeTimedOut_probeAbsent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 0, out = "Success", err = "", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertTrue("detail must defer to refresh convergence, got: \"$detail\"", detail.contains("refresh"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
    }

    // Timeout table, present side (Phase E P1-1): remove write timed out but
    // the immediate probe cleanly still lists the package -> VerifyUnknown
    // (NOT StillInstalled -- the probe ran right after the timeout and the
    // remote uninstall may still land after it). Never RemoveFailed.
    @Test
    fun removeVerified_writeTimedOut_probePresent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 0, out = "Success", err = "", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertTrue("detail must defer to refresh convergence, got: \"$detail\"", detail.contains("refresh"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
    }

    // PR-32 P1-1 (d): non-timeout write failures stay definite failures
    // (no regression): install failure -> InstallFailed without probing.
    @Test
    fun cloneVerified_writeFailedWithoutTimeout_isInstallFailed() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "Failure [INSTALL_FAILED_INVALID_APK]", err = "")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected InstallFailed, got: $outcome", outcome is CloneOutcome.InstallFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }

    // Same lock on the remove path: uninstall failure -> RemoveFailed.
    @Test
    fun removeVerified_writeFailedWithoutTimeout_isRemoveFailed() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "Failure [DELETE_FAILED_INTERNAL_ERROR]")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected RemoveFailed, got: $outcome", outcome is RemoveOutcome.RemoveFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-1 follow-up (MAJOR): a service-side 60 s captureProcess
    // timeout arrives over the frozen wire as exit=1 + "timeout after ..."
    // text with timedOut=false (the flag is lost on the parcel -- only
    // (exit, out, err) survive). The write must still go verify-first:
    // probe Installed -> Success, never InstallFailed.
    @Test
    fun cloneVerified_serviceSideTimeoutText_probeInstalled_isSuccess() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "timeout after 60000ms: sh", timedOut = false)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // Same wire-loss shape on the remove path, probe itself unknown ->
    // VerifyUnknown whose detail names the text-recognized service timeout.
    @Test
    fun removeVerified_serviceSideTimeoutText_probeUnknown_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "timeout after 60000ms: sh", timedOut = false)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the text-recognized service timeout, got: \"$detail\"", detail.contains("recognized by text"))
        assertTrue("detail must keep the probe cause, got: \"$detail\"", detail.contains("exit=1"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
    }

    // Phase E review MINOR-1 (reverse of cloneVerified_serviceSideTimeoutText_
    // probeInstalled_isSuccess above): the service-side 60 s timeout text
    // (timedOut=false on the wire) plus a clean REVERSE probe (absent right
    // after a clone) -> VerifyUnknown whose detail names the text
    // recognition (never VerifyFailed -- the install may still land after
    // the probe). Single write, single probe, no settle wait.
    @Test
    fun cloneVerified_serviceSideTimeoutText_probeAbsent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "timeout after 60000ms: sh", timedOut = false)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must name the text-recognized service timeout, got: \"$detail\"", detail.contains("recognized by text"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertTrue("detail must defer to refresh convergence, got: \"$detail\"", detail.contains("refresh"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean reverse probe must not wait, got: $delays", delays.isEmpty())
    }

    // Anti-false-positive lock: an ordinary "timeout" word that is NOT one
    // of our timeout statements must stay a definite failure (no probing).
    @Test
    fun cloneVerified_ordinaryTimeoutWord_isInstallFailedWithoutProbe() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "Error: timeout waiting for user response")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected InstallFailed, got: $outcome", outcome is CloneOutcome.InstallFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }

    // Direct predicate locks: only the exact producer prefixes hit. The
    // bind-failure wrapper, interrupt/spawn texts, mid-line "timeout" words
    // (either case), and empty err never match.
    @Test
    fun isServiceTimeoutText_onlyMatchesProducerPrefixes() {
        assertTrue(isServiceTimeoutText("timeout after 60000ms: sh"))
        assertTrue(isServiceTimeoutText("timeout after 200ms: su"))
        assertTrue(isServiceTimeoutText("shizuku: exec timed out after 60000ms"))
        assertTrue(!isServiceTimeoutText("shizuku: bind failed: bind timed out after 10000ms"))
        assertTrue(!isServiceTimeoutText("interrupted: wait interrupted"))
        assertTrue(!isServiceTimeoutText("spawn failed: No such file or directory"))
        assertTrue(!isServiceTimeoutText("Error: timeout waiting for user response"))
        assertTrue(!isServiceTimeoutText("Failure [INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: timeout?]"))
        assertTrue(!isServiceTimeoutText(""))
    }

    // PR-32 P2-1 (cancel-between-probes): a Job cancelled while sitting in
    // the settle wait between probe 1 (exit != 0) and probe 2 must propagate
    // CancellationException (never fold into Unknown) and must NOT run the
    // extra probe (listCalls stays 1). The wait is the production-equivalent
    // cancellable suspend (real delay(), gated to Long.MAX_VALUE so the
    // test -- not the wall clock -- decides when the wait ends): a blocking
    // Thread.sleep wait could not answer cancel promptly. delayCalls counts
    // production-shape delay invocations (exactly 1: the cancelled wait).
    // Core owns no in-flight key (the serial key lives in the VM); core only
    // guarantees it never swallows cancellation, and VM key release is
    // already finally-guaranteed (Phase 1). No VM test here by design (P2-2
    // builds VM test infra).
    @Test
    fun verifyListProbe_cancelBetweenProbes_propagatesWithoutExtraProbe() {
        val gateway = SeqGateway(
            listQueue = ArrayDeque(
                listOf(CmdResult(exit = 1, out = "", err = "")),
            ),
            // Present-listing fallback: proves no second probe ran (a second
            // probe would return Installed and flip the assertions below).
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        val repo = DualRepository(gateway)
        val entered = CompletableDeferred<Unit>()
        var delayCalls = 0
        repo.delayFn = { _ ->
            delayCalls++
            entered.complete(Unit)
            delay(Long.MAX_VALUE)
        }
        runBlocking {
            val deferred = async { repo.removeVerified("com.example.chat") }
            entered.await()
            deferred.cancel()
            try {
                deferred.await()
                fail("expected CancellationException, not an outcome (never fold cancel into Unknown)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected: cooperative cancel out of the settle wait.
            }
        }
        assertEquals(1, gateway.listCalls)
        assertEquals(1, delayCalls)
    }

    // PR-33 final review MINOR-1 (Phase E P1-1 update): removeVerifiedExpected
    // write-timeout branch (timedOut || isServiceTimeoutText -> after-pin then
    // verify-first, no resend). timedOut=true with a clean probe that still
    // lists the package -> VerifyUnknown (NOT StillInstalled -- the probe ran
    // right after the timeout and the remote uninstall may still land after
    // it). Single write.
    @Test
    fun removeVerifiedExpected_writeTimedOut_probeInstalled_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after 60000ms", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerifiedExpected("com.example.chat", 95, 15) }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // Same branch via the wire-frozen text backstop (timedOut=false lost on
    // the parcel, isServiceTimeoutText hit): probe Unknown -> VerifyUnknown
    // whose detail names the text recognition plus the probe cause.
    @Test
    fun removeVerifiedExpected_serviceSideTimeoutText_probeUnknown_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "timeout after 60000ms: sh", timedOut = false)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerifiedExpected("com.example.chat", 95, 15) }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the text-recognized service timeout, got: \"$detail\"", detail.contains("recognized by text"))
        assertTrue("detail must keep the probe cause, got: \"$detail\"", detail.contains("exit=1"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
    }

    // Phase E P1-1 (delayed landing, clone side): the install reply times out
    // and the immediate probe still sees absent -- VerifyUnknown (NOT
    // VerifyFailed), single write. The fake arms delayed landing: the first
    // post-write list probe sees absent, later probes see present, proving
    // the immediate absent could not exclude a late landing.
    @Test
    fun cloneVerified_writeTimedOut_probeAbsentFirst_landsAfter_isVerifyUnknown() {
        val absent = CmdResult(exit = 0, out = "", err = "")
        val present = CmdResult(exit = 0, out = "package:com.example.chat", err = "")
        val gateway = SeqGateway(
            listQueue = ArrayDeque(listOf(absent)),
            listFallback = absent,
            landingAfterListCalls = 1,
            postLandingListResult = present,
        )
        gateway.writeResult = CmdResult(exit = 0, out = "Success", err = "", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertTrue("detail must defer to refresh convergence, got: \"$detail\"", detail.contains("refresh"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        // Prove the landing: the next list probe (what a refresh would see)
        // now observes the package -- the first absent proved nothing.
        val landed = runSuspend { gateway.run("pm list packages --user 95") }
        assertTrue("write must land after the first probe, got: $landed", landed.out.contains("package:com.example.chat"))
    }

    // Phase E P1-1 (delayed landing, remove side): the uninstall reply times
    // out and the immediate probe still sees present -- VerifyUnknown (NOT
    // StillInstalled), single write. Landing is armed symmetrically: first
    // post-write probe present, later probes absent.
    @Test
    fun removeVerified_writeTimedOut_probePresentFirst_landsAfter_isVerifyUnknown() {
        val present = CmdResult(exit = 0, out = "package:com.example.chat", err = "")
        val absent = CmdResult(exit = 0, out = "", err = "")
        val gateway = SeqGateway(
            listQueue = ArrayDeque(listOf(present)),
            listFallback = present,
            landingAfterListCalls = 1,
            postLandingListResult = absent,
        )
        gateway.writeResult = CmdResult(exit = 0, out = "Success", err = "", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertTrue("detail must defer to refresh convergence, got: \"$detail\"", detail.contains("refresh"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        // Prove the landing: the next list probe (what a refresh would see)
        // now observes absent -- the first present proved nothing.
        val landed = runSuspend { gateway.run("pm list packages --user 95") }
        assertTrue("write must land after the first probe, got: $landed", !landed.out.contains("package:com.example.chat"))
    }

    // Phase E P1-1 (wait-window identity change, clone): the serial flips
    // from 15 to 16 while the client waits on the timed-out install, so the
    // timeout-branch after-pin (before vs fresh after) drifts ->
    // VerifyUnknown with identity-changed. Single timed-out write (no
    // resend), and the list probe never runs (drift short-circuits).
    @Test
    fun cloneVerified_writeTimedOut_serialChanged_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
            serialAfterWrite = 16,
        )
        gateway.writeResult = CmdResult(exit = 0, out = "Success", err = "", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must carry identity-changed, got: \"$detail\"", detail.contains("identity-changed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("drift short-circuits before any settle wait, got: $delays", delays.isEmpty())
    }

    // Phase E P1-1 (wait-window identity change, remove): the serial flips
    // from 15 to 16 while the client waits on the timed-out uninstall, so
    // the timeout-branch after-pin drifts -> VerifyUnknown with
    // identity-changed. Single timed-out write (no resend), zero list
    // probes.
    @Test
    fun removeVerified_writeTimedOut_serialChanged_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
            serialAfterWrite = 16,
        )
        gateway.writeResult = CmdResult(exit = 0, out = "Success", err = "", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must carry identity-changed, got: \"$detail\"", detail.contains("identity-changed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("drift short-circuits before any settle wait, got: $delays", delays.isEmpty())
    }

    // Phase E P1-1 (wait-window identity change, expected-remove): the serial
    // flips from 15 to 16 while the client waits on the timed-out uninstall,
    // so the timeout-branch after-pin (syntheticBefore vs fresh after)
    // drifts -> VerifyUnknown with identity-changed. Zero uninstalls beyond
    // the single timed-out write (no resend), and the list probe never runs
    // (drift short-circuits before it).
    @Test
    fun removeVerifiedExpected_writeTimedOut_serialChanged_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
            serialAfterWrite = 16,
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after 60000ms", timedOut = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerifiedExpected("com.example.chat", 95, 15) }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must carry identity-changed, got: \"$detail\"", detail.contains("identity-changed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("drift short-circuits before any settle wait, got: $delays", delays.isEmpty())
    }

    // Phase E review MINOR-2 (clone, timeout-branch after-pin drift via the
    // wire-frozen text backstop): the serial flips from 15 to 16 while the
    // client waits on the install, so the timeout-branch after-pin drifts.
    // The list view holds the SAME-direction observation (present -- the
    // probe that would have been Success) but drift wins: VerifyUnknown
    // with identity-changed, zero list probes, no settle wait.
    @Test
    fun cloneVerified_serviceSideTimeoutText_serialChanged_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
            serialAfterWrite = 16,
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "timeout after 60000ms: sh", timedOut = false)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must carry identity-changed, got: \"$detail\"", detail.contains("identity-changed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("drift short-circuits before any settle wait, got: $delays", delays.isEmpty())
    }

    // Phase E review MINOR-2 (remove, timeout-branch after-pin drift via the
    // wire-frozen text backstop): the serial flips from 15 to 16 while the
    // client waits on the uninstall, so the timeout-branch after-pin drifts.
    // The list view holds the SAME-direction observation (absent -- the
    // probe that would have been Success) but drift wins: VerifyUnknown
    // with identity-changed, zero list probes, no settle wait.
    @Test
    fun removeVerified_serviceSideTimeoutText_serialChanged_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
            serialAfterWrite = 16,
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "timeout after 60000ms: sh", timedOut = false)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must carry identity-changed, got: \"$detail\"", detail.contains("identity-changed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("drift short-circuits before any settle wait, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-2 (post-dispatch reply loss, clone same-direction): the server
    // executed pm but the reply was lost (transact threw / binder died
    // mid-call / reply decode failed -> transportUnknown, timedOut=false).
    // exit/out/err are untrusted, so the read-only probe decides: present ->
    // Success. Single write (no resend), single clean probe.
    @Test
    fun cloneVerified_transportUnknown_probeInstalled_isSuccess() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(
            exit = 1,
            out = "",
            err = "shizuku: transport unknown (reply lost, may have executed): reply decode failed",
            transportUnknown = true,
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-2 (post-dispatch reply loss, clone reverse): the reply was
    // lost and the immediate probe cleanly lacks the package -> VerifyUnknown
    // (NOT VerifyFailed -- the remote install may still land after the
    // probe). Never InstallFailed.
    @Test
    fun cloneVerified_transportUnknown_probeAbsent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(
            exit = 1,
            out = "",
            err = "shizuku: transport unknown (reply lost, may have executed): binder died mid-call",
            transportUnknown = true,
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name the transport loss, got: \"$detail\"", detail.contains("transport unknown"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-2 (post-dispatch reply loss, remove same-direction): reply
    // lost, probe confirms absent -> Success (ground truth backs the
    // removal). Single write, single clean probe.
    @Test
    fun removeVerified_transportUnknown_probeAbsent_isSuccess() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(
            exit = 1,
            out = "",
            err = "shizuku: transport unknown (reply lost, may have executed): transact failed",
            transportUnknown = true,
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-2 (post-dispatch reply loss, remove probe unknown): reply lost
    // AND the read-only probe is itself unknown (persistent non-zero) ->
    // VerifyUnknown whose detail names the transport loss and keeps the
    // probe cause. Single write (no resend) + bounded probe retries.
    @Test
    fun removeVerified_transportUnknown_probeUnknown_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 1, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(
            exit = 1,
            out = "",
            err = "shizuku: transport unknown (reply lost, may have executed): reply decode failed",
            transportUnknown = true,
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name the transport loss, got: \"$detail\"", detail.contains("transport unknown"))
        assertTrue("detail must keep the probe cause, got: \"$detail\"", detail.contains("exit=1"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(DualRepository.VERIFY_MAX_ATTEMPTS, gateway.listCalls)
        assertEquals(
            listOf(DualRepository.VERIFY_RETRY_DELAY_MS, DualRepository.VERIFY_RETRY_DELAY_MS),
            delays,
        )
    }

    // PR-32 P1-2 (post-dispatch reply loss, expected-remove third path):
    // reply lost, probe confirms absent -> Success (after-pin holds, ground
    // truth backs the removal). Single write, single clean probe.
    @Test
    fun removeVerifiedExpected_transportUnknown_probeAbsent_isSuccess() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(
            exit = 1,
            out = "",
            err = "shizuku: transport unknown (reply lost, may have executed): transact failed",
            transportUnknown = true,
        )
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerifiedExpected("com.example.chat", 95, 15) }
        assertTrue("expected Success, got: $outcome", outcome is RemoveOutcome.Success)
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean probe must not wait, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-2 (pre-dispatch对照, clone): the command never reached the
    // service (no binder, both flags false) -> definite InstallFailed with
    // no read-only probe. Guards against inflating every transport error
    // into Unknown.
    @Test
    fun cloneVerified_preDispatchNotSent_isInstallFailedWithoutProbe() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "shizuku: binder not connected (install/start Shizuku first)")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected InstallFailed, got: $outcome", outcome is CloneOutcome.InstallFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }

    // PR-32 P1-2 (pre-dispatch对照, remove): bind failure before transact
    // (both flags false) -> definite RemoveFailed with no read-only probe.
    @Test
    fun removeVerified_preDispatchBindFailed_isRemoveFailedWithoutProbe() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "shizuku: bind failed: bind timed out after 10000ms")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected RemoveFailed, got: $outcome", outcome is RemoveOutcome.RemoveFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }

    // Phase A1 (PR #40 Re-review P1): post-spawn interrupt text backstop.
    // "interrupted: ..." arrives over the frozen wire with flags lost
    // (service-side wait/drain interrupt after spawn) -- must reconcile
    // read-only, never claim definite failure.
    @Test
    fun isPostSpawnTransportText_onlyMatchesAnchoredPostSpawnPrefixes() {
        assertTrue(isPostSpawnTransportText("interrupted: wait interrupted"))
        assertTrue(isPostSpawnTransportText("interrupted: drain join interrupted: sleep"))
        assertTrue(isPostSpawnTransportText("service exec failed: boom"))
        assertTrue(isPostSpawnTransportText("service exec failed: interrupted: boom"))
        assertTrue(!isPostSpawnTransportText("spawn failed: No such file or directory"))
        assertTrue(!isPostSpawnTransportText("su failed: boom"))
        assertTrue(!isPostSpawnTransportText("shizuku: bind failed: bind timed out after 10000ms"))
        assertTrue(!isPostSpawnTransportText("Failure [INSTALL_FAILED_INVALID_APK: bad]"))
        assertTrue(!isPostSpawnTransportText("Failure [DELETE_FAILED_INTERNAL_ERROR]"))
        assertTrue(!isPostSpawnTransportText("Error: interrupted while waiting"))
        assertTrue(!isPostSpawnTransportText("timeout after 60000ms: sh"))
        assertTrue(!isPostSpawnTransportText("shizuku: exec timed out after 60000ms"))
        assertTrue(!isPostSpawnTransportText(""))
    }

    // Phase A1: clone write interrupted post-spawn (text backstop, flags
    // lost on the wire) + immediate clean REVERSE probe (absent) ->
    // VerifyUnknown (NOT VerifyFailed -- the install may still land after
    // the probe). Single write (no resend), single probe, no settle wait.
    // The Unknown outcome keeps the UI list untouched (caller only appends
    // on Success); the next refresh converges from ground truth.
    @Test
    fun cloneVerified_interruptedText_probeAbsent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "interrupted: wait interrupted", truncated = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name post-spawn unknown, got: \"$detail\"", detail.contains("post-spawn"))
        assertTrue("detail must keep the write signal, got: \"$detail\"", detail.contains("interrupted"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean reverse probe must not wait, got: $delays", delays.isEmpty())
    }

    // Phase A1: clone write service-side failure post-spawn (text backstop)
    // + clean reverse probe (absent) -> VerifyUnknown (never InstallFailed).
    @Test
    fun cloneVerified_serviceExecFailedText_probeAbsent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "service exec failed: boom")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name post-spawn unknown, got: \"$detail\"", detail.contains("post-spawn"))
        assertTrue("detail must keep the write signal, got: \"$detail\"", detail.contains("service exec failed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean reverse probe must not wait, got: $delays", delays.isEmpty())
    }

    // Phase A1: remove write interrupted post-spawn (text backstop) +
    // immediate clean REVERSE probe (present) -> VerifyUnknown (NOT
    // StillInstalled -- the uninstall may still land after the probe).
    // Single write, single probe.
    @Test
    fun removeVerified_interruptedText_probePresent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "interrupted: wait interrupted", truncated = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name post-spawn unknown, got: \"$detail\"", detail.contains("post-spawn"))
        assertTrue("detail must keep the write signal, got: \"$detail\"", detail.contains("interrupted"))
        assertTrue("detail must explain the reverse observation is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean reverse probe must not wait, got: $delays", delays.isEmpty())
    }

    // Phase A1: remove write service-side failure post-spawn (text backstop)
    // + clean reverse probe (present) -> VerifyUnknown (never RemoveFailed).
    @Test
    fun removeVerified_serviceExecFailedText_probePresent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "service exec failed: boom")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name post-spawn unknown, got: \"$detail\"", detail.contains("post-spawn"))
        assertTrue("detail must keep the write signal, got: \"$detail\"", detail.contains("service exec failed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean reverse probe must not wait, got: $delays", delays.isEmpty())
    }

    // Phase A1: expected-remove write interrupted post-spawn (text backstop)
    // + clean reverse probe (present) -> VerifyUnknown (NOT StillInstalled).
    // Third pinned path walks the same after-pin + single probe + reverse
    // Unknown + no resend contract.
    @Test
    fun removeVerifiedExpected_interruptedText_probePresent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "interrupted: wait interrupted", truncated = true)
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerifiedExpected("com.example.chat", 95, 15) }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name post-spawn unknown, got: \"$detail\"", detail.contains("post-spawn"))
        assertTrue("detail must keep the write signal, got: \"$detail\"", detail.contains("interrupted"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean reverse probe must not wait, got: $delays", delays.isEmpty())
    }

    // Phase A1: expected-remove write service-side failure post-spawn (text
    // backstop) + clean reverse probe (present) -> VerifyUnknown (never
    // RemoveFailed).
    @Test
    fun removeVerifiedExpected_serviceExecFailedText_probePresent_isVerifyUnknown() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "service exec failed: boom")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerifiedExpected("com.example.chat", 95, 15) }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is RemoveOutcome.VerifyUnknown)
        val detail = (outcome as RemoveOutcome.VerifyUnknown).detail
        assertTrue("detail must name post-spawn unknown, got: \"$detail\"", detail.contains("post-spawn"))
        assertTrue("detail must keep the write signal, got: \"$detail\"", detail.contains("service exec failed"))
        assertEquals(1, gateway.writeCalls)
        assertEquals(1, gateway.listCalls)
        assertTrue("clean reverse probe must not wait, got: $delays", delays.isEmpty())
    }

    // Phase A1对照: pre-spawn spawn failure stays definite on all three
    // paths (process never started, pm could not have executed) -- no
    // read-only probe, no Unknown inflation.
    @Test
    fun cloneVerified_spawnFailedText_isInstallFailedWithoutProbe() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "spawn failed: No such file or directory")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).cloneVerified("com.example.chat") }
        assertTrue("expected InstallFailed, got: $outcome", outcome is CloneOutcome.InstallFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }

    @Test
    fun removeVerified_spawnFailedText_isRemoveFailedWithoutProbe() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "spawn failed: No such file or directory")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerified("com.example.chat") }
        assertTrue("expected RemoveFailed, got: $outcome", outcome is RemoveOutcome.RemoveFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }

    @Test
    fun removeVerifiedExpected_spawnFailedText_isRemoveFailedWithoutProbe() {
        val gateway = SeqGateway(
            listFallback = CmdResult(exit = 0, out = "", err = ""),
        )
        gateway.writeResult = CmdResult(exit = 1, out = "", err = "spawn failed: No such file or directory")
        val delays = mutableListOf<Long>()
        val outcome = runSuspend { virtualRepo(gateway, delays).removeVerifiedExpected("com.example.chat", 95, 15) }
        assertTrue("expected RemoveFailed, got: $outcome", outcome is RemoveOutcome.RemoveFailed)
        assertEquals(1, gateway.writeCalls)
        assertEquals(0, gateway.listCalls)
        assertTrue("failed write must not probe, got: $delays", delays.isEmpty())
    }
}
