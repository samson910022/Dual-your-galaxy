// O1 cross-VM regressions (Phase 1 blocking + lifecycle/availability).
// Deterministic: no Thread.sleep to prove "nothing happened" (use await/latch/hook);
// 300ms negative assertions are direction-safe (documented: failure would be a write,
// i.e. fail-closed violation, not flakiness).
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicyWriteCrossVmTest {
    private val vms = mutableListOf<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        resetRemotePoisonForTests()
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            // dispose() first: disarms blind-clear/repair, revokes an owned
            // PREPARING claim (ownerAlive=false so no other VM inherits a dead
            // fence), and best-effort closes the per-VM reconcile dispatcher
            // (a thread blocked in binder IO may linger to process death — known
            // residue, documented; scope cancel below stops everything else).
            try { vm.dispose() } catch (_: Exception) { }
            try { vm.viewModelScope.cancel() } catch (_: Exception) { }
        }
        vms.clear()
        Dispatchers.resetMain()
        resetRemotePoisonForTests()
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val caller = try {
            Thread.currentThread().stackTrace.firstOrNull {
                it.fileName?.contains("PolicyWriteCrossVmTest") == true && it.methodName != "await"
            }?.let { "${it.fileName}:${it.lineNumber} ${it.methodName}" } ?: "unknown-caller"
        } catch (_: Exception) {
            "unknown-caller"
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                val vmsDump = try {
                    vms.mapIndexed { i, vm ->
                        val st = try { vm.state.value } catch (_: Exception) { null }
                        val tx = try { activeTxOf(vm) } catch (_: Exception) { "?" }
                        val stale = try { revisionStaleOf(vm) } catch (_: Exception) { "?" }
                        "vm[$i] phase=${st?.policyWritePhase} Known=${st?.policyDraftKnown} " +
                            "Stale=$stale Corrupt=${st?.policyDraftCorrupt} draft=${st?.policyDraft} " +
                            "pending=${st?.pendingPolicyWrite != null} activeTx=$tx msg=${st?.msg}"
                    }.joinToString("\n")
                } catch (_: Exception) {
                    "vms-dump-failed"
                }
                val coordDump = try {
                    vms.mapNotNull { vm ->
                        try {
                            val f = DualViewModel::class.java.getDeclaredField("policyTxCoordinator")
                                .apply { isAccessible = true }
                            (f.get(vm) as? PolicyTxCoordinator)?.let {
                                "coordTx=${it.activeTx.get()} rev=${it.currentRevision()} " +
                                    "pending=${it.pendingPublish.get()} gen=${it.orphanDropGen.get()} " +
                                    "dropRev=${it.orphanDropRev.get()} retired=${it.retiredRealCount.get()} " +
                                    "owner=${it.pendingOwner.get()}"
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }.distinct().joinToString("; ").ifEmpty { "no-coord" }
                } catch (_: Exception) {
                    "coord-dump-failed"
                }
                throw AssertionError(
                    "timeout at $caller after ${timeoutMs}ms\nvms:\n$vmsDump\ncoord: $coordDump",
                )
            }
            Thread.sleep(20)
        }
    }

    private fun makeVm(
        policyStore: HookPolicyStore?,
        userId: Int = 0,
        initialDraft: Set<String>? = setOf("com.seed"),
        policyIoTimeoutMs: Long = DualViewModel.POLICY_IO_TIMEOUT_MS,
        policyTxCoordinator: PolicyTxCoordinator? = null,
    ): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = emptyList(),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { false },
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.NOOP,
            hookDirectReads = { listOf(MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            hookPolicyStore = policyStore,
            currentUserId = { userId },
            policyIoTimeoutMs = policyIoTimeoutMs,
            policyTxCoordinator = policyTxCoordinator,
        )
        vms.add(vm)
        if (policyStore != null && initialDraft != null) {
            await { vm.state.value.policyDraftKnown }
        }
        await { !vm.state.value.loading }
        return vm
    }

    // Direct second-VM constructor for cross-VM handover tests: intentionally
    // does NOT await init Known/loading (unlike makeVm). The caller asserts on
    // the post-init fence itself (BUSY/RECONCILING/Unknown vs inherited Tx),
    // so awaiting Known here would mask the very state under test (a fresh
    // inheritor starts Unknown/RECONCILING, not Known).
    private fun makeVm2Direct(
        store: HookPolicyStore,
        coord: PolicyTxCoordinator,
        policyIoTimeoutMs: Long = 1_000,
    ): DualViewModel {
        val vm2 = DualViewModel(
            repo = DualRepository(NoOpGateway()),
            gatewayPrivilege = Privilege.NONE,
            elevators = emptyList(),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { false },
            shizukuAuthCheck = { false },
            activeGateway = NoOpGateway(),
            currentChannel = ResolvedChannel.NOOP,
            hookDirectReads = { listOf(MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            hookPolicyStore = store,
            currentUserId = { 0 },
            policyIoTimeoutMs = policyIoTimeoutMs,
            policyTxCoordinator = coord,
        )
        vms.add(vm2)
        return vm2
    }

    private fun activeTxOf(vm: DualViewModel): Any? {
        val f = DualViewModel::class.java.getDeclaredField("activePolicyTx").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ref = f.get(vm) as java.util.concurrent.atomic.AtomicReference<*>
        return ref.get()
    }

    // Test-only: clear pending while preserving phase (simulates a cancel that
    // landed in the no-suspension window between the post-acquire re-check and
    // the save-commit point — QUEUED survives with null pending, ownerless).
    private fun clearPendingKeepPhase(vm: DualViewModel) {
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = f.get(vm) as MutableStateFlow<com.galaxydual.plus.UiState>
        state.value = state.value.copy(pendingPolicyWrite = null)
    }

    private fun setPendingPolicyWrite(vm: DualViewModel, pending: PendingPolicyWrite?) {
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = f.get(vm) as MutableStateFlow<com.galaxydual.plus.UiState>
        state.value = state.value.copy(pendingPolicyWrite = pending)
    }

    private fun seedKnown(vm: DualViewModel, draft: Set<String>) {
        val f = DualViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = f.get(vm) as MutableStateFlow<com.galaxydual.plus.UiState>
        state.value = state.value.copy(policyDraft = draft, policyDraftKnown = true)
    }

    private fun setStale(vm: DualViewModel, stale: Boolean) {
        val f = DualViewModel::class.java.getDeclaredField("policyRevisionStale").apply { isAccessible = true }
        (f.get(vm) as AtomicBoolean).set(stale)
    }

    // T3 await-dump helper: read-only view of the fail-closed stale fence
    // (dumped on every await timeout so a conjunct await names the failed conjunct).
    private fun revisionStaleOf(vm: DualViewModel): Boolean {
        val f = DualViewModel::class.java.getDeclaredField("policyRevisionStale").apply { isAccessible = true }
        return (f.get(vm) as AtomicBoolean).get()
    }

    private fun abortUnarmedTxOf(vm: DualViewModel, tx: DualViewModel.PolicyTx) {
        val m = DualViewModel::class.java.getDeclaredMethod("abortUnarmedTx", DualViewModel.PolicyTx::class.java)
            .apply { isAccessible = true }
        m.invoke(vm, tx)
    }

    private fun ownerAliveOf(tx: Any?): Boolean {
        if (tx == null) return false
        return try {
            val f = tx.javaClass.getDeclaredField("ownerAlive").apply { isAccessible = true }
            (f.get(tx) as AtomicBoolean).get()
        } catch (_: Exception) {
            false
        }
    }

    private fun coordinatorTxOf(coord: PolicyTxCoordinator): Any? = coord.activeTx.get()

    // O1 P1#1 (consent): stale orphan-placeholder recovery must not let a different VM
    // bind confirmation to the already-advanced revision and overwrite unseen B.
    // VM-C Known(A) -> B CAS/no emit -> C request after B must fail-closed (no pending,
    // Unknown) asserting zero C backend writes until C re-reads B and re-consents.
    // Negative 300ms assertion is direction-safe: any C write would be a fail-closed
    // violation (test fails), absence proves nothing flaky (success path awaited below).
    @Test
    fun o1_staleConsent_orphanDrop_blocksUntilReread() {
        val backend = AtomicReference<Set<String>?>(setOf("com.A"))
        val saveCallsC = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                if (raw == setOf("com.C")) saveCallsC.incrementAndGet()
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            // Threshold 300ms so the orphan ages past it after 800ms sleep (deterministic recovery).
            val vmC = makeVm(store, userId = 0, initialDraft = setOf("com.A"), policyIoTimeoutMs = 300, policyTxCoordinator = coord)
            await { vmC.state.value.policyDraftKnown && vmC.state.value.policyDraft == setOf("com.A") }

            // Simulate VM-A commits B: save landed (backend=B), CAS done (rev 1, placeholder
            // staged, activeTx=null), paused before real stage/emit (afterClaimBeforeEmit equivalent).
            backend.set(setOf("com.B"))
            val fakeStore = HookPolicyStore(loadRaw = { backend.get() }, saveRaw = { raw -> if (raw != null) backend.set(raw); true })
            val orphanTx = DualViewModel.PolicyTx(
                coord.nextTxId(), "orphanB",
                kotlinx.coroutines.CompletableDeferred(true), setOf("com.B"), fakeStore, false,
            )
            coord.activeTx.set(orphanTx)
            val orphanPlaceholder = PolicySettled(orphanTx.txId, orphanTx.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false)
            runBlocking { assertTrue(coord.completeWithRevision(orphanTx, orphanPlaceholder)) }
            assertEquals(1L, coord.committedRevision.get())
            assertNotNull(coord.pendingPublish.get())
            assertNull(coord.latest.value)

            // VM-C (still displaying A) requests C AFTER B's unseen CAS.
            vmC.setSourceCatalog(listOf(SourceAppEntry("com.C", "C", 0, false)))
            vmC.toggleSelection("0:com.C")
            vmC.requestPolicyWrite()
            // Fail-closed: observed (0) != committed (1) => no pending, Unknown.
            assertNull(vmC.state.value.pendingPolicyWrite)
            assertFalse(vmC.state.value.policyDraftKnown)
            assertEquals(MsgKind.CHANNEL, vmC.state.value.msgKind)
            // Direction-safe negative: 300ms window, any C write fails the test.
            Thread.sleep(300)
            assertEquals(0, saveCallsC.get())
            assertEquals(setOf("com.B"), backend.get())

            // Age orphan past threshold (recovery age proof), then REALLY attempt a
            // confirm with a borrowed base: simulate the buggy request that sampled
            // the already-advanced rev (1) while still displaying stale A. Inject
            // pending base=1 directly — the pre-IO drain must drop the orphan and
            // refuse (UNKNOWN_REFRESH, zero C writes), proving even a borrowed base
            // cannot overwrite unseen B without a fresh read.
            Thread.sleep(800)
            setStale(vmC, false)
            seedKnown(vmC, setOf("com.A"))
            val borrowed = PendingPolicyWrite(
                packages = setOf("com.C"),
                requestedAtMs = System.currentTimeMillis(),
                baseCoordinatorRevision = 1L,
            )
            setPendingPolicyWrite(vmC, borrowed)
            vmC.confirmPolicyWrite(borrowed)
            await(timeoutMs = 10_000L) { vmC.state.value.pendingPolicyWrite == null }
            assertEquals(0, saveCallsC.get())
            assertEquals(setOf("com.B"), backend.get())
            assertFalse(vmC.state.value.policyDraftKnown)
            assertEquals(MsgKind.CHANNEL, vmC.state.value.msgKind)
            // The orphan was recovered by the drain (tombstoned, generation bumped,
            // drop rev recorded as max).
            assertEquals(1L, coord.orphanDropGen.get())
            assertEquals(1L, coord.orphanDropRev.get())
            // Fresh re-read B (authoritative) then re-consent C must succeed.
            // Selection persists across refresh; re-toggling would deselect, so only
            // ensure C is still selected.
            vmC.refreshPolicyDraft()
            await(timeoutMs = 10_000L) { vmC.state.value.policyDraftKnown && vmC.state.value.policyDraft == setOf("com.B") }
            if (!vmC.state.value.selectedKeys.contains("0:com.C")) {
                vmC.setSourceCatalog(listOf(SourceAppEntry("com.C", "C", 0, false)))
                vmC.toggleSelection("0:com.C")
            }
            vmC.requestPolicyWrite()
            val pendingC = vmC.state.value.pendingPolicyWrite
            assertNotNull(pendingC)
            vmC.confirmPolicyWrite(pendingC!!)
            await(timeoutMs = 15_000L) { vmC.state.value.pendingPolicyWrite == null }
            assertTrue(vmC.state.value.policyDraftKnown)
            assertEquals(setOf("com.C"), vmC.state.value.policyDraft)
            assertEquals(setOf("com.C"), backend.get())
            // C wrote exactly once (after re-consent), never before.
            assertEquals(1, saveCallsC.get())
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // O1 P2#2 (lifecycle): opening a second VM during a still-live first VM's PREPARING
    // Tx must not cancel the live owner's shared LAZY. Live VM-A in final repair read +
    // construct VM-B => VM-A not cancelled, single validated mutation, both converge.
    @Test
    fun o1_liveOwner_preparingNotCancelled() {
        val corrupt = AtomicBoolean(true)
        val backend = AtomicReference<Set<String>?>(null)
        val deleteCalls = AtomicInteger(0)
        val probeCalls = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)
        val finalStarted = CountDownLatch(1)
        val finalRelease = AtomicBoolean(false)
        val store = HookPolicyStore(
            loadRaw = {
                // Init loadResult: independent counter, direct Corrupt, never blocks.
                // vmB init must not become a second blocker (only the repair path
                // blocks below via repairProbe).
                loadCalls.incrementAndGet()
                if (corrupt.get()) throw ClassCastException("corrupt")
                backend.get()
            },
            saveRaw = { raw ->
                if (raw == null) {
                    deleteCalls.incrementAndGet()
                    corrupt.set(false)
                    backend.set(emptySet())
                    true
                } else {
                    if (raw != null) backend.set(raw)
                    true
                }
            },
            repairProbe = {
                // Repair path only: n==1 pre-validation immediate Corrupt,
                // n>=2 final fresh revalidation blocks on finalRelease.
                // loadResult (init) never routes here, so vmB init is not a blocker.
                val n = probeCalls.incrementAndGet()
                if (n == 1) LoadResult.Corrupt
                else {
                    finalStarted.countDown()
                    while (!finalRelease.get()) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { }
                    }
                    LoadResult.Corrupt
                }
            },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vmA = makeVm(store, userId = 0, initialDraft = null, policyIoTimeoutMs = 5_000, policyTxCoordinator = coord)
            await { !vmA.state.value.loading }
            await(timeoutMs = 5_000L) { vmA.state.value.policyDraftCorrupt }
            vmA.requestRepairCorruptDraft()
            assertTrue(vmA.isCorruptRepairArmed())
            vmA.confirmRepairCorruptDraft()
            assertTrue(finalStarted.await(10, TimeUnit.SECONDS))
            // VM-A is live in final fresh revalidation (PREPARING, unarmed, claim held).
            val txA = activeTxOf(vmA)
            assertNotNull(txA)
            assertSame(txA, coordinatorTxOf(coord))

            // Count isolation (T2): repair path uses repairProbe only, init uses
            // loadResult only. Pre-repair consumed probe #1, blocked final holds
            // probe #2; init consumed load #1. vmB handover init must not eat
            // probe (a merged shared path would read 3+ and fail here, not timeout).
            assertEquals(2, probeCalls.get())
            assertEquals(1, loadCalls.get())
            val loadBeforeB = loadCalls.get()

            // Construct VM-B while VM-A is still live and PREPARING.
            val vmB = makeVm2Direct(store, coord, policyIoTimeoutMs = 5_000)
            // Structural Busy proof (msg only auxiliary): B holds no local Tx,
            // coordinator still holds A's Tx, owner alive, Unknown, phase
            // IDLE/RECONCILING. T1: bare `phase == RECONCILING` alone is NOT
            // accepted (it would mask a BUSY-loss regression as pass); only the
            // full conjunction or the BUSY msg passes. Bounded 20s (busyWaiter
            // first round delay is policyIoTimeoutMs; 20s covers it + scheduling).
            await(timeoutMs = 20_000L) {
                val noLocal = activeTxOf(vmB) == null
                val stillCoord = coordinatorTxOf(coord) === txA
                val alive = ownerAliveOf(txA)
                val unknown = !vmB.state.value.policyDraftKnown
                val phase = vmB.state.value.policyWritePhase
                val phaseOk = phase == PolicyWritePhase.RECONCILING || phase == PolicyWritePhase.IDLE
                (noLocal && stillCoord && alive && unknown && phaseOk) ||
                    vmB.state.value.msg.contains(PolicyText(PolicyTextKind.BUSY).defaultText())
            }
            // T2: vmB init ran on loadResult only — probe untouched, load +1.
            // Await the load (async init) so the assertion is deterministic;
            // probe equality is the fail-fast red for a merged path.
            await(timeoutMs = 10_000L) { loadCalls.get() == loadBeforeB + 1 }
            assertEquals(2, probeCalls.get())
            assertEquals(loadBeforeB + 1, loadCalls.get())
            // VM-A must not be cancelled: coordinator still holds VM-A's Tx, local still holds it.
            assertSame(txA, activeTxOf(vmA))
            assertSame(txA, coordinatorTxOf(coord))
            // VM-B must not have claimed/cancelled: it holds no Tx (Unknown/Busy).
            assertNull(activeTxOf(vmB))
            assertFalse(vmB.state.value.policyDraftKnown)
            // Nothing executed yet: validation+delete run only after release (exactly once below).
            assertEquals(0, deleteCalls.get())

            // Let VM-A complete validation + delete.
            finalRelease.set(true)
            await(timeoutMs = 20_000L) { vmA.state.value.policyDraftKnown }
            assertEquals(emptySet<String>(), vmA.state.value.policyDraft)
            assertEquals(1, deleteCalls.get())
            // Both converge via _latest (VM-B via collector, no extra delete).
            await(timeoutMs = 15_000L) { vmB.state.value.policyDraftKnown && vmB.state.value.policyDraft.isEmpty() }
            assertEquals(1, deleteCalls.get())
            assertNull(activeTxOf(vmA))
            assertNull(coordinatorTxOf(coord))
        } finally {
            try { finalRelease.set(true) } catch (_: Exception) { }
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // O1 P2#3 (availability): real terminal stranded in pendingPublish (stageReal done,
    // emit omitted, publisher died) must retire once present-in-latest, not fence forever.
    // completeWithRevision->stageReal->omit emit->wait threshold->VM-C request/confirm C
    // succeeds, latest monotonic, no permanent fence.
    @Test
    fun o1_strandedReal_retiresAndAllowsC() {
        val backend = AtomicReference<Set<String>?>(setOf("com.B"))
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw -> if (raw != null) backend.set(raw); true },
        )
        val coord = PolicyTxCoordinator()
        try {
            // Simulate publisher: CAS (rev1 placeholder) then stageReal (rev1 real in latest),
            // then die before emitStaged (pending real stranded).
            // The owner deferred is INCOMPLETE (publisher died mid-flight): owner-done
            // is false, so retirement must come from the monotonic AGE proof below
            // (threshold = VM-C's policyIoTimeoutMs = 300ms; the 800ms sleep ages
            // the fence past it) — not from owner-done.
            val fakeStore = HookPolicyStore(loadRaw = { backend.get() }, saveRaw = { raw -> if (raw != null) backend.set(raw); true })
            val deadOwner = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val txB = DualViewModel.PolicyTx(
                coord.nextTxId(), "keyB",
                deadOwner, setOf("com.B"), fakeStore, false,
            )
            coord.activeTx.set(txB)
            val placeholder = PolicySettled(txB.txId, txB.key, setOf("com.B"), false, MsgKind.CHANNEL, "", false)
            runBlocking { assertTrue(coord.completeWithRevision(txB, placeholder)) }
            val realB = PolicySettled(txB.txId, txB.key, setOf("com.B"), true, MsgKind.INFO, "ok", false)
            val staged = runBlocking { coord.stageRealPayload(realB) }
            assertNotNull(staged)
            assertEquals(1L, staged!!.rev)
            assertEquals(setOf("com.B"), coord.latest.value?.draft)
            assertNotNull(coord.pendingPublish.get())
            // Omit emitStaged (publisher died here). Age past threshold.
            Thread.sleep(800)

            // VM-C freshly verifies B, then writes C. Must succeed (retire stranded real).
            val vmC = makeVm2Direct(store, coord, policyIoTimeoutMs = 300)
            await(timeoutMs = 10_000L) { vmC.state.value.policyDraftKnown && vmC.state.value.policyDraft == setOf("com.B") }
            val revBefore = coord.latest.value?.rev ?: -1L
            vmC.setSourceCatalog(listOf(SourceAppEntry("com.C", "C", 0, false)))
            vmC.toggleSelection("0:com.C")
            vmC.requestPolicyWrite()
            val pendingC = vmC.state.value.pendingPolicyWrite
            assertNotNull(pendingC)
            vmC.confirmPolicyWrite(pendingC!!)
            await(timeoutMs = 15_000L) { vmC.state.value.pendingPolicyWrite == null }
            assertTrue(vmC.state.value.policyDraftKnown)
            assertEquals(setOf("com.C"), vmC.state.value.policyDraft)
            assertEquals(setOf("com.C"), backend.get())
            val revAfter = coord.latest.value?.rev ?: -1L
            assertTrue(revAfter > revBefore)
            assertNull(coord.pendingPublish.get())
            // Retired exactly once via the age path (no placeholder drop involved).
            assertEquals(1L, coord.retiredRealCount.get())
            assertEquals(0L, coord.orphanDropGen.get())
            // No permanent fence: another write can proceed (phase IDLE, no stuck pending).
            assertEquals(PolicyWritePhase.IDLE, vmC.state.value.policyWritePhase)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // O1 extra (QUEUED+pending==null ownerless): a cancel landing between the
    // post-acquire re-check and the save-commit point clears pending; the
    // surviving QUEUED (null pending) is ownerless and must return IDLE — never
    // strand with no owner to settle it. The window has no suspension by design,
    // so the test injects it by reflection while the confirm is queued behind a
    // held opMutex (deterministic: Unconfined runs the confirm to the mutex).
    // Revert-red: the old bare `?: return@withLock` leaves QUEUED and the IDLE
    // await below times out.
    @Test
    fun o1_queuedPendingNull_returnsIdle() {
        val backend = AtomicReference<Set<String>?>(setOf("com.seed"))
        val saveCalls = AtomicInteger(0)
        val store = HookPolicyStore(
            loadRaw = { backend.get() },
            saveRaw = { raw ->
                saveCalls.incrementAndGet()
                if (raw != null) backend.set(raw)
                true
            },
        )
        val coord = PolicyTxCoordinator()
        // opMutex blocker: a slow unrelated selection persist holds the mutex.
        val blockerEntered = CountDownLatch(1)
        val blockerRelease = AtomicBoolean(false)
        val blockingSelection = SelectionStore(
            loadRaw = { null },
            saveRaw = { _ ->
                blockerEntered.countDown()
                while (!blockerRelease.get()) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { }
                }
                true
            },
        )
        try {
            val vm = DualViewModel(
                repo = DualRepository(NoOpGateway()),
                gatewayPrivilege = Privilege.NONE,
                elevators = emptyList(),
                preference = ChannelPreference.AUTO,
                appPackageName = "com.example.test",
                persistPreference = null,
                suPresentCheck = { false },
                shizukuAuthCheck = { false },
                activeGateway = NoOpGateway(),
                currentChannel = ResolvedChannel.NOOP,
                hookDirectReads = { listOf(MarkerRead.Readable(null)) },
                hookDiagnosticGateways = { emptyList() },
                hookPolicyStore = store,
                currentUserId = { 0 },
                selectionStore = blockingSelection,
                policyTxCoordinator = coord,
            )
            vms.add(vm)
            await { vm.state.value.policyDraftKnown }
            await { !vm.state.value.loading }
            vm.setSourceCatalog(listOf(SourceAppEntry("com.B", "B", 0, false)))
            vm.toggleSelection("0:com.B")
            vm.requestPolicyWrite()
            val pending = vm.state.value.pendingPolicyWrite
            assertNotNull(pending)
            assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
            // Queue the confirm behind the held opMutex.
            vm.cancelSavedSelection()
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))
            vm.confirmPolicyWrite(pending!!)
            // Inject the window: pending cleared, QUEUED survives, ownerless.
            clearPendingKeepPhase(vm)
            assertNull(vm.state.value.pendingPolicyWrite)
            assertEquals(PolicyWritePhase.QUEUED, vm.state.value.policyWritePhase)
            blockerRelease.set(true)
            // Ownerless QUEUED must return IDLE (no owner will ever settle it).
            await(timeoutMs = 10_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertNull(vm.state.value.pendingPolicyWrite)
            assertEquals(PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
            // Aborted pre-IO: the store was never touched.
            assertEquals(0, saveCalls.get())
            assertEquals(setOf("com.seed"), backend.get())
        } finally {
            try { blockerRelease.set(true) } catch (_: Exception) { }
            try { coord.close() } catch (_: Exception) { }
        }
    }

    // O1 extra (repair cancel must not wash Corrupt): pre-start cancellation of a
    // repair Tx retains Corrupt=true (fail-closed), not Unavailable.
    // The Tx below is a genuine PREPARING claim (claimed identity, unarmed LAZY,
    // incomplete deferred — exactly what confirmRepair builds before the final
    // fresh revalidation) aborted through the same private abort path a new-VM
    // inherit of an abandoned claim calls (reflection: the path is private by
    // design). Revert-red: the old code washed Corrupt=false on both branches.
    @Test
    fun o1_repairPreStartCancel_keepsCorrupt() {
        val store = HookPolicyStore(
            loadRaw = { throw ClassCastException("corrupt") },
            saveRaw = { _ -> true },
        )
        val coord = PolicyTxCoordinator()
        try {
            val vm = makeVm(store, userId = 0, initialDraft = null, policyTxCoordinator = coord)
            await { !vm.state.value.loading }
            await(timeoutMs = 5_000L) { vm.state.value.policyDraftCorrupt }
            assertFalse(vm.state.value.policyDraftKnown)
            // Repair Tx in PREPARING (unarmed, incomplete): abort must keep Corrupt.
            val doomed = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val tx = DualViewModel.PolicyTx(
                -1L, "k",
                doomed,
                emptySet(),
                store, true, null, true,
            )
            assertFalse(tx.executionArmed.get())
            abortUnarmedTxOf(vm, tx)
            assertTrue(doomed.isCancelled)
            await(timeoutMs = 5_000L) { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertFalse(vm.state.value.policyDraftKnown)
            assertTrue(vm.state.value.policyDraftCorrupt)
            assertEquals(MsgKind.CHANNEL, vm.state.value.msgKind)
            // Contrast: a non-repair (save) Tx abort DOES wash Corrupt.
            val vm2 = makeVm2Direct(store, coord)
            val doomedSave = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val txSave = DualViewModel.PolicyTx(
                -1L, "k2",
                doomedSave,
                emptySet(),
                store, false, null, false,
            )
            abortUnarmedTxOf(vm2, txSave)
            await(timeoutMs = 5_000L) { vm2.state.value.policyWritePhase == PolicyWritePhase.IDLE }
            assertFalse(vm2.state.value.policyDraftKnown)
            assertFalse(vm2.state.value.policyDraftCorrupt)
        } finally {
            try { coord.close() } catch (_: Exception) { }
        }
    }
}
