// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable

import java.util.NavigableSet
import java.util.SortedSet

// Pure (framework-free) whitelist-extension policy shared by all hooks.
//
// Kept free of android.* / LibXposed types so it runs as a plain JVM unit
// test (hook/src/test). The DualHook interceptor delegates here after
// chain.proceed(); this object never touches the chain itself.
object WhitelistPolicy {

    // Human-readable hook target for logs, e.g.
    // "com.foo.Bar#isWhitelisted(java.lang.String)".
    fun describe(executable: Executable): String {
        val params = executable.parameterTypes.joinToString(",") { it.name }
        return "${executable.declaringClass.name}#${executable.name}($params)"
    }

    // Shared whitelist policy: extend-only, never shrink.
    //
    // Booleans keep an original "true". A "false" is flipped only when the
    // package argument is identified safely: callers should pass a verified
    // [packageArgIndex] (resolved via WhitelistSignatureGuard). For backward
    // compatibility, when [packageArgIndex] is null and [strictSignature] is
    // false, a conservative legacy scan accepts only args containing exactly
    // one String. When [strictSignature] is true, an unverified index is
    // observe-only (no rewrite). Empty [extra] never rewrites.
    //
    // Collections/arrays are extended only when element types are safe and the
    // merged value is compatible with [declaredReturnType] (when provided).
    // Sorted/Navigable sets and non-String containers stay observe-only.
    // Anything unrecognized is observe-only: returned unchanged plus one
    // diagnosable log line.
    fun apply(
        result: Any?,
        args: List<Any?>,
        extra: Set<String>,
        methodDesc: String,
        onLog: (String) -> Unit,
        packageArgIndex: Int? = null,
        declaredReturnType: Class<*>? = null,
        strictSignature: Boolean = false,
    ): Any? {
        if (result is Boolean) {
            if (result) return true
            if (extra.isEmpty()) return false
            val effectiveIndex = packageArgIndex ?: if (strictSignature) {
                null
            } else {
                legacyBooleanPackageArgIndex(args)
            }
            if (effectiveIndex == null) {
                onLog(
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: boolean " +
                        "result with unverified package arg (size=${args.size}); " +
                        "returning original",
                )
                return result
            }
            val pkg = args.getOrNull(effectiveIndex)
            if (pkg !is String) {
                onLog(
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: boolean " +
                        "result with non-String package arg at index $effectiveIndex; " +
                        "returning original",
                )
                return result
            }
            return extra.contains(pkg)
        }
        if (extra.isEmpty()) return result
        if (result is List<*>) {
            if (result.any { it !is String }) {
                onLog(
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                        "list with non-String elements; returning original",
                )
                return result
            }
            val merged = result + extra.filter { !result.contains(it) }
            return compatibleOrOriginal(merged, declaredReturnType, result, methodDesc, onLog)
        }
        if (result is Set<*>) {
            if (result is SortedSet<*> || result is NavigableSet<*>) {
                onLog(
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                        "sorted/navigable set ${result.javaClass.name}; returning original",
                )
                return result
            }
            if (result.any { it !is String }) {
                onLog(
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                        "set with non-String elements; returning original",
                )
                return result
            }
            // Preserve Set semantics; rebuilding as a List would break callers
            // that cast the result back to Set.
            val merged = result.toMutableSet()
            merged.addAll(extra.filter { !merged.contains(it) })
            return compatibleOrOriginal(merged, declaredReturnType, result, methodDesc, onLog)
        }
        if (result is Collection<*>) {
            // Other Collection kinds (Queue, etc.) have unknown contracts;
            // observe-only rather than reshaping them into a List.
            onLog(
                "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                    "unsupported collection ${result.javaClass.name}; " +
                    "returning original",
            )
            return result
        }
        if (result != null && result.javaClass.isArray &&
            result.javaClass.componentType == String::class.java
        ) {
            @Suppress("UNCHECKED_CAST")
            val current = result as Array<String>
            val merged = (current.toList() + extra.filter { !current.contains(it) })
                .toTypedArray()
            return compatibleOrOriginal(merged, declaredReturnType, result, methodDesc, onLog)
        }
        onLog(
            "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                "unrecognized result ${result?.javaClass?.name ?: "null"}; " +
                "returning original",
        )
        return result
    }

    private fun legacyBooleanPackageArgIndex(args: List<Any?>): Int? {
        var found: Int? = null
        for ((i, arg) in args.withIndex()) {
            if (arg is String) {
                if (found != null) return null
                found = i
            }
        }
        return found
    }

    private fun compatibleOrOriginal(
        merged: Any,
        declaredReturnType: Class<*>?,
        original: Any,
        methodDesc: String,
        onLog: (String) -> Unit,
    ): Any {
        if (declaredReturnType != null && !declaredReturnType.isInstance(merged)) {
            onLog(
                "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                    "merged type ${merged.javaClass.name} incompatible with " +
                    "declared ${declaredReturnType.name}; returning original",
            )
            return original
        }
        return merged
    }
}
