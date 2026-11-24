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

    private fun block(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        // Skip default-value lambdas in the parameter list (e.g. `= {}`) and
        // start at the brace that opens the function BODY after `)`.
        var paren = 0
        var body = -1
        for (i in start until src.length) {
            when (src[i]) {
                '(' -> paren++
                ')' -> {
                    paren--
                    if (paren == 0) {
                        body = src.indexOf('{', i + 1)
                        break
                    }
                }
            }
        }
        assertTrue("$signature must open a block", body >= 0)
        val brace = body
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
            assertTrue("$name request*Warning must arm only via the accept seam", requestBody.contains("tryAcceptWarning("))
            assertTrue(
                "$name accept seam must be gated by the re-entry guard",
                requestBody.contains("consentResetAllowedForNewWarning(pendingClearConfirm)"),
            )
        }
    }

    @Test
    fun warningRestart_postsThroughOwnedHandlerWithHostGuard() {
        for ((name, src, handler) in listOf(
            Seam("MainActivity", main, "mainHandler.post", ""),
            Seam("PolicyActivity", editor, "handler.post", ""),
        )) {
            val restart = block(src, "private fun scheduleWarningRestart()")
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
            val installs = indicesOf(requestBody, "warningRestartController.install(")
            assertEquals("$name must install at both accept seams (Unknown + probe)", 2, installs.size)
            for (idx in installs) {
                val before = requestBody.substring(maxOf(0, idx - 420), idx)
                assertTrue(
                    "$name install must follow the accepted-warning guard",
                    before.contains(acceptGuard),
                )
            }
            assertFalse(
                "$name must not invoke an unbound pending restart callback",
                requestBody.contains("pendingWarningRestart?.invoke()"),
            )
        }

        assertEquals(
            "Main write installs a candidate-keyed identity",
            2,
            indicesOf(block(main, "private fun requestPreWriteWarning("), "candidateKey = candidate.sorted().joinToString()").size,
        )
        assertEquals(
            "Main clear installs a CLEAR identity",
            2,
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
            assertTrue(
                "$name Retry sites must be identity-bound",
                src.contains("warningRestartController.retryRequestId()") && src.contains("warningRestartController.consumeRetry("),
            )
            assertTrue(
                "$name onDestroy must clear the controller and its posted Runnable",
                src.contains("warningRestartController.clear()") && src.contains("pendingWarningRestartRunnable?.let {"),
            )
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
            assertEquals(
                "$name both accept seams must pass resetBudget = fromUserTap",
                2,
                indicesOf(block(src, request), "resetBudget = fromUserTap").size,
            )
        }
        // The INSTALLED callback serves BOTH auto-restart delivery and Retry
        // settles; neither may ever be mistaken for a fresh user entry tap.
        assertEquals(2, indicesOf(main, "startWriteWarning(fromUserTap = false)").size)
        assertEquals(2, indicesOf(main, "startClearWarning(fromUserTap = false)").size)
        assertEquals(2, indicesOf(editor, "startEditorWriteWarning(HashSet(ticks), fromUserTap = false)").size)
        // Genuine click handlers are the only true provenance sources.
        assertEquals(2, indicesOf(main, "startWriteWarning(fromUserTap = true)").size)
        assertEquals(1, indicesOf(main, "startClearWarning(fromUserTap = true)").size)
        assertEquals(1, indicesOf(editor, "startEditorWriteWarning(candidate, fromUserTap = true)").size)
    }
}
