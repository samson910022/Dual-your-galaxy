// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29), task D1: in-memory SIMULATION of the
// ChannelPreference persistence contract. The sibling ChannelPreferenceTest
// covers the pure string mapping; this class exercises the SharedPreferences
// read/write PATH using an in-memory SharedPreferences so it runs on the JVM
// without a device or Robolectric (no robolectric dependency is declared in
// app/build.gradle.kts). It does NOT prove on-disk Android SharedPreferences
// behaviour: apply() is synchronous here, listeners are no-ops, and disk-flush
// timing / async durability are not modelled. See the "Fidelity notes" below.
//
// Fidelity notes (keep in sync with InMemorySharedPreferences below):
// - apply() updates the backing map synchronously (real apply() updates
//   in-memory state synchronously but flushes to disk asynchronously).
// - registerOnSharedPreferenceChangeListener is a no-op (production does not
//   use listeners for channel prefs).
// - putString(key, null) is treated as a removal, matching Android's
//   SharedPreferencesImpl.EditorImpl semantics.
// - getString(key, default) returns `default` for a missing key and (unlike
//   real Android SharedPreferences, which throws ClassCastException on a
//   mistyped value) would also return `default` for a non-String entry. The
//   corrupt-type path is therefore NOT covered here; see
//   ChannelPreferenceRealStoreTest for on-device evidence.
//
// Profile/target identity note: ChannelPreference is a process-global user
// choice. It is NOT keyed by profile/target identity (no userId/targetId is
// folded into CHANNEL_PREFS_NAME or CHANNEL_PREF_KEY), so every profile/target
// in the process observes the same persisted channel. The fixed file/key
// constants below are part of that contract.
package com.galaxydual.plus

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ChannelPreferenceInMemoryStoreTest {

    @Before
    fun resetStore() {
        Store.reset(CHANNEL_PREFS_NAME)
    }

    // Minimal in-memory SharedPreferences. Backing maps are shared by file name
    // so a "new client" reading the same file observes previously applied writes
    // (simulating process restart / new SharedPreferences instance).
    private object Store {
        private val files = linkedMapOf<String, MutableMap<String, Any?>>()

        fun backing(name: String): MutableMap<String, Any?> =
            files.getOrPut(name) { linkedMapOf() }

        fun prefs(name: String): SharedPreferences =
            InMemorySharedPreferences(backing(name))

        fun reset(name: String) {
            backing(name).clear()
        }
    }

    private class InMemorySharedPreferences(
        private val data: MutableMap<String, Any?>,
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? {
            val v = data[key]
            return if (v is String) v else defValue
        }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = InMemoryEditor(data)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private class InMemoryEditor(
        private val data: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (value == null) {
                removed.add(key!!)
                pending.remove(key)
            } else {
                pending[key!!] = value
                removed.remove(key)
            }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key!!] = values?.toMutableSet()
            removed.remove(key)
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key!!] = value
            removed.remove(key)
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key!!] = value
            removed.remove(key)
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key!!] = value
            removed.remove(key)
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key!!] = value
            removed.remove(key)
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            removed.add(key!!)
            pending.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            pending.clear()
            removed.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) data.clear()
            for (k in removed) data.remove(k)
            for ((k, v) in pending) data[k] = v
            // Real editors are single-use; reset so a stray second apply/commit
            // does not re-apply or re-clear.
            clearAll = false
            pending.clear()
            removed.clear()
        }
    }

    @Test
    fun store_writeThenNewClientRereads() {
        val name = CHANNEL_PREFS_NAME
        Store.reset(name)

        // Client A writes a non-default choice.
        val clientA = Store.prefs(name)
        clientA.saveChannelPreference(ChannelPreference.SHIZUKU)

        // A brand-new client (fresh SharedPreferences instance, same backing
        // store) must observe the persisted value.
        val clientB = Store.prefs(name)
        assertEquals(ChannelPreference.SHIZUKU, loadChannelPreference(clientB))

        // Round-trip every value through the real store path.
        for (pref in ChannelPreference.values()) {
            Store.reset(name)
            val writer = Store.prefs(name)
            writer.saveChannelPreference(pref)
            val reader = Store.prefs(name)
            assertEquals(pref, loadChannelPreference(reader))
        }
    }

    @Test
    fun store_corruptDataRecoversFailClosed() {
        val name = CHANNEL_PREFS_NAME
        Store.reset(name)

        // Simulate on-disk corruption: a garbage string under the known key.
        val prefs = Store.prefs(name)
        prefs.edit().putString(CHANNEL_PREF_KEY, "!!!garbage-not-a-channel!!!").apply()

        // New client reads the corrupt value and falls back to the fail-closed
        // default (AUTO) instead of crashing.
        val reader = Store.prefs(name)
        assertEquals(ChannelPreference.AUTO, loadChannelPreference(reader))

        // Empty / whitespace-only stored values also recover to AUTO.
        Store.reset(name)
        Store.prefs(name).edit().putString(CHANNEL_PREF_KEY, "   ").apply()
        assertEquals(ChannelPreference.AUTO, loadChannelPreference(Store.prefs(name)))
    }

    @Test
    fun store_identityConstantsStableAndGlobal() {
        // Locks the persistence identity contract: renaming either orphans
        // stored choices. The choice is global (not scoped to profile/target).
        assertEquals("dual_channel_prefs", CHANNEL_PREFS_NAME)
        assertEquals("channel_preference", CHANNEL_PREF_KEY)
    }

    @Test
    fun store_missingKeyDefaultsToAuto() {
        val name = CHANNEL_PREFS_NAME
        Store.reset(name)
        assertEquals(
            ChannelPreference.AUTO,
            loadChannelPreference(Store.prefs(name)),
        )
    }

    @Test
    fun store_whitespaceCaseAndLegacyVariantsRecoverToAuto() {
        val name = CHANNEL_PREFS_NAME
        // Valid lowercase values survive round-trip through the store.
        for (pref in ChannelPreference.values()) {
            Store.reset(name)
            Store.prefs(name).saveChannelPreference(pref)
            assertEquals(pref, loadChannelPreference(Store.prefs(name)))
        }
        // Trim + lowercase is applied by the pure mapper; padded/mixed-case
        // valid literals normalize to their enum (see normalized map below).
        // Trim + lowercase normalization: padded/mixed-case valid literals
        // still resolve to their enum (pure mapper contract).
        val normalized = mapOf(
            "  ROOT  " to ChannelPreference.ROOT,
            "Shizuku" to ChannelPreference.SHIZUKU,
            "LSPOSED" to ChannelPreference.LSPOSED,
            "  Auto  " to ChannelPreference.AUTO,
        )
        for ((raw, expected) in normalized) {
            Store.reset(name)
            Store.prefs(name).edit().putString(CHANNEL_PREF_KEY, raw).apply()
            assertEquals(
                "raw='$raw' must normalize to $expected",
                expected,
                loadChannelPreference(Store.prefs(name)),
            )
        }
        // Genuine unknown/blank/legacy values recover to AUTO.
        val recoverToAuto = listOf(
            "noop",          // legacy value
            "!!!garbage!!!",
            "",
            "   ",
        )
        for (raw in recoverToAuto) {
            Store.reset(name)
            Store.prefs(name).edit().putString(CHANNEL_PREF_KEY, raw).apply()
            assertEquals(
                "raw='$raw' must recover to AUTO",
                ChannelPreference.AUTO,
                loadChannelPreference(Store.prefs(name)),
            )
        }
        // Exact lowercase valid values survive.
        for (raw in listOf("root", "shizuku", "lsposed", "auto")) {
            Store.reset(name)
            Store.prefs(name).edit().putString(CHANNEL_PREF_KEY, raw).apply()
            assertEquals(
                channelPreferenceFromStored(raw),
                loadChannelPreference(Store.prefs(name)),
            )
        }
    }

    @Test
    fun store_putNullTreatedAsRemoval() {
        val name = CHANNEL_PREFS_NAME
        Store.reset(name)
        val prefs = Store.prefs(name)
        prefs.saveChannelPreference(ChannelPreference.SHIZUKU)
        assertEquals(ChannelPreference.SHIZUKU, loadChannelPreference(Store.prefs(name)))

        // putString(key, null) removes the entry (Android semantics).
        prefs.edit().putString(CHANNEL_PREF_KEY, null).apply()
        assertFalse(Store.prefs(name).contains(CHANNEL_PREF_KEY))
        assertEquals(ChannelPreference.AUTO, loadChannelPreference(Store.prefs(name)))
    }
}
