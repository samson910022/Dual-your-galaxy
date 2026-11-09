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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val map = ConcurrentHashMap<String, String>()
        val reads = AtomicInteger(0)
        val writes = AtomicInteger(0)
        fun store(): SelectionStore = SelectionStore(
            loadRaw = {
                // Capture FIRST, signal AFTER: awaiting reads>before guarantees
                // the snapshot was already taken (no TOCTOU where a later
                // save() would change the captured value).
                val raw = map[SELECTION_KEY]
                reads.incrementAndGet()
                raw
            },
            saveRaw = { raw ->
                if (raw == null) map.remove(SELECTION_KEY) else map[SELECTION_KEY] = raw
                writes.incrementAndGet()
                true
            },
        )
    }

    private class FakeClock(var tick: Long = 0L) {
        private val counter = AtomicLong(tick)
        fun now(): Long = counter.incrementAndGet() * 1000L
    }

    private class FakeRepo(
        var dualId: Int = 95,
        val rootOk: Boolean = true,
        var dualSerial: Int = 15,
        val installedForUser: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.ROOT
        val installCalls = Collections.synchronizedList(mutableListOf<String>())
        val uninstallCalls = Collections.synchronizedList(mutableListOf<String>())
        // Command audit: every command issued is recorded; writes and any
        // command outside the known allow-list are tracked separately so tests
        // can assert "zero auto-resend" covers ALL command categories.
        // Synchronized because DualViewModel runs repository calls on
        // Dispatchers.IO while the test thread reads sizes concurrently.
        val allCommands = Collections.synchronizedList(mutableListOf<String>())
        val writeCommands = Collections.synchronizedList(mutableListOf<String>())
        val unexpectedCommands = Collections.synchronizedList(mutableListOf<String>())

        private fun hasShellSeparator(cmd: String): Boolean =
            cmd.contains(";") || cmd.contains("&&") || cmd.contains("||") ||
                cmd.contains("\n") || cmd.contains("`") || cmd.contains("$(") ||
                cmd.contains(">") || cmd.contains("<") || cmd.contains("|") ||
                cmd.contains("&") || cmd.contains("\\")

        private val LIST_PACKAGES = Regex("""pm list packages --user \d+""")
        private val INSTALL_RE = Regex("""pm install-existing --user \d+ [A-Za-z0-9._-]+""")
        private val UNINSTALL_RE = Regex("""pm uninstall --user \d+ [A-Za-z0-9._-]+""")

        private fun isWrite(cmd: String): Boolean =
            hasShellSeparator(cmd) ||
                INSTALL_RE.matches(cmd) || UNINSTALL_RE.matches(cmd) ||
                cmd.contains("pm install") || cmd.contains("pm uninstall") ||
                cmd.contains("rm ") || cmd.contains("chmod") || cmd.contains("chown") ||
                cmd.contains("pm grant") || cmd.contains("pm revoke")

        private fun isAllowed(cmd: String): Boolean = when {
            cmd.trim() == "id -u" -> true
            cmd == DualRepository.CMD_LIST_USERS -> true
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> true
            cmd == DualRepository.CMD_DUMPSYS_USER -> true
            cmd == DualRepository.CMD_GETPROP_DUAL -> true
            LIST_PACKAGES.matches(cmd) -> true
            else -> false
        }

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

        override suspend fun run(cmd: String): CmdResult {
            allCommands.add(cmd)
            if (isWrite(cmd)) writeCommands.add(cmd)
            // Writes are tracked separately (writeCommands); only non-write
            // commands outside the read allow-list count as unexpected.
            if (!isWrite(cmd) && !isAllowed(cmd)) unexpectedCommands.add(cmd)
            return when {
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
            LIST_PACKAGES.matches(cmd) ->
                CmdResult(
                    exit = 0,
                    out = installedForUser.map { "package:$it" }.joinToString("\n"),
                    err = "",
                )
            else -> CmdResult(exit = 127, out = "", err = "unexpected command: $cmd")
        }
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

    /**
     * Deterministic quiescence fence: returns only when every child of
     * vm.viewModelScope has completed (mutex wait + onIo + state commit).
     * Preconditions: called after a public API returns under
     * Dispatchers.Unconfined (setMain in setUp), so the subject job is
     * already registered; all DualViewModel jobs are finite ops.
     * Use drain-before + drain-after to prove a negative no-op actually ran.
     */
    private fun awaitScopeDrained(vm: DualViewModel, timeoutMs: Long = 5_000L) {
        val scopeJob = vm.viewModelScope.coroutineContext[Job]
            ?: throw AssertionError("viewModelScope has no Job")
        try {
            await(timeoutMs) { scopeJob.children.none { it.isActive } }
        } catch (e: AssertionError) {
            throw AssertionError("timeout draining viewModelScope; state=${vm.state.value}")
        }
    }

    @Test
    // Config change retains the same ViewModel instance (Android retains VMs
    // across configuration changes), so pendingRemove + selectedKeys survive
    // on the same instance with zero device writes. This does NOT exercise a
    // real Activity.recreate(); real recreate evidence lives in the
    // instrumentation RotationTest (pending_retainedOnSameInstance_zeroWrite /
    // pending_discardedOnNewInstance_zeroWrite /
    // activity_recreate_retainsPendingDialog).
    fun sameViewModel_instance_retainsPendingAndSelection_noWrites() {
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
        assertEquals(0, gw.writeCommands.size)
        assertEquals(0, gw.unexpectedCommands.size)
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

        assertEquals(
            "selected keys restored from snapshot (process-death recovery via refresh)",
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm.state.value.selectedKeys,
        )
        assertNull("process-death restore starts with null pending", vm.state.value.pendingRemove)
        assertEquals("no auto-resend: zero installs", callsBeforeInstall, gw.installCalls.size)
        assertEquals("no auto-resend: zero uninstalls", callsBeforeUninstall, gw.uninstallCalls.size)
        assertEquals(0, gw.writeCommands.size)
        assertEquals(0, gw.unexpectedCommands.size)
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
        assertEquals(0, gwEmpty.writeCommands.size)
        assertEquals(0, gwEmpty.unexpectedCommands.size)

        vmEmpty.setSourceCatalog(catalog())
        vmEmpty.refresh()
        await { vmEmpty.state.value.inventory.targetId > 0 }
        assertNull(vmEmpty.state.value.pendingRemove)
        assertTrue(vmEmpty.state.value.selectedKeys.isEmpty())
        assertEquals(0, gwEmpty.installCalls.size)
        assertEquals(0, gwEmpty.uninstallCalls.size)
        assertEquals(0, gwEmpty.writeCommands.size)
        assertEquals(0, gwEmpty.unexpectedCommands.size)
        assertTrue(gwEmpty.allCommands.isNotEmpty())

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
        assertEquals(0, gwCorrupt.writeCommands.size)
        assertEquals(0, gwCorrupt.unexpectedCommands.size)

        val writesBeforeCorrupt = backendCorrupt.writes.get()
        vmCorrupt.setSourceCatalog(catalog())
        vmCorrupt.refresh()
        await { backendCorrupt.writes.get() > writesBeforeCorrupt && backendCorrupt.map[SELECTION_KEY] == null }
        assertNull(vmCorrupt.state.value.pendingRemove)
        assertTrue(vmCorrupt.state.value.selectedKeys.isEmpty())
        assertEquals(0, gwCorrupt.installCalls.size)
        assertEquals(0, gwCorrupt.uninstallCalls.size)
        assertEquals(0, gwCorrupt.writeCommands.size)
        assertEquals(0, gwCorrupt.unexpectedCommands.size)
        assertNull(backendCorrupt.map[SELECTION_KEY])
    }

    @Test
    fun explicitRestore_isNoOpWhenSelectionNonEmpty() {
        val backend = MapBackend()
        backend.store().save(
            SELECTION_SOURCE_CATALOG,
            95,
            setOf("0:com.example.mail"),
            1000L,
        )
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }

        // Verify target first (inventory.targetId>0) without auto-restoring by
        // making selection non-empty before refresh.
        vm.setSourceCatalog(catalog())
        vm.toggleSelection("0:com.example.chat")
        await { vm.state.value.selectedKeys.contains("0:com.example.chat") }
        vm.refresh()
        await { vm.state.value.inventory.targetId > 0 }
        awaitScopeDrained(vm)
        val readsBeforeRestore = backend.reads.get()

        val selectedBefore = vm.state.value.selectedKeys
        val pendingBefore = vm.state.value.pendingRemove
        assertTrue(selectedBefore.contains("0:com.example.chat"))

        // Selection is non-empty: restore must NOT overwrite (no auto-resend).
        vm.restoreSelection(95)
        awaitScopeDrained(vm)
        // Guard returns before any store I/O.
        assertEquals(readsBeforeRestore, backend.reads.get())
        assertEquals(selectedBefore, vm.state.value.selectedKeys)
        assertEquals(pendingBefore, vm.state.value.pendingRemove)
        // Seeded key from store must not have been merged in.
        assertTrue(vm.state.value.selectedKeys.contains("0:com.example.chat"))
        assertFalse(vm.state.value.selectedKeys.contains("0:com.example.mail"))
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
        assertEquals(0, gw.writeCommands.size)
        assertEquals(0, gw.unexpectedCommands.size)
    }

    @Test
    fun processDeath_corruptPrefs_selfClearsAndRecovers() {
        val backend = MapBackend()
        backend.map[SELECTION_KEY] = "garbage-not-a-snapshot"
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }

        val writesBefore = backend.writes.get()
        vm.setSourceCatalog(catalog())
        vm.refresh()
        // Await the actual self-clear write, not just the inventory transition.
        await { backend.writes.get() > writesBefore && backend.map[SELECTION_KEY] == null }
        assertNull(backend.map[SELECTION_KEY])
        assertNull(vm.state.value.pendingRemove)
        assertTrue(vm.state.value.selectedKeys.isEmpty())

        // Recovery: a fresh save succeeds after self-clear.
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG,
                95,
                setOf("0:com.example.chat"),
                2000L,
            ),
        )
        assertEquals(
            setOf("0:com.example.chat"),
            backend.store().loadSelected(95, null),
        )
    }

    @Test
    fun unverifiedTarget_restore_isNoOpAndPreservesStore() {
        // Empty backend: no pre-existing snapshot to be cleared.
        val backend = MapBackend()
        val gw = FakeRepo(dualId = 96, dualSerial = 41)
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }

        // Refresh verifies target 96. refreshLocked publishes inventory before
        // restoreSelectionLocked does async store I/O (onIo), so await an
        // actual store read to prove restore/cleanup started before seeding.
        val readsBeforeRefresh = backend.reads.get()
        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { backend.reads.get() > readsBeforeRefresh && vm.state.value.inventory.targetId > 0 }
        awaitScopeDrained(vm)
        assertEquals(96, vm.state.value.inventory.targetId)

        // Seed a 95 snapshot only after refresh's restore/cleanup has run.
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG,
                95,
                setOf("0:com.example.chat"),
                1000L,
            ),
        )
        // Reset read counter to observe the restoreSelection read.
        val readsBeforeRestore = backend.reads.get()
        val rawBefore = backend.map[SELECTION_KEY]
        assertNotNull(rawBefore)

        vm.restoreSelection(95)
        awaitScopeDrained(vm)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        // Foreign guard returns before any store I/O.
        assertEquals(readsBeforeRestore, backend.reads.get())
        // Foreign guard must not touch the store: byte-identical snapshot.
        assertEquals(rawBefore, backend.map[SELECTION_KEY])
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
        assertEquals(0, gw.writeCommands.size)
        assertEquals(0, gw.unexpectedCommands.size)
    }

    @Test
    fun nullStore_restore_doesNotCrash() {
        val gw = FakeRepo()
        val clock = FakeClock()
        val vm = makeVm(gw, clock, null)
        await { vm.state.value.channelReady }
        awaitScopeDrained(vm)

        vm.setSourceCatalog(catalog())
        vm.restoreSelection(95)
        awaitScopeDrained(vm)
        assertNull(vm.state.value.pendingRemove)
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
        assertEquals(0, gw.writeCommands.size)
        assertEquals(0, gw.unexpectedCommands.size)
    }

    @Test
    fun emptyCatalog_restore_doesNotPoisonMarker_thenRecovers() {
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

        // Verify target while catalog is still empty (refresh triggers no
        // restore because sourceCatalog is empty, but inventory gets verified).
        vm.refresh()
        await { vm.state.value.inventory.targetId > 0 }
        awaitScopeDrained(vm)

        // Catalog still empty: explicit restore must not burn the marker.
        vm.restoreSelection(95)
        awaitScopeDrained(vm)
        assertTrue(vm.state.value.selectedKeys.isEmpty())

        // Once catalog is ready, the not-burned marker allows restore.
        vm.setSourceCatalog(catalog())
        await { "0:com.example.chat" in vm.state.value.selectedKeys }
        assertEquals(
            setOf("0:com.example.chat", "0:com.example.mail"),
            vm.state.value.selectedKeys,
        )
        assertNull(vm.state.value.pendingRemove)
    }

    @Test
    fun serialMismatch_restore_clearsStaleEntry() {
        val backend = MapBackend()
        // v2 snapshot bound to serial 15.
        assertTrue(
            backend.store().save(
                SELECTION_SOURCE_CATALOG,
                95,
                setOf("0:com.example.chat"),
                1000L,
                15,
            ),
        )
        // Device reports the same id but a rebuilt serial (41).
        val gw = FakeRepo(dualId = 95, dualSerial = 41)
        val clock = FakeClock()
        val vm = makeVm(gw, clock, backend.store())
        await { vm.state.value.channelReady }

        vm.setSourceCatalog(catalog())
        vm.refresh()
        await { !vm.state.value.loading }
        await { backend.map[SELECTION_KEY] == null }
        // Mismatch is verified -> stale entry cleared, nothing restored.
        assertTrue(vm.state.value.selectedKeys.isEmpty())
        assertNull(backend.map[SELECTION_KEY])
        assertEquals(0, gw.installCalls.size)
        assertEquals(0, gw.uninstallCalls.size)
        assertEquals(0, gw.writeCommands.size)
        assertEquals(0, gw.unexpectedCommands.size)
    }

    @Test
    fun processDeath_vmA_to_vmB_restoresSerialBoundSelection() {
        val backend = MapBackend()
        val gw = FakeRepo()
        val clock = FakeClock()
        val vmA = makeVm(gw, clock, backend.store())
        await { vmA.state.value.channelReady }

        vmA.setSourceCatalog(catalog())
        vmA.refresh()
        await { vmA.state.value.inventory.targetId > 0 }

        vmA.toggleSelection("0:com.example.chat")
        await { vmA.state.value.selectedKeys.contains("0:com.example.chat") }
        gw.installedForUser.add("com.example.chat")

        // Outstanding consent must not survive process death.
        vmA.requestRemove("com.example.chat")
        await { vmA.state.value.pendingRemove != null }
        assertNotNull(vmA.state.value.pendingRemove)

        // Drive persistence through VM A (serial-bound to the verified target).
        vmA.persistSelection(95)
        await { vmA.state.value.msg.contains("已保存") }
        // Serial-bound persistence: the on-disk entry must carry serial=15.
        assertTrue(backend.map[SELECTION_KEY]!!.contains("serial=15"))

        // Pending consent must survive persistence until the restart boundary:
        // re-check immediately before destroy so the VM-B null proof is real.
        val pendingBeforeDestroy = vmA.state.value.pendingRemove
        assertNotNull("VM-A pending must survive persist until destroy", pendingBeforeDestroy)

        // Destroy VM A (cancel its scope) to simulate VM recreation (same
        // process; a real process-kill is covered by instrumentation below).
        try {
            vmA.viewModelScope.cancel()
        } catch (_: Exception) {
        }
        vms.remove(vmA)

        // VM B backed by the same storage must recover the serial-bound selection.
        val gwB = FakeRepo()
        val clockB = FakeClock()
        val vmB = makeVm(gwB, clockB, backend.store())
        await { vmB.state.value.channelReady }

        vmB.setSourceCatalog(catalog())
        vmB.refresh()
        await { "0:com.example.chat" in vmB.state.value.selectedKeys }

        assertEquals(
            setOf("0:com.example.chat"),
            vmB.state.value.selectedKeys,
        )
        assertEquals(15, vmB.state.value.inventory.targetSerial)
        assertNull(vmB.state.value.pendingRemove)
        assertEquals(0, gwB.installCalls.size)
        assertEquals(0, gwB.uninstallCalls.size)
        assertEquals(0, gwB.writeCommands.size)
        assertEquals(0, gwB.unexpectedCommands.size)
    }
}
