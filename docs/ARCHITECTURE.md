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

- `Privilege` enum: `NONE` (no write), `SHELL` (Shizuku / ADB shell), `ROOT` (LSPosed / su).
- Router is a pure function of two probes: `isHookActive()` and `isShizukuAlive()`.
- UI disables write actions when privilege is `NONE`.

## Executor (CmdGateway)

- `CmdGateway` interface exposes `run(cmd: List<String>): CmdResult`.
- Implementations:
  - `ShellGateway`: delegates to Shizuku `newProcess` when `SHELL` is granted.
  - `RootGateway`: delegates to hooked service or `su -c` when `ROOT` is granted.
  - `NoOpGateway`: returns failure when `NONE`, prevents accidental writes.
- Callers never branch on ROOT vs SHELL directly; they call the injected gateway.

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
