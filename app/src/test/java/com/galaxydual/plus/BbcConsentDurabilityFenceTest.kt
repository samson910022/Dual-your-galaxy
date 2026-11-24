// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase C (P1-04): local-vs-durable fake prefs drive the REAL app-scoped
// holder/coordinator. A failed commit flushes only the optimistic local mMap;
// the daemon's durable map is untouched. The holder must keep demanding consent
// across store/controller/Activity-like rebuilds and binder rebinds, and only a
// Confirmed commit or an authoritative resync from a NEW service may grant.
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
private val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION

@OptIn(ExperimentalCoroutinesApi::class)
class BbcConsentDurabilityFenceTest {

    /**
     * Two maps are essential: `local` is the optimistic mMap that a FAILED
     * commit still pollutes; `durable` is what the daemon actually persisted.
     * A fresh service instance rebuilds `local` from `durable` only.
     */
    private class FakePrefs(val durable: MutableMap<String, Any>) {
        val local: MutableMap<String, Any> = durable.toMutableMap()
        var commitOk: Boolean = true

        fun contains(key: String): Boolean = local.containsKey(key)
        fun getBoolean(key: String, def: Boolean): Boolean = local[key] as? Boolean ?: def
        fun getString(key: String, def: String?): String? = local[key] as? String ?: def

        fun commitBoolean(key: String, value: Boolean): Boolean {
            local[key] = value // optimistic local write happens either way
            if (!commitOk) return false
            durable[key] = value
            return true
        }

        /** New binder instance: daemon truth, no pollution from the old mMap. */
        fun freshInstance(): FakePrefs = FakePrefs(durable)

        fun authoritativeRead(generation: Long) = BbcConsentAuthoritativeRead(
            serviceGeneration = generation,
            optInGranted = if (contains(optinKey)) getBoolean(optinKey, false) else false,
            classification = if (contains(classKey)) getString(classKey, null) else null,
            legacyRuntimeEnabled = true,
        )
    }

    private fun newService(): FakePrefs {
        val durable = mutableMapOf<String, Any>(
            classKey to PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        )
        return FakePrefs(durable)
    }

    private fun TestScope.coordinator(
        holder: BbcActivationConsentHolder,
        service: FakePrefs,
    ): BbcToggleCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        return BbcToggleCoordinator(
            scope = appScope,
            state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { BbcWriteOutcome.Confirmed(it) },
            io = dispatcher,
            consentCommit = { if (service.commitBoolean(optinKey, true)) Confirmed() else Unknown() },
            consentRead = { generation -> service.freshInstance().authoritativeRead(generation) },
            consentHolder = holder,
        )
    }

    private fun Confirmed() = BbcWriteOutcome.Confirmed(true)
    private fun Unknown() = BbcWriteOutcome.Unknown

    private fun TestScope.harness(holder: BbcActivationConsentHolder, coordinator: BbcToggleCoordinator) =
        BbcConsentTestHarness(holder, coordinator, StandardTestDispatcher(testScheduler))

    private fun resolveFresh(holder: BbcActivationConsentHolder, service: FakePrefs, gen: Long) {
        holder.onServiceGenerationChanged(gen)
        holder.resolve(service.freshInstance().authoritativeRead(gen))
    }

    @Test
    fun failedCommitFlushesLocalOnly_nextConsentReadStillDemandsConsent() = runTest {
        val service = newService()
        val holder = BbcActivationConsentHolder()
        resolveFresh(holder, service, 1L)
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        service.commitOk = false
        val h = harness(holder, coordinator(holder, service))
        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()

        assertEquals("failed commit must leave the optimistic local mMap polluted",
            true, service.local[optinKey])
        assertFalse("nothing durable may be persisted by a failed commit",
            service.durable.containsKey(optinKey))
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))
        assertNull(holder.currentState().toggleUi().checked)

        // A same-generation read sees the polluted local value if it uses the
        // same store instance; the holder must refuse to clear the fence.
        holder.resolve(service.authoritativeRead(1L))
        assertEquals("raw local mMap must never upgrade the fence",
            BbcActivationConsentState.Unknown, holder.currentState())
        h.dispose()
    }

    @Test
    fun fenceSurvivesStoreControllerAndActivityLikeRebuilds() = runTest {
        val service = newService()
        val holder = BbcActivationConsentHolder()
        resolveFresh(holder, service, 1L)
        service.commitOk = false
        val a = harness(holder, coordinator(holder, service))
        a.userToggle(true)
        a.accept()
        testScheduler.advanceUntilIdle()
        a.dispose()

        // New store + new coordinator + new controller share only the holder.
        val service2 = service.freshInstance()
        val holder2 = holder // app-scoped: survives the "Activity" rebuild
        val b = harness(holder2, coordinator(holder2, service2))
        b.seedFromHolder()
        testScheduler.advanceUntilIdle()

        assertEquals(BbcActivationConsentState.Unknown, holder2.currentState())
        assertTrue(b.lastSettledChecked.not()) // neutral seed, no fabricated ON
        assertTrue(b.controller.currentLastGood().not())
        b.userToggle(true)
        assertEquals("reopen after a failed consent write must re-ask", 1, b.dialogsShown.size)
        b.dispose()
    }

    @Test
    fun serviceInstanceChangeDoesNotBlindlyTrustCachedGranted() = runTest {
        val service = newService()
        val holder = BbcActivationConsentHolder()
        resolveFresh(holder, service, 1L)

        // Confirmed grant on gen 1 -> Granted, durable opt-in persisted.
        val a = harness(holder, coordinator(holder, service))
        a.userToggle(true)
        a.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertEquals(true, service.durable[optinKey])
        a.dispose()

        // Binder death / rebinding: same holder, NEW prefs instance.
        holder.onServiceGenerationChanged(2L)
        assertEquals("cached Granted must degrade to Initializing on a new service",
            BbcActivationConsentState.Initializing, holder.currentState())

        // Authoritative resync from the new instance genuinely finds the durable key.
        val resync = harness(holder, coordinator(holder, service))
        resync.coordinator.submitConsentRead()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        resync.dispose()

        // Now simulate a different durable truth on a third instance: no opt-in
        // key => must resolve to NotGranted, never inherit the old Granted.
        val emptyDurable = mutableMapOf<String, Any>(
            classKey to PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
        )
        val service3 = FakePrefs(emptyDurable)
        holder.onServiceGenerationChanged(3L)
        assertEquals(BbcActivationConsentState.Initializing, holder.currentState())
        holder.resolve(service3.authoritativeRead(3L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())
    }

    @Test
    fun unknownSurvivesBinderDeathButMayClearViaAuthoritativeResync() = runTest {
        val service = newService()
        val holder = BbcActivationConsentHolder()
        resolveFresh(holder, service, 1L)
        service.commitOk = false
        val a = harness(holder, coordinator(holder, service))
        a.userToggle(true)
        a.accept()
        testScheduler.advanceUntilIdle()
        a.dispose()

        // Binder death: fence outlives the service instance.
        holder.onServiceGenerationChanged(2L)
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        // An authoritative resync from the NEW instance may clear the fence;
        // here the daemon never persisted the opt-in, so it resolves NotGranted.
        holder.resolve(service.freshInstance().authoritativeRead(2L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        // A fresh failed consent commit on gen 2 installs a NEW fence. A raw
        // same-generation mMap read (opt-in true) must never clear it.
        service.commitOk = false
        val b = harness(holder, coordinator(holder, service))
        b.userToggle(true)
        b.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        holder.resolve(BbcConsentAuthoritativeRead(2L, true, null, true))
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        b.dispose()

        // A daemon that really persisted the key is authoritative on a NEWER
        // service generation and may clear the fence.
        service.commitOk = true
        service.durable[optinKey] = true
        holder.onServiceGenerationChanged(3L)
        holder.resolve(service.freshInstance().authoritativeRead(3L))
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
    }

    @Test
    fun onlyConfirmedOrAuthoritativeResyncUpgrades() = runTest {
        val service = newService()
        val holder = BbcActivationConsentHolder()
        resolveFresh(holder, service, 1L)

        // A bare read claiming opt-in is never a grant by itself for a fresh
        // install? It IS authoritative when it is a real durable read on gen 1.
        holder.resolve(service.freshInstance().authoritativeRead(1L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        service.commitOk = false
        val h = harness(holder, coordinator(holder, service))
        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        // Same-gen raw local read=true (the polluted mMap) is rejected.
        holder.resolve(BbcConsentAuthoritativeRead(1L, true, null, true))
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        // Confirmed write on the current generation DOES grant.
        service.commitOk = true
        h.userToggle(true)
        val seq = h.dialogsShown.last()
        h.accept(seq)
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())

        // A Confirmed reply from a DEAD generation must not grant on a new one.
        holder.onServiceGenerationChanged(2L)
        holder.resolve(service.freshInstance().authoritativeRead(2L))
        val staleSeq = holder.nextIntentSequence()
        val applied = holder.markConsentWriteOutcome(Confirmed(), staleSeq, 1L)
        assertTrue("stale outcome must be recorded as applied-but-fenced", applied)
        assertEquals("reply from a dead service must install the Unknown fence",
            BbcActivationConsentState.Unknown, holder.currentState())
        h.dispose()
    }

    @Test
    fun offWinsOverPendingConsent() = runTest {
        val service = newService()
        val holder = BbcActivationConsentHolder()
        resolveFresh(holder, service, 1L)
        var consentWrites = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { BbcWriteOutcome.Confirmed(it) }, io = dispatcher,
            consentCommit = { consentWrites++; Confirmed() },
            consentRead = { gen -> service.freshInstance().authoritativeRead(gen) },
            consentHolder = holder,
        )
        val h = BbcConsentTestHarness(holder, coordinator, dispatcher)

        h.userToggle(true)
        val stale = h.dialogsShown.single()
        h.userToggle(false)
        h.accept(stale)
        testScheduler.advanceUntilIdle()

        assertEquals(0, consentWrites)
        assertEquals(listOf(false), h.toggleCalls)
        assertEquals(false, h.lastSettledChecked)
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())
        h.dispose()
    }
}
