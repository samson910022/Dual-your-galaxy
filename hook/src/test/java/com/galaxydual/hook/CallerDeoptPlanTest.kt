// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Locks the pure caller->callee decision (issue #10 Stage1): known caller
// list (methods or constructors, as Executables) -> deoptimize callers + hook
// callee. Framework-free; ART effect stays DEVICE-UNVERIFIED.
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

    @Test
    fun excludesCalleeWhenEqualButDifferentInstance() {
        val callee = String::class.java.getMethod("length")
        val equalToCallee = String::class.java.getMethod("length")
        assertNotSame("getMethod returned same instance; test precondition not met", callee, equalToCallee)
        val caller = method("isEmpty")
        val plan = CallerDeoptPlan.plan(callee, listOf(equalToCallee, caller))
        assertEquals(listOf(caller), plan.callersToDeoptimize)
    }

    @Test
    fun dedupesEqualButDifferentInstancesKeepingFirstSeenOrder() {
        val callee = method("length")
        val first = String::class.java.getMethod("isEmpty")
        val equalToFirst = String::class.java.getMethod("isEmpty")
        assertNotSame("getMethod returned same instance; test precondition not met", first, equalToFirst)
        val second = String::class.java.getMethod("hashCode")
        val plan = CallerDeoptPlan.plan(callee, listOf(first, second, equalToFirst))
        assertEquals(listOf(first, second), plan.callersToDeoptimize)
        assertSame(first, plan.callersToDeoptimize[0])
    }

    @Test
    fun keepsSameNameMethodFromDifferentDeclaringClass() {
        val callee = String::class.java.getMethod("length")
        val otherClassSameName = StringBuilder::class.java.getMethod("length")
        val plan = CallerDeoptPlan.plan(callee, listOf(otherClassSameName))
        assertEquals(listOf(otherClassSameName), plan.callersToDeoptimize)
    }

    @Test
    fun keepsDifferentOverloadsWithSameName() {
        val callee = method("length")
        val booleanOverload = String::class.java.getMethod("valueOf", java.lang.Boolean.TYPE)
        val intOverload = String::class.java.getMethod("valueOf", Integer.TYPE)
        val plan = CallerDeoptPlan.plan(callee, listOf(booleanOverload, intOverload))
        assertEquals(listOf(booleanOverload, intOverload), plan.callersToDeoptimize)
    }

    @Test
    fun allEntriesEqualToCalleeReturnsEmpty() {
        val callee = method("length")
        val plan = CallerDeoptPlan.plan(callee, listOf(
            String::class.java.getMethod("length"),
            String::class.java.getMethod("length"),
        ))
        assertTrue(plan.callersToDeoptimize.isEmpty())
    }

    @Test
    fun ignoresNullElementsForJavaInteropDefensiveHandling() {
        val callee = method("length")
        val caller = method("isEmpty")
        val plan = CallerDeoptPlan.plan(callee, listOf<Method?>(null, caller, null))
        assertEquals(listOf(caller), plan.callersToDeoptimize)
    }

    @Test
    fun constructorCallerIsSupported() {
        val callee = method("length")
        val ctor = String::class.java.getConstructor(ByteArray::class.java)
        val plan = CallerDeoptPlan.plan(callee, listOf<Executable?>(ctor))
        assertEquals(listOf<Executable>(ctor), plan.callersToDeoptimize)
    }

    @Test
    fun constructorCallerDedupedAndOrdered() {
        val callee = method("length")
        val ctorA = String::class.java.getConstructor(ByteArray::class.java)
        val ctorB = String::class.java.getConstructor(CharArray::class.java)
        val plan = CallerDeoptPlan.plan(callee, listOf<Executable?>(ctorA, ctorB, ctorA, ctorB))
        assertEquals(listOf<Executable>(ctorA, ctorB), plan.callersToDeoptimize)
    }

    @Test
    fun constructorNotExcludedByMethodCallee() {
        val callee = method("length")
        val ctor = String::class.java.getConstructor()
        val caller = method("isEmpty")
        val plan = CallerDeoptPlan.plan(callee, listOf<Executable?>(callee, ctor, caller))
        assertEquals(listOf<Executable>(ctor, caller), plan.callersToDeoptimize)
    }

    @Test
    fun mixedMethodAndConstructorPreserveOrderAndDedupe() {
        val callee = method("length")
        val m = method("isEmpty")
        val ctor = String::class.java.getConstructor(ByteArray::class.java)
        val plan = CallerDeoptPlan.plan(callee, listOf<Executable?>(m, ctor, m, null, ctor))
        assertEquals(listOf<Executable>(m, ctor), plan.callersToDeoptimize)
        assertSame(m, plan.callersToDeoptimize[0])
        assertSame(ctor, plan.callersToDeoptimize[1])
    }
}
