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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun bbcIoSession_isPublishedAtomicallyAndInvalidatedOnDeath() {
        // R5-03: the (generation, service, prefs) session is published atomically
        // at bind entry and invalidated on death ONLY for the owning identity.
        // A generation number alone is not an I/O identity.
        fun snapshot(): Any? = (GalaxyApp::class.java
            .getDeclaredField("bbcIoSession").apply { isAccessible = true }
            .get(app) as java.util.concurrent.atomic.AtomicReference<*>).get()

        fun generationOf(session: Any): Long {
            val f = session.javaClass.getDeclaredField("generation").apply { isAccessible = true }
            return f.get(session) as Long
        }
        fun serviceOf(session: Any): Any? {
            val f = session.javaClass.getDeclaredField("service").apply { isAccessible = true }
            return f.get(session)
        }

        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        assertNull("no session before bind", snapshot())

        app.onServiceBind(service1)
        val first = snapshot()
        assertNotNull("bind must publish the I/O session", first)
        assertEquals(1L, generationOf(first!!))
        assertSame("the session captures the actual bound service", service1, serviceOf(first))
        assertEquals(1L, app.bbcActivationConsentHolder.currentGeneration())

        app.onServiceBind(service2) // rebind without a death callback
        val second = snapshot()
        assertNotNull(second)
        assertNotSame("a new bind must replace the session atomically", first, second)
        assertEquals(2L, generationOf(second!!))
        assertSame("the new session captures the new service", service2, serviceOf(second))
        assertEquals(2L, app.bbcActivationConsentHolder.currentGeneration())

        app.onServiceDied(service1) // stale death must not clobber service2's session
        assertSame("stale death must keep service2's session", second, snapshot())

        app.onServiceDied(service2)
        assertNull("the owning death must invalidate the session", snapshot())
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
        /** Test oracle: a no-commit path must never reach an editor commit. */
        var commitCount = 0
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
                commitCount++
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

    /**
     * R5-03: a fake with SEPARATE optimistic-local and durable-daemon maps. A
     * failed commit pollutes only [local]; a genuinely fresh service instance
     * rebuilds local from [durable] only. [commits] records the keys of every
     * accepted editor commit so a test can prove no runtime enable ran.
     */
    private class DualPrefs(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        val local = LinkedHashMap<String, Any>(initial)
        val durable = LinkedHashMap<String, Any>(initial)
        var commitOk = true
        /** Throw AFTER the optimistic local write, before the durable daemon write. */
        var throwCancellationOnCommit = false
        val commits = Collections.synchronizedList(mutableListOf<Set<String>>())

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(local)
        override fun getString(key: String?, defValue: String?): String? =
            local[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (local[key] as? Set<*>)?.mapNotNull { it as? String }?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = local[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = local[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = local[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            local[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = local.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any>()
            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null && value != null) pending[key] = value
                return this
            }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor {
                pending.remove(key); return this
            }
            override fun clear(): SharedPreferences.Editor { pending.clear(); return this }
            override fun commit(): Boolean {
                commits.add(pending.keys.toSet())
                local.putAll(pending) // optimistic local write happens either way
                if (throwCancellationOnCommit) throw CancellationException("interrupted mid-commit")
                if (!commitOk) return false
                durable.putAll(pending)
                return true
            }
            override fun apply() { local.putAll(pending); durable.putAll(pending) }
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

    /** R5-03: the current session, read reflectively for identity assertions. */
    private fun currentSession(): Any? = (GalaxyApp::class.java
        .getDeclaredField("bbcIoSession").apply { isAccessible = true }
        .get(app) as java.util.concurrent.atomic.AtomicReference<*>).get()

    private fun sessionGeneration(session: Any): Long =
        (session.javaClass.getDeclaredField("generation").apply { isAccessible = true }
            .get(session) as Long)

    private fun sessionService(session: Any): Any? =
        session.javaClass.getDeclaredField("service").apply { isAccessible = true }
            .get(session)

    private fun sessionPrefs(session: Any): Any? =
        session.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .get(session)

    private fun invokeConsentWrite(capturedGeneration: Long): BbcConsentWriteResult {
        val method = GalaxyApp::class.java.getDeclaredMethod(
            "performBbcConsentWrite", Long::class.java,
        )
        method.isAccessible = true
        return method.invoke(app, capturedGeneration) as BbcConsentWriteResult
    }

    /**
     * The enable-pending marker is an APP-ONLY durability bookkeeping key. It
     * must not change the Hook gate for any marker value — Hook code is not
     * even aware of the key.
     */
    @Test
    fun appOnlyEnablePendingMarker_isIgnoredByHookGate() {
        val base = linkedMapOf<String, Any>(
            PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to true,
            PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to true,
            PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        )
        val without = BbcPreactivationHookReader.effectOpen(true, MemPrefs(base))
        assertTrue("the base opt-in gate must be open", without)
        for (marker in listOf(true, false)) {
            val withMarker = MemPrefs(base + (BBC_ENABLE_PENDING_PREF_KEY to marker))
            assertEquals("the app-only marker must not change Hook authorization",
                without, BbcPreactivationHookReader.effectOpen(true, withMarker))
        }
    }

    /**
     * Phase 2 fixer (BLOCKING 2 / F1): a consent-write attempt whose bind
     * snapshot was superseded BEFORE the commit entered must report a NO-
     * GENERATION sentinel, not the current generation. Otherwise the holder
     * installs a spurious current-gen Unknown fence that blocks the gen-2
     * authoritative resync until gen 3.
     */
    @Test
    fun consentWriteWithNoCommit_doesNotFenceTheCurrentGeneration() {
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
            PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to true,
        )
        val prefs1 = MemPrefs(durable)
        val prefs2 = MemPrefs(durable)
        var currentPrefs: SharedPreferences? = prefs1
        app.bbcClassificationPrefsProvider = { currentPrefs }
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        try {
            app.onServiceBind(service1) // gen 1 captures prefs1 + service1
            assertEquals(1L, generationCounter().get())

            // Rebind to a genuinely DIFFERENT prefs instance without a death.
            currentPrefs = prefs2
            app.onServiceBind(service2)
            assertEquals(2L, app.bbcActivationConsentHolder.currentGeneration())

            // The gen-1 attempt is stale BEFORE any commit: no local pollution.
            val result = invokeConsentWrite(1L)
            assertEquals(BbcWriteOutcome.Unknown, result.outcome)
            assertEquals("no commit -> NO generation sentinel, not current gen 2",
                0L, result.touchedGeneration)
            assertEquals(0, prefs1.commitCount)
            assertEquals(0, prefs2.commitCount)

            // The gen-2 resync resolves normally because nothing fenced it.
            app.bbcActivationConsentHolder.resolve(
                BbcConsentAuthoritativeRead(
                    serviceGeneration = 2L,
                    optIn = BbcOptInValue.TRUE,
                    classification = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
                    migrated = null,
                    legacyRuntimeEnabled = true,
                ),
            )
            assertEquals("a spurious current-gen fence must not block resync",
                BbcActivationConsentState.Granted,
                app.bbcActivationConsentHolder.currentState(),
            )
        } finally {
            scope.cancel()
            scopeField.set(app, previousScope)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * Same race, but the rebind lands INSIDE the opt-in commit. The write did
     * touch prefs1, so its reply is forced to Unknown and the touched
     * generation must stay 1 — the fresh gen-2 resync is still allowed.
     */
    @Test
    fun consentWriteRebindMidCommit_forcesUnknownButFencesOnlyTouchedGeneration() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val prefs1 = MemPrefs().apply {
            beforeCommit = {
                commitEntered.countDown()
                check(releaseCommit.await(10, TimeUnit.SECONDS)) {
                    "stale opt-in commit never reached its blocking point"
                }
            }
        }
        val prefs2 = MemPrefs()
        var currentPrefs: SharedPreferences? = prefs1
        app.bbcClassificationPrefsProvider = { currentPrefs }
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        val result = AtomicReference<BbcConsentWriteResult>()
        val writer = Thread({
            result.set(invokeConsentWrite(1L))
        }, "stale-consent-commit").apply { isDaemon = true }
        try {
            app.onServiceBind(service1)
            writer.start()
            check(commitEntered.await(10, TimeUnit.SECONDS)) { "commit never entered" }

            currentPrefs = prefs2
            app.onServiceBind(service2) // rebind during the blocking commit
            assertEquals(2L, app.bbcActivationConsentHolder.currentGeneration())
            releaseCommit.countDown()
            writer.join(10_000)
            check(!writer.isAlive) { "stale writer never finished" }

            assertEquals(BbcWriteOutcome.Unknown, result.get().outcome)
            assertEquals("the fence belongs to the instance actually touched", 1L, result.get().touchedGeneration)
            assertEquals(1, prefs1.commitCount)

            app.bbcActivationConsentHolder.resolve(
                BbcConsentAuthoritativeRead(
                    serviceGeneration = 2L,
                    optIn = BbcOptInValue.TRUE,
                    classification = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
                    migrated = null,
                    legacyRuntimeEnabled = true,
                ),
            )
            assertEquals(BbcActivationConsentState.Granted,
                app.bbcActivationConsentHolder.currentState())
        } finally {
            releaseCommit.countDown()
            writer.join(10_000)
            scope.cancel()
            scopeField.set(app, previousScope)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
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

        // A SAME-GENERATION fence requires a commit that ACTUALLY ENTERED:
        // resolve a prefs instance whose durable commit fails (and pollutes its
        // optimistic local map). A null provider is a NO-COMMIT failure and must
        // NOT fence at all — that case is covered by
        // noCommitBindFailure_installsNoPermanentFence_nextCleanBindGrantsAndEnablesOnce.
        val failingPrefs = MemPrefs(emptyMap()).apply {
            commitSucceeds = false
            flushOnFailedCommit = true
        }
        app.bbcClassificationPrefsProvider = { failingPrefs }
        val service1 = createFakeXposedService()
        try {
            app.onServiceBind(service1)
            val counter = generationCounter()
            assertEquals(1L, counter.get())
            assertEquals(1L, app.bbcActivationConsentHolder.currentGeneration())
            val session = currentSession()
            assertNotNull("bind must publish a session", session)
            assertEquals(1L, sessionGeneration(session!!))
            assertSame(service1, sessionService(session))
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
            app.bbcClassificationPrefsProvider = { null }
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
            val sessionA = currentSession()
            assertNotNull("gen1 must publish a session", sessionA)
            assertEquals(1L, sessionGeneration(sessionA!!))
            assertSame(service1, sessionService(sessionA))
            attemptAThread.start()
            check(attemptAParked.await(10, TimeUnit.SECONDS)) { "attempt A did not resolve prefs1" }

            app.onServiceBind(service2) // step 2: rebind, NO death callback
            assertEquals(2L, holder.currentGeneration())
            val sessionB = currentSession()
            assertNotNull("gen2 must publish a new session", sessionB)
            assertNotSame(sessionA, sessionB)
            assertEquals(2L, sessionGeneration(sessionB!!))
            assertSame(service2, sessionService(sessionB))
            dispatcher.drainQueued() // B completes on the test thread
            assertTrue("the new bind must confirm classification for gen2",
                holder.isInstallClassificationConfirmed(2L))

            releaseAttemptA.countDown() // step 3: A resumes holding prefs1
            attemptAThread.join(10_000)
            check(!attemptAThread.isAlive) { "attempt A never finished" }
            assertSame("the stale session resolved prefs from the CAPTURED service",
                prefs1, sessionPrefs(sessionA))

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
            val sessionA = currentSession()
            assertNotNull(sessionA)
            assertEquals(1L, sessionGeneration(sessionA!!))
            assertSame(service1, sessionService(sessionA))
            attemptAThread.start()
            check(commitEntered.await(10, TimeUnit.SECONDS)) {
                "attempt A never reached its blocking commit"
            }

            // Rebind lands WHILE A is inside its blocking commit: A has already
            // passed the pre-commit snapshot check, so only the post-commit
            // attempt-current guard can stop its outcome from touching gen2.
            app.onServiceBind(service2) // NO death callback; B queued
            assertEquals(2L, holder.currentGeneration())
            val sessionB = currentSession()
            assertNotNull(sessionB)
            assertNotSame(sessionA, sessionB)
            assertEquals(2L, sessionGeneration(sessionB!!))
            assertSame(service2, sessionService(sessionB))

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

    // --- PR #90 Phase 3 (R3-06a/E2, R3-06b/E3, E5) ------------------------------

    /**
     * E2: the app-scoped runtime holder must be NEUTRAL and must not perform a
     * binder read while Main acquires it. Before the fix the lazy initializer
     * called isBbcInjectionEnabled(), which defaults to true on read failure —
     * so this NEUTRAL assertion fails (confirmedOrUnknown == true) if a binder
     * read ever returns to the initializer.
     */
    @Test
    fun bbcToggleRuntimeState_isNeutralWithoutBinderRead() {
        assertNull("no binder read may seed a Confirmed effect on Main",
            app.bbcToggleRuntimeState.confirmedOrUnknown)
        assertEquals(BbcEffectStatus.Provisional, app.bbcToggleRuntimeState.effectStatus.value)
    }

    /**
     * E3: onServiceDied must never acquire versionCommitLock on the service
     * callback thread. We hold that monitor on another thread and assert the
     * death callback returns promptly AND has already bumped the generation.
     * Latches only — no wall-clock polling.
     */
    @Test
    fun onServiceDied_doesNotWaitForVersionCommitLockAndBumpsFirst() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })

        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = Thread({
            synchronized(versionCommitLock) {
                lockHeld.countDown()
                check(releaseLock.await(10, TimeUnit.SECONDS)) { "lock holder never released" }
            }
        }, "version-commit-lock-holder").apply { isDaemon = true }

        val service = createFakeXposedService()
        val deathReturned = CountDownLatch(1)
        try {
            app.onServiceBind(service)
            assertEquals(1L, app.bbcActivationConsentHolder.currentGeneration())

            lockHolder.start()
            check(lockHeld.await(10, TimeUnit.SECONDS)) { "versionCommitLock was not held" }

            val deathThread = Thread({
                app.onServiceDied(service)
                deathReturned.countDown()
            }, "service-death-callback").apply { isDaemon = true }
            deathThread.start()

            assertTrue("onServiceDied must not block on versionCommitLock",
                deathReturned.await(3, TimeUnit.SECONDS))
            deathThread.join(10_000)
            check(!deathThread.isAlive) { "death callback never finished" }
            assertEquals("generation bump is immediate, not behind the commit lock",
                2L, app.bbcActivationConsentHolder.currentGeneration())
            assertFalse(GalaxyApp.isServiceConnected())
        } finally {
            releaseLock.countDown()
            lockHolder.join(10_000)
            scope.cancel()
            scopeField.set(app, previousScope)
        }
    }

    /** Pending-intent cleanup is deferred to the app IO lane, under the lock. */
    @Test
    fun onServiceDied_defersPendingClassificationClearToIo() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val pending = PendingBbcClassification.of(
            MemPrefs(),
            BbcClassificationPlan.Write(NEW_GENERATION_NOT_CONSENTED, false),
        )
        pendingBbcClassification.set(pending)
        val service = createFakeXposedService()
        try {
            app.onServiceBind(service)
            app.onServiceDied(service)
            assertSame("the clear must run on IO, not on the callback thread",
                pending, pendingBbcClassification.get())
            dispatcher.drainQueued()
            assertNull("the owning death clears the pending intent on IO",
                pendingBbcClassification.get())
        } finally {
            scope.cancel()
            scopeField.set(app, previousScope)
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /** The deferred clear must not drop a retry intent installed by a newer bind. */
    @Test
    fun onServiceDied_deferredClearDoesNotDropNewerBindsPendingIntent() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val deadPending = PendingBbcClassification.of(
            MemPrefs(), BbcClassificationPlan.Write(NEW_GENERATION_NOT_CONSENTED, false),
        )
        pendingBbcClassification.set(deadPending)
        val newerPending = PendingBbcClassification.of(
            MemPrefs(), BbcClassificationPlan.Write(PRE_UPGRADE_EXISTING, true),
        )
        val service = createFakeXposedService()
        try {
            app.onServiceBind(service)
            app.onServiceDied(service) // captures deadPending, queues the IO clear
            pendingBbcClassification.set(newerPending) // newer bind re-armed the retry
            dispatcher.drainQueued()
            assertSame("a stale death must never clear a newer bind's retry intent",
                newerPending, pendingBbcClassification.get())
        } finally {
            scope.cancel()
            scopeField.set(app, previousScope)
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * E5: when the death bump invalidates an in-flight bind attempt, the app must
     * schedule a consent resync for the NEW generation — the stale attempt
     * deliberately skips its own refresh, so without this the holder is stranded
     * at Initializing until the user reopens Settings.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deathBumpInvalidatingBind_schedulesResyncForNewGeneration() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })

        val scheduler = TestCoroutineScheduler()
        val coordinatorDispatcher = StandardTestDispatcher(scheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
        val holder = app.bbcActivationConsentHolder
        val reads = mutableListOf<Long>()
        val coordinator = BbcToggleCoordinator(
            scope = coordinatorScope,
            state = BbcToggleRuntimeState(),
            commit = { BbcWriteOutcome.Confirmed(it) },
            io = coordinatorDispatcher,
            consentRead = { generation ->
                reads.add(generation)
                BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
            },
            consentHolder = holder,
        )
        val coordinatorField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcToggleCoordinator") }
        coordinatorField.isAccessible = true
        val previousCoordinator = coordinatorField.get(app)
        coordinatorField.set(app, lazy { coordinator })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val service = createFakeXposedService()
        try {
            app.onServiceBind(service) // gen 1, bind attempt captured
            assertEquals(1L, holder.currentGeneration())
            assertTrue("the bind attempt must be queued, not completed", dispatcher.hasQueued())

            app.onServiceDied(service) // bumps to gen 2; attempt is now stale
            assertEquals(2L, holder.currentGeneration())
            assertTrue("no resync before the IO bookkeeping runs", reads.isEmpty())

            dispatcher.drainQueued() // deferred cleanup + resync scheduling
            scheduler.advanceUntilIdle() // the coordinator drains its read lanes
            assertEquals("the invalidated bind must trigger a resync at the new generation",
                listOf(2L), reads)
        } finally {
            coordinatorScope.cancel()
            scope.cancel()
            scopeField.set(app, previousScope)
            coordinatorField.set(app, previousCoordinator)
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    // --- R5-03: session identity is not just a generation number ---------------

    /**
     * R5-03 (P1): a failed grant pollutes the optimistic local map of prefs P.
     * A repeated bind of the SAME prefs instance at a higher generation must NOT
     * let a forced-classification-success + resync read that polluted map as
     * fresh evidence: no Granted, no auto-enable, Unknown fence holds. Only a
     * genuinely FRESH prefs instance with a durable grant may clear it.
     */
    @Test
    fun samePrefsIdentityHigherGeneration_cannotClearPollutionFence_freshPrefsCan() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })

        val scheduler = TestCoroutineScheduler()
        val coordinatorDispatcher = StandardTestDispatcher(scheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
        // The REAL GalaxyApp seams (including the session-aware consent read),
        // only the dispatcher is virtual so ordering is deterministic.
        val coordinator = app.buildBbcToggleCoordinator(coordinatorScope, coordinatorDispatcher)
        val coordinatorField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcToggleCoordinator") }
        coordinatorField.isAccessible = true
        val previousCoordinator = coordinatorField.get(app)
        coordinatorField.set(app, lazy { coordinator })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
        val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
        val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED
        val pendingKey = BBC_ENABLE_PENDING_PREF_KEY
        val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED

        val polluted = DualPrefs()
        polluted.commitOk = false // grant commit fails; optimistic local is polluted
        var currentPrefs: SharedPreferences = polluted
        app.bbcClassificationPrefsProvider = { currentPrefs }
        val holder = app.bbcActivationConsentHolder
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        val service3 = createFakeXposedService()
        try {
            // gen1: bind classification fails and installs an Unknown fence whose
            // identity is the exact prefs instance actually touched.
            app.onServiceBind(service1)
            assertEquals(1L, holder.currentGeneration())
            dispatcher.drainQueued()
            assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
            val fenceSession = holder.currentFenceSession()
            assertNotNull("a failed bind must record the touched session", fenceSession)
            assertSame("the fence is attributed to the polluted prefs instance",
                polluted, (fenceSession as BbcConsentSessionIdentity).prefsIdentity)

            // A failed consent grant pollutes local optin=true + marker=true;
            // the daemon persisted nothing.
            val seq = holder.startConsentIntent()
            holder.notePendingEnable(seq, 1L)
            coordinator.submitConsentWrite(seq, 1L, onResult = {})
            scheduler.advanceUntilIdle()
            assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
            assertEquals(true, polluted.local[optinKey])
            assertFalse("no durable opt-in was persisted by the failed grant",
                polluted.durable.containsKey(optinKey))

            // gen2: SAME prefs identity, higher generation. The forced
            // classification commit now succeeds (classification + marker ONLY).
            polluted.commitOk = true
            polluted.commits.clear()
            app.onServiceBind(service2)
            assertEquals(2L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()
            assertTrue("classification is confirmed for gen2",
                holder.isInstallClassificationConfirmed(2L))
            assertEquals("a same-prefs higher generation must NOT clear the fence",
                BbcActivationConsentState.Unknown, holder.currentState())
            assertFalse("no Granted from an optimistic local map",
                holder.currentState() is BbcActivationConsentState.Granted)
            assertFalse("forced classification must not write the consent key",
                polluted.commits.any { optinKey in it })
            assertFalse("no auto-enable may be driven from the polluted instance",
                polluted.commits.any { runtimeKey in it })

            // gen3: a genuinely FRESH prefs instance with a durable grant.
            val fresh = DualPrefs(
                mapOf(
                    optinKey to true,
                    pendingKey to true,
                    classKey to newGeneration,
                    runtimeKey to false,
                ),
            )
            currentPrefs = fresh
            app.onServiceBind(service3)
            assertEquals(3L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()
            assertEquals("a different clean prefs instance may clear the fence",
                BbcActivationConsentState.Granted, holder.currentState())
            assertTrue("the fresh durable grant may converge the effect enable",
                fresh.commits.any { runtimeKey in it })
        } finally {
            coordinatorScope.cancel()
            scope.cancel()
            scopeField.set(app, previousScope)
            coordinatorField.set(app, previousCoordinator)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * R5-03 review R1: a CancellationException thrown by the consent store AFTER
     * it polluted the optimistic local map must still stamp the pollution fence
     * with the session identity of the commit. A dropped identity would let a
     * rebind of the SAME prefs at a higher generation clear the fence by
     * reading that polluted map again (generation-only rule).
     */
    @Test
    fun consentCommitCancellationAfterPollution_samePrefsRebindStaysFenced_freshPrefsGrants() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })

        val scheduler = TestCoroutineScheduler()
        val coordinatorDispatcher = StandardTestDispatcher(scheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
        val coordinator = app.buildBbcToggleCoordinator(coordinatorScope, coordinatorDispatcher)
        val coordinatorField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcToggleCoordinator") }
        coordinatorField.isAccessible = true
        val previousCoordinator = coordinatorField.get(app)
        coordinatorField.set(app, lazy { coordinator })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
        val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
        val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED
        val pendingKey = BBC_ENABLE_PENDING_PREF_KEY
        val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED

        val polluted = DualPrefs()
        var currentPrefs: SharedPreferences = polluted
        app.bbcClassificationPrefsProvider = { currentPrefs }
        val holder = app.bbcActivationConsentHolder
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        val service3 = createFakeXposedService()
        try {
            app.onServiceBind(service1)
            dispatcher.drainQueued()
            assertEquals(1L, holder.currentGeneration())

            // The store interrupts AFTER putting optin=true + marker=true into
            // the optimistic local map; the daemon persisted nothing.
            polluted.throwCancellationOnCommit = true
            val seq = holder.startConsentIntent()
            holder.notePendingEnable(seq, 1L)
            assertEquals(
                BbcWorkEnqueue.Accepted,
                coordinator.submitConsentWrite(seq, 1L, onResult = {}),
            )
            scheduler.advanceUntilIdle()

            assertEquals(true, polluted.local[optinKey])
            assertFalse("the interrupted commit persisted no durable opt-in",
                polluted.durable.containsKey(optinKey))
            assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
            val fence = holder.currentFenceSession()
            assertNotNull("the CANCELLED commit must still stamp its session", fence)
            assertSame("the fence owns the polluted prefs instance",
                polluted, (fence as BbcConsentSessionIdentity).prefsIdentity)

            // gen2: SAME prefs instance at a higher generation. A forced
            // classification success must not let its optimistic read clear the
            // fence left by the interrupted commit.
            polluted.throwCancellationOnCommit = false
            polluted.commitOk = true
            polluted.commits.clear()
            app.onServiceBind(service2)
            assertEquals(2L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()
            assertTrue("classification is confirmed for gen2",
                holder.isInstallClassificationConfirmed(2L))
            assertEquals("a same-prefs higher generation must stay fenced",
                BbcActivationConsentState.Unknown, holder.currentState())
            assertFalse("no Granted from the polluted local map",
                holder.currentState() is BbcActivationConsentState.Granted)
            assertFalse("no auto-enable may be driven from the polluted instance",
                polluted.commits.any { runtimeKey in it })

            // gen3: a genuinely fresh prefs instance with a durable grant.
            val fresh = DualPrefs(
                mapOf(
                    optinKey to true,
                    pendingKey to true,
                    classKey to newGeneration,
                    runtimeKey to false,
                ),
            )
            currentPrefs = fresh
            app.onServiceBind(service3)
            assertEquals(3L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()
            assertEquals("a clean prefs identity may clear the fence",
                BbcActivationConsentState.Granted, holder.currentState())
            assertTrue("the durable grant may converge the effect enable",
                fresh.commits.any { runtimeKey in it })
        } finally {
            coordinatorScope.cancel()
            scope.cancel()
            scopeField.set(app, previousScope)
            coordinatorField.set(app, previousCoordinator)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * R5-03 review R2: a user OFF racing a rebind must not be dropped. The first
     * commit may land on the old prefs; on the failed post-commit currency check
     * the OFF must be retried ONCE against the genuinely current session so its
     * durable enable-pending marker is cleared. Otherwise a cold resync of the
     * new prefs re-enables the effect the user just turned off.
     */
    @Test
    fun userOffRebindDuringCommit_retriesOnNewSession_coldResyncStaysOff() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })

        val scheduler = TestCoroutineScheduler()
        val coordinatorDispatcher = StandardTestDispatcher(scheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
        val coordinator = app.buildBbcToggleCoordinator(coordinatorScope, coordinatorDispatcher)
        val coordinatorField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcToggleCoordinator") }
        coordinatorField.isAccessible = true
        val previousCoordinator = coordinatorField.get(app)
        coordinatorField.set(app, lazy { coordinator })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
        val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
        val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED
        val pendingKey = BBC_ENABLE_PENDING_PREF_KEY
        val durable = linkedMapOf<String, Any>(
            optinKey to true,
            pendingKey to true, // an enable is still owed before the OFF
            runtimeKey to false,
            classKey to PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        )
        val prefs1 = MemPrefs(durable)
        val prefs2 = MemPrefs(durable)
        var currentPrefs: SharedPreferences = prefs1
        app.bbcClassificationPrefsProvider = { currentPrefs }
        val holder = app.bbcActivationConsentHolder
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        val service3 = createFakeXposedService()
        try {
            app.onServiceBind(service1)
            assertEquals(1L, holder.currentGeneration())

            // The rebind lands INSIDE the OFF commit, before MemPrefs applies
            // the edit. The old session's reply is invalid; the write itself
            // already touched prefs1.
            prefs1.beforeCommit = {
                currentPrefs = prefs2
                app.onServiceBind(service2)
            }
            assertEquals(BbcWorkEnqueue.Accepted,
                coordinator.submit(holder.nextIntentSequence(), false, onResult = {}))
            scheduler.advanceUntilIdle()

            assertEquals("a rebind happened during the OFF commit", 2L, holder.currentGeneration())
            assertEquals("the new session received the OFF", false, prefs2.data[runtimeKey])
            assertEquals("the new session must have its marker cleared by the retry",
                false, prefs2.data[pendingKey])

            // Cold resync: a brand-new prefs instance rebuilt from what the
            // daemon actually persisted must not re-enable the effect.
            val coldPrefs = MemPrefs(prefs2.data)
            currentPrefs = coldPrefs
            app.onServiceBind(service3)
            assertEquals(3L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()
            assertEquals("cold resync must not re-enable after a durable OFF",
                false, coldPrefs.data[runtimeKey])
            val coldRead = remoteHookPolicyStore { coldPrefs }.readBbcConsentAuthoritative()
            assertFalse("the durable marker stays clear across the cold start",
                coldRead.enablePending == true)
        } finally {
            prefs1.beforeCommit = null
            scope.cancel()
            scopeField.set(app, previousScope)
            coordinatorField.set(app, previousCoordinator)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * R5 Phase 3a (BLOCKING): a no-commit bind failure must NOT install an
     * Unknown fence. When prefs cannot be resolved (non-owner UID / transient
     * binder throwable) NO commit ever entered, so there is no optimistic local
     * map to fence. Installing a sessionless fence here (`fenceSession == null`)
     * is permanent: every later stamped resync meets `fenceIsCurrent == true`
     * and no prefs instance can clear it, so a genuinely clean bind with a
     * durable opt-in stays Unknown forever and needs a manual re-grant.
     *
     * gen1: provider -> null => no commit, neutral state, no fence.
     * gen2: a clean prefs with durable optin=true + enablePending marker must
     *       resync to Granted and write exactly ONE effect enable.
     */
    @Test
    fun noCommitBindFailure_installsNoPermanentFence_nextCleanBindGrantsAndEnablesOnce() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })

        val scheduler = TestCoroutineScheduler()
        val coordinatorDispatcher = StandardTestDispatcher(scheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
        val coordinator = app.buildBbcToggleCoordinator(coordinatorScope, coordinatorDispatcher)
        val coordinatorField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcToggleCoordinator") }
        coordinatorField.isAccessible = true
        val previousCoordinator = coordinatorField.get(app)
        coordinatorField.set(app, lazy { coordinator })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
        val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
        val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED
        val pendingKey = BBC_ENABLE_PENDING_PREF_KEY
        val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED
        val holder = app.bbcActivationConsentHolder
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()
        try {
            // gen1: no prefs instance can be resolved -> no commit at all.
            app.bbcClassificationPrefsProvider = { null }
            app.onServiceBind(service1)
            assertEquals(1L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()

            assertFalse("a no-commit bind failure must NOT be fenced Unknown",
                holder.currentState() is BbcActivationConsentState.Unknown)
            assertEquals("no commit -> no fence generation sentinel",
                -1L, holder.currentFenceGeneration())
            assertNull("no sessionless fence may be installed without a commit",
                holder.currentFenceSession())

            // gen2: genuinely clean prefs with a durable opt-in and marker.
            val clean = DualPrefs(
                mapOf(
                    optinKey to true,
                    pendingKey to true,
                    classKey to newGeneration,
                    runtimeKey to false,
                ),
            )
            app.bbcClassificationPrefsProvider = { clean }
            app.onServiceBind(service2)
            assertEquals(2L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()

            assertEquals("a clean authoritative resync must converge to Granted",
                BbcActivationConsentState.Granted, holder.currentState())
            assertNull("no stale sessionless fence may remain after a clean bind",
                holder.currentFenceSession())
            assertEquals("exactly one durable effect enable must be written",
                1, clean.commits.count { runtimeKey in it })
        } finally {
            coordinatorScope.cancel()
            scope.cancel()
            scopeField.set(app, previousScope)
            coordinatorField.set(app, previousCoordinator)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

    /**
     * R5 Phase 3a (BLOCKING C1): a RESOLVED prefs instance whose classification
     * planning FAILS CLOSED creates NO editor and commits NOTHING. There is no
     * optimistic local map to fence, so treating this as a pollution failure
     * installs a stamped Unknown fence that a later bind of the SAME cached
     * prefs instance can never clear (only a Confirmed grant, a genuinely fresh
     * prefs instance, or a process restart can). That is a permanent in-process
     * lockout requiring a manual re-grant — contrary to the recovery contract.
     *
     * gen1: resolved prefs carrying an UNKNOWN classification value => planner
     *       FailClosed, zero commits, holder must stay neutral/Initializing.
     * gen2: the SAME cached instance now carries a durable opt-in + enable
     *       marker + a valid classification => bind/resync must reach Granted
     *       and write exactly one effect enable.
     */
    @Test
    fun noCommitFailClosedClassification_installsNoFence_sameCachedPrefsNextBindGrantsOnce() {
        val dispatcher = GateDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val scopeField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcClassificationScope") }
        scopeField.isAccessible = true
        val previousScope = scopeField.get(app)
        scopeField.set(app, lazy { scope })

        val scheduler = TestCoroutineScheduler()
        val coordinatorDispatcher = StandardTestDispatcher(scheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
        val coordinator = app.buildBbcToggleCoordinator(coordinatorScope, coordinatorDispatcher)
        val coordinatorField = GalaxyApp::class.java.declaredFields
            .first { it.name.startsWith("bbcToggleCoordinator") }
        coordinatorField.isAccessible = true
        val previousCoordinator = coordinatorField.get(app)
        coordinatorField.set(app, lazy { coordinator })
        clearPendingBbcClassification()
        resetRemotePoisonForTests()

        val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
        val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
        val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED
        val pendingKey = BBC_ENABLE_PENDING_PREF_KEY
        val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED
        val holder = app.bbcActivationConsentHolder
        val service1 = createFakeXposedService()
        val service2 = createFakeXposedService()

        // The SAME cached instance is returned by both binds. An unknown value
        // makes planning fail closed without ever calling prefs.edit().
        val cached = DualPrefs(mapOf(classKey to "FUTURE_SCHEMA_UNKNOWN"))
        app.bbcClassificationPrefsProvider = { cached }
        try {
            app.onServiceBind(service1)
            assertEquals(1L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()

            assertEquals("a fail-closed plan must create no editor/commit",
                0, cached.commits.size)
            assertEquals("a no-commit fail-closed bind must stay neutral",
                BbcActivationConsentState.Initializing, holder.currentState())
            assertEquals("no fence generation without a commit",
                -1L, holder.currentFenceGeneration())
            assertNull("no stamped fence without a commit", holder.currentFenceSession())

            // gen2: the same cached instance now carries durable evidence. No
            // permanent same-prefs lockout may block this authoritative resync.
            for (map in listOf(cached.local, cached.durable)) {
                map[classKey] = newGeneration
                map[optinKey] = true
                map[pendingKey] = true
                map[runtimeKey] = false
            }
            app.onServiceBind(service2)
            assertEquals(2L, holder.currentGeneration())
            dispatcher.drainQueued()
            scheduler.advanceUntilIdle()

            assertTrue("classification must be confirmed for gen2",
                holder.isInstallClassificationConfirmed(2L))
            assertEquals("a resolved no-commit failure must not lock out the next bind",
                BbcActivationConsentState.Granted, holder.currentState())
            assertNull("no stale same-prefs fence may remain", holder.currentFenceSession())
            assertEquals("exactly one durable effect enable must be written",
                1, cached.commits.count { runtimeKey in it })
        } finally {
            coordinatorScope.cancel()
            scope.cancel()
            scopeField.set(app, previousScope)
            coordinatorField.set(app, previousCoordinator)
            app.bbcClassificationPrefsProvider = { null }
            clearPendingBbcClassification()
            resetRemotePoisonForTests()
        }
    }

}
