// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1):module-app 側的 LSPosed remote preferences
// 通道。LSPosed 會為模組行程綁定 XposedService;此處持有 binder 並暴露
// remotePreferences(group),讓 app 寫入白名單政策草稿。service 可能缺席
// (模組未啟用/binder 尚未取得),所有呼叫端以 null 統一 fail-closed
// (見 remoteHookPolicyStore)。
package com.galaxydual.plus

import android.app.Application
import android.content.SharedPreferences
import android.os.Process
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

class GalaxyApp : Application(), XposedServiceHelper.OnServiceListener {

    // Stage G2：App-scoped PolicyTxCoordinator（跨 VM 最小 coordinator；
    // MainActivity factory 經 applicationContext 取用；VM onCleared 不清除）。
    val policyTxCoordinator = PolicyTxCoordinator()

    override fun onCreate() {
        super.onCreate()
        // S2: hook 只讀 user 0；非 owner 進程（如 work profile）即使註冊 binder
        // 也只會讀寫到非本人的草稿通道，永遠不會生效 —— 不註冊（fail-closed，
        // 也省一個跨進程監聽器）。uid 拆解失敗一律視為非 owner（fail-closed）。
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

    override fun onServiceBind(service: XposedService) {
        serviceRef.set(service)
        // 5.1.5:通知所有綁定監聽者 (例如刷新政策草稿)。
        notifyServiceListeners()
    }

    override fun onServiceDied(service: XposedService) {
        // 原子 CAS：只清同一 instance，避免新 binder 被舊的 death 回調清掉。
        if (serviceRef.compareAndSet(service, null)) {
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
