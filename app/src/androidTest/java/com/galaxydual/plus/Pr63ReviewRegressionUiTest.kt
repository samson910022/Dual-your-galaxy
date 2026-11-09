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
import com.galaxydual.core.Privilege
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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

    private fun assertWritableState(vm: DualViewModel) {
        val s = vm.state.value
        assertTrue("explicit writable precondition: not loading", !s.loading)
        assertTrue("explicit writable precondition: writeReady", s.writeReady)
        assertTrue("explicit writable precondition: pmReadable != false", s.pmReadable != false)
        assertTrue(
            "explicit writable precondition: not LSPosed placeholder",
            s.resolvedGateway != ResolvedChannel.LSPOSED_PLACEHOLDER,
        )
        assertTrue("explicit writable precondition: execIdentity", s.execIdentity != Privilege.NONE)
        assertTrue("explicit writable precondition: privilege", s.privilege != Privilege.NONE)
    }

    private fun awaitRowButtons(
        scenario: ActivityScenario<MainActivity>,
        keyCount: Int,
        genBefore: Int,
        btnA0: Button,
        btnB0: Button,
        expectEnabled: Boolean,
    ) {
        HarnessPoll.await(30_000L) {
            var ok = false
            scenario.onActivity { activity ->
                val bs = findRowRemoveButtons(activity).sortedBy { it.tag?.toString() }
                val container = field(activity, "clonedAppsContainer") as LinearLayout
                val genNow = field(activity, "clonedGen") as Int
                val nowA = bs.firstOrNull { it.tag == "remove:com.example.a" }
                val nowB = bs.firstOrNull { it.tag == "remove:com.example.b" }
                ok = bs.size == 2 &&
                    bs.all { it.isEnabled == expectEnabled } &&
                    container.childCount == keyCount &&
                    genNow == genBefore &&
                    nowA === btnA0 &&
                    nowB === btnB0
            }
            ok
        }
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
                // Render cloned rows with an explicit writable precondition. Fields
                // affecting writeBlockReason/blockKey but NOT clonedListKeyFor
                // (cloned, targetSerial, targetId, hookLiveProven, catalogKey) are
                // set so early-return sync transitions can be asserted naturally.
                setVmState(vm) {
                    it.copy(
                        loading = false,
                        cloned = listOf("com.example.a", "com.example.b"),
                        pmReadable = true,
                        writeReady = true,
                        resolvedGateway = ResolvedChannel.ROOT,
                        privilege = Privilege.ROOT,
                        execIdentity = Privilege.ROOT,
                    )
                }
                HarnessPoll.await(30_000L) {
                    var count = 0
                    scenario.onActivity { activity -> count = findRowRemoveButtons(activity).size }
                    count >= 2
                }

                var keyCount = 0
                var genBefore = 0
                lateinit var btnA0: Button
                lateinit var btnB0: Button
                scenario.onActivity { activity ->
                    val buttons = findRowRemoveButtons(activity).sortedBy { it.tag?.toString() }
                    assertEquals(2, buttons.size)
                    val expectedText = activity.getString(R.string.action_remove)
                    buttons.forEach { b ->
                        val pkg = b.tag.toString().removePrefix("remove:")
                        assertEquals("remove:$pkg", b.tag)
                        assertEquals(expectedText, b.text.toString())
                        assertNotNull(b.contentDescription)
                        val cd = b.contentDescription.toString()
                        assertTrue("CD should contain action for $pkg", cd.contains(expectedText))
                        assertTrue("CD should contain pkg $pkg", cd.contains(pkg))
                        assertTrue("touch target >= 48dp", b.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt())
                        assertTrue("touch target >= 48dp", b.minimumWidth >= (48 * activity.resources.displayMetrics.density).toInt())
                        assertEquals(1, b.maxLines)
                    }
                    keyCount = (field(activity, "clonedAppsContainer") as LinearLayout).childCount
                    genBefore = field(activity, "clonedGen") as Int
                    btnA0 = buttons.first { it.tag == "remove:com.example.a" }
                    btnB0 = buttons.first { it.tag == "remove:com.example.b" }
                    assertTrue("initial row buttons must be enabled", btnA0.isEnabled && btnB0.isEnabled)
                }
                assertWritableState(vm)

                // Same-key early return: busy and writable/blockKey transitions must
                // sync row remove enabled without rebuilding rows. buildClonedListKey
                // excludes loading; pmReadable is not in the fingerprint but affects
                // writeBlockReason/blockKey, so it exercises the writable gate via the
                // same early-return sync (state seam, no View.isEnabled forcing).
                setVmState(vm) { it.copy(loading = true) }
                awaitRowButtons(scenario, keyCount, genBefore, btnA0, btnB0, expectEnabled = false)
                setVmState(vm) { it.copy(loading = false) }
                awaitRowButtons(scenario, keyCount, genBefore, btnA0, btnB0, expectEnabled = true)
                assertWritableState(vm)

                setVmState(vm) { it.copy(loading = false, pmReadable = false) }
                awaitRowButtons(scenario, keyCount, genBefore, btnA0, btnB0, expectEnabled = false)
                setVmState(vm) { it.copy(loading = false, pmReadable = true) }
                awaitRowButtons(scenario, keyCount, genBefore, btnA0, btnB0, expectEnabled = true)
                assertWritableState(vm)

                // Async label rebind updates CD in place (same Button instances) and
                // a naturally enabled click requests removal (two-phase) with no
                // immediate uninstall/write.
                scenario.onActivity { activity ->
                    method(activity, "cacheLabel", String::class.java, String::class.java)
                        .invoke(activity, "com.example.a", "Example A")
                    method(activity, "rebindClonedLabel", String::class.java, String::class.java)
                        .invoke(activity, "com.example.a", "Example A")
                    val rebound = findRowRemoveButtons(activity).first { it.tag == "remove:com.example.a" }
                    assertSame("rebind must not rebuild row/button", btnA0, rebound)
                    assertTrue(rebound.contentDescription.toString().contains("Example A"))
                    assertTrue("naturally enabled after transitions", rebound.isEnabled)
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
