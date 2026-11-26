// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase C (P1-04): local-vs-durable fake prefs drive the REAL app-scoped
// holder/coordinator. A failed commit flushes only the optimistic local mMap;
// the daemon's durable map is untouched. The holder must keep demanding consent
// across store/controller/Activity-like rebuilds and binder rebinds, and only a
// Confirmed commit or an authoritative resync from a NEW service may grant.
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
private val classKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
private val markerKey = PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED
private val runtimeKey = PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED

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

        /**
         * Owner-App bind-time classification: BOTH keys go into the optimistic
         * local map first. A failed commit leaves them there while the daemon's
         * durable map stays untouched — exactly the pollution the holder must
         * not authorize Legacy from.
         */
        fun commitClassification(classification: String, marker: Boolean): Boolean {
            local[classKey] = classification
            local[markerKey] = marker
            if (!commitOk) return false
            durable[classKey] = classification
            durable[markerKey] = marker
            return true
        }

        /** New binder instance: daemon truth, no pollution from the old mMap. */
        fun freshInstance(): FakePrefs = FakePrefs(durable)

        fun authoritativeRead(generation: Long) = BbcConsentAuthoritativeRead(
            serviceGeneration = generation,
            optIn = if (!contains(optinKey)) {
                BbcOptInValue.ABSENT
            } else if (getBoolean(optinKey, false)) {
                BbcOptInValue.TRUE
            } else {
                BbcOptInValue.FALSE
            },
            classification = if (contains(classKey)) getString(classKey, null) else null,
            migrated = if (contains(markerKey)) getBoolean(markerKey, false) else false,
            // Authoritative contains()-based seed: no default-true fabrication.
            legacyRuntimeEnabled = if (contains(runtimeKey)) getBoolean(runtimeKey, false) else null,
        )
    }

    private fun newService(): FakePrefs {
        val durable = mutableMapOf<String, Any>(
            classKey to PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
            runtimeKey to true,
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
    fun failedClassificationCommit_neverAuthorizesLegacy_fromPollutedLocalMap() = runTest {
        // Upgrade install: the owner App's bind-time durable classification
        // commit fails. The local mMap is polluted with PRE_UPGRADE + marker;
        // the daemon persisted NEITHER key.
        val durable = mutableMapOf<String, Any>(runtimeKey to true)
        val service = FakePrefs(durable)
        service.commitOk = false
        assertFalse(service.commitClassification(
            PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING, marker = true,
        ))
        assertEquals(PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING, service.local[classKey])
        assertEquals(true, service.local[markerKey])
        assertFalse("daemon persisted no classification", durable.containsKey(classKey))
        assertFalse("daemon persisted no migration marker", durable.containsKey(markerKey))

        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        // GalaxyApp feeds the FAILED outcome to the holder and MUST NOT resync
        // from this polluted read. Even if a stale caller does, the fence holds.
        holder.markInstallClassificationFailed(1L)
        holder.resolve(service.authoritativeRead(1L))
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))
        assertNull(holder.currentState().toggleUi().checked)

        val a = harness(holder, coordinator(holder, service))
        a.userToggle(true)
        assertEquals("a polluted upgrade must still demand consent", 1, a.dialogsShown.size)
        assertEquals(0, a.toggleCalls.size)
        a.dispose()

        // Retry on a NEW service generation with a confirmed durable commit:
        // classification + marker are both present in the daemon's map.
        service.commitOk = true
        assertTrue(service.commitClassification(
            PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING, marker = true,
        ))
        holder.onServiceGenerationChanged(2L)
        holder.markInstallClassificationConfirmed(2L)
        val b = harness(holder, coordinator(holder, service))
        // The legacy runtime seed is only used when a View is first built.
        // Supply the confirmed effect the projection needs to settle the box:
        // the durable legacy authorization alone must not fabricate a value.
        b.coordinator.state.mark(BbcWriteOutcome.Confirmed(true), 1L)
        testScheduler.runCurrent()
        b.coordinator.submitConsentRead()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Legacy(true), holder.currentState())
        assertEquals(true, b.lastSettledChecked)
        assertFalse(holder.currentState().requiresConsentFor(true))
        b.userToggle(true)
        b.userToggle(false)
        assertEquals("a durable legacy migration must not demand consent", 0, b.dialogsShown.size)
        assertEquals(listOf(true, false), b.toggleCalls)
        b.dispose()
    }

    @Test
    fun queuedReadAfterFailedClassification_fences_thenConfirmedReadAuthorizesLegacy() = runTest {
        val durable = mutableMapOf<String, Any>(runtimeKey to true)
        val service = FakePrefs(durable)
        service.commitOk = false
        assertFalse(service.commitClassification(
            PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING, marker = true,
        ))
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.markInstallClassificationFailed(1L)

        // P2 review: feed the OPTIMISTIC local mMap (not a fresh durable
        // instance) so the test actually exercises the pollution its comment
        // describes. The holder's same-generation fence must reject it.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val localReadCoordinator = BbcToggleCoordinator(
            scope = appScope,
            state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { BbcWriteOutcome.Confirmed(it) },
            io = dispatcher,
            consentCommit = { BbcWriteOutcome.Unknown },
            consentRead = { generation -> service.authoritativeRead(generation) },
            consentHolder = holder,
        )
        val a = BbcConsentTestHarness(holder, localReadCoordinator, dispatcher)
        a.coordinator.submitConsentRead()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))
        assertNull(holder.currentState().toggleUi().checked)
        a.dispose()

        // Retry on a NEW generation with a confirmed durable classification and
        // a persisted runtime opt-out of FALSE: no default-true fabrication.
        service.durable[runtimeKey] = false
        service.commitOk = true
        assertTrue(service.commitClassification(
            PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING, marker = true,
        ))
        holder.onServiceGenerationChanged(2L)
        holder.markInstallClassificationConfirmed(2L)
        val b = harness(holder, coordinator(holder, service))
        b.coordinator.submitConsentRead()
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Legacy(false), holder.currentState())
        assertEquals(false, b.lastSettledChecked)
        assertFalse(holder.currentState().requiresConsentFor(true))
        b.dispose()
    }

    @Test
    fun staleBindClassificationFailureCannotFenceCurrentGeneration() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(newService().authoritativeRead(1L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        // Binder death + rebind: generation 2 is backed by a NEW prefs instance.
        holder.onServiceGenerationChanged(2L)
        holder.resolve(newService().authoritativeRead(2L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        // The old bind's IO attempt finishes late and records its failure with
        // the DEAD generation. GalaxyApp now samples the generation from the
        // same IO snapshot as the prefs instance, but even if a stale caller
        // reports the dead generation, it must NOT fence the current instance.
        holder.markInstallClassificationFailed(1L)
        assertEquals("a dead generation must not fence the current one",
            BbcActivationConsentState.NotGranted, holder.currentState())
        holder.resolve(newService().authoritativeRead(2L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        // A failure attributed to the CURRENT generation DOES fence: the
        // optimistic PRE_UPGRADE + marker local read from that same polluted
        // instance can then never authorize Legacy.
        val pollutedCurrent = FakePrefs(mutableMapOf<String, Any>())
        pollutedCurrent.commitOk = false
        assertFalse(pollutedCurrent.commitClassification(
            PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING, marker = true,
        ))
        holder.markInstallClassificationFailed(2L)
        holder.resolve(pollutedCurrent.authoritativeRead(2L))
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))
        assertNull(holder.currentState().toggleUi().checked)
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
        holder.resolve(bbcConsentAuthoritativeReadLegacyBoolean(2L, true, null, null, true))
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
        holder.resolve(bbcConsentAuthoritativeReadLegacyBoolean(1L, true, null, null, true))
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
    fun staleUnknownAfterOffAbandon_stillInstallsDurableFence() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val service = newService()
        holder.resolve(service.authoritativeRead(1L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        // An opt-in commit entered gen 1, then OFF/abandon advanced the intent
        // fence; only THEN did its Unknown outcome arrive.
        val seq = holder.nextIntentSequence()
        holder.notePendingEnable(seq, 1L)
        holder.supersedeActiveIntent()
        assertFalse("a stale outcome must get no callback", holder.markConsentWriteOutcome(Unknown(), seq, 1L))
        assertFalse("a stale outcome must not revive the enable", holder.consumePendingEnable(seq, 1L))
        assertEquals("durability evidence survives the stale UI outcome",
            BbcActivationConsentState.Unknown, holder.currentState())

        // The polluted same-generation instance cannot clear its own fence.
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 1L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        // Only an authoritative resync from a NEW service generation may clear it.
        holder.onServiceGenerationChanged(2L)
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
    }

    @Test
    fun queuedGen1WriteTouchedGen2_fencesGen2AndNeverGrants() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        resolveFresh(holder, newService(), 1L)
        val service = newService()
        val outcomes = mutableListOf<BbcWriteOutcome>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { BbcWriteOutcome.Confirmed(it) }, io = dispatcher,
            consentCommitAt = { captured ->
                assertEquals(1L, captured) // captured at user-intent time
                // Binder death/rebind before the commit: the write touched the
                // NEW generation's prefs instance. A Confirmed reply from the
                // dead captured generation must be forced to Unknown.
                holder.onServiceGenerationChanged(2L)
                BbcConsentWriteResult(Unknown(), touchedGeneration = 2L)
            },
            consentRead = { generation ->
                BbcConsentAuthoritativeRead(
                    serviceGeneration = generation, optIn = BbcOptInValue.TRUE,
                    classification = null, migrated = null, legacyRuntimeEnabled = true,
                )
            },
            consentHolder = holder,
        )
        coordinator.submitConsentWrite(1L, 1L, onResult = { outcomes.add(it) })
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(BbcWriteOutcome.Unknown), outcomes)
        assertEquals("a mismatched generation must never grant",
            BbcActivationConsentState.Unknown, holder.currentState())

        // The gen-2 resync sees opt-in=true in its OWN prefs instance. The
        // fence is at gen 2, so it may NOT clear itself into a false grant.
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        // A genuinely newer generation may clear the fence authoritatively.
        holder.onServiceGenerationChanged(3L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
    }

    @Test
    fun confirmedFromMismatchedGeneration_neverGrantsAndFencesTouchedGeneration() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(newService().authoritativeRead(1L))
        val seq = holder.nextIntentSequence()
        assertTrue(holder.markConsentWriteOutcome(Confirmed(), seq, touchedGeneration = 2L))
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        // The reply was attributed to gen 2: make gen 2 the CURRENT generation,
        // otherwise resolve(2) is rejected before the fence logic runs and the
        // fence-attribution assertion below would be vacuous.
        holder.onServiceGenerationChanged(2L)
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
            ),
        )
        assertEquals("a gen-2 read must not clear a fence raised on gen 2",
            BbcActivationConsentState.Unknown, holder.currentState())
        holder.onServiceGenerationChanged(3L)
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 3L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
    }

    /**
     * Phase 2 fixer (BLOCKING 3): a stale reply from a DEAD generation must
     * never LOWER a fence already raised on a newer one. Old code computed
     * `touchedGeneration` alone (cur.fence=2, touched=1 -> 1) and the
     * `fence != cur.fenceGeneration` guard accepted the downgrade while
     * preserving Granted; the weakened fence could later be cleared.
     */
    @Test
    fun deadGenerationReplyNeverLowersAnExistingFence() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(2L)
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        val seq = holder.nextIntentSequence()
        assertTrue(holder.markConsentWriteOutcome(Confirmed(), seq, 2L))

        // A STALE Unknown outcome that really touched the CURRENT generation
        // records pollution evidence without downgrading the confirmed grant.
        assertFalse(holder.markConsentWriteOutcome(Unknown(), seq, 2L))
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertEquals(2L, holder.currentFenceGeneration())

        // A late reply attributed to the DEAD gen 1 used to lower it to 1.
        assertFalse(holder.markConsentWriteOutcome(Unknown(), seq, 1L))
        assertEquals("a dead generation must not weaken the current fence", 2L, holder.currentFenceGeneration())
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())

        // One more dead reply still cannot lower it: its evidence belongs to
        // gen 1, not the current gen 2.
        assertFalse(holder.markConsentWriteOutcome(Unknown(), seq, 1L))
        assertEquals(2L, holder.currentFenceGeneration())
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
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

    /**
     * R5-03: a late failure from a DEAD session records pollution evidence on
     * that session's prefs WITHOUT downgrading an already Granted/Legacy state.
     * The evidence must survive later reads — including a same-prefs read at a
     * higher generation — because the optimistic local map cannot clear it.
     */
    @Test
    fun staleFailureEvidenceSurvivesGrantedAndSamePrefsReadCannotClearIt() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        holder.resolve(newService().authoritativeRead(1L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        val oldPrefs = Any()
        val s1 = BbcConsentSessionIdentity(1L, Any(), oldPrefs)
        holder.markConsentWriteOutcome(Unknown(), 1L, 1L, session = s1)
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertSame(s1, holder.currentFenceSession())

        // A Confirmed grant from a genuinely different session clears the fence.
        holder.onServiceGenerationChanged(2L)
        val newPrefs = Any()
        val s2 = BbcConsentSessionIdentity(2L, Any(), newPrefs)
        holder.markConsentWriteOutcome(Confirmed(), 2L, 2L, session = s2)
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())

        // Late failure reply from the dead session: evidence is preserved on
        // S1's prefs, but the confirmed grant is NOT downgraded.
        holder.markConsentWriteOutcome(Unknown(), 2L, 1L, session = s1)
        assertEquals("a stale failure must not downgrade a confirmed grant",
            BbcActivationConsentState.Granted, holder.currentState())
        assertEquals(1L, holder.currentFenceGeneration())
        assertSame("stale evidence stays attributed to its own prefs instance",
            s1, holder.currentFenceSession())

        // A DIFFERENT-prefs read while Granted must leave the evidence intact.
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
                session = s2,
            ),
        )
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertSame(s1, holder.currentFenceSession())

        // The same-prefs rule must be exercised while the state is still UNKNOWN:
        // resolving while already Granted returns early and proves nothing.
        val fenced = BbcActivationConsentHolder()
        fenced.onServiceGenerationChanged(1L)
        val f1 = BbcConsentSessionIdentity(1L, Any(), oldPrefs)
        fenced.markConsentWriteOutcome(Unknown(), 1L, 1L, session = f1)
        assertEquals(BbcActivationConsentState.Unknown, fenced.currentState())
        fenced.onServiceGenerationChanged(2L)
        fenced.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
                session = BbcConsentSessionIdentity(2L, Any(), oldPrefs),
            ),
        )
        assertEquals("a same-prefs higher-generation read must not clear the fence",
            BbcActivationConsentState.Unknown, fenced.currentState())
        assertSame("the evidence stays attributed to its own prefs instance",
            f1, fenced.currentFenceSession())
    }

    /**
     * R5-03 review R1: a stamped read must never lift an Unknown fence whose
     * session identity was not recorded. Falling back to the generation number
     * here re-opens the generation-only fence the session stamp was added to close.
     */
    @Test
    fun stampedReadCannotClearSessionlessUnknownFence() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        // A legacy/sessionless writer installed a generation-only fence.
        holder.markConsentWriteOutcome(Unknown(), 1L, 1L)
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertNull(holder.currentFenceSession())

        holder.onServiceGenerationChanged(2L)
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
                session = BbcConsentSessionIdentity(2L, Any(), Any()),
            ),
        )
        assertEquals("a stamped read cannot clear a sessionless fence by generation",
            BbcActivationConsentState.Unknown, holder.currentState())
        assertNull(holder.currentFenceSession())
    }

    /**
     * R5-03 review R3: a SAME-prefs fence is deliberately permanent for resync.
     * The only recovery is a fresh Confirmed consent write on the current
     * generation — it clears the fence and drives exactly one app-consumer enable.
     */
    @Test
    fun confirmedGrantOnCurrentGeneration_recoversFromSamePrefsFenceOnce() = runTest {
        val holder = BbcActivationConsentHolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val oldPrefs = Any()
        val s1 = BbcConsentSessionIdentity(1L, Any(), oldPrefs)

        holder.onServiceGenerationChanged(1L)
        holder.markConsentWriteOutcome(Unknown(), holder.nextIntentSequence(), 1L, session = s1)
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertSame(s1, holder.currentFenceSession())

        holder.onServiceGenerationChanged(2L)
        val s2 = BbcConsentSessionIdentity(2L, s1.serviceIdentity, oldPrefs)
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L, optIn = BbcOptInValue.TRUE,
                classification = null, migrated = null, legacyRuntimeEnabled = true,
                session = s2,
            ),
        )
        assertEquals("resync from the same prefs instance must stay fenced",
            BbcActivationConsentState.Unknown, holder.currentState())
        assertSame(s1, holder.currentFenceSession())

        val enableWrites = mutableListOf<Boolean>()
        val coordinator = BbcToggleCoordinator(
            scope = appScope, io = dispatcher,
            state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { value -> enableWrites.add(value); Confirmed() },
            consentCommitAt = { generation -> BbcConsentWriteResult(Confirmed(), generation, s2) },
            currentSession = { s2 },
            consentHolder = holder,
        )
        val grant = holder.startConsentIntent()
        holder.notePendingEnable(grant, 2L)
        assertEquals(
            BbcWorkEnqueue.Accepted,
            coordinator.submitConsentWrite(grant, 2L, onResult = {}),
        )
        testScheduler.advanceUntilIdle()

        assertEquals("a fresh Confirmed grant is the explicit recovery",
            BbcActivationConsentState.Granted, holder.currentState())
        assertNull("the recovery clears the fence", holder.currentFenceSession())
        assertEquals(-1L, holder.currentFenceGeneration())
        assertEquals("exactly one enable is driven by the recovery", listOf(true), enableWrites)
        appScope.cancel()
    }

    /**
     * R5 Phase 3a: a NO-COMMIT consent outcome (GalaxyApp's 0L sentinel) must
     * leave the holder's state AND fence untouched. A sessionless Unknown fence
     * would fail closed for EVERY production-stamped authoritative read, across
     * rebinds, until a manual re-grant — even though no local mMap was polluted.
     * This drives the FULL coordinator executeConsentWrite path with the real
     * holder, then a stamped (forSession) durable resync.
     */
    @Test
    fun noCommitConsentOutcome_neverBlocksStampedResync() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }

        val s1 = BbcConsentSessionIdentity(1L, Any(), Any())
        val s2 = BbcConsentSessionIdentity(2L, Any(), Any())
        var currentSession: BbcConsentSessionIdentity = s1
        val outcomes = mutableListOf<BbcWriteOutcome>()
        val enableWrites = mutableListOf<Boolean>()

        val coordinator = BbcToggleCoordinator(
            scope = appScope, io = dispatcher,
            state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { value -> enableWrites.add(value); Confirmed() },
            consentCommitAt = { _ ->
                // A rebind lands AFTER the coordinator's authority check but
                // BEFORE the commit's session pre-check. performBbcConsentWrite
                // sees a stale session and returns the NO-GENERATION sentinel:
                // no edit()/commit() entered, so no pollution evidence exists.
                holder.onServiceGenerationChanged(2L)
                currentSession = s2
                BbcConsentWriteResult(
                    BbcWriteOutcome.Unknown,
                    GalaxyApp.NO_CONSENT_COMMIT_GENERATION,
                    sessionIdentity = null,
                )
            },
            currentSession = { currentSession },
            consentRead = { generation ->
                assertEquals(2L, generation)
                // PRODUCTION shape: stamped via BbcConsentDurableSnapshot.forSession,
                // NOT an unstamped legacy read.
                BbcConsentDurableSnapshot(
                    optIn = BbcOptInValue.TRUE,
                    classification = null,
                    migrated = null,
                    legacyRuntimeEnabled = true,
                    enablePending = null,
                ).forSession(s2)
            },
            consentHolder = holder,
        )

        val seq = holder.startConsentIntent()
        holder.notePendingEnable(seq, 1L)
        assertEquals(
            BbcWorkEnqueue.Accepted,
            coordinator.submitConsentWrite(seq, 1L, onResult = { outcomes.add(it) }),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(BbcWriteOutcome.Unknown), outcomes)
        assertTrue("no commit entered -> no enable may be driven by the failed grant",
            enableWrites.isEmpty())

        // The production-stamped resync on the NEW instance must reach the
        // durable grant. Pre-fix, the sessionless Unknown fence fails closed.
        assertEquals(BbcWorkEnqueue.Accepted, coordinator.submitConsentRead {})
        testScheduler.advanceUntilIdle()
        assertEquals(BbcActivationConsentState.Granted, holder.currentState())
        assertNull("a no-commit outcome must not install any fence",
            holder.currentFenceSession())
        assertEquals(-1L, holder.currentFenceGeneration())
        assertTrue("the marker-less resync drives no enable either", enableWrites.isEmpty())
        appScope.cancel()
    }

    /**
     * R5 Phase 3a hardening: the queued-write recovery boundary with PRODUCTION-
     * STAMPED reads (`forSession`, not the unstamped legacy shape). A genuinely
     * failed commit stamps its fence with the session it touched; only a
     * DIFFERENT prefs instance may clear it — a higher bind generation on the
     * SAME optimistic map may not.
     */
    @Test
    fun stampedFenceFromFailedCommit_recoversOnlyFromDifferentPrefs() = runTest {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val s2 = BbcConsentSessionIdentity(2L, Any(), Any())
        val outcomes = mutableListOf<BbcWriteOutcome>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        var readSession = s2 // same prefs, gen 2
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = BbcToggleRuntimeState(initialConfirmed = false),
            commit = { Confirmed() }, io = dispatcher,
            consentCommitAt = { BbcConsentWriteResult(Unknown(), 2L, s2) },
            consentRead = { _ ->
                BbcConsentDurableSnapshot(
                    optIn = BbcOptInValue.TRUE,
                    classification = null,
                    migrated = null,
                    legacyRuntimeEnabled = true,
                ).forSession(readSession)
            },
            consentHolder = holder,
        )

        coordinator.submitConsentWrite(
            holder.nextIntentSequence(), 1L, onResult = { outcomes.add(it) },
        )
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(BbcWriteOutcome.Unknown), outcomes)
        assertSame(s2, holder.currentFenceSession())
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        holder.onServiceGenerationChanged(2L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a same-prefs stamped read must not clear the fence",
            BbcActivationConsentState.Unknown, holder.currentState())
        assertSame(s2, holder.currentFenceSession())

        holder.onServiceGenerationChanged(3L)
        readSession = BbcConsentSessionIdentity(3L, Any(), Any()) // genuinely fresh prefs
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals("a different-prefs stamped read is the recovery",
            BbcActivationConsentState.Granted, holder.currentState())
        assertNull(holder.currentFenceSession())
        appScope.cancel()
    }

    /**
     * R5-04 (P1), durable-fence level: an old Confirmed(false) plus a grant
     * whose optin/marker exist ONLY in a FAILED commit's optimistic local mMap
     * must NEVER drive an enable. Only a later authoritative read that VERIFIES
     * the durable optin+marker on a fresh prefs instance may promote the grant
     * attempt and converge the owed enable exactly once.
     */
    @Test
    fun unverifiedLocalMarkerNeverEnables_verifiedDurableMarkerEnablesOnceAfterRebind() = runTest {
        val pendingKey = "app_enable_pending_test_only"
        val durable = mutableMapOf<String, Any>(
            classKey to PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED,
            runtimeKey to false,
        )
        val service = FakePrefs(durable)
        val sequence = AtomicLong(0L)
        val holder = BbcActivationConsentHolder(sequence)
        val runtime = BbcToggleRuntimeState(initialConfirmed = false, sequence = sequence)
        holder.onServiceGenerationChanged(1L)
        holder.resolve(service.authoritativeRead(1L))
        assertEquals(BbcActivationConsentState.NotGranted, holder.currentState())

        val s1 = BbcConsentSessionIdentity(1L, Any(), Any())
        var fresh = BbcConsentSessionIdentity(2L, Any(), Any())
        val commits = mutableListOf<Boolean>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        coroutineContext[Job]?.invokeOnCompletion { appScope.cancel() }
        val coordinator = BbcToggleCoordinator(
            scope = appScope, state = runtime,
            commit = { value ->
                commits.add(value)
                // One atomic runtime+marker commit, as in production.
                service.commitBoolean(runtimeKey, value)
                service.commitBoolean(pendingKey, false)
                BbcWriteOutcome.Confirmed(value)
            },
            io = dispatcher,
            consentCommitAt = { gen ->
                // The daemon rejects the write: only the optimistic mMap is
                // polluted. The commit reply is lost to the rebind.
                service.commitOk = false
                service.commitBoolean(optinKey, true)
                service.commitBoolean(pendingKey, true)
                BbcConsentWriteResult(Unknown(), gen, s1)
            },
            consentRead = { gen ->
                service.freshInstance().authoritativeRead(gen).copy(
                    enablePending = durable[pendingKey] as? Boolean ?: false,
                    session = fresh,
                )
            },
            currentSession = { fresh },
            consentHolder = holder,
        )
        val h = harness(holder, coordinator)

        h.userToggle(false) // the older, real Confirmed(false)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), commits)
        h.userToggle(true)
        h.accept()
        testScheduler.advanceUntilIdle()
        assertEquals("only the optimistic mMap carries the grant marker",
            true, service.local[pendingKey])
        assertFalse("the daemon never persisted opt-in", durable.containsKey(optinKey))
        assertEquals("an Unknown reply may not drive phase 2", listOf(false), commits)

        holder.onServiceGenerationChanged(2L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertFalse("a local-only grant must never authorize",
            holder.currentState() is BbcActivationConsentState.Granted)
        assertEquals("no enable from unverified local evidence", listOf(false), commits)
        h.dispose()

        // Now the daemon REALLY persists both keys. A genuinely fresh session
        // verifies them and converges the still-owed enable exactly once.
        service.commitOk = true
        durable[optinKey] = true
        durable[pendingKey] = true
        fresh = BbcConsentSessionIdentity(3L, Any(), Any())
        holder.onServiceGenerationChanged(3L)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertTrue("a verified durable read resolves Granted",
            holder.currentState() is BbcActivationConsentState.Granted)
        assertEquals(
            "a verified durable marker converges exactly one enable",
            listOf(false, true), commits,
        )
        assertEquals("the enable cleared the durable marker",
            false, durable[pendingKey] as? Boolean)
        coordinator.submitConsentRead {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, true), commits)
    }
}
