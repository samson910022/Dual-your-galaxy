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
        XposedServiceHelper.registerListener(this)
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
            } catch (_: Throwable) {
                // Best effort: listener must not break service bind.
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
        // Phase 4:包 try/catch -> null (fail-closed 內聚):binder 死亡/拋出
        // (含 Error) 不得越過此邊界,呼叫端統一視為無持久化草稿。
        // 與 HookPolicyStore 全鏈一致：CancellationException 透傳（協程取消
        // 不得被吞為缺席），其餘 Throwable（含 Error）才轉 null。
        fun remotePreferences(group: String): SharedPreferences? =
            try {
                serviceRef.get()?.getRemotePreferences(group)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }

        // 供呼叫端在訂閱後補發已綁定狀態 (避免 lost-event 競態)。
        fun isServiceConnected(): Boolean = serviceRef.get() != null
    }
}
