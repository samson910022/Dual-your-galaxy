# Unreleased（PR5：BBC pre-activation opt-in）

> 本檔描述**尚未 released** 的變更。已發布的 `v0.1.0-beta.3` 產物不含下列行為；
> beta.3 的 release notes 保留為歷史，請勿据其判斷目前 HEAD 的行為。

## Breaking change：新安裝需先 opt-in，BBC 注入效應才會生效

BBC 注入效應現在是四層 AND：

`compile-time 緊急開關` × `pre-activation opt-in` × `執行期 opt-out` × `hybrid fail-closed`

- **新安裝**：`dual_policy/bbc_preactivation_optin` 鍵缺席或讀取失敗 → 效應 **OFF**（fail-closed）。使用者首次把 App 內的「BBC 注入」開關切到 ON，會先出現明確的三語系 consent 對話框；只有 opt-in commit 成功持久化後，才接著寫入 BBC 開關設定。取消／返回鍵／外部觸控／對話框顯示失敗＝**零寫入**並回復 OFF。
- **既有安裝**：已有非空政策草稿（`extra_whitelist_packages` 非空或 `extra_whitelist_version` 存在）者視為 legacy opted-in，效應維持 ON，並寫入單鍵 `bbc_legacy_migrated=true` 標記。**舊政策草稿不刪除、不清空、不改寫**；標記寫入失敗只記診斷，不影響本次判定。
- **已明確 opt-out 者**：`dual_policy/bbc_injection_enabled=false` 仍最高優先關閉，無需動作。
- **讀取失敗**：任一鍵讀取失敗、timeout、contention、clock/CAS 例外一律 fail-closed（效應 OFF），且不寫任何 migration marker、不碰政策草稿。短暫不可用只是效應暫停，不是政策縮減。
- `BBC_AVAILABLE_APPS_INJECTION=true` 現在只是 compile-time 緊急開關；`bbc_injection_enabled` 鍵缺席不再當作「預設開」的依據。

## 遷移通知

1. 新安裝：到 App 的 BBC 開關首次 ON 時完成一次 opt-in；取消完全不寫入。
2. 既有安裝（政策草稿非空）：自動以 `bbc_legacy_migrated` 單鍵標記遷移，規則不刪改、無需使用者動作。
3. 已 opt-out：維持關閉。
4. 可能需要重啟 Hook 行程／開機才觀察到生效。opt-in 寫入需要可寫入的 remote preferences 通道；若 LSPosed NoOp 寫入被拒而無法完成 consent，效應維持 fail-closed（OFF）。
5. `bbc_preactivation_optin` 與 `bbc_legacy_migrated` 是 breaking-change-sensitive keys，**不得改名**（改名等同放棄同意與遷移狀態）。

## 限制與誠實揭露

- opt-in 只校正「生效前取得同意」的**時機**，**不是**資料安全保證，**不是** Samsung 冷啟動刪除（#75）的防護；BBC 主要效果仍 experimental／DEVICE-UNVERIFIED。
- App 行程 opt-in commit 失敗後，RemotePreferences 樂觀 `mMap` 可能讓同行程 `isBbcPreactivationOptIn()` 誤回 true，後續 ON 跳過再次詢問；Hook 端 all-or-nothing fail-closed 確保效應不會因此提前生效，App 重啟後恢復重新詢問。詳 `docs/ADR_bbc_preactivation_optin.md`。
- 真實 LSPosed 跨行程可見性、三語系 UI 可達性與 Scope A／效能（#73/#75/#79）仍待實機矩陣；JVM／CI 全綠不等於裝置通過。
