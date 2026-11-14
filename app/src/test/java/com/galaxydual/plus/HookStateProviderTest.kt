// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase F4b-P2 (issue #6): JVM unit tests for the app-side hook signal and
// the LSPosed read-only mapping -- marker parsing (active/scopes vs inactive),
// display text, the placeholder gateway honesty (NoOp privilege, never
// executable), and AUTO never resolving to the hook placeholder. Pure
// Kotlin/JUnit4 with temp files only; no device, no Android runtime (the
// ViewModel itself is not constructed here: viewModelScope needs a device
// Main dispatcher, so only its pure collaborators are covered).
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.Privilege
import com.galaxydual.hook.HookMarker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookStateProviderTest {

    // Temp "six production paths" (existence-only): per-scope primary x2,
    // per-scope fallback x2, legacy single-file x2. Direct reads use these
    // injected files; gateway `cat`s always target the production constants.
    private fun sixTempFiles(dir: java.io.File): List<java.io.File> = listOf(
        java.io.File(dir, "galaxydual_hook.active.system_server"),
        java.io.File(dir, "galaxydual_hook.active.daagent"),
        java.io.File(dir, "legacytmp.galaxydual_hook.active.system_server"),
        java.io.File(dir, "legacytmp.galaxydual_hook.active.daagent"),
        java.io.File(dir, "galaxydual_hook.active"),
        java.io.File(dir, "legacytmp.galaxydual_hook.active"),
    )

    // Null/blank/garbage marker text reads as inactive, never fake-active.
    @Test fun parse_nullBlankGarbageIsInactive() {
        assertFalse(parseHookState(null).active)
        assertFalse(parseHookState("").active)
        assertFalse(parseHookState("   ").active)
        val garbage = parseHookState("junk\ntimeMs=abc\n")
        assertFalse(garbage.active)
        assertTrue(garbage.scopes.isEmpty())
    }

    // A well-formed marker parses to active with its scopes.
    @Test fun parse_scopesYieldActive() {
        val raw = HookMarker.buildContent(setOf("system_server", "daagent"), 123L)
        val state = parseHookState(raw)
        assertTrue(state.active)
        assertEquals(setOf("system_server", "daagent"), state.scopes)
    }

    // Single-scope markers (one hook process wrote so far) stay active.
    @Test fun parse_singleScopeIsActive() {
        val state = parseHookState(HookMarker.buildContent(setOf("daagent"), 1L))
        assertTrue(state.active)
        assertEquals(setOf("daagent"), state.scopes)
    }

    // Display text: ACTIVE is a historical marker (existence evidence only,
    // boot freshness unconfirmed), inactive shows not-detected.
    @Test fun displayText_matchesState() {
        assertEquals(
            "偵測到歷史 Hook 標記（daagent, system_server，僅存在性證據，未確認本次開機是否生效）",
            HookState(active = true, scopes = setOf("system_server", "daagent")).displayText(),
        )
        assertEquals("未偵測到", HookState(active = false).displayText())
    }

    // Missing file reads as inactive (fresh install / SELinux-denied /
    // hook never ran: all indistinguishable, all honestly "not detected").
    @Test fun read_missingFileIsInactive() {
        val missing = File("/definitely/not/a/real/path/galaxydual_hook.active")
        val state = readHookState(listOf(missing))
        assertFalse(state.active)
        assertTrue(state.scopes.isEmpty())
    }

    // Temp-file round-trip: what the hook writes, the app reads as active.
    @Test fun read_tempMarkerFileIsActive() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val file = File(dir, "galaxydual_hook.active")
            file.writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 7L))
            val state = readHookState(listOf(file))
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // LSPosed placeholder honesty: the gateway declares NONE privilege, so
    // the channel is read-only and can never be mistaken for executable.
    @Test fun lsposed_placeholderGatewayIsReadOnlyNoOp() {
        assertEquals(
            Privilege.NONE,
            buildGatewayForChannel(ResolvedChannel.LSPOSED_PLACEHOLDER, "com.example.test").privilege,
        )
        val asm = assembleChannel(ChannelPreference.LSPOSED, suPresent = true, shizukuAuthorized = true, packageName = "p")
        assertEquals(ResolvedChannel.LSPOSED_PLACEHOLDER, asm.channel)
        assertEquals(Privilege.NONE, asm.gateway.privilege)
    }

    // AUTO never resolves to the hook placeholder regardless of environment:
    // the marker is display-only evidence and must never auto-route privilege.
    @Test fun auto_neverResolvesToLsposedPlaceholder() {
        for (su in listOf(false, true)) {
            for (shizuku in listOf(false, true)) {
                val resolved = ChannelPreference.AUTO.resolveChannel(suPresent = su, shizukuAuthorized = shizuku)
                assertFalse("AUTO(su=$su, shizuku=$shizuku) must not select the hook channel", resolved == ResolvedChannel.LSPOSED_PLACEHOLDER)
            }
        }
    }

    // Six-path direct read: scopes from every marker file are unioned (a
    // scope recorded via any write path is honored).
    @Test fun read_sixPathUnion() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = sixTempFiles(dir)
            files[0].writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 1L))
            files[3].writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 2L))
            val state = readHookState(files)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER, HookMarker.SCOPE_DAAGENT), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // Legacy back-compat: a per-scope primary readable-but-empty plus a
    // legacy single-file containing daagent still surfaces daagent (blank
    // is empty evidence, not malformed; legacy files stay read-compat).
    @Test fun read_perScopeEmptyLegacyStillSurfacesScope() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = sixTempFiles(dir)
            files[0].writeText("")
            files[4].writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 4L))
            val state = readHookState(files)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_DAAGENT), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // Primary missing but fallback present still reads active (migration /
    // fallback-write history is not lost).
    @Test fun read_primaryMissingFallbackPresentIsActive() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = sixTempFiles(dir)
            files[2].writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 3L))
            val state = readHookState(files)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_DAAGENT), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // In-memory fake gateway for the `cat` fallback: serves canned outputs
    // per command and records how often it was executed. Never touches real
    // device files; direct-read files below are always temp/nonexistent.
    private class FakeCatGateway(
        override val privilege: Privilege,
        private val handler: (String) -> CmdResult,
    ) : CmdGateway {
        var calls = 0
        override suspend fun run(cmd: String): CmdResult {
            calls++
            return handler(cmd)
        }
    }

    private fun absentSix(dir: File): List<File> = sixTempFiles(dir).map { File(dir, it.name + ".absent") }

    // Gateway `cat` success maps to active: direct reads miss (temp paths
    // absent), the SHELL gateway serves the two per-scope `cat` paths of the
    // six, scopes union.
    @Test fun gateway_catSuccessMapsToActive() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = absentSix(dir)
            val gw = FakeCatGateway(Privilege.SHELL) { cmd ->
                when (cmd) {
                    "cat ${HookMarker.MARKER_PATH_SYSTEM_SERVER}" ->
                        CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L), "")
                    "cat ${HookMarker.MARKER_PATH_DAAGENT}" ->
                        CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 10L), "")
                    else -> CmdResult(1, "", "not found")
                }
            }
            val state = readHookStateWithGateway(gw, files)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER, HookMarker.SCOPE_DAAGENT), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // Gateway back-compat: per-scope paths cat-fail (e.g. pre-Phase-5
    // install, or a ROM where only the legacy file landed), and the legacy
    // single-file serves both scopes over the gateway -> still ACTIVE with
    // the union. Legacy markers stay read-compat forever.
    @Test fun gateway_legacySingleFileBackCompatMapsToActive() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = absentSix(dir)
            val gw = FakeCatGateway(Privilege.SHELL) { cmd ->
                when (cmd) {
                    "cat ${HookMarker.MARKER_PATH}" ->
                        CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER, HookMarker.SCOPE_DAAGENT), 11L), "")
                    else -> CmdResult(1, "", "not found")
                }
            }
            val state = readHookStateWithGateway(gw, files)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER, HookMarker.SCOPE_DAAGENT), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // Gateway `cat` failure (non-zero exits on all six paths) maps to inactive,
    // never to a fake active state and never throws.
    @Test fun gateway_catFailureMapsToInactive() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = absentSix(dir)
            val gw = FakeCatGateway(Privilege.ROOT) { CmdResult(1, "", "No such file") }
            val state = readHookStateWithGateway(gw, files)
            assertFalse(state.active)
            assertTrue(state.scopes.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // A throwing gateway maps to inactive without propagating the exception.
    @Test fun gateway_throwingMapsToInactive() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = absentSix(dir)
            val gw = FakeCatGateway(Privilege.SHELL) { throw RuntimeException("binder died") }
            val state = readHookStateWithGateway(gw, files)
            assertFalse(state.active)
        } finally {
            dir.deleteRecursively()
        }
    }

    // A NONE-privilege (NoOp/unproven) gateway is never executed: no `cat`
    // is attempted and the result stays inactive.
    @Test fun gateway_nonePrivilegeNeverExecutes() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = absentSix(dir)
            val gw = FakeCatGateway(Privilege.NONE) { CmdResult(0, HookMarker.buildContent(setOf("x"), 1L), "") }
            val state = readHookStateWithGateway(gw, files)
            assertFalse(state.active)
            assertEquals(0, gw.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    // Null gateway (default) keeps the old behavior: direct reads only, and
    // a direct hit never consults any gateway.
    @Test fun gateway_nullGatewayIsDirectOnly() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = absentSix(dir)
            assertFalse(readHookStateWithGateway(null, files).active)
            assertFalse(readHookStateWithGateway(markerFiles = files).active)
            // Direct hit short-circuits: even a servable gateway is not called.
            files[0].writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 5L))
            val gw = FakeCatGateway(Privilege.SHELL) { CmdResult(1, "", "unused") }
            val state = readHookStateWithGateway(gw, files)
            assertTrue(state.active)
            assertEquals(0, gw.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    // PR #60 r6 Phase 5 (Finding #5): the production six-path list is pinned
    // — order matters for the diagnostics raw pick; the union itself is
    // order-free. Order: ss primary, daagent primary, ss fallback,
    // daagent fallback, legacy primary, legacy fallback.
    @Test fun allHookMarkerFiles_sixPathsFixedOrder() {
        assertEquals(
            listOf(
                HookMarker.MARKER_PATH_SYSTEM_SERVER,
                HookMarker.MARKER_PATH_DAAGENT,
                HookMarker.LEGACY_MARKER_PATH_SYSTEM_SERVER,
                HookMarker.LEGACY_MARKER_PATH_DAAGENT,
                HookMarker.MARKER_PATH,
                HookMarker.LEGACY_MARKER_PATH,
            ),
            allHookMarkerFiles().map { it.path },
        )
    }

    // PR #60 r6 Phase 5 (Finding #5): the raw picked for diagnostics (and for
    // hookMarkerTimeMs) is the FIRST file in the six-file order whose content
    // yields non-empty scopes — an earlier garbage-but-readable file must not
    // win, and a blank earlier file must not win either.
    @Test fun read_rawPickPrefersScopeProducingFileOverGarbage() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = sixTempFiles(dir)
            files[0].writeText("garbage\n")
            files[1].writeText("")
            val daagentContent = HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 42L)
            files[3].writeText(daagentContent) // the daagent per-scope fallback (index 3)
            val state = readHookState(files)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_DAAGENT), state.scopes)
            assertEquals(daagentContent, state.raw)
            assertEquals(42L, HookMarker.parseTimeMs(state.raw))
        } finally {
            dir.deleteRecursively()
        }
    }

    // PR #60 r6 Phase 5 (Finding #5): the gateway serves ONLY the per-scope
    // daagent FALLBACK path — the six-path union still surfaces daagent via
    // gateway and all six `cat`s are issued.
    @Test fun gateway_perScopeFallbackOnlyMapsToActive() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val files = absentSix(dir)
            val gw = FakeCatGateway(Privilege.SHELL) { cmd ->
                when (cmd) {
                    "cat ${HookMarker.LEGACY_MARKER_PATH_DAAGENT}" ->
                        CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 11L), "")
                    else -> CmdResult(1, "", "not found")
                }
            }
            val state = readHookStateWithGateway(gw, files)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_DAAGENT), state.scopes)
            assertEquals(6, gw.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    // PR #60 r6 Phase 5 (Finding #5): malformed per-scope file + valid peer ->
    // ACTIVE with the peer's scopes; malformed per-scope + blank peer ->
    // never INACTIVE (fail-closed, UNKNOWN_NO_DIAGNOSTIC).
    @Test fun diagnosis_malformedPerScopePeerValidIsActive() = runBlocking {
        val reads = listOf(
            MarkerRead.Readable("garbage\n"), // system_server per-scope: malformed
            MarkerRead.Readable(HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 10L)),
            MarkerRead.Readable(null),
            MarkerRead.Readable(null),
            MarkerRead.Readable(null),
            MarkerRead.Readable(null),
        )
        val diagnosis = diagnoseHookState(reads, emptyList())
        assertEquals(HookStatus.ACTIVE, diagnosis.status)
        assertEquals(setOf(HookMarker.SCOPE_DAAGENT), diagnosis.scopes)
    }

    @Test fun diagnosis_malformedPerScopePeerBlankIsNotInactive() = runBlocking {
        val reads = listOf(
            MarkerRead.Readable("garbage\n"),
            MarkerRead.Readable(""),
            MarkerRead.Readable(null),
            MarkerRead.Readable(null),
            MarkerRead.Readable(null),
            MarkerRead.Readable(null),
        )
        val diagnosis = diagnoseHookState(reads, emptyList())
        assertNotEquals(HookStatus.INACTIVE, diagnosis.status)
        assertEquals(HookStatus.UNKNOWN_NO_DIAGNOSTIC, diagnosis.status)
    }
}
