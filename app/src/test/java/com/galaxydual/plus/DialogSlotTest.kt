// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #89 Phase D2b (#78)：DialogSlot 的框架無關行為契約（JVM，無 Robolectric）。
// 以假 handle 直接驅動 slot：null 不覆寫、single-flight 擋下第二個 handle、
// identity guard、clear 先清空再 best-effort dismiss，以及所有 lambda 擲錯都被吞掉；
// 另驗證 dismiss callback 從另一條執行緒送達時 identity guard 仍生效，並以 latch
// handshake 確定 interleave：install 持有 monitor 時，過期 callback 不得進入。
// DialogSlot 沒有任何 Android 型別，這裡測的就是 Activity 端綁定以外的全部語意。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DialogSlotTest {

    private class FakeHandle(var showing: Boolean = true) {
        var dismissCount: Int = 0
        var onDismiss: (() -> Unit)? = null

        fun dismiss() {
            dismissCount++
            showing = false
            onDismiss?.invoke()
        }
    }

    private fun DialogSlot<FakeHandle>.installHandle(
        candidate: FakeHandle?,
        isShowing: (FakeHandle) -> Boolean = { true },
        bindDismiss: (FakeHandle, () -> Unit) -> Unit = { handle, cb -> handle.onDismiss = cb },
        discard: (FakeHandle) -> Unit = { it.dismiss() },
    ): Boolean = install(candidate, isShowing, bindDismiss, discard)

    @Test
    fun nullCandidateNeverOverwritesValidHandle() {
        val slot = DialogSlot<FakeHandle>()
        val live = FakeHandle()

        assertTrue(slot.installHandle(live))
        assertFalse("show() failure (null) must be rejected", slot.installHandle(null))
        assertSame("a null candidate must not discard a live handle", live, slot.current)
        assertEquals(0, live.dismissCount)

        // 空 slot 遇到 null 也必須保持空，且不得呼叫任何 dismiss。
        val empty = DialogSlot<FakeHandle>()
        assertFalse(empty.installHandle(null))
        assertNull(empty.current)
    }

    @Test
    fun showingHandleBlocksSecondInstallAndDiscardsCandidate() {
        val slot = DialogSlot<FakeHandle>()
        val first = FakeHandle(showing = true)
        val second = FakeHandle(showing = true)
        var isShowingQueriedWith: FakeHandle? = null

        assertTrue(slot.installHandle(first))
        val installed = slot.installHandle(
            second,
            isShowing = { isShowingQueriedWith = it; it.showing },
        )

        assertFalse("a showing handle must win single-flight", installed)
        assertSame(first, slot.current)
        assertSame("single-flight must consult the CURRENT handle", first, isShowingQueriedWith)
        assertEquals("the rejected candidate must be best-effort dismissed", 1, second.dismissCount)
        assertFalse(second.showing)
        assertEquals(0, first.dismissCount)
    }

    @Test
    fun dismissedHandleAllowsNewInstall() {
        val slot = DialogSlot<FakeHandle>()
        val first = FakeHandle(showing = false) // 已關閉：不再佔用 slot。
        val second = FakeHandle()

        assertTrue(slot.installHandle(first))
        assertTrue(slot.installHandle(second, isShowing = { it.showing }))
        assertSame(second, slot.current)
        assertEquals("a dismissed old handle must not be dismissed again", 0, first.dismissCount)
    }

    @Test
    fun oldDismissCallbackCannotClearNewerHandle() {
        val slot = DialogSlot<FakeHandle>()
        val first = FakeHandle(showing = false)
        val second = FakeHandle()

        assertTrue(slot.installHandle(first))
        val firstCallback = first.onDismiss!!
        assertTrue(slot.installHandle(second, isShowing = { it.showing }))
        val secondCallback = second.onDismiss!!

        firstCallback()
        assertSame("a stale dismiss callback must not clear the newer handle", second, slot.current)

        secondCallback()
        assertNull("the current handle's dismiss callback must clear the slot", slot.current)
    }

    @Test
    fun dismissCallbacksFromAnotherThread_respectIdentityGuard() {
        val slot = DialogSlot<FakeHandle>()
        val first = FakeHandle(showing = false)
        val second = FakeHandle()
        assertTrue(slot.installHandle(first))
        val firstCallback = first.onDismiss!!
        assertTrue(slot.installHandle(second, isShowing = { it.showing }))
        val secondCallback = second.onDismiss!!

        invokeOnAnotherThread(firstCallback)
        assertSame("a stale cross-thread callback must not clear the newer handle", second, slot.current)

        invokeOnAnotherThread(secondCallback)
        assertNull("a cross-thread callback for the current handle must clear the slot", slot.current)
    }

    private fun invokeOnAnotherThread(callback: () -> Unit) {
        val thread = trackedThread("dismiss-callback", callback)
        thread.start()
        thread.join(5_000)
        assertFalse("dismiss callback thread must finish", thread.isAlive)
        assertNull("dismiss callback thread must not throw", thread.uncaught)
    }

    /**
     * 把 body 丟到有名字的執行緒，並把 Thread.UncaughtExceptionHandler 收到的
     * Throwable 保存下來供 join 後斷言；避免 callback 在背景安靜地失敗。
     */
    private class TrackedThread(name: String, body: () -> Unit) {
        @Volatile
        var uncaught: Throwable? = null
            private set

        private val thread = Thread {
            try {
                body()
            } catch (t: Throwable) {
                uncaught = t
            }
        }

        init {
            thread.name = name
            thread.setUncaughtExceptionHandler { _, t -> uncaught = t }
        }

        fun start() = thread.start()
        fun join(timeoutMillis: Long) = thread.join(timeoutMillis)
        val isAlive: Boolean get() = thread.isAlive
    }

    private fun trackedThread(name: String, body: () -> Unit) = TrackedThread(name, body)

    @Test
    fun staleDismissCallbackCannotEnterWhileInstallHoldsMonitor() {
        val slot = DialogSlot<FakeHandle>()
        val first = FakeHandle(showing = false)
        val second = FakeHandle(showing = true)
        assertTrue(slot.installHandle(first))

        // 這是 first 的 callback；second 已成為 current，所以它是過期 callback。
        val staleCallback = first.onDismiss!!
        assertTrue(slot.installHandle(second, isShowing = { it.showing }))
        assertSame(second, slot.current)

        val installEntered = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val installDone = CountDownLatch(1)
        var installSawRelease = false
        val third = FakeHandle()

        // install 是 @Synchronized；isShowing(existing) 在其 monitor 之下被呼叫，
        // 所以 installEntered.countDown() 就等於「monitor 正被 install 持有」的信號。
        val installThread = trackedThread("dialog-slot-install") {
            try {
                slot.installHandle(third, isShowing = {
                    installEntered.countDown()
                    installSawRelease = releaseInstall.await(10_000, TimeUnit.MILLISECONDS)
                    it.showing // single-flight：third 會被擋下並 discard。
                })
            } finally {
                installDone.countDown()
            }
        }
        installThread.start()
        assertTrue("install must reach isShowing under the monitor", installEntered.await(10_000, TimeUnit.MILLISECONDS))

        // 另一條執行緒送達過期 callback；先確認它真的啟動，避免 thread-start race。
        val callbackStarted = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        val callbackThread = trackedThread("stale-dismiss-callback") {
            callbackStarted.countDown()
            try {
                staleCallback()
            } finally {
                callbackDone.countDown()
            }
        }
        callbackThread.start()
        assertTrue("stale callback thread must start", callbackStarted.await(10_000, TimeUnit.MILLISECONDS))

        // 關鍵斷言：monitor 仍被 install 持有時，@Synchronized onDismissed 不得進入。
        // 拿掉該註解，callback 會立刻完成，這裡就 fail（300ms 只是負向觀察視窗，
        // 不是 pass/fail 所需的 sleep；通過/失敗完全由 latch handshake 決定）。
        assertFalse(
            "stale dismiss callback must not enter while install holds the monitor",
            callbackDone.await(300, TimeUnit.MILLISECONDS),
        )

        releaseInstall.countDown()
        assertTrue("install thread must finish", installDone.await(10_000, TimeUnit.MILLISECONDS))
        callbackThread.join(10_000)
        assertFalse("stale callback thread must finish after release", callbackThread.isAlive)
        assertTrue("callbackDone must signal after release", callbackDone.await(10_000, TimeUnit.MILLISECONDS))

        assertNull("stale callback must not throw", callbackThread.uncaught)
        assertNull("install must not throw", installThread.uncaught)
        assertTrue("install must have been released", installSawRelease)
        assertSame("a stale callback must not clear the current handle", second, slot.current)
    }

    @Test
    fun clearDismissesAndEmptiesSlot() {
        val slot = DialogSlot<FakeHandle>()
        val handle = FakeHandle()
        assertTrue(slot.installHandle(handle))

        slot.clear { it.dismiss() }
        assertNull(slot.current)
        assertEquals(1, handle.dismissCount)

        // onDestroy 情境：dismiss 拋出（BadTokenException / dying window）不得留下
        // handle，也不得阻斷同一個 onDestroy 後面的清理。
        val throwing = FakeHandle()
        assertTrue(slot.installHandle(throwing))
        slot.clear { throw IllegalStateException("BadTokenException: dying Activity") }
        assertNull("a throwing dismiss must not strand the handle", slot.current)
        assertEquals(0, throwing.dismissCount)

        var dismissCalls = 0
        slot.clear { dismissCalls++ }
        assertEquals("clear on an empty slot must not call dismiss", 0, dismissCalls)

        val reusable = FakeHandle()
        assertTrue("a cleared slot must accept a new handle", slot.installHandle(reusable))
        assertSame(reusable, slot.current)
    }

    @Test
    fun bindDismissFailureDoesNotInstall() {
        val slot = DialogSlot<FakeHandle>()
        val existing = FakeHandle(showing = false)
        val candidate = FakeHandle()
        var discarded = 0

        assertTrue(slot.installHandle(existing))
        val installed = slot.installHandle(
            candidate,
            isShowing = { it.showing },
            bindDismiss = { _, _ -> throw IllegalStateException("setOnDismissListener failed") },
            discard = { discarded++ },
        )

        assertFalse("an untracked handle must not be installed", installed)
        assertSame(existing, slot.current)
        assertEquals("failed candidate must be discarded", 1, discarded)
        assertEquals(0, candidate.dismissCount)

        val empty = DialogSlot<FakeHandle>()
        var emptyDiscarded = 0
        assertFalse(empty.installHandle(
            FakeHandle(),
            bindDismiss = { _, _ -> throw IllegalStateException("bind failed") },
            discard = { emptyDiscarded++ },
        ))
        assertNull(empty.current)
        assertEquals(1, emptyDiscarded)
    }

    @Test
    fun discardThrowable_duringSingleFlightIsSwallowed() {
        val slot = DialogSlot<FakeHandle>()
        val first = FakeHandle()
        val second = FakeHandle()
        assertTrue(slot.installHandle(first))

        val installed = slot.installHandle(
            second,
            discard = { throw IllegalStateException("dismissing rejected candidate failed") },
        )

        assertFalse(installed)
        assertSame(first, slot.current)
    }

    @Test
    fun isShowingThrowable_blocksInstallRatherThanOrphaning() {
        val slot = DialogSlot<FakeHandle>()
        val first = FakeHandle()
        val second = FakeHandle()
        assertTrue(slot.installHandle(first))

        val installed = slot.installHandle(
            second,
            isShowing = { throw IllegalStateException("unknown show state") },
        )

        assertFalse("unknown state must fail safe by keeping the current handle", installed)
        assertSame(first, slot.current)
        assertEquals(1, second.dismissCount)
    }
}

// ===== MANUAL MATRIX (DEVICE-UNVERIFIED / needs instrumentation; NOT PASS) =====
// 以下都沒有在 JVM 測試覆蓋：真實 android.app.AlertDialog 的 show()/isShowing()/
// dismiss()、Activity.onDestroy 的呼叫時機、視窗/token 是否真的外洩，都是
// DEVICE-UNVERIFIED。本檔只證明 framework-free slot 的狀態契約； Android 綁定
// （setOnDismissListener / isShowing / dismiss）的正確性仍須在裝置上驗證：
//
// 1. show() 失敗或 host.isFinishing/isDestroyed 時 helper 回 null，既有 handle 不變。
// 2. 第一個風險對話框還在畫面上時第二次點擊：候選被 discard，第一個仍可 dismiss。
// 3. onDestroy 時 slot.clear { dismiss() } 觸發 OnDismissListener，因已先清空而不遞迴，
//    且不會呼叫 cancel() → 不產生 onCancel 回呼。
// 4. 旋轉／回到前景：舊 Activity 的視窗消失，新 Activity 可重新裝入自己的 handle。
