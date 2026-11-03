package com.galaxydual.core

// Structured entry parsed from pm/dumpsys output for one dual app.
data class DualAppInfo(
    // Base package name, e.g. com.example.chat.
    val packageName: String,
    // Android user id hosting the clone, e.g. 150.
    val userId: Int,
    // Whether dual instance is currently enabled.
    val dualEnabled: Boolean,
)

// Tolerant parser for pm-style outputs; safe on empty or unknown lines.
object PmOutputParser {
    // Parses lines like "package:/data/app/xxx basePackage=com.foo userId=150 enabled=true".
    fun parse(lines: List<String>): List<DualAppInfo> {
        // Collect valid entries, skip blanks and unrecognized formats.
        val out = mutableListOf<DualAppInfo>()
        for (raw in lines) {
            // Normalize whitespace before matching.
            val line = raw.trim()
            if (line.isEmpty()) continue
            // Minimal skeleton rule: split tokens and extract known keys.
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
        // Linear scan keeps the parser dependency-free and testable.
        for (token in line.split(" ")) {
            if (token.startsWith("$key=")) return token.substringAfter("=")
        }
        return null
    }

    // Fallback for plain "package:com.foo" lines from pm list packages.
    private fun extractPackageFallback(line: String): String? {
        // Accepts only the canonical pm prefix.
        if (!line.startsWith("package:")) return null
        return line.removePrefix("package:").trim().takeIf { it.isNotEmpty() }
    }
}
