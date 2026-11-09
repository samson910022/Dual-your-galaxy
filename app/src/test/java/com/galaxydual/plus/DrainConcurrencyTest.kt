// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #54 Phase1: JVM hammer tests for the drain snapshot race fix. The Binder/
// Parcel round-trip and the real `su -c` spawn path stay device-only; only
// the pure drainCapped/snapshot/capped synchronization is pinned here.
package com.galaxydual.plus

import com.galaxydual.core.TRUNCATION_MARKER
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainConcurrencyTest {
    // InputStream that yields at most 8KB per read with a small sleep, so the
    // hammer window lasts long enough for the reader threads to snapshot
    // WHILE writers are appending (the join-expiry interleaving under test).
    // Sleep is 10ms (not 3ms): with 4 writers racing to the 256KB cap the
    // live-writer window is only ~8 chunks/writer, so a 3ms sleep gave a
    // ~25ms window and overlap=64 (<100) in measurement; 10ms stretches the
    // same 8-chunk window to ~80ms so overlap clears 100 with margin while
    // keeping the test well under a second.
    private class ThrottledInputStream(
        private val delegate: InputStream,
        private val sleepMs: Long = 10L,
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, minOf(len, 8_192))
            if (n > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("interrupted")
                }
            }
            return n
        }

        override fun close() = delegate.close()
    }

    // Copy semantics of the snapshot: a snapshot is an atomic copy, later
    // appends never mutate an earlier snapshot, and capped() never mutates
    // the sink (marker is appended to the copy only).
    // NOTE: this is copy-semantics proof only, not concurrency proof -- the
    // hammer below is the concurrency proof (snapshot racing live writers).
    @Test fun snapshot_isCopy_laterAppendsDoNotMutate() {
        val sink = StringBuilder()
        synchronized(sink) { sink.append("hello") }
        val first = snapshot(sink)
        assertEquals("hello", first)
        synchronized(sink) { sink.append(" world") }
        assertEquals("hello world", snapshot(sink))
        assertEquals("earlier snapshot must stay a copy, got: $first", "hello", first)
        assertEquals(5, first.length)

        val marked = capped(sink, truncated = true)
        assertEquals("hello world\n$TRUNCATION_MARKER", marked)
        assertEquals("capped must not mutate the sink", "hello world", snapshot(sink))
        assertFalse("capped(false) must carry no marker", capped(sink, truncated = false).contains(TRUNCATION_MARKER))
    }

    // Multi-thread writes + concurrent timeout-path snapshots. Four writers
    // drain large streams into ONE shared sink (exceeding the cap) while two
    // readers snapshot in a tight loop -- the production join-expiry read
    // racing still-appending drains. Every snapshot must be a consistent
    // prefix run of 'x' (never a NUL from a torn backing-array copy, never
    // length regression, never over the cap), no writer/reader may throw,
    // and the final capture must sit exactly at MAX_CAPTURE_CHARS.
    // Post-fix the append + snapshot take the same synchronized(sink) lock,
    // so the race window is closed; the throttled writes stretch the
    // overlap so a torn implementation would be caught.
    @Test fun drainHammer_multiWriter_snapshotNeverTearsAndRespectsCap() {
        val sink = StringBuilder()
        val writersActive = AtomicBoolean(false)
        val snapshotsTaken = AtomicInteger(0)
        val overlapSnapshots = AtomicLong(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)

        val readers = (0 until 2).map { r ->
            thread(isDaemon = true, name = "hammer-snapshot-reader-$r") {
                try {
                    startLatch.await()
                    val deadlineNanos = System.nanoTime() + 5_000_000_000L
                    var lastLen = 0
                    // Primary loop while writers are live, plus a bounded
                    // post-drain (until 50 snapshots total or the 5s
                    // deadline, whichever comes first) so the loop is never
                    // infinite and never relies on a snapshot-count break.
                    while (true) {
                        val active = writersActive.get()
                        if (!active && snapshotsTaken.get() >= 50) break
                        if (System.nanoTime() > deadlineNanos) {
                            errors.add(
                                AssertionError(
                                    "reader deadline exceeded (snapshots=${snapshotsTaken.get()}, overlap=${overlapSnapshots.get()})",
                                ),
                            )
                            break
                        }
                        val snap = snapshot(sink)
                        if (snap.contains('\u0000')) {
                            errors.add(AssertionError("torn snapshot contains NUL (len=${snap.length})"))
                            break
                        }
                        if (snap.length < lastLen) {
                            errors.add(AssertionError("snapshot length regressed: $lastLen -> ${snap.length}"))
                            break
                        }
                        if (snap.length > MAX_CAPTURE_CHARS) {
                            errors.add(AssertionError("snapshot exceeds cap: ${snap.length} > $MAX_CAPTURE_CHARS"))
                            break
                        }
                        if (snap.isNotEmpty() && snap.any { it != 'x' }) {
                            errors.add(AssertionError("snapshot has foreign char: ${snap.firstOrNull() ?: '?'}"))
                            break
                        }
                        // Single 'x' alphabet + monotonic length: the all-x
                        // check above plus monotonic length imply the next
                        // longer snapshot extends the previous one (pure
                        // appends, no rewrites); no separate prefix check.
                        lastLen = snap.length
                        snapshotsTaken.incrementAndGet()
                        if (active && snap.isNotEmpty()) {
                            overlapSnapshots.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        val writers = (0 until 4).map { w ->
            thread(isDaemon = true, name = "hammer-drain-writer-$w") {
                try {
                    startLatch.await()
                    val data = ByteArray(200_000) { 'x'.code.toByte() }
                    drainCapped(ThrottledInputStream(ByteArrayInputStream(data)), sink)
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        // Start gate: readers and writers only begin after every thread is
        // constructed, so snapshots genuinely race live writers (no
        // reader-before-writer head start, no fixed sleep).
        writersActive.set(true)
        startLatch.countDown()
        writers.forEach { it.join(10_000L) }
        writersActive.set(false)
        readers.forEach { it.join(10_000L) }
        assertTrue(writers.none { it.isAlive })
        assertTrue(readers.none { it.isAlive })
        assertTrue("hammer threads must not throw, got: ${errors.firstOrNull()}", errors.isEmpty())
        assertTrue("readers must have taken snapshots during the hammer, got ${snapshotsTaken.get()}", snapshotsTaken.get() >= 50)
        System.out.println("drainHammer overlap=${overlapSnapshots.get()} total=${snapshotsTaken.get()}")
        assertTrue(
            "readers must have snapshotted while writers were live (overlap=${overlapSnapshots.get()}, total=${snapshotsTaken.get()})",
            overlapSnapshots.get() >= 100,
        )
        val finalSnap = snapshot(sink)
        assertEquals(MAX_CAPTURE_CHARS, finalSnap.length)
        assertFalse("final capture must not contain NUL", finalSnap.contains('\u0000'))
        assertTrue("final capture must be all 'x'", finalSnap.all { it == 'x' })
    }

    // Pipe-latch fake Process: models the Android child-held-pipe case --
    // the `su` wrapper is destroyed on timeout but a child (e.g. pm) keeps
    // the stdout FD open and keeps appending, so the drain stays blocked in
    // read() and the timeout join(2_000) genuinely expires while the writer
    // is live. destroyForcibly deliberately does NOT close the streams
    // (that is the child-held-pipe model; the test stops the writer by
    // closing the pipe in finally). The writer appends one line every
    // 50ms (the `sleep 0.05` loop) starting before the timeout.
    //
    // Why not a host `sh` background subshell (tried first): on OpenJDK a
    // real process's destroyForcibly closes the pipe streams and wakes the
    // blocked drain read at once (measured: drain got "Stream closed" 3ms
    // after destroy, captureProcess returned in 224ms, join never waited),
    // so no real child -- orphaned subshell or not -- can keep a JVM drain
    // alive past destroyForcibly. The fake is the task's stated fallback
    // and the only way to reach the join-expiry path on the JVM; it uses
    // the execFn seam added to captureProcess (mirroring
    // captureProcessCancellable), which production never overrides.
    private class PipeLatchProcess : Process() {
        private val pipedIn = PipedInputStream(64 * 1024)
        private val pipedOut = PipedOutputStream(pipedIn)
        @Volatile var destroyed = false
        @Volatile private var writerStop = false
        val writesAfterDestroy = AtomicInteger(0)

        private val writerThread: Thread = thread(isDaemon = true, name = "pipe-latch-writer") {
            try {
                pipedOut.write("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n".toByteArray())
                pipedOut.flush()
                while (!writerStop) {
                    Thread.sleep(50)
                    if (writerStop) break
                    pipedOut.write("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n".toByteArray())
                    pipedOut.flush()
                    if (destroyed) writesAfterDestroy.incrementAndGet()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // Pipe closed by stopWriter(): normal writer exit.
            }
        }

        fun stopWriter() {
            writerStop = true
            writerThread.interrupt()
            try {
                pipedOut.close()
            } catch (_: Exception) {
            }
        }

        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = pipedIn
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (!destroyed) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
            return 1
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val deadlineNanos = System.nanoTime() + unit.toNanos(timeout)
            while (System.nanoTime() < deadlineNanos) {
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

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = !destroyed
    }

    // End-to-end timeout-path snapshot: the writer keeps appending while the
    // capture already timed out after 200ms. The timeout path's
    // join(2_000) expires with the drain still alive, so capped() snapshots
    // the live sink -- it must still be a clean atomic copy (no NUL fill,
    // no torn length) marked truncated.
    @Test fun captureTimeout_snapshotNotTornWhileDrainStillAppending() {
        var fake: PipeLatchProcess? = null
        try {
            val startNanos = System.nanoTime()
            val res = captureProcess(
                arrayOf("su", "-c", "pm install-existing --user 95 com.example.app"),
                timeoutMs = 200L,
                execFn = { _ -> PipeLatchProcess().also { fake = it } },
            )
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            val writesAfterDestroy = fake?.writesAfterDestroy?.get() ?: -1
            val destroyed = fake?.destroyed ?: false
            System.out.println("captureTimeout elapsed=${elapsedMs}ms outLen=${res.out.length} writesAfterDestroy=$writesAfterDestroy")
            assertTrue("slow capture must time out, got: $res", res.timedOut)
            assertTrue("captureProcess must destroy the timed-out process", destroyed)
            assertTrue(
                "writer must have kept appending after destroy (writesAfterDestroy=$writesAfterDestroy)",
                writesAfterDestroy >= 1,
            )
            assertFalse("timeout capture out must not contain NUL, got len=${res.out.length}", res.out.contains('\u0000'))
            assertTrue("timeout capture out must carry the truncation marker, got len=${res.out.length}", res.out.contains(TRUNCATION_MARKER))
            assertTrue("timeout capture out must stay bounded, got len=${res.out.length}", res.out.length <= MAX_CAPTURE_CHARS + TRUNCATION_MARKER.length + 1)
            assertTrue("timeout capture out must contain writer output, got len=${res.out.length}", res.out.contains('x'))
            // Wall-clock proof the join actually waited: a dead drain EOFs
            // and returns in ~200ms+EOF (~300ms total); a live child-held
            // pipe forces the full timeout (200ms) + join (2_000ms).
            assertTrue(
                "captureProcess must wait out the join expiry against a live writer (elapsed=${elapsedMs}ms, expected >=1900ms)",
                elapsedMs >= 1900,
            )
        } finally {
            // Stop the live writer so its drain sees EOF and exits; never
            // mask the test outcome. The writer is a daemon thread on a
            // closed pipe, so even a missed stop cannot outlive the JVM.
            try {
                fake?.stopWriter()
            } catch (_: Exception) {
            }
            // Give the stdout drain a moment to observe EOF and release
            // its drain slot before the next test reserves a pair.
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
