// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

import java.util.NavigableSet
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Pure (framework-free) whitelist-extension policy shared by all hooks.
//
// Kept free of android.* / LibXposed types so it runs as a plain JVM unit
// test (hook/src/test). The DualHook interceptor delegates here after
// chain.proceed(); this object never touches the chain itself.
object WhitelistPolicy {

    // Bounded describe cache (Phase 4 hot-path): describe() runs on every
    // whitelist query and reflects over parameterTypes each time. Cache up to
    // 128 entries keyed by Executable (Method/Constructor equality covers the
    // hook targets); best-effort LRU-ish eviction (reinsert on hit, drop an
    // arbitrary eldest on overflow). Holds at most 128 Class refs in this
    // process (system_server/daagent classloaders are permanent, no leak).
    // Best-effort diagnostic only: a miss recomputes, a lost touch or an
    // evicted entry only affects log-string cost, never policy correctness.
    private const val DESCRIBE_CACHE_MAX = 128
    private val describeCache = ConcurrentHashMap<Executable, String>()

    // H13: caps unexpected-args observe-only spam on the hot path. The old
    // single process-global 8-log allowance could be drained by one noisy
    // method, silencing every other method for the process lifetime. Now each
    // distinct methodDesc gets its own small budget
    // (MAX_UNEXPECTED_ARGS_PER_METHOD) and a process-global cap
    // (MAX_UNEXPECTED_ARGS_GLOBAL_LOGS) backstops the aggregate worst case.
    // Per-method accounting uses a bounded map (UNEXPECTED_ARGS_KEYS_MAX);
    // once full, unseen keys fall back to the global cap only.
    private const val MAX_UNEXPECTED_ARGS_PER_METHOD = 8
    internal const val MAX_UNEXPECTED_ARGS_GLOBAL_LOGS = 64
    private const val UNEXPECTED_ARGS_KEYS_MAX = 128
    private val unexpectedArgsLogs = AtomicInteger(0) // attempts (logged + suppressed)
    private val unexpectedArgsGlobalLogs = AtomicInteger(0) // emitted, capped by MAX_UNEXPECTED_ARGS_GLOBAL_LOGS
    private val perMethodLogs = ConcurrentHashMap<String, AtomicInteger>()

    // Bounded mismatch logger (P2-4/H13). Returns true when the message was
    // logged (false when suppressed). Test seam: remainingUnexpectedArgsBudget()
    // exposes the GLOBAL budget for JVM tests without touching Android.
    // methodKey is the caller's methodDesc;
    // when null, callers share the "" bucket. The Boolean return is the
    // rate-limit contract (callers must not assume a log fired).
    internal fun logBounded(onLog: (String) -> Unit, msg: String, methodKey: String? = null): Boolean {
        unexpectedArgsLogs.incrementAndGet()
        val key = methodKey ?: ""
        // Bounded map growth: at the key cap, unseen keys bypass per-method
        // accounting and are governed by the global cap alone.
        val per = if (perMethodLogs.size < UNEXPECTED_ARGS_KEYS_MAX || perMethodLogs.containsKey(key)) {
            perMethodLogs.computeIfAbsent(key) { AtomicInteger(0) }
        } else {
            null
        }
        if (per != null && per.incrementAndGet() > MAX_UNEXPECTED_ARGS_PER_METHOD) {
            return false
        }
        if (unexpectedArgsGlobalLogs.incrementAndGet() > MAX_UNEXPECTED_ARGS_GLOBAL_LOGS) {
            return false
        }
        try {
            onLog(msg)
        } catch (_: Throwable) {
        }
        return true
    }

    internal fun unexpectedArgsLogCount(): Int = unexpectedArgsLogs.get()

    internal fun remainingUnexpectedArgsBudget(): Int =
        (MAX_UNEXPECTED_ARGS_GLOBAL_LOGS - unexpectedArgsGlobalLogs.get()).coerceAtLeast(0)

    // Test-only reset (JVM tests run in one process; without reset the log
    // budget would leak across tests).
    internal fun resetUnexpectedArgsLogsForTest() {
        unexpectedArgsLogs.set(0)
        unexpectedArgsGlobalLogs.set(0)
        perMethodLogs.clear()
    }

    // Human-readable hook target for logs, e.g. "Bar#isWhitelisted(String)".
    // Short simple names only (no package qualification) + take(200): avoids
    // leaking ROM package structure into logs and bounds hostile length.
    fun describe(executable: Executable): String {
        describeCache[executable]?.let { hit ->
            // Touch for LRU-ish recency (remove + reinsert is atomic enough
            // for a diagnostic cache; a lost touch only affects eviction).
            describeCache.remove(executable, hit)
            describeCache[executable] = hit
            return hit
        }
        val params = try {
            executable.parameterTypes.joinToString(",") { HookLog.shortClass(it) }
        } catch (_: Throwable) {
            ""
        }
        val owner = try {
            HookLog.shortClass(executable.declaringClass)
        } catch (_: Throwable) {
            "Class"
        }
        val computed = "$owner#${executable.name}($params)".take(200)
        if (describeCache.size >= DESCRIBE_CACHE_MAX) {
            describeCache.keys.firstOrNull()?.let { describeCache.remove(it) }
        }
        describeCache[executable] = computed
        return computed
    }

    // Shared whitelist policy: extend-only, never shrink.
    //
    // Booleans keep an original "true". A "false" is flipped only when the
    // package argument is identified via a shape-allowlisted/explicit
    // [packageArgIndex] from a guarded allowlist (WhitelistSignatureGuard shape
    // resolution; Stage 1 rewrite eligibility may be stricter). Guarded callers
    // (DualHook) resolve [packageArgIndex] via WhitelistSignatureGuard and use
    // [strictSignature]=true so unknown/ambiguous/unverified overloads remain
    // observe-only (no rewrite). The presence of a single String argument is NOT
    // used as a safety basis for unverified/device-unverified overloads.
    //
    // Backward compatibility: when [packageArgIndex] is null and [strictSignature]
    // is false, a conservative/limited legacy scan returns an index only if args
    // contain exactly one String value; this is a heuristic for legacy callers and
    // must not be treated as proof that the String is the packageName. When
    // [strictSignature] is true, an unverified index is observe-only. Empty
    // [extra] never rewrites.
    //
    // A4 (fail-closed): runtime Boolean results are rewritten only when
    // [declaredReturnType] is absent/null or boolean-like (`boolean`/`java.lang.Boolean`).
    // If a declared non-Boolean return is provided (e.g. `Object`/`Any`), a boxed
    // Boolean runtime result stays observe-only and the original is returned.
    //
    // Collections/arrays are extended only when element types are safe and the
    // merged value is compatible with [declaredReturnType] (when provided).
    // Sorted/Navigable sets and non-String containers stay observe-only.
    // A2 (fail-closed): for `List`/`Set`, non-empty containers with any non-String
    // element are observe-only. Empty containers (or cases where merge would add
    // elements) require a verified String element generic via [genericReturnType]
    // when merge would add elements; runtime-all-String non-empty containers remain
    // mergeable for compatibility. Unverified/raw/generic-mismatch => observe-only
    // when merge would add elements.
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
        genericReturnType: Type? = null,
    ): Any? {
        // Hostile extra view guard: isEmpty() runs on caller-supplied set
        // machinery and may throw. Fail closed to the original result
        // (observe-only). This matches the per-branch semantics below:
        // Boolean-false + empty returns false (== result here), and
        // List/Set/Array + empty return result unchanged; result == true
        // never reaches extra at all (returned above-equivalently as result).
        val extraEmpty: Boolean = try {
            extra.isEmpty()
        } catch (_: Throwable) {
            return result
        }
        if (result is Boolean) {
            if (declaredReturnType != null && !isBooleanLikeDeclaredReturn(declaredReturnType)) {
                logBounded(onLog,
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: boolean " +
                        "result with non-Boolean declared return ${HookLog.shortClass(declaredReturnType)}; " +
                        "returning original",
                methodDesc,
                )
                return result
            }
            if (result) return true
            if (extraEmpty) return false
            val effectiveIndex = packageArgIndex ?: if (strictSignature) {
                null
            } else {
                legacyBooleanPackageArgIndex(args)
            }
            if (effectiveIndex == null) {
                logBounded(onLog, 
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: boolean " +
                        "result with unverified package arg (size=${args.size}); " +
                        "returning original",
                methodDesc,
                )
                return result
            }
            val pkg = args.getOrNull(effectiveIndex)
            if (pkg !is String) {
                logBounded(onLog, 
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: boolean " +
                        "result with non-String package arg at index $effectiveIndex; " +
                        "returning original",
                methodDesc,
                )
                return result
            }
            return try {
                extra.contains(pkg)
            } catch (_: Throwable) {
                // Hostile extra set (contains throws): fail closed to false
                // (original Boolean result, which is false here).
                false
            }
        }
        if (extraEmpty) return result
        if (result is List<*>) {
            try {
                if (result.any { it !is String }) {
                    logBounded(onLog, 
                        "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                            "list with non-String elements; returning original",
                    methodDesc,
                    )
                    return result
                }
                val wouldAddElements = extra.any { !result.contains(it) }
                if (wouldAddElements && !isVerifiedStringCollectionGeneric(genericReturnType, result)) {
                    logBounded(onLog, 
                        "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                            "unverified collection element type/generic for list; returning original",
                    methodDesc,
                    )
                    return result
                }
                val merged = result + extra.filter { !result.contains(it) }
                return compatibleOrOriginal(merged, declaredReturnType, result, methodDesc, onLog)
            } catch (_: Throwable) {
                // Hostile list/extra view (any/filter/contains/+ throws):
                // fail closed to the original (observe-only).
                return result
            }
        }
        if (result is Set<*>) {
            if (result is SortedSet<*> || result is NavigableSet<*>) {
                logBounded(onLog,
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                        "sorted/navigable set ${HookLog.shortClass(result.javaClass)}; returning original",
                methodDesc,
                )
                return result
            }
            try {
                if (result.any { it !is String }) {
                    logBounded(onLog, 
                        "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                            "set with non-String elements; returning original",
                    methodDesc,
                    )
                    return result
                }
                val wouldAddElements = extra.any { !result.contains(it) }
                if (wouldAddElements && !isVerifiedStringCollectionGeneric(genericReturnType, result)) {
                    logBounded(onLog, 
                        "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                            "unverified collection element type/generic for set; returning original",
                    methodDesc,
                    )
                    return result
                }
                // Preserve Set semantics; rebuilding as a List would break callers
                // that cast the result back to Set. Hostile view safe: a throwing
                // set view fails closed to the original (observe-only).
                val merged = try {
                    result.toMutableSet()
                } catch (_: Throwable) {
                    return result
                }
                try {
                    merged.addAll(extra.filter { !merged.contains(it) })
                } catch (_: Throwable) {
                    return try {
                        result.toMutableSet()
                    } catch (_: Throwable) {
                        result
                    }
                }
                return compatibleOrOriginal(merged, declaredReturnType, result, methodDesc, onLog)
            } catch (_: Throwable) {
                // Hostile result/extra view (any/contains/filter/toMutableSet
                // throws): fail closed to the original (observe-only).
                return result
            }
        }
        if (result is Collection<*>) {
            // Other Collection kinds (Queue, etc.) have unknown contracts;
            // observe-only rather than reshaping them into a List.
            logBounded(onLog,
                "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                    "unsupported collection ${HookLog.shortClass(result.javaClass)}; " +
                    "returning original",
            methodDesc,
            )
            return result
        }
        if (result != null && result.javaClass.isArray &&
            result.javaClass.componentType == String::class.java
        ) {
            @Suppress("UNCHECKED_CAST")
            val current = result as Array<String>
            return try {
                val merged = (current.toList() + extra.filter { !current.contains(it) })
                    .toTypedArray()
                compatibleOrOriginal(merged, declaredReturnType, result, methodDesc, onLog)
            } catch (_: Throwable) {
                // Hostile extra view (filter/contains throws): fail closed to
                // the original (observe-only).
                result
            }
        }
        logBounded(onLog,
            "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                "unrecognized result ${result?.let { HookLog.shortClass(it.javaClass) } ?: "null"}; " +
                "returning original",
        methodDesc,
        )
        return result
    }

    // A4: declared returns that are boolean-like keep Boolean rewrite behavior.
    // A boxed Boolean runtime result with an ambiguous/non-Boolean declared type
    // (e.g. Object/Any) must stay observe-only (fail-closed).
    private fun isBooleanLikeDeclaredReturn(declaredReturnType: Class<*>): Boolean =
        declaredReturnType == java.lang.Boolean.TYPE ||
            declaredReturnType == java.lang.Boolean::class.java

    // A2: when merge would add String extras to a List/Set, require evidence that
    // the container's element type is String. When [genericReturnType] is a
    // parseable ParameterizedType (e.g. List<String> vs List<Int>) it is ALWAYS
    // checked first — even for non-empty all-String runtime containers — so a
    // declared List<Int>/Set<Int> can never be poisoned by String extras.
    // Only raw/unknown/null generics keep the legacy non-empty all-String
    // compat branch (runtime-verified); empty containers with unverified
    // generics stay fail-closed when adding.
    private fun isVerifiedStringCollectionGeneric(
        genericReturnType: Type?,
        runtimeResult: Collection<*>,
    ): Boolean {
        if (genericReturnType is ParameterizedType) {
            val args = genericReturnType.actualTypeArguments
            if (args.isEmpty()) return false
            val elementArg = args[0]
            return elementArg is Class<*> && elementArg == String::class.java
        }
        if (runtimeResult.isNotEmpty()) return true
        if (genericReturnType == null) return false
        if (genericReturnType is Class<*>) {
            // Raw List/Set without <String> (e.g. List.class): the element type
            // is unknown from the signature, so a merge that would add entries
            // stays observe-only (fail-closed). Only a parseable
            // ParameterizedType with a String element (checked above) or a
            // non-empty all-String runtime container (compat, checked above)
            // may merge.
            return false
        }
        // TypeVariable, WildcardType, GenericArrayType, etc. - not verified.
        return false
    }

    // Conservative/limited legacy heuristic (non-strict callers only): returns an
    // index only when exactly one String argument value is present. Not proof that
    // the String is packageName for device-unverified overloads; strict/guarded
    // callers must use WhitelistSignatureGuard-resolved packageArgIndex instead.
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
            logBounded(onLog,
                "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                    "merged type ${HookLog.shortClass(merged.javaClass)} incompatible with " +
                    "declared ${HookLog.shortClass(declaredReturnType)}; returning original",
            methodDesc,
            )
            return original
        }
        return merged
    }
}
