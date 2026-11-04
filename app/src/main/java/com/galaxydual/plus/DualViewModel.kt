// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.DualProfile
import com.galaxydual.core.DualRepository
import com.galaxydual.core.PmOutputParser
import com.galaxydual.core.ProfilesSnapshot
import com.galaxydual.core.RemoveOutcome

// UI state for the dual-app MVP screen; plain data with no Android types.
data class UiState(
    // All Android profiles parsed from `pm list users`.
    val profiles: List<DualProfile> = emptyList(),
    // Packages currently cloned into the dual profile (MVP tracks by name).
    val cloned: List<String> = emptyList(),
    // True while a repository call is in flight; UI should show progress.
    val loading: Boolean = false,
    // Last user-facing status or error message; empty means no message.
    val msg: String = "",
)

// Pure-Kotlin view model with no Android dependency (no Activity/ViewModel base).
// All shell work is delegated to DualRepository; this class only owns state.
//
// Phase C3 (issue #9): clone/remove go through the repository's pinned flows
// (cloneVerified/removeVerified: one snapshot, install/uninstall and re-check
// on the same dual id), and refresh rebuilds `cloned` from the device ground
// truth (`pm list packages --user <dualId>`) instead of trusting local state.
// Only a verify-confirmed install is added to `cloned`; only a
// verify-confirmed removal drops it.
class DualViewModel(private val repo: DualRepository) {
    // Current snapshot; mutated only via refresh/clone/remove below.
    var state: UiState = UiState()
        private set

    // Reloads profiles AND rebuilds `cloned` from the dual user's actual
    // packages. Three states are told apart explicitly (issue #9):
    // - ChannelError: `pm list users` itself failed -> "channel error: ...".
    // - NoDual: channel ok but no single DUAL profile -> "no dual profile".
    // - Empty: dual exists but owns no clones -> "no clones installed".
    // A failed clone-listing keeps the previous `cloned` and reports
    // "result unknown" rather than claiming the list is empty.
    suspend fun refresh() {
        // Mark busy first so the UI can render a spinner.
        state = state.copy(loading = true, msg = "")
        try {
            when (val snap = repo.profilesSnapshot()) {
                is ProfilesSnapshot.ChannelError ->
                    state = state.copy(
                        loading = false,
                        profiles = emptyList(),
                        msg = "channel error: ${snap.detail}",
                    )
                is ProfilesSnapshot.Loaded -> {
                    val profiles = snap.profiles
                    // Pure resolve on this snapshot: no second gateway call,
                    // so the id below cannot drift from what we display.
                    val dualId = PmOutputParser.resolveDualUserId(profiles)
                    if (dualId < 0) {
                        state = state.copy(
                            loading = false,
                            profiles = profiles,
                            cloned = emptyList(),
                            msg = "no dual profile",
                        )
                    } else {
                        val groundTruth = repo.listClonedPackages(dualId)
                        state = if (groundTruth == null) {
                            state.copy(
                                loading = false,
                                profiles = profiles,
                                msg = "refresh result unknown: cannot list clones for user $dualId",
                            )
                        } else if (groundTruth.isEmpty()) {
                            state.copy(
                                loading = false,
                                profiles = profiles,
                                cloned = emptyList(),
                                msg = "no clones installed",
                            )
                        } else {
                            state.copy(
                                loading = false,
                                profiles = profiles,
                                cloned = groundTruth,
                                msg = "",
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Gateways report via CmdResult, but guard against unexpected throws.
            state = state.copy(loading = false, msg = "refresh failed: ${e.message}")
        }
    }

    // Clones pkg into the dual profile; appends it to cloned ONLY when the
    // pinned re-check confirms it installed. A fake success (install ok, pkg
    // absent) or an uncheckable channel reports "verification failed" /
    // "result unknown" and leaves cloned untouched.
    suspend fun clone(pkg: String) {
        // Validate input early without touching the device.
        if (pkg.isBlank()) {
            state = state.copy(msg = "invalid package")
            return
        }
        val trimmed: String = pkg.trim()
        state = state.copy(loading = true, msg = "")
        try {
            // Single-snapshot pinned flow inside the repository.
            when (val res = repo.cloneVerified(trimmed)) {
                is CloneOutcome.Success -> {
                    val current: List<String> =
                        if (state.cloned.contains(trimmed)) state.cloned else state.cloned + trimmed
                    state = state.copy(loading = false, cloned = current, msg = "cloned: $trimmed")
                }
                is CloneOutcome.NoDual ->
                    state = state.copy(loading = false, msg = "no dual profile")
                is CloneOutcome.ChannelError ->
                    state = state.copy(loading = false, msg = "clone result unknown: channel error: ${res.detail}")
                is CloneOutcome.InvalidPackage ->
                    state = state.copy(loading = false, msg = "invalid package")
                is CloneOutcome.InstallFailed ->
                    state = state.copy(loading = false, msg = res.detail)
                is CloneOutcome.VerifyFailed ->
                    state = state.copy(
                        loading = false,
                        msg = "clone verification failed: $trimmed not installed for user ${res.dualId}",
                    )
                is CloneOutcome.VerifyUnknown ->
                    state = state.copy(
                        loading = false,
                        msg = "clone result unknown: cannot verify $trimmed for user ${res.dualId}",
                    )
            }
        } catch (e: Exception) {
            state = state.copy(loading = false, msg = "clone failed: ${e.message}")
        }
    }

    // Removes pkg from the dual profile; drops it from cloned ONLY when the
    // pinned re-check confirms it absent. Anything else (failed uninstall,
    // still present, uncheckable channel) keeps the entry: fail closed.
    suspend fun remove(pkg: String) {
        // Validate input early without touching the device.
        if (pkg.isBlank()) {
            state = state.copy(msg = "invalid package")
            return
        }
        val trimmed: String = pkg.trim()
        state = state.copy(loading = true, msg = "")
        try {
            // Single-snapshot pinned flow inside the repository.
            when (val res = repo.removeVerified(trimmed)) {
                is RemoveOutcome.Success ->
                    state = state.copy(
                        loading = false,
                        cloned = state.cloned.filterNot { it == trimmed },
                        msg = "removed: $trimmed",
                    )
                is RemoveOutcome.NoDual ->
                    state = state.copy(loading = false, msg = "no dual profile")
                is RemoveOutcome.ChannelError ->
                    state = state.copy(loading = false, msg = "remove result unknown: channel error: ${res.detail}")
                is RemoveOutcome.InvalidPackage ->
                    state = state.copy(loading = false, msg = "invalid package")
                is RemoveOutcome.RemoveFailed ->
                    state = state.copy(loading = false, msg = res.detail)
                is RemoveOutcome.StillInstalled ->
                    state = state.copy(
                        loading = false,
                        msg = "remove failed: $trimmed still installed for user ${res.dualId}",
                    )
                is RemoveOutcome.VerifyUnknown ->
                    state = state.copy(
                        loading = false,
                        msg = "remove result unknown: cannot verify removal of $trimmed for user ${res.dualId}",
                    )
            }
        } catch (e: Exception) {
            state = state.copy(loading = false, msg = "remove failed: ${e.message}")
        }
    }
}
