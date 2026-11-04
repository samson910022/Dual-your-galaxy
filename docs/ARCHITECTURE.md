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
// Pseudocode: selects the best available privilege level.
val privilege: Privilege = when {
    hookActive() -> Privilege.ROOT   // LSPosed module is enabled
    shizukuAlive() -> Privilege.SHELL // Shizuku binder reachable
    else -> Privilege.NONE            // read-only mode
}
```

> Honesty note (H4) [待實機驗證]: the router pseudocode above is the design
> intent. The app-side hook flag does NOT feed routing: `routePrivilege` is
> called with `hasLsposed = false` and readiness via `channelReadyFor`
> ([已支援] pure gate, unit-tested) ignores any hook signal -- a present file
> marker is existence+scope evidence, not proof of a live hook, so it must
> never grant privilege (#19 principle). Hook state rides separately in
> `UiState.hookActive/hookScopes` (F4b-P2 file marker, display-only) for the
> LSPosed picker row. The hook module itself is an observe-only placeholder
> (empty policy) until a non-empty policy source is wired (see `docs/HOOK.md`
> §1.1). DEVICE-UNVERIFIED: no on-device hook load has been observed.

- `Privilege` enum: `NONE` (no write), `SHELL` (Shizuku / ADB shell), `ROOT` (LSPosed / su).
- Router is a pure function of two probes: `isHookActive()` and `isShizukuAlive()`.
- UI disables write actions when privilege is `NONE`.

## Executor (CmdGateway) — F4a 真通道已接線 [DEVICE-UNVERIFIED]

- `CmdGateway` interface exposes `run(cmd: String): CmdResult` as the primary
  entry point (`run(argv: List<String>)` is a convenience overload that joins
  argv with spaces; prefer `run(String)`).
- Implementations (app side, all DEVICE-UNVERIFIED: no on-device round-trip):
  - `ShizukuCmdGateway`: SHELL channel via the Shizuku UserService
    `GalaxyExecService` (`Shizuku#newProcess` was removed in Shizuku API
    13.1.5; the official guide mandates UserService instead -- F4a implements
    exactly that).
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
- Tolerant parser: ignores unknown lines, trims whitespace, handles empty output.
- Unit-testable with static golden strings, no device required.

## Data Flow

1. UI asks `PrivilegeRouter` for current level.
2. UI builds a `CmdGateway` matching that level.
3. Gateway runs `pm` / `dumpsys` commands.
4. Raw stdout goes through `PmOutputParser` into UI state.
5. Hook path (ROOT) may bypass shell by calling the dual-app service directly.

## Non-Goals

- No Secure Folder / Knox container access.
- No Knox warranty-bit bypass or system image patching.
- No copied GPL code; all parsers and hooks are rewritten from scratch.
