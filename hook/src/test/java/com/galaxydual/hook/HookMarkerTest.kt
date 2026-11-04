package com.galaxydual.hook

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase F4b-P2 (issue #6): JVM unit tests for the hook-active marker.
// Pure build/parse plus temp-file read-merge-write -- no device, no
// framework stubs (HookMarker is framework-free like WhitelistPolicy).
class HookMarkerTest {

    // buildContent renders sorted scopes + timestamp; parseScopes recovers
    // the set and parseTimeMs the timestamp.
    @Test fun build_parseRoundTrip() {
        val raw = HookMarker.buildContent(setOf("daagent", "system_server"), 1710000000000L)
        assertEquals(setOf("system_server", "daagent"), HookMarker.parseScopes(raw))
        assertEquals(1710000000000L, HookMarker.parseTimeMs(raw))
    }

    // Null/blank/garbage never parses as active: the app maps empty scopes
    // to "not detected", never to a fake active state.
    @Test fun parse_nullBlankGarbageYieldEmpty() {
        assertTrue(HookMarker.parseScopes(null).isEmpty())
        assertTrue(HookMarker.parseScopes("").isEmpty())
        assertTrue(HookMarker.parseScopes("   \n  ").isEmpty())
        assertTrue(HookMarker.parseScopes("garbage\ntimeMs=abc\nscope=\n").isEmpty())
        assertNull(HookMarker.parseTimeMs(null))
        assertNull(HookMarker.parseTimeMs("scope=system_server\n"))
        assertNull(HookMarker.parseTimeMs("timeMs=not-a-number\n"))
    }

    // Unknown lines are ignored; scope tokens are trimmed and blanks dropped.
    @Test fun parse_ignoresUnknownLinesAndTrims() {
        val raw = "junk line\nscope= system_server ,, daagent \ntimeMs= 42 \nmore=junk\n"
        assertEquals(setOf("system_server", "daagent"), HookMarker.parseScopes(raw))
        assertEquals(42L, HookMarker.parseTimeMs(raw))
    }

    // markActive merges: a second scope joins the first instead of
    // overwriting it (both hook processes share one marker file).
    @Test fun markActive_mergesScopesAcrossWrites() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val file = File(dir, "galaxydual_hook.active")
            val logs = mutableListOf<String>()
            assertTrue(HookMarker.markActive("system_server", file, 1000L, logs::add))
            assertTrue(HookMarker.markActive("daagent", file, 2000L, logs::add))
            val raw = file.readText()
            assertEquals(setOf("system_server", "daagent"), HookMarker.parseScopes(raw))
            // Latest write stamps the time.
            assertEquals(2000L, HookMarker.parseTimeMs(raw))
        } finally {
            dir.deleteRecursively()
        }
    }

    // A blank scope with no prior file records nothing and returns false:
    // never claim active without evidence.
    @Test fun markActive_blankScopeWithNoHistoryWritesNothing() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val file = File(dir, "galaxydual_hook.active")
            assertFalse(HookMarker.markActive("   ", file, 1000L, {}))
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Unreadable/missing file reads as no scopes (app shows "not detected").
    @Test fun readScopes_missingFileIsEmpty() {
        val missing = File("/definitely/not/a/real/path/galaxydual_hook.active")
        assertTrue(HookMarker.readScopes(missing).isEmpty())
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            File(dir, "junk.active").writeText("not a marker\n")
            assertTrue(HookMarker.readScopes(File(dir, "junk.active")).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Path constants (real-device EACCES evidence): the primary marker lives
    // under /data/system (system_data_file, writable by system_server); the
    // pre-evidence /data/local/tmp location is the fallback only.
    @Test fun paths_primaryIsSystemAndLegacyIsTmp() {
        assertEquals("/data/system/galaxydual_hook.active", HookMarker.MARKER_PATH)
        assertEquals("/data/local/tmp/galaxydual_hook.active", HookMarker.LEGACY_MARKER_PATH)
    }

    // Fallback order: when the primary is writable the fallback file is left
    // untouched (primary-first, fallback only on primary failure).
    @Test fun markActive_prefersPrimaryAndLeavesFallbackUntouched() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val primary = File(dir, "primary.active")
            val fallback = File(dir, "fallback.active")
            assertTrue(HookMarker.markActive("system_server", primary, 1000L, {}, fallback))
            assertEquals(setOf("system_server"), HookMarker.parseScopes(primary.readText()))
            assertFalse(fallback.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Fallback order: when the primary write fails (here the "primary" is a
    // directory, so writeText throws) the merged content still lands in the
    // fallback file and markActive reports success.
    @Test fun markActive_fallsBackWhenPrimaryUnwritable() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val unwritablePrimary = File(dir, "primaryDir").apply { mkdir() }
            val fallback = File(dir, "fallback.active")
            val logs = mutableListOf<String>()
            assertTrue(HookMarker.markActive("daagent", unwritablePrimary, 1000L, logs::add, fallback))
            assertEquals(setOf("daagent"), HookMarker.parseScopes(fallback.readText()))
            assertTrue(logs.any { it.contains("fallback") })
        } finally {
            dir.deleteRecursively()
        }
    }

    // Merge history spans both files: a scope previously recorded via the
    // fallback path is preserved when the next write lands on the primary.
    @Test fun markActive_mergesHistoryFromBothFiles() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val primary = File(dir, "primary.active")
            val fallback = File(dir, "fallback.active")
            fallback.writeText(HookMarker.buildContent(setOf("daagent"), 1000L))
            assertTrue(HookMarker.markActive("system_server", primary, 2000L, {}, fallback))
            assertEquals(setOf("system_server", "daagent"), HookMarker.parseScopes(primary.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Reads UNION both files: scopes recorded via either write path are
    // honored together.
    @Test fun readScopes_unionsPrimaryAndFallback() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val primary = File(dir, "primary.active")
            val fallback = File(dir, "fallback.active")
            primary.writeText(HookMarker.buildContent(setOf("system_server"), 1000L))
            fallback.writeText(HookMarker.buildContent(setOf("daagent"), 2000L))
            assertEquals(setOf("system_server", "daagent"), HookMarker.readScopes(primary, fallback))
            // One side missing still yields the other side's scopes.
            assertEquals(setOf("system_server"), HookMarker.readScopes(primary, File(dir, "absent.active")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
