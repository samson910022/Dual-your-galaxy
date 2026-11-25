# Unreleased（PR5：BBC pre-activation opt-in）

> 本檔描述**尚未 released** 的變更。已發布的 `v0.1.0-beta.3` 產物不含下列行為；
> beta.3 的 release notes 保留為歷史，請勿據其判斷目前 HEAD 的行為。

## Breaking change：新安裝需先 opt-in，BBC 注入效應才會生效

BBC 注入效應現在是四層 AND：

`compile-time 緊急開關` × `pre-activation opt-in` × `執行期 opt-out` × `hybrid fail-closed`

- **新安裝**：owner App 在任何新世代草稿寫入前／同一 transaction 寫入
  `dual_policy/install_classification=NEW_GENERATION_NOT_CONSENTED`；
  `dual_policy/bbc_preactivation_optin` 鍵缺席或讀取失敗 → 效應 **OFF**（fail-closed）。
  使用者首次把 App 內的「BBC 注入」開關切到 ON，會先出現明確的三語系 consent 對話框；
  同意持久化（`Confirmed`）後才接著寫入 BBC 開關設定。取消／返回鍵／外部觸控／
  對話框顯示失敗＝**零寫入**、UI 回到最後已知狀態，**不對 Hook 實際效應作任何宣稱**。
  接受是兩階段（先 grant、後 enable）；若只有前半段成功，後段結果為 Unknown，
  不宣稱已回滾或已生效。
- **既有安裝**：owner App 在其可寫入的 `XposedService` remote-prefs 通道把
  `install_classification=PRE_UPGRADE_EXISTING` 與一次性 `bbc_legacy_migrated=true`
  在**每一次 bind** 的同一個 forced durable commit 寫入；Hook 端（API 102 hooked-app
  remote prefs 唯讀）只讀取，不作任何 marker 寫入。舊政策草稿**不刪除、不清空、不改寫（位元不變）**；
  未 `Confirmed` 的寫入／重試不算遷移完成。**升級後 owner App 從未被開啟至少一次，
  或 bind commit 持續失敗時，注入維持 OFF**：草稿 bytes 不被當成 legacy 同意，
  沒有 App 的 `Confirmed` 分類＋標記就沒有可讀的授權證據（刻意 fail-closed）。
- **已明確 opt-out 者**：`dual_policy/bbc_injection_enabled=false` 仍最高優先關閉，無需動作。
- **讀取失敗**：任一鍵讀取失敗、timeout、contention、clock/CAS 例外一律 fail-closed
  （效應 OFF），且不寫任何 migration marker、不碰政策草稿。短暫不可用只是效應暫停，
  不是政策縮減。
- `BBC_AVAILABLE_APPS_INJECTION=true` 現在只是 compile-time 緊急開關；
  `bbc_injection_enabled` 鍵缺席不再當作「預設開」的依據（缺席只表示沒有明確 opt-out，
  新安裝仍須 opt-in），讀取失敗一律 fail-closed。

## 遷移通知

1. 新安裝：到 App 的 BBC 開關首次 ON 時完成一次 opt-in；取消完全不寫入、UI 回到最後
   已知狀態，且不對 Hook 效應作宣稱。
2. 既有安裝（升級前已有政策草稿）：必須先開啟 owner App 至少一次。bind 時由 owner App
   分類，並在其可寫入的 `XposedService` remote-prefs 通道以 `install_classification`
   ＋一次性 `bbc_legacy_migrated` 在同一 forced commit 完成遷移，規則位元不變、
   無需使用者額外同意動作；Hook 唯讀，不聲稱 Hook 端完成任何寫入。
   **若 App 未開啟或該 commit 一直失敗，注入維持 OFF（刻意 fail-closed，
   不從草稿 bytes 推斷 legacy 同意）**。
3. 已 opt-out：維持關閉。
4. 可能需要重啟 Hook 行程／開機才觀察到生效。consent／owner 寫入使用 owner App
   可寫入的 **`XposedService.getRemotePreferences`** remote preferences 通道
   （`dual_policy` group）；Hook 端以 `XposedInterface.getRemotePreferences` 取得的
   remote preferences 在 hooked app 中**唯讀**，是另一項獨立限制，不用來完成任何寫入。
   若寫入結果 Unknown，效應不因此被證明為 OFF 或 ON，請重試／重新核對。
5. `bbc_preactivation_optin`、`bbc_legacy_migrated` 與 `install_classification` 是
   breaking-change-sensitive keys，**不得改名**（改名等同放棄同意與遷移狀態）。

## 限制與誠實揭露

- opt-in 只校正「生效前取得同意」的**時機**，**不是**資料安全保證，**不是**
  Samsung 冷啟動刪除（#75）的防護；BBC 主要效果仍 experimental／DEVICE-UNVERIFIED。
- `POLICY_KEY_EXTRA_VERSION`（`extra_whitelist_version`）損毀／讀取失敗時，Hook 的
  單一 coherent snapshot 整份 fail-closed，BBC 注入維持 OFF，直到該鍵被修復且
  後續讀取成功為止（C3）；非空草稿不能繞過這個 fail-closed。此 fail-closed 不是
  legacy 同意證據，也不是 #75 防護。
- App 行程 opt-in commit 失敗後，app-scoped 狀態機安裝 durable Unknown fence：
  RemotePreferences 樂觀 `mMap` 可能讓同行程讀到看似 grant 的值，但不能清除 fence；
  只有 `Confirmed` 或較新 service generation 的權威 resync 才能授權。故不宣稱
  「Hook 一定不提前生效」「App 重啟一定自癒」；grant 已落盤而 runtime 寫入缺席的
  cold process start 仍是已知限制。詳 `docs/ADR_bbc_preactivation_optin.md`。
- 真實 LSPosed 跨行程可見性、三語系 UI 可達性、**Scope A 警告（#73）、冷啟動刪除
  （#75）與效能（#79）**仍待實機矩陣；JVM／CI 全綠不等於裝置通過。**#80 維持 OPEN**，
  待 real-device LSPosed 跨進程可見性、UI 與相容性矩陣；不承諾零資料損失，也不承諾
  Samsung 不刪除分身資料。
