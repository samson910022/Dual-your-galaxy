package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// JVM unit tests for the signature-guard policy (WhitelistPolicy).
// Pure Kotlin/JUnit4 with hand-written inputs -- no Mockito, no framework
// stubs: WhitelistPolicy takes plain result/args and a log lambda.
class WhitelistPolicyTest {

    @Before
    fun resetLogBudget() {
        // P2-4 bounded-log isolation: the 8-log process budget would otherwise
        // leak across tests in one JVM (each test below expects its own log).
        WhitelistPolicy.resetUnexpectedArgsLogsForTest()
    }

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
        // Two String args are ambiguous even if one is in extra; never guess.
        assertEquals(false, WhitelistPolicy.apply(false, listOf("a", "b"), setOf("a"), "m", log))
        assertEquals(1, logs.size)
        assertTrue(
            "log should carry observe-only marker, was: ${logs.single()}",
            logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
    }

    @Test
    fun booleanFalseSingleStringPlusIntInExtraFlipsTrue() {
        val out = WhitelistPolicy.apply(
            false, listOf("com.listed.app", 0), setOf("com.listed.app"), "m", log,
        )
        assertEquals(true, out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun booleanFalseIntPlusSingleStringInExtraFlipsTrue() {
        val out = WhitelistPolicy.apply(
            false, listOf(0, "com.listed.app"), setOf("com.listed.app"), "m", log,
        )
        assertEquals(true, out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun booleanFalseMultiArgUnlistedPackageStaysFalse() {
        val out = WhitelistPolicy.apply(
            false, listOf("com.other.app", 0), setOf("com.listed.app"), "m", log,
        )
        assertEquals(false, out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun booleanFalseMultiArgNoStringArgsIsObserveOnly() {
        val out = WhitelistPolicy.apply(false, listOf(0, 1), setOf("a"), "m", log)
        assertEquals(false, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
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
        assertEquals("String#length()", WhitelistPolicy.describe(m))
    }

    @Test
    fun listWithNonStringElementsIsObserveOnly() {
        val original = listOf(1, 2)
        val out = WhitelistPolicy.apply(original, emptyList(), setOf("x"), "m", log)
        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun mutableListWithMixedNonStringElementsIsObserveOnly() {
        val original = mutableListOf<Any>("a", 1)
        val out = WhitelistPolicy.apply(original, emptyList(), setOf("x"), "m", log)
        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun treeSetIsObserveOnlyPreservingSortedContract() {
        val original = java.util.TreeSet(listOf("a"))
        val out = WhitelistPolicy.apply(original, emptyList(), setOf("b"), "m", log)
        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun linkedHashSetStillMergesAsSet() {
        val original = linkedSetOf("a")
        val out = WhitelistPolicy.apply(original, emptyList(), setOf("a", "b"), "m", log)
        assertTrue("expected Set, got ${out?.javaClass?.name}", out is Set<*>)
        assertEquals(setOf("a", "b"), out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun booleanStrictSignatureWithoutIndexIsObserveOnly() {
        val out = WhitelistPolicy.apply(
            false,
            listOf("com.listed.app"),
            setOf("com.listed.app"),
            "m",
            log,
            packageArgIndex = null,
            strictSignature = true,
        )
        assertEquals(false, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun booleanExplicitPackageArgIndexZeroFlipsWhenInExtra() {
        val out = WhitelistPolicy.apply(
            false,
            listOf("com.listed.app", 0),
            setOf("com.listed.app"),
            "m",
            log,
            packageArgIndex = 0,
            strictSignature = true,
        )
        assertEquals(true, out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun booleanExplicitPackageArgIndexOneFlipsWhenInExtra() {
        val out = WhitelistPolicy.apply(
            false,
            listOf(0, "com.listed.app"),
            setOf("com.listed.app"),
            "m",
            log,
            packageArgIndex = 1,
            strictSignature = true,
        )
        assertEquals(true, out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun declaredReturnTypeIncompatibleIsObserveOnly() {
        val original = listOf("a")
        val out = WhitelistPolicy.apply(
            original,
            emptyList(),
            setOf("b"),
            "m",
            log,
            declaredReturnType = java.util.TreeSet::class.java,
        )
        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }


    @Test
    fun setWithNonStringElementsIsObserveOnly() {
        val original = setOf(1, 2)
        val out = WhitelistPolicy.apply(original, emptyList(), setOf("x"), "m", log)

        assertSame(original, out)
        assertEquals(setOf(1, 2), out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun mutableSetWithMixedElementsIsObserveOnly() {
        val original = mutableSetOf<Any>("a", 1)
        val out = WhitelistPolicy.apply(original, emptyList(), setOf("b"), "m", log)

        assertSame(original, out)
        assertEquals(setOf<Any>("a", 1), out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun setMergeDeclaredReturnTypeIncompatibleIsObserveOnly() {
        val original = setOf("a")
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
        )

        assertSame(original, out)
        assertEquals(setOf("a"), out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    // A4: boxed Boolean runtime with ambiguous/non-Boolean declared return must not
    // trigger Boolean rewrite (fail-closed), even when index/signature/extra match.
    @Test
    fun booleanFalseDeclaredObjectIsObserveOnlyEvenWithValidIndexAndExtra() {
        val original = java.lang.Boolean.valueOf(false)
        val out = WhitelistPolicy.apply(
            result = original,
            args = listOf("com.listed.app"),
            extra = setOf("com.listed.app"),
            methodDesc = "m",
            onLog = log,
            packageArgIndex = 0,
            declaredReturnType = Any::class.java,
            strictSignature = true,
        )

        assertSame(original, out)
        assertEquals(false, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
        assertTrue(
            "expected non-Boolean declared return marker, was: ${logs.single()}",
            logs.single().contains("non-Boolean declared return"),
        )
    }

    @Test
    fun booleanFalseDeclaredJavaObjectIsObserveOnlyEvenWithValidIndexAndExtra() {
        val out = WhitelistPolicy.apply(
            result = java.lang.Boolean.valueOf(false),
            args = listOf("com.listed.app"),
            extra = setOf("com.listed.app"),
            methodDesc = "m",
            onLog = log,
            packageArgIndex = 0,
            declaredReturnType = java.lang.Object::class.java,
            strictSignature = true,
        )

        assertEquals(false, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun booleanFalseDeclaredJavaLangBooleanStillAllowsRewrite() {
        val out = WhitelistPolicy.apply(
            result = java.lang.Boolean.valueOf(false),
            args = listOf("com.listed.app"),
            extra = setOf("com.listed.app"),
            methodDesc = "m",
            onLog = log,
            packageArgIndex = 0,
            declaredReturnType = java.lang.Boolean::class.java,
            strictSignature = true,
        )

        assertEquals(true, out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun booleanFalseDeclaredPrimitiveBooleanStillAllowsRewrite() {
        val out = WhitelistPolicy.apply(
            result = java.lang.Boolean.valueOf(false),
            args = listOf("com.listed.app"),
            extra = setOf("com.listed.app"),
            methodDesc = "m",
            onLog = log,
            packageArgIndex = 0,
            declaredReturnType = java.lang.Boolean.TYPE,
            strictSignature = true,
        )

        assertEquals(true, out)
        assertTrue(logs.isEmpty())
    }

    // A2: empty generic collections must not be poisoned when merge would add
    // elements and element type is not verified as String.
    private interface GenericFixtures {
        fun stringList(): List<String>
        fun intList(): List<Int>
        fun stringSet(): Set<String>
        fun intSet(): Set<Int>
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun rawList(): java.util.List<*>
    }

    @Test
    fun emptyIntListWithIntGenericAndExtraIsObserveOnly() {
        val original = ArrayList<Int>() as List<*>
        val generic = GenericFixtures::class.java.getMethod("intList").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("com.x"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = generic,
        )

        assertSame(original, out)
        assertEquals(emptyList<Any?>(), out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
        assertTrue(
            "expected unverified/generic marker, was: ${logs.single()}",
            logs.single().contains("unverified collection element type/generic"),
        )
    }

    @Test
    fun emptyIntSetWithIntGenericAndExtraIsObserveOnly() {
        val original = HashSet<Int>() as Set<*>
        val generic = GenericFixtures::class.java.getMethod("intSet").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("com.x"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.Set::class.java,
            genericReturnType = generic,
        )

        assertSame(original, out)
        assertEquals(emptySet<Any?>(), out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun emptyListWithVerifiedStringGenericAndExtraMerges() {
        val original = ArrayList<String>() as List<*>
        val generic = GenericFixtures::class.java.getMethod("stringList").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("com.x"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = generic,
        )

        assertEquals(listOf("com.x"), out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun emptySetWithVerifiedStringGenericAndExtraMerges() {
        val original = LinkedHashSet<String>() as Set<*>
        val generic = GenericFixtures::class.java.getMethod("stringSet").genericReturnType
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("com.x"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.Set::class.java,
            genericReturnType = generic,
        )

        assertTrue(out is Set<*>)
        assertEquals(setOf("com.x"), out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun emptyListWithNullGenericAndExtraIsObserveOnly() {
        val original = ArrayList<String>() as List<*>
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("com.x"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = null,
        )

        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun emptyListWithRawClassGenericAndExtraIsObserveOnly() {
        val original = ArrayList<String>() as List<*>
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("com.x"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = java.util.List::class.java,
        )

        assertSame(original, out)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(WhitelistPatch.OBSERVE_ONLY_MARKER))
    }

    @Test
    fun nonEmptyAllStringListStillMergesWithNullGenericCompat() {
        val original = ArrayList(listOf("a")) as List<*>
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.List::class.java,
            genericReturnType = null,
        )

        assertEquals(listOf("a", "b"), out)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun nonEmptyAllStringSetStillMergesWithNullGenericCompat() {
        val original = LinkedHashSet(listOf("a")) as Set<*>
        val out = WhitelistPolicy.apply(
            result = original,
            args = emptyList(),
            extra = setOf("b"),
            methodDesc = "m",
            onLog = log,
            declaredReturnType = java.util.Set::class.java,
            genericReturnType = null,
        )

        assertTrue(out is Set<*>)
        assertEquals(setOf("a", "b"), out)
        assertTrue(logs.isEmpty())
    }

    // Phase 4:空 extra 時 unexpected-args 不記錄 (熱路徑靜默,observe-only)。
    @Test
    fun booleanFalseUnexpectedArgsWithEmptyExtraIsSilent() {
        val localLogs = mutableListOf<String>()
        assertEquals(false, WhitelistPolicy.apply(false, emptyList(), emptySet(), "m", { localLogs.add(it) }))
        assertTrue(localLogs.isEmpty())
    }

    // Phase 4:describe 快取一致 (二次呼叫同值,不拋)。
    @Test
    fun describeIsCachedConsistently() {
        val m = String::class.java.getMethod("length")
        val first = WhitelistPolicy.describe(m)
        val second = WhitelistPolicy.describe(m)
        assertEquals(first, second)
        assertEquals("String#length()", first)
    }

    // Phase 4:hostile extra（迭代即拋）不得越過邊界：第二段 addAll 與第一段
    // 同 try，失敗退回無 extra 的拷貝（observe-only，不拋）。
    @Test
    fun hostileExtraSetFallsBackWithoutThrowing() {
        val hostileExtra = object : AbstractSet<String>() {
            override val size: Int get() = 1
            override fun iterator(): MutableIterator<String> =
                throw IllegalStateException("boom")
        }
        val out = WhitelistPolicy.apply(setOf("a"), emptyList(), hostileExtra, "m", log)
        assertEquals(setOf("a"), out)
    }

    // Phase 4:hostile result（toMutableSet 拷貝即拋）退回原值（observe-only，
    // 同一實例，不拋）。
    @Test
    fun hostileResultSetReturnsOriginalWithoutThrowing() {
        val hostile = object : AbstractSet<String>() {
            override val size: Int get() = 1
            override fun iterator(): MutableIterator<String> =
                throw IllegalStateException("boom")
        }
        val out = WhitelistPolicy.apply(hostile, emptyList(), setOf("b"), "m", log)
        assertSame(hostile, out)
    }
}
