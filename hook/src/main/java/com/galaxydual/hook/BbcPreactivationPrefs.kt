// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// PR5 Phase B1 (#80) / PR #90 Phase A (P0): framework-free reader for the
// Hook-side, real pre-activation BBC opt-in. No SharedPreferences, no Android,
// no I/O — the caller adapts the real remote prefs to [BbcPreactivationPrefsView]
// exactly once and passes the resulting snapshot to bbcPreactivationGate().
// JVM tests pin every mapping in BbcPreactivationPrefsTest.kt.
//
// P0: `legacyPolicyPresent` (inferred from current packages/version) is GONE.
// Those bytes are written by ordinary fresh-install saves and are therefore
// NOT consent evidence. The only durable, App-owned authorization signal is
// [classification]; the Hook merely reads it and never writes anything.
//
// THE FAILURE MAPPING IS ALL-OR-NOTHING:
//   any read failure / invalid classification -> optIn = null,
//   classification = null, migrated = null
// A transient remote-prefs outage must NEVER be converted into "existing
// install" or into an authorization. An ABSENT classification key is a normal
// pre-rollout state: it stays null (not a failure) and fails closed at the
// gate. A PRESENT but unknown value is a failure and masks the whole snapshot.
// This reader never writes anything and never touches the policy draft.
// Kept PUBLIC only because it is the return value of the public read-only
// bridge BbcPreactivationHookReader used by the cross-module (`:app`) test.
data class BbcPreactivationPrefs(
    val optIn: Boolean?,
    val classification: String?,
    val migrated: Boolean?,
)

// Minimal view of the raw remote-prefs reads the decision needs.
// Production adapts the real SharedPreferences once in
// BbcPreactivationHookReader; JVM tests supply a fake that can make any read
// throw. INTERNAL: the cross-module test uses the public read-only bridge.
internal interface BbcPreactivationPrefsView {
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getString(key: String): String?
    fun getStringSet(key: String): Set<String>?
    fun contains(key: String): Boolean
    fun getLong(key: String, defValue: Long): Long
}

// Reads the opt-in / classification / migration signals from one snapshot.
// Never throws: every read is guarded, and a single failure maps the WHOLE
// snapshot to the fail-closed triple above.
//
// Key-specific rules:
//  - optIn / migrated: absent key => null. getBoolean is called with defValue
//    = false ONLY after contains() confirmed the key; a throw => null. The old
//    defValue=true "absent means consent" mapping is deliberately gone.
//  - classification: absent => null (no failure, gate fails closed); present
//    with a value outside PolicySource.INSTALL_CLASSIFICATION_VALUES => failure.
//  - packages / version: still READ, but ONLY as failure detectors (e.g. a
//    corrupt version key must fail the whole snapshot). They are NEVER used to
//    infer a legacy install / consent — see BbcPreactivationGate.
internal fun readBbcPreactivationPrefs(view: BbcPreactivationPrefsView): BbcPreactivationPrefs {
    var failed = false

    // Broad Throwable (including Error): hostile/framework code behind the
    // view may throw anything; it must never escape into the hook path.
    fun <T> read(field: () -> T): T? = try {
        field()
    } catch (_: Throwable) {
        failed = true
        null
    }

    val optIn = read {
        if (!view.contains(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN)) null
        else view.getBoolean(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN, false)
    }

    val migrated = read {
        if (!view.contains(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED)) null
        else view.getBoolean(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED, false)
    }

    val classification = read {
        if (!view.contains(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION)) {
            null
        } else {
            val value = view.getString(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION)
            if (value == null || value !in PolicySource.INSTALL_CLASSIFICATION_VALUES) {
                failed = true // present but unreadable / unknown => fail closed
            }
            value
        }
    }

    // Failure detectors only (no legacy inference): a hostile package set or a
    // corrupt/unavailable version key must mask the whole snapshot.
    val packagesReadable = read {
        view.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES)
        true
    }

    // Reuse the shared version-probe vocabulary (no second "is this key
    // really present?" implementation). probeVersion itself never throws, but
    // its Invalid/Unavailable outcomes are failures for this decision.
    val version = read {
        probeVersion(object : VersionPrefsView {
            override fun containsVersion(): Boolean =
                view.contains(PolicySource.POLICY_KEY_EXTRA_VERSION)

            override fun readVersion(defValue: Long): Long =
                view.getLong(PolicySource.POLICY_KEY_EXTRA_VERSION, defValue)
        })
    }
    if (version is VersionProbeResult.Invalid || version is VersionProbeResult.Unavailable) {
        failed = true
    }

    if (failed || packagesReadable != true) return BbcPreactivationPrefs(null, null, null)
    return BbcPreactivationPrefs(
        optIn = optIn,
        classification = classification,
        migrated = migrated,
    )
}
