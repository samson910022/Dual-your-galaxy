// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #54 Phase1: JVM hammer tests for the drain snapshot race fix. The Binder/
// Parcel round-trip and the real `su -c` spawn path stay device-only; only
// the pure drainCapped/snapshot/capped synchronization is pinned here.
package com.galaxydual.plus

import com.galaxydual.core.TRUNCATION_MARKER
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainConcurrencyTest {
    // InputStream that yields at most 8KB per read with a small sleep, so the
    // hammer window lasts long enough for the reader threads to snapshot
    // WHILE writers are appending (the join-expiry interleaving under test).
    private class ThrottledInputStream(
        private val delegate: InputStream,
        private val sleepMs: Long = 3L,
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
        val writersDone = AtomicBoolean(false)
        val snapshotsTaken = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()

        val readers = (0 until 2).map { r ->
            thread(isDaemon = true, name = "hammer-snapshot-reader-$r") {
                try {
                    var lastLen = 0
                    while (!writersDone.get() || snapshotsTaken.get() < 50) {
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
                        // Single 'x' alphabet: the next longer snapshot must
                        // extend the previous one (pure appends, no rewrites).
                        if (snap.length > lastLen && !snap.startsWith("x".repeat(lastLen))) {
                            errors.add(AssertionError("snapshot is not a prefix extension (last=$lastLen now=${snap.length})"))
                            break
                        }
                        lastLen = snap.length
                        snapshotsTaken.incrementAndGet()
                        if (snapshotsTaken.get() > 20_000) break
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        // Let readers take a few empty snapshots before writers start.
        Thread.sleep(10L)
        val writers = (0 until 4).map { w ->
            thread(isDaemon = true, name = "hammer-drain-writer-$w") {
                try {
                    val data = ByteArray(200_000) { 'x'.code.toByte() }
                    drainCapped(ThrottledInputStream(ByteArrayInputStream(data)), sink)
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        writers.forEach { it.join(10_000L) }
        writersDone.set(true)
        readers.forEach { it.join(10_000L) }
        assertTrue(writers.none { it.isAlive })
        assertTrue(readers.none { it.isAlive })
        assertTrue("hammer threads must not throw, got: ${errors.firstOrNull()}", errors.isEmpty())
        assertTrue("readers must have taken snapshots during the hammer, got ${snapshotsTaken.get()}", snapshotsTaken.get() >= 50)
        val finalSnap = snapshot(sink)
        assertEquals(MAX_CAPTURE_CHARS, finalSnap.length)
        assertFalse("final capture must not contain NUL", finalSnap.contains('\u0000'))
        assertTrue("final capture must be all 'x'", finalSnap.all { it == 'x' })
    }

    // End-to-end timeout-path snapshot: the child keeps appending for ~5s
    // while the capture already timed out after 200ms. The timeout path's
    // join(2_000) expires with the drain still alive, so buildResult/capped
    // snapshot the live sink -- it must still be a clean atomic copy (no
    // NUL fill, no torn length) marked truncated.
    // Uses the host shell only (same assumption as GatewayAssemblyTest).
    @Test fun captureTimeout_snapshotNotTornWhileDrainStillAppending() {
        val res = captureProcess(
            arrayOf("sh", "-c", "i=0; while [ \$i -lt 20 ]; do echo xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx; i=\$((i+1)); sleep 0.3; done; sleep 5"),
            timeoutMs = 200L,
        )
        assertTrue("slow capture must time out, got: $res", res.timedOut)
        assertFalse("timeout capture out must not contain NUL, got len=${res.out.length}", res.out.contains('\u0000'))
        assertTrue("timeout capture out must carry the truncation marker, got len=${res.out.length}", res.out.contains(TRUNCATION_MARKER))
        assertTrue("timeout capture out must stay bounded, got len=${res.out.length}", res.out.length <= MAX_CAPTURE_CHARS + TRUNCATION_MARKER.length + 1)
    }
}
