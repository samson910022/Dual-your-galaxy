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
            // G2: BOTH hosts funnel warning accepts through installAcceptedWarning;
            // the generation advances only at that seam, never at entry/fast path.
            val acceptSrc = block(src, "private fun installAcceptedWarning(")
            assertTrue("$name request*Warning must arm only via the accept seam", acceptSrc.contains("tryAcceptWarning("))
            assertTrue(
                "$name accept seam must be gated by the re-entry guard",
                acceptSrc.contains("consentResetAllowedForNewWarning(pendingClearConfirm)"),
            )
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

    // Collapse whitespace so a harmless reformat of a multi-line call does not
    // false-fail the gate-argument checks, while hard-wiring an argument to a
    // constant still does.
    private fun flat(src: String): String = src.replace(Regex("""\s+"""), " ")

    // Split a call's argument list at TOP-LEVEL commas only: nested calls such
    // as isLive(pending.consent?.actionId) must stay one argument.
    private fun callArguments(src: String, callNeedle: String): List<String> {
        val at = src.indexOf(callNeedle)
        assertTrue("$callNeedle must exist", at >= 0)
        val open = src.indexOf('(', at)
        assertTrue("$callNeedle must open an argument list", open >= 0)
        val args = mutableListOf<String>()
        var current = StringBuilder()
        var depth = 0
        var close = -1
        for (i in open + 1 until src.length) {
            when (val c = src[i]) {
                '(' -> {
                    depth++
                    current.append(c)
                }
                ')' -> {
                    if (depth == 0) {
                        close = i
                        break
                    }
                    depth--
                    current.append(c)
                }
                ',' -> {
                    if (depth == 0) {
                        args.add(flat(current.toString()).trim())
                        current = StringBuilder()
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
        }
        assertTrue("$callNeedle must close its argument list", close >= 0)
        val last = flat(current.toString()).trim()
        if (last.isNotEmpty()) args.add(last)
        return args
    }

    // PR #89 R3-3: the fail-closed orphan guard is one `if (...) { ... }` block
    // in each Activity's sync function. Return the WHOLE block (not just the
    // gate line) so tests can check both its internals and its position relative
    // to the final-dialog build site.
    private fun orphanBranch(body: String): String {
        val gateAt = body.indexOf("shouldInvalidateOrphanPendingOnRebuild(")
        if (gateAt < 0) return ""
        val ifAt = body.lastIndexOf("if (", gateAt)
        assertTrue("orphan gate must sit inside an if block", ifAt in 0 until gateAt)
        val open = body.indexOf('{', gateAt)
        assertTrue("orphan gate block must open", open >= 0)
        var depth = 0
        var close = -1
        for (i in open until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        close = i
                        break
                    }
                }
            }
        }
        assertTrue("orphan gate block must close", close >= 0)
        return body.substring(ifAt, close + 1)
    }

    // MainActivity.syncPolicyWriteDialog now legitimately calls the raw
    // vm.cancelPolicyWrite(pending) in that guard BEFORE a dialog is built. It
    // is a rebuild guard, not a dialog cancel arm, so strip it before asserting
    // the dialog's own cancel arms stay routed through the ownership helper.
    private fun withoutOrphanInvalidationBranch(body: String): String {
        val branch = orphanBranch(body)
        return if (branch.isEmpty()) body else body.replace(branch, "")
    }

    // G2: an Unknown Retry settle must validate the accepted action id BEFORE
    // it releases the freeze, consumes the retry token, or raises guidance.
    // Membership alone is not enough: the gate must precede EVERY side effect.
    private fun assertRetrySettleIsIdentityBound(name: String, body: String) {
        val gateNeedle = "warningRestartController.isLive(actionId)"
        val gates = indicesOf(body, gateNeedle)
        // One gate on the settled-Known branch and one on each settled-bad branch.
        assertTrue(
            "$name retry settle must gate every branch with isLive(actionId)",
            gates.size >= 3,
        )
        val releaseAt = body.indexOf("setPendingClearConfirm(false)")
        assertTrue("$name retry settle must expose a flag release", releaseAt >= 0)
        assertTrue("$name isLive gate must precede setPendingClearConfirm(false)", gates[0] in 0 until releaseAt)
        val retryAt = body.indexOf("consumeRetry(")
        assertTrue("$name retry settle must expose a Retry consumption", retryAt >= 0)
        assertTrue("$name isLive gate must precede consumeRetry(", gates[0] in 0 until retryAt)
        val guidance = indicesOf(body, "showWarningRetryGuidance(")
        assertEquals("$name retry settle must expose exactly two guidance sites", 2, guidance.size)
        assertTrue("$name first isLive gate must precede BOTH guidance sites", gates[0] in 0 until guidance[0])
        for (at in guidance) {
            assertTrue(
                "$name guidance must be preceded by an isLive(actionId) gate",
                gates.any { it in 0 until at },
            )
        }
    }

    // G4: whole-lambda slice for `onCancel = { ... }`. The warning cancel
    // bodies are one-liners that embed a NESTED `{ onCancel() }`, so reading
    // only to the next newline misses side effects appended after that nested
    // brace (and would miss a multi-line body entirely). Match braces instead.
    private fun cancelLambda(body: String, from: Int): String {
        val open = body.indexOf('{', from)
        assertTrue("onCancel lambda must open", open >= 0)
        var depth = 0
        for (i in open until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(from, i + 1)
                }
            }
        }
        throw AssertionError("onCancel lambda never closes")
    }

    private fun assertCancelsAreIdentityBound(name: String, body: String) {
        for (idx in indicesOf(body, "onCancel = {")) {
            val lambda = cancelLambda(body, idx)
            assertEquals(
                "$name dialog onCancel must have exactly one cancelAcceptedWarning(actionId)",
                1,
                indicesOf(lambda, "cancelAcceptedWarning(actionId)").size,
            )
            for (forbidden in listOf(
                "setPendingClearConfirm(",
                "pendingClearConfirm =",
                "showWarningRetryGuidance(",
            )) {
                assertFalse("$name dialog onCancel must not contain $forbidden", lambda.contains(forbidden))
            }
        }
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
            // G2: NO direct install inside ANY request body. Every accept branch
            // (Unknown + probe + no-warning fast paths) goes through a seam.
            assertFalse("$name request body must not install directly", requestBody.contains("warningRestartController.install("))
            assertEquals(
                "$name must accept both warning branches through the warning seam",
                2,
                indicesOf(requestBody, "installAcceptedWarning(").size,
            )
            assertEquals(
                "$name no-warning fast paths must install an identity through the action seam",
                if (name.startsWith("MainActivity")) 2 else 1,
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
            // The correlation-only `currentGeneration` read is REQUIRED above,
            // so strip it before checking that the fast path touches no other
            // one-shot member (onWarningInstalled/capture/tryConsume/...).
            // block() bounds this expression-bodied seam at the next top-level
            // declaration, so a later guarded body cannot leak into the slice.
            val fastPathOneShotUses = actionSeam.replace("warningRetryOneShot.currentGeneration", "")
            assertFalse(
                "$name fast-path seam must not advance one-shot",
                fastPathOneShotUses.contains("onWarningInstalled()") ||
                    fastPathOneShotUses.contains("warningRetryOneShot."),
            )
            assertFalse("$name request body must not advance one-shot", requestBody.contains("onWarningInstalled()"))
            // Exactly one scoped assertion: the Known/no-op fast path has
            // NO accept guard and must not advance the retry generation.
            assertFalse(
                "fast-path seam must NOT advance the retry generation or contain the accept guard",
                actionSeam.contains("tryAcceptWarning(") || actionSeam.contains(acceptGuard),
            )
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
            "Editor installs a sanitized-candidate identity at all three accept branches",
            3,
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
            // G2: BOTH hosts capture the accept-time id instead of reading the
            // controller's retry slot at settle time, and bind every
            // abort/cleanup through it.
            assertFalse("$name must not read the retry slot at settle time", src.contains("warningRestartController.retryRequestId()"))
            assertTrue(src.contains("warningRestartController.isLive("))
            assertTrue(src.contains("warningRestartController.revoke("))
            assertTrue(src.contains("expectedRequestId = actionId"))
            assertTrue(src.contains("private fun cancelAcceptedWarning("))
            assertTrue(src.contains("private var pendingWarningRestartActionId: Long? = null"))
            assertTrue(
                "$name onDestroy must clear the controller and its posted Runnable",
                src.contains("warningRestartController.clear()") && src.contains("pendingWarningRestartRunnable?.let {"),
            )
        }
        for ((name, src) in listOf("MainActivity" to main, "PolicyActivity" to editor)) {
            val destroy = block(src, "override fun onDestroy()")
            assertTrue("$name onDestroy must drop session-scoped consent and Runnable owner",
                destroy.contains("pendingWarningConsent = null") && destroy.contains("pendingWarningRestartActionId = null"))
        }
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
            // G2: provenance is applied in the shared seams, then forwarded by
            // every accept branch (Main: 2 warnings + 2 fast paths; Editor:
            // Unknown warning + no-op fast path + probe warning).
            val warningSeam = block(src, "private fun installAcceptedWarning(")
            val actionSeam = block(src, "private fun installAcceptedAction(")
            assertTrue(warningSeam.contains("resetBudget = fromUserTap"))
            assertTrue(actionSeam.contains("resetBudget = fromUserTap"))
            assertEquals(
                "$name every accept branch must forward provenance",
                if (name.startsWith("MainActivity")) 4 else 3,
                indicesOf(block(src, request), "fromUserTap = fromUserTap").size,
            )
        }
        // The INSTALLED callback serves BOTH auto-restart delivery and Retry
        // settles; neither may ever be mistaken for a fresh user entry tap.
        // G2: one lambda per request function is reused by all its accept sites.
        assertEquals(1, indicesOf(main, "startWriteWarning(fromUserTap = false)").size)
        assertEquals(1, indicesOf(main, "startClearWarning(fromUserTap = false)").size)
        assertEquals(1, indicesOf(editor, "startEditorWriteWarning(HashSet(ticks), fromUserTap = false)").size)
        // Genuine click handlers are the only true provenance sources.
        assertEquals(2, indicesOf(main, "startWriteWarning(fromUserTap = true)").size)
        assertEquals(1, indicesOf(main, "startClearWarning(fromUserTap = true)").size)
        assertEquals(1, indicesOf(editor, "startEditorWriteWarning(candidate, fromUserTap = true)").size)
    }

    @Test
    fun retryUnknownWarning_settleIsIdentityGatedBeforeRelease() {
        assertRetrySettleIsIdentityBound(
            "MainActivity",
            block(main, "private fun retryUnknownWarning("),
        )
        assertRetrySettleIsIdentityBound(
            "PolicyActivity",
            block(editor, "private fun retryUnknownWarning("),
        )
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
            // G4: scan the WHOLE lambda, not just its first line. The nested
            // `{ onCancel() }` means a stray side effect could follow on the
            // same line or on a later line of a multi-line body.
            assertCancelsAreIdentityBound(name, body)
        }
        assertEquals(
            "all six Main dialog cancels route through cancelAcceptedWarning",
            6,
            indicesOf(main, "cancelAcceptedWarning(actionId) { onCancel() }").size,
        )
        assertEquals(
            "cancelAcceptedWarning has one definition, six warning call sites, and one helper call site",
            8,
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

        // Mirror the Editor probe-settle gate assertions for BOTH Main request
        // functions: the accepted-id gate must precede consent/dialog side effects.
        for ((name, body) in listOf(
            "Main write" to block(main, "private fun requestPreWriteWarning("),
            "Main clear" to block(main, "private fun requestPreClearWarning("),
        )) {
            assertTrue(body.contains("onRetry = { retryUnknownWarning(actionId) }"))
            assertTrue(body.contains("actionAlive = { warningRestartController.isLive(actionId) }"))
            val gateAt = body.indexOf("if (!warningRestartController.isLive(actionId))")
            assertTrue("$name probe settle gate must exist", gateAt >= 0)
            assertTrue("$name probe settle gate must precede when(probe)", gateAt in 0 until body.indexOf("when (probe) {"))
            assertTrue(
                "$name probe settle gate must precede consent write",
                gateAt in 0 until body.indexOf("pendingWarningConsent = buildPolicyWriteConsent("),
            )
        }
    }

    @Test
    fun editorDialogCancelsAndAborts_areIdentityBound() {
        val body = block(editor, "private fun requestEditorWriteWarning(")
        val cancelSites = indicesOf(body, "onCancel = {")
        assertEquals("editor request must expose exactly three warning dialogs", 3, cancelSites.size)
        // G4: same whole-lambda scan as Main, so a nested callback cannot hide
        // a trailing setPendingClearConfirm / guidance side effect.
        assertCancelsAreIdentityBound("editor", body)
        assertEquals(
            "all three Editor dialog cancels route through cancelAcceptedWarning",
            3,
            indicesOf(editor, "cancelAcceptedWarning(actionId) { onCancel() }").size,
        )
        assertEquals(
            "cancelAcceptedWarning has one definition, three warning call sites, and one helper call site",
            5,
            indicesOf(editor, "cancelAcceptedWarning(").size,
        )
        assertEquals(
            "all Editor abort cleanups pass actionId",
            4,
            indicesOf(editor, "consentAbortCleanup(R.string.pre_write_recheck_failed, actionId)").size,
        )
        assertEquals(
            "consentAbortCleanup has one definition and four call sites",
            5,
            indicesOf(editor, "consentAbortCleanup(").size,
        )

        val cb = block(editor, "private fun cancelAcceptedWarning(")
        val liveAt = cb.indexOf("warningRestartController.isLive(actionId)")
        val clearAt = cb.indexOf("setPendingClearConfirm(false)")
        assertTrue("cancel must gate setPendingClearConfirm(false) with isLive", liveAt in 0 until clearAt)
        assertTrue("cancel must revoke the accepted identity", cb.contains("warningRestartController.revoke(actionId)"))

        val ab = block(editor, "private fun consentAbortCleanup(")
        val abortLiveAt = ab.indexOf("warningRestartController.isLive(actionId)")
        assertTrue("abort must gate consent cleanup with isLive", abortLiveAt in 0 until ab.indexOf("pendingWarningConsent = null"))
        assertTrue("abort must gate restart scheduling with isLive", abortLiveAt in 0 until ab.indexOf("scheduleWarningRestart(actionId)"))

        // G2: every deferred lambda captures the accept-time id; the whole
        // probe settle is identity-gated before consent/dialog/flag/proceed.
        assertTrue(body.contains("onRetry = { retryUnknownWarning(actionId) }"))
        assertTrue(body.contains("actionAlive = { warningRestartController.isLive(actionId) }"))
        val gateAt = body.indexOf("if (!warningRestartController.isLive(actionId))")
        assertTrue("probe settle gate must precede when(probe)", gateAt in 0 until body.indexOf("when (probe) {"))
        assertTrue("probe settle gate must precede consent write", gateAt in 0 until body.indexOf("pendingWarningConsent = buildPolicyWriteConsent("))
    }

    // PR #89 Round-3 R3-1: the FINAL policy-write confirmation dialog's two
    // cancel arms (OnCancel + Negative) must route through the ownership-revoking
    // helper, never a raw VM cancel. A cancel that clears the QUEUED pending but
    // leaves the accepted warning action live lets the suspended revalidation
    // abort schedule a restart for an already-cancelled WRITE.
    @Test
    fun finalWriteDialogCancels_routeThroughOwnershipRevokingHelper() {
        data class DialogSeam(
            val name: String,
            val src: String,
            val dialogFn: String,
            val cancelAnchors: List<String>,
            val rawCancelInBody: Int,
        )
        for (seam in listOf(
            DialogSeam(
                "MainActivity", main, "private fun syncPolicyWriteDialog(",
                listOf("setOnCancelListener", "BUTTON_NEGATIVE)?.setOnClickListener"),
                rawCancelInBody = 1, // the fail-closed orphan guard, not a dialog cancel arm
            ),
            DialogSeam(
                "PolicyActivity", editor, "private fun showConfirmDialog(",
                listOf("setOnCancelListener", "BUTTON_NEGATIVE)?.apply"),
                // Editor's fail-closed orphan guard lives in syncPolicyConfirmDialog,
                // not in showConfirmDialog, so this dialog body has no raw cancel.
                rawCancelInBody = 0,
            ),
        )) {
            val body = block(seam.src, seam.dialogFn)
            // Both cancel arms must use the helper with the accepted action id.
            // R3-3 exception: MainActivity's fail-closed orphan guard calls the
            // raw VM cancel before building a dialog; strip that guard so this
            // assertion still checks ONLY the final dialog's cancel arms.
            val cancelArmsBody = withoutOrphanInvalidationBranch(body)
            assertEquals(
                "${seam.name} final dialog must expose exactly two helper cancel arms",
                2,
                indicesOf(body, "cancelWritePendingAndRevoke(pending, pending.consent?.actionId)").size,
            )
            assertEquals(
                "${seam.name} final dialog must not call the raw VM cancel directly",
                0,
                indicesOf(cancelArmsBody, "vm.cancelPolicyWrite(").size,
            )
            // Pin the raw-cancel count in the WHOLE body too, so a second raw
            // cancel cannot hide in an unguarded arm elsewhere in the function.
            val rawCancels = indicesOf(body, "vm.cancelPolicyWrite(")
            assertEquals(
                "${seam.name} raw VM cancel count in the dialog body",
                seam.rawCancelInBody,
                rawCancels.size,
            )
            if (seam.rawCancelInBody == 1) {
                val branch = orphanBranch(body)
                val branchStart = body.indexOf(branch)
                assertTrue("${seam.name} orphan branch must be present", branchStart >= 0)
                assertTrue(
                    "${seam.name} raw VM cancel must be the one inside the orphan branch",
                    rawCancels[0] in branchStart until branchStart + branch.length,
                )
            }
            for (anchor in seam.cancelAnchors) {
                val at = body.indexOf(anchor)
                assertTrue("${seam.name} final dialog must expose the $anchor cancel arm", at >= 0)
                // Whole-lambda slice: a raw cancel appended after a nested
                // `{ }` on the same line must not escape this scan.
                val lambda = cancelLambda(body, at)
                assertTrue(
                    "${seam.name} $anchor arm must route through cancelWritePendingAndRevoke",
                    lambda.contains("cancelWritePendingAndRevoke(pending, pending.consent?.actionId)"),
                )
                val helperAt = lambda.indexOf("cancelWritePendingAndRevoke(")
                val rawAt = lambda.indexOf("vm.cancelPolicyWrite(")
                assertFalse("${seam.name} $anchor arm must not call the raw VM cancel", rawAt >= 0)
                // Gate ordering: the helper must precede any raw VM cancel.
                assertTrue(
                    "${seam.name} helper must precede any raw VM cancel in the $anchor arm",
                    rawAt < 0 || helperAt < rawAt,
                )
                // QUEUED-only: the final-dialog cancel lambda has SAVING /
                // RECONCILING early arms that must NOT cancel the pending write.
                // Slice from the last `} else {` (the QUEUED arm) so a helper
                // call parked in an unrelated branch cannot satisfy the scan.
                val queuedElseAt = lambda.lastIndexOf("} else {")
                assertTrue("${seam.name} $anchor arm must expose a QUEUED else branch", queuedElseAt >= 0)
                val queuedArm = lambda.substring(queuedElseAt)
                assertTrue(
                    "${seam.name} $anchor QUEUED arm must route through cancelWritePendingAndRevoke",
                    queuedArm.contains("cancelWritePendingAndRevoke(pending, pending.consent?.actionId)"),
                )
                assertFalse(
                    "${seam.name} $anchor QUEUED arm must not call the raw VM cancel",
                    queuedArm.contains("vm.cancelPolicyWrite("),
                )
            }
            // The helper itself gates revocation on BOTH the real VM result and
            // the live action identity, then revokes with an empty onCancel.
            val helper = block(seam.src, "private fun cancelWritePendingAndRevoke(")
            assertTrue(
                "${seam.name} helper must gate on real VM cancel AND live action",
                helper.contains("cancelOwnershipAccepted(vmCancelled, warningRestartController.isLive(actionId))"),
            )
            assertTrue(
                "${seam.name} helper must read the Boolean VM cancel result",
                helper.contains("try { vm.cancelPolicyWrite(pending) } catch (_: Exception) { false }"),
            )
            // Strip comment-only lines so a commented-out revoke cannot satisfy
            // this check: the production call itself must be present in code.
            val helperCode = helper.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            // Require the LITERAL `if (` form. A bare
            // `cancelOwnershipAccepted(...)` statement followed by an
            // unconditional revoke would otherwise let the brace matcher below
            // start at the revoke's own `{ }` and report it as "inside" a gate
            // that is never actually evaluated as a condition.
            val gateNeedle = "if (cancelOwnershipAccepted("
            val gateAt = helperCode.indexOf(gateNeedle)
            assertTrue(
                "${seam.name} helper must gate revocation with if (cancelOwnershipAccepted(...))",
                gateAt >= 0,
            )
            val revokeAt = helperCode.indexOf("cancelAcceptedWarning(actionId) { }")
            assertTrue("${seam.name} helper must revoke in code, not just in a comment", revokeAt >= 0)
            assertTrue("${seam.name} ownership gate must precede revocation", gateAt in 0 until revokeAt)
            // R3-4: nesting, not merely membership/order. A gate that is
            // evaluated but ignored (revoke parked OUTSIDE the block) must fail.
            assertEquals(
                "${seam.name} helper must expose exactly one conditional revoke",
                1,
                indicesOf(helperCode, "cancelAcceptedWarning(actionId)").size,
            )
            // Reuse the whole-lambda brace matcher starting at the `if`: its
            // first `{` is the gate's own block (the revoke's `{ }` is nested
            // inside), so an ungated revoke can no longer pose as the block.
            val gateBlock = cancelLambda(helperCode, gateAt)
            val gateBlockAt = helperCode.indexOf(gateBlock)
            assertTrue("${seam.name} ownership gate must open a block", gateBlockAt >= 0)
            val revokeCallAt = helperCode.indexOf("cancelAcceptedWarning(actionId)")
            assertTrue(
                "${seam.name} revoke must be NESTED inside the cancelOwnershipAccepted block",
                revokeCallAt in gateBlockAt until gateBlockAt + gateBlock.length,
            )
        }
    }

    // PR #89 Round-3 R3-2: the final-dialog Positive arm must capture the VM's
    // admission verdict and revoke the accepted warning action ONLY when the
    // write actually left the QUEUED state. A RETRYABLE_RETAINED refusal (e.g.
    // the ack was unchecked during the fresh-validation await) keeps the same
    // pending; revoking there leaves the retry with no live action, so the next
    // Positive trips `!isLive(actionId)` and the dialog silently disappears.
    @Test
    fun finalConfirmRevocation_isAdmissionGated() {
        data class ConfirmSeam(
            val name: String,
            val src: String,
            val ackExpr: String,
            // Editor-only: the callback-time ack-generation computation. Main
            // re-reads its live field inline in the confirm call; Editor must
            // snapshot the holder after the revalidation await instead.
            val callbackAckComputation: String? = null,
        )
        for (seam in listOf(
            ConfirmSeam("MainActivity", main, "if (policyAcknowledged) pending.generation else null"),
            // The Editor's ack CheckBox stays interactive while revalidation
            // suspends, so the holder must be re-read AT CALLBACK TIME. A plain
            // `pending.generation` there ignores a withdrawal during the await.
            // The VM has OTHER retained-pending refusals too (RECONCILING etc.),
            // so the admission gate is required on this host as well.
            ConfirmSeam(
                "PolicyActivity", editor, "ackGen",
                """
                val ackGen = if (policyConfirmState.acknowledged &&
                    policyConfirmState.ackGeneration == pending.generation
                ) pending.generation else null
                """.trimIndent(),
            ),
        )) {
            val body = block(seam.src, "private fun confirmWriteWithConsent(")
            val callLine = body.lines().firstOrNull { it.contains("vm.confirmPolicyWrite(") }
                ?: throw AssertionError("${seam.name} final confirm must call vm.confirmPolicyWrite")
            assertTrue(
                "${seam.name} final confirm must capture the admission verdict",
                callLine.trimStart().startsWith("val admission = vm.confirmPolicyWrite("),
            )
            assertTrue(
                "${seam.name} final confirm must keep the ack LIVE at callback time",
                callLine.contains("vm.confirmPolicyWrite(pending, ${seam.ackExpr})"),
            )

            // Strip comment-only lines: a documented guard cannot satisfy the
            // ordering check, only executable code can.
            val code = body.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            seam.callbackAckComputation?.let { computation ->
                // Flatten both sides: a harmless line break before `)` must not
                // false-fail, but replacing the holder check with a plain
                // `pending.generation` still does.
                val normalized = flat(computation).trim()
                val flatCode = flat(code)
                assertTrue(
                    "${seam.name} ack generation must be computed at callback time from the live holder",
                    flatCode.contains(normalized),
                )
                assertTrue(
                    "${seam.name} ack computation must precede the confirm call",
                    flatCode.indexOf(normalized) in 0 until flatCode.indexOf("vm.confirmPolicyWrite(pending, ackGen)"),
                )
            }
            assertFalse(
                "${seam.name} final confirm must not pass a plain pending.generation",
                callLine.contains("vm.confirmPolicyWrite(pending, pending.generation)"),
            )
            val guard = "if (confirmRevocationAccepted(admission)) {"
            val guardAt = code.indexOf(guard)
            val revokeAt = code.indexOf("warningRestartController.revoke(actionId)")
            assertTrue("${seam.name} final confirm must expose the confirmRevocationAccepted gate", guardAt >= 0)
            assertTrue("${seam.name} final confirm must expose the ownership revoke", revokeAt >= 0)
            assertTrue(
                "${seam.name} admission gate must precede revocation",
                guardAt in 0 until revokeAt,
            )

            // Exactly one confirm call and one revoke in this function: no
            // parallel unguarded arm can hide behind the gated one.
            assertEquals(
                "${seam.name} final confirm must have one VM confirm call",
                1,
                indicesOf(code, "vm.confirmPolicyWrite(").size,
            )
            assertEquals(
                "${seam.name} final confirm must have one ownership revoke",
                1,
                indicesOf(code, "warningRestartController.revoke(actionId)").size,
            )
            // A second revalidation or dispatch arm would suspend/confirm twice;
            // pin BOTH seams to exactly one call per host (membership is not enough).
            assertEquals(
                "${seam.name} final confirm must revalidate consent exactly once",
                1,
                indicesOf(code, "revalidatePolicyWriteConsent(").size,
            )
            assertEquals(
                "${seam.name} final confirm must dispatch consent-gated confirm exactly once",
                1,
                indicesOf(code, "dispatchConsentGatedConfirm(").size,
            )
            // Whole-lambda style: the revoke must be INSIDE the guard block,
            // not merely after it on an unguarded line. Match the guard's own
            // `{` to its closing `}` — counting braces between guard and revoke
            // is unsound: an empty guard plus a separate `run { revoke(...) }`
            // arm leaves more `{` than `}` before the revoke yet revokes
            // unconditionally.
            val guardOpen = code.indexOf('{', guardAt)
            assertTrue("${seam.name} admission guard must open a block", guardOpen >= 0)
            var depth = 0
            var guardClose = -1
            for (i in guardOpen until code.length) {
                when (code[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            guardClose = i
                            break
                        }
                    }
                }
            }
            assertTrue("${seam.name} admission guard block must close", guardClose >= 0)
            assertTrue(
                "${seam.name} revoke must be nested inside the admission guard",
                revokeAt in guardOpen until guardClose,
            )
            assertFalse(
                "${seam.name} must not have a bare confirm immediately followed by revoke",
                Regex("""vm\.confirmPolicyWrite\([^\n]*\)\s*\n\s*warningRestartController\.revoke\(""")
                    .containsMatchIn(code),
            )
        }
    }

    // PR #89 Round-4 R4-1: ACCEPTED only DISPATCHES the VM attempt. Both hosts
    // must hold a per-pending token across the async window, pass the settlement
    // callback to confirmPolicyWrite, and retire the action ONLY from the
    // dedicated settlement handler (never inline on the dispatch verdict).
    // Source shape only — actual async outcomes are proven by PolicyWriteTest
    // and the real WarningRestartController tests (DEVICE-UNVERIFIED for dialogs).
    @Test
    fun finalConfirmAsyncOwnership_isPerPendingAndStaleSafe() {
        for ((name, src) in listOf("MainActivity" to main, "PolicyActivity" to editor)) {
            val body = block(src, "private fun confirmWriteWithConsent(")
            val code = body.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            val codeFlat = flat(code)

            // Exactly one tokenized attempt guard, BEFORE every cancel path and
            // before the short global revalidation guard.
            val enterNeedle = "policyWriteAttempts.tryEnter(generation)"
            assertEquals("$name must enter one per-pending attempt", 1, indicesOf(codeFlat, enterNeedle).size)
            val enterAt = codeFlat.indexOf(enterNeedle)
            assertTrue(
                "$name attempt guard must precede the no-live-action cancel",
                codeFlat.indexOf("if (!warningRestartController.isLive(actionId))") in enterAt until codeFlat.length,
            )
            assertTrue(
                "$name attempt guard must precede the global revalidation guard",
                codeFlat.indexOf("consentConfirmInFlight.tryEnter()") in enterAt until codeFlat.length,
            )

            // The confirm call carries the settlement callback.
            val confirmAt = codeFlat.indexOf("vm.confirmPolicyWrite(")
            assertTrue("$name must call vm.confirmPolicyWrite", confirmAt >= 0)
            val confirmCall = codeFlat.substring(confirmAt, minOf(codeFlat.length, confirmAt + 400))
            assertTrue(
                "$name confirm must pass the async settlement callback",
                confirmCall.contains("onPolicyWriteAttemptComplete(actionId, generation, attemptId, cbGen, outcome)"),
            )

            // ACCEPTED only hands ownership to the callback; no inline retire.
            val acceptedNeedle = "if (admission == PolicyWriteConfirmAdmission.ACCEPTED) {"
            assertTrue("$name must gate handoff on ACCEPTED", codeFlat.contains(acceptedNeedle))
            val acceptedAt = codeFlat.indexOf(acceptedNeedle)
            val handedAt = codeFlat.indexOf("handedOff = true")
            val elseAt = codeFlat.indexOf("} else {", acceptedAt)
            assertTrue(
                "$name handedOff must be set inside the ACCEPTED arm",
                acceptedAt in 0 until handedAt && handedAt < elseAt,
            )
            assertEquals("$name must have exactly one inline (synchronous) revoke",
                1, indicesOf(codeFlat, "warningRestartController.revoke(actionId)").size)

            // Every non-handed-off attempt is released by the finally.
            assertTrue(
                "$name finally must release a non-handed-off attempt",
                codeFlat.contains("if (!handedOff) { policyWriteAttempts.exit(generation, attemptId) }"),
            )

            // The dedicated settlement handler consumes the token ATOMICALLY
            // and fences stale generations before retiring.
            val handler = block(src, "private fun onPolicyWriteAttemptComplete(")
            val handlerFlat = flat(handler)
            assertTrue(
                "$name settlement must consume the token via exit()",
                handlerFlat.contains("val attemptActive = policyWriteAttempts.exit(generation, attemptId)"),
            )
            assertTrue("$name settlement must reject a stale generation",
                handlerFlat.contains("callbackGeneration != generation"))
            assertTrue("$name settlement must use the framework-free retirement gate",
                handlerFlat.contains("confirmAttemptRetiresAction("))
            assertEquals("$name settlement must own exactly one revoke",
                1, indicesOf(handlerFlat, "warningRestartController.revoke(actionId)").size)
            assertFalse("$name settlement must not use isActive as a check-then-act token",
                handlerFlat.contains("policyWriteAttempts.isActive("))

            // Lifecycle + click peek.
            val destroy = block(src, "override fun onDestroy()")
            assertTrue("$name onDestroy must clear attempt tokens",
                flat(destroy).contains("policyWriteAttempts.clear()"))
            assertTrue("$name Positive click must peek the per-pending guard",
                flat(src).contains("policyWriteAttempts.isAnyActive("))
        }
    }

    // PR #89 Round-3 R3-3: rotation leaves a QUEUED pending in the retained VM
    // but destroys the Activity's WarningRestartController, so the rebuilt final
    // dialog has no live consent action and its Positive arm silently cancels the
    // pending. The accepted strategy is SIMPLE FAIL-CLOSED: invalidate the orphan
    // BEFORE showing an operable final dialog, guide the user to restart, and
    // leave SAVING/RECONCILING + app-scoped Tx/handover alone. No Bundle-based
    // consent/ack restoration and no auto-ack are allowed.
    //
    // DEVICE-UNVERIFIED: this test proves ONLY the two Activities' source shape
    // plus the framework-free predicate truth table
    // (PolicyWriteAdmissionPredicatesTest.shouldInvalidateOrphanPendingOnRebuild).
    // The end-to-end Activity/VM/device rotation behavior (real saved state, real
    // VM replay, real dialog, real cancel result) is NOT executed here and stays
    // DEVICE-UNVERIFIED; no on-device acceptance is claimed.
    @Test
    fun orphanPendingRebuild_failsClosedBeforeFinalDialogInBothActivities() {
        data class SyncSeam(
            val name: String,
            val src: String,
            val syncFn: String,
            val buildNeedle: String,
            val phaseArg: String,
            val handleClear: String,
        )
        for (seam in listOf(
            SyncSeam(
                "MainActivity", main, "private fun syncPolicyWriteDialog(",
                "AlertDialog.Builder(this)", "policyWritePhase", "policyWriteDialog = null",
            ),
            SyncSeam(
                "PolicyActivity", editor, "private fun syncPolicyConfirmDialog(",
                "showConfirmDialog(", "phase", "policyConfirmDialog = null",
            ),
        )) {
            val onCreate = block(seam.src, "override fun onCreate(savedInstanceState: Bundle?)")
            assertTrue(
                "${seam.name}.onCreate must record a saved-state rebuild",
                onCreate.contains("recreatedFromSavedState = savedInstanceState != null"),
            )
            assertTrue(
                "${seam.name}.onCreate must route rotation state through the holder",
                onCreate.contains("policyConfirmState.restoreFromBundle(savedInstanceState)"),
            )
            // Policy-write consent / ack are session-scoped and NEVER restored
            // from a Bundle. Scan the WHOLE onCreate body for ANY getter with a
            // consent / ack / writeConfirm key, regardless of receiver spelling
            // (`savedInstanceState[?/!!]`, `arguments`, `intent?.extras`, or `it`
            // inside `savedInstanceState?.let`) and regardless of getter name
            // (`get`, `getBoolean`, `getString`, `getLong`, `getInt`, ...).
            // Comment-only lines are ignored. PolicyActivity legitimately has
            // NO such getter, so this is a no-ack guard, not a "must find one"
            // assertion: adding any of these reads in either host must fail.
            // KEY_REMOVE_ACK / KEY_BATCH_ACK belong to unrelated remove/batch
            // flows and remain legitimate reads.
            val onCreateCode = flat(
                onCreate.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n"),
            )
            val bundleGet = Regex("""\.get\w*\s*\(([^)]*)\)""")
            for (match in bundleGet.findAll(onCreateCode)) {
                val key = match.groupValues[1].trim()
                val unrelatedFlow = key.contains("KEY_REMOVE_ACK") || key.contains("KEY_BATCH_ACK")
                val policyKey = key.contains("consent", ignoreCase = true) ||
                    key.contains("writeConfirm", ignoreCase = true) ||
                    (key.contains("ack", ignoreCase = true) && !unrelatedFlow)
                assertFalse(
                    "${seam.name}.onCreate must not restore policy consent/ack from Bundle (key=$key)",
                    policyKey,
                )
            }

            // The holder itself must force-clear the session-scoped ack on
            // restore, whether or not a legacy Bundle carried those keys.
            val holderRestore = block(prodFile("PolicyConfirmDialogState.kt").readText(), "fun restoreFromBundle(")
            assertTrue(
                "PolicyConfirmDialogState.restoreFromBundle must clear acknowledged",
                flat(holderRestore).contains("acknowledged = false"),
            )
            assertTrue(
                "PolicyConfirmDialogState.restoreFromBundle must clear ackGeneration",
                flat(holderRestore).contains("ackGeneration = null"),
            )

            val sync = block(seam.src, seam.syncFn)
            // Comment-only lines are NOT executable: a commented-out
            // `// recreatedFromSavedState = false` must not satisfy the one-shot
            // marker snapshot/reset/gate ordering checks below.
            val flatSync = flat(sync.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n"))
            // R3-4 (P2): the rotation marker is a one-shot consumed on the FIRST
            // sync regardless of phase/pending, and ALWAYS before the gate.
            // "Reset only inside the orphan branch" leaks it across the Activity
            // lifetime; pin the snapshot, the reset, and their ordering here.
            val snapshotAt = flatSync.indexOf("val rebuilt = recreatedFromSavedState")
            val markerResetAt = flatSync.indexOf("recreatedFromSavedState = false")
            val syncGateAt = flatSync.indexOf("shouldInvalidateOrphanPendingOnRebuild(")
            assertTrue("${seam.name}.sync must snapshot the rotation marker", snapshotAt >= 0)
            assertTrue("${seam.name}.sync must consume the marker on the first sync", markerResetAt >= 0)
            assertTrue("${seam.name}.sync must expose the orphan gate", syncGateAt >= 0)
            assertTrue("${seam.name} marker snapshot must precede its reset", snapshotAt in 0 until markerResetAt)
            assertTrue("${seam.name} marker must be cleared BEFORE the gate", markerResetAt in 0 until syncGateAt)

            val branch = orphanBranch(sync)
            assertTrue("${seam.name}.sync must expose the orphan-invalidation branch", branch.isNotEmpty())
            val flatBranch = flat(branch)
            // Comment-only lines are NOT control flow: a `// if (cancelled) {`
            // must not open a brace span that swallows the invalidation effects.
            // Build one code-only view and use it for the cancel-result gate,
            // its brace match, and the effect scan (and the tail return check
            // further down).
            val codeBranch = flat(
                branch.lines().filterNot { it.trimStart().startsWith("//") }.joinToString(" "),
            )
            assertFalse(
                "${seam.name} orphan branch must NOT hold the marker reset",
                flatBranch.contains("recreatedFromSavedState = false"),
            )
            assertTrue(flatBranch.contains("shouldInvalidateOrphanPendingOnRebuild("))
            // Pin the GATE ARGUMENTS, not only the call name: hard-wiring any of
            // rebuilt / phase / live-action to a constant must fail this test.
            // The rebuilt argument is the captured snapshot, not the mutable flag.
            assertEquals(
                "${seam.name} orphan gate must pass the live rebuilt/phase/action arguments",
                listOf(
                    "rebuilt",
                    seam.phaseArg,
                    "warningRestartController.isLive(pending.consent?.actionId)",
                ),
                callArguments(branch, "shouldInvalidateOrphanPendingOnRebuild("),
            )
            // R3-4 (MEDIUM): the invalidation is gated on the REAL VM cancel
            // result. A refused cancel (SAVING/RECONCILING or app-scoped Tx/
            // handover) must fall through to normal busy/Tx rendering.
            val cancelResultAt = codeBranch.indexOf("val cancelled = vm.cancelPolicyWrite(pending)")
            val resultGateAt = codeBranch.indexOf("if (cancelled)")
            assertTrue("${seam.name} orphan branch must capture the Boolean VM cancel result", cancelResultAt >= 0)
            assertEquals(
                "${seam.name} orphan branch must have exactly one raw VM cancel",
                1,
                indicesOf(codeBranch, "vm.cancelPolicyWrite(pending)").size,
            )
            assertTrue(
                "${seam.name} orphan branch must gate invalidation on the captured result",
                resultGateAt in cancelResultAt until codeBranch.length,
            )
            // Nesting, not membership: dismiss / holder clear / toast / early
            // return must all live INSIDE `if (cancelled) { ... }`, so a
            // gate-evaluated-but-ignored cancel cannot invalidate the pending.
            val resultGateBlock = cancelLambda(codeBranch, resultGateAt)
            val resultGateBlockAt = codeBranch.indexOf(resultGateBlock)
            assertTrue("${seam.name} cancel-result gate must open a block", resultGateBlockAt >= 0)
            val resultGateEnd = resultGateBlockAt + resultGateBlock.length
            for (effect in listOf(seam.handleClear, "onPendingCleared()", "R.string.pre_write_recheck_failed", "return")) {
                val effectAt = codeBranch.indexOf(effect, resultGateBlockAt)
                assertTrue(
                    "${seam.name} '$effect' must be nested inside the cancel-result gate",
                    effectAt in resultGateBlockAt until resultGateEnd,
                )
            }
            assertTrue(flatBranch.contains("onPendingCleared()"))
            assertTrue(flatBranch.contains("R.string.pre_write_recheck_failed"))
            assertTrue("${seam.name} orphan branch must clear its dialog handle", flatBranch.contains(seam.handleClear))
            // Gate ordering inside the branch: decide → cancel → clear holder.
            val inBranchGate = flatBranch.indexOf("shouldInvalidateOrphanPendingOnRebuild(")
            val inBranchCancel = flatBranch.indexOf("vm.cancelPolicyWrite(pending)")
            val inBranchClear = flatBranch.indexOf("onPendingCleared()")
            assertTrue(inBranchGate in 0 until inBranchCancel)
            assertTrue(inBranchCancel in 0 until inBranchClear)
            // NO warning action may be re-bound/re-armed/re-delivered here, and
            // NO ack may be manufactured: the orphan is invalidated, never
            // re-authorized from saved state.
            for (forbidden in listOf(
                "warningRestartController.install(",
                "warningRestartController.deliver(",
                "warningRestartController.consumeRetry(",
                "policyAcknowledged = true",
                "onAckChanged(true",
                "acknowledged = true",
            )) {
                assertFalse("$seam.name orphan branch must not call $forbidden", flatBranch.contains(forbidden))
            }
            // The orphan branch must return BEFORE every final-dialog build/show
            // site. Check the branch's LAST statement, so merely burying a
            // `return` earlier in the block cannot satisfy the requirement.
            // Comment-only lines are NOT control flow: a commented-out
            // `// return }` must fail both checks below. Reuse the code-only
            // `codeBranch` built above, and keep `branchStart` from the raw
            // `sync` so the return index still orders against build sites.
            // The early return closes BOTH the `if (cancelled)` gate and the
            // outer orphan gate, so allow the nested closing braces at the tail.
            assertTrue(
                "${seam.name} orphan branch must end with an early return",
                Regex("""return\s*(\}\s*)+$""").containsMatchIn(codeBranch),
            )
            val branchStart = sync.indexOf(branch)
            assertTrue("${seam.name} orphan branch must be a contiguous slice", branchStart >= 0)
            val returnInCode = codeBranch.lastIndexOf("return")
            assertTrue("${seam.name} orphan branch must expose a return statement", returnInCode >= 0)
            val returnAt = branchStart + returnInCode
            for (buildAt in indicesOf(sync, seam.buildNeedle)) {
                assertTrue("${seam.name}.sync must expose its final-dialog build site", buildAt >= 0)
                assertTrue("${seam.name} early return must precede every final-dialog build", returnAt < buildAt)
            }

            val destroy = block(seam.src, "override fun onDestroy()")
            assertTrue(
                "${seam.name}.onDestroy must still clear the warning controller",
                destroy.contains("warningRestartController.clear()"),
            )
            val confirm = block(seam.src, "private fun confirmWriteWithConsent(")
            assertTrue(
                "${seam.name} final confirm must still identity-gate the action",
                confirm.contains("warningRestartController.isLive(actionId)"),
            )
            assertTrue(
                "${seam.name} final confirm must still revalidate consent",
                confirm.contains("revalidatePolicyWriteConsent("),
            )
        }
    }
}
