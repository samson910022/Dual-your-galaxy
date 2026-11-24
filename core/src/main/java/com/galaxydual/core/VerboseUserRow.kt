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
// `, type=profile.CLONE` token. Those earlier `type=` declarations are
// treated as part of the label; the authorization-relevant type can only be
// the LAST declaration, and only when it alone carries the canonical suffix.
//
// FAIL CLOSED -- a row is VALID only when ALL of the following hold:
//   1. the LAST `, type=` declaration has a NON-EMPTY token;
//   2. that last declaration is immediately followed by the canonical flags
//      suffix (`, flags=` or ` (flags=`);
//   3. NO earlier `, type=` declaration is immediately followed by a canonical
//      flags suffix (exactly one canonical-suffix declaration, and it is last);
//   4. the display-name span contains NO control character (C0, incl. `\r`/
//      `\n`) -- a control char anywhere in the name makes the row malformed.
// An empty/missing/non-canonical real field can therefore NEVER be backfilled
// by a label-supplied declaration: rule 2/3 fail the row closed even when an
// earlier smuggled declaration is the only canonical-suffixed one. Callers
// must then refuse to let ANY token from such a row authorize an identity,
// must not neutralize its error semantics, and must fail the strict identity
// gate even when no ERROR_LINE matched.
//
// RESIDUAL AMBIGUITY (honest scope note): this is a FLAT-TEXT format with no
// field-length prefix or quoting. A row whose label supplies the ONLY
// declaration (e.g. `name=foo, type=profile.CLONE, flags=X` with no separate
// real field) is structurally indistinguishable from a legitimate single-type
// row -- arbitrary display labels cannot be perfectly recovered or "unscrambled"
// from the flat text. The rules above deliberately fail closed on AMBIGUOUS
// shape (competing/empty/non-canonical declarations, control chars in the
// name); they do not, and cannot, claim to detect a perfectly formed lone
// label declaration. Such a row is outside the threat model addressed here
// and must be corroborated by the other identity channels.
internal data class VerboseUserRow(
    val userId: Int,
    // Display label (trimmed), retained for logs/UI only -- never read for
    // authorization by either caller.
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

        // Canonical real-type suffix: either `, flags=` or ` (flags=`.
        // Anchored by the caller at the exact end of a type token, so an
        // injected `, type=profile.CLONE` (followed by another `, type=`, not
        // by flags) can never be mistaken for the real declaration.
        private val FLAGS_SUFFIX = Regex("""(?:,[ \t]*|[ \t]*\()flags=""")

        // Row splitting shared by BOTH callers (PmOutputParser's strict gate
        // and SamsungProfileAdapter's claim parser) so they can never disagree
        // on what one row is. Kotlin's String.lines() also breaks on a LONE
        // `\r`; that lets an attacker move a smuggled declaration onto its own
        // "row" and turn a malformed row into a valid-looking CLONE. Real ROMs
        // use LF or CRLF, so split on `\n` only and drop the `\r` of a CRLF;
        // any other `\r`/C0 char stays inside the row where rule 4 fails it.
        fun verboseLines(text: String): List<String> =
            text.split('\n').map { if (it.endsWith('\r')) it.dropLast(1) else it }

        // C0 controls (0x00..0x1F) plus DEL. `\r`/`\n` and friends in a display
        // label are never legitimate display-only noise: they can re-segment
        // the flat text, so the row must fail closed.
        private fun hasControl(s: String): Boolean =
            s.any { it.code <= 0x1F || it == '\u007F' }

        // Returns null when the line is not a new-format verbose row at all
        // (legacy UserInfo{...} blocks, section headers, blank lines, ...).
        fun parse(line: String): VerboseUserRow? {
            val prefix = ROW_PREFIX.find(line) ?: return null
            val userId = prefix.groupValues[1].toIntOrNull() ?: return null
            val nameStart = prefix.range.last + 1

            val declarations = TYPE_DECLARATION.findAll(line, nameStart).toList()
            // The display label ends at the FIRST `, type=`; every earlier
            // token (including an injected CLONE) stays in the label span.
            val nameEnd = declarations.firstOrNull()?.range?.first ?: line.length

            // Per declaration: does the canonical flags suffix start EXACTLY
            // at the end of that declaration's token? Only such a declaration
            // can be the real type -- and only if it is also the LAST one.
            val canonical = declarations.map { decl ->
                val tokenEnd = decl.range.last + 1
                FLAGS_SUFFIX.find(line, tokenEnd)?.range?.first == tokenEnd
            }
            val lastIndex = declarations.lastIndex
            // Rules 1-3: the last declaration has a non-empty token, carries
            // the canonical suffix, and no earlier declaration does. This is
            // what stops a label-supplied declaration from backfilling an
            // empty/missing/non-canonical real field.
            val candidate = declarations.lastOrNull()?.takeIf { decl ->
                decl.groupValues[1].isNotEmpty() && canonical[lastIndex]
            }?.takeIf { (0 until lastIndex).none { canonical[it] } }
            // Rule 4: control characters anywhere in the display name are
            // malformed (defense in depth for the lone-CR variant).
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
