// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import io.github.libxposed.service.XposedService
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GalaxyAppTest {

    private fun createFakeXposedService(): XposedService {
        val ctor = XposedService::class.java.declaredConstructors[0].apply {
            isAccessible = true
        }
        val paramType = ctor.parameterTypes[0]
        // Returning null from a Proxy for a method with a primitive (or
        // String-typed) return triggers an NPE at the call site's unboxing /
        // cast, long before the test's assertion runs — so answer with a
        // benign default per return type instead. Reference (non-String)
        // returns stay null, matching prior fake behavior.
        val proxy = Proxy.newProxyInstance(
            paramType.classLoader,
            arrayOf(paramType),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> null
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Short.TYPE -> 0.toShort()
                    java.lang.Byte.TYPE -> 0.toByte()
                    java.lang.Character.TYPE -> '\u0000'
                    String::class.java -> ""
                    else -> null
                }
            }
        }
        return ctor.newInstance(proxy) as XposedService
    }

    private lateinit var app: GalaxyApp

    @Before
    fun setUp() {
        // PR #89 Phase C: GalaxyApp creates the production coordinator, but this
        // JVM test must keep the pre-Phase-C synchronous IO behavior so all
        // existing assertions remain valid. The override is global so both
        // test-created and GalaxyApp-created coordinators are covered.
        RepairHintIoOwner.dispatchOverrideForTests = { task -> task() }
        app = GalaxyApp()
        // Reset any existing static serviceRef
        val serviceRefField = GalaxyApp::class.java.getDeclaredField("serviceRef").apply {
            isAccessible = true
        }
        val ref = serviceRefField.get(null) as java.util.concurrent.atomic.AtomicReference<*>
        ref.set(null)
    }

    @After
    fun tearDown() {
        RepairHintIoOwner.dispatchOverrideForTests = null
        val serviceRefField = GalaxyApp::class.java.getDeclaredField("serviceRef").apply {
            isAccessible = true
        }
        val ref = serviceRefField.get(null) as java.util.concurrent.atomic.AtomicReference<*>
        ref.set(null)
    }

    @Test
    fun onServiceDied_atomicCas_doesNotClearNewerService() {
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        val listenerCalls = AtomicInteger(0)
        val listener: () -> Unit = { listenerCalls.incrementAndGet() }

        GalaxyApp.addServiceBindListener(listener)
        try {
            assertFalse(GalaxyApp.isServiceConnected())

            app.onServiceBind(service1)
            assertTrue(GalaxyApp.isServiceConnected())
            assertEquals(1, listenerCalls.get())

            // Rebind with service2 (new service arrives)
            app.onServiceBind(service2)
            assertTrue(GalaxyApp.isServiceConnected())
            assertEquals(2, listenerCalls.get())

            // Stale service1 death must not clear service2
            app.onServiceDied(service1)
            assertTrue("Stale service death must NOT clear active service2", GalaxyApp.isServiceConnected())
            assertEquals(2, listenerCalls.get()) // Death listener must NOT be called for stale service

            // Active service2 death clears service
            app.onServiceDied(service2)
            assertFalse(GalaxyApp.isServiceConnected())
            assertEquals(3, listenerCalls.get())
        } finally {
            GalaxyApp.removeServiceBindListener(listener)
        }
    }

    @Test
    fun remotePreferences_returnsNullWhenDisconnected() {
        assertFalse(GalaxyApp.isServiceConnected())
        val prefs = GalaxyApp.remotePreferences("test_group")
        org.junit.Assert.assertNull(prefs)
    }

    @Test
    fun policyTxCoordinator_failsClosedWhenPrefsCannotBeAcquired() {
        // Pure JVM: android.jar's getSharedPreferences() either throws ("Stub!")
        // or returns null, so this exercises the REAL GalaxyApp acquisition path.
        // The seam in RepairHintStore.kt must substitute the fail-closed store —
        // reverting GalaxyApp to a null store makes this test fail (no warning,
        // persisted durability reported as verified).
        val coord = app.policyTxCoordinator

        assertTrue("coordinator must warn when production storage is unavailable", coord.repairHint.value)
        assertTrue("mirror stays UNKNOWN-active", coord.repairHintPersistedState.active)
        assertFalse("a non-existent store must never be certified", coord.repairHintDurableForTests)

        coord.markRepairHint(repairedVersion = 1L)
        assertTrue("warning survives an unconfirmed retry", coord.repairHint.value)
        assertFalse("and remains uncertified", coord.repairHintDurableForTests)
    }
}
