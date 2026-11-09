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

    // Not enough agreeing evidence (no dual-CLONE agreement: no CLONE claim,
    // CLONE on one verbose channel only, or CLONE+UNKNOWN across channels).
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
    // `profile.CLONE` with a tolerant separator (`.`, `_`, `-`, space/Tab) so
    // `android.os.usertype.profile.CLONE`, `profile_CLONE` and case
    // variants all count. Case-insensitive by design.
    // Historical note (P22-follow-up, fixed): old substring-marker right
    // boundary did not block '_', so `profile.CLONE_extra` once matched
    // via containsMatchIn; fixed by routing authorization through
    // isSupportedCloneToken (full token exact match), now the sole
    // authorization entry (no substring/regex fallback remains).
    // PR #30 Phase A (R7): the extractor intentionally stays boundary-free
    // and truncation-tolerant (`/` still terminates the name class); it only
    // SPLITS candidate spans. Whether a mention may be CLONE is decided by
    // isSupportedCloneToken (full-token allowlist) PLUS strict residual
    // validation (hasDisallowedResidual): any non-allowlisted leftover
    // (`!evil`, `/evil`, backslash, fullwidth slash, zero-width/control
    // chars, ...) fails the mention closed to OTHER, never CLONE.

    // Standalone `profile.X` token extractor (separator-enumerated,
    // case-insensitive). Matches dot form (`profile.CLONE`) or 1-3
    // enumerated separators (space/`_`/`-`/Tab, shared ENUM_SEP) plus a name
    // (`profile_CLONE`, `profile-CLONE`, `profile CLONE`). Only `.`/`_`/`-`/
    // whitespace (space/Tab) 1-3 chars count as separators; `!`/`?`/`:`/`/`
    // and any other punctuation are NOT separators and yield no token
    // (fail closed: `profile!CLONE`/`profile:::CLONE`/`profile/CLONE` are
    // OTHER via the no-token path, never CLONE). Consecutive enumerated
    // separators (e.g. double space) are still tolerated as documented
    // leniency. The name class is `[A-Za-z0-9_.-]+` so suffixes are never
    // truncated (`profile.CLONE-EXTRA` stays one token instead of being cut
    // to `profile.CLONE`); `/` remains a terminator (a trailing `/...`
    // after CLONE is caught by the tainted-slash guard and the residual
    // check, not by the extractor).
    // Zero-separator glued forms (`profileCloneable`) yield no token by
    // construction.
    // Used for token-level type uniqueness (P1-1): each type mention's
    // `profile.X` tokens are deduped case-insensitively (Locale.ROOT), then
    // a CLONE-family token (`isSupportedCloneToken` matches the token)
    // counts as clone evidence and any other `profile.X` token counts as
    // other evidence. Kept boundary-free by spec; callers ignore matches
    // glued to a preceding letter/digit/`_`/`-` (`myprofile.CLONE`,
    // `_profile.CLONE`) so they fall back to OTHER. `.` still passes the
    // glue check so FQ `android.os.usertype.profile.X` keeps matching
    // (Unicode-aware: Kotlin `isLetterOrDigit` is stricter than ASCII).
    // Enumerated separators shared by PROFILE_TOKEN and the allowlist below.
    private const val ENUM_SEP = "[ _\\-\\t]{1,3}"
    private val PROFILE_TOKEN = Regex("profile(?:\\.[A-Za-z0-9_.-]+|${ENUM_SEP}[A-Za-z0-9_.-]+)", RegexOption.IGNORE_CASE)

    // Full-token CLONE allowlist (P1 full-token boundary, P1-R6 enumerated):
    // `profile` + single `.` OR 1-3 enumerated separators (space/`_`/`-`/
    // Tab) + full word `clone`, whole-token match, case-insensitive.
    // `profile.CLONE`/`profile_CLONE`/`profile-CLONE`/`profile CLONE`
    // (incl. consecutive enumerated separators such as double space) ->
    // true; `profile.CLONE_extra`/`profile.CLONE-EXTRA`/`profile.CLONE.X`/
    // `profileCloneable` -> false; `profile!CLONE`/`profile?CLONE`/
    // `profile:CLONE`/`profile:::CLONE`/`profile/CLONE` -> false (arbitrary
    // punctuation is never a separator).
    private val SUPPORTED_CLONE_TOKEN =
        Regex("^profile(?:\\.clone|${ENUM_SEP}clone)$", RegexOption.IGNORE_CASE)
    private fun isSupportedCloneToken(token: String): Boolean = SUPPORTED_CLONE_TOKEN.matches(token)

    // Tainted-slash guard (P1-R6 fail-closed, PR #30 Phase A tightened): any
    // `/` in a type mention that does NOT open another `profile.X` token
    // taints the mention (segClone forced false, segOther forced true ->
    // OTHER for a single-CLONE mention). `profile.CLONE/evil`,
    // `profile.CLONE/`, `profile.CLONE / evil`, `profile.CLONE - /evil`,
    // `profile.CLONE foo/evil` and `profile.CLONE (/evil)` are tainted ->
    // OTHER; `profile.MANAGED/profile.CLONE` and
    // `profile.CLONE/profile.MANAGED` are untainted (the `/` opens another
    // `profile` token, so the mention still goes through the normal
    // two-token UNKNOWN rule). `profile.CLONE (default)` (no slash) stays
    // CLONE. Note: a slash inside a parenthesized note (e.g.
    // `profile.CLONE (a/b)`) conservatively degrades to OTHER (fail
    // closed); real-device type lines carry no such shape. Relation to the
    // `/` terminator: PROFILE_TOKEN
    // intentionally stops at `/`, which would otherwise let the trailing
    // word silently drop and leave a clean CLONE token; this guard re-adds
    // the trailing-slash signal so such mentions fail closed to OTHER
    // (single tainted CLONE) instead of CLONE. A mixed `CLONE, CLONE/evil`
    // mention is likewise OTHER (fail closed, never CLONE-by-priority).
    // Phase A tightening vs the old `/(?![ \t]*profile)` exemption: the old
    // prefix-only check exempted `/profilegarbage`, `/profile` (bare),
    // `/profileX` and any `/` before a second token (so a multi-slash
    // `profile.CLONE / profile /evil` mention slipped through: the first
    // `/` was exempt and the single containsMatchIn never re-scanned for
    // the second `/evil`). Now EVERY `/` is examined (hasTaintedSlash):
    // a slash is exempt only when it opens a legal second `profile.X`
    // token (optional `[ \t]*` gap, optional `android.os.usertype.` prefix,
    // `profile` + enumerated separator + name); any other slash taints.
    // The residual check (hasDisallowedResidual) independently forces the
    // same OTHER verdict for these shapes (double insurance), and additionally
    // covers non-`/` residuals (`!evil`, backslash, fullwidth slash,
    // zero-width/control chars) that no slash guard can see.
    private fun opensProfileToken(afterSlash: String): Boolean {
        val s = afterSlash.dropWhile { it == ' ' || it == '\t' }
        val t = if (s.startsWith(FQ_PREFIX_DOT, ignoreCase = true)) s.substring(FQ_PREFIX_DOT.length) else s
        val m = PROFILE_TOKEN.find(t)
        return m != null && m.range.first == 0
    }

    private fun hasTaintedSlash(mention: String): Boolean {
        var idx = mention.indexOf('/')
        while (idx >= 0) {
            if (!opensProfileToken(mention.substring(idx + 1))) return true
            idx = mention.indexOf('/', idx + 1)
        }
        return false
    }

    // Strict residual validation (PR #30 Phase A, R7 one-shot syntax check,
    // not a per-character blacklist): after removing every extracted
    // `profile.X` token span from the mention, the leftover must consist
    // solely of allowlisted noise, otherwise the mention cannot be CLONE
    // (caller forces segClone=false, segOther=true -> OTHER). Allowlisted:
    // ASCII space/Tab, `android.os.usertype.` prefix remnants (FQ form),
    // co-located `parentId=`/`serial=` field text (parsed independently and
    // valid on the same single-line fallback remainder, e.g.
    // `usertype profile.CLONE parentId=0`), and at most one `(default)`
    // note (case-insensitive, optional surrounding whitespace — the sole
    // tolerated suffix). In particular `CLONE!evil` leaves `!evil`, and
    // `CLONE/profile!evil` leaves `/` + `!evil`, both disallowed -> OTHER.
    // Only consulted for would-be CLONE mentions (all extracted tokens are
    // clone tokens); mixed clone+other mentions stay on the contradiction
    // rule (UNKNOWN) so genuine `MANAGED, CLONE` / `MANAGED/CLONE`
    // separators are never laundered into OTHER.
    private val FQ_PREFIX_TAIL = Regex("android\\.os\\.usertype\\.?", RegexOption.IGNORE_CASE)
    private val DEFAULT_NOTE = Regex("\\(\\s*default\\s*\\)", RegexOption.IGNORE_CASE)
    private fun hasDisallowedResidual(mention: String, matches: List<MatchResult>): Boolean {
        // 1. Remove every extracted token span.
        val sb = StringBuilder()
        var cursor = 0
        for (m in matches.sortedBy { it.range.first }) {
            sb.append(mention, cursor, m.range.first)
            cursor = m.range.last + 1
        }
        sb.append(mention, cursor, mention.length)
        var residual = sb.toString()
        // 2. Strip co-located structured fields (authorized separately).
        residual = PARENT_ID.replace(residual, "")
        residual = SERIAL.replace(residual, "")
        // 3. Strip FQ prefix remnants.
        residual = FQ_PREFIX_TAIL.replace(residual, "")
        // 4. The `(default)` note is the sole tolerated suffix: at most one.
        val notes = DEFAULT_NOTE.findAll(residual).toList()
        if (notes.size > 1) return true
        if (notes.size == 1) residual = DEFAULT_NOTE.replace(residual, "")
        // 5. Only ASCII space/Tab may remain (fullwidth/zero-width/control
        // chars and every punctuation residual fail closed to OTHER).
        return residual.any { it != ' ' && it != '\t' }
    }

    // FQ `android.os.usertype.` prefix (dot form) shared by the tainted-slash
    // exemption (opensProfileToken) and the residual check above.
    private const val FQ_PREFIX_DOT = "android.os.usertype."

    // `user type` keyword core shared by TYPE_LINE/TYPE_KEYWORD/EXPLICIT_SEP.
    private const val USER_TYPE_PREFIX = "user[\\s_\\-]*type"
    // Explicit type lines, e.g. `User type: ...`, `userType=...`.
    // Value is group 1 when `:`/`=` is present.
    private val TYPE_LINE =
        Regex("(?<![A-Za-z0-9])${USER_TYPE_PREFIX}\\s*[:=]\\s*(.+)", RegexOption.IGNORE_CASE)

    // Fallback keyword for separator-less ROM forms such as
    // `usertype profile.MANAGED` (no `:`/`=`). Any body line carrying the
    // keyword counts as an explicit type statement. Left boundary guards
    // against mid-token hits (PmOutputParser convention).
    private val TYPE_KEYWORD =
        Regex("(?<![A-Za-z0-9])$USER_TYPE_PREFIX", RegexOption.IGNORE_CASE)

    // Explicit `:`/`=` separator after the keyword: lines matching this take
    // the explicit-value path, never the separator-less fallback path.
    private val EXPLICIT_SEP =
        Regex("(?<![A-Za-z0-9])${USER_TYPE_PREFIX}\\s*[:=]", RegexOption.IGNORE_CASE)

    // `parentId=0`, `parent: 0`, `parentId: -10000`, ... (case-insensitive).
    // Left boundary so `transparentId`/`grandparentId` never match.
    private val PARENT_ID = Regex("(?<![A-Za-z0-9])parent(?:Id)?\\s*[:=]\\s*(-?\\d+)", RegexOption.IGNORE_CASE)

    // `serialNo=15`, `serial: 15`, `Serial number: 15`, ... Case-insensitive.
    // `serialNo` (no separator) is covered by the optional separator class.
    // Left boundary so `personalSerialNo` never matches.
    private val SERIAL =
        Regex("(?<![A-Za-z0-9])serial(?:No|\\s*number)?\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)

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
            // prefix) is excluded by construction. The flags/parent tail goes
            // on its own line(s): it carries no type keyword so the
            // classifier ignores it (a flags tail like
            // "(flags=A|B)" must never contribute profile.X tokens to the
            // type mention -- display-only metadata), while parentId/serial
            // extraction still scans the whole body.
            val body = "userType=$typeToken\n" + raw.substring(m.range.last + 1)
            out.add(parseOneBlock(userId = userId, label = label, body = body, source = IdentitySource.USER_LIST_V))
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

    // Extracts standalone `profile.X` token matches from one type mention.
    // A match glued to a preceding letter/digit/`_`/`-`
    // (`myprofile.CLONE`) is ignored so it falls back to OTHER via the
    // no-token path, never CLONE. `.` passes so FQ forms keep matching.
    private fun profileTokenMatches(mention: String): List<MatchResult> =
        PROFILE_TOKEN.findAll(mention)
            .filter { m ->
                val s = m.range.first
                if (s == 0) true else {
                    val p = mention[s - 1]
                    !(p.isLetterOrDigit() || p == '_' || p == '-')
                }
            }
            .toList()

    // Classifies one type mention (explicit `user type :/= value` or bare
    // keyword remainder) into (segClone, segOther). Empty token list with a
    // non-empty mention is (false, true) (OTHER via the no-token path);
    // callers skip empty explicit values and count empty bare remainders as
    // OTHER per the legacy rule. Intra-mention clone+other contradiction
    // surfaces as (true, true) -> caller maps to UNKNOWN for the block.
    private fun classifyMention(mention: String): Pair<Boolean, Boolean> {
        val matches = profileTokenMatches(mention)
        val tokens = matches.map { it.value }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.toList()
        if (tokens.isEmpty()) return Pair(false, true)
        val anyClone = tokens.any { isSupportedCloneToken(it) }
        val anyOtherToken = tokens.any { !isSupportedCloneToken(it) }
        // Slash taint (every `/` must open another legal token) plus strict
        // residual validation (would-be CLONE mentions only): either fails
        // the mention closed to OTHER. Mixed mentions skip the residual
        // check and stay on the contradiction rule.
        val tainted = hasTaintedSlash(mention)
        val residualTainted = anyClone && !anyOtherToken && !tainted && hasDisallowedResidual(mention, matches)
        val failClosed = tainted || residualTainted
        return Pair(anyClone && !failClosed, anyOtherToken || failClosed)
    }

    // Unicode line terminators that Kotlin lines() does NOT split on but
    // Java regex `.` treats as line ends (truncating the TYPE_LINE value
    // capture). Without normalization a `profile.CLONE<U+2028>evil` mention
    // would have its tail silently cut and misclassify as CLONE.
    private val UNICODE_LINE_END = Regex("[\\u0085\\u2028\\u2029]")

    // Classifies a block body with token-level type uniqueness (P1-1,
    // issue #21 owner re-review R3): a block is UNKNOWN whenever its
    // `profile.X` type tokens contradict themselves, never CLONE-by-priority.
    // Each type mention is one explicit `user type :/= value` (group 1,
    // trimmed, empty skipped) or one bare-keyword remainder
    // (`TYPE_KEYWORD.replaceFirst(line, "").trim()`).
    // - 0 `profile.X` tokens in a non-empty mention: old fallback (non-empty
    //   mention counts as OTHER evidence; empty values are skipped except an
    //   empty bare remainder which counts as OTHER like the old logic).
    // - >=1 tokens (deduped case-insensitively with Locale.ROOT): any token
    //   matching isSupportedCloneToken counts as clone evidence; any other
    //   `profile.X` token counts as other evidence. Non-token text is gated
    //   by the strict residual check (PR #30 Phase A): a would-be CLONE
    //   mention (all tokens clone tokens) with any non-allowlisted leftover
    //   (`!evil`, `/evil`, backslash, fullwidth slash, zero-width/control
    //   chars, second-token garbage like `CLONE/profile!evil`, ...) fails
    //   closed to OTHER via hasDisallowedResidual — non-token text is no
    //   longer ignored once tokens exist. `profile.MANAGED`/`profile.SYSTEM`
    //   are other tokens; `profileCloneable` (no separator) yields no token
    //   and never matches the allowlist; `profile.CLONE_extra`/
    //   `profile.CLONE-EXTRA`/`profile.CLONE.X`/`profile.CLONEable` are
    //   full-token non-matches and count as other evidence.
    // - Same mention holding both clone and other tokens is an intra-mention
    //   contradiction -> UNKNOWN for the block (covers whitespace, `/`,
    //   `,`, `;` or any other separator, since token spans are removed
    //   before residual validation and mixed mentions skip that check).
    // - Tainted-slash guard (P1-R6 fail-closed, Phase A tightened): within
    //   one mention, EVERY `/` must open another legal `profile.X` token
    //   (optional spaces, optional `android.os.usertype.` prefix,
    //   `profile` + enumerated separator + name; see hasTaintedSlash).
    //   A single `profile.CLONE/evil` (or trailing-slash, or gap-word forms
    //   like `profile.CLONE - /evil` / `profile.CLONE foo/evil` /
    //   `profile.CLONE (/evil)`, or multi-slash
    //   `profile.CLONE / profile /evil` where the second `/` opens no
    //   token) has segClone forced false and segOther forced true -> OTHER
    //   for the block; `profile.MANAGED/profile.CLONE` and
    //   `profile.CLONE/profile.MANAGED` are untainted (slash opens
    //   another legal token) and stay on the two-token UNKNOWN rule;
    //   a mixed `CLONE, CLONE/evil` mention is OTHER (fail closed).
    // - Block verdict: any intra-mention contradiction -> UNKNOWN; else
    //   hasCloneSeg && hasOtherSeg (across mentions) -> UNKNOWN
    //   (cross-mention contradiction); else CLONE / OTHER / UNKNOWN.
    // Non-type lines (notes, Failure lines, `profileCloneable` tokens) can
    // never yield CLONE. The header label is NOT searched (caller passes
    // the body only), so renaming a profile to contain "DUAL" can never
    // yield CLONE here. Empty type values are skipped (never OTHER).
    // Sole authorization entry: isSupportedCloneToken full-token match PLUS
    // hasDisallowedResidual; the extractor only splits spans, and
    // `(default)` is the sole tolerated suffix.
    private fun classifyType(body: String): ProfileUserType {
        var hasCloneSeg = false
        var hasOtherSeg = false
        // Normalize Unicode line ends first (see UNICODE_LINE_END): map them
        // to `?` (never allowlisted, never a separator) so a smuggled tail
        // fails closed to OTHER via the residual check instead of being cut.
        val text = UNICODE_LINE_END.replace(body, "?")
        // Step 1: explicit `user type :/= value` lines, token-level.
        for (line in text.lines()) {
            val m = TYPE_LINE.find(line) ?: continue
            val value = m.groupValues[1].trim()
            if (value.isEmpty()) continue
            val (segClone, segOther) = classifyMention(value)
            if (segClone && segOther) return ProfileUserType.UNKNOWN
            if (segClone) hasCloneSeg = true
            if (segOther) hasOtherSeg = true
        }
        // Step 2: separator-less fallback (`usertype <token>` with no :/=).
        // Same token rule applied to the remainder after the keyword.
        for (line in text.lines()) {
            if (TYPE_LINE.find(line) != null) continue
            if (!TYPE_KEYWORD.containsMatchIn(line)) continue
            if (EXPLICIT_SEP.containsMatchIn(line)) continue
            val remainder = TYPE_KEYWORD.replaceFirst(line, "").trim()
            if (remainder.isEmpty()) {
                hasOtherSeg = true
                continue
            }
            // Same slash-taint + residual rule as the explicit-value path.
            val (segClone, segOther) = classifyMention(remainder)
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
        val confirmed = resolveDual(candidates, propertyId) as? DualResolution.Confirmed
            ?: return null
        val id = confirmed.userId
        // Carry the resolver's agreed parentId (#53): resolveDual requires
        // both verbose channels to carry it, equal and == 0, so a Confirmed
        // target always bears a non-null parent; a null here (policy change
        // elsewhere) still fails closed rather than guessing an owner parent.
        val parentId = confirmed.parentId ?: return null
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
        return VerifiedDualTarget(userId = id, serial = serials.single(), parentId = parentId)
    }
}
