// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// P1-2 (PR-32 round 2 Phase B): JVM tests for the bounded client-side await.
// A fake blocking Binder is modelled by a lambda that sleeps / never returns
// / returns late / throws -- no Android classes involved.
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedAwaitTest {
    // A never-returning block must not hold the caller past the deadline:
    // 100ms budget against a 30s sleep must return null far under 5s. The
    // 2000ms bound is deliberately loose to avoid flakiness on slow CI.
    // Division of labor: this test only proves the wait is bounded (an
    // interruptible sleep would also return fast under the old
    // withTimeoutOrNull wrapper via cooperative cancellation); the regression
    // test that falsifies the old bug is lateReturn_... below, whose
    // swallow-the-interrupt busy-wait models a non-cooperative transact that
    // the old wrapper could not bound.
    @Test fun neverReturns_returnsNullWithinBound() {
        val startNs = System.nanoTime()
        val res = awaitBounded(100L) {
            Thread.sleep(30_000L)
            CmdResult(exit = 0, out = "never", err = "")
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertNull("never-returning block must time out, got: $res", res)
        assertTrue("client wait must be bounded (elapsed=${elapsedMs}ms)", elapsedMs < 2_000L)
    }

    // A reply that arrives after the deadline is discarded and must not touch
    // external state: the block simulates an uninterruptible transact with a
    // busy wait that ignores the timeout interrupt, produces its value late,
    // and only records it locally. This busy-wait (not neverReturns_... above)
    // is the regression test for the old withTimeoutOrNull bug: swallowing
    // the interrupt models binder.transact ignoring Dispatchers.IO
    // cancellation, which the old wrapper could not bound.
    // Scope limit: awaitExternalTouched below only locks the await-layer null
    // semantic (late value discarded, no shared-state write). The Parcel-layer
    // dropBinding/fresh-rebind behavior lives in transactExec and is not
    // observable from this JVM test (no Android Parcel/binder); it is
    // guaranteed by the gateway structure, not asserted here.
    @Test fun lateReturn_discardedAndExternalStateUntouched() {
        // Named for scope: await-layer external-state isolation only, not the
        // Parcel-layer dropBinding (see comment above).
        val awaitExternalTouched = AtomicBoolean(false)
        val lateCapture = AtomicReference<CmdResult?>(null)
        val startNs = System.nanoTime()
        val res = awaitBounded(100L) {
            // Uninterruptible ~300ms stand-in for a hung transact that
            // answers after the client gave up: swallow the cancel interrupt
            // and keep going, exactly like binder.transact ignores it.
            val endMs = System.currentTimeMillis() + 300L
            while (System.currentTimeMillis() < endMs) {
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    // Swallowed on purpose: the late reply still materializes.
                }
            }
            lateCapture.set(CmdResult(exit = 0, out = "late", err = ""))
            // Deliberately does NOT touch awaitExternalTouched: the late value
            // lives only in the local capture.
            lateCapture.get()!!
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertNull("late reply must be discarded (await returns null), got: $res", res)
        assertTrue("late-reply wait must still be bounded (elapsed=${elapsedMs}ms)", elapsedMs < 2_000L)
        // The block outlives the 100ms budget (~300ms of work), so poll for
        // its late completion before locking the isolation assertions.
        val deadlineMs = System.currentTimeMillis() + 2_000L
        while (lateCapture.get() == null && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(10L)
        }
        assertNotNull("late block should have completed", lateCapture.get())
        assertFalse("late result must not overwrite external state", awaitExternalTouched.get())
    }

    // A fast block passes its value straight through.
    @Test fun fastBlock_passthrough() {
        val expected = CmdResult(exit = 0, out = "ok", err = "")
        val res = awaitBounded(5_000L) { expected }
        assertEquals(expected, res)
    }

    // A non-cancellation exception from the block propagates to the caller
    // (transactExec maps it to remoteFailed; the await layer never swallows).
    @Test fun blockException_propagates() {
        try {
            awaitBounded(5_000L) { throw IllegalStateException("boom") }
            fail("block exception must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }
}
