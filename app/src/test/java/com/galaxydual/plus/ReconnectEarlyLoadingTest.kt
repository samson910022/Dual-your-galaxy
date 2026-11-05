// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase D P2 busy (issue #24): AUTO reconnect/recheck must report loading=true
// BEFORE the first suspending probe IO. swapAutoLocked() suspends on
// `su -c id -u` (auth prompt can block) while probeChannels() used to be the
// first place setting loading=true -- during that window state.loading stayed
// false and MainActivity left its buttons enabled. The fake below gates the
// first `id -u` so the test can assert loading while the probe is still
// blocked, then releases it and asserts clean completion.
//
// Harness mirrors ReconnectCoalesceTest: Main is Unconfined, repo I/O runs on
// Dispatchers.IO, tests poll with a timeout. MainActivity button-disable is
// driven by state.loading (busy proxy), so asserting state.loading covers the
// UI gate without an Activity test.
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReconnectEarlyLoadingTest {
    // Gated su fake: the first `id -u` blocks on `probeGate` when set, so the
    // AUTO swap probe stays in-flight while the test asserts loading. Later
    // `id -u` calls flow so the trailing work completes after release.
    private class EarlyProbeSu(var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        val idUCalls = AtomicInteger(0)
        val listPackagesCalls = AtomicInteger(0)

        @Volatile var probeGate: CompletableDeferred<Unit>? = null
        @Volatile var probeGate2: CompletableDeferred<Unit>? = null

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" -> {
                val n = idUCalls.incrementAndGet()
                if (n == 1) probeGate?.await() else if (n == 2) probeGate2?.await()
                CmdResult(exit = 0, out = "0\n", err = "")
            }
            cmd == DualRepository.CMD_LIST_USERS ->
                CmdResult(exit = 0, out = usersOut(), err = "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm list packages") -> {
                listPackagesCalls.incrementAndGet()
                CmdResult(exit = 0, out = "", err = "")
            }
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    private class StableShizuku(var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult = when {
            cmd == DualRepository.CMD_LIST_USERS ->
                CmdResult(exit = 0, out = usersOut(), err = "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm list packages") ->
                CmdResult(exit = 0, out = "", err = "")
            else -> CmdResult(exit = 0, out = "ok", err = "")
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
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun makeVm(suFake: EarlyProbeSu, shizukuFake: StableShizuku): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(suFake),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = suFake,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        return vm
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "timeout waiting for condition: state=${vms.lastOrNull()?.state?.value}",
                )
            }
            Thread.sleep(20)
        }
    }

    // AUTO reconnect must be busy while the first `id -u` is still gated:
    // without the Phase D early-loading set, state.loading stays false here
    // (init already completed) and the buttons stay enabled.
    @Test
    fun reconnect_is_busy_while_first_probe_blocked() {
        val suFake = EarlyProbeSu()
        val shizukuFake = StableShizuku()
        val vm = makeVm(suFake, shizukuFake)
        await { !vm.state.value.loading }

        suFake.idUCalls.set(0)
        suFake.probeGate = CompletableDeferred()

        vm.reconnectAndRefresh()
        // The swap probe has entered `id -u` and is now parked on the gate.
        await { suFake.idUCalls.get() >= 1 }
        // Give the Main-Confined loading update a chance to land (Unconfined
        // runs it synchronously before the IO suspend, so this is immediate).
        await { vm.state.value.loading }

        assertTrue(
            "AUTO reconnect must hold loading=true while the first id -u is gated",
            vm.state.value.loading,
        )

        suFake.probeGate?.complete(Unit)
        suFake.probeGate = null

        await(timeoutMs = 10_000L) { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
        assertTrue("reconnect must complete once the probe is released", vm.state.value.writeReady)
    }

    // Same early-busy contract for the binder/permission path: recheck must
    // also hold loading while its swap probe is gated.
    @Test
    fun recheck_is_busy_while_first_probe_blocked() {
        val suFake = EarlyProbeSu()
        val shizukuFake = StableShizuku()
        val vm = makeVm(suFake, shizukuFake)
        await { !vm.state.value.loading }

        suFake.idUCalls.set(0)
        suFake.probeGate = CompletableDeferred()

        vm.recheckPrivilege()
        await { suFake.idUCalls.get() >= 1 }
        await { vm.state.value.loading }

        assertTrue(
            "AUTO recheck must hold loading=true while the first id -u is gated",
            vm.state.value.loading,
        )

        suFake.probeGate?.complete(Unit)
        suFake.probeGate = null

        await(timeoutMs = 10_000L) { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
    }

    // Re-review #7 B2 (P2): the coalesced TRAILING recheck round must also
    // hold loading=true while its own `id -u` is blocked. Before the fix the
    // trailing loop inlined swapAutoLocked() without the early-busy set (and
    // the first round's probeChannels tail had already cleared loading), so
    // during the second probe state.loading read false and MainActivity
    // render() re-enabled its buttons (busy proxy: clone/remove/refresh +
    // channel picker all gate on s.loading). This test gates BOTH rounds'
    // `id -u`: parks round 1, queues round 2 (pending=true), releases round 1,
    // then asserts loading stays true for the whole second block.
    @Test
    fun recheck_trailing_round_holds_busy_while_second_probe_blocked() {
        val suFake = EarlyProbeSu()
        val shizukuFake = StableShizuku()
        val vm = makeVm(suFake, shizukuFake)
        await { !vm.state.value.loading }

        suFake.idUCalls.set(0)
        suFake.probeGate = CompletableDeferred()
        suFake.probeGate2 = CompletableDeferred()

        // Round 1 starts and parks on the first `id -u`.
        vm.recheckPrivilege()
        await { suFake.idUCalls.get() >= 1 }
        await { vm.state.value.loading }
        assertTrue(
            "first recheck round must hold loading=true while its id -u is gated",
            vm.state.value.loading,
        )

        // Second recheck while round 1 is still in flight: coalesced into a
        // single trailing re-run (no extra mutex waiter).
        vm.recheckPrivilege()

        // Release round 1; the trailing round must start and park on the
        // second `id -u`. The counter increment for round 2 happens-after the
        // trailing busy re-set (Main sets loading before dispatching the IO),
        // so observing idUCalls >= 2 implies the fix has already marked busy.
        suFake.probeGate?.complete(Unit)
        suFake.probeGate = null
        await { suFake.idUCalls.get() >= 2 }

        await { vm.state.value.loading }
        assertTrue(
            "trailing recheck round must hold loading=true while the second id -u is gated",
            vm.state.value.loading,
        )
        // Sustained-busy sampling: buttons stay disabled for the whole block,
        // not just at entry (catches a clear-then-re-set flash mid-block).
        val busyDeadline = System.currentTimeMillis() + 200
        while (System.currentTimeMillis() < busyDeadline) {
            assertTrue(
                "trailing recheck must keep loading=true for the whole second block, " +
                    "state=${vm.state.value}",
                vm.state.value.loading,
            )
            Thread.sleep(10)
        }
        assertEquals(
            "two coalesced recheck rounds must probe exactly twice (one id -u each)",
            2,
            suFake.idUCalls.get(),
        )

        suFake.probeGate2?.complete(Unit)
        suFake.probeGate2 = null

        await(timeoutMs = 10_000L) { !vm.state.value.loading }
        assertEquals(false, vm.state.value.loading)
    }
}
