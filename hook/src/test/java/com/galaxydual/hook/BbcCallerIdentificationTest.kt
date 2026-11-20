// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Caller-identification read-only diagnostics (BbcInjector.callerSnippet /
// originalContainsPolicy / formatBbcEntryDiag): truncation, boundedness and
// exception-skip behavior. Pure JVM (no Samsung/BBC/Android classes).
class BbcCallerIdentificationTest {

    private fun frame(cls: String, method: String) = StackTraceElement(cls, method, "F.java", 42)

    @Test
    fun snippetSkipsHookInfraAndKeepsSamsungCallerFirst() {
        val trace = arrayOf(
            frame("java.lang.Thread", "getStackTrace"),
            frame("com.galaxydual.hook.DualHook", "interceptBbcCall"),
            frame("com.galaxydual.hook.BbcInjector", "callerSnippet"),
            frame("io.github.libxposed.api.XposedInterface\$Chain", "proceed"),
            frame("com.samsung.android.da.daagent.fwwrapper.PmWrapper", "getPossibleDualAppPackages"),
            frame("com.samsung.android.da.daagent.activity.DualAppActivity", "getAvailableApps"),
        )
        val s = BbcInjector.callerSnippet(trace)
        assertTrue(s.startsWith("PmWrapper#getPossibleDualAppPackages"))
        assertTrue(s.contains("DualAppActivity#getAvailableApps"))
        assertFalse(s.contains("DualHook"))
        assertFalse(s.contains("BbcInjector"))
        assertFalse(s.contains("java.lang.Thread"))
        assertFalse(s.contains("com.samsung"))
    }

    @Test
    fun snippetTruncatesToFirstNFrames() {
        val trace = Array(12) { i -> frame("com.samsung.a.Cls$i", "m$i") }
        val s = BbcInjector.callerSnippet(trace, maxFrames = 8)
        assertEquals(8, s.split(" <- ").size)
        assertTrue(s.contains("Cls0#m0"))
        assertTrue(s.contains("Cls7#m7"))
        assertFalse(s.contains("Cls8"))
    }

    @Test
    fun snippetIsBoundedForHostileNames() {
        val longCls = "com.samsung." + "C".repeat(500)
        val longMethod = "m".repeat(500)
        val trace = arrayOf(frame(longCls, longMethod))
        val s = BbcInjector.callerSnippet(trace)
        assertTrue(s.length <= BbcInjector.BBC_CALLER_MAX_CHARS)
        assertFalse(s.contains("\n"))
    }

    @Test
    fun snippetSanitizesControlChars() {
        val trace = arrayOf(frame("com.samsung.a.Clazz", "meth\nod\u202A"))
        val s = BbcInjector.callerSnippet(trace)
        assertFalse(s.contains("\n"))
        assertFalse(s.contains("‪"))
    }

    @Test
    fun snippetNullEmptyAndNonPositiveAreEmpty() {
        assertEquals("", BbcInjector.callerSnippet(null))
        assertEquals("", BbcInjector.callerSnippet(arrayOf()))
        assertEquals("", BbcInjector.callerSnippet(arrayOf(frame("com.samsung.a.C", "m")), maxFrames = 0))
        assertEquals("", BbcInjector.callerSnippet(arrayOf(frame("com.samsung.a.C", "m")), maxFrames = -3))
    }

    @Test
    fun snippetSkipsEverythingWhenOnlyInfra() {
        val trace = arrayOf(
            frame("java.lang.Thread", "getStackTrace"),
            frame("com.galaxydual.hook.DualHook", "interceptBbcCall"),
        )
        assertEquals("", BbcInjector.callerSnippet(trace))
    }

    @Test
    fun containsPolicyTrueFalseAndEmpty() {
        assertTrue(BbcInjector.originalContainsPolicy(listOf("com.a", "com.policy"), setOf("com.policy")))
        assertFalse(BbcInjector.originalContainsPolicy(listOf("com.a", "com.b"), setOf("com.policy")))
        assertFalse(BbcInjector.originalContainsPolicy(emptyList<Any?>(), setOf("com.policy")))
        assertFalse(BbcInjector.originalContainsPolicy(listOf("com.a"), emptySet()))
    }

    @Test
    fun containsPolicySkipsNonStringElements() {
        assertTrue(BbcInjector.originalContainsPolicy(listOf<Any?>("com.a", 42, null, "com.policy"), setOf("com.policy")))
        assertFalse(BbcInjector.originalContainsPolicy(listOf<Any?>(42, null), setOf("com.policy")))
    }

    @Test
    fun containsPolicyHostileCollectionsFailClosed() {
        val hostileList = object : ArrayList<Any?>() {
            override fun iterator(): MutableIterator<Any?> = throw IllegalStateException("boom")
        }
        assertFalse(BbcInjector.originalContainsPolicy(hostileList, setOf("com.policy")))
        val hostileSet = object : HashSet<String>() {
            override fun contains(element: String): Boolean = throw IllegalStateException("boom")
        }
        assertFalse(BbcInjector.originalContainsPolicy(listOf("com.policy"), hostileSet))
    }

    @Test
    fun formatContainsTsPidSizeBooleanAndCallerOnly() {
        val line = BbcInjector.formatBbcEntryDiag(
            tsMs = 123456789L,
            pid = 6288,
            caller = "PmWrapper#getPossibleDualAppPackages <- DualAppActivity#getAvailableApps",
            resultSize = 7,
            containsPolicy = true,
        )
        assertTrue(line.startsWith("BBC-ENTRY "))
        assertTrue(line.contains("ts=123456789"))
        assertTrue(line.contains("pid=6288"))
        assertTrue(line.contains("size=7"))
        assertTrue(line.contains("containsPolicy=true"))
        assertTrue(line.contains("DualAppActivity#getAvailableApps"))
        // No package literal travels through this formatter by construction.
        assertFalse(line.contains("com.openai.chatgpt"))
    }

    @Test
    fun formatIsBoundedAndSanitized() {
        val hostileCaller = "C#m\nline2\u202B" + "x".repeat(2000)
        val line = BbcInjector.formatBbcEntryDiag(1L, 1, hostileCaller, 3, false)
        assertTrue(line.length <= BbcInjector.BBC_ENTRY_MAX_CHARS)
        assertFalse(line.contains("\n"))
        assertFalse(line.contains("‪"))
    }

    @Test
    fun formatNeverThrowsOnEmptyCaller() {
        val line = BbcInjector.formatBbcEntryDiag(0L, -1, "", -1, false)
        assertTrue(line.startsWith("BBC-ENTRY "))
        assertTrue(line.contains("size=-1"))
    }

    // --- R10 (P2): lazy caller capture gated by the once-per-pid ledger ---

    @Test
    fun entryLoggerInvokesSupplierExactlyOnceForSamePid() {
        val ledger = HashSet<String>()
        val sink = mutableListOf<String>()
        val logger = BbcInjector.BbcEntryLogger(ledger) { sink.add(it) }
        var captures = 0
        val supplier = { captures++; "PmWrapper#getPossibleDualAppPackages" }
        repeat(5) { logger.logOnce(100, 1L, 3, false, supplier) }
        assertEquals(1, captures)
        assertEquals(1, sink.size)
        assertEquals(setOf("entry:100"), ledger)
        assertTrue(sink.single().startsWith("BBC-ENTRY "))
        assertTrue(sink.single().contains("pid=100"))
    }

    @Test
    fun entryLoggerDifferentPidInvokesSupplierOnceMore() {
        val ledger = HashSet<String>()
        val sink = mutableListOf<String>()
        val logger = BbcInjector.BbcEntryLogger(ledger) { sink.add(it) }
        var captures = 0
        val supplier = { captures++; "PmWrapper#getPossibleDualAppPackages" }
        repeat(5) { logger.logOnce(100, 1L, 3, false, supplier) }
        repeat(5) { logger.logOnce(200, 2L, 4, true, supplier) }
        assertEquals(2, captures)
        assertEquals(2, sink.size)
        assertEquals(setOf("entry:100", "entry:200"), ledger)
    }

    @Test
    fun entryLoggerEmptyCallerEmitsNoLineThenRealCallerLogsOnce() {
        // F5: an empty-caller attempt emits no line and no ledger entry, but
        // must release the capture guard too — otherwise a later real-caller
        // attempt for the same pid returns early and never logs.
        val ledger = HashSet<String>()
        val sink = mutableListOf<String>()
        val logger = BbcInjector.BbcEntryLogger(ledger) { sink.add(it) }
        var captures = 0
        // First attempt: unresolvable caller -> no line, no ledger entry.
        assertFalse(logger.logOnce(300, 1L, 3, false, { captures++; "" }))
        assertEquals(1, captures)
        assertTrue(sink.isEmpty())
        assertTrue(ledger.isEmpty())
        // Second attempt, SAME pid with a real caller: exactly one line.
        assertTrue(logger.logOnce(300, 2L, 4, false, { captures++; "PmWrapper#getPossibleDualAppPackages" }))
        assertEquals(2, captures)
        assertEquals(1, sink.size)
        assertEquals(setOf("entry:300"), ledger)
        assertTrue(sink.single().startsWith("BBC-ENTRY "))
        assertTrue(sink.single().contains("pid=300"))
        // A third attempt is a fast path: ledger already claimed -> no capture,
        // no extra line. At most one SUCCESSFUL capture/log per pid holds.
        assertFalse(logger.logOnce(300, 3L, 5, false, { captures++; "PmWrapper#getPossibleDualAppPackages" }))
        assertEquals(2, captures)
        assertEquals(1, sink.size)
    }
}
