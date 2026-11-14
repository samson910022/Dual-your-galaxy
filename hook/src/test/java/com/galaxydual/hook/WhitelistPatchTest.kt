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
        assertTrue("empty extra should be Skipped", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should carry observe-only marker, was: ${o.reason}",
            o.reason.contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
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
        // Desensitized: only the short simple name is logged, never the
        // package-qualified name.
        assertTrue(reason.contains("Bar"))
        assertTrue(!reason.contains("com.foo.Bar"))
    }

    // Hostile ROM list whose iterator() throws: the element-type guard must
    // not let the failure escape apply() (it once propagated out of the
    // field loop and lost the sibling field's outcome). The fixture extends
    // AbstractMutableList because a plain AbstractList is not a MutableList
    // and would land in the unsupported-type branch instead of the
    // element-guard branch under test.
    private class HostileIteratorList : AbstractMutableList<String?>() {
        private val backing: MutableList<String?> = mutableListOf("a")
        override val size: Int get() = backing.size
        override fun get(index: Int): String? = backing[index]
        override fun set(index: Int, element: String?): String? = backing.set(index, element)
        override fun add(index: Int, element: String?) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String? = backing.removeAt(index)
        override fun iterator(): MutableIterator<String?> =
            throw IllegalStateException("injected iterator failure")
    }

    @Test
    fun hostileListIteratorThrowReturnsSkipped() {
        val out = WhitelistPatch.apply(HostileIteratorList(), true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should mention element inspection failure, was: ${o.reason}",
            o.reason.contains("element inspection failed"),
        )
    }

    // Simulates a hostile custom list: lands the element, then throws on the
    // 2nd insert of the patch (one-shot; 修復後的 rollback 走 subList 後綴
    // 清復 + 前綴驗證, 不再重插).
    private class PartialFailList(
        private val backing: MutableList<String> = mutableListOf(),
    ) : AbstractMutableList<String>() {
        private var inserts = 0
        private var armed = true

        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            inserts++
            if (armed && inserts == 2) {
                armed = false
                backing.add(index, element) // partial insert lands, then throw
                throw IllegalStateException("injected partial failure")
            }
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
    }

    // Permanently add-refusing but clearable AbstractMutableList: every
    // add() lands the element and then throws (append-then-throw, every
    // time). The pre-fix rollback (clear()+addAll(snapshot)) would lose the
    // originals; the subList(size0, size).clear() rollback must drop exactly
    // the appended suffix and keep the original prefix byte-for-byte.
    private class PermanentRefusalList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun clear() {
            backing.clear()
        }
    }

    @Test
    fun permanentlyAddRefusingListIsSkippedAndOriginalsPreserved() {
        val list = PermanentRefusalList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b", "c"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a", "z"), list)
    }

    // Add-refusing without appending: add() throws before mutating, so the
    // live list is never touched. The rollback must be a no-op (size == size0).
    private class AddThrowsWithoutAppendingList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            throw IllegalStateException("add refused without appending")
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun clear() {
            backing.clear()
        }
    }

    @Test
    fun addThrowingWithoutAppendingListIsSkippedAndOriginalsPreserved() {
        val list = AddThrowsWithoutAppendingList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b", "c"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a", "z"), list)
    }

    @Test
    fun partialFailureLeavesOriginalUntouchedAndSkipped() {
        val list = PartialFailList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b", "c"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a", "z"), list)
    }

    // Wrong-end removeAt: removeAt(i) removes the element at backing.size - 1 - i.
    // add() appends-then-throws. The (b) suffix drop then eats original
    // entries, the prefix verification must catch it, and the
    // clear()+addAll(snapshot) fallback restores the originals.
    // (P3 ISSUE 4: verify-fail fallback coverage.)
    private class WrongEndRemoveList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            // Refuse only the patch extras, so the fallback re-add of the
            // snapshot succeeds; the patch's own addAll then throws midway.
            if (element != "a" && element != "z") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String =
            backing.removeAt(backing.size - 1 - index)
        override fun clear() {
            backing.clear()
        }
    }

    @Test
    fun wrongEndRemoveAtIsCaughtByVerificationAndRestoredByFallback() {
        val list = WrongEndRemoveList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a", "z"), list)
    }

    // Wrong-end removeAt AND a clear() that throws: the verification still
    // fails, the fallback itself then throws, and the swallow must keep
    // apply() fail-closed — Skipped is returned, no exception escapes.
    // (Narrowed promise: list contents are not asserted here.)
    private class WrongEndClearThrowsList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            // Refuses patch extras (throws after the append). In THIS fixture
            // the fallback's re-add is never reached: clear() throws first
            // and is swallowed — the fixture pins the no-escape contract.
            if (element != "a" && element != "z") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String =
            backing.removeAt(backing.size - 1 - index)
        override fun clear() {
            throw IllegalStateException("clear refused")
        }
    }

    @Test
    fun wrongEndRemoveAtWithThrowingClearReturnsSkippedWithoutEscaping() {
        val list = WrongEndClearThrowsList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
    }

    // Detached-view subList: subList() returns a defensive copy, so the
    // (b) suffix drop clears the copy and leaves the live list untouched —
    // the prefix verification must catch size != size0 and the
    // clear()+addAll(snapshot) fallback restores the originals.
    // (add() refuses only the patch extras, so the fallback re-add succeeds.)
    private class DetachedSubListList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            if (element == "b") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> =
            ArrayList(backing.subList(fromIndex, toIndex))
        override fun clear() {
            backing.clear()
        }
    }

    @Test
    fun detachedSubListIsCaughtByVerificationAndRestoredByFallback() {
        val list = DetachedSubListList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a", "z"), list)
    }

    // size read throws: the pre-mutation guard must fail closed to Skipped
    // before anything is attempted, leaving the list untouched.
    // (ISSUE-3-a: the throwing-size arm of the two-tier rollback.)
    private class SizeThrowsList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        override val size: Int get() = throw IllegalStateException("size refused")
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
    }

    @Test
    fun sizeThrowingListFailsClosedToSkippedBeforeMutation() {
        val backing = mutableListOf("a", "z")
        val list = SizeThrowsList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        // Assert against the backing (the hostile list itself is unreadable).
        assertEquals(listOf("a", "z"), backing)
    }

    // Like SizeThrowsList, but iterator()/isEmpty() never touch size():
    // isEmpty() is pinned to false and iteration walks the backing directly.
    // The element-inspection guard therefore passes (it uses the iterator,
    // not size()), so the pre-mutation size0 guard is the first place that
    // observes the throwing size read — exercising ISSUE-3-a's guard, which
    // SizeThrowsList could never reach (its inherited iterator hasNext()
    // calls size() and fails inside the element-inspection guard first).
    private class ThrowingSizeOnlyList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        override val size: Int get() = throw IllegalStateException("size refused")
        override fun isEmpty(): Boolean = false
        override fun iterator(): MutableIterator<String> = backing.iterator()
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
    }

    @Test
    fun sizeThrowingOutsideIterationFailsClosedBeforeMutation() {
        val backing = mutableListOf("a", "z")
        val list = ThrowingSizeOnlyList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should mention the size-read guard, was: ${o.reason}",
            o.reason.contains("failed to read list size"),
        )
        // Assert against the backing (the hostile list itself is unreadable).
        assertEquals(listOf("a", "z"), backing)
    }

    // get() throws during the prefix verification (size stays readable and
    // consistent): the two-tier rollback's outer catch must count that as
    // verify-fail and fall back to clear()+addAll(snapshot), preserving the
    // originals. add() appends-then-throws for the patch extras only.
    // (ISSUE-3-b: the throwing-verification arm.)
    private class VerifyGetThrowsList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var mutating = false
        override val size: Int get() = backing.size
        override fun get(index: Int): String {
            // Throw only once mutation of the live list could have begun
            // (the union computation iterates via iterator(), not get()).
            if (mutating) throw IllegalStateException("get refused")
            return backing[index]
        }
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            mutating = true
            backing.add(index, element)
            if (element != "a" && element != "z") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun clear() {
            backing.clear()
        }
    }

    @Test
    fun getThrowingDuringVerificationFallsBackAndPreservesOriginals() {
        val backing = mutableListOf("a", "z")
        val list = VerifyGetThrowsList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        // Assert against the backing (the hostile list's get() throws).
        assertEquals(listOf("a", "z"), backing)
    }

    @Test
    fun unionDedupOrderUnaffectedByCopyOnWrite() {
        val list = mutableListOf("a", "b")
        val out = WhitelistPatch.apply(list, true, setOf("b", "c", "a", "d"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(2, (out as WhitelistPatch.Outcome.PatchedInPlace).added)
        assertEquals(listOf("a", "b", "c", "d"), list)
    }

    @Test
    fun emptyExtraWithFinalStillSkipped() {
        val arr = arrayOf("a")
        val out = WhitelistPatch.apply(arr, true, emptySet(), "F", "C", isFinal = true)
        assertTrue("empty policy should win over final, was: $out", out is WhitelistPatch.Outcome.Skipped)
    }

    @Test
    fun nonStaticFinalIsSkipped() {
        val arr = arrayOf("a")
        val out = WhitelistPatch.apply(arr, false, setOf("b"), "F", "C", isFinal = true)
        assertTrue("non-static should win over final, was: $out", out is WhitelistPatch.Outcome.Skipped)
    }
}
