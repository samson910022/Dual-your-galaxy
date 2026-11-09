// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable
import java.lang.reflect.Method

// Pure (framework-free) signature guard for whitelist Boolean queries.
//
// Resolves the package-argument index only for allowlisted, conservative
// method shapes. Unknown or ambiguous signatures return null so callers
// (WhitelistPolicy / DualHook) stay observe-only and never rewrite from an
// unverified package candidate.
object WhitelistSignatureGuard {

    private val ID_SLOT_TYPES: Set<Class<*>> = setOf(
        Int::class.javaPrimitiveType!!,
        Int::class.javaObjectType,
        Long::class.javaPrimitiveType!!,
        Long::class.javaObjectType,
    )

    // Allowlisted Stage 1 shapes:
    // - (String) -> 0
    // - (String, idSlot) -> 0
    // - (idSlot, String) -> 1
    // where idSlot is int/Integer/long/Long. Anything else is unverified.
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

    private fun isStringParam(type: Class<*>): Boolean = type == String::class.java

    private fun isIdSlot(type: Class<*>): Boolean = type in ID_SLOT_TYPES
}
