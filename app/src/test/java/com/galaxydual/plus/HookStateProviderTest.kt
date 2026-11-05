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
import org.junit.Assert.assertTrue
import org.junit.Test

class HookStateProviderTest {
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
        val state = readHookState(missing)
        assertFalse(state.active)
        assertTrue(state.scopes.isEmpty())
    }

    // Temp-file round-trip: what the hook writes, the app reads as active.
    @Test fun read_tempMarkerFileIsActive() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val file = File(dir, "galaxydual_hook.active")
            file.writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 7L))
            val state = readHookState(file)
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

    // Dual-path direct read: scopes from the primary AND the fallback file
    // are unioned (a scope recorded via either write path is honored).
    @Test fun read_dualPathUnion() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val primary = File(dir, "primary.active")
            val fallback = File(dir, "fallback.active")
            primary.writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 1L))
            fallback.writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 2L))
            val state = readHookState(primary, fallback)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER, HookMarker.SCOPE_DAAGENT), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // Primary missing but fallback present still reads active (migration /
    // fallback-write history is not lost).
    @Test fun read_primaryMissingFallbackPresentIsActive() {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val fallback = File(dir, "fallback.active")
            fallback.writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 3L))
            val state = readHookState(File(dir, "absent.active"), fallback)
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

    private fun absentPair(dir: File): Pair<File, File> =
        Pair(File(dir, "no-primary.active"), File(dir, "no-fallback.active"))

    // Gateway `cat` success maps to active: direct reads miss (temp paths
    // absent), the SHELL gateway serves both marker paths, scopes union.
    @Test fun gateway_catSuccessMapsToActive() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val (primary, fallback) = absentPair(dir)
            val gw = FakeCatGateway(Privilege.SHELL) { cmd ->
                when (cmd) {
                    "cat ${HookMarker.MARKER_PATH}" ->
                        CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L), "")
                    "cat ${HookMarker.LEGACY_MARKER_PATH}" ->
                        CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 10L), "")
                    else -> CmdResult(1, "", "not found")
                }
            }
            val state = readHookStateWithGateway(gw, primary, fallback)
            assertTrue(state.active)
            assertEquals(setOf(HookMarker.SCOPE_SYSTEM_SERVER, HookMarker.SCOPE_DAAGENT), state.scopes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // Gateway `cat` failure (non-zero exits on both paths) maps to inactive,
    // never to a fake active state and never throws.
    @Test fun gateway_catFailureMapsToInactive() = runBlocking {
        val dir = Files.createTempDirectory("hookstate").toFile()
        try {
            val (primary, fallback) = absentPair(dir)
            val gw = FakeCatGateway(Privilege.ROOT) { CmdResult(1, "", "No such file") }
            val state = readHookStateWithGateway(gw, primary, fallback)
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
            val (primary, fallback) = absentPair(dir)
            val gw = FakeCatGateway(Privilege.SHELL) { throw RuntimeException("binder died") }
            val state = readHookStateWithGateway(gw, primary, fallback)
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
            val (primary, fallback) = absentPair(dir)
            val gw = FakeCatGateway(Privilege.NONE) { CmdResult(0, HookMarker.buildContent(setOf("x"), 1L), "") }
            val state = readHookStateWithGateway(gw, primary, fallback)
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
            val (primary, fallback) = absentPair(dir)
            assertFalse(readHookStateWithGateway(null, primary, fallback).active)
            assertFalse(readHookStateWithGateway(markerFile = primary, fallbackFile = fallback).active)
            // Direct hit short-circuits: even a servable gateway is not called.
            primary.writeText(HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 5L))
            val gw = FakeCatGateway(Privilege.SHELL) { CmdResult(1, "", "unused") }
            val state = readHookStateWithGateway(gw, primary, fallback)
            assertTrue(state.active)
            assertEquals(0, gw.calls)
        } finally {
            dir.deleteRecursively()
        }
    }
}
