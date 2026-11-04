# Galaxy-Dual-app-Plus

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 三星 Dual 雙開工具：自動提權路由，LSPosed 優先、Shizuku 降級，絕不觸碰 Secure Folder。

## 目標 (Goals)

- 在三星 One UI 裝置上啟用並管理 **Dual Messenger / Dual App** 雙開。
- 提權策略：**LSPosed 優先（ROOT / 系統注入），Shizuku 降級（Shell / ADB 權限）**。
- 無 ROOT、無 LSPosed 時，自動降級為 Shizuku 模式執行 `pm` 指令。
- **不碰 Secure Folder（Knox 容器）**：不讀寫、不掛載、不繞過 Knox。
- 模組化設計：`app`（UI）、`core`（提權抽象與命令閘道）、`hook`（LSPosed 注入）。

## 架構總覽 (Overview)

```
app  -> core (PrivilegeRouter / Executor / PmOutputParser)
hook -> XposedModule (DualHook, apiVersion 102)
```

- `core`：定義 `Privilege`（NONE/SHELL/ROOT）、`CmdGateway` 介面、`PmOutputParser` 解析器。
- `app`：UI 層，透過 `core` 選擇最佳可用提權通道。
- `hook`：LSPosed 模組，hook 三星雙開白名單與服務，僅在已授權環境載入。

詳見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

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

## 提權路由 (Privilege Routing)

1. 偵測 LSPosed 模組是否啟用 → `Privilege.ROOT`。
2. 否則偵測 Shizuku 是否可用 → `Privilege.SHELL`。
3. 否則 → `Privilege.NONE`，僅顯示唯讀狀態，不執行寫入。

## 自動模式 (Auto Mode)

開機 / 開 App 時自動依序探測，無需手動切換：

- 有 LSPosed（模組已勾選並啟用）→ 自動掛載 hook 通道（`Privilege.ROOT`），直接走系統注入 / `su -c`。
- 沒有 LSPosed 但 Shizuku 可用 → 自動降級走 Shizuku 通道（`Privilege.SHELL`），執行 `pm install-existing / uninstall --user / path --user`。
- 兩者皆無 → `Privilege.NONE` 唯讀模式，寫入按鈕停用；稍後啟用任一通道後，下次 `refresh()` 自動切換。

詳見 [docs/USAGE.md](docs/USAGE.md) 第 1、4 節。

## 不碰 Secure Folder (Policy)

- 本專案不會存取 `/data/media/0/../knox`、Knox 容器、Secure Folder 內部資料。
- 不提供 Knox 規避、KnoxPatch 式補丁、Knox 熔斷規避。

## 免責聲明 (Disclaimer)

- 本專案不碰 Secure Folder / Knox：不讀寫、不掛載、不繞過 Knox 容器；任何 Knox 熔斷規避皆明確不在範圍內。
- 需 ROOT + LSPosed 或 Shizuku 環境才能寫入；操作系統服務本來就有風險（請先備份），後果自負。
- 僅供學習研究與個人裝置使用；請遵守當地法規與三星服務條款。

## Credits

- 詳見 [docs/CREDITS.md](docs/CREDITS.md)。
- 本專案程式碼皆為自寫（MIT），第三方僅為思路參考，已重寫不傳染授權。

## English Summary

Galaxy-Dual-app-Plus enables Samsung Dual Messenger / Dual Apps via a privilege router:
LSPosed first, Shizuku fallback. It never touches Secure Folder (Knox).
Modules: `app` (UI), `core` (privilege abstraction), `hook` (LSPosed module, API 102).
All skeleton code is original and MIT-licensed. See `docs/CREDITS.md` for references.

## License

MIT, Copyright 2026 samson910022. See [LICENSE](LICENSE).
