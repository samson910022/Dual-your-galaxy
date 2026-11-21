# Galaxy-Dual-app-Plus

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 三星 Dual 雙開工具（開發中腳手架，僅有有限情境實機證據）：設計目標為自動提權路由，
> LSPosed 通道為 NoOp 佔位＋marker 顯示（永不授權）、Shizuku 通道，絕不觸碰 Secure Folder。
> 目前路由純函數、命令封裝、真通道組裝＋手動 picker＋hook marker 顯示有單元測試；App 真通道＋picker＋marker 顯示已接線。分層實證（[S24 已驗證]僅限此句，其餘 [待實機驗證]）：App ROOT 一鍵 `cloned:`、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、裸 pm clone、uninstall 回滾、`su -c` 重裝、system hook（DualAppManagerService 單方法（getAllWhitelistedPackages）deoptimize＋hooked，isInstalledWhitelistedPackage* 未掛，見 `docs/HOOK.md` §1.4）、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取，不等於 callback 執行或包名放寬）、marker 主路徑 scope=system_server（早於本次開機約5.5h 的歷史證據，不能宣稱本次開機有效）。

> **實機證據更新（2026-11-05）**：[S24 調查與追蹤計畫](docs/UX_REVIEW_S24.md) 區分原碼、歷史實機與本輪唯讀證據，並連結 issues #21–#29。指定正常 SHELL clone/remove＋手動通道歷史見該文 §5。下文較舊 F4a 段落中的「全部未驗／啟動時靜態選路」描述不應當成目前完整現況：手動通道、偏好保存與動態重選、Auto 降級皆已實作（見自動模式節）；尚待完成的是安全負向邊界及當前 source 的可追溯裝置驗收（實機驗收，非實作）。

> **2026-11-20/11-21 更新（優先於本文 11-18 結論）**：非空政策下 daagent 冷啟動仍遭 Samsung 以 system uid 刪除 u95 分身，DELETE 早於 BBC 回調 116–144ms，toggle-ON 可恢復但資料全清；11-18「清空才刪」與「回傳位元一致＝零行為變更」不足以描述冷啟動風險。詳 [BBC_COLDSTART_DELETE_INCIDENT.md](docs/BBC_COLDSTART_DELETE_INCIDENT.md)、issue #75（issue #75 已於 2026-11-21 更正為非空回調；仍以 BBC_COLDSTART_DELETE_INCIDENT.md 為準）。下文所有 11-17/11-18 驗收段皆受此更新節制。

## 目標 (Goals)

- 〔11-20/11-21 更新優先於此 11-18 結論，見文頂註記／incident〕在三星 One UI 裝置上啟用並管理 **Dual Messenger / Dual App** 雙開（**已實機驗證 2026-11-17 ＋ unified build 重驗 2026-11-18（S23 Ultra；BBC1–BBC3；BBC4/BBC5 同 cycle 通過（2026-11-18 17:21/17:22，pid=3568）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載分身——見 BBC6_SSAID_INCIDENT.md）**：BBC 注入路徑使第三方 app 出現在 Dual Messenger「可用的應用程式」並成功雙開並排執行，見 [docs/TAB7_BBC_INJECTION_PLAN.md](docs/TAB7_BBC_INJECTION_PLAN.md)／[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) §6；unified build reader 接線重驗 2026-11-18（BBC1–BBC3；BBC4/BBC5 於同 cycle 通過（2026-11-18 17:21/17:22 安裝 + UI，pid=3568）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載分身——見 BBC6_SSAID_INCIDENT.md）。政策由 App 政策編輯器（`PolicyActivity`，入口：MainActivity「編輯應用清單」）勾選並經 VM 兩階段寫入（整組取代＋確認世代管線）；翻閘只給 BBC（`BBC_AVAILABLE_APPS_INJECTION=true`），Stage-1 白名單查詢閘封緘 `false`。
- 提權策略（真通道＋手動 picker＋hook marker 顯示已接線，[待實機驗證] DEVICE-UNVERIFIED）：**LSPosed 模式（NoOp 佔位＋file marker 顯示，`HookMarker`→`HookStateProvider`→picker 行，僅顯示、永不授權，[待實機驗證]），su ROOT（`SuCmdGateway` 經 `su -c`），Shizuku 通道（Shell，`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除）**。
- 無 ROOT 時（無論 LSPosed marker 是否存在），設計上自動降級為 Shizuku 模式執行 `pm` 指令（LSPosed marker 不進入路由；`core` 純函數 [已支援]；App 端 F4a 已接線 [待實機驗證]：Factory 在無 su 且 binder＋授權具備時選 `ShizukuCmdGateway`，探測通過才 `channelReady`）。
- **不碰 Secure Folder（Knox 容器）**：不讀寫、不掛載、不繞過 Knox。
- 模組化設計：`app`（UI）、`core`（提權抽象與命令閘道）、`hook`（LSPosed 注入）。

## 架構總覽 (Overview)

```
app  -> core (PrivilegeRouter / Executor / PmOutputParser)
hook -> XposedModule (DualHook, apiVersion 102)
```

- `core`：定義 `Privilege`（NONE/SHELL/ROOT）、`CmdGateway` 介面、`PmOutputParser` 解析器。
- `app`：UI 層（`MainActivity` 已有 Clone／Remove／重新連線並更新／請求 Shizuku 授權按鈕＋四通道選擇按鈕＋Hook marker 行＋`StateFlow` 收集＋Shizuku binder／授權生命週期接線；已接入真 gateway＋picker＋marker 顯示［待實機驗證］DEVICE-UNVERIFIED：ROOT→`SuCmdGateway`（`su -c` argv 單參直傳）、SHELL→`ShizukuCmdGateway`（UserService `GalaxyExecService`，`newProcess` 在 13.1.5 已移除故不用）、LSPosed→NoOp 佔位（寫入拒絕，marker 僅顯示）；`ChannelPreference`（AUTO 預設，persist）經 `assembleChannel` 在啟動／`selectChannel` 解析，選不到回 `NoOpGateway` fail-closed；`SuElevator`／`ShizukuElevator` 探測真通道，`channelReady` 僅所選通道探測通過才 `true`（`channelReadyFor` 純函數 [已支援]：hook 訊號永不授權）），目標是透過 `core` 選擇最佳可用提權通道。
- `hook`：LSPosed 模組，hook 三星雙開白名單的兩個層面：系統服務（system_server，`getAllWhitelistedPackages`）與 DAAgent BBC 庫（`PmWrapper#getPossibleDualAppPackages`，決定 Dual Messenger 頁「可用的應用程式」清單）。
  第三方 app 由 App 政策編輯器勾選並經 **VM 兩階段寫入**（整組取代，非寫入 per toggle）寫入 LSPosed remote preferences `dual_policy/extra_whitelist_packages`（owner user 才可寫、`.GalaxyApp` 單一 Application；hook 側經 `EffectivePolicyCache` 讀取——**60s TTL 輪詢、5s 失敗節流、single-flight、50ms 預算**；API 102 無跨行程推送，新鮮度僅靠 TTL＋重啟語義）。
  〔11-20/11-21 更新優先，見文頂註記〕BBC 注入 union-append 合併入 Samsung 清單（只增不減、PROTECTIVE、fail-closed／identity fast-path、installed∧enabled 謂詞；空政策＝BBC callback 回傳物件與 Samsung 原物件位元一致——僅指回傳物件，不代表裝置行為不變；冷啟動刪除見 `BBC_COLDSTART_DELETE_INCIDENT.md`），效應由 `BBC_AVAILABLE_APPS_INJECTION=true`（`PolicySource.kt`，2026-11-17 pre-unification 裝置證據＋使用者授權）治理；Stage-1 白名單查詢閘 `NON_EMPTY_POLICY_EFFECTIVE_GATE` **封緘 `false`**（system_server／daagent 查詢／`<clinit>` 零行為變更）。詳見 [docs/HOOK.md](docs/HOOK.md) 第 1.3a／1.4 節；裝置證據與 provenance 見 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) §6（2026-11-17 為 pre-unification build 實測；unified build 重驗 2026-11-18，BBC1–BBC3 重驗，BBC4/BBC5 於同 cycle 通過（2026-11-18 17:21/17:22 安裝 + UI，pid=3568）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md），BBC6 毀滅分身——見 BBC6_SSAID_INCIDENT.md）。

詳見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 實作狀態 (Status, Phase F1 誠實標示)

標示體系（與 [docs/USAGE.md](docs/USAGE.md) 一致）：**[已支援]** = 有程式碼＋單元測試／CI 閘門；**[已接線]** = 有程式碼並已接通，但端到端未實機驗證／DEVICE-UNVERIFIED；**[待實作]** = 程式碼未接通；**[待實機驗證]** = 程式碼存在但無三星實機證據，一律不宣稱可用。

- [已支援] profile 基本防護（`resolveDualUserId`：排除 user0、拒絕無／多名稱候選，見 `DualSafetyTest`）；完整平台身分與存在但非Dual的explicit-ID邊界仍待 [#21](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/21) 修正，名稱命中不等於安全身分證明。
- [已支援] `pm` 命令封裝（`DualRepository.cloneVerified / removeVerified / refresh`：單快照釘選＋package listing 二次確認，見 `CloneVerifyRefreshTest`）；錯誤／截斷輸出的可信度由 TrustedList 三態＋白名單＋截斷契約判定（P22 已合併，見 `ParseTrustedTest`／`TruncatedIdentityTest`）。
- [已支援] lint／CI 閘門（`./gradlew` 固定 8.7、fail-fast quality gates、DEX 檢查）。
- [部分已驗證] App 內一鍵 clone 真通道（F4a 已接線＋手動 picker：`MainActivity` 四通道按鈕＋Hook marker 行＋`StateFlow`，`DualViewModel.selectChannel/clone/refresh`；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），選不到回 `NoOp`＋唯讀 fail-closed，`channelReady` gating（`channelReadyFor` [已支援]：hook 訊號永不授權，NONE 永不就緒）。[S24 已驗證]僅限：ROOT 一鍵 `cloned: com.openai.chatgpt`＋`Cloned (35)`、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、`pm uninstall --user 95` 回滾＋`su -c install-existing` 重裝；其餘 Shizuku 未授權路徑／首次選路／binder 死引導、`pm path` 主驗證、marker gateway-`cat` 顯示行仍 [待實機驗證]）。
- [部分已驗證] 〔11-20/11-21 更新優先，見文頂註記〕BBC 注入（`PmWrapper#getPossibleDualAppPackages`）已實機驗證（2026-11-17，pre-unification build；S23 Ultra One UI 9 端對端雙開 ChatGPT、Tab S7 One UI 5 Firefox 雙開；`BBC_AVAILABLE_APPS_INJECTION=true` 效應面僅限 BBC 清單）；白名單 Stage-1 查詢面（`NON_EMPTY_POLICY_EFFECTIVE_GATE`）**sealed `false`**，預設 observe-only、零行為變更，目前唯一開啟的效應面僅限 BBC 注入清單；政策編輯器（`PolicyActivity`）經 VM 兩階段寫入（整組取代＋確認世代管線）。2026-11-17 裝置證據為 pre-unification build（政策 group `galaxydual_policy`）所記錄；unified build 共享 `dual_policy/extra_whitelist_packages`→`EffectivePolicyCache` 讀者接線，**已重驗（2026-11-18，S23 Ultra，BBC1/BBC2/BBC3 重驗 OK；BBC4/BBC5 於同 cycle 通過（2026-11-18 17:21/17:22 安裝 + UI，pid=3568）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載；BBC6 毀滅分身）**；JVM 測試覆蓋；LSPosed picker 之寫入通道仍為 NoOp 佔位，寫入一律拒絕。
- [部分已驗證] 雙開實機與 LSPosed 載入（寫後以 package listing 二次確認，不單靠 `pm path`，錯誤／截斷由 TrustedList 三態＋白名單判定（P22 已合併）；DUAL 名稱／固定 user 95 不能單獨授權寫入目標，CLONE 僅認全 token＋殘留驗證＋合法第二 token slash 豁免（`(default)` 為唯一容忍後綴），詳見 docs/USAGE.md §3，待 [#21](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/21)；指定正常 SHELL clone/remove＋手動通道歷史見 [S24 調查](docs/UX_REVIEW_S24.md) §5）。[S24 已驗證]僅限：裸 pm `install-existing --user 95`＋list 二次確認（shell 通道兩次獨立成功）、system hook（DualAppManagerService 單方法（getAllWhitelistedPackages）deoptimize＋hooked，isInstalledWhitelistedPackage* 未掛，見 `docs/HOOK.md` §1.4）、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取）、marker 主路徑 scope=system_server（早於本次開機約5.5h，不能宣稱本次開機有效）；其餘 LSPosed 寫入（NoOp 佔位）、`pm path` 主驗證仍 [待實機驗證]。

凡標 [已接線]／[待實作]／[待實機驗證] 者，皆不可描述為「可用功能」；下文路由／自動模式描述的是設計意圖與 `core` 純函數行為，不是已接通的 App 流程。

## 需求 (Requirements)

- Android 10+，建議三星 One UI 4.0+，`compileSdk 34`。
- Kotlin 2.x，AGP 8.x，JDK 17。
- 可選：LSPosed（NoOp 佔位＋marker 顯示，永不授權）、Shizuku v13+（通道；低於 v13 無 Context 構造 → -1 → EXEC 一律拒絕，本 App 綁定前明確拒絕）。

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

路由純函數（`routePrivilegeFromProbes`＋`channelReadyFor`，[已支援]，單元測試覆蓋）依序判斷；App 端探測＋選擇框架（`probeChannels`／`recheckPrivilege`／`selectChannel`＋手動 `ChannelPreference` picker＋Shizuku 生命週期）已接真 gateway（`SuCmdGateway` 經 `su -c`、`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；hook 訊號為 file marker 存在性證據，僅顯示、永不進入路由，[待實機驗證]；`selectChannel` 動態換 gateway；選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`，hook 永不授權，NONE 永不就緒）。以下順序為純函數 `routePrivilegeFromProbes(hasShizuku, hasRoot)` 行為（App 端固定 hook-neutral），App 端 Shizuku 切換（未授權路徑／首次選路／marker gateway-`cat` 顯示 [待實機驗證]；已授權一鍵、ROOT 一鍵／su／uninstall 已驗，見「實作狀態」）：

1. 偵測 su 是否可用 → `Privilege.ROOT`（LSPosed marker 不進入路由，[待實機驗證]）。
2. 否則偵測 Shizuku 是否可用 → `Privilege.SHELL`。
3. 否則 → `Privilege.NONE`，僅顯示唯讀狀態，不執行寫入。

## 自動模式 (Auto Mode) — 真通道已接線（ROOT `cloned:`／已授權 SHIZUKU 單次 [S24 已驗證]，指定歷史情境外 [待實機驗證]）

已接線（picker＋真通道＋marker 顯示）：AUTO 按 su→Shizuku→NoOp 解析；ROOT→`SuCmdGateway`（`su -c`）、SHELL→`ShizukuCmdGateway`（UserService，`newProcess` 在 13.1.5 已移除）、LSPosed→NoOp 佔位（寫入拒絕，marker 僅顯示）；`channelReady` 為各通道探測通過才 `true`（未通過／選不到時仍 `false` 拒寫，按鈕寫入被 `isWritable()` 攔下）。App 內 ROOT 一鍵／SHIZUKU 已授權一鍵／裸 pm／su／uninstall 已驗（見「實作狀態」）；Shizuku 未授權路徑／首次選路、`pm path` 主驗證、marker gateway-`cat` 顯示仍未驗：

- 若手動選 LSPosed 模式（模組已勾選並啟用，per-scope file marker 存在性證據，僅顯示、永不進入路由；與 AUTO 路由無關；marker 訊號為 `HookStateProvider` display-only，永不授權）→ 顯示 marker 行（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，[待實機驗證]）；執行通道為 NoOp 佔位、寫入一律拒絕（[待實機驗證]：LSPosed 寫入、marker gateway-`cat` 顯示未驗；BBC 注入（非寫入通道）已於 2026-11-17 雙裝置實機驗證，Samsung 設定頁出現第三方 app 並可雙開，見 [docs/HOOK.md](docs/HOOK.md) §1.3a／§1.4 與 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) §6；寫入通道仍為 NoOp 佔位，寫入一律拒絕）。[S24 已驗證]僅限：system hook 單方法（getAllWhitelistedPackages）deoptimize＋hooked，isInstalledWhitelistedPackage* 未掛（見 `docs/HOOK.md` §1.4）、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取）、marker 主路徑 scope=system_server（早於本次開機約5.5h，不能宣稱本次開機有效）；`hasLsposed` 參數已移除，hook 訊號永不進入路由（F4b／後續 [待實機驗證]）。
- Shizuku 可用（與 LSPosed 模組／marker 是否存在無關）→ 走 Shizuku 通道（`Privilege.SHELL`），執行 `pm install-existing / uninstall --user / path --user`（Shizuku binder／授權生命週期已接＋真 gateway 已接；Factory 在無 su 且 binder alive＋已授權時選擇，`ShizukuElevator` 探測通過才 `channelReady`）。[S24 已驗證]僅限已授權 binder 下 App 內切 SHIZUKU→就緒→CLONE（已安裝 verify 單次）；未授權路徑／首次選路／binder 死引導、binder round-trip、授權四情境 [待實機驗證]。
- 兩者皆無 → `Privilege.NONE` 唯讀模式（[已支援]：路由與 `NoOpGateway` 拒寫＋`channelReady` false fail-closed；所選真通道探測通過即為 `true`；`selectChannel()` 可在 mutex 內重建 gateway 並重探，`recheckPrivilege()` 在 AUTO 環境變化時重選（見 [docs/USAGE.md](docs/USAGE.md) 第 1 節與 `DualViewModel`）；重新連線並更新按鈕已為 `重新連線並更新`（probe 含 Auto 降級＋refresh 單一入口 `reconnectAndRefresh()`，暫態失敗後點此即重探恢復）；Auto probe 降級已實作（su 存在但探測拒絕＋Shizuku 通過→SHIZUKU＋降級說明，雙通道皆失敗→NOOP 唯讀，見 [#25](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/25)）；標題列顯示實際執行身份＋已解析通道（`execIdentity/resolvedGateway`）；顯示更新本身 [待實機驗證]）。
- 錯誤優先（error-first）：refresh 與 Auto 降級疊加時錯誤保持優先——`CHANNEL`／`FAILED`／`NO_DUAL`／`VERIFY`／`INVALID` 保持原 kind（不降級為 `INFO`），以降級原因附加（`；` join，恢復入口 `重新連線並更新` 隨附）；探測通過 ≠ PM可讀（`anyProbePassed` **NOT** PM可讀證明，真可讀 ＝ `pmReadable == true`＋`writeReady`，PM不可讀 fail-closed 拒寫 `CHANNEL`）。文案術語：`禁用` ＝ 未就緒 fail-closed，`拒絕` ＝ LSPosed 政策佔位。

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
via a privilege router (su ROOT primary, Shizuku fallback, LSPosed display-only marker/NoOp placeholder (manual only)). It never touches Secure Folder (Knox).
Modules: `app` (UI wired with Clone/Remove/Reconnect-and-refresh/permission buttons + channel picker + hook marker row + StateFlow + Shizuku lifecycle; true-channel + picker + marker display wired: ROOT via `SuCmdGateway` (`su -c`), SHELL via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5), LSPosed via NoOp placeholder (writes refused, marker display-only), `ChannelPreference` (AUTO default, `selectChannel` swaps gateway + re-probes) with `NoOp` fail-closed fallback, `channelReady` true only when the selected channel's probe passes (`channelReadyFor`, tested)), `core` (privilege abstraction, tested), `hook` (LSPosed module,
API 102, BBC-layer injector: hooks `PmWrapper#getPossibleDualAppPackages` (Samsung Dual Messenger
"available apps" list source) and union-appends the user-written policy draft read from LSPosed remote
preferences `dual_policy/extra_whitelist_packages` (whole-set replace, owner-user write via the in-page
two-phase confirmation; hook side reads via `EffectivePolicyCache` — 60s TTL poll, 5s failure cooldown,
single-flight, 50ms budget; no cross-process listener push on API 102, freshness = TTL poll + reboot
semantics only); PROTECTIVE, union-only, identity fast-path, fail-closed.
Effect gate: `BBC_AVAILABLE_APPS_INJECTION=true` (PolicySource.kt, ships true — 2026-11-17 pre-unification device evidence
+ user authorization; governs only the BBC merge; set back to `false` for bit-exact observe-only BBC — "bit-exact" refers only to the callback return object vs Samsung's original object, not to unchanged device behavior; see the 2026-11-20/11-21 cold-start deletion note above and `docs/BBC_COLDSTART_DELETE_INCIDENT.md`).
The Stage-1 whitelist gate `NON_EMPTY_POLICY_EFFECTIVE_GATE` is sealed `false` forever until a future
spec on Samsung stages; whitelist queries / system_server / <clinit> behavior unchanged).
Status: routing pure function (`routePrivilegeFromProbes` + `channelReadyFor`), `pm` command encapsulation, channel picker + marker display, gateway assembly JVM tests, hook pure-function tests (`WhitelistPolicy`/`WhitelistPatch`/`PolicySource`/`HookMarker`/`BbcInjector`), and lint/CI gates are implemented
with unit tests; in-app one-click true channel is wired with layered S24 evidence (see Chinese status): ROOT one-click `cloned:`, authorized-SHIZUKU one-click (installed-verify single), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24; BBC injector verified end-to-end on 2026-11-17 on two devices (pre-unification build — policy group `galaxydual_policy`; the unified tree reads `dual_policy/extra_whitelist_packages` via the TTL-polled cache, unified-build re-verification DONE on S23 Ultra 2026-11-18, BBC1–BBC3 re-verified; BBC4/BBC5 同 cycle 通過（2026-11-18 17:21/17:22，pid=3568）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）; BBC6 destroyed the dual app (see BBC6_SSAID_INCIDENT.md)):

> **2026-11-20/11-21 update (supersedes this document's 11-18 conclusion)**: even under a non-empty policy, a daagent cold start was followed by Samsung deleting the u95 clone as system uid, with DELETE preceding the BBC callback by 116–144 ms; toggle-ON restores it but all data is wiped. The 11-18 "only empty policy deletes" / "bit-identical return = zero behavior change" claim does not describe the cold-start risk. See `docs/BBC_COLDSTART_DELETE_INCIDENT.md`, issue #75 (issue #75 still mislabels the 11-20 callback as 'empty'; manual correction pending; `BBC_COLDSTART_DELETE_INCIDENT.md` is authoritative). All 11-17/11-18 verification paragraphs above are qualified by this note.

Samsung's Dual Messenger page on Galaxy S23 Ultra (One UI 9 / Android 17, DAAgent 3.7.00.0) gained `com.openai.chatgpt` after in-app policy tick (`BBC-HOOK merged +1 packages (total 7, pid=21472)`, see docs/ACCEPTANCE.md §6); toggling installed the dual instance in the dual profile (user 95) and it launched (u95_a431) alongside the owner-profile instance; on Galaxy Tab S7 (One UI 5 / Android 13) the same page shows Firefox with a working toggle (screenshot 10_dualmessenger_page.png); dual install was observed in session but no persisted process evidence exists (see docs/HOOK.md §1.3a), with the merge correctly a no-op for Samsung-runtime-whitelisted packages (`BBC-HOOK merge added 0 packages`); empty-policy path returns Samsung's original object unchanged (bit-identical **only for the callback return object**, not evidence of unchanged device behavior — cold-start deletion see `docs/BBC_COLDSTART_DELETE_INCIDENT.md`), and the fail-closed gate seam stays unit-tested;
hook marker gateway-`cat` display, Shizuku unauthorized/dead-guidance paths, first-time SHIZUKU selection, `pm path` as ground truth, and LSPosed writes (NoOp placeholder) remain unverified on real hardware; the Stage-1 whitelist effect surface stays sealed (`NON_EMPTY_POLICY_EFFECTIVE_GATE=false`, zero behavior change by design); outside the specified normal SHELL clone/remove + manual channel history (see docs/UX_REVIEW_S24.md §5), binder round-trip, auth matrix, su behavior, end-to-end flows, LSPosed enable detection, and traceable build of current source are unverified.
Nothing marked TODO/unverified is claimed as working. See `docs/CREDITS.md` for references.
All skeleton code is original and MIT-licensed. See `docs/CREDITS.md` for references.

## License

MIT, Copyright 2026 samson910022. See [LICENSE](LICENSE).
