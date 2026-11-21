// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// D03a (PR #74 round 3): immutable consent token for the policy-write warning.
// Framework-free on purpose: only primitives / collections so it can live on
// DualViewModel.PendingPolicyWrite without introducing a VM -> Activity layering
// dependency on the Activity-local PreClearDualTarget.
//
// Visibility is `public` only because Kotlin forbids a public declaration
// (PendingPolicyWrite + the public VM request APIs) from exposing an `internal`
// parameter/property type; suppressing that diagnostic is unspecified behavior.
// This is an app-internal implementation detail: no external consumer exists and
// nothing here is a device-safety guarantee.
package com.galaxydual.plus

/**
 * Immutable snapshot of what the *first-stage warning* actually warned about.
 *
 * The token is carried from the warning dialog into [PendingPolicyWrite] and is
 * only about **control-flow freshness**: it lets a later confirm prove that the
 * target and the clone-installed snapshot the user was shown still hold. It is
 * NOT a device-safety guarantee and does not replace the VM's authoritative
 * re-read / revision fences.
 *
 * - [delta] is `old − candidate` (the packages whose clones are at risk). A
 *   defensive copy is taken at capture time.
 * - A null [capturedUserId] / [capturedSerial] means **no verified target was
 *   available at warning time**; validation MUST fail closed (never treat a
 *   missing identity as "nothing changed"). The warning path only ever produces
 *   a non-null pair from a FRESH-verified resolve, so a complete pair is the
 *   FRESH-verified marker carried forward.
 * - [capturedInstalled] is `probe.installed` at warning time (defensive copy).
 *   **An empty set is legitimate**: it is the `Ok(empty)` warning path where no
 *   clones were found; it must not be confused with "no snapshot captured".
 * - [generation] must match the generation of the [PendingPolicyWrite] that
 *   carries this token. The VM rebinds it at pending-install time (the UI builds
 *   the token before a pending exists).
 * - [fromFailedProbe] (D03b fixer M6): true iff the FIRST-STAGE warning's probe
 *   was `DualProbeResult.Failed` (i.e. the "仍然清除／寫入" force-proceed path).
 *   Such a consent can never authorize a write: the fence returns
 *   `CannotDetermine` regardless of what any later reprobe reports. Default
 *   false keeps legacy/device-unverified seams compiling.
 * - [rootVerifiedEmpty] (D03b fixer M2): true ONLY after the ROOT uninstall
 *   verification's success callbacks (`onClearVerified` / `onForceClear`).
 *   It skips ONLY the `capturedInstalled` membership comparison (the expected
 *   set is legitimately empty after uninstall); live + fresh (id,serial)
 *   identity checks are STILL enforced. Never a "ROOT => ProceedOk" shortcut.
 *   Default false; the non-ROOT Positive button never sets it.
 */
data class PolicyWriteConsent(
    val generation: Long,
    val delta: Set<String>,
    val capturedUserId: Int?,
    val capturedSerial: String?,
    val capturedInstalled: Set<String>,
    val fromFailedProbe: Boolean = false,
    val rootVerifiedEmpty: Boolean = false,
)
