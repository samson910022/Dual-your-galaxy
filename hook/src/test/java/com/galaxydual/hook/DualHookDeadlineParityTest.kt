// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// P2-2 (deadline-orchestration parity pin): EffectivePolicyDeadlineTest
// MIRRORS DualHook.effectivePolicy()'s 50ms end-to-end budget inline (the
// production function is private and its pieces are package-internal-only).
// A mirror can silently drift away from the production shape it pretends to
// pin. This test closes the loop by asserting the structural contract of
// the production source text itself: every ordering/clamp/skip-arm the
// mirror assumes is verified here against DualHook.kt, so a production
// refactor that breaks the contract fails loudly with a message naming
// exactly what drifted — instead of the mirrored tests silently passing
// against a rearranged implementation.
//
// NOTE: this is a text-level (regex/indexOf) structural pin, not a semantic
// one; it deliberately tolerates formatting churn inside the pinned regions.
class DualHookDeadlineParityTest {

    // :hook is the gradle test working directory; fall back to walking up
    // from the current directory until a checkout root containing
    // hook/src/main/java/com/galaxydual/hook/DualHook.kt is found. The walk
    // is only the LOCATOR mechanism — every candidate (direct or walked)
    // must pass the provenance/containment fence in checked() below: both
    // the workspace-relative suffix and containment inside user.dir's
    // subtree, or the test dies loudly instead of pinning a parent-checkout
    // DualHook.kt outside this working tree.
    private fun locateDualHookSource(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val userDirCanonical = try {
            userDir.canonicalPath
        } catch (t: Throwable) {
            throw AssertionError(
                "user.dir '${userDir.path}' has no canonical path; " +
                    "cannot pin the deadline orchestration " +
                    "(${t.javaClass.simpleName}: ${t.message})",
                t,
            )
        }
        // Provenance/containment fence: (a) the found path must end with the
        // workspace-relative hook/src/main/java/com/galaxydual/hook/DualHook.kt
        // and (b) its canonical path must live inside user.dir's subtree.
        fun checked(candidate: File): File {
            val slashed = candidate.absolutePath.replace(File.separatorChar, '/')
            if (!slashed.endsWith("hook/src/main/java/com/galaxydual/hook/DualHook.kt")) {
                throw AssertionError(
                    "DualHook.kt candidate rejected: found path '${candidate.absolutePath}' does not end with " +
                        "'hook/src/main/java/com/galaxydual/hook/DualHook.kt' (user.dir='${userDir.absolutePath}'); " +
                        "refusing to pin the deadline orchestration against a file outside the expected hook-module layout",
                )
            }
            val candidateCanonical = try {
                candidate.canonicalPath
            } catch (t: Throwable) {
                throw AssertionError(
                    "DualHook.kt candidate at ${candidate.absolutePath} has no canonical path; " +
                        "refusing to pin the deadline orchestration (${t.javaClass.simpleName}: ${t.message})",
                    t,
                )
            }
            if (!candidateCanonical.startsWith(userDirCanonical)) {
                throw AssertionError(
                    "DualHook.kt candidate rejected: canonical path '$candidateCanonical' is outside user.dir's subtree " +
                        "(canonical user.dir='$userDirCanonical'); refusing to pin the deadline orchestration against a " +
                        "parent-checkout match outside this working tree",
                )
            }
            return candidate
        }
        val direct = File("src/main/java/com/galaxydual/hook/DualHook.kt")
        if (direct.isFile) return checked(direct)
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, "hook/src/main/java/com/galaxydual/hook/DualHook.kt")
            if (candidate.isFile) return checked(candidate)
            dir = dir.parentFile
        }
        throw AssertionError(
            "DualHook.kt not found at src/main/java/com/galaxydual/hook/DualHook.kt " +
                "or at hook/src/main/java/com/galaxydual/hook/DualHook.kt walking up " +
                "from ${File(".").absolutePath}; cannot pin the deadline orchestration",
        )
    }

    @Test
    fun dualHookDeadlineOrchestrationIsStructurallyPinned() {
        val file = locateDualHookSource()
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            throw AssertionError(
                "DualHook.kt at ${file.absolutePath} is unreadable; " +
                    "cannot pin the deadline orchestration (${t.javaClass.simpleName}: ${t.message})",
                t,
            )
        }

        fun indexOfOrFail(needle: String): Int {
            val idx = text.indexOf(needle)
            assertTrue(
                "DualHook.kt drifted: required anchor \"$needle\" is missing; " +
                    "the deadline orchestration changed shape and EffectivePolicyDeadlineTest's " +
                    "mirror can no longer be trusted",
                idx >= 0,
            )
            return idx
        }

        // --- interceptWhitelistCall: the composite backstop is sequenced
        // BEFORE the 50ms policy budget is spent.
        val compositeIdx = indexOfOrFail("isInstalledWhitelistCompositeQuery")
        val extraIdx = indexOfOrFail("val extra = effectivePolicy()")
        assertTrue(
            "DualHook.kt drifted: interceptWhitelistCall must sequence " +
                "isInstalledWhitelistCompositeQuery BEFORE resolving effectivePolicy() " +
                "(composite queries must never spend the 50ms policy-refresh budget)",
            compositeIdx < extraIdx,
        )
        // Between the composite check and the first "return result" after it,
        // effectivePolicy() must NOT be evaluated (the composite arm returns
        // the original result without touching the policy path).
        val returnResultIdx = text.indexOf("return result", compositeIdx)
        assertTrue(
            "DualHook.kt drifted: no \"return result\" found after " +
                "isInstalledWhitelistCompositeQuery (composite arm lost its early return)",
            returnResultIdx >= 0,
        )
        val compositeSegment = text.substring(compositeIdx, returnResultIdx)
        assertTrue(
            "DualHook.kt drifted: effectivePolicy() is evaluated for composite " +
                "queries (it appears between isInstalledWhitelistCompositeQuery and the " +
                "composite arm's \"return result\"); composite queries must never " +
                "spend the policy-refresh budget",
            !compositeSegment.contains("effectivePolicy()"),
        )

        // --- Reset fence: deadlineExhaustedCount is documented as monotonic,
        // never reset. Any production reset of the counter breaks the parity
        // contract (EffectivePolicyDeadlineTest mirrors the increment arms,
        // and downstream tests assert counter deltas, never absolutes).
        val resetFenceSet = Regex("""deadlineExhaustedCount\s*\.(set|getAndSet|updateAndGet)\s*\(""")
        assertTrue(
            "DualHook.kt resets deadlineExhaustedCount — it is documented as monotonic, never reset",
            !resetFenceSet.containsMatchIn(text),
        )
        val resetFenceNegativeAdd = Regex("""deadlineExhaustedCount\s*\.addAndGet\s*\(\s*-""")
        assertTrue(
            "DualHook.kt resets deadlineExhaustedCount — it is documented as monotonic, never reset",
            !resetFenceNegativeAdd.containsMatchIn(text),
        )

        // --- effectivePolicy: t0 sample -> capability probe -> prefs read,
        // in order, with the per-segment budget clamp and two deadline skip arms.
        val regionStart = indexOfOrFail("internal fun effectivePolicy")
        val regionEnd = indexOfOrFail("    private fun readRemotePolicy")
        assertTrue(
            "DualHook.kt drifted: the effectivePolicy() region markers are out of order " +
                "(\"internal fun effectivePolicy\" at $regionStart, " +
                "\"    private fun readRemotePolicy\" at $regionEnd); " +
                "the deadline orchestration changed shape and the mirror can no longer be scoped",
            regionStart < regionEnd,
        )
        val effectivePolicyRegion = text.substring(regionStart, regionEnd)

        val sampleIdx = indexOfOrFail("DeadlineBudget.sampleClock()")
        val notCapableIdx = indexOfOrFail("if (!capable) return@get emptySet()")
        val prefsGateIdx = indexOfOrFail("policyRefreshGate.readBoundedOrThrow")
        assertTrue(
            "DualHook.kt drifted: DeadlineBudget.sampleClock() must be sampled " +
                "before the capable gate is consulted (t0 anchors the shared 50ms budget)",
            sampleIdx < notCapableIdx,
        )
        assertTrue(
            "DualHook.kt drifted: the capable gate must precede " +
                "policyRefreshGate.readBoundedOrThrow (a not-capable probe must never " +
                "reach readRemotePrefs)",
            notCapableIdx < prefsGateIdx,
        )
        val varRemIdx = indexOfOrFail("var rem = remainingMs()")
        val capableIdx = indexOfOrFail("val capable =")
        assertTrue(
            "DualHook.kt drifted: the remaining budget must be sampled " +
                "(var rem = remainingMs()) before the capability probe runs " +
                "(val capable = ...); otherwise the capability segment is not budget-aware",
            varRemIdx < capableIdx,
        )
        assertTrue(
            "DualHook.kt drifted: missing the per-segment budget clamp " +
                "minOf(PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS, rem) in effectivePolicy() " +
                "(the capability probe would no longer be clamped to the remaining budget)",
            text.contains("minOf(PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS, rem)"),
        )
        val exhaustedOccurrences = Regex(Regex.escape("deadlineExhaustedCount.incrementAndGet()"))
            .findAll(effectivePolicyRegion)
            .count()
        assertTrue(
            "DualHook.kt drifted: deadlineExhaustedCount.incrementAndGet() occurs " +
                "$exhaustedOccurrences time(s) inside effectivePolicy(); exactly 2 are required " +
                "(one per deadline-skip arm — capability and prefs); a different count means the " +
                "skip-arm shape drifted and EffectivePolicyDeadlineTest's mirror can no longer be trusted",
            exhaustedOccurrences == 2,
        )
    }
}
