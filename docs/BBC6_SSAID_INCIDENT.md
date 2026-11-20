# BBC6 政策清空 → 三星自動卸載分身 → SSAID 崩潰：完整調查紀錄（2026-11-18）

> 定位：BBC 注入功能裝置驗收期間發生的單一事件（2026-11-18），以唯讀方式調查
> 其原因與後果。本文件記錄 11-18 觀察到的**政策清空與 Samsung 自動卸載分身的關聯**，
> 以及卸載後 app 仍裝回但啟動閃退的 SSAID 原因。
> **2026-11-20/11-21 更新**：非空政策下亦發生冷啟動 deletion，原「清空才刪／清空即觸發」因果已撤回；
> 詳 `BBC_COLDSTART_DELETE_INCIDENT.md` §5/§8，該文更新優先於本文 11-18 結論。
>
> **目前裝置只事實**，不執行任何裝置動作；未核實的全部標為「未核實」。

## TL;DR

1. **11-18 觀察**：「清除草稿／取消勾選全部」（政策為空）之後，daagent 冷啟動時
   Samsung 以 system uid 發出 `START DELETE PACKAGE` 刪除 user 95 雙開副本，連帶
   `PACKAGE_FULLY_REMOVED` 廣播。**原「空政策 → possible-list 空 → Samsung 刪除」
   的因果鏈已撤回**：11-20/11-21 兩次 deletion 皆在**非空政策**（`nonempty` +
   `merged +1 total 7`）下發生，且 DELETE 早於 BBC 回調 116–144ms；實際觸發機制
   未證實（見 `BBC_COLDSTART_DELETE_INCIDENT.md` §5/§8）。非空不保證安全，
   刪除後資料全清、無法復原。
2. 使用者稍後在 Samsung Dual Messenger 頁重新 ON，裝置自動執行 `START INSTALL-EXISTING`。
3. **但重新安裝後 app 每次啟動都崩潰**：`IllegalStateException:
   Ssaid settings not accessible`，由 `SettingsProvider$SettingsRegistry.generateSsaidLocked
   (:4653)` 拋出。user 0 同一 app 無 crash；user 95 僅此 app crash（exit-info 證據）。
4. 根因為 **Settings SSAID 表對這個 app 播種了一個 `appId` 鍵
   (`"10431"` → `<fc8baf3f…（系統 SSAID hash，已遮蔽）>`)**（2026-05 的雙開設定建立階段寫入），
   而 AOSP 衍生後的 Settings lookup key 是 "complete uid"（`"9510431"`＝95×100000+431）
   — 表中沒有這個鍵 → 系統產生路徑失敗。
5. onPackageRemoved只清 type-1（system）設定，**不改 type-3（SSAID）**；
   `onUidRemovedLocked(9510431)` 嘗試刪除、但表中根本沒有這個鍵，**100% no-op**。
   播種的 `10431` 條目活過了 BBC6／活過卸載／活過重灌。
6. **晚間恢復（2026-11-18 22:30，使用者回報）**：整機重啟 + Samsung「設定→雙開應用」原生 toggle-on 後，ChatGPT 分身**使用者回報正常啟動**（截圖 `s23u_05_chatgpt_working.png`；pid 未收錄於 repo 擷錄）。上文 1–5 均為事件當下狀態，以此恢復紀錄為準。
7. **主 app（user 0）無損；Telegram／Facebook appmanager（Samsung 官方雙開白名單）無損**
   ——它們從不呼叫 `android_id`，或 Samsung 為它們走了另外的播種流程（本表未證實，待驗）。

## 事件當下事實（2026-11-18 17:xx；22:30 恢復見後述『晚間更新』，本表為當時狀態留存）

| 項目 | 值 |
| --- | --- |
| Device | Galaxy S23 Ultra（SM-S9180）Android 17／One UI 9（`S9180ZHU8GZIF`） |
| Build | KernelSU rooted；Zygisk-LSPosed active；Sandstorm `daagent` 3.7.00.0 |
| App | `com.openai.chatgpt` versionName `1.2026.314` versionCode `2631431` appId `10431` |
| user 0 install | `true`（`installed=true enabled=0`） |
| user 95 install | `true`（after BBC6→clear→Samsung 自動卸載→再 toggle ON） |
| user 95 first install | 2026-11-18 17:55:40（掛了 SHA runtime 17:21 首灌；兩次實為新灌） |
| user 95 `pccCeDataInode/De` | `-1 / -1`（僅此 app 異常；Telegram／Facebook 為 `0/0`） |
| user 95 launch | **CRASH**（BBC6 刪除分身後的重裝版本；22:30 恢復後使用者回報可正常啟動）`IllegalStateException: Ssaid settings not accessible` |
| user 95 app Launch | BBC6 刪除分身後的**重裝版本**啟動崩潰（exit-info uid=9510431：#0 2026-11-18 17:58:54.522 pid=17674，#1 17:58:41.471，#2 17:58:41.440，#3 17:58:34.965，#4 17:56:03.152）— BBC6 之前、於 17:22 分身已可正常啟動（pid=3568 持續 ~9 分鐘正常 UI session），BBC6 後 SSAID 拒絕。 **可恢復**：裝置重啟 + Samsung 雙開應用切開；新 cycle（2026-11-18 22:30）已驗證用戶回報為可正常啟動（截圖 `s23u_05_chatgpt_working.png`）|
| user 0 launch | ✅ 正常 |
| Firefox on u95 | `installed=false`（不是可參照對照） |
| Telegram on u95 | `installed=true`，無 SSAID 表積極（系統白名單 app 可能走其他流程） |
| `settings --user 0 get secure android_id` | `<u0 android_id（已遮蔽）>` |
| `settings --user 95 get secure android_id` | `<u95 android_id（已遮蔽）>` |
| User-0 SSAID 表 ChatGPT entry | `10431` → `<8ca73ab6…（系統 SSAID hash，已遮蔽）>`（mtime 2026-11-17 04:18:00） |
| User-95 SSAID 表 ChatGPT entry | `10431` → `<fc8baf3f…（系統 SSAID hash，已遮蔽）>`（mtime 2026-11-18 17:22:21.354） |
| User-95 SSAID 表 條目數 | 約 324 筆：322 筆 full-uid 鍵（值為 u95 android_id）＋ 1 筆 appId 鍵 `10431`＋1 筆 preserve 列（依本機 `INVESTIGATION_SSAID_BBC6.md`／`abx_notes.md`）；**不是**「全部 appId keyed」

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
17:22:21.354  →  user-95 SSAID 表 mtime 更新（SSAID 內容已遮蔽）
17:22:48      →  使用者離開 app，nandswap start pid=3568
17:30:00      →  使用者嘗試「寫入政策草稿（0）」，VM 拒絕（CATALOG_MISSING，無確認對話框）
17:31:31.000  →  使用者按「清除草稿」（MainActivity techCard 入口）
17:31:32.586  →  DualAppActivity 啟動（trigger for BBC6）
17:31:32.913  →  LSPosed: BBC-HOOK installed=1 (daagent, pid=8239)
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
| 「app 從一開始就不能啟動」 | 反駁：17:22:20 pid=3568 完整 UI 動作；17:22:48 離開 app，17:31:33 BBC6 刪除，exit-info 17:56 才首次 crash（BBC6 之前無 crash 紀錄） |
| 「主空間同樣 crash」 | 反駁：user 0 uid=10431 `exit-info` 無任何 `APP CRASH` 記錄 |
| 「Firefox 也有這個問題」 | Firefox 沒裝 u95（`installed=false`），無法對照；先前 BBC 測試是在 Tab S7 user 0／95 上的 Firefox 實例（但 Tab S7 SSAID 未調查） |
| 「Toggle OFF → 再 ON 可修復」 | **部分成立**：當天 17:55 卸載→重裝後 toggle-on 仍 crash（pid=15625 起至 pid=17674）；稍後晚間整機重啟 + Samsung 原生 toggle-on（22:26 安裝）後，**使用者回報**可正常啟動（截圖 `s23u_05_chatgpt_working.png`；pid 未收錄於 repo 擷錄） |
| 「Telegram／Facebook 也會這樣」 | 目前無法實驗對等第三方，只能列「未知」，不能回答 |
| 「BBC6 是可復原的 rollback」 | **不是**：BBC6 是破壞性的系統刪除分身（user-95 資料全清）；11-18 觀察到其後伴隨政策清空，但「清空政策即觸發刪除」的因果已由 11-20/11-21 非空政策 deletion 撤回，見 `BBC_COLDSTART_DELETE_INCIDENT.md` §8 |

## 裝置自動執行的步驟（從 logcat）

按 `sendBroadcast` 到 `PACKAGE_REMOVED` 啟動批次回調，系統執行 `onPackageRemoved(…)`，這些是 Samsung 的閉環機制，方向僅為 'notify'，不是 'rollback'。

## BBC6／SSAID 之間的關聯

**11-18 觀察（關聯，非因果）**：背景 full-policy 清空後，daagent 冷啟動時 `START DELETE PACKAGE`（17:31:32.952）先於 BBC 空回調（17:31:33.070），其後 `PACKAGE_FULLY_REMOVED`、`SettingsProvider: Receive onPackageRemoved(userId:95)`，資料與包源全部被移除。**此因果鏈已撤回**：11-20/11-21 兩次 deletion 在非空政策（回調 `nonempty` + `merged +1 total 7`）下發生，DELETE 早於回調 116–144ms；實際觸發未知（見 `BBC_COLDSTART_DELETE_INCIDENT.md` §5/§8）。

> 因此：**「清除草稿」不是回復原廠狀態**（11-18 觀察到其後伴隨刪除；但 11-20/11-21 未清草稿、政策非空時亦遭刪除），非空不保證安全；恢復＝toggle-ON 重裝，但所有 user-95 資料已清除、無法復原。

## 修復可能性與已驗證結果

**恢復狀態（2026-11-18 22:30；使用者回報，截圖佐證；pid 未收錄於 repo 擷錄）**：

1. 主 app 可在設定中重新「啟用雙開」→ 雙開實例**使用者回報正常**（截圖 `s23u_05_chatgpt_working.png` 顯示歡迎／首次啟動畫面；`u95_a431 pid=10165` 為使用者口述，**無 repo 擷錄**）
2. BBC Hook 在重啟後 daagent 仍裝上並 `merged +1 packages (total 7, pid=7003)`（**pid=7003 未收錄於 repo 擷錄**；屬使用者回報等級）
3. 本機曾嘗試 Settings SSAID ABX 手動修補（A2：新增 `9510431` 列），但**無 log／exit-info 擷錄留存**；據此**無法斷言**「A2 已實測無效」，也不再採用該項作為任何結論依據。下列 root-cause 分析（uid-keyed lookup vs appId-keyed 播種列）仍由 dump 材料支撐（u95 SSAID strings；abx_notes；exit-info 17:56–17:58）

**官方恢復方式**：按使用者聯絡記錄中的 22 步順序——
  1. 裝置 `adb reboot`
  2. 重新 root（已在）
  3. 軟重啟以啟用 Zygisk/Vector
  4. 開啟 Samsung「設定→雙開應用」對 ChatGPT 切回 ON
  5. App 正常使用

**本機的 `settings_ssaid.xml` 修補嘗試（記錄，不再採用）**：
- `settings_ssaid.xml.patched_A2_add.example`（新增 `9510431` row，驗證過 parse）：push 之事於當晚 session 中進行，**但無 log／exit-info 擷錄可復核其結果**；不再主張「A2 已實測無效」
- `settings_ssaid.xml.patched_B_rename.example`（同樣 parse 過，未實驗）
- 原 17:55 重裝 + Samsung toggle-on 後，ChatGPT 分身 22:26:54 installed=true，22:30 起無 crash

## 2026-11-18 晚間更新：Samsung 原生 toggle-on + 重啟即可恢復（本文件結論修正）

> **重要修正**（未刪除上方事實紀錄；上方的是當時實況）：使用者在 BBC6 之後 17:55 重新勾選、22:25 重啟後，BBC 注入狀態照常運作；**使用者回報** ChatGPT 雙開於 22:30 起正常運行（`u95_a431 pid=10165` 為口述、未收錄於 repo；截圖 `s23u_05_chatgpt_working.png` 僅顯示歡迎畫面）。因此「不可回復」一事並非最終結論。

### 恢復步驟（2026-11-18 22:30 使用者回報）
1. BBC6 後，Samsung 確實主動刪除了分身（`PACKAGE_FULLY_REMOVED`）
2. 使用者照實作：`adb reboot`（整機）→ 進入 Samsung **「設定 → 雙開應用」**，對 ChatGPT **切回 ON**（同意安裝）→ 系統執行 `START INSTALL-EXISTING` → 使用者 95 重新有 `installed=true`、`firstInstallTime=2026-11-18 22:26:54`
3. 使用者回報 BBC-HOOK 在重啟後 daagent 仍然 `installed=1`、`BBC-HOOK merged +1 packages (total 7, pid=7003)`（**pid 未收錄於 repo 擷錄**）
4. 使用者 95 分身與主空間 `u0_a431` 同時存在，無 crash 環
5. 順帶確認：`settings --user 95 put secure android_id <任何值>` 會觸發 provider reload，但不影響 SSAID 表

### 推論
- BBC6 仍然是**破壞性指令**（系統會刪除分身及其資料，這次是 Samsung 自己做的）；不可恢復的是兩個可持久化的資料——分身內 app 的本地 session／資料、以及任何 Samsung 拒絕在**非白名單分身包**授予的 `Ssaid settings not accessible` 程式碼路徑
- 恢復路徑是：**重啟 + Samsung 原生 toggle-on**；不是從 SettingsProvider 補丁注入改寫 SSAID 表（那個修復方式是可能發生的「很好」目標，但實測沒有成功——單純的 ABX 修補在 live-load 下仍被 Samsung Knox 拒絕服務）
- 我作為修補維護者必須把這份簡單工作流寫清楚——這是 Samsung 官方流程，乾淨、安全

### 本機修復作業書寫守則（依據本次 Samsung 原生流程）
1. 離線備份（本機並留有本機複份）
2. 內部 ABX 嘗試——**結果未留存擷錄**：A2 push＋force-stop Samsung `SettingsProvider` 一事僅由 session 回憶，**無 log 可復核**；不再主張其失敗與否
3. **Samsung 原生 toggle-on（本機 + reboot + Samsung Settings「雙開應用」開啟）是唯一官方修復方式**；任何針對 SSAID 表的手動改寫都是嘗試、且超出本次範圍

### 對之前段落的誠實對照
| 舊斷言 | 修正 |
| --- | --- |
| 「重新勾選後 app 無法啟動、Samsung 永不授予 SSAID」 | 不是「永不」——只要以 Samsung 官方步驟重啟 + toggle-on，就會得到 Samsung 正常授予 SSAID 的結果 |
| 「ABX 修補失敗 = Samsung 不放行」 | 無法成立：A2 修補結果未有擷錄，無法斷言。可斷言的是——**Samsung 原生 toggle-on 恢復路徑**（重啟 + 官方流程），不需要任何修補 |
| 「使用者需要 Factory reset」 | 不必要，使用者自行重開機 + Samsung toggle-on 成功 |

## 對驗收與文件的影響（已繼續處理）

1. `ACCEPTANCE.md §6` — 需完全改寫（BBC6 = 卸載分身＋資料消失；BBC6 修復不再這樣歸類）。
2. `HOOK.md §1.3a` — 警告條文寫死「清除草稿 = 三星自動卸載分身」。
3. `USAGE.md` — 新增警告框。
4. `TAB7_BBC_INJECTION_PLAN.md` — Addendum D。
5. `ARCHITECTURE.md` — BBC 注入路徑加入「Samsung 對空 possible list 的系統副作用」。
6. `BBC6_SSAID_INCIDENT.md`（本文件）— 永久紀錄。
