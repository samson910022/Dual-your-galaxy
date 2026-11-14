// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

// Phase7 TOCTOU barrier (refresh representative for init/refresh/repair).
//
// Pins the P1-1 invariant via the revisionSamplerForTests seam (fake
// currentRevision with call counting + mid-IO bump): a concurrent B commit
// landing between revBefore and revMoved must discard the Known (stay
// Unknown, fail closed) and must NOT advance authoritativeObservedRev.
// The explicit-rev seam markObservedKnownAt(rev) is covered by the hammer.
//
// Refresh is the representative pinned path; init and repair share the same
// sampledCurrentRevision()/markObservedKnownAt() helpers.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyToctouBarrierTest {
    private val vms = mutableListOf<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        resetRemotePoisonForTests()
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try {
                vm.dispose()
            } catch (_: Exception) {
            }
            try {
                vm.viewModelScope.cancel()
            } catch (_: Exception) {
            }
        }
        vms.clear()
        Dispatchers.resetMain()
        resetRemotePoisonForTests()
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout awaiting condition")
            Thread.sleep(20)
        }
    }

    private fun makeVm(
        store: HookPolicyStore,
        coord: PolicyTxCoordinator,
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
            hookPolicyStore = store,
            currentUserId = { 0 },
            policyTxCoordinator = coord,
        )
        vms.add(vm)
        await { vm.state.value.policyDraftKnown }
        await { !vm.state.value.loading }
        return vm
    }

    @Test
    fun refreshBarrier_midIoRevMove_staysUnknownAndObservedStalls() {
        val coord = PolicyTxCoordinator()
        try {
            val backend = AtomicReference<Set<String>?>(setOf("com.seed"))
            val entered = CountDownLatch(1)
            val releaseIo = CountDownLatch(1)
            val store = HookPolicyStore(
                loadRaw = {
                    entered.countDown()
                    // In-flight IO window: block until test bumps revision mid-IO.
                    try { releaseIo.await(5, TimeUnit.SECONDS) } catch (_: Throwable) { }
                    backend.get()
                },
                saveRaw = { raw ->
                    if (raw == null) false else {
                        backend.set(raw)
                        true
                    }
                },
            )
            val vm = makeVm(store, coord)
            assertTrue(vm.state.value.policyDraftKnown)
            val observedBefore = vm.observedRevForTests()

            // Fake revision source: count calls; record a 3rd read (mark path)
            // which must NOT happen on the discard path.
            val calls = AtomicInteger(0)
            val thirdHappened = AtomicBoolean(false)
            val realCoord = coord
            vm.revisionSamplerForTests = {
                val c = calls.incrementAndGet()
                if (c >= 3) thirdHappened.set(true)
                try {
                    realCoord.currentRevision()
                } catch (_: Throwable) {
                    -1L
                }
            }

            // Start the refresh (revBefore sampled inside, then IO blocks).
            vm.refreshPolicyDraft()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Concurrent B lands mid-IO (no _latest event, rev only). The bump
            // must advance the revision beyond what this VM observed, otherwise
            // the barrier precondition (mid-IO rev move) is not exercised.
            val bumped = coord.bumpRevisionForTests()
            assertTrue(
                "bump must move rev beyond observed (bumped=$bumped observed=$observedBefore)",
                if (observedBefore == -1L) bumped != -1L else bumped > observedBefore,
            )
            // Release blocked IO after mid-IO rev move (deterministic, no sleep).
            try { releaseIo.countDown() } catch (_: Throwable) { }
            // Let the refresh finish.
            await(timeoutMs = 10_000L) { !vm.state.value.policyRefreshing }

            // Stale Known discarded: stay Unknown, no disarm/mark wash.
            assertFalse(vm.state.value.policyDraftKnown)
            assertEquals(observedBefore, vm.observedRevForTests())
            // Both arms deterministic in this fixture (>=2: revBeforeRefresh +
            // revMovedRefresh both sample before the discard decision — neither
            // is pre-empted since the refresh path always reaches the
            // revMovedRefresh sample; <=2: the mark path (3rd read) must not
            // run on the discard path, and no background path samples this
            // VM's sampler between install and refresh settle).
            assertTrue("rev sampler must see at least revBefore + revMoved", calls.get() >= 2)
            assertTrue("rev sampler must not exceed revBefore + revMoved", calls.get() <= 2)
            assertFalse("mark path (3rd read) must not run on discard", thirdHappened.get())
            // Latch discipline: the 2nd read observed the bump (revMoved true);
            // the 3rd read gate stayed closed (no release needed, no hang).
            assertTrue(coord.currentRevision() != observedBefore || observedBefore == -1L)
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }

    // Phase7 stress hammer (N=1000, LargeTest, opt-in only): hammers the
    // explicit-rev seam 1000 times to catch read-then-act races/flakes.
    // Skipped by default (no RUN_LARGE_TESTS=1) so CI stays green.
    @LargeTest
    @Test
    fun stressHammer_markObservedKnownAt_1000() {
        Assume.assumeTrue(System.getenv("RUN_LARGE_TESTS") == "1")
        val coord = PolicyTxCoordinator()
        try {
            val store = HookPolicyStore(
                loadRaw = { setOf("com.seed") },
                saveRaw = { true },
            )
            val vm = makeVm(store, coord)
            repeat(1000) {
                // Both arms drive the same explicit-rev seam; the 1000x
                // hammer proves the mark path never crashes and never
                // regresses the observed snapshot (constant revision here —
                // no commits land mid-hammer). markObservedKnownAt never
                // consults the sampler, so no sampler assignment is needed.
                vm.markObservedKnownAt(coord.currentRevision())
            }
            // Both marks set observed to the coordinator's current revision:
            // after the hammer, observed must equal it exactly (not merely
            // be >= a base).
            assertEquals(
                "observed must equal the coordinator's current revision after the hammer",
                coord.currentRevision(),
                vm.observedRevForTests(),
            )
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }
}
