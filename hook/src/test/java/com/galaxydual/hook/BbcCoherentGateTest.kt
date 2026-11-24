// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// PR #90 Phase B (B1/B3): JVM pins for the coherent, single-read final BBC
// effect gate. The executor is the real zero-queue factory wrapped with a
// submission counter, so "exactly ONE bounded read/wait" is observable. The
// SharedPreferences fake counts getAll() and makes edit() throw/count: ZERO
// writes must occur on the timeout, contention, flip and normal paths.
class BbcCoherentGateTest {

    private fun newExecutor(): ThreadPoolExecutor =
        PolicyRefreshBudget.newPolicyRefreshExecutor("test-bbc-coherent-gate")

    private class CountingExecutor(
        private val delegate: ExecutorService,
    ) : ExecutorService by delegate {
        val submissions = AtomicInteger(0)

        override fun <T : Any?> submit(task: Callable<T>?): Future<T> {
            submissions.incrementAndGet()
            return delegate.submit(task)
        }
    }

    private class CountingPrefs(
        private val values: Map<String, Any?>,
        private val alternateValues: Map<String, Any?>? = null,
        private val blockingGetAll: CountDownLatch? = null,
    ) : SharedPreferences {
        var getAllCalls = 0
            private set
        var editCalls = 0
            private set

        override fun getAll(): MutableMap<String, *> {
            getAllCalls++
            blockingGetAll?.await(2, TimeUnit.SECONDS)
            val map = if (alternateValues != null && getAllCalls % 2 == 0) alternateValues else values
            return LinkedHashMap(map)
        }
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val value = values[key] ?: return defValues
            if (value !is Set<*>) throw ClassCastException("$value is not a Set<String>")
            @Suppress("UNCHECKED_CAST")
            return (value as Set<String>).toMutableSet()
        }
        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (values[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor {
            editCalls++
            throw AssertionError("Hook remote prefs are READ-ONLY (libxposed API 102)")
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private val optInKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
    private val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED
    private val granted = PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED

    private fun freshHybrid(): (Boolean?) -> Boolean = { runtime ->
        bbcInjectionGateOpen(
            compileTimeGate = true,
            nowMs = { 1_000L },
            stickyState = AtomicReference(null),
        ) { runtime }
    }

    // Mirrors production bbcPolicy(): ONE bounded task for the coherent
    // snapshot, then BOTH stages decide from that same snapshot.
    private fun finalGate(
        prefs: CountingPrefs,
        executor: CountingExecutor,
        compileTimeGate: Boolean = true,
        hybridOpen: (Boolean?) -> Boolean = freshHybrid(),
    ): Boolean = bbcCoherentEffectGateBounded(
        compileTimeGate = compileTimeGate,
        readBoundedSnapshot = {
            readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                BbcPreactivationHookReader.readCoherent(prefs)
            }
        },
        hybridOpen = hybridOpen,
    )

    @Test
    fun oneBoundedReadCarriesBothStageInputs() {
        val executor = CountingExecutor(newExecutor())
        val prefs = CountingPrefs(
            mapOf(
                optInKey to true,
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to granted,
                runtimeKey to false, // runtime opt-out wins even with a valid opt-in
            ),
        )
        try {
            assertFalse(finalGate(prefs, executor))
            assertEquals("exactly ONE bounded submission", 1, executor.submissions.get())
            assertEquals("exactly ONE getAll() copy", 1, prefs.getAllCalls)
            assertEquals("zero write attempts", 0, prefs.editCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun coherentOptInAndRuntimeTrueOpensWithOneRead() {
        val executor = CountingExecutor(newExecutor())
        val prefs = CountingPrefs(
            mapOf(
                optInKey to true,
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to granted,
                runtimeKey to true,
                PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.pkg"),
                PolicySource.POLICY_KEY_EXTRA_VERSION to 4L,
            ),
        )
        try {
            assertTrue(finalGate(prefs, executor))
            assertEquals(1, executor.submissions.get())
            assertEquals(1, prefs.getAllCalls)
            assertEquals(0, prefs.editCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun alternatingGenerationsNeverOpenFromAHybridOfTwoReads() {
        // genA: preactivation OPEN + runtime OFF. genB: preactivation CLOSED +
        // runtime ON. A two-read implementation could sample A's opt-in and B's
        // runtime and open the effect. A single-copy implementation can only
        // ever produce CLOSED here, whichever generation it samples.
        val genA = mapOf(
            optInKey to true,
            PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to granted,
            runtimeKey to false,
        )
        val genB = mapOf(
            optInKey to false,
            PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to granted,
            runtimeKey to true,
        )
        for ((first, second) in listOf(genA to genB, genB to genA)) {
            val executor = CountingExecutor(newExecutor())
            val prefs = CountingPrefs(first, alternateValues = second)
            try {
                assertFalse(finalGate(prefs, executor))
                assertEquals(1, executor.submissions.get())
                assertEquals("only one generation may be read", 1, prefs.getAllCalls)
                assertEquals(0, prefs.editCalls)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun compileTimeClosedDoesZeroReadsAndZeroSubmissions() {
        val executor = CountingExecutor(newExecutor())
        val prefs = CountingPrefs(mapOf(optInKey to true, runtimeKey to true))
        try {
            assertFalse(finalGate(prefs, executor, compileTimeGate = false))
            assertEquals(0, executor.submissions.get())
            assertEquals(0, prefs.getAllCalls)
            assertEquals(0, prefs.editCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun timeoutFailsClosedWithOneSubmissionAndNoWrites() {
        val release = CountDownLatch(1)
        val executor = CountingExecutor(newExecutor())
        val prefs = CountingPrefs(
            mapOf(optInKey to true, runtimeKey to true),
            blockingGetAll = release,
        )
        try {
            assertFalse(finalGate(prefs, executor))
            assertEquals(1, executor.submissions.get())
            assertEquals("zero write attempts on timeout", 0, prefs.editCalls)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun contentionFailsClosedWithOneSubmissionAndNoWrites() {
        val delegate = newExecutor()
        val executor = CountingExecutor(delegate)
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prefs = CountingPrefs(mapOf(optInKey to true, runtimeKey to true))
        try {
            delegate.execute {
                occupied.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(occupied.await(1, TimeUnit.SECONDS))
            assertFalse(finalGate(prefs, executor))
            assertEquals("the single submit was rejected, never retried", 1, executor.submissions.get())
            assertEquals("reader never ran", 0, prefs.getAllCalls)
            assertEquals(0, prefs.editCalls)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun wrongTypeRuntimeFailsWholeSnapshotClosesSingleReadGate() {
        val executor = CountingExecutor(newExecutor())
        val prefs = CountingPrefs(
            mapOf(
                optInKey to true,
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to granted,
                runtimeKey to 1L, // Android getBoolean would throw CCE
            ),
        )
        try {
            assertFalse(finalGate(prefs, executor))
            assertEquals(1, executor.submissions.get())
            assertEquals(1, prefs.getAllCalls)
            assertEquals(0, prefs.editCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun stickyRuntimeOptOutIsPreservedByCoherentDecision() {
        // A coherent snapshot whose runtime read failed (null) still keeps a
        // process-local confirmed-OFF inside the TTL — sticky/CAS/clock logic
        // is unchanged by Phase B; only the second IPC/read was removed.
        val ttl = EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS
        val stamp = AtomicReference<Long?>(1_000L)
        val snapshot = BbcCoherentPrefsSnapshot(
            optIn = true,
            classification = granted,
            migrated = null,
            runtimeEnabled = null,
            versionBefore = VersionProbeResult.Absent,
            versionAfter = VersionProbeResult.Absent,
            coherent = true,
        )
        val open = bbcCoherentEffectDecision(true, snapshot) { runtime ->
            bbcInjectionGateOpen(
                compileTimeGate = true,
                nowMs = { 1_000L + ttl / 2 },
                ttlMs = ttl,
                stickyState = stamp,
            ) { runtime }
        }
        assertFalse(open)
        assertEquals(1_000L, stamp.get())
    }
}
