<!-- MIT License - Original docs by Galaxy-Dual-app-Plus contributors. No GPL content. -->
# 使用教學 (USAGE)

> 本專案僅經由 GitHub 發佈 sideload APK，不上架 Play 商店。
> Manifest 目前宣告全檔案存取等權限，但尚未找到對應 APK staging 實作；依 #26 核對必要性。不上 Play 不是保留過寬權限的充分理由。
> 全程不碰 Secure Folder（Knox 容器）。
> 本文件描述包含設計目標與未完成流程；凡 [待實作]／[待實機驗證] 皆不宣稱為可用功能，
> 驗證須依個別情境判讀，不能由正常成功案例推論所有通道與安全負向邊界。歷史 S24 寫入紀錄與本輪唯讀調查見下方更新。

> **2026-11-05 更新**：[S24 調查與追蹤計畫](UX_REVIEW_S24.md) 彙整既有 #6/#9 的正常 SHELL clone/remove、手動通道證據，以及新發現與 issues #21–#29。本文較舊 F4a 的「無實機／Factory 靜態選路」敘述屬歷史階段，不代表手動重選仍未實作；未完成項目仍不得宣稱可用。

## 0. 狀態標示（與 README 一致）

- [已支援] profile 基本防護（排除 user0、拒絕候選歧義；完整平台身分待 #21）、`pm` 命令封裝
 （`DualRepository.cloneVerified / removeVerified`＋二次確認）、lint／CI 閘門。
- [部分已驗證] App 內一鍵 clone 真通道＋手動 picker（F4a 已接線＋picker＋marker：`MainActivity` 四通道按鈕＋Hook marker 行＋`StateFlow`，`DualViewModel.selectChannel/clone/refresh`；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），選不到回 `NoOp`＋唯讀 fail-closed，`channelReady` gating（`channelReadyFor` [已支援]：hook 訊號永不授權，NONE 永不就緒）。[S24 已驗證]僅限 ROOT 一鍵 `cloned:`、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、`pm uninstall --user 95` 回滾＋`su -c` 重裝；寫後主驗證以 listing 為準（`pm path` 僅輔助診斷，見 #22）；Shizuku 未授權／binder 死引導、App 內 Shizuku 首次選路、marker gateway-`cat` 顯示行、callback 可驗日誌、非空政策生效、當前 source 可追溯部署仍 [待實機驗證]；指定正常 SHELL clone/remove＋手動通道歷史見 [S24 調查](UX_REVIEW_S24.md) §5，指定範圍外的 binder round-trip、授權四情境、su 實機行為、端到端 pm、LSPosed 啟用偵測及當前 source 可追溯部署仍待驗）。
- [待實作] 非空 whitelist policy（預設為空，hook 為 observe-only）。
- [部分已驗證] LSPosed 載入（system hook 雙方法 deoptimize＋hooked [S24 已驗證]、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取） [S24 已驗證]、marker 主路徑 scope=system_server（早於本次開機約5.5h，不能宣稱本次開機有效） [S24 已驗證]，其餘 LSPosed 寫入、非空政策 [待實機驗證]）、Shizuku（已授權一鍵已驗；未授權路徑／首次選路／binder 死引導待驗；含重啟後重啟動）、
  雙開實機（`install-existing / path / uninstall --user` 端到端；其中
  `install-existing --user 95`＋`pm list packages --user 95` 二次確認
  [S24 已驗證]：裸 pm shell 通道兩次獨立成功）。

Hook 誠實聲明（`docs/HOOK.md` 第 1.1 節，本文引用不再重述為已生效）：
hook 政策集合預設為空，hooks 為 observe-only 佔位——會安裝、會記 log，
但不改變任何行為、不放寬任何包名；接上政策來源前，本文凡提及「hook 通道／白名單放寬」
皆指設計意圖，不是現有效果。

## 1. 提權優先順序（`core` 純函數 [已支援]＋真通道已接線（ROOT／SHIZUKU 已授權一鍵已抽驗 [S24 已驗證]，其餘 [待實機驗證]）

路由純函數（[已支援]）依序判斷；開機／開 App 探測框架（`probeChannels`／`recheckPrivilege`／`selectChannel`＋Shizuku 生命週期）已接真 gateway（ROOT→`SuCmdGateway` 經 `su -c`；SHELL→`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；hook 訊號為 file marker 存在性證據，僅顯示、永不進入路由，[待實機驗證]；`ChannelPreference` 手動選擇 AUTO 預設，`selectChannel` 動態換 gateway；選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`，`channelReadyFor` [已支援]），
以下順序為接線後預期行為，App 內 Shizuku 切換（未授權路徑／首次選路／marker gateway-`cat` 顯示 [待實機驗證]；已授權一鍵、ROOT 一鍵／su／uninstall 已驗，見 §0）：

1. **LSPosed 模式（NoOp 佔位）**：App 內 picker 選 LSPosed → 顯示 file marker 狀態（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，[待實機驗證]），執行通道為 NoOp 佔位、寫入一律拒絕；hook 為
   observe-only（見上文引用；App 內 ROOT 一鍵 [S24 已驗證]，marker gateway-`cat` 顯示未驗），不可描述為已能擴展包名。
2. **Shizuku 降級（SHELL）**：無 LSPosed 但 Shizuku binder 可達 → `Privilege.SHELL`
   - 走 `pm` 指令通道（`install-existing` / `uninstall --user` / `path --user`），F4a 的 `ShizukuCmdGateway` 經 UserService `GalaxyExecService` 已接線（`newProcess` 在 13.1.5 已移除故不用；Factory 在無 su 且 binder alive＋已授權時選擇，`ShizukuElevator` 探測通過才 `channelReady`）；
     binder round-trip、未授權路徑／首次選路／binder 死引導 [待實機驗證] DEVICE-UNVERIFIED。
3. **NONE（唯讀）**：選不到通道 → 不執行任何寫入，只顯示狀態（[已支援]：`NoOpGateway` 拒寫＋`channelReady` false fail-closed；不再恆 `false`——所選真通道探測通過即為 `true`）。

```kotlin
// Pure routing logic, no side effects (see core/Privilege.kt).
// App 端現狀（H4／#19）：hook 訊號永不進入路由（hasLsposed 恆 false：file
// marker 是存在性證據，不是 live hook 證明）；hasRoot/hasShizuku 由
// Su/ShizukuElevator 真通道探測填入。Hook 狀態走 UiState.hookActive／
// hookScopes（display-only）供 LSPosed picker 行顯示。
val privilege = routePrivilege(hasLsposed = false, hasShizuku = shizukuAlive, hasRoot = hasRoot)
val ready = channelReadyFor(gatewayPrivilege, hasRoot, shizukuAlive)
```

Clone／Remove／Refresh／請求 Shizuku 授權按鈕＋四通道選擇按鈕與 `StateFlow` 收集＋Shizuku binder／授權生命週期已接；探測跑在與所選 gateway 同實例的真通道 elevator 上，`channelReady` 僅所選通道探測通過才 `true`（NoOp 回退則 `false` fail-closed）。
目前 `selectChannel()` 已可在 mutex 內重建 gateway、探測與刷新，`recheckPrivilege()` 也會在 AUTO 環境變化時重新選擇；不是只有啟動時的靜態 Factory。`refresh()` 本身只更新清單，不重建 `channelReady`，因此暫態失敗後不保證按 Refresh 就恢復。Auto 降級與單一恢復入口由 #25 追蹤；LSPosed 仍為拒寫的 NoOp 佔位。顯示更新本身 [待實機驗證]。

- 併發邊界（#24；術語：busy 即 `UiState.loading`，merge 即 coalesce）：`refresh()` 快速連點合併為一次 trailing 重跑（coalesce，in-flight 之後最多一次待跑，不堆積 mutex 等待者）；`clone`／`remove` 按操作 key 去重，同 key 第二次報 `already in progress` 不排隊，且 clone 與 remove 分屬不同命名空間（`clone:<pkg>`／`remove:<pkg>`）不互斥；所有 repo 呼叫經 `Dispatchers.IO`（`onIo`），執行期間 busy（`UiState.loading=true`）。

## 2. Shizuku 配對教學（無線調試，Android 11+，流程 [待實機驗證]）

預期適用無 ROOT 裝置（三星 One UI 4.0+ / Android 12+ 為目標機型，未經實機驗證）。

1. 手機與電腦連上**同一個 Wi-Fi**。
2. 手機開啟**開發者選項**：
   - 設定 → 關於手機 → 軟體資訊 → 連點「版本號碼」7 次。
3. 設定 → 開發者選項 → 開啟**無線調試（Wireless debugging）**。
4. 安裝並開啟 **Shizuku**（GitHub 版），點選「經由無線調試啟動」→「配對」。
   - Shizuku 會顯示 6 位數配對碼；在系統跳出的「無線調試配對」對話框輸入配對碼。
   - 配對成功後回到 Shizuku 點「啟動」，看到版本號即表示 binder 已就緒。
5. 在 Shizuku 的「授權應用」中找到 **Galaxy Dual Plus** 並允許。
6. 回到本 App 點 `refresh()`；F4a 接線後，已授權 binder 下 Factory 選 `ShizukuCmdGateway` 且 `ShizukuElevator` 探測通過時，標題列預期從 `(NONE)` 變為 `(SHELL)`
   （選不到／探測未過則維持 `(NONE)` 唯讀 fail-closed；此切換 [待實機驗證] DEVICE-UNVERIFIED，不可視為已可用）。

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

## 3. 複製 / 移除流程（需雙源驗證，`--user <dualId>` 僅為格式佔位）

以下 `<dualId>` 僅為命令格式佔位，不是推薦目標，更不是「任意非零即雙開」。
寫入前必須先完成雙源驗證：`cmd user list -v` 與 `dumpsys user` 兩個獨立通道
在同一非零 id 上同時給出 `profile.CLONE` 型別（含 parent/serial 一致），
且系統 property（`getprop sys.dualapp.profile_id`，若可讀）不與之矛盾；
任一通道缺失、未知、衝突或歧義（多 CLONE／型別打架／property 打架）一律拒寫、
拒讀，不猜「第一個非 0 的 id」、不認 label、不固定 95/150、不比整組 flags。
App 內 `DualRepository`／雙 Elevator 探測即执行此契約（[已支援] PR-1 階段2）：
`resolveDualUserIdVerified(userListVerbose, dumpsysUser, propertyId)` 僅在
`SamsungProfileAdapter.resolveDual() == Confirmed(HIGH)` 時回 id，否則 -1；
explicit id 即使存在於 `pm list users`，只要不是當下 Confirmed 目標，
clone/remove/verify/list 一律 `refused:`／`Unknown`／`null` fail-closed。
`pm list users` 的名稱僅供顯示，永不進入授權判定；user 0 永遠不可作為寫入目標。
此次 S24 的 95 為 Dual、150 為 Secure Folder，這不是其他機型的固定對照。

```bash
# 1. 雙源驗證（缺一不可；任一失敗即停手，不寫入不探測目標）：
adb shell cmd user list -v
#   每個 UserInfo{<id>:<label>:<flags>} 塊內找 User type 行：
#   目標 id 的兩通道都必須是 android.os.usertype.profile.CLONE（大小寫/分隔符容忍），
#   且 parentId 一致；label 含不含 DUAL 都不看，serial != id 屬正常。
adb shell dumpsys user
#   同一 id 在此通道同樣必須是 profile.CLONE；若此處是 SYSTEM/MANAGED/SECONDARY
#   或無型別行，即與上一通道矛盾或證據不足 -> 拒寫。
adb shell getprop sys.dualapp.profile_id
#   若可讀，其值必須等於上述同一 id；不可讀/空/格式未知視為無主張（仍需雙通道一致才放行），
#   指向不存在 id 或與 CLONE id 不一致 -> 拒寫。

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
- 本 App 的 `DualRepository.clone/verify` 即為上述指令的程式化封裝（[已支援]：`core` 層＋單元測試＋JVM 組裝測試；App 真 gateway 與 readiness gating 已接線：`SuCmdGateway` 經 `su -c`／`ShizukuCmdGateway` 經 UserService＋`channelReady` 探測 gating＋選不到回 `NoOp` fail-closed；目標解析為 PR-1 階段2雙源驗證＋失敗拒寫，label-only 舊路徑已 `@Deprecated`，`verify(pkg,dualId)` Boolean 已 `@Deprecated` 請改用 `verifyState`）。寫後確認以相同已驗證目標的 package listing 為準，不再只用 `pm path`（`pm path` 僅輔助診斷，不能當主驗證），錯誤／截斷判定仍待 #22。[S24 已驗證]僅限 App ROOT 一鍵、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、裸 pm clone、`su -c` 重裝、uninstall 回滾，詳見 §0 與 [S24 調查](UX_REVIEW_S24.md) §5；App ROOT 之外的完整 binder／授權矩陣、App 內 Shizuku 未授權路徑／首次選路、marker gateway-`cat` 顯示及當前 source 可追溯部署仍 [待實機驗證]。
- 移除確認已知限制（PR-33）：
  - (a) 確認 TTL 5 分鐘走 monotonic 時鐘（`SystemClock.elapsedRealtime()`，免 wall-clock 回撥延長有效期；pending 不跨重啟，reboot 重置無影響；JVM 預設 `currentTimeMillis` 僅供測試注入）。
  - (b) 同名重裝限制：membership 僅保證確認時可見同名 package，不綁定安裝世代。
  - (c) shell 檢查→uninstall 非原子（裝置限制，repo 雙閘僅縮小窗口）。
  - (d) S24 實機 destructive／negative acceptance [S24 已驗證]（2026-11-05，SM-S9210，ROOT 通道）：身分雙 CLONE＋serial 95＋prop 95 確認、`Cloned (35)` 真實列表；可拋棄 overlay（`com.android.settings.supervision.overlay`，owner 有／95 無，先 `install-existing` 進 95）經 App 兩階段確認移除→`removed:`＋95 內消失＋owner 副本完好＋總數回 35（淨零）；negative（取消鍵、back 鍵）皆零卸載並保留正確訊息，截圖存檔；未安裝拒絕以 JVM 測試為準（實機訊息僅截圖存檔）。殘留：旋轉重建、TalkBack 朗讀僅 JVM／靜態證據。

## 4. 通道選擇與 LSPosed 模式（F4b-P1 picker＋P2 marker；ROOT／SHIZUKU 已授權一鍵已抽驗 [S24 已驗證]，其餘 [待實機驗證]）

- 已 ROOT + LSPosed 的裝置：到 LSPosed 管理器勾選 **Galaxy Dual Plus**，
  作用域勾 `system`＋`com.samsung.android.da.daagent`（見 `docs/HOOK.md` §1.4／§2.2）後重啟；再到 App 內按 LSPosed 按鈕切到 LSPosed 模式：Hook 行顯示 marker 狀態（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，P1 四態見本節末段；file marker 存在性證據，僅顯示、永不授權；daagent 側因 SELinux 寫入失敗故 marker 恆缺 daagent，見 `docs/HOOK.md` §1.4）；LSPosed 通道為 NoOp 佔位、寫入一律拒絕（whitelist 政策分支待實作）。marker gateway-`cat` 顯示 [待實機驗證]，無實機證據。
- AUTO 模式按 su→Shizuku→NoOp 解析執行通道（hook 永不被自動選中）；`channelReadyFor` 純函數 [已支援]（ROOT 需 su 探測、hook 訊號永不授權、NONE 永不就緒）；停用模組後下次啟動降級 Shizuku 為設計意圖，實機切換 [待實機驗證] DEVICE-UNVERIFIED。
- LSPosed 模組僅 hook 雙開白名單查詢（observe-only 佔位，不放寬任何包名，見 `docs/HOOK.md` 第 1.1 節），
  絕不讀寫 Knox 容器；白名單真正放寬需等非空政策接上（[待實作]）。
- P1 診斷限制（配合 [#27](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/27)）：Hook 狀態為四態——偵測到歷史 Hook 標記（僅存在性證據，未確認本次開機是否生效）／未偵測到／狀態未知（無權限讀取）／狀態未知（無可用診斷通道）。明選 LSPosed（NoOp）時診斷讀取獨立於所選寫入通道：仍嘗試直接讀取＋經當時已探測通過的真通道（ROOT／SHIZUKU）`cat`；若無任何真通道且直接讀取不可讀（App uid 對 `/data/system` 恆 EACCES），顯示「狀態未知」而非「未偵測到」——後者只保留給確定可讀但缺失的情況（fail-closed 誠實顯示，不再假陰性）。要完整診斷需至少一個探測通過的真通道；診斷結果永不進入路由／`channelReady`（hook-neutral，沿襲 #19）。

## 5. 常見問題

| 症狀 | 原因 / 解法 |
| --- | --- |
| `(NONE)` 或「通道未就緒」 | 先區分所選 gateway、探測能力與 Shizuku binder／授權。未就緒時會拒寫，但 Refresh 本身不重新探測；重選模式或回到前景才會觸發相應重探，仍不保證 Auto 失敗降級。單一恢復流程由 #25 追蹤，不能以標題權限代替實際通道狀態。 |
| `no dual profile` | 可能是未建立、候選歧義或輸出無法解析，不能一律解讀為尚未建立。先唯讀核對平台身分；不確定即停止寫入，見 #21/#27 |
| `Failure [INSTALL_FAILED_...]` | 來源 APK 不存在或儲存空間不足；先確認主 profile 已安裝該 App |
| Play 審查相關警告 | 本專案不發 Play 版，請一律使用 GitHub Release 的 APK |

## English Summary

- Privilege routing (tested pure function + wired channels [DEVICE-UNVERIFIED]): manual `ChannelPreference` picker (AUTO default, `selectChannel` swaps gateway + re-probes; `channelReadyFor` pure gate, tested: ROOT needs su probe, hook signal never grants, NONE never ready), su ROOT via `SuCmdGateway` (`su -c`), Shizuku (SHELL) via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5)
  fallback, else read-only (NONE) with `NoOp` fail-closed. LSPosed mode is an explicit NoOp placeholder (writes refused); hook state comes from the F4b-P2 file marker (display-only, `HookStateProvider` direct + gateway-`cat` reads); marker gateway-`cat` display, Shizuku unauthorized/dead-guidance paths, first-time SHIZUKU selection, callback logs, and non-empty policy are unverified (`pm path` is auxiliary only, never ground truth, see #22); in-app ROOT one-click, authorized-SHIZUKU one-click (installed-verify), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24.
- Pair Shizuku via Wireless debugging (Android 11+, unverified flow), approve Galaxy Dual Plus, then refresh (status switches only when the Shizuku probe passes; otherwise stays read-only NONE — switching itself is DEVICE-UNVERIFIED).
- Clone with `pm install-existing --user <dualId> <pkg>`; verify with `pm list packages --user` ground truth (not `pm path` alone);
  remove with `pm uninstall --user`. Never touches Secure Folder (Knox).
  `core` encapsulation plus gateway assembly are unit-tested; in-app ROOT one-click `cloned:`, authorized-SHIZUKU one-click (installed-verify single), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24; system hook dual-method deoptimize+hooked (registration only), daagent `using provider.WhiteListApps` hits (category selection only), and marker main-path scope=system_server (historical, ~5.5h before this boot) verified as historical evidence only; callback logs, Shizuku unauthorized flows, first-time selection, binder dead-guidance, gateway-`cat` display, LSPosed writes, and non-empty policy remain DEVICE-UNVERIFIED (`pm path` auxiliary only, see #22); outside the specified normal SHELL clone/remove + manual channel history (see UX_REVIEW_S24.md §5), full binder/auth matrix, end-to-end matrix, and traceable build of current source remain unverified.
- Hook is an observe-only placeholder (empty policy, see `docs/HOOK.md`); it does not extend
  any package yet.
- GitHub sideload builds only; no Play release. Manifest `MANAGE_EXTERNAL_STORAGE`/`REQUEST_INSTALL_PACKAGES` necessity is still under review per #26 — not shipping on Play does not justify overbroad permissions.
