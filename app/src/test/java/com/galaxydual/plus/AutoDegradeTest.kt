// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-5 P2 (issue #25): Auto probe-based degrade -- ViewModel + pure matrix.
// Fake probe matrix (no device, no Android runtime):
// - su denied (id -u != 0) + Shizuku proven -> degrade to SHIZUKU with a note.
// - both probes failing -> NOOP read-only.
// - both succeeding -> ROOT (F4a order, no degrade note).
// - explicit SHIZUKU never crosses to ROOT even when ROOT is proven.
//
// Phase 4: 62 tests (was 17 at phase-2, 49 at phase-3 close); this file now also
// covers factory parity, probeScope, degrade-note error-first, reverse swaps,
// and command-count/command-delta assertions (see per-test headers).
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
        val idUCalls = AtomicInteger(0)
        // Counts `pm list users` calls so tests can prove a re-probe ran.
        val listUsersCalls = AtomicInteger(0)
        // Phase 4 C3: per-command counters so refresh can assert exact
        // command deltas (verbose/dumpsys/getprop/listPackages).
        val verboseCalls = AtomicInteger(0)
        val dumpsysCalls = AtomicInteger(0)
        val getpropCalls = AtomicInteger(0)
        val listPackagesCalls = AtomicInteger(0)
        // Counts diagnostic `cat` (hook marker) so tests prove a (non-)use
        // of a gateway for diagnosis without conflating with idU probes.
        val catCalls = AtomicInteger(0)
        val uninstallCalls = AtomicInteger(0)
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
                idUCalls.incrementAndGet()
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            }
            cmd == DualRepository.CMD_LIST_USERS -> {
                listUsersCalls.incrementAndGet()
                val pmOk = pmListOk ?: rootOk
                if (pmOk) CmdResult(exit = 0, out = usersOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied: su refused")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                verboseCalls.incrementAndGet()
                CmdResult(exit = 0, out = verboseOut(), err = "")
            }
            cmd == DualRepository.CMD_DUMPSYS_USER -> {
                dumpsysCalls.incrementAndGet()
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            }
            cmd == DualRepository.CMD_GETPROP_DUAL -> {
                getpropCalls.incrementAndGet()
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                uninstallCalls.incrementAndGet()
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                if (uninstallExit == 0) installedPkgs.remove(pkg)
                CmdResult(exit = uninstallExit, out = uninstallOut, err = "")
            }
            cmd.startsWith("pm list packages") -> {
                listPackagesCalls.incrementAndGet()
                CmdResult(exit = 0, out = listPackagesOut(), err = "")
            }
            cmd.startsWith("cat ") -> {
                // Phase A P2: serve marker content for hook diagnosis; null
                // keeps the legacy "ok" (no scopes). `cat` never touches
                // idUCalls/listUsersCalls, so zero-probe assertions stay valid.
                // N1 Phase 1: `cat` obeys rootOk/catOk -- a revoked su must
                // fail `cat` (fail closed, maps to UNKNOWN via diagnosis).
                catCalls.incrementAndGet()
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
        val listUsersCalls = AtomicInteger(0)
        // Phase 4 C3: per-command counters so refresh can assert exact
        // command deltas (verbose/dumpsys/getprop/listPackages).
        val verboseCalls = AtomicInteger(0)
        val dumpsysCalls = AtomicInteger(0)
        val getpropCalls = AtomicInteger(0)
        val listPackagesCalls = AtomicInteger(0)
        // Counts diagnostic `cat` so tests prove a (non-)use for diagnosis.
        // Mutable shizukuOk models binder dead/rebind (revocation): flipping
        // to false makes the next probe fail, which must rewrite the
        // shizuku evidence entry to false on the same instance.
        val catCalls = AtomicInteger(0)
        val uninstallCalls = AtomicInteger(0)
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
                listUsersCalls.incrementAndGet()
                if (shizukuOk) CmdResult(exit = 0, out = usersOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied: not authorized")
            }
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                verboseCalls.incrementAndGet()
                if (shizukuOk) CmdResult(exit = 0, out = verboseOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            }
            cmd == DualRepository.CMD_DUMPSYS_USER -> {
                dumpsysCalls.incrementAndGet()
                if (shizukuOk) CmdResult(exit = 0, out = dumpsysOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            }
            cmd == DualRepository.CMD_GETPROP_DUAL -> {
                getpropCalls.incrementAndGet()
                if (shizukuOk) CmdResult(exit = 0, out = "$dualId\n", err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            }
            cmd.startsWith("pm uninstall") -> {
                if (!shizukuOk) CmdResult(exit = 1, out = "", err = "denied")
                else {
                    uninstallCalls.incrementAndGet()
                    val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                    if (uninstallExit == 0) installedPkgs.remove(pkg)
                    CmdResult(exit = uninstallExit, out = uninstallOut, err = "")
                }
            }
            cmd.startsWith("pm list packages") -> {
                listPackagesCalls.incrementAndGet()
                if (shizukuOk) CmdResult(exit = 0, out = listPackagesOut(), err = "")
                else CmdResult(exit = 1, out = "", err = "denied")
            }
            cmd.startsWith("cat ") -> {
                catCalls.incrementAndGet()
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

    // Phase 4 A4 FactoryParity: default tracks production (null = ViewModel
    // default { listOf(suGateway, shizukuGateway) } so candidates TRACK
    // rebuilds, same as DualViewModelFactory/selectChannel production path).
    // Pass an explicit { emptyList() } ONLY for the intentional
    // no-diagnostic-channel case (explicit_shizukuNeverCrossesToRoot keeps
    // it to prove zero gateway diagnosis). All reuseFactory test doubles map
    // via pref.resolveChannel (same layer as production assembleChannel, see
    // factoryParity test below) so the two paths can never drift on channel.
    // Harness note (Phase 4 C1): Dispatchers.Unconfined runs viewModelScope
    // eagerly on the caller thread, so probe/refresh bodies start synchronously
    // and tests poll StateFlow with await()+Thread.sleep settle. Unconfined has
    // no scheduler control: a stray second probe would flip loading busy again,
    // so exact-once assertions always require quiescence (await loading false +
    // short settle) before reading counters, never a bare sleep.
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
        // Null (default, production tracking) = use the ViewModel default so
        // candidates TRACK rebuilds. Explicit { emptyList() } = intentional
        // no-diagnostic-channel (only the pinned example below may use it).
        hookDiagnosticGateways: (() -> List<CmdGateway>)? = null,
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
                blockText = zhTestBlockText(),
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
                blockText = zhTestBlockText(),
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
        assertEquals(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU, d.degrade!!.kind)
        assertTrue("degrade note must record the reason, got: \"${zhTestBlockText()(d.degrade!!)}\"", zhTestBlockText()(d.degrade!!).contains("降級"))
    }

    // Pure matrix: both probes failing -> NOOP read-only with a note.
    @Test
    fun pure_bothFail_readOnly() {
        val d = resolveAutoProbe(suPresent = true, hasRoot = false, hasShizuku = false)
        assertEquals(ResolvedChannel.NOOP, d.channel)
        assertEquals(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED, d.degrade!!.kind)
        assertTrue("read-only note expected, got: \"${zhTestBlockText()(d.degrade!!)}\"", zhTestBlockText()(d.degrade!!).contains("唯讀"))
    }

    // Pure matrix: both succeeding -> ROOT (F4a su-first order, no degrade).
    @Test
    fun pure_bothSucceed_prefersRoot() {
        val d = resolveAutoProbe(suPresent = true, hasRoot = true, hasShizuku = true)
        assertEquals(ResolvedChannel.ROOT, d.channel)
        assertTrue("no degrade note when ROOT is proven, got: \"${d.degrade}\"", d.degrade == null)
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
        assertEquals(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU, s.degrade!!.kind)
        assertTrue("degrade note must record the reason, got: \"${zhTestBlockText()(s.degrade!!)}\"", zhTestBlockText()(s.degrade!!).contains("降級"))
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
        assertTrue("read-only note expected, got: \"${s.degrade?.let { zhTestBlockText()(it) }}\"", s.degrade != null && zhTestBlockText()(s.degrade!!).contains("唯讀"))
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
        assertTrue("no degrade when ROOT is proven, got: \"${s.degrade}\"", s.degrade == null)
    }

    // Explicit SHIZUKU never crosses to ROOT even when ROOT is proven: the
    // resolved gateway stays SHIZUKU with SHELL identity (UI must not claim
    // "正在使用 ROOT").
    // P25 Item 3: explicit probes only its own channel, so the su elevator is
    // never probed here (idUCalls stays 0) and the routed summary
    // reflects the probed subset (SHELL), never an unprobed unrelated channel.
    // Phase 4 A4: this is the single intentional no-diagnostic-channel example
    // (explicit emptyList): proves an explicit probe succeeds with zero gateway
    // diagnosis, while makeAutoVm default (null) tracks production elsewhere.
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
        assertTrue("explicit choice clears the Auto degrade note", s.degrade == null)
        // P25 Item 3: routed reflects only the probed (selected) channel --
        // su was intentionally never probed, so this is SHELL, not ROOT.
        // Execution identity stays SHELL either way.
        assertEquals(Privilege.SHELL, s.privilege)
        assertEquals("explicit SHIZUKU must never run `su -c id -u`", 0, suFake.idUCalls.get())
    }

    // Phase 2: no su binary + Shizuku proven -> SHIZUKU with NO degrade note
    // (Shizuku is the natural pick, not a fallback).
    @Test
    fun pure_noSuNoShizukuOk_noDegradeNote() {
        val d = resolveAutoProbe(suPresent = false, hasRoot = false, hasShizuku = true)
        assertEquals(ResolvedChannel.SHIZUKU, d.channel)
        assertTrue(
            "no su present so SHIZUKU is the natural pick, note must be empty, " +
                "got: \"${d.degrade}\"",
            d.degrade == null,
        )
    }

    // Phase 2: no su binary + both probes failing -> NOOP read-only with the
    // second (no-su) copy, distinct from the su-denied copy.
    @Test
    fun pure_noSuBothFail_secondCopy() {
        val d = resolveAutoProbe(suPresent = false, hasRoot = false, hasShizuku = false)
        assertEquals(ResolvedChannel.NOOP, d.channel)
        assertEquals(WriteBlockKind.AUTO_DEGRADE_NO_CHANNEL, d.degrade!!.kind)
        assertTrue("read-only note expected, got: \"${zhTestBlockText()(d.degrade!!)}\"", zhTestBlockText()(d.degrade!!).contains("唯讀"))
        assertTrue(
            "must be the no-su copy, got: \"${zhTestBlockText()(d.degrade!!)}\"",
            zhTestBlockText()(d.degrade!!).contains("su 不存在"),
        )
    }

    // Phase 2: ROOT proven alone (su present, Shizuku denied) -> ROOT, no note.
    @Test
    fun pure_rootOnly_staysRoot() {
        val d = resolveAutoProbe(suPresent = true, hasRoot = true, hasShizuku = false)
        assertEquals(ResolvedChannel.ROOT, d.channel)
        assertTrue(
            "no degrade note when ROOT is proven, got: \"${d.degrade}\"",
            d.degrade == null,
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
        assertTrue("explicit choice clears the Auto degrade note", s.degrade == null)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        // P25 Item 3: Shizuku was intentionally never probed (explicit ROOT
        // probes su only), so anyProbePassed reflects the probed subset only.
        assertFalse("unprobed Shizuku must not count toward anyProbePassed", s.anyProbePassed)
        assertEquals(Privilege.NONE, s.privilege)
        assertEquals("explicit ROOT must not probe Shizuku", 0, shizukuFake.listUsersCalls.get())
        // Phase 4 A7: explicit ROOT probes only su -> scope is ROOT_ONLY.
        assertEquals(ProbeScope.ROOT_ONLY, s.probeScope)
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
        assertTrue("explicit choice clears the Auto degrade note", s.degrade == null)
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertEquals(Privilege.NONE, s.privilege)
        assertFalse("unprobed su must not count toward anyProbePassed", s.anyProbePassed)
        assertEquals("explicit SHIZUKU must never run `su -c id -u`", 0, suFake.idUCalls.get())
        // Phase 4 A7: explicit SHIZUKU probes only Shizuku -> SHIZUKU_ONLY.
        assertEquals(ProbeScope.SHIZUKU_ONLY, s.probeScope)
    }

    // Phase 4 A7: probeScope() pure mapping -- preference -> probed subset.
    // Single source used by probeChannels; render must never drift from state.
    @Test
    fun probeScope_pureMapping() {
        assertEquals(ProbeScope.FULL, ChannelPreference.AUTO.probeScope())
        assertEquals(ProbeScope.ROOT_ONLY, ChannelPreference.ROOT.probeScope())
        assertEquals(ProbeScope.SHIZUKU_ONLY, ChannelPreference.SHIZUKU.probeScope())
        assertEquals(ProbeScope.NONE, ChannelPreference.LSPOSED.probeScope())
    }

    // Phase 4 A4 FactoryParity (channel-parity): test reuseFactory and production
    // assembleChannel resolve through the same resolveChannel layer, so channel
    // selection can never drift between tests and production. Exhaustive over
    // prefs x env. Full-assembly parity: the assembled gateway instance/type
    // must also match the channel (ROOT -> suGateway instance, SHIZUKU ->
    // shizukuGateway instance, NOOP/LSPOSED_PLACEHOLDER -> NoOp), so a future
    // wiring change that picks the right channel but the wrong object turns red.
    @Test
    fun factoryParity_resolveChannelMatchesAssemble() {
        for (pref in ChannelPreference.values()) {
            for (suPresent in listOf(false, true)) {
                for (shizukuAuth in listOf(false, true)) {
                    val expected = pref.resolveChannel(suPresent, shizukuAuth)
                    val asm = assembleChannel(pref, suPresent, shizukuAuth, "com.example.test")
                    assertEquals(
                        "assembleChannel must match resolveChannel for $pref " +
                            "(suPresent=$suPresent, shizukuAuth=$shizukuAuth)",
                        expected,
                        asm.channel,
                    )
                    assertTrue(
                        "su slot must be the real su gateway for $pref " +
                            "(suPresent=$suPresent, shizukuAuth=$shizukuAuth)",
                        asm.suGateway is SuCmdGateway,
                    )
                    assertTrue(
                        "shizuku slot must be the real shizuku gateway for $pref " +
                            "(suPresent=$suPresent, shizukuAuth=$shizukuAuth)",
                        asm.shizukuGateway is ShizukuCmdGateway,
                    )
                    when (expected) {
                        ResolvedChannel.ROOT ->
                            assertTrue(
                                "ROOT must ride the suGateway instance for $pref " +
                                    "(suPresent=$suPresent, shizukuAuth=$shizukuAuth)",
                                asm.gateway === asm.suGateway,
                            )
                        ResolvedChannel.SHIZUKU ->
                            assertTrue(
                                "SHIZUKU must ride the shizukuGateway instance for $pref " +
                                    "(suPresent=$suPresent, shizukuAuth=$shizukuAuth)",
                                asm.gateway === asm.shizukuGateway,
                            )
                        ResolvedChannel.NOOP,
                        ResolvedChannel.LSPOSED_PLACEHOLDER,
                        ->
                            assertTrue(
                                "$expected must ride NoOp (fail-closed) for $pref " +
                                    "(suPresent=$suPresent, shizukuAuth=$shizukuAuth)",
                                asm.gateway is NoOpGateway,
                            )
                    }
                }
            }
        }
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
        assertEquals(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU, s.degrade!!.kind)
        assertTrue("degrade note must survive refresh, got: \"${zhTestBlockText()(s.degrade!!)}\"", zhTestBlockText()(s.degrade!!).contains("降級"))
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

        val probesBefore = shizukuFake.listUsersCalls.get()
        vm.recheckPrivilege()
        await { !vm.state.value.loading && shizukuFake.listUsersCalls.get() > probesBefore }

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
        assertEquals(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU, s.degrade!!.kind)
        assertTrue("degrade note must record the reason, got: \"${zhTestBlockText()(s.degrade!!)}\"", zhTestBlockText()(s.degrade!!).contains("降級"))
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
            "precondition: AUTO must have degraded, got: \"${vm.state.value.degrade}\"",
            vm.state.value.degrade?.kind == WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU,
        )

        vm.requestRemove("com.example.chat")
        await { vm.state.value.pendingRemove != null && !vm.state.value.loading }

        vm.selectChannel(ChannelPreference.ROOT)
        await { vm.state.value.channel == ChannelPreference.ROOT }

        val s = vm.state.value
        assertEquals(ChannelPreference.ROOT, s.channel)
        assertTrue("explicit choice clears the Auto degrade note, got: \"${s.degrade}\"", s.degrade == null)
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
        assertEquals(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU, s.degrade!!.kind)
        assertTrue("degrade must survive refresh, got: \"${zhTestBlockText()(s.degrade!!)}\"", zhTestBlockText()(s.degrade!!).contains("降級"))
        assertTrue(
            "refresh INFO must combine with degrade note, got: \"${s.msg}\"",
            s.msg.contains("no clones installed") && s.msg.contains("降級"),
        )
        assertEquals(MsgKind.INFO, s.msgKind)
        assertTrue("degraded SHIZUKU must stay writable", s.writeReady)
        // channelView short-tag condition (MainActivity appends
        // "已自 Auto 降級，詳見狀態列" exactly when degrade is non-null).
        assertNotNull(
            "channelView must show the degrade short tag (degrade non-null)",
            s.degrade,
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
            "precondition: AUTO must have degraded to NOOP, got: \"${vm.state.value.degrade}\"",
            vm.state.value.degrade != null && zhTestBlockText()(vm.state.value.degrade!!).contains("唯讀"),
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
        assertTrue("degrade must survive refresh, got: \"${s.degrade?.let { zhTestBlockText()(it) }}\"", s.degrade != null && zhTestBlockText()(s.degrade!!).contains("唯讀"))
        // Phase B P2 render/state acceptance: the combined status line must
        // carry the full degrade reason inline (not only in the degradeNote
        // field), error first with the recovery note appended.
        assertTrue(
            "combined msg must carry the full degrade inline, got: \"${s.msg}\"",
            s.degrade != null && s.msg.contains(zhTestBlockText()(s.degrade!!)),
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
        // channelView short-tag condition: degrade non-null under AUTO.
        assertEquals(ChannelPreference.AUTO, s.channel)
        assertNotNull(
            "channelView must show the degrade short tag (degrade non-null)",
            s.degrade,
        )
    }

    // Phase 4 A6: error-first degrade stacking -- combineWithDegradeNote keeps
    // the base kind and appends the degrade reason ("；" join). Error kinds
    // (CHANNEL/FAILED/NO_DUAL/VERIFY/INVALID) never downgrade to INFO; recovery entry
    // (重新連線並更新) rides in the degrade note. Tested via reflection so no
    // production visibility change is needed (private stays private).
    private fun combineViaReflection(
        vm: DualViewModel,
        baseMsg: String,
        baseKind: MsgKind,
        degrade: WriteBlock?,
    ): Pair<String, MsgKind> {
        val m = DualViewModel::class.java.getDeclaredMethod(
            "combineWithDegradeNote",
            String::class.java,
            MsgKind::class.java,
            WriteBlock::class.java,
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(vm, baseMsg, baseKind, degrade) as Pair<String, MsgKind>
    }

    @Test
    fun combine_channel_keepsKindAndStacksNote() {
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
        await { !vm.state.value.loading }
        val degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED)
        val degradeText = zhTestBlockText()(degrade)
        val (msg, kind) = combineViaReflection(vm, "channel error: denied", MsgKind.CHANNEL, degrade)
        assertEquals(MsgKind.CHANNEL, kind)
        assertTrue("base first, got: \"$msg\"", msg.startsWith("channel error: denied"))
        assertTrue("degrade appended, got: \"$msg\"", msg.contains(degradeText))
        assertTrue("recovery entry, got: \"$msg\"", msg.contains("重新連線並更新"))
    }

    @Test
    fun combine_noDual_keepsKindAndStacksNote() {
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
        await { !vm.state.value.loading }
        val degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED)
        val degradeText = zhTestBlockText()(degrade)
        val (msg, kind) = combineViaReflection(vm, "no dual profile", MsgKind.NO_DUAL, degrade)
        assertEquals(MsgKind.NO_DUAL, kind)
        assertTrue("base first, got: \"$msg\"", msg.startsWith("no dual profile"))
        assertTrue("degrade appended, got: \"$msg\"", msg.contains(degradeText))
        assertTrue("recovery entry, got: \"$msg\"", msg.contains("重新連線並更新"))
    }

    @Test
    fun combine_failed_keepsKindAndStacksNote() {
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
        await { !vm.state.value.loading }
        val degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED)
        val degradeText = zhTestBlockText()(degrade)
        val (msg, kind) = combineViaReflection(vm, "clone failed: boom", MsgKind.FAILED, degrade)
        assertEquals(MsgKind.FAILED, kind)
        assertTrue("base first, got: \"$msg\"", msg.startsWith("clone failed: boom"))
        assertTrue("degrade appended, got: \"$msg\"", msg.contains(degradeText))
    }

    @Test
    fun combine_verify_keepsKindAndStacksNote() {
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
        await { !vm.state.value.loading }
        val degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED)
        val degradeText = zhTestBlockText()(degrade)
        val (msg, kind) = combineViaReflection(vm, "clone verification failed: x not installed", MsgKind.VERIFY, degrade)
        assertEquals(MsgKind.VERIFY, kind)
        assertTrue("base first, got: \"$msg\"", msg.startsWith("clone verification failed"))
        assertTrue("degrade appended, got: \"$msg\"", msg.contains(degradeText))
    }

    @Test
    fun combine_invalid_keepsKindAndStacksNote() {
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
        await { !vm.state.value.loading }
        val degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED)
        val degradeText = zhTestBlockText()(degrade)
        val (msg, kind) = combineViaReflection(vm, "invalid package", MsgKind.INVALID, degrade)
        assertEquals(MsgKind.INVALID, kind)
        assertTrue("base first, got: \"$msg\"", msg.startsWith("invalid package"))
        assertTrue("degrade appended, got: \"$msg\"", msg.contains(degradeText))
        assertTrue("recovery entry, got: \"$msg\"", msg.contains("重新連線並更新"))
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

        suFake.idUCalls.set(0)
        val shizukuProbesBefore = shizukuFake.listUsersCalls.get()
        vm.recheckPrivilege()
        await { !vm.state.value.loading && suFake.idUCalls.get() >= 1 }
        // Settle: a stray second probe would flip loading busy again; require
        // quiescence before asserting the upper bound.
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("AUTO recheck must probe su exactly once, got ${suFake.idUCalls.get()}", 1, suFake.idUCalls.get())
        // Phase 4 C3/B1: precise +1 delta (not just >) proves no hidden extra probe.
        assertEquals(
            "AUTO must still probe Shizuku exactly once, got ${shizukuFake.listUsersCalls.get()} vs $shizukuProbesBefore",
            shizukuProbesBefore + 1,
            shizukuFake.listUsersCalls.get(),
        )

        suFake.idUCalls.set(0)
        vm.reconnectAndRefresh()
        await { !vm.state.value.loading && suFake.idUCalls.get() >= 1 }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("AUTO reconnect must probe su exactly once, got ${suFake.idUCalls.get()}", 1, suFake.idUCalls.get())
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
        assertEquals("init probe must not touch su", 0, suFake.idUCalls.get())

        vm.recheckPrivilege()
        await { !vm.state.value.loading && shizukuFake.listUsersCalls.get() >= 2 }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("explicit SHIZUKU recheck must never run `su -c id -u`", 0, suFake.idUCalls.get())

        vm.reconnectAndRefresh()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals("explicit SHIZUKU reconnect must never run `su -c id -u`", 0, suFake.idUCalls.get())
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

        assertEquals("explicit LSPOSED must never run `su -c id -u`", 0, suFake.idUCalls.get())
        assertEquals("explicit LSPOSED must never probe Shizuku", 0, shizukuFake.listUsersCalls.get())
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
        assertEquals("explicit LSPOSED must never run `su -c id -u`", 0, suFake.idUCalls.get())
        assertEquals("explicit LSPOSED must never probe Shizuku", 0, shizukuFake.listUsersCalls.get())

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

    // P2-1 (narrow consent) + B1 (diagnostic lifecycle split): AUTO ->
    // LSPOSED via REAL selectChannel + rebuild (new instances, production
    // behavior). The picker switch passes allowPrivilegedHookDiagnosis=false
    // (a mode switch is NOT consent for privileged diagnosis), so LSPOSED
    // stays direct-only: UNKNOWN_* with ZERO retained `cat` even though the
    // swap retains the still-usable OLD su instance in the retained slot. Only
    // requestHookRedDiagnosis diagnoses ACTIVE through the retained `cat`
    // while writeReady stays false (hook diagnosis never raises writeReady).
    // The FRESH instance is never probed or `cat`ed on any path (no new `su
    // -c` auth risk on the new object); only the already-authorized retained
    // instance serves a consented diagnosis.
    @Test
    fun autoToLsposed_rebuildRetainsVerifiedDiagnostic_manualOnlyActiveWithoutProbingNewInstance() {
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            // Null = tracking (production): candidates follow rebuilds.
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        // Initial AUTO proves the OLD instance (writable + ACTIVE via its cat).
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        val oldCatBefore = suOld.catCalls.get()
        assertTrue("precondition: old instance must have served `cat`", oldCatBefore > 0)
        val oldIdUBefore = suOld.idUCalls.get()

        vm.selectChannel(ChannelPreference.LSPOSED)
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
        // P2-1: a picker mode switch is NOT consent for privileged diagnosis:
        // direct denied + retained slot populated, yet UNKNOWN_* with ZERO
        // retained `cat` -- never ACTIVE, never a probe of the new instance.
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only, no privileged consent), got: ${s.hookStatus}",
            s.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                s.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse("NoOp placeholder is never writable", s.writeReady)
        assertFalse("NoOp placeholder is never channelReady", s.channelReady)
        assertFalse("LSPOSED probes none", s.anyProbePassed)
        val newSu = createdSu.first()
        assertEquals("new instance must never run `su -c id -u` before verification", 0, newSu.idUCalls.get())
        assertEquals("new instance must never be `cat`ed before verification", 0, newSu.catCalls.get())
        assertEquals(
            "picker switch must not `cat` the retained old instance, got ${suOld.catCalls.get()} vs $oldCatBefore",
            oldCatBefore,
            suOld.catCalls.get(),
        )
        assertEquals("switch must not run new `su -c id -u` probes", oldIdUBefore, suOld.idUCalls.get())

        // P2-1: LSPOSED auto recheck stays direct-only -- no retained `cat`,
        // no `id -u` anywhere, no `cat` on the fresh instance (fresh `su -c`
        // process, auth risk): hook stays UNKNOWN with writeReady false.
        val totalIdUBefore = suOld.idUCalls.get() + newSu.idUCalls.get()
        val retainedCatBeforeAuto = suOld.catCalls.get()
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
        assertEquals("LSPOSED auto recheck must run zero probes", totalIdUBefore, suOld.idUCalls.get() + newSu.idUCalls.get())
        assertEquals(
            "LSPOSED auto recheck must not `cat` the retained instance",
            retainedCatBeforeAuto,
            suOld.catCalls.get(),
        )
        assertEquals("fresh instance stays untouched across auto recheck", 0, newSu.idUCalls.get())
        assertEquals("fresh instance stays un-`cat`ed across auto recheck", 0, newSu.catCalls.get())
        // Consented opt-in restores ACTIVE through the retained instance (one
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
        assertTrue(vm.state.value.hookActive)
        assertTrue(
            "marker scopes must be visible, got: ${vm.state.value.hookScopes}",
            vm.state.value.hookScopes.contains(HookMarker.SCOPE_SYSTEM_SERVER),
        )
        assertFalse(vm.state.value.writeReady)
        assertTrue(
            "manual diagnosis must ride the retained old instance",
            suOld.catCalls.get() > retainedCatBeforeAuto,
        )
        assertEquals("manual diagnosis still runs zero `id -u` probes", totalIdUBefore, suOld.idUCalls.get() + newSu.idUCalls.get())
        assertEquals("fresh instance stays untouched across manual diagnosis", 0, newSu.idUCalls.get())
        assertEquals("fresh instance stays un-`cat`ed across manual diagnosis", 0, newSu.catCalls.get())
    }

    // Phase A P2 (match branch) + B1 (retained slot) + P2-1 (narrow
    // consent): explicit ROOT writes su evidence for the CURRENT instance via
    // a real probe; switching to LSPOSED with the SAME instances (factory
    // reuses fakes) keeps identity+generation equal, but the picker switch
    // passes allowPrivilegedHookDiagnosis=false, so LSPOSED stays direct-only
    // (UNKNOWN_*, zero retained `cat`). Only requestHookRedDiagnosis
    // diagnoses ACTIVE via that verified `cat` (current match and retained
    // slot agree, deduped to one `cat`) while writeReady stays false (hook
    // diagnosis never raises writeReady). No new `id -u` runs on the LSPOSED
    // path.
    @Test
    fun explicitRootToLsposed_sameInstance_manualOnlyActiveWithoutRaisingWriteReady() {
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        val idUBefore = suFake.idUCalls.get()
        val catBeforeSelect = suFake.catCalls.get()

        // P2-1: picker switch is NOT consent -- stays UNKNOWN with zero new
        // `cat` even though identity+generation match.
        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }

        val s = vm.state.value
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only, no privileged consent), got: ${s.hookStatus}",
            s.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                s.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse("NoOp placeholder is never writable", s.writeReady)
        assertFalse(s.channelReady)
        assertEquals(ResolvedChannel.LSPOSED_PLACEHOLDER, s.resolvedGateway)
        assertFalse("LSPOSED probes none", s.anyProbePassed)
        assertEquals("LSPOSED must not run `su -c id -u`", idUBefore, suFake.idUCalls.get())
        assertEquals(
            "picker switch must not `cat` the retained instance",
            catBeforeSelect,
            suFake.catCalls.get(),
        )
        // P2-1: auto recheck stays direct-only (UNKNOWN, no `cat`); only the
        // consented opt-in restores ACTIVE. Diagnosis never raises writeReady.
        val catAfterSelect = suFake.catCalls.get()
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
            suFake.catCalls.get(),
        )
        assertEquals("LSPOSED auto recheck must not run `su -c id -u`", idUBefore, suFake.idUCalls.get())
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(HookStatus.ACTIVE, vm.state.value.hookStatus)
        assertTrue(vm.state.value.hookActive)
        assertTrue(
            "marker scopes must be visible, got: ${vm.state.value.hookScopes}",
            vm.state.value.hookScopes.contains(HookMarker.SCOPE_SYSTEM_SERVER),
        )
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
        assertTrue("consented diagnosis must `cat` once", suFake.catCalls.get() > catAfterSelect)
        assertEquals("consented diagnosis still runs zero `id -u` probes", idUBefore, suFake.idUCalls.get())
    }

    // Phase A P2 (revocation) + P2-1 (narrow consent): ROOT proven then auth
    // revoked (rootOk true -> false) rewrites the SAME-instance entry to false
    // on the next probe, so a later LSPOSED round is UNKNOWN (no stale
    // ACTIVE). The picker switch passes allowPrivilegedHookDiagnosis=false, so
    // UNKNOWN holds with zero retained `cat`. Models Shizuku binder
    // dead/rebind the same way: a dead binding fails the next probe and
    // disables that entry until a passing probe re-enables it.
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        val catBeforeRevoke = suFake.catCalls.get()
        assertTrue(catBeforeRevoke > 0)

        // Revoke: next probe on the SAME instance fails and clears evidence.
        suFake.rootOk = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && !vm.state.value.writeReady }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertFalse("revoked ROOT must not be writable", vm.state.value.writeReady)

        vm.selectChannel(ChannelPreference.LSPOSED)
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
            suFake.catCalls.get(),
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
        assertEquals("explicit LSPOSED must never run `su -c id -u`", 0, suFake.idUCalls.get())
        assertEquals("explicit LSPOSED must never probe Shizuku", 0, shizukuFake.listUsersCalls.get())
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

    // Phase 4 A8 (reverse swap, reuseFactory): SHIZUKU -> NOOP expires
    // pmReadable to null. AUTO degraded to SHIZUKU with refresh-proven true;
    // revoking Shizuku degrades to NOOP, and the stale true must not survive
    // (probe-only recheck leaves unknown, never the old true).
    @Test
    fun phase4_shizukuToNoop_expiresPmReadable() {
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
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
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU }
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.pmReadable == true }
        assertEquals(ResolvedChannel.SHIZUKU, vm.state.value.resolvedGateway)

        shizukuFake.shizukuOk = false
        vm.recheckPrivilege()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.NOOP
        }
        val s = vm.state.value
        assertNull("SHIZUKU->NOOP must expire pmReadable, got ${s.pmReadable}", s.pmReadable)
        assertEquals("PM未知", s.pmReadableText())
        assertFalse(s.writeReady)
        assertFalse(s.anyProbePassed)
    }

    // Phase 4 A8 (reverse, reuseFactory): NOOP -> ROOT keeps pending null.
    // While NOOP a remove request is refused (fail closed, no dialog); after
    // recovery the pending is still null so the user must request again.
    @Test
    fun phase4_noopToRoot_pendingStaysNull() {
        val suFake = FakeSu(rootOk = false)
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
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.NOOP }
        assertNull(vm.state.value.pendingRemove)

        vm.requestRemove("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.msgKind == MsgKind.CHANNEL }
        assertNull("NOOP must never open a pending (fail closed)", vm.state.value.pendingRemove)

        suFake.rootOk = true
        vm.reconnectAndRefresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.resolvedGateway == ResolvedChannel.ROOT &&
                vm.state.value.pmReadable == true
        }
        val s = vm.state.value
        assertTrue(s.writeReady)
        assertNull("NOOP->ROOT must keep pending null (must re-request)", s.pendingRemove)
    }

    // Phase 4 A8 (reuseFactory): single-channel revoke retains pmReadable.
    // AUTO stays ROOT (su still proven) while only Shizuku dies; readiness
    // holds so the last refresh readability (true) is retained, not nulled.
    @Test
    fun phase4_singleChannelRevoke_retainsPmReadable() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = true)
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
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.pmReadable == true }

        shizukuFake.shizukuOk = false
        vm.recheckPrivilege()
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.ROOT }
        // Settle: a stray second probe would flip busy again; require
        // quiescence before reading the retained field.
        Thread.sleep(200)
        await { !vm.state.value.loading }
        val s = vm.state.value
        assertEquals(ResolvedChannel.ROOT, s.resolvedGateway)
        assertTrue(s.writeReady)
        assertEquals("single-channel revoke with readiness held must retain true", true, s.pmReadable)
        assertEquals("已就緒", s.readyMark())
    }

    // Phase 4 A8 (reuseFactory): LSPOSED <-> AUTO expires pmReadable both ways.
    // Any gateway swap nulls the last refresh snapshot; only a fresh refresh
    // rebuilds true/false.
    @Test
    fun phase4_lsposedAutoSwap_expiresPmReadable() {
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

        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.pmReadable == false
        }
        // Swap nulls then refresh proves false (NoOp ChannelError): stale true
        // must be gone (null transient is already covered by the swap; final
        // false proves the old snapshot did not survive).
        assertEquals(false, vm.state.value.pmReadable)
        assertEquals("PM不可讀", vm.state.value.pmReadableText())
        assertFalse(vm.state.value.writeReady)

        vm.selectChannel(ChannelPreference.AUTO)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.AUTO &&
                vm.state.value.pmReadable == true
        }
        assertEquals("已就緒", vm.state.value.readyMark())
    }

    // Phase 4 C3: refresh runs the single-snapshot path with a post-list
    // fence (DualViewModel.refreshLocked -> repo.refreshVerified() 8):
    // snapshot(1) + typed-target(3) + list(1) + post-list re-verify(3).
    // Assert exact per-command deltas on the SHIZUKU fake (repo lives
    // there after degrade).
    @Test
    fun phase4_refresh_commandCounts() {
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

        shizukuFake.listUsersCalls.set(0)
        shizukuFake.verboseCalls.set(0)
        shizukuFake.dumpsysCalls.set(0)
        shizukuFake.getpropCalls.set(0)
        shizukuFake.listPackagesCalls.set(0)
        val suIdUBefore = suFake.idUCalls.get()

        vm.refresh()
        await {
            !vm.state.value.loading &&
                vm.state.value.msg.contains("no clones installed")
        }
        // Settle before reading exact counts (Unconfined trailing run would
        // otherwise race the assertion).
        Thread.sleep(200)
        await { !vm.state.value.loading }

        assertEquals("refresh snapshot listUsers x1", 1, shizukuFake.listUsersCalls.get())
        assertEquals("refresh target fetches verbose x2 (first + post-list re-verify)", 2, shizukuFake.verboseCalls.get())
        assertEquals("refresh target fetches dumpsys x2 (first + post-list re-verify)", 2, shizukuFake.dumpsysCalls.get())
        assertEquals("refresh target fetches getprop x2 (first + post-list re-verify)", 2, shizukuFake.getpropCalls.get())
        assertEquals("refresh list probe x1", 1, shizukuFake.listPackagesCalls.get())
        assertEquals("pure refresh must never probe su", suIdUBefore, suFake.idUCalls.get())
    }

    // Phase 4 B4 glossary: 禁用 = not-ready fail-closed (generic NoOp +
    // explicit fail); 拒絕 = LSPOSED policy placeholder. Each of the three
    // notReadyMsg branches must carry its keyword.
    @Test
    fun phase4_notReadyMsg_glossary() {
        // 1) LSPOSED placeholder -> 佔位 + 拒絕 (init probe only, no refresh
        // overwrite: selectChannel would append refresh ChannelError, so pin
        // the probe message via a fresh LSPOSED init).
        run {
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
            Thread.sleep(200)
            await { !vm.state.value.loading }
            val msg = vm.state.value.msg
            assertTrue("LSPOSED must name 佔位, got: \"$msg\"", msg.contains("佔位"))
            assertTrue("LSPOSED policy uses 拒絕, got: \"$msg\"", msg.contains("拒絕"))
        }
        // 2) Explicit fail (ROOT denied) -> 明選不跨通道 + 禁用.
        run {
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
            val msg = vm.state.value.msg
            assertTrue("explicit fail must name 明選不跨通道, got: \"$msg\"", msg.contains("明選不跨通道"))
            assertTrue("not-ready uses 禁用 (fail-closed), got: \"$msg\"", msg.contains("禁用"))
        }
        // 3) Generic NoOp fallback (explicit with NONE gateway, no degrade note)
        // -> 通道未就緒 + 禁用 (fail-closed, distinct from LSPOSED 拒絕).
        run {
            val suFake = FakeSu(rootOk = false)
            val shizukuFake = FakeShizuku(shizukuOk = false)
            val vm = DualViewModel(
                repo = DualRepository(NoOpGateway()),
                gatewayPrivilege = Privilege.NONE,
                elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
                preference = ChannelPreference.ROOT,
                appPackageName = "com.example.test",
                persistPreference = null,
                suPresentCheck = { false },
                shizukuAuthCheck = { false },
                suGateway = suFake,
                shizukuGateway = shizukuFake,
                activeGateway = NoOpGateway(),
                currentChannel = ResolvedChannel.NOOP,
                hookDirectReads = { List(6) { MarkerRead.Denied } },
                hookDiagnosticGateways = { emptyList() },
                blockText = zhTestBlockText(),
            )
            vms.add(vm)
            await { !vm.state.value.loading && vm.state.value.msgKind == MsgKind.CHANNEL }
            Thread.sleep(200)
            await { !vm.state.value.loading }
            val msg = vm.state.value.msg
            assertTrue("NoOp fallback must name 通道未就緒, got: \"$msg\"", msg.contains("通道未就緒"))
            assertTrue("not-ready uses 禁用 (fail-closed), got: \"$msg\"", msg.contains("禁用"))
            assertFalse("generic fallback must not use 拒絕 (LSPOSED-only), got: \"$msg\"", msg.contains("拒絕"))
        }
    }

    // P2-1: AUTO -> LSPOSED (picker switch stays UNKNOWN, zero `cat`) ->
    // revoke while parked on LSPOSED (rootOk=false) -> auto
    // recheckPrivilege() must not execute any retained su `cat` (fresh `su
    // -c` process, no session persistence: auto exec risks an auth prompt or
    // a 60 s stall). Asserts su exec counters frozen, hook UNKNOWN_*,
    // writeReady false (diagnosis never raises readiness).
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        val catAfterInit = suFake.catCalls.get()
        assertTrue("precondition: init must have served `cat`", catAfterInit > 0)

        // Picker switch to LSPOSED: NOT consent -- stays UNKNOWN with zero
        // new `cat` (retained slot populated but unused, writeReady false).
        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
        val catAfterSelect = suFake.catCalls.get()
        assertEquals(
            "picker switch must not `cat` the retained instance",
            catAfterInit,
            catAfterSelect,
        )

        // Revoke while parked (no probing channel selected): the retained
        // `cat` would now fail -- auto must not discover this by executing.
        suFake.rootOk = false
        val catBefore = suFake.catCalls.get()
        val idUBefore = suFake.idUCalls.get()
        val listBefore = suFake.listUsersCalls.get()
        val shizukuListBefore = shizukuFake.listUsersCalls.get()
        val shizukuCatBefore = shizukuFake.catCalls.get()

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
            suFake.catCalls.get(),
        )
        assertEquals(
            "auto recheck must not run `su -c id -u` (LSPOSED probes none)",
            idUBefore,
            suFake.idUCalls.get(),
        )
        assertEquals(
            "auto recheck must not run `pm list users` via su",
            listBefore,
            suFake.listUsersCalls.get(),
        )
        assertEquals(
            "auto recheck must not probe Shizuku",
            shizukuListBefore,
            shizukuFake.listUsersCalls.get(),
        )
        assertEquals(
            "auto recheck must not `cat` via Shizuku",
            shizukuCatBefore,
            shizukuFake.catCalls.get(),
        )
        // Fresh post-swap instances are never verified under LSPOSED (no
        // probes), so they must stay fully untouched -- no non-consented
        // round executed anything.
        val newSu = createdSu.single()
        val newShizuku = createdShizuku.single()
        assertEquals("fresh instance must never run `su -c id -u`", 0, newSu.idUCalls.get())
        assertEquals("fresh instance must never be `cat`ed", 0, newSu.catCalls.get())
        assertEquals("fresh Shizuku must never be probed", 0, newShizuku.listUsersCalls.get())
        assertEquals("fresh Shizuku must never be `cat`ed", 0, newShizuku.catCalls.get())
    }

    // P2-1: consented opt-in still reads (only requestHookRedDiagnosis
    // triggers `cat` and recovers ACTIVE while authorized). Guards the other
    // half of the split: picker/reconnect/auto stay silent, the dedicated
    // entry stays capable.
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        // Picker switch is NOT consent: stays UNKNOWN with zero new `cat`
        // even while authorized; the consented entry below re-reads.
        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val catAfterSelect = suFake.catCalls.get()
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)

        // Auto stays UNKNOWN (direct-only) even while authorized...
        vm.recheckPrivilege()
        await {
            !vm.state.value.loading &&
                (vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE)
        }
        Thread.sleep(200)
        await { !vm.state.value.loading }
        val catAfterAuto = suFake.catCalls.get()
        assertEquals("auto must not `cat`", catAfterSelect, catAfterAuto)
        assertFalse(vm.state.value.writeReady)

        // ...but the consented entry re-reads the marker through the retained
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
            suFake.catCalls.get() > catAfterAuto,
        )
        assertEquals(
            "fresh instance stays un-`cat`ed: diagnosis rode the retained old one",
            0,
            createdSu.single().catCalls.get(),
        )
    }

    // P2-1: a consented privileged read that proves the retained channel
    // dead (parked revoke) clears the retained slot, so the NEXT auto or
    // consented round performs no new root exec (no retry storm, no prompt).
    // Also locks that non-consented operations never trigger new root
    // commands after the clear (fake counters frozen).
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
        // explicitRootToLsposed_sameInstance_manualOnlyActiveWithoutRaisingWriteReady.
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        // P2-1: picker switch is NOT consent -- stays UNKNOWN with zero new
        // `cat`; death is proven only by the consented round below.
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)

        // Revoke while parked, then prove death via ONE consented exec.
        suFake.rootOk = false
        val catBeforeManual = suFake.catCalls.get()
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                (vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE)
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "consented diagnosis must attempt exactly the proving `cat`",
            suFake.catCalls.get() > catBeforeManual,
        )
        assertFalse(vm.state.value.writeReady)
        val catAfterProving = suFake.catCalls.get()
        val idUAfterProving = suFake.idUCalls.get()

        // After the clear, further auto AND manual rounds must not exec.
        vm.recheckPrivilege()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(
            "cleared slot: auto must not re-exec root",
            catAfterProving,
            suFake.catCalls.get(),
        )
        assertEquals(idUAfterProving, suFake.idUCalls.get())

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
            "cleared slot: second consented round must not re-exec root",
            catAfterProving,
            suFake.catCalls.get(),
        )
        assertEquals(idUAfterProving, suFake.idUCalls.get())
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
    }

    // P2-1 (narrow-consent matrix): ROOT once usable -> picker switch to
    // LSPOSED -> revoke grant -> sequentially trigger the picker-switch,
    // reconnect-tap, and Shizuku-button (auto recheck) paths. The retained su
    // exec counters must stay frozen throughout (zero new `cat`, zero new
    // `id -u` / `pm list users`); hook stays UNKNOWN_*, writeReady stays
    // false. A final dedicated consented diagnosis is then allowed exactly
    // one controlled `cat` attempt, which fails under revoke (UNKNOWN_* +
    // slot cleared), and every post-clear round stays silent.
    @Test
    fun lsposed_revoked_allNonConsentedPaths_zeroExec_thenConsentedProvingCat() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        suFake.markerContent = marker
        val shizukuFake = FakeShizuku(shizukuOk = false)
        // Rebuild factory (production behavior): every swap mints fresh
        // instances, so the revoked-while-parked instance can only survive in
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = rebuildFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        // Picker switch to LSPOSED: retained slot populated but unused --
        // UNKNOWN with zero new `cat`.
        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)

        // Revoke while parked (no probing channel selected).
        suFake.rootOk = false
        fun suExecTotal(): Int =
            suFake.catCalls.get() + suFake.idUCalls.get() + suFake.listUsersCalls.get()
        fun shizukuExecTotal(): Int =
            shizukuFake.catCalls.get() + shizukuFake.listUsersCalls.get()
        val suBase = suExecTotal()
        val shizukuBase = shizukuExecTotal()
        fun assertZeroGrowthAcrossNonConsentedPaths(step: String) {
            assertEquals("$step: retained su must see zero new exec", suBase, suExecTotal())
            assertEquals("$step: shizuku must see zero new exec", shizukuBase, shizukuExecTotal())
            assertTrue(
                "$step: hook must stay UNKNOWN_*, got: ${vm.state.value.hookStatus}",
                vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
            )
            assertFalse("$step: diagnosis must never raise writeReady", vm.state.value.writeReady)
            assertFalse("$step: placeholder is never channelReady", vm.state.value.channelReady)
        }

        // Path 1 -- picker same-pref switch (re-probe + refresh, still
        // LSPOSED, direct-only).
        vm.selectChannel(ChannelPreference.LSPOSED)
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertZeroGrowthAcrossNonConsentedPaths("picker same-pref switch")

        // Path 2 -- 重新連線並更新 tap (auto: allowPrivilegedHookDiagnosis=false).
        vm.reconnectAndRefresh()
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertZeroGrowthAcrossNonConsentedPaths("reconnect tap")

        // Path 3 -- Shizuku button / binder auto path (recheck, auto).
        vm.recheckPrivilege()
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertZeroGrowthAcrossNonConsentedPaths("shizuku-button auto path")

        // Fresh post-swap instances are never verified under LSPOSED (no
        // probes), so they must stay fully untouched throughout.
        val newSu = createdSu.single()
        val newShizuku = createdShizuku.single()
        assertEquals("fresh su must never run `su -c id -u`", 0, newSu.idUCalls.get())
        assertEquals("fresh su must never run `pm list users`", 0, newSu.listUsersCalls.get())
        assertEquals("fresh su must never be `cat`ed", 0, newSu.catCalls.get())
        assertEquals("fresh Shizuku must never be probed", 0, newShizuku.listUsersCalls.get())
        assertEquals("fresh Shizuku must never be `cat`ed", 0, newShizuku.catCalls.get())

        // The dedicated consented entry is allowed exactly one controlled
        // diagnosis attempt (SIX `cat`s, one per marker path, on the single
        // retained instance), which fails under revoke:
        // UNKNOWN_* + slot cleared.
        val catBeforeConsented = suFake.catCalls.get()
        vm.requestHookRedDiagnosis()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(
            "consented diagnosis must attempt exactly one proving diagnosis (six per-path `cat`s)",
            catBeforeConsented + 6,
            suFake.catCalls.get(),
        )
        assertTrue(
            "revoked proving round stays UNKNOWN_*, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)

        // Post-clear: every further round (auto AND consented) stays silent.
        val catAfterClear = suFake.catCalls.get()
        vm.recheckPrivilege()
        Thread.sleep(300)
        await { !vm.state.value.loading }
        vm.requestHookRedDiagnosis()
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "cleared slot stays UNKNOWN_*, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertEquals(
            "cleared slot: no further consented round may re-exec root",
            catAfterClear,
            suFake.catCalls.get(),
        )
        assertEquals(
            "cleared slot: exactly one proving diagnosis total since revoke",
            suBase + 6,
            suExecTotal(),
        )
        assertFalse(vm.state.value.writeReady)
        assertFalse(vm.state.value.channelReady)
    }

    // P2-1 (sticky-privilege): the recheck loop consumes the privileged flag
    // per round, so a trailing auto round after a consented round never
    // inherits privilege. Leading auto + queued requestHookRedDiagnosis +
    // auto arriving during the consented trailing round: the second trailing
    // round must run auto (zero new `cat`), proving the grant did not leak
    // across rounds. A gated direct-read seam holds each diagnosis round so
    // the three rounds sequence deterministically. (Reconnect taps are NOT
    // consent post P2-1, so the privileged path is exercised via the
    // dedicated consented entry, not reconnectAndRefresh.)
    @Test
    fun recheckDrain_privilegedFlagNotStickyAcrossRounds() {
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
                List(6) { MarkerRead.Denied }
            },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        // Park on LSPOSED via a picker tap: NOT consent, stays UNKNOWN with
        // zero new `cat` (retained slot populated but unused).
        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        gating.set(true)
        val roundsBase = entered.get()
        val catBase = suFake.catCalls.get()

        // Leading auto round, held inside direct reads.
        vm.recheckPrivilege()
        await { entered.get() >= roundsBase + 1 }
        // Consented entry queued behind the leading auto (pending + flag).
        vm.requestHookRedDiagnosis()
        // Leading completes; trailing round 1 (consented) starts and blocks.
        gate.release()
        await { entered.get() >= roundsBase + 2 }
        // Auto arrives during the consented trailing round.
        vm.recheckPrivilege()
        // Trailing round 1 (consented) runs: retained `cat`, back to ACTIVE.
        gate.release()
        await { entered.get() >= roundsBase + 3 }
        assertEquals(
            "consented trailing round must restore ACTIVE, got: ${vm.state.value.hookStatus}",
            HookStatus.ACTIVE,
            vm.state.value.hookStatus,
        )
        val catAfterConsented = suFake.catCalls.get()
        assertTrue(
            "consented trailing round must `cat` the retained instance",
            catAfterConsented > catBase,
        )
        // Trailing round 2 must be auto: no new `cat`, back to UNKNOWN_*.
        gate.release()
        await { !vm.state.value.loading }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertEquals(
            "second trailing round must not inherit privilege (no new `cat`)",
            catAfterConsented,
            suFake.catCalls.get(),
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
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
        assertTrue("denied `cat` must still have been attempted", suFake.catCalls.get() > 0)
    }

    // P2-1 (re-verify recovery): after a consented proving `cat` clears
    // the retained slot, leaving LSPOSED and re-proving ROOT restores the
    // retained instance, so a later consented LSPOSED diagnosis is ACTIVE
    // again (one revoked round never permanently loses diagnosis).
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
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }

        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        // P2-1: picker switch is NOT consent -- stays UNKNOWN with zero new
        // `cat`; death is proven only by the consented round below.
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)

        // Revoke while parked; one consented round proves death and clears.
        suFake.rootOk = false
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                (vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                    vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE)
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val catAfterClear = suFake.catCalls.get()
        assertTrue(
            "proving round stays UNKNOWN_*, got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)

        // Re-verify: auth restored, back to ROOT; the probe passes again and
        // the retained slot is re-pinned (diagnosis ACTIVE via current).
        suFake.rootOk = true
        vm.selectChannel(ChannelPreference.ROOT)
        await { !vm.state.value.loading && vm.state.value.writeReady }
        await { vm.state.value.hookStatus == HookStatus.ACTIVE }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val restored = vm.state.value
        assertEquals(ChannelPreference.ROOT, restored.channel)
        assertTrue("re-proven ROOT is writable again", restored.writeReady)
        assertEquals(HookStatus.ACTIVE, restored.hookStatus)

        // Park on LSPOSED again: the picker switch itself stays UNKNOWN
        // (zero `cat`); the consented entry then rides the RESTORED retained
        // instance back to ACTIVE (zero new probes, not writable).
        val idUBefore = suFake.idUCalls.get()
        val catBeforeRepark = suFake.catCalls.get()
        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        assertTrue(
            "re-park picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse(vm.state.value.writeReady)
        assertEquals(
            "re-park picker switch must not `cat`",
            catBeforeRepark,
            suFake.catCalls.get(),
        )

        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
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
            suFake.catCalls.get() > catAfterClear,
        )
        assertEquals("re-park still runs zero `id -u` probes", idUBefore, suFake.idUCalls.get())
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

    // Phase 3 F1 + P2-1 (narrow consent): cold-start AUTO degrade keeps
    // evidence consistent (degrade-first, so the round's evidence binds the
    // post-degrade generation). After init degrades ROOT -> SHIZUKU, a picker
    // LSPOSED switch stays UNKNOWN (direct-only, zero retained `cat`); only
    // the consented requestHookRedDiagnosis reaches ACTIVE through the
    // retained Shizuku instance.
    @Test
    fun phase3_coldStartAutoDegrade_evidenceConsistent() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = false)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        shizukuFake.markerContent = marker
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
            shizukuAuth = true,
            initialChannel = ResolvedChannel.ROOT,
            initialGateway = suFake,
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = null,
            assembleChannelFn = reuseFactory,
        )
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.SHIZUKU }
        val degraded = vm.state.value
        assertEquals(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU, degraded.degrade!!.kind)
        assertTrue("degrade note expected, got: \"${zhTestBlockText()(degraded.degrade!!)}\"", zhTestBlockText()(degraded.degrade!!).contains("降級"))
        assertTrue(degraded.writeReady)

        vm.selectChannel(ChannelPreference.LSPOSED)
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        // Picker switch is NOT consent: UNKNOWN with zero retained `cat`.
        val parkedCatBefore = shizukuFake.catCalls.get()
        assertTrue(
            "picker switch must stay UNKNOWN (direct-only), got: ${vm.state.value.hookStatus}",
            vm.state.value.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                vm.state.value.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
        assertFalse("diagnosis must never raise writeReady", vm.state.value.writeReady)
        assertEquals(
            "picker switch must not `cat` the retained instance",
            parkedCatBefore,
            shizukuFake.catCalls.get(),
        )

        // Only the consented entry reaches ACTIVE through the retained
        // Shizuku instance.
        vm.requestHookRedDiagnosis()
        await {
            !vm.state.value.loading &&
                vm.state.value.channel == ChannelPreference.LSPOSED &&
                vm.state.value.hookStatus == HookStatus.ACTIVE
        }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val parked = vm.state.value
        assertEquals(HookStatus.ACTIVE, parked.hookStatus)
        assertFalse("diagnosis must never raise writeReady", parked.writeReady)
        assertTrue(
            "consented diagnosis must `cat` the retained instance",
            shizukuFake.catCalls.get() > parkedCatBefore,
        )
    }

    // Phase 3 F2/P3: cross-gate waiting stays busy. A recheck arriving while
    // a refresh tail is gated on `pm list packages` must read loading=true
    // for the whole wait (leading busy set synchronously at launch).
    @Test
    fun phase3_crossGateRecheckDuringGatedRefresh_staysBusy() {
        val gate = CompletableDeferred<Unit>()
        val listPackagesCalls = AtomicInteger(0)
        val suFake = object : CmdGateway {
            override val privilege: Privilege = Privilege.ROOT
            val idUCalls = AtomicInteger(0)
            private fun usersOut(): String =
                "Users:\n\tUserInfo{0:Owner:13} running\n" +
                    "\tUserInfo{95:DualApp:400} running\n"
            private fun verboseOut(): String =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    Serial: 15\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
            private fun dumpsysOut(): String =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.CLONE\n"
            override suspend fun run(cmd: String): CmdResult = when {
                cmd.trim() == "id -u" -> {
                    idUCalls.incrementAndGet()
                    CmdResult(exit = 0, out = "0\n", err = "")
                }
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = usersOut(), err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = "95\n", err = "")
                cmd.startsWith("pm list packages") -> {
                    if (listPackagesCalls.incrementAndGet() == 1) gate.await()
                    CmdResult(exit = 0, out = "", err = "")
                }
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val vm = DualViewModel(
            repo = DualRepository(suFake),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = suFake,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        await { !vm.state.value.loading && vm.state.value.writeReady }

        vm.refresh()
        await { listPackagesCalls.get() >= 1 }
        vm.recheckPrivilege()
        val busyDeadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < busyDeadline) {
            assertTrue(
                "recheck queued behind a gated refresh tail must stay busy, state=${vm.state.value}",
                vm.state.value.loading,
            )
            Thread.sleep(10)
        }
        gate.complete(Unit)
        await(timeoutMs = 10_000L) { !vm.state.value.loading && vm.state.value.writeReady }
        assertTrue(vm.state.value.writeReady)
    }

    // Phase 3 F3: id-ok/pm-fail refuses writes (fail-closed PM gate). Probe
    // passes (writeReady true) but `pm list users` proved unreadable, so
    // clone must be refused with the PM message and run zero device writes.
    @Test
    fun phase3_pmUnreadable_refusesWrite() {
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
        vm.refresh()
        await { !vm.state.value.loading && vm.state.value.pmReadable == false }
        assertTrue("probe gate stays true on `id -u == 0`", vm.state.value.writeReady)

        val idUBefore = suFake.idUCalls.get()
        vm.clone("com.example.chat")
        await { !vm.state.value.loading && vm.state.value.msg.contains("PM 不可讀") }
        val s = vm.state.value
        assertEquals(MsgKind.CHANNEL, s.msgKind)
        assertTrue("refusal must name PM unreadability, got: \"${s.msg}\"", s.msg.contains("PM 不可讀"))
        assertTrue("probe gate unchanged by the refusal", s.writeReady)
        assertEquals("refused clone must not run new probes", idUBefore, suFake.idUCalls.get())
        assertFalse("refused clone must not claim success", s.msg.contains("cloned:"))
    }

    // Phase 3 F4: a swap that never completes (cancelled/failed assembly)
    // must never persist: stored stays != memory is forbidden.
    // Phase 4 C1: gated start, tolerance window for the absence -- await
    // loading==true to prove the select body ran its leading busy (Unconfined
    // runs it synchronously on the caller thread), then poll a short 200 ms
    // tolerance window for any stray persist. No quiescence (loading==false)
    // gate here by design: the cancelled select never clears loading itself
    // (CE convention, successor owns it, and there is no successor), so
    // awaiting loading==false would time out. The stuck-busy assertion below
    // locks that convention (a future change that clears loading on cancel
    // must update this test + the convention note in selectChannel).
    @Test
    fun phase3_failedSwap_neverPersists() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val persisted = mutableListOf<ChannelPreference>()
        val failingFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            throw CancellationException("test cancel before swap")
        }
        val vm = DualViewModel(
            repo = DualRepository(suFake),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = persisted::add,
            suPresentCheck = { true },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = suFake,
            currentChannel = ResolvedChannel.ROOT,
            assembleChannelFn = failingFactory,
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        await { !vm.state.value.loading }
        vm.selectChannel(ChannelPreference.SHIZUKU)
        // Start gate: the leading busy is set synchronously on the caller
        // thread (Unconfined), so this proves the select body ran before the
        // tolerance window below. No quiescence (loading==false) gate here by
        // design: the cancelled select never clears loading itself (CE
        // convention, successor owns it, and there is no successor), so
        // awaiting loading==false would time out. Poll a short tolerance
        // window for any stray persist instead.
        await { vm.state.value.loading }
        Thread.sleep(200)
        assertTrue("failed swap must never persist, got: $persisted", persisted.isEmpty())
        assertEquals("in-memory preference unchanged", ChannelPreference.AUTO, vm.state.value.channel)
        assertTrue(
            "cancelled select holds busy (successor owns loading, no successor here)",
            vm.state.value.loading,
        )
    }

    // Phase 3 F5 (CE successor convention): a select cancelled mid-probe by
    // a newer tap settles without a wedge -- the successor owns loading and
    // the final state converges (loading false, last channel wins).
    @Test
    fun phase3_cancelThenReselect_loadingSettles() {
        val probeGate = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)
        val idUCalls = AtomicInteger(0)
        val suFake = object : CmdGateway {
            override val privilege: Privilege = Privilege.ROOT
            private fun usersOut(): String =
                "Users:\n\tUserInfo{0:Owner:13} running\n" +
                    "\tUserInfo{95:DualApp:400} running\n"
            private fun verboseOut(): String =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    Serial: 15\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
            private fun dumpsysOut(): String =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.CLONE\n"
            override suspend fun run(cmd: String): CmdResult = when {
                cmd.trim() == "id -u" -> {
                    idUCalls.incrementAndGet()
                    probeGate.get()?.await()
                    CmdResult(exit = 0, out = "0\n", err = "")
                }
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(exit = 0, out = usersOut(), err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verboseOut(), err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut(), err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = "95\n", err = "")
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = 0, out = "", err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }
        val shizukuFake = FakeShizuku(shizukuOk = true)
        val reuseFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            val channel = pref.resolveChannel(suPresent, shizukuAuth)
            val gateway: CmdGateway = when (channel) {
                ResolvedChannel.ROOT -> suFake
                ResolvedChannel.SHIZUKU -> shizukuFake
                else -> NoOpGateway()
            }
            ChannelAssembly(gateway, suFake, shizukuFake, channel)
        }
        val vm = DualViewModel(
            repo = DualRepository(suFake),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { true },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = suFake,
            currentChannel = ResolvedChannel.ROOT,
            assembleChannelFn = reuseFactory,
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = { emptyList() },
        )
        vms.add(vm)
        await { !vm.state.value.loading }
        idUCalls.set(0)
        probeGate.set(CompletableDeferred())

        vm.selectChannel(ChannelPreference.ROOT)
        await { idUCalls.get() >= 1 }
        assertTrue("loser select must hold busy while its probe is gated", vm.state.value.loading)
        vm.selectChannel(ChannelPreference.SHIZUKU)
        probeGate.getAndSet(null)?.complete(Unit)
        await(timeoutMs = 10_000L) {
            !vm.state.value.loading && vm.state.value.channel == ChannelPreference.SHIZUKU
        }
        val s = vm.state.value
        assertEquals(ChannelPreference.SHIZUKU, s.channel)
        assertFalse("successor must settle loading (no CE wedge)", s.loading)
        assertTrue("winner SHIZUKU with proven shell must be writable", s.writeReady)
    }

    // Phase 3 F6: non-LSPOSED diagnosis uses the same identity gate as
    // LSPOSED. A stale caller-supplied list holding a displaced old ROOT
    // instance must not serve `cat` even though the current probe passed.
    @Test
    fun phase3_nonLsposedStaleList_excluded() {
        val marker = HookMarker.buildContent(setOf(HookMarker.SCOPE_SYSTEM_SERVER), 9L)
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val staleSu = FakeSu(rootOk = true)
        staleSu.markerContent = marker
        val vm = DualViewModel(
            repo = DualRepository(suFake),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(suFake), ShizukuElevator(shizukuFake)),
            preference = ChannelPreference.ROOT,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = suFake,
            shizukuGateway = shizukuFake,
            activeGateway = suFake,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { List(6) { MarkerRead.Denied } },
            hookDiagnosticGateways = { listOf(staleSu) },
        )
        vms.add(vm)
        await { !vm.state.value.loading && vm.state.value.writeReady }
        Thread.sleep(300)
        await { !vm.state.value.loading }
        val s = vm.state.value
        assertTrue("readiness follows the current probe", s.writeReady)
        assertEquals(
            "stale instance must never serve `cat`",
            0,
            staleSu.catCalls.get(),
        )
        assertTrue(
            "stale-only candidates must yield UNKNOWN_*, got: ${s.hookStatus}",
            s.hookStatus == HookStatus.UNKNOWN_NO_DIAGNOSTIC ||
                s.hookStatus == HookStatus.UNKNOWN_UNREADABLE,
        )
    }

    // Phase 3 F8: same-pref select skips the rebuild (no factory churn) but
    // still re-probes + refreshes.
    @Test
    fun phase3_samePrefSelect_skipsRebuild() {
        val suFake = FakeSu(rootOk = true)
        val shizukuFake = FakeShizuku(shizukuOk = false)
        val factoryCalls = AtomicInteger(0)
        val reuseFactory = { pref: ChannelPreference, suPresent: Boolean, shizukuAuth: Boolean, pkg: String ->
            factoryCalls.incrementAndGet()
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
        await { !vm.state.value.loading && vm.state.value.writeReady }
        factoryCalls.set(0)
        val idUBefore = suFake.idUCalls.get()
        vm.selectChannel(ChannelPreference.AUTO)
        await { !vm.state.value.loading && suFake.idUCalls.get() > idUBefore }
        Thread.sleep(200)
        await { !vm.state.value.loading }
        assertEquals("same-pref select must not rebuild", 0, factoryCalls.get())
        assertEquals(ChannelPreference.AUTO, vm.state.value.channel)
        assertTrue("same-pref select still re-probes", vm.state.value.writeReady)
    }

    // Phase 3 F7: AUTO recovery/upgrade back to ROOT is silent by design
    // (empty note). Only downward overrides record a reason.
    @Test
    fun phase3_autoUpgradeToRoot_silent() {
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
        assertTrue("downward note expected, got: \"${vm.state.value.degrade?.let { zhTestBlockText()(it) }}\"", vm.state.value.degrade != null && zhTestBlockText()(vm.state.value.degrade!!).contains("唯讀"))

        suFake.rootOk = true
        vm.recheckPrivilege()
        await { !vm.state.value.loading && vm.state.value.resolvedGateway == ResolvedChannel.ROOT }
        val s = vm.state.value
        assertTrue("upgraded ROOT must be writable", s.writeReady)
        assertTrue("upgrade to ROOT is silent by design, got: \"${s.degrade}\"", s.degrade == null)
    }
}
