// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// REWRITTEN (PR #90 Phase A, P0): the old test pinned a Hook-side marker writer
// that violates libxposed API 102 (hooked-app remote prefs are READ-ONLY). The
// writer is GONE. This test now proves the opposite contract: the public Hook
// reader/gate adapter reads a correct snapshot from a fake whose edit() throws,
// performs ZERO write attempts, and never lets an exception escape.
class BbcPreactivationReadOnlyTest {

    private class ReadOnlyFakePrefs(
        private val values: Map<String, Any?> = emptyMap(),
        private val failGetAll: Boolean = false,
    ) : SharedPreferences {
        var editCalls = 0
            private set

        // Phase B: production reads ONE copy via getAll(); a broken transport
        // therefore surfaces as a throwing getAll(), not a per-key throw.
        override fun getAll(): MutableMap<String, *> {
            if (failGetAll) throw IllegalStateException("remote prefs getAll failed")
            return LinkedHashMap(values)
        }
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return values[key] as? MutableSet<String> ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (values[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor {
            editCalls++
            throw AssertionError("Hook must NEVER call edit() on hooked-app remote prefs")
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    @Test
    fun migratedSnapshotIsReadWithoutAnyWriteAttempt() {
        val prefs = ReadOnlyFakePrefs(
            mapOf(
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                    PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
                PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.old"),
                PolicySource.POLICY_KEY_EXTRA_VERSION to 1L,
            ),
        )
        val snapshot = BbcPreactivationHookReader.read(prefs)
        assertEquals(PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING, snapshot.classification)
        assertEquals(true, snapshot.migrated)
        assertNull(snapshot.optIn)
        assertTrue(BbcPreactivationHookReader.effectOpen(true, snapshot))
        assertEquals("zero write attempts even when edit() would throw", 0, prefs.editCalls)
    }

    @Test
    fun ordinaryDraftOnlyIsClosedWithoutAnyWriteAttempt() {
        // A fresh install's normal save writes these bytes; they must not open
        // the gate and must not trigger any write/marker attempt.
        val prefs = ReadOnlyFakePrefs(
            mapOf(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.new"),
                PolicySource.POLICY_KEY_EXTRA_VERSION to 1L,
            ),
        )
        val snapshot = BbcPreactivationHookReader.read(prefs)
        assertNull(snapshot.classification)
        assertNull(snapshot.migrated)
        assertFalse(BbcPreactivationHookReader.effectOpen(true, snapshot))
        assertEquals(0, prefs.editCalls)
    }

    @Test
    fun failedReadFailsClosedWithoutAnyWriteAttempt() {
        val prefs = ReadOnlyFakePrefs(
            mapOf(
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                    PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
            ),
            failGetAll = true,
        )
        val snapshot = BbcPreactivationHookReader.read(prefs)
        assertEquals(BbcPreactivationPrefs(null, null, null), snapshot)
        assertFalse(BbcPreactivationHookReader.effectOpen(true, snapshot))
        assertEquals(0, prefs.editCalls)
    }

    @Test
    fun nullRemotePrefsFailsClosed() {
        val snapshot = BbcPreactivationHookReader.read(null)
        assertEquals(BbcPreactivationPrefs(null, null, null), snapshot)
        assertFalse(BbcPreactivationHookReader.effectOpen(true, snapshot))
    }
}
