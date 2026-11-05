// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-5 P2 (issue #25): Auto probe-based degrade -- ViewModel + pure matrix.
// Fake probe matrix (no device, no Android runtime):
// - su denied (id -u != 0) + Shizuku proven -> degrade to SHIZUKU with a note.
// - both probes failing -> NOOP read-only.
// - both succeeding -> ROOT (F4a order, no degrade note).
// - explicit SHIZUKU never crosses to ROOT even when ROOT is proven.
//
// Harness mirrors RemoveConfirmTest: DualViewModel runs on viewModelScope
// (Dispatchers.Main replaced with Unconfined), repo I/O on Dispatchers.IO,
// tests poll StateFlow with a timeout.
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
class AutoDegradeTest {
    // Fake su gateway: rootOk=true proves ROOT via `id -u -> 0`; false denies
    // (exit 0 / non-zero uid, i.e. "su exists but refused").
    private class FakeSu(val rootOk: Boolean, var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS ->
                if (rootOk) CmdResult(exit = 0, out = usersOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied: su refused")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm list packages") ->
                CmdResult(exit = 0, out = "", err = "")
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    // Fake Shizuku gateway: shizukuOk=true proves SHELL via `pm list users`.
    private class FakeShizuku(val shizukuOk: Boolean, var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult = when {
            cmd == DualRepository.CMD_LIST_USERS ->
                if (shizukuOk) CmdResult(exit = 0, out = usersOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied: not authorized")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                if (shizukuOk) CmdResult(exit = 0, out = verboseOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                if (shizukuOk) CmdResult(exit = 0, out = dumpsysOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                if (shizukuOk) CmdResult(exit = 0, out = "$dualId\n", err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            cmd.startsWith("pm list packages") ->
                if (shizukuOk) CmdResult(exit = 0, out = "", err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            else -> if (shizukuOk) CmdResult(exit = 0, out = "ok", err = "")
            else CmdResult(exit = 1, out = "", err = "denied")
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

    private fun makeAutoVm(
        suFake: FakeSu,
        shizukuFake: FakeShizuku,
        suPresent: Boolean,
        shizukuAuth: Boolean,
        initialChannel: ResolvedChannel,
        initialGateway: CmdGateway,
    ): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(initialGateway),
            gatewayPrivilege = initialGateway.privilege,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { suPresent },
            shizukuAuthCheck = { shizukuAuth },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = initialGateway,
            currentChannel = initialChannel,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        return vm
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "timeout waiting for condition: state=${vms.lastOrNull()?.state?.value}",
                )
            }
            Thread.sleep(20)
        }
    }

    // Pure matrix: su denied + Shizuku proven -> SHIZUKU with a degrade note.
    @Test
    fun pure_suDeniedShizukuOk_degrades() {
        val d = resolveAutoProbe(suPresent = true, hasRoot = false, hasShizuku = true)
        assertEquals(ResolvedChannel.SHIZUKU, d.channel)
        assertTrue("degrade note must record the reason, got: \"${d.degradeNote}\"", d.degradeNote.contains("降級"))
    }

    // Pure matrix: both probes failing -> NOOP read-only with a note.
    @Test
    fun pure_bothFail_readOnly() {
        val d = resolveAutoProbe(suPresent = true, hasRoot = false, hasShizuku = false)
        assertEquals(ResolvedChannel.NOOP, d.channel)
        assertTrue("read-only note expected, got: \"${d.degradeNote}\"", d.degradeNote.contains("唯讀"))
    }

    // Pure matrix: both succeeding -> ROOT (F4a su-first order, no degrade).
    @Test
    fun pure_bothSucceed_prefersRoot() {
        val d = resolveAutoProbe(suPresent = true, hasRoot = true, hasShizuku = true)
        assertEquals(ResolvedChannel.ROOT, d.channel)
        assertTrue("no degrade note when ROOT is proven, got: \"${d.degradeNote}\"", d.degradeNote.isEmpty())
    }

    // su denied + Shizuku proven -> ViewModel degrades to SHIZUKU, writable.
    @Test
    fun auto_suDeniedShizukuOk_degradesToShizuku() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        // Startup heuristic with su present picks ROOT; the first probe must
        // correct it to SHIZUKU.
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
        )
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU }

        val s = vm.state.value
        assertEquals(ChannelPreference.AUTO, s.channel)
        assertEquals(ResolvedChannel.SHIZUKU, s.resolvedGateway)
        assertEquals(Privilege.SHELL, s.execIdentity)
        assertTrue("degraded SHIZUKU must be writable", s.writeReady)
        assertTrue("degraded SHIZUKU must keep channelReady for compat", s.channelReady)
        assertTrue("device proved a channel", s.readCap)
        assertTrue("degrade note must record the reason, got: \"${s.degradeNote}\"", s.degradeNote.contains("降級"))
    }

    // Both probes failing -> NOOP read-only.
    @Test
    fun auto_bothFail_readOnly() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
        )
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.NOOP }

        val s = vm.state.value
        assertEquals(ResolvedChannel.NOOP, s.resolvedGateway)
        assertEquals(Privilege.NONE, s.execIdentity)
        assertFalse("NOOP must never be writable", s.writeReady)
        assertFalse("NOOP must never be channelReady", s.channelReady)
        assertFalse("no proven channel", s.readCap)
        assertTrue("read-only note expected, got: \"${s.degradeNote}\"", s.degradeNote.contains("唯讀"))
    }

    // Both succeeding -> ROOT (preference order, no degrade note).
    @Test
    fun auto_bothSucceed_staysRoot() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }

        val s = vm.state.value
        assertEquals(ResolvedChannel.ROOT, s.resolvedGateway)
        assertEquals(Privilege.ROOT, s.execIdentity)
        assertTrue(s.writeReady)
        assertTrue(s.readCap)
        assertTrue("no degrade when ROOT is proven, got: \"${s.degradeNote}\"", s.degradeNote.isEmpty())
    }

    // Explicit SHIZUKU never crosses to ROOT even when ROOT is proven: the
    // resolved gateway stays SHIZUKU with SHELL identity (UI must not claim
    // "正在使用 ROOT").
    @Test
    fun explicit_shizukuNeverCrossesToRoot() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = DualViewModel(
            repo = DualRepository(shizukuFake),
            gatewayPrivilege = Privilege.SHELL,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.SHIZUKU,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = shizukuFake,
            currentChannel = ResolvedChannel.SHIZUKU,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        await { !vm.state.value.loading && vm.state.value.writeReady }

        val s = vm.state.value
        assertEquals(ChannelPreference.SHIZUKU, s.channel)
        assertEquals(ResolvedChannel.SHIZUKU, s.resolvedGateway)
        assertEquals(Privilege.SHELL, s.execIdentity)
        assertTrue("explicit SHIZUKU with proven SHELL must be writable", s.writeReady)
        assertTrue("explicit choice clears the Auto degrade note", s.degradeNote.isEmpty())
        // The routed device summary may still be ROOT (both channels proven),
        // but the execution identity must stay SHELL.
        assertEquals(Privilege.ROOT, s.privilege)
    }
}
