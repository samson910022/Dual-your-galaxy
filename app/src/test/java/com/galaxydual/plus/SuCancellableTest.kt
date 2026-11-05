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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
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
}
