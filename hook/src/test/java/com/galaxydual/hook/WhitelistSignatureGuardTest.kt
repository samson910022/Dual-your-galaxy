package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests shape-allowlisted index resolution (not semantic packageName/userId
// verification, and not Stage 1 rewrite eligibility; see WhitelistHookEligibility).
class WhitelistSignatureGuardTest {

    @Test
    fun singleStringIsAllowed() {
        assertEquals(0, WhitelistSignatureGuard.resolveBooleanPackageArgIndex(arrayOf(String::class.java)))
    }

    @Test
    fun stringThenPrimitiveIntIsAllowed() {
        assertEquals(
            0,
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
            ),
        )
    }

    @Test
    fun primitiveIntThenStringIsAllowed() {
        assertEquals(
            1,
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(Int::class.javaPrimitiveType!!, String::class.java),
            ),
        )
    }

    @Test
    fun stringThenIntegerWrapperIsAllowed() {
        assertEquals(
            0,
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(String::class.java, Integer::class.java),
            ),
        )
    }

    @Test
    fun integerWrapperThenStringIsAllowed() {
        assertEquals(
            1,
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(Integer::class.java, String::class.java),
            ),
        )
    }

    @Test
    fun stringThenLongVariantsAreAllowed() {
        assertEquals(
            0,
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(String::class.java, Long::class.javaPrimitiveType!!),
            ),
        )
        assertEquals(
            0,
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(String::class.java, java.lang.Long::class.java),
            ),
        )
    }

    @Test
    fun twoStringsIsAmbiguous() {
        assertNull(
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(String::class.java, String::class.java),
            ),
        )
    }

    @Test
    fun threeParamsIsUnverified() {
        assertNull(
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
            ),
        )
    }

    @Test
    fun nonAllowlistedSecondParamIsUnverified() {
        assertNull(
            WhitelistSignatureGuard.resolveBooleanPackageArgIndex(
                arrayOf(String::class.java, Any::class.java),
            ),
        )
    }

    @Test
    fun noParamsIsUnverified() {
        assertNull(WhitelistSignatureGuard.resolveBooleanPackageArgIndex(emptyArray()))
    }

    @Test
    fun installedWhitelistCompositeQueryDetection() {
        assertTrue(WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery("getAllInstalledWhitelistedPackages"))
        assertTrue(WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery("isInstalledWhitelistedPackage"))
        assertTrue(WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery("IsInstalledWhiteListed"))
        assertFalse(WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery("isWhitelistedPackage"))
        assertFalse(WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery("getInstalledPackages"))
    }
}
