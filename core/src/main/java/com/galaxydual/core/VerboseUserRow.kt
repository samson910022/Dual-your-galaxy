// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Structured parse of ONE new-ROM verbose `cmd user list -v` row, shared by
// PmOutputParser's strict identity sanitizer and SamsungProfileAdapter's
// claim parser (issue #65 fail-closed hardening). Pure Kotlin, no Android deps.
//
// Row shape (display index before ':' is NOT the user id and is ignored):
//
//   [ \t]*<displayIndex>:[ \t]*id=<userId>,[ \t]*name=<display label>,[ \t]*type=<token>[ \t]*,[ \t]*flags=<tail>
//
// Real ROMs also emit the flags suffix in parentheses with no comma
// (`type=profile.CLONE (flags=...) (parentId=0)`), so BOTH canonical forms
// are accepted as the real-type suffix. The `name=` value is DISPLAY ONLY:
// a user rename may legally contain commas, `Error`, or even a smuggled
// `, type=profile.CLONE` token. Such injected declarations lie AT or AFTER
// [nameEnd] (NOT inside the replaced label span — nameEnd stops at the FIRST
// `, type=`); they are simply NOT AUTHORIZED. The authorization-relevant type
// can only be the LAST declaration, and only when it alone carries the
// canonical suffix.
//
// FAIL CLOSED -- a row is VALID only when ALL of the following hold:
//   1. the LAST `, type=` declaration has a NON-EMPTY token;
//   2. that last declaration is immediately followed by the canonical flags
//      suffix (`, flags=` / ` , flags=` / ` (flags=`, with space/tab tolerated
//      on both delimiter spellings);
//   3. the LAST declaration is STRUCTURAL: the window from the end of its
//      token to the NEXT declaration's start (or line end) contains a real
//      `flags[ \t]*=` field or a `parent(Id)?`/`serial(No)?` `=`/`:` field;
//   4. NO earlier declaration is STRUCTURAL; and
//   5. the display-name span contains NO control character (C0/DEL/NEL/LS/PS,
//      incl. `\r`/`\n`) -- a control char anywhere in the name makes the row
//      malformed.
//
// STRUCTURAL INVARIANT (not a whitespace patch): a `, type=` occurrence is a
// COMPETING REAL declaration only when it owns a structural tail
// (flags/parent/serial) in its OWN window. An earlier declaration carrying
// such a tail can no longer be laundered by a single space before its comma
// (e.g. `type=profile.PRIVATE , flags=X (parentId=10), type=profile.CLONE,
// flags=X (parentId=0)` is REJECTED, not resolved in favour of the last token).
// Conversely a bare `, type=<token>` with an empty window is display-label
// text (a rename may legally contain one), so `name=foo, type=profile.CLONE,
// type=full.SYSTEM, flags=X` still resolves OTHER via the last, structural
// declaration. The case-insensitive field heads in VerboseIdentityFields
// (FLAGS_HEAD / PARENT_HEAD / SERIAL_HEAD) are a deliberately WIDER ambiguity
// detector, NOT the authorization grammar: strict canonical FLAGS_SUFFIX at
// the exact token end still decides which single token is real. Sharing those
// heads with the claim parser's parent/serial extractors keeps the detector
// and the value grammar from drifting apart (issue #87 R10b). Callers must
// refuse to let ANY token from a malformed row authorize an identity, must
// not neutralize its error semantics, and must fail the strict identity gate
// even if ERROR_LINE matched nothing.
//
// RESIDUAL AMBIGUITY (honest scope note): this is a FLAT-TEXT format with no
// field-length prefix or quoting. A row whose label supplies the ONLY
// perfectly formed declaration with no separate real field (e.g.
// `name=foo, type=profile.CLONE, flags=X` with no second field) remains
// structurally indistinguishable from a legitimate single-type row -- an
// arbitrary display label cannot be perfectly recovered or "unscrambled" from
// flat text. The rules above fail closed on AMBIGUOUS shape (an earlier
// structural declaration, a competing/empty/non-canonical last declaration,
// control chars in the name); they do not, and cannot, detect that lone
// label-supplied declaration. It is outside the threat model addressed here
// and must be corroborated by the other identity channels.
//
// COMPOSITE-LABEL / DISPLAY-NAME SCOPE (honest contract): the display-only
// guarantee covers ONLY the neutralized `name=` span (the prefix before the
// FIRST `, type=` declaration). A composite label whose EARLIER NON-CANONICAL
// declaration carries error semantics is NOT covered by that guarantee. For
// example `name=foo, type=Error, type=profile.CLONE, flags=X`: nameEnd stops at
// the first `, type=`, so this parser selects the LAST (canonical, structural)
// declaration and reports typeToken=CLONE with `malformed == false`; BUT the
// strict identity gate neutralizes ONLY `foo` and then scans the un-neutralized
// remainder (`, type=Error, ...`), where the ERROR_LINE keyword makes the gate
// REJECT. This is an INTENTIONAL conservative trade-off: the parser refuses to
// swallow potential malformed/error evidence just to make an arbitrary label
// display-only. Do NOT claim that arbitrary composite labels are display-only.
// `displayName` is ONLY `line.substring(nameStart, nameEnd).trim()` (the prefix
// before the first `, type=` declaration), NOT the full raw name; log/UI
// consumers must NOT treat it as complete or authoritative identity evidence.
// The lone-label-declaration ambiguity note above still applies unchanged.
internal data class VerboseUserRow(
    val userId: Int,
    // Display label only: EXACTLY line.substring(nameStart, nameEnd).trim(),
    // i.e. the prefix before the FIRST `, type=` declaration -- NOT the full
    // raw name. Retained for logs/UI only, never read for authorization and
    // never complete/authoritative identity evidence (see composite-label
    // scope note above).
    val displayName: String,
    // Offsets of the raw name= value in the ORIGINAL line, so the sanitizer
    // can replace ONLY that span and leave the whole remainder (type, flags,
    // parent, genuine error text) untouched for the ERROR_LINE scan.
    val nameStart: Int,
    val nameEnd: Int,
    // The one real type token, or null when malformed.
    val typeToken: String?,
    // Canonical flags suffix and everything after it in the original line.
    // Empty for a malformed row (no trustworthy tail).
    val tail: String,
    val malformed: Boolean,
) {
    companion object {
        // Same whitespace convention in both callers: leading spaces/tabs and
        // optional spaces/tabs after ':' and between key tokens. Kotlin \s is
        // deliberately avoided (it would swallow Unicode/VT/FF).
        private val ROW_PREFIX = Regex("""^[ \t]*\d+:[ \t]*id=(\d+),[ \t]*name=""")

        // Row-like ATTEMPT detector (issue #87 R10a/R12): ANY line shaped like
        // a new-format row prefix (`displayIndex: id` + `=`) is OWNED by the
        // structured-row path even when parse() cannot produce a row (missing
        // `name=`, overflow/non-numeric id, whitespace around `=`). Such a line
        // is REJECTED -- never left as OTHER where it could pass the strict gate
        // with no ERROR_LINE hit or be stitched into a preceding legacy body.
        // This is an ATTEMPT detector only, NOT an authorization grammar: the
        // strict ROW_PREFIX above alone decides what can authorize an identity.
        // Deliberately does NOT match section headers (`Users:`,
        // `User types (...)`, `Devices:`) or prose, so those stay compatible.
        internal val ROW_LIKE = Regex("""^[ \t]*\d+:[ \t]*id[ \t]*=""")

        // Diagnostic id recovery for a REJECTED row-like attempt. NEVER used
        // to authorize: it only lets the claim parser emit a bare UNKNOWN/LOW
        // claim instead of silently forgetting the attempted id.
        internal val ROW_LIKE_ID = Regex("""^[ \t]*\d+:[ \t]*id[ \t]*=(\d+)""")

        // Every `, type=` declaration after name=. Group 1 is the raw token:
        // non-whitespace/non-comma, and intentionally allowed to be EMPTY so
        // `type=, flags=...` is detected as a real declaration with no value
        // (malformed) instead of being silently skipped.
        private val TYPE_DECLARATION = Regex(""",[ \t]*type=([^\s,]*)""")

        // Canonical real-type suffix: either `, flags=` or ` (flags=`, both
        // spellings tolerating space/tab around the delimiter. Anchored by
        // the caller at the EXACT end of a type token, so an injected
        // `, type=profile.CLONE` (followed by another `, type=`, not by flags)
        // can never be mistaken for the real declaration.
        private val FLAGS_SUFFIX = Regex("""(?:[ \t]*,[ \t]*|[ \t]*\([ \t]*)flags=""")

        // WIDER ambiguity detector (NOT the authorization grammar above):
        // case-insensitive field heads are shared with the claim parser's
        // parent/serial extractors via VerboseIdentityFields, so every
        // spelling detected here is also one that parser can extract.
        // It only decides whether a declaration OWNS a structural tail; the
        // strict FLAGS_SUFFIX still decides which token, if any, is real.

        // Row splitting shared by BOTH callers (PmOutputParser's strict gate
        // and SamsungProfileAdapter's claim parser) so they can never disagree
        // on what one row is. Kotlin's String.lines() also breaks on a LONE
        // `\r`; that lets an attacker move a smuggled declaration onto its own
        // "row" and turn a malformed row into a valid-looking CLONE. Real ROMs
        // use LF or CRLF, so split on `\n` only and drop the `\r` of a CRLF;
        // any other `\r`/C0 char stays inside the row where rule 4 fails it.
        fun verboseLines(text: String): List<String> =
            text.split('\n').map { if (it.endsWith('\r')) it.dropLast(1) else it }

        // C0 controls (0x00..0x1F) plus DEL and the Unicode line/paragraph
        // separators (NEL U+0085, LS U+2028, PS U+2029). None is legitimate
        // display-only noise: each can re-segment flat text (Java/Kotlin regex
        // `^`/`.lines()` treat them as line boundaries), so the row must fail
        // closed even when the shared boundary rule is LF/CRLF only.
        private fun hasControl(s: String): Boolean =
            s.any {
                it.code <= 0x1F || it == '\u007F' ||
                    it == '\u0085' || it == '\u2028' || it == '\u2029'
            }

        // Returns null when the line is not a new-format verbose row at all
        // (legacy UserInfo{...} blocks, section headers, blank lines, ...).
        fun parse(line: String): VerboseUserRow? {
            val prefix = ROW_PREFIX.find(line) ?: return null
            val userId = prefix.groupValues[1].toIntOrNull() ?: return null
            val nameStart = prefix.range.last + 1

            val declarations = TYPE_DECLARATION.findAll(line, nameStart).toList()
            // The display label ends at the FIRST `, type=`. An injected
            // declaration lies AT/AFTER that offset (not inside the label
            // span) and is simply not eligible to authorize anything.
            val nameEnd = declarations.firstOrNull()?.range?.first ?: line.length

            // Per declaration (strict authorization grammar): does the
            // canonical flags suffix start EXACTLY at the end of that
            // declaration's token? Only such a declaration can be the real
            // type -- and only if it is also the LAST, STRUCTURAL one.
            val canonical = declarations.map { decl ->
                val tokenEnd = decl.range.last + 1
                FLAGS_SUFFIX.find(line, tokenEnd)?.range?.first == tokenEnd
            }
            // Per declaration (wider ambiguity detector): its own window runs
            // from the exact token end to the NEXT declaration's start (or to
            // line end). A structural field in that window means this is a
            // competing declaration, not mere label text -- even when the
            // space before its comma makes the strict suffix non-canonical.
            // The window boundary keeps a label's bare `, type=` token (empty
            // window) from being mistaken for a competing real declaration.
            val structural = declarations.mapIndexed { i, decl ->
                val windowEnd = declarations.getOrNull(i + 1)?.range?.first ?: line.length
                val window = line.substring(decl.range.last + 1, windowEnd)
                VerboseIdentityFields.FLAGS_HEAD.containsMatchIn(window) ||
                    VerboseIdentityFields.PARENT_HEAD.containsMatchIn(window) ||
                    VerboseIdentityFields.SERIAL_HEAD.containsMatchIn(window)
            }
            val lastIndex = declarations.lastIndex
            // Rules 1-4: the last declaration has a non-empty token, carries
            // the strict canonical suffix, IS structural, and no earlier
            // declaration is structural. This stops both a label-supplied
            // declaration backfilling an empty/non-canonical real field and
            // an earlier structurally complete declaration being overwritten
            // by a later canonical-looking token.
            val candidate = declarations.lastOrNull()?.takeIf { decl ->
                decl.groupValues[1].isNotEmpty() && canonical[lastIndex] && structural[lastIndex]
            }?.takeIf { (0 until lastIndex).none { structural[it] } }
            // Rule 5: control characters anywhere in the display name are
            // malformed (defense in depth for the lone-CR / Unicode separator
            // variants).
            val malformed = candidate == null || hasControl(line.substring(nameStart, nameEnd))
            val real = if (malformed) null else candidate

            val tailStart = real?.let { FLAGS_SUFFIX.find(line, it.range.last + 1)!!.range.first }
                ?: line.length
            return VerboseUserRow(
                userId = userId,
                displayName = line.substring(nameStart, nameEnd).trim(),
                nameStart = nameStart,
                nameEnd = nameEnd,
                typeToken = real?.groupValues?.get(1),
                tail = if (tailStart < line.length) line.substring(tailStart) else "",
                malformed = malformed,
            )
        }
    }
}

// Shared field grammar for structured identity evidence (issue #87 R10b).
// The wide structural-ambiguity detector in VerboseUserRow and the value
// extractors in SamsungProfileAdapter use THESE heads, so a spelling that one
// recognizes can never be invisible to the other. All are case-insensitive
// and carry the same left alnum guard, so `personalSerialNo`, `mySerial` and
// `grandparentId` never contribute parent/serial evidence.
internal object VerboseIdentityFields {
    // `parent`, `parentId`, with `:`/`=` and optional surrounding whitespace.
    internal val PARENT_HEAD =
        Regex("""(?<![A-Za-z0-9])parent(?:Id)?\s*[:=]""", RegexOption.IGNORE_CASE)

    // `serial`, `serialNo`, `serial number`, `serialnumber`, with `:`/`=`.
    // `serialNo` needs no separator class because `No` is part of the name.
    internal val SERIAL_HEAD =
        Regex("""(?<![A-Za-z0-9])serial(?:No|\s*number)?\s*[:=]""", RegexOption.IGNORE_CASE)

    // Canonical `, flags=` or spaced/parenthesized ` ( flags=` tail. Used by
    // the structural detector, never as the strict authorization suffix.
    internal val FLAGS_HEAD =
        Regex("""(?:[ \t]*,[ \t]*|[ \t]*\()[ \t]*flags[ \t]*=""", RegexOption.IGNORE_CASE)

    // Value-bearing form of each head. The value is ALWAYS capture group 1.
    // The capture is deliberately WIDE: the whole maximal candidate run up to
    // a structural delimiter (whitespace, comma, `)`), NOT a decimal prefix.
    // That way `0x10`, `15oops` and an overflowing token are OBSERVED as
    // malformed instead of being silently truncated to a plausible `0`/`15`
    // (issue #87 R14). Strict full-token validation lives in the parseXValue
    // helpers below; a wide match whose value cannot be parsed is INVALID
    // evidence, never an absent field.
    fun parentWithValue(): Regex =
        Regex(PARENT_HEAD.pattern + """[ \t]*([^\s,)]*)""", RegexOption.IGNORE_CASE)

    fun serialWithValue(): Regex =
        Regex(SERIAL_HEAD.pattern + """[ \t]*([^\s,)]*)""", RegexOption.IGNORE_CASE)

    // Strict value grammar (issue #87 R14). A recognized field is valid ONLY
    // when its whole captured token is an optional single trailing `:` (legacy
    // `serialNo=15:`) followed by a COMPLETE decimal in Int range: signed for
    // parent, unsigned for serial. Hex, a trailing word, `+15`, a negative
    // serial, an overflowing token and an empty value are all null -- never
    // coerced or truncated into a smaller plausible number.
    private val SIGNED_DECIMAL = Regex("""-?[0-9]+""")
    private val UNSIGNED_DECIMAL = Regex("""[0-9]+""")

    private fun parseDecimal(raw: String, signed: Boolean): Int? {
        val token = if (raw.endsWith(':')) raw.dropLast(1) else raw
        val grammar = if (signed) SIGNED_DECIMAL else UNSIGNED_DECIMAL
        if (!grammar.matches(token)) return null
        return token.toIntOrNull()
    }

    fun parseParentValue(raw: String): Int? = parseDecimal(raw, signed = true)
    fun parseSerialValue(raw: String): Int? = parseDecimal(raw, signed = false)
}

// Shared legacy `UserInfo{id:label:flags}` header fields, parsed by ONE
// grammar for every caller (strict gate, claim parser, pm-list diagnostics;
// issue #87 R12/R13). Offsets are indices into the ORIGINAL raw line passed to
// LegacyHeaderParser.parse, so the sanitizer can neutralize EXACTLY the
// display-label span and leave the flags/tail visible to ERROR_LINE.
//
// AOSP's UserInfo.toString() concatenates id, name and hex flags WITHOUT
// quoting the name, so a legal rename may contain colons (`Work:Error`) and
// spaces (`a:not found`). The label therefore ends at the separator
// IMMEDIATELY BEFORE A VALID FLAGS TOKEN (last such colon, else last such
// space/tab), NOT at the first colon. `flags` is diagnostic-only; no caller
// may read it for authorization.
internal data class LegacyHeaderFields(
    val id: Int,
    val label: String,
    val labelStart: Int,
    val labelEnd: Int,
    val flags: String,
    val flagsStart: Int,
    val flagsEnd: Int,
    val headerStart: Int,
    val headerEnd: Int,
)

internal sealed interface LegacyParseResult {
    data class Ok(val fields: LegacyHeaderFields) : LegacyParseResult

    data class Rejected(val reason: String) : LegacyParseResult

    data object NotAnAttempt : LegacyParseResult
}

internal object LegacyHeaderParser {
    // ATTEMPT claims a line as a LEGACY-HEADER attempt (even when malformed);
    // HEAD is the valid numeric-id prefix. Both are anchored to ONE physical
    // line: Java's `^` also matches after lone CR / U+0085/U+2028/U+2029,
    // which would let a smuggled header become a second claim. Callers always
    // pass one element of VerboseUserRow.verboseLines().
    internal val ATTEMPT = Regex("""^[ \t]*UserInfo\{""")
    internal val HEAD = Regex("""^[ \t]*UserInfo\{(\d+):""")

    // AOSP hex flags OR the decimal/hex-looking spellings already present in
    // ROM fixtures (`13`, `400`, `c13`, `4c13`, `20001010`, `61010`, ...).
    // Full match only: `400oops` is malformed, not a prefix of `400`.
    private val FLAGS_TOKEN = Regex("""(?:0[xX][0-9a-fA-F]+|[0-9a-fA-F]+)""")

    // Tab is part of the legacy separator grammar, so it is NOT an illegal
    // control here (unlike VerboseUserRow's strict display name). C0 controls
    // plus DEL / Unicode separators can re-segment flat text -> reject.
    private fun hasIllegalControl(s: String): Boolean = s.any {
        (it.code <= 0x1F && it != '\t') || it == '\u007F' ||
            it == '\u0085' || it == '\u2028' || it == '\u2029'
    }

    // Diagnostic-only id recovery for a REJECTED legacy attempt. Never an
    // authorization path: it only lets the claim parser emit a bare
    // UNKNOWN/LOW claim instead of silently forgetting the attempted id.
    fun recoverId(line: String): Int? = HEAD.find(line)?.groupValues?.get(1)?.toIntOrNull()

    // `requireLineStart = false` is for PmOutputParser's diagnostic pm-list
    // scan, which hands us a loose `UserInfo\{\d+:[^}]*\}` fragment beginning
    // at `UserInfo{`. The classifier/classification-based callers always use
    // the default (strict line start).
    fun parse(line: String, requireLineStart: Boolean = true): LegacyParseResult {
        if (requireLineStart && !ATTEMPT.containsMatchIn(line)) return LegacyParseResult.NotAnAttempt
        val head = HEAD.find(line) ?: return LegacyParseResult.Rejected("bad-id-head")
        val id = head.groupValues[1].toIntOrNull()
            ?: return LegacyParseResult.Rejected("overflow-id")
        val headEnd = head.range.last + 1

        // FIRST closing brace after the head only. An inner `{` (or a second
        // brace after the close) is a competing/nested header: reject instead
        // of greedily swallowing another user's body (issue #87 R13).
        val close = line.indexOf('}', headEnd)
        if (close < 0) return LegacyParseResult.Rejected("unclosed")
        val inner = line.substring(headEnd, close)
        if (inner.contains('{')) return LegacyParseResult.Rejected("extra-brace")
        val afterClose = line.substring(close + 1)
        if (afterClose.contains('{') || afterClose.contains('}')) {
            return LegacyParseResult.Rejected("extra-brace")
        }
        if (hasIllegalControl(line)) return LegacyParseResult.Rejected("control-char")

        // Locate the separator immediately preceding a VALID flags token:
        // the LAST such colon wins, else the LAST such space/tab.
        val suffix = findFlagsSuffix(inner) ?: return LegacyParseResult.Rejected("missing-or-malformed-flags")
        val (sepIndex, flagsStartInInner, flagsEndInInner) = suffix
        val flags = inner.substring(flagsStartInInner, flagsEndInInner)

        val labelRaw = inner.substring(0, sepIndex)
        val label = labelRaw.trim(' ', '\t')
        val labelOffset = labelRaw.indexOf(label)
        val labelStart = headEnd + if (labelOffset < 0) 0 else labelOffset
        val labelEnd = labelStart + label.length

        return LegacyParseResult.Ok(
            LegacyHeaderFields(
                id = id,
                label = label,
                labelStart = labelStart,
                labelEnd = labelEnd,
                flags = flags,
                flagsStart = headEnd + flagsStartInInner,
                flagsEnd = headEnd + flagsEndInInner,
                headerStart = head.range.first,
                headerEnd = close + 1,
            ),
        )
    }

    // Returns (separator index, flags start, flags end), all relative to
    // [inner]. Colons take priority over space/tab separators; within each
    // class the LAST valid separator wins.
    private fun findFlagsSuffix(inner: String): IntArray? {
        fun collect(wanted: (Char) -> Boolean): IntArray? {
            var best: IntArray? = null
            for (i in inner.indices) {
                if (!wanted(inner[i])) continue
                val rest = inner.substring(i + 1)
                val token = rest.trim(' ', '\t')
                if (!FLAGS_TOKEN.matches(token)) continue
                val start = i + 1 + rest.takeWhile { it == ' ' || it == '\t' }.length
                best = intArrayOf(i, start, start + token.length)
            }
            return best
        }
        return collect { it == ':' } ?: collect { it == ' ' || it == '\t' }
    }
}

// Exactly one classification per RAW physical line, shared by the strict
// identity gate (PmOutputParser.sanitizeIdentityOutput) and the adapter's
// legacy/new claim parsers. Classifying the SAME raw text in both callers is
// what makes "only neutralize a confirmed display label" safe: no caller may
// delete substrings from the whole output first and then re-interpret it.
internal enum class IdentityLineKind {
    NEW_ROW,
    LEGACY_HEADER,
    OTHER,
    REJECTED,
}

internal data class ClassifiedIdentityLine(
    val text: String,
    val kind: IdentityLineKind,
    // Non-null iff kind == NEW_ROW (already parsed by the shared row parser).
    val row: VerboseUserRow?,
    // Non-null iff kind == LEGACY_HEADER. Shared LegacyHeaderParser fields;
    // never a raw regex match, so label/flags splitting is identical in every
    // caller (gate, claim parser, pm-list diagnostics).
    val fields: LegacyHeaderFields?,
    // Non-null iff kind == REJECTED (diagnostic only: the line fails closed
    // regardless of the reason).
    val rejectReason: String?,
)

internal object IdentityLineClassifier {
    // Classification order is ownership order: an attempt that cannot be
    // parsed fully is OWNED and REJECTED before any other rule can look at its
    // text. This is what stops a malformed record from being stitched into a
    // preceding legacy body (issue #87 R12).
    fun classify(rawText: String): ClassifiedIdentityLine {
        fun rejected(reason: String) =
            ClassifiedIdentityLine(rawText, IdentityLineKind.REJECTED, null, null, reason)

        // Unsafe separators first: they are never display-only text and the
        // whole line must fail closed, regardless of what else it contains.
        if (rawText.any { it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }) {
            return rejected("unsafe-separator")
        }
        // New format wins over legacy: a NEW_ROW line is NEVER also scanned as
        // a legacy header, so an inline `UserInfo{...}` in a valid/malformed
        // `name=` label cannot manufacture a competing legacy claim.
        VerboseUserRow.parse(rawText)?.let { row ->
            return ClassifiedIdentityLine(rawText, IdentityLineKind.NEW_ROW, row, null, null)
        }
        // Row-like ATTEMPT that could not be parsed (R10a/R12): OWN the line
        // and fail closed. The `id[ \t]*=` head also catches spaced `id =`
        // attempts that previously fell through to OTHER. This is an ATTEMPT
        // detector, never an authorization grammar.
        if (VerboseUserRow.ROW_LIKE.containsMatchIn(rawText)) {
            return rejected("malformed-new-attempt")
        }
        return when (val result = LegacyHeaderParser.parse(rawText)) {
            is LegacyParseResult.Ok -> ClassifiedIdentityLine(
                rawText,
                IdentityLineKind.LEGACY_HEADER,
                null,
                result.fields,
                null,
            )
            is LegacyParseResult.Rejected -> rejected(result.reason)
            LegacyParseResult.NotAnAttempt ->
                ClassifiedIdentityLine(rawText, IdentityLineKind.OTHER, null, null, null)
        }
    }
}
