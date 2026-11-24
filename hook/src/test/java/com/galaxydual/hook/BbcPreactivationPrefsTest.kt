// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

// PR5 Phase B1 (#80) / PR #90 Phase A (P0): framework-free reader for the
// Hook-side pre-activation prefs. A fake view supplies the raw reads so every
// mapping (absent / value / failure -> snapshot) is pinned without a device.
//
// P0 contract: packages/version are NEVER a legacy/consent basis. Only the
// App-written durable classification is an authorization input; any read
// failure (including an unknown classification value) masks the WHOLE snapshot
// so the gate fails closed and no write is ever requested.
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

        override fun getString(key: String): String? {
            check(key)
            return values[key] as? String
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
    private val failClosed = BbcPreactivationPrefs(null, null, null)

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
        assertNull(readBbcPreactivationPrefs(FakeBbcPrefsView()).optIn)
    }

    @Test
    fun optInReadFailureIsNull() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN)),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun classificationAbsentIsNullButNotAFailure() {
        // Pre-rollout / bind-not-yet-established state: null classification,
        // but opt-in and marker can still be read normally (the gate decides).
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to true),
        )
        assertNull(p.classification)
        assertEquals(true, p.optIn)
    }

    @Test
    fun everyKnownClassificationValueIsRead() {
        for (value in PolicySource.INSTALL_CLASSIFICATION_VALUES) {
            val p = readBbcPreactivationPrefs(
                viewOf(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to value),
            )
            assertEquals(value, p.classification)
        }
    }

    @Test
    fun unknownClassificationValueFailsTheWholeSnapshot() {
        // REWRITTEN (P0): an unexpected value is never treated as a legacy basis.
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to "SOMETHING_ELSE"),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun packagesAndVersionAloneAreNotClassificationEvidence() {
        // REWRITTEN legacyVersionOnly / legacyNonEmptyPackages: an ordinary
        // fresh-install save writes these bytes, but they must never become
        // migration evidence for the Hook.
        val p = readBbcPreactivationPrefs(
            viewOf(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.one"),
                PolicySource.POLICY_KEY_EXTRA_VERSION to 1L,
            ),
        )
        assertNull(p.classification)
        assertEquals(false, bbcPreactivationGate(true, p.optIn, p.classification, p.migrated).effectOpen)
    }

    @Test
    fun classificationReadFailureFailsTheWholeSnapshot() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION)),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun packagesReadFailureFailsWholeSnapshot() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_EXTRA_PACKAGES)),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun versionReadFailureFailsWholeSnapshot() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_EXTRA_VERSION)),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun migratedAbsentIsNull() {
        assertNull(readBbcPreactivationPrefs(FakeBbcPrefsView()).migrated)
    }

    @Test
    fun migratedTrue() {
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true),
        )
        assertEquals(true, p.migrated)
    }

    @Test
    fun migratedReadFailureFailsTheWholeSnapshot() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(throwing = setOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED)),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun corruptVersionIsAFailure() {
        // probeVersion vocabulary: present but < 1 is Invalid (corrupt), not
        // usable proof. It must fail closed, never count as a legacy basis.
        val p = readBbcPreactivationPrefs(
            viewOf(PolicySource.POLICY_KEY_EXTRA_VERSION to 0L),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun anyFieldFailureMasksEvenSuccessfullyReadFields() {
        val p = readBbcPreactivationPrefs(
            FakeBbcPrefsView(
                values = mapOf(
                    PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to true,
                    PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                        PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING,
                    PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.one"),
                    PolicySource.POLICY_KEY_EXTRA_VERSION to 1L,
                    PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
                ),
                throwing = setOf(PolicySource.POLICY_KEY_EXTRA_VERSION),
            ),
        )
        assertEquals(failClosed, p)
    }

    @Test
    fun allReadsThrowNeverThrowsAndReturnsFailClosed() {
        val p = readBbcPreactivationPrefs(FakeBbcPrefsView(throwOnAll = true))
        assertEquals(failClosed, p)
    }

    // ---- Phase B (B6): live/mutating views and wrong types must fail closed ----

    private fun assertThrowsClassCast(block: () -> Unit) {
        try {
            block()
            fail("expected ClassCastException (Android typed getter contract)")
        } catch (_: ClassCastException) {
            // expected
        }
    }

    // Flips the boolean between contains() and getBoolean() — no exception, so
    // only a coherent (double-read) snapshot can detect it.
    private class FlippingBooleanView : BbcPreactivationPrefsView {
        private var optInCalls = 0

        override fun contains(key: String): Boolean = when (key) {
            PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN,
            PolicySource.POLICY_KEY_EXTRA_PACKAGES,
            PolicySource.POLICY_KEY_EXTRA_VERSION,
            -> true
            else -> false
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean = when (key) {
            PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN -> {
                optInCalls++
                optInCalls % 2 == 1
            }
            else -> defValue
        }

        override fun getString(key: String): String? = null
        override fun getStringSet(key: String): Set<String>? = setOf("com.example.pkg")
        override fun getLong(key: String, defValue: Long): Long = 1L
    }

    @Test
    fun mutatingOptInBetweenContainsAndGetFailsWholeSnapshot() {
        assertEquals(failClosed, readBbcPreactivationPrefs(FlippingBooleanView()))
    }

    // Version changes between the packages read and the lower fence.
    private class FlipVersionAfterPackagesView : BbcPreactivationPrefsView {
        private var packagesCalls = 0

        override fun contains(key: String): Boolean = key in setOf(
            PolicySource.POLICY_KEY_EXTRA_PACKAGES,
            PolicySource.POLICY_KEY_EXTRA_VERSION,
        )

        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun getString(key: String): String? = null
        override fun getStringSet(key: String): Set<String>? {
            packagesCalls++
            return setOf("com.example.pkg")
        }

        override fun getLong(key: String, defValue: Long): Long =
            if (packagesCalls == 0) 1L else 2L
    }

    @Test
    fun versionFlipBetweenPackagesAndFenceFailsWholeSnapshot() {
        assertEquals(failClosed, readBbcPreactivationPrefs(FlipVersionAfterPackagesView()))
    }

    // Version changes mid-probe (between containsVersion and readVersion or
    // across the two version probes) — the seqlock fence must reject it.
    private class FlipMidVersionProbeView : BbcPreactivationPrefsView {
        private var versionCalls = 0

        override fun contains(key: String): Boolean = key == PolicySource.POLICY_KEY_EXTRA_VERSION
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun getString(key: String): String? = null
        override fun getStringSet(key: String): Set<String>? = null
        override fun getLong(key: String, defValue: Long): Long {
            versionCalls++
            return versionCalls.toLong()
        }
    }

    @Test
    fun versionFlipMidProbeFailsWholeSnapshot() {
        assertEquals(failClosed, readBbcPreactivationPrefs(FlipMidVersionProbeView()))
    }

    @Test
    fun wrongTypeOptInThrowsLikeAndroidAndFailsWholeSnapshot() {
        val view = MapBackedBbcPreactivationPrefsView(
            mapOf(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to "true"),
        )
        assertThrowsClassCast {
            view.getBoolean(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN, false)
        }
        assertEquals(failClosed, readBbcPreactivationPrefs(view))
    }

    @Test
    fun wrongTypeMigratedThrowsLikeAndroidAndFailsWholeSnapshot() {
        val view = MapBackedBbcPreactivationPrefsView(
            mapOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to 1L),
        )
        assertThrowsClassCast {
            view.getBoolean(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED, false)
        }
        assertEquals(failClosed, readBbcPreactivationPrefs(view))
    }

    @Test
    fun wrongTypeClassificationThrowsLikeAndroidAndFailsWholeSnapshot() {
        val view = MapBackedBbcPreactivationPrefsView(
            mapOf(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to 42),
        )
        assertThrowsClassCast { view.getString(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION) }
        assertEquals(failClosed, readBbcPreactivationPrefs(view))
    }

    @Test
    fun wrongTypePackagesThrowsLikeAndroidAndFailsWholeSnapshot() {
        val view = MapBackedBbcPreactivationPrefsView(
            mapOf(PolicySource.POLICY_KEY_EXTRA_PACKAGES to "not a set"),
        )
        assertThrowsClassCast { view.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES) }
        assertEquals(failClosed, readBbcPreactivationPrefs(view))
    }

    @Test
    fun wrongTypeVersionThrowsLikeAndroidAndFailsWholeSnapshot() {
        val view = MapBackedBbcPreactivationPrefsView(
            mapOf(PolicySource.POLICY_KEY_EXTRA_VERSION to "7"),
        )
        assertThrowsClassCast { view.getLong(PolicySource.POLICY_KEY_EXTRA_VERSION, -1L) }
        assertEquals(failClosed, readBbcPreactivationPrefs(view))
    }

    @Test
    fun wrongTypeRuntimeThrowsLikeAndroidAndFailsWholeSnapshot() {
        // Even a fully consented opt-in must not survive a corrupt runtime key.
        val view = MapBackedBbcPreactivationPrefsView(
            mapOf(
                PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to true,
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                    PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED,
                PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to 1L,
            ),
        )
        assertThrowsClassCast {
            view.getBoolean(PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED, true)
        }
        assertEquals(failClosed, readBbcPreactivationPrefs(view))
        assertFalse(BbcPreactivationHookReader.effectOpen(true, readBbcPreactivationPrefs(view)))
    }

    @Test
    fun coherentSnapshotCarriesAllStageInputsAndSameGeneration() {
        val view = MapBackedBbcPreactivationPrefsView(
            mapOf(
                PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN to true,
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to
                    PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED,
                PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true,
                PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED to false,
                PolicySource.POLICY_KEY_EXTRA_PACKAGES to setOf("com.example.pkg"),
                PolicySource.POLICY_KEY_EXTRA_VERSION to 3L,
            ),
        )
        val s = readBbcCoherentPrefs(view)
        assertEquals(true, s.coherent)
        assertEquals(true, s.optIn)
        assertEquals(
            PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED,
            s.classification,
        )
        assertEquals(true, s.migrated)
        assertEquals(false, s.runtimeEnabled)
        assertEquals(3L, s.generation)
        assertEquals(s.versionBefore, s.versionAfter)
    }
}
