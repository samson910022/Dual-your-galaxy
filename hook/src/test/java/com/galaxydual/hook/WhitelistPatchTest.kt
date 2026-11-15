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
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
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
    // add() appends-then-throws. The ours-drop (subList(size0, size).clear(),
    // via removeRange → wrong-end removeAt) eats an ORIGINAL entry; the
    // re-verify must catch it, and NO whole-list restore runs (prefix-intact
    // entry, failed drop → no further mutator invoked; the list is left as
    // the drop left it).
    // (P3 ISSUE 4: verify-fail coverage. NEW contract.)
    private class WrongEndRemoveList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var cleared = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            // Refuse only the patch extras, so a hypothetical restore re-add
            // of the snapshot would succeed; the patch's own addAll throws midway.
            if (element != "a" && element != "z") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String =
            backing.removeAt(backing.size - 1 - index)
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun wrongEndRemoveAtFailedDropInvokesNoMutator() {
        val list = WrongEndRemoveList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        // The wrong-end drop ate the original 'a' (backing was [a, z, b],
        // drop of index 2 removed index 0); re-verification failed, so NO
        // further mutator runs — the whole-list restore is refused on this
        // prefix-intact entry. The list is left as the drop left it.
        assertEquals(listOf("z", "b"), list)
        assertEquals(0, list.cleared)
        assertTrue(
            "reason should report no further mutator invoked, was: ${o.reason}",
            o.reason.contains("no further mutator invoked"),
        )
        assertTrue(
            "reason should carry the honest dropFailedOutcome marker (originals may be lost / no further mutator invoked), was: ${o.reason}",
            o.reason.contains("originals may be lost") && o.reason.contains("no further mutator invoked"),
        )
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    // Wrong-end removeAt AND a clear() that throws: the ours-drop corrupts
    // the list (wrong-end removeAt), the re-verify fails, and NO whole-list
    // restore runs — so the throwing clear() is never invoked and the
    // swallow contract still keeps apply() fail-closed (Skipped, no
    // exception escapes). NEW contract.
    private class WrongEndClearThrowsList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var clearCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            // Refuses patch extras (throws after the append). The restore is
            // never reached under the new contract: the failed ours-drop
            // routes to the no-mutator Skipped, so clear() must not run.
            if (element != "a" && element != "z") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String =
            backing.removeAt(backing.size - 1 - index)
        override fun clear() {
            clearCalls++
            throw IllegalStateException("clear refused")
        }
    }

    @Test
    fun wrongEndRemoveAtFailedDropNeverReachesThrowingClear() {
        val list = WrongEndClearThrowsList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report no further mutator invoked, was: ${o.reason}",
            o.reason.contains("no further mutator invoked"),
        )
        // The failing ours-drop must NOT escalate to the whole-list restore:
        // the throwing clear() is never invoked.
        assertEquals(0, list.clearCalls)
        assertTrue(!o.reason.contains("last-restore failed"))
    }

    // Detached-view subList: subList() returns a defensive copy, so the
    // ours-drop clears the copy and leaves the live list holding ours.
    // The re-verify catches the size not shrinking, and NO whole-list
    // restore runs (prefix-intact entry, failed drop → no further mutator
    // invoked). add() refuses only the patch extras, so a hypothetical
    // restore re-add of the snapshot would succeed — it must not run.
    // NEW contract.
    private class DetachedSubListList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var cleared = 0
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
            cleared++
            backing.clear()
        }
    }

    @Test
    fun detachedSubListRefusedDropInvokesNoRestore() {
        val list = DetachedSubListList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        // The detached subList swallowed the ours-drop; the live list keeps
        // ours; the re-verify fails; no further mutator runs.
        assertEquals(listOf("a", "z", "b"), list)
        assertEquals(0, list.cleared)
        assertTrue(
            "reason should report no further mutator invoked, was: ${o.reason}",
            o.reason.contains("no further mutator invoked"),
        )
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    // subList() throws instead of returning a view: the ours-drop throws and
    // is swallowed, the re-verify fails (size unchanged), and NO whole-list
    // restore runs — clear() is never invoked. add() appends-then-throws for
    // the patch extras only, so a hypothetical restore re-add of the
    // snapshot would succeed — it must not run. NEW contract.
    private class SubListThrowsList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var cleared = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            // Refuse only the patch extras, so the fallback re-add of the
            // snapshot succeeds; the patch's own addAll then throws midway.
            if (element != "a" && element != "z") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> =
            throw IllegalStateException("subList refused")
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun subListThrowsDropFailsAndNoRestoreRuns() {
        val list = SubListThrowsList(mutableListOf("a", "z"))
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        // The subList() threw; the ours-drop never landed; the live list
        // keeps ours; the re-verify fails; no further mutator runs.
        assertEquals(listOf("a", "z", "b"), list)
        assertEquals(0, list.cleared)
        assertTrue(
            "reason should report no further mutator invoked, was: ${o.reason}",
            o.reason.contains("no further mutator invoked"),
        )
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    // Detached-view subList AND a restore that keeps only the FIRST snapshot
    // element: the patch's addAll lands the extra then throws (arm (b)); the
    // ours-drop clears the detached copy, so the live list keeps the
    // extras; the re-verify must fail — and NO whole-list restore runs
    // (prefix-intact entry, failed drop → no further mutator invoked).
    private class DetachedSubListPrefixKeepList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var calls = 0
        var cleared = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
            // Refuse the patch extras (throws after the append); the full
            // snapshot (com.a/com.b) is accepted so the prefix-keeping
            // restore can run.
            if (element != "com.a" && element != "com.b") throw IllegalStateException("add refused")
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> =
            ArrayList(backing.subList(fromIndex, toIndex))
        override fun clear() {
            cleared++
            backing.clear()
        }
        override fun addAll(elements: Collection<String>): Boolean {
            calls++
            // First call is the patch's addAll: apply (partially) and throw
            // via the default addAll path, landing in arm (b).
            if (calls == 1) return super.addAll(elements)
            // Hypothetical restore re-add: keep only the FIRST snapshot
            // element, return true. Under the new contract this must run
            // ZERO times (no whole-list restore on a prefix-intact entry).
            val it = elements.iterator()
            if (it.hasNext()) add(it.next())
            return true
        }
    }

    @Test
    fun detachedSubListRefusedDropKeepsListAndNoRestoreRuns() {
        val list = DetachedSubListPrefixKeepList(mutableListOf("com.a", "com.b"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        val o = out as WhitelistPatch.Outcome.Skipped
        // The detached subList swallowed the ours-drop; the live list keeps
        // ours; the re-verify fails; no further mutator runs — the prefix-
        // keeping restore must NOT run.
        assertEquals(listOf("com.a", "com.b", "com.example.extra"), list)
        assertEquals(0, list.cleared)
        assertTrue(
            "reason should report no further mutator invoked, was: ${o.reason}",
            o.reason.contains("no further mutator invoked"),
        )
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("last-restore failed"))
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
    // consistent): the unified ownership-contract settle sees an unreadable
    // list at its entry probe (matchesPrefixLen returns -1) and must invoke
    // NO mutator at all — the list is left exactly as addAll left it. (add()
    // appends-then-throws for the patch extras only. ISSUE-3-b: the
    // throwing-verification arm. NEW contract: no whole-list restore runs
    // on an unreadable list — that was the H-A fail-open.)
    private class VerifyGetThrowsList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var mutating = false
        var cleared = 0
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
            cleared++
            backing.clear()
        }
    }

    @Test
    fun getThrowingDuringVerificationInvokesNoMutator() {
        val backing = mutableListOf("a", "z")
        val list = VerifyGetThrowsList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the unverifiable list, was: ${o.reason}",
            o.reason.contains("verification could not read the list (get threw)"),
        )
        assertTrue(
            "reason should report no mutator invoked, was: ${o.reason}",
            o.reason.contains("no mutator invoked"),
        )
        // Assert against the backing (the hostile list's get() throws).
        assertEquals(listOf("a", "z", "b"), backing)
        assertEquals(0, list.cleared)
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    @Test
    fun unionDedupOrderUnaffectedByCopyOnWrite() {
        val list = mutableListOf("a", "b")
        val out = WhitelistPatch.apply(list, true, setOf("b", "c", "a", "d"), "F", "C")
        assertTrue(out is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(2, (out as WhitelistPatch.Outcome.PatchedInPlace).added)
        assertEquals(listOf("a", "b", "c", "d"), list)
    }

    // addAll that legally returns false WITHOUT mutating the list (a legal
    // no-op return per the Collection contract). clear() is counted to pin
    // the early unmodified-list return: no mutator may run.
    private class NoOpAddAllFalseList(contents: Collection<String>) : ArrayList<String>(contents) {
        var cleared = 0
        override fun addAll(elements: Collection<String>): Boolean = false
        override fun clear() {
            cleared++
            super.clear()
        }
    }

    @Test
    fun noOpAddAllFalseReturnsSkippedAndPreservesOriginals() {
        val list = NoOpAddAllFalseList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertEquals(listOf("com.samsung.seed", "com.samsung.region"), list)
        assertEquals(0, list.cleared)
        assertTrue("reason should mention the unchanged list, was: ${o.reason}", o.reason.contains("the list is unchanged"))
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
    }

    // Same hostile-but-normal no-op addAll, but returns true (lying about
    // having appended). Same expectations: verification fails, the list is
    // unchanged since the snapshot, early return, zero mutators invoked.
    private class NoOpAddAllTrueList(contents: Collection<String>) : ArrayList<String>(contents) {
        var cleared = 0
        override fun addAll(elements: Collection<String>): Boolean = true
        override fun clear() {
            cleared++
            super.clear()
        }
    }

    @Test
    fun noOpAddAllTrueReturnsSkippedAndPreservesOriginals() {
        val list = NoOpAddAllTrueList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertEquals(listOf("com.samsung.seed", "com.samsung.region"), list)
        assertEquals(0, list.cleared)
        assertTrue("reason should mention the unchanged list, was: ${o.reason}", o.reason.contains("the list is unchanged"))
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
    }

    // Patch addAll drops the first original (seed) and returns true,
    // inserting nothing. Stateful: only the FIRST addAll call mangles; the
    // fallback's re-add behaves normally so the restore can succeed.
    private class PartialSuccessManglesOriginalsList(contents: Collection<String>) : ArrayList<String>(contents) {
        var cleared = 0
        private var first = true
        override fun addAll(elements: Collection<String>): Boolean {
            if (first) {
                first = false
                removeAt(0)
                return true
            }
            return super.addAll(elements)
        }
        override fun clear() {
            cleared++
            super.clear()
        }
    }

    @Test
    fun partialSuccessManglesOriginalsWhichAreRestored() {
        val list = PartialSuccessManglesOriginalsList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(listOf("com.samsung.seed", "com.samsung.region"), list)
        // The fallback ran (verification failed and the list was changed).
        assertEquals(1, list.cleared)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    // Patch addAll seeds hostile junk at index 0 and returns true, changing
    // the list so the early unmodified-list return does NOT apply. The
    // verification prefix leg catches the junk; the fallback restores.
    private class PartialSuccessHeadJunkList(contents: Collection<String>) : ArrayList<String>(contents) {
        var cleared = 0
        private var first = true
        override fun addAll(elements: Collection<String>): Boolean {
            if (first) {
                first = false
                add(0, "com.hostile.junk")
                return true
            }
            return super.addAll(elements)
        }
        override fun clear() {
            cleared++
            super.clear()
        }
    }

    @Test
    fun partialSuccessHeadJunkIsRestored() {
        val list = PartialSuccessHeadJunkList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(listOf("com.samsung.seed", "com.samsung.region"), list)
        // The fallback ran (verification failed and the list was changed).
        assertEquals(1, list.cleared)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    // Patch addAll drops the original tail ("region"), seeds hostile junk at
    // index 0, then appends the real delta correctly. The post-success
    // verification's size leg (3 == 2 + 1) and suffix leg (element at
    // index 2 == the delta) both PASS — only the prefix leg fails. That is
    // what makes this fixture unique: deleting the prefix leg from the
    // post-success check flips this scenario to a false
    // PatchedInPlace(1). `capturedAfterFirstAddAll` pins the post-mutation
    // state so the test can assert exactly that leg-by-leg picture.
    private class HeadJunkWithAddList(contents: Collection<String>) : ArrayList<String>(contents) {
        var capturedAfterFirstAddAll: List<String>? = null
        private var first = true
        override fun addAll(elements: Collection<String>): Boolean {
            if (first) {
                first = false
                removeAt(size - 1)   // drop original tail (e.g. "region")
                add(0, "com.hostile.junk")  // junk at head
                super.addAll(elements)      // append the real delta correctly
                capturedAfterFirstAddAll = ArrayList(this)
            } else return super.addAll(elements)
            return true
        }
    }

    @Test
    fun postSuccessPrefixCorruptionIsCaughtAndRestored() {
        val list = HeadJunkWithAddList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(listOf("com.samsung.seed", "com.samsung.region"), list)
        // Leg-by-leg picture at the moment verification ran: the size leg and
        // the suffix leg pass, only the prefix leg fails — pinning the prefix leg (the size and suffix legs are pinned by tests further down):
        // size 3, suffix at index 2 == the delta, junk at index 0.
        assertEquals(
            listOf("com.hostile.junk", "com.samsung.seed", "com.example.extra"),
            list.capturedAfterFirstAddAll,
        )
    }

    // Patch addAll super-appends the real delta and THEN appends hostile
    // junk after it, and returns true. Stateful: only the FIRST addAll call
    // mangles; subsequent calls behave normally. The post-success
    // verification's size leg fails (4 != 2 + 1) — the post-success
    // ok-expression never reads index 3 (its legs are size/prefix/full
    // delta). At settle time the suffix probe (index 3 == "com.hostile.tail",
    // not the delta) fails and the front-aligned probe (index 2 == the
    // delta) passes — ours dropped, foreign tail retained. That is what makes
    // this fixture unique: deleting the size leg from the post-success
    // check flips this scenario to a false PatchedInPlace(1). NEW contract
    // (ownership-drop arm): the ours delta is front-aligned at the snapshot
    // boundary, so ONLY it is dropped; the foreign tail survives.
    private class TrailingJunkAfterDeltaList(contents: Collection<String>) : ArrayList<String>(contents) {
        private var first = true
        override fun addAll(elements: Collection<String>): Boolean {
            if (first) {
                first = false
                super.addAll(elements)
                add("com.hostile.tail")
                return true
            }
            return super.addAll(elements)
        }
    }

    @Test
    fun trailingForeignSuffixIsRetainedWithOursDropped() {
        val list = TrailingJunkAfterDeltaList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        val o = out as WhitelistPatch.Outcome.Skipped
        // NEW contract: ours (front-aligned at the snapshot boundary) are
        // rolled back; the foreign tail after them is RETAINED.
        assertEquals(listOf("com.samsung.seed", "com.samsung.region", "com.hostile.tail"), list)
        assertTrue(
            "reason should report ours rolled back with the foreign tail retained, was: ${o.reason}",
            o.reason.contains("appended-ours") && o.reason.contains("foreign entries retained"),
        )
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    // Patch addAll appends hostile junk at the tail instead of the real
    // delta, and returns true. Stateful: only the FIRST addAll call mangles.
    // The post-success verification's size leg (3 == 2 + 1) and prefix leg
    // both PASS — only the suffix leg fails (element at index 2 ==
    // "com.hostile.fake", not the delta). That is what makes this fixture
    // unique: deleting the suffix leg from the post-success check flips this
    // scenario to a false PatchedInPlace(1). NEW contract: prefix intact,
    // ours NOT locatable (addAll appended non-ours bytes) — NO mutator runs,
    // the junk tail is left in place (no whole-list restore on a
    // prefix-intact path).
    private class WrongSuffixDeltaList(contents: Collection<String>) : ArrayList<String>(contents) {
        private var first = true
        override fun addAll(elements: Collection<String>): Boolean {
            if (first) {
                first = false
                add("com.hostile.fake")
                return true
            }
            return super.addAll(elements)
        }
    }

    @Test
    fun wrongSuffixDeltaIsNotRestoredAndForeignJunkRetained() {
        val list = WrongSuffixDeltaList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        val o = out as WhitelistPatch.Outcome.Skipped
        // NEW contract: ours are not locatable anywhere in the tail, so NO
        // mutator runs and the list keeps exactly what addAll left there.
        assertEquals(listOf("com.samsung.seed", "com.samsung.region", "com.hostile.fake"), list)
        assertTrue(
            "reason should report ours could not be located, was: ${o.reason}",
            o.reason.contains("could not be located"),
        )
        assertTrue(
            "reason should report no mutator invoked, was: ${o.reason}",
            o.reason.contains("no mutator invoked"),
        )
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertTrue(!o.reason.contains("restore incomplete"))
    }

    // Post-success mirror of VerifyGetThrowsList: add() is the same
    // append-then-throw as VerifyGetThrowsList (here unused on this arm —
    // addAll applies the delta without going through add() and returns
    // normally), get() throws once mutation could have begun, size stays
    // readable. The post-success verification read throws (swallowed as -1
    // by matchesPrefixLen, so `ok` evaluates to false), and the
    // post-success decision tree must invoke NO mutator at all — the list
    // is left exactly as addAll left it. (This fixture predates the H3 /
    // hostile-read decision tree, which forbids the restore mutator here;
    // the pre-Phase-5 expectations — restore runs, re-verify throws,
    // "; restore unverified" — were updated to the new contract.)
    private class PostSuccessVerifyGetThrowsList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var mutating = false
        var cleared = 0
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
            if (element != "com.a" && element != "com.b") throw IllegalStateException("add refused")
        }
        override fun addAll(elements: Collection<String>): Boolean {
            mutating = true
            // Apply the delta without going through the throwing add(): the
            // post-success arm requires a normally-returning addAll.
            return backing.addAll(elements)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun postSuccessVerificationReadThrowsInvokesNoMutator() {
        val backing = mutableListOf("com.a", "com.b")
        val list = PostSuccessVerifyGetThrowsList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the unverifiable (unreadable) list, was: ${o.reason}",
            o.reason.contains("verification could not read the list (get threw)"),
        )
        assertTrue(
            "reason must not claim a restore ran, was: ${o.reason}",
            !o.reason.contains("restore unverified"),
        )
        // The restore (clear()+addAll) must NOT run: the verification read
        // threw, so no mutator may be invoked. The list keeps exactly what
        // addAll put there (the delta, unverifiable either way).
        assertEquals(0, list.cleared)
        assertEquals(listOf("com.a", "com.b", "com.example.extra"), backing)
    }

    // Patch addAll removes the LAST element and returns true. clear() is
    // counted. The fallback's addAll(snapshot) adds only the FIRST element
    // of the snapshot and returns normally (statefully: first call mangles,
    // subsequent calls keep the prefix only, without throwing). The
    // re-verify must flag the prefix-only restore.
    private class PrefixOnlyRestoreList(contents: Collection<String>) : ArrayList<String>(contents) {
        var cleared = 0
        private var calls = 0
        override fun addAll(elements: Collection<String>): Boolean {
            calls++
            if (calls == 1) {
                removeAt(size - 1)
                return true
            }
            val it = elements.iterator()
            if (it.hasNext()) add(it.next())
            return true
        }
        override fun clear() {
            cleared++
            super.clear()
        }
    }

    @Test
    fun fallbackKeepsOnlyPrefixAndReportsIt() {
        val list = PrefixOnlyRestoreList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertEquals(listOf("com.samsung.seed"), list)
        assertEquals(1, list.cleared)
        assertTrue(
            "reason should report the kept prefix, was: ${o.reason}",
            o.reason.contains("restore incomplete (kept 1 of 2 original entries)"),
        )
    }

    // Patch addAll drops the LAST element and returns true. The fallback's
    // clear() throws, so the last-resort restore itself fails; the
    // swallow must keep apply() fail-closed.
    private class FallbackClearThrowsList(contents: Collection<String>) : ArrayList<String>(contents) {
        private var first = true
        override fun addAll(elements: Collection<String>): Boolean {
            if (first) {
                first = false
                removeAt(size - 1)
                return true
            }
            return super.addAll(elements)
        }
        override fun clear() {
            throw IllegalStateException("clear refused")
        }
    }

    @Test
    fun fallbackRestoreThrowsAfterMangle() {
        val list = FallbackClearThrowsList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the failed restore, was: ${o.reason}",
            o.reason.contains("last-restore failed"),
        )
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
    }

    // Patch addAll drops the LAST original and returns true (mangle). Every
    // addAll after the first returns true WITHOUT adding anything — a
    // normally-returning no-op restore is NOT proof of success: the
    // fallback's re-add "succeeds" by keeping zero snapshot elements, and
    // the re-verify must report kept 0 of the original size.
    private class NoOpRestoreList(contents: Collection<String>) : ArrayList<String>(contents) {
        var cleared = 0
        private var calls = 0
        override fun addAll(elements: Collection<String>): Boolean {
            calls++
            if (calls == 1) {
                removeAt(size - 1)  // drop the tail: [seed, region] -> [seed]
                return true
            }
            // Calls >= 2 (the restore re-add): claim success, add nothing.
            return true
        }
        override fun clear() {
            cleared++
            super.clear()
        }
    }

    @Test
    fun restoreCompletingAsNoopIsReportedWithKeptZeroMarker() {
        val list = NoOpRestoreList(listOf("com.samsung.seed", "com.samsung.region"))
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertEquals(emptyList<String>(), list)
        assertEquals(1, list.cleared)
        assertTrue(
            "reason should report the incomplete restore, was: ${o.reason}",
            o.reason.contains("restore incomplete"),
        )
        assertTrue(
            "reason should report kept 0 of 2, was: ${o.reason}",
            o.reason.contains("kept 0 of 2"),
        )
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

    // ------------------------------------------------------------------
    // Phase 5 fixtures (H3 hostile-read verification / H1 rollback
    // ownership check). All extend AbstractMutableList like the fixtures
    // above; mutator counts are recorded via overrides and assertions run
    // against the backing list.
    // ------------------------------------------------------------------

    // Patch addAll returns false WITHOUT appending anything (a legal no-op
    // return per the Collection contract); from then on every get() throws,
    // so the post-success verification reads are unverifiable (size stays
    // readable). The decision tree must invoke NO mutator beyond the
    // original addAll: the list keeps the originals byte-for-byte and the
    // Skipped reason carries the "verification could not read" marker.
    private class PoisonGetList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var armed = false
        var cleared = 0
        var addAllCalls = 0
        var subListCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String {
            // Throw only after the patch's addAll has run: the union
            // computation and element inspection must see a healthy list,
            // otherwise they fail first with a different (pre-mutation)
            // Skipped arm and the post-success arm is never reached.
            if (armed) throw IllegalStateException("get refused")
            return backing[index]
        }
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            armed = true
            return false
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun poisonGetListReturnsSkippedWithNoMutatorInvoked() {
        val backing = mutableListOf("a", "z")
        val list = PoisonGetList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the unverifiable list, was: ${o.reason}",
            o.reason.contains("verification could not read the list (get threw)"),
        )
        assertEquals(0, list.cleared)
        assertEquals(1, list.addAllCalls)
        assertEquals(0, list.subListCalls)
        assertEquals(listOf("a", "z"), backing)
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
    }

    // Like PoisonGetList, but size() throws from then on while get() stays
    // readable. isEmpty()/iterator() are pinned so nothing before the
    // post-success arm trips on the size read prematurely (size is read at
    // the pre-mutation guard BEFORE the patch's addAll runs, while the list
    // is still healthy).
    private class PoisonSizeList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var armed = false
        var cleared = 0
        var addAllCalls = 0
        var subListCalls = 0
        override val size: Int
            get() {
                if (armed) throw IllegalStateException("size refused")
                return backing.size
            }
        override fun isEmpty(): Boolean = backing.isEmpty()
        override fun iterator(): MutableIterator<String> = backing.iterator()
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            armed = true
            return false
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun poisonSizeListReturnsSkippedWithNoMutatorInvoked() {
        val backing = mutableListOf("a", "z")
        val list = PoisonSizeList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the unverifiable list size, was: ${o.reason}",
            o.reason.contains("verification could not read the list size"),
        )
        assertEquals(0, list.cleared)
        assertEquals(1, list.addAllCalls)
        assertEquals(0, list.subListCalls)
        assertEquals(listOf("a", "z"), backing)
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
    }

    // Like PoisonSizeList, but size() reports a negative garbage value from
    // then on (never throws) while get() stays readable. isEmpty()/iterator()
    // are pinned so nothing before the throw-arm trips on the size read
    // prematurely (size is read at the pre-mutation guard BEFORE the patch's
    // addAll runs, while the list is still healthy — the stateful fixture
    // presents 2 until addAll is called, then -1 forever). The addAll
    // override throws WITHOUT appending anything and arms the garbage-size
    // state, so the settle path is reached with size reporting -1: a
    // negative size is indeterminate garbage — it must never be treated as
    // a readable short list (which would mis-route into the prefix-damaged
    // restore arm and clear() the originals), and must land in the
    // no-mutator Skipped arm.
    private class NegativeGarbageSizeList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var armed = false
        var cleared = 0
        var addAllCalls = 0
        var subListCalls = 0
        override val size: Int
            get() {
                if (armed) return -1
                return backing.size
            }
        override fun isEmpty(): Boolean = backing.isEmpty()
        override fun iterator(): MutableIterator<String> = backing.iterator()
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            armed = true
            throw IllegalStateException("addAll refused without appending")
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun negativeGarbageSizeRoutesToNoMutatorAndPreservesOriginals() {
        val backing = mutableListOf("a", "b")
        val list = NegativeGarbageSizeList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("c"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the unreadable list size, was: ${o.reason}",
            o.reason.contains("verification could not read the list size"),
        )
        assertTrue(
            "reason should report no mutator invoked, was: ${o.reason}",
            o.reason.contains("no mutator invoked"),
        )
        assertEquals(0, list.cleared)
        assertEquals(1, list.addAllCalls)
        assertEquals(0, list.subListCalls)
        assertEquals(listOf("a", "b"), backing)
    }

    // Like NegativeGarbageSizeList, but size() reports a negative garbage
    // value at ALL times (no arming/state flags) — the pre-mutation guard
    // must fail closed on the FIRST size read, before the union snapshot is
    // even computed and long before addAll is reached. addAll would return
    // true having landed nothing, so it must never be reached: a negative
    // size0 is not a readable empty list (`(0 until -1).all{}` would be
    // vacuously true) and must skip before any mutator runs.
    private class NegativeFromFirstReadList(
        initial: List<String>,
    ) : ArrayList<String>(initial) {
        var cleared = 0
        var addAllCalls = 0
        var subListCalls = 0
        override val size: Int
            get() = -1
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            return true
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            super.clear()
        }
    }

    @Test
    fun negativeSizeFromFirstReadRoutesToNoMutator() {
        val backing = mutableListOf("a", "b")
        val list = NegativeFromFirstReadList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("c"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the failed list size read, was: ${o.reason}",
            o.reason.contains("failed to read list size"),
        )
        assertEquals(0, list.addAllCalls)
        assertEquals(0, list.cleared)
        assertEquals(0, list.subListCalls)
        assertEquals(listOf("a", "b"), backing)
    }

    // Patch addAll seeds a FOREIGN entry BEFORE applying the real delta and
    // returns true: backing becomes [seed, region, foreign, alpha, beta]
    // (5 elements — both originals, the foreign entry, then ours). The
    // post-success verification fails on the size leg (5 != 2 + 2) while the
    // prefix stays byte-for-byte and the LAST add.size elements ARE our
    // delta. The decision tree must drop ONLY our appended suffix; the
    // foreign entry before it survives; the whole-list clear() must run ZERO
    // times, and exactly one subList() suffix drop runs.
    // (OUR entries are the final positions — a foreign entry at the very
    // end would fail the suffix check by design and take the restore path
    // instead, here on the post-success arm. The foreign-at-very-end case is
    // covered on the addAll-throws rollback arm by
    // foreignAtVeryEndIsRetainedWithOursDroppedInRollback.)
    private class ForeignOrderedTailList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var cleared = 0
        var subListCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            backing.add("com.foreign.entry") // foreign BEFORE ours
            return backing.addAll(elements)
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun foreignEntryBeforeOursSurvivesAppendedSuffixRollback() {
        val backing = mutableListOf("com.samsung.seed", "com.samsung.region")
        val list = ForeignOrderedTailList(backing)
        val out = WhitelistPatch.apply(
            list, true, setOf("com.example.alpha", "com.example.beta"), "F", "C",
        )
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped // cast asserts it is not PatchedInPlace/Replaced
        assertTrue(
            "reason should report the ours-only rollback, was: ${o.reason}",
            o.reason.contains("tail entries") && o.reason.contains("appended-ours"),
        )
        assertTrue(
            "reason should report the retained foreign entry, was: ${o.reason}",
            o.reason.contains("foreign entries retained"),
        )
        assertEquals(listOf("com.samsung.seed", "com.samsung.region", "com.foreign.entry"), backing)
        assertEquals(0, list.cleared)
        assertEquals(1, list.subListCalls)
    }

    // Patch addAll appends our delta and THEN a foreign entry at the VERY
    // END, then throws (addAll throws — the rollback arm, not the
    // post-success arm). NEW semantics (P1-2): the rollback ownership check
    // drops ONLY ours (front-aligned at size0 here — foreign extras sit
    // after ours, at the very end) and RETAINS the foreign entry; the
    // originals stay byte-for-byte and the restore path never runs.
    private class ForeignSuffixSkipList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var cleared = 0
        var subListCalls = 0
        var addAllCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            if (addAllCalls == 1) {
                backing.addAll(elements)          // ours first ...
                backing.add("com.foreign.entry")  // ... then foreign at the very end
                throw IllegalStateException("addAll refused after appending")
            }
            return backing.addAll(elements) // the restore re-add succeeds
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    // Patch addAll seeds a FOREIGN entry BEFORE ours and then throws
    // ([originals, rom-before-ours, ours] interleave): the rollback must
    // drop ONLY ours at the very end and RETAIN the foreign entry before it.
    private class ForeignBeforeOursThrowList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var cleared = 0
        var subListCalls = 0
        var addAllCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            if (addAllCalls == 1) {
                backing.add("com.foreign.entry") // foreign BEFORE ours
                backing.addAll(elements)          // then ours at the very end
                throw IllegalStateException("addAll refused after appending")
            }
            return backing.addAll(elements)
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun foreignBeforeOursIsRetainedWithOursDroppedInRollback() {
        val backing = mutableListOf("com.samsung.seed", "com.samsung.region")
        val list = ForeignBeforeOursThrowList(backing)
        val out = WhitelistPatch.apply(
            list, true, setOf("com.example.alpha", "com.example.beta"), "F", "C",
        )
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(o.reason.contains("addAll failed"))
        assertTrue(
            "reason should report the retained foreign entry, was: ${o.reason}",
            o.reason.contains("foreign entries retained"),
        )
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
        assertEquals(1, list.subListCalls)
        assertEquals(0, list.cleared)
        assertEquals(1, list.addAllCalls)
        assertEquals(
            listOf("com.samsung.seed", "com.samsung.region", "com.foreign.entry"),
            backing,
        )
    }

    @Test
    fun foreignAtVeryEndIsRetainedWithOursDroppedInRollback() {
        val backing = mutableListOf("com.samsung.seed", "com.samsung.region")
        val list = ForeignSuffixSkipList(backing)
        val out = WhitelistPatch.apply(
            list, true, setOf("com.example.alpha", "com.example.beta"), "F", "C",
        )
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the addAll failure, was: ${o.reason}",
            o.reason.contains("addAll failed"),
        )
        assertTrue(
            "reason should report the ours-only rollback with foreign retained, was: ${o.reason}",
            o.reason.contains("tail entries") && o.reason.contains("appended-ours"),
        )
        assertTrue(
            "reason should report the retained foreign entry, was: ${o.reason}",
            o.reason.contains("foreign entries retained"),
        )
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
        // Exactly one ours-only suffix drop ran; the restore never ran; ours
        // were removed and the foreign entry at the very end survives.
        assertEquals(1, list.subListCalls)
        assertEquals(0, list.cleared)
        assertEquals(1, list.addAllCalls)
        assertEquals(
            listOf("com.samsung.seed", "com.samsung.region", "com.foreign.entry"),
            backing,
        )
    }

    // Hostile list whose addAll throws BEFORE mutating (the live list is
    // untouched) and whose get() throws from then on, with size() still
    // readable — the unreadable branch of the unified ownership-contract
    // settle is reached via matchesPrefixLen returning -1. The restore
    // (clear()+addAll(snapshot)) would throw on this fixture if it were
    // ever attempted; clear() is counted so the test can pin ZERO mutator
    // invocations beyond the original addAll.
    private class PoisonAddAllThrowsBeforeMutatingList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var armed = false
        var cleared = 0
        var addAllCalls = 0
        var subListCalls = 0
        override val size: Int get() = backing.size
        override fun isEmpty(): Boolean = backing.isEmpty()
        override fun iterator(): MutableIterator<String> = backing.iterator()
        override fun get(index: Int): String {
            if (armed) throw IllegalStateException("get refused")
            return backing[index]
        }
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            armed = true
            throw IllegalStateException("addAll refused before mutating")
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun throwArmHostileUnreadableListInvokesNoMutatorAndPreservesOriginals() {
        val backing = mutableListOf("com.samsung.seed", "com.samsung.region")
        val list = PoisonAddAllThrowsBeforeMutatingList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("b"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        assertTrue("must not report success; was: $out", out !is WhitelistPatch.Outcome.PatchedInPlace)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the unverifiable list, was: ${o.reason}",
            o.reason.contains("verification could not read the list (get threw)"),
        )
        assertTrue(
            "reason should report no mutator invoked, was: ${o.reason}",
            o.reason.contains("no mutator invoked"),
        )
        // The restore path (clear()+addAll(snapshot)) must NOT run — the
        // verification reads are unverifiable, so no mutator may be invoked.
        assertEquals(0, list.cleared)
        assertEquals(1, list.addAllCalls)
        assertEquals(0, list.subListCalls)
        assertEquals(listOf("com.samsung.seed", "com.samsung.region"), backing)
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
    }

    // Hostile list whose addAll lands EXACTLY ONE delta element and then
    // throws (the landed-prefix case: extra == 1 < add.size). The
    // ownership check must prove the landed entry is ours (matchesRange
    // front-aligned at size0) and roll back ONLY it; the originals stay
    // byte-for-byte and the whole-list clear() never runs.
    private class PartialAppendThenThrowList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var addAllCalls = 0
        var cleared = 0
        var subListCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            if (addAllCalls == 1) {
                backing.add("com.example.alpha") // land exactly one delta element
                throw IllegalStateException("addAll refused after a partial append")
            }
            return backing.addAll(elements)
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun partialAppendThenThrowRollsBackOnlyLandedPrefix() {
        val backing = mutableListOf("com.samsung.seed", "com.samsung.region")
        val list = PartialAppendThenThrowList(backing)
        val out = WhitelistPatch.apply(
            list, true, setOf("com.example.alpha", "com.example.beta"), "F", "C",
        )
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report the partial roll-back, was: ${o.reason}",
            o.reason.contains("partially appended ours rolled back"),
        )
        // Originals intact byte-for-byte; the landed delta element dropped.
        assertEquals(listOf("com.samsung.seed", "com.samsung.region"), backing)
        assertEquals(0, list.cleared)
        assertEquals(1, list.subListCalls)
        assertTrue(!o.reason.contains("last-restore failed"))
        assertTrue(!o.reason.contains("restore incomplete"))
        assertTrue(!o.reason.contains("restore unverified"))
    }

    // Hostile list whose addAll interleaves ours and foreign entries and
    // then throws: [originals, ours1, foreign, ours2]. Ours are NOT
    // locatable as a contiguous ownable range (suffix fails, front-aligned
    // fails on the foreign at size0+1, extra == add.size so the landed
    // prefix arm does not apply), so NO mutator runs — the list is left
    // exactly as addAll left it.
    private class InterleavedOursThrowList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        private var addAllCalls = 0
        var cleared = 0
        var subListCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            addAllCalls++
            if (addAllCalls == 1) {
                backing.add("com.example.alpha") // ours
                backing.add("com.foreign.entry") // foreign, threaded between ours
                backing.add("com.example.beta")  // ours
                throw IllegalStateException("addAll refused after interleaving ours and foreign")
            }
            return backing.addAll(elements)
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun interleavedOursAndForeignInThrowArmInvokesNoMutator() {
        val backing = mutableListOf("com.samsung.seed", "com.samsung.region")
        val list = InterleavedOursThrowList(backing)
        val out = WhitelistPatch.apply(
            list, true, setOf("com.example.alpha", "com.example.beta"), "F", "C",
        )
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report ours could not be located, was: ${o.reason}",
            o.reason.contains("could not be located"),
        )
        assertTrue(
            "reason should report no mutator invoked, was: ${o.reason}",
            o.reason.contains("no mutator invoked"),
        )
        // clear() must NOT run and the list is left exactly as addAll left it.
        assertEquals(0, list.cleared)
        assertEquals(0, list.subListCalls)
        assertEquals(
            listOf(
                "com.samsung.seed",
                "com.samsung.region",
                "com.example.alpha",
                "com.foreign.entry",
                "com.example.beta",
            ),
            backing,
        )
    }

    // Normal-return arm, front-aligned mixed case: addAll appends the delta
    // and THEN a foreign entry and returns true:
    // [seed, region, extra, com.foreign.entry]. Post-success verification
    // fails on the size leg (4 != 2 + 1); the suffix check mismatches
    // (tail is the foreign entry); the ours entry is FRONT-ALIGNED at the
    // snapshot boundary, so ONLY it is dropped; the foreign entry at the
    // very end is retained.
    private class FrontAlignedMixedForeignList(
        private val backing: MutableList<String>,
    ) : AbstractMutableList<String>() {
        var cleared = 0
        var subListCalls = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): String = backing[index]
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun add(index: Int, element: String) {
            backing.add(index, element)
        }
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun addAll(elements: Collection<String>): Boolean {
            backing.addAll(elements)
            backing.add("com.foreign.entry") // foreign AFTER ours
            return true
        }
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> {
            subListCalls++
            return super.subList(fromIndex, toIndex)
        }
        override fun clear() {
            cleared++
            backing.clear()
        }
    }

    @Test
    fun frontAlignedOursWithForeignAfterDropsOnlyOursInNormalReturnArm() {
        val backing = mutableListOf("com.samsung.seed", "com.samsung.region")
        val list = FrontAlignedMixedForeignList(backing)
        val out = WhitelistPatch.apply(list, true, setOf("com.example.extra"), "F", "C")
        assertTrue("apply() must not throw; was: $out", out is WhitelistPatch.Outcome.Skipped)
        val o = out as WhitelistPatch.Outcome.Skipped
        assertTrue(
            "reason should report ours rolled back with the foreign entry retained, was: ${o.reason}",
            o.reason.contains("appended-ours") && o.reason.contains("foreign entries retained"),
        )
        // Ours was dropped; the foreign entry at the very end survives.
        assertEquals(listOf("com.samsung.seed", "com.samsung.region", "com.foreign.entry"), backing)
        assertEquals(0, list.cleared)
        assertEquals(1, list.subListCalls)
    }
}
