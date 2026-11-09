// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase R2-2 (Phase 3 Error-escape): JVM tests that an Error (and,
// on the blocking path, any Exception) escaping post-spawn waitFor/exitValue
// still cleans up (destroyForcibly + pipe closes + drain-slot release) and
// rethrows the SAME instance instead of converting to a CmdResult.
//
// Layering note (blocking vs cancellable):
// - Blocking captureProcess has a single outer catch(Throwable) around
//   waitFor/join/exitValue (SuCmdGateway.kt): Error AND Exception both hit
//   cleanup + rethrow, never a CmdResult. Tests 1-3 therefore call
//   captureProcess DIRECTLY (not SuCmdGateway.run) and assert the throw.
// - Cancellable captureProcessCancellable maps post-spawn Exception to
//   transportUnknown (outer catch Exception) but Error falls through to
//   outer catch Throwable (cleanup + rethrow). Tests 4-6 therefore use
//   Error (AssertionError) for rethrow asserts and call
//   captureProcessCancellable DIRECTLY in runBlocking (never via gw.run).
package com.galaxydual.plus

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ErrorCleanupTest {
    private enum class WaitMode { THROW, RETURN_TRUE, NEVER_EXIT }
    private enum class StreamKind { EOF, HANGING }

    // Same-instance tracked streams: the getter returns the identical object
    // on every call so a close() via any call site (drain use, outer cleanup,
    // proc fallback) is observable on the instance the drain thread reads.
    // @Volatile closed flag + idempotent close() mirror the HangingInputStream
    // fixture in SuCancellableTest (close-pollable model: a blocked read
    // returns EOF once closed).
    private abstract class TrackedStream : java.io.InputStream() {
        @Volatile var closed = false
        override fun close() {
            closed = true
        }
    }

    private class TrackedEofInputStream : TrackedStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    }

    private class TrackedHangingInputStream : TrackedStream() {
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
    }

    // Configurable fake Process: waitFor behavior (throw / exited / hung),
    // stream behavior (EOF / hanging), and exitValue throw switch. Each test
    // constructs its own fake + boom (no shared mutable state between tests).
    private class ErrorThrowingProcess(
        private val waitMode: WaitMode,
        private val streamKind: StreamKind,
        private val boom: Throwable,
        private val exitThrows: Boolean = false,
    ) : Process() {
        @Volatile var destroyed = false

        val stdout: TrackedStream =
            if (streamKind == StreamKind.EOF) TrackedEofInputStream() else TrackedHangingInputStream()
        val stderr: TrackedStream =
            if (streamKind == StreamKind.EOF) TrackedEofInputStream() else TrackedHangingInputStream()

        val stdoutClosed: Boolean get() = stdout.closed
        val stderrClosed: Boolean get() = stderr.closed

        override fun getOutputStream(): java.io.OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): java.io.InputStream = stdout

        override fun getErrorStream(): java.io.InputStream = stderr

        override fun waitFor(): Int {
            return when (waitMode) {
                WaitMode.THROW -> throw boom
                WaitMode.RETURN_TRUE -> 0
                WaitMode.NEVER_EXIT -> {
                    while (!destroyed) {
                        try {
                            Thread.sleep(10)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw e
                        }
                    }
                    0
                }
            }
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return when (waitMode) {
                WaitMode.THROW -> throw boom
                WaitMode.RETURN_TRUE -> true
                WaitMode.NEVER_EXIT -> {
                    if (timeout <= 0L) return destroyed
                    val sliceMs = minOf(unit.toMillis(timeout), SU_WAIT_POLL_MS)
                    try {
                        Thread.sleep(sliceMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                    destroyed
                }
            }
        }

        override fun exitValue(): Int {
            if (exitThrows) throw boom
            if (waitMode == WaitMode.NEVER_EXIT && !destroyed) {
                throw IllegalThreadStateException("not exited")
            }
            return 0
        }

        override fun destroy() {}

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = !destroyed
    }

    // Blocking poll helpers (captureProcess tests run off-coroutine).
    private fun pollDrainCountToBaseBlocking(base: Int) {
        var spins = 0
        while (activeDrainCount.get() != base && spins < 50) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            spins++
        }
    }

    // Suspend poll helpers (cancellable tests run in runBlocking).
    private suspend fun pollDrainCountToBaseSuspend(base: Int) {
        var spins = 0
        while (activeDrainCount.get() != base && spins < 50) {
            delay(100L)
            spins++
        }
    }

    @Test fun blockingWaitForError_rethrowsSameAndCleansUp() {
        // Brief settle for async releases from prior tests, then record the
        // baseline (SuCancellableTest drain-budget pattern: never assume 0).
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val base = activeDrainCount.get()
        assertTrue("drain count must never exceed cap at start (base=$base)", base <= MAX_ACTIVE_DRAINS)
        // Per-test own fake + boom (no sharing between tests).
        val boom = AssertionError("boom-wait-error")
        val fake = ErrorThrowingProcess(WaitMode.THROW, StreamKind.HANGING, boom)
        try {
            // DIRECT captureProcess call (not gateway): the blocking outer
            // catch(Throwable) must rethrow the same instance.
            val thrownRef = AtomicReference<Throwable?>(null)
            try {
                captureProcess(arrayOf("su", "-c", "pm list users"), timeoutMs = 5_000, execFn = { fake })
                fail("waitFor Error must rethrow, never return a CmdResult")
            } catch (t: Throwable) {
                thrownRef.set(t)
            }
            assertSame("blocking waitFor must rethrow the same Error instance", boom, thrownRef.get())
            assertTrue("cleanup must destroyForcibly after waitFor Error", fake.destroyed)
            assertTrue("cleanup must close stdout after waitFor Error", fake.stdoutClosed)
            assertTrue("cleanup must close stderr after waitFor Error", fake.stderrClosed)
            pollDrainCountToBaseBlocking(base)
            assertEquals("drain count must return to baseline after Error cleanup", base, activeDrainCount.get())
            // Next real echo proves slots recovered and the path is healthy.
            val next = captureProcess(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000)
            assertEquals("next echo after Error cleanup must exit 0, got: $next", 0, next.exit)
            assertEquals("next echo after Error cleanup must succeed", "ok\n", next.out)
        } finally {
            // Finally poll so a failing test cannot pollute the next one.
            pollDrainCountToBaseBlocking(base)
        }
    }

    @Test fun blockingExitValueError_rethrowsSameAndCleansUp() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val base = activeDrainCount.get()
        assertTrue("drain count must never exceed cap at start (base=$base)", base <= MAX_ACTIVE_DRAINS)
        val boom = AssertionError("boom-exit-error")
        val fake = ErrorThrowingProcess(WaitMode.RETURN_TRUE, StreamKind.EOF, boom, exitThrows = true)
        try {
            val thrownRef = AtomicReference<Throwable?>(null)
            try {
                captureProcess(arrayOf("su", "-c", "pm list users"), timeoutMs = 5_000, execFn = { fake })
                fail("exitValue Error must rethrow, never return a CmdResult")
            } catch (t: Throwable) {
                thrownRef.set(t)
            }
            assertSame("blocking exitValue must rethrow the same Error instance", boom, thrownRef.get())
            assertTrue("cleanup must destroyForcibly after exitValue Error", fake.destroyed)
            // Close coverage is in test #1 (HANGING); EOF drains close via
            // their own use() here, so only destroyed + slot + next echo are
            // asserted as the cleanup proof.
            pollDrainCountToBaseBlocking(base)
            assertEquals("drain count must return to baseline after exitValue Error cleanup", base, activeDrainCount.get())
            val next = captureProcess(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000)
            assertEquals("next echo after exitValue Error cleanup must exit 0, got: $next", 0, next.exit)
            assertEquals("next echo after exitValue Error cleanup must succeed", "ok\n", next.out)
        } finally {
            pollDrainCountToBaseBlocking(base)
        }
    }

    @Test fun blockingWaitForRuntimeException_rethrowsSameAndCleansUp() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val base = activeDrainCount.get()
        assertTrue("drain count must never exceed cap at start (base=$base)", base <= MAX_ACTIVE_DRAINS)
        val boom = IllegalStateException("boom-wait-runtime")
        val fake = ErrorThrowingProcess(WaitMode.THROW, StreamKind.HANGING, boom)
        try {
            // Blocking layering proof: unlike the cancellable outer
            // catch(Exception) that maps to transportUnknown, the blocking
            // outer catch(Throwable) rethrows Exception too.
            val thrownRef = AtomicReference<Throwable?>(null)
            try {
                captureProcess(arrayOf("su", "-c", "pm list users"), timeoutMs = 5_000, execFn = { fake })
                fail("waitFor RuntimeException must rethrow on blocking path, never return a CmdResult")
            } catch (t: Throwable) {
                thrownRef.set(t)
            }
            assertSame("blocking waitFor must rethrow the same RuntimeException instance", boom, thrownRef.get())
            assertTrue("cleanup must destroyForcibly after waitFor RuntimeException", fake.destroyed)
            assertTrue("cleanup must close stdout after waitFor RuntimeException", fake.stdoutClosed)
            assertTrue("cleanup must close stderr after waitFor RuntimeException", fake.stderrClosed)
            pollDrainCountToBaseBlocking(base)
            assertEquals("drain count must return to baseline after RuntimeException cleanup", base, activeDrainCount.get())
            val next = captureProcess(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000)
            assertEquals("next echo after RuntimeException cleanup must exit 0, got: $next", 0, next.exit)
            assertEquals("next echo after RuntimeException cleanup must succeed", "ok\n", next.out)
        } finally {
            pollDrainCountToBaseBlocking(base)
        }
    }

    @Test fun cancellableWaitForError_rethrowsSameAndCleansUp() = runBlocking {
        delay(500L)
        val base = activeDrainCount.get()
        assertTrue("drain count must never exceed cap at start (base=$base)", base <= MAX_ACTIVE_DRAINS)
        val boom = AssertionError("boom-cancellable-wait-error")
        val fake = ErrorThrowingProcess(WaitMode.THROW, StreamKind.HANGING, boom)
        try {
            // DIRECT cancellable call in runBlocking (not via gw.run).
            val thrownRef = AtomicReference<Throwable?>(null)
            try {
                captureProcessCancellable(arrayOf("su", "-c", "pm list users"), timeoutMs = 5_000, execFn = { fake })
                fail("cancellable waitFor Error must rethrow, never return a CmdResult")
            } catch (t: Throwable) {
                thrownRef.set(t)
            }
            assertSame("cancellable waitFor must rethrow the same Error instance", boom, thrownRef.get())
            assertTrue("cleanup must destroyForcibly after cancellable waitFor Error", fake.destroyed)
            assertTrue("cleanup must close stdout after cancellable waitFor Error", fake.stdoutClosed)
            assertTrue("cleanup must close stderr after cancellable waitFor Error", fake.stderrClosed)
            pollDrainCountToBaseSuspend(base)
            assertEquals("drain count must return to baseline after cancellable Error cleanup", base, activeDrainCount.get())
            val next = captureProcessCancellable(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000)
            assertEquals("next cancellable echo after Error cleanup must exit 0, got: $next", 0, next.exit)
            assertEquals("next cancellable echo after Error cleanup must succeed", "ok\n", next.out)
        } finally {
            pollDrainCountToBaseSuspend(base)
        }
    }

    @Test fun cancellableExitValueError_rethrowsSameAndCleansUp() = runBlocking {
        delay(500L)
        val base = activeDrainCount.get()
        assertTrue("drain count must never exceed cap at start (base=$base)", base <= MAX_ACTIVE_DRAINS)
        val boom = AssertionError("boom-cancellable-exit-error")
        val fake = ErrorThrowingProcess(WaitMode.RETURN_TRUE, StreamKind.EOF, boom, exitThrows = true)
        try {
            val thrownRef = AtomicReference<Throwable?>(null)
            try {
                captureProcessCancellable(arrayOf("su", "-c", "pm list users"), timeoutMs = 5_000, execFn = { fake })
                fail("cancellable exitValue Error must rethrow, never return a CmdResult")
            } catch (t: Throwable) {
                thrownRef.set(t)
            }
            assertSame("cancellable exitValue must rethrow the same Error instance", boom, thrownRef.get())
            assertTrue("cleanup must destroyForcibly after cancellable exitValue Error", fake.destroyed)
            assertTrue("cancellable cleanup must close stdout after exitValue Error", fake.stdoutClosed)
            assertTrue("cancellable cleanup must close stderr after exitValue Error", fake.stderrClosed)
            pollDrainCountToBaseSuspend(base)
            assertEquals("drain count must return to baseline after cancellable exitValue Error cleanup", base, activeDrainCount.get())
            val next = captureProcessCancellable(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000)
            assertEquals("next cancellable echo after exitValue Error cleanup must exit 0, got: $next", 0, next.exit)
            assertEquals("next cancellable echo after exitValue Error cleanup must succeed", "ok\n", next.out)
        } finally {
            pollDrainCountToBaseSuspend(base)
        }
    }

    @Test fun cancellableScopeCancel_propagatesCancellationAndCleansUp() = runBlocking {
        delay(500L)
        val base = activeDrainCount.get()
        assertTrue("drain count must never exceed cap at start (base=$base)", base <= MAX_ACTIVE_DRAINS)
        // Thread-safe capture: execFn runs on Dispatchers.Default while the
        // test body reads after await (CopyOnWriteArrayList, SuCancellableTest).
        val fakes = CopyOnWriteArrayList<ErrorThrowingProcess>()
        try {
            val job = async(Dispatchers.Default) {
                captureProcessCancellable(
                    arrayOf("su", "-c", "pm list users"),
                    timeoutMs = 30_000,
                    execFn = {
                        ErrorThrowingProcess(
                            WaitMode.NEVER_EXIT,
                            StreamKind.HANGING,
                            AssertionError("never-exit-unused"),
                        ).also { fakes += it }
                    },
                )
            }
            // Spawn is synchronous inside the helper, so 300 ms guarantees
            // the wait is in flight before cancel (SuCancellableTest).
            delay(300L)
            val startNs = System.nanoTime()
            job.cancel()
            try {
                job.await()
                fail("scope cancel must propagate CancellationException")
            } catch (_: CancellationException) {
                // Expected: queue-collapse handover, never a result.
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue(
                "scope cancel must hand over fast, not wait the deadline (elapsed=${elapsedMs}ms)",
                elapsedMs < 2_000L,
            )
            assertEquals("scope-cancel fake must be observed", 1, fakes.size)
            val fake = fakes[0]
            assertTrue("scope cancel must destroyForcibly", fake.destroyed)
            assertTrue("scope cancel must close stdout", fake.stdoutClosed)
            assertTrue("scope cancel must close stderr", fake.stderrClosed)
            pollDrainCountToBaseSuspend(base)
            assertEquals("drain count must return to baseline after scope cancel", base, activeDrainCount.get())
            // Next echo stays fast after cancel cleanup.
            val nextStartNs = System.nanoTime()
            val next = captureProcessCancellable(arrayOf("sh", "-c", "echo ok"), timeoutMs = 5_000)
            val nextElapsedMs = (System.nanoTime() - nextStartNs) / 1_000_000L
            assertEquals("next echo after scope cancel must exit 0, got: $next", 0, next.exit)
            assertEquals("next echo after scope cancel must succeed", "ok\n", next.out)
            assertTrue(
                "next echo after scope cancel must be fast (elapsed=${nextElapsedMs}ms)",
                nextElapsedMs < 2_000L,
            )
        } finally {
            pollDrainCountToBaseSuspend(base)
        }
    }
}
