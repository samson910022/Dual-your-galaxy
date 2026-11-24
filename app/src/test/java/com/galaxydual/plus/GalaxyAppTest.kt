// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import io.github.libxposed.service.XposedService
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /** A dispatcher that queues bind-time work so the test controls WHEN it runs. */
    private class GateDispatcher : CoroutineDispatcher() {
        val queued = mutableListOf<Runnable>()
        var accept = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (accept) queued.add(block)
        }

        fun drainQueued() {
            val tasks = queued.toList()
            queued.clear()
            tasks.forEach { it.run() }
        }
    }

    private fun generationCounter(): AtomicLong {
        val field = GalaxyApp::class.java.getDeclaredField("bbcConsentServiceGeneration")
        field.isAccessible = true
        return field.get(app) as AtomicLong
    }

    private fun classificationLatch(): AtomicBoolean {
        val field = (GalaxyApp::class.java.declaredFields + GalaxyApp.Companion::class.java.declaredFields)
            .first { it.name == "installClassificationOnce" }
        field.isAccessible = true
        return if (Modifier.isStatic(field.modifiers)) {
            field.get(null) as AtomicBoolean
        } else {
            field.get(GalaxyApp.Companion) as AtomicBoolean
        }
    }

    @Test
    fun delayedBindClassification_failureFencesCurrentGenerationAfterRebind() {
        // Hold the first bind's classification attempt until AFTER a rebind.
        // Generation is now sampled inside the IO coroutine, so the late
        // failure must be attributed to the CURRENT generation (2), not to
        // the generation captured on Main by the dead bind (1).
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        val latch = classificationLatch()
        latch.set(false)
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        try {
            app.onServiceBind(service1)
            val counter = generationCounter()
            assertEquals(1L, counter.get())
            assertEquals(1L, app.bbcActivationConsentHolder.currentGeneration())

            // Rebind while attempt #1 is still queued. Drop attempt #2's
            // dispatch so only the stale attempt runs below.
            dispatcher.accept = false
            app.onServiceBind(service2)
            assertEquals(2L, counter.get())
            assertEquals(2L, app.bbcActivationConsentHolder.currentGeneration())
            assertTrue("the first bind's classification attempt must be queued",
                dispatcher.queued.isNotEmpty())

            dispatcher.drainQueued()

            val holder = app.bbcActivationConsentHolder
            assertEquals("a late failure must fence the CURRENT generation",
                BbcActivationConsentState.Unknown, holder.currentState())
            // Even a same-generation read of a polluted local mMap (PRE_UPGRADE
            // + durable marker) cannot clear it or authorize Legacy.
            holder.resolve(
                BbcConsentAuthoritativeRead(
                    serviceGeneration = 2L,
                    optInGranted = false,
                    classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    migrated = true,
                    legacyRuntimeEnabled = true,
                ),
            )
            assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        } finally {
            scope.cancel()
            scopeField.set(app, previousScope)
            latch.set(false)
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }
}
