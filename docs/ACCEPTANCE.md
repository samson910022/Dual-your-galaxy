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

## 6. BBC 注入與非空政策驗收紀錄（2026-11-17，雙裝置）

> 這是一組**已完成的裝置人工矩陣**（非 harness）。裝置序列已遮蔽；路徑與截圖留本機。

| 欄位 | 值 |
| --- | --- |
| source commit | `45a40e67`（含 gate=true 預設，含 §1.4 文件同步；含 Stage 4 picker 修正） |
| 版本 | `versionName=0.0.1` `versionCode=1` |
| 裝置 1 | Galaxy Tab S7（SM-T870，Android 13／One UI 5，DAAgent 3.3.00.0，LSPosed（Vector v2.2 API 102），user 0／95） |
| 裝置 2 | Galaxy S23 Ultra（SM-S9180，Android 17／One UI 9，DAAgent 3.7.00.0，Zygisk-LSPosed，KernelSU，user 0／95） |
| 政策（device local） | 裝置 2：`com.openai.chatgpt`；裝置 1：`com.shopee.tw`／`org.mozilla.firefox`／`com.google.android.apps.bard`（Gemini） |

| # | 操作 | 預期 | 實際（裝置 2 / 裝置 1） | 通過 |
| --- | --- | --- | --- | --- |
| BBC1 | daagent 啟動時 BBC hook 安裝 | `BBC-HOOK installed=1 (daagent, pid=…)` | ✅ 兩台皆觀察到 | ☑ |
| BBC2 | 空政策 | BBC callback 回傳 Samsung 原始物件（零行為變更） | ✅ 裝置 2（initial load: 0）回傳原始；無 merged 行 | ☑ |
| BBC3 | 勾選政策 app → 重進 Dual Messenger 頁 | 頁面出現該 app 列、可切換（BBC-HOOK merged +N） | ✅ 裝置 2：`merged +1 (total 7, pid=21472)`，ChatGPT 出現在「應用程式」區；✅ 裝置 1：`merged +0`（Firefox/Bard 已在 Samsung 執行期白名單→union no-op，非失敗） | ☑ |
| BBC4 | 切換 ChatGPT toggle → 等待安裝 | user 95 安裝 dual APK、無重啟需求 | ✅ 裝置 2：`pm path --user 95 com.openai.chatgpt` 列出 base/APK 路徑 | ☑ |
| BBC5 | launch 分身實例 | 雙開實例啟動、主 space 並存 | ✅ 裝置 2：`ps` 同時見 u0_a431＋u95_a431 `com.openai.chatgpt`（並確認頁面截圖「應用程式：ChatGPT」已開啟） | ☑ |
| BBC6 | 回滾：取消勾選 | 空政策 → BBC callback 回傳原始物件；`RemotePreferences updated: 0` | 本輪未測（政策仍保留 `com.openai.chatgpt`） | ☐ |
| BBC7 | Knox／Secure Folder（user 150） | 零觸及、零查詢 | 全程未查詢 user 150；`pm` 僅查 user 0／95 | ☑ |

截圖／log：
- 裝置 2：`10` 頁面；`11` 裝置 1 toggle；`12` 頁面（應用程式：ChatGPT＋Telegram toggle 開啟）；log `s23_bbc_gate_true.log`（本機）。
- 裝置 1：`05`–`09` screens＋`11_firefox_dual.png`＋`s4_gate_true_bbc.log`（本機）。

殘餘未測（誠實揭露）：
- user 95 若由 ROM 指到其他 id，需以 `ps`/uid 前綴確認，勿假設 95。
- 回滾（取消勾選）未於本輪執行。
- BBC 注入不觸及 mutation 面（目的明顯不同：清單可見性，而非安裝路徑放寬）；安裝路徑永不掛勾。

