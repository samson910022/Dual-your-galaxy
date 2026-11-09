// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #53 splice-negative tests: refreshLocked runs ONE repo.refreshVerified()
// (snapshot 1 + typed target 3 + list 1 + post-list re-verify 3 = 8 commands)
// and only maps the RefreshOutcome to UiState. FRESH is published only when
// both triples agree on the strict (id, serial, parentId); an intra-refresh
// 95->96 rebuild or same-id new serial fails closed (CHANNEL, old list kept,
// never a fictional (95, 41) FRESH pair), and a parent-drifted identity
// fails closed with zero list probes.
//
// Harness mirrors RemoveConfirmStageCTest (Unconfined Main + IO poll).
// Fixture is call-order scripted (ScriptedGateway pattern: every listUsers
// call consumes one snap entry, every identity call consumes one ident
// entry; exhausted scripts repeat the last value fail-safe) -- never a
// global R+N threshold. Scripts are refilled AFTER init settles, so each
// refresh phase consumes exactly: 1 snap + 6 idents + 1 packages.
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
    // consistently claim [id] with [serial] on both verbose channels (null
    // serial omits both serial lines, simulating a SerialUnavailable
    // resolution that still confirms the id). dumpsysParent differs from
    // the verbose 0 to model a cross-channel parent disagreement.
    private data class IdentSpec(val id: Int, val serial: Int?, val dumpsysParent: Int = 0)

    // Call-order scripted gateway (per-stage consistent identity).
    private class SpliceGateway(
        val snaps: ArrayDeque<Int>,
        val idents: ArrayDeque<IdentSpec>,
        val pkgsByUser: MutableMap<Int, MutableSet<String>> = mutableMapOf(),
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        var listUsersCalls: Int = 0
        var identCalls: Int = 0
        var listPackagesCalls: Int = 0
        var installCalls: Int = 0
        var uninstallCalls: Int = 0
        var failListPackages: Boolean = false
        var listUsersExit: Int = 0
        var listUsersErr: String = ""
        // Minimal malformed-prop hook: when non-null, every identity (getprop)
        // call returns this output verbatim (still consuming one ident per
        // call so triple budgets stay honest). Used to drive
        // Unknown("typed property channel malformed: ...") with untrusted
        // text (e.g. an email) through resolveDualTargetTyped.
        var propOverride: String? = null

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
                    (if (spec.serial != null) "    Serial: ${spec.serial}\n" else "") +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n")

        private fun dumpsysOut(spec: IdentSpec): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                (if (spec.id < 0) "" else "  UserInfo{${spec.id}:DualApp:400}" +
                    (if (spec.serial != null) " serialNo=${spec.serial}" else "") + ":\n" +
                    "    parentId=${spec.dumpsysParent}\n" +
                    "    userType=android.os.usertype.profile.CLONE\n")

        private fun userOfListCmd(cmd: String): Int {
            val m = Regex("--user\\s+(\\d+)").find(cmd)
            return m?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" -> CmdResult(exit = 0, out = "0\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls++
                if (listUsersExit != 0 || listUsersErr.isNotEmpty()) {
                    CmdResult(exit = listUsersExit, out = "", err = listUsersErr)
                } else {
                    CmdResult(exit = 0, out = usersOut(nextSnap()), err = "")
                }
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                identCalls++
                CmdResult(exit = 0, out = verboseOut(nextIdent()), err = "")
            }
            cmd == DualRepository.CMD_DUMPSYS_USER -> {
                identCalls++
                CmdResult(exit = 0, out = dumpsysOut(nextIdent()), err = "")
            }
            cmd == DualRepository.CMD_GETPROP_DUAL -> {
                val spec = nextIdent()
                identCalls++
                val out = propOverride ?: (if (spec.id < 0) "" else "${spec.id}\n")
                CmdResult(exit = 0, out = out, err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val uid = userOfListCmd(cmd)
                listPackagesCalls++
                if (failListPackages) {
                    CmdResult(exit = 1, out = "", err = "Failure [list failed]")
                } else {
                    val pkgs = pkgsByUser[uid] ?: emptySet()
                    CmdResult(exit = 0, out = pkgs.map { "package:$it" }.joinToString("\n"), err = "")
                }
            }
            cmd.startsWith("pm install-existing") -> {
                installCalls++
                CmdResult(exit = 0, out = "ok", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                uninstallCalls++
                CmdResult(exit = 0, out = "ok", err = "")
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
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(stable95(serial))
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        val s = vm.state.value
        assertEquals(95, s.inventory.targetId)
        assertEquals(serial, s.inventory.targetSerial)
        assertTrue("precondition: chat must be listed, got: ${s.cloned}", s.cloned.contains("com.example.chat"))
    }

    // Happy path guard: a stable (95, 15) world still publishes
    // FRESH(95, 15), consuming exactly one snapshot + two triples + one
    // list probe (8 commands) -- never a second snapshot for re-verify.
    @Test
    fun splice_singleSnapshot_stillFresh() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        val listBefore = gw.listPackagesCalls
        reachFresh95(gw, vm, serial = 15)
        assertEquals("refresh must take exactly one snapshot", 1, gw.listUsersCalls - listUsersBefore)
        assertEquals("refresh must take two identity triples (first + post-list re-verify)", 6, gw.identCalls - identsBefore)
        assertEquals("refresh must list exactly once", 1, gw.listPackagesCalls - listBefore)
    }

    // A rebuilt world (96, 41) is published as a whole: FRESH(96, 41) with
    // 96's list, never a spliced FRESH(95, 41). The old incarnation's
    // selection is cleared, never carried to the new target.
    @Test
    fun splice_worldSwitch_publishesWholeNewTarget() {
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

        vm.setSourceCatalog(
            listOf(SourceAppEntry("com.example.chat", "Chat", 0, false)),
        )
        vm.toggleSelection("0:com.example.chat")
        assertTrue(vm.state.value.selectedKeys.contains("0:com.example.chat"))

        // Phase 2: the whole world is (96, 41) -- the single snapshot and
        // single triple both see it, so the refresh publishes FRESH(96, 41).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(96))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(96, 41) }))
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.inventory.targetId == 96 }

        val s = vm.state.value
        assertFalse(
            "must never publish the spliced pair FRESH(95,41), got: $s",
            s.inventory.freshness == InventoryFreshness.FRESH &&
                s.inventory.targetId == 95 &&
                s.inventory.targetSerial == 41,
        )
        assertEquals(InventoryFreshness.FRESH, s.inventory.freshness)
        assertEquals(96, s.inventory.targetId)
        assertEquals(41, s.inventory.targetSerial)
        assertTrue("96's chat must be listed, got: ${s.cloned}", s.cloned.contains("com.example.chat"))
        assertTrue(
            "old-incarnation selection must be cleared, got: ${s.selectedKeys}",
            s.selectedKeys.isEmpty(),
        )
        assertEquals("zero installs on target move, got: ${gw.installCalls}", 0, gw.installCalls)
        assertEquals("zero uninstalls on target move, got: ${gw.uninstallCalls}", 0, gw.uninstallCalls)
    }

    // The verified identity vanishes (owner-only on all channels): fail
    // closed with CHANNEL + STALE, old cloned kept, never FRESH. The
    // snapshot stayed readable (pmReadable true, same rule as before);
    // the write gate still blocks only proven-false, never relaxed here.
    @Test
    fun splice_targetUnknown_failClosed() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm)

        // Phase 2: owner-only world on all channels.
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(-1, 0) }))
        val listProbesBefore = gw.listPackagesCalls
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("cannot confirm dual profile") }

        val s = vm.state.value
        assertFalse("unknown target must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertEquals(DiagnoseCode.CHANNEL_ERROR, s.diagnose?.code)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals(95, s.inventory.targetId)
        assertEquals(15, s.inventory.targetSerial)
        assertEquals(true, s.pmReadable)
        assertTrue(
            "unknown target must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(
            "unknown target must not probe the list, got: ${gw.listPackagesCalls}",
            listProbesBefore, gw.listPackagesCalls,
        )
        // S2: refresh-driven Unknown = 1 snapshot + 1 triple + 0 list.
        // The post-list fence Unknown (mid-refresh flip) runs both triples
        // (6 idents + 1 list) -- the two shapes must stay distinguished.
        assertEquals("refresh Unknown must take exactly one snapshot", 1, gw.listUsersCalls - listUsersBefore)
        assertEquals("refresh Unknown must take one triple only (no re-verify)", 3, gw.identCalls - identsBefore)
        assertEquals("refresh Unknown must take zero list probes", 0, gw.listPackagesCalls - listProbesBefore)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // Cross-channel parent disagreement (verbose 0 vs dumpsys 1): the
    // single triple refuses the drifted identity -- fail closed with
    // CHANNEL, old cloned kept, zero list probes, never FRESH. This is
    // the refresh-side counterpart of the pinned paths' TargetMoved:
    // the drifted identity is refused, never written or listed.
    @Test
    fun splice_parentDrift_failClosedWithoutListing() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm)

        // Phase 2: same id/serial, but dumpsys now claims parent 1.
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(95, 15, dumpsysParent = 1) }))
        val listProbesBefore = gw.listPackagesCalls
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("cannot confirm dual profile") }

        val s = vm.state.value
        assertFalse("parent drift must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertTrue(
            "detail must name the parent conflict, got: ${s.diagnose?.techDetail}",
            (s.diagnose?.techDetail ?: "").contains("parent"),
        )
        assertTrue(
            "parent drift must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(
            "parent drift must not probe the list, got: ${gw.listPackagesCalls}",
            listProbesBefore, gw.listPackagesCalls,
        )
        // S2: refresh-driven Unknown = 1 snapshot + 1 triple + 0 list.
        // The post-list fence Unknown (mid-refresh flip) runs both triples
        // (6 idents + 1 list) -- the two shapes must stay distinguished.
        assertEquals("refresh Unknown must take exactly one snapshot", 1, gw.listUsersCalls - listUsersBefore)
        assertEquals("refresh Unknown must take one triple only (no re-verify)", 3, gw.identCalls - identsBefore)
        assertEquals("refresh Unknown must take zero list probes", 0, gw.listPackagesCalls - listProbesBefore)
        assertEquals("zero installs on parent drift, got: ${gw.installCalls}", 0, gw.installCalls)
        assertEquals("zero uninstalls on parent drift, got: ${gw.uninstallCalls}", 0, gw.uninstallCalls)
    }

    // Same id rebuilt with a fresh serial (95, 15 -> 95, 41): the single
    // triple sees the new incarnation whole, so FRESH(95, 41) is published
    // as one consistent pin (never mixed with the old world's pair).
    // The old incarnation's selection is cleared, never carried across.
    @Test
    fun splice_sameIdSerialRebuild_publishesWholeNewIncarnation() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(15),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)
        // Bind a selection to (95, 15) so the rebuild must clear it.
        vm.setSourceCatalog(
            listOf(SourceAppEntry("com.example.chat", "Chat", 0, false)),
        )
        vm.toggleSelection("0:com.example.chat")
        assertTrue(vm.state.value.selectedKeys.contains("0:com.example.chat"))

        // Phase 2: the whole world is the rebuilt (95, 41).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(95, 41) }))
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.inventory.targetSerial == 41 }

        val s = vm.state.value
        assertEquals(InventoryFreshness.FRESH, s.inventory.freshness)
        assertEquals(95, s.inventory.targetId)
        assertEquals(41, s.inventory.targetSerial)
        assertTrue("rebuilt incarnation must stay listed, got: ${s.cloned}", s.cloned.contains("com.example.chat"))
        assertTrue(
            "old-incarnation selection must be cleared, never carried to the new incarnation, got: ${s.selectedKeys}",
            s.selectedKeys.isEmpty(),
        )
        assertEquals("zero installs on rebuild, got: ${gw.installCalls}", 0, gw.installCalls)
        assertEquals("zero uninstalls on rebuild, got: ${gw.uninstallCalls}", 0, gw.uninstallCalls)
    }

    // Intra-refresh serial flip (95,15 -> 95,41 between the first triple and
    // the post-list re-verify): the post-list fence proves the new target
    // and reports TargetMoved -- VERIFY_MISMATCH/INVALID, STALE live target
    // (95,41), old cloned kept, pmReadable true, never FRESH, zero writes.
    // SpliceGateway flip hook: first 3 ident calls see the old incarnation,
    // the re-verify triple sees the new one.
    @Test
    fun splice_midRefreshSerialFlip_failClosed() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        // Single refresh where identity flips mid-refresh (triple->list):
        // first triple (95,15), list on 95, re-verify (95,41).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(3) { IdentSpec(95, 15) } + List(30) { IdentSpec(95, 41) }))
        val identsBefore = gw.identCalls
        val listBefore = gw.listPackagesCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.VERIFY_MISMATCH }

        val s = vm.state.value
        assertFalse("mid-refresh serial flip must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(MsgKind.INVALID, s.msgKind)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, s.diagnose?.code)
        assertTrue(
            "detail must name post-list target moved, got: ${s.diagnose?.techDetail}",
            (s.diagnose?.techDetail ?: "").contains("post-list target moved"),
        )
        assertTrue(
            "detail must name post-list drift, got: ${s.diagnose?.techDetail}",
            (s.diagnose?.techDetail ?: "").contains("post-list"),
        )
        assertTrue(
            "msg must ask for reconfirmation, got: ${s.msg}",
            s.msg.contains("dual target changed") && s.msg.contains("reconfirm"),
        )
        assertTrue(
            "VM must not clear the old cloned list on fence fail, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(true, s.pmReadable)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals(95, s.inventory.targetId)
        assertEquals(41, s.inventory.targetSerial)
        assertTrue(
            "empty-selection flip must stay a no-op for selection, got: ${s.selectedKeys}",
            s.selectedKeys.isEmpty(),
        )
        assertEquals("batchReport must stay null, got: ${s.batchReport}", null, s.batchReport)
        assertEquals("zero installs on fence fail, got: ${gw.installCalls}", 0, gw.installCalls)
        assertEquals("zero uninstalls on fence fail, got: ${gw.uninstallCalls}", 0, gw.uninstallCalls)
        assertEquals("flip must run both triples", 6, gw.identCalls - identsBefore)
        assertEquals("exactly one list probe", 1, gw.listPackagesCalls - listBefore)
    }

    // Intra-refresh id flip (95 -> 96 between the first triple and the
    // post-list re-verify): the fence proves (96,41) -- VERIFY_MISMATCH/
    // INVALID, STALE live target (96,41), never FRESH(95,15), old cloned
    // kept and pmReadable true.
    @Test
    fun splice_midRefreshIdFlip_failClosed() {
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
        reachFresh95(gw, vm, serial = 15)

        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(3) { IdentSpec(95, 15) } + List(30) { IdentSpec(96, 41) }))
        val identsBefore = gw.identCalls
        val listBefore = gw.listPackagesCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.VERIFY_MISMATCH }

        val s = vm.state.value
        assertFalse("mid-refresh id flip must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(MsgKind.INVALID, s.msgKind)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, s.diagnose?.code)
        assertTrue(
            "detail must name post-list target moved, got: ${s.diagnose?.techDetail}",
            (s.diagnose?.techDetail ?: "").contains("post-list target moved"),
        )
        assertTrue(
            "msg must ask for reconfirmation, got: ${s.msg}",
            s.msg.contains("dual target changed") && s.msg.contains("reconfirm"),
        )
        assertTrue(
            "VM must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(true, s.pmReadable)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals(96, s.inventory.targetId)
        assertEquals(41, s.inventory.targetSerial)
        assertTrue(
            "empty-selection flip must stay a no-op for selection, got: ${s.selectedKeys}",
            s.selectedKeys.isEmpty(),
        )
        assertEquals("batchReport must stay null, got: ${s.batchReport}", null, s.batchReport)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
        assertEquals("flip must run both triples", 6, gw.identCalls - identsBefore)
        assertEquals("exactly one list probe", 1, gw.listPackagesCalls - listBefore)
    }

    // Bound selection + mid-refresh flip (95,15)->(96,41): the fence
    // proves the new target, so the VM must clear the bound selection
    // (live != bound) and never restore it for the moved target. Mirror
    // the selection binding of worldSwitch/unknown_keepsSelection.
    @Test
    fun splice_midRefreshFlip_clearsBoundSelection() {
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
        reachFresh95(gw, vm, serial = 15)

        vm.setSourceCatalog(
            listOf(SourceAppEntry("com.example.chat", "Chat", 0, false)),
        )
        vm.toggleSelection("0:com.example.chat")
        assertTrue(vm.state.value.selectedKeys.contains("0:com.example.chat"))

        // Single refresh where identity flips mid-refresh (triple->list):
        // first triple (95,15), list on 95, re-verify (96,41).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(3) { IdentSpec(95, 15) } + List(30) { IdentSpec(96, 41) }))
        val identsBefore = gw.identCalls
        val listBefore = gw.listPackagesCalls
        vm.refresh()
        awaitVm(vm) {
            !vm.state.value.loading &&
                vm.state.value.diagnose?.code == DiagnoseCode.VERIFY_MISMATCH &&
                vm.state.value.selectedKeys.isEmpty()
        }

        val s = vm.state.value
        assertFalse("mid-refresh flip must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(DiagnoseCode.VERIFY_MISMATCH, s.diagnose?.code)
        assertEquals(MsgKind.INVALID, s.msgKind)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals(96, s.inventory.targetId)
        assertEquals(41, s.inventory.targetSerial)
        assertTrue(
            "bound selection must be cleared on proven target move, got: ${s.selectedKeys}",
            s.selectedKeys.isEmpty(),
        )
        assertEquals("batchReport must stay null, got: ${s.batchReport}", null, s.batchReport)
        assertTrue(
            "VM must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(true, s.pmReadable)
        assertEquals("zero installs on fence fail, got: ${gw.installCalls}", 0, gw.installCalls)
        assertEquals("zero uninstalls on fence fail, got: ${gw.uninstallCalls}", 0, gw.uninstallCalls)
        assertEquals("flip must run both triples", 6, gw.identCalls - identsBefore)
        assertEquals("exactly one list probe", 1, gw.listPackagesCalls - listBefore)
    }

    // Resolution without a serial (SerialUnavailable shape: id confirmed,
    // both serial lines missing): the pinned target carries serial null --
    // never a fictional serial spliced from another window.
    @Test
    fun splice_noSerial_freshWithNullSerial() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        // Phase 2: id confirmed but no serial on either channel.
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(95, null) }))
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.inventory.targetSerial == null && vm.state.value.inventory.freshness == InventoryFreshness.FRESH }

        val s = vm.state.value
        assertEquals(InventoryFreshness.FRESH, s.inventory.freshness)
        assertEquals(95, s.inventory.targetId)
        assertEquals(null, s.inventory.targetSerial)
        assertTrue("serial-unavailable target must stay listed, got: ${s.cloned}", s.cloned.contains("com.example.chat"))
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // B2(a): Confirmed id missing from the pm snapshot (typed channels
    // confirm 95, snapshot lists owner-only {0}): fail closed with CHANNEL
    // + CHANNEL_ERROR (never NoDual, never FRESH), old cloned kept, the old
    // inventory target (95, 15) kept, zero list probes, zero writes. The
    // snapshot gate runs before the list probe, so the refresh consumes
    // 1 snapshot + 1 triple + 0 list.
    @Test
    fun confirmedNotInSnapshot_keepsOldClonedAndNeverNoDual() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        // Phase 2: snapshot owner-only (-1), idents stay confirmed 95/15.
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(-1))
        gw.idents.clear()
        gw.idents.addAll(stable95(15))
        val listBefore = gw.listPackagesCalls
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val s = vm.state.value
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertEquals(DiagnoseCode.CHANNEL_ERROR, s.diagnose?.code)
        assertFalse("missing-from-snapshot must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertTrue(
            "must keep the old cloned list, never NoDual-clear, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(95, s.inventory.targetId)
        assertEquals(15, s.inventory.targetSerial)
        assertEquals(0, gw.listPackagesCalls - listBefore)
        assertEquals(1, gw.listUsersCalls - listUsersBefore)
        assertEquals(3, gw.identCalls - identsBefore)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // B2(b): SerialUnavailable id missing from the pm snapshot (typed
    // channels confirm 95 with no serial, snapshot lists owner-only {0}):
    // same fail-closed shape as B2(a) -- CHANNEL + CHANNEL_ERROR, old
    // cloned kept, old target (95, 15) kept, zero list probes, zero
    // writes. Never NoDual, never FRESH.
    @Test
    fun serialUnavailableNotInSnapshot_keepsOldCloned() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        // Phase 2: snapshot owner-only (-1), idents stay 95 with no serial.
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(-1))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(95, null) }))
        val listBefore = gw.listPackagesCalls
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val s = vm.state.value
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertEquals(DiagnoseCode.CHANNEL_ERROR, s.diagnose?.code)
        assertFalse("missing-from-snapshot must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertTrue(
            "must keep the old cloned list, never NoDual-clear, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(95, s.inventory.targetId)
        assertEquals(15, s.inventory.targetSerial)
        assertEquals(0, gw.listPackagesCalls - listBefore)
        assertEquals(1, gw.listUsersCalls - listUsersBefore)
        assertEquals(3, gw.identCalls - identsBefore)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // B3: List failure on the pinned target keeps the old cloned list and
    // reports VERIFY_UNKNOWN (never FRESH). The pinned target (95, 15) is
    // marked STALE, pmReadable stays true (`pm list users` was readable;
    // only the clone listing is unknown), msg names "cannot list clones".
    // Refresh consumes 1 snapshot + 2 triples + 1 list (the list ran, then
    // the post-list fence passed on the same pinned target).
    @Test
    fun refresh_listFailure_keepsOldClonedAndVerifyUnknown() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        gw.failListPackages = true
        val listBefore = gw.listPackagesCalls
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.VERIFY_UNKNOWN }

        val s = vm.state.value
        assertEquals(DiagnoseCode.VERIFY_UNKNOWN, s.diagnose?.code)
        assertEquals(InventoryFreshness.STALE, s.inventory.freshness)
        assertEquals(95, s.inventory.targetId)
        assertEquals(15, s.inventory.targetSerial)
        assertTrue(
            "list failure must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(true, s.pmReadable)
        assertTrue(
            "msg must name the list failure, got: ${s.msg}",
            s.msg.contains("cannot list clones"),
        )
        assertEquals(1, gw.listPackagesCalls - listBefore)
        assertEquals(1, gw.listUsersCalls - listUsersBefore)
        assertEquals(6, gw.identCalls - identsBefore)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // S1: Refresh into Unknown keeps the user's selection (documented
    // retention). The Unknown branch keeps the old cloned list and the old
    // inventory target, so the selection stays bound to the same
    // (95, 15) and must not be cleared; only a proven target move clears
    // it (see splice_worldSwitch / splice_sameIdSerialRebuild).
    @Test
    fun unknown_keepsSelection() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        vm.setSourceCatalog(
            listOf(SourceAppEntry("com.example.chat", "Chat", 0, false)),
        )
        vm.toggleSelection("0:com.example.chat")
        assertTrue(vm.state.value.selectedKeys.contains("0:com.example.chat"))

        // Phase 2: verified identity vanishes (owner-only on all channels).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(-1, 0) }))
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.msg.contains("cannot confirm dual profile") }

        val s = vm.state.value
        assertFalse("unknown must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertTrue(
            "Unknown must retain the selection (same bound target), got: ${s.selectedKeys}",
            s.selectedKeys.contains("0:com.example.chat"),
        )
        assertTrue(
            "Unknown must keep the old cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // P2: ChannelError techDetail must be sanitized before truncation. A
    // gateway detail carrying a raw email (foo@example.com) must never
    // surface verbatim in techDetail; it is redacted to the <email> marker
    // by sanitizeForDisplay (same rule as the same-branch msg and the
    // diagnoseEvent sites at L1319/1727/2150). capDetail stays the second
    // bound upstream.
    @Test
    fun channelError_techDetailRedactsEmail() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        gw.listUsersExit = 1
        gw.listUsersErr = "connect to foo@example.com failed"
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val s = vm.state.value
        val tech = s.diagnose?.techDetail ?: ""
        assertFalse(
            "techDetail must not leak the raw email, got: $tech",
            tech.contains("foo@example.com"),
        )
        assertTrue(
            "techDetail must carry the <email> redaction marker, got: $tech",
            tech.contains("<email>"),
        )
        assertTrue(
            "techDetail must keep the channel-failure prefix, got: $tech",
            tech.startsWith("profiles snapshot channel failed"),
        )
        assertFalse("channel error must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // Security (SHOULD): Unknown techDetail must be sanitized before
    // truncation. A malformed property-channel payload carrying a raw email
    // (foo@example.com) drives resolveDualTargetTyped to
    // Unknown("typed property channel malformed: x foo@example.com"), and
    // the VM Unknown branch (DualViewModel sanitizeForDisplay + take(200))
    // must redact it to <email> -- the raw address must never surface in
    // techDetail. Mirrors channelError_techDetailRedactsEmail for the
    // Unknown arm (DualViewModel.kt:1151). Fail closed: never FRESH, the
    // previously FRESH cloned list is kept.
    @Test
    fun unknown_techDetailRedactsEmail() {
        val gw = SpliceGateway(
            snaps = snapsOf(95),
            idents = stable95(),
            pkgsByUser = mutableMapOf(95 to mutableSetOf("com.example.chat")),
        )
        val vm = makeVm(gw)
        awaitVm(vm) { vm.state.value.channelReady }
        reachFresh95(gw, vm, serial = 15)

        // Phase 2: same (95, 15) world, but the property channel answers
        // malformed text carrying an email. First triple refuses -> Unknown,
        // zero list probes (1 snapshot + 1 triple + 0 list).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(95))
        gw.idents.clear()
        gw.idents.addAll(stable95(15))
        gw.propOverride = "x foo@example.com"
        val listBefore = gw.listPackagesCalls
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.diagnose?.code == DiagnoseCode.CHANNEL_ERROR }

        val s = vm.state.value
        val tech = s.diagnose?.techDetail ?: ""
        assertFalse(
            "techDetail must not leak the raw email, got: $tech",
            tech.contains("foo@example.com"),
        )
        assertTrue(
            "techDetail must carry the <email> redaction marker, got: $tech",
            tech.contains("<email>"),
        )
        assertTrue(
            "techDetail must keep the unknown-identity prefix, got: $tech",
            tech.startsWith("verified dual id unknown"),
        )
        assertTrue(
            "techDetail must name the malformed property channel, got: $tech",
            tech.contains("typed property channel malformed"),
        )
        assertFalse("unknown must never be FRESH, got: $s", s.inventory.freshness == InventoryFreshness.FRESH)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertEquals(DiagnoseCode.CHANNEL_ERROR, s.diagnose?.code)
        assertTrue(
            "unknown must keep the previously fresh cloned list, got: ${s.cloned}",
            s.cloned.contains("com.example.chat"),
        )
        assertEquals(95, s.inventory.targetId)
        assertEquals(15, s.inventory.targetSerial)
        assertEquals("refresh Unknown must take exactly one snapshot", 1, gw.listUsersCalls - listUsersBefore)
        assertEquals("refresh Unknown must take one triple only (no re-verify)", 3, gw.identCalls - identsBefore)
        assertEquals("refresh Unknown must take zero list probes", 0, gw.listPackagesCalls - listBefore)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }

    // S5: Sequential two-refresh whole-world. Gen1 (95, 15) publishes
    // FRESH whole; gen2 (96, 41) publishes FRESH whole on the next refresh.
    // The final FRESH is gen2 whole -- never a spliced pair mixing either
    // world's id/serial/list. Each refresh takes exactly one snapshot, so
    // listUsers delta == number of refreshes (2), idents 12 (6 per
    // refresh), list 2 (1 per refresh). Sequential only: the concurrent
    // variant would be flake-prone, so no threads / no Thread.sleep are
    // introduced here beyond the existing awaitVm harness poll.
    @Test
    fun splice_sequentialTwoRefresh_wholeWorld() {
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
        val listUsersBefore = gw.listUsersCalls
        val identsBefore = gw.identCalls
        val listBefore = gw.listPackagesCalls

        // Refresh #1: gen1 whole (95, 15).
        reachFresh95(gw, vm, serial = 15)
        assertEquals(InventoryFreshness.FRESH, vm.state.value.inventory.freshness)
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)

        // Refresh #2: gen2 whole (96, 41).
        gw.snaps.clear()
        gw.snaps.addAll(snapsOf(96))
        gw.idents.clear()
        gw.idents.addAll(ArrayDeque(List(30) { IdentSpec(96, 41) }))
        vm.refresh()
        awaitVm(vm) { !vm.state.value.loading && vm.state.value.inventory.targetId == 96 }

        val s = vm.state.value
        assertEquals(InventoryFreshness.FRESH, s.inventory.freshness)
        assertEquals(96, s.inventory.targetId)
        assertEquals(41, s.inventory.targetSerial)
        assertTrue("gen2 chat must be listed whole, got: ${s.cloned}", s.cloned.contains("com.example.chat"))
        assertFalse(
            "must never publish a spliced pair (95,41), got: $s",
            s.inventory.targetId == 95 && s.inventory.targetSerial == 41,
        )
        assertFalse(
            "must never publish a spliced pair (96,15), got: $s",
            s.inventory.targetId == 96 && s.inventory.targetSerial == 15,
        )
        assertEquals("two refreshes must take two snapshots", 2, gw.listUsersCalls - listUsersBefore)
        assertEquals("two refreshes must take twelve idents (two triples each)", 12, gw.identCalls - identsBefore)
        assertEquals("two refreshes must take two list probes", 2, gw.listPackagesCalls - listBefore)
        assertEquals(0, gw.installCalls)
        assertEquals(0, gw.uninstallCalls)
    }
}
