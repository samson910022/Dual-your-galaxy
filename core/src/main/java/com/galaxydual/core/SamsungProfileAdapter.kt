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
    // Phase B (P1-2/P1-3): `serial`/`parentId` carry the cross-channel
    // agreed values (null when the passing policy tolerates absence; parent
    // is always 0 on the confirm path because non-zero never confirms).
    data class Confirmed(val userId: Int, val serial: Int? = null, val parentId: Int? = null) : DualResolution {
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
    // TODO(P22-follow-up): right boundary does not block '_', so
    // profile.CLONE_extra still counts as CLONE; confirm with real-device
    // corpus before tightening to [A-Za-z0-9_].
    private val CLONE_MARKER = Regex("(?<![A-Za-z0-9])profile[^A-Za-z0-9]{0,3}clone(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    // Standalone `profile.X` token extractor (separator-tolerant,
    // case-insensitive). Matches dot form (`profile.CLONE`) or 1-3
    // non-alphanumeric separators plus a name (`profile_CLONE`,
    // `profile-CLONE`, `profile CLONE`). Zero-separator glued forms
    // (`profileCloneable`) yield no token by construction.
    // Used for token-level type uniqueness (P1-1): each type mention's
    // `profile.X` tokens are deduped case-insensitively, then a CLONE-family
    // token (`CLONE_MARKER` matches the token) counts as clone evidence and
    // any other `profile.X` token counts as other evidence. Kept
    // boundary-free by spec; callers ignore matches glued to a preceding
    // ASCII letter/digit (`myprofile.CLONE`) so they fall back to OTHER.
    private val PROFILE_TOKEN = Regex("profile(?:\\.[A-Za-z0-9_]+|[^A-Za-z0-9]{1,3}[A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)

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
    // Intra-block parent/serial contradiction (P1-B): more than one
    // distinct parsable parentId (or serial) value inside the SAME block
    // forces userType UNKNOWN (fail closed). The first parsable value is
    // retained for diagnosis; confidence follows the final userType.
    private fun parseOneBlock(
        userId: Int,
        label: String,
        body: String,
        source: IdentitySource,
    ): ProfileIdentity {
        var userType = classifyType(body)
        val parentIds = PARENT_ID.findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val serials = SERIAL.findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        if (parentIds.distinct().size > 1 || serials.distinct().size > 1) {
            userType = ProfileUserType.UNKNOWN
        }
        val parentId = parentIds.firstOrNull()
        val serial = serials.firstOrNull()
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

    // Extracts standalone `profile.X` tokens from one type mention,
    // deduped case-insensitively. A match glued to a preceding ASCII
    // letter/digit (`myprofile.CLONE`) is ignored so it falls back to
    // OTHER via the no-token path, never CLONE.
    private fun extractProfileTokens(mention: String): List<String> =
        PROFILE_TOKEN.findAll(mention)
            .filter { m ->
                val s = m.range.first
                if (s == 0) true else {
                    val p = mention[s - 1]
                    !(p in 'A'..'Z' || p in 'a'..'z' || p in '0'..'9')
                }
            }
            .map { it.value }
            .distinctBy { it.lowercase() }
            .toList()

    // Classifies a block body with token-level type uniqueness (P1-1,
    // issue #21 owner re-review R3): a block is UNKNOWN whenever its
    // `profile.X` type tokens contradict themselves, never CLONE-by-priority.
    // Each type mention is one explicit `user type :/= value` (group 1,
    // trimmed, empty skipped) or one bare-keyword remainder
    // (`TYPE_KEYWORD.replaceFirst(line, "").trim()`).
    // - 0 `profile.X` tokens in a mention: old fallback (non-empty mention
    //   counts as OTHER evidence; empty values/remainders are ignored except
    //   an empty bare remainder which counts as OTHER like the old logic).
    // - >=1 tokens (deduped case-insensitively): any token matching
    //   CLONE_MARKER counts as clone evidence; any other `profile.X` token
    //   counts as other evidence. Non-token text is ignored once tokens
    //   exist. `profile.MANAGED`/`profile.SYSTEM` are other tokens;
    //   `profileCloneable` (no separator) yields no token and never matches
    //   CLONE_MARKER's boundaries.
    // - Same mention holding both clone and other tokens is an intra-mention
    //   contradiction -> UNKNOWN for the block (covers whitespace, `/`,
    //   `,`, `;` or any other separator, since tokens ignore separators).
    // - Block verdict: any intra-mention contradiction -> UNKNOWN; else
    //   hasCloneSeg && hasOtherSeg (across mentions) -> UNKNOWN
    //   (cross-mention contradiction); else CLONE / OTHER / UNKNOWN.
    // Non-type lines (notes, Failure lines, `profileCloneable` tokens) can
    // never yield CLONE. The header label is NOT searched (caller passes
    // the body only), so renaming a profile to contain "DUAL" can never
    // yield CLONE here. Empty type values are skipped (never OTHER).
    private fun classifyType(body: String): ProfileUserType {
        var hasCloneSeg = false
        var hasOtherSeg = false
        // Step 1: explicit `user type :/= value` lines, token-level.
        for (line in body.lines()) {
            val m = TYPE_LINE.find(line) ?: continue
            val value = m.groupValues[1].trim()
            if (value.isEmpty()) continue
            val tokens = extractProfileTokens(value)
            if (tokens.isEmpty()) {
                hasOtherSeg = true
                continue
            }
            val segClone = tokens.any { CLONE_MARKER.containsMatchIn(it) }
            val segOther = tokens.any { !CLONE_MARKER.containsMatchIn(it) }
            if (segClone && segOther) return ProfileUserType.UNKNOWN
            if (segClone) hasCloneSeg = true
            if (segOther) hasOtherSeg = true
        }
        // Step 2: separator-less fallback (`usertype <token>` with no :/=).
        // Same token rule applied to the remainder after the keyword.
        val explicitSep = Regex("(?<![A-Za-z0-9])user[\\s_\\-]*type\\s*[:=]", RegexOption.IGNORE_CASE)
        for (line in body.lines()) {
            if (TYPE_LINE.find(line) != null) continue
            if (!TYPE_KEYWORD.containsMatchIn(line)) continue
            if (explicitSep.containsMatchIn(line)) continue
            val remainder = TYPE_KEYWORD.replaceFirst(line, "").trim()
            if (remainder.isEmpty()) {
                hasOtherSeg = true
                continue
            }
            val tokens = extractProfileTokens(remainder)
            if (tokens.isEmpty()) {
                hasOtherSeg = true
                continue
            }
            val segClone = tokens.any { CLONE_MARKER.containsMatchIn(it) }
            val segOther = tokens.any { !CLONE_MARKER.containsMatchIn(it) }
            if (segClone && segOther) return ProfileUserType.UNKNOWN
            if (segClone) hasCloneSeg = true
            if (segOther) hasOtherSeg = true
        }
        return when {
            hasCloneSeg && hasOtherSeg -> ProfileUserType.UNKNOWN
            hasCloneSeg -> ProfileUserType.CLONE
            hasOtherSeg -> ProfileUserType.OTHER
            else -> ProfileUserType.UNKNOWN
        }
    }

    // Cross-source resolver (PR-1, issue #21; Phase C P2-2 property required).
    // Returns Confirmed(HIGH) ONLY when every agreeing source points at one
    // identical non-owner userId:
    // - USER_LIST_V claim AND DUMPSYS claim BOTH type that same id as
    //   userType CLONE (two independent CLONE claims; CLONE on one channel
    //   plus UNKNOWN on the other is Unknown, never Confirmed), and
    // - a property id MUST be known (explicit argument OR PROP-sourced
    //   candidates) and equal that same id (triple agreement). Phase C P2-2:
    //   property is required corroboration, not optional. effectiveProp ==
    //   null (missing/empty/unparseable, no PROP candidate) -> Unknown
    //   (insufficient evidence), never a two-channel Confirm; mismatch
    //   stays Refused. A version that cannot read the property refuses
    //   explicitly as an unsupported version (#21: property missing/format
    //   unknown -> Unknown/Refused; unknown explicitly refuses writes).
    // Phase B adds two gates after the id/type/property checks, before
    // Confirmed (parent first, serial second):
    // - parent: the id's parentId on BOTH verbose channels must be present,
    //   equal, and == 0. Either side missing -> Unknown (insufficient
    //   evidence); values differ -> Refused("conflicting parent ...");
    //   equal but != 0 -> Refused("non-owner parent ... policy ...").
    //   Only dual-channel-agreed parentId 0 passes (single-device S24
    //   evidence parentId=0, issue #21; other models unverified -- a
    //   non-zero parent fails closed to Unknown/Refused, never confirms).
    // - serial: when BOTH channels carry a serial for the id and they
    //   differ -> Refused("conflicting serial ..."). A missing serial on
    //   either side is tolerated (verbose dumps do not always carry serial,
    //   so absence must not regress availability); the agreed value is the
    //   deduplicated single serial or null.
    // Everything else is Unknown (insufficient evidence) or Refused
    // (contradiction/ambiguity/policy). NEVER label-based: .label is never
    // read (a DUAL-containing label proves nothing); .serial is only a
    // conflict signal (both present and unequal -> Refused) and is never
    // the identity key (serial != userId is normal).
    //
    // @param candidates parsed claims from any sources (PROP entries act as
    //   property claims when present; PM_LIST/XML_DIAG entries never count
    //   as type evidence).
    // @param propertyId property-derived dual id; must be non-null and
    //   non-negative and equal the agreed id, else Unknown (null/negative =
    //   no claim -> Unknown: property required). Production callers pass
    //   ONLY this argument
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

        // Phase C P2-2: property is required corroboration, not optional.
        // Missing/empty/unparseable (effectiveProp == null, no PROP
        // candidate) means insufficient evidence -> Unknown, never a
        // two-channel Confirm. Mismatch stays Refused above.
        if (effectiveProp == null) return DualResolution.Unknown

        val cloneSources = typed.filter { it.userId == id && it.userType == ProfileUserType.CLONE }.map { it.source }.toSet()
        val twoChannel = IdentitySource.USER_LIST_V in cloneSources && IdentitySource.DUMPSYS in cloneSources
        if (!twoChannel) return DualResolution.Unknown

        // Phase B P1-2 parent gate (before serial): both verbose channels
        // must state a parentId for this id, and they must agree on 0.
        // Either side missing (no claim or null parentId) is insufficient
        // evidence -> Unknown (fail closed); disagreement or a non-zero
        // agreed parent is a policy refusal, never a target.
        val verboseParents = typed.filter {
            it.source == IdentitySource.USER_LIST_V && it.userId == id
        }.map { it.parentId }.toSet()
        val dumpsysParents = typed.filter {
            it.source == IdentitySource.DUMPSYS && it.userId == id
        }.map { it.parentId }.toSet()
        if (verboseParents.isEmpty() || dumpsysParents.isEmpty()) return DualResolution.Unknown
        if (verboseParents.any { it == null } || dumpsysParents.any { it == null }) {
            return DualResolution.Unknown
        }
        val agreedParents = (verboseParents + dumpsysParents).filterNotNull().toSet()
        if (agreedParents.size > 1) {
            return DualResolution.Refused(
                "conflicting parent for user $id: verbose=$verboseParents vs dumpsys=$dumpsysParents",
            )
        }
        val agreedParent = agreedParents.single()
        if (agreedParent != 0) {
            return DualResolution.Refused(
                "non-owner parent $agreedParent for user $id: policy requires parentId 0",
            )
        }

        // Phase B P1-3 serial gate: conflict only when BOTH channels carry
        // a serial and they disagree. A missing serial on either side is
        // tolerated (S24 verbose evidence does not guarantee serial); the
        // agreed value is the deduplicated single serial or null.
        val serials = typed.filter {
            it.userId == id && it.userType == ProfileUserType.CLONE
        }.mapNotNull { it.serial }.toSet()
        if (serials.size > 1) {
            return DualResolution.Refused("conflicting serial for user $id: $serials")
        }
        val agreedSerial = serials.singleOrNull()

        return DualResolution.Confirmed(id, agreedSerial, agreedParent)
    }
}
