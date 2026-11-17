<!-- MIT - Original spec by Galaxy-Dual-app-Plus contributors. No GPL content. -->
# Spec：BBC/DAAgent 層 Dual Messenger 白名單擴充（Tab S7 / One UI 5）

狀態：**Draft v2（待人審）**　|　日期：2026-11-17（v2：依 SPEC_REVIEW 28 項修正）
裝置基準：SM-T870（Android 13, One UI 5, DAAgent 3.3.00.0, Vector v2.2 / API 102）。裝置序號不寫入 repo。
非目標 ROM：S24／One UI 9（本 spec 以 Tab S7 為證據基準；其他 ROM 只做候選級保留）
授權：MIT original（借鑑思路見 `docs/CREDITS.md`，不複製任何第三方代碼；OneUIX=AGPL 禁止複製，DualAppMod=Apache-2.0 僅思路）

---

## 0. 證據記錄（本 spec 的所有事實基礎）

### 0.1 BBC 庫（com.samsung.bbc）來源與完整性

- 路徑：`/system/framework/com.samsung.bbc.jar`（framework 分區，唯讀 pull）
- 大小：70,438 bytes｜sha256：`55c99ea45c3386c81a3c0141accb40a8724dff0a10cdaa7018aa167af37a0189`
- 內容：`classes.dex`（70,152 B, DEX 039, 65 classes, 979 strings）＋`META-INF/MANIFEST.MF`
- 註冊：`cmd package list libraries` → `library:com.samsung.bbc`
- DAAgent 宣告：`<uses-library android:name="com.samsung.bbc"/>`（line=195, required 未設＝true）；`knoxsdk` required=true
- 註記： BBC 庫「存在+uses-library 宣告」為靜態事實；**BBC 類別在 daagent 進程 classloader 可解析 = 未實機驗證（見 §6 spike，為 BBC-injector 硬前置）**

### 0.2 呼叫鏈（disassembly 偏移量照抄，詳 ROM_ANALYSIS_TABS7.md / RUNTIME_FINDINGS_TABS7.md）

```
onResume                             16ddf0    [DualAppActivity]
 └ updateDualAppList()               16e08c    [DualAppActivity]
    └ getAvailableApps()             16d394    [DualAppActivity, PRIVATE]
       └ PmWrapper.getPossibleDualAppPackages(Context)Ljava/util/ArrayList;   @BBC 006310, PUBLIC STATIC, 74 insns
          ├ DaWrapper.getAllWhitelistedPackages()()[Ljava/lang/String;       @BBC 005c0c, PUBLIC STATIC, 5 insns
          │   └ SemDualAppManager.getAllWhitelistedPackages()                 @fw 2161c0, PUBLIC STATIC
          │      ├ ServiceManager.getService("dual_app") → 有 → AIDL transact 3
          │      │   → DualAppManagerService.getAllWhitelistedPackages()       [我們已 hook 的錨點]
          │      ├ ServiceManager.getService("dual_app") → 無，或 RemoteException，或 **AIDL 回傳 null**
          │      │   → framework 常數 DUAL_APP_WHITELIST_PACKAGES[_FOR_CHINA]（19 條，clinit base64 解碼明文，無 AIDL）
          │      └ mIsChinaModel → DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA
          └ getInstalledApplications(...)   [GET_APPLICATION_FLAG = 0（無 sput 該欄位 ⇒ 靜態 int 預設 0）]
             → 迴圈 installed∩whitelist ∧ enabled → ArrayList<String>
```

- 已確認（ROM_ANALYSIS §2.2/§5.2）：`PopupActivity.accept()` @`16f7dc` 無白名單 predicate（101 path 無 whitelist 檢查）；`DualAppManager.installWhitelistPackageIntoDualAppProfile` @`1676dc` 無 whitelist gate——依次經 `IPackageManager.installExistingPackageAsUser` 安裝後以 `addInstalledWhitelistedPkgInSystemServer` 推送給 system_server。**啟用路徑已被確認無白名單阻斷**（僅「清單不可見」擋住非官方 app）。
- `getDualApps()`（「Dual Messenger 應用程式」區）只走本地 `daApps` DB（`appType&2`），與清單擴充無關。
- `DAUtility.getWhiteListPkgs(Z)` 唯一 dex 呼叫者 `SaWrapper.sendStatusLogForAllDualApps`——純遙測；BBC hook 的擴充清單亦會流入此遙測（無安裝/啟用語意，良性，記錄於案）。

### 0.3 A/B 驗證目標

- 主目標：**`com.shopee.tw`**（user 0：已安裝且 enabled；user 95：未安裝——正好是待啟用狀態）；`pm path` 成立（base.apk＋split apks）。備案：`org.mozilla.firefox`。`com.openai.chatgpt` **不存在**於此裝置。
- 驗收依賴：spec §6 spike 必過；gate=true 只能在 device-verified A/B 後、且明確經使用者授權翻轉

---

## 1. 目標

讓「設定 → 進階功能 → Dual Messenger」畫面（實為 `com.samsung.android.da.daagent.activity.DualAppActivity`，
DAAgent 進程）的「**可用的應用程式**」清單出現**原生白名單不含**的第三方應用；使用者可直接開關啟用，
啟用後以 Telegram 型態運行雙實例——沿用 DAAgent 原生啟用路徑，不改寫安裝/移除路徑、不改 Launcher/PM、
不碰 Knox / Secure Folder（user 150）。

## 2. Capability Map（spec 附帶，模組邊界與實作序）

| 模組 id | 職責 | 依賴 |
|---|---|---|
| `policy-remote` | RemotePreferences 政策接線（App 端寫、hook 端讀）＋靜態種子 | 無 |
| `bbc-injector` | BBC 層清單擴充 hook（`bbc-injector` 為本 spec 主交付） | `policy-remote`（政策解析）、spike 前置 |
| `gate-verify` | §1.4 hard gate 驗收工具/決定清單（runbook） | `bbc-injector` |
| `ui-surface` | 更多 Dual Messenger UI 面擴充（**使用者指定範圍後另 spec**） | `bbc-injector` |

Build order：`policy-remote` → spike(v-d) → `bbc-injector` → `gate-verify` → `ui-surface`。`bbc-injector`
在 policy 為空時仍可獨立安裝（observe-only）；兩者可並行驗證，但 BBC-injector 的**啟用**需政策非空且 gate 翻轉，非預設。

## 3. 掛載點決策（雙錨點，使用者已選）

### PRIMARY（必掛）：BBC `com.samsung.android.da.daagent.fwwrapper.PmWrapper.getPossibleDualAppPackages(Context)Ljava/util/ArrayList;`

- 回傳元素：packageName 字串 ArrayList（清單擴充點唯一產生處）
- Hook 策略（fail-closed、extend-only）：
  - 合併純函數 `mergeAvailableList(original, policy, installed, enabled)`：union-only；保留 Samsung 原順序；政策包名**迭加至末端**；
    重複/非法（不通過 `PolicySource.isValidPackage`/`PKG` regex）/未安裝/未啟用者跳過；**政策不刪減、不排序、不重排**；
    零新增回原始列表本物件；不丟例外
  - **caller deopt**：`getAvailableApps()`（第一順位）＋`updateDualAppList()`（第二順位）best-effort deoptimize
    （HookInstaller.installWithCallers 既有路徑；kallers 透過反射解析、`null` drop、失敗回落 callee-only，行為不變）；
    callee 本身 deopt 保留
  - **BBC-hook 不 blocker 原行為**：任何一步失敗 → 回傳 original（identity）；`ExceptionMode.PROTECTIVE`
- BBC class 不可用（loadClass 失敗／依賴解析失敗）→ **不安裝任何 hook**，記 log，continue；無行為變更（spike 見 §6）

### SECONDARY（候選；PRIMARY 穩定後再定案，預設不掛）：BBC `fwwrapper.DaWrapper.getAllWhitelistedPackages()[Ljava/lang/String;`

- 位置：BBC jar `005c0c`，PUBLIC STATIC，5 insns（sv ae→ SemDualAppManager static call）
- 語意：上游白名單字串來源；若掛：回傳陣列尾端追加政策字串（使用同一 extend merge 概念）；token 類別／順序與 PRIMARY 一致
- 風險較大（inline 概率更高、靜態介面擴散）；**本 spec 審定時需使用者明示選掛**，否則 PRIMARY only

### 明確不掛（禁止）

- `DualAppActivity` / `PopupActivity` / `DualAppManager` / `DualAppDBHelper` 任何方法
- framework `SemDualAppManager.getAllWhitelistedPackages()`（static facade，影響面過大、fallback 語意複雜）
- system_server `DualAppManagerService.*`（不動现有錨點，形式不新增不移除）
- `DAUtility.getWhiteListPkgs` 維持遙測（為遙測保留，並在 BBC 穩定後由獨立 spec 決定清理）
- 一切 mutation/安裝/移除/更新方法

## 4. Policy 政策來源（使用者已選：靜態種子＋RemotePreferences 一次做好）

### 4.1 形狀（不變）

- union-only `Set<String>`；`PolicySource.isValidPackage`/`PKG` 驗證；空集合＝observe-only；gate=false ⇒ 回傳空（fail-closed）
- `STATIC_SEED` 預設空；種子由審作者於 device 驗證階段提供（建議 `com.shopee.tw`，Open Question 3）

### 4.2 App 端（`policy-remote`）：寫入路徑，使用者＝user-0 的本 App 自身 UID

- 依賴：`implementation("io.github.libxposed:service:102.0.0")`（遞移 `interface:102.0.0`，**不另聲明**；annotation 為 hook 端 compileOnly）
- Manifest：新增 `<provider android:name="io.github.libxposed.service.XposedProvider" android:authorities="${applicationId}.XposedService" android:exported="true"/>`
  ——當 service AAR 自動合併 provider 時（現行 AAR 即自動合併），規格反而**不**另宣告；若手動宣告則加 `tools:ignore="ExportedContentProvider"`（實作階段審核定案）
- 不需 `<uses-permission>`；存取控制由 daemon 端（`callingUid % 100000 == moduleAppId`）
- 新增 `GalaxyDualApplication`（`<application android:name=".GalaxyDualApplication">`）；`Application.onCreate` 呼叫 `XposedServiceHelper.registerListener(...)`；
  政策寫入 UI 由獨立 `PolicyViewModel`/設定畫面承載（含 SaWrapper式驗證顯示、不寫入非法包名）
- 寫入：`getRemotePreferences("galaxydual_policy")` → `edit().putStringSet("packages", sanitized)` ——group=`galaxydual_policy`，key=`packages`、**StringSet**、
  **user 0 origin**（user-95 掛載的 hook 讀的是 user-95 自己 process config，writeFrom 為 user 0 時 user-95 端為 empty→observe-only）
- 防禦性複製：StringSet 經 binder 為 Serializable → `filterIsInstance<String>()`＋`PolicySource.sanitize`，**不信任 unchecked casts**
- 失敗語意：`commit()` 為樂觀更新（App 本地先更新，daemon 不回應確認）；false＝寫入失敗；App 側呈現錯誤；hook 端無感
- `pm clear` 不清除遙控器 prefs（daemon 側 `/data/adb/lspd/config/modules_config.db`）；卸載會 wipe（daemon FULLY_REMOVED 事件）

### 4.3 Hook 端：讀取路徑

- `DualHook.onSystemServerStarting`/`onPackageLoaded` 階段注入完成前，建立 **RemotePolicyState**（新內部 object，framework-free 決策留在 PolicySource）：
  - `group = "galaxydual_policy"`，listener（`OnSharedPreferenceChangeListener`，**全程 try/catch，不得拋例外**）
  - `@Volatile` cache（`Set<String>`，含 defensive copy；listener 中驗形條件過擇）
  - hook 模組端 `remotePolicySnapshot()` → `RemotePolicyState.get()`；`interceptWhitelistCall` 維持 IPC-free、`effective(...)` 不改簽名、gate 不改
- 若 listener 拋例外：Vector v2.2 `runCatching{}.onFailure{ remove }` 會讓訂閱**被靜默移除**（整個 boot 其餘時間不再推送）→ 24/7 無聲失效 →
  listener 必須 total；單筆更新失敗時 cache 維持舊值或清空二選一（規格：清空並保序？—→ **不更新，保留前值**，記 log）
- **cold-boot 保障**：`getRemotePreferences` 在 onSystemServerStarting/onPackageLoaded 內同步讀取完成；第一個 hooked 呼叫之前已正確初始化（marker 可用輔助）
- **Remote prefs 不代替 marker**：marker 可降級為輔助診斷；不再作為唯一 enablement 訊號
- `NON_EMPTY_POLICY_EFFECTIVE_GATE = false` **維持**（接線完成≠啟用；gate 僅依 §1.4 device 證據翻轉，且須使用者明確授權）
  → **2026-11-17 狀態：已翻轉為 `true`**（commit `45a40e67`）。兩台裝置實機 A/B 證據滿足 §1.4 五項硬閘門
   （見 Addendum A）；使用者於 2026-11-17 明確授權；S23 Ultra 端對端雙開 ChatGPT 成功。

## 5. 更多 UI 面（使用者已選：清單＋更多 UI 面）

候選表（不預寫碼；範圍由使用者指定後另 spec 或增補本 spec）：

| 面 | 位置 | 本 spec |
|---|---|---|
| 「可用的應用程式」清單 | `getAvailableApps()`/`updateDualAppList()` | ✅（PRIMARY 面） |
| 「Dual Messenger 應用程式」區（已啟用） | `getDualApps()`→`daApps` DB | ❌（另 spec 需要 DB 變更） |
| 啟用確認對話框 | `PopupActivity` | ❌（無白名單檢查，不動） |
| 分身捷徑 | `CreateShortcutActivity` | ⚠️ 待使用者指定 |
| `DANotificationActivity`/widget/reminder 等 | 未鑑識 | ⚠️ 未知；不進本 spec |
| 系統通知/移除 | `DARemoveService` | ❌（禁止） |

## 6. spike 硬前置（`bbc-injector` 啟用條件 = spike 日誌產物存在）

首次以**空政策 build** flash → 開機 log 搜尋：

```
BBC-SPIKE ok com.samsung.android.da.daagent.fwwrapper.PmWrapper#getPossibleDualAppPackages
BBC-SPIKE FAIL: BBC library not resolvable on this ROM
```

- 未解析到 → **BBC-injector 不得安裝任何 BBC hook**；onPackageLoaded 記 `BBC-SPIKE FAIL`；module 保持 observe-only（無行為變更）
- spike 僅觀察、不改變任何決策；pass → 才進 `bbc-injector` 安裝（本 spec 其餘部分維持有效）

## 7. Commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :hook:testDebugUnitTest assembleDebug --console=plain
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk   # serial 不入 repo
adb -s <serial> reboot
adb -s <serial> logcat -d | grep -E "GalaxyDualHook|BBC-HOOK|BBC-SPIKE"
adb -s <serial> shell su -c 'stat -c %y /data/system/galaxydual_hook.active' 2>&1
# gate=true A/B 流程：見 docs/../VERIFY_TABS7_RUNBOOK.md（清單收集、Shopee 步驟、負對照、回滾）
```

## 8. Project Structure

```
hook/src/main/java/com/galaxydual/hook/BbcInjector.kt          # 新增（PRIMARY/SECONDARY 候選常數 + extend-only 合併 + BBC-HOOK entry log）
hook/src/main/java/com/galaxydual/hook/RemotePolicyState.kt    # 新增（internal object；@Volatile cache；listener 不拋）
hook/src/main/java/com/galaxydual/hook/DualHook.kt             # 編輯（BBC-SPIKE + BBC 注入註冊 + remotePolicySnapshot seam）
hook/src/main/java/com/galaxydual/hook/PolicySource.kt         # 編輯（接收 live remote seam；framework-free 決策不動）
app/src/main/java/com/galaxydual/plus/GalaxyDualApplication.kt # 新增（Application.onCreate + XposedServiceHelper 註冊）
app/src/main/.../<Policy UI>                                     # 新增（政策編輯/顯示；驗證輸入包名）
app/build.gradle.kts                                             # 編輯（service:102.0.0 implementation）
app/src/main/AndroidManifest.xml                                 # 編輯（<application android:name=...>）
hook/src/test/.../BbcInjectorTest.kt                            # 新增（合併純函數 + 候選常數）
hook/src/test/.../RemotePolicyStateTest.kt                     # 新增（listener 永不拋、stale semantics、clear/put 行為）
docs/TAB7_BBC_INJECTION_PLAN.md                                  # 本檔
/mnt/...                                                           # 分析產物——不入 repo
```

## 9. Code Style

```kotlin
// Stage 3 (Tab S7 P0): extend-only list merge for the Dual Messenger
// "available" list. Union-only: never removes, never reorders.
// Fail-closed: any mismatch -> return original unchanged.
internal fun mergeAvailableList(
    original: List<String>,
    policy: Set<String>,
    installed: (String) -> Boolean,
    enabled: (String) -> Boolean,
): List<String> {
    var out = LinkedHashSet<String>(original)   // 零新增時回原物件
    ...
}
```

註解必含 ROM/證據標籤；命名遵循現有檔案；繁中或英文皆可。

## 10. Testing Strategy

- **JVM（純邏輯，全綠門檻）**：
  - `mergeAvailableList`：union-only、順序保留、append-at-end、去重、非法過濾、identity fast path、128 項政策上限
  - `RemotePolicyState`：listener 不拋（含 daemon dying/clear/putTranslate 並發）、stale cache 語意
  - BBC 候選常數合約（仿 DaagentQueryHooks：BBC 類別/方法名不被 mutation 標籤誤傷）
  - gate=false ⇒ `BBC-HOOK installed=1` 但行為零變化；gate 翻轉後才有政策效果
- **BBC-HOOK entry log（rate-limited，每進程每方法一行）**：空政策與 BBC hook 皆記 `BBC-HOOK callback <sig> (pid=…)` ——負對照可證「沒有動」
- **裝置（Tab S7 唯一證據基準）** §1.4：
  1. spike 通過日誌（§6）
  2. hook 安裝 log（deopt+hooked + `installed=1`）
  3. gate=false：清單逐列相同（螢幕截圖）＋`BBC-HOOK` 僅 entry log，無 merge 行為
  4. gate=true（device-verified A/B，target=`com.shopee.tw`）：
     - "可用的應用程式" 出現 Shopee；Popup；啟用；「Dual Messenger 應用程式」出現 Shopee
     - `pm list packages --user 95 | grep shopee`（應顯示）；`pm path --user 95 com.shopee.tw`；分身啟動（使用者實測）＋重新開機後仍在
  5. RemotePreferences live-swap（不重開機）：App 寫入→cache 更新→清單差異重現；/cb=off 時 fail-closed；
     daemon death 後 listener 訂閱應轉介為「保留舊 cache」（BBC-HOOK 入口 log 可證）
  6. 回歸：Telegram/Facebook 行為不變；連續 2 次重開機；`logcat -b crash | grep -i galaxydual` 空；
        Secure Folder（user 150）零接觸（含 `pm path --user 150` 不執行）；Shopee row 出現為唯一行為差異
- **不做**：不以 JVM 取代 on-device 驗證；不主張未測 ROM 可用

## 11. Boundaries

- **Always**：union-only、fail-closed、PROTECTIVE、空政策預設、JVM 綠燈才裝機、marker 僅輔助
- **Ask first**：翻 `NON_EMPTY_POLICY_EFFECTIVE_GATE`；SECONDARY 錨點；擴大 scope；卸載 APK/副本；commit；任何裝置寫入類動作；新增/移除 mutation 面
- **Never**：碰 Knox/Secure Folder（user 150）、複製 AGPL/GPL 代碼、改 mutation 路徑、無 device 證據強寫 static final、撤銷 rollback、
  把裝置序號/個人資料/本 spec 外內容寫進 repo、未經授權 force-stop 服務、用 `pm path --user 150` 驗證

## 12. Success Criteria

1. `:hook:testDebugUnitTest` 全綠；`assembleDebug` 成功
2. spike 通過；`BBC-HOOK installed=1`（或 fail-closed 記 `BBC-HOOK skip` 且行為不變）
3. gate=false（預設）：**零行為差異**
4. gate=true（device-verified A/B, `com.shopee.tw`）：清單出現、可啟用、分身安裝/啟動成功、重啟後仍在
5. RemotePreferences 寫入在**不重開機**情況下可見（volatile cache＋listener，全 try/catch 無拋例外）
6. §1.4 hard gate 5 項各有 Tab S7 證據；任何一項未達 ⇒ gate=false、 BBC-injector observe-only（不得有 UNVERIFIED 直接上線）
7. 連續 2 次重開機正常；`logcat -b crash | grep -i galaxydual` 空
8. Docs 同步：`docs/HOOK.md`、`docs/CREDITS.md`（OneUIX/DualAppMod 思路引用）、`README.md`、`docs/USAGE.md` 一致更新，且**不寫**序號/個人在 repo

## 13. 實作前必答（Open Questions）

1. **「更多 UI 面」具體指哪些？**（CreateShortcutActivity 納入與否？其他面請指名）
2. **SECONDARY 錨點（BBC `DaWrapper.getAllWhitelistedPackages`）掛不掛？** 預設 PRIMARY only；審定時明示
3. **首個驗證種子包名？** 建議 `com.shopee.tw`（已安裝 enabled；user 95 無分身，正好 A/B 用得上）；種子預設空，裝機 A/B 時暫放
4. **BBC-hook 入口 log 格式／計數上限**（rate-limit 1 行/進程足夠？或 N 行/進程？）——建議 1 行
5. **SECONDARY 遙測處置**（`DAUtility.getWhiteListPkgs` 已裝、僅遙測）：保留觀察（建議）還是本輪直接清理？
6. **user-95 側**：user-0 寫入的 remote prefs 在 user-95 進程不可見 → user-95 預設 observe-only；是否要为 user-95 提供同一政策寫入入口（App 需 user-95 安裝與 multi-user 寫入）？目前不做
7. **BBC service 依賴**：依賴 service AAR 自動合併 provider 而不特別宣告，還是明示 manifest provider＋`tools:ignore="ExportedContentProvider"`？（審核定案）

## 14. 實作 commit 序列（建議，各自審核）

1. `feat(hook): add Tab S7 DAAgent class candidate provenance (utils.DAUtility)`（即現有 uncommitted 修正；單獨 commit）
2. `docs: add BBC/DAAgent injection spec`（本檔）
3. `feat(hook): BBC-SPIKE class resolution gate + BBC-HOOK entry log (no behavior change)`
4. `feat(policy): wire RemotePreferences policy source (remote snapshot seam)`
5. `feat(hook): BBC-injector PRIMARY anchor (extend-only merge)` 
6. `feat(app): policy editor UI + GalaxyDualApplication registration`
7. `docs: sync HOOK/USAGE/README/ACCEPTANCE with Tab S7 evidence`

---

**變更記錄**
- v2（2026-11-17）：修正 SPEC_REVIEW（2 BLK/10 Major/10 Minor/6 Nit）→ BBC jar provenance 記錄＋spike 硬前置；
  §1.4 收緊（UNVERIFIED ⇒ gate=false，BBC observe-only；BBC-HOOK entry log 必要；marker 降級輔助）；
  RemotePreferences 契約定稿（group `galaxydual_policy`/key `packages`/StringSet/user-0 origin/防禦性複製）；
  A/B 目標改 `com.shopee.tw`（chatgpt 未安裝）；CreateShortcutActivity 維持另 spec；
  GET_APPLICATION_FLAG=0 確認；序號已移出 repo；manifest-provider AAR 自動合併註解。

## Addendum A（2026-11-17，裝置雙機實機驗證與 gate 翻轉紀錄）

**裝置 1：Galaxy Tab S7（SM-T870，Android 13／One UI 5，DAAgent 3.3.00.0，Vector v2.2 API 102）**
- Gate=false 基線：`BBC-HOOK installed=1 (daagent, pid=…)`、`BBC-HOOK callback …`＋`RemotePreferences initial load: 0 package(s)`；Samsung 可用清單不受影響。
- Gate=true：勾選 Firefox＋Gemini → `RemotePreferences updated`／callback nonempty → `BBC-HOOK merge added 0 packages (policy=3: com.shopee.tw installed=true, enabled=false, org.mozilla.firefox installed=true, enabled=true, com.google.android.apps.bard installed=true, enabled=true)`。**merge added 0 是正確結論**：Firefox／Gemini 已在 Samsung 執行期 dual_app 白名單（AIDL 傳回、裝置持久化，非框架常數）；注入位元一致、零回歸。toggle 在設定頁可操作，雙開實例（`org.mozilla.firefox`）已安裝並以 `u95_a306` 執行。
- BBC 演算法（`PmWrapper.getPossibleDualAppPackages`）在 S7 BBC：installed ∩ enabled ∩ `SemDualAppManager.getAllWhitelistedPackages()`。

**裝置 2：Galaxy S23 Ultra（SM-S9180，Android 17／One UI 9 `90000`，DAAgent 3.7.00.0，`ro.build.version.sdk=37`，KernelSU Next + Zygisk-LSPosed，BBC jar 33,946 bytes，classes.dex 33,644 bytes）**
- BBC 庫結構與 S7 同：`Lcom/samsung/android/da/daagent/fwwrapper/PmWrapper`、`getPossibleDualAppPackages(Context)Ljava/util/ArrayList;` PUBLIC STATIC，演算法位元一致（`installed ∩ enabled ∩ SemDualAppManager`）。DUAL_APP_WHITELIST_PACKAGES/\_FOR_CHINA 仍在 DAAgent `provider.WhiteListApps`（`<clinit>` hook 維持 static final observe-only）。
- Caller deopt：`DualAppActivity#getAvailableApps`／`#updateDualAppList` 在 One UI 9 **找不到目標**（已改名/內聯），hook 自動 `skipped`（不影響 BBC callback——hook 裝在 BBC callee 本體）。
- Policy editor（Phase 4 picker 版）：搜尋＋勾選 `com.openai.chatgpt` →
  `RemotePreferences updated: 1 package(s)`（live push、無需重啟）→
  **20:54 首次開頁 `BBC-HOOK callback getPossibleDualAppPackages (pid=21472)`,
  23:01 政策生效後 `BBC-HOOK callback (nonempty, pid=21472)` →
  `BBC-HOOK merged +1 packages (total 7, pid=21472)`**。
- Samsung 設定頁（Dual Messenger）在「應用程式」區出現 **ChatGPT** 與 Telegram 並列，可切換；
  切換後 `pm path --user 95 com.openai.chatgpt` 顯示 dual APK 安裝於 user 95；
  `ps` 顯示 `u95_a431 com.openai.chatgpt` 進程執行中（與 u0_a431 並存，無互相污染）。
- 頁面截圖與全 log 見本目錄外 `/mnt/seagate500g/opencode_tmp/s23ultra/`（裝置證據檔）。
- 本 addendum 取代 §12 第 4 條的「S23 Ultra」驗收目標——終驗目標變更（已由使用者指定 ChatGPT）。

**閘門翻轉授權與 commit**：2026-11-17 使用者明確授權 → commit `45a40e67`
（`feat(hook): ship BBC whitelist injection effect by default (gate=true)`，含 3 個 test-lock 改寫為
 預設開＋顯式 fail-closed seam 保留）。

