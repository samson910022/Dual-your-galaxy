package com.galaxydual.core

// Result of a single command execution via a gateway.
data class CmdResult(
    // Process exit code, 0 means success on shell/ROOT channels.
    val exitCode: Int,
    // Raw stdout captured from the command.
    val stdout: String,
    // Raw stderr captured from the command.
    val stderr: String,
)

// Abstraction over SHELL (Shizuku) and ROOT (su/hook) execution channels.
interface CmdGateway {
    // Runs a command and returns captured output; never throws for command failure.
    fun run(cmd: List<String>): CmdResult

    // Declared privilege of this gateway implementation.
    val privilege: Privilege
}

// No-op gateway used when privilege is NONE; blocks all writes safely.
class NoOpGateway : CmdGateway {
    // Always reports denial without running anything.
    override val privilege: Privilege = Privilege.NONE

    // Returns a non-zero result so callers show read-only UI state.
    override fun run(cmd: List<String>): CmdResult {
        // Denied: no execution channel is available.
        return CmdResult(exitCode = 1, stdout = "", stderr = "denied: no privilege")
    }
}
