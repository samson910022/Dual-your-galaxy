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
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Default wall-clock budget for one `su -c` invocation (pm commands are
// normally sub-second; install-existing on a cold device can take longer).
internal const val SU_EXEC_TIMEOUT_MS = 60_000L

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
    // with the capped(out) read below. Accepted as extremely low risk (2 s
    // grace on kilobyte-scale pm output; worst case is a torn tail that the
    // trusted parse already treats as untrusted), never silently trusted.
    val out = StringBuilder()
    val err = StringBuilder()
    val drainOut = thread(isDaemon = true, name = "capture-stdout") {
        drainCapped(proc.inputStream, out)
    }
    val drainErr = thread(isDaemon = true, name = "capture-stderr") {
        drainCapped(proc.errorStream, err)
    }
    val exited = try {
        proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        proc.destroyForcibly()
        // Honest flag: the process was killed mid-stream, so the capture is
        // incomplete by construction even when nothing hit the cap. exit=1,
        // so parser outcomes are unchanged (Unknown either way).
        return CmdResult(exit = 1, out = capped(out), err = "interrupted: ${(e.message ?: "wait interrupted")}", truncated = true)
    }
    if (!exited) {
        proc.destroyForcibly()
        drainOut.join(2_000)
        drainErr.join(2_000)
        // Same honest-flag reasoning as the interrupt path above: killed
        // mid-stream means incomplete output; exit=1 keeps behavior unchanged.
        return CmdResult(exit = 1, out = capped(out), err = "timeout after ${timeoutMs}ms: ${argv.firstOrNull() ?: "?"}", truncated = true)
    }
    drainOut.join(5_000)
    drainErr.join(5_000)
    // Join-expiry honesty: join() returns Unit (no timed-out flag), so isAlive
    // is the expiry signal. A still-alive drain means the capture may be
    // incomplete even when nothing hit the cap -- fail closed via truncated.
    val joinExpired = drainOut.isAlive || drainErr.isAlive
    return result(proc.exitValue(), out, err, joinExpired)
}

// Single construction point for the steady-state path: truncated is derived
// from the same cap test as capped() so flag and marker can never disagree,
// plus join-expiry (a drain still alive after its join window) which likewise
// means the capture may be incomplete.
private fun result(exit: Int, out: StringBuilder, err: StringBuilder, joinExpired: Boolean = false): CmdResult =
    CmdResult(exit = exit, out = capped(out), err = capped(err), truncated = isCapped(out) || isCapped(err) || joinExpired)

// Reads all of input into sink up to MAX_CAPTURE_CHARS; closes the stream.
// Truncation marker included so callers can tell capped output apart.
private fun drainCapped(input: java.io.InputStream, sink: StringBuilder) {
    try {
        input.bufferedReader().use { reader ->
            val buf = CharArray(8_192)
            while (true) {
                if (sink.length >= MAX_CAPTURE_CHARS) break
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                val room = MAX_CAPTURE_CHARS - sink.length
                sink.append(buf, 0, minOf(n, room))
            }
        }
    } catch (_: Exception) {
        // Best effort: a broken pipe mid-read still yields whatever was
        // captured; the exit code below carries the failure signal.
    }
}

private fun capped(sink: StringBuilder): String =
    if (isCapped(sink)) sink.toString() + "\n$TRUNCATION_MARKER" else sink.toString()

// Mirrors the capped() cap test so captureProcess/result() can set
// CmdResult.truncated without sniffing text (PR-2, issue #22; consumed since
// stage 2 via PmOutputParser.parsePackagesTrusted). True iff this stream hit
// MAX_CAPTURE_CHARS and capped() appended the marker.
// Off-by-one note (deliberate, fail closed): drainCapped() stops AT the cap,
// so a stream can only reach length == MAX, never exceed it; treating the
// exact-equality case as capped may flag a stream that ended precisely on the
// boundary without loss, but never misses a real cut. cap test unchanged (interrupt/timeout flag intentionally true).
private fun isCapped(sink: StringBuilder): Boolean =
    sink.length >= MAX_CAPTURE_CHARS

// ROOT gateway: executes core-built pm commands via `su -c`.
// DEVICE-UNVERIFIED: su presence/behavior varies by ROM; verified only by
// compilation + JVM tests here, never on a rooted device.
class SuCmdGateway : CmdGateway {
    override val privilege: Privilege = Privilege.ROOT

    override suspend fun run(cmd: String): CmdResult =
        withContext(Dispatchers.IO) {
            try {
                captureProcess(suArgv(cmd))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CmdResult(exit = 1, out = "", err = "su failed: ${(e.message ?: e.javaClass.simpleName)}")
            }
        }
}
