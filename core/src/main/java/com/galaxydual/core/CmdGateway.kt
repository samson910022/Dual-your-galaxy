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
) {
    // Backward-compatible aliases for the older field names.
    val exitCode: Int get() = exit
    val stdout: String get() = out
    val stderr: String get() = err
}

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
