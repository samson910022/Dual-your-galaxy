// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.DualProfile
import com.galaxydual.core.DualRepository
import com.galaxydual.core.DualTargetResult
import com.galaxydual.core.Elevator
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ProfilesSnapshot
import com.galaxydual.core.RefreshOutcome
import com.galaxydual.core.RemoveOutcome
import com.galaxydual.core.VerifyState
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import com.galaxydual.core.channelReadyFor
import com.galaxydual.core.routePrivilege
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Pending two-phase remove confirmation (PR-4 P1, issue #23).
//
// Created by requestRemove(pkg) and consumed by confirmRemove(); cleared by
// cancelRemove() or by any confirm rejection/execution. All fields are a
// snapshot of the request moment so confirmRemove can re-verify that the
// target did not move under the dialog:
//
// - pkg: trimmed package the user asked to remove.
// - dualId: verified cross-source dual target at request time (never the
//   pm-list label).
// - dualSerial: agreed platform serial for that target at request time
//   (PR-33 R2, P1 serial binding). A delete+recreate reuses the user id and
//   label but mints a fresh serial, so confirmRemove re-verifies (id, serial)
//   together and refuses a serial mismatch without uninstalling.
// - profileLabel: display label of that target at request time (second
//   "selection unchanged" signal besides the id).
// - requestedAtMs: clock reading at request time (consent generation,
//   same clock source as the TTL check). confirmRemove rejects a stale
//   consent older than REMOVE_CONFIRM_TTL_MS (fail closed, must request
//   again); the serial/id/label re-check still guards a moved target
//   regardless of age.
//
// Rotation policy (explicit choice, pinned by RemoveConfirmTest): pending
// lives in UiState inside the ViewModel, so a config rotation (Activity
// recreate, ViewModel retained) KEEPS the pending and the dialog re-shows
// from state with no re-send. A ViewModel recreation (process death) starts
// with pendingRemove == null (fail closed): the user must request again.
// Phase A P2 fix (issue #25 review): instance-bound diagnostic evidence.
//
// The old boolean cache (lastVerifiedHasRoot/Shizuku) recorded only "a
// channel once proved" and survived gateway rebuilds, so an LSPOSED round
// could drive `cat` through a FRESH, never-verified su/Shizuku instance
// (SuCmdGateway.run = `su -c cat` may pop an auth prompt on the new
// instance). The evidence now binds to the verified instance + generation:
// usable only when evidence.gateway === current su/shizukuGateway AND
// evidence.generation == evidenceGeneration AND hasCapability true.
//
// - generation increments when swapToLocked installs DIFFERENT su/shizuku
//   instances (production rebuilds fresh every time, so every production
//   swap bumps; a test factory reusing the same fake keeps the generation,
//   which exercises the match branch). swapToResolvedLocked reuses the same
//   instances (AUTO degrade) and bumps as well so a stale pre-degrade entry
//   can never leak across (probeChannels rewrites both entries right after
//   with the new generation).
// - explicit single-channel success updates ONLY its own entry with the
//   CURRENT gateway (ROOT_ONLY -> su entry, SHIZUKU_ONLY -> shizuku entry;
//   the other entry is left unknown, never cleared); failure writes
//   hasCapability=false on the same instance. AUTO full probes rewrite both.
// - LSPOSED probes none and writes nothing; its diagnosis uses ONLY matched
//   evidence, otherwise UNKNOWN (never auto-triggers a fresh `su -c` probe:
//   needing a new su must NOT pop auth, stay UNKNOWN until that instance is
//   verified by an explicit/AUTO round).
// - Shizuku binder dead/rebind: a dead UserService binding makes the next
//   Shizuku probe (`pm list users` via ShizukuCmdGateway) fail (exit != 0),
//   so hasShizuku=false rewrites shizukuEvidence.hasCapability=false on the
//   SAME instance/same generation; diagnosis immediately stops using it. A
//   successful rebind only re-enables diagnosis after the next passing probe
//   rewrites true (fail closed, no optimistic reuse of a dead handle).
// Diagnostic-only: never feeds routing/readiness (hook-neutral, #19).
data class DiagnosticEvidence(
    val gateway: CmdGateway? = null,
    val hasCapability: Boolean = false,
    val generation: Long = 0L,
)
// Single probe result reused by swap + probeChannels (P25 Item 3, issue #25):
// hasRoot/hasShizuku reflect ONLY the probed subset: AUTO probes all
// elevators, explicit probes only its own channel (ROOT -> su only,
// SHIZUKU -> Shizuku only, LSPOSED -> none), so for explicit the fields
// never claim an unrelated channel.
data class ProbeOutcome(
    val hasRoot: Boolean,
    val hasShizuku: Boolean,
)

// Phase C P2 semantic (issue #25 review): probe scope tracking. The probe
// result (hasRoot/hasShizuku, routed privilege, anyProbePassed) reflects ONLY
// the probed subset, never device total capability:
// - AUTO probes all elevators -> FULL (device-wide for this round).
// - Explicit ROOT probes only SuElevator -> ROOT_ONLY.
// - Explicit SHIZUKU probes only ShizukuElevator -> SHIZUKU_ONLY.
// - Explicit LSPOSED probes none (NoOp placeholder) -> NONE (zero probe).
// Render must show 本次路由 + 本次探測[scope] + 三態結果 (NONE -> 未探測),
// never "裝置路由/探測未通過" for a zero-probe round.
enum class ProbeScope {
    FULL,
    ROOT_ONLY,
    SHIZUKU_ONLY,
    NONE,
}

// Pure mapping: preference -> probe scope (single source; probeChannels uses
// this so state and render can never drift). Covered by JVM unit tests.
fun ChannelPreference.probeScope(): ProbeScope = when (this) {
    ChannelPreference.AUTO -> ProbeScope.FULL
    ChannelPreference.ROOT -> ProbeScope.ROOT_ONLY
    ChannelPreference.SHIZUKU -> ProbeScope.SHIZUKU_ONLY
    ChannelPreference.LSPOSED -> ProbeScope.NONE
}

// Pure display label for the scope (MainActivity uses this; tests assert the
// state fields, not the rendered string).
fun ProbeScope.displayLabel(): String = when (this) {
    ProbeScope.FULL -> "全部通道"
    ProbeScope.ROOT_ONLY -> "僅ROOT"
    ProbeScope.SHIZUKU_ONLY -> "僅Shizuku"
    ProbeScope.NONE -> "未探測"
}

data class PendingRemove(
    val pkg: String,
    val dualId: Int,
    val dualSerial: Int,
    val profileLabel: String,
    val requestedAtMs: Long,
)

// Error class of the user-facing message, so the UI (F3) can render channel
// failures differently from validation refusals or verify mismatches.
enum class MsgKind {
    // No message to show.
    NONE,

    // Success or neutral status ("cloned: x", "no clones installed").
    INFO,

    // Invalid input (bad package token). Refused writes use CHANNEL
    // (selected channel not ready); only the defensive unreachable guard
    // keeps INVALID.
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
// routed elevation decision, channel the persisted preference, resolvedGateway
// the actual gateway backing the repo (P25: preference vs actual split, so an
// explicit SHIZUKU pick with ROOT available never misreports "using ROOT"),
// execIdentity the declared privilege of that gateway, anyProbePassed whether
// the probed subset proved any channel (probe signal only, never proof that
// `pm list users` is readable -- see field docs), probeScope which subset was
// probed this round (Phase C P2: NONE = zero probe, never "failed"), pmReadable
// whether `pm list users` proved readable via refresh (probe never writes it;
// true readability = pmReadable == true plus writeReady -- never collapse
// probe identity / PM-read / PM-write into one boolean), writeReady whether
// the selected channel proved itself, degradeNote the Auto probe-based degrade
// explanation (empty when none), profiles/cloned the device ground truth,
// loading the in-flight flag, and msg (+msgKind) the user-facing status.
data class UiState(
    // Routed elevation level from the elevator probes (ROOT/SHELL/NONE).
    // Phase C P2: 本次路由 only -- reflects the probed subset (see probeScope),
    // never device total capability. An explicit SHIZUKU success never implies
    // ROOT absence; an LSPOSED zero-probe NONE never implies failure.
    val privilege: Privilege = Privilege.NONE,
    // Selected channel preference (F4b-P1 model, P2 picker UI in
    // MainActivity). NoOp is never a value here, only the fail-closed
    // fallback gateway.
    val channel: ChannelPreference = ChannelPreference.AUTO,
    // False until the selected real gateway's own probe succeeds (F4a): a
    // su-backed repo needs the su probe (ROOT), a Shizuku-backed repo the
    // Shizuku probe (SHELL); a NoOp repo (NONE) is never ready. Fail closed.
    // Legacy alias of writeReady (kept so existing callers/tests compile).
    val channelReady: Boolean = false,
    // Actual gateway backing the repo (P25). For explicit choices this equals
    // the preference mapping; for AUTO it is the probe-based degrade result
    // (su denied + Shizuku proven -> SHIZUKU, both fail -> NOOP).
    val resolvedGateway: ResolvedChannel = ResolvedChannel.NOOP,
    // Declared privilege of resolvedGateway (ROOT/SHELL/NONE). This is the
    // actual execution identity, distinct from the routed summary above
    // (e.g. AUTO both-proven: routed=ROOT and execIdentity=ROOT; explicit
    // SHIZUKU probes only Shizuku per P25 Item 3, so routed reflects the
    // probed subset, never an unprobed unrelated channel).
    val execIdentity: Privilege = Privilege.NONE,
    // Probed subset proved at least one channel (hasRoot || hasShizuku).
    // P25 Item 4: probe signal only, NOT proof that `pm list users` is
    // readable. Counter-example A: SuElevator returns hasRoot=true on
    // `id -u == 0` even when its `pm list users` fails (dualUserId=-1), so
    // anyProbePassed is true while refresh reports ChannelError. True PM
    // readability is decided by refresh (profilesSnapshot Loaded vs
    // ChannelError, listClonedPackages null vs list), not by this flag.
    // AUTO probes all, so this is device-wide; explicit probes only its own
    // channel (P25 Item 3), so for explicit anyProbePassed == writeReady by
    // construction (unrelated channels intentionally unprobed, never reported).
    // Counter-example B: the environment may hold a capability (e.g. ROOT
    // proven) while the current repo is a failing/placeholder channel
    // (denied SHIZUKU, LSPOSED NoOp) -- the flag then reflects only the
    // selected subset (false), never the unprobed environment.
    val anyProbePassed: Boolean = false,
    // Phase C P2: which subset this round probed (set by probeChannels from
    // preference; see ChannelPreference.probeScope). NONE = zero probe
    // (explicit LSPOSED): render shows 未探測, never 未通過. Together with
    // privilege/anyProbePassed this is 本次探測, not device total capability.
    val probeScope: ProbeScope = ProbeScope.NONE,
    // Phase C P2: independent PM readability (`pm list users` via refresh
    // only; probe never writes this field). null = unknown (not yet refreshed
    // or refresh threw), true = Loaded (profiles parsed, incl. NoDual which
    // still read profiles), false = ChannelError (list users failed). Tracks
    // `pm list users`, not `pm list packages` (a clone-list null still leaves
    // this true). True readability = (pmReadable == true) plus writeReady;
    // never collapse probe identity / PM-read / PM-write into one boolean.
    // Phase 2 expiry: swapToLocked/swapToResolvedLocked (gateway 已換) 即
    // null；probeChannels 在失去 readiness（ready false: NOOP 回退/撤銷/
    // binder dead）時釘 null（舊 refresh 快照過期）。僅 refresh 能寫
    // true/false。
    val pmReadable: Boolean? = null,
    // Selected channel proved itself (same gate as channelReady).
    val writeReady: Boolean = false,
    // Auto probe-based degrade explanation (P25). Non-empty only for AUTO
    // when the probes overrode the file heuristic (su denied -> Shizuku) or
    // when both probes failed (read-only). Explicit choices clear it.
    val degradeNote: String = "",
    // Hook-enable signal from the cross-process marker file (F4b-P2).
    // Refreshed on every probe (recheckPrivilege/selectChannel cover all UI
    // entry points). Honest existence+scope evidence only: the marker
    // survives reboots, so active here means "marker present", not proof of
    // a live hook (see HookMarker/HookStateProvider headers).
    val hookActive: Boolean = false,
    // Scopes recorded in the marker (subset of system_server/daagent).
    val hookScopes: Set<String> = emptySet(),
    // P1 diagnostic state (配合 #27): ACTIVE / INACTIVE / UNKNOWN_UNREADABLE
    // / UNKNOWN_NO_DIAGNOSTIC. Unknown (unreadable / no diagnostic path) is
    // never displayed as "not detected"; only a positively-read absence is.
    val hookStatus: HookStatus = HookStatus.UNKNOWN_NO_DIAGNOSTIC,
    // All Android profiles parsed from `pm list users`.
    val profiles: List<DualProfile> = emptyList(),
    // Packages currently cloned into the dual profile (MVP tracks by name).
    val cloned: List<String> = emptyList(),
    // True while a repository call is in flight; UI should show progress.
    // Starts true so the cold-start frame renders 載入中 rather than 就緒
    // before the first probe sets loading explicitly.
    val loading: Boolean = true,
    // Last user-facing status or error message; empty means no message.
    val msg: String = "",
    // Error class of msg; NONE when msg is empty.
    val msgKind: MsgKind = MsgKind.NONE,
    // Two-phase remove confirmation (PR-4 P1, issue #23): set by
    // requestRemove(), consumed by confirmRemove(), cleared by cancelRemove()
    // and by every confirm outcome (executed or rejected). Non-null means the
    // MainActivity must show the confirmation dialog; null means no dialog.
    // Survives config rotation (ViewModel retained); a recreated ViewModel
    // starts null (fail closed, must request again).
    val pendingRemove: PendingRemove? = null,
)

// Android ViewModel owning the dual-app screen state (Phase F2, issue #6).
// All shell work is delegated to DualRepository; this class only owns state.
//
// Phase C3 (issue #9): clone/remove go through the repository's pinned flows
// (cloneVerified/removeVerified: one `pm list users` snapshot plus
// before/fresh/after verified-target pinning; install/uninstall and re-check
// on the same dual id), and refresh rebuilds `cloned` from the device ground
// truth (`pm list packages --user <dualId>`) instead of trusting local state.
// Only a verify-confirmed install is added to `cloned`; only a
// verify-confirmed removal drops it.
//
// Concurrency (core review finding, PR-3 stage 1 issue #24): refresh/clone/
// remove/probe are serialized through opMutex so concurrent calls queue
// instead of racing last-writer-wins on `cloned`/`loading`. Three bounds keep
// rapid UI taps from piling an unbounded queue on that mutex: refresh() and
// reconnectAndRefresh() share one coalescing gate (at most one pending
// re-run behind the in-flight op, reconnect upgrades a pending refresh so
// the Auto degrade is never skipped), recheckPrivilege() coalesces
// separately (at most one pending re-run behind the in-flight one),
// selectChannel() is latest-wins (a new call cancels the
// previous select job), and clone/request-remove/confirm dedup by operation
// key (a second identical write while the first is in flight reports
// "already in progress" instead of queueing). Confirm keys are per
// generation (`remove:<pkg>@<dualId>#<serial>#<ts>`, PR-33 R2: the serial
// joins the generation so a stale dialog never blocks a fresh one, even
// across a delete+recreate that reuses the id). Every repository call runs under Dispatchers.IO
// (see onIo): gateway.run is a blocking suspend with no dispatcher
// guarantee, and a custom blocking delayFn (e.g. Thread.sleep) would still
// block its caller (the default settle wait is cancellable delay(),
// PR-32 P2-1), so running repo calls on Main would stall the Main dispatcher
// heartbeat. Public entry points are plain functions launching in
// viewModelScope; the Activity just calls them. All op state below
// (refreshJob/refreshPending/reconnectPending/recheckJob/recheckPending/inFlightWrites plus
// selectJob cleanup) is touched from viewModelScope coroutines
// (Main-confined) so no extra synchronization is needed, except the
// selectJob eager store which runs on the caller thread (Main in
// production, JUnit thread in tests) with the ordering guarantee noted
// at selectChannel().
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
//
// PR-4 P1 (issue #23): remove is two-phase. requestRemove(pkg) only records
// PendingRemove (verified id + serial + label + timestamp); confirmRemove()
// re-checks (verified id equal, serial equal, label equal, still writable)
// and only then runs repo.removeVerifiedExpected. cancelRemove() drops the
// pending. Rapid double confirm executes at most once via the shared
// inFlightWrites dedup.
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
    // Real-channel gateways backing `elevators` and the P1 hook diagnostic
    // reader. The selected gateway shares its instance with the matching
    // one here (ROOT -> suGateway, SHIZUKU -> shizukuGateway), so probes and
    // diagnosis verify the actual channels. Mutable: selectChannel rebuilds
    // them from the fresh assembly alongside `elevators` (single source).
    // Constructors stay Android-free so JVM tests can build them.
    private var suGateway: CmdGateway = SuCmdGateway(),
    private var shizukuGateway: CmdGateway = ShizukuCmdGateway(appPackageName),
    // Gateway backing `repo`, tracked so a swapped-out Shizuku gateway can
    // release its cached UserService binding instead of leaking it.
    private var activeGateway: CmdGateway = NoOpGateway(),
    // Resolved channel backing `repo` (disambiguates the two NONE-privilege
    // cases: explicit NOOP fallback vs LSPOSED placeholder).
    private var currentChannel: ResolvedChannel = ResolvedChannel.NOOP,
    // Test seam for channel assembly (P2-2 latest-wins tests). Production
    // uses the shared assembleChannel (real Su/Shizuku gateways, same path
    // as the startup factory). JVM tests inject fake-backed assemblies so
    // select probes run on a gatable FakeGateway: real SuCmdGateway blocks
    // up to SU_EXEC_TIMEOUT on its password prompt with no tty
    // (environment-dependent, fast-fails in CI) and Shizuku statics throw
    // ExceptionInInitializerError under unit tests, so select bodies can
    // never run on the JVM without this seam. Defaults keep production
    // behavior identical.
    private val assembleChannelFn: (
        ChannelPreference,
        Boolean,
        Boolean,
        String,
    ) -> ChannelAssembly = ::assembleChannel,
    // Last Auto probe-based degrade explanation (P25). Recomputed on every
    // probe when preference==AUTO (see degradeAutoLocked); cleared on
    // explicit selection. Copied into UiState.degradeNote by probeChannels.
    private var autoDegradeNote: String = "",
    // Instance-bound diagnostic evidence (Phase A P2 fix, see
    // DiagnosticEvidence header): su/shizuku each bind to the verified
    // gateway instance + generation. Cold-start = empty (gateway null) so
    // LSPOSED diagnoses via direct reads only (UNKNOWN_NO_DIAGNOSTIC there
    // is correct). Survives swaps as stale (mismatched) entries -- never
    // cleared eagerly; the identity/generation gate in probeChannels treats
    // a rebuilt instance as UNKNOWN until that instance itself is verified.
    private var suEvidence: DiagnosticEvidence = DiagnosticEvidence(),
    private var shizukuEvidence: DiagnosticEvidence = DiagnosticEvidence(),
    // Evidence generation: bumped when swapToLocked installs different
    // su/shizuku instances (production rebuilds fresh every swap) and on
    // every swapToResolvedLocked (AUTO degrade reuses instances but still
    // bumps so a pre-degrade entry cannot leak; probeChannels rewrites both
    // entries right after with the new generation). A test factory reusing
    // the same fake keeps the generation, exercising the match branch.
    private var evidenceGeneration: Long = 0L,
    // Channel assembly seam: production passes real assembleChannel (fresh
    // SuCmdGateway/ShizukuCmdGateway every swap); tests inject a fake
    // factory returning countable CmdGateway fakes (same vs new instances
    // per test) so selectChannel + rebuild runs for real instead of
    // constructor-injecting booleans. Signature mirrors assembleChannel.
    private val assembleChannelFn: (
        ChannelPreference,
        Boolean,
        Boolean,
        String,
    ) -> ChannelAssembly = ::assembleChannel,
    // Hook-enable signal source (F4b-P2, P1 diagnostic split). Injected so JVM
    // tests never touch the device marker file; production reads via
    // defaultDirectMarkerReads() (direct MarkerReads of BOTH marker paths,
    // primary then fallback). hookDiagnosticGateways supplies the CURRENT
    // real-channel gateways INDEPENDENT of the selected write channel, so an
    // explicit-LSPosed (NoOp) selection still diagnoses through any
    // probe-verified ROOT/SHIZUKU channel; probeChannels further gates that
    // list by hasRoot/hasShizuku for non-LSPOSED, and by the matched
    // su/shizukuEvidence for LSPOSED (an unproven or rebuilt gateway is not
    // a diagnostic path -- needs-new-su must NOT auto-trigger a probe).
    // Never throws by contract; probeChannels guards with try/catch.
    private val hookDirectReads: () -> List<MarkerRead> = { defaultDirectMarkerReads() },
    private val hookDiagnosticGateways: () -> List<CmdGateway> = { listOf(suGateway, shizukuGateway) },
    // Clock source for the remove-consent TTL (PR-33 R3, P2; S3 uses a
    // monotonic clock in production). Defaults to the real wall clock so
    // JVM tests need no Android runtime; tests inject a controllable fake.
    // Constructor stays Android-free; the factory wires elapsedRealtime.
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    companion object {
        // Consent 有效期，超期 confirm 拒絕重請 (PR-33 R3, P2): a dialog left
        // open longer than this must re-request; confirmRemove clears the
        // stale pending with INVALID + 重新確認 and runs zero uninstalls.
        const val REMOVE_CONFIRM_TTL_MS = 5 * 60 * 1000L
    }
    private val _state = MutableStateFlow(
        UiState(
            channel = preference,
            resolvedGateway = currentChannel,
            execIdentity = gatewayPrivilege,
            // 初值不預設任何通道訊息（啟動可能是 ROOT/SHIZUKU/NoOp）：
            // msg/msgKind 保持 NONE，等首次探測（probeChannels）再寫入。
            msg = "",
            msgKind = MsgKind.NONE,
        ),
    )

    // Observable state for the UI; collected with lifecycleScope in F2.
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Serializes probe/refresh/clone/remove (see class doc).
    private val opMutex = Mutex()

    // Refresh/reconnect coalescing (PR-3 stage 1, issue #24; P25 Item 2):
    // the in-flight op job plus at most one pending re-run. A tap while an op
    // is active only flips a pending flag instead of queueing another mutex
    // waiter, so rapid taps collapse into a single trailing run. refresh() and
    // reconnectAndRefresh() share one job/pending pair: a trailing reconnect
    // (probe+refresh) covers a pending refresh, and a trailing refresh covers
    // only refresh when no reconnect arrived (last-wins with reconnect
    // upgrade, so a reconnect during a refresh is never lost and the Auto
    // degrade is never skipped). Main-confined (see class doc);
    // selectChannel's internal refreshLocked bypasses this gate and runs
    // inline under its own mutex hold.
    private var refreshJob: Job? = null
    private var refreshPending = false
    private var reconnectPending = false

    // Recheck single-flight (P2-2): same coalescing gate as refresh(). Binder/
    // permission/onResume bursts collapse to at most one trailing re-probe.
    // Main-confined (see class doc).
    private var recheckJob: Job? = null
    private var recheckPending = false

    // Select latest-wins (P2-2): the in-flight select job, cancelled by the
    // next picker tap. Eagerly assigned in selectChannel() (see below).
    // Main-confined (see class doc).
    private var selectJob: Job? = null

    // In-flight write dedup keys ("clone:<pkg>" / "request-remove:<pkg>" /
    // "remove:<pkg>@<id>#<serial>#<ts>", PR-3 stage 1, issue #24 + PR-4 P23
    // stage 2, PR-33 R2 adds the serial to the generation).
    // A duplicate op while the first is still running reports
    // "already in progress" instead of queueing behind the mutex. Clone and
    // remove use distinct keys so an intentional clone-then-remove sequence on
    // the same package is never collapsed. Main-confined (see class doc).
    private val inFlightWrites = mutableSetOf<String>()

    // Single constructor for in-flight write dedup keys; keeps the
    // "clone:<pkg>" / "request-remove:<pkg>" namespaces consistent.
    private fun opKey(op: String, pkg: String) = "$op:$pkg"

    // Runs a repository call off the Main thread (PR-3 stage 1, issue #24):
    // gateway.run is a blocking suspend with no dispatcher guarantee, and a
    // custom blocking delayFn (e.g. Thread.sleep) still blocks its caller
    // (the default settle wait is cancellable delay(), PR-32 P2-1), so every
    // repo call site goes through here to keep the Main dispatcher heartbeat
    // unblocked.
    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }

    init {
        // Probe elevation channels first; later ops queue behind this, so the
        // first refresh always sees the routed privilege, never a default.
        viewModelScope.launch {
            opMutex.withLock { probeChannels() }
        }
    }

    // Reloads profiles AND rebuilds `cloned` from the dual user's actual
    // packages via the single-target refresh (Phase D P2-D): one
    // profilesSnapshot (1 cmd) + one verified-target fetch (3 cmds) + one
    // list probe on that same id (1 cmd) = 5 commands. Three states are told
    // apart explicitly (issue #9):
    // - ChannelError: `pm list users` itself failed -> "channel error: ...".
    // - NoDual: channel ok but no single DUAL profile -> "no dual profile".
    // - Empty: dual exists but owns no clones -> "no clones installed".
    // A failed clone-listing (GroundTruth cloned == null) keeps the previous
    // `cloned` and reports "result unknown" rather than claiming the list is
    // empty. Drift is eliminated inside this API (single target throughout);
    // the explicit-id residual remains only in the old entries.
    fun refresh() {
        // Coalesce rapid taps (PR-3 stage 1, issue #24; P25 Item 2 shares the
        // gate with reconnect): while an op is in flight (including waiting on
        // opMutex behind init/probe), only record that one trailing re-run is
        // wanted. A later reconnect upgrades the trailing run to
        // probe+refresh (see drainCoalesced), so the degrade is never lost.
        // The trailing run covers every intermediate tap, so the mutex queue
        // never grows past one.
        //
        // The gate runs INSIDE viewModelScope.launch (Main-serialized with the
        // drain below): check-and-set (gate) and check-and-clear (drain +
        // refreshJob = null) each run synchronously on Main with no suspension
        // in between, so a tap can never slip between the final drain check
        // and job completion (lost wakeup). Clearing refreshJob in finally also
        // lets the next tap start a fresh job instead of piling behind a dead
        // one. currentCoroutineContext (not the scope receiver) identifies the
        // child job for the self-comparison in finally.
        viewModelScope.launch {
            if (refreshJob?.isActive == true) {
                refreshPending = true
                return@launch
            }
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            refreshJob = myJob
            try {
                opMutex.withLock { refreshLocked() }
                drainCoalesced()
            } finally {
                if (refreshJob === myJob) refreshJob = null
            }
        }
    }

    // Single trailing-run drain shared by refresh() and reconnectAndRefresh().
    // Must run inside the in-flight op job on Main (see refresh gate above):
    // the flag check-and-clear runs synchronously before the next mutex hold,
    // so no tap is lost. Last-wins with reconnect upgrade: a pending
    // reconnect covers a pending refresh in one probe+refresh round; a
    // pending refresh alone re-runs only refreshLocked (refresh semantics
    // stay probe-free).
    private suspend fun drainCoalesced() {
        while (refreshPending || reconnectPending) {
            val doReconnect = reconnectPending
            refreshPending = false
            reconnectPending = false
            if (doReconnect) {
                opMutex.withLock { reconnectLocked() }
            } else {
                opMutex.withLock { refreshLocked() }
            }
        }
    }

    // Probe (with Auto degrade) + refresh body shared by reconnectAndRefresh()
    // and the coalesced trailing run. Must run under opMutex: callers hold
    // it, so this never launches or locks itself.
    private suspend fun reconnectLocked() {
        // Phase D P2 busy (#24): mark busy BEFORE any suspending probe IO.
        // swapAutoLocked() suspends on `su -c id -u` (auth prompt can block)
        // while probeChannels() is the first place that used to set
        // loading=true -- during that window state.loading stayed false and
        // MainActivity left its buttons enabled. Idempotent with the
        // probeChannels/refreshLocked sets below.
        _state.update { it.copy(loading = true) }
        // P25 Item 3: one probe per round — swapAutoLocked probes once and
        // returns the result for probeChannels to reuse (previously each
        // probed all elevators: double `su -c` cost + double auth-prompt risk).
        if (preference == ChannelPreference.AUTO) {
            val probe = swapAutoLocked()
            probeChannels(probe)
        } else {
            probeChannels()
        }
        refreshLocked()
    }

    // Reload body shared by refresh() and selectChannel(). Must run under
    // opMutex: callers hold it, so this never launches or locks itself.
    // Phase D P2-D (single target fetch: snapshot 1 + target 3 cmds + list 1
    // = 5): repo.refreshVerified() pins snapshot, target, and listing to the
    // same id so resolve-to-list drift is inexpressible (preferred for new
    // code). This VM still uses the resolve-then-reverify path below;
    // migrating refreshLocked() to refreshVerified() is follow-up (same
    // fail-closed mapping, fewer commands).
    // Repository calls run via onIo (PR-3 stage 1, issue #24) so the retry
    // settle wait never blocks Main; StateFlow updates stay thread-safe from
    // any dispatcher.
    // P25 Item 1: combines a refresh result with the Auto degrade note so the
    // degrade reason survives refreshLocked (which previously overwrote msg
    // with "no clones installed" / "" / channel error). NONE (silent) shows
    // the note alone (INFO); INFO (neutral) appends the note ("；" join);
    // errors (CHANNEL/FAILED/NO_DUAL/VERIFY/INVALID) keep error priority:
    // base error first ("；" join) with the degrade reason appended, kind
    // stays the original error (never downgraded to INFO). Phase B P2: the
    // AUTO→NOOP refresh through the NoOp repo always ends as
    // "channel error: denied", so without the error-branch join the full
    // dual-failure reason would be hidden behind the refresh error (only in
    // degradeNote/channelView short tag).
    // Mirrors the pendingRemove preserve rule (NONE/INFO combinable, errors pass).
    private fun combineWithDegradeNote(
        baseMsg: String,
        baseKind: MsgKind,
        degradeNote: String,
    ): Pair<String, MsgKind> {
        if (preference != ChannelPreference.AUTO || degradeNote.isEmpty()) return baseMsg to baseKind
        return when (baseKind) {
            MsgKind.NONE -> degradeNote to MsgKind.INFO
            MsgKind.INFO -> "$baseMsg；$degradeNote" to MsgKind.INFO
            else -> "$baseMsg；$degradeNote" to baseKind
        }
    }

    private suspend fun refreshLocked() {
        // Mark busy first so the UI can render a spinner. Dialog 開著時狀態列
        // 不洗掉確認提示：pendingRemove != null 時保留原 msg/msgKind，只設
        // loading=true（refresh 不應驅散確認對話框的提示）。
        _state.update {
            if (it.pendingRemove != null) it.copy(loading = true)
            else it.copy(loading = true, msg = "", msgKind = MsgKind.NONE)
        }
        try {
            when (val snap = onIo { repo.profilesSnapshot() }) {
                is ProfilesSnapshot.ChannelError ->
                    _state.update { cur ->
                        val (combinedMsg, combinedKind) = combineWithDegradeNote(
                            "channel error: ${snap.detail}",
                            MsgKind.CHANNEL,
                            cur.degradeNote,
                        )
                        val next = cur.copy(
                            loading = false,
                            profiles = emptyList(),
                            // Phase C P2: `pm list users` proven unreadable.
                            pmReadable = false,
                            msg = combinedMsg,
                            msgKind = combinedKind,
                        )
                        if (cur.pendingRemove != null &&
                            (next.msgKind == MsgKind.NONE || next.msgKind == MsgKind.INFO)
                        ) {
                            next.copy(msg = cur.msg, msgKind = cur.msgKind)
                        } else {
                            next
                        }
                    }
                is ProfilesSnapshot.Loaded -> {
                    val profiles = snap.profiles
                    // Verified identity only: the id comes from the repo's
                    // cross-source resolver, never from the pm-list label
                    // (display-only by contract), so the display layer makes
                    // no label judgment. Two separate fetches under the hood
                    // (resolve here plus re-verification inside
                    // listClonedPackages(dualId)); drift between them returns
                    // unknown (fail closed), never a write to the wrong user
                    // (refresh itself is read-only).
                    val dualId = onIo { repo.resolveDualUserId() }
                    if (dualId < 0) {
                        _state.update { cur ->
                            val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                "no dual profile",
                                MsgKind.NO_DUAL,
                                cur.degradeNote,
                            )
                            val next = cur.copy(
                                loading = false,
                                profiles = profiles,
                                cloned = emptyList(),
                                // Phase C P2: profiles parsed (readable) but no
                                // dual target -- still true (`pm list users`
                                // succeeded).
                                pmReadable = true,
                                msg = combinedMsg,
                                msgKind = combinedKind,
                            )
                            if (cur.pendingRemove != null &&
                                (next.msgKind == MsgKind.NONE || next.msgKind == MsgKind.INFO)
                            ) {
                                next.copy(msg = cur.msg, msgKind = cur.msgKind)
                            } else {
                                next
                            }
                        }
                    } else {
                        val groundTruth = onIo { repo.listClonedPackages(dualId) }
                        _state.update { cur ->
                            val (baseMsg, baseKind) = if (groundTruth == null) {
                                "refresh result unknown: " +
                                    "cannot list clones for user $dualId" to MsgKind.CHANNEL
                            } else if (groundTruth.isEmpty()) {
                                "no clones installed" to MsgKind.INFO
                            } else {
                                "" to MsgKind.NONE
                            }
                            val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                baseMsg,
                                baseKind,
                                cur.degradeNote,
                            )
                            val next = if (groundTruth == null) {
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    // Phase C P2: profilesSnapshot Loaded proves
                                    // `pm list users` readable even when the
                                    // clone list itself is unknowable (separate
                                    // `pm list packages` failure).
                                    pmReadable = true,
                                    msg = combinedMsg,
                                    msgKind = combinedKind,
                                )
                            } else if (groundTruth.isEmpty()) {
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    cloned = emptyList(),
                                    pmReadable = true,
                                    msg = combinedMsg,
                                    msgKind = combinedKind,
                                )
                            } else {
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    cloned = groundTruth,
                                    pmReadable = true,
                                    msg = combinedMsg,
                                    msgKind = combinedKind,
                                )
                            }
                            if (cur.pendingRemove != null &&
                                (next.msgKind == MsgKind.NONE || next.msgKind == MsgKind.INFO)
                            ) {
                                next.copy(msg = cur.msg, msgKind = cur.msgKind)
                            } else {
                                next
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Never swallow coroutine cancellation: rethrow so cancel stays
            // cooperative and refresh() coalescing still drains correctly.
            if (e is CancellationException) throw e
            // Gateways report via CmdResult, but guard against unexpected throws.
            // pendingRemove 開著時只保留非錯誤提示（NONE/INFO），CHANNEL/FAILED 穿透。
            _state.update { cur ->
                val (combinedMsg, combinedKind) = combineWithDegradeNote(
                    "refresh failed: ${e.message ?: e.javaClass.simpleName}",
                    MsgKind.FAILED,
                    cur.degradeNote,
                )
                val next = cur.copy(
                    loading = false,
                    // Phase C P2: unexpected throw -- readability unknown
                    // (fail closed via null; never claim false proof).
                    pmReadable = null,
                    msg = combinedMsg,
                    msgKind = combinedKind,
                )
                if (cur.pendingRemove != null &&
                    (next.msgKind == MsgKind.NONE || next.msgKind == MsgKind.INFO)
                ) {
                    next.copy(msg = cur.msg, msgKind = cur.msgKind)
                } else {
                    next
                }
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
            // Validate input early without touching the device.
            if (pkg.isBlank()) {
                _state.update { it.copy(msg = "invalid package", msgKind = MsgKind.INVALID) }
                return@launch
            }
            val trimmed: String = pkg.trim()
            // Write dedup (PR-3 stage 1, issue #24): a second identical clone
            // while the first is still in flight reports "already in progress"
            // instead of queueing another mutex waiter + device round-trip.
            // Checked before the mutex hold (Main-confined add); released in
            // finally so a cancelled/failed op never wedges the key.
            val key = opKey("clone", trimmed)
            if (!inFlightWrites.add(key)) {
                _state.update {
                    it.copy(msg = "already in progress: $trimmed", msgKind = MsgKind.INFO)
                }
                return@launch
            }
            try {
                opMutex.withLock {
                    try {
                    if (!isWritable()) return@withLock
                    _state.update { it.copy(loading = true, msg = "", msgKind = MsgKind.NONE) }
                    try {
                        // Single-snapshot pinned flow inside the repository.
                        // onIo: the core retry settle wait must never run on Main.
                        when (val res = onIo { repo.cloneVerified(trimmed) }) {
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
                    // Never swallow coroutine cancellation (see refreshLocked).
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            loading = false,
                            msg = "clone failed: ${e.message ?: e.javaClass.simpleName}",
                            msgKind = MsgKind.FAILED,
                        )
                    }
                }
                } finally {
                    // Release the dedup key BEFORE the mutex is released, so a
                    // next identical op arriving right after this one finishes
                    // is accepted instead of wrongly refused. The outer finally
                    // re-removes idempotently to cover cancel-while-queued
                    // (thrown before entering withLock).
                    inFlightWrites.remove(key)
                }
                }
            } finally {
                inFlightWrites.remove(key)
            }
        }
    }

    // Two-phase remove, phase 1 (PR-4 P1, issue #23): records what the user
    // asked to remove WITHOUT touching the device uninstall path. Resolves
    // the serial-bound verified target once (cross-source id + agreed serial
    // + display label, PR-33 R2) and stores it in UiState.pendingRemove so
    // MainActivity can render the confirmation dialog from state
    // (rotation-safe: the dialog is state driven, never re-sent). No `pm
    // uninstall` runs here by construction. Refused (no pending) while the
    // channel is not writable, when no dual target exists, or when the id is
    // confirmed but its serial cannot be established. A second request
    // overwrites the first (last wins); the confirm step re-validates, so a
    // stale overwrite cannot execute.
    // PR-33 R3 (P2 安裝狀態 + 時效): stores nowMs() as the consent
    // generation and refuses before storing when the package is not
    // installed (NotInstalled -> INFO 無需移除, Unknown -> CHANNEL fail
    // closed), so a no-op removal never opens a dialog.
    fun requestRemove(pkg: String) {
        viewModelScope.launch {
            // Validate input early without touching the device.
            if (pkg.isBlank()) {
                _state.update { it.copy(msg = "invalid package", msgKind = MsgKind.INVALID) }
                return@launch
            }
            val trimmed: String = pkg.trim()
            // Write dedup (PR-4 P23 stage 2, 比照 clone()): a second identical
            // requestRemove while the first is still in flight reports
            // "already in progress" instead of queueing another mutex waiter +
            // device round-trip. request(request-remove:) vs confirm(remove:@#)
            // vs clone(clone:) 三命名空間互不擋。Checked before the mutex hold
            // (Main-confined add); released in finally
            // so a cancelled/failed op never wedges the key.
            val key = opKey("request-remove", trimmed)
            if (!inFlightWrites.add(key)) {
                _state.update {
                    it.copy(msg = "already in progress: $trimmed", msgKind = MsgKind.INFO)
                }
                return@launch
            }
            try {
                opMutex.withLock {
                    try {
                    if (!isWritable()) return@withLock
                    _state.update { it.copy(loading = true, msg = "", msgKind = MsgKind.NONE) }
                    try {
                        when (val snap = onIo { repo.profilesSnapshot() }) {
                            is ProfilesSnapshot.ChannelError ->
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        pendingRemove = null,
                                        msg = "remove result unknown: " +
                                            "channel error: ${snap.detail}",
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            is ProfilesSnapshot.Loaded -> {
                                when (val target = onIo { repo.fetchDualTarget() }) {
                                    is DualTargetResult.NoDual ->
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                profiles = snap.profiles,
                                                pendingRemove = null,
                                                msg = "no dual profile",
                                                msgKind = MsgKind.NO_DUAL,
                                            )
                                        }
                                    is DualTargetResult.SerialUnavailable ->
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                profiles = snap.profiles,
                                                pendingRemove = null,
                                                msg = "無法驗證分身身分（serial 缺失），請重試",
                                                msgKind = MsgKind.NO_DUAL,
                                            )
                                        }
                                    is DualTargetResult.Confirmed -> {
                                        val dualId = target.target.userId
                                        val label =
                                            snap.profiles.firstOrNull { it.userId == dualId }?.label
                                        if (label == null) {
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "no dual profile",
                                                    msgKind = MsgKind.NO_DUAL,
                                                )
                                            }
                                        } else {
                                            // P2 安裝檢查 (PR-33 R3): only an
                                            // Installed package opens a
                                            // dialog; a confirmed-absent
                                            // package is a no-op INFO, an
                                            // uncheckable channel is CHANNEL
                                            // fail closed (never a dialog).
                                            when (val st = onIo { repo.verifyState(trimmed, dualId) }) {
                                                is VerifyState.Installed -> {
                                                    val pending = PendingRemove(
                                                        pkg = trimmed,
                                                        dualId = dualId,
                                                        dualSerial = target.target.serial,
                                                        profileLabel = label,
                                                        requestedAtMs = nowMs(),
                                                    )
                                                    _state.update {
                                                        it.copy(
                                                            loading = false,
                                                            profiles = snap.profiles,
                                                            pendingRemove = pending,
                                                            msg = "等待確認移除：$trimmed（user $dualId）",
                                                            msgKind = MsgKind.INFO,
                                                        )
                                                    }
                                                }
                                                is VerifyState.NotInstalled ->
                                                    _state.update {
                                                        it.copy(
                                                            loading = false,
                                                            profiles = snap.profiles,
                                                            pendingRemove = null,
                                                            msg = "未安裝，無需移除：$trimmed",
                                                            msgKind = MsgKind.INFO,
                                                        )
                                                    }
                                                is VerifyState.Unknown ->
                                                    _state.update {
                                                        it.copy(
                                                            loading = false,
                                                            profiles = snap.profiles,
                                                            pendingRemove = null,
                                                            msg = "remove result unknown: " +
                                                                "cannot verify $trimmed for user $dualId: ${st.detail}",
                                                            msgKind = MsgKind.CHANNEL,
                                                        )
                                                    }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _state.update {
                            it.copy(
                                loading = false,
                                pendingRemove = null,
                                msg = "remove failed: ${e.message ?: e.javaClass.simpleName}",
                                msgKind = MsgKind.FAILED,
                            )
                        }
                    }
                } finally {
                    // Release the dedup key BEFORE the mutex is released, so a
                    // next identical op arriving right after this one finishes
                    // is accepted instead of wrongly refused. The outer finally
                    // re-removes idempotently to cover cancel-while-queued
                    // (thrown before entering withLock).
                    inFlightWrites.remove(key)
                }
                }
            } finally {
                inFlightWrites.remove(key)
            }
        }
    }

    // Two-phase remove, cancel (PR-4 P1, issue #23): drops the pending
    // confirmation without running any uninstall. Synchronous (no device
    // I/O) so the dialog dismisses immediately, including the back-button /
    // outside-tap cancel path. A no-op when nothing is pending. Intentionally
    // leaves loading untouched so an in-flight confirm/refresh spinner is
    // never overwritten by the cancel.
    fun cancelRemove() {
        _state.update { cur ->
            val pending = cur.pendingRemove ?: return@update cur
            cur.copy(
                pendingRemove = null,
                msg = "已取消移除：${pending.pkg}",
                msgKind = MsgKind.INFO,
            )
        }
    }

    // Two-phase remove, phase 2 (PR-4 P1, issue #23): executes the pending
    // uninstall. Re-verifies under opMutex before running anything:
    // verified id still equals the requested target, the agreed serial still
    // matches (PR-33 R2: a delete+recreate reuses id+label with a fresh
    // serial, so a serial mismatch refuses without uninstalling), the
    // recorded profile label still matches (selection unchanged), and the
    // channel is still writable. Any mismatch clears the pending and refuses
    // with a message that asks for a fresh confirmation -- never executes a
    // moved target. Rapid double-taps execute at most once via the shared
    // inFlightWrites dedup (second concurrent confirm reports "already in
    // progress"). Drops the pending entry from cloned ONLY when the pinned
    // re-check confirms it absent (same fail-closed rule as the old direct
    // remove). 最終授權者是 repo pinned API，VM pre-check 只是 fail-fast.
    // PR-33 R3 (P2 安裝狀態 + 時效): after the serial/label checks, a stale
    // consent (nowMs() - requestedAtMs > REMOVE_CONFIRM_TTL_MS) is refused
    // first (cheap, no IO), then the package is re-verified Installed via
    // repo.verifyState (NotInstalled -> INFO 無需移除 without touching
    // cloned, Unknown -> CHANNEL fail closed) -- only then runs
    // removeVerifiedExpected.
    fun confirmRemove() {
        viewModelScope.launch {
            val pendingAtCall = _state.value.pendingRemove
            if (pendingAtCall == null) {
                // 終態成功不被遲到/重複 confirm 覆寫（結果釘住）；其餘才報無待確認。
                _state.update { cur ->
                    if (cur.msgKind == MsgKind.INFO && cur.msg.startsWith("removed: ")) cur
                    else cur.copy(msg = "沒有待確認的移除", msgKind = MsgKind.INVALID)
                }
                return@launch
            }
            // Write dedup 按世代 (PR-4 P23 stage 2, PR-33 R2 加 serial): key 綁定
            // pending 世代 (pkg@dualId#serial#requestedAtMs)，避免 v1 卡鎖時 v2
            // 合法確認被誤殺；a second concurrent confirm of the SAME
            // generation reports instead of queueing a second uninstall.
            val key = "remove:${pendingAtCall.pkg}@${pendingAtCall.dualId}" +
                "#${pendingAtCall.dualSerial}#${pendingAtCall.requestedAtMs}"
            if (!inFlightWrites.add(key)) {
                _state.update {
                    it.copy(
                        msg = "already in progress: ${pendingAtCall.pkg}",
                        msgKind = MsgKind.INFO,
                    )
                }
                return@launch
            }
            try {
                opMutex.withLock {
                    try {
                    // Re-read inside the lock: a cancelRemove() queued ahead
                    // of us must win (fail closed, run nothing).
                    val cur = _state.value.pendingRemove
                    if (cur == null) return@withLock
                    // Full-equality guard (full-equality 含 pkg/timestamp，覆寫即拒):
                    // any overwrite means a newer request replaced ours. 舊 confirm
                    // abort，保留新 pending，由使用者確認新目標：只清 loading，不動
                    // pendingRemove 與 msg（保留新 request 的 pending 與「等待確認」訊息），
                    // 與 post-IO 一致（preserve 式）。
                    if (cur != pendingAtCall) {
                        _state.update { it.copy(loading = false) }
                        return@withLock
                    }
                    if (!isWritable()) {
                        // isWritable already reported the reason into msg;
                        // drop the stale pending so the next attempt must
                        // request again (never execute a stale target).
                        _state.update { it.copy(pendingRemove = null) }
                        return@withLock
                    }
                    _state.update { it.copy(loading = true, msg = "", msgKind = MsgKind.NONE) }
                    try {
                        // Single-snapshot pinned flow inside the repository.
                        // onIo: the core retry settle wait must never run on Main.
                        val freshId = onIo { repo.resolveDualUserId() }
                        if (freshId < 0) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    msg = "no dual profile",
                                    msgKind = MsgKind.NO_DUAL,
                                )
                            }
                            return@withLock
                        }
                        if (freshId != cur.dualId) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    msg = "移除目標已變更（verified id " +
                                        "$freshId ≠ ${cur.dualId}），請重新確認",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                            return@withLock
                        }
                        // P1 serial binding (PR-33 R2): re-fetch the
                        // serial-bound target and compare the agreed serial.
                        // A delete+recreate under the dialog reuses id+label
                        // with a fresh serial: mismatch -- or no confirmable
                        // serial at all -- clears the pending and refuses
                        // (INVALID + 重新確認), running zero uninstalls.
                        // VM pre-check 只是 fail-fast，最終授權仍是 repo 的
                        // removeVerifiedExpected(expectedSerial)。
                        val second = onIo { repo.fetchDualTarget() }
                        // MINOR (Phase F review): distinguish an id drift on the
                        // second fetch from a missing/mismatched serial. Both
                        // stay INVALID + 重新確認 with zero uninstalls; only the
                        // message differs so the user can tell which pin moved.
                        if (second is DualTargetResult.Confirmed && second.target.userId != cur.dualId) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    msg = "移除目標已變更（verified id " +
                                        "${second.target.userId} ≠ ${cur.dualId}），請重新確認",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                            return@withLock
                        }
                        val freshSerial = (second as? DualTargetResult.Confirmed)?.target?.serial
                        if (freshSerial == null) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    msg = "移除目標無法驗證（serial 缺失），請重新確認",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                            return@withLock
                        }
                        if (freshSerial != cur.dualSerial) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    msg = "移除目標已變更（serial " +
                                        "$freshSerial ≠ ${cur.dualSerial}），請重新確認",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                            return@withLock
                        }
                        when (val snap = onIo { repo.profilesSnapshot() }) {
                            is ProfilesSnapshot.ChannelError ->
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        pendingRemove = null,
                                        msg = "remove result unknown: " +
                                            "channel error: ${snap.detail}",
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            is ProfilesSnapshot.Loaded -> {
                                val label =
                                    snap.profiles.firstOrNull { it.userId == cur.dualId }?.label
                                if (label == null) {
                                    _state.update {
                                        it.copy(
                                            loading = false,
                                            profiles = snap.profiles,
                                            pendingRemove = null,
                                            msg = "no dual profile",
                                            msgKind = MsgKind.NO_DUAL,
                                        )
                                    }
                                } else if (label != cur.profileLabel) {
                                    _state.update {
                                        it.copy(
                                            loading = false,
                                            profiles = snap.profiles,
                                            pendingRemove = null,
                                            msg = "移除目標已變更（$label ≠ " +
                                                "${cur.profileLabel}），請重新確認",
                                            msgKind = MsgKind.INVALID,
                                        )
                                    }
                                } else {
                                    // P2 時效 (PR-33 R3): no extra verify IO
                                    // (TTL 判在既有 IO 之後). A stale consent
                                    // is refused with zero uninstalls
                                    // (INVALID + 重新確認).
                                    if (nowMs() - cur.requestedAtMs > REMOVE_CONFIRM_TTL_MS) {
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                profiles = snap.profiles,
                                                pendingRemove = null,
                                                msg = "確認已過期，請重新確認移除",
                                                msgKind = MsgKind.INVALID,
                                            )
                                        }
                                        return@withLock
                                    }
                                    // P2 安裝重驗 (PR-33 R3): the package may
                                    // be gone already (external uninstall).
                                    // NotInstalled clears without uninstall
                                    // (INFO 無需移除, cloned left for refresh
                                    // to sync); Unknown clears CHANNEL fail
                                    // closed. Only Installed proceeds.
                                    when (val st = onIo { repo.verifyState(cur.pkg, cur.dualId) }) {
                                        is VerifyState.Installed -> Unit
                                        is VerifyState.NotInstalled -> {
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "未安裝，無需移除：${cur.pkg}",
                                                    msgKind = MsgKind.INFO,
                                                )
                                            }
                                            return@withLock
                                        }
                                        is VerifyState.Unknown -> {
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "remove result unknown: " +
                                                        "cannot verify ${cur.pkg} " +
                                                        "for user ${cur.dualId}: ${st.detail}",
                                                    msgKind = MsgKind.CHANNEL,
                                                )
                                            }
                                            return@withLock
                                        }
                                    }
                                    // Cancel-during-confirm race (B2): the IO above
                                    // (freshId + snapshot) may have interleaved a
                                    // cancelRemove(); re-check the pending is still
                                    // ours before running the uninstall. Clear only
                                    // loading here (只清 loading): keep pendingRemove
                                    // and msg so the cancel message or the new
                                    // request's pending survives.
                                    if (_state.value.pendingRemove != cur) {
                                        _state.update { it.copy(loading = false) }
                                        return@withLock
                                    }
                                    when (val res = onIo { repo.removeVerifiedExpected(cur.pkg, cur.dualId, cur.dualSerial) { _state.value.pendingRemove == cur } }) {
                                        is RemoveOutcome.Success ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    cloned = it.cloned.filterNot { p -> p == cur.pkg },
                                                    pendingRemove = null,
                                                    msg = "removed: ${cur.pkg}",
                                                    msgKind = MsgKind.INFO,
                                                )
                                            }
                                        is RemoveOutcome.NoDual ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "no dual profile",
                                                    msgKind = MsgKind.NO_DUAL,
                                                )
                                            }
                                        is RemoveOutcome.ChannelError ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "remove result unknown: " +
                                                        "channel error: ${res.detail}",
                                                    msgKind = MsgKind.CHANNEL,
                                                )
                                            }
                                        is RemoveOutcome.InvalidPackage ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "invalid package",
                                                    msgKind = MsgKind.INVALID,
                                                )
                                            }
                                        is RemoveOutcome.RemoveFailed ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = res.detail,
                                                    msgKind = MsgKind.FAILED,
                                                )
                                            }
                                        is RemoveOutcome.StillInstalled ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "remove failed: ${cur.pkg} " +
                                                        "still installed for user ${res.dualId}",
                                                    msgKind = MsgKind.VERIFY,
                                                )
                                            }
                                        is RemoveOutcome.VerifyUnknown ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "remove result unknown: " +
                                                        "cannot verify removal of ${cur.pkg} " +
                                                        "for user ${res.dualId}: ${res.detail}",
                                                    msgKind = MsgKind.CHANNEL,
                                                )
                                            }
                                        is RemoveOutcome.TargetMoved ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "移除目標已變更（verified id ${res.verified} ≠ ${res.expected}），請重新確認",
                                                    msgKind = MsgKind.INVALID,
                                                )
                                            }
                                        // P1 serial binding (PR-33 R2): the repo
                                        // refused a serial mismatch (or an
                                        // unavailable serial) with zero
                                        // uninstalls -- same INVALID +
                                        // 重新確認 shape as TargetMoved.
                                        is RemoveOutcome.SerialMismatch ->
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    msg = "移除目標已變更（serial " +
                                                        "${res.actual ?: "缺失"} ≠ " +
                                                        "${res.expected ?: "缺失"}），請重新確認",
                                                    msgKind = MsgKind.INVALID,
                                                )
                                            }
                                        is RemoveOutcome.Cancelled ->
                                            _state.update { it.copy(loading = false) }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _state.update {
                            it.copy(
                                loading = false,
                                pendingRemove = null,
                                msg = "remove failed: ${e.message ?: e.javaClass.simpleName}",
                                msgKind = MsgKind.FAILED,
                            )
                        }
                    }
                } finally {
                    // Release the dedup key BEFORE the mutex is released, so a
                    // next identical op arriving right after this one finishes
                    // is accepted instead of wrongly refused. The outer finally
                    // re-removes idempotently to cover cancel-while-queued
                    // (thrown before entering withLock).
                    inFlightWrites.remove(key)
                }
                }
            } finally {
                inFlightWrites.remove(key)
            }
        }
    }

    // Legacy single-tap entry (pre-P23): now only opens the confirmation
    // (phase 1). Kept so existing callers keep compiling; new code must call
    // requestRemove() explicitly. Never executes an uninstall.
    @Deprecated(
        message = "Two-phase removal: use requestRemove + confirmRemove; " +
            "remove() no longer executes an uninstall.",
        ReplaceWith("requestRemove(pkg)"),
    )
    fun remove(pkg: String) = requestRemove(pkg)

    // Re-probes elevation channels after a binder/permission lifecycle event
    // (Phase F3, issue #6): called from MainActivity's Shizuku binder/permission
    // listeners and from onResume. Serialized through opMutex like every other
    // op, so a re-probe never races an in-flight refresh/clone/remove; the
    // probe rewrites privilege/channelReady/msg (+loading while it runs, PR-3
    // stage 1 issue #24) and leaves profiles/cloned to the next refresh.
    //
    // Phase F4b-P1: when the persisted choice is AUTO, the startup pick is
    // re-resolved first (the environment may have changed since process
    // start: Shizuku started/granted, su appeared). An explicit non-AUTO
    // choice is never overridden here: only selectChannel() changes it.
    // P25: swapAutoLocked is probe-based (su present but probe-denied +
    // Shizuku proven -> degrade to SHIZUKU with a recorded reason).
    fun recheckPrivilege() {
        // Single-flight coalesce (P2-2): same gate shape as refresh(). A tap
        // while a recheck is in flight (including waiting on opMutex behind
        // init/probe) only records one trailing re-run, so binder/permission/
        // onResume bursts never pile an unbounded waiter queue on the mutex.
        // Main-confined check-and-set/clear (same reasoning as refresh()).
        viewModelScope.launch {
            if (recheckJob?.isActive == true) {
                recheckPending = true
                return@launch
            }
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            recheckJob = myJob
            try {
                opMutex.withLock {
                    // Phase D P2 busy (#24): same early-busy as reconnectLocked --
                    // swapAutoLocked() suspends before probeChannels() sets
                    // loading, so set it here first (idempotent, msg-preserving).
                    _state.update { it.copy(loading = true) }
                    // P25 Item 3: one probe per round — see reconnectLocked.
                    if (preference == ChannelPreference.AUTO) {
                        val probe = swapAutoLocked()
                        probeChannels(probe)
                    } else {
                        probeChannels()
                    }
                }
                while (recheckPending) {
                    recheckPending = false
                    opMutex.withLock {
                        // P25 Item 3: one probe per round — see reconnectLocked.
                        if (preference == ChannelPreference.AUTO) {
                            val probe = swapAutoLocked()
                            probeChannels(probe)
                        } else {
                            probeChannels()
                        }
                    }
                }
            } finally {
                if (recheckJob === myJob) recheckJob = null
            }
        }
    }

    // Single "reconnect and update" entry (P25, issue #25): probe (with Auto
    // degrade) + refresh in one mutex hold, so a Shizuku binder/permission
    // lifecycle event never races an in-flight refresh. MainActivity routes
    // both the Refresh button ("重新連線並更新") and the Shizuku
    // dead/authorized paths here.
    //
    // P25 Item 2: shares the refresh coalescing gate (same refreshJob plus a
    // reconnectPending upgrade flag) so a binder/button burst collapses to at
    // most one trailing re-run instead of queueing a waiter per tap (#24
    // bound). A reconnect arriving during a refresh upgrades the trailing
    // run to probe+refresh; concurrent reconnects collapse to one trailing
    // reconnect.
    fun reconnectAndRefresh() {
        viewModelScope.launch {
            if (refreshJob?.isActive == true) {
                reconnectPending = true
                return@launch
            }
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            refreshJob = myJob
            try {
                opMutex.withLock { reconnectLocked() }
                drainCoalesced()
            } finally {
                if (refreshJob === myJob) refreshJob = null
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
        // Latest-wins (P2-2): a new picker tap cancels the previous select
        // job, so rapid AUTO -> ROOT -> SHIZUKU taps collapse to the last
        // choice instead of queueing a mutex waiter per event. Eager assign:
        // cancel + launch + store happen synchronously on the caller thread
        // (Main in production, JUnit thread in tests), so the next tap
        // always cancels the right job even before either body starts.
        // Safety: the body is swap (persist + in-memory rebuild) + probe +
        // refresh -- all read-only/local, no pm install/uninstall write.
        // Cancellation unwinds via opMutex.withLock (mutex released) and every
        // catch in the body rethrows CancellationException.
        //
        // Cleanup runs in the launch-body finally (Main resumption point,
        // same shape as refresh()/recheckPrivilege()): no
        // invokeOnCompletion. No race: production callers are all
        // Main-serialized (picker callbacks), so the caller-thread store and
        // the Main finally never run concurrently; tests sequence taps with
        // scheduler drains for the same ordering. myJob comes from
        // currentCoroutineContext (not the scope receiver) for the
        // self-comparison; the eager store above already points selectJob at
        // the new job, so a cancelled predecessor sees a mismatch and never
        // clears its successor.
        selectJob?.cancel()
        val job = viewModelScope.launch {
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            try {
                opMutex.withLock {
                    try {
                        persistPreference?.invoke(pref)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Best effort: a failing store never blocks the swap.
                    }
                    swapToLocked(pref)
                    probeChannels()
                    refreshLocked()
                }
            } finally {
                if (selectJob === myJob) selectJob = null
            }
        }
        selectJob = job
    }

    // Single probe source (P25 Item 3, issue #25): explicit probes only its
    // own channel so an unrelated channel never triggers an auth prompt.
    // AUTO probes all elevators; explicit ROOT probes only SuElevator,
    // explicit SHIZUKU only ShizukuElevator, explicit LSPOSED (NoOp
    // placeholder) probes none and relies on hook diagnosis only. The su probe
    // runs `su -c id -u`, which on denial can block up to SU_EXEC_TIMEOUT_MS
    // (60 s) while `su` waits for user input -- halving the calls halves that
    // risk (timeout constant unchanged; fewer invocations, not a shorter
    // budget). Must run under opMutex.
    private suspend fun probeOnce(): ProbeOutcome {
        val targets: List<Elevator> = when (preference) {
            ChannelPreference.AUTO -> elevators
            ChannelPreference.ROOT -> elevators.filterIsInstance<SuElevator>()
            ChannelPreference.SHIZUKU -> elevators.filterIsInstance<ShizukuElevator>()
            ChannelPreference.LSPOSED -> emptyList()
        }
        var hasRoot = false
        var hasShizuku = false
        for (elevator in targets) {
            try {
                when (onIo { elevator.probe() }.level) {
                    Privilege.ROOT -> if (elevator is SuElevator) hasRoot = true
                    Privilege.SHELL -> if (elevator is ShizukuElevator) hasShizuku = true
                    Privilege.NONE -> Unit
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // A throwing probe means no channel from this elevator.
            }
        }
        return ProbeOutcome(hasRoot, hasShizuku)
    }

    // Re-resolves AUTO against live probes and swaps the gateway only when
    // the probe-based resolution actually changed (preserves the Shizuku
    // binder cache on the common no-change path). Must run under opMutex.
    // P25 probe-based (issue #25): su file presence alone never grants ROOT;
    // the elevators are probed for actual capability and resolveAutoProbe
    // decides: su present but probe-denied + Shizuku proven -> SHIZUKU with
    // autoDegradeNote recording the reason; both fail -> NOOP read-only;
    // both succeed -> ROOT (F4a order). Explicit choices never reach here.
    // P25 Item 3: probes once via probeOnce() and returns the result so the
    // caller (recheck/reconnect) reuses it in probeChannels instead of probing
    // a second time. Previously swapAutoLocked + probeChannels each probed all
    // elevators (idempotent degrade, double `su -c` cost and double
    // auth-prompt risk); now each round probes at most once.
    private suspend fun swapAutoLocked(): ProbeOutcome {
        // Callers guarantee preference == AUTO here, so probeOnce() probes all.
        val probe = probeOnce()
        degradeAutoLocked(probe.hasRoot, probe.hasShizuku)
        return probe
    }

    // Applies a probe result to the AUTO selection (P25). Records the degrade
    // reason and swaps the repo gateway without changing the persisted
    // preference (preference stays AUTO; currentChannel/resolvedGateway moves).
    // Elevator instances are reused so fake-injected tests and the Shizuku
    // binder cache survive the swap. Must run under opMutex.
    private suspend fun degradeAutoLocked(hasRoot: Boolean, hasShizuku: Boolean) {
        val suPresent = try {
            suPresentCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        val decision = resolveAutoProbe(suPresent, hasRoot, hasShizuku)
        autoDegradeNote = decision.degradeNote
        if (decision.channel != currentChannel) {
            swapToResolvedLocked(decision.channel)
        }
    }

    // Switches the repo gateway to an already-decided ResolvedChannel while
    // keeping preference==AUTO (P25 degrade path). Reuses the existing
    // suGateway/shizukuGateway (and their elevators) so probes keep verifying
    // the actual channels and the Shizuku binding is not dropped on a
    // no-op. Must run under opMutex.
    private suspend fun swapToResolvedLocked(desired: ResolvedChannel) {
        val old = activeGateway
        val newGateway: CmdGateway = when (desired) {
            ResolvedChannel.ROOT -> suGateway
            ResolvedChannel.SHIZUKU -> shizukuGateway
            ResolvedChannel.NOOP,
            ResolvedChannel.LSPOSED_PLACEHOLDER,
            -> NoOpGateway()
        }
        activeGateway = newGateway
        repo = DualRepository(newGateway)
        gatewayPrivilege = newGateway.privilege
        currentChannel = desired
        // Evidence generation bump (Phase A P2): same instances are reused
        // here, but the resolved channel moved, so a pre-degrade entry must
        // not leak across -- probeChannels rewrites both su/shizuku entries
        // right after with this new generation (AUTO full probe).
        evidenceGeneration++
        // repo/gateway 已換，快照即失效，fail closed：舊 pending 的 dualId/
        // label 是換通道前的驗證結果，繼續保留會讓 confirm 誤判，必須清掉，
        // 由使用者重新 request（與 swapToLocked 一致；confirm 仍有重驗雙保險）。
        // Phase 2 (P25 Item 4): gateway 已換，上次 refresh 的 `pm list users`
        // 結果即過期 -- pmReadable 回到 null（未知），僅 refresh 能重建
        // true/false；probe 永不寫入此欄（失去 readiness 時由 probeChannels
        // 再釘 null，見下）。
        _state.update {
            it.copy(
                channel = preference,
                resolvedGateway = desired,
                execIdentity = newGateway.privilege,
                pendingRemove = null,
                pmReadable = null,
            )
        }
        if (old !== newGateway && old is ShizukuCmdGateway) {
            try {
                old.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Best effort: the stale binding dies with the process anyway.
            }
        }
    }

    // Rebuilds repo/gatewayPrivilege/elevators for pref via the shared
    // assembleChannel (same path as the startup factory, so the two can
    // never disagree on Auto order or the LSPosed placeholder). The selected
    // gateway shares its instance with the matching elevator, so the next
    // probe verifies the actual channel the repo uses. Must run under
    // opMutex. A swapped-out Shizuku gateway releases its cached binding.
    // Builds through assembleChannelFn (production: the shared
    // assembleChannel; tests: fake-backed assemblies).
    // P25: an explicit choice clears the Auto degrade note (explicit never
    // crosses channels); an AUTO file-heuristic swap keeps the note until
    // probeChannels recomputes it from live probes.
    private suspend fun swapToLocked(pref: ChannelPreference) {
        // Snapshot BEFORE the swap: assembleChannel builds fresh su/shizuku
        // instances below, so the displaced idle Shizuku would otherwise leak
        // its cached UserService binding. suGateway/shizukuGateway always have
        // defaults (never lateinit), so this snapshot is safe on every path.
        val oldSu = suGateway
        val oldShizuku = shizukuGateway
        val suPresent = try {
            suPresentCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        val shizukuAuth = try {
            shizukuAuthCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        val asm = assembleChannelFn(pref, suPresent, shizukuAuth, appPackageName)
        val old = activeGateway
        activeGateway = asm.gateway
        repo = DualRepository(asm.gateway)
        gatewayPrivilege = asm.gateway.privilege
        suGateway = asm.suGateway
        shizukuGateway = asm.shizukuGateway
        elevators = listOf(SuElevator(suGateway), ShizukuElevator(shizukuGateway))
        preference = pref
        currentChannel = asm.channel
        if (pref != ChannelPreference.AUTO) autoDegradeNote = ""
        // Phase A P2: instance-bound evidence deliberately SURVIVES here as
        // STALE entries (unlike autoDegradeNote which clears on explicit):
        // suEvidence/shizukuEvidence keep pointing at the OLD instances with
        // the OLD generation, so the identity+generation gate below treats a
        // rebuilt (fresh, never-verified) gateway as UNKNOWN until THAT
        // instance itself passes a probe. A test factory reusing the same
        // fake keeps identity+generation equal and exercises the match branch.
        // Only bump when the su/shizuku instances actually changed (production
        // always rebuilds fresh, so every production swap bumps).
        if (suGateway !== oldSu || shizukuGateway !== oldShizuku) {
            evidenceGeneration++
        }
        // repo/gateway 已換，快照即失效，fail closed：舊 pending 的 dualId/
        // label 是換通道前的驗證結果，繼續保留會讓 confirm 誤判，必須清掉，
        // 由使用者重新 request。
        // Phase 2 (P25 Item 4): 同 swapToResolvedLocked -- 上次 refresh 的
        // `pm list users` 結果隨 gateway 過期，回到 null（未知），僅 refresh
        // 能重建 true/false。
        _state.update {
            it.copy(
                channel = pref,
                resolvedGateway = asm.channel,
                execIdentity = asm.gateway.privilege,
                degradeNote = if (pref == ChannelPreference.AUTO) autoDegradeNote else "",
                pendingRemove = null,
                pmReadable = null,
            )
        }
        if (old !== activeGateway && old is ShizukuCmdGateway) {
            try {
                old.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Best effort: the stale binding dies with the process anyway.
            }
        }
        // Release displaced idle Shizuku instances (assembleChannel builds
        // fresh ones every swap; the unpicked idle instance is otherwise
        // dropped without closing). Never the newly installed instances;
        // double-close with the block above is safe (close is idempotent).
        if (oldShizuku !== suGateway && oldShizuku !== shizukuGateway &&
            oldShizuku !== activeGateway && oldShizuku is ShizukuCmdGateway
        ) {
            try {
                oldShizuku.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Best effort: the stale binding dies with the process anyway.
            }
        }
        // Defensive: suGateway is a SuCmdGateway by construction, but tests
        // may inject any CmdGateway -- never leak a Shizuku hiding there.
        if (oldSu !== suGateway && oldSu !== shizukuGateway &&
            oldSu !== activeGateway && oldSu is ShizukuCmdGateway
        ) {
            try {
                oldSu.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Best effort: the stale binding dies with the process anyway.
            }
        }
    }

    // Probes elevation channels and routes the privilege decision via the
    // shared core router. Must run under opMutex.
    //
    // Phase F4a (issue #6): the factory injects elevators backed by the real
    // su/Shizuku gateways, so these probes verify the actual channels.
    // channelReady/writeReady is true ONLY when the repo's own gateway
    // channel proved itself (su repo needs the su/ROOT probe, Shizuku repo
    // the Shizuku/SHELL probe, NoOp repo never). A selected-but-unproven
    // channel stays read-only: fail closed, never a fake working channel.
    // P25 (issue #25): the result is split five ways -- preference
    // (channel) vs resolvedGateway (currentChannel) vs execIdentity
    // (gatewayPrivilege) vs anyProbePassed (probed subset proved any channel;
    // probe signal only, never proof of PM readability -- see UiState docs) vs
    // writeReady (selected channel proved). For AUTO the same probe results
    // drive degradeAutoLocked (su denied + Shizuku proven -> SHIZUKU with a
    // recorded reason; both fail -> NOOP; both succeed -> ROOT), so the
    // startup file heuristic is corrected on the first probe. Explicit
    // choices never cross channels here (only AUTO degrades).
    // P25 Item 3 (issue #25): probeOnce() probes only the selected channel
    // for explicit (ROOT -> su only, SHIZUKU -> Shizuku only, LSPOSED -> none;
    // hook diagnosis still runs below via direct reads + verified gateways,
    // so explicit SHIZUKU never runs `su -c id -u` and never triggers a su
    // auth prompt). For explicit, anyProbePassed/routed therefore reflect only the
    // selected channel (anyProbePassed == writeReady by construction: unrelated
    // channels are intentionally unprobed, never reported), not device-wide
    // capability. `reusedProbe` carries the swapAutoLocked result so AUTO
    // recheck/reconnect rounds probe at most once (no double `su -c` cost).
    // DEVICE-UNVERIFIED: probe/exec round-trips never ran on a device.
    // PR-3 stage 1 (issue #24): drives loading like every other op (entry sets
    // busy, the single exit update below clears it, the catch path clears it
    // too), and elevator probes run via onIo so a blocking gateway can never
    // stall the Main dispatcher heartbeat.
    // Instance-bound evidence gate (Phase A P2): a diagnostic `cat` may run
    // ONLY through the exact gateway instance that proved the capability in
    // the same generation. A rebuilt (fresh, never-verified) su/shizukuGateway
    // is naturally !== evidence.gateway (or older generation) and is excluded
    // until THAT instance passes its own probe -- this closes path A where the
    // old boolean cache drove `su -c cat` on a new instance (auth prompt).
    private fun isSuEvidenceUsable(): Boolean =
        suEvidence.hasCapability &&
            suEvidence.gateway === suGateway &&
            suEvidence.generation == evidenceGeneration

    private fun isShizukuEvidenceUsable(): Boolean =
        shizukuEvidence.hasCapability &&
            shizukuEvidence.gateway === shizukuGateway &&
            shizukuEvidence.generation == evidenceGeneration

    private suspend fun probeChannels(reusedProbe: ProbeOutcome? = null) {
        _state.update { it.copy(loading = true) }
        try {
            // Single probe per call: reuse the swap result when present,
            // otherwise probe once (preference-filtered inside probeOnce).
            val probe = reusedProbe ?: probeOnce()
            val hasRoot = probe.hasRoot
            val hasShizuku = probe.hasShizuku
            // Phase A P2: per-channel independent evidence (diagnostic-only,
            // never feeds routing/readiness). AUTO full probes rewrite BOTH
            // entries with the CURRENT gateways + generation; explicit
            // single-channel rounds rewrite ONLY their own entry (the other
            // stays unknown, never cleared) with the CURRENT gateway; LSPOSED
            // probes none and writes nothing. Failure writes
            // hasCapability=false on the same instance (so a Shizuku binder
            // death, which fails the next `pm list users` probe, immediately
            // disables that entry until a later passing probe re-enables it).
            when (preference) {
                ChannelPreference.AUTO -> {
                    suEvidence = DiagnosticEvidence(suGateway, hasRoot, evidenceGeneration)
                    shizukuEvidence = DiagnosticEvidence(shizukuGateway, hasShizuku, evidenceGeneration)
                }
                ChannelPreference.ROOT -> {
                    suEvidence = DiagnosticEvidence(suGateway, hasRoot, evidenceGeneration)
                }
                ChannelPreference.SHIZUKU -> {
                    shizukuEvidence = DiagnosticEvidence(shizukuGateway, hasShizuku, evidenceGeneration)
                }
                ChannelPreference.LSPOSED -> Unit
            }
            // P25: AUTO follows live probes, not the startup file heuristic.
            // Only the entry without a prior swap (init / selectChannel-AUTO)
            // degrades here; recheck/reconnect already degraded via
            // swapAutoLocked(reused) so this skips the second (idempotent but
            // redundant) degrade.
            if (preference == ChannelPreference.AUTO && reusedProbe == null) {
                degradeAutoLocked(hasRoot, hasShizuku)
            }
            val degradeNoteForState = if (preference == ChannelPreference.AUTO) autoDegradeNote else ""
            // LSPosed hook (ROOT) outranks su (ROOT) outranks Shizuku (SHELL);
            // the router encodes that priority, probes only feed it.
            // F4b-P2 + P1 (沿襲 #19): hasLsposed stays conservatively false for
            // ROUTING even though the marker now feeds the UI display below
            // (see hookNeutralRoute). Rationale: the marker is existence+scope
            // evidence from a file that survives reboots (see HookMarker), not
            // proof of a live, effective hook, so it must never grant privilege.
            // Detecting a truly effective hook needs a real device to verify.
            val routed = hookNeutralRoute(hasRoot = hasRoot, hasShizuku = hasShizuku)
            // H2/H4 pure gate (unit-tested by ChannelReadyTest): identical to
            // the inline when it replaces. Hook signal stays display-only (#19).
            val ready = channelReadyFor(gatewayPrivilege, hasRoot, hasShizuku)
            val anyProbePassed = hasRoot || hasShizuku
            // Hook diagnostic signal for the LSPosed picker row (F4b-P2, P1
            // split). Runs on EVERY probe -- including an explicit-LSPosed
            // selection with an unproven/NoOp write channel -- and reads through
            // INDEPENDENT diagnostic paths: direct MarkerReads first, then `cat`
            // over probe-verified real gateways only (a declared-ROOT/SHELL
            // gateway whose probe failed is not a usable path). LSPOSED probes
            // none, so the current hasRoot/hasShizuku are always false here;
            // filter by the matched instance-bound evidence instead (no new
            // `su -c id -u` probe, no new su auth prompt -- the `cat` itself
            // reuses already-authorized channels per the #27 contract).
            // A rebuilt instance is naturally mismatched (identity/generation)
            // and excluded until THAT instance is verified: needing a new su
            // must NOT auto-trigger a probe here, stay UNKNOWN (+ comment in
            // the UNKNOWN_NO_DIAGNOSTIC text). Cold-start LSPOSED (empty
            // evidence) yields empty usable, so diagnosis is direct-only and
            // UNKNOWN_NO_DIAGNOSTIC there is correct.
            // Diagnosis never feeds `ready`/`routed` above (hook-neutral) and
            // an unproven channel never executes anything except via a verified
            // diagnostic gateway. No true channel + unreadable direct reads maps
            // to UNKNOWN_NO_DIAGNOSTIC, never to inactive.
            val hook: HookDiagnosis = try {
                withContext(Dispatchers.IO) {
                    val direct = try {
                        hookDirectReads()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        listOf(MarkerRead.Denied, MarkerRead.Denied)
                    }
                    val candidates = try {
                        hookDiagnosticGateways()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        emptyList()
                    }
                    // LSPOSED: candidate must BE the currently-installed
                    // gateway AND that gateway's evidence must be usable
                    // (same instance, same generation, proven). This rejects
                    // both stale pre-rebuild instances and fresh post-rebuild
                    // instances that have not yet been verified (path A).
                    val suUsable = isSuEvidenceUsable()
                    val shizukuUsable = isShizukuEvidenceUsable()
                    val usable = when (preference) {
                        ChannelPreference.LSPOSED -> candidates.filter {
                            (it === suGateway && suUsable) ||
                                (it === shizukuGateway && shizukuUsable)
                        }
                        else -> candidates.filter {
                            (it.privilege == Privilege.ROOT && hasRoot) ||
                                (it.privilege == Privilege.SHELL && hasShizuku)
                        }
                    }
                    diagnoseHookState(direct, usable)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                HookDiagnosis(HookStatus.UNKNOWN_NO_DIAGNOSTIC)
            }
            val hookDesc = hook.displayText()
            // P25 messages: explicit choices never cross channels -- the text
            // states the selected-mode reason plus the optional recovery
            // entry (switch to Auto or tap "重新連線並更新").
            val notReadyMsg: String = when {
                currentChannel == ResolvedChannel.LSPOSED_PLACEHOLDER -> {
                    // LSPosed mode semantics (F4b-P2): the gateway is still
                    // the NoOp placeholder (whitelist policy branch pending),
                    // so the channel is honestly never ready. The message
                    // states the reason plus the hook signal; it never claims
                    // the channel can execute.
                    "LSPosed 通道為佔位（NoOp）：whitelist 政策分支待實作，" +
                        "寫入已拒絕；Hook 狀態：$hookDesc；" +
                        "如需寫入請切到 Auto/Root/Shizuku 後點「重新連線並更新」"
                }
                gatewayPrivilege == Privilege.NONE -> {
                    if (degradeNoteForState.isNotEmpty()) degradeNoteForState
                    else "通道未就緒：真通道已接線但未選中（NoOp 回退），目前為唯讀；" +
                        "請檢查授權後點「重新連線並更新」"
                }
                else -> {
                    "所選模式 $preference（${gatewayPrivilege} 通道）探測未通過，" +
                        "寫入已禁用（明選不跨通道）；" +
                        "可切到 Auto 或點「重新連線並更新」重試"
                }
            }
            // When degraded-but-ready (Auto -> SHIZUKU proven), surface the
            // degrade note as INFO so the reason is visible even though writes
            // work; otherwise ready means silent.
            val readyMsg = degradeNoteForState
            // dialog 開著時狀態列不洗掉確認提示：pendingRemove != null 且新
            // kind == NONE/INFO 才保留原 msg/msgKind，CHANNEL/FAILED 穿透。
            // 其他欄位照寫（loading/hook/ready/resolved/exec/caps 照常更新）。
            // Phase 2 (P25 Item 4): probe 只在失去 readiness 時使 pmReadable
            // 過期 -- ready false（NOOP 回退/撤銷/通道失敗/binder dead）即
            // null（未知），因舊 refresh 結果屬於舊通道/舊授權；ready true
            // 時保留（同通道仍可讀，快照未失效）。僅 refresh 能寫 true/false。
            _state.update { cur ->
                val base = cur.withHookDiagnosis(hook).copy(
                    loading = false,
                    channel = preference,
                    privilege = routed,
                    channelReady = ready,
                    resolvedGateway = currentChannel,
                    execIdentity = gatewayPrivilege,
                    anyProbePassed = anyProbePassed,
                    probeScope = preference.probeScope(),
                    writeReady = ready,
                    pmReadable = if (!ready) null else cur.pmReadable,
                    degradeNote = degradeNoteForState,
                    msg = if (ready) readyMsg else notReadyMsg,
                    msgKind = if (ready) {
                        if (readyMsg.isNotEmpty()) MsgKind.INFO else MsgKind.NONE
                    } else {
                        MsgKind.CHANNEL
                    },
                    )
                if (cur.pendingRemove != null &&
                    (base.msgKind == MsgKind.NONE || base.msgKind == MsgKind.INFO)
                ) {
                    base.copy(msg = cur.msg, msgKind = cur.msgKind)
                } else {
                    base
                }
            }
    } catch (e: Exception) {
        // Never swallow coroutine cancellation (see refreshLocked); probes
        // report via privilege/channelReady, but guard against
        // unexpected throws so loading never wedges busy. pendingRemove 開著
        // 時新 kind == NONE/INFO 才保留，CHANNEL/FAILED 穿透，不保留。
        // Phase 2: probe 拋錯（binder dead 等）同樣使 pmReadable 過期 -> null。
        if (e is CancellationException) throw e
        _state.update { cur ->
            val next = cur.copy(
                loading = false,
                pmReadable = null,
                msg = "probe failed: ${e.message ?: e.javaClass.simpleName}",
                msgKind = MsgKind.FAILED,
            )
            if (cur.pendingRemove != null &&
                (next.msgKind == MsgKind.NONE || next.msgKind == MsgKind.INFO)
            ) {
                next.copy(msg = cur.msg, msgKind = cur.msgKind)
            } else {
                next
            }
        }
    }
    }

    // 注意：帶副作用，拒絕時會寫入 msg+loading=false。
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
            // Status-aware hook text (P1): unknown renders as 狀態未知, never
            // as 未偵測到 (the latter is reserved for positively-read absence).
            val hookDesc = HookDiagnosis(cur.hookStatus, cur.hookScopes).displayText()
            _state.update {
                it.copy(
                    loading = false,
                    msg = "LSPosed 通道為佔位（NoOp）：whitelist 政策分支待實作，" +
                        "寫入已拒絕；Hook 狀態：$hookDesc；" +
                        "如需寫入請切到 Auto/Root/Shizuku 後點「重新連線並更新」",
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return false
        }
        if (!cur.channelReady) {
            // P25: explicit never crosses channels -- state the selected-mode
            // reason plus the optional recovery entry.
            val reason = if (cur.degradeNote.isNotEmpty() && cur.channel == ChannelPreference.AUTO) {
                cur.degradeNote
            } else if (gatewayPrivilege == Privilege.NONE) {
                "通道未就緒：真通道已接線但未選中（NoOp 回退），目前為唯讀；" +
                    "請檢查授權後點「重新連線並更新」"
            } else {
                "所選模式 ${cur.channel}（${gatewayPrivilege} 通道）探測未通過，" +
                    "寫入已禁用（明選不跨通道）；可切到 Auto 或點「重新連線並更新」重試"
            }
            _state.update {
                it.copy(
                    loading = false,
                    msg = reason,
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return false
        }
        if (cur.privilege == Privilege.NONE || gatewayPrivilege == Privilege.NONE) {
            // Unreachable by construction: channelReady true implies a proven
            // non-NONE gateway, but kept as a fail-closed guard. 中文化以免
            // 使用者看到英文原文。
            _state.update {
                it.copy(
                    loading = false,
                    msg = "唯讀：無可用權限通道，寫入已禁用；" +
                        "請檢查授權後點「重新連線並更新」",
                    msgKind = MsgKind.INVALID,
                )
            }
            return false
        }
        return true
    }
}

// P1 hook-neutral routing (issue #6, 沿襲 #19, 配合 #27): privilege routing
// always runs with hasLsposed = false, no matter what the hook diagnosis
// says. A present marker is existence+scope evidence, not proof of a live,
// effective hook, so it must never grant privilege. probeChannels routes
// through this function; HookDiagnosisTest locks the contract.
fun hookNeutralRoute(hasRoot: Boolean, hasShizuku: Boolean): Privilege =
    routePrivilege(hasLsposed = false, hasShizuku = hasShizuku, hasRoot = hasRoot)

// Pure display mapping: applies a hook diagnosis to the UI state without
// touching routing or readiness fields (channel/privilege/channelReady are
// preserved by construction). probeChannels uses this; the hook-neutral lock
// test pins it for every HookStatus.
fun UiState.withHookDiagnosis(diagnosis: HookDiagnosis): UiState = copy(
    hookStatus = diagnosis.status,
    hookActive = diagnosis.active,
    hookScopes = diagnosis.scopes,
)

// Phase C P2 pure display helpers (MainActivity render uses these; JVM tests
// assert the state fields plus these keys so the Activity stays untested).
// Tristate: NONE (zero probe) -> 未探測, never 未通過; otherwise the probed
// subset decides 通過/未通過. The scope label + 本次路由 wording keep the UI
// from claiming device total capability.
fun UiState.probeResultMark(): String = when {
    probeScope == ProbeScope.NONE -> "未探測"
    anyProbePassed -> "探測通過"
    else -> "探測未通過"
}

// Full 本次探測 status key for state-layer assertions and render.
fun UiState.probeStatusText(): String =
    "本次探測[${probeScope.displayLabel()}]${probeResultMark()}"

// Independent PM readability key (refresh only). null = 未知, true = 可讀,
// false = 不可讀. True readability = (pmReadable == true) plus writeReady.
fun UiState.pmReadableText(): String = when (pmReadable) {
    true -> "PM可讀"
    false -> "PM不可讀"
    null -> "PM未知"
}

// Phase 2 P3 (P25 Item 4): 雙條件就緒標記 -- writeReady（探測門）+ pmReadable
//（PM 讀取）缺一不可。writeReady false -> 未就緒（唯讀）不變；writeReady true
// 但 pmReadable false/null -> 探測通過但 PM 未證明可讀，絕不顯示單純已就緒。
// 僅 (true + true) 顯示已就緒。
fun UiState.readyMark(): String = when {
    !writeReady -> "未就緒（唯讀）"
    pmReadable == true -> "已就緒"
    pmReadable == false -> "探測通過，PM不可讀"
    else -> "探測通過，PM未確認"
}
