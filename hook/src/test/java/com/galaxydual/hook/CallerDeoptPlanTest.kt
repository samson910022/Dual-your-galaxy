// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Locks the pure caller->callee decision (issue #10 Stage1): known caller
// list -> deoptimize callers + hook callee. Framework-free; ART effect stays
// DEVICE-UNVERIFIED.
class CallerDeoptPlanTest {
    private fun method(name: String): Method = String::class.java.getMethod(name)

    @Test
    fun emptyCallersMeansCalleeOnly() {
        val callee = method("length")
        val plan = CallerDeoptPlan.plan(callee, emptyList())
        assertSame(callee, plan.callee)
        assertTrue(plan.callersToDeoptimize.isEmpty())
    }

    @Test
    fun preservesOrderAndDedupes() {
        val callee = method("length")
        val a = method("isEmpty")
        val b = method("hashCode")
        val plan = CallerDeoptPlan.plan(callee, listOf(a, b, a, b, a))
        assertEquals(listOf(a, b), plan.callersToDeoptimize)
    }

    @Test
    fun excludesCalleeItself() {
        val callee = method("length")
        val caller = method("isEmpty")
        val plan = CallerDeoptPlan.plan(callee, listOf(callee, caller, callee))
        assertEquals(listOf(caller), plan.callersToDeoptimize)
    }

    @Test
    fun doesNotMutateInput() {
        val callee = method("length")
        val input = mutableListOf(method("isEmpty"), callee, method("isEmpty"))
        val snapshot = input.toList()
        CallerDeoptPlan.plan(callee, input)
        assertEquals(snapshot, input)
    }
}
