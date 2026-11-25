// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.hook.BbcPreactivationHookReader
import com.galaxydual.hook.PolicySource
import io.github.libxposed.service.XposedService
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.reflect.Proxy
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

    /**
     * A dispatcher that queues bind-time work so the test controls WHEN it runs.
     * Thread-safe because the stale/new bind attempts must be drained on
     * different threads to reproduce the real rebind race deterministically.
     */
    private class GateDispatcher : CoroutineDispatcher() {
        val queued = Collections.synchronizedList(mutableListOf<Runnable>())
        var accept = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (accept) queued.add(block)
        }

        fun drainQueued() {
            val tasks = synchronized(queued) {
                val copy = queued.toList()
                queued.clear()
                copy
            }
            tasks.forEach { it.run() }
        }

        fun hasQueued(): Boolean = synchronized(queued) { queued.isNotEmpty() }
    }

    /** In-memory SharedPreferences for the bind-time classification seam. */
    private class MemPrefs(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        val data = LinkedHashMap<String, Any>(initial)
        /** false models a failed daemon commit. */
        var commitSucceeds = true
        /** A failed bind commit still pollutes THIS instance's optimistic mMap. */
        var flushOnFailedCommit = false
        /** Test-only hook at commit entry; lets a rebind land mid-commit. */
        var beforeCommit: (() -> Unit)? = null

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<*>)?.mapNotNull { it as? String }?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any>()
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null && value != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor { pending.clear(); return this }
            override fun commit(): Boolean {
                beforeCommit?.invoke()
                if (!commitSucceeds) {
                    if (flushOnFailedCommit) data.putAll(pending)
                    return false
                }
                data.putAll(pending)
                return true
            }
            override fun apply() { data.putAll(pending) }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private fun generationCounter(): AtomicLong {
        val field = GalaxyApp::class.java.getDeclaredField("bbcConsentServiceGeneration")
        field.isAccessible = true
        return field.get(app) as AtomicLong
    }

    @Test
    fun delayedBindClassification_failureFencesSameGeneration() {
        // A SAME-INSTANCE bind-time failure must still install the durable
        // Unknown fence. (A failure from an attempt whose bind snapshot was
        // superseded by a rebind is covered separately by
        // staleBindAttemptCannotFenceNewGeneration and must NOT fence.)
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val service1 = createFakeXposedService()
        try {
            app.onServiceBind(service1)
            val counter = generationCounter()
            assertEquals(1L, counter.get())
            assertEquals(1L, app.bbcActivationConsentHolder.currentGeneration())
            assertTrue("the first bind's classification attempt must be queued",
                dispatcher.hasQueued())

            // No rebind: the queued failure belongs to the current generation.
            dispatcher.drainQueued()

            val holder = app.bbcActivationConsentHolder
            assertEquals("a same-generation failure must fence that generation",
                BbcActivationConsentState.Unknown, holder.currentState())
            // Even a same-generation read of a polluted local mMap (PRE_UPGRADE
            // + durable marker) cannot clear it or authorize Legacy.
            holder.resolve(
                BbcConsentAuthoritativeRead(
                    serviceGeneration = 1L,
                    optIn = BbcOptInValue.ABSENT,
                    classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    migrated = true,
                    legacyRuntimeEnabled = true,
                ),
            )
            assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        } finally {
            scope.cancel()
            scopeField.set(app, previousScope)
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * PR #90 P1-1: a rebind WITHOUT a death callback used to be short-circuited
     * by a one-shot classification flag, so the old else branch only refreshed
     * from durable keys. The generation bump had reset
     * classificationConfirmedGeneration, so a legacy install stayed
     * Initializing (App demanding consent while the Hook gate was open).
     * Confirmation must be re-established for EVERY bind generation.
     */
    @Test
    fun rebindWithoutDeath_reconfirmsClassificationForNewGeneration() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val durable = linkedMapOf<String, Any>(
            PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
            PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
            PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to true,
        )
        val prefs1 = MemPrefs(durable)
        val prefs2 = MemPrefs(durable)
        var currentPrefs: SharedPreferences? = prefs1
        app.bbcClassificationPrefsProvider = { currentPrefs }

        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        try {
            app.onServiceBind(service1)
            assertEquals(1L, generationCounter().get())
            assertEquals(1L, app.bbcActivationConsentHolder.currentGeneration())
            dispatcher.drainQueued()
            assertTrue("first bind must confirm classification",
                app.bbcActivationConsentHolder.isInstallClassificationConfirmed(1L))

            // Rebind service2 WITHOUT onServiceDied: confirmation must still run.
            currentPrefs = prefs2
            app.onServiceBind(service2)
            assertEquals(2L, app.bbcActivationConsentHolder.currentGeneration())

            dispatcher.drainQueued()
            assertTrue("rebind must re-confirm classification for the new generation",
                app.bbcActivationConsentHolder.isInstallClassificationConfirmed(2L))

            // Feed the durable PRE_UPGRADE + marker read the Hook gate sees.
            app.bbcActivationConsentHolder.resolve(
                BbcConsentAuthoritativeRead(
                    serviceGeneration = 2L,
                    optIn = BbcOptInValue.ABSENT,
                    classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    migrated = true,
                    legacyRuntimeEnabled = true,
                ),
            )
            assertEquals("a re-bound legacy install must resolve, not stay Initializing",
                BbcActivationConsentState.Legacy(true),
                app.bbcActivationConsentHolder.currentState(),
            )
            assertFalse(app.bbcActivationConsentHolder.currentState().requiresConsentFor(true))
            assertTrue("Hook gate is open for the same classification",
                BbcPreactivationHookReader.effectOpen(true, prefs2))
        } finally {
            scope.cancel()
            scopeField.set(app, previousScope)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * PR #90 re-review P1-B: a STALE bind attempt must never fence the NEW
     * service generation. Exact race:
     *  1. bind service1 -> gen1; attempt A resolves prefs1 (its commit will
     *     fail) and parks before completing;
     *  2. rebind service2 WITHOUT a death callback -> gen2; attempt B resolves
     *     prefs2 and confirms durable classification;
     *  3. A resumes while still holding prefs1 (the old code re-sampled the
     *     generation here and saw gen2);
     *  4. A's failed confirm installs an Unknown fence @gen2;
     *  5. B's resync sees 2 <= fenceGeneration(2) and cannot resolve Legacy;
     *  6. the Hook reads durable prefs2 and its gate is OPEN -> parity bug.
     * The fix snapshots generation + bind identity together at bind entry and
     * skips ALL effects of an attempt whose snapshot is no longer current.
     */
    @Test
    fun staleBindAttemptCannotFenceNewGeneration() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val durable = linkedMapOf<String, Any>(
            PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
            PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
            PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to true,
        )
        // prefs1 has no durable classification: its forced commit FAILS but
        // pollutes the optimistic local mMap. prefs2 is the genuine rebind.
        val prefs1 = MemPrefs().apply { commitSucceeds = false; flushOnFailedCommit = true }
        val prefs2 = MemPrefs(durable)
        var firstProviderCall = true
        val attemptAParked = CountDownLatch(1)
        val releaseAttemptA = CountDownLatch(1)
        app.bbcClassificationPrefsProvider = {
            if (firstProviderCall) {
                firstProviderCall = false
                attemptAParked.countDown()
                check(releaseAttemptA.await(10, TimeUnit.SECONDS)) { "stale bind attempt never released" }
                prefs1
            } else prefs2
        }

        val holder = app.bbcActivationConsentHolder
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        val attemptAThread = Thread({ dispatcher.drainQueued() }, "stale-bind-attempt").apply {
            isDaemon = true
        }
        try {
            app.onServiceBind(service1) // step 1: gen1, A queued
            assertEquals(1L, generationCounter().get())
            attemptAThread.start()
            check(attemptAParked.await(10, TimeUnit.SECONDS)) { "attempt A did not resolve prefs1" }

            app.onServiceBind(service2) // step 2: rebind, NO death callback
            assertEquals(2L, holder.currentGeneration())
            dispatcher.drainQueued() // B completes on the test thread
            assertTrue("the new bind must confirm classification for gen2",
                holder.isInstallClassificationConfirmed(2L))

            releaseAttemptA.countDown() // step 3: A resumes holding prefs1
            attemptAThread.join(10_000)
            check(!attemptAThread.isAlive) { "attempt A never finished" }

            // step 5: B's authoritative durable resync from prefs2.
            holder.resolve(
                BbcConsentAuthoritativeRead(
                    serviceGeneration = 2L,
                    optIn = BbcOptInValue.ABSENT,
                    classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    migrated = true,
                    legacyRuntimeEnabled = true,
                ),
            )
            assertEquals("a stale attempt must not install an Unknown fence at gen2",
                BbcActivationConsentState.Legacy(true), holder.currentState())
            assertFalse(holder.currentState().requiresConsentFor(true))
            assertTrue("Hook gate is open for the same durable prefs2",
                BbcPreactivationHookReader.effectOpen(true, prefs2))
        } finally {
            releaseAttemptA.countDown()
            attemptAThread.join(10_000)
            scope.cancel()
            scopeField.set(app, previousScope)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * PR #90 re-review P1-B: same race as [staleBindAttemptCannotFenceNewGeneration],
     * but the rebind lands DURING attempt A's blocking commit instead of while
     * A resolves prefs. A therefore passes the pre-commit snapshot check and
     * reaches the post-commit attempt-current guard; it must still apply no
     * confirmation or Unknown fence to gen2. Latch-based only — no polling.
     */
    @Test
    fun staleBindCommitCannotFenceNewGeneration() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val durable = linkedMapOf<String, Any>(
            PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
            PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
            PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to true,
        )
        // A's forced commit FAILS and pollutes its own local mMap; it blocks at
        // commit entry until the test has rebound to prefs2.
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val prefs1 = MemPrefs().apply {
            commitSucceeds = false
            flushOnFailedCommit = true
            beforeCommit = {
                commitEntered.countDown()
                check(releaseCommit.await(10, TimeUnit.SECONDS)) {
                    "blocked stale commit never released"
                }
            }
        }
        val prefs2 = MemPrefs(durable)
        var firstProviderCall = true
        app.bbcClassificationPrefsProvider = {
            if (firstProviderCall) {
                firstProviderCall = false
                prefs1
            } else prefs2
        }

        val holder = app.bbcActivationConsentHolder
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        val attemptAThread = Thread({ dispatcher.drainQueued() }, "stale-bind-commit").apply {
            isDaemon = true
        }
        try {
            app.onServiceBind(service1) // gen1, A queued
            assertEquals(1L, generationCounter().get())
            attemptAThread.start()
            check(commitEntered.await(10, TimeUnit.SECONDS)) {
                "attempt A never reached its blocking commit"
            }

            // Rebind lands WHILE A is inside its blocking commit: A has already
            // passed the pre-commit snapshot check, so only the post-commit
            // attempt-current guard can stop its outcome from touching gen2.
            app.onServiceBind(service2) // NO death callback; B queued
            assertEquals(2L, holder.currentGeneration())

            releaseCommit.countDown()
            attemptAThread.join(10_000)
            check(!attemptAThread.isAlive) { "attempt A never finished its commit" }

            dispatcher.drainQueued() // B completes on the test thread
            assertTrue("the new bind must confirm classification for gen2",
                holder.isInstallClassificationConfirmed(2L))

            holder.resolve(
                BbcConsentAuthoritativeRead(
                    serviceGeneration = 2L,
                    optIn = BbcOptInValue.ABSENT,
                    classification = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    migrated = true,
                    legacyRuntimeEnabled = true,
                ),
            )
            assertEquals("a stale commit must not install an Unknown fence at gen2",
                BbcActivationConsentState.Legacy(true), holder.currentState())
            assertFalse(holder.currentState().requiresConsentFor(true))
            assertTrue("Hook gate is open for the same durable prefs2",
                BbcPreactivationHookReader.effectOpen(true, prefs2))
        } finally {
            releaseCommit.countDown()
            attemptAThread.join(10_000)
            scope.cancel()
            scopeField.set(app, previousScope)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }
}
