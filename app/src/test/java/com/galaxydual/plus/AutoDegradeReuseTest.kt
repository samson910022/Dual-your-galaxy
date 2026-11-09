// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase C P1 (issue #34 review #11 P1): AUTO soft-degrade must not
// terminal-close a reusable Shizuku gateway. swapToResolvedLocked() SHIZUKU ->
// NOOP reused the same shizukuGateway instance but called old.close()
// (closed=true permanently); the next reconnect probed through the same
// closed instance (boundBinder throws) and pinned the VM in NOOP until a
// picker rebuild. FakeShizuku in older tests never modelled terminal-closed,
// so the bug was invisible.
//
// This file pins the fix at two levels:
// - Gateway level (real ShizukuCmdGateway + FakeBindDriver seam): soft
//   releaseBinding() drops the cache but stays reusable; close() is terminal.
// - VM state-sequence level (fakes modelling closed semantics via the
//   teardown observer as the poison signal): AUTO + suPresent=false +
//   shizukuAuthorized=true, initial probe fails -> NOOP with zero teardown,
//   then permission/binder recovers (no picker rebuild, same instance) ->
//   reconnectAndRefresh() returns to SHIZUKU ready; dispose still unbinds
//   exactly once.
package com.galaxydual.plus

import android.os.IBinder
import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoDegradeReuseTest {
    private class FakeSu(var rootOk: Boolean) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            else -> CmdResult(exit = 1, out = "", err = "denied: su refused")
        }
    }

    // SHELL fake modelling terminal-closed via the teardown observer: once
    // `terminated` is set (wired to gatewayTeardown below, the VM's terminal
    // signal for fakes), every run fails -- the analogue of a real
    // ShizukuCmdGateway whose closed=true makes boundBinder throw and whose
    // probe then fails. Soft reuse never sets this flag, so probes recover.
    private class FakeShizukuReuse(var shizukuOk: Boolean, var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        @Volatile var terminated: Boolean = false
        val installedPkgs = mutableSetOf<String>()

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

        override suspend fun run(cmd: String): CmdResult {
            if (terminated) return CmdResult(exit = 1, out = "", err = "shizuku: gateway closed")
            if (!shizukuOk) return CmdResult(exit = 1, out = "", err = "denied: not authorized")
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = usersOut(), err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = "$dualId\n", err = "")
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = 0, out = installedPkgs.map { "package:$it" }.joinToString("\n"), err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }
    }

    private class TeardownCounter(val poison: (CmdGateway) -> Unit = {}) {
        val counts = ConcurrentHashMap<Int, Int>()
        val fn: (CmdGateway) -> Unit = { gw ->
            counts.merge(System.identityHashCode(gw), 1, Int::plus)
            try {
                poison(gw)
            } catch (_: Exception) {
            }
        }
        fun countFor(gw: CmdGateway): Int = counts[System.identityHashCode(gw)] ?: 0
        fun total(): Int = counts.values.sum()
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

    private fun reuseFactory(
        suFake: FakeSu,
        shizukuFake: FakeShizukuReuse,
    ): (ChannelPreference, Boolean, Boolean, String) -> ChannelAssembly = { pref, suPresent, shizukuAuth, _ ->
        val channel = pref.resolveChannel(suPresent, shizukuAuth)
        val gateway: CmdGateway = when (channel) {
            ResolvedChannel.ROOT -> suFake
            ResolvedChannel.SHIZUKU -> shizukuFake
            else -> NoOpGateway()
        }
        ChannelAssembly(gateway, suFake, shizukuFake, channel)
    }

    // Main regression: AUTO SHIZUKU -> NOOP degrade must NOT terminal-close the
    // reused instance; recovery without a picker rebuild must return to
    // SHIZUKU ready. Old code called old.close() (+ teardown observer) on the
    // still-referenced shizukuGateway, poisoning it (terminated=true here,
    // closed=true on the real gateway) so the second probe still failed.
    @Test
    fun auto_shizukuNoopRecover_reusesSameGateway() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizukuReuse(shizukuOk = false)
        // Poison models terminal-close for fakes: the VM's teardown observer
        // is the only terminal signal FakeShizukuReuse observes (it has no
        // real close()). A buggy swap that tears down the reused instance
        // sets terminated and blocks recovery, exactly like closed=true on
        // the real gateway.
        val counter = TeardownCounter(poison = { gw ->
            if (gw === shizukuFake) shizukuFake.terminated = true
        })
        val vm = DualViewModel(
            repo = DualRepository(shizukuFake),
            gatewayPrivilege = shizukuFake.privilege,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = shizukuFake,
            currentChannel = ResolvedChannel.SHIZUKU,
            assembleChannelFn = reuseFactory(suFake, shizukuFake),
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowMs = System::currentTimeMillis,
            gatewayTeardown = counter.fn,
        )
        vms.add(vm)

        // Initial probe: su denied (id -u != 0), Shizuku denied -> NOOP.
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.NOOP }
        val degraded = vm.state.value
        assertEquals(ChannelPreference.AUTO, degraded.channel)
        assertEquals(ResolvedChannel.NOOP, degraded.resolvedGateway)
        assertFalse("NOOP must never be writable", degraded.writeReady)
        val degradedText = zhTestBlockText()(degraded.degrade!!)
        assertEquals(WriteBlockKind.AUTO_DEGRADE_NO_CHANNEL, degraded.degrade!!.kind)
        assertTrue("no-su copy expected, got: \"$degradedText\"", degradedText.contains("唯讀"))
        // Still-referenced reuse: no terminal teardown on the degrade itself.
        assertEquals(
            "SHIZUKU -> NOOP degrade must not terminal-close the reused gateway",
            0,
            counter.countFor(shizukuFake),
        )
        assertFalse("reused gateway must stay usable", shizukuFake.terminated)

        // Binder/permission recovers (same instance, no picker rebuild).
        shizukuFake.shizukuOk = true
        vm.reconnectAndRefresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU &&
                vm.state.value.writeReady
        }
        val recovered = vm.state.value
        assertEquals(ResolvedChannel.SHIZUKU, recovered.resolvedGateway)
        assertEquals(Privilege.SHELL, recovered.execIdentity)
        assertTrue("recovered SHIZUKU must be writable", recovered.writeReady)
        assertTrue("recovered SHIZUKU must prove a channel", recovered.anyProbePassed)
        assertTrue("refresh must list profiles, got: ${recovered.profiles}", recovered.profiles.any { it.userId == 95 })
        assertEquals(
            "recovery must not have torn down the reused gateway",
            0,
            counter.countFor(shizukuFake),
        )

        // VM clear still unbinds exactly once (terminal semantics kept).
        vm.dispose()
        assertEquals("cleared must unbind the Shizuku instance once", 1, counter.countFor(shizukuFake))
        vm.dispose()
        assertEquals("second dispose must stay silent", 1, counter.countFor(shizukuFake))
        assertEquals(1, counter.total())
    }

    // Gateway-level pin (real ShizukuCmdGateway + FakeBindDriver seam):
    // releaseBinding() is soft (reusable), close() is terminal. Also pins the
    // Phase B P2-2 exactly-once release (soft + rebind + terminal = 2 total,
    // no double on repeat close, closed probe throws).
    private fun fakeBinder(alive: Boolean = true): IBinder {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "isBinderAlive", "pingBinder" -> alive
                "getInterfaceDescriptor" -> "FakeBinder"
                "queryLocalInterface" -> null
                "transact" -> false
                "unlinkToDeath" -> true
                "linkToDeath", "dump", "dumpAsync" -> Unit
                "toString" -> "FakeBinder"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
            handler,
        ) as IBinder
    }

    private class FakeBindDriver : ShizukuBindDriver {
        class Sent(val onResult: (Result<IBinder>) -> Unit) {
            val releaseCount = AtomicInteger(0)
            val request = object : ShizukuBindDriver.Request {
                override fun release() {
                    releaseCount.incrementAndGet()
                }
            }
        }

        private val lock = Any()
        private val sent = mutableListOf<Sent>()
        val sendCount: Int get() = synchronized(lock) { sent.size }
        val totalReleases: Int get() = synchronized(lock) { sent.sumOf { it.releaseCount.get() } }

        override fun send(onResult: (Result<IBinder>) -> Unit): ShizukuBindDriver.Request {
            val s = Sent(onResult)
            synchronized(lock) { sent.add(s) }
            return s.request
        }

        fun completeLatestWith(binder: IBinder) {
            val s = synchronized(lock) { sent.last() }
            s.onResult(Result.success(binder))
        }
    }

    @Test
    fun gateway_softReleaseReusable_closeTerminal() = runBlocking {
        val driver = FakeBindDriver()
        val gw = ShizukuCmdGateway("com.example.test", bindTimeoutMs = 5_000L, bindDriver = driver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // First bind promotes to cache.
        val first = scope.async { gw.boundBinder() }
        while (driver.sendCount < 1) delay(10)
        driver.completeLatestWith(fakeBinder())
        val bound = first.await()
        assertTrue(bound!!.binder.isBinderAlive)
        assertTrue(gw.hasCachedBindingForTest())
        assertFalse(gw.isClosedForTest())
        assertEquals(0, driver.totalReleases)

        // Soft release: drops the cache, stays reusable.
        gw.releaseBinding()
        assertFalse("soft release must drop the cached binding", gw.hasCachedBindingForTest())
        assertFalse("soft release must NOT mark terminal-closed", gw.isClosedForTest())
        assertEquals(1, driver.totalReleases)

        // Same instance rebinds fine after soft release.
        val second = scope.async { gw.boundBinder() }
        while (driver.sendCount < 2) delay(10)
        driver.completeLatestWith(fakeBinder())
        assertTrue(second.await()!!.binder.isBinderAlive)
        assertTrue(gw.hasCachedBindingForTest())
        assertEquals("rebind must not touch the released request", 1, driver.totalReleases)

        // Terminal close: drops + poisons future binds.
        gw.close()
        assertFalse(gw.hasCachedBindingForTest())
        assertTrue(gw.isClosedForTest())
        assertEquals(2, driver.totalReleases)
        try {
            gw.boundBinder()
            fail("boundBinder after close must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("closed"))
        }
        assertEquals(2, driver.totalReleases)
        gw.close()
        assertEquals("second close must not double-unbind", 2, driver.totalReleases)
        scope.cancel()
    }

    // Truly displaced instances keep terminal semantics at the VM layer: an
    // explicit SHIZUKU (denied, not retained) -> ROOT swap with a fresh
    // factory must tear down the old idle Shizuku exactly once, and dispose
    // must not re-unbind it.
    @Test
    fun vm_displacedIdle_terminalTeardownOnce() {
        val suFake = FakeSu(rootOk = true)
        val oldShizuku = FakeShizukuReuse(shizukuOk = false)
        val newShizuku = FakeShizukuReuse(shizukuOk = false)
        val counter = TeardownCounter(poison = { gw ->
            if (gw === oldShizuku) oldShizuku.terminated = true
            if (gw === newShizuku) newShizuku.terminated = true
        })
        // Fresh-instance factory (production shape): every selectChannel
        // rebuilds; the old idle instance is fully displaced.
        var currentShizuku: FakeShizukuReuse = oldShizuku
        val freshFactory: (ChannelPreference, Boolean, Boolean, String) -> ChannelAssembly =
            { pref, suPresent, shizukuAuth, _ ->
                if (pref == ChannelPreference.ROOT) {
                    // ROOT keeps the fresh idle Shizuku slot alongside.
                    currentShizuku = newShizuku
                }
                val channel = pref.resolveChannel(suPresent, shizukuAuth)
                val gateway: CmdGateway = when (channel) {
                    ResolvedChannel.ROOT -> suFake
                    ResolvedChannel.SHIZUKU -> currentShizuku
                    else -> NoOpGateway()
                }
                ChannelAssembly(gateway, suFake, currentShizuku, channel)
            }
        val vm = DualViewModel(
            repo = DualRepository(oldShizuku),
            gatewayPrivilege = oldShizuku.privilege,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(oldShizuku)),
            preference = ChannelPreference.SHIZUKU,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = oldShizuku,
            activeGateway = oldShizuku,
            currentChannel = ResolvedChannel.SHIZUKU,
            assembleChannelFn = freshFactory,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowMs = System::currentTimeMillis,
            gatewayTeardown = counter.fn,
        )
        vms.add(vm)
        // Explicit SHIZUKU probe fails (denied) -> not ready, evidence false,
        // retained cleared -- the old instance is unprotected.
        await { !vm.state.value.loading && !vm.state.value.writeReady }
        assertEquals(0, counter.countFor(oldShizuku))

        vm.selectChannel(ChannelPreference.ROOT)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.ROOT &&
                vm.state.value.writeReady
        }
        assertEquals("truly displaced idle must be torn down once", 1, counter.countFor(oldShizuku))
        assertEquals("fresh idle must stay untouched", 0, counter.countFor(newShizuku))

        vm.dispose()
        // Dispose snapshots current slots (su + newShizuku): old stays at 1,
        // new idle gets its single cleared unbind.
        assertEquals(1, counter.countFor(oldShizuku))
        assertEquals("cleared must unbind the current idle once", 1, counter.countFor(newShizuku))
        vm.dispose()
        assertEquals(1, counter.countFor(oldShizuku))
        assertEquals(1, counter.countFor(newShizuku))
    }
}
