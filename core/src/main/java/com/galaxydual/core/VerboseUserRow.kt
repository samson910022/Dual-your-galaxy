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
// declaration. The case-insensitive STRUCTURAL_FLAGS / STRUCTURAL_PARENT_SERIAL
// regexes are a deliberately WIDER ambiguity detector, NOT the authorization
// grammar: strict canonical FLAGS_SUFFIX at the exact token end still decides
// which single token is real. Callers must refuse to let ANY token from a
// malformed row authorize an identity, must not neutralize its error
// semantics, and must fail the strict identity gate even if ERROR_LINE matched
// nothing.
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
        // case-insensitive, tolerant of space/tab around `flags` and of the
        // `parent`/`parentId`/`serial`/`serialNo` spellings with `=` or `:`.
        // It only decides whether a declaration OWNS a structural tail; the
        // strict FLAGS_SUFFIX still decides which token, if any, is real.
        private val STRUCTURAL_FLAGS =
            Regex("""(?:[ \t]*,[ \t]*|[ \t]*\()[ \t]*flags[ \t]*=""", RegexOption.IGNORE_CASE)
        private val STRUCTURAL_PARENT_SERIAL =
            Regex("""(?:parent(?:Id)?|serial(?:No)?)[ \t]*[=:]""", RegexOption.IGNORE_CASE)

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
                STRUCTURAL_FLAGS.containsMatchIn(window) ||
                    STRUCTURAL_PARENT_SERIAL.containsMatchIn(window)
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
    // Non-null iff kind == LEGACY_HEADER; groups are id / label / flags.
    val header: MatchResult?,
)

internal object IdentityLineClassifier {
    // Anchored to ONE physical line: no `(?m)`. Java's multiline `^` also
    // matches after U+0085/U+2028/U+2029 and a lone CR, which could turn a
    // header smuggled after such a char into a second legacy claim. Callers
    // always pass one element of VerboseUserRow.verboseLines(), so LF/CRLF is
    // the only record boundary; an embedded unsafe separator is REJECTED
    // before this regex is ever tried.
    val LEGACY_HEADER = Regex("""^[ \t]*UserInfo\{(\d+):([^:}]*):([^}]*)\}""")

    fun classify(rawText: String): ClassifiedIdentityLine {
        // Unsafe separators first: they are never display-only text and the
        // whole line must fail closed, regardless of what else it contains.
        if (rawText.any { it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }) {
            return ClassifiedIdentityLine(rawText, IdentityLineKind.REJECTED, null, null)
        }
        // New format wins over legacy: a NEW_ROW line is NEVER also scanned as
        // a legacy header, so an inline `UserInfo{...}` in a valid/malformed
        // `name=` label cannot manufacture a competing legacy claim.
        VerboseUserRow.parse(rawText)?.let { row ->
            return ClassifiedIdentityLine(rawText, IdentityLineKind.NEW_ROW, row, null)
        }
        LEGACY_HEADER.find(rawText)?.let { header ->
            return ClassifiedIdentityLine(rawText, IdentityLineKind.LEGACY_HEADER, null, header)
        }
        return ClassifiedIdentityLine(rawText, IdentityLineKind.OTHER, null, null)
    }
}
