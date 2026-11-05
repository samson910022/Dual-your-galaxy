# Galaxy-Dual-app-Plus

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 三星 Dual 雙開工具（開發中腳手架，僅有有限情境實機證據）：設計目標為自動提權路由，
> LSPosed 優先、Shizuku 降級，絕不觸碰 Secure Folder。
> 目前路由純函數、命令封裝、真通道組裝＋手動 picker＋hook marker 顯示有單元測試；App 真通道＋picker＋marker 顯示已接線。分層實證（[S24 已驗證]僅限此句，其餘 [待實機驗證]）：App ROOT 一鍵 `cloned:`、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、裸 pm clone、uninstall 回滾、`su -c` 重裝、system hook（DualAppManagerService 雙方法 deoptimize＋hooked）、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取，不等於 callback 執行或包名放寬）、marker 主路徑 scope=system_server（早於本次開機約5.5h 的歷史證據，不能宣稱本次開機有效）。

> **實機證據更新（2026-11-05）**：[S24 調查與追蹤計畫](docs/UX_REVIEW_S24.md) 區分原碼、歷史實機與本輪唯讀證據，並連結 issues #21–#29。指定正常 SHELL clone/remove＋手動通道歷史見該文 §5。下文較舊 F4a 段落中的「全部未驗／啟動時靜態選路」描述不應當成目前完整現況：手動通道、偏好保存與動態重選已實作，但 Auto 降級、安全負向邊界及當前 source 的可追溯裝置驗收仍待完成。

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
- `app`：UI 層（`MainActivity` 已有 Clone／Remove／重新連線並更新／請求 Shizuku 授權按鈕＋四通道選擇按鈕＋Hook marker 行＋`StateFlow` 收集＋Shizuku binder／授權生命週期接線；已接入真 gateway＋picker＋marker 顯示［待實機驗證］DEVICE-UNVERIFIED：ROOT→`SuCmdGateway`（`su -c` argv 單參直傳）、SHELL→`ShizukuCmdGateway`（UserService `GalaxyExecService`，`newProcess` 在 13.1.5 已移除故不用）、LSPosed→NoOp 佔位（寫入拒絕，marker 僅顯示）；`ChannelPreference`（AUTO 預設，persist）經 `assembleChannel` 在啟動／`selectChannel` 解析，選不到回 `NoOpGateway` fail-closed；`SuElevator`／`ShizukuElevator` 探測真通道，`channelReady` 僅所選通道探測通過才 `true`（`channelReadyFor` 純函數 [已支援]：hook 訊號永不授權）），目標是透過 `core` 選擇最佳可用提權通道。
- `hook`：LSPosed 模組佔位，hook 三星雙開白名單與服務，僅在已授權環境載入；政策集合預設為空，屬 observe-only（會安裝、會記 log，但不改變行為），詳見 [docs/HOOK.md](docs/HOOK.md) 第 1.1 節。

詳見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 實作狀態 (Status, Phase F1 誠實標示)

標示體系（與 [docs/USAGE.md](docs/USAGE.md) 一致）：**[已支援]** = 有程式碼＋單元測試／CI 閘門；**[待實作]** = 程式碼未接通；**[待實機驗證]** = 程式碼存在但無三星實機證據，一律不宣稱可用。

- [已支援] profile 基本防護（`resolveDualUserId`：排除 user0、拒絕無／多名稱候選，見 `DualSafetyTest`）；完整平台身分與存在但非Dual的explicit-ID邊界仍待 [#21](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/21) 修正，名稱命中不等於安全身分證明。
- [已支援] `pm` 命令封裝（`DualRepository.cloneVerified / removeVerified / refresh`：單快照釘選＋package listing 二次確認，見 `CloneVerifyRefreshTest`）；錯誤／截斷輸出的可信度由 TrustedList 三態＋白名單＋截斷契約判定（P22 已合併，見 `ParseTrustedTest`／`TruncatedIdentityTest`）。
- [已支援] lint／CI 閘門（`./gradlew` 固定 8.7、fail-fast quality gates、DEX 檢查）。
- [部分已驗證] App 內一鍵 clone 真通道（F4a 已接線＋手動 picker：`MainActivity` 四通道按鈕＋Hook marker 行＋`StateFlow`，`DualViewModel.selectChannel/clone/refresh`；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），選不到回 `NoOp`＋唯讀 fail-closed，`channelReady` gating（`channelReadyFor` [已支援]：hook 訊號永不授權，NONE 永不就緒）。[S24 已驗證]僅限：ROOT 一鍵 `cloned: com.openai.chatgpt`＋`Cloned (35)`、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、`pm uninstall --user 95` 回滾＋`su -c install-existing` 重裝；其餘 Shizuku 未授權路徑／首次選路／binder 死引導、`pm path` 主驗證、marker gateway-`cat` 顯示行仍 [待實機驗證]）。
- [待實作] 非空 whitelist policy（`EXTRA_WHITELIST_PACKAGES` 預設為空，hook 為 observe-only；接上政策來源前不放寬任何包名）。
- [部分已驗證] 雙開實機與 LSPosed 載入（寫後以 package listing 二次確認，不單靠 `pm path`，錯誤／截斷由 TrustedList 三態＋白名單判定（P22 已合併）；DUAL 名稱／固定 user 95 不能單獨授權寫入目標，CLONE 僅認全 token＋殘留驗證＋合法第二 token slash 豁免（`(default)` 為唯一容忍後綴），詳見 docs/USAGE.md §3，待 [#21](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/21)；指定正常 SHELL clone/remove＋手動通道歷史見 [S24 調查](docs/UX_REVIEW_S24.md) §5）。[S24 已驗證]僅限：裸 pm `install-existing --user 95`＋list 二次確認（shell 通道兩次獨立成功）、system hook（DualAppManagerService 雙方法 deoptimize＋hooked）、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取）、marker 主路徑 scope=system_server（早於本次開機約5.5h，不能宣稱本次開機有效）；其餘 LSPosed 寫入、非空政策、`pm path` 主驗證仍 [待實機驗證]。

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

## 提權路由 (Privilege Routing) — `core` 純函數 [已支援]＋App 真通道已接線（ROOT／SHIZUKU 已授權一鍵已抽驗 [S24 已驗證]，其餘 [待實機驗證]）

路由純函數（`routePrivilege`＋`channelReadyFor`，[已支援]，單元測試覆蓋）依序判斷；App 端探測＋選擇框架（`probeChannels`／`recheckPrivilege`／`selectChannel`＋手動 `ChannelPreference` picker＋Shizuku 生命週期）已接真 gateway（`SuCmdGateway` 經 `su -c`、`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；hook 訊號為 file marker 存在性證據，僅顯示、永不進入路由，[待實機驗證]；`selectChannel` 動態換 gateway；選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`，hook 永不授權，NONE 永不就緒）。以下順序仍是設計＋純函數行為，App 端 Shizuku 切換（未授權路徑／首次選路／marker gateway-`cat` 顯示 [待實機驗證]；已授權一鍵、ROOT 一鍵／su／uninstall 已驗，見「實作狀態」）：

1. 偵測 LSPosed 模組是否啟用 → `Privilege.ROOT`。
2. 否則偵測 Shizuku 是否可用 → `Privilege.SHELL`。
3. 否則 → `Privilege.NONE`，僅顯示唯讀狀態，不執行寫入。

## 自動模式 (Auto Mode) — 真通道已接線（ROOT `cloned:`／已授權 SHIZUKU 單次 [S24 已驗證]，指定歷史情境外 [待實機驗證]）

已接線（picker＋真通道＋marker 顯示）：AUTO 按 su→Shizuku→NoOp 解析；ROOT→`SuCmdGateway`（`su -c`）、SHELL→`ShizukuCmdGateway`（UserService，`newProcess` 在 13.1.5 已移除）、LSPosed→NoOp 佔位（寫入拒絕，marker 僅顯示）；`channelReady` 為各通道探測通過才 `true`（未通過／選不到時仍 `false` 拒寫，按鈕寫入被 `isWritable()` 攔下）。App 內 ROOT 一鍵／SHIZUKU 已授權一鍵／裸 pm／su／uninstall 已驗（見「實作狀態」）；Shizuku 未授權路徑／首次選路、`pm path` 主驗證、marker gateway-`cat` 顯示仍未驗：

- 有 LSPosed（模組已勾選並啟用，file marker 存在性證據，僅顯示、永不進入路由）→ App 內切 LSPosed 模式顯示 marker 行（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`）；執行通道為 NoOp 佔位、寫入一律拒絕（[待實機驗證]：LSPosed 寫入、marker gateway-`cat` 顯示未驗；hook 現為 observe-only，不放寬任何包名，見 [docs/HOOK.md](docs/HOOK.md)）。[S24 已驗證]僅限：system hook 雙方法 deoptimize＋hooked、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取）、marker 主路徑 scope=system_server（早於本次開機約5.5h，不能宣稱本次開機有效）；現階段 `hasLsposed = false` 保守值使 hook 通道不被選（F4b／後續 [待實機驗證]）。
- 沒有 LSPosed 但 Shizuku 可用 → 預期降級走 Shizuku 通道（`Privilege.SHELL`），執行 `pm install-existing / uninstall --user / path --user`（Shizuku binder／授權生命週期已接＋真 gateway 已接；Factory 在無 su 且 binder alive＋已授權時選擇，`ShizukuElevator` 探測通過才 `channelReady`）。[S24 已驗證]僅限已授權 binder 下 App 內切 SHIZUKU→就緒→CLONE（已安裝 verify 單次）；未授權路徑／首次選路／binder 死引導、binder round-trip、授權四情境 [待實機驗證]。
- 兩者皆無 → `Privilege.NONE` 唯讀模式（[已支援]：路由與 `NoOpGateway` 拒寫＋`channelReady` false fail-closed；所選真通道探測通過即為 `true`；`selectChannel()` 可在 mutex 內重建 gateway 並重探，`recheckPrivilege()` 在 AUTO 環境變化時重選（見 [docs/USAGE.md](docs/USAGE.md) 第 1 節與 `DualViewModel`）；Refresh 按鈕已為 `重新連線並更新`（probe 含 Auto 降級＋refresh 單一入口 `reconnectAndRefresh()`，暫態失敗後點此即重探恢復）；Auto probe 降級已實作（su 存在但探測拒絕＋Shizuku 通過→SHIZUKU＋降級說明，雙通道皆失敗→NOOP 唯讀，見 [#25](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/25)）；標題列顯示實際執行身份＋已解析通道（`execIdentity/resolvedGateway`）；顯示更新本身 [待實機驗證]）。

詳見 [docs/USAGE.md](docs/USAGE.md) 第 1、4 節。

## 不碰 Secure Folder (Policy)

- 本專案不會存取 `/data/media/0/../knox`、Knox 容器、Secure Folder 內部資料。
- 不提供 Knox 規避、KnoxPatch 式補丁、Knox 熔斷規避。

## 免責聲明 (Disclaimer)

- 本專案不碰 Secure Folder / Knox：不讀寫、不掛載、不繞過 Knox 容器；任何 Knox 熔斷規避皆明確不在範圍內。
- 寫入設計上需 ROOT 或 Shizuku 環境，且需所選真通道探測通過（`channelReady` true）＋privilege 非 NONE（`isWritable()` fail-closed；LSPosed 模式為 NoOp 佔位、寫入一律拒絕）；指定正常 SHELL clone/remove 與手動通道歷史見 [S24 調查](docs/UX_REVIEW_S24.md) §5，指定範圍外的 binder round-trip、授權四情境、su 實機行為、端到端 pm、LSPosed 啟用偵測及當前 source 可追溯部署仍 [待實機驗證]。[S24 已驗證]僅限 App ROOT 一鍵／裸 pm／`su -c` 重裝／uninstall 回滾、已授權 SHIZUKU 一鍵（已安裝 verify 單次）；Shizuku 未授權／binder 死引導、App 內 Shizuku 首次選路、marker gateway-`cat` 顯示、`pm path` 主驗證仍 [待實機驗證]；操作系統服務本來就有風險（請先備份），後果自負。
- 僅供學習研究與個人裝置使用；請遵守當地法規與三星服務條款。

## Credits

- 詳見 [docs/CREDITS.md](docs/CREDITS.md)。
- 本專案程式碼皆為自寫（MIT），第三方僅為思路參考，已重寫不傳染授權。

## English Summary

Galaxy-Dual-app-Plus is a scaffold towards enabling Samsung Dual Messenger / Dual Apps
via a privilege router (LSPosed first, Shizuku fallback). It never touches Secure Folder (Knox).
Modules: `app` (UI wired with Clone/Remove/Reconnect-and-refresh/permission buttons + channel picker + hook marker row + StateFlow + Shizuku lifecycle; true-channel + picker + marker display wired: ROOT via `SuCmdGateway` (`su -c`), SHELL via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5), LSPosed via NoOp placeholder (writes refused, marker display-only), `ChannelPreference` (AUTO default, `selectChannel` swaps gateway + re-probes) with `NoOp` fail-closed fallback, `channelReady` true only when the selected channel's probe passes (`channelReadyFor`, tested)), `core` (privilege abstraction, tested), `hook` (LSPosed module,
API 102, observe-only placeholder with empty policy + file-marker writer).
Status: routing pure function (`routePrivilege` + `channelReadyFor`), `pm` command encapsulation, channel picker + marker display, gateway assembly JVM tests, hook pure-function tests (`WhitelistPolicy`/`WhitelistPatch`/`PolicySource`/`HookMarker`), and lint/CI gates are implemented
with unit tests; in-app one-click true channel is wired with layered S24 evidence (see Chinese status): ROOT one-click `cloned:`, authorized-SHIZUKU one-click (installed-verify single), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24; system hook dual-method deoptimize+hooked, daagent `using provider.WhiteListApps` hits (category selection only), and marker main-path scope=system_server (historical, ~5.5h before this boot, not valid for current boot) verified as historical evidence only;
hook marker gateway-`cat` display, Shizuku unauthorized/dead-guidance paths, first-time SHIZUKU selection, `pm path` as ground truth, LSPosed writes, and non-empty whitelist policy are unverified on real hardware; outside the specified normal SHELL clone/remove + manual channel history (see docs/UX_REVIEW_S24.md §5), binder round-trip, auth matrix, su behavior, end-to-end flows, LSPosed enable detection, and traceable build of current source are unverified.
Nothing marked TODO/unverified is claimed as working. See `docs/CREDITS.md` for references.
All skeleton code is original and MIT-licensed. See `docs/CREDITS.md` for references.

## License

MIT, Copyright 2026 samson910022. See [LICENSE](LICENSE).
