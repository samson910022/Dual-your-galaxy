// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelPreferenceRealStoreTest {
    // These tests run against the production-named prefs file on a disposable
    // test app instance; they save and restore the original contents so a
    // pre-existing channel choice is not permanently discarded.

    private fun withCleanPrefs(block: (android.content.SharedPreferences) -> Unit) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences(CHANNEL_PREFS_NAME, Context.MODE_PRIVATE)
        val original = LinkedHashMap(prefs.all)
        try {
            prefs.edit().clear().commit()
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
                }
            }
            editor.commit()
        }
    }

    @Test
    fun realPrefs_sameProcessRoundTrip() {
        withCleanPrefs { prefs ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            for (pref in ChannelPreference.values()) {
                prefs.saveChannelPreference(pref)
                // Same-process framework-backed read: Android may return a
                // cached SharedPreferences instance, so this validates platform
                // API/type behaviour, not an independent process restart.
                val reader = ctx.getSharedPreferences(CHANNEL_PREFS_NAME, Context.MODE_PRIVATE)
                assertEquals(pref, loadChannelPreference(reader))
            }
        }
    }

    @Test
    fun realPrefs_corruptTypeFailsClosed() {
        withCleanPrefs { prefs ->
            // Store a non-String under the known key to simulate type corruption.
            prefs.edit().putInt(CHANNEL_PREF_KEY, 42).commit()

            // loadChannelPreference must not throw and must fall back to AUTO.
            assertEquals(ChannelPreference.AUTO, loadChannelPreference(prefs))

            // Documented no-self-clear policy: the mistyped entry is left
            // untouched because it cannot be safely rewritten here.
            assertEquals(42, prefs.getInt(CHANNEL_PREF_KEY, -1))
            assertTrue(prefs.contains(CHANNEL_PREF_KEY))
        }
    }
}
