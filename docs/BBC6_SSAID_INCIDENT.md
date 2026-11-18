# BBC6 政策清空 → 三星自動卸載分身 → SSAID 崩潰：完整調查紀錄（2026-11-18）

> 定位：BBC 注入功能裝置驗收期間發生的單一事件（2026-11-18），以唯讀方式調查
> 其原因與後果。本文件記錄：**全清政策 = 觸發 Samsung 自動卸載分身**，
> 以及卸載後 app 仍裝回但啟動閃退的 SSAID 原因。
>
> **目前裝置只事實**，不執行任何裝置動作；未核實的全部標為「未核實」。

## TL;DR

1. **「清除草稿／取消勾選全部」被 Samsung 系統理解為「分身候選集為空」**，啟動
   `DualAppManagerService` 的對帳，直接以 system uid 發出 `START DELETE PACKAGE`
   刪除 user 95 的雙開副本，連帶 `PACKAGE_FULLY_REMOVED` 廣播。
   這不是 `AppPolicyWriter`／`VM`／`BBC hook` 的決定；是 Samsung 對 policy
   possible list 變化的固有反應。BBC6 的「清掉政策」就是卸載分身指令。
2. 使用者稍後在 Samsung Dual Messenger 頁重新 ON，裝置自動執行 `START INSTALL-EXISTING`。
3. **但重新安裝後 app 每次啟動都崩潰**：`IllegalStateException:
   Ssaid settings not accessible`，由 `SettingsProvider$SettingsRegistry.generateSsaidLocked
   (:4653)` 拋出。user 0 同一 app 無 crash；user 95 僅此 app crash（exit-info 證據）。
4. 根因為 **Settings SSAID 表對這個 app 播種了一個 `appId` 鍵
   (`"10431"` → `[REDACTED-SSAID-U95]`)**（2026-05 的雙開設定建立階段寫入），
   而 AOSP 衍生後的 Settings lookup key 是 "complete uid"（`"9510431"`＝95×100000+431）
   — 表中沒有這個鍵 → 系統產生路徑失敗。
5. onPackageRemoved只清 type-1（system）設定，**不改 type-3（SSAID）**；
   `onUidRemovedLocked(9510431)` 嘗試刪除、但表中根本沒有這個鍵，**100% no-op**。
   播種的 `10431` 條目活過了 BBC6／活過卸載／活過重灌。
6. **主 app（user 0）無損；Telegram／Facebook appmanager（Samsung 官方雙開白名單）無損**
   ——它們從不呼叫 `android_id`，或 Samsung 為它們走了另外的播種流程（本表未證實，待驗）。

## 目前事實（2026-11-18 裝置）

| 項目 | 值 |
| --- | --- |
| Device | Galaxy S23 Ultra（SM-S9180）Android 17／One UI 9（`S9180ZHU8GZIF`） |
| Build | KernelSU rooted；Zygisk-LSPosed active；Sandstorm `daagent` 3.7.00.0 |
| App | `com.openai.chatgpt` versionName `1.2026.314` versionCode `2631431` appId `10431` |
| user 0 install | `true`（`installed=true enabled=0`） |
| user 95 install | `true`（after BBC6→clear→Samsung 自動卸載→再 toggle ON） |
| user 95 first install | 2026-11-18 17:55:40（掛了 SHA runtime 17:21 首灌；兩次實為新灌） |
| user 95 `pccCeDataInode/De` | `-1 / -1`（僅此 app 異常；Telegram／Facebook 為 `0/0`） |
| user 95 launch | **CRASH** `IllegalStateException: Ssaid settings not accessible` |
| user 95 app Launch | 失敗 4 次（exit-info uid=9510431：#0 2026-11-18 17:58:54.522 pid=17674，#1 17:58:41.471，#2 17:58:41.440，#3 17:58:34.965，#4 17:56:03.152）— 即 **BBC6 重裝後第一次啟動即失敗，永久不可回復** |
| user 0 launch | ✅ 正常 |
| Firefox on u95 | `installed=false`（不是可參照對照） |
| Telegram on u95 | `installed=true`，無 SSAID 表積極（系統白名單 app 可能走其他流程） |
| `settings --user 0 get secure android_id` | `[REDACTED-ANDROID-ID-U0]` |
| `settings --user 95 get secure android_id` | `[REDACTED-ANDROID-ID-U95]` |
| User-0 SSAID 表 ChatGPT entry | `10431` → `[REDACTED-SSAID-U0]`（mtime 2026-11-17 04:18:00） |
| User-95 SSAID 表 ChatGPT entry | `10431` → `[REDACTED-SSAID-U95]`（mtime 2026-11-18 17:22:21.354） |
| User-95 SSAID 表 條目數 | 322 筆，全部 appId keyed，全部 value `[REDACTED-ANDROID-ID-U95]`（即 u95 android_id） |

## 時間線（2026-11-18，UTC+08:00）

```
17:18:10.117  →  LSPosed kernel PID 29265 pid 完整（unified build） daagent 啟動（BBC1，全清政策）
17:18:10.210  →  START DELETE PACKAGE pkg{com.openai.chatgpt} user{95} caller{1000}
17:18:10.215  →  Killing 15500:com.openai.chatgpt/u95a431
17:21:17.495  →  BBC3: 政策 BBC-HOOK installed=1 (daagent, pid=1519)
17:21:17.649  →  BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=1519)
17:21:17.650  →  BBC-HOOK merged +1 packages (total 7, pid=1519)
17:21:54      →  START INSTALL-EXISTING userId{95} pkg{com.openai.chatgpt}   ← BBC4 新裝
17:22:20.742  →  雙開 activity Record 啟動成功： pid=3568（真實 UI session）
17:22:20.922  →  window focused；APP_CALLED_RESTART_INPUT_API
17:22:21.354  →  user-95 SSAID 表 mtime 更新（ChatGPT 10431→[REDACTED-SSAID-U95] 寫入）
17:22:48      →  使用者離開 app，nandswap start pid=3568
17:30:00      →  使用者嘗試「寫入政策草稿（0）」，VM 拒絕（CATALOG_MISSING，無確認對話框）
17:31:31.000  →  使用者按「清除草稿」（MainActivity techCard 入口）
17:31:32.586  →  DualAppActivity 啟動（trigger for BBC6）
17:31:32.910  →  LSPosed: BBC-HOOK installed=1 (daagent, pid=8239)
17:31:32.950  →  PackageManager: START INSTALL-EXISTING permissioncontroller (install-needed)
17:31:32.952  →  START DELETE PACKAGE pkg{com.openai.chatgpt} user{95} caller{1000}   ← 系統刪除
17:31:32.957  →  START INSTALL-EXISTING permissioncontroller (second permission)
17:31:32.962  →  ActivityManager: Force stopping com.openai.chatgpt appid=10431 user=95
17:31:32.967  →  Killing 3568:com.openai.chatgpt/u95a431 (adj 850)
17:31:33.030  →  DualAppManagerService: updateDualAppData Bundle[1120 bytes]
17:31:33.065  →  InputChannel destroyed com.openai.chatgpt/u95a431
17:31:33.068  →  SensorService: Unregistering client listener for PID 3568
17:31:33.070  →  LSPosed: BBC-HOOK callback getPossibleDualAppPackages (pid=8239)  ← EMPTY（無 nonempty 標記）
17:31:33.361  →  RollbackManager: broadcast=ACTION_PACKAGE_FULLY_REMOVED
17:31:33.522  →  PersistedStoragePackageUninstalledReceiver: PACKAGE_FULLY_REMOVED for u95
17:31:33.700  →  SettingsProvider: Receive onPackageRemoved(userId:95)
17:32:44.806  →  BBC re-tick: BBC callback (nonempty, pid=9255) → merged +1
17:55:38      →  使用者切換回 Samsung Dual Messenger 頁面
17:55:40.604  →  START INSTALL-EXISTING userId{95} pkg{com.openai.chatgpt}   ← 再灌
17:56:03      →  FATAL EXCEPTION pid=15625 — IllegalStateException at SettingsProvider$SettingsRegistry.generateSsaidLocked(:4653)
17:58:54.579  →  PowerAnomalyGatherer: AppError uid=9510431 pkg=com.openai.chatgpt errorType=crash
```

## 不成立的假設（以證據排除）

| 假設 | 證據／結論 |
| --- | --- |
| 「BBC6 卸載是使用者干預」 | 無：Samsung 使用 system uid 1000，bundle 的 updateDualAppData 來自 daagent 進程，你沒有操作任何 toggle |
| 「app 從一開始就不能啟動」 | 反駁：17:22:20 pid=3568 完整 UI 動作，>30 分鐘無當機；exit-info 17:56 才首次 crash |
| 「主空間同樣 crash」 | 反駁：user 0 uid=10431 `exit-info` 無任何 `APP CRASH` 記錄 |
| 「Firefox 也有這個問題」 | Firefox 沒裝 u95（`installed=false`），無法對照；先前 BBC 測試是在 Tab S7 user 0／95 上的 Firefox 實例（但 Tab S7 SSAID 未調查） |
| 「Toggle OFF → 再 ON 可修復」 | **實測不可修復**：關閉→完整卸載→重新安裝（17:55:40），之後每次啟動仍崩潰（pid=15625 起至 pid=17674）。因為播種鍵寫入是 fixed 的，卸載不重寫播種，重裝不產生新的 SSAID 行 |
| 「Telegram／Facebook 也會這樣」 | 目前無法實驗對等第三方，只能列「未知」，不能回答 |
| 「BBC6 是可復原的 rollback」 | **不是**：BBC6 的「政策清空」被 Samsung 強解釋為可能安裝的雙開應用集合空，觸發 `START DELETE PACKAGE` |

## 裝置自動執行的步驟（從 logcat）

按 `sendBroadcast` 到 `PACKAGE_REMOVED` 啟動批次回調，系統執行 `onPackageRemoved(…)`，這些是 Samsung 的閉環機制，方向僅為 'notify'，不是 'rollback'。

## BBC6／SSAID 之間的關聯

BBC6（背景 full-policy 清空）→ daagent BBC hook 回傳空 → Samsung DualAppManagerService 重新計算可雙開集合 → 判定 "這個 app 不應該在可能清單" → 啟動 `START DELETE PACKAGE`、`PACKAGE_FULLY_REMOVED`、`SettingsProvider: Receive onPackageRemoved(userId:95)` → 並導致資料與包源全部被移除。

> 因此：**「清除草稿」不是回復原廠狀態**——它會**同步刪除該 app 的雙開副本（含所有 user-95 資料）**。

## 修復可能性（唯讀觀察範圍外，未處置）

- 已知事實：Settings SSAID 表呼叫 `getOrCreateSettingsStateLocked(…)`；插入「完整 uid」鍵失敗（或產生 0-row no-op），而 Samsung 的 lookup path 查的是「完整 uid」鍵——沒有會自動為「完整 uid」補播種的流程。
- 唯一的控制點是在 Samsung SettingsProvider 層面修改 SSAID 表（需 ABX 工具改 `/data/system/users/95/settings_ssaid.xml`，目前裝置持有 root）。
- 若 ABX 改寫未採用：這個 app 在 user 95 永遠無法啟動。
- 本調查**沒有任何 device-write 動作**；修復裝置在此份記錄確認之前不進行。

## 對驗收與文件的影響（已繼續處理）

1. `ACCEPTANCE.md §6` — 需完全改寫（BBC6 = 卸載分身＋資料消失；BBC6 修復不再這樣歸類）。
2. `HOOK.md §1.3a` — 警告條文寫死「清除草稿 = 三星自動卸載分身」。
3. `USAGE.md` — 新增警告框。
4. `TAB7_BBC_INJECTION_PLAN.md` — Addendum D。
5. `ARCHITECTURE.md` — BBC 注入路徑加入「Samsung 對空 possible list 的系統副作用」。
6. `BBC6_SSAID_INCIDENT.md`（本文件）— 永久紀錄。
