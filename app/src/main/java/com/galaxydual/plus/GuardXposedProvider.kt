// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1, Phase 4 hardened):app 自有的 XposedProvider 守衛。
// (Phase 5: header kept bilingual; semantics unchanged. English: app-owned
// guard provider replacing the libxposed exported provider at the same
// authority; only root(0)/system(1000) or the app itself may deliver a binder.)
//
// libxposed service AAR 把自己的 XposedProvider 宣告為 exported 且無權限,
// 任何 app 都能以 content://<applicationId>.XposedService 呼叫 SendBinder
// 挾帶假 IBinder,劫持政策通道 (XposedServiceHelper.onBinderReceived)。
// 這裡以同 authority 的守衛 provider 取代它:僅允許 root(0)/system(1000) 或
// 本 app (uid == myUid) 交付 binder,其餘直接回 null。Guard 即邊界:任何
// 未經此門控的 binder 不得進入 XposedServiceHelper。
//
// 注意:函式庫的 XposedProvider 是 final,無法繼承,故以委派 (composition)
// 包裹同一個函式庫實例,call 行為與其完全一致 (只加 UID 門控 + 速率門控);
// onCreate 不可委派 (見該方法註解)。CRUD (query/getType/insert/delete/update) 在
// 函式庫本就無效 (回 null/0),故直接回 null/0,不委派。
package com.galaxydual.plus

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.github.libxposed.service.XposedProvider
import java.util.concurrent.atomic.AtomicInteger

class GuardXposedProvider(
    // Test seams: production reads the kernel/binder state.
    private val callingUidProvider: () -> Int = { Binder.getCallingUid() },
    private val myUidProvider: () -> Int = { Process.myUid() },
    private val delegateCall: ((String, String?, Bundle?) -> Bundle?)? = null,
    // Phase 4 rate-limit clock: DEX 用 SystemClock.elapsedRealtime (單調);
    // JVM 單元測試無 framework 時 SystemClock 為 stub 會拋,fallback 到
    // currentTimeMillis 保證測試可跑。測試可注入假時鐘。
    private val nowMsProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    },
    // 拒絕診斷接收器：生產預設寫 Log.w（try/catch 包覆，JVM stub 安全）；
    // JVM 測試注入記錄式 lambda 以鎖定計數行為。只收 uid 類別字串，絕不
    // 收 binder 內容/arg/extras（見 uidRejectionCategory）。
    private val onRejectLog: (String) -> Unit = { msg ->
        try {
            android.util.Log.w("GuardXposedProvider", msg)
        } catch (_: Throwable) {
            // Best effort: JVM stub / framework 缺席時靜默。
        }
    },
) : ContentProvider() {

    // 函式庫 provider 為 final,無法继承;以委派保留其 call 行為 (CRUD 不委派)。
    private val delegate: XposedProvider by lazy { XposedProvider() }

    // Phase 4: per-uid 簡易 token-bucket (固定窗口)。Guard 即邊界:熱路徑上
    // 擋掉突發洪水,超限回 null (fail-closed,不委派)。窗口 1s、上限 20/uid:
    // 正常 LSPosed daemon 握手僅數次,惡意 app 洪水則被節流。Best-effort,
    // 狀態僅存記憶體 (重啟即清空)。
    // 有界化：上限 512  uid 條目（callingUid 為 kernel 提供，正常僅數十種；
    // 512 為防禦性上限，避免極端偽造/多用戶爆炸撐大 map）。超限時驅逐任意
    // 最舊首鍵（best-effort LRU-ish；丟失僅影響該被驅逐 uid 的窗口計數，
    // 下次重建，不影響信任門控）。
    // Phase7 註記：滿表時選擇「驅逐最舊＋放行本次（return false）」而非
    // 「滿即 return true」。理由：rateWindows 僅記窗口計數（per-uid 計數器），
    // 信任判定另由 isTrustedXposedCaller 獨立執行，驅逐永不放寬信任門控；
    // 滿即拒（return true）會把合法的新 uid（例如多用戶新 daemon）在表滿時
    // 誤判為洪水而拒絕握手，可用性損失大於節流收益，故保留驅逐語義。
    private val rateLock = Any()
    private val rateWindows = mutableMapOf<Int, Pair<Long, Int>>()
    private val rejectionLogs = AtomicInteger(0)

    private fun isRateLimited(uid: Int): Boolean {
        val now = try {
            nowMsProvider()
        } catch (_: Throwable) {
            // 時鐘失效時 fail-open (仍受 UID 門控保護):可用性優先,避免誤殺 daemon。
            return false
        }
        synchronized(rateLock) {
            val cur = rateWindows[uid]
            if (cur == null || now - cur.first >= RATE_WINDOW_MS) {
                if (cur == null && rateWindows.size >= RATE_WINDOWS_MAX) {
                    rateWindows.keys.firstOrNull()?.let { rateWindows.remove(it) }
                }
                rateWindows[uid] = now to 1
                return false
            }
            if (cur.second >= RATE_LIMIT_MAX) return true
            rateWindows[uid] = cur.first to (cur.second + 1)
            return false
        }
    }

    // 委派分發:測試注入 delegateCall 時用該 lambda (含回傳 null);未注入時
    // 走 lazy delegate。內層再包 try/catch:函式庫 provider 拋出 (含 Error)
    // 不得越過 Guard 邊界,一律轉 null (fail-closed)。
    private fun dispatch(method: String, arg: String?, extras: Bundle?): Bundle? =
        try {
            if (delegateCall != null) delegateCall(method, arg, extras)
            else delegate.call(method, arg, extras)
        } catch (_: Throwable) {
            null
        }

    override fun onCreate(): Boolean {
        // 不可委派 delegate.onCreate():該方法會取 getContext(),而未經 framework
        // 附加的實體 mContext 為 null,會 NPE。它唯一作用是設
        // RemotePreferences.shouldNotifyCleared (此版僅在 Editor.clear() 通知
        // listener 時讀取;本 app 綁定用 remove(key) 且未註冊 listener,無影響)。
        return true
    }

    // Override of ContentProvider.call(String, String, Bundle). method is
    // @NonNull in the SDK, so the override parameter is non-null; the null
    // case is handled inside guardedPolicyCall (defensive, unreachable from
    // the framework which always passes "SendBinder"). The wiring is locked by
    // GuardXposedProviderTest.callWiring_* (fake uid providers + recording
    // delegate); the null case is locked by guardedPolicyCall's own test.
    // Phase 4: 外層整體 try/catch -> null (含 dispatch 拋、uid provider 拋、
    // 時鐘拋);入口先做 per-uid 速率門控,超限回 null。
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        try {
            val callingUid = try {
                callingUidProvider()
            } catch (_: Throwable) {
                return null
            }
            val myUid = try {
                myUidProvider()
            } catch (_: Throwable) {
                return null
            }
            // 速率門控先於信任門控:洪水 uid 直接回 null,不耗委派。
            if (isRateLimited(callingUid)) return null
            // 信任門控拒絕時 rate-limited 診斷：只記 uid 類別（見
            // uidRejectionCategory），絕不記 binder 內容/arg/extras；沿用
            // unexpectedArgs 8 次封頂模式（見 shouldLogRejection），防 log 洪水。
            if (!isTrustedXposedCaller(callingUid, myUid)) {
                if (shouldLogRejection()) {
                    try {
                        onRejectLog(
                            "GuardXposedProvider reject " +
                                "uidCategory=${uidRejectionCategory(callingUid, myUid)}",
                        )
                    } catch (_: Throwable) {
                        // Best effort: 診斷不得影響門控。
                    }
                }
                return null
            }
            return guardedPolicyCall(method, arg, extras, callingUid, myUid) { m, a, e ->
                dispatch(m, a, e)
            }
        } catch (_: Throwable) {
            return null
        }
    }

    // 拒絕診斷速率門：每行程至多前 N 次拒絕記一筆（與 WhitelistPolicy 的
    // unexpectedArgs 8 次封頂同模式），後續靜默計數不記 log，防惡意 app
    // 洪水刷爆 log。計數本身經 rejectionLogCount() 可觀測（測試鎖定）。
    internal fun shouldLogRejection(): Boolean =
        rejectionLogs.incrementAndGet() <= MAX_REJECT_LOGS

    // 已發生的拒絕診斷筆數（單調計數，含被封頂靜默者；供測試鎖定不爆 log）。
    internal fun rejectionLogCount(): Int = rejectionLogs.get()

    // CRUD 在函式庫為 inert (null/0),直接回 null/0,不委派。
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        // 固定窗口 1s,每 uid 上限 20 次 call。正常握手遠低於此,洪水則節流。
        internal const val RATE_WINDOW_MS = 1_000L
        internal const val RATE_LIMIT_MAX = 20
        // 拒絕診斷封頂（與 WhitelistPolicy unexpectedArgs 同 8 次模式）。
        internal const val MAX_REJECT_LOGS = 8
        // 速率窗口表有界上限（見 rateWindows 註解）。
        internal const val RATE_WINDOWS_MAX = 512
    }
}

// 信任邊界 (純函數,可 JVM 測):僅 root(0)/system(1000) 或本 app 自己
// (uid == myUid) 受信任。Phase 4 收緊:拒絕 shell(2000) 與其他 1000..9999
// 特權 uid (舊碼信任整個 < FIRST_APPLICATION_UID 範圍,含 shell)。
// 假設:LSPosed daemon 以 root/system 執行,其 callingUid 由 kernel 設定、
// 一般 app 無法偽造。
// TODO(on-device, DEVICE-UNVERIFIED):JVM 無法補真機證據，須以真機驗證：
//  1) LSPosed daemon 經 SendBinder 進本 provider 時 Binder.getCallingUid()
//     實際值是否恆為 0/1000（若為其他特權 uid，需回調此 allowlist）；
//  2) 多用戶/工作資料夾下同包名不同 user 的 callingUid 行为（myUid 比對
//     是否仍正確，有無 userId 高位混入 uid 比較的坑）；
//  3) 拒絕分支在真機 logcat 的 uidCategory 分布（shell/app/privileged
//     是否與預期一致，且無 binder 內容洩漏）。
// 驗證前此收緊視為 DEVICE-UNVERIFIED，但維持 fail-closed（寧可誤拒第三方
//  su 實作，也不放行 shell/未知特權 uid），不回退舊寬鬆 allowlist。
// 一般 app uid 一律拒絕;負數 uid 亦拒絕 (Binder.getCallingUid 正常不回負值,
// 但純函數不得因漏掉下界而讓 -1 落在信任側)。
internal fun isTrustedXposedCaller(callingUid: Int, myUid: Int): Boolean {
    if (callingUid < 0) return false
    if (callingUid == 0 || callingUid == 1000) return true
    if (callingUid == myUid) return true
    val callerUser = callingUid / 100_000
    val myUser = myUid / 100_000
    val appId = callingUid % 100_000
    return callerUser == myUser && appId == 1000
}

// 拒絕診斷的 uid 類別 (純函數,可 JVM 測)：只記粗類別，絕不記 binder 內容、
// arg、extras 或包名。shell 單列（2000 為 adb/shell 高風險）；其餘 0..9999
// 特權 uid 歸 privileged；負數歸 negative；其餘一律 app（含一般 app 與
// FIRST_APPLICATION_UID 邊界）。多用戶（secondary user / work profile）下
// UID 高位帶有 userId * 100_000，取 appId = callingUid % 100_000 正規化。
// self 不會走到拒絕分支（受信），故無 self 類別。
internal fun uidRejectionCategory(callingUid: Int, myUid: Int): String {
    if (callingUid < 0) return "negative"
    val appId = callingUid % 100_000
    return when {
        appId == 2000 -> "shell"
        appId in 0..9999 && callingUid != myUid -> "privileged"
        else -> "app"
    }
}

// call 的門控＋委派決策 (純函數,可 JVM 測):method 為 null 或 callingUid
// 不受信任時直接回 null,絕不委派 (避免一般 app 經 SendBinder 交付假 binder);
// 信任時以原 method/arg/extras 委派函式庫 provider。Binder.getCallingUid()/
// Process.myUid() 由呼叫端 (GuardXposedProvider.call) 取值後傳入,確保純函數
// 不依賴 Android framework。委派拋出 (含 Error) 一律轉 null (fail-closed,
// Guard 即邊界);速率門控不在此純函數內 (有狀態+時鐘,由 call 入口負責)。
internal fun guardedPolicyCall(
    method: String?,
    arg: String?,
    extras: Bundle?,
    callingUid: Int,
    myUid: Int,
    delegate: (String, String?, Bundle?) -> Bundle?,
): Bundle? {
    // method 為 null 時不解引用 (避免 Kotlin intrinsic NPE),直接回 null。
    if (method == null) return null
    // Only the LSPosed binder-delivery method is ever delegated; any other
    // method name is rejected even for trusted UIDs (defense in depth).
    if (method != "SendBinder") return null
    // 只信任 root(0)/system(1000) 或本 app;其餘 (含 shell 2000 與一般 app)
    // 不得交付 binder。
    if (!isTrustedXposedCaller(callingUid, myUid)) return null
    return try {
        delegate(method, arg, extras)
    } catch (_: Throwable) {
        null
    }
}
