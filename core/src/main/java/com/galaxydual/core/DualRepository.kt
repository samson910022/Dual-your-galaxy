// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Profile-list snapshot distinguishing channel failure from an empty parse.
// Phase C3 (issue #9): listProfiles() collapses both to emptyList by design
// (C2 contract, kept); callers that must tell "channel failed" apart from
// "channel ok" use profilesSnapshot() instead.
sealed interface ProfilesSnapshot {
    // `pm list users` parsed successfully (may still be an empty list).
    data class Loaded(val profiles: List<DualProfile>) : ProfilesSnapshot

    // The listing command itself failed (exit != 0 or error-semantic output
    // per the pm-list dedicated isListUsersOutputOk gate); detail is the
    // stderr/out reason.
    data class ChannelError(val detail: String) : ProfilesSnapshot
}

// Tri-state install check for one package on a pinned dual profile.
// Phase C3 (issue #9): the old Boolean verify() folds NotInstalled and
// Unknown together (both false, kept for compatibility). Callers that must
// display "confirmed absent" differently from "cannot check" use verifyState.
sealed interface VerifyState {
    // `pm list packages` ground truth contains the package (exact match).
    data object Installed : VerifyState

    // The list probe ran cleanly but the package is absent: confirmed absent.
    data object NotInstalled : VerifyState

    // The probe could not run (non-zero exit): no knowledge.
    data class Unknown(val detail: String = "") : VerifyState
}

// Outcome of a pinned clone: resolve once, install and verify on the same id.
// Only Success means the clone is confirmed present; every other outcome
// must NOT be added to the UI's cloned list.
sealed interface CloneOutcome {
    data class Success(val dualId: Int) : CloneOutcome
    data object NoDual : CloneOutcome
    data class ChannelError(val detail: String) : CloneOutcome
    data class InvalidPackage(val pkg: String) : CloneOutcome
    data class InstallFailed(val detail: String) : CloneOutcome

    // Install reported success but the pinned re-check shows the package
    // absent: suspected fake success, never treat as cloned.
    data class VerifyFailed(val dualId: Int) : CloneOutcome

    // Install reported success but the re-check channel failed: unknown.
    data class VerifyUnknown(val dualId: Int, val detail: String = "") : CloneOutcome
}

// Outcome of a pinned remove: resolve once, uninstall and re-check on the
// same id. Only Success (post-check confirms absent) counts as removed.
sealed interface RemoveOutcome {
    data class Success(val dualId: Int) : RemoveOutcome
    data object NoDual : RemoveOutcome
    data class ChannelError(val detail: String) : RemoveOutcome
    data class InvalidPackage(val pkg: String) : RemoveOutcome
    data class RemoveFailed(val detail: String) : RemoveOutcome

    // Uninstall reported success but the package is still present.
    data class StillInstalled(val dualId: Int) : RemoveOutcome

    // Uninstall reported success but the re-check channel failed: unknown,
    // must NOT be dropped from the UI's cloned list (fail closed).
    data class VerifyUnknown(val dualId: Int, val detail: String = "") : RemoveOutcome
}

// Outcome of a single-target refresh (Phase D P2-D, issue #21): one
// `pm list users` snapshot plus one verified-target fetch, then a list
// probe on that same id (no re-resolution). GroundTruth carries the pinned
// target so callers display the same id they listed; cloned == null means
// the list channel failed (unknown, keep the old display list).
sealed interface RefreshOutcome {
    data class ChannelError(val detail: String) : RefreshOutcome
    data class NoDual(val profiles: List<DualProfile>) : RefreshOutcome
    data class GroundTruth(
        val profiles: List<DualProfile>,
        val target: VerifiedTarget,
        val cloned: List<String>?,
    ) : RefreshOutcome
}

// Repository for dual-profile app lifecycle; all commands go through PmOutputParser.
// Pure Kotlin with no Android dependency; execution is delegated to CmdGateway.
class DualRepository(
    private val gateway: CmdGateway,
) {
    companion object {
        // Command used to enumerate profiles.
        const val CMD_LIST_USERS = "pm list users"

        // Verified-identity channels (PR-1 stage 2, issue #21): the two verbose
        // sources whose CLONE type tokens must agree, plus the Samsung dual
        // property. All are shell-readable on supported builds; when any typed
        // channel is unavailable the resolver refuses (fail closed) instead of
        // falling back to the pm-list label.
        const val CMD_USER_LIST_VERBOSE = "cmd user list -v"
        const val CMD_DUMPSYS_USER = "dumpsys user"
        const val CMD_GETPROP_DUAL = "getprop sys.dualapp.profile_id"
    }

    // Lists all profiles from `pm list users`; empty when the channel fails.
    // Phase C2 (issue #8) + P2-C: the pm-list dedicated gate
    // (isListUsersOutputOk: exit-first, header-stripped, no install-command
    // exemptions), so a `pm list users` that exits 0 but reports "Failure [...]"
    // on stderr is treated as failed (empty) instead of parsed as profiles,
    // while an Error/Failure-labelled UserInfo header stays display-only.
    // UserInfo{...} headers never participate in error matching, so normal
    // listings are unaffected. Delegates to profilesSnapshot(); use that directly when
    // ChannelError must be told apart from a genuinely empty parse (C3).
    suspend fun listProfiles(): List<DualProfile> =
        when (val snap = profilesSnapshot()) {
            is ProfilesSnapshot.Loaded -> snap.profiles
            is ProfilesSnapshot.ChannelError -> emptyList()
        }

    // Same listing as listProfiles() but keeps the channel-failure signal:
    // ChannelError when the `pm list users` command itself fails the pm-list
    // dedicated gate (isListUsersOutputOk: exit-first, header-stripped,
    // no install-command exemptions, so an Error/Failure-labelled UserInfo
    // header is display-only and never fails the channel), Loaded(parse)
    // otherwise. Single gateway call.
    suspend fun profilesSnapshot(): ProfilesSnapshot {
        val res = gateway.run(CMD_LIST_USERS)
        if (!PmOutputParser.isListUsersOutputOk(res.out + "\n" + res.err, res.exit)) {
            return ProfilesSnapshot.ChannelError(res.err.ifEmpty { res.out.ifEmpty { "channel failed" } })
        }
        return ProfilesSnapshot.Loaded(PmOutputParser.parseUsers(res.out))
    }

    // Resolves the dual profile id via the verified cross-source boundary
    // (PR-1 stage 2, issue #21): `cmd user list -v` + `dumpsys user` must agree
    // on one CLONE-typed non-zero id (SamsungProfileAdapter.resolveDual);
    // the `pm list users` label is never an authorization source. Returns the
    // Confirmed id, or -1 when Unknown/Refused (fail closed). No "first
    // non-zero user" fallback by design.
    suspend fun resolveDualUserId(): Int = fetchVerifiedDualId()

    // Explicit verified entry: compat alias for resolveDualUserId() (the
    // canonical entry). Both run the same shared fetch; this overload exists
    // only so existing callers keep compiling.
    // For pure parsing without I/O use
    // PmOutputParser.resolveDualUserIdVerified(userListVerbose, dumpsysUser,
    // propertyId) directly.
    suspend fun resolveDualUserIdVerified(): Int = resolveDualUserId()

    // Shared identity fetch: delegates to the Phase-2 shared fetcher
    // (see VerifiedIdentity.kt), the single source every resolver and both
    // Elevator probes share. Method name kept so callers keep compiling.
    // Never throws: gateway errors collapse to -1 (fail closed); only
    // coroutine cancellation is rethrown by the shared fetcher itself.
    private suspend fun fetchVerifiedDualId(): Int = gateway.fetchVerifiedDualId()

    // Phase D target fetch: same shared fetch as above but preserves the
    // serial/parentId continuity signals for ID-reuse protection. Null means
    // Unknown/Refused/channel failure (fail closed).
    private suspend fun fetchVerifiedTarget(): VerifiedTarget? = gateway.fetchVerifiedTarget()

    // Clones a package into the dual profile via `pm install-existing`.
    // Auto path carries the resolve-time identity as `expected`: the fresh
    // re-check inside the write must still agree on serial/parentId
    // (both-present-and-unequal refuses, suspecting ID reuse; a known
    // expected.serial followed by a missing fresh.serial refuses as
    // continuity lost); a null resolve maps to the original
    // "no dual profile" refusal.
    suspend fun clone(pkg: String): CmdResult {
        val expected = fetchVerifiedTarget()
        if (expected == null) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return clone(pkg, expected.userId, expected)
    }

    // Clones a package into an explicit dual profile id.
    // Verified-dual guard: refuses dualId 0 (owner), ids absent from the
    // current `pm list users` snapshot, and ids that are not the Confirmed
    // cross-source target, so a stale, mistyped, or non-Dual id can never
    // redirect the write at the wrong profile.
    // Residual TOCTOU note: the explicit-id entry checks only fresh
    // self-consistency (expected = null); a profile deleted and recreated
    // under the same id between this check and the write is not detected
    // here. Auto/pinned flows carry `expected` (or re-check after the
    // write) to close that window; explicit callers accept the residual.
    suspend fun clone(pkg: String, dualId: Int): CmdResult = clone(pkg, dualId, null)

    // Private write carrying the resolve-time identity for ID-reuse
    // protection (Phase D): `expected` is the auto-path target; the fresh
    // target must still agree on serial/parentId (see checkExplicitDualId).
    private suspend fun clone(pkg: String, dualId: Int, expected: VerifiedTarget?): CmdResult {
        checkExplicitDualId(dualId, expected)?.let { return it }
        val cmd = PmOutputParser.buildInstallExisting(pkg, dualId)
        return gateway.run(cmd)
    }

    // Removes the clone from the dual profile via `pm uninstall --user`.
    // Auto path carries the resolve-time identity as `expected` (same
    // ID-reuse guard as clone(pkg) above).
    suspend fun remove(pkg: String): CmdResult {
        val expected = fetchVerifiedTarget()
        if (expected == null) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return remove(pkg, expected.userId, expected)
    }

    // Removes the clone from an explicit dual profile id.
    // Same verified-dual guard as clone(pkg, dualId): see checkExplicitDualId.
    // Residual TOCTOU note: same as the explicit clone entry above
    // (expected = null, fresh self-consistency only).
    suspend fun remove(pkg: String, dualId: Int): CmdResult = remove(pkg, dualId, null)

    // Private write carrying the resolve-time identity (Phase D, same as
    // the private clone overload above).
    private suspend fun remove(pkg: String, dualId: Int, expected: VerifiedTarget?): CmdResult {
        checkExplicitDualId(dualId, expected)?.let { return it }
        val cmd = PmOutputParser.buildUninstall(pkg, dualId)
        return gateway.run(cmd)
    }

    // Validates an explicit dual profile id against the current snapshot AND the
    // verified cross-source identity (PR-1 stage 2, issue #21), plus Phase D
    // ID-reuse protection: when `expected` (the resolve-time target carried
    // by auto paths) is non-null and the fresh target still names the same
    // id, both-present-and-unequal serial/parentId refuses (the id was
    // reused by a different profile incarnation). Cross-fetch serial
    // continuity (Phase A P1-A, issue #21): a known expected.serial followed
    // by a missing fresh.serial also refuses (continuity/reuse suspected,
    // executed before any write command); expected==null or
    // expected.serial==null stays tolerated (Phase B upgrade path, same as
    // the single-fetch resolver tolerance). Only a positive disagreement or
    // a known-to-unknown serial loss refuses.
    // Parent needs no known-to-unknown rule: the resolver already requires
    // both verbose channels to carry a known, equal parentId == 0 before it
    // Confirms, so any non-null fresh target carries parentId non-null and
    // == 0 by construction -- a known-to-unknown parent transition is
    // unrepresentable while the id still Confirms (fresh==null or id drift
    // already refuses above).
    // Returns a refusal CmdResult, or null when the id is safe to target.
    // Refuses dualId 0 (owner profile, must never be written) and any id not
    // present in this call's `pm list users` snapshot (stale/mistyped id),
    // plus any id that is not the currently Confirmed dual target (exists but
    // non-Dual: Work/Secondary/Secure Folder, renamed DUAL-label, type
    // conflict, property mismatch, or unknown). Existence alone never suffices.
    // Phase D + Phase A P1-A continuity helper (issue #21): centralizes the
    // serial/parent cross-fetch comparison shared by checkExplicitDualId,
    // verifyOnProfiles, listOnProfiles and checkTargetDrift. userId is NOT
    // compared here (callers check id drift/vanish separately). Returns null
    // when every comparable signal agrees; otherwise returns the breach
    // reason (non-null means breach). Serial known->unknown is continuity
    // lost; both-present-and-unequal serial/parent each breach. Callers wrap
    // with "refused: ..." (auto paths) or "identity-changed: ..." (pinned
    // drift) so existing reason tokens ("reuse"/"continuity"/
    // "identity-changed") are preserved and external outcomes (refused exit
    // 1 / Unknown / null / VerifyUnknown) are unchanged.
    private fun continuityBreach(expected: VerifiedTarget, fresh: VerifiedTarget): String? {
        if (expected.serial != null && fresh.serial == null) {
            return "continuity lost: serial known expected=${expected.serial} but fresh missing"
        }
        if (expected.serial != null && fresh.serial != null && expected.serial != fresh.serial) {
            return "serial changed expected=${expected.serial} fresh=${fresh.serial}"
        }
        if (expected.parentId != null && fresh.parentId != null && expected.parentId != fresh.parentId) {
            return "parent changed expected=${expected.parentId} fresh=${fresh.parentId}"
        }
        return null
    }

    private suspend fun checkExplicitDualId(dualId: Int, expected: VerifiedTarget? = null): CmdResult? {
        if (dualId <= 0) {
            return CmdResult(
                exit = 1,
                out = "",
                err = "refused: invalid dual profile id ($dualId), owner profile is off-limits",
            )
        }
        val snapshot = listProfiles()
        if (snapshot.none { it.userId == dualId }) {
            return CmdResult(
                exit = 1,
                out = "",
                err = "refused: unknown dual profile id ($dualId), not in current snapshot",
            )
        }
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) {
            return CmdResult(
                exit = 1,
                out = "",
                err = "refused: id $dualId is not the verified dual target" +
                    " (verified=${fresh?.userId?.toString() ?: "none"})",
            )
        }
        if (expected != null) {
            continuityBreach(expected, fresh)?.let { breach ->
                return CmdResult(
                    exit = 1,
                    out = "",
                    err = "refused: id $dualId identity reuse suspected" +
                        " ($breach)",
                )
            }
        }
        return null
    }

    // Double-checks installation via `pm list packages --user <dualId>`
    // ground truth (exact membership). Returns true only when the probe
    // exits 0 and the parsed set contains the package. Auto path carries
    // the resolve-time identity as `expected` (Phase D ID-reuse guard);
    // a null resolve maps to false (same as the old -1 behavior).
    suspend fun verify(pkg: String): Boolean {
        val expected = fetchVerifiedTarget() ?: return false
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return false
        return verifyOnProfiles(pkg, expected.userId, (snap as ProfilesSnapshot.Loaded).profiles, expected) is VerifyState.Installed
    }

    // Verifies against an explicit dual profile id.
    // Same verified-dual guard as clone(pkg, dualId)/remove(pkg, dualId):
    // refuses dualId 0 (owner), ids absent from the current snapshot, and ids
    // that are not the currently Confirmed dual target (exists but non-Dual)
    // without running any command (returns false). Read-only path, but
    // consistency first: a stale, mistyped, or non-Dual id must never be
    // probed, and callers get graceful false instead of an
    // IllegalArgumentException from the command builder.
    // Residual TOCTOU note: explicit-id entries check only fresh
    // self-consistency (expected = null); see the explicit clone entry.
    //
    // DEPRECATED (PR-1 stage 2, issue #21): the Boolean folds NotInstalled and
    // Unknown together (both false). Callers that must tell "confirmed absent"
    // apart from "cannot check" must use verifyState(pkg, dualId) instead
    // (tri-state contract is owned by PR-2, issue #22, and is unchanged here).
    @Deprecated(
        message = "Boolean verify folds NotInstalled and Unknown; use verifyState for tri-state.",
        ReplaceWith("verifyState(pkg, dualId)"),
    )
    suspend fun verify(pkg: String, dualId: Int): Boolean =
        when (val state = verifyState(pkg, dualId)) {
            is VerifyState.Installed -> true
            is VerifyState.NotInstalled -> false
            is VerifyState.Unknown -> false
        }

    // Tri-state variant of verify(pkg, dualId): Installed only when the
    // `pm list packages` probe parses as TrustedFull containing the package
    // (exact match); NotInstalled on a whitelist-clean listing that lacks
    // it (TrustedFull of other packages, or TrustedEmpty: confirmed absent);
    // Unknown when the id is guard-refused, the package token is invalid, or
    // the probe parses as Unknown (truncated capture, non-zero exit after
    // retries, error-semantic text per the shared C2 line rule, or
    // unexpected-format/unexplained-stderr residue per the trusted
    // structural whitelist). C1 guard is unchanged; the mapping itself is
    // owned by verifyListProbe (PR-2 stage 2, issue #22).
    // Residual TOCTOU note: explicit-id entries check only fresh
    // self-consistency (expected = null); auto verify(pkg) carries expected.
    suspend fun verifyState(pkg: String, dualId: Int): VerifyState {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return VerifyState.Unknown(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        return verifyOnProfiles(pkg, dualId, profiles, null)
    }

    // Lists the dual profile's actual packages (ground truth for refresh).
    // Null means unknown: no verified dual profile, channel failure, guard
    // refusal, or an untrusted listing (truncated / non-zero / error-semantic:
    // never an authoritative empty). Non-null (possibly empty) is
    // authoritative: refresh rebuilds
    // `cloned` from it, so external installs/removals sync and a fresh
    // ViewModel discovers pre-existing clones. Uses `pm list packages
    // --user <dualId>` (see PmOutputParser.buildListPackages); per-package
    // `pm path` probing alone could never discover names nobody asked about.
    // Resolution is verified cross-source (stage 2): a DUAL-labeled but
    // untyped id never authorizes a listing. Auto path carries the
    // resolve-time identity as `expected` (Phase D ID-reuse guard).
    suspend fun listClonedPackages(): List<String>? {
        val expected = fetchVerifiedTarget() ?: return null
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return null
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        if (profiles.none { it.userId == expected.userId }) return null
        return listOnProfiles(expected.userId, profiles, expected)
    }

    // Explicit-id variant with the same verified-dual guard (stage 2) as clone(pkg, dualId):
    // dualId 0 and ids absent from this call's snapshot yield null (unknown)
    // without probing; an invalid builder token also yields null.
    // Residual TOCTOU note: explicit-id entries check only fresh
    // self-consistency (expected = null); auto listClonedPackages() carries expected.
    suspend fun listClonedPackages(dualId: Int): List<String>? {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return null
        return listOnProfiles(dualId, (snap as ProfilesSnapshot.Loaded).profiles, null)
    }

    // Pinned clone (Phase C3, issue #9; Phase D target pinning): captures the
    // verified target BEFORE the write, then re-fetches AFTER the write and
    // requires same id plus serial/parentId continuity (both-present-equal;
    // a known before.serial followed by a missing after.serial is drift)
    // before the `pm list packages` re-check. A pre-write fresh-vs-before re-check narrows
    // the before-to-write reuse window (a residual fresh-to-write micro-window
    // remains, fail closed): drift returns VerifyUnknown without
    // emitting any write command (fail closed). A null/drifted after-target maps to
    // VerifyUnknown("identity-changed ...") fail closed (never Success, never
    // counted as cloned). Success is reported only when the re-check then
    // confirms Installed; a clean-but-absent re-check is VerifyFailed
    // (fake success, issue #9), a failed re-check channel is VerifyUnknown.
    // Neither is added to cloned. Unknown/Refused identity maps to NoDual
    // (fail closed); the pinned re-check itself does not re-resolve identity
    // beyond the before/fresh/after pin so a write can never be redirected to
    // a different user (the after-to-probe window is read-only, see below).
    suspend fun cloneVerified(pkg: String): CloneOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return CloneOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val before = fetchVerifiedTarget()
        if (before == null) return CloneOutcome.NoDual
        val dualId = before.userId
        if (profiles.none { it.userId == dualId }) return CloneOutcome.NoDual
        val fresh = fetchVerifiedTarget()
        val preDrift = checkTargetDrift(before, fresh)
        if (preDrift != null) return CloneOutcome.VerifyUnknown(dualId, preDrift)
        val clean = pkg.trim()
        val installCmd = try {
            PmOutputParser.buildInstallExisting(clean, dualId)
        } catch (e: IllegalArgumentException) {
            return CloneOutcome.InvalidPackage(clean)
        }
        val res = gateway.run(installCmd)
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
            return CloneOutcome.InstallFailed(res.err.ifEmpty { res.out.ifEmpty { "clone failed" } })
        }
        val after = fetchVerifiedTarget()
        val drift = checkTargetDrift(before, after)
        if (drift != null) return CloneOutcome.VerifyUnknown(dualId, drift)
        return when (val state = verifyListProbe(clean, dualId)) {
            is VerifyState.Installed -> CloneOutcome.Success(dualId)
            is VerifyState.NotInstalled -> CloneOutcome.VerifyFailed(dualId)
            is VerifyState.Unknown -> CloneOutcome.VerifyUnknown(dualId, state.detail)
        }
    }

    // Pinned remove (Phase C3, issue #9; Phase D target pinning): same
    // before/fresh/after identity pin as cloneVerified. A pre-write fresh-vs-before
    // re-check narrows the before-to-write reuse window (residual micro-window
    // remains, fail closed): drift returns
    // VerifyUnknown without emitting any write command (fail closed).
    // Only a post-uninstall
    // re-check of NotInstalled counts as removed; still-present is
    // StillInstalled, an uncheckable channel or a drifted after-target is
    // VerifyUnknown (caller keeps the entry: fail closed). A failed uninstall
    // stays RemoveFailed by design (C2 scope: uninstall-side "Unknown
    // package" is not idempotent-success); refresh later syncs the display
    // from ground truth either way.
    suspend fun removeVerified(pkg: String): RemoveOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return RemoveOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val before = fetchVerifiedTarget()
        if (before == null) return RemoveOutcome.NoDual
        val dualId = before.userId
        if (profiles.none { it.userId == dualId }) return RemoveOutcome.NoDual
        val fresh = fetchVerifiedTarget()
        val preDrift = checkTargetDrift(before, fresh)
        if (preDrift != null) return RemoveOutcome.VerifyUnknown(dualId, preDrift)
        val clean = pkg.trim()
        val uninstallCmd = try {
            PmOutputParser.buildUninstall(clean, dualId)
        } catch (e: IllegalArgumentException) {
            return RemoveOutcome.InvalidPackage(clean)
        }
        val res = gateway.run(uninstallCmd)
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
            return RemoveOutcome.RemoveFailed(res.err.ifEmpty { res.out.ifEmpty { "remove failed" } })
        }
        val after = fetchVerifiedTarget()
        val drift = checkTargetDrift(before, after)
        if (drift != null) return RemoveOutcome.VerifyUnknown(dualId, drift)
        return when (val state = verifyListProbe(clean, dualId)) {
            is VerifyState.NotInstalled -> RemoveOutcome.Success(dualId)
            is VerifyState.Installed -> RemoveOutcome.StillInstalled(dualId)
            is VerifyState.Unknown -> RemoveOutcome.VerifyUnknown(dualId, state.detail)
        }
    }

    // Phase D drift check for pinned flows: after must be non-null, name the
    // same id, and agree on serial/parentId on positive disagreement plus
    // serial known-to-unknown continuity. A known before.serial followed by
    // a missing after.serial drifts (identity-changed ... continuity lost,
    // fail closed). Either-side-missing is otherwise tolerated only for the
    // parent channel and for both-null serials (Phase B single-fetch
    // resolver tolerance, unchanged). Parent needs no known-to-unknown
    // rule: the resolver already requires both verbose channels to carry a
    // known, equal parentId == 0 before it Confirms, so any non-null after
    // target carries parentId non-null and == 0 by construction. Returns
    // the detail string (always containing "identity-changed") or null when
    // the pin holds.
    private fun checkTargetDrift(before: VerifiedTarget, after: VerifiedTarget?): String? {
        if (after == null) return "identity-changed: target vanished after write (before=${before.userId})"
        if (after.userId != before.userId) {
            return "identity-changed: id drifted before=${before.userId} after=${after.userId}"
        }
        continuityBreach(before, after)?.let { breach ->
            val detail = when {
                breach.startsWith("continuity lost") ->
                    "identity-changed: serial continuity lost before=${before.serial} after missing (reuse suspected)"
                breach.startsWith("serial changed") ->
                    "identity-changed: serial reuse suspected before=${before.serial} after=${after.serial}"
                else ->
                    "identity-changed: parent reuse suspected before=${before.parentId} after=${after.parentId}"
            }
            return detail
        }
        return null
    }

    // Runs the `pm list packages --user <dualId>` re-check for an
    // already-pinned id WITHOUT re-listing profiles: containment was
    // established by this call's own snapshot, so no second guess can drift
    // to another user. Invalid package tokens and builder refusals map to
    // Unknown (fail closed), never throw.
    //
    // Probe choice (third round, real-device proven): the re-check uses
    // `pm list packages --user <dualId>` (buildListPackages) plus exact
    // `pkg in parsePackages(out)` membership -- NOT `pm path --user N <pkg>`.
    // Right after `pm uninstall` succeeds, `pm path` can fail consecutively
    // with non-zero exit and empty out/err inside the teardown settling
    // window (three back-to-back probes all non-zero: no retry count saves
    // it), while `pm list packages` on the same window stays deterministic
    // ground truth (exit 0 with the package simply absent). Membership is an
    // exact set lookup so a superstring never mis-hits (com.example.app2
    // does not imply com.example.app).
    //
    // Trusted tri-state semantics (PR-2 stage 2, issue #22): the final probe
    // CmdResult is judged by parsePackagesTrusted. A clean listing decides
    // presence by exact membership (Installed) or confirmed absence
    // (NotInstalled -- TrustedEmpty, or TrustedFull of other packages); an
    // untrusted capture is Unknown (detail kept: exit code, truncation, or
    // the error-semantic line). In particular an exit-0 "Unknown package" /
    // SecurityException / Failure text is Unknown, NOT confirmed-absent: only
    // a format-clean listing is absence evidence. Install/uninstall-side
    // channels keep isSuccess; only this list probe and the refresh listing
    // use the trusted rule.
    //
    // Retry (frozen, PR-3 owns any change -- same policy as listProbe below):
    // a non-zero list probe is retried at most twice with a 1500 ms settle
    // wait before the trusted parse, covering transient binder/timeout blips.
    // gateway.run is a blocking suspend with no dispatcher assumption, hence
    // Thread.sleep (core has no kotlinx-coroutines dependency for delay).
    private suspend fun verifyOnProfiles(pkg: String, dualId: Int, profiles: List<DualProfile>, expected: VerifiedTarget? = null): VerifyState {
        if (dualId <= 0 || profiles.none { it.userId == dualId }) {
            return VerifyState.Unknown("refused: unknown dual profile id ($dualId)")
        }
        // Verified-dual gate (stage 2): existence alone never suffices. The id
        // must equal the currently Confirmed cross-source target; a present
        // but non-Dual id (Work/Secondary/Secure Folder, renamed DUAL-label,
        // type conflict, property mismatch) maps to Unknown (fail closed),
        // never to a probe of the wrong user.
        // Phase D ID-reuse protection: when `expected` is non-null, a fresh
        // target naming the same id must still agree on serial/parentId
        // (both-present-and-unequal -> Unknown reuse refusal; a known
        // expected.serial followed by a missing fresh.serial -> Unknown
        // continuity-lost refusal, no probe). expected==null or
        // expected.serial==null stays tolerated (Phase B single-fetch
        // resolver tolerance). Parent needs no known-to-unknown rule (see
        // checkExplicitDualId: a Confirmed fresh target always carries
        // parentId == 0).
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) {
            return VerifyState.Unknown(
                "refused: id $dualId is not the verified dual target" +
                    " (verified=${fresh?.userId?.toString() ?: "none"})",
            )
        }
        if (expected != null) {
            continuityBreach(expected, fresh)?.let { breach ->
                return VerifyState.Unknown(
                    "refused: id $dualId identity reuse suspected" +
                        " ($breach)",
                )
            }
        }
        return verifyListProbe(pkg, dualId)
    }

    // List-only re-check for an already-verified id (pinned flows): runs the
    // `pm list packages --user <dualId>` probe WITHOUT re-resolving identity.
    // The write is already pinned to the same id (before/fresh/after pin);
    // the probe itself performs no resolve, so a residual after-to-probe
    // window remains. It is read-only and fail-closed in the misread
    // direction: a drifted target can only misreport presence/absence, never
    // redirect a write to another user.
    // All tri-state/trusted-parse/retry semantics are identical to the explicit
    // path below (PR-2 owns that contract; #21 only changes WHO may be
    // probed, never HOW the probe is judged).
    private suspend fun verifyListProbe(pkg: String, dualId: Int): VerifyState {
        val clean = pkg.trim()
        try {
            ShellEscaper.escapePackage(clean)
        } catch (e: IllegalArgumentException) {
            return VerifyState.Unknown(e.message ?: "invalid package")
        }
        val cmd = try {
            PmOutputParser.buildListPackages(dualId)
        } catch (e: IllegalArgumentException) {
            // Unreachable in practice: dualId was already verified/pinned, so
            // only an invalid userId could throw here (never a package token).
            return VerifyState.Unknown(e.message ?: "invalid userId")
        }
        var res = gateway.run(cmd)
        var attempts = 1
        while (res.exit != 0 && attempts < 3) {
            Thread.sleep(1500)
            res = gateway.run(cmd)
            attempts++
        }
        // Trusted tri-state (PR-2 stage 2, issue #22): the final probe result
        // is judged by parsePackagesTrusted, not by exit code alone. A clean
        // full listing (buildListPackages has no filter parameter) that lacks
        // the package -- TrustedEmpty, or TrustedFull of other packages --
        // is confirmed absent (NotInstalled); only such a full listing may
        // use this mapping. An untrusted capture (truncated,
        // non-zero after the retries above, or error-semantic text such as
        // "Unknown package"/SecurityException/Failure with exit 0) is
        // Unknown. Membership stays an exact set lookup (superstrings never
        // hit). Retry/sleep policy is frozen (PR-3 owns it; same policy as
        // the verifyOnProfiles note above).
        return PmOutputParser.parsePackagesTrusted(res).toVerify(clean)
    }

    // Runs `pm list packages --user <dualId>` for an already-pinned snapshot.
    // Guard-refused ids and untrusted listings yield null (unknown), never throw.
    // Stage 2 adds the verified-dual gate: existence alone never suffices;
    // the trusted parse (#22) is owned by listProbe.
    // Phase D ID-reuse protection: same serial/parent rule as
    // verifyOnProfiles above (both-present-and-unequal -> null; known
    // expected.serial followed by missing fresh.serial -> null continuity
    // loss, no probe). expected==null or expected.serial==null stays
    // tolerated. Parent needs no known-to-unknown rule (see
    // checkExplicitDualId).
    private suspend fun listOnProfiles(dualId: Int, profiles: List<DualProfile>, expected: VerifiedTarget? = null): List<String>? {
        if (dualId <= 0 || profiles.none { it.userId == dualId }) return null
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) return null
        if (expected != null && continuityBreach(expected, fresh) != null) return null
        return listProbe(dualId)
    }

    // List-only probe for an already-verified id (auto listing path): no
    // identity re-resolution. Trusted tri-state (PR-2 stage 2, issue #22):
    // TrustedFull yields its packages, TrustedEmpty yields confirmed-empty
    // (emptyList, authoritative), and Unknown (truncated / non-zero /
    // error-semantic) yields null -- never an authoritative empty list, so
    // refresh keeps the previous `cloned` instead of wiping it.
    // No retry here by design (frozen, PR-3 owns any change -- same policy
    // as verifyOnProfiles above): refresh maps null to "keep old value", so
    // a transient blip costs one stale frame, not data loss.
    private suspend fun listProbe(dualId: Int): List<String>? {
        val cmd = try {
            PmOutputParser.buildListPackages(dualId)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val res = gateway.run(cmd)
        return PmOutputParser.parsePackagesTrusted(res).toPackageList()
    }

    // Shared TrustedList mappings (PR-2 Phase 2 dedup): single place owning
    // HOW a trusted parse becomes a probe result (#21 only changes WHO may be
    // probed). Pure, no I/O, no retry/sleep policy.
    // Trusted -> VerifyState: clean listing decides by exact membership
    // (Installed) or confirmed absence (NotInstalled); untrusted is Unknown.
    private fun TrustedList.toVerify(clean: String): VerifyState = when (this) {
        is TrustedList.TrustedFull ->
            if (clean in packages) VerifyState.Installed else VerifyState.NotInstalled
        is TrustedList.TrustedEmpty -> VerifyState.NotInstalled
        is TrustedList.Unknown -> VerifyState.Unknown(detail)
    }

    // Trusted -> authoritative list: clean listing yields its packages
    // (possibly empty); untrusted yields null (unknown, never empty).
    private fun TrustedList.toPackageList(): List<String>? = when (this) {
        is TrustedList.TrustedFull -> packages
        is TrustedList.TrustedEmpty -> emptyList()
        is TrustedList.Unknown -> null
    }

    // Single-target refresh (Phase D P2-D, issue #21): exactly one
    // profilesSnapshot() (1 cmd) then one fetchVerifiedTarget() (3 cmds:
    // verbose + dumpsys + property) then one listProbe(target.userId)
    // (1 cmd, no re-resolution) = 5 commands total (was 9 via the old
    // resolve-then-reverify listing path). Single target throughout, so
    // resolve-to-list drift is inexpressible in this API: the listed id is
    // always the resolved id. ChannelError early-returns; a null target or
    // a target absent from the snapshot maps to NoDual(profiles) (profiles
    // still carried for display); otherwise GroundTruth with cloned null
    // when the list channel fails.
    suspend fun refreshVerified(): RefreshOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return RefreshOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val target = fetchVerifiedTarget() ?: return RefreshOutcome.NoDual(profiles)
        if (profiles.none { it.userId == target.userId }) return RefreshOutcome.NoDual(profiles)
        val cloned = listProbe(target.userId)
        return RefreshOutcome.GroundTruth(profiles, target, cloned)
    }
}
