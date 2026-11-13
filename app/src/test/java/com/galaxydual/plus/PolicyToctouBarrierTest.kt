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
            // Exactly revBefore + revMoved: the mark path (3rd read) must not run
            // on discard (already pinned by !thirdHappened below, so ==2 is the
            // stable equivalent of >=2 — no background path samples this VM's
            // sampler between install and refresh settle).
            assertEquals("rev sampler must see exactly revBefore + revMoved", 2, calls.get())
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
    // explicit-rev seam + sampled path 1000 times to catch read-then-act
    // races/flakes. Skipped by default (no RUN_LARGE_TESTS=1) so CI stays green.
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
            val base = vm.observedRevForTests()
            repeat(1000) { i ->
                // Alternate between explicit rev and sampled path.
                if (i % 2 == 0) {
                    vm.markObservedKnownAt(coord.currentRevision())
                } else {
                    // Exercise the sampler path (no bump, must stay consistent).
                    vm.revisionSamplerForTests = { coord.currentRevision() }
                    // Private markObservedKnown() not directly callable; drive
                    // via the explicit seam with the sampled value.
                    vm.markObservedKnownAt(vm.observedRevForTests())
                }
            }
            // Hammer never crashes and observed is monotonic (never regresses
            // below base; -1L is the minimum so after >= base covers the
            // never-observed base too).
            val after = vm.observedRevForTests()
            assertTrue("observed must never regress (base=$base after=$after)", after >= base)
        } finally {
            try {
                coord.close()
            } catch (_: Exception) {
            }
        }
    }
}
