// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Outcome of the cross-source dual-profile resolution (PR-1, issue #21).
// Only Confirmed carries HIGH confidence; anything else must never be used
// as a write/verify target. Refused (contradiction/ambiguity/policy) and
// Unknown (insufficient evidence) are distinct so callers can log precisely.
sealed interface DualResolution {
    // All agreeing sources point at one non-owner id: safe to target.
    // `confidence` is always HIGH by construction.
    data class Confirmed(
        val userId: Int,
        val confidence: IdentityConfidence = IdentityConfidence.HIGH,
    ) : DualResolution

    // Not enough agreeing evidence (no dual-CLONE agreement: no CLONE claim,
    // CLONE on one verbose channel only, or CLONE+UNKNOWN across channels).
    data object Unknown : DualResolution

    // Evidence contradicts itself or policy forbids the target: ambiguous
    // candidates, type conflict on one id, property mismatch, owner id.
    data class Refused(val reason: String) : DualResolution
}

// Minimal platform identity parsers plus the cross-source resolver
// (PR-1, issue #21, stage 1). All functions are pure (String in, data out;
// no I/O, no Android dependency) and directly unit-testable. Stage 1 is
// additive only: PmOutputParser/DualRepository/Elevators are untouched and
// nothing here replaces resolveDualUserId(); stage 2 wires this in.
object SamsungProfileAdapter {
    // Header opening one per-user block in both `cmd user list -v` and
    // `dumpsys user` outputs. Same shape as PmOutputParser's UserInfo
    // pattern, kept local so this adapter stays dependency-free.
    private val HEADER = Regex("UserInfo\\{(\\d+):([^:}]*):([^}]*)\\}")

    // New-ROM `cmd user list -v` line format (One UI 6.1+/S24, PR-33 D2):
    // no UserInfo header and no serial line, e.g.
    // `1: id=95, name=DUAL_APP, type=profile.CLONE, flags=... (parentId=0) ...`.
    // Group 1 = user id, group 2 = display name (label only), group 3 =
    // type token. The display index before `:` is NOT the id and is
    // ignored; the remainder of the line after the type token carries the
    // flags/parent tail.
    private val NEW_VERBOSE_LINE =
        Regex("""^\s*\d+:\s*id=(\d+),\s*name=([^,]*),\s*type=([^\s,]+)""")

    // A user-type token meaning Samsung/clone profile. Matches
    // `profile.CLONE` with a tolerant separator (`.`, `_`, `-`, space) so
    // `android.os.usertype.profile.CLONE`, `profile_CLONE` and case
    // variants all count. Case-insensitive by design.
    private val CLONE_MARKER = Regex("profile[^A-Za-z0-9]{0,3}clone", RegexOption.IGNORE_CASE)

    // Explicit type lines, e.g. `User type: ...`, `userType=...`.
    // Value is group 1 when `:`/`=` is present.
    private val TYPE_LINE =
        Regex("user[\\s_\\-]*type\\s*[:=]\\s*(.+)", RegexOption.IGNORE_CASE)

    // Fallback keyword for separator-less ROM forms such as
    // `usertype profile.MANAGED` (no `:`/`=`). Any body line carrying the
    // keyword counts as an explicit type statement.
    private val TYPE_KEYWORD =
        Regex("user[\\s_\\-]*type", RegexOption.IGNORE_CASE)

    // `parentId=0`, `parent: 0`, `parentId: -10000`, ... (case-insensitive).
    private val PARENT_ID = Regex("parent(?:Id)?\\s*[:=]\\s*(-?\\d+)", RegexOption.IGNORE_CASE)

    // `serialNo=15`, `serial: 15`, `Serial number: 15`, ... Case-insensitive.
    // `serialNo` (no separator) is covered by the optional separator class.
    private val SERIAL =
        Regex("serial(?:No|\\s*number)?\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)

    // Parses `cmd user list -v` verbose output into one claim per user.
    // Dual-format (PR-33 D2): legacy UserInfo{...} blocks are parsed as
    // before; new-ROM lines (`N: id=<id>, name=<label>, type=<type>, ...`,
    // no UserInfo header, no serial line, S24 實機) are parsed per line with
    // the label taken from `name=` and the type/parent evidence from the
    // `type=` token plus the flag/parent tail (the label never enters the
    // body). Results merge by userId with UserInfo winning a duplicate
    // (the two formats never coexist in practice). Tolerant otherwise:
    // line order, letter case, `:` vs `=` separators and missing lines are
    // all accepted; a block without any type token yields userType UNKNOWN
    // (never guessed from the label). The header label is retained
    // display-only and never affects userType.
    fun parseUserListVerbose(text: String): List<ProfileIdentity> {
        val legacy = parseBlocks(text, IdentitySource.USER_LIST_V)
        val fresh = parseNewVerboseLines(text)
        if (fresh.isEmpty()) return legacy
        if (legacy.isEmpty()) return fresh
        val merged = LinkedHashMap<Int, ProfileIdentity>()
        for (e in fresh) merged[e.userId] = e
        for (e in legacy) merged[e.userId] = e
        return merged.values.toList()
    }

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
    // Section guard (PR-33 D3, S24 實機 blocking): the LAST header has no
    // next header, so without a cap its body would extend to EOF and swallow
    // the trailing global catalog. On the S24 `dumpsys user` output the Users
    // section (UserInfo{0/95/150}, 150 typed profile.PRIVATE) is followed by
    // `Device properties:`, `User types (12 types):` (which contains an
    // `android.os.usertype.profile.CLONE:` catalog entry), `Allowlisted ...`
    // and `Non-compliant ...` sections. That CLONE token leaked into user
    // 150's body, typing 150 as CLONE -> cloneIds={95,150} -> Refused ->
    // whole-device NoDual (logcat: verboseOk=true dumpsysOk=true prop=95
    // but resolved=-1). Fix: a block also ends at the first line starting
    // with a non-whitespace char after its body start. UserInfo headers
    // themselves are indented (two spaces) so they never match; top-level
    // section headers (`Users:`, `Device properties:`, `User types ...:`,
    // `Allowlisted ...`, `Non-compliant ...`, `Current user:`) start at
    // column 0 and terminate the block. The new-ROM verbose per-line parser
    // below is line-oriented and immune; it shares this guard through the
    // legacy path only. PmOutputParser.parseUsers stays line-oriented and
    // untouched.
    private fun parseBlocks(text: String, source: IdentitySource): List<ProfileIdentity> {
        val headers = HEADER.findAll(text).toList()
        if (headers.isEmpty()) return emptyList()
        val out = mutableListOf<ProfileIdentity>()
        for (i in headers.indices) {
            val h = headers[i]
            val userId = h.groupValues[1].toIntOrNull() ?: continue
            val label = h.groupValues[2].trim()
            val bodyStart = h.range.last + 1
            val nextHeader = if (i + 1 < headers.size) headers[i + 1].range.first else text.length
            val bodyEnd = minOf(nextHeader, sectionEnd(text, bodyStart))
            val body = text.substring(bodyStart, bodyEnd)
            out.add(parseOneBlock(userId = userId, label = label, body = body, source = source))
        }
        return out
    }

    // First offset >= bodyStart where a new top-level section starts: the
    // first line (after the header's own line remainder, e.g. ` serialNo=..`)
    // whose first char is non-whitespace (^\\S). Returns text.length when
    // there is no such line (block runs to EOF / next header).
    private fun sectionEnd(text: String, bodyStart: Int): Int {
        var nl = text.indexOf('\n', bodyStart)
        while (nl != -1) {
            val lineStart = nl + 1
            if (lineStart >= text.length) return text.length
            if (!text[lineStart].isWhitespace()) return lineStart
            nl = text.indexOf('\n', lineStart)
        }
        return text.length
    }

    // New-ROM `cmd user list -v` line parser (PR-33 D2, S24 實機): one claim
    // per `N: id=<id>, name=<label>, type=<type>, ...` line. The label is
    // taken from `name=` only; the body searched for type/parent/serial is
    // the `type=` token plus the flag/parent tail, so the label can never
    // leak into the authorization-relevant userType (same guarantee as the
    // UserInfo body-only rule above). `type=profile.CLONE` classifies CLONE
    // via CLONE_MARKER; any other explicit type (profile.PRIVATE,
    // full.SYSTEM, ...) classifies OTHER via the shared classifier; a line
    // without a type token is UNKNOWN. `parentId=N` in the tail yields the
    // parent (absent, e.g. owner, yields null); there is no serial line on
    // this ROM, so serial stays null (dumpsys serialNo= is authoritative).
    private fun parseNewVerboseLines(text: String): List<ProfileIdentity> {
        val out = mutableListOf<ProfileIdentity>()
        for (raw in text.lines()) {
            val m = NEW_VERBOSE_LINE.find(raw) ?: continue
            val userId = m.groupValues[1].toIntOrNull() ?: continue
            val label = m.groupValues[2].trim()
            val typeToken = m.groupValues[3].trim()
            // Normalized to a userType= statement so the shared classifier
            // sees the explicit type; the name=... segment (inside the match
            // prefix) is excluded by construction.
            val body = "userType=$typeToken" + raw.substring(m.range.last + 1)
            out.add(parseOneBlock(userId = userId, label = label, body = body, source = IdentitySource.USER_LIST_V))
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

    // Classifies a block body: CLONE when any type evidence names
    // profile.CLONE (explicit `user type:` line OR a bare profile.CLONE
    // token in the body, including separator-less `usertype profile.CLONE`
    // ROM forms); OTHER when an explicit non-clone type line is present;
    // UNKNOWN when no type statement exists at all. The header label is
    // NOT searched (caller passes the body only), so renaming a profile to
    // contain "DUAL" can never yield CLONE here.
    private fun classifyType(body: String): ProfileUserType {
        // Bare/body-wide check first: covers both `userType=<...CLONE>` and
        // separator-less `usertype profile.CLONE` lines. Body-only, so the
        // header label is excluded by construction.
        if (CLONE_MARKER.containsMatchIn(body)) return ProfileUserType.CLONE
        val typeValues = body.lines().mapNotNull { line ->
            val m = TYPE_LINE.find(line) ?: return@mapNotNull null
            m.groupValues[1].trim().takeIf { it.isNotEmpty() }
        }
        if (typeValues.isNotEmpty()) return ProfileUserType.OTHER
        if (body.lines().any { TYPE_KEYWORD.containsMatchIn(it) }) return ProfileUserType.OTHER
        return ProfileUserType.UNKNOWN
    }

    // Cross-source resolver (PR-1, issue #21). Returns Confirmed(HIGH) ONLY
    // when every agreeing source points at one identical non-owner userId:
    // - USER_LIST_V carries a CLONE claim on that id AND DUMPSYS carries a
    //   CLONE claim on that id (two independent channels agree on the TYPE,
    //   not just on the id's presence: an UNKNOWN entry -- no type line --
    //   on either side never counts, so CLONE+UNKNOWN is Unknown), and
    // - when a property id is known (explicit argument OR PROP-sourced
    //   candidates), it equals that same id (triple agreement).
    // Everything else is Unknown (insufficient evidence) or Refused
    // (contradiction/ambiguity/policy). An OTHER claim on the id from
    // either verbose channel is a contradiction (Refused). NEVER
    // label-based: .label and .serial are not read here at all (serial !=
    // userId is normal and a DUAL-containing label proves nothing).
    //
    // @param candidates parsed claims from any sources (PROP entries act as
    //   property claims when present).
    // @param propertyId optional property-derived dual id; when non-null and
    //   non-negative it must equal the agreed id (negative/null = no claim).
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

        // Type evidence by user id (PROP entries are constraints, not type
        // evidence, and never count toward the two-channel rule).
        val typed = candidates.filter { it.source != IdentitySource.PROP }
        val cloneIds = typed.filter { it.userType == ProfileUserType.CLONE }.map { it.userId }.toSet()
        if (cloneIds.isEmpty()) return DualResolution.Unknown
        if (cloneIds.size > 1) {
            return DualResolution.Refused("ambiguous CLONE candidates: ${cloneIds.sorted()}")
        }
        val id = cloneIds.single()
        // Owner profile is off-limits even when explicitly typed CLONE.
        if (id == 0) return DualResolution.Refused("owner profile (0) is off-limits")
        if (id < 0) return DualResolution.Refused("invalid user id ($id)")

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

        val verboseClone = typed.any {
            it.userId == id &&
                it.source == IdentitySource.USER_LIST_V &&
                it.userType == ProfileUserType.CLONE
        }
        val dumpsysClone = typed.any {
            it.userId == id &&
                it.source == IdentitySource.DUMPSYS &&
                it.userType == ProfileUserType.CLONE
        }
        // Dual-CLONE gate (PR-33 S1, P1): BOTH verbose channels must
        // explicitly type this id CLONE. An UNKNOWN entry (no type line) on
        // either side is insufficient evidence -> Unknown, never Confirmed.
        // (OTHER on either side already Refused above.)
        if (!verboseClone || !dumpsysClone) return DualResolution.Unknown
        return DualResolution.Confirmed(id)
    }

    // Serial-bound dual target (PR-33 R2, P1; single-source policy PR-33
    // D2). First confirms the id through the existing resolveDual -- which
    // itself requires a dual-CLONE consensus (USER_LIST_V CLONE on the id
    // AND DUMPSYS CLONE on the id; CLONE+UNKNOWN is Unknown, never
    // Confirmed) -- so non-Confirmed maps to null, fail closed. Then
    // requires the USER_LIST_V and DUMPSYS claims for that id to yield
    // exactly ONE distinct non-null serial: nulls are ignored, so a missing
    // serial line on ONE side is tolerated (new-ROM `cmd user list -v`
    // carries no serial line; the `dumpsys user` serialNo= line is then
    // authoritative). No serial on EITHER side, or two disagreeing serials
    // (profile recreated with a fresh serial), maps to null so callers
    // refuse the write instead of targeting a possibly-recreated profile.
    // PROP-sourced serials are ignored: the property channel is id-only by
    // contract (a numeric id claim, never identity evidence).
    //
    // @param candidates parsed claims from any sources (same contract as
    //   resolveDual).
    // @param propertyId optional property-derived dual id (same contract as
    //   resolveDual; negative/null = no claim).
    fun resolveDualTarget(
        candidates: Collection<ProfileIdentity>,
        propertyId: Int? = null,
    ): VerifiedDualTarget? {
        val id = (resolveDual(candidates, propertyId) as? DualResolution.Confirmed)?.userId
            ?: return null
        // Pooled non-empty consensus: at least one verbose channel must
        // carry a serial, and every carried serial must agree; none (both
        // sides missing) or disagreement both map to null (fail closed).
        val serials = candidates
            .filter {
                it.userId == id &&
                    (it.source == IdentitySource.USER_LIST_V || it.source == IdentitySource.DUMPSYS)
            }
            .mapNotNull { it.serial }
            .toSet()
        if (serials.size != 1) return null
        return VerifiedDualTarget(userId = id, serial = serials.single())
    }
}
