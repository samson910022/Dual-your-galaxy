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

### 11-21／11-22 round-2+3 待實機驗收（UNMEASURED／NOT a pass／不得據此合併）

> **免責框架（先讀）**：本節所有列在產生實機證據前，一律標記為 **UNMEASURED／NOT a pass**；「已文件化」與「JVM／CI 全綠」皆**不是**通過。本輪獨立審查環境未執行 Gradle 裝置測試；2026-11-23 曾在 Samsung 裝置做**唯讀**觀察（未執行任何破壞性臂／冷啟動）。凡涉及分身刪除或 user-95 資料清除的步驟，必須在**明確授權且已備份**的裝置上、由持有人親自執行；未經授權**不得重跑破壞性實驗**（issue #75）。本節不得解讀為圓滿驗收，亦不得據以宣稱 P0 風險已關閉。

| 項目 | 狀態（round 2+3） | 未完成之必要裝置證據 |
| --- | --- | --- |
| N01／D01 P0 冷啟動 DELETE（default BBC） | **UNMEASURED／NOT a pass**；causal 機制未證實；runtime opt-out 非刪除防護；round-3 D02–D07 為 JVM／程式碼修正，未關閉此 P0 合併門檻 | 同基線三臂對照（見下）；trace 系統端 pre-DELETE 決策輸入；DELETE 與 callback 相對時序 |
| N08／D08 BBC hot path 1/50/200/512 | **UNMEASURED／NOT a pass**；`BbcMergeTimingTest` 僅量純內存合併 | wall-time p50/p95/max 與 Binder IPC 次數（含 cold process）；定義有界 fallback 與 measured go/no-go 閾值 |
| N09／D08 sticky 30 分鐘到期／fresh DAAgent 行程 | **UNMEASURED／NOT a pass**；設計為有界降解，非安全保證 | force-stop／重啟／>30 min 持續失敗後，分別觀測 available-list 與 user/profile 資料狀態 |
| D06 gate wall-time | **UNMEASURED／NOT a pass** | 阻塞 provider >200ms 時主線路返回上界（JVM 已證有界）、真實 Binder 延遲、DAAgent UI/ANR 未量測 |
| D08 go/no-go | **UNMEASURED／NOT a pass** | policy 1/50/200/512 p50/p95/max、Binder IPC 次數（含 cold process）、量測門檻定義 |
| D07 version repair | 文件化限制／NOT a pass | Invalid→v1 修復在已接受較高版本的 Hook 行程被單調門控拒收，需重啟 Hook 行程；App save 成功不證明已生效；UNMEASURED |

**N01 必要三臂同基線對照（缺一不可）**：在**同一台已備份且明確授權**的 Samsung 裝置、同一 user-95 app installed-state 起點，分別量測：
- **A 臂｜default BBC**：`BBC_AVAILABLE_APPS_INJECTION=true` 且 runtime gate `dual_policy/bbc_injection_enabled` 維持預設 ON；
- **B 臂｜confirmed-off runtime gate**：`dual_policy/bbc_injection_enabled=false`，且已確認 hook 行程確實讀到新值（RemotePreferences 無跨行程推送，可能需重啟 hook 行程／開機）；
- **C 臂｜Samsung baseline**：未安裝本模組，或本模組完全停用且未注入（Samsung 原生行為）。

每臂皆須記錄：daagent cold-start 後是否出現 `START DELETE PACKAGE`（caller uid）、DELETE 與 `BBC-HOOK callback` 的相對時序與回調是否非空、available-list 內容、分身是否存在、user-95 資料是否存活。**同一 installed-state 基線**是比較前提；無此三臂對照，不得宣稱任一臂安全、亦不得宣稱因果成立。B 臂的「OFF」在 round 2 N06 之後為「最後一次已確認 OFF；後續讀取失敗沿用」，但此語意本身**仍須實機驗證**。

> **round-3 更新（2026-11-22，D01–D08）**：本輪僅完成程式碼／文件修正（D02–D07 為 JVM／控制流程可推導缺陷的修正；D01、D06 裝置面、D08 仍為裝置驗收項目）。**P0 合併門檻（D01＝N01）維持開啟、未關閉**；D02–D07 即使 JVM／CI 全綠，**也不代表任何裝置閘門通過**。D06 的 opt-out gate 讀取雖已有 dedicated bounded executor（JVM 證明 wall-time 上界），真實 Binder 延遲與 DAAgent UI 延遲仍 **UNMEASURED／NOT a pass**；D08 的 512-entry IPC 耗時（1/50/200/512 的 p50/p95/max）、Binder 次數、30 分鐘 sticky 到期與 fresh process fallback 仍 **UNMEASURED／NOT a pass**。三臂同基線對照（default BBC／confirmed-off runtime gate／Samsung baseline）仍是 N01 的必要證據，缺一不得宣稱安全或因果成立。凡涉及分身刪除或 user-95 資料清除的步驟，須在**已備份且明確授權、持有人同意**的裝置上親自執行；未經授權不得重跑破壞性實驗（issue #75）。runtime opt-out **不是**刪除防護。

> **round-4 更新（2026-11-22，E00–E08）**：本輪僅完成 E01–E07 的程式碼／UI 契約修正（E01/E02 政策操作流程、E03 CancellationException 透傳、E04 toggle 的 Unknown→Confirmed UI 恢復、E05 探測失敗對話框生命週期 handle、E06 VM 生產寫入 seam 強制非 null consent、E07 Invalid→v1 需重啟的 UI 提示）；epoch/generation 修復不在本輪範圍。**E00（P0 冷啟動 DELETE）與 E08（512 條目 IPC／15ms 真實 Binder 延遲／sticky 30 分鐘／Scope A dialog）維持 UNMEASURED／NOT a pass，三臂同基線對照要求與備份＋授權前提完全不變**；不得因 E01–E07 的 JVM／UI 修正而宣稱任何裝置閘門通過。E07 的 UI 提示僅為誠實告知（修復可能尚未生效、需重新啟動 Hook 行程後驗證、不代表新政策已套用），不代表跨行程立即生效。**opt-out is NOT a deletion guard**（issue #75）；runtime opt-out 仍非刪除防護。

> **round-5 更新（2026-11-23，F00–F06；更新而非取代 round-2+3+4）**：本輪 F01–F04 僅為 JVM／UI 契約修正（F01 Unknown 警告的重新整理改為權威 `refreshPolicyDraft()` 並 await、盲清正式撤回停用；F02 修復提示 app-scoped 跨 VM 在 Main＋Editor 可見；F03 繼承 `Confirmed(value)` 時同步 CheckBox；F04 VM 對非 null consent 重算移除 delta）。**F00（＝D01/N01 P0 冷啟動 DELETE）與 F06（＝D06 gate 延遲／D08 512-entry IPC／sticky／Scope A dialog）維持 UNMEASURED／NOT a pass**；P0 合併門檻未關閉。**三臂同基線對照（default BBC／confirmed-off runtime gate／Samsung baseline）與備份＋明確授權＋持有人同意前提完全不變**；不得因 JVM／UI 全綠而宣稱任何裝置閘門通過。**opt-out is NOT a deletion guard**（issue #75）。

**Scope A dialog（issue #73）**：PreClear warning dialog 從未在裝置上觸發或擷錄，維持 **UNMEASURED／NOT a pass／device verification pending（backup+consent）**。

擷錄指引：
- S23 Ultra：BBC-HOOK 行只出現在 LSPosed verbose mirror：`adb shell su -c 'grep -E "BBC-HOOK|BBC-SPIKE|RemotePreferences" /data/adb/lspd/log/verbose_*.log'`；logcat 僅見 `adb logcat -d -s GalaxyDualHook`（unified build 不再印 `RemotePreferences initial load/updated`，pool/TTL 模式無此行）。
- 擷錄檔命名不含序號，存放於本機裝置證據目錄（私有，不入 repo），不入 repo；本節證據欄位僅記檔名。

### 2026-11-23 實機唯讀觀察（F00/F06 仍 UNMEASURED／NOT a pass）

> **誠實框架（先讀）**：本觀察為 round-5 的**唯讀**實機記錄，不是任何驗收通過。持有人於 G0 已備份並授權完整三臂（含破壞性冷啟動 DELETE 重現），但在最終閘門改口「只記錄，停止破壞性臂」；G1（force-stop ＋ daagent 冷啟動）**從未獲批**。executor 因此**未執行任何破壞性臂**，三臂 A/B/C 冷啟動對照**未執行**。F00／F06 維持 **UNMEASURED／NOT a pass**，P0 合併門檻未關閉；**opt-out 不是刪除防護**（issue #75）。

- 裝置／環境：SM-S9180（S23 Ultra）、Android 17／SDK 37、KernelSU root、LSPosed active。全程僅唯讀 `pm`／`dumpsys`／`ps`／`logcat`／`su -c cat`／`screencap`，另有一次非破壞性的 App 啟動與 About 導航點擊（無寫入／無破壞性臂）；未查詢 user 150、未 force-stop／冷啟動／重開機、未寫政策或 DB；**Scope B `settings_ssaid.xml`／ABX／SSSAID 修補禁止、未嘗試**。
- 政策狀態（LSPosed remote-prefs DB，唯讀）：非空 BBC 政策**已提交**（`dual_policy/extra_whitelist_packages` ＝ `{com.openai.chatgpt}`；另有 `galaxydual_policy/packages`）。執行期閘鍵 `bbc_injection_enabled` **缺席** → 依預設推斷為 **ON**；此為**由持久化狀態推斷，非 UI 確認**（「關於」對話框未抵達，toggle 狀態 UNMEASURED）。
- u95 ChatGPT 分身：4 個 APK 路徑、`installed=true`、`ceDataInode=631837`、`firstInstallTime=2026-11-21 03:37:34`，但**已安裝卻故障**：啟動即崩潰 `IllegalStateException: Ssaid settings not accessible`（`SettingsProvider.generateSsaidLocked`）；持有人回報即使經 Samsung Dual Messenger toggle OFF→ON 重裝仍崩潰。資料大小 **UNMEASURED**（即使 `su -c` 下 `du`／`ls` 仍 Permission denied）。此 SSAID 崩潰與既有 incident 記錄一致，但**不證明亦不關閉 P0 因果問題**；根因位於硬禁止的 SSAID 區，**Scope B 修補禁止、未嘗試**。
- F00（P0 冷啟動 DELETE）：**UNMEASURED** — 無冷啟動、無 DELETE chain／caller uid／時序／分身與資料存活數據。F06：**UNMEASURED** — 無延遲／IPC／sticky／Scope A 量測。三臂同基線對照（default BBC／confirmed-off runtime gate／Samsung baseline）仍是必要證據，缺一不可。
- 證據（**不入 repo**，依本 repo 慣例）：目錄 `/mnt/seagate500g/opencode_tmp/device_evidence/`，共 54 檔（53 檔附 SHA-256 ＋ MANIFEST 自身）、約 81 MiB，含完整 LSPosed verbose log（181 行 BBC）、約 37 MB logcat dump（含崩潰堆疊）、截圖與 DB 快照；完整清單與每檔 SHA-256 見外部 `MANIFEST.md`，原始記錄為同目錄 `FINAL_device_observation.md`。

#### 觸發場景分析（非根因；信心分級）

> **定位**：以下為觸發場景重建（correlated observation／排除法），**不是根因證明**。SSAID 根因位於 Scope B 硬禁止區，本輪未讀、未修補；本分析亦**不關閉 P0 因果問題**。

- **一句話**：崩潰事实為 **CONFIRMED**；「崩潰與系統有無自動解除安裝是兩條不同的軸」為**推論（LIKELY）**、非直接量測。觸發為 u95 分身（uid 9510431／`effectiveUid=9510431`）啟動時讀 `android_id`，`SettingsProvider.generateSsaidLocked` 拋 `IllegalStateException: Ssaid settings not accessible`；4/4 次啟動崩潰（01:45:45、01:45:47、01:48:23、01:50:02；pid 7130／7425／8240／9082），u0 本體（pid 13642）正常。

- **再現場景（preconditions → action → failure）**：
  - 前提：u95 clone 已安裝（4 APK path、`installed=true`、data dir 在、`firstInstallTime=2026-11-21 03:37:34`）；自 11-18 22:25 無 reboot；非空政策 ＋ hook 正常（`installed=1`、nonempty、merged +1）——後兩者與崩潰**無 differential**。
  - 動作：啟動 u95 clone → app 讀 `Settings.Secure.getString(ANDROID_ID)`。
  - 失敗：`SettingsProvider.call → getSecureSetting → getSsaidSettingLocked → generateSsaidLocked(:4653)` 拋錯 → `Force finishing activity` → 崩潰。

- **關鍵對比（直接回答 owner 問題：「過去系統自動解除安裝 → toggle ON 重裝就好；這次沒有自動清除但 app 打不開」）**：
  - **本輪 DELETE 是人為，不是系統自動（CONFIRMED）**：01:49:52 的 `START DELETE PACKAGE … user{95} caller{1000}` 是持有人**手動把 Samsung「雙開應用」開關切 OFF** 造成的，01:49:56 的 `START INSTALL-EXISTING` 是隨後**手動切 ON 重裝**；**不是** Samsung 自動冷啟動對帳。G1／daagent 冷啟動從未核准，本輪**既未觀察到、也未測試、也未反證**系統自動 DELETE，該路徑維持 UNMEASURED（見上 F00）。
  - **與歷史路徑不可直接比較**：owner 問題中「過去系統自動刪 → toggle ON 重裝就好」是**自動清除**路徑；本輪是**人為 DELETE → toggle ON 重裝**，兩者不是同一路徑，不能拿來對照。崩潰在**不同的軸**（SSAID provisioning），且於人為 DELETE＋重裝後仍存在。
  - **就連人為完整 DELETE＋重裝都清不掉（CONFIRMED，log 旁證）**：`01:50:02` 全新 pid 9082 啟動仍崩。這是比「單純 toggle 重裝無效」更強的證據，且**獨立於尚未解決的冷啟動 DELETE 問題**。
  - 歷史「重裝就好」本身也不可靠：11-18 當天 17:55 立即重裝也崩（pid 15625 起），是後來 **reboot ＋ 22:26 toggle** 才恢復（唯一有 artifact 的恢復）；11-20/21「不崩」僅口述、無重裝後 log，而現在崩的正是 11-21 03:37 裝的那個。**reboot 是唯一有 artifact 的恢復要素（LIKELY 相關、因果 UNKNOWN）**；非空政策不是 DELETE 防護（11-20/21 非空仍被刪，DELETE 早於 callback 116–144ms）。
  - 持有人回憶（**UNKNOWN，文件無記錄**）：過去「系統自動移除／關閉 → 再次重 hook → 重開系統雙開開關 → 可正常啟動」的程序中，持有人記得有「重 hook」一步，但無法確認具體操作；該次觀察階段**確定沒有重開機**。repo 事故文件唯一有 device artifact 的恢復要素是 11-18 的 **reboot + toggle ON**；此回憶不作為已證事實，也不改變上述信心分級。
  - **2026-11-23 新增觀察（CONFIRMED，唯讀）**：持有人其後對裝置做**軟重開機**，出現「密碼正確仍無法進桌面」；logcat 顯示 `LockSettingsService.doVerifyCredential → SyntheticPasswordManager.unwrapSyntheticPasswordBlob` 因 `UnrecoverableKeyException`（Keystore code 4）失敗，`com.android.systemui` 連續 4 次 crash，底層 keymint 反覆報 `km_check_key_os_version_patchlevels failed / Failed to deserialize key`。持有人接著做**完整硬重開機**後，u0/u95 皆 `deviceLocked=0`、Launcher 在前景、最近 5000 行 log 無上述 keymint／SSAID／SystemUI crash —— **密碼驗證與桌面恢復（觀察，非根因證明、非永久修復）**。重開後**未重新啟動 u95 分身**，故其 SSAID 崩潰狀態仍 **UNMEASURED／未關閉**；亦未執行任何冷啟動 DELETE。證據：`/mnt/seagate500g/opencode_tmp/device_evidence/r5_reboot_recovery_record.md`（不入 repo）。

- **與 PR #74 關係（CONFIRMED）**：`.kt` 原始碼中 `Settings.Secure`／`SSAID`／`android_id`／`generateSsaid` **0 命中**；LSPosed scope 只含 `system;com.samsung.android.da.daagent`（明確排除 `com.android.settings`）；BBC 只對 daagent 唯讀清單做 union-only append；logcat `avc: denied` **0 命中**。PR 沒有任何路徑造成或惡化此 SSAID 失敗。

- **信心分級表**：

  | 等級 | 項目 |
  | --- | --- |
  | **CONFIRMED** | 崩潰觸發（SSAID 讀取失敗／4/4 啟動）／人為完整 DELETE＋重裝後仍崩／01:49:52 DELETE 是人為非系統自動／PR 無因果路徑 |
  | **LIKELY** | 壞層在 u95 雙開供給層（排除法＋11-18 dump）／reboot 是恢復要素 |
  | **HYPOTHESIS** | SSAID registry 鍵配置（11-18 dump 理論，未重讀）／Samsung 只對白名單雙開 app 播種 |
  | **UNKNOWN** | 11-21→11-23 狀態變化／現在冷啟動是否 DELETE／SSAID 內部狀態（Scope B 硬禁令，未讀） |

- **Hard prohibition 重申**：不提出任何 SSAID／`settings_ssaid.xml`／ABX 修補；不宣稱 P0 根因已證明；opt-out **不是**刪除防護（issue #75）。

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
