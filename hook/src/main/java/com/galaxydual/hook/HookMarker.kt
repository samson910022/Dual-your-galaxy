// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.io.File

// Phase F4b-P2 (issue #6): hook-active marker shared by the hook writer and
// the app reader.
//
// Cross-process signal WITHOUT any IPC dependency: after DualHook installs at
// least one hook in system_server (scope "system_server") or in the daagent
// process (scope "daagent"), it best-effort writes this marker file so the
// app UI can show "LSPosed Hook: active (scopes)" vs "not detected". The app
// side reads it via HookStateProvider (which reuses parseScopes below, so
// writer and reader can never disagree on the format).
//
// Format (plain text, two lines):
//   scope=system_server,daagent
//   timeMs=1710000000000
//
// Path selection (REAL-DEVICE evidence, Nov 2026): the first marker revision
// wrote only /data/local/tmp, but on-device system_server got EACCES writing
// there (shell_data_file/tmp context is not writable by the system_server
// SELinux domain). The primary path is therefore /data/system (labeled
// system_data_file, writable by system_server); the old /data/local/tmp path
// is kept as a FALLBACK write target (and read source) so a ROM where
// /data/system is unexpectedly unwritable can still record the signal.
// Writer order is fixed: primary first, fallback only when the primary write
// fails. Reader order is fixed the same way, and reads UNION both files so a
// scope recorded via the fallback is never lost.
//
// Honesty limits (DEVICE-UNVERIFIED, pending real-device validation):
// - staleness: the file SURVIVES REBOOTS. After a reboot (or after the user
//   disables the module in the LSPosed manager) the stale file still claims
//   "active" until a hook process rewrites it. The app therefore treats the
//   marker as existence + scope evidence only, and labels it as such in the
//   UI. A boot-time comparison (marker timeMs vs device boot time) would
//   harden this, but the right clock source needs on-device verification, so
//   it is deliberately NOT implemented here (no fake freshness).
// - SELinux: whether the app uid can directly read either path on a given
//   ROM is unverified (a direct read is likely denied); an unreadable file
//   reads as "not detected", never as active. When a SHELL/ROOT channel is
//   ready the app additionally retries via `cat` over the gateway (see
//   HookStateProvider.readHookStateWithGateway); that path is equally
//   unverified on-device.
// - merging: both hook processes write the same primary file, so markActive
//   read-merge-writes (existing scopes UNION new scope, reading BOTH the
//   primary and the fallback first). A concurrent write may drop one scope;
//   the next hook event rewrites it. No locking, no IPC.
//
// Framework-free (no android.* / LibXposed types): build/parse/mark run as
// plain JVM unit tests (hook/src/test HookMarkerTest).
object HookMarker {
    // Primary marker path: /data/system is labeled system_data_file, which
    // the system_server SELinux domain can write (unlike /data/local/tmp,
    // where real-device system_server got EACCES -- see header).
    const val MARKER_PATH = "/data/system/galaxydual_hook.active"

    // Fallback marker path: the pre-EACCES-evidence location. Tried only
    // when the primary write fails (writer) and unioned into reads, so a
    // scope recorded here is still honored.
    const val LEGACY_MARKER_PATH = "/data/local/tmp/galaxydual_hook.active"

    // Scope written by onSystemServerStarting after whitelist-query hooks.
    const val SCOPE_SYSTEM_SERVER = "system_server"

    // Scope written by onPackageLoaded(daagent) after WhiteListApps hooks.
    const val SCOPE_DAAGENT = "daagent"

    // Pure: renders scopes (sorted, blank-free) + timestamp. Never throws
    // for any input (null-safe by construction: params are non-null).
    fun buildContent(scopes: Set<String>, nowMs: Long): String {
        val clean = scopes.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        return "scope=" + clean.joinToString(",") + "\ntimeMs=" + nowMs + "\n"
    }

    // Pure: extracts the scope set; null/blank/garbage (including unknown
    // lines) yields emptySet(), never null, never throws.
    fun parseScopes(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("scope=")) continue
            trimmed.removePrefix("scope=").split(",").forEach { token ->
                val scope = token.trim()
                if (scope.isNotEmpty()) out.add(scope)
            }
        }
        return out
    }

    // Pure: extracts the timeMs line; absent/unparseable yields null.
    // Kept for a future boot-time staleness check (see header: not wired).
    fun parseTimeMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("timeMs=")) {
                return trimmed.removePrefix("timeMs=").trim().toLongOrNull()
            }
        }
        return null
    }

    // Best-effort read-merge-write: existing scopes (UNIONED across the
    // primary and fallback files, so history recorded via either path
    // survives) UNION [scope], stamped with nowMs. Write order is fixed:
    // primary first, fallback only when the primary write fails. Returns
    // true only when one of the files was written. NEVER throws (any
    // Throwable -> onLog + false): the hook path must survive even when both
    // paths are unwritable on a given ROM.
    //
    // markerFile/nowMs are parameters (not statics) so JVM tests can point
    // at a temp dir; production passes the defaults. fallbackFile trails
    // onLog (rather than sitting next to markerFile) so existing positional
    // call sites keep compiling with identical semantics.
    fun markActive(
        scope: String,
        markerFile: File = File(MARKER_PATH),
        nowMs: Long = System.currentTimeMillis(),
        onLog: (String) -> Unit = {},
        fallbackFile: File = File(LEGACY_MARKER_PATH),
    ): Boolean {
        try {
            val want = scope.trim()
            val merged = (parseScopes(readTextOrNull(markerFile, onLog, "primary")) +
                parseScopes(readTextOrNull(fallbackFile, onLog, "fallback"))).toMutableSet()
            if (want.isNotEmpty()) merged.add(want)
            if (merged.isEmpty()) {
                safeLog(onLog, "marker not written: no scope to record")
                return false
            }
            val content = buildContent(merged, nowMs)
            if (tryWrite(markerFile, content)) {
                safeLog(onLog, "marker wrote scope=${merged.sorted().joinToString(",")}")
                return true
            }
            safeLog(onLog, "marker primary write failed, trying fallback")
            if (tryWrite(fallbackFile, content)) {
                safeLog(onLog, "marker wrote fallback scope=${merged.sorted().joinToString(",")}")
                return true
            }
            safeLog(onLog, "marker write failed: primary and fallback unwritable")
            return false
        } catch (t: Throwable) {
            safeLog(onLog, "marker write failed: ${t.message}")
            return false
        }
    }

    // Best-effort read: missing/unreadable/garbage files -> emptySet()
    // (caller maps that to "not detected"). Reads BOTH the primary and the
    // fallback file and returns the UNION, so a scope recorded via either
    // write path is honored. Never throws.
    fun readScopes(
        markerFile: File = File(MARKER_PATH),
        fallbackFile: File = File(LEGACY_MARKER_PATH),
    ): Set<String> {
        return try {
            parseScopes(readTextOrNull(markerFile)) +
                parseScopes(readTextOrNull(fallbackFile))
        } catch (_: Throwable) {
            emptySet()
        }
    }

    // Null when the file is absent or unreadable (SELinux denial included);
    // never throws. Shared by markActive/readScopes so both agree on what
    // "unreadable" means.
    private fun readTextOrNull(
        file: File,
        onLog: (String) -> Unit = {},
        which: String = "primary",
    ): String? {
        return try {
            if (!file.isFile) null else file.readText()
        } catch (e: Exception) {
            safeLog(onLog, "marker $which read failed, ignoring: ${e.message}")
            null
        }
    }

    // Best-effort single-file write (mkdirs + writeText); false on any
    // failure, never throws.
    private fun tryWrite(file: File, content: String): Boolean {
        return try {
            try {
                file.parentFile?.mkdirs()
            } catch (_: Exception) {
                // Best effort: parents normally exist.
            }
            file.writeText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    // Logging itself must never break the marker path (log() can throw when
    // the LSPosed log backend is unavailable mid-startup).
    private fun safeLog(onLog: (String) -> Unit, msg: String) {
        try {
            onLog(msg)
        } catch (_: Throwable) {
            // Swallow: diagnostics only.
        }
    }
}
