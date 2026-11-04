// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) static-field patch helper for WhiteListApps.
//
// Extracted from DualHook.patchWhitelistField so plain JVM unit tests can
// cover every branch without reflection or LibXposed. DualHook stays a thin
// reflective shell: it checks static-ness, loads the field, delegates here,
// and only writes back on Replaced.
//
// Contract: callers must pass an already-sanitized policy set (see
// PolicySource.effective). This object never validates package grammar
// itself; it only decides merge-vs-skip.
//
// Fail-closed rule: anything unrecognized (non-static, null, immutable,
// wrong element type) returns Skipped and the original whitelist is left
// untouched -- equivalent to the hook not existing.
//
// Threading note: PatchedInPlace mutates the live list exactly like the
// pre-H2 code did; <clinit> runs under the class-init lock, but later readers
// share the list without extra sync. If a future change ever ships a
// non-empty policy, prefer replace-with-copy for the list case.
object WhitelistPatch {

    // Outcome of attempting to merge [extra] into a static field value.
    sealed interface Outcome {
        // Mutable list patched in place; [added] entries appended.
        data class PatchedInPlace(val added: Int) : Outcome

        // Immutable snapshot (e.g. String[]): caller must set() this back.
        data class Replaced(val value: Array<String>, val added: Int) : Outcome

        // Leave the field untouched; [reason] is already log-ready.
        data class Skipped(val reason: String) : Outcome
    }

    fun apply(
        current: Any?,
        isStatic: Boolean,
        extra: Set<String>,
        fieldName: String,
        className: String,
    ): Outcome {
        if (extra.isEmpty()) {
            return Outcome.Skipped(
                "skip patch $fieldName on $className: empty policy set (observe-only)",
            )
        }
        if (!isStatic) {
            return Outcome.Skipped(
                "skip patch $fieldName on $className: field is not static",
            )
        }
        if (current == null) {
            return Outcome.Skipped(
                "skip patch $fieldName on $className: field value is null",
            )
        }
        if (current is MutableList<*>) {
            // Element-type guard BEFORE the unchecked cast: iterating a
            // MutableList<Int> as MutableList<String> would ClassCastException
            // inside the lambda (erasure), so inspect via the star-projected
            // view first. A raw list holding non-Strings must not be polluted
            // with Strings (would ClassCastException the reader).
            if (current.any { it !is String }) {
                return Outcome.Skipped(
                    "skip patch $fieldName on $className: MutableList holds non-String elements",
                )
            }
            @Suppress("UNCHECKED_CAST")
            val list = current as MutableList<String>
            val add = extra.filter { !list.contains(it) }
            if (add.isEmpty()) return Outcome.PatchedInPlace(added = 0)
            try {
                list.addAll(add)
            } catch (t: Throwable) {
                // Immutable view (List.of / unmodifiableList): fail closed.
                return Outcome.Skipped(
                    "skip patch $fieldName on $className: list is not modifiable (${t.javaClass.simpleName})",
                )
            }
            return Outcome.PatchedInPlace(added = add.size)
        }
        if (current.javaClass.isArray && current.javaClass.componentType == String::class.java) {
            @Suppress("UNCHECKED_CAST")
            val arr = current as Array<String>
            val add = extra.filter { !arr.contains(it) }
            if (add.isEmpty()) return Outcome.Replaced(arr, added = 0)
            return Outcome.Replaced((arr.toList() + add).toTypedArray(), added = add.size)
        }
        return Outcome.Skipped(
            "skip patch $fieldName on $className: unsupported type ${current.javaClass.name}",
        )
    }
}
