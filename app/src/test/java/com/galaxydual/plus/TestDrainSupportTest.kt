// PR #60 round 2 — pins the drain semantics that TestDrainSupport encodes:
//   A. strict BY DEFAULT: an incomplete drain (interrupt-ignoring IO child
//      outliving the bounded join) THROWS IllegalStateException before the
//      caller can resetMain().
//   B. explicit opt-out (failOnIncomplete = false): same hanging child,
//      same bounded join, drain returns normally with only its System.err line.
//   C. isolation: a hanging coordinator does not stop the drain from closing
//      the healthy one; the strict throw reports a nonzero pending count.
// No Dispatchers.Main involvement — plain real Dispatchers.IO + AtomicBoolean
// sleep-poll child, which is the accepted pattern for this fixture. Total
// runtime budget ~1.5 s (three bounded 500 ms drains).
//
// VM phase of the drain is exercised by symmetry with the coordinator phase
// (same joinBounded implementation and identical vmReason plumbing);
// constructing a DualViewModel to hang a viewModelScope child was deliberately
// avoided — the pins target the drain's semantics, not VM wiring.
package com.galaxydual.plus

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TestDrainSupportTest {

    // The child must be CONFIRMED parked in its sleep loop before the drain
    // runs: scope.cancel() does not interrupt Thread.sleep, so a confirmed-started
    // child always outlives the cancel and the bounded join deadline. Launching
    // without the latch races cancel() against first IO dispatch — a child
    // cancelled before it starts never enters the loop and the scope completes
    // immediately.
    private fun newHangingChild(
        coord: PolicyTxCoordinator,
        release: AtomicBoolean,
    ): Job {
        val started = CountDownLatch(1)
        val child = coord.scope.launch(Dispatchers.IO) {
            started.countDown()
            // Interrupt-ignoring on purpose: never checks Thread.interrupted, so
            // scope.cancel() alone cannot stop this child — only the release flag.
            while (!release.get()) {
                Thread.sleep(10)
            }
        }
        try {
            assertTrue(
                "hanging child must be parked before the drain runs",
                started.await(5, TimeUnit.SECONDS),
            )
        } catch (e: Throwable) {
            // Park-confirmation failed: release the interrupt-ignoring child
            // before rethrowing so a setup failure never parks it forever.
            release.set(true)
            throw e
        }
        return child
    }

    private fun releaseAndJoin(
        release: AtomicBoolean,
        child: Job?,
        vararg coords: PolicyTxCoordinator,
    ) {
        release.set(true)
        child?.let { runBlocking { withTimeout(2_000) { it.join() } } }
        for (c in coords) {
            try {
                c.close()
            } catch (_: Exception) {
                // Idempotent close; best effort.
            }
        }
    }

    @Test
    fun drainBeforeResetMain_strictDefault_throwsBeforeResetMain() {
        val release = AtomicBoolean(false)
        val coord = PolicyTxCoordinator()
        var child: Job? = null
        try {
            child = newHangingChild(coord, release)
            try {
                // No failOnIncomplete argument: this pins the strict default.
                TestDrainSupport.drainBeforeResetMain(emptyList(), listOf(coord), timeoutMs = 500)
                fail("expected IllegalStateException for an incomplete drain")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "message should report pending jobs: ${e.message}",
                    e.message?.contains("pending") == true,
                )
            }
        } finally {
            releaseAndJoin(release, child, coord)
        }
    }

    @Test
    fun drainBeforeResetMain_explicitOptOut_returnsNormally() {
        val release = AtomicBoolean(false)
        val coord = PolicyTxCoordinator()
        var child: Job? = null
        try {
            child = newHangingChild(coord, release)
            // Explicit opt-out: the suite tolerates the bounded failure and
            // takes the System.err line instead of the exception.
            TestDrainSupport.drainBeforeResetMain(
                emptyList(),
                listOf(coord),
                timeoutMs = 500,
                failOnIncomplete = false,
            )
        } finally {
            releaseAndJoin(release, child, coord)
        }
    }

    @Test
    fun drainBeforeResetMain_strictDrain_reportsPendingAndClosesHealthyCoordinator() {
        val release = AtomicBoolean(false)
        val hanging = PolicyTxCoordinator()
        val healthy = PolicyTxCoordinator()
        var child: Job? = null
        try {
            child = newHangingChild(hanging, release)
            val error = try {
                TestDrainSupport.drainBeforeResetMain(
                    emptyList(),
                    listOf(hanging, healthy),
                    timeoutMs = 500,
                )
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertNotNull("strict drain must throw for the hanging coordinator", error)
            assertTrue(
                "message should report a nonzero pending count: ${error!!.message}",
                error.message?.contains("pending") == true,
            )
            // The hanging coordinator must not starve the healthy one: the drain
            // closed (and joined) the healthy scope inside the same pass.
            assertTrue(
                "healthy coordinator scope must be closed by the drain",
                healthy.scope.coroutineContext[Job]?.isCompleted == true,
            )
            assertFalse(
                "hanging child must NOT be reported complete",
                hanging.scope.coroutineContext[Job]!!.isCompleted,
            )
        } finally {
            releaseAndJoin(release, child, hanging, healthy)
        }
    }
}
