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
- 目前政策集合（`EXTRA_WHITELIST_PACKAGES`，經 `PolicySource.effective(seed, remote)`
  決策）預設為**空**，即 hooks 為 observe-only 佔位：會安裝、會記 log，
  但不改變任何行為。接上政策來源（例如 RemotePreferences）後才真正放寬。
  決策為 union-only（只增不減），包名校驗與 `core/ShellEscaper` 同規則；
  靜態欄位合併由純函數 `WhitelistPatch` 執行（`MutableList` 原地追加、
  `String[]` 重建替換，其餘一律放棄並記 log）。`remote` 快照目前為空
 （無政策來源已接線；未來工作才考慮 RemotePreferences 唯讀＋變更監聽），
  行為與空種子完全一致
  [待實機驗證]。

安裝／移除分身仍走 `pm install-existing --user <dualId>` 通道（見 `docs/USAGE.md`），
hook 不介入。

### 1.2 system_server：`dual_app` 服務家族 [S24 OneUI 9 部分已驗證]

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

實機證據（S24 SM-S9210，OneUI 9／Android 17，PDA S9210ZHU6EZIF，
五層之第 2 層 hook 註冊）：
`service list` 確認 `dual_app: com.samsung.android.app.ISemDualAppManager`；
services.jar 反編譯確認 `com.android.server.DualAppManagerService` 存在
（含兩方法＋ `isInstalledWhitelistedPackageInternal` 變體）；lspd 模組日誌
確認候選 1 在三次 system_server 啟動皆 `deoptimized`＋`hooked` 成功、
無 `method not found`。此為 hook 註冊證據，不等於第 3 層 callback 觸發
（可驗日誌缺：尚無回調命中／放寬包名 log）與第 4 層政策生效
（預設空政策，observe-only；system_server marker 同樣僅證明安裝）。
候選 2／3 在此 ROM 未命中，保留為 fallback。

模組在 `onSystemServerStarting` 以反射依序嘗試（第一個載入成功者勝出，
其餘 `try/catch` 略過，非三星 ROM 自動無操作）：

| 候選服務類別 | Hook 的查詢方法 |
| --- | --- |
| `com.android.server.DualAppManagerService` | `getAllWhitelistedPackages()` |
| `com.samsung.android.server.dual.DualAppManagerService` | `isInstalledWhitelistedPackage(String)` |
| `com.android.server.dual.DualAppManagerService` | （同上，取決於版本存在的方法簽名） |

每個方法 hook 前先呼叫 **`deoptimize(method)`**（best-effort）：`deoptimize(Executable)`
機制存在，但缺 caller 身份資訊，故只能對被 hook 的 callee 本體做 best-effort
去最佳化，**已經內聯進呼叫者（caller）的副本不受影響**；短方法若在 hook 前
已被內聯，仍可能收不到回調。
**反內聯效果未驗證**（無三星實機）：`deoptimize()` 回傳值（成功／no-op）與
失敗都會記 log（`GalaxyDualHook` tag），不再全吞；hook 本體不受影響、照常安裝。
Hook Builder 統一使用
**`ExceptionMode.PROTECTIVE`**（佔位保護）：hook 自身出錯只記 log 並跳過，
絕不拖垮 `system_server`。

### 1.3 daagent：`WhiteListApps` [S24 OneUI 9 部分已驗證]

`com.samsung.android.da.daagent` 進程內的
`WhiteListApps`（完整路徑 `com.samsung.android.da.daagent.provider.WhiteListApps`，
舊短路徑保留為 fallback）持有靜態白名單常量：

實機證據（S24，DAAgent v3.7.00.0，dexdump 反編譯確認，五層之第 1 層類別選取）：
該類為 `PUBLIC ABSTRACT` 純常量持有者（3 個 `static final String[]`：
`DUAL_APP_WHITELIST_PACKAGES`、`..._FOR_CHINA`、`CHINA_SALES_CODES`，
僅 `<clinit>`＋Base64 helper，**零查詢方法**）——故 `<clinit>` patch 是
有效的 daagent hook，模糊 `*hitelist*` 掃描在此類正確掛上零方法；
讀查詢實際位於 `DualAppDBHelper.getAllInstalledWhitelistedPackages`、
`DAUtility.getWhiteListPkgs`／`*InSystemServer`、`DualAppManager.*WhitelistPackage*`
（未來非空政策接上時的方法級 hook 目標）。lspd 日誌已確認 daagent 行程載入
含該欄位的類（`found ... static=true` 跨多代行程）；
`using com.samsung.android.da.daagent.provider.WhiteListApps for daagent hooks`
[S24 已驗證]（新路徑連續命中 7 次、零退回，方法零掛載符合抽象類預期）。
`using`×7 僅證明第 1 層類別選取，不等於第 2 層 `<clinit>` interceptor 執行、
第 3 層 callback 觸發、第 4 層包名放寬（可驗日誌缺；預設空政策下本就
observe-only）。App 一鍵與 listing 主驗證見 `docs/USAGE.md` §0／§3，
歷史通道見 [S24 調查](UX_REVIEW_S24.md) §5。

- 反射尋找欄位 **`DUAL_APP_WHITELIST_PACKAGES`** 與 **`..._FOR_CHINA`** 變體：
  兩個候選欄位只要存在**都會**嘗試合併（分別記錄結果），不存在者略過不拋錯。
  地區風險說明：ROM 另持 `CHINA_SALES_CODES`，可能按地區／銷售碼讀取
  `_FOR_CHINA` 欄位；若採「先命中先 patch」單欄位策略，`_FOR_CHINA`
  將永遠被跳過，國行裝置靜默失效——故雙欄位全覆蓋（見 `WhitelistPatch.patchFields`）。
- `hookClassInitializer`：在 `<clinit>` 跑完後把政策集合併入靜態欄位
  （支援 `MutableList`／`String[]` 兩種形態；非 static、型別不合、反射失敗
  一律放棄**並記 log**，方便定位）。
  - `static final`（ROM 實為 `static final String[]`）：**observe-only，不寫入**。
    普通 `Field.set()` 無法重指派 final 欄位（JVM 拋 `IllegalAccessException`，
    見 `WhitelistPatchReflectTest` 行為鎖定測試）；不做 `setAccessible` 強寫，
    ART 行為未驗證、不宣稱可覆寫。結果以 `Outcome.ObserveOnly` 明確記錄，
    絕不靜默當成功。
    daagent marker（`scope=daagent`）僅證明至少一個 `WhiteListApps` hook 已安裝
    （此 ROM 實為 `<clinit>` interceptor），不證明政策已生效
    （預設空政策下直接返回（語義等同 Skipped，未觸及反射）；
    即使將來政策非空，此 ROM 的 `static final` 欄位仍恆 `ObserveOnly`，
    見 `DualHook.patchWhitelistField`（空政策直接返回）與
    `WhitelistPatch.apply`（非空時 `final=>ObserveOnly`），故僅觀察）。
  - `added=0`（政策已完全包含）：跳過 `set()`，無需寫入，自然 fail-closed。
- 方法名含 `hitelist`（大小寫不敏感，如 `isWhitelisted`）者全部掛上與
  system_server 側相同的 extend-only 策略。模糊匹配是故意的：
  無實機證據前縮窄正則可能漏掉真正的查詢方法；簽名守衛兜底——
  Boolean 結果只接受單一 `String` 參數查詢，集合按原類型重建
 （`Set` 不再降級為 `List`，其餘 `Collection`／未知回傳一律只觀察不改並打 log）。
  命中未知方法時僅多一條 log，不改變行為。

### 1.4 非目標進程：`detach()`（H4：自報 scope 已退場，見下）

`onPackageLoaded` 第一件事就是檢查 `SUPPORTED_SCOPES`；不在名單內的進程
立即呼叫 **`detach()`**，該 entry 在此進程不再收到後續生命週期回調
（已安裝的 hook 不受影響）。`SUPPORTED_SCOPES` 與
`META-INF/xposed/scope.list` 兩行保持一致：

```
system
com.samsung.android.da.daagent
```

system vs android 釐清：`system` 是 LSPosed 作用域偽名（代表 system_server，
走 `onSystemServerStarting` 注入）；`android` 才是 framework 實包名，
`onPackageLoaded` 在 system_server 進程收到 `android`（或 `system`）時
直接返回、**絕不 `detach()`**（避免在 system_server 內自斷後續回調）。
`com.android.settings` 已移除：目前沒有任何 settings 側 hook，
留空 scope 只會擴大注入面；將來真有 settings 側檢查再加回。

自報 scope（H3 評估、H4 退場）：自 scope prefs 自報方案已由 F4b-P2
file marker（`HookMarker`：`/data/system` 主＋`/data/local/tmp` 備，
scope `system_server`／`daagent`，按實際安裝數寫入，見本文件 §1.2／§1.3
與 `HookMarker.kt`）取代，不再注入自家 UI 進程。理由：file marker 有
S24 實機 EACCES 經驗＋安裝數證據（未安裝不寫，誠實 "not detected"），
是目標 hook 存活證據；自家 prefs 只能證明自注入，需額外 2 scope 的
注入面卻換來更弱的證據。hook 啟用訊號的唯一來源是 file marker，
App 側經 `HookStateProvider` 讀取、僅顯示、永不進入路由
（`hasLsposed` 恆 `false`，見 `DualViewModel.probeChannels`）。

Marker 實測現況（S24 [S24 已驗證]，root 讀取；五層之 marker 歷史證據，
詳 [S24 調查](UX_REVIEW_S24.md) §4.4／§5）：
- 主路徑已寫入：`scope=system_server`（system_server 側 hook 安裝後寫入成功；
  早於本次開機約 5.5h，為歷史證據，不能宣稱本次開機有效）。
- daagent 側雙路徑 EACCES：主＋備皆不可寫（daagent SELinux 域限制），
  故 marker 目前僅含 `system_server`；daagent hook 安裝證據以 lspd 日誌
  `found ... static=true`＋clinit 行為準，不以 marker scope 齊全為準。
- App uid／`run-as` 皆不可讀任一路徑 [S24 已驗證]：直接讀取恆不可讀——P1 起不再顯示為「未偵測到」（假陰性），而是「狀態未知（無權限讀取／無可用診斷通道）」（配合 [#27](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/27)）；僅當確定可讀但缺失時才顯示「未偵測到」。明選 LSPosed（NoOp 佔位）時診斷仍走獨立路徑：直接讀取＋經當時已探測通過的真通道（ROOT／SHIZUKU）gateway `cat`（[待實機驗證]）；無任何真通道時為「狀態未知（無可用診斷通道）」，需切到 ROOT／Shizuku 真通道後重探才能完整診斷。診斷結果永不進入路由／`channelReady`（沿襲 #19）。
- daagent 新類路徑（provider 首選）掛載 [S24 已驗證]：
  `using com.samsung.android.da.daagent.provider.WhiteListApps for daagent hooks`
  連續命中（daagent 自然重啟後新碼載入，零退回、零 `not present`）。

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

### 2.2 LSPosed 勾選（＋ App 內通道選擇）

1. 打開 LSPosed 管理器 → 模組 → 勾選 **Galaxy Dual Plus Hook**（啟用）。
2. 作用域（Scope）勾選以下兩項（H4：自家包自報已退場，file marker 為唯一訊號）：
   - `system`（系統框架，必勾，否則 system_server hook 不生效）
   - `com.samsung.android.da.daagent`（三星雙開代理）
3. 若管理器支援按用戶啟用：主用戶與雙開用戶（user 95）**兩邊都要啟用**。
   不同 LSPosed 版本 UI 不同（全局作用域＋按用戶開關，或按用戶各自勾選），
   原則不變：兩個用戶都要開、都要含 `system`。
4. App 內通道選擇（F4b-P1 手動 picker，見 `docs/USAGE.md` §4）：打開 App，
   用四按鈕（自動／ROOT／Shizuku／LSPosed）選擇通道；LSPosed 模式顯示
   Hook marker 行（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，見 §1.4 與 P1 四態），
   該行僅顯示、永不授權——LSPosed 通道為 NoOp 佔位，寫入一律拒絕。

### 2.3 重啟需求

- **首次啟用／改動作用域後必須重啟**：`system_server` 注入只在開機時發生，
  不重啟等於沒 hook。建議直接完整重啟手機（軟重啟若管理器支援亦可）。
- 僅更新模組 APK（未改 scope）：API 102 支援 hot reload，但系統進程建議仍重啟一次確認。
- 驗證 hook 安裝：重啟後 `adb logcat | grep GalaxyDualHook` 應看到
  `hooked ...#getAllWhitelistedPackages` 等行＋
  `marker wrote scope=...` 行；看不到＝類名／方法名與目前
  One UI 版本對不上，回報時請附上 `service list` 與版本號。
- 驗證 marker 檔案（需 ROOT／shell 讀權限；App 直接讀不到即顯示「狀態未知」，
  屬正常 fail-closed，不代表 hook 沒裝；僅確定可讀但缺失才顯示「未偵測到」）：
  ```bash
  adb shell su -c 'cat /data/system/galaxydual_hook.active' 2>/dev/null \
    || adb shell cat /data/local/tmp/galaxydual_hook.active
  # 期望輸出（scope 依實際安裝而定）：
  # scope=daagent,system_server
  # timeMs=1710000000000
  ```
  App 內 LSPosed 模式的 Hook 行顯示同一內容（歷史標記文案／`未偵測到`／`狀態未知（…）`）。

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
  with best-effort `deoptimize()` (callee-only, effect unverified) and `PROTECTIVE` exception mode.
- Agent side: patches both `WhiteListApps#DUAL_APP_WHITELIST_PACKAGES` and
  `#..._FOR_CHINA` after `<clinit>` (static-final fields stay observe-only, never force-written) and
  hooks `*hitelist*` query methods (fuzzy match kept for lack of on-device evidence;
  signature guard keeps unknown shapes observe-only); non-scope processes get `detach()`.
- Install the APK in both owner and dual (e.g. user 95) users, enable the module with
  `system` + daagent scopes in LSPosed, then reboot. Never touches Knox.
- The marker proves hook installation only, not policy effect (empty=>no-op,
  returns early in `DualHook.patchWhitelistField`; static-final=>`ObserveOnly`
  in `WhitelistPatch.apply` on this ROM).
