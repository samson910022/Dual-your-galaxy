// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// PR5 Phase B1 (#80) / PR #90 Phase A (P0) / Phase B (B1): framework-free
// reader for the Hook-side, real pre-activation BBC opt-in. No SharedPreferences,
// no Android, no I/O — the caller adapts the real remote prefs to
// [BbcPreactivationPrefsView] exactly once and passes the resulting coherent
// snapshot to bbcPreactivationGate(). JVM tests pin every mapping in
// BbcPreactivationPrefsTest.kt.
//
// P0: `legacyPolicyPresent` (inferred from current packages/version) is GONE.
// Those bytes are written by ordinary fresh-install saves and are therefore
// NOT consent evidence. The only durable, App-owned authorization signal is
// [classification]; the Hook merely reads it and never writes anything.
//
// THE FAILURE MAPPING IS ALL-OR-NOTHING:
//   any read failure / invalid classification / wrong type / torn generation
//   -> optIn = null, classification = null, migrated = null (and the coherent
//      snapshot's runtimeEnabled = null, coherent = false)
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

// Phase B (B1): the ONE coherent snapshot consumed by BOTH final BBC stages.
// Production fills all fields from a SINGLE SharedPreferences.getAll() copy;
// versionBefore/versionAfter are the seqlock fence around that copy. A single
// map copy is a single generation, so both probes agree by construction. Any
// failure, wrong type or generation mismatch maps to [failClosed] — never a
// hybrid of two generations.
//
// INTERNAL: only the strictly read-only BbcPreactivationHookReader is public
// for the cross-module (:app) regression test.
internal data class BbcCoherentPrefsSnapshot(
    val optIn: Boolean?,
    val classification: String?,
    val migrated: Boolean?,
    // The hybrid stage's runtime opt-out, read from the SAME generation. The
    // legacy absent-key default (true) is applied by [readBbcCoherentPrefs].
    val runtimeEnabled: Boolean?,
    val versionBefore: VersionProbeResult,
    val versionAfter: VersionProbeResult,
    val coherent: Boolean,
) {
    /** The pre-activation stage consumes only its three original fields. */
    fun preactivationPrefs(): BbcPreactivationPrefs =
        BbcPreactivationPrefs(optIn, classification, migrated)

    /** Generation/fence signal: a Present version identifies the copy. */
    val generation: Long?
        get() = (versionBefore as? VersionProbeResult.Present)?.value

    companion object {
        fun failClosed(): BbcCoherentPrefsSnapshot = BbcCoherentPrefsSnapshot(
            optIn = null,
            classification = null,
            migrated = null,
            runtimeEnabled = null,
            versionBefore = VersionProbeResult.Unavailable,
            versionAfter = VersionProbeResult.Unavailable,
            coherent = false,
        )
    }
}

// Minimal view of the raw remote-prefs reads the decision needs.
// Production adapts the real SharedPreferences ONCE (one getAll() copy) in
// BbcPreactivationHookReader; JVM tests supply a fake that can make any read
// throw or mutate values between reads. INTERNAL: the cross-module test uses
// the public read-only bridge.
internal interface BbcPreactivationPrefsView {
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getString(key: String): String?
    fun getStringSet(key: String): Set<String>?
    fun contains(key: String): Boolean
    fun getLong(key: String, defValue: Long): Long
}

// Map-backed view over ONE immutable copy of the remote preferences. This is
// the production adapter's coherent view: every getter reads the same map, so
// no value can change between contains/get, between packages and version, or
// mid-version-probe. Type checks MATCH real SharedPreferences: a present key
// with a wrong type throws ClassCastException (as Android's typed getters do)
// instead of silently returning the default. The reader maps that to the whole
// fail-closed snapshot.
internal class MapBackedBbcPreactivationPrefsView(
    private val values: Map<String, Any?>,
) : BbcPreactivationPrefsView {
    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val value = values[key] ?: return defValue
        if (value !is Boolean) {
            throw ClassCastException("$value is not a Boolean (key=$key)")
        }
        return value
    }

    override fun getString(key: String): String? {
        val value = values[key] ?: return null
        if (value !is String) {
            throw ClassCastException("$value is not a String (key=$key)")
        }
        return value
    }

    override fun getStringSet(key: String): Set<String>? {
        val value = values[key] ?: return null
        if (value !is Set<*>) {
            throw ClassCastException("$value is not a Set<String> (key=$key)")
        }
        @Suppress("UNCHECKED_CAST")
        return value as Set<String>
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun getLong(key: String, defValue: Long): Long {
        val value = values[key] ?: return defValue
        if (value !is Long) {
            throw ClassCastException("$value is not a Long (key=$key)")
        }
        return value
    }
}

// Reads ALL inputs of BOTH BBC stages as ONE coherent generation. Never throws:
// every read (including the seqlock probes) is guarded, and a single failure,
// wrong type or generation mismatch maps the WHOLE snapshot fail-closed.
//
// Key-specific rules:
//  - optIn / migrated: absent key => null. getBoolean is called with defValue
//    = false ONLY after contains() confirmed the key; a throw/wrong type => null.
//    The old defValue=true "absent means consent" mapping is deliberately gone.
//  - classification: absent => null (no failure, gate fails closed); present
//    with a value outside PolicySource.INSTALL_CLASSIFICATION_VALUES => failure.
//  - runtime (bbc_injection_enabled): absent => legacy defValue=true (the
//    pre-activation gate is now the consent authority); a throw/wrong type
//    fails the whole snapshot.
//  - packages / version: still READ, but ONLY as failure detectors (e.g. a
//    corrupt version key must fail the whole snapshot). They are NEVER used to
//    infer a legacy install / consent — see BbcPreactivationGate.
//  - every scalar is read TWICE and the two observations must agree; the
//    version probe brackets the whole copy. A mutating/live view is therefore
//    rejected even when none of its individual reads throws.
internal fun readBbcCoherentPrefs(view: BbcPreactivationPrefsView): BbcCoherentPrefsSnapshot {
    var failed = false

    // Broad Throwable (including Error): hostile/framework code behind the
    // view may throw anything; it must never escape into the hook path.
    fun <T> read(field: () -> T): T? = try {
        field()
    } catch (_: Throwable) {
        failed = true
        null
    }

    fun probe(): VersionProbeResult = read {
        probeVersion(object : VersionPrefsView {
            override fun containsVersion(): Boolean =
                view.contains(PolicySource.POLICY_KEY_EXTRA_VERSION)

            override fun readVersion(defValue: Long): Long =
                view.getLong(PolicySource.POLICY_KEY_EXTRA_VERSION, defValue)
        })
    } ?: VersionProbeResult.Unavailable

    fun <T> coherent(field: () -> T?): T? {
        val first = read(field)
        val second = read(field)
        if (first != second) {
            failed = true
            return null
        }
        return first
    }

    // Upper fence of the seqlock around the copy.
    val versionBefore = probe()

    val optIn = coherent {
        if (!view.contains(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN)) null
        else view.getBoolean(PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN, false)
    }

    val migrated = coherent {
        if (!view.contains(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED)) null
        else view.getBoolean(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED, false)
    }

    val classification = coherent {
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

    // Failure detector only (no legacy inference): a hostile package set must
    // mask the whole snapshot. Read defensively instead of trusting a live
    // cross-process collection.
    val packagesReadable = coherent {
        view.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES)
        true
    }

    // The hybrid runtime opt-out, from the SAME generation as optIn above.
    // No second IPC/read is needed by the final gate.
    val runtimeEnabled = coherent {
        view.getBoolean(PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED, true)
    }

    // Lower fence: a torn tuple / corrupt version fails the whole snapshot.
    val versionAfter = probe()
    if (versionBefore is VersionProbeResult.Invalid ||
        versionBefore is VersionProbeResult.Unavailable ||
        versionAfter is VersionProbeResult.Invalid ||
        versionAfter is VersionProbeResult.Unavailable ||
        versionBefore != versionAfter
    ) {
        failed = true
    }

    if (failed || packagesReadable != true) return BbcCoherentPrefsSnapshot.failClosed()
    return BbcCoherentPrefsSnapshot(
        optIn = optIn,
        classification = classification,
        migrated = migrated,
        runtimeEnabled = runtimeEnabled,
        versionBefore = versionBefore,
        versionAfter = versionAfter,
        coherent = true,
    )
}

// Reads the opt-in / classification / migration signals from ONE coherent
// view. Never throws: every failure maps the WHOLE snapshot to the fail-closed
// triple above. Kept source-compatible with the pre-Phase-B tests; the
// production adapter now supplies a single-copy (map-backed) view.
internal fun readBbcPreactivationPrefs(view: BbcPreactivationPrefsView): BbcPreactivationPrefs =
    readBbcCoherentPrefs(view).preactivationPrefs()
