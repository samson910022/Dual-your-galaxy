<!-- MIT License - Original docs by Galaxy-Dual-app-Plus contributors. No GPL content. -->
# 使用教學 (USAGE)

> 本專案僅經由 GitHub 發佈 sideload APK，不上架 Play 商店。
> PR-7 P2 (#26) 權限審計已完成：`REQUEST_INSTALL_PACKAGES` 與 `MANAGE_EXTERNAL_STORAGE`
> 無呼叫鏈（clone 走 shell `pm install-existing`，不走 PackageInstaller 會話；無共享儲存
> 暫存）故已移除；僅保留功能必需的 `QUERY_ALL_PACKAGES`（個人來源 catalog 經
> PackageManager 列舉，見 `SourceInventory.loadSourceApps`）。不上 Play 不是保留過寬權限的充分理由。
> 全程不碰 Secure Folder（Knox 容器）。
> 本文件描述包含設計目標與未完成流程；凡 [待實作]／[待實機驗證] 皆不宣稱為可用功能，
> 驗證須依個別情境判讀，不能由正常成功案例推論所有通道與安全負向邊界。歷史 S24 寫入紀錄與本輪唯讀調查見下方更新。

> **2026-11-05 更新**：[S24 調查與追蹤計畫](UX_REVIEW_S24.md) 彙整既有 #6/#9 的正常 SHELL clone/remove、手動通道證據，以及新發現與 issues #21–#29。本文較舊 F4a 的「無實機／Factory 靜態選路」敘述屬歷史階段，不代表手動重選仍未實作；未完成項目仍不得宣稱可用。

## 0. 狀態標示（與 README 一致）

- [已支援] profile 基本防護（排除 user0、拒絕候選歧義；完整平台身分待 #21）、`pm` 命令封裝
 （`DualRepository.cloneVerified / removeVerified`＋二次確認）、lint／CI 閘門。
- [部分已驗證] App 內一鍵 clone 真通道＋手動 picker（F4a 已接線＋picker＋marker：`MainActivity` 四通道按鈕＋Hook marker 行＋`StateFlow`，`DualViewModel.selectChannel/clone/refresh`；真 gateway 為 `SuCmdGateway`（`su -c`）／Shizuku UserService `ShizukuCmdGateway`（`newProcess` 在 13.1.5 已移除），選不到回 `NoOp`＋唯讀 fail-closed，`channelReady` gating（`channelReadyFor` [已支援]：hook 訊號永不授權，NONE 永不就緒）。[S24 已驗證]僅限 ROOT 一鍵 `cloned:`、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、`pm uninstall --user 95` 回滾＋`su -c` 重裝；寫後主驗證以 listing 為準（`pm path` 僅輔助診斷，見 #22）；Shizuku 未授權／binder 死引導、App 內 Shizuku 首次選路、marker gateway-`cat` 顯示行、callback 可驗日誌、非空政策生效、當前 source 可追溯部署仍 [待實機驗證]；指定正常 SHELL clone/remove＋手動通道歷史見 [S24 調查](UX_REVIEW_S24.md) §5，指定範圍外的 binder round-trip、授權四情境、su 實機行為、端到端 pm、LSPosed 啟用偵測及當前 source 可追溯部署仍待驗）。
- [待實作] 非空 whitelist policy（預設為空，hook 為 observe-only）。
- [部分已驗證] LSPosed 載入（system hook 雙方法 deoptimize＋hooked [S24 已驗證]、daagent 新路徑 `using provider.WhiteListApps` 連續命中（僅類別選取） [S24 已驗證]、marker 主路徑 scope=system_server（早於本次開機約5.5h，不能宣稱本次開機有效） [S24 已驗證]，其餘 LSPosed 寫入、非空政策 [待實機驗證]）、Shizuku（已授權一鍵已驗；未授權路徑／首次選路／binder 死引導待驗；含重啟後重啟動）、
  雙開實機（`install-existing / path / uninstall --user` 端到端；其中
  `install-existing --user 95`＋`pm list packages --user 95` 二次確認
  [S24 已驗證]：裸 pm shell 通道兩次獨立成功）。

Hook 誠實聲明（`docs/HOOK.md` 第 1.1 節，本文引用不再重述為已生效）：
hook 政策集合預設為空，hooks 為 observe-only 佔位——會安裝、會記 log，
但不改變任何行為、不放寬任何包名；接上政策來源前，本文凡提及「hook 通道／白名單放寬」
皆指設計意圖，不是現有效果。

## 1. 提權優先順序（`core` 純函數 [已支援]＋真通道已接線（ROOT／SHIZUKU 已授權一鍵已抽驗 [S24 已驗證]，其餘 [待實機驗證]）

路由純函數（[已支援]）依序判斷；開機／開 App 探測框架（`probeChannels`／`recheckPrivilege`／`selectChannel`＋Shizuku 生命週期）已接真 gateway（ROOT→`SuCmdGateway` 經 `su -c`；SHELL→`ShizukuCmdGateway` 經 UserService，`newProcess` 在 13.1.5 已移除；hook 訊號為 file marker 存在性證據，僅顯示、永不進入路由，[待實機驗證]；`ChannelPreference` 手動選擇 AUTO 預設，`selectChannel` 動態換 gateway；選不到回 `NoOp` fail-closed；`channelReady` 為各通道探測通過才 `true`，`channelReadyFor` [已支援]），
以下順序為接線後預期行為，App 內 Shizuku 切換（未授權路徑／首次選路／marker gateway-`cat` 顯示 [待實機驗證]；已授權一鍵、ROOT 一鍵／su／uninstall 已驗，見 §0）：

1. **LSPosed 模式（NoOp 佔位）**：App 內 picker 選 LSPosed → 顯示 file marker 狀態（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，[待實機驗證]），執行通道為 NoOp 佔位、寫入一律拒絕；hook 為
   observe-only（見上文引用；App 內 ROOT 一鍵 [S24 已驗證]，marker gateway-`cat` 顯示未驗），不可描述為已能擴展包名。
2. **Shizuku 降級（SHELL）**：無 LSPosed 但 Shizuku binder 可達 → `Privilege.SHELL`
   - 走 `pm` 指令通道（`install-existing` / `uninstall --user` / `path --user`），F4a 的 `ShizukuCmdGateway` 經 UserService `GalaxyExecService` 已接線（`newProcess` 在 13.1.5 已移除故不用；Factory 在無 su 且 binder alive＋已授權時選擇，`ShizukuElevator` 探測通過才 `channelReady`）；
     binder round-trip、未授權路徑／首次選路／binder 死引導 [待實機驗證] DEVICE-UNVERIFIED。
3. **NONE（唯讀）**：選不到通道 → 不執行任何寫入，只顯示狀態（[已支援]：`NoOpGateway` 拒寫＋`channelReady` false fail-closed；不再恆 `false`——所選真通道探測通過即為 `true`）。

```kotlin
// Pure routing logic, no side effects (see core/Privilege.kt).
// App 端現狀（H4／#19）：hook 訊號永不進入路由（hasLsposed 恆 false：file
// marker 是存在性證據，不是 live hook 證明）；hasRoot/hasShizuku 由
// Su/ShizukuElevator 真通道探測填入。Hook 狀態走 UiState.hookActive／
// hookScopes（display-only）供 LSPosed picker 行顯示。
val privilege = routePrivilege(hasLsposed = false, hasShizuku = shizukuAlive, hasRoot = hasRoot)
val ready = channelReadyFor(gatewayPrivilege, hasRoot, shizukuAlive)
```

Clone／Remove／重新連線並更新／請求 Shizuku 授權按鈕＋四通道選擇按鈕與 `StateFlow` 收集＋Shizuku binder／授權生命週期已接；探測跑在與所選 gateway 同實例的真通道 elevator 上，`channelReady` 僅所選通道探測通過才 `true`（NoOp 回退則 `false` fail-closed）。
目前 `selectChannel()` 已可在 mutex 內重建 gateway、探測與刷新，`recheckPrivilege()` 也會在 AUTO 環境變化時重新選擇；不是只有啟動時的靜態 Factory。重新連線並更新按鈕（單一入口 `reconnectAndRefresh()`：probe 含 Auto 降級＋refresh，暫態失敗後點此即重探恢復）。Auto probe 降級已實作（su 存在但探測拒絕＋Shizuku 通過→SHIZUKU＋降級說明，雙通道皆失敗→NOOP 唯讀，見 #25）；LSPosed 仍為拒寫的 NoOp 佔位。顯示更新本身 [待實機驗證]。

- 併發邊界（#24；術語：busy 即 `UiState.loading`，merge 即 coalesce）：`重新連線並更新`（`reconnectAndRefresh()`：probe 含 Auto 降級＋refresh，單一入口）快速連點合併為一次 trailing 重跑（coalesce，in-flight 之後最多一次待跑，不堆積 mutex 等待者；純 `refresh()` 仍為探測-free 的舊路徑，僅供 coalesced trailing 內部使用）；`clone`／`request-remove`／`confirm` 按操作 key 去重，同 key 第二次報 `already in progress` 不排隊，三命名空間全稱 `clone:<pkg>`／`request-remove:<pkg>`／`remove:<pkg>@<id>#<serial>#<ts>`（世代 key，serial 綁定：同包不同世代不互擋，同世代重複才去重；clone 與 request／confirm 互不干擾）；寫回覆 `timedOut‖transportUnknown‖isPostSpawnTransportText‖isServiceTimeoutText` 一律 verify-first（單次有界唯讀 `verifyListProbe` 定奪，不重發寫入；同向成功才認 Success，反向觀測一律 Unknown；before／after pin 漂移→`VerifyUnknown`（identity-changed），不計成功）；執行期間 busy（`UiState.loading=true`，7 鈕＋dialog 按鈕禁用，cancel／pending 保留語義見 ARCH）；所有 repo 呼叫經 `Dispatchers.IO`（`onIo`），settle wait 預設可取消 `delay()`（經 `delayFn`），cancel 在等待間傳播。
- 錯誤優先（error-first，`combineWithDegradeNote`，見 `DualViewModel.refreshLocked`）：refresh 結果與 Auto 降級說明疊加時，錯誤保持優先——`CHANNEL`／`FAILED`／`NO_DUAL`／`VERIFY`／`INVALID` 保持原 kind 不降級為 `INFO`，以降級原因附加（`；` join，恢復入口 `重新連線並更新` 隨附後）；`NONE` 顯示降級說明 alone（`INFO`），`INFO` 中性結果附加說明（`INFO`）。狀態列因此同時攜帶刷新失敗與雙通道恢復指引。
- 文案術語（`notReadyMsg`／`isWritable`）：`禁用` ＝ 未就緒 fail-closed（通用 NoOp 回退 `通道未就緒…寫入已禁用`＋明選失敗 `…寫入已禁用（明選不跨通道）`，寫入一律拒絕）；`拒絕` ＝ LSPosed 政策佔位（`LSPosed 通道為佔位…寫入已拒絕`，whitelist 政策分支待實作）。兩詞不混用；測試以 `contains` 鎖定三條分支關鍵字。
- 探測通過 ≠ PM可讀（P25 Item 4＋Phase 3 fail-closed）：`anyProbePassed`/`writeReady` 僅為探測門（`id -u == 0` 即可通過），**NOT** PM可讀證明；真可讀 ＝ `pmReadable == true`（`pm list users` 經 refresh 證明）＋`writeReady`。`pmReadable == false`（PM不可讀）時寫入被拒（fail-closed，`探測通過但 PM 不可讀…寫入已禁用`，`CHANNEL`），不嘗試寫入；`null`（未知，未刷新或已過期）仍允許嘗試，由 repo 層重驗 fail-closed。

## 2. Shizuku 配對教學（無線調試，Android 11+，流程 [待實機驗證]）

預期適用無 ROOT 裝置（三星 One UI 4.0+ / Android 12+ 為目標機型，未經實機驗證）。

1. 手機與電腦連上**同一個 Wi-Fi**。
2. 手機開啟**開發者選項**：
   - 設定 → 關於手機 → 軟體資訊 → 連點「版本號碼」7 次。
3. 設定 → 開發者選項 → 開啟**無線調試（Wireless debugging）**。
4. 安裝並開啟 **Shizuku**（GitHub 版），點選「經由無線調試啟動」→「配對」。
   - Shizuku 會顯示 6 位數配對碼；在系統跳出的「無線調試配對」對話框輸入配對碼。
   - 配對成功後回到 Shizuku 點「啟動」，看到版本號即表示 binder 已就緒。
5. 在 Shizuku 的「授權應用」中找到 **Galaxy Dual Plus** 並允許。
6. 回到本 App 點 `重新連線並更新`；F4a 接線後，已授權 binder 下 Factory 選 `ShizukuCmdGateway` 且 `ShizukuElevator` 探測通過時，標題列預期從 `(NONE/NOOP)` 變為 `(SHELL/SHIZUKU)`（標題列顯示實際執行身份＋已解析通道 `execIdentity/resolvedGateway`；選不到／探測未過則維持唯讀 fail-closed；此切換 [待實機驗證] DEVICE-UNVERIFIED，不可視為已可用）。

> 每次重開機後需重複步驟 4（Shizuku 需重新啟動）；配對碼只需配對過一次，
> 同一 Wi-Fi 下重啟一般直接按「啟動」即可。
>
> 已 root 裝置可用 Shizuku 自帶 so 直接啟動（無需配對流程，S24 已用此法）：
>
> ```bash
> adb shell /data/app/~~-jo681V4qGxj5nbJO4ijNA==/moe.shizuku.privileged.api-MyY9Gq9kawonTnGEcFOYdw==/lib/arm64/libshizuku.so
> ```
>
> 注意 `~~...` 中間路徑每次安裝隨機，實際以 `pm path moe.shizuku.privileged.api` 輸出為準。

舊裝置（Android 10 以下無無線調試）改用 USB：

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

## 3. 複製 / 移除 / 驗證 / 列表流程（需雙源驗證，`--user <dualId>` 僅為格式佔位）

以下 `<dualId>` 僅為命令格式佔位，不是推薦目標，更不是「任意非零即雙開」。
寫入前必須先完成雙源驗證：`cmd user list -v` 與 `dumpsys user` 兩個獨立通道
在同一非零 id 上同時給出 `profile.CLONE` 型別，且 parent/serial 門通過：
parent 須雙通道皆提供、相等、== 0（任一缺失 → Unknown；兩值不等 →
Refused「conflicting parent」；相等但非 0 → Refused「non-owner parent」政策）；
serial 雙邊皆有且不等 → Refused「conflicting serial」，單 fetch 任一缺失則容忍放行
（serial != id 屬正常）；跨 fetch 已知→未知亦拒（continuity lost，與實作一致）；
且系統 property（`getprop sys.dualapp.profile_id`）必須可讀且與該同一 id 一致（必要佐證，不可省略：缺失／空／格式未知 → Unknown 證據不足拒寫；指向不存在 id 或與 CLONE id 不一致 → Refused；不可讀版本按不支援版本明確拒寫，見 #21）；
任一通道缺失、未知、衝突或歧義（多 CLONE／型別打架／property 缺失→Unknown／property 打架→Refused）一律拒寫、
拒讀，不猜「第一個非 0 的 id」、不認 label、不固定 95/150、不比整組 flags。
App 內 `DualRepository`／雙 Elevator 探測即執行此契約（命令字串與 adapter 共用，fetch 經 `VerifiedIdentity.kt` 共用 fetch，[已支援] PR-1 階段2）：
`resolveDualUserIdVerified(userListVerbose, dumpsysUser, propertyId)` 僅在
`SamsungProfileAdapter.resolveDual() == Confirmed(HIGH)` 時回 id，否則 -1；
explicit id 即使存在於 `pm list users`，只要不是當下 Confirmed 目標一律
fail-closed：`clone`／`remove` 回 `refused:` `CmdResult(1)`、
`verifyState` 回 `Unknown`、`verify` Boolean 回 `false`（已 `@Deprecated`）、
`listClonedPackages` 回 `null`，不探測目標、不寫入（仍做 snapshot＋identity fetch 唯讀）。
同 id 重建（serial 變更）視為不同目標：批次 pin 與移除確認皆比對 `(id, serial)`，不一致零寫入。
`pm list users` 的名稱僅供顯示，永不進入授權判定；user 0 永遠不可作為寫入目標。
CLONE 僅認全 token 精確拼寫：`profile`＋單點 `.` 或 1–3 個列舉分隔符（空格／`_`／`-`／Tab 任意混搭，如 double space、`__`、` -` 皆計，4 個以上即無 token→OTHER）＋全字 `clone`（大小寫-insensitive）；`!`／`?`／`:`／`/` 等任意其他標點永不為分隔符，一律無 token→OTHER（fail closed）；`CLONE_extra`／`CLONE-EXTRA`／`CLONE.X`／`CLONEable` 等未知後綴一律非 CLONE；`profile.CLONE/evil` 類斜線尾詞一律 tainted→OTHER：`/` 僅在後緊接另一個合法 `profile.X` token（`profile`＋單點或 1–3 個列舉分隔符＋名稱，如 `profile.MANAGED`／`profile.CLONE`，含 FQ `android.os.usertype.profile.X`）時豁免，此時走雙 token 混合→UNKNOWN 仍拒寫；`/profile!evil`、`/profilegarbage`、裸 `/profile`、`/profileX` 等非 token 一律不豁免，仍 tainted→OTHER；括號內斜線如 `(a/b)` 即使無裸 slash 語義亦保守降級→OTHER（fail closed；實機 type 行無此形狀）。殘留驗證（唯一授權＝全 token 匹配＋slash-guard＋殘留驗證）：附註僅指空白／Tab 與恰一次的 `(default)`（大小寫-insensitive，前後可空白）；直接貼在 CLONE 後的任何其他字元（含 `!evil` `?evil` `:evil` `;evil` `,evil` `@#$%^&*+=|\`、全形 slash、零寬／control、非 default 括號 `[evil]` `(evil)` `(a/b)`、`(default)evil` 多餘尾詞、雙 `(default)`）一律殘留→OTHER；逗號分隔重複 CLONE（如 `CLONE, CLONE`）亦殘留→OTHER（從嚴）。僅 `profile.CLONE (default)` 容忍為 CLONE；`MANAGED, CLONE`／`MANAGED/CLONE` 類真混合走雙 token 矛盾→UNKNOWN，仍拒寫。
此次 S24 的 95 為 Dual、150 為 Secure Folder，這不是其他機型的固定對照。
執行前核對 serial/parent（ID 重用防護，Phase D）：auto 路徑攜帶 resolve 時 identity
作 `expected`，guard 以 fresh 比對——同 id 但 serial／parent 雙邊皆有且不等即拒
（reason 註明 reuse，疑為同 id 被不同 profile 轉世重用）；pinned 寫前取 `before`、
寫後重核 `after`，不一致即 `VerifyUnknown`（detail 註明 identity-changed，fail
closed，不計成功）；explicit-ID 入口僅做 fresh 自洽檢查（expected=null），殘餘
TOCTOU 已揭露（check 與寫入之間重建同 id 無法在此入口偵測）。serial 單邊缺失則
放行（tolerate，與 Phase B 一致）：僅雙邊皆有且不等才拒；跨 fetch 已知→未知亦拒
（continuity lost，與實作一致）。
pinned 寫入（`cloneVerified()`／`removeVerified()`）為 snapshot1＋before3＋fresh3＋write1＋after3＋list1＝12 命令（probe 最多 2 次 retry 另計）；寫前 fresh-vs-before 重核僅縮小 before-to-write 重用窗口，仍殘餘 fresh-to-write 微窗口（fail closed，與 DualRepository KDoc 一致）。
列表刷新（`refreshVerified()`，Phase D P2-D）為單一 target：一次 `pm list users`（1）
＋一次 verified-target fetch（`cmd user list -v`＋`dumpsys user`＋`getprop`＝3）
＋一次 `pm list packages --user <dualId>`（1）＝5 命令（舊 resolve-then-reverify
列表路徑為 9）；同一 target 全程，resolve→list 漂移在本 API 內消除；fetch→probe 殘唯讀窗口（只誤報 presence/absence，不重定向寫入）（explicit
舊入口殘餘除外）。Auto 降級與單一恢復入口仍由 #25 擁有，本 API 只保證列表
一致性，不改變政策門。初次 fetch 雙通道皆無 serial 即容忍放行，屬能力限制而非全防護；非阻塞操作生命週期由 #24 追蹤。註：`DualViewModel.refreshLocked()` 目前仍走 9 命令 resolve-then-reverify 舊路徑（`DualViewModel.kt:399-490`），遷移到 `refreshVerified()` 5 命令為 follow-up；兩者同為 fail-closed，僅命令數差異。
Listing 判定為 P22 已合併之 trusted 三態（`PmOutputParser.parsePackagesTrusted`，PR-2 stage 2 已消費於 `verifyListProbe`／`listProbe`）：`TrustedFull(packages)` 為白名單乾淨 listing（含包為 Installed）、`TrustedEmpty` 為零 `package:` 行之確認空（confirmed empty）、`Unknown(reason)` 為不可判（truncated／exit 非零／error-semantic／格式或 stderr 殘留，`detail` 為 `reason` 別名）。白名單（P1 BLOCKER #1）：權威 listing 除合法 `package:` 行外別無其他——每行非空 stdout 須以 `package:` 開頭（大小寫容忍，與 `TRUSTED_EXEMPT_LINE` 一致），payload 須符合 `PACKAGE_PAYLOAD`（`^[A-Za-z0-9._-]+$`，與 `ShellEscaper.SAFE_TOKEN` 同字符集，trusted 路徑專用；`parsePackages()` 本身維持寬容）；`unexpected listing format`／`malformed package line`／`unexpected stderr` 永不當空（fail closed，`refresh` 保留舊顯示）。`installed for user` 回聲在 trusted 路徑不豁免（僅 `package:`／`Success` 行跳過 error 掃描；`ALREADY_EXISTS` 在 listing 無例外，任一 `Failure` 行即 untrusted）。
截斷契約（P22）：`CmdResult.truncated` flag＋`[truncated]` marker 文本回退（`TRUNCATION_MARKER`，新舊 producer／Shizuku Binder 重建 flag 之相容嗅探）；listing 任一截斷→`Unknown`（exit 非零另附 `exit=`＋首行 hint，`take(200)`）；identity 任一通道截斷→fail closed（`pm list users` 截斷→`profilesSnapshot` `ChannelError` 不解析，Elevator pm-list 門同理降級；`cmd user list -v`／`dumpsys user`／`getprop` 任一截斷→`fetchVerifiedTarget` null／`-1`，截斷 property 不視為 no claim）。Shizuku 側 `SERVICE_VERSION=2` 升級閉環（`ShizukuExecProtocol`：v1 僅 cap 打標，v2 截斷必打標；`.version(2)` 迫 daemon 重建常駐 UserService，關閉 NEW-client＋OLD-service 間隙；S24 實機升級驗證仍待補）。
Throw defense（P22 R5）：全路徑 fail-closed（`profilesSnapshot`／`verifyListProbe`／`listProbe`／`fetchVerifiedTarget` 皆捕獲 gateway throw 轉 `ChannelError`／`Unknown`／null，`CancellationException` 重拋保協程正確；`InterruptedException` 在含 sleep／阻塞讀取的路徑（`verifyListProbe`、`SuCmdGateway` capture、fetch／probe 讀取側）先復位 `Thread.currentThread().interrupt()` 後同樣 fail closed；純寫入側僅 CE 重拋＋Exception 收斂，中斷旗由 gateway／調用方處理）。Detail caps：trusted 解析器截斷／exit／error 行 hint 皆 `take(200)` 有界；`VerifyState.Unknown`／`CloneOutcome`／`RemoveOutcome` detail 供顯示層參考，顯示層 bound（VM 層直接插值全文，改動 out-of-scope，見 `DualRepository` KDoc）。
Retry 凍結（PR-3 擁有，禁 Main）：`verifyListProbe` 非零 exit 至多 3 次（含初次）＋`VERIFY_RETRY_DELAY_MS`（1500 ms）settle wait（經 `delayFn`，預設可取消 `delay()`；測試可虛擬化；生產經 `onIo`／`Dispatchers.IO`，VM 層所有 repo 呼叫皆走 `onIo`；cancel 在等待間傳播不折疊為 Unknown）；exit-0 截斷／髒 listing 不 retry（trusted 已判 Unknown 即停，零等待；僅 exit 非零觸發 retry）；`listProbe` 零 retry（`refresh` 映射 null 為保留舊值，一次 stale 不丟資料；throw 即 null，cancel 重拋）。Locale 限制（PR-2 Phase 3 R4）：`ERROR_LINE` 僅匹配英文 pm 輸出，非英文 ROM 髒行可能漏判為已知 trade-off；fail-closed 方向不變（截斷／非零／malformed／白名單規則不受語言影響，非英文錯誤行落白名單 `unexpected listing format` 仍 `Unknown`）。

```bash
# 1. 雙源驗證（缺一不可；任一失敗即停手，不寫入不探測目標）：
adb shell cmd user list -v
#   每個 UserInfo{<id>:<label>:<flags>} 塊內找 User type 行：
#   目標 id 的兩通道都必須是 android.os.usertype.profile.CLONE（大小寫/分隔符容忍）；
#   parent 須雙通道皆知、一致、== 0（缺失 Unknown；衝突或非零 Refused）；
#   serial 雙邊皆有且不等即 Refused，單 fetch 任一缺失則放行（serial != id 屬正常）；跨 fetch 已知→未知亦拒；
#   label 含不含 DUAL 都不看；未知一律 fail closed。
adb shell dumpsys user
#   同一 id 在此通道同樣必須是 profile.CLONE；若此處是 SYSTEM/MANAGED/SECONDARY
#   或無型別行，即與上一通道矛盾或證據不足 -> 拒寫。
adb shell getprop sys.dualapp.profile_id
#   其值必須等於上述同一 id（必要佐證）；不可讀/空/格式未知 → Unknown 證據不足拒寫（不可讀版本按不支援版本明確拒寫），
#   指向不存在 id 或與 CLONE id 不一致 -> Refused 拒寫。

# 2. 僅在上一步確認同一 <dualId> 後，才可用該 id 執行寫入（示例用 95 佔位，
#    實際必須填上一步 Confirmed 的 id；未確認、未知、矛盾時不執行以下任何一行）。
#    將已安裝的 App 複製進雙開 profile（不會動到 Secure Folder）：
adb shell pm install-existing --user <dualId> com.example.chat

# 3. 唯讀診斷輔助：查看套件路徑；僅輔助診斷，不作主驗證（主驗證以 listing 為準，見 #22）。
adb shell pm path --user <dualId> com.example.chat
#   package:/data/app/.../base.apk  -> 僅表示該包有路徑回應，不能單獨證明雙開成功

# 4. 只移除雙開分身，不動主 profile（user 0）
adb shell pm uninstall --user <dualId> com.example.chat
```

- `install-existing` 僅對已存在於主 profile 的 package 有效；全新 APK 請先在主 profile 正常安裝一次。
- 重啟注意（S24 OneUI 9 兩次觀察，假說待對照實驗）：`pm install-existing --user <dualId>` 建立的雙開連結在硬重啟、軟重啟後各消失一次（user 0 主包與完整安裝的雙開包不受影響）；疑為開機時 DualAppManager 對非白名單條目做對帳清理，恰為本 hook 要放寬的對象。重啟後請以 `pm list packages --user <dualId>` 複驗，消失即重裝。
- 指令失敗時請看 `msg` 欄（`Failure` / `Unknown package` / `not installed` 皆視為失敗）。
- 本 App 的 `DualRepository.clone/remove/verifyState/list` 即為上述指令的程式化封裝（[已支援]：`core` 層＋單元測試＋JVM 組裝測試；App 真 gateway 與 readiness gating 已接線：`SuCmdGateway` 經 `su -c`／`ShizukuCmdGateway` 經 UserService＋`channelReady` 探測 gating＋選不到回 `NoOp` fail-closed；目標解析為 PR-1 階段2雙源驗證＋失敗拒寫，label-only 舊路徑已 `@Deprecated`，`verify(pkg,dualId)` Boolean 已 `@Deprecated` 請改用 `verifyState`）。寫後確認（`cloneVerified`／`removeVerified`）以相同已驗證目標的 package listing 為準，不再只用 `pm path`（`pm path` 僅輔助診斷，不能當主驗證；plain `clone()`／`remove()` 無內建 re-check），listing 判定為 P22 已合併之 trusted 三態＋白名單＋截斷契約＋throw defense＋detail caps＋retry 凍結（見 §3 P22 段）。[S24 已驗證]僅限 App ROOT 一鍵、已授權 SHIZUKU 一鍵（已安裝 verify 單次）、裸 pm clone、`su -c` 重裝、uninstall 回滾，詳見 §0 與 [S24 調查](UX_REVIEW_S24.md) §5；App ROOT 之外的完整 binder／授權矩陣、App 內 Shizuku 未授權路徑／首次選路、marker gateway-`cat` 顯示及當前 source 可追溯部署仍 [待實機驗證]。
- 移除確認已知限制（PR-33）：
  - (a) 確認 TTL 5 分鐘走 monotonic 時鐘（`SystemClock.elapsedRealtime()`，免 wall-clock 回撥延長有效期；pending 不跨重啟，reboot 重置無影響；JVM 預設 `currentTimeMillis` 僅供測試注入）。
  - (b) 同名重裝限制：membership 僅保證確認時可見同名 package，不綁定安裝世代（profile 世代由 serial 綁定；package 安裝世代不綁定：同名重裝視為同一 membership）。
  - (c) shell 檢查→uninstall 非原子（裝置限制，repo 雙閘僅縮小窗口）。
  - (d) S24 實機 destructive／negative acceptance [S24 已驗證]（2026-11-05，SM-S9210，ROOT 通道）：身分雙 CLONE＋serial 95＋prop 95 確認、`Cloned (35)` 真實列表；可拋棄 overlay（`com.android.settings.supervision.overlay`，owner 有／95 無，先 `install-existing` 進 95）經 App 兩階段確認移除→`removed:`＋95 內消失＋owner 副本完好＋總數回 35（淨零）；negative（取消鍵、back 鍵）皆零卸載並保留正確訊息，截圖存檔；未安裝拒絕以 JVM 測試為準（實機訊息僅截圖存檔）。殘留：旋轉重建、TalkBack 朗讀僅 JVM／靜態證據。

## 4. 通道選擇與 LSPosed 模式（F4b-P1 picker＋P2 marker；ROOT／SHIZUKU 已授權一鍵已抽驗 [S24 已驗證]，其餘 [待實機驗證]）

- 已 ROOT + LSPosed 的裝置：到 LSPosed 管理器勾選 **Galaxy Dual Plus**，
  作用域勾 `system`＋`com.samsung.android.da.daagent`（見 `docs/HOOK.md` §1.4／§2.2）後重啟；再到 App 內按 LSPosed 按鈕切到 LSPosed 模式：Hook 行顯示 marker 狀態（`偵測到歷史 Hook 標記（…，僅存在性證據，未確認本次開機是否生效）`／`未偵測到`／`狀態未知（…）`，P1 四態見本節末段；file marker 存在性證據，僅顯示、永不授權；daagent 側因 SELinux 寫入失敗故 marker 恆缺 daagent，見 `docs/HOOK.md` §1.4）；LSPosed 通道為 NoOp 佔位、寫入一律拒絕（whitelist 政策分支待實作）。marker gateway-`cat` 顯示 [待實機驗證]，無實機證據。
- AUTO 模式按 su→Shizuku→NoOp 解析執行通道（hook 永不被自動選中）；`channelReadyFor` 純函數 [已支援]（ROOT 需 su 探測、hook 訊號永不授權、NONE 永不就緒）；Auto probe 降級已實作（P25：首次探測即依 live probes 修正啟動猜測——su 存在但探測拒絕＋Shizuku 通過→SHIZUKU＋降級說明，雙通道皆失敗→NOOP 唯讀，暫態失敗後點 `重新連線並更新` 即重探恢復）；實機切換 [待實機驗證] DEVICE-UNVERIFIED。
- LSPosed 模組僅 hook 雙開白名單查詢（observe-only 佔位，不放寬任何包名，見 `docs/HOOK.md` 第 1.1 節），
  絕不讀寫 Knox 容器；白名單真正放寬需等非空政策接上（[待實作]）。
- P1 診斷限制（配合 [#27](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/27)）：Hook 狀態為四態——偵測到歷史 Hook 標記（僅存在性證據，未確認本次開機是否生效）／未偵測到／狀態未知（無權限讀取）／狀態未知（無可用診斷通道）。明選 LSPosed（NoOp）時診斷讀取獨立於所選寫入通道：仍嘗試直接讀取＋經當時已探測通過的真通道（ROOT／SHIZUKU）`cat`；切換通道時保留當時已驗證的診斷實例（新實例未驗證前仍以後者 `cat`，不觸發新 `su` 授權；新驗證通過即覆寫，授權撤銷即失效回未知）；若無任何真通道且直接讀取不可讀（App uid 對 `/data/system` 恆 EACCES），顯示「狀態未知」而非「未偵測到」——後者只保留給確定可讀但缺失的情況（fail-closed 誠實顯示，不再假陰性）。要完整診斷需至少一個探測通過的真通道；診斷結果永不進入路由／`channelReady`（hook-neutral，沿襲 #19）。

## 5. 常見問題

| 症狀 | 原因 / 解法 |
| --- | --- |
| `(NONE)` 或「通道未就緒」 | 先區分所選 gateway、探測能力與 Shizuku binder／授權。未就緒時會拒寫；點 `重新連線並更新`（probe 含 Auto 降級＋refresh 單一入口）即重探恢復。Auto probe 降級已實作（#25）；不能以標題的裝置路由代替實際通道狀態（標題列顯示的是 `execIdentity/resolvedGateway`）。 |
| `no dual profile` | 可能是未建立、候選歧義或輸出無法解析，不能一律解讀為尚未建立。先唯讀核對平台身分；不確定即停止寫入，見 #21/#27 |
| `Failure [INSTALL_FAILED_...]` | 來源 APK 不存在或儲存空間不足；先確認主 profile 已安裝該 App |
| Play 審查相關警告 | 本專案不發 Play 版，請一律使用 GitHub Release 的 APK |

## 6. 來源選取與批次建立（PR-7 P2 #26 階段 1）

個人來源 catalog 為唯讀展示＋勾選：`MainActivity` 經 `PackageManager.getInstalledApplications(0)`
僅列舉呼叫者自身 user 可見的已安裝 App（政策：不跨 user 枚舉，不讀取其他 user 的套件、檔案與私有內容，包括其他 user 下的容器資料），
以 `SourceAppEntry` 唯讀列出；寫入目標仍以 `DualRepository` 跨來源 verified dualId 為準（見 §3），
本表只決定「要對哪個包名打勾」。

- 搜尋／系統開關語義：搜尋列比對應用名稱或包名子字串（大小寫無關，空白不過濾），
  只改變顯示範圍；「系統應用」開關只切換是否顯示系統 App，兩者皆不觸發寫入。
  旋轉重建時搜尋字串由 ViewModel 回填（保留過濾條件）。
- 勾選≠卸載：清單列為 CheckBox，勾選／取消勾選只改 `UiState.selectedKeys`
 （身分鍵為 `serial:package` 的 AppKey），永不呼叫 uninstall；取消勾選不是移除分身。
- 保存／取消保存／清除勾選三者區別：
  「保存選取」只把目前勾選寫入 app-private 持久化（需已知目標 `targetId > 0`，
  否則按鈕禁用；只保存仍在 catalog 的 key），不寫入任何分身；
  「取消保存」只刪除持久化條目，不卸載分身、也不清除目前記憶體勾選；
  「清除勾選」只清空記憶體勾選（顯示「已清除勾選（未卸載任何分身）」），不碰持久化、不卸載。
- 全選可見截斷語義：清單單次最多渲染前 200 筆（`MAX_SOURCE_ROWS` 效能護欄，
  超出顯示「…等共 N 筆，僅顯示前 200 筆，全選可見只選前 200 筆」）；
  「全選可見」與渲染走同一過濾／排序／截斷，只勾選可見前 200 筆，超出請用搜尋縮小範圍。
  來源清單載入失敗時保留舊列表（不清空），並顯示獨立錯誤行（與「尚未載入」區分），「重新連線並更新」按鈕會同步重試 catalog 載入；「系統應用」開關的無障礙描述隨顯示中／已隱藏狀態同步更新。
- 批次按鈕計數與部分成功解讀：按鈕顯示「建立 N 個分身」（N＝目前勾選數，無勾選時禁用；
  批次執行中勾選區與相關按鈕一律禁用，不可中途改勾選）。
  執行走 `cloneVerifiedExpected(pkg, expectedId, expectedSerial)` 原子 pin 逐項（包名升冪確定順序），結果分成功／失敗／未知三類：
  成功才加入 `cloned`，失敗（含 catalog 缺失的 `InvalidPackage`）與未知（需重查）分開列出；
  批次起始先檢查選取綁定目標與裝置當前目標一致（`selectionBoundTarget == expectedTarget`，含未綁定哨兵與 legacy id-only），
  不一致則整批拒絕並清空選取（`INVALID`「選取已綁定舊目標，已清空，請重新勾選確認」，零寫入）；
  逐項 pin 發現目標漂移時立即停止（漂移本身不記為條目，已完成項保留為 partial，診斷為 `VERIFY_MISMATCH`），
  部分成功如實呈現（例「成功 1 / 失敗 1 / 未知 0」）；全成功清診斷事件，否則留彙總診斷；
  成功或未知（可能已寫入未驗證）即把清單標 STALE；目標漂移時無條件 STALE 並更新為 live target、清空選取（同目標變更處理）。
  唯讀／佔位通道整批拒絕（不產生報告）；勾選變更後舊報告即清（stale 報告不保留）。
- 重啟恢復行為：重啟後新 ViewModel＋同一持久化，恢復只打勾（永不自動執行 clone），
  只恢復仍在目前 catalog 的 key（已卸載的包自動捨去），且同一目標只恢復一次；
  空 catalog 上不置恢復標記（待 catalog 就緒後仍可重試）；目標變更（profile 變動）時舊選取失效清除。

## English Summary

- Privilege routing (tested pure function + wired channels [DEVICE-UNVERIFIED]): manual `ChannelPreference` picker (AUTO default, `selectChannel` swaps gateway + re-probes; `channelReadyFor` pure gate, tested: ROOT needs su probe, hook signal never grants, NONE never ready), su ROOT via `SuCmdGateway` (`su -c`), Shizuku (SHELL) via `ShizukuCmdGateway` (UserService; `newProcess` removed in 13.1.5)
  fallback, else read-only (NONE) with `NoOp` fail-closed. LSPosed mode is an explicit NoOp placeholder (writes refused); hook state comes from the F4b-P2 file marker (display-only, `HookStateProvider` direct + gateway-`cat` reads); marker gateway-`cat` display, Shizuku unauthorized/dead-guidance paths, first-time SHIZUKU selection, callback logs, and non-empty policy are unverified (`pm path` is auxiliary only, never ground truth, see #22); in-app ROOT one-click, authorized-SHIZUKU one-click (installed-verify), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24.
- Pair Shizuku via Wireless debugging (Android 11+, unverified flow), approve Galaxy Dual Plus, then Reconnect-and-refresh (single entry `reconnectAndRefresh()`: probe with Auto degrade + refresh; title syncs to `(SHELL/SHIZUKU)` only when the Shizuku probe passes, otherwise stays read-only NONE — switching itself is DEVICE-UNVERIFIED).
- Error-first (`combineWithDegradeNote`): refresh results + Auto degrade note with error priority — `CHANNEL`/`FAILED`/`NO_DUAL`/`VERIFY`/`INVALID` keep their kind (never downgraded to `INFO`) with the degrade reason appended (`;` join, recovery entry included); probe-pass ≠ PM-readable (`anyProbePassed` is **NOT** proof of PM readability; true readability = `pmReadable == true` + `writeReady`, fail-closed `CHANNEL` refusal when PM unreadable).
- Clone with `pm install-existing --user <dualId> <pkg>`; verify with `pm list packages --user` ground truth (not `pm path` alone);
  remove with `pm uninstall --user`. Never touches Secure Folder (Knox).
  `core` encapsulation plus gateway assembly are unit-tested; in-app ROOT one-click `cloned:`, authorized-SHIZUKU one-click (installed-verify single), bare-pm clone, `su -c` reinstall, and uninstall rollback verified on S24; system hook dual-method deoptimize+hooked (registration only), daagent `using provider.WhiteListApps` hits (category selection only), and marker main-path scope=system_server (historical, ~5.5h before this boot) verified as historical evidence only; callback logs, Shizuku unauthorized flows, first-time selection, binder dead-guidance, gateway-`cat` display, LSPosed writes, and non-empty policy remain DEVICE-UNVERIFIED (`pm path` auxiliary only, see #22); outside the specified normal SHELL clone/remove + manual channel history (see UX_REVIEW_S24.md §5), full binder/auth matrix, end-to-end matrix, and traceable build of current source remain unverified.
- Hook is an observe-only placeholder (empty policy, see `docs/HOOK.md`); it does not extend
  any package yet.
- GitHub sideload builds only; no Play release. Manifest audit per #26 done:
  `REQUEST_INSTALL_PACKAGES`/`MANAGE_EXTERNAL_STORAGE` removed (no call chain);
  only `QUERY_ALL_PACKAGES` kept (source catalog enumeration).
