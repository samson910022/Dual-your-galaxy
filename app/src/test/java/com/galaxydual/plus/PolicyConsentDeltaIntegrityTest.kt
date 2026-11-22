// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// F04 (PR #74 round 5) — consent-delta integrity.
//
// The additive consent shortcut (revalidatePolicyWriteConsent:
// `additiveNoRemoval && delta.isEmpty() -> ProceedOk`) trusts a PUBLIC DTO's
// `delta` / `additiveNoRemoval` fields. A caller could forge delta=∅,
// additive=true while the persisted Known old={A} and the real candidate={B}
// (a genuine removal) and reach ProceedOk. The VM now RECOMPUTES the delta
// from the authoritative Known pre-read vs the real candidate and requires the
// token's delta to match, BEFORE the consent fence (and outside the test seam
// that replaces the fence wholesale).
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolicyConsentDeltaIntegrityTest {
    private val vms = CopyOnWriteArrayList<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        resetRemotePoisonForTests()
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try { vm.dispose() } catch (_: Exception) { }
            try { vm.viewModelScope.cancel() } catch (_: Exception) { }
        }
        TestDrainSupport.drainBeforeResetMain(vms, emptyList())
        vms.clear()
        resetRemotePoisonForTests()
        Dispatchers.resetMain()
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout after ${timeoutMs}ms state=${try { vms.lastOrNull()?.state?.value } catch (_: Exception) { "?" }}")
            }
            Thread.sleep(20)
        }
    }

    private fun atomicBackendStore(
        backend: AtomicReference<Set<String>?>,
        saveCalls: AtomicInteger,
        clearCalls: AtomicInteger,
    ): HookPolicyStore = HookPolicyStore(
        loadRaw = { backend.get() },
        saveRaw = { raw ->
            if (raw == null) {
                clearCalls.incrementAndGet()
                backend.set(emptySet())
            } else {
                saveCalls.incrementAndGet()
                backend.set(raw)
            }
            true
        },
    )

    private fun makeVm(store: HookPolicyStore): DualViewModel {
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
            // Legacy opt-out seam: lets the legacy-null-consent carve-out test
            // install a pending with no consent (production default is true).
            requirePolicyWriteConsent = false,
            currentUserId = { 0 },
            nowMonoMs = { System.currentTimeMillis() },
            policyIoTimeoutMs = 500L,
            policyTxCoordinator = null,
        )
        vms.add(vm)
        await { vm.state.value.policyDraftKnown }
        await { !vm.state.value.loading }
        return vm
    }

    // A forged additive token: the DTO claims "empty delta / no removal" even
    // though the real Known snapshot vs candidate is a destructive removal.
    private fun forgedAdditiveConsent(): PolicyWriteConsent = buildPolicyWriteConsent(
        delta = emptySet(),
        verifiedTarget = PreClearDualTarget(95, 7),
        installed = listOf("com.a.app"),
    ).copy(additiveNoRemoval = true)

    private fun assertAborted(
        vm: DualViewModel,
        backend: AtomicReference<Set<String>?>,
        initial: Set<String>,
        saveCalls: AtomicInteger,
        clearCalls: AtomicInteger,
    ) {
        assertEquals("zero save", 0, saveCalls.get())
        assertEquals("zero clear", 0, clearCalls.get())
        assertEquals("backend unchanged", initial, backend.get())
        assertNull("pending cleared", vm.state.value.pendingPolicyWrite)
        assertEquals("phase IDLE", PolicyWritePhase.IDLE, vm.state.value.policyWritePhase)
        assertEquals(
            "UNKNOWN_REFRESH",
            PolicyText(PolicyTextKind.UNKNOWN_REFRESH).defaultText(),
            vm.state.value.msg,
        )
    }

    @Test
    fun replacePolicyDraft_forgedEmptyAdditiveConsent_destructiveReplace_abortsZeroSave() {
        val initial = setOf("com.a.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store)
        // Fence seam returns ProceedOk — the additive shortcut the attacker relies
        // on would be honored if the VM trusted the DTO. The delta gate is OUTSIDE
        // this seam, so it must still abort.
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.ProceedOk }

        // warningBinding=null (legacy carve-out for the gen/drift fences), but a
        // non-null consent now always triggers the authoritative pre-read.
        vm.replacePolicyDraft(setOf("com.b.app"), warningBinding = null, consent = forgedAdditiveConsent())
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null && vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertAborted(vm, backend, initial, saveCalls, clearCalls)
    }

    @Test
    fun clearPolicyDraft_forgedEmptyAdditiveConsent_knownNonEmpty_abortsZeroClear() {
        val initial = setOf("com.a.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.ProceedOk }

        vm.clearPolicyDraft(consent = forgedAdditiveConsent())
        await { vm.state.value.pendingPolicyWrite == null && vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertAborted(vm, backend, initial, saveCalls, clearCalls)
    }

    @Test
    fun bootstrapKnownEmpty_addPackage_withAdditiveConsent_saves() {
        val backend = AtomicReference<Set<String>?>(emptySet())
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store)
        // No fence seam: the REAL additive shortcut must still return ProceedOk.
        val consent = runBlocking {
            buildFastPathConsent(
                delta = policyRemovalDelta(emptySet(), setOf("com.a.app")), // ∅
                resolveLiveTarget = { null },
                reprobe = { _, _ -> DualProbeResult.Ok(emptyList()) },
                noRemovalKnown = true,
            )
        }
        assertTrue("real fast-path token must be additive", consent.additiveNoRemoval)

        vm.replacePolicyDraft(setOf("com.a.app"), warningBinding = null, consent = consent)
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { saveCalls.get() == 1 && vm.state.value.pendingPolicyWrite == null }
        await { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertEquals("one save", 1, saveCalls.get())
        assertEquals("candidate saved", setOf("com.a.app"), backend.get())
        assertEquals("zero clear", 0, clearCalls.get())
    }

    @Test
    fun replacePolicyDraft_partialDeltaForgery_overclaimedDelta_abortsZeroSave() {
        val initial = setOf("com.a.app", "com.b.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.ProceedOk }

        // Real delta is {a} (old {a,b} -> candidate {b}); the token over-claims
        // {a,b} with the additive flag — still a mismatch, must abort.
        val forged = buildPolicyWriteConsent(
            delta = setOf("com.a.app", "com.b.app"),
            verifiedTarget = PreClearDualTarget(95, 7),
            installed = listOf("com.b.app"),
        ).copy(additiveNoRemoval = true)
        vm.replacePolicyDraft(setOf("com.b.app"), warningBinding = null, consent = forged)
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null && vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertAborted(vm, backend, initial, saveCalls, clearCalls)
    }

    @Test
    fun replacePolicyDraft_deltaMismatch_withWarnedOldPresent_stillAborts() {
        val initial = setOf("com.a.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.ProceedOk }

        // A real warning binding whose warnedOld matches the authoritative read:
        // the R04 drift check passes, so ONLY the new delta gate can stop the forgery.
        val binding = vm.currentWarningBinding().withWarning(
            old = setOf("com.a.app"),
            candidate = setOf("com.b.app"),
        )
        vm.replacePolicyDraft(setOf("com.b.app"), warningBinding = binding, consent = forgedAdditiveConsent())
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { vm.state.value.pendingPolicyWrite == null && vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertAborted(vm, backend, initial, saveCalls, clearCalls)
    }

    @Test
    fun replacePolicyDraft_legacyNullConsent_skipsDeltaGate_saves() {
        val initial = setOf("com.a.app")
        val backend = AtomicReference<Set<String>?>(initial)
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)
        val store = atomicBackendStore(backend, saveCalls, clearCalls)
        val vm = makeVm(store)
        vm.policyWriteConsentFenceForTests = { _ -> PreClearContinueOutcome.CannotDetermine }

        // Legacy carve-out preserved: consent==null AND baseFreshGen==-1 must
        // skip both the fence and the new delta gate.
        vm.replacePolicyDraft(setOf("com.b.app"), warningBinding = null, consent = null)
        val pending = vm.state.value.pendingPolicyWrite ?: throw AssertionError("no pending installed")
        vm.confirmPolicyWrite(pending, pending.generation)
        await { saveCalls.get() == 1 && vm.state.value.pendingPolicyWrite == null }
        await { vm.state.value.policyWritePhase == PolicyWritePhase.IDLE }

        assertEquals("one save", 1, saveCalls.get())
        assertEquals("candidate saved", setOf("com.b.app"), backend.get())
        assertEquals("zero clear", 0, clearCalls.get())
    }

    @Test
    fun consentDeltaMatchesAuthoritative_pureGate_examples() {
        val honest = buildPolicyWriteConsent(
            delta = setOf("com.a.app"),
            verifiedTarget = PreClearDualTarget(95, 7),
            installed = listOf("com.b.app"),
        )
        assertTrue(
            "honest delta {a} for old {a,b} -> candidate {b} matches",
            consentDeltaMatchesAuthoritative(honest, setOf("com.a.app", "com.b.app"), setOf("com.b.app")),
        )
        val forged = forgedAdditiveConsent()
        assertTrue(
            "forged empty delta must NOT match a real removal",
            !consentDeltaMatchesAuthoritative(forged, setOf("com.a.app"), setOf("com.b.app")),
        )
        assertTrue(
            "null authoritative old always fails closed",
            !consentDeltaMatchesAuthoritative(honest, null, setOf("com.b.app")),
        )
        assertTrue(
            "genuine empty delta on empty old matches (E02 bootstrap)",
            consentDeltaMatchesAuthoritative(forged, emptySet(), setOf("com.a.app")),
        )
    }
}
