// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the BBC injector's pure merge decision
// (BbcInjector.mergeAvailableList). Framework operations (installed/enabled
// PackageManager probes) are injected as predicates so the decision is locked
// without loading any Samsung/BBC class.
class BbcInjectorTest {

    private val allInstalled: (String) -> Boolean = { true }
    private val allEnabled: (String) -> Boolean = { true }

    @Test
    fun emptyPolicyReturnsOriginalByIdentity() {
        val original = listOf("com.a", "com.b")
        val merged = BbcInjector.mergeAvailableList(original, emptySet(), allInstalled, allEnabled)
        assertSame(original, merged)
    }

    @Test
    fun policyPackagesAreAppendedAtEndInPolicyOrder() {
        val original = listOf("com.samsung.a", "com.samsung.b")
        val merged = BbcInjector.mergeAvailableList(
            original,
            linkedSetOf("com.policy.z", "com.policy.y"),
            allInstalled,
            allEnabled,
        )
        assertEquals(listOf("com.samsung.a", "com.samsung.b", "com.policy.z", "com.policy.y"), merged)
    }

    @Test
    fun mergedResultIsNewListNotOriginal() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(original, setOf("com.policy.new"), allInstalled, allEnabled)
        assertNotSame(original, merged)
    }

    @Test
    fun originalOrderPreservedWithMerge() {
        val original = listOf("com.z", "com.y", "com.x")
        val merged = BbcInjector.mergeAvailableList(original, setOf("com.w"), allInstalled, allEnabled)
        assertEquals(listOf("com.z", "com.y", "com.x", "com.w"), merged)
    }

    @Test
    fun emptyOriginalMergesPolicyAtEnd() {
        val merged = BbcInjector.mergeAvailableList(emptyList(), setOf("com.new"), allInstalled, allEnabled)
        assertEquals(listOf("com.new"), merged)
    }

    @Test
    fun policyEntryAlreadyInOriginalIsNotReAdded() {
        val original = listOf("com.dup", "com.keep", "com.dup")
        // Nothing survives the merge (com.dup already present): identity,
        // original duplicates preserved exactly as Samsung returned them.
        val merged = BbcInjector.mergeAvailableList(original, setOf("com.dup"), allInstalled, allEnabled)
        assertSame(original, merged)
    }

    @Test
    fun policyEntryDuplicatedInOriginalIsNotReAppendedAndOriginalDuplicatesSurvive() {
        val original = listOf("com.dup", "com.keep", "com.dup")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("com.dup", "com.new"),
            allInstalled,
            allEnabled,
        )
        // Samsung's own list is copied verbatim (duplicates survive); the
        // policy-side duplicate of com.dup is dropped; com.new is appended.
        assertEquals(listOf("com.dup", "com.keep", "com.dup", "com.new"), merged)
    }

    @Test
    fun duplicatesInOriginalArePreservedVerbatim() {
        val original = listOf("com.a", "com.b", "com.a")
        val merged = BbcInjector.mergeAvailableList(original, setOf("com.c"), allInstalled, allEnabled)
        assertEquals(listOf("com.a", "com.b", "com.a", "com.c"), merged)
    }

    @Test
    fun mergeStopsAtMaxPolicyEntriesCap() {
        val original = listOf("com.samsung.a")
        // Exactly the pre-filter cap: all 512 entries are appended.
        val atCap = (1..PolicySource.MAX_POLICY_ENTRIES).map { "com.cap.p$it" }.toCollection(LinkedHashSet())
        val mergedAtCap = BbcInjector.mergeAvailableList(original, atCap, allInstalled, allEnabled)
        assertEquals(1 + PolicySource.MAX_POLICY_ENTRIES, mergedAtCap.size)
        assertEquals(atCap.toList(), mergedAtCap.subList(1, mergedAtCap.size))

        // One over the cap: the loop stops at MAX_POLICY_ENTRIES additions.
        val overCap = (1..PolicySource.MAX_POLICY_ENTRIES + 1).map { "com.cap.q$it" }.toCollection(LinkedHashSet())
        val mergedOverCap = BbcInjector.mergeAvailableList(original, overCap, allInstalled, allEnabled)
        assertEquals(1 + PolicySource.MAX_POLICY_ENTRIES, mergedOverCap.size)
        assertEquals(
            overCap.take(PolicySource.MAX_POLICY_ENTRIES),
            mergedOverCap.subList(1, mergedOverCap.size),
        )
    }

    @Test
    fun duplicatesInPolicyAreImpossibleByType() {
        // A Set<String> cannot hold duplicates; the merge therefore never
        // appends the same policy package twice. Lock the construction here.
        val policy = linkedSetOf("com.net", "com.net")
        assertEquals(1, policy.size)
        val merged = BbcInjector.mergeAvailableList(listOf("com.base"), policy, allInstalled, allEnabled)
        assertEquals(listOf("com.base", "com.net"), merged)
    }

    @Test
    fun invalidPolicyPackageIsFiltered() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("bad pkg!", "com.ok.app"),
            allInstalled,
            allEnabled,
        )
        assertEquals(listOf("com.samsung.a", "com.ok.app"), merged)
    }

    @Test
    fun allInvalidPolicyReturnsOriginalByIdentity() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("bad pkg!", "", "semi;colon", "slash/x"),
            allInstalled,
            allEnabled,
        )
        assertSame(original, merged)
    }

    @Test
    fun notInstalledPolicyPackageIsFiltered() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("com.not.installed", "com.installed"),
            { it != "com.not.installed" },
            allEnabled,
        )
        assertEquals(listOf("com.samsung.a", "com.installed"), merged)
    }

    @Test
    fun allPolicyPackagesNotInstalledReturnsOriginalByIdentity() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(original, setOf("com.a", "com.b"), { false }, allEnabled)
        assertSame(original, merged)
    }

    @Test
    fun notEnabledPolicyPackageIsFiltered() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("com.disabled", "com.enabled"),
            allInstalled,
            { it != "com.disabled" },
        )
        assertEquals(listOf("com.samsung.a", "com.enabled"), merged)
    }

    @Test
    fun allPolicyPackagesDisabledReturnsOriginalByIdentity() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(original, setOf("com.a", "com.b"), allInstalled, { false })
        assertSame(original, merged)
    }

    @Test
    fun policyFullyContainedInOriginalReturnsOriginalByIdentity() {
        // Nothing merged => the exact original object (no new allocation).
        val original = listOf("com.a", "com.b")
        val merged = BbcInjector.mergeAvailableList(original, setOf("com.a", "com.b"), allInstalled, allEnabled)
        assertSame(original, merged)
    }

    @Test
    fun policy128EntriesMerge() {
        val original = listOf("com.samsung.a", "com.samsung.b")
        val policy = (1..128).map { "com.policy.p$it" }.toSet()
        val merged = BbcInjector.mergeAvailableList(original, policy, allInstalled, allEnabled)
        assertEquals(130, merged.size)
        assertEquals(original, merged.subList(0, 2))
        assertEquals(policy.toList(), merged.subList(2, 130))
        assertTrue(merged.containsAll(policy))
    }

    @Test
    fun installedPredicateThrowingFiltersThatPackage() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("com.boom", "com.ok"),
            { pkg -> if (pkg == "com.boom") throw RuntimeException("pm down") else true },
            allEnabled,
        )
        assertEquals(listOf("com.samsung.a", "com.ok"), merged)
    }

    @Test
    fun enabledPredicateThrowingFiltersThatPackage() {
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("com.boom", "com.ok"),
            allInstalled,
            { pkg -> if (pkg == "com.boom") throw RuntimeException("pm down") else true },
        )
        assertEquals(listOf("com.samsung.a", "com.ok"), merged)
    }

    @Test
    fun overlongPolicyTokenIsDroppedBeforeValidCheck() {
        val original = listOf("com.samsung.a")
        val longOk = "a".repeat(PolicySource.MAX_PACKAGE_LENGTH + 1) // grammar-valid, over length cap
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf(longOk, "com.ok.app"),
            allInstalled,
            allEnabled,
        )
        assertEquals(listOf("com.samsung.a", "com.ok.app"), merged)
    }

    @Test
    fun policyStoppingAtMaxEntriesIsHonored() {
        val original = listOf("com.samsung.a")
        // MAX_POLICY_ENTRIES policy entries, all of them unique tokens:
        // the merge accepts the cap verbatim (no more than MAX entries added).
        val cap = PolicySource.MAX_POLICY_ENTRIES
        val policy = LinkedHashSet<String>()
        for (i in 0 until cap) policy.add("com.policy.pad$i")
        val merged = BbcInjector.mergeAvailableList(original, policy, allInstalled, allEnabled)
        // original (1) + exactly MAX_POLICY_ENTRIES merged entries
        assertEquals(1 + cap, merged.size)
        // One beyond the cap never reaches the merge: the set passed here
        // already holds the cap; adding the +1 next entry is the merge's
        // saturating semantics — verify with a CAP+1 distinct set:
        val policyPlusOne = LinkedHashSet(policy)
        policyPlusOne.add("com.policy.pad.one.extra")
        val merged2 = BbcInjector.mergeAvailableList(original, policyPlusOne, allInstalled, allEnabled)
        assertEquals(1 + cap, merged2.size)
        assertEquals("com.samsung.a", merged2.first())
    }

    @Test
    fun predicatesThrowingEverywhereReturnsOriginalByIdentity() {
        // Total failure of both probes => fail-closed: original object, no merge.
        val original = listOf("com.samsung.a")
        val merged = BbcInjector.mergeAvailableList(
            original,
            setOf("com.boom1", "com.boom2"),
            { throw IllegalStateException("pm down") },
            { throw IllegalStateException("pm down") },
        )
        assertSame(original, merged)
    }
}
