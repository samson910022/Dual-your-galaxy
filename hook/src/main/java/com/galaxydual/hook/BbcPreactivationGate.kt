// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// PR5 Phase A (#80): pure, framework-free decision for the Hook-side, real
// pre-activation BBC opt-in. No SharedPreferences, no Android, no I/O — the
// caller reads remote prefs and passes the four observed values here. The
// remote-prefs wiring is DONE in Phase B1 (BbcPreactivationPrefs.kt plus
// DualHook.bbcPreactivationPrefsNow()/bbcPreactivationOpenNow()); the App-side
// first-ON consent landed in Phase B2 (BbcPreactivationConsent.kt / MainActivity).
// JVM tests pin the full decision table in BbcPreactivationGateTest.kt.
//
// Product decision (breaking change from the shipped "default on" behavior):
//  1. NEW INSTALL / FAIL CLOSED. optIn == null (key absent OR read failure)
//     with no legacy basis => effect OFF until the app writes an explicit
//     pre-activation opt-in (POLICY_KEY_BBC_PREACTIVATION_OPTIN = true).
//  2. EXISTING INSTALL / SAFE MIGRATION. A non-empty legacy policy draft
//     (extra_whitelist_packages non-empty or extra_whitelist_version present)
//     with no migration marker is treated as legacy opted-in: effect stays ON
//     and writeMigrationMarker asks the caller to persist
//     POLICY_KEY_BBC_LEGACY_MIGRATED = true. The existing draft is NEVER
//     deleted, cleared or rewritten — no silent policy shrink.
//  3. EXPLICIT OPT-OUT ALWAYS WINS. optIn == false => OFF regardless of legacy
//     state or marker (the separate bbc_injection_enabled=false key is ANDed
//     by the outer hybrid gate, so it wins there too).
//  4. MIGRATED IS PERSISTENT. migrated == true means this install already went
//     through the one-time migration, so it stays ON even if the policy draft
//     was later cleared (clearing rules is not an opt-out). migrated == false
//     is NOT a migration and opens nothing.
//  5. FINAL AND. compileTimeGate == false is the emergency kill switch: closed
//     for every input, with no marker write. It is no longer the "default on"
//     rationale — that role belongs to rules 1-4.
//
// BREAKING-CHANGE SENSITIVITY: the two policy keys above must be renamed only
// with an explicit migration plan; renaming abandons persisted consent and
// migration state. See docs/ADR_bbc_preactivation_optin.md.

internal data class BbcPreactivationDecision(
    val effectOpen: Boolean,
    val writeMigrationMarker: Boolean,
)

internal fun bbcPreactivationGate(
    compileTimeGate: Boolean,
    optIn: Boolean?, // null = POLICY_KEY_BBC_PREACTIVATION_OPTIN absent or read failure
    legacyPolicyPresent: Boolean,
    migrated: Boolean?, // null = POLICY_KEY_BBC_LEGACY_MIGRATED absent or read failure
): BbcPreactivationDecision {
    // 5. Final AND emergency switch: never opens, never writes a marker.
    if (!compileTimeGate) return BbcPreactivationDecision(false, false)

    // 3. Explicit opt-out has the highest priority over every fallback.
    if (optIn == false) return BbcPreactivationDecision(false, false)

    // Explicit pre-activation consent: open, nothing to migrate.
    if (optIn == true) return BbcPreactivationDecision(true, false)

    // optIn == null: fresh install fails closed, but an already-migrated
    // install keeps its persistent state (4) and a legacy draft must not be
    // silently abandoned because a transient read failed (2).
    if (migrated == true) return BbcPreactivationDecision(true, false)
    if (legacyPolicyPresent && migrated == null) {
        return BbcPreactivationDecision(true, true)
    }
    return BbcPreactivationDecision(false, false)
}
