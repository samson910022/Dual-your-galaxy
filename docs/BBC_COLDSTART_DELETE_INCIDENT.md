# BBC 非空政策下冷啟動刪除事件（2026-11-20 17:53）完整紀錄

> Repo：`Dual-your-galaxy`，分支 `unify/bbc-on-main`（@ `ae8db116`）。
> 本文為 untracked 新增文件，只記錄 2026-11-20 17:53 事件；不做任何結論外推。
> 凡標【擷錄】者，backup 內有原文可對；凡標【口述】者，為對話事實、無逐字擷錄留存。
> 裝置未再觸碰；logcat 全文 36 MB 僅引用行號，不貼全文。

## 0. 事件一句話

非空 BBC 政策（`installed=1`／`callback(nonempty)`／`merged +1 total 7`，pid=12644）之下，
使用者打開 Dual Messenger 頁觸發 daagent 冷啟動，Samsung 在 BBC 首次回調**之前**
（18.974 秒）即以 system uid（`caller{1000}`）下達 `START DELETE PACKAGE` 刪除 u95 ChatGPT 分身，
BBC nonempty 回調（19.090 秒）晚到一步、未能阻止；
`PACKAGE_FULLY_REMOVED` 於 19.5 秒完成。18:05 使用者自行 toggle-ON 重裝，當場可開不崩。

---

## 1. 操作日誌（時間＋執行者＋命令原文＋結果）

### 1.1 上機三步（host 經 adb 執行；命令原文為【口述】，結果有 log 旁證）

| # | 時間 | 執行者 | 命令原文 | 結果 |
|---|------|--------|----------|------|
| 1 | 2026-11-20 17:36 前後 | 維護者（host） | `adb install -r <新包>`（確切檔名與完整命令列未收錄）【口述】 | 成功，保資料。旁證【擷錄】：`logcat_20261120_1753.txt:L128057` `GOS … onPackageChanged(). changeType(INSTALL_STARTED), pkgName(com.galaxydual.plus.debug)`；`L128114` `VerificationCheck: Verification finished for com.galaxydual.plus.debug. Result: Pass(reason=SHELL_INSTALL, message=null)`；`L129047` `Package replaced: com.galaxydual.plus.debug` |
| 2 | 2026-11-20 17:37:50 | 維護者（host） | `am force-stop com.samsung.android.da.daagent`（device 側 adbd 記錄原文【擷錄】`L153659`：`adbd service requested 'shell,v2,TERM=xterm-256color,raw:am force-stop com.samsung.android.da.daagent'`；`from pid 9476` 見 `L153664`） | daagent 被殺：`L153664` `Force stopping com.samsung.android.da.daagent appid=1000 user=0: from pid 9476`；`L153665`（上下文）`Killing 19443:com.samsung.android.da.daagent/1000 (adj 915): stop … due to from pid 9476` |
| 3 | 2026-11-20 17:53:18.619 | 使用者（手持裝置開頁） | 開啟 Samsung Dual Messenger／DualApp 頁（手勢操作，無命令）【口述】 | 觸發 daagent 冷啟動：`L214828` `ActivityManager: Start proc 12644:com.samsung.android.da.daagent/1000 for next-activity {…DualAppActivity}` |

### 1.2 唯讀驗證命令（host 經 adb；device 側 adbd 请求原文【擷錄】，輸出值多數未收錄）

| 時間 | 命令原文（logcat adbd 行 verbatim） | 結果 |
|------|-------------------------------------|------|
| 17:33:01 | `L114143` `getprop ro.product.model`、`L114144` `getprop ro.serialno` | 前期裝置確認；輸出未收錄 |
| 17:35:16 | `L124409` `pm list packages`、`L124412` `pm path com.galaxydual.plus.debug`、`L124415` `dumpsys package com.galaxydual.plus.debug` | 輸出未收錄 |
| 17:35:21 | `L124728`–`L124730` `pm path …`／`pidof …`／`ps -A`／`dumpsys package …` | 輸出未收錄 |
| 17:36:22 | `L134413` `pm path …`、`L134548` `dumpsys package …`、`L134716` `pidof …`、`L134717` `ps -A`、`L134755` `pm list packages` | 輸出未收錄 |
| 17:36:30 | `L135986` `pm list packages`、`L135995` `ps -A`、`L135999` `cmd activity get-current-user`、`L136000` `pm list users` | 輸出未收錄 |
| 17:36:36–17:38:28 | `L136194` `exec logcat -d -s GalaxyDualHook`；`L137326`／`L137511`／`L154662`／`L154844` 各式 `su -c grep … BBC-HOOK … /data/adb/lspd/log/verbose_*.log`；`L154840` `su -c grep -a -E "keep-last-good\|Cleared\|sticky" …` | sticky／keep-last-good 的 grep **輸出未收錄**（見 §5 未決問題） |
| 17:37:10 | `L142345` `su -c 'sha256sum /data/app/~~wPL74o0B9ZnEaJXRK3nDTA==/com.galaxydual.plus.debug-zehl8yUsmosI5rwzsvr0Xw==/base.apk'` | **輸出未收錄**；新包 sha 僅 EVENT 前綴 `d7b60b0f…`（見 §3.2） |
| 17:37:35 | `L153281` `exec logcat -b crash -d`、`L153282` `exec logcat -d` | crash 緩衝區檢查；當時 chatgpt 無 FATAL（見 §3.5） |
| 17:37:44 | `L153520` `pm path --user 95 com.openai.chatgpt` | 刪前存在性檢查；**輸出未收錄**（刪前 `installed=true` 僅【口述】＋ 08:01 在裝事實） |
| 17:37:55–17:39:04 | `L153848` `pidof com.samsung.android.da.daagent`、`L154658`／`L155986` `pidof …daagent`、`L155987` `pidof com.galaxydual.plus.debug`、`L154671`／`L155988`／`L155990` `pm path --user 95 com.openai.chatgpt`／`pm list packages` | 輸出未收錄；此段 force-stop 後 daagent 無 Start proc（直到 17:53:18.619 冷啟動），確認 12644 為冷啟動新 pid |
| 17:54:20（刪後） | `L224935` `ps -A`、`L224938` `pm path --user 95 com.openai.chatgpt` | 輸出未收錄；同期 log 內 `L216923`–`L216924` 已顯示 `installed=false` |
| 17:54:28–17:54:46（刪後） | `L225326`／`L225981`–`L225982` `pm list packages --user 95`（／`--user 0`）、`L225327` `su -c grep -h BBC-HOOK /data/adb/lspd/log/verbose_*.log \| tail -n 12` | 輸出未收錄；BBC 政策全程非空（`merged +1 total 7` 維持，見 §2） |

### 1.3 開關 toggle（全部由使用者在手持裝置上執行；repo／PR 未經手任何寫入）

| 時間 | 執行者 | 動作 | 結果 |
|------|--------|------|------|
| 2026-11-20 17:53 刪除後某刻 | 使用者 | 打開 Dual Messenger 頁查看，ChatGPT 顯示為 **OFF**【口述；使用者確認非手動關閉】 | 刪除的外部可見徵兆；log 旁證為整條刪除鏈（§2） |
| 2026-11-20 18:05 | 使用者 | 在 Dual Messenger 頁把 ChatGPT 切回 **ON**（同意安裝）【口述】 | `timeline_1805_recovery.txt:L1` `18:05:46.447 … START INSTALL-EXISTING PACKAGE: userId{95} pkg{com.openai.chatgpt}`；恢復後 `pm path` base＋3 splits 齊全、`dumpsys` `installed=true`（§3.4）；使用者回報可打開、不崩【口述＋log 旁證：crash 零，§3.5】 |

---

## 2. 完整時間線（每條附原始 log 行）

> 行號 `L…` 指 backup 檔 `logcat_20261120_1753.txt`（共 249,674 行、約 36 MB，範圍覆蓋 11-18 crash 段＋11-20 約 16:22→18:00:48；**18:05 恢復不在此 logcat 內**，另見 `timeline_1805_recovery.txt`）。
> `T…:L…` 指 `timeline_1753_delete.txt`（14 行摘錄）對應行。區間如 `L215269`–`L215271` 為行號區間，非逐行順序展開。

| 時間（UTC+08） | 事件 | 原始 log 行 |
|---|---|---|
| 08:01 前後 | u95 ChatGPT 分身在裝（當日基準）【口述；不在本次 logcat 時間範圍內，無擷錄】 | —（無） |
| 17:36:15.248 | 新包 `install -r` 開始（保資料） | `L128057` `GOS:GameIntentService: onPackageChanged(). changeType(INSTALL_STARTED), pkgName(com.galaxydual.plus.debug), UserHandle(0), userId(0), Installed(true)` |
| 17:36:15.281 | 安裝驗證通過（SHELL_INSTALL） | `L128114` `VerificationCheck: Verification finished for com.galaxydual.plus.debug. Result: Pass(reason=SHELL_INSTALL, message=null)` |
| 17:36:17.397–17:36:17.791 | 因包更新，系統 force-stop 舊版 hook app（u95／u0 各一次） | `L128370` `Force stopping com.galaxydual.plus.debug appid=10326 user=-1: killDueToPackageUpdate`；`L128500` `… user=95: pkg removed`；`L128548`／`L128790` `… user=0: pkg removed` |
| 17:36:17.984 | 包替換完成 | `L129047` `vol.BroadcastManager: Package replaced: com.galaxydual.plus.debug` |
| 17:37:50.355 | host 下 `am force-stop daagent`，舊 daagent pid=19443 被殺（pid 新舊對照之「舊」） | `L153664` `Force stopping com.samsung.android.da.daagent appid=1000 user=0: from pid 9476`；上下文行 `Killing 19443:com.samsung.android.da.daagent/1000 (adj 915): stop … due to from pid 9476`（舊 pid 來源：`L663` `16:22:33.946 … Start proc 19443:com.samsung.android.da.daagent/1000 for started-service …DAWeekUsageService`） |
| 17:53:18.619 | 使用者開頁 → daagent 冷啟動，新 pid=12644（pid 新舊對照之「新」） | `L214828` `ActivityManager: Start proc 12644:com.samsung.android.da.daagent/1000 for next-activity {…DualAppActivity}` |
| 17:53:18.923 | hook 查詢裝上（`installed=1` 前置） | `L215206` `daagent query hooks: installed=1, classesLoaded=3/9, classLookupMisses=6, overloadsMatched=1, guardSkipped=0, methodLookupFailures=0, installFailures=0` |
| 17:53:18.927 | **BBC-HOOK installed=1（新包三行之第 1 行；T:L1）** | `L215212` `(com.samsung.android.da.daagent)[com.galaxydual.plus.debug,GalaxyDualHook,3164-1a1f85fee43-2-e,0,1] BBC-HOOK installed=1 (daagent, pid=12644)` |
| 17:53:18.971 | daagent（pid 12644）請求 INSTALL-EXISTING permissioncontroller（T:L2） | `L215260` `PackageManager: START INSTALL-EXISTING PACKAGE: userId{95} pkg{com.samsung.android.permissioncontroller}` ＋ 下一行 `Request from pId{12644 / com.samsung.android.da.daagent}` |
| **17:53:18.974** | **START DELETE PACKAGE（T:L3）**：`pkg{com.openai.chatgpt}, user{95}, caller{1000} flags{0}`（system uid；先於 BBC 首次回調約 116 ms） | `L215269` `PackageManager: START DELETE PACKAGE: observer{18431438}`；`L215270` `PackageManager: pkg{com.openai.chatgpt}, user{95}, caller{1000} flags{0}`；`L215271` `PackageManager: 0002/{caller=android.uid.system:1000, ver=2631431, pkg=com.openai.chatgpt}`（`ver=2631431` 與 BBC6 文件所載 versionCode 一致） |
| 17:53:18.975 | 另兩筆 INSTALL-EXISTING（packageinstaller／permissioncontroller，T:L4–L5） | `L215272`–`L215275`（同含 `Request from pId{12644 …}`） |
| 17:53:18.977 | hook 對白名單僅觀察、不 patch | `L215277` 前後：`skip patch on WhiteListApps: no patchable field shape on device (observe-only)` |
| 17:53:18.977–17:53:19.072 | `updateDualAppData` ×3（T:L6–L8） | `L215278` `…dataSize=1120`；`L215279` `…dataSize=284`；`L215568` `…dataSize=160` |
| 17:53:18.990 | 系統 force-stop 分身（`deletePackageX`）並摘除其 Activity | `L215295` `Force stopping com.openai.chatgpt appid=10431 user=95: deletePackageX`；`L215296`（上下文）`WindowManager: Force removing ActivityRecord{133482041 u95 com.openai.chatgpt/.MainActivity t140}` |
| **17:53:19.090** | **BBC 首次回調（非空；新包三行之第 2 行；T:L9）——晚於 DELETE** | `L215574` `BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=12644)` |
| 17:53:19.091 | **merged +1 total 7（新包三行之第 3 行；T:L10）** | `L215575` `BBC-HOOK merged +1 packages (total 7, pid=12644)` |
| 17:53:19.102–19.117 | 分身進程收尾（pkg removed） | `L215589`／`L215607` `Force stopping com.openai.chatgpt appid=10431 user=95: pkg removed` |
| 17:53:19.127 | `PACKAGE_FULLY_REMOVED/u95` 廣播排隊（T:L11） | `L215616` `ActivityManager Intent delay: Delaying Broadcast: 1000ms, BroadcastRecord{9782f97 android.intent.action.PACKAGE_FULLY_REMOVED/u95/0x2081}` |
| 17:53:19.441 | RollbackManager 廣播 `pkg=com.openai.chatgpt`（T:L12） | `L216710` `RollbackManager: broadcast=ACTION_PACKAGE_FULLY_REMOVED pkg=com.openai.chatgpt` |
| 17:53:19.487 | SGM 確認分身已不存在：`installed=false` | `L216923` `SGM:Util: isPkgInstalled(), com.openai.chatgpt does not exist.`；`L216924` `… pkgName=com.openai.chatgpt, userId=95, installed=false` |
| 17:53:19.604 | AppSearch 處理 FULLY_REMOVED（T:L13） | `L217136` `AppSearchManagerService: Handling android.intent.action.PACKAGE_FULLY_REMOVED broadcast on package: com.openai.chatgpt` |
| 17:53:19.5–19.998 | FULLY_REMOVED 完成（T:L14；EVENT 記 `u95 installed=false, ceDataInode=-1（資料清）`——後者見誠實標註） | `L218218` `PersistedStoragePackageUninstalledReceiver: Received android.intent.action.PACKAGE_FULLY_REMOVED for com.openai.chatgpt for u95` |
| 刪後某刻 | 使用者開頁見開關變 **OFF**（確認非手動）【口述】 | —（無擷錄；見 §6） |
| 18:05:46.447 | 使用者 toggle-ON → **START INSTALL-EXISTING**（恢復；本行出自 `timeline_1805_recovery.txt:L1`，**不在** `logcat_20261120_1753.txt` 時間範圍內） | `timeline_1805_recovery.txt:L1` `11-20 18:05:46.447 26678 26929 I PackageManager: START INSTALL-EXISTING PACKAGE: userId{95} pkg{com.openai.chatgpt}`；旁證：`dumpsys_u95_postrecovery.txt:L4` `firstInstallTime=2026-11-20 18:05:46` |
| 18:05:46 後 | 可打開、不崩；crash 零；user150 初步零觸及待複核【口述＋grep 旁證，見 §3.5】 | —（使用者回報；log 全文無 chatgpt FATAL／SSAID 相關行，user150 僅初步比對） |

時序要點：`DELETE(18.974)` → `BBC 首次回調(19.090)`，相隔約 **116 ms**；
冷啟動期間 BBC log 僅新包 3 行（`verbose_pid12644.log:L1`–`L3`），**無空回調**。

---

## 3. 證據

### 3.1 唯讀驗證命令輸出原文（backup 內有文件的兩份，逐字貼）

`pm_path_u95_postrecovery.txt`（恢復後，base＋3 splits 齊全）：

```
package:/data/app/~~lwAYb0omxnzHPRdRsbrUyg==/com.openai.chatgpt-MyO48A02iZRaJ38WeezaHw==/base.apk
package:/data/app/~~lwAYb0omxnzHPRdRsbrUyg==/com.openai.chatgpt-MyO48A02iZRaJ38WeezaHw==/split_config.arm64_v8a.apk
package:/data/app/~~lwAYb0omxnzHPRdRsbrUyg==/com.openai.chatgpt-MyO48A02iZRaJ38WeezaHw==/split_config.xxhdpi.apk
package:/data/app/~~lwAYb0omxnzHPRdRsbrUyg==/com.openai.chatgpt-MyO48A02iZRaJ38WeezaHw==/split_config.zh.apk
```

註：`~~...==` 後綴含義／政策未明確，原樣保留，不推斷。

`dumpsys_u95_postrecovery.txt`（恢復後，`installed=true`）：

```
    User 95: ceDataInode=782348 deDataInode=403699 pccCeDataInode=-1 pccDeDataInode=-1 installed=true hidden=false suspended=false appLockEnabled=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false quarantined=false
      installReason=0
      dataDir=/data/user/95/com.openai.chatgpt
      firstInstallTime=2026-11-20 18:05:46
      uninstallReason=0
```

其餘唯讀命令（§1.2 表）僅 device 側 adbd 请求行留存於 logcat，**輸出值未收錄於 backup**，
此處不捏造。

### 3.2 sha256

- `old-base.apk`（17:36 被替換掉的舊包；backup 內實檔）：全值來自本地唯讀重算
  `sha256sum`＝`953803c455cc17f1ad3cde70db8e072b03381c36540a407940ceb0d15f31496f`
  （EVENT `EVENT_20261120_1753.md:L11` 僅記 8 字首 `953803c4…`，一致；全 64 字以本地重算為準）【本地重算可重現】。
- 新包：僅 EVENT 前綴 `d7b60b0f…`【口述／敘事主幹原文】，**全值未收錄於 backup**；
  device 側 `L142345` 的 `sha256sum …/com.galaxydual.plus.debug-…/base.apk` **輸出未收錄**。
  不在此斷言兩包差異內容。

### 3.3 pid 新舊對照（daagent）

| | pid | 來源行 |
|---|---|---|
| 舊 | **19443** | `L663` `16:22:33.946 … Start proc 19443:com.samsung.android.da.daagent/1000 for started-service …DAWeekUsageService`；`17:37:50` 被 `am force-stop` 殺掉（上下文行 `Killing 19443 … stop … due to from pid 9476`，緊隨 `L153664` 之後） |
| 新（冷啟動） | **12644** | `L214828` `17:53:18.619 … Start proc 12644:com.samsung.android.da.daagent/1000 for next-activity {…DualAppActivity}`；新包三行 BBC log 全部標 `pid=12644`（`verbose_pid12644.log:L1`–`L3`；`L215212`／`L215574`／`L215575`） |

中間 `17:37:55`／`17:38:21`／`17:39:04` 的 `pidof com.samsung.android.da.daagent`
（`L153848`／`L154658`／`L155986`）**輸出未收錄**，但 17:37:50→17:53:18 間無任何
`Start proc …daagent` 行，12644 確為 force-stop 後首次冷啟動。

### 3.4 dumpsys 前後對照（u95 `com.openai.chatgpt`）

| 階段 | `installed` | 其它欄位 | 來源 |
|------|-------------|----------|------|
| 刪前（08:01 在裝；17:37–17:39 `pm path` 仍可查） | `true` | 詳細欄位未收錄 | 【口述】（`pm path` **輸出未收錄**，見 §6） |
| 刪後（17:53:19.487） | `false` | `com.openai.chatgpt does not exist` | 【擷錄】`L216923`–`L216924`；另 `PACKAGE_FULLY_REMOVED` 鏈 `L215616`／`L216710`／`L217136`／`L218218` |
| 刪後（資料） | EVENT 記 `ceDataInode=-1（資料清）` | — | 【口述／敘事主幹原文】；36 MB 全文 `grep -n ceDataInode` **零命中**，無獨立 log 行可引（見 §6） |
| 恢復後（18:05:46） | `true` | `ceDataInode=782348 deDataInode=403699`，`firstInstallTime=2026-11-20 18:05:46` | 【擷錄】`dumpsys_u95_postrecovery.txt:L1`–`L4`（上文逐字貼） |
| owner（u0） | 完好 | `lastUpdateTime 2026-11-17` | 【口述】（owner 側 dumpsys 未收錄於 backup） |

### 3.5 crash／SSAID／user150（零命中的誠實寫法）

- 以下為本次寫檔前以 `grep -nE` 分開重跑（對象：`logcat_20261120_1753.txt` 全文 249,674 行）、可重現：
  `grep -nE -i "FATAL EXCEPTION.*chatgpt"` → 0 行；`grep -nE "AndroidRuntime.*chatgpt"` → 0 行；
  `grep -nE "ceDataInode"` → 0 行；`grep -nE "user\{150\}|userId\{150\}|u150|User 150"` → 0 行。
  其中 `user150` 僅為初步零命中、待複核（建議後續再以 `150` 全字比對複核——此為未竟之事，見 §5）。
- `logcat -b crash -d` 在 17:37:35（`L153281`）與 17:38:21（`L154670`）被調用過，
  輸出未收錄；恢復後「可打開、不會崩潰」為**使用者回報**【口述】＋上述零命中旁證，
  **無恢復後新 log 擷錄**（logcat 止於 18:00:48，早於 18:05 重裝）。

---

## 4. BBC6 對照表

> 來源：BBC6 見 `docs/BBC6_SSAID_INCIDENT.md`；本次見 §2–§3。

| 維度 | BBC6（2026-11-18） | 本次冷啟動刪除（2026-11-20 17:53） |
|------|-------------------|-----------------------------------|
| 政策內容 | **空**（「清除草稿／取消勾選全部」→候選集為空；`BBC6_SSAID_INCIDENT.md:L11`–`L15`） | **非空**（`installed=1`／`callback(nonempty)`／`merged +1 total 7`，`L215212`／`L215574`／`L215575`） |
| 回調內容 | EMPTY（無 `nonempty` 標記；`BBC6: L79` `… (pid=8239) ← EMPTY`） | nonempty（`L215574` `callback getPossibleDualAppPackages (nonempty, pid=12644)`） |
| 刪除 vs 首次回調時序 | `DELETE 17:31:32.952` → `EMPTY 回調 17:31:33.070`（`BBC6: L72`／`L79`）——**同樣先刪後回調**，差距約 118 ms | `DELETE 17:53:18.974`（`L215269`–`L215271`）→ `nonempty 回調 17:53:19.090`（`L215574`），差距約 116 ms |
| 刪除發起者 | `caller{1000}` system uid（`BBC6: L72`） | 相同：`caller{1000}`／`android.uid.system:1000`（`L215270`–`L215271`） |
| 恢復方式 | 整機重啟＋Samsung 原生 toggle-ON（`BBC6: L120`–`L125`） | **免重啟**：使用者 18:05 直接 toggle-ON 即裝回可用（`timeline_1805_recovery.txt:L1`＋§3.1／§3.4） |
| 恢復後啟動 | **SSAID 崩**（`IllegalStateException: Ssaid settings not accessible`，`BBC6: L17`–`L18`、`L86`） | **不崩**：使用者回報可打開＋全文 crash 零命中（§3.5；但恢復後無新 log 擷錄，見 §6） |
| 觸發路徑 | 使用者在 app 內按「清除草稿」→政策變空→對帳刪 | 使用者僅**打開頁面**（冷啟動）→對帳刪；政策全程非空 |

**誠實附註**：任務素材要求對照「先回調後刪 vs 先刪後回調」，但按 `BBC6_SSAID_INCIDENT.md`
時間線（`L70`–`L80`），BBC6 的 EMPTY 回調（17:31:33.070）同樣落在 DELETE（17:31:32.952）
**之後**；兩次事件皆為「先刪後回調」，真正的區分是**政策空 vs 非空**與**回調空 vs 非空**，
而非時序方向。此表已按原始行糾正，不沿用與擷錄矛盾的敘事。

---

## 5. 未決問題

1. **冷啟動對帳看的是哪份名單？**
   BBC hook 在 19.090 才第一次回調（nonempty），而 Samsung 在 18.974 已經下達刪除。
   Samsung 在 `DualAppActivity` 冷啟動對帳瞬間讀的是哪份名單（開頁前快照？persisted
   名單？與 hook 無關的另一條 possible-list 來源？）——**未做時序分析**（EVENT L12
   原文明確記為「待定」）。
2. **重裝復發風險。**
   18:05 toggle-ON 裝回可用，但觸發條件（冷啟動＋對帳時機）並未消除；
   下一次 daagent 被 kill 後再冷啟動是否重演刪除——**未知**，需後續觀察，
   且觀察本身有再次丟資料的風險（分身資料隨 `FULLY_REMOVED` 清除）。
3. **sticky／keep-last-good 是否擋得住此路徑（假說，待驗證）。**
   17:38:28 查過 `keep-last-good|Cleared|sticky`（`L154840`），但**輸出未收錄**；
   假說：sticky 或可覆蓋「回調被調用時」的競態；但本次時序（DELETE 18.974 早於首次回調 19.090
   約 116 ms）提示另一種可能——冷啟動對帳瞬間未等待 hook 回調即下達刪令，
   即使回調內容正確也未能阻止。此假說未驗證，需另案設計
   （例如開頁前預熱／對帳源頭核對，皆未驗證）。

---

## 6. 誠實標註（口述 vs 擷錄）

| 事實 | 等級 | 說明 |
|------|------|------|
| 上機三步發生過（`install -r` 保資料成功、force-stop daagent、使用者開頁） | 命令原文【口述】＋結果【擷錄旁證】 | host 側完整命令列未收錄；結果見 §1.1 引用的 `L128057`／`L128114`／`L129047`／`L153664` |
| 08:01 在裝、刪前 `installed=true`、owner 完好（`lastUpdateTime 2026-11-17`） | 【口述】 | 對應 `pm path`／`dumpsys` **輸出未收錄**（§1.2）；logcat 時間範圍亦不含 08:01 |
| 開關變 OFF（使用者確認非手動關閉） | 【口述】 | 無擷錄；旁證僅為刪除鏈本身 |
| 18:05 使用者切回 ON、可打開、不會崩潰 | 【口述】＋【log 旁證】 | 旁證：`timeline_1805_recovery.txt:L1`、`pm_path`／`dumpsys` 兩文件、crash 零命中；但**無恢復後新 log 擷錄**（logcat 止於 18:00:48） |
| `u95 installed=false`、`installed=false`＋`does not exist`、`FULLY_REMOVED` 鏈 | 【擷錄】 | `L216923`–`L216924`、`L215616`／`L216710`／`L217136`／`L218218` |
| `ceDataInode=-1（資料清）` | 【口述／敘事主幹原文】 | 全文 `grep ceDataInode` 零命中，無獨立 log 行；採 EVENT 說法但標明無二重證據 |
| 新包 sha `d7b60b0f…` | 【口述／敘事主幹原文，前綴】 | 全值未收錄；`L142345` 的 sha256sum **輸出未收錄** |
| crash 零、user150 初步零觸及待複核 | 本次寫檔前以 `grep -nE` 分開重跑的零命中【可重現】 | 搜尋式見 §3.5；`user150` 建議後續再以全字比對複核 |
| **無同時 `ps u95a431` 擷錄** | 明確缺失 | backup 內 `ps -A`（`L124729`／`L134717`／`L135995`／`L224935`）**輸出均未收錄**；分身進程存在性僅由 `Force removing ActivityRecord{… u95 com.openai.chatgpt/.MainActivity …}`（`L215296` 上下文）旁證 |

---

## 7. 原始檔清單（backup dir，未入 repo，供後續比對）

> 路徑：`/mnt/seagate500g/opencode_tmp/bbc_20261120/`（只讀引用）。

| 檔名 | 大小／行數 | 內容 |
|------|-----------|------|
| `EVENT_20261120_1753.md` | 1,583 B／19 行 | 敘事主幹（本文件 §2–§6 口述部分的源頭） |
| `logcat_20261120_1753.txt` | 36,955,348 B／249,674 行 | 全文 logcat（11-18 crash 段＋11-20 約 16:22→18:00:48）；本文 `L…` 行號皆指此檔 |
| `verbose_pid12644.log` | 670 B／3 行 | 新包三行（`installed=1`／`callback(nonempty)`／`merged +1 total 7`） |
| `timeline_1753_delete.txt` | 2,161 B／14 行 | 刪除鏈摘錄（§2 `T:L…`） |
| `timeline_1805_recovery.txt` | 126 B／1 行 | `18:05:46.447 START INSTALL-EXISTING … pkg{com.openai.chatgpt}` |
| `pm_path_u95_postrecovery.txt` | 436 B／4 行 | 恢復後 `pm path`（base＋3 splits，§3.1 逐字貼） |
| `dumpsys_u95_postrecovery.txt` | 398 B／6 行 | 恢復後 dumpsys（`installed=true`，`firstInstallTime=2026-11-20 18:05:46`，§3.1 逐字貼） |
| `old-base.apk` | 9,218,185 B | 被替換舊包；`sha256 = 953803c455cc17f1ad3cde70db8e072b03381c36540a407940ceb0d15f31496f`（本地唯讀重算） |

*（本文件結束；未 commit，留 untracked／working tree。）*

---

> 以下為 2026-11-21 追記（append-only；§0–§7 原文未動）。
> 追記時 repo：`unify/bbc-on-main @ e3953b56`（本文件頭註 `ae8db116` 為 11-20 寫檔時點，不改）。
> 本次未碰裝置（追記為離線寫檔）；不改其他檔；不 commit／不 push。PII 零入庫。

## 8. 11-21 BBC-ENTRY 重現：調用端落定（pid=30228）

### 8.1 重現操作日誌＋時序（時間為 UTC+08，11-21 凌晨）

上機三步（host 經 adb；命令原文與逐字輸出未收錄）【口述】：

| # | 動作 | 結果／旁證 |
|---|------|-----------|
| 1 | 備份現包（`before_repro.apk`） | 本地 sha256 `d7b60b0f8e22aa72c22e99089a55ec038f4293dff85746ba0ffb42723d0d84e6`（backup 實檔唯讀重算【可重現】） |
| 2 | `install -r` 新包（BBC-ENTRY 唯讀進入日誌版，`new-bbc-entry.apk`） | 本地 sha256 `e4fdc78fe86b7646bd0eb5c13a24a9d498cc238d75bb460ca138d234b1c2cb31`（backup 實檔唯讀重算【可重現】）；保資料（刪前分身在裝見 PHONE_C640 基線 §3：`firstInstallTime=2026-11-20 18:05:46`＋7 次完整 nonempty 三元組） |
| 3 | `force-stop com.samsung.android.da.daagent` | 舊 daagent 被殺；之後待開頁（未立即開頁）【口述】 |

開頁重現（使用者手持開 Dual Messenger／DualApp 頁）【口述】→ daagent 冷啟動，新 pid=**30228**：

> `Start proc 40.219` 起點無摘錄留存【口述，timeline 檔內無此行，僅作參考】；
> 以下毫秒時序凡標 `T:Ln` 者皆為【擷錄】，出自 `timeline_20261121_repro.txt` 對應行；
> 該檔共 21 行：`L1` 為 ssh 告警噪音，`L2`–`L16` 為事件摘錄，`L17`–`L21` 為事後唯讀驗證的 adbd 请求行（輸出未收錄）。

| 時間 | 事件 | 來源 |
|---|---|---|
| 03:26:40.219 | `Start proc …daagent`（新 pid=30228，冷啟動）【口述；無擷錄】 | —（無；事後 `L21` 的 `grep "Start proc.*daagent"` 请求行證明查過，輸出未收錄） |
| 03:26:40.566 | `BBC-HOOK installed=1 (daagent, pid=30228)`（`7614-1a1fa6cdba9-2-e`） | `T:L2`【擷錄】 |
| 03:26:40.693 | `START INSTALL-EXISTING userId{95} pkg{com.samsung.android.permissioncontroller}` | `T:L3`【擷錄】 |
| 03:26:40.697 | `START INSTALL-EXISTING userId{95} pkg{com.samsung.android.packageinstaller}` | `T:L4`【擷錄】 |
| 03:26:40.698 | `START INSTALL-EXISTING userId{95} pkg{com.android.permissioncontroller}` | `T:L5`【擷錄】 |
| **03:26:40.698** | **`START DELETE PACKAGE: observer{132051203}`**（摘錄內單次；本摘錄僅 1 行，`pkg{…}／caller{…}` 行未收錄於此檔，全量未收錄，不斷言唯一；刪除對象 chatgpt u95 以 FULLY 鏈 L10–L15 旁證＋【口述】） | `T:L6`【擷錄；摘錄內單次】 |
| 03:26:40.710 | `updateDualAppData …dataSize=1120` | `T:L7`【擷錄】 |
| 03:26:40.712 | `updateDualAppData …dataSize=284` | `T:L8`【擷錄】 |
| 03:26:40.811 | `updateDualAppData …dataSize=160` | `T:L9`【擷錄】 |
| 03:26:40.835 | `Intent delay: Delaying Broadcast: 1000ms, …PACKAGE_FULLY_REMOVED/u95`（入隊） | `T:L10`【擷錄】 |
| 03:26:40.842 | `BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=30228)`（`-2-15`） | `T:L11`【擷錄】 |
| 03:26:40.843 | `BBC-HOOK merged +1 packages (total 7, pid=30228)`（`-2-16`） | `T:L12`【擷錄】 |
| 03:26:41.210 | `RollbackManager: broadcast=ACTION_PACKAGE_FULLY_REMOVED pkg=com.openai.chatgpt` | `T:L13`【擷錄】 |
| 03:26:41.348 | `AppSearchManagerService: Handling …FULLY_REMOVED … com.openai.chatgpt` | `T:L14`【擷錄】 |
| 03:26:42.049 | `PersistedStoragePackageUninstalledReceiver: Received …FULLY_REMOVED for com.openai.chatgpt for u95`（鏈尾） | `T:L15`【擷錄】 |
| 03:26:50.459 | `…PACKAGE_FULLY_REMOVED, package = com.samsung.android.rubin.app, userId = 0`（他包噪音，非 chatgpt） | `T:L16`【擷錄；排除】 |
| 03:27:22–03:28:25 | 事後唯讀驗證 5 條 adbd 请求（`grep BBC-HOOK`／`DELETE／FULLY_REMOVED／chatgpt` 計數／`crash -b crash`／`REAL_DELETE／PKG_LINE／CALLER_LINE／START_PROC／INSTALLED_FALSE`） | `T:L17`–`L21`【擷錄請求行；輸出未收錄】 |

間隔（毫秒相減，±1ms）：

```
installed(40.566) → DELETE(40.698)   = +132ms
DELETE(40.698) → ENTRY(40.811)       = +113ms
DELETE(40.698) → callback(40.842)    = +144ms
ENTRY(40.811) → callback(40.842)     =  +31ms（同一次查詢的進入→返回）
callback(40.842) → merged(40.843)    =   +1ms
DELETE → FULLY_REMOVED 入隊(40.835)   = +137ms
DELETE → Rollback(41.210)            = +512ms → AppSearch(41.348) +650ms → PersistedStorage(42.049) +1351ms ≈ 1.35s（FULLY_REMOVED 完整鏈）
Start proc(40.219【口述】) → DELETE   = +479ms（起點無擷錄，僅供參考；見 §8.4 誠實附註）
```

### 8.2 ENTRY 行逐字引用＋解讀（調用端落定）

`verbose_bbcentry_20261121.log:L2` 逐字【擷錄】（`L1` 為 ssh 告警噪音，排除）：

```
[ 2026-11-21T03:26:40.842     1000: 30228: 30228 I/LSPosedFramework ] (com.samsung.android.da.daagent)[com.galaxydual.plus.debug,GalaxyDualHook,7614-1a1fa6cdba9-2-14,0,1] BBC-ENTRY ts=1795202800811 pid=30228 size=6 containsPolicy=false caller=VMStack#getThreadStackTrace <- k2#intercept <- m#proceed <- Heeck#getPossibleDualAppPackages <- DualAppActivity#onResume <- Instrumentation#callActivityOnResume <- Activity#performResume <- ActivityThread#performResumeActivity
```

解讀（每點標等級）：

- 調用端：`DualAppActivity#onResume → Heeck#getPossibleDualAppPackages`【擷錄】。
  `Heeck` 為混淆名，對應 hook 點 `PmWrapper#getPossibleDualAppPackages(Context)->ArrayList`
  （PLAN_CALLER_IDENTIFICATION.md §已知-1 同一 hook 點；PHONE_C640_DEEPDIVE.md §2.2 `BBC-SPIKE ok …PmWrapper#…` 同一真名）。
  此前猜測的 `DualAppActivity#getAvailableApps／#updateDualAppList` 在此 ROM 不存在
  （TIMELINE §7.1-F `not found; skipped`＋DEEPDIVE §2.3 pid=6288 復現）；本次 ENTRY 僅為刪後一次查詢的進入行，
  刪前是否查過另缺行證。
- 名單快照：`size=6 containsPolicy=false`【擷錄】——Samsung 傳入本次查詢的原始名單為 6 筆、**不含政策包**；
  hook 隨後 `merged +1 (total 7)`（`T:L12`）才補上政策包。`containsPolicy` 僅記布林值，不記包名原文。
- 時序含義：ENTRY（進入，40.811）晚於 DELETE（40.698）**+113ms**，
  callback（返回 nonempty，40.842）晚於 DELETE **+144ms**。
  刪除時刻 Samsung 依據的名單仍無行證；僅能說刪後這次查詢傳入為 6 筆自有名冊，不作刪除依據推斷。
- H1／H2：H1（開頁快照／不等回調即刪）支持增強，但刪前名單仍缺直接觀測，不轉正確認；
  H2（調用名猜錯＋首次查詢競態）缺口收窄但未關閉——調用端真名在本次刪後查詢中落定（`onResume` 鏈），
  但 DELETE 前是否查過／查到幾筆仍無行證。
  H3／H4／H5 維持 TIMELINE §7.3 原結論（本次 ENTRY 不解 Bundle Parcel、不碰 verify 歸屬、不新增第二次 DELETE）。

### 8.3 恢復記錄（使用者切ON；免重啟；crash 零）

| 時間 | 動作 | 結果 |
|---|---|---|
| 03:37:34 | 使用者（手持）在 Dual Messenger 頁把 ChatGPT 切回 **ON**（同意安裝）【口述】 | `START INSTALL-EXISTING userId{95} pkg{com.openai.chatgpt}`（時間點【口述】；log 行未收錄於 backup） |
| 恢復後 | `pm path --user 95 com.openai.chatgpt` | base＋3 splits 齊全【口述；逐字輸出未收錄】 |
| 恢復後 | 可打開、不崩；crash 緩衝區 chatgpt／FATAL 零命中 | 【口述＋命令旁證】：`T:L20` 有 `logcat -b crash … grep -i chatgpt/FATAL` 及計數請求行，但**輸出未收錄**，零命中為對話事實、無二重擷錄 |
| 恢復後 | 免重啟、無 SSAID 崩 | 【口述】；與 BBC6（11-18 重啟＋SSAID 崩）對照：本次與 11-20 兩次皆免重啟不崩 |
| 之後 | 停手，未再動（未再開頁重現、未再 toggle） | 【口述】 |

### 8.4 結論收斂

- **開頁 START→DELETE 間隔相近（366／372ms），n=2 不稱定時**：START 定義為 `ActivityTaskManager: START … DualAppActivity` 開頁行；
  兩減式為 BBC6 `17:31:32.586 → 17:31:32.952 = 366ms`、11-20 `17:53:18.602 → 17:53:18.974 = 372ms`；
  僅記間隔相近，樣本 n=2，不稱定時。11-21 開頁原點無擷錄（`Start proc 40.219` 為口述，算得 Start proc→DELETE +479ms，
  起點定義不同、基線不可比，不計入平均，僅列供參考）；DELETE→callback **144ms** 與前兩次（118ms／116ms）同方向、同量級（較前兩次大約大 28ms），模式未破。
- 三次 DELETE→callback 延遲 **118／116／144ms 同方向、同量級**（144ms 較前兩次大約大 28ms）：政策（空／非空／ENTRY-6筆）與回調內容（EMPTY／nonempty／nonempty）翻轉，
  延遲量級不動 → 刪除不依賴本次 hook 回調（TIMELINE §7 §判定-1 的第三次複現）。
- **Phase A sticky（未做對照）**：sticky／keep-last-good 讀取成功【口述】；與 sticky 值是否相關未做對照，不斷言無關（ENTRY／callback／merged 均正常落盤僅記現象）。
- 殘留問題轉 issue 追蹤：見 issue #75。候選項（不展開，僅佔位）：下次冷啟動復發風險與觀察代價；
  Bundle Parcel 仍未解碼（H3）；verify 無包名（H4）；H5 僅在「先回後刪」形態復活。

### 8.5 誠實標註（本追記專用；§6 原表不動）

| 事實 | 等級 | 說明 |
|---|---|---|
| 上機三步（備份／重裝 ENTRY 包／force-stop、待開頁、開頁重現） | 【口述】 | 命令原文與輸出未收錄；sha 以 backup 實檔重算補強（§8.1） |
| `Start proc 40.219`／`pkg{chatgpt},user{95},caller{1000}` 行（11-21） | 【口述】 | `T:L6` 僅 1 行 DELETE，無 pkg／caller 行；對象以 FULLY 鏈＋對話事實認定 |
| `T:L2`–`L16` 事件行、ENTRY `L2` 全文 | 【擷錄】 | 行號可復現（§8.1–§8.2） |
| 03:37:34 切ON、INSTALL-EXISTING、pm path 4 splits 齊、crash 零、可打開不崩、停手 | 【口述】（＋`T:L17`–`L21` 请求行旁證，輸出未收錄） | 無恢復後新 log 擷錄 |
| Phase A sticky 讀取成功 | 【口述】 | 無擷錄；與 sticky 值是否相關未做對照，不作相關性斷言 |
| 見 issue #75 | 佔位，號碼待填 | — |

## 9. 原始檔清單追記（backup dir，只讀引用；§7 原表不動）

| 檔名 | 大小／行數 | 內容 | sha256（實檔重算） |
|---|---|---|---|
| `verbose_bbcentry_20261121.log` | 550 B／2 行（L1 ssh 噪音＋L2 ENTRY 正文） | BBC-ENTRY 逐字行（§8.2） | —（短文本，不列 sha） |
| `timeline_20261121_repro.txt` | 4,344 B／21 行 | 11-21 重現窗口摘錄＋事後唯讀驗證请求行（§8.1） | —（短文本，不列 sha） |
| `new-bbc-entry.apk` | 9,121,361 B | 上機新包（ENTRY 唯讀進入日誌版） | `e4fdc78fe86b7646bd0eb5c13a24a9d498cc238d75bb460ca138d234b1c2cb31` |
| `before_repro.apk` | 9,121,361 B | 重裝前備份包 | `d7b60b0f8e22aa72c22e99089a55ec038f4293dff85746ba0ffb42723d0d84e6` |

*（追記結束；留 dirty 不 commit、不 push。）*
