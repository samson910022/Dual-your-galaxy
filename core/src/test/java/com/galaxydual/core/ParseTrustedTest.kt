// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// PR-2 (issue #22): trusted tri-state parse for `pm list packages`.
// Covers parsePackagesTrusted (consumed since stage 2 by
// verifyListProbe/listProbe); parsePackages/isSuccess keep exact behavior.
// Pure Kotlin, no device required.
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
    // Covers both the explicit flag and the legacy TRUNCATION_MARKER text
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
            out = "package:com.example.chat\n$TRUNCATION_MARKER",
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

    // P22 fix M1: unknown stdout format with exit 0 is Unknown, never
    // TrustedEmpty (fail closed: usage/help text proves nothing about absence).
    @Test
    fun unknownFormat_withExitZero_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "usage: pm list packages [--user N]",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        assertTrue(
            "reason must name format, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.contains("unexpected listing format"),
        )
    }

    // P22 fix M2: malformed package: payload is Unknown on the trusted path
    // only (parsePackages() itself stays tolerant). Payload must match
    // ^[A-Za-z0-9._-]+$ (same charset as ShellEscaper.SAFE_TOKEN).
    @Test
    fun malformedPackageLine_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "package: unknown package",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        assertTrue(
            "reason must name malformed line, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.contains("malformed package line"),
        )

        // Non-error-semantic malformed payload (slash) is also Unknown via M2.
        val slash = PmOutputParser.parsePackagesTrusted(
            out = "package:com.foo/bar",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $slash", slash is TrustedList.Unknown)
        assertTrue(
            "reason must name malformed line, got: \"${(slash as TrustedList.Unknown).reason}\"",
            slash.reason.contains("malformed"),
        )
    }

    // P22 fix: ALREADY_EXISTS has NO exception in listing context (it is
    // install-only idempotent-success); any Failure line makes the snapshot
    // untrusted even with exit 0.
    @Test
    fun alreadyExists_inListing_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "Failure [INSTALL_FAILED_ALREADY_EXISTS: existing package]",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        assertTrue(
            "reason must name error semantics, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.contains("error-semantic"),
        )
    }

    // P22 fix M1 err side: a non-blank err stream with zero packages is a
    // dirty channel -> Unknown (fail closed), even when no error keyword
    // matches. Also covers the err-side TRUNCATION_MARKER.
    @Test
    fun dirtyErrWithoutErrorKeyword_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "",
            err = "some warning marker",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        assertTrue(
            "reason must name format, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.contains("unexpected listing format"),
        )

        val marker = PmOutputParser.parsePackagesTrusted(
            out = "",
            err = "partial output\n$TRUNCATION_MARKER",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $marker", marker is TrustedList.Unknown)
        assertTrue(
            "marker must read as truncation, got: \"${(marker as TrustedList.Unknown).reason}\"",
            marker.reason.contains("truncat", ignoreCase = true),
        )
    }

    // Truncated has priority over non-zero exit (step 1 before step 2).
    @Test
    fun truncated_hasPriorityOverNonZeroExit() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "package:com.example.chat",
            err = "",
            exit = 1,
            truncated = true,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        assertTrue(
            "truncated must win, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.startsWith("truncated"),
        )
        assertTrue(
            "truncated + non-zero must keep exit, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.contains("exit=1"),
        )
    }

    // R1: non-zero detail keeps the first stderr (else stdout) line; empty
    // streams keep the bare exit code.
    @Test
    fun nonZeroExit_detailKeepsFirstStderrLine() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "",
            err = "error: closed\nsecond line",
            exit = 1,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        val detail = (res as TrustedList.Unknown).reason
        assertTrue("detail must carry exit code, got: \"$detail\"", detail.contains("exit=1"))
        assertTrue("detail must carry stderr hint, got: \"$detail\"", detail.contains("error: closed"))

        val fromOut = PmOutputParser.parsePackagesTrusted(
            out = "usage failed",
            err = "",
            exit = 2,
            truncated = false,
        )
        assertTrue(
            "detail must fall back to stdout, got: \"${(fromOut as TrustedList.Unknown).reason}\"",
            fromOut.reason.contains("usage failed"),
        )
        assertTrue(
            "detail must carry exit code, got: \"${fromOut.reason}\"",
            fromOut.reason.contains("exit=2"),
        )

        val bare = PmOutputParser.parsePackagesTrusted(
            out = "",
            err = "",
            exit = 1,
            truncated = false,
        )
        assertEquals("verify failed (exit=1)", (bare as TrustedList.Unknown).reason)
    }

    // Pin: bare "package:" with an empty payload is Unknown (malformed),
    // never TrustedEmpty.
    @Test
    fun emptyPackagePayload_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "package:",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        assertTrue(
            "reason must name malformed line, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.contains("malformed"),
        )
    }

    // Pin: a lone "Success" line with zero packages is Unknown
    // (unexpected format), never TrustedEmpty.
    @Test
    fun loneSuccessLine_isUnknown() {
        val res = PmOutputParser.parsePackagesTrusted(
            out = "Success",
            err = "",
            exit = 0,
            truncated = false,
        )
        assertTrue("expected Unknown, got $res", res is TrustedList.Unknown)
        assertTrue(
            "reason must name format, got: \"${(res as TrustedList.Unknown).reason}\"",
            res.reason.contains("unexpected listing format"),
        )
    }
}
