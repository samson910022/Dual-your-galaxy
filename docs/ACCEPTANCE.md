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

## 6. BBC 注入驗收紀錄與重驗計畫（2026-11-17 雙裝置；2026-11-18 unified 重驗待辦）

> **provenance 聲明（先讀）**：2026-11-17 的 BBC 實測是在 **pre-unification build**（BBC 分支；政策 group `galaxydual_policy`／key `packages`，RemotePreferences listener 模型）上做的；unified tree（`unify/bbc-on-main`）已遷移為共享 `dual_policy`／`extra_whitelist_packages` → `EffectivePolicyCache`（60s TTL 輪詢／5s 失敗節流／single-flight／50ms stall 預算；跨行程 listener 不觸發，API 102）→ `BbcInjector.mergeAvailableList`（效應閘 `BBC_AVAILABLE_APPS_INJECTION=true`；Stage-1 `NON_EMPTY_POLICY_EFFECTIVE_GATE=false` 封緘不變）。BBC 合併演算法兩端逐字等價（BBC jar 於兩裝置 dexdump 比對，見 `docs/TAB7_BBC_INJECTION_PLAN.md` Addendum A／B），**證據不自動移植**：以下 BBC1–BBC7 的操作與預期可直接移植，但 reader 接線（寫入→hook 讀到）必須在 unified build 重新驗證（**Stage 8.3，S23 Ultra；BBC6 回滾必測**），本節不得挪用為 unified build 的通過證明；unified HEAD 之 provenance 將於 Stage 8.3 裝置驗證後補錄於本節。

> 裝置序列已遮蔽；路徑與截圖留本機。擷錄檔不入 repo。

| 欄位 | 值 |
| --- | --- |
| 驗收日期 | 2026-11-17（pre-unification build） |
| 版本 | `versionName=0.0.1` `versionCode=1`（pre-unification build） |
| 裝置 1 | Galaxy Tab S7（SM-T870，Android 13／One UI 5，DAAgent 3.3.00.0，LSPosed（Vector v2.2 API 102），user 0／95） |
| 裝置 2 | Galaxy S23 Ultra（SM-S9180，Android 17／One UI 9，DAAgent 3.7.00.0，Zygisk-LSPosed，KernelSU，user 0／95） |
| 政策（裝置本機） | 裝置 2：`com.openai.chatgpt`；裝置 1：`com.shopee.tw`／`org.mozilla.firefox`／`com.google.android.apps.bard`（Gemini） |
| 證據檔（本機，不入 repo） | `s23_bbc_evidence_20261117.log`（S23，LSPosed verbose mirror 擷錄；證據相對根 `/mnt/seagate500g/opencode_tmp/s23ultra/`）；`s23_bbc_gate_true.log`（S23，2 行 logcat corroboration）；`s4_gate_true_bbc.log`（Tab S7，logcat 直接擷錄 BBC-HOOK 行，相對根 `/mnt/seagate500g/opencode_tmp/galaxydual_tab7/`）；`s3_boot.txt:8`（Tab S7 基線重開機，`RemotePreferences initial load: 0 package(s)`，政策空 baseline） |
| 本 build 語義差異 | `galaxydual_policy/packages` + listener push（pre-unification）→ `dual_policy/extra_whitelist_packages` + `EffectivePolicyCache` TTL 輪詢（unified） |

| # | 操作 | 預期（unified build 同） | 實際（2026-11-17，pre-unification build；裝置 2 ／ 裝置 1） | 通過 |
| --- | --- | --- | --- | --- |
| BBC1 | daagent 啟動時 BBC hook 安裝 | `BBC-HOOK installed=1 (daagent, pid=…)` | ✅ 裝置 2：`BBC-HOOK installed=1 (daagent, pid=21472)`（verbose mirror）；✅ 裝置 1：`BBC-HOOK installed=1 (daagent, pid=7812／18538／21602)`（logcat） | ☑ |
| BBC2 | 空政策 | BBC callback 回傳 Samsung 原始物件（零行為變更；`BBC-HOOK callback` 不帶 `nonempty`、無 merged 行）；Samsung 清單不變 | ✅ 裝置 2：`RemotePreferences initial load: 0 package(s)` 基線回傳原始；裝置 1：`s3_boot.txt:8` 基線同 | ☑ |
| BBC3 | 勾選政策 app → 重進 Dual Messenger 頁 | 頁面出現該 app 列、可切換（`BBC-HOOK merged +N`／`merge added N`） | ✅ 裝置 2：`BBC-HOOK merged +1 packages (total 7, pid=21472)`，ChatGPT 出現在「應用程式」區；✅ 裝置 1：`BBC-HOOK merge added 0 packages (policy=3: …)`（Firefox/Bard 已在 Samsung 執行期白名單→union no-op；非失敗，merged 計數為 0 屬正確結論） | ☑ |
| BBC4 | 切換 ChatGPT toggle → 等待安裝 | user 95 安裝 dual APK、無重啟需求 | ✅ 裝置 2：`pm path --user 95 com.openai.chatgpt` 列出 base APK＋split APK（`s23_bbc_evidence_20261117.log` 同檔） | ☑ |
| BBC5 | launch 分身實例 | 雙開實例啟動、主 profile 並存 | ✅ 裝置 2：`ps` 同時見 `u0_a431`＋`u95_a431`（同檔） | ☑ |
| BBC6 | **回滾（un-tick）：全部政策取消勾選** →「寫入政策草稿（0）」→ 確認寫入 | (a) App 側 `HookPolicyStore` 整組取代為空（`RemotePreferences updated: 0 package(s)` 類似 log）；(b) daagent 下一次 `EffectivePolicyCache` poll（≤60s）或重啟後，BBC callback 帶空政策訊息（不帶 `nonempty`／無 merged 行，或 `merge added 0`）；(c) Samsung 設定頁 ChatGPT toggle 列消失，回到 Samsung 預設「可用的應用程式」；(d) 政策 UX 仍可再開關（再勾選 → 重寫 → ≤60s/重啟 → toggle 列重現） | **☐ 未測（unified build Stage 8.3 必測項目；pre-unification build 未執行）** | ☐ |
| BBC7 | Knox／Secure Folder（user 150） | 零觸及、零查詢 | ✅ 全程未查詢 user 150；`pm` 僅查 user 0／95 | ☑ |

> BBC6 重測步驟（S23 Ultra，Stage 8.3）：1) 政策編輯器取消 `com.openai.chatgpt` →「寫入政策草稿（0）」→ in-page 確認框「確認寫入」；2) App 側觀察 `remote preferences updated` 類 logcat 行（或重進頁面確認勾選狀態已清）；3) 等待 ≤60s（TTL poll）或使 daagent 重啟；4) verbose mirror grep `BBC-HOOK`：`BBC-HOOK callback getPossibleDualAppPackages (pid=…)` **不帶 `nonempty`**、無 `merged +N` 行；5) Samsung 設定頁 ChatGPT toggle 列消失回到 Samsung 預設；6) 政策 UX 仍可再開關（再勾選 →「寫入政策草稿（1）」→ 等待 ≤60s/重啟 → toggle 列重現）；7) 任何一步不符即 fail——**不得**宣稱回滾已驗證。BBC6 通過後才能把本列改為 ☑ 並註記 `unified build`；BBC6 回滾前的任何文字不得宣稱 "Rollback verified"。

擷錄指引（Stage 8.3 重驗時）：
- S23 Ultra：BBC-HOOK 行只出現在 LSPosed verbose mirror：`adb shell su -c 'grep -E "BBC-HOOK|BBC-SPIKE|RemotePreferences" /data/adb/lspd/log/verbose_*.log'`；logcat 僅見 `adb logcat -d -s GalaxyDualHook` 之 `RemotePreferences initial load: 0 package(s)`／`RemotePreferences updated: N package(s)` 兩行。
- Tab S7：`adb logcat -d -s GalaxyDualHook` 可直接看到 BBC-HOOK 行。
- Tab S7 基線：先確認政策空 baseline（`RemotePreferences initial load: 0 package(s)`）。
- 擷錄檔命名不含序號，存放於裝置證據目錄（本機 `/mnt/seagate500g/opencode_tmp/s23ultra/` 等），不入 repo；本節證據欄位僅記檔名與相對根。

殘餘未測（誠實揭露）：
- user 95 若由 ROM 指到其他 id，需以 `ps`/uid 前綴確認，勿假設 95。
- **BBC6 回滾未測**（Stage 8.3 必測；unified build 上執行，見上）。
- 2026-11-17 證據僅適用於 pre-unification build（`galaxydual_policy` group + listener push）；unified build 之 BBC reader 接線（`dual_policy/extra_whitelist_packages` → `EffectivePolicyCache` → BBC merge）**尚未裝置重驗**（Stage 8.3，S23 Ultra）。
- BBC 注入不觸及 mutation 面（目的明顯不同：清單可見性，而非安裝路徑放寬）；安裝路徑永不掛勾。
- unified build 若 BBC6 亦通過，應於本節補錄新日期／新證據檔名與 `unified=yes` 標記；在此之前本節所有 ☑ 僅代表 pre-unification build 行為。
