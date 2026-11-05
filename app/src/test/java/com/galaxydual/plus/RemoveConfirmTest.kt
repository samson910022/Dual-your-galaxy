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
        // Backward-compatible label override: "change label, keep id" simulation.
        // Null (default) keeps the historic "DualApp" on every channel.
        var labelOverride: String? = null
        private val label: String get() = labelOverride ?: "DualApp"

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:$label:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:$label:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:$label:400} serialNo=15:\n" +
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
                    CmdResult(exit = uninstallExit, out = uninstallOut, err = "")
                }
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOut, err = "")
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

    private fun makeVm(gw: FakeRepoGateway): DualViewModel {
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
        assertEquals("removed: com.example.chat", vm.state.value.msg)
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
    // second uninstall.
    @Test
    fun confirm_secondConfirmAfterSuccess_reportsNoPending() {
        val gw = FakeRepoGateway(dualId = 95)
        val vm = makeVm(gw)
        awaitReady(vm)

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.pendingRemove == null && !vm.state.value.loading }
        assertEquals("first confirm must run one uninstall", 1, gw.uninstallCalls)

        vm.confirmRemove()
        awaitVm(vm) { vm.state.value.msg.contains("沒有待確認") }

        assertEquals("second confirm must not run another uninstall", 1, gw.uninstallCalls)
        assertNull(vm.state.value.pendingRemove)
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
}
