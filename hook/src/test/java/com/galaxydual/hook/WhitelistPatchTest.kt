// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the pure field-patch decision object (WhitelistPatch).
// No reflection, no LibXposed: inputs are plain values plus an isStatic flag.
class WhitelistPatchTest {

    @Test
    fun emptyExtraIsSkippedObserveOnly() {
        val list = mutableListOf("a")
        val out = WhitelistPatch.apply(list, true, emptySet(), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a"), list)
    }

    @Test
    fun nonStaticIsSkipped() {
        val list = mutableListOf("a")
        val out = WhitelistPatch.apply(list, false, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a"), list)
    }

    @Test
    fun nullValueIsSkipped() {
        val out = WhitelistPatch.apply(null, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
    }

    @Test
    fun mutableListMergesInPlace() {
        val list = mutableListOf("a")
        val out = WhitelistPatch.apply(list, true, setOf("a", "b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(1, (out as WhitelistPatch.Outcome.PatchedInPlace).added)
        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun mutableListNoopWhenAlreadyComplete() {
        val list = mutableListOf("a", "b")
        val out = WhitelistPatch.apply(list, true, setOf("a", "b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(0, (out as WhitelistPatch.Outcome.PatchedInPlace).added)
        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun immutableListIsSkippedFailClosed() {
        // listOf() returns a read-only view: the is-check sees a List but not
        // a MutableList, so it falls into the unsupported-type branch.
        // Either way the list is untouched (fail closed).
        val list = listOf("a")
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a"), list)
    }

    @Test
    fun unmodifiableMutableListIsSkipped() {
        val list = java.util.Collections.unmodifiableList(mutableListOf("a"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a"), list)
    }

    @Test
    fun nonStringListIsSkipped() {
        val list = mutableListOf(42)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf(42), list)
    }

    @Test
    fun mixedListIsSkipped() {
        val list = mutableListOf<Any?>("a", 42)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf<Any?>("a", 42), list)
    }

    @Test
    fun nullElementListIsSkipped() {
        val list = mutableListOf<String?>(null)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(1, list.size)
    }

    @Test
    fun stringArrayReturnsReplacement() {
        val arr = arrayOf("a")
        val out = WhitelistPatch.apply(arr, true, setOf("a", "b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Replaced)
        val replaced = out as WhitelistPatch.Outcome.Replaced
        assertEquals(1, replaced.added)
        assertEquals(listOf("a", "b"), replaced.value.toList())
        // Original array is untouched; caller must set() the replacement.
        assertEquals(listOf("a"), arr.toList())
    }

    @Test
    fun stringArrayNoopReturnsSameContents() {
        val arr = arrayOf("a")
        val out = WhitelistPatch.apply(arr, true, setOf("a"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Replaced)
        assertEquals(0, (out as WhitelistPatch.Outcome.Replaced).added)
    }

    @Test
    fun nonStringArrayIsSkipped() {
        val arr = arrayOf(1, 2)
        val out = WhitelistPatch.apply(arr, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
    }

    @Test
    fun unsupportedTypeIsSkippedOriginalUntouched() {
        val set = setOf("a")
        val out = WhitelistPatch.apply(set, true, setOf("b"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(setOf("a"), set)
    }

    @Test
    fun skipReasonMentionsFieldAndClass() {
        val out = WhitelistPatch.apply(null, true, setOf("b"), "MY_FIELD", "com.foo.Bar")
        val reason = (out as WhitelistPatch.Outcome.Skipped).reason
        assertTrue(reason.contains("MY_FIELD"))
        assertTrue(reason.contains("com.foo.Bar"))
    }
}
