// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase F4b-P2 (issue #6): app-side reader for the hook-active marker.
//
// DualHook (system_server + daagent processes) best-effort writes the marker
// (per-scope primary files /data/system/galaxydual_hook.active.{system_server,daagent},
// per-scope fallbacks /data/local/tmp/....{system_server,daagent}; legacy
// single-files /data/system/galaxydual_hook.active and
// /data/local/tmp/galaxydual_hook.active kept read-compat only) after
// installing hooks; this file reads it back into a HookState the LSPosed
// picker row displays as "active (scopes)" vs "not detected". No IPC, no new
// dependencies beyond the already-present CmdGateway abstraction: direct
// java.io.File reads first, then (only when a SHELL/ROOT channel is ready)
// `cat` over the gateway, plus the pure HookMarker parser shared with the
// writer (single source of truth for the format, so reader/writer cannot
// drift). All SIX paths are unioned everywhere: a scope recorded via any of
// the six files is honored (existence evidence, never a completeness claim).
//
// Path rationale (REAL-DEVICE evidence, Nov 2026): the first marker revision
// used only /data/local/tmp, but on-device system_server got EACCES writing
// there, so the writer moved to /data/system (system_data_file, writable by
// system_server) with /data/local/tmp kept as fallback. Phase 5 (issue #6)
// split the marker into per-scope files so the two hook processes never
// share a written file (no read-merge-write, no lock, no cross-process
// primitive); readers union all six paths (per-scope primary x2, per-scope
// fallback x2, then the two legacy single-file paths, in that fixed order).
//
// Read strategy (readHookState / readHookStateWithGateway, legacy two-state):
//  1. direct File reads of the six paths (most likely SELinux-denied for the app
//     uid; denial reads as absent, never as active);
//  2. only in readHookStateWithGateway, and only when `gateway` is non-null
//     with SHELL-or-ROOT privilege (i.e. the caller already proved the
//     channel: DualViewModel passes its active gateway only when channelReady
//     holds), `cat` of one file at a time over the gateway for all SIX
//     paths (a missing file exits cat non-zero, so one `cat a b` would die
//     whole -- each path is catted separately), unioned with the same
//     parser. Any gateway failure (non-zero exit, blank/
//     garbage output, throwing gateway, NONE privilege) maps to "not
//     detected", never to active, never throws.
//  Both legacy readers are DEPRECATED: they conflate unreadable / absent /
//  malformed (all inactive), so their INACTIVE must never be used as negative
//  evidence. Production (DualViewModel.probeChannels) reads via
//  diagnoseHookState below, which keeps malformed/unreadable as UNKNOWN.
//
// P1 diagnostic split (issue #6, 沿襲 #19, 配合 #27): the legacy functions
// above conflate "unreadable" with "absent" (both inactive), which makes an
// explicit-LSPosed selection -- whose NoOp gateway can never `cat` --
// permanently display "not detected" even when system_server wrote the
// marker (false negative: the only hook signal loses observability). The new
// diagnoseHookState below separates DIAGNOSTIC reading from WRITE-gateway
// readiness: it takes direct reads plus INDEPENDENT diagnostic gateways
// (probe-verified real channels; DualViewModel filters by hasRoot/hasShizuku
// so an LSPosed/NoOp SELECTION never blocks diagnosis) and returns a
// four-state HookDiagnosis -- ACTIVE / INACTIVE / UNKNOWN_UNREADABLE /
// UNKNOWN_NO_DIAGNOSTIC. Unknown is never displayed as "not detected".
// Diagnosis never feeds routing (hook signal excluded) or channelReady.
//
// Honesty (DEVICE-UNVERIFIED, pending real-device validation):
// - staleness: the marker survives reboots, so a stale file can read active
//   after the module was disabled until a hook process rewrites it. The UI
//   therefore presents this as existence+scope evidence, never as proof of a
//   live hook; a boot-time comparison stays future work (see HookMarker).
// - SELinux: the app uid may be unable to directly read any of the six paths on some
//   ROMs, and the gateway-`cat` round-trip has never run on a device either.
//   Legacy two-state reads (deprecated, see above) map unreadable/missing/
//   garbage to inactive ("not detected"), never to active; diagnoseHookState
//   instead reports unreadable/no-path/malformed as UNKNOWN (never "not
//   detected"). The LSPosed gateway stays the NoOp placeholder either way.
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.Privilege
import com.galaxydual.hook.HookMarker
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

// Snapshot of the hook-enable signal for one UI render.
data class HookState(
    // True only when the marker file parsed to a non-empty scope set.
    val active: Boolean = false,
    // Scopes recorded in the marker (subset of system_server/daagent).
    val scopes: Set<String> = emptySet(),
    // Raw marker text when the file was readable (diagnostics only).
    val raw: String? = null,
)

// Pure: maps marker text to state. Null/blank/garbage -> inactive (never a
// fake active). Covered by JVM unit tests (HookStateProviderTest).
fun parseHookState(raw: String?): HookState {
    val scopes = HookMarker.parseScopes(raw)
    return HookState(active = scopes.isNotEmpty(), scopes = scopes, raw = raw)
}

// The six marker paths every reader unions, in fixed read order: per-scope
// primary (system_server, daagent), per-scope fallback, then the two legacy
// single-file paths (read-compat forever). Order only matters for the
// diagnostics raw pick (first readable non-empty wins) — the scope union is
// order-independent.
// The six marker paths every reader unions (bound by reference to the hook
// module's single source of truth; order pinned by HookMarkerTest and
// HookStateProviderTest).
internal fun allHookMarkerFiles(): List<File> = HookMarker.DEFAULT_MARKER_FILES

// Diagnostics pick: the first readable raw (in the fixed six-file order)
// whose parse yields a non-empty scope set; falls back to the first non-null
// raw, then null. Used for the marker's timeMs line (hookMarkerTimeMs).
private fun firstScopesRaw(raws: List<String?>): String? =
    raws.firstOrNull { HookMarker.parseScopes(it).isNotEmpty() } ?: raws.firstOrNull { it != null }

// IO: direct File reads of all SIX marker paths (see allHookMarkerFiles),
// unioned via the shared parser. Missing, unreadable, or unparseable files
// yield inactive. Never throws: every failure mode maps to
// HookState(active=false), so callers need no catch.
// The markerFiles parameter exists so JVM tests can point at temp files;
// production always uses the default six. No gateway is consulted here:
// for the gateway-`cat` fallback see readHookStateWithGateway.
//
// DEPRECATED: inactive here conflates unreadable/absent/malformed (all map
// to "not detected"), so a returned inactive is NOT negative evidence.
// Production reads via diagnoseHookState; this stays only for legacy callers.
@Deprecated(
    message = "Legacy two-state read conflates unreadable/absent/malformed; " +
        "its inactive is not negative evidence. Use diagnoseHookState.",
)
fun readHookState(
    markerFiles: List<File> = allHookMarkerFiles(),
): HookState {
    return try {
        val raws = markerFiles.map { readTextOrNull(it) }
        if (raws.all { it == null }) {
            HookState(active = false)
        } else {
            val scopes = raws.fold(emptySet<String>()) { acc, raw -> acc + HookMarker.parseScopes(raw) }
            HookState(active = scopes.isNotEmpty(), scopes = scopes, raw = firstScopesRaw(raws))
        }
    } catch (_: Throwable) {
        HookState(active = false)
    }
}

// IO, suspend: full app-side read strategy -- direct File reads first (see
// readHookState), then, only when `gateway` is a proven SHELL/ROOT channel,
// `cat` of all SIX marker paths over that gateway (one `cat` per file: a
// missing file exits cat non-zero and would kill a combined command),
// unioned with the same parser. `gateway` is an OPTIONAL injection point
// defaulting to null, which behaves exactly like readHookState (old
// behavior); JVM tests inject a fake CmdGateway plus temp/nonexistent files
// and never touch real device paths (the gateway `cat`s always target the
// six production paths, mirroring the legacy two-file behavior).
// Production (DualViewModel.probeChannels) passes its active gateway only
// when channelReady holds, so an unproven/NoOp channel never executes `cat`.
// Never throws: unreadable output, non-zero exits, a throwing gateway, or a
// NONE-privilege gateway all map to inactive ("not detected").
//
// DEPRECATED: same conflation as readHookState -- inactive is not negative
// evidence. Production reads via diagnoseHookState; this stays only for
// legacy callers.
@Deprecated(
    message = "Legacy two-state read conflates unreadable/absent/malformed; " +
        "its inactive is not negative evidence. Use diagnoseHookState.",
)
suspend fun readHookStateWithGateway(
    gateway: CmdGateway? = null,
    markerFiles: List<File> = allHookMarkerFiles(),
): HookState {
    return try {
        val direct = readHookState(markerFiles)
        if (direct.active) return direct
        if (gateway == null ||
            (gateway.privilege != Privilege.SHELL && gateway.privilege != Privilege.ROOT)
        ) {
            return direct
        }
        // Marker paths are compile-time constants with a shell-safe charset
        // ([a-zA-Z0-9._/-], no spaces), so plain interpolation is safe; there
        // is deliberately no caller-controlled input in these commands.
        val raws = allHookMarkerFiles().map { catOrNull(gateway, it.path) }
        if (raws.all { it == null }) return direct
        val scopes = raws.fold(emptySet<String>()) { acc, raw -> acc + HookMarker.parseScopes(raw) }
        if (scopes.isEmpty()) return direct
        HookState(active = true, scopes = scopes, raw = firstScopesRaw(raws))
    } catch (_: Throwable) {
        HookState(active = false)
    }
}

// Null when the file is absent/unreadable (SELinux denial included); never
// throws. Callers pass the exact files to read (see allHookMarkerFiles), so
// tests control exactly which files are touched.
private fun readTextOrNull(file: File): String? {
    return try {
        if (!file.isFile) null else file.readText()
    } catch (_: Exception) {
        null
    }
}

// Runs `cat <absPath>` over the gateway; null on non-zero exit or any
// failure (including a throwing gateway). Never throws.
private suspend fun catOrNull(gateway: CmdGateway, absPath: String): String? {
    return try {
        val res = gateway.run("cat $absPath")
        if (res.exit != 0) null else res.out
    } catch (_: Throwable) {
        null
    }
}

// Pure UI text for a HookState (legacy two-state; see deprecation on the
// readers). ACTIVE keeps the same historical-marker wording as the
// diagnostic text: existence+scope evidence only, never proof of a live hook.
fun HookState.displayText(): String =
    if (active) {
        "偵測到歷史 Hook 標記（${scopes.sorted().joinToString(", ")}，僅存在性證據，未確認本次開機是否生效）"
    } else {
        "未偵測到"
    }

// P1 hook diagnosis state (issue #6, 配合 #27): ACTIVE needs a readable
// marker with a non-empty scope set; INACTIVE needs POSITIVE readable
// evidence of absence (every examined path positively checked, no scopes);
// UNKNOWN_UNREADABLE means a diagnostic path existed but could not be read
// (EACCES-style denial, failed `cat`); UNKNOWN_NO_DIAGNOSTIC means no usable
// diagnostic path existed at all (direct reads denied/missing AND no
// probe-verified real gateway to `cat` through). At minimum unknown is kept
// distinct from inactive so the UI never reports "not detected" for a signal
// it could not observe.
enum class HookStatus {
    ACTIVE,
    INACTIVE,
    UNKNOWN_UNREADABLE,
    UNKNOWN_NO_DIAGNOSTIC,
}

// Diagnosis snapshot for one UI render. `active` mirrors status == ACTIVE so
// legacy hookActive consumers keep working; `viaGateway` records whether the
// verdict came from a privileged `cat` (diagnostics only). Carries no
// Privilege and no readiness flag by construction: diagnosis output cannot
// grant privilege (hook-neutral, #19).
data class HookDiagnosis(
    val status: HookStatus,
    val scopes: Set<String> = emptySet(),
    val raw: String? = null,
    val viaGateway: Boolean = false,
) {
    val active: Boolean get() = status == HookStatus.ACTIVE
}

// One direct-read attempt: Readable(raw) means the check itself succeeded
// (raw == null: file positively absent; non-null: content as read, even
// blank/garbage which then parses to no scopes); Denied means the check
// failed (EACCES/SELinux denial included) so absence cannot be claimed. The
// injectable seam lets JVM tests simulate on-device denial without chmod.
sealed interface MarkerRead {
    data class Readable(val raw: String?) : MarkerRead
    object Denied : MarkerRead
}

// Default content reader for direct marker attempts: NIO newInputStream
// gives a precise NoSuchFileException vs AccessDeniedException split.
// (Files.readString would need Java 11 / core-library-desugaring, which this
// module does not enable; newInputStream has existed since API 26 and minSdk
// is 29, so no version branch is required.)
private fun readMarkerTextNio(file: File): String =
    Files.newInputStream(file.toPath()).use { it.readBytes().toString(Charsets.UTF_8) }

// IO: one direct attempt at a marker file. Read-first, never `isFile()`:
// `File.isFile()` returns false for genuinely missing files AND for
// EACCES/SELinux stat denial (un-traversable parent, denied stat), so it
// cannot prove absence -- the old `if (!file.isFile) Readable(null)` turned
// denial into "positively absent" and produced a false INACTIVE. Only
// NoSuchFileException proves absence -> Readable(null); AccessDenied /
// Security / other IO failures are unknown -> Denied (conservative). The
// `reader` seam (default: real NIO) lets JVM tests inject on-device denial
// without chmod, which same-uid CI and root would both ignore. `reader`
// implementations based on File.readText spell denial as
// FileNotFoundException("Permission denied"), indistinguishable from its
// missing spelling, so FileNotFoundException stays Denied. Never throws.
fun readDirectMarker(
    file: File,
    reader: (File) -> String = ::readMarkerTextNio,
): MarkerRead {
    return try {
        MarkerRead.Readable(reader(file))
    } catch (_: NoSuchFileException) {
        MarkerRead.Readable(null)
    } catch (_: FileNotFoundException) {
        MarkerRead.Denied
    } catch (_: AccessDeniedException) {
        MarkerRead.Denied
    } catch (_: SecurityException) {
        MarkerRead.Denied
    } catch (_: IOException) {
        MarkerRead.Denied
    } catch (_: Exception) {
        MarkerRead.Denied
    }
}

// Production direct reads of all SIX marker paths (per fixed order, see
// allHookMarkerFiles). The `reader` seam is threaded through for tests;
// production uses the default.
fun defaultDirectMarkerReads(
    reader: (File) -> String = ::readMarkerTextNio,
): List<MarkerRead> =
    allHookMarkerFiles().map { readDirectMarker(it, reader) }

// Malformed content (readable but unparseable): raw non-null, non-blank, yet
// parsing to no scopes ("garbage"). Malformed is NEITHER active NOR positive
// absence: it must not feed directComplete / gatewayFullyEmpty below, so a
// stale or foreign file never reads as "not detected". Read failures stay
// null (unchanged logic); blank content stays empty evidence (not malformed).
internal fun isMalformedMarker(raw: String?): Boolean =
    raw != null && raw.isNotBlank() && HookMarker.parseScopes(raw).isEmpty()

// IO, suspend: full diagnostic read -- direct reads first, then `cat` over
// each SHELL/ROOT-privilege diagnostic gateway (NONE gateways are never
// executed, mirroring the legacy contract). Diagnostic gateways are
// INDEPENDENT of the selected write channel: DualViewModel passes
// probe-verified real gateways even when LSPosed/NoOp is selected, so an
// explicit-LSPosed choice still diagnoses via any available true channel.
// Never throws: every failure mode maps to an UNKNOWN state, never to a
// fake active or a fake inactive.
//
// Decision order:
//  1. direct (or gateway) scopes non-empty -> ACTIVE (direct wins, no exec);
//  2. a privileged gateway positively read ALL SIX paths with no scopes and
//     no malformed content anywhere, or every direct path was positively
//     checked with no scopes and no gateway saw malformed content -> INACTIVE;
//  3. a gateway was attempted but nothing positively read -> UNKNOWN_UNREADABLE;
//  4. otherwise (direct denied/missing/malformed, no usable gateway) ->
//     UNKNOWN_NO_DIAGNOSTIC.
suspend fun diagnoseHookState(
    directReads: List<MarkerRead>,
    diagnosticGateways: List<CmdGateway> = emptyList(),
): HookDiagnosis {
    val directScopes = directReads
        .filterIsInstance<MarkerRead.Readable>()
        .fold(emptySet<String>()) { acc, read -> acc + HookMarker.parseScopes(read.raw) }
    if (directScopes.isNotEmpty()) {
        val raws = directReads.filterIsInstance<MarkerRead.Readable>().map { it.raw }
        return HookDiagnosis(HookStatus.ACTIVE, directScopes, firstScopesRaw(raws), viaGateway = false)
    }
    val directMalformed = directReads.any { it is MarkerRead.Readable && isMalformedMarker(it.raw) }
    val directComplete = directReads.isNotEmpty() && directReads.all { it is MarkerRead.Readable } && !directMalformed
    var attemptedGateway = false
    var gatewayFullyEmpty = false
    var gatewayMalformed = false
    for (gateway in diagnosticGateways) {
        if (gateway.privilege != Privilege.SHELL && gateway.privilege != Privilege.ROOT) continue
        attemptedGateway = true
        // One `cat` per path: a missing file exits cat non-zero and would
        // kill a combined `cat a b c`, so the six paths are catted
        // separately and each result arrives independently.
        val raws = allHookMarkerFiles().map { catOrNull(gateway, it.path) }
        if (raws.any { it != null }) {
            val scopes = raws.fold(emptySet<String>()) { acc, raw -> acc + HookMarker.parseScopes(raw) }
            if (scopes.isNotEmpty()) {
                return HookDiagnosis(HookStatus.ACTIVE, scopes, firstScopesRaw(raws), viaGateway = true)
            }
            if (raws.any { isMalformedMarker(it) }) {
                gatewayMalformed = true
            } else if (raws.all { it != null }) {
                gatewayFullyEmpty = true
            }
        }
    }
    // A partial gateway read (some path unreadable, no scopes) proves
    // nothing about the unread path: stay unknown, never claim inactive.
    // Malformed content anywhere likewise blocks an INACTIVE verdict: a
    // safely-read but unparseable file is not positive absence.
    if (gatewayFullyEmpty && !directMalformed) return HookDiagnosis(HookStatus.INACTIVE, viaGateway = true)
    // Positive direct evidence stands even when a gateway attempt failed: a
    // failed privileged retry cannot erase a completed direct check. A
    // malformed gateway read likewise blocks the INACTIVE verdict —
    // fail-closed: safely-read-but-unparseable is not positive absence
    // (same rule as the direct and gateway arms above).
    if (directComplete && !gatewayMalformed) return HookDiagnosis(HookStatus.INACTIVE)
    if (attemptedGateway) return HookDiagnosis(HookStatus.UNKNOWN_UNREADABLE, viaGateway = true)
    return HookDiagnosis(HookStatus.UNKNOWN_NO_DIAGNOSTIC)
}

// IO overload reading the real marker files for the direct stage. The
// `reader` seam is threaded through for tests; production uses the default.
suspend fun diagnoseHookState(
    markerFiles: List<File> = allHookMarkerFiles(),
    diagnosticGateways: List<CmdGateway> = emptyList(),
    reader: (File) -> String = ::readMarkerTextNio,
): HookDiagnosis = diagnoseHookState(
    markerFiles.map { readDirectMarker(it, reader) },
    diagnosticGateways,
)

// Pure UI text for a diagnosis. Unknown states NEVER render as "未偵測到"
// (reserved for positively-read absence); they name the limitation instead.
// ACTIVE is historical existence+scope evidence only (the marker survives
// reboots; S24 evidence shows it predating boot by ~5.5h): the text says so
// and never claims the current boot is hooked.
fun HookDiagnosis.displayText(): String = when (status) {
    HookStatus.ACTIVE -> "偵測到歷史 Hook 標記（${scopes.sorted().joinToString(", ")}，僅存在性證據，未確認本次開機是否生效）"
    HookStatus.INACTIVE -> "未偵測到"
    HookStatus.UNKNOWN_UNREADABLE -> "狀態未知（無權限讀取）"
    HookStatus.UNKNOWN_NO_DIAGNOSTIC -> "狀態未知（無可用診斷通道）"
}
