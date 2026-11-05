// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Modifier

// Pure (framework-free) static-field patch helper for WhiteListApps.
//
// Extracted from DualHook.patchWhitelistField so plain JVM unit tests can
// cover every branch without LibXposed (reflection is JVM-legal). DualHook
// stays a thin shell: it resolves the policy, delegates to patchFields, and
// logs each per-field outcome.
//
// Contract: callers must pass an already-sanitized policy set (see
// PolicySource.effective). This object never validates package grammar
// itself; it only decides merge-vs-skip.
//
// Fail-closed rule: anything unrecognized (non-static, null, immutable,
// wrong element type) returns Skipped and the original whitelist is left
// untouched -- equivalent to the hook not existing.
//
// Copy-on-write promise (list case): the union is fully computed on a plain
// ArrayList copy; the live list sees at most ONE mutating call (addAll). If
// that call throws after a partial append (hostile AbstractMutableList), we
// best-effort roll back to the snapshot. Narrowed guarantee for an extreme
// list whose clear/addAll ALSO throws during rollback: union was computed on
// a copy and the live list got only a single addAll -- it may stay dirty.
// That class cannot be made atomic through the in-place MutableList API.
//
// Threading note: PatchedInPlace mutates the live list exactly like the
// pre-H2 code did; <clinit> runs under the class-init lock, but later readers
// share the list without extra sync. The union itself is computed on an
// ArrayList copy; only the final single addAll touches the live list.
object WhitelistPatch {

    const val OBSERVE_ONLY_MARKER = "observe-only"

    // Outcome of attempting to merge [extra] into a static field value.
    sealed interface Outcome {
        // Mutable list patched in place; [added] entries appended.
        data class PatchedInPlace(val added: Int) : Outcome

        // Immutable snapshot (e.g. String[]): caller must set() this back.
        // added == 0 means already complete: the caller must skip set()
        // (no write needed, naturally fail-closed).
        data class Replaced(val value: Array<String>, val added: Int) : Outcome

        // Leave the field untouched; [reason] is already log-ready.
        data class Skipped(val reason: String) : Outcome

        // Static final field: plain Field.set() cannot reassign it
        // (IllegalAccessException on JVM; ART unverified, so no setAccessible
        // force-write is attempted). Explicitly observe-only: never mistaken
        // for a successful patch.
        data class ObserveOnly(val reason: String) : Outcome
    }

    // Reflective multi-field patch used by DualHook.patchWhitelistField.
    //
    // Every name in [fieldNames] that exists on [target] is attempted and
    // gets its own entry in the returned map (dual-field coverage: the ROM
    // may read the _FOR_CHINA field per region/sales code, so first-wins
    // would silently miss region devices). Missing names map to Skipped.
    //
    // Rules, in order per field: not-static -> Skipped (checked before
    // get(null) for a precise diagnosis); non-final non-public -> controlled
    // setAccessible (fail-closed Skipped on refusal; see below); final ->
    // ObserveOnly (no setAccessible, no set); Replaced with added == 0 -> recorded without
    // set() (already complete, no write needed); Replaced with added > 0 ->
    // set() back (non-final only); anything else is recorded as returned.
    fun patchFields(
        target: Class<*>,
        fieldNames: List<String>,
        extra: Set<String>,
    ): Map<String, Outcome> {
        val results = LinkedHashMap<String, Outcome>()
        if (extra.isEmpty()) {
            for (name in fieldNames) {
                results[name] = Outcome.Skipped(
                    "skip patch $name on ${target.name}: empty policy set ($OBSERVE_ONLY_MARKER)",
                )
            }
            return results
        }
        for (name in fieldNames) {
            val field = try {
                target.getDeclaredField(name)
            } catch (_: Throwable) {
                results[name] = Outcome.Skipped(
                    "skip patch $name on ${target.name}: field not found",
                )
                continue
            }
            val isStatic = Modifier.isStatic(field.modifiers)
            if (!isStatic) {
                results[name] = Outcome.Skipped(
                    "skip patch $name on ${target.name}: field is not static",
                )
                continue
            }
            val isFinal = Modifier.isFinal(field.modifiers)
            if (!isFinal) {
                // Controlled accessibility for NON-FINAL fields only: a ROM
                // variant may declare the holder non-public
                // (private/protected), which plain get(null) cannot read even
                // though the field is patchable. setAccessible(true) opens
                // that path; a throw (SecurityException on hardened ROMs) is
                // an explicit fail-closed Skipped. Spelled as setAccessible
                // (not trySetAccessible: absent from the compileSdk 34
                // android.jar stubs, so it does not compile on this
                // baseline). FINAL fields never take this path: they stay
                // observe-only and Field.set is never attempted on them (no
                // force-write).
                val accessible = try {
                    field.isAccessible = true
                    true
                } catch (_: Throwable) {
                    false
                }
                if (!accessible) {
                    results[name] = Outcome.Skipped(
                        "skip patch $name on ${target.name}: cannot access non-final field " +
                            "(setAccessible failed, fail-closed)",
                    )
                    continue
                }
                // Accessible now (or already was); a later get/set failure is
                // still recorded as Skipped by the precise handlers below.
            }
            val current = try {
                field.get(null)
            } catch (t: Throwable) {
                results[name] = Outcome.Skipped(
                    "skip patch $name on ${target.name}: read failed (${t.javaClass.simpleName})",
                )
                continue
            }
            when (val outcome = apply(current, true, extra, name, target.name, isFinal)) {
                is Outcome.Replaced -> {
                    if (outcome.added == 0) {
                        // Already complete: skip set(), nothing to write.
                        results[name] = outcome
                        continue
                    }
                    try {
                        field.set(null, outcome.value)
                        results[name] = outcome
                    } catch (t: Throwable) {
                        results[name] = Outcome.Skipped(
                            "skip patch $name on ${target.name}: write failed (${t.javaClass.simpleName})",
                        )
                    }
                }
                else -> results[name] = outcome
            }
        }
        return results
    }

    fun apply(
        current: Any?,
        isStatic: Boolean,
        extra: Set<String>,
        fieldName: String,
        className: String,
        isFinal: Boolean = false,
    ): Outcome {
        if (extra.isEmpty()) {
            return Outcome.Skipped(
                "skip patch $fieldName on $className: empty policy set ($OBSERVE_ONLY_MARKER)",
            )
        }
        if (!isStatic) {
            return Outcome.Skipped(
                "skip patch $fieldName on $className: field is not static",
            )
        }
        if (isFinal) {
            return Outcome.ObserveOnly(
                "$OBSERVE_ONLY_MARKER $fieldName on $className: static final field, " +
                    "not reassigned via Field.set (no force-write; ART unverified)",
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
            // Copy-on-write: snapshot, then compute union + addAll on a plain
            // ArrayList. The live list is never used for computation, so any
            // throw before the single addAll below leaves it untouched.
            val snapshot: ArrayList<String>
            val add: List<String>
            try {
                snapshot = ArrayList(list)
                add = extra.filter { !snapshot.contains(it) }
                ArrayList(snapshot).apply { addAll(add) }
            } catch (t: Throwable) {
                return Outcome.Skipped(
                    "skip patch $fieldName on $className: failed to compute union (${t.javaClass.simpleName})",
                )
            }
            if (add.isEmpty()) return Outcome.PatchedInPlace(added = 0)
            try {
                list.addAll(add)
            } catch (t: Throwable) {
                // Immutable view (List.of / unmodifiableList): addAll throws
                // before mutating, so rollback is a no-op. A hostile custom
                // list may have partially appended: best-effort restore.
                try {
                    list.clear()
                    list.addAll(snapshot)
                } catch (_: Throwable) {
                    // Swallowed: narrowed promise applies (see header).
                }
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
