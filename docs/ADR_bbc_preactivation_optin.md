# ADR — Hook 端真正的 BBC pre-activation opt-in（PR5 Phase A/B / #80 / PR #90 review 修正）

- 狀態：已接受（orchestrator 產品決策，2026-11-24）。**Phase A（owner App 持久化 install classification＋一次性 migrate marker、Hook 唯讀）、B1（單一 coherent 唯讀快照）、B2/C（App consent 狀態機＋durable Unknown fence）皆已完成**；本文件按 PR #90 review 修正為實際行為，不再描述為「wiring 屬後續 phase」。
- 關聯檔案（關鍵實作與測試）：
  - 決策：`hook/src/main/java/com/galaxydual/hook/BbcPreactivationGate.kt`、
    `BbcPreactivationPrefs.kt`、`BbcPreactivationHookReader.kt`；keys 與分類值字彙定義於
    `PolicySource.kt`（含 `install_classification`）。
  - App 分類／寫入：`app/src/main/java/com/galaxydual/plus/BbcInstallClassification.kt`
    （framework-free planner）、`HookPolicyStore.kt`（`versionCommitLock` 內的同一 transaction）、
    `GalaxyApp.kt`（`XposedService` bind 時 idempotent 分類；owner App 的可寫入 remote prefs 通道）。
  - App consent：`BbcActivationConsentState.kt`、`BbcToggleCoordinator.kt`、`MainActivity.kt`，
    以及 `app/src/main/res/values*/strings.xml`。
  - Hook 接線：`hook/src/main/java/com/galaxydual/hook/DualHook.kt`
    （`bbcCoherentEffectGateBounded()` → `bbcCoherentEffectDecision()`，搭配
    `readBbcPreactivationBounded()` 的單一 `getAll()` coherent snapshot 與
    `BbcPreactivationHookReader.readCoherent()`）。**Hook 側沒有任何
    migration marker writer**；API 102 在 hooked app 取得的 remote preferences 是唯讀的。
  - 測試：`BbcPreactivationGateTest`、`BbcPreactivationPrefsTest`、`BbcCoherentGateTest`、
    `BbcPreactivationReadOnlyTest`、`BbcPreactivationClassificationTest`（production writer →
    Hook gate 的跨邊界測試）、`BbcActivationConsentStateMachineTest`、
    `BbcConsentDurabilityFenceTest`、`BbcPreactivationConsentTest`；
    `BbcStickyPolicyTest`／`BbcGateBoundedWaitTest` 隨 fail-closed 決策更新。

## 背景（OLD／released-beta 行為，非目前 HEAD）

以下描述的是**舊版／已 released beta 的出貨行為**，保留只為說明本 ADR 的動機，
**不是目前 HEAD 的行為**。舊版是 `BBC_AVAILABLE_APPS_INJECTION=true`（compile-time）加上
`dual_policy/bbc_injection_enabled` 缺席／首次讀取失敗一律視為 `true`，
因此 Hook **可能早於 App 首次風險警告或確認就生效**。App 的 `RiskNotice`
checkbox 只是 app-private pref，不是 Hook 的 gate；Hook 只認 remote prefs。
這與「生效前 opt-in」的要求不符，且此前的揭露已明確把它列為已知限制。
PR #90 已把 compile-time 開關降為最終 AND 的緊急開關、把執行期讀取失敗改為
fail-closed，並把 legacy 判定從「目前的草稿 bytes」改為 App 持久化的安裝分類。

## 決定

1. **新安裝 fail-closed。** owner App 在任何新世代草稿（packages／version）寫入之前或
   同一 transaction（`versionCommitLock`）寫入
   `dual_policy/install_classification=NEW_GENERATION_NOT_CONSENTED`。
   `dual_policy/bbc_preactivation_optin` 缺席或讀取失敗 → BBC 效應 **OFF**，直到 App
   在使用者明確 pre-activation opt-in 後以可寫入通道持久化 `true`。目前的
   packages／version bytes **不是** consent 證據；普通草稿保存不能隱含授權。
2. **既有安裝由 App 分類與遷移。** 分類鍵缺席且判定為升級前既有草稿
   （`extra_whitelist_packages` 非空或 `extra_whitelist_version` Present）時，owner App
   在**每一次 bind** 的同一個 forced durable commit 寫入
   `install_classification=PRE_UPGRADE_EXISTING` 與一次性 `bbc_legacy_migrated=true`；
   Hook 只讀取這兩個鍵。**舊政策草稿位元不變**
   （不刪除、不清空、不改寫）。**草稿 bytes 本身不被當成 legacy 同意證據**
   （App 只用它們判別升級前既有，並把結果寫入分類鍵；Hook 永不從草稿推斷同意）；
   升級後 App 從未被開啟至少一次，或 bind commit 持續失敗／仍在重試時，注入維持 OFF
   （刻意 fail-closed）。只有 `Confirmed` 的分類＋標記 commit 才算 migration 完成，
   下一次 bind 會重寫相同鍵值。Hook 端 API 102 remote prefs 唯讀，
   **不聲稱 Hook 曾完成任何 marker 寫入**。
   App 授權端比照 Hook gate：`PRE_UPGRADE_EXISTING` 亦須 authoritative marker
   read 為 `true` 才進入 `Legacy`（`MIGRATED`／`EXPLICITLY_GRANTED` 不需要）；
   bind-time 分類 commit 失敗時，App 不從被污染的 local mMap resync，而是對該
   generation 安裝 Unknown fence，直到較新 service instance 的 durable resync。
3. **明確 opt-out 永遠優先。** `bbc_injection_enabled=false` 或新 optin 鍵為
   `false` → OFF，優先於分類／遷移狀態。
4. **App checkbox 不是 gate。** `RiskNotice` 只是 app-private 提示狀態；Hook 只認
   `dual_policy` remote prefs。
5. **Compile-time gate 改為緊急開關。** `BBC_AVAILABLE_APPS_INJECTION` 不再作為
   「預設開」的依據；保留為最終 AND 的一環（`false` 即零偏好讀取、零合併），設 `false`
   即 BBC 零合併。
6. **混合閘也 fail-closed。** 執行期 opt-out 鍵缺席不再是「預設開」的依據
   （缺席只表示沒有明確 opt-out；新安裝仍須明確 opt-in）；讀取失敗、
   timeout、contention、clock/CAS 例外一律 closed；confirmed OFF 的 sticky 語意
   （`BBC_STICKY_MAX_AGE_MS`，process-local）維持不變。Hook 以單一 `getAll()` 的
   coherent snapshot 同時判定 pre-activation 與 hybrid 兩層；torn generation／錯型別
   即整份 fail-closed。
7. **durable Unknown fence（單調）。** App consent 狀態機在 `commit(false)`／拋錯後安裝跨
   store／controller／Activity／service rebinding 的 Unknown fence；fence 的 generation
   只升不降（`maxOf`），同一 generation 的本地／污染讀取永遠不能清除它。只有 `Confirmed`
   grant 或較新 service generation 的權威 resync 可升級為 `Granted`，
   被樂觀污染的 local `mMap` 不可清除該 fence。

### 目前實作的取捨（PR #90 Phases 1–3）

- **Hook 完全唯讀，不寫任何 marker。** hooked app 的 API 102 remote preferences 唯讀；
  Hook 不寫入 `bbc_legacy_migrated`、`install_classification`、`bbc_enable_pending`，
  也不改政策草稿（位元不變）。所有 durable 寫入都由 owner App 在其可寫入的
  `XposedService.getRemotePreferences` 通道完成；Hook 只讀取同一批 coherent 鍵。
- **owner App 每次 bind 都重新確認分類。** 分類＋一次性遷移標記在同一個 forced durable
  commit 寫入，只有 `Confirmed` 算數；bind commit 失敗會對該 generation 安裝 Unknown
  fence，下一次 bind 重試。App 從未開啟或 commit 一直失敗時，既有安裝維持 OFF。
- **app-only `bbc_enable_pending` 讓 cold-start／rebind 收斂，但不反轉 OFF。** grant 時
  App 在同一個 atomic forced commit 寫入 `bbc_preactivation_optin=true` 與
  `bbc_enable_pending=true`；其後每一次 runtime 偏好寫入（consumer 的 enable 與使用者的
  OFF 都算）都在同一個 commit 把此標記清除。bind/resync 的 coherent 讀取必須同時看到
  optin=true 與 marker=true，app IO consumer 才補寫一次 effect enable；已持久化的 OFF
  已清除標記，背景 resync 因此不會把 OFF 翻回 ON。此標記只給 App 用，Hook gate 永不讀它。
- **進程死亡殘餘（如實揭露）。** 若 grant 已 `Confirmed`、runtime enable 尚未寫入時進程死亡，
  marker 仍在，App 下次 bind/resync 會補上該 enable；但若 coherent 讀取讀不到 optin／marker，
  或該 commit 回報 Unknown，resync 不補寫、效應維持 OFF（fail-closed）。hook 行程的
  RemotePreferences 快取也可能仍落後，需重啟 hook 行程／開機才觀察到新值。
  這不是即時跨行程保證，也不是 #75 防護或資料安全保證。
- `POLICY_KEY_EXTRA_VERSION`（`extra_whitelist_version`）損毀／讀取失敗會讓整份 coherent
  snapshot fail-closed，效應 OFF 直到修復（C3）；草稿曾非空不能繞過它，也不是 consent 證據。

### 純函式決策表（`bbcPreactivationGate`）

| compileTimeGate | optIn | classification | migrated | effectOpen |
| --- | --- | --- | --- | --- |
| false | 任意 | 任意 | 任意 | false |
| true | false | 任意 | 任意 | false |
| true | true | 任意 | 任意 | true |
| true | null | `EXPLICITLY_GRANTED`／`MIGRATED` | 任意 | true |
| true | null | `PRE_UPGRADE_EXISTING` | true | true |
| true | null | `PRE_UPGRADE_EXISTING` | null／false | false |
| true | null | `NEW_GENERATION_NOT_CONSENTED`／null／未知值 | 任意 | false |

`PRE_UPGRADE_EXISTING` 需可信的 `migrated=true` 才開閘（兩鍵由 App 在同一 commit
寫入）。`MIGRATED` 與 `EXPLICITLY_GRANTED` 是 reader／gate 接受的**保留值**，
目前沒有 live writer，不得視為已交付的寫入路徑；未知值 fail-closed。使用者清空
政策規則只是移除規則，不等於 opt-out（opt-out 有專用鍵），不能因此靜默撤銷同意。

## 破壞性變更與 key 穩定性

`bbc_preactivation_optin`、`bbc_legacy_migrated` 與 `install_classification`
（連同其分類值字彙）是 breaking-change-sensitive keys。**重命名任何一個或其值
都會放棄已持久化的同意／遷移／分類狀態**，必須經過明確的遷移計劃與審查；
`BbcPreactivationGateTest` 以字面值 pin 住鍵與值。

## 後果

- 新安裝若 App 尚未持久化 opt-in，BBC 效應保持 OFF（保守、可預期）；第一次 ON
  由 app-scoped 狀態機直達 consent，取消／返回鍵／外部觸控／show 失敗零寫入並回到
  最後已知狀態，不虛構 Hook 效應 OFF。
- 既有安裝的舊政策草稿完全保留；owner App **額外寫入一個一次性遷移標記與分類**
  （都在其可寫入的 `XposedService` remote-prefs 通道），Hook 端唯讀；不聲稱 Hook
  端曾完成任何寫入。
- 既有 hybrid 測試中「首次讀取失敗 fail-open」的預期已如實改為 fail-closed；
  沒有丟棄任何 hybrid fail-open 預期。API 102 marker-writer 測試
  (`BbcMigrationMarkerTest.kt`) 已隨其 production writer 一併移除（Hook 唯讀），
  並由 read-only／owner-write 測試取代。
- **已知限制（App opt-in commit Unknown 與冷行程）**：`commit(false)`／拋錯時如實
  回報 Unknown 且不寫 BBC 開關；本地 mMap 可能被樂觀污染，但單調 durable fence 不允許
  同 generation 的讀取或污染值清除 Unknown。有 `bbc_enable_pending=true` 的
  `Confirmed` grant 在 cold start／rebind 的 coherent resync 會被 app IO consumer 補寫一次
  effect enable（而不會反轉已清除標記的 durable OFF）；但 grant 已落盤而 runtime 寫入
  缺席時，若下次讀取讀不到 optin／marker 或 commit 仍為 Unknown，就不補寫，
  授權與效應仍可能不一致。因此**不能**宣稱「Hook 一定不提前生效」，也**不能**宣稱
  「App 重啟一定自癒」；重啟只是重新讀取，不保證回到重新詢問，hook 行程的快取亦可能
  落後。這是同意 UX 的可觀測限制，不據此宣稱已修正或資料安全。
- 真實 LSPosed 跨行程可見性（remote prefs 的 daemon／`mMap` 快取是否如預期重同步）
  仍 **DEVICE-UNVERIFIED**；Samsung 冷啟動刪除（#75，DELETE 早於 BBC 回調）亦
  **DEVICE-UNVERIFIED**。opt-in 只校正「生效前取得同意」的時機，**不是 #75 防護**，
  也不構成任何資料安全保證。
- **追蹤中的實機議題**：#73（Scope A 警告）、#75（Samsung 冷啟動刪除）與
  #79（熱路徑效能／15ms gate／512-entry IPC）皆維持 **DEVICE-UNVERIFIED**；
  本 PR 不關閉任何實機驗收。**#80 維持 OPEN**，待 real-device LSPosed 跨進程
  可見性、UI 與相容性矩陣。不承諾零資料損失，也不承諾 Samsung 不刪除分身資料。
