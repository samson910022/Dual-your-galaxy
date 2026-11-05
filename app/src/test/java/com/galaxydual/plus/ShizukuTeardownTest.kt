// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase N2 (issue #34 re-review): Shizuku binding teardown -- ViewModel
// cleared must unbind every Shizuku-like instance exactly once (identity
// dedup), swaps must not close the retained diagnostic instance early.
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShizukuTeardownTest {
    private class FakeSu(var rootOk: Boolean) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS ->
                if (rootOk) CmdResult(exit = 0, out = "Users:\n\tUserInfo{0:Owner:13} running\n", err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    private class FakeShizuku(var shizukuOk: Boolean) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        override suspend fun run(cmd: String): CmdResult = when {
            cmd == DualRepository.CMD_LIST_USERS ->
                if (shizukuOk) CmdResult(exit = 0, out = "Users:\n\tUserInfo{0:Owner:13} running\n", err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            else -> if (shizukuOk) CmdResult(exit = 0, out = "ok", err = "")
            else CmdResult(exit = 1, out = "", err = "denied")
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

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout: state=${vms.lastOrNull()?.state?.value}")
            }
            Thread.sleep(20)
        }
    }

    // Counts teardown invocations per gateway identity (System.identityHashCode).
    private class TeardownCounter {
        val counts = ConcurrentHashMap<Int, Int>()
        val instances = ConcurrentHashMap<Int, CmdGateway>()
        val fn: (CmdGateway) -> Unit = { gw ->
            val key = System.identityHashCode(gw)
            instances.putIfAbsent(key, gw)
            counts.merge(key, 1, Int::plus)
        }
        fun countFor(gw: CmdGateway): Int = counts[System.identityHashCode(gw)] ?: 0
        fun total(): Int = counts.values.sum()
    }

    private fun makeVm(
        suFake: FakeSu,
        shizukuFake: FakeShizuku,
        preference: ChannelPreference,
        initialChannel: ResolvedChannel,
        initialGateway: CmdGateway,
        counter: TeardownCounter,
        assembleFn: (
            ChannelPreference,
            Boolean,
            Boolean,
            String,
        ) -> ChannelAssembly,
    ): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(initialGateway),
            gatewayPrivilege = initialGateway.privilege,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = preference,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = initialGateway,
            currentChannel = initialChannel,
            assembleChannelFn = assembleFn,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowMs = System::currentTimeMillis,
            gatewayTeardown = counter.fn,
        )
        vms.add(vm)
        return vm
    }

    private fun reuseFactory(
        suFake: FakeSu,
        shizukuFake: FakeShizuku,
    ): (ChannelPreference, Boolean, Boolean, String) -> ChannelAssembly = { pref, suPresent, shizukuAuth, _ ->
        val channel = pref.resolveChannel(suPresent, shizukuAuth)
        val gateway: CmdGateway = when (channel) {
            ResolvedChannel.ROOT -> suFake
            ResolvedChannel.SHIZUKU -> shizukuFake
            else -> NoOpGateway()
        }
        ChannelAssembly(gateway, suFake, shizukuFake, channel)
    }

    // 1. SHIZUKU probe -> LSPOSED retain -> cleared -> unbind once, dispose
    // idempotent. Swap must not close the retained instance early (counter
    // stays 0 until dispose).
    @Test
    fun shizukuProbe_lsposedRetain_cleared_unbindOnce_idempotent() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val counter = TeardownCounter()
        val vm = makeVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            preference = ChannelPreference.SHIZUKU,
            initialChannel = ResolvedChannel.SHIZUKU,
            initialGateway = shizukuFake,
            counter = counter,
            assembleFn = reuseFactory(suFake, shizukuFake),
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }

        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        // Retained diagnostic instance must NOT be closed by the swap itself.
        assertEquals(
            "swap must not close the retained Shizuku instance early",
            0,
            counter.countFor(shizukuFake),
        )
        assertEquals("su (ROOT) is never a teardown target", 0, counter.countFor(suFake))

        vm.dispose()
        assertEquals("cleared must unbind the retained Shizuku once", 1, counter.countFor(shizukuFake))
        assertEquals("su (ROOT) stays untouched", 0, counter.countFor(suFake))

        // Idempotent: second dispose is a no-op (no duplicate unbind).
        vm.dispose()
        assertEquals("second dispose must not re-unbind", 1, counter.countFor(shizukuFake))
    }

    // 2. Repeated SHIZUKU <-> LSPOSED toggles with the same instances: swaps
    // close nothing (same identity + retained guard), cleared closes once.
    @Test
    fun repeatedSwitch_noDuplicateUnbind() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val counter = TeardownCounter()
        val vm = makeVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            preference = ChannelPreference.SHIZUKU,
            initialChannel = ResolvedChannel.SHIZUKU,
            initialGateway = shizukuFake,
            counter = counter,
            assembleFn = reuseFactory(suFake, shizukuFake),
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }

        repeat(3) {
            vm.selectChannel(ChannelPreference.LSPOSED)
            await { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.LSPOSED }
            Thread.sleep(150)
            await { !vm.state.value.loading }
            vm.selectChannel(ChannelPreference.SHIZUKU)
            await { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.SHIZUKU }
            Thread.sleep(150)
            await { !vm.state.value.loading }
        }
        // Same instances reused: no displaced close across all toggles.
        assertEquals(
            "repeated toggles with same instances must not unbind",
            0,
            counter.countFor(shizukuFake),
        )

        vm.selectChannel(ChannelPreference.LSPOSED)
        await { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.LSPOSED }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(0, counter.countFor(shizukuFake))

        vm.dispose()
        assertEquals("cleared closes once after repeated toggles", 1, counter.countFor(shizukuFake))
        assertEquals(1, counter.total())
        vm.dispose()
        assertEquals(1, counter.total())
    }

    // 3. Current + retained are the same instance (SHIZUKU still selected):
    // snapshot dedups by identity, so cleared unbinds once, not twice.
    @Test
    fun currentAndRetainedDedup_singleUnbind() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val counter = TeardownCounter()
        val vm = makeVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            preference = ChannelPreference.SHIZUKU,
            initialChannel = ResolvedChannel.SHIZUKU,
            initialGateway = shizukuFake,
            counter = counter,
            assembleFn = reuseFactory(suFake, shizukuFake),
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        // Precondition: active/shizukuGateway/retained all point at the same
        // SHELL instance (explicit SHIZUKU probe pins retained). Teardown must
        // count it once via identity dedup.
        vm.dispose()
        assertEquals(
            "active === shizukuGateway === retained must unbind once",
            1,
            counter.countFor(shizukuFake),
        )
        assertEquals("exactly one teardown call total", 1, counter.total())
        assertEquals("su never a target", 0, counter.countFor(suFake))
        assertTrue("only the Shizuku instance was torn down", counter.instances.size == 1)
    }
}
