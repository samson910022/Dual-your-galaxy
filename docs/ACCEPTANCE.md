# ACCEPTANCE — 實機/模擬器驗收矩陣（Phase P29, issue #29）

> 定位：harness（`app/src/androidTest`）只保證
> **編譯通過 + 零真機寫入**；真正的裝置驗收一律走本文件的人工矩陣記錄。
> harness 測試於每次 PR 的 `locale-instrumentation` job（API 29 emulator）自動執行，
> 另於 `workflow_dispatch` 的 `instrumentation-manual` job（API 34 emulator）手動執行；
> PR CI 另跑 `:app:assembleDebugAndroidTest`（編譯）作為編譯閘門。

## 1. 版本錨定（每次驗收必填）

| 欄位 | 值 |
| --- | --- |
| source commit | `<git rev-parse HEAD>` |
| APK SHA256 | `<sha256sum app-debug.apk>`（**測試位元組證據必須來自 manual job 的 `tested-apk-provenance` artifact**；`apk-debug` artifact 僅為編譯產物，**不是** tested-byte 證據；無 emulator 時由本機 build 取得且須自行記錄 provenance） |
| 測試 APK（androidTest） | `<sha256sum ...-androidTest.apk>` |
| 裝置 / 模擬器 | 例：Pixel 8 API 34 emulator / S24 One UI 6.1 |
| 通道 | Auto / Root / Shizuku / LSPosed（實際以畫面「實際：」行為準） |
| 驗收人 / 日期 | |
| 已測 APK provenance | manual job 產出的 `tested-apk-provenance` artifact（`tested-apk-provenance.txt`：GITHUB_SHA、APK 路徑/SHA256、emulator API/profile、command、result） |
| artifact / run ID | `<workflow run id / artifact name>`（例：`instrumentation-manual` run #N 的 `tested-apk-provenance`） |
| artifact source commit | `<GITHUB_SHA from tested-apk-provenance.txt>` |
| installed APK SHA | `<APP_SHA256 from tested-apk-provenance.txt>`（實機安裝之 APK SHA，須與上列 APK SHA256 一致） |
| install result | 成功 / 失敗（含錯誤訊息） |
| versionCode / versionName | `<versionCode>` / `<versionName>`（須與 app/build.gradle.kts 一致） |

## 2. 操作矩陣

| # | 操作 | 預期 | 實際 | 通過 |
| --- | --- | --- | --- | --- |
| C1 | 開確認框後按取消 | pending 清除，零 uninstall，訊息「已取消移除」 | | ☐ |
| C2 | 開確認框後按返回鍵 | 同 C1（同走 `cancelRemove` 路徑） | | ☐ |
| R1 | 有 pending 時旋轉 | 對話框重顯，pending 保留，不重送 | | ☐ |
| R2 | 搜尋輸入後旋轉 | 同一 query 仍顯示 | | ☐ |
| R3 | 勾選後旋轉 | 同一選取保留 | | ☐ |
| B1 | 開確認框後退後台再返回 | pending 保留，不自動 confirm，零 uninstall | | ☐ |
| B2 | 前台連點重新整理 | 不疊加寫入，零 uninstall/install | | ☐ |
| I1 | 搜尋輸入 + 通道偏好行旋轉後渲染 | 輸入與通道行仍顯示 | | ☐ |
| W1 | 確認移除（真機專用，需明確授權） | 單次 uninstall，結果釘住 | | ☐ |

## 3. 未測項（誠實揭露）

- 例：LSPosed 通道未在真機驗證（`[待實機驗證]`）。
- 例：API 29 最低版模擬器矩陣本次未跑。
- Activity 層 rotation/background 檢查部分自動化（view 存在 + EditText 文字 + 對話框重顯 via `activity_recreate_retainsPendingDialog`）；選取恢復、back-press 仍為手動（見 §5）。
- 任何未執行的列在「實際」填 `未測`，不得留空冒充通過。

## 4. 脫敏聲明

- 本文件與診斷匯出只記錄**已遮蔽可辨識資料**（包名白名單、serial、路徑經遮蔽）。
- 分享前請人工確認無 IMEI / 序號 / 私有路徑 / 帳號外洩。
- 驗收截圖/日誌上傳前同樣先遮蔽，原文確認對話框（含完整包名）僅留本機核對，不得貼入公開 issue。

## 5. Harness 對照（免裝置可跑）

Harness 共 27 個自動化測試（`app/src/androidTest`，8 個檔案 / 7 個 test class：20 個 JUnit4 `@Test` + 7 個 JUnit3 `test*` 方法；`HarnessFakes.kt` 僅提供 fake，不含測試）。
其中 `LocaleResourceSelectionTest`（locale-aware remove-confirm 標題/訊息）與 `RemoveConfirmationUiTest`（ack 詳情/busy/替換 + blocked-channel gating）為輔助 harness，未對應單一人工列。
這些測試在每次 PR 的 `locale-instrumentation` job（API 29 emulator）自動執行，
並於 `workflow_dispatch` 的 `instrumentation-manual` job（API 34 emulator）手動執行；
並非僅手動執行。

| 人工列 | 對應 androidTest | 寫入保證 / 備註 |
| --- | --- | --- |
| C1 | `CancelZeroWriteTest`（cancel + cancel 後 confirm）+ `RemoveFlowTest`（NotInstalled/SerialUnavailable） | `FakeHarnessGateway.uninstallCalls == 0` 與 `writeCalls == 0`；C1 另斷言訊息「已取消移除：…」 |
| C2 | （uiautomator 已刻意省略；以 CancelZeroWriteTest cancel 路徑等價） | 等價性由程式碼/文件保證，無 back-press 自動化 |
| R1 | `RotationTest` VM 同實例 pending 保留 / 新實例 fail-closed；Activity `activity_recreate_retainsPendingDialog` 斷言 recreate 後「確認移除分身」對話框仍顯示 | fake 計數 + dialog assertion（對話框重顯已自動化） |
| R2 | `RotationTest`（query retained + Activity EditText 文字恢復） | EditText 文字斷言 |
| R3 | `RotationTest`（同實例選取保留）；Activity 層未自動化（partial） | fake 計數 |
| B1 | `BackgroundReturnTest`（pending 保留 + refresh 零 uninstall）；Activity 背景往返僅渲染（partial） | fake 計數 |
| B2 | `BackgroundReturnTest`（兩次 refresh 零 uninstall/install） | fake 計數；無快速連點自動化 |
| I1 | `InputRestoreTest`（query/選取保留 + Activity EditText 文字 + 通道行）；`activity_recreate_retainsNonDefaultChannelPreference`（ROOT）recreate 後仍顯示 | fake 計數 + EditText 文字 + 通道偏好（含非預設通道 recreate） |
| W1 | `RemoveFlowTest.confirmRemove_success_uninstallsOnce`（VM 半邊：單次 uninstall、結果釘住） | `uninstallCalls == 1`；真機仍須驗 W1 |

## 6. BBC 注入驗收紀錄（2026-11-17 雙裝置 pre-unification；2026-11-18 unified build 重驗）

> **provenance 聲明（先讀）**：2026-11-17 的 BBC 實測在 **pre-unification build**（BBC 分支；政策 group `galaxydual_policy`）上做；其改變的讀者模型**已不再實作**。unified tree（`unify/bbc-on-main`）已遷移為共享 `dual_policy/extra_whitelist_packages` → `EffectivePolicyCache`（60s TTL 輪詢）→ `BbcInjector.mergeAvailableList`（`BBC_AVAILABLE_APPS_INJECTION=true`；`NON_EMPTY_POLICY_EFFECTIVE_GATE=false` 封緘不變）。**2026-11-18 已於 S23 Ultra 以 unified build 完整重驗（BBC1–BBC3 重驗；BBC4/BBC5 於同 cycle 通過（2026-11-18 17:21/17:22 安裝 + UI，pid=3568）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載分身，資料清除），結論補錄如下；2026-11-17 段落作為 reference 保留。**

> **2026-11-20/11-21 更新（優先於本文 11-18 結論）**：非空政策下 daagent 冷啟動仍遭 Samsung 以 system uid 刪除 u95 分身，DELETE 早於 BBC 回調 116–144ms，toggle-ON 可恢復但資料全清；11-18「清空才刪」與「回傳位元一致＝零行為變更」不足以描述冷啟動風險。詳 `BBC_COLDSTART_DELETE_INCIDENT.md`、issue #75（issue #75 已於 2026-11-21 更正為非空回調；仍以 BBC_COLDSTART_DELETE_INCIDENT.md 為準）。本節所有 11-18 列（含 BBC6）皆受此更新節制。

> 裝置序列已遮蔽；路徑與截圖留本機。擷錄檔不入 repo。

### 8.3 紀錄（S23 Ultra only，unified build）

| 欄位 | 值 |
| --- | --- |
| 驗收日期 | 2026-11-18 |
| source commit | `3e9ccedee504e014b1e37d9e77446a81c4ae41b7`（unified tree，origin/main 7fd25d00 合併後；與 APK 完全一致） |
| APK（本機） | `app/build/outputs/apk/debug/app-debug.apk`（unified build）；sha256=`953803c455cc17f1ad3cde70db8e072b03381c36540a407940ceb0d15f31496f` |
| 版本 | `versionName=0.0.1` `versionCode=1` |
| 裝置 | Galaxy S23 Ultra（SM-S9180，Android 17／One UI 9，DAAgent 3.7.00.0，Zygisk-LSPosed，KernelSU，user 0／95） |
| 政策（裝置本機） | `com.openai.chatgpt`（unified 後重新勾選） |
| 證據檔（本機） | `s23_bbc_unified_20261118.log`（unified 全部 BBC-HOOK 行 + pm path + ps，相對根 `s23ultra/`）；`s23u_01_baseline.png`／`s23u_02_bbc3.png`／`s23u_03_bbc6_cleared.png`／`s23u_04_bbc6_restored.png` |

| # | 操作 | 預期 | 實際（2026-11-18，unified build） | 通過 |
| --- | --- | --- | --- | --- |
| BBC1 | daagent 啟動時 BBC hook 安裝 | `BBC-HOOK installed=1 (daagent, pid=…)` | ✅ `BBC-HOOK installed=1 (daagent, pid=29265)`（verbose mirror，force-stop daagent 後首次開頁） | ☑ |
| BBC2 | 空政策 | BBC callback 回傳物件與 Samsung 原始物件位元一致（僅指回傳物件，不代表裝置行為不變；冷啟動刪除見 incident） | ✅ `BBC-HOOK callback getPossibleDualAppPackages (pid=29265)`（不帶 `nonempty`／無 merged 行） | ☑ |
| BBC3 | 勾選政策 app → 重進頁面 | ChatGPT 出現在清單 | ✅ `BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=1519)`；**`BBC-HOOK merged +1 packages (total 7, pid=1519)`**；頁面截圖 `s23u_02_bbc3.png`（ChatGPT 列出現，可切換） | ☑ |
| BBC4 | 切換 ChatGPT toggle → 等待安裝 | user 95 安裝 dual APK、無重啟需求 | ⚠️ `pm path --user 95 com.openai.chatgpt` 於 2026-11-18 **17:56 的 `s23_bbc_unified_reenable_20261118.log`（本機）** 量測：含 base＋split APK 多行輸出（`# exit=0`）；此 log **未由本 repo 收錄**，不可獨立驗證，僅有裝置端 session 記錄相映；本節不再將之視為可重查證據 | ☑（caveat） |
| BBC5 | launch 分身實例 | 雙開實例啟動、主 profile 並存 | ⚠️ 進程案由 `INVESTIGATION_SSAID_BBC6.md`（unified build 於 17:21–17:30 確有 u95 分身進程且系統未強制其退出）；本節**沒有同時 `ps` 擷錄 U95 + U0 的證據**；`s23u_05_chatgpt_working.png`（22:31 截圖）顯示分身以第一次啟動歡迎畫面可見，無崩潰；不得稱證據 `u95_a431 pid=10165` — 該 pid 為 user 自陳、未收錄 log | ☑（caveat） |
| BBC6 | **破壞性卸載（不是回滾）：清空政策／冷啟動 deletion（資料全清）** | **11-18 觀察**：空政策 daagent 冷啟動與 `START DELETE PACKAGE (caller{1000})` 同時出現（11-18 BBC1 pid=29265 政策空；11-17 pre-unification BBC1 pid=21472 政策空）。**因果鏈已撤回**：原主張「空政策 → possible-list 空 → Samsung 刪除」被 2026-11-20/11-21 反證——兩次皆在非空政策（`nonempty` + `merged +1 total 7`）下遭系統 uid 刪除，且 DELETE 早於 BBC 回調 116–144ms；刪除觸發機制未證實（見 `BBC_COLDSTART_DELETE_INCIDENT.md` §5/§8）。**恢復**：toggle-ON 重裝即可（11-20/11-21 免重啟）；11-18 當次另以 `adb reboot` + Samsung「設定→雙開應用」切回 ON，22:30 **使用者回報**正常（`u95_a431 pid=10165` 為口述、未收錄；截圖 `s23u_05_chatgpt_working.png`）；資料全清、無法復原。 | ❌ 不通過、不得標 ☑；BBC6 屬破壞性指令；詳 `BBC6_SSAID_INCIDENT.md` 與 `BBC_COLDSTART_DELETE_INCIDENT.md` §8 |
| BBC7 | Knox／Secure Folder（user 150） | 零觸及、零查詢 | ✅ 全程未查詢 user 150；所有 `pm` 查詢僅 user 0／95 | ☑ |

BBC6 注意事項（unified build）：
- 空政策清除的正規流程是**主畫面「清除草稿」**；編輯器「寫入政策草稿（N）」在 N=0 時被 VM 拒絕（CATALOG_MISSING 分支，目前僅顯示 VM 訊息，無確認對話框）。
- BBC6 不是回滾——是破壞性的系統刪除分身。11-18 觀察到其後伴隨政策清空；但 11-20/11-21 在不清空政策（非空）且冷啟動時亦遭刪除，故「清除政策＝刪除觸發」的因果已撤回，觸發機制未證實。見 `BBC6_SSAID_INCIDENT.md` 與 `BBC_COLDSTART_DELETE_INCIDENT.md` §5/§8。
- **恢復**：toggle-ON 重裝即可；11-18 當次另以裝置 `adb reboot` → Samsung「設定→雙開應用」對目標 app 切回 ON → Samsung 重新安裝；**使用者回報**分身正常（pid 未收錄於 repo 擷錄）；BBC3 merged +1 生效與否亦以使用者回報為準。資料全清、無法復原。
- 觀察到 `PACKAGE_FULLY_REMOVED`（`START DELETE PACKAGE`）伴隨 deletion；恢復用 Samsung 原生 toggle-on 即可（11-20/11-21 免重啟）；不必、也不應嘗試 ABX 修補 ssaid.xml（見 incident）。此為 correlated observation，非觸發因果證明。

### 11-20/11-21 破壞性 fault matrix（誠實揭露；非通過宣稱）

> 本表為 11-20/11-21 事件後補錄的 fault matrix，列出**未對照／未證實**的破壞性路徑；不得解讀為任何一列已通過驗收。

| 情境 | 設計意圖 | 實測／對照狀態 |
| --- | --- | --- |
| `BBC_STICKY_MAX_AGE_MS`（30 分鐘）到期 | fail-closed 回空，停止沿用 last-good，為**有界降解視窗**上限 | 此上限與冷啟動 deletion 無對照證據（DELETE 早於回調 116–144ms 且回調非空，sticky 路徑無對照；見 `BBC_COLDSTART_DELETE_INCIDENT.md` §8.4）；**不是**冷啟動刪除的結構性修正。 |
| daagent 冷啟動（force-stop 後開 DualAppActivity） | BBC 回調應先於任何 deletion 判定 | 11-18/11-20/11-21 皆出現 deletion；11-20/11-21 於**非空政策**（`nonempty` + `merged +1 total 7`）下 DELETE 早於回調 116/144ms；觸發機制未證實，見 incident §2/§8。 |
| LSPosed daemon／root daemon 失去同步（底層 `mMap` 陳舊） | 60s TTL 到期重讀應見新值 | API 102 無跨行程推送；到期重讀的是同一永久快取 `RemotePreferences` 實例，`mMap` 可能仍陳舊，不保證下一次 poll 看到新集合；重啟 hook 行程後才保證。見 `docs/HOOK.md` §1.1、`docs/USAGE.md` 政策 TTL 對照表。 |
| 政策清空／非空冷 deletion 造成 user-95 資料全清 | 清除前 Root probe 列出分身並警示 | 11-18/11-20/11-21 皆發生 deletion 且資料清除；toggle-ON 可重裝但資料無法復原；非可回滾操作。 |
| BBC hot path 於政策規模 1/50/200/512（hook on/off）的量測 | 合併應為有界、線性、不阻塞 DAAgent UI | **UNMEASURED／需裝置 trace**：從未在裝置上量測 `pm.getApplicationInfo` 等 Binder IPC 次數或 DAAgent UI 延遲。`BbcMergeTimingTest` 僅鎖「純內存合併」工作量與 2s 寬鬆上界；once-per-PID stack-trace 捕獲修正僅 JVM 測試覆蓋，真實 IPC 成本未量測。**不得**據此宣稱「50ms 內完成／1/50/200/512 無 ANR」；本列不是通過項。 |
| BBC 版本號 wraparound 重播視窗（~9.22e18 次寫入；`bbcVersionWraparoundReopensReplayWindowDocumentedBbc`） | 版本鍵固定格式、無 epoch；`Good(MAX)`→`Good(1)` 後 high-water 降到 1，中間範圍重播 `Good(500)` 被接受 | **已文件化限制／接受（非通過項）**：需約 9.22e18 次提交寫入才可能達成，實務不可及；未來若加入 epoch 方案，該測試應改為預期 Unknown。見 hook `BbcStickyPolicyTest.bbcVersionWraparoundReopensReplayWindowDocumentedBbc` 與 `EffectivePolicyCache.isBbcVersionWraparound`。本列為已知限制，不是驗收通過。 |

擷錄指引：
- S23 Ultra：BBC-HOOK 行只出現在 LSPosed verbose mirror：`adb shell su -c 'grep -E "BBC-HOOK|BBC-SPIKE|RemotePreferences" /data/adb/lspd/log/verbose_*.log'`；logcat 僅見 `adb logcat -d -s GalaxyDualHook`（unified build 不再印 `RemotePreferences initial load/updated`，pool/TTL 模式無此行）。
- 擷錄檔命名不含序號，存放於本機裝置證據目錄（私有，不入 repo），不入 repo；本節證據欄位僅記檔名。

### 2026-11-17 紀錄（pre-unification build，reference）

provenance：pre-unification build 之 BBC 實測——政策 group `galaxydual_policy`、RemotePreferences listener push 模型；其合併演算法與 unified 逐字等價，故實證兩者成果可相互對照（但不得挪用為 unified 的通過證明）。

> 裝置序列已遮蔽；路徑與截圖留本機。

| 欄位 | 值 |
| --- | --- |
| 驗收日期 | 2026-11-17（pre-unification build） |
| 版本 | `versionName=0.0.1` `versionCode=1`（pre-unification build） |
| 裝置 1 | Tab S7（SM-T870，Android 13／One UI 5，DAAgent 3.3.00.0，LSPosed（Vector v2.2 API 102），user 0／95） |
| 裝置 2 | S23 Ultra（SM-S9180，Android 17／One UI 9，DAAgent 3.7.00.0，Zygisk-LSPosed，KernelSU，user 0／95） |
| 政策（裝置本機） | 裝置 2：`com.openai.chatgpt`；裝置 1：`com.shopee.tw`／`org.mozilla.firefox`／`com.google.android.apps.bard`（Gemini） |
| 證據檔 | `s23_bbc_evidence_20261117.log`（S23）；`s4_gate_true_bbc.log`（Tab S7）；`s3_boot.txt:8`（Tab S7 政策空基線） |

| # | 操作 | 實際（裝置 2 ／ 裝置 1） | 通過 |
| --- | --- | --- | --- |
| BBC1 | BBC hook 安裝 | ✅ 2：`installed=1 (pid=21472)`（verbose mirror）；✅ 1：`installed=1 (pid=7812／18538／21602)`（logcat） | ☑ |
| BBC2 | 政策空 | ✅ 2：`initial load: 0 package(s)` 基線；✅ 1：`s3_boot.txt:8` | ☑ |
| BBC3 | 勾選→重進頁 | ✅ 2：`merged +1 packages (total 7, pid=21472)`、ChatGPT 出現；✅ 1：`merge added 0 packages (policy=3:…)`（Firefox/Bard 已在 Samsung 執行期白名單 → union no-op，非失敗） | ☑ |
| BBC4 | 切換→安裝 | ✅ 2：`pm path --user 95 com.openai.chatgpt` 回應 dual APK paths | ☑ |
| BBC5 | launch 分身 | ✅ 2：`ps` 同時見 u0_a431＋u95_a431 | ☑ |
| BBC6 | 破壞性卸載（不是回滾） | ❌ 不得通過（見 BBC6_SSAID_INCIDENT.md） | ❌ |
| BBC7 | Knox／Secure Folder（user 150） | ✅ 全程未查詢 user 150；`pm` 僅查 user 0／95 | ☑ |

附記（2026-11-17，因未持久化故更正）：Tab S7 之 Firefox 雙開 session 觀察存在（`pm path --user 95` 回應 dual APK paths 見於當次 session；`screencap 11_firefox_dual.png` 為 Firefox first-run 畫面），**但未持久化為檔案：無 `u95_a306` 或任何 u95_* 進程持久證據，一律不得引用**；BBC 注入不觸及 mutation 面（清單可見性）；安裝路徑永不掛勾。

殘餘未測（誠實揭露）：
- user 95 若由 ROM 指到其他 id，需以 `ps`/uid 前綴確認，勿假設 95。
- 連續 2 次重開機 + `logcat -b crash | grep -i galaxydual` 空 → 未測。
- BBC6 unified build 已測（2026-11-18，見上——結果為分身被卸載且 app 無法啟動）。
