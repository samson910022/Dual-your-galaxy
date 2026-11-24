// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
// Real Binder latency / device behavior DEVICE-UNVERIFIED (docs/ACCEPTANCE §6);
// these JVM wall-time pins are safety bounds, not device go/no-go claims.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// PR5 Phase B1 (#80): JVM pins for the bounded pre-activation pref read, the
// one-time migration marker write seam, and the final BBC effect AND.
// Framework-free (JUnit4 + java.util.concurrent); the executor is the real
// zero-queue factory, so timeout / throw / contention exercise the real
// wall-time path (mirrors BbcGateBoundedWaitTest for the legacy opt-out read).
class BbcPreactivationBoundedTest {

    private fun newExecutor(name: String): ThreadPoolExecutor =
        PolicyRefreshBudget.newPolicyRefreshExecutor(name)

    private val failClosed = BbcPreactivationPrefs(null, false, null)

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
                BbcPreactivationPrefs(true, false, null)
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
                    BbcPreactivationPrefs(true, false, null)
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
                BbcPreactivationPrefs(true, false, null)
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
        val snapshot = BbcPreactivationPrefs(true, false, null)
        try {
            assertEquals(snapshot, readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) { snapshot })
        } finally {
            executor.shutdownNow()
        }
    }

    // ---- decision + one-time migration marker write seam ----

    @Test
    fun legacyDraftWithoutMarker_opensAndWritesMarkerOnce() {
        var writes = 0
        val open = bbcPreactivationDecisionOpen(
            compileTimeGate = true,
            prefs = BbcPreactivationPrefs(null, true, null),
            writeMigrationMarker = { writes++ },
        )
        assertTrue(open)
        assertEquals(1, writes)
    }

    @Test
    fun failClosedSnapshot_closesAndNeverWritesMarker() {
        var writes = 0
        val open = bbcPreactivationDecisionOpen(
            compileTimeGate = true,
            prefs = failClosed, // any read failure maps here
            writeMigrationMarker = { writes++ },
        )
        assertFalse(open)
        assertEquals(0, writes)
    }

    @Test
    fun explicitOptOut_neverWritesMarker() {
        var writes = 0
        val open = bbcPreactivationDecisionOpen(
            compileTimeGate = true,
            prefs = BbcPreactivationPrefs(false, true, null),
            writeMigrationMarker = { writes++ },
        )
        assertFalse(open)
        assertEquals(0, writes)
    }

    @Test
    fun markerWriterThrow_doesNotChangeTheDecisionAndNeverThrows() {
        val open = bbcPreactivationDecisionOpen(
            compileTimeGate = true,
            prefs = BbcPreactivationPrefs(null, true, null),
            writeMigrationMarker = { throw IllegalStateException("write failed") },
        )
        // The write failure is counted/logged by the production callback; this
        // call's gate result is final and must not be flipped by it.
        assertTrue(open)
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
    fun timeoutThroughEffectGate_failsClosedWithoutMarkerWrite() {
        // End-to-end shape of the production bbcGateOpen lambda: the bounded
        // preactivation snapshot times out -> no decision, no marker write,
        // hybrid stage is skipped, overall gate CLOSED.
        val release = CountDownLatch(1)
        val executor = newExecutor("test-bbc-pre-effect-timeout")
        var writes = 0
        try {
            val open = bbcEffectGateOpen(
                compileTimeGate = true,
                preactivationOpen = {
                    val snapshot = readBbcPreactivationBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                        release.await(2, TimeUnit.SECONDS)
                        BbcPreactivationPrefs(true, false, null)
                    }
                    if (snapshot == null) {
                        false
                    } else {
                        bbcPreactivationDecisionOpen(true, snapshot) { writes++ }
                    }
                },
                hybridOpen = { true },
            )
            assertFalse(open)
            assertEquals(0, writes)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
