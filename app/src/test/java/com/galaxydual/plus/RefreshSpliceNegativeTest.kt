// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-37 P26 Stage 1 (GH #1 + #2) deterministic splice-negative tests:
// refreshLocked must take (id, serial) from ONE triple
// (resolveDualTargetTyped), never splice the typed id of one triple with the
// serial of another into a fictional FRESH pair such as (95, 41).
//
// Harness mirrors RemoveConfirmStageCTest (Unconfined Main + IO poll).
// Fixture is call-order scripted (ScriptedGateway pattern: every listUsers
// call consumes one snap entry, every identity call consumes one ident
// entry; exhausted scripts repeat the last value fail-safe) -- never a
// global R+N threshold. Scripts are refilled AFTER init settles, so each
// refresh phase consumes an exact, auditable sequence:
// - happy FRESH: 2 snaps + 6 idents + 1 packages.
// - drift/unknown null-path: 3 snaps + 9 idents (+ 0 packages: the listing
//   short-circuits before probing when the re-verification disagrees).
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RefreshSpliceNegativeTest {
    // One identity observation: id < 0 means owner-only on all three
    // channels (clean no-claim property); otherwise all three channels
    // consistently claim [id] with [serial] on both verbose channels.
    private data class IdentSpec(val id: Int, val serial: Int)

    // Call-order scripted gateway (per-stage consistent identity).
    private class SpliceGateway(
        val snaps: ArrayDeque<Int>,
        val idents: ArrayDeque<IdentSpec>,
        val pkgsByUser: MutableMap<Int, MutableSet<String>> = mutableMapOf(),
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT

        private fun nextSnap(): Int = if (snaps.size > 1) snaps.removeFirst() else snaps.first()
        private fun nextIdent(): IdentSpec =
            if (idents.size > 1) idents.removeFirst() else idents.first()

        private fun usersOut(id: Int): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                (if (id < 0) "" else "\tUserInfo{$id:DualApp:400} running\n")

        private fun verboseOut(spec: IdentSpec): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                (if (spec.id < 0) "" else "  UserInfo{${spec.id}:DualApp:400} running:\n" +
                    "    Serial: ${spec.serial}\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n")

        private fun dumpsysOut(spec: IdentSpec): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                (if (spec.id < 0) "" else "  UserInfo{${spec.id}:DualApp:400} serialNo=${spec.serial}:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.CLONE\n")

        private fun userOfListCmd(cmd: String): Int {
            val m = Regex("--user\\s+(\\d+)").find(cmd)
            return m?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" -> CmdResult(exit = 0, out = "0\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS ->
                CmdResult(exit = 0, out = usersOut(nextSnap()), err = "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(nextIdent()), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(nextIdent()), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL -> {
                val spec = nextIdent()
                CmdResult(exit = 0, out = if (spec.id < 0) "" else "${spec.id}\n", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val uid = userOfListCmd(cmd)
                val pkgs = pkgsByUser[uid] ?: emptySet()
                CmdResult(exit = 0, out = pkgs.map { "package:$it" }.joinToString("\n"), err = "")
            }
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    private val vms = mutableListOf<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try {
                vm.viewModelScope.cancel()
            } catch (_: Exception) {
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun makeVm(gw: SpliceGateway): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(gw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = SuCmdGateway(),
            shizukuGateway = ShizukuCmdGateway("com.example.test"),
            activeGateway = gw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        return vm
    }

    private fun awaitVm(vm: DualViewModel, timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (!cond()) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("timeout")
                Thread.sleep(20)
            }
        } catch (e: AssertionError) {
            throw AssertionError("timeout waiting for condition; state=${vm.state.value}")
        }
    }

    private fun stable95(serial: Int = 15): ArrayDeque<IdentSpec> =
        ArrayDeque(List(30) { IdentSpec(95, serial) })

    private fun snapsOf(vararg ids: Int): ArrayDeque<Int> = ArrayDeque(ids.toList())

    // Phase-1 helper: stable (95, serial) everywhere -> FRESH with the chat pkg.
    private fun reachFresh95(gw: SpliceGateway, vm: DualViewModel, serial: Int = 15) {
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95, 95))
        gw.idents.clear()
        gw.idents.addAll(stable95(serial))
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        val s = vm.state.value
        assertEquals(95, s.inventory.targetId)
        assertEquals(serial, s.inventory.targetSerial)
        assertTrue("precondition: chat must be listed, got: ${s.cloned}", s.cloned.contains("com.example.chat"))
    }

    // typed(95) stable but the listing-time re-verification sees (96, 41)
    // while packages(95) stay readable: must never surface FRESH(95, 41).
    // Fail closed: VERIFY_MISMATCH drift to 96 + STALE, old cloned kept.
    @Test
    fun splice_typed95_target96_neverFreshMismatchedPair() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(
                95 to mutableSetOf("com.example.chat"),
                96 to mutableSetOf("com.example.chat"),
            ),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm)

        // Phase 2: refresh triple pins (95, 15); listing re-verification sees
        // (96, 41); null-path re-check confirms 96 -> drift.
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95, 95, 96))
        gw.idents.clear()
        gw.idents.addAll(
            ArrayDeque(
                List(3) { IdentSpec(95, 15) } +
                    List(3) { IdentSpec(96, 41) } +
                    List(3) { IdentSpec(96, 41) },
            ),
        )
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("verification mismatch") }

        val s = vm.state.value
        assertFalse(
            "must never publish the spliced pair FRESH(95,41), got: $s",
            s.inventory.freshness == InventoryFreshness.FRESH &&
                s.inventory.targetId == 95 &&
                s.inventory.targetSerial == 41,
        )
        assertEquals(MsgKind.VERIFY, s.msgKind)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, s.diagnose?.code)
        assertTrue(
            "drift must name the ids, got: ${s.diagnose?.techDetail}",
            (s.diagnose?.techDetail ?: "").startsWith("verified id drifted: listed 95 but now 96"),
        )
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals(96, s.inventory.targetId)
        assertEquals(null, s.inventory.targetSerial)
        assertTrue(
            "drift must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
    }

    // typed(95) then the listing-time target vanishes (owner-only on all
    // channels): fail closed with CHANNEL/VERIFY_UNKNOWN + STALE, old cloned
    // kept, never FRESH.
    @Test
    fun splice_typed95_targetUnknown_failClosed() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm)

        // Phase 2: refresh triple pins (95, 15); listing re-verification sees
        // owner-only (no dual); null-path re-check confirms unknown.
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95, 95, 95))
        gw.idents.clear()
        gw.idents.addAll(
            ArrayDeque(
                List(3) { IdentSpec(95, 15) } +
                    List(3) { IdentSpec(-1, 0) } +
                    List(3) { IdentSpec(-1, 0) },
            ),
        )
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("cannot list clones for user 95") }

        val s = vm.state.value
        assertFalse("unknown target must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, s.diagnose?.code)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals(95, s.inventory.targetId)
        assertEquals(15, s.inventory.targetSerial)
        assertTrue(
            "unknown target must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
    }

    // Same id rebuilt with a fresh serial (95, 15 -> 95, 41): the published
    // pair must come from one evidence set only -- (95, 15) from the refresh
    // triple -- never the cross-triple splice (95, 41).
    @Test
    fun splice_sameIdSerialRebuild_keepsSingleEvidencePair() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(15),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        // Phase 2: refresh triple still pins (95, 15); the listing-time
        // re-verification already sees the rebuilt (95, 41). The explicit-id
        // listing tolerates the serial (expected == null) and probes 95, so
        // the refresh lands FRESH -- but the pair stays the single-evidence
        // (95, 15), never the spliced (95, 41).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95, 95))
        gw.idents.clear()
        gw.idents.addAll(
            ArrayDeque(
                List(3) { IdentSpec(95, 15) } +
                    List(3) { IdentSpec(95, 41) },
            ),
        )
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        val s = vm.state.value
        assertEquals(95, s.inventory.targetId)
        assertEquals(
            "pair must come from one triple, never the cross-triple splice (95,41), got: $s",
            15,
            s.inventory.targetSerial,
        )
        assertTrue("rebuilt target list must stay readable, got: ${s.cloned}", s.cloned.contains("com.example.chat"))
    }
}
