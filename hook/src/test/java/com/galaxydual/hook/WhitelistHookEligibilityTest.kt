package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistHookEligibilityTest {

    // Fake query targets for JVM reflection (framework-free).
    @Suppress("unused")
    private class BooleanQueries {
        fun isWhitelistedPackage(pkg: String): Boolean = false
        fun isWhitelistedPackage(pkg: String, userId: Int): Boolean = false
        fun isWhitelistedPackage(userId: Int, pkg: String): Boolean = false
        fun ambiguous(pkg: String, other: String): Boolean = false
        fun unknownShape(pkg: String, userId: Int, flags: Long): Boolean = false
        fun noArgs(): Boolean = false
    }

    @Suppress("unused")
    private class CompositeQueries {
        fun getAllInstalledWhitelistedPackages(): List<String> = emptyList()
        fun isInstalledWhitelistedPackage(pkg: String): Boolean = false
    }

    @Suppress("unused")
    private class CollectionQueries {
        fun getWhitelistedPackages(): List<String> = emptyList()
        fun getWhitelistPackages(): java.util.ArrayList<String> = java.util.ArrayList()
        fun getArray(): Array<String> = emptyArray()
    }

    @Test
    fun booleanSingleString_isEligibleWithIndex0() {
        val method = BooleanQueries::class.java.getDeclaredMethod("isWhitelistedPackage", String::class.java)
        val decision = WhitelistHookEligibility.evaluate(method)
        assertTrue(decision is WhitelistHookEligibility.Eligible)
        val eligible = decision as WhitelistHookEligibility.Eligible
        assertTrue(eligible.booleanRewrite)
        assertEquals(0, eligible.packageArgIndex)
    }

    @Test
    fun booleanStringThenInt_isEligibleWithIndex0() {
        val method = BooleanQueries::class.java.getDeclaredMethod(
            "isWhitelistedPackage",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val decision = WhitelistHookEligibility.evaluate(method)
        val eligible = decision as WhitelistHookEligibility.Eligible
        assertTrue(eligible.booleanRewrite)
        assertEquals(0, eligible.packageArgIndex)
    }

    @Test
    fun booleanIntThenString_isEligibleWithIndex1() {
        val method = BooleanQueries::class.java.getDeclaredMethod(
            "isWhitelistedPackage",
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val decision = WhitelistHookEligibility.evaluate(method)
        val eligible = decision as WhitelistHookEligibility.Eligible
        assertTrue(eligible.booleanRewrite)
        assertEquals(1, eligible.packageArgIndex)
    }

    @Test
    fun booleanTwoStringsAmbiguous_isSkipped() {
        val method = BooleanQueries::class.java.getDeclaredMethod("ambiguous", String::class.java, String::class.java)
        val decision = WhitelistHookEligibility.evaluate(method)
        assertTrue(decision is WhitelistHookEligibility.Skip)
        assertTrue(decision.reason.contains("fail-closed", ignoreCase = true))
    }

    @Test
    fun booleanUnknownShapes_areSkipped() {
        val unknown = BooleanQueries::class.java.getDeclaredMethod(
            "unknownShape",
            String::class.java,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        assertTrue(WhitelistHookEligibility.evaluate(unknown) is WhitelistHookEligibility.Skip)

        val noArgs = BooleanQueries::class.java.getDeclaredMethod("noArgs")
        assertTrue(WhitelistHookEligibility.evaluate(noArgs) is WhitelistHookEligibility.Skip)
    }

    @Test
    fun installedCompositeQueries_areExcludedEvenWithAllowlistedSignature() {
        val getter = CompositeQueries::class.java.getDeclaredMethod("getAllInstalledWhitelistedPackages")
        val getterDecision = WhitelistHookEligibility.evaluate(getter)
        assertTrue(getterDecision is WhitelistHookEligibility.Skip)
        assertTrue(getterDecision.reason.contains("Stage 1", ignoreCase = true))

        val checker = CompositeQueries::class.java.getDeclaredMethod("isInstalledWhitelistedPackage", String::class.java)
        val checkerDecision = WhitelistHookEligibility.evaluate(checker)
        assertTrue(checkerDecision is WhitelistHookEligibility.Skip)
        assertTrue(checkerDecision.reason.contains("composite", ignoreCase = true))
        // Name-based exclusion takes precedence over Boolean signature allowlisting.
        assertFalse((checkerDecision as WhitelistHookEligibility.Skip).reason.contains("package-arg signature"))
    }

    @Test
    fun collectionReadMethods_areEligibleWithRuntimeGuards() {
        val list = CollectionQueries::class.java.getDeclaredMethod("getWhitelistedPackages")
        val listDecision = WhitelistHookEligibility.evaluate(list)
        assertTrue(listDecision is WhitelistHookEligibility.Eligible)
        val eligible = listDecision as WhitelistHookEligibility.Eligible
        assertFalse(eligible.booleanRewrite)
        assertNull(eligible.packageArgIndex)
        assertTrue(eligible.reason.contains("runtime", ignoreCase = true))

        val array = CollectionQueries::class.java.getDeclaredMethod("getArray")
        assertTrue(WhitelistHookEligibility.evaluate(array) is WhitelistHookEligibility.Eligible)
    }

    @Test
    fun evaluateByParts_matchesMethodOverload() {
        val method = BooleanQueries::class.java.getDeclaredMethod("isWhitelistedPackage", String::class.java)
        val byParts = WhitelistHookEligibility.evaluate(method.name, method.parameterTypes, method.returnType)
        assertEquals(WhitelistHookEligibility.evaluate(method), byParts)
    }


    @Test
    fun voidReturn_isSkipped() {
        val decision = WhitelistHookEligibility.evaluate(
            "getWhitelistedPackages",
            emptyArray(),
            Void.TYPE,
        )
        assertTrue(decision is WhitelistHookEligibility.Skip)
        assertTrue((decision as WhitelistHookEligibility.Skip).reason.contains("void", ignoreCase = true))
    }

    @Test
    fun booleanWrapperReturn_isEligibleForRewrite() {
        val decision = WhitelistHookEligibility.evaluate(
            "isWhitelistedPackage",
            arrayOf(String::class.java),
            java.lang.Boolean::class.java,
        )
        assertTrue(decision is WhitelistHookEligibility.Eligible)
        val eligible = decision as WhitelistHookEligibility.Eligible
        assertTrue(eligible.booleanRewrite)
        assertEquals(0, eligible.packageArgIndex)
    }
}
