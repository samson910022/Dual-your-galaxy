// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-4 P1 (issue #23): two-phase remove confirmation -- ViewModel unit tests.
// requestRemove() must not run any uninstall; cancelRemove() clears without
// uninstall; rapid confirmRemove() executes at most once (inFlightWrites
// dedup); a moved target (verified id / label) is rejected and requires a
// fresh confirmation; rotation policy is pinned: config rotation (same
// ViewModel instance) RETAINS the pending, while a recreated ViewModel
// starts with null pending (fail closed, must request again).
//
// Harness: DualViewModel runs on viewModelScope (Dispatchers.Main), so tests
// replace Main with Unconfined via kotlinx-coroutines-test setMain/resetMain.
// Repository I/O runs on Dispatchers.IO (real pool); tests poll StateFlow
// with a timeout instead of advancing a test scheduler. No device, no
// Android runtime; the fake gateway serves the verified-identity channels
// (verbose + dumpsys + property agree) plus the uninstall/list probes.
// DEVICE-UNVERIFIED: the unwritable-channel branch (NONE privilege) is not
// covered here (needs a NONE-privilege harness); rapid dedup beyond the
// existing rapidConfirm is not covered (would need a blocking uninstall).
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoveConfirmTest {
    // In-memory repo gateway with a movable verified target. dualId drives
    // all identity channels (list users + verbose + dumpsys + property) so
    // flipping it simulates the dual profile moving under the dialog.
    private class FakeRepoGateway(var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        var uninstallCalls = 0
        val commands = mutableListOf<String>()
        var uninstallOut = "Success"
        var uninstallExit = 0
        var listPackagesOut = ""
        var listPackagesExit = 0
        // Stateful installed set (PR-33 R3, P2): verifyState reads
        // `pm list packages` ground truth, so the fake tracks installs.
        // Default contains the chat pkg used by most tests; the overwrite
        // test adds its own pkgs in-fixture.
        val installedPkgs = mutableSetOf("com.example.chat")
        // Backward-compatible label override: "change label, keep id" simulation.
        // Null (default) keeps the historic "DualApp" on every channel.
        var labelOverride: String? = null
        private val label: String get() = labelOverride ?: "DualApp"
        // Agreed platform serial for the dual profile on both verbose
        // channels (PR-33 R2, P1 serial binding). Flipping it after a request
        // simulates a delete+recreate that reuses id+label with a fresh
        // serial; noSerial omits both serial lines to simulate an
        // unavailable serial (id confirmed, serial missing).
        var serial: Int = 15
        var noSerial: Boolean = false
        // S24 new-ROM verbose (PR-33 D2): `N: id=..., name=..., type=...`
        // lines with no UserInfo header and no serial line; dumpsys stays
        // in the legacy UserInfo shape (serialNo= authoritative).
        // Strict-semantics adaptation (fixture-only): the CLONE line keeps
        // its flags evidence without the `, flags=` comma -- base splits an
        // explicit `userType=` value on `,`/`;`, so `profile.CLONE` plus a
        // `flags=...` segment is an intra-line contradiction (UNKNOWN, no
        // dual target). Flags are display-only; parentId tail retained.
        var s24Verbose: Boolean = false

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:$label:400} running\n"

        private fun verboseOut(): String =
            if (!s24Verbose) {
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{$dualId:$label:400} running:\n" +
                    (if (noSerial) "" else "    Serial: $serial\n") +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
            } else {
                "3 users:\n" +
                    "\n" +
                    "0: id=0, name=Owner, type=full.SYSTEM," +
                    " flags=ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM" +
                    " (running) (current) (visible)\n" +
                    "1: id=$dualId, name=$label, type=profile.CLONE" +
                    " (flags=DUALAPP_PROFILE|INITIALIZED|PROFILE)" +
                    " (parentId=0) (running) (visible)\n" +
                    "2: id=150, name=Secure Folder, type=profile.PRIVATE," +
                    " flags=INITIALIZED|PROFILE|SECURE_FOLDER" +
                    " (parentId=0) (running) (profile-owner) (visible)\n"
            }

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:$label:400}" + (if (noSerial) "" else " serialNo=$serial") + ":\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd.trim() == "id -u" ->
                    CmdResult(exit = 0, out = "0\n", err = "")
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = usersOut(), err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = "$dualId\n", err = "")
                cmd.startsWith("pm uninstall") -> {
                    uninstallCalls++
                    // Stateful: last token is the pkg; a successful uninstall
                    // removes it so the post-write re-check sees NotInstalled.
                    val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                    if (uninstallExit == 0) installedPkgs.remove(pkg)
                    CmdResult(exit = uninstallExit, out = uninstallOut, err = "")
                }
                cmd.startsWith("pm list packages") -> {
                    val base = installedPkgs.map { "package:$it" }.joinToString("\n")
                    val out = if (base.isEmpty()) {
                        listPackagesOut
                    } else if (listPackagesOut.isEmpty()) {
                        base
                    } else {
                        base + "\n" + listPackagesOut
                    }
                    CmdResult(exit = listPackagesExit, out = out, err = "")
                }
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }
    }

    private val vms = mutableListOf<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try {
                vm.viewModelScope.cancel()
            } catch (_: Exception) {
                // Best effort: test process is discarded anyway.
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun makeVm(gw: FakeRepoGateway, nowMs: () -> Long = System::currentTimeMillis): DualViewModel {
        val repo = DualRepository(gw)
        // Real SuElevator over the fake gateway: probeChannels only counts
        // hasRoot when the elevator IS a SuElevator (and hasShizuku for
        // ShizukuElevator), so a FakeElevator would never flip readiness.
        // `id -u` -> 0 proves ROOT through the real probe path.
        val vm = DualViewModel(
            repo = repo,
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = SuCmdGateway(),
            shizukuGateway = ShizukuCmdGateway("com.example.test"),
            activeGateway = gw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowMs = nowMs,
        )
        vms.add(vm)
        return vm
    }

    private fun awaitReady(vm: DualViewModel, timeoutMs: Long = 5_000L) {
        await(timeoutMs) { vm.state.value.channelReady }
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout waiting for condition")
            }
            Thread.sleep(20)
        }
    }

    // Same poll loop, but a timeout carries the UiState dump so a red test
    // shows what the ViewModel was stuck on. The old await() above is kept
    // verbatim for awaitReady and the original five tests.
    private fun awaitVm(vm: DualViewModel, timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        try {
            await(timeoutMs, cond)
        } catch (e: AssertionError) {
            throw AssertionError("timeout waiting for condition; state=${vm.state.value}")
        }
    }

    // Phase 1 records the verified target and runs zero uninstalls.
    @Test
    fun request_setsPendingWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null }

        val pending = vm.state.value.pendingRemove
        assertNotNull("request must set pending", pending)
        assertEquals("com.example.chat", pending!!.pkg)
        assertEquals(95, pending.dualId)
        assertTrue("profile label must be recorded", pending.profileLabel.isNotBlank())
        assertEquals("no uninstall may run on request", 0, gw.uninstallCalls)
    }

    // Cancel drops the pending without running any uninstall.
    @Test
    fun cancel_clearsPendingWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null }
        vm.cancelRemove()

        await { vm.state.value.pendingRemove == null }
        assertNull(vm.state.value.pendingRemove)
        assertEquals("cancel must not uninstall", 0, gw.uninstallCalls)
    }

    // Rapid double confirm executes at most once (shared inFlightWrites dedup:
    // the second concurrent confirm reports "already in progress").
    // Flake note: the two confirms race (Unconfined + IO); the terminal msg
    // depends on who writes last: winner writes "removed: ..." on success,
    // concurrent loser deduped by inFlightWrites writes "already in progress",
    // serial loser arriving after pending is cleared writes "沒有待確認的移除".
    // All three are legal; the strong invariants are uninstallCalls == 1 and
    // pending cleared.
    @Test
    fun rapidConfirm_executesAtMostOnce() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null }

        vm.confirmRemove()
        vm.confirmRemove()
        await(timeoutMs = 5_000L) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("double confirm must run a single uninstall", 1, gw.uninstallCalls)
        assertNull("pending must be cleared", vm.state.value.pendingRemove)
        assertTrue(
            "final msg must be a legal terminal state, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg in setOf(
                "removed: com.example.chat",
                "already in progress: com.example.chat",
                "沒有待確認的移除",
            ),
        )
    }

    // The target moved between request and confirm (verified id 95 -> 96):
    // confirm must refuse, run zero uninstalls, clear the pending, and ask
    // for a fresh confirmation.
    @Test
    fun targetChange_rejectsAndRequiresReconfirm() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null }

        gw.dualId = 96
        vm.confirmRemove()
        await(timeoutMs = 5_000L) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("moved target must not uninstall", 0, gw.uninstallCalls)
        assertTrue(
            "rejection must ask for re-confirmation, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("重新確認"),
        )
    }

    // Rotation policy (explicit choice): a config rotation keeps the same
    // ViewModel instance, so the pending is retained and the dialog re-shows
    // with no re-send. A recreated ViewModel (process death) starts with
    // null pending (fail closed): the user must request again.
    @Test
    fun rotation_retainsPendingButRecreationDiscards() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm1 = makeVm(gw)
        awaitReady(vm1)

        vm1.requestRemove("com.example.chat")
        await { vm1.state.value.pendingRemove != null }

        // Config rotation: same instance, pending retained (dialog re-shows).
        assertNotNull(
            "rotation must retain pending on the same ViewModel",
            vm1.state.value.pendingRemove,
        )

        // Process recreation: a fresh ViewModel starts with no pending.
        val vm2 = makeVm(gw)
        assertNull(
            "recreated ViewModel must start with null pending (must re-confirm)",
            vm2.state.value.pendingRemove,
        )
        awaitReady(vm2)
        assertNull(
            "recreated ViewModel must stay null after probe (fail closed)",
            vm2.state.value.pendingRemove,
        )
    }

    // Same id, new label (profile renamed under the dialog): confirm must
    // refuse with zero uninstalls, clear the pending, and ask for a fresh
    // confirmation (INVALID).
    @Test
    fun confirm_labelChange_rejectsWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        gw.labelOverride = "OtherLabel"
        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("renamed target must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertTrue(
            "rejection must ask for re-confirmation, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("重新確認"),
        )
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // Confirm with nothing pending reports INVALID and runs no uninstall.
    @Test
    fun confirm_noPending_reportsInvalidWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.msg.contains("沒有待確認") }

        assertEquals("confirm with no pending must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // Request, cancel, then confirm: the cancelled confirm runs nothing.
    @Test
    fun confirm_afterCancel_runsNothing() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }
        vm.cancelRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null }

        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.msg.contains("沒有待確認") }

        assertEquals("confirm after cancel must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
    }

    // The dual profile vanished between request and confirm (all identity
    // channels follow dualId, resolver returns -1): confirm reports NO_DUAL
    // with zero uninstalls.
    @Test
    fun confirm_targetDisappears_reportsNoDual() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        gw.dualId = -1
        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("vanished target must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertEquals("no dual profile", vm.state.value.msg)
        assertEquals(MsgKind.NO_DUAL, vm.state.value.msgKind)
    }

    // A blank request is rejected as INVALID with no pending and no uninstall.
    @Test
    fun request_blank_setsInvalidWithoutPending() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("   ")
        awaitVm(vm) { vm.state.value.msg == "invalid package" }

        assertNull(vm.state.value.pendingRemove)
        assertEquals("blank request must not uninstall", 0, gw.uninstallCalls)
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // A second request overwrites the first: last wins, still zero uninstalls.
    @Test
    fun request_secondOverwritesFirst_lastWins() {
        val gw = FakeRepoGateway(dualId = 95)
        gw.installedPkgs.add("com.example.a")
        gw.installedPkgs.add("com.example.b")
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.a")
        awaitVm(vm) { vm.state.value.pendingRemove?.pkg == "com.example.a" }

        vm.requestRemove("com.example.b")
        awaitVm(vm) { vm.state.value.pendingRemove?.pkg == "com.example.b" && !vm.state.value.loading }

        assertEquals("com.example.b", vm.state.value.pendingRemove?.pkg)
        assertEquals("overwrite must not uninstall", 0, gw.uninstallCalls)
    }

    // Legacy single-tap entry only opens the confirmation (phase 1): pending
    // is set, no uninstall runs.
    @Suppress("DEPRECATION")
    @Test
    fun removeLegacy_shim_opensPendingWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.remove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        assertNotNull("legacy remove must open a pending", vm.state.value.pendingRemove)
        assertEquals("com.example.chat", vm.state.value.pendingRemove!!.pkg)
        assertEquals("legacy remove must not uninstall", 0, gw.uninstallCalls)
    }

    // Uninstall reports failure: exactly one uninstall ran, the pending is
    // cleared, and the outcome is FAILED (the entry is kept by the caller).
    @Test
    fun confirm_removeFailed_clearsPendingAndKeepsEntry() {
        val gw = FakeRepoGateway(dualId = 95)
        gw.uninstallExit = 1
        gw.uninstallOut = "Failure"
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("failed uninstall runs exactly once", 1, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertEquals(MsgKind.FAILED, vm.state.value.msgKind)
        assertTrue(
            "failure detail must surface, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("Failure"),
        )
    }

    // A second confirm after a successful one finds no pending and runs no
    // second uninstall; the pinned success result ("removed: ...") is kept,
    // not overwritten by the late no-pending report (PR-33 S2, P2 終態釘住).
    @Test
    fun confirm_secondConfirmAfterSuccess_pinsResult() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }
        assertEquals("first confirm must run one uninstall", 1, gw.uninstallCalls)

        vm.confirmRemove()
        awaitVm(vm) { !vm.state.value.loading }

        assertEquals("second confirm must not run another uninstall", 1, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertEquals("removed: com.example.chat", vm.state.value.msg)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
    }

    // Cancel with nothing pending is a no-op: pending stays null, no
    // uninstall runs, and the message is untouched.
    @Test
    fun cancel_withoutPending_isNoop() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        val before = vm.state.value.msg
        vm.cancelRemove()

        assertNull(vm.state.value.pendingRemove)
        assertEquals("cancel with no pending must not uninstall", 0, gw.uninstallCalls)
        assertEquals("cancel with no pending must leave msg untouched", before, vm.state.value.msg)
    }

    // Same id, same label, fresh serial (profile deleted and recreated under
    // the dialog, PR-33 R2 P1): confirm must refuse with zero uninstalls,
    // clear the pending, and ask for a fresh confirmation (INVALID).
    @Test
    fun confirm_serialChange_rejectsWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        gw.serial = 41
        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("recreated target must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertTrue(
            "rejection must ask for re-confirmation, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("重新確認"),
        )
        assertTrue(
            "rejection must name the serial mismatch, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("serial"),
        )
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // The id is confirmed but no serial is available on either verbose
    // channel (PR-33 R2 P1): request must refuse with no pending and zero
    // uninstalls (NO_DUAL, fail closed).
    @Test
    fun request_serialUnavailable_refusesWithoutPending() {
        val gw = FakeRepoGateway(dualId = 95)
        gw.noSerial = true
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("serial") }

        assertNull(vm.state.value.pendingRemove)
        assertEquals("unverifiable target must not uninstall", 0, gw.uninstallCalls)
        assertEquals(MsgKind.NO_DUAL, vm.state.value.msgKind)
    }

    // P2 安裝狀態 (PR-33 R3): requesting a package that is not installed
    // refuses with no pending and zero uninstalls (INFO 無需移除).
    @Test
    fun request_notInstalled_refusesWithoutPending() {
        val gw = FakeRepoGateway(dualId = 95)
        gw.installedPkgs.clear()
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("無需移除") }

        assertNull(vm.state.value.pendingRemove)
        assertEquals("absent package must not uninstall", 0, gw.uninstallCalls)
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
    }

    // P2 時效 (PR-33 R3): a stale consent older than REMOVE_CONFIRM_TTL_MS
    // is refused with zero uninstalls (INVALID + 重新確認, must re-request).
    @Test
    fun confirm_stalePending_rejectsAndRequiresReconfirm() {
        val gw = FakeRepoGateway(dualId = 95)
        var now = System.currentTimeMillis()
        val vm = makeVm(gw) { now }
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        now += DualViewModel.REMOVE_CONFIRM_TTL_MS + 1
        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("stale consent must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertTrue(
            "expiry must ask for re-confirmation, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("重新確認"),
        )
        assertEquals(MsgKind.INVALID, vm.state.value.msgKind)
    }

    // P2 安裝重驗 (PR-33 R3): the package vanished between request and
    // confirm (external uninstall) -- confirm clears without uninstalling
    // (INFO 無需移除, cloned left for refresh to sync).
    @Test
    fun confirm_packageGoneBeforeConfirm_clearsPendingWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        gw.installedPkgs.clear()
        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }

        assertEquals("gone package must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertTrue(
            "gone package must report no-op, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("無需移除"),
        )
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
    }

    // Cancel-during-confirm (B2): cancelRemove() interleaved while
    // confirmRemove() is suspended on IO must abort the confirm with zero
    // uninstalls and must preserve the cancel message.
    //
    // 機制（DualViewModel.kt:844-848, 1000-1003）：Unconfined 下
    // vm.confirmRemove() 的前綴同步執行——讀 pending、加 dedup key、拿未競爭
    // 的 opMutex、isWritable 純狀態檢查、置 loading=true——直到首個 onIo
    //（withContext(IO)，切 dispatcher 必掛起）才交出控制；cancelRemove()
    // 是同步 _state.update 且不拿 mutex，故必在 confirm 掛起後、post-IO
    // 重驗前到達並清掉 pending。confirm 的 IO 序列（resolveDualUserId +
    // fetchDualTarget + profilesSnapshot + verifyState→fetchVerifiedDualId，
    // 共 3 次 verbose）結束後的 post-IO 重驗（pendingRemove != cur）abort，
    // 連 repo.removeVerifiedExpected 都沒進（其兩道 isStillValid 閘與
    // uninstall 皆未到達）；abort 只清 loading，保留 cancel 訊息與 kind。
    // 誠實註記：掛起後的交錯是執行緒競速而非硬屏障（fake gateway 純記憶體、
    // IO 極快），計數 verbose 設閘反而脆弱（次數隨 verifyState/repo 內部
    // 實現漂移），故不設 gate；此測試以 msg 保留斷言鎖定 preserve 語義，
    // 若未來競速落敗（出現 uninstall 或訊息被覆寫）會直接轉紅而非靜默通過。
    @Test
    fun confirm_cancelDuringConfirm_abortsWithoutUninstall() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        vm.confirmRemove()
        vm.cancelRemove()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.pendingRemove == null }

        assertEquals("cancel-during-confirm must not uninstall", 0, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
        assertFalse(vm.state.value.loading)
        assertTrue(
            "abort must preserve the cancel message, got: \"${vm.state.value.msg}\"",
            vm.state.value.msg.contains("已取消移除"),
        )
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)
    }

    // PR-33 D2 (S24 實機格式相容): new-format verbose carries no serial
    // line, so the dumpsys serialNo= alone must bind end to end: request
    // builds a pending with dualSerial from the single source and runs
    // zero uninstalls (Secure Folder line present but never selected).
    @Test
    fun request_s24VerboseWithoutSerial_succeedsWithDumpsysSerial() {
        val gw = FakeRepoGateway(dualId = 95)
        gw.s24Verbose = true
        gw.serial = 95
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        val pending = vm.state.value.pendingRemove
        assertNotNull("S24 verbose must still build a pending", pending)
        assertEquals(95, pending!!.dualId)
        assertEquals("single-source dumpsys serial must bind", 95, pending.dualSerial)
        assertEquals("no uninstall may run on request", 0, gw.uninstallCalls)
    }

    // PR-33 S2 (P2 終態結果釘住): a serial second confirm after success must
    // not overwrite the pinned "removed: ..." result with the no-pending
    // report; no second uninstall runs and pending stays null.
    @Test
    fun confirm_afterSuccess_doesNotOverwriteResult() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.msg == "removed: com.example.chat" }

        vm.confirmRemove()
        awaitVm(vm) { !vm.state.value.loading }

        assertEquals("removed: com.example.chat", vm.state.value.msg)
        assertEquals("late confirm must not run another uninstall", 1, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
    }
}
