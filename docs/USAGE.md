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
- [待實機驗證] App 內一鍵 clone 真通道（F4a 已接線 DEVICE-UNVERIFIED：`MainActivity` 已有 Clone／Remove／Refresh／請求 Shizuku 授權按鈕＋`StateFlow` 收集，Shizuku binder／授權生命週期已接；
  `DualViewModel.clone/remove/refresh/recheckPrivilege` 邏輯存在；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），`channelReady` 改由各通道探測結果 gating，選不到仍維持 `NoOp`＋唯讀 fail-closed；`hasLsposed` 維持保守 `false`（F4b／後續）；指定正常 SHELL clone/remove 與手動通道的歷史證據見 [S24 調查](UX_REVIEW_S24.md) §5，指定範圍外的 binder round-trip、授權四情境、su 實機行為、端到端 pm、LSPosed 啟用偵測及當前 source 可追溯部署仍待驗）。
- [待實作] 非空 whitelist policy（預設為空，hook 為 observe-only）。
- [待實機驗證] LSPosed 載入、Shizuku 四情境（有／無 binder × 有／無授權，含重啟後重啟動）、
  雙開實機（`install-existing / list packages / uninstall --user` 端到端：指定正常 SHELL 情境有歷史實機紀錄，見 [S24 調查](UX_REVIEW_S24.md) §5；當前 source 可追溯部署與完整矩陣仍待驗）。

Hook 誠實聲明（`docs/HOOK.md` 第 1.1 節，本文引用不再重述為已生效）：
hook 政策集合預設為空，hooks 為 observe-only 佔位——會安裝、會記 log，
但不改變任何行為、不放寬任何包名；接上政策來源前，本文凡提及「hook 通道／白名單放寬」
皆指設計意圖，不是現有效果。

## 1. 提權優先順序（`core` 純函數 [已支援]＋真通道 F4a 已接線 [待實機驗證] DEVICE-UNVERIFIED）

路由純函數（[已支援]）依序判斷；開機／開 App 探測框架（`probeChannels`／`recheckPrivilege`＋Shizuku 生命週期）F4a 已接真 gateway（ROOT→`SuCmdGateway` 經 `su -c`、SHELL→`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；Factory 按探測選擇、選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`；`hasLsposed` 維持保守 `false`，F4b／後續），
以下順序為接線後預期行為，全部切換 [待實機驗證]（binder round-trip、授權四情境、su 實機行為、LSPosed 啟用偵測皆未驗）：

1. **LSPosed 優先（ROOT）**：偵測到本模組在 LSPosed 中已勾選並啟用 → `Privilege.ROOT`
   - 走 hook 通道（或 `su -c`，F4a 的 `SuCmdGateway` 經 `su -c` 已接線），目標是呼叫三星雙開服務；但現階段 `hasLsposed` 維持保守 `false`（跨進程啟用訊號需實機驗證，F4b／後續），故 hook 通道永不被選；hook 為
     observe-only（見上文引用），LSPosed 載入／啟用偵測＋su 實機行為 [待實機驗證] DEVICE-UNVERIFIED，不可描述為已能擴展包名。
2. **Shizuku 降級（SHELL）**：無 LSPosed 但 Shizuku binder 可達 → `Privilege.SHELL`
   - 走 `pm` 指令通道（`install-existing` / `uninstall --user` / `path --user`），F4a 的 `ShizukuCmdGateway` 經 UserService `GalaxyExecService` 已接線（`newProcess` 在 13.1.5 已移除故不用；Factory 在無 su 且 binder alive＋已授權時選擇，`ShizukuElevator` 探測通過才 `channelReady`）；
     binder round-trip、四情境 [待實機驗證] DEVICE-UNVERIFIED。
3. **NONE（唯讀）**：選不到通道 → 不執行任何寫入，只顯示狀態（[已支援]：`NoOpGateway` 拒寫＋`channelReady` false fail-closed；不再恆 `false`——所選真通道探測通過即為 `true`）。

```kotlin
// Pure routing logic, no side effects (see core/Privilege.kt).
// App 端現狀：hasLsposed 維持保守 false（跨進程啟用訊號需實機驗證，F4b／後續），hasRoot/hasShizuku 由 Su/ShizukuElevator 真通道探測填入。
val privilege = routePrivilege(hasLsposed = false, hasShizuku = shizukuAlive, hasRoot = hasRoot)
```

Clone／Remove／Refresh／請求 Shizuku 授權按鈕與 `StateFlow` 收集＋Shizuku binder／授權生命週期已接；F4a 後探測跑在與所選 gateway 同實例的真通道 elevator 上，`channelReady` 僅所選通道探測通過才 `true`（NoOp 回退則 `false` fail-closed）。
目前 `selectChannel()` 已可在 mutex 內重建 gateway、探測與刷新，`recheckPrivilege()` 也會在 AUTO 環境變化時重新選擇；不是只有啟動時的靜態 Factory。`refresh()` 本身只更新清單，不重建 `channelReady`，因此暫態失敗後不保證按 Refresh 就恢復。Auto 降級與單一恢復入口由 #25 追蹤；LSPosed 仍為拒寫的 NoOp 佔位。

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

以下 `95` 僅為命令格式範例，不是推薦目標。App 目前 `refresh()` 顯示的名稱／ID 不提供完整平台身分保證，必須獨立核對型別、parent/group、serial 與 Samsung 證據；不能憑名稱或固定數字。
此次 S24 的95為Dual、150為Secure Folder，這不是其他機型的固定對照。
基本owner／歧義防護有單元測試，但完整目標安全邊界仍由 [#21](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/21) 追蹤，不能把範例當成寫入授權。

```bash
# 1. 列出全域 user table 作為候選參考；名稱不是寫入授權證據。
#    必須另外核對支援版本的平台型別、parent/group、serial與Samsung身分證據。
#    無證據、解析未知或候選衝突時停手，不能猜「第一個非0的id」、95或150。
#    只有確認尚未建立Dual時，才考慮到三星設定建立；歧義／解析失敗不是同一原因。
adb shell pm list users
#   Users:
#     UserInfo{0:Owner:13} running
#     UserInfo{95:DUAL_APP:400} running
#   上例名稱不能單獨證明95是安全目標；user0永遠不可作為寫入目標。
#   目前名稱resolver與explicit-ID仍有#21追蹤的缺口，不把此範例視為安全保證。
#   後續寫入示例僅適用於已獨立確認目標、備份並明確同意操作的情境。

# 2. 將已安裝的 App 複製進雙開 profile（不會動到 Secure Folder）
adb shell pm install-existing --user 95 com.example.chat

# 3. 唯讀診斷輔助：查看套件路徑；不能只靠此命令當所有情境的成功證據。
adb shell pm path --user 95 com.example.chat
#   package:/data/app/.../base.apk  -> 表示成功

# 4. 只移除雙開分身，不動主 profile（user 0）
adb shell pm uninstall --user 95 com.example.chat
```

- `install-existing` 僅對已存在於主 profile 的 package 有效；全新 APK 請先在主 profile 正常安裝一次。
- 指令失敗時請看 `msg` 欄（`Failure` / `Unknown package` / `not installed` 皆視為失敗）。
- 上述為手動診斷／命令格式示例；本 App 現行 verified 流程以相同目標的 package listing 作寫後確認，不再只用 `pm path`，錯誤／截斷判定仍待 #22。命令封裝有 `core` 層單元測試；
  App 真 gateway 與 readiness gating 已接線，指定正常 SHELL 情境有歷史實機證據；App ROOT、完整 binder／授權矩陣及當前 source 的可追溯部署仍待驗證，詳見 [S24 調查](UX_REVIEW_S24.md)。

## 4. LSPosed 優先模式（F4a 已接線但 `hasLsposed` 保守 `false`，[待實機驗證] DEVICE-UNVERIFIED）

- 已 ROOT + LSPosed 的裝置：到 LSPosed 管理器勾選 **Galaxy Dual Plus**，
  作用域勾三星雙開相關進程後重啟；F4a 後 su ROOT 通道已接（su 檔存在即選 `SuCmdGateway`，`SuElevator` 以 `id -u`＝0 探測通過才 `channelReady`），但 `hasLsposed` 維持保守 `false`（跨進程啟用訊號需實機驗證，F4b／後續），故勾選模組不會切 hook 通道；LSPosed 載入／啟用偵測＋su 實機行為 [待實機驗證]，無實機證據。
- 同時有 LSPosed 與 Shizuku 時路由設計為永遠選 LSPosed（`routePrivilege` 純函數 [已支援]）；但 Factory 現階段永不選 hook 通道（同上 `hasLsposed = false`）；停用模組後下次啟動降級 Shizuku 為設計意圖，實機切換 [待實機驗證] DEVICE-UNVERIFIED。
- LSPosed 模組僅 hook 雙開白名單查詢（observe-only 佔位，不放寬任何包名，見 `docs/HOOK.md` 第 1.1 節），
  絕不讀寫 Knox 容器；白名單真正放寬需等非空政策接上（[待實作]）。

## 5. 常見問題

| 症狀 | 原因 / 解法 |
| --- | --- |
| `(NONE)` 或「通道未就緒」 | 先區分所選 gateway、探測能力與 Shizuku binder／授權。未就緒時會拒寫，但 Refresh 本身不重新探測；重選模式或回到前景才會觸發相應重探，仍不保證 Auto 失敗降級。單一恢復流程由 #25 追蹤，不能以標題權限代替實際通道狀態。 |
| `no dual profile` | 可能是未建立、候選歧義或輸出無法解析，不能一律解讀為尚未建立。先唯讀核對平台身分；不確定即停止寫入，見 #21/#27 |
| `Failure [INSTALL_FAILED_...]` | 來源 APK 不存在或儲存空間不足；先確認主 profile 已安裝該 App |
| Play 審查相關警告 | 本專案不發 Play 版，請一律使用 GitHub Release 的 APK |

## English Summary

- Privilege routing (tested pure function + F4a wired channels [DEVICE-UNVERIFIED]): LSPosed (ROOT) first, su ROOT via `SuCmdGateway` (`su -c`), Shizuku (SHELL) via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5)
  fallback, else read-only (NONE) with `NoOp` fail-closed. Factory selects by probe; `channelReady` is true only when the selected channel's probe passes; `hasLsposed` stays conservative false (F4b); binder round-trip, all four auth scenarios, su behavior, end-to-end pm, and LSPosed enable detection are unverified.
- Pair Shizuku via Wireless debugging (Android 11+, unverified flow), approve Galaxy Dual Plus, then refresh (status switches only when the Shizuku probe passes; otherwise stays read-only NONE — switching itself is DEVICE-UNVERIFIED).
- Clone with `pm install-existing --user <dualId> <pkg>`; verify with `pm list packages --user` ground truth (not `pm path` alone);
  remove with `pm uninstall --user`. Never touches Secure Folder (Knox).
  `core` encapsulation plus gateway assembly are unit-tested; in-app one-click true channel is wired with limited historical S24 evidence for specified normal SHELL clone/remove and manual channel (see UX_REVIEW_S24.md §5); App ROOT, full binder/auth matrix and traceable build of current source remain unverified; end-to-end matrix is unverified.
- Hook is an observe-only placeholder (empty policy, see `docs/HOOK.md`); it does not extend
  any package yet.
- GitHub sideload builds only; no Play release. Manifest `MANAGE_EXTERNAL_STORAGE`/`REQUEST_INSTALL_PACKAGES` necessity is still under review per #26 — not shipping on Play does not justify overbroad permissions.
