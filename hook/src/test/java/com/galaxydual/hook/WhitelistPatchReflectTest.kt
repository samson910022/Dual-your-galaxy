// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// JVM reflection integration tests for the static-final risk (review P2-code).
//
// The ROM holder declares `static final String[]`; plain Field.set() cannot
// reassign such a field. These tests lock that fact plus the fail-closed
// handling (observe-only, added=0 skips set(), both candidate fields
// attempted) using fixture classes and real java.lang.reflect calls.
// No LibXposed, no device.
class WhitelistPatchReflectTest {

    // Mirrors the ROM shape: public static final String[].
    object FinalHolder {
        @JvmField
        val FINAL_ARR: Array<String> = arrayOf("a")
    }

    // Non-final counterpart: set() is legal here.
    object MutableHolder {
        @JvmField
        var ARR: Array<String> = arrayOf("a")
    }

    // Dual-field shape: both candidate names present, as on the ROM
    // (standard + _FOR_CHINA). Non-final so the write path is exercisable.
    object DualHolder {
        @JvmField
        var DUAL_APP_WHITELIST_PACKAGES: Array<String> = arrayOf("a")

        @JvmField
        var DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA: Array<String> = arrayOf("a")
    }

    class InstanceHolder {
        @JvmField
        var ARR: Array<String> = arrayOf("a")
    }

    @Before
    fun reset() {
        MutableHolder.ARR = arrayOf("a")
        DualHolder.DUAL_APP_WHITELIST_PACKAGES = arrayOf("a")
        DualHolder.DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA = arrayOf("a")
    }

    @Test
    fun staticFinalArraySetThrowsIllegalAccessException() {
        // Behavior lock: this is WHY final fields go observe-only instead of
        // attempting Field.set(). No setAccessible is used (and since JDK 9
        // even setAccessible(true) does not permit static-final reassignment;
        // ART behavior is additionally unverified, so no force-write exists).
        val field = FinalHolder::class.java.getDeclaredField("FINAL_ARR")
        assertTrue(Modifier.isStatic(field.modifiers))
        assertTrue(Modifier.isFinal(field.modifiers))
        // Read works; write does not.
        assertEquals(listOf("a"), (field.get(null) as Array<String>).toList())
        try {
            field.set(null, arrayOf("x"))
            throw AssertionError("expected IllegalAccessException for static final set()")
        } catch (expected: IllegalAccessException) {
            // Expected: plain set() cannot reassign a static final field.
        }
        field.isAccessible = true
        try {
            field.set(null, arrayOf("x"))
            throw AssertionError("expected IllegalAccessException even after setAccessible(true)")
        } catch (expected: IllegalAccessException) {
            // Expected on JDK 9+: force-write is not available.
        }
        assertEquals(listOf("a"), FinalHolder.FINAL_ARR.toList())
    }

    @Test
    fun applyReportsObserveOnlyForFinalField() {
        val arr = arrayOf("a")
        val out = WhitelistPatch.apply(arr, true, setOf("b"), "F", "C", isFinal = true)
        assertTrue("final field should be ObserveOnly", out is WhitelistPatch.Outcome.ObserveOnly)
        val o = out as WhitelistPatch.Outcome.ObserveOnly
        assertTrue("reason should mention field, was: ${o.reason}", o.reason.contains("F"))
        assertTrue(
            "reason should carry observe-only marker, was: ${o.reason}",
            o.reason.contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
        assertEquals(listOf("a"), arr.toList())
    }

    @Test
    fun finalFieldIsObserveOnlyAndUntouched() {
        val out = WhitelistPatch.patchFields(
            FinalHolder::class.java, listOf("FINAL_ARR"), setOf("b"),
        )
        assertEquals(1, out.size)
        val single = out.getValue("FINAL_ARR")
        assertTrue("FINAL_ARR should be ObserveOnly", single is WhitelistPatch.Outcome.ObserveOnly)
        val s = single as WhitelistPatch.Outcome.ObserveOnly
        assertTrue("reason should mention field, was: ${s.reason}", s.reason.contains("FINAL_ARR"))
        assertTrue(
            "reason should carry observe-only marker, was: ${s.reason}",
            s.reason.contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
        assertEquals(listOf("a"), FinalHolder.FINAL_ARR.toList())
    }

    @Test
    fun addedZeroSkipsWrite() {
        // Policy already fully present: Replaced(added=0) must cause no
        // observable write (same instance, same contents).
        val before = MutableHolder.ARR
        val out = WhitelistPatch.patchFields(
            MutableHolder::class.java, listOf("ARR"), setOf("a"),
        )
        val single = out.getValue("ARR")
        assertTrue(single is WhitelistPatch.Outcome.Replaced)
        assertEquals(0, (single as WhitelistPatch.Outcome.Replaced).added)
        assertSame(before, MutableHolder.ARR)
        assertEquals(listOf("a"), MutableHolder.ARR.toList())
    }

    @Test
    fun nonFinalArrayIsReplacedViaSet() {
        val out = WhitelistPatch.patchFields(
            MutableHolder::class.java, listOf("ARR"), setOf("b"),
        )
        val single = out.getValue("ARR")
        assertTrue(single is WhitelistPatch.Outcome.Replaced)
        assertEquals(1, (single as WhitelistPatch.Outcome.Replaced).added)
        assertEquals(listOf("a", "b"), MutableHolder.ARR.toList())
    }

    @Test
    fun bothCandidateFieldsArePatched() {
        val out = WhitelistPatch.patchFields(
            DualHolder::class.java,
            listOf("DUAL_APP_WHITELIST_PACKAGES", "DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA"),
            setOf("b"),
        )
        assertEquals(2, out.size)
        for (name in listOf("DUAL_APP_WHITELIST_PACKAGES", "DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA")) {
            val single = out.getValue(name)
            assertTrue("$name should be Replaced", single is WhitelistPatch.Outcome.Replaced)
            assertEquals(1, (single as WhitelistPatch.Outcome.Replaced).added)
        }
        assertEquals(listOf("a", "b"), DualHolder.DUAL_APP_WHITELIST_PACKAGES.toList())
        assertEquals(listOf("a", "b"), DualHolder.DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA.toList())
    }

    @Test
    fun emptyPolicyLeavesFieldsUntouched() {
        val beforeStd = DualHolder.DUAL_APP_WHITELIST_PACKAGES
        val beforeChina = DualHolder.DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA
        val out = WhitelistPatch.patchFields(
            DualHolder::class.java,
            listOf("DUAL_APP_WHITELIST_PACKAGES", "DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA"),
            emptySet(),
        )
        assertEquals(2, out.size)
        val allSkipped = out.values.all { it is WhitelistPatch.Outcome.Skipped }
        assertTrue("empty policy should leave all fields Skipped", allSkipped)
        val reasons = out.values.map { (it as WhitelistPatch.Outcome.Skipped).reason }
        assertTrue(
            "empty-policy Skipped reasons should carry observe-only marker, was: $out",
            reasons.all { it.contains(WhitelistPatch.OBSERVE_ONLY_MARKER) },
        )
        assertSame(beforeStd, DualHolder.DUAL_APP_WHITELIST_PACKAGES)
        assertSame(beforeChina, DualHolder.DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA)
    }

    @Test
    fun missingFieldIsSkipped() {
        val out = WhitelistPatch.patchFields(
            MutableHolder::class.java, listOf("NO_SUCH_FIELD"), setOf("b"),
        )
        val single = out.getValue("NO_SUCH_FIELD")
        assertTrue(single is WhitelistPatch.Outcome.Skipped)
        assertEquals(listOf("a"), MutableHolder.ARR.toList())
    }

    @Test
    fun nonStaticFieldIsSkipped() {
        val out = WhitelistPatch.patchFields(
            InstanceHolder::class.java, listOf("ARR"), setOf("b"),
        )
        val single = out.getValue("ARR")
        assertTrue(single is WhitelistPatch.Outcome.Skipped)
        assertTrue((single as WhitelistPatch.Outcome.Skipped).reason.contains("not static"))
    }

    // P2 regression (PR #20): a non-public static NON-FINAL field (ROM
    // variant shape) must still patch via controlled setAccessible --
    // plain get(null) alone would report "read failed" on it.
    @Test
    fun nonPublicStaticNonFinalArrayIsPatchedViaControlledAccessible() {
        NonPublicStaticHolder.reset()
        // Sanity: the fixture really is the regression shape.
        val field = NonPublicStaticHolder::class.java.getDeclaredField("ARR")
        assertTrue(Modifier.isStatic(field.modifiers))
        assertFalse(Modifier.isFinal(field.modifiers))
        assertFalse(Modifier.isPublic(field.modifiers))
        val out = WhitelistPatch.patchFields(
            NonPublicStaticHolder::class.java, listOf("ARR"), setOf("b"),
        )
        val single = out.getValue("ARR")
        assertTrue(single is WhitelistPatch.Outcome.Replaced)
        assertEquals(1, (single as WhitelistPatch.Outcome.Replaced).added)
        assertEquals(listOf("a", "b"), NonPublicStaticHolder.peekArr().toList())
        NonPublicStaticHolder.reset()
    }

    // The non-public static FINAL counterpart is never force-written:
    // ObserveOnly when readable, Skipped when even the read is denied --
    // either way the value is untouched and no Replaced/PatchedInPlace.
    @Test
    fun nonPublicStaticFinalArrayIsNeverForceWritten() {
        val field = NonPublicStaticHolder::class.java.getDeclaredField("FINAL_ARR")
        assertTrue(Modifier.isStatic(field.modifiers))
        assertTrue(Modifier.isFinal(field.modifiers))
        assertFalse(Modifier.isPublic(field.modifiers))
        val out = WhitelistPatch.patchFields(
            NonPublicStaticHolder::class.java, listOf("FINAL_ARR"), setOf("b"),
        )
        val single = out.getValue("FINAL_ARR")
        assertFalse(single is WhitelistPatch.Outcome.Replaced)
        assertFalse(single is WhitelistPatch.Outcome.PatchedInPlace)
        assertEquals(listOf("a"), NonPublicStaticHolder.peekFinalArr().toList())
    }
}
