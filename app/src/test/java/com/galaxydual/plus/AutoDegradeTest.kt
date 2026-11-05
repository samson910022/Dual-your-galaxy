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
import com.galaxydual.hook.HookMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoDegradeTest {
    // Fake su gateway: rootOk=true proves ROOT via `id -u -> 0`; false denies
    // (exit 0 / non-zero uid, i.e. "su exists but refused"). Mutable so a
    // test can flip the probe outcome mid-flight (degrade path).
    private class FakeSu(var rootOk: Boolean, var dualId: Int = 95) : CmdGateway {
        // P25 Item 4: split `id -u` (auth) from `pm list users` (readability).
        // Null (default) preserves the legacy coupling (pm follows rootOk);
        // setting pmListOk=false with rootOk=true models counter-example A:
        // `id -u == 0` proves the probe while PM stays unreadable.
        var pmListOk: Boolean? = null
        override val privilege: Privilege = Privilege.ROOT

        // Counts `id -u` (su auth-risk probe) vs `pm list users` separately so
        // P25 Item 3 can bound unrelated-channel probes (explicit SHIZUKU must
        // never run `su -c id -u`).
        @Volatile var idUCalls = 0
        // Counts `pm list users` calls so tests can prove a re-probe ran.
        @Volatile var listUsersCalls = 0
        @Volatile var uninstallCalls = 0
        var uninstallOut = "Success"
        var uninstallExit = 0
        // Stateful installed set (mirrors RemoveConfirmTest FakeRepoGateway):
        // verifyState reads `pm list packages` ground truth. Default contains
        // the chat pkg used by the pending tests.
        val installedPkgs = mutableSetOf("com.example.chat")
        // Agreed platform serial for the dual profile on both verbose
        // channels (serial binding); noSerial omits both serial lines.
        var serial: Int = 15
        var noSerial: Boolean = false
        // Phase A P2: marker content served via `cat` for hook-diagnosis tests.
        // Null (default) preserves the legacy "ok" (no scopes); setting it
        // makes `cat <marker path>` return parseable marker text so a cached
        // verified ROOT channel can diagnose ACTIVE without new `id -u` probes.
        var markerContent: String? = null

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                (if (noSerial) "" else "    Serial: $serial\n") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400}" + (if (noSerial) "" else " serialNo=$serial") + ":\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        private fun listPackagesOut(): String =
            installedPkgs.map { "package:$it" }.joinToString("\n")

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" -> {
                idUCalls++
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            }
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls++
                val pmOk = pmListOk ?: rootOk
                if (pmOk) CmdResult(exit = 0, out = usersOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied: su refused")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm uninstall") -> {
                uninstallCalls++
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                if (uninstallExit == 0) installedPkgs.remove(pkg)
                CmdResult(exit = uninstallExit, out = uninstallOut, err = "")
            }
            cmd.startsWith("pm list packages") ->
                CmdResult(exit = 0, out = listPackagesOut(), err = "")
            cmd.startsWith("cat ") -> {
                // Phase A P2: serve marker content for hook diagnosis; null
                // keeps the legacy "ok" (no scopes). `cat` never touches
                // idUCalls/listUsersCalls, so zero-probe assertions stay valid.
                val content = markerContent
                if (content != null) CmdResult(exit = 0, out = content, err = "")
                else CmdResult(exit = 0, out = "ok", err = "")
            }
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    // Fake Shizuku gateway: shizukuOk=true proves SHELL via `pm list users`.
    private class FakeShizuku(val shizukuOk: Boolean, var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL

        // Counts `pm list users` calls so tests can prove a re-probe ran.
        @Volatile var listUsersCalls = 0
        @Volatile var uninstallCalls = 0
        var uninstallOut = "Success"
        var uninstallExit = 0
        // Stateful installed set (mirrors RemoveConfirmTest FakeRepoGateway):
        // verifyState reads `pm list packages` ground truth.
        val installedPkgs = mutableSetOf("com.example.chat")
        // Agreed platform serial for the dual profile on both verbose
        // channels (serial binding); noSerial omits both serial lines.
        var serial: Int = 15
        var noSerial: Boolean = false

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                (if (noSerial) "" else "    Serial: $serial\n") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400}" + (if (noSerial) "" else " serialNo=$serial") + ":\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        private fun listPackagesOut(): String =
            installedPkgs.map { "package:$it" }.joinToString("\n")

        override suspend fun run(cmd: String): CmdResult = when {
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls++
                if (shizukuOk) CmdResult(exit = 0, out = usersOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied: not authorized")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                if (shizukuOk) CmdResult(exit = 0, out = verboseOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                if (shizukuOk) CmdResult(exit = 0, out = dumpsysOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                if (shizukuOk) CmdResult(exit = 0, out = "$dualId\n", err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            cmd.startsWith("pm uninstall") -> {
                if (!shizukuOk) CmdResult(exit = 1, out = "", err = "denied")
                else {
                    uninstallCalls++
                    val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                    if (uninstallExit == 0) installedPkgs.remove(pkg)
                    CmdResult(exit = uninstallExit, out = uninstallOut, err = "")
                }
            }
            cmd.startsWith("pm list packages") ->
                if (shizukuOk) CmdResult(exit = 0, out = listPackagesOut(), err = "")
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
        preference: ChannelPreference = ChannelPreference.AUTO,
        hookDirectReads: () -> List<MarkerRead> =
            { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
        hookDiagnosticGateways: () -> List<CmdGateway> = { emptyList() },
        lastVerifiedHasRoot: Boolean = false,
        lastVerifiedHasShizuku: Boolean = false,
    ): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(initialGateway),
            gatewayPrivilege = initialGateway.privilege,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = preference,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { suPresent },
            shizukuAuthCheck = { shizukuAuth },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = initialGateway,
            currentChannel = initialChannel,
            lastVerifiedHasRoot = lastVerifiedHasRoot,
            lastVerifiedHasShizuku = lastVerifiedHasShizuku,
            hookDirectReads = hookDirectReads,
            hookDiagnosticGateways = hookDiagnosticGateways,
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
        assertTrue("device proved a channel", s.anyProbePassed)
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
        assertFalse("no proven channel", s.anyProbePassed)
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
        assertTrue(s.anyProbePassed)
        assertTrue("no degrade when ROOT is proven, got: \"${s.degradeNote}\"", s.degradeNote.isEmpty())
    }

    // Explicit SHIZUKU never crosses to ROOT even when ROOT is proven: the
    // resolved gateway stays SHIZUKU with SHELL identity (UI must not claim
    // "正在使用 ROOT").
    // P25 Item 3: explicit probes only its own channel, so the su elevator is
    // never probed here (suFake.idUCalls stays 0) and the routed summary
    // reflects the probed subset (SHELL), never an unprobed unrelated channel.
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
        // P25 Item 3: routed reflects only the probed (selected) channel --
        // su was intentionally never probed, so this is SHELL, not ROOT.
        // Execution identity stays SHELL either way.
        assertEquals(Privilege.SHELL, s.privilege)
        assertEquals("explicit SHIZUKU must never run `su -c id -u`", 0, suFake.idUCalls)
    }

    // Phase 2: no su binary + Shizuku proven -> SHIZUKU with NO degrade note
    // (Shizuku is the natural pick, not a fallback).
    @Test
    fun pure_noSuNoShizukuOk_noDegradeNote() {
        val d = resolveAutoProbe(suPresent = false, hasRoot = false, hasShizuku = true)
        assertEquals(ResolvedChannel.SHIZUKU, d.channel)
        assertTrue(
            "no su present so SHIZUKU is the natural pick, note must be empty, " +
                "got: \"${d.degradeNote}\"",
            d.degradeNote.isEmpty(),
        )
    }

    // Phase 2: no su binary + both probes failing -> NOOP read-only with the
    // second (no-su) copy, distinct from the su-denied copy.
    @Test
    fun pure_noSuBothFail_secondCopy() {
        val d = resolveAutoProbe(suPresent = false, hasRoot = false, hasShizuku = false)
        assertEquals(ResolvedChannel.NOOP, d.channel)
        assertTrue("read-only note expected, got: \"${d.degradeNote}\"", d.degradeNote.contains("唯讀"))
        assertTrue(
            "must be the no-su copy, got: \"${d.degradeNote}\"",
            d.degradeNote.contains("su 不存在"),
        )
    }

    // Phase 2: ROOT proven alone (su present, Shizuku denied) -> ROOT, no note.
    @Test
    fun pure_rootOnly_staysRoot() {
        val d = resolveAutoProbe(suPresent = true, hasRoot = true, hasShizuku = false)
        assertEquals(ResolvedChannel.ROOT, d.channel)
        assertTrue(
            "no degrade note when ROOT is proven, got: \"${d.degradeNote}\"",
            d.degradeNote.isEmpty(),
        )
    }

    // Phase 2: the Auto probe matrix must never select the LSPosed placeholder
    // (hook channel is never auto-selected); exhaustive over all 8 combos.
    @Test
    fun pure_neverLsposed() {
        for (suPresent in listOf(false, true)) {
            for (hasRoot in listOf(false, true)) {
                for (hasShizuku in listOf(false, true)) {
                    val d = resolveAutoProbe(
                        suPresent = suPresent,
                        hasRoot = hasRoot,
                        hasShizuku = hasShizuku,
                    )
                    assertTrue(
                        "auto probe must never select the LSPosed placeholder " +
                            "(suPresent=$suPresent, hasRoot=$hasRoot, " +
                            "hasShizuku=$hasShizuku), got ${d.channel}",
                        d.channel != ResolvedChannel.LSPOSED_PLACEHOLDER,
                    )
                }
            }
        }
    }

    // Phase 2 (mirror of explicit_shizukuNeverCrossesToRoot): explicit ROOT +
    // only Shizuku proven -> stays ROOT, fail closed, never crosses to SHIZUKU.
    // P25 Item 3: explicit ROOT probes only su, so the proven-but-unprobed
    // Shizuku is intentionally invisible (anyProbePassed false, routed NONE).
    @Test
    fun explicit_rootNeverCrossesToShizuku() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = false,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.ROOT,
        )
        await { !vm.state.value.loading && vm.state.value.msg.contains("明選不跨通道") }

        val s = vm.state.value
        assertEquals(ChannelPreference.ROOT, s.channel)
        assertEquals(ResolvedChannel.ROOT, s.resolvedGateway)
        assertEquals(Privilege.ROOT, s.execIdentity)
        assertFalse("explicit ROOT with denied su must not be writable", s.writeReady)
        assertFalse("explicit ROOT with denied su must not be channelReady", s.channelReady)
        assertTrue("explicit choice clears the Auto degrade note", s.degradeNote.isEmpty())
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        // P25 Item 3: Shizuku was intentionally never probed (explicit ROOT
        // probes su only), so anyProbePassed reflects the probed subset only.
        assertFalse("unprobed Shizuku must not count toward anyProbePassed", s.anyProbePassed)
        assertEquals(Privilege.NONE, s.privilege)
        assertEquals("explicit ROOT must not probe Shizuku", 0, shizukuFake.listUsersCalls)
    }

    // Phase 2 (sharpest explicit isolation, missing from Phase 1): explicit
    // SHIZUKU + only ROOT proven -> stays SHIZUKU, fail closed, never crosses
    // to ROOT. P25 Item 3: su is intentionally never probed, so routed is NONE
    // (not ROOT) and su auth is never triggered.
    @Test
    fun explicit_shizukuOnlyRootProven_staysShizuku() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.SHIZUKU,
            initialGateway = shizukuFake,
            preference = ChannelPreference.SHIZUKU,
        )
        await { !vm.state.value.loading && vm.state.value.msg.contains("明選不跨通道") }

        val s = vm.state.value
        assertEquals(ChannelPreference.SHIZUKU, s.channel)
        assertEquals(ResolvedChannel.SHIZUKU, s.resolvedGateway)
        assertEquals(Privilege.SHELL, s.execIdentity)
        assertFalse("explicit SHIZUKU with denied Shizuku must not be writable", s.writeReady)
        assertFalse("explicit SHIZUKU with denied Shizuku must not be channelReady", s.channelReady)
        assertTrue("explicit choice clears the Auto degrade note", s.degradeNote.isEmpty())
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertEquals(Privilege.NONE, s.privilege)
        assertFalse("unprobed su must not count toward anyProbePassed", s.anyProbePassed)
        assertEquals("explicit SHIZUKU must never run `su -c id -u`", 0, suFake.idUCalls)
    }

    // Phase 2: after su-denied + Shizuku-ok degrade, refresh() must see the
    // switched repo (profiles load, "no clones installed"), proving the repo
    // really moved to the Shizuku fake. Counter-example: an unswitched repo
    // would report ChannelError ("denied: su refused").
    @Test
    fun auto_repoFollowsDegrade() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        // This fixture wants the empty-listing branch; the fakes default to
        // installed (for the requestRemove pending tests), so clear here.
        shizukuFake.installedPkgs.clear()
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
        )
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU }

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msg.contains("no clones installed") &&
                vm.state.value.msg.contains("降級")
        }

        val s = vm.state.value
        assertEquals(ResolvedChannel.SHIZUKU, s.resolvedGateway)
        assertEquals(Privilege.SHELL, s.execIdentity)
        assertEquals(MsgKind.INFO, s.msgKind)
        assertTrue("degraded SHIZUKU refresh must list profiles, got: ${s.profiles}", s.profiles.any { it.userId == 95 })
        assertTrue("degraded SHIZUKU must stay writable", s.writeReady)
        assertTrue("degrade note must survive refresh, got: \"${s.degradeNote}\"", s.degradeNote.contains("降級"))
        assertTrue(
            "refresh INFO must carry the degrade note, got: \"${s.msg}\"",
            s.msg.contains("no clones installed") && s.msg.contains("降級"),
        )
    }

    // Phase 2: a re-probe that does NOT swap (already SHIZUKU, still SHIZUKU)
    // keeps pendingRemove and its confirmation message (NONE/INFO preserved).
    // listUsersCalls proves the second probe actually ran (state is otherwise
    // identical, so awaiting on state alone could pass trivially).
    @Test
    fun probe_pendingPreservedOnDegradeInfo() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.SHIZUKU,
            initialGateway = shizukuFake,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null && !vm.state.value.loading }
        val pending = vm.state.value.pendingRemove
        assertNotNull("request must set pending", pending)
        val msgBefore = vm.state.value.msg
        assertTrue("confirmation message expected, got: \"$msgBefore\"", msgBefore.contains("等待確認"))
        assertEquals(MsgKind.INFO, vm.state.value.msgKind)

        val probesBefore = shizukuFake.listUsersCalls
        vm.recheckPrivilege()
        await { !vm.state.value.loading && shizukuFake.listUsersCalls > probesBefore }

        val s = vm.state.value
        assertEquals(ResolvedChannel.SHIZUKU, s.resolvedGateway)
        assertEquals("no swap means the pending survives", pending, s.pendingRemove)
        assertEquals("NONE/INFO probe must not wash the confirm message", msgBefore, s.msg)
        assertEquals(MsgKind.INFO, s.msgKind)
    }

    // Phase 2: an AUTO degrade swap (ROOT -> SHIZUKU) fail-closes the stale
    // pending: the verified id/label snapshot belongs to the old channel, so
    // the user must request again after the swap.
    @Test
    fun auto_degradeSwap_clearsPending() {
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

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null && !vm.state.value.loading }

        // Deny su now: the next probe degrades ROOT -> SHIZUKU.
        suFake.rootOk = false
        vm.recheckPrivilege()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU &&
                vm.state.value.pendingRemove == null
        }

        val s = vm.state.value
        assertNull("degrade swap must drop the stale pending (fail closed)", s.pendingRemove)
        assertTrue("degrade note must record the reason, got: \"${s.degradeNote}\"", s.degradeNote.contains("降級"))
        assertTrue("degraded SHIZUKU must be writable", s.writeReady)
    }

    // Phase 2: after an AUTO degrade, switching to an explicit channel clears
    // both the degrade note and any pending (repo/gateway rebuilt, old
    // snapshot invalid).
    //
    // Harness note: selectChannel() rebuilds REAL gateways, so the trailing
    // probe/refresh runs host `su -c`. Under a Gradle worker (stdin is an
    // open pipe, no tty) `su` blocks reading a password up to
    // SU_EXEC_TIMEOUT_MS instead of failing fast, so awaiting full probe
    // completion here is environment-hostage. The object under test does not
    // need it: swapToLocked() applies channel + cleared degradeNote/pending
    // in ONE synchronous _state.update before the first probe suspension
    // (Unconfined runs it on the caller thread), so awaiting only the
    // channel flip is deterministic; probe/refresh outcomes on real
    // gateways are deliberately not asserted.
    @Test
    fun selectChannel_clearsDegradeNoteAndPending() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
        )
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU }
        assertTrue(
            "precondition: AUTO must have degraded, got: \"${vm.state.value.degradeNote}\"",
            vm.state.value.degradeNote.contains("降級"),
        )

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null && !vm.state.value.loading }

        vm.selectChannel(ChannelPreference.ROOT)
        await { vm.state.value.channel == ChannelPreference.ROOT }

        val s = vm.state.value
        assertEquals(ChannelPreference.ROOT, s.channel)
        assertTrue("explicit choice clears the Auto degrade note, got: \"${s.degradeNote}\"", s.degradeNote.isEmpty())
        assertNull("channel switch must drop the stale pending (fail closed)", s.pendingRemove)
    }

    // P25 Item 1: probe -> refresh must keep the degrade reason visible.
    // su denied + Shizuku proven degrades to SHIZUKU; after
    // reconnectAndRefresh() the refresh INFO ("no clones installed") must be
    // combined with the degrade note, not overwritten by it.
    @Test
    fun reconnect_degradeSurvivesRefresh_combinedMsg() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        // Empty listing so the refresh base is the neutral INFO branch.
        shizukuFake.installedPkgs.clear()
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
        )
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU }

        vm.reconnectAndRefresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msg.contains("no clones installed") &&
                vm.state.value.msg.contains("降級")
        }

        val s = vm.state.value
        assertEquals(ChannelPreference.AUTO, s.channel)
        assertEquals(ResolvedChannel.SHIZUKU, s.resolvedGateway)
        assertTrue("degradeNote must survive refresh, got: \"${s.degradeNote}\"", s.degradeNote.contains("降級"))
        assertTrue(
            "refresh INFO must combine with degrade note, got: \"${s.msg}\"",
            s.msg.contains("no clones installed") && s.msg.contains("降級"),
        )
        assertEquals(MsgKind.INFO, s.msgKind)
        assertTrue("degraded SHIZUKU must stay writable", s.writeReady)
        // channelView short-tag condition (MainActivity appends
        // "已自 Auto 降級，詳見狀態列" exactly when degradeNote is non-empty).
        assertTrue(
            "channelView must show the degrade short tag (degradeNote non-empty)",
            s.degradeNote.isNotEmpty(),
        )
        assertTrue("refresh must list profiles, got: ${s.profiles}", s.profiles.any { it.userId == 95 })
    }

    // P25 Item 1 (error priority): both probes failing degrades to NOOP
    // read-only; the NoOp repo cannot list profiles so refresh ends as
    // CHANNEL error. The error must win (pass through, not covered by the
    // degrade note), while the degrade reason stays in degradeNote +
    // channelView short tag.
    @Test
    fun reconnect_noopDegradeRefresh_keepsErrorAndNote() {
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
        assertTrue(
            "precondition: AUTO must have degraded to NOOP, got: \"${vm.state.value.degradeNote}\"",
            vm.state.value.degradeNote.contains("唯讀"),
        )

        vm.reconnectAndRefresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.NOOP &&
                vm.state.value.msg.contains("channel error")
        }

        val s = vm.state.value
        assertEquals(ResolvedChannel.NOOP, s.resolvedGateway)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertTrue(
            "error must pass through (not covered by degrade), got: \"${s.msg}\"",
            s.msg.contains("channel error"),
        )
        assertTrue("degradeNote must survive refresh, got: \"${s.degradeNote}\"", s.degradeNote.contains("唯讀"))
        assertFalse("NOOP must never be writable", s.writeReady)
        // channelView short-tag condition: degradeNote non-empty under AUTO.
        assertEquals(ChannelPreference.AUTO, s.channel)
        assertTrue(
            "channelView must show the degrade short tag (degradeNote non-empty)",
            s.degradeNote.isNotEmpty(),
        )
    }

    // P25 Item 3: AUTO each round probes at most once -- swap result reused by
    // probeChannels, no double `su -c id -u`. Stable ROOT: initial probe +
    // one recheck + one reconnect = exactly one `id -u` per round.
    @Test
    fun auto_eachRound_probesOnce() {
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

        suFake.idUCalls = 0
        val shizukuProbesBefore = shizukuFake.listUsersCalls
        vm.recheckPrivilege()
        await { !vm.state.value.loading && suFake.idUCalls >= 1 }
        // Settle: a stray second probe would flip loading busy again; require
        // quiescence before asserting the upper bound.
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("AUTO recheck must probe su exactly once, got ${suFake.idUCalls}", 1, suFake.idUCalls)
        assertTrue("AUTO must still probe Shizuku (full probe)", shizukuFake.listUsersCalls > shizukuProbesBefore)

        suFake.idUCalls = 0
        vm.reconnectAndRefresh()
        await { !vm.state.value.loading && suFake.idUCalls >= 1 }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("AUTO reconnect must probe su exactly once, got ${suFake.idUCalls}", 1, suFake.idUCalls)
    }

    // P25 Item 3: explicit SHIZUKU never triggers su -- no `id -u`, no
    // `su -c` auth prompt, even across recheck/reconnect rounds. Shizuku
    // itself is still probed (writeReady proves it).
    @Test
    fun explicit_shizuku_neverRunsSu() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.SHIZUKU,
            initialGateway = shizukuFake,
            preference = ChannelPreference.SHIZUKU,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        assertEquals("init probe must not touch su", 0, suFake.idUCalls)

        vm.recheckPrivilege()
        await { !vm.state.value.loading && shizukuFake.listUsersCalls >= 2 }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("explicit SHIZUKU recheck must never run `su -c id -u`", 0, suFake.idUCalls)

        vm.reconnectAndRefresh()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("explicit SHIZUKU reconnect must never run `su -c id -u`", 0, suFake.idUCalls)
        assertTrue("selected Shizuku channel must stay writable", vm.state.value.writeReady)
    }

    // P25 Item 3: explicit LSPOSED (NoOp placeholder) probes no true channel --
    // hook diagnosis runs via direct reads only, never `su -c id -u`.
    @Test
    fun explicit_lsposed_probesNoTrueChannel() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            initialGateway = NoOpGateway(),
            preference = ChannelPreference.LSPOSED,
        )
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        assertEquals("explicit LSPOSED must never run `su -c id -u`", 0, suFake.idUCalls)
        assertEquals("explicit LSPOSED must never probe Shizuku", 0, shizukuFake.listUsersCalls)
        assertFalse("NoOp placeholder is never writable", vm.state.value.writeReady)
        assertFalse("NoOp placeholder is never channelReady", vm.state.value.channelReady)
        assertFalse("LSPOSED probes none, so no probe passes", vm.state.value.anyProbePassed)
    }

    // P25 Item 4 (counter-example A): `id -u == 0` proves the su probe while
    // `pm list users` still fails. anyProbePassed is true (probe signal) but
    // the PM list is unreadable: refresh must report ChannelError. The flag
    // therefore never claims PM readability; readability is decided by
    // refresh (profilesSnapshot) and writeReady is only the probe gate.
    @Test
    fun anyProbePassed_rootIdOkPmFail_doesNotImplyReadable() {
        val suFake = FakeSu(rootOk = true)
        suFake.pmListOk = false
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
        )
        await { !vm.state.value.loading && vm.state.value.anyProbePassed }

        val probed = vm.state.value
        assertTrue("su `id -u == 0` proves the probe", probed.anyProbePassed)
        // Probe gate only: `id -u` passed, so the selected channel counts as
        // proven even though `pm list users` is unreadable. This true value
        // must not be read as "PM readable".
        assertTrue("probe gate passes on `id -u == 0`", probed.writeReady)
        assertEquals(Privilege.ROOT, probed.privilege)

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msgKind == MsgKind.CHANNEL &&
                vm.state.value.msg.contains("channel error")
        }

        val s = vm.state.value
        assertTrue("probe flag stays true while PM is unreadable", s.anyProbePassed)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertTrue("PM unreadable must surface as channel error, got: \"${s.msg}\"", s.msg.contains("channel error"))
        assertTrue("unreadable PM lists no profiles", s.profiles.isEmpty())
    }

    // P25 Item 4 (counter-example B): the environment holds capabilities
    // (both su and Shizuku would prove) but explicit LSPOSED probes none, so
    // anyProbePassed stays false and the NoOp repo stays unreadable. The flag
    // reflects only the selected subset, never the unprobed environment; PM
    // readability is decided by refresh (ChannelError here).
    @Test
    fun anyProbePassed_explicitLsposedEnvCapable_staysFalseAndUnreadable() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = true,
            initialChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            initialGateway = NoOpGateway(),
            preference = ChannelPreference.LSPOSED,
        )
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        val probed = vm.state.value
        assertFalse("LSPOSED probes none, so no probe passes despite env capability", probed.anyProbePassed)
        assertFalse("NoOp placeholder is never writable", probed.writeReady)
        assertEquals(Privilege.NONE, probed.privilege)
        assertEquals("explicit LSPOSED must never run `su -c id -u`", 0, suFake.idUCalls)
        assertEquals("explicit LSPOSED must never probe Shizuku", 0, shizukuFake.listUsersCalls)

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msgKind == MsgKind.CHANNEL &&
                vm.state.value.msg.contains("channel error")
        }

        val s = vm.state.value
        assertFalse("probe flag stays false for the unprobed subset", s.anyProbePassed)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertTrue("placeholder repo is unreadable, got: \"${s.msg}\"", s.msg.contains("channel error"))
    }

    // Phase A P2 regression: LSPOSED diagnosis reuses the AUTO-verified cache
    // without new probes. Simulates "AUTO全成功後切LSPOSED": direct reads are
    // denied (app-uid EACCES), but the cache holds proven ROOT (injected here
    // to stand in for the prior AUTO full success without rebuilding real
    // gateways via selectChannel), and the cached ROOT channel serves the
    // marker via `cat`. LSPOSED must then diagnose ACTIVE while write-readiness
    // stays refused and no new `su -c id -u` probe runs. Cold-start LSPOSED
    // (empty cache) stays UNKNOWN -- see HookDiagnosisTest direct-denied case.
    @Test
    fun explicit_lsposed_usesVerifiedCacheForDiagnosisWithoutNewProbe() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.LSPOSED_PLACEHOLDER,
            initialGateway = NoOpGateway(),
            preference = ChannelPreference.LSPOSED,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = { listOf(suFake, shizukuFake) },
            // Manual cache: stands in for a prior AUTO full success that proved ROOT.
            lastVerifiedHasRoot = true,
            lastVerifiedHasShizuku = false,
        )
        await { !vm.state.value.loading && vm.state.value.hookStatus == HookStatus.ACTIVE }

        val s = vm.state.value
        assertEquals("cached ROOT must diagnose ACTIVE via `cat`", HookStatus.ACTIVE, s.hookStatus)
        assertTrue("ACTIVE diagnosis must set hookActive", s.hookActive)
        assertTrue(
            "marker history must be visible, got: ${s.hookScopes}",
            s.hookScopes.contains(HookMarker.SCOPE_SYSTEM_SERVER),
        )
        assertFalse("NoOp placeholder is never writable, even with cached diagnosis", s.writeReady)
        assertFalse("NoOp placeholder is never channelReady", s.channelReady)
        assertEquals(ResolvedChannel.LSPOSED_PLACEHOLDER, s.resolvedGateway)
        assertFalse("LSPOSED probes none, so no probe passes even with cache", s.anyProbePassed)
        assertEquals("LSPOSED diagnosis must not run `su -c id -u`", 0, suFake.idUCalls)
        assertEquals("LSPOSED diagnosis must not probe Shizuku", 0, shizukuFake.listUsersCalls)

        // A re-probe must not start new `su -c` auth either, and must keep ACTIVE.
        vm.recheckPrivilege()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("LSPOSED recheck must not run `su -c id -u`", 0, suFake.idUCalls)
        assertEquals(HookStatus.ACTIVE, vm.state.value.hookStatus)
        assertFalse(vm.state.value.writeReady)
    }
}
