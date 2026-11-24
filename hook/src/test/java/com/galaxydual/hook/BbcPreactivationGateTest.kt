// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// PR5 Phase A (#80): pure decision table for the real, Hook-side pre-activation
// BBC opt-in. No Android / SharedPreferences / I/O here — these pins cover the
// decision only; wiring the remote-prefs readers is a later phase.
//
// Product decision (breaking change):
//  - New install: key absent OR read failure => effect OFF (fail closed) until
//    the app writes an explicit pre-activation opt-in.
//  - Existing install: a non-empty legacy draft (extra_whitelist_packages or
//    extra_whitelist_version) with no migration marker => treat as legacy
//    opted-in (effect ON) and ask the caller to write bbc_legacy_migrated=true.
//    Never delete / clear / rewrite the existing policy rules.
//  - Explicit opt-out (the new optin key false, or the legacy
//    bbc_injection_enabled=false at the outer AND) always wins => OFF.
//  - The compile-time gate is only a final AND emergency kill switch, no longer
//    the source of "default on".
class BbcPreactivationGateTest {

    @Test
    fun newInstallWithAbsentOptInIsClosed() {
        // No optin key, no legacy draft, no migration marker => fresh install:
        // BBC injection must stay off until the user opts in before activation.
        val d = bbcPreactivationGate(
            compileTimeGate = true,
            optIn = null,
            legacyPolicyPresent = false,
            migrated = null,
        )
        assertFalse(d.effectOpen)
        assertFalse(d.writeMigrationMarker)
    }

    @Test
    fun optInReadFailureIsClosed() {
        // The signature deliberately merges "key absent" and "read failure"
        // into null. On a fresh install (no legacy basis) both fail closed: a
        // transient IPC failure must never be treated as consent. On an
        // existing install the separate legacy-migration rule below applies
        // even when this read failed (we must not silently shrink old rules).
        val d = bbcPreactivationGate(true, null, false, null)
        assertFalse(d.effectOpen)
        assertFalse(d.writeMigrationMarker)
    }

    @Test
    fun explicitOptInOpensWithoutMarker() {
        val d = bbcPreactivationGate(true, true, false, null)
        assertTrue(d.effectOpen)
        assertFalse(d.writeMigrationMarker)
    }

    @Test
    fun explicitOptOutClosesWithHighestPriority() {
        // false wins over a migration marker and over a legacy draft: an
        // explicit opt-out is never overridden by legacy/opt-in state.
        val withMarker = bbcPreactivationGate(true, false, true, true)
        assertFalse(withMarker.effectOpen)
        assertFalse(withMarker.writeMigrationMarker)

        val withLegacy = bbcPreactivationGate(true, false, true, null)
        assertFalse(withLegacy.effectOpen)
        assertFalse(withLegacy.writeMigrationMarker)

        val fresh = bbcPreactivationGate(true, false, false, null)
        assertFalse(fresh.effectOpen)
        assertFalse(fresh.writeMigrationMarker)
    }

    @Test
    fun legacyDraftWithoutMarkerOpensAndRequestsMigrationMarker() {
        // Existing install with a non-empty policy draft and no migration marker:
        // keep the effect ON and ask the caller to persist the marker. The draft
        // itself is NOT deleted / cleared / rewritten by this decision.
        val d = bbcPreactivationGate(true, null, true, null)
        assertTrue(d.effectOpen)
        assertTrue(d.writeMigrationMarker)
    }

    @Test
    fun legacyDraftWithMarkerOpensWithoutRewrite() {
        // A previously migrated install carries the marker: effect stays ON,
        // but no marker write is needed (idempotent, no repeated I/O).
        val d = bbcPreactivationGate(true, null, true, true)
        assertTrue(d.effectOpen)
        assertFalse(d.writeMigrationMarker)
    }

    @Test
    fun migratedMarkerAloneOpensAfterPolicyCleared() {
        // migrated=true is the PERSISTENT state of an already-migrated install.
        // It stays open even after the user later clears the policy draft:
        // clearing rules is not an opt-out (use the explicit opt-out key), and
        // we must not silently revoke consent already given before activation.
        val d = bbcPreactivationGate(true, null, false, true)
        assertTrue(d.effectOpen)
        assertFalse(d.writeMigrationMarker)
    }

    @Test
    fun migratedMarkerFalseOnFreshInstallIsClosed() {
        // An explicit marker=false is not "migrated": it must never open the gate.
        val d = bbcPreactivationGate(true, null, false, false)
        assertFalse(d.effectOpen)
        assertFalse(d.writeMigrationMarker)
    }

    @Test
    fun compileTimeGateClosedAlwaysClosesWithoutMarkerWrites() {
        // The compile-time constant is retained only as a final AND emergency
        // switch: false overrides every other input, including an explicit
        // opt-in and a pending legacy migration.
        for (optIn in arrayOf(null, false, true)) {
            for (legacy in arrayOf(false, true)) {
                for (migrated in arrayOf(null, false, true)) {
                    val d = bbcPreactivationGate(false, optIn, legacy, migrated)
                    assertFalse(d.effectOpen)
                    assertFalse(d.writeMigrationMarker)
                }
            }
        }
    }

    @Test
    fun breakingChangeSensitiveKeyLiteralsAreStable() {
        // Renaming either key abandons persisted consent / migration state.
        // Pinning the literals makes that a deliberate, reviewed change.
        assertEquals("bbc_preactivation_optin", PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN)
        assertEquals("bbc_legacy_migrated", PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED)
    }
}
