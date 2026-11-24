<!-- MIT - Original docs by Dual your Galaxy contributors. No GPL content. -->
# Hook 模組教學（HOOK）

> LSPosed 模組：擴展三星 Dual 白名單查詢，絕不觸碰 Secure Folder（Knox）。
> 全部自創 MIT 程式碼，僅思路參考第三方（見 `docs/CREDITS.md`），無抄襲 OnePlus / GPL hook 碼。
> 本文件所述 log 均為 best-effort：記錄可能失敗或不可取得，缺少日誌不等於事件未發生。

## 1. Hook 原理

### 1.1 目標：只放寬「白名單查詢」，不碰安裝 / 移除路徑

三星 Dual（Dual Messenger / Dual App）能否雙開某個 App，取決於系統側的
**白名單查詢**。本模組只 hook 查詢類方法，採「只擴展、不縮減」策略：

- 原本回傳 `true`／已在名單 → 保持不變。
- 原本回傳 `false`／不在名單 → 僅當該包名在模組政策集合內才放行。
- 目前政策集合（`EXTRA_WHITELIST_PACKAGES`，經 `PolicySource.effective(seed, remote)`
  決策）的靜態種子為**空**，故 Stage-1 查詢 hooks 為 observe-only 佔位：會安裝、會記 log，
  但不改變任何行為。決策為 union-only（只增不減），包名校驗與
  `core/ShellEscaper` 同規則；靜態欄位合併由純函數 `WhitelistPatch` 執行
  （`MutableList` 原地追加、`String[]` 重建替換，其餘一律放棄並記 log）。
- 政策來源已接線：App 端（`GalaxyApp` 取得 `XposedService`）把使用者草稿
  寫入 LSPosed remote preferences group `dual_policy`／key
  `extra_whitelist_packages`；hook 側 `DualHook.readRemotePolicy()` 以
  `getRemotePreferences(PolicySource.POLICY_PREFS_NAME)` 讀同一 group/key。常數單一來源於
  `PolicySource.POLICY_PREFS_NAME`／`POLICY_KEY_EXTRA_PACKAGES`。
- owner-user 需求：hook 讀 user 0；草稿必須由 owner user 寫入（非 owner
  用戶的寫入不會被 hook 讀到）。「hook 讀 user 0」為 assertion，非實機證明；
  app 寫入後 hook 是否真讀到，需 on-device 端到端證明（DEVICE-UNVERIFIED；
  BBC 注入面僅有 11-17 pre-unification／11-18 unified build **舊 build** 證據；**本版本（beta.3）experimental／未實機驗證**，見 §1.3a／§1.4／`docs/ACCEPTANCE.md` §6）。
- 跨行程新鮮度（P1-1，已修正為 POLL ONLY，API 102.0.0 無推送）：
  LSPosed API 102.0.0 的 `IXposedService` **沒有**遠端偏好回調 transaction；
  `RemotePreferences` 在 daemon 抓取 `mMap` 一次後只通知**同行程**監聽器；
  `XposedService.getRemotePreferences` 為 `computeIfAbsent` 永久快取。
  因此 hook 行程註冊的 `OnSharedPreferenceChangeListener` **永遠不會**因
  app 行程寫入而觸發——舊文案「daemon 推送＋invalidate 近乎即時」為誤述，
  已更正。`DualHook` 保留註冊僅為同行程 hygiene（防 stale-instance 洩漏），
  新鮮度**不依賴**它。新鮮度機制＝**輪詢＋版本證明（PR #74 Phase 3 已實作）**：
  `EffectivePolicyCache` TTL（`maxCacheAgeMs` 60 秒）到期後下次讀取重讀同一
  cached 實例；因實例永久快取，輪詢仍可能讀到舊值直到**重啟**（daemon 重建
  才重同步權威值）。寫入側 `HookPolicyStore` 在 `dual_policy` 同 group 另存單調
  `extra_whitelist_version`（每次 save/clear＋1，與 packages 同一 atomic commit）；
  hook 側 BBC 讀取走 seqlock 自洽快照（version-before → 防禦性 copy → version-after，
  不一致即 fail-closed 回 Unknown），並以 **Good（非空）／Cleared（空＋版本證明）**
  單調版本閘拒絕重放與陳舊值。此為「可觀測的新鮮度證據」，**不是**跨行程推送替代：
  版本亦經同一永久快取實例，底層 `mMap` 可能仍陳舊，新鮮度仍受 TTL＋重啟語義節制，
  不保證 60s 內或下一次 poll 看到新集合；重啟 hook 行程後才保證讀到新值。
  重啟語義見 §2.3。端到端仍需 on-device 驗證
  （DEVICE-UNVERIFIED）。
   hook 側 TTL 一覽（皆見 `EffectivePolicyCache`，與 App 側 TTL 對照見 `docs/USAGE.md` 政策 TTL 對照表）：
   - 快取 TTL `maxCacheAgeMs` 60 秒：到期後下次讀取 fail-closed 回 seed（`invalidated=true`），不復活過期值；TTL 到期不 bump 世代（到期前已發起的在途新鮮讀仍可滿足到期）。能力缺失（`PROP_CAP_REMOTE` false→`emptySet()` 成功空讀）同樣被快取 60 秒，故 false 有效重試＝max(60 秒 TTL，5 秒 latch 內閘)；latch 5 秒僅在快取 miss（TTL 到期／`invalidate()`）後才可達。
   - 失敗節流 `failureCooldownMs` 5 秒：遠端讀取失敗（null／拋出，未被快取）後不重試 reader，回 `last-known-good`（未 invalidate 時）或 seed；`invalidate()` 顯式重置節流。注意與上條區分：失敗（未快取）由 5 秒節流，能力-false 空讀（已快取）由 60 秒 TTL 閘控。
   - BBC sticky `BBC_STICKY_MAX_AGE_MS` 30 分鐘：讀取失敗／Unknown 時沿用最近一次成功集合（Good 非空或 Cleared 空＋版本證明）的**有界降解視窗**上限，超過即 fail-closed 回空；單調時鐘取樣異常（後續樣本小於前次高水位／時鐘失效）時無法證明新鮮度，同樣 fail-closed 丟棄 sticky，上限不被放寬——此處的 clock 是 `getBbc` 的 trusted 取樣（生產預設 `System.nanoTime()/1_000_000`，單調），**非使用者調整 wall clock**（此句僅指 `EffectivePolicyCache` 的政策集合 sticky；opt-out gate 的 confirmed-OFF process-local sticky 在 skew 下是 fail-open，兩者為不同機制，見 opt-out 段）；這**不是**冷啟動刪除的結構性修正——DELETE 早於 BBC 回調且回調非空，sticky 對該路徑無對照證據（見 `BBC_COLDSTART_DELETE_INCIDENT.md` §8.4）。清除草稿後亦可能因本地快取／sticky 在最多 30 分鐘內沿用舊集合，無即時停止保證。
    - 能力熔斷開啟期間（capability gate 卡住／爭用，`PolicyRefreshContentionException` 被 `isRemoteCapable` 吞掉而回 null，見 DualHook catch-site KDoc：閘門級 herd fix 經此路徑被繞開）：`EffectivePolicyCache` 將該 null 視為失敗讀取並續期 5 秒 `failureCooldownMs`——TTL 到期（invalidated=true）後的每次 5 秒節流重試都會再次續期節流，完整能力的刷新路徑被壓在 seed-only，直到 5 秒節流的能力探針成功為止——此條僅如實記錄既有行為，明確不改代碼。
    - reconciler 總預算 `POLICY_RECONCILE_TOTAL_BUDGET_MS` 15 秒（App 側，Stage F2/G2）：reconciler join＋3 讀＋退避合計有界；handover 登記的 Tx 由行程級 coordinator 的 app-scoped 域接手，reconciler 不隨 VM 死亡；超限即誠實 unresolved（`REFRESH_FAILED`／`CHANNEL`＋Known=false＋pending 保留＋`RECONCILING` 續留，Tx 存活）——有 ground truth 則收斂回 `IDLE`，無 ground truth 則維持 `RECONCILING`（joinable（armed／已交接）的兄弟 VM 由 init reclaim 認領為 `RECONCILING` 並 join 同一 deferred；僅「owner 存活且 Tx 未 arm（PREPARING）」的兄弟 VM 見 `BUSY`，直到 owner 終態後自行收斂；不一律 `BUSY`；「稍後刷新」僅為手動 late-join 提示）（見 `docs/USAGE.md` 政策 TTL 對照表）。
    - 單次 auto-recheck（App 側，Stage G2）：`deferred.invokeOnCompletion` 在 handoverTx 登記時掛單次權威重查（`boundedReconcileRead`，coordinator app-scoped 域＋`Dispatchers.IO` 共用 IO 池，容量有界讀取 wrapper，非獨立 pool，不耗 reconciler 三讀預算）→走 settle helper（deferred 完成＋Known==預期才結清）；Unknown 保持 unresolved＋`UNRESOLVED_REFRESH`（按鈕即重新整理草稿，待手動 refresh late-join）。terminal-publish arbitration（Stage P2）= Tx-scoped adoption-epoch fence：busy-waiter 與 async-init 認領在 commitMutex 內 bump 共享 `PolicyTx.adoptionEpoch`（sync-init 認領（joinable）不再於 init 內直接 bump：init 的 commitMutex tryLock 快照僅為「建議性」觀察，認領本身——本地 CAS＋adoptionEpoch bump＋RECONCILING fence flip——一律經 launchReclaimAsync 於 commitMutex 內線性化，與 busy-waiter 及 async-init 同臂，詳見 DualViewModel.kt sync-init 註記；三臂的 fence 皆於 claim 時與同一 mutex 內比對，為 fail-closed。attach 時快照，claim 時於同一 mutex 內比對，fence 失敗即 EPOCH_STALE（不消耗 claim、不 bump rev、不發布）；讀取橫跨認領的 worker retain 並讓位 adopter；舊 worker 在認領 bump 前線性化則合法勝出（linearization point = claim CAS inside commitMutex，順序以 mutex 到達為準）。
   - 閘門關閉短路（Stage C）：`NON_EMPTY_POLICY_EFFECTIVE_GATE` 關閉時 `get()` 直接回空集合，不碰 reader、不加鎖、不讀時鐘——observe-only 下零 IPC 讀取，故上述 TTL 在閘門關閉時皆不觸發。
- 樂觀快取限制：`RemotePreferences.commit()` 先更新行程內快取再走 binder，
  故 save/clear 失敗後同一行程的 `load()` 可能樂觀讀到未落盤的值；冷啟動
  會從 daemon 重新抓取。hook 側框架的快取／讀取行為（是否一律讀取 daemon
  的權威值）尚未實機驗證（DEVICE-UNVERIFIED）。App 在失敗發生時即回報
  失敗，不以樂觀值宣稱成功。
  App 側另有新身份穿透實驗路徑（DEVICE-UNVERIFIED）：失敗後僅當 provider
  解出不同身份的新 `SharedPreferences` 實例才允許一次穿透讀，同實例永回
  Unknown 直到成功 save/clear；此「新身份＝新 mMap」僅為比較假設，未經
  on-device 證明——若 LSPosed 同 group 永回同一快取實例則此層生產永不命中，
  若 wrapper 共享底層 mMap 則仍有污染風險。TODO(on-device)：驗證 daemon 重建
  後新實例是否重同步權威值、同 group 是否回同一實例、wrapper 是否共享 mMap。
  版本／序列鍵（P1-1 poll 配套，**PR #74 Phase 3 已實作**，端到端仍 DEVICE-UNVERIFIED）：
  寫入側 `HookPolicyStore` 已在同 group 另存單調 `extra_whitelist_version`（Long，每次
  save/clear＋1，與 packages 同一 atomic commit）；hook 側 BBC 讀取以 seqlock 自洽快照
  （version-before → 防禦性 copy → version-after，不一致 fail-closed 回 Unknown）搭配
  **Good（非空）／Cleared（空＋版本證明）**單調版本閘，拒絕重放／陳舊值（含內容感知的
  同版本再接受規則）；App 側 reconciler／refresh 以本地世代（policyDraftVersion／freshGen／rev）判定是否需重讀；持久化版本鍵僅供 hook 側新鮮度證據。版本本身仍經同一
  永久快取實例，底層 mMap 可能仍陳舊，故仍受 TTL＋重啟語義約束（daemon 重建前版本亦可能
  過期），僅為「可觀測的新鮮度證據」，**非推送替代**；不保證 60s 內或下一次 poll 看到新集合，
  重啟 hook 行程後才保證讀到新值。DevOps 端無須額外操作，但端到端 read-after-write 仍待實機證明。
- App 側失敗語義（Phase1 fence／Phase2 Unknown 權威 refresh（盲清已撤回停用）／Phase3 phase／
  Phase4 timeout／Stage G1 settle＋世代拆分／Stage G2 auto-recheck＋coordinator，見 `docs/USAGE.md` 政策寫入流程與 `DualViewModel`）不改變
  生效點：`WhitelistPatch`/`PolicySource` 本階段不動，僅 fail-closed 可觀測
  （所有非 Known 草稿皆 fail-closed：Unknown 需先手動重整或警告 Retry 的權威
  `refreshPolicyDraft()` 回到 Known 才能清除，Corrupt 走修復流程，超時轉 RECONCILING
  核對）；端到端仍 DEVICE-UNVERIFIED。
- 熱路徑 stall 預算（P1-2，K4 P2#6 硬化，Phase7 端到端 total）：hook 白名單查詢執行緒上的有效政策重讀經
  `PolicyRefreshGate`（at-most-one guard＋stuck-worker breaker）＋零隊列執行器
  （`ThreadPoolExecutor(0,1,60s,SynchronousQueue)`，滿載／爭用即拒收）＋50ms 端到端總預算
  （`DEFAULT_STALL_BUDGET_MS`，Phase7 起為 capability＋prefs 兩段共享的單一 total：每段取
  `min(50, remaining)`，`remaining<=0` 直接跳過 fail-closed 回 null；follower grace 取
  `min(50, remaining)`，耗盡強制 0）；超時／爭用／拒收／熔斷／deadline 耗盡一律回 seed／last-known-good
  （fail-closed observe-only），慢 binder 讀在背景取消（uninterruptible 讀亦僅滯留
  單一 worker，隊列恆零、不增長），永不 pin 住查詢執行緒。強制重建卡死的 uninterruptible worker 現受
  **兩道獨立上限**約束（PR#60 r6 Finding #3）：(1) 每個 invalidate-epoch 的 `MAX_WORKER_REBUILDS`（回收節流，
  `invalidate()` 會歸零 `workerRebuilds`）；(2) 每個 gate 實例的 `MAX_PROCESS_ABANDONED_WORKER_REBUILDS=4L`
  （process-lifetime 硬駐留上限，`invalidate()`／`onSuccess()` 皆不重置；全行程兩個 gate ⇒ 最多 8 個殘留
  stuck daemon）。重建時 `activeCount<=0` 的 drained worker 不消耗此預算；真正 abandon（activeCount>0 且已換新
  executor）才計數。超過 cap 一律 fail-closed（丟棄＋有界日誌＋backoff 續期，不再開新執行緒）；**超過 cap 後、worker 仍 busy 時僅能 process restart 恢復**（或將來另行設計有界 rebind；drained worker 走半開探針照常恢復、不消耗此預算）——此為刻意僅記錄、不實作的重啟策略，文件不承諾其自動恢復。
  JVM 以 `PolicyRefreshBudgetTest`
  鎖定超時回退（leader-timeout）＋`PolicyRefreshGateTest` 鎖定有界＋熔斷＋invalidate 恢復＋
  `EffectivePolicyDeadlineTest` 鎖定端到端 total（雙 40ms 截斷、快慢合併、contention 不續節流、grace 0 快速），
  並以 `PolicyRefreshGateProcessCapTest` 鎖定 epoch 與 process 預算分離（invalidate 只重置 epoch）、drained 探針
  不消耗 process 預算、cap 耗盡後一律 fail-closed（拒絕換新 executor）、以及雙 gate 實例預算互相獨立。
- BBC 注入是本模組**唯一帶開啟效應閘的政策消費者**：BBC 清單合併由 `BbcInjector.mergeAvailableList`（見 §1.3a）執行，讀同一批政策集合（見上一條），效應開關為 `PolicySource.BBC_AVAILABLE_APPS_INJECTION = true`（Stage-1 白名單查詢閘 `NON_EMPTY_POLICY_EFFECTIVE_GATE = false` 不受影響、亦不得靠 BBC 開關繞過）。政策未勾選任何包時，BBC hook 回傳 Samsung 原始結果物件——**位元一致僅指回傳物件，不代表裝置行為不變；冷啟動刪除見 `BBC_COLDSTART_DELETE_INCIDENT.md`**。〔11-20/11-21 更新優先，見 §1.3a provenance 註記〕裝置證據 2026-11-17 於 pre-unification build 取得（政策 group `galaxydual_policy`；見 §1.3a 與 `docs/ACCEPTANCE.md` §6 provenance）；unified build 已於 2026-11-18 部分重驗（BBC1–BBC3；S23 Ultra，BBC1–BBC3 正向紀錄；BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載；證據 `s23_bbc_unified_20261118.log`，結論以 `docs/ACCEPTANCE.md` §6 為準）。

安裝／移除分身仍走 `pm install-existing --user <dualId>` 通道（見 `docs/USAGE.md`），
hook 不介入。

### 1.2 system_server：`dual_app` 服務家族 [S24 OneUI 9 部分已驗證]

雙開服務跑在 `system_server` 內，對外以 binder 服務 `dual_app` 暴露。
常見的白名單查詢 transaction 編號落在 **1／2／3／4**（白名單列表、
是否在白名單、是否已安裝白名單包、雙開狀態查詢）；**實際編號隨 One UI
版本漂移，上機前必須重新確認**：

```bash
# 1. 確認服務存在
adb shell service list | grep -i dual
# 2. 查實作類別（版本不同類別名不同，模組內已做多候選反射）
adb shell dumpsys activity services | grep -i -A2 dual
```

實機證據（S24 SM-S9210，OneUI 9／Android 17，PDA S9210ZHU6EZIF，
五層之第 2 層 hook 註冊）：
`service list` 確認 `dual_app: com.samsung.android.app.ISemDualAppManager`；
services.jar 反編譯確認 `com.android.server.DualAppManagerService` 存在
（含 `getAllWhitelistedPackages`、`isInstalledWhitelistedPackage`／
`isInstalledWhitelistedPackageInternal` 變體）；lspd 模組日誌
確認候選 1 在三次 system_server 啟動皆 `deoptimized`＋`hooked` 成功、
無 `method not found`。此為 hook 註冊證據，不等於第 3 層 callback 觸發
（可驗日誌缺：尚無回調命中／放寬包名 log）與第 4 層政策生效
（預設空政策，observe-only；system_server marker 同樣僅證明安裝）。
候選 2／3 在此 ROM 未命中，保留為 fallback。

模組在 `onSystemServerStarting` 以反射依序嘗試（第一個載入成功者勝出，
其餘 `try/catch` 略過，非三星 ROM 自動無操作）：

| 候選服務類別（第一個載入成功者勝出） | Stage 1 hook 的讀查詢方法 | 明確排除（fail-closed，待裝置驗證） |
| --- | --- | --- |
| `com.android.server.DualAppManagerService` | 僅嘗試 `getAllWhitelistedPackages()`（純白名單列表；若存在且通過 eligibility／簽名與回傳守衛） | `isInstalledWhitelistedPackage*`（已安裝且白名單複合查詢，userId／安裝狀態語義未驗證） |
| `com.samsung.android.server.dual.DualAppManagerService` | （同 Stage 1，僅嘗試 `getAllWhitelistedPackages()`；若存在且通過 eligibility） | （同上） |
| `com.android.server.dual.DualAppManagerService` | （同 Stage 1，僅嘗試 `getAllWhitelistedPackages()`；取決於版本是否存在該簽名） | （同上） |

安裝／移除路徑永不掛 hook。`*Installed*Whitelist*` 複合查詢在 hook 安裝路徑
（`WhitelistHookEligibility`）與攔截器（`WhitelistSignatureGuard`／
`DualHook.interceptWhitelistCall` runtime backstop）雙重 fail-closed，Stage 1
不改寫其結果，直到裝置驗證其 userId／安裝狀態語義。

每個方法 hook 前先呼叫 **`deoptimize(method)`**（best-effort）：`deoptimize(Executable)`
機制存在，但缺 caller 身份資訊，因此只明確請求對被 hook 的 callee 本體做 best-effort
去最佳化，**不保證影響已內聯進呼叫者（caller）的副本**；短方法若在 hook 前
已被內聯，仍可能收不到回調。
**反內聯效果尚未經實機驗證**：`deoptimize()` 回傳 `true`、`false` 或拋出
例外都會嘗試記 log（`GalaxyDualHook` tag）；`false` 只表示框架回傳了 `false`，
不推斷是 no-op 或其他原因。無論回傳值或例外，仍嘗試安裝 hook；安裝本身
也可能失敗，會另行嘗試記錄。
JVM 的 `DeoptimizeContractTest` 使用 fake 操作，只驗證無框架依賴的
`HookInstaller` 編排（順序、同一方法、結果及錯誤處理），**不驗證**
`DualHook` 的 framework adapter、LSPosed 執行行為或 ART 內聯效果。
caller-inline fixture 尚未建立，相關裝置驗證尚未完成；驗收待辦見下。

#### Caller-side 研究註記（issue #10 Stage1，不改行為）
- callee-only best-effort 維持現狀：真正的反內聯修正需要 **(a)** 按支援
  ROM／版本辨識已內聯 callee 的 caller（方法或建構子，`Executable`）並對
  **那些 caller** 做 deoptimize，或 **(b)** 選擇內聯副本無法繞過的 hook point。
- **不無差別 deoptimize 整個 system_server**：只維持已證據的最小範圍
 （已知 callers＋被 hook 的 callee）。
- JVM 可測部分已落地：`CallerDeoptPlan.plan(callee, knownCallers)`
  純函數決策（去重、保序、排除 callee 本體、不改輸入）＋
  `HookInstaller.installWithCallers` 編排（先逐一 best-effort deoptimize
  callers 並逐條保留 false／例外診斷（含 hookId 與完整簽名），再走既有
  callee deopt＋hook；caller 失敗不擋 hook），由 `CallerDeoptPlanTest`／
  `HookInstallerCallerTest` 鎖定（含 false 不得寫成「不需要」／no-op）。
- `DualHook.knownCallersFor()` 為 DEVICE-UNVERIFIED 接線點，目前回空
  （＝今日 callee-only 行為，呼叫點仍走 `hookWithDeoptimize()`，行為不變）；
  待按 ROM 取到 caller ART 證據後才接上。ART 實效仍待裝置驗證；未來若接上
  非空 caller 名單，其實路徑驗證亦屬 #10 caller/反內聯範圍（非非空政策
  concern）待裝置，驗收待辦維持不變。
Hook Builder 統一使用
**`ExceptionMode.PROTECTIVE`**（佔位保護）：請求 framework 保護 hook 例外處理，
不保證所有情況下 `system_server` 或 daagent 都不會崩潰。

#### 反內聯驗收待辦（尚未完成）

1. 建立可重現的測試 fixture：包含短 callee、直接呼叫與可預熱的 caller，
   能分別計數原方法與 hook callback；記錄建置版本與操作步驟。這是待辦，
   目前沒有現成的 caller-inline fixture。
2. 在支援 LSPosed 的測試裝置，以各自隔離的全新進程／重啟及相同初始條件
   分組，避免先前 hook、deopt 或編譯狀態污染：未掛 hook 基線、hook-only、
   hook＋callee deopt、hook＋caller deopt（若 API 支援）；必要時另測
   hook＋callee／caller 均 deopt。各組分別測未預熱及預熱後安裝，並比較
   直接呼叫與 caller 呼叫。記錄呼叫和 callback 次數、各 deopt 回傳值或
   例外、hook 安裝結果與可取得的原始日誌；確認基線原行為。另以實際
   `DualHook` adapter 驗證 framework 串接，而非以 JVM fake 替代。
3. 收集裝置型號、ROM／Android／ART 與 LSPosed 版本、模組及 fixture 版本、
   隔離與預熱方式、重現步驟，並以可取得的 ART 診斷證據確認各組測試時
   caller 是否真的已內聯。在 hook 安裝成功的組別，直接呼叫預期命中 callback；安裝失敗須單獨記為
   安裝失敗，不能拿來判定反內聯效果。若安裝狀態、內聯證據或必要對照
   不足，結果標為 inconclusive（不確定），不能僅憑 callback 次數或缺失
   的 log 宣稱成功或失敗。已確認內聯的 caller 若繞過 callback，記錄該組
   觀察及限制，不推廣為所有環境的必然行為。實際結果與未通過項目待執行後
   附證據記錄，現有 S24 註冊日誌不構成此驗收。

### 1.3 daagent：`WhiteListApps` [S24 OneUI 9 部分已驗證]

`com.samsung.android.da.daagent` 進程內的
`WhiteListApps`（完整路徑 `com.samsung.android.da.daagent.provider.WhiteListApps`，
舊短路徑保留為 fallback）持有靜態白名單常量：

實機證據（S24，DAAgent v3.7.00.0，dexdump 反編譯確認，五層之第 1 層類別選取）：
該類為 `PUBLIC ABSTRACT` 純常量持有者（3 個 `static final String[]`：
`DUAL_APP_WHITELIST_PACKAGES`、`..._FOR_CHINA`、`CHINA_SALES_CODES`，
僅 `<clinit>`＋Base64 helper，**零查詢方法**）——故 `<clinit>` patch 僅為
安裝證據（`static final` ⇒ `ObserveOnly`，不寫入）。`*hitelist*` 名稱掃描在
`WhiteListApps` 上為 **Stage 1 diagnose-only（不安裝 hook）**：ROM 變體存在
mutation／read-only 歧義（如 `removeWhitelistedPackage`、`addWhitelist*`、
`setWhitelist*`、`clearWhitelist`），S24 此類零查詢方法，故現階段無功能損失；
讀查詢實際位於 `DualAppDBHelper.getAllInstalledWhitelistedPackages`、
`DAUtility.getWhiteListPkgs`／`*InSystemServer`、`DualAppManager.*WhitelistPackage*`
（Stage1 方法級 hook 候選，見 `DualHook.hookDaagentQueryMethods`，[待實機驗證]）。lspd 日誌已確認 daagent 行程載入
含該欄位的類（`found ... static=true` 跨多代行程）；
`using com.samsung.android.da.daagent.provider.WhiteListApps for daagent hooks`
[S24 已驗證]（新路徑連續命中 7 次、零退回，方法零掛載符合抽象類預期）。
`using`×7 僅證明第 1 層類別選取，不等於第 2 層 `<clinit>` interceptor 執行、
第 3 層 callback 觸發、第 4 層包名放寬（可驗日誌缺；預設空政策下本就
observe-only）。App 一鍵與 listing 主驗證見 `docs/USAGE.md` §0／§3，
歷史通道見 [S24 調查](UX_REVIEW_S24.md) §5。

- 反射尋找欄位 **`DUAL_APP_WHITELIST_PACKAGES`** 與 **`..._FOR_CHINA`** 變體：
  兩個候選欄位只要存在**都會**嘗試合併（分別記錄結果），不存在者略過不拋錯。
  地區風險說明：ROM 另持 `CHINA_SALES_CODES`，可能按地區／銷售碼讀取
  `_FOR_CHINA` 欄位；若採「先命中先 patch」單欄位策略，`_FOR_CHINA`
  將永遠被跳過，國行裝置靜默失效——故雙欄位全覆蓋（見 `WhitelistPatch.patchFields`）。
- `hookClassInitializer`：在 `<clinit>` 跑完後把政策集合併入靜態欄位
  （支援 `MutableList`／`String[]` 兩種形態；非 static、型別不合、反射失敗
  一律放棄**並記 log**，方便定位）。
  - `static final`（ROM 實為 `static final String[]`）：**observe-only，不寫入**。
    普通 `Field.set()` 無法重指派 final 欄位（JVM 拋 `IllegalAccessException`，
    見 `WhitelistPatchReflectTest` 行為鎖定測試）；不做 `setAccessible` 強寫，
    ART 行為未驗證、不宣稱可覆寫。結果以 `Outcome.ObserveOnly` 明確記錄，
    絕不靜默當成功。
    daagent marker（`scope=daagent`）僅證明至少一個 daagent hook 已安裝
   （此 ROM 實為 `<clinit>` interceptor 加 Stage1 查詢方法候選 [待實機驗證]），不證明政策已生效
   （預設空政策下直接返回（語義等同 `Skipped`，未觸及反射）；
    即使將來政策非空，可讀的 `static-final` 欄位回 `ObserveOnly`，
    反射失敗（欄位缺失／讀取失敗）則回 `Skipped`，兩者都不寫入，
    見 `DualHook.patchWhitelistField` 與 `WhitelistPatch.patchFields`／`apply`，
    故僅觀察；政策生效需命中方法級查詢 hook（`WhitelistPolicy.apply`），否則仍 observe-only）。
  - `added=0`（政策已完全包含）：跳過 `set()`，無需寫入，自然 fail-closed。
- `WhiteListApps` 上方法名含 `hitelist`（大小寫不敏感）的 **fuzzy sweep 為 Stage 1
  diagnose-only（不安裝 hook）**：因 ROM 變體存在 mutation／read-only 歧義，掃描僅做
  診斷記錄（`WhitelistSignatureGuard.isPotentialMutationMethod` mutation 分類 +
  `WhitelistHookEligibility` 評估）並 fail-closed，不呼叫 `hookWithDeoptimize`、不計入
  installed；`<clinit>` hook 行為與計數不變。明確命名的讀查詢 hook 走 `hookDaagentQueryMethods`
  的顯式 allowlist（`DaagentQueryHooks.DAAGENT_QUERY_METHOD_NAMES`）＋
  `WhitelistHookEligibility`／`WhitelistSignatureGuard`，不依賴 fuzzy sweep。
- **Stage 1 Boolean 改寫（fail-closed）**：僅允許宣告 `boolean`/`Boolean` 且 **shape-allowlisted
  單一 `(String)`** 套件參數簽名（index 0，語義仍 [待實機驗證]）；`(String,idSlot)`／`(idSlot,String)`
  等多參數／userId 形狀、未知／歧義簽名、`*Installed*Whitelist*` 複合查詢一律不改寫
  （不安裝 rewrite eligibility／攔截器 strict 失敗即回傳原值），不宣稱 userId/profile 隔離。
  攔截器使用 strict `packageArgIndex`，不以「參數中恰有一個 String」作為未知 overload 的安全依據。
- Collection／array 合併由 `WhitelistPolicy.apply` 做型別守衛：僅合併 String 元素的 `List`／
  `String[]` 與**非 `SortedSet`／`NavigableSet` 的** `Set`（保留 Set 語意，不降級為 `List`；
  合併結果不保證原排序／具體實作）；`SortedSet`／`NavigableSet`、
  含非 String 元素的容器、其他 `Collection`、未知回傳一律 observe-only 並回傳原值。若提供
  `declaredReturnType`／`genericReturnType`，合併結果與宣告回傳型別不相容、或空/不可驗證的
  容器泛型在合併會新增元素時，皆 fail-closed 回傳原值。宣告非 Boolean 的回傳若執行期出現
  Boolean 值亦不進行 Boolean 改寫。
- Stage1 額外掛 `DualAppDBHelper`／`DAUtility`／`DualAppManager` 讀查詢方法級候選
  （`DualHook.hookDaagentQueryMethods`，[待實機驗證]）：每個載入成功的候選類上，僅嘗試
  `DaagentQueryHooks.DAAGENT_QUERY_METHOD_NAMES` 內的 **6 個**讀查詢方法名
  （`getWhitelistedPackages`／`getWhitelistPackages`、`getWhiteListPkgs`／
  `getWhiteListPkgsInSystemServer`、`isWhitelistedPackage`／`isWhiteListedPackage`；read-query only，
  安裝／移除路徑永不掛）。Stage 1 **不**掛 `getAllInstalledWhitelistedPackages`、
  `isInstalledWhitelistedPackage`（installed+whitelist 複合，fail-closed）。每個 overload 先經
  `WhitelistHookEligibility.evaluate()`；不合格者跳過並記 log。攔截器與 system_server 側相同
  （`WhitelistPolicy.apply`：有政策才翻 `true`／合併集合，空政策原樣返回 observe-only）。
  JVM 單測僅鎖定 `WhitelistPolicy.apply`／eligibility／guard 決策，安裝與回調需 LSPosed
  daagent 實機驗證。
- 持久化與生效的區分：App 端成功持久化草稿（`HookPolicyStore` save/clear → LSPosed remote preferences group `dual_policy`）僅證明寫入通道，**不**代表 daagent（或 system_server）已套用或撤銷任何政策。白名單 Stage-1 查詢面仍 DEVICE-UNVERIFIED（`NON_EMPTY_POLICY_EFFECTIVE_GATE=false` 封緘，零行為變更）；BBC 注入面僅 11-17 pre-unification／11-18 unified build 舊 build 證據（**本版本（beta.3）experimental／未實機驗證**；見 §1.3a／§1.4），unified build 曾於 2026-11-18 部分重驗（BBC1–BBC3；S23 Ultra，BBC1–BBC3 正向紀錄；BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載；證據 `s23_bbc_unified_20261118.log`，結論以 `docs/ACCEPTANCE.md` §6 為準）（見 `docs/ACCEPTANCE.md` §6）。


### 1.3a BBC 注入器（`PmWrapper#getPossibleDualAppPackages`）— 歷史證據：11-17 pre-unification／11-18 unified build 舊 build；本版本（beta.3）experimental／未實機驗證（S23 Ultra，BBC1–BBC3 舊 build 正向紀錄（部分重驗）；BBC4／BBC5 皆 UNVERIFIED（本 PR 未獨立稽核）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on；BBC6=系統自動卸載）

> provenance 聲明（先讀）
> 以下所有 log 與裝置行為，除非另有標注，皆為 **2026-11-17 於 pre-unification build**（政策 group `galaxydual_policy`／key `packages`，RemotePreferences listener 模型）實測所得；**以上僅舊 build 歷史證據，本版本（beta.3）experimental／未實機驗證。** **2026-11-18 已於 unified build（`unify/bbc-on-main`）以 S23 Ultra 重驗（BBC1/BBC2/BBC3 OK；BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載分身，見 `BBC6_SSAID_INCIDENT.md`，詳該文件）。**證據 `s23_bbc_unified_20261118.log`／`s23u_02..04`。
>
> **2026-11-20/11-21 更新（優先於本文 11-18 結論）**：非空政策下 daagent 冷啟動仍遭 Samsung 以 system uid 刪除 u95 分身，DELETE 早於 BBC 回調 116–144ms，toggle-ON 可恢復但資料全清；11-18「清空才刪」與「回傳位元一致＝零行為變更」不足以描述冷啟動風險。詳 `BBC_COLDSTART_DELETE_INCIDENT.md`、issue #75（issue #75 已於 2026-11-21 更正為非空回調；仍以 BBC_COLDSTART_DELETE_INCIDENT.md 為準）。本節所有 11-17/11-18 證據段落皆受此更新節制。

Samsung Dual Messenger 頁的「可用的應用程式」清單由 DAAgent 呼叫 BBC 庫
`PmWrapper.getPossibleDualAppPackages(Context)->java.util.ArrayList` 取得
（兩條入口均經此：`DualAppActivity` 頁面渲染、`DAUtility.getWhiteListPkgs(true)`
遙測）；BBC 類在 daagent 行程 boot classpath 解析。清單語義為
`installed ∩ enabled ∩ SemDualAppManager.getAllWhitelistedPackages()`（BBC 內部實作）。
BBC jar 於 Tab S7（One UI 5）與 S23 Ultra（One UI 9）結構相同：`Lcom/samsung/android/bbccommon/*`＋`fwwrapper/PmWrapper`，`getPossibleDualAppPackages` 演算法**位元相同**（opcode-identical：74 16-bit code units，無任何新增 static final 常數，方法簽名完全一致，雙 dump 逐字比對）。

本模組在 daagent 內 hook BBC 方法（PRIMARY anchor，PUBLIC STATIC，policy 面的唯一擴點），將**政策包集合**（`dual_policy/extra_whitelist_packages`；由 `PolicySource.effective` union-only 合併後再經 `EffectivePolicyCache` 容量約束讀取，見 §1.1；BBC 路徑效應閘 `PolicySource.BBC_AVAILABLE_APPS_INJECTION = true`）自我合併進 BBC 清單
之尾端（`BbcInjector.mergeAvailableList`：候選包若已存在、或未通過 installed/enabled 謂詞則跳過；至少一個新增才回傳**全新的** `ArrayList`（逐字複製 Samsung 清單後於尾端追加政策包；Samsung 原始清單物件永不原地修改）；零新增回傳 Samsung 原始物件——fail-closed / identity fast path 不變；PROTECTIVE）。
包含 BBC 所有可呼叫路徑，列表因此擴大為
`installed ∩ enabled ∩ (Samsung whitelist ∪ 政策包)`，只有可見條目數增加，
Samsung 原始驗證路徑不動；Stage-1 白名單查詢閘 `NON_EMPTY_POLICY_EFFECTIVE_GATE = false` 封緘不受影響。

裝置實機證據（2026-11-17，pre-unification build）：

| 裝置 | BBC | 操作 | BBC-HOOK 證據（擷錄原文） | 結果 |
| --- | --- | --- | --- | --- |
| Galaxy Tab S7（SM-T870，Android 13 / One UI 5，DAAgent 3.3.00.0） | BBC 驗證 | 政策勾選 `org.mozilla.firefox`、`com.google.android.apps.bard`（改名 Gemini） | `BBC-HOOK installed=1 (daagent, pid=21602)`；`BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=21602)`；`BBC-HOOK merge added 0 packages (policy=3: com.shopee.tw: installed=true, enabled=false, org.mozilla.firefox: installed=true, enabled=true, com.google.android.apps.bard: installed=true, enabled=true) (pid=21602)`（firefox/bard 已在 Samsung 執行期白名單：union no-op，注入零回歸） | 兩 app 出現在「可用」清單、可切換（截圖 `10_dualmessenger_page.png`：Firefox/Gemini 列顯示、toggle 可見）；雙開安裝觀察存在（2026-11-17 同 session `pm path --user 95 org.mozilla.firefox` 回應 dual APK paths；截圖 `11_firefox_dual.png` 為 Firefox first-run 畫面），**但未持久化為檔案證據：無持久化 u95 process 證據，不得宣稱 Firefox 已啟動雙開／u0 並存**；BBC 合併為 no-op（merge +0） |
| Galaxy S23 Ultra（SM-S9180，Android 17 / One UI 9「90000」，DAAgent 3.7.00.0，BBC jar 33,946 bytes，KernelSU + Zygisk-LSPosed） | BBC 驗證（`PmWrapper` 新增 static final 常數、方法完全一致；BBC dump 證據：BBC jar `Lcom/samsung/android/bbccommon/*` + `fwwrapper/PmWrapper`，演算法 `installed ∩ enabled ∩ SemDualAppManager` 與 S7 位元一致） | 政策勾選 `com.openai.chatgpt`（BBC 不含 `com.openai.chatgpt`） | `BBC-HOOK installed=1 (daagent, pid=21472)`；`BBC-HOOK callback getPossibleDualAppPackages (pid=21472)` → `BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=21472)` → **`BBC-HOOK merged +1 packages (total 7, pid=21472)`**（2026-11-17 22:54/23:01 LSPosed verbose mirror） | Samsung 頁「應用程式」區出現 ChatGPT 條目且可切換；已建立雙開並以 `u95_a431` 執行（`u0_a431` 主行程並存；`pm path --user 95 com.openai.chatgpt` 列出 base＋split APK，見 `s23_bbc_evidence_20261117.log`） |

S23 Ultra 之 BBC-HOOK 行僅出現於 LSPosed verbose mirror（`/data/adb/lspd/log/verbose_*.log`；`adb shell su -c 'grep …verbose_*.log'`），logcat 僅見 `RemotePreferences initial load: 0 package(s)` 與 `RemotePreferences updated: 1 package(s)` 兩行（`s23_bbc_gate_true.log`）；Tab S7 之 BBC-HOOK 行直接出現於 logcat（`s4_gate_true_bbc.log`，如 `BBC-HOOK installed=1 (daagent, pid=7812)`、`BBC-HOOK merge added 0 packages…`）。兩者皆為合法擷錄路徑。

附帶說明（unified build 與 pre-unification build 的差異）：
- BBC **不需要** empty-policy warm-up：`EffectivePolicyCache` 於 daagent 首個查詢時惰性讀取（TTL 輪詢），首次呼叫即得到與重啟後相同語義；政策空 ⇒ Samsung 原物件原樣回傳（identity fast path）。
- 讀者模型改變：pre-unification build 以 RemotePreferences listener push 即時生效（"live push"，見舊文案）；unified build **無**跨行程推送（API 102）——**60 秒僅為 hook 行程本地快取 TTL**；到期重讀的是同一個永久快取的 `RemotePreferences` 實例，底層 `mMap` 可能仍陳舊。**不保證 60s 內或下一次 poll 看到新集合；重啟 hook 行程後才保證讀到新值（無即時推送）。** 版本鍵（Phase 3）為可觀測新鮮度證據，非推送替代，見 §1.1。**unified build 的 reader 接線已於 2026-11-18 部分重驗（BBC1–BBC3）**（S23 Ultra，BBC1–BBC3 正向紀錄；BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載，`BBC-HOOK merged +1 packages (total 7, pid=…)` 之 `nonempty` 重現、callback 非空於空政策狀態下不帶 nonempty；見下）。
- BBC 合併（BBC merge、PROTECTIVE、identity fast path）、`installed ∧ enabled` 謂詞、`BBC_AVAILABLE_APPS_INJECTION` 效應閘皆為 S5/S9 共用、不動；Stage-1 sealed gate 無變。
- caller deopt：One UI 9 的 `DualAppActivity#getAvailableApps`／`#updateDualAppList` **找不到目標**（方法改名/內聯）→ hook 自動 skip（`BBC-SPIKE caller: … not found; skipped`），不影響 BBC callee 本體 hook——BBC callback **仍然**觸發（deopt 為保險性質非必要條件）。
- 政策空時：BBC callback 回傳 Samsung 原始物件（`BBC-HOOK callback (pid=…)` 不帶 `nonempty`），**位元一致僅指回傳物件，不代表裝置行為不變；冷啟動刪除見 `BBC_COLDSTART_DELETE_INCIDENT.md`**。
- BBC 合併前置條件：候選包 **installed（`ApplicationInfo` 非 null）∧ enabled**；否則 skip
  （Shopee `com.shopee.tw` 停用時自動 skip，符合預期）。
- Marker（`scope=daagent`）在 daagent 進程維持不可寫（EACCES）——與 S24 相同，
  daagent 安裝證據以 lspd 日誌（`found ... static=true`、`BBC-HOOK installed=1`）為準，非 marker。
- daagent 政策快照來源（unified build）：App 進程 `.GalaxyApp` 經 `remoteHookPolicyStore`（LSPosed remote preferences，group `dual_policy`／key `extra_whitelist_packages`）寫入；daagent hook 端經 `EffectivePolicyCache`（60s TTL 輪詢、5s 失敗節流、single-flight、50ms stall 預算）讀同一 group/key；cross-process listener 在 API 102 **不會**觸發（同行程 hygiene only，見 §1.1），新鮮度＝TTL 輪詢＋重啟語義。user 95 不讀（user-0 寫入、hook 讀 user 0；OQ6 observe-only 維持）。



### 1.4 JVM 測試邊界與啟用非空政策前的硬閘門（11-17 historical spec 用語；round-7b 已重新分類，見 round-7b 揭露段）

JVM 測試（`WhitelistPolicy`／`WhitelistSignatureGuard`／`WhitelistHookEligibility`／contract tests）
**僅證明純決策／選擇邏輯**。它們**不**證明：裝置上目標類別是否存在、方法簽名／宣告回傳型別、
hook 是否真的安裝、攔截器 callback 是否被命中、符合 eligibility 的 overload 在 Samsung DAAgent／
LSPosed 實機上是否真的被走到。`DualHook` 的 framework adapter、LSPosed 執行行為、ART 內聯／
去最佳化效果亦不在 JVM 測試範圍（見 §1.2 反內聯待辦）。

JVM 單元測試全綠（`testDebugUnitTest`）**不構成開啟 `NON_EMPTY_POLICY_EFFECTIVE_GATE` 的依據**——開 gate 只認下列硬閘門的裝置證據，不以 JVM 綠燈開啟 gate。

以下為現況（unified tree，2026-11-18）：**Stage-1 白名單查詢閘封緘常閉 `false`；BBC 注入效應開關 `true`（2026-11-17 裝置證據＋使用者授權，僅治理 BBC 清單合併）**。兩者獨立，互不繞過：

**效應開關一覽（非裝置驗證；裝置證據見 `docs/ACCEPTANCE.md` §6）：**

| 開關 | 值 | 治理面 | 備註 |
| --- | --- | --- | --- |
| `PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE`（Stage-1，sealed） | `const val false` | 白名單查詢面：`effective()` 的 sanitized 非空合併一律回空；`getAllWhitelistedPackages`／daagent 查詢 hook／`<clinit>` 合併 | 常閉；2026-11-17 使用者授權涵蓋的只有 BBC 效應面，全域翻閘授權**從未給過**；未來翻轉需另一份 SAMSUNG 階段 spec＋明示授權 |
| `PolicySource.BBC_AVAILABLE_APPS_INJECTION`（BBC 注入） | `const val true`（compile-time default） | 僅 `BbcInjector.mergeAvailableList` 的 BBC 清單 union-only 擴展（Dual Messenger「可用的應用程式」） | 2026-11-17 雙裝置裝置證據＋使用者授權（pre-unification build；2026-11-18 unified build 部分重驗（BBC1–BBC3；正向紀錄：BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載分身）；設 `false` 即 BBC 回傳物件與原物件位元一致（僅指回傳物件，不代表裝置行為不變；冷啟動刪除見 `BBC_COLDSTART_DELETE_INCIDENT.md`） |
| `dual_policy/bbc_injection_enabled`（執行期 opt-out，`PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED`） | 鍵缺席／首次讀取失敗 => `true`（出貨預設）；一旦確認 `false`，後續讀取失敗在 30 分鐘內維持 `false`（process-local，未經裝置驗證） | 與上列 compile-time 常數 **AND**；`false` 時 `getBbc` gate-closed 短路、BBC 不合併 | App「關於」對話框的「BBC 注入（雙開可用清單擴充）」開關經 `HookPolicyStore.setBbcInjectionEnabled` 寫入（同一 `dual_policy` remote prefs，單鍵 atomic commit）；hook 在 BBC 路徑以既有 prefs accessor 讀取。**可能需重啟 hook 行程或重新開機**（RemotePreferences 無跨行程推送／值有本地快取，直至 daemon 重啟／開機才保證讀到新值）。**這不是 Samsung 冷啟動刪除的防護**：DELETE 早於 BBC 回調、且回調非空（issue #75 / `BBC_COLDSTART_DELETE_INCIDENT.md`）。混合規則：首次失敗仍回 true，不因單一短暫讀失敗誤關效應；但一旦讀到 false，30 分鐘（`BBC_STICKY_MAX_AGE_MS`）內的失敗維持 OFF，直到讀到 true 才清除（狀態僅存在於 hook 行程內，重啟即消失）。gate 讀取已改走**專屬零隊列 daemon executor**（`readBbcInjectionEnabledBounded` → `PolicyRefreshBudget.readBoundedValue`），由可調 **15ms 上限**（`BBC_GATE_MAX_MS`）約束主線路 wall-time，timeout 即 fail-open（已確認 OFF 且仍在 30 分鐘內時維持關閉）；真實 Binder 延遲仍 **UNMEASURED／NOT a pass**。Stage-1 sealed gate 不受影響。 |

**BBC 執行期 opt-out 的誠實邊界（PR #74 G1）**：此偏好只是「不需重新編譯即可關掉 BBC 清單合併」的便利性開關，**不是安全保證**。Samsung 以 system uid 進行的冷啟動刪除發生在 BBC 回調之前 116–144ms，且非空政策下回調仍非空（issue #75）；即使此開關為 `false`，也**不能**據此宣稱分身不會被刪除。寫入後 hook 行程可能仍讀到快取值（RemotePreferences 無跨行程通知，見 §1.3a），故**可能需重啟 hook 行程或重新開機**才觀察到關閉。讀取語意為混合規則：**首次**讀取失敗／鍵缺席視為 `true`，避免在單一短暫 IPC 錯誤時意外改變出貨行為；但一旦成功讀到 `false`（確認 OFF），接下來 30 分鐘（`BBC_STICKY_MAX_AGE_MS`）內的讀取失敗會**維持 OFF**，直到成功讀到 `true` 才清除；若此 opt-out gate 自用的 wall clock（`System.currentTimeMillis()`）倒退到該時間戳之前，視為 TTL 失效並 fail-open（清除 confirmed OFF）——此為 opt-out gate 的 **process-local sticky** 語意，與 §1.1 所述 `EffectivePolicyCache` 政策集合 sticky 在單調時鐘取樣異常時 **fail-closed 丟棄快照**是**不同機制**，不可混為一談。此 sticky 狀態是 **process-local**（hook 行程的 `AtomicReference`，非跨行程、非裝置驗證，行程重啟即消失），不是 device-persisted 保證。gate 偏好讀取現已改走**專屬零隊列 daemon executor**（`readBbcInjectionEnabledBounded` → `PolicyRefreshBudget.readBoundedValue`），以可調 **15ms 上限**（`BBC_GATE_MAX_MS`）約束主線路 wall-time、timeout 即 **fail-open**：對尚未確認 OFF 的 opt-out 而言，15ms 逾時代表 BBC 照常注入（此為刻意 fail-open 預設，**不是**裝置 SLA）；卡死的 uninterruptible worker 僅能靠**行程重啟**恢復（此 executor 為行程生命週期、永不 shutdown）；真實 Binder 延遲與 DAAgent UI／ANR 仍 **UNMEASURED／NOT a pass**。JVM 測試鎖定偏好語意、gate AND 與混合失效規則（`BbcStickyPolicyTest`／`HookPolicyRemoteStoreTest`），**未在裝置上驗證 opt-out 本身**。

**round-3 opt-out／同意語意（R3-1..R3-7）**：偏好寫入改為三態——失敗（`commit(false)` 或拋錯）顯示 **生效未知（Unknown）**，**不是回滾**：本地 `RemotePreferences` 的 `mMap` 可能已被樂觀污染，故不得解讀為「已恢復舊值」；須**重啟 hook 行程**後重新讀取才能驗證。`Confirmed` 只涵蓋**已持久化的偏好**（daemon 已 ack），不代表 hook 的立即行為。寫入由 app-scoped 的 **latest-intent coordinator** 跨對話框／Activity 生命週期全局序列化（late-join 以最新意圖為準）。以上語意**皆非刪除防護**。另（D07）：損毀版本修復（Invalid→v1）在已接受較高版本的執行中 Hook 行程會被單調 high-water 門控拒收，**需重啟 Hook 行程**才生效；App 端 save 成功**不證明** BBC 已套用。（D03 同意柵欄）最終政策確認與清除現在 Activity 層與獨立 VM pre-IO 層**雙重重跑四道關卡同意檢查**；generation 失配、檢查失敗或同意不存在一律零寫入。generation 僅作 pending 身分綁定（binding-only），**不是新鮮度權威**；ROOT 驗證對話框繼續後（VerifiedEmpty 或 Residual/ProbeFailed 的自擔風險覆寫，後者非驗證成功）只跳過 installed 成員比對，(id,serial) 身分檢查仍強制；第一階段探測失敗的 consent 不可繼續（CannotDetermine）。以上皆為 JVM 可驗證的控制流性質，**不是裝置驗證**；P0 冷啟動刪除閘門**維持開啟、未關閉**。

**round-4 同意／UI 語意更新（E01–E07）**：第一階段探測失敗（Failed）的警告不再提供永遠無法履行的「仍然清除／寫入」做為政策寫入的 force-proceed；該入口改為「重試探測／取消」，寫入入口的按鈕／toast 改用「寫入」而非「清除」。consent fence 本身**不放寬**——`fromFailedProbe` 仍無條件 `CannotDetermine`（D03 fail-closed 不變）。新增「破壞性（δ=old−candidate≠∅）」與「增量／首次新增（δ=∅ 且 Known snapshot）」的同意規則區分：真正不移除任何項目的操作不再要求預先存在可驗證的雙開 profile；此例外**不適用**於任何縮減／清除，也不適用於 Unknown snapshot。共用版本探測對 `CancellationException` 改為透傳（App writer 的 `contains`／`getLong` 丟出 CE 時向上透傳、0 commit、無 poison）；Hook reader 在自己的邊界接住 CE 並維持 never-throw 與單調 high-water。VM 的生產寫入 seam 要求非 null consent，legacy bypass 限 test-only。BBC toggle 的 Unknown→Confirmed 在同一 dialog 解除灰化；探測失敗警告 dialog 納入 handle 於 onDestroy 釋放。Invalid→v1 修復顯示「可能尚未生效、需重啟 Hook 行程驗證、不代表新政策已套用」提示；epoch/generation 修復不在本輪範圍。以上皆為 JVM／UI 可驗證的控制流性質，**不是裝置驗證**；E00／E08 維持 **UNMEASURED／NOT a pass**，P0 合併閘門未關閉，**opt-out is NOT a deletion guard**。

**round-5 誠實註記（2026-11-23，F00–F06；更新而非取代 round-2+3+4）**：F01–F04 僅為 App／VM／UI 契約修正（Unknown 警告重新整理改為權威 `refreshPolicyDraft()` 並 await settle、仍 Unknown／Corrupt 給非遞迴引導，盲清正式撤回停用；Invalid→v1 修復提示 app-scoped 跨 VM 在 Main＋Editor 可見；BBC toggle CheckBox 於繼承 `Confirmed(value)` 時同步；VM 對非 null consent 重算移除 delta）。**F00（＝D01/N01 P0 冷啟動 DELETE）與 F06（＝D06 gate 延遲／D08 512-entry IPC／sticky／Scope A dialog）維持 UNMEASURED／NOT a pass**；P0 合併門檻未關閉。**三臂同基線對照（default BBC／confirmed-off runtime gate／Samsung baseline）與備份＋明確授權＋持有人同意前提完全不變**；JVM／UI 全綠不是通過。**opt-out is NOT a deletion guard**（issue #75）。Round-2 的 hybrid「confirmed OFF」語意、D03 consent fence 與 round-4 的三臂／備份＋同意框架皆未改變。

**round-5 實機唯讀觀察（2026-11-23）**：唯讀檢查發現非空政策已提交於 LSPosed remote-prefs DB、閘鍵缺席故推斷預設 ON（非 UI 確認），且 u95 分身啟動即 `Ssaid settings not accessible` 崩潰；未執行任何冷啟動／force-stop／破壞性臂，F00／F06 維持不變，**opt-out 不是刪除防護**（issue #75）；唯讀證據清單見外部 `/mnt/seagate500g/opencode_tmp/device_evidence/MANIFEST.md`（不入 repo、本 PR 未獨立稽核）。**觸發場景分析（非根因；含信心分級）見 `docs/ACCEPTANCE.md` §「2026-11-23 實機唯讀觀察」，不在本文重複。**

**round-5 三臂冷啟動對照（已執行／2026-11-23）**：同日後續於同一已備份授權裝置完成 A/B/C 三臂（BBC ON 非空注入／gate 確認 OFF 且無注入／移除本模組 scope 使 BBC hook 完全缺席），A/B 臂 DELETE 早於 BBC 回調（D−E/D−C = −83/−99 ms、−69/−89 ms）；C 臂無 BBC 回調可比（process-start 代理、低精度 +103 ms）。三臂皆為 system-uid DELETE 的**負向控制關聯（ASSOCIATION，非因果）**——**opt-out is NOT a deletion guard**；DELETE 前決策輸入與 F00/F06 仍 **UNMEASURED／NOT a pass**、P0 合併閘門未關閉，詳 `docs/ACCEPTANCE.md` §6.0 E-23-ABC。

**round-6 誠實註記（G03／G04；更新而非取代 round-2+3+4+5）**：G03 下修 SSAID 因果 certainty——直接存取未發現，但間接路徑（Samsung provisioning／clone 生命週期／狀態相依）未確立亦未排除，SSAID 根因 UNKNOWN；此為文件誠實性修正，非新 hook 缺陷。**系統 DELETE 軸與 SSAID 崩潰軸維持分離（非同一因果路徑）。**G04：512-entry IPC／15ms gate 真實 Binder 延遲／TTL 與 30 分鐘 sticky failover（含活躍 user-95 clone）／Scope A warning／ROOT dialog 仍 **UNMEASURED／NOT a pass**；2026-11-23 三臂 A/B/C 僅為負向控制關聯，且各臂 App build 不同、`pm install-existing` 不還原 prior user data，不能替代效能／UI／安全驗收；Android CI #334 手動模擬器矩陣略過（該 run 為 PR #74 **歷史 run**）。**beta.3 provenance**：run [#45228233599](https://github.com/samson910022/Dual-your-galaxy/actions/runs/45228233599)（API 29 automated harness 已執行、manual skipped；head `e5c61f51…`）／[#45233459919](https://github.com/samson910022/Dual-your-galaxy/actions/runs/45233459919)（Release 已發布；head `fc579de3…`）；兩者 tree 相同（`33614a81…`）但 head 不同、皆無 `tested-apk-provenance`；詳細限制與 [release tag](https://github.com/samson910022/Dual-your-galaxy/releases/tag/v0.1.0-beta.3)、[app-release.apk](https://github.com/samson910022/Dual-your-galaxy/releases/download/v0.1.0-beta.3/app-release.apk) 連結見 `docs/ACCEPTANCE.md` §1。**F00／F06 與 G00／G04 維持 UNMEASURED／NOT a pass，P0 合併閘門未關閉，opt-out is NOT a deletion guard**（issue #75）。不提出任何 SSAID／`settings_ssaid.xml`／ABX 修補。

**round-7b 範圍收斂揭露（2026-11-23；依最新 Scoped Review，優先於上列 round-2～6 的合併閘門用語）**：本版本（beta.3）之 BBC 主要效果為 **experimental／not device-verified on this version**；正向證據僅來自 2026-11-17（pre-unification，已退役的 listener reader 模型）與 2026-11-18 unified-build builds，早於 r1–r6 各輪，不用於驗證本版本（beta.3）。**Root/Shizuku 是操作路線；LSPosed BBC 是另一項實驗性能力，兩者不可混為一談。**Hook **可能早於首次 App UI 警告生效**（`BBC_AVAILABLE_APPS_INJECTION=true`、執行期偏好缺席視為啟用）；PR #74 **不重新設計既有安裝的 Hook default/migration**，App checkbox／第一次對話框**不是**「Hook 在生效前取得同意」；真正的 pre-activation opt-in 與安全遷移另列 follow-up（不可未經確認就縮減舊政策）。依 round-7b 維護者決策，P0 已於誠實揭露後**接受為 experimental limitation**；**H01–H04 與 #75 為 follow-ups，非 blocker**。本文其餘 UNMEASURED／NOT a pass 用語維持為風險揭露與研究清單，非 PR #74 合併門檻。

> **round-2+3 合併閘門（N01／D01、D06、D08、N09／D08；UNMEASURED／NOT a pass）**：本輪獨立審查未跑 Gradle 裝置測試；2026-11-23 曾在 Samsung 裝置做**唯讀**觀察（未執行任何破壞性臂／冷啟動），已文件化或 JVM／CI 全綠皆**不是**通過。必要裝置證據與同基線三臂對照見 `docs/ACCEPTANCE.md` §6「11-21／11-22 round-2+3 待實機驗收」小節：
> - **N01／D01**：P0 冷啟動 DELETE 因果未證實；須先完成**同一 installed-state 基線三臂對照**（default BBC／confirmed-off runtime gate／Samsung baseline），缺一即不得宣稱安全或因果成立。runtime opt-out **不是刪除防護**（issue #75；DELETE 早於回調且回調非空）。
> - **D06**：opt-out gate 讀取已有專屬零隊列 bounded executor 與 15ms 可調上限（JVM 已證主線路 wall-time 有界，timeout fail-open）（已確認 OFF 且仍在 30 分鐘內時維持關閉）；阻塞 provider >200ms 時主線路返回上界，但真實 Binder 延遲與 DAAgent UI／ANR 仍 **UNMEASURED／NOT a pass**。
> - **D08／N08**：hot path 1/50/200/512 的 wall-time p50/p95/max 與 Binder IPC 次數（含 cold process）未量測；`BbcMergeTimingTest` 僅量純內存合併。須以 x-trace 量測並訂有界 fallback 與 measured go/no-go 閾值。
> - **N09／D08**：sticky 30 分鐘到期／fresh DAAgent 行程為有界降解、非安全保證；須分別觀測 force-stop／重啟／>30min 持續失敗後的 available-list 與 user/profile 資料狀態。
>
> **授權前提**：凡涉及分身刪除或 user-95 資料清除的步驟，必須在明確授權且已備份的裝置上由持有人親自執行；未經授權不得重跑破壞性實驗（issue #75）。round-2 hybrid「confirmed OFF」語意本身仍須實機驗證（見上；RemotePreferences 無跨行程推送）。

**程式碼層級 sealed gate 兩項（常閉，JVM 測試鎖定；皆非裝置驗證）：**

| # | sealed gate | 行為 | JVM 鎖定 |
| --- | --- | --- | --- |
| S1 | `PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE`（`const val = false`，編譯期封緘，執行期不可翻轉） | `effective()` 的 sanitized 非空合併一律回空集合 | `PolicySourceTest`（gate 預設關閉斷言） |
| S2 | `EffectivePolicyCache.get()` 閘門關閉短路（Stage C） | 關閉時直接回空，不碰 reader、不加鎖、不讀時鐘（零 IPC 讀取） | `EffectivePolicyCacheTest`（gate-closed 零讀取） |

> （本段為 11-17 歷史授權紀錄，效力受 round-7b 揭露節制）
>
> **硬閘門（hard gate，缺一不可；11-17 historical spec 用語，round-7b 已重新分類，見 round-7b 揭露段）— 四個分離時點：**
>
> ① **11-17 歷史授權（pre-unification 舊閘）**：owner 依 11-17 兩裝置證據授權 BBC 清單注入效應（commit `71920f95`）；**該 commit 的常數是 `NON_EMPTY_POLICY_EFFECTIVE_GATE: Boolean = true`（舊閘）；當時尚未有 `BBC_AVAILABLE_APPS_INJECTION` 常數，不得把現行 BBC 專用開關直接歸給此 commit**。BBC 效應開關到 unified tree 才拆分命名（見 ②／TAB7 Addendum B）。raw log 私有、本 PR 未獨立稽核（E-17-P-S23-BBC1–BBC5／E-17-P-S7-LIST）。
> ② **11-18 unified build**：僅 BBC1–BBC3 reader 接線部分重驗正向紀錄；BBC4／BBC5 UNVERIFIED、BBC6 破壞性 fail；共享 `dual_policy/extra_whitelist_packages`→`EffectivePolicyCache` 輪詢讀者，**不構成全項目重驗**（E-18-U-S23-BBC1–BBC6）。效應面拆為 BBC-only `BBC_AVAILABLE_APPS_INJECTION=true`、Stage-1 `NON_EMPTY_POLICY_EFFECTIVE_GATE` 重新封緘 `false`（TAB7 Addendum B）。
> ③ **目前 beta.3**：BBC effect experimental／本版本未實機驗證、無 tested-byte provenance（E-B3-HEAD；詳 `docs/ACCEPTANCE.md` §1／§6.0）。
> ④ **round-7b 維護者決策**：M1/M2 為合併門檻、P0 以 experimental limitation 接受揭露、H01–H04/#75 為 follow-ups——**不是安全宣稱、不重啟 P0 為合併門檻**。
>
> **unified/current Stage-1 閘** `NON_EMPTY_POLICY_EFFECTIVE_GATE` **自 11-18 重新封緘 `false` 後未曾翻轉、維持封緘 `false`**（現行 beta.3 同此）；11-17 pre-unification 舊閘（commit `71920f95`）曾為 `true`，與 unified/current Stage-1 不是同一閘，不得據以宣稱『未曾翻轉』。

> provenance 摘要：本節裝置證據僅代表 pre-unification build 之 BBC 合併行為（BBC jar 於兩裝置逐字等價，見 §1.3a）；unified build 的 BBC 注入效應由 `PolicySource.BBC_AVAILABLE_APPS_INJECTION` 治理，reader 接線部分重驗（2026-11-18，BBC1–BBC3 正向紀錄；BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6=系統自動卸載；`BBC-HOOK merged +1 packages (total 7, pid=…)` 重現）。詳 `docs/ACCEPTANCE.md` §6。

1. ✅（HISTORICAL；11-17 歷史觀察／raw log 私有／本 PR 未獨立稽核） 裝置驗證每個欲 hook 查詢的**完整方法簽名＋宣告回傳型別**（pre-unification build，BBC 查詢法）：BBC jar 反編譯一致
   （Tab S7 One UI 5：`PmWrapper.getPossibleDualAppPackages(Context)->ArrayList`；
   S23 Ultra One UI 9：同 BBC 庫、同全稱方法，dexdump 雙方逐字一致）。
2. ✅（HISTORICAL；11-17 歷史觀察／raw log 私有／本 PR 未獨立稽核） 每個方法的 **hook 安裝數**證據：`BBC-HOOK installed=1 (daagent, pid=21472)`（S23，LSPosed verbose mirror）；`BBC-HOOK installed=1 (daagent, pid=7812／18538／21602)`（Tab S7，logcat）；
   安裝失敗會記 `BBC-SPIKE FAIL`/`installFailures=…`，不視為生效。
3. ✅（HISTORICAL；11-17 歷史觀察／raw log 私有／本 PR 未獨立稽核） **callback 命中**證據：`BBC-HOOK callback getPossibleDualAppPackages (pid=21472)`、
   `BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=21472)`（2026-11-17 S23 Ultra）；Tab S7 `BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=21602)`，能區分「hook 已裝」與
   「查詢路徑真的被攔截」。
4. ✅（HISTORICAL；11-17 歷史觀察／raw log 私有／本 PR 未獨立稽核） user／profile isolation 語義：BBC 路徑候選包 predicated on
   `installed(ApplicationInfo!=null ∧ enabled==true)`；userId 過濾面不擴充
   （`*Installed*Whitelist*` 複合查詢維持 fail-closed 排除），雙開實例落在
   目標 ROM 的 dual profile（S23 Ultra 實機實測 user 95：`u95_a431`，持久化記錄見 `s23_bbc_evidence_20261117.log`；Tab S7 的安裝觀察僅存在於未持久化的 session 輸出，無 `u95_a306` 檔案證據，不得引作定案），
   可正常 launch；未觸及 user 150（Knox）。
5. ✅（HISTORICAL；11-17 歷史觀察／raw log 私有／本 PR 未獨立稽核） 目標 ROM 上**受控空政策 vs 非空政策對照**（pre-unification build）：Tab S7（`RemotePreferences initial load: 0→3 package(s)`；
   空政策 BBC callback 回傳原始物件；非空時 `BBC-HOOK merge added 0 packages (policy=3: com.shopee.tw: installed=true, enabled=false, org.mozilla.firefox: installed=true, enabled=true, com.google.android.apps.bard: installed=true, enabled=true)`＝Samsung 白名單
   已含 Firefox/Bard，union no-op）與 S23 Ultra（`0→1 package(s)`；
   ChatGPT 合併 `BBC-HOOK merged +1 packages (total 7, pid=21472)` 並成功在 Samsung 設定頁出現、可切換、雙開安裝並 launch；
   空政策回退之回傳物件位元一致（僅指回傳物件，不代表裝置行為不變；冷啟動刪除見 `BBC_COLDSTART_DELETE_INCIDENT.md`），無崩潰、無列表污染、Knox 路徑無觸及）。
6. ⏳ **寫入→hook 可觀測傳播**（P1-1 poll 模型，缺一不可）：App 寫入草稿（`dual_policy/extra_whitelist_packages`；`extra_whitelist_version` 版本序號 bump **Phase 3 已實作**（HookPolicyStore save/clear＋1，與 packages 同一 atomic commit；hook 側 seqlock＋Good/Cleared版本閘），但版本僅為可觀測新鮮度證據、非推送前提）後，需在 hook 側觀測到傳播——TTL 輪詢重讀命中新集合（附 logcat＋時間戳，證明非陳舊快取），或重啟後 hook 讀到新值（重啟語義對照）。無推送即時性宣稱：兩次觀測間隔須落在 TTL／重啟語義內，單次「寫後立即讀到」不得當作推送證據。JVM 以 `CrossProcessFreshnessContractTest` 鎖定「無跨行程通知」契約（invalidate 僅本地、TTL 始為輪詢機制）。**部分：11-18 BBC2/BBC3 callback＋merged 正向紀錄（`BBC-HOOK callback getPossibleDualAppPackages (nonempty, pid=…)` + `BBC-HOOK merged +1 packages (total 7, pid=…)`；BBC2 空政策 baseline callback 帶 `pid=…` 不帶 nonempty）；寫入→toggle→install→launch 完整傳播 ⏳ UNVERIFIED；11-18 S23 為 unified build 部分重驗（E-18-U-S23-BBC2/3）；11-17 S23 為 pre-unification 歷史觀察（E-17-P-S23-BBC2/3）**

上述 1–5 為 pre-unification build 之裝置證據（2026-11-17）；unified build 部分重驗＝2026-11-18（S23 Ultra；BBC1–BBC3 正向紀錄；BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED；BBC6 destructive fail；本 PR 未獨立稽核）。Samsung BBC6 刪除分身後 SSAID 拒絕；恢復 = 裝置重啟 + Samsung 原生 toggle-on（見 BBC6_SSAID_INCIDENT.md）；BBC6 毀滅分身——不得標 ☑。附近結論以 `docs/ACCEPTANCE.md` §6.0 矩陣為唯一真相來源。〔11-20/11-21 更新優先於此 11-18 結論：非空政策下亦遭冷啟動刪除，DELETE 早於回調 116–144ms，見 §1.3a provenance 註記與 `BBC_COLDSTART_DELETE_INCIDENT.md`〕

以下保留作為長期約束，**不得**移除：

Stage 1 明確保持 `*Installed*Whitelist*` 排除：daagent 不掛
`getAllInstalledWhitelistedPackages`／`isInstalledWhitelistedPackage`；system_server 不掛
`isInstalledWhitelistedPackage*`。原因為 install-state／userId 歧義——在語意未經裝置驗證前
fail-closed，寧可 observe-only 也不錯誤放寬。

### 1.5 非目標進程：`detach()`（H4：自報 scope 已退場，見下）

`onPackageLoaded` 第一件事就是檢查 `SUPPORTED_SCOPES`；不在名單內的進程
立即呼叫 **`detach()`**，該 entry 在此進程不再收到後續生命週期回調
（已安裝的 hook 不受影響）。`SUPPORTED_SCOPES` 與
`META-INF/xposed/scope.list` 兩行保持一致：

```
system
com.samsung.android.da.daagent
```

system vs android 釐清：`system` 是 LSPosed 作用域偽名（代表 system_server，
走 `onSystemServerStarting` 注入）；`android` 才是 framework 實包名，
`onPackageLoaded` 在 system_server 進程收到 `android`（或 `system`）時
直接返回、**絕不 `detach()`**（避免在 system_server 內自斷後續回調）。
`com.android.settings` 已移除：目前沒有任何 settings 側 hook，
留空 scope 只會擴大注入面；將來真有 settings 側檢查再加回。

自報 scope（H3 評估、H4 退場）：自 scope prefs 自報方案已由 F4b-P2
file marker（`HookMarker`：自 Phase 5 起每 scope 各寫一個檔，
`/data/system/galaxydual_hook.active.{system_server,daagent}` 為主、
`/data/local/tmp/…` 同名為備（僅 tmp+rename 寫入；rename 被拒＝marker 不寫成，直接覆寫的後備路徑已移除；是否顯示「未偵測到」依本文件 §1.5 末段與 docs/USAGE.md §4（P1 四態）——僅當確定可讀但缺失時顯示；無可判讀通道時一律顯示「狀態未知」；事實上，一個 gateway 六條皆返回 exit 0 且六份內容皆非 malformed（空白或可解析且無 scope 之乾淨標記）時，即使直接讀因 EACCES 全拒絕，仍可裁定「未偵測到」——取決於讀取當下的判讀，非固定字串；tmp+rename 亦隱含兩項副作用：寫入前會掃除同目錄下逾 60 秒的 `*.tmp-*` 孤兒暫存檔（至多 16 個），以及對父目錄呼叫 `mkdirs()`）；
parser 仍相容舊版單檔
`/data/system/galaxydual_hook.active`＋`/data/local/tmp/...`，App 側恆 union
六路徑，scope `system_server`／`daagent`；`/data/local/tmp` 路徑僅作存在性證據（1777 目錄下，凡該 ROM 的 SELinux 策略允許寫入 shell_data_file 的域——例如 shell／root，S24 上 system_server 皆得 EACCES；本機 app 能否寫入亦屬 ROM 相關——皆可預置 `scope=system_server,daagent`
內容偽造「偵測到歷史 Hook 標記」，不證明 hook 安裝、不證明權限），見本文件 §1.2／§1.3
與 `HookMarker.kt`）取代，不再注入自家 UI 進程。理由：file marker 有
S24 實機 EACCES 經驗＋安裝數證據（未安裝不寫，誠實 "not detected"），
是目標 hook 存活證據；自家 prefs 只能證明自注入，需額外 2 scope 的
注入面卻換來更弱的證據。hook 啟用訊號的唯一來源是 file marker，
App 側經 `HookStateProvider` 讀取、僅顯示、永不進入路由
（`routePrivilegeFromProbes` 已無 `hasLsposed` 參數；hook 訊號不得作為授權輸入，見 `DualViewModel.probeChannels`）。

Marker 實測現況（S24 [S24 已驗證]，root 讀取；五層之 marker 歷史證據，
詳 [S24 調查](UX_REVIEW_S24.md) §4.4／§5）：
- 主路徑已寫入：`scope=system_server`（system_server 側 hook 安裝後寫入成功；
  早於本次開機約 5.5h，為歷史證據，不能宣稱本次開機有效）。
- daagent 側雙路徑 EACCES：主＋備皆不可寫（daagent SELinux 域限制），
  故 marker 目前僅含 `system_server`；daagent hook 安裝證據以 lspd 日誌
  `found ... static=true`＋clinit 行為準，不以 marker scope 齊全為準。
- App uid／`run-as` 皆不可讀任一路徑 [S24 已驗證]：直接讀取恆不可讀——P1 起不再顯示為「未偵測到」（假陰性），而是「狀態未知（無權限讀取／無可用診斷通道）」（配合 [#27](https://github.com/samson910022/Dual-your-galaxy/issues/27)）；僅當確定可讀但缺失時才顯示「未偵測到」。明選 LSPosed（NoOp 佔位）時診斷仍走獨立路徑：直接讀取＋經當時已探測通過的真通道（ROOT／SHIZUKU）gateway `cat`（[待實機驗證]）；無任何真通道時為「狀態未知（無可用診斷通道）」，需切到 ROOT／Shizuku 真通道後重探才能完整診斷。診斷結果永不進入路由／`channelReady`（沿襲 #19）。
- daagent 新類路徑（provider 首選）掛載 [S24 已驗證]：
  `using com.samsung.android.da.daagent.provider.WhiteListApps for daagent hooks`
  連續命中（daagent 自然重啟後新碼載入，零退回、零 `not present`）。

## 2. Scope 勾選教學

### 2.1 安裝：主用戶＋雙開用戶都要裝

> **安裝／升級警告（v0.1.0-beta.3）— 請先讀完本警告再點 Release 連結：**
> **先確認目前安裝的 id**：若是舊 id `com.galaxydual.plus` → 全新安裝、不遷移舊資料、不可與新 id 共存、先備份並確認可還原；若已是 `io.github.samson910022.galaxydualplus`（含 beta.2 → beta.3）→ **同 id、同簽章 in-place 升級，不需卸載**，Android 通常會就地保留該管理 App 的資料，但**不保證 Samsung 分身或其資料完好**。
> Samsung 可能自動刪除分身並永久清除資料；一般既有雙開 App 不需為此套件 id 遷移而**手動移除**，且這**不表示**分身「不受影響」——本 App 沒有刪除防護。
> release applicationId `io.github.samson910022.galaxydualplus`，自建 debug APK 為 `io.github.samson910022.galaxydualplus.debug`（是**不同套件**；舊 id `com.galaxydual.plus` 的資料**不會遷移**），其 applicationId 與 `module.prop` 的 module id `io.github.samson910022.galaxydualplus` 不一致（已知限制、不保證被 LSPosed 正確載入）。**無內建匯入／匯出**，請先備份並**確認備份可還原**。**新舊管理 App／LSPosed 模組不可同時啟用**（一般既有雙開 App 不需為此遷移**手動移除**，資料也不會合併）；不同 applicationId／module id／remote-prefs authority 會被系統視為不同個體。停用／卸載／opt-out／重開機**都不是**刪除防護；beta.3 的 hook **可能早於首次 App UI 警告生效**。BBC 清單注入仍為 experimental／本版本未實機驗證，LSPosed 寫入通道仍為 NoOp 佔位。**截至 v0.1.0-beta.3（`fc579de3`）尚無 pre-activation opt-in；PR #90 合併後以 `docs/HOOK.md` §1.4／ADR 為準。**
> **已發布的 Release page 目前仍有舊身分／A/B/C 文字；maintainer 同步更新前，以本警告與 repo 內 `docs/RELEASE_NOTES_beta3.md` 為準。**

1. 主用戶（user 0）安裝本 App 的**已簽章 release** `app-release.apk`（applicationId `io.github.samson910022.galaxydualplus`；一般使用者請用 [GitHub Release](https://github.com/samson910022/Dual-your-galaxy/releases/tag/v0.1.0-beta.3) 的版本化 asset）。若已裝同一 id（beta.2 → beta.3），直接覆蓋更新即可，不需卸載。
2. 查出雙開用戶的 userId（常見為 `95`，機型而異，以實際輸出為準）：
   ```bash
   adb shell pm list users
   #   Users:
   #     UserInfo{0:Owner:13} running
   #     UserInfo{95:DUAL_APP:400} running
   ```
3. **一般雙開用戶路徑**：把與 step 1 **同一個已簽章 `app-release.apk`** 裝到 user 95（以 `95` 為例，`<路徑>` 為你下載的 asset 路徑）：
   ```bash
   # 一般安裝：與 step 1 相同的 signed release APK（新安裝）
   adb install --user 95 <路徑>/app-release.apk
   # 已裝同一 id 時更新（明確為更新指令，不是實機驗證宣稱）
   adb install -r --user 95 <路徑>/app-release.apk
   ```

   **`--user 95` 裝的是管理 App 本體的第二份獨立資料**，僅供 LSPosed 按用戶作用域啟用之用途，**不能用來寫政策**——政策只能由 owner user（user 0）寫入（見 [docs/USAGE.md](USAGE.md) §「政策寫入流程／只有 owner user（user 0）能寫入／清除草稿」）。這份資料**不會與 user 0 的資料合併，也不會與舊 id 版本合併**。

**開發用（選配）debug 範例** — 一般使用者請用上方 signed release 路徑；以下僅供開發／歷史復現：

> ⚠️ **執行前警告**：自建 debug APK 的 applicationId 為 `io.github.samson910022.galaxydualplus.debug`，是**不同套件**，與 `module.prop` 的 id `io.github.samson910022.galaxydualplus` 不一致（**已知限制，如實揭露**：LSPosed 以 module id 辨識模組，**不保證被正確載入**）；一般使用者請用上面的 signed release 路徑。
>
> ```bash
> adb install --user 95 app/build/outputs/apk/debug/app-debug.apk
> ```

### 2.2 LSPosed 勾選（＋ App 內通道選擇）

> **安裝／升級警告（v0.1.0-beta.3）**：勾選作用域／重開機前請先讀本文件 §2.1／[docs/USAGE.md](USAGE.md)「安裝」節的完整警告與 repo 內 `docs/RELEASE_NOTES_beta3.md`：全新安裝僅相對於舊 id `com.galaxydual.plus`（beta.2→beta.3 同 id 為 in-place 升級、**不需卸載**）、**無資料／偏好自動遷移**、無內建匯入匯出，先備份並確認可還原；**舊版管理 App 與其 LSPosed 模組不可與新版同時啟用**。Samsung 可能自動刪除分身並永久清除資料；一般既有雙開 App 不需為此套件 id 遷移而**手動移除**，且這**不表示**分身「不受影響」——本 App 沒有刪除防護。停用／卸載／opt-out／重開機不是刪除防護，beta.3 hook 可能早於首次 App UI 警告生效。**已發布的 Release page 仍有舊身分／A/B/C 文字；maintainer 同步前以上述警告與 `docs/RELEASE_NOTES_beta3.md` 為準。**

1. 打開 LSPosed 管理器 → 模組 → 勾選 **Dual your Galaxy**（LSPosed 模組）並啟用；此名稱即 App 顯示名稱——`module.prop` 無 `name` 鍵、hook manifest 無 `android:label`，以 App 的 label 為準。
2. 作用域（Scope）勾選以下兩項（H4：自家包自報已退場，file marker 為唯一訊號）：
   - `system`（系統框架，必勾，否則 system_server hook 不生效）
   - `com.samsung.android.da.daagent`（三星雙開代理）
3. 若管理器支援按用戶啟用：主用戶與雙開用戶（user 95）**兩邊都要啟用**。
   不同 LSPosed 版本 UI 不同（全局作用域＋按用戶開關，或按用戶各自勾選），
   原則不變：兩個用戶都要開、都要含 `system`。
4. App 內通道選擇（F4b-P1 手動 picker，見 `docs/USAGE.md` §4）：打開 App，
   用四按鈕（自動／ROOT／Shizuku／LSPosed）選擇通道；LSPosed 模式顯示
   Hook marker 行（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，見 §1.5 與 P1 四態），
   該行僅顯示、永不授權——LSPosed 通道為 NoOp 佔位，寫入一律拒絕。

### 2.3 重啟需求

> **安裝／升級警告（v0.1.0-beta.3）**：重開機**不是**刪除防護；一般安裝／升級請先讀 §2.1 完整警告與 repo 內 `docs/RELEASE_NOTES_beta3.md`（全新安裝僅相對於舊 id `com.galaxydual.plus`，beta.2→beta.3 同 id 為 in-place 升級、**不需卸載**；無資料遷移／內建匯入匯出、先備份並確認可還原；新舊管理 App 與其 LSPosed 模組不可同時啟用）。Samsung 可能自動刪除分身並永久清除資料；一般既有雙開 App 不需為此套件 id 遷移而**手動移除**，且這**不表示**分身「不受影響」——本 App 沒有刪除防護。**已發布的 Release page 仍有舊身分／A/B/C 文字；maintainer 同步前以上述警告與 `docs/RELEASE_NOTES_beta3.md` 為準。**

- **首次啟用／改動作用域後必須重啟**：`system_server` 注入只在開機時發生，
  不重啟等於沒 hook。建議直接完整重啟手機（軟重啟若管理器支援亦可）。
- 僅更新模組 APK（未改 scope）：API 102 支援 hot reload，但系統進程建議仍重啟一次確認。
- 驗證 BBC 注入與非空政策（僅 11-17 pre-unification／11-18 unified build 舊 build 證據；**本版本（beta.3）experimental／未實機驗證**，見 §1.3a）：
  先確認政策非空（App → MainActivity 技術卡「編輯應用清單」→ `PolicyActivity` 勾選目標 app →「寫入政策草稿（N）」→ 確認寫入）；再看擷錄（兩裝置擷錄路徑不同）：
  - S23 Ultra：`adb shell su -c 'grep -E "BBC-HOOK|BBC-SPIKE|RemotePreferences" /data/adb/lspd/log/verbose_*.log'`（BBC-HOOK 行只出現在 LSPosed verbose mirror；logcat 只看得到 `RemotePreferences` 兩行）。
  - Tab S7：直接 `adb logcat -d -s GalaxyDualHook | grep -E "BBC-HOOK|RemotePreferences"`。
  再看 Samsung 設定的 Dual Messenger 頁「可用的應用程式」是否出現目標 app：
  `BBC-HOOK callback getPossibleDualAppPackages (nonempty...)` 與
  `BBC-HOOK merged +N packages`（空政策時回傳 Samsung 原始物件，無 merged 行），
  沒看到 merged 行不代表失敗——政策可能仍是 Samsung 白名單內 app（union no-op）。
  **TTL 新鮮度（誠實口徑）**：unified build 無跨行程推送（API 102）——**60 秒僅為 hook 行程本地快取 TTL**（`EffectivePolicyCache.maxCacheAgeMs`）；到期重讀的是同一個永久快取的 `RemotePreferences` 實例，底層 `mMap` 可能仍陳舊。**不保證 60s 內或下一次 poll 看到新集合；重啟 hook 行程後才保證讀到新值（無即時推送）。** 60s 內看不到變化不構成異常；版本鍵（Phase 3）為可觀測新鮮度證據，非推送替代（重啟語義見 §1.1）。
- 驗證 hook 安裝：重啟後 `adb logcat | grep GalaxyDualHook` 可能看到
  `hooked ...#getAllWhitelistedPackages` 等行＋
  `BBC-HOOK installed=1 (daagent, pid=…)` 行；看不到可能是記錄不可取得、安裝失敗或
  類名／方法名不匹配，不能單憑缺日誌判定，回報時請附上 `service list` 與版本號。
- 驗證 marker 檔案（需 ROOT／shell 讀權限；App 直接讀不到即顯示「狀態未知」，
  屬正常 fail-closed，不代表 hook 沒裝；僅確定可讀但缺失才顯示「未偵測到」）：
  ```bash
  for p in /data/system/galaxydual_hook.active.system_server \
           /data/system/galaxydual_hook.active.daagent \
           /data/local/tmp/galaxydual_hook.active.system_server \
           /data/local/tmp/galaxydual_hook.active.daagent \
           /data/system/galaxydual_hook.active \
           /data/local/tmp/galaxydual_hook.active; do
    adb shell su -c "cat $p" 2>/dev/null
  done
  # Phase 5 起每個 scope 各寫一個檔（system_server / daagent 進程各自只寫自己的；
  # 舊版單檔 galaxydual_hook.active 仍會被讀取相容）：
  #   /data/system/galaxydual_hook.active.system_server -> scope=system_server
  #   /data/system/galaxydual_hook.active.daagent       -> scope=daagent
  #   timeMs=1710000000000
  ```
  App 內 LSPosed 模式的 Hook 行顯示同一內容（歷史標記文案／`未偵測到`／`狀態未知（…）`）。

## 3. 免責聲明

- 本模組僅放寬白名單**查詢**，不讀寫、不掛載、不繞過 Secure Folder／Knox；
  任何 Knox 熔斷規避皆明確不在範圍內。
- 需 ROOT＋LSPosed 環境；操作系統進程本來就有風險（ bootloop 可能性雖低但存在，
  請先備份）。後果自負，作者不承擔裝置變磚、資料遺失、保固失效責任。
- 全部 hook 邏輯為本專案自創（MIT），第三方僅為思路參考（見 `docs/CREDITS.md`），
  未複製任何 GPL 授權代碼。
- 僅供學習研究與個人裝置使用；請遵守當地法規與三星服務條款。

## English Summary

- **BBC injector (historical old-build evidence only — device-verified 2026-11-17 pre-unification; partially re-verified 2026-11-18 on the unified build (BBC1–BBC3 only; current beta.3 experimental / not device-verified on this version) — BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）; BBC6 destroys the dual app)**: `PmWrapper#getPossibleDualAppPackages(Context)` is the
  single source of Samsung's Dual Messenger "available apps" rows (both `DualAppActivity`
  render path and `DAUtility.getWhiteListPkgs(true)`); alike BBC jars ship in One UI 5
  (Tab S7, One UI 5.0 / Android 13, DAAgent 3.3.00.0) and One UI 9 (S23 Ultra, One UI 9 /
  Android 17 “90000”, DAAgent 3.7.00.0, KernelSU). The BBC algorithm is byte-equivalent:
  `installed ∩ enabled ∩ SemDualAppManager.getAllWhitelistedPackages()`. The hook
  union-appends the user-written policy draft (group `dual_policy` / key `extra_whitelist_packages`,
  whole-set replace, owner-user write) behind `installed(ApplicationInfo!=null ∧ enabled==true)`
  predicates (union-only, identity fast-path on no-merge, PROTECTIVE, fail-closed back to Samsung's
  original object on any error), read via `EffectivePolicyCache` (60s TTL poll, 5s failure cooldown,
  single-flight, 50ms stall budget; no cross-process listener push on API 102 — freshness = TTL poll
  + reboot semantics only; the 60s is only the local cache TTL and the permanently cached
  RemotePreferences mMap may stay stale, so neither "within 60s" nor "on the next poll" is guaranteed
  — only a hook-process restart guarantees the new value). Phase 3 adds a monotonic version key with a
  hook-side seqlock snapshot and Good/Cleared version gates as observable freshness evidence, **not**
  a push substitute. Effect gate: `BBC_AVAILABLE_APPS_INJECTION=true` (`PolicySource.kt`;
  governs only the BBC merge) vs Stage-1 `NON_EMPTY_POLICY_EFFECTIVE_GATE=false` (sealed; whitelist
  queries / system_server / <clinit> unchanged, bit-identical return object only — not evidence of
  unchanged device behavior, see the cold-start deletion note below — installs unchanged).
  Evidence (pre-unification build, 2026-11-17): S23 Ultra — `BBC-HOOK installed=1 (daagent, pid=21472)`,
  `BBC-HOOK callback getPossibleDualAppPackages (pid=21472)`, `… (nonempty, pid=21472)`,
  `BBC-HOOK merged +1 packages (total 7, pid=21472)`; ChatGPT appeared in Samsung's settings page,
  dual instance installed and launched (u95_a431). Full capture: `s23_bbc_evidence_20261117.log`
  (LSPosed verbose mirror) with corroborating 2-line logcat `s23_bbc_gate_true.log`. Tab S7 —
  `BBC-HOOK merge added 0 packages (policy=3: …)` (Firefox/Bard already in Samsung's runtime
  whitelist → no-op); Firefox listed with toggles visible; merge no-op; `s4_gate_true_bbc.log`.
  Unified build shares the same BBC algorithm (BBC jar byte-equivalent on both devices) but the
  reader path changed — evidence did not carry over automatically; re-verification = Stage 8.3 (historical old build, partial re-verification (BBC1–BBC3) 2026-11-18; experimental / not device-verified on this version (beta.3) — BBC1–BBC3 positive records only; BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）; BBC6 destroyed the dual app (see BBC6_SSAID_INCIDENT.md))
  (S23 Ultra, partial re-verify (BBC1–BBC3)). See `docs/ACCEPTANCE.md` §6.0 (unique evidence matrix).
  Runtime whitelist is device-persisted and not byte-for-byte identical to the 19-entry framework
  fallback constant.

  > **2026-11-20/11-21 update (supersedes this document's 11-18 conclusion)**: even under a non-empty
  > policy, a daagent cold start was followed by Samsung deleting the u95 clone as system uid, with
  > DELETE preceding the BBC callback by 116–144 ms; toggle-ON restores it but all data is wiped.
  > The 11-18 "only empty policy deletes" / "bit-identical return = zero behavior change" claim does
  > not describe the cold-start risk. See `docs/BBC_COLDSTART_DELETE_INCIDENT.md`, issue #75 (corrected 2026-11-21: the 11-20 callback was non-empty; `BBC_COLDSTART_DELETE_INCIDENT.md` remains authoritative). All
  > 11-17/11-18 verification paragraphs above are qualified by this note.
- **Stage 1 (fail-closed):** `*Installed*Whitelist*` composite queries
  (`isInstalledWhitelistedPackage*`, daagent `getAllInstalledWhitelistedPackages`/
  `isInstalledWhitelistedPackage`) are excluded due to install-state/userId ambiguity.
  System_server hooks only the pure list query `getAllWhitelistedPackages()` when eligible.
  Daagent hooks 6 named read queries
  (`getWhitelistedPackages`/`getWhitelistPackages`, `getWhiteListPkgs`/
  `getWhiteListPkgsInSystemServer`, `isWhitelistedPackage`/`isWhiteListedPackage`)
  plus a `*hitelist*` sweep on `WhiteListApps` that is **Stage 1 diagnose-only (no hook installs)**
  due to mutation/read-only ambiguity on ROM variants (mutation classification +
  eligibility logged, fail-closed).
- **Stage-1 gate SEALED (unified tree)** — 〔see §1.4; `NON_EMPTY_POLICY_EFFECTIVE_GATE` stays sealed `false`
  forever until a future spec on Samsung stages; the BBC effect switch `BBC_AVAILABLE_APPS_INJECTION=true` shipped
  on the strength of two devices' 2026-11-17 empty-vs-nonempty A/B evidence plus explicit user authorization
  (pre-unification build; unified build partially re-verified 2026-11-18 (BBC1–BBC3 only; old build; experimental / not device-verified on this version (beta.3)) — BBC4（`pm path` 17:56 本機 log，未入 repo）／BBC5（17:22:20 啟動，無並存 `ps`）皆 UNVERIFIED（本 PR 未獨立稽核）；BBC6 後 SSAID 拒絕；恢復=重啟+Samsung toggle-on（見 BBC6_SSAID_INCIDENT.md）; BBC6 destroys the dual app). A code-level kill switch is kept:
  set the BBC const back to `false` to make the BBC callback return object bit-identical to
  Samsung's original object (this refers only to the returned object, not to unchanged device
  behavior — cold-start deletion see `BBC_COLDSTART_DELETE_INCIDENT.md`). A runtime opt-out was
  added in PR #74 G1: `dual_policy/bbc_injection_enabled` (App "About" dialog checkbox) is ANDed
  with that const; absent and the FIRST read failure default to `true` (shipped behavior), but once
  `false` has been confirmed, later read failures keep the gate closed for up to 30 minutes
  (`BBC_STICKY_MAX_AGE_MS`, process-local, cleared on hook-process restart; a backwards wall clock
  fails open). `false` closes the BBC gate without a rebuild. This is NOT a deletion guard.
  It MAY require a hook-process restart or a reboot (RemotePreferences caching;
  cached values can stay stale until the daemon restarts or the device reboots),
  and it is NOT a proven guard against Samsung's system-side cold-start deletion — DELETE precedes
  the BBC callback by 116–144 ms (issue #75 / `BBC_COLDSTART_DELETE_INCIDENT.md`). The Stage-1
  sealed gate is unaffected.
  *Installed*Whitelist* exclusions are intentionally preserved.]
- `DualAppActivity#getAvailableApps` / `#updateDualAppList` cannot be deoptimized on One UI 9
  (methods renamed/inlined); the BBC callee hook does not depend on them — callback evidence
  confirms the BBC path is intercepted. `*hitelist*` sweep stays diagnose-only on all known ROMs.
- Agent side also patches both `WhiteListApps#DUAL_APP_WHITELIST_PACKAGES` and
  `#..._FOR_CHINA` after `<clinit>` (static-final fields stay observe-only, never force-written);
  non-scope processes get `detach()`.
- **JVM tests prove pure decision/selection logic only** (not on-device class presence,
  signatures/return types, hook installation, callback interception, or that eligible overloads
  are hit on Samsung DAAgent/LSPosed). The BBC effect seam was device-verified on the 2026-11-17 (pre-unification) and 2026-11-18 builds only; it is experimental / not device-verified on this version (beta.3);
  the Stage-1 gate stays sealed: a code-level gate (`PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE`, default `false`) keeps
  `effective(...)` fail-closed (returns empty when closed) — this is a code gate, not device verification, and Stage 1 defaults remain unchanged;
  the fail-closed gate seam remains unit-tested.
- **Install / upgrade warning (v0.1.0-beta.3) — read this entire warning before following the Release link:**
  **Check which id you currently have**: if the old id `com.galaxydual.plus`, this is a clean install — no migration of old data, no coexistence with the new id, back up first and verify the backup is restorable; if already `io.github.samson910022.galaxydualplus` (including beta.2 → beta.3), this is a same-id, same-signer in-place upgrade — **do not uninstall**; Android ordinarily keeps that management App's data in place, but this is **not** a promise that Samsung clones or their data remain intact.
  Samsung may automatically delete clones and permanently erase their data; ordinary existing dual apps **do not need to be manually removed** for this package-id migration — this is **not** a claim that they are unaffected, and this App provides **no deletion guard**.
  Release applicationId `io.github.samson910022.galaxydualplus`, locally built debug APK `io.github.samson910022.galaxydualplus.debug` — a different package, and its applicationId does not match the `module.prop` module id `io.github.samson910022.galaxydualplus` (known limitation, not guaranteed to load correctly in LSPosed); no migration from the old id `com.galaxydual.plus` (preferences / policy draft / risk ACK are not carried over), no built-in import/export. Back up first and verify the backup is restorable. Do **not** enable the legacy management App / LSPosed module together with the new one (ordinary dual apps need not be manually removed for this migration, and data is not merged). Disabling, uninstalling, opting out or rebooting are **not** a deletion guard; the beta.3 hook may act before the first in-app warning. BBC available-list injection is experimental / not device-verified on this version; as of v0.1.0-beta.3 (`fc579de3`) there is no pre-activation opt-in (after PR #90 merges, `docs/HOOK.md` §1.4 / the ADR are authoritative).
  **The published Release page still has the old identity / A/B/C wording; until the maintainer syncs it, rely on this warning and the repo's `docs/RELEASE_NOTES_beta3.md`.** After the sync, see [v0.1.0-beta.3 release notes](https://github.com/samson910022/Dual-your-galaxy/releases/tag/v0.1.0-beta.3).
  Install the same signed `app-release.apk` in both owner and dual (e.g. user 95) users — `adb install --user 95 <path>/app-release.apk` (or `adb install -r --user 95 <path>/app-release.apk` to update an already-installed same-id copy); the `--user 95` copy is a separate second copy of the management App's own data for LSPosed per-user scope only and cannot write policy (owner user 0 only, see `docs/USAGE.md`). A locally built debug APK is an optional developer-only path (different package, module-id mismatch, no guaranteed LSPosed load). Enable the module (`Dual your Galaxy`, LSPosed module; `module.prop` id `io.github.samson910022.galaxydualplus`) with `system` + daagent scopes in LSPosed, then reboot. Never touches Knox.
- The marker proves hook installation only, not policy effect (empty=>observe-only on the callback
  return object, not a guarantee of device safety — cold-start deletion see `BBC_COLDSTART_DELETE_INCIDENT.md`;
  static-final holder fields may remain `ObserveOnly`, reflection failures `Skipped`; see
  `DualHook`/`WhitelistPatch`).
