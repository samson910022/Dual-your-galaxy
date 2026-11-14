// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// Phase F4b-P2 (issue #6): hook-active marker shared by the hook writer and
// the app reader.
//
// Cross-process signal WITHOUT any IPC dependency: after DualHook installs at
// least one hook in system_server (scope "system_server") or in the daagent
// process (scope "daagent"), it best-effort writes this marker file so the
// app UI can show "LSPosed Hook: active (scopes)" vs "not detected" (today the
// app renders four states — see app HookDiagnosis/displayText: absence-only
// yields 未偵測到, unreadable yields 狀態未知). The app
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
// Write semantics (renamed from the old fall-back-to-direct-write): a marker
// write is tmp-file + rename(2) only. If the rename is refused on a given
// ROM, the tmp is deleted, nothing else is written, and the write returns
// false — the marker is best-effort by contract. "Not detected" is a UI
// verdict that additionally requires positively-checked absence under the
// app's four-state rule (docs/HOOK.md §1.5): a missing marker with EACCES
// on every path must surface as 狀態未知, never 未偵測到. The old non-atomic
// direct write (which followed symlinks) no longer exists.
//
// Spoof surface (honesty note — tracked behavior, not a bug fix): the
// per-scope tmp fallback paths (LEGACY_MARKER_PATH_SYSTEM_SERVER /
// LEGACY_MARKER_PATH_DAAGENT) — used by the writer only after the primary
// /data/system write fails — are unioned by readers; the read-compat legacy
// single-file LEGACY_MARKER_PATH is never written, and is unioned by
// readers for read-compat only. /data/local/tmp is world-writable (1777),
// and which non-system SELinux domains may actually write there is
// ROM-dependent — the S24 evidence shows even system_server getting EACCES —
// so an unprivileged app MAY or MAY NOT be able to pre-create the paths on a
// given ROM, but nothing in this module makes it impossible. Any domain the
// ROM's policy lets write shell_data_file (e.g. shell/root, and on some ROMs
// ordinary apps) could pre-create those paths with planted
// `scope=system_server,daagent` content and flip the app's LSPosed picker row
// to "偵測到歷史 Hook 標記".
// The marker is EXISTENCE evidence only — a planted file proves no hook
// privilege and no hook installation. The reader union treats tmp-path
// positives identically to primary-path positives (no spoof resistance);
// the exposure is mitigated only by the existence-only UI wording and the
// docs caveat (docs/HOOK.md §1.5). Accepted and tracked, not fixable from
// the writer side.
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
//   file reads as inactive under the legacy two-state view (the four-state
//   diagnosis surfaces 狀態未知 instead), never as active. When a SHELL/ROOT channel
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
    // Bounded: at most MAX_MARKER_LINES lines are scanned; anything beyond
    // the line cap is dropped unread (a hostile planted file cannot make the
    // parser chew through megabytes of scope= lines). Legitimate markers
    // are two lines, so nothing real is lost.
    // H12: scope tokens are allowlisted — only {system_server, daagent} (the
    // only scopes this module ever writes) are honored; anything else is
    // dropped (fail-closed: an unknown token can never describe a scope) and
    // counted. At most UNKNOWN_SCOPE_DROP_LOG_CAP log lines per process;
    // pass onLog to observe drops, otherwise they are counted silently.
    // Defensive read/parse bounds (hostile or planted giant files must not
    // be buffered whole or scanned unbounded). MAX_MARKER_BYTES caps what
    // readScopes reads from any single marker file; anything beyond the cap
    // is dropped. MAX_MARKER_LINES caps how many lines parseScopes scans;
    // legitimate markers are exactly two lines (scope=..., timeMs=...), so
    // 16 is generous headroom, not a format constraint.
    private const val MAX_MARKER_BYTES = 8 * 1024
    private const val MAX_MARKER_LINES = 16

    private val ALLOWED_SCOPES = setOf(SCOPE_SYSTEM_SERVER, SCOPE_DAAGENT)
    private const val UNKNOWN_SCOPE_DROP_LOG_CAP = 4
    val unknownScopeDrops = AtomicLong(0L)
    private val unknownScopeDropLogs = AtomicInteger(0)

    fun parseScopes(raw: String?, onLog: ((String) -> Unit)? = null): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        var dropped = 0
        var lines = 0
        for (line in raw.lineSequence()) {
            lines++
            if (lines > MAX_MARKER_LINES) break
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
    // Bounded: at most MAX_MARKER_LINES lines are scanned (mirrors
    // parseScopes); anything beyond the cap is dropped unread. Legitimate
    // markers are two lines, so nothing real is lost.
    fun parseTimeMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        var lines = 0
        for (line in raw.lineSequence()) {
            lines++
            if (lines > MAX_MARKER_LINES) break
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
            if (primary != null && tryWrite(primary, content, onLog)) {
                safeLog(onLog, "marker wrote scope=$want")
                return true
            }
            safeLog(onLog, "marker primary write failed, trying fallback")
            if (fallback != null && tryWrite(fallback, content, onLog)) {
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
    // (legacy callers map the union to "not detected"; the four-state
    // diagnosis shows 狀態未知 instead). Reads the given files (default:
    // the six production paths — per-scope primary ×2, per-scope fallback ×2,
    // legacy single-file ×2) and returns the UNION, so a scope recorded via
    // any write path is honored. Garbage/unreadable files are ignored.
    // Never throws.
    // Bounded: each file is read at most MAX_MARKER_BYTES (8 KiB) via a bounded
    // stream read — a hostile or planted giant file is never buffered whole;
    // bytes past the cap are dropped. parseScopes additionally scans at most
    // MAX_MARKER_LINES (16) lines per file. Each file's read+parse is wrapped
    // individually: a non-regular file (FIFO/device/symlink, refused before
    // open by the no-follow gate in readTextOrNull) or an I/O error is skipped
    // and can never zero the union of the others. (A TOCTOU regular→FIFO swap
    // between the gate and open(2) can still block the read — see
    // readTextOrNull; a blocked read is not an exception.)
    fun readScopes(
        markerFiles: List<File> = DEFAULT_MARKER_FILES,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        for (file in markerFiles) {
            try {
                out += parseScopes(readTextOrNull(file))
            } catch (_: Throwable) {
                // Skip this file: a single failure never sinks the union.
            }
        }
        return out
    }

    // Null when the file is absent or unreadable (SELinux denial included);
    // never throws. Silent by design (readScopes/diagnose paths treat an
    // unreadable file as unreadable — legacy unions render it as "not
    // detected", the four-state diagnosis shows 狀態未知; never as active,
    // never as an error).
    // Bounded read: reads at most MAX_MARKER_BYTES + 1 bytes from the stream
    // via a plain read(2)-style loop (works on every Android API level; no
    // InputStream.readNBytes, which is only API 33+) and decodes only the
    // first MAX_MARKER_BYTES — the tail of a bigger file is dropped.
    private fun readTextOrNull(file: File): String? {
        return try {
            // No-follow regular-file gate BEFORE opening: a planted FIFO,
            // device node, or symlink at a marker path must not be opened
            // (a FIFO open(2) with no writer would block the read thread
            // forever). Folds the old File.isFile check into this single
            // NOFOLLOW_LINKS test, which is strictly stricter (symlinks to
            // regular files are now rejected too).
            // TOCTOU honesty note: a hostile swap (regular -> FIFO) between
            // this check and open(2) can still block the read. This narrows
            // the window but does NOT eliminate it — a blocked read is not
            // an exception, so readScopes' per-file catch cannot isolate it.
            // There is no O_NONBLOCK equivalent in pure JVM without native
            // calls. Best-effort narrowing, stated as such.
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            FileInputStream(file).use { input ->
                val buf = ByteArray(MAX_MARKER_BYTES + 1)
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, minOf(off, MAX_MARKER_BYTES), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    // Best-effort single-file write: write to a sibling tmp file first, then
    // atomic rename over the target (H11) so a concurrent reader never
    // observes a half-written marker. false on any failure, never throws.
    // If the rename is refused on a given ROM (filesystem/SELinux — e.g. the
    // sticky-bit EPERM when the fallback path is a pre-planted attacker-owned
    // symlink in the 1777 /data/local/tmp), there is NO fall-through to a
    // direct write: the tmp is deleted, the refusal is logged, and false is
    // returned. rename-refusal ⇒ marker unwritten. The marker is
    // best-effort by contract; an unwritten marker can surface as 未偵測到
    // only under positively-checked absence (docs/HOOK.md §1.5 four-state
    // rule — EACCES paths show 狀態未知 instead). Never worse than the
    // on-device EACCES reality. The former non-atomic direct write (file.writeText, which
    // follows symlinks and would truncate the symlink target with
    // system_server/daagent privileges) no longer exists.
    // CR-N3: a process that dies between the tmp write and the rename leaves
    // an orphan `*.tmp-*` sibling; delete at most ORPHAN_TMP_SWEEP_MAX such
    // siblings per call (older than ORPHAN_TMP_STALE_MS so a concurrent
    // in-flight write's tmp is never taken out from under its rename — a
    // young tmp is skipped). A sibling whose mtime cannot be determined
    // (lastModified() == 0, stat failure) is also skipped: unknown age is
    // treated as fresh, since deleting on a failed stat could unlink a
    // peer's in-flight tmp. Only a known-age tmp past the staleness window
    // is swept. Enumeration failure or any per-file error is
    // swallowed: sweeping must never break the write.
    // Pre-planted tmp: the tmp is created with createNewFile() (atomic
    // exists-or-creates) as the ONLY gate before writing — a tmp path that
    // already exists (including a pre-planted symlink) is never opened or
    // followed; the write is refused, logged, and the marker stays unwritten
    // (fail-closed).
    // Frozen-writer caveat (SIGSTOP / debugger / frozen >= ORPHAN_TMP_STALE_MS):
    // a writer stopped after it created its tmp but before the rename can see
    // its own tmp age past the staleness window and be swept by another
    // process's call. The rename then fails and the write is REFUSED (no
    // direct-write fallback — see above): the marker is unwritten and reads
    // stays unwritten (absence must be positively verified before anything
    // other than 狀態未知 is displayed — four-state rule). Fail-soft: the target file itself is
    // never deleted or truncated by the sweep — no target data loss.
    private const val ORPHAN_TMP_SWEEP_MAX = 16
    private const val ORPHAN_TMP_STALE_MS = 60_000L

    private fun tryWrite(file: File, content: String, onLog: (String) -> Unit = {}): Boolean {
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
                        // lastModified() returns 0 on stat failure. mtime <= 0L
                        // (stat failure / unknown) is treated as FRESH and
                        // skipped — never unlink a peer's in-flight tmp on a
                        // failed stat. Only a known-age tmp is eligible:
                        // mtime > 0L && now - mtime >= ORPHAN_TMP_STALE_MS.
                        // (now - mtime < 0, a future mtime, is skipped too.)
                        val mtime = sibling.lastModified()
                        if (mtime <= 0L) continue
                        if (now - mtime < ORPHAN_TMP_STALE_MS) continue
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
                // Single atomic exists-or-creates gate BEFORE any write:
                // createNewFile() returns false if the tmp path already
                // exists — including a pre-planted symlink (the symlink
                // itself exists, so it is never opened or followed) — and
                // atomically creates a fresh regular file otherwise. No
                // exists() fast path: createNewFile() IS the TOCTOU-free
                // check-and-create, so only the file this call just created
                // is ever opened for writing. An attacker who plants the
                // tmp between allocation and here wins nothing: the write
                // is refused (fail-closed), never redirected.
                val created = try {
                    tmp.createNewFile()
                } catch (_: Throwable) {
                    false
                }
                if (!created) {
                    safeLog(onLog, "tmp create refused for ${file.name} (pre-existing or uncreatable tmp path); marker unwritten (fail-closed)")
                    return false
                }
                try {
                    tmp.writeText(content)
                } catch (t: Exception) {
                    // Tmp path failed: NO fall-through to a direct write
                    // (rename-refusal semantics — see KDoc above).
                    safeLog(onLog, "tmp write failed for ${file.name} (${HookLog.shortEx(t)}); marker unwritten (fail-closed)")
                    return false
                }
                if (tmp.renameTo(file)) return true
                // renameTo refused (filesystem/SELinux — e.g. sticky-bit EPERM
                // on a 1777 world-writable dir with a pre-planted
                // attacker-owned symlink at the fallback path): do NOT fall
                // through to file.writeText — that follows symlinks and would
                // TRUNCATE the symlink target with system_server/daagent
                // privileges. Fail closed instead: delete the tmp (finally
                // block), log, return false. The marker is best-effort; a
                // missing marker can surface as 未偵測到 only under
                // positively-verified absence (four-state rule).
                safeLog(onLog, "rename refused for ${file.name}; marker unwritten (fail-closed)")
                return false
            }
            // No tmp sibling could be allocated: without the atomic rename
            // path there is no write this contract allows.
            safeLog(onLog, "no tmp sibling for ${file.name}; marker unwritten (fail-closed)")
            return false
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
