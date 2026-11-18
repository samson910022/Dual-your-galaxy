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
- `hook`: LSPosed module (API 102), loaded only inside target Samsung processes. BBC injector anchors `PmWrapper#getPossibleDualAppPackages` (union-only append of user-written policy items into Samsung's 「可用的應用程式」 list; PROTECTIVE / identity fast-path / fail-closed; device-verified 2026-11-17, pre-unification evidence); its effect switch is `BBC_AVAILABLE_APPS_INJECTION=true` — independent of, and not opening, the sealed Stage-1 `NON_EMPTY_POLICY_EFFECTIVE_GATE=false` (see `docs/HOOK.md` §1.3a／§1.4).

## GuardXposedProvider (policy provider parity)

- The libxposed `service` AAR declares its own exported `XposedProvider` at
  `content://<applicationId>.XposedService` with no permission, so any app could
  call `SendBinder` and deliver a fake `IBinder` to hijack the policy channel
  (`XposedServiceHelper.onBinderReceived`). The app manifest removes that node
  (`tools:node="remove"`) and declares `GuardXposedProvider` at the same
  authority.
- `GuardXposedProvider` delegates `call()` to the library provider instance but
  gates it: only root (`uid == 0`), system (`appId == 1000` — exact `1000`, or the
  same-user system UID in multi-user, `uid % 100_000 == 1000` within the app's
  user), or the app itself (`uid == Process.myUid()`) may deliver a binder;
  **everything else is rejected — including shell (`2000`) and general app
  UIDs** — and gets `null`. The trusted set is exactly that; the
  `uid < FIRST_APPLICATION_UID` superset is **not** trusted. The UID checked is
  the kernel-set `Binder.getCallingUid()`, never a caller-supplied value. CRUD
  stays inert (`null`/`0`).
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
> 未驗 —— (3)callback 可驗日誌缺（`system_server`／daagent 查詢面）、(4)非空政策之白名單 Stage-1 查詢面 DEVICE-UNVERIFIED（sealed false，零行為變更 by design；BBC 注入面已於 2026-11-17 驗證——pre-unification build：`BBC-HOOK merged +1 packages (total 7, pid=21472)`；unified build 已驗 2026-11-18（BBC1–BBC3；BBC4/BBC5 launch 失敗；BBC6 毀滅分身——不得標 ☑）、寫後以 listing 為準（`pm path` 僅輔助，見 #22）、
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
> Build-note: `gradle/verification-metadata.xml` enforces dependency
> verification in **sha256** mode with `verify-signatures=false`; regenerate via
> `./gradlew --write-verification-metadata sha256 <tasks>` (never hand-edit hashes
> except the aapt2 caveat below). It covers all currently-run classpaths
> (incl. lint / debug / test); the residual is the emulator/device RUNTIME
> path (artifacts are pinned; the on-device execution is unverified).
> all workflow actions in both android.yml and release.yml are SHA-pinned; each SHA re-verified against its git tag (2026-11-16 audit): actions/checkout 11d5960 v4.4.0, setup-java cf277c60 v4.9.1, setup-gradle d9c87d48 v3.5.0, upload-artifact ea165f8 v4.6.2, download-artifact d3f86a10 v4.3.0, action-gh-release 3bb1273 v2.6.2, android-emulator-runner a421e438 v2.38.0. Caveat: the aapt2
> **platform-classifier** artifacts
> (`-windows.jar`/`-osx.jar`) are not resolvable on this Linux host, so those
> entries were added manually with sha256 hashes verified locally from Google's
> Maven (`dl.google.com`); the linux classifier stays Gradle-generated.
> Wrapper provenance: the committed `gradle/wrapper/gradle-wrapper.jar`
> (81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f) matches
> Gradle 8.13's published wrapper jar — the 8.7 distribution download itself is
> independently pinned via `distributionSha256Sum` (official 8.7-bin checksum),
> so the skew affects only wrapper bootstrap, not the 8.7 toolchain. Follow-up
> (not done): swap the committed jar for the official Gradle 8.7 wrapper jar to
> remove the version skew.
>
> Device + SDK 矩陣（release gate 實證口徑：JVM／emulator 綠燈不等於 S24 裝置驗收）：
>
> | 環境 | SDK／版本 | 用途 | 狀態 |
> | --- | --- | --- | --- |
> | S24 SM-S9210（OneUI `oneui=90000`、Android 17／API 37） | API 37 實機 | 五層部分已驗證的唯一實機來源（見 `docs/UX_REVIEW_S24.md` §5） | 歷史證據（2026-11-05）；當前 source 可追溯部署未驗 |
> | Emulator API 29（minSdk floor） | API 29 | CI `locale-instrumentation` job：`:app:connectedDebugAndroidTest` | 每 PR 自動執行 |
> | Emulator API 34（compileSdk／targetSdk） | API 34 | `instrumentation-manual` job（`workflow_dispatch`）：同上 harness＋provenance | 手動執行 |
> | Build baseline | compileSdk 34、minSdk 29、targetSdk 34、AGP 8.5.2、Gradle 8.7、JDK 17 | PR CI `build` job：JVM 單元測試＋lint＋honesty／Knox／GPL gates | `testDebugUnitTest` 全綠只保證語意，不替代上列裝置驗收 |
> | 非空政策生效 gate（`NON_EMPTY_POLICY_EFFECTIVE_GATE=false` sealed 常閉） | —（code gate，非裝置驗證） | JVM 鎖定（`PolicySourceTest`／`EffectivePolicyCacheTest`）＋硬閘門見 `docs/HOOK.md` §1.4 | 常閉；JVM 綠燈不開 gate，開 gate 只認硬閘門裝置證據；任何未來翻轉需另一份 SAMSUNG 階段 spec＋使用者明示授權 |
> | BBC 注入效應開關（`BBC_AVAILABLE_APPS_INJECTION=true`） | —（code gate，非裝置驗證） | BBC merge 僅擴展 Dual Messenger「可用的應用程式」清單，PROTECTIVE／union-only／identity fast-path／fail-closed；裝置證據 2026-11-17（S23 Ultra／Tab S7，pre-unification build）；unified build 已驗 2026-11-18（BBC1–BBC3 重驗 OK；BBC4/BBC5 launch 不可用（app SSAID 崩潰）；BBC6=系統自動卸載，含 BBC6 ☑）；Stage-1 封緘不受影響 | 效應面僅限 BBC 清單可見性；設 `false` 即 BBC 零行為變更；2026-11-17 授權涵蓋僅 BBC 注入面 |
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
  preferences — whole-set two-phase write (`HookPolicyStore`, group
  `dual_policy`/key `extra_whitelist_packages`, owner-user write); hook read via
  `EffectivePolicyCache` TTL poll. BBC injection effect is device-verified
  (2026-11-17, pre-unification build; re-verified 2026-11-18 on the unified build, BBC1– BBC3 only)
  BBC4/BBC5 launch fails; BBC6 destroys the dual app — not untested, broken (see BBC6_SSAID_INCIDENT.md).

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
- App 寫入 LSPosed remote preferences group `dual_policy`（與 hook 側同一 group/key，常數單一來源 `PolicySource`）；僅 owner user（user 0）寫入有效。BBC 注入面 2026-11-17 裝置已驗證（pre-unification build；`BBC-HOOK merged +1 packages (total 7, pid=21472)`）；unified build 讀者接線已於 2026-11-18 重驗（BBC1–BBC3 重驗 OK；BBC4/BBC5 launch 不可用（app SSAID 崩潰）；BBC6=系統自動卸載）；白名單 Stage-1 查詢面 DEVICE-UNVERIFIED（`NON_EMPTY_POLICY_EFFECTIVE_GATE=false` 封緘，零行為變更 by design）。
- Hook 側新鮮度（Stage J5 修正，API 102.0.0 無推送）：hook 行程監聽器僅同行程有效，跨行程新鮮度為輪詢（`EffectivePolicyCache` TTL 60 秒重讀永久快取實例；`extra_whitelist_version` 版本序號 **規劃中／未實作**，新鮮度目前僅靠 TTL＋重啟語義，詳見 `docs/HOOK.md` §1.1）；熱路徑重讀經 `PolicyRefreshGate`（at-most-one guard＋stuck breaker）＋零隊列執行器＋50ms stall 預算，超時／爭用／拒收／熔斷一律回 seed／last-known-good（fail-closed）。
- 鎖外 IO：所有 store 讀寫經 `onIo`（`Dispatchers.IO`）切線程，confirm/clear 持 `opMutex` 橫跨有界超時（`POLICY_IO_TIMEOUT_MS` 10s，超時轉 `RECONCILING`＋`CHANNEL`（Unknown fence，pending 保留），由 reconciler 背景核對（不自動重送）；核對中 cancel 為 no-op（`QUEUED` 可取消回 `IDLE`，`SAVING`／`RECONCILING` 不可取消）；終態正常回 `IDLE`，唯有界讀耗盡／總預算超限（`POLICY_RECONCILE_TOTAL_BUDGET_MS` 15s）滯留 `RECONCILING`（`REFRESH_FAILED`／`CHANNEL`＋Known=false＋pending 保留，Tx 存活）。handover 登記給行程級 `PolicyTxCoordinator`（其 reconciler 跑在 coordinator 的 app-scoped 域，不隨 VM 死亡），負責把 Tx 收斂回 `IDLE`／終態；joinable（armed／已交接）Tx 由 init reclaim 的兄弟 VM 認領為 `RECONCILING` 並 join 同一 deferred；僅「owner 存活且 Tx 未 arm（PREPARING）」的兄弟 VM 見 `BUSY`，直到 owner 終態後自行收斂；不一律 `BUSY`；`RECONCILING` 相位的「稍後刷新」僅為手動 late-join 提示；`FAILED` 僅保留給已證明失敗（store 回 false/拋出））；主執行緒永不打 binder。
- UID 收斂：owner-user 門控以 `Process.myUid()` 拆解 userId（`userIdFromUid`，負值回 -1 fail-closed），非 owner 拒絕且不建 pending；Guard 側 binder 門控另見上文。
- Tx 結清（Stage G1，`decidePolicyTxSettle` 唯一真相）：deferred 已完成＋權威讀==預期→SUCCESS，deferred 已證失敗→FAILED＋Unknown（樂觀讀不得翻案），deferred 完成＋讀非預期→mismatch `REFRESHED`；deferred 未完成時任何 Known（A/B）皆 inconclusive 續留（Known(B) 永 inconclusive，Known(A) 不得 mismatch-settle）。世代拆分：顯示 `policyFreshnessGen` vs 所有權 `policyOwnershipGen`（reconciler／late-join 只比 version＋ownership）；Tx 存活期 refresh yielding 只推 freshness，不碰 phase/pending。
- 跨 VM（Stage G2）：`PolicyTxCoordinator` App-scoped（`GalaxyApp` 單例），`activeTx` 跨 VM 唯一真相；handover 登記的 Tx 由 coordinator scope 上的 reconciler 接手（不隨 VM 死亡），VM 死亡不阻斷收斂；Tx 完成前建立的兄弟 VM：joinable（armed／已交接）者由 init reclaim 的兄弟 VM 認領為 `RECONCILING` 並 join 同一 deferred；僅「owner 存活且 Tx 未 arm（PREPARING）」的兄弟 VM 見 `BUSY`，直到 owner 終態後自行收斂；不一律 `BUSY`；Tx 攜 pending，新 VM init 認領（繼承 fence＋`RECONCILING`＋Unknown＋reconciler join 同一 deferred）；`deferred.invokeOnCompletion` 單次 auto-recheck（coordinator app-scoped 域＋`Dispatchers.IO` 共用 IO 池，`boundedReconcileRead` 為容量有界讀取 wrapper，非獨立 pool，不耗 reconciler 預算，Unknown→unresolved；此時無 ground truth，維持 `RECONCILING`，「稍後刷新」僅為手動 late-join 提示）；終態 `tryClaimCoordinatorPublish` CAS 勝者發布；reconciler 總預算 15 秒，超限誠實 unresolved 滯留（有 ground truth 則收斂至 `IDLE`；無 ground truth 則維持 `RECONCILING`，待手動 `refreshPolicyDraft` late-join）。terminal-publish arbitration（Stage P2）= Tx-scoped adoption-epoch fence：busy-waiter 與 async-init 認領在 commitMutex 內 bump 共享 `PolicyTx.adoptionEpoch`（sync-init 認領（joinable）不再於 init 內直接 bump：init 的 commitMutex tryLock 快照僅為「建議性」觀察，認領本身——本地 CAS＋adoptionEpoch bump＋RECONCILING fence flip——一律經 launchReclaimAsync 於 commitMutex 內線性化，與 busy-waiter 及 async-init 同臂，詳見 DualViewModel.kt sync-init 註記；三臂的 fence 皆於 claim 時與同一 mutex 內比對，為 fail-closed。attach 時快照，claim 時於同一 mutex 內比對，fence 失敗即 EPOCH_STALE（不消耗 claim、不 bump rev、不發布）；讀取橫跨認領的 worker retain 並讓位 adopter；舊 worker 在認領 bump 前線性化則合法勝出（linearization point = claim CAS inside commitMutex，順序以 mutex 到達為準）。兄弟 VM 可各自本地採納同一 Tx（coordinator.activeTx 不隨認領消費），恰好一次發布由 epoch fence＋claim 的 commitMutex 重新檢查保證。
- 完整語義（pending 世代、TTL、fence、blind-clear 二次確認〈僅 Unavailable 瞬斷：TTL＋世代＋disarm，Corrupt 走修復流程〉）見 `docs/USAGE.md` §政策寫入流程（含同世代綁定說明與政策 TTL 對照表：App 側確認／blind-clear arm 5 分鐘、store IO 10 秒、reconciler 總預算 15 秒、auto-recheck 單次 vs hook 側快取 60 秒／失敗節流 5 秒）。

### Poison 封裝（remote prefs 樂觀值隔離，`HookPolicyStore` 內部狀態）

- 封裝：`remotePoison: AtomicReference<PoisonState(entries, gen, overflow)>`（copy-on-write，全域單例，弱鍵；`internal` 僅測試可見，VM 經 `loadResult/loadForRepair/repairCorrupt` 間接使用，不直觸狀態）。
- 證據：`remoteCorruptEvidence: AtomicReference<Map<WeakPrefKey, CorruptEvidence>>`（per-instance CCE 診斷，first-wins；與 poison fence 並存，不替代）。
- 狀態表（set/cleared 條件）：
  | 狀態 | 寫入條件 | 清除條件 |
  |---|---|---|
  | poison fence（某 prefs 加毒） | `prePoison`（save/commit 前，先加毒 gen+1 再 commit）／CCE 讀命中 `ensurePoisoned` | 成功 save/clear 以 token 相等 `clearPoison`（防 lost-poison：併發新毒 token 更大即不清除）；Verified Known 讀（unpoisoned 成功讀） |
  | corrupt evidence（某 instance 腐壞） | `loadResult` 捕獲 CCE → `recordCorruptEvidence`（first-wins） | Verified Known（unpoisoned 成功讀 `clearCorruptEvidence`）或成功修復；transient 失敗保留舊證據 |
  | overflow 閂 | 任一表達 bound（`POISON_MAX_ENTRIES`／`EVIDENCE_MAX_ENTRIES`）採 drop-new＋閂鎖＋`poisonEvictCount/evidenceEvictCount` 可觀測 | 永不自動清除（重啟重置；見 `resetRemotePoisonForTests` 測試重置） |
- TTL/真相單一來源：`docs/USAGE.md` §政策 TTL 對照為準（App 側 5 分鐘／IO 10 秒／reconciler 15 秒；hook 側快取 60 秒／失敗節流 5 秒；單調時鐘）。

## Non-Goals

- No Secure Folder / Knox container access.
- No Knox warranty-bit bypass or system image patching.
- No copied GPL code; all parsers and hooks are rewritten from scratch.
