// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable
import java.lang.reflect.Method

// Pure (framework-free) signature guard for whitelist Boolean queries.
//
// Resolves a shape-allowlisted package-argument index for known parameter
// shapes; NOT semantic verification of packageName/userId. Unknown or
// ambiguous signatures return null so callers (WhitelistPolicy / DualHook)
// stay observe-only and never rewrite from an unverified package candidate.
// Stage 1 rewrite eligibility is restricted separately (see
// WhitelistHookEligibility).
object WhitelistSignatureGuard {

    private val ID_SLOT_TYPES: Set<Class<*>> = setOf(
        Int::class.javaPrimitiveType!!,
        Int::class.javaObjectType,
        Long::class.javaPrimitiveType!!,
        Long::class.javaObjectType,
    )

    // Shape-allowlisted Stage 1 shapes (index resolution only; not semantic
    // verification of packageName/userId, and not Stage 1 rewrite eligibility):
    // - (String) -> 0
    // - (String, idSlot) -> 0
    // - (idSlot, String) -> 1
    // where idSlot is a shape slot only: int/Integer/long/Long. Anything else
    // is unverified. Stage 1 rewrite eligibility is restricted separately to
    // single (String) only (see WhitelistHookEligibility).
    fun resolveBooleanPackageArgIndex(parameterTypes: Array<out Class<*>>): Int? {
        if (parameterTypes.size == 1 && isStringParam(parameterTypes[0])) return 0
        if (parameterTypes.size == 2) {
            val firstString = isStringParam(parameterTypes[0])
            val secondString = isStringParam(parameterTypes[1])
            if (firstString && !secondString && isIdSlot(parameterTypes[1])) return 0
            if (!firstString && secondString && isIdSlot(parameterTypes[0])) return 1
        }
        return null
    }

    fun resolveBooleanPackageArgIndex(executable: Executable): Int? =
        resolveBooleanPackageArgIndex(executable.parameterTypes)

    fun resolveBooleanPackageArgIndex(method: Method): Int? =
        resolveBooleanPackageArgIndex(method.parameterTypes)

    // Conservative signal for installed-and-whitelisted composite read queries
    // (e.g. getAllInstalledWhitelistedPackages, isInstalledWhitelistedPackage).
    // Used for Stage 2 handling; added in Phase 1 for shared use.
    fun isInstalledWhitelistCompositeQuery(methodName: String): Boolean =
        methodName.contains("installed", ignoreCase = true) &&
            methodName.contains("whitelist", ignoreCase = true)

    // Mutation/action tokens that must not be hook-installed by the Stage 1
    // fuzzy *hitelist* sweep (diagnose-only). Conservative/camelCase-segment
    // match: token at start, or after lower->upper boundary, or after '_', and
    // followed by end, upper, '_', or digit (complete segment/verb) so names
    // like getSettings are not misclassified. Fail-closed for hooking safety.
    private val MUTATION_TOKENS = listOf(
        "add", "remove", "delete", "clear", "set", "put", "insert",
        "update", "write", "reset", "revoke", "grant", "enable",
        "disable", "install", "uninstall",
    )

    fun isPotentialMutationMethod(methodName: String): Boolean {
        if (methodName.isEmpty()) return false
        for (token in MUTATION_TOKENS) {
            val pattern = Regex(
                "(^|(?<=[a-z])(?=[A-Z])|_)${Regex.escape(token)}($|(?=[A-Z_0-9]))",
                RegexOption.IGNORE_CASE,
            )
            if (pattern.containsMatchIn(methodName)) return true
        }
        return false
    }

    private fun isStringParam(type: Class<*>): Boolean = type == String::class.java

    private fun isIdSlot(type: Class<*>): Boolean = type in ID_SLOT_TYPES
}
