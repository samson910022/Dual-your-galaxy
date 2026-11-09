package com.galaxydual.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Framework-free: locks Stage 1 fuzzy *hitelist* sweep safety. The sweep is
// diagnose-only (no hook installs) due to mutation/read-only ambiguity on ROM
// variants; these tests guard the mutation classifier and sweep-install safety
// helper so mutation-shaped methods are never treated as hook-installable.
class WhitelistSweepSafetyTest {

    @Test
    fun mutationShapedNames_areFlaggedUnsafeForHookInstall() {
        val mutationNames = listOf(
            "removeWhitelistedPackage",
            "addWhitelistPackage",
            "setWhitelistPackages",
            "clearWhitelist",
            "updateWhitelist",
            "deleteWhitelistedPackage",
        )
        for (name in mutationNames) {
            assertTrue(
                "mutation-shaped name must be flagged: $name",
                WhitelistSignatureGuard.isPotentialMutationMethod(name),
            )
        }
    }

    @Test
    fun readShapedNames_areNotFlaggedAsMutation() {
        val readNames = listOf(
            "isWhitelistedPackage",
            "getWhitelistedPackages",
            "getWhiteListPkgs",
            "hasWhitelistedPackage",
            "containsWhitelistedPackage",
        )
        for (name in readNames) {
            assertFalse(
                "read-shaped name must not be flagged as mutation: $name",
                WhitelistSignatureGuard.isPotentialMutationMethod(name),
            )
        }
    }

    @Test
    fun mutationShapedNames_areNotSafeForFuzzySweepHookInstall_evenWithAllowlistedSignature() {
        // Boolean(String) is an allowlisted/read-like signature shape, but
        // mutation verb names must still be unsafe for fuzzy sweep hook install
        // (Stage 1 fail-closed; sweep is diagnose-only).
        val allowlistedBooleanSig = arrayOf<Class<*>>(String::class.java)
        val booleanReturn = Boolean::class.javaPrimitiveType!!
        val mutationNames = listOf(
            "removeWhitelistedPackage",
            "addWhitelistPackage",
            "setWhitelistPackages",
            "clearWhitelist",
            "updateWhitelist",
            "deleteWhitelistedPackage",
        )
        for (name in mutationNames) {
            assertFalse(
                "mutation-shaped name must not be safe for fuzzy sweep hook install: $name",
                WhitelistHookEligibility.isSafeForFuzzySweepHookInstall(
                    name, allowlistedBooleanSig, booleanReturn,
                ),
            )
        }
    }

    @Test
    fun readShapedEligibleQuery_canBeSafeForFuzzySweepHookInstallBySignature() {
        // Sanity: the combined helper still allows a genuinely read-like,
        // signature-eligible query (documents the invariant, not used for
        // install now because Stage 1 sweep is diagnose-only).
        val decision = WhitelistHookEligibility.isSafeForFuzzySweepHookInstall(
            "isWhitelistedPackage",
            arrayOf(String::class.java),
            Boolean::class.javaPrimitiveType!!,
        )
        assertTrue(decision)
    }

    @Test
    fun camelCaseSegmentMatching_doesNotMisclassifyGetSettingsLikeNames() {
        // "set" inside "Settings" is not a mutation verb segment; must not flag.
        assertFalse(WhitelistSignatureGuard.isPotentialMutationMethod("getSettings"))
        assertFalse(WhitelistSignatureGuard.isPotentialMutationMethod("hasDataset"))
    }
}