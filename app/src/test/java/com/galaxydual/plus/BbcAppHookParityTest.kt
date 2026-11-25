// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 review (R3-03): APP/HOOK PARITY. Feed the SAME durable key map through
// the app's authoritative read + holder.resolve() and through the public,
// strictly read-only Hook reader + gate, then assert they agree. This is the
// regression test for the app having a weaker authorization model than the
// Hook: explicit opt-out must win, read failures must fail closed, and a legacy
// install must only open after a CONFIRMED durable classification.
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.hook.BbcPreactivationHookReader
import com.galaxydual.hook.PolicySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BbcAppHookParityTest {

    private val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
    private val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
    private val markerKey = PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED
    private val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED
    private val preUpgrade = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING
    private val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED
    private val explicitlyGranted = PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED
    private val migrated = PolicySource.INSTALL_CLASSIFICATION_MIGRATED
    private val versionKey = PolicySource.POLICY_KEY_EXTRA_VERSION
    private val packagesKey = PolicySource.POLICY_KEY_EXTRA_PACKAGES

    /** Read-only map view with real SharedPreferences typed-getter semantics. */
    private class MapPrefs(initial: Map<String, Any?>) : SharedPreferences {
        val data = LinkedHashMap(initial)

        override fun getAll(): MutableMap<String, *> = LinkedHashMap<String, Any?>(data)

        override fun getString(key: String?, defValue: String?): String? {
            val value = data[key] ?: return defValue
            return value as? String ?: throw ClassCastException("$value is not a String")
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val value = data[key] ?: return defValues
            if (value !is Set<*>) throw ClassCastException("$value is not a Set<String>")
            val result = LinkedHashSet<String>()
            for (item in value) {
                if (item !is String) throw ClassCastException("$item is not a String")
                result.add(item)
            }
            return result
        }

        override fun getInt(key: String?, defValue: Int): Int {
            val value = data[key] ?: return defValue
            return value as? Int ?: throw ClassCastException("$value is not an Int")
        }

        override fun getLong(key: String?, defValue: Long): Long {
            val value = data[key] ?: return defValue
            return value as? Long ?: throw ClassCastException("$value is not a Long")
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            val value = data[key] ?: return defValue
            return value as? Float ?: throw ClassCastException("$value is not a Float")
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            val value = data[key] ?: return defValue
            return value as? Boolean ?: throw ClassCastException("$value is not a Boolean")
        }

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only")

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    /** App side: the REAL store snapshot fed to the REAL holder. */
    private fun appRead(prefs: SharedPreferences, generation: Long): BbcConsentAuthoritativeRead =
        remoteHookPolicyStore { prefs }.readBbcConsentAuthoritative().forGeneration(generation)

    /**
     * Both sides must agree on the gate decision. App "open" means one of the
     * authorized states (Granted/Legacy), i.e. no consent dialog on ON.
     */
    private fun assertParity(
        prefs: SharedPreferences,
        confirmedClassification: Boolean,
        expectedOpen: Boolean,
    ): BbcActivationConsentState {
        val hookOpen = BbcPreactivationHookReader.effectOpen(true, prefs)
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(7L)
        if (confirmedClassification) holder.markInstallClassificationConfirmed(7L)
        holder.resolve(appRead(prefs, 7L))
        val state = holder.currentState()
        val appOpen = state is BbcActivationConsentState.Granted ||
            state is BbcActivationConsentState.Legacy
        assertEquals("Hook gate decision", expectedOpen, hookOpen)
        assertEquals("app/hook authorization parity", expectedOpen, appOpen)
        return state
    }

    @Test
    fun explicitFalseOptOut_withPreUpgradeAndMarker_bothClosed() {
        val prefs = MapPrefs(
            mapOf(
                optinKey to false,
                classKey to preUpgrade,
                markerKey to true,
                runtimeKey to true,
            ),
        )
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals("explicit FALSE wins over legacy classification", BbcActivationConsentState.NotGranted, state)
        assertTrue(state.requiresConsentFor(true))
    }

    @Test
    fun optInReadFailure_bothFailClosed() {
        // A present-but-wrong-type key is a read failure, not an absent key.
        val prefs = MapPrefs(
            mapOf(
                optinKey to "not-a-boolean",
                classKey to preUpgrade,
                markerKey to true,
                runtimeKey to true,
            ),
        )
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals("an unreadable snapshot must not authorize", BbcActivationConsentState.Initializing, state)
        assertTrue(state.requiresConsentFor(true))
    }

    @Test
    fun absentOptInWithConfirmedLegacy_bothOpenWithPersistedRuntime() {
        val prefs = MapPrefs(
            mapOf(
                classKey to preUpgrade,
                markerKey to true,
                runtimeKey to false,
            ),
        )
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = true)
        assertEquals("app must mirror the Hook's legacy authorization", BbcActivationConsentState.Legacy(false), state)
        assertFalse(state.requiresConsentFor(true))
        // The checkbox seed is the persisted runtime value, never default-true.
        assertEquals(false, state.toggleUi().checked)
    }

    @Test
    fun absentOptInWithUnconfirmedLegacy_appStaysFailClosed() {
        val prefs = MapPrefs(
            mapOf(
                classKey to preUpgrade,
                markerKey to true,
                runtimeKey to true,
            ),
        )
        // The Hook cannot observe the App's bind-time confirmation; the App
        // must not authorize from an optimistic/unconfirmed classification.
        assertTrue(BbcPreactivationHookReader.effectOpen(true, prefs))
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(7L)
        holder.resolve(appRead(prefs, 7L))
        assertEquals(BbcActivationConsentState.Initializing, holder.currentState())
    }

    @Test
    fun absentRuntimeKey_optInTrue_authorizesWithoutFabricatingRuntime() {
        val prefs = MapPrefs(mapOf(optinKey to true, classKey to newGeneration))
        val snapshot = appRead(prefs, 7L)
        assertEquals(BbcOptInValue.TRUE, snapshot.optIn)
        assertNull("absent runtime key must stay null, not default-true", snapshot.legacyRuntimeEnabled)
        val state = assertParity(prefs, confirmedClassification = false, expectedOpen = true)
        assertEquals(BbcActivationConsentState.Granted, state)
    }

    @Test
    fun runtimeFalse_optInTrue_authorizesButRendersUnchecked() {
        val prefs = MapPrefs(
            mapOf(optinKey to true, classKey to newGeneration, runtimeKey to false),
        )
        val state = assertParity(prefs, confirmedClassification = false, expectedOpen = true)
        assertEquals(BbcActivationConsentState.Granted, state)
        assertNull("Granted alone is not a checkbox value", state.toggleUi().checked)
        assertEquals(
            false,
            bbcToggleUiProjection(state, BbcEffectStatus.Confirmed(false), null).checked,
        )
    }

    // ---- P2 review: mirror the Hook's failure detectors -----------------------

    @Test
    fun corruptVersionKey_shortValue_bothFailClosed() {
        // optIn=true would open the gate on its own, but a corrupt version
        // poisons the WHOLE coherent snapshot on both sides.
        val prefs = MapPrefs(
            mapOf(
                optinKey to true,
                versionKey to 0L, // < 1 => VersionProbeResult.Invalid
                classKey to preUpgrade,
                markerKey to true,
                runtimeKey to true,
            ),
        )
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals(BbcActivationConsentState.Initializing, state)
        assertTrue(state.requiresConsentFor(true))
    }

    @Test
    fun corruptVersionKey_wrongType_bothFailClosed() {
        val prefs = MapPrefs(
            mapOf(
                optinKey to true,
                versionKey to "not-a-long", // CCE => Invalid in the Hook probe
                classKey to preUpgrade,
                markerKey to true,
            ),
        )
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals(BbcActivationConsentState.Initializing, state)
    }

    @Test
    fun wrongTypePackages_bothFailClosed() {
        val prefs = MapPrefs(
            mapOf(
                optinKey to true,
                packagesKey to "not-a-set", // Hook's packagesReadable detector
                classKey to explicitlyGranted,
            ),
        )
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals(BbcActivationConsentState.Initializing, state)
    }

    // ---- P2 review: missing classification parity tuples ---------------------

    @Test
    fun explicitlyGrantedClassification_bothOpenWithoutMarker() {
        val prefs = MapPrefs(mapOf(classKey to explicitlyGranted, runtimeKey to true))
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = true)
        assertEquals(BbcActivationConsentState.Legacy(true), state)
        assertFalse(state.requiresConsentFor(true))
    }

    @Test
    fun migratedClassification_bothOpenWithoutMarker() {
        val prefs = MapPrefs(mapOf(classKey to migrated, runtimeKey to false))
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = true)
        assertEquals(BbcActivationConsentState.Legacy(false), state)
    }

    @Test
    fun newGenerationClassification_bothClosed() {
        val prefs = MapPrefs(mapOf(classKey to newGeneration, runtimeKey to true))
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals(BbcActivationConsentState.NotGranted, state)
        assertTrue(state.requiresConsentFor(true))
    }

    @Test
    fun absentClassification_bothClosed() {
        val prefs = MapPrefs(mapOf(runtimeKey to true))
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals(BbcActivationConsentState.Initializing, state)
        assertTrue(state.requiresConsentFor(true))
    }

    @Test
    fun preUpgradeWithoutMarker_bothClosed() {
        val prefs = MapPrefs(
            mapOf(classKey to preUpgrade, markerKey to false, runtimeKey to true),
        )
        val state = assertParity(prefs, confirmedClassification = true, expectedOpen = false)
        assertEquals(BbcActivationConsentState.Initializing, state)
        assertTrue(state.requiresConsentFor(true))
    }
}
