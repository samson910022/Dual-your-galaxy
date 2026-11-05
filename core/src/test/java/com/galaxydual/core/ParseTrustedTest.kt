// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// PR-2 stage 1 (issue #22): trusted tri-state parse for `pm list packages`.
// Additive only: covers parsePackagesTrusted without touching the existing
// parsePackages/isSuccess/verify behavior. Pure Kotlin, no device required.
class ParseTrustedTest {
    // exit 0 + dirty out text -> Unknown (not TrustedEmpty).
    @Test
    fun dirtyOut_withExitZero_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "Failure [INSTALL_FAILED_INVALID_APK: bad]",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
    }

    // exit 0 + dirty err text -> Unknown (SecurityException / Error paths).
    @Test
    fun dirtyErr_withExitZero_isUnknown() {
        val security = PmOutputParser.parsePackagesTrusted(
            out = "",
            err = "java.lang.SecurityException: Neither user 2000 nor current process has permission",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $security", security is TrustedList.Unknown)

        val error = PmOutputParser.parsePackagesTrusted(
            out = "",
            err = "Error: Unknown package: com.example.chat",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $error", error is TrustedList.Unknown)
    }

    // Non-zero exit -> Unknown even when the text looks clean (upper layer
    // owns retry/backoff; the pure parser only reports no-knowledge).
    @Test
    fun nonZeroExit_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "package:com.example.chat",
            err = "",
            exit = 1,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
    }

    // Clean empty listing (no package: lines, no error text) -> TrustedEmpty.
    @Test
    fun cleanEmpty_isTrustedEmpty() {
        val empty = PmOutputParser.parsePackagesTrusted("", "", 0, false)
        assertEquals(TrustedList.TrustedEmpty, empty)

        val blanks = PmOutputParser.parsePackagesTrusted("\n  \n", "", 0, false)
        assertEquals(TrustedList.TrustedEmpty, blanks)
    }

    // Legal listing -> TrustedFull with exact package names.
    @Test
    fun legalList_isTrustedFull() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "package:com.example.chat\npackage:com.example.other",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertEquals(
            TrustedList.TrustedFull(listOf("com.example.chat", "com.example.other")),
            res,
        )
    }

    // Issue #8 regression: package names containing failure/error/exception
    // substrings must NOT be misjudged as errors (package: lines are exempt
    // from the error-keyword match, same as isSuccess).
    @Test
    fun failureNamedPackages_areNotMisjudged() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "package:com.failure.app\npackage:com.example.exceptionapp\npackage:com.error.app",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertEquals(
            TrustedList.TrustedFull(
                listOf(
                    "com.failure.app",
                    "com.example.exceptionapp",
                    "com.error.app",
                ),
            ),
            res,
        )
    }

    // Truncated capture -> Unknown even when the visible slice looks clean.
    // Covers both the explicit flag and the legacy "[truncated]" marker text
    // (callers that still build CmdResult without the flag).
    @Test
    fun truncated_isUnknown() {
        val flagged = PmOutputParser.parsePackagesTrusted(
            out = "package:com.example.chat",
            err = "",
            exit = 0,
            truncated = true,
        )
        assertTrue("expected Unknown, got $flagged", flagged is TrustedList.Unknown)

        val flaggedEmpty = PmOutputParser.parsePackagesTrusted(
            out = "",
            err = "",
            exit = 0,
            truncated = true,
        )
        assertTrue("expected Unknown, got $flaggedEmpty", flaggedEmpty is TrustedList.Unknown)

        val marker = PmOutputParser.parsePackagesTrusted(
            out = "package:com.example.chat\n[truncated]",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $marker", marker is TrustedList.Unknown)
    }

    // CmdResult overload forwards the flag (additive convenience, same rules).
    @Test
    fun cmdResultOverload_forwardsTruncated() {
        val res = PmOutputParser.parsePackagesTrusted(
            CmdResult(exit = 0, out = "package:com.example.chat", err = "", truncated = true),
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)

        val clean = PmOutputParser.parsePackagesTrusted(
            CmdResult(exit = 0, out = "package:com.example.chat", err = ""),
        )
        assertEquals(TrustedList.TrustedFull(listOf("com.example.chat")), clean)
    }
}
