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
