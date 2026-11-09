// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Capture-truncation marker appended by capture helpers whenever the capture
// is truncated (cap, read error, or join expiry -- PR-2 Phase B, issue #22).
// Single source of truth: gateways append "\n$TRUNCATION_MARKER" via
// capped(sink, truncated) and parsers sniff it via
// PmOutputParser.parsePackagesTrusted (legacy text fallback for callers that
// still build CmdResult without the flag, and for the Shizuku Binder client
// which re-derives the flag from text via decodeExecReply). Changing this
// string requires both sides to change together.
const val TRUNCATION_MARKER = "[truncated]"

// Result of a single command execution via a gateway.
// exit is the process exit code (0 means success on shell/ROOT channels).
data class CmdResult(
    val exit: Int,
    val out: String,
    val err: String,
    // Capture-truncation flag (PR-2, issue #22).
    // Contract: capture helpers append newline + TRUNCATION_MARKER whenever
    // the capture is truncated (cap, read error, or join expiry); flag and
    // marker are always set together so parsers can tell "clean empty" apart
    // from "output was cut off" without sniffing text. Defaults to false so
    // every existing CmdResult(...) call site keeps compiling and keeps its
    // old meaning. Consumed since stage 2:
    // verify/list judge the flag via PmOutputParser.parsePackagesTrusted.
    val truncated: Boolean = false,
    // Client-side wait-timeout flag (PR-32 P1-1, unknown-after-send).
    // Contract: the producer must set timedOut=true when it abandons the
    // client-side wait for the reply (reply not received -- the remote
    // command may still have executed). exit/out/err are then untrusted and
    // must NOT be judged by isSuccess/exit code. Defaults to false so every
    // existing CmdResult(...) call site keeps compiling and keeps its old
    // meaning. Consumed by DualRepository.cloneVerified/removeVerified:
    // a timedOut write NEVER maps to InstallFailed/RemoveFailed; it runs a
    // bounded read-only verifyListProbe instead (no write resend).
    // Parsers/callers must never treat timedOut as definite failure.
    // Note: the flag does not survive the Shizuku binder reply (wire layout
    // frozen to (exit, out, err), see ShizukuExecProtocol): a service-side
    // captureProcess timeout arrives as exit=1 + its "timeout after ..."
    // text with timedOut=false. DualRepository therefore ALSO treats
    // isServiceTimeoutText(res.err) (anchored producer prefixes, below) as
    // the same unknown-after-send signal -- text backstop only, no wire
    // change.
    val timedOut: Boolean = false,
    // Post-dispatch transport-unknown flag (PR-32 P1-2, unknown-after-send).
    // Contract: the producer must set transportUnknown=true when the write
    // was (or may have been) dispatched to the remote service but the reply
    // was lost -- transact threw, the binder died mid-call, or the reply
    // decode failed. The remote pm may already have executed, so exit/out/
    // err are untrusted and must NOT be judged by isSuccess/exit code --
    // same consumer rule as timedOut (read-only verifyListProbe, no write
    // resend, reverse observation maps to Unknown). Defaults to false so
    // every existing CmdResult(...) call site keeps compiling and keeps its
    // old meaning. four-signal rule for write replies:
    // explicit pm failure (pm reports Failure[...], both flags false) ->
    // definite failure; pre-dispatch refusal (command never sent: no binder,
    // not authorized, bind failure -- both flags false) -> definite failure;
    // post-dispatch reply loss (timedOut OR transportUnknown OR
    // isServiceTimeoutText OR isPostSpawnTransportText) -> reconcile
    // read-only, never claim failure.
    // Set client-side only (Shizuku transactExec catch path); never crosses
    // the frozen (exit, out, err) parcel -- same wire constraint as timedOut.
    val transportUnknown: Boolean = false,
) {
    // Backward-compatible aliases for the older field names.
    val exitCode: Int get() = exit
    val stdout: String get() = out
    val stderr: String get() = err
}

// Service-side timeout text backstop (PR-32 P1-1 follow-up).
// Survey of our own timeout producers (exact err text, case-exact):
// - captureProcess timeout (SuCmdGateway.kt: shared by the su client AND
//   GalaxyExecService): "timeout after ${timeoutMs}ms: ${argv0}" -- e.g.
//   "timeout after 60000ms: sh" on the service side (argv "sh -c ...").
//   The timedOut flag is lost on the wire: onTransact replies only
//   (exit, out, err) and the client rebuilds truncated from the marker,
//   so a service-side 60 s timeout arrives as exit=1 + this text +
//   timedOut=false (without this backstop the repo would misjudge it as
//   InstallFailed/RemoveFailed).
// - ShizukuExecErrors.timedOut() (client withTimeoutOrNull expiry):
//   "shizuku: exec timed out after 60000ms" -- flag survives (same
//   process); the text match is a backstop only.
// Deliberately NOT matched (anchored startsWith excludes them):
// - "shizuku: bind failed: bind timed out after ..." (bind wrapper: the
//   command never reached the service -- different knowledge state);
// - "interrupted: ...", "spawn failed: ...", "service exec failed: ...",
//   "su failed post-spawn: ...", and any mid-line "timeout" word inside pm
//   out/err. "interrupted: ", "service exec failed: " and "su failed
//   post-spawn: " are owned by the post-spawn backstop below
//   (isPostSpawnTransportText), NOT here: they are post-spawn unknown
//   (may have executed), while "spawn failed:" is pre-spawn definite
//   (process never started).
// Both prefixes contain a space, which a package name can never contain, so
// normal install/uninstall output (including package echoes) cannot hit.
// Wire layout stays frozen: no new parcel field, old payloads compatible.
// Pure, no I/O -- covered by VerifyRetryTest predicate locks.
private const val SERVICE_TIMEOUT_PREFIX = "timeout after "
private const val SHIZUKU_TIMEOUT_PREFIX = "shizuku: exec timed out after "

internal fun isServiceTimeoutText(err: String): Boolean =
    err.startsWith(SERVICE_TIMEOUT_PREFIX) || err.startsWith(SHIZUKU_TIMEOUT_PREFIX)

// Post-spawn transport text backstop (Phase A1, PR #40 Re-review P1).
// Pre-spawn vs post-spawn contract:
// - Pre-spawn definite (process never started, pm could not have executed):
//   "spawn failed: ..." (Runtime.exec threw: missing su, SecurityException).
//   transportUnknown=false, timedOut=false -> DualRepository claims definite
//   failure (InstallFailed/RemoveFailed). NEVER matched here.
// - Post-spawn unknown (spawn succeeded, pm may have executed, reply lost):
//   "interrupted: ..." (su/service wait or drain join interrupted after
//   spawn -- captureProcess wait-interrupt and drain-join-interrupt paths,
//   plus the SuCmdGateway.run InterruptedException catch),
//   "service exec failed: ..." (GalaxyExecService.onTransact catch around
//   captureProcess -- any exception escaping the service-side capture after
//   spawn started), and
//   "su failed post-spawn: ..." (SuCmdGateway.captureProcess drain-setup
//   failure or outer guard after Runtime.exec() already started the
//   process). All three arrive over the frozen (exit, out, err) wire with
//   flags lost, so DualRepository.isUnknownAfterSend matches them by these
//   anchored prefixes (fail closed: read-only verifyListProbe, no resend).
// Anchoring is exact (startsWith, case-exact, trailing ": " included) so
// normal pm outputs -- including "Failure [...]" lines, which start with a
// capital F -- can never hit. Mid-line occurrences inside pm out/err are
// excluded by the same anchoring.
// Wire layout stays frozen: no new parcel field, old payloads compatible.
// Pure, no I/O -- covered by VerifyRetryTest predicate locks.
private const val INTERRUPTED_PREFIX = "interrupted: "
private const val SERVICE_EXEC_FAILED_PREFIX = "service exec failed: "
// SuCmdGateway.captureProcess post-spawn catch (drain-setup failure or outer
// guard after Runtime.exec() already started the process): the process may
// have executed, so the frozen-wire reply (flags lost) must be treated as
// unknown-after-send. Anchored startsWith so mid-line occurrences inside pm
// output cannot trigger a false positive.
private const val SU_FAILED_POST_SPAWN_PREFIX = "su failed post-spawn: "

internal fun isPostSpawnTransportText(err: String): Boolean =
    err.startsWith(INTERRUPTED_PREFIX) ||
    err.startsWith(SERVICE_EXEC_FAILED_PREFIX) ||
    err.startsWith(SU_FAILED_POST_SPAWN_PREFIX)

// Abstraction over SHELL (Shizuku) and ROOT (su/hook) execution channels.
// Implementations must never throw for command failure; report via CmdResult.
interface CmdGateway {
    // Declared privilege of this gateway implementation.
    val privilege: Privilege

    // Runs one shell command line and returns captured output.
    suspend fun run(cmd: String): CmdResult

    // Convenience overload joining argv with spaces; prefer run(String).
    suspend fun run(argv: List<String>): CmdResult = run(argv.joinToString(" "))
}

// No-op gateway used when privilege is NONE; blocks all writes safely.
class NoOpGateway : CmdGateway {
    // Always reports denial without running anything.
    override val privilege: Privilege = Privilege.NONE

    // Returns a non-zero result so callers show read-only UI state.
    override suspend fun run(cmd: String): CmdResult {
        return CmdResult(exit = 1, out = "", err = "denied: no privilege")
    }
}

// Validates and escapes shell arguments; rejects anything outside the allowlist.
// Package names, permissions and ops may only contain [a-zA-Z0-9._-].
// User ids must be positive dual profile ids (never 0: the owner profile is off-limits).
object ShellEscaper {
    private val SAFE_TOKEN = Regex("^[a-zA-Z0-9._-]+$")
    private val DIGITS = Regex("^[0-9]+$")

    // Validates a package name; package charset is shell-safe so it is returned as-is.
    fun escapePackage(pkg: String): String {
        require(pkg.isNotEmpty() && SAFE_TOKEN.matches(pkg)) { "invalid package: $pkg" }
        return pkg
    }

    // Validates a numeric user id and renders it as a string.
    // C1 safety boundary (issue #2, P0): user 0 (owner profile) is rejected here
    // on purpose. All pm command builders (install-existing / uninstall / grant /
    // appops / path) funnel through this function, so no write path can ever be
    // assembled against the owner profile; callers must resolve a real dual id
    // first via the verified boundary (DualRepository.resolveDualUserId() /
    // CmdGateway.fetchVerifiedDualId(), never the deprecated label-only
    // PmOutputParser.resolveDualUserId(List)) or fail closed. The owner
    // profile is never writable through this function by construction.
    fun escapeUserId(userId: Int): String {
        require(userId > 0) { "invalid userId (must be a dual profile id, never 0): $userId" }
        return userId.toString()
    }

    // Compat-only raw variant; prefer escapeUserId(Int). Same >0 dual-id policy:
    // all-zero inputs ("0", "00", ...) are rejected like userId == 0 above.
    fun escapeUserIdRaw(raw: String): String {
        require(raw.isNotEmpty() && DIGITS.matches(raw)) { "invalid userId: $raw" }
        require(raw.trimStart('0').isNotEmpty()) { "invalid userId (must be a dual profile id, never 0): $raw" }
        return raw
    }

    // Validates a permission name such as android.permission.CAMERA.
    fun escapePermission(permission: String): String {
        require(permission.isNotEmpty() && SAFE_TOKEN.matches(permission)) {
            "invalid permission: $permission"
        }
        return permission
    }

    // Validates an app-op name or mode token (same safe charset).
    fun escapeOpToken(token: String, field: String = "op"): String {
        require(token.isNotEmpty() && SAFE_TOKEN.matches(token)) { "invalid $field: $token" }
        return token
    }
}
