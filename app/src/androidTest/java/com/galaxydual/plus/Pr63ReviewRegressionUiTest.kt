// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #63 review regression coverage (feasible instrumentation):
// - cloned-row removal control is an explicit Button (not Switch), with
//   stable remove:<pkg> tag/text/contentDescription and 48dp touch target;
//   clicking the enabled control sets pendingRemove (two-phase) without
//   immediate uninstall/write side effects.
// - About nav tab uses the info/about icon and About dialog contentDescription.
//
// Visual states (light/night disabled boundaries, 320dp/large-font layout,
// S24 screenshots) remain device/manual verification per PR review.
package com.galaxydual.plus

import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Pr63ReviewRegressionUiTest {

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun method(target: Any, name: String, vararg types: Class<*>): java.lang.reflect.Method =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun setVmState(vm: DualViewModel, transform: (UiState) -> UiState) {
        val state = field(vm, "_state") as MutableStateFlow<UiState>
        state.value = transform(state.value)
    }

    private fun findRowRemoveButtons(activity: MainActivity): List<Button> {
        val container = field(activity, "clonedAppsContainer") as? LinearLayout ?: return emptyList()
        val out = mutableListOf<Button>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                val child = row.getChildAt(j)
                if (child is Button && (child.tag as? String)?.startsWith("remove:") == true) {
                    out += child
                }
            }
        }
        return out
    }

    @Test
    fun clonedRow_removeButton_contractAndTwoPhaseClick() {
        val gw = FakeHarnessGateway(dualId = 95).apply {
            // requestRemove verifies installed state via the harness gateway;
            // without this, pm list returns NotInstalled and pendingRemove is never set.
            installedPkgs.addAll(listOf("com.example.a", "com.example.b"))
        }
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Ensure Dual Apps tab and inject two cloned rows via VM state.
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_DUAL_APPS, announce = false)
                }
                setVmState(vm) { it.copy(loading = false, cloned = listOf("com.example.a", "com.example.b")) }
                HarnessPoll.await(30_000L) {
                    var count = 0
                    scenario.onActivity { activity -> count = findRowRemoveButtons(activity).size }
                    count >= 2
                }
                scenario.onActivity { activity ->
                    val buttons = findRowRemoveButtons(activity).sortedBy { it.tag?.toString() }
                    assertEquals(2, buttons.size)
                    val expectedText = activity.getString(R.string.action_remove)
                    buttons.forEachIndexed { idx, b ->
                        val pkg = if (idx == 0) "com.example.a" else "com.example.b"
                        assertEquals("remove:$pkg", b.tag)
                        assertEquals(expectedText, b.text.toString())
                        assertNotNull(b.contentDescription)
                        val cd = b.contentDescription.toString()
                        assertTrue("CD should contain action", cd.contains(expectedText))
                        assertTrue("CD should contain pkg context", cd.contains("com.example.a") || cd.contains("com.example.b"))
                        assertTrue("touch target >= 48dp", b.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt())
                        assertTrue("touch target >= 48dp", b.minimumWidth >= (48 * activity.resources.displayMetrics.density).toInt())
                        assertEquals(1, b.maxLines)
                    }
                }
                // Same-key early return: busy toggle must sync row remove enabled
                // without rebuilding rows (buildClonedListKey excludes loading).
                var keyCount = 0
                scenario.onActivity { activity ->
                    keyCount = (field(activity, "clonedAppsContainer") as LinearLayout).childCount
                }
                setVmState(vm) { it.copy(loading = true) }
                HarnessPoll.await(30_000L) {
                    var disabled = false
                    scenario.onActivity { activity ->
                        val bs = findRowRemoveButtons(activity)
                        disabled = bs.size == 2 && bs.all { !it.isEnabled } &&
                            (field(activity, "clonedAppsContainer") as LinearLayout).childCount == keyCount
                    }
                    disabled
                }
                setVmState(vm) { it.copy(loading = false) }
                HarnessPoll.await(30_000L) {
                    var ok = false
                    scenario.onActivity { activity ->
                        val bs = findRowRemoveButtons(activity)
                        ok = bs.size == 2 && (field(activity, "clonedAppsContainer") as LinearLayout).childCount == keyCount
                    }
                    ok
                }

                // Async label rebind regression (remove button tag/type path) +
                // wiring: click requests removal (two-phase) with no immediate write.
                scenario.onActivity { activity ->
                    method(activity, "cacheLabel", String::class.java, String::class.java)
                        .invoke(activity, "com.example.a", "Example A")
                    method(activity, "rebindClonedLabel", String::class.java, String::class.java)
                        .invoke(activity, "com.example.a", "Example A")
                    val rebound = findRowRemoveButtons(activity).first { it.tag == "remove:com.example.a" }
                    assertTrue(rebound.contentDescription.toString().contains("Example A"))
                    // Enable for deterministic handler wiring; writability gating is
                    // separately enforced by VM/other tests.
                    rebound.isEnabled = true
                    val before = gw.uninstallCalls
                    rebound.performClick()
                    assertEquals("click must not uninstall immediately", before, gw.uninstallCalls)
                    assertEquals("click must not write immediately", 0, gw.writeCalls)
                }
                HarnessPoll.await(30_000L) { vm.state.value.pendingRemove?.pkg == "com.example.a" }
                assertEquals("com.example.a", vm.state.value.pendingRemove?.pkg)
            }
        } finally {
            vm.dispose()
            MainActivity.testViewModel = null
        }
    }

    @Test
    fun aboutTab_usesAboutIconAndContentDescription() {
        val gw = FakeHarnessGateway(dualId = 95)
        val vm = newHarnessViewModel(gw)
        try {
            awaitHarnessReady(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = field(activity, "navTabSettings") as LinearLayout
                    val expectedLabel = activity.getString(R.string.tab_settings)
                    val expectedCd = activity.getString(R.string.settings_tab_cd)
                    assertEquals(expectedCd, nav.contentDescription?.toString())
                    var sawLabel = false
                    var sawAboutIcon = false
                    fun walk(v: android.view.View) {
                        if (v is TextView && v.text?.toString() == expectedLabel) sawLabel = true
                        // ConstantState equality is flaky across tinted/wrapped vector
                        // drawables on API 29 emulators; createNavItem tags the icon with
                        // its source resource id for a stable identity check.
                        if (v is ImageView && (v.tag as? Int) == R.drawable.ic_nav_about) sawAboutIcon = true
                        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                    walk(nav)
                    assertTrue("About tab label should render", sawLabel)
                    assertTrue("About tab should use ic_nav_about", sawAboutIcon)
                }
            }
        } finally {
            vm.dispose()
            MainActivity.testViewModel = null
        }
    }
}
