# ADR — Hook 端真正的 BBC pre-activation opt-in（PR5 Phase A/B1/B2 / #80）

- 狀態：已接受（orchestrator 產品決策，2026-11-24）。**Phase A（純決策層）、
  B1（Hook 端 remote prefs I/O 接線＋單鍵遷移標記寫入）、B2（App 首次 ON 的
  opt-in consent 對話框＋寫入接線）皆已完成**，不再是「wiring 屬後續 phase」。
- 關聯檔案（關鍵實作與測試）：
  - 決策：`hook/src/main/java/com/galaxydual/hook/BbcPreactivationGate.kt`、
    `BbcPreactivationPrefs.kt`、`BbcMigrationMarkerWriter.kt`；keys 定義於
    `hook/src/main/java/com/galaxydual/hook/PolicySource.kt`。
  - Hook 接線：`hook/src/main/java/com/galaxydual/hook/DualHook.kt`
    （`bbcPreactivationPrefsNow()`／`bbcPreactivationOpenNow()`／
    `writeBbcLegacyMigrationMarker()`／`bbcEffectGateOpen()`）。
  - App 接線：`app/src/main/java/com/galaxydual/plus/BbcPreactivationConsent.kt`、
    `MainActivity.kt`、`HookPolicyStore.kt`，以及 `app/src/main/res/values*/strings.xml`。
  - 測試：`BbcPreactivationGateTest`、`BbcPreactivationPrefsTest`、
    `BbcMigrationMarkerTest`、`BbcPreactivationBoundedTest`、
    `BbcPreactivationConsentTest`、`HookPolicyStorePreactivationTest`；
    `BbcStickyPolicyTest`／`BbcGateBoundedWaitTest` 隨 fail-closed 決策更新。

## 背景

出貨行為是 `BBC_AVAILABLE_APPS_INJECTION=true`（compile-time）加上
`dual_policy/bbc_injection_enabled` 缺席／首次讀取失敗一律視為 `true`，
因此 Hook **可能早於 App 首次風險警告或確認就生效**。App 的 `RiskNotice`
checkbox 只是 app-private pref，不是 Hook 的 gate；Hook 只認 remote prefs。
這與「生效前 opt-in」的要求不符，且此前的揭露已明確把它列為已知限制。

## 決定

1. **新安裝 fail-closed。** `dual_policy/bbc_preactivation_optin` 缺席或讀取失敗
   → BBC 注入效應 **OFF**，直到 App 在使用者明確 pre-activation opt-in 後寫入
   `true`。
2. **既有安裝安全遷移。** 若已有非空政策草稿（`extra_whitelist_packages` 非空
   或 `extra_whitelist_version` 存在）且沒有遷移標記 → 視為 legacy opted-in，
   效應維持 **ON**，並寫入 `dual_policy/bbc_legacy_migrated=true`。
   **不得刪除、清空或改寫既有政策規則**（不得靜默縮減舊政策）。
3. **明確 opt-out 永遠優先。** `bbc_injection_enabled=false` 或新 optin 鍵為
   `false` → OFF，優先於 legacy 草稿與遷移標記。
4. **App checkbox 不是 gate。** `RiskNotice` 只是 app-private 提示狀態；Hook 只
   認 `dual_policy` remote prefs。
5. **Compile-time gate 改為緊急開關。** `BBC_AVAILABLE_APPS_INJECTION` 不再作為
   「預設開」的依據；保留為最終 AND 的一環，設 `false` 即 BBC 零合併。
6. **混合閘也 fail-closed。** `bbcInjectionGateOpenWithSticky` 的 `runtime==null`
   （讀取失敗）不再是首次 fail-open，改為 closed；confirmed OFF 的 sticky
   語意（`BBC_STICKY_MAX_AGE_MS`，process-local）維持不變。這與決定 1 構成兩道
   獨立的 fail-closed。防禦性的 CAS/clock 例外路徑同樣 closed。

### 純函式決策表（`bbcPreactivationGate`）

| compileTimeGate | optIn | legacyPolicyPresent | migrated | effectOpen | writeMigrationMarker |
| --- | --- | --- | --- | --- | --- |
| false | 任意 | 任意 | 任意 | false | false |
| true | true | 任意 | 任意 | true | false |
| true | false | 任意 | 任意 | false | false |
| true | null | 任意 | true | true | false |
| true | null | true | null | true | true |
| 其餘（含 null / null / false、null / false / null） | | | | false | false |

`migrated=true` 且無 legacy 草稿仍為 open：這是**已遷移安裝的持久狀態**；使用者
清空政策規則只是移除規則，不等於 opt-out（opt-out 有專用鍵），不能因此靜默
撤銷已給予的同意。`migrated=false` 不是遷移，不開閘。

## 破壞性變更與 key 穩定性

`bbc_preactivation_optin` 與 `bbc_legacy_migrated` 是 breaking-change-sensitive
keys。**重命名任何一個都會放棄已持久化的同意／遷移狀態**，必須經過明确的迁移
計劃與審查；`BbcPreactivationGateTest` 以字面值 pin 住兩鍵。

## 後果

- 新安裝若 App 尚未寫入 opt-in，BBC 效應保持 OFF（保守、可預期）。
- 既有安裝的舊政策草稿完全保留，僅额外寫入一個遷移標記（單鍵、不影響草稿）。
- 既有 hybrid 測試中「首次讀取失敗 fail-open」的預期已如實改為 fail-closed；
  沒有刪除任何測試。
- **已知限制（B2 review 揭露，刻意不宣稱已修）**：App 端 opt-in commit 失敗
  （`commit(false)`／拋錯）時，寫入路徑如實回報 Unknown 且不寫 BBC 開關；但
  `RemotePreferences.commit()` 的樂觀 `mMap` 可能讓**同一行程**的
  `HookPolicyStore.isBbcPreactivationOptIn()` 誤回 `true`，使該行程後續 ON
  跳過再次 consent 詢問。這**不會**讓 Hook 提前生效——Hook 端 opt-in／legacy／
  migrated 快照讀取是 all-or-nothing fail-closed，鍵未持久化時效應不會啟動；
  App 重啟後因鍵未落盤，會恢復重新詢問（自癒）。此為同意 UX 的可觀測限制，
  不是資料安全退化，亦不據此宣稱已修正。
- 真實 LSPosed 跨行程可見性（remote prefs 的 daemon／`mMap` 快取是否如預期
  重同步）仍 **DEVICE-UNVERIFIED**；Samsung 冷啟動刪除（#75，DELETE 早於
  BBC 回調）亦 **DEVICE-UNVERIFIED**。opt-in 只校正「生效前取得同意」的時機，
  **不是 #75 防護**，也不構成任何資料安全保證。
