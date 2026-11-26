// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 4 / E04: framework-free projection from the app-scoped BBC write
// outcome holder to the toggle row's presentation. No Android imports so JVM
// tests can exercise it without Robolectric; the string resources stay the
// single source of truth for the issue-#75 cold-start caveat.
package com.galaxydual.plus

/**
 * UI-facing projection of the app-scoped BBC write outcome.
 *
 * [Provisional] is the seed for "no mark(Confirmed) has happened yet in this
 * process". A fresh/unconfirmed process is rendered UNVERIFIED — identical to
 * [Unknown]'s "effect unknown" styling — and must never be shown as a
 * confirmation of the effect.
 *
 * [Confirmed] still literally means a confirmed mark was observed: a persisted
 * preference acknowledgement only, NOT proof of any effect. It keeps its
 * caveat-bearing summary. [Unknown] is produced ONLY by a successfully applied
 * mark(Unknown). It says nothing about which value the preference holds: a
 * commit(false)/throw may have polluted the local mMap, so Unknown is never a
 * rollback or a restored value. This is also NOT a Samsung cold-start deletion
 * guard (issue #75).
 *
 * PR #90 Phase 3 (R3-06a/E2): [ProvisionalRead] carries a persisted value that
 * was READ on the app IO lane. It supplies the checkbox value so the row is not
 * stuck neutral, but it is deliberately NOT [Confirmed]: a read can never claim
 * that a write was acknowledged, and it renders the same UNVERIFIED presentation
 * as [Provisional]/[Unknown]. A preference read may observe an optimistically
 * polluted local mMap, so it is never write-confirmation evidence either.
 */
internal sealed interface BbcEffectStatus {
    /** A non-stale mark(Confirmed) was applied; [value] is the persisted value. */
    data class Confirmed(val value: Boolean) : BbcEffectStatus

    /** A non-stale mark(Unknown) was applied; the effect cannot be verified. */
    data object Unknown : BbcEffectStatus

    /** Initial seed: no confirmed mark yet in this process; renders UNVERIFIED. */
    data object Provisional : BbcEffectStatus

    /**
     * A persisted value obtained from an authoritative IO-lane read (never a
     * write acknowledgement). [value] is a neutral checkbox seed only; the row
     * still renders UNVERIFIED and a later Confirmed mark always wins.
     */
    data class ProvisionalRead(val value: Boolean) : BbcEffectStatus

    /**
     * R5-02: a PROVISIONAL read that carries WHY there is no persisted value.
     * [runtime] is the four-state authoritative key read (ABSENT/FALSE/TRUE/
     * UNAVAILABLE); [value] is the definite value when there is one, otherwise
     * null. ABSENT is "no explicit opt-out" — for an AUTHORIZED install the
     * projection may seed checked=true, still UNVERIFIED. UNAVAILABLE must
     * never render checked=true. This is NEVER emitted by mark(): no write is
     * acknowledged, no sequence fence is advanced, and it never claims effect.
     */
    data class ProvisionalRuntimeRead(
        val runtime: BbcRuntimePrefValue,
        val value: Boolean?,
    ) : BbcEffectStatus
}

/** Pure presentation description; the Activity applies it to its own Views. */
internal data class BbcEffectPresentation(
    val summaryRes: Int,
    val dimmed: Boolean,
    val italic: Boolean,
)

/**
 * Map an effect status to the row presentation.
 *
 * A fresh process ([Provisional]), [Unknown] and an IO-lane [ProvisionalRead]
 * all render as UNVERIFIED, with the issue-#75 cold-start caveat: a read is not
 * a write acknowledgement. Only a later [Confirmed] — a persisted preference
 * acknowledgement, NOT effect proof — switches to the normal caveat-bearing
 * summary. Every presentation keeps the cold-start warning; switching never
 * removes it.
 */
internal fun bbcEffectPresentationFor(status: BbcEffectStatus): BbcEffectPresentation =
    when (status) {
        BbcEffectStatus.Unknown, BbcEffectStatus.Provisional,
        is BbcEffectStatus.ProvisionalRead, is BbcEffectStatus.ProvisionalRuntimeRead,
        -> BbcEffectPresentation(
            summaryRes = R.string.settings_bbc_injection_effect_unknown,
            dimmed = true,
            italic = true,
        )
        is BbcEffectStatus.Confirmed -> BbcEffectPresentation(
            summaryRes = R.string.settings_bbc_injection_summary,
            dimmed = false,
            italic = false,
        )
    }
