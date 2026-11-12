// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Shared log-hygiene helpers (fail-closed, never throws).
//
// Package-qualified names never enter logs: exception logs keep only the
// simple class name + truncated message (take 200), class logs keep only the
// simple name (take 200). This bounds hostile message length on the hot path
// and avoids leaking ROM package structure into lspd logs.
// Used by DualHook / HookInstaller / WhitelistPatch / WhitelistPolicy.
internal object HookLog {
    private const val MAX_LOG_PART = 200

    fun shortEx(t: Throwable): String = try {
        (t.javaClass.simpleName?.takeIf { it.isNotEmpty() } ?: "Throwable").take(MAX_LOG_PART)
    } catch (_: Throwable) {
        "Throwable"
    }

    fun shortMsg(t: Throwable): String = try {
        (t.message ?: "").take(MAX_LOG_PART)
    } catch (_: Throwable) {
        ""
    }

    fun shortClass(c: Class<*>): String = try {
        (c.simpleName?.takeIf { it.isNotEmpty() } ?: c.name.substringAfterLast('.')).take(MAX_LOG_PART)
            .ifEmpty { "Class" }
    } catch (_: Throwable) {
        "Class"
    }

    // Shortens an already-stringified class/type name (e.g. stored className
    // strings in WhitelistPatch, or Class.getTypeName() in signatures).
    // Keeps only the segment after the last '.'/'$'/'/' and truncates to 200.
    fun shortName(name: String): String {
        return try {
            if (name.isEmpty()) ""
            else name.substringAfterLast('.').substringAfterLast('$').substringAfterLast('/').take(MAX_LOG_PART)
        } catch (_: Throwable) {
            ""
        }
    }
}
