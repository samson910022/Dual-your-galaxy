# S24 唯讀調查、UX Review 修正與追蹤計畫

調查日期：2026-11-05 UTC。原始碼基準：`b3d48432f21d8e14b3a0da9e9a6b897cd4e0b776`。

## 1. 結論與證據分級

本輪由四個獨立調查方向交叉整理：profile 身分、安裝產物／UI、通道／命令契約、既有 issues 與驗收規劃。

- **實機唯讀證據**：指定 S24 的平台身分、命令回應、檔案權限、已安裝 APK 與實際 UI。
- **原始碼證據**：基準 commit 的接受條件與控制流程，不等於已在手機重現故障。
- **歷史實機紀錄**：既有 GitHub 留言的指定正常情境；不是本輪重新執行。
- **未驗證**：破壞性操作、故障注入、當前 source 的可追溯 APK 部署、完整授權／無障礙矩陣。

核心結論：先補寫入目標與結果可信度，再加入批次操作；App 選取意圖、實際安裝狀態及 Hook whitelist 政策必須分離。

## 2. 裝置與安全範圍

| 項目 | 結果與限制 |
|---|---|
| 型號 | Samsung SM-S9210（S24） |
| Android／API | 17／37 |
| One UI property | `ro.build.version.oneui=90000`；未另以設定頁確認產品版號 |
| 安全更新 | `2026-09-05` |
| ADB shell | uid 2000 |
| ADB caller 的 su | uid 0；**不等於本 App UID 已獲 root** |
| 本輪目標 | 已有 Dual profile 95；沒有建立或變更 profile |
| 其他容器 | 全域 user table 顯示 Secure Folder 使用150；未針對該 user 查套件、檔案或私有內容 |

沒有 install／upgrade／uninstall／clear、變更權限／通道偏好、啟停服務或重啟。沒有呼叫未知 Binder transaction。

UI 調查只開啟本 App、喚醒／收合系統遮罩及捲動；未輸入憑證、授權 Root／Shizuku、點擊 Clone／Remove，或打開其他 App 內容。不是完全零互動，也不是寫入端到端測試。

公開文件與 issues 不附裝置序號、網路位址、個人 user 名稱、第三方 App 清單、原始截圖／XML 或完整 logcat。

## 3. 安裝產物來源：不能把現有輸出當成可信建置

| 項目 | 已安裝版 | 本地既有 `app/build/outputs/apk/debug/app-debug.apk` |
|---|---|---|
| package | `com.galaxydual.plus.debug` | 相同 |
| version | `0.0.1(1)` | 相同 |
| min／target SDK | 29／34 | 相同 |
| 大小 | 5,999,211 bytes | 5,862,711 bytes |
| SHA256 | `78307c6af36c69201c98695a8d6a388d676699b0b85d282626485f6cd5a0a22c` | `cbd0bf17cd1cbb89d1bdedc028c3747904ffa5451b09c44485cc6246a34ccb1c` |

`classes2.dex`、`classes3.dex`、`classes5.dex` 不同，不是只有簽章差異。本地既有輸出缺少安裝版與當前 source 已有的部分通道功能，因此它也不是當前 checkout 的可信建置證據。

**安裝版不同於本地舊產物，不等於已證明安裝版不同於當前 source。安裝版 commit 仍未知。** 本輪未重新 assemble／部署；所有動態 UI 結論僅歸因上述安裝 APK hash。下一階段需要 source → build → installed APK 的可追溯鏈，不能靠相同 versionName／versionCode 認定版本一致。

## 4. 實機發現

### 4.1 Dual 目標有比名稱更強的平台證據

針對已存在的 user95，取得相互一致的最小身分欄位：

- `userType=android.os.usertype.profile.CLONE`。
- parent／profileGroupId=0。
- serialNumber=95；本機剛好與 ID 相同，不應當成通則。
- flags=`20001010`，符號化為 `DUALAPP_PROFILE|INITIALIZED|PROFILE`。
- `sys.dualapp.profile_id=95`，shell／root 都可讀。
- `dual_app` 服務存在，介面為 `com.samsung.android.app.ISemDualAppManager`；未呼叫 transaction。

資料來源為只保留目標列的 `cmd user list -v`、限定目標並最小過濾的 `dumpsys user --user 95`、property，以及 root 解碼目標 user XML 後只輸出的白名單身分 attributes。沒有公開原始 XML。

`dumpsys` 的顯示 label 與 `pm list users` 不同，不能互換格式後依名稱授權。當前 `PmOutputParser.resolveDualUserId()` 仍只用非零 ID＋label contains DUAL；explicit-ID guard 又只要求存在。

> 註（2026-11-05，PR-1 stage 2 後）：上段描述的是 b3d4843（PR 前）基準，現已改為雙源 CLONE 驗證＋ explicit verified guard（見 USAGE §3）：label-only 路徑已 `@Deprecated`，explicit id 須為當下 Confirmed 目標否則 fail-closed。原文保留作歷史對照。

本機這次正常快照會選對目標，**沒有實機選錯／誤刪的證據**。待修的是接受條件與負向邊界。不能只換成固定95／150、整組 flags、CLONE 型別或單一 property。root XML 只作診斷佐證，不應成為一般 Shizuku 使用的必要依賴。

### 4.2 `pm` 的乾淨空結果與錯誤要分開

對明顯不存在的 `com.galaxydual.plus.review.nonexistent`，在已確認目標上以 shell／root 多輪唯讀查詢：

| 查詢 | exit | stdout／stderr |
|---|---:|---|
| `pm path --user <DUAL_ID> <nonexistent-package>` | 1 | 均空 |
| `pm list packages --user <DUAL_ID> <nonexistent-package>` | 0 | 均空 |
| 完整 `pm list packages --user <DUAL_ID>` | 0 | 合法 package 行，stderr 空 |

沒有觀察到 `Unknown package`，也沒有卸載後 settling-window 重現。filtered 查詢不是 exact-match API，仍要比對完整 package token。

主調查者於01:11:47 UTC的統一計數為 shell／root 各34個唯一套件，未輸出清單。兩份獨立子報告曾回報33與34，差異原因未追溯；**不以數量當作固定 fixture、使用者建立分身數或安全性證明。**

原碼 `verifyOnProfiles()` 只看 exit，exit0 錯誤／垃圾／截斷可能被當成 absent；這是靜態缺口，不是本輪實機觀察到的假成功。修正不得把合法完整空 listing 全部改成 unknown，也不應退回只相信 `pm path`。

### 4.3 ADB root、App root 與 Shizuku 是不同證據

- `command -v su` 為 `/system/bin/su`，已被現有 heuristic 涵蓋；未重現路徑漏判。
- ADB caller 的 su 成功，不驗證 App 的 spawn、grant、capture、timeout 或 `SuCmdGateway`。
- Shizuku 已安裝；本次特定名稱的 process 快照未見 shizuku，已安裝版 UI 顯示 binder 未連線。
- 沒有 instrument App SDK 的 ping／permission／UserService／Binder exec；不能僅由 ADB 讀取成功宣稱 App Shizuku round-trip 通過。
- 安裝版畫面顯示 AUTO／ROOT，只是該 APK 的觀察，不可替代當前 source 的通道驗收。

Auto 依 su 存在優先、失敗不降級的分支可由原碼確認；本輪沒有改 root grant、停止 Shizuku或故障注入來重現。

### 4.4 Hook marker 是歷史證據

只調查本專案定義的兩個固定 marker 路徑：

- 主檔 system:system／0600：shell cat Permission denied，root cat 成功。
- fallback 不存在。
- marker 記錄 system_server scope，時間約2026-11-04 09:11 UTC。
- 2026-11-05 01:07 UTC以 wall clock 減 uptime 推估本次開機約前日14:39 UTC，marker早約5小時28分。

是否曾調整 wall clock 未驗證；正式設計應考慮 boot identity／可信時鐘。這是具體歷史 marker 風險證據，**不能宣稱本次開機 Hook 有效，也不能因缺少另一 scope 就宣稱該 scope 失敗**。

原碼只要 scope 非空就顯示已啟用，且 LSPosed NoOp 選項無法使用 ready gateway fallback。應分開診斷 reader 與寫入 gateway，保留 marker 永不授予寫入權限的防線。

### 4.5 實際 UI 與前次 review 的界線

僅歸因第3節的已安裝 APK，擷取前與 XML dump 後均確認本 App MainActivity 在前景：

- 長文字清單把輸入、操作、訊息推到首屏外；一次捲動後可見，**不是永久裁切**。
- 通道按鈕為2×2，不是四個擠在同一列。
- AUTO 已選，但四按鈕均 selected／checked=false；目前選擇靠另一行文字。
- Shizuku binder 未連線時授權按鈕仍 enabled，未點擊，不能宣稱已重現授權失敗。
- 原生文字按鈕有文字標籤；content-desc 空不代表無標籤。
- XML input focus 不等於 TalkBack 焦點；未實測 TalkBack、旋轉、字級、鍵盤或亮暗主題。
- 沒有觸發寫入；enabled=true 不能解讀為 ViewModel guard 失效。

## 5. 修正「全部未經實機驗證」的舊敘述

既有公開紀錄已包含正常 S24 寫入成功，不應忽略：

| 歷史紀錄 | 可支持 | 不能推論 |
|---|---|---|
| [#6 SHELL clone 紀錄](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/6#issuecomment-6625676231) | 指定正常配置的 App SHELL clone 曾成功 | 所有通道、版本或負向情境都安全 |
| [#9／PR18 remove 複驗](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/9#issuecomment-6626334331) | package listing 重試改善指定 remove 查核窗口 | 所有 exit0 錯誤／截斷都能正確辨識 |
| [#6／PR19 手動通道紀錄](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/6#issuecomment-6627423091) | 手動切換、偏好保存及動態重選曾測試 | App 勾選已保存、Auto 拒絕降級或 live LSPosed 已完成 |

這些是歷史證據，不是本輪重新做 clone／remove。#2 的 owner／歧義／不存在ID防護和 #9 的 verify／refresh 已實作，follow-up 應針對殘留契約，不重開成「完全沒做」。

## 6. GitHub 追蹤與交付順序

以仍開啟的 [#6](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/6) 作整合索引，不重開 #2／#9，也不混入既有 #10 的 deoptimize 工作。

| 優先級 | Issue | 主要交付／依賴 |
|---|---|---|
| P1 | [#21 平台身分與 explicit-ID 邊界](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/21) | typed identity、跨來源核對、非Dual存在ID拒絕；#2後續 |
| P1 | [#22 可信完整 listing／unknown](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/22) | capture完整性、錯誤與合法空清單分流；#9後續 |
| P1 | [#23 移除確認與目標綁定](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/23) | 取消零寫入、一次確認一次派送；依賴#21/#22 |
| P1 | [#24 非阻塞操作生命週期](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/24) | Main等待、busy、事件合併、deadline／取消及晚到結果 |
| P2 | [#25 實測能力選路與通道語意](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/25) | Auto降級、明確模式、單一重新連線流程；協調#24 |
| P2 | [#26 App清單與選取持久化](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/26) | 唯讀catalog可先做；批次寫入依賴安全／操作修正 |
| P2 | [#27 診斷與歷史Hook證據](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/27) | unknown不被probe消除、freshness、reader、去識別匯出 |
| P2 | [#28 任務導向UI與無障礙](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/28) | 版面、選取語意、語系主題、圖示及實際驗收 |
| P2 | [#29 CI與可追溯UX驗收](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/29) | 先接app tests，隨各修正補VM/UI/持久化/裝置矩陣 |

建議階段：

1. 先接#29既有app tests；為#21/#22建立合成負向fixture，再實作安全修正。
2. #24 → #25/#27，並以#21/#22契約完成#23。
3. #26的唯讀清單／選取store可平行；安全前置完成後才接批次寫入，再依#28驗收。
4. 檢查`MANAGE_EXTERNAL_STORAGE`與`REQUEST_INSTALL_PACKAGES`的真正呼叫鏈用途；`QUERY_ALL_PACKAGES`另按inventory需求評估。不上Play不是保留過寬權限的充分理由。
5. #6既有whitelist跨程序政策與LSPosed真通道仍另行完成，不能拿勾選保存或marker存在代替。

每張issue均含來源、證據界線、計畫與驗收。本輪只調查及補充計畫／文件，**沒有宣稱App缺陷已修復**。

## 7. 測試與待驗證矩陣

本對話前段已在相同source commit以JDK21、`--rerun-tasks --no-build-cache`實際執行app33＋core44，共77項，零failures／errors／skipped；lint為0 errors／43 warnings。沒有把hook測試補稱為已執行，也沒有用既有unit結果代替UX驗收。

目前CI unit step只跑core／hook；缺app33項。未見真正ViewModel／Activity／選取store測試，應隨各修正補齊。

| 待測 | 通過條件／限制 |
|---|---|
| 當前source實機版本 | 建立可追溯APK並在獲允許部署後比對installed hash，不覆蓋來源不明的現有App當作零風險 |
| App ROOT／Shizuku | instrument App UID、SDK授權與UserService唯讀round-trip；ADB成功不替代 |
| 目標安全負向 | 假gateway驗證存在非Dual／型別衝突／serial改變皆零目標命令，不實打受保護profile |
| verify錯誤／截斷 | installed／absent／unknown三態與capture完整性；保留乾淨空listing |
| 移除確認／資料後果 | 取消零寫入；資料後果只在明確授權的可拋棄App與備份下測 |
| Main／Binder／斷線 | 可控故障注入驗證非阻塞、deadline、晚到結果、unknown；不擅停私人裝置服務 |
| 選取恢復 | 搜尋／排序／刷新／旋轉／程序死亡／重啟後一致；恢復不自動重送寫入 |
| 無障礙與視覺 | TalkBack、字級、亮暗、鍵盤、窄／分割視窗實測；XML不能替代 |
| 版本矩陣 | 本機Android17/API37只代表一組；補宣告支援的舊版三星與minSdk smoke |

## 8. 證據保存

原始截圖／XML包含私人資訊，未放入repo或GitHub。指定SSD實際為不支援所需chmod語義的檔案系統；生成的14份原始artifact已移至本機`~/.local/state/opencode/galaxydual-review-20261105/`，核對目錄0700、檔案0600及capture SHA256。遠端原始capture留在任務專用0700目錄。

公開內容只使用匿名化摘要與APK hash。診斷匯出功能日後也應先預覽、預設去識別且不自動上傳。
