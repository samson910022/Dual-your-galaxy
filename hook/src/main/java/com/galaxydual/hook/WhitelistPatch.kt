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
// (exception: the narrowed promise below — an unreadable list is left dirty by
// design, a prefix-intact ours-drop that fails its re-verify never whole-list
// restores (the drop's damage stands, foreign entries are retained by design),
// and the prefix-damaged last-resort restore itself may fail or keep
// only a prefix of the snapshot)
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
// calls (addAll, plus — on the failure arms only — an ownership-checked
// ours-only drop or, when the ORIGINAL PREFIX IS PROVABLY DAMAGED BUT
// READABLE at the settle helper's ENTRY PROBE, a last-resort
// clear()+addAll(snapshot) restore). BOTH failure
// arms (list.addAll(add) threw, or the post-success integrity verification
// failed) settle through one ownership-contract helper
// (settleTailOrSkipMutator) and verify before returning:
// (a) UNREADABLE at the entry probe — list.size throws or reports a
//     negative garbage size (list.size < 0), or the prefix read
//     throws (matchesPrefixLen returns -1): Skipped with an explicit
//     "verification could not read ... no mutator invoked" marker and NO
//     mutator at all, in BOTH arms. The list is left exactly as addAll
//     left it, because its mutation cannot be established. (The settle
//     helper's unified sizeNow < 0 probe lands here too: a throwing size read is
//     probed first and routes straight to this arm — no drop is attempted.)
//     A boundary get() that throws during the matcher evaluation of cases
//     (i)–(iii) routes to the (iv) bucket with the not-locatable marker
//     instead — no mutator is invoked there either.
// (b) ORIGINAL PREFIX PROVABLY DAMAGED but readable (matchesPrefixLen <
//     size0) AT THE ENTRY PROBE: the ONLY remaining whole-list restore case —
//     clear()+addAll(snapshot), itself best-effort (it may itself keep only
//     a prefix of the snapshot, or throw). The restore runs ONLY when the
//     damage is detected here, at the entry probe — damage detected in
//     dropOursAndSettle AFTER an ours-drop was attempted never runs the
//     whole-list restore (it would also delete foreign entries;
//     foreign-retained by design). The restore is RE-VERIFIED with
//     explicit failure vocabulary: a normally-returning no-op restore is NOT
//     proof of success, so after clear()+addAll(snapshot) completes we
//     re-check byte-for-byte and the Skipped reason carries one of the
//     explicit markers ("; restore incomplete (kept N of M original
//     entries)" when the re-verify mismatches, "; restore unverified" when
//     the re-verify read throws, "; last-restore failed" when the restore
//     itself threw). Restore is attempted ONLY here at the entry probe — it
//     never runs after an ours-drop attempt (damage that drop causes is
//     loss mode (2) and is never restored).
// (c) PREFIX INTACT (matchesPrefixLen == size0): never a whole-list
//     clear(). If sizeNow == size0 (tail empty; throw-arm reachable — no
//     unmodified-list early return there), Skipped with the "the list is
//     unchanged" marker and no mutator. Otherwise the tail is handled by
//     ownership evidence, dropping ONLY provably-ours entries via
//     subList().clear() (ours-only, bounded to add.size elements — the
//     instructed ranges never address indices < size0; and an
//     instruction-level whole-list delete cannot occur — a hostile
//     subList view may clear more than its range (the re-verify detects
//     that loss but does not prevent it; escalation to a whole-list
//     restore is refused after any drop attempt)) and re-verifying
//     (matchesPrefixLen == size0 AND
//     list.size == sizeNow - dropLen AND no element of the dropped ours
//     slice occurs at any index >= size0 — a correct drop removes ALL
//     ours; an equal-count wrong-target hostile subList view that
//     cleared foreign entries instead is caught here, including an
//     overlapping view that cleared a mix of ours and foreign — such a
//     view passes prefixOk/sizeOk while leaving k (0 < k < dropLen) ours
//     behind) before returning:
//     (i)   ours at the VERY END (sizeNow >= size0 + add.size AND the last
//           add.size elements byte-match the delta — suffix byte-match):
//           drop subList(sizeNow - add.size, sizeNow);
//     (ii)  ours FRONT-ALIGNED at size0 (byte-match at the snapshot
//           boundary, foreign extras AFTER ours at the very end tolerated
//           and retained): drop subList(size0, size0 + add.size);
//     (i)/(ii) on success return Skipped with "appended-ours entries
//           rolled back, originals preserved, foreign entries retained".
//           On a failed drop (hostile no-op subList, throwing clear,
//           equal-count wrong-target subList view, or a
//           re-verify read that fails) there is NO escalation to a
//           whole-list restore — Skipped with an explicit "no further
//           mutator invoked (no whole-list restore)" marker, list left as
//           the drop left it;
//     (iii) a LANDED PREFIX of the delta front-aligned at size0 (addAll
//           threw before the whole delta landed; extra = sizeNow - size0
//           in 1 until add.size AND matchesRange byte-match): drop
//           subList(size0, size0 + extra); re-verify; Skipped with a
//           "partially appended ours rolled back ..." marker;
//     (iv)  ours NOT LOCATABLE (interleaved ours/foreign — e.g.
//           [ours1, foreign, ours2]; a foreign tail with ours elsewhere;
//           or an addAll that returned normally while appending non-ours
//           bytes/garbage; or a boundary get() that threw during the
//           matcher evaluation): NO mutator; Skipped with "appended-ours
//           entries could not be located (or verification could not read them);
//           original prefix intact; list
//           left as addAll left it; foreign entries retained; no mutator
//           invoked".
//     FOREIGN entries wherever they sit are RETAINED on every (i)–(iv)
//     path; only provably-ours entries are ever dropped.
// ORIGINAL ROM whitelist entries may be lost in exactly THREE loss modes:
// (1) ORIGINAL PREFIX DAMAGED, reads readable at the entry probe (case
//     (b)): the last-resort clear()+addAll(snapshot) restore runs —
//     best-effort; it may keep only a prefix of the snapshot or throw,
//     and the reason signals it via the markers above ("; restore
//     incomplete (kept N of M original entries)" / "; restore unverified" /
//     "; last-restore failed"). Restore is attempted ONLY here at the entry
//     probe — it never runs after an ours-drop attempt (damage that drop
//     causes is loss mode (2) and is never restored).
// (2) PREFIX-INTACT ours-drop fails its re-verify (adversarial drop view —
//     wrong-end removeAt, mis-targeting subList — the ours-detection catches
//     any surviving ours element, overlapping wrong-target views included;
//     a detached view with a failed/no-op drop; a throwing subList.clear()
//     lands here identically, without reaching the re-verify): NO
//     whole-list restore runs — it would also
//     delete foreign entries the prefix-intact paths must retain. The
//     original loss stands; the appended ours may remain as the drop left
//     them; the reason carries the
//     "no further mutator invoked (no whole-list restore)" marker; this damage
//     is detected in dropOursAndSettle (after a drop attempt), never at the
//     entry probe; see loss mode (1) for the complement.
// (3) LIST UNREADABLE (list.size/list[i] throws, or size reports a
//     negative garbage value): NO mutator is ever invoked by the patch on
//     this path; the list is left exactly as addAll left it — preservation of
//     originals depends on what addAll itself did, and its contents cannot be
//     established.
// On every prefix-intact path, given size0 >= 0 (enforced at the pre-probe),
// the originals are preserved byte-for-byte
// EXCEPT loss mode (2) — where a failed ours-drop's wrong-end/mis-targeting
// view already ate an original — and every Skipped reason states exactly
// what happened via an explicit marker.
// Threading note: PatchedInPlace mutates the live list exactly like the
// pre-H2 code did; <clinit> runs under the class-init lock, but later readers
// share the list without extra sync. The union itself is computed on an
// ArrayList copy; only the final addAll (and, on the failure arms, the
// bounded ours-drop / prefix-damaged restore sequence) touches the live
// list.
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
    // A negative (garbage) [size0] is indeterminate too — never a readable
    // empty snapshot: `(0 until -1).all{}` would be vacuously true, so a
    // negative size0 is refused explicitly before the try body.
    private fun matchesSnapshot(list: MutableList<String>, snapshot: ArrayList<String>, size0: Int): Boolean =
        if (size0 < 0) false else try { list.size == size0 && (0 until size0).all { list[it] == snapshot[it] } } catch (_: Throwable) { false }

    // Longest matching prefix length against the snapshot. Returns -1 when a
    // read throws (list unreadable) or when the list reports a negative
    // (garbage) size — a negative size is indeterminate, never treated as a
    // readable short list; a shrunk list yields the in-bounds prefix length
    // (0 when nothing matches).
    private fun matchesPrefixLen(list: MutableList<String>, snapshot: ArrayList<String>, size0: Int): Int {
        try {
            val s = try { list.size } catch (_: Throwable) { return -1 }
            if (s < 0) return -1
            if (size0 < 0) return -1
            val n = minOf(s, size0)
            var i = 0
            while (i < n && list[i] == snapshot[i]) i++
            return i
        } catch (_: Throwable) {
            return -1
        }
    }

    // Last [add.size] elements byte-match [add]? False on any read throw.
    // A negative (garbage) sizeNow is not readable either — refused explicitly
    // even though add.size >= 1 makes `sizeNow >= add.size` imply it.
    // The caller must additionally require sizeNow >= size0 + add.size so the
    // matched range starts at or past the end of the snapshot prefix (the
    // instructed range never addresses indices < size0; the effect of a
    // hostile subList view is bounded by the re-verify, not by the range
    // instruction).
    private fun suffixMatchesAdd(list: MutableList<String>, add: List<String>, sizeNow: Int): Boolean =
        try {
            sizeNow >= add.size && sizeNow >= 0 && (0 until add.size).all { list[sizeNow - add.size + it] == add[it] }
        } catch (_: Throwable) {
            false
        }

    // Our byte-range match at [offset, offset + count). Any read throw => false.
    private fun matchesRange(list: MutableList<String>, add: List<String>, offset: Int, count: Int = add.size): Boolean =
        try { (0 until count).all { i -> list[offset + i] == add[i] } } catch (_: Throwable) { false }

    // True if ANY element of add[0 until count] occurs at an index >= from
    // in [list]. Used after an ours-drop: correct drops remove ALL ours —
    // any occurrence means ours remain (e.g. an overlapping equal-count
    // wrong-target hostile subList view that cleared foreign entries
    // instead of ours; or partially hit ours). add is filtered against the
    // snapshot (extras not present in it), so no snapshot-filtered add
    // element can appear in the byte-intact prefix; a foreign entry
    // byte-equal to ours would flip this
    // to "drop failed" — the safe, already-preferred failure direction.
    // Read failures count as PRESENT (fail-closed).
    private fun anyOursElementAtOrAfter(
        list: MutableList<String>,
        add: List<String>,
        count: Int,
        from: Int,
    ): Boolean {
        try {
            val ours = HashSet<String>(count)
            for (i in 0 until count) ours.add(add[i])
            val n = list.size
            var i = from
            while (i < n) {
                if (list[i] in ours) return true
                i++
            }
            return false
        } catch (_: Throwable) {
            return true
        }
    }

    // Ownership-contract settle helper — the SINGLE decision tree for both
    // failure arms (the list.addAll(add)-threw arm and the post-success
    // verification-failed arm). Called ONLY after one of those two arms
    // declares failure; never on the success path. Returns the Skipped
    // outcome; NEVER whole-list clear()s on a prefix-intact path — the only
    // clear() this helper may run is the prefix-damaged last-resort restore.
    //
    // [failurePrefix] is the arm-specific reason prefix (already log-ready,
    // without a trailing marker); the helper appends the precise outcome
    // marker. Cases, in order:
    // (a) the entry probe is unreadable (size throws or reports a negative
    //     garbage size, or matchesPrefixLen returns -1): Skipped with an
    //     explicit "verification could not read ... no mutator invoked"
    //     marker — NO mutator at all. Boundary get() throws during the
    //     matcher evaluation of cases (i)–(iii) route to the (iv) bucket
    //     with the not-locatable marker instead — no mutator is invoked
    //     there either;
    // (b) prefix provably damaged but readable (prefixLen < size0): the
    //     ONLY remaining whole-list case — clear()+addAll(snapshot),
    //     re-verified, with the existing "; restore unverified" /
    //     "; restore incomplete (kept N of M original entries)" /
    //     "; last-restore failed" markers. The restore runs ONLY when the
    //     damage is detected here, at the entry probe — damage detected in
    //     dropOursAndSettle AFTER an ours-drop was attempted never
    //     whole-list restores (foreign entries would be deleted;
    //     foreign-retained by design);
    // (c) prefix intact: tail-empty -> "the list is unchanged" Skipped, no
    //     mutator; tail present -> drop ONLY provably-ours entries, by
    //     ownership evidence: (i) ours at the very end (suffix byte-match),
    //     (ii) ours front-aligned at size0 (foreign extras AFTER ours
    //     retained), (iii) a landed prefix of the delta (front-aligned at
    //     size0, addAll threw early). On success: "appended-ours
    //     entries rolled back ..." (i)/(ii) or "partially appended ours
    //     rolled back ..." (iii), original prefix re-verified. On a failed
    //     drop: Skipped with "no further mutator invoked ... list left as
    //     the drop left it" — NOT an escalation to a whole-list restore.
    //     Ours not locatable at all (interleaved ours/foreign, or a
    //     normally-returning addAll that appended non-ours bytes/garbage):
    //     NO mutator; "could not be located ... no mutator invoked".
    // Foreign entries wherever they sit are retained on every (i)-(iv) path.
    private fun settleTailOrSkipMutator(
        list: MutableList<String>,
        snapshot: ArrayList<String>,
        size0: Int,
        add: List<String>,
        failurePrefix: String,
    ): Outcome.Skipped {
        // (a) Entry probe unreadable -> NO mutator at all. The list is left
        // exactly as addAll left it: its mutation cannot be established.
        // (The settle helper's unified sizeNow < 0 probe lands here — the
        // size read is probed FIRST, before any drop is attempted. A negative
        // garbage size is not readable either: it is indeterminate, never a
        // short readable list, and never routed to the prefix-damaged restore
        // arm — NO mutator is invoked.)
        val sizeNow = try {
            list.size
        } catch (_: Throwable) {
            -1
        }
        if (sizeNow < 0) {
            return Outcome.Skipped(
                "$failurePrefix; verification could not read the list size (hostile/ROM bug); " +
                    "list left as addAll left it, no mutator invoked",
            )
        }
        val prefixLen = matchesPrefixLen(list, snapshot, size0)
        if (prefixLen == -1) {
            return Outcome.Skipped(
                "$failurePrefix; verification could not read the list (get threw); " +
                    "list left as addAll left it, no mutator invoked",
            )
        }
        // (b) Prefix provably damaged but readable: the ONLY whole-list
        // restore case — clear()+addAll(snapshot), then re-verify.
        if (prefixLen < size0) {
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
                failurePrefix + when {
                    restoreNote != null -> restoreNote
                    restoreFailed -> "; last-restore failed"
                    else -> ""
                },
            )
        }
        // (c) Prefix intact — never a whole-list clear() from here on.
        if (sizeNow == size0) {
            // Tail empty (this arm is reachable from the THROW arm — no
            // unmodified-list early return there; on the post-success arm the
            // early return subsumes it in practice).
            return Outcome.Skipped(
                "$failurePrefix; the list is unchanged since the snapshot — originals preserved, no restore attempted",
            )
        }
        val extra = sizeNow - size0
        // (i) ours at the VERY END (suffix byte-match of the last add.size
        // elements) and (ii) ours FRONT-ALIGNED at size0 (foreign extras
        // AFTER ours at the very end tolerated and retained). Only entered
        // when the whole delta fits at/after the snapshot boundary — the
        // instructed range never addresses indices < size0 (a hostile subList
        // view's effect is bounded by the re-verify, not by the instruction).
        if (sizeNow >= size0 + add.size) {
            if (suffixMatchesAdd(list, add, sizeNow)) {
                return dropOursAndSettle(list, snapshot, size0, sizeNow, sizeNow - add.size, add.size,
                    failurePrefix, partial = false, add = add)
            }
            if (matchesRange(list, add, size0)) {
                return dropOursAndSettle(list, snapshot, size0, sizeNow, size0, add.size,
                    failurePrefix, partial = false, add = add)
            }
        }
        // (iii) a LANDED PREFIX of the delta front-aligned at size0
        // (addAll threw before the whole delta landed).
        if (extra in 1 until add.size && matchesRange(list, add, size0, extra)) {
            return dropOursAndSettle(list, snapshot, size0, sizeNow, size0, extra,
                failurePrefix, partial = true, add = add)
        }
        // (iv) Ours not locatable (interleaved ours/foreign, or a
        // normally-returning addAll that appended non-ours bytes/garbage, or
        // a matcher that could not read the bytes — a boundary get() that
        // threw lands here too): NO mutator. The list is left exactly as
        // addAll left it.
        return Outcome.Skipped(
            "$failurePrefix; appended-ours entries could not be located (or verification could not read them); " +
                "original prefix intact; list left as addAll left it; foreign entries retained; no mutator invoked",
        )
    }

    // Drop [len] entries at [from] (ours), then re-verify: the original
    // prefix must be byte-for-byte intact AND the drop must have removed
    // exactly [len] elements AND no element of the dropped ours slice
    // (add[0 until len]) may occur at an index >= size0 afterwards — a
    // correct drop removes ALL ours; an equal-count wrong-target hostile
    // subList view that cleared foreign entries instead (prefixOk and
    // sizeOk pass while ours were never removed), including an overlapping
    // view that cleared a mix of ours and foreign leaving k (0 < k < len)
    // ours behind, is caught here. On success: Skipped
    // with the (i)/(ii) or (iii) rolled-back marker. On a failed drop
    // (hostile no-op subList, throwing clear, detached view, equal-count
    // wrong-target subList view, or a re-verify read that fails): NO
    // escalation to a whole-list restore — Skipped with the explicit
    // "no further mutator invoked" marker; the list is left as the drop
    // left it. Foreign entries wherever they sit survive.
    private fun dropOursAndSettle(
        list: MutableList<String>,
        snapshot: ArrayList<String>,
        size0: Int,
        sizeNow: Int,
        from: Int,
        len: Int,
        failurePrefix: String,
        partial: Boolean,
        add: List<String>,
    ): Outcome.Skipped {
        try {
            list.subList(from, from + len).clear()
        } catch (_: Throwable) {
            return dropFailedOutcome(failurePrefix)
        }
        val prefixOk = matchesPrefixLen(list, snapshot, size0) == size0
        val sizeOk = try {
            list.size == sizeNow - len
        } catch (_: Throwable) {
            false
        }
        if (prefixOk && sizeOk && !anyOursElementAtOrAfter(list, add, len, size0)) {
            return Outcome.Skipped(
                failurePrefix + if (partial) {
                    "; partially appended ours rolled back, originals preserved, foreign entries retained"
                } else {
                    "; appended-ours entries rolled back, originals preserved, foreign entries retained"
                },
            )
        }
        return dropFailedOutcome(failurePrefix)
    }

    // (i)/(ii)/(iii) drop-failure outcome: the ours-only drop (or its
    // re-verification) failed — nothing was dropped, the drop removed the
    // wrong entries, or re-verification could not read the list. A whole-list
    // restore is NOT attempted here — it would delete foreign entries the
    // prefix-intact paths must retain.
    private fun dropFailedOutcome(failurePrefix: String): Outcome.Skipped =
        Outcome.Skipped(
            "$failurePrefix; appended-ours drop or its re-verification failed; " +
                "no further mutator invoked (no whole-list restore); list left as the drop left it — " +
                "originals may be lost and appended ours may remain",
        )

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
        // or when restore re-verify passes (restore path runs only when the
        // original prefix is provably damaged but readable); otherwise the
        // reason carries an explicit marker (restore incomplete (kept N of M) /
        // restore unverified / last-restore failed / no mutator invoked markers)
        // — see cases (a), (b) and (c) in the module header. The reason string
        // is already log-ready.
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
            if (size0 < 0) return Outcome.Skipped("skip patch $fieldName on $owner: failed to read list size (hostile/ROM bug: negative size)")
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
                // Settle through the SAME ownership-contract helper as the
                // post-success verification-failed arm (see module header,
                // cases (a)-(c)). The helper probes size FIRST — a throwing
                // size read short-circuits straight to the
                // "verification could not read ... no mutator invoked" arm
                // with NO drop attempted and NO whole-list restore. A
                // readable-but-damaged prefix routes to the last-resort
                // restore; an intact prefix routes to the ours-only drop
                // ownership tree; verification reads that throw route to
                // the no-mutator Skipped. Foreign entries are retained on
                // every intact-prefix path.
                return settleTailOrSkipMutator(
                    list,
                    snapshot,
                    size0,
                    add,
                    "skip patch $fieldName on $owner: list addAll failed (${HookLog.shortEx(t)})",
                )
            }
            // Post-success integrity arm: a hostile AbstractMutableList may
            // return normally from addAll while dropping originals or seeding
            // nothing. Verify the resulting list before reporting success; on
            // verification failure (or a throwing verification read), settle
            // through the SAME ownership-contract helper as the throw-arm and
            // report Skipped instead of a false "+N added" signal.
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
                // Verification failed and the list changed: settle through
                // the SAME ownership-contract helper as the throw-arm (see
                // module header, cases (a)-(c)). Unreadable probes route to
                // the no-mutator Skipped; a readable-but-damaged prefix
                // routes to the last-resort restore; an intact prefix routes
                // to the ours-only drop ownership tree (exact suffix,
                // front-aligned, or landed partial); ours not locatable —
                // or a failed drop — routes to the no-mutator Skipped,
                // NEVER to a whole-list restore.
                return settleTailOrSkipMutator(
                    list,
                    snapshot,
                    size0,
                    add,
                    "skip patch $fieldName on $owner: addAll returned but the resulting list " +
                        "failed verification (hostile/ROM bug)",
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
