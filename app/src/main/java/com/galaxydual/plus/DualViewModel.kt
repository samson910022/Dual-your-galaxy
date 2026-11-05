// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.DualProfile
import com.galaxydual.core.DualIdResolution
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
import com.galaxydual.core.PmOutputParser
import com.galaxydual.core.routePrivilege
import com.galaxydual.hook.HookMarker
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
import java.util.concurrent.atomic.AtomicBoolean

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
// - requestedAtMs: 單調時鐘讀數 (nowMonoMs，同意生成，與 TTL 同源，
 //   不受 wall 回撥影響). confirmRemove rejects a stale
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
//
// PR-6 P2 (#27 後半)：另有 inventory (來源/目標身份/查詢時間/新鮮度)、
// lastOperation (opId/appKey/pinnedTarget/phase/time，不被普通 probe 清除)、
// hookQueriedAtMs/hookMarkerTimeMs/hookViaGateway (scope/time 證據，沿用
// HookStateProvider) 與 diagnose (穩定碼 + 繁中摘要/下一步 + 可展開 detail)。
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
    // P25 Item 4: probe signal only, **NOT** proof that `pm list users` is
    // readable (探測通過 ≠ PM可讀; Phase 3 fail-closed: PM不可讀時寫入被拒,
    // 以 CHANNEL 呈現, 見 isWritable PM gate). Counter-example A: SuElevator
    // returns hasRoot=true on `id -u == 0` even when its `pm list users`
    // fails (dualUserId=-1), so anyProbePassed is true while refresh reports
    // ChannelError. True PM readability is decided by refresh
    // (profilesSnapshot Loaded vs ChannelError, listClonedPackages null vs
    // list), not by this flag.
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
    // when the probes overrode the file heuristic downward (su denied ->
    // Shizuku) or when both probes failed (read-only); recovery/upgrade back
    // to ROOT is silent by design (empty note, readyMsg empty -> silent).
    // Explicit choices clear it.
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
    // Hook 時間證據 (PR-6 P2 #27 後半)：App 何時跑的診斷 (wall-clock)。
    val hookQueriedAtMs: Long = 0L,
    // marker 內 timeMs 行 (HookMarker.parseTimeMs)，null=無/不可解析。
    val hookMarkerTimeMs: Long? = null,
    // 診斷是否走 privileged `cat` (沿用 HookStateProvider viaGateway)。
    val hookViaGateway: Boolean = false,
    // All Android profiles parsed from `pm list users`.
    val profiles: List<DualProfile> = emptyList(),
    // Packages currently cloned into the dual profile (MVP tracks by name).
    val cloned: List<String> = emptyList(),
    // 清單快照 (PR-6 P2 #27 後半)：來源/目標身份/查詢時間/新鮮度。
    val inventory: InventorySnapshot = InventorySnapshot.unknown(),
    // 最近一次 clone/remove 歷史 (PR-6 P2 #27 後半)：普通 probe 永不清除；
    // probe 成功不清 UNKNOWN，需下一次 pinned verify 才覆寫。
    val lastOperation: LastOperation? = null,
    // 診斷事件 (PR-6 P2 #27 後半)：穩定碼 + 繁中摘要/下一步 + 可展開 detail。
    val diagnose: DiagnoseEvent? = null,
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
    // Retained diagnostic instances (Phase B1, issue #34 re-review #7 B1):
    // swap drops the current su/shizukuGateway objects (production rebuilds
    // fresh every swap), which orphans the instance-bound evidence above and
    // used to leave LSPOSED (zero probe) with direct-reads only. To keep an
    // already-authorized channel usable for DIAGNOSIS across a swap,
    // swapToLocked/swapToResolvedLocked move each still-usable evidence
    // instance here (su/shizuku one slot each) before the swap; probeChannels
    // moves a newly verified current instance here on hasCapability=true and
    // clears the slot on hasCapability=false (revocation fail-closed, so a
    // later LSPOSED round never `cat`s through a revoked instance). A
    // retained instance is DIAGNOSTIC-ONLY: it never feeds routing/readiness
    // (writeReady stays false under LSPOSED), never authorizes a fresh
    // instance (no old evidence is ever applied to a new gateway), and never
    // triggers a fresh `su` probe (LSPOSED probeOnce stays empty). A retained
    // instance may be expired (auth revoked while on LSPOSED, where no
    // re-probe runs): a failing/denied retained `cat` then maps to UNKNOWN
    // via diagnoseHookState (fail closed, no retry, no upgrade) -- never to
    // a fake ACTIVE or INACTIVE.
    private var retainedSuDiagnostic: CmdGateway? = null,
    private var retainedShizukuDiagnostic: CmdGateway? = null,
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
    // Phase B1 adds the retained verified instances for the LSPOSED branch
    // below (the retained slot survives rebuilds that this list does not
    // track). Non-LSPOSED keeps the existing current-only filter untouched,
    // so an explicit empty list still means "no gateway diagnosis".
    // Never throws by contract; probeChannels guards with try/catch.
    private val hookDirectReads: () -> List<MarkerRead> = { defaultDirectMarkerReads() },
    private val hookDiagnosticGateways: () -> List<CmdGateway> = { listOf(suGateway, shizukuGateway) },
    // Clock source for the remove-consent TTL (PR-33 R3, P2; S3 uses a
    // monotonic clock in production) and the PR-6 P2 #27 後半
    // inventory/hook/lastOperation 時間證據。Defaults to the real wall clock so
    // JVM tests need no Android runtime; tests inject a controllable fake.
    // Constructor stays Android-free; the factory wires elapsedRealtime.
    // NOTE: p27 dual-clock (C1) 以 nowWallMs/nowMonoMs 接手；nowMs 暫留供
    // 未遷移呼叫端，最終統一移除。
    private val nowMs: () -> Long = System::currentTimeMillis,
    // Phase N2 (issue #34 re-review): injectable Shizuku teardown observer.
    // Production default null (real ShizukuCmdGateway.abandon() runs inline);
    // tests inject a counting lambda so FakeShizuku (SHELL privilege, not a
    // ShizukuCmdGateway) closings are observable without touching Shizuku
    // statics. Invoked for every Shizuku-like target (see isShizukuLike):
    // displaced-idle closes in swap paths plus the final cleared snapshot.
    // Never throws (guarded); never drives readiness/diagnosis.
    private val gatewayTeardown: ((CmdGateway) -> Unit)? = null,
    // 雙時鐘 (C1)：wall-clock 供 inventory/queryTime/hook/lastOp/老化/顯示
    // (存在性證據，可格式化)；monotonic 供同意時效 (requestedAtMs/TTL，不受
    // wall 回撥影響)。JVM 預設皆為 currentTimeMillis (免 Android runtime，
    // 測試注入可控 fake)；生產由 factory 分別接線 (wall 走 currentTimeMillis，
    // mono 走 elapsedRealtime)。Constructor stays Android-free。
    private val nowWallMs: () -> Long = System::currentTimeMillis,
    // 同意時效單調時鐘 (見上)：request 的 requestedAtMs 與 confirm 的 TTL
    // 差值皆經此取時， wall 回撥不得延長有效期。
    private val nowMonoMs: () -> Long = System::currentTimeMillis,
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
    // P2-1 (issue #34 review #10): narrow privileged-diagnosis gate.
    // reconnectPendingPrivileged ORs every coalesced reconnect request's
    // allowPrivilegedHookDiagnosis flag so a privileged request is never
    // downgraded to auto when it collapses behind an in-flight op. Post P2-1
    // every reconnect caller passes false (a reconnect tap is NOT consent for
    // privileged `cat`), so the trailing round stays direct-only; the flag is
    // consumed per round so a stale grant can never leak into a later round.
    private var reconnectPendingPrivileged = false

    // Recheck single-flight (P2-2): same coalescing gate as refresh(). Binder/
    // permission/onResume bursts collapse to at most one trailing re-probe.
    // Main-confined (see class doc).
    private var recheckJob: Job? = null
    private var recheckPending = false
    // P2-1: trailing recheck inherits privilege when any collapsed request
    // was privileged (OR, same as reconnect above). Only
    // requestHookRedDiagnosis passes true; auto lifecycle stays false.
    private var recheckPendingPrivileged = false

    // Foreground single-flight (Phase B P0): onForeground bursts collapse to
    // at most one trailing run, mirroring refresh()/recheckPrivilege().
    // Main-confined (see class doc).
    private var foregroundJob: Job? = null
    private var foregroundPending = false

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
    //
    // CE convention (F5): a cancelled job never clears loading itself --
    // every catch rethrows CancellationException before touching state. The
    // live successor's entry set (leading busy, F2) owns the flag; a terminal
    // scope-cancel discards state with the ViewModel, so no wedge survives.
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

    // Phase N2 Shizuku binding teardown (issue #34 re-review), hardened by
    // Phase B P2-2: ViewModel has no onCleared, so the retained + current
    // Shizuku instances skipped by the swap guards (old !== retained) leaked
    // their UserService bindings across Activity finish while the process
    // lived on. This override snapshots every Shizuku-like slot (active/su/
    // shizuku/retained, identity deduped) and releases each exactly once.
    // abandon() is non-suspend (marks closed + inline drop when uncontended,
    // deferred bounded-wait drop on the gateway-owned cleanup scope when
    // contended -- never the already-cancelled viewModelScope), so onCleared
    // -- which cannot suspend with viewModelScope already cancelled -- needs
    // no independent closeScope. Idempotent via teardownOnce (dispose() twice
    // is a no-op, and abandon()/close() themselves release at most once, so a
    // repeated dispose never double-unbinds); diagnostic instances are NOT
    // closed early -- only this cleared path closes them all.
    private val teardownOnce = AtomicBoolean(false)

    private fun isShizukuLike(gw: CmdGateway): Boolean =
        gw is ShizukuCmdGateway || gw.privilege == Privilege.SHELL

    private fun collectTeardownTargets(): List<CmdGateway> {
        val all = listOfNotNull(
            activeGateway,
            suGateway,
            shizukuGateway,
            retainedSuDiagnostic,
            retainedShizukuDiagnostic,
        )
        val distinct = mutableListOf<CmdGateway>()
        for (gw in all) {
            if (distinct.none { it === gw }) distinct.add(gw)
        }
        return distinct.filter { isShizukuLike(it) }
    }

    private fun teardownOne(gw: CmdGateway) {
        try {
            if (gw is ShizukuCmdGateway) gw.abandon()
        } catch (_: Exception) {
            // Best effort: the stale binding dies with the process anyway.
        }
        try {
            gatewayTeardown?.invoke(gw)
        } catch (_: Exception) {
            // Best effort: observer must never break teardown.
        }
    }

    private fun teardownShizukuBindings() {
        if (!teardownOnce.compareAndSet(false, true)) return
        val targets = try {
            collectTeardownTargets()
        } catch (_: Exception) {
            emptyList()
        }
        for (gw in targets) teardownOne(gw)
    }

    // Test-visible entry: onCleared is protected, so JVM tests drive disposal
    // through here (same idempotent body).
    fun dispose() {
        teardownShizukuBindings()
    }

    override fun onCleared() {
        teardownShizukuBindings()
        super.onCleared()
    }

    // PR-6 P4 (#27 架構整理)：clone/confirmRemove 各 pinned 結果分支共用的
    // LastOperation+diagnose 配對建構。內部統一 nowWallMs() 取時與 detail 截斷
    // (capDetail)；diagnose 映射沿用既有 (呼叫端傳入已按原規則算好的事件，
    // 含 Success 分支的 null)，此函數只負責配對、不重解 outcome。
    // 行為等價依據：capDetail(x) 與原 x.take(MAX_DIAG_DETAIL_CHARS) 同輸出；
    // 每分支仍恰取時一次 (原各分支 val now = nowWallMs() 改由此函數內取，
    // update 同步執行故 tick 順序不變)；每處 phase/detail/diagnose 與原字面
    // 一字不差 (見各呼叫端傳入值)。
    // Stage C C4 優先順序：OK 判據為 diagnose==null &&
    // (lastOperation==null || phase != FAILED)；FRESH 成功清碼時 FAILED 歷史
    // 由 refreshLocked 同步清除 (見該處)，本函數不自清，僅配對。
    private fun recordOp(
        opId: String,
        appKey: String,
        pinnedTarget: Int,
        phase: OpPhase,
        detail: String,
        diagnose: DiagnoseEvent?,
    ): Pair<LastOperation, DiagnoseEvent?> {
        val op = LastOperation(
            opId = opId,
            appKey = appKey,
            pinnedTarget = pinnedTarget,
            phase = phase,
            timeMs = nowWallMs(),
            detail = capDetail(detail),
        )
        return op to diagnose
    }

    init {
        // Probe elevation channels first; later ops queue behind this, so the
        // first refresh always sees the routed privilege, never a default.
        viewModelScope.launch {
            opMutex.withLock { probeChannels(allowPrivilegedHookDiagnosis = false) }
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
        // F2/P3 leading busy: mark busy synchronously at launch (msg-preserving)
        // so a waiter queued behind opMutex never exposes loading=false before
        // its locked body re-sets it idempotently below. CE convention: a
        // cancelled job never clears loading itself (see class doc); the live
        // successor's entry set owns the flag, and a terminal scope-cancel
        // discards state with the ViewModel.
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
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
    // stay probe-free). P2-1: the trailing reconnect inherits the privileged
    // flag (privileged wins) so a privileged request collapsed behind an auto
    // op still diagnoses with privilege; pure-auto bursts stay false. The flag
    // is consumed per round (same shape as the recheck loop below): a
    // request arriving during trailing round N is read by round N+1, never
    // via a stale snapshot held across rounds.
    private suspend fun drainCoalesced() {
        while (refreshPending || reconnectPending) {
            val doReconnect = reconnectPending
            // Privileged wins for THIS round only: consume the flag now so a
            // later auto request cannot inherit an earlier grant.
            val trailingPrivileged = reconnectPendingPrivileged
            refreshPending = false
            reconnectPending = false
            reconnectPendingPrivileged = false
            // P3 trailing busy (same shape as the recheck loop): hold busy
            // across the mutex handoff so the trailing round never exposes
            // loading=false while it waits on opMutex behind another op.
            _state.update { it.copy(loading = true) }
            if (doReconnect) {
                opMutex.withLock { reconnectLocked(allowPrivilegedHookDiagnosis = trailingPrivileged) }
            } else {
                opMutex.withLock { refreshLocked() }
            }
        }
    }

    // Probe (with Auto degrade) + refresh body shared by reconnectAndRefresh()
    // and the coalesced trailing run. Must run under opMutex: callers hold
    // it, so this never launches or locks itself. P2-1:
    // allowPrivilegedHookDiagnosis gates privileged diagnostic `cat` (see
    // probeChannels): every reconnect caller passes false (a reconnect tap is
    // NOT consent for privileged diagnosis; LSPOSED stays direct-only).
    private suspend fun reconnectLocked(allowPrivilegedHookDiagnosis: Boolean = false) {
        // Phase D P2 busy (#24): mark busy BEFORE any suspending probe IO.
        // swapAutoLocked() suspends on `su -c id -u` (auth prompt can block)
        // while probeChannels() is the first place that used to set
        // loading=true -- during that window state.loading stayed false and
        // MainActivity left its buttons enabled. Idempotent with the
        // probeChannels/refreshLocked sets below.
        // 取消窗口：early-busy 先於 swap，若取消落在 swap 內 su -c 懸掛，
        // 永不到 probe/refresh 的清除點 → 同殘 true；外層補取消清 loading
        // (僅取消路徑，成功/失敗語意不動；生產 job 只隨 scope 清除被取消，
        // 無手動 cancel，此為保險)。
        _state.update { it.copy(loading = true) }
        try {
        // P25 Item 3: one probe per round — swapAutoLocked probes once and
        // returns the result for probeChannels to reuse (previously each
        // probed all elevators: double `su -c` cost + double auth-prompt risk).
        // Phase 2: 鏈式共用一次 loading (probe 保留 busy，refresh 結尾才清除)。
        // P2-4: swap 前記錄 resolved，納入 probe 的 FRESH 失效判斷。
        val resolvedBeforeOp = currentChannel
        if (preference == ChannelPreference.AUTO) {
            val probe = swapAutoLocked()
            probeChannels(
                probe,
                allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis,
                keepLoadingOnExit = true,
                resolvedBeforeOp = resolvedBeforeOp,
            )
        } else {
            probeChannels(
                allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis,
                keepLoadingOnExit = true,
                resolvedBeforeOp = resolvedBeforeOp,
            )
            refreshLocked()
        } catch (e: CancellationException) {
            _state.update { it.copy(loading = false) }
            throw e
        }
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
        // PR-6 P2 (#27 後半): inventory/lastOperation/diagnose 由各結果分支顯式
        // 維護；Stage C C4 例外：FRESH 成功清碼 (diagnose→null) 時同步清除
        // FAILED lastOperation (verified 失敗已被新鮮 ground truth 取代，三者
        // 一致)；UNKNOWN 永不清除 (需下一次 pinned verify 才覆寫)；SUCCESS 保留。
        // OK 判據：diagnose==null && (lastOperation==null || phase != FAILED)。
        _state.update {
            if (it.pendingRemove != null) it.copy(loading = true)
            else it.copy(loading = true, msg = "", msgKind = MsgKind.NONE)
        }
        try {
            when (val snap = onIo { repo.profilesSnapshot() }) {
                is ProfilesSnapshot.ChannelError -> {
                    val now = nowWallMs()
                    _state.update { cur ->
                        val (combinedMsg, combinedKind) = combineWithDegradeNote(
                            "channel error: ${sanitizeForDisplay(snap.detail)}",
                            MsgKind.CHANNEL,
                            cur.degradeNote,
                        )
                        val next = cur.copy(
                            loading = false,
                            // 理由：通道失敗時快照不可信，保留舊 profiles 供追溯
                            // (比照 null-snap 保留舊值分支，不謊稱空清單)。
                            profiles = cur.profiles,
                            // Phase C P2: `pm list users` proven unreadable.
                            pmReadable = false,
                            inventory = cur.inventory.toStaleOrUnknown(
                                now = now,
                                source = DualRepository.CMD_LIST_USERS,
                                // UNKNOWN 分支回退保留舊身份：傳入已知 target，
                                // UI 在未知中仍可見上次目標。
                                targetId = cur.inventory.targetId,
                            ),
                            diagnose = snap.toDiagnoseEvent(),
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
                is ProfilesSnapshot.Loaded -> {
                    val profiles = snap.profiles
                    // Verified identity only: the id comes from the repo's
                    // cross-source resolver, never from the pm-list label
                    // (display-only by contract), so the display layer makes
                    // no label judgment and shares one verified target with
                    // the listing below.
                    // P2-5 (verified -1 誤判修正)：-1 一律視為「無法確認」。
                    // 舊碼用 Int 哨兵 (resolveDualUserId 回 -1) 再以快照有無
                    // 猜 NO_DUAL/CHANNEL_ERROR：typed 通道單獨失敗時也會回 -1，
                    // 快照正常有戶就被誤判為「確認無 dual」並清空 cloned。
                    // 現改走 typed 入口 resolveDualUserIdTyped()：Confirmed
                    // (正值) 才取 id 查核；Absent（通道乾淨且確認無 dual）且
                    // 快照可用才報 NO_DUAL 並清空；其餘（Unknown 全域，含舊
                    // -1；或 Absent 但快照為空不可信）一律保留舊 cloned、報
                    // CHANNEL_ERROR，不謊稱無 dual。
                    // M2 pm 否決：Absent 前 repo 另收 pm 戶集合否決 typed 漏戶
                    //（null 表無快照不否決；此處快照可用故傳 profiles id 集合）。
                    val idRes = onIo { repo.resolveDualUserIdTyped(pmIds = profiles.map { it.userId }.toSet()) }
                    val dualId = (idRes as? DualIdResolution.Confirmed)?.userId ?: NO_TARGET_ID
                    if (dualId <= NO_TARGET_ID) {
                        val now = nowWallMs()
                        // 真正確認無 dual 的唯一條件：typed 稱 Absent 且快照
                        // 可用（列得出戶）。Unknown 全域走 CHANNEL_ERROR；
                        // 空快照（連 owner 都列不出，快照不可信）時即使 typed
                        // 稱 Absent 也不得報 NO_DUAL（快照本身已證通道有問題）。
                        val confirmedAbsent =
                            idRes is DualIdResolution.Absent && profiles.isNotEmpty()
                        _state.update { cur ->
                            val next = if (confirmedAbsent) {
                                val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                    "no dual profile",
                                    MsgKind.NO_DUAL,
                                    cur.degradeNote,
                                )
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    cloned = emptyList(),
                                    // Phase C P2: profiles parsed (readable)
                                    // but no dual target -- still true
                                    // (`pm list users` succeeded).
                                    pmReadable = true,
                                    inventory = InventorySnapshot(
                                        source = DualRepository.CMD_LIST_USERS,
                                        targetId = NO_TARGET_ID,
                                        queryTimeMs = now,
                                        freshness = InventoryFreshness.UNKNOWN,
                                        count = 0,
                                    ),
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.NO_DUAL,
                                        techDetail = "typed resolution absent",
                                    ),
                                    msg = combinedMsg,
                                    msgKind = combinedKind,
                                )
                            } else {
                                val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                    "refresh result unknown: " +
                                        "cannot confirm dual profile",
                                    MsgKind.CHANNEL,
                                    cur.degradeNote,
                                )
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    // Phase C P2: `pm list users` returned
                                    // (Loaded) so readable; only the dual
                                    // identity is unverifiable here.
                                    pmReadable = true,
                                    inventory = cur.inventory.toStaleOrUnknown(
                                        now = now,
                                        source = DualRepository.CMD_LIST_USERS,
                                        targetId = cur.inventory.targetId,
                                    ),
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.CHANNEL_ERROR,
                                        techDetail = when {
                                            idRes is DualIdResolution.Unknown &&
                                                idRes.detail.isNotBlank() ->
                                                "verified dual id unknown: " +
                                                    idRes.detail
                                            idRes is DualIdResolution.Absent ->
                                                "profiles snapshot empty " +
                                                    "despite typed absent"
                                            else -> "verified dual id unknown"
                                        },
                                    ),
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
                    } else {
                        val groundTruth = onIo { repo.listClonedPackages(dualId) }
                        // listClonedPackages==null 原因鑑別：只在 null 路徑加跑
                        // 唯讀的 profilesSnapshot + resolveDualUserIdTyped
                        // (成功路徑零額外開銷)。通道失敗->CHANNEL_ERROR
                        // (techDetail 寫明)；只有正值且不同的新 verified ID
                        // (Confirmed 且 != dualId) 才報 drift
                        // (VERIFY_MISMATCH，detail 攜新舊 targetId，inventory
                        // 帶新目標，UI 可見新目標)；複查 Unknown/Absent
                        // (P2-5：舊 Int -1 走此路) 與 id 一致者一律維持
                        // VERIFY_UNKNOWN，不誤報 MISMATCH。
                        // Stage C C6：此處複查刻意傳 pmIds=null——本路徑 Unknown/Absent
                        // 一律 VERIFY_UNKNOWN，不授權 Absent 清單 (Absent 需快照可用
                        // + typed 乾淨，見 refresh 首跳)；null 僅省一次快照傳參，
                        // 語意仍為未知，不清空。
                        val nullSnap: ProfilesSnapshot? =
                            if (groundTruth == null) onIo { repo.profilesSnapshot() } else null
                        // Stage C C6：同上，刻意 null——Absent 在此亦只報 UNKNOWN。
                        val nullVerified: DualIdResolution? =
                            if (groundTruth == null) onIo { repo.resolveDualUserIdTyped() } else null
                        _state.update { cur ->
                            val now = nowWallMs()
                            val next = if (groundTruth == null) {
                                // 未知：保留舊 cloned，inventory 舊資料標 STALE
                                // (FRESH->STALE；否則 UNKNOWN，傳入的 targetId
                                // 不再丟棄)，供上層重查。
                                // Phase C P2: pmReadable 由本次 null 鑑別重查決定
                                // (ChannelError->false，未漂移 Loaded->true)，
                                // 非一律 true。
                                val snapErr = (nullSnap as? ProfilesSnapshot.ChannelError)?.detail
                                val freshProfiles =
                                    (nullSnap as? ProfilesSnapshot.Loaded)?.profiles ?: profiles
                                if (snapErr != null) {
                                    val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                        "refresh result unknown: " +
                                            "channel error: ${sanitizeForDisplay(snapErr)}",
                                        MsgKind.CHANNEL,
                                        cur.degradeNote,
                                    )
                                    cur.copy(
                                        loading = false,
                                        profiles = freshProfiles,
                                        // Phase C P2: 重查連 `pm list users`
                                        // 都失敗 -> 不可讀。
                                        pmReadable = false,
                                        inventory = cur.inventory.toStaleOrUnknown(
                                            now = now,
                                            source = PmOutputParser.buildListPackages(dualId),
                                            targetId = dualId,
                                        ),
                                        diagnose = diagnoseEvent(
                                            DiagnoseCode.CHANNEL_ERROR,
                                            techDetail = "profiles snapshot " +
                                                "channel failed: $snapErr",
                                        ),
                                        msg = combinedMsg,
                                        msgKind = combinedKind,
                                    )
                                } else if (nullVerified is DualIdResolution.Confirmed &&
                                    nullVerified.userId != dualId
                                ) {
                                    // Confirmed 保證正值（repo 拒收 0/負值），
                                    // 故此分支即「正值且不同」的新身份。
                                    val newTarget = nullVerified.userId
                                    val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                        "refresh verification mismatch: " +
                                            "target $dualId -> $newTarget",
                                        MsgKind.VERIFY,
                                        cur.degradeNote,
                                    )
                                    cur.copy(
                                        loading = false,
                                        profiles = freshProfiles,
                                        // Phase C P2: 重查 Loaded 證明 `pm list
                                        // users` 可讀 (僅 clone 清單未知)。
                                        pmReadable = true,
                                        inventory = cur.inventory.toStaleOrUnknown(
                                            now = now,
                                            source = PmOutputParser.buildListPackages(dualId),
                                            targetId = newTarget,
                                        ),
                                        diagnose = diagnoseEvent(
                                            DiagnoseCode.VERIFY_MISMATCH,
                                            techDetail = "verified id drifted: " +
                                                "listed $dualId but now $newTarget",
                                        ),
                                        msg = combinedMsg,
                                        msgKind = combinedKind,
                                    )
                                } else {
                                    val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                        "refresh result unknown: " +
                                            "cannot list clones for user $dualId",
                                        MsgKind.CHANNEL,
                                        cur.degradeNote,
                                    )
                                    cur.copy(
                                        loading = false,
                                        profiles = freshProfiles,
                                        // Phase C P2: 同上，重查 Loaded -> 可讀。
                                        pmReadable = true,
                                        inventory = cur.inventory.toStaleOrUnknown(
                                            now = now,
                                            source = PmOutputParser.buildListPackages(dualId),
                                            targetId = dualId,
                                        ),
                                        diagnose = diagnoseEvent(
                                            DiagnoseCode.VERIFY_UNKNOWN,
                                            techDetail = "cannot list clones for user $dualId",
                                        ),
                                        msg = combinedMsg,
                                        msgKind = combinedKind,
                                    )
                                }
                            } else if (groundTruth.isEmpty()) {
                                // 契約 (Stage C C4，選 (c))：FRESH 成功清碼時同步
                                // 歸檔 FAILED 歷史——diagnose 將清 null (phase !=
                                // UNKNOWN) 且 phase==FAILED 時 lastOperation 同步
                                // 清為 null，三者一致 (diagnose null + 無 FAILED
                                // 歷史 + 就緒 msg)；OK 判據為 diagnose==null &&
                                // lastOperation==null 或 phase != FAILED。
                                // UNKNOWN 仍保留 (需下一次 pinned verify 才覆寫)；
                                // SUCCESS 保留 (成功歷史非錯誤，與 OK 共存)。
                                val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                    "no clones installed",
                                    MsgKind.INFO,
                                    cur.degradeNote,
                                )
                                // Stage C C4：diagnose 清 null 且 FAILED 時同步清歷史。
                                val clearFailed =
                                    cur.lastOperation?.phase != OpPhase.UNKNOWN &&
                                        cur.lastOperation?.phase == OpPhase.FAILED
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    cloned = emptyList(),
                                    pmReadable = true,
                                    inventory = InventorySnapshot(
                                        source = PmOutputParser.buildListPackages(dualId),
                                        targetId = dualId,
                                        queryTimeMs = now,
                                        freshness = InventoryFreshness.FRESH,
                                        count = 0,
                                    ),
                                    lastOperation = if (clearFailed) {
                                        null
                                    } else {
                                        cur.lastOperation
                                    },
                                    diagnose = if (cur.lastOperation?.phase == OpPhase.UNKNOWN) {
                                        cur.diagnose
                                    } else {
                                        null
                                    },
                                    msg = combinedMsg,
                                    msgKind = combinedKind,
                                )
                            } else {
                                // 契約同上 (Stage C C4)：UNKNOWN 保留原 diagnose；
                                // FAILED 隨 FRESH 成功清碼同步清除 (見空分支註解)；
                                // SUCCESS 保留。
                                val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                    "",
                                    MsgKind.NONE,
                                    cur.degradeNote,
                                )
                                // Stage C C4：diagnose 清 null 且 FAILED 時同步清歷史。
                                val clearFailed =
                                    cur.lastOperation?.phase != OpPhase.UNKNOWN &&
                                        cur.lastOperation?.phase == OpPhase.FAILED
                                cur.copy(
                                    loading = false,
                                    profiles = profiles,
                                    cloned = groundTruth,
                                    pmReadable = true,
                                    inventory = InventorySnapshot(
                                        source = PmOutputParser.buildListPackages(dualId),
                                        targetId = dualId,
                                        queryTimeMs = now,
                                        freshness = InventoryFreshness.FRESH,
                                        count = groundTruth.size,
                                    ),
                                    lastOperation = if (clearFailed) {
                                        null
                                    } else {
                                        cur.lastOperation
                                    },
                                    diagnose = if (cur.lastOperation?.phase == OpPhase.UNKNOWN) {
                                        cur.diagnose
                                    } else {
                                        null
                                    },
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
        } catch (e: CancellationException) {
            // 協程取消必須透傳：吞掉會導致 viewModelScope 取消失效、
            // loading 卡住且 refreshPending 尾隨永不執行。
            // Phase B P0 (B3)：取消路徑補 loading=false，免殘 true。
            _state.update { it.copy(loading = false) }
            throw e
        } catch (e: Exception) {
            // Never swallow coroutine cancellation: rethrow so cancel stays
            // cooperative and refresh() coalescing still drains correctly.
            if (e is CancellationException) throw e
            // Gateways report via CmdResult, but guard against unexpected throws.
            // pendingRemove 開著時只保留非錯誤提示（NONE/INFO），CHANNEL/FAILED 穿透。
            // PR-6 P2: 舊資料標 STALE，不謊稱新鮮；lastOperation 不動。
            // UNKNOWN 分支回退保留舊身份：傳入已知 target，UI 仍可見上次目標。
            val now = nowWallMs()
            _state.update { cur ->
                val (combinedMsg, combinedKind) = combineWithDegradeNote(
                    "refresh failed: ${sanitizeForDisplay(e.message ?: "refresh failed")}",
                    MsgKind.FAILED,
                    cur.degradeNote,
                )
                val next = cur.copy(
                    loading = false,
                    // Phase C P2: unexpected throw -- readability unknown
                    // (fail closed via null; never claim false proof).
                    pmReadable = null,
                    inventory = cur.inventory.toStaleOrUnknown(
                        now = now,
                        // 有意變更：例外路徑來源由 "refresh" 改名 "refresh(exception)"，
                        // 與命令字串區分 (正常分支皆為真實命令)，理由：匯出可追溯性。
                        source = "refresh(exception)",
                        targetId = cur.inventory.targetId,
                    ),
                    diagnose = diagnoseEvent(
                        DiagnoseCode.OP_FAILED,
                        techDetail = sanitizeForDisplay(e.message ?: "refresh failed"),
                    ),
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
    //
    // PR-6 P2 (#27 後半): 每個 pinned 結果都記錄 lastOperation (phase
    // SUCCESS/FAILED/UNKNOWN) + diagnose 穩定碼；普通 probe 永不清除。
    fun clone(pkg: String) {
        viewModelScope.launch {
            // P2-8 輸入有界：先 trim，空白拒絕；trim 後超過
            // MAX_PACKAGE_INPUT_CHARS 即 INVALID_INPUT 拒絕執行（取代舊
            // take(256) 靜默截斷——截斷會把超長輸入變形成另一個包名再執行）。
            // 字元集校驗刻意不在此重做：ShellEscaper 政策由 core 擁有
            // (PmOutputParser builders + DualRepository；
            // ViewModel 無既有 ShellEscaper 使用模式)，此處只做空白/長度檢查；
            // 非法字元由 repo 回 InvalidPackage (同碼 INVALID_INPUT)。
            // 空白/超長拒絕皆不建 opKey、不碰裝置、不寫 lastOperation，
            // 只設 diagnose+msg（與 requestRemove 一致）。
            val trimmed: String = pkg.trim()
            // Validate input early without touching the device.
            if (trimmed.isBlank()) {
                _state.update {
                    it.copy(
                        msg = "invalid package",
                        msgKind = MsgKind.INVALID,
                        diagnose = diagnoseEvent(DiagnoseCode.INVALID_INPUT),
                    )
                }
                return@launch
            }
            if (trimmed.length > MAX_PACKAGE_INPUT_CHARS) {
                _state.update {
                    it.copy(
                        msg = "輸入過長（超過 $MAX_PACKAGE_INPUT_CHARS 字元），已拒絕",
                        msgKind = MsgKind.INVALID,
                        diagnose = diagnoseEvent(
                            DiagnoseCode.INVALID_INPUT,
                            techDetail = "package input length ${trimmed.length} " +
                                "exceeds $MAX_PACKAGE_INPUT_CHARS",
                        ),
                    )
                }
                return@launch
            }
            // Write dedup (PR-3 stage 1, issue #24): a second identical clone
            // while the first is still in flight reports "already in progress"
            // instead of queueing another mutex waiter + device round-trip.
            // Checked before the mutex hold (Main-confined add); released in
            // finally so a cancelled/failed op never wedges the key.
            val key = opKey("clone", trimmed)
            if (!inFlightWrites.add(key)) {
                _state.update {
                    // 理由 (本機回音原文)：使用者自輸包名在自己螢幕回顯用
                    // sanitizeUserEcho (只壓 control+有界，不遮蔽)；遮蔽只在
                    // 診斷 techDetail/匯出。儲存的 opKey 保持 raw。
                    it.copy(
                        msg = "already in progress: ${sanitizeUserEcho(trimmed)}",
                        msgKind = MsgKind.INFO,
                    )
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
                            // P1-1 target 重建：成功結果 dualId != inventory.targetId 時
                            // 不能只附註警告。FRESH 快照聲稱舊目標可信，但寫入已釘在新目標：
                            // 先降原 inventory 為 STALE（不清 cloned，只降新鮮度；跨目標合併
                            // 結果 B 不得留在舊 target 清單——當次寫入包已排除在舊清單外，
                            // 整單標 STALE 待重查），再對新 verified target 做全量 refresh
                            //（已在 opMutex 內，直接調 refreshLocked，不重入加鎖）。
                            // 觸發條件僅 FRESH mismatch（UNKNOWN/STALE 本已非新鮮，維持原
                            // count 對齊待下次 refresh，避免冷啟動 UNKNOWN 覆寫成功提示）。
                            // rebuiltNote 保留原邏輯不變（FRESH + 舊 id + 上次釘舊 id 才附註）。
                            val newTarget = res.dualId
                            val needRebuildRefresh =
                                _state.value.inventory.freshness == InventoryFreshness.FRESH &&
                                    _state.value.inventory.targetId != newTarget
                            _state.update { cur ->
                                val current: List<String> =
                                    if (needRebuildRefresh) {
                                        cur.cloned.filterNot { p -> p == trimmed }
                                    } else if (cur.cloned.contains(trimmed)) {
                                        cur.cloned
                                    } else {
                                        cur.cloned + trimmed
                                    }
                                // 全量新鮮度只由 refresh 賦予：成功寫入僅對齊筆數
                                // (count=當前筆數)；非重建時 freshness/source/target/
                                // queryTime 一律不動，重建時先降 STALE 待下行 refresh 覆寫。
                                // target 重建提示：FRESH 快照目標與本次 verified
                                // 目標不一致、且上次操作釘在舊 id 上時，detail
                                // 附加需重新查證提示。
                                val oldTarget = cur.inventory.targetId
                                val rebuiltNote =
                                    if (cur.inventory.freshness == InventoryFreshness.FRESH &&
                                        oldTarget != res.dualId &&
                                        cur.lastOperation?.pinnedTarget == oldTarget
                                    ) {
                                        " (target 已重建，需重新查證)"
                                    } else {
                                        ""
                                    }
                                val (op, diag) = recordOp(
                                    opId = key,
                                    appKey = trimmed,
                                    pinnedTarget = res.dualId,
                                    phase = OpPhase.SUCCESS,
                                    // 理由 (m2)：顯示用 detail 回音消毒；
                                    // 儲存的 opId/appKey 保持 raw 不動。
                                    detail = "cloned: ${sanitizeForDisplay(trimmed)}$rebuiltNote",
                                    diagnose = null,
                                )
                                val degradedInventory =
                                    if (needRebuildRefresh &&
                                        cur.inventory.freshness == InventoryFreshness.FRESH
                                    ) {
                                        cur.inventory.copy(
                                            freshness = InventoryFreshness.STALE,
                                            count = current.size,
                                        )
                                    } else {
                                        cur.inventory.copy(count = current.size)
                                    }
                                cur.copy(
                                    loading = false,
                                    cloned = current,
                                    inventory = degradedInventory,
                                    lastOperation = op,
                                    diagnose = diag,
                                    // 理由 (本機回音原文)：msg 顯示真包名，
                                    // 遮蔽只在 detail/techDetail/匯出。
                                    msg = "cloned: ${sanitizeUserEcho(trimmed)}",
                                    msgKind = MsgKind.INFO,
                                )
                            }
                            if (needRebuildRefresh) {
                                refreshLocked()
                            }
                        }
                        is CloneOutcome.NoDual -> {
                            // 寫前拒絕 (install 未執行)：只設 diagnose+msg，
                            // 不覆寫 lastOperation (與空白輸入分支一致)。
                            // M1 typed 鑑別 (沿 refreshLocked)：僅 Absent+快照可用
                            // 才報 NO_DUAL；Unknown 一律 CHANNEL_ERROR (含 Absent
                            // 但空快照：快照本身已證通道有問題)。
                            // 理由 (M3 寫前拒絕誠實新鮮度)：FRESH 經 toStaleOrUnknown
                            // 降為 STALE (不清 cloned/lastOperation，只降新鮮度)。
                            // M2 pm 否決：Absent 前傳 pm 戶集合（無快照傳 null 不否決）。
                            val snap = onIo { repo.profilesSnapshot() }
                            val pmIds = (snap as? ProfilesSnapshot.Loaded)?.profiles?.map { it.userId }?.toSet()
                            val idRes = onIo { repo.resolveDualUserIdTyped(pmIds = pmIds) }
                            val snapshotUsable =
                                (snap as? ProfilesSnapshot.Loaded)?.profiles?.isNotEmpty() == true
                            val confirmedAbsent =
                                idRes is DualIdResolution.Absent && snapshotUsable
                            val channelDetail = (snap as? ProfilesSnapshot.ChannelError)?.detail
                            val typedDetail = (idRes as? DualIdResolution.Unknown)?.detail
                            val now = nowWallMs()
                            _state.update { cur ->
                                val degraded =
                                    if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                        cur.inventory.toStaleOrUnknown(
                                            now = now,
                                            source = cur.inventory.source,
                                            targetId = cur.inventory.targetId,
                                        )
                                    } else {
                                        cur.inventory
                                    }
                                if (!confirmedAbsent) {
                                    cur.copy(
                                        loading = false,
                                        inventory = degraded,
                                        diagnose = diagnoseEvent(
                                            DiagnoseCode.CHANNEL_ERROR,
                                            techDetail = when {
                                                typedDetail?.isNotBlank() == true ->
                                                    "verified dual id unknown: $typedDetail"
                                                channelDetail != null ->
                                                    "verified dual id unknown: $channelDetail"
                                                !snapshotUsable ->
                                                    "verified dual id unknown " +
                                                        "and profiles snapshot empty"
                                                else ->
                                                    "verified dual id unknown"
                                            },
                                        ),
                                        msg = if (channelDetail != null) {
                                            "clone result unknown: " +
                                                "channel error: ${sanitizeForDisplay(channelDetail)}"
                                        } else {
                                            "clone result unknown: " +
                                                "cannot confirm dual profile"
                                        },
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        inventory = degraded,
                                        diagnose = res.toDiagnoseEvent(),
                                        msg = "no dual profile",
                                        msgKind = MsgKind.NO_DUAL,
                                    )
                                }
                            }
                        }
                        is CloneOutcome.ChannelError -> {
                            // 寫前拒絕 (install 未執行)：只設 diagnose+msg，
                            // 不覆寫 lastOperation。
                            // 理由 (M3)：FRESH 經 toStaleOrUnknown 降 STALE (不清 cloned/lastOperation)。
                            val now = nowWallMs()
                            _state.update { cur ->
                                val degraded =
                                    if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                        cur.inventory.toStaleOrUnknown(
                                            now = now,
                                            source = cur.inventory.source,
                                            targetId = cur.inventory.targetId,
                                        )
                                    } else {
                                        cur.inventory
                                    }
                                cur.copy(
                                    loading = false,
                                    inventory = degraded,
                                    diagnose = res.toDiagnoseEvent(),
                                    msg = "clone result unknown: " +
                                        "channel error: ${sanitizeForDisplay(res.detail)}",
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                        is CloneOutcome.InvalidPackage -> {
                            // 寫前拒絕 (install 未執行)：只設 diagnose+msg，
                            // 不覆寫 lastOperation (remove InvalidPackage 同，
                            // 見統一規則：install/uninstall 未執行者不碰歷史)。
                            // 理由 (M3)：FRESH 經 toStaleOrUnknown 降 STALE。
                            val now = nowWallMs()
                            _state.update { cur ->
                                val degraded =
                                    if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                        cur.inventory.toStaleOrUnknown(
                                            now = now,
                                            source = cur.inventory.source,
                                            targetId = cur.inventory.targetId,
                                        )
                                    } else {
                                        cur.inventory
                                    }
                                cur.copy(
                                    loading = false,
                                    inventory = degraded,
                                    diagnose = res.toDiagnoseEvent(),
                                    msg = "invalid package",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                        }
                        is CloneOutcome.InstallFailed -> {
                            _state.update {
                                val (op, diag) = recordOp(
                                    opId = key,
                                    appKey = trimmed,
                                    pinnedTarget = NO_TARGET_ID,
                                    phase = OpPhase.FAILED,
                                    detail = res.detail,
                                    diagnose = res.toDiagnoseEvent(),
                                )
                                it.copy(
                                    loading = false,
                                    lastOperation = op,
                                    diagnose = diag,
                                    msg = sanitizeForDisplay(res.detail),
                                    msgKind = MsgKind.FAILED,
                                )
                            }
                        }
                        is CloneOutcome.VerifyFailed -> {
                            _state.update {
                                val (op, diag) = recordOp(
                                    opId = key,
                                    appKey = trimmed,
                                    pinnedTarget = res.dualId,
                                    phase = OpPhase.FAILED,
                                    detail = "not installed for user ${res.dualId}",
                                    diagnose = res.toDiagnoseEvent(trimmed),
                                )
                                it.copy(
                                    loading = false,
                                    lastOperation = op,
                                    diagnose = diag,
                                    // 理由 (本機回音原文)：msg 的包名回音用
                                    // sanitizeUserEcho 顯示原文；儲存的 opId/appKey
                                    // 保持 raw，diagnose techDetail 已截斷不動。
                                    msg = "clone verification failed: " +
                                        "${sanitizeUserEcho(trimmed)} not installed " +
                                        "for user ${res.dualId}",
                                    msgKind = MsgKind.VERIFY,
                                )
                            }
                        }
                        is CloneOutcome.VerifyUnknown -> {
                            _state.update {
                                val (op, diag) = recordOp(
                                    opId = key,
                                    appKey = trimmed,
                                    pinnedTarget = res.dualId,
                                    phase = OpPhase.UNKNOWN,
                                    detail = res.detail,
                                    diagnose = res.toDiagnoseEvent(),
                                )
                                it.copy(
                                    loading = false,
                                    lastOperation = op,
                                    diagnose = diag,
                                    // 理由 (本機回音原文)：msg 的包名回音顯示原文；
                                    // res.detail 仍經 sanitizeForDisplay (不可信)。
                                    msg = "clone result unknown: " +
                                        "cannot verify ${sanitizeUserEcho(trimmed)} " +
                                        "for user ${res.dualId}: " +
                                        sanitizeForDisplay(res.detail),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // 協程取消必須透傳：外層 finally 會釋放 inFlightWrites，
                    // 此處若吞掉會把取消誤記為 UNKNOWN 操作歷史。
                    // Phase B P0 (B3)：取消路徑補 loading=false，免殘 true。
                    _state.update { it.copy(loading = false) }
                    throw e
                } catch (e: Exception) {
                    // Never swallow coroutine cancellation (see refreshLocked).
                    if (e is CancellationException) throw e
                    // 例外逃逸：寫後是否執行未知 -> phase UNKNOWN (非 FAILED)，
                    // pinnedTarget 保留當次已知 dualId (取自 inventory，無則哨兵)，
                    // diagnose 為 VERIFY_UNKNOWN (寫後未查證，需重新查證，
                    // 非 OP_FAILED)。
                    val knownTarget =
                        _state.value.inventory.targetId.takeIf { it > NO_TARGET_ID } ?: NO_TARGET_ID
                    _state.update {
                        val (op, diag) = recordOp(
                            opId = key,
                            appKey = trimmed,
                            pinnedTarget = knownTarget,
                            phase = OpPhase.UNKNOWN,
                            detail = sanitizeForDisplay(e.message ?: "clone failed"),
                            diagnose = diagnoseEvent(
                                DiagnoseCode.VERIFY_UNKNOWN,
                                techDetail = sanitizeForDisplay(e.message ?: "clone failed"),
                            ),
                        )
                        it.copy(
                            loading = false,
                            lastOperation = op,
                            diagnose = diag,
                            msg = "clone failed: " +
                                "${sanitizeForDisplay(e.message ?: "clone failed")}",
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
    // PR-33 R3 (P2 安裝狀態 + 時效): stores nowMonoMs() as the consent
    // generation and refuses before storing when the package is not
    // installed (NotInstalled -> INFO 無需移除, Unknown -> CHANNEL fail
    // closed), so a no-op removal never opens a dialog.
    fun requestRemove(pkg: String) {
        viewModelScope.launch {
            // P2-8 輸入有界（同 clone）：先 trim，空白/超長 (trim 後超過
            // MAX_PACKAGE_INPUT_CHARS) 即 INVALID_INPUT 拒絕，不建 opKey、
            // 不碰裝置、不寫 lastOperation，只設 diagnose+msg。
            // 字元集校驗刻意不在此重做：ShellEscaper 政策由 core 擁有
            // (PmOutputParser builders + DualRepository；
            // ViewModel 無既有 ShellEscaper 使用模式)，此處只做空白/長度檢查；
            // 非法字元由後續 repo 流回 InvalidPackage (同碼 INVALID_INPUT)。
            // 空白/超長拒絕不碰裝置 (與 clone 一致)，diagnose 與 clone 對齊。
            val trimmed: String = pkg.trim()
            // Validate input early without touching the device.
            if (trimmed.isBlank()) {
                _state.update {
                    it.copy(
                        msg = "invalid package",
                        msgKind = MsgKind.INVALID,
                        diagnose = diagnoseEvent(DiagnoseCode.INVALID_INPUT),
                    )
                }
                return@launch
            }
            if (trimmed.length > MAX_PACKAGE_INPUT_CHARS) {
                _state.update {
                    it.copy(
                        msg = "輸入過長（超過 $MAX_PACKAGE_INPUT_CHARS 字元），已拒絕",
                        msgKind = MsgKind.INVALID,
                        diagnose = diagnoseEvent(
                            DiagnoseCode.INVALID_INPUT,
                            techDetail = "package input length ${trimmed.length} " +
                                "exceeds $MAX_PACKAGE_INPUT_CHARS",
                        ),
                    )
                }
                return@launch
            }
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
                    // 理由 (本機回音原文)：使用者自輸包名在自己螢幕回顯用
                    // sanitizeUserEcho (只壓 control+有界，不遮蔽)；遮蔽只在
                    // 診斷 techDetail/匯出。儲存的 opKey 保持 raw。
                    it.copy(
                        msg = "already in progress: ${sanitizeUserEcho(trimmed)}",
                        msgKind = MsgKind.INFO,
                    )
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
                            is ProfilesSnapshot.ChannelError -> {
                                // 理由 (M3 寫前拒絕誠實新鮮度)：寫前拒絕時 FRESH 快照已不可信，
                                // 經 toStaleOrUnknown 降為 STALE (不清 cloned/lastOperation，只降新鮮度)。
                                val now = nowWallMs()
                                _state.update { cur ->
                                    val degraded =
                                        if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                            cur.inventory.toStaleOrUnknown(
                                                now = now,
                                                source = cur.inventory.source,
                                                targetId = cur.inventory.targetId,
                                            )
                                        } else {
                                            cur.inventory
                                        }
                                    cur.copy(
                                        loading = false,
                                        pendingRemove = null,
                                        inventory = degraded,
                                        // 與 clone 對齊：寫前通道失敗補通道碼。
                                        diagnose = snap.toDiagnoseEvent(),
                                        msg = "remove result unknown: " +
                                            "channel error: ${sanitizeForDisplay(snap.detail)}",
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            is ProfilesSnapshot.Loaded -> {
                                when (val target = onIo { repo.fetchDualTarget() }) {
                                    is DualTargetResult.NoDual -> {
                                        // Stage C C6：DualTargetResult.NoDual 命名誤導——
                                        // 它僅表「未確認」(含通道失敗/證據不足)，不等於
                                        // 「確認無 dual」；呼叫端不得據此清空/報 NO_DUAL，
                                        // 需 Absent 語意一律走 typed 入口
                                        // resolveDualUserIdTyped()->Absent (見下)。
                                        // M1 typed 鑑別 (沿 refreshLocked/clone-NoDual)：
                                        // 僅 Absent+快照可用才為真 NO_DUAL；Unknown
                                        // 一律 CHANNEL_ERROR (含 Absent 但空快照)。
                                        // Loaded 空快照 (連 owner 都列不出，快照不可信)
                                        // ->CHANNEL_ERROR 且不清 cloned。
                                        // 理由 (M3)：寫前拒絕時 FRESH 降 STALE (不清 cloned/lastOperation)。
                                        // M2 pm 否決：Absent 前傳 pm 戶集合否決 typed 漏戶。
                                        val snapshotUsable = snap.profiles.isNotEmpty()
                                        val idRes = onIo { repo.resolveDualUserIdTyped(pmIds = snap.profiles.map { it.userId }.toSet()) }
                                        val confirmedAbsent =
                                            idRes is DualIdResolution.Absent && snapshotUsable
                                        val typedDetail =
                                            (idRes as? DualIdResolution.Unknown)?.detail
                                        val now = nowWallMs()
                                        _state.update { cur ->
                                            val degraded =
                                                if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                                    cur.inventory.toStaleOrUnknown(
                                                        now = now,
                                                        source = cur.inventory.source,
                                                        targetId = cur.inventory.targetId,
                                                    )
                                                } else {
                                                    cur.inventory
                                                }
                                            if (!confirmedAbsent) {
                                                cur.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    inventory = degraded,
                                                    diagnose = diagnoseEvent(
                                                        DiagnoseCode.CHANNEL_ERROR,
                                                        techDetail = when {
                                                            typedDetail?.isNotBlank() == true ->
                                                                "verified dual id unknown: $typedDetail"
                                                            !snapshotUsable ->
                                                                "verified dual id unknown " +
                                                                    "and profiles snapshot empty"
                                                            else ->
                                                                "verified dual id unknown"
                                                        },
                                                    ),
                                                    msg = "remove result unknown: " +
                                                        "cannot confirm dual profile",
                                                    msgKind = MsgKind.CHANNEL,
                                                )
                                            } else {
                                                cur.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    inventory = degraded,
                                                    diagnose = diagnoseEvent(
                                                        DiagnoseCode.NO_DUAL,
                                                        techDetail = "typed resolution absent",
                                                    ),
                                                    msg = "no dual profile",
                                                    msgKind = MsgKind.NO_DUAL,
                                                )
                                            }
                                        }
                                    }
                                    is DualTargetResult.SerialUnavailable -> {
                                        // Stage C C1：serial 缺失為身份不可驗證 (VERIFY_MISMATCH +
                                        // INVALID)，與 confirm 內 serial 缺失/不一致一致
                                        // (:2051-2137)；非通道錯誤 (CHANNEL_ERROR) 亦非
                                        // 確認無 dual (NO_DUAL)。理由 (M3) 不變：寫前拒絕
                                        // 時 FRESH 降 STALE (不清 cloned/lastOperation)。
                                        val now = nowWallMs()
                                        _state.update { cur ->
                                            val degraded =
                                                if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                                    cur.inventory.toStaleOrUnknown(
                                                        now = now,
                                                        source = cur.inventory.source,
                                                        targetId = cur.inventory.targetId,
                                                    )
                                                } else {
                                                    cur.inventory
                                                }
                                            cur.copy(
                                                loading = false,
                                                profiles = snap.profiles,
                                                pendingRemove = null,
                                                inventory = degraded,
                                                diagnose = diagnoseEvent(
                                                    DiagnoseCode.VERIFY_MISMATCH,
                                                    techDetail = "serial unavailable " +
                                                        "for verified target",
                                                ),
                                                msg = "無法驗證分身身分（serial 缺失），請重試",
                                                msgKind = MsgKind.INVALID,
                                            )
                                        }
                                    }
                                    is DualTargetResult.Confirmed -> {
                                        val dualId = target.target.userId
                                        val label =
                                            snap.profiles.firstOrNull { it.userId == dualId }?.label
                                        if (label == null) {
                                            // 理由 (M1 空快照鑑別，repo 簽名不動，照 refreshLocked/clone-NoDual)：
                                            // 空快照 (連 owner 都無，快照不可信) ->CHANNEL_ERROR 且不清 cloned；
                                            // 快照正常有戶但 verified 目標缺席才為真 NO_DUAL。
                                            // 理由 (M3)：寫前拒絕時 FRESH 降 STALE。
                                            val snapshotUsable = snap.profiles.isNotEmpty()
                                            val now = nowWallMs()
                                            _state.update { cur ->
                                                val degraded =
                                                    if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                                        cur.inventory.toStaleOrUnknown(
                                                            now = now,
                                                            source = cur.inventory.source,
                                                            targetId = cur.inventory.targetId,
                                                        )
                                                    } else {
                                                        cur.inventory
                                                    }
                                                if (!snapshotUsable) {
                                                    cur.copy(
                                                        loading = false,
                                                        profiles = snap.profiles,
                                                        pendingRemove = null,
                                                        inventory = degraded,
                                                        diagnose = diagnoseEvent(
                                                            DiagnoseCode.CHANNEL_ERROR,
                                                            techDetail = "verified dual id " +
                                                                "$dualId absent and profiles " +
                                                                "snapshot empty",
                                                        ),
                                                        msg = "remove result unknown: " +
                                                            "cannot confirm dual profile",
                                                        msgKind = MsgKind.CHANNEL,
                                                    )
                                                } else {
                                                    cur.copy(
                                                        loading = false,
                                                        profiles = snap.profiles,
                                                        pendingRemove = null,
                                                        inventory = degraded,
                                                        // verified 目標不在快照列中 -> 無可用 dual
                                                        // (對應 confirmRemove 的 label==null 分支)。
                                                        diagnose = diagnoseEvent(
                                                            DiagnoseCode.NO_DUAL,
                                                            techDetail = "verified dual id " +
                                                                "$dualId absent",
                                                        ),
                                                        msg = "no dual profile",
                                                        msgKind = MsgKind.NO_DUAL,
                                                    )
                                                }
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
                                                        requestedAtMs = nowMonoMs(),
                                                    )
                                                    _state.update {
                                                        it.copy(
                                                            loading = false,
                                                            profiles = snap.profiles,
                                                            pendingRemove = pending,
                                                            // 理由 (本機回音原文)：msg 顯示真包名，
                                                            // 儲存的 pending.pkg 保持 raw 不動。
                                                            msg = "等待確認移除：" +
                                                                "${sanitizeUserEcho(trimmed)}" +
                                                                "（user $dualId）",
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
                                                            // 理由 (本機回音原文)：msg 顯示真包名
                                                            // (此分支無儲存)。
                                                            msg = "未安裝，無需移除：" +
                                                                sanitizeUserEcho(trimmed),
                                                            msgKind = MsgKind.INFO,
                                                        )
                                                    }
                                                is VerifyState.Unknown -> {
                                                    // 理由 (M3)：寫前拒絕時 FRESH 降 STALE。
                                                    val now = nowWallMs()
                                                    _state.update { cur ->
                                                        val degraded =
                                                            if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                                                cur.inventory.toStaleOrUnknown(
                                                                    now = now,
                                                                    source = cur.inventory.source,
                                                                    targetId = cur.inventory.targetId,
                                                                )
                                                            } else {
                                                                cur.inventory
                                                            }
                                                        cur.copy(
                                                            loading = false,
                                                            profiles = snap.profiles,
                                                            pendingRemove = null,
                                                            inventory = degraded,
                                                            diagnose = diagnoseEvent(
                                                                DiagnoseCode.VERIFY_UNKNOWN,
                                                                techDetail = sanitizeForDisplay(
                                                                    "cannot verify $trimmed " +
                                                                        "for user $dualId: " +
                                                                        st.detail,
                                                                ).take(MAX_DIAG_DETAIL_CHARS),
                                                            ),
                                                            // 理由 (本機回音原文)：msg 的 $trimmed
                                                            // 回音顯示原文；
                                                            // st.detail 已經 sanitizeForDisplay。
                                                            msg = "remove result unknown: " +
                                                                "cannot verify " +
                                                                "${sanitizeUserEcho(trimmed)} " +
                                                                "for user " +
                                                                "$dualId: " +
                                                                sanitizeForDisplay(st.detail),
                                                            msgKind = MsgKind.CHANNEL,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // 協程取消必須透傳：外層 finally 會釋放 inFlightWrites，
                        // 此處若吞掉會把取消誤記為 UNKNOWN 操作歷史。
                        // Phase B P0 (B3)：取消路徑補 loading=false，免殘 true (同 clone/confirm)。
                        _state.update { it.copy(loading = false) }
                        throw e
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _state.update {
                            it.copy(
                                loading = false,
                                pendingRemove = null,
                                // phase 1 只做唯讀查驗 (未執行 uninstall)，
                                // 例外補 OP_FAILED (與 clone 對齊：只寫 msg 的分支補碼)。
                                diagnose = diagnoseEvent(
                                    DiagnoseCode.OP_FAILED,
                                    techDetail = sanitizeForDisplay(e.message ?: "remove failed")
                                        .take(MAX_DIAG_DETAIL_CHARS),
                                ),
                                msg = "remove failed: " +
                                    "${sanitizeForDisplay(e.message ?: "remove failed")}",
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
                // 理由 (m1 本機回音原文)：使用者自輸包名回顯用
                // sanitizeUserEcho (保原文、去換行，不 redact)。
                msg = "已取消移除：${sanitizeUserEcho(pending.pkg)}",
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
    // consent (nowMonoMs() - requestedAtMs > REMOVE_CONFIRM_TTL_MS) is refused
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
                        // 理由 (m1 本機回音原文)：使用者自輸包名回顯用
                        // sanitizeUserEcho (保原文、去換行，不 redact)；
                        // 儲存的 key/appKey 保持 raw 不動。
                        msg = "already in progress: ${sanitizeUserEcho(pendingAtCall.pkg)}",
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
                        // M1 typed 鑑別：僅 Absent+快照可用才報 NO_DUAL；
                        // Unknown 一律 CHANNEL_ERROR + cannot confirm，
                        // msg/kind 與 diagnose 對齊 (不再自矛盾)。
                        // M2 pm 否決：先取快照再鑑別，Absent 前傳 pm 戶集合
                        //（無快照傳 null 不否決；兩次 IO 皆唯讀，順序不影響語意）。
                        val snapForId = onIo { repo.profilesSnapshot() }
                        val pmIdsForId = (snapForId as? ProfilesSnapshot.Loaded)?.profiles?.map { it.userId }?.toSet()
                        val idRes = onIo { repo.resolveDualUserIdTyped(pmIds = pmIdsForId) }
                        val freshId =
                            (idRes as? DualIdResolution.Confirmed)?.userId ?: NO_TARGET_ID
                        if (freshId < 0) {
                            val snapshotUsable =
                                (snapForId as? ProfilesSnapshot.Loaded)
                                    ?.profiles?.isNotEmpty() == true
                            val confirmedAbsent =
                                idRes is DualIdResolution.Absent && snapshotUsable
                            val typedDetail =
                                (idRes as? DualIdResolution.Unknown)?.detail
                            if (confirmedAbsent) {
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        pendingRemove = null,
                                        diagnose = diagnoseEvent(
                                            DiagnoseCode.NO_DUAL,
                                            techDetail = "typed resolution absent",
                                        ),
                                        msg = "no dual profile",
                                        msgKind = MsgKind.NO_DUAL,
                                    )
                                }
                            } else {
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        pendingRemove = null,
                                        diagnose = diagnoseEvent(
                                            DiagnoseCode.CHANNEL_ERROR,
                                            techDetail = sanitizeForDisplay(
                                                when {
                                                    typedDetail?.isNotBlank() == true ->
                                                        "verified dual id unknown: $typedDetail"
                                                    !snapshotUsable ->
                                                        "verified dual id unknown and profiles " +
                                                            "snapshot empty"
                                                    else ->
                                                        "verified dual id unknown " +
                                                            "for expected ${cur.dualId}"
                                                },
                                            ).take(MAX_DIAG_DETAIL_CHARS),
                                        ),
                                        msg = "remove result unknown: " +
                                            "cannot confirm dual profile",
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                        if (freshId != cur.dualId) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.VERIFY_MISMATCH,
                                        techDetail = "verified id $freshId != ${cur.dualId}",
                                    ),
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
                            // Stage C C2：補 diagnose=VERIFY_MISMATCH (與第一段
                            // :2051-2066 同形)，否則殘留舊碼；零卸載語意不變。
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.VERIFY_MISMATCH,
                                        techDetail = "verified id " +
                                            "${second.target.userId} != ${cur.dualId}",
                                    ),
                                    msg = "移除目標已變更（verified id " +
                                        "${second.target.userId} ≠ ${cur.dualId}），請重新確認",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                            return@withLock
                        }
                        val freshSerial = (second as? DualTargetResult.Confirmed)?.target?.serial
                        if (freshSerial == null) {
                            // 理由 (M2 殘留)：serial 缺失為驗證不一致 (目標無法確認)，
                            // 補 diagnose=VERIFY_MISMATCH 並攜帶 dualId/serial 狀態
                            // (數字身份可讀性)；不含 raw label/pkg (隱私，已有欄位外不新增)。
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.VERIFY_MISMATCH,
                                        techDetail = "serial unavailable " +
                                            "for user ${cur.dualId} " +
                                            "(expected ${cur.dualSerial})",
                                    ),
                                    msg = "移除目標無法驗證（serial 缺失），請重新確認",
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                            return@withLock
                        }
                        if (freshSerial != cur.dualSerial) {
                            // M2 補 diagnose：serial 不一致為驗證不一致，
                            // techDetail 僅數字身份 (不含 raw pkg/label，隱私)。
                            _state.update {
                                it.copy(
                                    loading = false,
                                    pendingRemove = null,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.VERIFY_MISMATCH,
                                        techDetail = "serial $freshSerial != " +
                                            "${cur.dualSerial} for user ${cur.dualId}",
                                    ),
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
                                        diagnose = snap.toDiagnoseEvent(),
                                        msg = "remove result unknown: " +
                                            "channel error: ${sanitizeForDisplay(snap.detail)}",
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            is ProfilesSnapshot.Loaded -> {
                                val label =
                                    snap.profiles.firstOrNull { it.userId == cur.dualId }?.label
                                if (label == null) {
                                    // 理由 (M1 空快照鑑別，照 requestRemove 分支)：
                                    // 空快照 (連 owner 都無，快照不可信) ->CHANNEL_ERROR；
                                    // 快照正常有戶但 verified 目標缺席才為真 NO_DUAL。
                                    // 理由 (M3)：寫前拒絕時 FRESH 降 STALE。
                                    val snapshotUsable = snap.profiles.isNotEmpty()
                                    val now = nowWallMs()
                                    _state.update { st ->
                                        val degraded =
                                            if (st.inventory.freshness == InventoryFreshness.FRESH) {
                                                st.inventory.toStaleOrUnknown(
                                                    now = now,
                                                    source = st.inventory.source,
                                                    targetId = st.inventory.targetId,
                                                )
                                            } else {
                                                st.inventory
                                            }
                                        if (!snapshotUsable) {
                                            st.copy(
                                                loading = false,
                                                profiles = snap.profiles,
                                                pendingRemove = null,
                                                inventory = degraded,
                                                diagnose = diagnoseEvent(
                                                    DiagnoseCode.CHANNEL_ERROR,
                                                    techDetail = "verified dual id " +
                                                        "${cur.dualId} absent and profiles " +
                                                        "snapshot empty",
                                                ),
                                                msg = "remove result unknown: " +
                                                    "cannot confirm dual profile",
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        } else {
                                            st.copy(
                                                loading = false,
                                                profiles = snap.profiles,
                                                pendingRemove = null,
                                                inventory = degraded,
                                                diagnose = diagnoseEvent(
                                                    DiagnoseCode.NO_DUAL,
                                                    techDetail = "verified dual id ${cur.dualId} absent",
                                                ),
                                                msg = "no dual profile",
                                                msgKind = MsgKind.NO_DUAL,
                                            )
                                        }
                                    }
                                } else if (label != cur.profileLabel) {
                                    _state.update {
                                        it.copy(
                                            loading = false,
                                            profiles = snap.profiles,
                                            pendingRemove = null,
                                            // 隱私：techDetail 不含原始 label 字串
                                            // (label 為裝置顯示名，匯出可見)，
                                            // 只留 userId (數字可讀性) 與雙方長度
                                            // 供除錯；msg 對話框維持原可讀文案。
                                            diagnose = diagnoseEvent(
                                                DiagnoseCode.VERIFY_MISMATCH,
                                                techDetail = "profile label mismatch " +
                                                    "for user ${cur.dualId} " +
                                                    "(len=${label.length} vs " +
                                                    "${cur.profileLabel.length})",
                                            ),
                                            // 理由 (m2)：顯示用 label 回音經 sanitizeForDisplay。
                                            msg = "移除目標已變更（${sanitizeForDisplay(label)} ≠ " +
                                                "${sanitizeForDisplay(cur.profileLabel)}），請重新確認",
                                            msgKind = MsgKind.INVALID,
                                        )
                                    }
                                } else {
                                    // P2 時效 (PR-33 R3): no extra verify IO
                                    // (TTL 判在既有 IO 之後). A stale consent
                                    // is refused with zero uninstalls
                                    // (INVALID + 重新確認).
                                    if (nowMonoMs() - cur.requestedAtMs > REMOVE_CONFIRM_TTL_MS) {
                                        // M3 補 diagnose：同意過期為輸入拒絕，
                                        // techDetail 僅時效數字 (不含 pkg)。
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                profiles = snap.profiles,
                                                pendingRemove = null,
                                                diagnose = diagnoseEvent(
                                                    DiagnoseCode.INVALID_INPUT,
                                                    techDetail = "consent expired for user " +
                                                        "${cur.dualId}",
                                                ),
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
                                                    // 理由 (本機回音原文)：msg 顯示真包名。
                                                    msg = "未安裝，無需移除：" +
                                                        sanitizeUserEcho(cur.pkg),
                                                    msgKind = MsgKind.INFO,
                                                )
                                            }
                                            return@withLock
                                        }
                                        is VerifyState.Unknown -> {
                                            // M3 補 diagnose：寫後無法查證為 VERIFY_UNKNOWN，
                                            // techDetail 經 sanitizeForDisplay。
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    diagnose = diagnoseEvent(
                                                        DiagnoseCode.VERIFY_UNKNOWN,
                                                        techDetail = sanitizeForDisplay(
                                                            "cannot verify ${cur.pkg} " +
                                                                "for user ${cur.dualId}: " +
                                                                st.detail,
                                                        ).take(MAX_DIAG_DETAIL_CHARS),
                                                    ),
                                                    // 理由 (m1 本機回音原文)：pkg 回音用
                                                    // sanitizeUserEcho；st.detail 續消毒。
                                                    msg = "remove result unknown: " +
                                                        "cannot verify " +
                                                        "${sanitizeUserEcho(cur.pkg)} " +
                                                        "for user ${cur.dualId}: " +
                                                        sanitizeForDisplay(st.detail),
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
                                        is RemoveOutcome.Success -> {
                                            // P1-1 target 重建（同 clone 成功分支）：成功結果 dualId
                                            // != inventory.targetId 時不能只附註警告。FRESH 快照聲稱
                                            // 舊目標可信，但移除已釘在新目標執行：先降原 inventory
                                            // 為 STALE（不清 cloned，只降新鮮度；remaining 已排除
                                            // 當次包，跨目標結果不混入舊清單，整單標 STALE 待重查），
                                            // 再對新 verified target 做全量 refresh（已在 opMutex
                                            // 內，直接調 refreshLocked，不重入加鎖）。觸發條件僅
                                            // FRESH mismatch；rebuiltNote 保留原邏輯不變。
                                            val needRebuildRefresh =
                                                _state.value.inventory.freshness == InventoryFreshness.FRESH &&
                                                    _state.value.inventory.targetId != cur.dualId
                                            _state.update {
                                                val remaining =
                                                    it.cloned.filterNot { p -> p == cur.pkg }
                                                // 全量新鮮度只由 refresh 賦予：成功寫入僅對齊筆數
                                                // (count=當前筆數)；非重建時 freshness/source/target/
                                                // queryTime 一律不動，重建時先降 STALE 待下行 refresh 覆寫。
                                                // target 重建提示：FRESH 快照目標與本次 verified
                                                // 目標不一致、且上次操作釘在舊 id 上時，detail
                                                // 附加需重新查證提示。
                                                val oldTarget = it.inventory.targetId
                                                val rebuiltNote =
                                                    if (it.inventory.freshness == InventoryFreshness.FRESH &&
                                                        oldTarget != cur.dualId &&
                                                        it.lastOperation?.pinnedTarget == oldTarget
                                                    ) {
                                                        " (target 已重建，需重新查證)"
                                                    } else {
                                                        ""
                                                    }
                                                val (op, diag) = recordOp(
                                                    opId = key,
                                                    appKey = cur.pkg,
                                                    pinnedTarget = cur.dualId,
                                                    phase = OpPhase.SUCCESS,
                                                    // 理由 (本機回音原文)：msg 顯示真包名；
                                                    // 儲存的 opId/appKey 保持 raw，detail 仍遮蔽。
                                                    detail = "removed: " +
                                                        sanitizeForDisplay(cur.pkg) +
                                                        rebuiltNote,
                                                    diagnose = null,
                                                )
                                                val degradedInventory =
                                                    if (needRebuildRefresh &&
                                                        it.inventory.freshness == InventoryFreshness.FRESH
                                                    ) {
                                                        it.inventory.copy(
                                                            freshness = InventoryFreshness.STALE,
                                                            count = remaining.size,
                                                        )
                                                    } else {
                                                        it.inventory.copy(count = remaining.size)
                                                    }
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    cloned = remaining,
                                                    inventory = degradedInventory,
                                                    pendingRemove = null,
                                                    lastOperation = op,
                                                    diagnose = diag,
                                                    // 理由 (本機回音原文)：msg 顯示真包名。
                                                    msg = "removed: ${sanitizeUserEcho(cur.pkg)}",
                                                    msgKind = MsgKind.INFO,
                                                )
                                            }
                                            if (needRebuildRefresh) {
                                                refreshLocked()
                                            }
                                        }
                                        is RemoveOutcome.NoDual -> {
                                            // Stage C C5：先走 typed 入口 (比照 :1997-2001
                                            // 的 freshId 檢查)，與 refresh drift 語意對齊；
                                            // Confirmed(otherId) 即目標已變更 → VERIFY_MISMATCH，
                                            // 不落入下方 targetListed 啟發式 (與 refresh 分叉)。
                                            // 其餘 (Absent/Unknown/同 id) 才走原啟發式。
                                            // pmIds 取新鮮快照 (比照 1997-2001 先取快照再鑑別)，
                                            // 免舊 snap 否決遮蔽真漂移。
                                            val freshSnapForNoDual = onIo { repo.profilesSnapshot() }
                                            val pmIds2 = (freshSnapForNoDual as? ProfilesSnapshot.Loaded)?.profiles?.map { it.userId }?.toSet()
                                                ?: snap.profiles.map { it.userId }.toSet()
                                            val idRes2 = onIo { repo.resolveDualUserIdTyped(pmIds = pmIds2) }
                                            val driftedId = (idRes2 as? DualIdResolution.Confirmed)?.userId
                                                ?.takeIf { it != cur.dualId }
                                            if (driftedId != null) {
                                                _state.update {
                                                    it.copy(
                                                        loading = false,
                                                        profiles = snap.profiles,
                                                        pendingRemove = null,
                                                        diagnose = diagnoseEvent(
                                                            DiagnoseCode.VERIFY_MISMATCH,
                                                            techDetail = "verified id " +
                                                                "$driftedId != ${cur.dualId}",
                                                        ),
                                                        msg = "移除目標已變更（verified id " +
                                                            "$driftedId ≠ ${cur.dualId}），請重新確認",
                                                        msgKind = MsgKind.INVALID,
                                                    )
                                                }
                                            } else {
                                            // 寫前拒絕 (uninstall 未執行)：只設 diagnose+msg，
                                            // 不覆寫 lastOperation。鑑別沿用本次已持有的
                                            // profilesSnapshot：仍列有目標 id -> typed 身份
                                            // 通道抖動 -> CHANNEL_ERROR；否則真正無 profile
                                            // -> NO_DUAL。兩者皆不碰 cloned (移除未執行)。
                                            // M4 空快照守衛 (比照 clone/request)：空快照
                                            // (連 owner 都無，快照不可信) 即 CHANNEL_ERROR，
                                            // 不得報 NO_DUAL。
                                            // 理由 (M3)：FRESH 經 toStaleOrUnknown 降 STALE。
                                            val targetListed =
                                                snap.profiles.any { it.userId == cur.dualId }
                                            val snapshotUsable = snap.profiles.isNotEmpty()
                                            val now = nowWallMs()
                                            _state.update { cur2 ->
                                                val degraded =
                                                    if (cur2.inventory.freshness == InventoryFreshness.FRESH) {
                                                        cur2.inventory.toStaleOrUnknown(
                                                            now = now,
                                                            source = cur2.inventory.source,
                                                            targetId = cur2.inventory.targetId,
                                                        )
                                                    } else {
                                                        cur2.inventory
                                                    }
                                                if (targetListed || !snapshotUsable) {
                                                    cur2.copy(
                                                        loading = false,
                                                        profiles = snap.profiles,
                                                        pendingRemove = null,
                                                        inventory = degraded,
                                                        diagnose = diagnoseEvent(
                                                            DiagnoseCode.CHANNEL_ERROR,
                                                            techDetail = if (!snapshotUsable) {
                                                                "verified dual id unknown " +
                                                                    "and profiles snapshot empty"
                                                            } else {
                                                                "verified dual id unknown " +
                                                                    "for listed target ${cur.dualId}"
                                                            },
                                                        ),
                                                        msg = "remove result unknown: " +
                                                            "channel error for user ${cur.dualId}",
                                                        msgKind = MsgKind.CHANNEL,
                                                    )
                                                } else {
                                                    cur2.copy(
                                                        loading = false,
                                                        profiles = snap.profiles,
                                                        pendingRemove = null,
                                                        inventory = degraded,
                                                        diagnose = res.toDiagnoseEvent(),
                                                        msg = "no dual profile",
                                                        msgKind = MsgKind.NO_DUAL,
                                                    )
                                                }
                                            }
                                            }
                                        }
                                        is RemoveOutcome.ChannelError -> {
                                            // 寫前拒絕 (uninstall 未執行)：只設 diagnose+msg，
                                            // 不覆寫 lastOperation。
                                            // 理由 (M3)：FRESH 經 toStaleOrUnknown 降 STALE。
                                            val now = nowWallMs()
                                            _state.update { cur2 ->
                                                val degraded =
                                                    if (cur2.inventory.freshness == InventoryFreshness.FRESH) {
                                                        cur2.inventory.toStaleOrUnknown(
                                                            now = now,
                                                            source = cur2.inventory.source,
                                                            targetId = cur2.inventory.targetId,
                                                        )
                                                    } else {
                                                        cur2.inventory
                                                    }
                                                cur2.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    inventory = degraded,
                                                    diagnose = res.toDiagnoseEvent(),
                                                    msg = "remove result unknown: " +
                                                        "channel error: " +
                                                        sanitizeForDisplay(res.detail),
                                                    msgKind = MsgKind.CHANNEL,
                                                )
                                            }
                                        }
                                        is RemoveOutcome.InvalidPackage -> {
                                            // 寫前拒絕 (uninstall 未執行)：只設 diagnose+msg，
                                            // 不覆寫 lastOperation (msgKind 維持 INVALID)。
                                            // 理由 (M3)：FRESH 經 toStaleOrUnknown 降 STALE。
                                            val now = nowWallMs()
                                            _state.update { cur2 ->
                                                val degraded =
                                                    if (cur2.inventory.freshness == InventoryFreshness.FRESH) {
                                                        cur2.inventory.toStaleOrUnknown(
                                                            now = now,
                                                            source = cur2.inventory.source,
                                                            targetId = cur2.inventory.targetId,
                                                        )
                                                    } else {
                                                        cur2.inventory
                                                    }
                                                cur2.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    inventory = degraded,
                                                    diagnose = res.toDiagnoseEvent(),
                                                    msg = "invalid package",
                                                    msgKind = MsgKind.INVALID,
                                                )
                                            }
                                        }
                                        is RemoveOutcome.RemoveFailed -> {
                                            _state.update {
                                                val (op, diag) = recordOp(
                                                    opId = key,
                                                    appKey = cur.pkg,
                                                    pinnedTarget = cur.dualId,
                                                    phase = OpPhase.FAILED,
                                                    detail = res.detail,
                                                    diagnose = res.toDiagnoseEvent(),
                                                )
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    lastOperation = op,
                                                    diagnose = diag,
                                                    msg = sanitizeForDisplay(res.detail),
                                                    msgKind = MsgKind.FAILED,
                                                )
                                            }
                                        }
                                        is RemoveOutcome.StillInstalled -> {
                                            _state.update {
                                                val (op, diag) = recordOp(
                                                    opId = key,
                                                    appKey = cur.pkg,
                                                    pinnedTarget = res.dualId,
                                                    phase = OpPhase.FAILED,
                                                    detail = "still installed for user ${res.dualId}",
                                                    diagnose = res.toDiagnoseEvent(cur.pkg),
                                                )
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    lastOperation = op,
                                                    diagnose = diag,
                                                    // 理由 (本機回音原文)：msg 的包名回音顯示原文。
                                                    msg = "remove failed: " +
                                                        "${sanitizeUserEcho(cur.pkg)} " +
                                                        "still installed for user ${res.dualId}",
                                                    msgKind = MsgKind.VERIFY,
                                                )
                                            }
                                        }
                                        is RemoveOutcome.VerifyUnknown -> {
                                            _state.update {
                                                val (op, diag) = recordOp(
                                                    opId = key,
                                                    appKey = cur.pkg,
                                                    pinnedTarget = res.dualId,
                                                    phase = OpPhase.UNKNOWN,
                                                    detail = res.detail,
                                                    diagnose = res.toDiagnoseEvent(),
                                                )
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    lastOperation = op,
                                                    diagnose = diag,
                                                    // 理由 (本機回音原文)：msg 的包名回音顯示原文；
                                                    // res.detail 仍消毒 (不可信)。
                                                    msg = "remove result unknown: " +
                                                        "cannot verify removal of " +
                                                        "${sanitizeUserEcho(cur.pkg)} " +
                                                        "for user ${res.dualId}: " +
                                                        sanitizeForDisplay(res.detail),
                                                    msgKind = MsgKind.CHANNEL,
                                                )
                                            }
                                        }
                                        is RemoveOutcome.TargetMoved -> {
                                            val now = nowWallMs()
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    lastOperation = LastOperation(
                                                        opId = key,
                                                        appKey = cur.pkg,
                                                        pinnedTarget = cur.dualId,
                                                        phase = OpPhase.FAILED,
                                                        timeMs = now,
                                                        detail = "verified id ${res.verified} != ${res.expected}",
                                                    ),
                                                    diagnose = res.toDiagnoseEvent(),
                                                    msg = "移除目標已變更（verified id ${res.verified} ≠ ${res.expected}），請重新確認",
                                                    msgKind = MsgKind.INVALID,
                                                )
                                            }
                                        }
                                        // P1 serial binding (PR-33 R2): the repo
                                        // refused a serial mismatch (or an
                                        // unavailable serial) with zero
                                        // uninstalls -- same INVALID +
                                        // 重新確認 shape as TargetMoved.
                                        is RemoveOutcome.SerialMismatch -> {
                                            val now = nowWallMs()
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    profiles = snap.profiles,
                                                    pendingRemove = null,
                                                    lastOperation = LastOperation(
                                                        opId = key,
                                                        appKey = cur.pkg,
                                                        pinnedTarget = cur.dualId,
                                                        phase = OpPhase.FAILED,
                                                        timeMs = now,
                                                        detail = "serial ${res.actual ?: "?"} != ${res.expected ?: "?"}",
                                                    ),
                                                    diagnose = res.toDiagnoseEvent(),
                                                    msg = "移除目標已變更（serial " +
                                                        "${res.actual ?: "缺失"} ≠ " +
                                                        "${res.expected ?: "缺失"}），請重新確認",
                                                    msgKind = MsgKind.INVALID,
                                                )
                                            }
                                        }
                                        is RemoveOutcome.Cancelled ->
                                            _state.update { it.copy(loading = false) }
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // 協程取消必須透傳：外層 finally 會釋放 inFlightWrites，
                        // 此處若吞掉會把取消誤記為 UNKNOWN 操作歷史。
                        // Phase B P0 (B3)：取消路徑補 loading=false，免殘 true。
                        _state.update { it.copy(loading = false) }
                        throw e
                    } catch (e: Exception) {
                        // Never swallow coroutine cancellation (see refreshLocked).
                        if (e is CancellationException) throw e
                        // 例外逃逸：寫後是否執行未知 -> phase UNKNOWN (非 FAILED)，
                        // pinnedTarget 保留當次已知 dualId (pending 目標)，
                        // diagnose 為 VERIFY_UNKNOWN (寫後未查證，需重新查證，
                        // 非 OP_FAILED)。
                        _state.update {
                            val (op, diag) = recordOp(
                                opId = key,
                                appKey = pendingAtCall.pkg,
                                pinnedTarget = pendingAtCall.dualId,
                                phase = OpPhase.UNKNOWN,
                                detail = sanitizeForDisplay(e.message ?: "remove failed"),
                                diagnose = diagnoseEvent(
                                    DiagnoseCode.VERIFY_UNKNOWN,
                                    techDetail = sanitizeForDisplay(e.message ?: "remove failed"),
                                ),
                            )
                            it.copy(
                                loading = false,
                                pendingRemove = null,
                                lastOperation = op,
                                diagnose = diag,
                                msg = "remove failed: " +
                                    "${sanitizeForDisplay(e.message ?: "remove failed")}",
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
    // Single-round recheck body shared by the leading and trailing runs.
    // Must run under opMutex: callers hold it, so this never launches or
    // locks itself. Sets loading BEFORE any suspending probe IO
    // (Phase D P2 busy, issue #24; re-review #7 B2): swapAutoLocked()
    // suspends on `su -c id -u` while probeChannels() used to be the first
    // place setting loading -- without this early set the trailing round
    // (which previously inlined swapAutoLocked directly) left a window with
    // loading=false and MainActivity buttons enabled.
    private suspend fun recheckLocked(allowPrivilegedHookDiagnosis: Boolean = false) {
        // Phase D P2 busy (#24): same early-busy as reconnectLocked --
        // swapAutoLocked() suspends before probeChannels() sets
        // loading, so set it here first (idempotent, msg-preserving).
        // 取消窗口 (同 reconnectLocked)：取消落在 swap 內 su -c 懸掛
        // 永不到 probe 的清除點 → 同殘 true；補取消清 loading
        // (僅取消路徑，成功/失敗語意不動)。
        _state.update { it.copy(loading = true) }
        try {
            // P25 Item 3: one probe per round — see reconnectLocked.
            // P2-1: allowPrivilegedHookDiagnosis gates privileged diagnostic `cat`
            // (auto binder/onResume pass false: LSPOSED stays direct-only).
            if (preference == ChannelPreference.AUTO) {
                val probe = swapAutoLocked()
                probeChannels(probe, allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis)
            } else {
                probeChannels(allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis)
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(loading = false) }
            throw e
        }
    }

    // P2-1 (issue #34 review #10): allowPrivilegedHookDiagnosis gates
    // privileged diagnostic exec. Only requestHookRedDiagnosis passes true
    // (explicit user consent for a retained `cat`; the button copy states it
    // runs ROOT/Shizuku diagnostic commands). Every other caller -- auto
    // lifecycle (onResume / binder dead / permission bursts), picker taps,
    // reconnect taps -- passes false (default): LSPOSED diagnosis runs direct
    // reads only, usable stays empty, never `cat`s through a retained su/
    // Shizuku instance (each `su -c` is a fresh process with no session
    // persistence, so an auto `cat` risks an unexpected auth prompt or a
    // 60 s stall on revoke/per-prompt).
    fun recheckPrivilege(allowPrivilegedHookDiagnosis: Boolean = false) {
        // Single-flight coalesce (P2-2): same gate shape as refresh(). A tap
        // while a recheck is in flight (including waiting on opMutex behind
        // init/probe) only records one trailing re-run, so binder/permission/
        // onResume bursts never pile an unbounded waiter queue on the mutex.
        // Main-confined check-and-set/clear (same reasoning as refresh()).
        // F2/P3 leading busy (msg-preserving, same as refresh()): queued
        // behind opMutex still reads busy.
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            if (recheckJob?.isActive == true) {
                recheckPending = true
                // Privileged wins across the coalesced burst (see field docs).
                recheckPendingPrivileged = recheckPendingPrivileged || allowPrivilegedHookDiagnosis
                return@launch
            }
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            recheckJob = myJob
            try {
                opMutex.withLock { recheckLocked(allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis) }
                while (recheckPending) {
                    val trailingPrivileged = recheckPendingPrivileged
                    recheckPending = false
                    recheckPendingPrivileged = false
                    // Re-review #7 B2: hold busy across the mutex handoff so
                    // the trailing round never exposes loading=false while it
                    // waits on opMutex behind another op. The previous round's
                    // probeChannels() tail cleared loading; without this the
                    // trailing swapAutoLocked() suspend (`su -c id -u`) runs
                    // with buttons enabled. Idempotent with the recheckLocked
                    // entry set below; no suspension between the two, so the
                    // in-flight + trailing drain reads as continuous busy and
                    // every probe IO stays under loading=true.
                    _state.update { it.copy(loading = true) }
                    opMutex.withLock { recheckLocked(allowPrivilegedHookDiagnosis = trailingPrivileged) }
                }
            } finally {
                if (recheckJob === myJob) recheckJob = null
            }
        }
    }

    // Narrow-consent privileged hook re-diagnosis (P2-1): the ONLY entry that
    // runs a probeChannels round with allowPrivilegedHookDiagnosis=true, so a
    // parked LSPOSED channel may `cat` through the retained verified instance.
    // Picker taps, reconnect taps, and all auto lifecycle (onResume / binder /
    // permission) pass false: mode switch / reconnect is NOT consent for
    // privileged diagnosis. Isolates the auth-risking exec to this explicit
    // user tap (the button copy states it runs ROOT/Shizuku diagnostic
    // commands); never raises writeReady (see probeChannels isolation
    // assertion).
    fun requestHookRedDiagnosis() {
        recheckPrivilege(allowPrivilegedHookDiagnosis = true)
    }

    // Single "reconnect and update" entry (P25, issue #25): probe (with Auto
    // degrade) + refresh in one mutex hold, so a Shizuku binder/permission
    // lifecycle event never races an in-flight refresh. MainActivity routes
    // both the 重新連線並更新 button ("重新連線並更新") and the Shizuku
    // dead/authorized paths here (single entry reconnectAndRefresh; the
    // standalone refresh() stays probe-free for coalesced trailing runs).
    //
    // P25 Item 2: shares the refresh coalescing gate (same refreshJob plus a
    // reconnectPending upgrade flag) so a binder/button burst collapses to at
    // most one trailing re-run instead of queueing a waiter per tap (#24
    // bound). A reconnect arriving during a refresh upgrades the trailing
    // run to probe+refresh; concurrent reconnects collapse to one trailing
    // reconnect.
    // P2-1: allowPrivilegedHookDiagnosis gates privileged diagnostic `cat`
    // (see probeChannels). Every reconnect caller passes false (a 重新連線並更新
    // tap is NOT consent for privileged diagnosis); auto binder bursts pass
    // false too (direct reads only under LSPOSED). Only requestHookRedDiagnosis
    // passes true, so only that dedicated entry gains privilege.
    fun reconnectAndRefresh(allowPrivilegedHookDiagnosis: Boolean = false) {
        // F2/P3 leading busy (msg-preserving, same as refresh()): queued
        // behind opMutex still reads busy.
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            if (refreshJob?.isActive == true) {
                reconnectPending = true
                reconnectPendingPrivileged = reconnectPendingPrivileged || allowPrivilegedHookDiagnosis
                return@launch
            }
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            refreshJob = myJob
            try {
                opMutex.withLock { reconnectLocked(allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis) }
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
    // Serialized through opMutex like every other op. P2-1: picker taps pass
    // allowPrivilegedHookDiagnosis=false (a mode switch is NOT consent for
    // privileged diagnosis); the default stays false (fail-closed: a
    // programmatic call without the dedicated diagnosis tap never gains
    // privileged diagnosis). Only requestHookRedDiagnosis passes true. Auto
    // lifecycle never calls selectChannel.
    fun selectChannel(pref: ChannelPreference, allowPrivilegedHookDiagnosis: Boolean = false) {
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
            // F2/P3 leading busy (msg-preserving): the picker tap reads busy
            // even while queued behind opMutex; the locked body re-sets it
            // idempotently below.
            _state.update { it.copy(loading = true) }
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            try {
                opMutex.withLock {
                    // F8 same-pref early-return: no rebuild/repersist churn when
                    // the preference is unchanged (still re-probe + refresh so
                    // a revoked-then-reselected same channel recovers).
                    // F4 persist-after-swap: the stored choice follows a
                    // successful in-memory swap, so a cancel/failure before or
                    // during the swap never leaves stored != memory. Same-pref
                    // has no swap, so its persist stays idempotent here.
                    if (pref != preference) {
                        swapToLocked(pref)
                    }
                    try {
                        persistPreference?.invoke(pref)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Best effort: a failing store never blocks the swap.
                    }
                    // 鏈式共用一次 loading：probe 保留 busy，refresh 結尾才清除。
                    probeChannels(allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis, keepLoadingOnExit = true)
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

    // Phase C P1 (issue #34 review #11 P1): still-referenced guard for the
    // swap close policy. Instances held by suGateway/shizukuGateway (AUTO
    // reuses them across degrades, and their elevators probe through them)
    // or by the retained diagnostic slots must NEVER be terminal-closed:
    // close()/abandon() set closed=true permanently, so a SHIZUKU -> NOOP
    // degrade that closed the reused shizukuGateway would poison every later
    // probe (boundBinder throws "closed") and pin the VM in NOOP until a
    // picker rebuild. Still-referenced instances get at most a soft
    // releaseBinding() (drops the idle UserService binding, stays reusable);
    // only truly displaced instances (no slot references them anymore) get
    // terminal close() + teardown observer. onCleared/dispose keeps terminal
    // semantics (VM death never reuses).
    private fun isStillReferenced(gw: CmdGateway): Boolean =
        gw === suGateway || gw === shizukuGateway ||
            gw === retainedSuDiagnostic || gw === retainedShizukuDiagnostic

    // Switches the repo gateway to an already-decided ResolvedChannel while
    // keeping preference==AUTO (P25 degrade path). Reuses the existing
    // suGateway/shizukuGateway (and their elevators) so probes keep verifying
    // the actual channels and the Shizuku binding is not dropped on a
    // no-op. Must run under opMutex.
    private suspend fun swapToResolvedLocked(desired: ResolvedChannel) {
        // Fail-closed mapping: the AUTO probe matrix (resolveAutoProbe,
        // locked by pure_neverLsposed over all 8 combos) never selects the
        // LSPosed placeholder, but a future matrix change must still land
        // read-only -- never throw here (IllegalStateException out of a
        // swap path would violate fail-closed). Both NOOP and
        // LSPOSED_PLACEHOLDER map to NoOp below.
        val old = activeGateway
        val newGateway: CmdGateway = when (desired) {
            ResolvedChannel.ROOT -> suGateway
            ResolvedChannel.SHIZUKU -> shizukuGateway
            // Fail-closed: AUTO never selects the placeholder by construction,
            // but this arm stays live so any future drift still lands read-only.
            ResolvedChannel.NOOP,
            ResolvedChannel.LSPOSED_PLACEHOLDER,
            -> NoOpGateway()
        }
        activeGateway = newGateway
        repo = DualRepository(newGateway)
        gatewayPrivilege = newGateway.privilege
        currentChannel = desired
        // Phase B1: instances are reused here, so pin the still-usable ones
        // as retained BEFORE the generation bump orphans the evidence below
        // (probeChannels rewrites both entries right after on the
        // recheck/reconnect path; the init path rewrites nothing after the
        // bump, so without this pin the diagnosis would lose its gateway).
        if (isSuEvidenceUsable()) setRetainedSuDiagnostic(suGateway)
        if (isShizukuEvidenceUsable()) setRetainedShizukuDiagnostic(shizukuGateway)
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
        if (old !== newGateway) {
            if (isStillReferenced(old)) {
                // Phase C P1 soft path: AUTO still holds this instance for
                // future probes (e.g. SHIZUKU -> NOOP keeps shizukuGateway).
                // Soft-unbind the idle binding but stay reusable; never mark
                // terminal-closed and never notify the teardown observer.
                if (old is ShizukuCmdGateway) {
                    try {
                        old.releaseBinding()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Best effort: the stale binding dies with the process anyway.
                    }
                }
            } else {
                if (old is ShizukuCmdGateway) {
                    try {
                        old.close()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Best effort: the stale binding dies with the process anyway.
                    }
                }
                // Phase N2 observer: count SHELL fakes too (production seam null =
                // no-op). Only truly displaced instances reach here; retained /
                // still-referenced instances are NOT closed early -- only the
                // cleared snapshot does.
                if (isShizukuLike(old)) {
                    try {
                        gatewayTeardown?.invoke(old)
                    } catch (_: Exception) {
                    }
                }
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
        // Phase B1: retain still-usable diagnostic instances BEFORE the
        // rebuild orphans them. Only an entry usable right now (same
        // instance, same generation, proven) moves to the retained slot; a
        // stale/failed entry is left to the existing close path below. The
        // retained instance keeps serving diagnostic `cat` after the swap
        // (LSPOSED included) without ever authorizing the fresh instance.
        if (isSuEvidenceUsable()) setRetainedSuDiagnostic(oldSu)
        if (isShizukuEvidenceUsable()) setRetainedShizukuDiagnostic(oldShizuku)
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
        if (old !== activeGateway) {
            if (isStillReferenced(old)) {
                // Phase C P1 soft path (reuse factory / same-instance swap):
                // the previous active instance is still the probed/diagnostic
                // slot, so only soft-unbind, never terminal-close.
                if (old is ShizukuCmdGateway) {
                    try {
                        old.releaseBinding()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Best effort: the stale binding dies with the process anyway.
                    }
                }
            } else {
                if (old is ShizukuCmdGateway) {
                    try {
                        old.close()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Best effort: the stale binding dies with the process anyway.
                    }
                }
                // Phase N2 observer (same guard): SHELL fakes counted, real already
                // closed above (seam extra, null in production). Retained /
                // still-referenced not closed.
                if (isShizukuLike(old)) {
                    try {
                        gatewayTeardown?.invoke(old)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        // Release displaced idle Shizuku instances (assembleChannel builds
        // fresh ones every swap; the unpicked idle instance is otherwise
        // dropped without closing). Never the newly installed instances;
        // double-close with the block above is safe (close is idempotent, and
        // the !== old guard below keeps the teardown observer exactly-once
        // when the previous active and the idle slot are the same instance).
        if (oldShizuku !== old && oldShizuku !== suGateway && oldShizuku !== shizukuGateway &&
            oldShizuku !== activeGateway && oldShizuku is ShizukuCmdGateway &&
            oldShizuku !== retainedSuDiagnostic && oldShizuku !== retainedShizukuDiagnostic
        ) {
            try {
                oldShizuku.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Best effort: the stale binding dies with the process anyway.
            }
        }
        if (oldShizuku !== old && oldShizuku !== suGateway && oldShizuku !== shizukuGateway &&
            oldShizuku !== activeGateway && isShizukuLike(oldShizuku) &&
            oldShizuku !== retainedSuDiagnostic && oldShizuku !== retainedShizukuDiagnostic
        ) {
            try {
                gatewayTeardown?.invoke(oldShizuku)
            } catch (_: Exception) {
            }
        }
        // Defensive: suGateway is a SuCmdGateway by construction, but tests
        // may inject any CmdGateway -- never leak a Shizuku hiding there.
        // Same !== old dedup as above (previous active may equal the idle
        // slot when it was the unpicked channel).
        if (oldSu !== old && oldSu !== suGateway && oldSu !== shizukuGateway &&
            oldSu !== activeGateway && oldSu is ShizukuCmdGateway &&
            oldSu !== retainedSuDiagnostic && oldSu !== retainedShizukuDiagnostic
        ) {
            try {
                oldSu.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Best effort: the stale binding dies with the process anyway.
            }
        }
        if (oldSu !== old && oldSu !== suGateway && oldSu !== shizukuGateway &&
            oldSu !== activeGateway && isShizukuLike(oldSu) &&
            oldSu !== retainedSuDiagnostic && oldSu !== retainedShizukuDiagnostic
        ) {
            try {
                gatewayTeardown?.invoke(oldSu)
            } catch (_: Exception) {
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

    // Retains a verified diagnostic instance, closing the displaced slot
    // occupant when nothing references it anymore. Same-instance (factory
    // reuse) is a pure no-op: the guards inside closeRetainedIfDisplaced see
    // the still-referenced instance and close nothing.
    private suspend fun setRetainedSuDiagnostic(instance: CmdGateway) {
        val old = retainedSuDiagnostic
        if (old === instance) return
        retainedSuDiagnostic = instance
        closeRetainedIfDisplaced(old)
    }

    private suspend fun setRetainedShizukuDiagnostic(instance: CmdGateway) {
        val old = retainedShizukuDiagnostic
        if (old === instance) return
        retainedShizukuDiagnostic = instance
        closeRetainedIfDisplaced(old)
    }

    private suspend fun clearRetainedSuDiagnostic() {
        val old = retainedSuDiagnostic
        retainedSuDiagnostic = null
        closeRetainedIfDisplaced(old)
    }

    private suspend fun clearRetainedShizukuDiagnostic() {
        val old = retainedShizukuDiagnostic
        retainedShizukuDiagnostic = null
        closeRetainedIfDisplaced(old)
    }

    // Closes a displaced SHIZUKU diagnostic binding only when nothing
    // references it anymore (mirrors the swap close policy: only
    // ShizukuCmdGateway holds a cached binding, su gateways are stateless).
    // Must run under opMutex. Phase N2: also notifies the injectable seam for
    // SHELL fakes (null in production).
    private suspend fun closeRetainedIfDisplaced(gateway: CmdGateway?) {
        if (gateway == null || !isShizukuLike(gateway)) return
        if (gateway === suGateway || gateway === shizukuGateway || gateway === activeGateway) return
        if (gateway === retainedSuDiagnostic || gateway === retainedShizukuDiagnostic) return
        if (gateway is ShizukuCmdGateway) {
            try {
                gateway.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Best effort: the stale binding dies with the process anyway.
            }
        }
        try {
            gatewayTeardown?.invoke(gateway)
        } catch (_: Exception) {
        }
    }

    // P2-1: allowPrivilegedHookDiagnosis gates privileged diagnostic `cat`
    // under LSPOSED. Only requestHookRedDiagnosis passes true (explicit user
    // consent for a retained `cat`); every other caller (picker taps,
    // reconnect taps, onResume / binder / init auto paths) passes false, so
    // diagnosis is direct-reads only (UNKNOWN_* when direct is denied, never
    // a `su -c cat` that risks an auth prompt or a 60 s stall after a parked
    // revoke). A mode switch / reconnect tap is NOT consent for privileged
    // diagnosis.
    // Diagnosis never feeds ready/routed (hook-neutral, #19): ready is
    // computed from probes before diagnosis and asserted independent below.
    // Phase 2 (併發修復)：keepLoadingOnExit=true 時出口保留 loading=true，
    // 供同一次 mutex 持有內的 probe+refresh 鏈式呼叫共用一次 loading
    // (中間不閃斷)；獨立呼叫維持預設 false (語意不變)。
    // P2-4：resolvedBeforeOp 為呼叫端在前置 swapAutoLocked 之前記錄的
    // resolved（recheck/onForeground 會先 swap 再 probe；probe 內捕捉不到
    // 那段變化，故由呼叫端傳入）。null 表無前置 swap，以 probe 入口的
    // currentChannel 為準（selectChannel/reconnect 皆無前置 swap）。
    // 最大持鎖估算 (不加 timeout，僅記錄)：probe 約 2×elevator(各 ≤3s settle)
    // + hook 診斷(cat ≤3s) ≈ ≤9s；後接 refresh 約 ≤4 round-trip(各 ≤3s)
    // ≈ ≤12s；鏈式合計最差約 ~20s，期間其他 op 在 mutex 排隊，
    // UI 只顯示 loading，Main 不卡 (IO 已 offload)。
    // P25 Item 3: reusedProbe 攜帶 swapAutoLocked 結果，AUTO recheck/reconnect
    // 每輪最多探測一次 (no double `su -c` cost)。
    private suspend fun probeChannels(
        reusedProbe: ProbeOutcome? = null,
        allowPrivilegedHookDiagnosis: Boolean = false,
        keepLoadingOnExit: Boolean = false,
        resolvedBeforeOp: ResolvedChannel? = null,
    ) {
        // P2-4 選定通道失效即失效快照：FRESH 快照聲稱「選定通道可信」，故
        // probe 後若選定通道不可用（resolved 改變或 ready 變 false），即使
        // 他通道 probe 成功（anyProbePassed=true），也把 FRESH 降 STALE。舊規則只
        // 看 anyProbePassed：他通道成功即保 FRESH，即使選定通道已失。
        val resolvedBefore = resolvedBeforeOp ?: currentChannel
        _state.update { it.copy(loading = true) }
        try {
            // Single probe per call: reuse the swap result when present,
            // otherwise probe once (preference-filtered inside probeOnce,
            // cancellation propagates via probeOnce's rethrow).
            val probe = reusedProbe ?: probeOnce()
            val hasRoot = probe.hasRoot
            val hasShizuku = probe.hasShizuku
            // Phase A P2: per-channel independent evidence (diagnostic-only,
            // never feeds routing/readiness). P25: AUTO follows live probes,
            // so the entry without a prior swap (init / selectChannel-AUTO)
            // degrades FIRST here; recheck/reconnect already degraded via
            // swapAutoLocked(reused) so this skips the second (idempotent but
            // redundant) degrade. Degrade-first keeps the evidence below bound
            // to the post-degrade generation: writing evidence before the bump
            // would leave the whole round stale (gen N vs N+1, F1).
            if (preference == ChannelPreference.AUTO && reusedProbe == null) {
                degradeAutoLocked(hasRoot, hasShizuku)
            }
            // AUTO full probes rewrite BOTH entries with the CURRENT gateways
            // + generation; explicit single-channel rounds rewrite ONLY their
            // own entry (the other stays unknown, never cleared) with the
            // CURRENT gateway; LSPOSED probes none and writes nothing. Failure
            // writes hasCapability=false on the same instance (so a Shizuku
            // binder death, which fails the next `pm list users` probe,
            // immediately disables that entry until a later passing probe
            // re-enables it).
            when (preference) {
                ChannelPreference.AUTO -> {
                    suEvidence = DiagnosticEvidence(suGateway, hasRoot, evidenceGeneration)
                    shizukuEvidence = DiagnosticEvidence(shizukuGateway, hasShizuku, evidenceGeneration)
                    // Phase B1: a newly verified current instance becomes the
                    // retained diagnostic instance (same instance -> no-op);
                    // a failed channel clears its slot (revocation
                    // fail-closed: a later LSPOSED round must not `cat`
                    // through a revoked instance).
                    if (hasRoot) setRetainedSuDiagnostic(suGateway) else clearRetainedSuDiagnostic()
                    if (hasShizuku) setRetainedShizukuDiagnostic(shizukuGateway) else clearRetainedShizukuDiagnostic()
                }
                ChannelPreference.ROOT -> {
                    suEvidence = DiagnosticEvidence(suGateway, hasRoot, evidenceGeneration)
                    if (hasRoot) setRetainedSuDiagnostic(suGateway) else clearRetainedSuDiagnostic()
                }
                ChannelPreference.SHIZUKU -> {
                    shizukuEvidence = DiagnosticEvidence(shizukuGateway, hasShizuku, evidenceGeneration)
                    if (hasShizuku) setRetainedShizukuDiagnostic(shizukuGateway) else clearRetainedShizukuDiagnostic()
                }
                ChannelPreference.LSPOSED -> {
                    // N1 Phase 1: zero probe -- no evidence rewrite here by
                    // design (a parked LSPOSED round must not touch evidence).
                    // Retained expiry for a MANUAL diagnostic failure lives
                    // post-diagnosis (UNKNOWN_UNREADABLE clears retained +
                    // stale evidence); auto never clears and never execs.
                }
            }
            // P25: AUTO already follows live probes above (degrade-first); the
            // reusedProbe path (recheck/reconnect) degraded before this call,
            // so nothing remains to do here.
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
            // must NOT auto-trigger a probe here (a fresh instance with no
            // usable evidence and no retained slot stays UNKNOWN, + comment in
            // the UNKNOWN_NO_DIAGNOSTIC text). Cold-start LSPOSED (empty
            // evidence, empty retained) yields empty usable, so diagnosis is
            // direct-only and UNKNOWN_NO_DIAGNOSTIC there is correct.
            // Diagnosis never feeds `ready`/`routed` above (hook-neutral) and
            // an unproven channel never executes anything except via a verified
            // diagnostic gateway. No true channel + unreadable direct reads maps
            // to UNKNOWN_NO_DIAGNOSTIC, never to inactive.
            // Records the usable gateways of this round for the retained
            // expiry check below (diagnoseHookState swallows per-gateway
            // failure: an attempted-but-failed `cat` pair collapses into the
            // status, so the round must remember it attempted an exec).
            var usableGateways: List<CmdGateway> = emptyList()
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
                    // Phase B1 diagnostic lifecycle split: LSPOSED additionally
                    // uses the retained verified instances (kept across the
                    // swap, never a fresh probe; same-instance case deduped to
                    // one `cat`), so LSPOSED still initiates zero probes.
                    // Non-LSPOSED keeps the existing current-only filter
                    // untouched: an explicit empty hookDiagnosticGateways list
                    // still means "no gateway diagnosis" (and never executes
                    // a gateway the caller did not list). A retained instance
                    // may be expired (revoked while no probing channel was
                    // selected): its failing `cat` maps to UNKNOWN below
                    // (fail closed, no retry, no upgrade).
                    val suUsable = isSuEvidenceUsable()
                    val shizukuUsable = isShizukuEvidenceUsable()
                    // P2-1: without explicit privileged consent
                    // (allowPrivilegedHookDiagnosis=false) usable stays empty
                    // even when a retained instance exists: a retained Kotlin
                    // object is NOT a live su session (every `su -c` is a
                    // fresh process, 60 s timeout, per-prompt risk), so a
                    // non-consented `cat` after a parked revoke would pop an
                    // unexpected auth box or stall. Consented (true, only
                    // requestHookRedDiagnosis) keeps the B1 retained path
                    // (deduped to one `cat`, zero probes).
                    val usable = when (preference) {
                        ChannelPreference.LSPOSED -> if (!allowPrivilegedHookDiagnosis) {
                            emptyList()
                        } else {
                            (candidates.filter {
                                (it === suGateway && suUsable) ||
                                    (it === shizukuGateway && shizukuUsable)
                            } + listOfNotNull(
                                retainedSuDiagnostic,
                                retainedShizukuDiagnostic,
                            )).distinct()
                        }
                        else -> candidates.filter {
                            // F6 identity gate (same as the LSPOSED branch): a
                            // candidate must BE the current su/shizukuGateway
                            // whose probe just passed. Privilege + probe result
                            // alone would let a stale caller-supplied list
                            // drive `cat` through a displaced old instance; an
                            // explicit empty list still means "no diagnosis".
                            (it === suGateway && it.privilege == Privilege.ROOT && hasRoot) ||
                                (it === shizukuGateway && it.privilege == Privilege.SHELL && hasShizuku)
                        }
                    }
                    usableGateways = usable
                    diagnoseHookState(direct, usable)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                HookDiagnosis(HookStatus.UNKNOWN_NO_DIAGNOSTIC)
            }
            // P2-1 retained expiry: a consented privileged read that proves
            // the retained channel no longer serves `cat` (revoked while
            // parked) drops the retained slots (and the stale matched
            // evidence, which would otherwise re-authorize the same dead
            // instance on the next consented round via the current-match
            // path). Non-consented rounds never reach here (usable empty, no
            // exec), so a parked revoke never clears -- the slot survives
            // until a consented round proves it dead. LSPOSED evidence block
            // above stays Unit: the clearing lives here, not in the
            // probe-based evidence rewrite.
            // The expiry signal is the gateway exec itself failing, not just
            // UNKNOWN_UNREADABLE: positively-absent direct reads
            // (Readable(null) x2) plus failed `cat`s collapse to INACTIVE
            // viaGateway=false (direct wins, attempt info lost), so that
            // outcome with a non-empty usable list is also a proving failure.
            val gatewayExecFailed = usableGateways.isNotEmpty() &&
                ((hook.status == HookStatus.UNKNOWN_UNREADABLE && hook.viaGateway) ||
                    (hook.status == HookStatus.INACTIVE && !hook.viaGateway))
            if (preference == ChannelPreference.LSPOSED && allowPrivilegedHookDiagnosis && gatewayExecFailed) {
                clearRetainedSuDiagnostic()
                clearRetainedShizukuDiagnostic()
                // Same-instance reuse would otherwise keep suUsable true via
                // the stale evidence entry and re-`cat` the dead instance on
                // the next manual round: fail the stale entries closed here.
                // Production rebuilds are already mismatched (no-op); the
                // reuse-fake match branch is what this closes.
                suEvidence = DiagnosticEvidence(suGateway, false, evidenceGeneration)
                shizukuEvidence = DiagnosticEvidence(shizukuGateway, false, evidenceGeneration)
            }
            // Diagnostic isolation lock (P2-1 contract): hook diagnosis must
            // never raise writeReady. ready was computed from probes only;
            // LSPOSED (NoOp placeholder, NONE privilege) is never ready by
            // construction -- fail closed (clamp to not-ready) if that ever
            // drifts, never throw: an IllegalStateException out of a probe
            // path would violate fail-closed, and the outer catch preserves
            // stale ready (it never clears channelReady/writeReady).
            val effectiveReady =
                if (preference == ChannelPreference.LSPOSED) false else ready
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
                    else "通道未就緒：真通道已接線但未選中（NoOp 回退），目前為唯讀，" +
                        "寫入已禁用；請檢查授權後點「重新連線並更新」"
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
            // PR-6 P2 (#27 後半): 普通 probe 永不清除 lastOperation/diagnose
            // (probe 成功不清 UNKNOWN，需下一次 pinned verify 才覆寫)；FRESH
            // 在以下任一成立時補標 STALE（舊資料不謊稱新鮮）：
            // - 選定通道未就緒 (!ready：P2-4；含舊 !readCap 全失情形——
            //   readCap 為 false 時 ready 必為 false 的上位條件 (!readCap ⇒ !ready)，故只判 ready)；
            // - resolved 在本次處理中改變（選定通道已換，舊通道查的資料不可信）；
            // - 已過 maxAge（與 markStaleIfAgedLocked 共用 needsRefresh）。
            val readyMsg = degradeNoteForState
            // dialog 開著時狀態列不洗掉確認提示：pendingRemove != null 且新
            // kind == NONE/INFO 才保留原 msg/msgKind，CHANNEL/FAILED 穿透。
            // 其他欄位照寫（loading/hook/ready/resolved/exec/caps 照常更新）。
            // Phase 2 (P25 Item 4): probe 只在失去 readiness 時使 pmReadable
            // 過期 -- ready false（NOOP 回退/撤銷/通道失敗/binder dead）即
            // null（未知），因舊 refresh 結果屬於舊通道/舊授權；ready true
            // 時保留（同通道仍可讀，快照未失效）。僅 refresh 能寫 true/false。
            // Phase C P2: probeScope follows preference (本次探測範圍); probe
            // never writes pmReadable (refresh owns it) -- copy preserves it.
            // PR-6 P2 (#27 後半): FRESH 失效條件見上（P2-4：!ready 或 resolved
            // 改變即降 STALE，不再只看 anyProbePassed——他通道成功不能替選定通道背書）。
            // 雙時鐘：探測/清單/hook 取 wall-clock（nowWallMs），同意 TTL 取
            // 單調時鐘（nowMonoMs），互不混用。
            val probeNow = nowWallMs()
            _state.update { cur ->
                val staleInventory =
                    if (cur.inventory.freshness == InventoryFreshness.FRESH &&
                        (!ready || currentChannel != resolvedBefore || cur.inventory.needsRefresh(probeNow))
                    ) {
                        cur.inventory.copy(freshness = InventoryFreshness.STALE)
                    } else {
                        cur.inventory
                    }
                // P1-3 正常 probe 失敗（非 throw）產生 diagnose（PROBE_FAILED）：
                // 冷啟動無通道時若 diagnose 留 null，匯出會宣稱 OK。
                // 優先順序規則：已有 sticky 歷史事件（lastOperation UNKNOWN 帶
                // VERIFY_UNKNOWN，或現有 diagnose 非 null 且非 OK）時保留歷史；
                // 僅當 diagnose==null（或 OK，即 null）才寫入新的失敗碼，
                // 避免探測抖動洗掉待查證歷史。
                // P2-3 通道恢復清過時 E_PROBE（recheck 輕探語意）：
                // probeChannels 成功（ready=true）走輕探直寫 cur.diagnose，
                // 不經 full refresh，故舊 PROBE_FAILED 不會被 refresh 清掉，
                // 須在此清——無 sticky UNKNOWN lastOperation 且現有 diagnose
                // 為 PROBE_FAILED 時清為 null（通道已恢復，舊失敗碼過時；
                // 當次 probe 已通過、無新碼可寫，故 null 表就緒）；
                // 有 sticky UNKNOWN lastOperation 時一律保留 cur.diagnose
                // （待下一次 pinned verify 重新查證才清）；其他非 PROBE 診斷
                //（VERIFY_UNKNOWN/VERIFY_MISMATCH/CHANNEL_ERROR 等操作歷史）
                // 一律保留（sticky，輕探不得洗掉，需 full refresh 才覆寫）。
                // Phase B P0 (B1)：LSPOSED 零探測 (scope NONE) 永不寫 PROBE_FAILED：
                // ready=false 恆成立（NoOp 佔位），但 probeResultMark 須為「未探測」
                // 而非「探測未通過」；此處首判保留 cur.diagnose。
                val probeDiagnose =
                    if (preference == ChannelPreference.LSPOSED) {
                        cur.diagnose
                    } else if (!ready) {
                        if (cur.lastOperation?.phase == OpPhase.UNKNOWN) {
                            cur.diagnose
                        } else {
                            cur.diagnose ?: diagnoseEvent(
                                DiagnoseCode.PROBE_FAILED,
                                techDetail = "probe not ready: hasRoot=$hasRoot " +
                                    "hasShizuku=$hasShizuku preference=$preference",
                            )
                        }
                    } else {
                        if (cur.lastOperation?.phase == OpPhase.UNKNOWN) {
                            cur.diagnose
                        } else if (cur.diagnose?.code == DiagnoseCode.PROBE_FAILED) {
                            null
                        } else {
                            cur.diagnose
                        }
                    }
                val base = cur.withHookDiagnosis(hook, probeNow).copy(
                    // 鏈式呼叫 (keepLoadingOnExit) 保留 busy，下一段 refreshLocked
                    // 會接手；獨立呼叫則在此清除。
                    loading = keepLoadingOnExit,
                    channel = preference,
                    privilege = routed,
                    channelReady = effectiveReady,
                    resolvedGateway = currentChannel,
                    execIdentity = gatewayPrivilege,
                    anyProbePassed = anyProbePassed,
                    probeScope = preference.probeScope(),
                    writeReady = effectiveReady,
                    pmReadable = if (!effectiveReady) null else cur.pmReadable,
                    degradeNote = degradeNoteForState,
                    inventory = staleInventory,
                    diagnose = probeDiagnose,
                    msg = if (effectiveReady) readyMsg else notReadyMsg,
                    msgKind = if (effectiveReady) {
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
        // PR-6 P2: 保留 lastOperation/inventory (probe 失敗不清除歷史)，
        // 只記錄本次探測失敗的診斷事件。
        // phase1: 探測異常只在無 sticky 事件時才設 PROBE_FAILED (既有 diagnose
        // 如 VERIFY_UNKNOWN 等待重新查證者優先保留，避免探測抖動洗掉歷史)。
        // phase2: 鏈式呼叫保留 busy 交給 refreshLocked；獨立呼叫在此清除。
        // m2 與正常路徑同形：sticky UNKNOWN lastOperation 時一律保留
        // cur.diagnose（即使為 null 亦不寫 PROBE_FAILED，待 pinned verify
        // 重查）；其餘才在 diagnose==null 時寫 PROBE_FAILED。
        // Phase B P0 (B3)：取消路徑補 loading=false，免殘 true。
        // Phase B P0 (B1)：LSPOSED 零探測永不寫 PROBE_FAILED，保留原值。
        if (e is CancellationException) {
            _state.update { it.copy(loading = false) }
            throw e
        }
        _state.update { cur ->
            val probeDiagnose =
                if (preference == ChannelPreference.LSPOSED) {
                    cur.diagnose
                } else if (cur.lastOperation?.phase == OpPhase.UNKNOWN) {
                    cur.diagnose
                } else {
                    cur.diagnose ?: diagnoseEvent(
                        DiagnoseCode.PROBE_FAILED,
                        techDetail = sanitizeForDisplay(e.message ?: "probe failed"),
                    )
                }
            val next = cur.copy(
                loading = keepLoadingOnExit,
                pmReadable = null,
                diagnose = probeDiagnose,
                msg = "probe failed: ${sanitizeForDisplay(e.message ?: "probe failed")}",
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
        // P1-3 write-guard 拒絕產生 diagnose（CHANNEL_ERROR）：
        // 冷啟動無通道時若 diagnose 留 null，後續寫入拒絕只改 msg，
        // 匯出仍宣稱 OK。優先順序規則同 probeChannels：已有 sticky
        // 歷史事件（lastOperation UNKNOWN 帶 VERIFY_UNKNOWN，或現有
        // diagnose 非 null 且非 OK）時保留歷史；僅當 diagnose==null
        //（或 OK，即 null）才寫入 CHANNEL_ERROR。
        if (currentChannel == ResolvedChannel.LSPOSED_PLACEHOLDER) {
            // Status-aware hook text (P1): unknown renders as 狀態未知, never
            // as 未偵測到 (the latter is reserved for positively-read absence).
            // PR-6 P4: 時間證據經 hookEvidence()+displayText 統一 (扁平欄位保留)。
            // Phase B P0 (B1)：LSPOSED 零探測 (probeChannels) 永不寫 diagnose
            //（含 PROBE_FAILED/CHANNEL_ERROR 亦不寫），保留 cur.diagnose；
            // scope NONE=未探測，絕不報未通過。此規則僅限探測路徑。
            // 階段F：使用者明確嘗試寫入 (clone/remove 經此共用入口，含
            // confirmRemove) 而遭 NoOp/LSPOSED 政策拒絕時，若無 sticky
            // 歷史（diagnose==null 且 lastOperation.phase != UNKNOWN），
            // 記 CHANNEL_ERROR，techDetail 標註佔位/政策拒寫（明確不是
            // probe failure）；已有診斷（含 sticky UNKNOWN）則保留原值。
            val hookDesc = cur.hookEvidence().displayText()
            _state.update {
                val sticky = it.lastOperation?.phase == OpPhase.UNKNOWN || it.diagnose != null
                it.copy(
                    loading = false,
                    diagnose = if (sticky) {
                        it.diagnose
                    } else {
                        diagnoseEvent(
                            DiagnoseCode.CHANNEL_ERROR,
                            techDetail = "LSPOSED placeholder / writes unsupported " +
                                "(explicit write refused by policy, not a probe failure)",
                        )
                    },
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
                "通道未就緒：真通道已接線但未選中（NoOp 回退），目前為唯讀，" +
                    "寫入已禁用；請檢查授權後點「重新連線並更新」"
            } else {
                "所選模式 ${cur.channel}（${gatewayPrivilege} 通道）探測未通過，" +
                    "寫入已禁用（明選不跨通道）；可切到 Auto 或點「重新連線並更新」重試"
            }
            _state.update {
                val sticky = it.lastOperation?.phase == OpPhase.UNKNOWN || it.diagnose != null
                it.copy(
                    loading = false,
                    diagnose = if (sticky) {
                        it.diagnose
                    } else {
                        diagnoseEvent(
                            DiagnoseCode.CHANNEL_ERROR,
                            techDetail = "selected channel not ready " +
                                "(channel=${it.channel} privilege=$gatewayPrivilege)",
                        )
                    },
                    msg = reason,
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return false
        }
        // F3 fail-closed PM gate (dual-condition readyMark): probe-passed
        // (channelReady) alone never authorizes a write when `pm list users`
        // proved unreadable (pmReadable == false). null (unknown, not yet
        // refreshed or expired by a swap) still allows the write attempt: the
        // repo re-verifies and fail-closes there; only a proven-false PM
        // refuses here.
        if (cur.pmReadable == false) {
            _state.update {
                it.copy(
                    loading = false,
                    msg = "探測通過但 PM 不可讀（pm list users 失敗），寫入已禁用；" +
                        "請檢查授權後點「重新連線並更新」",
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
                val sticky = it.lastOperation?.phase == OpPhase.UNKNOWN || it.diagnose != null
                it.copy(
                    loading = false,
                    diagnose = if (sticky) {
                        it.diagnose
                    } else {
                        diagnoseEvent(
                            DiagnoseCode.CHANNEL_ERROR,
                            techDetail = "no privilege channel (read-only)",
                        )
                    },
                    msg = "唯讀：無可用權限通道，寫入已禁用；" +
                        "請檢查授權後點「重新連線並更新」",
                    msgKind = MsgKind.INVALID,
                )
            }
            return false
        }
        return true
    }

    // PR-6 P2 (#27 後半): 前景入口。MainActivity.onResume 經此進入 (取代直調
    // recheckPrivilege)：先把過期 FRESH 補標 STALE，再依新鮮度與通道變化決定
    // 輕探或重查——inventory 非 FRESH 或通道改變 (resolved/ready 變化) 即走
    // probe+refresh 全量重查；否則只走 probe 輕探 (不動清單與 lastOperation)。
    // P2-6：hook UNKNOWN 不再觸發全量 refresh——probeChannels 本就重跑 hook
    // 診斷，hook 未知只重 probe 即可；反覆前景只探不明顯增加裝置呼叫。
    fun onForeground() {
        // Single-flight coalesce (Phase B P0 B2): same gate shape as refresh()/
        // recheckPrivilege(). A burst while a foreground round is in flight
        // (including waiting on opMutex) only records one trailing re-run, so
        // 5 rapid taps collapse to leader + at most one trailing instead of
        // 5 mutex waiters each running probe (+ full refresh when stale).
        // Main-confined check-and-set/clear (same reasoning as refresh()).
        viewModelScope.launch {
            if (foregroundJob?.isActive == true) {
                foregroundPending = true
                return@launch
            }
            val myJob = currentCoroutineContext()[Job] ?: return@launch
            foregroundJob = myJob
            try {
                opMutex.withLock { foregroundLocked() }
                while (foregroundPending) {
                    foregroundPending = false
                    opMutex.withLock { foregroundLocked() }
                }
            } finally {
                if (foregroundJob === myJob) foregroundJob = null
            }
        }
    }

    // Foreground body: must run under opMutex (callers hold it).
    private suspend fun foregroundLocked() {
        markStaleIfAgedLocked()
        // 註：此路無 early-busy (loading 由 probeChannels 內首設)，swap 懸掛時
        // 取消落在 loading=true 之前，舊值多為 false，無殘 true 窗口；生產 job
        // 只隨 scope 清除被取消，無手動 cancel。外層仍補取消清 loading 作保險
        // (僅取消路徑，不改成功/失敗語意)。
        try {
                // P2-6：probe 前記錄通道狀態 (resolved/ready)，供 probe 後
                // 變化偵測（全量 refresh 只在 inventory 非 FRESH 或通道改變時）。
                val resolvedBefore = currentChannel
                val readyBefore = _state.value.writeReady
                // P25 Item 3: one probe per round — swapAutoLocked probes once and
                // returns the result for probeChannels to reuse.
                // 全程共用一次 loading：probe 保留 busy，需重查則 refresh 接手
                // (中間不閃斷)；輕探路徑 (免重查) 在此清除。
                if (preference == ChannelPreference.AUTO) {
                    val probe = swapAutoLocked()
                    probeChannels(
                        probe,
                        keepLoadingOnExit = true,
                        resolvedBeforeOp = resolvedBefore,
                    )
                } else {
                    probeChannels(
                        keepLoadingOnExit = true,
                        resolvedBeforeOp = resolvedBefore,
                    )
                }
                // stale-read 修正：probeChannels() 已改寫 state，必須重讀
                // _state.value 再判斷 refresh (此前沿用 probe 前快照，FRESH
                // 過期或 hook 轉未知會被漏判)。
                val after = _state.value
                val channelChanged =
                    currentChannel != resolvedBefore || after.writeReady != readyBefore
                if (after.inventory.freshness != InventoryFreshness.FRESH || channelChanged) {
                    refreshLocked()
                } else {
                    _state.update { it.copy(loading = false) }
                }
        } catch (e: CancellationException) {
            _state.update { it.copy(loading = false) }
            throw e
        }
    }

    // 過期 FRESH 補標 STALE (保留原 target/queryTime/count 供追溯)。
    // 必須在 opMutex 下呼叫。
    private fun markStaleIfAgedLocked() {
        val now = nowWallMs()
        _state.update { cur ->
            if (cur.inventory.freshness == InventoryFreshness.FRESH &&
                cur.inventory.needsRefresh(now)
            ) {
                cur.copy(inventory = cur.inventory.copy(freshness = InventoryFreshness.STALE))
            } else {
                cur
            }
        }
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
// PR-6 P2 (#27 後半): 同步寫入時間證據 (queriedAtMs/markerTimeMs/viaGateway)；
// 呼叫端傳入本次探測的 wall-clock，沿用 HookStateProvider 的 scope 解析。
// P4：queriedAtMs 無預設 (強制顯式，免 0L 預設誤讀為真實時間)。
fun UiState.withHookDiagnosis(diagnosis: HookDiagnosis, queriedAtMs: Long = 0L): UiState = copy(
    hookStatus = diagnosis.status,
    hookActive = diagnosis.active,
    hookScopes = diagnosis.scopes,
    hookQueriedAtMs = queriedAtMs,
    hookMarkerTimeMs = HookMarker.parseTimeMs(diagnosis.raw)?.takeIf { it > 0 },
    hookViaGateway = diagnosis.viaGateway,
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

// PR-6 P4 (#27 架構整理)：扁平時間證據欄位的衍生視圖。isWritable 與
// MainActivity hook 行經此組裝 HookEvidence 再 displayText，消滅手動組裝；
// 扁平欄位 (hookStatus/hookScopes/hookQueriedAtMs/hookMarkerTimeMs/
// hookViaGateway) 保留 (匯出/測試仍直讀).
fun UiState.hookEvidence(): HookEvidence = HookEvidence(
    status = hookStatus,
    scopes = hookScopes,
    queriedAtMs = hookQueriedAtMs,
    markerTimeMs = hookMarkerTimeMs,
    viaGateway = hookViaGateway,
)
