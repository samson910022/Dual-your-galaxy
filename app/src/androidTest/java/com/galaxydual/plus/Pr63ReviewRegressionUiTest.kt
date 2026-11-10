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
        fun collect(root: android.view.View) {
            if (root is Button && (root.tag as? String)?.startsWith("remove:") == true) {
                out += root
                return
            }
            if (root is ViewGroup) {
                for (j in 0 until root.childCount) collect(root.getChildAt(j))
            }
        }
        for (i in 0 until container.childCount) {
            collect(container.getChildAt(i))
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

    private data class RowDiag(
        val ok: Boolean,
        val detail: String,
    )

    private fun awaitRowButtons(
        scenario: ActivityScenario<MainActivity>,
        vm: DualViewModel,
        keyCount: Int,
        genBefore: Int,
        btnA0: Button,
        btnB0: Button,
        expectEnabled: Boolean,
        phase: String,
    ) {
        val deadline = System.nanoTime() + 30_000L * 1_000_000L
        var last = RowDiag(false, "no sample")
        while (System.nanoTime() <= deadline) {
            scenario.onActivity { activity ->
                val bs = findRowRemoveButtons(activity).sortedBy { it.tag?.toString() }
                val container = field(activity, "clonedAppsContainer") as LinearLayout
                val genNow = field(activity, "clonedGen") as Int
                val lastKey = field(activity, "lastClonedListKey") as? String
                val nowA = bs.firstOrNull { it.tag == "remove:com.example.a" }
                val nowB = bs.firstOrNull { it.tag == "remove:com.example.b" }
                val ok = bs.size == 2 &&
                    bs.all { it.isEnabled == expectEnabled } &&
                    container.childCount == keyCount &&
                    genNow == genBefore &&
                    nowA === btnA0 &&
                    nowB === btnB0
                val s = vm.state.value
                last = RowDiag(
                    ok,
                    "phase=$phase expectEnabled=$expectEnabled " +
                        "ok=$ok bs.size=${bs.size} enabled=${bs.map { it.isEnabled }} " +
                        "childCount=${container.childCount}/$keyCount clonedGen=$genNow/$genBefore " +
                        "idA=${nowA === btnA0} idB=${nowB === btnB0} " +
                        "tags=${bs.map { it.tag }} " +
                        "vm[loading=${s.loading} writeReady=${s.writeReady} pmReadable=${s.pmReadable} " +
                        "resolved=${s.resolvedGateway} privilege=${s.privilege} exec=${s.execIdentity} " +
                        "cloned=${s.cloned} inv.targetId=${s.inventory.targetId} inv.serial=${s.inventory.targetSerial} " +
                        "sourceCatalog.size=${s.sourceCatalog.size} sourceCatalog.hash=${s.sourceCatalog.hashCode()} " +
                        "hookStatus=${s.hookStatus} hookBootFresh=${s.hookBootFresh}] " +
                        "lastClonedListKey=$lastKey",
                )
            }
            if (last.ok) return
            Thread.sleep(50)
        }
        throw AssertionError("awaitRowButtons timeout: ${last.detail}")
    }

    private fun awaitCatalogSettled(scenario: ActivityScenario<MainActivity>, timeoutMs: Long = 30_000L) {
        HarnessPoll.await(timeoutMs) {
            var settled = false
            scenario.onActivity { activity ->
                val job = try {
                    field(activity, "catalogJob")
                } catch (e: Exception) {
                    throw AssertionError("awaitCatalogSettled: failed to read catalogJob field", e)
                }
                settled = when (job) {
                    null -> false
                    is kotlinx.coroutines.Job -> job.isCompleted
                    else -> throw AssertionError(
                        "awaitCatalogSettled: catalogJob has unexpected type ${job.javaClass.name}",
                    )
                }
            }
            settled
        }
    }

    private fun applyStableWritableState(vm: DualViewModel) {
        // Pin fingerprint inputs used by renderClonedApps/buildClonedListKey so
        // Activity init/foreground/catalog async work cannot change the list key
        // between baseline capture and early-return sync assertions. catalogKey is
        // derived from sourceCatalog (stable empty list here); inventory/hook
        // evidence stay at defaults. loading/pmReadable transitions under test do
        // NOT affect clonedListKeyFor.
        vm.setSourceCatalog(emptyList())
        setVmState(vm) {
            it.copy(
                loading = false,
                cloned = listOf("com.example.a", "com.example.b"),
                pmReadable = true,
                writeReady = true,
                resolvedGateway = ResolvedChannel.ROOT,
                privilege = Privilege.ROOT,
                execIdentity = Privilege.ROOT,
                inventory = InventorySnapshot.unknown(),
                hookBootFresh = false,
            )
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
            awaitHarnessDrained(vm)
            MainActivity.testViewModel = vm
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.switchTab(MainActivity.TAB_DUAL_APPS, announce = false)
                }
                // Wait for Activity startup catalog load to settle, pin a stable
                // sourceCatalog/inventory, then re-check: closes the small window
                // where a reload could start between settle and pinning. This prevents
                // catalogHash/fingerprint changes from rebuilding rows (clonedGen/
                // Button identity) during early-return sync checks.
                awaitCatalogSettled(scenario)
                applyStableWritableState(vm)
                awaitCatalogSettled(scenario)
                HarnessPoll.await(30_000L) {
                    var ok = false
                    scenario.onActivity { activity ->
                        val bs = findRowRemoveButtons(activity).sortedBy { it.tag?.toString() }
                        ok = bs.size == 2 && bs.all { it.isEnabled && it.width > 0 && it.layout != null }
                    }
                    ok
                }

                var keyCount = 0
                var genBefore = 0
                lateinit var btnA0: Button
                lateinit var btnB0: Button
                scenario.onActivity { activity ->
                    val buttons = findRowRemoveButtons(activity).sortedBy { it.tag?.toString() }
                    assertEquals(2, buttons.size)
                    val expectedVisualText = activity.getString(R.string.action_remove_row)
                    val expectedActionCd = activity.getString(R.string.action_remove)
                    val min48 = (48 * activity.resources.displayMetrics.density).toInt()
                    buttons.forEach { b ->
                        val pkg = b.tag.toString().removePrefix("remove:")
                        assertEquals("remove:$pkg", b.tag)
                        assertEquals("visual short label for $pkg", expectedVisualText, b.text.toString())
                        assertNotNull(b.contentDescription)
                        val cd = b.contentDescription.toString()
                        assertTrue("CD should contain full action for $pkg", cd.contains(expectedActionCd))
                        assertTrue("CD should contain pkg $pkg", cd.contains(pkg))
                        assertTrue("touch target >= 48dp", b.minimumHeight >= min48)
                        assertTrue("touch target >= 48dp", b.minimumWidth >= min48)
                        assertEquals(1, b.maxLines)
                        assertTrue("width should be positive for $pkg", b.width > 0)
                        assertTrue("height should be positive for $pkg", b.height > 0)
                        val layout = requireNotNull(b.layout) { "layout must be measured for $pkg" }
                        assertTrue("layout should have at least one line for $pkg", layout.lineCount > 0)
                        assertEquals(
                            "row remove label must not ellipsize for $pkg (line0 ellipsis=${layout.getEllipsisCount(0)})",
                            0,
                            layout.getEllipsisCount(0),
                        )
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
                awaitRowButtons(scenario, vm, keyCount, genBefore, btnA0, btnB0, expectEnabled = false, phase = "loading=true")
                setVmState(vm) { it.copy(loading = false) }
                awaitRowButtons(scenario, vm, keyCount, genBefore, btnA0, btnB0, expectEnabled = true, phase = "loading=false")
                assertWritableState(vm)

                setVmState(vm) { it.copy(loading = false, pmReadable = false) }
                awaitRowButtons(scenario, vm, keyCount, genBefore, btnA0, btnB0, expectEnabled = false, phase = "pmReadable=false")
                setVmState(vm) { it.copy(loading = false, pmReadable = true) }
                awaitRowButtons(scenario, vm, keyCount, genBefore, btnA0, btnB0, expectEnabled = true, phase = "pmReadable=true")
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
