package com.galaxydual.hook

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase F4b-P2 (issue #6): JVM unit tests for the hook-active marker.
// Pure build/parse plus temp-file per-scope writes -- no device, no
// framework stubs (HookMarker is framework-free like WhitelistPolicy).
//
// Phase 5 (issue #6): each hook process writes ONLY its own per-scope
// file; readers union all marker files (per-scope primary/fallback +
// legacy single-files). No read-merge-write, no lock, no cross-process
// primitive; legacy files stay read-compat forever.
class HookMarkerTest {

    // Temp "six production paths": per-scope primary x2, per-scope fallback
    // x2, legacy single-file x2 (names mirror the production basenames).
    private fun sixTempFiles(dir: File): List<File> = listOf(
        File(dir, "galaxydual_hook.active.system_server"),
        File(dir, "galaxydual_hook.active.daagent"),
        File(dir, "legacy-tmp.galaxydual_hook.active.system_server"),
        File(dir, "legacy-tmp.galaxydual_hook.active.daagent"),
        File(dir, "galaxydual_hook.active"),
        File(dir, "legacy-tmp.galaxydual_hook.active"),
    )

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

    // H12: unknown scope tokens are dropped (fail-closed — never treated as
    // an active scope) and counted in unknownScopeDrops. Known tokens in the
    // same line still survive.
    @Test fun parse_dropsUnknownScopeTokensAndCountsThem() {
        val before = HookMarker.unknownScopeDrops.get()
        val logs = mutableListOf<String>()
        val raw = "scope=system_server,evil_scope,also_unknown,daagent\n"
        assertEquals(setOf("system_server", "daagent"), HookMarker.parseScopes(raw) { logs.add(it) })
        assertEquals(before + 2L, HookMarker.unknownScopeDrops.get())
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("unknown scope"))
    }

    // CR-O2/H12 parity: markActive validates `want` against the same
    // allowlist as on-disk tokens — an unknown scope is never persisted
    // (fail-closed), is counted on the drop channel, and no file is
    // written. There is no merge anymore, so even a pre-existing valid
    // marker is left untouched.
    @Test fun markActive_unknownScopeNotPersistedAndCounted() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val file = File(dir, "galaxydual_hook.active.system_server")
            val before = HookMarker.unknownScopeDrops.get()
            val logs = mutableListOf<String>()
            assertFalse(HookMarker.markActive("system!!!", file, 1000L, logs::add))
            assertFalse(file.exists())
            assertEquals(before + 1L, HookMarker.unknownScopeDrops.get())
            assertTrue(logs.any { it.contains("unknown scope") })
            // A pre-existing valid marker survives untouched; only the
            // bogus want is rejected (no read-merge-write to corrupt it).
            file.writeText(HookMarker.buildContent(setOf("system_server"), 1000L))
            val dropsBefore2 = HookMarker.unknownScopeDrops.get()
            assertFalse(HookMarker.markActive("system!!!", file, 2000L, logs::add))
            val raw = file.readText()
            assertEquals(setOf("system_server"), HookMarker.parseScopes(raw))
            assertEquals(1000L, HookMarker.parseTimeMs(raw))
            assertFalse(raw.contains("system!!!"))
            assertEquals(dropsBefore2 + 1L, HookMarker.unknownScopeDrops.get())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Phase 5: a freshly written per-scope file contains EXACTLY the one
    // scope + timeMs line — never a union with anything else.
    @Test fun markActive_writesExactlyItsOwnScope() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val ss = File(dir, "galaxydual_hook.active.system_server")
            assertTrue(HookMarker.markActive("system_server", ss, 1000L, {}))
            assertEquals(
                HookMarker.buildContent(setOf("system_server"), 1000L),
                ss.readText(),
            )
            assertEquals(setOf("system_server"), HookMarker.parseScopes(ss.readText()))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Phase 5: two sequential markActive calls for the two scopes land in
    // their OWN files; readScopes unions the six files and sees both
    // scopes; the system_server file NEVER contains daagent.
    @Test fun markActive_twoScopesUnionAcrossPerScopeFiles() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val files = sixTempFiles(dir)
            val (ss, daagent) = files
            assertTrue(HookMarker.markActive("system_server", ss, 1000L, {}))
            assertTrue(HookMarker.markActive("daagent", daagent, 2000L, {}))
            assertFalse(ss.readText().contains("daagent"))
            assertFalse(daagent.readText().contains("system_server"))
            assertEquals(setOf("system_server", "daagent"), HookMarker.readScopes(files))
        } finally {
            dir.deleteRecursively()
        }
    }

    // A blank scope writes nothing and returns false: never claim active
    // without evidence.
    @Test fun markActive_blankScopeWithNoHistoryWritesNothing() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val file = File(dir, "galaxydual_hook.active.system_server")
            assertFalse(HookMarker.markActive("   ", file, 1000L, {}))
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Unreadable/missing file reads as no scopes (app shows "not detected").
    @Test fun readScopes_missingFileIsEmpty() {
        val missing = File("/definitely/not/a/real/path/galaxydual_hook.active")
        assertTrue(HookMarker.readScopes(listOf(missing)).isEmpty())
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            File(dir, "junk.active").writeText("not a marker\n")
            assertTrue(HookMarker.readScopes(listOf(File(dir, "junk.active"))).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Path constants (real-device EACCES evidence): the primary per-scope
    // markers live under /data/system (system_data_file, writable by
    // system_server); the legacy single-files are read-compat only.
    @Test fun paths_perScopeAndLegacyCompat() {
        assertEquals("/data/system/galaxydual_hook.active", HookMarker.MARKER_PATH)
        assertEquals("/data/local/tmp/galaxydual_hook.active", HookMarker.LEGACY_MARKER_PATH)
        assertEquals("/data/system/galaxydual_hook.active.system_server", HookMarker.MARKER_PATH_SYSTEM_SERVER)
        assertEquals("/data/system/galaxydual_hook.active.daagent", HookMarker.MARKER_PATH_DAAGENT)
        assertEquals("/data/local/tmp/galaxydual_hook.active.system_server", HookMarker.LEGACY_MARKER_PATH_SYSTEM_SERVER)
        assertEquals("/data/local/tmp/galaxydual_hook.active.daagent", HookMarker.LEGACY_MARKER_PATH_DAAGENT)
        // Writer path resolution: allowlist-checked, unknown -> null.
        assertEquals(HookMarker.MARKER_PATH_SYSTEM_SERVER, HookMarker.markerPathFor("system_server"))
        assertEquals(HookMarker.MARKER_PATH_DAAGENT, HookMarker.markerPathFor("daagent"))
        assertEquals(HookMarker.LEGACY_MARKER_PATH_SYSTEM_SERVER, HookMarker.fallbackMarkerPathFor("system_server"))
        assertEquals(HookMarker.LEGACY_MARKER_PATH_DAAGENT, HookMarker.fallbackMarkerPathFor("daagent"))
        assertNull(HookMarker.markerPathFor("evil_scope"))
        assertNull(HookMarker.markerPathFor(""))
        assertNull(HookMarker.fallbackMarkerPathFor("evil_scope"))
        assertNull(HookMarker.fallbackMarkerPathFor("  "))
        // The writer NEVER resolves the legacy single-file paths.
        assertFalse(HookMarker.MARKER_PATH == HookMarker.markerPathFor("system_server"))
        assertFalse(HookMarker.LEGACY_MARKER_PATH == HookMarker.fallbackMarkerPathFor("daagent"))
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
    // directory, so writeText throws) the content still lands in the
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

    // Phase 5: the writer NEVER writes the legacy single-file markers: a
    // full two-scope write sequence in a temp dir leaves the legacy files
    // absent.
    @Test fun markActive_neverWritesLegacySingleFile() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val files = sixTempFiles(dir)
            assertTrue(HookMarker.markActive("system_server", files[0], 1000L, {}, files[2]))
            assertTrue(HookMarker.markActive("daagent", files[1], 2000L, {}, files[3]))
            assertFalse(files[4].exists())
            assertFalse(files[5].exists())
            // Directory scan: exactly the two per-scope primaries exist — no
            // legacy single-file and no orphan tmp sibling at all.
            assertEquals(
                setOf(files[0].name, files[1].name),
                dir.listFiles().orEmpty().map { it.name }.toSet(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // Byte-exact writer format pin: the app<->hook on-disk contract is
    // `scope=<scope>\ntimeMs=<ms>\n` (two lines). A format drift (e.g. a
    // renamed key) must fail here even though buildContent round-trips.
    @Test fun markActive_writesExactlyScopeAndTimeMsBytes() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val marker = File(dir, "marker.active")
            assertTrue(HookMarker.markActive("system_server", marker, 1234L, {}))
            assertEquals("scope=system_server\ntimeMs=1234\n", marker.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Both paths unwritable (primary and fallback are directories): the write
    // fails for BOTH, markActive returns false and never throws.
    @Test fun markActive_bothPathsUnwritableReturnsFalse() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val primary = File(dir, "primaryDir").apply { mkdir() }
            val fallback = File(dir, "fallbackDir").apply { mkdir() }
            assertFalse(HookMarker.markActive("system_server", primary, 1000L, {}, fallback))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun readScopes_unionsAllFiles() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val files = sixTempFiles(dir)
            val (ss, daagent) = files
            ss.writeText(HookMarker.buildContent(setOf("system_server"), 1000L))
            daagent.writeText(HookMarker.buildContent(setOf("daagent"), 2000L))
            assertEquals(setOf("system_server", "daagent"), HookMarker.readScopes(files))
            // One side missing still yields the other side's scopes.
            assertEquals(setOf("system_server"), HookMarker.readScopes(listOf(ss, File(dir, "absent.active"))))
        } finally {
            dir.deleteRecursively()
        }
    }

    // A garbage per-scope file is ignored while the peer scope still reads.
    @Test fun readScopes_garbagePerScopeFileIgnoredPeerSurvives() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val files = sixTempFiles(dir)
            val (ss, daagent) = files
            ss.writeText("this is not a marker\nscope=evil_scope\n")
            daagent.writeText(HookMarker.buildContent(setOf("daagent"), 2000L))
            assertEquals(setOf("daagent"), HookMarker.readScopes(files))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Legacy back-compat (read-compat forever): a legacy single-file seeded
    // with BOTH scopes still surfaces both, and a garbage legacy file is
    // ignored while per-scope files still read.
    @Test fun readScopes_legacySingleFileBackCompat() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val files = sixTempFiles(dir)
            val legacyPrimary = files[4]
            legacyPrimary.writeText(HookMarker.buildContent(setOf("system_server", "daagent"), 1500L))
            assertEquals(setOf("system_server", "daagent"), HookMarker.readScopes(files))
            // Garbage legacy files do not break the union.
            legacyPrimary.writeText("garbage\n")
            files[5].writeText("also garbage\ntimeMs=abc\n")
            assertTrue(HookMarker.readScopes(files).isEmpty())
            // ... while a valid per-scope file next to garbage legacy still reads.
            files[0].writeText(HookMarker.buildContent(setOf("system_server"), 3000L))
            assertEquals(setOf("system_server"), HookMarker.readScopes(files))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Phase 2(b): parseScopes line-cap: a 100-line flood (garbage first
    // line, then scope=daagent lines, then overflow lines beyond the 16-line
    // cap) must still recover daagent from within the first 16 lines, must
    // not crash, and must never scan the overflow lines (pinned via the
    // unknown-scope drop counter staying flat).
    @Test fun parseScopes_lineCapStillRecoversScopeAndIgnoresOverflow() {
        val raw = buildString {
            append("garbage flood line\n")           // line 1
            repeat(15) { append("scope=daagent\n") }  // lines 2..16 (cap)
            repeat(84) { append("scope=evil_overflow_token\n") } // lines 17..100
        }
        val before = HookMarker.unknownScopeDrops.get()
        val scopes = HookMarker.parseScopes(raw)
        assertEquals(setOf("daagent"), scopes)
        assertEquals(before, HookMarker.unknownScopeDrops.get())
    }

    // Finding P3 (Phase 3): parseTimeMs line-cap, mirroring parseScopes — a
    // 100-line flood with timeMs only past the 16-line cap must yield null
    // (the overflow line is never scanned); a timeMs inside the cap still
    // parses.
    @Test fun parseTimeMs_lineCapDropsOverflow() {
        val beyondCap = buildString {
            repeat(16) { append("junk flood line\n") }          // lines 1..16 (cap)
            append("timeMs=1710000000000\n")                    // line 17: never scanned
        }
        assertNull(HookMarker.parseTimeMs(beyondCap))
        val withinCap = buildString {
            repeat(15) { append("junk flood line\n") }          // lines 1..15
            append("timeMs=1710000000000\n")                    // line 16: still scanned
        }
        assertEquals(1710000000000L, HookMarker.parseTimeMs(withinCap))
    }

    // Phase 2(a): a ~256 KB marker file of junk bytes is never buffered
    // whole: readScopes returns emptySet() (and the JVM would additionally
    // throw OutOfMemoryError if the read were unbounded — deliberately not
    // asserted).
    @Test fun readScopes_giantFileIsBoundedAndReadsEmpty() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val file = File(dir, "galaxydual_hook.active.system_server")
            file.writeText("junk line with no marker keys 0123456789\n".repeat(6 * 1024))
            assertTrue("fixture should be ~256 KB, was ${file.length()}", file.length() > 200 * 1024)
            assertEquals(emptySet<String>(), HookMarker.readScopes(listOf(file)))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Phase 2(c): symlink-truncation regression. The evil fallback path is a
    // pre-planted symlink to a victim file (the /data/local/tmp 1777 attack
    // shape). The write must be REFUSED (tmp alloc fails here, mirroring the
    // on-device sticky-bit EPERM that makes renameTo() fail) — never fall
    // through to a direct write that follows the symlink and TRUNCATES the
    // victim with system_server/daagent privileges.
    // Note: with a parent directory still writable, a plain symlink lets
    // rename(2) replace the symlink itself without touching the target, so
    // the tmp path must be made to fail UID-independently — here by an
    // overlong tmp filename (base name 247 chars + ".tmp-<nanoTime>-<tid>"
    // exceeds NAME_MAX 255). The parent stays a directory; the spec's
    // "parent is a regular FILE" variant is pinned separately below.
    @Test fun markActive_symlinkFallbackTruncationIsRefused() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val victim = File(dir, "victim.txt").apply { writeText("IMPORTANT") }
            val evil = File(dir, "e".repeat(240) + ".active") // 247 chars, legal name
            Files.createSymbolicLink(evil.toPath(), victim.toPath())
            val unwritablePrimary = File(dir, "primaryDir").apply { mkdir() }
            assertFalse(HookMarker.markActive("daagent", unwritablePrimary, 1000L, {}, evil))
            assertEquals("IMPORTANT", victim.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Phase 2(c), spec-literal variant: the fallback path lives UNDER a
    // regular file (its parent is a regular FILE, not a directory), so tmp
    // creation and rename are impossible. markActive must return false and
    // the victim must remain untouched. (This variant also failed closed
    // before the direct-write removal; kept as defense-in-depth for the
    // rename-refusal contract — the bypass it closes is pinned by the
    // symlink test above.)
    @Test fun markActive_parentRegularFileFallbackRefusedVictimIntact() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val victim = File(dir, "victim.txt").apply { writeText("IMPORTANT") }
            val blocker = File(dir, "blocker").apply { writeText("not a directory") }
            val evil = File(blocker, "evil.active")
            val unwritablePrimary = File(dir, "primaryDir").apply { mkdir() }
            assertFalse(HookMarker.markActive("daagent", unwritablePrimary, 1000L, {}, evil))
            assertEquals("IMPORTANT", victim.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Concurrent writers of the two per-scope files never clobber each
    // other: distinct files, no shared state. (In-process stand-in for the
    // cross-process case -- not a real two-process test; proves the union
    // converges with no locking.)
    @Test fun markActive_concurrentWritersBothSurvive() {
        val dir = Files.createTempDirectory("hookmarker").toFile()
        try {
            val files = sixTempFiles(dir)
            val (ss, daagent) = files
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val results = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
            val t1 = Thread {
                start.await()
                results[1] = HookMarker.markActive("system_server", ss, 1000L, {})
                done.countDown()
            }
            val t2 = Thread {
                start.await()
                results[2] = HookMarker.markActive("daagent", daagent, 2000L, {})
                done.countDown()
            }
            t1.start(); t2.start()
            start.countDown()
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            t1.join(1000); t2.join(1000)
            assertEquals(true, results[1])
            assertEquals(true, results[2])
            assertEquals(setOf("system_server", "daagent"), HookMarker.readScopes(files))
        } finally {
            dir.deleteRecursively()
        }
    }
}
