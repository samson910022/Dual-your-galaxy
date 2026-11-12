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
