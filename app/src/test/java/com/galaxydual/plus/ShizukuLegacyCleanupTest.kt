// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase D1 (P1 legacy fixed-tag migration cleanup): JVM unit tests for the
// one-time legacy record removal. The run() call site (after ping/permission,
// before the v13 gate, before the first per-UID v6 bind) is
// device-only: Shizuku statics and Parcels throw "Stub!" on the JVM, so
// these tests drive ensureLegacyCleanupDone() directly through a fake
// ShizukuLegacyCleaner. The production RealShizukuLegacyCleaner is never
// constructed on the JVM (Stub!).
package com.galaxydual.plus

import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ShizukuLegacyCleanupTest {
    private class FakeLegacyCleaner(var delayMs: Long = 0L) : ShizukuLegacyCleaner {
        val calls = AtomicInteger(0)

        @Volatile
        var toThrow: Throwable? = null

        override fun removeLegacy() {
            calls.incrementAndGet()
            if (delayMs > 0L) Thread.sleep(delayMs)
            toThrow?.let { throw it }
        }
    }

    private fun makeGateway(fake: FakeLegacyCleaner): ShizukuCmdGateway =
        ShizukuCmdGateway("com.example.test", legacyCleaner = fake)

    // Phase B P2-2 fakes, copied locally (from ShizukuTeardownTest) so these
    // run()-level tests drive bind without touching Shizuku statics or
    // Android ComponentName (JVM stubs throw "Stub!").
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

        fun failLatestWith(e: Throwable) {
            val s = synchronized(lock) { sent.last() }
            s.onResult(Result.failure(e))
        }
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout")
            Thread.sleep(20)
        }
    }

    private fun makeRunGateway(
        fake: FakeLegacyCleaner,
        driver: FakeBindDriver,
        ping: Boolean = true,
        perm: Boolean = true,
        version: Int = 13,
    ): ShizukuCmdGateway =
        ShizukuCmdGateway(
            "com.example.test",
            bindDriver = driver,
            legacyCleaner = fake,
            preflightPing = { ping },
            preflightPermission = { perm },
            preflightVersion = { version },
        )

    @Test fun successFirstTry_marksComplete_singleCall() = runBlocking {
        val fake = FakeLegacyCleaner()
        val gw = makeGateway(fake)
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertTrue(gw.ensureLegacyCleanupDone().completed)
        assertEquals(1, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
        assertTrue(gw.ensureLegacyCleanupDone().completed)
        assertEquals(1, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
    }

    @Test fun failureRetries_notMarked() = runBlocking {
        val fake = FakeLegacyCleaner()
        fake.toThrow = RuntimeException("boom")
        val gw = makeGateway(fake)
        // Phase E1 fail-closed: failure is not-completed, flag stays incomplete.
        // Phase F2: failure detail is per-call (no shared volatile race).
        val first = gw.ensureLegacyCleanupDone()
        assertFalse(first.completed)
        assertEquals("boom", first.failureDetail)
        assertEquals(1, fake.calls.get())
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        // The gate returns a definite legacyCleanupFailed refusal (it
        // retries once more, so this gate call is the 2nd attempt).
        val gateFail = gw.checkLegacyCleanupGate()
        assertNotNull(gateFail)
        assertNotEquals(0, gateFail!!.exit)
        assertTrue(gateFail.err.contains("legacy cleanup failed"))
        assertTrue(gateFail.err.contains("boom"))
        assertFalse(gateFail.timedOut)
        assertFalse(gateFail.transportUnknown)
        assertEquals(2, fake.calls.get())
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        // Next call retries and succeeds (gate is null once cleanup is done).
        fake.toThrow = null
        assertNull(gw.checkLegacyCleanupGate())
        assertEquals(3, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
        // Third call skips (already complete).
        assertTrue(gw.ensureLegacyCleanupDone().completed)
        assertEquals(3, fake.calls.get())
    }

    @Test fun failureDetail_perCall_noSharedRace() = runBlocking {
        // Phase F2: each failed call's detail is independent; a concurrent
        // success must not rewrite a sibling failure's detail to "unknown".
        val fake = FakeLegacyCleaner()
        val gw = makeGateway(fake)
        fake.toThrow = RuntimeException("first-boom")
        val first = gw.ensureLegacyCleanupDone()
        assertFalse(first.completed)
        assertTrue(first.failureDetail!!.contains("first-boom"))
        fake.toThrow = null
        val second = gw.ensureLegacyCleanupDone()
        assertTrue(second.completed)
        // The first outcome is immutable and still carries its own detail.
        assertFalse(first.completed)
        assertTrue(first.failureDetail!!.contains("first-boom"))
    }

    @Test fun cancellationPropagates_flagStaysIncomplete() = runBlocking {
        val fake = FakeLegacyCleaner()
        fake.toThrow = CancellationException("cancel")
        val gw = makeGateway(fake)
        try {
            gw.ensureLegacyCleanupDone()
            fail("CancellationException must propagate, not be swallowed")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertEquals(1, fake.calls.get())
    }

    @Test fun concurrentFirstCalls_singleFlight() = runBlocking {
        val fake = FakeLegacyCleaner(delayMs = 50L)
        val gw = makeGateway(fake)
        val deferred = List(8) { async(Dispatchers.Default) { gw.ensureLegacyCleanupDone() } }
        val results = deferred.map { it.await() }
        assertEquals(1, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
        assertTrue(results.all { it.completed })
    }

    @Test fun userServiceMinimum_pinnedTo10() {
        // Phase F1: cleanup runs only when version >= MIN_USERSERVICE_VERSION
        // (UserService API exists from v10); <10 goes straight to unsupported
        // without attempting cleanup.
        assertEquals(10, ShizukuExecProtocol.MIN_USERSERVICE_VERSION)
        assertEquals(13, ShizukuExecProtocol.MIN_SHIZUKU_VERSION)
    }

    @Test fun run_preV13_cleanupCalledThenUnsupportedVersion_noBind() = runBlocking {
        val fake = FakeLegacyCleaner()
        val driver = FakeBindDriver()
        val gw = makeRunGateway(fake, driver, ping = true, perm = true, version = 12)
        val res = gw.run("pm list users")
        assertNotEquals(0, res.exit)
        assertTrue("must contain 'unsupported', got: ${res.err}", res.err.contains("unsupported"))
        assertTrue("must contain '12', got: ${res.err}", res.err.contains("12"))
        assertTrue("must contain 'v13', got: ${res.err}", res.err.contains("v13"))
        assertEquals(1, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
        assertEquals(0, driver.sendCount)
    }

    @Test fun run_preUserServiceMinimum_unsupportedWithoutCleanup_noBind() = runBlocking {
        // Phase F1: version < MIN_USERSERVICE_VERSION (10) has no UserService
        // API, so no legacy record can exist; skip cleanup and go straight to
        // unsupportedVersion (no meaningless cleanup attempt, no bind).
        for (v in listOf(9, 0, -1)) {
            val fake = FakeLegacyCleaner()
            val driver = FakeBindDriver()
            val gw = makeRunGateway(fake, driver, ping = true, perm = true, version = v)
            val res = gw.run("pm list users")
            assertNotEquals(0, res.exit)
            assertTrue("must contain 'unsupported', got: ${res.err}", res.err.contains("unsupported"))
            assertTrue("must echo version $v, got: ${res.err}", res.err.contains("$v"))
            assertFalse("must not be cleanup failure, got: ${res.err}", res.err.contains("legacy cleanup failed"))
            assertEquals(0, fake.calls.get())
            assertFalse(gw.isLegacyCleanupCompleteForTest())
            assertEquals(0, driver.sendCount)
        }
    }

    @Test fun run_cleanupFailure_failClosed_noBind_definiteFailure() = runBlocking {
        val fake = FakeLegacyCleaner()
        fake.toThrow = RuntimeException("cleanup boom")
        val driver = FakeBindDriver()
        val gw = makeRunGateway(fake, driver, ping = true, perm = true, version = 13)
        val res = gw.run("pm list users")
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertEquals(0, driver.sendCount)
        assertNotEquals(0, res.exit)
        assertFalse("definite failure must not be timedOut, got: $res", res.timedOut)
        assertFalse("definite failure must not be transportUnknown, got: $res", res.transportUnknown)
        assertFalse(res.err.contains("may have executed"))
        for (prefix in listOf("service exec failed: ", "interrupted: ", "timeout after ", "shizuku: exec timed out after ", "may have executed")) {
            assertFalse(
                "legacyCleanupFailed err must not start with backstop prefix '$prefix', got: ${res.err}",
                res.err.startsWith(prefix),
            )
        }
        assertTrue(res.err.contains("legacy cleanup failed"))
        assertTrue(res.err.contains("cleanup boom"))
    }

    @Test fun run_cleanupFailureThenSuccess_retriesAndBinds() = runBlocking {
        val fake = FakeLegacyCleaner()
        fake.toThrow = RuntimeException("first boom")
        val driver = FakeBindDriver()
        val gw = makeRunGateway(fake, driver, ping = true, perm = true, version = 13)
        val res1 = gw.run("pm list users")
        assertEquals(0, driver.sendCount)
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertTrue(res1.err.contains("legacy cleanup failed"))
        fake.toThrow = null
        // Second run retries cleanup (succeeds) and then binds. The transact
        // below hits the JVM Parcel Stub! artifact as transportUnknown --
        // we assert the bind attempt (sendCount + cleaner calls + flag),
        // never res2.exit == 0.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deferred = scope.async { gw.run("pm list users") }
            await { driver.sendCount == 1 }
            driver.completeLatestWith(fakeBinder())
            deferred.await()
        } finally {
            scope.cancel()
        }
        assertEquals(1, driver.sendCount)
        assertEquals(2, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
    }

    @Test fun run_noBinder_cleanupNotCalled() = runBlocking {
        val fake = FakeLegacyCleaner()
        val driver = FakeBindDriver()
        val gw = makeRunGateway(fake, driver, ping = false, perm = true, version = 13)
        val res = gw.run("pm list users")
        assertTrue(res.err.contains("binder not connected"))
        assertEquals(0, fake.calls.get())
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertEquals(0, driver.sendCount)
    }

    @Test fun run_notAuthorized_cleanupNotCalled() = runBlocking {
        val fake = FakeLegacyCleaner()
        val driver = FakeBindDriver()
        val gw = makeRunGateway(fake, driver, ping = true, perm = false, version = 13)
        val res = gw.run("pm list users")
        assertTrue(res.err.contains("not authorized"))
        assertEquals(0, fake.calls.get())
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertEquals(0, driver.sendCount)
    }

    @Test fun run_cleanupCancellation_propagates_noBind() = runBlocking {
        val fake = FakeLegacyCleaner()
        fake.toThrow = CancellationException("cancel cleanup")
        val driver = FakeBindDriver()
        val gw = makeRunGateway(fake, driver, ping = true, perm = true, version = 13)
        try {
            gw.run("pm list users")
            fail("CancellationException must propagate, not be swallowed")
        } catch (e: CancellationException) {
            assertEquals("cancel cleanup", e.message)
        }
        assertEquals(0, driver.sendCount)
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertEquals(1, fake.calls.get())
    }
}
