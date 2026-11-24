// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// PR5 Phase A (#80) / PR #90 Phase A (P0): pure decision table for the real,
// Hook-side pre-activation BBC opt-in. No Android / SharedPreferences / I/O.
//
// P0: the Hook is READ-ONLY and no longer infers anything from current
// packages/version. ONLY the durable App-owned classification (plus an
// explicit opt-in / opt-out and the trusted marker) can decide the effect.
class BbcPreactivationGateTest {

    private val preUpgrade = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING
    private val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED
    private val migratedClass = PolicySource.INSTALL_CLASSIFICATION_MIGRATED
    private val granted = PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED

    @Test
    fun newInstallWithAbsentOptInIsClosed() {
        // No opt-in, no classification, no marker => fresh install: OFF.
        val d = bbcPreactivationGate(true, null, null, null)
        assertFalse(d.effectOpen)
    }

    @Test
    fun staleMarkerWithoutClassificationStaysClosed() {
        // HIGH-2: a marker from an old Hook (or a stale persisted value) is NOT
        // authorization. The gate is pure, so "optIn read failure" is simply
        // optIn=null here; the genuine read-failure case is pinned in the
        // reader test (optInReadFailureIsNull).
        assertFalse(bbcPreactivationGate(true, null, null, true).effectOpen)
        assertFalse(bbcPreactivationGate(true, null, newGeneration, true).effectOpen)
    }

    @Test
    fun explicitOptInOpens() {
        assertTrue(bbcPreactivationGate(true, true, null, null).effectOpen)
        assertTrue(bbcPreactivationGate(true, true, newGeneration, false).effectOpen)
    }

    @Test
    fun explicitOptOutClosesWithHighestPriority() {
        // false wins over every classification and marker combination.
        for (classification in arrayOf(null, preUpgrade, newGeneration, migratedClass, granted, "BOGUS")) {
            for (marker in arrayOf(null, false, true)) {
                assertFalse(
                    "opt-out must win for classification=$classification marker=$marker",
                    bbcPreactivationGate(true, false, classification, marker).effectOpen,
                )
            }
        }
    }

    @Test
    fun draftWithoutTrustedClassificationStaysClosed() {
        // OLD contract: packages/version alone opened the gate and requested a
        // Hook-side marker write. NEW contract: a draft is never evidence. Only
        // the App-written PRE_UPGRADE_EXISTING classification + trusted marker
        // can open; there is no write request in the decision any more.
        assertFalse(bbcPreactivationGate(true, null, null, null).effectOpen)
        assertFalse(bbcPreactivationGate(true, null, preUpgrade, null).effectOpen)
        assertFalse(bbcPreactivationGate(true, null, preUpgrade, false).effectOpen)
        assertTrue(bbcPreactivationGate(true, null, preUpgrade, true).effectOpen)
    }

    @Test
    fun newGenerationNotConsentedIsAlwaysClosed() {
        for (marker in arrayOf(null, false, true)) {
            assertFalse(bbcPreactivationGate(true, null, newGeneration, marker).effectOpen)
        }
    }

    @Test
    fun migratedAndExplicitlyGrantedClassificationsOpen() {
        for (marker in arrayOf(null, false, true)) {
            assertTrue(bbcPreactivationGate(true, null, migratedClass, marker).effectOpen)
            assertTrue(bbcPreactivationGate(true, null, granted, marker).effectOpen)
        }
    }

    @Test
    fun unknownClassificationFailsClosed() {
        assertFalse(bbcPreactivationGate(true, null, "SOMETHING_ELSE", true).effectOpen)
    }

    @Test
    fun preUpgradeWithTrustedMarkerStaysOpenForAnyOptInAbsentState() {
        assertTrue(bbcPreactivationGate(true, null, preUpgrade, true).effectOpen)
    }

    @Test
    fun compileTimeGateClosedAlwaysCloses() {
        // The compile-time constant is only a final AND emergency switch.
        for (optIn in arrayOf(null, false, true)) {
            for (classification in arrayOf(null, preUpgrade, newGeneration, migratedClass, granted)) {
                for (marker in arrayOf(null, false, true)) {
                    assertFalse(
                        bbcPreactivationGate(false, optIn, classification, marker).effectOpen,
                    )
                }
            }
        }
    }

    @Test
    fun migrationSafetyKeyLiteralsArePinned() {
        // MIGRATION-SAFETY PIN (not a behavior test): renaming any of these
        // strings abandons persisted consent / classification state.
        assertEquals("bbc_preactivation_optin", PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN)
        assertEquals("bbc_legacy_migrated", PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED)
        assertEquals("install_classification", PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION)
        assertEquals("PRE_UPGRADE_EXISTING", preUpgrade)
        assertEquals("NEW_GENERATION_NOT_CONSENTED", newGeneration)
        assertEquals("MIGRATED", migratedClass)
        assertEquals("EXPLICITLY_GRANTED", granted)
        assertTrue(PolicySource.INSTALL_CLASSIFICATION_VALUES.containsAll(
            listOf(preUpgrade, newGeneration, migratedClass, granted),
        ))
    }
}
