<!-- MIT - Original docs by Galaxy-Dual-app-Plus contributors. No GPL content. -->
# Hook 模組教學（HOOK）

> LSPosed 模組：擴展三星 Dual 白名單查詢，絕不觸碰 Secure Folder（Knox）。
> 全部自創 MIT 程式碼，僅思路參考第三方（見 `docs/CREDITS.md`），無抄襲 OnePlus / GPL hook 碼。

## 1. Hook 原理

### 1.1 目標：只放寬「白名單查詢」，不碰安裝 / 移除路徑

三星 Dual（Dual Messenger / Dual App）能否雙開某個 App，取決於系統側的
**白名單查詢**。本模組只 hook 查詢類方法，採「只擴展、不縮減」策略：

- 原本回傳 `true`／已在名單 → 保持不變。
- 原本回傳 `false`／不在名單 → 僅當該包名在模組政策集合內才放行。
- 目前政策集合（`EXTRA_WHITELIST_PACKAGES`）預設為**空**，即 hooks 為
  observe-only 佔位：會安裝、會記 log，但不改變任何行為。
  接上政策來源（例如 RemotePreferences）後才真正放寬。

安裝／移除分身仍走 `pm install-existing --user <dualId>` 通道（見 `docs/USAGE.md`），
hook 不介入。

### 1.2 system_server：`dual_app` 服務家族

雙開服務跑在 `system_server` 內，對外以 binder 服務 `dual_app` 暴露。
常見的白名單查詢 transaction 編號落在 **1／2／3／4**（白名單列表、
是否在白名單、是否已安裝白名單包、雙開狀態查詢）；**實際編號隨 One UI
版本漂移，上機前必須重新確認**：

```bash
# 1. 確認服務存在
adb shell service list | grep -i dual
# 2. 查實作類別（版本不同類別名不同，模組內已做多候選反射）
adb shell dumpsys activity services | grep -i -A2 dual
```

模組在 `onSystemServerStarting` 以反射依序嘗試（第一個載入成功者勝出，
其餘 `try/catch` 略過，非三星 ROM 自動無操作）：

| 候選服務類別 | Hook 的查詢方法 |
| --- | --- |
| `com.android.server.DualAppManagerService` | `getAllWhitelistedPackages()` |
| `com.samsung.android.server.dual.DualAppManagerService` | `isInstalledWhitelistedPackage(String)` |
| `com.android.server.dual.DualAppManagerService` | （同上，取決於版本存在的方法簽名） |

每個方法 hook 前先呼叫 **`deoptimize(method)`**：防止 ART 把被 hook 的短方法
內聯（inline）進呼叫者、導致 hook 回調永遠不觸發。Hook Builder 統一使用
**`ExceptionMode.PROTECTIVE`**（佔位保護）：hook 自身出錯只記 log 並跳過，
絕不拖垮 `system_server`。

### 1.3 daagent：`WhiteListApps`

`com.samsung.android.da.daagent` 進程內的
`WhiteListApps` 持有靜態白名單常量：

- 反射尋找欄位 **`DUAL_APP_WHITELIST_PACKAGES`**（僅診斷記 log＋合併用，
  找不到就略過，不拋錯）。
- `hookClassInitializer`：在 `<clinit>` 跑完後把政策集合併入靜態欄位
  （支援 `MutableList`／`String[]` 兩種形態，其餘形態直接放棄）。
- 方法名含 `hitelist`（大小寫不敏感，如 `isWhitelisted`）者全部掛上與
  system_server 側相同的 extend-only 策略。

### 1.4 非目標進程：`detach()`

`onPackageLoaded` 第一件事就是檢查 `SUPPORTED_SCOPES`；不在名單內的進程
立即呼叫 **`detach()`**，該 entry 在此進程不再收到後續生命週期回調
（已安裝的 hook 不受影響）。`SUPPORTED_SCOPES` 與
`META-INF/xposed/scope.list` 三行保持一致：

```
system
com.samsung.android.da.daagent
com.android.settings
```

## 2. Scope 勾選教學

### 2.1 安裝：主用戶＋雙開用戶都要裝

1. 主用戶（user 0）正常安裝本 App 的 APK。
2. 查出雙開用戶的 userId（常見為 `95`，機型而異，以實際輸出為準）：
   ```bash
   adb shell pm list users
   #   Users:
   #     UserInfo{0:Owner:13} running
   #     UserInfo{95:DUAL_APP:400} running
   ```
3. 在雙開用戶內同樣安裝一份（以 `95` 為例）：
   ```bash
   adb install --user 95 app/build/outputs/apk/debug/app-debug.apk
   ```

### 2.2 LSPosed 勾選

1. 打開 LSPosed 管理器 → 模組 → 勾選 **Galaxy Dual Plus Hook**（啟用）。
2. 作用域（Scope）勾選以下三項：
   - `system`（系統框架，必勾，否則 system_server hook 不生效）
   - `com.samsung.android.da.daagent`（三星雙開代理）
   - `com.android.settings`（預留設定側檢查）
3. 若管理器支援按用戶啟用：主用戶與雙開用戶（user 95）**兩邊都要啟用**。
   不同 LSPosed 版本 UI 不同（全局作用域＋按用戶開關，或按用戶各自勾選），
   原則不變：兩個用戶都要開、都要含 `system`。

### 2.3 重啟需求

- **首次啟用／改動作用域後必須重啟**：`system_server` 注入只在開機時發生，
  不重啟等於沒 hook。建議直接完整重啟手機（軟重啟若管理器支援亦可）。
- 僅更新模組 APK（未改 scope）：API 102 支援 hot reload，但系統進程建議仍重啟一次確認。
- 驗證：重啟後 `adb logcat | grep GalaxyDualHook` 應看到
  `hooked ...#getAllWhitelistedPackages` 等行；看不到＝類名／方法名與目前
  One UI 版本對不上，回報時請附上 `service list` 與版本號。

## 3. 免責聲明

- 本模組僅放寬白名單**查詢**，不讀寫、不掛載、不繞過 Secure Folder／Knox；
  任何 Knox 熔斷規避皆明確不在範圍內。
- 需 ROOT＋LSPosed 環境；操作系統進程本來就有風險（ bootloop 可能性雖低但存在，
  請先備份）。後果自負，作者不承擔裝置變磚、資料遺失、保固失效責任。
- 全部 hook 邏輯為本專案自創（MIT），第三方僅為思路參考（見 `docs/CREDITS.md`），
  未複製任何 GPL 授權代碼。
- 僅供學習研究與個人裝置使用；請遵守當地法規與三星服務條款。

## English Summary

- The hook module extends Samsung Dual whitelist *queries* only (extend-only, never shrink);
  default policy set is empty so hooks are observe-only placeholders.
- System side: reflectively hooks `DualAppManagerService#getAllWhitelistedPackages` /
  `#isInstalledWhitelistedPackage` (`dual_app` transactions ~1/2/3/4, verify per One UI),
  with `deoptimize()` against ART inlining and `PROTECTIVE` exception mode.
- Agent side: patches `WhiteListApps#DUAL_APP_WHITELIST_PACKAGES` after `<clinit>` and
  hooks `*hitelist*` query methods; non-scope processes get `detach()`.
- Install the APK in both owner and dual (e.g. user 95) users, enable the module with
  `system` + daagent + settings scopes in LSPosed, then reboot. Never touches Knox.
