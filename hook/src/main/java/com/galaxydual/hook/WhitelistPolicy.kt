// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Executable

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
    // Booleans keep an original "true"; a "false" is flipped only for a
    // single-String-arg query whose package is in [extra]. Collections get
    // EXTRA entries appended with the ORIGINAL container type preserved
    // (a Set stays a Set -- callers may cast). Anything unrecognized is
    // observe-only: returned unchanged plus one diagnosable log line.
    //
    // EXTRA_WHITELIST_PACKAGES is empty by default, so hooks are observe-only
    // placeholders until a real policy source (e.g. RemotePreferences) is
    // wired in.
    fun apply(
        result: Any?,
        args: List<Any?>,
        extra: Set<String>,
        methodDesc: String,
        onLog: (String) -> Unit,
    ): Any? {
        if (result is Boolean) {
            if (result) return true
            // Extend-only re-decision: if exactly 1 String arg exists anywhere in
            // args, treat it as the package candidate (supports (String),
            // (String, int), (int, String), etc.). 0 or >1 String args is
            // ambiguous — stay observe-only and never guess.
            val stringArgs = args.filterIsInstance<String>()
            if (stringArgs.size != 1) {
                onLog(
                    "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: boolean " +
                        "result with ambiguous args (size=${args.size}, " +
                        "stringCount=${stringArgs.size}); returning original",
                )
                return result
            }
            return extra.contains(stringArgs[0])
        }
        if (extra.isEmpty()) return result
        if (result is List<*>) {
            return result + extra.filter { !result.contains(it) }
        }
        if (result is Set<*>) {
            // Preserve the Set type: rebuilding as a List would break callers
            // that cast the result back to Set.
            val merged = result.toMutableSet()
            merged.addAll(extra.filter { !merged.contains(it) })
            return merged
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
            return (current.toList() + extra.filter { !current.contains(it) })
                .toTypedArray()
        }
        onLog(
            "${WhitelistPatch.OBSERVE_ONLY_MARKER} $methodDesc: " +
                "unrecognized result ${result?.javaClass?.name ?: "null"}; " +
                "returning original",
        )
        return result
    }
}
