// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    // H12: scope tokens are allowlisted — only {system_server, daagent} (the
    // only scopes this module ever writes) are honored; anything else is
    // dropped (fail-closed: an unknown token can never describe a scope) and
    // counted. At most UNKNOWN_SCOPE_DROP_LOG_CAP log lines per process;
    // pass onLog to observe drops, otherwise they are counted silently.
    private val ALLOWED_SCOPES = setOf(SCOPE_SYSTEM_SERVER, SCOPE_DAAGENT)
    private const val UNKNOWN_SCOPE_DROP_LOG_CAP = 4
    val unknownScopeDrops = AtomicLong(0L)
    private val unknownScopeDropLogs = AtomicInteger(0)

    fun parseScopes(raw: String?, onLog: ((String) -> Unit)? = null): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        var dropped = 0
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("scope=")) continue
            trimmed.removePrefix("scope=").split(",").forEach { token ->
                val scope = token.trim()
                if (scope.isEmpty()) return@forEach
                if (ALLOWED_SCOPES.contains(scope)) {
                    out.add(scope)
                } else {
                    dropped++
                }
            }
        }
        if (dropped > 0) {
            unknownScopeDrops.addAndGet(dropped.toLong())
            val n = unknownScopeDropLogs.incrementAndGet()
            if (n <= UNKNOWN_SCOPE_DROP_LOG_CAP) {
                try {
                    onLog?.invoke(
                        "marker ignored $dropped unknown scope token(s) (allowlist={system_server,daagent})",
                    )
                } catch (_: Throwable) {
                    // Diagnostics only.
                }
            }
        }
        return out
    }

    // H15: best-effort lost-update telemetry. Both hook processes write the
    // same primary file read-merge-write with no locking, so a concurrent
    // write may drop a scope. Track the per-process monotonic union of every
    // scope this process has ever read from disk; a merged write that no
    // longer contains a bit we previously saw means a previously recorded
    // scope vanished (peer write clobber, partial write, external edit).
    // Counter + log only — never repaired here. Process-local by design: a
    // fresh process starts at 0 and only reports against scopes it has
    // observed itself; writes are rare (one per hook install per process).
    private const val SCOPE_BIT_SYSTEM_SERVER = 1L
    private const val SCOPE_BIT_DAAGENT = 2L
    val scopeMergeLostUpdates = AtomicLong(0L)
    private val everSeenScopeBits = AtomicLong(0L)
    // CR-N4: process-lifetime cap for the "lost update suspected" line —
    // mirrors UNKNOWN_SCOPE_DROP_LOG_CAP so a pathological merge storm cannot
    // spam the log on every hook install. The counter always increments;
    // only the line is capped.
    private const val LOST_UPDATE_LOG_CAP = 4
    private val lostUpdateLogs = AtomicInteger(0)

    private fun scopeBits(scopes: Set<String>): Long {
        var bits = 0L
        for (scope in scopes) {
            when (scope) {
                SCOPE_SYSTEM_SERVER -> bits = bits or SCOPE_BIT_SYSTEM_SERVER
                SCOPE_DAAGENT -> bits = bits or SCOPE_BIT_DAAGENT
            }
        }
        return bits
    }

    // H11 note: timeMs is consumed by the app for its diagnostics surface
    // (HookStateProvider / DualViewModel read the marker's timeMs line), so
    // it is not dead code — kept for the boot-time staleness comparison the
    // docs already call out as future hardening.
    // Pure: extracts the timeMs line; absent/unparseable yields null.
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
            val merged = (parseScopes(readTextOrNull(markerFile, onLog, "primary"), onLog) +
                parseScopes(readTextOrNull(fallbackFile, onLog, "fallback"), onLog)).toMutableSet()
            if (want.isNotEmpty()) {
                // CR-O2/H12 parity: `want` must pass the same allowlist as the
                // on-disk tokens — an unknown scope can never be persisted
                // (fail-closed). Counted on the same drop channel; a single
                // safeLog per call (markActive is once-per-install) and the
                // unknown scope is skipped, never merged.
                if (ALLOWED_SCOPES.contains(want)) {
                    merged.add(want)
                } else {
                    unknownScopeDrops.incrementAndGet()
                    safeLog(onLog, "marker ignored unknown scope want='$want' (allowlist={system_server,daagent})")
                }
            }
            // H15: detect a previously-seen scope disappearing from the
            // merged view (concurrent writers, partial write, external
            // edit). Monotonic process-local union; log + counter only.
            val mergedBits = scopeBits(merged)
            val seen = everSeenScopeBits.getAndUpdate { it or mergedBits }
            if (seen != 0L && (mergedBits and seen) != seen) {
                scopeMergeLostUpdates.incrementAndGet()
                val n = lostUpdateLogs.incrementAndGet()
                if (n <= LOST_UPDATE_LOG_CAP) {
                    safeLog(
                        onLog,
                        "marker: previously recorded scope vanished across merges " +
                            "(lost update suspected), now writing=${merged.sorted().joinToString(",")}",
                    )
                }
            }
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
            safeLog(onLog, "marker write failed: ${HookLog.shortEx(t)}: ${HookLog.shortMsg(t)}")
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
            safeLog(onLog, "marker $which read failed, ignoring: ${HookLog.shortEx(e)}: ${HookLog.shortMsg(e)}")
            null
        }
    }

    // Best-effort single-file write: write to a sibling tmp file first, then
    // atomic rename over the target (H11) so a concurrent reader never
    // observes a half-written marker. If rename is unavailable/refused on a
    // given ROM, fall back to the old direct write (best effort, see header).
    // false on any failure, never throws.
    // CR-N3: a process that dies between the tmp write and the rename leaves
    // an orphan `*.tmp-*` sibling; delete at most ORPHAN_TMP_SWEEP_MAX such
    // siblings per call (older than ORPHAN_TMP_STALE_MS so a concurrent
    // in-flight write's tmp is never taken out from under its rename — a
    // young tmp is skipped). Enumeration failure or any per-file error is
    // swallowed: sweeping must never break the write.
    // Frozen-writer caveat (SIGSTOP / debugger / frozen >= ORPHAN_TMP_STALE_MS):
    // a writer stopped after it created its tmp but before the rename can see
    // its own tmp age past the staleness window and be swept by another
    // process's call. The rename then fails and the write falls back to a
    // non-atomic direct write of the target (or fails). Fail-soft: the target
    // file itself is never deleted or truncated by the sweep; at worst a
    // crash during that direct write leaves a torn marker, which readScopes
    // maps to "not detected" (fail-closed) — no target data loss.
    private const val ORPHAN_TMP_SWEEP_MAX = 16
    private const val ORPHAN_TMP_STALE_MS = 60_000L

    private fun tryWrite(file: File, content: String): Boolean {
        try {
            val prefix = file.name + ".tmp-"
            val siblings = file.parentFile?.listFiles { _, name -> name.startsWith(prefix) }
            if (siblings != null) {
                var swept = 0
                val now = System.currentTimeMillis()
                for (sibling in siblings) {
                    if (swept >= ORPHAN_TMP_SWEEP_MAX) break
                    try {
                        if (!sibling.isFile) continue
                        val mtime = sibling.lastModified()
                        if (mtime > 0L && now - mtime < ORPHAN_TMP_STALE_MS) continue
                        if (sibling.delete()) swept++
                    } catch (_: Throwable) {
                        // Best effort: keep sweeping.
                    }
                }
            }
        } catch (_: Throwable) {
            // Best effort: a sweep failure must never break the write.
        }
        // Unique tmp name per write: both hook processes write the same
        // primary file, and a shared tmp name would let one process rename
        // the other's half-written tmp over the target (lost update).
        val tmp: File? = try {
            file.parentFile?.let {
                File(it, file.name + ".tmp-" + System.nanoTime() + "-" + Thread.currentThread().id)
            }
        } catch (_: Exception) {
            null
        }
        return try {
            try {
                file.parentFile?.mkdirs()
            } catch (_: Exception) {
                // Best effort: parents normally exist.
            }
            if (tmp != null) {
                try {
                    tmp.writeText(content)
                    if (tmp.renameTo(file)) return true
                    // renameTo refused (filesystem/SELinux): fall through to
                    // the direct write with the tmp file removed below.
                } catch (_: Exception) {
                    // tmp path failed: fall through to the direct write.
                }
            }
            file.writeText(content)
            true
        } catch (_: Exception) {
            false
        } finally {
            if (tmp != null) {
                try {
                    if (tmp.exists()) tmp.delete()
                } catch (_: Throwable) {
                    // Best effort cleanup.
                }
            }
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
