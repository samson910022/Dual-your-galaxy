// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// One Android user/profile that may host a dual clone.
data class DualProfile(
    // Android user id, e.g. 0 for owner, 95 for a dual app profile.
    val userId: Int,
    // Human readable label parsed from pm output, e.g. "Owner" or "DUAL_APP".
    val label: String,
)

// Structured entry parsed from pm/dumpsys output for one dual app.
// Kept for backward compatibility with existing callers and docs.
data class DualAppInfo(
    // Base package name, e.g. com.example.chat.
    val packageName: String,
    // Android user id hosting the clone, e.g. 150.
    val userId: Int,
    // Whether dual instance is currently enabled.
    val dualEnabled: Boolean,
)

// Tolerant parser and command builder for pm-style outputs; pure Kotlin, no Android deps.
object PmOutputParser {
    // Matches lines like "UserInfo{0:Owner:13} running" or "UserInfo{95:DUAL_APP:400}".
    private val USER_INFO = Regex("UserInfo\\{(\\d+):([^:}]*):[^}]*\\}")

    // Keywords indicating failure even when the caller reports exit 0.
    private val FAILURE_HINTS = listOf("failure", "exception", "unknown package", "not installed", "error:")

    // Parses `pm list users` output into one entry per UserInfo{...} line.
    // Returns all profiles found; callers pick the dual one (non-zero id or DUAL label).
    fun parseUsers(output: String): List<DualProfile> {
        val out = mutableListOf<DualProfile>()
        for (raw in output.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = USER_INFO.find(line) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val label = match.groupValues[2].trim()
            out.add(DualProfile(userId = id, label = label))
        }
        return out
    }

    // Parses `pm list packages [--user N]` output into package names.
    // Accepts canonical "package:com.foo" lines and skips anything else.
    fun parsePackages(output: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in output.lines()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val pkg = line.removePrefix("package:").trim()
            if (pkg.isNotEmpty()) out.add(pkg)
        }
        return out
    }

    // Builds `pm install-existing --user <dualId> <pkg>` to clone into the dual profile.
    fun buildInstallExisting(pkg: String, dualId: Int): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        return "pm install-existing --user $u $p"
    }

    // Builds `pm uninstall --user <dualId> <pkg>` to remove the dual clone only.
    fun buildUninstall(pkg: String, dualId: Int): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        return "pm uninstall --user $u $p"
    }

    // Builds `pm grant --user <dualId> <pkg> <permission>`.
    fun buildGrant(pkg: String, dualId: Int, permission: String): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        val perm = ShellEscaper.escapePermission(permission)
        return "pm grant --user $u $p $perm"
    }

    // Builds `appops set --user <dualId> <pkg> <op> <mode>`, e.g. mode allow/deny.
    fun buildAppOpsSet(pkg: String, dualId: Int, op: String, mode: String): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        val opToken = ShellEscaper.escapeOpToken(op, "op")
        val modeToken = ShellEscaper.escapeOpToken(mode, "mode")
        return "appops set --user $u $p $opToken $modeToken"
    }

    // Builds `pm path --user <dualId> <pkg>` for second-check verification.
    fun buildPathCheck(pkg: String, dualId: Int): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        return "pm path --user $u $p"
    }

    // Success means exit 0 plus no failure keywords in combined output.
    fun isSuccess(output: String, exit: Int): Boolean {
        if (exit != 0) return false
        val lower = output.lowercase()
        return FAILURE_HINTS.none { lower.contains(it) }
    }

    // Legacy entry point kept for compatibility: parses key=value token lines.
    // Example: "package:/data/app/xxx basePackage=com.foo userId=150 enabled=true".
    fun parse(lines: List<String>): List<DualAppInfo> {
        val out = mutableListOf<DualAppInfo>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val pkg = extractValue(line, "basePackage")
                ?: extractPackageFallback(line) ?: continue
            val user = extractValue(line, "userId")?.toIntOrNull() ?: 0
            val enabled = extractValue(line, "enabled")?.toBooleanStrictOrNull() ?: false
            out.add(DualAppInfo(pkg, user, enabled))
        }
        return out
    }

    // Extracts key=value tokens from a whitespace-separated line.
    private fun extractValue(line: String, key: String): String? {
        for (token in line.split(" ")) {
            if (token.startsWith("$key=")) return token.substringAfter("=")
        }
        return null
    }

    // Fallback for plain "package:com.foo" lines from pm list packages.
    private fun extractPackageFallback(line: String): String? {
        if (!line.startsWith("package:")) return null
        return line.removePrefix("package:").trim().takeIf { it.isNotEmpty() }
    }
}
