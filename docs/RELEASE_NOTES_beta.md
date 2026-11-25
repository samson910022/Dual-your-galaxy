# Galaxy Dual Plus — v0.1.0-beta.1 (experimental beta)

**定位：主要功能可用 + 完整、誠實、容易看見的風險揭露。這不是「P0 已解決」或「資料安全保證」的版本。**

- 版本：`0.1.0-beta.1`（versionCode `2`）
- 標籤：`v0.1.0-beta.1`
- GitHub Release 請標記為 **Pre-release / beta**，不要設為最新穩定版
- LSPosed 模組：`com.galaxydual.plus`（module.prop 版本與 app 同步）

## 此 beta 包含的變更

- **M1 風險揭露（P1 product-safety）**：主頁持續可見的風險提示＋可重開的「詳細風險」；首次手動建立分身、批次建立、BBC 政策寫入前，出現可取消、明確確認的三語系風險警告（default / English / zh-Hant）；一次性 ACK，取消＝零操作、不標記。
- **M2 主功能正確性／誠實標註**：授權通道辨識、Dual Messenger profile 身分驗證、clone/remove、政策保存／重讀、不虛假成功；BBC 效果在目前版本標為 **experimental／尚未於本版本實機確認**（`Provisional` 不再顯示為 `Confirmed`）。
- 先前 PR #60–#82 的 BBC 注入、Scope A 清除警告、多輪 review 修正與文件收斂。

## ⚠️ 風險揭露（必須保留在使用者看得見的地方）

1. **Samsung 可能自動刪除分身與永久資料。** 2026-11-23 的 A/B/C 對照中，BBC 注入 ON、DB 確認 OFF、以及完全沒有本模組 BBC hook 三臂，都出現同一個 system uid (`1000` / `android.uid.system:1000`) 對 `com.openai.chatgpt` user 95 的 `START DELETE PACKAGE`；A/B 臂早於 BBC callback，C 臂無 callback 可比（process-start 代理、低精度 +103 ms）。Samsung daagent 自己的 DB 記錄 `Delete forbidden package: com.openai.chatgpt from user: 95`。刪除根因**未證實**；此為負向控制關聯（ASSOCIATION，非因果；各臂 App build 不同，`pm install-existing` 只還原套件存在、不還原使用者資料），opt-out 不是刪除防護。詳 `docs/ACCEPTANCE.md` §6.0 E-23-ABC。
2. **這是已揭露、由維護者接受的 experimental limitation，不是 pass、不是已修復、不是安全結論。** 若日後確認本 App 在未授權下直接刪除資料的程式錯誤，仍須重新提升為 blocker。
3. **opt-out 不是刪除防護。** `BBC_AVAILABLE_APPS_INJECTION=true`、執行期偏好缺席視為啟用；Hook **可能早於首次 App UI 警告生效**。App 的 checkbox／首次對話框**不是**「Hook 生效前取得同意」。
4. **Root / Shizuku 只是操作路線，不是資料保護。** Shizuku / ADB shell 無法通用備份其他 profile 的私人 App 資料；`pm install-existing` 只還原套件存在，不還原已清除的聊天、登入與私人檔案。即使有 Root，也不等於 Keystore／私有資料可完整還原。
5. **LSPosed BBC 是另一項實驗性能力。** 它的正向證據來自 2026-11-17/18 舊 build，不適用於目前版本；單次觀測到 hook 安裝與 `merged +1` 不構成普遍有效性或資料安全保證。不要把 Root/Shizuku 的成功與 BBC 驗證混為一談。
6. 「已安裝」「政策已存」「BBC ON/OFF」都不等於「資料安全」；未驗證的 ROM／版本一律標示 UNKNOWN / NOT VERIFIED。

**使用前請先以各 App 支援的方式完成並確認可還原的備份；不要把唯一重要資料只放在分身內。**

## 驗證狀態（如實）

- CI build／locale-instrumentation：**SUCCESS**（PR #74 最終 run #45103347110；main merge 後 #45113206916）；manual emulator matrix **SKIPPED**。
- JVM：2292 tests（app 1401 / core 395 / hook 496），0 failures / errors / skipped。
- Android instrumentation：60 tests 全通過（locale-instrumentation，API 29 emulator job）。
- **不是**裝置零風險驗證；JVM／CI 全綠不等於 Samsung 端資料安全。
- P0／H00：experiment limitation，UNMEASURED / NOT a pass。

## 已知 follow-up（不影響本 beta 定位，但尚未修）

- #75：Samsung 冷啟動 pre-DELETE 決策根因與 pre-activation opt-in。
- #76–#81：修復提示持久化可靠性、warning retry 競態、Editor 對話框生命週期、BBC 效能量測、toggle 序號。
- Scope A dialog 的實機 UI 驗證與 512-entry IPC／15ms gate／30min sticky 量測仍為 UNMEASURED。

## 安裝注意

- 此 APK 為 signed release；LSPosed 模組與 App 同一 APK（`module.prop` 與 `java_init.list` 已由 release gate 驗證）。
- 需要 Root（KernelSU / Magisk）+ LSPosed 與相容的 Xposed API（102），以及 Samsung Dual Messenger。
- 不同 One UI／Android 版本的行為可能不同；未驗證組合請視為 UNKNOWN。
