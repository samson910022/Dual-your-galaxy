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
        assertTrue(result.length <= 200)
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
