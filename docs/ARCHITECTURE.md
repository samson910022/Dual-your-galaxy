# Architecture

This document describes the privilege abstraction of Galaxy-Dual-app-Plus.
All code is original and MIT-licensed. Third-party projects are idea-only references.

## Goals

- Support Samsung Dual Messenger / Dual Apps without touching Secure Folder.
- Prefer LSPosed injection when available, fall back to Shizuku shell channel.
- Keep parsing and execution testable without Android framework dependencies.

## Modules

- `app`: UI layer, owns user actions and status display.
- `core`: pure-Kotlin privilege routing, command gateway, and `pm` output parsing.
- `hook`: LSPosed module (API 102), loaded only inside target Samsung processes.

## PrivilegeRouter

```kotlin
// Pseudocode: hook-neutral — hook signal never grants privilege (#19).
// App side calls routePrivilege(hasLsposed = false, ...); hook state is display-only.
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
> `DualAppManagerService` 雙方法 deoptimize＋hooked（僅註冊，
> 回調觸發未觀察）；(3)marker 主路徑歷史 scope=system_server
>（早於本次開機約 5.5h，歷史證據）；(5)App 一鍵：ROOT 一鍵 `cloned:`、
> 已授權 SHIZUKU 一鍵（已安裝 verify 單次）。
> 未驗 —— (3)callback 可驗日誌缺、(4)非空政策生效（預設空政策，
> observe-only）、寫後以 listing 為準（`pm path` 僅輔助，見 #22）、
> 目標身分完整邊界（見 #21）、當前 source 可追溯部署。
> 路由 hook-neutral：`routePrivilege` 以 `hasLsposed = false` 呼叫，
> `channelReadyFor` 忽略 hook 訊號；marker 僅顯示（F4b-P2，見 #19／#27）。

- `Privilege` enum: `NONE` (no write), `SHELL` (Shizuku / ADB shell), `ROOT` (su probe).
- Router is a pure function of two probes: `hasRoot` (su) and `isShizukuAlive()`; hook signal never enters routing (hook-neutral, LSPosed mode is NoOp placeholder).
- UI disables write actions when privilege is `NONE`.

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
    exactly that). Owner-uid gate invariant (Phase A P0, DEVICE-UNVERIFIED):
    only the owner app uid (expected >= 10000/AID_APP_START) may EXEC;
    privileged/shared uids (root 0, system 1000, shell 2000) and resolution
    failure fail closed.
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
  direct + gateway cat; whitelist policy still pending).

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
5. Hook path (ROOT) may bypass shell by calling the dual-app service directly.
6. Concurrency boundary (#24; busy = `UiState.loading`, merge = coalesce):
   `opMutex` serializes probe/refresh/clone/remove; rapid refresh taps coalesce
   into one trailing run, clone/request-remove/confirm dedup by op key (`already in progress`,
   three namespaces `clone:<pkg>` / `request-remove:<pkg>` / `remove:<pkg>@<id>#<serial>#<ts>` generation-bound by serial);
   every repo call runs via `onIo` on `Dispatchers.IO`.
   Write replies `timedOut ‖ transportUnknown ‖ isPostSpawnTransportText ‖ isServiceTimeoutText` go verify-first (single bounded read-only `verifyListProbe`, never resend the write).
   Reverse observations are always Unknown (present-after-remove / absent-after-clone never claim StillInstalled/VerifyFailed); before/after pin drift → `VerifyUnknown` (identity-changed).
   `busy = loading` disables 7 buttons + dialog (positive/negative) while in flight; cancel preserves message, pending follows preserve rules.
   註：`DualViewModel.refreshLocked()` 目前仍走 9 命令 resolve-then-reverify 舊路徑（`DualViewModel.kt:399-490`），遷移到 `refreshVerified()` 5 命令為 follow-up；兩者同為 fail-closed，僅命令數差異。

   測試驗收分階段：JVM unit tests（CI `testDebugUnitTest`）只保證併發語意，不替代 S24 實機驗收。
   #24 的 Main dispatcher heartbeat 與 instrumentation responsiveness 驗收為獨立追蹤階段（見 issue #24；`app/src/androidTest` 尚不存在，本 PR 不新建）。
   本 PR 只保證 JVM 語意 + CI 綠燈。

## Non-Goals

- No Secure Folder / Knox container access.
- No Knox warranty-bit bypass or system image patching.
- No copied GPL code; all parsers and hooks are rewritten from scratch.
