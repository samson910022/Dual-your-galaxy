// MIT License
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LifecycleSeparationTest {

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

    private class MapBackend {
        val map = mutableMapOf<String, String?>()
        fun store(): SelectionStore = SelectionStore(
            loadRaw = { map[SELECTION_KEY] },
            saveRaw = { raw ->
                if (raw == null) map.remove(SELECTION_KEY) else map[SELECTION_KEY] = raw
                true
            },
        )
    }

    private class FakeClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }
    }

    private class FakeRepo(
        var dualId: Int = 95,
        val rootOk: Boolean = true,
        var dualSerial: Int = 15,
        val installedForUser: MutableSet<String> = mutableSetOf(),
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        val installCalls = mutableListOf<String>()
        val uninstallCalls = mutableListOf<String>()

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                "    Serial: $dualSerial\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} serialNo=$dualSerial:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS ->
                CmdResult(exit = 0, out = usersOut(), err = "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm install-existing") -> {
                val pkg = cmd.trim().split(" ").last()
                installCalls.add(pkg)
                installedForUser.add(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                uninstallCalls.add(pkg)
                installedForUser.remove(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") ->
                CmdResult(
                    exit = 0,
                    out = installedForUser.map { "package:$it" }.joinToString("\n"),
                    err = "",
                )
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    private fun makeVm(
        gw: FakeRepo,
        clock: FakeClock,
        store: SelectionStore?,
    ): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(gw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = gw,
            shizukuGateway = ShizukuCmdGateway("com.example.test"),
            activeGateway = gw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
            selectionStore = store,
            blockText = zhTestBlockText(),
        )
        vms.add(vm)
        return vm
    }

    private fun catalog(): List<SourceAppEntry> = listOf(
        SourceAppEntry("com.example.chat", "Chat App", 0, false),
        SourceAppEntry("com.example.mail", "Mail", 0, false),
    )

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout waiting for condition")
            }
            Thread.sleep(20)
        }
    }

    private fun awaitVm(vm: DualViewModel, timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        try {
            await(timeoutMs, cond)
        } catch (e: AssertionError) {
            throw AssertionError("timeout waiting for condition; state=${vm.state.value}")
        }
    }

    @Test
    fun rotation_configChange_retainsPendingAndSelection() {
        val gw = FakeRepo()
        gw.installedForUser.add("com.example.chat")
        val clock = FakeClock()
        val backend = MapBackend()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }

        vm.setSourceCatalog(catalog())
        vm.toggleSelection("0:com.example.chat")
        await { vm.state.value.selectedKeys.contains("0:com.example.chat") }

        vm.requestRemove("com.example.chat")
        awaitVm(vm) { vm.state.value.pendingRemove != null }

        val pendingBefore = vm.state.value.pendingRemove
        val selectedBefore = vm.state.value.selectedKeys

        assertNotNull("rotation must retain pending on same ViewModel", vm.state.value.pendingRemove)
        assertEquals(pendingBefore, vm.state.value.pendingRemove)
        assertEquals(selectedBefore, vm.state.value.selectedKeys)
        assertTrue(vm.state.value.selectedKeys.contains("0:com.example.chat"))
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
    }

    @Test
    fun processDeath_restore_reloadsSelection_noAutoResend() {
        val backend = MapBackend()
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG,
                95,
                setOf("0:com.example.chat", "0:com.example.mail"),
                1000L,
            ),
        )

        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }

        val callsBeforeInstall = gw.installCalls.size
        val callsBeforeUninstall = gw.uninstallCalls.size

        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { "0:com.example.chat" in vm.state.value.selectedKeys }

        vm.restoreSelection(95)
        await { "0:com.example.chat" in vm.state.value.selectedKeys }

        assertEquals(
            "selected keys restored from snapshot",
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm.state.value.selectedKeys,
        )
        assertNull("process-death restore starts with null pending", vm.state.value.pendingRemove)
        assertEquals("no auto-resend: zero installs", callsBeforeInstall, gw.installCalls.size)
        assertEquals("no auto-resend: zero uninstalls", callsBeforeUninstall, gw.uninstallCalls.size)
    }

    @Test
    fun coldStart_clean_emptyOrCorruptPrefs() {
        val backendEmpty = MapBackend()
        val gwEmpty = FakeRepo()
        val clockEmpty = FakeClock()
        val vmEmpty = makeVm(gwEmpty, clockEmpty, backendEmpty.store())
        await { vmEmpty.state.value.channelReady }
        assertNull(vmEmpty.state.value.pendingRemove)
        assertTrue(vmEmpty.state.value.selectedKeys.isEmpty())
        assertEquals(0, gwEmpty.installCalls.size)
        assertEquals(0, gwEmpty.uninstallCalls.size)

        vmEmpty.setSourceCatalog(catalog())
        vmEmpty.refresh()
        await { !vmEmpty.state.value.loading }
        assertNull(vmEmpty.state.value.pendingRemove)
        assertTrue(vmEmpty.state.value.selectedKeys.isEmpty())
        assertEquals(0, gwEmpty.installCalls.size)
        assertEquals(0, gwEmpty.uninstallCalls.size)

        val backendCorrupt = MapBackend()
        backendCorrupt.map[SELECTION_KEY] = "garbage-not-a-snapshot"
        val gwCorrupt = FakeRepo()
        val clockCorrupt = FakeClock()
        val vmCorrupt = makeVm(gwCorrupt, clockCorrupt, backendCorrupt.store())
        await { vmCorrupt.state.value.channelReady }
        assertNull(vmCorrupt.state.value.pendingRemove)
        assertTrue(vmCorrupt.state.value.selectedKeys.isEmpty())
        assertEquals(0, gwCorrupt.installCalls.size)
        assertEquals(0, gwCorrupt.uninstallCalls.size)

        vmCorrupt.setSourceCatalog(catalog())
        vmCorrupt.refresh()
        await { !vmCorrupt.state.value.loading }
        assertNull(vmCorrupt.state.value.pendingRemove)
        assertTrue(vmCorrupt.state.value.selectedKeys.isEmpty())
        assertEquals(0, gwCorrupt.installCalls.size)
        assertEquals(0, gwCorrupt.uninstallCalls.size)
        assertNull(backendCorrupt.map[SELECTION_KEY])
    }
}
