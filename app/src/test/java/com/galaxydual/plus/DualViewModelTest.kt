// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR-32 P2-2: deterministic DualViewModel tests (dispatcher / heartbeat /
// dedup / channel queue). No device, no Android runtime, no Turbine.
//
// Harness contract:
// - Dispatchers.setMain(StandardTestDispatcher): viewModelScope runs on the
//   test scheduler; every gate check (refresh/recheck coalesce, write dedup,
//   select latest-wins assignment) is Main-confined and advances
//   deterministically in posting order.
// - onIo() still jumps to the real Dispatchers.IO (accepted per P2-2): the
//   fake gateway answers immediately, and pumpUntil() alternates
//   scheduler.advanceUntilIdle() with short real sleeps so Main resumptions
//   posted back from IO threads are drained. Tests assert only logic counts
//   and state.value (never timing).
// - Fake probes are always clean (exit 0), so the core retry settle wait
//   never fires and repo.delayFn (core-internal, invisible to the app module)
//   is never invoked; no virtual delay plumbing is needed.
// - Elevators are the real SuElevator/ShizukuElevator (probeChannels checks
//   `is SuElevator` / `is ShizukuElevator`, so a hand-rolled Elevator fake
//   could never set hasRoot/hasShizuku) backed by the same fake gateway.
// - Select tests (g)(h): selectChannel() swaps through the assembleChannelFn
//   seam (default: the shared assembleChannel; tests inject fake-backed
//   assemblies), so select probes run on gatable fakes instead of real
//   gateways. Measured reason: on the JVM SuCmdGateway blocks up to
//   SU_EXEC_TIMEOUT on its password prompt with no tty
//   (environment-dependent, fast-fails in CI) and Shizuku statics
//   throw ExceptionInInitializerError, so a select body with real gateways
//   can never complete in a unit test. Fake probes stay clean (exit 0), so
//   readiness holds after every swap and assertions read only
//   channel/loading/counts, never readiness timing.
// - Flake note (Phase D review): timeline -- pre-seam one transient
//   FAILED-status observation during review reruns; post-seam 6+ consecutive
//   local full-suite runs all green (:app + :core testDebugUnitTest
//   --rerun-tasks, DualViewModelTest 8/8 green each time, 201/201 total per
//   run). Root cause was the real-gateway JVM incompatibility, already
//   eliminated by the assembleChannelFn seam above: a select body with real
//   gateways can never complete on the JVM (su blocks up to SU_EXEC_TIMEOUT
//   on its password prompt with no tty, environment-dependent, fast-fails
//   in CI; Shizuku statics throw ExceptionInInitializerError,
//   wedging loading). Any recurrence should suspect harness wall-clock
//   polling in pumpUntil or the real-sleep settle in test (e), not logic
//   (assertions never read elapsed time).
// - pumpUntil budget/interval: 10 s wall-clock budget, 10 ms poll. A genuine
//   logic hang fails loudly with "timed out waiting for condition" instead
//   of hanging the suite; every wait alternates scheduler.advanceUntilIdle()
//   with a real sleep so Main resumptions posted back from IO threads drain.
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DualViewModelTest {
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // One-shot gate: the next matching gateway call signals `entered`, then
    // suspends until the test completes `release`. Consumed by the first
    // caller only (fake clears the slot on take).
    private class Latch {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    // Immediate fake: clean probe answers, exact package-membership listing,
    // "Success" writes. Only pm list packages / install / uninstall are
    // counted (the coalesce/dedup signals); snapshot/identity traffic is
    // uncounted but must succeed so every flow reaches its probe/write.
    private class FakeGateway(
        override val privilege: Privilege = Privilege.ROOT,
        var listPackagesOut: String = "",
        var listPackagesExit: Int = 0,
        var idUOut: String = "0",
        var failListUsers: Boolean = false,
    ) : CmdGateway {
        val idCalls = AtomicInteger(0)
        val listCalls = AtomicInteger(0)
        val writeCalls = AtomicInteger(0)

        // One-shot gates: the check-then-clear take below is intentionally
        // non-atomic -- safe because ViewModel ops are serialized through
        // opMutex, so at most one gated gateway call is ever in flight.
        @Volatile var blockNextIdU: Latch? = null
        @Volatile var blockNextList: Latch? = null
        @Volatile var blockNextWrite: Latch? = null

        override suspend fun run(cmd: String): CmdResult = when {
            cmd == "id -u" -> {
                idCalls.incrementAndGet()
                blockNextIdU?.let { l ->
                    blockNextIdU = null
                    l.entered.complete(Unit)
                    l.release.await()
                }
                CmdResult(0, idUOut, "")
            }
            cmd == DualRepository.CMD_LIST_USERS ->
                if (failListUsers) CmdResult(1, "", "no binder")
                else CmdResult(0, USERS_95, "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> CmdResult(0, VERBOSE_95, "")
            cmd == DualRepository.CMD_DUMPSYS_USER -> CmdResult(0, DUMPSYS_95, "")
            cmd == DualRepository.CMD_GETPROP_DUAL -> CmdResult(0, "95\n", "")
            cmd.startsWith("pm list packages") -> {
                listCalls.incrementAndGet()
                blockNextList?.let { l ->
                    blockNextList = null
                    l.entered.complete(Unit)
                    l.release.await()
                }
                CmdResult(listPackagesExit, listPackagesOut, "")
            }
            cmd.startsWith("pm install-existing") || cmd.startsWith("pm uninstall") -> {
                writeCalls.incrementAndGet()
                blockNextWrite?.let { l ->
                    blockNextWrite = null
                    l.entered.complete(Unit)
                    l.release.await()
                }
                CmdResult(0, "Success", "")
            }
            else -> CmdResult(0, "", "")
        }

        companion object {
            const val USERS_95 =
                "Users:\n" +
                    "\tUserInfo{0:Owner:13} running\n" +
                    "\tUserInfo{95:DUAL_APP:400} running\n"
            const val VERBOSE_95 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} running:\n" +
                    "    Serial: 15\n" +
                    "    parentId=0\n" +
                    "    User type: android.os.usertype.profile.CLONE\n"
            const val DUMPSYS_95 =
                "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n" +
                    "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                    "    parentId=0\n" +
                    "    userType=android.os.usertype.profile.CLONE\n"
        }
    }

    private fun writableVm(
        fake: FakeGateway,
        repo: DualRepository,
        persist: ((ChannelPreference) -> Unit)? = null,
    ): DualViewModel =
        DualViewModel(
            repo = repo,
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(fake), ShizukuElevator(fake)),
            preference = ChannelPreference.ROOT,
            suGateway = NoOpGateway(),
            shizukuGateway = NoOpGateway(),
            activeGateway = fake,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            persistPreference = persist,
        )

    private fun readOnlyVm(fake: FakeGateway, repo: DualRepository): DualViewModel =
        DualViewModel(
            repo = repo,
            gatewayPrivilege = Privilege.NONE,
            elevators = listOf(SuElevator(fake), ShizukuElevator(fake)),
            preference = ChannelPreference.ROOT,
            suGateway = NoOpGateway(),
            shizukuGateway = NoOpGateway(),
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.NOOP,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
        )

    // Select-test rig: fake-backed channel assembly through the
    // assembleChannelFn seam, so select probes run on gatable fakes (see
    // harness header). Each explicit pref maps to its own FakeGateway, which
    // keeps per-round probe counts attributable: the loser's fake must show
    // a gated probe entry with no refresh listing, the winner's a full
    // probe + refresh.
    private class SelectRig(
        val rootFake: FakeGateway,
        val shizukuFake: FakeGateway,
        val persisted: MutableList<ChannelPreference>,
        val vm: DualViewModel,
    )

    private fun selectVm(): SelectRig {
        val rootFake = FakeGateway(listPackagesOut = "")
        val shizukuFake = FakeGateway(privilege = Privilege.SHELL, listPackagesOut = "")
        val persisted = mutableListOf<ChannelPreference>()
        val assemble: (
            ChannelPreference,
            Boolean,
            Boolean,
            String,
        ) -> ChannelAssembly = { pref, _, _, _ ->
            when (pref) {
                ChannelPreference.ROOT ->
                    ChannelAssembly(rootFake, rootFake, rootFake, ResolvedChannel.ROOT)
                ChannelPreference.SHIZUKU ->
                    ChannelAssembly(shizukuFake, shizukuFake, shizukuFake, ResolvedChannel.SHIZUKU)
                ChannelPreference.LSPOSED ->
                    ChannelAssembly(
                        NoOpGateway(),
                        NoOpGateway(),
                        NoOpGateway(),
                        ResolvedChannel.LSPOSED_PLACEHOLDER,
                    )
                ChannelPreference.AUTO ->
                    ChannelAssembly(rootFake, rootFake, rootFake, ResolvedChannel.ROOT)
            }
        }
        val vm = DualViewModel(
            repo = DualRepository(rootFake),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(rootFake), ShizukuElevator(rootFake)),
            preference = ChannelPreference.ROOT,
            suGateway = rootFake,
            shizukuGateway = rootFake,
            activeGateway = rootFake,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            persistPreference = persisted::add,
            assembleChannelFn = assemble,
        )
        return SelectRig(rootFake, shizukuFake, persisted, vm)
    }

    // Drains the Main test scheduler while real IO threads run: advance, then
    // yield so a gated gateway call can fire and post its resumption back.
    private fun pumpUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (cond()) return
            if (System.currentTimeMillis() >= deadline) {
                testDispatcher.scheduler.advanceUntilIdle()
                if (cond()) return
                throw AssertionError("timed out waiting for condition")
            }
            Thread.sleep(10)
        }
    }

    // (a) Five rapid refresh taps collapse to at most 2 list probes
    // (in-flight + one trailing) and the final state converges to ground truth.
    @Test
    fun refresh_rapidFiveTaps_coalescesToAtMostTwoListsAndConverges() {
        val fake = FakeGateway(listPackagesOut = "")
        val vm = writableVm(fake, DualRepository(fake))
        pumpUntil { vm.state.value.channelReady }
        assertEquals(0, fake.listCalls.get())
        val latch = Latch()
        fake.blockNextList = latch
        repeat(5) { vm.refresh() }
        pumpUntil { latch.entered.isCompleted }
        assertTrue(
            "refresh must hold busy while the list probe is gated",
            vm.state.value.loading,
        )
        latch.release.complete(Unit)
        pumpUntil { fake.listCalls.get() >= 2 && !vm.state.value.loading }
        val lists = fake.listCalls.get()
        assertTrue("5 rapid refresh taps must coalesce to <= 2 list probes, got $lists", lists <= 2)
        val s = vm.state.value
        assertFalse(s.loading)
        assertTrue(s.cloned.isEmpty())
        assertEquals("no clones installed", s.msg)
        assertEquals(MsgKind.INFO, s.msgKind)
    }

    // (b) Two concurrent same-key clones: exactly 1 gateway write, the loser
    // reports "already in progress" instead of queueing a second write.
    @Test
    fun clone_sameKeyConcurrent_secondIsDedupedAndWritesOnce() {
        val fake = FakeGateway(listPackagesOut = "package:com.example.alpha")
        val vm = writableVm(fake, DualRepository(fake))
        pumpUntil { vm.state.value.channelReady }
        val latch = Latch()
        fake.blockNextWrite = latch
        vm.clone("com.example.alpha")
        vm.clone("com.example.alpha")
        pumpUntil { latch.entered.isCompleted }
        assertEquals(1, fake.writeCalls.get())
        assertTrue(
            "second identical clone must report already-in-progress, got: ${vm.state.value.msg}",
            vm.state.value.msg.contains("already in progress"),
        )
        latch.release.complete(Unit)
        pumpUntil {
            !vm.state.value.loading && vm.state.value.cloned.contains("com.example.alpha")
        }
        assertEquals(1, fake.writeCalls.get())
        val s = vm.state.value
        assertTrue(s.cloned.contains("com.example.alpha"))
        assertEquals("cloned: com.example.alpha", s.msg)
    }

    // (c) Different keys never collapse: an intentional clone-then-clone
    // sequence on two packages executes both writes.
    @Test
    fun clone_differentKeysInterleaved_bothExecute() {
        val fake = FakeGateway(listPackagesOut = "package:com.example.a\npackage:com.example.b")
        val vm = writableVm(fake, DualRepository(fake))
        pumpUntil { vm.state.value.channelReady }
        vm.clone("com.example.a")
        vm.clone("com.example.b")
        pumpUntil { fake.writeCalls.get() >= 2 && !vm.state.value.loading }
        assertEquals(2, fake.writeCalls.get())
        val cloned = vm.state.value.cloned
        assertTrue(cloned.contains("com.example.a"))
        assertTrue(cloned.contains("com.example.b"))
    }

    // (d) Every op drives loading true -> false: a gated clone holds busy
    // mid-flight and clears it after, and a refused write on a not-ready
    // channel never wedges busy (loading stays false, no device write).
    @Test
    fun loading_everyOpDrivesBusyTrueThenFalseIncludingRefusedWrites() {
        val fake = FakeGateway(listPackagesOut = "package:com.example.d")
        val vm = writableVm(fake, DualRepository(fake))
        pumpUntil { vm.state.value.channelReady }
        val latch = Latch()
        fake.blockNextWrite = latch
        vm.clone("com.example.d")
        pumpUntil { latch.entered.isCompleted }
        assertTrue("clone must hold loading=true while the write is gated", vm.state.value.loading)
        latch.release.complete(Unit)
        pumpUntil { !vm.state.value.loading }
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.cloned.contains("com.example.d"))

        val roFake = FakeGateway(idUOut = "2000", failListUsers = true)
        val roVm = readOnlyVm(roFake, DualRepository(roFake))
        pumpUntil { roFake.idCalls.get() > 0 && !roVm.state.value.loading }
        assertFalse(roVm.state.value.channelReady)
        roVm.clone("com.example.z")
        pumpUntil { roVm.state.value.msg.contains("寫入已禁用") }
        assertFalse("refused write must not wedge busy", roVm.state.value.loading)
        assertEquals(0, roFake.writeCalls.get())
    }

    // (e) A clone cancelled while queued on opMutex releases its dedup key:
    // the waiter never reaches the gateway and the same key is accepted
    // again afterwards (no wedge).
    @Test
    fun clone_cancelWhileQueued_sameKeyCanReenter() {
        val fake = FakeGateway(listPackagesOut = "package:com.example.q")
        val vm = writableVm(fake, DualRepository(fake))
        pumpUntil { vm.state.value.channelReady }
        val listLatch = Latch()
        fake.blockNextList = listLatch
        vm.refresh()
        pumpUntil { listLatch.entered.isCompleted }
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val before = scopeJob.children.toSet()
        vm.clone("com.example.q")
        var waiter: Job? = null
        pumpUntil {
            scopeJob.children.toSet().minus(before).firstOrNull { it.isActive }
                ?.also { waiter = it } != null
        }
        waiter!!.cancel()
        // Settle: drain the Main-side cancel propagation before releasing
        // the IO-parked refresh, so the cancelled waiter deterministically
        // never reaches the gateway.
        pumpUntil { waiter!!.isCompleted }
        testDispatcher.scheduler.advanceUntilIdle()
        listLatch.release.complete(Unit)
        pumpUntil { !vm.state.value.loading }
        assertEquals("cancelled waiter must never reach the gateway", 0, fake.writeCalls.get())
        vm.clone("com.example.q")
        pumpUntil { !vm.state.value.loading && fake.writeCalls.get() >= 1 }
        assertEquals(1, fake.writeCalls.get())
        val s = vm.state.value
        assertTrue(s.cloned.contains("com.example.q"))
        assertTrue("re-entered clone must succeed, got: ${s.msg}", s.msg.contains("cloned: com.example.q"))
    }

    // (f) Recheck bursts coalesce like refresh: 5 rapid recheckPrivilege calls
    // collapse to at most 2 probe runs (in-flight + one trailing).
    @Test
    fun recheckPrivilege_rapidBursts_coalesceToAtMostTwoProbes() {
        val fake = FakeGateway()
        val vm = writableVm(fake, DualRepository(fake))
        pumpUntil { vm.state.value.channelReady }
        val base = fake.idCalls.get()
        assertTrue(base >= 1)
        val latch = Latch()
        fake.blockNextIdU = latch
        repeat(5) { vm.recheckPrivilege() }
        pumpUntil { latch.entered.isCompleted }
        assertTrue("recheck must hold busy while the probe is gated", vm.state.value.loading)
        latch.release.complete(Unit)
        pumpUntil { fake.idCalls.get() >= base + 2 && !vm.state.value.loading }
        val probes = fake.idCalls.get() - base
        assertTrue("5 rapid rechecks must coalesce to <= 2 probe runs, got $probes", probes <= 2)
        assertTrue(vm.state.value.channelReady)
    }

    // (g) Rapid double-tap ROOT -> SHIZUKU with the first select still in
    // flight: the loser entered its probe (gated `id -u`, holding opMutex)
    // when the second tap cancels it, so it never reaches its refresh
    // listing -- exactly one convergence (the winner's full probe +
    // refresh) lands and the final channel is SHIZUKU. Counts/state only,
    // no timing: the second tap is queued before the latch is released,
    // then the scheduler drains to idle.
    @Test
    fun selectChannel_rapidDoubleTap_latestWinsAndLoserNeverConverges() {
        val rig = selectVm()
        val vm = rig.vm
        pumpUntil { vm.state.value.channelReady }
        val baseId = rig.rootFake.idCalls.get()
        val baseList = rig.rootFake.listCalls.get()
        val latch = Latch()
        rig.rootFake.blockNextIdU = latch
        vm.selectChannel(ChannelPreference.ROOT)
        pumpUntil { latch.entered.isCompleted }
        assertTrue(
            "first select must hold busy while its probe is gated",
            vm.state.value.loading,
        )
        // Second tap lands while the first is genuinely in flight:
        // latest-wins cancels the gated probe mid-await.
        vm.selectChannel(ChannelPreference.SHIZUKU)
        testDispatcher.scheduler.advanceUntilIdle()
        latch.release.complete(Unit)
        pumpUntil { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.SHIZUKU }
        // The loser ran its probe entry exactly once but never converged:
        // no refresh listing on its gateway. The winner ran a full round.
        assertEquals(baseId + 1, rig.rootFake.idCalls.get())
        assertEquals(baseList, rig.rootFake.listCalls.get())
        assertTrue(
            "winner must complete a refresh listing, got ${rig.shizukuFake.listCalls.get()}",
            rig.shizukuFake.listCalls.get() >= 1,
        )
        assertEquals(ChannelPreference.SHIZUKU, vm.state.value.channel)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.channelReady)
        // Both taps persisted in order (persist follows the swap and precedes
        // the cancellable probe); convergence is decided by the completed
        // probe + refresh, not by persist.
        assertEquals(
            listOf(ChannelPreference.ROOT, ChannelPreference.SHIZUKU),
            rig.persisted,
        )
        // Select performs swap + probe + refresh only -- never a device write.
        assertEquals(0, rig.rootFake.writeCalls.get() + rig.shizukuFake.writeCalls.get())
    }

    // (h) A select cancelled by a newer tap leaves no wedge: reselecting
    // afterwards completes normally (loading ends false, channel tracks the
    // last choice, readiness holds). Both interleavings (loser cancelled
    // before/after its swap) converge identically because the latch gates
    // the loser's probe in both cases, so no scheduling assumption is made.
    @Test
    fun selectChannel_cancelThenReselect_completesWithoutWedge() {
        val rig = selectVm()
        val vm = rig.vm
        pumpUntil { vm.state.value.channelReady }
        // Cancel a gated first select with a second tap, then let it converge.
        val latch = Latch()
        rig.rootFake.blockNextIdU = latch
        vm.selectChannel(ChannelPreference.ROOT)
        pumpUntil { latch.entered.isCompleted }
        vm.selectChannel(ChannelPreference.SHIZUKU)
        testDispatcher.scheduler.advanceUntilIdle()
        latch.release.complete(Unit)
        pumpUntil { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.SHIZUKU }
        // Reselect after the cancel: must complete normally (fresh
        // latest-wins job, never piled behind a dead one). The launch-body
        // finally cleanup is hygiene only -- this test asserts convergence,
        // not the cleanup mutation (a missing clear would still converge).
        vm.selectChannel(ChannelPreference.ROOT)
        pumpUntil { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.ROOT }
        assertEquals(ChannelPreference.ROOT, vm.state.value.channel)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.channelReady)
        assertEquals(
            listOf(ChannelPreference.ROOT, ChannelPreference.SHIZUKU, ChannelPreference.ROOT),
            rig.persisted,
        )
    }

    // (j) Unknown list probe preserves the display list: seed cloned=[a]
    // with a clean refresh, then fail every list probe (persistent exit=1) --
    // refresh must report CHANNEL ("refresh result unknown"), keep cloned
    // untouched (fail closed, never wipe to empty), and clear loading.
    @Test
    fun refresh_unknownList_preservesCloned() {
        val fake = FakeGateway(listPackagesOut = "package:com.example.a")
        val vm = writableVm(fake, DualRepository(fake))
        pumpUntil { vm.state.value.channelReady }
        vm.refresh()
        pumpUntil { vm.state.value.cloned.contains("com.example.a") }
        val seeded = vm.state.value.cloned
        fake.listPackagesOut = ""
        fake.listPackagesExit = 1
        vm.refresh()
        pumpUntil { !vm.state.value.loading && vm.state.value.msgKind == MsgKind.CHANNEL }
        assertEquals(seeded, vm.state.value.cloned)
        assertTrue(vm.state.value.cloned.contains("com.example.a"))
        assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
        assertFalse(vm.state.value.loading)
    }

    // (i) P2-1 real blocking subprocess handover: the old ROOT select stuck in
    // `id -u` on an actual `sleep` child (true waitFor block, non-cooperative
    // -- coroutine cancel cannot interrupt it, only the poll+ensureActive
    // checkpoint surfaces it) must not hold opMutex to its 10 s probe budget.
    // Switching to SHIZUKU converges in bounded time (well under the probe
    // budget, far under the 60 s exec deadline): latest-wins cancel ends the
    // loser wait within one poll slice, the winner takes over at once, the
    // loser never converges (no refresh listing), and nothing is resent.
    @Test
    fun selectChannel_blockingSuProbe_latestWinsHandsOverQuickly() {
        val entered = CountDownLatch(1)
        val blockingIdCalls = AtomicInteger(0)
        val blockingListCalls = AtomicInteger(0)
        val blockingWriteCalls = AtomicInteger(0)
        // Real blocking child for the loser's `id -u`: `sleep 20` ignores
        // coroutine cancel (worker owns it); the caller retreats via CE and
        // destroys it best effort. Other commands return an instant child so
        // a non-cancelled path would still probe deterministically (the loser
        // never reaches them -- it is cancelled inside `id -u`).
        val rootBlocking = SuCmdGateway { argv ->
            val cmd = argv.lastOrNull() ?: ""
            when {
                cmd == "id -u" -> {
                    blockingIdCalls.incrementAndGet()
                    entered.countDown()
                    ProcessBuilder("sh", "-c", "sleep 20").start()
                }
                cmd.startsWith("pm list packages") -> {
                    blockingListCalls.incrementAndGet()
                    ProcessBuilder("sh", "-c", "echo quick").start()
                }
                cmd.startsWith("pm install-existing") || cmd.startsWith("pm uninstall") -> {
                    blockingWriteCalls.incrementAndGet()
                    ProcessBuilder("sh", "-c", "echo quick").start()
                }
                else -> ProcessBuilder("sh", "-c", "echo quick").start()
            }
        }
        val rootFake = FakeGateway(listPackagesOut = "")
        val shizukuFake = FakeGateway(privilege = Privilege.SHELL, listPackagesOut = "")
        val persisted = mutableListOf<ChannelPreference>()
        val useBlocking = java.util.concurrent.atomic.AtomicBoolean(false)
        val assemble: (
            ChannelPreference,
            Boolean,
            Boolean,
            String,
        ) -> ChannelAssembly = { pref, _, _, _ ->
            when (pref) {
                ChannelPreference.ROOT ->
                    if (useBlocking.get()) {
                        ChannelAssembly(rootBlocking, rootBlocking, rootBlocking, ResolvedChannel.ROOT)
                    } else {
                        ChannelAssembly(rootFake, rootFake, rootFake, ResolvedChannel.ROOT)
                    }
                ChannelPreference.SHIZUKU ->
                    ChannelAssembly(shizukuFake, shizukuFake, shizukuFake, ResolvedChannel.SHIZUKU)
                ChannelPreference.LSPOSED ->
                    ChannelAssembly(
                        NoOpGateway(),
                        NoOpGateway(),
                        NoOpGateway(),
                        ResolvedChannel.LSPOSED_PLACEHOLDER,
                    )
                ChannelPreference.AUTO ->
                    ChannelAssembly(rootFake, rootFake, rootFake, ResolvedChannel.ROOT)
            }
        }
        val vm = DualViewModel(
            repo = DualRepository(rootFake),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(rootFake), ShizukuElevator(rootFake)),
            preference = ChannelPreference.ROOT,
            suGateway = rootFake,
            shizukuGateway = rootFake,
            activeGateway = rootFake,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            persistPreference = persisted::add,
            assembleChannelFn = assemble,
        )
        pumpUntil { vm.state.value.channelReady }
        // F8 same-pref skips the rebuild, so park on a different channel
        // first: the blocking ROOT assembly below is only consulted on a
        // real swap (different pref). This keeps the handover under test a
        // genuine in-flight `sleep` child.
        vm.selectChannel(ChannelPreference.SHIZUKU)
        pumpUntil { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.SHIZUKU }
        // Arm the real block for the next ROOT select only (init stays clean).
        useBlocking.set(true)
        vm.selectChannel(ChannelPreference.ROOT)
        // The select body runs on the Main test scheduler: drain it while the
        // IO-parked `sleep` child starts, so the handover below races a
        // genuinely in-flight probe (not a queued job).
        pumpUntil(timeoutMs = 10_000) { entered.count == 0L }
        assertTrue(vm.state.value.loading)
        // Latest-wins: the new select cancels the hung probe mid-waitFor.
        val handoverStartMs = System.currentTimeMillis()
        vm.selectChannel(ChannelPreference.SHIZUKU)
        pumpUntil { !vm.state.value.loading && vm.state.value.channel == ChannelPreference.SHIZUKU }
        val handoverMs = System.currentTimeMillis() - handoverStartMs
        assertTrue(
            "winner must take over well under the 10 s probe budget, not the 60 s deadline (handover=${handoverMs}ms)",
            handoverMs < 8_000L,
        )
        // The loser ran its probe entry exactly once but never converged: no
        // refresh listing on its gateway. The winner ran a full round.
        assertEquals(1, blockingIdCalls.get())
        assertEquals(0, blockingListCalls.get())
        assertTrue(
            "winner must complete a refresh listing, got ${shizukuFake.listCalls.get()}",
            shizukuFake.listCalls.get() >= 1,
        )
        assertEquals(ChannelPreference.SHIZUKU, vm.state.value.channel)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.channelReady)
        assertEquals(
            listOf(ChannelPreference.SHIZUKU, ChannelPreference.ROOT, ChannelPreference.SHIZUKU),
            persisted,
        )
        // Cancelled capture is never resent as a write on any gateway.
        assertEquals(0, blockingWriteCalls.get() + rootFake.writeCalls.get() + shizukuFake.writeCalls.get())
    }
}
