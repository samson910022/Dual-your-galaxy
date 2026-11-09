// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Method

// Pure (framework-free) whitelist hook candidate selector/planner.
//
// Evaluates a method for rewrite/hook eligibility. Selection focuses on
// Stage 1 (safest) install-state exclusion and Boolean arg-index allowlisting;
// collection/array/type safety is enforced at runtime by WhitelistPolicy.
//
// Fail-closed: unknown/ambiguous Boolean signatures and installed-composite
// queries are skipped.
object WhitelistHookEligibility {

    sealed interface Decision {
        val methodName: String
        val reason: String
    }

    data class Eligible(
        override val methodName: String,
        override val reason: String,
        // Non-null only for Boolean rewrite targets with an allowlisted package
        // argument signature (WhitelistSignatureGuard).
        val packageArgIndex: Int? = null,
        val booleanRewrite: Boolean = false,
    ) : Decision

    data class Skip(
        override val methodName: String,
        override val reason: String,
    ) : Decision

    fun evaluate(method: Method): Decision =
        evaluate(method.name, method.parameterTypes, method.returnType)

    // Stage 1 fuzzy sweep is diagnose-only (no hook installs). This helper
    // captures the safety invariant: mutation-shaped methods are never eligible
    // for fuzzy sweep hook install even if signature looks ok (fail-closed).
    fun isSafeForFuzzySweepHookInstall(method: Method): Boolean =
        isSafeForFuzzySweepHookInstall(method.name, method.parameterTypes, method.returnType)

    fun isSafeForFuzzySweepHookInstall(
        methodName: String,
        parameterTypes: Array<out Class<*>>,
        returnType: Class<*>,
    ): Boolean {
        if (WhitelistSignatureGuard.isPotentialMutationMethod(methodName)) return false
        return evaluate(methodName, parameterTypes, returnType) is Eligible
    }

    fun evaluate(
        methodName: String,
        parameterTypes: Array<out Class<*>>,
        returnType: Class<*>,
    ): Decision {
        // Stage 1: exclude installed-and-whitelist composite queries
        // (install-state/userId ambiguity) until device-verified.
        if (WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery(methodName)) {
            return Skip(
                methodName,
                "installed-whitelist composite query excluded in Stage 1 (fail-closed)",
            )
        }

        if (isBooleanLike(returnType)) {
            val packageArgIndex = WhitelistSignatureGuard.resolveBooleanPackageArgIndex(parameterTypes)
                ?: return Skip(
                    methodName,
                    "Boolean rewrite requires allowlisted package-arg signature; unknown/ambiguous (fail-closed)",
                )
            return Eligible(
                methodName = methodName,
                reason = "Boolean rewrite eligible with verified packageArgIndex=$packageArgIndex",
                packageArgIndex = packageArgIndex,
                booleanRewrite = true,
            )
        }

        // Non-Boolean results: permissive at selection. WhitelistPolicy applies
        // runtime guards (type/shape/return compatibility) and remains extend-only.
        if (returnType == Void.TYPE) {
            return Skip(methodName, "void return type is not a query result")
        }
        return Eligible(
            methodName = methodName,
            reason = "non-Boolean query eligible for hook; runtime type guards apply",
            packageArgIndex = null,
            booleanRewrite = false,
        )
    }

    private fun isBooleanLike(type: Class<*>): Boolean =
        type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java
}
