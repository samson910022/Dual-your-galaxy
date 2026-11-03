// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Result of a single command execution via a gateway.
// exit is the process exit code (0 means success on shell/ROOT channels).
data class CmdResult(
    val exit: Int,
    val out: String,
    val err: String,
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
// User ids must be non-negative integers.
object ShellEscaper {
    private val SAFE_TOKEN = Regex("^[a-zA-Z0-9._-]+$")
    private val DIGITS = Regex("^[0-9]+$")

    // Validates a package name; package charset is shell-safe so it is returned as-is.
    fun escapePackage(pkg: String): String {
        require(pkg.isNotEmpty() && SAFE_TOKEN.matches(pkg)) { "invalid package: $pkg" }
        return pkg
    }

    // Validates a numeric user id and renders it as a string.
    fun escapeUserId(userId: Int): String {
        require(userId >= 0) { "invalid userId: $userId" }
        return userId.toString()
    }

    // Validates a raw user id string containing only digits.
    fun escapeUserIdRaw(raw: String): String {
        require(raw.isNotEmpty() && DIGITS.matches(raw)) { "invalid userId: $raw" }
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
