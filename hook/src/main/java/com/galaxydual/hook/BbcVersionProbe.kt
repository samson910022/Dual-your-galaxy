// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// N03: shared, framework-free version-key probe model. The hook reader uses it
// today (see readBbcVersionedPrefs in DualHook.kt); the app writer can reuse the
// SAME vocabulary in a later phase (app already depends on :hook, see
// app/build.gradle.kts). Keeping the model here — not in DualHook.kt — gives
// both sides one source of truth for "is the version key really absent?".
//
// THE BUG THIS FIXES: the old reader folded "key read threw" into null
// (absent/null version). A transport failure or a corrupt (ClassCastException)
// version key was therefore indistinguishable from a legitimately missing key,
// which permitted a legacy, unversioned Good read to replay after a versioned
// state. The four outcomes below keep those cases apart so the reader can fail
// closed while still allowing the genuine legacy-migration path.

/** Minimal view of the two prefs calls the version probe needs.
 *
 * Framework-free on purpose: production adapts the real SharedPreferences once
 * in the reader; JVM tests supply fakes that can throw or swap values between
 * the two probe calls. */
internal interface VersionPrefsView {
    fun containsVersion(): Boolean
    fun readVersion(defValue: Long): Long
}

/** Outcome of probing the BBC version key. Vocabulary (do NOT conflate):
 *
 * - [Absent] — key CONFIRMED missing (`contains` returned false). This is the
 *   only case where legacy migration is allowed: an unversioned draft may be
 *   treated as a no-version Good/legacy read.
 * - [Present] — key present with a usable value (`value >= 1`).
 * - [Invalid] — key is present but corrupt: value `< 1`, wrong type
 *   (ClassCastException). A read in this state is a FAILURE, never absence.
 *   The reader fails closed (Unknown, keep last-good); a future writer may
 *   later repair the corrupt key.
 * - [Unavailable] — transport / unknown `Throwable`: the probe could not be
 *   completed at all. Failure — fail closed. Never report a missing key.
 *
 * Reader rule: only [Absent] on BOTH seqlock reads yields `version = null` with
 * `failed = false`; [Invalid] or [Unavailable] on either read (or a mismatch)
 * fails closed. High-water is never lowered. */
internal sealed interface VersionProbeResult {
    /** Key confirmed absent — legacy migration allowed. */
    data object Absent : VersionProbeResult

    /** Key present with a usable version (`value >= 1`). */
    data class Present(val value: Long) : VersionProbeResult

    /** Present but corrupt (`< 1` or wrong type). Reader fails closed; writer may repair. */
    data object Invalid : VersionProbeResult

    /** Transport / unknown failure. Fail closed; never treat as absent. */
    data object Unavailable : VersionProbeResult
}

/** Probe the version key exactly once, classifying every failure.
 *
 * Never throws: a [ClassCastException] means a corrupt value ([Invalid]), any
 * other [Throwable] means the probe itself failed ([Unavailable]). The caller
 * compares two such probes (before/after the package snapshot) via seqlock. */
internal fun probeVersion(view: VersionPrefsView): VersionProbeResult = try {
    if (!view.containsVersion()) {
        VersionProbeResult.Absent
    } else {
        val v = view.readVersion(-1L)
        if (v >= 1L) VersionProbeResult.Present(v) else VersionProbeResult.Invalid
    }
} catch (cce: ClassCastException) {
    VersionProbeResult.Invalid
} catch (_: Throwable) {
    VersionProbeResult.Unavailable
}
