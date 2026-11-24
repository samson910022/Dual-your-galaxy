// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #77 + P2 Handler-leak source-shape lock (JVM, no Robolectric), mirroring
// RiskDisclosureSourceShapeTest: read the production .kt text and verify that
// (a) the retry generation is armed only at the accepted-warning seams (never
// at start*Warning, which stale auto-restarts also pass through), and
// (b) warning restarts are posted through an Activity-owned Handler with a
// hostAlive guard, and that Handler's callbacks are removed on destroy.
package com.galaxydual.plus

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
}
