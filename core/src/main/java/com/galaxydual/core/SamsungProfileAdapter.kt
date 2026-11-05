// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Outcome of the cross-source dual-profile resolution (PR-1, issue #21).
// Only Confirmed carries HIGH confidence; anything else must never be used
// as a write/verify target. Refused (contradiction/ambiguity/policy) and
// Unknown (insufficient evidence) are distinct so callers can log precisely.
sealed interface DualResolution {
    // All agreeing sources point at one non-owner id: safe to target.
    // `confidence` is always HIGH by construction (fixed getter, not a
    // constructor parameter, so an illegal confidence is unrepresentable).
    data class Confirmed(val userId: Int) : DualResolution {
        val confidence: IdentityConfidence get() = IdentityConfidence.HIGH
    }

    // Not enough agreeing evidence (no CLONE claim, or a single source only).
    data object Unknown : DualResolution

    // Evidence contradicts itself or policy forbids the target: ambiguous
    // candidates, type conflict on one id, property mismatch, owner id.
    data class Refused(val reason: String) : DualResolution
}

// Minimal platform identity parsers plus the cross-source resolver
// (PR-1, issue #21, stage 2 wired). All functions are pure (String in, data out;
// no I/O, no Android dependency) and directly unit-testable. Stage 2 is
// wired in: DualRepository/Elevators resolve via SamsungProfileAdapter
// parsing (see PmOutputParser.resolveDualUserIdVerified); the label-only
// resolveDualUserId() is Deprecated and must not authorize new writes.
object SamsungProfileAdapter {
    // Header opening one per-user block in both `cmd user list -v` and
    // `dumpsys user` outputs. Same shape as PmOutputParser's UserInfo
    // pattern, kept local so this adapter stays dependency-free.
    private val HEADER = Regex("UserInfo\\{(\\d+):([^:}]*):([^}]*)\\}")

    // A user-type token meaning Samsung/clone profile. Matches
    // `profile.CLONE` with a tolerant separator (`.`, `_`, `-`, space) so
    // `android.os.usertype.profile.CLONE`, `profile_CLONE` and case
    // variants all count. Case-insensitive by design. Right boundary
    // (?![A-Za-z0-9]) excludes `profileCloneable`-style longer tokens.
    private val CLONE_MARKER = Regex("(?<![A-Za-z0-9])profile[^A-Za-z0-9]{0,3}clone(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    // Explicit type lines, e.g. `User type: ...`, `userType=...`.
    // Value is group 1 when `:`/`=` is present.
    private val TYPE_LINE =
        Regex("(?<![A-Za-z0-9])user[\\s_\\-]*type\\s*[:=]\\s*(.+)", RegexOption.IGNORE_CASE)

    // Fallback keyword for separator-less ROM forms such as
    // `usertype profile.MANAGED` (no `:`/`=`). Any body line carrying the
    // keyword counts as an explicit type statement. Left boundary guards
    // against mid-token hits (PmOutputParser convention).
    private val TYPE_KEYWORD =
        Regex("(?<![A-Za-z0-9])user[\\s_\\-]*type", RegexOption.IGNORE_CASE)

    // `parentId=0`, `parent: 0`, `parentId: -10000`, ... (case-insensitive).
    // Left boundary so `transparentId`/`grandparentId` never match.
    private val PARENT_ID = Regex("(?<![A-Za-z0-9])parent(?:Id)?\\s*[:=]\\s*(-?\\d+)", RegexOption.IGNORE_CASE)

    // `serialNo=15`, `serial: 15`, `Serial number: 15`, ... Case-insensitive.
    // `serialNo` (no separator) is covered by the optional separator class.
    // Left boundary so `personalSerialNo` never matches.
    private val SERIAL =
        Regex("(?<![A-Za-z0-9])serial(?:No|\\s*number)?\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)

    // Parses `cmd user list -v` verbose output into one claim per
    // UserInfo{...} block. Tolerant: line order, letter case, `:` vs `=`
    // separators and missing lines are all accepted; a block without any
    // type token yields userType UNKNOWN (never guessed from the label).
    // The header label is retained display-only and never affects userType.
    fun parseUserListVerbose(text: String): List<ProfileIdentity> =
        parseBlocks(text, IdentitySource.USER_LIST_V)

    // Parses `dumpsys user` output into one claim per UserInfo{...} block.
    // Same tolerance rules as parseUserListVerbose: order/case/separator
    // insensitive, missing type line yields UNKNOWN. Looks for the
    // `usertype profile.CLONE`, `parent` and `serial` lines dumpsys emits.
    fun parseDumpsysUser(text: String): List<ProfileIdentity> =
        parseBlocks(text, IdentitySource.DUMPSYS)

    // Shared block splitter/parser for both verbose channels. Cuts the
    // output at each UserInfo{...} header; the lines following a header up
    // to the next header form that user's block. Type/parent/serial are
    // searched in the block BODY only, so the header label can never leak
    // into the authorization-relevant userType.
    private fun parseBlocks(text: String, source: IdentitySource): List<ProfileIdentity> {
        val headers = HEADER.findAll(text).toList()
        if (headers.isEmpty()) return emptyList()
        val out = mutableListOf<ProfileIdentity>()
        for (i in headers.indices) {
            val h = headers[i]
            val userId = h.groupValues[1].toIntOrNull() ?: continue
            val label = h.groupValues[2].trim()
            val bodyStart = h.range.last + 1
            val bodyEnd = if (i + 1 < headers.size) headers[i + 1].range.first else text.length
            val body = text.substring(bodyStart, bodyEnd)
            out.add(parseOneBlock(userId = userId, label = label, body = body, source = source))
        }
        return out
    }

    // Parses one user's block body into a single claim. Pure.
    private fun parseOneBlock(
        userId: Int,
        label: String,
        body: String,
        source: IdentitySource,
    ): ProfileIdentity {
        val userType = classifyType(body)
        val parentId = PARENT_ID.find(body)?.groupValues?.get(1)?.toIntOrNull()
        val serial = SERIAL.find(body)?.groupValues?.get(1)?.toIntOrNull()
        // Single-source confidence cap: a typed claim is MED (still needs a
        // second source for HIGH); an untyped block is LOW.
        val confidence = when (userType) {
            ProfileUserType.CLONE, ProfileUserType.OTHER -> IdentityConfidence.MED
            ProfileUserType.UNKNOWN -> IdentityConfidence.LOW
        }
        return ProfileIdentity(
            userId = userId,
            serial = serial,
            userType = userType,
            parentId = parentId,
            source = source,
            confidence = confidence,
            label = label,
        )
    }

    // Classifies a block body: CLONE only when a TYPE line's value names
    // profile.CLONE (explicit `user type:`/`userType=` line first, then the
    // separator-less `usertype <token>` keyword fallback where the same
    // line carries the CLONE token); OTHER when an explicit non-clone type
    // statement is present; UNKNOWN when no type statement exists at all.
    // Non-type lines (notes, Failure lines, `profileCloneable` tokens) can
    // never yield CLONE. The header label is NOT searched (caller passes
    // the body only), so renaming a profile to contain "DUAL" can never
    // yield CLONE here. Empty type values are skipped (never OTHER).
    private fun classifyType(body: String): ProfileUserType {
        // Step 1: explicit `user type :/= value` lines only.
        val typeValues = body.lines().mapNotNull { line ->
            val m = TYPE_LINE.find(line) ?: return@mapNotNull null
            m.groupValues[1].trim().takeIf { it.isNotEmpty() }
        }
        if (typeValues.any { CLONE_MARKER.containsMatchIn(it) }) return ProfileUserType.CLONE
        if (typeValues.isNotEmpty()) return ProfileUserType.OTHER
        // Step 2: separator-less fallback (`usertype <token>` with no :/=).
        // Any body line NOT matched by TYPE_LINE but carrying the type
        // keyword counts; CLONE wins there too. Lines with an explicit
        // `user type :/=` declaration but an empty value (TYPE_LINE matched
        // nothing) are skipped via explicitSep, so an empty `User type:`
        // stays UNKNOWN, never OTHER. Trailing `:`/`=` elsewhere in the
        // line (e.g. `parentId=0`, `flags: 0x400`) no longer excludes it.
        val explicitSep = Regex("(?<![A-Za-z0-9])user[\\s_\\-]*type\\s*[:=]", RegexOption.IGNORE_CASE)
        val fallback = body.lines().filter { TYPE_LINE.find(it) == null }
        if (fallback.any { TYPE_KEYWORD.containsMatchIn(it) && CLONE_MARKER.containsMatchIn(it) && !explicitSep.containsMatchIn(it) }) return ProfileUserType.CLONE
        val bareKeyword = fallback.filter { TYPE_KEYWORD.containsMatchIn(it) && !explicitSep.containsMatchIn(it) }
        if (bareKeyword.isNotEmpty()) return ProfileUserType.OTHER
        return ProfileUserType.UNKNOWN
    }

    // Cross-source resolver (PR-1, issue #21). Returns Confirmed(HIGH) ONLY
    // when every agreeing source points at one identical non-owner userId:
    // - at least one USER_LIST_V claim AND one DUMPSYS claim with
    //   userType CLONE on that id (two independent channels agree), and
    // - when a property id is known (explicit argument OR PROP-sourced
    //   candidates), it equals that same id (triple agreement).
    // Everything else is Unknown (insufficient evidence) or Refused
    // (contradiction/ambiguity/policy). NEVER label-based: .label and
    // .serial are not read here at all (serial != userId is normal and a
    // DUAL-containing label proves nothing).
    //
    // @param candidates parsed claims from any sources (PROP entries act as
    //   property claims when present; PM_LIST/XML_DIAG entries never count
    //   as type evidence).
    // @param propertyId optional property-derived dual id; when non-null and
    //   non-negative it must equal the agreed id (negative/null = no claim).
    //   Production callers pass ONLY this argument
    //   (PmOutputParser.resolveDualUserIdVerified forwards the parsed
    //   `getprop sys.dualapp.profile_id` value here); the PROP-sourced
    //   candidate path is retained for tests/diagnostics only.
    fun resolveDual(
        candidates: Collection<ProfileIdentity>,
        propertyId: Int? = null,
    ): DualResolution {
        // Property claims: explicit argument plus any PROP-sourced entries
        // (negative ids mean "no claim" and are ignored).
        val propIds = candidates
            .filter { it.source == IdentitySource.PROP && it.userId >= 0 }
            .map { it.userId }
            .distinct()
        if (propIds.size > 1) {
            return DualResolution.Refused("contradictory PROP sources: $propIds")
        }
        val propFromCandidates = propIds.singleOrNull()
        val effectiveProp = when {
            propertyId != null && propertyId >= 0 && propFromCandidates != null &&
                propFromCandidates != propertyId ->
                return DualResolution.Refused(
                    "contradictory property sources: arg=$propertyId vs PROP=$propFromCandidates",
                )
            propertyId != null && propertyId >= 0 -> propertyId
            else -> propFromCandidates
        }

        // Type evidence by user id: ONLY the two verified verbose channels
        // count (USER_LIST_V + DUMPSYS). PROP entries are property
        // constraints, PM_LIST/XML_DIAG are display/diagnostic sources; none
        // of them may join the two-channel rule or veto via CLONE/OTHER.
        val typed = candidates.filter {
            it.source == IdentitySource.USER_LIST_V || it.source == IdentitySource.DUMPSYS
        }
        val cloneIds = typed.filter { it.userType == ProfileUserType.CLONE }.map { it.userId }.toSet()
        if (cloneIds.isEmpty()) return DualResolution.Unknown
        if (cloneIds.size > 1) {
            return DualResolution.Refused("ambiguous CLONE candidates: ${cloneIds.sorted()}")
        }
        val id = cloneIds.single()
        // Owner profile is off-limits even when explicitly typed CLONE.
        // HEADER only matches \d+, so id is never negative here.
        if (id == 0) return DualResolution.Refused("owner profile (0) is off-limits")

        // Same-id contradiction: another channel explicitly typed this id
        // non-clone (e.g. userList says CLONE, dumpsys says SYSTEM).
        val conflict = typed.any {
            it.userId == id &&
                it.userType == ProfileUserType.OTHER &&
                (it.source == IdentitySource.USER_LIST_V || it.source == IdentitySource.DUMPSYS)
        }
        if (conflict) {
            return DualResolution.Refused("conflicting type evidence for user $id")
        }

        if (effectiveProp != null && effectiveProp != id) {
            return DualResolution.Refused("property id $effectiveProp disagrees with clone user $id")
        }

        val sources = typed.filter { it.userId == id }.map { it.source }.toSet()
        val twoChannel = IdentitySource.USER_LIST_V in sources && IdentitySource.DUMPSYS in sources
        if (!twoChannel) return DualResolution.Unknown
        return DualResolution.Confirmed(id)
    }
}
