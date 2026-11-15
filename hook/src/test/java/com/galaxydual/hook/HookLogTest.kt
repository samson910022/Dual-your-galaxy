// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Shared log-hygiene helper: short simple names + truncated messages, never throws.
class HookLogTest {

    @Test
    fun shortClassKeepsOnlySimpleName() {
        assertEquals("String", HookLog.shortClass(String::class.java))
        assertEquals("Bar", HookLog.shortName("com.foo.Bar"))
        assertFalse(HookLog.shortName("com.foo.Bar").contains("."))
    }

    @Test
    fun normalizeLogPartStripsControlChars() {
        // Every control char matched by [\r\n\p{Cntrl}] under java.util.regex is
        // replaced by a single space; consecutive control chars yield
        // consecutive spaces (no collapsing). Java's \p{Cntrl} covers C0
        // (U+0000-U+001F) and U+007F only — the C1 byte U+0081 is NOT matched
        // and passes through unchanged (pinned as-is, see PR report).
        val result = HookLog.normalizeLogPart("line1\nline2\r\u001b[31mred\u007f\u0081end")
        assertEquals("line1 line2  [31mred \u0081end", result)
        assertFalse(result.contains("\n"))
        assertFalse(result.contains("\r"))
        assertFalse(Regex("[\\r\\n\\p{Cntrl}]").containsMatchIn(result))
    }

    @Test
    fun normalizeLogPartStripsBidiAndFormatChars() {
        // Hostile scope string shaped like HookMarker's unknown-scope want=
        // line: bidi embeddings/overrides (U+202A-U+202E), isolates
        // (U+2066-U+2069), word-joiner range (U+2060), BOM/ZWNBSP (U+FEFF) and
        // a private-use-area code point (U+E000). All are \p{Cf}/\p{Co} and
        // must be replaced by single spaces before any .take() truncation.
        val hostile = "sys\u202Aserver\u202E \u2066daagent\u2069 \u2060ok\uFEFF \uE000end"
        val result = HookLog.normalizeLogPart(hostile)
        assertEquals("sys server   daagent   ok   end", result)
        assertFalse(result.any { it == '\u202A' || it == '\u202E' || it == '\u2066' || it == '\u2069' || it == '\u2060' || it == '\uFEFF' || it == '\uE000' })
        assertFalse(Regex("[\\p{Cf}\\p{Co}]").containsMatchIn(result))
    }

    @Test
    fun shortExAndMsgAreTruncatedAndNeverThrow() {
        val t = RuntimeException("x".repeat(500))
        assertEquals("RuntimeException", HookLog.shortEx(t))
        assertTrue(HookLog.shortMsg(t).length <= 200)
        // Hostile throwable whose message getter throws still fails closed.
        val hostile = object : Throwable() {
            override val message: String?
                get() = throw IllegalStateException("boom")
        }
        assertEquals("", HookLog.shortMsg(hostile))
    }

    @Test
    fun describeIsShortAndBounded() {
        val m = String::class.java.getMethod("length")
        val d = WhitelistPolicy.describe(m)
        assertEquals("String#length()", d)
        assertFalse(d.contains("java.lang"))
        assertTrue(d.length <= 200)
    }

    @Test
    fun executableSignatureIsShort() {
        val m = String::class.java.getMethod("length")
        val s = HookInstaller.executableSignature(m)
        assertEquals("String#length()->int", s)
        assertFalse(s.contains("java.lang"))
    }
}
