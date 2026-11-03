// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Repository for dual-profile app lifecycle; all commands go through PmOutputParser.
// Pure Kotlin with no Android dependency; execution is delegated to CmdGateway.
class DualRepository(
    private val gateway: CmdGateway,
) {
    companion object {
        // Command used to enumerate profiles.
        const val CMD_LIST_USERS = "pm list users"
    }

    // Lists all profiles from `pm list users`; empty when the channel fails.
    suspend fun listProfiles(): List<DualProfile> {
        val res = gateway.run(CMD_LIST_USERS)
        if (res.exit != 0) return emptyList()
        return PmOutputParser.parseUsers(res.out)
    }

    // Resolves the dual profile id: DUAL label first, else first non-owner id.
    suspend fun resolveDualUserId(): Int {
        val profiles = listProfiles()
        return profiles.firstOrNull { it.label.contains("DUAL", ignoreCase = true) }?.userId
            ?: profiles.firstOrNull { it.userId != 0 }?.userId
            ?: -1
    }

    // Clones a package into the dual profile via `pm install-existing`.
    suspend fun clone(pkg: String): CmdResult {
        val dualId = resolveDualUserId()
        if (dualId < 0) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return clone(pkg, dualId)
    }

    // Clones a package into an explicit dual profile id.
    suspend fun clone(pkg: String, dualId: Int): CmdResult {
        val cmd = PmOutputParser.buildInstallExisting(pkg, dualId)
        return gateway.run(cmd)
    }

    // Removes the clone from the dual profile via `pm uninstall --user`.
    suspend fun remove(pkg: String): CmdResult {
        val dualId = resolveDualUserId()
        if (dualId < 0) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return remove(pkg, dualId)
    }

    // Removes the clone from an explicit dual profile id.
    suspend fun remove(pkg: String, dualId: Int): CmdResult {
        val cmd = PmOutputParser.buildUninstall(pkg, dualId)
        return gateway.run(cmd)
    }

    // Double-checks installation via `pm path --user <dualId> <pkg>`.
    // Returns true only when exit is 0, output parses as success,
    // and stdout contains a "package:" path line.
    suspend fun verify(pkg: String): Boolean {
        val dualId = resolveDualUserId()
        if (dualId < 0) return false
        return verify(pkg, dualId)
    }

    // Verifies against an explicit dual profile id.
    suspend fun verify(pkg: String, dualId: Int): Boolean {
        val cmd = PmOutputParser.buildPathCheck(pkg, dualId)
        val res = gateway.run(cmd)
        if (!PmOutputParser.isSuccess(res.out + res.err, res.exit)) return false
        return res.out.lines().any { it.trim().startsWith("package:") }
    }
}
