// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import com.galaxydual.core.DualProfile
import com.galaxydual.core.DualRepository
import com.galaxydual.core.PmOutputParser

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
class DualViewModel(private val repo: DualRepository) {
    // Current snapshot; mutated only via refresh/clone/remove below.
    var state: UiState = UiState()
        private set

    // Reloads the profile list from the device; failures surface via msg.
    suspend fun refresh() {
        // Mark busy first so the UI can render a spinner.
        state = state.copy(loading = true, msg = "")
        try {
            // Repository returns empty on channel failure; surface that explicitly.
            val profiles = repo.listProfiles()
            state = if (profiles.isEmpty()) {
                state.copy(loading = false, profiles = emptyList(), msg = "no profiles found")
            } else {
                state.copy(loading = false, profiles = profiles, msg = "")
            }
        } catch (e: Exception) {
            // Gateways report via CmdResult, but guard against unexpected throws.
            state = state.copy(loading = false, msg = "refresh failed: ${e.message}")
        }
    }

    // Clones pkg into the dual profile; on success appends it to cloned.
    suspend fun clone(pkg: String) {
        // Validate input early without touching the device.
        if (pkg.isBlank()) {
            state = state.copy(msg = "invalid package")
            return
        }
        state = state.copy(loading = true, msg = "")
        try {
            // DualRepository resolves the dual user id internally.
            val res = repo.clone(pkg.trim())
            val ok = PmOutputParser.isSuccess(res.out + res.err, res.exit)
            state = if (ok) {
                val current = if (state.cloned.contains(pkg.trim())) state.cloned else state.cloned + pkg.trim()
                state.copy(loading = false, cloned = current, msg = "cloned: ${pkg.trim()}")
            } else {
                val reason = res.err.ifEmpty { res.out.ifEmpty { "clone failed" } }
                state.copy(loading = false, msg = reason)
            }
        } catch (e: Exception) {
            state = state.copy(loading = false, msg = "clone failed: ${e.message}")
        }
    }

    // Removes pkg from the dual profile; on success drops it from cloned.
    suspend fun remove(pkg: String) {
        // Validate input early without touching the device.
        if (pkg.isBlank()) {
            state = state.copy(msg = "invalid package")
            return
        }
        state = state.copy(loading = true, msg = "")
        try {
            // DualRepository resolves the dual user id internally.
            val res = repo.remove(pkg.trim())
            val ok = PmOutputParser.isSuccess(res.out + res.err, res.exit)
            state = if (ok) {
                state.copy(loading = false, cloned = state.cloned - pkg.trim(), msg = "removed: ${pkg.trim()}")
            } else {
                val reason = res.err.ifEmpty { res.out.ifEmpty { "remove failed" } }
                state = state.copy(loading = false, msg = reason)
            }
        } catch (e: Exception) {
            state = state.copy(loading = false, msg = "remove failed: ${e.message}")
        }
    }
}
