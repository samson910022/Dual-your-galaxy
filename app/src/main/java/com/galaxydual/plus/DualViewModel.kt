// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.DualProfile
import com.galaxydual.core.DualRepository
import com.galaxydual.core.Elevator
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.PmOutputParser
import com.galaxydual.core.Privilege
import com.galaxydual.core.ProfilesSnapshot
import com.galaxydual.core.RemoveOutcome
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import com.galaxydual.core.channelReadyFor
import com.galaxydual.core.routePrivilege
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Error class of the user-facing message, so the UI (F3) can render channel
// failures differently from validation refusals or verify mismatches.
enum class MsgKind {
    // No message to show.
    NONE,

    // Success or neutral status ("cloned: x", "no clones installed").
    INFO,

    // Invalid input or refused write (bad package token, read-only channel).
    INVALID,

    // Channel answered but reports no single DUAL profile.
    NO_DUAL,

    // Post-write re-check disagrees (fake success / still installed).
    VERIFY,

    // Channel itself failed, the result is unknowable, or the selected
    // channel is not ready (F4a wired: gateway picked at startup, probe not
    // passed / NoOp fallback stays read-only).
    CHANNEL,

    // The install/uninstall command reported failure, or an unexpected
    // exception escaped the gateway (gateways should report via CmdResult).
    FAILED,
}

// UI state for the dual-app MVP screen; plain data with no Android types
// except the core Privilege enum. One column per concern: privilege shows the
// routed elevation decision, profiles/cloned the device ground truth, loading
// the in-flight flag, and msg (+msgKind) the user-facing status.
data class UiState(
    // Routed elevation level from the elevator probes (ROOT/SHELL/NONE).
    val privilege: Privilege = Privilege.NONE,
    // Selected channel preference (F4b-P1 model, P2 picker UI in
    // MainActivity). NoOp is never a value here, only the fail-closed
    // fallback gateway.
    val channel: ChannelPreference = ChannelPreference.AUTO,
    // False until the selected real gateway's own probe succeeds (F4a): a
    // su-backed repo needs the su probe (ROOT), a Shizuku-backed repo the
    // Shizuku probe (SHELL); a NoOp repo (NONE) is never ready. Fail closed.
    val channelReady: Boolean = false,
    // Hook-enable signal from the cross-process marker file (F4b-P2).
    // Refreshed on every probe (recheckPrivilege/selectChannel cover all UI
    // entry points). Honest existence+scope evidence only: the marker
    // survives reboots, so active here means "marker present", not proof of
    // a live hook (see HookMarker/HookStateProvider headers).
    val hookActive: Boolean = false,
    // Scopes recorded in the marker (subset of system_server/daagent).
    val hookScopes: Set<String> = emptySet(),
    // All Android profiles parsed from `pm list users`.
    val profiles: List<DualProfile> = emptyList(),
    // Packages currently cloned into the dual profile (MVP tracks by name).
    val cloned: List<String> = emptyList(),
    // True while a repository call is in flight; UI should show progress.
    val loading: Boolean = false,
    // Last user-facing status or error message; empty means no message.
    val msg: String = "",
    // Error class of msg; NONE when msg is empty.
    val msgKind: MsgKind = MsgKind.NONE,
)

// Android ViewModel owning the dual-app screen state (Phase F2, issue #6).
// All shell work is delegated to DualRepository; this class only owns state.
//
// Phase C3 (issue #9): clone/remove go through the repository's pinned flows
// (cloneVerified/removeVerified: one snapshot, install/uninstall and re-check
// on the same dual id), and refresh rebuilds `cloned` from the device ground
// truth (`pm list packages --user <dualId>`) instead of trusting local state.
// Only a verify-confirmed install is added to `cloned`; only a
// verify-confirmed removal drops it.
//
// Concurrency (core review finding): refresh/clone/remove are serialized
// through opMutex so concurrent calls queue instead of racing
// last-writer-wins on `cloned`/`loading`. Public entry points are plain
// functions launching in viewModelScope; the Activity just calls them.
//
// Phase F4b-P1 (issue #6): selectChannel(pref) swaps the gateway under the
// same mutex (persist + rebuild via assembleChannel + re-probe + refresh),
// so a later environment no longer disagrees with the startup pick, and
// recheckPrivilege() re-resolves AUTO the same way.
//
// Phase F4b-P2 (issue #6): the picker UI (MainActivity) calls selectChannel
// for all four preferences; the LSPosed hook-enable signal (HookState from
// the marker file) is tracked in UiState.hookActive/hookScopes for display
// in LSPosed mode. The LSPosed gateway stays the NoOp placeholder (whitelist
// policy branch pending): writes under it are refused with an explicit
// reason, never executed. The marker never feeds privilege routing
// (hasLsposed stays false below): a present marker is not proof of a live,
// effective hook, so it must not grant privilege.
class DualViewModel(
    private var repo: DualRepository,
    // Declared privilege of the gateway backing `repo`. F4a wires the real
    // gateway's privilege here (ROOT for SuCmdGateway, SHELL for
    // ShizukuCmdGateway, NONE for the NoOp fallback); probeChannels() flips
    // channelReady only when that same channel's probe succeeds.
    // Checked by class (not by probing the repo) so core stays untouched.
    // Mutable for F4b-P1 dynamic re-selection (selectChannel swaps it with
    // the rebuilt repository under opMutex).
    private var gatewayPrivilege: Privilege = Privilege.NONE,
    // Elevators probed in order; F4a injects elevators backed by the real
    // su/Shizuku gateways, so probes honestly verify each channel. Mutable:
    // selectChannel rebuilds them around the fresh gateway instances so the
    // probes keep verifying the actual channels after a swap.
    private var elevators: List<Elevator> = listOf(SuElevator(), ShizukuElevator()),
    // F4b-P1 selection state: the persisted user choice (AUTO default).
    private var preference: ChannelPreference = ChannelPreference.AUTO,
    // Package name for rebuilding the Shizuku gateway on re-selection.
    private val appPackageName: String = "",
    // Persists the choice (SharedPreferences in production, null in tests).
    // Best-effort: a failing store never blocks the in-memory swap.
    private val persistPreference: ((ChannelPreference) -> Unit)? = null,
    // Live environment checks for (re-)resolving AUTO. Injected so JVM tests
    // never touch File/Shizuku statics; the factory passes the real ones.
    private val suPresentCheck: () -> Boolean = { false },
    private val shizukuAuthCheck: () -> Boolean = { false },
    // Gateway backing `repo`, tracked so a swapped-out Shizuku gateway can
    // release its cached UserService binding instead of leaking it.
    private var activeGateway: CmdGateway = NoOpGateway(),
    // Resolved channel backing `repo` (disambiguates the two NONE-privilege
    // cases: explicit NOOP fallback vs LSPOSED placeholder).
    private var currentChannel: ResolvedChannel = ResolvedChannel.NOOP,
    // Hook-enable signal source (F4b-P2). Injected so JVM tests never touch
    // the device marker file; production reads it via readHookState()
    // (direct File reads of BOTH marker paths, primary then fallback).
    // Never throws by contract (readHookState maps all failures to
    // inactive), and probeChannels guards with try/catch regardless. When
    // the direct read is inactive but the channel proved ready, probeChannels
    // additionally retries via readHookStateWithGateway(activeGateway).
    private val hookStateProvider: () -> HookState = { readHookState() },
) : ViewModel() {
    private val _state = MutableStateFlow(
        UiState(
            channel = preference,
            msg = "通道未就緒：真通道已接線但未選中（NoOp 回退），目前為唯讀",
            msgKind = MsgKind.CHANNEL,
        ),
    )

    // Observable state for the UI; collected with lifecycleScope in F2.
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Serializes probe/refresh/clone/remove (see class doc).
    private val opMutex = Mutex()

    init {
        // Probe elevation channels first; later ops queue behind this, so the
        // first refresh always sees the routed privilege, never a default.
        viewModelScope.launch {
            opMutex.withLock { probeChannels() }
        }
    }

    // Reloads profiles AND rebuilds `cloned` from the dual user's actual
    // packages. Three states are told apart explicitly (issue #9):
    // - ChannelError: `pm list users` itself failed -> "channel error: ...".
    // - NoDual: channel ok but no single DUAL profile -> "no dual profile".
    // - Empty: dual exists but owns no clones -> "no clones installed".
    // A failed clone-listing keeps the previous `cloned` and reports
    // "result unknown" rather than claiming the list is empty.
    fun refresh() {
        viewModelScope.launch {
            opMutex.withLock { refreshLocked() }
        }
    }

    // Reload body shared by refresh() and selectChannel(). Must run under
    // opMutex: callers hold it, so this never launches or locks itself.
    private suspend fun refreshLocked() {
        // Mark busy first so the UI can render a spinner.
        _state.update { it.copy(loading = true, msg = "", msgKind = MsgKind.NONE) }
        try {
            when (val snap = repo.profilesSnapshot()) {
                is ProfilesSnapshot.ChannelError ->
                    _state.update {
                        it.copy(
                            loading = false,
                            profiles = emptyList(),
                            msg = "channel error: ${snap.detail}",
                            msgKind = MsgKind.CHANNEL,
                        )
                    }
                is ProfilesSnapshot.Loaded -> {
                    val profiles = snap.profiles
                    // Pure resolve on this snapshot: no second gateway call,
                    // so the id below cannot drift from what we display.
                    val dualId = PmOutputParser.resolveDualUserId(profiles)
                    if (dualId < 0) {
                        _state.update {
                            it.copy(
                                loading = false,
                                profiles = profiles,
                                cloned = emptyList(),
                                msg = "no dual profile",
                                msgKind = MsgKind.NO_DUAL,
                            )
                        }
                    } else {
                        val groundTruth = repo.listClonedPackages(dualId)
                        _state.update { cur ->
                            if (groundTruth == null) {
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    msg = "refresh result unknown: " +
                                        "cannot list clones for user $dualId",
                                    msgKind = MsgKind.CHANNEL,
                                )
                            } else if (groundTruth.isEmpty()) {
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    cloned = emptyList(),
                                    msg = "no clones installed",
                                    msgKind = MsgKind.INFO,
                                )
                            } else {
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    cloned = groundTruth,
                                    msg = "",
                                    msgKind = MsgKind.NONE,
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Gateways report via CmdResult, but guard against unexpected throws.
            _state.update {
                it.copy(
                    loading = false,
                    msg = "refresh failed: ${e.message}",
                    msgKind = MsgKind.FAILED,
                )
            }
        }
    }

    // Clones pkg into the dual profile; appends it to cloned ONLY when the
    // pinned re-check confirms it installed. A fake success (install ok, pkg
    // absent) or an uncheckable channel reports "verification failed" /
    // "result unknown" and leaves cloned untouched. Refused while the channel
    // is not ready or privilege is NONE (fail closed, read-only).
    fun clone(pkg: String) {
        viewModelScope.launch {
            opMutex.withLock {
                // Validate input early without touching the device.
                if (pkg.isBlank()) {
                    _state.update { it.copy(msg = "invalid package", msgKind = MsgKind.INVALID) }
                    return@withLock
                }
                if (!isWritable()) return@withLock
                val trimmed: String = pkg.trim()
                _state.update { it.copy(loading = true, msg = "", msgKind = MsgKind.NONE) }
                try {
                    // Single-snapshot pinned flow inside the repository.
                    when (val res = repo.cloneVerified(trimmed)) {
                        is CloneOutcome.Success -> {
                            _state.update { cur ->
                                val current: List<String> =
                                    if (cur.cloned.contains(trimmed)) cur.cloned
                                    else cur.cloned + trimmed
                                cur.copy(
                                    loading = false,
                                    cloned = current,
                                    msg = "cloned: $trimmed",
                                    msgKind = MsgKind.INFO,
                                )
                            }
                        }
                        is CloneOutcome.NoDual ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "no dual profile",
                                    msgKind = MsgKind.NO_DUAL,
                                )
                            }
                        is CloneOutcome.ChannelError ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "clone result unknown: " +
                                        "channel error: ${res.detail}",
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        is CloneOutcome.InvalidPackage ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "invalid package",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                        is CloneOutcome.InstallFailed ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = res.detail,
                                    msgKind = MsgKind.FAILED,
                                )
                            }
                        is CloneOutcome.VerifyFailed ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "clone verification failed: " +
                                        "$trimmed not installed for user ${res.dualId}",
                                    msgKind = MsgKind.VERIFY,
                                )
                            }
                        is CloneOutcome.VerifyUnknown ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "clone result unknown: " +
                                        "cannot verify $trimmed for user ${res.dualId}: ${res.detail}",
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                    }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            loading = false,
                            msg = "clone failed: ${e.message}",
                            msgKind = MsgKind.FAILED,
                        )
                    }
                }
            }
        }
    }

    // Removes pkg from the dual profile; drops it from cloned ONLY when the
    // pinned re-check confirms it absent. Anything else (failed uninstall,
    // still present, uncheckable channel) keeps the entry: fail closed.
    // Refused while the channel is not ready or privilege is NONE.
    fun remove(pkg: String) {
        viewModelScope.launch {
            opMutex.withLock {
                // Validate input early without touching the device.
                if (pkg.isBlank()) {
                    _state.update { it.copy(msg = "invalid package", msgKind = MsgKind.INVALID) }
                    return@withLock
                }
                if (!isWritable()) return@withLock
                val trimmed: String = pkg.trim()
                _state.update { it.copy(loading = true, msg = "", msgKind = MsgKind.NONE) }
                try {
                    // Single-snapshot pinned flow inside the repository.
                    when (val res = repo.removeVerified(trimmed)) {
                        is RemoveOutcome.Success ->
                            _state.update { cur ->
                                cur.copy(
                                    loading = false,
                                    cloned = cur.cloned.filterNot { it == trimmed },
                                    msg = "removed: $trimmed",
                                    msgKind = MsgKind.INFO,
                                )
                            }
                        is RemoveOutcome.NoDual ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "no dual profile",
                                    msgKind = MsgKind.NO_DUAL,
                                )
                            }
                        is RemoveOutcome.ChannelError ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "remove result unknown: " +
                                        "channel error: ${res.detail}",
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        is RemoveOutcome.InvalidPackage ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "invalid package",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                        is RemoveOutcome.RemoveFailed ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = res.detail,
                                    msgKind = MsgKind.FAILED,
                                )
                            }
                        is RemoveOutcome.StillInstalled ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "remove failed: $trimmed " +
                                        "still installed for user ${res.dualId}",
                                    msgKind = MsgKind.VERIFY,
                                )
                            }
                        is RemoveOutcome.VerifyUnknown ->
                            _state.update {
                                it.copy(
                                    loading = false,
                                    msg = "remove result unknown: " +
                                        "cannot verify removal of $trimmed " +
                                        "for user ${res.dualId}: ${res.detail}",
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                    }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            loading = false,
                            msg = "remove failed: ${e.message}",
                            msgKind = MsgKind.FAILED,
                        )
                    }
                }
            }
        }
    }

    // Re-probes elevation channels after a binder/permission lifecycle event
    // (Phase F3, issue #6): called from MainActivity's Shizuku binder/permission
    // listeners and from onResume. Serialized through opMutex like every other
    // op, so a re-probe never races an in-flight refresh/clone/remove; the
    // probe only rewrites privilege/channelReady/msg and leaves
    // profiles/cloned to the next refresh.
    //
    // Phase F4b-P1: when the persisted choice is AUTO, the startup pick is
    // re-resolved first (the environment may have changed since process
    // start: Shizuku started/granted, su appeared). An explicit non-AUTO
    // choice is never overridden here: only selectChannel() changes it.
    fun recheckPrivilege() {
        viewModelScope.launch {
            opMutex.withLock {
                if (preference == ChannelPreference.AUTO) swapAutoLocked()
                probeChannels()
            }
        }
    }

    // Switches to pref (F4b-P1 dynamic re-selection): persists the choice so
    // a process restart remembers it, rebuilds the repository around the
    // matching gateway (su / Shizuku / NoOp; explicit LSPosed still maps to
    // the NoOp placeholder until the P2 hook channel lands -- see
    // assembleChannel), then re-probes privilege and refreshes the lists.
    // Serialized through opMutex like every other op.
    fun selectChannel(pref: ChannelPreference) {
        viewModelScope.launch {
            opMutex.withLock {
                try {
                    persistPreference?.invoke(pref)
                } catch (_: Exception) {
                    // Best effort: a failing store never blocks the swap.
                }
                swapToLocked(pref)
                probeChannels()
                refreshLocked()
            }
        }
    }

    // Re-resolves AUTO against the live environment and swaps the gateway
    // only when the resolution actually changed (preserves the Shizuku
    // binder cache on the common no-change path). Must run under opMutex.
    private suspend fun swapAutoLocked() {
        val suPresent = try {
            suPresentCheck()
        } catch (_: Exception) {
            false
        }
        val shizukuAuth = try {
            shizukuAuthCheck()
        } catch (_: Exception) {
            false
        }
        val resolved = ChannelPreference.AUTO.resolveChannel(suPresent, shizukuAuth)
        if (resolved != currentChannel) {
            swapToLocked(ChannelPreference.AUTO)
        }
    }

    // Rebuilds repo/gatewayPrivilege/elevators for pref via the shared
    // assembleChannel (same path as the startup factory, so the two can
    // never disagree on Auto order or the LSPosed placeholder). The selected
    // gateway shares its instance with the matching elevator, so the next
    // probe verifies the actual channel the repo uses. Must run under
    // opMutex. A swapped-out Shizuku gateway releases its cached binding.
    private suspend fun swapToLocked(pref: ChannelPreference) {
        val suPresent = try {
            suPresentCheck()
        } catch (_: Exception) {
            false
        }
        val shizukuAuth = try {
            shizukuAuthCheck()
        } catch (_: Exception) {
            false
        }
        val asm = assembleChannel(pref, suPresent, shizukuAuth, appPackageName)
        val old = activeGateway
        activeGateway = asm.gateway
        repo = DualRepository(asm.gateway)
        gatewayPrivilege = asm.gateway.privilege
        elevators = listOf(SuElevator(asm.suGateway), ShizukuElevator(asm.shizukuGateway))
        preference = pref
        currentChannel = asm.channel
        _state.update { it.copy(channel = pref) }
        if (old !== activeGateway && old is ShizukuCmdGateway) {
            try {
                old.close()
            } catch (_: Exception) {
                // Best effort: the stale binding dies with the process anyway.
            }
        }
    }

    // Probes elevation channels (ROOT first, then SHELL) and routes the
    // privilege decision via the shared core router. Must run under opMutex.
    //
    // Phase F4a (issue #6): the factory injects elevators backed by the real
    // su/Shizuku gateways, so these probes verify the actual channels.
    // channelReady is true ONLY when the repo's own gateway channel proved
    // itself (su repo needs the su/ROOT probe, Shizuku repo the
    // Shizuku/SHELL probe, NoOp repo never). A selected-but-unproven channel
    // stays read-only: fail closed, never a fake working channel.
    // DEVICE-UNVERIFIED: probe/exec round-trips never ran on a device.
    private suspend fun probeChannels() {
        var hasRoot = false
        var hasShizuku = false
        for (elevator in elevators) {
            try {
                when (elevator.probe().level) {
                    Privilege.ROOT -> if (elevator is SuElevator) hasRoot = true
                    Privilege.SHELL -> if (elevator is ShizukuElevator) hasShizuku = true
                    Privilege.NONE -> Unit
                }
            } catch (e: Exception) {
                // A throwing probe means no channel from this elevator.
            }
        }
        // LSPosed hook (ROOT) outranks su (ROOT) outranks Shizuku (SHELL);
        // the router encodes that priority, probes only feed it.
        // F4b-P2: hasLsposed stays conservatively false for ROUTING even
        // though the marker now feeds the UI display below. Rationale: the
        // marker is existence+scope evidence from a file that survives
        // reboots (see HookMarker), not proof of a live, effective hook, so
        // it must never grant privilege. Detecting a truly effective hook
        // needs a real device to verify.
        val routed = routePrivilege(hasLsposed = false, hasShizuku = hasShizuku, hasRoot = hasRoot)
        // H2/H4 pure gate (unit-tested by ChannelReadyTest): identical to
        // the inline when it replaces. Hook signal stays display-only (#19).
        val ready = channelReadyFor(gatewayPrivilege, hasRoot, hasShizuku)
        // Hook-enable signal for the LSPosed picker row (F4b-P2). Read off
        // the main thread (file IO); any failure reads as inactive, never
        // blocking the privilege update. probeChannels runs under opMutex
        // from recheckPrivilege/selectChannel/init, so every UI entry point
        // refreshes the hook display with no extra Activity wiring.
        // Strategy: the injected seam does the direct File reads of both
        // marker paths first (likely SELinux-denied for the app uid); only
        // when that reads inactive AND this probe proved the channel (ready)
        // is the active SHELL/ROOT gateway given one `cat` chance per path
        // via readHookStateWithGateway. A NoOp/unproven channel never
        // executes anything, and every failure still maps to inactive.
        val hook: HookState = try {
            withContext(Dispatchers.IO) {
                val direct = hookStateProvider()
                if (direct.active) {
                    direct
                } else if (!ready || gatewayPrivilege == Privilege.NONE) {
                    direct
                } else {
                    try {
                        readHookStateWithGateway(activeGateway)
                    } catch (_: Exception) {
                        direct
                    }
                }
            }
        } catch (_: Exception) {
            HookState(active = false)
        }
        val hookDesc = hook.displayText()
        _state.update {
            it.copy(
                channel = preference,
                privilege = routed,
                channelReady = ready,
                hookActive = hook.active,
                hookScopes = hook.scopes,
                msg = if (ready) {
                    ""
                } else if (currentChannel == ResolvedChannel.LSPOSED_PLACEHOLDER) {
                    // LSPosed mode semantics (F4b-P2): the gateway is still
                    // the NoOp placeholder (whitelist policy branch pending),
                    // so the channel is honestly never ready. The message
                    // states the reason plus the hook signal; it never claims
                    // the channel can execute.
                    "LSPosed 通道為佔位（NoOp）：whitelist 政策分支待實作，" +
                        "寫入已拒絕；Hook 狀態：$hookDesc"
                } else if (gatewayPrivilege == Privilege.NONE) {
                    "通道未就緒：真通道已接線但未選中（NoOp 回退），目前為唯讀"
                } else {
                    "通道未就緒：已選 ${gatewayPrivilege} 通道但探測未通過，寫入已禁用"
                },
                msgKind = if (ready) MsgKind.NONE else MsgKind.CHANNEL,
            )
        }
    }

    // Write guard: a real (non-NoOp) ready channel with SHELL or better.
    // Reports the refusal into msg and returns false when writes are off.
    // Must run under opMutex.
    //
    // F4b-P2 LSPosed semantics: the placeholder gateway is a NoOp by policy
    // (whitelist branch pending), so writes are refused with the reason
    // stated, including the hook signal. Nothing is ever executed here: the
    // channel must not be mistaken for a working hook path.
    private fun isWritable(): Boolean {
        val cur = _state.value
        if (currentChannel == ResolvedChannel.LSPOSED_PLACEHOLDER) {
            val hookDesc = if (cur.hookActive) {
                "已啟用（${cur.hookScopes.sorted().joinToString(", ")}）"
            } else {
                "未偵測到"
            }
            _state.update {
                it.copy(
                    loading = false,
                    msg = "LSPosed 通道為佔位（NoOp）：whitelist 政策分支待實作，" +
                        "寫入已拒絕；Hook 狀態：$hookDesc",
                    msgKind = MsgKind.INVALID,
                )
            }
            return false
        }
        if (!cur.channelReady) {
            _state.update {
                it.copy(
                    loading = false,
                    msg = "通道未就緒：未選中真通道或所選通道探測未通過，寫入已禁用",
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return false
        }
        if (cur.privilege == Privilege.NONE || gatewayPrivilege == Privilege.NONE) {
            _state.update {
                it.copy(
                    loading = false,
                    msg = "read-only: no privilege channel, writes disabled",
                    msgKind = MsgKind.INVALID,
                )
            }
            return false
        }
        return true
    }
}
