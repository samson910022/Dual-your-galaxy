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
// system_data_file) — verified writable for the system_server domain on S24
// only; other ROMs are unverified. The daagent per-scope paths live in the
// SAME two contexts, and daagent got EACCES on BOTH legacy paths on S24
// (docs/HOOK.md §1.5), so daagent marker writability is entirely unverified
// (likely EACCES): a missing daagent marker proves nothing about the daagent
// hook — lspd logs remain the daagent install evidence. The old
// /data/local/tmp path is kept as a FALLBACK write target (and read source)
// so a ROM where /data/system is unexpectedly unwritable can still record
// the signal. Phase 5: that fallback is now a per-scope tmp file; the
// legacy single-file tmp path is never written again. Writer order is fixed:
// primary first, fallback only when the primary write fails. Reader order is
// fixed the same way, and reads UNION all six files.
//
// Per-scope files (Phase 5, issue #6): each hook process writes ONLY its own
// per-scope file (tmp->rename atomic, same as before); concurrent writers
// cannot clobber each other because no file is shared for writing (no read,
// no merge, no lock, no cross-process primitive). The app unions all marker
// files, so a scope recorded by either process survives regardless of the
// other. The legacy single-file markers (MARKER_PATH / LEGACY_MARKER_PATH)
// are read-compat ONLY: the writer NEVER writes them anymore; they stay
// readable forever because existing installs may have been written by
// previous module versions.
//
// Honesty limits (DEVICE-UNVERIFIED, pending real-device validation):
// - staleness: the file SURVIVES REBOOTS. After a reboot (or after the user
//   disables the module in the LSPosed manager) the stale file still claims
//   "active" until a hook process rewrites it. The app therefore treats the
//   marker as existence + scope evidence only, and labels it as such in the
//   UI. A boot-time comparison (marker timeMs vs device boot time) would
//   harden this, but the right clock source needs on-device verification, so
//   it is deliberately NOT implemented here (no fake freshness).
// - SELinux: whether the app uid can directly read any of the paths on a
//   given ROM is unverified (a direct read is likely denied); an unreadable
//   file reads as "not detected", never as active. When a SHELL/ROOT channel
//   is ready the app additionally retries via `cat` over the gateway (see
//   HookStateProvider.readHookStateWithGateway); that path is equally
//   unverified on-device.
// - scope union: readers see the UNION of the per-scope files plus any
//   legacy single-file content; no scope-completeness claim is made (a
//   missing per-scope file proves nothing about the peer process).
// - deletion remains possible: any file can vanish at any time (reboot is
//   not required); the marker is existence evidence only.
//
// Framework-free (no android.* / LibXposed types): build/parse/mark run as
// plain JVM unit tests (hook/src/test HookMarkerTest).
object HookMarker {
    // LEGACY single-file marker path (read-compat ONLY): written by previous
    // module versions (pre-Phase-5). The writer NEVER writes this path
    // anymore; it stays readable forever because existing installs may have
    // been written by those versions. Do not re-add a writer for it.
    const val MARKER_PATH = "/data/system/galaxydual_hook.active"

    // LEGACY tmp fallback single-file marker (read-compat ONLY): same
    // read-forever contract as MARKER_PATH; the writer never writes it.
    const val LEGACY_MARKER_PATH = "/data/local/tmp/galaxydual_hook.active"

    // Per-scope marker path for the system_server hook process (primary,
    // /data/system labeled system_data_file -- see header).
    const val MARKER_PATH_SYSTEM_SERVER = "/data/system/galaxydual_hook.active.system_server"

    // Per-scope marker path for the daagent hook process (primary).
    const val MARKER_PATH_DAAGENT = "/data/system/galaxydual_hook.active.daagent"

    // Per-scope FALLBACK path for system_server: tried only when the primary
    // per-scope write fails (a ROM where /data/system is unexpectedly
    // unwritable can still record the signal).
    const val LEGACY_MARKER_PATH_SYSTEM_SERVER = "/data/local/tmp/galaxydual_hook.active.system_server"

    // Per-scope FALLBACK path for daagent.
    const val LEGACY_MARKER_PATH_DAAGENT = "/data/local/tmp/galaxydual_hook.active.daagent"

    // Per-scope path resolution for the writer: allowlist-checked, unknown
    // scope -> null. Pure, never throws.
    fun markerPathFor(scope: String): String? = when (scope.trim()) {
        SCOPE_SYSTEM_SERVER -> MARKER_PATH_SYSTEM_SERVER
        SCOPE_DAAGENT -> MARKER_PATH_DAAGENT
        else -> null
    }

    // Per-scope FALLBACK path resolution: allowlist-checked, unknown scope ->
    // null. Pure, never throws.
    fun fallbackMarkerPathFor(scope: String): String? = when (scope.trim()) {
        SCOPE_SYSTEM_SERVER -> LEGACY_MARKER_PATH_SYSTEM_SERVER
        SCOPE_DAAGENT -> LEGACY_MARKER_PATH_DAAGENT
        else -> null
    }

    // The six marker paths every reader unions, in fixed read order:
    // per-scope primary (system_server, daagent), per-scope fallback, then
    // the two legacy single-file paths (read-compat forever). Public: the
    // app module (HookStateProvider / DiagnoseFreshness) unions the same
    // list via allHookMarkerFiles() — the single source of truth; do not
    // copy it.
    val DEFAULT_MARKER_FILES: List<File> = listOf(
        File(MARKER_PATH_SYSTEM_SERVER),
        File(MARKER_PATH_DAAGENT),
        File(LEGACY_MARKER_PATH_SYSTEM_SERVER),
        File(LEGACY_MARKER_PATH_DAAGENT),
        File(MARKER_PATH),
        File(LEGACY_MARKER_PATH),
    )

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

    // Best-effort per-scope write: records ONLY [scope], stamped with nowMs,
    // into the scope's own marker file (no read, no merge — each hook
    // process owns its file, so concurrent writers cannot clobber each
    // other). Write order is fixed: primary first, fallback only when the
    // primary write fails. Returns true only when one of the files was
    // written. NEVER throws (any Throwable -> onLog + false): the hook path
    // must survive even when both paths are unwritable on a given ROM.
    //
    // markerFile/nowMs are parameters (not statics) so JVM tests can point
    // at a temp dir; production passes the defaults. fallbackFile trails
    // onLog (rather than sitting next to markerFile) so existing positional
    // call sites keep compiling with identical semantics. Path selection:
    // explicit markerFile/fallbackFile > allowlist-derived per-scope default
    // > fail — except that an explicit markerFile WITHOUT an explicit
    // fallbackFile targets NO fallback (never silently the production tmp
    // path, which would make JVM-injected tests write real host paths).
    // The scope allowlist is enforced FIRST: an unknown scope is
    // never persisted (fail-closed), is counted on the drop channel, and no
    // file is written.
    fun markActive(
        scope: String,
        markerFile: File? = null,
        nowMs: Long = System.currentTimeMillis(),
        onLog: (String) -> Unit = {},
        fallbackFile: File? = null,
    ): Boolean {
        try {
            val want = scope.trim()
            // CR-O2/H12 parity: `want` must pass the same allowlist as the
            // on-disk tokens — an unknown scope can never be persisted
            // (fail-closed). Counted on the same drop channel; a single
            // safeLog per call (markActive is once-per-install) and the
            // unknown scope is never written.
            if (!ALLOWED_SCOPES.contains(want)) {
                unknownScopeDrops.incrementAndGet()
                safeLog(onLog, "marker ignored unknown scope want='$want' (allowlist={system_server,daagent})")
                return false
            }
            val primary = markerFile ?: markerPathFor(want)?.let { File(it) }
            // explicit primary without explicit fallback => no fallback target
            // (never the production tmp path by accident).
            val fallback = fallbackFile
                ?: if (markerFile == null) fallbackMarkerPathFor(want)?.let { File(it) } else null
            val content = buildContent(setOf(want), nowMs)
            if (primary != null && tryWrite(primary, content)) {
                safeLog(onLog, "marker wrote scope=$want")
                return true
            }
            safeLog(onLog, "marker primary write failed, trying fallback")
            if (fallback != null && tryWrite(fallback, content)) {
                safeLog(onLog, "marker wrote fallback scope=$want")
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
    // (caller maps that to "not detected"). Reads the given files (default:
    // the six production paths — per-scope primary ×2, per-scope fallback ×2,
    // legacy single-file ×2) and returns the UNION, so a scope recorded via
    // any write path is honored. Garbage/unreadable files are ignored.
    // Never throws.
    fun readScopes(
        markerFiles: List<File> = DEFAULT_MARKER_FILES,
    ): Set<String> {
        return try {
            val out = LinkedHashSet<String>()
            for (file in markerFiles) {
                out += parseScopes(readTextOrNull(file))
            }
            out
        } catch (_: Throwable) {
            emptySet()
        }
    }

    // Null when the file is absent or unreadable (SELinux denial included);
    // never throws. Silent by design (readScopes/diagnose paths treat an
    // unreadable file as "not detected", never as active, never as an error).
    private fun readTextOrNull(file: File): String? {
        return try {
            if (!file.isFile) null else file.readText()
        } catch (_: Exception) {
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
        // Unique tmp name per write: a shared tmp name would let one writer
        // rename another writer's half-written tmp over the target. (Each
        // hook process now writes its own per-scope file, but the tmp name is
        // unique regardless, so a stray tmp can never alias a peer's write.)
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
