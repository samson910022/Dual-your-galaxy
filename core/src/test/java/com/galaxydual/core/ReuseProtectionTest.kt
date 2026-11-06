// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase D ID-reuse protection (issue #21, owner P1-3) + Phase A P1-A serial
// continuity (issue #21, #21 "execute-then-verify serial"): an Android user
// id may be recycled for a different profile incarnation. The id alone is
// not the identity -- serial pins the instance. A same-id target whose
// serial positively disagrees between resolve time and use time must be
// refused (auto paths) or reported VerifyUnknown (pinned post-write
// re-check), fail closed with zero mis-targeted writes. A known serial
// followed by a missing serial across fetches (expected 15 -> fresh null,
// before 15 -> after null) is continuity lost and likewise refuses (fail
// closed, before any write/probe). Only an unknown expected.serial stays
// tolerated (null -> 15 writes); single-fetch single-channel missing stays
// tolerated (Phase B resolver policy, unchanged). Parent needs no
// known-to-unknown rule (resolver already requires both channels known and
// == 0, so a Confirmed fresh target always carries parentId == 0).
class ReuseProtectionTest {
    // Mutable gateway: per-identity-fetch serials plus an optional post-write
    // drift. Identity fetch index increments per dumpsys call (verbose then
    // dumpsys form one fetch); serial lists shorter than the fetch count hold
    // their last value. Setting flipAfterWrite flips all later identity reads
    // to that serial once any install/uninstall command has run.
    private class FlipGateway(
        var listUsersOutput: String = DEFAULT_USERS,
        var propOutput: String = DEFAULT_PROP,
        var propOutputs: MutableList<String>? = null,
        var listPackagesOutput: String = "package:com.example.chat",
        var listPackagesExit: Int = 0,
        var verboseSerials: MutableList<Int?> = mutableListOf(15),
        var dumpsysSerials: MutableList<Int?> = mutableListOf(15),
        var flipAfterWrite: Int? = null,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        private var fetchIndex = 0
        private var propFetchIndex = 0
        private var drifted = false

        private fun serialFor(idx: Int, list: List<Int?>): Int? {
            if (drifted && flipAfterWrite != null) return flipAfterWrite
            return if (idx < list.size) list[idx] else list.last()
        }

        private fun buildVerbose(serial: Int?): String {
            val serialLine = if (serial != null) "\n    Serial: $serial" else ""
            return "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} running:\n" +
                "    Flags: 0x00000190$serialLine\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"
        }

        private fun buildDumpsys(serial: Int?): String {
            val serialPart = if (serial != null) " serialNo=$serial" else ""
            return "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400}$serialPart:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        }

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = listUsersOutput, err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = buildVerbose(serialFor(fetchIndex, verboseSerials)), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER -> {
                    val out = buildDumpsys(serialFor(fetchIndex, dumpsysSerials))
                    fetchIndex++
                    CmdResult(exit = 0, out = out, err = "")
                }
                cmd == DualRepository.CMD_GETPROP_DUAL -> {
                    val outs = propOutputs
                    val out = if (outs != null) {
                        val idx = propFetchIndex.coerceAtMost(outs.size - 1)
                        propFetchIndex++
                        outs[idx]
                    } else {
                        propOutput
                    }
                    CmdResult(exit = 0, out = out, err = "")
                }
                cmd.startsWith("pm install-existing") || cmd.startsWith("pm uninstall") -> {
                    drifted = true
                    CmdResult(exit = 0, out = "ok", err = "")
                }
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOutput, err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        fun installWrites(): List<String> = commands.filter { it.startsWith("pm install-existing") }

        fun uninstallWrites(): List<String> = commands.filter { it.startsWith("pm uninstall") }

        fun listProbes(): List<String> = commands.filter { it.startsWith("pm list packages") }

        fun targetCommandsFor(userId: Int): List<String> = commands.filter {
            it.contains("--user $userId")
        }

        companion object {
            const val DEFAULT_USERS =
                "Users:\n" +
                    "\tUserInfo{0:Owner:13} running\n" +
                    "\tUserInfo{95:DUAL_APP:400} running\n"
            const val DEFAULT_PROP = "95\n"
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(r: Result<T>) {
                    result = r
                }
            },
        )
        return (result ?: error("suspend block never resumed")).getOrThrow()
    }

    // Auto clone with a reused id (resolve serial 15, fresh serial 99) is
    // refused with zero install-existing writes. Refused (not NoDual) proves
    // the reuse guard fired rather than a plain unknown.
    @Test
    fun autoClone_reusedIdRefusedWithoutWrite() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15, 99),
            dumpsysSerials = mutableListOf(15, 99),
        )
        val repo = DualRepository(gateway)
        val res = runSuspend { repo.clone("com.example.chat") }
        assertEquals(1, res.exit)
        assertTrue("expected reuse refusal, got: ${res.err}", res.err.startsWith("refused:") && res.err.contains("reuse"))
        assertTrue(
            "reused id must never be written, got: ${gateway.commands}",
            gateway.installWrites().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // Pinned clone whose identity drifts across the write (15 -> 99) reports
    // VerifyUnknown (detail notes identity-changed), never Success, even
    // though the install and the list probe both succeed.
    @Test
    fun pinnedClone_driftAfterWriteYieldsVerifyUnknown() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15),
            dumpsysSerials = mutableListOf(15),
            flipAfterWrite = 99,
            listPackagesOutput = "package:com.example.chat",
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("drift must be VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        assertTrue(
            "detail must note identity-changed, got: ${(outcome as CloneOutcome.VerifyUnknown).detail}",
            outcome.detail.contains("identity-changed"),
        )
        // The write itself ran once (pinning cannot prevent the already-sent
        // command); the contract is fail-closed reporting, never Success.
        assertEquals(1, gateway.installWrites().size)
    }

    // Serial continuity is asymmetric (Phase A P1-A): unknown -> known is
    // tolerated (Phase B upgrade path, expected.serial == null writes), but
    // known -> unknown is continuity lost and refuses (fail closed, before
    // any write). Parent needs no such rule (see DualRepository KDoc).
    @Test
    fun serialSingleSideMissing_tolerated() {
        // expected.serial == null (both channels silent at resolve), fresh 15:
        // stays tolerated and writes.
        val missingFirst = FlipGateway(
            verboseSerials = mutableListOf(null, 15),
            dumpsysSerials = mutableListOf(null, 15),
        )
        val first = runSuspend { DualRepository(missingFirst).clone("com.example.chat") }
        assertEquals("missing-expected must be tolerated, got: $first", 0, first.exit)
        assertEquals(1, missingFirst.installWrites().size)

        // fresh.serial == null (channels fall silent after resolve), expected
        // 15: continuity lost -> refused (exit 1, reuse/continuity reason),
        // zero writes, no target command.
        val missingFresh = FlipGateway(
            verboseSerials = mutableListOf(15, null),
            dumpsysSerials = mutableListOf(15, null),
        )
        val second = runSuspend { DualRepository(missingFresh).clone("com.example.chat") }
        assertEquals("continuity-lost must refuse, got: $second", 1, second.exit)
        assertTrue(
            "expected reuse/continuity refusal, got: ${second.err}",
            second.err.startsWith("refused:") && (second.err.contains("reuse") || second.err.contains("continuity")),
        )
        assertTrue(
            "continuity-lost must never write, got: ${missingFresh.commands}",
            missingFresh.installWrites().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${missingFresh.commands}",
            missingFresh.targetCommandsFor(95).isEmpty(),
        )
    }

    // Auto remove with continuity lost (expected serial 15, fresh serial
    // missing) is refused with zero uninstall writes and no target command.
    @Test
    fun autoRemove_serialContinuityLost_refusedWithoutWrite() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15, null),
            dumpsysSerials = mutableListOf(15, null),
        )
        val repo = DualRepository(gateway)
        val res = runSuspend { repo.remove("com.example.chat") }
        assertEquals(1, res.exit)
        assertTrue(
            "expected reuse/continuity refusal, got: ${res.err}",
            res.err.startsWith("refused:") && (res.err.contains("reuse") || res.err.contains("continuity")),
        )
        assertTrue(
            "continuity-lost must never write, got: ${gateway.commands}",
            gateway.uninstallWrites().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // Pinned clone whose serial is lost across the write (before 15, fresh
    // 15, after missing) reports VerifyUnknown (identity-changed +
    // continuity lost), never Success, even though install and list both
    // succeed. The write itself ran once (post-write drift); the contract is
    // fail-closed reporting, never Success.
    @Test
    fun pinnedClone_continuityLostAfterWrite_yieldsVerifyUnknown() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15, 15, null),
            dumpsysSerials = mutableListOf(15, 15, null),
            listPackagesOutput = "package:com.example.chat",
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("drift must be VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must note identity-changed, got: $detail", detail.contains("identity-changed"))
        assertTrue("detail must note continuity lost, got: $detail", detail.contains("continuity lost"))
        assertEquals(1, gateway.installWrites().size)
    }

    // Pinned clone whose serial is already lost before the write (before 15,
    // fresh missing) reports VerifyUnknown without emitting any write
    // command (fail closed pre-write branch).
    @Test
    fun pinnedClone_continuityLostBeforeWrite_yieldsVerifyUnknownWithoutWrite() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15, null),
            dumpsysSerials = mutableListOf(15, null),
            listPackagesOutput = "package:com.example.chat",
        )
        val repo = DualRepository(gateway)
        val outcome = runSuspend { repo.cloneVerified("com.example.chat") }
        assertTrue("pre-write drift must be VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must note identity-changed, got: $detail", detail.contains("identity-changed"))
        assertTrue("detail must note continuity lost, got: $detail", detail.contains("continuity lost"))
        assertTrue(
            "pre-write drift must never write, got: ${gateway.commands}",
            gateway.installWrites().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // verifyOnProfiles continuity (auto verify path, Phase A P1-A): expected
    // serial 15 followed by fresh serial missing maps to Unknown (folded to
    // false by the Boolean auto verify) without emitting the `pm list
    // packages` probe. The explicit verifyState(pkg, dualId) entry carries
    // expected=null by construction so it has no cross-fetch pair; the
    // cross-fetch Unknown is locked here via the auto path that shares the
    // same verifyOnProfiles guard.
    @Test
    fun autoVerify_serialContinuityLost_noProbe() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15, null),
            dumpsysSerials = mutableListOf(15, null),
            listPackagesOutput = "package:com.example.chat",
        )
        val verified = runSuspend { DualRepository(gateway).verify("com.example.chat") }
        assertTrue("continuity-lost verify must be false (Unknown folded), got: $verified", !verified)
        assertTrue(
            "continuity-lost must not probe, got: ${gateway.commands}",
            gateway.listProbes().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // listOnProfiles continuity (auto list path, Phase A P1-A): expected
    // serial 15 followed by fresh serial missing yields null (unknown)
    // without emitting the `pm list packages` probe. The explicit entry now
    // carries its own expected pair (see below); the auto path sharing the
    // same listOnProfiles guard is locked here.
    @Test
    fun autoList_serialContinuityLost_returnsNullWithoutProbe() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15, null),
            dumpsysSerials = mutableListOf(15, null),
            listPackagesOutput = "package:com.example.chat",
        )
        val listed = runSuspend { DualRepository(gateway).listClonedPackages() }
        assertEquals("continuity-lost list must be null (unknown), got: $listed", null, listed)
        assertTrue(
            "continuity-lost must not probe, got: ${gateway.commands}",
            gateway.listProbes().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // PR-37 P26 Stage 1 (GH #1): explicit listing carries the resolve-time
    // pair, so a same-id rebuild surfaces as SerialBreach (not Ok) without
    // probing the new incarnation. The legacy single-arg entry no longer
    // exists; the explicit entry takes VerifiedTarget? (null stays tolerated).
    @Test
    fun explicitList_sameIdSerialRebuild_returnsBreachWithoutProbe() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(41),
            dumpsysSerials = mutableListOf(41),
            listPackagesOutput = "package:com.example.chat",
        )
        val repo = DualRepository(gateway)
        val result = runSuspend {
            repo.listClonedPackages(95, VerifiedTarget(95, 15, parentId = 0))
        }
        assertTrue("same-id rebuild must breach, got: $result", result is DualRepository.ListClonedResult.SerialBreach)
        val breach = result as DualRepository.ListClonedResult.SerialBreach
        assertEquals(95, breach.fresh?.userId)
        assertEquals(41, breach.fresh?.serial)
        assertTrue(
            "breach must not probe the new incarnation, got: ${gateway.commands}",
            gateway.listProbes().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // Same id + same serial still lists (happy path must not be killed).
    @Test
    fun explicitList_sameSerial_returnsOk() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15),
            dumpsysSerials = mutableListOf(15),
            listPackagesOutput = "package:com.example.chat",
        )
        val repo = DualRepository(gateway)
        val result = runSuspend {
            repo.listClonedPackages(95, VerifiedTarget(95, 15, parentId = 0))
        }
        assertTrue("same serial must list, got: $result", result is DualRepository.ListClonedResult.Ok)
        assertEquals(
            listOf("com.example.chat"),
            (result as DualRepository.ListClonedResult.Ok).packages,
        )
        assertEquals(1, gateway.listProbes().size)
    }

    // Known serial followed by missing fresh serial is continuity lost:
    // SerialBreach without probing (fail closed, never an authoritative list).
    @Test
    fun explicitList_serialContinuityLost_returnsBreachWithoutProbe() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf<Int?>(null),
            dumpsysSerials = mutableListOf<Int?>(null),
            listPackagesOutput = "package:com.example.chat",
        )
        val repo = DualRepository(gateway)
        val result = runSuspend {
            repo.listClonedPackages(95, VerifiedTarget(95, 15, parentId = 0))
        }
        assertTrue("continuity-lost must breach, got: $result", result is DualRepository.ListClonedResult.SerialBreach)
        assertTrue(
            "continuity-lost must not probe, got: ${gateway.commands}",
            gateway.listProbes().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // Null expected stays tolerated (legacy upgrade path): a fresh serial is
    // listed without a breach, even when it differs from nothing.
    @Test
    fun explicitList_nullExpected_toleratesFreshSerial() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(41),
            dumpsysSerials = mutableListOf(41),
            listPackagesOutput = "package:com.example.chat",
        )
        val repo = DualRepository(gateway)
        val result = runSuspend { repo.listClonedPackages(95, null) }
        assertTrue("null expected must tolerate, got: $result", result is DualRepository.ListClonedResult.Ok)
        assertEquals(
            listOf("com.example.chat"),
            (result as DualRepository.ListClonedResult.Ok).packages,
        )
    }

    // Serial cross-channel conflict via the auto clone path (Phase B P1-3):
    // verbose serial 15 vs dumpsys serial 99 (parent 0, CLONE both, prop 95)
    // is Refused -> fetch null -> resolve -1 and auto clone(pkg) "no dual
    // profile" with zero target commands. Locks the single-fetch conflict
    // on the write path (distinct from the cross-fetch reuse guard above).
    @Test
    fun autoClone_crossChannelSerialConflict_refusedWithoutWrite() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15),
            dumpsysSerials = mutableListOf(99),
        )
        val repo = DualRepository(gateway)
        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        val res = runSuspend { repo.clone("com.example.chat") }
        assertEquals(1, res.exit)
        assertEquals("no dual profile", res.err)
        assertTrue(
            "conflict must never write, got: ${gateway.commands}",
            gateway.installWrites().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // Cross-fetch property flip (Phase D): fetch1 prop 95 -> target 95,
    // fetch2 prop 96 -> fresh null (property mismatch Refused). Auto
    // clone(pkg) carries expected 95 but fresh is null, so the explicit
    // guard refuses ("not the verified dual target", no stale-95 write).
    // This is the fresh-null path: a property change never writes stale 95.
    // Parent-flip (0 -> 10) likewise resolves to fresh null (parent conflict
    // Refused) and is covered by the same fresh-null refusal, so no extra
    // fake is added for it.
    @Test
    fun autoClone_crossFetchPropertyFlip_refusedWithoutStaleWrite() {
        val gateway = FlipGateway(
            verboseSerials = mutableListOf(15, 15),
            dumpsysSerials = mutableListOf(15, 15),
            propOutputs = mutableListOf("95\n", "96\n"),
        )
        val repo = DualRepository(gateway)
        val res = runSuspend { repo.clone("com.example.chat") }
        assertEquals(1, res.exit)
        assertTrue(
            "property flip must refuse, got: ${res.err}",
            res.err.startsWith("refused:"),
        )
        assertTrue(
            "property flip must never write stale 95, got: ${gateway.commands}",
            gateway.installWrites().isEmpty(),
        )
        assertTrue(
            "no target command for user 95, got: ${gateway.commands}",
            gateway.targetCommandsFor(95).isEmpty(),
        )
    }

    // Direct parser mapping: Confirmed carries the target, Unknown/Refused
    // map to null (single source for resolveDualUserIdVerified).
    @Test
    fun resolveDualTarget_mapsConfirmedOnly() {
        val verbose =
            "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    Serial: 15\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
        val dumpsys =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        val target = PmOutputParser.resolveDualTarget(verbose, dumpsys, 95)
        assertEquals(VerifiedTarget(userId = 95, serial = 15, parentId = 0), target)
        assertEquals(95, PmOutputParser.resolveDualUserIdVerified(verbose, dumpsys, 95))
        assertEquals(null, PmOutputParser.resolveDualTarget(verbose, dumpsys, null))
        assertEquals(null, PmOutputParser.resolveDualTarget(verbose, dumpsys, 96))
    }
}
