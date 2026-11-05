// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// P25 Item 2 (issue #25): reconnect shares the refresh coalescing gate.
// Binder/button bursts must collapse to in-flight + at most one trailing
// round instead of queueing a mutex waiter per tap (#24 bound). The gate is
// a controllable-delay fake: the first `pm list packages` (refresh tail)
// blocks on a test-controlled gate while the burst fires, so without
// coalescing each tap would run its own round; with coalescing the burst
// collapses to exactly 2 rounds. A refresh arriving during a reconnect (and
// vice versa) upgrades/collapses to a single trailing run.
//
// Harness mirrors AutoDegradeTest: Main is Unconfined, repo I/O runs on
// Dispatchers.IO, tests poll with a timeout. No device, no Android runtime.
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
class ReconnectCoalesceTest {
    // Gated su fake: rootOk=true proves ROOT via `id -u -> 0`. Counts probe
    // (`id -u`) vs refresh-tail (`pm list packages`) entries separately. The
    // first refresh-tail entry blocks on `gate` when set, so the test can
    // hold the leading op in-flight while the burst fires.
    private class GatedSu(var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        val idUCalls = AtomicInteger(0)
        val listUsersCalls = AtomicInteger(0)
        val listPackagesCalls = AtomicInteger(0)

        @Volatile var gate: CompletableDeferred<Unit>? = null

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
                idUCalls.incrementAndGet()
                CmdResult(exit = 0, out = "0\n", err = "")
            }
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls.incrementAndGet()
                CmdResult(exit = 0, out = usersOut(), err = "")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm list packages") -> {
                val n = listPackagesCalls.incrementAndGet()
                // Only the leading round blocks: trailing re-runs must flow
                // so the drain can complete after the test releases the gate.
                if (n == 1) gate?.await()
                CmdResult(exit = 0, out = "", err = "")
            }
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    // Gated Shizuku fake: proves SHELL via `pm list users`. Counts its own
    // probe entries; the repo under test stays on the su fake so refresh-tail
    // counts live in one place.
    private class GatedShizuku(var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val listUsersCalls = AtomicInteger(0)

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
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls.incrementAndGet()
                CmdResult(exit = 0, out = usersOut(), err = "")
            }
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

    private fun makeStableRootVm(suFake: GatedSu, shizukuFake: GatedShizuku): DualViewModel {
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

    // Burst of reconnects collapses to in-flight + one trailing round: the
    // gated leading refresh-tail holds the mutex while 4 more reconnects
    // fire, so an uncoalesced implementation would run 5 refresh tails.
    @Test
    fun burst_reconnect_collapses_to_two_rounds() {
        val suFake = GatedSu()
        val shizukuFake = GatedShizuku()
        val vm = makeStableRootVm(suFake, shizukuFake)
        await { !vm.state.value.loading && vm.state.value.writeReady }

        suFake.listPackagesCalls.set(0)
        suFake.idUCalls.set(0)
        suFake.gate = CompletableDeferred()

        vm.reconnectAndRefresh()
        await { suFake.listPackagesCalls.get() >= 1 }
        repeat(4) { vm.reconnectAndRefresh() }
        // Main is Unconfined: the burst flags are set synchronously above,
        // so releasing now deterministically covers all 4 behind the leader.
        suFake.gate?.complete(Unit)
        suFake.gate = null

        await { !vm.state.value.loading && suFake.listPackagesCalls.get() >= 2 }
        // Settle: a stray extra round would start here and flip loading back
        // on; waiting truly quiescent keeps the bound honest.
        Thread.sleep(500)
        await { !vm.state.value.loading }

        val rounds = suFake.listPackagesCalls.get()
        assertEquals("reconnect burst must collapse to leader + one trailing", 2, rounds)
        // Each reconnect round probes su twice (swapAuto + probeChannels);
        // 2 rounds bound the probe side too (no per-tap waiter).
        val probes = suFake.idUCalls.get()
        assertTrue("probe must be bounded too (<=4), got $probes", probes <= 4)
    }

    // Burst of pure refreshes coalesces without ever probing: refresh
    // semantics stay probe-free even on the trailing run.
    @Test
    fun burst_refresh_collapses_without_probe() {
        val suFake = GatedSu()
        val shizukuFake = GatedShizuku()
        val vm = makeStableRootVm(suFake, shizukuFake)
        await { !vm.state.value.loading && vm.state.value.writeReady }

        suFake.listPackagesCalls.set(0)
        suFake.idUCalls.set(0)
        suFake.gate = CompletableDeferred()

        vm.refresh()
        await { suFake.listPackagesCalls.get() >= 1 }
        repeat(4) { vm.refresh() }
        suFake.gate?.complete(Unit)
        suFake.gate = null

        await { !vm.state.value.loading && suFake.listPackagesCalls.get() >= 2 }
        Thread.sleep(500)
        await { !vm.state.value.loading }

        assertEquals("refresh burst must collapse to leader + one trailing", 2, suFake.listPackagesCalls.get())
        assertEquals("pure refresh must never probe", 0, suFake.idUCalls.get())
    }

    // A reconnect arriving during a refresh upgrades the trailing run to
    // probe+refresh: without the upgrade the degrade would be skipped.
    @Test
    fun burst_refresh_then_reconnect_upgrades_trailing() {
        val suFake = GatedSu()
        val shizukuFake = GatedShizuku()
        val vm = makeStableRootVm(suFake, shizukuFake)
        await { !vm.state.value.loading && vm.state.value.writeReady }

        suFake.listPackagesCalls.set(0)
        suFake.idUCalls.set(0)
        suFake.gate = CompletableDeferred()

        vm.refresh()
        await { suFake.listPackagesCalls.get() >= 1 }
        vm.reconnectAndRefresh()
        // An extra refresh behind the reconnect must fold into the same
        // trailing reconnect (reconnect covers refresh).
        vm.refresh()
        suFake.gate?.complete(Unit)
        suFake.gate = null

        await { !vm.state.value.loading && suFake.listPackagesCalls.get() >= 2 }
        Thread.sleep(500)
        await { !vm.state.value.loading }

        assertEquals("refresh+reconnect burst must collapse to 2 rounds", 2, suFake.listPackagesCalls.get())
        assertTrue(
            "trailing must have been upgraded to reconnect (probe ran), got ${suFake.idUCalls.get()}",
            suFake.idUCalls.get() >= 2,
        )
    }
}
