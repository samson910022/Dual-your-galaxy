// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Hook contract test (JVM, framework-free constants in DaagentQueryHooks).
//
// Locks the Stage1 daagent read-query hook candidates to the docs/S24 contract:
// read-query names only, and every candidate class in the daagent package family.
// Constants live in DaagentQueryHooks (pure object) so tests run without loading
// DualHook/Xposed (compileOnly at unit test runtime). DualHook install/callback
// remains DEVICE-UNVERIFIED (not covered here).
class DaagentQueryHookContractTest {

    @Test
    fun queryMethodNames_areNonEmptyAndContainKeyS24ReadQueries() {
        val names = DaagentQueryHooks.DAAGENT_QUERY_METHOD_NAMES
        assertTrue("DAAGENT_QUERY_METHOD_NAMES must not be empty", names.isNotEmpty())
        // Lock the full doc-aligned candidate set (Stage 1). Update intentionally
        // when candidates change with device evidence.
        val expected = setOf(
            "getAllInstalledWhitelistedPackages",
            "getWhitelistedPackages",
            "getWhitelistPackages",
            "getWhiteListPkgs",
            "getWhiteListPkgsInSystemServer",
            "isWhitelistedPackage",
            "isWhiteListedPackage",
            "isInstalledWhitelistedPackage",
        )
        assertEquals(expected, names.toSet())
    }

    @Test
    fun queryMethodNames_doNotIndicateWriteInstallOrRemovePaths() {
        // Pragmatic mutation-verb check (read-query names only; install/remove
        // paths never hooked). Preserves read-state names like
        // isInstalledWhitelistedPackage/getAllInstalledWhitelistedPackages
        // ("installed" is state, not an install action), while rejecting
        // install/action mutations and other typical whitelist mutation verbs.
        val forbiddenSubstrings = listOf("remove", "delete", "clear", "uninstall", "revoke", "insert", "update")
        val forbiddenPrefixes = listOf("add", "set", "put", "write")
        for (name in DaagentQueryHooks.DAAGENT_QUERY_METHOD_NAMES) {
            val lower = name.lowercase()
            for (token in forbiddenSubstrings) {
                assertFalse(
                    "read-query method name must not contain mutation token '$token': $name",
                    lower.contains(token),
                )
            }
            // "install" as an action is forbidden, but "installed" (read state) is
            // allowed for get*/is* queries.
            assertFalse(
                "read-query method name must not indicate an install action: $name",
                lower.contains("install") && !lower.contains("installed"),
            )
            for (prefix in forbiddenPrefixes) {
                assertFalse(
                    "read-query method name must not start with mutation prefix '$prefix': $name",
                    lower.startsWith(prefix),
                )
            }
        }
    }

    @Test
    fun queryClassCandidates_areInDaagentPackageFamily() {
        val daagentPrefix = "com.samsung.android.da.daagent"
        val classes = DaagentQueryHooks.DAAGENT_QUERY_CLASS_CANDIDATES
        assertTrue("DAAGENT_QUERY_CLASS_CANDIDATES must not be empty", classes.isNotEmpty())
        for (className in classes) {
            assertTrue(
                "daagent query class candidate must be under $daagentPrefix: $className",
                className.startsWith(daagentPrefix),
            )
        }
    }
}
