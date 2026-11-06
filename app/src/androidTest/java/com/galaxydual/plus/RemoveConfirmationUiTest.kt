package com.galaxydual.plus

import android.app.AlertDialog
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import kotlinx.coroutines.flow.MutableStateFlow

@Suppress("DEPRECATION")
class RemoveConfirmationUiTest : InstrumentationTestCase() {
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun checkbox(view: View): CheckBox? {
        if (view is CheckBox) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            checkbox(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    fun testAcknowledgementDetailsBusyAndReplacement() {
        val activity = launchActivity(instrumentation.targetContext.packageName, MainActivity::class.java, null)
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val vm = activity.javaClass.getDeclaredMethod("getVm").apply { isAccessible = true }.invoke(activity) as DualViewModel
                @Suppress("UNCHECKED_CAST")
                val state = field(vm, "_state") as MutableStateFlow<UiState>
                val sync = activity.javaClass.getDeclaredMethod("syncRemoveDialog", PendingRemove::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                val old = PendingRemove("com.example.old", 95, 15, "Profile", 1)
                val replacement = PendingRemove("com.example.new", 95, 15, "Profile", 2)
                state.value = state.value.copy(pendingRemove = old, loading = false)
                sync.invoke(activity, old, false)
                val dialog = field(activity, "removeConfirmDialog") as AlertDialog
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val check = checkbox(dialog.window!!.decorView)!!
                assertFalse(positive.isEnabled)
                check.performClick()
                assertTrue(positive.isEnabled)
                sync.invoke(activity, old, true)
                assertFalse(positive.isEnabled)
                assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
                sync.invoke(activity, old, false)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                val details = field(activity, "removeLabelsDialog") as AlertDialog
                assertTrue(details.isShowing)
                details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                // Replace state before the collector can render: the old confirmed
                // dialog must not authorize or cancel the new generation.
                state.value = state.value.copy(pendingRemove = replacement, loading = false)
                positive.performClick()
                assertEquals(replacement, state.value.pendingRemove)
                sync.invoke(activity, replacement, false)
                val newDialog = field(activity, "removeConfirmDialog") as AlertDialog
                assertFalse(newDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertFalse(checkbox(newDialog.window!!.decorView)!!.isChecked)
                newDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertNull(state.value.pendingRemove)
            }
        } finally {
            activity.finish()
        }
    }
}
