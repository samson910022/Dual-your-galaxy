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
 * process", so a [Confirmed] status still literally means a confirmed mark was
 * observed. Like [Confirmed] it renders without the "effect unknown" styling,
 * but it is NOT a write confirmation and must never be reported as one.
 *
 * [Unknown] is produced ONLY by a successfully applied mark(Unknown). It says
 * nothing about which value the preference holds: a commit(false)/throw may
 * have polluted the local mMap, so Unknown is never a rollback or a restored
 * value. This is also NOT a Samsung cold-start deletion guard (issue #75).
 */
internal sealed interface BbcEffectStatus {
    /** A non-stale mark(Confirmed) was applied; [value] is the persisted value. */
    data class Confirmed(val value: Boolean) : BbcEffectStatus

    /** A non-stale mark(Unknown) was applied; the effect cannot be verified. */
    data object Unknown : BbcEffectStatus

    /** Initial seed: no confirmed mark yet; renders normally, but unconfirmed. */
    data object Provisional : BbcEffectStatus
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
 * Both summaries carry the issue-#75 cold-start caveat; switching between them
 * never removes that warning, it only stops claiming that the effect is
 * unverified once a later commit has actually been confirmed.
 */
internal fun bbcEffectPresentationFor(status: BbcEffectStatus): BbcEffectPresentation =
    when (status) {
        BbcEffectStatus.Unknown -> BbcEffectPresentation(
            summaryRes = R.string.settings_bbc_injection_effect_unknown,
            dimmed = true,
            italic = true,
        )
        is BbcEffectStatus.Confirmed, BbcEffectStatus.Provisional -> BbcEffectPresentation(
            summaryRes = R.string.settings_bbc_injection_summary,
            dimmed = false,
            italic = false,
        )
    }
