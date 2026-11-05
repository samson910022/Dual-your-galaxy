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
    // per the shared C2 isSuccess rule); detail is the stderr/out reason.
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
    // Phase C2 (issue #8): shares the isSuccess semantics with verify()/clone/
    // remove, so a `pm list users` that exits 0 but reports "Failure [...]"
    // on stderr is treated as failed (empty) instead of parsed as profiles.
    // UserInfo{...} lines never match the error patterns, so normal listings
    // are unaffected. Delegates to profilesSnapshot(); use that directly when
    // ChannelError must be told apart from a genuinely empty parse (C3).
    suspend fun listProfiles(): List<DualProfile> =
        when (val snap = profilesSnapshot()) {
            is ProfilesSnapshot.Loaded -> snap.profiles
            is ProfilesSnapshot.ChannelError -> emptyList()
        }

    // Same listing as listProfiles() but keeps the channel-failure signal:
    // ChannelError when the `pm list users` command itself fails the shared
    // C2 isSuccess rule, Loaded(parse) otherwise. Single gateway call.
    suspend fun profilesSnapshot(): ProfilesSnapshot {
        val res = gateway.run(CMD_LIST_USERS)
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
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
    // (both-present-and-unequal refuses, suspecting ID reuse); a null
    // resolve maps to the original "no dual profile" refusal.
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
    // reused by a different profile incarnation). Either side missing is
    // tolerated (Phase B upgrade path, same as the resolver): only a
    // positive disagreement refuses.
    // Returns a refusal CmdResult, or null when the id is safe to target.
    // Refuses dualId 0 (owner profile, must never be written) and any id not
    // present in this call's `pm list users` snapshot (stale/mistyped id),
    // plus any id that is not the currently Confirmed dual target (exists but
    // non-Dual: Work/Secondary/Secure Folder, renamed DUAL-label, type
    // conflict, property mismatch, or unknown). Existence alone never suffices.
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
            if (expected.serial != null && fresh.serial != null && expected.serial != fresh.serial) {
                return CmdResult(
                    exit = 1,
                    out = "",
                    err = "refused: id $dualId identity reuse suspected" +
                        " (serial changed expected=${expected.serial} fresh=${fresh.serial})",
                )
            }
            if (expected.parentId != null && fresh.parentId != null && expected.parentId != fresh.parentId) {
                return CmdResult(
                    exit = 1,
                    out = "",
                    err = "refused: id $dualId identity reuse suspected" +
                        " (parent changed expected=${expected.parentId} fresh=${fresh.parentId})",
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
    // `pm list packages` probe exits 0 AND the parsed set contains the
    // package (exact match); NotInstalled when the probe exits 0 but the
    // set lacks it (confirmed absent); Unknown when the id is
    // guard-refused, the package token is invalid, or the probe exits
    // non-zero (channel failure). Same verified-dual guard (stage 2) as the
    // explicit paths; the shared C2
    // isSuccess rule is intentionally NOT applied to the list probe here
    // (see verifyOnProfiles): only the result granularity is new.
    // Residual TOCTOU note: explicit-id entries check only fresh
    // self-consistency (expected = null); auto verify(pkg) carries expected.
    suspend fun verifyState(pkg: String, dualId: Int): VerifyState {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return VerifyState.Unknown(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        return verifyOnProfiles(pkg, dualId, profiles, null)
    }

    // Lists the dual profile's actual packages (ground truth for refresh).
    // Null means unknown: no verified dual profile, channel failure, or guard
    // refusal. Non-null (possibly empty) is authoritative: refresh rebuilds
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
    // requires same id plus both-present-equal serial/parentId before the
    // `pm list packages` re-check. A null/drifted after-target maps to
    // VerifyUnknown("identity-changed ...") fail closed (never Success, never
    // counted as cloned). Success is reported only when the re-check then
    // confirms Installed; a clean-but-absent re-check is VerifyFailed
    // (fake success, issue #9), a failed re-check channel is VerifyUnknown.
    // Neither is added to cloned. Unknown/Refused identity maps to NoDual
    // (fail closed); the pinned re-check itself does not re-resolve identity
    // beyond the before/after pin so the write and the check cannot drift to
    // different users.
    suspend fun cloneVerified(pkg: String): CloneOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return CloneOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val before = fetchVerifiedTarget()
        if (before == null) return CloneOutcome.NoDual
        val dualId = before.userId
        if (profiles.none { it.userId == dualId }) return CloneOutcome.NoDual
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
    // before/after identity pin as cloneVerified. Only a post-uninstall
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
    // same id, and agree on serial/parentId whenever both sides carry them.
    // Either side missing is tolerated (Phase B upgrade path); only a
    // positive disagreement (or a vanished/renamed target) drifts. Returns
    // the detail string (always containing "identity-changed") or null when
    // the pin holds.
    private fun checkTargetDrift(before: VerifiedTarget, after: VerifiedTarget?): String? {
        if (after == null) return "identity-changed: target vanished after write (before=${before.userId})"
        if (after.userId != before.userId) {
            return "identity-changed: id drifted before=${before.userId} after=${after.userId}"
        }
        if (before.serial != null && after.serial != null && before.serial != after.serial) {
            return "identity-changed: serial reuse suspected before=${before.serial} after=${after.serial}"
        }
        if (before.parentId != null && after.parentId != null && before.parentId != after.parentId) {
            return "identity-changed: parent reuse suspected before=${before.parentId} after=${after.parentId}"
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
    // Exit-only semantics: exit != 0 (after retries below) maps to Unknown
    // (detail kept); exit == 0 with the package present is Installed, and
    // exit == 0 without it is NotInstalled -- even when the output carries
    // an error-ish text. The shared C2 isSuccess rule is deliberately NOT
    // used here: absence text is absence evidence, and folding it into
    // Unknown would turn a confirmed removal into "unknown" (remove) or a
    // fake clone success into "unknown" instead of VerifyFailed (clone).
    // Every other flow (list/install/uninstall/packages) keeps isSuccess.
    //
    // Retry (retained): a non-zero list probe is retried at most twice with
    // a 1500 ms settle wait before reporting Unknown, covering transient
    // binder/timeout blips. gateway.run is a blocking suspend with no
    // dispatcher assumption, hence Thread.sleep (core has no
    // kotlinx-coroutines dependency for delay). Only three consecutive
    // non-zero exits map to Unknown.
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
        // (both-present-and-unequal -> Unknown reuse refusal); either side
        // missing is tolerated (Phase B upgrade path).
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) {
            return VerifyState.Unknown(
                "refused: id $dualId is not the verified dual target" +
                    " (verified=${fresh?.userId?.toString() ?: "none"})",
            )
        }
        if (expected != null) {
            if (expected.serial != null && fresh.serial != null && expected.serial != fresh.serial) {
                return VerifyState.Unknown(
                    "refused: id $dualId identity reuse suspected" +
                        " (serial changed expected=${expected.serial} fresh=${fresh.serial})",
                )
            }
            if (expected.parentId != null && fresh.parentId != null && expected.parentId != fresh.parentId) {
                return VerifyState.Unknown(
                    "refused: id $dualId identity reuse suspected" +
                        " (parent changed expected=${expected.parentId} fresh=${fresh.parentId})",
                )
            }
        }
        return verifyListProbe(pkg, dualId)
    }

    // List-only re-check for an already-verified id (pinned flows): runs the
    // `pm list packages --user <dualId>` probe WITHOUT re-resolving identity,
    // so the write and the check share one verified target and cannot drift.
    // All tri-state/exit-only/retry semantics are identical to the explicit
    // path below (PR-2 owns that contract; stage 2 only changes WHO may be
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
            return VerifyState.Unknown(e.message ?: "invalid package")
        }
        var res = gateway.run(cmd)
        var attempts = 1
        while (res.exit != 0 && attempts < 3) {
            Thread.sleep(1500)
            res = gateway.run(cmd)
            attempts++
        }
        if (res.exit != 0) {
            return VerifyState.Unknown(res.err.ifEmpty { res.out.ifEmpty { "verify failed (exit=${res.exit})" } })
        }
        return if (clean in PmOutputParser.parsePackages(res.out)) {
            VerifyState.Installed
        } else {
            VerifyState.NotInstalled
        }
    }

    // Runs `pm list packages --user <dualId>` for an already-pinned snapshot.
    // Guard-refused ids and failed listings yield null (unknown), never throw.
    // Stage 2 adds the verified-dual gate: existence alone never suffices.
    // Phase D ID-reuse protection: same both-present-and-unequal serial/parent
    // rule as verifyOnProfiles above; either side missing is tolerated.
    private suspend fun listOnProfiles(dualId: Int, profiles: List<DualProfile>, expected: VerifiedTarget? = null): List<String>? {
        if (dualId <= 0 || profiles.none { it.userId == dualId }) return null
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) return null
        if (expected != null) {
            if (expected.serial != null && fresh.serial != null && expected.serial != fresh.serial) return null
            if (expected.parentId != null && fresh.parentId != null && expected.parentId != fresh.parentId) return null
        }
        return listProbe(dualId)
    }

    // List-only probe for an already-verified id (auto listing path): no
    // identity re-resolution, same isSuccess ground-truth semantics as before.
    private suspend fun listProbe(dualId: Int): List<String>? {
        val cmd = try {
            PmOutputParser.buildListPackages(dualId)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val res = gateway.run(cmd)
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) return null
        return PmOutputParser.parsePackages(res.out)
    }
}
