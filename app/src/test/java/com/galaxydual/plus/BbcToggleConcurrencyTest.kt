// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 2 / N05 + round 3 / D05: BBC 開關併發的 framework-free JVM 測試。
//
// EN: the deterministic tests drive the shared coordinator on ONE TestScheduler
// (scope, io and main all share it), so ordering is decided by runCurrent()/
// advanceUntilIdle() and CompletableDeferred gates — never a wall-clock sleep,
// never Robolectric (this project has no Robolectric). D05's new tests instead
// use a REAL single-thread IO thread blocked on a CountDownLatch.await(): a
// cancellable CompletableDeferred gate would let a disposed actor abandon its
// commit and make the cross-rebuild test vacuous. Java latches are pure
// handshakes here (no timing assumptions).
//
// 中文：決定性測試把 scope/io/main 指向同一個 TestScheduler，用 gate 控制完成順序，
// 不靠睡眠、不靠 Robolectric。D05 新測試改用真實單執行緒 + CountDownLatch.await()
// 做出真正不可取消的阻塞提交；若用可取消的 CompletableDeferred，舊 actor 一 dispose
// 就能拋棄提交，測試會變成空架子。Java latch 只做同步握手，不含時間假設。
package com.galaxydual.plus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BbcToggleConcurrencyTest {

    /**
     * Controllable store: records call order, can gate any call index, and can
     * force false/throw. `persisted` only records what the fake accepted — the
     * controller never re-reads it; the commit() return value is its authority.
     */
    private class Sink(initial: Boolean) {
        @Volatile
        var persisted: Boolean = initial
        val commits = mutableListOf<Boolean>()
        val restored = mutableListOf<Boolean>()
        var failures = 0
        var unknown = 0
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var resultAt: (Int) -> Boolean = { true }
        var throwAt: (Int) -> Boolean = { false }

        suspend fun commit(value: Boolean): BbcWriteOutcome {
            val idx = commits.size
            commits.add(value)
            gates[idx]?.await()
            if (throwAt(idx)) throw RuntimeException("store boom @$idx")
            val ok = resultAt(idx)
            if (ok) persisted = value
            return if (ok) BbcWriteOutcome.Confirmed(value) else BbcWriteOutcome.Unknown
        }
    }

    // Shared app-scoped coordinator + controller under one TestScheduler.
    private fun TestScope.testCoordinator(
        sink: Sink,
        state: BbcToggleRuntimeState = BbcToggleRuntimeState(initialConfirmed = false),
    ): BbcToggleCoordinator {
        // The app-lifetime consumer is intentionally never cancelled by a
        // controller. It lives in its own scope on the SAME TestScheduler (so
        // runCurrent/advanceUntilIdle drive it), and is torn down only when the
        // test body finishes — making it a child of the test scope would be
        // reported as an uncompleted coroutine by runTest.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        return BbcToggleCoordinator(
            scope = appScope,
            state = state,
            commit = { sink.commit(it) },
            io = dispatcher,
        )
    }

    private fun TestScope.testController(
        coordinator: BbcToggleCoordinator,
        initial: Boolean,
        sink: Sink,
    ): BbcInjectionToggleController = BbcInjectionToggleController(
        coordinator = coordinator,
        initialValue = initial,
        main = StandardTestDispatcher(testScheduler),
        onRestored = { sink.restored.add(it) },
        onFailed = { sink.failures++ },
        onUnknown = { sink.unknown++ },
    )

    @Test
    fun rapidToggle_lastIntentWinsWhenEarlierCommitDelayed() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        val coordinator = testCoordinator(sink)
        val c = testController(coordinator, initial = false, sink = sink)

        c.onToggle(true) // ON (seq 1): commit starts, blocks on gate0.
        testScheduler.runCurrent()
        assertEquals(listOf(true), sink.commits)

        c.onToggle(false) // OFF (seq 2): newer tap supersedes while #1 is in IO.
        gate0.complete(Unit) // release the stale completion
        testScheduler.advanceUntilIdle()

        // Single global actor serialises commits, so OFF is the value that
        // actually lands last; the gen-1 result is ignored (no rollback, no toast).
        assertEquals(listOf(true, false), sink.commits)
        assertEquals(false, sink.persisted)
        assertEquals(false, c.currentLastGood())
        assertEquals(0, sink.restored.size)
        assertEquals(0, sink.failures)
        assertTrue("generation must advance per tap", c.currentGeneration() >= 2L)
        assertEquals(2L, coordinator.latestSequence())
        c.dispose()
    }

    @Test
    fun rapidToggle_onOffOn_reorderedIO_finalPersistEqualsFinalIntent() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        val coordinator = testCoordinator(sink)
        val c = testController(coordinator, initial = false, sink = sink)

        c.onToggle(true) // ON (seq 1), gated in IO.
        testScheduler.runCurrent()
        c.onToggle(false) // OFF (seq 2)
        c.onToggle(true) // ON (seq 3) — final intent; replaces OFF in the conflated slot.
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(true, sink.persisted) // final persisted == final intent
        assertEquals(true, c.currentLastGood())
        assertTrue("generation must advance per tap", c.currentGeneration() >= 3L)
        // gen-1 result dropped; the OFF tap coalesced into the trailing ON commit.
        assertTrue("bounded commits, got ${sink.commits}", sink.commits.size <= 2)
        assertEquals(true, sink.commits.last())
        assertEquals(3L, coordinator.latestSequence())
        c.dispose()
    }

    @Test
    fun rapidToggle_staleFailureDoesNotClobberLastGood() = runTest {
        val sink = Sink(initial = true)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        sink.resultAt = { idx -> idx != 0 } // call 0 (OFF) fails when released
        val coordinator = testCoordinator(sink, BbcToggleRuntimeState(initialConfirmed = true))
        val c = testController(coordinator, initial = true, sink = sink)

        // Earlier intent (OFF) is delayed in IO; a newer intent (ON) arrives.
        c.onToggle(false) // seq 1, gated failure
        testScheduler.runCurrent()
        c.onToggle(true) // seq 2, latest intent
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Delayed failure is stale: no rollback, no unknown UI, no toast;
        // lastGood advances only through the newer successful commit.
        assertEquals(0, sink.restored.size)
        assertEquals(0, sink.unknown)
        assertEquals(0, sink.failures)
        assertEquals(true, c.currentLastGood())
        assertEquals(true, sink.persisted)
        assertEquals(true, coordinator.state.confirmedOrUnknown)

        // D04: a genuinely current failure must NOT restore anything. The
        // CheckBox stays at the user's last intent and onUnknown fires; lastGood
        // remains the last CONFIRMED value (true), not a rollback authority.
        sink.resultAt = { idx -> idx != 2 } // next commit (idx 2) fails
        c.onToggle(false) // seq 3
        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.restored.size)
        assertEquals(1, sink.unknown)
        assertEquals(1, sink.failures)
        assertEquals(true, c.currentLastGood())
        assertNull("failed commit must clear confirmation", coordinator.state.confirmedOrUnknown)
        c.dispose()
    }

    @Test
    fun rapidToggle_generationRejectsStaleSuccess() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        val gate1 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        sink.gates[1] = gate1
        val coordinator = testCoordinator(sink)
        val c = testController(coordinator, initial = false, sink = sink)

        c.onToggle(true) // seq 1: stale success, gated
        testScheduler.runCurrent()
        c.onToggle(false) // seq 2: latest intent, gated too
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true, false), sink.commits)
        // The stale true write may have landed in storage, but the sequence
        // fence must NOT let it advance lastGood.
        assertEquals("stale success must not advance lastGood", false, c.currentLastGood())

        gate1.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(false, c.currentLastGood())
        assertEquals(false, sink.persisted)
        c.dispose()
    }

    @Test
    fun commitFalse_marksUnknownDoesNotRestore() = runTest {
        val sink = Sink(initial = true)
        sink.resultAt = { false } // commit return is the authority
        val coordinator = testCoordinator(sink, BbcToggleRuntimeState(initialConfirmed = true))
        val c = testController(coordinator, initial = true, sink = sink)

        c.onToggle(false)
        testScheduler.advanceUntilIdle()

        // D04: commit(false) is UNKNOWN. The controller neither re-reads storage
        // nor claims a rollback: the CheckBox stays at the user's last intent,
        // onUnknown marks the effect unverified, and lastGood stays confirmed.
        assertEquals(0, sink.restored.size) // no rollback
        assertEquals(1, sink.unknown) // explicit UNKNOWN UI
        assertEquals(1, sink.failures) // legacy toast path taken
        assertEquals(true, c.currentLastGood())
        assertEquals(null, coordinator.state.confirmedOrUnknown) // Unknown clears confirmation
        c.dispose()
    }

    @Test
    fun unknownOutcome_callsOnUnknownNotRestored() = runTest {
        // Both Unknown sources (commit false and a throw) must route to
        // onUnknown and never call onRestored.
        val falseSink = Sink(initial = true)
        falseSink.resultAt = { false }
        val falseCoordinator = testCoordinator(falseSink, BbcToggleRuntimeState(initialConfirmed = true))
        val falseController = testController(falseCoordinator, initial = true, sink = falseSink)
        falseController.onToggle(false)
        testScheduler.advanceUntilIdle()
        assertEquals(0, falseSink.restored.size)
        assertEquals(1, falseSink.unknown)
        assertNull(falseCoordinator.state.confirmedOrUnknown)
        falseController.dispose()

        val throwSink = Sink(initial = true)
        throwSink.throwAt = { true }
        val throwCoordinator = testCoordinator(throwSink, BbcToggleRuntimeState(initialConfirmed = true))
        val throwController = testController(throwCoordinator, initial = true, sink = throwSink)
        throwController.onToggle(false)
        testScheduler.advanceUntilIdle()
        assertEquals(0, throwSink.restored.size)
        assertEquals(1, throwSink.unknown)
        assertNull(throwCoordinator.state.confirmedOrUnknown)
        throwController.dispose()
    }

    @Test
    fun rapidToggle_intermediateIntentsCoalesced_boundedCommits() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        val coordinator = testCoordinator(sink)
        val c = testController(coordinator, initial = false, sink = sink)

        c.onToggle(true) // seq 1: first commit blocked in IO
        testScheduler.runCurrent()
        repeat(10) { c.onToggle(it % 2 == 0) } // burst; only the newest survives
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Exact survival set: seq-1 ON (gated), then only the LAST tap of the
        // 10-tap burst survives the conflated slot (it=9 -> false). The other
        // nine taps are coalesced away, so no bound/vacuous-zero check needed.
        assertEquals(listOf(true, false), sink.commits)
        assertEquals(false, c.currentLastGood())
        assertEquals(false, sink.persisted)
        assertTrue("generation must advance per tap", c.currentGeneration() >= 11L)
        assertEquals(11L, coordinator.latestSequence())
        c.dispose()
    }

    @Test
    fun dispose_detachesUiButLeavesSharedConsumerAlive() = runTest {
        val sink = Sink(initial = false)
        val coordinator = testCoordinator(sink)
        val c = testController(coordinator, initial = false, sink = sink)
        testScheduler.runCurrent() // let the consumer start and park on the channel
        assertTrue("consumer must be live before dispose", coordinator.isConsumerActive())
        assertTrue("controller must be alive before dispose", c.isAlive())

        c.dispose()
        assertFalse("controller UI callbacks must be detached", c.isAlive())
        assertTrue("shared consumer must survive controller dispose", coordinator.isConsumerActive())

        // Late taps after dispose must be inert: no commit, no rollback, no
        // unknown UI, no toast.
        c.onToggle(true)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.commits.size)
        assertEquals(0, sink.restored.size)
        assertEquals(0, sink.unknown)
        assertEquals(0, sink.failures)
        assertEquals(false, sink.persisted)
        assertEquals(0L, coordinator.latestSequence())
    }

    @Test
    fun staleSuccessThenNewerFailure_unknownNotPersistedRollback() = runTest {
        val sink = Sink(initial = false) // persisted starts OFF
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        sink.resultAt = { idx -> idx != 1 } // call 0 (ON) succeeds, call 1 (OFF) fails
        val coordinator = testCoordinator(sink)
        val c = testController(coordinator, initial = false, sink = sink)

        c.onToggle(true) // seq 1: ON commit starts, gated in IO
        testScheduler.runCurrent()
        assertEquals(listOf(true), sink.commits)

        c.onToggle(false) // seq 2: OFF supersedes while the ON commit is in flight
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        // ON really landed on disk (persisted=true) but its seq-1 result was
        // stale for the UI, so lastGood stayed false. The newer OFF commit fails.
        // D04: that real persisted ON must NOT be claimed as a rollback — no
        // onRestored at all — because the failed commit may have polluted the
        // local mMap. The unknown callback fires instead.
        assertEquals("no rollback may claim the real persisted value", 0, sink.restored.size)
        assertEquals(1, sink.unknown)
        assertEquals(1, sink.failures)
        assertEquals(false, c.currentLastGood())
        assertEquals(true, sink.persisted) // real disk truth, NEVER used as rollback
        assertNull(coordinator.state.confirmedOrUnknown)
        c.dispose()
    }

    // --- D05: genuinely blocking commit, real IO thread, no sleeps ------------

    /** UI callbacks captured separately per controller so stale routing is visible. */
    private class UiLog {
        val restored = mutableListOf<Boolean>()
        var failures = 0
        var unknown = 0

        fun isEmpty(): Boolean = restored.isEmpty() && failures == 0 && unknown == 0
    }

    /**
     * The first commit blocks a REAL IO thread on a CountDownLatch. Unlike a
     * CompletableDeferred gate, disposing a controller cannot cancel or bypass
     * it. Latches are only handshakes; no timing/wall-clock assumptions.
     */
    private class BlockingSink(initial: Boolean) {
        @Volatile
        var persisted: Boolean = initial
            private set

        private val events = Collections.synchronizedList(mutableListOf<String>())
        private val calls = AtomicInteger(0)
        private val firstStarted = CountDownLatch(1)
        private val firstRelease = CountDownLatch(1)
        private val firstEnded = CountDownLatch(1)
        private val secondEnded = CountDownLatch(1)

        suspend fun commit(value: Boolean): BbcWriteOutcome = withContext(NonCancellable) {
            val idx = calls.getAndIncrement()
            events.add("start:$value")
            if (idx == 0) {
                firstStarted.countDown()
                firstRelease.await() // genuinely blocking; not coroutine-cancellable
            }
            events.add("end:$value")
            persisted = value
            if (idx == 0) firstEnded.countDown() else secondEnded.countDown()
            BbcWriteOutcome.Confirmed(value)
        }

        fun awaitFirstStarted() = check(firstStarted.await(5, TimeUnit.SECONDS))
        fun releaseFirst() = firstRelease.countDown()
        fun awaitFirstEnded() = check(firstEnded.await(5, TimeUnit.SECONDS))
        fun awaitSecondEnded() = check(secondEnded.await(5, TimeUnit.SECONDS))

        fun snapshot(): List<String> = synchronized(events) { events.toList() }
    }

    private fun newIoDispatcher() =
        Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-d05-test-io") }.asCoroutineDispatcher()

    private fun uiController(
        coordinator: BbcToggleCoordinator,
        initial: Boolean,
        log: UiLog,
    ): BbcInjectionToggleController = BbcInjectionToggleController(
        coordinator = coordinator,
        initialValue = initial,
        main = Dispatchers.Unconfined, // UI block runs inline on the consumer thread
        onRestored = { log.restored.add(it) },
        onFailed = { log.failures++ },
        onUnknown = { log.unknown++ },
    )

    @Test
    fun rebuildDuringBlockingCommit_lastGlobalIntentWins() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BlockingSink(initial = false)
            // The initial true is a PROVISIONAL UI seed, not a write confirmation.
            val state = BbcToggleRuntimeState(initialConfirmed = true)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state, commit = { sink.commit(it) }, io = io,
            )
            val aLog = UiLog()
            val a = uiController(coordinator, initial = false, log = aLog)

            a.onToggle(true) // seq 1: enters a genuinely blocking commit
            sink.awaitFirstStarted()
            assertEquals(listOf("start:true"), sink.snapshot())

            a.dispose() // UI-only; the in-flight commit is NOT cancelled

            val bLog = UiLog()
            val b = uiController(coordinator, initial = true, log = bLog)
            b.onToggle(false) // seq 2: same coordinator, new controller
            assertEquals("B must not commit while A's commit still holds the single writer",
                listOf("start:true"), sink.snapshot())

            sink.releaseFirst() // release A's latch LAST
            sink.awaitSecondEnded()
            // state.mark runs before onResult with no suspension in between, so
            // observing the newer mark proves all earlier callbacks were attempted.
            withTimeout(5_000L) { while (state.confirmedOrUnknown != false) yield() }
            withTimeout(5_000L) { while (b.currentLastGood()) yield() }

            assertEquals(false, sink.persisted) // last global intent wins on disk
            assertEquals(listOf("start:true", "end:true", "start:false", "end:false"),
                sink.snapshot()) // A's commit completed before B's even started
            assertEquals(2L, coordinator.latestSequence())
            assertEquals(false, state.confirmedOrUnknown)
            // Confirmed commits fire NO UI callback (onRestored is dead and the
            // Confirmed branch only writes lastGood), so the UiLog.isEmpty()
            // pins below cannot see them. currentLastGood() is the real
            // observation: it advances only for a LIVE controller whose own
            // intent is still current (the alive + sequence guards).
            assertEquals("B committed false while alive -> lastGood must follow",
                false, b.currentLastGood())
            // A was disposed before its Confirmed(true) landed; its stale result
            // must never advance A's lastGood off its initial false. (The app-scoped
            // state, not the disposed controller, carries the provisional seed.)
            assertEquals("disposed A must not absorb its own Confirmed(true) result",
                false, a.currentLastGood())
            assertTrue("disposed A's UI must be cut", aLog.isEmpty())
            assertTrue("A's stale result must never be routed to B", bLog.isEmpty())
            a.dispose()
            b.dispose()
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    @Test
    fun disposeDoesNotCancelInFlightCommit_butOnlyCutsCallbacks() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BlockingSink(initial = false)
            val state = BbcToggleRuntimeState(initialConfirmed = false)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state, commit = { sink.commit(it) }, io = io,
            )
            val log = UiLog()
            val c = uiController(coordinator, initial = false, log = log)

            c.onToggle(true)
            sink.awaitFirstStarted()
            c.dispose() // detach UI while the commit is blocked on a real latch

            sink.releaseFirst()
            sink.awaitFirstEnded()
            withTimeout(5_000L) { while (state.confirmedOrUnknown != true) yield() }

            // The commit still completed and its outcome was marked app-wide...
            assertTrue(sink.persisted)
            assertEquals(true, state.confirmedOrUnknown)
            assertTrue(coordinator.isConsumerActive())
            // ...but the DISPOSED controller must NOT absorb the Confirmed(true)
            // result: c started at false and stayed disposed through the commit.
            // currentLastGood() is the real observation (Confirmed fires no UI
            // callback, so UiLog.isEmpty() below is only secondary): a LIVE
            // controller would have advanced lastGood to the confirmed true,
            // while the disposed one must stay at false. This pins the
            // alive/sequence-guard contract that protects UI state on dispose.
            assertFalse("disposed controller lastGood must stay at initial false",
                c.currentLastGood())
            assertTrue(log.isEmpty())
            assertFalse(c.isAlive())
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    @Test
    fun sequenceIsGlobalAcrossRebuilds() = runTest {
        val sink = Sink(initial = false)
        val coordinator = testCoordinator(sink)
        val taps = listOf(true, false, true, false, true)
        var c = testController(coordinator, initial = false, sink = sink)
        taps.forEachIndexed { index, value ->
            if (index == 2 || index == 4) {
                c.dispose() // UI-only: the global writer keeps its sequence
                c = testController(coordinator, initial = false, sink = sink)
            }
            c.onToggle(value)
            testScheduler.advanceUntilIdle()
        }

        assertEquals(5L, coordinator.latestSequence())
        // No per-controller generation reset reaches the store: every intent
        // got its own commit, in user order, across two rebuilds.
        assertEquals(taps, sink.commits)
        assertEquals(true, sink.persisted)
        assertEquals(true, c.currentLastGood())
        assertEquals(true, coordinator.state.confirmedOrUnknown)
        assertTrue("local generation is per controller, global sequence is not",
            c.currentGeneration() == 1L && coordinator.latestSequence() == 5L)
        c.dispose()
    }
}
