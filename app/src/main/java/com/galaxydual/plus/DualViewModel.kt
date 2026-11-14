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
import com.galaxydual.core.routePrivilegeFromProbes
import com.galaxydual.hook.HookMarker
import com.galaxydual.hook.PolicySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

// Phase 3 (hook-policy-source P1, 階段 3):政策寫入可觀測相位，唯一真相在
// UiState.policyWritePhase（render 只讀 state，不讀 ConcurrentHashMap）。
// - IDLE：無政策寫入/清除在途（無論有無 pending；request 建 pending 後為 QUEUED）。
// - QUEUED：已排隊等 opMutex（confirm 已呼叫但 save 未開始；request 建 pending
//   後亦為 QUEUED；clear 已呼叫但 store IO 未開始亦為 QUEUED）。此時取消仍有效。
// - SAVING：store save/clear IO 已實際開始（不可取消；cancel 為 no-op）。
// - RECONCILING：save/clear 已逾時、結果未知，mutex 已釋放，reconciler 正在
//   背景核對（loadResult 重試 N=3）。pending 保留（confirm；clear 本無 pending），
//   新 request/confirm/clear/cancel 一律拒絕（BUSY/核對中），render 視同 SAVING
//   禁用確認/取消。終態由 reconciler 以 TOCTOU 守衛發布（Known→成功/REFRESHED，
//   Unknown→REFRESH_FAILED），相位回 IDLE。Additive：既有 IDLE/QUEUED/SAVING
//   語義不動，僅新增此相位。
// 相位與 loading 在同一 _state.update 翻轉（特別是 QUEUED→SAVING 與
// loading=true 同一 emission），消除「loading 已 true 但 saving 集合尚未 add」
// 的一幀 stale（舊碼先 update loading=true 才 add，dialog 仍可取消一幀）。
enum class PolicyWritePhase {
    IDLE,
    QUEUED,
    SAVING,
    RECONCILING,
}

private val removeGeneration = java.util.concurrent.atomic.AtomicLong()

// Stage2 (hook-policy-source P1, 階段 2):兩階段政策寫入的待確認快照。
//
// 由 requestPolicyWrite() 建立 (當下已勾選映射出的包名集合)，confirmPolicyWrite()
// 執行寫入 HookPolicyStore，cancelPolicyWrite() 丟棄 (取消≠寫入≠安裝)。
// 世代單調唯一 (policyWriteGeneration)，跨請求覆寫即舊世代失效 (與
// PendingRemove 同式)。
private val policyWriteGeneration = java.util.concurrent.atomic.AtomicLong()

// Stage2 (hook-policy-source P2 #5):政策草稿版本號，避免晚到的 init/refresh
// 載入覆寫較新的 save 所寫入的 policyDraft。
private val policyDraftVersion = java.util.concurrent.atomic.AtomicLong()
// Stage G1 世代拆分：顯示 vs 所有權。
// - policyFreshnessGen：refresh/load 顯示 TOCTOU（重疊 load 丟棄、盲清 arm、
//   成功/失敗顯示 fence）。refresh 期中（Tx 存活）只推進此者，不碰 ownership。
// - policyOwnershipGen：Tx 所有權（reconciler 世代、late-join 結清判定）。
//   reconciler 只比 version＋ownership；refresh 期中永不推進 ownership，
//   故 yielding 不致 TOCTOU 丟棄 reconciler（保留 fence）。
// Read-generation guard for policy draft loads: overlapping loads discard
// stale results (load A must not overwrite a newer load B).
private val policyFreshnessGen = java.util.concurrent.atomic.AtomicLong()
private val policyOwnershipGen = java.util.concurrent.atomic.AtomicLong()
data class PendingPolicyWrite(
    val packages: Set<String>,
    val requestedAtMs: Long,
    val droppedCount: Int = 0,
    val generation: Long = policyWriteGeneration.incrementAndGet(),
    // Stage J3 (P2#3 foreign-vs-pending)：綁定請求當下的修訂版本（顯示 fence）。
    // request 時快照 policyDraftVersion；confirm 在 opMutex
    // 內、store IO 前比對失配即 abort（UNKNOWN_REFRESH），防盲覆寫外來修訂。
    // 僅比 version（freshness-only 推進為重疊 load 顯示守衛，不代表修訂推進，
    // 不得使 pending 失效，故不快照／不比對，免誤殺）。
    // -1＝legacy 注入（單測直接 setPending，未經 request），比對時跳過版本檢查
    // （僅走 stale/unknown 門控，保既有測試綠）。
    val baseDraftVersion: Long = -1L,
    // Stage L1 P1: coordinator commit revision snapshot (authoritative).
    // Stage O1 P1#1: binds the AUTHORITATIVELY OBSERVED revision (the rev at which
    // this VM actually read the displayed draft — see ObservedPolicySnapshot and
    // markObservedKnownAt/markObservedKnown), NOT coord.currentRevision() sampled
    // at request time. Sampling current here would borrow the already-advanced B
    // revision for a dialog still showing stale A, letting confirm overwrite unseen
    // B. confirm pre-IO 在 policyRevisionMutex+commitMutex 下比對失配即
    // abort（UNKNOWN_REFRESH），防 CAS-visible-before-event 窗口：舊 pending C
    // 不得覆寫未見 B。
    // -1L=legacy 注入（跳過 revision 檢查，僅走 stale/unknown 門控）。
    val baseCoordinatorRevision: Long = -1L,
)

data class PendingRemove(
    val pkg: String,
    val dualId: Int,
    val dualSerial: Int,
    val profileLabel: String,
    val requestedAtMs: Long,
    val generation: Long = removeGeneration.incrementAndGet(),
)

// PR-7 P2 (issue #26, 階段 1):批次執行逐項結果。
//
// 批次走 `cloneVerifiedExpected` 逐項 pin：非原子交易、逐包驗證
// (見 cloneSelected)，不引入
// 新的批次寫入通道 (留待 #21/#22/#23/#24/#27 完備後的後續階段)。
// - outcome 直接沿用 CloneOutcome (成功/部分成功/unknown 分流與單筆一致)。
// - appKey 為勾選時的來源身分鍵 (serial:package);pkg 為實際寫入包名。
// - 取消勾選/清除選取永不產生 BatchItemResult (見 clearSelectionLocal)。
data class BatchItemResult(
    val appKey: String,
    val pkg: String,
    val outcome: CloneOutcome,
)

// 一次「建立 N 個分身」的彙總:逐項結果 + 成功/失敗/未知計數。
// failed = 非 Success 且非 unknown (含 NoDual/Invalid/InstallFailed/
// VerifyFailed/TargetMismatch；真 Absent 的 NoDual 走 failed 口徑＋M13 短路，
// 與逐項中途明細一致)；unknown = VerifyUnknown/ChannelError
// (寫後未查證或身份暫時無法判定，需重查，不算失敗)。
data class BatchReport(
    val items: List<BatchItemResult> = emptyList(),
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val unknownCount: Int = 0,
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
// the selected channel proved itself, degrade the Auto probe-based degrade
// explanation (typed WriteBlock, null when none), profiles/cloned the device ground truth,
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
    // (RefreshOutcome ChannelError vs Loaded variants NoDual/Unknown/
    // GroundTruth), not by this flag.
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
    // Auto probe-based degrade explanation (P25, P2-3 typed). Non-null only for
    // AUTO when the probes overrode the file heuristic downward (su denied ->
    // Shizuku) or when both probes failed (read-only); recovery/upgrade back
    // to ROOT is silent by design (null, readyMsg empty -> silent).
    // Explicit choices clear it. Resolved via blockText (auto_degrade_*).
    val degrade: WriteBlock? = null,
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
    // Phase H live-health 誠實門控 (Codex P1 #2732)：本次開機是否已證實 hook
    // 存活，預設 false。diagnoseHookState 只給存在性證據 (marker survives
    // reboots，無 boot-identity/freshness)，永不將此欄設 true；未來比較
    // markerTimeMs vs (nowWallMs - nowMonoMs) 或 BOOT_COUNT/boot_id 通過才
    // 由專屬 setter 設 true。UI live 宣稱一律經 hookLiveProven() 門控。
    val hookBootFresh: Boolean = false,
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
    // Stage2 (hook-policy-source P1, 階段 2):白名單政策草稿。
    // - policyDraft:HookPolicyStore 內已保存的包名集合 (顯示計數用；預設空，
    //   只在 init/寫入/清除/refreshPolicyDraft 後更新，勾選變更永不自動寫入)。
    // - pendingPolicyWrite:兩階段寫入的待確認快照 (沿用 remove ack 模式：
    //   世代綁定＋二次確認＋TTL，見 request/confirm/cancelPolicyWrite)。
    val policyDraft: Set<String> = emptySet(),
    // True once a policy draft has been successfully read from the store
    // (unknown/unavailable is distinct from a confirmed empty draft).
    val policyDraftKnown: Boolean = false,
    // Stage L4 P2#3: corrupt flag (CCE). True only when the last load observed
    // LoadResult.Corrupt; Unavailable keeps this false. Request refuses both
    // (Known==false); clear blind-clear only for Unavailable; Corrupt goes via
    // repair flow (requestRepair/confirmRepair). Render shows corrupt_detected
    // when true.
    val policyDraftCorrupt: Boolean = false,
    val pendingPolicyWrite: PendingPolicyWrite? = null,
    // Phase 3：政策寫入可觀測相位＋單一 epoch（唯一真相，render 只讀此二欄）。
    // - policyWritePhase：IDLE/QUEUED/SAVING（見 PolicyWritePhase KDoc）。
    //   request 建 pending 即 QUEUED；confirm 在 opMutex 內同一 _state.update
    //   翻 QUEUED→SAVING（與 loading=true 同 emission）；完成/取消/TTL/拒絕
    //   即回 IDLE（abort 但 pending 保留時回 QUEUED，見 confirm 內註）。
    //   clear 在途亦經 QUEUED→SAVING→IDLE（clear 起始即清 pending，故 dialog
    //   以 pending==null 關閉，phase 僅供 positive/negative 門控與測試）。
    // - policyEpoch：相位遷移單調計數（request/cancel/confirm/clear 每次相位
    //   變遷即 +1；QUEUED→SAVING 保持同一 epoch 以標示同一 op 推進，僅翻相位）。
    //   與 policyWriteGeneration（pending 身份）/clearGeneration（clear key 唯一）
    //   /policyDraftVersion+policyFreshnessGen（顯示 TOCTOU fence，Phase1 語義不動）
    //   之關係見 DualViewModel 內各計數器 KDoc（Tx 所有權另由 policyOwnershipGen）。
    val policyWritePhase: PolicyWritePhase = PolicyWritePhase.IDLE,
    val policyEpoch: Long = 0L,
    // Phase 2：手動重新整理草稿的可觀測位（獨立於全域 loading，避免誤清
    // sibling busy；見 refreshPolicyDraft + hasActiveSibling 守衛說明）。
    // true = 至少一次 refreshPolicyDraft 在途；完成（成功/Unknown/丟棄）
    // 即回 false（多重疊加由計數器兜底，最後完成者清位）。
    val policyRefreshing: Boolean = false,
    // PR-7 P2 (issue #26, 階段 1):個人來源 catalog + 多選狀態 (唯讀展示 +
    // 勾選,執行見 cloneSelected)。sourceCatalog 由 MainActivity 經
    // loadSourceApps 載入後注入;搜尋/篩選/排序走 SourceInventory 純函數。
    // selectedKeys 為 AppKey (serial:package) 集合;取消勾選/清除只改此集合,
    // 永不呼叫 uninstall (見 clearSelectionLocal)。batchReport 為最近一次
    // 批次執行的逐項結果 (成功/失敗/未知分開,部分成功如實呈現)。
    val sourceCatalog: List<SourceAppEntry> = emptyList(),
    val sourceQuery: String = "",
    val includeSystemApps: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val batchReport: BatchReport? = null,
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
// (refreshJob/refreshPending/reconnectPending/recheckJob/recheckPending/foregroundJob/foregroundPending/inFlightWrites plus
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
// in LSPosed mode. The LSPosed gateway stays the NoOp placeholder (the
// whitelist policy source is wired, end-to-end DEVICE-UNVERIFIED, but the
// LSPosed execution channel remains a NoOp placeholder): writes under it are
// refused with an explicit reason, never executed. The marker never feeds privilege routing
// (hook signal never enters routing; `routePrivilegeFromProbes` has no `hasLsposed`
// parameter): a present marker is not proof of a live, effective hook, so it
// must not grant privilege.
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
    // explicit selection. Copied into UiState.degrade by probeChannels.
    private var autoDegrade: WriteBlock? = null,
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
    // defaultDirectMarkerReads() (direct MarkerReads of the SIX marker
    // paths: per-scope primary/fallback + legacy single-files, unioned). hookDiagnosticGateways supplies the CURRENT
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
    // 同意時效/TTL 單調時鐘 (見上)：request 的 requestedAtMs 與 confirm 的 TTL
    // 差值皆經此取時。生產由 factory 以 SystemClock.elapsedRealtime() 接線
    // （boot-monotonic，NTP-immune；僅在本 boot 可比，不用於 wall-clock 顯示——
    // 那是 nowWallMs 的職責）；本 VM class 預設為 System.nanoTime()/1e6
    // （測試多注入 fake）。兩者皆非 NTP 可步進，consent TTLs tolerate neither
    // NTP steps nor reboot drift；pending 為 in-memory only，consent TTLs
    // 亦不跨 process 存活（process 死亡即消失，無持久化比對問題）。
    private val nowMonoMs: () -> Long = { System.nanoTime() / 1_000_000L },
    // PR-7 P2 (issue #26, 階段 1):選取持久化 store。生產由 MainActivity 綁定
    // SharedPreferences (selectionStore());JVM 測試注入 HashMap 後端;null
    // 表示無持久化 (既有測試路徑,所有選取操作退化為純記憶體)。
    private val selectionStore: SelectionStore? = null,
    // Stage2 (hook-policy-source P1, 階段 2):政策草稿 store。生產由 factory 綁定
    // LSPosed remote preferences (remoteHookPolicyStore,group dual_policy,與
    // hook 側同一 group；service 缺席時 fail-closed)；JVM 測試注入
    // HookPolicyStore lambda 後端；null 表示無持久化 (所有政策操作退化為
    // 拒絕並說明，不碰裝置)。
    private val hookPolicyStore: HookPolicyStore? = null,
    // Stage2:政策草稿寫入的 owner-user 門控。hook 讀 user 0 的 remote prefs
    // (此為 assertion,非實機證明,DEVICE-UNVERIFIED);非 owner 用戶實例寫入的
    // 是自己 user 的 group,hook 讀不到,故要求由 owner user 寫入。生產由
    // factory 以 Process.myUid() 拆解注入 (見 MainActivity userIdFromUid);
    // 預設 -1 (未知即拒絕,fail-closed):未經注入的實例一律擋在門控外,不得
    // 誤用預設 0 (owner) 放行寫入。JVM 測試顯式注入所需 userId (owner 測
    // 試傳 0,非 owner 測試傳非 0)。
    private val currentUserId: () -> Int = { -1 },
    // p28-1 review（en 殘留）：寫入拒絕理由在地化。VM 本體不碰 Android
    // Context：文字解析經此注入——生產由 factory 以 Application Context 對接
    // values/strings.xml（見 MainActivity.resolveBlockReason）；JVM 測試注入
    // 假值（見 BlockReasonTestFake.zhTestBlockText）。預設回退 key 名
    // （WriteBlockKind.name），保證主程式碼零硬編碼字面。
    private val blockText: (WriteBlock) -> String = { it.kind.name },
    // Phase 5 polish：政策 VM 訊息資源化。VM 本體不碰 Android Context：文字
    // 經此注入——生產由 factory 以 Application Context 對接 values/*/strings.xml
    // （policy_busy/policy_owner_* 等＋policy_dropped/policy_written plurals，
    // 見 MainActivity.DualViewModelFactory）；JVM 測試預設即繁中字面
    // （PolicyText.defaultText，與 zh 資源同文），或注入假值鎖定調用點
    // （見 PolicyWriteTest policyTextInjection）。前身硬編碼繁中＋一處英文
    // "already in progress: policy write"，現一律經此 single source。
    private val policyText: (PolicyText) -> String = { it.defaultText() },
    // Phase 4:confirm/clear 持 opMutex 橫跨 onIo 的有界超時（預設 10s）。
    // 可注入以便 JVM 測試鎖定超時轉 FAILED+Unknown（fake delay > timeout，
    // 見 PolicyWriteTest 超時測試；TimeoutCancellationException 先於
    // CancellationException 捕捉順序亦由該測試鎖定——順序顛倒則超時被重拋為
    // 取消，測試失敗）。生產一律預設值，不另傳。
    private val policyIoTimeoutMs: Long = POLICY_IO_TIMEOUT_MS,
    // Stage G2 (P2#4#5)：跨 VM 最小 coordinator（App-scoped，見 PolicyTxCoordinator）。
    // null=本地單 VM 模式（既有測試路徑，行為不變）；非 null 時 handoverTx 同步
    // 登記本地＋coordinator，onCleared 刻意不清除，新 VM init 認領。
    private val policyTxCoordinator: PolicyTxCoordinator? = null,
) : ViewModel() {
    companion object {
        // Consent 有效期，超期 confirm 拒絕重請 (PR-33 R3, P2): a dialog left
        // open longer than this must re-request; confirmRemove clears the
        // stale pending with INVALID + 重新確認 and runs zero uninstalls.
        const val REMOVE_CONFIRM_TTL_MS = 5 * 60 * 1000L
        // Phase 3：政策寫入同意有效期別名＝REMOVE_CONFIRM_TTL_MS（共用同一值：
        // 移除與政策寫入皆為「對話框開啟後 generation 綁定＋二次確認＋TTL」，
        // 同意有效期語意相同，故不另設值；政策路徑一律用此別名，移除路徑續用
        // 原名，grep 可區分調用點）。
        const val POLICY_CONFIRM_TTL_MS = REMOVE_CONFIRM_TTL_MS
        // Stage D (P2 blind-clear 有界 + 世代綁定)：blind-clear 二次確認有效期
        // ＝POLICY_CONFIRM_TTL_MS（與政策寫入同意同值：皆為「對話框/告警後
        // generation 綁定＋二次確認＋TTL」，5min 語意相同，故不另設值）。
        const val BLIND_CLEAR_TTL_MS = POLICY_CONFIRM_TTL_MS
        // Phase 4:confirm/clear 持 opMutex 橫跨 onIo save/clear 的有界超時。
        // RemotePreferences 為 binder IPC,無超時會長 pin opMutex 餓死其他操作;
        // 超時轉既有 FAILED + Unknown fence (不新增語義,見下 withTimeout 處)。
        // Stage A：逾時改走 RECONCILING + reconciler（見 confirm/clear），FAILED
        // 僅保留給「已證明失敗」（store 回 false/拋出），逾時（未知）一律 CHANNEL。
        const val POLICY_IO_TIMEOUT_MS = 10_000L
        // Stage A：reconciler loadResult 重試次數（store 零 API 新增，僅重讀）。
        const val POLICY_RECONCILE_RETRIES = 3
        // Stage A review：reconciler 退避（選 (a)：加退避＋重試間隔，不 await
        // 脫鉤的慢 save future）。逾時交接後慢 save 仍在背景續跑，首讀若立即發出
        // 極可能讀到寫前舊值（stale-read 誤判 mismatch）；500ms 首延給慢 save
        // 落盤機會，重試間 1s 避免對 binder 熱迴圈。捨 (b) 延長 await 脫鉤句柄：
        // 逾時已證明該 future 不可信（可能永不回），再等只會重 pin reconciler，
        // ground truth 應來自重讀而非殘留 future。
        const val POLICY_RECONCILE_INITIAL_DELAY_MS = 500L
        const val POLICY_RECONCILE_RETRY_DELAY_MS = 1_000L
        // Stage F2 (P2#4)：reconciler 總預算寫死上限（join＋3 讀＋退避合計）。
        // 無論注入 policyIoTimeoutMs 多大（預設 10s 下三讀即 30s），總耗時有界；
        // 超限即誠實 unresolved（REFRESH_FAILED/CHANNEL＋Known=false＋pending
        // 保留＋phase 續留 RECONCILING，不回 IDLE、不解 fence）。15s 容納測試
        // 小超時全程（200ms 檔約 3.3s）與生產 binder 抖動，預設大超時則提前截斷。
        const val POLICY_RECONCILE_TOTAL_BUDGET_MS = 15_000L
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

    // P2-4 Busy waiter handle (bounded 4 rounds + _latest collector backstop).
    // attachBusyWaiter stores the Job here (replacing any prior waiter);
    // dispose()/onCleared() cancel it explicitly. viewModelScope cancellation
    // alone is NOT relied upon (dispose() does not cancel the scope in tests;
    // production scope cancel happens only in onCleared), so an explicit
    // handle is required to avoid a 300s leak. The _latest collector
    // (observeRevisionUpdates) is the convergence backstop after the bounded
    // rounds expire.
    private var busyWaiterJob: Job? = null

    // In-flight write dedup keys ("clone:<pkg>" / "request-remove:<pkg>" /
    // "remove:<pkg>@<id>#<serial>#<ts>", PR-3 stage 1, issue #24 + PR-4 P23
    // stage 2, PR-33 R2 adds the serial to the generation).
    // A duplicate op while the first is still running reports
    // "already in progress" instead of queueing behind the mutex. Clone and
    // remove use distinct keys so an intentional clone-then-remove sequence on
    // the same package is never collapsed. Main-confined (see class doc).
    //
    // CE convention (F5 + 階段L): a cancelled job clears loading before rethrowing
    // ONLY when no active sibling still owns busy -- the four coalesced entries
    // check hasActiveSibling(myJob) first (holder 續持 busy 時維持 true)，無 sibling
    // 才 loading=false，一律 rethrow (reconnect/refresh/recheck/probe/foreground
    // 皆同，成功/失敗清位不變；取消路徑補清）。唯 selectChannel 無 CE catch (latest-wins 靠
    // successor 的 leading busy 覆寫)；terminal scope-cancel 隨 ViewModel
    // 丟棄狀態，無殘留。
    // Thread-safe: touched from multiple coroutines on viewModelScope.
    private val inFlightWrites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // P1 #1 queued-vs-committing: subset of policy-write keys whose store-save
    // IO has actually started (added just before onIo { store.save(...) },
    // removed after the commit decision). A cancel that lands while the key is
    // merely queued (in inFlightWrites but not here) still clears pending so
    // the queued confirm aborts; only a save already in flight is uncancellable.
    private val policyWriteSaving = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Stage A (P1 超時不確定性)：核對中 key 集合。confirm/clear 逾時即由外層
    // finally 自 inFlight/saving 交接至此（mutex 已釋放），reconciler 終態 finally
    // 移除。入口守衛讀 phase==RECONCILING OR 本集合非空（雙層，防 state/集合
    // 瞬時窗口漂移）；store 零 API 新增，僅 VM 側追蹤。
    private val policyReconciling = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Stage F1 (交易所有權核心 P1#1+P1#2排序+P2#3)：逾時交易的唯一擁有者。
    // - Tx{txId,deferred,sanitized,store,isClear,key}：confirm/clear 逾時即保留
    //   脫鉤 deferred 句柄並登記 Tx（opMutex 照常釋放，但 Tx 存活＋RECONCILING＋
    //   reconcilingKeys 三者同生同滅）；終態只許 Tx-settle CAS 清除
    //   （settlePolicyTx：activePolicyTx.compareAndSet(tx,null)＋reconciling移除），
    //   任何非擁有者不得移除 fence。
    // - 守衛一律讀 Tx（isPolicyReconcilingLocked 含 activePolicyTx!=null，防
    //   state/集合瞬時窗口漂移；request/confirm/clear/cancel 共用）。
    // - 全域 refresh()/reconnect() 入口 Tx 存活即併發丟棄（選併發丟棄，不選短路
    //   BUSY：全局 refresh 不擁有 policy busy，誤發 BUSY 會覆寫 TIMEOUT 提示；
    //   丟棄即無聲 no-op，保留 RECONCILING＋TIMEOUT＋pending，draft 不被覆寫。
    //   政策 refreshPolicyDraft() 仍允許併發，世代守衛最新者勝，F1-simple；
    //   F2 再議 BUSY/預算化 join）。
    // - reconciler 先 join 句柄（有界 withTimeout(policyIoTimeoutMs)，F1-simple；
    //   F2 預算見 StageF2）：true→驗證讀，false/throw→FAILED+Unknown，
    //   pending→保留 fence 續讀（Stage G1：deferred 未完成時任何 Known（A 或 B）
    //   皆 inconclusive 續留，不搶結；Known(B) 永 inconclusive（刪末輪特例），
    //   Known(A) 視 stale 續留，不得 mismatch-settle）。
    // - 快徑收斂（P2#3）：ok==true 先一次 fresh read-after-ack，==sanitized 才
    //   WRITE_SUCCESS，!=sanitized 即 REFRESHED；Unknown 信 ack（保既有版本守衛
    //   語義：stalePending／slowUnknown 的 ackRead 可能 Unknown（阻塞逾時／空後
    //   端），仍以 sanitized 發布 SUCCESS；Stage G1 的嚴格 settle（Unknown 不信
    //   ack）僅適用 reconciler／late-join 權威讀，見 decidePolicyTxSettle，快徑
    //   維持既有以免既有 fence 測試抖動）。
    // - Stage G1 settle 唯一真相（decidePolicyTxSettle）：可 settle IFF
    //   （deferred 已完成＋權威讀==預期→SUCCESS；deferred 已證失敗→FAILED+Unknown；
    //   deferred 完成＋權威讀非預期→mismatch REFRESHED）。deferred 未完成時任何
    //   Known（A 或 B）都不 settle（保留 fence＋RECONCILING＋pending；顯示可
    //   yielding 更新但 Known=false 或維持 Unknown）。
    // - TOCTOU-discard：F1-simple 仍回 IDLE+REFRESHED（保 84 綠；F2 再改移交/
    //   保留不回 IDLE，屆時 refresh 推進不終止）。
    // - Stage G1 世代拆分：reconciler 只比 version＋ownership（policyOwnershipGen）；
    //   refresh 期中（Tx 存活）永不推進 ownership，只 yielding（不碰 phase/pending）。
    // - Stage G2：Tx 攜 pending（跨 VM 認領：新 VM 繼承 pending＋
    //   RECONCILING＋Unknown＋reconciler join 同一 deferred）。
    data class PolicyTx(
        val txId: Long,
        val key: String,
        val deferred: Deferred<Boolean>,
        val sanitized: Set<String>,
        val store: HookPolicyStore,
        val isClear: Boolean,
        val pending: PendingPolicyWrite? = null,
        // Stage M1 P1: repair is a first-class destructive Tx (delete Corrupt→
        // Known empty). isRepair distinguishes its terminal kind (REPAIRED /
        // REPAIR_FAILED + corrupt retention) from plain clear (CLEAR_SUCCESS /
        // CLEAR_FAILED + corrupt wash). Fencing/claim/emit paths are shared;
        // only terminal messages + corrupt-bit handling branch on this flag.
        // isClear stays true for repair (destructive delete-to-empty) so generic
        // isClear gating (destructive vs write) remains correct.
        val isRepair: Boolean = false,
        // Stage N2 P1: execution gate — LAZY deferred must NOT be started/joined
        // until final validation + baseline verified. False = PREPARING (must
        // not start: join paths return without awaiting; inheritance aborts).
        // True = ARMED/STARTED (validated, may start/join). Set exactly once via
        // armAndStartTxDeferred() immediately before start() (save/clear: pre-IO
        // gate after checks; repair: final fresh revalidation after pre-IO gate).
        // Fallback Tx with an already-running/completed eager deferred passes
        // true (validation already done at creation). Atomic: read on IO/Main.
        val executionArmed: AtomicBoolean = AtomicBoolean(false),
        // Stage O1 P2#2 (lifecycle): owner-liveness for shared LAZY ownership.
        // True until the owning VM explicitly revokes pre-start (dispose/onCleared
        // or its own pre-start abort). A newly attached VM decides liveness via
        // an inline single-snapshot under commitMutex (activeTx+ownerAlive+
        // deferred+pending/age, see init/async-reclaim/busy-waiter): live
        // PREPARING owner => must NOT cancel (mark self Unverified/Busy, wait
        // for owner); abandoned (false) => may abort/unclaim. Armed Txs ignore
        // this (inherit regardless).
        val ownerAlive: AtomicBoolean = AtomicBoolean(true),
        // Stage (PR#60 P2#2): typed repair outcome carried on the SHARED Tx.
        // Written by the repair deferred BEFORE it completes; all repair
        // terminals derive msg+msgKind from this — on any VM that observes
        // the tx (winner, reconciler, late-join, coordinator replay).
        val repairResult: AtomicReference<RepairResult?> = AtomicReference(null),
    )
    // Stage I1：無 coordinator 舊測試路徑的回退發號器（有 coordinator 一律經
    // 其 nextTxId() 全域發號，見四處建 Tx 點；本計數器僅 null-coordinator 用）。
    private val policyTxId = AtomicLong(0L)
    private val activePolicyTx = AtomicReference<PolicyTx?>(null)
    // Repair Success terminal (msg, kind) — mirrors the fast path's
    // construction exactly: a RepairedNoBackup recorded ON THE SHARED TX ⇒
    // REPAIRED_NO_BACKUP + CHANNEL; anything else (recorded Repaired, or
    // unknown/absent) ⇒ CORRUPT_REPAIRED + INFO ("backup attempted, not
    // guaranteed" wording). The outcome rides the Tx, so every VM that
    // observes the tx (winner, reconciler, late-join, coordinator replay)
    // derives the same pair — the entry dies with the tx, never outlives it.
    private fun repairSuccessTerminal(tx: PolicyTx): Pair<String, MsgKind> {
        var result: RepairResult? = try {
            tx.repairResult.get()
        } catch (_: Throwable) {
            null
        }
        if (tx.isRepair && result == null && tx.deferred.isCompleted) {
            // 防禦性二次確認：repair 前的 set(ref) 在 resumeWith 前完成，完成後的
            // 讀理論上必見。此處仍做一次有窮自旋重讀（sub-millisecond），以排除
            // 任何可觀察層級的發布競態（例如完成回調與本機讀之間的競爭窗），
            // 仍為 null 才視為異常。
            val deadline = System.nanoTime() + 2_000_000L // 2ms
            while (result == null && System.nanoTime() < deadline) {
                result = try { tx.repairResult.get() } catch (_: Throwable) { null }
            }
        }
        if (tx.isRepair && result == null) {
            // Defensive arm: repair completed ⇒ deferred completed ⇒ ref
            // non-null (same lambda order). A null here means the outcome was
            // lost between completion and terminal — count it; the terminal
            // keeps the honest CORRUPT_REPAIRED/INFO fallback wording.
            try { repairOutcomeMissingCount.incrementAndGet() } catch (_: Exception) { }
        }
        return if (result is RepairResult.RepairedNoBackup) {
            policyText(PolicyText(PolicyTextKind.REPAIRED_NO_BACKUP)) to MsgKind.CHANNEL
        } else {
            policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIRED)) to MsgKind.INFO
        }
    }
    // Pure leaf lock coordinating atomic local transitions of
    // (activePolicyTx, policyReconciling, policyWritePhase) so no caller can
    // observe IDLE while activePolicyTx or policyReconciling still holds tx.
    // Never acquire suspending locks or perform IO while holding policySettleLock.
    private val policySettleLock = Any()
    // Stage I2 (P2#3#4)：跨 VM 陳舊修訂同步。
    // - lastRevisionSeenTxId/Key：settled 常駐收集的去重水位（全域單調 txId＋key
    //   雙元，replay=1 晚訂閱仍只應用一次；自發射即記水位，免自回放重 bump）。
    // - policyRevisionStale：本地有 activeTx 時收到異 Tx 終態即丟棄並記 stale，
    //   後續 request/confirm 強制重讀（UNKNOWN_REFRESH 拒絕，直至 refresh Known
    //   或自身 Tx 成功結清洗白）。
    private val lastRevisionSeenTxId = AtomicLong(-1L)
    private val lastRevisionSeenKey = AtomicReference<String?>(null)
    // Stage L3: rev 水位（committedRevision 序號，publish order = claim order）。
    // 去重＋overtake 守衛：rev 已知時由 rev 決定新舊（incoming rev < last 即
    // stale 丟棄，不覆寫新事實）；rev 任一為 -1 時退回 txId 排序守衛。
    // gap（rev 跳號 lastRev+1 < incoming rev）經 revisionGapCount/log 顯式標記，
    // 仍收斂最新（conflated 有序，不靜默丟失）。
    private val lastRevisionSeenRev = AtomicLong(-1L)
    // Stage L3 gap 可觀測計數（JVM 可讀；跳號僅標記不丟失）。
    internal val revisionGapCount = AtomicLong(0L)
    // Stage (PR#60 P2#2): defensive-arm observability — incremented exactly
    // when a repair Tx's Success terminal is published but the outcome carried
    // ON THE SHARED TX (repairResult) is still null (should be impossible:
    // the repair lambda sets it before completing). Mirrors revisionGapCount;
    // intentionally not wired into a diagnostics dump (no dump exists for it).
    internal val repairOutcomeMissingCount = AtomicLong(0L)
    private val policyRevisionStale = AtomicBoolean(false)
    // Stage O1 P1#1 (consent): authoritatively observed policy revision.
    // The coordinator committedRevision sampled at the moment this VM's current
    // Known draft was authoritatively read (fresh trusted load / terminal event /
    // reconciler ground-truth). requestPolicyWrite binds pending.baseCoordinatorRevision
    // to THIS (not currentRevision()), so a confirmation displaying stale A cannot
    // borrow the already-advanced B revision. Updated only on fresh Known transitions
    // via markObservedKnown(); never on stale-retained Known. -1L = never observed
    // (legacy/Unknown; request falls back to fail-closed Unknown path, confirm gates
    // use -1L skip only for legacy injected pendings).
    internal data class ObservedPolicySnapshot(val rev: Long, val dropGen: Long)
    private val observedSnapshot = AtomicReference(ObservedPolicySnapshot(-1L, 0L))

    // Stage O1 P1#1: last orphan-drop generation observed by this VM. Advanced
    // whenever this VM observes a drop (pre-IO recover) or a fresh Known read.
    // Request fails closed when coord.orphanDropGen != lastSeenDropGen (an unseen
    // placeholder was dropped without any _latest event).
    // Phase7 test seam: overrides the authoritative revision source for
    // TOCTOU barrier tests (fake currentRevision with call counting + latch).
    // Production stays null (coordinator.currentRevision()); tests set this
    // to a counting lambda so revBefore/revMoved/mark reads are
    // deterministically interleaved. Refresh is the representative pinned
    // path (PolicyToctouBarrierTest); init shares the same
    // sampledCurrentRevision()/markObservedKnownAt() helpers (same fail-closed
    // discard), and repair is additionally guarded by the atomic
    // completeWithRevisionChecked rev gate under commitMutex.
    internal var revisionSamplerForTests: (() -> Long)? = null

    private fun sampledCurrentRevision(): Long = try {
        val sampler = revisionSamplerForTests
        if (sampler != null) {
            try {
                sampler()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                -1L
            }
        } else {
            try {
                policyTxCoordinator?.currentRevision() ?: -1L
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                -1L
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        -1L
    }

    // P2-2: log-once (process lifetime) when an authoritative revision source
    // is attached but the sample throws or yields -1 — the fail-closed signal
    // for a rev gate that must never be opted out over unseen foreign commits.
    // Class-name-only message (log hygiene); never package names.
    private val revisionSampleFailureLogged = AtomicBoolean(false)
    private fun noteRevisionSampleFailure() {
        if (revisionSampleFailureLogged.compareAndSet(false, true)) {
            try {
                System.err.println(
                    "[DualViewModel] policy revision sample failed with an authoritative source attached; " +
                        "failing closed (UNKNOWN_REFRESH) until a verified sample succeeds",
                )
            } catch (_: Throwable) {
            }
        }
    }

    // P2-2 (fail-closed revision sampling): distinguish "no authoritative
    // source" (legacy null-coordinator path with no test sampler → -1L, rev
    // gate stays opt-out, semantics unchanged) from "sampling FAILED /
    // unverifiable" (non-null coordinator or test sampler attached, but the
    // sample throws or yields -1). The latter must abort fail-closed with
    // UNKNOWN_REFRESH — never treat it as rev-gate-opt-out. Returns the
    // authoritative revision (>= 0) on the healthy path, -1L ONLY on the
    // legacy null-coordinator path, or null (= abort required) when it cannot
    // be verified. CE rethrown.
    private fun authoritativeRevisionSample(): Long? {
        val hasAuthoritative: Boolean = try {
            policyTxCoordinator != null || revisionSamplerForTests != null
        } catch (_: Exception) {
            // Broken field read: assume authoritative (fail-closed).
            true
        }
        if (!hasAuthoritative) return -1L
        val r: Long? = try {
            sampledCurrentRevision()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
        if (r == null || r == -1L) {
            noteRevisionSampleFailure()
            return null
        }
        return r
    }

    // Phase7 seam: explicit-rev variant of markObservedKnown for barrier
    // tests. Production calls markObservedKnown() (samples via
    // sampledCurrentRevision); tests call markObservedKnownAt(rev) directly
    // or drive the full path with revisionSamplerForTests.
    // Phase7 observability for tests: current authoritatively-observed rev.
    // Fail-closed: any Throwable reading the atomic returns -1L (never throws).
    internal fun observedRevForTests(): Long = try {
        observedSnapshot.get().rev
    } catch (_: Throwable) {
        -1L
    }

    internal fun markObservedKnownAt(rev: Long, dropGen: Long? = null) {
        try {
            val targetRev = if (rev != -1L) {
                rev
            } else {
                val lastR = try { lastRevisionSeenRev.get() } catch (_: Throwable) { -1L }
                if (lastR != -1L) lastR else observedSnapshot.get().rev
            }
            val dg = dropGen ?: try {
                policyTxCoordinator?.orphanDropGen?.get() ?: 0L
            } catch (_: Throwable) {
                0L
            }
            observedSnapshot.set(ObservedPolicySnapshot(targetRev, dg))
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
    }

    private fun markObservedKnown() {
        val cur = sampledCurrentRevision()
        val dg = try {
            policyTxCoordinator?.orphanDropGen?.get() ?: 0L
        } catch (_: Throwable) {
            0L
        }
        markObservedKnownAt(cur, dg)
    }

    // 統一 orphanDropGen 與修訂陳舊門控：於 request / confirm pre-IO 統一校驗。
    private fun checkOrphanDropAndRevisionStaleLocked(): Boolean {
        val coordO = policyTxCoordinator ?: return !policyRevisionStale.get()
        if (hookPolicyStore == null) return !policyRevisionStale.get()
        val snap = observedSnapshot.get()
        val observed = snap.rev
        if (observed != -1L) {
            val curRev = try { coordO.currentRevision() } catch (_: Exception) { -1L }
            if (curRev == -1L || curRev != observed) {
                try { policyRevisionStale.set(true) } catch (_: Exception) { }
                try { markPolicyUnknownLocked() } catch (_: Exception) { }
                return false
            }
            val curDrop = try { coordO.orphanDropGen.get() } catch (_: Exception) { 0L }
            val seenDrop = snap.dropGen
            if (curDrop != seenDrop) {
                val dropRev = try { coordO.orphanDropRev.get() } catch (_: Exception) { -1L }
                if (dropRev == -1L || dropRev >= observed) {
                    try { policyRevisionStale.set(true) } catch (_: Exception) { }
                    try { markPolicyUnknownLocked() } catch (_: Exception) { }
                    return false
                }
                // Stale drop (older than observed): catch up silently via CAS loop.
                var updated = false
                var guard = 0
                while (!updated && guard++ < 8) {
                    val cur = try { observedSnapshot.get() } catch (_: Exception) { snap }
                    if (cur.dropGen == curDrop) break
                    // Preserve newer rev if concurrently advanced; only advance dropGen.
                    val merged = if (cur.rev != snap.rev) cur.copy(dropGen = curDrop) else snap.copy(dropGen = curDrop)
                    try {
                        updated = observedSnapshot.compareAndSet(cur, merged)
                    } catch (_: Exception) {
                        break
                    }
                    if (!updated && try { observedSnapshot.get().dropGen } catch (_: Exception) { curDrop } == curDrop) break
                }
            }
        }
        if (policyRevisionStale.get()) {
            try { markPolicyUnknownLocked() } catch (_: Exception) { }
            return false
        }
        return true
    }

    // Verified mark: only stamps revBefore when coordinator still at revBefore.
    // Returns false + fail-closed stale when a concurrent commit landed between
    // the IO second-sample and this mark (prevents stale-read absorbing newer rev).
    private fun markObservedKnownVerified(revBefore: Long, dropGen: Long? = null): Boolean {
        if (revBefore == -1L) {
            try { markObservedKnownAt(revBefore, dropGen) } catch (_: Exception) { }
            return true
        }
        val cur = try { sampledCurrentRevision() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { -1L }
        if (cur == -1L || cur != revBefore) {
            try { policyRevisionStale.set(true) } catch (_: Exception) { }
            return false
        }
        try { markObservedKnownAt(revBefore, dropGen) } catch (_: Exception) { }
        return true
    }
    // P1-1 (TOCTOU) invariant: every trusted-read Known path (init load,
    // refreshLocked, repair success) samples revBefore BEFORE the IO and discards
    // the Known (no disarm, no stale wash, no mark — stay Unknown, fail closed)
    // when currentRevision moved mid-IO. See the revBefore/revMoved pairs below.
    // Residual window (documented, not closed here): the revMoved re-check vs
    // markObservedKnown/disarm/stale-wash is a lock-free read-then-act pair,
    // so a concurrent commit landing between the check and the mark could still
    // wash. The pre-IO double door (opMutex→policyRevisionMutex→withCommitLock
    // union drain of replayCache+pendingPublish+committedRevision+dropGen, plus
    // the atomic rev-gated claim for repair) is the second defense: any unseen
    // B still blocks the next request/confirm even if this window is hit.
    // Stage J4 (J3#1 pre-IO TOCTOU)：修訂觀察與寫入路徑序列化的專用互斥。
    // - confirm pre-IO 門控（check base/stale＋lazy.start）在 opMutex 內再持此鎖；
    // - handleForeignSettled 的 stale 標記段同持此鎖（短臨界，無 IO、不長持）。
    // 與 opMutex 順序固定（opMutex→revision），collector 側僅持 revision，
    // 故無死鎖；tryLock 不用（臨界皆短，withLock 等待有界）。
    // Stage L1 P1: order opMutex→writeMutex→policyRevisionMutex→commitMutex;
    // never emit while holding mutex (stage payload under lock, emit after release).
    private val policyRevisionMutex = Mutex()
    // Stage L1 P1: test hook afterClaimBeforeEmit (default no-op) inside tryClaim
    // caller after CAS success before emit. Test p1_casBeforeEvent_noOverwrite gates
    // B reconciler here (CAS done, committedRevision bumped, replayCache no B)
    // while VM2 confirm C must abort via pendingPublish/revision gate.
    // Suspend function (not blocking): the winner may run on the test thread
    // itself under Dispatchers.Unconfined, so a blocking latch gate would pin
    // the very thread the test waits on (deadlock until gate timeout). A
    // suspending gate (e.g. Deferred.await) frees the thread instead.
    var afterClaimBeforeEmit: (suspend () -> Unit)? = null
    // Stage F2 (P2#4)：reconciler 專用獨立讀池（per-VM 單執行緒，執行緒名
    // policyReconcile）。Binder 不可中斷：逾時讀的執行緒滯留此池（不佔共用 IO
    // 池），呼叫側計預算（withTimeout 照常到期）。per-VM 而非全域：無限阻塞測
    // 試滯留的執行緒不得堵住其他 VM 的後續核對。執行緒 lazy 啟動（無核對即無
    // 執行緒）；onCleared/dispose 盡力關閉（阻塞中執行緒仍滯留至行程結束）。
    // Stage J4 (HoL)：單執行緒池＋withTimeout 無法搶佔 binder loadRaw（無視取消
    // 續跑），第 2/3 輪重試排隊於第 1 輪滯留執行緒之後，預算被隊列耗盡。
    // boundedReconcileRead 已改走 Dispatchers.IO 共享池（每輪獨立執行緒並行，
    // 慢讀不阻塞後續輪），本池保留作相容（close 仍安全），不再用於核對讀。
    private val policyReconcileDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "policyReconcile") }
            .asCoroutineDispatcher()

    private fun closeReconcileDispatcher() {
        try {
            (policyReconcileDispatcher as? ExecutorCoroutineDispatcher)?.close()
        } catch (_: Exception) {
            // Best effort: 阻塞中執行緒隨行程結束回收。
        }
    }
    // F1 Tx-settle：唯擁有者可清 fence（CAS＋reconciling移除同組）。回 true 表
    // 本 Tx 結清（含首次）；false 表 Tx 已非擁有者（併發已結，不重複移除）。
    // Stage G2：同清 coordinator（CAS，跨 VM 唯一真相；onCleared 刻意不清除，
    // 僅 settle 清）。
    // Stage I1：claim 先行——先經 tryCompleteClaim 做 coordinator 側 CAS，再清
    // 本地；刪除舊集中式「先讀 _state 後 emit」（讀-發窗口可被併發終態污染，敗者
    // 誤發射）；發射改由各終態發布點顯式 emitTxSettledSnapshot（claim 持有下呼叫，
    // 見下），settle 本體只做 CAS＋清理。
    // P2-3: lock-free CAS (no tryLock decoration). activeTx clear is a single
    // atomic CAS — holding commitMutex around it adds no atomicity (the CAS is
    // the fence). Multi-field snapshots that need atomicity use withCommitLock
    // at their own call sites (pre-IO gates); settle itself never takes the lock.
    private fun settlePolicyTx(tx: PolicyTx): Boolean {
        val coord = policyTxCoordinator
        if (coord != null) {
            try {
                coord.tryCompleteClaim(tx)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Best effort.
            }
        }
        return synchronized(policySettleLock) {
            val owned = activePolicyTx.compareAndSet(tx, null)
            policyReconciling.remove(tx.key)
            owned
        }
    }

    // Stage H2 P1: winner stranded-terminal replay (idempotent convergence).
    // When the winner's _state.update was discarded by a concurrent loser
    // clearing the local fence (activePolicyTx !== tx), the phase stays
    // RECONCILING while both fences are already empty and _latest already
    // holds this Tx's terminal. Re-apply the terminal once (IDLE + settled
    // draft/Known/msg, pending cleared) so the VM does not strand.
    // Conditions (ALL required, all lock-free single snapshots):
    // - phase still RECONCILING (winner update lost, not yet converged);
    // - dual fences empty (no new Tx in flight; foreign in-flight => skip,
    //   new op owns convergence);
    // - _latest identity is this Tx (own terminal, not foreign).
    // No-op otherwise (already IDLE, new Tx, or foreign terminal).
    // Note: the re-applied terminal is a simplified escape-convergence copy
    // (IDLE + settled draft/Known/msg, pending cleared). It intentionally does
    // not replicate every winner branch detail (repair/clear Corrupt flag
    // handling); winners publish the full terminal via the normal
    // claim->bump->update->emit->settle path, this replay only rescues a
    // stranded RECONCILING fence pair. With the pre-bump ordering the IDLE wash
    // window is closed, so the RECONCILING-only guard is sufficient.
    private fun advanceLastRevisionSeenRev(rev: Long) {
        if (rev == -1L) return
        try {
            lastRevisionSeenRev.updateAndGet { prev -> if (rev > prev) rev else prev }
        } catch (_: Exception) {
        }
    }

    private fun clearObsoleteLocalTxLocked(tx: PolicyTx, coord: PolicyTxCoordinator) {
        val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
        if (curLocal != null && curLocal !== tx) {
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
            return
        }
        val curCoord = try { coord.activeTx.get() } catch (_: Exception) { null }
        val shouldClear = (curLocal === tx || (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) &&
            curCoord != null && curCoord !== tx
        if (shouldClear) {
            try { activePolicyTx.compareAndSet(tx, null) } catch (_: Exception) { }
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
            try { policyRevisionStale.set(true) } catch (_: Exception) { }
            try { markPolicyUnknownLocked() } catch (_: Exception) { }
            _state.update { cur ->
                if (cur.policyWritePhase != PolicyWritePhase.SAVING &&
                    cur.policyWritePhase != PolicyWritePhase.RECONCILING
                ) {
                    cur
                } else {
                    cur.copy(
                        loading = false,
                        pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                        policyWritePhase = PolicyWritePhase.IDLE,
                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                        policyDraftKnown = false,
                        policyDraftCorrupt = tx.isRepair,
                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
            }
        } else {
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
        }
    }

    private fun clearObsoleteLocalTx(tx: PolicyTx, coord: PolicyTxCoordinator) {
        synchronized(policySettleLock) {
            clearObsoleteLocalTxLocked(tx, coord)
        }
    }

    private fun retainUnresolvedReconciling(tx: PolicyTx, corruptRead: Boolean = false) {
        val coord = policyTxCoordinator
        synchronized(policySettleLock) {
            val lNow = try { activePolicyTx.get() } catch (_: Exception) { null }
            if (lNow != null && lNow !== tx) return
            val cNow = try { coord?.activeTx?.get() } catch (_: Exception) { null }
            if (coord != null && cNow != null && cNow !== tx) {
                clearObsoleteLocalTxLocked(tx, coord)
                return
            }
            _state.update { cur ->
                if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                else cur.copy(
                    policyWritePhase = PolicyWritePhase.RECONCILING,
                    policyDraftKnown = false,
                    policyDraftCorrupt = tx.isRepair || corruptRead,
                    msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
        }
    }

    private fun replayWinnerTerminalIfStranded(tx: PolicyTx) {
        try {
            val coord = policyTxCoordinator ?: return
            synchronized(policySettleLock) {
                if (_state.value.policyWritePhase != PolicyWritePhase.RECONCILING) return
                try {
                    if (activePolicyTx.get() != null) return
                } catch (_: Exception) {
                    return
                }
                try {
                    if (coord.activeTx.get() != null) return
                } catch (_: Exception) {
                    return
                }
                val latest: PolicySettled = try {
                    coord.latest.value ?: return
                } catch (_: Exception) {
                    return
                }
                if (latest.txId != tx.txId || latest.key != tx.key) return
                if (latest.known) {
                    // 防禦性同步（與 winner 的 applyTerminal 一致）：replay 路徑把
                    // 本 VM 翻成 Known 時，亦把行程級 lastKnown 圍欄寫入，避免
                    // winner 於 stageRealPayload 後、applyTerminal 前死亡時，圍欄
                    // 落後於可觀察狀態。
                    try { noteLastKnownDraft(latest.draft) } catch (_: Exception) { }
                    try { disarmBlindClear() } catch (_: Exception) { }
                    try { disarmCorruptRepair() } catch (_: Exception) { }
                }
                try {
                    policyReconciling.remove(tx.key)
                } catch (_: Exception) {
                }
                _state.update { cur ->
                    if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                    else cur.copy(
                        pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                        policyWritePhase = PolicyWritePhase.IDLE,
                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                        policyDraft = if (latest.known) latest.draft else cur.policyDraft,
                        policyDraftKnown = latest.known,
                        policyDraftCorrupt = if (tx.isRepair && !latest.known) true else false,
                        msg = latest.msg,
                        msgKind = latest.msgKind,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: replay must never break the winner path.
        }
    }

    // Provider contract (SEC-L4/CODE-OPT #3): called under policySettleLock with
    // the local claim held OR with fences held; settles ONLY non-converged phases
    // (terminal _state.update is a no-op when policyWritePhase is outside
    // {QUEUED, SAVING, RECONCILING} as observed in cur); idempotent per tx via
    // the 64-entry set. fenceHeld carries the caller's knowledge of this VM's
    // local-fence ownership (see inside); null = let the helper decide.
    //
    // A2-3: stage rejected (stamped==null at call sites) while our placeholder may
    // still hold coordinator.pendingPublish — as "our" claim, new C observes busy/stale
    // for ~policyIoTimeoutMs (age-gated recover) with no path to converge.
    // Drop the EXACT-identity abandoned placeholder via the coordinator's
    // lock-free dropStagedPlaceholderIfMatch (no commitMutex needed; single CAS,
    // tombstoned on success) so the fence does not linger. Real/foreign/cleared
    // pending values are untouched (helper re-checks identity + placeholder-only).
    // Non-suspend by design: this runs under the VM's leaf policySettleLock.
    //
    // A3-3 (C1): this helper is the settle-like-a-terminal path for EVERY rejected
    // claim, including the FAILED rev-gate rejections and the success-side
    // claimForPublish(Checked) rejections, which can still hold the LOCAL fence
    // (activePolicyTx) and, for a rev-gate rejection, the coordinator's activeTx
    // claim. It therefore ALSO completes the coordinator claim
    // (policyTxCoordinator?.tryCompleteClaim(tx); no-op when the claim was already
    // consumed or is foreign). Idempotent: a bounded per-VM identity set of
    // settled txs makes a double invocation (e.g. caller pre-CAS'd the fence) a
    // no-op for the non-idempotent parts (epoch bump / generation bump /
    // _state.update); the fence clears stay unconditionally applied (CAS/removes
    // are naturally idempotent).
    private val stageRejectedLock = Any()
    private val stageRejectedTxs: MutableSet<PolicyTx> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PolicyTx, Boolean>())

    private fun applyStageRejectedUnderLock(
        tx: PolicyTx,
        key: String? = null,
        // Ownership of this VM's local fence, when the caller decided it in the
        // same critical section: true = caller consumed/held the fence for this
        // VM (winner arm); false = a foreign winner consumed it (skip the
        // stale/unknown stamp — the winner owns ordering); null = caller didn't
        // decide — the helper CASes and decides (the FAILED rev-gate-rejection
        // arm: the claim is rejected BEFORE CAS so nobody consumed the fence,
        // CAS succeeds → stamp applies).
        fenceHeld: Boolean? = null,
    ) {
        val first = synchronized(stageRejectedLock) {
            if (stageRejectedTxs.size >= 64) {
                // Bounded: drop an arbitrary entry (insertion order via
                // iterator; IdentityHashMap has no defined order but is bounded).
                val it = stageRejectedTxs.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            stageRejectedTxs.add(tx)
        }
        // CODE-OPT #3: when the caller didn't decide, the CAS here is the
        // ownership test. (It may fail in arms where the caller pre-consumed
        // the fence — those arms pass fenceHeld explicitly.)
        val wonLocal = fenceHeld
            ?: try {
                activePolicyTx.compareAndSet(tx, null)
            } catch (_: Exception) {
                false
            }
        try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
        // A3-3 (C1): complete the coordinator claim (best effort; CAS is idempotent
        // and fail-safe when the claim is already consumed or foreign). CE rethrown.
        try {
            policyTxCoordinator?.tryCompleteClaim(tx)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }
        // A2-3: best-effort exact-identity placeholder drop; failure keeps the fence
        // (recover path handles it once age-gated).
        try {
            val dropped = policyTxCoordinator?.dropStagedPlaceholderIfMatch(tx.txId, tx.key) == true
            if (dropped) {
                try {
                    System.err.println(
                        "[DualViewModel] dropped abandoned staged placeholder txId=${tx.txId} key=${tx.key} (stage rejected)",
                    )
                } catch (_: Exception) {
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Best effort: the fence remains for the age-gated recover path.
        }
        if (key != null) {
            try { inFlightWrites.remove(key) } catch (_: Exception) { }
            try { policyWriteSaving.remove(key) } catch (_: Exception) { }
        }
        if (!first) return
        if (wonLocal) {
            policyRevisionStale.set(true)
            markPolicyUnknownLocked()
        }
        _state.update { cur ->
            // SEC-L4: a converged Known/IDLE (or foreign) session must not be
            // stomped — settle the terminal only for non-converged phases.
            if (cur.policyWritePhase != PolicyWritePhase.QUEUED &&
                cur.policyWritePhase != PolicyWritePhase.SAVING &&
                cur.policyWritePhase != PolicyWritePhase.RECONCILING
            ) {
                cur
            } else {
                cur.copy(
                    loading = false,
                    pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                    policyWritePhase = PolicyWritePhase.IDLE,
                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                    policyDraftKnown = false,
                    policyDraftCorrupt = tx.isRepair,
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
        }
    }

    // Stage real payload under commitMutex and advance local revision watermarks
    // BEFORE releasing local fence / publishing IDLE. Caller calls coord.emitStaged(stamped)
    // AFTER releasing policySettleLock.
    private suspend fun emitTxSettledStageOnly(tx: PolicyTx, payload: PolicySettled): PolicySettled? {
        val coord = policyTxCoordinator
        if (coord == null) {
            lastRevisionSeenTxId.set(payload.txId)
            lastRevisionSeenKey.set(payload.key)
            return null
        }
        return try {
            val stamped = coord.stageRealPayload(payload)
            if (stamped != null) {
                try {
                    lastRevisionSeenTxId.set(stamped.txId)
                    lastRevisionSeenKey.set(stamped.key)
                    advanceLastRevisionSeenRev(stamped.rev)
                    try {
                        observedSnapshot.set(ObservedPolicySnapshot(stamped.rev, coord.orphanDropGen.get()))
                    } catch (_: Exception) { }
                } catch (_: Exception) {
                }
            }
            stamped
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // Stage I1：終態快照顯式發射（原 settle 集中式先讀後 emit 的遷出）。
    // 呼叫方須在 claim 持有下、終態 _state.update 之後、settlePolicyTx 之前呼叫
    // （同舊集中式時序：發射→CAS；無併發發布者，讀到即本次終態）。
    // 語義同舊集中式：僅 phase==IDLE 發射（unresolved 續留不發射）；Known 成功
    // 的版本三元由呼叫方在 update 前同步預 bump（claim->bump->update->emit 順序，
    // 使晚到 stale 讀丟棄），此處刻意不 bump（舊 Known 條件 bump 已移除，免與
    // 預 bump 形成 double）。Failed/Unknown 經 mark 已 bump，此處亦不重複。coordinator 為 null 即 no-op。
    // Stage L1 P1: stage payload under commitMutex, emit after release.
    // Payload pre-built from _state snapshot, staged (overwrite placeholder)
    // inside withCommitLock with watermark update in same observation,
    // then emitStaged after unlock (never emit while holding mutex).
    // Stage L3: stage via stageRealPayload (preserve claim rev, _latest monotonic),
    // emit via emitStaged (rev guard + timeout metric, never silent).
    private suspend fun emitTxSettledSnapshot(tx: PolicyTx) {
        val coord = policyTxCoordinator ?: return
        try {
            val s = _state.value
            if (s.policyWritePhase == PolicyWritePhase.IDLE) {
                val payload = PolicySettled(
                    tx.txId, tx.key, s.policyDraft, s.policyDraftKnown,
                    s.msgKind, s.msg, tx.isClear,
                )
                val stamped = emitTxSettledStageOnly(tx, payload)
                // Stage J4 lossless + L1 staged: emit after release.
                // Superseded (null) → skip SharedFlow emit (metric already surfaced).
                if (stamped != null) coord.emitStaged(stamped)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort.
        }
    }

    // Stage G2 P2-1：settle-CAS 先行、勝者才發布（取代 write-only 的
    // creatorEpoch/vmEpoch：刪除該二欄，以 CAS 仲裁防雙發布）。
    // - coordinator 存在時以其為跨 VM 仲裁：publish 前先 CAS(tx→null) 搶發布權；
    //   勝者（CAS 成功）才發布，本地 fence 仍保留至發布後經 settlePolicyTx 清
    //   （發布期間本地仍持 Tx，新 save 仍被擋）；敗者（CAS 失敗，coordinator 已空
    //   或被新 Tx 替換）僅清本地＋reconciling，不發布、不碰 coordinator（舊 VM
    //   殘留 handler 不得覆寫新 VM fence，亦不得清掉新 Tx——不同物件 CAS 本就失敗）。
    // - 無 coordinator（本地單 VM 既有測試路徑）一律回 true，維持既有
    //   publish-then-settle 語義（單發布者，行為不變；併發仲裁仍經本地 settle CAS）。
    // 回 true=勝者（可發布），false=敗者（跳過發布）。
    // Stage H2 P1 note: the loser path below (sync clear + SAVING->RECONCILING
    // + launchLoserSync) is dead when a coordinator is attached — all coord
    // paths claim via tryClaimPlaceholder/tryClaimPlaceholderVerdict (follow-then-clear,
    // never sync-clear). It is retained only for the null-coordinator legacy
    // single-VM path (which returns true above and never reaches the loser
    // branch). Kept aligned in spirit (loser never overwrites the winner) and
    // not deleted to preserve the legacy contract.
    private fun tryClaimCoordinatorPublish(tx: PolicyTx): Boolean {
        val coord = policyTxCoordinator ?: return true
        if (!coord.activeTx.compareAndSet(tx, null)) {
            synchronized(policySettleLock) {
                activePolicyTx.compareAndSet(tx, null)
                policyReconciling.remove(tx.key)
                val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                val curCoord = try { coord.activeTx.get() } catch (_: Exception) { null }
                if (curLocal == null && curCoord == null) {
                    _state.update { cur ->
                        if (cur.policyWritePhase != PolicyWritePhase.SAVING) cur
                        else cur.copy(
                            loading = false,
                            policyWritePhase = PolicyWritePhase.RECONCILING,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                        )
                    }
                }
            }
            // Stage H2：敗者啟動跟隨（有界等待勝者 settled，收到即應用本地 IDLE
            // 終態；逾時走顯式 reconcile 兜底，不永久卡死）。
            launchLoserSync(tx)
            return false
        }
        return true
    }

    // Stage L1 P1: staged claim — atomic completeWithRevision (CAS+revision bump
    // +pendingPublish stage) under commitMutex, then afterClaimBeforeEmit hook,
    // emit happens later via emitStaged.
    // A3-3: revBefore != null routes the claim through completeWithRevisionChecked
    // (the SAME rev gate the repair success path uses under commitMutex): if a
    // foreign B commits between the failed IO and this placeholder claim, the
    // bumped committedRevision != revBefore → claim rejected → terminal is gated
    // the same way as a success. revBefore == null keeps completeWithRevision
    // (legacy/exempt — see claimForPublish KDoc).
    // Verdict variant: REV_GATE_REJECTED (rev moved before CAS — no claim
    // consumed; settle as stage-rejected) and CAS_LOSS (a live winner consumed
    // the claim — loser handling applied here) are distinguished from
    // inside the commitMutex lock, so callers never re-read committedRevision
    // to guess why the claim failed. REV_GATE_REJECTED deliberately skips the
    // loser-follow (no winner exists to follow).
    private suspend fun tryClaimStagedVerdict(
        tx: PolicyTx,
        payload: PolicySettled,
        revBefore: Long? = null,
    ): PolicyTxCoordinator.ClaimStagedVerdict {
        val coord = policyTxCoordinator
            ?: return PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED
        val verdict: PolicyTxCoordinator.ClaimStagedVerdict = try {
            if (revBefore != null) coord.completeWithRevisionCheckedVerdict(tx, payload, revBefore)
            else if (coord.completeWithRevision(tx, payload)) {
                PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED
            } else {
                PolicyTxCoordinator.ClaimStagedVerdict.CAS_LOSS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PolicyTxCoordinator.ClaimStagedVerdict.CAS_LOSS
        }
        if (verdict == PolicyTxCoordinator.ClaimStagedVerdict.REV_GATE_REJECTED) {
            // No claim consumed, no winner to follow — the caller settles the
            // terminal as stage-rejected. Loser-follow (SAVING→RECONCILING +
            // launchLoserSync) would wait on a winner that does not exist.
            return verdict
        }
        if (verdict != PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED) {
            // Stage H2 P1: loser follow-then-clear (same as above): never clear
            // the local fence synchronously; the winner's
            // claim->stage->emit->update window must not observe a cleared
            // fence. Keep the fence, flip SAVING->RECONCILING when no foreign
            // Tx is in flight, then converge via launchLoserSync (clears after).
            var shouldLaunchLoserSync = false
            synchronized(policySettleLock) {
                val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                val curCoord = try { coord.activeTx.get() } catch (_: Exception) { null }
                if (curCoord != null && curCoord !== tx) {
                    clearObsoleteLocalTxLocked(tx, coord)
                } else if (curLocal != null && curLocal !== tx) {
                    try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
                } else {
                    _state.update { cur ->
                        if (cur.policyWritePhase != PolicyWritePhase.SAVING) cur
                        else cur.copy(
                            loading = false,
                            policyWritePhase = PolicyWritePhase.RECONCILING,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                        )
                    }
                    shouldLaunchLoserSync = true
                }
            }
            if (shouldLaunchLoserSync) {
                launchLoserSync(tx)
            }
            return verdict
        }
        try {
            afterClaimBeforeEmit?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                // SEC-L2: class-name-only — never the raw e.message.
                System.err.println("[DualViewModel] afterClaimBeforeEmit failed: ${e.javaClass.simpleName}")
            } catch (_: Exception) {
            }
        }
        return PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED
    }

    // Stage L1 P1: placeholder claim for paths where real payload is built after
    // _state.update (reconciler finally via emitTxSettledSnapshot). Stages a
    // placeholder (same txId/key, sanitized draft) so CAS-visible window still
    // blocks fresh C via pendingPublish!=null; later emit overwrites with real.
    // A3-3: revBefore != null gates the claim on the pre-IO observed revision
    // (see tryClaimStagedVerdict); null keeps the legacy unconditional claim.
    private suspend fun tryClaimPlaceholderVerdict(
        tx: PolicyTx,
        revBefore: Long? = null,
    ): PolicyTxCoordinator.ClaimStagedVerdict {
        val placeholder = PolicySettled(
            tx.txId, tx.key, tx.sanitized, false, MsgKind.CHANNEL, "", tx.isClear,
        )
        if (policyTxCoordinator == null) {
            // Legacy single-VM path: no claim machinery at all — claimForPublishCheckedVerdict
            // routes null-coordinator through tryClaimCoordinatorPublish itself; mirror
            // the old `?: return true` here for standalone callers.
            return PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED
        }
        return tryClaimStagedVerdict(tx, placeholder, revBefore)
    }

    private suspend fun tryClaimPlaceholder(tx: PolicyTx, revBefore: Long? = null): Boolean =
        tryClaimPlaceholderVerdict(tx, revBefore) == PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED

    // Stage L1 P1: reconciler/fast-path uniform entry (placeholder staged when
    // coordinator present, legacy CAS when null). Call-sites use this so
    // CAS+revision bump+pendingPublish stage atomically under commitMutex,
    // emit happens later via emitTxSettledSnapshot/emitStaged after release.
    // A3-3 (exemption note): this unconditional form is INTENTIONALLY kept for
    // call sites that cannot name a base revision (reconciler/late-join terminals
    // where no revision was observed at IO entry — e.g. adopted cross-VM Txs,
    // pre-arm aborts). It is safe there because those payloads are non-Known
    // terminals, or Known terminals backed by a fresh ground-truth read taken
    // under/after the revision-move gates (revMovedRefresh/staleOutside/
    // revUnverifiableRefresh) — never an unobserved/stale read — and peers
    // converge fail-closed (they treat !known/empty payloads as "not truth" and
    // re-read). Any call site that DID observe a base revision for its IO must
    // pass it as revBefore via claimForPublishCheckedVerdict so a foreign commit
    // mid-IO bumps the rev and the terminal is gated exactly like a success.
    private suspend fun claimForPublish(tx: PolicyTx): Boolean {
        val coord = policyTxCoordinator
        return if (coord != null) tryClaimPlaceholder(tx) else tryClaimCoordinatorPublish(tx)
    }

    // A3-3: rev-gated sibling of claimForPublish for FAILED terminals whose IO
    // ran against a known revision (confirm: pending.baseCoordinatorRevision;
    // clear: sampled at IO entry; repair: repairRevBefore). A foreign B committing
    // between the failed IO and this claim bumps committedRevision → the claim is
    // rejected and the terminal (non-Known) is never staged — peers stay converged
    // on B either via _latest or via the UNKNOWN_REFRESH gate.
    // Verdict variant for the FAILED-terminal claim path: REV_GATE_REJECTED →
    // caller settles as stage-rejected; CAS_LOSS → a live winner consumed the
    // claim (loser handling already applied inside tryClaimStagedVerdict) and
    // the caller must do NOTHING further; CLAIMED → staged.
    private suspend fun claimForPublishCheckedVerdict(
        tx: PolicyTx,
        revBefore: Long,
    ): PolicyTxCoordinator.ClaimStagedVerdict {
        val coord = policyTxCoordinator
        return if (coord != null) tryClaimPlaceholderVerdict(tx, revBefore) else if (tryClaimCoordinatorPublish(tx)) {
            PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED
        } else {
            PolicyTxCoordinator.ClaimStagedVerdict.CAS_LOSS
        }
    }

    // Atomic local application of an authoritative PolicySettled terminal
    // (shared by launchLoserSync, observeSettledFor, and resolveObserverFallback).
    private fun applyAuthoritativeSettled(tx: PolicyTx, settled: PolicySettled) {
        val coord = policyTxCoordinator
        synchronized(policySettleLock) {
            val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
            val curCoord = try { coord?.activeTx?.get() } catch (_: Exception) { null }
            if (curLocal != null && curLocal !== tx) {
                try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
                return
            }
            if (coord != null && curCoord != null && curCoord !== tx) {
                clearObsoleteLocalTxLocked(tx, coord)
                return
            }
            val canApplyUi = (curLocal === tx) || (_state.value.policyWritePhase == PolicyWritePhase.RECONCILING)
            if (!canApplyUi) {
                try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
                return
            }
            val latestAny = try { coord?.latest?.value } catch (_: Exception) { null }
            val supersededByLatest = latestAny != null &&
                !(latestAny.txId == tx.txId && latestAny.key == tx.key) &&
                (settled.rev == -1L || (latestAny.rev != -1L && latestAny.rev >= settled.rev))
            if (supersededByLatest) {
                try { activePolicyTx.compareAndSet(tx, null) } catch (_: Exception) { }
                try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
                try { policyRevisionStale.set(true) } catch (_: Exception) { }
                try { markPolicyUnknownLocked() } catch (_: Exception) { }
                _state.update { cur ->
                    if (cur.policyWritePhase != PolicyWritePhase.RECONCILING &&
                        cur.policyWritePhase != PolicyWritePhase.SAVING
                    ) {
                        cur
                    } else {
                        cur.copy(
                            loading = false,
                            pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraftKnown = false,
                            policyDraftCorrupt = tx.isRepair,
                            msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                            msgKind = MsgKind.CHANNEL,
                        )
                    }
                }
                return
            }
            lastRevisionSeenTxId.set(settled.txId)
            lastRevisionSeenKey.set(settled.key)
            advanceLastRevisionSeenRev(settled.rev)
            if (settled.known) {
                try { noteLastKnownDraft(settled.draft) } catch (_: Exception) { }
                bumpPolicyGenerations()
                try { disarmBlindClear() } catch (_: Exception) { }
                try { disarmCorruptRepair() } catch (_: Exception) { }
                try {
                    val curSnap = observedSnapshot.get()
                    val orv = if (settled.rev != -1L) settled.rev else (coord?.currentRevision() ?: -1L)
                    val newRev = if (orv != -1L) orv else curSnap.rev
                    val newDg = try { coord?.orphanDropGen?.get() ?: 0L } catch (_: Exception) { 0L }
                    observedSnapshot.set(ObservedPolicySnapshot(newRev, newDg))
                } catch (_: Exception) { }
            } else {
                markPolicyUnknownLocked()
            }
            try { activePolicyTx.compareAndSet(tx, null) } catch (_: Exception) { }
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
            _state.update { cur ->
                if (cur.policyWritePhase != PolicyWritePhase.RECONCILING &&
                    cur.policyWritePhase != PolicyWritePhase.SAVING
                ) cur
                else cur.copy(
                    loading = false,
                    pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                    policyWritePhase = PolicyWritePhase.IDLE,
                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                    policyDraft = if (settled.known) settled.draft else cur.policyDraft,
                    policyDraftKnown = settled.known,
                    policyDraftCorrupt = tx.isRepair && !settled.known,
                    msg = settled.msg,
                    msgKind = settled.msgKind,
                )
            }
        }
    }

    // Shared observer fallback loop (PR #1 coordinator liveness + PR #2 stale authority TOCTOU):
    // 1. Verify I1 ownership & canJoinTx.
    // 2. Verify tx.deferred.isCompleted BEFORE performing boundedReconcileRead (never judge a
    //    pre-completion read against a post-completion deferred).
    // 3. Sample revision across boundedReconcileRead and re-check _latest / activeTx / pendingPublish.
    // 4. Delegate to resolveObserverFallback when no revision movement occurred.
    @androidx.annotation.VisibleForTesting
    internal suspend fun runObserverFallback(tx: PolicyTx, coord: PolicyTxCoordinator) {
        val curLocalFb = try { activePolicyTx.get() } catch (_: Exception) { null }
        val curCoordFb = try { coord.activeTx.get() } catch (_: Exception) { null }
        if (curLocalFb != null && curLocalFb !== tx) {
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
            return
        }
        if (curCoordFb != null && curCoordFb !== tx) {
            clearObsoleteLocalTx(tx, coord)
            return
        }
        if (!canJoinTx(tx)) {
            abortUnarmedTx(tx)
            return
        }
        // PR #2: NEVER run authoritative terminal read before tx.deferred has completed.
        if (!tx.deferred.isCompleted) {
            resolveObserverFallback(tx, LoadResult.Unavailable, true)
            return
        }
        val revBeforeFb = try { sampledCurrentRevision() } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            -1L
        }
        val (fbRead, fbTimedOut) = try {
            boundedReconcileRead(tx.store)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LoadResult.Unavailable to false
        }
        val latestAnyBefore = try { coord.latest.value } catch (_: Exception) { null }
        val latestForTx = latestAnyBefore?.takeIf { it.txId == tx.txId && it.key == tx.key }
        val replayForTx = try {
            coord.settled.replayCache.firstOrNull { it.txId == tx.txId && it.key == tx.key }
        } catch (_: Exception) {
            null
        }
        if (latestForTx != null) {
            applyAuthoritativeSettled(tx, latestForTx)
            return
        }
        if (replayForTx != null) {
            applyAuthoritativeSettled(tx, replayForTx)
            return
        }
        val curLocalAfter = try { activePolicyTx.get() } catch (_: Exception) { null }
        val curCoordAfter = try { coord.activeTx.get() } catch (_: Exception) { null }
        if (curLocalAfter != null && curLocalAfter !== tx) {
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
            return
        }
        if (curCoordAfter != null && curCoordAfter !== tx) {
            clearObsoleteLocalTx(tx, coord)
            return
        }
        val revAfterFb = try { sampledCurrentRevision() } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            -1L
        }
        val revMovedFb = (revBeforeFb == -1L) || (revAfterFb == -1L) || (revAfterFb != revBeforeFb)
        if (revMovedFb) {
            val pend = try { coord.pendingPublish.get() } catch (_: Exception) { null }
            if (pend != null && pend.txId == tx.txId && pend.key == tx.key) {
                val afterClaim = try {
                    coord.awaitSettled(tx.txId, tx.key, policyIoTimeoutMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (afterClaim != null) {
                    applyAuthoritativeSettled(tx, afterClaim)
                } else {
                    resolveObserverFallback(tx, LoadResult.Unavailable, true)
                }
                return
            }
            val latestAny = try { coord.latest.value } catch (_: Exception) { null }
            if (latestAny != null && latestAny.txId == tx.txId && latestAny.key == tx.key) {
                applyAuthoritativeSettled(tx, latestAny)
                return
            }
            if (curCoordAfter == null && latestAny != null &&
                !(latestAny.txId == tx.txId && latestAny.key == tx.key)
            ) {
                synchronized(policySettleLock) {
                    val lNow = try { activePolicyTx.get() } catch (_: Exception) { null }
                    if (lNow === tx || (lNow == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                        activePolicyTx.compareAndSet(tx, null)
                        policyReconciling.remove(tx.key)
                        policyRevisionStale.set(true)
                        markPolicyUnknownLocked()
                        _state.update { cur ->
                            if (cur.policyWritePhase != PolicyWritePhase.RECONCILING &&
                                cur.policyWritePhase != PolicyWritePhase.SAVING
                            ) cur
                            else cur.copy(
                                loading = false,
                                pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                policyWritePhase = PolicyWritePhase.IDLE,
                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                policyDraftKnown = false,
                                policyDraftCorrupt = tx.isRepair,
                                msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                msgKind = MsgKind.CHANNEL,
                            )
                        }
                    }
                }
                return
            }
            // Revision moved mid-read without matching terminal: discard fbRead, stay Unresolved.
            resolveObserverFallback(tx, LoadResult.Unavailable, true)
            return
        }
        resolveObserverFallback(tx, fbRead, fbTimedOut)
    }

    // Stage H2：敗者跟隨＋init 認領訂閱共用（冪等：同一 txId 多次 refresh 觸發的
    // 跟隨皆等待同一 settled，首個應用者翻相位，後到者經 phase!=RECONCILING 丟棄；
    // 不去重亦安全，此處註明不另加去重）。
    // - 有界等待同一 txId 的勝者 settled（withTimeout(policyIoTimeoutMs)，replay=1
    //   使晚訂閱仍可見）：收到即應用本地 IDLE+draft/Known/msg（僅 phase 仍為
    //   RECONCILING 才應用，免覆寫已結新事實；Known 成功即 disarm）。
    // - 逾時兜底：settled==null 即走共用 fallback（boundedReconcileRead 單次＋
    //   resolveObserverFallback/decide 唯一真相，見該 helper KDoc）：Adopt 才收斂
    //   IDLE（Success/Mismatch/Failed），Retain 續留 RECONCILING（不盲 Known→IDLE），
    //   免 claimed VM 永留 RECONCILING。
    // - CE 透傳（scope 取消不吞）。
    internal fun launchLoserSync(tx: PolicyTx) {
        val coord = policyTxCoordinator ?: return
        viewModelScope.launch {
            val settled: PolicySettled? = try {
                coord.awaitSettled(tx.txId, tx.key, policyIoTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (settled != null) {
                applyAuthoritativeSettled(tx, settled)
                return@launch
            }
            runObserverFallback(tx, coord)
        }
    }

    // Stage H2：init/認領後訂閱（同一 txId+key 即應用，replay=8 晚到仍可見）。
    // 認領即啟動，勝者終態一到即收斂本地（與 reconciler 敗者跟隨同 helper，冪等）。
    // Stage J4：settled==null（掉終態／超時）不再直接返回——走 loser 式有界重讀
    // 兜底（boundedReconcileRead 單次＋resolveObserverFallback/decide 唯一真相，
    // 見該 helper KDoc：Adopt 才收斂 IDLE，Retain 續留 RECONCILING，不盲 Known→IDLE），
    // 免 claimed VM 永留 RECONCILING。
    // 與進行中 reconciler 冪等（同 Tx-settle CAS＋版本守衛，先到者勝）。
    // Stage I1：txId+key 精確比對（免重建後新 Tx 撞舊 id 致 stale 誤同步）＋
    // 應用前 activeTx===tx 再檢（新 Tx 在途即丟棄，僅清自身 key）。
    internal fun observeSettledFor(
        tx: PolicyTx,
        timeoutMs: Long = POLICY_RECONCILE_TOTAL_BUDGET_MS,
    ) {
        val coord = policyTxCoordinator ?: return
        viewModelScope.launch {
            val settled: PolicySettled? = try {
                coord.awaitSettled(tx.txId, tx.key, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (settled == null) {
                runObserverFallback(tx, coord)
                return@launch
            }
            applyAuthoritativeSettled(tx, settled)
        }
    }

    // Stage I2 (P2#3#4)：coordinator 級陳舊修訂同步——每 VM init 常駐收集 settled，
    // 不限認領單一 txId（以 lastSeenTxId 去重，replay=8 晚訂閱仍只應用一次）。
    // Stage L3: 改收斂 _latest StateFlow（conflated、有序、無逐出；晚訂閱收斂最新，
    // gap（lastRev+1 < incoming rev）經 revisionGapCount/log 標記，永不靜默丟失）。
    // _settled SharedFlow 僅為瞬態 notifier（相容保留，不再作為真相）。
    private fun observeRevisionUpdates() {
        val coord = policyTxCoordinator ?: return
        viewModelScope.launch {
            try {
                coord.latest.collect { settled ->
                    if (settled != null) handleForeignSettled(settled)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A3-2: collector 靜默死亡＝整個 cross-VM 陳舊修訂防禦失效，但
                // 呼叫端無從得知。非-CE 退出一律 fail-closed：記一行 stderr
                // （僅 tag＋例外類別，無內容）、標 policyRevisionStale＋世代
                // fence，使後續 request/confirm/repair 一律走 UNKNOWN_REFRESH
                // 重讀門（與 observe 退出前語義一致，只是永久停用同步）。
                // Collector 不重啟：本 VM 的同步自此失效（下一個兌現點為
                // refresh/stall gate）；重啟需新的訂閱句柄，刻意不自動重訂
                // 以免重複應用已觀察終態。
                try {
                    System.err.println(
                        "[DualViewModel] observeRevisionUpdates exited: ${e.javaClass.name}; " +
                            "cross-VM revision sync disabled, failing closed (stale + Unknown)",
                    )
                } catch (_: Throwable) {
                }
                try {
                    policyRevisionStale.set(true)
                } catch (_: Throwable) {
                }
                try {
                    markPolicyUnknownLocked()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private suspend fun handleForeignSettled(settled: PolicySettled) {
        try {
            policyRevisionMutex.withLock {
                handleForeignSettledLocked(settled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort.
        }
    }

    // Stage L1 P1: authoritative comparison becomes coordinator committedRevision;
    // VM-local watermark update inside same commitMutex observation.
    // Order policyRevisionMutex->commitMutex (outer holds revision, inner commit).
    private suspend fun handleForeignSettledLocked(settled: PolicySettled) {
        val coord = policyTxCoordinator
        if (coord != null) {
            try {
                coord.withCommitLock {
                    handleForeignSettledLockedInner(settled, coord)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Fail-closed: never run handleForeignSettledLockedInner unlocked on lock failure.
                return
            }
        } else {
            handleForeignSettledLockedInner(settled, null)
        }
    }

    private fun handleForeignSettledLockedInner(settled: PolicySettled, coord: PolicyTxCoordinator?) {
        try {
            // Stage L1 P1: observe coordinator committedRevision + pendingPublish
            // atomically with watermark (same commitMutex observation).
            // P2-4: orphanDropGen is sampled in the SAME observation (rev/pending/
            // dropGen one snapshot, no split window).
            val committedRev: Long = try {
                coord?.committedRevision?.get() ?: -1L
            } catch (_: Exception) {
                -1L
            }
            val stagedPending: PolicySettled? = try {
                coord?.pendingPublish?.get()
            } catch (_: Exception) {
                null
            }
            @Suppress("UNUSED_VARIABLE")
            val observedDropGen: Long = try {
                coord?.orphanDropGen?.get() ?: 0L
            } catch (_: Exception) {
                0L
            }
            // Authoritative: staged (CAS done, not yet emitted) counts as seen
            // revision — mark stale even before its event lands (closes P1 gap).
            // Union drain: replayCache already drained by pre-IO gate; here staged
            // pending with newer txId also forces stale when local active/pending.
            @Suppress("UNUSED_VARIABLE")
            val authoritativeRev = committedRev
            @Suppress("UNUSED_VARIABLE")
            val authoritativePending = stagedPending
            // Stage L3: rev 守衛 — rev 已知時由 rev 決定新舊（overtake 殘留
            // rev < last 一律丟棄，免 stale 覆寫新事實）；任一 rev==-1 時退回
            // txId 排序守衛。gap（跳號 lastRev+1 < incoming）經 revisionGapCount
            // + stderr 顯式標記，仍收斂最新（下行應用），不靜默丟失。
            val lastRev = try { lastRevisionSeenRev.get() } catch (_: Exception) { -1L }
            val lastId = lastRevisionSeenTxId.get()
            val lastK = lastRevisionSeenKey.get()
            val revKnown = settled.rev != -1L && lastRev != -1L
            if (revKnown) {
                if (settled.rev < lastRev) return
                if (settled.rev > lastRev + 1) {
                    try {
                        revisionGapCount.incrementAndGet()
                    } catch (_: Exception) {
                    }
                    try {
                        System.err.println(
                            "[DualViewModel] revision gap lastRev=$lastRev incoming rev=${settled.rev} txId=${settled.txId}",
                        )
                    } catch (_: Exception) {
                    }
                }
                if (settled.txId == lastId && settled.key == lastK) {
                    // Same identity: still advance rev watermark when newer.
                    advanceLastRevisionSeenRev(settled.rev)
                    return
                }
                // rev 已知時不再以 txId 排序（rev 為準；txId 僅為 rev==-1 fallback）。
            } else {
                if (settled.txId == lastId && settled.key == lastK) {
                    // Same identity: still advance rev watermark when newer (placeholder
                    // →real 同 rev 覆寫不需動；跨 rev 同 id 極罕，仍推進）。
                    advanceLastRevisionSeenRev(settled.rev)
                    return
                }
                // 全域單調 fallback（僅 rev 未知時）：舊修訂（txId 更小）一律丟棄，
                // 免 stale 覆寫新事實。
                if (settled.txId < lastId) return
            }
            synchronized(policySettleLock) {
                val localTx = try { activePolicyTx.get() } catch (_: Exception) { null }
                // 自 Tx 終態經專屬跟隨處理，此處僅記水位不應用。
                if (localTx != null && settled.txId == localTx.txId && settled.key == localTx.key) {
                    lastRevisionSeenTxId.set(settled.txId)
                    lastRevisionSeenKey.set(settled.key)
                    advanceLastRevisionSeenRev(settled.rev)
                    return
                }
                // Stage J2: 異 Tx 而本地有 activeTx → 丟棄＋記 stale（Known 與 Unknown
                // 皆記：共享失敗亦使本地視圖可疑，後續 request/confirm 強制重讀；本地
                // Tx 仍由自身 reconciler 經 ground-truth 結清，此處不翻 Known、不 bump，
                // 免干擾在途核對的 version/ownership 守衛）。
                if (localTx != null) {
                    lastRevisionSeenTxId.set(settled.txId)
                    lastRevisionSeenKey.set(settled.key)
                    advanceLastRevisionSeenRev(settled.rev)
                    policyRevisionStale.set(true)
                    return
                }
                // Stage J3 (P2#3 foreign-vs-pending)：無 activeTx 但有 pending（QUEUED
                // 待確認，對話框基於舊修訂構建）時，外來終態不得覆寫 draft——跳過
                // draft 更新＋記 stale＋翻 Unknown（保留 pending＋draft 舊快照，後續
                // confirm 經 stale/unknown 門控拒寫 UNKNOWN_REFRESH，需顯式重讀後
                // 重建 pending）。無 pending 才走既有 Known 更新／Unknown 作廢。
                // （bump＝markUnknown fence，作廢在途 stale load；phase 保持，不動
                // pending，不 disarm，免誤關對話框或洩漏舊 arm。）
                val hasPendingForForeign = try {
                    _state.value.pendingPolicyWrite != null
                } catch (_: Exception) {
                    false
                }
                if (hasPendingForForeign) {
                    lastRevisionSeenTxId.set(settled.txId)
                    lastRevisionSeenKey.set(settled.key)
                    advanceLastRevisionSeenRev(settled.rev)
                    policyRevisionStale.set(true)
                    markPolicyUnknownLocked()
                    _state.update { cur ->
                        if (cur.policyWritePhase != PolicyWritePhase.IDLE &&
                            cur.policyWritePhase != PolicyWritePhase.QUEUED
                        ) {
                            cur.copy(policyDraftKnown = false, policyDraftCorrupt = false)
                        } else {
                            cur.copy(
                                policyDraftKnown = false,
                                policyDraftCorrupt = false,
                                msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                msgKind = MsgKind.CHANNEL,
                            )
                        }
                    }
                    return
                }
                // Stage J2: 本地無 activeTx 但收到共享終態失敗/未決 (known==false) 不得
                // 忽略——必須作廢本地 Known（樂觀快取跨 VM 污染：他 VM 失敗前本 VM 可能
                // 已讀到其樂觀值 B 並誤存為 Known）。bump 三元作廢在途 load，翻 Unknown
                // 並要求顯式重讀後才可再寫（!Known 門控）。
                if (!settled.known) {
                    lastRevisionSeenTxId.set(settled.txId)
                    lastRevisionSeenKey.set(settled.key)
                    advanceLastRevisionSeenRev(settled.rev)
                    markPolicyUnknownLocked()
                    _state.update { cur ->
                        if (cur.policyWritePhase != PolicyWritePhase.IDLE &&
                            cur.policyWritePhase != PolicyWritePhase.QUEUED
                        ) {
                            cur.copy(policyDraftKnown = false, policyDraftCorrupt = false)
                        } else {
                            cur.copy(
                                policyDraftKnown = false,
                                policyDraftCorrupt = false,
                                msg = settled.msg,
                                msgKind = settled.msgKind,
                            )
                        }
                    }
                    return
                }
                lastRevisionSeenTxId.set(settled.txId)
                lastRevisionSeenKey.set(settled.key)
                advanceLastRevisionSeenRev(settled.rev)
                policyDraftVersion.incrementAndGet()
                policyFreshnessGen.incrementAndGet()
                policyOwnershipGen.incrementAndGet()
                policyRevisionStale.set(false)
                // Stage O1 P1#1: terminal event is authoritative truth => observed.
                try {
                    val newRev = if (settled.rev != -1L) settled.rev else (policyTxCoordinator?.currentRevision() ?: -1L)
                    val newDg = try { policyTxCoordinator?.orphanDropGen?.get() ?: 0L } catch (_: Exception) { 0L }
                    observedSnapshot.set(ObservedPolicySnapshot(newRev, newDg))
                } catch (_: Exception) { }
                val canApplyForeign = _state.value.policyWritePhase == PolicyWritePhase.IDLE ||
                    _state.value.policyWritePhase == PolicyWritePhase.QUEUED
                if (canApplyForeign) {
                    disarmBlindClear()
                    disarmCorruptRepair()
                }
                try { noteLastKnownDraft(settled.draft) } catch (_: Exception) { }
                _state.update { cur ->
                    if (cur.policyWritePhase != PolicyWritePhase.IDLE &&
                        cur.policyWritePhase != PolicyWritePhase.QUEUED
                    ) {
                        cur
                    } else {
                        cur.copy(
                            policyDraft = settled.draft,
                            policyDraftKnown = true,
                            policyDraftCorrupt = false,
                            msg = settled.msg,
                            msgKind = settled.msgKind,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    // Stage G2：handoverTx 登記（本地＋coordinator 同組＋reconciling＋
    // deferred.invokeOnCompletion 自動重查＋reconciler 啟動）。
    // 调用方在外層 finally 調用（mutex 已釋放）。
    // Stage H1：set->CAS（失敗 loser 清本地不碰 winner；settle/tryClaim 保持
    // CAS）。已前置認領（同一物件，本地＋coordinator 皆已為該 Tx）即勝者直行；
    // 本地 CAS 失配（另有 winner）即 loser 直接返回（不加 reconciling、不啟動
    // reconciler、不碰 winner）；coordinator CAS 失配即 loser 清本地（僅清自身
    // Tx，不碰 coordinator winner）後返回。
    // P2-3: lock-free dual CAS (no tryLock decoration). Both CAS are single
    // atomic structures; a mutex around them cannot make the pair atomic
    // (non-suspend finally path cannot hold commitMutex across the pair anyway).
    // Rollback-safe: local CAS failure => return (coord untouched when coordCur
    // was already tx, else coord CAS attempted first below); coord CAS failure =>
    // roll back local and return without touching the winner.
    // P2-A: Boolean 回傳——true 僅代表 handover 實際登記成功（本地＋coordinator
    // 皆為該 Tx、policyReconciling 已加、invokeOnCompletion＋reconciler 已掛）；
    // false 代表拒登（canJoinTx 失敗→aborted／本地 CAS 敗／coordinator CAS 敗→
    // 已回滾本地）。呼叫端（F3 armed-register）必須據此決定 RECONCILING 或落回
    // IDLE，不得假設登記成功。既有呼叫端可忽略回傳值（source-compatible）。
    private fun registerHandoverTx(tx: PolicyTx, isClear: Boolean): Boolean {
        // Stage N2 P1: never expose/transfer a PREPARING (unvalidated) Tx as
        // executable. Handover is only for post-validation timeouts (armed);
        // an unarmed handover request is aborted without store touch.
        // Stage O1 P2#2: owner-liveness already checked by caller (init) or by
        // canJoinTx gate here; abandoned claims abort, live owners never reach here
        // via init (init returns Busy early).
        if (!canJoinTx(tx)) {
            abortUnarmedTx(tx)
            return false
        }
        val registered = synchronized(policySettleLock) {
            val localCur = try { activePolicyTx.get() } catch (_: Exception) { null }
            val coordCur: PolicyTx? = try { policyTxCoordinator?.activeTx?.get() } catch (_: Exception) { null }
            if (localCur !== tx) {
                if (!activePolicyTx.compareAndSet(null, tx)) {
                    return@synchronized false
                }
            }
            val coord = policyTxCoordinator
            if (coord != null && coordCur !== tx) {
                val claimed: Boolean = try {
                    if (coord.activeTx.get() !== tx) {
                        coord.activeTx.compareAndSet(null, tx)
                    } else {
                        true
                    }
                } catch (_: Exception) { false }
                if (!claimed) {
                    activePolicyTx.compareAndSet(tx, null)
                    policyReconciling.remove(tx.key)
                    return@synchronized false
                }
            }
            policyReconciling.add(tx.key)
            true
        }
        if (!registered) return false
        attachDeferredAutoRecheck(tx)
        if (isClear) launchClearReconciler(tx) else launchConfirmReconciler(tx)
        return true
    }

    // F3 fail-closed backstop：mid-await CE unwind（或理論上的 wonLocal==false
    // 殘留）把 phase 擱淺在 SAVING 時，呼叫端在 policySettleLock 下調用本 helper，
    // 把擱淺的 SAVING 原地改寫為收斂終態。三條寫入路徑（confirm／clear／repair）
    // 共用：predicate 統一為 phase==SAVING（repair 的 CE catch 會先把 loading 清掉
    // 但 phase 仍 SAVING，故以 phase 為唯一準繩；confirm 臂再要求 loading——既有
    // 語義，SAVING 下 loading 本為 true，等價且多一道防線）。lambda 內再檢一次：
    // 合法終態（phase 已被移出 SAVING）為 no-op，不誤覆寫。handedOff（htx 或 armed
    // Tx 已註冊 reconciler）→ RECONCILING 並保留 cur.msg/msgKind（交接者負責收斂、
    // 收斂時附誠實新終態），否則 IDLE＋UNKNOWN_REFRESH。corrupt：clear/confirm
    // 傳 false（該 arm 僅宣告 view 未知、不保留損壞語境）；repair 傳 true（store
    // 仍持損壞證據，直到 reconciler 驗證 Known(empty) 才清位）。policyEpoch 隨行
    // +1（相位變遷慣例）。best-effort：本 helper 不 rethrow；呼叫端負責
    // policySettleLock 與 try/catch 外殼（與同 finally 的其他清理一致）。
    // P3-A：IDLE arm（handedOff==false）在 _state.update 之前先 markPolicyUnknownLocked
    // （與 F2 siblings 一致）——否則在途 stale-Known load 可在 fail-closed flip 後
    // 落地 Known。predicate（phase==SAVING && (!requireLoading || loading)）與 lambda 內
    // 檢查一致：合法終態不 bump。RECONCILING arm 不變（handedOff==true 跳過本 bump）。
    private fun failClosedSavingRepairIfAbandoned(
        handedOff: Boolean,
        corrupt: Boolean,
        requireLoading: Boolean,
    ) {
        if (!handedOff) {
            val s0 = _state.value
            if (s0.policyWritePhase == PolicyWritePhase.SAVING &&
                (!requireLoading || s0.loading)
            ) {
                markPolicyUnknownLocked()
            }
        }
        _state.update { cur ->
            if (cur.policyWritePhase != PolicyWritePhase.SAVING || (requireLoading && !cur.loading)) cur
            else cur.copy(
                loading = false,
                policyWritePhase = if (handedOff) PolicyWritePhase.RECONCILING else PolicyWritePhase.IDLE,
                policyDraftKnown = false,
                policyDraftCorrupt = corrupt,
                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                msg = if (handedOff) cur.msg else policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                msgKind = if (handedOff) cur.msgKind else MsgKind.CHANNEL,
            )
        }
    }

    // P2-4: Busy waiter — a VM that marked itself BUSY (live PREPARING owner
    // elsewhere) must not strand forever when that owner disposes immediately
    // after the snapshot (no re-estimate, no timeout, no adopt, no subscribe).
    // Best-effort periodic recheck under commitMutex (single snapshot):
    // - claim abandoned (activeTx holds tx but ownerAlive==false, or deferred
    //   done + staged fence stale/missing) => abort (pre-start cancel path);
    // - claim joinable (armed/started) => reclaim like the normal inherit path;
    // - claim gone (settled, activeTx==null/different) => return (the _latest
    //   collector converges this VM, no local action needed).
    // Capped iterations (bounded 4 rounds; explicitly cancelled in
    // dispose()/onCleared() via busyWaiterJob, NOT via viewModelScope alone —
    // dispose() does not cancel the scope in tests) so a truly live
    // owner never spins forever; the _latest collector (observeRevisionUpdates)
    // remains the convergence backstop after the bounded rounds expire.
    private fun attachBusyWaiter(tx: PolicyTx) {
        try {
            try { busyWaiterJob?.cancel() } catch (_: Exception) { }
            busyWaiterJob = viewModelScope.launch {
                try {
                    val coord = policyTxCoordinator ?: return@launch
                    var rounds = 0
                    while (rounds < 4) {
                        rounds += 1
                        try { kotlinx.coroutines.delay(policyIoTimeoutMs) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
                        if (activePolicyTx.get() != null) return@launch
                        var action = 0 // 0=keep waiting, 1=abort, 2=reclaim, -1=done
                        try {
                            coord.withCommitLock {
                                val cur = try { coord.activeTx.get() } catch (_: Exception) { null }
                                if (cur !== tx) {
                                    action = -1
                                    return@withCommitLock
                                }
                                val alive = try { tx.ownerAlive.get() } catch (_: Exception) { false }
                                if (!alive) {
                                    action = 1
                                    return@withCommitLock
                                }
                                if (canJoinTx(tx)) {
                                    action = 2
                                    return@withCommitLock
                                }
                                val done = try { tx.deferred.isCompleted || tx.deferred.isCancelled } catch (_: Exception) { false }
                                if (done) {
                                    val pend = try { coord.pendingPublish.get() } catch (_: Exception) { null }
                                    if (pend == null || pend.txId != tx.txId || pend.key != tx.key) {
                                        action = 1
                                        return@withCommitLock
                                    }
                                    val stagedAt = try { coord.pendingPublishAtNanos.get() } catch (_: Exception) { 0L }
                                    val now = try { System.nanoTime() } catch (_: Exception) { 0L }
                                    if (stagedAt == 0L || (now >= stagedAt &&
                                        (now - stagedAt) / 1_000_000L >= policyIoTimeoutMs)
                                    ) {
                                        action = 1
                                        return@withCommitLock
                                    }
                                }
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
                        if (action < 0) return@launch
                        if (action == 1) {
                            // Only abort a Tx we actually hold: CAS null->tx first;
                            // failure means another VM claimed/settled concurrently —
                            // never abort someone else's fence.
                            val held = synchronized(policySettleLock) {
                                try { activePolicyTx.compareAndSet(null, tx) } catch (_: Exception) { false }
                            }
                            if (!held) return@launch
                            try { abortUnarmedTx(tx) } catch (ce: CancellationException) { throw ce } catch (_: Exception) { }
                            return@launch
                        } else if (action == 2) {
                            try {
                                val heldJoin = synchronized(policySettleLock) {
                                    if (activePolicyTx.compareAndSet(null, tx)) {
                                        try { policyReconciling.add(tx.key) } catch (_: Exception) { }
                                        try { markPolicyUnknownLocked() } catch (_: Exception) { }
                                        _state.update { cur ->
                                            cur.copy(
                                                pendingPolicyWrite = tx.pending ?: cur.pendingPolicyWrite,
                                                policyWritePhase = PolicyWritePhase.RECONCILING,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = tx.isRepair,
                                                loading = false,
                                                msg = policyText(
                                                    PolicyText(
                                                        if (tx.isRepair) PolicyTextKind.CORRUPT_REPAIR_TIMEOUT
                                                        else if (tx.isClear) PolicyTextKind.CLEAR_TIMEOUT else PolicyTextKind.WRITE_TIMEOUT,
                                                    ),
                                                ),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (heldJoin) {
                                    try { attachDeferredAutoRecheck(tx) } catch (_: Exception) { }
                                    if (tx.isClear) launchClearReconciler(tx) else launchConfirmReconciler(tx)
                                    observeSettledFor(tx)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                            return@launch
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
    }

    // Stage G2 (P2#4#5)：deferred 完成自動重查。
    // handoverTx 登記時掛 invokeOnCompletion：若 activePolicyTx 仍為該 Tx，
    // 啟動單次權威重查（fresh read bounded，獨立池，不耗 reconciler 預算）→走
    // settle helper；讀 Unknown 保持 unresolved＋發布明確 unresolved-refresh
    // 動作（UNRESOLVED_REFRESH＋按鈕即 refreshPolicyDraft）。
    // - 單次：invokeOnCompletion 本身單次；重查內再判擁有者，併發已結即丟棄。
    // - 有界獨立池：經 boundedReconcileRead 單次（withTimeout(policyIoTimeoutMs)
    //   ＋policyReconcileDispatcher，不佔共用 IO 池，不耗 reconciler 三讀預算）。
    // - settle helper：decidePolicyTxSettle（Known＋已完成才 Success/Mismatch/
    //   Failed；Unknown 一律 Unresolved 續留，不信 ack）。
    // - P2-1 防雙發布：終態（Success/Mismatch/Failed）發布前先經
    //   tryClaimCoordinatorPublish 做 settle-CAS 先行、勝者才發布（coordinator
    //   CAS 成功才發布，敗者跳過）；unresolved 不搶發布權（不碰 fence）。
    private fun attachDeferredAutoRecheck(tx: PolicyTx) {
        try {
            tx.deferred.invokeOnCompletion {
                // 非同步交接：仍為擁有者才啟動單次重查（已結／被替換即丟棄）。
                if (activePolicyTx.get() !== tx) {
                    // 跨 VM：本地已空但 coordinator 仍為該 Tx（新 VM 未及認領？
                    // 舊 VM 已死）→舊 handler 不啟動，待新 VM 認領後自掛。
                    return@invokeOnCompletion
                }
                viewModelScope.launch {
                    autoRecheckTx(tx)
                }
            }
        } catch (_: Exception) {
            // Best effort: 掛載失敗不影響 reconciler 主路徑。
        }
    }

    // Stage G2 單次權威重查本體（viewModelScope 上跑，讀切獨立池）。
    private suspend fun autoRecheckTx(tx: PolicyTx) {
        // 擁有者再判（併發已結／被替換即丟棄，防雙發布）。
        if (activePolicyTx.get() !== tx) return
        if (policyTxCoordinator != null && policyTxCoordinator.activeTx.get() !== tx) return
        // Stage N2 P1: unvalidated Tx must never auto-execute; abort instead.
        if (!canJoinTx(tx)) {
            abortUnarmedTx(tx)
            return
        }
        // 終態發布權以 settle-CAS 先行仲裁（見 claimForPublish），此處
        // 僅做擁有者預檢；同一 Tx 被新 VM 認領時舊 handler 與新 handler 競爭發布權，
        // 首個 CAS 成功者勝出發布，敗者跳過（不覆寫勝者 fence）。
        val read: LoadResult = try {
            val (r, _) = boundedReconcileRead(tx.store)
            r
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LoadResult.Unavailable
        }
        // 讀 Unknown 保持 unresolved（不信 ack，不結清；即使 deferred 已證失敗，
        // 無 ground truth 仍續留，待手動 refresh late-join）。
        if (read !is LoadResult.Known) {
            // 仍為擁有者才發布 unresolved-refresh 動作（併發已結即丟棄）。
            synchronized(policySettleLock) {
                if (activePolicyTx.get() !== tx) return
                if (policyTxCoordinator != null && policyTxCoordinator.activeTx.get() !== tx) return
                _state.update { cur ->
                    if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                    else cur.copy(
                        policyWritePhase = PolicyWritePhase.RECONCILING,
                        policyDraftKnown = false,
                        policyDraftCorrupt = tx.isRepair || (read is LoadResult.Corrupt),
                        msg = policyText(PolicyText(PolicyTextKind.UNRESOLVED_REFRESH)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
            }
            return
        }
        // Known：走 settle helper（deferred 未完成→Unresolved 續留；完成＋符合→
        // Success；完成＋不符→Mismatch；已證失敗→Failed）。
        val decision: PolicyTxSettle = try {
            decidePolicyTxSettle(tx, read)
        } catch (e: CancellationException) {
            throw e
        }
        when (decision) {
            is PolicyTxSettle.Success -> {
                if (activePolicyTx.get() !== tx) return
                if (policyTxCoordinator != null && policyTxCoordinator.activeTx.get() !== tx) return
                // Pre-build terminal payload, claim with PLACEHOLDER-ONLY (never pass real
                // payload to completeWithRevision), then stageRealPayload -> atomic settle -> emitStaged.
                val repairTerminal: Pair<String, MsgKind>? = if (tx.isRepair) repairSuccessTerminal(tx) else null
                val successMsg = repairTerminal?.first ?: if (tx.isClear) policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS))
                else writeSuccessMsg(decision.packages.size, tx.pending, tx.sanitized)
                val successKind: MsgKind = repairTerminal?.second ?: MsgKind.INFO
                val successPayload = PolicySettled(
                    tx.txId, tx.key,
                    if (tx.isClear) emptySet() else decision.packages,
                    true, successKind,
                    successMsg,
                    tx.isClear,
                )
                if (!claimForPublish(tx)) return
                winnerPublish(tx, successPayload, bump = true) {
                    disarmBlindClear()
                    if (tx.isRepair) disarmCorruptRepair()
                    try { noteLastKnownDraft(if (tx.isRepair || tx.isClear) emptySet() else decision.packages) } catch (_: Exception) { }
                    _state.update { cur ->
                        if (tx.isRepair) cur.copy(
                            pendingPolicyWrite = null,
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraft = emptySet(),
                            policyDraftKnown = true,
                            policyDraftCorrupt = false,
                            msg = successMsg,
                            msgKind = successKind,
                        )
                        else if (tx.isClear) cur.copy(
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraft = emptySet(),
                            policyDraftKnown = true,
                            policyDraftCorrupt = false,
                            msg = successMsg,
                            msgKind = MsgKind.INFO,
                        )
                        else cur.copy(
                            pendingPolicyWrite = null,
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraft = decision.packages,
                            policyDraftKnown = true,
                            policyDraftCorrupt = false,
                            msg = successMsg,
                            msgKind = MsgKind.INFO,
                        )
                    }
                }
            }
            is PolicyTxSettle.Mismatch -> {
                if (activePolicyTx.get() !== tx) return
                if (policyTxCoordinator != null && policyTxCoordinator.activeTx.get() !== tx) return
                val mismatchPayload = PolicySettled(
                    tx.txId, tx.key, decision.packages, true, MsgKind.INFO,
                    policyText(PolicyText(PolicyTextKind.REFRESHED)), tx.isClear,
                )
                if (!claimForPublish(tx)) return
                winnerPublish(tx, mismatchPayload, bump = true) {
                    disarmBlindClear()
                    if (tx.isRepair) disarmCorruptRepair()
                    try { noteLastKnownDraft(decision.packages) } catch (_: Exception) { }
                    _state.update { cur ->
                        cur.copy(
                            pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraft = decision.packages,
                            policyDraftKnown = true,
                            policyDraftCorrupt = false,
                            msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                            msgKind = MsgKind.INFO,
                        )
                    }
                }
            }
            is PolicyTxSettle.Failed -> {
                if (activePolicyTx.get() !== tx) return
                if (policyTxCoordinator != null && policyTxCoordinator.activeTx.get() !== tx) return
                val failedMsg = policyText(
                    PolicyText(
                        if (tx.isRepair) PolicyTextKind.CORRUPT_REPAIR_FAILED
                        else if (tx.isClear) PolicyTextKind.CLEAR_FAILED else PolicyTextKind.WRITE_FAILED,
                    ),
                )
                val failedPayload = PolicySettled(
                    tx.txId, tx.key, _state.value.policyDraft, false, MsgKind.FAILED,
                    failedMsg,
                    tx.isClear,
                )
                if (!claimForPublish(tx)) return
                winnerPublish(tx, failedPayload, bump = false) {
                    markPolicyUnknownLocked()
                    _state.update { cur ->
                        if (tx.isRepair) cur.copy(
                            pendingPolicyWrite = null,
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraftKnown = false,
                            policyDraftCorrupt = true,
                            msg = failedMsg,
                            msgKind = MsgKind.FAILED,
                        )
                        else if (tx.isClear) cur.copy(
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraftKnown = false,
                            policyDraftCorrupt = false,
                            msg = failedMsg,
                            msgKind = MsgKind.FAILED,
                        )
                        else cur.copy(
                            pendingPolicyWrite = null,
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                            policyDraftKnown = false,
                            policyDraftCorrupt = false,
                            msg = failedMsg,
                            msgKind = MsgKind.FAILED,
                        )
                    }
                }
            }
            is PolicyTxSettle.Unresolved -> {
                synchronized(policySettleLock) {
                    if (activePolicyTx.get() !== tx) return
                    if (policyTxCoordinator != null && policyTxCoordinator.activeTx.get() !== tx) return
                    _state.update { cur ->
                        if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                        else cur.copy(
                            policyWritePhase = PolicyWritePhase.RECONCILING,
                            policyDraftKnown = false,
                            policyDraftCorrupt = tx.isRepair,
                            msg = policyText(PolicyText(PolicyTextKind.UNRESOLVED_REFRESH)),
                            msgKind = MsgKind.CHANNEL,
                        )
                    }
                }
            }
        }
    }
    // Stage G1 settle helper（Tx 釋放唯一真相；reconciler／refresh late-join／
    // 快徑共用，免三處漂移）。
    // - 可 settle IFF：deferred 已完成＋權威讀==預期→SUCCESS；deferred 已證失敗
    //   （await 回 false／拋出）→FAILED＋Unknown（樂觀讀不得翻案）；deferred 完成
    //   ＋權威讀非預期→mismatch REFRESHED（ground truth）。
    // - deferred 未完成時任何 Known（A 或 B）都不 settle→Unresolved（保留 fence＋
    //   RECONCILING＋pending；顯示可 yielding 更新但 Known=false 或維持 Unknown）。
    // - deferred 完成＋讀 Unknown→Unresolved（reconciler／late-join 不信 ack，
    //   等自動重查；快徑為保既有版本守衛綠仍信 ack，見上，不經此 helper）。
    // CE 透傳（scope 取消，不吞為失敗）。
    private sealed interface PolicyTxSettle {
        data class Success(val packages: Set<String>) : PolicyTxSettle
        data class Mismatch(val packages: Set<String>) : PolicyTxSettle
        object Failed : PolicyTxSettle
        object Unresolved : PolicyTxSettle
    }
    // Stage N2 P1: execution gate — joining/reconciliation is INCAPABLE of
    // starting an unvalidated pending job. LAZY deferred starts on first await,
    // so every join path must check armed BEFORE touching deferred. Unarmed +
    // incomplete (PREPARING, validation never finished) => never await/start;
    // caller must abort/unclaim instead. Cancelled deferreds (isCancelled=true,
    // which in kotlinx.coroutines also sets isCompleted=true) are NOT joinable.
    private fun canJoinTx(tx: PolicyTx): Boolean = try {
        tx.executionArmed.get() || (tx.deferred.isCompleted && !tx.deferred.isCancelled)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        false
    }

    // Stage N2 P1: validate-then-arm-then-start (single source). Sets armed
    // BEFORE start() so any concurrent join observing armed==true sees a Tx
    // whose validation already passed. Unarmed cancel prevents LAZY start.
    // A3-7: cancel an (armed or new) repair deferred and WAIT — bounded by
    // policyIoTimeoutMs — for it to actually stop before gates are released.
    // Cancellation cannot interrupt a blocking store.repairCorrupt() (it runs on
    // an app-scoped Dispatchers.IO job), so fire-and-forget cancel could report
    // IDLE while the delete still lands later.
    // - deferred never started (the only arm point is AFTER the final-read
    //   gates) → join() returns immediately;
    // - deferred already running → bounded wait; on timeout, mark stale
    //   (refresh required) + log, and report false so the caller stays honest.
    // CE 透傳（協程取消不得被吞）。
    // @VisibleForTesting internal：單元測試直接以（未 arm 的 deferred／阻塞
    // deferred）驅動此有界 join（R1(c)）；生產仍只經 confirmRepairCorruptDraft。
    @androidx.annotation.VisibleForTesting
    internal suspend fun cancelAndJoinRepairDeferred(def: Deferred<Boolean>?): Boolean {
        if (def == null) return true
        try {
            def.cancel()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }
        return try {
            withTimeout(policyIoTimeoutMs) { def.join() }
            true
        } catch (te: TimeoutCancellationException) {
            // Belt-and-braces: this blocking-arm timeout branch is currently
            // UNREACHABLE — repair deferreds are never armed at the two call
            // sites (armed only after the final-read gates) — keep it fail-closed.
            try {
                System.err.println(
                    "[DualViewModel] repair deferred did not stop after cancel (blocking repairCorrupt); " +
                        "marking policyRevisionStale — refresh required before next write",
                )
            } catch (_: Exception) {
            }
            try {
                policyRevisionStale.set(true)
            } catch (_: Exception) {
            }
            false
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // join 本身不應失敗；保守視為已停止（刪除結果交由 post-read 驗證收斂）。
            true
        }
    }

    private fun armAndStartTxDeferred(tx: PolicyTx) {
        try {
            tx.executionArmed.set(true)
        } catch (_: Exception) {
        }
        try {
            tx.deferred.start()
        } catch (_: Exception) {
        }
    }

    // Stage N2 P1: abort an unvalidated (PREPARING) Tx without any store touch.
    // Cancels the LAZY (unstarted cancel prevents start), settles/unclaims
    // local+coordinator, clears the reconciling key, and publishes
    // IDLE+Unknown (fail-closed, requires fresh validation by the inheriting
    // VM). Safe to call redundantly (settle CAS + best-effort cancel).
    // Stage O1 P2#2: only the owner itself (pre-start cancel) or an abandoned
    // claim (ownerAlive==false, owner destroyed) may revoke. A live owner's Tx
    // is never aborted by a newly attached VM (init decides liveness via the
    // inline single-snapshot under commitMutex first).
    // Revocation explicitly clears ownerAlive so late attachers see abandoned.
    // P2-1: repair Tx keeps Corrupt=true (still damaged, fail-closed); only
    // non-repair aborts wash Corrupt=false. Matches the liveOwner Busy path
    // (policyDraftCorrupt = tx.isRepair).
    private fun abortUnarmedTx(tx: PolicyTx) {
        try {
            tx.ownerAlive.set(false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
        try {
            tx.deferred.cancel()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
        try {
            policyTxCoordinator?.tryCompleteClaim(tx)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
        synchronized(policySettleLock) {
            val curLocal = try { activePolicyTx.get() } catch (_: Throwable) { null }
            if (curLocal != null && curLocal !== tx) {
                try { policyReconciling.remove(tx.key) } catch (_: Throwable) { }
                return
            }
            try {
                activePolicyTx.compareAndSet(tx, null)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
            try {
                policyReconciling.remove(tx.key)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
            try {
                markPolicyUnknownLocked()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
            val keepCorrupt = try { tx.isRepair } catch (_: Throwable) { false }
            _state.update { cur ->
                if (cur.policyWritePhase != PolicyWritePhase.RECONCILING &&
                    cur.policyWritePhase != PolicyWritePhase.SAVING &&
                    cur.policyWritePhase != PolicyWritePhase.QUEUED
                ) {
                    cur.copy(policyDraftKnown = false, policyDraftCorrupt = keepCorrupt)
                } else {
                    cur.copy(
                        loading = false,
                        policyWritePhase = PolicyWritePhase.IDLE,
                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                        policyDraftKnown = false,
                        policyDraftCorrupt = keepCorrupt,
                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
            }
        }
    }
    private suspend fun decidePolicyTxSettle(tx: PolicyTx, read: LoadResult): PolicyTxSettle {
        // Stage N2 P1: never start/await an unvalidated job (see canJoinTx).
        if (!canJoinTx(tx)) return PolicyTxSettle.Unresolved
        if (!tx.deferred.isCompleted) return PolicyTxSettle.Unresolved
        val ok: Boolean = try {
            tx.deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        // Stage J2: ok==false 不得純憑句柄結 FAILED，須以已取 ground-truth 校準——
        // store 圍欄已保證毒讀為 Unknown，故此處 Known==sanitized 必為真值（例如併發
        // 他 VM 落盤同值），應 Success；否則 Failed。Unknown＋已證失敗 → Failed
        // （fail-closed，IDLE+Unknown）；Unknown＋ok==true → Unresolved 續留。
        if (!ok) {
            return when (read) {
                is LoadResult.Known ->
                    if (read.packages == tx.sanitized) PolicyTxSettle.Success(read.packages)
                    else PolicyTxSettle.Failed
                is LoadResult.Unknown -> PolicyTxSettle.Failed
            }
        }
        return when (read) {
            is LoadResult.Known ->
                if (read.packages == tx.sanitized) PolicyTxSettle.Success(read.packages)
                else PolicyTxSettle.Mismatch(read.packages)
            is LoadResult.Unknown -> PolicyTxSettle.Unresolved
        }
    }

    // Phase6 (P1 observer 雙路徑 false-authority)：兩 observer 兜底共用。
    // launchLoserSync 與 observeSettledFor 的 settled==null 兜底同調此 helper，
    // 唯一調 decidePolicyTxSettle，免雙路徑漂移（false-authority 根因：舊兜底見
    // Known 即 IDLE+Known+REFRESHED，不看 deferred 是否完成，late-failure 誤認
    // ground B 為成功）。
    // - canJoinTx false → abortUnarmedTx，不讀（呼叫端已先檢，此處防禦性再檢）。
    // - timedOut → Retain（逾時 Unknown 不可採納，不調 decide，直接續留）。
    // - Unresolved（含 !isCompleted 任何 Known、ok=true+Unknown）→ Retain：
    //   至多 RECONCILING+Known false+REFRESH_FAILED/CHANNEL 提示，pending/fence/
    //   reconciling 全保留，不 disarm，不 CAS 清，不廣播。
    // - Success/Mismatch/Failed（deferred 完成+ack+readback）→ Adopt：先過 I1
    //  （本地/coordinator 仍持同一 Tx，敗者僅清自身 key 不覆寫）+ TOCTOU
    //   ownership/version（期間新事實即丟棄不覆寫），才收斂 IDLE（Success
    //   SUCCESS+Known；Mismatch REFRESHED+ground B 永不 SUCCESS；Failed
    //   WRITE_FAILED+Unknown+markPolicyUnknownLocked）。
    // - PR #1: When coord != null and coord.activeTx.get() === tx, MUST claim coordinator
    //   ownership via claimForPublish(tx), stage real payload, clear local fences + IDLE
    //   atomically under policySettleLock, and emitStaged — never clear only activePolicyTx
    //   while leaving coord.activeTx === tx stranded.
    // - CE 透傳（scope 取消不吞）。
    internal suspend fun resolveObserverFallback(
        tx: PolicyTx,
        read: LoadResult,
        timedOut: Boolean,
    ) {
        if (!canJoinTx(tx)) {
            abortUnarmedTx(tx)
            return
        }
        val versionAtStart = policyDraftVersion.get()
        val ownershipSnap = policyOwnershipGen.get()
        if (timedOut) {
            retainUnresolvedReconciling(tx)
            return
        }
        val decision: PolicyTxSettle = try {
            decidePolicyTxSettle(tx, read)
        } catch (e: CancellationException) {
            throw e
        }
        if (decision is PolicyTxSettle.Unresolved) {
            retainUnresolvedReconciling(tx, corruptRead = read is LoadResult.Corrupt)
            return
        }
        // Adopt：I1 + TOCTOU，敗者不覆寫新事實（僅清自身 key）。
        val coord = policyTxCoordinator
        val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
        val curCoord = try { coord?.activeTx?.get() } catch (_: Exception) { null }
        if (curLocal != null && curLocal !== tx) {
            try {
                policyReconciling.remove(tx.key)
            } catch (_: Exception) {
            }
            return
        }
        if (coord != null && curCoord != null && curCoord !== tx) {
            clearObsoleteLocalTx(tx, coord)
            return
        }
        if (policyDraftVersion.get() != versionAtStart ||
            policyOwnershipGen.get() != ownershipSnap
        ) {
            try {
                policyReconciling.remove(tx.key)
            } catch (_: Exception) {
            }
            return
        }
        // Check if coordinator already has our terminal or an unseen foreign terminal,
        // or holds tx in coord.activeTx (PR #1 coordinator liveness).
        var claimedCoord = false
        if (coord != null) {
            val winnerForTx = try {
                coord.latest.value?.takeIf { it.txId == tx.txId && it.key == tx.key }
            } catch (_: Exception) {
                null
            }
            if (winnerForTx != null) {
                applyAuthoritativeSettled(tx, winnerForTx)
                return
            }
            val coordNow = try { coord.activeTx.get() } catch (_: Exception) { null }
            if (coordNow === tx) {
                if (!claimForPublish(tx)) {
                    // Lost coordinator claim to concurrent winner; claimForPublish already handled loser sync/cleanup.
                    return
                }
                claimedCoord = true
            } else if (coordNow != null) {
                clearObsoleteLocalTx(tx, coord)
                return
            } else {
                // coordNow == null and winnerForTx == null: inspect pendingPublish and unseen foreign terminal.
                val pend = try { coord.pendingPublish.get() } catch (_: Exception) { null }
                if (pend != null && pend.txId == tx.txId && pend.key == tx.key) {
                    val afterClaim = try {
                        coord.awaitSettled(tx.txId, tx.key, policyIoTimeoutMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (afterClaim != null) {
                        applyAuthoritativeSettled(tx, afterClaim)
                    } else {
                        retainUnresolvedReconciling(tx)
                    }
                    return
                }
                val foreignPending = pend != null && !(pend.txId == tx.txId && pend.key == tx.key)
                val foreignLatest = try { coord.latest.value } catch (_: Exception) { null }
                if (foreignLatest != null && foreignLatest.txId == tx.txId && foreignLatest.key == tx.key) {
                    applyAuthoritativeSettled(tx, foreignLatest)
                    return
                }
                val selfTombstoned = try {
                    coord.isTombstoned(tx.txId, tx.key)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    true
                }
                val lastR = try { lastRevisionSeenRev.get() } catch (_: Exception) { -1L }
                val unseenForeign = foreignPending || selfTombstoned || (
                    foreignLatest != null &&
                        !(foreignLatest.txId == tx.txId && foreignLatest.key == tx.key) &&
                        (foreignLatest.rev == -1L || lastR == -1L || foreignLatest.rev > lastR || policyRevisionStale.get())
                    )
                if (unseenForeign) {
                    synchronized(policySettleLock) {
                        val lNow = try { activePolicyTx.get() } catch (_: Exception) { null }
                        if (lNow === tx || (lNow == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                            activePolicyTx.compareAndSet(tx, null)
                            policyReconciling.remove(tx.key)
                            policyRevisionStale.set(true)
                            markPolicyUnknownLocked()
                            _state.update { cur ->
                                if (cur.policyWritePhase != PolicyWritePhase.RECONCILING &&
                                    cur.policyWritePhase != PolicyWritePhase.SAVING
                                ) cur
                                else cur.copy(
                                    loading = false,
                                    pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    policyDraftKnown = false,
                                    policyDraftCorrupt = tx.isRepair,
                                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                    }
                    return
                }
            }
        }
        val terminalPayload: PolicySettled = when (decision) {
            is PolicyTxSettle.Success -> {
                val repairTerminal: Pair<String, MsgKind>? = if (tx.isRepair) repairSuccessTerminal(tx) else null
                val successMsg = repairTerminal?.first ?: if (tx.isClear) {
                    policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS))
                } else {
                    writeSuccessMsg(decision.packages.size, tx.pending, tx.sanitized)
                }
                val successDraft = if (tx.isClear) emptySet() else decision.packages
                PolicySettled(tx.txId, tx.key, successDraft, true, repairTerminal?.second ?: MsgKind.INFO, successMsg, tx.isClear)
            }
            is PolicyTxSettle.Mismatch -> {
                PolicySettled(
                    tx.txId, tx.key, decision.packages, true, MsgKind.INFO,
                    policyText(PolicyText(PolicyTextKind.REFRESHED)), tx.isClear,
                )
            }
            is PolicyTxSettle.Failed -> {
                val failedMsg = policyText(
                    PolicyText(
                        if (tx.isRepair) PolicyTextKind.CORRUPT_REPAIR_FAILED
                        else if (tx.isClear) PolicyTextKind.CLEAR_FAILED else PolicyTextKind.WRITE_FAILED,
                    ),
                )
                PolicySettled(
                    tx.txId, tx.key, _state.value.policyDraft, false, MsgKind.FAILED,
                    failedMsg, tx.isClear,
                )
            }
            is PolicyTxSettle.Unresolved -> return
        }
        val stamped: PolicySettled? = if (claimedCoord) {
            emitTxSettledStageOnly(tx, terminalPayload)
        } else {
            null
        }
        var applied = false
        synchronized(policySettleLock) {
            val lNow = try { activePolicyTx.get() } catch (_: Exception) { null }
            val canApply = (lNow === tx) || (lNow == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)
            if (!canApply) {
                try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
                return@synchronized
            }
            if (!claimedCoord) {
                if (policyDraftVersion.get() != versionAtStart ||
                    policyOwnershipGen.get() != ownershipSnap
                ) {
                    try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
                    return@synchronized
                }
            }
            if (claimedCoord && stamped == null) {
                applyStageRejectedUnderLock(tx)
                return@synchronized
            }
            applied = true
            if (terminalPayload.known) {
                bumpPolicyGenerations()
                try { disarmBlindClear() } catch (_: Exception) { }
                try { disarmCorruptRepair() } catch (_: Exception) { }
                if (stamped == null && !claimedCoord) {
                    try { markObservedKnown() } catch (_: Exception) { }
                }
            } else {
                markPolicyUnknownLocked()
            }
            try { activePolicyTx.compareAndSet(tx, null) } catch (_: Exception) { }
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
            _state.update { cur ->
                if (cur.policyWritePhase != PolicyWritePhase.RECONCILING &&
                    cur.policyWritePhase != PolicyWritePhase.SAVING
                ) cur
                else cur.copy(
                    loading = false,
                    pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                    policyWritePhase = PolicyWritePhase.IDLE,
                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                    policyDraft = if (terminalPayload.known) terminalPayload.draft else cur.policyDraft,
                    policyDraftKnown = terminalPayload.known,
                    policyDraftCorrupt = tx.isRepair && !terminalPayload.known,
                    msg = terminalPayload.msg,
                    msgKind = terminalPayload.msgKind,
                )
            }
        }
        if (stamped != null) {
            coord?.emitStaged(stamped)
        }
        if (applied) {
            replayWinnerTerminalIfStranded(tx)
        }
    }
    // F1 有界 join（F1-simple：withTimeout(policyIoTimeoutMs)；F2 再預算化）：
    // true=句柄已證成功，false=已證失敗（含回 false 與拋出），null=仍 pending
    // （逾時未知，保留 fence 續讀）。CE 透傳（scope 取消，不吞為失敗）。
    // Stage F2：本 join 納入 reconciler 總預算（外層
    // withTimeout(POLICY_RECONCILE_TOTAL_BUDGET_MS)），此處內界不變。
    private suspend fun joinPolicyTxBounded(tx: PolicyTx): Boolean? {
        // Stage N2 P1: never start an unvalidated LAZY (await would start it).
        if (!canJoinTx(tx)) return null
        return try {
            withTimeout(policyIoTimeoutMs) { tx.deferred.await() }
        } catch (te: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    // Stage F2 有界讀（獨立池＋detach）：每次讀外包 withTimeout(policyIoTimeoutMs)，
    // 最多 POLICY_RECONCILE_RETRIES 次。Binder 不可中斷：loadRaw 若無視取消
    // （測試 latch/無限迴圈皆 catch InterruptedException 續跑），逾時僅放棄等待
    // （d.cancel 盡力），執行緒滯留獨立池，呼叫側計預算（timedOut=true）。回
    // (結果，是否逾時)：逾時一律 Unknown＋true；真異常 Unknown＋false；CE 透傳。
    // child-async（viewModelScope 子協程：VM 清除可取消；總預算/單讀逾時經手動
    // d.cancel 收斂，見下）。
    // Stage J4 (HoL)：改走 Dispatchers.IO 共享池（每輪獨立執行緒並行；首輪 latch
    // 無視取消滯留亦不阻塞第 2/3 輪，次輪 Known 即收斂，總預算內完成）。
    private suspend fun boundedReconcileRead(store: HookPolicyStore): Pair<LoadResult, Boolean> {
        val d = viewModelScope.async(Dispatchers.IO) { store.loadResult() }
        return try {
            val v: LoadResult = withTimeout(policyIoTimeoutMs) { d.await() }
            Pair(v, false)
        } catch (te: TimeoutCancellationException) {
            try {
                d.cancel()
            } catch (_: Exception) {
                // Best effort: 執行緒滯留獨立池，呼叫側已計預算。
            }
            LoadResult.Unavailable to true
        } catch (e: CancellationException) {
            try {
                d.cancel()
            } catch (_: Exception) {
                // Best effort.
            }
            throw e
        } catch (_: Exception) {
            try {
                d.cancel()
            } catch (_: Exception) {
                // Best effort.
            }
            LoadResult.Unavailable to false
        }
    }
    // Monotonic counter for unique clear-in-flight keys (avoids timestamp collisions).
    // Phase 3 關係註記：本計數器僅保證 "policy-clear:<n>" key 唯一（與
    // policyWriteGeneration 保證 PendingPolicyWrite.generation 唯一分工）；
    // UI 可觀測相位另由 policyPhaseEpoch 推進（見下），三者皆單調但用途正交：
    // clearGeneration/policyWriteGeneration＝操作身份，policyPhaseEpoch＝
    // UiState.policyEpoch（相位遷移可觀測），policyDraftVersion＋
    // policyFreshnessGen＝顯示 TOCTOU fence（Phase1 語義不動，寫入成功/失敗才 bump）；
    // Tx 所有權另由 policyOwnershipGen（Stage G1 拆分，reconciler 只比此者）。
    private val clearGeneration = java.util.concurrent.atomic.AtomicLong()
    // Phase 3：UiState.policyEpoch 的推進源（相位變遷即 +1；QUEUED→SAVING 保持
    // 同一 epoch，僅翻相位，見 request/cancel/confirm/clear 各點）。
    private val policyPhaseEpoch = java.util.concurrent.atomic.AtomicLong()
    // Stage D blind-clear 兩步確認（有界＋世代綁定）：Unknown 下第一次 clear
    // 僅告警並 arm，第二次才執行（防靜默單步刪除未見內容；見 clearPolicyDraft）。
    // Known（或 null-store）下直接單步執行並順手 disarm。
    // - blindClearArmedAtMs：arm 時刻 nowMonoMs()，-1＝未 arm（disarmed）。
    // - blindClearArmedVersion／FreshGen：arm 當下 policyDraftVersion／
    //   policyFreshnessGen 快照；第二 tap 比對失配即視為 stale（期間有
    //   save/clear/refresh/load 推進）→重 arm（視為新的第一次）。
    //   Stage G1：追 freshness（顯示），不追 ownership（Tx 期中 refresh yielding
    //   只推 freshness，仍應視為新事實而重 arm）。
    // - TTL：now - armedAt > BLIND_CLEAR_TTL_MS 即過期→重 arm＋EXPIRED 文案。
    // 三者以 -1 哨兵同步（disarm 全置 -1，arm 全寫當下值）；isArmed 以
    // armedAtMs != -1 判定。AtomicLong：Unconfined 測試下多執行緒可見，
    // 生產全 Main 串行。
    private val blindClearArmedAtMs = AtomicLong(-1L)
    private val blindClearArmedVersion = AtomicLong(-1L)
    private val blindClearArmedFreshGen = AtomicLong(-1L)

    // S4（blind-clear defense-in-depth）：行程級（process-lifetime）最後一次
    // 「已驗證 Known」的政策草稿快照（null＝從未觀察到），由 app-scoped
    // coordinator（GalaxyApp 持有的 PolicyTxCoordinator）持有，而非 VM 本機欄。
    // 同行程內跨 Activity 重建／重開（新 VM 實例）不變；只有新行程（新 coordinator、
    // 本欄＋VM 本機欄皆 null）才解除。所有 verified-Known 終態/刷新/載入的
    // noteLastKnownDraft() 呼叫點更新此欄（同一防禦拷貝複製第二份寫入 coordinator；兩份皆為拷貝，
    // 互不共享可變身份）。
    // 僅作為盲清前置門：盲清 armed 且（coordinator 或 VM 本機）有 Known 歷史時，
    // clearPolicyDraft 不再單純依賴兩次點擊，而是先強制一次
    // 權威重讀（refreshPolicyDraft）；只有重讀恢復 Known 才允許本次清除，
    // 重讀失敗（仍 Unknown/Unavailable）→ 中止並提示（UNKNOWN_REFRESH）。
    // Known 本身即證據；此欄只是「曾經 Known」的行程級歷史，永不被覆寫為 null。
    private val lastKnownPolicyDraft = AtomicReference<Set<String>?>(null)

    private fun noteLastKnownDraft(draft: Set<String>) {
        try {
            // 防禦性拷貝：呼叫端集合可能於後續被重用/變異（sanitize 已產新集合，
            // 此處再防一次）。永不拋出（Throwable 亦吞：OOM 等不應波及呼叫路徑）。
            val copy = HashSet(draft)
            // coordinator 先寫入（行程級史實的跨 VM 信號，monotonic），再寫 VM 本機；
            // VM 本機沿用舊路徑作為 coordinator 不存在時的回退。
            try { policyTxCoordinator?.lastKnownPolicyDraft?.set(HashSet(copy)) } catch (_: Throwable) {}
            lastKnownPolicyDraft.set(copy)
        } catch (_: Throwable) {
        }
    }

    // S4 fence 讀取助手：coordinator 有 Known 歷史即視為觀察過 Known；coordinator
    // 不可讀（取值拋出）時回退 VM 本機欄（舊測試路徑無 coordinator 時亦走此路）。
    // coordinator presence OR VM-local presence counts；coordinator 不可讀則回退本機。
    private fun lastKnownDraftObserved(): Boolean = try {
        val c = policyTxCoordinator?.lastKnownPolicyDraft?.get()
        c != null || lastKnownPolicyDraft.get() != null
    } catch (_: Throwable) {
        lastKnownPolicyDraft.get() != null
    }

    // 測試可見性：最後 Known 快照（驗證 S4 門控的存在/刷新行為；production 未用）。
    // 優先回 coordinator 持有的行程級值；coordinator 無值時回 VM 本機欄。
    @androidx.annotation.VisibleForTesting
    internal fun lastKnownPolicyDraftForTests(): Set<String>? = try {
        (policyTxCoordinator?.lastKnownPolicyDraft?.get() ?: lastKnownPolicyDraft.get())
    } catch (_: Throwable) {
        null
    }

    private fun isBlindClearArmed(): Boolean = blindClearArmedAtMs.get() != -1L

    private fun armBlindClearLocked() {
        blindClearArmedAtMs.set(nowMonoMs())
        blindClearArmedVersion.set(policyDraftVersion.get())
        blindClearArmedFreshGen.set(policyFreshnessGen.get())
    }

    private fun disarmBlindClear() {
        blindClearArmedAtMs.set(-1L)
        blindClearArmedVersion.set(-1L)
        blindClearArmedFreshGen.set(-1L)
    }
    // Stage L4 P2#3 corrupt-repair 兩步確認（與盲清 Unavailable 分離，平行 arm）：
    // Corrupt 下第一次修復請求僅告警並 arm，第二次才執行（防靜默單步刪除）。
    // Known（無損壞）或 Unavailable 下不 arm（分別走正常清除／盲清）。
    // - TTL 複用 BLIND_CLEAR_TTL_MS（5min，同盲清口徑）；逾時複用 EXPIRED 文案。
    // - 世代綁定同盲清：version／freshGen 任一失配即重 arm。
    private val corruptRepairArmedAtMs = AtomicLong(-1L)
    private val corruptRepairArmedVersion = AtomicLong(-1L)
    private val corruptRepairArmedFreshGen = AtomicLong(-1L)
    // Stage M1 P1: corrupt-repair arm carries the coordinator committedRevision
    // snapshot at request time (authoritative cross-VM base, parallel to
    // PendingPolicyWrite.baseCoordinatorRevision). confirm's pre-IO gate compares
    // it under policyRevisionMutex→commitMutex (union drain); mismatch (unseen B)
    // aborts without IO. -1L = legacy/no-coordinator (skip revision check).
    private val corruptRepairArmedBaseRev = AtomicLong(-1L)

    // Stage L4 P2#3 corrupt-repair armed 狀態（UI 清除鈕路由用：未 arm 走
    // request（第一次 tap），已 arm 走 confirm（第二次 tap，TTL＋世代雙檢）。
    // 內部三元組仍以 -1 哨兵同步，生產 Main 串行、測試 Unconfined 可見。
    fun isCorruptRepairArmed(): Boolean = corruptRepairArmedAtMs.get() != -1L

    // Stage (P2-2): arm the corrupt-repair confirm gate on an AUTHORITATIVE
    // base revision. A non-null coordinator whose revision cannot be sampled
    // (throws or yields -1) makes the base unverifiable — arming anyway would
    // let the repair's success claim publish over unseen foreign commits with
    // the rev gate opted out. Fail closed: leave the arm DISARMED and return
    // false (the caller publishes UNKNOWN_REFRESH, never CORRUPT_REPAIR_FIRST).
    // The -1 base is kept ONLY for the legacy null-coordinator path (no
    // authoritative source at all).
    private fun armCorruptRepairLocked(): Boolean {
        val base = authoritativeRevisionSample()
        if (base == null) {
            corruptRepairArmedAtMs.set(-1L)
            corruptRepairArmedVersion.set(-1L)
            corruptRepairArmedFreshGen.set(-1L)
            corruptRepairArmedBaseRev.set(-1L)
            return false
        }
        corruptRepairArmedAtMs.set(nowMonoMs())
        corruptRepairArmedVersion.set(policyDraftVersion.get())
        corruptRepairArmedFreshGen.set(policyFreshnessGen.get())
        corruptRepairArmedBaseRev.set(base)
        return true
    }

    private fun disarmCorruptRepair() {
        corruptRepairArmedAtMs.set(-1L)
        corruptRepairArmedVersion.set(-1L)
        corruptRepairArmedFreshGen.set(-1L)
        corruptRepairArmedBaseRev.set(-1L)
    }
    // Phase 2 手動重新整理在途計數：policyRefreshing = count > 0（最後完成者清位，
    // 世代/版本丟棄的 stale 結果不提前清位，save bump 亦不洩漏）。
    private val policyRefreshInFlight = AtomicInteger(0)

    // Exposed for UI gating (policy confirm enablement) independent of the
    // global loading flag. True while a policy write/clear is queued OR its
    // store IO has started. Cancelability uses policyWriteCommitInProgress
    // (saving only): a merely-queued confirm is still cancellable.
    // Phase 3：唯一真相已遷入 UiState.policyWritePhase，render 不再讀此二 getter
    //（見 render：s.policyWritePhase != IDLE / == SAVING || == RECONCILING）。
    // 此二 getter 僅為相容保留（舊測試/外部調用），以「state OR 集合」實現，避免 state 更新與集合
    // add 之間的瞬時窗口漏報；新碼一律讀 state。
    @Deprecated(
        "Render 請讀 state.policyWritePhase != IDLE；僅相容保留",
        ReplaceWith("state.value.policyWritePhase != PolicyWritePhase.IDLE"),
    )
    val policyOperationInProgress: Boolean
        get() = _state.value.policyWritePhase != PolicyWritePhase.IDLE ||
            inFlightWrites.any { it.startsWith("policy-") }

    // True only once a policy save IO has actually started
    // (policyWriteSaving non-empty); queued-behind-mutex is still cancellable.
    // Phase 3：同上，唯一真相為 state.policyWritePhase == SAVING；此 getter 僅相容。
    // Stage A：核對中視同提交中（render 同 SAVING 禁用；集合另有 reconciling 交接，
    // 此處以 state OR 三集合實現，避免窗口漏報）。
    @Deprecated(
        "Render 請讀 state.policyWritePhase == SAVING；僅相容保留",
        ReplaceWith("state.value.policyWritePhase == PolicyWritePhase.SAVING"),
    )
    val policyWriteCommitInProgress: Boolean
        get() = _state.value.policyWritePhase == PolicyWritePhase.SAVING ||
            _state.value.policyWritePhase == PolicyWritePhase.RECONCILING ||
            policyWriteSaving.isNotEmpty() || policyReconciling.isNotEmpty()

    // Busy ownership (階段L P2): a cancelled waiter must not clear loading
    // while a sibling holder still owns the mutex. All four coalesced entries
    // (refresh/recheck/reconnect/foreground) are direct viewModelScope children
    // (clone/write 同為直屬 children，故可見)；外層 CE catch 清位前先檢查除自己外
    // 是否還有 active sibling：有 → 維持 busy（不清除），無 → 才 loading=false。
    // 一律 rethrow CE。selectChannel 不動 (successor 覆寫)。
    private fun hasActiveSibling(excluding: Job?): Boolean {
        val scopeJob = viewModelScope.coroutineContext[Job] ?: return false
        return scopeJob.children.any { it !== excluding && it.isActive }
    }

    // Single constructor for in-flight write dedup keys; keeps the
    // "clone:<pkg>" / "request-remove:<pkg>" namespaces consistent.
    private fun opKey(op: String, pkg: String) = "$op:$pkg"
    // Policy-write confirm dedup key (generation-bound, matches cancel +
    // saving-set lookups; single source so the three sites never drift).
    private fun policyWriteKey(p: PendingPolicyWrite) =
        "policy-write:${p.requestedAtMs}#${p.generation}"
    // P2 persist 訊息含捨去數（single source）：WRITE_SUCCESS＋DROPPED 後綴，
    // 與 request 警語/dialog 對齊，免靜默。dropped 含 request 時 catalog 捨去
    // （pending.droppedCount）與 confirm 重消毒增量（packages - sanitized）。
    private fun writeSuccessMsg(count: Int, pending: PendingPolicyWrite?, sanitized: Set<String>): String {
        val base = policyText(PolicyText(PolicyTextKind.WRITE_SUCCESS, count))
        val confirmExtra = if (pending != null) {
            (pending.packages.size - sanitized.size).coerceAtLeast(0)
        } else 0
        val total = (pending?.droppedCount ?: 0) + confirmExtra
        return if (total > 0) {
            base + "\n" + policyText(PolicyText(PolicyTextKind.DROPPED, total))
        } else base
    }
    // PR-7 P2 (issue #26):已對哪個目標自動恢復過持久選取 (in-memory marker)。
    // SELECTION_RESTORE_NONE = 從未恢復;恢復只在 selectedKeys 為空時執行
    // 一次 (永不覆蓋使用者當前勾選,永不自動重送寫入)。目標改變 (profile
    // 變動/serial 重建) 後允許對新目標再恢復一次 (舊目標條目已由 SelectionStore 失效清除)。
    // PR-37 Phase B #2：marker 為 (id, serial) 配對——同 id 重建（新 serial）
    // 視為新目標，仍可恢復一次；舊 marker 不阻擋新 serial 的恢復。
    // Stage5 併發：Main-confinement (見類註解；生產全 Main，同值)；@Volatile
    // 僅為 Unconfined 測試可見性 (toggle 跑測試執行緒、restore 提交跑 IO 執行緒)，
    // 與 selectionGen (AtomicLong) 同理，不改變生產串行語義。
    @Volatile private var selectionRestoredForTarget: SelectionTarget = SELECTION_RESTORE_NONE
    // PR-7 P2 Phase B #1：選取綁定目標（in-memory）。
    // - restore 成功（kept 非空）或使用者在已驗證目標下修改勾選時綁定到當下
    //   inventory 的 (targetId, targetSerial)；清空勾選後回到哨兵。
    // - refreshLocked 偵測到 verified target 變動（bound 舊≠新）時清空
    //   selectedKeys＋batchReport、重置恢復 marker，msg 明示目標變更。
    // - Serial 綁定（PR-37 Phase B #2）：綁定為 (id, serial) 配對，由當下
    //   已驗證 inventory 取得（refresh 以 repo.refreshVerified 一次取回 id＋serial，
    //   與舊 resolveDualUserId 同為三通道、同等裝置呼叫，零額外開銷）。
    //   殘留風險如實揭露：inventory serial 未知（SerialUnavailable 罕見路徑）
    //   或 legacy 快照恢復時綁定為 id-only (serial null)；id-only 綁定永不
    //   靜默提升為完全體——批次要求完全體 (id,serial) 相符才執行，否則整批拒絕。
    // Stage5 併發：同上 Main-confinement＋@Volatile (測試可見性，生產仍串行)。
    @Volatile private var selectionBoundTarget: SelectionTarget = SELECTION_BOUND_NONE
    // PR-37 Phase B R4 #2：選取變更單調計數（lost-update 競態守衛，ABA 安全）。
    // - toggle/setSelected/selectVisible/clear（含無-opMutex 使用者路徑）實際
    //   改變 selectedKeys 時＋1；mutex 下清空（handle/batch drift）與 restore
    //   提交成功亦＋1（單調，不重用）。
    // - restore 起始記錄 startGen，提交前比對 gen 未變＋仍為空；中途
    //   「有→清除→空」與「一直空」以 gen 區分（isEmpty 無法區分 ABA）。
    // - AtomicLong：Unconfined 測試下 toggle 跑測試執行緒、restore 提交跑 IO
    //   執行緒，仍可見有序（生產全 Main，同值）。
    private val selectionGen = AtomicLong(0L)

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
    // Stage D：同步 disarm blind-clear（VM 丟棄後不得殘留確認態）。
    // Stage L4: 同步 disarm corrupt-repair（同上）。
    // Stage F2：盡力關閉獨立讀池（阻塞中執行緒仍滯留至行程結束）。
    // Stage O1 P2#2: owner-liveness revoke — dispose marks any owned PREPARING
    // (unvalidated) Tx as abandoned (ownerAlive=false) so a later VM may abort
    // it; live VMs never touch another owner's flag. Armed/in-flight Txs keep
    // their fence for inheritance (activeTx retained, ownerAlive stays true until
    // explicit pre-start revoke or settle).
    fun dispose() {
        try { busyWaiterJob?.cancel() } catch (_: Exception) { }
        try { busyWaiterJob = null } catch (_: Exception) { }
        try {
            val owned = try { activePolicyTx.get() } catch (_: Exception) { null }
            if (owned != null && !canJoinTx(owned)) {
                try { owned.ownerAlive.set(false) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
        disarmBlindClear()
        disarmCorruptRepair()
        teardownShizukuBindings()
        closeReconcileDispatcher()
    }

    override fun onCleared() {
        try { busyWaiterJob?.cancel() } catch (_: Exception) { }
        try { busyWaiterJob = null } catch (_: Exception) { }
        try {
            val owned = try { activePolicyTx.get() } catch (_: Exception) { null }
            if (owned != null && !canJoinTx(owned)) {
                try { owned.ownerAlive.set(false) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
        disarmBlindClear()
        disarmCorruptRepair()
        teardownShizukuBindings()
        closeReconcileDispatcher()
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
        // Stage G2：跨 VM 認領（同步， ран於 probe/load 續發）。
        // coordinator 仍有在途 Tx（舊 VM handover 後未結清、onCleared 刻意不清除）
        // 即認領：繼承 fence＋RECONCILING＋pending＋Unknown＋啟動 reconciler join
        // 同一 deferred；終態防雙發布經 tryClaimCoordinatorPublish 做 settle-CAS
        // 先行、勝者才發布（認領沿用同一 Tx 物件；舊 VM 殘留 handler 以 coordinator
        // CAS 判定丟棄，敗者不發布）。
        // 認領同步 bump version/freshness/ownership，晚到的 init load 因世代失配被
        // 丟棄（不覆寫 fence）；state 同步翻 RECONCILING＋Unknown＋pending。
        // Stage L1 P1: init-reclaim checks committedRevision/pendingPublish under
        // commitMutex (tryLock, non-suspend init; order
        // opMutex→writeMutex→policyRevisionMutex→commitMutex).
        run {
            val coordInit = policyTxCoordinator ?: return@run
            var reclaimedTx: PolicyTx? = null
            // P2-4: liveness decided under the SAME tryLock snapshot below (no
            // torn read between activeTx/ownerAlive/deferred/pending/dropGen).
            var reclaimedLive = false
            // Self-reference: DualViewModel.PolicyTx == PolicyTx in this scope.
            var lockedInit = false
            try {
                lockedInit = try { coordInit.commitMutex.tryLock() } catch (_: Exception) { false }
                // Stage O1 extra (init tryLock drop): only trust the snapshot when the
                // lock was actually held (single-structure snapshot under commitMutex).
                // tryLock failure => stale read, defer to async withCommitLock below
                // (never activeTx value but no VM claim).
                if (lockedInit) {
                    try {
                        @Suppress("UNUSED_VARIABLE")
                        val revInit = try { coordInit.committedRevision.get() } catch (_: Exception) { -1L }
                        @Suppress("UNUSED_VARIABLE")
                        val stagedInit = try { coordInit.pendingPublish.get() } catch (_: Exception) { null }
                        reclaimedTx = try { coordInit.activeTx.get() } catch (_: Exception) { null }
                        // P2-4: dropGen sampled in the SAME lock observation as
                        // rev/pending/active (no split-window staleness).
                        try {
                            val cur = observedSnapshot.get()
                            val dg = try { coordInit.orphanDropGen.get() } catch (_: Exception) { 0L }
                            observedSnapshot.set(cur.copy(dropGen = dg))
                        } catch (_: Exception) {
                        }
                        // P2-4: single-snapshot liveness (flag + deferred/age backstop).
                        reclaimedLive = run {
                            val t = reclaimedTx ?: return@run false
                            if (!canJoinTx(t)) {
                                val alive = try { t.ownerAlive.get() } catch (_: Exception) { false }
                                if (!alive) return@run false
                                val done = try { t.deferred.isCompleted || t.deferred.isCancelled } catch (_: Exception) { false }
                                if (!done) return@run true
                                val pend = try { coordInit.pendingPublish.get() } catch (_: Exception) { null }
                                if (pend == null || pend.txId != t.txId || pend.key != t.key) return@run false
                                val stagedAt = try { coordInit.pendingPublishAtNanos.get() } catch (_: Exception) { 0L }
                                if (stagedAt == 0L) return@run false
                                val now = try { System.nanoTime() } catch (_: Exception) { 0L }
                                if (now < stagedAt) return@run true
                                val ageMs = try { (now - stagedAt) / 1_000_000L } catch (_: Exception) { 0L }
                                ageMs < policyIoTimeoutMs
                            } else {
                                true
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } finally {
                if (lockedInit) {
                    try { coordInit.commitMutex.unlock() } catch (_: Exception) { }
                }
            }
            // Stage O1 extra: tryLock missed => async locked reclaim (delayed retry).
            // Guarantees every activeTx value is eventually claimed by some live VM.
            if (!lockedInit) {
                try {
                    viewModelScope.launch {
                        try {
                            coordInit.withCommitLock {
                                val txAsync = try { coordInit.activeTx.get() } catch (_: Exception) { null }
                                    ?: return@withCommitLock
                                if (activePolicyTx.get() != null) return@withCommitLock
                                if (!canJoinTx(txAsync)) {
                                    // Stage O1 P2#2: live owner => never cancel.
                                    // P2-4: single-snapshot liveness — this block already
                                    // holds commitMutex, so read active/owner/deferred/
                                    // pending/age inline (the former torn-read helpers
                                    // are deleted; inline is the single source).
                                    val liveAsync = run {
                                        val curA = try { coordInit.activeTx.get() } catch (_: Exception) { null }
                                        if (curA !== txAsync) return@run false
                                        val aliveA = try { txAsync.ownerAlive.get() } catch (_: Exception) { false }
                                        if (!aliveA) return@run false
                                        val doneA = try { txAsync.deferred.isCompleted || txAsync.deferred.isCancelled } catch (_: Exception) { false }
                                        if (!doneA) return@run true
                                        val pendA = try { coordInit.pendingPublish.get() } catch (_: Exception) { null }
                                        if (pendA == null || pendA.txId != txAsync.txId || pendA.key != txAsync.key) return@run false
                                        val stagedAtA = try { coordInit.pendingPublishAtNanos.get() } catch (_: Exception) { 0L }
                                        if (stagedAtA == 0L) return@run false
                                        val nowA = try { System.nanoTime() } catch (_: Exception) { 0L }
                                        if (nowA < stagedAtA) return@run true
                                        val ageA = try { (nowA - stagedAtA) / 1_000_000L } catch (_: Exception) { 0L }
                                        ageA < policyIoTimeoutMs
                                    }
                                    if (liveAsync) {
                                        try { markPolicyUnknownLocked() } catch (_: Exception) { }
                                        _state.update { cur ->
                                            cur.copy(
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = txAsync.isRepair,
                                                msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                                msgKind = MsgKind.INFO,
                                            )
                                        }
                                        // P2-4: Busy must not strand forever if the owner
                                        // dies right after this snapshot (no re-estimate, no
                                        // timeout, no adopt). Attach a light waiter that
                                        // rechecks under commitMutex and aborts/reclaims
                                        // once the claim is abandoned.
                                        try { attachBusyWaiter(txAsync) } catch (_: Exception) { }
                                        return@withCommitLock
                                    }
                                    // P2-4 fix: only abort a Tx we actually hold locally.
                                    // CAS null->txAsync first; failure means another
                                    // VM/op claimed locally concurrently — return
                                    // without aborting someone else's fence.
                                    val heldAbandoned = synchronized(policySettleLock) {
                                        try { activePolicyTx.compareAndSet(null, txAsync) } catch (_: Exception) { false }
                                    }
                                    if (!heldAbandoned) return@withCommitLock
                                    try { abortUnarmedTx(txAsync) } catch (ce: CancellationException) { throw ce } catch (_: Exception) { }
                                    return@withCommitLock
                                }
                                // P2-4 fix: joinable reclaim must also CAS (never blind
                                // set). Failure => local race lost; re-read and let
                                // the _latest collector converge (never overwrite).
                                val heldJoin = synchronized(policySettleLock) {
                                    if (activePolicyTx.compareAndSet(null, txAsync)) {
                                        try { policyReconciling.add(txAsync.key) } catch (_: Exception) { }
                                        try { markPolicyUnknownLocked() } catch (_: Exception) { }
                                        _state.update { cur ->
                                            cur.copy(
                                                pendingPolicyWrite = txAsync.pending ?: cur.pendingPolicyWrite,
                                                policyWritePhase = PolicyWritePhase.RECONCILING,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = txAsync.isRepair,
                                                loading = false,
                                                msg = policyText(
                                                    PolicyText(
                                                        if (txAsync.isRepair) PolicyTextKind.CORRUPT_REPAIR_TIMEOUT
                                                        else if (txAsync.isClear) PolicyTextKind.CLEAR_TIMEOUT else PolicyTextKind.WRITE_TIMEOUT,
                                                    ),
                                                ),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (!heldJoin) return@withCommitLock
                                try { attachDeferredAutoRecheck(txAsync) } catch (_: Exception) { }
                                if (txAsync.isClear) launchClearReconciler(txAsync) else launchConfirmReconciler(txAsync)
                                observeSettledFor(txAsync)
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                }
                return@run
            }
            val tx = reclaimedTx ?: return@run
            if (activePolicyTx.get() == null) {
                // Stage N2 P1: inherit only validated (ARMED/STARTED) Tx. A
                // PREPARING (unvalidated, pre-start-cancelled) Tx must NOT be
                // joined — its LAZY await would start the destructive delete
                // without either VM completing final validation. Abort it here
                // (cancel unstarted LAZY, unclaim, IDLE+Unknown) and require a
                // fresh Corrupt+revision validation by this VM.
                // Stage O1 P2#2: distinguish live PREPARING owner from abandoned.
                // Live owner (coordinator still holds Tx AND ownerAlive) => new VM
                // must NOT cancel the shared LAZY; mark self Unverified/Busy and
                // wait for the owner to finish (converge via _latest collector).
                if (!canJoinTx(tx)) {
                    // P2-4: use the single-snapshot verdict taken under the init
                    // tryLock above (never a torn lock-free re-read here). false on
                    // tryLock miss is handled by the async locked reclaim path;
                    // reaching here means lockedInit held, so the flag is fresh.
                    val liveOwner = reclaimedLive
                    if (liveOwner) {
                        try { markPolicyUnknownLocked() } catch (_: Exception) { }
                        _state.update { cur ->
                            cur.copy(
                                policyDraftKnown = false,
                                policyDraftCorrupt = tx.isRepair,
                                msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                msgKind = MsgKind.INFO,
                            )
                        }
                        // P2-4: Busy waiter (see async path): recheck under
                        // commitMutex; abort/reclaim once abandoned, never strand.
                        try { attachBusyWaiter(tx) } catch (_: Exception) { }
                        return@run
                    }
                    // Sync init blind sets become CAS: failure => local race lost,
                    // re-read and let _latest converge (never overwrite).
                    // Hold-check: already holding same Tx => proceed to abort.
                    val heldAbSync = synchronized(policySettleLock) {
                        val curLocalAb = try { activePolicyTx.get() } catch (_: Exception) { null }
                        curLocalAb === tx || try { activePolicyTx.compareAndSet(null, tx) } catch (_: Exception) { false }
                    }
                    if (!heldAbSync) return@run
                    abortUnarmedTx(tx)
                    return@run
                }
                // P2-4 fix: joinable reclaim must also CAS (never blind set).
                // Failure => local race lost; re-read and let _latest converge.
                // Hold-check first: if we already hold this exact Tx (e.g. async
                // reclaim won before sync), proceed (CAS null->tx would fail
                // spuriously on same-identity).
                val heldJoinSync = synchronized(policySettleLock) {
                    val curLocalJoin = try { activePolicyTx.get() } catch (_: Exception) { null }
                    if (curLocalJoin !== tx && !try { activePolicyTx.compareAndSet(null, tx) } catch (_: Exception) { false }) {
                        false
                    } else {
                        policyReconciling.add(tx.key)
                        markPolicyUnknownLocked()
                        _state.update { cur ->
                            cur.copy(
                                pendingPolicyWrite = tx.pending ?: cur.pendingPolicyWrite,
                                policyWritePhase = PolicyWritePhase.RECONCILING,
                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                policyDraftKnown = false,
                                policyDraftCorrupt = tx.isRepair,
                                loading = false,
                                msg = policyText(
                                    PolicyText(
                                        if (tx.isRepair) PolicyTextKind.CORRUPT_REPAIR_TIMEOUT
                                        else if (tx.isClear) PolicyTextKind.CLEAR_TIMEOUT else PolicyTextKind.WRITE_TIMEOUT,
                                    ),
                                ),
                                msgKind = MsgKind.CHANNEL,
                            )
                        }
                        true
                    }
                }
                if (!heldJoinSync) return@run
                attachDeferredAutoRecheck(tx)
                if (tx.isClear) launchClearReconciler(tx) else launchConfirmReconciler(tx)
                // Stage H2：init/認領後訂閱 settled（同一 txId 即應用本地 IDLE 終態，
                // replay=1 晚到仍可見；與 reconciler 敗者跟隨同 helper，冪等）。
                observeSettledFor(tx)
            }
        }
        // Stage I2：每 VM init 常駐收集 settled（不限認領單一 txId；replay=1 晚到
        // 仍可見）。異 Tx 新終態：無 activeTx 即 bump＋更新（phase 保持 IDLE），
        // 有 activeTx 即丟棄＋記 stale（後續 request/confirm 強制重讀）。
        observeRevisionUpdates()
        // Probe elevation channels first; later ops queue behind this, so the
        // first refresh always sees the routed privilege, never a default.
        viewModelScope.launch {
            opMutex.withLock {
                probeChannels(
                    allowPrivilegedHookDiagnosis = false,
                    resolvedBeforeOp = null,
                )
            }
        }
        // Stage2:冷啟動載入政策草稿 (唯讀，不觸發任何寫入；失敗即留空，
        // 不擋 probe/refresh)。Stage G1：顯示 fence（version＋freshness）。
        viewModelScope.launch {
            val generation = policyFreshnessGen.incrementAndGet()
            val versionAtStart = policyDraftVersion.get()
            val store = hookPolicyStore
            // P1-1: sample the authoritative revision BEFORE the trusted IO. If a
            // concurrent B commits (placeholder rev bump, no _latest event) while
            // this read is in flight, the Known payload below is stale (A) and must
            // be discarded (stay Unknown, fail closed) — never marked observed.
            // Phase7: via sampledCurrentRevision() so barrier tests can fake
            // the revision source (count + latch).
            // Sampler/null distinction (fail-closed): sampler==null && coordinator==null
            // is legacy (no authoritative rev, -1 skips); otherwise -1 is unverifiable
            // and must discard Known below (never mark observed).
            val revBeforeInit = try { sampledCurrentRevision() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { -1L }
            val hasAuthInit = policyTxCoordinator != null || revisionSamplerForTests != null
            val revUnverifiableInit = revBeforeInit == -1L && hasAuthInit
            // Stage J4 P1 (identity-aware rev-discard): sample the in-flight
            // Tx identities before IO. A rev move caused by our OWN commit
            // (winner bumped version triple above, so the version fence already
            // discards; this covers the split snapshot window where the version
            // was sampled after the bump but revBefore before it) must not wash
            // Known=false — _latest with the same identity is own-commit truth.
            val initLocalSnap = try { activePolicyTx.get() } catch (_: Throwable) { null }
            val initCoordSnap = try { policyTxCoordinator?.activeTx?.get() } catch (_: Throwable) { null }
            val result = if (store == null) {
                LoadResult.Unavailable
            } else try {
                onIo { store.loadResult() }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                LoadResult.Unavailable
            }
            // P1-1: revision moved during the read => stale Known, discard.
            // Phase7: via sampledCurrentRevision() (barrier-test seam).
            // Check failure is fail-closed (true => discard, never pass).
            val revMovedInit = try {
                revBeforeInit != -1L && sampledCurrentRevision() != revBeforeInit
            } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { true }
            synchronized(policySettleLock) {
                if (hasActivePolicyTx()) return@synchronized
                if (policyDraftVersion.get() != versionAtStart ||
                    policyFreshnessGen.get() != generation
                ) {
                    return@synchronized
                }
                if (result is LoadResult.Known) {
                    if (revUnverifiableInit || revMovedInit) {
                        val ownCommit: Boolean = try {
                            val latest = policyTxCoordinator?.latest?.value
                            latest != null && (
                                (initLocalSnap != null &&
                                    latest.txId == initLocalSnap.txId &&
                                    latest.key == initLocalSnap.key) ||
                                    (initCoordSnap != null &&
                                        latest.txId == initCoordSnap.txId &&
                                        latest.key == initCoordSnap.key)
                                )
                        } catch (_: Throwable) { false }
                        if (!ownCommit) {
                            _state.update { cur ->
                                cur.copy(policyDraftKnown = false, policyDraftCorrupt = cur.policyDraftCorrupt)
                            }
                        }
                    } else {
                        // Verified mark: third-sample guard closes revAfter->mark window.
                        // On concurrent commit, fail closed to Unknown (no disarm, no mark).
                        val verified = try {
                            markObservedKnownVerified(revBeforeInit)
                        } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { false }
                        if (!verified) {
                            try { policyRevisionStale.set(true) } catch (_: Throwable) { }
                            _state.update { cur ->
                                cur.copy(policyDraftKnown = false, policyDraftCorrupt = cur.policyDraftCorrupt)
                            }
                        } else {
                            disarmBlindClear()
                            disarmCorruptRepair()
                            try { noteLastKnownDraft(result.packages) } catch (_: Exception) { }
                            _state.update { cur ->
                                cur.copy(policyDraft = result.packages, policyDraftKnown = true, policyDraftCorrupt = false)
                            }
                        }
                    }
                } else {
                    val isCorrupt = result is LoadResult.Corrupt
                    if (isCorrupt) disarmBlindClear()
                    _state.update { cur ->
                        cur.copy(policyDraftKnown = false, policyDraftCorrupt = isCorrupt)
                    }
                }
            }
        }
    }

    // Reloads profiles AND rebuilds `cloned` from the dual user's actual
    // packages via the single-target refresh (Phase D P2-D): one
    // profilesSnapshot (1 cmd) + one typed triple via resolveDualTargetTyped
    // (3 cmds) + one list probe on that same id (1 cmd) + post-list re-verify
    // triple (3 cmds) = 8 commands (second triple is the strict-equality fence). Four states are told
    // apart explicitly (issue #9):
    // - ChannelError: `pm list users` itself failed -> "channel error: ...".
    // - NoDual: channel ok but no single DUAL profile -> "no dual profile".
    // - Unknown: identity unconfirmable (serial/parent conflict, refused,
    //   confirmed id missing from the snapshot, Absent with an empty
    //   snapshot, ...) -> "refresh result unknown: cannot confirm dual
    //   profile", keep the old cloned.
    // - Empty: dual exists but owns no clones -> "no clones installed".
    // A failed clone-listing (GroundTruth cloned == null) keeps the previous
    // `cloned` and reports "result unknown" rather than claiming the list is
    // empty. Observable-serial drift is eliminated inside this API (single
    // target throughout); serial==null proves only same (id,parent) twice
    // + listing, not incarnation (known limit, see repo KDoc). The
    // explicit-id residual remains only in the old entries.
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
        // cancelled job clears loading before rethrowing (see class doc);
        // selectChannel is the only path relying on the successor overwrite.
        viewModelScope.launch {
            // Stage F1：全域 refresh 入口 Tx 存活即併發丟棄（選併發丟棄，不選短路
            // BUSY：全局 refresh 不擁有 policy busy，誤發 BUSY/loading=true 會
            // 覆寫 TIMEOUT 提示並搶 loading；丟棄即無聲 no-op，保留
            // RECONCILING＋TIMEOUT＋pending，draft 不被覆寫；phase 恆 RECONCILING）。
            if (hasActivePolicyTx()) return@launch
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
            } catch (e: CancellationException) {
                // K2 + 階段L：opMutex 外層等待被取消時 CE 在進入 refreshLocked 體前拋出，
                // 內層清位覆蓋不到；僅無 active sibling 時補清 loading（有 holder 續持
                // busy 時維持 true，免 spinner 消失/按鈕重啟），一律重拋。
                if (!hasActiveSibling(myJob)) _state.update { it.copy(loading = false) }
                throw e
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
        // Stage F1：Tx 存活即併發丟棄（鎖內兜底，覆蓋 trailing；不碰 loading，
        // 保留 RECONCILING；disarm 仍執行與否？此處直接返回，連 disarm 都不做，
        // Tx 終態才 disarm，避免重連誤清核對中的 stale-arm 洩漏修復語義）。
        // Stage G2：含 coordinator（跨 VM）。
        if (hasActivePolicyTx()) return
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
        // Stage D：斷開/重連即 disarm blind-clear（通道/遠端可能已變，舊 arm
        // 不得殘留；含 coalesced trailing 經此路徑）。
        // Stage L4: 同步 disarm corrupt-repair（同上）。
        _state.update { it.copy(loading = true) }
        disarmBlindClear()
        disarmCorruptRepair()
        try {
            // P25 Item 3: one probe per round — swapAutoLocked probes once and
            // returns the result for probeChannels to reuse (previously each
            // probed all elevators: double `su -c` cost + double auth-prompt risk).
            // Phase 2: 鏈式共用一次 loading (probe 保留 busy，refresh 結尾才清除)。
            // P2-4（僅 AUTO 有效）: swap 前記錄 resolved，納入 probe 的 FRESH 失效判斷。
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
            }
            refreshLocked() // BOTH AUTO and explicit, inside try
        } catch (e: CancellationException) {
            _state.update { it.copy(loading = false) }
            throw e
        }
    }

    // Reload body shared by refresh() and selectChannel(). Must run under
    // opMutex: callers hold it, so this never launches or locks itself.
    // Single-snapshot refresh (#53): refreshLocked runs ONLY
    // repo.refreshVerified() (snapshot 1 + target 3 cmds + list 1 + re-verify triple 3 = 8) and
    // maps its RefreshOutcome to UiState -- the second triple inside
    // refreshVerified is the strict-equality fence, so with observable serial
    // no cross-window (id, serial) pair can be spliced into a FRESH listing;
    // serial==null proves only same (id,parent) twice + listing, not
    // incarnation (known limit, see repo KDoc).
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
    // degrade/channelView short tag).
    // Mirrors the pendingRemove preserve rule (NONE/INFO combinable, errors pass).
    private fun combineWithDegradeNote(
        baseMsg: String,
        baseKind: MsgKind,
        degrade: WriteBlock?,
    ): Pair<String, MsgKind> {
        if (preference != ChannelPreference.AUTO || degrade == null) return baseMsg to baseKind
        val degradeText = blockText(degrade)
        return when (baseKind) {
            MsgKind.NONE -> degradeText to MsgKind.INFO
            MsgKind.INFO -> "$baseMsg；$degradeText" to MsgKind.INFO
            else -> "$baseMsg；$degradeText" to baseKind
        }
    }

    private suspend fun refreshLocked() {
        // Stage F1：Tx 存活即併發丟棄（鎖內兜底，覆蓋 drainCoalesced trailing：
        // 不碰 loading/msg/phase/draft，保留 RECONCILING＋TIMEOUT）。
        // Stage G2：含 coordinator（跨 VM）。
        if (hasActivePolicyTx()) return
        // NOTE (Phase 3): the pendingRemove-preserve guard below is
        // intentionally duplicated per RefreshOutcome branch (no helper
        // extracted). Only the GroundTruth listed branch can actually
        // trigger it (msgKind NONE/INFO); the ChannelError/NoDual/Unknown/
        // TargetMoved/GroundTruth-null/catch branches set
        // CHANNEL/NO_DUAL/INVALID/FAILED, so their guards are dead but
        // kept for symmetry.
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
            // Single snapshot (#53): one repo.refreshVerified() pins the
            // `pm list users` snapshot, the verified target, and the cloned
            // listing to one triple of evidence. The VM only maps the
            // RefreshOutcome to UiState below -- the VM never snapshots,
            // re-resolves, or re-verifies a second time of its own (the
            // second triple inside refreshVerified is the strict-equality
            // fence), so with observable serial a 95->96 rebuild (or a same-id
            // new serial) can never be spliced into a fictional FRESH pair:
            // whatever is published came from the single pinned target,
            // carried with its true (id, serial, parentId); serial==null proves
            // only same (id,parent) twice + listing, not incarnation (known limit,
            // see repo KDoc). Drift between two refreshes is real change (the
            // new world publishes as a whole on the next refresh, never
            // mixed with the old world's id/serial when serial is observable).
            when (val outcome = onIo { repo.refreshVerified() }) {
                is RefreshOutcome.ChannelError -> {
                    val now = nowWallMs()
                    _state.update { cur ->
                        val (combinedMsg, combinedKind) = combineWithDegradeNote(
                            "channel error: ${sanitizeForDisplay(outcome.detail)}",
                            MsgKind.CHANNEL,
                            cur.degrade,
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
                            diagnose = diagnoseEvent(
                                DiagnoseCode.CHANNEL_ERROR,
                                techDetail = "profiles snapshot channel failed: ${sanitizeForDisplay(outcome.detail).take(200)}",
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
                    // Phase B #1：通道失敗不遷移目標（保留舊身份），但仍跑一次
                    // 綁定檢查（FRESH→STALE 不清勾選；bound 一致即 no-op）。
                    handleSelectionTargetChangeLocked()
                }
                is RefreshOutcome.NoDual -> {
                    val now = nowWallMs()
                    _state.update { cur ->
                        val (combinedMsg, combinedKind) = combineWithDegradeNote(
                            "no dual profile",
                            MsgKind.NO_DUAL,
                            cur.degrade,
                        )
                        val next = cur.copy(
                            loading = false,
                            profiles = outcome.profiles,
                            cloned = emptyList(),
                            // Phase C P2: profiles parsed (readable) but no
                            // dual target -- still true (`pm list users`
                            // succeeded).
                            pmReadable = true,
                            inventory = InventorySnapshot(
                                source = DualRepository.CMD_LIST_USERS,
                                targetId = NO_TARGET_ID,
                                targetSerial = null,
                                queryTimeMs = now,
                                freshness = InventoryFreshness.UNKNOWN,
                                count = 0,
                            ),
                            diagnose = diagnoseEvent(
                                DiagnoseCode.NO_DUAL,
                                techDetail = "no verified dual target",
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
                    // Phase B #1：無目標/未知時亦跑綁定檢查：bound 舊≠新（-1）
                    // 即清空舊勾選（防 95 勾選殘留到舊目標外的空窗）。
                    handleSelectionTargetChangeLocked()
                }
                is RefreshOutcome.Unknown -> {
                    // Fail closed: the single triple could not confirm the
                    // dual identity (conflicting serial/parent, refused
                    // typed channels, pm veto, parent drift, ...). Keep the
                    // old cloned list and the old inventory target (STALE /
                    // UNKNOWN), report CHANNEL + CHANNEL_ERROR, and surface
                    // the typed detail. The write gate is untouched: only a
                    // proven-false pmReadable blocks writes; this branch
                    // neither proves unreadability nor clears the way for
                    // writes on the old list's authority -- writes still
                    // re-verify in the repo.
                    val now = nowWallMs()
                    _state.update { cur ->
                        val (combinedMsg, combinedKind) = combineWithDegradeNote(
                            "refresh result unknown: " +
                                "cannot confirm dual profile",
                            MsgKind.CHANNEL,
                            cur.degrade,
                        )
                        val next = cur.copy(
                            loading = false,
                            profiles = outcome.profiles,
                            // Phase C P2: `pm list users` returned (Loaded)
                            // so readable; only the dual identity is
                            // unverifiable here. Same rule as before: the
                            // UNKNOWN branch never claims pm unreadability
                            // (false) from identity refusal alone.
                            pmReadable = true,
                            inventory = cur.inventory.toStaleOrUnknown(
                                now = now,
                                source = DualRepository.CMD_LIST_USERS,
                                targetId = cur.inventory.targetId,
                            ),
                            diagnose = diagnoseEvent(
                                DiagnoseCode.CHANNEL_ERROR,
                                techDetail = if (outcome.detail.isNotBlank()) {
                                    "verified dual id unknown: " + sanitizeForDisplay(outcome.detail).take(200)
                                } else {
                                    "verified dual id unknown"
                                },
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
                    // Phase B #1：未知時亦跑綁定檢查：保留舊 targetId，
                    // 故 bound 一致即 no-op（不清勾選）。
                    handleSelectionTargetChangeLocked()
                }
                is RefreshOutcome.TargetMoved -> {
                    // Confirmed post-list drift: the re-verify proved a
                    // new target. Keep the old cloned list, publish the
                    // live target as STALE (never FRESH, never the
                    // discarded list), report VERIFY_MISMATCH + INVALID
                    // requiring reconfirmation. Selection: the live
                    // target differs from any bound selection, so
                    // handleSelectionTargetChangeLocked clears it (do NOT
                    // restoreSelectionLocked for the moved target).
                    val live = outcome.liveTarget
                    val now = nowWallMs()
                    _state.update { cur ->
                        val (combinedMsg, combinedKind) = combineWithDegradeNote(
                            "dual target changed: please reconfirm",
                            MsgKind.INVALID,
                            cur.degrade,
                        )
                        val next = cur.copy(
                            loading = false,
                            profiles = outcome.profiles,
                            pmReadable = true,
                            inventory = cur.inventory.toStaleOrUnknown(
                                now = now,
                                source = DualRepository.CMD_LIST_USERS,
                                targetId = live.userId,
                                targetSerial = live.serial,
                            ),
                            diagnose = diagnoseEvent(
                                DiagnoseCode.VERIFY_MISMATCH,
                                techDetail = "post-list target moved: " + sanitizeForDisplay(outcome.detail).take(200),
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
                    handleSelectionTargetChangeLocked()
                }
                is RefreshOutcome.GroundTruth -> {
                    val target = outcome.target
                    val listed = outcome.cloned
                    if (listed == null) {
                        // The list channel failed on the pinned target.
                        // Unknown listing: keep the old cloned list, mark
                        // the pinned target STALE, VERIFY_UNKNOWN.
                        val now = nowWallMs()
                        _state.update { cur ->
                            val (combinedMsg, combinedKind) = combineWithDegradeNote(
                                "refresh result unknown: " +
                                    "cannot list clones for user ${target.userId}",
                                MsgKind.CHANNEL,
                                cur.degrade,
                            )
                            val next = cur.copy(
                                loading = false,
                                profiles = outcome.profiles,
                                // `pm list users` was readable (the snapshot
                                // parsed); only the clone listing is unknown.
                                pmReadable = true,
                                inventory = cur.inventory.toStaleOrUnknown(
                                    now = now,
                                    source = PmOutputParser.buildListPackages(target.userId),
                                    targetId = target.userId,
                                    targetSerial = target.serial,
                                ),
                                diagnose = diagnoseEvent(
                                    DiagnoseCode.VERIFY_UNKNOWN,
                                    techDetail = "cannot list clones for user ${target.userId}",
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
                        handleSelectionTargetChangeLocked()
                        val invNow = _state.value.inventory
                        restoreSelectionLocked(invNow.targetId, invNow.targetSerial)
                    } else {
                        val now = nowWallMs()
                        val isEmpty = listed.isEmpty()
                        _state.update { cur ->
                            // 契約 (Stage C C4，選 (c))：FRESH 成功清碼時同步
                            // 歸檔 FAILED 歷史——diagnose 將清 null (phase !=
                            // UNKNOWN) 且 phase==FAILED 時 lastOperation 同步
                            // 清為 null，三者一致 (diagnose null + 無 FAILED
                            // 歷史 + 就緒 msg)；OK 判據為 diagnose==null &&
                            // lastOperation==null 或 phase != FAILED。
                            // UNKNOWN 仍保留 (需下一次 pinned verify 才覆寫)；
                            // SUCCESS 保留 (成功歷史非錯誤，與 OK 共存)。
                            val (combinedMsg, combinedKind) = if (isEmpty) {
                                combineWithDegradeNote(
                                    "no clones installed",
                                    MsgKind.INFO,
                                    cur.degrade,
                                )
                            } else {
                                combineWithDegradeNote(
                                    "",
                                    MsgKind.NONE,
                                    cur.degrade,
                                )
                            }
                            val clearFailed = cur.lastOperation?.phase == OpPhase.FAILED
                            val next = cur.copy(
                                loading = false,
                                profiles = outcome.profiles,
                                cloned = listed,
                                pmReadable = true,
                                inventory = InventorySnapshot(
                                    source = PmOutputParser.buildListPackages(target.userId),
                                    targetId = target.userId,
                                    targetSerial = target.serial,
                                    queryTimeMs = now,
                                    freshness = InventoryFreshness.FRESH,
                                    count = listed.size,
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
                            if (cur.pendingRemove != null &&
                                (next.msgKind == MsgKind.NONE || next.msgKind == MsgKind.INFO)
                            ) {
                                next.copy(msg = cur.msg, msgKind = cur.msgKind)
                            } else {
                                next
                            }
                        }
                        // Phase B #1：目標變動先清舊勾選（bound 舊≠新即清空＋重置
                        // marker＋INVALID 明示），再由下行對新目標補觸恢復。
                        handleSelectionTargetChangeLocked()
                        // PR-7 P2 (#26):清單 FRESH 且目標已知時,對該目標自動
                        // 恢復一次持久選取 (僅打勾,不重送寫入;已有勾選則跳過)。
                        // 必須在 opMutex 下呼叫 (refreshLocked 已持有)。
                        val invNow = _state.value.inventory
                        restoreSelectionLocked(invNow.targetId, invNow.targetSerial)
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
            // 取消由上方專屬 catch 透傳；此處只處理真正異常。
            // Gateways report via CmdResult, but guard against unexpected throws.
            // pendingRemove 開著時只保留非錯誤提示（NONE/INFO），CHANNEL/FAILED 穿透。
            // PR-6 P2: 舊資料標 STALE，不謊稱新鮮；lastOperation 不動。
            // UNKNOWN 分支回退保留舊身份：傳入已知 target，UI 仍可見上次目標。
            val now = nowWallMs()
            _state.update { cur ->
                val (combinedMsg, combinedKind) = combineWithDegradeNote(
                    "refresh failed: ${sanitizeForDisplay(e.message ?: "refresh failed")}",
                    MsgKind.FAILED,
                    cur.degrade,
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
                        is CloneOutcome.TargetMismatch -> {
                            // 到達不能：單筆路徑走 repo.cloneVerified，永不回傳
                            // TargetMismatch（僅 cloneVerifiedExpected 可產生）。
                            // 穩健起見視為 UNKNOWN（目標漂移、install 未執行，
                            // 需重查而非命令失敗），fail-closed：只設
                            // diagnose+msg，不覆寫 lastOperation（與 NoDual/
                            // ChannelError/InvalidPackage 寫前拒絕一致）。
                            // 理由 (M3)：FRESH 經 toStaleOrUnknown 降為 STALE。
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
                                    msg = "clone verification mismatch: " +
                                        "target ${res.actualId} != ${res.expectedId} " +
                                        "(serial ${res.actualSerial ?: "?"}" +
                                        " != ${res.expectedSerial ?: "?"})",
                                    msgKind = MsgKind.VERIFY,
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
                                            // Stage G P2 (review B)：fresh Confirmed 與 PM 快照衝突
                                            // (快照可用但缺 target id) 是讀取時間點變動/來源不一致，
                                            // 不是「確認無 dual」。只有 typed Absent+快照可用才為真
                                            // NO_DUAL (與 refreshLocked/clone-NoDual 同形)；其餘
                                            // (typed Confirmed/Unknown，含本分支已見 fresh
                                            // Confirmed) 一律 VERIFY_MISMATCH + INVALID，要求重查，
                                            // 零 uninstall。空快照仍為 CHANNEL_ERROR (快照不可信)。
                                            // 理由 (M3)：寫前拒絕時 FRESH 降 STALE，不清 cloned。
                                            val snapshotUsable = snap.profiles.isNotEmpty()
                                            val idResForConflict: DualIdResolution? =
                                                if (snapshotUsable) {
                                                    onIo { repo.resolveDualUserIdTyped(pmIds = snap.profiles.map { it.userId }.toSet()) }
                                                } else {
                                                    null
                                                }
                                            val confirmedAbsent =
                                                idResForConflict is DualIdResolution.Absent && snapshotUsable
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
                                                } else if (confirmedAbsent) {
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
                                                } else {
                                                    cur.copy(
                                                        loading = false,
                                                        profiles = snap.profiles,
                                                        pendingRemove = null,
                                                        inventory = degraded,
                                                        diagnose = diagnoseEvent(
                                                            DiagnoseCode.VERIFY_MISMATCH,
                                                            techDetail = "verified id " +
                                                                "$dualId absent from profiles " +
                                                                "snapshot",
                                                        ),
                                                        msg = "移除目標已變更（verified id " +
                                                            "$dualId 與快照不一致），請重新確認",
                                                        msgKind = MsgKind.INVALID,
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
        _state.value.pendingRemove?.let { cancelRemove(it) }
    }

    fun cancelRemove(expected: PendingRemove) {
        _state.update { cur ->
            val pending = cur.pendingRemove ?: return@update cur
            if (pending != expected) return@update cur
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
        val pending = _state.value.pendingRemove
        if (pending != null) confirmRemove(pending) else {
            _state.update { cur ->
                if (cur.pendingRemove != null || (cur.msgKind == MsgKind.INFO && cur.msg.startsWith("removed: "))) cur
                else cur.copy(msg = "沒有待確認的移除", msgKind = MsgKind.INVALID)
            }
        }
    }

    fun confirmRemove(expected: PendingRemove) {
        viewModelScope.launch {
            val pendingAtCall = expected
            if (_state.value.pendingRemove != pendingAtCall) return@launch
            // Write dedup 按世代 (PR-4 P23 stage 2, PR-33 R2 加 serial): key 綁定
            // pending 世代 (pkg@dualId#serial#requestedAtMs#generation)，避免 v1 卡鎖時 v2
            // 合法確認被誤殺；a second concurrent confirm of the SAME
            // generation reports instead of queueing a second uninstall.
            val key = "remove:${pendingAtCall.pkg}@${pendingAtCall.dualId}" +
                "#${pendingAtCall.dualSerial}#${pendingAtCall.requestedAtMs}#${pendingAtCall.generation}"
            if (!inFlightWrites.add(key)) {
                _state.update {
                    if (it.pendingRemove != pendingAtCall) return@update it
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
                    // abort，保留新 pending 與所有狀態，由使用者確認新目標，
                    // 與 post-IO 一致（preserve 式）。
                    if (cur != pendingAtCall) {
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
                                    // Stage G P2 (review B)：二次快照 label 缺席但 fresh
                                    // target 仍 Confirmed (首段 typed 已 Confirmed(cur.dualId)
                                    // 且第二段 fetchDualTarget 亦 Confirmed 同 id+serial，
                                    // 見上)——是讀取時間點變動/來源不一致，不是確認無
                                    // dual。僅 typed Absent+快照可用才為真 NO_DUAL；
                                    // 此前兩次驗證皆 Confirmed、未觀測到 Absent，故不得
                                    // 逕報 NO_DUAL (此處省掉第三次 typed 重查：已有兩次
                                    // Confirmed 背書，省 IO；結論同為保守拒絕)。
                                    // 一律 VERIFY_MISMATCH + INVALID，要求重查，
                                    // 零 uninstall。空快照仍為 CHANNEL_ERROR (快照不可信)。
                                    // 理由 (M3)：寫前拒絕時 FRESH 降 STALE，不清 cloned。
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
                                                    DiagnoseCode.VERIFY_MISMATCH,
                                                    techDetail = "verified id " +
                                                        "${cur.dualId} absent from profiles " +
                                                        "snapshot",
                                                ),
                                                msg = "移除目標已變更（verified id " +
                                                    "${cur.dualId} 與快照不一致），請重新確認",
                                                msgKind = MsgKind.INVALID,
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
                        // 非 OP_FAILED).
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

    // PR-7 P2 (issue #26, 階段 1):來源 catalog 與選取操作。
    //
    // - setSourceCatalog:MainActivity 載入 loadSourceApps 後注入 (全量替換)。
    // - setSourceQuery/setIncludeSystemApps:搜尋字串與系統 App 開關 (純狀態)。
    // - toggleSelection/setSelected/selectVisible:勾選變更,只改 selectedKeys,
    //   永不觸及裝置寫入路徑 (取消勾選 ≠ 卸載,由 InventorySelectionTest 鎖定
    //   零 gateway 呼叫)。
    // - clearSelectionLocal:清空記憶體勾選 (≠卸載);要連持久化一起丟用
    //   cancelSavedSelection。
    fun setSourceCatalog(entries: List<SourceAppEntry>) {
        // m3：空 catalog 亦正常寫入 state（UI 顯示「尚未載入」），不吞掉；
        // 但空時不觸發恢復（守衛，配合 M4 marker 語義）。
        _state.update { it.copy(sourceCatalog = entries.toList()) }
        // catalog 後載補觸恢復：refresh 可能先跑在空 catalog 上（不置 marker，
        // 見 restoreSelectionLocked），此處 catalog 就緒後對已知目標補試一次。
        // Phase B #1：inventory 未驗證（targetId<=0）時絕不以 snapshot 的
        // knownTargetId 充當恢復目標（防 95 存檔寫到 96）：直接跳過自動恢復，
        // 不燒 marker，待驗證後由 refresh 路徑補觸。
        if (entries.isEmpty()) return
        if (_state.value.selectedKeys.isNotEmpty()) return
        val inv = _state.value.inventory
        if (inv.targetId <= NO_TARGET_ID) return
        // PR-37 Phase B #2：補觸恢復攜帶當下 verified (id, serial) 配對。
        val target = SelectionTarget(inv.targetId, inv.targetSerial)
        if (selectionRestoredForTarget == target) return
        viewModelScope.launch {
            opMutex.withLock { restoreSelectionLocked(target.id, target.serial) }
        }
    }

    fun setSourceQuery(query: String) {
        _state.update { it.copy(sourceQuery = query) }
    }

    fun setIncludeSystemApps(include: Boolean) {
        _state.update { it.copy(includeSystemApps = include) }
    }

    fun toggleSelection(appKey: String) {
        var changed = false
        _state.update { cur ->
            val next = cur.selectedKeys.toMutableSet()
            if (!next.add(appKey)) next.remove(appKey)
            // 勾選變更即清 stale 批次報告（報告只描述上次執行的勾選集合）。
            if (next == cur.selectedKeys) cur
            else {
                changed = true
                cur.copy(selectedKeys = next, batchReport = null)
            }
        }
        if (changed) selectionGen.incrementAndGet()
        // Phase B #1：已驗證目標下修改勾選即綁定；未驗證時不綁（哨兵，待 refresh 領養）。
        bindSelectionToVerifiedTargetLocked()
    }

    fun setSelected(appKey: String, checked: Boolean) {
        var changed = false
        _state.update { cur ->
            val next = cur.selectedKeys.toMutableSet()
            if (checked) next.add(appKey) else next.remove(appKey)
            // 同上：無實際變更不碰報告，避免重渲染抖動。
            if (next == cur.selectedKeys) cur
            else {
                changed = true
                cur.copy(selectedKeys = next, batchReport = null)
            }
        }
        if (changed) selectionGen.incrementAndGet()
        bindSelectionToVerifiedTargetLocked()
    }

    fun selectVisible(appKeys: Set<String>) {
        if (appKeys.isEmpty()) return
        var changed = false
        _state.update { cur ->
            val merged = cur.selectedKeys + appKeys
            if (merged == cur.selectedKeys) cur
            else {
                changed = true
                cur.copy(selectedKeys = merged, batchReport = null)
            }
        }
        if (changed) selectionGen.incrementAndGet()
        bindSelectionToVerifiedTargetLocked()
    }

    // 清空勾選 (記憶體):只改 selectedKeys,不跑任何 uninstall,不碰持久化。
    // 語意:「取消勾選」≠「卸載分身」。
    fun clearSelectionLocal() {
        var changed = false
        _state.update { cur ->
            if (cur.selectedKeys.isEmpty()) return@update cur
            changed = true
            cur.copy(
                selectedKeys = emptySet(),
                batchReport = null,
                msg = "已清除勾選（未卸載任何分身）",
                msgKind = MsgKind.INFO,
            )
        }
        if (changed) selectionGen.incrementAndGet()
        // Phase B #1：清空即解綁（空集合無歸屬）。
        selectionBoundTarget = SELECTION_BOUND_NONE
    }

    // 保存當前勾選到 SelectionStore (需明確已知目標,不自行解析)。
    // 只保存勾選,不寫入任何分身;targetId <= 0 或無 store 時拒絕並說明。
    // 保存內容為 AppKey 集合 ("serial:package",見 sourceAppKey)：只存仍在
    // 當前 catalog 的 key；catalog 已缺失的 key 直接捨去 (批次亦無法執行，
    // 見 cloneSelected 的 InvalidPackage 分流，計入 failed 非 unknown)。
    // M9 stale-target 守衛：在 opMutex 內重讀 inventory (id, serial)，已知目標
    // (>0) 與傳入不一致即拒絕 (MsgKind.INVALID，明示目標已變更)，不寫入 store。
    // PR-37 Phase B #2：守衛比對 (id,serial) 配對——呼叫端可另傳 targetSerial
    //（為空即沿用當下 verified serial）；寫入的 serial 一律取自當下已驗證
    // inventory（權威來源，非呼叫端參數），serial 未知時寫 legacy 形狀。
    // Stage5 新鮮度閘：僅 FRESH 允許保存；STALE 拒絕並提示重查 (舊資料不謊稱
    // 新鮮，寫入前必須先 refresh 重驗)。UNKNOWN 允許——persist 為顯式使用者
    // 動作 (使用者主動按保存＋顯式傳入 targetId，非依賴 inventory 自動行為)，
    // 未驗證時按顯式目標寫 legacy/id-only，不以未知身份冒充已驗證；STALE 則為
    // 已知過期，必須拒絕 (fail closed)。
    // Stage5 不降級：known.targetSerial==null (SerialUnavailable/UNKNOWN) 時
    // 若既有快照為 v2，拒絕以 legacy 覆寫 (防降級丟失 serial 綁定)。
    fun persistSelection(
        targetId: Int,
        source: String = SELECTION_SOURCE_CATALOG,
        targetSerial: Int? = null,
    ) {
        viewModelScope.launch {
            opMutex.withLock {
                val store = selectionStore
                if (store == null) {
                    _state.update {
                        it.copy(msg = "無選取 store,無法保存", msgKind = MsgKind.CHANNEL)
                    }
                    return@withLock
                }
                if (targetId <= 0) {
                    _state.update {
                        it.copy(msg = "no dual profile", msgKind = MsgKind.NO_DUAL)
                    }
                    return@withLock
                }
                val known = _state.value.inventory
                // Stage5：STALE 即拒絕 (提示重查)，UNKNOWN 顯式動作放行 (見上)。
                if (known.freshness == InventoryFreshness.STALE) {
                    _state.update {
                        it.copy(
                            msg = "清單已過期（STALE），請重新查詢後再保存",
                            msgKind = MsgKind.INVALID,
                        )
                    }
                    return@withLock
                }
                if (known.targetId > 0 && known.targetId != targetId) {
                    _state.update {
                        it.copy(
                            msg = "選取目標已變更（user ${known.targetId} ≠ $targetId），" +
                                "請重新確認後再保存",
                            msgKind = MsgKind.INVALID,
                        )
                    }
                    return@withLock
                }
                val wantSerial = targetSerial ?: known.targetSerial
                if (wantSerial != null && known.targetSerial != null &&
                    known.targetSerial != wantSerial
                ) {
                    _state.update {
                        it.copy(
                            msg = "選取目標已變更（serial ${known.targetSerial} ≠ $wantSerial），" +
                                "請重新確認後再保存",
                            msgKind = MsgKind.INVALID,
                        )
                    }
                    return@withLock
                }
                // Stage5：不可驗證時不降級覆寫既有 v2 (同 mutex 下單次快照讀取)。
                if (known.targetSerial == null) {
                    val existing = try {
                        onIo { store.loadSnapshot() }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    if (existing != null && existing.knownTargetSerial != null) {
                        _state.update {
                            it.copy(
                                msg = "無法驗證分身身分（serial 缺失），" +
                                    "為保護既有選取已拒絕覆寫，請重查後再保存",
                                msgKind = MsgKind.INVALID,
                            )
                        }
                        return@withLock
                    }
                }
                val catalogKeys = _state.value.sourceCatalog.map { it.appKey }.toSet()
                val selected = _state.value.selectedKeys
                    .filterTo(mutableSetOf()) { it in catalogKeys }
                    .toSortedSet()
                val ok = try {
                    onIo { store.save(source, targetId, selected, nowWallMs(), known.targetSerial) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    false
                }
                _state.update {
                    if (ok) {
                        it.copy(
                            msg = "已保存選取 ${selected.size} 項（user $targetId," +
                                "重啟可恢復；只保存勾選，不寫入分身）",
                            msgKind = MsgKind.INFO,
                        )
                    } else {
                        it.copy(msg = "保存選取失敗", msgKind = MsgKind.FAILED)
                    }
                }
            }
        }
    }

    // 取消保存:只刪持久化條目,不跑任何 uninstall,不清記憶體勾選
    // (語意:「取消保存」≠「卸載分身」;記憶體勾選另由 clearSelectionLocal 清)。
    fun cancelSavedSelection() {
        viewModelScope.launch {
            opMutex.withLock {
                val store = selectionStore
                if (store == null) {
                    _state.update {
                        it.copy(msg = "無選取 store,無法取消保存", msgKind = MsgKind.CHANNEL)
                    }
                    return@withLock
                }
                val ok = try {
                    onIo { store.clear() }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    false
                }
                _state.update {
                    if (ok) {
                        it.copy(
                            msg = "已取消保存選取（不影響已安裝分身與目前勾選）",
                            msgKind = MsgKind.INFO,
                        )
                    } else {
                        it.copy(msg = "取消保存失敗", msgKind = MsgKind.FAILED)
                    }
                }
            }
        }
    }

    // Stage2 (hook-policy-source P1, 階段 2):白名單政策草稿 (讀/寫/清)。
    //
    // - 草稿內容為包名集合 (plain package names)，由當下已勾選 (selectedKeys,
    //   AppKey "serial:package") 映射：仍在當前 catalog 的 key 取包名，去 serial
    //   前綴；catalog 缺失的 key 直接捨去 (與 persistSelection 同口徑)。
    // - 勾選≠安裝，寫草稿≠安裝：本組方法只碰 HookPolicyStore (LSPosed
    //   remote preferences group dual_policy)，永不呼叫 repo/寫入通道；與
    //   cloneSelected 完全分離。
    // - 預設空，不自動寫入：toggle/selectVisible/clear 等勾選變更永不觸碰本
    //   store；只有 confirmPolicyWrite (二次確認後) 才存。
    // - 兩階段寫入沿用 remove ack 模式：request 建世代快照 -> dialog 勾選確認 ->
    //   confirm 世代全等＋TTL 內才寫；cancel 只清 pending，永不寫入。
    // - 寫入政策不經特權通道 (app 經 LSPosed remote preferences 落盤)，故
    //   confirm 不設 isWritable 門控；store 為 null 即拒絕並說明。

    // 已勾選映射出政策包名 (純狀態讀取)：空勾選回空；catalog 缺失 key 捨去。
    // 以完整 AppKey 比對來源清單 (P2 #7)，避免同包名不同 serial 塌陷；
    // legacy 無冒號 key（舊版包名格式）走同一 byPackageFirst 映射（與
    // restoreSelectionLocked 一致：同包多來源取 catalog 首個，舊資料永不硬寫回
    // AppKey）。刻意與 restore 一致而非更嚴：寫入映射與恢復映射同源，同一舊
    // key 在兩處不會一收一捨。
    // 回傳 (包名集合, 因不在當前 catalog 而捨去的勾選數)；比對整 AppKey
    // (serial:package) 而非僅包名，避免跨 serial 的同名包被誤收或誤捨。
    // 空 catalog 由呼叫端 requestPolicyWrite 先行守衛（「來源清單尚未載入」），
    // 本函數不另設空分支（空 catalog 下全捨去，dropped＝選取數，語意不變）。
    // Phase 5 polish：文法前置檢查（PolicySource.isValidPackage，與 hook 側
    // sanitize 同文法 [a-zA-Z0-9._-]+）：catalog 內理論皆合法（PM 包名為該
    // 文法的子集：PM 另要求至少一個 '.' 且首字為字母，見 PackageManager 文檔；
    // 此處以前置寬鬆文法過濾顯示/寫入快照，真正消毒仍由 confirm sanitizeHookPolicy
    // 兜底），非法 token 計入 dropped（與 catalog 缺失同口徑），不連坐整包。
    private fun selectedPackagesForPolicy(): Pair<Set<String>, Int> {
        val catalog = _state.value.sourceCatalog
        val byKey = catalog.associateBy { it.appKey }
        val byPackageFirst = catalog.groupBy { it.packageName }
            .mapValues { (_, v) -> v.first() }
        var dropped = 0
        val pkgs = _state.value.selectedKeys.mapNotNull { key ->
            val entry = if (":" in key) {
                byKey[key]
            } else {
                byPackageFirst[key.trim()]
            }
            if (entry == null) { dropped++; return@mapNotNull null }
            val pkg = entry.packageName.takeIf { it.isNotEmpty() }
                ?: run { dropped++; return@mapNotNull null }
            // 文法前置檢查：非法包名計入 dropped（顯示數與寫入數一致，見 request）。
            if (!PolicySource.isValidPackage(pkg)) { dropped++; return@mapNotNull null }
            pkg
        }.toSet()
        return pkgs to dropped
    }

    // Phase 1 fail-closed generation fence：寫入失敗導致的 Unknown 必須作廢
    // 在途 load（late Known 不得蓋掉 Unknown）。
    //
    // 用法：所有將 policyDraftKnown 翻為 false 的寫入路徑（confirm-fail、
    // clear-fail、confirm/clear null-store、unknown-gate 拒絕重申）在同一次
    // _state.update（只翻 known、不清 draft）之前先呼叫此函數
    // bump policyDraftVersion + policyFreshnessGen + policyOwnershipGen。
    // bump 必須在 update 之前，
    // 使晚到的 load 因 versionAtStart/freshness 失配被丟棄；晚到的 reconciler 因
    // versionAtStart/ownership 失配被丟棄（Stage G1 拆分：顯示 vs 所有權）。
    //
    // 不變量：本函數與其後的 _state.update 之間不得插入 suspend，否則 fence
    // 失效（bump 與翻 Unknown 必須背靠背發生；一旦讓出，併發 load 可能插隊
    // 取號，使其晚到結果反被採納）。
    //
    // 區分（刻意不對稱）：
    // - 寫入失敗導致的 Unknown → 必須 bump（本函數）：失敗是新事實，舊 load 的
    //   Known 已過期，留之即把 Unknown 蓋回 Known（Phase 1 缺口）。
    // - load 結果本身的 Unknown（init 冷啟動 / refreshPolicyDraft 的 Unknown
    //   分支）→ 不得呼叫本函數：Unknown 只是本次讀值，bump 會連帶丟棄併發的
    //   新 load（同為讀值、無新舊之分），破壞現有 overlapping-load 語意
    //  （見 refreshPolicyDraft_overlappingLoads 世代守衛測試）。
    // - TTL 過期 → 不得呼叫本函數：TTL 只清 pending、不翻 known/draft，無新事實
    //   需作廢舊 load；refresh/init 的 update 永不碰 pending，load 不可能復活
    //   pending；bump 會無辜丟棄併發的正常 refresh。
    // - null-store bump 刻意保留以求統一、終態不變（null-store 下 load 恆為
    //   Unknown，本就無 Known 可 fence；bump 只為統一寫入路徑語意）。
    private fun markPolicyUnknownLocked() {
        bumpPolicyGenerations()
    }

    // Stage H2 P1: winner triple bump (order contract for claim->bump->update->emit
    // ordering, not a single helper). Every winner terminal (Success/Mismatch Known,
    // Failed Unknown via mark) advances version+freshness+ownership synchronously
    // after the claim win and BEFORE the terminal _state.update, so an in-flight
    // init/refresh load sampling before the bump observes a generation mismatch
    // and discards instead of washing Known=false (which stranded IDLE+Unknown
    // with a SUCCESS msg). Callers must bump BEFORE update and emit via the
    // non-bumping path (emitTxSettled / snapshot without bump); the old snapshot
    // Known-conditional bump is removed to avoid double-bump.
    // Exemptions (same order, inline, not via winnerPublish): reconciler and
    // refresh late-join inline pre-bumps (see their bumpPolicyGenerations sites).
    private fun bumpPolicyGenerations() {
        policyDraftVersion.incrementAndGet()
        policyFreshnessGen.incrementAndGet()
        policyOwnershipGen.incrementAndGet()
    }

    // Stage H2 P1: winner publish helper enforcing claim->bump->update->emit->
    // settle->replay (order-contract helper for the terminal order; fast-path
    // autoRecheck winners only). bump=true advances the version triple BEFORE
    // applyTerminal (Known terminals); bump=false skips the helper bump because
    // applyTerminal already bumps via markPolicyUnknownLocked (Failed Unknown
    // terminals). applyTerminal must contain the terminal _state.update
    // (+disarms); emit uses the non-bumping path so the pre-bump is never
    // doubled. Reconciler/late-join inline paths follow the same order without
    // this helper (see their inline pre-bump + non-bumping emit sites).
    private suspend fun winnerPublish(
        tx: PolicyTx,
        payload: PolicySettled,
        bump: Boolean,
        applyTerminal: () -> Unit,
    ) {
        val stamped = try {
            emitTxSettledStageOnly(tx, payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        synchronized(policySettleLock) {
            val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
            val wonLocal = (curLocal === tx) && activePolicyTx.compareAndSet(tx, null)
            try {
                policyReconciling.remove(tx.key)
            } catch (_: Exception) {
            }
            if (wonLocal) {
                if (policyTxCoordinator != null && stamped == null) {
                    applyStageRejectedUnderLock(tx, fenceHeld = true)
                } else {
                    if (bump) bumpPolicyGenerations()
                    try {
                        applyTerminal()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (stamped != null) {
            try {
                policyTxCoordinator?.emitStaged(stamped)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        try {
            replayWinnerTerminalIfStranded(tx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun policyDraftVersionForTests(): Long = try {
        policyDraftVersion.get()
    } catch (_: Exception) {
        -1L
    }

    // Stage A：核對中守衛（phase OR 集合雙層，防 state/集合瞬時窗口漂移）。
    // request/cancel/clear/confirm 入口共用：核對中一律拒絕新寫入（BUSY/進行中），
    // cancel 靜默 no-op（pending 保留，沿用 SAVING 語義）。
    // Stage F1：改讀 Tx（activePolicyTx!=null OR phase OR 集合三層，防 Tx 登記與
    // state 翻轉間的瞬時窗口漂移；Tx 為唯一真相，phase/集合為可觀測投影）。
    // Stage G2：跨 VM 加讀 coordinator（新 VM 認領前本地仍空，舊 B 落盤前試 C
    // 必須拒絕；認領後本地亦設，雙層）。
    private fun isPolicyReconcilingLocked(): Boolean = synchronized(policySettleLock) {
        activePolicyTx.get() != null ||
            policyTxCoordinator?.activeTx?.get() != null ||
            _state.value.policyWritePhase == PolicyWritePhase.RECONCILING ||
            policyReconciling.isNotEmpty()
    }

    // Stage G2：跨 VM Tx 存活判定（本地 OR coordinator；全域 refresh／select
    // 入口併發丟棄用，不碰 phase/集合）。
    private fun hasActivePolicyTx(): Boolean = synchronized(policySettleLock) {
        activePolicyTx.get() != null || policyTxCoordinator?.activeTx?.get() != null
    }

    private suspend fun settleReconcilerTerminal(
        tx: PolicyTx,
        payload: PolicySettled,
        applyUnderLock: () -> Unit,
    ) {
        val stamped = try {
            emitTxSettledStageOnly(tx, payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        synchronized(policySettleLock) {
            val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
            val wonLocal = (curLocal === tx) ||
                (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)
            if (curLocal === tx) {
                try { activePolicyTx.compareAndSet(tx, null) } catch (_: Exception) { }
            }
            try { policyReconciling.remove(tx.key) } catch (_: Exception) { }
            if (wonLocal) {
                if (policyTxCoordinator != null && stamped == null) {
                    applyStageRejectedUnderLock(tx, fenceHeld = (curLocal === tx))
                } else {
                    try {
                        applyUnderLock()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
            // The repair outcome rides the shared Tx (tx.repairResult); it is
            // consumed under the lock by applyUnderLock and dies with the Tx —
            // no per-VM purge on settle.
        }
        if (stamped != null) {
            try {
                policyTxCoordinator?.emitStaged(stamped)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        replayWinnerTerminalIfStranded(tx)
    }

    // Stage A：confirm 逾時後的背景核對器（獨立 job，不持 opMutex/mutex 已釋放）。
    // ...
    private fun launchConfirmReconciler(tx: PolicyTx) {
        val key = tx.key
        val store = tx.store
        val sanitized = tx.sanitized
        viewModelScope.launch {
            // Stage N2 P1: unvalidated (PREPARING) Tx must never be joined —
            // it was cancelled before final validation/arm. Abort without any
            // store touch (cancel unstarted LAZY so it can never start) and
            // require fresh validation; do NOT keep the fence.
            if (!canJoinTx(tx)) {
                abortUnarmedTx(tx)
                return@launch
            }
            val versionAtStart = policyDraftVersion.get()
            val ownership = policyOwnershipGen.incrementAndGet()
            // settle=false＝誠實 unresolved：保留 fence（late-join 可見）＋pending
            // 保留＋phase 續留 RECONCILING。外部取消（CE 重拋）仍經 settle=true
            // 清場，不殘留。
            var settle = true
            try {
                try {
                    withTimeout(POLICY_RECONCILE_TOTAL_BUDGET_MS) {
                        // F1 先 join：已證失敗即短路 FAILED+Unknown（不讀）。
                        val join: Boolean? = try {
                            joinPolicyTxBounded(tx)
                        } catch (e: CancellationException) {
                            throw e
                        }
                        if (join == false) {
                            // Stage L1 P1: staged claim (placeholder) — atomic completeWithRevision
                            // under commitMutex, hook after CAS before emit.
                            if (!claimForPublish(tx)) {
                                settle = false
                                return@withTimeout
                            }
                            // Stage J2: 已證失敗亦須一次 ground-truth 讀（store 圍欄保證
                            // 毒讀為 Unknown，故 Known==sanitized 必為真值，如併發他寫同值）。
                            // Known==sanitized 即 Success，否則 Failed；永不零讀發布 Failed。
                            val groundRead: LoadResult = try {
                                val (gr, _) = boundedReconcileRead(store)
                                gr
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                LoadResult.Unavailable
                            }
                            val groundDecision: PolicyTxSettle = try {
                                decidePolicyTxSettle(tx, groundRead)
                            } catch (e: CancellationException) {
                                throw e
                            }
                            val staleJoin = policyDraftVersion.get() != versionAtStart ||
                                policyOwnershipGen.get() != ownership
                            val snapStateJoin = _state.value
                            val payloadJoin = if (staleJoin) {
                                PolicySettled(
                                    tx.txId, tx.key, snapStateJoin.policyDraft, snapStateJoin.policyDraftKnown,
                                    MsgKind.INFO, policyText(PolicyText(PolicyTextKind.REFRESHED)), false,
                                )
                            } else if (groundDecision is PolicyTxSettle.Success) {
                                PolicySettled(
                                    tx.txId, tx.key, groundDecision.packages, true,
                                    MsgKind.INFO, writeSuccessMsg(groundDecision.packages.size, tx.pending, tx.sanitized), false,
                                )
                            } else {
                                PolicySettled(
                                    tx.txId, tx.key, snapStateJoin.policyDraft, false,
                                    MsgKind.FAILED, policyText(PolicyText(PolicyTextKind.WRITE_FAILED)), false,
                                )
                            }
                            settleReconcilerTerminal(tx, payloadJoin) {
                                if (policyDraftVersion.get() != versionAtStart ||
                                    policyOwnershipGen.get() != ownership
                                ) {
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                } else if (groundDecision is PolicyTxSettle.Success) {
                                    bumpPolicyGenerations()
                                    disarmBlindClear()
                                    try { noteLastKnownDraft(groundDecision.packages) } catch (_: Exception) { }
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraft = groundDecision.packages,
                                            policyDraftKnown = true,
                                            policyDraftCorrupt = false,
                                            msg = writeSuccessMsg(groundDecision.packages.size, tx.pending, tx.sanitized),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                } else {
                                    markPolicyUnknownLocked()
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = false,
                                            msg = policyText(PolicyText(PolicyTextKind.WRITE_FAILED)),
                                            msgKind = MsgKind.FAILED,
                                        )
                                    }
                                }
                            }
                            settle = false
                            return@withTimeout
                        }
                        var result: LoadResult = LoadResult.Unavailable
                        var hadReadTimeout = false
                        delay(POLICY_RECONCILE_INITIAL_DELAY_MS)
                        for (i in 0 until POLICY_RECONCILE_RETRIES) {
                            val (r, timedOut) = boundedReconcileRead(store)
                            if (timedOut) hadReadTimeout = true
                            result = r
                            if (r is LoadResult.Known) {
                                if (!tx.deferred.isCompleted) {
                                    if (i < POLICY_RECONCILE_RETRIES - 1) {
                                        delay(POLICY_RECONCILE_RETRY_DELAY_MS)
                                    }
                                    continue
                                }
                                break
                            }
                            if (i < POLICY_RECONCILE_RETRIES - 1) {
                                delay(POLICY_RECONCILE_RETRY_DELAY_MS)
                            }
                        }
                        val finalResult = result
                        val lateProvenFailed = if (finalResult is LoadResult.Unknown &&
                            (hadReadTimeout || policyTxCoordinator != null) &&
                            canJoinTx(tx) && tx.deferred.isCompleted
                        ) {
                            try {
                                !tx.deferred.await()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                true
                            }
                        } else {
                            false
                        }
                        if (lateProvenFailed &&
                            policyDraftVersion.get() == versionAtStart &&
                            policyOwnershipGen.get() == ownership
                        ) {
                            if (!claimForPublish(tx)) {
                                settle = false
                                return@withTimeout
                            }
                            val failedPayload = PolicySettled(
                                tx.txId, tx.key, _state.value.policyDraft, false,
                                MsgKind.FAILED, policyText(PolicyText(PolicyTextKind.WRITE_FAILED)), false,
                            )
                            settleReconcilerTerminal(tx, failedPayload) {
                                if (policyDraftVersion.get() != versionAtStart ||
                                    policyOwnershipGen.get() != ownership
                                ) {
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                } else {
                                    markPolicyUnknownLocked()
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = false,
                                            msg = policyText(PolicyText(PolicyTextKind.WRITE_FAILED)),
                                            msgKind = MsgKind.FAILED,
                                        )
                                    }
                                }
                            }
                            settle = false
                        } else {
                            val settleDecision: PolicyTxSettle? = if (finalResult is LoadResult.Known) {
                                try {
                                    decidePolicyTxSettle(tx, finalResult)
                                } catch (e: CancellationException) {
                                    throw e
                                }
                            } else {
                                null
                            }
                            val staleOutside = policyDraftVersion.get() != versionAtStart ||
                                policyOwnershipGen.get() != ownership
                            val keepActiveUnknown = finalResult is LoadResult.Unknown &&
                                policyTxCoordinator != null &&
                                (!tx.deferred.isCompleted || !lateProvenFailed)
                            val unresolvedOutside = !staleOutside && (
                                settleDecision is PolicyTxSettle.Unresolved ||
                                    (finalResult is LoadResult.Unknown && hadReadTimeout) ||
                                    keepActiveUnknown
                                )
                            if (unresolvedOutside) {
                                synchronized(policySettleLock) {
                                    val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                                    if (curLocal === tx || (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                                        _state.update { cur ->
                                            if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                                            else cur.copy(
                                                policyWritePhase = PolicyWritePhase.RECONCILING,
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = tx.isRepair || (finalResult is LoadResult.Corrupt),
                                                msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        }
                                    }
                                }
                                settle = false
                            } else {
                                if (!claimForPublish(tx)) {
                                    settle = false
                                    return@withTimeout
                                }
                                val snapState = _state.value
                                val terminalPayload = if (staleOutside) {
                                    PolicySettled(
                                        tx.txId, tx.key, snapState.policyDraft, snapState.policyDraftKnown,
                                        MsgKind.INFO, policyText(PolicyText(PolicyTextKind.REFRESHED)), false,
                                    )
                                } else if (finalResult is LoadResult.Known) {
                                    when (settleDecision) {
                                        is PolicyTxSettle.Success -> PolicySettled(
                                            tx.txId, tx.key, finalResult.packages, true,
                                            MsgKind.INFO, writeSuccessMsg(finalResult.packages.size, tx.pending, tx.sanitized), false,
                                        )
                                        is PolicyTxSettle.Mismatch -> PolicySettled(
                                            tx.txId, tx.key, finalResult.packages, true,
                                            MsgKind.INFO, policyText(PolicyText(PolicyTextKind.REFRESHED)), false,
                                        )
                                        else -> PolicySettled(
                                            tx.txId, tx.key, snapState.policyDraft, false,
                                            MsgKind.FAILED, policyText(PolicyText(PolicyTextKind.WRITE_FAILED)), false,
                                        )
                                    }
                                } else {
                                    PolicySettled(
                                        tx.txId, tx.key, snapState.policyDraft, false,
                                        MsgKind.CHANNEL, policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)), false,
                                    )
                                }
                                settleReconcilerTerminal(tx, terminalPayload) {
                                    if (policyDraftVersion.get() != versionAtStart ||
                                        policyOwnershipGen.get() != ownership
                                    ) {
                                        _state.update { cur ->
                                            cur.copy(
                                                pendingPolicyWrite = null,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                                msgKind = MsgKind.INFO,
                                            )
                                        }
                                    } else if (finalResult is LoadResult.Known) {
                                        when (settleDecision) {
                                            is PolicyTxSettle.Success -> {
                                                bumpPolicyGenerations()
                                                disarmBlindClear()
                                                try { noteLastKnownDraft(finalResult.packages) } catch (_: Exception) { }
                                                _state.update { cur ->
                                                    cur.copy(
                                                        pendingPolicyWrite = null,
                                                        policyWritePhase = PolicyWritePhase.IDLE,
                                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                        policyDraft = finalResult.packages,
                                                        policyDraftKnown = true,
                                                        policyDraftCorrupt = false,
                                                        msg = writeSuccessMsg(finalResult.packages.size, tx.pending, tx.sanitized),
                                                        msgKind = MsgKind.INFO,
                                                    )
                                                }
                                            }
                                            is PolicyTxSettle.Mismatch -> {
                                                bumpPolicyGenerations()
                                                disarmBlindClear()
                                                try { noteLastKnownDraft(finalResult.packages) } catch (_: Exception) { }
                                                _state.update { cur ->
                                                    cur.copy(
                                                        pendingPolicyWrite = null,
                                                        policyWritePhase = PolicyWritePhase.IDLE,
                                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                        policyDraft = finalResult.packages,
                                                        policyDraftKnown = true,
                                                        policyDraftCorrupt = false,
                                                        msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                                        msgKind = MsgKind.INFO,
                                                    )
                                                }
                                            }
                                            is PolicyTxSettle.Failed -> {
                                                markPolicyUnknownLocked()
                                                _state.update { cur ->
                                                    cur.copy(
                                                        pendingPolicyWrite = null,
                                                        policyWritePhase = PolicyWritePhase.IDLE,
                                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                        policyDraftKnown = false,
                                                        policyDraftCorrupt = false,
                                                        msg = policyText(PolicyText(PolicyTextKind.WRITE_FAILED)),
                                                        msgKind = MsgKind.FAILED,
                                                    )
                                                }
                                            }
                                            else -> {
                                                // Unreachable here (handled in unresolvedOutside).
                                            }
                                        }
                                    } else {
                                        _state.update { cur ->
                                            cur.copy(
                                                pendingPolicyWrite = null,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = tx.isRepair || (finalResult is LoadResult.Corrupt),
                                                msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        }
                                    }
                                }
                                settle = false
                            }
                        }
                    }
                } catch (te: TimeoutCancellationException) {
                    val staleOutside = policyDraftVersion.get() != versionAtStart ||
                        policyOwnershipGen.get() != ownership
                    if (staleOutside) {
                        try {
                            val coordO = policyTxCoordinator
                            if (coordO != null) {
                                coordO.discardOverrunPlaceholder(tx.txId, tx.key)
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                        }
                        try {
                            policyTxCoordinator?.tryCompleteClaim(tx)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                        }
                    }
                    synchronized(policySettleLock) {
                        if (policyDraftVersion.get() != versionAtStart ||
                            policyOwnershipGen.get() != ownership
                        ) {
                            val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                            if (curLocal === tx) activePolicyTx.compareAndSet(tx, null)
                            policyReconciling.remove(tx.key)
                            if (curLocal === tx || (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                                _state.update { cur ->
                                    cur.copy(
                                        pendingPolicyWrite = null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                        msgKind = MsgKind.INFO,
                                    )
                                }
                            }
                        } else {
                            val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                            if (curLocal === tx || (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                                _state.update { cur ->
                                    if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                                    else cur.copy(
                                        policyWritePhase = PolicyWritePhase.RECONCILING,
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = tx.isRepair,
                                        msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                    }
                    settle = false
                }
            } catch (e: CancellationException) {
                settle = false
                throw e
            } finally {
                if (settle) {
                    emitTxSettledSnapshot(tx)
                    settlePolicyTx(tx)
                    replayWinnerTerminalIfStranded(tx)
                }
            }
        }
    }

    // F1 相容過載：舊 (key,store,sanitized) 調用轉 Tx 調用（無句柄即無 join，
    // 直接走驗證讀；僅測試/舊路徑兜底，生產一律 Tx 路徑）。
    private fun launchConfirmReconciler(
        key: String,
        store: HookPolicyStore,
        sanitized: Set<String>,
    ) {
        val tx = activePolicyTx.get()?.takeIf { it.key == key }
        if (tx != null) {
            launchConfirmReconciler(tx)
            return
        }
        // 無 Tx（不應發生）：退化為空 Tx 驗證讀，fence 仍經集合移除保終止。
        val fallback = PolicyTx(-1L, key, kotlinx.coroutines.CompletableDeferred(true), sanitized, store, false)
        launchConfirmReconciler(fallback)
    }

    // Stage A：clear 逾時後的背景核對器（語義同 confirm，終態以 ground truth 為準：
    // Known-empty→CLEAR_SUCCESS，Known 非空（清除未生效/併發寫入）→REFRESHED 誠實
    // 呈現；成功即 disarm；不碰 loading；子 job 可取消，見 confirm 註記）。
    private fun launchClearReconciler(tx: PolicyTx) {
        val store = tx.store
        viewModelScope.launch {
            if (!canJoinTx(tx)) {
                abortUnarmedTx(tx)
                return@launch
            }
            val versionAtStart = policyDraftVersion.get()
            val ownership = policyOwnershipGen.incrementAndGet()
            var settle = true
            try {
                try {
                    withTimeout(POLICY_RECONCILE_TOTAL_BUDGET_MS) {
                        val join: Boolean? = try {
                            joinPolicyTxBounded(tx)
                        } catch (e: CancellationException) {
                            throw e
                        }
                        if (join == false) {
                            if (!claimForPublish(tx)) {
                                settle = false
                                return@withTimeout
                            }
                            val groundRead: LoadResult = try {
                                val (gr, _) = boundedReconcileRead(store)
                                gr
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                LoadResult.Unavailable
                            }
                            val groundDecision: PolicyTxSettle = try {
                                decidePolicyTxSettle(tx, groundRead)
                            } catch (e: CancellationException) {
                                throw e
                            }
                            val staleJoin = policyDraftVersion.get() != versionAtStart ||
                                policyOwnershipGen.get() != ownership
                            val snapStateJoin = _state.value
                            val payloadJoin = if (staleJoin) {
                                PolicySettled(
                                    tx.txId, tx.key, snapStateJoin.policyDraft, snapStateJoin.policyDraftKnown,
                                    MsgKind.INFO, policyText(PolicyText(PolicyTextKind.REFRESHED)), true,
                                )
                            } else if (groundDecision is PolicyTxSettle.Success) {
                                val repairTerminal: Pair<String, MsgKind>? = if (tx.isRepair) repairSuccessTerminal(tx) else null
                                PolicySettled(
                                    tx.txId, tx.key, emptySet(), true,
                                    repairTerminal?.second ?: MsgKind.INFO,
                                    repairTerminal?.first ?: policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS)),
                                    true,
                                )
                            } else {
                                PolicySettled(
                                    tx.txId, tx.key, snapStateJoin.policyDraft, false, MsgKind.FAILED,
                                    if (tx.isRepair) policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED))
                                    else policyText(PolicyText(PolicyTextKind.CLEAR_FAILED)),
                                    true,
                                )
                            }
                            settleReconcilerTerminal(tx, payloadJoin) {
                                if (policyDraftVersion.get() != versionAtStart ||
                                    policyOwnershipGen.get() != ownership
                                ) {
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                } else if (groundDecision is PolicyTxSettle.Success) {
                                    bumpPolicyGenerations()
                                    disarmBlindClear()
                                    if (tx.isRepair) disarmCorruptRepair()
                                    try { noteLastKnownDraft(emptySet()) } catch (_: Exception) { }
                                    val repairTerminal: Pair<String, MsgKind>? = if (tx.isRepair) repairSuccessTerminal(tx) else null
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraft = emptySet(),
                                            policyDraftKnown = true,
                                            policyDraftCorrupt = false,
                                            msg = repairTerminal?.first ?: policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS)),
                                            msgKind = repairTerminal?.second ?: MsgKind.INFO,
                                        )
                                    }
                                } else {
                                    markPolicyUnknownLocked()
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = tx.isRepair,
                                            msg = if (tx.isRepair) policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED)) else policyText(PolicyText(PolicyTextKind.CLEAR_FAILED)),
                                            msgKind = MsgKind.FAILED,
                                        )
                                    }
                                }
                            }
                            settle = false
                            return@withTimeout
                        }
                        var result: LoadResult = LoadResult.Unavailable
                        var hadReadTimeout = false
                        delay(POLICY_RECONCILE_INITIAL_DELAY_MS)
                        for (i in 0 until POLICY_RECONCILE_RETRIES) {
                            val (r, timedOut) = boundedReconcileRead(store)
                            if (timedOut) hadReadTimeout = true
                            result = r
                            if (r is LoadResult.Known) {
                                if (!tx.deferred.isCompleted) {
                                    if (i < POLICY_RECONCILE_RETRIES - 1) {
                                        delay(POLICY_RECONCILE_RETRY_DELAY_MS)
                                    }
                                    continue
                                }
                                break
                            }
                            if (i < POLICY_RECONCILE_RETRIES - 1) {
                                delay(POLICY_RECONCILE_RETRY_DELAY_MS)
                            }
                        }
                        val finalResult = result
                        val lateProvenFailed = if (finalResult is LoadResult.Unknown &&
                            (hadReadTimeout || policyTxCoordinator != null) &&
                            canJoinTx(tx) && tx.deferred.isCompleted
                        ) {
                            try {
                                !tx.deferred.await()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                true
                            }
                        } else {
                            false
                        }
                        if (lateProvenFailed &&
                            policyDraftVersion.get() == versionAtStart &&
                            policyOwnershipGen.get() == ownership
                        ) {
                            if (!claimForPublish(tx)) {
                                settle = false
                                return@withTimeout
                            }
                            val failedPayload = PolicySettled(
                                tx.txId, tx.key, _state.value.policyDraft, false, MsgKind.FAILED,
                                if (tx.isRepair) policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED))
                                else policyText(PolicyText(PolicyTextKind.CLEAR_FAILED)),
                                true,
                            )
                            settleReconcilerTerminal(tx, failedPayload) {
                                if (policyDraftVersion.get() != versionAtStart ||
                                    policyOwnershipGen.get() != ownership
                                ) {
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                } else {
                                    markPolicyUnknownLocked()
                                    _state.update { cur ->
                                        cur.copy(
                                            pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = tx.isRepair,
                                            msg = if (tx.isRepair) policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED)) else policyText(PolicyText(PolicyTextKind.CLEAR_FAILED)),
                                            msgKind = MsgKind.FAILED,
                                        )
                                    }
                                }
                            }
                            settle = false
                        } else {
                            val settleDecision: PolicyTxSettle? = if (finalResult is LoadResult.Known) {
                                try {
                                    decidePolicyTxSettle(tx, finalResult)
                                } catch (e: CancellationException) {
                                    throw e
                                }
                            } else {
                                null
                            }
                            val staleOutside = policyDraftVersion.get() != versionAtStart ||
                                policyOwnershipGen.get() != ownership
                            val keepActiveUnknown = finalResult is LoadResult.Unknown &&
                                policyTxCoordinator != null &&
                                (!tx.deferred.isCompleted || !lateProvenFailed)
                            val unresolvedOutside = !staleOutside && (
                                settleDecision is PolicyTxSettle.Unresolved ||
                                    (finalResult is LoadResult.Unknown && hadReadTimeout) ||
                                    keepActiveUnknown
                                )
                            if (unresolvedOutside) {
                                synchronized(policySettleLock) {
                                    val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                                    if (curLocal === tx || (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                                        _state.update { cur ->
                                            if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                                            else cur.copy(
                                                policyWritePhase = PolicyWritePhase.RECONCILING,
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = tx.isRepair || (finalResult is LoadResult.Corrupt),
                                                msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        }
                                    }
                                }
                                settle = false
                            } else {
                                if (!claimForPublish(tx)) {
                                    settle = false
                                    return@withTimeout
                                }
                                val snapState = _state.value
                                val terminalPayload = if (staleOutside) {
                                    PolicySettled(
                                        tx.txId, tx.key, snapState.policyDraft, snapState.policyDraftKnown,
                                        MsgKind.INFO, policyText(PolicyText(PolicyTextKind.REFRESHED)), true,
                                    )
                                } else if (finalResult is LoadResult.Known) {
                                    when (settleDecision) {
                                        is PolicyTxSettle.Success -> {
                                            val repairTerminal: Pair<String, MsgKind>? = if (tx.isRepair) repairSuccessTerminal(tx) else null
                                            PolicySettled(
                                                tx.txId, tx.key, emptySet(), true,
                                                repairTerminal?.second ?: MsgKind.INFO,
                                                repairTerminal?.first ?: policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS)),
                                                true,
                                            )
                                        }
                                        is PolicyTxSettle.Mismatch -> PolicySettled(
                                            tx.txId, tx.key, finalResult.packages, true,
                                            MsgKind.INFO, policyText(PolicyText(PolicyTextKind.REFRESHED)), true,
                                        )
                                        else -> PolicySettled(
                                            tx.txId, tx.key, snapState.policyDraft, false, MsgKind.FAILED,
                                            if (tx.isRepair) policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED))
                                            else policyText(PolicyText(PolicyTextKind.CLEAR_FAILED)),
                                            true,
                                        )
                                    }
                                } else {
                                    PolicySettled(
                                        tx.txId, tx.key, snapState.policyDraft, false,
                                        MsgKind.CHANNEL, policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)), true,
                                    )
                                }
                                settleReconcilerTerminal(tx, terminalPayload) {
                                    if (policyDraftVersion.get() != versionAtStart ||
                                        policyOwnershipGen.get() != ownership
                                    ) {
                                        _state.update { cur ->
                                            cur.copy(
                                                pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                                msgKind = MsgKind.INFO,
                                            )
                                        }
                                    } else if (finalResult is LoadResult.Known) {
                                        when (settleDecision) {
                                            is PolicyTxSettle.Success -> {
                                                bumpPolicyGenerations()
                                                disarmBlindClear()
                                                if (tx.isRepair) disarmCorruptRepair()
                                                try { noteLastKnownDraft(emptySet()) } catch (_: Exception) { }
                                                val repairTerminal: Pair<String, MsgKind>? = if (tx.isRepair) repairSuccessTerminal(tx) else null
                                                _state.update { cur ->
                                                    cur.copy(
                                                        pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                                        policyWritePhase = PolicyWritePhase.IDLE,
                                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                        policyDraft = emptySet(),
                                                        policyDraftKnown = true,
                                                        policyDraftCorrupt = false,
                                                        msg = repairTerminal?.first ?: policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS)),
                                                        msgKind = repairTerminal?.second ?: MsgKind.INFO,
                                                    )
                                                }
                                            }
                                            is PolicyTxSettle.Mismatch -> {
                                                bumpPolicyGenerations()
                                                disarmBlindClear()
                                                if (tx.isRepair) disarmCorruptRepair()
                                                try { noteLastKnownDraft(finalResult.packages) } catch (_: Exception) { }
                                                _state.update { cur ->
                                                    cur.copy(
                                                        pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                                        policyWritePhase = PolicyWritePhase.IDLE,
                                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                        policyDraft = finalResult.packages,
                                                        policyDraftKnown = true,
                                                        policyDraftCorrupt = false,
                                                        msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                                        msgKind = MsgKind.INFO,
                                                    )
                                                }
                                            }
                                            is PolicyTxSettle.Failed -> {
                                                markPolicyUnknownLocked()
                                                _state.update { cur ->
                                                    cur.copy(
                                                        pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                                        policyWritePhase = PolicyWritePhase.IDLE,
                                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                        policyDraftKnown = false,
                                                        policyDraftCorrupt = tx.isRepair,
                                                        msg = if (tx.isRepair) policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED)) else policyText(PolicyText(PolicyTextKind.CLEAR_FAILED)),
                                                        msgKind = MsgKind.FAILED,
                                                    )
                                                }
                                            }
                                            else -> {
                                                // Unreachable.
                                            }
                                        }
                                    } else {
                                        _state.update { cur ->
                                            cur.copy(
                                                pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = tx.isRepair || (finalResult is LoadResult.Corrupt),
                                                msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        }
                                    }
                                }
                                settle = false
                            }
                        }
                    }
                } catch (te: TimeoutCancellationException) {
                    val staleOutside = policyDraftVersion.get() != versionAtStart ||
                        policyOwnershipGen.get() != ownership
                    if (staleOutside) {
                        try {
                            val coordO = policyTxCoordinator
                            if (coordO != null) {
                                coordO.discardOverrunPlaceholder(tx.txId, tx.key)
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                        }
                        try {
                            policyTxCoordinator?.tryCompleteClaim(tx)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                        }
                    }
                    synchronized(policySettleLock) {
                        if (policyDraftVersion.get() != versionAtStart ||
                            policyOwnershipGen.get() != ownership
                        ) {
                            val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                            if (curLocal === tx) activePolicyTx.compareAndSet(tx, null)
                            policyReconciling.remove(tx.key)
                            if (curLocal === tx || (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                                _state.update { cur ->
                                    cur.copy(
                                        pendingPolicyWrite = if (tx.isClear && !tx.isRepair) cur.pendingPolicyWrite else null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                        msgKind = MsgKind.INFO,
                                    )
                                }
                            }
                        } else {
                            val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                            if (curLocal === tx || (curLocal == null && _state.value.policyWritePhase == PolicyWritePhase.RECONCILING)) {
                                _state.update { cur ->
                                    if (cur.policyWritePhase != PolicyWritePhase.RECONCILING) cur
                                    else cur.copy(
                                        policyWritePhase = PolicyWritePhase.RECONCILING,
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = tx.isRepair,
                                        msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                    }
                    settle = false
                }
            } catch (e: CancellationException) {
                settle = false
                throw e
            } finally {
                if (settle) {
                    emitTxSettledSnapshot(tx)
                    settlePolicyTx(tx)
                    replayWinnerTerminalIfStranded(tx)
                }
            }
        }
    }

    // F1 相容過載：舊 (key,store) 調用轉 Tx 調用。
    private fun launchClearReconciler(key: String, store: HookPolicyStore) {
        val tx = activePolicyTx.get()?.takeIf { it.key == key }
        if (tx != null) {
            launchClearReconciler(tx)
            return
        }
        val fallback = PolicyTx(-1L, key, kotlinx.coroutines.CompletableDeferred(true), emptySet(), store, true)
        launchClearReconciler(fallback)
    }

    fun refreshPolicyDraft() {
        viewModelScope.launch {
            policyRefreshInFlight.incrementAndGet()
            _state.update { it.copy(policyRefreshing = true) }
            try {
                val freshGen = policyFreshnessGen.incrementAndGet()
                val versionAtStart = policyDraftVersion.get()
                val ownershipSnap = policyOwnershipGen.get()
                val store = hookPolicyStore
                val revBeforeRefresh = try { sampledCurrentRevision() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { -1L }
                val hasAuthRefresh = policyTxCoordinator != null || revisionSamplerForTests != null
                val revUnverifiableRefresh = revBeforeRefresh == -1L && hasAuthRefresh
                val txBeforeRead = activePolicyTx.get()
                val txCompletedBeforeRead = txBeforeRead != null &&
                    canJoinTx(txBeforeRead) && txBeforeRead.deferred.isCompleted
                val result = if (store == null) {
                    LoadResult.Unavailable
                } else try {
                    onIo {
                        if (_state.value.policyDraftCorrupt || store.hasCorruptEvidence()) {
                            store.loadForRepair()
                        } else {
                            val r = store.loadResult()
                            if (r is LoadResult.Unavailable && store.hasCorruptEvidence()) {
                                store.loadForRepair()
                            } else {
                                r
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    LoadResult.Unavailable
                }
                val revMovedRefresh = try {
                    revBeforeRefresh != -1L && sampledCurrentRevision() != revBeforeRefresh
                } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { true }
                val txSnap = activePolicyTx.get()
                val lateJoinOk: Boolean? = if (txSnap != null &&
                    txBeforeRead === txSnap &&
                    txCompletedBeforeRead &&
                    result is LoadResult.Known &&
                    !revUnverifiableRefresh &&
                    !revMovedRefresh &&
                    canJoinTx(txSnap) && txSnap.deferred.isCompleted
                ) {
                    try {
                        txSnap.deferred.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    null
                }
                if (txSnap != null && result is LoadResult.Known && lateJoinOk != null &&
                    !revUnverifiableRefresh && !revMovedRefresh
                ) {
                    val staleOutside = policyDraftVersion.get() != versionAtStart ||
                        policyOwnershipGen.get() != ownershipSnap
                    if (staleOutside) {
                        return@launch
                    }
                    if (policyTxCoordinator != null) {
                        val coordActiveSnap = policyTxCoordinator.activeTx.get()
                        if (coordActiveSnap !== txSnap) {
                            if (coordActiveSnap != null) {
                                clearObsoleteLocalTx(txSnap, policyTxCoordinator)
                            } else {
                                launchLoserSync(txSnap)
                            }
                            return@launch
                        }
                        if (!claimForPublish(txSnap)) {
                            return@launch
                        }
                    } else if (activePolicyTx.get() !== txSnap) {
                        return@launch
                    }
                    val latePayload = if (!lateJoinOk) {
                        val fMsg = policyText(
                            PolicyText(
                                if (txSnap.isRepair) PolicyTextKind.CORRUPT_REPAIR_FAILED
                                else if (txSnap.isClear) PolicyTextKind.CLEAR_FAILED else PolicyTextKind.WRITE_FAILED,
                            ),
                        )
                        PolicySettled(
                            txSnap.txId, txSnap.key, _state.value.policyDraft, false,
                            MsgKind.FAILED, fMsg, txSnap.isClear,
                        )
                    } else if (txSnap.isRepair) {
                        if (result.packages.isEmpty()) {
                            // Late-join repair success: publish the honest backup
                            // outcome carried on the shared Tx (repairResult) —
                            // mirrors the fast path; absent ⇒ attempted-not-
                            // guaranteed wording.
                            val repairTerminal = repairSuccessTerminal(txSnap)
                            PolicySettled(
                                txSnap.txId, txSnap.key, result.packages, true,
                                repairTerminal.second, repairTerminal.first, true,
                            )
                        } else {
                            PolicySettled(
                                txSnap.txId, txSnap.key, result.packages, true,
                                MsgKind.INFO, policyText(PolicyText(PolicyTextKind.REFRESHED)), true,
                            )
                        }
                    } else if (txSnap.isClear) {
                        val cMsg = if (result.packages.isEmpty()) policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS))
                        else policyText(PolicyText(PolicyTextKind.REFRESHED))
                        PolicySettled(
                            txSnap.txId, txSnap.key, result.packages, true,
                            MsgKind.INFO, cMsg, true,
                        )
                    } else {
                        val wMsg = if (result.packages == txSnap.sanitized) {
                            writeSuccessMsg(result.packages.size, txSnap.pending, txSnap.sanitized)
                        } else {
                            policyText(PolicyText(PolicyTextKind.REFRESHED))
                        }
                        PolicySettled(
                            txSnap.txId, txSnap.key, result.packages, true,
                            MsgKind.INFO, wMsg, false,
                        )
                    }
                    val stamped = emitTxSettledStageOnly(txSnap, latePayload)
                    synchronized(policySettleLock) {
                        val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                        val wonLocal = (curLocal === txSnap) && activePolicyTx.compareAndSet(txSnap, null)
                        try { policyReconciling.remove(txSnap.key) } catch (_: Exception) { }
                        if (wonLocal) {
                            if (policyTxCoordinator != null && stamped == null) {
                                applyStageRejectedUnderLock(txSnap, fenceHeld = true)
                            } else if (policyDraftVersion.get() != versionAtStart ||
                                policyOwnershipGen.get() != ownershipSnap
                            ) {
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        pendingPolicyWrite = if (txSnap.isClear && !txSnap.isRepair) cur.pendingPolicyWrite else null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                        msgKind = MsgKind.INFO,
                                    )
                                }
                            } else if (!lateJoinOk) {
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        pendingPolicyWrite = if (txSnap.isClear && !txSnap.isRepair) cur.pendingPolicyWrite else null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = txSnap.isRepair,
                                        msg = latePayload.msg,
                                        msgKind = MsgKind.FAILED,
                                    )
                                }
                            } else {
                                bumpPolicyGenerations()
                                disarmBlindClear()
                                disarmCorruptRepair()
                                try { noteLastKnownDraft(result.packages) } catch (_: Exception) { }
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        pendingPolicyWrite = if (txSnap.isClear && !txSnap.isRepair) cur.pendingPolicyWrite else null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraft = if (txSnap.isRepair && result.packages.isEmpty()) emptySet()
                                        else if (txSnap.isClear && result.packages.isEmpty()) emptySet()
                                        else result.packages,
                                        policyDraftKnown = true,
                                        policyDraftCorrupt = false,
                                        msg = latePayload.msg,
                                        msgKind = latePayload.msgKind,
                                    )
                                }
                            }
                        }
                    }
                    if (stamped != null) {
                        try {
                            policyTxCoordinator?.emitStaged(stamped)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    replayWinnerTerminalIfStranded(txSnap)
                    return@launch
                }
                if (txSnap != null && policyTxCoordinator != null) {
                    val coordActiveSnap = policyTxCoordinator.activeTx.get()
                    if (coordActiveSnap !== txSnap) {
                        if (coordActiveSnap != null) {
                            clearObsoleteLocalTx(txSnap, policyTxCoordinator)
                        } else {
                            launchLoserSync(txSnap)
                        }
                    }
                }
                synchronized(policySettleLock) {
                    if (txSnap != null) {
                        if (activePolicyTx.get() !== txSnap) return@synchronized
                        if (policyDraftVersion.get() != versionAtStart ||
                            policyFreshnessGen.get() != freshGen
                        ) {
                            return@synchronized
                        }
                        _state.update { cur ->
                            cur.copy(
                                policyDraftKnown = false,
                                policyDraftCorrupt = result is LoadResult.Corrupt || (cur.policyDraftCorrupt && result !is LoadResult.Known),
                            )
                        }
                    } else {
                        if (policyDraftVersion.get() != versionAtStart ||
                            policyFreshnessGen.get() != freshGen
                        ) {
                            return@synchronized
                        }
                        if (result is LoadResult.Known) {
                            if (hasActivePolicyTx()) {
                                _state.update { cur ->
                                    cur.copy(policyDraftKnown = false, policyDraftCorrupt = cur.policyDraftCorrupt)
                                }
                            } else if (revUnverifiableRefresh || revMovedRefresh) {
                                _state.update { cur ->
                                    cur.copy(policyDraftKnown = false, policyDraftCorrupt = cur.policyDraftCorrupt)
                                }
                            } else {
                                val verified = try {
                                    markObservedKnownVerified(revBeforeRefresh)
                                } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { false }
                                if (!verified) {
                                    try { policyRevisionStale.set(true) } catch (_: Throwable) { }
                                    _state.update { cur ->
                                        cur.copy(policyDraftKnown = false, policyDraftCorrupt = cur.policyDraftCorrupt)
                                    }
                                    return@synchronized
                                }
                                disarmBlindClear()
                                disarmCorruptRepair()
                                policyRevisionStale.set(false)
                                try { noteLastKnownDraft(result.packages) } catch (_: Exception) { }
                                _state.update { cur ->
                                    if (cur.msgKind == MsgKind.FAILED || cur.msgKind == MsgKind.CHANNEL) {
                                        cur.copy(
                                            policyDraft = result.packages,
                                            policyDraftKnown = true,
                                            policyDraftCorrupt = false,
                                            msg = policyText(PolicyText(PolicyTextKind.REFRESHED)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    } else {
                                        cur.copy(policyDraft = result.packages, policyDraftKnown = true, policyDraftCorrupt = false)
                                    }
                                }
                            }
                        } else {
                            val isCorrupt = result is LoadResult.Corrupt ||
                                (store?.hasCorruptEvidence() == true) ||
                                (_state.value.policyDraftCorrupt && result !is LoadResult.Known)
                            if (isCorrupt) disarmBlindClear()
                            _state.update { cur ->
                                if (store == null) {
                                    cur.copy(
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.NO_STORE_READ)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else if (isCorrupt) {
                                    cur.copy(
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = true,
                                        msg = policyText(PolicyText(PolicyTextKind.CORRUPT_DETECTED)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else {
                                    cur.copy(
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                if (policyRefreshInFlight.decrementAndGet() <= 0) {
                    policyRefreshInFlight.set(0)
                    _state.update { it.copy(policyRefreshing = false) }
                }
            }
        }
    }

    // 兩階段寫入 phase 1：為當下勾選建待確認快照 (同步，不碰 store)。
    fun requestPolicyWrite() {
        // 5.1.1:不再以全域 loading 旗標阻擋新請求 (refresh/其他操作進行中
        // 仍可開啟確認對話框),但若政策寫入 confirm 正在進行中則拒絕,避免
        // 併發寫入造成 UI/store 不一致 (opMutex 之外仍有 inFlight 去重)。
        // 任何政策寫入/清除進行中皆拒絕新請求,避免覆寫進行中的操作。
        if (inFlightWrites.any { it.startsWith("policy-") }) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        // Stage A：核對中拒絕新請求（BUSY；render 按鈕亦因非 IDLE 禁用，此為
        // 程式化調用兜底，pending 保留，請勿重試）。
        if (isPolicyReconcilingLocked()) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        // owner-user 門控:hook 只讀 user 0;非 owner 實例寫入的 group 不會被
        // 看到,故直接拒絕且不建 pending (不進入兩階段流程)。
        if (currentUserId() != 0) {
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.OWNER_WRITE)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // Stage I2 / O1: 陳舊修訂與 orphanDropGen 門控（fail-closed：未見新修訂
        // 或 orphan placeholder 被丟棄時，強制重讀刷新，不得覆寫未見修訂）。
        if (!checkOrphanDropAndRevisionStaleLocked()) {
            _state.update {
                it.copy(
                    policyDraftKnown = false,
                    policyDraftCorrupt = it.policyDraftCorrupt,
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // P2 #3:先前遠端政策未知時拒絕建 pending (fail closed,避免整包覆寫
        // 未曾示出的內容)。store 為 null 時走既有 null-store 路徑,不在此攔截。
        // clear 刻意不設 unknown 門控：明確刪除≠覆寫未見內容。
        // Stage L4: Corrupt 與 Unavailable 皆拒絕，但文案分離（損壞走修復流程）。
        if (hookPolicyStore != null && !_state.value.policyDraftKnown) {
            val corrupt = _state.value.policyDraftCorrupt
            _state.update {
                it.copy(
                    msg = policyText(
                        if (corrupt) PolicyText(PolicyTextKind.CORRUPT_DETECTED)
                        else PolicyText(PolicyTextKind.UNKNOWN_REFRESH),
                    ),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        if (_state.value.selectedKeys.isEmpty()) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.EMPTY_SELECTION)), msgKind = MsgKind.INVALID)
            }
            return
        }
        // Phase 3：catalog 空守衛（與「勾選已不在清單」區分：空 catalog 是載入
        // 時序問題而非使用者選錯，訊息明示「尚未載入」，免誤導重選）。
        if (_state.value.sourceCatalog.isEmpty()) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.CATALOG_LOADING)), msgKind = MsgKind.INVALID)
            }
            return
        }
        val (mapped, catalogDropped) = selectedPackagesForPolicy()
        // Phase 5 polish：request 建 pending 前先 sanitize（與 confirm 同消毒）：
        // 顯示數與寫入數一致；dropped 拆 catalog 缺失＋非法文法兩類（後者經
        // sanitizeHookPolicy 計數：除文法另有長度上限 256／總量上限 512，
        // 故 invalidDropped 亦含超長／超量被截斷者；此處 catalog 映射已做
        // isValidPackage 前置，故通常為 0，仍顯式計算以防映射/消毒漂移）。
        val sanitized = sanitizeHookPolicy(mapped)
        val invalidDropped = mapped.size - sanitized.size
        val dropped = catalogDropped + invalidDropped
        if (sanitized.isEmpty()) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.CATALOG_MISSING)), msgKind = MsgKind.INVALID)
            }
            return
        }
        if (dropped > 0) {
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.DROPPED, dropped)),
                    msgKind = MsgKind.INFO,
                )
            }
        }
        // S8（A3-6）：coordinator 和 store 皆在時，絕不再以 currentRevision() 代借
        // 未被權威觀察過的 rev —— 本 dialog 的基礎只有「本 VM 實際讀到的 rev」
        // （observedSnapshot）；從未權威讀過（obs==-1）時直接拒絕建 pending
        // （UNKNOWN_REFRESH，請使用者先重新整理），不再引入 TOCTOU 借用窗口。
        // coordinator==null 的 legacy 路徑不受影響（confirm 跳過 rev 門控）。
        if (hookPolicyStore != null && policyTxCoordinator != null) {
            val obsRev = try {
                observedSnapshot.get().rev
            } catch (_: Exception) {
                -1L
            }
            if (obsRev == -1L) {
                _state.update {
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
        }
        // Phase 3：建 pending 即進 QUEUED（同一 _state.update 翻相位＋epoch+1；
        // dropped 警語在上一次 emission，第二次 copy 保留其 msg 不覆寫）。
        // Stage J3：綁定當下修訂版本（外來修訂即 stale，confirm 比對失配拒寫）。
        // Stage L1 P1: 同綁 coordinator committedRevision (authoritative)。
        // Stage O1 P1#1: bind the AUTHORITATIVELY OBSERVED revision (the rev at which
        // this VM actually read the displayed draft), NOT currentRevision(). Sampling
        // current here would borrow the already-advanced B revision for a dialog still
        // showing stale A, letting confirm overwrite unseen B. obs==-1 於上方 S8 門
        // 已拒絕（coordinator+store 皆在時）；此處僅 legacy/null-coordinator 路徑
        // 才會讀到 -1（confirm 跳過 rev 檢查）。
        _state.update {
            it.copy(
                pendingPolicyWrite = PendingPolicyWrite(
                    packages = sanitized,
                    requestedAtMs = nowMonoMs(),
                    droppedCount = dropped,
                    baseDraftVersion = policyDraftVersion.get(),
                    baseCoordinatorRevision = try {
                        observedSnapshot.get().rev
                    } catch (_: Exception) {
                        -1L
                    },
                ),
                policyWritePhase = PolicyWritePhase.QUEUED,
                policyEpoch = policyPhaseEpoch.incrementAndGet(),
            )
        }
    }

    fun cancelPolicyWrite() {
        _state.value.pendingPolicyWrite?.let { cancelPolicyWrite(it) }
    }

    // 取消確認：只清 pending，永不寫入 store (取消≠寫入≠安裝)。
    // 5.1.1:全域 loading 不再阻擋取消 (refresh/其他操作進行中仍可取消),
    // 但 save 已開始的預期寫入則 no-op,避免 store
    // 已寫入而 UI 未提交的不一致;confirm 路徑在 save 後仍會 re-check pending。
    // P1 #1 queued-vs-committing:僅當此 expected 的 save 已實際開始
    // (policyWriteSaving 持有 key) 才 no-op；僅排隊等 mutex 的 confirm 仍可
    // 取消 (清 pending 後，排隊的 confirm 在取到 mutex 時 abort，不寫入)。
    fun cancelPolicyWrite(expected: PendingPolicyWrite) {
        synchronized(policySettleLock) {
            if (policyWriteSaving.contains(policyWriteKey(expected))) return
            if (_state.value.policyWritePhase == PolicyWritePhase.SAVING &&
                _state.value.pendingPolicyWrite == expected
            ) return
            if (_state.value.policyWritePhase == PolicyWritePhase.RECONCILING &&
                _state.value.pendingPolicyWrite == expected
            ) return
            if (policyReconciling.contains(policyWriteKey(expected))) return
            if (activePolicyTx.get()?.key == policyWriteKey(expected)) return
            // Stage G2：跨 VM（新 VM 認領前本地仍空，coordinator 仍為舊 B 的 key）。
            if (policyTxCoordinator?.activeTx?.get()?.key == policyWriteKey(expected)) return
            _state.update { cur ->
                val pending = cur.pendingPolicyWrite ?: return@update cur
                if (pending != expected) return@update cur
                cur.copy(
                    pendingPolicyWrite = null,
                    policyWritePhase = PolicyWritePhase.IDLE,
                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                    msg = policyText(PolicyText(PolicyTextKind.CANCELLED)),
                    msgKind = MsgKind.INFO,
                )
            }
        }
    }

    private fun drainForeignUpdatesUnderCommitLock(tx: PolicyTx, coord: PolicyTxCoordinator) {
        try {
            val replay = try { coord.settled.replayCache } catch (_: Exception) { emptyList<PolicySettled>() }
            for (s in replay) {
                if (s.txId == tx.txId && s.key == tx.key) continue
                val lastR = try { lastRevisionSeenRev.get() } catch (_: Exception) { -1L }
                val lastId = lastRevisionSeenTxId.get()
                val lastK = try { lastRevisionSeenKey.get() } catch (_: Exception) { null }
                val isNewer = if (s.rev != -1L && lastR != -1L) {
                    s.rev > lastR
                } else {
                    s.txId > lastId || (s.txId == lastId && s.key != lastK)
                }
                if (!isNewer) continue
                lastRevisionSeenTxId.set(s.txId)
                lastRevisionSeenKey.set(s.key)
                advanceLastRevisionSeenRev(s.rev)
                policyRevisionStale.set(true)
            }
            try {
                val latestOne = try { coord.latest.value } catch (_: Exception) { null }
                if (latestOne != null &&
                    !(latestOne.txId == tx.txId && latestOne.key == tx.key)
                ) {
                    val lastR = try { lastRevisionSeenRev.get() } catch (_: Exception) { -1L }
                    val isNewer = if (latestOne.rev != -1L && lastR != -1L) {
                        latestOne.rev > lastR
                    } else {
                        latestOne.txId != lastRevisionSeenTxId.get() ||
                            latestOne.key != try { lastRevisionSeenKey.get() } catch (_: Exception) { null }
                    }
                    if (isNewer) {
                        lastRevisionSeenTxId.set(latestOne.txId)
                        lastRevisionSeenKey.set(latestOne.key)
                        advanceLastRevisionSeenRev(latestOne.rev)
                        policyRevisionStale.set(true)
                    }
                }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        try {
            val stagedForDrain = try { coord.pendingPublish.get() } catch (_: Exception) { null }
            if (stagedForDrain != null &&
                !(stagedForDrain.txId == tx.txId && stagedForDrain.key == tx.key)
            ) {
                try {
                    val retired = try {
                        coord.retireStagedRealPresentInLatestLocked(
                            System.nanoTime(),
                            policyIoTimeoutMs,
                        )
                    } catch (_: Exception) { null }
                    if (retired != null) {
                        lastRevisionSeenTxId.set(retired.txId)
                        lastRevisionSeenKey.set(retired.key)
                        advanceLastRevisionSeenRev(retired.rev)
                    }
                } catch (_: Exception) {
                }
                val recovered = try {
                    coord.recoverStalePendingLocked(
                        System.nanoTime(),
                        policyIoTimeoutMs,
                    )
                } catch (_: Exception) { null }
                if (recovered == null) {
                    val stillStaged = try { coord.pendingPublish.get() } catch (_: Exception) { null }
                    if (stillStaged != null &&
                        !(stillStaged.txId == tx.txId && stillStaged.key == tx.key)
                    ) {
                        policyRevisionStale.set(true)
                    }
                } else {
                    val isReal = try {
                        recovered.known || recovered.msg.isNotEmpty()
                    } catch (_: Exception) { true }
                    if (isReal) {
                        lastRevisionSeenTxId.set(recovered.txId)
                        lastRevisionSeenKey.set(recovered.key)
                        advanceLastRevisionSeenRev(recovered.rev)
                    }
                    val obs = try { observedSnapshot.get().rev } catch (_: Exception) { -1L }
                    if (obs != -1L && (recovered.rev == -1L || recovered.rev > obs)) {
                        policyRevisionStale.set(true)
                    } else if (obs == -1L) {
                        policyRevisionStale.set(true)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // 兩階段寫入 phase 2：世代全等＋TTL 內＋S1 acknowledgement 才寫 store；
    // 任何不符清 pending 拒絕。
    // S1（consent fence，第二道門）：acknowledgedGeneration 必須非 null 且等於
    // expected.generation（ack 綁定到本 pending 的世代），且 pending 必須
    // 與 expected 相同；否則不寫 store、不清 pending（僅提示），msgKind INVALID。
    // UI 第一道門（MainActivity positive 鈕以 policyAcknowledged 門控，勾選
    // 綁世代）為主，本門為全 VM 防線 —— 程式化誤呼叫/腳本亦無法繞過。
    // SL1 (ack generation binding): the write is authorized iff the caller
    // proves it saw the acknowledgement for THIS pending — pass the pending's
    // generation as [acknowledgedGeneration]; a mismatch (or null) refuses.
    // MainActivity passes `if (policyAcknowledged) pending.generation else null`;
    // no default argument, no blanket boolean. The previously-added
    // POLICY_NOT_ACKNOWLEDGED message stays for the unauthorized case.
    fun confirmPolicyWrite(expected: PendingPolicyWrite, acknowledgedGeneration: Long?) {
        // N1: pending==null 必須優先報 NO_PENDING（INVALID），不得被 acknowledgement
        // 訊息掩蓋（acknowledgement 檢查置於其後）。
        if (_state.value.pendingPolicyWrite == null) {
            // 保留舊 no-arg 語義：無 pending 時報 NO_PENDING（INVALID），不靜默。
            _state.update { cur ->
                if (cur.pendingPolicyWrite != null) cur
                else cur.copy(msg = policyText(PolicyText(PolicyTextKind.NO_PENDING)), msgKind = MsgKind.INVALID)
            }
            return
        }
        // S1: acknowledgement fence。pending 保留（使用者仍可在對話框勾選後
        // 再按確認）；世代不符／未確認一律不寫 store（POLICY_NOT_ACKNOWLEDGED）；
        // pending 已非 expected（別的 pending 在途）則靜默 no-op（同既有世代契約）。
        if (acknowledgedGeneration == null || acknowledgedGeneration != expected.generation) {
            _state.update { cur ->
                if (cur.pendingPolicyWrite == expected) {
                    cur.copy(
                        msg = policyText(PolicyText(PolicyTextKind.POLICY_NOT_ACKNOWLEDGED)),
                        msgKind = MsgKind.INVALID,
                    )
                } else {
                    cur
                }
            }
            return
        }
        if (_state.value.pendingPolicyWrite != expected) return
        // Stage A：核對中拒絕新確認（IN_PROGRESS；pending 保留，請勿重試）。
        // 置於 owner 門控之前：核對中不得清 pending（owner 拒絕會清 pending，
        // 此處必須先擋）。
        if (isPolicyReconcilingLocked()) {
            _state.update { cur ->
                if (cur.pendingPolicyWrite != expected) return@update cur
                cur.copy(
                    msg = policyText(PolicyText(PolicyTextKind.IN_PROGRESS)),
                    msgKind = MsgKind.INFO,
                )
            }
            return
        }
        // owner-user 門控 (defense-in-depth)：hook 只讀 user 0；非 owner 的
        // 寫入不會被 hook 看到，故直接拒絕、清 pending 且不寫 store。
        if (currentUserId() != 0) {
            _state.update {
                it.copy(
                    pendingPolicyWrite = null,
                    policyWritePhase = PolicyWritePhase.IDLE,
                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                    msg = policyText(PolicyText(PolicyTextKind.OWNER_WRITE)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // Stage I2：陳舊修訂 stale 即強制重讀（同 unknown 門控：清 pending、不碰
        // store、重申 Unknown＋fence，晚到舊 Known 不得蓋回）。
        if (policyRevisionStale.get()) {
            markPolicyUnknownLocked()
            _state.update { cur ->
                if (cur.pendingPolicyWrite != expected) return@update cur
                cur.copy(
                    pendingPolicyWrite = null,
                    policyWritePhase = PolicyWritePhase.IDLE,
                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                    policyDraftKnown = false,
                    policyDraftCorrupt = cur.policyDraftCorrupt,
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        viewModelScope.launch {
            val pendingAtCall = expected
            if (_state.value.pendingPolicyWrite != pendingAtCall) return@launch
            val key = policyWriteKey(pendingAtCall)
            if (!inFlightWrites.add(key)) {
                _state.update {
                    if (it.pendingPolicyWrite != pendingAtCall) return@update it
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.IN_PROGRESS)),
                        msgKind = MsgKind.INFO,
                    )
                }
                return@launch
            }
            // Phase 3：排隊即 QUEUED（inFlight 已持 key 但尚未取到 mutex；request
            // 已設 QUEUED 時此為冪等；直接注入 pending 的測試路徑在此補上 QUEUED，
            // 使 state 與 inFlight 同步，render 顯示等待中）。
            _state.update { cur ->
                if (cur.pendingPolicyWrite != pendingAtCall) return@update cur
                if (cur.policyWritePhase != PolicyWritePhase.IDLE) return@update cur
                cur.copy(
                    policyWritePhase = PolicyWritePhase.QUEUED,
                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                )
            }
            // Stage A：逾時交接槽（外層 finally 自 inFlight/saving 交接至
            // reconciling；非逾時路徑為 null，走原清理）。
            // Stage F1：交接改攜 Tx（deferred 句柄保留，登記 activePolicyTx）。
            // Stage H1：所有權前置＋單寫者序列化——opMutex 內、store IO 前同步
            // CAS claim（LAZY async，失敗即 IN_PROGRESS 不啟 IO）；writeLocked
            // 追蹤 app-scoped writeMutex（在途持有，逾時/快徑/取消皆解鎖，但
            // finally 永不清除 coordinator.activeTx，僅 settle 清）；preClaimTx
            // 追蹤已認領 Tx（VM cancel 不 cancel 已 claim deferred，只取消
            // await，deferred 續跑供新 VM join 同一句柄）。
            var handoverTx: PolicyTx? = null
            var writeLocked = false
            var preClaimTx: PolicyTx? = null
            var coordClaimedTx: PolicyTx? = null
            try {
                opMutex.withLock {
                    if (isPolicyReconcilingLocked()) {
                        _state.update { cur ->
                            if (cur.policyWritePhase == PolicyWritePhase.RECONCILING) {
                                if (cur.pendingPolicyWrite != pendingAtCall) cur.copy(loading = false)
                                else cur.copy(
                                    loading = false,
                                    msg = policyText(PolicyText(PolicyTextKind.IN_PROGRESS)),
                                    msgKind = MsgKind.INFO,
                                )
                            } else {
                                cur.copy(
                                    loading = false,
                                    policyWritePhase = PolicyWritePhase.QUEUED,
                                )
                            }
                        }
                        return@withLock
                    }
                    val cur = _state.value.pendingPolicyWrite
                    if (cur == null) {
                        synchronized(policySettleLock) {
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update { s ->
                                if (s.pendingPolicyWrite == null &&
                                    s.policyWritePhase == PolicyWritePhase.QUEUED
                                ) {
                                    s.copy(
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    )
                                } else s
                            }
                        }
                        return@withLock
                    }
                    if (cur != pendingAtCall) return@withLock
                    if (nowMonoMs() - cur.requestedAtMs > POLICY_CONFIRM_TTL_MS) {
                        synchronized(policySettleLock) {
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update {
                                it.copy(
                                    pendingPolicyWrite = null,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    msg = policyText(PolicyText(PolicyTextKind.TTL_EXPIRED)),
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                        }
                        return@withLock
                    }
                    val store = hookPolicyStore
                    if (store == null) {
                        synchronized(policySettleLock) {
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            markPolicyUnknownLocked()
                            _state.update {
                                it.copy(
                                    pendingPolicyWrite = null,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    policyDraftKnown = false,
                                    policyDraftCorrupt = false,
                                    msg = policyText(PolicyText(PolicyTextKind.NO_STORE_WRITE)),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                        return@withLock
                    }
                    if (!_state.value.policyDraftKnown) {
                        val wasCorrupt = _state.value.policyDraftCorrupt
                        synchronized(policySettleLock) {
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            markPolicyUnknownLocked()
                            _state.update {
                                it.copy(
                                    pendingPolicyWrite = null,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    policyDraftKnown = false,
                                    policyDraftCorrupt = wasCorrupt,
                                    msg = policyText(
                                        if (wasCorrupt) PolicyText(PolicyTextKind.CORRUPT_DETECTED)
                                        else PolicyText(PolicyTextKind.UNKNOWN_REFRESH),
                                    ),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                        return@withLock
                    }
                    if (policyRevisionStale.get()) {
                        synchronized(policySettleLock) {
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            markPolicyUnknownLocked()
                            _state.update { curWrite ->
                                if (curWrite.pendingPolicyWrite != pendingAtCall) return@update curWrite
                                curWrite.copy(
                                    pendingPolicyWrite = null,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    policyDraftKnown = false,
                                    policyDraftCorrupt = curWrite.policyDraftCorrupt,
                                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                        return@withLock
                    }
                    val sanitized = sanitizeHookPolicy(cur.packages)
                    if (sanitized.isEmpty()) {
                        synchronized(policySettleLock) {
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update {
                                it.copy(
                                    pendingPolicyWrite = null,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    msg = policyText(PolicyText(PolicyTextKind.CATALOG_MISSING)),
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                        }
                        return@withLock
                    }
                    var h1Deferred: Deferred<Boolean>? = null
                    val h1Coord = policyTxCoordinator
                    if (h1Coord != null) {
                        if (!h1Coord.writeMutex.tryLock()) {
                            _state.update { s ->
                                if (s.pendingPolicyWrite != pendingAtCall) s.copy(loading = false)
                                else s.copy(
                                    loading = false,
                                    msg = policyText(PolicyText(PolicyTextKind.IN_PROGRESS)),
                                    msgKind = MsgKind.INFO,
                                )
                            }
                            return@withLock
                        }
                        writeLocked = true
                        val lazy = h1Coord.scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            store.save(sanitized)
                        }
                        val tx = PolicyTx(
                            txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                            key = key,
                            deferred = lazy,
                            sanitized = sanitized,
                            store = store,
                            isClear = false,
                            pending = pendingAtCall,
                        )
                        val h1ClaimRes = h1Coord.tryClaimWrite(tx, pendingAtCall.baseCoordinatorRevision)
                        if (h1ClaimRes == PolicyTxCoordinator.ClaimWriteResult.BUSY) {
                            try { lazy.cancel() } catch (_: Exception) { }
                            try { h1Coord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            _state.update { s ->
                                if (s.pendingPolicyWrite != pendingAtCall) s.copy(loading = false)
                                else s.copy(
                                    loading = false,
                                    msg = policyText(PolicyText(PolicyTextKind.IN_PROGRESS)),
                                    msgKind = MsgKind.INFO,
                                )
                            }
                            return@withLock
                        }
                        if (h1ClaimRes == PolicyTxCoordinator.ClaimWriteResult.STALE) {
                            try { lazy.cancel() } catch (_: Exception) { }
                            try { h1Coord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            synchronized(policySettleLock) {
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                markPolicyUnknownLocked()
                                _state.update { s ->
                                    s.copy(
                                        loading = false,
                                        pendingPolicyWrite = null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                        coordClaimedTx = tx
                        val localSet = synchronized(policySettleLock) {
                            activePolicyTx.compareAndSet(null, tx)
                        }
                        if (!localSet) {
                            try {
                                withContext(NonCancellable) {
                                    h1Coord.withCommitLock {
                                        try {
                                            if (h1Coord.activeTx.get() === tx) h1Coord.activeTx.set(null)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                try { h1Coord.tryCompleteClaim(tx) } catch (_: Exception) { }
                            }
                            coordClaimedTx = null
                            try { lazy.cancel() } catch (_: Exception) { }
                            try { h1Coord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            _state.update { s ->
                                if (s.pendingPolicyWrite != pendingAtCall) s.copy(loading = false)
                                else s.copy(
                                    loading = false,
                                    msg = policyText(PolicyText(PolicyTextKind.IN_PROGRESS)),
                                    msgKind = MsgKind.INFO,
                                )
                            }
                            return@withLock
                        }
                        h1Deferred = lazy
                        preClaimTx = tx
                        coordClaimedTx = null
                        var preIoAbort = false
                        policyRevisionMutex.withLock {
                            try {
                                h1Coord.withCommitLock {
                                    currentCoroutineContext().ensureActive()
                                    drainForeignUpdatesUnderCommitLock(tx, h1Coord)
                                    val baseMismatchPreIo = pendingAtCall.baseDraftVersion != -1L &&
                                        pendingAtCall.baseDraftVersion != policyDraftVersion.get()
                                    val coordActive = try { h1Coord.activeTx.get() } catch (_: Exception) { null }
                                    val coordActiveOk = (coordActive == null || coordActive === tx)
                                    val revOk = (pendingAtCall.baseCoordinatorRevision == -1L ||
                                        h1Coord.committedRevision.get() == pendingAtCall.baseCoordinatorRevision)
                                    val staged = try { h1Coord.pendingPublish.get() } catch (_: Exception) { null }
                                    val pendingOk = (staged == null || (staged.txId == tx.txId && staged.key == tx.key))
                                    val snap = observedSnapshot.get()
                                    val curDrop = try { h1Coord.orphanDropGen.get() } catch (_: Exception) { 0L }
                                    val dropStale = if (curDrop != snap.dropGen) {
                                        val dropRev = try { h1Coord.orphanDropRev.get() } catch (_: Exception) { -1L }
                                        if (dropRev == -1L || dropRev >= snap.rev) {
                                            try { policyRevisionStale.set(true) } catch (_: Exception) { }
                                            true
                                        } else {
                                            // dropGen is only mutated under commitMutex (recover/discard
                                            // paths); this loop runs inside withCommitLock, so curDrop is
                                            // stable and the CAS converges or breaks immediately.
                                            var done = false
                                            var guard = 0
                                            while (!done && guard++ < 8) {
                                                val curSnap = try { observedSnapshot.get() } catch (_: Exception) { snap }
                                                if (curSnap.dropGen == curDrop) break
                                                val merged = if (curSnap.rev != snap.rev) curSnap.copy(dropGen = curDrop) else snap.copy(dropGen = curDrop)
                                                try { done = observedSnapshot.compareAndSet(curSnap, merged) } catch (_: Exception) { break }
                                                if (!done && try { observedSnapshot.get().dropGen } catch (_: Exception) { curDrop } == curDrop) break
                                            }
                                            false
                                        }
                                    } else false
                                    if (!coordActiveOk || !revOk || !pendingOk || baseMismatchPreIo || policyRevisionStale.get() || dropStale) {
                                        preIoAbort = true
                                    } else {
                                        armAndStartTxDeferred(tx)
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                // Fail-closed: never fall back to local-only checks and never arm/start deferred.
                                preIoAbort = true
                            }
                        }
                        if (preIoAbort) {
                            try { h1Coord.tryCompleteClaim(tx) } catch (_: Exception) { }
                            if (writeLocked) {
                                try { h1Coord.writeMutex.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            try { lazy.cancel() } catch (_: Exception) { }
                            synchronized(policySettleLock) {
                                activePolicyTx.compareAndSet(tx, null)
                                policyReconciling.remove(tx.key)
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                markPolicyUnknownLocked()
                                _state.update { s ->
                                    s.copy(
                                        loading = false,
                                        pendingPolicyWrite = null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                    }
                    if (h1Coord == null) {
                        val baseMismatchNoCoord = pendingAtCall.baseDraftVersion != -1L &&
                            pendingAtCall.baseDraftVersion != policyDraftVersion.get()
                        if (baseMismatchNoCoord || policyRevisionStale.get()) {
                            synchronized(policySettleLock) {
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                markPolicyUnknownLocked()
                                _state.update {
                                    it.copy(
                                        pendingPolicyWrite = null,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                    }
                    synchronized(policySettleLock) {
                        _state.update {
                            it.copy(
                                loading = true,
                                msg = "",
                                msgKind = MsgKind.NONE,
                                policyWritePhase = PolicyWritePhase.SAVING,
                            )
                        }
                        policyWriteSaving.add(key)
                    }
                    if (_state.value.pendingPolicyWrite != pendingAtCall) {
                        val pc = preClaimTx
                        if (pc != null) {
                            try { policyTxCoordinator?.tryCompleteClaim(pc) } catch (_: Exception) { }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            try { h1Deferred?.cancel() } catch (_: Exception) { }
                        }
                        synchronized(policySettleLock) {
                            if (pc != null) {
                                activePolicyTx.compareAndSet(pc, null)
                                policyReconciling.remove(pc.key)
                                preClaimTx = null
                            }
                            val nowPendingNull = try { _state.value.pendingPolicyWrite == null } catch (_: Exception) { true }
                            if (!nowPendingNull) {
                                // F2: a *different* pending exists — this VM's view of the
                                // committed draft is still no longer authoritative, so the
                                // draft fence must be dropped exactly like the vanished arm
                                // (fail-closed REFRESH semantics). pendingPolicyWrite itself
                                // is NOT touched — a different pending may exist. The
                                // in-flight/saving keys ARE ours alone (generation-bound via
                                // policyWriteKey) and are dropped symmetrically with the
                                // vanished arm so they don't leak.
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                markPolicyUnknownLocked()
                            }
                            if (nowPendingNull) {
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                // A2-2: the pending vanished mid-SAVING — the draft fence
                                // must be dropped just like the preIoAbort path, or a stale
                                // Known fence survives an unfinished write.
                                markPolicyUnknownLocked()
                            }
                            _state.update { cur ->
                                // F2: both arms converge to the same fail-closed UNKNOWN_REFRESH
                                // terminal (the mismatch arm previously restored QUEUED without
                                // dropping the fence). Predicate-gated: a legitimate terminal
                                // may already have moved phase out of SAVING (concurrent
                                // reconciler/cancel) — never overwrite it.
                                if (cur.policyWritePhase != PolicyWritePhase.SAVING) cur
                                else cur.copy(
                                    loading = false,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    policyDraftKnown = false,
                                    policyDraftCorrupt = false,
                                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                        return@withLock
                    }
                    try {
                        val deferred: Deferred<Boolean> = h1Deferred ?: viewModelScope.async(Dispatchers.IO) {
                            store.save(sanitized)
                        }
                        val ok: Boolean? = try {
                            withTimeout(policyIoTimeoutMs) {
                                deferred.await()
                            }
                        } catch (te: TimeoutCancellationException) {
                            null
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                if (preClaimTx == null) {
                                    try { deferred.cancel() } catch (_: Exception) { }
                                }
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                                throw e
                            }
                            if (preClaimTx == null) {
                                try { deferred.cancel() } catch (_: Exception) { }
                            }
                            false
                        }
                        if (_state.value.pendingPolicyWrite != pendingAtCall) {
                            val pc = preClaimTx
                            if (pc != null) {
                                try { policyTxCoordinator?.tryCompleteClaim(pc) } catch (_: Exception) { }
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                            } else {
                                try { deferred.cancel() } catch (_: Exception) { }
                            }
                            synchronized(policySettleLock) {
                                if (pc != null) {
                                    activePolicyTx.compareAndSet(pc, null)
                                    policyReconciling.remove(pc.key)
                                    preClaimTx = null
                                }
                                val nowPendingNull2 = try { _state.value.pendingPolicyWrite == null } catch (_: Exception) { true }
                                if (!nowPendingNull2) {
                                    // F2: a *different* pending exists — this VM's view of the
                                    // committed draft is still no longer authoritative, so the
                                    // draft fence must be dropped exactly like the vanished arm
                                    // (fail-closed REFRESH semantics). pendingPolicyWrite itself
                                    // is NOT touched — a different pending may exist. The
                                    // in-flight/saving keys ARE ours alone (generation-bound via
                                    // policyWriteKey) and are dropped symmetrically with the
                                    // vanished arm so they don't leak.
                                    inFlightWrites.remove(key)
                                    policyWriteSaving.remove(key)
                                    markPolicyUnknownLocked()
                                }
                                if (nowPendingNull2) {
                                    inFlightWrites.remove(key)
                                    policyWriteSaving.remove(key)
                                    // A2-2: after the await, the pending vanished — drop the
                                    // draft fence exactly like the preIoAbort path.
                                    markPolicyUnknownLocked()
                                }
                                _state.update { cur ->
                                    // F2: both arms converge to the same fail-closed UNKNOWN_REFRESH
                                    // terminal (the mismatch arm previously restored QUEUED without
                                    // dropping the fence). Predicate-gated: a legitimate terminal
                                    // may already have moved phase out of SAVING (concurrent
                                    // reconciler/cancel) — never overwrite it.
                                    if (cur.policyWritePhase != PolicyWritePhase.SAVING) cur
                                    else cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                        if (ok == null) {
                            val htx = preClaimTx ?: PolicyTx(
                                txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                                key = key,
                                deferred = deferred,
                                sanitized = sanitized,
                                store = store,
                                isClear = false,
                                pending = pendingAtCall,
                                executionArmed = AtomicBoolean(true),
                            )
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                if (preClaimTx == null) {
                                    activePolicyTx.compareAndSet(null, htx)
                                }
                                policyReconciling.add(htx.key)
                                markPolicyUnknownLocked()
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.RECONCILING,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.WRITE_TIMEOUT)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            handoverTx = htx
                            preClaimTx = null
                            return@withLock
                        }
                        if (ok) {
                            val ackJob = viewModelScope.async(Dispatchers.IO) { store.loadResult() }
                            val ackRead: LoadResult = try {
                                withTimeout(minOf(policyIoTimeoutMs, 1_000L)) { ackJob.await() }
                            } catch (te: TimeoutCancellationException) {
                                try { ackJob.cancel() } catch (_: Exception) { }
                                LoadResult.Unavailable
                            } catch (e: CancellationException) {
                                try { ackJob.cancel() } catch (_: Exception) { }
                                val pc = preClaimTx
                                if (pc != null) {
                                    handoverTx = pc
                                    preClaimTx = null
                                    if (writeLocked) {
                                        try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                        writeLocked = false
                                    }
                                }
                                throw e
                            } catch (_: Exception) {
                                try { ackJob.cancel() } catch (_: Exception) { }
                                LoadResult.Unavailable
                            }
                            val staleFallbackHandover = policyRevisionStale.get() && ackRead !is LoadResult.Known
                            val unknownHandover = ackRead is LoadResult.Unknown
                            if (staleFallbackHandover || unknownHandover) {
                                val ackCorrupt = ackRead is LoadResult.Corrupt && !staleFallbackHandover
                                val htx = preClaimTx ?: PolicyTx(
                                    txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                                    key = key,
                                    deferred = deferred,
                                    sanitized = sanitized,
                                    store = store,
                                    isClear = false,
                                    pending = pendingAtCall,
                                    executionArmed = AtomicBoolean(true),
                                )
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                                synchronized(policySettleLock) {
                                    if (preClaimTx == null) {
                                        activePolicyTx.compareAndSet(null, htx)
                                    }
                                    policyReconciling.add(htx.key)
                                    markPolicyUnknownLocked()
                                    _state.update {
                                        it.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.RECONCILING,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = ackCorrupt,
                                            msg = policyText(PolicyText(PolicyTextKind.WRITE_TIMEOUT)),
                                            msgKind = MsgKind.CHANNEL,
                                        )
                                    }
                                }
                                handoverTx = htx
                                preClaimTx = null
                                return@withLock
                            }
                            val pcFast = preClaimTx
                            if (pcFast != null && !claimForPublish(pcFast)) {
                                try { ackJob.cancel() } catch (_: Exception) { }
                                preClaimTx = null
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                                // 成功側 claimForPublish 的 revBefore=null 恆為 unconditional
                                // CAS；reject 只可能是 CAS-loss＝有 live winner 消費了 claim
                                // —— loser handling（SAVING→RECONCILING＋launchLoserSync）
                                // 已在 tryClaimStagedVerdict 內完成，本 VM 跟隨 winner 收斂即可，
                                // 不得在此 abort 至 UNKNOWN_REFRESH（會蓋掉 winner 終態）。
                                return@withLock
                            }
                            val isRefresh = policyRevisionStale.get() ||
                                (ackRead is LoadResult.Known && ackRead.packages != sanitized)
                            val finalDraft = if (isRefresh && ackRead is LoadResult.Known) {
                                ackRead.packages
                            } else {
                                sanitized
                            }
                            // F6: 訊息計數使用 committed finalDraft——在 Known-ack 分歧下
                            // finalDraft 是 store 的 ground truth，而非本 VM 的 sanitized 提案。
                            val finalMsg = if (isRefresh) {
                                policyText(PolicyText(PolicyTextKind.REFRESHED))
                            } else {
                                writeSuccessMsg(finalDraft.size, cur, sanitized)
                            }
                            val payload = pcFast?.let {
                                PolicySettled(it.txId, it.key, finalDraft, true, MsgKind.INFO, finalMsg, false)
                            }
                            val stamped = if (pcFast != null && payload != null) {
                                emitTxSettledStageOnly(pcFast, payload)
                            } else {
                                null
                            }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                val wonLocal = (pcFast == null) || activePolicyTx.compareAndSet(pcFast, null)
                                if (pcFast != null) { policyReconciling.remove(pcFast.key) }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                if (wonLocal) {
                                    if (pcFast != null && policyTxCoordinator != null && stamped == null) {
                                        applyStageRejectedUnderLock(pcFast, key, fenceHeld = true)
                                    } else {
                                        // F4 (F2/F9): the VM's own successful confirm/clear is an
                                        // authoritative observation of the committed draft —
                                        // clear the stale flag (unconditional, matching the
                                        // foreign-known-settle handler). markObservedKnown() is
                                        // deliberately NOT called here. When stamped != null,
                                        // observedSnapshot was already set to stamped.rev inside
                                        // emitTxSettledStageOnly (no sampling race), so a mark
                                        // would be redundant AND could overshoot observed to an
                                        // unseen FOREIGN B commit. When stamped == null (no staged
                                        // payload): (a) coord==null legacy → sampledCurrentRevision()
                                        // returns -1 → mark is a no-op; (b) claimless-with-coordinator
                                        // edge → sampledCurrentRevision() returns committedRevision()
                                        // (>= 0, unconditionally assigned, no max/merge) and COULD
                                        // absorb a foreign commit. The defense is therefore that this
                                        // call site NEVER marks; the no-mark rule is the contract,
                                        // not an impossibility proof.
                                        try { policyRevisionStale.set(false) } catch (_: Exception) { }
                                        bumpPolicyGenerations()
                                        disarmBlindClear()
                                        try { noteLastKnownDraft(finalDraft) } catch (_: Exception) { }
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                pendingPolicyWrite = null,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                policyDraft = finalDraft,
                                                policyDraftKnown = true,
                                                policyDraftCorrupt = false,
                                                msg = finalMsg,
                                                msgKind = MsgKind.INFO,
                                            )
                                        }
                                    }
                                }
                            }
                            if (stamped != null) {
                                policyTxCoordinator?.emitStaged(stamped)
                            }
                        } else {
                            val pcFailFast = preClaimTx
                            // A3-3: gate the FAILED placeholder claim on the same rev base
                            // the IO-stage claim used; a foreign B committing between the
                            // failed save and this claim bumps committedRevision and the
                            // terminal is rejected, exactly like a success claim.
                            if (pcFailFast != null) {
                                val claimVerdict = claimForPublishCheckedVerdict(
                                    pcFailFast,
                                    pendingAtCall.baseCoordinatorRevision,
                                )
                                if (claimVerdict != PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED) {
                                    preClaimTx = null
                                    if (writeLocked) {
                                        try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                        writeLocked = false
                                    }
                                    // A3-3 (C1): settle ONLY on rev-gate rejection — the
                                    // foreign B committed mid-IO and bumped
                                    // committedRevision before the CAS, so no claim was
                                    // consumed and no winner exists: settle the terminal
                                    // as stage-rejected (clears local fence + keys,
                                    // IDLE + UNKNOWN_REFRESH). CAS_LOSS means a live
                                    // winner consumed the claim — loser-follow already ran
                                    // inside tryClaimStagedVerdict; do nothing further.
                                    if (claimVerdict == PolicyTxCoordinator.ClaimStagedVerdict.REV_GATE_REJECTED) {
                                        synchronized(policySettleLock) {
                                            applyStageRejectedUnderLock(pcFailFast, key)
                                        }
                                    }
                                    return@withLock
                                }
                            }
                            val failedMsg = policyText(PolicyText(PolicyTextKind.WRITE_FAILED))
                            val failedPayload = pcFailFast?.let {
                                PolicySettled(it.txId, it.key, _state.value.policyDraft, false, MsgKind.FAILED, failedMsg, false)
                            }
                            val stamped = if (pcFailFast != null && failedPayload != null) {
                                emitTxSettledStageOnly(pcFailFast, failedPayload)
                            } else {
                                null
                            }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                val wonLocal = (pcFailFast == null) || activePolicyTx.compareAndSet(pcFailFast, null)
                                if (pcFailFast != null) { policyReconciling.remove(pcFailFast.key) }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                if (wonLocal) {
                                    if (pcFailFast != null && policyTxCoordinator != null && stamped == null) {
                                        applyStageRejectedUnderLock(pcFailFast, key, fenceHeld = true)
                                    } else {
                                        markPolicyUnknownLocked()
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                pendingPolicyWrite = null,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = false,
                                                msg = failedMsg,
                                                msgKind = MsgKind.FAILED,
                                            )
                                        }
                                    }
                                }
                            }
                            if (stamped != null) {
                                policyTxCoordinator?.emitStaged(stamped)
                            }
                        }
                    } finally {
                        policyWriteSaving.remove(key)
                    }
                }
            } finally {
                if (writeLocked) {
                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                }
                val cc = coordClaimedTx
                if (cc != null && cc !== preClaimTx && cc !== handoverTx) {
                    try { cc.ownerAlive.set(false) } catch (_: Exception) { }
                    try { cc.deferred.cancel() } catch (_: Exception) { }
                    try { policyTxCoordinator?.tryCompleteClaim(cc) } catch (_: Exception) { }
                    coordClaimedTx = null
                }
                val pcOuterSave = preClaimTx
                if (pcOuterSave != null && !canJoinTx(pcOuterSave)) {
                    preClaimTx = null
                    inFlightWrites.remove(key)
                    policyWriteSaving.remove(key)
                    try { abortUnarmedTx(pcOuterSave) } catch (_: Exception) { }
                }
                val htx = handoverTx
                var htxRegistered = false
                if (htx != null) {
                    inFlightWrites.remove(htx.key)
                    policyWriteSaving.remove(htx.key)
                    htxRegistered = try {
                        registerHandoverTx(htx, isClear = false)
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    inFlightWrites.remove(key)
                    policyWriteSaving.remove(key)
                }
                // F3：CE 在 SAVING await 期間穿透時，未套用任何終態——loading=true /
                // phase=SAVING 會被本 finally 遺留（legacy DEL／coord==null 路徑沒有
                // recovery hook，phase 會永遠卡在 SAVING）。此處把擱淺的 SAVING 原地
                // 改寫為收斂終態。正常終態都會把 phase 移出 SAVING 或至少清 loading；
                // 三條寫入路徑（confirm／clear／repair）的 unwind 皆以 predicate-gated
                // repair 覆蓋（本區塊／clear finally／repair finally＋repair CE catch），
                // lambda 內再檢一次——合法終態不會被誤覆寫。此分支只可能在
                // mid-await CE unwind，或理論上的 wonLocal==false 殘留（activePolicyTx
                // CAS(pcFast, null) 敗北、終態未被套用時）觸發——兩者都 fail-closed
                // 收斂。htx（timeout handover）由上方區塊註冊，此處不重複註冊；
                // pcOuterSave（armed 但 htx==null、尚未交接）先在此註冊
                // （registerHandoverTx：掛 reconciler＋invokeOnCompletion），註冊成功
                // 才有交接者負責收斂，RECONCILING 才是誠實的；登記被拒／拋出則落回
                // IDLE＋UNKNOWN_REFRESH（P2-A：以回傳值為準，不再假設成功）。
                // handedOff 時保留 cur.msg/msgKind（交接者
                // 收斂時附誠實新終態），否則 UNKNOWN_REFRESH＋CHANNEL。
                // best-effort：不 rethrow，與同 finally 的其他清理一致。
                try {
                    synchronized(policySettleLock) {
                        var armedRegistered = htxRegistered
                        if (!armedRegistered && pcOuterSave != null && _state.value.policyWritePhase == PolicyWritePhase.SAVING) {
                            try {
                                armedRegistered = canJoinTx(pcOuterSave) &&
                                    registerHandoverTx(pcOuterSave, isClear = false)
                            } catch (_: Throwable) {
                                armedRegistered = false
                            }
                        }
                        val handedOff = htxRegistered || armedRegistered
                        failClosedSavingRepairIfAbandoned(handedOff = handedOff, corrupt = false, requireLoading = true)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // 清除草稿：只刪 HookPolicyStore 條目，不清記憶體勾選，不碰裝置寫入路徑
    // (≠卸載≠安裝)。Phase 2 blind-clear 門控：Unknown 下不再靜默單步執行——
    // 第一次呼叫僅告警並 arm，不清 pending、不碰 store、不耗
    // clearGeneration/inFlight；第二次呼叫才執行（明確刪除≠覆寫未見內容，
    // 故第二步不受 unknown 門控阻擋）。Known（或 null-store，無遠端內容可刪）
    // 維持單步。保持 clearGeneration/inFlight 互斥。
    // Foreign-Known exemption (PR #60 Phase 2 裁決，ADR: foreign-Known
    // mid-clear delete semantics): a foreign-Known B committed mid-clear is
    // deleted by design — this is an explicit user delete, not a blind write,
    // and rev-gating the clear against the pre-IO base would wedge permanently
    // under dual-app sync (the other app keeps advancing committedRevision).
    // The rev gate exists only on the FAILED placeholder terminals
    // (confirm/clear/repair) and the repair success claim; the clear/
    // save success side is intentionally unrev-gated. See docs/ARCHITECTURE.md
    // §政策寫入流程 and the A3-3 claim sites.
    // Stage D（有界＋世代綁定）：arm 攜 armedAtMs（nowMonoMs）＋version／freshGen
    // 快照；第二 tap 先判 TTL（now - armedAt > BLIND_CLEAR_TTL_MS→重 arm＋
    // EXPIRED 文案），再判世代（version／freshGen 失配→重 arm＋FIRST 文案），
    // 兩者皆過才 disarm 執行。BUSY／RECONCILING 拒絕一律保留既有 arm（不碰
    // 三元組）；owner-gate 拒絕則 disarm（非 owner 不得殘留確認態）。
    fun clearPolicyDraft() {
        // owner-user 門控 (defense-in-depth)：hook 只讀 user 0；非 owner 的
        // 清除不會被 hook 看到，故直接拒絕且不碰 store。Stage D：同步 disarm
        //（非 owner tap 不得殘留 arm，免切回 owner 後單步誤刪）。
        if (currentUserId() != 0) {
            disarmBlindClear()
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.OWNER_OPERATE)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // A3-5: pending 的確認寫入排隊中時不得靜默 supersede 它 —— 明確拒絕
        // （BUSY），與 request 的既有 pending/in-flight 門控對稱。狀態保持
        // （pending 保留、arm 保留，待確認完成後再清除）。
        if (_state.value.pendingPolicyWrite != null) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        // 同步門控：任何「政策」操作進行中皆拒絕清除,避免覆蓋進行中的寫入;
        // clone/remove/batch 等非政策操作不阻擋 (Phase 2 narrowing)。
        // Stage D：BUSY 保留既有 arm（不碰三元組，待在途結束後仍需二次確認）。
        if (inFlightWrites.any { it.startsWith("policy-") }) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        // Stage A：核對中拒絕清除（BUSY；按鈕亦因非 IDLE 禁用，此為程式化兜底）。
        // Stage D：同上保留 arm。
        if (isPolicyReconcilingLocked()) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        // Stage L4: Corrupt 永不走盲清（盲清僅 Unavailable 瞬斷專用）。
        // Corrupt 雙 tap 仍零 store 觸碰（保 corrupt double-clear 0 契約），
        // 導向修復流程（requestRepair/confirmRepair）。
        if (hookPolicyStore != null && !_state.value.policyDraftKnown && _state.value.policyDraftCorrupt) {
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.CORRUPT_DETECTED)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        if (hookPolicyStore != null && !_state.value.policyDraftKnown) {
            if (!isBlindClearArmed()) {
                // 第一次：僅告警，不執行（不清 pending，不碰 store）。
                armBlindClearLocked()
                _state.update {
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.BLIND_CLEAR_FIRST)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
            // 第二次：先判 TTL，再判世代；任一失敗即重 arm（視為新的第一次），
            // 兩者皆過才 disarm 落到正常清除路徑。
            val now = nowMonoMs()
            val armedAt = blindClearArmedAtMs.get()
            if (now - armedAt > BLIND_CLEAR_TTL_MS) {
                armBlindClearLocked()
                _state.update {
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.BLIND_CLEAR_EXPIRED)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
            if (blindClearArmedVersion.get() != policyDraftVersion.get() ||
                blindClearArmedFreshGen.get() != policyFreshnessGen.get()
            ) {
                armBlindClearLocked()
                _state.update {
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.BLIND_CLEAR_FIRST)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
            // Phase 2 S4：行程級曾觀察到 Known 草稿（coordinator 持有的行程級
            // 「曾經 Known」歷史非空；跨 Activity 重建／重開不變，僅新行程解除）
            // 但遠端目前未知 → 兩次點擊確認不再足夠：先強制一次權威重讀
            // （refreshPolicyDraft），本次點擊不刪除任何內容；只有重讀恢復
            // Known 後下一次清除才走一般 Known 單步路徑（新鮮重讀本身即為
            // 證據）。重讀仍未知 → 中止，草稿維持未知，不盲刪（fail-closed）。
            // SM (session permanence → process-lifetime, intentional): 一旦本行程
            // 觀察過 Known，「refresh-first」狀態即對本行程餘下時間永久生效——
            // 每次雙擊都只觸發重讀而永不盲刪，直到重讀恢復 Known。此為刻意的
            // 行程級（process-lifetime）fail-closed 設計：同行程內 Activity
            // 重開／VM 重建皆不解除（coordinator 持有，GalaxyApp 級生命週期）；
            // 僅新行程（新 coordinator、lastKnown==null）即解除，回復到經典雙擊
            // 盲清。不提供程式化的第三次確認（SM 約定：(a) 文案提供可行的補救、
            // (b) 單測釘住「重讀失敗兩次仍不刪」語義）。
            if (lastKnownDraftObserved()) {
                // consume 本 arm（重新開始）；刷新成 Known 後下一次點擊走 Known 路徑。
                disarmBlindClear()
                try {
                    refreshPolicyDraft()
                } catch (_: Throwable) {
                    // refreshPolicyDraft 為 best-effort；其自身失敗已在 state（msg/Known）揭露。
                    // 本點擊本就不刪除任何內容，故此處不拋出（UI 呼叫路徑）。
                }
                _state.update { cur ->
                    cur.copy(
                        msg = policyText(PolicyText(PolicyTextKind.BLIND_CLEAR_REFRESH_FIRST)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
            // 兩者皆過：consume 後落到正常清除路徑。
            disarmBlindClear()
        } else {
            disarmBlindClear()
        }
        // Phase 2 O3 (S4): commit-time Known fence — a refresh landing between
        // the gate read and the actual store.clear() flips Known→false; the
        // arms below re-check this and abort like the Unknown path instead of
        // deleting content we no longer know. One comment line per check site;
        // the refresh-first abort helper is shared. Armed on Known OR on a
        // process-lifetime observed Known (coordinator-held via lastKnownDraftObserved):
        // the latter is defense-in-depth — today unreachable (the S4 refresh-first gate
        // returns before this capture whenever observed && !known), keeping a
        // fail-closed fence even if that gate ever moves.
        val knownCommitFence = hookPolicyStore != null &&
            (_state.value.policyDraftKnown || lastKnownDraftObserved())
        fun abortBlindClearCommit(key: String) {
            synchronized(policySettleLock) {
                inFlightWrites.remove(key)
                policyWriteSaving.remove(key)
                disarmBlindClear()
                _state.update { cur ->
                    val base = if (cur.policyWritePhase == PolicyWritePhase.QUEUED ||
                        cur.policyWritePhase == PolicyWritePhase.SAVING
                    ) {
                        cur.copy(
                            loading = false,
                            policyWritePhase = PolicyWritePhase.IDLE,
                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                        )
                    } else {
                        cur.copy(loading = false)
                    }
                    base.copy(
                        msg = policyText(PolicyText(PolicyTextKind.BLIND_CLEAR_REFRESH_FIRST)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
            }
            try { refreshPolicyDraft() } catch (_: Throwable) { }
        }
        val key = "policy-clear:${clearGeneration.incrementAndGet()}"
        // A2-4: 鍵由 clearGeneration 單調遞增保證唯一，add 必成功；舊 BUSY
        // 帶不可達死分支，改為單純簿記（其餘 policy-* 觀測者用）。
        inFlightWrites.add(key)
        // Phase 3：clear 在途亦經 QUEUED→SAVING→IDLE（dialog 以 pending==null
        // 關閉，phase 供 positive/negative 門控與「進行中請稍候」守衛觀測）。
        _state.update { cur ->
            if (cur.policyWritePhase != PolicyWritePhase.IDLE) return@update cur
            cur.copy(
                policyWritePhase = PolicyWritePhase.QUEUED,
                policyEpoch = policyPhaseEpoch.incrementAndGet(),
            )
        }
        viewModelScope.launch {
            // Stage A：逾時交接槽（見 confirm）。
            // Stage F1：交接改攜 Tx（clear 句柄保留，sanitized=empty）。
            // Stage H1：同 confirm 前置＋單寫者（clear 口徑失敗即 BUSY；pending
            // 本無，abort 即回 IDLE；VM cancel 不 cancel 已 claim deferred，只
            // 取消 await；finally 不碰 coordinator.activeTx，僅安全解鎖）。
            var handoverTx: PolicyTx? = null
            var writeLocked = false
            var preClaimTx: PolicyTx? = null
            var coordClaimedTx: PolicyTx? = null
            try {
                opMutex.withLock {
                    // Phase 2 O3 (S4): re-check the Known fence at the commit
                    // point — abort like the Unknown path if a refresh flipped it.
                    if (knownCommitFence && !_state.value.policyDraftKnown) {
                        abortBlindClearCommit(key)
                        return@withLock
                    }
                    if (isPolicyReconcilingLocked()) {
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): unconditional cleanup of THIS clear's key — the
                            // in-flight owner (not the clear) owns the lifecycle of any
                            // foreign key; our key must never wedge the process.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update { cur ->
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED && activePolicyTx.get() == null) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                        msgKind = MsgKind.INFO,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                        msgKind = MsgKind.INFO,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    // A3-5 (defensive; entry gate above normally refuses first):
                    // a pending armed mid-flight (only possible via direct test
                    // injection — production request is blocked by our own
                    // in-flight key) must never be silently superseded. Abort the
                    // clear honestly instead of nulling the armed confirm.
                    if (_state.value.pendingPolicyWrite != null) {
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): unconditional cleanup of THIS clear's key — the
                            // pending owner, not the clear, owns the lifecycle in the
                            // QUEUED case; this sliver must not wedge the process.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update { cur ->
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED && activePolicyTx.get() == null) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                        msgKind = MsgKind.INFO,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                        msgKind = MsgKind.INFO,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    val store = hookPolicyStore
                    if (store == null) {
                        synchronized(policySettleLock) {
                            inFlightWrites.remove(key)
                            markPolicyUnknownLocked()
                            _state.update {
                                it.copy(
                                    policyDraftKnown = false,
                                    policyDraftCorrupt = false,
                                    policyWritePhase = PolicyWritePhase.IDLE,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    msg = policyText(PolicyText(PolicyTextKind.NO_STORE_CLEAR)),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                        return@withLock
                    }
                    run {
                        val preJob = viewModelScope.async(Dispatchers.IO) { store.loadResult() }
                        val preRead: LoadResult = try {
                            withTimeout(minOf(policyIoTimeoutMs, 1_000L)) { preJob.await() }
                        } catch (te: TimeoutCancellationException) {
                            try { preJob.cancel() } catch (_: Exception) { }
                            LoadResult.Unavailable
                        } catch (e: CancellationException) {
                            try { preJob.cancel() } catch (_: Exception) { }
                            throw e
                        } catch (_: Exception) {
                            try { preJob.cancel() } catch (_: Exception) { }
                            LoadResult.Unavailable
                        }
                        if (preRead is LoadResult.Unknown) {
                            val preCorrupt = preRead is LoadResult.Corrupt
                            synchronized(policySettleLock) {
                                inFlightWrites.remove(key)
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    val preMsg = policyText(
                                        if (preCorrupt) PolicyText(PolicyTextKind.CORRUPT_DETECTED)
                                        else PolicyText(PolicyTextKind.REFRESH_FAILED),
                                    )
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                        cur.copy(
                                            loading = false,
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = preCorrupt,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = preMsg,
                                            msgKind = MsgKind.CHANNEL,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = preCorrupt,
                                            msg = preMsg,
                                            msgKind = MsgKind.CHANNEL,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                    }
                    var h1ClearDeferred: Deferred<Boolean>? = null
                    var clearRevBeforeProbe: Long? = null
                    val h1ClearCoord = policyTxCoordinator
                    if (h1ClearCoord != null) {
                        if (!h1ClearCoord.writeMutex.tryLock()) {
                            synchronized(policySettleLock) {
                                // A3-5 (SL2): unconditional cleanup of this op's own keys —
                                // this abort ends our key lifecycle, never gate on phase.
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                _state.update { cur ->
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                        writeLocked = true
                        // Phase 2 O3 (S4): commit re-check before the clear arm —
                        // abort like the Unknown path if a refresh flipped Known.
                        if (knownCommitFence && !_state.value.policyDraftKnown) {
                            try { h1ClearCoord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            abortBlindClearCommit(key)
                            return@withLock
                        }
                        val lazyClear = h1ClearCoord.scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            // Phase 2 O3 (S4): last-moment Known re-check inside the IO arm —
                            // refuse the delete (fail-closed) if a refresh flipped Known
                            // since the commit gate; never clear unobserved content.
                            if (knownCommitFence && !_state.value.policyDraftKnown) false else store.clear()
                        }
                        val txClear = PolicyTx(
                            txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                            key = key,
                            deferred = lazyClear,
                            sanitized = emptySet(),
                            store = store,
                            isClear = true,
                            pending = null,
                        )
                        val clearClaimRes = h1ClearCoord.tryClaimWrite(txClear, -1L)
                        if (clearClaimRes != PolicyTxCoordinator.ClaimWriteResult.CLAIMED) {
                            try { lazyClear.cancel() } catch (_: Exception) { }
                            try { h1ClearCoord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            synchronized(policySettleLock) {
                                // A3-5 (SL2): unconditional cleanup of this op's own keys —
                                // this abort ends our key lifecycle, never gate on phase.
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                _state.update { cur ->
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                        coordClaimedTx = txClear
                        val localClearSet = synchronized(policySettleLock) {
                            activePolicyTx.compareAndSet(null, txClear)
                        }
                        if (!localClearSet) {
                            try {
                                withContext(NonCancellable) {
                                    h1ClearCoord.withCommitLock {
                                        try {
                                            if (h1ClearCoord.activeTx.get() === txClear) h1ClearCoord.activeTx.set(null)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                try { h1ClearCoord.tryCompleteClaim(txClear) } catch (_: Exception) { }
                            }
                            coordClaimedTx = null
                            try { lazyClear.cancel() } catch (_: Exception) { }
                            try { h1ClearCoord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            synchronized(policySettleLock) {
                                // A3-5 (SL2): unconditional cleanup of this op's own keys —
                                // this abort ends our key lifecycle, never gate on phase.
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                _state.update { cur ->
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                        // Stage N2 ordering pin (lazyClear fast path): preClaimTx is
                        // assigned HERE, before the commitMutex section below can call
                        // armAndStartTxDeferred(txClear). Every abort/cancel path that
                        // can revoke a PREPARING Tx (finally-block canJoinTx check,
                        // onCleared, foreign-attach abandonment) reads preClaimTx —
                        // abort must precede arm. Never assign preClaimTx after
                        // armAndStartTxDeferred; an armed Tx whose identity was not yet
                        // reachable from preClaimTx would be untracked and the delete
                        // could land after an "aborted" report.
                        h1ClearDeferred = lazyClear
                        preClaimTx = txClear
                        coordClaimedTx = null
                        var preIoAbortClear = false
                        policyRevisionMutex.withLock {
                            try {
                                h1ClearCoord.withCommitLock {
                                    currentCoroutineContext().ensureActive()
                                    drainForeignUpdatesUnderCommitLock(txClear, h1ClearCoord)
                                    val coordActiveClear = try { h1ClearCoord.activeTx.get() } catch (_: Exception) { null }
                                    val coordActiveOkClear = (coordActiveClear == null || coordActiveClear === txClear)
                                    val stagedClear = try { h1ClearCoord.pendingPublish.get() } catch (_: Exception) { null }
                                    val pendingOkClear = (stagedClear == null || (stagedClear.txId == txClear.txId && stagedClear.key == txClear.key))
                                    val snapClear = try { observedSnapshot.get() } catch (_: Exception) { null }
                                    var dropStaleClear = false
                                    if (snapClear != null && snapClear.rev != -1L) {
                                        val curDropClear = try { h1ClearCoord.orphanDropGen.get() } catch (_: Exception) { snapClear.dropGen }
                                        if (curDropClear != snapClear.dropGen) {
                                            val dropRevClear = try { h1ClearCoord.orphanDropRev.get() } catch (_: Exception) { -1L }
                                            if (dropRevClear == -1L || dropRevClear >= snapClear.rev) {
                                                try { policyRevisionStale.set(true) } catch (_: Exception) { }
                                                dropStaleClear = true
                                            } else {
                                                // dropGen is only mutated under commitMutex (recover/discard
                                                // paths); this loop runs inside withCommitLock, so curDropClear
                                                // is stable and the CAS converges or breaks immediately.
                                                var doneC = false
                                                var guardC = 0
                                                while (!doneC && guardC++ < 8) {
                                                    val curS = try { observedSnapshot.get() } catch (_: Exception) { snapClear }
                                                    if (curS.dropGen == curDropClear) break
                                                    val mergedC = if (curS.rev != snapClear.rev) curS.copy(dropGen = curDropClear) else snapClear.copy(dropGen = curDropClear)
                                                    try { doneC = observedSnapshot.compareAndSet(curS, mergedC) } catch (_: Exception) { break }
                                                    if (!doneC && try { observedSnapshot.get().dropGen } catch (_: Exception) { curDropClear } == curDropClear) break
                                                }
                                            }
                                        }
                                    }
                                    if (!coordActiveOkClear || !pendingOkClear || policyRevisionStale.get() || dropStaleClear) {
                                        preIoAbortClear = true
                                    } else {
                                        // P2-2: probe the authoritative revision BEFORE the
                                        // clear IO arms — unverifiable (throws or -1 with a
                                        // source attached) ⇒ abort fail-closed, delete
                                        // never starts.
                                        val probe = authoritativeRevisionSample()
                                        if (probe == null) {
                                            preIoAbortClear = true
                                        } else {
                                            clearRevBeforeProbe = probe
                                            armAndStartTxDeferred(txClear)
                                        }
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                // Fail-closed: never fall back to local-only checks and never arm/start deferred.
                                preIoAbortClear = true
                            }
                        }
                        if (preIoAbortClear) {
                            try { h1ClearCoord.tryCompleteClaim(txClear) } catch (_: Exception) { }
                            if (writeLocked) {
                                try { h1ClearCoord.writeMutex.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            try { lazyClear.cancel() } catch (_: Exception) { }
                            synchronized(policySettleLock) {
                                activePolicyTx.compareAndSet(txClear, null)
                                policyReconciling.remove(txClear.key)
                                inFlightWrites.remove(key)
                                preClaimTx = null
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                    }
                    synchronized(policySettleLock) {
                        _state.update {
                            it.copy(
                                loading = true,
                                msg = "",
                                msgKind = MsgKind.NONE,
                                policyWritePhase = PolicyWritePhase.SAVING,
                            )
                        }
                        // A3-4: clear must hold its key in policyWriteSaving inside the
                        // same settlement-lock block as the QUEUED→SAVING transition
                        // (mirror confirm/repair). The existing finally removes it.
                        policyWriteSaving.add(key)
                    }
                    // A3-3 + P2-2: base revision for the FAILED claim gate. A
                    // non-null coordinator whose sample fails must abort fail-closed
                    // — never proceed to a FAILED terminal with the rev gate opted
                    // out. Null-coordinator legacy keeps the -1 opt-out.
                    val clearRevBefore: Long = clearRevBeforeProbe ?: run {
                        val s = authoritativeRevisionSample()
                        if (s == null) {
                            val pcRevAbort = preClaimTx
                            if (pcRevAbort != null) {
                                try { policyTxCoordinator?.tryCompleteClaim(pcRevAbort) } catch (_: Exception) { }
                            }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            val stopped = cancelAndJoinRepairDeferred(h1ClearDeferred)
                            if (!stopped) {
                                // Cannot confirm the clear stopped; stay fail-closed.
                                try { markPolicyUnknownLocked() } catch (_: Exception) { }
                            }
                            synchronized(policySettleLock) {
                                if (pcRevAbort != null) {
                                    activePolicyTx.compareAndSet(pcRevAbort, null)
                                    policyReconciling.remove(pcRevAbort.key)
                                }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED ||
                                        cur.policyWritePhase == PolicyWritePhase.SAVING
                                    ) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            policyDraftKnown = false,
                                            msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                            msgKind = MsgKind.CHANNEL,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                            msgKind = MsgKind.CHANNEL,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                        s
                    }
                    val deferred: Deferred<Boolean> = h1ClearDeferred ?: viewModelScope.async(Dispatchers.IO) {
                        // Phase 2 O3 (S4): last-moment Known re-check inside the fallback
                        // IO arm — refuse the delete if a refresh flipped Known (fail-closed).
                        if (knownCommitFence && !_state.value.policyDraftKnown) false else store.clear()
                    }
                    val ok: Boolean? = try {
                        withTimeout(policyIoTimeoutMs) {
                            deferred.await()
                        }
                    } catch (te: TimeoutCancellationException) {
                        null
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            if (preClaimTx == null) {
                                try { deferred.cancel() } catch (_: Exception) { }
                            }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            throw e
                        }
                        if (preClaimTx == null) {
                            try { deferred.cancel() } catch (_: Exception) { }
                        }
                        false
                    }
                    if (ok == null) {
                        val htx = preClaimTx ?: PolicyTx(
                            txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                            key = key,
                            deferred = deferred,
                            sanitized = emptySet(),
                            store = store,
                            isClear = true,
                            pending = null,
                            executionArmed = AtomicBoolean(true),
                        )
                        if (writeLocked) {
                            try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                            writeLocked = false
                        }
                        synchronized(policySettleLock) {
                            if (preClaimTx == null) {
                                activePolicyTx.compareAndSet(null, htx)
                            }
                            policyReconciling.add(htx.key)
                            markPolicyUnknownLocked()
                            _state.update {
                                it.copy(
                                    loading = false,
                                    policyDraftKnown = false,
                                    policyDraftCorrupt = false,
                                    policyWritePhase = PolicyWritePhase.RECONCILING,
                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                    msg = policyText(PolicyText(PolicyTextKind.CLEAR_TIMEOUT)),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                        }
                        handoverTx = htx
                        preClaimTx = null
                        return@withLock
                    }
                    if (ok) {
                        val ackJob = viewModelScope.async(Dispatchers.IO) { store.loadResult() }
                        val ackRead: LoadResult = try {
                            withTimeout(minOf(policyIoTimeoutMs, 1_000L)) { ackJob.await() }
                        } catch (te: TimeoutCancellationException) {
                            try { ackJob.cancel() } catch (_: Exception) { }
                            LoadResult.Unavailable
                        } catch (e: CancellationException) {
                            try { ackJob.cancel() } catch (_: Exception) { }
                            val pc = preClaimTx
                            if (pc != null) {
                                handoverTx = pc
                                preClaimTx = null
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                            }
                            throw e
                        } catch (_: Exception) {
                            try { ackJob.cancel() } catch (_: Exception) { }
                            LoadResult.Unavailable
                        }
                        val staleFallbackHandover = policyRevisionStale.get() && ackRead !is LoadResult.Known
                        val unknownHandover = ackRead is LoadResult.Unknown
                        if (staleFallbackHandover || unknownHandover) {
                            val ackCorrupt = ackRead is LoadResult.Corrupt && !staleFallbackHandover
                            val htx = preClaimTx ?: PolicyTx(
                                txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                                key = key,
                                deferred = deferred,
                                sanitized = emptySet(),
                                store = store,
                                isClear = true,
                                pending = null,
                                executionArmed = AtomicBoolean(true),
                            )
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                if (preClaimTx == null) {
                                    activePolicyTx.compareAndSet(null, htx)
                                }
                                policyReconciling.add(htx.key)
                                markPolicyUnknownLocked()
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = ackCorrupt,
                                        policyWritePhase = PolicyWritePhase.RECONCILING,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.CLEAR_TIMEOUT)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            handoverTx = htx
                            preClaimTx = null
                            return@withLock
                        }
                        val pcClearFast = preClaimTx
                        if (pcClearFast != null && !claimForPublish(pcClearFast)) {
                            try { ackJob.cancel() } catch (_: Exception) { }
                            preClaimTx = null
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            // 成功側 claimForPublish 的 revBefore=null 恆為 unconditional
                            // CAS；reject 只可能是 CAS-loss＝live winner 消費 claim
                            // （loser handling 已在 tryClaimStagedVerdict 內完成）—— 跟隨
                            // winner 收斂，不得 abort 至 UNKNOWN_REFRESH。
                            return@withLock
                        }
                        val finalDraft = if (ackRead is LoadResult.Known && ackRead.packages.isNotEmpty()) {
                            ackRead.packages
                        } else {
                            emptySet()
                        }
                        val finalMsg = if (ackRead is LoadResult.Known && ackRead.packages.isNotEmpty()) {
                            policyText(PolicyText(PolicyTextKind.REFRESHED))
                        } else {
                            policyText(PolicyText(PolicyTextKind.CLEAR_SUCCESS))
                        }
                        val payload = pcClearFast?.let {
                            PolicySettled(it.txId, it.key, finalDraft, true, MsgKind.INFO, finalMsg, true)
                        }
                        val stamped = if (pcClearFast != null && payload != null) {
                            emitTxSettledStageOnly(pcClearFast, payload)
                        } else {
                            null
                        }
                        if (writeLocked) {
                            try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                            writeLocked = false
                        }
                        synchronized(policySettleLock) {
                            val wonLocal = (pcClearFast == null) || activePolicyTx.compareAndSet(pcClearFast, null)
                            if (pcClearFast != null) { policyReconciling.remove(pcClearFast.key) }
                            inFlightWrites.remove(key)
                            preClaimTx = null
                            if (wonLocal) {
                                if (pcClearFast != null && policyTxCoordinator != null && stamped == null) {
                                    applyStageRejectedUnderLock(pcClearFast, key, fenceHeld = true)
                                } else {
                                    // F4 (F2/F9): the VM's own successful confirm/clear is an
                                    // authoritative observation of the committed draft —
                                    // clear the stale flag (unconditional, matching the
                                    // foreign-known-settle handler). markObservedKnown() is
                                    // deliberately NOT called here. When stamped != null,
                                    // observedSnapshot was already set to stamped.rev inside
                                    // emitTxSettledStageOnly (no sampling race), so a mark
                                    // would be redundant AND could overshoot observed to an
                                    // unseen FOREIGN B commit. When stamped == null (no staged
                                    // payload): (a) coord==null legacy → sampledCurrentRevision()
                                    // returns -1 → mark is a no-op; (b) claimless-with-coordinator
                                    // edge → sampledCurrentRevision() returns committedRevision()
                                    // (>= 0, unconditionally assigned, no max/merge) and COULD
                                    // absorb a foreign commit. The defense is therefore that this
                                    // call site NEVER marks; the no-mark rule is the contract,
                                    // not an impossibility proof.
                                    try { policyRevisionStale.set(false) } catch (_: Exception) { }
                                    bumpPolicyGenerations()
                                    disarmBlindClear()
                                    try { noteLastKnownDraft(finalDraft) } catch (_: Exception) { }
                                    _state.update {
                                        it.copy(
                                            loading = false,
                                            policyDraft = finalDraft,
                                            policyDraftKnown = true,
                                            policyDraftCorrupt = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = finalMsg,
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                }
                            }
                        }
                        if (stamped != null) {
                            policyTxCoordinator?.emitStaged(stamped)
                        }
                    } else {
                        // Phase 2 O3 (S4): the in-IO-arm fence refused the delete
                        // (knownCommitFence flipped to !Known before/at IO time) —
                        // publish NO FAILED/CLEAR_FAILED terminal. Settle with the
                        // same shape as the outer fence sites: claim completed, fence
                        // cleared, BLIND_CLEAR_REFRESH_FIRST + CHANNEL, forced refresh
                        // afterward. The delete never ran (clearCalls stays 0).
                        if (knownCommitFence && !_state.value.policyDraftKnown) {
                            val pcFenceAbort = preClaimTx
                            if (pcFenceAbort != null) {
                                try { policyTxCoordinator?.tryCompleteClaim(pcFenceAbort) } catch (_: Exception) { }
                            }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                if (pcFenceAbort != null) {
                                    activePolicyTx.compareAndSet(pcFenceAbort, null)
                                    policyReconciling.remove(pcFenceAbort.key)
                                }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                disarmBlindClear()
                                _state.update { cur ->
                                    val base = if (cur.policyWritePhase == PolicyWritePhase.QUEUED ||
                                        cur.policyWritePhase == PolicyWritePhase.SAVING
                                    ) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        )
                                    } else {
                                        cur.copy(loading = false)
                                    }
                                    base.copy(
                                        msg = policyText(PolicyText(PolicyTextKind.BLIND_CLEAR_REFRESH_FIRST)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            try { refreshPolicyDraft() } catch (_: Throwable) { }
                            return@withLock
                        }
                        val pcClearFailFast = preClaimTx
                        // A3-3: gate the FAILED placeholder claim on the revision
                        // sampled before the clear IO — a foreign B committing
                        // between the failed clear and this claim bumps
                        // committedRevision and the terminal is rejected.
                        if (pcClearFailFast != null) {
                            val claimVerdict = claimForPublishCheckedVerdict(pcClearFailFast, clearRevBefore)
                            if (claimVerdict != PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED) {
                                preClaimTx = null
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                                // A3-3 (C1): settle ONLY on rev-gate rejection (foreign B
                                // committed mid-IO — no winner exists); CAS_LOSS means a
                                // live winner consumed the claim and loser-follow already
                                // ran inside claimForPublishCheckedVerdict.
                                if (claimVerdict == PolicyTxCoordinator.ClaimStagedVerdict.REV_GATE_REJECTED) {
                                    synchronized(policySettleLock) {
                                        applyStageRejectedUnderLock(pcClearFailFast, key)
                                    }
                                }
                                return@withLock
                            }
                        }
                        val failedMsg = policyText(PolicyText(PolicyTextKind.CLEAR_FAILED))
                        val failedPayload = pcClearFailFast?.let {
                            PolicySettled(it.txId, it.key, _state.value.policyDraft, false, MsgKind.FAILED, failedMsg, true)
                        }
                        val stamped = if (pcClearFailFast != null && failedPayload != null) {
                            emitTxSettledStageOnly(pcClearFailFast, failedPayload)
                        } else {
                            null
                        }
                        if (writeLocked) {
                            try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                            writeLocked = false
                        }
                        synchronized(policySettleLock) {
                            val wonLocal = (pcClearFailFast == null) || activePolicyTx.compareAndSet(pcClearFailFast, null)
                            if (pcClearFailFast != null) { policyReconciling.remove(pcClearFailFast.key) }
                            inFlightWrites.remove(key)
                            preClaimTx = null
                            if (wonLocal) {
                                if (pcClearFailFast != null && policyTxCoordinator != null && stamped == null) {
                                    applyStageRejectedUnderLock(pcClearFailFast, key, fenceHeld = true)
                                } else {
                                    markPolicyUnknownLocked()
                                    _state.update {
                                        it.copy(
                                            loading = false,
                                            policyDraftKnown = false,
                                            policyDraftCorrupt = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = failedMsg,
                                            msgKind = MsgKind.FAILED,
                                        )
                                    }
                                }
                            }
                        }
                        if (stamped != null) {
                            policyTxCoordinator?.emitStaged(stamped)
                        }
                    }
                }
            } finally {
                if (writeLocked) {
                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                }
                val cc = coordClaimedTx
                if (cc != null && cc !== preClaimTx && cc !== handoverTx) {
                    try { cc.ownerAlive.set(false) } catch (_: Exception) { }
                    try { cc.deferred.cancel() } catch (_: Exception) { }
                    try { policyTxCoordinator?.tryCompleteClaim(cc) } catch (_: Exception) { }
                    coordClaimedTx = null
                }
                val pcOuterClear = preClaimTx
                if (pcOuterClear != null && !canJoinTx(pcOuterClear)) {
                    preClaimTx = null
                    inFlightWrites.remove(key)
                    policyWriteSaving.remove(key)
                    try { abortUnarmedTx(pcOuterClear) } catch (_: Exception) { }
                }
                val htx = handoverTx
                var htxRegistered = false
                if (htx != null) {
                    inFlightWrites.remove(htx.key)
                    policyWriteSaving.remove(htx.key)
                    htxRegistered = try {
                        registerHandoverTx(htx, isClear = true)
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    inFlightWrites.remove(key)
                    policyWriteSaving.remove(key)
                }
                // F3：CE 在 SAVING await 期間穿透（legacy DEL／coord==null 路徑，
                // 無 recovery hook）——loading=true／phase=SAVING 會被本 finally
                // 遺留。此處把擱淺的 SAVING 原地改寫為收斂終態。正常終態都會把
                // phase 移出 SAVING，predicate（phase==SAVING）在正常終態下不可
                // 見；helper lambda 內再檢一次。htx（timeout handover）由上方區塊
                // 註冊，此處不重複註冊；pcOuterClear（armed 但 htx==null、尚未
                // 交接）在 phase 仍為 SAVING 時先在此註冊（registerHandoverTx：
                // 掛 reconciler＋invokeOnCompletion），註冊成功 RECONCILING 才
                // 誠實（有交接者負責收斂）；登記被拒／拋出則落回 IDLE＋UNKNOWN_REFRESH
                // （P2-A：以回傳值為準，不再假設成功）。
                // best-effort：不 rethrow，與同 finally 的其他清理一致。
                try {
                    synchronized(policySettleLock) {
                        var armedRegistered = htxRegistered
                        if (!armedRegistered && pcOuterClear != null &&
                            _state.value.policyWritePhase == PolicyWritePhase.SAVING
                        ) {
                            try {
                                armedRegistered = canJoinTx(pcOuterClear) &&
                                    registerHandoverTx(pcOuterClear, isClear = true)
                            } catch (_: Throwable) {
                                armedRegistered = false
                            }
                        }
                        val handedOff = htxRegistered || armedRegistered
                        failClosedSavingRepairIfAbandoned(handedOff = handedOff, corrupt = false, requireLoading = false)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // Stage L4 P2#3 corrupt-repair 兩步確認（與盲清 Unavailable 平行，獨立 arm）：
    // - requestRepairCorruptDraft：第一次 tap，Corrupt 才 arm＋CORRUPT_REPAIR_FIRST；
    //   Known（無損壞）/Unavailable/無 store/非 owner/BUSY/RECONCILING 一律拒絕
    //  （不 arm、不碰 store）。BUSY/RECONCILING 保留既有 arm。
    // - confirmRepairCorruptDraft：第二次 tap，TTL＋世代雙檢後執行修復：
    //   跳過 Known pre-read（Corrupt 永不 Known），直接 onIo 調用
    //   store.repairCorrupt()（有界 policyIoTimeoutMs），成功即
    //   CORRUPT_REPAIRED＋Known(empty)＋雙 disarm＋版本 bump，不成功即
    //   FAILED＋滯留 Corrupt（不碰 draft 舊快照）。
    // 兩者永不 auto-called store 修復以外的寫入；repair 結果經版本 bump fence
    // 在途舊 Known load。
    fun requestRepairCorruptDraft() {
        if (currentUserId() != 0) {
            disarmCorruptRepair()
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.OWNER_OPERATE)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // A3-5 symmetry (O2): a queued confirm owns the policy writers — refuse
        // repair with BUSY while one is pending (same gate as clear); the arm and
        // the pending are both preserved.
        if (_state.value.pendingPolicyWrite != null) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        if (inFlightWrites.any { it.startsWith("policy-") }) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        if (isPolicyReconcilingLocked()) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        val store = hookPolicyStore
        if (store == null) {
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.NO_STORE_READ)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        if (_state.value.policyDraftKnown || !_state.value.policyDraftCorrupt) {
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // Stage M1 P1: stale revision (unseen foreign settled) forces explicit
        // re-read before a new repair arm (same fail-closed as requestPolicyWrite).
        if (policyRevisionStale.get()) {
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // 第一次：僅告警並 arm，不碰 store。
        if (!armCorruptRepairLocked()) {
            // P2-2: authoritative revision unverifiable — fail closed.
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        _state.update {
            it.copy(
                msg = policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FIRST)),
                msgKind = MsgKind.CHANNEL,
            )
        }
    }

    fun confirmRepairCorruptDraft() {
        if (currentUserId() != 0) {
            disarmCorruptRepair()
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.OWNER_OPERATE)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // A3-5 symmetry (O2): a queued confirm owns the policy writers — refuse
        // repair with BUSY while one is pending (same gate as clear); the arm and
        // the pending are both preserved.
        if (_state.value.pendingPolicyWrite != null) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        if (inFlightWrites.any { it.startsWith("policy-") }) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        if (isPolicyReconcilingLocked()) {
            _state.update {
                it.copy(msg = policyText(PolicyText(PolicyTextKind.BUSY)), msgKind = MsgKind.INFO)
            }
            return
        }
        // Stage M1 P1: stale revision short-circuits before any arm/TTL work
        // (old repair base is obsolete; require explicit refresh first).
        if (policyRevisionStale.get()) {
            disarmCorruptRepair()
            markPolicyUnknownLocked()
            _state.update {
                it.copy(
                    policyDraftKnown = false,
                    // preserve on stale abort, never wash nor fabricate.
                    policyDraftCorrupt = it.policyDraftCorrupt,
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        val store = hookPolicyStore
        if (store == null) {
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.NO_STORE_READ)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        if (_state.value.policyDraftKnown || !_state.value.policyDraftCorrupt) {
            disarmCorruptRepair()
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        if (!isCorruptRepairArmed()) {
            if (!armCorruptRepairLocked()) {
                // P2-2: authoritative revision unverifiable — fail closed.
                _state.update {
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FIRST)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        val now = nowMonoMs()
        val armedAt = corruptRepairArmedAtMs.get()
        if (now - armedAt > BLIND_CLEAR_TTL_MS) {
            if (!armCorruptRepairLocked()) {
                // P2-2: authoritative revision unverifiable — fail closed.
                _state.update {
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.BLIND_CLEAR_EXPIRED)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        if (corruptRepairArmedVersion.get() != policyDraftVersion.get() ||
            corruptRepairArmedFreshGen.get() != policyFreshnessGen.get()
        ) {
            if (!armCorruptRepairLocked()) {
                // P2-2: authoritative revision unverifiable — fail closed.
                _state.update {
                    it.copy(
                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                        msgKind = MsgKind.CHANNEL,
                    )
                }
                return
            }
            _state.update {
                it.copy(
                    msg = policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FIRST)),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return
        }
        // Stage L4: snapshot the armed generation for post-lock re-validation
        // (disarm below wipes the arm; the queue window may have changed state).
        // Stage M1 P1: also snapshot the armed coordinator base revision.
        val armedRepairVersion = corruptRepairArmedVersion.get()
        val armedRepairFreshGen = corruptRepairArmedFreshGen.get()
        val armedRepairBaseRev = corruptRepairArmedBaseRev.get()
        disarmCorruptRepair()
        val key = "policy-repair:${clearGeneration.incrementAndGet()}"
        // A2-4: 鍵由 clearGeneration 單調遞增保證唯一，add 必成功；舊 BUSY
        // 帶不可達死分支，改為單純簿記（其餘 policy-* 觀測者用）。
        inFlightWrites.add(key)
        _state.update { cur ->
            if (cur.policyWritePhase != PolicyWritePhase.IDLE) return@update cur
            cur.copy(
                policyWritePhase = PolicyWritePhase.QUEUED,
                policyEpoch = policyPhaseEpoch.incrementAndGet(),
            )
        }
        viewModelScope.launch {
            // Stage M1 P1: repair is a first-class destructive PolicyTx (same
            // fencing as save/clear). Stage M2 P2#3: timeout hands over like
            // save/clear (RECONCILING + handoverTx + reconciler, never settle
            // on unknown); writeLocked tracks the app-scoped single
            // writer; preClaimTx tracks the claimed Tx for rollback/emit/settle.
            var handoverTx: PolicyTx? = null
            var writeLocked = false
            var preClaimTx: PolicyTx? = null
            var coordClaimedTx: PolicyTx? = null
            // P2-B：CE catch 與 outer finally 共用的 handover 登記（兩條 unwind 路徑
            // 行為一致，禁止各自重複發明；冪等、每條路徑最多真正登記一次）。登記順序
            // 先 htx（timeout handover 已就位），否則仍未登記的 armed Tx（preClaimTx、
            // phase==SAVING）於此刻登記。回傳 true＝handover 就位（登記＋reconciler
            // 已掛）；false＝未登記（本地 CAS 敗於 winner／coordinator CAS 敗已回滾／
            // unarmed abort／phase 已非 SAVING）。htx 與 armed 登記一律以
            // registerHandoverTx 的回傳值為準（P2-A），不再假設成功。
            var htxRegistered = false
            var htxTried = false
            var armedRegistered = false
            val tryArmedRegister: () -> Boolean = {
                if (!htxTried) {
                    htxTried = true
                    val htx = handoverTx
                    if (htx != null) {
                        inFlightWrites.remove(htx.key)
                        policyWriteSaving.remove(htx.key)
                        htxRegistered = try {
                            registerHandoverTx(htx, isClear = true)
                        } catch (_: Throwable) {
                            false
                        }
                    } else {
                        inFlightWrites.remove(key)
                        policyWriteSaving.remove(key)
                    }
                }
                if (!htxRegistered && !armedRegistered) {
                    val pc = preClaimTx
                    if (pc != null && _state.value.policyWritePhase == PolicyWritePhase.SAVING) {
                        armedRegistered = try {
                            canJoinTx(pc) && registerHandoverTx(pc, isClear = true)
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }
                htxRegistered || armedRegistered
            }
            try {
                opMutex.withLock {
                    if (isPolicyReconcilingLocked()) {
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): unconditional cleanup of THIS repair's key —
                            // the in-flight owner (not the repair) owns the lifecycle
                            // of any foreign key; our key must never wedge the process.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update { cur ->
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED && activePolicyTx.get() == null) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                        msgKind = MsgKind.INFO,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                        msgKind = MsgKind.INFO,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    val repairCur = _state.value
                    if (repairCur.policyDraftKnown || !repairCur.policyDraftCorrupt) {
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): this op's own keys, abort path — foreign keys
                            // are owned by the pending/in-flight op, never touched here.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update { cur ->
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    if (policyDraftVersion.get() != armedRepairVersion ||
                        policyFreshnessGen.get() != armedRepairFreshGen
                    ) {
                        // P2-2: re-arm fails closed when the authoritative revision
                        // is unverifiable — publish UNKNOWN_REFRESH, never FIRST.
                        val reArmOk = armCorruptRepairLocked()
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): this op's own keys, abort path — foreign keys
                            // are owned by the pending/in-flight op, never touched here.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            _state.update { cur ->
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(if (reArmOk) PolicyTextKind.CORRUPT_REPAIR_FIRST else PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        msg = policyText(PolicyText(if (reArmOk) PolicyTextKind.CORRUPT_REPAIR_FIRST else PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    if (policyRevisionStale.get()) {
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): this op's own keys, abort path — foreign keys
                            // are owned by the pending/in-flight op, never touched here.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            markPolicyUnknownLocked()
                            _state.update { cur ->
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        // Repair is only reachable when corrupt==true (gated above):
                                        // preserve corrupt on stale abort, never wash to false.
                                        policyDraftCorrupt = true,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        policyDraftKnown = false,
                                        // Repair is only reachable when corrupt==true (gated above):
                                        // preserve corrupt on stale abort, never wash to false.
                                        policyDraftCorrupt = true,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    val preJob = viewModelScope.async(Dispatchers.IO) { store.loadForRepair() }
                    val preRead: LoadResult = try {
                        withTimeout(minOf(policyIoTimeoutMs, 1_000L)) { preJob.await() }
                    } catch (te: TimeoutCancellationException) {
                        try { preJob.cancel() } catch (_: Exception) { }
                        LoadResult.Unavailable
                    } catch (e: CancellationException) {
                        try { preJob.cancel() } catch (_: Exception) { }
                        throw e
                    } catch (_: Exception) {
                        try { preJob.cancel() } catch (_: Exception) { }
                        LoadResult.Unavailable
                    }
                    if (preRead is LoadResult.Known) {
                        // Verified Known may clear corrupt (fail-closed): authoritative
                        // pre-read proves healed, so clear the bit. Stays Unknown
                        // (requires explicit refresh to adopt the healed value);
                        // only Unavailable below must retain corrupt.
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): this op's own keys, abort path — foreign keys
                            // are owned by the pending/in-flight op, never touched here.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            markPolicyUnknownLocked()
                            _state.update { cur ->
                                val knownMsg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH))
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = knownMsg,
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = knownMsg,
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    if (preRead is LoadResult.Unavailable) {
                        // P2 retention: transient Unavailable never proves healed.
                        // Preserve corrupt evidence + bump generations (fail-closed
                        // fence) so the next tap still routes to repair request.
                        synchronized(policySettleLock) {
                            // A3-5 (SL2): this op's own keys, abort path — foreign keys
                            // are owned by the pending/in-flight op, never touched here.
                            inFlightWrites.remove(key)
                            policyWriteSaving.remove(key)
                            markPolicyUnknownLocked()
                            _state.update { cur ->
                                val preMsg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED))
                                if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = true,
                                        msg = preMsg,
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                } else {
                                    cur.copy(
                                        loading = false,
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = true,
                                        msg = preMsg,
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                        }
                        return@withLock
                    }
                    var h1RepairDeferred: Deferred<Boolean>? = null
                    var repairRevBeforeProbe: Long? = null
                    // A3-1: fast-path 需要知道修復是「Repaired」還是「RepairedNoBackup」
                    // 才能誠實揭露備份失敗 —— 結果寫入此 ref（同一 ref 同時掛在
                    // PolicyTx.repairResult 上，跨 VM 終態據此推導；fast path 分支
                    // 直接讀同一 ref）（PolicyTx.deferred 為 Deferred<Boolean>，不變更其型別）。
                    val repairResultRef = AtomicReference<RepairResult?>(null)
                    val h1RepairCoord = policyTxCoordinator
                    if (h1RepairCoord != null) {
                        if (!h1RepairCoord.writeMutex.tryLock()) {
                            synchronized(policySettleLock) {
                                // A3-5 (SL2): this op's own keys, abort path — foreign keys
                                // are owned by the pending/in-flight op, never touched here.
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                _state.update { cur ->
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                        writeLocked = true
                        val lazy = h1RepairCoord.scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            val r = try {
                                store.repairCorrupt()
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                RepairResult.RepairFailed
                            }
                            try {
                                repairResultRef.set(r)
                            } catch (_: Exception) {
                            }
                            r is RepairResult.Repaired || r is RepairResult.RepairedNoBackup
                        }
                        val tx = PolicyTx(
                            txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                            key = key,
                            deferred = lazy,
                            sanitized = emptySet(),
                            store = store,
                            isClear = true,
                            pending = null,
                            isRepair = true,
                            repairResult = repairResultRef,
                        )
                        val h1ClaimRes = h1RepairCoord.tryClaimWrite(tx, armedRepairBaseRev)
                        if (h1ClaimRes == PolicyTxCoordinator.ClaimWriteResult.BUSY) {
                            try { lazy.cancel() } catch (_: Exception) { }
                            try { h1RepairCoord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            synchronized(policySettleLock) {
                                // A3-5 (SL2): this op's own keys, abort path — foreign keys
                                // are owned by the pending/in-flight op, never touched here.
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                _state.update { cur ->
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                        if (h1ClaimRes == PolicyTxCoordinator.ClaimWriteResult.STALE) {
                            try { lazy.cancel() } catch (_: Exception) { }
                            try { h1RepairCoord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            synchronized(policySettleLock) {
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        // Repair is only reachable when corrupt==true (gated above +
                                        // authoritative loadForRepair proved Corrupt; unseen B
                                        // does not prove healed): preserve corrupt, never wash to false.
                                        policyDraftCorrupt = true,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                        coordClaimedTx = tx
                        val localRepairSet = synchronized(policySettleLock) {
                            activePolicyTx.compareAndSet(null, tx)
                        }
                        if (!localRepairSet) {
                            try {
                                withContext(NonCancellable) {
                                    h1RepairCoord.withCommitLock {
                                        try {
                                            if (h1RepairCoord.activeTx.get() === tx) h1RepairCoord.activeTx.set(null)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                try { h1RepairCoord.tryCompleteClaim(tx) } catch (_: Exception) { }
                            }
                            coordClaimedTx = null
                            try { lazy.cancel() } catch (_: Exception) { }
                            try { h1RepairCoord.writeMutex.unlock() } catch (_: Exception) { }
                            writeLocked = false
                            synchronized(policySettleLock) {
                                // A3-5 (SL2): this op's own keys, abort path — foreign keys
                                // are owned by the pending/in-flight op, never touched here.
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                _state.update { cur ->
                                    if (cur.policyWritePhase == PolicyWritePhase.QUEUED) {
                                        cur.copy(
                                            loading = false,
                                            policyWritePhase = PolicyWritePhase.IDLE,
                                            policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    } else {
                                        cur.copy(
                                            loading = false,
                                            msg = policyText(PolicyText(PolicyTextKind.BUSY)),
                                            msgKind = MsgKind.INFO,
                                        )
                                    }
                                }
                            }
                            return@withLock
                        }
                        h1RepairDeferred = lazy
                        preClaimTx = tx
                        coordClaimedTx = null
                        var preIoAbortRepair = false
                        policyRevisionMutex.withLock {
                            try {
                                h1RepairCoord.withCommitLock {
                                    currentCoroutineContext().ensureActive()
                                    drainForeignUpdatesUnderCommitLock(tx, h1RepairCoord)
                                    val baseMismatchPreIo = armedRepairVersion != -1L &&
                                        (armedRepairVersion != policyDraftVersion.get() ||
                                            armedRepairFreshGen != policyFreshnessGen.get())
                                    val coordActive = try { h1RepairCoord.activeTx.get() } catch (_: Exception) { null }
                                    val coordActiveOk = (coordActive == null || coordActive === tx)
                                    val revOk = (armedRepairBaseRev == -1L ||
                                        h1RepairCoord.committedRevision.get() == armedRepairBaseRev)
                                    val staged = try { h1RepairCoord.pendingPublish.get() } catch (_: Exception) { null }
                                    val pendingOk = (staged == null || (staged.txId == tx.txId && staged.key == tx.key))
                                    val snapRep = try { observedSnapshot.get() } catch (_: Exception) { null }
                                    var dropStaleRep = false
                                    if (snapRep != null && snapRep.rev != -1L) {
                                        val curDropRep = try { h1RepairCoord.orphanDropGen.get() } catch (_: Exception) { snapRep.dropGen }
                                        if (curDropRep != snapRep.dropGen) {
                                            val dropRevRep = try { h1RepairCoord.orphanDropRev.get() } catch (_: Exception) { -1L }
                                            if (dropRevRep == -1L || dropRevRep >= snapRep.rev) {
                                                try { policyRevisionStale.set(true) } catch (_: Exception) { }
                                                dropStaleRep = true
                                            } else {
                                                // dropGen is only mutated under commitMutex (recover/discard
                                                // paths); this loop runs inside withCommitLock, so curDropRep
                                                // is stable and the CAS converges or breaks immediately.
                                                var doneR = false
                                                var guardR = 0
                                                while (!doneR && guardR++ < 8) {
                                                    val curS = try { observedSnapshot.get() } catch (_: Exception) { snapRep }
                                                    if (curS.dropGen == curDropRep) break
                                                    val mergedR = if (curS.rev != snapRep.rev) curS.copy(dropGen = curDropRep) else snapRep.copy(dropGen = curDropRep)
                                                    try { doneR = observedSnapshot.compareAndSet(curS, mergedR) } catch (_: Exception) { break }
                                                    if (!doneR && try { observedSnapshot.get().dropGen } catch (_: Exception) { curDropRep } == curDropRep) break
                                                }
                                            }
                                        }
                                    }
                                    if (!coordActiveOk || !revOk || !pendingOk || baseMismatchPreIo || policyRevisionStale.get() || dropStaleRep) {
                                        preIoAbortRepair = true
                                    } else {
                                        // P2-2: probe the authoritative revision BEFORE any
                                        // IO arms — unverifiable (throws or -1 with a source
                                        // attached) ⇒ abort fail-closed, no IO starts.
                                        val probe = authoritativeRevisionSample()
                                        if (probe == null) {
                                            preIoAbortRepair = true
                                        } else {
                                            repairRevBeforeProbe = probe
                                        }
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                // Fail-closed: never fall back to local-only checks and never proceed.
                                preIoAbortRepair = true
                            }
                        }
                        if (preIoAbortRepair) {
                            try { h1RepairCoord.tryCompleteClaim(tx) } catch (_: Exception) { }
                            if (writeLocked) {
                                try { h1RepairCoord.writeMutex.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            try { lazy.cancel() } catch (_: Exception) { }
                            synchronized(policySettleLock) {
                                activePolicyTx.compareAndSet(tx, null)
                                policyReconciling.remove(tx.key)
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        // Repair is only reachable when corrupt==true (gated above):
                                        // preserve corrupt on pre-IO abort, never wash to false.
                                        policyDraftCorrupt = true,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                    }
                    synchronized(policySettleLock) {
                        _state.update {
                            it.copy(
                                loading = true,
                                msg = "",
                                msgKind = MsgKind.NONE,
                                policyWritePhase = PolicyWritePhase.SAVING,
                            )
                        }
                        policyWriteSaving.add(key)
                    }
                    try {
                        // P2-2: authoritative revision for the repair's rev gates. A
                        // non-null coordinator whose sample fails must abort fail-closed
                        // — never proceed to a FAILED/SUCCESS claim with the rev gate
                        // opted out. Null-coordinator legacy keeps the -1 opt-out.
                        val repairRevBefore: Long = repairRevBeforeProbe ?: run {
                            val s = authoritativeRevisionSample()
                            if (s == null) {
                                val pcRevAbort = preClaimTx
                                if (pcRevAbort != null) {
                                    try { policyTxCoordinator?.tryCompleteClaim(pcRevAbort) } catch (_: Exception) { }
                                }
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                                val stopped = cancelAndJoinRepairDeferred(h1RepairDeferred)
                                if (!stopped) {
                                    // Cannot confirm the repair stopped; stay fail-closed.
                                    try { markPolicyUnknownLocked() } catch (_: Exception) { }
                                }
                                synchronized(policySettleLock) {
                                    if (pcRevAbort != null) {
                                        activePolicyTx.compareAndSet(pcRevAbort, null)
                                        policyReconciling.remove(pcRevAbort.key)
                                    }
                                    inFlightWrites.remove(key)
                                    policyWriteSaving.remove(key)
                                    preClaimTx = null
                                    markPolicyUnknownLocked()
                                    _state.update { cur ->
                                        if (cur.policyWritePhase == PolicyWritePhase.QUEUED ||
                                            cur.policyWritePhase == PolicyWritePhase.SAVING
                                        ) {
                                            cur.copy(
                                                loading = false,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = true,
                                                msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        } else {
                                            cur.copy(
                                                loading = false,
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = true,
                                                msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                                msgKind = MsgKind.CHANNEL,
                                            )
                                        }
                                    }
                                }
                                return@withLock
                            }
                            s
                        }
                        val finalJob = viewModelScope.async(Dispatchers.IO) { store.loadForRepair() }
                        val finalRead: LoadResult = try {
                            withTimeout(minOf(policyIoTimeoutMs, 1_000L)) { finalJob.await() }
                        } catch (te: TimeoutCancellationException) {
                            try { finalJob.cancel() } catch (_: Exception) { }
                            LoadResult.Unavailable
                        } catch (e: CancellationException) {
                            try { finalJob.cancel() } catch (_: Exception) { }
                            throw e
                        } catch (_: Exception) {
                            try { finalJob.cancel() } catch (_: Exception) { }
                            LoadResult.Unavailable
                        }
                        if (finalRead is LoadResult.Known) {
                            // Verified Known may clear corrupt (fail-closed): fresh
                            // revalidation proves healed, so clear the bit. Stays
                            // Unknown (requires explicit refresh to adopt value);
                            // only Unavailable below must retain corrupt.
                            val pcKnown = preClaimTx
                            if (pcKnown != null) {
                                try { policyTxCoordinator?.tryCompleteClaim(pcKnown) } catch (_: Exception) { }
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                            }
                            val repairStopped = cancelAndJoinRepairDeferred(h1RepairDeferred)
                            if (!repairStopped) {
                                // A3-7: cannot confirm the blocking repair stopped;
                                // stay fail-closed (stale → next write/confirm requires refresh).
                                try { markPolicyUnknownLocked() } catch (_: Exception) { }
                            }
                            synchronized(policySettleLock) {
                                if (pcKnown != null) {
                                    activePolicyTx.compareAndSet(pcKnown, null)
                                    policyReconciling.remove(pcKnown.key)
                                    preClaimTx = null
                                }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = false,
                                        msg = policyText(PolicyText(PolicyTextKind.UNKNOWN_REFRESH)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                        if (finalRead is LoadResult.Unavailable) {
                            // P2 retention: transient Unavailable never proves healed.
                            // Preserve corrupt evidence + bump generations (fail-closed
                            // fence); do NOT delete. Next tap still routes to repair.
                            val pcAbort = preClaimTx
                            if (pcAbort != null) {
                                try { policyTxCoordinator?.tryCompleteClaim(pcAbort) } catch (_: Exception) { }
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                            }
                            val repairStoppedUnavailable = cancelAndJoinRepairDeferred(h1RepairDeferred)
                            if (!repairStoppedUnavailable) {
                                // A3-7: cannot confirm the blocking repair stopped;
                                // stay fail-closed (stale → next write/confirm requires refresh).
                                try { markPolicyUnknownLocked() } catch (_: Exception) { }
                            }
                            synchronized(policySettleLock) {
                                if (pcAbort != null) {
                                    activePolicyTx.compareAndSet(pcAbort, null)
                                    policyReconciling.remove(pcAbort.key)
                                    preClaimTx = null
                                }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                markPolicyUnknownLocked()
                                _state.update { cur ->
                                    cur.copy(
                                        loading = false,
                                        policyWritePhase = PolicyWritePhase.IDLE,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = true,
                                        msg = policyText(PolicyText(PolicyTextKind.REFRESH_FAILED)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            return@withLock
                        }
                        currentCoroutineContext().ensureActive()
                        run {
                            val toArm = h1RepairDeferred
                            if (toArm != null && preClaimTx != null) {
                                armAndStartTxDeferred(preClaimTx!!)
                            } else if (toArm != null) {
                                try { toArm.start() } catch (_: Exception) { }
                            }
                        }
                        val deferred: Deferred<Boolean> = h1RepairDeferred ?: viewModelScope.async(Dispatchers.IO) {
                            val r = try {
                                store.repairCorrupt()
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                RepairResult.RepairFailed
                            }
                            try {
                                repairResultRef.set(r)
                            } catch (_: Exception) {
                            }
                            r is RepairResult.Repaired || r is RepairResult.RepairedNoBackup
                        }
                        val ok: Boolean? = try {
                            withTimeout(policyIoTimeoutMs) { deferred.await() }
                        } catch (te: TimeoutCancellationException) {
                            null
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                if (preClaimTx == null) {
                                    try { deferred.cancel() } catch (_: Exception) { }
                                }
                                if (writeLocked) {
                                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                    writeLocked = false
                                }
                                throw e
                            }
                            if (preClaimTx == null) {
                                try { deferred.cancel() } catch (_: Exception) { }
                            }
                            false
                        }
                        if (ok == null) {
                            val htx = preClaimTx ?: PolicyTx(
                                txId = policyTxCoordinator?.nextTxId() ?: policyTxId.incrementAndGet(),
                                key = key,
                                deferred = deferred,
                                sanitized = emptySet(),
                                store = store,
                                isClear = true,
                                pending = null,
                                isRepair = true,
                                executionArmed = AtomicBoolean(true),
                                repairResult = repairResultRef,
                            )
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                if (preClaimTx == null) {
                                    activePolicyTx.compareAndSet(null, htx)
                                }
                                policyReconciling.add(htx.key)
                                markPolicyUnknownLocked()
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        policyDraftKnown = false,
                                        policyDraftCorrupt = true,
                                        policyWritePhase = PolicyWritePhase.RECONCILING,
                                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                        msg = policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_TIMEOUT)),
                                        msgKind = MsgKind.CHANNEL,
                                    )
                                }
                            }
                            handoverTx = htx
                            preClaimTx = null
                            return@withLock
                        }
                        // A3-1: 修復確實發生（ok==true）時，備份結果可能是
                        // Repaired 或 RepairedNoBackup —— 後者必須以誠實訊息揭露
                        // （刪除發生但證據未保留），不得以 CORRUPT_REPAIRED 語氣宣稱。
                        val repairNoBackup = try {
                            repairResultRef.get() is RepairResult.RepairedNoBackup
                        } catch (_: Exception) {
                            false
                        }
                        if (ok) {
                            val pcFast = preClaimTx
                            if (pcFast != null) {
                                val coordR = policyTxCoordinator
                                val claimedRepair: PolicyTxCoordinator.ClaimStagedVerdict = if (coordR != null) {
                                    if (repairRevBefore == -1L) {
                                        try { policyTxCoordinator?.tryCompleteClaim(pcFast) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { }
                                        if (writeLocked) {
                                            try { policyTxCoordinator?.writeMutex?.unlock() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { }
                                            writeLocked = false
                                        }
                                        synchronized(policySettleLock) {
                                            activePolicyTx.compareAndSet(pcFast, null)
                                            policyReconciling.remove(pcFast.key)
                                            inFlightWrites.remove(key)
                                            policyWriteSaving.remove(key)
                                            preClaimTx = null
                                            try { markPolicyUnknownLocked() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { }
                                            _state.update {
                                                it.copy(
                                                    loading = false,
                                                    policyDraftKnown = false,
                                                    policyDraftCorrupt = true,
                                                    policyWritePhase = PolicyWritePhase.IDLE,
                                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                    msg = policyText(
                                                        if (repairNoBackup) PolicyText(PolicyTextKind.REPAIRED_NO_BACKUP)
                                                        else PolicyText(PolicyTextKind.UNKNOWN_REFRESH),
                                                    ),
                                                    msgKind = MsgKind.CHANNEL,
                                                )
                                            }
                                        }
                                        return@withLock
                                    }
                                    try {
                                        val ph = PolicySettled(
                                            pcFast.txId, pcFast.key, pcFast.sanitized,
                                            false, MsgKind.CHANNEL, "", pcFast.isClear,
                                        )
                                        val verdict = try {
                                            coordR.completeWithRevisionCheckedVerdict(pcFast, ph, repairRevBefore)
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (_: Throwable) {
                                            PolicyTxCoordinator.ClaimStagedVerdict.CAS_LOSS
                                        }
                                        if (verdict == PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED) {
                                            try { afterClaimBeforeEmit?.invoke() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { }
                                        }
                                        verdict
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (_: Throwable) {
                                        PolicyTxCoordinator.ClaimStagedVerdict.CAS_LOSS
                                    }
                                } else {
                                    if (claimForPublish(pcFast)) {
                                        PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED
                                    } else {
                                        PolicyTxCoordinator.ClaimStagedVerdict.CAS_LOSS
                                    }
                                }
                                if (claimedRepair != PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED) {
                                    // A3-3 (C1): settle ONLY on rev-gate rejection — the
                                    // coordinator's CAS was never consumed (claim rejected
                                    // before CAS) and no winner exists, so apply the
                                    // stage-rejected terminal (helper also completes the
                                    // coordinator claim and clears keys). CAS_LOSS means a
                                    // live winner consumed the claim — loser-follow below.
                                    if (claimedRepair == PolicyTxCoordinator.ClaimStagedVerdict.REV_GATE_REJECTED) {
                                        if (writeLocked) {
                                            try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                            writeLocked = false
                                        }
                                        synchronized(policySettleLock) {
                                            applyStageRejectedUnderLock(pcFast, key)
                                            preClaimTx = null
                                        }
                                        return@withLock
                                    }
                                    // Lost CAS to another publisher without rev rejection:
                                    // if coord.activeTx moved to a foreign Tx, clean up obsolete local Tx;
                                    // otherwise flip SAVING -> RECONCILING and follow winner.
                                    var shouldLaunchLoserSync = false
                                    synchronized(policySettleLock) {
                                        val curLocal = try { activePolicyTx.get() } catch (_: Exception) { null }
                                        val curCoord = try { policyTxCoordinator?.activeTx?.get() } catch (_: Exception) { null }
                                        if (coordR != null && curCoord != null && curCoord !== pcFast) {
                                            clearObsoleteLocalTxLocked(pcFast, coordR)
                                        } else if (curLocal != null && curLocal !== pcFast) {
                                            try { policyReconciling.remove(pcFast.key) } catch (_: Exception) { }
                                        } else {
                                            _state.update { cur ->
                                                if (cur.policyWritePhase != PolicyWritePhase.SAVING) cur
                                                else cur.copy(
                                                    loading = false,
                                                    policyWritePhase = PolicyWritePhase.RECONCILING,
                                                    policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                )
                                            }
                                            shouldLaunchLoserSync = true
                                        }
                                    }
                                    preClaimTx = null
                                    if (writeLocked) {
                                        try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                        writeLocked = false
                                    }
                                    if (shouldLaunchLoserSync) {
                                        launchLoserSync(pcFast)
                                    }
                                    return@withLock
                                }
                            }
                            // A3-1: 備份失敗 → 誠實揭露（刪除發生；證據未保留）。
                            val repairedMsg = if (repairNoBackup) {
                                policyText(PolicyText(PolicyTextKind.REPAIRED_NO_BACKUP))
                            } else {
                                policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIRED))
                            }
                            val repairedKind = if (repairNoBackup) MsgKind.CHANNEL else MsgKind.INFO
                            val repairedPayload = pcFast?.let {
                                PolicySettled(it.txId, it.key, emptySet(), true, repairedKind, repairedMsg, true)
                            }
                            val stamped = if (pcFast != null && repairedPayload != null) {
                                emitTxSettledStageOnly(pcFast, repairedPayload)
                            } else {
                                null
                            }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                val wonLocal = (pcFast == null) || activePolicyTx.compareAndSet(pcFast, null)
                                if (pcFast != null) { policyReconciling.remove(pcFast.key) }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                if (wonLocal) {
                                    if (pcFast != null && policyTxCoordinator != null && stamped == null) {
                                        applyStageRejectedUnderLock(pcFast, key, fenceHeld = true)
                                    } else {
                                        bumpPolicyGenerations()
                                        disarmBlindClear()
                                        disarmCorruptRepair()
                                        policyRevisionStale.set(false)
                                        if (pcFast == null) {
                                            markObservedKnown()
                                        }
                                        try { noteLastKnownDraft(emptySet()) } catch (_: Exception) { }
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                pendingPolicyWrite = null,
                                                policyDraft = emptySet(),
                                                policyDraftKnown = true,
                                                policyDraftCorrupt = false,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                msg = repairedMsg,
                                                msgKind = repairedKind,
                                            )
                                        }
                                    }
                                }
                            }
                            if (stamped != null) {
                                policyTxCoordinator?.emitStaged(stamped)
                            }
                        } else {
                            val pcFailFast = preClaimTx
                            // A3-3: gate the FAILED placeholder claim on the same rev base
                            // the IO-stage claim used; a foreign B committing between the
                            // failed repair and this claim bumps committedRevision and the
                            // terminal is rejected, exactly like a success claim.
                            if (pcFailFast != null) {
                                val claimVerdict = claimForPublishCheckedVerdict(pcFailFast, repairRevBefore)
                                if (claimVerdict != PolicyTxCoordinator.ClaimStagedVerdict.CLAIMED) {
                                    preClaimTx = null
                                    if (writeLocked) {
                                        try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                        writeLocked = false
                                    }
                                    // A3-3 (C1): settle ONLY on rev-gate rejection (foreign B
                                    // committed mid-IO — no winner exists); CAS_LOSS means a
                                    // live winner consumed the claim and loser-follow already
                                    // ran inside claimForPublishCheckedVerdict.
                                    if (claimVerdict == PolicyTxCoordinator.ClaimStagedVerdict.REV_GATE_REJECTED) {
                                        synchronized(policySettleLock) {
                                            applyStageRejectedUnderLock(pcFailFast, key)
                                        }
                                    }
                                    return@withLock
                                }
                            }
                            val failedMsg = policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED))
                            val failedPayload = pcFailFast?.let {
                                PolicySettled(it.txId, it.key, _state.value.policyDraft, false, MsgKind.FAILED, failedMsg, true)
                            }
                            val stamped = if (pcFailFast != null && failedPayload != null) {
                                emitTxSettledStageOnly(pcFailFast, failedPayload)
                            } else {
                                null
                            }
                            if (writeLocked) {
                                try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                                writeLocked = false
                            }
                            synchronized(policySettleLock) {
                                val wonLocal = (pcFailFast == null) || activePolicyTx.compareAndSet(pcFailFast, null)
                                if (pcFailFast != null) { policyReconciling.remove(pcFailFast.key) }
                                inFlightWrites.remove(key)
                                policyWriteSaving.remove(key)
                                preClaimTx = null
                                if (wonLocal) {
                                    if (pcFailFast != null && policyTxCoordinator != null && stamped == null) {
                                        applyStageRejectedUnderLock(pcFailFast, key, fenceHeld = true)
                                    } else {
                                        markPolicyUnknownLocked()
                                        _state.update {
                                            it.copy(
                                                loading = false,
                                                pendingPolicyWrite = null,
                                                policyDraftKnown = false,
                                                policyDraftCorrupt = true,
                                                policyWritePhase = PolicyWritePhase.IDLE,
                                                policyEpoch = policyPhaseEpoch.incrementAndGet(),
                                                msg = failedMsg,
                                                msgKind = MsgKind.FAILED,
                                            )
                                        }
                                    }
                                }
                            }
                            if (stamped != null) {
                                policyTxCoordinator?.emitStaged(stamped)
                            }
                        }
                    } finally {
                        policyWriteSaving.remove(key)
                    }
                }
            } catch (e: CancellationException) {
                // CE catch 在 commit fail-closed 終態之前，先呼叫與 outer finally 共用
                // 的 tryArmedRegister（兩條 unwind 路徑行為必須一致）：登記仍未登記的
                // armed Tx（preClaimTx、phase==SAVING），或為已設的 handoverTx 完成登記——
                // 否則 armed Tx 會留著 fence 卻無 reconciler，本 VM 的 writers 永遠 BUSY
                // （只有新 VM 認領能收斂）。登記成功（handedOff=true）→ RECONCILING
                // （like confirm：保留 cur.msg/msgKind，收斂時由 handover 方附誠實新終態）；
                // 否則 → IDLE＋CORRUPT_REPAIR_FAILED＋corrupt=true（repair 未完成，store
                // 仍持損壞證據）。IDLE 分支再與 failClosedSavingRepairIfAbandoned 的
                // IDLE arm 對齊：在 _state.update 之前先 bump 世代（P3-A），避免在途
                // stale-Known load 在 flip 後落地 Known；predicate（phase==SAVING）與
                // helper 一致——合法終態不 bump。
                val handedOff = try { tryArmedRegister() } catch (_: Throwable) { false }
                if (!handedOff) {
                    // 與 failClosedSavingRepairIfAbandoned 的 IDLE arm 對齊（P3-A）：
                    // 僅當 predicate（phase==SAVING）真的會 flip 才 bump——legitimate
                    // 終態（phase 已非 SAVING）不得 bump。
                    val s0 = _state.value
                    if (s0.policyWritePhase == PolicyWritePhase.SAVING) {
                        try { markPolicyUnknownLocked() } catch (_: Exception) { }
                    }
                }
                _state.update { cur ->
                    if (cur.policyWritePhase == PolicyWritePhase.SAVING) cur.copy(
                        loading = false,
                        policyWritePhase = if (handedOff) PolicyWritePhase.RECONCILING else PolicyWritePhase.IDLE,
                        policyDraftKnown = false,
                        // Repair is only reachable when corrupt==true: the repair never
                        // completed, so the store still holds the corrupt evidence —
                        // never wash corrupt to false here (fail-closed, matches the
                        // repair pre-IO abort which also preserves corrupt).
                        policyDraftCorrupt = true,
                        msg = if (handedOff) cur.msg else policyText(PolicyText(PolicyTextKind.CORRUPT_REPAIR_FAILED)),
                        msgKind = if (handedOff) cur.msgKind else MsgKind.FAILED,
                        policyEpoch = policyPhaseEpoch.incrementAndGet(),
                    ) else cur.copy(loading = false)
                }
                throw e
            } finally {
                if (writeLocked) {
                    try { policyTxCoordinator?.writeMutex?.unlock() } catch (_: Exception) { }
                }
                val cc = coordClaimedTx
                if (cc != null && cc !== preClaimTx && cc !== handoverTx) {
                    try { cc.ownerAlive.set(false) } catch (_: Exception) { }
                    try { cc.deferred.cancel() } catch (_: Exception) { }
                    try { policyTxCoordinator?.tryCompleteClaim(cc) } catch (_: Exception) { }
                    coordClaimedTx = null
                }
                val pcOuterRepair = preClaimTx
                if (pcOuterRepair != null && !canJoinTx(pcOuterRepair)) {
                    preClaimTx = null
                    inFlightWrites.remove(key)
                    policyWriteSaving.remove(key)
                    try { abortUnarmedTx(pcOuterRepair) } catch (_: Exception) { }
                }
                // P2-B：htx／armed 登記已移入與 CE catch 共用的 tryArmedRegister
                // （冪等，只執行一次；CE catch 已登記則此處短路返回）。登記一律以
                // registerHandoverTx 回傳值為準（P2-A）。
                // F3：CE 在 SAVING await 期間穿透（legacy DEL／coord==null 路徑，
                // 無 recovery hook）——phase=SAVING 會被本 finally 遺留（repair 的
                // CE catch 已先嚐試 armed-register 並 commit RECONCILING 或
                // CORRUPT_REPAIR_FAILED 終態；若 catch 已收斂，phase 不再是 SAVING，
                // 此處 predicate-gated backstop no-op，helper lambda 內再檢一次，
                // 最多只是無害複檢）。handover 就位 → RECONCILING（有交接者負責收斂）；
                // 否則 → IDLE（corrupt 證據不清：repair 未完成，store 仍持證據）。
                // best-effort：不 rethrow。
                try {
                    synchronized(policySettleLock) {
                        val handedOff = tryArmedRegister()
                        failClosedSavingRepairIfAbandoned(handedOff = handedOff, corrupt = true, requireLoading = false)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // Stage L4 fix(a) 清除鈕統一入口（MainActivity 唯一調用點，thin wrapper →
    // onClearPolicyButtonTap 為 single source）：路由謂詞與 TTL/世代雙檢皆在
    // VM 內經 pure clearPolicyButtonRoute(known,corrupt,armed) 判定（僅
    // !known&&corrupt 走修復；Known（含髒 Corrupt 殘留）／Unavailable 一律 CLEAR，
    // 維持原盲清／正常清除語義），純函數僅作 VM 內部決策 helper／JVM 可測。
    fun onClearPolicyButtonTap() {
        when (
            clearPolicyButtonRoute(
                _state.value.policyDraftKnown,
                _state.value.policyDraftCorrupt,
                isCorruptRepairArmed(),
            )
        ) {
            ClearPolicyButtonRoute.REPAIR_CONFIRM -> confirmRepairCorruptDraft()
            ClearPolicyButtonRoute.REPAIR_REQUEST -> requestRepairCorruptDraft()
            ClearPolicyButtonRoute.CLEAR -> clearPolicyDraft()
        }
    }

    // 從 SelectionStore 恢復勾選 (供重啟/Activity 重建後呼叫):
    // - 僅在 selectedKeys 為空時恢復 (永不覆蓋使用者當前勾選)。
    // - 保存的是 AppKey 集合；只恢復仍在 catalog (availableKeys,預設為當前
    //   catalog 全量 AppKey) 交集內的 key (舊版包名格式向前容忍：無冒號條目
    //   按包名 groupBy 取首映射，見內註；新寫入一律 AppKey，無塌陷)。
    // - 恢復只打勾,永不自動執行 clone (恢复不重送寫入)。
    // - 同一目標只恢復一次 (selectionRestoredForTarget marker)；空結果
    //   (store 空/損壞/目標不符/交集空) 不置 marker 直接 return (M4)。
    // - PR-37 Phase B #2：serial 綁定——調用端傳 id，本體以 inventory 的 serial
    //   配對驗證（inventory id == targetId 才取其 serial，否則 null 嚴格比對，
    //   外來 id 永不借用他目標的 serial）。
    fun restoreSelection(targetId: Int, availableKeys: Set<String>? = null) {
        viewModelScope.launch {
            val inv = _state.value.inventory
            val serial = if (inv.targetId == targetId) inv.targetSerial else null
            opMutex.withLock { restoreSelectionLocked(targetId, serial, availableKeys) }
        }
    }

    // 恢復本體 (必須在 opMutex 下呼叫):refreshLocked 的 FRESH 分支共用。
    // suspend：store I/O 經 onIo 切至 IO dispatcher，不阻塞主執行緒。
    // PR-37 Phase B #2：targetSerial 為當下 verified serial（null=不可驗證）；
    // 綁定 serial 取自存檔快照本身（v2 全、legacy 空），永不靜默提升 id-only
    // 為 (id,serial) 完全體。
    // Stage5 併發收斂：單次 I/O (loadValidSnapshot) 取得 selectedPackages＋
    // knownTargetSerial 同一快照，免雙重讀取；同 opMutex 下 persist/clear/
    // restore 互斥，故快照與提交守衛間無交錯寫入 (外部行程直改 prefs 檔除外，
    // 該路徑由提交前三比對兜底：身份/選取已變即放棄)。
    // PR-37 Phase B R4 #2 lost-update 競態守衛：提交（寫 selectedKeys／marker／
    // bound）前重讀最新 StateFlow 三比對，任一不符即放棄（不寫選取、不燒 marker、
    // 靜默：不碰 msg，勿擾民）——
    // (a) 選取仍為空＋selectionGen 未變（ABA：中途「有→清除→空」gen 已推進，
    //     isEmpty 無法區分，必須比 gen）；
    // (b) catalog 引用未變（setSourceCatalog 一律 toList 新實例）＋kept 仍全在
    //     當前 catalog（交集仍有效）；
    // (c) 當前 (id,serial) 仍等於起始 verified 身份且等於本次請求目標（防
    //     請求外 stale＋中途遷移）。
    private suspend fun restoreSelectionLocked(
        targetId: Int,
        targetSerial: Int? = null,
        availableKeys: Set<String>? = null,
    ) {
        val store = selectionStore ?: return
        if (targetId <= NO_TARGET_ID) return
        // Phase B #1：inventory 未驗證直接返回，不燒 marker（待驗證後由 refresh 補觸）。
        if (_state.value.inventory.targetId <= NO_TARGET_ID) return
        // Stage5 外來 id 守衛 (防誤刪為優先)：請求目標 != 已驗證 inventory 即
        // no-op，不進 store I/O。舊碼以 serial=null 調 loadSelected(外來 id)，
        // 會把有效條目判不符並自清 (loadSelected 副作用)；VM 層 no-op 最小改動，
        // 保留有效條目待正確目標恢復。
        if (_state.value.inventory.targetId != targetId) return
        val cur = _state.value
        if (cur.selectedKeys.isNotEmpty()) return
        val current = SelectionTarget(targetId, targetSerial)
        if (selectionRestoredForTarget == current) return
        // 空 catalog 不置 marker：尚未真正嘗試映射，待 catalog 就緒後
        // (setSourceCatalog 補觸或下一次 refresh/restore) 仍可重試。
        if (cur.sourceCatalog.isEmpty()) return
        val startGen = selectionGen.get()
        val startCatalog = cur.sourceCatalog
        val startVerified =
            SelectionTarget(cur.inventory.targetId, cur.inventory.targetSerial)
        val byKey = cur.sourceCatalog.associateBy { it.appKey }
        val available = availableKeys ?: byKey.keys
        // Stage5 單次 I/O：selectedPackages＋knownTargetSerial 同快照取得。
        val validSnap = try {
            onIo { store.loadValidSnapshot(targetId, targetSerial) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val restored = validSnap?.selectedPackages ?: emptySet()
        if (restored.isEmpty()) return
        // AppKey 交集恢復；舊版包名條目 (無 ":") 向前容忍：按包名 groupBy 取首
        // (同包多來源只取 catalog 首個，消除 associateBy(packageName) 塌陷的
        // 非確定性，舊資料永不硬寫回 AppKey)。
        val byPackageFirst = cur.sourceCatalog.groupBy { it.packageName }
            .mapValues { (_, v) -> v.first() }
        val kept = restored.mapNotNull { saved ->
            if (":" in saved) byKey[saved]?.appKey
            else byPackageFirst[saved.trim()]?.appKey
        }.filterTo(mutableSetOf()) { it in available }
        if (kept.isEmpty()) return
        // 綁定 serial 取自同一快照本身：v2 快照綁完全體，legacy 快照綁 id-only
        //（已於 loadValidSnapshot 驗證有效，無第二重讀）。
        val savedSerial = validSnap?.knownTargetSerial
        // R4 #2：提交前三比對（最新 StateFlow）。
        val latest = _state.value
        // (a) 選取仍允許自動恢復：為空且期間無任何選取變更（gen 守 ABA）。
        if (latest.selectedKeys.isNotEmpty() || selectionGen.get() != startGen) return
        // (b) catalog 未變：引用同一實例＋交集仍有效。
        if (latest.sourceCatalog !== startCatalog) return
        val curKeys = latest.sourceCatalog.map { it.appKey }.toSet()
        if (kept.any { it !in curKeys }) return
        // (c) 驗證身份未變且仍等於本次請求目標。
        val latestVerified =
            SelectionTarget(latest.inventory.targetId, latest.inventory.targetSerial)
        if (latestVerified != startVerified) return
        if (latestVerified != current) return
        selectionRestoredForTarget = current
        // Phase B #1：恢復成功即綁定到該目標（Phase B #2：serial 配對）。
        selectionBoundTarget = SelectionTarget(targetId, savedSerial)
        _state.update { it.copy(selectedKeys = kept) }
        selectionGen.incrementAndGet()
    }

    // Phase B #1：使用者在已驗證目標下修改勾選時綁定；未驗證不綁（哨兵）。
    // 空集合一律解綁（無歸屬）。呼叫端為 toggle/setSelected/selectVisible
    //（Main 執行緒同步更新，不持 opMutex；與 refreshLocked 的清理解綁皆在
    // Main 或 mutex 下串行，JVM 測試 Unconfined 下同執行緒）。
    // PR-37 Phase B #2：綁定 (id, serial) 配對；serial 未知時綁 id-only
    //（殘留，批次拒絕靜默提升，見 cloneSelected 守衛）。
    private fun bindSelectionToVerifiedTargetLocked() {
        val cur = _state.value
        if (cur.selectedKeys.isEmpty()) {
            selectionBoundTarget = SELECTION_BOUND_NONE
            return
        }
        val inv = cur.inventory
        selectionBoundTarget =
            if (inv.targetId > NO_TARGET_ID) SelectionTarget(inv.targetId, inv.targetSerial)
            else SELECTION_BOUND_NONE
    }

    // Phase B #1：refreshLocked 偵測到 verified target 變動（舊 bound≠新）時
    // 清空 selectedKeys＋batchReport、重置恢復 marker，msg 明示目標變更
    //（MsgKind 用 INVALID：需使用者重新確認，與 persistSelection 的
    // stale-target 守衛同級；非 VERIFY/CHANNEL 通道語意）。
    // 必須在 opMutex 下呼叫（refreshLocked 已持有）。newTarget 取當下
    // _state.inventory 的 (targetId, targetSerial)（各分支剛寫入的新值）。
    // PR-37 Phase B #2：比較 (id, serial) 配對——同 id 重建（新 serial）
    // 亦視為目標變更（舊勾選清空，舊持久選取已由 store 失效）。
    private fun handleSelectionTargetChangeLocked() {
        val inv = _state.value.inventory
        val newTarget = SelectionTarget(inv.targetId, inv.targetSerial)
        val bound = selectionBoundTarget
        if (bound == SELECTION_BOUND_NONE) {
            // 未綁定（空集合或未驗證下產生）的勾選，首次見到已驗證目標即領養
            // 綁定，不清空（保留使用者剛打的勾）；空集合無事可做。
            if (newTarget.id > NO_TARGET_ID && _state.value.selectedKeys.isNotEmpty()) {
                selectionBoundTarget = newTarget
            }
            return
        }
        if (bound == newTarget) return
        // 目標已變：解綁＋重置 marker（新目標仍可恢復一次）。
        selectionBoundTarget = SELECTION_BOUND_NONE
        selectionRestoredForTarget = SELECTION_RESTORE_NONE
        if (_state.value.selectedKeys.isNotEmpty()) {
            val oldBound = bound
            _state.update { cur ->
                cur.copy(
                    selectedKeys = emptySet(),
                    batchReport = null,
                    msg = "選取目標已變更（user ${oldBound.display()} → ${newTarget.display()}），" +
                        "已清空舊勾選，請重新確認",
                    msgKind = MsgKind.INVALID,
                )
            }
            // R4 #2：mutex 下清空亦推進 gen（後續 restore 起始即見新值）。
            selectionGen.incrementAndGet()
        } else if (_state.value.batchReport != null) {
            _state.update { it.copy(batchReport = null) }
        }
    }

    // PR-7 P2 (issue #26, 階段 1):批次執行已勾選。
    //
    // 階段 1 不引入新批次寫入通道:對每個已勾選包名依確定順序 (包名升冪)
    // 逐一呼叫既有單筆 repo.cloneVerified,逐項記錄 lastOperation/diagnose,
    // 最後彙總 BatchReport (成功/失敗/未知分開,部分成功如實呈現；
    // InvalidPackage 計入 failed，非 unknown)。
    // - 空選取:拒絕,不碰裝置 (鎖外快照僅做早拒,執行以鎖內重讀為準,見 B1)。
    // - 單一 "batch:clone" inFlight key:併發第二批回報 already in progress。
    // - batch×single 互斥 (B2):執行前按鎖內 catalog 映射逐包認領
    //   "clone:<pkg>" (catalog 缺失 key 跳過認領,仍按 InvalidPackage 記帳，
    //   計入 failed);
    //   任一已存在則整批拒絕 (INFO,明示該包),不碰裝置,整批結束 finally 釋放。
    //   已知缺口:remove:<pkg> 未納入本次 batch×single 互斥 (Phase 1 範圍外)。
    // - 一次 isWritable 前置檢查 (唯讀/佔位通道整批拒絕);逐項失敗不中斷
    //   其餘項目 (ChannelError 逐項繼續;NoDual 首見後短路,見下)。
    //   PR-37 Phase C (R4 #5)：身分暫時無法判定 (exit!=0／noisy／throw) 由 core
    //   以 ChannelError 回報，走 unknown 口徑逐項繼續、不觸發 M13 短路；僅真
    //   Absent 才以 NoDual 短路。
    // - NoDual 短路 (M13):首個 NoDual 後剩餘未執行項直接記同一 NoDual,
    //   不再打裝置 (verified target 已消失,繼續打裝置必敗)。
    // - catalog 缺失的 key 以 InvalidPackage 記帳 (計入 failed),顯示用包名
    //   回退為 appKey 去 serial 前綴 (m1)，不拋出。
    // - 最終彙總 (M1/M2/M10):覆寫一條批次級 LastOperation (opId="batch:clone",
    //   phase 按 failed>0→FAILED、unknown>0→UNKNOWN、否則 SUCCESS)，
    //   diagnose 全成功則 null，否則為彙總對應事件；只要有 ≥1 成功即把
    //   inventory 標 STALE（保留 targetId 供追溯，不觸發 refresh）。
    //   Phase B #3：VerifyUnknown 可能=已寫入未驗證，故 STALE 條件放寬為
    //   (success>0 || unknown>0)，正常＋partial 兩處一致。
    // - Phase B #2 原子 pin（PR-37 P1 #2，不動 core 既有 API 之外的新 API）：
    //   鎖內起始經 fetchDualTarget 抓一次 serial 綁定目標 expectedTarget
    //   (id, serial)（起始無目標/serial 不可用即整批拒絕不碰裝置）；逐項走
    //   新 core API repo.cloneVerifiedExpected(pkg, expectedId, expectedSerial)，
    //   在同一流程內 snapshot＋resolve＋比對，漂移即回 TargetMismatch（零
    //   install），VM 側不再另做 preflight resolve（TOCTOU 已消除）。
    //   TargetMismatch 沿用 drift 收斂（partial＋VERIFY_MISMATCH＋停＋STALE，
    //   新目標 serial 一併寫入 inventory）；vanish 沿用 M13 NoDual 短路。
    //   逐項 recordBatchItemFailedOrUnknown 僅為中途明細，最終必被覆寫。
    // - 批次中斷 (M3):catch 內以已累積 items 發 partial BatchReport
    //   (計數口徑與正常彙總一致),loading=false,lastOperation FAILED
    //   (opId=batch:clone);取消 (CancellationException) 直接重拋,不寫
    //   partial,僅靠 finally 釋放 inFlight key (B3)。
    // - cloned 只加入 Success 確認的包 (與單筆 clone 一致的 fail-closed)。
    // PR64 P1：approved 為使用者確認快照（keys＋targetId＋targetSerial）。
    // 非 null 時 VM 在 opMutex 內驗證「核准快照 == live 勾選/目標」且
    // 起始 pin 目標一致後才寫入，任一不符即拒絕（零裝置寫入），關閉
    // confirm→mutex 競態與同數量換選取誤執行。UI 確認路徑必傳 approved；
    // null 僅保留給既有測試/非確認呼叫（以呼叫當下 live 快照執行）。
    internal fun cloneSelected(approved: ApprovedBatchCloneRequest? = null) {
        viewModelScope.launch {
            // B1 鎖外快照:僅做早拒 (空選取) 用,不作為執行依據。
            val outerKeys = _state.value.selectedKeys.toList()
            if (outerKeys.isEmpty()) {
                _state.update {
                    it.copy(msg = "未勾選任何應用", msgKind = MsgKind.INVALID)
                }
                return@launch
            }
            val opKey = "batch:clone"
            if (!inFlightWrites.add(opKey)) {
                _state.update {
                    it.copy(msg = "already in progress: batch clone", msgKind = MsgKind.INFO)
                }
                return@launch
            }
            // B2 認領的逐包 key:鎖內填充,外層 finally 釋放 (取消亦不殘留)。
            val batchPkgKeys = mutableListOf<String>()
            try {
                opMutex.withLock {
                    if (!isWritable()) return@withLock
                    // B1 鎖內重讀:以此快照為執行唯一依據 (鎖外僅早拒)。
                    val liveState = _state.value
                    val lockedKeys = liveState.selectedKeys.toList()
                    // PR64 P1：確認路徑必驗證「核准快照 == live 勾選/目標」；不符即拒絕（零寫入）。
                    if (approved != null) {
                        val liveTargetOk =
                            liveState.inventory.targetId == approved.targetId &&
                                liveState.inventory.targetSerial == approved.targetSerial
                        val liveKeysOk = liveState.selectedKeys == approved.selectedKeys
                        if (!liveTargetOk || !liveKeysOk || approved.selectedKeys.isEmpty()) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    batchReport = null,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.VERIFY_MISMATCH,
                                        techDetail = "batch approval mismatch: selection/target changed",
                                    ),
                                    msg = BATCH_APPROVAL_MISMATCH_SELECTION_MSG,
                                    msgKind = MsgKind.INVALID,
                                )
                            }
                            return@withLock
                        }
                    }
                    if (lockedKeys.isEmpty()) {
                        _state.update {
                            it.copy(
                                loading = false,
                                msg = "未勾選任何應用",
                                msgKind = MsgKind.INVALID,
                            )
                        }
                        return@withLock
                    }
                    val catalogByKey = liveState.sourceCatalog.associateBy { it.appKey }
                    // 確定順序:包名升冪 (catalog 缺失的 key 排最後,按 key 比)。
                    // approved 非 null 時僅執行核准集合（已驗證與 live 相符）。
                    val executionKeys = if (approved != null) approved.selectedKeys.toList() else lockedKeys
                    val ordered = executionKeys.sortedWith(
                        compareBy(
                            { catalogByKey[it]?.packageName ?: it },
                            { it },
                        ),
                    )
                    // B2:逐包認領 clone:<pkg> (鎖內 catalog 映射;缺失跳過)。
                    val distinctPkgs = ordered.mapNotNull { catalogByKey[it]?.packageName }
                        .distinct()
                    var conflictingPkg: String? = null
                    val claimed = mutableListOf<String>()
                    for (pkg in distinctPkgs) {
                        val k = opKey("clone", pkg)
                        if (!inFlightWrites.add(k)) {
                            conflictingPkg = pkg
                            break
                        }
                        claimed.add(k)
                    }
                    if (conflictingPkg != null) {
                        for (k in claimed) inFlightWrites.remove(k)
                        _state.update {
                            it.copy(
                                loading = false,
                                msg = "already in progress: ${sanitizeUserEcho(conflictingPkg)}",
                                msgKind = MsgKind.INFO,
                            )
                        }
                        return@withLock
                    }
                    batchPkgKeys.addAll(claimed)
                    _state.update { it.copy(loading = true, msg = "", msgKind = MsgKind.NONE) }
                    // PR-37 Phase B #1/#2：鎖內起始 pin 單一 serial 綁定目標。
                    // 經 onIo fetchDualTarget 一次取回 (id, serial)（三通道，
                    // 與舊 resolve 同等裝置呼叫）；逐項走 core 原子
                    // cloneVerifiedExpected（expected＝此處 pin），VM 側不再
                    // 另做 preflight resolve——preflight→clone 間 TOCTOU 由 core
                    // 內 snapshot＋resolve＋比對消除（P1 #2）。
                    // PR-37 P3 Phase A #6：起始 I/O（pin＋snapshot）納入 catch/finally
                    // 保護：一般 Exception → loading=false＋錯誤 msg＋釋放已認領 keys
                    //（外層 finally 釋放 batchPkgKeys，此處 return 即可）；取消透傳
                    //（僅復位 loading，如 Phase 1 模式）。
                    // SerialUnavailable（id 已確認但 serial 不可得）：無法建立
                    // 完全體 pin，整批拒絕、零裝置寫入（fail closed，與
                    // requestRemove 的 serial 缺失口徑一致）。
                    val pinned: DualTargetResult
                    try {
                        pinned = onIo { repo.fetchDualTarget() }
                    } catch (e: CancellationException) {
                        _state.update { it.copy(loading = false) }
                        throw e
                    } catch (e: Exception) {
                        val nowPinEx = nowWallMs()
                        _state.update { cur ->
                            val degraded =
                                if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                    cur.inventory.toStaleOrUnknown(
                                        now = nowPinEx,
                                        source = cur.inventory.source,
                                        targetId = cur.inventory.targetId,
                                    )
                                } else {
                                    cur.inventory
                                }
                            cur.copy(
                                loading = false,
                                inventory = degraded,
                                diagnose = diagnoseEvent(
                                    DiagnoseCode.CHANNEL_ERROR,
                                    techDetail = sanitizeForDisplay(e.message ?: "batch start failed"),
                                ),
                                msg = "批次無法開始：channel error: " +
                                    sanitizeForDisplay(e.message ?: "batch start failed"),
                                msgKind = MsgKind.CHANNEL,
                            )
                        }
                        return@withLock
                    }
                    if (pinned is DualTargetResult.SerialUnavailable) {
                        // PR-37 P3 Phase A #3：serial 不可用即無完全體 pin，
                        // 整批拒絕、零裝置寫入（fail closed）；舊資料不得殘留
                        // FRESH（FRESH 即降 STALE 待重查，保留原 target/
                        // queryTime 供追溯）；與 request SerialUnavailable 口徑
                        // 一致走 VERIFY_MISMATCH + INVALID（身份不可驗證，
                        // 非通道錯誤亦非 NO_DUAL）。
                        val nowSerialBad = nowWallMs()
                        _state.update { cur ->
                            val degraded =
                                if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                    cur.inventory.toStaleOrUnknown(
                                        now = nowSerialBad,
                                        source = cur.inventory.source,
                                        targetId = cur.inventory.targetId,
                                    )
                                } else {
                                    cur.inventory
                                }
                            cur.copy(
                                loading = false,
                                inventory = degraded,
                                diagnose = diagnoseEvent(
                                    DiagnoseCode.VERIFY_MISMATCH,
                                    techDetail = "serial unavailable for verified target",
                                ),
                                msg = "無法驗證分身身分（serial 缺失），請重試",
                                msgKind = MsgKind.INVALID,
                            )
                        }
                        return@withLock
                    }
                    val expectedTarget: SelectionTarget? =
                        (pinned as? DualTargetResult.Confirmed)?.let {
                            SelectionTarget(it.target.userId, it.target.serial)
                        }
                    // PR64 P1：pin 結果必須與核准目標一致（防執行期目標漂移）。
                    // 與 bound-mismatch/handleSelectionTargetChangeLocked 同收斂：
                    // 清選取＋重置 bound/restore marker＋遞增 gen，避免舊目標勾選殘留。
                    if (approved != null && expectedTarget != null &&
                        (expectedTarget.id != approved.targetId || expectedTarget.serial != approved.targetSerial)
                    ) {
                        val oldBound = selectionBoundTarget
                        selectionBoundTarget = SELECTION_BOUND_NONE
                        selectionRestoredForTarget = SELECTION_RESTORE_NONE
                        _state.update { cur ->
                            cur.copy(
                                loading = false,
                                selectedKeys = emptySet(),
                                batchReport = null,
                                inventory = cur.inventory.copy(
                                    freshness = InventoryFreshness.STALE,
                                    targetId = expectedTarget.id,
                                    targetSerial = expectedTarget.serial,
                                ),
                                diagnose = diagnoseEvent(
                                    DiagnoseCode.VERIFY_MISMATCH,
                                    techDetail = "batch approval target mismatch vs pin",
                                ),
                                msg = BATCH_APPROVAL_MISMATCH_PIN_PREFIX +
                                    "${oldBound.display()} → ${expectedTarget.display()}）",
                                msgKind = MsgKind.INVALID,
                            )
                        }
                        selectionGen.incrementAndGet()
                        return@withLock
                    }
                    if (expectedTarget == null) {
                        // 起始即無 verified target（NoDual）：整批拒絕，不碰裝置
                        //（除上行 pin＋此處鑑別用 snapshot，無 install）。
                        // #6：snapshot 亦納入保護（Cancellation 透傳僅復位 loading）。
                        val snapStart: ProfilesSnapshot
                        try {
                            snapStart = onIo { repo.profilesSnapshot() }
                        } catch (e: CancellationException) {
                            _state.update { it.copy(loading = false) }
                            throw e
                        } catch (e: Exception) {
                            val nowSnapEx = nowWallMs()
                            _state.update { cur ->
                                val degraded =
                                    if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                        cur.inventory.toStaleOrUnknown(
                                            now = nowSnapEx,
                                            source = cur.inventory.source,
                                            targetId = cur.inventory.targetId,
                                        )
                                    } else {
                                        cur.inventory
                                    }
                                cur.copy(
                                    loading = false,
                                    inventory = degraded,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.CHANNEL_ERROR,
                                        techDetail = sanitizeForDisplay(e.message ?: "batch start failed"),
                                    ),
                                    msg = "批次無法開始：channel error: " +
                                        sanitizeForDisplay(e.message ?: "batch start failed"),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                            return@withLock
                        }
                        // Stage2 身份診斷一致性（照單筆 clone M1/M2）：僅 typed
                        // Absent＋快照可用才報 NO_DUAL；Unknown 一律 CHANNEL_ERROR
                        //（含 Absent 但空快照：快照本身已證通道有問題）。零
                        // install、FRESH 降 STALE、CHANNEL msg 不含 "no dual"。
                        val pmIdsStart = (snapStart as? ProfilesSnapshot.Loaded)
                            ?.profiles?.map { it.userId }?.toSet()
                        val idResStart: DualIdResolution
                        try {
                            idResStart = onIo { repo.resolveDualUserIdTyped(pmIds = pmIdsStart) }
                        } catch (e: CancellationException) {
                            _state.update { it.copy(loading = false) }
                            throw e
                        } catch (e: Exception) {
                            val nowTypedEx = nowWallMs()
                            _state.update { cur ->
                                val degraded =
                                    if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                        cur.inventory.toStaleOrUnknown(
                                            now = nowTypedEx,
                                            source = cur.inventory.source,
                                            targetId = cur.inventory.targetId,
                                        )
                                    } else {
                                        cur.inventory
                                    }
                                cur.copy(
                                    loading = false,
                                    inventory = degraded,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.CHANNEL_ERROR,
                                        techDetail = sanitizeForDisplay(e.message ?: "batch start failed"),
                                    ),
                                    msg = "批次無法開始：channel error: " +
                                        sanitizeForDisplay(e.message ?: "batch start failed"),
                                    msgKind = MsgKind.CHANNEL,
                                )
                            }
                            return@withLock
                        }
                        val snapshotUsableStart =
                            (snapStart as? ProfilesSnapshot.Loaded)?.profiles?.isNotEmpty() == true
                        val confirmedAbsentStart =
                            idResStart is DualIdResolution.Absent && snapshotUsableStart
                        val typedDetailStart =
                            (idResStart as? DualIdResolution.Unknown)?.detail
                        val channelDetailStart =
                            (snapStart as? ProfilesSnapshot.ChannelError)?.detail
                        val nowStart = nowWallMs()
                        _state.update { cur ->
                            val degraded =
                                if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                                    cur.inventory.toStaleOrUnknown(
                                        now = nowStart,
                                        source = cur.inventory.source,
                                        targetId = cur.inventory.targetId,
                                    )
                                } else {
                                    cur.inventory
                                }
                            if (!confirmedAbsentStart) {
                                cur.copy(
                                    loading = false,
                                    inventory = degraded,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.CHANNEL_ERROR,
                                        techDetail = when {
                                            typedDetailStart?.isNotBlank() == true ->
                                                "verified dual id unknown: $typedDetailStart"
                                            channelDetailStart != null -> channelDetailStart
                                            !snapshotUsableStart ->
                                                "verified dual id unknown " +
                                                    "and profiles snapshot empty"
                                            else -> "verified dual id unknown"
                                        },
                                    ),
                                    msg = if (channelDetailStart != null) {
                                        "refresh result unknown: " +
                                            "channel error: ${sanitizeForDisplay(channelDetailStart)}"
                                    } else {
                                        "refresh result unknown: cannot confirm dual profile"
                                    },
                                    msgKind = MsgKind.CHANNEL,
                                )
                            } else {
                                cur.copy(
                                    loading = false,
                                    inventory = degraded,
                                    diagnose = diagnoseEvent(
                                        DiagnoseCode.NO_DUAL,
                                        techDetail = "confirmed absent dual profile",
                                    ),
                                    msg = "no dual profile",
                                    msgKind = MsgKind.NO_DUAL,
                                )
                            }
                        }
                        return@withLock
                    }
                    // PR-37 P3 Phase A #1＋Phase B #2：批次起始檢查選取綁定
                    //（防 95 勾選寫到 96；同 id 重建的新 serial 亦擋）。
                    // 鎖內、pin expectedTarget 之後、任何 install 之前：若 selectedKeys
                    // 非空且 selectionBoundTarget != expectedTarget（含 bound==NONE
                    // 哨兵與 id-only legacy 綁定——legacy 永不靜默提升為完全體）
                    // → 整批拒絕：清空 selectedKeys＋batchReport、重置恢復 marker
                    //（允許新目標後續恢復自己的存檔）、msg 明示 INVALID、loading=false，
                    // 零裝置寫入。禁止自動重新領養舊選取（此處不清 store、不觸 restore，
                    // 下一次 refresh 對新目標仍可恢復一次）。
                    // 口徑與 M9 persist 守衛、handleSelectionTargetChangeLocked 一致
                    //（fail closed on target mismatch）。
                    // PR-37 P3 Phase A #3：bound 拒絕亦視為一次目標變更——
                    // inventory 無條件 STALE＋更新為 live 目標（id/serial 皆取
                    // pin 的 expectedTarget；queryTime 保留供追溯，不觸發
                    // refresh）。
                    if (lockedKeys.isNotEmpty() && selectionBoundTarget != expectedTarget) {
                        val oldBound = selectionBoundTarget
                        selectionBoundTarget = SELECTION_BOUND_NONE
                        selectionRestoredForTarget = SELECTION_RESTORE_NONE
                        _state.update { cur ->
                            cur.copy(
                                loading = false,
                                selectedKeys = emptySet(),
                                batchReport = null,
                                inventory = cur.inventory.copy(
                                    freshness = InventoryFreshness.STALE,
                                    targetId = expectedTarget.id,
                                    targetSerial = expectedTarget.serial,
                                ),
                                msg = "選取已綁定舊目標，已清空，請重新勾選確認" +
                                    "（user ${oldBound.display()} → ${expectedTarget.display()}）",
                                msgKind = MsgKind.INVALID,
                            )
                        }
                        // R4 #2：mutex 下清空亦推進 gen。
                        selectionGen.incrementAndGet()
                        return@withLock
                    }
                    // M3:items 外提,catch 可見已累積進度以發 partial。
                    val items = mutableListOf<BatchItemResult>()
                    try {
                        // M13:首個 NoDual 後短路 (verified target 已消失)。
                        var noDualShort: CloneOutcome.NoDual? = null
                        for (appKey in ordered) {
                            if (noDualShort != null) {
                                // M13:短路前先本地校驗 catalog,缺失 key 按正常路徑記 InvalidPackage
                                // (計入 failed；顯示用包名回退 appKey 去 serial 前綴)。
                                val pkg = catalogByKey[appKey]?.packageName
                                if (pkg == null) {
                                    items.add(
                                        BatchItemResult(
                                            appKey = appKey,
                                            pkg = appKey.substringAfter(":"),
                                            outcome = CloneOutcome.InvalidPackage(appKey),
                                        ),
                                    )
                                    continue
                                }
                                val short = noDualShort
                                items.add(BatchItemResult(appKey, pkg, short))
                                recordBatchItemFailedOrUnknown(pkg, short)
                                continue
                            }
                            val pkg = catalogByKey[appKey]?.packageName
                            if (pkg == null) {
                                items.add(
                                    BatchItemResult(
                                        appKey = appKey,
                                        pkg = appKey.substringAfter(":"),
                                        outcome = CloneOutcome.InvalidPackage(appKey),
                                    ),
                                )
                                continue
                            }
                            // PR-37 Phase B #2 原子 pin：expected＝批次起始 pin 的
                            // (id, serial)；core 在同一流程內 snapshot＋resolve＋
                            // 比對，漂移即回 TargetMismatch（零 install），VM 側
                            // 不再另做 preflight resolve（preflight→clone 間
                            // TOCTOU 已消除；vanish 沿用 M13 NoDual 短路）。
                            // 單筆 pinned 流程 (與 clone(pkg) 相同契約，唯 pin 來自批次)。
                            when (val res = onIo {
                                repo.cloneVerifiedExpected(pkg, expectedTarget.id, expectedTarget.serial)
                            }) {
                                is CloneOutcome.Success -> {
                                    items.add(BatchItemResult(appKey, pkg, res))
                                    _state.update { cur ->
                                        val current = if (cur.cloned.contains(pkg)) {
                                            cur.cloned
                                        } else {
                                            cur.cloned + pkg
                                        }
                                        cur.copy(
                                            cloned = current,
                                            lastOperation = LastOperation(
                                                opId = "clone:$pkg",
                                                appKey = pkg,
                                                pinnedTarget = res.dualId,
                                                phase = OpPhase.SUCCESS,
                                                timeMs = nowWallMs(),
                                                detail = "cloned: $pkg",
                                            ),
                                        )
                                    }
                                }
                                is CloneOutcome.NoDual -> {
                                    // PR-37 Phase C (R4 #5)：core 已用 typed 身分
                                    // 區分 Absent／Unknown，此分支僅真 Absent 到達
                                    // (Unknown 走 ChannelError，分進 unknown 口徑)。
                                    items.add(BatchItemResult(appKey, pkg, res))
                                    recordBatchItemFailedOrUnknown(pkg, res)
                                    noDualShort = res
                                }
                                is CloneOutcome.ChannelError -> {
                                    items.add(BatchItemResult(appKey, pkg, res))
                                    recordBatchItemFailedOrUnknown(pkg, res)
                                }
                                is CloneOutcome.InvalidPackage -> {
                                    items.add(BatchItemResult(appKey, pkg, res))
                                    recordBatchItemFailedOrUnknown(pkg, res)
                                }
                                is CloneOutcome.InstallFailed -> {
                                    items.add(BatchItemResult(appKey, pkg, res))
                                    recordBatchItemFailedOrUnknown(pkg, res)
                                }
                                is CloneOutcome.VerifyFailed -> {
                                    items.add(BatchItemResult(appKey, pkg, res))
                                    recordBatchItemFailedOrUnknown(pkg, res)
                                }
                                is CloneOutcome.VerifyUnknown -> {
                                    items.add(BatchItemResult(appKey, pkg, res))
                                    recordBatchItemFailedOrUnknown(pkg, res)
                                }
                                is CloneOutcome.TargetMismatch -> {
                                    // 批次中原子漂移：core pin 與批次 pin 不一致
                                    //（id 或 serial 漂移，含同 id 重建的新 serial），
                                    // 此項零 install。沿用既有 drift 收斂
                                    //（partial＋VERIFY_MISMATCH＋停＋STALE）：
                                    // 剩餘項不執行、不記假 outcome、不打裝置，
                                    // 以已完成項發 partial。actualId/actualSerial
                                    // 為 live 身份（id gate 未取 serial 時後者為
                                    // null，見 core 註記）；vanish（無確認目標）
                                    // 走下行 NoDual 短路，不進此分支。
                                    val liveId =
                                        if (res.actualId > NO_TARGET_ID) res.actualId
                                        else expectedTarget.id
                                    val successD =
                                        items.count { it.outcome is CloneOutcome.Success }
                                    val unknownD =
                                        items.count { isBatchUnknownOutcome(it.outcome) }
                                    val failedD = items.size - successD - unknownD
                                    val partialDrift = BatchReport(
                                        items = items.toList(),
                                        successCount = successD,
                                        failedCount = failedD,
                                        unknownCount = unknownD,
                                    )
                                    val total = ordered.size
                                    val completed = items.size
                                    val driftDetail = "目標已變更（user " +
                                        "${expectedTarget.display()} → " +
                                        "${SelectionTarget(liveId, res.actualSerial).display()}），" +
                                        "批次停止：已完成 $completed/共 $total 項" +
                                        "（成功 $successD/失敗 $failedD/未知 $unknownD）"
                                    // PR-37 P3 Phase A #4：drift 無條件 STALE
                                    //（即使 0 項完成，含 UNKNOWN 起點亦升為
                                    // STALE，舊資料不謊稱新鮮）。採「更新為 live
                                    // target＋視為一次目標變更」口徑，與
                                    // handleSelectionTargetChangeLocked 一致：
                                    // inventory 更新為新目標 STALE（UI 可見新目標
                                    // 供追溯），清空選取＋重置恢復 marker（新目標
                                    // 後續仍可恢復自己的存檔，禁止自動重新領養舊
                                    // 選取）。與 handle 差異：partial 必須保留
                                    //（審計已完成項），故 batchReport 保留 partial
                                    // 不置 null；msgKind 維持 FAILED（批次中斷優先，
                                    // handle 的 INVALID 為純選取變更無批次失敗）。
                                    selectionBoundTarget = SELECTION_BOUND_NONE
                                    selectionRestoredForTarget = SELECTION_RESTORE_NONE
                                    _state.update { cur ->
                                        cur.copy(
                                            loading = false,
                                            selectedKeys = emptySet(),
                                            batchReport = partialDrift,
                                            lastOperation = LastOperation(
                                                opId = opKey,
                                                appKey = "batch",
                                                pinnedTarget = expectedTarget.id,
                                                phase = OpPhase.FAILED,
                                                timeMs = nowWallMs(),
                                                detail = driftDetail,
                                            ),
                                            inventory = cur.inventory.copy(
                                                freshness = InventoryFreshness.STALE,
                                                targetId = liveId,
                                                targetSerial = res.actualSerial,
                                            ),
                                            diagnose = diagnoseEvent(
                                                DiagnoseCode.VERIFY_MISMATCH,
                                                techDetail = driftDetail.take(MAX_DIAG_DETAIL_CHARS),
                                            ),
                                            msg = "批次中斷：目標已變更（user " +
                                                "${expectedTarget.display()} → " +
                                                "${SelectionTarget(liveId, res.actualSerial).display()}），" +
                                                "已完成 $completed/共 $total 項" +
                                                "（成功 $successD/失敗 $failedD/未知 $unknownD）" +
                                                "；已清空舊勾選，請重新確認",
                                            msgKind = MsgKind.FAILED,
                                        )
                                    }
                                    // R4 #2：mutex 下清空亦推進 gen。
                                    selectionGen.incrementAndGet()
                                    return@withLock
                                }
                            }
                        }
                        val success = items.count { it.outcome is CloneOutcome.Success }
                        val unknown = items.count { isBatchUnknownOutcome(it.outcome) }
                        val failed = items.size - success - unknown
                        val report = BatchReport(
                            items = items.toList(),
                            successCount = success,
                            failedCount = failed,
                            unknownCount = unknown,
                        )
                        // M1/M2/M10：最終彙總覆寫批次級 LastOperation + diagnose 口徑，
                        // 取代逐項末筆覆寫；Phase B #3：VerifyUnknown 可能=已寫入
                        // 未驗證，故 (success>0 || unknown>0) 即把 inventory 標
                        // STALE（保留 targetId 供追溯，不觸發 refresh）。
                        val batchDetail = "批次 ${items.size} 項：成功 $success/失敗 $failed/未知 $unknown"
                        val batchPhase = when {
                            failed > 0 -> OpPhase.FAILED
                            unknown > 0 -> OpPhase.UNKNOWN
                            else -> OpPhase.SUCCESS
                        }
                        val batchDiagnose: DiagnoseEvent? = when {
                            failed == 0 && unknown == 0 -> null
                            failed > 0 -> diagnoseEvent(
                                DiagnoseCode.OP_FAILED,
                                techDetail = batchDetail.take(MAX_DIAG_DETAIL_CHARS),
                            )
                            // Stage4：純未知批次按首個未知項選碼——ChannelError
                            //（通道無法查證）走 CHANNEL_ERROR，VerifyUnknown 才走
                            // VERIFY_UNKNOWN；與明細文案同口徑，防彙總/明細漂移。
                            else -> {
                                val firstUnknown =
                                    items.firstOrNull { isBatchUnknownOutcome(it.outcome) }?.outcome
                                val code =
                                    if (firstUnknown is CloneOutcome.ChannelError) {
                                        DiagnoseCode.CHANNEL_ERROR
                                    } else {
                                        DiagnoseCode.VERIFY_UNKNOWN
                                    }
                                diagnoseEvent(
                                    code,
                                    techDetail = batchDetail.take(MAX_DIAG_DETAIL_CHARS),
                                )
                            }
                        }
                        _state.update { cur ->
                            val summary = "批次完成：成功 $success / 失敗 $failed / 未知 $unknown" +
                                "（共 ${items.size} 項）"
                            val kind = when {
                                failed > 0 -> MsgKind.FAILED
                                unknown > 0 -> MsgKind.CHANNEL
                                else -> MsgKind.INFO
                            }
                            val pinned = items.firstNotNullOfOrNull {
                                when (val o = it.outcome) {
                                    is CloneOutcome.Success -> o.dualId
                                    is CloneOutcome.VerifyFailed -> o.dualId
                                    is CloneOutcome.VerifyUnknown -> o.dualId
                                    else -> null
                                }
                            } ?: cur.inventory.targetId.takeIf { it > 0 } ?: -1
                            val staleInventory =
                                if (success > 0 || unknown > 0) cur.inventory.copy(freshness = InventoryFreshness.STALE)
                                else cur.inventory
                            cur.copy(
                                loading = false,
                                batchReport = report,
                                lastOperation = LastOperation(
                                    opId = opKey,
                                    appKey = "batch",
                                    pinnedTarget = pinned,
                                    phase = batchPhase,
                                    timeMs = nowWallMs(),
                                    detail = batchDetail,
                                ),
                                inventory = staleInventory,
                                diagnose = batchDiagnose,
                                msg = summary,
                                msgKind = kind,
                            )
                        }
                    } catch (e: Exception) {
                        // B3:取消永不吞沒,重拋前復位 loading (不寫 partial report)。
                        if (e is CancellationException) {
                            _state.update { it.copy(loading = false) }
                            throw e
                        }
                        // M3:批次中斷不丟進度,以已累積 items 發 partial。
                        val now = nowWallMs()
                        val success = items.count { it.outcome is CloneOutcome.Success }
                        val unknown = items.count { isBatchUnknownOutcome(it.outcome) }
                        val failed = items.size - success - unknown
                        val partial = BatchReport(
                            items = items.toList(),
                            successCount = success,
                            failedCount = failed,
                            unknownCount = unknown,
                        )
                        _state.update { cur ->
                            // Phase B #3：同正常彙總，unknown 亦標 STALE。
                            val staleInventory =
                                if (success > 0 || unknown > 0) cur.inventory.copy(freshness = InventoryFreshness.STALE)
                                else cur.inventory
                            cur.copy(
                                loading = false,
                                batchReport = partial,
                                lastOperation = LastOperation(
                                    opId = opKey,
                                    appKey = "batch",
                                    pinnedTarget = -1,
                                    phase = OpPhase.FAILED,
                                    timeMs = now,
                                    detail = "批次中斷：已完成 ${items.size} 項 " +
                                        "(成功 $success / 失敗 $failed / 未知 $unknown)" +
                                        ": ${(e.message ?: "batch clone failed")
                                            .take(MAX_DIAG_DETAIL_CHARS)}",
                                ),
                                inventory = staleInventory,
                                diagnose = diagnoseEvent(
                                    DiagnoseCode.OP_FAILED,
                                    techDetail = (e.message ?: "batch clone failed")
                                        .take(MAX_DIAG_DETAIL_CHARS),
                                ),
                                msg = "批次中斷：已完成 ${items.size} 項 " +
                                    "(成功 $success / 失敗 $failed / 未知 $unknown)" +
                                    ": ${e.message}",
                                msgKind = MsgKind.FAILED,
                            )
                        }
                    }
                }
            } finally {
                inFlightWrites.remove(opKey)
                for (k in batchPkgKeys) inFlightWrites.remove(k)
            }
        }
    }

    // 批次 unknown 口徑 (PR-37 Phase C, review R4 #5)：VerifyUnknown 一直計入
    // unknown；ChannelError 亦計入 unknown——core cloneVerifiedExpected 把暫時
    // 無法判定的身分 (exit!=0／noisy 輸出／throw) 以 ChannelError 回報 (fail
    // closed，零寫入)，通道什麼都沒證明，故為未知、永不計入 failed、永不宣稱
    // "no dual"。此與逐項中途明細一致 (recordBatchItemFailedOrUnknown 已把
    // ChannelError 記為 OpPhase.UNKNOWN) 及單筆 "result unknown" 語義一致。
    // STALE 無需改動：(success>0 || unknown>0) 已涵蓋。真 Absent 仍為 NoDual
    // (failed 口徑＋M13 短路，不變)。
    private fun isBatchUnknownOutcome(outcome: CloneOutcome): Boolean =
        outcome is CloneOutcome.VerifyUnknown || outcome is CloneOutcome.ChannelError

    // 批次逐項 detail:沿用單筆 clone 的 msg 語意 (InstallFailed 取通道原文)。
    // TargetMismatch 到達不能（批次內 drift 直接走停止分支，不經此函數）；
    // 穩健起見給出目標變更文案（fail closed，不記為成功/未知）。
    private fun batchOutcomeDetail(res: CloneOutcome): String = when (res) {
        is CloneOutcome.Success -> "cloned: ${res.dualId}"
        is CloneOutcome.NoDual -> "no dual profile"
        is CloneOutcome.ChannelError -> res.detail.take(MAX_DIAG_DETAIL_CHARS)
        is CloneOutcome.InvalidPackage -> "invalid package"
        is CloneOutcome.InstallFailed -> res.detail.take(MAX_DIAG_DETAIL_CHARS)
        is CloneOutcome.VerifyFailed -> "not installed for user ${res.dualId}"
        is CloneOutcome.VerifyUnknown -> res.detail.take(MAX_DIAG_DETAIL_CHARS)
        is CloneOutcome.TargetMismatch ->
            "target changed: user ${res.expectedId} -> ${res.actualId} " +
                "(serial ${res.expectedSerial ?: "?"} -> ${res.actualSerial ?: "?"})"
    }

    // 批次逐項非成功結果記帳 (中途明細):lastOperation 逐項覆寫
    // (pinned verify 語意與單筆 clone 一致),diagnose 取該 outcome 穩定碼;
    // cloned 不動 (fail closed)。最終彙總必覆寫為批次級
    // LastOperation (opId=batch:clone, 見 cloneSelected 尾段 M1/M2/M10)。
    // 口徑 (與 BatchReport 聚合一致)：NoDual 計 failed（真 Absent，M13 短路同記；
    // 逐項 phase 亦為 FAILED，不再是 UNKNOWN）；ChannelError/VerifyUnknown 計
    // unknown (phase UNKNOWN)。m2: 未使用的 code 參數已刪除；不可達 Success
    // 分支已刪除 (本函數僅用於非成功項)。
    private fun recordBatchItemFailedOrUnknown(
        pkg: String,
        res: CloneOutcome,
    ) {
        val now = nowWallMs()
        val (phase, target) = when (res) {
            is CloneOutcome.NoDual -> OpPhase.FAILED to -1
            is CloneOutcome.ChannelError -> OpPhase.UNKNOWN to -1
            is CloneOutcome.InvalidPackage -> OpPhase.FAILED to -1
            is CloneOutcome.InstallFailed -> OpPhase.FAILED to -1
            is CloneOutcome.VerifyFailed -> OpPhase.FAILED to res.dualId
            is CloneOutcome.VerifyUnknown -> OpPhase.UNKNOWN to res.dualId
            // 到達不能（批次內 drift 直接走停止分支，不經此函數）；
            // 穩健起見記 FAILED（目標漂移、寫入未執行，需重查）。
            is CloneOutcome.TargetMismatch -> OpPhase.FAILED to res.expectedId
            else -> error("recordBatchItemFailedOrUnknown: unexpected Success")
        }
        _state.update { cur ->
            cur.copy(
                lastOperation = LastOperation(
                    opId = "clone:$pkg",
                    appKey = pkg,
                    pinnedTarget = target,
                    phase = phase,
                    timeMs = now,
                    detail = batchOutcomeDetail(res),
                ),
                diagnose = res.toDiagnoseEvent(pkg),
            )
        }
    }

    // Re-probes elevation channels after a binder/permission lifecycle event
    // (Phase F3, issue #6): called from MainActivity's Shizuku binder/permission
    // listeners and from onForeground (MainActivity.onResume routes through
    // onForeground since StageB). Serialized through opMutex like every other
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
            // P2-4（僅 AUTO 有效；同 reconnectLocked/foregroundLocked）：swap 前記錄 resolved，
            // 納入 probe 的 FRESH 失效判斷 (resolved 改變即降 STALE；此處若沿用
            // swap 後的 currentChannel，degrade 會被誤判為無變化而續留 FRESH)。
            val resolvedBeforeOp = currentChannel
            if (preference == ChannelPreference.AUTO) {
                val probe = swapAutoLocked()
                probeChannels(
                    probe,
                    allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis,
                    resolvedBeforeOp = resolvedBeforeOp,
                )
            } else {
                probeChannels(
                    allowPrivilegedHookDiagnosis = allowPrivilegedHookDiagnosis,
                    resolvedBeforeOp = resolvedBeforeOp,
                )
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
        // foreground bursts never pile an unbounded waiter queue on the mutex.
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
            } catch (e: CancellationException) {
                // K2 + 階段L：同 refresh（預取等待取消窗口；有 active sibling 續持 busy）。
                if (!hasActiveSibling(myJob)) _state.update { it.copy(loading = false) }
                throw e
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
        // Stage D：同步 disarm（Unconfined 測試下 launch 體前即可觀測；鎖內
        // reconnectLocked 另有 disarm 覆蓋 coalesced trailing 路徑）。
        disarmBlindClear()
        // F2/P3 leading busy (msg-preserving, same as refresh()): queued
        // behind opMutex still reads busy.
        viewModelScope.launch {
            // Stage F1：同 refresh()，Tx 存活即併發丟棄（保留 RECONCILING；
            // disarm 照舊同步執行，見上）。
            if (hasActivePolicyTx()) return@launch
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
            } catch (e: CancellationException) {
                // K2 + 階段L：同 refresh（預取等待取消窗口；有 active sibling 續持 busy）。
                if (!hasActiveSibling(myJob)) _state.update { it.copy(loading = false) }
                throw e
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
        // Stage D：通道切換即 disarm（目標/遠端可能已變；同步執行以便
        // latest-wins 取消前即清除，鎖內不再重複）。
        disarmBlindClear()
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
            // Stage F2 honest-unresolved：Tx 存活時跳過 probe＋refresh（保留
            // RECONCILING＋TIMEOUT/REFRESH_FAILED＋pending＋draft/Known，不碰
            // msg/phase；見 PolicyWriteTest selectDuringTx）。入口先判，免搶
            // loading 覆寫核對提示。
            if (hasActivePolicyTx()) return@launch
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
                    // Stage F2 內層重檢（排隊期間出現 Tx）：僅清 loading 並返回，
                    // 不碰 probe/refresh/msg/phase/pending（保留核對 fence）。
                    // Stage G2：含 coordinator。
                    if (hasActivePolicyTx()) {
                        _state.update { it.copy(loading = false) }
                        return@withLock
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
        // Phase I I4：su 檔探測經 onIo（VM 永不主線直跑阻塞 I/O；binder 類比 swapToLocked）。
        val suPresent = try {
            onIo { suPresentCheck() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        val decision = resolveAutoProbe(suPresent, hasRoot, hasShizuku)
        autoDegrade = decision.degrade
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
        // Phase I I4：su 檔探測＋Shizuku binder 授權經 onIo（主線永不直呼 binder IPC；
        // 與 repo/elevator probe 同式，仍在 opMutex 內，dispatcher hop 無死鎖）。
        val suPresent = try {
            onIo { suPresentCheck() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        val shizukuAuth = try {
            onIo { shizukuAuthCheck() }
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
        if (pref != ChannelPreference.AUTO) autoDegrade = null
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
                degrade = if (pref == ChannelPreference.AUTO) autoDegrade else null,
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
    // P2-4（僅 AUTO 有效）：resolvedBeforeOp 為呼叫端在前置 swapAutoLocked 之前記錄的
    // resolved（AUTO recheck/reconnect/onForeground 會先 swap 再 probe；probe 內捕捉不到
    // 那段變化，故由呼叫端傳入）。明選無前置 swap，傳入值即等於 currentChannel。
    // null 表無前置 swap，以 probe 入口的 currentChannel 為準（init 與
    // selectChannel 顯式傳 null——其後接 refresh 全量重查，結果良性）。
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
            val degradeForState = if (preference == ChannelPreference.AUTO) autoDegrade else null
            // Routing is hook-neutral: su ROOT -> Shizuku SHELL -> NoOp; the
            // hook signal never enters routing (no `hasLsposed` parameter).
            // F4b-P2 + P1 (沿襲 #19): hook signal excluded from routing even
            // though the marker now feeds the UI display below
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
            // failure: an attempted-but-failed `cat` round collapses into the
            // status, so the round must remember it attempted an exec).
            var usableGateways: List<CmdGateway> = emptyList()
            val hook: HookDiagnosis = try {
                withContext(Dispatchers.IO) {
                    val direct = try {
                        hookDirectReads()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // The production direct-read list is six files wide
                        // (see allHookMarkerFiles); mirror that here so the
                        // catch path honors the six-path contract exactly.
                        List(6) { MarkerRead.Denied }
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
            // (Readable(null) x6) plus failed `cat`s collapse to INACTIVE
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
            // p28-1 review（en 殘留）：文字經 blockText 解析資源（見 values/
            // strings.xml + values-en/），分支條件與原文一字不動；與 isWritable
            // 共用 WriteBlock key（single source）。
            val notReadyMsg: String = when {
                currentChannel == ResolvedChannel.LSPOSED_PLACEHOLDER -> {
                    // LSPosed mode semantics (F4b-P2): the gateway is still
                    // the NoOp placeholder (the whitelist policy source is
                    // wired, end-to-end DEVICE-UNVERIFIED, but the LSPosed
                    // execution channel remains a NoOp placeholder), so the
                    // channel is honestly never ready. The message states the
                    // reason plus the hook signal; it never claims the channel
                    // can execute.
                    blockText(WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(hookDesc)))
                }
                gatewayPrivilege == Privilege.NONE -> {
                    if (degradeForState != null) blockText(degradeForState)
                    else blockText(WriteBlock(WriteBlockKind.NOOP_FALLBACK))
                }
                else -> {
                    blockText(
                        WriteBlock(
                            WriteBlockKind.EXPLICIT_FAILURE,
                            listOf(preference.name, gatewayPrivilege.name),
                        ),
                    )
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
            val readyMsg = degradeForState?.let { blockText(it) } ?: ""
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
                    degrade = degradeForState,
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
        // Phase 2: probe 外層拋錯（時鐘/拷貝類非預期拋錯）同樣使 pmReadable 過期 -> null。
        // 注意：probeOnce 內 elevator/hook 拋錯已就地吞掉走 !ready 正常路徑
        // （CHANNEL，見上）；binder dead 歸正常路徑，不進此 catch。
        // PR-6 P2: 保留 lastOperation (probe 失敗不清除歷史)，
        // 只記錄本次探測失敗的診斷事件；inventory 舊資料標 STALE/NOT-FRESH
        // (FRESH->STALE，否則 UNKNOWN；比照 refreshLocked throw，保留 targetId，
        // 時鐘/拷貝類非預期 throw 後 FRESH 不得續留）。
        // msgKind 意圖 (J3c)：此外層 catch 為時鐘/拷貝類非預期拋錯 -> FAILED；
        // 正常 !ready（預期未就緒，含 binder dead 等通道失敗）-> CHANNEL。
        // 兩者現行值由測試鎖定，不得互換。
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
        // 時鐘亦可能故障（如測試注入的 nowWallMs 拋錯）：此處再拋會逃出
        // catch 使 loading 楔住 true，故兜底——FRESH 僅降 STALE（不碰
        // queryTime），其餘不動；時鐘正常則走完整 toStaleOrUnknown。
        val throwNow: Long? = try {
            nowWallMs()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
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
                        techDetail = sanitizeForDisplay(e.message ?: "probe failed") +
                            // 時鐘兜底分支痕跡：throwNow 取時失敗即時鐘不可用，
                            // 別靜默吞（FRESH 僅降 STALE 且 queryTime 未動）。
                            if (throwNow == null) " [clock unavailable]" else "",
                    )
                }
            val next = cur.copy(
                loading = keepLoadingOnExit,
                pmReadable = null,
                inventory = if (throwNow == null) {
                    if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                        cur.inventory.copy(freshness = InventoryFreshness.STALE)
                    } else {
                        cur.inventory
                    }
                } else {
                    cur.inventory.toStaleOrUnknown(
                        now = throwNow,
                        source = "probe(exception)",
                        targetId = cur.inventory.targetId,
                    )
                },
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
    // (the whitelist policy source is wired, end-to-end DEVICE-UNVERIFIED, but
    // the LSPosed execution channel remains a NoOp placeholder), so writes are
    // refused with the reason stated, including the hook signal. Nothing is
    // ever executed here: the channel must not be mistaken for a working hook
    // path.
    // p28-1 review：BlockReason -> 顯示文字（isWritable/probeChannels 共用
    // single source；Activity 側另有 Context 版 resolveBlockReason，見
    // MainActivity）。
    private fun resolveReason(reason: BlockReason): String = when (reason) {
        is BlockReason.Keyed -> blockText(reason.block)
    }

    private fun isWritable(): Boolean {
        val cur = _state.value
        // p28-1 single source: the refusal reason also shown next to the
        // disabled write buttons (MainActivity renders writeBlockReason of
        // the same state). Null means writable -- return before touching msg.
        val reason = writeBlockReason(cur) ?: return true
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
            // 拒絕理由 single source：外層 reason（見 writeBlockReason）。
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
                    msg = resolveReason(reason),
                    msgKind = MsgKind.CHANNEL,
                )
            }
            return false
        }
        if (!cur.channelReady) {
            // P25: explicit never crosses channels -- state the selected-mode
            // reason plus the optional recovery entry（拒絕理由 single source：
            // 外層 reason，見 writeBlockReason，與按鈕旁禁用原因同文）.
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
                    msg = resolveReason(reason),
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
            // PM 不可讀拒寫不另寫 diagnose：靠前序 probe 殘留 PROBE_FAILED
            // (ready=false 時 probe 已寫碼；sticky 歷史優先保留）。
            _state.update {
                it.copy(
                    loading = false,
                    msg = resolveReason(reason),
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
                    msg = resolveReason(reason),
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
        // Stage D：前景返回即 disarm（切換期間遠端可能已變；同步執行以便
        // burst coalesce 前即清除，foregroundLocked 不再重複）。
        disarmBlindClear()
        // Single-flight coalesce (Phase B P0 B2): same gate shape as refresh()/
        // recheckPrivilege(). A burst while a foreground round is in flight
        // (including waiting on opMutex) only records one trailing re-run, so
        // 5 rapid taps collapse to leader + at most one trailing instead of
        // 5 mutex waiters each running probe (+ full refresh when stale).
        // Main-confined check-and-set/clear (same reasoning as refresh()).
        // F2/P3 leading busy (msg-preserving, same as refresh()): queued
        // behind opMutex still reads busy.
        viewModelScope.launch {
            // Stage F2 honest-unresolved：Tx 存活即跳過（保留 RECONCILING＋
            // TIMEOUT/REFRESH_FAILED＋pending，不碰 msg/phase；見 selectChannel
            // 同級守衛＋測試）。入口先判，免搶 loading。
            if (hasActivePolicyTx()) return@launch
            _state.update { it.copy(loading = true) }
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
                    // Re-review #7 B2 (same as recheck loop): hold busy across
                    // the mutex handoff so the trailing round never exposes
                    // loading=false while it waits on opMutex behind another op.
                    _state.update { it.copy(loading = true) }
                    opMutex.withLock { foregroundLocked() }
                }
            } catch (e: CancellationException) {
                // K2 + 階段L：同 refresh (預取等待取消窗口；有 active sibling 續持 busy；
                // 體內取消已由 foregroundLocked 內層 catch 清位，此為保險)。
                if (!hasActiveSibling(myJob)) _state.update { it.copy(loading = false) }
                throw e
            } finally {
                if (foregroundJob === myJob) foregroundJob = null
            }
        }
    }

    // Foreground body: must run under opMutex (callers hold it).
    // Phase D P2 busy (#24, same as reconnectLocked/recheckLocked): mark busy
    // BEFORE any suspending swap/probe IO. swapAutoLocked() suspends on
    // `su -c id -u` while probeChannels() used to be the first place setting
    // loading -- without this early set the swap window exposes loading=false.
    // 取消窗口 (同 reconnectLocked)：取消落在 swap 內 su -c 懸掛永不到 probe
    // 的清除點 → 同殘 true；外層補取消清 loading 作保險 (僅取消路徑，
    // 不改成功/失敗語意)。
    private suspend fun foregroundLocked() {
        // Stage F2 內層重檢（排隊期間出現 Tx）：僅清 loading 並返回，不碰
        // probe/refresh/msg/phase/pending/draft（保留核對 fence；trailing 亦經此）。
        // Stage G2：含 coordinator。
        if (hasActivePolicyTx()) {
            _state.update { it.copy(loading = false) }
            return
        }
        _state.update { it.copy(loading = true) }
        try {
            // 過期 FRESH 補 STALE 放 try 內：時鐘拋錯走取消/異常路徑清 loading，
            // 不得卡 loading=true（此前在 try 外，時鐘拋錯直接逃出）。
            markStaleIfAgedLocked()
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
        } catch (e: Exception) {
            // K1 (第九輪 P3)：markStaleIfAgedLocked 的 nowWallMs 非取消拋錯
            // (如時鐘不可用) 不得逃出致 loading 楔住 true。CE 已在上分支重拋，
            // 此處僅收非取消路徑：舊資料保留、不可信快照降 STALE/UNKNOWN、
            // msg 帶失敗原因 (FAILED，同 refreshLocked/probeChannels 外層慣例)。
            if (e is CancellationException) throw e
            // 時鐘亦可能故障：此處再拋會逃出 catch 使 loading 楔住，故兜底——
            // FRESH 僅降 STALE (不碰 queryTime)，其餘不動；時鐘正常則走完整
            // toStaleOrUnknown (同 probeChannels 外層 catch 形狀)。
            val throwNow: Long? = try {
                nowWallMs()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            _state.update { cur ->
                val reason = sanitizeForDisplay(e.message ?: "foreground failed")
                cur.copy(
                    loading = false,
                    pmReadable = null,
                    inventory = if (throwNow == null) {
                        if (cur.inventory.freshness == InventoryFreshness.FRESH) {
                            cur.inventory.copy(freshness = InventoryFreshness.STALE)
                        } else {
                            cur.inventory
                        }
                    } else {
                        cur.inventory.toStaleOrUnknown(
                            now = throwNow,
                            source = "foreground(exception)",
                            targetId = cur.inventory.targetId,
                        )
                    },
                    diagnose = if (cur.lastOperation?.phase == OpPhase.UNKNOWN) {
                        cur.diagnose
                    } else {
                        cur.diagnose ?: diagnoseEvent(
                            DiagnoseCode.OP_FAILED,
                            techDetail = reason,
                        )
                    },
                    msg = "foreground failed: $reason",
                    msgKind = MsgKind.FAILED,
                )
            }
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
// has no hook input (`routePrivilegeFromProbes` signature has no `hasLsposed` parameter).
// A present marker is existence+scope evidence, not proof of a live, effective
// hook, so it must never grant privilege. probeChannels routes through this
// function; HookDiagnosisTest locks the contract.
fun hookNeutralRoute(hasRoot: Boolean, hasShizuku: Boolean): Privilege =
    routePrivilegeFromProbes(hasShizuku = hasShizuku, hasRoot = hasRoot)

// PR-7 P2 (issue #26):選取自動恢復 marker 初值 (從未恢復)。
// 合法 dualId 恆 >= -1 (-1 = 無目標),故 -2 = 「從未恢復」哨兵。
// PR-37 Phase B #2：marker 與綁定皆為 (id, serial) 配對（見 SelectionTarget），
// 哨兵 id 取 -2、serial 取 null。
private const val SELECTION_RESTORE_ID_NONE = -2

// PR64 P1：UI 已確認的批次快照（VM 在 opMutex 內驗證相符後才寫入）。
// selectedKeys 為不可變集合快照；target 為確認當下已驗證目標 (id, serial)。
internal data class ApprovedBatchCloneRequest(
    val targetId: Int,
    val targetSerial: Int,
    val selectedKeys: Set<String>,
)

// 選取目標身份：verified dualId＋平台 serial 配對（PR-37 Phase B #2 serial 綁定）。
// serial null＝id-only（legacy 快照恢復或 serial 不可用時的殘留）；與完全體
// (id, serial) 永不相等，拒絕靜默提升（見 cloneSelected 起始守衛）。
private data class SelectionTarget(val id: Int, val serial: Int?)

private val SELECTION_RESTORE_NONE = SelectionTarget(SELECTION_RESTORE_ID_NONE, null)

// PR-7 P2 Phase B #1：選取綁定目標初值（從未綁定）。同 -2 哨兵（與恢復 marker
// 同值但語意獨立：bound 追蹤「當前勾選歸屬」，marker 追蹤「已自動恢復過」）。
private val SELECTION_BOUND_NONE = SelectionTarget(SELECTION_RESTORE_ID_NONE, null)

// 配對顯示：id 一律顯示；serial 已知才附加（legacy/id-only 只顯示 id）。
private fun SelectionTarget.display(): String =
    if (serial != null) "$id#$serial" else "$id"

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
    // Phase H：診斷只證明歷史存在性，永不證明本次開機存活，fail-closed 歸零
    // (未來 boot 比較通過由專屬路徑重設 true，不經此處)。
    // TODO: 未來 setter 必須經 boot 比較 markerTimeMs vs boot（見 HookMarker.parseTimeMs），不得在此直設 true。
    hookBootFresh = false,
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

// p28-1（review 修復）：寫入拒絕理由 single source（isWritable() 報入 msg 的
// 即此 key 經 blockText 解析的文字；MainActivity 在主操作按鈕旁以 Context 版
// resolveBlockReason 顯示同一 key，不再只靠點按後拒絕才得知）。
// 回傳資源鍵（JVM 可測，不碰 Android runtime；文字見 values/strings.xml +
// values-en/），主程式碼零硬編碼字面。
// Null = 可寫。分支順序與 isWritable 完全一致：LSPOSED 佔位、!writeReady
//（AUTO 降級 typed Keyed、無權限 NoOp 回退、明選模式失敗）、PM 不可讀門、
// 最後不可達的 NONE 權限守衛。channelReady 為 writeReady 舊別名（恆等）；
// gatewayPrivilege/currentChannel 由 probeChannels 同步拷貝至
// execIdentity/resolvedGateway，故 isWritable 持鎖下讀 state 等價
// （明選分支原文用 gatewayPrivilege，此處讀 execIdentity，值相同）。
// P2-3 AUTO 降級註記（probe 動態產物）亦為 typed Keyed（AUTO_DEGRADE_*），
// 經 blockText/資源解析；主路徑無 Verbatim。
enum class WriteBlockKind {
    LSPOSED_PLACEHOLDER,
    NOOP_FALLBACK,
    EXPLICIT_FAILURE,
    PM_UNREADABLE,
    NO_PRIVILEGE,
    AUTO_DEGRADE_SU_TO_SHIZUKU,
    AUTO_DEGRADE_BOTH_FAILED,
    AUTO_DEGRADE_NO_CHANNEL,
}

// 資源鍵 + 格式參數：LSPOSED 的 args[0]=Hook 證據文字（動態）；EXPLICIT 的
// args[0]=模式名、args[1]=通道身份名（列舉名，中英同形）；其餘無參數。
data class WriteBlock(
    val kind: WriteBlockKind,
    val args: List<String> = emptyList(),
)

// Phase 5 polish：政策 VM 訊息 single source（前身硬編碼繁中＋一處英文）。
// VM 本體不碰 Android Context：顯示文字一律經 policyText 注入解析；預設
// defaultText() 即繁中字面（與 values/strings.xml 同文，三語系同鍵），JVM
// 測試无需 Context 即可斷言。count 僅 DROPPED/WRITE_SUCCESS 使用（plurals
// 數量）；其餘忽略。
enum class PolicyTextKind {
    BUSY,
    OWNER_WRITE,
    OWNER_OPERATE,
    UNKNOWN_REFRESH,
    EMPTY_SELECTION,
    CATALOG_LOADING,
    CATALOG_MISSING,
    DROPPED,
    CANCELLED,
    NO_PENDING,
    IN_PROGRESS,
    TTL_EXPIRED,
    NO_STORE_WRITE,
    NO_STORE_CLEAR,
    NO_STORE_READ,
    WRITE_SUCCESS,
    WRITE_FAILED,
    CLEAR_SUCCESS,
    CLEAR_FAILED,
    REFRESHED,
    REFRESH_FAILED,
    BLIND_CLEAR_FIRST,
    // Stage D：blind-clear 確認逾時（arm 超過 BLIND_CLEAR_TTL_MS）。msgKind 複用
    // CHANNEL（與 FIRST 同級：未知＋需重新確認＋建議先重新整理）。
    BLIND_CLEAR_EXPIRED,
    // Stage L4 P2#3 corrupt repair（損壞 CCE 專用，與盲清 Unavailable 分離）：
    // DETECTED＝refresh/init 觀測到 Corrupt（CHANNEL，保留舊快照）；
    // REPAIR_FIRST＝修復第一次 arm（CHANNEL，需二次確認）；
    // REPAIRED＝修復成功 Known(empty)（INFO）；
    // REPAIR_FAILED＝修復失敗仍 Corrupt（FAILED）。逾時複用 BLIND_CLEAR_EXPIRED。
    CORRUPT_DETECTED,
    CORRUPT_REPAIR_FIRST,
    CORRUPT_REPAIRED,
    CORRUPT_REPAIR_FAILED,
    // Stage M2 P2#3: repair IO 逾時（結果未知，正在核對，請勿重試）。msgKind 複用
    // CHANNEL（與 WRITE/CLEAR_TIMEOUT 同級：未知＋正在核對＋不可重試）。
    CORRUPT_REPAIR_TIMEOUT,
    // Stage A：IO 逾時（結果未知，正在核對，請勿重試）。msgKind 複用 CHANNEL
    //（不新增 enum），文案口徑「逾時、結果未知、正在核對、請勿重試」。
    WRITE_TIMEOUT,
    CLEAR_TIMEOUT,
    // Stage G2：deferred 完成後單次權威重查仍未知（unresolved）。msgKind 複用
    // CHANNEL（未知＋明確動作）；文案明示按鈕即 refreshPolicyDraft（點
    // 「重新整理草稿」確認結果），與 REFRESH_FAILED（重試失敗）區分：本態為
    // 自動重查後仍未知、待手動刷新 late-join。
    UNRESOLVED_REFRESH,
    // Phase 2 S1（consent fence）：confirm 在使用者未勾選確認框時被調用 ——
    // VM 層第二道門（UI positive 鈕為第一道），不寫 store、不清 pending。
    // msgKind INVALID。
    POLICY_NOT_ACKNOWLEDGED,
    // Phase 2 S4（blind-clear refresh-first）：盲清第二次點擊時發現行程級
    // （process-lifetime，coordinator 持有；同行程 Activity 重開不變，僅新行程
    // 解除）最後 Known 草稿快照，遂強制先重讀（refreshPolicyDraft）；本次
    // 點擊不刪除任何內容。恢復 Known 後下一次清除即走一般 Known 單步路徑；
    // 若重讀仍未知，草稿維持未知且不可盲刪（fail-closed）。msgKind CHANNEL。
    BLIND_CLEAR_REFRESH_FIRST,
    // Phase 2 A3-1（repair backup honesty）：修復（刪除損壞內容）已發生且
    // post-read 驗證為 Known(empty)，但 best-effort 備份失敗 —— 舊損壞內容
    // 未被保留。與 CORRUPT_REPAIRED 區分：本態絕不宣稱證據已保留。
    // msgKind CHANNEL（提醒而非失敗）。
    REPAIRED_NO_BACKUP,
}

data class PolicyText(
    val kind: PolicyTextKind,
    val count: Int = 0,
)

// 預設繁中字面（與 values/strings.xml 同文；en 見 values-en/）：生產 factory
// 以 Application Context 解析同鍵資源（plurals 走 getQuantityString），JVM
// 預設走此分支，語義不動。
internal fun PolicyText.defaultText(): String = when (kind) {
    PolicyTextKind.BUSY -> "政策寫入進行中，請稍候"
    PolicyTextKind.OWNER_WRITE -> "政策草稿必須由主用戶（owner user）寫入，否則 hook 讀不到"
    PolicyTextKind.OWNER_OPERATE -> "政策草稿必須由主用戶（owner user）操作，否則 hook 讀不到"
    PolicyTextKind.UNKNOWN_REFRESH -> "遠端政策未知，請先重新整理後再寫入"
    PolicyTextKind.EMPTY_SELECTION -> "未勾選任何應用"
    PolicyTextKind.CATALOG_LOADING -> "來源清單尚未載入，請稍候再試"
    PolicyTextKind.CATALOG_MISSING -> "勾選的應用已不在來源清單，請重選"
    PolicyTextKind.DROPPED -> "已捨去 $count 項不在來源清單的勾選"
    PolicyTextKind.CANCELLED -> "已取消寫入政策（未寫入任何草稿）"
    PolicyTextKind.NO_PENDING -> "沒有待確認的政策寫入"
    PolicyTextKind.IN_PROGRESS -> "政策寫入已在進行中，請稍候"
    PolicyTextKind.TTL_EXPIRED -> "確認已逾時，請重新確認後再寫入"
    PolicyTextKind.NO_STORE_WRITE -> "無政策 store，無法寫入政策"
    PolicyTextKind.NO_STORE_CLEAR -> "無政策 store，無法清除草稿"
    PolicyTextKind.NO_STORE_READ -> "無政策 store，無法讀取草稿"
    PolicyTextKind.WRITE_SUCCESS -> "已寫入政策草稿 $count 項（勾選≠安裝，可隨時清除）"
    PolicyTextKind.WRITE_FAILED -> "寫入政策失敗"
    PolicyTextKind.CLEAR_SUCCESS -> "已清除政策草稿（不影響已安裝分身與目前勾選）"
    PolicyTextKind.CLEAR_FAILED -> "清除草稿失敗"
    // Dedicated string (R.string.policy_refreshed): JVM default mirrors the
    // resource verbatim; production resolves via resources (see factory).
    PolicyTextKind.REFRESHED -> "已重新整理政策草稿"
    PolicyTextKind.REFRESH_FAILED -> "重新整理政策草稿失敗：遠端內容未知"
    PolicyTextKind.BLIND_CLEAR_FIRST -> "遠端內容未知，清除將刪除未見內容（具破壞性且無法復原），請於5分鐘內再次清除以確認；若可連線建議先重新整理"
    PolicyTextKind.BLIND_CLEAR_EXPIRED -> "清除確認已逾時（超過5分鐘），請重新清除以確認；建議先重新整理"
    PolicyTextKind.CORRUPT_DETECTED -> "遠端政策已損壞（類型錯亂），已保留舊快照；清除需經修復確認，盲清已拒絕；若可連線建議先重新整理"
    PolicyTextKind.CORRUPT_REPAIR_FIRST -> "檢測到損壞的政策條目，修復將嘗試備份後清除損壞內容（備份僅 best-effort、可能失敗、無法保證可恢復；具破壞性且無法復原），請於5分鐘內再次確認修復；若可連線建議先重新整理"
    PolicyTextKind.CORRUPT_REPAIRED -> "已修復損壞的政策草稿（已嘗試備份並清除，現為空；備份不保證成功）"
    PolicyTextKind.CORRUPT_REPAIR_FAILED -> "修復損壞的政策草稿失敗，仍為損壞狀態；建議重試或檢查服務連線"
    PolicyTextKind.CORRUPT_REPAIR_TIMEOUT -> "修復損壞政策逾時，結果未知，正在核對，請勿重試"
    PolicyTextKind.WRITE_TIMEOUT -> "寫入政策逾時，結果未知，正在核對，請勿重試"
    PolicyTextKind.CLEAR_TIMEOUT -> "清除草稿逾時，結果未知，正在核對，請勿重試"
    PolicyTextKind.UNRESOLVED_REFRESH -> "結果仍未知，請點「重新整理草稿」確認結果"
    PolicyTextKind.POLICY_NOT_ACKNOWLEDGED -> "尚未勾選確認框，未寫入任何內容"
    PolicyTextKind.BLIND_CLEAR_REFRESH_FIRST -> "先前曾確認草稿內容，但遠端目前未知；已先觸發重新整理。請檢查 Hook 連線或重新啟動 App 後再清除"
    PolicyTextKind.REPAIRED_NO_BACKUP -> "已清除損壞的政策草稿，但備份失敗：舊的損壞內容未被保留"
}

sealed interface BlockReason {
    data class Keyed(val block: WriteBlock) : BlockReason
}

internal fun writeBlockReason(s: UiState): BlockReason? {
    if (s.resolvedGateway == ResolvedChannel.LSPOSED_PLACEHOLDER) {
        return BlockReason.Keyed(
            WriteBlock(WriteBlockKind.LSPOSED_PLACEHOLDER, listOf(s.hookEvidence().displayText())),
        )
    }
    if (!s.writeReady) {
        return if (s.channel == ChannelPreference.AUTO && s.degrade != null) {
            BlockReason.Keyed(s.degrade)
        } else if (s.execIdentity == Privilege.NONE) {
            BlockReason.Keyed(WriteBlock(WriteBlockKind.NOOP_FALLBACK))
        } else {
            BlockReason.Keyed(
                WriteBlock(
                    WriteBlockKind.EXPLICIT_FAILURE,
                    listOf(s.channel.name, s.execIdentity.name),
                ),
            )
        }
    }
    if (s.pmReadable == false) {
        return BlockReason.Keyed(WriteBlock(WriteBlockKind.PM_UNREADABLE))
    }
    if (s.privilege == Privilege.NONE || s.execIdentity == Privilege.NONE) {
        return BlockReason.Keyed(WriteBlock(WriteBlockKind.NO_PRIVILEGE))
    }
    return null
}

// PR-6 P4 (#27 架構整理)：扁平時間證據欄位的衍生視圖。isWritable 與
// MainActivity hook 行經此組裝 HookEvidence 再 displayText，消滅手動組裝；
// 扁平欄位 (hookStatus/hookScopes/hookQueriedAtMs/hookMarkerTimeMs/
// hookViaGateway) 保留 (匯出/測試仍直讀).
// Phase H：HookEvidence 只攜 existence 證據；live 門控另讀
// UiState.hookBootFresh (預設 false)，不經此處搬運。
fun UiState.hookEvidence(): HookEvidence = HookEvidence(
    status = hookStatus,
    scopes = hookScopes,
    queriedAtMs = hookQueriedAtMs,
    markerTimeMs = hookMarkerTimeMs,
    viaGateway = hookViaGateway,
)

// Stage L4 fix(a) 清除鈕路由 single source（純函數，JVM 可測，不碰 VM/Activity）：
// 僅 !known&&corrupt 走修復（未 arm→REPAIR_REQUEST arm＋警告，已 arm→
// REPAIR_CONFIRM TTL＋世代雙檢後執行）；其餘一律 CLEAR（Known 單步清除／
// Unavailable 盲清雙確認；Known+Corrupt 殘留亦 CLEAR走正常清除路徑）。
// 直調 clearPolicyDraft 的 !known&&corrupt 仍由其內 CORRUPT_DETECTED 拒絕兜底
// （零 store 觸碰），永不走盲清；VM onClearPolicyButtonTap 唯一經此分發，
// Activity 僅 thin wrapper 直調，不重複 when。
internal enum class ClearPolicyButtonRoute { CLEAR, REPAIR_REQUEST, REPAIR_CONFIRM }

internal fun clearPolicyButtonRoute(
    known: Boolean,
    corrupt: Boolean,
    repairArmed: Boolean,
): ClearPolicyButtonRoute =
    if (!(!known && corrupt)) ClearPolicyButtonRoute.CLEAR
    else if (repairArmed) ClearPolicyButtonRoute.REPAIR_CONFIRM
    else ClearPolicyButtonRoute.REPAIR_REQUEST
