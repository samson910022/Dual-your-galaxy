// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// DEVICE-UNVERIFIED：無裝置，未經實機驗證。
// Phase F4a (issue #6): ROOT execution channel over `su -c`.
//
// Design notes:
// - The command string is assembled by core (DualRepository via
//   PmOutputParser builders + ShellEscaper); this gateway passes it verbatim
//   as the single `-c` argument in argv form, never through another shell
//   layer, so no re-quoting or hand-splicing happens here.
// - Validation still lives in core (C1-C3 untouched). A malformed string can
//   only come from a core bug; `sh -c` would then fail and the failure is
//   reported via CmdResult, never thrown.
// - Never throws for command failure: every outcome (missing su, timeout,
//   non-zero exit) is a CmdResult with exit != 0. CancellationException is
//   rethrown to stay coroutine-correct.
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.Privilege
import com.galaxydual.core.TRUNCATION_MARKER
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Default wall-clock budget for one `su -c` invocation (pm commands are
// normally sub-second; install-existing on a cold device can take longer).
internal const val SU_EXEC_TIMEOUT_MS = 60_000L

// Short budget for the ROOT probe (`id -u`, SuElevator.whoAmICmd): the probe
// only checks uid 0 and never installs anything, so 10 s is ample while the
// write path below keeps the full 60 s. Bounds selectChannel latest-wins
// handover when su itself hangs (password prompt with no tty): the loser
// probe expires in 10 s instead of pinning opMutex for 60 s even before
// cancellation is considered.
internal const val SU_PROBE_TIMEOUT_MS = 10_000L

// Poll slice for the cancellable wait below: proc.waitFor(poll) blocks at
// most this long, so coroutine cancellation surfaces within one slice
// (~50 ms) instead of the full timeout. Winner takeover is therefore
// bounded by the poll, not by SU_EXEC_TIMEOUT_MS.
internal const val SU_WAIT_POLL_MS = 50L
internal const val SU_DRAIN_POLL_MS = 50L

// Short reclaim window for post-cancel drain cleanup (P2-1, PR #40): after
// close() unblocks a pipe-held read, drains usually exit within milliseconds;
// the bound only caps a stuck child-held pipe. Kept << handover budget so
// cancel cleanup never wedges latest-wins handover (see header policy).
internal const val SU_CANCEL_DRAIN_MS = 300L

// Single monotonic source for all cancellable waits below (P2-2 G3): default
// System.nanoTime (CLOCK_MONOTONIC on JVM/ART, immune to wall rollback).
// Production injects its own clock only if deep-sleep-inclusive (BOOTTIME)
// semantics are desired; JVM tests inject a fake.
internal fun defaultMonoMs(): Long = System.nanoTime() / 1_000_000L

// Maps a su command line to its wait budget: probe (`id -u`) uses the short
// probe timeout, every other command (pm writes, list probes, identity
// fetches) keeps the full exec timeout. Pure so JVM tests pin it.
internal fun suTimeoutFor(cmd: String): Long =
    if (cmd.trim() == "id -u") SU_PROBE_TIMEOUT_MS else SU_EXEC_TIMEOUT_MS

// Cap per captured stream so a pathological `pm` output cannot OOM the app.
// Dual-profile listings are kilobytes; the cap only bites on abuse.
internal const val MAX_CAPTURE_CHARS = 256_000

// Drain thread budget (H1, P2 backpressure): close() cannot be relied on to
// unblock a native read() on a child-held pipe (reviewer experiment: the
// reader stays blocked; close + bounded join only bounds the waiter, never
// stops the reader). Every stuck capture therefore parks up to 2 daemon
// drain threads indefinitely, each holding a stuck stack (~1 MB virtual,
// ~tens of KB resident) plus its share of the dual 256 KB capture buffers
// above. Cap concurrent drains at 16 (= 8 stuck captures): large enough that
// legitimate bursts (select latest-wins overlap + timeout reclaim) never hit
// it, small enough that a pathological child-holds-pipe storm cannot exhaust
// threads/FDs. Exhaustion (I1): reserve fails → no spawn, fast definite exit 1
// resource-exhausted; spawned children always hold both drains, never wedges caller nor resends pm.
internal const val MAX_ACTIVE_DRAINS = 16
internal val activeDrainCount = AtomicInteger(0)

// CAS acquire: true when a drain slot was reserved, false when the budget
// is exhausted (caller degrades list/verify fail-closed, never blocks).
internal fun tryAcquireDrainSlot(): Boolean {
    while (true) {
        val cur = activeDrainCount.get()
        if (cur >= MAX_ACTIVE_DRAINS) return false
        if (activeDrainCount.compareAndSet(cur, cur + 1)) return true
    }
}

// Release one slot; clamps at zero so production never crashes on an
// unexpected count. The clamp would silently absorb a double-release -- if
// that ever happened it would be a bug: the only production release is the
// spawnDrain finally below, so no double-release path exists (tests release
// manually by count, never via a drain thread).
internal fun releaseDrainSlot() {
    activeDrainCount.updateAndGet { cur -> if (cur > 0) cur - 1 else 0 }
}

// Atomic pair reservation (I1, 5th-round P2 backpressure): reserve→spawn→drain
// order. A capture needs 2 drain threads (stdout + stderr); reserving both in
// ONE CAS before spawn guarantees a spawned child always has its drains.
// Without this, a slots-full spawn with no drains leaves a large-output child
// blocked in pipe write() while the client waits out the full 60 s timeout.
// FD bound: concurrent captures are bounded by MAX_ACTIVE_DRAINS/2 (= 8).
// Exhaustion degrades fast (definite resource-exhausted, no timeout wait) and
// never resends: the process never started, so the reply is pre-dispatch
// definite (same bucket as "spawn failed: ...").
internal fun tryReserveDrainPair(): Boolean {
    while (true) {
        val cur = activeDrainCount.get()
        if (cur > MAX_ACTIVE_DRAINS - 2) return false
        if (activeDrainCount.compareAndSet(cur, cur + 2)) return true
    }
}

// Release a pair reservation (exec failed before any drain started, or
// defensive release of the unconsumed remainder when stream/drain setup
// throws post-spawn). Clamps at zero like releaseDrainSlot above.
internal fun releaseDrainPair() {
    activeDrainCount.updateAndGet { cur -> if (cur >= 2) cur - 2 else 0 }
}

// Single construction point for drain threads: budget first, thread second.
// Returns null when exhausted -- status keeps its READ_ERROR default so the
// list/verify caller fails closed (truncated=true, forcing a verify re-probe)
// without waiting. On success starts a
// daemon thread that always releases its slot in finally, even when
// drainCapped throws an Error.
internal fun spawnDrain(
    name: String,
    status: AtomicReference<DrainStatus>,
    stream: java.io.InputStream,
    sink: StringBuilder,
): Thread? {
    if (!tryAcquireDrainSlot()) return null
    try {
        return thread(isDaemon = true, name = name) {
            try {
                status.set(drainCapped(stream, sink))
            } finally {
                releaseDrainSlot()
            }
        }
    } catch (t: Throwable) {
        // thread() construction itself can throw under storm load (OOM /
        // cannot create native thread): the slot was already acquired above,
        // so release it before propagating -- otherwise the budget would leak
        // exactly when pressure is highest.
        releaseDrainSlot()
        throw t
    }
}

// Reserved-slot drain start (I1): consumes ONE previously reserved pair slot.
// No acquire here -- the caller already holds 2 via tryReserveDrainPair, so
// this never returns null (exhaustion was decided before spawn). The thread
// still releases its slot in finally. Construction throw (theoretically
// unreachable, reservation held) propagates WITHOUT an internal release --
// the caller defensively releases the unconsumed remainder (2 - started).
internal fun spawnDrainReserved(
    name: String,
    status: AtomicReference<DrainStatus>,
    stream: java.io.InputStream,
    sink: StringBuilder,
): Thread {
    return thread(isDaemon = true, name = name) {
        try {
            status.set(drainCapped(stream, sink))
        } finally {
            releaseDrainSlot()
        }
    }
}

// Builds the argv for `su -c <cmd>` with cmd verbatim as ONE argument.
// Pure function (no I/O): covered by JVM unit tests (GatewayAssemblyTest).
// Uses argv form (not "su -c $cmd" through /bin/sh) so metacharacters in an
// already-validated core-built command cannot be reinterpreted by an extra
// shell layer.
fun suArgv(cmd: String): Array<String> = arrayOf("su", "-c", cmd)

// Runs argv, captures stdout/stderr fully, and maps every outcome to
// CmdResult. Shared by SuCmdGateway (client side) and GalaxyExecService (the
// Shizuku UserService side: same APK, same helper, privileged uid there).
// Blocking: callers must dispatch to Dispatchers.IO (gateway) or already be
// off the main thread (binder thread on the service side).
// Binder-thread note (P2-1): GalaxyExecService.onTransact keeps calling THIS
// blocking form -- the binder thread has no coroutine scope, so it cannot
// run the cancellable suspend variant. The SuCmdGateway client path uses
// captureProcessCancellable below (same contract, cancellable waits).
// execFn seam (mirrors captureProcessCancellable): production default is
// Runtime.exec; JVM tests inject a fake Process to model an Android
// child-held pipe (wrapper destroyed, child keeps the FDs, drain stays
// blocked) -- a real JVM process cannot model that because OpenJDK
// destroyForcibly closes the streams and wakes the drains at once, so the
// join-expiry path is otherwise unreachable on the JVM.
internal fun captureProcess(
    argv: Array<String>,
    timeoutMs: Long = SU_EXEC_TIMEOUT_MS,
    execFn: (Array<String>) -> Process = { a -> Runtime.getRuntime().exec(a) },
): CmdResult {
    // I1 reserve→spawn→drain: atomically hold both drain slots BEFORE spawn so
    // a spawned child can never wait for a drain that will never start (large
    // output would otherwise block in pipe write() to the 60 s timeout). FD
    // bound: concurrent captures ≤ MAX_ACTIVE_DRAINS/2. Fast degrade: return
    // at once without waiting when no pair is available.
    if (!tryReserveDrainPair()) {
        return CmdResult(exit = 1, out = "", err = "resource exhausted: no drain slots (active=${activeDrainCount.get()}/$MAX_ACTIVE_DRAINS)", truncated = true)
    }
    val proc = try {
        execFn(argv)
    } catch (e: Exception) {
        // Covers missing su ("No such file or directory"), SecurityException,
        // and any other spawn failure: explicit error, exit != 0, no throw.
        // Reservation was never consumed (process never started) -- release it.
        releaseDrainPair()
        return CmdResult(exit = 1, out = "", err = "spawn failed: ${(e.message ?: e.javaClass.simpleName)}")
    } catch (t: Throwable) {
        releaseDrainPair()
        throw t
    }
    // Drain both pipes on daemon threads: reading stdout to EOF while stderr
    // sits unfilled can deadlock once the stderr pipe buffer fills.
    // Concurrency (#54 Phase1): out/err are StringBuilder guarded by
    // synchronized(sink) -- drainCapped appends under the same lock and
    // capped()/snapshot copy under it too, so the timeout path's
    // join(2_000) expiry read is an atomic snapshot, never a torn
    // StringBuilder (writer append vs reader toString race). The blocking
    // read() itself stays outside the lock, so a snapshot never waits on I/O.
    val out = StringBuilder()
    val err = StringBuilder()
    // Per-stream drain outcomes (Phase B, issue #22 P1-#2): AtomicReferences
    // because the join()s below may expire while a drain is still writing.
    // Defaults are READ_ERROR (fail closed: a drain thread dying from an
    // Error without setting a status must not look like clean EOF).
    val outStatus = AtomicReference(DrainStatus.READ_ERROR)
    val errStatus = AtomicReference(DrainStatus.READ_ERROR)
    // H1: budget-gated drains (I1: pair already reserved above, so consume the
    // reservation here -- no re-acquire, never null; the threads still release
    // in finally). Blocking form deliberately carries no best-effort
    // interrupt (binder-thread caller: pipe read() wake-up on interrupt is not
    // guaranteed and the thread has no coroutine scope, so interrupt is
    // intentionally left to the cancellable variant below; the drain budget
    // cap is the guarantee on both paths).
    // Defensive: spawnDrainReserved construction throw is theoretically
    // unreachable (slots held), but the unconsumed remainder is released and
    // the already-spawned process maps to post-spawn unknown below -- never
    // resource-exhausted (already sent cannot be pre-dispatch).
    val drainOut: Thread
    val drainErr: Thread
    var startedReserved = 0
    try {
        val inReserved = proc.inputStream
        val errReserved = proc.errorStream
        drainOut = spawnDrainReserved("capture-stdout", outStatus, inReserved, out).also { startedReserved++ }
        drainErr = spawnDrainReserved("capture-stderr", errStatus, errReserved, err).also { startedReserved++ }
    } catch (t: Throwable) {
        repeat(2 - startedReserved) { releaseDrainSlot() }
        if (t is Exception) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            try {
                proc.inputStream.close()
            } catch (_: Exception) {
            }
            try {
                proc.errorStream.close()
            } catch (_: Exception) {
            }
            return CmdResult(exit = 1, out = capped(out, true), err = "su failed post-spawn: ${(t.message ?: t.javaClass.simpleName)}", truncated = true, transportUnknown = true)
        }
        try {
            proc.destroyForcibly()
        } catch (_: Throwable) {
        }
        throw t
    }
    // Phase 3 post-spawn Error-escape guard: an Error from waitFor/join/exitValue
    // must not escape without destroyForcibly/pipe-close. The catches below map
    // only CE/IE paths to CmdResult; this outer catch rethrows after best-effort
    // cleanup (mirrors the spawn-setup inner path) and never converts to a result.
    // Behavior for CE/IE/Exception + timeout/interrupt returns stays byte-identical.
    try {
        val exited = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            proc.destroyForcibly()
            // Post-spawn unknown (Phase A1): the process was spawned, so the
            // remote pm may have executed before the kill -- unknown-after-send
            // by construction. transportUnknown=true forces the repository onto
            // the read-only verify probe instead of claiming definite failure
            // (exit/out untrusted either way). Pre-spawn spawn failures
            // ("spawn failed: ...", exec threw, process never started) stay
            // definite -- only this post-spawn interrupt carries the flag.
            // The "interrupted: " text prefix is the wire-frozen backstop: the
            // service-side capture shares this helper and its flag is lost on
            // the (exit, out, err) parcel, so DualRepository also matches the
            // anchored text via isPostSpawnTransportText.
            return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: ${(e.message ?: "wait interrupted")}", truncated = true, transportUnknown = true)
        }
        if (!exited) {
            proc.destroyForcibly()
            try {
                drainOut.join(2_000)
                drainErr.join(2_000)
            } catch (e: InterruptedException) {
                // Post-spawn unknown (Phase A1): the timeout already abandoned
                // the wait (timedOut=true below covers it), but the drain-join
                // interrupt itself proves the capture is incomplete after spawn.
                // Restore the flag and stay unknown-after-send either way.
                Thread.currentThread().interrupt()
                return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: drain join interrupted after timeout: ${(e.message ?: "interrupted")}", truncated = true, timedOut = true, transportUnknown = true)
            }
            // Same honest-flag reasoning as the interrupt path above: killed
            // mid-stream means incomplete output; exit=1 keeps behavior unchanged.
            // Unknown-after-send (PR-32 P1-1): the client abandoned the wait, the
            // remote pm may still have executed, so timedOut=true forces the
            // repository onto the read-only verify probe instead of claiming
            // definite failure (exit/out untrusted either way).
            return CmdResult(exit = 1, out = capped(out, true), err = "timeout after ${timeoutMs}ms: ${argv.firstOrNull() ?: "?"}", truncated = true, timedOut = true)
        }
        try {
            drainOut.join(5_000)
            drainErr.join(5_000)
        } catch (e: InterruptedException) {
            // Post-spawn unknown (Phase A1): spawn succeeded and the steady
            // drain was interrupted -- the remote pm may have executed while
            // this client holds only a partial capture. Destroy, restore the
            // flag, and force read-only reconciliation via transportUnknown
            // (same rule as the wait-interrupt above). The "interrupted: "
            // prefix is the wire-frozen backstop for the service side.
            Thread.currentThread().interrupt()
            proc.destroyForcibly()
            return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: drain join interrupted: ${(e.message ?: "interrupted")}", truncated = true, transportUnknown = true)
        }
        // Join-expiry honesty: join() returns Unit (no timed-out flag), so isAlive
        // is the expiry signal. A still-alive drain means the capture may be
        // incomplete even when nothing hit the cap -- fail closed via truncated.
        // I1: drains always started here (pair reserved pre-spawn), so no null
        // case remains; READ_ERROR defaults still fail closed via buildResult.
        val joinExpired = drainOut.isAlive || drainErr.isAlive
        return buildResult(proc.exitValue(), out, err, outStatus.get(), errStatus.get(), joinExpired)
    } catch (t: Throwable) {
        // Error escape (incl. Error + any unhandled Exception): best-effort
        // cleanup mirroring the spawn-setup inner path, then rethrow. Never
        // converts to CmdResult: CE propagates, and IE/Exception paths above
        // already returned (timeout/interrupt values unchanged).
        try {
            proc.destroyForcibly()
        } catch (_: Throwable) {
        }
        try {
            proc.inputStream.close()
        } catch (_: Throwable) {
        }
        try {
            proc.errorStream.close()
        } catch (_: Throwable) {
        }
        throw t
    }
}

// Cancellable wait for process exit: polls waitFor(SU_WAIT_POLL_MS) and
// checks coroutine cancellation between slices. Returns true when the
// process exited before the deadline, false on timeout (caller maps false
// to the existing timedOut path). Throws CancellationException at once on
// coroutine cancel (selectChannel latest-wins) -- never converts cancel to
// a result -- and InterruptedException on thread interrupt (caller maps to
// the Phase A transportUnknown path, same as the blocking version).
// Service side (GalaxyExecService binder thread, no coroutine scope) keeps
// calling the blocking captureProcess above; only the SuCmdGateway client
// path uses this helper.
// Monotonic budget (P2-2, PR #40 Re-review): elapsed time on the injectable
// monoMs clock (default nanoTime, monotonic on JVM and Android ART),
// never a System.currentTimeMillis wall deadline -- a user/NTP backward wall
// correction must not extend the 10 s / 60 s elapsed bound. Production may
// inject SystemClock.elapsedRealtime only if deep-sleep-inclusive (BOOTTIME)
// semantics are desired (nanoTime is CLOCK_MONOTONIC and excludes deep
// sleep, but both are immune to wall rollback); JVM tests inject a fake.
internal suspend fun cancellableAwaitExit(
    proc: Process,
    timeoutMs: Long,
    monoMs: () -> Long = ::defaultMonoMs,
): Boolean {
    val startMs = monoMs()
    while (true) {
        // Queue-collapse checkpoint: a cancelled select job surfaces here
        // within one poll slice, releasing opMutex for the winner. This is
        // NOT remote preemption (see captureProcessCancellable header).
        currentCoroutineContext().ensureActive()
        val elapsedMs = monoMs() - startMs
        if (elapsedMs >= timeoutMs) {
            // Final non-blocking check before declaring timeout.
            return proc.waitFor(0, TimeUnit.MILLISECONDS)
        }
        val poll = minOf(SU_WAIT_POLL_MS, timeoutMs - elapsedMs)
        if (proc.waitFor(poll, TimeUnit.MILLISECONDS)) return true
    }
}

// Cancellable join for a drain thread: polls join(SU_DRAIN_POLL_MS) with the
// same cancellation checkpoint. Returns when the thread dies OR when the
// budget expires (caller re-checks isAlive for joinExpired, same as the
// blocking path). Throws CancellationException on coroutine cancel (caller
// destroys and rethrows) and InterruptedException on thread interrupt
// (caller maps to transportUnknown). Budget is monoMs elapsed (P2-2, same
// wall-rollback immunity as cancellableAwaitExit above).
// H1: nullable -- null means the drain never started (budget exhausted);
// returns at once without waiting (the side already carries READ_ERROR).
internal suspend fun cancellableJoin(
    t: Thread?,
    budgetMs: Long,
    monoMs: () -> Long = ::defaultMonoMs,
) {
    if (t == null) return
    val startMs = monoMs()
    while (t.isAlive) {
        currentCoroutineContext().ensureActive()
        val elapsedMs = monoMs() - startMs
        if (elapsedMs >= budgetMs) return
        try {
            t.join(minOf(SU_DRAIN_POLL_MS, budgetMs - elapsedMs))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }
}

// Suspend (cancellable) variant of captureProcess for the SuCmdGateway
// client path. Same spawn/drain/buildResult contract as the blocking
// version above; only the waits are cancellable.
//
// Cancel semantics (P2-1, queue-collapse vs preemption):
// - Queue-collapse: coroutine cancellation (selectChannel latest-wins
//   cancelling the previous select while its su probe/write is in flight)
//   ends the CLIENT WAIT at once (CancellationException within one
//   SU_WAIT_POLL_MS slice, destroyForcibly, rethrow). opMutex is released
//   immediately so the winner takes over in ~50 ms, not SU_EXEC_TIMEOUT_MS.
//   The loser never converges (no refresh after cancel) because the
//   exception aborts probeChannels before refreshLocked.
// - NOT preemption: destroyForcibly only kills the local `su` child best
//   effort; the remote pm may already have executed before the kill, so a
//   cancelled post-spawn capture is unknown-after-send by construction.
//   Coroutine-correctness requires CE to propagate (never synthesize a
//   definite result on the cancel path); any result-form of this state
//   (thread-interrupt paths below, service-side wire texts) carries
//   transportUnknown=true (Phase A), never a definite failure. The Shizuku
//   transact path (BoundedAwait) documents the same split: caller wait is
//   bounded + cancellable, remote work is never force-stopped.
//
// Post-spawn cleanup policy (P2-1 fix, PR #40 Re-review):
// - destroyForcibly only kills the local `su` wrapper best effort; a child
//   spawned via `su -c` (e.g. pm) may inherit the stdout/stderr pipe FDs and
//   outlive the wrapper, so a drain blocked in read() would wait for EOF
//   forever (2 leaked daemon threads per cancel without cleanup).
// - Every post-spawn exit therefore closes the local pipe ends first (促使
//   read 返回/EOF or IOException -> drain thread exits), then does a BOUNDED
//   + CANCELLABLE drain wait (cancellableJoin: SU_CANCEL_DRAIN_MS on cancel
//   paths, existing 2 s / 5 s windows on timeout / steady paths). The
//   cancel-path wait aborts at once when the job is already cancelled (close
//   still lets drains die async), so handover stays fast.
// - Cleanup only promotes reclaim; it does NOT guarantee killing an
//   already-sent pm child, and unknown-after-send semantics are unchanged
//   (cancel rethrows CE, result paths carry transportUnknown / timedOut).
internal suspend fun captureProcessCancellable(
    argv: Array<String>,
    timeoutMs: Long = SU_EXEC_TIMEOUT_MS,
    // Monotonic clock seam (P2-2): threaded into every cancellableAwaitExit /
    // cancellableJoin below so all bounds are mono elapsed. Same default as
    // the helpers; SuCmdGateway injects its own (production: nanoTime by
    // default, or SystemClock.elapsedRealtime if deep-sleep-inclusive
    // semantics are desired). Kept BEFORE execFn so
    // the trailing-lambda call style `SuCmdGateway { argv -> ... }` still
    // binds execFn.
    monoMs: () -> Long = ::defaultMonoMs,
    execFn: (Array<String>) -> Process = { a -> Runtime.getRuntime().exec(a) },
): CmdResult {
    currentCoroutineContext().ensureActive()
    // I1 reserve→spawn→drain: atomically hold both drain slots BEFORE spawn so
    // a spawned child can never wait for a drain that will never start (large
    // output would otherwise block in pipe write() to the full timeout). FD
    // bound: concurrent captures ≤ MAX_ACTIVE_DRAINS/2. Fast degrade: return
    // at once without spawning when no pair is available. Definite: process
    // never started (same bucket as "spawn failed: ..."); the prefix avoids
    // isServiceTimeoutText/isPostSpawnTransportText so list exit!=0 stays
    // fail-closed and writes never resend (never-sent).
    if (!tryReserveDrainPair()) {
        return CmdResult(exit = 1, out = "", err = "resource exhausted: no drain slots (active=${activeDrainCount.get()}/$MAX_ACTIVE_DRAINS)", truncated = true)
    }
    val proc = try {
        execFn(argv)
    } catch (e: CancellationException) {
        releaseDrainPair()
        throw e
    } catch (e: Exception) {
        releaseDrainPair()
        return CmdResult(exit = 1, out = "", err = "spawn failed: ${(e.message ?: e.javaClass.simpleName)}")
    } catch (t: Throwable) {
        releaseDrainPair()
        throw t
    }
    // Post-spawn section (Phase G1): proc is live from here on. Any
    // non-CE/non-IE exception escaping the waits below (e.g. a rogue
    // Process.waitFor/exitValue throwing IllegalStateException) must NOT
    // reach SuCmdGateway.run's generic catch as definite -- the remote pm
    // may have executed after spawn, so fail closed to transportUnknown.
    // CE rethrows (queue-collapse handover); IE keeps the existing
    // "interrupted: " path. The inner awaits already map their own CE/IE;
    // the outer catches below cover every other post-spawn throw site.
    val out = StringBuilder()
    val err = StringBuilder()
    val outStatus = AtomicReference(DrainStatus.READ_ERROR)
    val errStatus = AtomicReference(DrainStatus.READ_ERROR)
    // Outer-visible handles for post-spawn cleanup (P2-1): inner paths use
    // the local vals below; outer guards use these (assigned as soon as each
    // resource materializes) so CE/IE/generic after spawn can still close.
    var openIn: java.io.InputStream? = null
    var openErr: java.io.InputStream? = null
    var openDrainOut: Thread? = null
    var openDrainErr: Thread? = null
    try {
        // Materialize streams on the caller thread: proc.inputStream /
        // errorStream getters may throw, and an access inside thread{...}
        // would run on the child thread (uncaught there, invisible here).
        // Materializing here routes a getter throw to the outer post-spawn
        // generic below (transportUnknown), which is the correct
        // unknown-after-send bucket.
        // I1: consume the pair reservation (no re-acquire, never null; the
        // threads still release in finally). Construction throw is
        // theoretically unreachable (slots held): defensively release the
        // unconsumed remainder and rethrow so the outer post-spawn guards map
        // it to transportUnknown -- never resource-exhausted (already sent
        // cannot be pre-dispatch).
        var startedReserved = 0
        val inS: java.io.InputStream
        val errS: java.io.InputStream
        val drainOut: Thread
        val drainErr: Thread
        try {
            inS = proc.inputStream.also { openIn = it }
            errS = proc.errorStream.also { openErr = it }
            drainOut = spawnDrainReserved("capture-stdout", outStatus, inS, out).also { openDrainOut = it; startedReserved++ }
            drainErr = spawnDrainReserved("capture-stderr", errStatus, errS, err).also { openDrainErr = it; startedReserved++ }
        } catch (t: Throwable) {
            repeat(2 - startedReserved) { releaseDrainSlot() }
            if (t is Exception) throw t
            try {
                proc.destroyForcibly()
            } catch (_: Throwable) {
            }
            throw t
        }
        val exited = try {
            cancellableAwaitExit(proc, timeoutMs, monoMs)
        } catch (e: CancellationException) {
            // Post-spawn unknown (Phase A): spawned, may have executed, reply
            // lost to cancel. Destroy best effort and propagate CE so the
            // winner takes over at once; never claim definite. The destroy
            // itself is best-effort: a rogue destroyForcibly throw must not
            // mask the CE (outer generic would convert it to a result).
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            // P2-1 reclaim: close local pipe ends so a surviving child holding
            // the same FDs cannot pin drains in read() forever (2 leaked
            // daemon threads per cancel). Bounded + cancellable wait aborts
            // at once here (already cancelled) while drains die async via
            // close; handover stays fast. Swallow the reclaim CE to preserve
            // the original cancel below.
            try {
                inS.close()
            } catch (_: Exception) {
            }
            try {
                errS.close()
            } catch (_: Exception) {
            }
            // H1 best-effort interrupt after close, before the bounded join:
            // a plain pipe read() on Linux is NOT guaranteed to wake on
            // interrupt, so this is never relied on -- the drain budget cap
            // is the guarantee. close() stays synchronous (~ms).
            try {
                drainOut.interrupt()
            } catch (_: Exception) {
            }
            try {
                drainErr.interrupt()
            } catch (_: Exception) {
            }
            try {
                cancellableJoin(drainOut, SU_CANCEL_DRAIN_MS, monoMs)
            } catch (_: CancellationException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                cancellableJoin(drainErr, SU_CANCEL_DRAIN_MS, monoMs)
            } catch (_: CancellationException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            // P2-1: same pipe close so drains cannot linger on a child-held
            // FD; bounded reclaim before returning the interrupt result
            // (cancellable, so a concurrent cancel still hands over at once).
            try {
                inS.close()
            } catch (_: Exception) {
            }
            try {
                errS.close()
            } catch (_: Exception) {
            }
            // H1 best-effort interrupt (same non-guarantee as above; cap is
            // the guarantee).
            try {
                drainOut.interrupt()
            } catch (_: Exception) {
            }
            try {
                drainErr.interrupt()
            } catch (_: Exception) {
            }
            try {
                cancellableJoin(drainOut, SU_CANCEL_DRAIN_MS, monoMs)
                cancellableJoin(drainErr, SU_CANCEL_DRAIN_MS, monoMs)
            } catch (ce: CancellationException) {
                try {
                    proc.destroyForcibly()
                } catch (_: Exception) {
                }
                throw ce
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: ${(e.message ?: "wait interrupted")}", truncated = true, transportUnknown = true)
        }
        if (!exited) {
            // Best-effort kill: a rogue destroyForcibly throw must not mask
            // the timeout result (outer generic would convert it).
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            // P2-1: close pipes BEFORE joining so a child-held FD cannot pin
            // drains to the full 2 s windows; drains observing close exit
            // early and the joins return at once.
            try {
                inS.close()
            } catch (_: Exception) {
            }
            try {
                errS.close()
            } catch (_: Exception) {
            }
            // H1 best-effort interrupt (same non-guarantee; cap is guarantee).
            try {
                drainOut.interrupt()
            } catch (_: Exception) {
            }
            try {
                drainErr.interrupt()
            } catch (_: Exception) {
            }
            try {
                cancellableJoin(drainOut, 2_000, monoMs)
                cancellableJoin(drainErr, 2_000, monoMs)
            } catch (e: CancellationException) {
                // Timeout already abandoned the wait (timedOut below), but the
                // cancel must still release the waiter at once for handover.
                // Pipes already closed above; propagate without extra wait.
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: drain join interrupted after timeout: ${(e.message ?: "interrupted")}", truncated = true, timedOut = true, transportUnknown = true)
            }
            return CmdResult(exit = 1, out = capped(out, true), err = "timeout after ${timeoutMs}ms: ${argv.firstOrNull() ?: "?"}", truncated = true, timedOut = true)
        }
        try {
            cancellableJoin(drainOut, 5_000, monoMs)
            cancellableJoin(drainErr, 5_000, monoMs)
        } catch (e: CancellationException) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            // P2-1: close so a child-held FD cannot pin the drains; already
            // cancelled so the reclaim below (if any) must not delay
            // handover -- propagate at once, drains die async via close.
            try {
                inS.close()
            } catch (_: Exception) {
            }
            try {
                errS.close()
            } catch (_: Exception) {
            }
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            // P2-1: close before returning; the interrupted join already
            // bounded the wait, drains die async via close.
            try {
                inS.close()
            } catch (_: Exception) {
            }
            try {
                errS.close()
            } catch (_: Exception) {
            }
            return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: drain join interrupted: ${(e.message ?: "interrupted")}", truncated = true, transportUnknown = true)
        }
        val joinExpired = drainOut.isAlive || drainErr.isAlive
        if (joinExpired) {
            // P2-1: steady joins expired but a surviving child may still hold
            // the pipe FDs; close so lingering drains die async via
            // close instead of waiting for an EOF that never comes. No extra
            // wait here (already spent 5 s per drain); unknown-ness is
            // carried by buildResult's truncated flag, exit stays as read.
            try {
                inS.close()
            } catch (_: Exception) {
            }
            try {
                errS.close()
            } catch (_: Exception) {
            }
        }
        return buildResult(proc.exitValue(), out, err, outStatus.get(), errStatus.get(), joinExpired)
    } catch (e: CancellationException) {
        // Outer post-spawn guard: CE from any point after spawn (including a
        // rethrown inner CE) still destroys best effort and propagates --
        // never synthesized into a definite result (queue-collapse).
        try {
            proc.destroyForcibly()
        } catch (_: Exception) {
        }
        // P2-1 reclaim: close cached pipes (fallback to proc getters if setup
        // threw before caching); bounded wait aborts at once here (already
        // cancelled) while drains die async via close. Swallow reclaim CE to
        // preserve the original cancel.
        try {
            openIn?.close()
        } catch (_: Exception) {
        }
        try {
            openErr?.close()
        } catch (_: Exception) {
        }
        try {
            proc.inputStream.close()
        } catch (_: Exception) {
        }
        try {
            proc.errorStream.close()
        } catch (_: Exception) {
        }
        // H1 best-effort interrupt before the bounded reclaim join (same
        // non-guarantee; cap is the guarantee; null-safe).
        try {
            openDrainOut?.interrupt()
        } catch (_: Exception) {
        }
        try {
            openDrainErr?.interrupt()
        } catch (_: Exception) {
        }
        try {
            openDrainOut?.let { cancellableJoin(it, SU_CANCEL_DRAIN_MS, monoMs) }
        } catch (_: CancellationException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            openDrainErr?.let { cancellableJoin(it, SU_CANCEL_DRAIN_MS, monoMs) }
        } catch (_: CancellationException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        try {
            proc.destroyForcibly()
        } catch (_: Exception) {
        }
        // P2-1: close cached pipes first (proc getters as fallback); the
        // thread is interrupted so a join here would abort at once -- close
        // alone lets drains die async without delaying the result.
        try {
            openIn?.close()
        } catch (_: Exception) {
        }
        try {
            openErr?.close()
        } catch (_: Exception) {
        }
        try {
            proc.inputStream.close()
        } catch (_: Exception) {
        }
        try {
            proc.errorStream.close()
        } catch (_: Exception) {
        }
        return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: ${(e.message ?: "post-spawn interrupted")}", truncated = true, transportUnknown = true)
    } catch (e: Exception) {
        // Outer post-spawn guard (Phase G1 P1): any other exception after
        // spawn (waitFor/exitValue/drain-setup throwing) is
        // unknown-after-send -- the remote pm may have executed. Destroy
        // best effort, close pipes (P2-1: cached handles + proc fallback so
        // a child-held FD cannot pin drains), then bounded + cancellable
        // reclaim before returning transportUnknown (cancel during reclaim
        // still propagates). CE is excluded above so cancellation is never
        // swallowed here.
        try {
            proc.destroyForcibly()
        } catch (_: Exception) {
        }
        try {
            openIn?.close()
        } catch (_: Exception) {
        }
        try {
            openErr?.close()
        } catch (_: Exception) {
        }
        try {
            proc.inputStream.close()
        } catch (_: Exception) {
        }
        try {
            proc.errorStream.close()
        } catch (_: Exception) {
        }
        // Bounded reclaim: close above usually lets drains exit at once, so
        // this caps a stuck child-held pipe without blocking the result.
        // Cancellable: a concurrent cancel aborts the wait and propagates.
        // H1 best-effort interrupt (same non-guarantee; cap is guarantee).
        try {
            openDrainOut?.interrupt()
        } catch (_: Exception) {
        }
        try {
            openDrainErr?.interrupt()
        } catch (_: Exception) {
        }
        try {
            openDrainOut?.let { cancellableJoin(it, SU_CANCEL_DRAIN_MS, monoMs) }
            openDrainErr?.let { cancellableJoin(it, SU_CANCEL_DRAIN_MS, monoMs) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return CmdResult(exit = 1, out = capped(out, true), err = "su failed post-spawn: ${(e.message ?: e.javaClass.simpleName)}", truncated = true, transportUnknown = true)
    } catch (t: Throwable) {
        // Phase 3 Error escape (Error is not an Exception, so the guards above
        // missed it: waitFor/exitValue Error would otherwise leak proc + pipes
        // without destroyForcibly/close). Mirror the spawn-setup inner path:
        // best-effort destroy + stream closes (each try/catch best-effort so a
        // cleanup throw cannot mask the original), then rethrow. Never
        // converts to CmdResult, so CE is never swallowed here (CE is an
        // Exception caught above and rethrown there); this catch only sees
        // Error + any non-Exception Throwable. Do not add reclaim joins:
        // close alone lets drains die async without delaying the rethrow.
        try {
            proc.destroyForcibly()
        } catch (_: Throwable) {
        }
        try {
            openIn?.close()
        } catch (_: Throwable) {
        }
        try {
            openErr?.close()
        } catch (_: Throwable) {
        }
        try {
            proc.inputStream.close()
        } catch (_: Throwable) {
        }
        try {
            proc.errorStream.close()
        } catch (_: Throwable) {
        }
        throw t
    }
}

// Per-stream drain outcome (review Phase B, issues #22 P1-#2/P2-#3): the old
// drainCapped() swallowed read exceptions, so a mid-stream failure with a
// short capture and exit 0 surfaced as truncated=false. Every drain now
// reports EOF (clean end), CAPPED (cap hit, bytes dropped), or READ_ERROR
// (exception mid-read, partial content preserved in sink). Fail closed:
// anything but EOF on either stream forces truncated=true in buildResult().
internal enum class DrainStatus { EOF, CAPPED, READ_ERROR }

// Single construction point for the steady-state path: truncated is true when
// either stream did not reach clean EOF (cap or read error) or a drain was
// still alive after its join window. The marker is appended PER STREAM
// (outTrunc/errTrunc below, join expiry marks both since it may cut either):
// a cap/read-error on one stream no longer pollutes the clean stream, so the
// hint parser can still use the intact side. The Shizuku Parcel client --
// which re-derives the flag by sniffing text -- still sees truncated whenever
// any marked stream crosses (P2-#3).
// Compatibility note: service and client ship in the same APK and update
// together, so both sides agree on whenever-truncated-append. The residual
// NEW-client + OLD-service (v1 cap-only marker) gap is closed at the
// deployment layer by ShizukuExecProtocol.SERVICE_VERSION (v6), which forces
// the daemon to rebuild the resident UserService on upgrade -- v6 also
// invalidates v5 resident services (concurrency gate + drain sync).
internal fun buildResult(
    exit: Int,
    out: StringBuilder,
    err: StringBuilder,
    outStatus: DrainStatus,
    errStatus: DrainStatus,
    joinExpired: Boolean,
): CmdResult {
    val truncated = outStatus != DrainStatus.EOF || errStatus != DrainStatus.EOF || joinExpired
    // Per-stream marking: only the affected stream gets the marker (join
    // expiry marks both -- it may have cut either side). Global truncated
    // stays the OR so no truncation is ever lost.
    val outTrunc = outStatus != DrainStatus.EOF || joinExpired
    val errTrunc = errStatus != DrainStatus.EOF || joinExpired
    return CmdResult(exit = exit, out = capped(out, outTrunc), err = capped(err, errTrunc), truncated = truncated)
}

// Snapshot copy of a drain sink: the toString() copy runs under the same
// synchronized(sink) lock drainCapped appends with, so a join-expiry /
// timeout-path read racing a still-appending drain returns an atomic
// snapshot, never a torn StringBuilder (count vs backing-array mismatch).
internal fun snapshot(sink: StringBuilder): String = synchronized(sink) { sink.toString() }

// Reads all of input into sink up to MAX_CAPTURE_CHARS; closes the stream.
// Returns the drain outcome; already-appended characters are preserved on
// every path, including READ_ERROR. Internal (not private) so JVM unit tests
// cover it with an injectable failing-stream fixture (GatewayAssemblyTest).
// Synchronized writes (#54 Phase1): the length check + room computation +
// append are one synchronized(sink) critical section, so concurrent drains
// hammering one sink never overflow the cap and a snapshot racing an append
// is never torn. The blocking reader.read() stays outside the lock.
internal fun drainCapped(input: java.io.InputStream, sink: StringBuilder): DrainStatus {
    return try {
        input.bufferedReader().use { reader ->
            val buf = CharArray(8_192)
            var outcome: DrainStatus? = null
            while (outcome == null) {
                if (synchronized(sink) { sink.length } >= MAX_CAPTURE_CHARS) {
                    outcome = DrainStatus.CAPPED
                } else {
                    val n = reader.read(buf, 0, buf.size)
                    if (n < 0) {
                        outcome = DrainStatus.EOF
                    } else {
                        synchronized(sink) {
                            val room = MAX_CAPTURE_CHARS - sink.length
                            if (n > room) {
                                // The cap cuts inside this chunk: bytes were
                                // dropped, so report CAPPED even if the stream
                                // ends right after. A stream ending EXACTLY on
                                // the cap boundary also reports CAPPED (next loop
                                // pass sees sink.length >= MAX): a conservative
                                // false-positive, fail-closed and consistent with
                                // the old isCapped semantics -- never a missed
                                // truncation.
                                sink.append(buf, 0, room)
                                outcome = DrainStatus.CAPPED
                            } else {
                                sink.append(buf, 0, n)
                            }
                        }
                    }
                }
            }
            // The loop only exits with outcome != null.
            outcome ?: DrainStatus.READ_ERROR
        }
    } catch (_: Exception) {
        // Best effort: a broken pipe mid-read still yields whatever was
        // captured; READ_ERROR forces truncated=true at the call site, and
        // the exit code below carries the failure signal.
        DrainStatus.READ_ERROR
    }
}

// Appends the core TRUNCATION_MARKER whenever the result is truncated (cap,
// read error, join expiry, or kill path) so text-sniffing consumers (trusted
// parser fallback, Shizuku client via decodeExecReply) observe the same flag
// the producer sets. Internal for the same testability reason as above.
// Uses snapshot() so the copy is atomic vs a still-appending drain (#54).
internal fun capped(sink: StringBuilder, truncated: Boolean): String {
    val snap = snapshot(sink)
    return if (truncated) snap + "\n$TRUNCATION_MARKER" else snap
}

// ROOT gateway: executes core-built pm commands via `su -c`.
// DEVICE-UNVERIFIED: su presence/behavior varies by ROM; verified only by
// compilation + JVM tests here, never on a rooted device.
//
// Cancel contract (P2-1): run() is cancellable latest-wins. The su wait is
// the cancellable captureProcessCancellable above (poll + ensureActive), so
// a selectChannel latest-wins cancel ends the loser probe/write wait within
// one SU_WAIT_POLL_MS slice and releases opMutex for the winner -- bounded
// handover (<1 s), never the full SU_EXEC_TIMEOUT_MS. CancellationException
// always propagates (never swallowed into a definite result): the loser
// aborts before refreshLocked, so it never converges. destroyForcibly on
// cancel is best-effort local kill only, NOT remote preemption -- a
// post-spawn cancel stays unknown-after-send (Phase A transportUnknown
// semantics); any result-form of that state carries transportUnknown=true.
class SuCmdGateway(
    // Monotonic clock seam (P2-2, PR #40 Re-review): elapsed source for every
    // cancellable wait above. Default nanoTime is monotonic on JVM and
    // Android ART (immune to wall/NTP rollback); production may inject
    // SystemClock.elapsedRealtime only if deep-sleep-inclusive (BOOTTIME)
    // semantics are desired (nanoTime is CLOCK_MONOTONIC and excludes deep
    // sleep). JVM tests inject a fake.
    // Kept BEFORE execFn so `SuCmdGateway { argv -> ... }` still binds execFn.
    private val monoMs: () -> Long = ::defaultMonoMs,
    // Injectable process spawn (P2-1 test seam): production spawns `su -c`
    // via Runtime.exec; JVM tests inject a real blocking `sleep` subprocess
    // (actual Process, non-cooperative waitFor) so latest-wins handover is
    // proven against a true subprocess block, not a cancellable fake suspend.
    private val execFn: (Array<String>) -> Process = { argv -> Runtime.getRuntime().exec(argv) },
) : CmdGateway {
    override val privilege: Privilege = Privilege.ROOT

    override suspend fun run(cmd: String): CmdResult =
        withContext(Dispatchers.IO) {
            try {
                captureProcessCancellable(suArgv(cmd), suTimeoutFor(cmd), monoMs = monoMs, execFn = execFn)
            } catch (e: InterruptedException) {
                // Post-spawn unknown (Phase A1): an interrupt escaping
                // captureProcess means the spawn was attempted and the
                // remote pm may have executed. Restore the flag and force
                // read-only reconciliation. The "interrupted: " prefix is
                // the wire-frozen backstop shared with the service side.
                Thread.currentThread().interrupt()
                CmdResult(exit = 1, out = "", err = "interrupted: ${(e.message ?: "su interrupted")}", truncated = true, transportUnknown = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Conservative unknown (Phase G1): this catch cannot prove
                // pre-spawn -- captureProcessCancellable already maps every
                // known pre-spawn throw to "spawn failed: ..." (definite)
                // and every known post-spawn throw to transportUnknown, so
                // anything reaching here is an unclassified escape (e.g. a
                // rogue Process API throw). Fail closed to Unknown via the
                // in-process flag; the "su failed: " text prefix is kept
                // verbatim (isPostSpawnTransportText("su failed: boom") stays
                // false -- discrimination walks the flag, not the text).
                // True pre-spawn definite stays with the "spawn failed" path.
                CmdResult(exit = 1, out = "", err = "su failed: ${(e.message ?: e.javaClass.simpleName)}", transportUnknown = true)
            }
        }
}
