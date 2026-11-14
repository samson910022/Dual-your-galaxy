// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Shared log-hygiene helpers (fail-closed, never throws).
// Public: app-module call sites (e.g. MainActivity) reuse the same short-form
// helpers; package-qualified names never enter logs: exception logs keep only
// the simple class name + truncated message (take 200), class logs keep only
// the simple name (take 200). This bounds hostile message length on the hot
// path and avoids leaking ROM package structure into lspd logs.
// Every string interpolated into a line is first stripped of control
// characters (CR/LF and every \p{Cntrl}), replaced by a single space, and
// only then truncated — so a hostile message/class-name/caller-supplied
// string can never inject extra log lines or terminal escape sequences.
// Used by DualHook / HookInstaller / WhitelistPatch / WhitelistPolicy / app.
object HookLog {
    private const val MAX_LOG_PART = 200

    // Control-character stripper shared by every helper (and by HookMarker's
    // unknown-scope line): one definition, applied before any .take().
    private val CONTROL_CHARS = Regex("[\\r\\n\\p{Cntrl}]")

    // Module-internal (HookMarker reuses it for its want= line): never throws.
    internal fun normalizeLogPart(text: String): String =
        text.replace(CONTROL_CHARS, " ")

    fun shortEx(t: Throwable): String = try {
        normalizeLogPart(t.javaClass.simpleName?.takeIf { it.isNotEmpty() } ?: "Throwable").take(MAX_LOG_PART)
    } catch (_: Throwable) {
        "Throwable"
    }

    fun shortMsg(t: Throwable): String = try {
        normalizeLogPart(t.message ?: "").take(MAX_LOG_PART)
    } catch (_: Throwable) {
        ""
    }

    fun shortClass(c: Class<*>): String = try {
        normalizeLogPart(c.simpleName?.takeIf { it.isNotEmpty() } ?: c.name.substringAfterLast('.')).take(MAX_LOG_PART)
            .ifEmpty { "Class" }
    } catch (_: Throwable) {
        "Class"
    }

    // Shortens an already-stringified class/type name (e.g. stored className
    // strings in WhitelistPatch, or Class.getTypeName() in signatures).
    // Keeps only the segment after the last '.'/'$'/'/' and truncates to 200.
    fun shortName(name: String): String {
        return try {
            val safe = normalizeLogPart(name)
            if (safe.isEmpty()) ""
            else safe.substringAfterLast('.').substringAfterLast('$').substringAfterLast('/').take(MAX_LOG_PART)
        } catch (_: Throwable) {
            ""
        }
    }
}
