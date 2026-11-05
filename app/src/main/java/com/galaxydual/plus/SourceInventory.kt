// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-7 P2 (issue #26, 階段 1):個人來源 App 唯讀 catalog。
//
// 範圍 (S24 證據界線,見 docs/UX_REVIEW_S24.md §2):
// - 只列舉「呼叫者所屬 user 可見」的已安裝 App (PackageManager 授權範圍):
//   loader 用無參數 getInstalledApplications(0),絕不跨 user 枚舉,絕不碰
//   Secure Folder / Knox 容器 (150 等其他 user 的套件/檔案/私有內容一律不讀)。
// - AppKey = sourceUserSerial + packageName ("<serial>:<pkg>"),同一包名在
//   不同來源 user 下永遠是不同 key;圖示載入狀態永不參與身分鍵 (見
//   SourceIconState),避免快取狀態污染選取/寫入目標。
// - 排序/搜尋/篩選皆為純函數 (JVM 可測);只有 loadSourceApps 碰 Android
//   PackageManager (JVM 測試不呼叫)。
package com.galaxydual.plus

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

// 個人來源 catalog 的一列 (唯讀展示用,不做寫入授權依據)。
// 寫入目標仍以 DualRepository 的跨來源 verified dualId 為準 (#21),本表
// 只決定「要對哪個包名打勾」。
data class SourceAppEntry(
    // 包名 (寫入時傳給 cloneVerified 的唯一依據)。
    val packageName: String,
    // 顯示名 (PackageManager label,缺失時回退包名;僅顯示用)。
    val label: String,
    // 來源 user 的 serial (AppKey 的一部分;單機個人來源通常固定)。
    val sourceSerial: Int,
    // 是否為系統 App (預設篩選掉,需使用者主動開啟才顯示)。
    val isSystemApp: Boolean,
) {
    // 選取身分鍵:來源 serial + 包名。圖示/載入狀態永不進入此鍵。
    val appKey: String get() = sourceAppKey(sourceSerial, packageName)
}

// 選取身分鍵構造:同一包名在不同來源 user 下為不同 key。
fun sourceAppKey(sourceSerial: Int, packageName: String): String =
    "$sourceSerial:$packageName"

// 搜尋/篩選 (純函數):
// - query:大小寫無關,比對 label 子字串或包名子字串;空白=不過濾。
// - includeSystem=false 時剔除系統 App。
fun filterSourceApps(
    apps: List<SourceAppEntry>,
    query: String,
    includeSystem: Boolean,
): List<SourceAppEntry> {
    val q = query.trim().lowercase()
    return apps.filter { e ->
        if (!includeSystem && e.isSystemApp) return@filter false
        if (q.isEmpty()) return@filter true
        e.label.lowercase().contains(q) || e.packageName.lowercase().contains(q)
    }
}

// 排序 (純函數):label 大小寫無關升冪,同名再比包名,皆同再比 AppKey
// (保證全序,渲染/批次執行順序確定)。
fun sortSourceApps(apps: List<SourceAppEntry>): List<SourceAppEntry> =
    apps.sortedWith(
        compareBy(
            { it.label.lowercase() },
            { it.packageName },
            { it.appKey },
        ),
    )

// 圖示載入記憶 (純記憶體狀態,非身分鍵):
// - markLoaded(appKey):該 key 的圖示已嘗試載入過 (成功或失敗皆記,避免重打 PackageManager)。
// - isLoaded 只供「是否再嘗試載入」決策;選取/寫入/恢復一律只讀 appKey,永不讀此表。
// - key 必須是完整 appKey (serial+pkg),不可只用包名 (跨來源覆蓋)。
class SourceIconState {
    private val loaded = mutableSetOf<String>()

    fun markLoaded(appKey: String) {
        loaded.add(appKey)
    }

    fun isLoaded(appKey: String): Boolean = appKey in loaded

    fun clear() {
        loaded.clear()
    }
}

// 載入呼叫者 user 可見的已安裝 App (DEVICE-UNVERIFIED:需實機驗證列表完整性)。
//
// 授權範圍保證:
// - 只用 pm.getInstalledApplications(0):回傳呼叫者所屬 user 授權可見的
//   列表,不帶跨 user 枚舉 (不用 getInstalledApplicationsAsUser 等隱藏
//   API,不讀其他 user 的套件/檔案)。
// - Secure Folder / Knox 容器屬於其他 user,一律不在此列表內。
// - API 30+ 完整列表需 QUERY_ALL_PACKAGES (見 manifest 註解);若系統過濾,
//   回傳即為授權可見子集,不偽造、不補全。
// 穩健性:逐項 try/catch,單項失敗跳過;整體載入失敗回傳 null (呼叫方保留舊列表),
// 空列表=裝置確實無應用 (合法寫入)。除整體失敗回 null 外永不拋出。
fun loadSourceApps(pm: PackageManager, sourceSerial: Int): List<SourceAppEntry>? {
    val installed: List<ApplicationInfo> = try {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    } catch (_: Exception) {
        return null
    }
    val out = ArrayList<SourceAppEntry>(installed.size)
    for (info in installed) {
        try {
            val pkg = info.packageName ?: continue
            if (pkg.isBlank()) continue
            val label = try {
                pm.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            } catch (_: Exception) {
                pkg
            }
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            out.add(
                SourceAppEntry(
                    packageName = pkg,
                    label = label,
                    sourceSerial = sourceSerial,
                    isSystemApp = system,
                ),
            )
        } catch (_: Exception) {
            // 單項損壞跳過,不影響整表。
            continue
        }
    }
    return out
}
