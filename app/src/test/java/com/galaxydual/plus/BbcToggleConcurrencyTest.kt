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
//
// DEVICE-UNVERIFIED: real View styling in MainActivity.renderEffect is not exercised.
package com.galaxydual.plus

import kotlinx.coroutines.CancellationException
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
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

    /**
     * E04: app-level status stream, deliberately separate from per-intent UiLog.
     * Volatile snapshot so a status emitted on the blocking commit thread is
     * safely visible to the test thread after the latch/state handshakes.
     */
    private class StatusLog {
        @Volatile
        private var items: List<BbcEffectStatus> = emptyList()
        fun add(status: BbcEffectStatus) { items = items + status }
        fun snapshot(): List<BbcEffectStatus> = items
        fun clear() { items = emptyList() }
        fun isEmpty(): Boolean = items.isEmpty()
    }

    private fun TestScope.testController(
        coordinator: BbcToggleCoordinator,
        initial: Boolean,
        sink: Sink,
        statusLog: StatusLog? = null,
    ): BbcInjectionToggleController = BbcInjectionToggleController(
        coordinator = coordinator,
        initialValue = initial,
        main = StandardTestDispatcher(testScheduler),
        onRestored = { sink.restored.add(it) },
        onFailed = { sink.failures++ },
        onUnknown = { sink.unknown++ },
        onEffectStatus = statusLog?.let { log -> { status -> log.add(status) } },
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
        @Volatile var failures = 0
        @Volatile var unknown = 0

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
        statusLog: StatusLog? = null,
    ): BbcInjectionToggleController = BbcInjectionToggleController(
        coordinator = coordinator,
        initialValue = initial,
        main = Dispatchers.Unconfined, // UI block runs inline on the consumer thread
        onRestored = { log.restored.add(it) },
        onFailed = { log.failures++ },
        onUnknown = { log.unknown++ },
        onEffectStatus = statusLog?.let { log -> { status -> log.add(status) } },
    )

    /**
     * F03: thread-safe record of onRestored(value) sync callbacks. The sync runs
     * on the app-level collector (inline on the IO consumer thread with
     * Dispatchers.Unconfined), so the test thread needs a volatile snapshot —
     * no wall-clock sleeps, just latch/state handshakes + withTimeout spins.
     */
    private class RestoredLog {
        @Volatile
        private var items: List<Boolean> = emptyList()
        fun add(value: Boolean) { items = items + value }
        fun snapshot(): List<Boolean> = items
    }

    /** F03 controller with a dedicated, thread-safe onRestored recorder. */
    private fun syncedController(
        coordinator: BbcToggleCoordinator,
        initial: Boolean,
        restored: RestoredLog,
        statusLog: StatusLog? = null,
        log: UiLog = UiLog(),
    ): BbcInjectionToggleController = BbcInjectionToggleController(
        coordinator = coordinator,
        initialValue = initial,
        main = Dispatchers.Unconfined,
        onRestored = { restored.add(it) },
        onFailed = { log.failures++ },
        onUnknown = { log.unknown++ },
        onEffectStatus = statusLog?.let { s -> { status -> s.add(status) } },
    )

    @Test
    fun controller_unknownThenConfirmed_sameControllerClearsStyling() = runTest {
        val sink = Sink(initial = true)
        sink.resultAt = { idx -> idx != 0 } // first commit fails -> Unknown
        val coordinator = testCoordinator(sink, BbcToggleRuntimeState(initialConfirmed = true))
        val statuses = StatusLog()
        val c = testController(coordinator, initial = true, sink = sink, statusLog = statuses)
        testScheduler.runCurrent() // let the StateFlow collector subscribe/replay

        c.onToggle(false) // seq 1 -> Unknown
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf<BbcEffectStatus>(BbcEffectStatus.Provisional, BbcEffectStatus.Unknown),
            statuses.snapshot(),
        )
        assertEquals(0, sink.restored.size) // D04: no rollback
        assertEquals(1, sink.unknown) // per-intent Unknown path still fires
        assertEquals(1, sink.failures) // legacy toast path still fires once
        assertNull(coordinator.state.confirmedOrUnknown)

        c.onToggle(true) // seq 2 -> Confirmed(true), styling must clear
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf<BbcEffectStatus>(
                BbcEffectStatus.Provisional,
                BbcEffectStatus.Unknown,
                BbcEffectStatus.Confirmed(true),
            ),
            statuses.snapshot(),
        )
        // Per-intent toast counters stay at one: Confirmed only arrives on the
        // app-level status flow, not as a second "effect unknown" toast.
        assertEquals(1, sink.unknown)
        assertEquals(1, sink.failures)
        assertEquals(0, sink.restored.size)
        assertEquals(true, c.currentLastGood())
        assertEquals(true, coordinator.state.confirmedOrUnknown)
        c.dispose()
    }

    @Test
    fun controller_rebuildDuringBlockingCommit_newControllerReceivesCompletionStatus() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BlockingSink(initial = false)
            val state = BbcToggleRuntimeState(initialConfirmed = true)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state, commit = { sink.commit(it) }, io = io,
            )
            val aStatus = StatusLog()
            val aLog = UiLog()
            val a = uiController(coordinator, initial = false, log = aLog, statusLog = aStatus)
            assertEquals("replay is Provisional, not a confirmation",
                listOf<BbcEffectStatus>(BbcEffectStatus.Provisional), aStatus.snapshot())

            a.onToggle(true) // seq 1: enters a genuinely blocking commit
            sink.awaitFirstStarted()
            a.dispose() // UI-only; the in-flight commit is NOT cancelled
            aStatus.clear() // nothing may arrive after dispose

            val bStatus = StatusLog()
            val bLog = UiLog()
            val b = uiController(coordinator, initial = true, log = bLog, statusLog = bStatus)
            // B subscribed while A's commit is still blocked: replay gives it
            // the last applied status, NOT the not-yet-existing Confirmed.
            assertEquals(listOf<BbcEffectStatus>(BbcEffectStatus.Provisional), bStatus.snapshot())

            sink.releaseFirst()
            sink.awaitFirstEnded()
            withTimeout(5_000L) { while (state.confirmedOrUnknown != true) yield() }
            withTimeout(5_000L) {
                while (BbcEffectStatus.Confirmed(true) !in bStatus.snapshot()) yield()
            }

            // B never submitted seq 1, yet it learns that commit's outcome from
            // the app-scoped StateFlow; A's disposed collector stays silent.
            assertEquals(
                listOf<BbcEffectStatus>(
                    BbcEffectStatus.Provisional,
                    BbcEffectStatus.Confirmed(true),
                ),
                bStatus.snapshot(),
            )
            assertTrue("disposed A must receive no status at all", aStatus.isEmpty())
            assertEquals(listOf("start:true", "end:true"), sink.snapshot())
            assertEquals(1L, coordinator.latestSequence()) // B submitted nothing
            assertTrue(aLog.isEmpty())
            // F03: Confirmed is delivered app-level, but a rebuilt dialog with no
            // tap now ALSO syncs a stale box. bLog stays empty here only because
            // B's seeded initial (true) already equals the inherited Confirmed(true)
            // — the sync short-circuits on value equality. The value-mismatch sync
            // is pinned by rebuiltDialogSyncsCheckboxToInheritedConfirmedFalse.
            assertTrue(bLog.isEmpty())
            a.dispose()
            b.dispose()
        } finally {
            appScope.cancel()
            io.close()
        }
    }

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
            val aStatus = StatusLog()
            val a = uiController(coordinator, initial = false, log = aLog, statusLog = aStatus)
            assertEquals("replay is Provisional, not a confirmation",
                listOf<BbcEffectStatus>(BbcEffectStatus.Provisional), aStatus.snapshot())

            a.onToggle(true) // seq 1: enters a genuinely blocking commit
            sink.awaitFirstStarted()
            assertEquals(listOf("start:true"), sink.snapshot())

            a.dispose() // UI-only; the in-flight commit is NOT cancelled
            aStatus.clear() // nothing may arrive on the disposed collector

            val bLog = UiLog()
            val bStatus = StatusLog()
            val b = uiController(coordinator, initial = true, log = bLog, statusLog = bStatus)
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
            // Confirmed commits fire NO PER-INTENT callback; the Confirmed branch
            // only writes lastGood. They ARE visible on the app-level statusLog:
            // B's collector sees A's Confirmed(true) then its own Confirmed(false).
            // F03: B taps seq 2, so its box already shows its own latest intent
            // and the inherited Confirmed(true) must NOT sync it — the UiLog pin
            // below holds by the D05 last-intent-wins rule, not because onRestored
            // is unreachable. currentLastGood() stays the per-intent observation:
            // it advances only for a LIVE controller whose own intent is current.
            assertEquals(
                listOf<BbcEffectStatus>(
                    BbcEffectStatus.Provisional,
                    BbcEffectStatus.Confirmed(true),
                    BbcEffectStatus.Confirmed(false),
                ),
                bStatus.snapshot(),
            )
            assertTrue("disposed A must receive no status after dispose", aStatus.isEmpty())
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
            // currentLastGood() is the real per-intent observation (Confirmed
            // fires no per-intent callback; its app-level status delivery is
            // covered by the sibling tests, so UiLog.isEmpty() is secondary):
            // a LIVE controller would have advanced lastGood to true,
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

    // --- F03: inherited-confirmed CheckBox sync on a rebuilt dialog -----------

    @Test
    fun rebuiltDialogSyncsCheckboxToInheritedConfirmedFalse() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BlockingSink(initial = true)
            val state = BbcToggleRuntimeState(initialConfirmed = true)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state, commit = { sink.commit(it) }, io = io,
            )
            val aLog = UiLog()
            val aStatus = StatusLog()
            val a = uiController(coordinator, initial = true, log = aLog, statusLog = aStatus)

            a.onToggle(false) // seq 1: OFF enters a genuinely blocking commit
            sink.awaitFirstStarted()
            a.dispose() // UI-only; the in-flight commit is NOT cancelled
            aStatus.clear() // a disposed dialog must receive nothing further

            // B is rebuilt from a stale pre-commit read (true) and taps nothing.
            val bRestored = RestoredLog()
            val bStatus = StatusLog()
            val b = syncedController(
                coordinator, initial = true, restored = bRestored, statusLog = bStatus,
            )
            assertEquals("replay is still Provisional while A's commit is blocked",
                listOf<BbcEffectStatus>(BbcEffectStatus.Provisional), bStatus.snapshot())

            sink.releaseFirst()
            sink.awaitFirstEnded()
            withTimeout(5_000L) { while (state.confirmedOrUnknown != false) yield() }
            withTimeout(5_000L) { while (BbcEffectStatus.Confirmed(false) !in bStatus.snapshot()) yield() }
            withTimeout(5_000L) { while (false !in bRestored.snapshot()) yield() }

            // B never submitted a tap: the inherited Confirmed(false) is the only
            // authority for its stale box, so the sync must correct it.
            assertEquals("B synced its stale box to the inherited Confirmed(false)",
                listOf(false), bRestored.snapshot())
            assertFalse("lastGood must follow the sync", b.currentLastGood())
            assertTrue(BbcEffectStatus.Confirmed(false) in bStatus.snapshot())
            assertEquals(1L, coordinator.latestSequence()) // B submitted no intent
            assertEquals(listOf("start:false", "end:false"), sink.snapshot())
            assertTrue("disposed A's UI must be cut", aLog.isEmpty())
            assertTrue("disposed A must receive no status at all", aStatus.isEmpty())
            a.dispose()
            b.dispose()
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    @Test
    fun newerLocalIntentWinsOverStaleInheritedConfirmed() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BlockingSink(initial = true)
            val state = BbcToggleRuntimeState(initialConfirmed = true)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state, commit = { sink.commit(it) }, io = io,
            )
            val a = uiController(coordinator, initial = true, log = UiLog())
            a.onToggle(true) // seq 1: ON enters a genuinely blocking commit
            sink.awaitFirstStarted()
            a.dispose() // UI-only; the stale commit survives

            val bRestored = RestoredLog()
            val bStatus = StatusLog()
            val b = syncedController(
                coordinator, initial = false, restored = bRestored, statusLog = bStatus,
            )
            b.onToggle(false) // seq 2: B's own NEWER intent; single writer still held by A
            assertEquals("B must wait for the single writer",
                listOf("start:true"), sink.snapshot())

            sink.releaseFirst() // release A's stale commit LAST
            sink.awaitSecondEnded()
            withTimeout(5_000L) { while (state.confirmedOrUnknown != false) yield() }
            withTimeout(5_000L) { while (BbcEffectStatus.Confirmed(false) !in bStatus.snapshot()) yield() }

            // A's stale Confirmed(true) DID reach B's app-level collector, but B
            // tapped seq 2: its box already shows false and D05 last-intent-wins
            // forbids syncing from an inherited/older Confirmed.
            assertTrue("stale inherited Confirmed(true) is still observed app-level",
                BbcEffectStatus.Confirmed(true) in bStatus.snapshot())
            assertFalse("stale inherited true must never sync B's CheckBox",
                true in bRestored.snapshot())
            assertFalse("B's own intent stays current", b.currentLastGood())
            assertEquals(listOf("start:true", "end:true", "start:false", "end:false"),
                sink.snapshot())
            assertEquals(2L, coordinator.latestSequence())
            a.dispose()
            b.dispose()
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    // --- PR #90 Phase 2: split lanes, explicit same-axis supersession --------

    private fun TestScope.splitLaneCoordinator(
        sink: Sink,
        holder: BbcActivationConsentHolder,
        consentCommit: suspend () -> BbcWriteOutcome = { BbcWriteOutcome.Unknown },
        consentRead: suspend (Long) -> BbcConsentAuthoritativeRead = { generation ->
            BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
        },
        state: BbcToggleRuntimeState = BbcToggleRuntimeState(initialConfirmed = false),
        effectRead: suspend () -> BbcRuntimePrefValue = { BbcRuntimePrefValue.UNAVAILABLE },
    ): BbcToggleCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        return BbcToggleCoordinator(
            scope = appScope, state = state, commit = { sink.commit(it) }, io = dispatcher,
            consentCommit = consentCommit, consentRead = consentRead, consentHolder = holder,
            effectRead = effectRead,
        )
    }

    @Test
    fun queuedConsentReadNeverReplacesQueuedOff() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val reads = mutableListOf<Long>()
        val coordinator = splitLaneCoordinator(
            sink, holder,
            consentRead = { generation -> reads.add(generation); BbcConsentDurableSnapshot.failClosed().forGeneration(generation) },
        )

        assertEquals(
            BbcWorkEnqueue.Accepted,
            coordinator.submit(1L, true, onResult = {}),
        )
        testScheduler.runCurrent()
        assertEquals(listOf(true), sink.commits) // seq 1 holds the one writer

        var offReplaced = false
        var offOutcome: BbcWriteOutcome? = null
        assertEquals(
            "OFF must be accepted while another commit is in flight",
            BbcWorkEnqueue.Accepted,
            coordinator.submit(
                2L, false,
                onReplaced = { offReplaced = true },
                onResult = { offOutcome = it },
            ),
        )
        assertEquals("a read must not displace the queued OFF",
            BbcWorkEnqueue.Accepted, coordinator.submitConsentRead {})

        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("the read must NOT replace the OFF", listOf(true, false), sink.commits)
        assertEquals(false, sink.persisted)
        assertEquals(BbcWriteOutcome.Confirmed(false), offOutcome)
        assertFalse("the OFF must not be reported as replaced", offReplaced)
        assertEquals(1, reads.size)
    }

    @Test
    fun queuedConsentReadNeverReplacesGrantWrite() = runTest {
        val sink = Sink(initial = false)
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val gate = CompletableDeferred<Unit>()
        var consentWrites = 0
        var outcomes = 0
        val reads = mutableListOf<Long>()
        val coordinator = splitLaneCoordinator(
            sink, holder,
            consentCommit = {
                consentWrites++
                gate.await()
                BbcWriteOutcome.Confirmed(true)
            },
            consentRead = { generation -> reads.add(generation); BbcConsentDurableSnapshot.failClosed().forGeneration(generation) },
        )

        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submitConsentWrite(1L, 1L) { outcomes++ })
        testScheduler.runCurrent()
        assertEquals(1, consentWrites)
        assertEquals("the read must not take the consent writer's slot",
            BbcWorkEnqueue.Accepted, coordinator.submitConsentRead {})

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("the grant write must still run", 1, consentWrites)
        assertEquals(1, outcomes)
        assertEquals(1, reads.size)
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        // The same app consumer converges phase 2: exactly one runtime enable.
        assertEquals(listOf(true), sink.commits)
    }

    @Test
    fun sameAxisSupersessionIsExplicitAndReported() = runTest {
        val sink = Sink(initial = false)
        val gateToggle = CompletableDeferred<Unit>()
        sink.gates[0] = gateToggle
        val gateConsent = CompletableDeferred<Unit>()
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        var consentCalls = 0
        val coordinator = splitLaneCoordinator(
            sink, holder,
            consentCommit = { consentCalls++; gateConsent.await(); BbcWriteOutcome.Unknown },
        )

        var olderToggleReplaced = false
        var olderToggleResult: BbcWriteOutcome? = null
        // The callback belongs to the DISPLACED caller: register it on the
        // older intent, not on the newer one that does the replacing.
        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submit(
            1L, false,
            onReplaced = { olderToggleReplaced = true },
            onResult = { olderToggleResult = it },
        ))
        // Both submits happen before the consumer is driven: the second one
        // must explicitly replace the pending first one (not silently drop it).
        var newerToggleOutcome: BbcWriteOutcome? = null
        assertEquals("a newer Toggle supersedes an older Toggle only",
            BbcWorkEnqueue.ReplacedOlder,
            coordinator.submit(2L, true) { newerToggleOutcome = it },
        )
        assertEquals("an older Toggle cannot displace the newer pending Toggle",
            BbcWorkEnqueue.SupersededByNewer,
            coordinator.submit(1L, false) {},
        )

        var olderConsentReplaced = false
        var olderConsentResult: BbcWriteOutcome? = null
        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submitConsentWrite(
            1L, 1L,
            onReplaced = { olderConsentReplaced = true },
            onResult = { olderConsentResult = it },
        ))
        var newerConsentResult: BbcWriteOutcome? = null
        assertEquals(BbcWorkEnqueue.ReplacedOlder,
            coordinator.submitConsentWrite(2L, 1L) { newerConsentResult = it })

        gateToggle.complete(Unit)
        gateConsent.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue("the displaced Toggle must be told it was replaced", olderToggleReplaced)
        assertTrue("the displaced consent write must be told it was replaced", olderConsentReplaced)
        assertNull("a replaced intent must get no outcome", olderToggleResult)
        assertNull("a replaced consent write must get no outcome", olderConsentResult)
        assertEquals(BbcWriteOutcome.Confirmed(true), newerToggleOutcome)
        assertEquals(BbcWriteOutcome.Unknown, newerConsentResult)
        assertEquals(listOf(true), sink.commits)
        assertEquals(1, consentCalls)
    }

    @Test
    fun readsCoalesceWithReadsOnlyAndReportDisplacement() = runTest {
        val sink = Sink(initial = false)
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val gate = CompletableDeferred<Unit>()
        val reads = mutableListOf<Long>()
        val coordinator = splitLaneCoordinator(
            sink, holder,
            consentCommit = { gate.await(); BbcWriteOutcome.Unknown },
            consentRead = { generation -> reads.add(generation); BbcConsentDurableSnapshot.failClosed().forGeneration(generation) },
        )
        var firstReadCoalesced = false
        var firstReadResult: BbcConsentAuthoritativeRead? = null
        var secondReadResult: BbcConsentAuthoritativeRead? = null

        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submitConsentWrite(1L, 1L) {})
        testScheduler.runCurrent() // consent write holds the writer
        assertEquals(BbcWorkEnqueue.Accepted,
            coordinator.submitConsentRead(onCoalesced = { firstReadCoalesced = true }) {
                firstReadResult = it
            })
        assertEquals("the second read replaces only the first read",
            BbcWorkEnqueue.ReplacedOlder, coordinator.submitConsentRead(onResult = {
                secondReadResult = it
            }))
        assertEquals("a read never displaces user work",
            BbcWorkEnqueue.Accepted, coordinator.submit(2L, false) {})

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(firstReadCoalesced)
        assertNull(firstReadResult)
        assertTrue("the surviving read must run", secondReadResult != null)
        assertEquals(listOf(1L), reads)
        assertEquals("OFF still ran behind the coalesced reads", listOf(false), sink.commits)
    }

    /**
     * Re-review P1-A: a consent grant already superseded before the consumer
     * drains its slot must be SKIPPED before the store seam is entered. No
     * optin/marker write, and the enqueuing caller gets an honest non-granting
     * outcome instead of a fabricated Confirmed/Unknown.
     */
    @Test
    fun supersededConsentWrite_isSkippedBeforeAnyStoreWrite() = runTest {
        val sink = Sink(initial = false)
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        var consentCalls = 0
        val outcomes = mutableListOf<BbcWriteOutcome>()
        val coordinator = splitLaneCoordinator(
            sink, holder,
            consentCommit = { consentCalls++; BbcWriteOutcome.Confirmed(true) },
        )

        val grantSeq = holder.startConsentIntent()
        holder.supersedeActiveIntent() // OFF/cancel arrives before the drain
        assertEquals(BbcWorkEnqueue.Accepted,
            coordinator.submitConsentWrite(grantSeq, 1L, onResult = { outcomes.add(it) }))
        testScheduler.advanceUntilIdle()

        assertEquals("no superseded grant may enter the consent store", 0, consentCalls)
        assertEquals(listOf<BbcWriteOutcome>(BbcWriteOutcome.Skipped), outcomes)
        assertEquals(0, sink.commits.size)
        assertFalse(holder.currentState() is BbcActivationConsentState.Granted)
    }

    /**
     * PR #90 Phase 3 (R3-06a/E2): the authoritative effect read is a dedicated
     * lane on the app IO consumer. It seeds the row provisionally, never
     * Confirmed, never advances the write fence, and never enqueues a commit.
     * Reads coalesce with reads only.
     */
    @Test
    fun effectRead_runsOnIoLaneSeedsProvisionalNeverConfirms() = runTest {
        val sink = Sink(initial = false)
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val state = BbcToggleRuntimeState() // production-neutral seed
        var reads = 0
        val coordinator = splitLaneCoordinator(
            sink, holder, state = state, effectRead = { reads++; BbcRuntimePrefValue.FALSE },
        )

        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submitEffectRead())
        testScheduler.advanceUntilIdle()

        assertEquals(1, reads)
        assertEquals(BbcEffectStatus.ProvisionalRead(false), state.effectStatus.value)
        assertNull("a read is not a Confirmed write outcome", state.confirmedOrUnknown)
        assertTrue("a read must never drive an effect commit", sink.commits.isEmpty())
        assertEquals(0L, state.latestSequence())

        var firstCoalesced = false
        assertEquals(BbcWorkEnqueue.Accepted,
            coordinator.submitEffectRead(onCoalesced = { firstCoalesced = true }))
        assertEquals("a newer read replaces only the older read",
            BbcWorkEnqueue.ReplacedOlder, coordinator.submitEffectRead())
        testScheduler.advanceUntilIdle()
        assertTrue(firstCoalesced)
        assertEquals(2, reads) // only the surviving read ran
        assertEquals(BbcEffectStatus.ProvisionalRead(false), state.effectStatus.value)
    }

    // --- Phase 2 fixer (BLOCKING 4): never promise work to a dead consumer ---

    @Test
    fun deadConsumer_neverReportsAcceptedAndNeverRunsWork() = runTest {
        val sink = Sink(initial = false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { sink.commit(it) }, io = dispatcher,
        )
        testScheduler.runCurrent()
        assertTrue("consumer must be alive before cancellation", coordinator.isConsumerActive())
        appScope.cancel()
        testScheduler.runCurrent()
        assertFalse("consumer must be dead after app-scope cancellation", coordinator.isConsumerActive())

        var toggleResult: BbcWriteOutcome? = null
        var toggleReplaced = false
        var consentResult: BbcWriteOutcome? = null
        var consentReplaced = false
        var readResult: BbcConsentAuthoritativeRead? = null
        var readCoalesced = false
        assertEquals("a dead consumer cannot accept a toggle", BbcWorkEnqueue.Rejected,
            coordinator.submit(1L, false, onReplaced = { toggleReplaced = true },
                onResult = { toggleResult = it }))
        assertEquals("a dead consumer cannot accept a consent write", BbcWorkEnqueue.Rejected,
            coordinator.submitConsentWrite(2L, 1L, onReplaced = { consentReplaced = true },
                onResult = { consentResult = it }))
        assertEquals("a dead consumer cannot accept a read", BbcWorkEnqueue.Rejected,
            coordinator.submitConsentRead(onCoalesced = { readCoalesced = true },
                onResult = { readResult = it }))
        testScheduler.advanceUntilIdle()

        assertEquals(0, sink.commits.size)
        assertNull("a rejected toggle must get no outcome", toggleResult)
        assertNull("a rejected consent write must get no outcome", consentResult)
        assertNull("a rejected read must get no result", readResult)
        assertFalse("nothing was displaced, so no replaced callback may fire", toggleReplaced)
        assertFalse(consentReplaced)
        assertFalse(readCoalesced)
    }

    @Test
    fun commitCancellationWhileAppScopeAlive_mapsToUnknownAndKeepsConsumer() = runTest {
        var commitCalls = 0
        val outcomes = mutableListOf<BbcWriteOutcome>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { commitCalls++; throw CancellationException("seam interrupted") },
            io = dispatcher,
        )
        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submit(1L, false, onResult = { outcomes.add(it) }))
        testScheduler.advanceUntilIdle()
        assertEquals("a commit CancellationException is not an app-scope shutdown",
            listOf(BbcWriteOutcome.Unknown), outcomes)
        assertTrue("the single app-lifetime consumer must survive", coordinator.isConsumerActive())

        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submit(2L, true, onResult = { outcomes.add(it) }))
        testScheduler.advanceUntilIdle()
        assertEquals("a second submit must still run on the same consumer", 2, commitCalls)
    }

    @Test
    fun consentCommitCancellationWhileAppScopeAlive_installsFenceNotCrash() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val outcomes = mutableListOf<BbcWriteOutcome>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { BbcWriteOutcome.Confirmed(it) }, io = dispatcher,
            consentCommit = { throw CancellationException("binder interrupted") },
            consentHolder = holder,
        )
        assertEquals(BbcWorkEnqueue.Accepted,
            coordinator.submitConsentWrite(1L, 1L, onResult = { outcomes.add(it) }))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(BbcWriteOutcome.Unknown), outcomes)
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertTrue(coordinator.isConsumerActive())
    }

    /**
     * Regression: a toggle whose commit was skipped before touching any store
     * must leave the app-scoped effect holder untouched — no fence advance, no
     * erasure of a prior Confirmed(true), no Unknown status emission — while the
     * enqueuing caller still receives the honest Skipped outcome.
     */
    @Test
    fun skippedToggleOutcome_leavesHolderUntouchedButStillCallsBack() = runTest {
        val state = BbcToggleRuntimeState(initialConfirmed = true)
        assertTrue(state.mark(BbcWriteOutcome.Confirmed(true), 1L))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        var commitOutcome: BbcWriteOutcome = BbcWriteOutcome.Skipped
        val commits = mutableListOf<Boolean>()
        val outcomes = mutableListOf<BbcWriteOutcome>()
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = state,
            commit = { value -> commits.add(value); commitOutcome },
            io = dispatcher,
        )

        assertEquals(BbcWorkEnqueue.Accepted,
            coordinator.submit(2L, false, onResult = { outcomes.add(it) }))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(false), commits)
        assertEquals(listOf<BbcWriteOutcome>(BbcWriteOutcome.Skipped), outcomes)
        assertEquals("no durable key was touched: prior confirmation survives",
            true, state.confirmedOrUnknown)
        assertEquals("Skipped must never emit an Unknown effect status",
            BbcEffectStatus.Confirmed(true), state.effectStatus.value)

        // Fence probe: Skipped did not advance state to seq2, so a real Confirmed
        // at that same sequence must still apply.
        commitOutcome = BbcWriteOutcome.Confirmed(false)
        assertEquals(BbcWorkEnqueue.Accepted,
            coordinator.submit(2L, false, onResult = { outcomes.add(it) }))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, false), commits)
        assertEquals(
            listOf<BbcWriteOutcome>(BbcWriteOutcome.Skipped, BbcWriteOutcome.Confirmed(false)),
            outcomes,
        )
        assertEquals(false, state.confirmedOrUnknown)
    }

    @Test
    fun unknownNeverSyncsCheckboxAndStaysAtIntent() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BlockingSink(initial = true)
            val state = BbcToggleRuntimeState(initialConfirmed = true)
            var firstCommit = true
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state,
                commit = { value ->
                    val outcome = sink.commit(value)
                    // D04: force the first commit to Unknown while keeping the
                    // real blocking handshake (no sleeps, no cancelled commits).
                    if (firstCommit) { firstCommit = false; BbcWriteOutcome.Unknown } else outcome
                },
                io = io,
            )
            val restored = RestoredLog()
            val statuses = StatusLog()
            val log = UiLog()
            val c = syncedController(
                coordinator, initial = true, restored = restored, statusLog = statuses, log = log,
            )
            assertEquals("replay is Provisional, not a confirmation",
                listOf<BbcEffectStatus>(BbcEffectStatus.Provisional), statuses.snapshot())

            c.onToggle(false) // seq 1 -> forced Unknown; D04 keeps box at tapped false
            sink.awaitFirstStarted()
            sink.releaseFirst()
            sink.awaitFirstEnded()
            withTimeout(5_000L) { while (BbcEffectStatus.Unknown !in statuses.snapshot()) yield() }
            withTimeout(5_000L) { while (log.unknown == 0) yield() }

            assertEquals("D04: Unknown never syncs the CheckBox", 0, restored.snapshot().size)
            assertEquals(1, log.unknown) // per-intent Unknown still fires
            assertEquals(true, c.currentLastGood()) // no rollback; lastGood stays confirmed
            assertNull(state.confirmedOrUnknown) // Unknown clears app-level confirmation

            // A later Confirmed from THIS dialog's OWN tap (seq 2) clears the
            // Unknown styling but does not sync the box: a local intent exists,
            // so D05 last-intent-wins suppresses inherited-confirmed sync.
            c.onToggle(true) // seq 2
            withTimeout(5_000L) { while (BbcEffectStatus.Confirmed(true) !in statuses.snapshot()) yield() }
            withTimeout(5_000L) { while (state.confirmedOrUnknown != true) yield() }

            assertEquals(0, restored.snapshot().size)
            assertEquals(true, c.currentLastGood())
            assertEquals(2L, coordinator.latestSequence())
            c.dispose()
        } finally {
            appScope.cancel()
            io.close()
        }
    }

    // --- Phase C / #81: sequence fence BEFORE commit ---------------------------

    /**
     * #81: the FIRST commit (the newer sequence) blocks on a real latch; any
     * LATER commit (the stale sequence) records that it actually reached disk.
     * Latches/volatiles are pure handshakes — no sleeps, no cancellable gates.
     */
    private class SequenceOrderSink(initial: Boolean) {
        @Volatile
        var persisted: Boolean = initial
            private set

        @Volatile
        var staleCommitEnded = false
            private set

        private val events = Collections.synchronizedList(mutableListOf<String>())
        private val calls = AtomicInteger(0)
        private val newerStarted = CountDownLatch(1)
        private val releaseNewer = CountDownLatch(1)

        suspend fun commit(value: Boolean): BbcWriteOutcome = withContext(NonCancellable) {
            val idx = calls.getAndIncrement()
            events.add("start:$value")
            if (idx == 0) {
                newerStarted.countDown()
                releaseNewer.await() // genuinely blocking, never cancelled mid-call
            } else {
                staleCommitEnded = true // BUG SIGNAL: the fenced-out write ran
            }
            events.add("end:$value")
            persisted = value
            BbcWriteOutcome.Confirmed(value)
        }

        fun awaitNewerStarted() = check(newerStarted.await(5, TimeUnit.SECONDS))
        fun releaseNewerWrite() = releaseNewer.countDown()
        fun snapshot(): List<String> = synchronized(events) { events.toList() }
    }

    /**
     * Two callers can assign sequences with nextSequence() in one order but
     * reach submit() in the OPPOSITE order. CONFLATED then queues the older
     * sequence behind the newer commit; without a pre-commit fence the older
     * value is written LAST and overwrites the user's final intent (#81).
     *
     * Determinism: the newer commit holds a real single-thread IO writer on a
     * CountDownLatch; a separate thread submits the older sequence and latches
     * only after trySend() returned. Releasing the latch is the sole scheduling
     * decision. Both outcomes expose a positive drain signal — the buggy build
     * ends the stale commit, the fixed build increments skippedWrites() — so
     * there is no timeout-based negative assertion and no wall-clock sleep.
     */
    @Test
    fun staleSequenceSubmittedWhileNewerCommitBlocked_neverReachesDisk() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val submitter = Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-c81-submitter") }
        val sink = SequenceOrderSink(initial = false)
        try {
            val state = BbcToggleRuntimeState(initialConfirmed = false)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state, commit = { sink.commit(it) }, io = io,
            )

            // Sequence assigned FIRST but submitted LAST; the newer one is
            // assigned second yet submitted first — exactly the #81 race.
            val staleSeq = state.nextSequence()
            val freshSeq = state.nextSequence()
            assertEquals(1L, staleSeq)
            assertEquals(2L, freshSeq)
            val staleCallbackCalls = AtomicInteger(0)
            val freshCallbackCalls = AtomicInteger(0)

            coordinator.submitWithExplicitSequenceForTests(freshSeq, true) { freshCallbackCalls.incrementAndGet() }
            sink.awaitNewerStarted() // newer commit is in flight and blocked

            val submitReturned = CountDownLatch(1)
            submitter.execute {
                coordinator.submitWithExplicitSequenceForTests(staleSeq, false) { staleCallbackCalls.incrementAndGet() }
                submitReturned.countDown()
            }
            check(submitReturned.await(5, TimeUnit.SECONDS))

            sink.releaseNewerWrite()

            // Positive drain barrier in both worlds; bounded only as a deadlock
            // guard, never used to decide pass/fail.
            withTimeout(5_000L) {
                while (coordinator.skippedWrites() == 0 && !sink.staleCommitEnded) yield()
            }

            assertEquals("newer value must be the final persisted value", true, sink.persisted)
            assertEquals("only the newer value may reach the store",
                listOf("start:true", "end:true"), sink.snapshot())
            assertEquals(1, freshCallbackCalls.get())
            assertEquals("stale write must never notify its caller", 0, staleCallbackCalls.get())
            assertEquals(
                "older sequence must be rejected BEFORE commit()",
                1, coordinator.skippedWrites(),
            )
            assertEquals(2L, coordinator.latestSequence())
        } finally {
            sink.releaseNewerWrite() // idempotent: no latch-blocked thread survives a failure
            submitter.shutdownNow()
            appScope.cancel()
            io.close()
        }
    }

    // --- Follow-up B: sequence allocation + enqueue in ONE critical section ---

    /**
     * Discriminating test for the #81 follow-up. The test seam parks the FIRST
     * atomic submit after allocating seq 2 but BEFORE trySend, while the single
     * consumer is held on a real latch. A SECOND submit on another thread must
     * then be unable to finish — indeed unable to allocate seq 3 — until the
     * lock is freed. After release the HIGHEST sequence's value is what reaches
     * disk and only its callback fires.
     *
     * "Cannot finish" is a bounded latch wait whose PASS condition is the
     * timeout; no wall-clock sleep is used as a pass/fail signal.
     */
    @Test
    fun enqueueIsAtomicWithSequenceAllocation() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstSubmitter = Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-atomic-first") }
        val secondSubmitter = Executors.newSingleThreadExecutor { r -> Thread(r, "bbc-atomic-second") }
        val sink = BlockingSink(initial = false)
        val releasePark = CountDownLatch(1) // declared outside try so cleanup can always free it
        try {
            val state = BbcToggleRuntimeState(initialConfirmed = false)
            val coordinator = BbcToggleCoordinator(
                scope = appScope, state = state, commit = { sink.commit(it) }, io = io,
            )

            // Occupy the single consumer so neither atomic write is dequeued
            // while the enqueue-lock handoff is under test. The blocker writes
            // TRUE; the final value below is FALSE, so disk proves who won.
            val blockerCallbacks = AtomicInteger(0)
            coordinator.submitWithExplicitSequenceForTests(state.nextSequence(), true) { blockerCallbacks.incrementAndGet() }
            sink.awaitFirstStarted()

            val allocatedSeq2 = CountDownLatch(1)
            coordinator.afterBbcSequenceAllocatedForTests = { seq ->
                if (seq == 2L) {
                    allocatedSeq2.countDown()
                    check(releasePark.await(5, TimeUnit.SECONDS)) // parked inside the lock
                }
            }

            val firstSeq = AtomicLong(0L)
            val firstReturned = CountDownLatch(1)
            val firstCallbacks = AtomicInteger(0)
            firstSubmitter.execute {
                firstSeq.set(coordinator.submit(true) { firstCallbacks.incrementAndGet() })
                firstReturned.countDown()
            }
            check(allocatedSeq2.await(5, TimeUnit.SECONDS))
            assertEquals("only the parked submit allocated so far", 2L, coordinator.latestSequence())

            val secondSeq = AtomicLong(0L)
            val secondStarted = CountDownLatch(1) // proves the 2nd thread reached submit()
            val secondReturned = CountDownLatch(1)
            val secondCallbacks = AtomicInteger(0)
            secondSubmitter.execute {
                secondStarted.countDown() // immediately before submit(false)
                secondSeq.set(coordinator.submit(false) { secondCallbacks.incrementAndGet() })
                secondReturned.countDown()
            }
            check(secondStarted.await(5, TimeUnit.SECONDS))

            // NEGATIVE OBSERVATION: a split allocate/enqueue would let this
            // finish immediately; the atomic one cannot make progress at all.
            assertFalse(
                "second submit must not complete while the first is parked inside the enqueue lock",
                secondReturned.await(300, TimeUnit.MILLISECONDS),
            )
            assertEquals(
                "second submit must not even allocate while the lock is held",
                2L, coordinator.latestSequence(),
            )

            releasePark.countDown()
            check(firstReturned.await(5, TimeUnit.SECONDS))
            check(secondReturned.await(5, TimeUnit.SECONDS))
            coordinator.afterBbcSequenceAllocatedForTests = null

            sink.releaseFirst()
            sink.awaitSecondEnded()
            withTimeout(5_000L) { while (secondCallbacks.get() == 0) yield() }

            assertEquals(2L, firstSeq.get())
            assertEquals(3L, secondSeq.get())
            assertEquals(3L, coordinator.latestSequence())
            // seq 3 is the last value in the CONFLATED slot; the parked-but-
            // lower seq 2 never even reached the channel.
            assertEquals("highest sequence's value must be the final disk value",
                false, sink.persisted)
            assertEquals(listOf("start:true", "end:true", "start:false", "end:false"),
                sink.snapshot())
            assertEquals("highest sequence's callback must fire", 1, secondCallbacks.get())
            assertEquals("coalesced lower sequence must never notify", 0, firstCallbacks.get())
            assertEquals(1, blockerCallbacks.get())
        } finally {
            releasePark.countDown() // idempotent: no latch-blocked submit survives a failure
            sink.releaseFirst()
            firstSubmitter.shutdownNow()
            secondSubmitter.shutdownNow()
            appScope.cancel()
            io.close()
        }
    }

    /**
     * Three atomic submits with a real latch holding seq 1's commit. Seq 2 is
     * coalesced away; seq 3 is the newest. Asserts the NEWEST value lands on
     * disk, its mark/UI callback fires, and the stale seq-1 Unknown callback
     * does NOT fire. skippedWrites() is checked but is deliberately not the
     * only signal (here it is 0 — the guarantee comes from atomic enqueue and
     * the controller's per-intent fence, not from a pre-commit skip).
     */
    @Test
    fun threeSequences_highestWinsOnDiskAndNotifies() = runBlocking {
        val io = newIoDispatcher()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = BlockingSink(initial = true)
        try {
            val state = BbcToggleRuntimeState(initialConfirmed = true)
            var firstCommit = true
            val coordinator = BbcToggleCoordinator(
                scope = appScope,
                state = state,
                commit = { value ->
                    val outcome = sink.commit(value)
                    // Force the STALE first write to Unknown: if the per-intent
                    // fence broke, onUnknown/onFailed would fire for it.
                    if (firstCommit) { firstCommit = false; BbcWriteOutcome.Unknown } else outcome
                },
                io = io,
            )
            val log = UiLog()
            val statuses = StatusLog()
            val c = uiController(coordinator, initial = true, log = log, statusLog = statuses)

            c.onToggle(true)  // seq 1: enters the latched (stale) commit
            sink.awaitFirstStarted()
            c.onToggle(true)  // seq 2: takes the CONFLATED slot...
            c.onToggle(false) // seq 3: ...and replaces it with the newest intent

            assertEquals(3L, coordinator.latestSequence())
            assertEquals("no further commit may start while seq 1 is blocked",
                listOf("start:true"), sink.snapshot())

            sink.releaseFirst()
            sink.awaitSecondEnded()
            withTimeout(5_000L) { while (state.confirmedOrUnknown != false) yield() }
            withTimeout(5_000L) { while (c.currentLastGood()) yield() }

            assertEquals("newest value must be what actually reached disk", false, sink.persisted)
            assertEquals(listOf("start:true", "end:true", "start:false", "end:false"),
                sink.snapshot())
            assertEquals("state.mark must apply the newest value", false, state.confirmedOrUnknown)
            assertEquals("newest intent's UI callback must fire", false, c.currentLastGood())
            assertTrue("stale Unknown callback must be fenced out", log.isEmpty())
            assertEquals(0, log.unknown)
            assertEquals(0, log.failures)
            assertEquals(
                listOf(
                    BbcEffectStatus.Provisional,
                    BbcEffectStatus.Unknown,
                    BbcEffectStatus.Confirmed(false),
                ),
                statuses.snapshot(),
            )
            assertEquals("guarantee is atomic enqueue + fences, not skipped writes",
                0, coordinator.skippedWrites())
            c.dispose()
        } finally {
            sink.releaseFirst() // idempotent: no latch-blocked commit survives a failure
            appScope.cancel()
            io.close()
        }
    }

    /**
     * Regression for the "publish the token after enqueue" bug. With the
     * Unconfined UI+IO seam the consumer commits, marks and invokes onResult
     * INLINE inside submit(), before submit() returns. A fence that reads a
     * token assigned only after submit() returns therefore treats every tap
     * after the first as stale and drops its own newest result. The fence must
     * be captured from generation (incremented at the top of onToggle) BEFORE
     * enqueue instead.
     */
    @Test
    fun inlineConsumer_newestIntentResultIsNotDroppedByFence() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sink = Sink(initial = false)
        sink.resultAt = { idx -> idx != 2 } // newest (third) intent is forced Unknown
        val coordinator = BbcToggleCoordinator(
            scope = appScope,
            state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { sink.commit(it) },
            io = Dispatchers.Unconfined,
        )
        val log = UiLog()
        val c = BbcInjectionToggleController(
            coordinator = coordinator,
            initialValue = false,
            main = Dispatchers.Unconfined, // UI block runs inline inside onResult inside submit()
            onRestored = { log.restored.add(it) },
            onFailed = { log.failures++ },
            onUnknown = { log.unknown++ },
        )
        try {
            c.onToggle(true) // seq 1: Confirmed(true) -> lastGood true
            assertEquals(true, c.currentLastGood())

            c.onToggle(false) // seq 2: Confirmed(false) -> newest result must apply
            assertEquals("newest Confirmed result must not be dropped",
                false, c.currentLastGood())

            c.onToggle(true) // seq 3: forced Unknown -> newest Unknown must be reported
            assertEquals("newest Unknown result must not be dropped", 1, log.unknown)
            assertEquals(1, log.failures)
            assertEquals("Unknown must never advance lastGood", false, c.currentLastGood())
            assertEquals(3L, coordinator.latestSequence())
            c.dispose()
        } finally {
            c.dispose()
            appScope.cancel()
        }
    }

    // --- Source shape: the controller must not split allocation from enqueue --

    private fun prodSource(name: String): File {
        val candidates = listOf(
            File("src/main/java/com/galaxydual/plus/$name"),
            File("app/src/main/java/com/galaxydual/plus/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing $name (tried ${candidates.map { it.path }})")
    }

    private fun functionBlock(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("$signature must open a block", brace >= 0)
        var depth = 0
        for (i in brace until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("$signature block never closes")
    }

    @Test
    fun sourceShape_controllerUsesAtomicSubmit_only() {
        val onToggle = functionBlock(
            prodSource("BbcInjectionToggleController.kt").readText(), "fun onToggle(",
        )
        assertFalse(
            "controller must NOT call state.nextSequence() directly before coordinator.submit(",
            onToggle.contains("state.nextSequence()"),
        )
        assertTrue("controller must use the atomic submit(value, onResult) overload",
            onToggle.contains("coordinator.submit(checked)"))
        assertTrue("controller must adopt the sequence returned by atomic submit",
            onToggle.contains("lastSubmittedSequence = seq"))
        assertTrue(
            "per-intent fence must be captured from generation BEFORE enqueue",
            onToggle.contains("val myIntent = generation") &&
                onToggle.indexOf("val myIntent = generation") <
                onToggle.indexOf("coordinator.submit(checked)") &&
                onToggle.contains("myIntent != generation"),
        )

        // Pin the critical section itself, not just the presence of its tokens
        // somewhere in the file: a mutation that leaves the lock before
        // trySend() must fail this test.
        val coordinatorSrc = prodSource("BbcToggleCoordinator.kt").readText()
        val atomicSubmit = functionBlock(coordinatorSrc, "fun submit(value: Boolean")
        val critical = functionBlock(atomicSubmit, "synchronized(enqueueLock)")
        assertTrue("enqueue must share the lock with allocation",
            critical.contains("writes.trySend("))
        assertTrue("allocation must be inside the lock",
            critical.contains("state.nextSequence()"))
    }
}
