// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.lang.reflect.Field
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
// (exception: the narrowed promise below — a wholly-hostile list may stay
// dirty after the last-resort restore)
//
// Static-final bypass evaluation (Stage1, S24 OneUI 9): the ROM holder
// declares `static final String[]`, so plain Field.set() cannot reassign
// it (JVM IllegalAccessException, locked in WhitelistPatchReflectTest; ART
// unverified). No setAccessible force-write is attempted -- final fields
// stay ObserveOnly by design. The effective policy path is therefore NOT
// this holder patch but the method-level WhitelistPolicy.apply hooks on
// the read-query methods (DualAppDBHelper / DAUtility / DualAppManager):
// when such a hook hits, a non-empty policy flips/adds the listed
// packages; when no query hook hits (or the policy is empty), behavior
// stays observe-only even with a non-empty policy seeded.
//
// Copy-on-write promise (list case): the union is fully computed on a plain
// ArrayList copy; the live list sees at most a bounded sequence of mutating
// calls (addAll, suffix drop, last-resort clear()+addAll). If
// that call throws after a partial append (hostile AbstractMutableList), we
// roll back in two tiers and verify before returning: first drop exactly our
// appended suffix — ownership-checked: the tail must byte-match the delta
// (either the landed prefix of it, or its trailing entries at the very end),
// otherwise the drop is skipped entirely; then verify the list is
// back to the original size with every original element at its original
// index; only on verification failure (or a throwing verification) do we
// last-resort to clear() + addAll(snapshot) -- itself best-effort, and it may
// itself keep only a prefix of the snapshot. The restore is now RE-VERIFIED
// with explicit failure vocabulary: a normally-returning no-op restore is NOT
// proof of success, so after clear()+addAll(snapshot) completes we re-check
// byte-for-byte and the Skipped reason carries one of the explicit markers
// ("; restore incomplete (kept N of M original entries)" when the re-verify
// mismatches, "; restore unverified" when the re-verify read throws,
// "; last-restore failed" when the restore itself threw). If verification
// fails but the live list is byte-for-byte the snapshot (an unmodified-list
// early return), Skipped is returned with NO mutator invoked at all -- no
// clear(), no addAll. Narrowed guarantee for an
// extreme list whose clear/addAll ALSO throw during that last resort: union
// was computed on a copy and the live list got only a single addAll -- it
// may stay dirty. That class cannot be made atomic through the in-place
// MutableList API. A list whose `size` read itself throws fails closed to
// Skipped BEFORE any mutation is attempted.
// Hostile-addAll cases after the post-success verification arm (see the code):
// (a) a hostile addAll that returns normally but mangles the list -- dropping
//     original ROM entries or seeding extras -- is now caught by the
//     post-success integrity check and reported as Skipped (reason: "addAll
//     returned but the resulting list failed verification (hostile/ROM
//     bug)"). Decision tree, in order: byte-for-byte snapshot match ->
//     Skipped with NO mutator invoked (same unmodified-list marker as
//     always); verification could not read the list (size or get throws) ->
//     Skipped with an explicit "verification could not read ... no mutator
//     invoked" marker and NO mutator at all -- the list is left exactly as
//     addAll left it, because its mutation cannot be established;
//     original prefix provably damaged (matchesPrefixLen != size0) ->
//     clear()+addAll(snapshot) restore attempted and re-verified (see
//     below); prefix intact but a tail present -> only OUR appended
//     entries (the last add.size elements, dropped solely when they
//     byte-match the delta AND the drop removes exactly add.size entries —
//     a no-op hostile drop falls through to the restore path) are rolled
//     back, foreign entries are retained, and the re-verify tolerates foreign
//     extras (Skipped with an explicit "appended-ours entries rolled back,
//     originals preserved, foreign entries retained" marker); a suffix that does
//     not match is left for the restore path below; an unreadable suffix
//     (probed get) is reported as "verification could not read the list
//     suffix ... no mutator invoked" with NO mutator at all;
// (b) if addAll throws after partially appending, the appended entries are
//     dropped only after an ownership decision tree proves them ours:
//     ours sitting at the VERY END (suffix byte-match of the last add.size
//     elements), ours front-aligned at size0 (full-delta byte-match at the
//     snapshot boundary, foreign extras AFTER ours at the very end tolerated
//     and retained), or a landed prefix of the delta (front-aligned at
//     size0, when addAll threw before the whole delta landed). Our entries
//     are dropped and foreign entries wherever they sit are RETAINED; when
//     the drop leaves the original prefix byte-for-byte intact, Skipped
//     carries the "appended-ours entries rolled back, originals preserved,
//     foreign entries retained" marker. If the tail is not verifiably ours
//     — or the size read itself threw (ownership check skipped) — the
//     drop is skipped entirely; verification (matchesSnapshot) fails
//     internally and the restore path below runs. The last-resort restore
//     (clear() + addAll(snapshot)) may itself fail or keep only a prefix of
//     the snapshot; when it throws, the Skipped reason carries
//     "; last-restore failed". When the restore returns normally but fails
//     re-verification, the reason carries "; restore unverified" (list
//     unreadable) or "; restore incomplete (kept N of M original entries)"
//     (prefix-only restore) -- a normally-returning no-op restore is NOT
//     proof of success. ORIGINAL ROM whitelist entries may be lost --
//     fail-closed for the affected apps, and the reason now signals it.
//
// Threading note: PatchedInPlace mutates the live list exactly like the
// pre-H2 code did; <clinit> runs under the class-init lock, but later readers
// share the list without extra sync. The union itself is computed on an
// ArrayList copy; only the final addAll (and, on failure arms, the bounded
// rollback/restore sequence) touches the live list.
object WhitelistPatch {

    const val OBSERVE_ONLY_MARKER = "observe-only"

    // Pure shape probe for the pre-IPC fast-out in DualHook.patchWhitelistField.
    //
    // Purpose: answer "could [patchFields] patch anything at all on this
    // target?" without reading field VALUES, without mutation, without
    // logging content — so the caller can skip the policy IPC entirely on an
    // all-static-final (or absent/wrong-shaped) ROM, where the patch goes
    // ObserveOnly and the IPC would be discarded.
    //
    // Direction of approximation (H4 safety): this probe is conservative in
    // the FALSE direction — probe == false must imply patchFields cannot
    // patch any candidate. Probe == true is the LOOSE direction: it may
    // pass a field patchFields later fails on (a non-final non-MutableList
    // type holding nothing patchable, a hierarchy-only match invisible to
    // patchFields, a MutableList<Int>, a setAccessible-refusing field) —
    // that costs at most one discarded policy IPC, never a lost patch. Fail
    // direction: any Throwable during probing fails closed to false.
    //
    // Per candidate name the field is resolved by walking the class
    // hierarchy from [target] upward (a field declared on a superclass is
    // counted — a strict superset of patchFields, which resolves
    // target.getDeclaredField only; noted here and in the PR report). For
    // each resolved field: skip non-static, skip FINAL (static final cannot
    // be reassigned via plain Field.set — matches the patchFields ObserveOnly
    // arm), and count as patchable any declared type that could hold a
    // MutableList or String[] at runtime — i.e. a reference-declared array
    // whose component type is String or a supertype of String (arrays are
    // covariant, so a CharSequence[]/Object[]/Serializable[]/Comparable[]/
    // Array<out Any> field may hold a String[] at runtime — matching
    // patchFields's runtime javaClass check), a type assignable to
    // MutableList, or ANY non-final/abstract/interface type
    // (List/Object/Any/etc.). Only primitives, arrays whose component type
    // is not assignable from String (primitive component types such as Int;
    // final container-exclusion types such as Integer), and final
    // non-MutableList classes (e.g. String) are excluded as provably
    // non-container — patchFields cannot patch them either.
    //
    // NO field.get(), NO setAccessible, NO mutation, NO content inspection,
    // NO logging of content (element types of a MutableList are NOT shape;
    // patchFields fails closed on a MutableList<Int> — the safe direction).
    //
    // Equivalence note: if patchFields later grows new patchable shapes,
    // this probe must be updated to match (keep this note current); if
    // patchFields grows hierarchy resolution, the superset gap closes to
    // exact equivalence.
    fun hasPatchableFieldShape(target: Class<*>, fieldNames: List<String>): Boolean {
        try {
            for (name in fieldNames) {
                var klass: Class<*>? = target
                var field: Field? = null
                while (klass != null && field == null) {
                    field = try {
                        klass.getDeclaredField(name)
                    } catch (_: Throwable) {
                        null
                    }
                    klass = klass.superclass
                }
                if (field == null) continue
                if (!Modifier.isStatic(field.modifiers)) continue
                if (Modifier.isFinal(field.modifiers)) continue
                val type = try {
                    field.type
                } catch (_: Throwable) {
                    continue
                }
                if (type.isArray) {
                    val ct = type.componentType
                    if (ct != null && !ct.isPrimitive && ct.isAssignableFrom(String::class.java)) return true
                    continue
                }
                if (type.isPrimitive) continue
                if (MutableList::class.java.isAssignableFrom(type)) return true
                // Non-final/abstract/interface declared types may hold a
                // MutableList at runtime; only FINAL non-container classes
                // (String, Int holders, ...) are provably not patchable.
                if (!Modifier.isFinal(type.modifiers)) return true
            }
            return false
        } catch (_: Throwable) {
            return false
        }
    }

    // Byte-for-byte snapshot equality. Any Throwable (size/get read) => false.
    private fun matchesSnapshot(list: MutableList<String>, snapshot: ArrayList<String>, size0: Int): Boolean =
        try { list.size == size0 && (0 until size0).all { list[it] == snapshot[it] } } catch (_: Throwable) { false }

    // Longest matching prefix length against the snapshot. Returns -1 when a
    // read throws (list unreadable); a shrunk list yields the in-bounds
    // prefix length (0 when nothing matches).
    private fun matchesPrefixLen(list: MutableList<String>, snapshot: ArrayList<String>, size0: Int): Int =
        try {
            val n = minOf(list.size, size0)
            var i = 0
            while (i < n && list[i] == snapshot[i]) i++
            i
        } catch (_: Throwable) {
            -1
        }

    // Last [add.size] elements byte-match [add]? False on any read throw.
    // The caller must additionally require sizeNow >= size0 + add.size so the
    // matched range starts at or past the end of the snapshot prefix (the
    // suffix drop must never touch original entries).
    private fun suffixMatchesAdd(list: MutableList<String>, add: List<String>, sizeNow: Int): Boolean =
        try {
            sizeNow >= add.size && (0 until add.size).all { list[sizeNow - add.size + it] == add[it] }
        } catch (_: Throwable) {
            false
        }

    // Our byte-range match at [offset, offset + count). Any read throw => false.
    private fun bytesEqualAt(list: MutableList<String>, add: List<String>, offset: Int, count: Int = add.size): Boolean =
        try { (0 until count).all { i -> list[offset + i] == add[i] } } catch (_: Throwable) { false }

    // Front-aligned match limited to a landed prefix of ours (count < add.size). Any read throw => false.
    private fun matchesRange(list: MutableList<String>, add: List<String>, offset: Int, count: Int): Boolean =
        try { (0 until count).all { i -> list[offset + i] == add[i] } } catch (_: Throwable) { false }

    // Outcome of attempting to merge [extra] into a static field value.
    sealed interface Outcome {
        // Mutable list patched in place; [added] entries appended.
        data class PatchedInPlace(val added: Int) : Outcome

        // Immutable snapshot (e.g. String[]): caller must set() this back.
        // added == 0 means already complete: the caller must skip set()
        // (no write needed, naturally fail-closed).
        data class Replaced(val value: Array<String>, val added: Int) : Outcome

        // Leave the field as-is or best-effort restored per the narrowed promise.
        // Originals are guaranteed intact when the list is unchanged since snapshot,
        // or when restore re-verify passes; otherwise originals may be partially
        // lost and the reason carries an explicit marker (last-restore failed /
        // restore unverified / restore incomplete (kept N of M)) — see case (a)
        // in the module header (restore-failure markers are emitted in the (a)
        // restore path too). The reason string is already log-ready.
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
        val targetShort = HookLog.shortClass(target)
        if (extra.isEmpty()) {
            for (name in fieldNames) {
                results[name] = Outcome.Skipped(
                    "skip patch $name on $targetShort: empty policy set ($OBSERVE_ONLY_MARKER)",
                )
            }
            return results
        }
        for (name in fieldNames) {
            val field = try {
                target.getDeclaredField(name)
            } catch (_: Throwable) {
                results[name] = Outcome.Skipped(
                    "skip patch $name on $targetShort: field not found",
                )
                continue
            }
            val isStatic = Modifier.isStatic(field.modifiers)
            if (!isStatic) {
                results[name] = Outcome.Skipped(
                    "skip patch $name on $targetShort: field is not static",
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
                        "skip patch $name on $targetShort: cannot access non-final field " +
                            "(setAccessible failed, fail-closed)",
                    )
                    continue
                }
                // Accessible now (or already was); a later get/set failure is
                // still recorded as Skipped by the precise handlers below.
                // Phase 5: `isAccessible` is deprecated upstream; spelled as
                // setAccessible (not trySetAccessible: absent from the
                // compileSdk 34 android.jar stubs, so it does not compile on
                // this baseline). Revisit on the compileSdk 37 migration
                // (see gradle.properties plan): prefer trySetAccessible.
            }
            val current = try {
                field.get(null)
            } catch (t: Throwable) {
                if (isFinal) {
                    // Mirror the apply() isFinal arm's vocabulary: a private/
                    // protected static final field is not reassigned either —
                    // the read itself was refused. Not a patch failure; observe-only.
                    results[name] = Outcome.ObserveOnly(
                        "$OBSERVE_ONLY_MARKER $name on $targetShort: static final field, " +
                            "not reassigned (read access refused; no force-write; ART unverified)",
                    )
                    continue
                }
                results[name] = Outcome.Skipped(
                    "skip patch $name on $targetShort: read failed (${HookLog.shortEx(t)})",
                )
                continue
            }
            // Defense-in-depth: even a hostile ROM object that escapes every
            // guard inside apply() must not abort the whole field loop — a
            // throwing apply() for one candidate field must never deny its
            // sibling (DUAL_APP_WHITELIST_PACKAGES_FOR_CHINA) its attempt.
            // One bad field costs exactly its own outcome entry.
            val outcome = try {
                apply(current, true, extra, name, target.name, isFinal)
            } catch (t: Throwable) {
                Outcome.Skipped(
                    "skip patch $name on $targetShort: apply threw (${HookLog.shortEx(t)})",
                )
            }
            when (outcome) {
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
                            "skip patch $name on $targetShort: write failed (${HookLog.shortEx(t)})",
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
        // Desensitized owner: callers pass Class.name (package-qualified);
        // logs keep only the short simple name.
        val owner = HookLog.shortName(className)
        if (extra.isEmpty()) {
            return Outcome.Skipped(
                "skip patch $fieldName on $owner: empty policy set ($OBSERVE_ONLY_MARKER)",
            )
        }
        if (!isStatic) {
            return Outcome.Skipped(
                "skip patch $fieldName on $owner: field is not static",
            )
        }
        if (isFinal) {
            return Outcome.ObserveOnly(
                "$OBSERVE_ONLY_MARKER $fieldName on $owner: static final field, " +
                    "not reassigned via Field.set (no force-write; ART unverified)",
            )
        }
        if (current == null) {
            return Outcome.Skipped(
                "skip patch $fieldName on $owner: field value is null",
            )
        }
        if (current is MutableList<*>) {
            // Element-type guard BEFORE the unchecked cast: iterating a
            // MutableList<Int> as MutableList<String> would ClassCastException
            // inside the lambda (erasure), so inspect via the star-projected
            // view first. A raw list holding non-Strings must not be polluted
            // with Strings (would ClassCastException the reader). A hostile
            // ROM list whose iterator()/hasNext() throws is caught here too:
            // inspection must never escape apply() unhandled.
            val hasNonString = try {
                current.any { it !is String }
            } catch (t: Throwable) {
                return Outcome.Skipped(
                    "skip patch $fieldName on $owner: MutableList element inspection failed (${HookLog.shortEx(t)})",
                )
            }
            if (hasNonString) {
                return Outcome.Skipped(
                    "skip patch $fieldName on $owner: MutableList holds non-String elements",
                )
            }
            @Suppress("UNCHECKED_CAST")
            val list = current as MutableList<String>
            // Copy-on-write: the union is materialized first as a NEW list
            // (`add`, via `extra.filter { it !in snapshot }`); the live list
            // is not touched until the final addAll applies exactly that
            // precomputed delta.
            // A hostile list whose `size` throws must not escape apply():
            // fail closed to Skipped before any mutation is attempted.
            val size0 = try {
                list.size
            } catch (t: Throwable) {
                return Outcome.Skipped(
                    "skip patch $fieldName on $owner: failed to read list size (${HookLog.shortEx(t)})",
                )
            }
            val snapshot: ArrayList<String>
            val add: List<String>
            try {
                snapshot = ArrayList(list)
                add = extra.filter { !snapshot.contains(it) }
            } catch (t: Throwable) {
                return Outcome.Skipped(
                    "skip patch $fieldName on $owner: failed to compute union (${HookLog.shortEx(t)})",
                )
            }
            if (add.isEmpty()) return Outcome.PatchedInPlace(added = 0)
            try {
                list.addAll(add)
            } catch (t: Throwable) {
                // Two-tier rollback with pre-return verification, applied
                // uniformly (not a one-shot subList().clear()):
                // (b) drop exactly OUR appended entries — ownership-checked:
                //     the tail must byte-match the precomputed delta (ours at
                //     the very end via suffix match, ours front-aligned at
                //     size0, or a landed prefix of the delta when addAll
                //     threw early); otherwise the drop is skipped entirely so
                //     a foreign entry threaded in alongside ours is NOT
                //     deleted; if the size read itself threw, the ownership
                //     check is skipped (sizeNowRb == -1), matchesSnapshot
                //     returns false internally, and the restore path runs
                //     normally (clear()+addAll(snapshot));
                // (c) verify the list is back to the original prefix —
                //     size == size0 and every original element at its old
                //     index (foreign extras before ours, if any, also fail
                //     this and route to the fallback); any Throwable during
                //     verification counts as verify-fail;
                // (d) only on verify-fail, last-resort clear()+addAll(snapshot)
                //     — itself best-effort (may keep only a snapshot prefix).
                // Byte-for-byte originals preserved for lists whose add/addAll
                // throws before or after appending only at the tail; verified
                // before returning, otherwise best-effort fallback. Lists that
                // shrink during add, detached-view subLists, wrong-end removal,
                // or prefix damage are caught by the verification step, not
                // assumed safe.
                var rolled = false
                try {
                    val sizeNowRb = try { list.size } catch (_: Throwable) { -1 }
                    if (sizeNowRb != -1 && sizeNowRb >= size0) {
                        val extra = sizeNowRb - size0
                        var oursFrom = -1
                        var dropLen = 0
                        if (extra >= add.size) {
                            if (suffixMatchesAdd(list, add, sizeNowRb)) {
                                oursFrom = sizeNowRb - add.size; dropLen = add.size   // ours at the very end
                            } else if (bytesEqualAt(list, add, size0)) {
                                oursFrom = size0; dropLen = add.size                   // ours front-aligned, foreign at very end
                            }
                        } else if (extra >= 1) {
                            if (matchesRange(list, add, size0, extra)) { oursFrom = size0; dropLen = extra } // partial landed prefix
                        }
                        if (oursFrom != -1) {
                            // Drop ONLY our entries; foreign entries wherever they sit must survive.
                            try { list.subList(oursFrom, oursFrom + dropLen).clear() } catch (_: Throwable) { }
                            if (matchesPrefixLen(list, snapshot, size0) == size0 &&
                                (try { list.size == sizeNowRb - dropLen } catch (_: Throwable) { false })
                            ) {
                                return Outcome.Skipped(
                                    "skip patch $fieldName on $owner: list addAll failed (${HookLog.shortEx(t)}); " +
                                        "appended-ours entries rolled back, originals preserved, foreign entries retained",
                                )
                            }
                            // drop did not restore originals (hostile no-op subList or throw): fall through to restore
                        }
                        // else: skip the drop entirely (tail not verifiably ours); fall through to verify/restore
                    }
                    rolled = matchesSnapshot(list, snapshot, size0)
                } catch (_: Throwable) {
                    rolled = false
                }
                var restoreFailed = false
                var restoreNote: String? = null
                if (!rolled) {
                    try {
                        list.clear()
                        list.addAll(snapshot)
                    } catch (_: Throwable) {
                        // Swallowed: last-resort restore may fail too (see header).
                        restoreFailed = true
                    }
                    if (!restoreFailed) {
                        // Normally-returning no-op restore is NOT proof; verify it.
                        if (!matchesSnapshot(list, snapshot, size0)) {
                            restoreFailed = true
                            val kept = matchesPrefixLen(list, snapshot, size0)
                            restoreNote = if (kept < 0) {
                                "; restore unverified"
                            } else {
                                "; restore incomplete (kept $kept of $size0 original entries)"
                            }
                        }
                    }
                }
                return Outcome.Skipped(
                    "skip patch $fieldName on $owner: list addAll failed (${HookLog.shortEx(t)})" +
                        when {
                            restoreNote != null -> restoreNote
                            restoreFailed -> "; last-restore failed"
                            else -> ""
                        },
                )
            }
            // Post-success integrity arm: a hostile AbstractMutableList may
            // return normally from addAll while dropping originals or seeding
            // nothing. Verify the resulting list before reporting success; on
            // verification failure (or a throwing verification read), best-effort
            // restore and report Skipped instead of a false "+N added" signal.
            // (O(n) check; this is the <clinit> path, not the hot query path.)
            val ok = try {
                // Prefix leg via matchesPrefixLen == size0: byte-for-byte
                // prefix match without the helper's list.size == size0 check
                // (the post-success list is size0 + add.size by construction).
                list.size == size0 + add.size &&
                    matchesPrefixLen(list, snapshot, size0) == size0 &&
                    (0 until add.size).all { list[size0 + it] == add[it] }
            } catch (_: Throwable) {
                false
            }
            if (!ok) {
                // Early unmodified-list return: verification failed but the
                // live list is byte-for-byte the snapshot (e.g. a hostile
                // addAll that returns normally without touching the list).
                // No mutator is invoked -- the originals are intact and
                // there is nothing to restore.
                if (matchesSnapshot(list, snapshot, size0)) {
                    return Outcome.Skipped(
                        "skip patch $fieldName on $owner: addAll returned but the resulting list " +
                            "failed verification (hostile/ROM bug); the list is unchanged since the snapshot — " +
                            "originals preserved, no restore attempted",
                    )
                }
                // Verification READS throw (indexed get/size): the post-write
                // state is unverifiable, so NO mutator may be invoked — the
                // list is left exactly as addAll left it (its mutation cannot
                // be established; see header (a)).
                val sizeNow = try { list.size } catch (_: Throwable) { -1 }
                if (sizeNow == -1) {
                    return Outcome.Skipped(
                        "skip patch $fieldName on $owner: addAll returned but verification could not read " +
                            "the list size (hostile/ROM bug); list left untouched, no mutator invoked",
                    )
                }
                val prefixLen = matchesPrefixLen(list, snapshot, size0)
                if (prefixLen == -1) {
                    return Outcome.Skipped(
                        "skip patch $fieldName on $owner: addAll returned but verification could not read " +
                            "the list (get threw); list left untouched, no mutator invoked",
                    )
                }
                if (prefixLen == size0 && sizeNow == size0) {
                    // Unreachable in practice: the matchesSnapshot early return
                    // above (marker "the list is unchanged since the snapshot")
                    // subsumes this case; kept as an explicit arm so the
                    // decision tree reads end-to-end.
                    return Outcome.Skipped(
                        "skip patch $fieldName on $owner: addAll returned but the resulting list " +
                            "failed verification (hostile/ROM bug); the list is unchanged since the snapshot — " +
                            "originals preserved, no restore attempted",
                    )
                }
                // Prefix intact + a tail present: the tail may hold OUR entries
                // plus foreign ones. Drop ONLY our entries (the last add.size
                // elements, when they byte-match the delta) and retain any
                // foreign entries before them; the re-verify requires BOTH an
                // intact original prefix AND the drop having removed exactly
                // add.size entries (a no-op hostile drop falls through to the
                // restore path).
                if (prefixLen == size0 && sizeNow > size0 && sizeNow >= size0 + add.size) {
                    // A suffix that does not byte-match may be a genuine mismatch
                    // (fall through to the restore path) or an unreadable suffix
                    // (NO mutator may run). Distinguish them by probing the first
                    // suffix index: if the probe throws, the suffix cannot be
                    // read and the list's post-addAll state cannot be established.
                    val suffixMismatch = !suffixMatchesAdd(list, add, sizeNow)
                    val suffixUnreadable = suffixMismatch &&
                        try { list[sizeNow - add.size]; false } catch (_: Throwable) { true }
                    if (suffixMismatch && suffixUnreadable) {
                        return Outcome.Skipped(
                            "skip patch $fieldName on $owner: addAll returned but verification could not read " +
                                "the list suffix (get threw); list left untouched, no mutator invoked",
                        )
                    }
                    if (!suffixMismatch) {
                        var oursRolledBack = false
                        try {
                            list.subList(sizeNow - add.size, sizeNow).clear()
                            if (matchesPrefixLen(list, snapshot, size0) == size0) {
                                oursRolledBack = try { list.size == sizeNow - add.size } catch (_: Throwable) { false }
                            } else {
                                oursRolledBack = false
                            }
                        } catch (_: Throwable) {
                            // subList/clear/read threw: fall through to the restore path.
                            oursRolledBack = false
                        }
                        if (oursRolledBack) {
                            return Outcome.Skipped(
                                "skip patch $fieldName on $owner: addAll returned but the resulting list " +
                                    "failed verification (hostile/ROM bug); appended-ours entries rolled back, " +
                                    "originals preserved, foreign entries retained",
                            )
                        }
                        // else: fall through to the restore path.
                    }
                }
                // else (prefix damaged, or unreachable-size, or the suffix is
                // not ours): fall through to the existing restore path.
                var restoreFailed = false
                var restoreNote: String? = null
                try {
                    list.clear()
                    list.addAll(snapshot)
                } catch (_: Throwable) {
                    // Swallowed: last-resort restore may fail too (see header).
                    restoreFailed = true
                }
                if (!restoreFailed) {
                    // Normally-returning no-op restore is NOT proof; verify it.
                    if (!matchesSnapshot(list, snapshot, size0)) {
                        restoreFailed = true
                        val kept = matchesPrefixLen(list, snapshot, size0)
                        restoreNote = if (kept < 0) {
                            "; restore unverified"
                        } else {
                            "; restore incomplete (kept $kept of $size0 original entries)"
                        }
                    }
                }
                return Outcome.Skipped(
                    "skip patch $fieldName on $owner: addAll returned but the resulting " +
                        "list failed verification (hostile/ROM bug)" +
                        when {
                            restoreNote != null -> restoreNote
                            restoreFailed -> "; last-restore failed"
                            else -> ""
                        },
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
            "skip patch $fieldName on $owner: unsupported type ${HookLog.shortClass(current.javaClass)}",
        )
    }
}
