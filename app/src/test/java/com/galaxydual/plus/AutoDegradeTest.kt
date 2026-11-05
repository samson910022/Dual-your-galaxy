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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        // Counts diagnostic `cat` (hook marker) so tests prove a (non-)use
        // of a gateway for diagnosis without conflating with idU probes.
        @Volatile var catCalls = 0
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
        // N1 Phase 1: `cat` obeys the auth gate. Null (default) preserves the
        // legacy coupling (cat follows rootOk); setting catOk=false with
        // rootOk=true models a `cat`-only denial, and rootOk=false always
        // denies `cat` (parked-revoke: retained `cat` must fail closed).
        var catOk: Boolean? = null

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
                // N1 Phase 1: `cat` obeys rootOk/catOk -- a revoked su must
                // fail `cat` (fail closed, maps to UNKNOWN via diagnosis).
                catCalls++
                val catAllowed = catOk ?: rootOk
                if (!catAllowed) {
                    CmdResult(exit = 1, out = "", err = "denied: su revoked")
                } else {
                    val content = markerContent
                    if (content != null) CmdResult(exit = 0, out = content, err = "")
                    else CmdResult(exit = 0, out = "ok", err = "")
                }
            }
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    // Fake Shizuku gateway: shizukuOk=true proves SHELL via `pm list users`.
    private class FakeShizuku(var shizukuOk: Boolean, var dualId: Int = 95) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL

        // Counts `pm list users` calls so tests can prove a re-probe ran.
        @Volatile var listUsersCalls = 0
        // Counts diagnostic `cat` so tests prove a (non-)use for diagnosis.
        // Mutable shizukuOk models binder dead/rebind (revocation): flipping
        // to false makes the next probe fail, which must rewrite the
        // shizuku evidence entry to false on the same instance.
        @Volatile var catCalls = 0
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
            cmd.startsWith("cat ") -> {
                catCalls++
                if (shizukuOk) CmdResult(exit = 0, out = markerContentForTest(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            }
            else -> if (shizukuOk) CmdResult(exit = 0, out = "ok", err = "")
            else CmdResult(exit = 1, out = "", err = "denied")
        }

        // Marker served via `cat` for hook-diagnosis tests. Null keeps the
        // legacy "ok" (no scopes); set to parseable content for ACTIVE.
        var markerContent: String? = null
        private fun markerContentForTest(): String = markerContent ?: "ok"
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
        // Null = use the ViewModel default { listOf(suGateway, shizukuGateway) }
        // so candidates TRACK rebuilds (production behavior for the new
        // instance-bound tests). Non-null keeps a fixed list (legacy tests).
        hookDiagnosticGateways: (() -> List<CmdGateway>)? = { emptyList() },
        assembleChannelFn: (
            ChannelPreference,
            Boolean,
            Boolean,
            String,
        ) -> ChannelAssembly = ::assembleChannel,
    ): DualViewModel {
        // Null hookDiagnosticGateways = use the ViewModel default
        // { listOf(suGateway, shizukuGateway) } so candidates TRACK rebuilds
        // (production behavior). An explicit lambda keeps a fixed list.
        val vm = if (hookDiagnosticGateways == null) {
            DualViewModel(
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
                assembleChannelFn = assembleChannelFn,
                hookDirectReads = hookDirectReads,
            )
        } else {
            DualViewModel(
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
                assembleChannelFn = assembleChannelFn,
                hookDirectReads = hookDirectReads,
                hookDiagnosticGateways = hookDiagnosticGateways,
            )
        }
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

    // P25 Item 1 (error priority, Phase B P2): both probes failing degrades to
    // NOOP read-only; the NoOp repo cannot list profiles so refresh ends as
    // CHANNEL error. The error stays first (kind CHANNEL) with the full
    // degrade reason appended ("；" join), so the user-visible status line
    // carries both the refresh failure and the dual-failure recovery note
    // (previously the error passed through alone and the reason lived only
    // in degradeNote + channelView short tag).
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
            "error stays first with kind CHANNEL, got: \"${s.msg}\"",
            s.msg.contains("channel error"),
        )
        assertTrue("degradeNote must survive refresh, got: \"${s.degradeNote}\"", s.degradeNote.contains("唯讀"))
        // Phase B P2 render/state acceptance: the combined status line must
        // carry the full degrade reason inline (not only in the degradeNote
        // field), error first with the recovery note appended.
        assertTrue(
            "combined msg must carry the full degradeNote inline, got: \"${s.msg}\"",
            s.degradeNote.isNotEmpty() && s.msg.contains(s.degradeNote),
        )
        assertTrue(
            "combined msg must carry the 唯讀 reason, got: \"${s.msg}\"",
            s.msg.contains("唯讀"),
        )
        assertTrue(
            "combined msg must carry the recovery entry, got: \"${s.msg}\"",
            s.msg.contains("重新連線並更新"),
        )
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

    // Phase B1 (diagnostic lifecycle split): AUTO -> LSPOSED via REAL
    // selectChannel + rebuild (new instances, production behavior). The swap
    // retains the still-usable OLD su instance as the diagnostic instance, so
    // LSPOSED diagnoses ACTIVE through the retained `cat` while writeReady
    // stays false (hook diagnosis never raises writeReady). The FRESH
    // instance is never probed or `cat`ed (no new `su -c` auth risk on the
    // new object); only the already-authorized retained instance serves
    // diagnosis. A later LSPOSED recheck keeps ACTIVE with zero probes.
    @Test
    fun autoToLsposed_rebuildRetainsVerifiedDiagnostic_activeWithoutProbingNewInstance() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suOld = FakeSu(rootOk = true)
        suOld.markerContent = marker
        val shizukuOld = FakeShizuku(shizukuOk = false)
        val createdSu = mutableListOf<FakeSu>()
        // Fresh instances every swap (production behavior): new identity.
        val rebuildFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val newSu = FakeSu(rootOk = true)
            newSu.markerContent = marker
            val newShizuku = FakeShizuku(shizukuOk = false)
            createdSu.add(newSu)
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> newSu
                ResolvedChannel.SHIZUKU -> newShizuku
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, newSu, newShizuku, channel)
        }
        val vm = makeAutoVm(
            suFake = suOld,
            shizukuFake = shizukuOld,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suOld,
            preference = ChannelPreference.AUTO,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            // Null = tracking (production): candidates follow rebuilds.
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        // Initial AUTO proves the OLD instance (writable + ACTIVE via its cat).
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        val oldCatBefore = suOld.catCalls
        assertTrue("precondition: old instance must have served `cat`", oldCatBefore > 0)
        val oldIdUBefore = suOld.idUCalls

        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                createdSu.isNotEmpty()
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertEquals(ChannelPreference.LSPOSED, s.channel)
        assertEquals(ResolvedChannel.LSPOSED_PLACEHOLDER, s.resolvedGateway)
        // The retained (already-authorized) instance keeps diagnosis alive:
        // direct denied + no usable current gateway, yet ACTIVE via the
        // retained `cat` -- never UNKNOWN, never a probe of the new instance.
        assertEquals("retained instance must diagnose ACTIVE under LSPOSED", HookStatus.ACTIVE, s.hookStatus)
        assertTrue(s.hookActive)
        assertTrue(
            "marker scopes must be visible, got: ${s.hookScopes}",
            s.hookScopes.contains(HookMarker.SCOPE_SYSTEM_SERVER),
        )
        assertFalse("NoOp placeholder is never writable", s.writeReady)
        assertFalse("NoOp placeholder is never channelReady", s.channelReady)
        assertFalse("LSPOSED probes none", s.anyProbePassed)
        val newSu = createdSu.first()
        assertEquals("new instance must never run `su -c id -u` before verification", 0, newSu.idUCalls)
        assertEquals("new instance must never be `cat`ed before verification", 0, newSu.catCalls)
        assertTrue(
            "diagnosis must ride the retained old instance, got ${suOld.catCalls} vs $oldCatBefore",
            suOld.catCalls > oldCatBefore,
        )
        assertEquals("switch must not run new `su -c id -u` probes", oldIdUBefore, suOld.idUCalls)

        // N1 Phase 1: LSPOSED AUTO recheck stays direct-only -- no retained
        // `cat`, no `id -u` anywhere, no `cat` on the fresh instance. The
        // retained instance diagnosed ACTIVE on the manual select above, but
        // the auto recheck must not re-exec it (fresh `su -c` process, auth
        // risk): hook falls back to UNKNOWN with writeReady false.
        val totalIdUBefore = suOld.idUCalls + newSu.idUCalls
        val retainedCatBeforeAuto = suOld.catCalls
        vm.recheckPrivilege()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "LSPOSED auto recheck must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
        assertEquals("LSPOSED auto recheck must run zero probes", totalIdUBefore, suOld.idUCalls + newSu.idUCalls)
        assertEquals(
            "LSPOSED auto recheck must not `cat` the retained instance",
            retainedCatBeforeAuto,
            suOld.catCalls,
        )
        assertEquals("fresh instance stays untouched across auto recheck", 0, newSu.idUCalls)
        assertEquals("fresh instance stays un-`cat`ed across auto recheck", 0, newSu.catCalls)
        // Manual opt-in restores ACTIVE through the retained instance (one
        // `cat`, still zero probes on the fresh instance, writeReady stays
        // false: diagnosis never raises readiness).
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(HookStatus.ACTIVE, vm.state.value.hookStatus)
        assertFalse(vm.state.value.writeReady)
        assertTrue(
            "manual diagnosis must ride the retained old instance",
            suOld.catCalls > retainedCatBeforeAuto,
        )
        assertEquals("manual diagnosis still runs zero `id -u` probes", totalIdUBefore, suOld.idUCalls + newSu.idUCalls)
        assertEquals("fresh instance stays untouched across manual diagnosis", 0, newSu.idUCalls)
        assertEquals("fresh instance stays un-`cat`ed across manual diagnosis", 0, newSu.catCalls)
    }

    // Phase A P2 (match branch) + B1 (retained slot): explicit ROOT writes
    // su evidence for the CURRENT instance via a real probe; switching to
    // LSPOSED with the SAME instances (factory reuses fakes) keeps
    // identity+generation equal, so LSPOSED diagnoses ACTIVE via that
    // verified `cat` (current match and retained slot agree, deduped to one
    // `cat`) while writeReady stays false (hook diagnosis never raises
    // writeReady). No new `id -u` runs on the LSPOSED path.
    @Test
    fun explicitRootToLsposed_sameInstance_diagnosesActiveWithoutRaisingWriteReady() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        // Reuse the same fakes across swaps (exercises the match branch).
        val reuseFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> suFake
                ResolvedChannel.SHIZUKU -> shizukuFake
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, suFake, shizukuFake, channel)
        }
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.ROOT,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        val idUBefore = suFake.idUCalls

        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertEquals("verified ROOT must diagnose ACTIVE under LSPOSED", HookStatus.ACTIVE, s.hookStatus)
        assertTrue(s.hookActive)
        assertTrue(
            "marker scopes must be visible, got: ${s.hookScopes}",
            s.hookScopes.contains(HookMarker.SCOPE_SYSTEM_SERVER),
        )
        assertFalse("NoOp placeholder is never writable, even with ACTIVE diagnosis", s.writeReady)
        assertFalse(s.channelReady)
        assertEquals(ResolvedChannel.LSPOSED_PLACEHOLDER, s.resolvedGateway)
        assertFalse("LSPOSED probes none", s.anyProbePassed)
        assertEquals("LSPOSED must not run `su -c id -u`", idUBefore, suFake.idUCalls)
        // N1 Phase 1: auto recheck stays direct-only (UNKNOWN, no `cat`);
        // manual opt-in restores ACTIVE. Diagnosis never raises writeReady.
        val catAfterSelect = suFake.catCalls
        vm.recheckPrivilege()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "LSPOSED auto recheck must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
        assertEquals(
            "LSPOSED auto recheck must not `cat` the retained instance",
            catAfterSelect,
            suFake.catCalls,
        )
        assertEquals("LSPOSED auto recheck must not run `su -c id -u`", idUBefore, suFake.idUCalls)
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(HookStatus.ACTIVE, vm.state.value.hookStatus)
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
        assertTrue("manual diagnosis must `cat` once", suFake.catCalls > catAfterSelect)
        assertEquals("manual diagnosis still runs zero `id -u` probes", idUBefore, suFake.idUCalls)
    }

    // Phase A P2 (revocation): ROOT proven then auth revoked (rootOk true ->
    // false) rewrites the SAME-instance entry to false on the next probe, so
    // a later LSPOSED round is UNKNOWN (no stale ACTIVE). Models Shizuku
    // binder dead/rebind the same way: a dead binding fails the next probe
    // and disables that entry until a passing probe re-enables it.
    @Test
    fun explicitRoot_revoked_clearsEvidence_lsposedUnknown() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val reuseFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> suFake
                ResolvedChannel.SHIZUKU -> shizukuFake
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, suFake, shizukuFake, channel)
        }
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.ROOT,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        val catBeforeRevoke = suFake.catCalls
        assertTrue(catBeforeRevoke > 0)

        // Revoke: next probe on the SAME instance fails and clears evidence.
        suFake.rootOk = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && !vm.state.value.writeReady }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertFalse("revoked ROOT must not be writable", vm.state.value.writeReady)

        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertTrue(
            "revoked evidence must yield UNKNOWN, got: ${s.hookStatus}",
            s.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                s.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(s.writeReady)
        assertFalse(s.channelReady)
        assertEquals(
            "revoked instance must not serve new `cat` for LSPOSED",
            catBeforeRevoke,
            suFake.catCalls,
        )
    }

    // Phase C P2: LSPOSED zero-probe renders 未探測, never 未通過. probeScope
    // tracks the probed subset (NONE here); privilege/anyProbePassed reflect
    // only that subset, never device total capability (env holds ROOT+SHELL
    // but both stay unprobed). Probe never writes pmReadable (null until
    // refresh); refresh through NoOp then proves PM unreadable (false) while
    // the scope stays NONE.
    @Test
    fun phaseC_lsposed_zeroProbe_scopeNoneUnprobed() {
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
        assertEquals(ProbeScope.NONE, probed.probeScope)
        assertEquals("zero probe must render 未探測", "未探測", probed.probeResultMark())
        assertTrue(
            "status key must carry 未探測, got: \"${probed.probeStatusText()}\"",
            probed.probeStatusText().contains("未探測"),
        )
        assertFalse(
            "zero probe must never render 未通過, got: \"${probed.probeStatusText()}\"",
            probed.probeStatusText().contains("未通過"),
        )
        assertFalse(probed.anyProbePassed)
        assertEquals(Privilege.NONE, probed.privilege)
        assertEquals("explicit LSPOSED must never run `su -c id -u`", 0, suFake.idUCalls)
        assertEquals("explicit LSPOSED must never probe Shizuku", 0, shizukuFake.listUsersCalls)
        assertNull("probe must never write pmReadable (still unknown)", probed.pmReadable)
        assertEquals("PM unknown before refresh", "PM未知", probed.pmReadableText())

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msgKind == MsgKind.CHANNEL &&
                vm.state.value.msg.contains("channel error")
        }

        val s = vm.state.value
        assertEquals(ProbeScope.NONE, s.probeScope)
        assertEquals("未探測", s.probeResultMark())
        assertEquals(false, s.pmReadable)
        assertEquals("PM不可讀", s.pmReadableText())
        assertFalse(s.anyProbePassed)
        assertFalse(s.writeReady)
    }

    // Phase C P2: AUTO full probe sets FULL scope; refresh Loaded proves PM
    // readable (true). Captures the pre-refresh null to lock probe/refresh
    // separation (probe never writes pmReadable).
    @Test
    fun phaseC_pmReadable_loadedTrue() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
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

        val probed = vm.state.value
        assertEquals(ProbeScope.FULL, probed.probeScope)
        assertEquals("探測通過", probed.probeResultMark())
        assertNull("probe must never write pmReadable", probed.pmReadable)

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msg.contains("no clones installed")
        }

        val s = vm.state.value
        assertEquals(ProbeScope.FULL, s.probeScope)
        assertEquals(true, s.pmReadable)
        assertEquals("PM可讀", s.pmReadableText())
        assertTrue("refresh must list profiles, got: ${s.profiles}", s.profiles.any { it.userId == 95 })
    }

    // Phase C P2: AUTO full probe with both failing sets FULL + 未通過 (not
    // 未探測 -- the subset was probed and failed); refresh ChannelError proves
    // PM unreadable (false).
    @Test
    fun phaseC_pmReadable_channelErrorFalse() {
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

        val probed = vm.state.value
        assertEquals(ProbeScope.FULL, probed.probeScope)
        assertEquals("probed-but-failed must render 未通過", "探測未通過", probed.probeResultMark())
        assertNull("probe must never write pmReadable", probed.pmReadable)

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msgKind == MsgKind.CHANNEL &&
                vm.state.value.msg.contains("channel error")
        }

        val s = vm.state.value
        assertEquals(ProbeScope.FULL, s.probeScope)
        assertEquals(false, s.pmReadable)
        assertEquals("PM不可讀", s.pmReadableText())
        assertTrue("PM unreadable must surface as channel error, got: \"${s.msg}\"", s.msg.contains("channel error"))
    }

    // Phase C P2 (Item 4 lock): id-ok/pm-fail keeps anyProbePassed + writeReady
    // true (probe gate) while PM stays unreadable (false). True readability =
    // (pmReadable == true) plus writeReady; never read the probe flags as PM
    // readability.
    @Test
    fun phaseC_pmReadable_idOkPmFail_staysFalseDespiteProbePass() {
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
        assertEquals(ProbeScope.FULL, probed.probeScope)
        assertTrue(probed.anyProbePassed)
        assertTrue(probed.writeReady)
        assertNull("probe must never write pmReadable", probed.pmReadable)

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msgKind == MsgKind.CHANNEL &&
                vm.state.value.msg.contains("channel error")
        }

        val s = vm.state.value
        assertTrue("probe flag stays true while PM is unreadable", s.anyProbePassed)
        assertTrue("probe gate stays true on `id -u == 0`", s.writeReady)
        assertEquals(false, s.pmReadable)
        assertEquals("PM不可讀", s.pmReadableText())
    }

    // Phase 2: refresh(true) -> revoke -> probe-only recheck -> NOOP expiry.
    // Stale true must not survive: swapToResolvedLocked clears to null and
    // probeChannels with ready=false pins null (unknown, not false). Recovery
    // via reconnect+refresh rebuilds true.
    @Test
    fun phase2_refreshTrue_revoke_recheck_expiresToNull_thenRecover() {
        val suFake = FakeSu(rootOk = true)
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
        assertNull(vm.state.value.pmReadable)

        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.pmReadable == true }
        val fresh = vm.state.value
        assertTrue(fresh.writeReady)
        assertEquals(ResolvedChannel.ROOT, fresh.resolvedGateway)
        assertEquals("已就緒", fresh.readyMark())

        // Revoke both channels (ROOT denied + Shizuku dead) then probe-only.
        suFake.rootOk = false
        shizukuFake.shizukuOk = false
        vm.recheckPrivilege()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.NOOP
        }
        // Probe-only: no refresh ran, so readability is unknown (null), never
        // the stale true; write gate is closed.
        val expired = vm.state.value
        assertNull("revoked probe must expire pmReadable to null, got ${expired.pmReadable}", expired.pmReadable)
        assertEquals("PM未知", expired.pmReadableText())
        assertFalse(expired.writeReady)
        assertFalse(expired.anyProbePassed)
        assertEquals("未就緒（唯讀）", expired.readyMark())

        // Recover ROOT then probe+refresh: fresh refresh rebuilds true.
        suFake.rootOk = true
        vm.reconnectAndRefresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.ROOT &&
                vm.state.value.pmReadable == true
        }
        val recovered = vm.state.value
        assertTrue(recovered.writeReady)
        assertEquals("已就緒", recovered.readyMark())
    }

    // Phase 2: explicit channel switch clears stale readability; the new
    // refresh proves the new gateway (false here), never the old true.
    // Harness: reuse the same fakes across the swap (match-branch factory),
    // otherwise selectChannel would rebuild REAL gateways and the fake-based
    // assertions below would be environment-hostage (see
    // selectChannel_clearsDegradeNoteAndPending).
    @Test
    fun phase2_channelSwitch_clearsStalePmReadable() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val reuseFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> suFake
                ResolvedChannel.SHIZUKU -> shizukuFake
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, suFake, shizukuFake, channel)
        }
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.anyProbePassed }
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.pmReadable == true }
        assertEquals("已就緒", vm.state.value.readyMark())

        // Switch to a failing SHIZUKU: swap nulls, probe keeps null
        // (ready false), refresh proves false -- stale true must be gone.
        shizukuFake.shizukuOk = false
        vm.selectChannel(ChannelPreference.SHIZUKU)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.SHIZUKU &&
                vm.state.value.pmReadable == false
        }
        val switched = vm.state.value
        assertEquals(ResolvedChannel.SHIZUKU, switched.resolvedGateway)
        assertFalse(switched.writeReady)
        assertEquals(false, switched.pmReadable)
        assertEquals("未就緒（唯讀）", switched.readyMark())
    }

    // Phase 2 P3: id-ok/pm-fail keeps writeReady true + pmReadable false, and
    // the UI must NOT claim 已就緒 (dual-condition readyMark).
    @Test
    fun phase2_idOkPmFail_readyMarkNotReady() {
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
        // Before refresh: probe passed but PM unknown -> not ready.
        val probed = vm.state.value
        assertTrue(probed.writeReady)
        assertNull(probed.pmReadable)
        assertEquals("探測通過，PM未確認", probed.readyMark())

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msgKind == MsgKind.CHANNEL &&
                vm.state.value.pmReadable == false
        }
        val s = vm.state.value
        assertTrue(s.writeReady)
        assertEquals(false, s.pmReadable)
        assertEquals("探測通過，PM不可讀", s.readyMark())
        assertFalse(
            "id-ok/pm-fail must never render 已就緒, got: \"${s.readyMark()}\"",
            s.readyMark() == "已就緒",
        )
    }

    // N1 Phase 1: AUTO -> LSPOSED (ACTIVE via manual select) -> revoke while
    // parked on LSPOSED (rootOk=false) -> auto recheckPrivilege() must not
    // execute any retained su `cat` (fresh `su -c` process, no session
    // persistence: auto exec risks an auth prompt or a 60 s stall). Asserts
    // su exec counters frozen, hook UNKNOWN_*, writeReady false (diagnosis
    // never raises readiness).
    @Test
    fun lsposed_retained_revokedWhileParked_noAutoExec() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        // Rebuild factory (production behavior): every swap mints fresh
        // instances, so candidates TRACK rebuilds (hookDiagnosticGateways =
        // null) and the revoked-while-parked instance can only survive in
        // the retained slot -- nothing current may serve `cat`.
        val createdSu = mutableListOf<FakeSu>()
        val createdShizuku = mutableListOf<FakeShizuku>()
        val rebuildFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val newSu = FakeSu(rootOk = true)
            newSu.markerContent = marker
            val newShizuku = FakeShizuku(shizukuOk = false)
            createdSu.add(newSu)
            createdShizuku.add(newShizuku)
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> newSu
                ResolvedChannel.SHIZUKU -> newShizuku
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, newSu, newShizuku, channel)
        }
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.AUTO,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        // Manual switch to LSPOSED (picker tap): retained instance diagnoses
        // ACTIVE (one `cat`, zero new probes, writeReady stays false).
        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(HookStatus.ACTIVE, vm.state.value.hookStatus)
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)

        // Revoke while parked (no probing channel selected): the retained
        // `cat` would now fail -- auto must not discover this by executing.
        suFake.rootOk = false
        val catBefore = suFake.catCalls
        val idUBefore = suFake.idUCalls
        val listBefore = suFake.listUsersCalls
        val shizukuListBefore = shizukuFake.listUsersCalls
        val shizukuCatBefore = shizukuFake.catCalls

        vm.recheckPrivilege()
        await {
            !vm.state.value.loading &&
                (vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE)
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertTrue(
            "parked revoke + auto recheck must stay UNKNOWN_*, got: ${s.hookStatus}",
            s.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                s.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse("diagnosis must never raise writeReady", s.writeReady)
        assertFalse(s.channelReady)
        assertFalse("LSPOSED probes none", s.anyProbePassed)
        assertEquals(
            "auto recheck must not execute retained su `cat`",
            catBefore,
            suFake.catCalls,
        )
        assertEquals(
            "auto recheck must not run `su -c id -u` (LSPOSED probes none)",
            idUBefore,
            suFake.idUCalls,
        )
        assertEquals(
            "auto recheck must not run `pm list users` via su",
            listBefore,
            suFake.listUsersCalls,
        )
        assertEquals(
            "auto recheck must not probe Shizuku",
            shizukuListBefore,
            shizukuFake.listUsersCalls,
        )
        assertEquals(
            "auto recheck must not `cat` via Shizuku",
            shizukuCatBefore,
            shizukuFake.catCalls,
        )
        // Fresh post-swap instances are never verified under LSPOSED (no
        // probes), so they must stay fully untouched -- diagnosis rode only
        // the retained old instance (or nothing, when auto).
        val newSu = createdSu.single()
        val newShizuku = createdShizuku.single()
        assertEquals("fresh instance must never run `su -c id -u`", 0, newSu.idUCalls)
        assertEquals("fresh instance must never be `cat`ed", 0, newSu.catCalls)
        assertEquals("fresh Shizuku must never be probed", 0, newShizuku.listUsersCalls)
        assertEquals("fresh Shizuku must never be `cat`ed", 0, newShizuku.catCalls)
    }

    // N1 Phase 1: manual opt-in still reads (manual entry triggers `cat` and
    // recovers ACTIVE while authorized). Guards the other half of the split:
    // auto stays silent, manual stays capable.
    @Test
    fun lsposed_manualDiagnosis_stillReadable() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        // Rebuild factory (production behavior): every swap mints fresh
        // instances, so the manual `cat` below provably rides the retained
        // OLD instance, never a fresh one.
        val createdSu = mutableListOf<FakeSu>()
        val rebuildFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val newSu = FakeSu(rootOk = true)
            newSu.markerContent = marker
            val newShizuku = FakeShizuku(shizukuOk = false)
            createdSu.add(newSu)
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> newSu
                ResolvedChannel.SHIZUKU -> newShizuku
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, newSu, newShizuku, channel)
        }
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.AUTO,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        // Auto stays UNKNOWN (direct-only) even while authorized...
        vm.recheckPrivilege()
        await {
            !vm.state.value.loading &&
                (vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE)
        }
        Thread.sleep(200)
        await { !vm.state.value.loading }
        val catAfterAuto = suFake.catCalls
        assertFalse(vm.state.value.writeReady)

        // ...but the manual entry re-reads the marker through the retained
        // instance (one `cat`, no new `id -u`, writeReady stays false).
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val s = vm.state.value
        assertEquals(HookStatus.ACTIVE, s.hookStatus)
        assertTrue(s.hookActive)
        assertTrue(
            "marker scopes must be visible, got: ${s.hookScopes}",
            s.hookScopes.contains(HookMarker.SCOPE_SYSTEM_SERVER),
        )
        assertFalse("diagnosis must never raise writeReady", s.writeReady)
        assertFalse(s.channelReady)
        assertTrue(
            "manual diagnosis must execute retained `cat`",
            suFake.catCalls > catAfterAuto,
        )
        assertEquals(
            "fresh instance stays un-`cat`ed: diagnosis rode the retained old one",
            0,
            createdSu.single().catCalls,
        )
    }

    // N1 Phase 1: a manual privileged read that proves the retained channel
    // dead (parked revoke) clears the retained slot, so the NEXT auto/manual
    // round performs no new root exec (no retry storm, no prompt). Also locks
    // that non-user operations never trigger new root commands after the
    // clear (fake counters frozen).
    @Test
    fun lsposed_manualDiagnosis_revokedClearsRetained_noFurtherExec() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        // Rebuild factory (production behavior): every swap mints fresh
        // instances, so the proving `cat` and the post-clear silence both
        // involve only the retained OLD instance -- with a rebuild, a
        // retained-clear regression turns red (a fresh instance could never
        // silently substitute, and the stale same-instance evidence path is
        // gone). The reuse-factory current-match path stays covered by
        // explicitRootToLsposed_sameInstance_diagnosesActiveWithoutRaisingWriteReady.
        val createdSu = mutableListOf<FakeSu>()
        val rebuildFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val newSu = FakeSu(rootOk = true)
            newSu.markerContent = marker
            val newShizuku = FakeShizuku(shizukuOk = false)
            createdSu.add(newSu)
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> newSu
                ResolvedChannel.SHIZUKU -> newShizuku
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, newSu, newShizuku, channel)
        }
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.AUTO,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        // Revoke while parked, then prove death via ONE manual exec.
        suFake.rootOk = false
        val catBeforeManual = suFake.catCalls
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                (vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE)
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "manual diagnosis must attempt exactly the proving `cat`",
            suFake.catCalls > catBeforeManual,
        )
        assertFalse(vm.state.value.writeReady)
        val catAfterProving = suFake.catCalls
        val idUAfterProving = suFake.idUCalls

        // After the clear, further auto AND manual rounds must not exec.
        vm.recheckPrivilege()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(
            "cleared slot: auto must not re-exec root",
            catAfterProving,
            suFake.catCalls,
        )
        assertEquals(idUAfterProving, suFake.idUCalls)

        vm.requestHookRedDiagnosis()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "cleared slot stays UNKNOWN_*, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertEquals(
            "cleared slot: second manual must not re-exec root",
            catAfterProving,
            suFake.catCalls,
        )
        assertEquals(idUAfterProving, suFake.idUCalls)
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
    }

    // N1 Phase 1 (sticky-manual): drainCoalesced consumes the manual flag
    // per round (same shape as the recheck loop), so a trailing auto round
    // after a manual round never inherits manual privilege. Leading auto +
    // queued manual + auto arriving during the manual trailing round: the
    // second trailing round must run auto (zero new `cat`), proving the
    // manual flag did not leak across rounds. A gated direct-read seam holds
    // each diagnosis round so the three rounds sequence deterministically.
    @Test
    fun reconnectDrain_manualFlagNotStickyAcrossRounds() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val reuseFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> suFake
                ResolvedChannel.SHIZUKU -> shizukuFake
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, suFake, shizukuFake, channel)
        }
        val entered = AtomicInteger(0)
        val gate = Semaphore(0)
        val gating = java.util.concurrent.atomic.AtomicBoolean(false)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.AUTO,
            hookDirectReads = {
                entered.incrementAndGet()
                if (gating.get()) {
                    // Hold this diagnosis round until the test releases it; a
                    // 5 s timeout fails safe (round completes ungated) so a
                    // sequencing bug turns red instead of hanging.
                    gate.tryAcquire(5, TimeUnit.SECONDS)
                }
                listOf(MarkerRead.Denied, MarkerRead.Denied)
            },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        // Park on LSPOSED via a manual picker tap: retained ACTIVE.
        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        gating.set(true)
        val roundsBase = entered.get()
        val catBase = suFake.catCalls

        // Leading auto round, held inside direct reads.
        vm.reconnectAndRefresh(userInitiated = false)
        await { entered.get() >= roundsBase + 1 }
        // Manual queued behind the leading auto (pending + manual flag).
        vm.reconnectAndRefresh(userInitiated = true)
        // Leading completes; trailing round 1 (manual) starts and blocks.
        gate.release()
        await { entered.get() >= roundsBase + 2 }
        // Auto arrives during the manual trailing round.
        vm.reconnectAndRefresh(userInitiated = false)
        // Trailing round 1 (manual) runs: retained `cat`, back to ACTIVE.
        gate.release()
        await { entered.get() >= roundsBase + 3 }
        val catAfterManual = suFake.catCalls
        assertTrue(
            "manual trailing round must `cat` the retained instance",
            catAfterManual > catBase,
        )
        // Trailing round 2 must be auto: no new `cat`, back to UNKNOWN_*.
        gate.release()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(
            "second trailing round must not inherit manual (no new `cat`)",
            catAfterManual,
            suFake.catCalls,
        )
        assertTrue(
            "auto trailing round stays UNKNOWN_*, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)
    }

    // N1 Phase 1 (cat-only denial): `id -u` proves ROOT but `cat` is refused
    // (rootOk=true, catOk=false). Readiness follows the probe (writeReady
    // stays true); diagnosis fails closed (UNKNOWN_*, never a fake
    // ACTIVE/INACTIVE) after actually attempting the `cat`.
    @Test
    fun rootProbeOk_catDenied_unknownButReady() {
        val suFake = FakeSu(rootOk = true)
        suFake.catOk = false
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.AUTO,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = null,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }

        val s = vm.state.value
        assertEquals(ResolvedChannel.ROOT, s.resolvedGateway)
        assertTrue("probe-proven ROOT stays writable", s.writeReady)
        assertTrue("probe-proven ROOT stays channelReady", s.channelReady)
        // The diagnostic `cat` was attempted and refused: UNKNOWN (a gateway
        // path existed), never a fake ACTIVE/INACTIVE.
        assertEquals(HookStatus.UNKNOWN_UNREADABLE, s.hookStatus)
        assertFalse(s.hookActive)
        assertTrue("denied `cat` must still have been attempted", suFake.catCalls > 0)
    }

    // N1 Phase 1 (re-verify recovery): after a manual proving `cat` clears
    // the retained slot, leaving LSPOSED and re-proving ROOT restores the
    // retained instance, so a later manual LSPOSED diagnosis is ACTIVE again
    // (one revoked round never permanently loses diagnosis).
    @Test
    fun lsposed_clearedRetained_reverifyRestores_manualActiveAgain() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val reuseFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> suFake
                ResolvedChannel.SHIZUKU -> shizukuFake
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, suFake, shizukuFake, channel)
        }
        val vm = makeAutoVm(
            suFake = suFake,
            shizukuFake = shizukuFake,
            suPresent = true,
            shizukuAuth = false,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            preference = ChannelPreference.AUTO,
            hookDirectReads = { listOf(MarkerRead.Denied, MarkerRead.Denied) },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        // Revoke while parked; one manual round proves death and clears.
        suFake.rootOk = false
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                (vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE)
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val catAfterClear = suFake.catCalls
        assertTrue(
            "proving round stays UNKNOWN_*, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)

        // Re-verify: auth restored, back to ROOT; the probe passes again and
        // the retained slot is re-pinned (diagnosis ACTIVE via current).
        suFake.rootOk = true
        vm.selectChannel(ChannelPreference.ROOT, userInitiated = true)
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val restored = vm.state.value
        assertEquals(ChannelPreference.ROOT, restored.channel)
        assertTrue("re-proven ROOT is writable again", restored.writeReady)
        assertEquals(HookStatus.ACTIVE, restored.hookStatus)

        // Park on LSPOSED again: manual diagnosis rides the RESTORED
        // retained instance back to ACTIVE (zero new probes, not writable).
        val idUBefore = suFake.idUCalls
        vm.selectChannel(ChannelPreference.LSPOSED, userInitiated = true)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val s = vm.state.value
        assertEquals(HookStatus.ACTIVE, s.hookStatus)
        assertFalse("diagnosis must never raise writeReady", s.writeReady)
        assertFalse(s.channelReady)
        assertTrue(
            "restored retained instance must serve `cat` again",
            suFake.catCalls > catAfterClear,
        )
        assertEquals("re-park still runs zero `id -u` probes", idUBefore, suFake.idUCalls)
    }

    // Phase 2 P3: dual-condition matrix for readyMark.
    @Test
    fun phase2_readyMark_dualCondition() {
        val base = UiState(writeReady = false, pmReadable = null)
        assertEquals("未就緒（唯讀）", base.readyMark())
        assertEquals("未就緒（唯讀）", base.copy(writeReady = false, pmReadable = true).readyMark())
        assertEquals("未就緒（唯讀）", base.copy(writeReady = false, pmReadable = false).readyMark())
        assertEquals("已就緒", base.copy(writeReady = true, pmReadable = true).readyMark())
        assertEquals("探測通過，PM不可讀", base.copy(writeReady = true, pmReadable = false).readyMark())
        assertEquals("探測通過，PM未確認", base.copy(writeReady = true, pmReadable = null).readyMark())
    }
}
