// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR5 Phase B2 (#80): pure decisions for the first-time BBC pre-activation opt-in.
// Framework-free on purpose: the Activity owns the dialog/window, but BOTH gates
// live here so JVM tests and production share a single source of truth.
//
// Contract:
// - New installs have no persisted consent (remote prefs key absent/failed read
//   => false), so the FIRST user-initiated ON must pass through an explicit
//   opt-in dialog. Turning OFF or toggling an already opted-in install never asks.
// - The dialog's positive action writes the consent key and only then may it
//   proceed to the existing BbcInjectionToggleController.onToggle(true). A
//   commit(false)/throw is tri-state Unknown: the optimistic RemotePreferences
//   mMap may already show the new value, so neither a re-read nor a cached value
//   may be used to claim success. Unknown => no onToggle (fail-closed).
// - This consent is about WHEN the user agreed, not a data-safety guarantee:
//   the BBC main effect remains experimental / not device-verified, and this is
//   NOT a guard against Samsung's cold-start deletion (issue #75).
package com.galaxydual.plus

/** True only for a user-initiated ON on an install that has not opted in yet. */
internal fun shouldRequestPreactivationOptIn(checked: Boolean, alreadyOptedIn: Boolean): Boolean =
    checked && !alreadyOptedIn

/**
 * True only when the opt-in preference write was confirmed persisted.
 *
 * `false` is tri-state Unknown (commit failed/threw): never proceed to the effect
 * write and never claim the opt-in took effect.
 */
internal fun shouldApplyBbcToggleAfterOptIn(optInWriteConfirmed: Boolean): Boolean =
    optInWriteConfirmed
