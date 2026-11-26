// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences

// PR #90 Phase A (P0) / Phase B (B1): PUBLIC, STRICTLY READ-ONLY bridge from
// the real remote preferences to the framework-free reader/gate. The Hook
// obtains remote prefs via libxposed XposedInterface.getRemotePreferences,
// which is READ-ONLY in hooked apps (API 102). This adapter therefore only
// ever calls getAll() and getters: there is no edit()/put*/commit() call on
// this path (proved by BbcPreactivationReadOnlyTest with a fake whose edit()
// throws/counts).
//
// Phase B coherence: getAll() is called exactly ONCE and immediately copied
// into an immutable map. That map-backed BbcPreactivationPrefsView is the ONE
// generation every field (including the hybrid runtime opt-out) is read from,
// so the final effect can never mix two torn generations. A wrong type throws
// ClassCastException (exactly like Android's typed getters) and is mapped to
// the whole fail-closed snapshot.
//
// PUBLIC so :app tests can drive production `remoteHookPolicyStore` writes and
// then feed the very same prefs through the Hook reader + gate (cross-boundary
// regression test). No Android runtime is needed to call it with a fake.
object BbcPreactivationHookReader {

    /**
     * Reads ONE coherent snapshot from a SINGLE getAll() copy. Strictly
     * read-only. A null prefs / a throwing getAll() / any wrong type / any
     * generation mismatch fails closed.
     */
    internal fun readCoherent(prefs: SharedPreferences?): BbcCoherentPrefsSnapshot = try {
        if (prefs == null) {
            BbcCoherentPrefsSnapshot.failClosed()
        } else {
            val copy = try {
                LinkedHashMap<String, Any?>(prefs.getAll())
            } catch (_: Throwable) {
                null
            }
            if (copy == null) {
                BbcCoherentPrefsSnapshot.failClosed()
            } else {
                readBbcCoherentPrefs(MapBackedBbcPreactivationPrefsView(copy))
            }
        }
    } catch (_: Throwable) {
        BbcCoherentPrefsSnapshot.failClosed()
    }

    /** Reads one coherent preactivation snapshot. A null prefs / any failure fails closed. */
    fun read(prefs: SharedPreferences?): BbcPreactivationPrefs =
        readCoherent(prefs).preactivationPrefs()

    /** Applies the read-only decision to an already-read snapshot. */
    fun effectOpen(compileTimeGate: Boolean, prefs: BbcPreactivationPrefs): Boolean =
        bbcPreactivationGate(
            compileTimeGate,
            prefs.optIn,
            prefs.classification,
            prefs.migrated,
        ).effectOpen

    /** Convenience: read + decide in one call (still zero writes). */
    fun effectOpen(compileTimeGate: Boolean, prefs: SharedPreferences?): Boolean =
        effectOpen(compileTimeGate, read(prefs))
}
