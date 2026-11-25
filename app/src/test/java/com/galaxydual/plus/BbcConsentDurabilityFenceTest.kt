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
}
