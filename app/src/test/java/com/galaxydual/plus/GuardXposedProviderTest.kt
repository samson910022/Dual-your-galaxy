// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1):GuardXposedProvider 信任邊界 JVM 測。
// 測 isTrustedXposedCaller 與 guardedPolicyCall 純函數 (不實例化
// GuardXposedProvider);另以注入 callingUidProvider/myUidProvider/
// delegateCall 的接線測實例化 call (ContentProvider 建構子為 no-op、call
// 不使用 getContext(),故 JVM 安全),Cover root/system/shell 等特權 uid 與
// 本 app uid 放行,一般 app uid 拒絕,以及 call 的門控＋委派決策。
package com.galaxydual.plus

import android.os.Bundle
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardXposedProviderTest {
    private val myUid = 10123

    @Test
    fun rootUidAllowed() {
        assertTrue(isTrustedXposedCaller(0, myUid))
    }

    @Test
    fun systemUidAllowed() {
        assertTrue(isTrustedXposedCaller(1000, myUid))
    }

    // Phase 4 收緊:shell (2000) 不再受信任 (僅 root 0/system 1000/self)。
    // 舊碼信任整個 < FIRST_APPLICATION_UID 範圍 (含 shell);現拒絕。
    // TODO(on-device):驗證 LSPosed daemon 實際 uid,若非 0/1000 需回調。
    @Test
    fun shellUidRejectedAfterPhase4Tightening() {
        assertFalse(isTrustedXposedCaller(2000, myUid))
    }

    @Test
    fun foreignAppUidRejected() {
        assertFalse(isTrustedXposedCaller(10124, myUid))
    }

    @Test
    fun ownUidAllowed() {
        assertTrue(isTrustedXposedCaller(myUid, myUid))
    }

    // Phase 4 邊界:僅 0/1000/self 受信;9999/10000/10001 一律拒絕
    // (舊碼 9999 曾受信,現拒絕)。
    @Test
    fun boundaryAroundFirstApplicationUid() {
        assertFalse(isTrustedXposedCaller(Process.FIRST_APPLICATION_UID - 1, myUid))
        assertFalse(isTrustedXposedCaller(Process.FIRST_APPLICATION_UID, myUid))
        assertFalse(isTrustedXposedCaller(Process.FIRST_APPLICATION_UID + 1, myUid))
    }

    // 其他 1000..9999 特權 uid (如 9999) 亦拒絕,除非恰為 self。
    @Test
    fun otherPrivilegedUidsRejected() {
        assertFalse(isTrustedXposedCaller(9999, myUid))
        assertFalse(isTrustedXposedCaller(1001, myUid))
        assertFalse(isTrustedXposedCaller(5000, myUid))
    }

    // 負數 uid 不得落進 < FIRST_APPLICATION_UID 的信任側。
    @Test
    fun negativeUidRejected() {
        assertFalse(isTrustedXposedCaller(-1, myUid))
    }

    // ---- guardedPolicyCall:門控＋委派決策 (純函數,不實例化 provider) ----

    // 記錄式假 delegate:登記呼叫次數與傳入值,可指定回傳結果。
    private class RecordingDelegate : (String, String?, Bundle?) -> Bundle? {
        var calls = 0
            private set
        var lastMethod: String? = null
            private set
        var lastArg: String? = null
            private set
        var lastExtras: Bundle? = null
            private set
        var result: Bundle? = null

        override fun invoke(method: String, arg: String?, extras: Bundle?): Bundle? {
            calls++
            lastMethod = method
            lastArg = arg
            lastExtras = extras
            return result
        }
    }

    // method 為 null:不解引用、不委派,直接回 null。
    @Test
    fun nullMethodReturnsNullWithoutDelegating() {
        val delegate = RecordingDelegate()
        assertNull(guardedPolicyCall(null, "arg", Bundle(), 0, myUid, delegate))
        assertEquals(0, delegate.calls)
    }

    // 一般 app uid:gate 擋下,不委派。
    @Test
    fun untrustedUidReturnsNullWithoutDelegating() {
        val delegate = RecordingDelegate()
        assertNull(guardedPolicyCall("SendBinder", "arg", Bundle(), 10124, myUid, delegate))
        assertEquals(0, delegate.calls)
    }

    // 信任 uid (root 0 或本 app):以原 method/arg/extras 委派。
    @Test
    fun trustedUidDelegatesWithSameArgs() {
        val delegate = RecordingDelegate()
        val extras = Bundle()
        val result = Bundle()
        delegate.result = result

        assertSame(result, guardedPolicyCall("SendBinder", "arg", extras, 0, myUid, delegate))
        assertEquals(1, delegate.calls)
        assertEquals("SendBinder", delegate.lastMethod)
        assertEquals("arg", delegate.lastArg)
        assertSame(extras, delegate.lastExtras)

        // 本 app uid 亦受信任。
        assertSame(result, guardedPolicyCall("SendBinder", "arg", extras, myUid, myUid, delegate))
        assertEquals(2, delegate.calls)
    }

    // 邊界:FIRST_APPLICATION_UID 本身不得交付 binder。
    @Test
    fun firstApplicationUidBoundaryIsRejected() {
        val delegate = RecordingDelegate()
        assertNull(
            guardedPolicyCall(
                "SendBinder",
                "arg",
                Bundle(),
                Process.FIRST_APPLICATION_UID,
                myUid,
                delegate,
            ),
        )
        assertEquals(0, delegate.calls)
    }

    // ---- GuardXposedProvider.call 接線 ----
    // 注入 callingUidProvider/myUidProvider/delegateCall,不碰 Binder/Process/
    // XposedProvider;ContentProvider 建構子為 no-op、call 不使用 getContext(),
    // 故可安全在 JVM 測實例化。

    // 記錄式假 delegateCall:登記呼叫次數與傳入值,可指定回傳結果。
    private class RecordingDelegateCall : (String, String?, Bundle?) -> Bundle? {
        var calls = 0
            private set
        var lastMethod: String? = null
            private set
        var lastArg: String? = null
            private set
        var lastExtras: Bundle? = null
            private set
        var result: Bundle? = null

        override fun invoke(method: String, arg: String?, extras: Bundle?): Bundle? {
            calls++
            lastMethod = method
            lastArg = arg
            lastExtras = extras
            return result
        }
    }

    // 以注入的 uid/delegate 建立 provider (myUid 固定 10123)。
    private fun provider(
        callingUid: Int,
        delegateCall: RecordingDelegateCall,
    ): GuardXposedProvider = GuardXposedProvider(
        callingUidProvider = { callingUid },
        myUidProvider = { myUid },
        delegateCall = delegateCall,
    )

    // 一般 app uid:gate 擋下,不委派。
    @Test
    fun callRejectsUntrustedUidWithoutDelegating() {
        val delegateCall = RecordingDelegateCall()
        assertNull(provider(10124, delegateCall).call("SendBinder", null, Bundle()))
        assertEquals(0, delegateCall.calls)
    }

    // 信任 uid (root 0):以原 method/arg/extras 委派。
    @Test
    fun callDelegatesForTrustedRootUid() {
        val delegateCall = RecordingDelegateCall()
        val result = Bundle()
        delegateCall.result = result
        val extras = Bundle()

        assertSame(result, provider(0, delegateCall).call("SendBinder", null, extras))
        assertEquals(1, delegateCall.calls)
        assertEquals("SendBinder", delegateCall.lastMethod)
        assertNull(delegateCall.lastArg)
        assertSame(extras, delegateCall.lastExtras)
    }

    // 本 app uid 亦受信任。
    @Test
    fun callDelegatesForTrustedSelfUid() {
        val delegateCall = RecordingDelegateCall()
        val result = Bundle()
        delegateCall.result = result

        assertSame(result, provider(myUid, delegateCall).call("SendBinder", "arg", Bundle()))
        assertEquals(1, delegateCall.calls)
        assertEquals("SendBinder", delegateCall.lastMethod)
        assertEquals("arg", delegateCall.lastArg)
    }

    // null method: covered by guardedPolicyCall's `nullMethodReturnsNullWithoutDelegating`
    // (the framework always passes a non-null method, so the instance-level
    // call(method: String) cannot receive null; the guard is retained inside
    // guardedPolicyCall for defense-in-depth and unit-tested there).

    // Method allowlist: a trusted UID invoking any method other than SendBinder
    // must be rejected without delegating.
    @Test
    fun trustedUidNonSendBinderMethodReturnsNullWithoutDelegating() {
        val delegate = RecordingDelegate()
        assertNull(guardedPolicyCall("Query", "arg", Bundle(), 0, myUid, delegate))
        assertEquals(0, delegate.calls)
    }

    @Test
    fun callRejectsNonSendBinderMethodEvenForTrustedUid() {
        val delegateCall = RecordingDelegateCall()
        assertNull(provider(0, delegateCall).call("Query", null, Bundle()))
        assertEquals(0, delegateCall.calls)
    }

    // Phase 4:delegate 拋出 (含 Error) 轉 null,不委派異常 (Guard 即邊界)。
    @Test
    fun delegateThrowingReturnsNullFailClosed() {
        assertNull(
            guardedPolicyCall("SendBinder", "arg", Bundle(), 0, myUid) { _, _, _ ->
                throw RuntimeException("boom")
            },
        )
        assertNull(
            guardedPolicyCall("SendBinder", "arg", Bundle(), 0, myUid) { _, _, _ ->
                throw AssertionError("boom")
            },
        )
    }

    @Test
    fun callDelegateThrowingReturnsNullFailClosed() {
        val throwing = GuardXposedProvider(
            callingUidProvider = { 0 },
            myUidProvider = { myUid },
            delegateCall = { _, _, _ -> throw RuntimeException("boom") },
        )
        assertNull(throwing.call("SendBinder", null, Bundle()))
        val throwingError = GuardXposedProvider(
            callingUidProvider = { 0 },
            myUidProvider = { myUid },
            delegateCall = { _, _, _ -> throw AssertionError("boom") },
        )
        assertNull(throwingError.call("SendBinder", null, Bundle()))
    }

    // shell 經 call 入口亦拒絕 (不委派)。
    @Test
    fun callRejectsShellUid() {
        val delegateCall = RecordingDelegateCall()
        assertNull(provider(2000, delegateCall).call("SendBinder", null, Bundle()))
        assertEquals(0, delegateCall.calls)
    }

    // Phase 4:per-uid token-bucket 超限回 null (同 uid 20/s 內第 21 次拒絕,
    // 不委派;窗口推進後恢復)。
    @Test
    fun rateLimitExceededReturnsNull() {
        var now = 0L
        val delegateCall = RecordingDelegateCall()
        delegateCall.result = Bundle()
        val limited = GuardXposedProvider(
            callingUidProvider = { 0 },
            myUidProvider = { myUid },
            delegateCall = delegateCall,
            nowMsProvider = { now },
        )
        repeat(GuardXposedProvider.RATE_LIMIT_MAX) {
            assertSame(
                delegateCall.result,
                limited.call("SendBinder", null, Bundle()),
            )
        }
        assertEquals(GuardXposedProvider.RATE_LIMIT_MAX, delegateCall.calls)
        // 超限:回 null,不委派。
        assertNull(limited.call("SendBinder", null, Bundle()))
        assertEquals(GuardXposedProvider.RATE_LIMIT_MAX, delegateCall.calls)
        // 窗口推進後恢復。
        now += GuardXposedProvider.RATE_WINDOW_MS
        assertSame(delegateCall.result, limited.call("SendBinder", null, Bundle()))
        assertEquals(GuardXposedProvider.RATE_LIMIT_MAX + 1, delegateCall.calls)
    }

    // 不同 uid 限流隔離:uid A 超限不影響 uid B。
    @Test
    fun rateLimitIsPerUid() {
        var now = 0L
        var callingUid = 0
        val delegateCall = RecordingDelegateCall()
        delegateCall.result = Bundle()
        val provider = GuardXposedProvider(
            callingUidProvider = { callingUid },
            myUidProvider = { myUid },
            delegateCall = delegateCall,
            nowMsProvider = { now },
        )
        repeat(GuardXposedProvider.RATE_LIMIT_MAX) {
            callingUid = 0
            provider.call("SendBinder", null, Bundle())
        }
        // uid 0 已滿,uid 1000 仍可通過 (system 受信)。
        callingUid = 1000
        assertSame(delegateCall.result, provider.call("SendBinder", null, Bundle()))
    }

    // 拒絕診斷 rate-limited：只記 uid 類別（不記 binder 內容/arg/extras），
    // 20 次拒絕僅前 8 筆記 log（與 unexpectedArgs 封頂同模式），不爆 log 洪水。
    @Test
    fun rejectionDiagnosticsAreRateLimitedToEight() {
        val logs = mutableListOf<String>()
        val delegateCall = RecordingDelegateCall()
        val provider = GuardXposedProvider(
            callingUidProvider = { 10124 },
            myUidProvider = { myUid },
            delegateCall = delegateCall,
            onRejectLog = { logs.add(it) },
        )
        repeat(20) {
            assertNull(provider.call("SendBinder", "secret-arg", Bundle()))
        }
        assertEquals(0, delegateCall.calls)
        assertEquals(GuardXposedProvider.MAX_REJECT_LOGS, logs.size)
        assertEquals(20, provider.rejectionLogCount())
        for (line in logs) {
            assertTrue("reject log must carry uidCategory, was: $line", line.contains("uidCategory=app"))
            assertFalse("reject log must not leak binder arg, was: $line", line.contains("secret-arg"))
        }
    }

    // 拒絕類別純函數：shell/privileged/negative/app 四類可辨，且不含任何內容。
    @Test
    fun rejectionCategoryCoversUidClasses() {
        assertEquals("shell", uidRejectionCategory(2000, myUid))
        assertEquals("privileged", uidRejectionCategory(9999, myUid))
        assertEquals("privileged", uidRejectionCategory(1001, myUid))
        assertEquals("negative", uidRejectionCategory(-1, myUid))
        assertEquals("app", uidRejectionCategory(10124, myUid))
    }

    // 速率窗口表有界：512 上限下大量不同 uid 不無限增長（驅逐舊鍵）。
    @Test
    fun rateWindowsAreBounded() {
        var now = 0L
        var callingUid = 10000
        val delegateCall = RecordingDelegateCall()
        val provider = GuardXposedProvider(
            callingUidProvider = { callingUid },
            myUidProvider = { myUid },
            delegateCall = delegateCall,
            nowMsProvider = { now },
        )
        repeat(GuardXposedProvider.RATE_WINDOWS_MAX + 100) {
            callingUid = 10000 + it
            // 非受信 uid：先過速率門控再被信任門控拒絕；重點是窗口表不爆炸。
            provider.call("SendBinder", null, Bundle())
        }
        // 內部表經反射讀取大小（僅測試可見性不斷言實作細節以外的行為）。
        val field = GuardXposedProvider::class.java.getDeclaredField("rateWindows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(provider) as Map<Int, *>
        assertTrue("rateWindows must stay bounded, was ${map.size}", map.size <= GuardXposedProvider.RATE_WINDOWS_MAX)
    }
}
