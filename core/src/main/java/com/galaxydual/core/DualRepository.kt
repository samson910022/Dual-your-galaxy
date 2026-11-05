// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.Volatile

// Serial-bound dual target fetch result (PR-33 R2, P1): distinguishes a
// confirmed (id, serial) target from "no dual" and from "id confirmed but
// the serial cannot be established", so the two-phase remove confirm can
// refuse a possibly-recreated profile instead of uninstalling it.
sealed interface DualTargetResult {
    // Both verbose channels agree on the id AND on one non-null serial.
    data class Confirmed(val target: VerifiedDualTarget) : DualTargetResult

    // The id itself is not confirmed (Unknown/Refused, or a failed channel).
    data object NoDual : DualTargetResult

    // The id is confirmed but no single agreed serial exists (a channel
    // carries no serial line, or the two serials disagree).
    data object SerialUnavailable : DualTargetResult
}

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

    /**
     * pinned 期望與當下 verified 不一致，零卸載，呼叫方應視為 INVALID 請重新確認。
     */
    data class TargetMoved(val expected: Int, val verified: Int) : RemoveOutcome

    /**
     * pinned serial 與當下 verified serial 不一致（或當下 serial 不可用），
     * 零卸載，呼叫方應視為 INVALID 請重新確認（與 TargetMoved 同式）。
     * null expected 表示呼叫方根本不可驗證（VM 必傳，此分支只應出現在程式化
     * 誤用）；null actual 表示當下目標無可用 serial。
     */
    data class SerialMismatch(val expected: Int?, val actual: Int?) : RemoveOutcome

    /**
     * confirm 前被取消，零卸載，呼叫方只清 loading 不覆寫 msg。
     */
    data object Cancelled : RemoveOutcome
}

// Repository for dual-profile app lifecycle; all commands go through PmOutputParser.
// Pure Kotlin with no Android dependency; execution is delegated to CmdGateway.
class DualRepository(
    private val gateway: CmdGateway,
) {
    // Retry settle wait (PR-3 stage 1, issue #24): the list-probe retry below
    // waits VERIFY_RETRY_DELAY_MS between attempts. Core has no
    // kotlinx-coroutines dependency, so this is a suspend function slot rather
    // than delay(): the default blocks the CALLER thread via Thread.sleep and
    // therefore callers must dispatch repository calls off the Main thread
    // (DualViewModel runs every repo call under Dispatchers.IO). Tests replace
    // it with a no-op/virtual delay to skip the real 1500 ms waits.
    // TEST-ONLY: production must not overwrite. @Volatile gives visibility
    // across threads (test thread writes, IO thread reads) but no atomicity;
    // concurrent overwrite is not safe -- set once before use, never at runtime.
    @Volatile
    internal var delayFn: suspend (Long) -> Unit = { ms -> Thread.sleep(ms) }

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

        // Settle wait between list-probe retries (PR-3 stage 1, issue #24).
        // Part of the retry policy (bounded: at most 2 waits per probe); the
        // wait itself runs through delayFn so tests can go virtual.
        const val VERIFY_RETRY_DELAY_MS = 1500L

        // Total list-probe attempts per verify (PR-3 stage 2, issue #24):
        // 1 initial + 2 retries, i.e. at most 2 waits.
        const val VERIFY_MAX_ATTEMPTS = 3
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

    // Explicit verified entry (same fetch as resolveDualUserId): raw verbose
    // outputs are gateway-owned, so this suspend variant fetches them itself.
    // For pure parsing without I/O use
    // PmOutputParser.resolveDualUserIdVerified(userListVerbose, dumpsysUser,
    // propertyId) directly.
    suspend fun resolveDualUserIdVerified(): Int = fetchVerifiedDualId()

    // Single shared identity fetch: runs both verbose channels plus the dual
    // property, then maps Confirmed -> id, Unknown/Refused -> -1. A failed
    // typed channel (non-zero exit or error-semantic output) yields -1 without
    // guessing; a missing/unparseable property is "no claim" (null) and still
    // allows a two-channel agreement to confirm (single source never does).
    // Never throws: gateway errors collapse to -1 (fail closed).
    private suspend fun fetchVerifiedDualId(): Int {
        val verboseRes = try {
            gateway.run(CMD_USER_LIST_VERBOSE)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return -1
        }
        val dumpsysRes = try {
            gateway.run(CMD_DUMPSYS_USER)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return -1
        }
        if (verboseRes.exit != 0 || dumpsysRes.exit != 0) return -1
        if (!PmOutputParser.isSuccess(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) return -1
        if (!PmOutputParser.isSuccess(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) return -1
        val propRes = try {
            gateway.run(CMD_GETPROP_DUAL)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val propertyId = propRes
            ?.takeIf { it.exit == 0 }
            ?.let { PmOutputParser.parseDualPropertyId(it.out) }
        return PmOutputParser.resolveDualUserIdVerified(verboseRes.out, dumpsysRes.out, propertyId)
    }

    // Single shared serial-bound identity fetch (PR-33 R2, P1): same three
    // gateway runs, CancellationException rethrow, and fail-closed mapping
    // as fetchVerifiedDualId, but keeps the agreed serial. A confirmed id
    // maps to Confirmed(target) only when both verbose channels agree on one
    // non-null serial for it; a confirmed id without an agreed serial maps
    // to SerialUnavailable; an unconfirmed id (or any failed channel) maps
    // to NoDual. Never throws except on coroutine cancellation.
    suspend fun fetchDualTarget(): DualTargetResult {
        val verboseRes = try {
            gateway.run(CMD_USER_LIST_VERBOSE)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DualTargetResult.NoDual
        }
        val dumpsysRes = try {
            gateway.run(CMD_DUMPSYS_USER)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DualTargetResult.NoDual
        }
        if (verboseRes.exit != 0 || dumpsysRes.exit != 0) return DualTargetResult.NoDual
        if (!PmOutputParser.isSuccess(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) {
            return DualTargetResult.NoDual
        }
        if (!PmOutputParser.isSuccess(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) {
            return DualTargetResult.NoDual
        }
        val propRes = try {
            gateway.run(CMD_GETPROP_DUAL)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val propertyId = propRes
            ?.takeIf { it.exit == 0 }
            ?.let { PmOutputParser.parseDualPropertyId(it.out) }
        // Id first (same resolver as fetchVerifiedDualId): unconfirmed means
        // there is no target at all, regardless of serial lines.
        val verifiedId =
            PmOutputParser.resolveDualUserIdVerified(verboseRes.out, dumpsysRes.out, propertyId)
        if (verifiedId < 0) return DualTargetResult.NoDual
        val target =
            PmOutputParser.resolveDualTargetVerified(verboseRes.out, dumpsysRes.out, propertyId)
                ?: return DualTargetResult.SerialUnavailable
        return DualTargetResult.Confirmed(target)
    }

    // Clones a package into the dual profile via `pm install-existing`.
    suspend fun clone(pkg: String): CmdResult {
        val dualId = resolveDualUserId()
        if (dualId < 0) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return clone(pkg, dualId)
    }

    // Clones a package into an explicit dual profile id.
    // Verified-dual guard: refuses dualId 0 (owner), ids absent from the
    // current `pm list users` snapshot, and ids that are not the Confirmed
    // cross-source target, so a stale, mistyped, or non-Dual id can never
    // redirect the write at the wrong profile.
    suspend fun clone(pkg: String, dualId: Int): CmdResult {
        checkExplicitDualId(dualId)?.let { return it }
        val cmd = PmOutputParser.buildInstallExisting(pkg, dualId)
        return gateway.run(cmd)
    }

    // Removes the clone from the dual profile via `pm uninstall --user`.
    suspend fun remove(pkg: String): CmdResult {
        val dualId = resolveDualUserId()
        if (dualId < 0) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return remove(pkg, dualId)
    }

    // Removes the clone from an explicit dual profile id.
    // Same verified-dual guard as clone(pkg, dualId): see checkExplicitDualId.
    suspend fun remove(pkg: String, dualId: Int): CmdResult {
        checkExplicitDualId(dualId)?.let { return it }
        val cmd = PmOutputParser.buildUninstall(pkg, dualId)
        return gateway.run(cmd)
    }

    // Validates an explicit dual profile id against the current snapshot AND the
    // verified cross-source identity (PR-1 stage 2, issue #21).
    // Returns a refusal CmdResult, or null when the id is safe to target.
    // Refuses dualId 0 (owner profile, must never be written) and any id not
    // present in this call's `pm list users` snapshot (stale/mistyped id),
    // plus any id that is not the currently Confirmed dual target (exists but
    // non-Dual: Work/Secondary/Secure Folder, renamed DUAL-label, type
    // conflict, property mismatch, or unknown). Existence alone never suffices.
    private suspend fun checkExplicitDualId(dualId: Int): CmdResult? {
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
        val verified = fetchVerifiedDualId()
        if (verified != dualId) {
            return CmdResult(
                exit = 1,
                out = "",
                err = "refused: id $dualId is not the verified dual target" +
                    " (verified=${if (verified < 0) "none" else verified})",
            )
        }
        return null
    }

    // Double-checks installation via `pm list packages --user <dualId>`
    // ground truth (exact membership). Returns true only when the probe
    // exits 0 and the parsed set contains the package.
    suspend fun verify(pkg: String): Boolean {
        val dualId = resolveDualUserId()
        if (dualId < 0) return false
        return verifyState(pkg, dualId) is VerifyState.Installed
    }

    // Verifies against an explicit dual profile id.
    // Same verified-dual guard as clone(pkg, dualId)/remove(pkg, dualId):
    // refuses dualId 0 (owner), ids absent from the current snapshot, and ids
    // that are not the currently Confirmed dual target (exists but non-Dual)
    // without running any command (returns false). Read-only path, but
    // consistency first: a stale, mistyped, or non-Dual id must never be
    // probed, and callers get graceful false instead of an
    // IllegalArgumentException from the command builder.
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
    // (exact match); NotInstalled on a clean listing that lacks it
    // (TrustedFull of other packages, or TrustedEmpty: confirmed absent);
    // Unknown when the id is guard-refused, the package token is invalid, or
    // the probe parses as Unknown (truncated capture, non-zero exit after
    // retries, or error-semantic text per the shared C2 line rule). C1 guard
    // is unchanged; the mapping itself is owned by verifyListProbe (PR-2
    // stage 2, issue #22).
    suspend fun verifyState(pkg: String, dualId: Int): VerifyState {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return VerifyState.Unknown(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        return verifyOnProfiles(pkg, dualId, profiles)
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
    // untyped id never authorizes a listing.
    suspend fun listClonedPackages(): List<String>? {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return null
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val dualId = fetchVerifiedDualId()
        if (dualId < 0) return null
        if (profiles.none { it.userId == dualId }) return null
        return listProbe(dualId)
    }

    // Explicit-id variant with the same C1 guard as clone(pkg, dualId):
    // dualId 0 and ids absent from this call's snapshot yield null (unknown)
    // without probing; an invalid builder token also yields null.
    suspend fun listClonedPackages(dualId: Int): List<String>? {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return null
        return listOnProfiles(dualId, (snap as ProfilesSnapshot.Loaded).profiles)
    }

    // Pinned clone (Phase C3, issue #9): resolves the verified dual id ONCE via
    // the cross-source resolver, then runs install-existing AND the
    // `pm list packages` re-check against that same id -- never one guess for
    // the write and another for the check. Success is reported only when the
    // re-check confirms Installed; a clean-but-absent re-check is VerifyFailed
    // (fake success, issue #9), a failed re-check channel is VerifyUnknown.
    // Neither is added to cloned. Unknown/Refused identity maps to NoDual
    // (fail closed); the pinned re-check itself does not re-resolve identity
    // so the write and the check cannot drift to different users.
    suspend fun cloneVerified(pkg: String): CloneOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return CloneOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val dualId = fetchVerifiedDualId()
        if (dualId < 0) return CloneOutcome.NoDual
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
        return when (val state = verifyListProbe(clean, dualId)) {
            is VerifyState.Installed -> CloneOutcome.Success(dualId)
            is VerifyState.NotInstalled -> CloneOutcome.VerifyFailed(dualId)
            is VerifyState.Unknown -> CloneOutcome.VerifyUnknown(dualId, state.detail)
        }
    }

    // Pinned remove (Phase C3, issue #9): same single-verified-id pinning as
    // cloneVerified. Only a post-uninstall re-check of NotInstalled counts as
    // removed; still-present is StillInstalled, an uncheckable channel is
    // VerifyUnknown (caller keeps the entry: fail closed). A failed uninstall
    // stays RemoveFailed by design (C2 scope: uninstall-side "Unknown
    // package" is not idempotent-success); refresh later syncs the display
    // from ground truth either way.
    suspend fun removeVerified(pkg: String): RemoveOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return RemoveOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val dualId = fetchVerifiedDualId()
        if (dualId < 0) return RemoveOutcome.NoDual
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
        return when (val state = verifyListProbe(clean, dualId)) {
            is VerifyState.NotInstalled -> RemoveOutcome.Success(dualId)
            is VerifyState.Installed -> RemoveOutcome.StillInstalled(dualId)
            is VerifyState.Unknown -> RemoveOutcome.VerifyUnknown(dualId, state.detail)
        }
    }

    /**
     * Pinned remove with an expected id (PR-4 Stage 1, issue #23): pin-style
     * execution for the two-phase confirm. The VM passes the request-time
     * dualId; this call snapshots, re-fetches the verified target, and
     * refuses (fail closed, no uninstall) when the verified target moved.
     *
     * PR-33 R2 (P1 serial binding): the VM additionally passes the
     * request-time serial ([expectedSerial], no default -- callers must pass
     * it). After the cancel check and before any uninstall, the serial-bound
     * target is re-fetched: a re-created profile (same id, fresh serial) or
     * an unavailable serial maps to [RemoveOutcome.SerialMismatch] with zero
     * uninstalls. A null [expectedSerial] means the caller cannot verify at
     * all and is refused directly as SerialMismatch(null, actual). An id
     * mismatch keeps the [RemoveOutcome.TargetMoved] mapping.
     */
    suspend fun removeVerifiedExpected(
        pkg: String,
        expectedDualId: Int,
        expectedSerial: Int?,
        isStillValid: () -> Boolean = { true },
    ): RemoveOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return RemoveOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val verified = fetchVerifiedDualId()
        if (verified < 0 || expectedDualId < 0) return RemoveOutcome.NoDual
        if (verified != expectedDualId) {
            return RemoveOutcome.TargetMoved(expectedDualId, verified)
        }
        if (profiles.none { it.userId == expectedDualId }) return RemoveOutcome.NoDual
        val clean = pkg.trim()
        val uninstallCmd = try {
            PmOutputParser.buildUninstall(clean, expectedDualId)
        } catch (e: IllegalArgumentException) {
            return RemoveOutcome.InvalidPackage(clean)
        }
        // 縮小 cancel 窗：uninstall 前再檢查一次 pending 是否仍有效，擋住
        // confirm 與 cancel 交錯的程式化路徑。仍非原子（檢查與執行之間仍有
        // 間隙），UI 層另用不可取消擋住使用者路徑（back/outside-tap）。
        if (!isStillValid()) return RemoveOutcome.Cancelled
        // P1 serial binding (PR-33 R2): re-fetch the serial-bound target
        // AFTER the cancel check, BEFORE any uninstall. A recreated profile
        // (same id+label, fresh serial) must never be uninstalled: any id
        // mismatch keeps TargetMoved, while a serial mismatch -- or no
        // confirmable serial at all (including a null expectedSerial, which
        // means the caller cannot verify) -- maps to SerialMismatch.
        // 零卸載 fail closed。
        val second = fetchDualTarget()
        if (second is DualTargetResult.Confirmed && second.target.userId != expectedDualId) {
            return RemoveOutcome.TargetMoved(expectedDualId, second.target.userId)
        }
        val actualSerial = (second as? DualTargetResult.Confirmed)?.target?.serial
        if (expectedSerial == null || actualSerial != expectedSerial) {
            return RemoveOutcome.SerialMismatch(expectedSerial, actualSerial)
        }
        val res = try {
            gateway.run(uninstallCmd)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return RemoveOutcome.VerifyUnknown(expectedDualId, e.message ?: e.javaClass.simpleName)
        }
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
            return RemoveOutcome.RemoveFailed(res.err.ifEmpty { res.out.ifEmpty { "remove failed" } })
        }
        return when (val state = verifyListProbe(clean, expectedDualId)) {
            is VerifyState.NotInstalled -> RemoveOutcome.Success(expectedDualId)
            is VerifyState.Installed -> RemoveOutcome.StillInstalled(expectedDualId)
            is VerifyState.Unknown -> RemoveOutcome.VerifyUnknown(expectedDualId, state.detail)
        }
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
    // Retry (retained, PR-3 owns it): a non-zero list probe is retried at most
    // twice with a VERIFY_RETRY_DELAY_MS settle wait before the trusted parse,
    // covering transient binder/timeout blips. The wait runs through delayFn
    // (suspend slot: core has no kotlinx-coroutines dependency for delay);
    // callers must dispatch repository calls off Main (DualViewModel wraps
    // every repo call in Dispatchers.IO) because the default delayFn blocks
    // its caller thread.
    private suspend fun verifyOnProfiles(pkg: String, dualId: Int, profiles: List<DualProfile>): VerifyState {
        if (dualId <= 0 || profiles.none { it.userId == dualId }) {
            return VerifyState.Unknown("refused: unknown dual profile id ($dualId)")
        }
        // Verified-dual gate (stage 2): existence alone never suffices. The id
        // must equal the currently Confirmed cross-source target; a present
        // but non-Dual id (Work/Secondary/Secure Folder, renamed DUAL-label,
        // type conflict, property mismatch) maps to Unknown (fail closed),
        // never to a probe of the wrong user.
        val verified = fetchVerifiedDualId()
        if (verified != dualId) {
            return VerifyState.Unknown(
                "refused: id $dualId is not the verified dual target" +
                    " (verified=${if (verified < 0) "none" else verified})",
            )
        }
        return verifyListProbe(pkg, dualId)
    }

    // List-only re-check for an already-verified id (pinned flows): runs the
    // `pm list packages --user <dualId>` probe WITHOUT re-resolving identity,
    // so the write and the check share one verified target and cannot drift.
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
            return VerifyState.Unknown(e.message ?: "invalid package")
        }
        var res: CmdResult = try {
            // Phase 3 (issue #24): fail-closed consistency with
            // fetchVerifiedDualId -- the gateway contract says it does not
            // throw, but a rogue implementation still must not escape as an
            // exception; an un-runnable probe is Unknown, never a guess.
            // Retry asymmetry is intentional: verify keeps its bounded retry
            // (post-write confirmation must ride out transient binder/timeout
            // blips), while listProbe stays single-shot (refresh path is
            // already coalesced upstream; stacking retries there would pile
            // waits behind rapid taps).
            gateway.run(cmd)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return VerifyState.Unknown(e.message ?: e.javaClass.simpleName)
        }
        var attempts = 1
        while (res.exit != 0 && attempts < VERIFY_MAX_ATTEMPTS) {
            delayFn(VERIFY_RETRY_DELAY_MS)
            try {
                res = gateway.run(cmd)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return VerifyState.Unknown(e.message ?: e.javaClass.simpleName)
            }
            attempts++
        }
        // Trusted tri-state (PR-2 stage 2, issue #22): the final probe result
        // is judged by parsePackagesTrusted, not by exit code alone. A clean
        // listing that lacks the package -- TrustedEmpty, or TrustedFull of
        // other packages (including a filtered-style empty) -- is confirmed
        // absent (NotInstalled); only an untrusted capture (truncated,
        // non-zero after the retries above, or error-semantic text such as
        // "Unknown package"/SecurityException/Failure with exit 0) is
        // Unknown. Membership stays an exact set lookup (superstrings never
        // hit). Retry policy is unchanged (PR-3 owns it: at most 2 retries with
        // a delayFn settle wait between attempts).
        return when (val trusted = PmOutputParser.parsePackagesTrusted(res)) {
            is TrustedList.TrustedFull ->
                if (clean in trusted.packages) VerifyState.Installed else VerifyState.NotInstalled
            is TrustedList.TrustedEmpty -> VerifyState.NotInstalled
            is TrustedList.Unknown -> VerifyState.Unknown(trusted.detail)
        }
    }

    // Runs `pm list packages --user <dualId>` for an already-pinned snapshot.
    // Guard-refused ids and untrusted listings yield null (unknown), never
    // throw. The verified-dual gate (#21) is unchanged: existence alone never
    // suffices; the trusted parse (#22) is owned by listProbe.
    private suspend fun listOnProfiles(dualId: Int, profiles: List<DualProfile>): List<String>? {
        if (dualId <= 0 || profiles.none { it.userId == dualId }) return null
        val verified = fetchVerifiedDualId()
        if (verified != dualId) return null
        return listProbe(dualId)
    }

    // List-only probe for an already-verified id (auto listing path): no
    // identity re-resolution. Trusted tri-state (PR-2 stage 2, issue #22):
    // TrustedFull yields its packages, TrustedEmpty yields confirmed-empty
    // (emptyList, authoritative), and Unknown (truncated / non-zero /
    // error-semantic) yields null -- never an authoritative empty list, so
    // refresh keeps the previous `cloned` instead of wiping it.
    private suspend fun listProbe(dualId: Int): List<String>? {
        val cmd = try {
            PmOutputParser.buildListPackages(dualId)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // Phase 3 (issue #24): same fail-closed rule as verifyListProbe --
        // a throwing gateway is unknown (null), never an authoritative empty.
        // Single-shot by design (see verifyListProbe note): refresh callers
        // coalesce rapid taps upstream instead of retrying here.
        val res: CmdResult = try {
            gateway.run(cmd)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        return when (val trusted = PmOutputParser.parsePackagesTrusted(res)) {
            is TrustedList.TrustedFull -> trusted.packages
            is TrustedList.TrustedEmpty -> emptyList()
            is TrustedList.Unknown -> null
        }
    }
}
