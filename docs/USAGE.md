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
- [待實作] App 內一鍵 clone UI（`MainActivity` 現為唯讀佔位，無複製／移除按鈕；
  `DualViewModel.clone/remove` 邏輯存在但未接真實 gateway 與 Shizuku 綁定）、
  非空 whitelist policy（預設為空，hook 為 observe-only）。
- [待實機驗證] LSPosed 載入、Shizuku 四情境（有／無 binder × 有／無授權，含重啟後重啟動）、
  雙開實機（`install-existing / path / uninstall --user` 端到端）。

Hook 誠實聲明（`docs/HOOK.md` 第 1.1 節，本文引用不再重述為已生效）：
hook 政策集合預設為空，hooks 為 observe-only 佔位——會安裝、會記 log，
但不改變任何行為、不放寬任何包名；接上政策來源前，本文凡提及「hook 通道／白名單放寬」
皆指設計意圖，不是現有效果。

## 1. 提權優先順序（設計目標＋`core` 純函數，自動接線 [待實作]）

路由純函數（[已支援]）依序判斷；開機／開 App 自動探測與通道綁定（[待實作]）完成前，
以下為預期行為而非可用流程：

1. **LSPosed 優先（ROOT）**：偵測到本模組在 LSPosed 中已勾選並啟用 → `Privilege.ROOT`
   - 設計上走 hook 通道（或 `su -c`），目標是呼叫三星雙開服務；現階段 hook 為
     observe-only（見上文引用），LSPosed 載入 [待實機驗證]，不可描述為已能擴展包名。
2. **Shizuku 降級（SHELL）**：無 LSPosed 但 Shizuku binder 可達 → `Privilege.SHELL`
   - 設計上走 `pm` 指令通道（`install-existing` / `uninstall --user` / `path --user`）；
     App 端綁定 [待實作]，四情境 [待實機驗證]。
3. **NONE（唯讀）**：兩者皆無 → 不執行任何寫入，只顯示狀態（[已支援]：`NoOpGateway` 拒寫）。

```kotlin
// Pure routing logic, no side effects (see core/Privilege.kt).
val privilege = routePrivilege(hasLsposed = hookActive, hasShizuku = shizukuAlive, hasRoot = false)
```

UI 在 `NONE` 時設計為停用「複製 / 移除」按鈕；接線完成後，LSPosed 啟用可於下次
`refresh()` 切回 ROOT——以上按鈕與自動切換皆 [待實作]（`MainActivity` 現無按鈕），
實機切換 [待實機驗證]。

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
6. 回到本 App，下拉 `refresh()`；接線完成後標題列預期從 `(NONE)` 變為 `(SHELL)`
   （現階段探測寫死＋`NoOpGateway`，此切換 [待實作]＋[待實機驗證]，不可視為已可用）。

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
  App 內一鍵 UI [待實作]，端到端 [待實機驗證]）。

## 4. LSPosed 優先模式（設計目標，[待實作]＋[待實機驗證]）

- 已 ROOT + LSPosed 的裝置：到 LSPosed 管理器勾選 **Galaxy Dual Plus**，
  作用域勾三星雙開相關進程後重啟；接線完成後 App 預期自動以 `ROOT` 執行，不再走 Shizuku
  （現階段未接線、無實機證據）。
- 同時有 LSPosed 與 Shizuku 時設計為永遠選 LSPosed；停用模組後設計為下次啟動自動降級 Shizuku
  （同上，[待實作]＋[待實機驗證]）。
- LSPosed 模組僅 hook 雙開白名單查詢（observe-only 佔位，不放寬任何包名，見 `docs/HOOK.md` 第 1.1 節），
  絕不讀寫 Knox 容器；白名單真正放寬需等非空政策接上（[待實作]）。

## 5. 常見問題

| 症狀 | 原因 / 解法 |
| --- | --- |
| 標題列顯示 `(NONE)` | Shizuku 未啟動或未授權；按第 2 節重新啟動並授權後 `refresh()` |
| `no dual profile` | 三星尚未建立雙開 profile；先到系統設定開啟 Dual Messenger 任一 App |
| `Failure [INSTALL_FAILED_...]` | 來源 APK 不存在或儲存空間不足；先確認主 profile 已安裝該 App |
| Play 審查相關警告 | 本專案不發 Play 版，請一律使用 GitHub Release 的 APK |

## English Summary

- Privilege routing (design + tested pure function): LSPosed (ROOT) first, Shizuku (SHELL)
  fallback, else read-only (NONE). In-app auto-wiring is TODO; on-device switching is unverified.
- Pair Shizuku via Wireless debugging (Android 11+, unverified flow), approve Galaxy Dual Plus, then refresh.
- Clone with `pm install-existing --user <dualId> <pkg>`; verify with `pm path --user`;
  remove with `pm uninstall --user`. Never touches Secure Folder (Knox).
  `core` encapsulation is unit-tested; in-app one-click UI is TODO; end-to-end is unverified.
- Hook is an observe-only placeholder (empty policy, see `docs/HOOK.md`); it does not extend
  any package yet.
- GitHub sideload builds only; no Play release, so `MANAGE_EXTERNAL_STORAGE` needs no Play filing.
