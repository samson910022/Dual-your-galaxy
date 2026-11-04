// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase F4b-P2 (issue #6): app-side reader for the hook-active marker.
//
// DualHook (system_server + daagent processes) best-effort writes the marker
// (primary /data/system/galaxydual_hook.active, fallback
// /data/local/tmp/galaxydual_hook.active) after installing hooks; this file
// reads it back into a HookState the LSPosed picker row displays as
// "active (scopes)" vs "not detected". No IPC, no new dependencies beyond the
// already-present CmdGateway abstraction: direct java.io.File reads first,
// then (only when a SHELL/ROOT channel is ready) `cat` over the gateway, plus
// the pure HookMarker parser shared with the writer (single source of truth
// for the format, so reader/writer cannot drift).
//
// Path rationale (REAL-DEVICE evidence, Nov 2026): the first marker revision
// used only /data/local/tmp, but on-device system_server got EACCES writing
// there, so the writer moved to /data/system (system_data_file, writable by
// system_server) with /data/local/tmp kept as fallback. Reads therefore cover
// BOTH paths (unioned): a scope recorded via either write path is honored,
// and the fixed order is primary-then-fallback everywhere.
//
// Read strategy (both readHookState and readHookStateWithGateway):
//  1. direct File reads of both paths (most likely SELinux-denied for the app
//     uid; denial reads as absent, never as active);
//  2. only in readHookStateWithGateway, and only when `gateway` is non-null
//     with SHELL-or-ROOT privilege (i.e. the caller already proved the
//     channel: DualViewModel passes its active gateway only when channelReady
//     holds), `cat <primary>` then `cat <fallback>` over the gateway, unioned
//     with the same parser. Any gateway failure (non-zero exit, blank/
//     garbage output, throwing gateway, NONE privilege) maps to "not
//     detected", never to active, never throws.
//
// Honesty (DEVICE-UNVERIFIED, pending real-device validation):
// - staleness: the marker survives reboots, so a stale file can read active
//   after the module was disabled until a hook process rewrites it. The UI
//   therefore presents this as existence+scope evidence, never as proof of a
//   live hook; a boot-time comparison stays future work (see HookMarker).
// - SELinux: the app uid may be unable to directly read either path on some
//   ROMs, and the gateway-`cat` round-trip has never run on a device either.
//   Unreadable/missing/garbage reads as inactive ("not detected"), never as
//   active. The LSPosed gateway stays the NoOp placeholder either way.
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.Privilege
import com.galaxydual.hook.HookMarker
import java.io.File

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

// IO: direct File reads of BOTH marker paths (primary then fallback),
// unioned via the shared parser. Missing, unreadable, or unparseable files
// yield inactive. Never throws: every failure mode maps to
// HookState(active=false), so callers need no catch.
// The markerFile/fallbackFile parameters exist so JVM tests can point at temp
// files; production always uses the defaults. No gateway is consulted here:
// for the gateway-`cat` fallback see readHookStateWithGateway.
fun readHookState(
    markerFile: File = File(HookMarker.MARKER_PATH),
    fallbackFile: File = File(HookMarker.LEGACY_MARKER_PATH),
): HookState {
    return try {
        val primaryRaw = readTextOrNull(markerFile)
        val fallbackRaw = readTextOrNull(fallbackFile)
        if (primaryRaw == null && fallbackRaw == null) {
            HookState(active = false)
        } else {
            val scopes = HookMarker.parseScopes(primaryRaw) + HookMarker.parseScopes(fallbackRaw)
            HookState(active = scopes.isNotEmpty(), scopes = scopes, raw = primaryRaw ?: fallbackRaw)
        }
    } catch (_: Throwable) {
        HookState(active = false)
    }
}

// IO, suspend: full app-side read strategy -- direct File reads first (see
// readHookState), then, only when `gateway` is a proven SHELL/ROOT channel,
// `cat` of both marker paths over that gateway, unioned with the same parser.
// `gateway` is an OPTIONAL injection point defaulting to null, which behaves
// exactly like readHookState (old behavior); JVM tests inject a fake
// CmdGateway plus temp/nonexistent files and never touch real device paths.
// Production (DualViewModel.probeChannels) passes its active gateway only
// when channelReady holds, so an unproven/NoOp channel never executes `cat`.
// Never throws: unreadable output, non-zero exits, a throwing gateway, or a
// NONE-privilege gateway all map to inactive ("not detected").
suspend fun readHookStateWithGateway(
    gateway: CmdGateway? = null,
    markerFile: File = File(HookMarker.MARKER_PATH),
    fallbackFile: File = File(HookMarker.LEGACY_MARKER_PATH),
): HookState {
    return try {
        val direct = readHookState(markerFile, fallbackFile)
        if (direct.active) return direct
        if (gateway == null ||
            (gateway.privilege != Privilege.SHELL && gateway.privilege != Privilege.ROOT)
        ) {
            return direct
        }
        // Marker paths are compile-time constants with a shell-safe charset
        // ([a-zA-Z0-9._/-], no spaces), so plain interpolation is safe; there
        // is deliberately no caller-controlled input in these commands.
        val primaryRaw = catOrNull(gateway, HookMarker.MARKER_PATH)
        val fallbackRaw = catOrNull(gateway, HookMarker.LEGACY_MARKER_PATH)
        if (primaryRaw == null && fallbackRaw == null) return direct
        val scopes = HookMarker.parseScopes(primaryRaw) + HookMarker.parseScopes(fallbackRaw)
        if (scopes.isEmpty()) return direct
        HookState(active = true, scopes = scopes, raw = primaryRaw ?: fallbackRaw)
    } catch (_: Throwable) {
        HookState(active = false)
    }
}

// Null when the file is absent/unreadable (SELinux denial included); never
// throws. markerFile/fallbackFile are explicit (no statics) so tests control
// exactly which files are touched.
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

// Pure UI text for the LSPosed picker row.
fun HookState.displayText(): String =
    if (active) {
        "已啟用（${scopes.sorted().joinToString(", ")}）"
    } else {
        "未偵測到"
    }
