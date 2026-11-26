// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    companion object {
        // A cold JVM loading/JIT-compiling classes on the fresh executor thread
        // can exceed the production 15 ms wall-clock ceiling. Determinism-
        // sensitive tests inject this generous timeout so a timeout can never
        // masquerade as a policy decision: the corrupt-key half must reach the
        // wrong-type fail-closed path, not time out into a null snapshot.
        private const val GENEROUS_READ_TIMEOUT_MS = 2_000L
    }

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
    // snapshot, then BOTH stages decide from that same snapshot. The timeout
    // defaults to production's BBC_GATE_MAX_MS; determinism-sensitive tests can
    // inject a generous value, and snapshotSpy observes what the bounded read
    // actually returned (null == timeout/contention, non-null == a real snapshot)
    // so a timeout can be distinguished from a wrong-type fail-closed decision.
    private fun finalGate(
        prefs: CountingPrefs,
        executor: CountingExecutor,
        compileTimeGate: Boolean = true,
        hybridOpen: (Boolean?) -> Boolean = freshHybrid(),
        timeoutMs: Long = DualHook.BBC_GATE_MAX_MS,
        snapshotSpy: ((BbcCoherentPrefsSnapshot?) -> Unit)? = null,
    ): Boolean = bbcCoherentEffectGateBounded(
        compileTimeGate = compileTimeGate,
        readBoundedSnapshot = {
            readBbcPreactivationBounded(executor, timeoutMs) {
                BbcPreactivationHookReader.readCoherent(prefs)
            }.also { snapshotSpy?.invoke(it) }
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
            assertFalse(finalGate(prefs, executor, timeoutMs = GENEROUS_READ_TIMEOUT_MS))
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
            assertTrue(finalGate(prefs, executor, timeoutMs = GENEROUS_READ_TIMEOUT_MS))
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
                assertFalse(finalGate(prefs, executor, timeoutMs = GENEROUS_READ_TIMEOUT_MS))
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
    fun absentRuntimeKeyWithAuthorizedLegacyInstallOpensButReadFailureCloses() {
        // Honest semantics pinned end-to-end (still read-only / zero writes):
        // an ABSENT bbc_injection_enabled key maps to defValue=true only because
        // there is no explicit opt-out. It is NOT a default-on basis: legacy
        // consent must still be authorized by the owner App's durable
        // PRE_UPGRADE_EXISTING classification + trusted marker.
        val authorizedLegacy = mapOf(
            PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
            PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
            PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.pkg"),
            PolicySource.POLICY_KEY_EXTRA_VERSION to 4L,
        )
        val absentExecutor = CountingExecutor(newExecutor())
        val absentPrefs = CountingPrefs(authorizedLegacy)
        val absentSnapshot = AtomicReference<BbcCoherentPrefsSnapshot?>()
        try {
            assertTrue("absent runtime key = no explicit opt-out; legacy authority decides",
                finalGate(absentPrefs, absentExecutor,
                    timeoutMs = GENEROUS_READ_TIMEOUT_MS,
                    snapshotSpy = { absentSnapshot.set(it) }))
            assertEquals(1, absentPrefs.getAllCalls)
            assertEquals(0, absentPrefs.editCalls)
            assertEquals(1, absentExecutor.submissions.get())
            // The bounded read COMPLETED (not a timeout): one coherent generation
            // resolved the absent runtime key to "no explicit opt-out" (true).
            val observedAbsent = absentSnapshot.get()
            assertNotNull("bounded read must return a snapshot, not time out", observedAbsent)
            assertTrue("absent runtime key is honored as no explicit opt-out",
                observedAbsent!!.coherent && observedAbsent.runtimeEnabled == true)
        } finally {
            absentExecutor.shutdownNow()
        }

        // A corrupt runtime key is NOT absence. One coherent snapshot fails
        // closed, so even the same authorized legacy install gets OFF.
        val failedExecutor = CountingExecutor(newExecutor())
        val failedPrefs = CountingPrefs(authorizedLegacy + (runtimeKey to 1L))
        val failedSnapshot = AtomicReference<BbcCoherentPrefsSnapshot?>()
        try {
            assertFalse("a runtime read failure must close even an authorized legacy install",
                finalGate(failedPrefs, failedExecutor,
                    timeoutMs = GENEROUS_READ_TIMEOUT_MS,
                    snapshotSpy = { failedSnapshot.set(it) }))
            assertEquals(1, failedPrefs.getAllCalls)
            assertEquals(0, failedPrefs.editCalls)
            assertEquals(1, failedExecutor.submissions.get())
            // With the generous timeout the bounded read COMPLETED, so the
            // snapshot is genuinely fail-closed because the present runtime key
            // has the WRONG TYPE (Long, not Boolean) -- NOT because of a timeout,
            // which would have produced a null snapshot instead.
            assertEquals("wrong-type runtime must yield the fail-closed snapshot, not a timeout null",
                BbcCoherentPrefsSnapshot.failClosed(), failedSnapshot.get())
        } finally {
            failedExecutor.shutdownNow()
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
            assertFalse(finalGate(prefs, executor, timeoutMs = GENEROUS_READ_TIMEOUT_MS))
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
