// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1):module-app 側的 LSPosed remote preferences
// 通道。LSPosed 會為模組行程綁定 XposedService;此處持有 binder 並暴露
// remotePreferences(group),讓 app 寫入白名單政策草稿。service 可能缺席
// (模組未啟用/binder 尚未取得),所有呼叫端以 null 統一 fail-closed
// (見 remoteHookPolicyStore)。
package com.galaxydual.plus

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import com.galaxydual.hook.PolicySource
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GalaxyApp : Application(), XposedServiceHelper.OnServiceListener {

    // Stage G2：App-scoped PolicyTxCoordinator（跨 VM 最小 coordinator；
    // MainActivity factory 經 applicationContext 取用；VM onCleared 不清除）。
    // PR #74 R6-2 (G02): MUST stay lazy — Application field initializers run before
    // attachBaseContext(), so getSharedPreferences() here would crash on device.
    // The repair-hint marker lives in app-private prefs (NOT dual_policy remote
    // prefs) and is seeded into the coordinator at construction, so a restarted
    // app process immediately warns again until explicit acknowledgement.
    // PR #89 Phase B (B3): failure to ACQUIRE the prefs must NOT degrade to a null
    // store — that would make persistRepairHint early-return and silently drop the
    // hint on every restart (fail-open). Inject the fail-closed stub instead: it
    // warns, reports unconfirmed saves, and never touches disk. A genuinely null
    // store remains only the explicit JVM/in-memory no-store mode.
    val policyTxCoordinator: PolicyTxCoordinator by lazy {
        // Single source of truth for the fail-closed acquisition decision
        // (JVM-testable in RepairHintStore.kt): null/throw -> fail-closed stub,
        // never a null store / in-memory-only coordinator.
        val hintStore = repairHintStoreOrFailClosed {
            getSharedPreferences(REPAIR_HINT_PREFS_NAME, Context.MODE_PRIVATE).repairHintStore()
        }
        PolicyTxCoordinator(repairHintStore = hintStore)
    }

    // PR #74 R3-1: app-process-local BBC toggle state (last KNOWN write outcome
    // + monotonic intent sequence). Lazy because the XposedService binder is not
    // bound yet in Application.onCreate(); first access reinitializes from the
    // PERSISTED preference via the same remote store seam MainActivity uses
    // (default true on read failure). Dialog/Activity rebuilds MUST reuse this
    // instance. NOT persisted, NOT the hook process's bbcConfirmedOffAtMs, and
    // NOT a Samsung cold-start deletion guard (issue #75).
    internal val bbcToggleRuntimeState: BbcToggleRuntimeState by lazy {
        BbcToggleRuntimeState(
            initialConfirmed = remoteHookPolicyStore {
                remotePreferences(PolicySource.POLICY_PREFS_NAME)
            }.isBbcInjectionEnabled(),
        )
    }

    // PR #74 R3 D05: the ONE BBC write serializer for this app process. Built
    // once on the Application lifetime (NOT lifecycleScope) so a controller
    // rebuilt by a later dialog/Activity shares the same single consumer: an
    // old synchronous commit cannot be abandoned and land after a newer intent.
    // The scope is never cancelled by UI teardown; it ends with the process.
    // commit uses the SAME remote store seam MainActivity uses. NOT a Samsung
    // cold-start deletion guard (issue #75).
    internal val bbcToggleCoordinator: BbcToggleCoordinator by lazy {
        BbcToggleCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            state = bbcToggleRuntimeState,
            commit = { enabled ->
                remoteHookPolicyStore {
                    remotePreferences(PolicySource.POLICY_PREFS_NAME)
                }.setBbcInjectionEnabledDetailed(enabled)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // S2: hook 只讀 user 0；非 owner 進程（如 work profile）即使註冊 binder
        // 也只會讀寫到非本人的草稿通道，永遠不會生效 —— 不註冊（fail-closed，
        // 也省一個跨進程監聽器）。uid 拆解失敗一律視為非 owner（fail-closed）。
        // SEC-2: 這裡的 userIdFromUid 分解依賴 AOSP user-range shift
        // （userId*100_000 + appId，100_000 為 @hide 的 UserHandle.PER_USER_RANGE
        // 字面）。fail-closed 性質是方向依存的，並非無條件：shift 誤差下
        // 過大 shift 會把 / 100_000 分解縮退為 userId 0（owner 判定放寬給次級
        // user——fail-closed ではない）；過小 shift 則可能把 owner 誤判為非 owner
        // （可用性損失）。uid==0 與（trust gate 的）uid==myUid 等価路徑不受 shift
        // 影響。詳述與 DEVICE-UNVERIFIED 狀態（on-device assertion 保留中、非儀礼
        // 但書，不主張無條件 fail-closed）見
        // GuardXposedProvider.isTrustedXposedCaller 上的 SEC-2 コメント。
        val owner = try {
            userIdFromUid(Process.myUid()) == 0
        } catch (t: Throwable) {
            try {
                System.err.println("[GalaxyApp] owner-user check failed (${t.javaClass.name}); skipping XposedServiceHelper registration")
            } catch (_: Throwable) {
            }
            false
        }
        if (owner) {
            XposedServiceHelper.registerListener(this)
        }
    }

    // PR #90 P0: bind-time classification performs a BLOCKING binder commit.
    // Run it on the app's IO scope so it can never delay bind notification;
    // HookPolicyStore.saveRaw remains the correctness gate (it re-plans and
    // re-adds any pending intent before EVERY draft write).
    internal val bbcClassificationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onServiceBind(service: XposedService) {
        serviceRef.set(service)
        // P0 (PR #90): best-effort durable install classification on the App's
        // writable channel. A failed attempt must NOT latch the one-shot flag —
        // a later bind retries. saveRaw independently guarantees that no draft
        // is ever written without a durable classification.
        if (!installClassificationOnce.get()) {
            bbcClassificationScope.launch {
                val ok = try {
                    ensureBbcInstallClassification { remotePreferences(PolicySource.POLICY_PREFS_NAME) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    false
                }
                // NoWrite (key already present) and a confirmed commit both
                // satisfy the bind-time responsibility.
                if (ok) installClassificationOnce.compareAndSet(false, true)
            }
        }
        // 5.1.5:通知所有綁定監聽者 (例如刷新政策草稿)。
        // Never blocked by the binder commit above.
        notifyServiceListeners()
    }

    override fun onServiceDied(service: XposedService) {
        // 原子 CAS：只清同一 instance，避免新 binder 被舊的 death 回調清掉。
        if (serviceRef.compareAndSet(service, null)) {
            // PR #90 P0: a rebound service exposes a DIFFERENT remote-prefs
            // instance. Drop the old intent and re-arm bind-time classification
            // so the new service cannot inherit stale process state.
            synchronized(versionCommitLock) {
                clearPendingBbcClassification()
                installClassificationOnce.set(false)
            }
            // 通知監聽者服務已斷線,讓呼叫端重新評估 (fail-closed)。
            notifyServiceListeners()
        }
    }

    private fun notifyServiceListeners() {
        for (listener in GalaxyApp.serviceBindListeners) {
            try {
                listener()
            } catch (e: Throwable) {
                // A3-11: 監聽器例外不得靜默吞掉 —— 單行 log（僅 tag＋異常類別名，
                // 絕不含 listener 內容／政策資料），並繼續通知其餘監聽者。
                try {
                    System.err.println("[GalaxyApp] service bind listener threw ${e.javaClass.name}; continuing")
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        private val serviceRef = AtomicReference<XposedService?>(null)

        // P0 (PR #90): latched only after a bind-time classification SUCCEEDS
        // (or is already present). Reset on active service death so a new
        // service/rebind retries; saveRaw owns the retry-before-write path.
        private val installClassificationOnce = AtomicBoolean(false)

        // 5.1.5:service 綁定監聽者 (CopyOnWriteArrayList 供併發安全)。
        // Phase 4:改 private + add/remove 封裝,避免外部直接 mutable 存取。
        private val serviceBindListeners = CopyOnWriteArrayList<() -> Unit>()

        fun addServiceBindListener(listener: () -> Unit) {
            serviceBindListeners.add(listener)
        }

        fun removeServiceBindListener(listener: () -> Unit) {
            serviceBindListeners.remove(listener)
        }

        // 讀/寫指定 group 的 remote preferences;service 缺席時回 null
        // (fail-closed:呼叫端視為無持久化草稿)。
        // S2: 非 owner 進程回 null（hook 只讀 user 0；非 owner 寫入不會被看到，
        // 故直接 fail-closed，與 GalaxyApp.onCreate 不註冊綁定同策）。
        // Phase 4:包 try/catch -> null (fail-closed 內聚):binder 死亡/拋出
        // (含 Error) 不得越過此邊界,呼叫端統一視為無持久化草稿。
        // 與 HookPolicyStore 全鏈一致：CancellationException 透傳（協程取消
        // 不得被吞為缺席），其餘 Throwable（含 Error）才轉 null。
        fun remotePreferences(group: String): SharedPreferences? {
            val owner = try {
                userIdFromUid(Process.myUid()) == 0
            } catch (_: Throwable) {
                // uid 拆解失敗 → fail-closed（視為非 owner）。
                false
            }
            if (!owner) return null
            return try {
                serviceRef.get()?.getRemotePreferences(group)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
        }

        // 供呼叫端在訂閱後補發已綁定狀態 (避免 lost-event 競態)。
        fun isServiceConnected(): Boolean = serviceRef.get() != null
    }
}
