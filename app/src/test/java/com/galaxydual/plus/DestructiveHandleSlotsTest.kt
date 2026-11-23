// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-74 R4-5 (E05)：破壞性對話框 handle 的「完整四欄 cleanup contract」。
// JVM-only：以假 handle 注入 dismiss lambda，驗證四個 slots 全數 dismiss 一次並回 null，
// 單一欄位 throw 不阻斷後續（包含新增的 preClearProbeFailed slot）。
// 這裡證明的是 cleanup 契約，不是 Android window/token 行為（見下方 MATRIX）。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestructiveHandleSlotsTest {

    private class FakeDialogHandle(
        var dismissCount: Int = 0,
        val throwOnDismiss: Boolean = false,
    )

    @Test
    fun releaseAll_fourFieldsNulled_eachDismissedOnce() {
        val slots = DestructiveHandleSlots<FakeDialogHandle>()
        val warning = FakeDialogHandle()
        val root = FakeDialogHandle()
        val verify = FakeDialogHandle()
        val probeFailed = FakeDialogHandle()
        slots.preClearWarning = warning
        slots.rootConfirm = root
        slots.uninstallVerify = verify
        slots.preClearProbeFailed = probeFailed

        dismissAllDestructiveHandles(slots) { it.dismissCount++ }

        assertEquals(1, warning.dismissCount)
        assertEquals(1, root.dismissCount)
        assertEquals(1, verify.dismissCount)
        assertEquals(1, probeFailed.dismissCount)
        assertNull(slots.preClearWarning)
        assertNull(slots.rootConfirm)
        assertNull(slots.uninstallVerify)
        assertNull(slots.preClearProbeFailed)
    }

    @Test
    fun releaseAll_continuesAfterThrow() {
        val slots = DestructiveHandleSlots<FakeDialogHandle>()
        val warning = FakeDialogHandle()
        val root = FakeDialogHandle(throwOnDismiss = true) // BadToken-like failure
        val verify = FakeDialogHandle()
        val probeFailed = FakeDialogHandle()
        slots.preClearWarning = warning
        slots.rootConfirm = root
        slots.uninstallVerify = verify
        slots.preClearProbeFailed = probeFailed

        // throw 被吞掉；呼叫端觀察不到例外，且後面兩欄仍舊釋放。
        dismissAllDestructiveHandles(slots) {
            it.dismissCount++
            if (it.throwOnDismiss) throw IllegalStateException("BadTokenException: dying Activity")
        }

        assertEquals(1, warning.dismissCount)
        assertEquals(1, root.dismissCount)
        assertEquals(1, verify.dismissCount)
        assertEquals(1, probeFailed.dismissCount)
        assertNull(slots.preClearWarning)
        assertNull(slots.rootConfirm)
        assertNull(slots.uninstallVerify)
        assertNull(slots.preClearProbeFailed)
    }

    @Test
    fun probeFailedSlot_isPartOfFullCleanup() {
        val slots = DestructiveHandleSlots<FakeDialogHandle>()
        val probeFailed = FakeDialogHandle()
        slots.preClearProbeFailed = probeFailed

        dismissAllDestructiveHandles(slots) { it.dismissCount++ }

        assertEquals(1, probeFailed.dismissCount)
        assertNull(slots.preClearProbeFailed)
        assertNull(slots.preClearWarning)
        assertNull(slots.rootConfirm)
        assertNull(slots.uninstallVerify)
    }
}

// ===== MANUAL MATRIX (DEVICE-UNVERIFIED / needs instrumentation; NOT PASS) =====
// PR CI skips the emulator matrix; the JVM tests above prove only the cleanup
// contract (fake handles: dismiss once + null + continue after throw). They do
// NOT prove anything about real android.app.AlertDialog windows or tokens.
// The leak being fixed on a real device is NOT VERIFIED by this suite — status
// is NOT PASS for the matrix below until an instrumentation run records it.
//
// 1. Real AlertDialog show()/dismiss(): showPreClearProbeFailedWarning stores the
//    show() return value in destructiveDialogHandles.preClearProbeFailed; after
//    onDestroy the stored window is dismissed (and the field nulled). Verify no
//    "View not attached to window manager"/BadTokenException in logcat, and that
//    dismiss() is idempotent on an already-dismissed dialog.
// 2. Rotation: trigger the probe-failed warning (unknown snapshot and/or probe
//    Failed) -> rotate while it is on screen -> old Activity window gone, new
//    Activity can re-show its own warning, no duplicate/stale dialog.
// 3. onDestroy: back/finish while the warning is up; confirm onCancel is NOT
//    invoked by our cleanup path (we call dismiss(), never cancel()) and that
//    pendingClearConfirm is reset by the Activity's own teardown path.
// 4. Background switch: home button / app switch while the warning is up, then
//    return; no leaked window from the backgrounded instance.
// 5. host.isFinishing / host.isDestroyed: if either is true at call time,
//    showPreClearProbeFailedWarning takes the early onCancel branch and must
//    not create a window or write the slot; verify no late dialog appears.
// 6. Re-trigger after rebuild: after rotation/Activity rebuild, retry then
//    re-probe to fail again -> a fresh dialog is shown and tracked in the NEW
//    Activity's slots (old slot was nulled), not suppressed by stale state.
