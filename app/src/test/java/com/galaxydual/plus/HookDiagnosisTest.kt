// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// P1 hook-diagnosis tests (issue #6, 沿襲 #19, 配合 #27): the diagnostic
// reader is independent of the selected write gateway -- even an
// explicit-LSPosed (NoOp) selection diagnoses through direct reads plus any
// probe-verified true-channel gateway -- and reports four states
// (ACTIVE / INACTIVE / UNKNOWN_UNREADABLE / UNKNOWN_NO_DIAGNOSTIC) with
// unknown kept distinct from inactive. Diagnosis never feeds routing or
// readiness (hook-neutral lock). Pure Kotlin/JUnit4 with fake gateways and
// injected MarkerReads; no device, no Android runtime.
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.Privilege
import com.galaxydual.core.channelReadyFor
import com.galaxydual.core.routePrivilege
import com.galaxydual.hook.HookMarker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException

class HookDiagnosisTest {
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

    private fun servingGateway(privilege: Privilege): FakeCatGateway =
        FakeCatGateway(privilege) { cmd ->
            when (cmd) {
                "cat ${HookMarker.MARKER_PATH}" ->
                    CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L), "")
                "cat ${HookMarker.LEGACY_MARKER_PATH}" ->
                    CmdResult(0, HookMarker.buildContent(setOf(HookMarker.SCOPE_DAAGENT), 10L), "")
                else -> CmdResult(1, "", "not found")
            }
        }

    // (a) Marker exists but direct reads are denied (app-uid EACCES) and no
    // true channel is available: UNKNOWN_NO_DIAGNOSTIC, never INACTIVE --
    // absence cannot be claimed without a readable path.
    @Test fun diagnosis_directDeniedWithoutTrueChannelIsUnknownNoDiagnostic() = runBlocking {
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Denied, MarkerRead.Denied),
            emptyList(),
        )
        assertEquals(HookStatus.UNKNOWN_NO_DIAGNOSTIC, diagnosis.status)
        assertFalse(diagnosis.active)
        assertTrue(diagnosis.scopes.isEmpty())
    }

    // Partial denial (one path denied, the other positively absent) with no
    // gateway still cannot claim inactive: the denied path may hold scopes.
    @Test fun diagnosis_partialDenialWithoutGatewayIsNotInactive() = runBlocking {
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Denied, MarkerRead.Readable(null)),
            emptyList(),
        )
        assertNotEquals(HookStatus.INACTIVE, diagnosis.status)
        assertFalse(diagnosis.active)
    }

    // (b) A SHIZUKU diagnostic gateway that can `cat` the marker yields
    // ACTIVE even though direct reads failed (the explicit-LSPosed case:
    // diagnosis rides the true channel, not the selected NoOp gateway).
    @Test fun diagnosis_shizukuGatewayCatServesMarkerIsActive() = runBlocking {
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Denied, MarkerRead.Readable(null)),
            listOf(servingGateway(Privilege.SHELL)),
        )
        assertEquals(HookStatus.ACTIVE, diagnosis.status)
        assertTrue(diagnosis.active)
        assertTrue(diagnosis.viaGateway)
        assertEquals(
            setOf(HookMarker.SCOPE_SYSTEM_SERVER, HookMarker.SCOPE_DAAGENT),
            diagnosis.scopes,
        )
    }

    // (c) Positively-read absence (both paths checked, nothing there) with
    // no gateway needed reads as INACTIVE with the legacy "not detected" text.
    @Test fun diagnosis_readableButMissingIsInactive() = runBlocking {
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)),
            emptyList(),
        )
        assertEquals(HookStatus.INACTIVE, diagnosis.status)
        assertFalse(diagnosis.active)
        assertEquals("未偵測到", diagnosis.displayText())
    }

    // P2 (PR #20): ACTIVE is historical existence+scope evidence only (S24
    // marker predates boot by ~5.5h, no boot-identity/freshness check); the
    // text must name that limit and never read as proof of a live hook.
    @Test fun diagnosis_activeTextIsHistoricalExistenceOnly() {
        val text = HookDiagnosis(
            HookStatus.ACTIVE,
            setOf(HookMarker.SCOPE_SYSTEM_SERVER),
        ).displayText()
        assertTrue(text.contains(HookMarker.SCOPE_SYSTEM_SERVER))
        assertTrue("active text must name existence-only evidence: $text", text.contains("僅存在性證據"))
        assertTrue("active text must disclaim boot freshness: $text", text.contains("未確認本次開機是否生效"))
    }

    // Unknown states never render as "not detected" (reserved for
    // positively-read absence); they name the limitation instead.
    @Test fun diagnosis_unknownDisplayNeverClaimsNotDetected() {
        for (status in listOf(HookStatus.UNKNOWN_UNREADABLE, HookStatus.UNKNOWN_NO_DIAGNOSTIC)) {
            val text = HookDiagnosis(status).displayText()
            assertTrue("unknown text must say 狀態未知: $text", text.contains("狀態未知"))
            assertFalse("unknown text must not claim 未偵測到: $text", text.contains("未偵測到"))
        }
    }

    // A usable gateway was attempted but nothing could be read (both `cat`
    // paths failed): UNKNOWN_UNREADABLE -- a path existed, reading failed.
    @Test fun diagnosis_failedGatewayAttemptIsUnreadable() = runBlocking {
        val failing = FakeCatGateway(Privilege.SHELL) { CmdResult(1, "", "denied") }
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Denied, MarkerRead.Denied),
            listOf(failing),
        )
        assertEquals(HookStatus.UNKNOWN_UNREADABLE, diagnosis.status)
        assertFalse(diagnosis.active)
    }

    // A NONE-privilege (NoOp/unproven) gateway is never executed by the
    // diagnostic reader: no `cat` is attempted and denial stays
    // UNKNOWN_NO_DIAGNOSTIC.
    @Test fun diagnosis_noneGatewayNeverExecutes() = runBlocking {
        val noop = FakeCatGateway(Privilege.NONE) {
            CmdResult(0, HookMarker.buildContent(setOf("x"), 1L), "")
        }
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Denied, MarkerRead.Denied),
            listOf(noop),
        )
        assertEquals(HookStatus.UNKNOWN_NO_DIAGNOSTIC, diagnosis.status)
        assertEquals(0, noop.calls)
    }

    // A direct hit short-circuits: no gateway `cat` is attempted.
    @Test fun diagnosis_directHitShortCircuitsGateway() = runBlocking {
        val gateway = FakeCatGateway(Privilege.SHELL) { CmdResult(1, "", "unused") }
        val direct = listOf(
            MarkerRead.Readable(HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 5L)),
            MarkerRead.Readable(null),
        )
        val diagnosis = diagnoseHookState(direct, listOf(gateway))
        assertEquals(HookStatus.ACTIVE, diagnosis.status)
        assertFalse(diagnosis.viaGateway)
        assertEquals(0, gateway.calls)
    }

    // P1 regression (PR #20): the production readDirectMarker path must not
    // treat denial as absence. On-device EACCES surfaces as
    // AccessDeniedException under the NIO reader; it is injected here
    // because same-uid CI (and root, which ignores permission bits) cannot
    // reproduce an un-traversable parent dir with chmod, so a chmod-based
    // test would be flaky by construction.
    @Test fun readDirectMarker_accessDeniedReaderMapsToDenied() {
        val file = File("/data/system/galaxydual_hook.active")
        val read = readDirectMarker(file) { throw AccessDeniedException(it.path) }
        assertEquals(MarkerRead.Denied, read)
    }

    // Other denial spellings (SecurityManager/SELinux, generic EIO) are
    // likewise unknown, never positive absence.
    @Test fun readDirectMarker_denialVariantsMapToDenied() {
        val file = File("/data/system/galaxydual_hook.active")
        assertEquals(MarkerRead.Denied, readDirectMarker(file) { throw SecurityException("SELinux") })
        assertEquals(MarkerRead.Denied, readDirectMarker(file) { throw IOException("EIO") })
    }

    // Denied direct reads through the PRODUCTION overload (real
    // readDirectMarker calls with an injected denying reader, no usable
    // gateway) must stay UNKNOWN -- never INACTIVE, never "未偵測到".
    @Test fun diagnosis_productionOverloadDeniedWithoutGatewayIsUnknown() = runBlocking {
        val diagnosis = diagnoseHookState(
            File("/data/system/galaxydual_hook.active"),
            File("/data/local/tmp/galaxydual_hook.active"),
            emptyList(),
            reader = { throw AccessDeniedException(it.path) },
        )
        assertEquals(HookStatus.UNKNOWN_NO_DIAGNOSTIC, diagnosis.status)
        assertNotEquals(HookStatus.INACTIVE, diagnosis.status)
        assertFalse(diagnosis.active)
        assertFalse(diagnosis.displayText().contains("未偵測到"))
    }

    // Positive-absence semantics are preserved: a TRULY missing file goes
    // through the default NIO reader (NoSuchFileException) to Readable(null).
    @Test fun readDirectMarker_trulyMissingFileIsReadableNull() {
        val missing = File.createTempFile("galaxydual-marker-missing", ".active")
        assertTrue(missing.delete())
        assertEquals(MarkerRead.Readable(null), readDirectMarker(missing))
    }

    // ... and two truly-missing files through the production overload still
    // diagnose INACTIVE (positive absence, legacy "not detected" text).
    @Test fun diagnosis_productionOverloadMissingBothIsInactive() = runBlocking {
        fun missingTemp(): File {
            val f = File.createTempFile("galaxydual-marker-missing", ".active")
            assertTrue(f.delete())
            return f
        }
        val diagnosis = diagnoseHookState(missingTemp(), missingTemp(), emptyList())
        assertEquals(HookStatus.INACTIVE, diagnosis.status)
        assertEquals("未偵測到", diagnosis.displayText())
    }

    // Success path through the production reader: an existing readable file
    // yields its content.
    @Test fun readDirectMarker_existingFileReadsContent() {
        val content = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 7L)
        val file = File.createTempFile("galaxydual-marker-present", ".active")
        try {
            file.writeText(content)
            assertEquals(MarkerRead.Readable(content), readDirectMarker(file))
        } finally {
            file.delete()
        }
    }

    // P2 (PR #20): malformed content (readable but unparseable "garbage") is
    // neither active nor positive absence -- mixed with a truly-missing path
    // and no gateway it reads UNKNOWN, never INACTIVE.
    @Test fun diagnosis_garbageDirectWithoutGatewayIsNotInactive() = runBlocking {
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Readable("garbage"), MarkerRead.Readable(null)),
            emptyList(),
        )
        assertNotEquals(HookStatus.INACTIVE, diagnosis.status)
        assertFalse(diagnosis.active)
    }

    // All-readable-but-malformed direct paths are likewise not INACTIVE.
    @Test fun diagnosis_allGarbageDirectWithoutGatewayIsNotInactive() = runBlocking {
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Readable("garbage"), MarkerRead.Readable("junk\ntimeMs=abc\n")),
            emptyList(),
        )
        assertNotEquals(HookStatus.INACTIVE, diagnosis.status)
        assertFalse(diagnosis.active)
    }

    // A gateway `cat` exit 0 with unparseable content is not positive
    // absence either: attempted but nothing positively read, and the
    // malformed gateway read cannot erase the completed direct check --
    // UNKNOWN_UNREADABLE, never INACTIVE. (cat failures/null stay unchanged.)
    @Test fun diagnosis_garbageGatewayIsNotInactive() = runBlocking {
        val garbage = FakeCatGateway(Privilege.SHELL) { CmdResult(0, "garbage\n", "") }
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)),
            listOf(garbage),
        )
        assertNotEquals(HookStatus.INACTIVE, diagnosis.status)
        assertFalse(diagnosis.active)
    }

    // Blank content stays empty evidence (not malformed): positively-read
    // blank/missing paths still diagnose INACTIVE.
    @Test fun diagnosis_blankDirectIsStillInactive() = runBlocking {
        val diagnosis = diagnoseHookState(
            listOf(MarkerRead.Readable(""), MarkerRead.Readable(null)),
            emptyList(),
        )
        assertEquals(HookStatus.INACTIVE, diagnosis.status)
    }

    // (d) Hook-neutral lock: for every probe combination and every hook
    // status, routing still runs with hook signal excluded, readiness ignores
    // the diagnosis, and the UiState mapping preserves channel/privilege/
    // channelReady while carrying the hook display fields.
    @Test fun hookNeutral_diagnosisNeverGrantsReadinessOrRouting() {
        for (hasRoot in listOf(false, true)) {
            for (hasShizuku in listOf(false, true)) {
                // Expected privilege is a pure function of the two probes only
                // (su ROOT -> Shizuku -> NONE); hook status must not change it.
                val expectedPrivilege = if (hasRoot) Privilege.ROOT
                    else if (hasShizuku) Privilege.SHELL
                    else Privilege.NONE
                assertEquals(expectedPrivilege, routePrivilege(hasShizuku = hasShizuku, hasRoot = hasRoot))
                assertEquals(expectedPrivilege, hookNeutralRoute(hasRoot = hasRoot, hasShizuku = hasShizuku))
                for (status in HookStatus.values()) {
                    val diagnosis = HookDiagnosis(
                        status = status,
                        scopes = if (status == HookStatus.ACTIVE) {
                            setOf(HookMarker.SCOPE_SYSTEM_SERVER)
                        } else {
                            emptySet()
                        },
                    )
                    for (gateway in Privilege.values()) {
                        // Readiness is a pure function of the selected gateway
                        // plus the elevator probes: no hook-status input exists
                        // to consult, so no diagnosis can flip it. Pin the
                        // no-probe floor explicitly: without any true channel
                        // even an ACTIVE diagnosis stays unready.
                        if (!hasRoot && !hasShizuku) {
                            assertFalse(channelReadyFor(gateway, hasRoot, hasShizuku))
                        }
                    }
                    val base = UiState(
                        channelReady = channelReadyFor(Privilege.SHELL, hasRoot, hasShizuku),
                        privilege = hookNeutralRoute(hasRoot = hasRoot, hasShizuku = hasShizuku),
                    )
                    val mapped = base.withHookDiagnosis(diagnosis)
                    assertEquals(base.channelReady, mapped.channelReady)
                    assertEquals(base.privilege, mapped.privilege)
                    assertEquals(base.channel, mapped.channel)
                    assertEquals(diagnosis.active, mapped.hookActive)
                    assertEquals(diagnosis.status, mapped.hookStatus)
                    assertEquals(diagnosis.scopes, mapped.hookScopes)
                }
            }
        }
    }
}
