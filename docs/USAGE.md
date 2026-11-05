<!-- MIT License - Original docs by Galaxy-Dual-app-Plus contributors. No GPL content. -->
# 使用教學 (USAGE)

> 本專案僅經由 GitHub 發佈 sideload APK，不上架 Play 商店。
> 因此 `MANAGE_EXTERNAL_STORAGE` 等權限僅用於 GitHub 版 APK 暫存，不做 Play 政策宣告。
> 全程不碰 Secure Folder（Knox 容器）。
> 本文件描述包含設計目標與未完成流程；凡 [待實作]／[待實機驗證] 皆不宣稱為可用功能，
> 未經三星實機驗證（與 README「實作狀態」標示體系一致）。

## 0. 狀態標示（與 README 一致）

- [已支援] profile 解析安全邊界（只認唯一 DUAL、永不選 user 0）、`pm` 命令封裝
 （`DualRepository.cloneVerified / removeVerified`＋二次確認）、lint／CI 閘門。
- [部分已驗證] App 內一鍵 clone 真通道＋手動 picker（F4a 已接線＋F4b-P1 picker＋P2 marker DEVICE-UNVERIFIED：`MainActivity` 已有 Clone／Remove／Refresh／請求 Shizuku 授權按鈕＋四通道選擇按鈕＋Hook marker 行＋`StateFlow` 收集，Shizuku binder／授權生命週期已接；
  `DualViewModel.clone/remove/refresh/recheckPrivilege/selectChannel` 邏輯存在；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），`channelReady` 改由各通道探測結果 gating（`channelReadyFor` [已支援] pure gate：ROOT 需 su 探測、hook 訊號永不授權，NONE 永不就緒），選不到仍維持 `NoOp`＋唯讀 fail-closed；hook 訊號為 file marker 存在性證據（`HookStateProvider`，僅顯示；`path` 主驗證、binder round-trip、授權四情境、su 實機行為、App 內一鍵端到端、marker round-trip 皆未驗；注：裸 `pm install-existing --user 95`＋list 二次確認 [S24 已驗證]，見下條）。
- [待實作] 非空 whitelist policy（預設為空，hook 為 observe-only）。
- [待實機驗證] LSPosed 載入、Shizuku 四情境（有／無 binder × 有／無授權，含重啟後重啟動）、
  雙開實機（`install-existing / path / uninstall --user` 端到端；其中
  `install-existing --user 95`＋`pm list packages --user 95` 二次確認
  [S24 已驗證]：ChatGPT `com.openai.chatgpt`，shell 通道，兩次獨立成功）。

Hook 誠實聲明（`docs/HOOK.md` 第 1.1 節，本文引用不再重述為已生效）：
hook 政策集合預設為空，hooks 為 observe-only 佔位——會安裝、會記 log，
但不改變任何行為、不放寬任何包名；接上政策來源前，本文凡提及「hook 通道／白名單放寬」
皆指設計意圖，不是現有效果。

## 1. 提權優先順序（`core` 純函數 [已支援]＋真通道 F4a 已接線 [待實機驗證] DEVICE-UNVERIFIED）

路由純函數（[已支援]）依序判斷；開機／開 App 探測框架（`probeChannels`／`recheckPrivilege`／`selectChannel`＋Shizuku 生命週期）已接真 gateway（ROOT→`SuCmdGateway` 經 `su -c`；SHELL→`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；hook 訊號為 file marker 存在性證據，僅顯示、永不進入路由，[待實機驗證]；`ChannelPreference` 手動選擇 AUTO 預設，`selectChannel` 動態換 gateway；選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`，`channelReadyFor` [已支援]），
以下順序為接線後預期行為，全部切換 [待實機驗證]（binder round-trip、授權四情境、su 實機行為、marker round-trip 皆未驗）：

1. **LSPosed 模式（NoOp 佔位）**：App 內 picker 選 LSPosed → 顯示 file marker 狀態（`已啟用（system_server, daagent）`／`未偵測到`，[待實機驗證]），執行通道為 NoOp 佔位、寫入一律拒絕；hook 為
   observe-only（見上文引用），marker round-trip＋su 實機行為 [待實機驗證] DEVICE-UNVERIFIED，不可描述為已能擴展包名。
2. **Shizuku 降級（SHELL）**：無 LSPosed 但 Shizuku binder 可達 → `Privilege.SHELL`
   - 走 `pm` 指令通道（`install-existing` / `uninstall --user` / `path --user`），F4a 的 `ShizukuCmdGateway` 經 UserService `GalaxyExecService` 已接線（`newProcess` 在 13.1.5 已移除故不用；Factory 在無 su 且 binder alive＋已授權時選擇，`ShizukuElevator` 探測通過才 `channelReady`）；
     binder round-trip、四情境 [待實機驗證] DEVICE-UNVERIFIED。
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
`selectChannel()` 可切換通道（persist＋重建 gateway＋重探＋refresh）；AUTO 下 `refresh()`／`recheckPrivilege()` 重探並更新顯示（privilege／`hookActive`／`hookScopes`／`channelReady`）——顯示更新本身 [待實機驗證] DEVICE-UNVERIFIED。

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

舊裝置（Android 10 以下無無線調試）改用 USB：

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

## 3. 複製 / 移除流程（`adb install --user 95` 範例）

以下 `95` 為雙開 profile 的 userId 範例；實際請以 App 內 `refresh()` 顯示的
`UserInfo{...}` / `dualUserId` 為準（常見為 95 / 150，機型而異）
（[已支援]：`core` 的 `resolveDualUserId` 解析＋拒寫邊界有單元測試；[待實機驗證]：App 內 `refresh()` 顯示尚未經三星實機驗證）。

```bash
# 1. 列出所有 profile，確認雙開 userId：只認 DUAL 字樣，且絕對不可選 user 0。
#    若沒有 DUAL 字樣、或出現多個 DUAL（無法唯一確定），請停手：
#    先到系統設定開啟 Dual Messenger 任一 App 再重列，切勿猜「第一個非 0 的 id」
#    （那可能是 Work profile 或其他無關使用者，寫錯目標會動到別人的資料）。
adb shell pm list users
#   Users:
#     UserInfo{0:Owner:13} running
#     UserInfo{95:DUAL_APP:400} running
#   上例唯一 DUAL 為 95；user 0（Owner）永遠不可作為 --user 目標，
#   本 App 的 resolveDualUserId / clone / remove 遇到上述情況會直接拒絕寫入。

# 2. 將已安裝的 App 複製進雙開 profile（不會動到 Secure Folder）
adb shell pm install-existing --user 95 com.example.chat

# 3. 二次確認是否真的裝進去了
adb shell pm path --user 95 com.example.chat
#   package:/data/app/.../base.apk  -> 表示成功

# 4. 只移除雙開分身，不動主 profile（user 0）
adb shell pm uninstall --user 95 com.example.chat
```

- `install-existing` 僅對已存在於主 profile 的 package 有效；全新 APK 請先在主 profile 正常安裝一次。
- 指令失敗時請看 `msg` 欄（`Failure` / `Unknown package` / `not installed` 皆視為失敗）。
- 本 App 的 `DualRepository.clone/remove/verify` 即為上述三指令的程式化封裝（[已支援]：`core` 層＋單元測試；
  App 內一鍵 F4a 已接線（`SuCmdGateway` 經 `su -c`／`ShizukuCmdGateway` 經 UserService＋`channelReady` 探測 gating＋選不到回 `NoOp` fail-closed，JVM 組裝測試覆蓋）但 [待實機驗證] DEVICE-UNVERIFIED：binder round-trip、授權四情境、su 實機行為、端到端 pm 皆未驗）。

## 4. 通道選擇與 LSPosed 模式（F4b-P1 picker＋P2 marker，[待實機驗證] DEVICE-UNVERIFIED）

- 已 ROOT + LSPosed 的裝置：到 LSPosed 管理器勾選 **Galaxy Dual Plus**，
  作用域勾 `system`＋`com.samsung.android.da.daagent`（見 `docs/HOOK.md` §1.4／§2.2）後重啟；再到 App 內按 LSPosed 按鈕切到 LSPosed 模式：Hook 行顯示 marker 狀態（`已啟用（system_server, daagent）`／`未偵測到`，file marker 存在性證據，僅顯示、永不授權）；LSPosed 通道為 NoOp 佔位、寫入一律拒絕（whitelist 政策分支待實作）。marker round-trip＋su 實機行為 [待實機驗證]，無實機證據。
- AUTO 模式按 su→Shizuku→NoOp 解析執行通道（hook 永不被自動選中）；`channelReadyFor` 純函數 [已支援]（ROOT 需 su 探測、hook 訊號永不授權、NONE 永不就緒）；停用模組後下次啟動降級 Shizuku 為設計意圖，實機切換 [待實機驗證] DEVICE-UNVERIFIED。
- LSPosed 模組僅 hook 雙開白名單查詢（observe-only 佔位，不放寬任何包名，見 `docs/HOOK.md` 第 1.1 節），
  絕不讀寫 Knox 容器；白名單真正放寬需等非空政策接上（[待實作]）。

## 5. 常見問題

| 症狀 | 原因 / 解法 |
| --- | --- |
| 標題列顯示 `(NONE)`（F4a 已接線，[待實機驗證]） | `(NONE)` 僅表示所選通道探測未通過（NoOp 回退／su 缺失／binder 未連線或未授權／探測失敗，fail-closed 唯讀）；重做第 2 節裝置設定／授權後 `refresh()`／`recheckPrivilege()` 會重探，真通道探測通過即切換——但切換本身 DEVICE-UNVERIFIED，未經實機驗證 |
| `no dual profile` | 三星尚未建立雙開 profile；先到系統設定開啟 Dual Messenger 任一 App |
| `Failure [INSTALL_FAILED_...]` | 來源 APK 不存在或儲存空間不足；先確認主 profile 已安裝該 App |
| Play 審查相關警告 | 本專案不發 Play 版，請一律使用 GitHub Release 的 APK |

## English Summary

- Privilege routing (tested pure function + wired channels [DEVICE-UNVERIFIED]): manual `ChannelPreference` picker (AUTO default, `selectChannel` swaps gateway + re-probes; `channelReadyFor` pure gate, tested: ROOT needs su probe, hook signal never grants, NONE never ready), su ROOT via `SuCmdGateway` (`su -c`), Shizuku (SHELL) via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5)
  fallback, else read-only (NONE) with `NoOp` fail-closed. LSPosed mode is an explicit NoOp placeholder (writes refused); hook state comes from the F4b-P2 file marker (display-only, `HookStateProvider` direct + gateway-`cat` reads); marker round-trip, binder round-trip, all four auth scenarios, su behavior, and end-to-end pm are unverified.
- Pair Shizuku via Wireless debugging (Android 11+, unverified flow), approve Galaxy Dual Plus, then refresh (status switches only when the Shizuku probe passes; otherwise stays read-only NONE — switching itself is DEVICE-UNVERIFIED).
- Clone with `pm install-existing --user <dualId> <pkg>`; verify with `pm path --user`;
  remove with `pm uninstall --user`. Never touches Secure Folder (Knox).
  `core` encapsulation plus gateway assembly are unit-tested; in-app one-click true channel is wired but DEVICE-UNVERIFIED; end-to-end is unverified.
- Hook is an observe-only placeholder (empty policy, see `docs/HOOK.md`); it does not extend
  any package yet.
- GitHub sideload builds only; no Play release, so `MANAGE_EXTERNAL_STORAGE` needs no Play filing.
