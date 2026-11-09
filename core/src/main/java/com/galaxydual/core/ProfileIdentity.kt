// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Provenance of one platform profile identity claim (PR-1, issue #21, stage 2 wired).
// Each value names the exact command/channel the claim was parsed from:
// - PM_LIST: `pm list users` (UserInfo{id:label:flags} lines only, no type info).
//   reserved, no production parser yet
// - USER_LIST_V: `cmd user list -v` verbose per-user blocks.
// - DUMPSYS: `dumpsys user` per-user blocks.
// - PROP: system-property derived id (e.g. `getprop sys.dualapp.profile_id`).
// - XML_DIAG: diagnostic user XML.
//   reserved, no production parser yet
enum class IdentitySource {
    PM_LIST,
    USER_LIST_V,
    DUMPSYS,
    PROP,
    XML_DIAG,
}

// Confidence of one identity claim or resolution.
// Single-source parses never exceed MED; HIGH is reserved for the
// cross-source agreement computed by SamsungProfileAdapter.resolveDual().
enum class IdentityConfidence {
    HIGH,
    MED,
    LOW,
}

// Platform user-type classification. Determined ONLY from explicit user-type
// tokens (e.g. a `profile.CLONE` userType line), never from the label.
enum class ProfileUserType {
    CLONE,
    OTHER,
    UNKNOWN,
}

// One platform-level profile identity claim (PR-1, issue #21, stage 2 wired).
// DualRepository/Elevators resolve via SamsungProfileAdapter parsing
// (see PmOutputParser.resolveDualUserIdVerified); the label-only
// PmOutputParser.resolveDualUserId() is Deprecated and must not authorize writes.
//
// DISPLAY-ONLY LABEL RULE: `label` is a human-readable name kept for logs
// and UI display. It MUST NOT participate in any authorization decision.
// In particular a label containing "DUAL" (user-renamed or ROM-named)
// proves nothing: only userType == CLONE from an explicit type token
// counts, and only resolveDual()'s cross-source agreement grants HIGH.
// Likewise `serial` is a distinct namespace from `userId` (serial != id is
// normal); resolution keys on userId only and never equates the two, so a
// reused userId with a new serial cannot be confused by construction.
// Phase B policy (enforced in resolveDual, not in the parser): parentId
// must be present on BOTH verbose channels, equal, and == 0 (missing ->
// Unknown; conflict or non-zero -> Refused); serial conflicts (both
// present and unequal) -> Refused, while a missing serial is tolerated and
// still confirms (agreed serial recorded on Confirmed).
data class ProfileIdentity(
    // Android user id, e.g. 0 for owner, 95 for a dual/clone profile.
    val userId: Int,
    // Platform serial number for the user, distinct from userId; null when
    // the source block carries no serial line. Phase B: both channels
    // present but unequal -> resolveDual Refused; either side missing is
    // tolerated (still Confirmed with the deduplicated value or null).
    val serial: Int? = null,
    // CLONE only when an explicit type token says profile.CLONE; OTHER when
    // an explicit non-clone type is present; UNKNOWN when no type line.
    val userType: ProfileUserType = ProfileUserType.UNKNOWN,
    // Parent user id from parentId=/parent= lines; null when absent.
    // Phase B: resolveDual requires it present on BOTH verbose channels,
    // equal, and == 0 (missing -> Unknown; conflict or non-zero -> Refused).
    val parentId: Int? = null,
    // Which channel this claim was parsed from.
    val source: IdentitySource,
    // Single-source parses use MED (typed claim) or LOW (untyped); HIGH is
    // assigned only by resolveDual() on cross-source agreement.
    val confidence: IdentityConfidence = IdentityConfidence.LOW,
    // Human-readable label from the UserInfo{...} header. DISPLAY ONLY:
    // never read for authorization (see rule above).
    val label: String = "",
)

/**
 * Verified dual-profile target with serial binding (PR-33 R2, P1).
 *
 * Extends the id-only [DualResolution.Confirmed] with the platform serial
 * number agreed for that id: the distinct non-null serial pooled from the
 * verbose channels ([IdentitySource.USER_LIST_V] and
 * [IdentitySource.DUMPSYS]), nulls ignored, so a single carrying side
 * suffices (new-ROM verbose has no serial line; dumpsys serialNo= is then
 * authoritative) while both-missing or disagreement refuses. The serial survives an
 * id+label-preserving profile recreation (delete + recreate reuses the user
 * id and label but mints a fresh serial), so the two-phase remove confirm
 * pins (userId, serial) instead of the id alone: a recreated profile with a
 * different serial is refused, never uninstalled.
 */
data class VerifiedDualTarget(
    // Confirmed cross-source dual user id (same id resolveDual confirms).
    val userId: Int,
    // Agreed platform serial for that id (exactly one distinct non-null
    // serial across the verbose channels; see KDoc above).
    val serial: Int,
    // Parent user id pinned beside (id, serial): the agreed parentId from
    // resolveDual's Confirmed (Phase B policy requires both verbose channels
    // to carry it, equal, and == 0, so every constructed target carries 0).
    // Pinned as a triple in refreshVerified's strict-equality fence
    // (#53): a disagreeing parent can only surface as Refused -> Unknown
    // at resolution (never a FRESH listing on a drifted identity); writes
    // re-verify via continuityBreach on their own before/after pin.
    // The ViewModel's InventorySnapshot persists (id, serial) only and
    // drops this field (it is not re-checked at listing time).
    val parentId: Int,
)
