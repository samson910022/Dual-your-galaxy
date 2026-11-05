// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Provenance of one platform profile identity claim (PR-1, issue #21, stage 1).
// Each value names the exact command/channel the claim was parsed from:
// - PM_LIST: `pm list users` (UserInfo{id:label:flags} lines only, no type info).
// - USER_LIST_V: `cmd user list -v` verbose per-user blocks.
// - DUMPSYS: `dumpsys user` per-user blocks.
// - PROP: system-property derived id (e.g. fw.max_users-adjacent dual id prop).
// - XML_DIAG: diagnostic user XML (reserved for stage 2, no parser yet).
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

// One platform-level profile identity claim (PR-1, issue #21, stage 1).
// Additive model: nothing here replaces DualProfile or the existing
// PmOutputParser.resolveDualUserId() boundary; stage 2 wires it in.
//
// DISPLAY-ONLY LABEL RULE: `label` is a human-readable name kept for logs
// and UI display. It MUST NOT participate in any authorization decision.
// In particular a label containing "DUAL" (user-renamed or ROM-named)
// proves nothing: only userType == CLONE from an explicit type token
// counts, and only resolveDual()'s cross-source agreement grants HIGH.
// Likewise `serial` is a distinct namespace from `userId` (serial != id is
// normal); resolution keys on userId only and never equates the two, so a
// reused userId with a new serial cannot be confused by construction.
data class ProfileIdentity(
    // Android user id, e.g. 0 for owner, 95 for a dual/clone profile.
    val userId: Int,
    // Platform serial number for the user, distinct from userId; null when
    // the source block carries no serial line.
    val serial: Int? = null,
    // CLONE only when an explicit type token says profile.CLONE; OTHER when
    // an explicit non-clone type is present; UNKNOWN when no type line.
    val userType: ProfileUserType = ProfileUserType.UNKNOWN,
    // Parent user id from parentId=/parent= lines; null when absent.
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
