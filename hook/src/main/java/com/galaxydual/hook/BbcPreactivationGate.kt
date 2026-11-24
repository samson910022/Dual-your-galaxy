// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// PR5 Phase A (#80) / PR #90 Phase A (P0): pure, framework-free decision for the
// Hook-side, real pre-activation BBC opt-in. No SharedPreferences, no Android,
// no I/O — the caller reads remote prefs and passes the observed values here.
// JVM tests pin the full decision table in BbcPreactivationGateTest.kt.
//
// P0 (PR #90): the Hook is STRICTLY READ-ONLY. There is no write request in the
// decision any more — no marker write, no retry on the hot path. The owner App
// persists the durable install classification (and, for a genuine upgrade, the
// migration marker) atomically before any new-generation draft write.
//
// Decision rules (explicit opt-out always wins):
//  1. compileTimeGate == false => OFF (final AND emergency kill switch).
//  2. optIn == false           => OFF (explicit opt-out, highest priority).
//  3. optIn == true            => ON (explicit pre-activation consent).
//  4. classification EXPLICITLY_GRANTED or MIGRATED => ON.
//  5. classification PRE_UPGRADE_EXISTING => ON ONLY when the migration marker
//     is a trusted true (the App writes both in the same commit). A null/false
//     marker fails closed — never infer consent from packages/version bytes.
//  6. classification NEW_GENERATION_NOT_CONSENTED or null => OFF.
//
// BREAKING-CHANGE SENSITIVITY: the policy keys and classification values in
// PolicySource must be renamed only with an explicit migration plan; renaming
// abandons persisted consent / classification state. See
// docs/ADR_bbc_preactivation_optin.md.
// INTERNAL: only the strictly read-only BbcPreactivationHookReader is public
// for the cross-module (:app) regression test.
internal data class BbcPreactivationDecision(
    val effectOpen: Boolean,
)

internal fun bbcPreactivationGate(
    compileTimeGate: Boolean,
    optIn: Boolean?, // null = POLICY_KEY_BBC_PREACTIVATION_OPTIN absent or read failure
    classification: String?, // null = absent/read failure; unknown values fail closed
    migrated: Boolean?, // null = POLICY_KEY_BBC_LEGACY_MIGRATED absent or read failure
): BbcPreactivationDecision {
    // 1. Final AND emergency switch: never opens.
    if (!compileTimeGate) return BbcPreactivationDecision(false)

    // 2. Explicit opt-out has the highest priority over every fallback.
    if (optIn == false) return BbcPreactivationDecision(false)

    // 3. Explicit pre-activation consent: open.
    if (optIn == true) return BbcPreactivationDecision(true)

    // 4-6. Durable, App-owned classification is the ONLY legacy/authorization
    // basis. packages/version are deliberately NOT inputs here.
    return BbcPreactivationDecision(
        when (classification) {
            PolicySource.INSTALL_CLASSIFICATION_EXPLICITLY_GRANTED,
            PolicySource.INSTALL_CLASSIFICATION_MIGRATED -> true
            PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING -> migrated == true
            PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED -> false
            null -> false
            else -> false // unknown value: fail closed
        },
    )
}
