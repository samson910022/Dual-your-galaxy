// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #53 splice-negative tests: refreshLocked runs ONE repo.refreshVerified()
// (snapshot 1 + typed target 3 + list 1 = 5 commands) and only maps the
// RefreshOutcome to UiState. There is no second snapshot/triple to splice
// with, so a mid-refresh 95->96 rebuild or a same-id new serial can never
// surface as a fictional FRESH pair such as (95, 41): each refresh
// publishes the single pinned world as a whole, and a parent-drifted
// identity fails closed (CHANNEL, old list kept, zero list probes).
//
// Harness mirrors RemoveConfirmStageCTest (Unconfined Main + IO poll).
// Fixture is call-order scripted (ScriptedGateway pattern: every listUsers
// call consumes one snap entry, every identity call consumes one ident
// entry; exhausted scripts repeat the last value fail-safe) -- never a
// global R+N threshold. Scripts are refilled AFTER init settles, so each
// refresh phase consumes exactly: 1 snap + 3 idents + 1 packages.
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
                CmdResult(exit = 0, out = usersOut(nextSnap()), err = "")
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
                CmdResult(exit = 0, out = if (spec.id < 0) "" else "${spec.id}\n", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val uid = userOfListCmd(cmd)
                listPackagesCalls++
                val pkgs = pkgsByUser[uid] ?: emptySet()
                CmdResult(exit = 0, out = pkgs.map { "package:$it" }.joinToString("\n"), err = "")
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
    // FRESH(95, 15), consuming exactly one snapshot + one triple + one
    // list probe (5 commands) -- never a second snapshot for re-verify.
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
        assertEquals("refresh must take exactly one identity triple", 3, gw.identCalls - identsBefore)
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
}
