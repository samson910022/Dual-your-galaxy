// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase F4b-P1 (issue #6): channel-selection model, persistence, and dynamic
// re-selection assembly. Phase F4b-P2 adds the picker UI (MainActivity) and
// the hook-enable display (HookStateProvider); the LSPosed gateway mapping
// below is UNCHANGED (still the NoOp placeholder).
//
// Design:
// - ChannelPreference has exactly four values (AUTO / ROOT / SHIZUKU /
//   LSPOSED). NoOp is only the fail-closed fallback gateway, never a menu
//   entry, so it is NOT a preference value.
// - Persistence is framework SharedPreferences (zero new dependencies):
//   explicit prefs file + key, default AUTO, unknown/blank stored values
//   fall back to AUTO. The string mapping is pure Kotlin so JVM unit tests
//   cover it; only load/save touch the Android stub.
// - Auto semantics are probe-based after the first probe (P25): the startup
//   pick keeps the F4a file heuristic (su presence first, then Shizuku auth,
//   else NoOp) so the factory stays I/O-free, but DualViewModel degrades that
//   pick once elevator probes report actual capability (see resolveAutoProbe):
//   su present but probe-denied + Shizuku proven -> SHIZUKU with a typed
//   degrade (WriteBlock); both probes failing -> NOOP read-only (typed);
//   both succeeding -> ROOT (null degrade).
//   The hook channel is never auto-selected (conservative continuation:
//   the marker signal is display-only evidence, see HookMarker, and must
//   never grant privilege without on-device verification).
// - LSPOSED explicitly selected resolves to LSPOSED_PLACEHOLDER, which still
//   builds a NoOp gateway (the whitelist policy source is wired, end-to-end
//   DEVICE-UNVERIFIED, but the LSPosed execution channel remains a NoOp
//   placeholder -- writes refused with the reason stated, never executed).
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.NoOpGateway

// User-visible channel choice. Exactly four values; NoOp stays a fallback
// only and never enters the picker menu (P2).
enum class ChannelPreference {
    AUTO,
    ROOT,
    SHIZUKU,
    LSPOSED,
}

// Gateway actually built for a preference after Auto resolution.
// - NOOP is the fail-closed read-only fallback gateway (never a menu entry).
// - LSPOSED_PLACEHOLDER is the explicit-LSPosed resolution: intentionally a
//   NoOp as well (not a second gateway kind). Both build NoOpGateway today;
//   they differ only in reason metadata (placeholder vs no-channel), so the
//   ViewModel can refuse writes with the accurate copy. See buildGatewayForChannel.
enum class ResolvedChannel {
    ROOT,
    SHIZUKU,
    NOOP,
    // Explicit LSPosed choice until the P2 hook channel lands.
    LSPOSED_PLACEHOLDER,
}

// Explicit SharedPreferences file + key (default AUTO). Both constants are
// part of the persistence contract: renaming either orphans stored choices.
const val CHANNEL_PREFS_NAME = "dual_channel_prefs"
const val CHANNEL_PREF_KEY = "channel_preference"

// Stable stored form (lowercase literals, never the enum name directly, so a
// future rename/reorder cannot orphan stored choices).
fun ChannelPreference.toStored(): String = when (this) {
    ChannelPreference.AUTO -> "auto"
    ChannelPreference.ROOT -> "root"
    ChannelPreference.SHIZUKU -> "shizuku"
    ChannelPreference.LSPOSED -> "lsposed"
}

// Parses a stored value; null/blank/unknown (including the legacy "noop" or
// any future value) falls back to AUTO. Pure: covered by JVM unit tests.
fun channelPreferenceFromStored(raw: String?): ChannelPreference =
    when (raw?.trim()?.lowercase()) {
        "root" -> ChannelPreference.ROOT
        "shizuku" -> ChannelPreference.SHIZUKU
        "lsposed" -> ChannelPreference.LSPOSED
        else -> ChannelPreference.AUTO
    }

// Reads the persisted choice (default AUTO). Thin wrapper over the pure
// mapping above; never called on JVM tests (Android stub would throw).
// Corrupt-data policy: a stored value of the wrong type (or any other read
// failure, including Error) is treated as unrecoverable and falls back to
// AUTO (fail-closed), rather than propagating ClassCastException to the
// caller. This keeps a mistyped platform entry from crashing startup; the
// entry itself is left untouched (no self-clear) because the type cannot be
// safely rewritten here. Phase7: catches Throwable (hostile prefs impl may
// throw Error); CancellationException is re-thrown (cancellation is never
// swallowed as AUTO).
fun loadChannelPreference(prefs: SharedPreferences): ChannelPreference =
    try {
        channelPreferenceFromStored(prefs.getString(CHANNEL_PREF_KEY, null))
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (_: Throwable) {
        ChannelPreference.AUTO
    }

// Persists the choice so a process restart remembers it. Async apply: the
// in-memory preference is already updated by the caller.
// Durability note: apply() updates in-memory state synchronously but flushes
// to disk asynchronously, so a process killed in the flush window reverts to
// the previous on-disk value on next start. This is an accepted best-effort
// trade-off for a UI preference; see SelectionStore for the synchronous
// commit() rationale used where restart-safety is required.
fun SharedPreferences.saveChannelPreference(pref: ChannelPreference) {
    edit().putString(CHANNEL_PREF_KEY, pref.toStored()).apply()
}

// Resolves a preference to the gateway channel to build. Pure: covered by
// JVM unit tests.
// - AUTO (startup heuristic): su presence first, then Shizuku authorized,
//   else NOOP (F4a order, hook never auto-selected). This stays I/O-free for
//   the factory; the probe-based correction lives in resolveAutoProbe below
//   and is applied by DualViewModel once elevator probes report.
// - Explicit ROOT/SHIZUKU win regardless of probes (probes fail closed
//   later when the channel is actually unusable).
// - Explicit LSPOSED is a placeholder until P2 (hook enable detection).
fun ChannelPreference.resolveChannel(suPresent: Boolean, shizukuAuthorized: Boolean): ResolvedChannel =
    when (this) {
        ChannelPreference.ROOT -> ResolvedChannel.ROOT
        ChannelPreference.SHIZUKU -> ResolvedChannel.SHIZUKU
        ChannelPreference.LSPOSED -> ResolvedChannel.LSPOSED_PLACEHOLDER
        ChannelPreference.AUTO -> when {
            suPresent -> ResolvedChannel.ROOT
            shizukuAuthorized -> ResolvedChannel.SHIZUKU
            else -> ResolvedChannel.NOOP
        }
    }

// Probe-based Auto decision (P25, issue #25): corrects the startup file
// heuristic once elevator probes report actual capability. Pure: covered by
// AutoDegradeTest.
// - hasRoot true -> ROOT (su first; covers both-probes-succeed: follows the
//   F4a preference order, no degrade note).
// - else hasShizuku true -> SHIZUKU; when suPresent is also true the su file
//   exists but its probe was denied, so this is a degrade and the typed reason
//   (WriteBlock) records it plus the recovery entry via resources.
// - else NOOP read-only with a typed reason stating both probes failed plus
//   recovery (via resources).
// F7 semantics (locked by AutoDegradeTest): the typed reason records DOWNWARD
// overrides only. Recovery/upgrade back to ROOT (hasRoot true) is silent by
// design (null) -- a proven ROOT channel needs no explanation.
// Explicit preferences never reach here (the ViewModel only calls this for
// AUTO): explicit choices never cross channels, they fail closed with their
// own message instead.
data class AutoProbeDecision(
    val channel: ResolvedChannel,
    val degrade: WriteBlock? = null,
)

fun resolveAutoProbe(
    suPresent: Boolean,
    hasRoot: Boolean,
    hasShizuku: Boolean,
): AutoProbeDecision = when {
    hasRoot -> AutoProbeDecision(ResolvedChannel.ROOT, null)
    hasShizuku -> if (suPresent) {
        AutoProbeDecision(
            ResolvedChannel.SHIZUKU,
            WriteBlock(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU),
        )
    } else {
        AutoProbeDecision(ResolvedChannel.SHIZUKU, null)
    }
    else -> if (suPresent) {
        AutoProbeDecision(
            ResolvedChannel.NOOP,
            WriteBlock(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED),
        )
    } else {
        AutoProbeDecision(
            ResolvedChannel.NOOP,
            WriteBlock(WriteBlockKind.AUTO_DEGRADE_NO_CHANNEL),
        )
    }
}

// Builds the gateway for a resolved channel. Constructor-only, no I/O, so
// JVM-constructible (same pattern as GatewayAssemblyTest). LSPosed builds
// NoOp by policy (the whitelist policy source is wired, end-to-end
// DEVICE-UNVERIFIED, but the LSPosed execution channel remains a NoOp
// placeholder, F4b-P2): the placeholder must never be mistaken for an
// executable channel -- DualViewModel refuses writes under it with the reason
// stated.
// Intentionally NoOp (no hook-channel TODO kept): returning a real LSPosed
// hook gateway for LSPOSED_PLACEHOLDER requires on-device verification of the
// execution channel (see docs/HOOK.md); until then this stays NoOp by design,
// not by omission.
fun buildGatewayForChannel(channel: ResolvedChannel, packageName: String): CmdGateway =
    when (channel) {
        ResolvedChannel.ROOT -> SuCmdGateway()
        ResolvedChannel.SHIZUKU -> ShizukuCmdGateway(packageName)
        ResolvedChannel.NOOP -> NoOpGateway()
        ResolvedChannel.LSPOSED_PLACEHOLDER -> NoOpGateway()
    }

// One assembly: the selected gateway plus the fresh su/Shizuku instances
// backing the probes. The selected gateway SHARES its instance with the
// matching elevator gateway (ROOT -> suGateway, SHIZUKU -> shizukuGateway)
// so probes verify the actual channel the repo uses. Typed as CmdGateway
// (not the concrete Su/Shizuku types) so JVM tests can inject fake-backed
// assemblies through DualViewModel's assembleChannelFn seam; production
// still builds the real gateways below.
data class ChannelAssembly(
    val gateway: CmdGateway,
    val suGateway: CmdGateway,
    val shizukuGateway: CmdGateway,
    val channel: ResolvedChannel,
)

// Single source of truth for preference -> live gateways. Used by both the
// startup factory and DualViewModel.selectChannel, so Auto order and the
// LSPosed placeholder can never drift between the two paths.
fun assembleChannel(
    preference: ChannelPreference,
    suPresent: Boolean,
    shizukuAuthorized: Boolean,
    packageName: String,
): ChannelAssembly {
    val suGateway = SuCmdGateway()
    val shizukuGateway = ShizukuCmdGateway(packageName)
    val channel = preference.resolveChannel(suPresent, shizukuAuthorized)
    val gateway: CmdGateway = when (channel) {
        ResolvedChannel.ROOT -> suGateway
        ResolvedChannel.SHIZUKU -> shizukuGateway
        // F4b-P2: LSPOSED_PLACEHOLDER still builds NoOp (read-only); the
        // whitelist policy source is wired (end-to-end DEVICE-UNVERIFIED) but
        // the LSPosed execution channel remains an intentionally-NoOp
        // placeholder (see buildGatewayForChannel above).
        ResolvedChannel.NOOP,
        ResolvedChannel.LSPOSED_PLACEHOLDER,
        -> NoOpGateway()
    }
    return ChannelAssembly(gateway, suGateway, shizukuGateway, channel)
}
