// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

// Platform-backed coverage for the SELECTION store adapter (SELECTION_PREFS_NAME).
// This proves the production file/key wiring, commit durability, serialize→parse
// via real SharedPreferences, and serial binding — which the in-memory
// MapBackend VM-recreation simulation cannot establish. It is same-process
// fidelity (Android may return a cached SharedPreferences instance), not an
// independent process-kill guarantee. Original prefs are restored in finally.
@RunWith(AndroidJUnit4::class)
class SelectionRealStoreTest {
    private fun withCleanPrefs(block: (android.content.SharedPreferences) -> Unit) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences(SELECTION_PREFS_NAME, Context.MODE_PRIVATE)
        val original = LinkedHashMap(prefs.all)
        try {
            if (!prefs.edit().clear().commit()) fail("setup clear commit failed")
            block(prefs)
        } finally {
            val editor = prefs.edit().clear()
            for ((k, v) in original) {
                when (v) {
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Set<*> -> editor.putStringSet(k, v.filterIsInstance<String>().toMutableSet())
                    else -> editor.putString(k, v.toString())
                }
            }
            if (!editor.commit()) fail("restore commit failed")
        }
    }

    @Test
    fun realPrefs_selectionRoundTrip_serialBound() {
        withCleanPrefs { prefs ->
            val store = prefs.selectionStore()
            assertTrue(
                store.save(
                    SELECTION_SOURCE_CATALOG,
                    95,
                    setOf("0:com.example.chat", "0:com.example.mail"),
                    1000L,
                    15,
                ),
            )
            val raw = prefs.getString(SELECTION_KEY, null)
            assertTrue(raw != null && raw.contains("serial=15"))

            // New client on same adapter reads the serial-bound snapshot.
            val reread = prefs.selectionStore()
            assertEquals(
                setOf("0:com.example.chat", "0:com.example.mail"),
                reread.loadSelected(95, 15),
            )
            assertEquals(15, reread.loadSnapshot()?.knownTargetSerial)
        }
    }

    @Test
    fun realPrefs_corruptSelfClearsAndRecovers() {
        withCleanPrefs { prefs ->
            if (!prefs.edit().putString(SELECTION_KEY, "garbage-not-a-snapshot").commit()) {
                fail("corrupt setup commit failed")
            }
            val store = prefs.selectionStore()
            assertTrue(store.loadSelected(95, null).isEmpty())
            assertNull(prefs.getString(SELECTION_KEY, null))

            // Recovery: fresh save succeeds after self-clear.
            assertTrue(
                store.save(SELECTION_SOURCE_CATALOG, 95, setOf("0:com.example.chat"), 2000L, 15),
            )
            assertEquals(
                setOf("0:com.example.chat"),
                prefs.selectionStore().loadSelected(95, 15),
            )
        }
    }

    @Test
    fun realPrefs_vmAToVmB_viaProductionStore_restoresSelection() {
        withCleanPrefs { prefs ->
            val gw = FakeHarnessGateway(dualId = 95)
            val vmA = newHarnessViewModel(gw, selectionStore = prefs.selectionStore())
            try {
                awaitHarnessReady(vmA)
                vmA.setSourceCatalog(
                    listOf(
                        SourceAppEntry("com.example.chat", "Chat", 0, false),
                        SourceAppEntry("com.example.mail", "Mail", 0, false),
                    ),
                )
                vmA.refresh()
                HarnessPoll.await { vmA.state.value.inventory.targetId > 0 }

                vmA.toggleSelection(sourceAppKey(0, "com.example.chat"))
                HarnessPoll.await { vmA.state.value.selectedKeys.isNotEmpty() }
                vmA.persistSelection(95)
                HarnessPoll.await { vmA.state.value.msg.contains("已保存") }
            } finally {
                vmA.dispose()
            }

            // Fresh VM on the same production prefs file recovers the selection.
            val gwB = FakeHarnessGateway(dualId = 95)
            val vmB = newHarnessViewModel(gwB, selectionStore = prefs.selectionStore())
            try {
                awaitHarnessReady(vmB)
                vmB.setSourceCatalog(
                    listOf(
                        SourceAppEntry("com.example.chat", "Chat", 0, false),
                        SourceAppEntry("com.example.mail", "Mail", 0, false),
                    ),
                )
                vmB.refresh()
                HarnessPoll.await { sourceAppKey(0, "com.example.chat") in vmB.state.value.selectedKeys }
                assertEquals(15, vmB.state.value.inventory.targetSerial)
                assertNull(vmB.state.value.pendingRemove)
                assertEquals(0, gwB.writeCalls)
                assertEquals(0, gwB.installCalls)
                assertEquals(0, gwB.uninstallCalls)
                assertTrue(gwB.writeCommands.isEmpty())
                assertTrue(gwB.unexpectedCommands.isEmpty())
                // VM-A save path also issues zero device writes.
                assertEquals(0, gw.writeCalls)
                assertEquals(0, gw.installCalls)
                assertEquals(0, gw.uninstallCalls)
                assertTrue(gw.writeCommands.isEmpty())
                assertTrue(gw.unexpectedCommands.isEmpty())
            } finally {
                vmB.dispose()
            }
        }
    }
}
