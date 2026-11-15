// CI #306 (PR60-P1) regression fix support.
//
// Why this exists: kotlinx-coroutines-test's Dispatchers.resetMain() throws
// IllegalStateException ("Dispatchers.Main is used concurrently with setting
// it") when Main is reset while a reader is still inside a Main-dispatched
// coroutine — e.g. a DualViewModel reconciler child resuming through Main
// during tearDown (CI #306 stack: boundedReconcileRead withTimeout). Suite
// tearDowns historically called
// vm.viewModelScope.cancel() / coord.close() but never JOINED outstanding
// children before resetMain(), so the throw was a race.
//
// THE RULE THIS ENCODES: teardown must deterministically synchronize
// BEFORE resetMain() — release (gate/latches, done by tests in finally) ->
// cancel + bounded join/drain of viewModelScope children and
// coordinator-scope (PolicyTxCoordinator.scope) children -> resetMain LAST.
//
// SCOPE AND HONESTY: this is a TEST-ONLY, best-effort drain. It exists to
// make teardown non-racy; it is NOT a correctness argument for production
// code (production shutdown paths have their own drain semantics), and a green
// run under this helper does NOT prove the underlying CI race is gone — only
// that teardown drains before resetting Main, with each join bounded to
// timeoutMs.
//
// Best-effort semantics (mirrors existing tearDowns): every step is
// per-item tolerant — one failing VM/coordinator must not block the rest,
// and draining must never fail or hang the suite. Uninterruptible blocking
// IO children (interrupt-ignoring reads) are children of viewModelScope when
// no coordinator is used, and of coord.scope when one is; the bounded join
// covers both. The existing tests flip their release latches in `finally`
// before returning, so such children unwind quickly; the timeout only caps
// the pathological case. Joining already-cancelled jobs is safe and returns
// fast. CancellationException raised INSIDE a child job is swallowed by the
// per-item runCatching; nothing outside the child jobs is caught in a way
// that would swallow cancellation of the caller.

package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deterministic pre-`Dispatchers.resetMain()` drain for unit-test suites.
 *
 * Call this AFTER the suite's own best-effort dispose/cancel/close loops and
 * IMMEDIATELY BEFORE `Dispatchers.resetMain()`:
 *
 *   for (vm in vms) { ... }              // suite's existing logic
 *   TestDrainSupport.drainBeforeResetMain(vms, coords)
 *   Dispatchers.resetMain()              // must stay LAST
 *
 * Steps, all bounded and best-effort:
 *  1. viewModelScope.cancel() per VM (per-item try/catch; one VM's failure
 *     does not block the others).
 *  2. Bounded join of every VM scope Job (coroutineContext[Job]) so children
 *     parked at another suspension unwind. With an explicit [pump], Main-bound
 *     children on a test scheduler are resumed before each poll.
 *  3. PolicyTxCoordinator.close() per coordinator (idempotent: cancels the
 *     SupervisorJob scope), then a bounded join of every coordinator scope
 *     Job.
 *
 * @param vms    VMs created by the test (may be empty).
 * @param coords app-scoped coordinators the VMs were wired to (may be empty).
 * @param timeoutMs upper bound per bounded join phase (VM phase, coordinator
 *                  phase). Defaults to 2 s.
 * @param pump   optional action run before each poll of the bounded joins
 *               (e.g. `{ testDispatcher.scheduler.advanceUntilIdle() }` for
 *               StandardTestDispatcher suites) so Main-bound children make
 *               progress while teardown waits. A throwing pump is ignored for
 *               that poll slice. The pump itself is NOT deadline-bounded — it
 *               must not block.
 *
 * On timeout (either join phase) or an internal drain error, one line is
 * printed to System.err and the drain returns normally; it never throws.
 */
internal object TestDrainSupport {

    fun drainBeforeResetMain(
        vms: List<DualViewModel>,
        coords: List<PolicyTxCoordinator> = emptyList(),
        timeoutMs: Long = 2_000,
        pump: (() -> Unit)? = null,
    ) {
        // 1) Best-effort cancel of every VM scope (idempotent).
        for (vm in vms) {
            try {
                vm.viewModelScope.cancel()
            } catch (_: Exception) {
                // Best effort: one VM's failure must not block the rest.
            }
        }
        // 2) Bounded join of VM scope jobs.
        val vmJobs = vms.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        joinBounded(vmJobs, timeoutMs, pump)?.let { reason ->
            System.err.println(
                "[TestDrainSupport] drain did not complete before resetMain() ($reason; ${vmJobs.count { !it.isCompleted }} job(s) still pending)"
            )
        }
        // 3) Coordinators: best-effort close (idempotent), then bounded join.
        for (coord in coords) {
            try {
                coord.close()
            } catch (_: Exception) {
                // Best effort: one coordinator's failure must not block the rest.
            }
        }
        val coordJobs = coords.mapNotNull { it.scope.coroutineContext[Job] }
        joinBounded(coordJobs, timeoutMs, pump)?.let { reason ->
            System.err.println(
                "[TestDrainSupport] drain did not complete before resetMain() ($reason; ${coordJobs.count { !it.isCompleted }} job(s) still pending)"
            )
        }
    }

    // Bounded fan-out join: polls the [jobs] list in short (20 ms) slices
    // until every job completes or the overall [timeoutMs] budget elapses;
    // each slice fans out via async and is awaited with awaitAll(). Optional
    // [pump] runs before each poll (e.g. StandardTestDispatcher suites pump
    // the test scheduler so Main-bound children make progress while teardown
    // waits). Per-item failures (including CancellationException from joining
    // a cancelled job) are captured by runCatching and never escape. Joining
    // an already-cancelled job returns fast. A throwing [pump] degrades to
    // "no pump this slice" and never aborts the drain.
    // Honesty limits: (a) the [pump] itself is NOT deadline-bounded —
    // a pump that blocks forever (Main child stuck under advanceUntilIdle)
    // hangs tearDown without bound; keep pumps to non-blocking scheduler
    // advances; (b) the wall-clock deadline bounds only jobs that can
    // complete on their own; an interrupt-ignoring IO child outliving the
    // budget is abandoned as an orphan (the drain reports, it never waits
    // past the deadline).
    // Returns null when every job completed within the window; otherwise a
    // short reason string ("deadline exceeded" or the exception name/message).
    private fun joinBounded(
        jobs: List<Job>,
        timeoutMs: Long,
        pump: (() -> Unit)? = null,
    ): String? {
        if (jobs.isEmpty()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        return try {
            runBlocking {
                var remaining = jobs
                while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
                    try {
                        pump?.invoke()
                    } catch (_: Exception) {
                        // Pump is optional per slice; never abort the drain.
                    }
                    withTimeoutOrNull(20) {
                        coroutineScope {
                            remaining
                                .map { job -> async { runCatching { job.join() } } }
                                .awaitAll()
                        }
                    }
                    remaining = remaining.filter { !it.isCompleted }
                    // Wall-clock pacing against a real-time deadline, not
                    // virtual time — delay() would drift with the test scheduler.
                    if (remaining.isNotEmpty()) Thread.sleep(5)
                }
                if (remaining.isEmpty()) null else "deadline exceeded"
            }
        } catch (e: Exception) {
            // Best effort: teardown drain must never fail the suite.
            "drain error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
