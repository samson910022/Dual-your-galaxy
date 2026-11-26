<!-- MIT License - Original docs by Dual your Galaxy contributors. No GPL content. -->
# 使用教學 (USAGE)

> 本專案僅經由 GitHub 發佈 sideload APK，不上架 Play 商店。
> PR-7 P2 (#26) 權限審計已完成：`REQUEST_INSTALL_PACKAGES` 與 `MANAGE_EXTERNAL_STORAGE`
> 無呼叫鏈（clone 走 shell `pm install-existing`，不走 PackageInstaller 會話；無共享儲存
> 暫存）故已移除；僅保留功能必需的 `QUERY_ALL_PACKAGES`（個人來源 catalog 經
> PackageManager 列舉，見 `SourceInventory.loadSourceApps`）。不上 Play 不是保留過寬權限的充分理由。
> 全程不碰 Secure Folder（Knox 容器）。
> 本文件描述包含設計目標與未完成流程；凡 [待實作]／[待實機驗證]／[已接線] 皆不宣稱為可用功能（[已接線]＝有程式碼並已接通，但端到端未實機驗證），
> 驗證須依個別情境判讀，不能由正常成功案例推論所有通道與安全負向邊界。歷史 S24 寫入紀錄與本輪唯讀調查見下方更新。

## 安裝（v0.1.0-beta.3）

> **安裝／升級警告（v0.1.0-beta.3）— 請先讀完本警告再點 Release 連結：**
> **先確認目前安裝的 id**：若是舊 id `com.galaxydual.plus` → 全新安裝、不遷移舊資料、不可與新 id 共存、先備份並確認可還原；若已是 `io.github.samson910022.galaxydualplus`（含 beta.2 → beta.3）→ **同 id、同簽章 in-place 升級，不需卸載**，Android 通常會就地保留該管理 App 的資料，但**不保證 Samsung 分身或其資料完好**。
> Samsung 可能自動刪除分身並永久清除資料；一般既有雙開 App 不需為此套件 id 遷移而**手動移除**，且這**不表示**分身「不受影響」——本 App 沒有刪除防護。
> release applicationId `io.github.samson910022.galaxydualplus`，自建 debug APK 為 `io.github.samson910022.galaxydualplus.debug`（是**不同套件**；舊 id `com.galaxydual.plus` 的資料**不會遷移**，偏好／政策草稿／風險確認也不會自動搬移），其 applicationId 與 `module.prop` 的 module id `io.github.samson910022.galaxydualplus` 不一致（已知限制、不保證被 LSPosed 正確載入）。**無內建匯入／匯出**，請先備份並**確認備份可還原**。**新舊管理 App／LSPosed 模組不可同時啟用**（一般既有雙開 App 不需為此遷移**手動移除**，資料也不會合併）；不同 applicationId／module id／remote-prefs authority 會被系統視為不同個體。停用／卸載／opt-out／重開機**都不是**刪除防護；beta.3 的 hook **可能早於首次 App UI 警告生效**。BBC 清單注入仍為 experimental／本版本未實機驗證（正向證據僅 11-17／11-18 舊 build），LSPosed 寫入通道仍為 NoOp 佔位。**截至 v0.1.0-beta.3（`fc579de3`）尚無 pre-activation opt-in；PR #90 合併後以 `docs/HOOK.md` §1.4／ADR 為準。**
> **已發布的 Release page 目前仍有舊身分／A/B/C 文字；maintainer 同步更新前，以本警告與 repo 內 `docs/RELEASE_NOTES_beta3.md` 為準。**
>
> `AndroidManifest.xml` 的 `package="com.galaxydual.plus"` 為歷史殘留欄位，與目前 applicationId 不同（AGP 以 `namespace`／`applicationId` 為準）；此差異不影響上列「相對於舊 id 為全新安裝、不遷移」結論，本文不改程式。
>
> 一般安裝請用 [GitHub Release](https://github.com/samson910022/Dual-your-galaxy/releases/tag/v0.1.0-beta.3) 的已簽章 `app-release.apk`（一般雙開用戶亦裝同一個 signed APK 到 user 95，見 `docs/HOOK.md` §2.1）；自建 debug APK 的 id 與 `module.prop` 的 module id 不一致，屬已知限制。安裝／更新完成後，以 repo 內 `docs/RELEASE_NOTES_beta3.md`（本 PR 修正版）為準；GitHub Release page 由 maintainer 同步（[v0.1.0-beta.3 release notes](https://github.com/samson910022/Dual-your-galaxy/releases/tag/v0.1.0-beta.3)）。

> **2026-11-05 更新**：[S24 調查與追蹤計畫](UX_REVIEW_S24.md) 彙整既有 #6/#9 的正常 SHELL clone/remove、手動通道證據，以及新發現與 issues #21–#29。本文較舊 F4a 的「無實機／Factory 靜態選路」敘述屬歷史階段，不代表手動重選仍未實作；未完成項目仍不得宣稱可用。

> **2026-11-20/11-21 更新（優先於本文 11-18 結論）**：非空政策下 daagent 冷啟動仍遭 Samsung 以 system uid 刪除 u95 分身，DELETE 早於 BBC 回調 116–144ms，toggle-ON 可恢復但資料全清；11-18「清空才刪」與「回傳位元一致＝零行為變更」不足以描述冷啟動風險。詳 `BBC_COLDSTART_DELETE_INCIDENT.md`、issue #75（issue #75 已於 2026-11-21 更正為非空回調；仍以 BBC_COLDSTART_DELETE_INCIDENT.md 為準）。本節與下文所有 11-17/11-18 驗收段皆受此更新節制。

> **round-7b 範圍收斂（2026-11-23）**：合併門檻與風險揭露請見 [README](../README.md) 與 [docs/ACCEPTANCE.md](ACCEPTANCE.md) 的 round-7b 章節（優先於本文 round-2～6 用語）；**BBC 效應於本版本（beta.3）為 experimental／未實機驗證**，11-17/11-18 builds 的正向證據不代表目前版本。

## 0. 狀態標示（與 README 一致）

- [已支援] profile 基本防護（排除 user0、拒絕候選歧義；**#21 已修；#27 診斷分離／清單新鮮度；#65 parser `name` 欄位誤判（合法使用者名稱被當錯誤訊息）；#66 寫後 listing 核對目標身分**）、`pm` 命令封裝
 （`DualRepository.cloneVerified / removeVerified`＋二次確認）、lint／CI 閘門。
- [部分已驗證] App 內一鍵 clone 真通道＋手動 picker（F4a 已接線＋picker＋marker：`MainActivity` 四通道按鈕＋Hook marker 行＋`StateFlow`，`DualViewModel.selectChannel/clone/refresh`；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），選不到回 `NoOp`＋唯讀 fail-closed，`channelReady` gating（`channelReadyFor` [已支援]：hook 訊號永不授權，NONE 永不就緒）。[S24 已驗證]僅限 ROOT 一鍵 `cloned:`、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、`pm uninstall --user 95` 回滾＋`su -c` 重裝；寫後主驗證以 listing 為準（`pm path` 僅輔助診斷，見 #22）；Shizuku 未授權／binder 死引導、App 內 Shizuku 首次選路、marker gateway-`cat` 顯示行、callback 可驗日誌（BBC 路徑僅 11-17／11-18 舊 build 有證據；本版本（beta.3）experimental／未實機驗證，見下行與 `docs/HOOK.md` §1.3a／§1.4）、白名單 Stage-1 查詢面（sealed false，[待實機驗證]，設計上零行為變更）、當前 source 可追溯部署仍 [待實機驗證]；指定正常 SHELL clone/remove＋手動通道歷史見 [S24 調查](UX_REVIEW_S24.md) §5，指定範圍外的 binder round-trip、授權四情境、su 實機行為、端到端 pm、LSPosed 啟用偵測及當前 source 可追溯部署仍待驗）。
- [部分已驗證] BBC 注入路徑（**僅 11-17 pre-unification／11-18 unified 舊 build 證據；本版本（beta.3）experimental／未實機驗證**；2026-11-17 雙裝置清單面／注入面觀察（**pre-unification build**：S23 Ultra One UI 9、Tab S7 One UI 5），**end-to-end dual launch 僅 S23 歷史觀察，Tab S7 無 persisted u95 process**；Samsung Dual Messenger 頁出現目標第三方 app、可切換；dual 安裝與可啟動僅在 pre-unification 狀態被觀察到，unified build 的讀者僅部分重驗（BBC1–BBC3），但 **unified build 下 BBC4／BBC5 皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 destructive fail；SSAID 拒絕 post-BBC6; reboot+toggle 恢復 (見 BBC6_SSAID_INCIDENT.md)**；unified build 共享 `dual_policy/extra_whitelist_packages`→`EffectivePolicyCache`（`BBC_AVAILABLE_APPS_INJECTION=true` 僅治理 BBC 合併，Stage-1 unified tree 自 11-18 起 `NON_EMPTY_POLICY_EFFECTIVE_GATE=false` 封緘不變）。**unified build 部分重驗（BBC1–BBC3）2026-11-18：BBC1–BBC3 正向紀錄，BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）、BBC6=系統自動卸載分身（11-18 其後曾觀察到政策清空，但因果已撤回；11-20/21 非空政策亦遭刪除，見 BBC_COLDSTART_DELETE_INCIDENT.md §8）**。JVM 測試覆蓋（`BbcInjectorTest` 等）；白名單 Stage-1 查詢面仍為 observe-only（`effective(...)` fail-closed 回空）。
- [部分已驗證] LSPosed 載入（system hook 單方法（getAllWhitelistedPackages）deoptimize＋hooked [S24 已驗證]、isInstalledWhitelistedPackage* 未掛（見 HOOK §1.4）、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取） [S24 已驗證]、marker 主路徑 scope=system_server（早於本次開機約5.5h，不能宣稱本次開機有效） [S24 已驗證]，其餘 LSPosed 寫入（NoOp 佔位）、白名單 Stage-1 查詢面 [待實機驗證]；BBC 注入面僅 11-17／11-18 舊 build 有證據（本版本（beta.3）experimental／未實機驗證），見上行）、Shizuku（已授權一鍵已驗；未授權路徑／首次選路／binder 死引導待驗；含重啟後重啟動）、
  雙開實機（`install-existing / path / uninstall --user` 端到端；其中
  `install-existing --user 95`＋`pm list packages --user 95` 二次確認
  [S24 已驗證]：裸 pm shell 通道兩次獨立成功）。

Hook 誠實聲明（`docs/HOOK.md` 第 1.1／1.3a／1.4 節，本文引用不再重述為已生效）：
BBC 注入路徑（unified build：`EffectivePolicyCache` 輪詢讀 `dual_policy/extra_whitelist_packages`（整組取代、owner-user 寫入）；pre-unification evidence：`galaxydual_policy/packages`）
在 2026-11-17 經 pre-unification build 實機觀察（S23 Ultra One UI 9 端對端雙開 ChatGPT；Tab S7 驗注入 no-op 與位元一致回退；`BBC_AVAILABLE_APPS_INJECTION=true` 僅治理 BBC 清單 union-append）；
Stage-1 白名單查詢閘 `NON_EMPTY_POLICY_EFFECTIVE_GATE=false` 封緘常閉，白名單查詢／`<clinit>`／system_server 面預設 observe-only、零行為變更；
政策空時的 hook 回傳物件與 Samsung 原始物件位元一致（僅指回傳物件，不代表裝置行為不變；冷啟動刪除見 `BBC_COLDSTART_DELETE_INCIDENT.md`）；本文凡提及其他「hook 通道／白名單放寬」（例如 mutation 面、Stage-1 白名單查詢）
仍指設計意圖，不是現有效果。unified build 讀者接線於 2026-11-18 部分重驗（BBC1–BBC3）（S23 Ultra；見 `docs/ACCEPTANCE.md` §6.0；BBC4/5 UNVERIFIED、BBC6 毀滅分身，不覆蓋）。

## 1. 通道選擇與優先順序（`core` 純函數 [已支援]＋真通道已接線（ROOT／SHIZUKU 已授權一鍵已抽驗 [S24 已驗證]，其餘 [待實機驗證]）；自動路由順序為 su ROOT → Shizuku → NONE，下列為手動 picker 表面）

路由純函數（[已支援]）依序判斷；開機／開 App 探測框架（`probeChannels`／`recheckPrivilege`／`selectChannel`＋Shizuku 生命週期）已接真 gateway（ROOT→`SuCmdGateway` 經 `su -c`；SHELL→`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；hook 訊號為 file marker 存在性證據，僅顯示、永不進入路由，[待實機驗證]；`ChannelPreference` 手動選擇 AUTO 預設，`selectChannel` 動態換 gateway；選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`，`channelReadyFor` [已支援]），
以下順序為接線後預期行為，App 內 Shizuku 切換（未授權路徑／首次選路／marker gateway-`cat` 顯示 [待實機驗證]；已授權一鍵、ROOT 一鍵／su／uninstall 已驗，見 §0）：

1. **LSPosed 模式（NoOp 佔位）**：App 內 picker 選 LSPosed → 顯示 file marker 狀態（per-scope marker；`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，[待實機驗證]），執行通道為 NoOp 佔位、寫入一律拒絕；
   hook Stage-1 白名單面預設 observe-only（見上文引用；BBC 注入除外——僅 11-17 S23 歷史觀察；11-18 僅 BBC1–3，BBC4/5 UNVERIFIED；本版本 experimental，見上文 Hook 誠實聲明與 `docs/ACCEPTANCE.md` §6.0；App 內 ROOT 一鍵 [S24 已驗證]，marker gateway-`cat` 顯示未驗）。
2. **AUTO 降級（SHELL）**：無可用 su ROOT（不論 LSPosed 模組／marker 是否存在），且 Shizuku 授權及能力探測通過 → `Privilege.SHELL`；明選 LSPosed 仍為 NoOp 佔位（hook 訊號不進入路由）
   - 走 `pm` 指令通道（`install-existing` / `uninstall --user` / `path --user`），F4a 的 `ShizukuCmdGateway` 經 UserService `GalaxyExecService` 已接線（`newProcess` 在 13.1.5 已移除故不用；Factory 在無 su 且 binder alive＋已授權時選擇，`ShizukuElevator` 探測通過才 `channelReady`）；
     binder round-trip、未授權路徑／首次選路／binder 死引導 [待實機驗證] DEVICE-UNVERIFIED。
   - 版本要求：需 Shizuku v13+（v13 起才有 UserService 的 Context 構造）；低於 v13 時本 App 在綁定前明確拒絕（`unsupported Shizuku version`），不綁定、不執行。
3. **NONE（唯讀）**：選不到通道 → 不執行任何寫入，只顯示狀態（[已支援]：`NoOpGateway` 拒寫＋`channelReady` false fail-closed；不再恆 `false`——所選真通道探測通過即為 `true`）。

```kotlin
// Pure routing logic, no side effects (see core/Privilege.kt).
// App 端現狀（H4／#19）：hook 訊號永不進入路由（`hasLsposed` 參數已移除：
// file marker 是存在性證據，不是 live hook 證明，不得作為授權輸入）；
// hasRoot/hasShizuku 由 Su/ShizukuElevator 真通道探測填入。Hook 狀態走
// UiState.hookActive／hookScopes（display-only）供 LSPosed picker 行顯示。
val privilege = routePrivilegeFromProbes(hasShizuku = shizukuAlive, hasRoot = hasRoot)
val ready = channelReadyFor(gatewayPrivilege, hasRoot, shizukuAlive)
```

Clone／Remove／重新連線並更新／請求 Shizuku 授權按鈕＋四通道選擇按鈕與 `StateFlow` 收集＋Shizuku binder／授權生命週期已接；探測跑在與所選 gateway 同實例的真通道 elevator 上，`channelReady` 僅所選通道探測通過才 `true`（NoOp 回退則 `false` fail-closed）。
目前 `selectChannel()` 已可在 mutex 內重建 gateway、探測與刷新，`recheckPrivilege()` 也會在 AUTO 環境變化時重新選擇；不是只有啟動時的靜態 Factory。重新連線並更新按鈕（單一入口 `reconnectAndRefresh()`：probe 含 Auto 降級＋refresh，暫態失敗後點此即重探恢復）。Auto probe 降級已實作（su 存在但探測拒絕＋Shizuku 通過→SHIZUKU＋降級說明，雙通道皆失敗→NOOP 唯讀，見 #25）；LSPosed 仍為拒寫的 NoOp 佔位。顯示更新本身 [待實機驗證]。

- 併發邊界（#24；術語：busy 即 `UiState.loading`，merge 即 coalesce）：`重新連線並更新`（`reconnectAndRefresh()`：probe 含 Auto 降級＋refresh，單一入口）快速連點合併為一次 trailing 重跑（coalesce，in-flight 之後最多一次待跑，不堆積 mutex 等待者；純 `refresh()` 仍為探測-free 的舊路徑，僅供 coalesced trailing 內部使用）；`clone`／`request-remove`／`confirm` 按操作 key 去重，同 key 第二次報 `already in progress` 不排隊，三命名空間全稱 `clone:<pkg>`／`request-remove:<pkg>`／`remove:<pkg>@<id>#<serial>#<ts>`（世代 key，serial 綁定：同包不同世代不互擋，同世代重複才去重；clone 與 request／confirm 互不干擾）；寫回覆 `timedOut‖transportUnknown‖isPostSpawnTransportText‖isServiceTimeoutText` 一律 verify-first（單次有界唯讀 `verifyListProbe` 定奪，不重發寫入；同向成功才認 Success，反向觀測一律 Unknown；before／after pin 漂移→`VerifyUnknown`（identity-changed），不計成功）；執行期間 busy（`UiState.loading=true`，7 鈕＋dialog 按鈕禁用，cancel／pending 保留語義見 ARCH）；所有 repo 呼叫經 `Dispatchers.IO`（`onIo`），settle wait 預設可取消 `delay()`（經 `delayFn`），cancel 在等待間傳播。
- 錯誤優先（error-first，`combineWithDegradeNote`，見 `DualViewModel.refreshLocked`）：refresh 結果與 Auto 降級說明疊加時，錯誤保持優先——`CHANNEL`／`FAILED`／`NO_DUAL`／`VERIFY`／`INVALID` 保持原 kind 不降級為 `INFO`，以降級原因附加（`；` join，恢復入口 `重新連線並更新` 隨附後）；`NONE` 顯示降級說明 alone（`INFO`），`INFO` 中性結果附加說明（`INFO`）。狀態列因此同時攜帶刷新失敗與雙通道恢復指引。
- 文案術語（`notReadyMsg`／`isWritable`）：`禁用` ＝ 未就緒 fail-closed（通用 NoOp 回退 `通道未就緒…寫入已禁用`＋明選失敗 `…寫入已禁用（明選不跨通道）`，寫入一律拒絕）；`拒絕` ＝ LSPosed 政策佔位（`LSPosed 通道為佔位…寫入已拒絕`；白名單政策來源已接線，白名單 Stage-1 查詢面 sealed false／observe-only；BBC 注入面僅 11-17 S23 歷史觀察（pre-unification build）；11-18 unified 僅 BBC1–3 reader 接線部分重驗，BBC4/5 UNVERIFIED；本版本 experimental，見 `docs/HOOK.md` §1.3a／`docs/ACCEPTANCE.md` §6.0）。兩詞不混用；測試以 `contains` 鎖定三條分支關鍵字。
- 探測通過 ≠ PM可讀（P25 Item 4＋Phase 3 fail-closed）：`anyProbePassed`/`writeReady` 僅為探測門（`id -u == 0` 即可通過），**NOT** PM可讀證明；真可讀 ＝ `pmReadable == true`（`pm list users` 經 refresh 證明）＋`writeReady`。`pmReadable == false`（PM不可讀）時寫入被拒（fail-closed，`探測通過但 PM 不可讀…寫入已禁用`，`CHANNEL`），不嘗試寫入；`null`（未知，未刷新或已過期）仍允許嘗試，由 repo 層重驗 fail-closed。

## 2. Shizuku 配對教學（無線調試，Android 11+，流程 [待實機驗證]）

預期適用無 ROOT 裝置（三星 One UI 4.0+ / Android 12+ 為目標機型，未經實機驗證）。

1. 手機與電腦連上**同一個 Wi-Fi**。
2. 手機開啟**開發者選項**：
   - 設定 → 關於手機 → 軟體資訊 → 連點「版本號碼」7 次。
3. 設定 → 開發者選項 → 開啟**無線調試（Wireless debugging）**。
4. 安裝並開啟 **Shizuku**（GitHub 版），點選「經由無線調試啟動」→「配對」。
   - Shizuku 會顯示 6 位數配對碼；在系統跳出的「無線調試配對」對話框輸入配對碼。
   - 配對成功後回到 Shizuku 點「啟動」，看到版本號即表示 binder 已就緒。
5. 在 Shizuku 的「授權應用」中找到 **Dual your Galaxy** 並允許。
6. 回到本 App 點 `重新連線並更新`；F4a 接線後，已授權 binder 下 Factory 選 `ShizukuCmdGateway` 且 `ShizukuElevator` 探測通過時，標題列預期從 `(NONE/NOOP)` 變為 `(SHELL/SHIZUKU)`（標題列顯示實際執行身份＋已解析通道 `execIdentity/resolvedGateway`；選不到／探測未過則維持唯讀 fail-closed；此切換 [待實機驗證] DEVICE-UNVERIFIED，不可視為已可用）。

> 每次重開機後需重複步驟 4（Shizuku 需重新啟動）；配對碼只需配對過一次，
> 同一 Wi-Fi 下重啟一般直接按「啟動」即可。
>
> 已 root 裝置可用 Shizuku 自帶 so 直接啟動（無需配對流程，S24 已用此法）：
>
> ```bash
> adb shell /data/app/~~-jo681V4qGxj5nbJO4ijNA==/moe.shizuku.privileged.api-MyY9Gq9kawonTnGEcFOYdw==/lib/arm64/libshizuku.so
> ```
>
> 注意 `~~...` 中間路徑每次安裝隨機，實際以 `pm path moe.shizuku.privileged.api` 輸出為準。

舊裝置（Android 10 以下無無線調試）改用 USB：

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

## 3. 複製 / 移除 / 驗證 / 列表流程（需雙源驗證，`--user <dualId>` 僅為格式佔位）

以下 `<dualId>` 僅為命令格式佔位，不是推薦目標，更不是「任意非零即雙開」。
寫入前必須先完成雙源驗證：`cmd user list -v` 與 `dumpsys user` 兩個獨立通道
在同一非零 id 上同時給出 `profile.CLONE` 型別，且 parent/serial 門通過：
parent 須雙通道皆提供、相等、== 0（任一缺失 → Unknown；兩值不等 →
Refused「conflicting parent」；相等但非 0 → Refused「non-owner parent」政策）；
serial 雙邊皆有且不等 → Refused「conflicting serial」，單 fetch 任一缺失則容忍放行
（serial != id 屬正常）；跨 fetch 已知→未知亦拒（continuity lost，與實作一致）；
且系統 property（`getprop sys.dualapp.profile_id`）必須可讀且與該同一 id 一致（必要佐證，不可省略：缺失／空／格式未知 → Unknown 證據不足拒寫；指向不存在 id 或與 CLONE id 不一致 → Refused；不可讀版本按不支援版本明確拒寫，見 #21）；
任一通道缺失、未知、衝突或歧義（多 CLONE／型別打架／property 缺失→Unknown／property 打架→Refused）一律拒寫、
拒讀，不猜「第一個非 0 的 id」、不認 label、不固定 95/150、不比整組 flags。
App 內 `DualRepository`／雙 Elevator 探測即執行此契約（命令字串與 adapter 共用，fetch 經 `VerifiedIdentity.kt` 共用 fetch，[已支援] PR-1 階段2）：
`resolveDualUserIdVerified(userListVerbose, dumpsysUser, propertyId)` 僅在
`SamsungProfileAdapter.resolveDual() == Confirmed(HIGH)` 時回 id，否則 -1；
explicit id 即使存在於 `pm list users`，只要不是當下 Confirmed 目標一律
fail-closed：`clone`／`remove` 回 `refused:` `CmdResult(1)`、
`verifyState` 回 `Unknown`、`verify` Boolean 回 `false`（已 `@Deprecated`）、
`listClonedPackages` 回 `null`，不探測目標、不寫入（仍做 snapshot＋identity fetch 唯讀）。
同 id 重建（serial 變更）視為不同目標：批次 pin 與移除確認皆比對 `(id, serial)`，不一致零寫入。
`pm list users` 的名稱僅供顯示，永不進入授權判定；user 0 永遠不可作為寫入目標。
CLONE 僅認全 token 精確拼寫：`profile`＋單點 `.` 或 1–3 個列舉分隔符（空格／`_`／`-`／Tab 任意混搭，如 double space、`__`、` -` 皆計，4 個以上即無 token→OTHER）＋全字 `clone`（大小寫-insensitive）；`!`／`?`／`:`／`/` 等任意其他標點永不為分隔符，一律無 token→OTHER（fail closed）；`CLONE_extra`／`CLONE-EXTRA`／`CLONE.X`／`CLONEable` 等未知後綴一律非 CLONE；`profile.CLONE/evil` 類斜線尾詞一律 tainted→OTHER：`/` 僅在後緊接另一個合法 `profile.X` token（`profile`＋單點或 1–3 個列舉分隔符＋名稱，如 `profile.MANAGED`／`profile.CLONE`，含 FQ `android.os.usertype.profile.X`）時豁免，此時走雙 token 混合→UNKNOWN 仍拒寫；`/profile!evil`、`/profilegarbage`、裸 `/profile`、`/profileX` 等非 token 一律不豁免，仍 tainted→OTHER；括號內斜線如 `(a/b)` 即使無裸 slash 語義亦保守降級→OTHER（fail closed；實機 type 行無此形狀）。殘留驗證（唯一授權＝全 token 匹配＋slash-guard＋殘留驗證）：附註僅指空白／Tab 與恰一次的 `(default)`（大小寫-insensitive，前後可空白）；直接貼在 CLONE 後的任何其他字元（含 `!evil` `?evil` `:evil` `;evil` `,evil` `@#$%^&*+=|\`、全形 slash、零寬／control、非 default 括號 `[evil]` `(evil)` `(a/b)`、`(default)evil` 多餘尾詞、雙 `(default)`）一律殘留→OTHER；逗號分隔重複 CLONE（如 `CLONE, CLONE`）亦殘留→OTHER（從嚴）。僅 `profile.CLONE (default)` 容忍為 CLONE；`MANAGED, CLONE`／`MANAGED/CLONE` 類真混合走雙 token 矛盾→UNKNOWN，仍拒寫。
此次 S24 的 95 為 Dual、150 為 Secure Folder，這不是其他機型的固定對照。
執行前核對 serial/parent（ID 重用防護，Phase D）：auto 路徑攜帶 resolve 時 identity
作 `expected`，guard 以 fresh 比對——同 id 但 serial／parent 雙邊皆有且不等即拒
（reason 註明 reuse，疑為同 id 被不同 profile 轉世重用）；pinned 寫前取 `before`、
寫後重核 `after`，不一致即 `VerifyUnknown`（detail 註明 identity-changed，fail
closed，不計成功）；explicit-ID 入口僅做 fresh 自洽檢查（expected=null），殘餘
TOCTOU 已揭露（check 與寫入之間重建同 id 無法在此入口偵測）。serial 單邊缺失則
放行（tolerate，與 Phase B 一致）：僅雙邊皆有且不等才拒；跨 fetch 已知→未知亦拒
（continuity lost，與實作一致）。
pinned 寫入（`cloneVerified()`／`removeVerified()`）為 snapshot1＋before3＋fresh3＋write1＋after3＋list1＋post-list re-verify triple3＝15 命令（probe 最多 2 次 retry 另計）；此 15 為 repository／API 層「單次 pinned 寫入」的命令數，非 App 層使用者感知的總成本：FRESH 成功後另做一次 `fetchFreshVerifiedTarget()` triple（約 +3，合計約 18），偵測到身分漂移時再跑 `refreshVerified()`（約 +8，合計約 26）；此 refresh 亦在「舊 FRESH inventory 的 serial 為 null，但寫入／同意／當下 triple 已可讀到 serial」時觸發（證據升級，非已證明身分漂移）：仍只做唯讀 `refreshLocked()` 建立完整快照，不重送寫入、不附註「target 已重建」；所有 serial 全程 null 的能力限制維持不變。實際命令數會隨 retry／路徑浮動，僅為約略值。post-list fence 在 `pm list packages` 完成後再核對 pinned `(id, serial, parent)`，漂移即回 `VerifyUnknown`（不發布新 incarnation 清單為舊目標成功；serial==null 時仍為能力限制，無法證明同 id 轉世）；寫前 fresh-vs-before 重核僅縮小 before-to-write 重用窗口，仍殘餘 check-to-write／fresh-to-write 微窗口（fail closed，與 DualRepository KDoc 一致）。此 fence 非原子交易：最終檢查之後的變更仍無法觀測；上述 fence 漂移情境與命令數之證據皆為 JVM fake-gateway 重現，非 Samsung 實機驗證（本項實機閉環由 #79 追蹤），不宣稱實機保證。
`verifyState`／`verify`（含 auto `verify(pkg)` 與已 `@Deprecated` 的 `verify(pkg, dualId)`，皆經 `verifyOnProfiles`）於 package listing 後亦增加同一 post-list identity re-verify triple（+3 命令）：短暫身分丟失／漂移即回 `VerifyState.Unknown`（Boolean `verify` 回 `false`），不再回報 `Installed`；此 fail-closed 同樣套用到 VM 的移除請求／確認前檢查（`requestRemove`／`confirmRemove` 的 `verifyState` precheck）——身分瞬斷即不開確認對話框／不卸載（Unknown → CHANNEL／VERIFY_UNKNOWN，零寫入）。
列表刷新（`refreshVerified()`，Phase D P2-D）為單一 target：一次 `pm list users`（1）
＋一次 typed target（`cmd user list -v`＋`dumpsys user`＋`getprop`＝3）
＋一次 `pm list packages --user <dualId>`（1）＋post-list re-verify triple（3）＝8 命令（舊 resolve-then-reverify
列表路徑為 9）；同一 target 全程，第二次 triple 即 strict-equality fence，有可觀測 serial 時 resolve→list 漂移在本 API 內消除；serial==null 時 fence 只證明兩次 typed 檢查確認同一 (id,parent) 且列舉成功，不證明同一 incarnation；fetch→probe 殘唯讀窗口（只誤報 presence/absence，不重定向寫入）（explicit
舊入口殘餘除外）。Auto 降級與單一恢復入口仍由 #25 擁有，本 API 只保證列表
一致性（serial==null 時僅同 id/parent 兩次確認+列舉），不改變政策門。初次 fetch 雙通道皆無 serial 即容忍放行，屬能力限制而非全防護；非阻塞操作生命週期由 #24 追蹤。註（#53）：`DualViewModel.refreshLocked()` 已遷移到 `refreshVerified()` 8 命令單一快照（snapshot 1＋triple 3＋list 1＋re-verify triple 3），VM 只做 `RefreshOutcome→UiState` 映射，第二次 triple 即 strict-equality fence；pinned target 攜帶真 parentId，parent drift fail-closed（Unknown）；serial intra-triple conflict fail-closed, inter-refresh rebuild = whole-world FRESH，不發布虛構 FRESH 配對（僅指有可觀測 serial 時；serial==null 時同 id 重建後新 list 仍可能以 FRESH(id,null) 發布）。serial==null FRESH 僅代表單次快照內已列舉且兩次 typed 均確認同 id/parent，不保證 incarnation continuity；不發布虛構 (oldSerial,newList) 配對僅指有可觀測 serial 時；serial==null 時同 id 重建後新 list 仍可能以 FRESH(id,null) 發布，寫入授權不由此承擔（pinned 寫入路徑寫前/寫後重核）。 Refresh 的 `Unknown` 保留舊 `cloned` 並標 STALE、`pmReadable=true`（`NoDual` 才清空），inter-refresh 重建不再發 `VERIFY_MISMATCH`（兩次 refresh 間的重建下次以整個新世界 FRESH 發布），`Unknown` 有四形（typed refusal／confirmed id 不在 pm snapshot／Absent+空快照／post-list identity drift）。`TargetMoved`（confirmed post-list drift → `VERIFY_MISMATCH`、STALE live target、selection cleared，需 reconfirm；inter-refresh rebuild 仍以整個新世界 FRESH 發布）。
Listing 判定為 P22 已合併之 trusted 三態（`PmOutputParser.parsePackagesTrusted`，PR-2 stage 2 已消費於 `verifyListProbe`／`listProbe`）：`TrustedFull(packages)` 為白名單乾淨 listing（含包為 Installed）、`TrustedEmpty` 為零 `package:` 行之確認空（confirmed empty）、`Unknown(reason)` 為不可判（truncated／exit 非零／error-semantic／格式或 stderr 殘留，`detail` 為 `reason` 別名）。白名單（P1 BLOCKER #1）：權威 listing 除合法 `package:` 行外別無其他——每行非空 stdout 須以 `package:` 開頭（大小寫容忍，與 `TRUSTED_EXEMPT_LINE` 一致），payload 須符合 `PACKAGE_PAYLOAD`（`^[A-Za-z0-9._-]+$`，與 `ShellEscaper.SAFE_TOKEN` 同字符集，trusted 路徑專用；`parsePackages()` 本身維持寬容）；`unexpected listing format`／`malformed package line`／`unexpected stderr` 永不當空（fail closed，`refresh` 保留舊顯示）。`installed for user` 回聲在 trusted 路徑不豁免（僅 `package:`／`Success` 行跳過 error 掃描；`ALREADY_EXISTS` 在 listing 無例外，任一 `Failure` 行即 untrusted）。
截斷契約（P22）：`CmdResult.truncated` flag＋`[truncated]` marker 文本回退（`TRUNCATION_MARKER`，新舊 producer／Shizuku Binder 重建 flag 之相容嗅探）；listing 任一截斷→`Unknown`（exit 非零另附 `exit=`＋首行 hint，`take(200)`）；identity 任一通道截斷→fail closed（`pm list users` 截斷→`profilesSnapshot` `ChannelError` 不解析，Elevator pm-list 門同理降級；`cmd user list -v`／`dumpsys user`／`getprop` 任一截斷→`fetchVerifiedTarget` null／`-1`，截斷 property 不視為 no claim）。Shizuku 側 `SERVICE_VERSION=6` 升級閉環（`ShizukuExecProtocol`：v1 僅 cap 打標，v2 截斷必打標，v3 = v2 打標 + owner-uid EXEC gate，v4 = v3 打標+閘門 + appId 正規化閘門（特權 appId 0/1000/2000 不分 user 一律拒絕），v5 = v4 打標+閘門 + appId 上限閘門（appId > 19999 不分 user 一律拒絕，含 SDK sandbox 20000-29999 與 isolated 99000-99999），v6 = v5 + 併發閘門（`SERVICE_EXEC_MAX_CONCURRENT=4`，pre-dispatch `serviceBusy`，fail-fast）+ drain 同步（`snapshot`/`drainCapped` 於 `synchronized(sink)`）+ parcel-null 守衛＋post-spawn catch 補 marker（null/blank wire cmd 不 spawn、null reply 字串轉 transportUnknown、post-spawn 兩 catch 分支 err 加 `\n[truncated]` 使本地 truncated 跨 wire 誠實）；`.version(6)` 迫 daemon 重建常駐 UserService，關閉 NEW-client＋OLD-service 間隙並作廢 v5 常駐服務；S24 實機升級驗證仍待補）。
Throw defense（P22 R5）：全路徑 fail-closed（`profilesSnapshot`／`verifyListProbe`／`listProbe`／`fetchVerifiedTarget` 皆捕獲 gateway throw 轉 `ChannelError`／`Unknown`／null，`CancellationException` 重拋保協程正確；`InterruptedException` 在含 sleep／阻塞讀取的路徑（`verifyListProbe`、`SuCmdGateway` capture、fetch／probe 讀取側）先復位 `Thread.currentThread().interrupt()` 後同樣 fail closed；純寫入側僅 CE 重拋＋Exception 收斂，中斷旗由 gateway／調用方處理）。Detail caps：trusted 解析器截斷／exit／error 行 hint 皆 `take(200)` 有界；`VerifyState.Unknown`／`CloneOutcome`／`RemoveOutcome` detail 供顯示層參考，顯示層 bound（VM 層直接插值全文，改動 out-of-scope，見 `DualRepository` KDoc）。
Retry 凍結（PR-3 擁有，禁 Main）：`verifyListProbe` 非零 exit 至多 3 次（含初次）＋`VERIFY_RETRY_DELAY_MS`（1500 ms）settle wait（經 `delayFn`，預設可取消 `delay()`；測試可虛擬化；生產經 `onIo`／`Dispatchers.IO`，VM 層所有 repo 呼叫皆走 `onIo`；cancel 在等待間傳播不折疊為 Unknown）；exit-0 截斷／髒 listing 不 retry（trusted 已判 Unknown 即停，零等待；僅 exit 非零觸發 retry）；`listProbe` 零 retry（`refresh` 映射 null 為保留舊值，一次 stale 不丟資料；throw 即 null，cancel 重拋）。Locale 限制（PR-2 Phase 3 R4）：`ERROR_LINE` 僅匹配英文 pm 輸出，非英文 ROM 髒行可能漏判為已知 trade-off；fail-closed 方向不變（截斷／非零／malformed／白名單規則不受語言影響，非英文錯誤行落白名單 `unexpected listing format` 仍 `Unknown`）。

```bash
# 1. 雙源驗證（缺一不可；任一失敗即停手，不寫入不探測目標）：
adb shell cmd user list -v
#   每個 UserInfo{<id>:<label>:<flags>} 塊內找 User type 行：
#   目標 id 的兩通道都必須是 android.os.usertype.profile.CLONE（大小寫/分隔符容忍）；
#   parent 須雙通道皆知、一致、== 0（缺失 Unknown；衝突或非零 Refused）；
#   serial 雙邊皆有且不等即 Refused，單 fetch 任一缺失則放行（serial != id 屬正常）；跨 fetch 已知→未知亦拒；
#   label 含不含 DUAL 都不看；未知一律 fail closed。
adb shell dumpsys user
#   同一 id 在此通道同樣必須是 profile.CLONE；若此處是 SYSTEM/MANAGED/SECONDARY
#   或無型別行，即與上一通道矛盾或證據不足 -> 拒寫。
adb shell getprop sys.dualapp.profile_id
#   其值必須等於上述同一 id（必要佐證）；不可讀/空/格式未知 → Unknown 證據不足拒寫（不可讀版本按不支援版本明確拒寫），
#   指向不存在 id 或與 CLONE id 不一致 -> Refused 拒寫。

# 2. 僅在上一步確認同一 <dualId> 後，才可用該 id 執行寫入（示例用 95 佔位，
#    實際必須填上一步 Confirmed 的 id；未確認、未知、矛盾時不執行以下任何一行）。
#    將已安裝的 App 複製進雙開 profile（不會動到 Secure Folder）：
adb shell pm install-existing --user <dualId> com.example.chat

# 3. 唯讀診斷輔助：查看套件路徑；僅輔助診斷，不作主驗證（主驗證以 listing 為準，見 #22）。
adb shell pm path --user <dualId> com.example.chat
#   package:/data/app/.../base.apk  -> 僅表示該包有路徑回應，不能單獨證明雙開成功

# 4. 只移除雙開分身，不動主 profile（user 0）
adb shell pm uninstall --user <dualId> com.example.chat
```

- `install-existing` 僅對已存在於主 profile 的 package 有效；全新 APK 請先在主 profile 正常安裝一次。
- 重啟注意（S24 OneUI 9 兩次觀察，假說待對照實驗）：`pm install-existing --user <dualId>` 建立的雙開連結在硬重啟、軟重啟後各消失一次（user 0 主包與完整安裝的雙開包不受影響）；疑為開機時 DualAppManager 對非白名單條目做對帳清理，恰為本 hook 要放寬的對象。重啟後請以 `pm list packages --user <dualId>` 複驗，消失即重裝。
- 指令失敗時請看 `msg` 欄（`Failure` / `Unknown package` / `not installed` 皆視為失敗）。
- 本 App 的 `DualRepository.clone/remove/verifyState/list` 即為上述指令的程式化封裝（[已支援]：`core` 層＋單元測試＋JVM 組裝測試；App 真 gateway 與 readiness gating 已接線：`SuCmdGateway` 經 `su -c`／`ShizukuCmdGateway` 經 UserService＋`channelReady` 探測 gating＋選不到回 `NoOp` fail-closed；目標解析為 PR-1 階段2雙源驗證＋失敗拒寫，label-only 舊路徑已 `@Deprecated`，`verify(pkg,dualId)` Boolean 已 `@Deprecated` 請改用 `verifyState`）。寫後確認（`cloneVerified`／`removeVerified`）以相同已驗證目標的 package listing 為準，不再只用 `pm path`（`pm path` 僅輔助診斷，不能當主驗證；plain `clone()`／`remove()` 無內建 re-check），listing 判定為 P22 已合併之 trusted 三態＋白名單＋截斷契約＋throw defense＋detail caps＋retry 凍結（見 §3 P22 段）。[S24 已驗證]僅限 App ROOT 一鍵、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、裸 pm clone、`su -c` 重裝、uninstall 回滾，詳見 §0 與 [S24 調查](UX_REVIEW_S24.md) §5；App ROOT 之外的完整 binder／授權矩陣、App 內 Shizuku 未授權路徑／首次選路、marker gateway-`cat` 顯示及當前 source 可追溯部署仍 [待實機驗證]。
- 移除確認已知限制（PR-33）：
  - (a) 確認 TTL 5 分鐘走 monotonic 時鐘（`SystemClock.elapsedRealtime()`，免 wall-clock 回撥延長有效期；pending 不跨重啟，reboot 重置無影響；JVM 預設 `currentTimeMillis` 僅供測試注入）。
  - (b) 同名重裝限制：membership 僅保證確認時可見同名 package，不綁定安裝世代（profile 世代由 serial 綁定；package 安裝世代不綁定：同名重裝視為同一 membership）。
  - (c) shell 檢查→uninstall 非原子（裝置限制，repo 雙閘僅縮小窗口）。
  - (d) S24 實機 destructive／negative acceptance [S24 已驗證]（2026-11-05，SM-S9210，ROOT 通道）：身分雙 CLONE＋serial 95＋prop 95 確認、`Cloned (35)` 真實列表；可拋棄 overlay（`com.android.settings.supervision.overlay`，owner 有／95 無，先 `install-existing` 進 95）經 App 兩階段確認移除→`removed:`＋95 內消失＋owner 副本完好＋總數回 35（淨零）；negative（取消鍵、back 鍵）皆零卸載並保留正確訊息，截圖存檔；未安裝拒絕以 JVM 測試為準（實機訊息僅截圖存檔）。殘留：旋轉重建、TalkBack 朗讀僅 JVM／靜態證據。

## 4. 通道選擇與 LSPosed 模式（F4b-P1 picker＋P2 marker；ROOT／SHIZUKU 已授權一鍵已抽驗 [S24 已驗證]，其餘 [待實機驗證]）

> **安裝／升級警告（v0.1.0-beta.3）**：下達「作用域勾選→重開機」前請先讀本文件「安裝」節完整警告與 repo 內 `docs/RELEASE_NOTES_beta3.md`：全新安裝僅相對於舊 id `com.galaxydual.plus`（beta.2→beta.3 同 id 為 in-place 升級、**不需卸載**），**無資料／偏好自動遷移**、無內建匯入匯出，先備份並確認可還原；**舊版管理 App 與其 LSPosed 模組不可與新版同時啟用**。Samsung 可能自動刪除分身並永久清除資料；一般既有雙開 App 不需為此套件 id 遷移而**手動移除**，且這**不表示**分身「不受影響」——本 App 沒有刪除防護。停用／卸載／opt-out／重開機不是刪除防護，beta.3 hook 可能早於首次 App UI 警告生效。**已發布的 Release page 仍有舊身分／A/B/C 文字；maintainer 同步前以上述警告與 `docs/RELEASE_NOTES_beta3.md` 為準。**

- 已 ROOT + LSPosed 的裝置：到 LSPosed 管理器勾選 **Dual your Galaxy**，
  作用域勾 `system`＋`com.samsung.android.da.daagent`（見 `docs/HOOK.md` §1.5／§2.2）後重啟；再到 App 內按 LSPosed 按鈕切到 LSPosed 模式：Hook 行顯示 marker 狀態（per-scope marker；`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，P1 四態見本節末段；file marker 存在性證據，僅顯示、永不授權；daagent 側因 SELinux 寫入失敗故 marker 恆缺 daagent，見 `docs/HOOK.md` §1.5）；LSPosed 通道為 NoOp 佔位、寫入一律拒絕（BBC 注入（非寫入通道）僅 11-17／11-18 舊 build 有實機證據，**本版本（beta.3）experimental／未實機驗證**：Samsung 設定頁可見第三方 app 並可雙開，見 [docs/HOOK.md](HOOK.md) §1.3a／§2.3；寫入通道仍為 NoOp 佔位，一律拒絕）。marker gateway-`cat` 顯示 [待實機驗證]，無實機證據。
- AUTO 模式按 su→Shizuku→NoOp 解析執行通道（hook 永不被自動選中）；`channelReadyFor` 純函數 [已支援]（ROOT 需 su 探測、hook 訊號永不授權、NONE 永不就緒）；Auto probe 降級已實作（P25：首次探測即依 live probes 修正啟動猜測——su 存在但探測拒絕＋Shizuku 通過→SHIZUKU＋降級說明，雙通道皆失敗→NOOP 唯讀，暫態失敗後點 `重新連線並更新` 即重探恢復）；實機切換 [待實機驗證] DEVICE-UNVERIFIED。
- LSPosed 模組 hook 雙開白名單**查詢**（Stage-1 封緘 `NON_EMPTY_POLICY_EFFECTIVE_GATE=false`，預設 observe-only、零行為變更）與 BBC 清單生成（效應閘 `BBC_AVAILABLE_APPS_INJECTION=true`，union-only 只增不減；BBC 路徑僅 11-17／11-18 舊 build 有實機證據（本版本（beta.3）experimental／未實機驗證）；見 `docs/HOOK.md` 第 1.1／1.3a 節），
  絕不讀寫 Knox 容器；Knox／Secure Folder 面永不 hook。
- P1 診斷限制（配合 [#27](https://github.com/samson910022/Dual-your-galaxy/issues/27)）：Hook 狀態為四態——偵測到歷史 Hook 標記（僅存在性證據，未確認本次開機是否生效）／未偵測到／狀態未知（無權限讀取）／狀態未知（無可用診斷通道）。明選 LSPosed（NoOp）時診斷讀取獨立於所選寫入通道：仍嘗試直接讀取＋經當時已探測通過的真通道（ROOT／SHIZUKU）`cat`；切換通道時保留當時已驗證的診斷實例（新實例未驗證前仍以後者 `cat`，不觸發新 `su` 授權；新驗證通過即覆寫，授權撤銷即失效回未知）；若無任何真通道且直接讀取不可讀（App uid 對 `/data/system` 恆 EACCES），顯示「狀態未知」而非「未偵測到」——後者只保留給確定可讀但缺失的情況（fail-closed 誠實顯示，不再假陰性）。要完整診斷需至少一個探測通過的真通道；診斷結果永不進入路由／`channelReady`（hook-neutral，沿襲 #19）。

## 5. 常見問題

| 症狀 | 原因 / 解法 |
| --- | --- |
| `(NONE)` 或「通道未就緒」 | 先區分所選 gateway、探測能力與 Shizuku binder／授權。未就緒時會拒寫；點 `重新連線並更新`（probe 含 Auto 降級＋refresh 單一入口）即重探恢復。Auto probe 降級已實作（#25）；不能以標題的裝置路由代替實際通道狀態（標題列顯示的是 `execIdentity/resolvedGateway`）。 |
| `no dual profile` | 可能是未建立、候選歧義或輸出無法解析，不能一律解讀為尚未建立。先唯讀核對平台身分；不確定即停止寫入，見 #21/#27 |
| `Failure [INSTALL_FAILED_...]` | 來源 APK 不存在或儲存空間不足；先確認主 profile 已安裝該 App |
| `unsupported Shizuku version` | Shizuku 低於 v13：v13 才有 UserService Context 構造，本 App 在綁定前明確拒絕；請更新 Shizuku 至 v13 或更新版本 |
| Play 審查相關警告 | 本專案不發 Play 版，請一律使用 GitHub Release 的 APK |

## 6. 來源選取與批次建立（PR-7 P2 #26 階段 1）

個人來源 catalog 為唯讀展示＋勾選：`MainActivity` 經 `PackageManager.getInstalledApplications(0)`
僅列舉呼叫者自身 user 可見的已安裝 App（政策：不跨 user 枚舉，不讀取其他 user 的套件、檔案與私有內容，包括其他 user 下的容器資料），
以 `SourceAppEntry` 唯讀列出；寫入目標仍以 `DualRepository` 跨來源 verified dualId 為準（見 §3），
本表只決定「要對哪個包名打勾」。

- 搜尋／系統開關語義：搜尋列比對應用名稱或包名子字串（大小寫無關，空白不過濾），
  只改變顯示範圍；「系統應用」開關只切換是否顯示系統 App，兩者皆不觸發寫入。
  旋轉重建時搜尋字串由 ViewModel 回填（保留過濾條件）。
- 勾選≠卸載：清單列為 CheckBox，勾選／取消勾選只改 `UiState.selectedKeys`
 （身分鍵為 `serial:package` 的 AppKey），永不呼叫 uninstall；取消勾選不是移除分身。
- 保存／取消保存／清除勾選三者區別：
  「保存選取」只把目前勾選寫入 app-private 持久化（需已知目標 `targetId > 0`，
  否則按鈕禁用；只保存仍在 catalog 的 key），不寫入任何分身；
  「取消保存」只刪除持久化條目，不卸載分身、也不清除目前記憶體勾選；
  「清除勾選」只清空記憶體勾選（顯示「已清除勾選（未卸載任何分身）」），不碰持久化、不卸載。
- 全選可見截斷語義：清單單次最多渲染前 200 筆（`MAX_SOURCE_ROWS` 效能護欄，
  超出顯示「…等共 N 筆，僅顯示前 200 筆，全選可見只選前 200 筆」）；
  「全選可見」與渲染走同一過濾／排序／截斷，只勾選可見前 200 筆，超出請用搜尋縮小範圍。
  來源清單載入失敗時保留舊列表（不清空），並顯示獨立錯誤行（與「尚未載入」區分），「重新連線並更新」按鈕會同步重試 catalog 載入；「系統應用」開關的無障礙描述隨顯示中／已隱藏狀態同步更新。
- 批次按鈕計數與部分成功解讀：按鈕顯示「建立 N 個分身」（N＝目前勾選數，無勾選時禁用；
  批次執行中勾選區與相關按鈕一律禁用，不可中途改勾選）。
  執行走 `cloneVerifiedExpected(pkg, expectedId, expectedSerial)` 逐項 pin（非原子交易，逐包驗證）（包名升冪確定順序），結果分成功／失敗／未知三類：
  成功才加入 `cloned`，失敗（含 catalog 缺失的 `InvalidPackage`，以及真 Absent 的 `NoDual`）與未知（`VerifyUnknown` 與 `ChannelError` 皆計未知：身份暫時無法判定、零寫入，需重查，不算失敗、不宣稱無分身；純未知彙總診斷按首個未知項選碼，`ChannelError`→`CHANNEL_ERROR`，否則 `VERIFY_UNKNOWN`）分開列出；
  批次起始先檢查選取綁定目標與裝置當前目標一致（`selectionBoundTarget == expectedTarget`，含未綁定哨兵與 legacy id-only），
  不一致則整批拒絕並清空選取（`INVALID`「選取已綁定舊目標，已清空，請重新勾選確認」，零寫入）；
  逐項 pin 發現目標漂移時立即停止（漂移本身不記為條目，已完成項保留為 partial，診斷為 `VERIFY_MISMATCH`），
  部分成功如實呈現（例「成功 1 / 失敗 1 / 未知 0」）；全成功清診斷事件，否則留彙總診斷；
  成功或未知（可能已寫入未驗證）即把清單標 STALE；目標漂移時無條件 STALE 並更新為 live target、清空選取（同目標變更處理）。
  唯讀／佔位通道整批拒絕（不產生報告）；勾選變更後舊報告即清（stale 報告不保留）。
- 兩步確認與 sticky bar：批次 CTA 與勾選數固定於底部 sticky bar（貼 bottom navigation 上方，不隨清單捲動；只在雙開應用頁顯示，切到 Engine 頁自動隱藏）；點「建立 N 個分身」不直接執行，先開「確認建立分身」對話框（警告、將建立數量、目標 user/serial）。inventory 為 FRESH 時顯示「已驗證目標」，否則顯示「上次辨識目標（執行前將再次驗證）」。須勾選確認框才啟用「確認建立」。確認快照綁定勾選集合與目標（含旋轉保留；key/目標/新鮮度語境變動即失效並重設確認），VM 在 `opMutex` 內驗證核准快照與 live/pin 目標後才寫入，不符即拒絕（零寫入）。sticky bar 會鏡像簡短原因/結果摘要（可點按前往詳情）以便捲動時辨識狀態（batch_create_cd 已標註需二次確認）。
- 重啟恢復行為：重啟後新 ViewModel＋同一持久化，恢復只打勾（永不自動執行 clone），
  只恢復仍在目前 catalog 的 key（已卸載的包自動捨去），且同一目標只恢復一次；
  空 catalog 上不置恢復標記（待 catalog 就緒後仍可重試）；目標變更（profile 變動）時舊選取失效清除。
- 誠實性註記：本節 UI 呈現行為（搜尋／開關／勾選／截斷／批次文案／旋轉回填／重啟恢復）以單元測試＋靜態審查為證；
  TalkBack 朗讀、旋轉重建實機表現、長清單圖示效能、真通道 batch 端到端仍 [待實機驗證]（與 §0／§4 及 PR body 的 Device verification left open 口徑一致），
  不得宣稱為已通過實機驗證。
- PR #89 Round-4 R4-1 政策寫入「已 dispatched ≠ 已 admit」：`DualViewModel.confirmPolicyWrite` 的同步 `ACCEPTED` **只代表 coroutine 已啟動**，不代表交易已取得；真正的 async 結果（coordinator `writeMutex`／claim 失敗時保留 `QUEUED` pending、零 save，或成功結清／Tx handover）由該 coroutine 最外層 `finally` **只發一次** `PolicyWriteConfirmAttemptOutcome`（`RETRYABLE_RETAINED`／`CONSUMED`／`HANDED_OFF`／`SUPERSEDED`），判決由結束時的觀測狀態決定（同世代且仍 `QUEUED` 優先於任何 Tx 殘跡）。兩屏 Activity 以 **per-pending、token 化** 的 `PolicyWriteAttemptTracker` 橫跨 revalidation＋VM 預讀／認領窗口：重複按「確認寫入」直接忽略（不再誤走 `!isLive → cancelPolicyWrite`，取消掉自己剛送出的寫入）；明確的「取消／返回」仍照 R3-1 條件式取消；警示 action 只在 `CONSUMED`／`HANDED_OFF` 或同步 `TERMINAL` 時退休，`RETRYABLE_RETAINED` 時保留 live action 讓使用者重新勾選後以**全新** revalidation 重試。遲到的舊 attempt settlement 無法退休較新的 action（token 以 atomic `exit` 消費＋generation／action 雙重比對）。純函式真值表、VM 真實 BUSY／重試一次寫入／重複 Positive／明確 Negative 與兩屏原始碼形狀由 JVM 測試鎖定；**AlertDialog／Looper／實機按鈕與旋轉時序仍為 DEVICE-UNVERIFIED**，不得宣稱已通過實機驗收。
- PR #89 Round-3 R3-3 政策寫入 final 對話框旋轉孤兒 fail-closed：ViewModel 在旋轉後仍在，尚未送出的 `QUEUED` pending 會重播，但每個 Activity 的 `WarningRestartController` 於 onDestroy 清除，且 consent／ack 為 session-scoped（永不寫入 Bundle，亦不自動 rebind／auto-ack）。若據 saved state 重建後該 pending 已無 live consent action，則在顯示（或重建）final 對話框**之前**先取消該孤兒 pending、清掉 dialog handle／holder 狀態，並以既有 `pre_write_recheck_failed` 字串提示使用者重新操作；`SAVING`／`RECONCILING` 與 app-scoped Tx／handover 完全不動。純函式真值表（僅 rebuilt＋`QUEUED`＋無 live action 才失效）與兩屏原始碼形狀由 JVM 測試鎖定；**旋轉重建的實機對話框行為仍未驗證（DEVICE-UNVERIFIED）**，不得宣稱已通過實機驗收。

### 政策寫入流程（Stage2：白名單政策草稿）

> 政策草稿有兩個入口，語義相同：MainActivity「寫入政策」（catalog 勾選），以及 BBC 政策編輯器（App → MainActivity 技術卡「編輯應用清單」→ `PolicyActivity`，`exported=false`，installed-apps picker：搜尋／圖示 LRU 64／200 列上限／VM 狀態 `policyDraft*`）勾選 →「寫入政策草稿（N）」→ `DualViewModel.replacePolicyDraft(pkgs)` → **同一** pending/CAS/世代兩階段管線 → in-page 確認框（≤200 項上限＋ack 勾框＋「確認寫入」）→ `confirmPolicyWrite`。兩者皆 whole-set 整組取代，無 per-toggle 寫入；編輯器以 `policyDraftKnown=false` 時隱藏寫入路徑並顯示 main 風格 Unknown 引導。BBC 政策生效面＝Dual Messenger「可用的應用程式」清單擴展（見 `docs/HOOK.md` §1.3a）；白名單 Stage-1 查詢面 sealed false（observe-only），政策寫草稿不影響其行為。

- `寫入政策` 只把目前勾選映射為包名快照，建立兩階段確認 dialog（列出前 50 項＋溢出行、需勾選確認框；TTL 5 分鐘（`POLICY_CONFIRM_TTL_MS`，此 5 分鐘為 App 側確認 TTL，與 hook 側 `EffectivePolicyCache` 的 60 秒 TTL 不同，詳見下表）、同 pending 世代保留勾選；包名逾 10 項時 dialog 清單限高（一般 320dp，小螢幕 smallestWidth<360 或 screenHeight<640 回 240dp，見 `policyDialogListMaxHeightDp`），少量時 WRAP_CONTENT 不留空，ack 永遠可見）；`確認寫入` 才經 `HookPolicyStore` 寫入 LSPosed remote preferences 的 `dual_policy` group（`HookPolicyStore.save` 為原子單鍵全量取代 REPLACEMENT：`putStringSet(...).commit()` 整組覆寫、不合併累加）。Stage L4：展開顯示封頂前 200 項（`POLICY_DIALOG_DISPLAY_MAX`，display≡inspectability）：總數>200 即禁用確認＋超限警告（`policy_write_over_cap_warning`：每次寫入整組取代、不合併，單次政策須縮減至 ≤200 項，尚無合併寫入流程；先寫 200 再寫 112 最終僅剩 112，而非 312），Toggle/播報切 capped（`show_all_capped`／`expanded_announce_capped`，複用收合語義「已展開前200項，共N項，超限須縮減；寫入整組取代」）；寫入仍用完整 pending.packages，但須經門控單次確認（`policyWriteCanConfirmInspectable`）。
- 同世代（same-generation）綁定：pending 攜帶 `PendingPolicyWrite.generation`（`policyWriteGeneration` 單調唯一，既有 pending 時新請求退回 BUSY（pending 保留），世代不再有覆寫路徑（見 `DualViewModel` 之 `requestPolicyWrite` pending→BUSY 門控與 `policyWriteGeneration`（定義處）、`PendingPolicyWrite.generation`）；`確認寫入` 僅在傳入 pending 與 live pending 全等＋TTL 內才寫 store（見 `confirmPolicyWrite`），不符即拒寫（零寫入，舊世代傳入保留 live pending）；清單展開為 Bundle-retained，依 `expandedGeneration` 於同世代還原、跨世代重置；**ack 勾選為 session-scoped，永不寫入 Bundle，`PolicyConfirmDialogState.restoreFromBundle` 強制清除 `acknowledged`/`ackGeneration`，重建後必須重新勾選**（不 auto-ack）；若據 saved state 重建出的 `QUEUED` pending 已無 live consent action，則視為孤兒並先失效取消（見上方 PR #89 Round-3 R3-3 註記），不自動 rebind／auto-ack（UI 以 `pending.generation` 比對，見 `MainActivity` 之 confirm 對話框 sync 路徑）。
- `清除草稿` 只刪該持久化條目，不清記憶體勾選。
- 只有 owner user（user 0）能寫入／清除草稿；非 owner 的寫入 hook 讀不到（訊息明確拒絕）。
- 清除政策前的雙開警告（Issue #73）：以 Root（`su`）探測 user 95 已安裝的分身、在清除前列出並警示；無 Root／目標未驗證／探測失敗（Failed）時**不靜默放行，也沒有任何「無視警告繼續」入口**——對話框只提供「重試探測／取消」（Unknown 時為「重新整理／取消」），零 consent、零 pending、零 store 寫入。Shizuku／無 Root 裝置沒有此探測能力（僅 Root 通道），警告為 fail-closed 而非缺漏；δ=∅（Known）的純新增／無移除路徑不依賴探測結果、不顯示探測警告（仍會唯讀重探一次作觀測用途），不受此限制。舊的 force-proceed 入口與字串已移除，該警告對話框不再有任何可跳過警告的文案（ROOT 卸載後驗證對話框的「自擔風險」選擇屬另一獨立路徑）。
- `勾選≠安裝`、`寫草稿≠安裝`：政策寫入只放寬 hook 的可用清單判定（目前生效面＝BBC「可用的應用程式」清單，`BBC_AVAILABLE_APPS_INJECTION=true`；Stage-1 白名單查詢面 sealed false、不受影響），永不觸發 clone/remove/install。
- `Unknown`（草稿未知）時拒絕建 pending／拒絕覆寫：須先以「重新整理草稿」讀到 Known 後再寫入／清除（fail-closed，不覆寫未見內容）。Stage L4：Unknown 拆分為瞬斷 Unavailable（null／IPC／timeout／非 CCE）與損壞 Corrupt（CCE）；**R5-0（F01）已正式撤回（withdraw）盲清（blind-clear）**——Unavailable 的二次確認／TTL／世代重 arm／「經典雙擊」全部停用，`clearPolicyDraft` 對任何 Unknown tap 只回 `UNKNOWN_REFRESH`（先重新整理）＋disarm，零 store 觸碰；Corrupt 走修復流程（見下）。Unknown 在權威重整回 Known 之前**無法清除**；具體恢復路徑＝按「重新整理草稿」（Main／Editor 的 refresh 入口）或直接 `vm.refreshPolicyDraft()`，讀到 Known 後才重新走清除。
- Unknown 警告的「重新整理」（Retry）為權威重讀：`refreshPolicyDraftForWarningRetry` 呼叫並 **await** `refreshPolicyDraft()` 的一次 settled 回調；只有結算為 Known 才釋出凍結並重進新警告（R6-1 改為 `WarningRetryOneShot`：Retry 點時 capture token，**每次安裝新警告時重置（含自動重入 re-entry）**，故同一動作中的第二輪重進仍可進行；遲到／重複 settle 因 generation 已變或 token 已 consume 而被忽略）；仍 Unknown 顯示 `policy_refresh_failed`、Corrupt 顯示 `policy_corrupt_detected` 的非遞迴引導（Toast），不解凍重入、不自動再彈對話框，故無無限模態迴圈。
- 「重新整理草稿」為唯讀入口：只重讀遠端草稿，不觸發寫入；服務未連線或讀取失敗時顯示 Unavailable（保留舊草稿，不謊稱空、不拿 stale 快照當授權依據）；損壞（CCE）時顯示 `policy_corrupt_detected`（保留舊快照）。
- 寫入／清除失敗（`FAILED`）即標 Unavailable 並 fence 在途載入：晚到的舊 Known 不得蓋回 Unavailable（見 `DualViewModel.markPolicyUnknownLocked`），須重整後再試；失敗不自動重送。
- 實際路徑矩陣（δ＝old−candidate 僅在 Known 時權威；VM 對每個非 null consent 都會做權威 Known 預讀並重算 δ，consent.delta 與重算值不符即零寫入中止，見 F04）：

  | 快照 | δ | ROOT 可用（probe 可執行） | ROOT 不可用／probe Failed |
  | --- | --- | --- | --- |
  | Known | ∅（無移除：首次／增量新增或空草稿） | 不依賴探測結果／不顯示探測警告（仍唯讀重探作觀測）；建 no-removal consent → VM 兩階段確認 → 寫入／清除；不需預先存在雙開 profile | 同左（不依賴 ROOT） |
  | Known | ≠∅，probe Ok(∅) | 不警告，直接進 VM（consent 綁定真實空 installed） | 探測無法取得 Ok ⇒ Failed 對話框：重試／取消，零寫入 |
  | Known | ≠∅，probe Ok(non-empty) | 列出已安裝分身＋勾選確認；確認後寫入／清除，ROOT 可選事後解除安裝（需再驗證） | 無 ROOT 事後解除安裝入口；警告仍須勾選確認 |
  | Known | ≠∅，probe Failed | 失敗對話框：重試／取消；無 force-proceed、零 consent／pending | 同左（fail-closed） |
  | Unknown（Unavailable） | 不權威（任何值） | 清除 tap → `UNKNOWN_REFRESH`、零 store touch；Retry → 權威 refresh，Known 才重進一次新警告，仍 Unknown → 非遞迴引導 | 同左（與 ROOT 無關；盲清已停用） |
  | Corrupt | 不權威（任何值） | 僅修復兩步確認（best-effort 備份＋remove＋post-read Known(empty)），非 Known 一律 fail-closed | 同左（與 ROOT 無關） |

- 損壞修復（Corrupt 專用；盲清已停用，修復是損壞草稿的唯一路徑）：`requestRepairCorruptDraft` 第一次 tap 僅告警並 arm（`policy_corrupt_repair_first`：備份僅 best-effort、可能失敗、無法保證可恢復），`confirmRepairCorruptDraft` 第二次 tap 先判 TTL（逾 5 分鐘→重 arm＋`policy_blind_clear_expired` 複用）再判世代（失配→重 arm），兩者皆過才跳過 Known pre-read、直調 `store.repairCorrupt()`（best-effort 備份至固定鍵 `dual_policy.bak`（覆寫；舊 `dual_policy.bak.<ts>` 鍵遷移清除——多鍵時分批至多 512 個/次、至多殘留 1 個新鍵；備份失敗不擋修復）——`prefs.all` 或型別錯亂值皆可能拋出，備份可能失敗、無法保證可恢復——＋`remove(KEY).commit()`＋post-read 驗證 Known(empty)；永不 auto-called）。成功即 `policy_corrupt_repaired`（已嘗試備份，備份不保證成功）＋Known(empty)＋雙 disarm＋版本 bump；失敗（含 `ServiceUnavailable`）即 `policy_corrupt_repair_failed`＋滯留 Corrupt（FAILED，保留舊快照）。`clearPolicyDraft` 在 Corrupt 與任何 Unknown 下皆零 store 觸碰，導向修復／重整流程；R5-0 後不存在任何盲清路徑。
- 取消語義：`QUEUED` 可取消（清 pending 回 `IDLE`）；`SAVING`／`RECONCILING` 不可取消（no-op，pending 保留；核對中請勿重試；reconciler 終態正常回 `IDLE`，唯有界讀耗盡／總預算超限滯留 `RECONCILING` 待 `refreshPolicyDraft` late-join 恢復，見下表）。
- Tx 釋放條件（Stage G1 `decidePolicyTxSettle`，reconciler／refresh late-join／auto-recheck 共用唯一真相）：可 settle IFF deferred 已完成＋權威讀為預期→SUCCESS（`WRITE_SUCCESS`／`CLEAR_SUCCESS`＋Known）；deferred 已證失敗（await 回 false／拋出）→FAILED＋Unknown（樂觀讀不得翻案）；deferred 完成＋權威讀非預期→mismatch `REFRESHED`（ground truth）；其餘一律 Unresolved（保留 fence＋`RECONCILING`＋pending）。快徑 read-after-ack 維持既有信 ack 語義（Unknown 即信 ack 發布 SUCCESS），不經此 helper（保版本守衛既有測試語義）。
- Known(A)/Known(B) inconclusive：deferred 未完成時任何 Known（A 或 B）都不 settle 續留；Known(B)（讀到值==sanitized）永 inconclusive（刪末輪特例）；Known(A)（讀到值!=sanitized）視 stale 續留，不得 mismatch-settle（防 stale／樂觀讀早熟）。
- refresh 世代分離（freshness vs ownership）：`policyFreshnessGen` 管顯示 TOCTOU（refresh/load 重疊丟棄；VM 內部歷史 arm 欄位仍引用此世代，但盲清已停用、UI 不可達），`policyOwnershipGen` 管 Tx 所有權（reconciler／late-join 結清判定只比 version＋ownership）；Tx 存活期 `refreshPolicyDraft` 期中讓位（yielding）：可更新顯示但 Known=false 或維持 Unknown，不碰 phase/pending、不 disarm、不推進 ownership，故 yielding 永不致 TOCTOU 丟棄 reconciler（保留 fence）；`markPolicyUnknownLocked` 同步 bump 三者（version＋freshness＋ownership）。
- auto-recheck（Stage G2 P2#4）：handoverTx 登記時掛 `deferred.invokeOnCompletion` 單次權威重查（`boundedReconcileRead` 單次，`withTimeout(policyIoTimeoutMs)`，跑在 coordinator 的 app-scoped 域＋`Dispatchers.IO` 共用 IO 池；`boundedReconcileRead` 為容量有界讀取 wrapper，非獨立 pool，不耗 reconciler 三讀預算）→走 settle helper；讀 Unknown 保持 unresolved＋發布 `UNRESOLVED_REFRESH` 動作（`policy_unresolved_refresh`，按鈕即「重新整理草稿」）；終態（Success/Mismatch/Failed）發布前先經 coordinator settle-CAS 先行、勝者才發布，unresolved 不搶發布權。terminal-publish arbitration（Stage P2）= Tx-scoped adoption-epoch fence：busy-waiter 與 async-init 認領（提交 mutex 內 bump）與 sync-init 認領（經 launchReclaimAsync 於 commitMutex 內線性化，與 busy-waiter、async-init 同臂；init 的 tryLock 快照僅為建議性觀察，該臂細節見 DualViewModel.kt sync-init 註記）bump 共享 `PolicyTx.adoptionEpoch`；attach 時快照；claim 時在同一 mutex 內比對，fence 失敗即 EPOCH_STALE（不消耗 claim、不 bump rev、不發布）；讀取橫跨認領的 worker 保留（retain）並讓位給 adopter 收斂。舊 worker 在認領 bump 前於 commitMutex 內線性化（linearization point = claim CAS inside commitMutex）屬合法勝出，與 mutex 到達順序一致；adopter 終態為快照，不保證新鮮度。
- coordinator 認領（Stage G2 P2#5）：`PolicyTxCoordinator` App-scoped（`GalaxyApp` 單例；`MainActivity` 經 applicationContext 取用，另有靜態 fallback＋測試重置入口），`activeTx` 為跨 VM 唯一真相；Tx 攜 pending（`PolicyTx.pending`）；handover 登記同步寫本地＋coordinator＋reconciling；`onCleared`/scope 取消刻意不清除（reconciler `catch CE` 設 settle=false 保留 Tx）；新 VM init 認領（繼承 pending＋`RECONCILING`＋Unknown＋`TIMEOUT` 訊息，啟動 reconciler join 同一 deferred；認領本體經 launchReclaimAsync 於 commitMutex 內線性化，晚到的 init load 因世代失配被丟棄）；save deferred 跑 coordinator `scope`（SupervisorJob＋IO）存活過 VM 重建；終態 `tryClaimCoordinatorPublish` CAS 仲裁防雙發布（勝者發布後 `settlePolicyTx` 雙清，敗者僅清本地＋reconciling 不發布；無 coordinator 本地單 VM 路徑維持既有 publish-then-settle）。
- unresolved 手動 refresh 恢復：reconciler 誠實 unresolved 退出（有界讀耗盡／總預算 `POLICY_RECONCILE_TOTAL_BUDGET_MS` 15s 超限，`REFRESH_FAILED`／`CHANNEL`＋Known=false＋pending 保留＋`RECONCILING` 續留，Tx 存活）後，`refreshPolicyDraft` 為唯一恢復路徑（late-join）：Tx 存活＋本次 Known＋deferred 已完成→結清（成功／mismatch／已證失敗 FAILED，樂觀讀不得翻案）；其餘 yielding 續留待下一次 refresh；全域 `refresh()`／`reconnect()`／`selectChannel`／`onForeground` 期中一律跳過（Tx 存活即併發丟棄，保留核對提示），僅 `refreshPolicyDraft` 期中讓位。

### 政策 TTL 對照（App 側 vs hook 側，時鐘皆為 monotonic，不受 wall-clock 回撥影響）

| TTL | 值 | 所在側 | 語義 |
| --- | --- | --- | --- |
| 寫入確認 `POLICY_CONFIRM_TTL_MS` | 5 分鐘 | App | dialog pending 逾時→清 pending＋`TTL_EXPIRED` 訊息，不碰 draft（零寫入） |
| blind-clear arm `BLIND_CLEAR_TTL_MS` | 5 分鐘（與上同值，別名） | App（VM 內部遺留常數） | **R5-0（F01）起正式停用**：VM 內部 arm／TTL 欄位仍存在，但 UI 不可達、無法啟用；不再有「第二次 tap 清除」路徑。Unknown 一律 fail-closed，必須先權威重整回 Known。Corrupt 修復複用此 TTL 做自身兩步確認，非盲清。 |
| store IO `POLICY_IO_TIMEOUT_MS` | 10 秒 | App | confirm／clear 持 `opMutex` 橫跨有界超時；超時轉 `RECONCILING`＋`CHANNEL`（Unknown fence，pending 保留），由 reconciler 背景核對（不自動重送）；核對中 cancel 為 no-op；終態正常回 `IDLE`，唯有界讀耗盡（3 次皆 Unknown 且曾逾時）／總預算超限（`POLICY_RECONCILE_TOTAL_BUDGET_MS` 15s）滯留 `RECONCILING`（`REFRESH_FAILED`／`CHANNEL`＋Known=false＋pending 保留），可由後續 `refreshPolicyDraft` late-join 結清回 `IDLE`（讀到 Known 且原 save 已完成：成功→Known＋成功訊息，失敗→`FAILED`＋Unknown）；核對中 `refresh()`／`reconnect()`／`selectChannel`／`onForeground` 的 probe＋refresh 一律跳過（保留核對提示），`refreshPolicyDraft` 期中讓位（覆寫 msg/draft 但不碰 phase/pending，reconciler 只比 version＋ownership，期中 yielding 只推 freshness 不致丟棄） |
| 快取 `EffectivePolicyCache.maxCacheAgeMs` | 60 秒 | hook | 60 秒僅為 hook 行程本地快取 TTL；到期重讀的是同一個永久快取的 `RemotePreferences` 實例，底層 `mMap` 可能仍陳舊（poll-only，無跨行程推送）。**不保證 60s 內或下一次 poll 看到新集合；重啟 hook 行程後才保證讀到新值**；版本鍵（Phase 3）為可觀測證據非推送替代，見 `docs/HOOK.md` §1.1，非 App 側 5 分鐘 |
| 失敗節流 `failureCooldownMs` | 5 秒 | hook | 遠端讀取失敗後查詢節流，不重試 reader（`last-known-good` 保留或回 seed，見 `docs/HOOK.md` §1.1） |
| BBC sticky last-good `BBC_STICKY_MAX_AGE_MS` | 30 分鐘 | hook | 讀取失敗時沿用最近成功集合的上限，超過即 fail-closed 回空；這是**有界降解視窗**，不是冷啟動刪除的結構性修正（DELETE 早於回調且回調非空，sticky 對該路徑無對照證據，見 `BBC_COLDSTART_DELETE_INCIDENT.md` §8.4）。清除草稿後亦可能因本地快取／sticky 在最多 30 分鐘內沿用舊集合，無即時停止保證。 |
| 單次 auto-recheck（Stage G2） | 1 次（`invokeOnCompletion` 單次；讀 `withTimeout(policyIoTimeoutMs)`，coordinator app-scoped 域＋`Dispatchers.IO` 共用 IO 池，`boundedReconcileRead` 容量有界讀取 wrapper，非獨立 pool） | App | deferred 完成即觸發單次權威重查（不耗 reconciler 三讀預算，走 settle helper）；Unknown→unresolved＋`UNRESOLVED_REFRESH` 待手動 refresh late-join |

## English Summary

- **Install / upgrade warning (v0.1.0-beta.3) — read this entire warning before following the Release link:**
  **Check which id you currently have**: if the old id `com.galaxydual.plus`, this is a clean install — no migration of old data, no coexistence with the new id, back up first and verify the backup is restorable; if already `io.github.samson910022.galaxydualplus` (including beta.2 → beta.3), this is a same-id, same-signer in-place upgrade — **do not uninstall**; Android ordinarily keeps that management App's data in place, but this is **not** a promise that Samsung clones or their data remain intact.
  Samsung may automatically delete clones and permanently erase their data; ordinary existing dual apps **do not need to be manually removed** for this package-id migration — this is **not** a claim that they are unaffected, and this App provides **no deletion guard**.
  Release applicationId `io.github.samson910022.galaxydualplus`, locally built debug APK `io.github.samson910022.galaxydualplus.debug` — a different package, and its applicationId does not match the `module.prop` module id `io.github.samson910022.galaxydualplus` (known limitation, not guaranteed to load correctly in LSPosed); no migration from the old id `com.galaxydual.plus` (preferences / policy draft / risk ACK are not carried over), no built-in import/export. Back up first and verify the backup is restorable. Do **not** enable the legacy management App / LSPosed module together with the new one (ordinary dual apps need not be manually removed for this migration, and data is not merged). Disabling, uninstalling, opting out or rebooting are **not** a deletion guard; the beta.3 hook may act before the first in-app warning. BBC available-list injection is experimental / not device-verified on this version; as of v0.1.0-beta.3 (`fc579de3`) there is no pre-activation opt-in (after PR #90 merges, `docs/HOOK.md` §1.4 / the ADR are authoritative).
  **The published Release page still has the old identity / A/B/C wording; until the maintainer syncs it, rely on this warning and the repo's `docs/RELEASE_NOTES_beta3.md`.** After the sync, see [v0.1.0-beta.3 release notes](https://github.com/samson910022/Dual-your-galaxy/releases/tag/v0.1.0-beta.3).
- Privilege routing (tested pure function + wired channels [DEVICE-UNVERIFIED]): manual `ChannelPreference` picker (AUTO default, `selectChannel` swaps gateway + re-probes; `channelReadyFor` pure gate, tested: ROOT needs su probe, hook signal never grants, NONE never ready), su ROOT via `SuCmdGateway` (`su -c`), Shizuku (SHELL) via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5)
  fallback, else read-only (NONE) with `NoOp` fail-closed. LSPosed mode is an explicit NoOp placeholder (writes refused); hook state comes from the F4b-P2 per-scope file marker (display-only, `HookStateProvider` direct + gateway-`cat` reads); marker gateway-`cat` display, Shizuku unauthorized/dead-guidance paths, first-time SHIZUKU selection are unverified (`pm path` is auxiliary only, never ground truth, see #22); BBC callback logs and non-empty policy are device-verified on 2026-11-17/18 builds; experimental / not device-verified on the v0.1.0-beta.3 release source (S23 Ultra + Tab S7, 2026-11-17, **pre-unification build** — see `docs/ACCEPTANCE.md` §6; 2026-11-18 unified build: partially re-verified (BBC1–BBC3 positive only); BBC4/BBC5 UNVERIFIED (see `docs/ACCEPTANCE.md` §6.0) — BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）; BBC6 destroyed the dual app (see BBC6_SSAID_INCIDENT.md)); in-app ROOT one-click, authorized-SHIZUKU one-click (installed-verify), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24.
- Pair Shizuku via Wireless debugging (Android 11+, unverified flow), approve Dual your Galaxy, then Reconnect-and-refresh (single entry `reconnectAndRefresh()`: probe with Auto degrade + refresh; title syncs to `(SHELL/SHIZUKU)` only when the Shizuku probe passes, otherwise stays read-only NONE — switching itself is DEVICE-UNVERIFIED).
- Error-first (`combineWithDegradeNote`): refresh results + Auto degrade note with error priority — `CHANNEL`/`FAILED`/`NO_DUAL`/`VERIFY`/`INVALID` keep their kind (never downgraded to `INFO`) with the degrade reason appended (`;` join, recovery entry included); probe-pass ≠ PM-readable (`anyProbePassed` is **NOT** proof of PM readability; true readability = `pmReadable == true` + `writeReady`, fail-closed `CHANNEL` refusal when PM unreadable).
- Clone with `pm install-existing --user <dualId> <pkg>`; verify with `pm list packages --user` ground truth (not `pm path` alone);
  remove with `pm uninstall --user`. Never touches Secure Folder (Knox).
  `core` encapsulation plus gateway assembly are unit-tested; in-app ROOT one-click `cloned:`, authorized-SHIZUKU one-click (installed-verify single), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24; system hook single-method (getAllWhitelistedPackages) deoptimize+hooked (registration only), isInstalledWhitelistedPackage* not hooked (see HOOK §1.4), daagent `using provider.WhiteListApps` hits (category selection only), and marker main-path scope=system_server (historical, ~5.5h before this boot) verified as historical evidence only; callback logs (BBC path device-verified on 2026-11-17/18 builds; experimental / not device-verified on the v0.1.0-beta.3 release source — pre-unification build 2026-11-17), Shizuku unauthorized flows, first-time selection, binder dead-guidance, gateway-`cat` display, LSPosed writes remain DEVICE-UNVERIFIED; BBC injector verified end-to-end only on the 2026-11-17 pre-unification S23 historical observation; 2026-11-18 unified build: partial re-verification (BBC1–BBC3 positive only); BBC4/BBC5 UNVERIFIED; beta.3 experimental / not device-verified on this version (S23 Ultra + Tab S7, 2026-11-17 — see below) (`pm path` auxiliary only, see #22); Stage-1 whitelist queries stay sealed (`effective()` fail-closed to empty, zero behavior change by design); outside the specified normal SHELL clone/remove + manual channel history (see UX_REVIEW_S24.md §5), full binder/auth matrix, end-to-end matrix, and traceable build of current source remain unverified.
- Hook BBC injector was device-verified on 2026-11-17/18 builds; experimental / not device-verified on the v0.1.0-beta.3 release source (2026-11-17, **pre-unification build**: S23 Ultra One UI 9 → Dual Messenger page listed ChatGPT and it dual-launched as u95_a431; Tab S7 One UI 5 → Firefox listed toggleable (screenshot `10_dualmessenger_page.png`); dual install observed in session but **not persisted to file — no u95_* process evidence**); **2026-11-18 unified build on S23 Ultra: partially re-verified (BBC1–BBC3 positive only); BBC4/BBC5 UNVERIFIED (see `docs/ACCEPTANCE.md` §6.0) — BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）; BBC6 destroys the dual app** (evidence: `s23_bbc_unified_20261118.log`; screenshots `s23u_02_bbc3.png` / `s23u_03_bbc6_cleared.png` / `s23u_04_bbc6_restored.png`). The unified tree reads the shared `dual_policy/extra_whitelist_packages` draft via `EffectivePolicyCache` (60s TTL poll, no cross-process listener push on API 102), gates BBC only with `BBC_AVAILABLE_APPS_INJECTION=true` (Stage-1 gate stays sealed `false`). It does not extend
  any package by default (policy empty ⇒ BBC returns Samsung's original object); see `docs/HOOK.md`.
- GitHub sideload builds only; no Play release. Manifest audit per #26 done:
  `REQUEST_INSTALL_PACKAGES`/`MANAGE_EXTERNAL_STORAGE` removed (no call chain);
  only `QUERY_ALL_PACKAGES` kept (source catalog enumeration).
