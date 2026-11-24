// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// PR5 Phase B1 (#80): framework-free reader for the Hook-side, real
// pre-activation BBC opt-in. No SharedPreferences, no Android, no I/O — the
// caller adapts the real remote prefs to [BbcPreactivationPrefsView] exactly
// once and passes the resulting snapshot to bbcPreactivationGate(). JVM tests
// pin every mapping in BbcPreactivationPrefsTest.kt.
//
// THE FAILURE MAPPING IS ALL-OR-NOTHING (orchestrator handoff):
//   any read failure -> optIn = null, legacyPolicyPresent = false, migrated = null
// Rationale: a transient remote-prefs outage must NEVER be converted into
// "existing install" (which would open the effect AND write the migration
// marker). A short outage only PAUSES the effect; it is not a policy shrink.
// This reader never writes anything and never touches the policy draft.
internal data class BbcPreactivationPrefs(
    val optIn: Boolean?,
    val legacyPolicyPresent: Boolean,
    val migrated: Boolean?,
)

// Minimal view of the four raw remote-prefs reads the decision needs.
// Production adapts the real SharedPreferences once in DualHook;
// JVM tests supply a fake that can make any read throw.
internal interface BbcPreactivationPrefsView {
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getStringSet(key: String): Set<String>?
    fun contains(key: String): Boolean
    fun getLong(key: String, defValue: Long): Long
}

// Reads the opt-in / legacy / migration signals from one snapshot.
// Never throws: every read is guarded, and a single failure maps the WHOLE
// snapshot to the fail-closed triple above.
//
// Key-specific rules:
//  - optIn / migrated: absent key => null. getBoolean is called with defValue
//    = false ONLY after contains() confirmed the key; a throw => null. The old
//    defValue=true "absent means consent" mapping is deliberately gone.
//  - legacyPolicyPresent: true iff the package set is non-empty OR the version
//    key is a usable Present (probeVersion's vocabulary: >= 1). Invalid (< 1 /
//    wrong type) or Unavailable (transport throw) are FAILURES, not proof of a
//    legacy install, so they map the whole snapshot to fail-closed.
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

    val packagesNonEmpty = read {
        val set = view.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES)
        set != null && set.isNotEmpty()
    } ?: false

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

    if (failed) return BbcPreactivationPrefs(null, false, null)
    return BbcPreactivationPrefs(
        optIn = optIn,
        legacyPolicyPresent = packagesNonEmpty || version is VersionProbeResult.Present,
        migrated = migrated,
    )
}
