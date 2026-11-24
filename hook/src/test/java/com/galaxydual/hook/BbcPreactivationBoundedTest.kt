// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
// Real Binder latency / device behavior DEVICE-UNVERIFIED (docs/ACCEPTANCE §6);
// these JVM wall-time pins are safety bounds, not device go/no-go claims.
package com.galaxydual.hook

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// PR5 Phase B1 (#80) / PR #90 Phase A (P0): JVM pins for the bounded pre-
// activation pref read, the READ-ONLY decision, and the final BBC effect AND.
// Framework-free (JUnit4 + java.util.concurrent); the executor is the real
// zero-queue factory, so timeout / throw / contention exercise the real
// wall-time path (mirrors BbcGateBoundedWaitTest for the legacy opt-out read).
class BbcPreactivationBoundedTest {

    private fun newExecutor(name: String): ThreadPoolExecutor =
        PolicyRefreshBudget.newPolicyRefreshExecutor(name)

    private val failClosed = BbcPreactivationPrefs(null, null, null)

    private class FakeRemotePrefs(
        private val values: Map<String, Any?>,
    ) : SharedPreferences {
        var editCalls = 0
            private set

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
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

    // ---- bounded reader: timeout / throw / contention -> no snapshot ----

    @Test
    fun blockingReader_returnsNullWithinCeiling() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = newExecutor("test-bbc-pre-ceiling")
        try {
            val result = readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS) // far past the ceiling
                BbcPreactivationPrefs(true, null, null)
            }
            assertTrue("reader must have entered", entered.await(1, TimeUnit.SECONDS))
            assertNull(result) // timeout -> no usable snapshot, not the reader's opt-in
        } finally {
            release.countDown() // release the stuck worker: no leaked thread
            executor.shutdownNow()
        }
    }

    @Test
    fun throwingReader_returnsNull() {
        val executor = newExecutor("test-bbc-pre-throw")
        try {
            val result = readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                throw IllegalStateException("simulated IPC failure")
            }
            assertNull(result)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun zeroTimeout_neverSubmits() {
        val executor = newExecutor("test-bbc-pre-zero")
        val called = AtomicBoolean(false)
        try {
            assertNull(
                readBbcPreactivationBounded(executor, 0L) {
                    called.set(true)
                    BbcPreactivationPrefs(true, null, null)
                },
            )
            assertFalse(called.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun executorContention_returnsNullWithoutCallingReader() {
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = newExecutor("test-bbc-pre-reject")
        val called = AtomicBoolean(false)
        try {
            executor.execute {
                occupied.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(occupied.await(1, TimeUnit.SECONDS))

            val startMs = System.nanoTime() / 1_000_000L
            val result = readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                called.set(true)
                BbcPreactivationPrefs(true, null, null)
            }
            val elapsedMs = System.nanoTime() / 1_000_000L - startMs

            assertNull(result)
            assertFalse(called.get())
            assertTrue("elapsed=$elapsedMs must be < 500ms", elapsedMs < 500L)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fastSnapshot_returnsValue() {
        val executor = newExecutor("test-bbc-pre-fast")
        val snapshot = BbcPreactivationPrefs(true, null, null)
        try {
            assertEquals(snapshot, readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) { snapshot })
        } finally {
            executor.shutdownNow()
        }
    }

    // ---- P0 read-only decision (no marker writer exists any more) ----

    @Test
    fun preUpgradeClassificationWithTrustedMarkerOpens() {
        // REWRITTEN: a draft alone never opened the gate. The App-owned durable
        // classification + trusted marker is the only legacy basis.
        val open = bbcPreactivationDecisionOpen(
            compileTimeGate = true,
            prefs = BbcPreactivationPrefs(
                null,
                PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                true,
            ),
        )
        assertTrue(open)
    }

    @Test
    fun preUpgradeClassificationWithoutMarkerStaysClosed() {
        assertFalse(
            bbcPreactivationDecisionOpen(
                compileTimeGate = true,
                prefs = BbcPreactivationPrefs(
                    null,
                    PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    null,
                ),
            ),
        )
    }

    @Test
    fun failClosedSnapshot_closes() {
        assertFalse(bbcPreactivationDecisionOpen(compileTimeGate = true, prefs = failClosed))
    }

    @Test
    fun explicitOptOut_closesEvenForMigratedClassification() {
        assertFalse(
            bbcPreactivationDecisionOpen(
                compileTimeGate = true,
                prefs = BbcPreactivationPrefs(
                    false,
                    PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    true,
                ),
            ),
        )
    }

    // ---- final effect AND: compile-time × preactivation × hybrid ----

    @Test
    fun compileTimeClosed_shortCircuitsBothStages() {
        val preCalled = AtomicBoolean(false)
        val hybridCalled = AtomicBoolean(false)
        val open = bbcEffectGateOpen(
            compileTimeGate = false,
            preactivationOpen = { preCalled.set(true); true },
            hybridOpen = { hybridCalled.set(true); true },
        )
        assertFalse(open)
        assertFalse(preCalled.get()) // zero pref reads / zero marker writes
        assertFalse(hybridCalled.get())
    }

    @Test
    fun preactivationClosed_skipsHybrid() {
        val hybridCalled = AtomicBoolean(false)
        val open = bbcEffectGateOpen(
            compileTimeGate = true,
            preactivationOpen = { false },
            hybridOpen = { hybridCalled.set(true); true },
        )
        assertFalse(open)
        assertFalse(hybridCalled.get())
    }

    @Test
    fun bothOpen_returnsHybridDecision() {
        assertTrue(
            bbcEffectGateOpen(
                compileTimeGate = true,
                preactivationOpen = { true },
                hybridOpen = { true },
            ),
        )
        assertFalse(
            bbcEffectGateOpen(
                compileTimeGate = true,
                preactivationOpen = { true },
                hybridOpen = { false },
            ),
        )
    }

    @Test
    fun stageThrows_failClosed() {
        assertFalse(
            bbcEffectGateOpen(
                compileTimeGate = true,
                preactivationOpen = { throw IllegalStateException("read failed") },
                hybridOpen = { true },
            ),
        )
        assertFalse(
            bbcEffectGateOpen(
                compileTimeGate = true,
                preactivationOpen = { true },
                hybridOpen = { throw IllegalStateException("clock failed") },
            ),
        )
    }

    @Test
    fun timeoutThroughEffectGate_failsClosedWithNoWrites() {
        // End-to-end shape of the production bbcGateOpen lambda: the bounded
        // preactivation snapshot times out -> no decision, no writes at all,
        // hybrid stage is skipped, overall gate CLOSED.
        val release = CountDownLatch(1)
        val executor = newExecutor("test-bbc-pre-effect-timeout")
        try {
            val open = bbcEffectGateOpen(
                compileTimeGate = true,
                preactivationOpen = {
                    val snapshot = readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                        release.await(2, TimeUnit.SECONDS)
                        BbcPreactivationPrefs(true, null, null)
                    }
                    snapshot != null && bbcPreactivationDecisionOpen(true, snapshot)
                },
                hybridOpen = { true },
            )
            assertFalse(open)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun genuineUpgradeWithRuntimeOptOut_finalEffectClosed() {
        // HIGH-3: production boundary. The owner App has written a genuine
        // upgrade's durable classification + marker, so the preactivation stage
        // legitimately OPENS. But bbc_injection_enabled=false is ANDed by the
        // REAL hybrid gate, so the FINAL effect must stay CLOSED. No hand-built
        // booleans: both stages use the production readers/helpers.
        val prefs = FakeRemotePrefs(
            mapOf(
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                    PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
                PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.old"),
                PolicySource.POLICY_KEY_EXTRA_VERSION to 7L,
                PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to false,
            ),
        )

        val preactivationOpen = {
            bbcPreactivationDecisionOpen(true, BbcPreactivationHookReader.read(prefs))
        }
        assertTrue("genuine upgrade opens the preactivation stage", preactivationOpen())

        val hybridOpen = {
            bbcInjectionGateOpen(
                compileTimeGate = true,
                nowMs = { 1_000L },
                stickyState = AtomicReference<Long?>(null),
            ) {
                readBbcInjectionEnabled(object : BbcInjectionPrefsView {
                    override fun getBoolean(key: String, defValue: Boolean): Boolean =
                        prefs.getBoolean(key, defValue)
                })
            }
        }
        assertFalse("explicit runtime opt-out wins", hybridOpen())
        assertFalse(
            "final effect = preactivation AND hybrid; runtime opt-out wins",
            bbcEffectGateOpen(
                compileTimeGate = true,
                preactivationOpen = preactivationOpen,
                hybridOpen = hybridOpen,
            ),
        )
        assertEquals("zero writes on the whole gate path", 0, prefs.editCalls)
    }
}
