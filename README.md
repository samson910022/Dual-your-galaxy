# Galaxy-Dual-app-Plus

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> LSPosed 優先、Shizuku 降級，絕不觸碰 Secure Folder。
> 目前路由純函數、命令封裝、真通道組裝＋手動 picker＋hook marker 顯示有單元測試；App 真通道＋picker＋marker 顯示接線完成但全部分支未經實機驗證（見下文「實作狀態」）。

## 目標 (Goals)

- 在三星 One UI 裝置上啟用並管理 **Dual Messenger / Dual App** 雙開（目標，未經實機驗證）。
- 提權策略（真通道＋手動 picker＋hook marker 顯示已接線，[待實機驗證] DEVICE-UNVERIFIED）：**LSPosed 模式（NoOp 佔位＋file marker 顯示，`HookMarker`→`HookStateProvider`→picker 行，僅顯示、永不授權，[待實機驗證]），su ROOT（`SuCmdGateway` 經 `su -c`），Shizuku 降級（Shell，`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除）**。
- 無 ROOT、無 LSPosed 時，設計上自動降級為 Shizuku 模式執行 `pm` 指令（`core` 純函數 [已支援]；App 端 F4a 已接線 [待實機驗證]：Factory 在無 su 且 binder＋授權具備時選 `ShizukuCmdGateway`，探測通過才 `channelReady`）。
- **不碰 Secure Folder（Knox 容器）**：不讀寫、不掛載、不繞過 Knox。
- 模組化設計：`app`（UI）、`core`（提權抽象與命令閘道）、`hook`（LSPosed 注入）。

## 架構總覽 (Overview)

```
app  -> core (PrivilegeRouter / Executor / PmOutputParser)
hook -> XposedModule (DualHook, apiVersion 102)
```

- `core`：定義 `Privilege`（NONE/SHELL/ROOT）、`CmdGateway` 介面、`PmOutputParser` 解析器。
- `app`：UI 層（`MainActivity` 已有 Clone／Remove／Refresh／請求 Shizuku 授權按鈕＋四通道選擇按鈕＋Hook marker 行＋`StateFlow` 收集＋Shizuku binder／授權生命週期接線；已接入真 gateway＋picker＋marker 顯示［待實機驗證］DEVICE-UNVERIFIED：ROOT→`SuCmdGateway`（`su -c` argv 單參直傳）、SHELL→`ShizukuCmdGateway`（UserService `GalaxyExecService`，`newProcess` 在 13.1.5 已移除故不用）、LSPosed→NoOp 佔位（寫入拒絕，marker 僅顯示）；`ChannelPreference`（AUTO 預設，persist）經 `assembleChannel` 在啟動／`selectChannel` 解析，選不到回 `NoOpGateway` fail-closed；`SuElevator`／`ShizukuElevator` 探測真通道，`channelReady` 僅所選通道探測通過才 `true`（`channelReadyFor` 純函數 [已支援]：hook 訊號永不授權）），目標是透過 `core` 選擇最佳可用提權通道。
- `hook`：LSPosed 模組佔位，hook 三星雙開白名單與服務，僅在已授權環境載入；政策集合預設為空，屬 observe-only（會安裝、會記 log，但不改變行為），詳見 [docs/HOOK.md](docs/HOOK.md) 第 1.1 節。

詳見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 實作狀態 (Status, Phase F1 誠實標示)

標示體系（與 [docs/USAGE.md](docs/USAGE.md) 一致）：**[已支援]** = 有程式碼＋單元測試／CI 閘門；**[待實作]** = 程式碼未接通；**[待實機驗證]** = 程式碼存在但無三星實機證據，一律不宣稱可用。

- [已支援] profile 解析安全邊界（`resolveDualUserId`：只認唯一 DUAL、永不選 user 0、多 DUAL／無 DUAL 直接拒絕寫入，見 `DualSafetyTest`）。
- [已支援] `pm` 命令封裝（`DualRepository.cloneVerified / removeVerified / refresh`：單快照釘選＋`pm path` 二次確認，見 `CloneVerifyRefreshTest`）。
- [已支援] lint／CI 閘門（`./gradlew` 固定 8.7、fail-fast quality gates、DEX 檢查）。
- [待實機驗證] App 內一鍵 clone 真通道（已接線 DEVICE-UNVERIFIED：`MainActivity` 已有 Clone／Remove／Refresh／請求 Shizuku 授權按鈕＋四通道選擇按鈕＋Hook marker 行＋`StateFlow` 收集，Shizuku binder／授權生命週期已接；`DualViewModel.clone/remove/refresh/recheckPrivilege/selectChannel` 邏輯存在；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），選不到回 `NoOp`＋唯讀 fail-closed，`channelReady` 改由各通道探測結果 gating（`channelReadyFor` [已支援]：hook 訊號永不授權，NONE 永不就緒）；marker round-trip、binder round-trip、授權四情境、su 實機行為、端到端 pm 皆未驗）。
- [待實作] 非空 whitelist policy（`EXTRA_WHITELIST_PACKAGES` 預設為空，hook 為 observe-only；接上政策來源前不放寬任何包名）。
- [待實機驗證] LSPosed 載入（system／daagent scope 注入、重啟後 log 確認，無實機）。
- [待實機驗證] Shizuku 四情境（有／無 binder × 有／無授權，以及重啟後重啟動流程，無實機）。
- [待實機驗證] 雙開實機（`pm install-existing / path / uninstall --user <dualId>` 端到端，無實機）。

凡標 [待實作]／[待實機驗證] 者，皆不可描述為「可用功能」；下文路由／自動模式描述的是設計意圖與 `core` 純函數行為，不是已接通的 App 流程。

## 需求 (Requirements)

- Android 10+，建議三星 One UI 4.0+，`compileSdk 34`。
- Kotlin 2.x，AGP 8.x，JDK 17。
- 可選：LSPosed（優先）、Shizuku（降級）。

## 快速開始 (Quick Start)

```bash
# Clone
git clone https://github.com/samson910022/Galaxy-Dual-app-Plus.git
cd Galaxy-Dual-app-Plus

# Build (Gradle 8.7 via the committed wrapper, see gradle/wrapper/README.md)
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

> 注意：本倉庫已提交完整 Gradle wrapper（固定 8.7），CI 與本機一律使用 `./gradlew`；CI 另以 `gradle/actions/setup-gradle` 提供快取，無需自行安裝 Gradle。

## 提權路由 (Privilege Routing) — `core` 純函數 [已支援]＋App 真通道已接線 [待實機驗證] DEVICE-UNVERIFIED

路由純函數（`routePrivilege`＋`channelReadyFor`，[已支援]，單元測試覆蓋）依序判斷；App 端探測＋選擇框架（`probeChannels`／`recheckPrivilege`／`selectChannel`＋手動 `ChannelPreference` picker＋Shizuku 生命週期）已接真 gateway（`SuCmdGateway` 經 `su -c`、`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；hook 訊號為 file marker 存在性證據，僅顯示、永不進入路由，[待實機驗證]；`selectChannel` 動態換 gateway；選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`，hook 永不授權，NONE 永不就緒）。以下順序仍是設計＋純函數行為，App 端切換全數 [待實機驗證]（binder round-trip、授權四情境、su 實機行為、marker round-trip 皆未驗）：

1. 偵測 LSPosed 模組是否啟用 → `Privilege.ROOT`。
2. 否則偵測 Shizuku 是否可用 → `Privilege.SHELL`。
3. 否則 → `Privilege.NONE`，僅顯示唯讀狀態，不執行寫入。

## 自動模式 (Auto Mode) — 真通道已接線，全部 [待實機驗證] DEVICE-UNVERIFIED

已接線（picker＋真通道＋marker 顯示，[待實機驗證] DEVICE-UNVERIFIED）：AUTO 按 su→Shizuku→NoOp 解析；ROOT→`SuCmdGateway`（`su -c`）、SHELL→`ShizukuCmdGateway`（UserService，`newProcess` 在 13.1.5 已移除）、LSPosed→NoOp 佔位（寫入拒絕，marker 僅顯示）；`channelReady` 為各通道探測通過才 `true`（未通過／選不到時仍 `false` 拒寫，按鈕寫入被 `isWritable()` 攔下）。以下為接線後預期行為，無實機切換證據（marker round-trip、binder round-trip、授權四情境、su 實機行為、端到端 pm 皆未驗）：

- 有 LSPosed（模組已勾選並啟用，file marker 存在性證據 [待實機驗證]）→ App 內切 LSPosed 模式顯示 marker 行（`已啟用（…）`／`未偵測到`），執行通道為 NoOp 佔位；su ROOT 通道需 su 檔存在被解析且 `SuElevator`（`id -u`＝0）探測通過才 `channelReady`（[待實機驗證]：marker round-trip、su 實機行為未驗；hook 現為 observe-only，不放寬任何包名，見 [docs/HOOK.md](docs/HOOK.md)）。
- 沒有 LSPosed 但 Shizuku 可用 → 預期降級走 Shizuku 通道（`Privilege.SHELL`），執行 `pm install-existing / uninstall --user / path --user`（Shizuku binder／授權生命週期已接＋F4a 真 gateway 已接［待實機驗證］；Factory 在無 su 且 binder alive＋已授權時選 `ShizukuCmdGateway`，`ShizukuElevator` 探測通過才 `channelReady`；[待實機驗證]：binder round-trip、授權四情境無實機）。
- 兩者皆無 → `Privilege.NONE` 唯讀模式（[已支援]：路由與 `NoOpGateway` 拒寫＋`channelReady` false fail-closed；所選真通道探測通過即為 `true`；注意 AUTO 解析＋`selectChannel()` 可切換 gateway，`refresh()`／`recheckPrivilege()` 重探更新顯示（privilege／`hookActive`／`hookScopes`／`channelReady`／msg）；顯示更新本身 [待實機驗證] DEVICE-UNVERIFIED）。

詳見 [docs/USAGE.md](docs/USAGE.md) 第 1、4 節。

## 不碰 Secure Folder (Policy)

- 本專案不會存取 `/data/media/0/../knox`、Knox 容器、Secure Folder 內部資料。
- 不提供 Knox 規避、KnoxPatch 式補丁、Knox 熔斷規避。

## 免責聲明 (Disclaimer)

- 本專案不碰 Secure Folder / Knox：不讀寫、不掛載、不繞過 Knox 容器；任何 Knox 熔斷規避皆明確不在範圍內。
- 寫入設計上需 ROOT 或 Shizuku 環境，且需所選真通道探測通過（`channelReady` true）＋privilege 非 NONE（`isWritable()` fail-closed；LSPosed 模式為 NoOp 佔位、寫入一律拒絕）；在 ROOT／Shizuku 裝置上有可能寫入（選不到／探測未過時仍拒寫），但全部分支 DEVICE-UNVERIFIED（marker round-trip、binder round-trip、授權四情境、su 實機行為、端到端 pm 皆未驗）；操作系統服務本來就有風險（請先備份），後果自負。
- 僅供學習研究與個人裝置使用；請遵守當地法規與三星服務條款。

## Credits

- 詳見 [docs/CREDITS.md](docs/CREDITS.md)。
- 本專案程式碼皆為自寫（MIT），第三方僅為思路參考，已重寫不傳染授權。

## English Summary

Galaxy-Dual-app-Plus is a scaffold towards enabling Samsung Dual Messenger / Dual Apps
via a privilege router (LSPosed first, Shizuku fallback). It never touches Secure Folder (Knox).
Modules: `app` (UI wired with Clone/Remove/Refresh/permission buttons + channel picker + hook marker row + StateFlow + Shizuku lifecycle; true-channel + picker + marker display wired [DEVICE-UNVERIFIED]: ROOT via `SuCmdGateway` (`su -c`), SHELL via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5), LSPosed via NoOp placeholder (writes refused, marker display-only), `ChannelPreference` (AUTO default, `selectChannel` swaps gateway + re-probes) with `NoOp` fail-closed fallback, `channelReady` true only when the selected channel's probe passes (`channelReadyFor`, tested)), `core` (privilege abstraction, tested), `hook` (LSPosed module,
API 102, observe-only placeholder with empty policy + file-marker writer).
Status: routing pure function (`routePrivilege` + `channelReadyFor`), `pm` command encapsulation, channel picker + marker display, gateway assembly JVM tests, hook pure-function tests (`WhitelistPolicy`/`WhitelistPatch`/`PolicySource`/`HookMarker`), and lint/CI gates are implemented
with unit tests; in-app one-click true channel + picker + marker display are wired but DEVICE-UNVERIFIED and non-empty whitelist policy is TODO;
hook marker round-trip, Shizuku binder round-trip plus all four auth scenarios, su behavior, and end-to-end on-device dual flows are unverified on real hardware.
Nothing marked TODO/unverified is claimed as working. See `docs/CREDITS.md` for references.
All skeleton code is original and MIT-licensed. See `docs/CREDITS.md` for references.

## License

MIT, Copyright 2026 samson910022. See [LICENSE](LICENSE).
