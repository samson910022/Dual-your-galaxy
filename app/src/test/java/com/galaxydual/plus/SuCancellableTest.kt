// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// P2-1 (issue #24 Phase B): JVM tests for the cancellable su capture path.
// The old blocking captureProcess held proc.waitFor(timeoutMs) without a
// cancellation checkpoint, so a selectChannel latest-wins cancel while a su
// probe/write was in flight kept the loser on opMutex until the full
// SU_EXEC_TIMEOUT_MS (60 s) expired -- the winner could not take over. The
// new captureProcessCancellable polls waitFor(SU_WAIT_POLL_MS) with
// ensureActive() between slices, so cancel ends the client wait within one
// poll (~50 ms) with CancellationException (queue-collapse), while
// destroyForcibly stays best-effort local kill only (NOT remote preemption:
// post-spawn cancel is unknown-after-send, never definite -- Phase A
// transportUnknown semantics preserved by CE propagation aborting the loser
// before refresh).
package com.galaxydual.plus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.galaxydual.core.TRUNCATION_MARKER
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SuCancellableTest {
    // Probe (`id -u`) uses the short probe budget; writes keep the full 60 s.
    @Test fun suTimeoutFor_probeVsWrite() {
        assertEquals(SU_PROBE_TIMEOUT_MS, suTimeoutFor("id -u"))
        assertEquals(SU_PROBE_TIMEOUT_MS, suTimeoutFor("  id -u  "))
        assertEquals(SU_EXEC_TIMEOUT_MS, suTimeoutFor("pm list users"))
        assertEquals(
            SU_EXEC_TIMEOUT_MS,
            suTimeoutFor("pm install-existing --user 95 com.example.app"),
        )
        assertEquals(SU_EXEC_TIMEOUT_MS, suTimeoutFor("pm uninstall --user 95 com.example.app"))
    }

    // Steady path unchanged: fast command still succeeds via the cancellable helper.
    @Test fun cancellable_echoSucceeds() = runBlocking {
        val res = captureProcessCancellable(arrayOf("sh", "-c", "echo hello-dual"), timeoutMs = 5_000L)
        assertEquals(0, res.exit)
        assertEquals("hello-dual\n", res.out)
    }

    // Timeout path unchanged: abandoning the wait still maps to timedOut
    // (unknown-after-send, read-only verify) -- never a definite failure.
    @Test fun cancellable_timeoutCarriesTimedOutFlag() = runBlocking {
        val res = captureProcessCancellable(arrayOf("sh", "-c", "sleep 5"), timeoutMs = 200L)
        assertTrue("timeout reply must carry timedOut, got: $res", res.timedOut)
        assertTrue(
            "timeout err must hit the isServiceTimeoutText backstop, got: \"${res.err}\"",
            res.err.startsWith("timeout after "),
        )
    }

    // Latest-wins handover at the su layer: cancelling a hung su wait ends
    // the CALLER at once with CancellationException instead of holding it
    // to the 30 s deadline. The 2 s bound is deliberately loose to avoid
    // flakiness on slow CI; it still falsifies the old blocking design where
    // the same cancel would have wedged the caller for the full timeout
    // (UncancellableBlockingFake对照: old proc.waitFor(60_000) ignored
    // coroutine cancel, so the winner waited out the deadline on opMutex).
    // Cancel is post-spawn unknown by construction (spawn already succeeded,
    // remote pm may have executed): the helper destroys best effort and
    // rethrows CE so the loser aborts before refresh -- never synthesized
    // into a definite result. Thread-interrupt result-forms keep Phase A
    // transportUnknown (see captureProcess + SuCmdGateway.run catch).
    @Test fun cancellable_cancelThrowsAtOnceWithDestroy() = runBlocking {
        // Dispatchers.Default: the waiter must run off the runBlocking event
        // loop, which is about to block in delay() below.
        val job = async(Dispatchers.Default) {
            captureProcessCancellable(arrayOf("sh", "-c", "sleep 30"), timeoutMs = 30_000L)
        }
        // Spawn is synchronous inside the helper (exec returns before the
        // first poll), so a short settle guarantees the wait is in flight.
        delay(300L)
        val startNs = System.nanoTime()
        job.cancel()
        try {
            job.await()
            fail("cancelled su wait must throw CancellationException")
        } catch (_: CancellationException) {
            // Expected: caller stops waiting at once; the local proc is
            // destroyed best effort, the remote side is untouched.
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue(
            "cancelled su wait must end at once, not at the deadline (elapsed=${elapsedMs}ms)",
            elapsedMs < 2_000L,
        )
    }

    // Handover: after the old hung wait is cancelled, a fresh call proceeds
    // immediately instead of wedging behind it -- the selectChannel
    // latest-wins winner takes over without waiting out the deadline.
    @Test fun cancellable_cancelThenFastCall_handsOverQuickly() = runBlocking {
        val hung = async(Dispatchers.Default) {
            captureProcessCancellable(arrayOf("sh", "-c", "sleep 30"), timeoutMs = 30_000L)
        }
        delay(300L)
        hung.cancel()
        try {
            hung.await()
        } catch (_: CancellationException) {
            // Expected: the old wait is gone.
        }
        val startNs = System.nanoTime()
        val res = captureProcessCancellable(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000L)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertEquals("handover call must complete", "ok\n", res.out)
        assertTrue("handover must be immediate (elapsed=${elapsedMs}ms)", elapsedMs < 2_000L)
    }

    // Phase G1 P1: a rogue Process.waitFor throwing post-spawn (e.g. a fake
    // or OEM Process impl throwing IllegalStateException) must NOT escape
    // to SuCmdGateway.run's generic catch as definite -- spawn already
    // succeeded, so the remote pm may have executed. Fail closed to
    // transportUnknown via the in-process flag (text keeps the
    // "su failed post-spawn: " prefix for greppability).
    private class PostSpawnThrowingProcess : Process() {
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = throw IllegalStateException("boom")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = throw IllegalStateException("boom")
        override fun exitValue(): Int = throw IllegalStateException("boom")
        override fun destroy() {}
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
        override fun isAlive(): Boolean = true
    }

    @Test fun postSpawnWaitThrows_mapsToTransportUnknownNotDefinite() = runBlocking {
        val fake = PostSpawnThrowingProcess()
        val gw = SuCmdGateway { _ -> fake }
        val res = gw.run("pm install-existing --user 95 com.example.app")
        assertTrue("post-spawn throw must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("post-spawn throw must not carry timedOut (transport loss, not deadline), got: $res", res.timedOut)
        assertTrue("post-spawn throw must be truncated (partial capture), got: $res", res.truncated)
        assertTrue(
            "post-spawn throw err must keep the post-spawn prefix, got: \"${res.err}\"",
            res.err.startsWith("su failed post-spawn: "),
        )
        assertTrue("post-spawn throw err must keep the cause, got: \"${res.err}\"", res.err.contains("boom"))
        assertTrue("post-spawn throw must not claim definite (exit trusted), got: $res", res.exit != 0)
        assertTrue("post-spawn throw must destroy best effort", fake.destroyed)
    }

    // Phase G1 P1 (destroy variant): timeout already abandoned the wait, and
    // the best-effort destroy itself throws post-spawn (rogue Process impl).
    // Best-effort means the throw must not mask the timeout result: the
    // reply stays timedOut=true (unknown-after-send, read-only reconcile),
    // never definite, never an escape. Short budget keeps the JVM test fast.
    private class DestroyThrowingProcess : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = 0
        override fun destroy() {}
        override fun destroyForcibly(): Process = throw IllegalStateException("destroy boom")
        override fun isAlive(): Boolean = false
    }

    @Test fun postSpawnDestroyThrows_doesNotMaskTimeout() = runBlocking {
        val res = captureProcessCancellable(
            arrayOf("su", "-c", "pm install-existing --user 95 com.example.app"),
            timeoutMs = 200L,
            execFn = { _ -> DestroyThrowingProcess() },
        )
        assertTrue("destroy throw must not mask the timeout, got: $res", res.timedOut)
        assertTrue("timeout reply must not claim definite, got: $res", res.exit != 0)
        assertTrue("timeout err must keep the deadline prefix, got: \"${res.err}\"", res.err.startsWith("timeout after "))
    }

    // Phase G1 P1 (exitValue variant): the wait reports exit but reading the
    // code throws post-spawn (process vanished between wait and read).
    // Unknown-after-send by construction -- the remote pm may have executed.
    private class ExitValueThrowingProcess : Process() {
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = throw IllegalStateException("exit boom")
        override fun destroy() {}
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
        override fun isAlive(): Boolean = false
    }

    @Test fun postSpawnExitValueThrows_mapsToTransportUnknownNotDefinite() = runBlocking {
        val fake = ExitValueThrowingProcess()
        val gw = SuCmdGateway { _ -> fake }
        val res = gw.run("pm install-existing --user 95 com.example.app")
        assertTrue("exitValue throw must carry transportUnknown, got: $res", res.transportUnknown)
        assertFalse("exitValue throw must not carry timedOut, got: $res", res.timedOut)
        assertTrue("exitValue throw must not claim definite, got: $res", res.exit != 0)
        assertTrue("exitValue throw err must keep the cause, got: \"${res.err}\"", res.err.contains("exit boom"))
        assertTrue("exitValue throw must destroy best effort", fake.destroyed)
    }

    // Phase G1 cancel-semantics lock: CE thrown by the spawn seam (or by the
    // cancellable waits) must still propagate -- never swallowed into a
    // CmdResult by the new post-spawn generic catch.
    @Test fun postSpawn_cancellationStillPropagates() = runBlocking {
        val gw = SuCmdGateway { _ -> throw CancellationException("cancel-seam") }
        try {
            gw.run("id -u")
            fail("CancellationException from spawn must propagate, not map to a CmdResult")
        } catch (e: CancellationException) {
            assertEquals("cancel-seam", e.message)
        }
    }

    // Phase A2 P2-1 (PR #40 Re-review): child holds stdout/stderr FDs while
    // the wrapper is already dead. destroyForcibly only kills the local `su`
    // wrapper; a `su -c`-spawned child (e.g. pm) inheriting the pipe FDs keeps
    // the pipes open, so without explicit close() the two daemon drains block
    // in read() forever waiting for an EOF that never comes (2 leaked threads
    // per cancel). Cleanup must close the local pipe ends (促使 read 返回/EOF)
    // and bound the drain wait (SU_CANCEL_DRAIN_MS, cancellable, never wedging
    // handover), so repeated cancels converge instead of accumulating.
    // Fault injection: fake Process whose wait reports wrapper exit after
    // destroy but whose streams never EOF on their own (simulated child-held
    // pipes that only release on local close()).
    // Scope note: this fake verifies reclaim wiring (cleanup calls close on
    // both local pipe ends + bounded join), NOT OS-level close wake-up
    // semantics (a real FileInputStream stuck in native read is not guaranteed
    // to wake on close; HangingInputStream here is a close-pollable model that
    // returns EOF once closed).
    private class HangingInputStream : java.io.InputStream() {
        @Volatile private var closed = false
        val isClosed: Boolean get() = closed
        override fun read(): Int {
            while (!closed) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("interrupted")
                }
            }
            return -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            while (!closed) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("interrupted")
                }
            }
            return -1
        }
        override fun close() {
            closed = true
        }
    }

    private class ChildHoldsPipeProcess : Process() {
        val stdout = HangingInputStream()
        val stderr = HangingInputStream()
        @Volatile var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): java.io.InputStream = stdout
        override fun getErrorStream(): java.io.InputStream = stderr
        override fun waitFor(): Int {
            while (!destroyed) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout)
            while (System.currentTimeMillis() < deadlineMs) {
                if (destroyed) return true
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
            return destroyed
        }
        override fun exitValue(): Int {
            if (!destroyed) throw IllegalThreadStateException("not exited")
            return 1
        }
        override fun destroy() {}
        override fun destroyForcibly(): Process {
            // Only the wrapper dies; the simulated child keeps the pipe FDs
            // open (streams stay hanging until cleanup closes them).
            destroyed = true
            return this
        }
        override fun isAlive(): Boolean = !destroyed
    }

    private fun aliveCaptureDrains(): Int =
        Thread.getAllStackTraces().keys.count { t ->
            t.isAlive && t.isDaemon && (t.name == "capture-stdout" || t.name == "capture-stderr")
        }

    @Test fun cancelWithChildHoldingPipe_doesNotAccumulateDrainThreads() = runBlocking {
        // Settle leftovers from earlier tests so the final count belongs to
        // this fault injection, not to prior real-process drains.
        var spins = 0
        while (aliveCaptureDrains() > 0 && spins < 50) {
            delay(100L)
            spins++
        }
        // Repeated cancels: each spawns 2 drains blocked on child-held pipes.
        // Keep every fake so we can prove cleanup closed its pipes directly
        // (deterministic) in addition to the global thread-count convergence
        // below (which tolerates one foreign lingering drain under full-suite
        // load, hence <= 4 rather than <= 2 -- old code leaves ~16 here).
        // Thread safety: execFn runs on the Dispatchers.Default waiter while
        // the test body reads after each join; CopyOnWriteArrayList removes the
        // cross-thread data race a plain mutableListOf would have here.
        val fakes = CopyOnWriteArrayList<ChildHoldsPipeProcess>()
        var maxElapsed = 0L
        repeat(8) { idx ->
            val job = async(Dispatchers.Default) {
                captureProcessCancellable(
                    arrayOf("su", "-c", "pm install-existing --user 95 com.example.app"),
                    timeoutMs = 30_000L,
                    execFn = { _ -> ChildHoldsPipeProcess().also { fakes += it } },
                )
            }
            // Let spawn + drains start and the wait go in flight.
            delay(200L)
            val startNs = System.nanoTime()
            job.cancel()
            try {
                job.await()
                fail("cancelled capture must throw CE (iter $idx)")
            } catch (_: CancellationException) {
                // Expected: caller released at once, wrapper destroyed best
                // effort, remote child untouched (unknown-after-send).
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            if (elapsedMs > maxElapsed) maxElapsed = elapsedMs
            assertTrue(
                "cancel handover must stay fast with child-held pipes (iter $idx elapsed=${elapsedMs}ms)",
                elapsedMs < 2_000L,
            )
        }
        // Cancel-path joins abort at once (already cancelled; 600 ms is the
        // non-cancel cap), so 1 s is generous margin for scheduling jitter.
        assertTrue(
            "worst cancel return must stay under 1 s with child-held pipes (maxElapsed=${maxElapsed}ms)",
            maxElapsed < 1_000L,
        )
        // Deterministic: every cancel must have closed both local pipe ends,
        // otherwise a surviving child holding the FDs pins the drains forever.
        assertEquals("every child-holds-pipe fake must be observed", 8, fakes.size)
        fakes.forEachIndexed { idx, fake ->
            assertTrue("cancel must close stdout pipe (iter $idx)", fake.stdout.isClosed)
            assertTrue("cancel must close stderr pipe (iter $idx)", fake.stderr.isClosed)
        }
        // Bounded convergence: close() above lets the blocked reads
        // return/EOF so drains die async instead of accumulating 2 per cancel
        // (old code would leave ~16 alive here).
        // Primary verdict is the deterministic isClosed check above; the
        // thread count below stays a weak backstop (tolerates foreign drains
        // under full-suite load, hence <= 4 rather than <= 2).
        val deadlineMs = System.currentTimeMillis() + 5_000L
        var alive = aliveCaptureDrains()
        while (alive > 4 && System.currentTimeMillis() < deadlineMs) {
            delay(100L)
            alive = aliveCaptureDrains()
        }
        assertTrue(
            "daemon drain threads must converge after child-holds-pipe cancels (alive=$alive, expected<=4 not 16)",
            alive <= 4,
        )
        // Post-reclaim health: a fresh call right after the 8 child-held
        // cancels must still succeed fast (later calls unaffected).
        val nextStartNs = System.nanoTime()
        val next = captureProcessCancellable(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000L)
        val nextElapsedMs = (System.nanoTime() - nextStartNs) / 1_000_000L
        assertEquals("call after child-held cancels must succeed", "ok\n", next.out)
        assertTrue(
            "call after child-held cancels must be fast (elapsed=${nextElapsedMs}ms)",
            nextElapsedMs < 2_000L,
        )
    }

    // Phase A3 P2-2 (PR #40 Re-review): fake whose timed waitFor slices block
    // like a real hung `su` (sleeps the requested slice, reports not-exited)
    // while the zero-time poll reports the truth without blocking. Lets the
    // tests below drive the injected monotonic clock while real time only
    // paces the poll slices.
    private class NeverExitingProcess : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = throw UnsupportedOperationException("no-arg waitFor unused")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (timeout <= 0) return false
            try {
                Thread.sleep(minOf(unit.toMillis(timeout), SU_WAIT_POLL_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            return false
        }
        override fun exitValue(): Int = throw IllegalThreadStateException("not exited")
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = true
    }

    // Phase A3 P2-2 regression (falsifiable): the helpers measure elapsed
    // time on the injectable monotonic clock only (mono-only design, never a
    // System.currentTimeMillis wall deadline). A previous version of these
    // tests moved a fakeWallMs variable that production never reads (decoy:
    // helpers only take a monoMs parameter), so a wall-clock implementation
    // passed vacuously -- the decoy is therefore removed, not moved.
    // Falsifiability instead comes from freezing mono while REAL time runs
    // past the budget: a wall-clock implementation (`deadline =
    // currentTimeMillis + timeout`) would expire after ~300 ms of real time
    // regardless of the frozen mono, failing the still-waiting assertion
    // below; the mono implementation stays waiting until mono advances.
    // Margin is deliberately loose (1 s real vs 300 ms budget, >3x) so CI
    // jitter cannot flip the verdict; the 5 s withTimeoutOrNull ceiling only
    // bounds hangs.
    @Test fun awaitExitFrozenMonoDoesNotExpire_monoAdvanceExpiresImmediately() = runBlocking {
        val monoMs = AtomicLong(0L) // frozen: never advances until the second phase
        val proc = NeverExitingProcess()
        val done = async(Dispatchers.Default) {
            cancellableAwaitExit(proc, timeoutMs = 300L, monoMs = { monoMs.get() })
        }
        // Real time (1 s) runs well past the 300 ms budget while mono stays 0.
        // A wall-clock implementation would have timed out at ~300 ms real;
        // the mono implementation must still be waiting.
        delay(1_000L)
        assertFalse(
            "frozen mono must not expire even after 1 s real > 300 ms budget (wall impl would fail here)",
            done.isCompleted,
        )
        // Advance mono past the budget: expiry must now be immediate (one
        // poll slice, well under the loose 2 s bound / 5 s ceiling).
        monoMs.set(300L)
        val startNs = System.nanoTime()
        val finished = withTimeoutOrNull(5_000L) { done.await(); true } ?: false
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("mono advance past budget must expire the wait", finished)
        assertTrue(
            "mono expiry must be immediate (elapsed=${elapsedMs}ms)",
            elapsedMs < 2_000L,
        )
        assertFalse("hung proc reports timeout (false), not exit", done.await())
    }

    // Same frozen-mono falsifiability for the drain-join budget: the join
    // returns once monotonic elapsed reaches the budget, not after real time
    // passes it. A wall-clock join would return at ~300 ms real despite the
    // frozen mono and fail the still-waiting assertion; the mono join stays
    // until mono advances.
    @Test fun joinFrozenMonoDoesNotExpire_monoAdvanceExpiresImmediately() = runBlocking {
        val monoMs = AtomicLong(0L) // frozen: never advances until the second phase
        val sleeper = thread(isDaemon = true, name = "p22-fake-drain") {
            try {
                Thread.sleep(30_000L)
            } catch (_: InterruptedException) {
                // Expected on cleanup below.
            }
        }
        try {
            val job = async(Dispatchers.Default) {
                cancellableJoin(sleeper, budgetMs = 300L, monoMs = { monoMs.get() })
            }
            // Real time (1 s) runs well past the 300 ms budget while mono stays 0.
            delay(1_000L)
            assertFalse(
                "frozen mono must not expire the join even after 1 s real > 300 ms budget (wall impl would fail here)",
                job.isCompleted,
            )
            // Advance mono past the budget: the join must return at once
            // while the thread is still alive (caller re-checks isAlive).
            monoMs.set(300L)
            val startNs = System.nanoTime()
            val finished = withTimeoutOrNull(5_000L) { job.await(); true } ?: false
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue("mono advance past budget must expire the join", finished)
            assertTrue(
                "mono join expiry must be immediate (elapsed=${elapsedMs}ms)",
                elapsedMs < 2_000L,
            )
            assertTrue(
                "budget expiry returns while the thread is alive (caller re-checks isAlive)",
                sleeper.isAlive,
            )
        } finally {
            sleeper.interrupt()
        }
    }

    // H1 drain budget (P2 backpressure): 16 slots = 8 stuck captures. When
    // exhausted, spawnDrain returns null and the capture still returns
    // (never wedged) fail-closed with truncated=true via the READ_ERROR
    // defaults; after release, normal drains recover (echo, truncated=false).
    @Test fun drainBudgetExhausted_failClosedWithoutWedging_thenRecovers() = runBlocking {
        // Base may be non-zero: earlier tests can park stuck drains on
        // child-held pipes (close does not unblock a native read held by a
        // surviving `sleep` child) -- that is exactly why the H1 cap exists.
        // So fill only the REMAINING slots instead of assuming a zero start.
        // Brief settle for async releases, then record the base.
        delay(500L)
        val base = activeDrainCount.get()
        assertTrue("drain count must never exceed cap at start (base=$base)", base <= MAX_ACTIVE_DRAINS)
        val needed = MAX_ACTIVE_DRAINS - base
        repeat(needed) { idx ->
            assertTrue("slot $idx must acquire when budget free", tryAcquireDrainSlot())
        }
        try {
            assertEquals(MAX_ACTIVE_DRAINS, activeDrainCount.get())
            assertFalse("extra acquire must fail when budget exhausted", tryAcquireDrainSlot())
            // One capture while exhausted: must still return, fail closed.
            val degraded = withTimeoutOrNull(10_000L) {
                captureProcessCancellable(arrayOf("sh", "-c", "echo hi"), timeoutMs = 5_000L)
            }
            assertTrue("exhausted capture must still return (not wedged)", degraded != null)
            val res = degraded!!
            assertTrue("exhausted capture must be truncated (fail closed), got: $res", res.truncated)
            assertTrue("exhausted capture must keep exit 0 with marker (budget must not pollute exit), got: $res", res.exit == 0 && res.out.contains(TRUNCATION_MARKER))
            assertTrue(
                "drain count must never exceed cap (count=${activeDrainCount.get()})",
                activeDrainCount.get() <= MAX_ACTIVE_DRAINS,
            )
        } finally {
            repeat(needed) { releaseDrainSlot() }
        }
        assertEquals(base, activeDrainCount.get())
        // Recovered: normal drain succeeds with clean EOF, truncated=false.
        val ok = withTimeoutOrNull(10_000L) {
            captureProcessCancellable(arrayOf("sh", "-c", "echo hi"), timeoutMs = 5_000L)
        }
        assertTrue("recovered capture must return", ok != null)
        assertEquals(0, ok!!.exit)
        assertEquals("hi\n", ok.out)
        assertFalse("recovered capture must not be truncated, got: $ok", ok.truncated)
        assertEquals(base, activeDrainCount.get())
    }
}
