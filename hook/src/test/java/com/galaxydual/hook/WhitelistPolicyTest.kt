package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the signature-guard policy (WhitelistPolicy).
// Pure Kotlin/JUnit4 with hand-written inputs -- no Mockito, no framework
// stubs: WhitelistPolicy takes plain result/args and a log lambda.
class WhitelistPolicyTest {

    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { logs.add(it) }

    @Test
    fun booleanTrueKeepsTrue() {
        assertEquals(true, WhitelistPolicy.apply(true, listOf("a"), setOf("b"), "m", log))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun booleanFalseSingleStringInExtraFlipsTrue() {
        assertEquals(true, WhitelistPolicy.apply(false, listOf("a"), setOf("a"), "m", log))
    }

    @Test
    fun booleanFalseSingleStringNotInExtraStaysFalse() {
        assertEquals(false, WhitelistPolicy.apply(false, listOf("z"), setOf("a"), "m", log))
    }

    @Test
    fun booleanFalseWithNoArgsIsObserveOnly() {
        assertEquals(false, WhitelistPolicy.apply(false, emptyList(), setOf("a"), "m", log))
        assertEquals(1, logs.size)
        assertTrue(
            "log should carry observe-only marker, was: ${logs.single()}",
            logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
    }

    @Test
    fun booleanFalseWithNonStringArgIsObserveOnly() {
        assertEquals(false, WhitelistPolicy.apply(false, listOf(42), setOf("a"), "m", log))
        assertEquals(1, logs.size)
        assertTrue(
            "log should carry observe-only marker, was: ${logs.single()}",
            logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
    }

    @Test
    fun booleanFalseWithTwoArgsIsObserveOnly() {
        assertEquals(false, WhitelistPolicy.apply(false, listOf("a", "b"), setOf("a"), "m", log))
        assertEquals(1, logs.size)
        assertTrue(
            "log should carry observe-only marker, was: ${logs.single()}",
            logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
    }

    @Test
    fun listMergesExtras() {
        val out = WhitelistPolicy.apply(listOf("a"), emptyList(), setOf("a", "b"), "m", log)
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun setIsNotDowngradedToList() {
        val out = WhitelistPolicy.apply(setOf("a"), emptyList(), setOf("a", "b"), "m", log)
        assertTrue("expected Set, got ${out?.javaClass?.name}", out is Set<*>)
        assertEquals(setOf("a", "b"), out)
    }

    @Test
    fun otherCollectionKindIsObserveOnly() {
        // PriorityQueue is a Collection but neither List nor Set.
        val queue: Collection<String> = java.util.PriorityQueue(listOf("a"))
        val out = WhitelistPolicy.apply(queue, emptyList(), setOf("b"), "m", log)
        assertSame(queue, out)
        assertEquals(1, logs.size)
        assertTrue(
            "log should carry observe-only marker, was: ${logs.single()}",
            logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
    }

    @Test
    fun stringArrayMergesExtras() {
        val out = WhitelistPolicy.apply(arrayOf("a"), emptyList(), setOf("a", "b"), "m", log)
        assertTrue(out is Array<*>)
        assertEquals(listOf("a", "b"), (out as Array<*>).toList())
    }

    @Test
    fun unknownResultTypeIsObserveOnly() {
        val out = WhitelistPolicy.apply(42, emptyList(), setOf("a"), "m", log)
        assertEquals(42, out)
        assertEquals(1, logs.size)
        assertTrue(
            "log should carry observe-only marker, was: ${logs.single()}",
            logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
    }

    @Test
    fun nullResultIsObserveOnly() {
        val out = WhitelistPolicy.apply(null, emptyList(), setOf("a"), "m", log)
        assertEquals(null, out)
        assertEquals(1, logs.size)
        assertTrue(
            "log should carry observe-only marker, was: ${logs.single()}",
            logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
    }

    @Test
    fun emptyExtraReturnsCollectionsUnchanged() {
        val list = listOf("a")
        assertSame(list, WhitelistPolicy.apply(list, emptyList(), emptySet(), "m", log))
        val set = setOf("a")
        assertSame(set, WhitelistPolicy.apply(set, emptyList(), emptySet(), "m", log))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun describeFormatsSignature() {
        val m = String::class.java.getMethod("length")
        assertEquals("java.lang.String#length()", WhitelistPolicy.describe(m))
    }
}
