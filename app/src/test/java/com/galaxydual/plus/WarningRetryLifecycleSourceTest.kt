// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #77 + P2 Handler-leak source-shape lock (JVM, no Robolectric), mirroring
// RiskDisclosureSourceShapeTest: read the production .kt text and verify that
// (a) the retry generation is armed only at the accepted-warning seams (never
// at start*Warning, which stale auto-restarts also pass through), and
// (b) warning restarts are posted through an Activity-owned Handler with a
// hostAlive guard, and that Handler's callbacks are removed on destroy.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WarningRetryLifecycleSourceTest {

    private data class Seam(val name: String, val src: String, val a: String, val b: String)

    private fun prodFile(name: String): File {
        val candidates = listOf(
            File("src/main/java/com/galaxydual/plus/$name"),
            File("app/src/main/java/com/galaxydual/plus/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing $name (tried ${candidates.map { it.path }})")
    }

    // Top-level members of the Activity are indented exactly four spaces;
    // continuation lines and locals are deeper. Bound expression-bodied
    // declarations (`fun f(...) = expr`) at the next such line: they have no
    // function-body brace, so naive brace matching would grab the NEXT
    // function's body instead.
    private fun nextTopLevelDecl(src: String, from: Int): Int {
        val starters = listOf("private ", "override ", "internal ", "companion ", "init ", "data ", "sealed ", "class ", "object ", "fun ", "val ", "var ", "@")
        var nl = src.indexOf('\n', from)
        while (nl >= 0) {
            val line = nl + 1
            if (line >= src.length) break
            if (src.startsWith("    ", line) && (line + 4 >= src.length || src[line + 4] != ' ')) {
                val rest = src.substring(line + 4)
                if (starters.any { rest.startsWith(it) }) return line
            }
            nl = src.indexOf('\n', line)
        }
        return src.length
    }

    private fun block(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        // Skip default-value lambdas in the parameter list (e.g. `= {}`) and
        // find the `)` that closes the parameter list.
        var paren = 0
        var paramsEnd = -1
        for (i in start until src.length) {
            when (src[i]) {
                '(' -> paren++
                ')' -> {
                    paren--
                    if (paren == 0) {
                        paramsEnd = i
                        break
                    }
                }
            }
        }
        assertTrue("$signature must close its parameter list", paramsEnd >= 0)
        val brace = src.indexOf('{', paramsEnd + 1)
        val nextDecl = nextTopLevelDecl(src, paramsEnd + 1)
        if (brace < 0 || nextDecl < brace) {
            // Expression body: slice from the signature to the next top-level
            // declaration rather than scanning into a later function's block.
            return src.substring(start, nextDecl)
        }
        var depth = 0
        for (i in brace until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("$signature block never closes")
    }

    private val main by lazy { prodFile("MainActivity.kt").readText() }
    private val editor by lazy { prodFile("PolicyActivity.kt").readText() }

    @Test
    fun generationArmedOnlyAtAccept_notAtWarningEntry() {
        for ((name, src, entry, request) in listOf(
            Seam("MainActivity/write", main, "private fun startWriteWarning(", "private fun requestPreWriteWarning("),
            Seam("MainActivity/clear", main, "private fun startClearWarning(", "private fun requestPreClearWarning("),
            Seam("PolicyActivity/editor", editor, "private fun startEditorWriteWarning(", "private fun requestEditorWriteWarning("),
        )) {
            val entryBody = block(src, entry)
            assertFalse("$name start*Warning must not arm the retry token", entryBody.contains("onWarningInstalled()"))

            val requestBody = block(src, request)
            // G2: Main funnels warning accepts through installAcceptedWarning;
            // PolicyActivity still calls tryAcceptWarning inline. Either way the
            // generation advances only at the accept seam, never at entry/fast path.
            val acceptSrc = if (name.startsWith("MainActivity")) {
                block(src, "private fun installAcceptedWarning(")
            } else {
                requestBody
            }
            assertTrue("$name request*Warning must arm only via the accept seam", acceptSrc.contains("tryAcceptWarning("))
            assertTrue(
                "$name accept seam must be gated by the re-entry guard",
                acceptSrc.contains("consentResetAllowedForNewWarning(pendingClearConfirm)"),
            )
            if (name.startsWith("MainActivity")) {
                assertTrue(requestBody.contains("installAcceptedWarning("))
                assertTrue(requestBody.contains("installAcceptedAction("))
                assertFalse("fast paths must never arm the retry token", requestBody.contains("tryAcceptWarning("))
                // Ordering matters, not just membership: the accept decision
                // must precede installing the identity so a rejected accept
                // can never leave an installed restart request behind.
                val tryAcceptAt = acceptSrc.indexOf("tryAcceptWarning(")
                val installAt = acceptSrc.indexOf("warningRestartController.install(")
                assertTrue(
                    "$name tryAcceptWarning must precede install at the accept seam",
                    tryAcceptAt in 0 until installAt,
                )
            }
        }
    }

    @Test
    fun warningRestart_postsThroughOwnedHandlerWithHostGuard() {
        for ((name, src, handler) in listOf(
            Seam("MainActivity", main, "mainHandler.post", ""),
            Seam("PolicyActivity", editor, "handler.post", ""),
        )) {
            // G2: the scheduler takes the accepted request id; trailing "(" keeps
            // this source-shape lock valid for the new signature.
            val restart = block(src, "private fun scheduleWarningRestart(")
            assertFalse(
                "$name must not create a new Handler per restart (leaks posted callbacks)",
                restart.contains("Handler(Looper.getMainLooper())"),
            )
            assertTrue("$name must post on its Activity-owned Handler", restart.contains(handler))
            assertTrue(
                "$name posted restart must check isFinishing/isDestroyed",
                restart.contains("isFinishing") && restart.contains("isDestroyed"),
            )
        }
        val mainDestroy = block(main, "override fun onDestroy()")
        assertTrue(
            "MainActivity.onDestroy must remove owned Handler callbacks",
            mainDestroy.contains("mainHandler.removeCallbacksAndMessages(null)"),
        )
        val editorDestroy = block(editor, "override fun onDestroy()")
        assertTrue(
            "PolicyActivity.onDestroy must remove owned Handler callbacks",
            editorDestroy.contains("handler.removeCallbacksAndMessages(null)"),
        )
    }

    private fun indicesOf(src: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = src.indexOf(needle, from)
            if (i < 0) break
            out.add(i)
            from = i + needle.length
        }
        return out
    }

    @Test
    fun restartRequestInstalledOnlyAtAcceptSeam_noMutablePendingSlot() {
        val acceptGuard =
            "if (!tryAcceptWarning(warningRetryOneShot, consentResetAllowedForNewWarning(pendingClearConfirm))) return"
        for ((name, src, entry, request) in listOf(
            Seam("MainActivity/write", main, "private fun startWriteWarning(", "private fun requestPreWriteWarning("),
            Seam("MainActivity/clear", main, "private fun startClearWarning(", "private fun requestPreClearWarning("),
            Seam("PolicyActivity/editor", editor, "private fun startEditorWriteWarning(", "private fun requestEditorWriteWarning("),
        )) {
            val entryBody = block(src, entry)
            assertFalse(
                "$name start*Warning must not install a restart request (that is the accept seam)",
                entryBody.contains("warningRestartController.install("),
            )
            assertFalse(
                "$name start*Warning must not overwrite restart state at entry",
                entryBody.contains("pendingWarningRestart") || entryBody.contains("autoRestartCount"),
            )

            val requestBody = block(src, request)
            assertFalse(
                "$name must not invoke an unbound pending restart callback",
                requestBody.contains("pendingWarningRestart?.invoke()"),
            )
            if (name.startsWith("MainActivity")) {
                // G2: NO direct install inside a request body. All four accept
                // branches (Unknown + probe + two no-warning fast paths) go
                // through one of the two shared seams.
                assertFalse("$name request body must not install directly", requestBody.contains("warningRestartController.install("))
                assertEquals(
                    "$name must accept both warning branches through the warning seam",
                    2,
                    indicesOf(requestBody, "installAcceptedWarning(").size,
                )
                assertEquals(
                    "$name must install identity at both no-warning fast paths",
                    2,
                    indicesOf(requestBody, "installAcceptedAction(").size,
                )
                assertFalse("$name fast paths must not arm the retry token", requestBody.contains("tryAcceptWarning("))
                val warningSeam = block(src, "private fun installAcceptedWarning(")
                // installAcceptedAction is expression-bodied; block() bounds it
                // at the next top-level declaration so no later guarded body
                // can leak into this slice.
                val actionSeam = block(src, "private fun installAcceptedAction(")
                assertTrue(warningSeam.contains(acceptGuard))
                assertTrue(warningSeam.contains("warningRestartController.install("))
                assertTrue(warningSeam.contains("generation = warningRetryOneShot.currentGeneration"))
                // Membership alone is not enough: a rejected accept must not
                // install anything, so the guard has to run BEFORE install.
                val guardAt = warningSeam.indexOf(acceptGuard)
                val installAt = warningSeam.indexOf("warningRestartController.install(")
                assertTrue("warning install must follow the accept guard", guardAt in 0 until installAt)
                assertTrue(actionSeam.contains("warningRestartController.install("))
                assertTrue(actionSeam.contains("generation = warningRetryOneShot.currentGeneration"))
                // Exactly one scoped assertion: the Known/no-op fast path has
                // NO accept guard and must not advance the retry generation.
                assertFalse(
                    "fast-path seam must NOT advance the retry generation or contain the accept guard",
                    actionSeam.contains("tryAcceptWarning(") || actionSeam.contains(acceptGuard),
                )
            } else {
                val installs = indicesOf(requestBody, "warningRestartController.install(")
                assertEquals("$name must install at both accept seams (Unknown + probe)", 2, installs.size)
                for (idx in installs) {
                    val before = requestBody.substring(maxOf(0, idx - 420), idx)
                    assertTrue(
                        "$name install must follow the accepted-warning guard",
                        before.contains(acceptGuard),
                    )
                }
            }
        }

        assertEquals(
            "Main write binds the candidate key at all four accept branches",
            4,
            indicesOf(block(main, "private fun requestPreWriteWarning("), "candidateKey = candidate.sorted().joinToString()").size,
        )
        assertEquals(
            "Main clear installs a CLEAR identity at all four accept branches",
            4,
            indicesOf(block(main, "private fun requestPreClearWarning("), "WarningRestartAction.CLEAR").size,
        )
        assertEquals(
            "Editor installs a sanitized-candidate identity",
            2,
            indicesOf(block(editor, "private fun requestEditorWriteWarning("), "candidateKey = sanitizedCandidate.sorted().joinToString()").size,
        )

        for ((name, src) in listOf("MainActivity" to main, "PolicyActivity" to editor)) {
            assertFalse(
                "$name's mutable pendingWarningRestart callback field must be gone",
                src.contains("private var pendingWarningRestart:"),
            )
            assertTrue(
                "$name must own a WarningRestartController",
                src.contains("private val warningRestartController = WarningRestartController()"),
            )
            assertTrue("$name Retry sites must be identity-bound", src.contains("warningRestartController.consumeRetry("))
            if (name == "MainActivity") {
                // G2: Main captures the accept-time id instead of reading the
                // controller's retry slot at settle time, and binds every
                // abort/cleanup through it.
                assertFalse(src.contains("warningRestartController.retryRequestId()"))
                assertTrue(src.contains("warningRestartController.isLive("))
                assertTrue(src.contains("warningRestartController.revoke("))
                assertTrue(src.contains("expectedRequestId = actionId"))
                assertTrue(src.contains("private fun cancelAcceptedWarning("))
                assertTrue(src.contains("private var pendingWarningRestartActionId: Long? = null"))
            } else {
                assertTrue(src.contains("warningRestartController.retryRequestId()"))
            }
            assertTrue(
                "$name onDestroy must clear the controller and its posted Runnable",
                src.contains("warningRestartController.clear()") && src.contains("pendingWarningRestartRunnable?.let {"),
            )
        }
        val mainDestroy = block(main, "override fun onDestroy()")
        assertTrue("onDestroy must drop session-scoped consent and Runnable owner", mainDestroy.contains("pendingWarningConsent = null") && mainDestroy.contains("pendingWarningRestartActionId = null"))
    }

    @Test
    fun provenanceThreaded_fromUserTapOnlyForGenuineEntryTaps() {
        for ((name, src, entry, request) in listOf(
            Seam("MainActivity/write", main, "private fun startWriteWarning(", "private fun requestPreWriteWarning("),
            Seam("MainActivity/clear", main, "private fun startClearWarning(", "private fun requestPreClearWarning("),
            Seam("PolicyActivity/editor", editor, "private fun startEditorWriteWarning(", "private fun requestEditorWriteWarning("),
        )) {
            assertTrue(
                "$name entry must forward provenance to the accept seam",
                block(src, entry).contains("fromUserTap = fromUserTap"),
            )
            if (name.startsWith("MainActivity")) {
                // G2: provenance is applied in the shared seams, then forwarded
                // by every accept branch (2 warnings + 2 no-warning fast paths).
                val warningSeam = block(src, "private fun installAcceptedWarning(")
                val actionSeam = block(src, "private fun installAcceptedAction(")
                assertTrue(warningSeam.contains("resetBudget = fromUserTap"))
                assertTrue(actionSeam.contains("resetBudget = fromUserTap"))
                assertEquals(
                    "$name all four accept branches must forward provenance",
                    4,
                    indicesOf(block(src, request), "fromUserTap = fromUserTap").size,
                )
            } else {
                assertEquals(
                    "$name both accept seams must pass resetBudget = fromUserTap",
                    2,
                    indicesOf(block(src, request), "resetBudget = fromUserTap").size,
                )
            }
        }
        // The INSTALLED callback serves BOTH auto-restart delivery and Retry
        // settles; neither may ever be mistaken for a fresh user entry tap.
        // G2: one lambda per request function is reused by all its accept sites.
        assertEquals(1, indicesOf(main, "startWriteWarning(fromUserTap = false)").size)
        assertEquals(1, indicesOf(main, "startClearWarning(fromUserTap = false)").size)
        assertEquals(2, indicesOf(editor, "startEditorWriteWarning(HashSet(ticks), fromUserTap = false)").size)
        // Genuine click handlers are the only true provenance sources.
        assertEquals(2, indicesOf(main, "startWriteWarning(fromUserTap = true)").size)
        assertEquals(1, indicesOf(main, "startClearWarning(fromUserTap = true)").size)
        assertEquals(1, indicesOf(editor, "startEditorWriteWarning(candidate, fromUserTap = true)").size)
    }

    @Test
    fun mainDialogCancelsAndAborts_areIdentityBound() {
        // G2: every Main dialog CANCEL must route through cancelAcceptedWarning,
        // which identity-gates the cancel before touching pendingClearConfirm.
        // A raw setPendingClearConfirm(false) in an onCancel body would let a
        // stale (superseded) warning clear the freeze of the live one.
        val clearRequest = block(main, "private fun requestPreClearWarning(")
        val writeRequest = block(main, "private fun requestPreWriteWarning(")
        for ((name, body) in listOf("clear" to clearRequest, "write" to writeRequest)) {
            val cancelSites = indicesOf(body, "onCancel = {")
            assertEquals("$name request must expose exactly three warning dialogs", 3, cancelSites.size)
            for (idx in cancelSites) {
                val newline = body.indexOf('\n', idx)
                val line = body.substring(idx, if (newline < 0) body.length else newline)
                assertTrue(
                    "$name dialog onCancel must route through cancelAcceptedWarning",
                    line.contains("cancelAcceptedWarning(actionId)"),
                )
                assertFalse(
                    "$name dialog onCancel must not touch pendingClearConfirm directly",
                    line.contains("setPendingClearConfirm("),
                )
            }
        }
        assertEquals(
            "all six Main dialog cancels route through cancelAcceptedWarning",
            6,
            indicesOf(main, "cancelAcceptedWarning(actionId) { onCancel() }").size,
        )
        assertEquals(
            "cancelAcceptedWarning has one definition and six call sites",
            7,
            indicesOf(main, "cancelAcceptedWarning(").size,
        )

        // G2: every Main abort cleanup must pass the accepted action id so a
        // stale failed probe cannot schedule a restart for the live request.
        // actionId has no default, so a call omitting it would not compile;
        // pin both concrete forms to keep every call site visible here.
        assertEquals(
            "all Main abort cleanups pass actionId",
            3,
            indicesOf(main, "consentAbortCleanup(actionId = actionId)").size,
        )
        assertEquals(
            "all Main write aborts pass actionId",
            4,
            indicesOf(main, "consentAbortCleanup(R.string.pre_write_recheck_failed, actionId)").size,
        )
        assertEquals(
            "consentAbortCleanup has one definition and seven call sites",
            8,
            indicesOf(main, "consentAbortCleanup(").size,
        )

        // Function-scope gates: membership alone is not enough. A stale cancel
        // must be rejected before ANY side effect, so the isLive gate has to
        // precede the clear-flag write, and the abort gate has to precede both
        // consent cleanup and restart scheduling.
        val cb = block(main, "private fun cancelAcceptedWarning(")
        val liveAt = cb.indexOf("warningRestartController.isLive(actionId)")
        val clearAt = cb.indexOf("setPendingClearConfirm(false)")
        assertTrue("cancel must gate setPendingClearConfirm(false) with isLive", liveAt in 0 until clearAt)
        assertTrue("cancel must revoke the accepted identity", cb.contains("warningRestartController.revoke(actionId)"))

        val ab = block(main, "private fun consentAbortCleanup(")
        val abortLiveAt = ab.indexOf("warningRestartController.isLive(actionId)")
        assertTrue("abort must gate consent cleanup with isLive", abortLiveAt in 0 until ab.indexOf("pendingWarningConsent = null"))
        assertTrue("abort must gate restart scheduling with isLive", abortLiveAt in 0 until ab.indexOf("scheduleWarningRestart(actionId)"))
    }
}
