// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 3 / R3-1: tri-state result of a BBC opt-out preference write.
// Framework-free on purpose: both the store seam and later serializer/UI phases
// can use it in JVM unit tests without Robolectric.
package com.galaxydual.plus

/**
 * Outcome of writing the persisted BBC injection opt-out preference.
 *
 * This is deliberately tri-state because the old Boolean return could not
 * distinguish "the daemon acknowledged the new value" from "the commit failed
 * and nothing is known". A `false` return must never be read as "the old value
 * is still in force".
 *
 * [Confirmed] refers ONLY to the PERSISTED PREFERENCE (the remote daemon
 * acknowledged the commit). It says nothing about immediate hook behavior: the
 * hook process may still be serving a cached value until it re-reads the
 * preference, so never treat this as an instantaneous on/off switch.
 *
 * [Unknown] means `commit(false)` or a throw: the local `RemotePreferences`
 * mMap may have been optimistically polluted with the new value, so this
 * process could read the new value back even though nothing was persisted.
 * Unknown must NEVER be treated as "restored to the old value" — the old value
 * was not verified, and the local cache cannot be trusted as evidence either.
 *
 * The BBC opt-out is NOT a Samsung cold-start deletion guard: deletion happens
 * before the BBC callback (issue #75). Neither outcome is a safety guarantee.
 */
internal sealed class BbcWriteOutcome {
    /** The daemon acknowledged the persisted preference value. */
    data class Confirmed(val value: Boolean) : BbcWriteOutcome()

    /** Commit failed/threw; local mMap may be polluted. Never "restored to old". */
    data object Unknown : BbcWriteOutcome()
}
