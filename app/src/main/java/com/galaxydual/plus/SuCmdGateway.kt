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

// Maps a su command line to its wait budget: probe (`id -u`) uses the short
// probe timeout, every other command (pm writes, list probes, identity
// fetches) keeps the full exec timeout. Pure so JVM tests pin it.
internal fun suTimeoutFor(cmd: String): Long =
    if (cmd.trim() == "id -u") SU_PROBE_TIMEOUT_MS else SU_EXEC_TIMEOUT_MS

// Cap per captured stream so a pathological `pm` output cannot OOM the app.
// Dual-profile listings are kilobytes; the cap only bites on abuse.
internal const val MAX_CAPTURE_CHARS = 256_000

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
internal fun captureProcess(argv: Array<String>, timeoutMs: Long = SU_EXEC_TIMEOUT_MS): CmdResult {
    val proc = try {
        Runtime.getRuntime().exec(argv)
    } catch (e: Exception) {
        // Covers missing su ("No such file or directory"), SecurityException,
        // and any other spawn failure: explicit error, exit != 0, no throw.
        return CmdResult(exit = 1, out = "", err = "spawn failed: ${(e.message ?: e.javaClass.simpleName)}")
    }
    // Drain both pipes on daemon threads: reading stdout to EOF while stderr
    // sits unfilled can deadlock once the stderr pipe buffer fills.
    // Known limitation (PR-2 Phase 3, R8): each StringBuilder has exactly one
    // writer thread and the steady path joins both drains before reading, but
    // StringBuilder itself is unsynchronized, and on the timeout path
    // join(2_000) can expire while a drain thread is still appending, racing
    // with the buildResult read below. Accepted as extremely low risk (2 s
    // grace on kilobyte-scale pm output; worst case is a torn tail that the
    // trusted parse already treats as untrusted), never silently trusted.
    val out = StringBuilder()
    val err = StringBuilder()
    // Per-stream drain outcomes (Phase B, issue #22 P1-#2): AtomicReferences
    // because the join()s below may expire while a drain is still writing.
    // Defaults are READ_ERROR (fail closed: a drain thread dying from an
    // Error without setting a status must not look like clean EOF).
    val outStatus = AtomicReference(DrainStatus.READ_ERROR)
    val errStatus = AtomicReference(DrainStatus.READ_ERROR)
    val drainOut = thread(isDaemon = true, name = "capture-stdout") {
        outStatus.set(drainCapped(proc.inputStream, out))
    }
    val drainErr = thread(isDaemon = true, name = "capture-stderr") {
        errStatus.set(drainCapped(proc.errorStream, err))
    }
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
    val joinExpired = drainOut.isAlive || drainErr.isAlive
    return buildResult(proc.exitValue(), out, err, outStatus.get(), errStatus.get(), joinExpired)
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
internal suspend fun cancellableAwaitExit(proc: Process, timeoutMs: Long): Boolean {
    val deadlineMs = System.currentTimeMillis() + timeoutMs
    while (true) {
        // Queue-collapse checkpoint: a cancelled select job surfaces here
        // within one poll slice, releasing opMutex for the winner. This is
        // NOT remote preemption (see captureProcessCancellable header).
        currentCoroutineContext().ensureActive()
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0) {
            // Final non-blocking check before declaring timeout.
            return proc.waitFor(0, TimeUnit.MILLISECONDS)
        }
        val poll = minOf(SU_WAIT_POLL_MS, remaining)
        if (proc.waitFor(poll, TimeUnit.MILLISECONDS)) return true
    }
}

// Cancellable join for a drain thread: polls join(SU_DRAIN_POLL_MS) with the
// same cancellation checkpoint. Returns when the thread dies OR when the
// budget expires (caller re-checks isAlive for joinExpired, same as the
// blocking path). Throws CancellationException on coroutine cancel (caller
// destroys and rethrows) and InterruptedException on thread interrupt
// (caller maps to transportUnknown).
internal suspend fun cancellableJoin(t: Thread, budgetMs: Long) {
    val deadlineMs = System.currentTimeMillis() + budgetMs
    while (t.isAlive) {
        currentCoroutineContext().ensureActive()
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0) return
        try {
            t.join(minOf(SU_DRAIN_POLL_MS, remaining))
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
internal suspend fun captureProcessCancellable(
    argv: Array<String>,
    timeoutMs: Long = SU_EXEC_TIMEOUT_MS,
    execFn: (Array<String>) -> Process = { a -> Runtime.getRuntime().exec(a) },
): CmdResult {
    currentCoroutineContext().ensureActive()
    val proc = try {
        execFn(argv)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return CmdResult(exit = 1, out = "", err = "spawn failed: ${(e.message ?: e.javaClass.simpleName)}")
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
    try {
        // Materialize streams on the caller thread: proc.inputStream /
        // errorStream getters may throw, and an access inside thread{...}
        // would run on the child thread (uncaught there, invisible here).
        // Materializing here routes a getter throw to the outer post-spawn
        // generic below (transportUnknown), which is the correct
        // unknown-after-send bucket.
        val inS = proc.inputStream
        val errS = proc.errorStream
        val drainOut = thread(isDaemon = true, name = "capture-stdout") {
            outStatus.set(drainCapped(inS, out))
        }
        val drainErr = thread(isDaemon = true, name = "capture-stderr") {
            errStatus.set(drainCapped(errS, err))
        }
        val exited = try {
            cancellableAwaitExit(proc, timeoutMs)
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
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
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
            try {
                cancellableJoin(drainOut, 2_000)
                cancellableJoin(drainErr, 2_000)
            } catch (e: CancellationException) {
                // Timeout already abandoned the wait (timedOut below), but the
                // cancel must still release the waiter at once for handover.
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: drain join interrupted after timeout: ${(e.message ?: "interrupted")}", truncated = true, timedOut = true, transportUnknown = true)
            }
            return CmdResult(exit = 1, out = capped(out, true), err = "timeout after ${timeoutMs}ms: ${argv.firstOrNull() ?: "?"}", truncated = true, timedOut = true)
        }
        try {
            cancellableJoin(drainOut, 5_000)
            cancellableJoin(drainErr, 5_000)
        } catch (e: CancellationException) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: drain join interrupted: ${(e.message ?: "interrupted")}", truncated = true, transportUnknown = true)
        }
        val joinExpired = drainOut.isAlive || drainErr.isAlive
        return buildResult(proc.exitValue(), out, err, outStatus.get(), errStatus.get(), joinExpired)
    } catch (e: CancellationException) {
        // Outer post-spawn guard: CE from any point after spawn (including a
        // rethrown inner CE) still destroys best effort and propagates --
        // never synthesized into a definite result (queue-collapse).
        try {
            proc.destroyForcibly()
        } catch (_: Exception) {
        }
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
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
        return CmdResult(exit = 1, out = capped(out, true), err = "interrupted: ${(e.message ?: "post-spawn interrupted")}", truncated = true, transportUnknown = true)
    } catch (e: Exception) {
        // Outer post-spawn guard (Phase G1 P1): any other exception after
        // spawn (waitFor/exitValue/drain-setup throwing) is
        // unknown-after-send -- the remote pm may have executed. Destroy
        // best effort (+ close opened streams; full P2-1 drain cleanup stays
        // with G2) and return transportUnknown, never definite. CE is
        // excluded above so cancellation is never swallowed here.
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
        return CmdResult(exit = 1, out = capped(out, true), err = "su failed post-spawn: ${(e.message ?: e.javaClass.simpleName)}", truncated = true, transportUnknown = true)
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
// deployment layer by ShizukuExecProtocol.SERVICE_VERSION (v2), which forces
// the daemon to rebuild the resident UserService on upgrade.
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

// Reads all of input into sink up to MAX_CAPTURE_CHARS; closes the stream.
// Returns the drain outcome; already-appended characters are preserved on
// every path, including READ_ERROR. Internal (not private) so JVM unit tests
// cover it with an injectable failing-stream fixture (GatewayAssemblyTest).
internal fun drainCapped(input: java.io.InputStream, sink: StringBuilder): DrainStatus {
    return try {
        input.bufferedReader().use { reader ->
            val buf = CharArray(8_192)
            var outcome: DrainStatus? = null
            while (outcome == null) {
                if (sink.length >= MAX_CAPTURE_CHARS) {
                    outcome = DrainStatus.CAPPED
                } else {
                    val n = reader.read(buf, 0, buf.size)
                    if (n < 0) {
                        outcome = DrainStatus.EOF
                    } else {
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
internal fun capped(sink: StringBuilder, truncated: Boolean): String =
    if (truncated) sink.toString() + "\n$TRUNCATION_MARKER" else sink.toString()

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
                captureProcessCancellable(suArgv(cmd), suTimeoutFor(cmd), execFn)
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
