<!-- MIT License - Original docs by Galaxy-Dual-app-Plus contributors. No GPL content. -->
# 使用教學 (USAGE)

> 本專案僅經由 GitHub 發佈 sideload APK，不上架 Play 商店。
> 因此 `MANAGE_EXTERNAL_STORAGE` 等權限僅用於 GitHub 版 APK 暫存，不做 Play 政策宣告。
> 全程不碰 Secure Folder（Knox 容器）。

## 1. 提權優先順序（自動切換）

開機 / 開 App 時自動依序探測，最優者勝出：

1. **LSPosed 優先（ROOT）**：偵測到本模組在 LSPosed 中已勾選並啟用 → `Privilege.ROOT`
   - 走 hook 通道（或 `su -c`），可直接呼叫三星雙開服務。
2. **Shizuku 降級（SHELL）**：無 LSPosed 但 Shizuku binder 可達 → `Privilege.SHELL`
   - 走 `pm` 指令通道（`install-existing` / `uninstall --user` / `path --user`）。
3. **NONE（唯讀）**：兩者皆無 → 不執行任何寫入，只顯示狀態。

```kotlin
// Pure routing logic, no side effects (see core/Privilege.kt).
val privilege = routePrivilege(hasLsposed = hookActive, hasShizuku = shizukuAlive, hasRoot = false)
```

UI 在 `NONE` 時會停用「複製 / 移除」按鈕；一旦 LSPosed 啟用，下次 `refresh()` 即自動切回 ROOT。

## 2. Shizuku 配對教學（無線調試，Android 11+）

適用無 ROOT 裝置，三星 One UI 4.0+（Android 12+）皆可用。

1. 手機與電腦連上**同一個 Wi-Fi**。
2. 手機開啟**開發者選項**：
   - 設定 → 關於手機 → 軟體資訊 → 連點「版本號碼」7 次。
3. 設定 → 開發者選項 → 開啟**無線調試（Wireless debugging）**。
4. 安裝並開啟 **Shizuku**（GitHub 版），點選「經由無線調試啟動」→「配對」。
   - Shizuku 會顯示 6 位數配對碼；在系統跳出的「無線調試配對」對話框輸入配對碼。
   - 配對成功後回到 Shizuku 點「啟動」，看到版本號即表示 binder 已就緒。
5. 在 Shizuku 的「授權應用」中找到 **Galaxy Dual Plus** 並允許。
6. 回到本 App，下拉 `refresh()`；標題列應從 `(NONE)` 變為 `(SHELL)`。

> 每次重開機後需重複步驟 4（Shizuku 需重新啟動）；配對碼只需配對過一次，
> 同一 Wi-Fi 下重啟一般直接按「啟動」即可。

舊裝置（Android 10 以下無無線調試）改用 USB：

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

## 3. 複製 / 移除流程（`adb install --user 95` 範例）

以下 `95` 為雙開 profile 的 userId 範例；實際請以 App 內 `refresh()` 顯示的
`UserInfo{...}` / `dualUserId` 為準（常見為 95 / 150，機型而異）。

```bash
# 1. 列出所有 profile，確認雙開 userId（找 DUAL 字樣或非 0 的 id）
adb shell pm list users
#   Users:
#     UserInfo{0:Owner:13} running
#     UserInfo{95:DUAL_APP:400} running

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
- 本 App 的 `DualRepository.clone/remove/verify` 即為上述三指令的程式化封裝。

## 4. LSPosed 優先模式

- 已 ROOT + LSPosed 的裝置：到 LSPosed 管理器勾選 **Galaxy Dual Plus**，
  作用域勾三星雙開相關進程後重啟，App 會自動以 `ROOT` 執行，不再走 Shizuku。
- 同時有 LSPosed 與 Shizuku 時永遠選 LSPosed；停用模組後下次啟動自動降級 Shizuku。
- LSPosed 模組僅 hook 雙開白名單與服務，絕不讀寫 Knox 容器。

## 5. 常見問題

| 症狀 | 原因 / 解法 |
| --- | --- |
| 標題列顯示 `(NONE)` | Shizuku 未啟動或未授權；按第 2 節重新啟動並授權後 `refresh()` |
| `no dual profile` | 三星尚未建立雙開 profile；先到系統設定開啟 Dual Messenger 任一 App |
| `Failure [INSTALL_FAILED_...]` | 來源 APK 不存在或儲存空間不足；先確認主 profile 已安裝該 App |
| Play 審查相關警告 | 本專案不發 Play 版，請一律使用 GitHub Release 的 APK |

## English Summary

- Privilege auto-routing: LSPosed (ROOT) first, Shizuku (SHELL) fallback, else read-only (NONE).
- Pair Shizuku via Wireless debugging (Android 11+), approve Galaxy Dual Plus, then refresh.
- Clone with `pm install-existing --user <dualId> <pkg>`; verify with `pm path --user`;
  remove with `pm uninstall --user`. Never touches Secure Folder (Knox).
- GitHub sideload builds only; no Play release, so `MANAGE_EXTERNAL_STORAGE` needs no Play filing.
