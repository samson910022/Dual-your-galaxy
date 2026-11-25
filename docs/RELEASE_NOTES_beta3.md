# Dual your Galaxy — v0.1.0-beta.3（舊 id 全新安裝／同 id in-place 升級；實驗性 beta）

**定位：主要功能可用 + 完整、誠實、容易看見的風險揭露。這不是「P0 已解決」或「資料安全保證」的版本。**

- 版本：`0.1.0-beta.3`（versionCode `4`）
- 標籤：`v0.1.0-beta.3`
- GitHub Release 請標記為 **Pre-release / beta**，不要設為最新穩定版
- LSPosed 模組 id：`io.github.samson910022.galaxydualplus`（`module.prop` 版本與 app 同步）

## ⚠️ 身分辨識（依目前安裝的 id 條件化）

**套件 id 遷移發生在 beta.2**：`applicationId`／LSPosed module id 從 `com.galaxydual.plus` 改為 `io.github.samson910022.galaxydualplus`。**套件識別碼不變；beta.3 同步 App 顯示名稱與版本欄位（versionCode 4 等）**。

**先確認目前安裝的 id**：若是舊 id `com.galaxydual.plus` → 全新安裝、不遷移舊資料、不可與新 id 共存、先備份並確認可還原；若已是 `io.github.samson910022.galaxydualplus`（含 beta.2 → beta.3）→ **同 id、同簽章 in-place 升級，不需卸載**，Android 通常會就地保留該管理 App 的資料，但**不保證 Samsung 分身或其資料完好**。

Samsung 可能自動刪除分身並永久清除資料；一般既有雙開 App 不需為此套件 id 遷移而**手動移除**，且這**不表示**分身「不受影響」——本 App 沒有刪除防護。

不同套件 id 會被 Android 與 LSPosed 當成**另一個完全不同的 App／模組**：

- **不會遷移任何東西**（僅舊 id → 新 id 的情境）：頻道偏好（channel preference）、雙開選擇（dual-app selection）、風險確認 ACK、修復提示（repair hint）、LSPosed 政策草稿（policy draft）全部留在舊套件下，**不會**自動帶到新套件。升級後請重新設定、重新確認風險、重新寫入政策。
- **舊資料仍留在 `com.galaxydual.plus`**：安裝新版本不會清掉舊套件的資料；解除安裝舊版本也不會把資料搬進新套件。兩邊的內部儲存空間彼此獨立。
- **沒有 App 內建匯出／匯入**（no in-app export/import）。請在解除安裝任何一個版本**之前**，先以每個 App 各自支援的機制（App 內建備份／雲端同步／系統備份）備份並實際驗證可還原分身與聊天資料；不要把唯一重要資料只放在分身內。
- **請勿同時安裝或啟用新、舊兩個套件 id。** 兩者會成為兩個獨立的 LSPosed 模組，並使用各自獨立的遠端偏好 authorities（舊 `com.galaxydual.plus.XposedService`／新 `io.github.samson910022.galaxydualplus.XposedService`，Shizuku authorities 同理）。同時啟用會造成兩組模組與偏好並存、行為不可預期。請先停用並解除安裝其中一個，再安裝另一個。一般既有雙開 App 不需為此遷移**手動移除**，但這**不表示**分身不受影響（見上方 Samsung 說明）。

> **Rollout 註記**：已發布的 GitHub Release body 仍為舊的身分／A/B/C 用語，由 maintainer 於 rollout 同步更新（PR 描述記錄 owner）；更新前以本檔與 README／USAGE／HOOK 的安裝警告為準。此為 release-metadata 更新，**不得 retag／rebuild／改 APK bytes**。

## 此 beta 包含的變更

- **M1 風險揭露（P1 product-safety）**：主頁持續可見的風險提示＋可重開的「詳細風險」；首次手動建立分身、批次建立、BBC 政策寫入前，出現可取消、明確確認的三語系風險警告（default / English / zh-Hant）；一次性 ACK，取消＝零操作、不標記。
- **M2 主功能正確性／誠實標註**：授權通道辨識、Dual Messenger profile 身分驗證、clone/remove、政策保存／重讀、不虛假成功；BBC 效果在目前版本標為 **experimental／尚未於本版本實機確認**（`Provisional` 不再顯示為 `Confirmed`）。
- **本次變更（beta.3）**：App 顯示名稱改為 `Dual your Galaxy`（3 語系 `app_name` + manifest label + hook 描述同步）；套件識別碼不變。
- **套件身分遷移（beta.2）**：`applicationId` 與 LSPosed `module.prop id` 同步改為 `io.github.samson910022.galaxydualplus`；`namespace` 仍維持原始碼套件 `com.galaxydual.plus`（**未變動**，以確保 Kotlin 類別與元件 FQN 一致），版本同步為 `0.1.0-beta.3` / versionCode `4`；`scope.list` 與 `java_init.list` 維持不變。此變更**不代表**舊的風險狀態被修復或驗證。
- 先前 PR #60–#82 的 BBC 注入、Scope A 清除警告、多輪 review 修正與文件收斂。

## ⚠️ 風險揭露（必須保留在使用者看得見的地方）

1. **Samsung 可能自動刪除分身並永久清除資料。** 2026-11-23 的 A/B/C 對照中，BBC 注入 ON、DB 確認 OFF、以及完全沒有本模組 BBC hook 三臂，都出現同一個 system uid (`1000` / `android.uid.system:1000`) 對 `com.openai.chatgpt` user 95 的 `START DELETE PACKAGE`；A/B 臂早於 BBC callback，C 臂無 callback 可比（process-start 代理、低精度 +103 ms）。Samsung daagent 自己的 DB 記錄 `Delete forbidden package: com.openai.chatgpt from user: 95`。刪除根因**未證實**；此為負向控制關聯（ASSOCIATION，非因果；各臂 App build 不同，`pm install-existing` 只還原套件存在、不還原使用者資料），opt-out 不是刪除防護。詳 `docs/ACCEPTANCE.md` §6.0 E-23-ABC。
2. **這是已揭露、由維護者接受的 experimental limitation，不是 pass、不是已修復、不是安全結論。** 若日後確認本 App 在未授權下直接刪除資料的程式錯誤，仍須重新提升為 blocker。套件 id 變更也不例外：新 id 不代表風險消失。
3. **opt-out 不是刪除防護。** `BBC_AVAILABLE_APPS_INJECTION=true`、執行期偏好缺席視為啟用；Hook **可能早於首次 App UI 警告生效**。App 的 checkbox／首次對話框**不是**「Hook 生效前取得同意」。
4. **Root / Shizuku 只是操作路線，不是資料保護。** Shizuku / ADB shell 無法通用備份其他 profile 的私人 App 資料；`pm install-existing` 只還原套件存在，不還原已清除的聊天、登入與私人檔案。即使有 Root，也不等於 Keystore／私有資料可完整還原。Root／Shizuku 的成功**不是** LSPosed BBC 的驗證證據。
5. **LSPosed BBC 是另一項實驗性能力。** 它的正向證據來自 2026-11-17/18 舊 build，不適用於目前版本；單次觀測到 hook 安裝與 `merged +1` 不構成普遍有效性或資料安全保證。不要把 Root/Shizuku 的成功與 BBC 驗證混為一談。
6. 「已安裝」「政策已存」「BBC ON/OFF」都不等於「資料安全」；未驗證的 ROM／版本一律標示 UNKNOWN / NOT VERIFIED。

**使用前請先以各 App 支援的方式完成並確認可還原的備份；不要把唯一重要資料只放在分身內。本版沒有任何零資料遺失保證（no zero-data-loss guarantee）。**

## 驗證狀態（如實）

- 下列數字為 **beta.1（v0.1.0-beta.1 / versionCode 2）** 的實際 run 結果，僅代表當時那個版本，**不是**本 beta.3（versionCode 4）的驗證結果。
- CI build／locale-instrumentation：**SUCCESS**（PR #74 最終 run #45103347110；main merge 後 #45113206916）；manual emulator matrix **SKIPPED**。
- JVM：2292 tests（app 1401 / core 395 / hook 496），0 failures / errors / skipped。
- Android instrumentation：60 tests 全通過（locale-instrumentation，API 29 emulator job）。
- **本 beta.3 的 CI／artifact 錨點（已驗證，見 `docs/ACCEPTANCE.md` §1）**：Android CI run **#45228233599**（PR head `e5c61f51`，tree `33614a81…`：build／locale-instrumentation SUCCESS、manual emulator matrix SKIPPED）與 Release run **#45233459919**（tag push，commit／main HEAD `fc579de3`，tree `33614a81…`：release＋publish SUCCESS、無 manual matrix job）；annotated tag `v0.1.0-beta.3` → `fc579de3`，APK SHA256 `98a3ce205c9a8b5a4f6cc6870c7afa9e93731655cc0a3d0f7f7147335a5a1531`（API metadata，非 tested byte）。**已發布 Release body 已填入本版實際數字**（run #45228233599／#45233459919、JVM 2297 tests〔app 1406／core 395／hook 496〕、locale-instrumentation 60 tests、apksigner cert pin `608e674f…61e3` 等），該 live 內容為既成事實，**不得用本模板的 TBD 或上方 beta.1 數字覆蓋**；rollout 時由 maintainer 同步，且**不得 retag／rebuild／改 APK bytes**。（PR #91 的文件 run #45442918490／#45445622345 為文件審查 run，非 beta.3 驗證錨點。）上方 beta.1 的 2292 tests／run #45103347110／#45113206916 僅屬 beta.1 歷史。JVM／CI 全綠不是 Samsung 端資料安全保證。
- **不是**裝置零風險驗證；JVM／CI 全綠不等於 Samsung 端資料安全。
- P0／H00：experiment limitation，UNMEASURED / NOT a pass。

## 已知 follow-up（不影響本 beta 定位，但尚未修）

- #75：Samsung 冷啟動 pre-DELETE 決策根因與 pre-activation opt-in。
- #76–#81：修復提示持久化可靠性、warning retry 競態、Editor 對話框生命週期、BBC 效能量測、toggle 序號。
- Scope A dialog 的實機 UI 驗證與 512-entry IPC／15ms gate／30min sticky 量測仍為 UNMEASURED。

## 安裝注意

- 此 APK 為 signed release；LSPosed 模組與 App 同一 APK（`module.prop` 與 `java_init.list` 已由 release gate／套件身分檢查驗證）。
- **先確認目前安裝的 id**：若是舊 id `com.galaxydual.plus` → 全新安裝、不遷移舊資料、不可與新 id 共存、先備份並確認可還原；若已是 `io.github.samson910022.galaxydualplus`（含 beta.2 → beta.3）→ **同 id、同簽章 in-place 升級，不需卸載**，Android 通常會就地保留該管理 App 的資料，但**不保證 Samsung 分身或其資料完好**。
- Samsung 可能自動刪除分身並永久清除資料；一般既有雙開 App 不需為此套件 id 遷移而**手動移除**，且這**不表示**分身「不受影響」——本 App 沒有刪除防護。
- 停用／卸載／opt-out／重開機**都不是**刪除防護；beta.3 的 hook **可能早於首次 App UI 警告生效**。BBC 清單注入仍為 experimental／本版本未實機驗證。
- 需要 Root（KernelSU / Magisk）+ LSPosed 與相容的 Xposed API（102），以及 Samsung Dual Messenger。
- 不同 One UI／Android 版本的行為可能不同；未驗證組合請視為 UNKNOWN。
