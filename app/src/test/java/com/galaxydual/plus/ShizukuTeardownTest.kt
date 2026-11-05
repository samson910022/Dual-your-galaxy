// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase N2 (issue #34 re-review): Shizuku binding teardown -- ViewModel
// cleared must unbind every Shizuku-like instance exactly once (identity
// dedup), swaps must not close the retained diagnostic instance early.
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

    // Phase B P2-2 fakes: a JVM binder plus a manually-driven bind driver.
    // The driver never touches Shizuku statics or Android ComponentName -- it
    // only records sent requests (each with its own release counter) and
    // delivers the test's callback on demand, so contention (never-complete),
    // cancellation, timeout, callback failure, and late-arrival are all
    // deterministic.
    //
    // The binder is a java.lang.reflect Proxy (not a direct `: IBinder`
    // implementation): android.os.IBinder is an android.jar stub whose method
    // set is fixed, and a Proxy answers isBinderAlive without compile-time
    // override matching against the stub. Only isBinderAlive/pingBinder are
    // ever consulted (boundBinder liveness checks); everything else returns
    // inert defaults.
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
                // Counts EVERY call (not idempotent) so the test pins the
                // gateway to at most one release per sent request.
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

        fun failLatestWith(e: Throwable) {
            val s = synchronized(lock) { sent.last() }
            s.onResult(Result.failure(e))
        }
    }

    private fun makeGateway(driver: FakeBindDriver, timeoutMs: Long = 5_000L): ShizukuCmdGateway =
        ShizukuCmdGateway("com.example.test", bindTimeoutMs = timeoutMs, bindDriver = driver)

    // Phase B P2-2 (a): cancel-during-bind (no abandon) releases the sent
    // request exactly once, caches nothing, and leaves the gateway usable --
    // the next bind completes normally and close() releases it once.
    // NOTE: probe jobs run in an isolated CoroutineScope (not runBlocking's):
    // a FAILED child (ISE below in test (c)) would otherwise cancel the
    // runBlocking scope itself via structured concurrency and silently wedge
    // every later async in the test.
    @Test
    fun gateway_cancelDuringBind_releasesOnce_nextRunUsable() = runBlocking {
        val driver = FakeBindDriver()
        val gw = makeGateway(driver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.async { gw.boundBinder() }
        await { driver.sendCount == 1 }
        job.cancel()
        try {
            job.await()
            fail("cancelled bind must throw CancellationException")
        } catch (_: CancellationException) {
            // Expected: the waiter retreats at once; the request is released.
        }
        await { driver.totalReleases == 1 }
        assertEquals(1, driver.totalReleases)
        assertFalse("cancelled bind must cache nothing", gw.hasCachedBindingForTest())

        // Handover: a fresh bind on the same gateway completes and is cached;
        // the in-flight retry must not touch the already-released request.
        val next = scope.async { gw.boundBinder() }
        await { driver.sendCount == 2 }
        assertEquals("retry must not touch the old request", 1, driver.totalReleases)
        driver.completeLatestWith(fakeBinder())
        val binder = next.await()
        assertTrue(binder!!.isBinderAlive)
        assertEquals(1, driver.totalReleases)
        assertTrue(gw.hasCachedBindingForTest())

        // close() releases the promoted request once; repeat is a no-op.
        gw.close()
        assertEquals(2, driver.totalReleases)
        assertFalse(gw.hasCachedBindingForTest())
        gw.close()
        assertEquals("second close must not double-unbind", 2, driver.totalReleases)
        scope.cancel()
    }

    // Phase B P2-2 (b): a bind that never completes hits the bounded wait and
    // releases exactly once; the gateway stays usable afterwards.
    @Test
    fun gateway_bindTimeout_releasesOnce_nextRunUsable() = runBlocking {
        val driver = FakeBindDriver()
        val gw = makeGateway(driver, timeoutMs = 300L)
        try {
            gw.boundBinder()
            fail("timed-out bind must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("timed out"))
        }
        await { driver.totalReleases == 1 }
        assertEquals(1, driver.totalReleases)
        assertFalse("timed-out bind must cache nothing", gw.hasCachedBindingForTest())

        val next = async(Dispatchers.Default) { gw.boundBinder() }
        await { driver.sendCount == 2 }
        driver.completeLatestWith(fakeBinder())
        val binder = next.await()
        assertTrue(binder!!.isBinderAlive)
        assertEquals(1, driver.totalReleases)
        gw.close()
        assertEquals(2, driver.totalReleases)
    }

    // Phase B P2-2 (c): a failing callback (e.g. null service binder) releases
    // the sent request exactly once and caches nothing.
    @Test
    fun gateway_callbackFailure_releasesOnce_nextRunUsable() = runBlocking {
        val driver = FakeBindDriver()
        val gw = makeGateway(driver)
        // Isolated scope: the failing child must not cancel runBlocking
        // (structured concurrency would wedge the later async below).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.async { gw.boundBinder() }
        await { driver.sendCount == 1 }
        driver.failLatestWith(IllegalStateException("null service binder"))
        try {
            job.await()
            fail("failed bind must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("null service binder"))
        }
        await { driver.totalReleases == 1 }
        assertEquals(1, driver.totalReleases)
        assertFalse(gw.hasCachedBindingForTest())

        val next = scope.async { gw.boundBinder() }
        await { driver.sendCount == 2 }
        driver.completeLatestWith(fakeBinder())
        assertTrue(next.await()!!.isBinderAlive)
        assertEquals(1, driver.totalReleases)
        gw.close()
        assertEquals(2, driver.totalReleases)
        scope.cancel()
    }

    // Phase B P2-2 (d): contended abandon (in-flight bind holds the mutex)
    // marks closed and defers; cancelling the in-flight attempt releases its
    // request, a late callback is ignored, and repeats never double-unbind.
    @Test
    fun gateway_abandonContended_deferredUnbindExactlyOnce_lateCallbackIgnored() = runBlocking {
        val driver = FakeBindDriver()
        val gw = makeGateway(driver)
        // Isolated scope (see test (c) note): cancellation here is CE-only,
        // but the same shape keeps every probe independent of runBlocking.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.async { gw.boundBinder() }
        await { driver.sendCount == 1 }
        gw.abandon() // Contended: the in-flight bind holds bindMutex.
        job.cancel()
        try {
            job.await()
            fail("cancelled bind must throw CancellationException")
        } catch (_: CancellationException) {
        }
        // Late arrival after the release: must not unbind again.
        driver.completeLatestWith(fakeBinder())
        await { driver.totalReleases == 1 }
        // Let the deferred drop run to prove it is a no-op, not a 2nd unbind.
        delay(300)
        assertEquals("late callback must not double-unbind", 1, driver.totalReleases)
        assertFalse(gw.hasCachedBindingForTest())
        gw.abandon()
        gw.close()
        delay(300)
        assertEquals("repeat abandon/close must not re-unbind", 1, driver.totalReleases)
        scope.cancel()
    }

    // Phase B P2-2 (e): the production contention shape -- an in-flight bind
    // holds the gateway lock, ViewModel.dispose() runs (abandon contends and
    // defers), the bind is cancelled, and the callback arrives late. Exactly
    // one unbind, no residual binding, and a second dispose stays silent.
    @Test
    fun vm_clearedDuringBind_unbindOnce_noDouble() {
        val suFake = FakeSu(rootOk = false)
        val probeFake = FakeShizuku(shizukuOk = true)
        val counter = TeardownCounter()
        val driver = FakeBindDriver()
        val realGw = makeGateway(driver)
        val assembleReal: (ChannelPreference, Boolean, Boolean, String) -> ChannelAssembly =
            { pref, suPresent, shizukuAuth, _ ->
                val channel = pref.resolveChannel(suPresent, shizukuAuth)
                val gateway: CmdGateway = when (channel) {
                    ResolvedChannel.ROOT -> suFake
                    ResolvedChannel.SHIZUKU -> realGw
                    else -> NoOpGateway()
                }
                ChannelAssembly(gateway, suFake, realGw, channel)
            }
        val vm = DualViewModel(
            repo = DualRepository(realGw),
            gatewayPrivilege = realGw.privilege,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(probeFake)),
            preference = ChannelPreference.SHIZUKU,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = realGw,
            activeGateway = realGw,
            currentChannel = ResolvedChannel.SHIZUKU,
            assembleChannelFn = assembleReal,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowMs = System::currentTimeMillis,
            gatewayTeardown = counter.fn,
        )
        vms.add(vm)
        await { !vm.state.value.loading && vm.state.value.writeReady }

        // Hold the gateway lock with an in-flight bind, then clear.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.async { realGw.boundBinder() }
        await { driver.sendCount == 1 }
        vm.dispose() // abandon() contends -> deferred; observer fires now.
        assertEquals("dispose must notify the teardown observer once", 1, counter.countFor(realGw))
        job.cancel()
        await { job.isCompleted }
        // Late arrival after cancel + release: must not unbind again.
        driver.completeLatestWith(fakeBinder())
        await { driver.totalReleases == 1 }
        Thread.sleep(300)
        assertEquals("late callback must not double-unbind", 1, driver.totalReleases)
        assertEquals(1, counter.countFor(realGw))
        assertEquals(1, counter.total())
        vm.dispose()
        Thread.sleep(200)
        assertEquals("second dispose must not re-unbind", 1, driver.totalReleases)
        assertEquals("second dispose must stay silent", 1, counter.total())
        scope.cancel()
    }
}
