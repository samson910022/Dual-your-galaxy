// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 2 / N05：BBC 開關連點併發的 framework-free JVM 測試。
//
// EN: every test drives the controller on ONE TestScheduler (scope, io and main
// all share it), so ordering is decided by runCurrent()/advanceUntilIdle() and
// CompletableDeferred gates — never a wall-clock sleep, never Robolectric (this
// project has no Robolectric). The "gate" maximises the window in which an
// older commit is still running when a newer tap arrives; the actor must then
// drop the stale result so the final persisted value == the final user intent.
//
// 中文：所有測試把 scope/io/main 都指向同一個 TestScheduler，用 gate 控制完成順序，
// 不靠睡眠、不靠 Robolectric。柵欄拉大「舊提交還在飛、新點擊已到」的視窗，
// 驗證 generation 柵欄作廢舊結果，最終 persisted 必等於最後一次意圖。
package com.galaxydual.plus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var resultAt: (Int) -> Boolean = { true }
        var throwAt: (Int) -> Boolean = { false }

        suspend fun commit(value: Boolean): Boolean {
            val idx = commits.size
            commits.add(value)
            gates[idx]?.await()
            if (throwAt(idx)) throw RuntimeException("store boom @$idx")
            val ok = resultAt(idx)
            if (ok) persisted = value
            return ok
        }
    }

    private fun TestScope.controller(
        initial: Boolean,
        sink: Sink,
        onRestored: (Boolean) -> Unit = { sink.restored.add(it) },
        onFailed: (() -> Unit)? = { sink.failures++ },
    ): BbcInjectionToggleController {
        // One dispatcher for scope/io/main: everything is deterministic on the
        // single TestScheduler owned by runTest; no Main dispatcher needed.
        val dispatcher = StandardTestDispatcher(testScheduler)
        return BbcInjectionToggleController(
            scope = this,
            store = { sink.commit(it) },
            initialValue = initial,
            io = dispatcher,
            main = dispatcher,
            onRestored = onRestored,
            onFailed = onFailed,
        )
    }

    @Test
    fun rapidToggle_lastIntentWinsWhenEarlierCommitDelayed() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        val c = controller(initial = false, sink = sink)

        c.onToggle(true) // ON (gen 1): commit starts, blocks on gate0.
        testScheduler.runCurrent()
        assertEquals(listOf(true), sink.commits)

        c.onToggle(false) // OFF (gen 2): newer tap supersedes while #1 is in IO.
        gate0.complete(Unit) // release the stale completion
        testScheduler.advanceUntilIdle()

        // Single actor serialises commits, so OFF is the value that actually
        // lands last; the gen-1 result is ignored (no rollback, no toast).
        assertEquals(listOf(true, false), sink.commits)
        assertEquals(false, sink.persisted)
        assertEquals(false, c.currentLastGood())
        assertEquals(0, sink.restored.size)
        assertEquals(0, sink.failures)
        assertTrue("generation must advance per tap", c.currentGeneration() >= 2L)
        c.dispose()
    }

    @Test
    fun rapidToggle_onOffOn_reorderedIO_finalPersistEqualsFinalIntent() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        val c = controller(initial = false, sink = sink)

        c.onToggle(true) // ON (gen 1), gated in IO.
        testScheduler.runCurrent()
        c.onToggle(false) // OFF (gen 2)
        c.onToggle(true) // ON (gen 3) — final intent; replaces OFF in the conflated slot.
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(true, sink.persisted) // final persisted == final intent
        assertEquals(true, c.currentLastGood())
        assertTrue("generation must advance per tap", c.currentGeneration() >= 3L)
        // gen-1 result dropped; the OFF tap coalesced into the trailing ON commit.
        assertTrue("bounded commits, got ${sink.commits}", sink.commits.size <= 2)
        assertEquals(true, sink.commits.last())
        c.dispose()
    }

    @Test
    fun rapidToggle_staleFailureDoesNotClobberLastGood() = runTest {
        val sink = Sink(initial = true)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        sink.resultAt = { idx -> idx != 0 } // call 0 (OFF) fails when released
        val c = controller(initial = true, sink = sink)

        // Earlier intent (OFF) is delayed in IO; a newer intent (ON) arrives.
        c.onToggle(false) // gen 1, gated failure
        testScheduler.runCurrent()
        c.onToggle(true) // gen 2, latest intent
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Delayed failure is stale: no rollback, no toast; lastGood advances
        // only through the newer successful commit.
        assertEquals(0, sink.restored.size)
        assertEquals(0, sink.failures)
        assertEquals(true, c.currentLastGood())
        assertEquals(true, sink.persisted)

        // A genuinely current failure must restore the LATEST lastGood (true),
        // not the value that just failed (false) nor a stale snapshot.
        sink.resultAt = { idx -> idx != 2 } // next commit (idx 2) fails
        c.onToggle(false) // gen 3
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true), sink.restored)
        assertEquals(1, sink.failures)
        assertEquals(true, c.currentLastGood())
        c.dispose()
    }

    @Test
    fun rapidToggle_generationRejectsStaleSuccess() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        val gate1 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        sink.gates[1] = gate1
        val c = controller(initial = false, sink = sink)

        c.onToggle(true) // gen 1: stale success, gated
        testScheduler.runCurrent()
        c.onToggle(false) // gen 2: latest intent, gated too
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true, false), sink.commits)
        // The stale true write may have landed in storage, but the generation
        // fence must NOT let it advance lastGood.
        assertEquals("stale success must not advance lastGood", false, c.currentLastGood())

        gate1.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(false, c.currentLastGood())
        assertEquals(false, sink.persisted)
        c.dispose()
    }

    @Test
    fun commitFalse_rollsBackAndReportsFailure() = runTest {
        val sink = Sink(initial = true)
        sink.resultAt = { false } // commit return is the authority
        val c = controller(initial = true, sink = sink)

        c.onToggle(false)
        testScheduler.advanceUntilIdle()

        // The controller does NOT re-read storage: it trusts commit(false) and
        // rolls the CheckBox back to lastGood + reports the failure.
        assertEquals(listOf(true), sink.restored) // rollback to lastGood
        assertEquals(1, sink.failures) // toast path taken
        assertEquals(true, c.currentLastGood())
        c.dispose()
    }

    @Test
    fun rapidToggle_intermediateIntentsCoalesced_boundedCommits() = runTest {
        val sink = Sink(initial = false)
        val gate0 = CompletableDeferred<Unit>()
        sink.gates[0] = gate0
        val c = controller(initial = false, sink = sink)

        c.onToggle(true) // gen 1: first commit blocked in IO
        testScheduler.runCurrent()
        repeat(10) { c.onToggle(it % 2 == 0) } // burst; only the newest survives
        gate0.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Exact survival set: gen-1 ON (gated), then only the LAST tap of the
        // 10-tap burst survives the conflated slot (it=9 -> false). The other
        // nine taps are coalesced away, so no bound/vacuous-zero check needed.
        assertEquals(listOf(true, false), sink.commits)
        assertEquals(false, c.currentLastGood())
        assertEquals(false, sink.persisted)
        assertTrue("generation must advance per tap", c.currentGeneration() >= 11L)
        c.dispose()
    }

    @Test
    fun dispose_cancelsActorAndIgnoresLaterTaps() = runTest {
        val sink = Sink(initial = false)
        val c = controller(initial = false, sink = sink)
        testScheduler.runCurrent() // let the consumer start and park on the channel
        assertTrue("actor must be live before dispose", c.isActorActive())

        c.dispose()
        assertTrue("actor must be cancelled after dispose", !c.isActorActive())

        // Late taps after dispose must be inert: no commit, no rollback, no toast.
        c.onToggle(true)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertEquals(0, sink.commits.size)
        assertEquals(0, sink.restored.size)
        assertEquals(0, sink.failures)
        assertEquals(false, sink.persisted)
    }
}
