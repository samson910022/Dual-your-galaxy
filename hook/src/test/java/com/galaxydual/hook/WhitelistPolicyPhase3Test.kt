// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Phase3: non-empty containers must honor a parseable ParameterizedType generic
// even when the runtime contents are all Strings. Only raw/unknown/null keeps
// the legacy non-empty all-String compat branch.
class WhitelistPolicyPhase3Test {

    @Before
    fun resetLogBudget() {
        WhitelistPolicy.resetUnexpectedArgsLogsForTest()
    }

    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { logs.add(it) }

    private interface Generics {
        fun stringList(): List<String>
        fun intList(): List<Int>
        fun stringSet(): Set<String>
        fun intSet(): Set<Int>
    }

    @Test
    fun nonEmptyAllStringListWithIntGenericIsObserveOnly() {
        val original = ArrayList(listOf("a")) as List<*>
        val generic = Generics::class.java.getMethod("intList").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = generic,
        )
        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun nonEmptyAllStringSetWithIntGenericIsObserveOnly() {
        val original = LinkedHashSet(listOf("a")) as Set<*>
        val generic = Generics::class.java.getMethod("intSet").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.Set::class.java,
            genericReturnType = generic,
        )
        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun nonEmptyAllStringListWithStringGenericStillMerges() {
        val original = ArrayList(listOf("a")) as List<*>
        val generic = Generics::class.java.getMethod("stringList").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = generic,
        )
        assertEquals(listOf("a", "b"), out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun nonEmptyAllStringSetWithStringGenericStillMerges() {
        val original = LinkedHashSet(listOf("a")) as Set<*>
        val generic = Generics::class.java.getMethod("stringSet").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.Set::class.java,
            genericReturnType = generic,
        )
        assertEquals(setOf("a", "b"), out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun nonEmptyAllStringWithRawGenericKeepsCompatMerge() {
        val original = ArrayList(listOf("a")) as List<*>
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = java.util.List::class.java,
        )
        // Raw generic: legacy compat branch still merges all-String non-empty.
        assertEquals(listOf("a", "b"), out)
        assertTrue(logs.isEmpty())
    }
}
