# Architecture

This document describes the privilege abstraction of Galaxy-Dual-app-Plus.
All code is original and MIT-licensed. Third-party projects are idea-only references.

## Goals

- Support Samsung Dual Messenger / Dual Apps without touching Secure Folder.
- LSPosed channel is a display-only marker / NoOp placeholder (never grants privilege); routing is hook-neutral, fall back to Shizuku shell channel when su is unavailable.
- Keep parsing and execution testable without Android framework dependencies.

## Modules

- `app`: UI layer, owns user actions and status display.
- `core`: pure-Kotlin privilege routing, command gateway, and `pm` output parsing.
- `hook`: LSPosed module (API 102), loaded only inside target Samsung processes.

## GuardXposedProvider (policy provider parity)

- The libxposed `service` AAR declares its own exported `XposedProvider` at
  `content://<applicationId>.XposedService` with no permission, so any app could
  call `SendBinder` and deliver a fake `IBinder` to hijack the policy channel
  (`XposedServiceHelper.onBinderReceived`). The app manifest removes that node
  (`tools:node="remove"`) and declares `GuardXposedProvider` at the same
  authority.
- `GuardXposedProvider` delegates `call()` to the library provider instance but
  gates it: only LSPosed daemon UIDs (`uid < FIRST_APPLICATION_UID`, including
  shell 2000) or the app itself (`uid == Process.myUid()`) may deliver a binder;
  everything else gets `null`. CRUD stays inert (`null`/`0`).
- The pure decision function `isTrustedXposedCaller` / `guardedPolicyCall` is
  JVM-tested; the real SendBinder round-trip remains DEVICE-UNVERIFIED.

## PrivilegeRouter

```kotlin
// Pseudocode: hook-neutral — hook signal never grants privilege (#19).
// routePrivilegeFromProbes(hasShizuku, hasRoot) is a pure function of two probes only;
// hook state is display-only and must never be fed as an authorization input.
val privilege: Privilege = when {
    hasRoot -> Privilege.ROOT          // su probe passed
    shizukuAlive() -> Privilege.SHELL // Shizuku binder reachable + authorized
    else -> Privilege.NONE            // read-only mode
}
```

> Honesty note (H5 五層證據，見 `docs/HOOK.md` §1.2／§1.3 與
> [S24 調查](UX_REVIEW_S24.md) §5)：已觀察 —— (1)類別選取：
> daagent `using provider.WhiteListApps` 連續命中×7（僅類別選取，
> 不等於 `<clinit>` 執行／回調／放寬）；(2)hook 註冊：system
> `DualAppManagerService` 單方法（getAllWhitelistedPackages）deoptimize＋hooked（僅註冊，
> 回調觸發未觀察）；(3)marker 主路徑歷史 scope=system_server
>（早於本次開機約 5.5h，歷史證據）；(5)App 一鍵：ROOT 一鍵 `cloned:`、
> 已授權 SHIZUKU 一鍵（已安裝 verify 單次）。
> 未驗 —— (3)callback 可驗日誌缺、(4)非空政策生效（政策來源已接線至 LSPosed
> remote preferences，端到端未驗；靜態種子預設空，故預設 observe-only）、寫後以 listing 為準（`pm path` 僅輔助，見 #22）、
> 目標身分完整邊界（見 #21）、當前 source 可追溯部署。
> 路由 hook-neutral：`routePrivilegeFromProbes(hasShizuku, hasRoot)` 為兩探針純函數，
> `hasLsposed` 參數已移除；`channelReadyFor` 忽略 hook 訊號；marker 僅顯示（F4b-P2，見 #19／#27）。

- `Privilege` enum: `NONE` (no write), `SHELL` (Shizuku / ADB shell), `ROOT` (su probe).
- Router is a pure function of two probes: `hasRoot` (su) and `isShizukuAlive()`; hook signal never enters routing (hook-neutral, LSPosed mode is NoOp placeholder).
- UI disables write actions when privilege is `NONE`.

> Binary-compatibility note: `core` is an in-repo Gradle module (not
> published) with **NO binary compatibility guarantee** for precompiled
> consumers — all in-repo callers rebuild from source. The router was renamed
> from `routePrivilege` to `routePrivilegeFromProbes` specifically to avoid a
> silent JVM semantic collision: the removed old form took hook-active plus
> shizuku-alive Booleans, while the current form takes hasShizuku plus hasRoot,
> yet both share the same two-Boolean JVM descriptor `routePrivilege(ZZ)`. A
> previously compiled caller passing false, then true (old Shizuku-only =>
> `SHELL`) would have bound to the new method as hasShizuku false, hasRoot
> true => `ROOT` (silent privilege escalation); the symmetric call (old hook
> plus no Shizuku => `ROOT`) would have become hasShizuku true, hasRoot false
> => `SHELL` (silent de-escalation). The rename changes the JVM method
> identity so old binaries fail to bind instead of silently changing meaning.

> Build-note: `gradle.properties` sets
> `android.experimental.disableCompileSdkChecks=true` as an **accepted, documented
> risk** for the `io.github.libxposed` 102.0.0 AARs (`service`/`interface`/`api`),
> whose AAR metadata declares `minCompileSdk=37` while this project compiles
> against SDK 34 on the AGP 8.5.2 baseline. The app only uses APIs available in
> 34; the API 29 instrumentation run is the runtime-compatibility guard. This
> flag will be revisited on the next AGP/compileSdk upgrade.
>
> Device + SDK 矩陣（release gate 實證口徑：JVM／emulator 綠燈不等於 S24 裝置驗收）：
>
> | 環境 | SDK／版本 | 用途 | 狀態 |
> | --- | --- | --- | --- |
> | S24 SM-S9210（OneUI `oneui=90000`、Android 17／API 37） | API 37 實機 | 五層部分已驗證的唯一實機來源（見 `docs/UX_REVIEW_S24.md` §5） | 歷史證據（2026-11-05）；當前 source 可追溯部署未驗 |
> | Emulator API 29（minSdk floor） | API 29 | CI `locale-instrumentation` job：`:app:connectedDebugAndroidTest` | 每 PR 自動執行 |
> | Emulator API 34（compileSdk／targetSdk） | API 34 | `instrumentation-manual` job（`workflow_dispatch`）：同上 harness＋provenance | 手動執行 |
> | Build baseline | compileSdk 34、minSdk 29、targetSdk 34、AGP 8.5.2、Gradle 8.7、JDK 17 | PR CI `build` job：JVM 單元測試＋lint＋honesty／Knox／GPL gates | `testDebugUnitTest` 全綠只保證語意，不替代上列裝置驗收 |
> | 非空政策生效 gate（`NON_EMPTY_POLICY_EFFECTIVE_GATE=false` sealed 常閉） | —（code gate，非裝置驗證） | JVM 鎖定（`PolicySourceTest`／`EffectivePolicyCacheTest`）＋硬閘門見 `docs/HOOK.md` §1.4 | 常閉；JVM 綠燈不開 gate，開 gate 只認硬閘門裝置證據 |
> | 政策 Tx 結清／coordinator（Stage G1/G2） | —（VM 語義，非裝置驗證） | JVM 鎖定（`PolicyWriteTest`：settle helper／世代拆分／跨 VM 認領（auto-recheck 間接覆蓋）；reconciler 總預算 15 秒） | JVM 綠燈不等於裝置驗收；旋轉重建實機恢復未驗 |

## Executor (CmdGateway) — F4a 真通道已接線（五層第 5 層：App 一鍵部分已觀察，其餘未驗）

- `CmdGateway` interface exposes `run(cmd: String): CmdResult` as the primary
  entry point (`run(argv: List<String>)` is a convenience overload that joins
  argv with spaces; prefer `run(String)`).
- Implementations (app side, 五層第 5 層)：已觀察為 ROOT 一鍵 `cloned:`、
  已授權 SHIZUKU 一鍵（已安裝 verify 單次，見 `docs/USAGE.md` §0 與
  [S24 調查](UX_REVIEW_S24.md) §5）；未驗為完整 binder／授權矩陣、
  當前 source 可追溯部署：
  - `ShizukuCmdGateway`: SHELL channel via the Shizuku UserService
    `GalaxyExecService` (`Shizuku#newProcess` was removed in Shizuku API
    13.1.5; the official guide mandates UserService instead -- F4a implements
    exactly that). Owner-uid gate invariant (Phase A P0, DEVICE-UNVERIFIED;
    Phase C2 appId-normalized, Phase D2 appId upper bound): only the owner
    app uid (appId in 10000..19999 / AID_APP_START..AID_APP_LAST) may EXEC;
    privileged/shared appIds (root 0, system 1000, shell 2000, regardless of
    user offset) and SDK sandbox (20000-29999) / isolated (99000-99999)
    appIds, and resolution failure fail closed; equality stays full-UID
    (never appId equality).
    Server gate (Phase C3): requires Shizuku v13+ (first API with the
    Context constructor); the gateway checks `Shizuku.getVersion()` pre-bind
    and refuses older servers with a definite failure before any bind.
  - `SuCmdGateway`: ROOT channel via `su -c` (core-built command passed
    verbatim as the single `-c` argument, argv form, no extra shell layer).
  - `NoOpGateway` (core): returns failure when `NONE`, prevents accidental writes.
- Callers never branch on ROOT vs SHELL directly; they call the injected gateway.
- Selection (F4b-P1): the persisted `ChannelPreference` (AUTO default,
  `SharedPreferences`) is resolved via `assembleChannel` both at startup
  (`DualViewModelFactory`) and on re-selection (`DualViewModel.selectChannel`
  swaps the repo gateway under opMutex, then re-probes + refreshes);
  `recheckPrivilege()` re-resolves AUTO when the environment changed. AUTO
  keeps the F4a order (su file presence first, else Shizuku binder alive +
  authorized, else `NoOp` fail-closed; hook never auto-selected). Explicit
  LSPosed maps to the NoOp placeholder with the picker UI + file-marker
  enable detection done in P2 (hook writes /data/system marker, app reads
  direct + gateway cat; whitelist policy source wired to LSPosed remote
  preferences, end-to-end DEVICE-UNVERIFIED).

## PmOutputParser

- Parses `pm list packages`, `dumpsys`, and Samsung dual-app service output.
- Returns structured `DualAppInfo(packageName, userId, dualEnabled)` list.
- `parsePackagesTrusted(out/err/exit/truncated)` is the fail-closed whitelist judge (`PmOutputParser.kt:386-459`): `TrustedFull(packages)` = clean listing containing these exact packages; `TrustedEmpty` = clean listing with zero `package:` lines (confirmed empty); `Unknown(reason)` = uncheckable (truncated / non-zero exit / error-semantic / unexpected format / stderr residue, `detail` aliases `reason`).
- Whitelist rule: every non-blank stdout line must be a legal `package:` line (case-insensitive, same as `TRUSTED_EXEMPT_LINE`) with payload `^[A-Za-z0-9._-]+$` (`PACKAGE_PAYLOAD`, trusted-path only), and stderr must be fully blank; `unexpected listing format` / `malformed package line` / `unexpected stderr` never count as empty (fail closed).
- `installed for user` echo is not exempt on the trusted path (only `package:` / `Success` lines skip the error scan); any `Failure` line (`ALREADY_EXISTS` included) makes the listing `Unknown`.
- `parsePackages()` itself stays tolerant for compatibility (ignores unknown lines, trims whitespace) and never authorizes writes; trusted decisions always go through `parsePackagesTrusted` (`verifyListProbe` / `listProbe`).
- Unit-testable with static golden strings, no device required.

## Data Flow

1. UI asks `PrivilegeRouter` for current level.
2. UI builds a `CmdGateway` matching that level.
3. Gateway runs `pm` / `dumpsys` commands.
4. Raw stdout goes through `PmOutputParser` into UI state.
5. LSPosed / hook path (display-only): marker presence and hook registration are existence+scope evidence only; they never grant write capability. No direct service-backed executable hook gateway is wired in the selected path — `ChannelPreference.assembleChannel(ChannelPreference.LSPOSED, …)` constructs `NoOpGateway` (writes refused). LSPosed channel remains a NoOp placeholder.
6. Concurrency boundary (#24; busy = `UiState.loading`, merge = coalesce):
   `opMutex` serializes probe/refresh/clone/remove; rapid refresh taps coalesce
   into one trailing run, clone/request-remove/confirm dedup by op key (`already in progress`,
   three namespaces `clone:<pkg>` / `request-remove:<pkg>` / `remove:<pkg>@<id>#<serial>#<ts>` generation-bound by serial);
   every repo call runs via `onIo` on `Dispatchers.IO`.
   Write replies `timedOut ‖ transportUnknown ‖ isPostSpawnTransportText ‖ isServiceTimeoutText` go verify-first (single bounded read-only `verifyListProbe`, never resend the write).
   Reverse observations are always Unknown (present-after-remove / absent-after-clone never claim StillInstalled/VerifyFailed); before/after pin drift → `VerifyUnknown` (identity-changed).
   `busy = loading` disables 7 buttons + dialog (positive/negative) while in flight; cancel preserves message, pending follows preserve rules.
   註（#53）：`DualViewModel.refreshLocked()` 已遷移到 `refreshVerified()` 8 命令單一快照（snapshot 1 ＋ typed target 3 ＋ list 1 ＋ re-verify triple 3），VM 只做 `RefreshOutcome→UiState` 映射，第二次 triple 即 strict-equality fence；pinned target 攜帶真 parentId，parent drift fail-closed；serial intra-triple conflict fail-closed, inter-refresh rebuild = whole-world FRESH。 Refresh `Unknown` keeps the old `cloned` list, marks the inventory STALE, and reports `pmReadable=true` (vs `NoDual` which clears `cloned`), an inter-refresh rebuild no longer emits `VERIFY_MISMATCH` (a rebuild between refreshes publishes the whole new world as FRESH on the next refresh), and `Unknown` has four shapes (typed refusal, confirmed id missing from the pm snapshot, Absent with an empty snapshot, and post-list identity drift). `TargetMoved` (confirmed post-list drift → `VERIFY_MISMATCH`, STALE live target, selection cleared; inter-refresh rebuild still whole FRESH). serial==null 前後時 strict equality 僅為 (id,null,parent)：FRESH 只保證同 id/parent 兩次確認 + 列舉成功，不保證 incarnation continuity（能力限制；有 serial 時才消除 resolve→list 漂移）。

   測試驗收分階段：JVM unit tests（CI `testDebugUnitTest` 全綠）只保證併發語意，不替代 S24 實機驗收。
   #24 的 Main dispatcher heartbeat 與 instrumentation responsiveness 驗收為獨立追蹤階段（見 issue #24）。
   `app/src/androidTest/` harness 見 docs/ACCEPTANCE.md §5（CI 綠不斷言測試數，數量隨演進調整）；CI `locale-instrumentation` job 執行 `:app:connectedDebugAndroidTest`（emulator API 29），`build` job 執行 `:app:testDebugUnitTest :core:testDebugUnitTest :hook:testDebugUnitTest`。Emulator instrumentation 驗證 UI/流程，不等於 S24 裝置驗收；JVM 語意與 CI 綠燈不替代 S24 實機驗收。

## Policy draft flow (hook-policy-source P2-P4 semantics)

- App 寫入 LSPosed remote preferences group `dual_policy`（與 hook 側同一 group/key，常數單一來源 `PolicySource`）；僅 owner user（user 0）寫入有效，端到端 DEVICE-UNVERIFIED。
- Hook 側新鮮度（Stage J5 修正，API 102.0.0 無推送）：hook 行程監聽器僅同行程有效，跨行程新鮮度為輪詢（`EffectivePolicyCache` TTL 60 秒重讀永久快取實例，寫入側版本序號＋重啟語義，詳見 `docs/HOOK.md` §1.1）；熱路徑重讀經 `PolicyRefreshBudget` 有界執行器＋50ms stall 預算，超時回 seed／last-known-good（fail-closed）。
- 鎖外 IO：所有 store 讀寫經 `onIo`（`Dispatchers.IO`）切線程，confirm/clear 持 `opMutex` 橫跨有界超時（`POLICY_IO_TIMEOUT_MS` 10s，超時轉 `RECONCILING`＋`CHANNEL`（Unknown fence，pending 保留），由 reconciler 背景核對（不自動重送）；核對中 cancel 為 no-op（`QUEUED` 可取消回 `IDLE`，`SAVING`／`RECONCILING` 不可取消）；終態正常回 `IDLE`，唯有界讀耗盡／總預算超限（`POLICY_RECONCILE_TOTAL_BUDGET_MS` 15s）滯留 `RECONCILING`（`REFRESH_FAILED`／`CHANNEL`＋Known=false＋pending 保留，Tx 存活，後續全 `BUSY`，由 `refreshPolicyDraft` late-join 結清回 `IDLE`）；`FAILED` 僅保留給已證明失敗（store 回 false/拋出））；主執行緒永不打 binder。
- UID 收斂：owner-user 門控以 `Process.myUid()` 拆解 userId（`userIdFromUid`，負值回 -1 fail-closed），非 owner 拒絕且不建 pending；Guard 側 binder 門控另見上文。
- Tx 結清（Stage G1，`decidePolicyTxSettle` 唯一真相）：deferred 已完成＋權威讀==預期→SUCCESS，deferred 已證失敗→FAILED＋Unknown（樂觀讀不得翻案），deferred 完成＋讀非預期→mismatch `REFRESHED`；deferred 未完成時任何 Known（A/B）皆 inconclusive 續留（Known(B) 永 inconclusive，Known(A) 不得 mismatch-settle）。世代拆分：顯示 `policyFreshnessGen` vs 所有權 `policyOwnershipGen`（reconciler／late-join 只比 version＋ownership）；Tx 存活期 refresh yielding 只推 freshness，不碰 phase/pending。
- 跨 VM（Stage G2）：`PolicyTxCoordinator` App-scoped（`GalaxyApp` 單例），`activeTx` 跨 VM 唯一真相；Tx 攜 pending，新 VM init 認領（繼承 fence＋`RECONCILING`＋Unknown＋reconciler join 同一 deferred）；`deferred.invokeOnCompletion` 單次 auto-recheck（有界獨立池，不耗 reconciler 預算，Unknown→unresolved 待手動 `refreshPolicyDraft` late-join）；終態 `tryClaimCoordinatorPublish` CAS 勝者發布；reconciler 總預算 15 秒，超限誠實 unresolved 滯留待 late-join。
- 完整語義（pending 世代、TTL、fence、二步清除）見 `docs/USAGE.md` §政策寫入流程（含同世代綁定說明與政策 TTL 對照表：App 側確認／blind-clear arm 5 分鐘、store IO 10 秒、reconciler 總預算 15 秒、auto-recheck 單次 vs hook 側快取 60 秒／失敗節流 5 秒）。

## Non-Goals

- No Secure Folder / Knox container access.
- No Knox warranty-bit bypass or system image patching.
- No copied GPL code; all parsers and hooks are rewritten from scratch.
