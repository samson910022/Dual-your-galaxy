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
    @Test
    fun realPrefs_writeThenNewClientRereads() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences(CHANNEL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        for (pref in ChannelPreference.values()) {
            prefs.saveChannelPreference(pref)
            val reader = ctx.getSharedPreferences(CHANNEL_PREFS_NAME, Context.MODE_PRIVATE)
            assertEquals(pref, loadChannelPreference(reader))
        }

        prefs.edit().clear().commit()
    }

    @Test
    fun realPrefs_corruptTypeFailsClosed() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences(CHANNEL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // Store a non-String under the known key to simulate type corruption.
        prefs.edit().putInt(CHANNEL_PREF_KEY, 42).commit()

        // loadChannelPreference must not throw and must fall back to AUTO.
        assertEquals(ChannelPreference.AUTO, loadChannelPreference(prefs))

        // Documented no-self-clear policy: the mistyped entry is left untouched
        // because it cannot be safely rewritten here.
        assertEquals(42, prefs.getInt(CHANNEL_PREF_KEY, -1))
        assertTrue(prefs.contains(CHANNEL_PREF_KEY))

        prefs.edit().clear().commit()
    }
}
