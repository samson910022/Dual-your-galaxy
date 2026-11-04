# Galaxy-Dual-app-Plus

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 三星 Dual 雙開工具（開發中腳手架，未經實機驗證）：設計目標為自動提權路由，
> LSPosed 優先、Shizuku 降級，絕不觸碰 Secure Folder。
> 目前僅路由純函數與命令封裝有單元測試；App 接線與實機流程尚未完成（見下文「實作狀態」）。

## 目標 (Goals)

- 在三星 One UI 裝置上啟用並管理 **Dual Messenger / Dual App** 雙開（目標，未經實機驗證）。
- 提權策略（設計目標，接線未完成）：**LSPosed 優先（ROOT / 系統注入），Shizuku 降級（Shell / ADB 權限）**。
- 無 ROOT、無 LSPosed 時，設計上自動降級為 Shizuku 模式執行 `pm` 指令（`core` 純函數已實作，App 端綁定待實作）。
- **不碰 Secure Folder（Knox 容器）**：不讀寫、不掛載、不繞過 Knox。
- 模組化設計：`app`（UI）、`core`（提權抽象與命令閘道）、`hook`（LSPosed 注入）。

## 架構總覽 (Overview)

```
app  -> core (PrivilegeRouter / Executor / PmOutputParser)
hook -> XposedModule (DualHook, apiVersion 102)
```

- `core`：定義 `Privilege`（NONE/SHELL/ROOT）、`CmdGateway` 介面、`PmOutputParser` 解析器。
- `app`：UI 層（`MainActivity` 已有 Clone／Remove／Refresh／請求 Shizuku 授權按鈕＋`StateFlow` 收集＋Shizuku binder／授權生命週期接線；但倉庫仍為 `NoOpGateway`，探測跑在 NoOp 佔位 elevator 上恆回 NONE，`channelReady` 恆 `false` 一律拒寫；真通道 gateway 待 F4 接入），目標是透過 `core` 選擇最佳可用提權通道。
- `hook`：LSPosed 模組佔位，hook 三星雙開白名單與服務，僅在已授權環境載入；政策集合預設為空，屬 observe-only（會安裝、會記 log，但不改變行為），詳見 [docs/HOOK.md](docs/HOOK.md) 第 1.1 節。

詳見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 實作狀態 (Status, Phase F1 誠實標示)

標示體系（與 [docs/USAGE.md](docs/USAGE.md) 一致）：**[已支援]** = 有程式碼＋單元測試／CI 閘門；**[待實作]** = 程式碼未接通；**[待實機驗證]** = 程式碼存在但無三星實機證據，一律不宣稱可用。

- [已支援] profile 解析安全邊界（`resolveDualUserId`：只認唯一 DUAL、永不選 user 0、多 DUAL／無 DUAL 直接拒絕寫入，見 `DualSafetyTest`）。
- [已支援] `pm` 命令封裝（`DualRepository.cloneVerified / removeVerified / refresh`：單快照釘選＋`pm path` 二次確認，見 `CloneVerifyRefreshTest`）。
- [已支援] lint／CI 閘門（`./gradlew` 固定 8.7、fail-fast quality gates、DEX 檢查）。
- [待實作] App 內一鍵 clone 真通道（`MainActivity` 已有 Clone／Remove／Refresh／請求 Shizuku 授權按鈕＋`StateFlow` 收集，Shizuku binder／授權生命週期已接；`DualViewModel.clone/remove/refresh/recheckPrivilege` 邏輯存在，但倉庫仍為 `NoOpGateway`，`channelReady` 恆 `false` 一律拒寫；真通道 gateway 待 F4 接入）。
- [待實作] 非空 whitelist policy（`EXTRA_WHITELIST_PACKAGES` 預設為空，hook 為 observe-only；接上政策來源前不放寬任何包名）。
- [待實機驗證] LSPosed 載入（system／daagent scope 注入、重啟後 log 確認，無實機）。
- [待實機驗證] Shizuku 四情境（有／無 binder × 有／無授權，以及重啟後重啟動流程，無實機）。
- [待實機驗證] 雙開實機（`pm install-existing / path / uninstall --user <dualId>` 端到端，無實機）。

凡標 [待實作]／[待實機驗證] 者，皆不可描述為「可用功能」；下文路由／自動模式描述的是設計意圖與 `core` 純函數行為，不是已接通的 App 流程。

## 需求 (Requirements)

- Android 10+，建議三星 One UI 4.0+，`compileSdk 34`。
- Kotlin 2.x，AGP 8.x，JDK 17。
- 可選：LSPosed（優先）、Shizuku（降級）。

## 快速開始 (Quick Start)

```bash
# Clone
git clone https://github.com/samson910022/Galaxy-Dual-app-Plus.git
cd Galaxy-Dual-app-Plus

# Build (Gradle 8.7 via the committed wrapper, see gradle/wrapper/README.md)
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

> 注意：本倉庫已提交完整 Gradle wrapper（固定 8.7），CI 與本機一律使用 `./gradlew`；CI 另以 `gradle/actions/setup-gradle` 提供快取，無需自行安裝 Gradle。

## 提權路由 (Privilege Routing) — 設計意圖＋`core` 純函數，App 接線待實作

路由純函數（`routePrivilege`，[已支援]，單元測試覆蓋）依序判斷；App 端探測框架（`probeChannels`／`recheckPrivilege`＋Shizuku 生命週期）已接但跑在 NoOp 佔位 elevator 上，真通道綁定（[待實作]，待 F4）完成前不代表可用流程：

1. 偵測 LSPosed 模組是否啟用 → `Privilege.ROOT`。
2. 否則偵測 Shizuku 是否可用 → `Privilege.SHELL`。
3. 否則 → `Privilege.NONE`，僅顯示唯讀狀態，不執行寫入。

## 自動模式 (Auto Mode) — 設計目標，[待實作]＋[待實機驗證]

以下為真通道接通後的預期行為；現階段探測跑在 NoOp 佔位 elevator 上恆回 NONE（`hasLsposed = false` 寫死、`Su`／`ShizukuElevator` 無真 gateway），倉庫為 `NoOpGateway` 且 `channelReady` 恆 `false` 一律拒寫（按鈕已存在但寫入被 `isWritable()` 攔下），無自動切換實機證據：

- 有 LSPosed（模組已勾選並啟用）→ 預期取 `Privilege.ROOT`，走 hook 通道或 `su -c`（[待實機驗證]：LSPosed 載入未驗證；hook 現為 observe-only，不放寬任何包名，見 [docs/HOOK.md](docs/HOOK.md)）。
- 沒有 LSPosed 但 Shizuku 可用 → 預期降級走 Shizuku 通道（`Privilege.SHELL`），執行 `pm install-existing / uninstall --user / path --user`（Shizuku binder／授權生命週期已接，真通道 gateway［待實作］，待 F4；[待實機驗證]：Shizuku 四情境無實機）。
- 兩者皆無 → `Privilege.NONE` 唯讀模式（[已支援]：路由與 `NoOpGateway` 拒寫；現階段因 `channelReady` 恆 `false`，Clone／Remove 一律被拒（按鈕已存在，`refresh()`／`recheckPrivilege()` 已接但無法切出 NONE），真通道切換屬 [待實作]＋[待實機驗證]）。

詳見 [docs/USAGE.md](docs/USAGE.md) 第 1、4 節。

## 不碰 Secure Folder (Policy)

- 本專案不會存取 `/data/media/0/../knox`、Knox 容器、Secure Folder 內部資料。
- 不提供 Knox 規避、KnoxPatch 式補丁、Knox 熔斷規避。

## 免責聲明 (Disclaimer)

- 本專案不碰 Secure Folder / Knox：不讀寫、不掛載、不繞過 Knox 容器；任何 Knox 熔斷規避皆明確不在範圍內。
- 寫入設計上需 ROOT + LSPosed 或 Shizuku 環境；但現階段 `channelReady` 恆為 `false`，一律拒寫（`isWritable()` fail-closed），即使裝置已有 ROOT／Shizuku 環境也一樣，待 F4 接入真通道後才可能寫入；操作系統服務本來就有風險（請先備份），後果自負。
- 僅供學習研究與個人裝置使用；請遵守當地法規與三星服務條款。

## Credits

- 詳見 [docs/CREDITS.md](docs/CREDITS.md)。
- 本專案程式碼皆為自寫（MIT），第三方僅為思路參考，已重寫不傳染授權。

## English Summary

Galaxy-Dual-app-Plus is a scaffold towards enabling Samsung Dual Messenger / Dual Apps
via a privilege router (LSPosed first, Shizuku fallback). It never touches Secure Folder (Knox).
Modules: `app` (UI wired with Clone/Remove/Refresh/permission buttons + StateFlow + Shizuku lifecycle, but still NoOp-backed with channelReady always false, read-only), `core` (privilege abstraction, tested), `hook` (LSPosed module,
API 102, observe-only placeholder with empty policy).
Status: routing pure function, `pm` command encapsulation, and lint/CI gates are implemented
with unit tests; in-app one-click true channel and non-empty whitelist policy are TODO;
LSPosed load, Shizuku scenarios, and on-device dual flows are unverified on real hardware.
Nothing marked TODO/unverified is claimed as working. See `docs/CREDITS.md` for references.
All skeleton code is original and MIT-licensed. See `docs/CREDITS.md` for references.

## License

MIT, Copyright 2026 samson910022. See [LICENSE](LICENSE).
