// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// PR5 Phase B1 (#80): framework-free reader for the Hook-side pre-activation
// prefs. No Android / SharedPreferences / I/O here — a fake view supplies the
// four raw reads so the mapping (absent / value / failure -> fail-closed
// triple) is pinned without a device.
//
// CRITICAL MAPPING (handoff): a read failure must never be converted into
// "existing install". Any field that fails makes the WHOLE snapshot a failure:
//   optIn = null, legacyPolicyPresent = false, migrated = null.
// That is what prevents a transient IPC outage from opening the gate AND from
// writing the migration marker. No policy draft/version is ever touched here.
class BbcPreactivationPrefsTest {

    private class FakeBbcPrefsView(
        private val values: Map<String, Any?> = emptyMap(),
        private val throwing: Set<String> = emptySet(),
        private val throwOnAll: Boolean = false,
    ) : BbcPreactivationPrefsView {
        private fun check(key: String) {
            if (throwOnAll || key in throwing) {
                throw IllegalStateException("simulated read failure: $key")
            }
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            check(key)
            return values[key] as? Boolean ?: defValue
        }

        override fun getStringSet(key: String): Set<String>? {
            check(key)
            @Suppress("UNCHECKED_CAST")
            return values[key] as? Set<String>?
        }

        override fun contains(key: String): Boolean {
            check(key)
            return values.containsKey(key)
        }

        override fun getLong(key: String, defValue: Long): Long {
            check(key)
            return values[key] as? Long ?: defValue
        }
    }

    private fun viewOf(vararg pairs: Pair<String, Any?>) = FakeBbcPrefsView(mapOf(*pairs))

    @Test
    fun optInTrue() {
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to true),
        )
        assertEquals(true, p.optIn)
    }

    @Test
    fun optInFalse() {
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to false),
        )
        assertEquals(false, p.optIn)
    }

    @Test
    fun optInAbsentIsNull() {
        // Key absent must NOT inherit defValue=true (that was the old
        // fail-open behavior). Absence means "no consent recorded".
        val p = readBbcPreactivationPrefs(FakeBbcPrefsView())
        assertNull(p.optIn)
    }

    @Test
    fun optInReadFailureIsNull() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN)),
        )
        assertNull(p.optIn)
    }

    @Test
    fun legacyNonEmptyPackages() {
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.one")),
        )
        assertTrue(p.legacyPolicyPresent)
    }

    @Test
    fun legacyEmptyPackagesIsNotABasis() {
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_EXTRA_PACKAGES to emptySet<String>()),
        )
        assertFalse(p.legacyPolicyPresent)
    }

    @Test
    fun legacyVersionOnly() {
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_EXTRA_VERSION to 1L),
        )
        assertTrue(p.legacyPolicyPresent)
    }

    @Test
    fun noLegacyBasis() {
        val p = readBbcPreactivationPrefs(FakeBbcPrefsView())
        assertFalse(p.legacyPolicyPresent)
    }

    @Test
    fun legacyPackagesReadFailureIsFalse() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_EXTRA_PACKAGES)),
        )
        assertFalse(p.legacyPolicyPresent)
        // Whole-snapshot failure: never convert a failed read into "legacy install".
        assertEquals(BbcPreactivationPrefs(null, false, null), p)
    }

    @Test
    fun legacyVersionReadFailureIsFalse() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_EXTRA_VERSION)),
        )
        assertFalse(p.legacyPolicyPresent)
        assertEquals(BbcPreactivationPrefs(null, false, null), p)
    }

    @Test
    fun migratedAbsentIsNull() {
        val p = readBbcPreactivationPrefs(FakeBbcPrefsView())
        assertNull(p.migrated)
    }

    @Test
    fun migratedTrue() {
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true),
        )
        assertEquals(true, p.migrated)
    }

    @Test
    fun migratedReadFailureIsNull() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED)),
        )
        assertNull(p.migrated)
    }

    @Test
    fun corruptVersionIsAFailureNotALegacyBasis() {
        // probeVersion vocabulary: present but < 1 is Invalid (corrupt), not
        // a usable version proof. It must fail closed, never count as legacy.
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_EXTRA_VERSION to 0L),
        )
        assertEquals(BbcPreactivationPrefs(null, false, null), p)
    }

    @Test
    fun anyFieldFailureMasksEvenSuccessfullyReadFields() {
        // opt-in / legacy / migrated were all readable, but a single failing
        // probe (version) makes the snapshot fail closed as a whole — that is
        // what guarantees no marker write and no opening on a transient outage.
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(
                values = mapOf(
                    PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to true,
                    PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.one"),
                    PolicySource.POLICY_KEY_EXTRA_VERSION to 1L,
                    PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
                ),
                throwing = setOf(PolicySource.POLICY_KEY_EXTRA_VERSION),
            ),
        )
        assertEquals(BbcPreactivationPrefs(null, false, null), p)
    }

    @Test
    fun allReadsThrowNeverThrowsAndReturnsFailClosed() {
        val p = readBbcPreactivationPrefs(FakeBbcPrefsView(throwOnAll = true))
        assertEquals(BbcPreactivationPrefs(null, false, null), p)
    }
}
