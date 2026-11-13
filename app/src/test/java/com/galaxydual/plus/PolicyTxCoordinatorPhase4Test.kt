// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase4 (tombstone bound + synthetic-path + recover-branch hardening):
// pure PolicyTxCoordinator unit tests. No ViewModel, no store IO, no sleeps —
// aging is driven deterministically (staleThresholdMs=0 with a completed
// owner deferred; "now" is computed from the staged fence time), so these
// tests never flake on timing. Count discipline: only ==0/empty/null are
// exact; 1/2-style counts assert relatively (>=).
package com.galaxydual.plus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTxCoordinatorPhase4Test {

    private fun fakeStore(): HookPolicyStore = HookPolicyStore(
        loadRaw = { null },
        saveRaw = { true },
    )

    private fun placeholderTx(
        coord: PolicyTxCoordinator,
        key: String,
        completedOwner: Boolean,
        store: HookPolicyStore = fakeStore(),
    ): DualViewModel.PolicyTx {
        val deferred = if (completedOwner) CompletableDeferred(true)
        else CompletableDeferred<Boolean>()
        return DualViewModel.PolicyTx(
            coord.nextTxId(), key, deferred, setOf("com.p"), store, false,
        )
    }

    private fun stagePlaceholder(
        coord: PolicyTxCoordinator,
        tx: DualViewModel.PolicyTx,
    ): PolicySettled = runBlocking {
        coord.activeTx.set(tx)
        val placeholder = PolicySettled(tx.txId, tx.key, setOf("com.p"), false, MsgKind.CHANNEL, "", false)
        assertTrue(coord.completeWithRevision(tx, placeholder))
        coord.pendingPublish.get()!!
    }

    // recover branch 1: recorded owner still active in coordinator (same
    // identity) -> in-flight, keep (null, pending intact).
    @Test
    fun recover_ownerActive_keepsFence() {
        val coord = PolicyTxCoordinator()
        try {
            val tx = placeholderTx(coord, "phase4-owner-active", completedOwner = true)
            stagePlaceholder(coord, tx)
            // Simulate a live owner: activeTx still holds this exact Tx.
            coord.activeTx.set(tx)
            assertNull(coord.recoverStalePendingLocked(System.nanoTime(), 0))
            assertNotNull(coord.pendingPublish.get())
            assertFalse(coord.isTombstoned(tx.txId, tx.key))
        } finally {
            coord.close()
        }
    }

    // recover branch 2: real (terminal) payload is never aged-out here,
    // even with a completed owner and stale age -> keep (null, pending intact).
    @Test
    fun recover_realPayload_neverDropped() {
        val coord = PolicyTxCoordinator()
        try {
            val tx = placeholderTx(coord, "phase4-real", completedOwner = true)
            stagePlaceholder(coord, tx)
            val real = PolicySettled(tx.txId, tx.key, setOf("com.r"), true, MsgKind.INFO, "m", false)
            val staged = runBlocking { coord.stageRealPayload(real) }
            assertNotNull(staged)
            assertNull(coord.recoverStalePendingLocked(System.nanoTime(), 0))
            assertNotNull(coord.pendingPublish.get())
            assertFalse(coord.isTombstoned(tx.txId, tx.key))
        } finally {
            coord.close()
        }
    }

    // recover branch 3: legacy null-owner (no owner record) + aged
    // placeholder + inactive -> dropped with tombstone (M3 dead-owner path).
    @Test
    fun recover_legacyNullOwner_dropsAndTombstones() {
        val coord = PolicyTxCoordinator()
        try {
            val tx = placeholderTx(coord, "phase4-legacy", completedOwner = true)
            // Direct stage without completeWithRevision: no owner record,
            // nanos fence untouched (stagedAt==0 would keep) — so stamp an
            // old fence explicitly via the public clock field.
            val placeholder = PolicySettled(tx.txId, tx.key, setOf("com.p"), false, MsgKind.CHANNEL, "", false, rev = 7L)
            coord.pendingPublish.set(placeholder)
            coord.pendingPublishAtNanos.set(System.nanoTime() - 5_000_000_000L)
            assertNull(coord.pendingOwner.get())
            val dropped = coord.recoverStalePendingLocked(System.nanoTime(), 0)
            assertNotNull(dropped)
            assertEquals(tx.txId, dropped!!.txId)
            assertNull(coord.pendingPublish.get())
            assertTrue(coord.isTombstoned(tx.txId, tx.key))
            assertTrue(coord.orphanDropGen.get() >= 1L)
        } finally {
            coord.close()
        }
    }

    // recover: monotonic clock backwards jump (now < stagedAt) never infers
    // death -> keep (null, pending intact, no tombstone).
    @Test
    fun recover_clockBackwards_keepsFence() {
        val coord = PolicyTxCoordinator()
        try {
            val tx = placeholderTx(coord, "phase4-backwards", completedOwner = true)
            stagePlaceholder(coord, tx)
            val stagedAt = coord.pendingPublishAtNanos.get()
            assertTrue(stagedAt != 0L)
            assertNull(coord.recoverStalePendingLocked(stagedAt - 1, 0))
            assertNotNull(coord.pendingPublish.get())
            assertFalse(coord.isTombstoned(tx.txId, tx.key))
        } finally {
            coord.close()
        }
    }

    // recover: never-staged fence (stagedAt==0, e.g. direct set by a legacy
    // path or cleared clock) -> keep (null, never infer death from no time).
    @Test
    fun recover_neverStagedClock_keepsFence() {
        val coord = PolicyTxCoordinator()
        try {
            val tx = placeholderTx(coord, "phase4-noclock", completedOwner = true)
            coord.pendingPublish.set(
                PolicySettled(tx.txId, tx.key, setOf("com.p"), false, MsgKind.CHANNEL, "", false),
            )
            assertEquals(0L, coord.pendingPublishAtNanos.get())
            assertNull(coord.recoverStalePendingLocked(System.nanoTime(), 0))
            assertNotNull(coord.pendingPublish.get())
            assertFalse(coord.isTombstoned(tx.txId, tx.key))
        } finally {
            coord.close()
        }
    }

    // Phase4 bound: 512 tombstones full -> recover rejects the new drop
    // (null, pending KEPT, size stays 512); no arbitrary eviction.
    @Test
    fun recover_tombstoneFull_rejectsAndKeepsFence() {
        val coord = PolicyTxCoordinator()
        try {
            repeat(512) { i -> coord.claimTombstones["old:$i"] = i.toLong() }
            assertEquals(512, coord.claimTombstones.size)
            val tx = placeholderTx(coord, "phase4-full", completedOwner = true)
            stagePlaceholder(coord, tx)
            val genBefore = coord.orphanDropGen.get()
            assertNull(coord.recoverStalePendingLocked(System.nanoTime(), 0))
            // Fence kept for STALE retry; table untouched (no eviction).
            assertNotNull(coord.pendingPublish.get())
            assertEquals(512, coord.claimTombstones.size)
            assertFalse(coord.isTombstoned(tx.txId, tx.key))
            assertEquals(genBefore, coord.orphanDropGen.get())
            assertTrue(coord.tombstoneRejects.get() >= 1L)
        } finally {
            coord.close()
        }
    }

    // Phase4 bound: 512 tombstones full -> overrun discard refuses (false,
    // pending KEPT); below the cap it still discards + tombstones.
    @Test
    fun discardOverrun_tombstoneFull_refusesAndKeepsFence() {
        val coord = PolicyTxCoordinator()
        try {
            repeat(512) { i -> coord.claimTombstones["old:$i"] = i.toLong() }
            val tx = placeholderTx(coord, "phase4-overrun-full", completedOwner = false)
            stagePlaceholder(coord, tx)
            val rejectsBefore = coord.tombstoneRejects.get()
            assertFalse(runBlocking { coord.discardOverrunPlaceholder(tx.txId, tx.key) })
            assertNotNull(coord.pendingPublish.get())
            assertEquals(512, coord.claimTombstones.size)
            assertFalse(coord.isTombstoned(tx.txId, tx.key))
            assertTrue(coord.tombstoneRejects.get() > rejectsBefore)
        } finally {
            coord.close()
        }
    }

    @Test
    fun discardOverrun_belowCap_discardsAndTombstones() {
        val coord = PolicyTxCoordinator()
        try {
            val tx = placeholderTx(coord, "phase4-overrun-ok", completedOwner = false)
            stagePlaceholder(coord, tx)
            assertTrue(runBlocking { coord.discardOverrunPlaceholder(tx.txId, tx.key) })
            assertNull(coord.pendingPublish.get())
            assertTrue(coord.isTombstoned(tx.txId, tx.key))
            assertTrue(coord.orphanDropGen.get() >= 1L)
        } finally {
            coord.close()
        }
    }

    // tryEmitSettled synthetic path: foreign pending in-flight -> reject
    // (false, latest untouched, superseded counted).
    @Test
    fun tryEmitSettled_foreignPending_rejected() {
        val coord = PolicyTxCoordinator()
        try {
            val txA = placeholderTx(coord, "phase4-foreign-A", completedOwner = false)
            stagePlaceholder(coord, txA)
            val supersededBefore = coord.emitSuperseded.get()
            val foreign = PolicySettled(
                coord.nextTxId(), "phase4-foreign-B",
                setOf("com.b"), true, MsgKind.INFO, "mB", false,
            )
            assertFalse(coord.tryEmitSettled(foreign))
            assertNull(coord.latest.value)
            assertTrue(coord.emitSuperseded.get() > supersededBefore)
        } finally {
            coord.close()
        }
    }

    // tryEmitSettled: payload rev behind _latest -> reject (false, latest kept).
    @Test
    fun tryEmitSettled_revBehindLatest_rejected() {
        val coord = PolicyTxCoordinator()
        try {
            val first = PolicySettled(
                coord.nextTxId(), "phase4-seq-1",
                setOf("com.1"), true, MsgKind.INFO, "m1", false,
            )
            val second = PolicySettled(
                coord.nextTxId(), "phase4-seq-2",
                setOf("com.2"), true, MsgKind.INFO, "m2", false,
            )
            assertTrue(coord.tryEmitSettled(first))
            assertTrue(coord.tryEmitSettled(second))
            val latestRev = coord.latest.value!!.rev
            assertTrue(latestRev >= 2L)
            val stale = PolicySettled(
                coord.nextTxId(), "phase4-stale",
                setOf("com.s"), true, MsgKind.INFO, "mS", false,
                rev = latestRev - 1,
            )
            val supersededBefore = coord.emitSuperseded.get()
            assertFalse(coord.tryEmitSettled(stale))
            assertEquals(second.txId, coord.latest.value!!.txId)
            assertEquals(latestRev, coord.latest.value!!.rev)
            assertTrue(coord.emitSuperseded.get() > supersededBefore)
        } finally {
            coord.close()
        }
    }

    // tryEmitSettled: no pending -> pure synthetic path assigns strictly
    // increasing revs (monotonic seq, latest advances each time).
    @Test
    fun tryEmitSettled_noPending_syntheticRevMonotonic() {
        val coord = PolicyTxCoordinator()
        try {
            assertNull(coord.pendingPublish.get())
            val revs = mutableListOf<Long>()
            repeat(3) { i ->
                val s = PolicySettled(
                    coord.nextTxId(), "phase4-synth-$i",
                    setOf("com.s$i"), true, MsgKind.INFO, "m$i", false,
                )
                assertTrue(coord.tryEmitSettled(s))
                revs.add(coord.latest.value!!.rev)
            }
            assertTrue(revs[0] >= 1L)
            assertTrue(revs[1] > revs[0])
            assertTrue(revs[2] > revs[1])
        } finally {
            coord.close()
        }
    }

    // Phase4 test lock: canonical PolicyTx acquisition order is pinned;
    // any future coordinator lock joins the tail (never the head).
    @Test
    fun policyTxLockOrder_pinned() {
        val coord = PolicyTxCoordinator()
        try {
            assertEquals(
                "opMutex->writeMutex->policyRevisionMutex->commitMutex",
                coord.policyTxLockOrder(),
            )
        } finally {
            coord.close()
        }
    }

    @Test
    fun completeWithRevisionChecked_claimsMatching_rejectsStale_enforcesPlaceholderOnly() = runBlocking {
        val coord = PolicyTxCoordinator()
        try {
            // Non-placeholder payloads must be rejected fail-loud by both claim methods.
            val txBad = placeholderTx(coord, "phase4-non-ph", completedOwner = true)
            val realPayload = PolicySettled(txBad.txId, txBad.key, setOf("com.p"), true, MsgKind.INFO, "ok", false)
            val msgOnlyPayload = PolicySettled(txBad.txId, txBad.key, setOf("com.p"), false, MsgKind.CHANNEL, "err", false)
            for (bad in listOf(realPayload, msgOnlyPayload)) {
                var threwUnchecked = false
                try {
                    coord.completeWithRevision(txBad, bad)
                } catch (_: IllegalArgumentException) {
                    threwUnchecked = true
                }
                assertTrue("completeWithRevision must reject non-placeholder $bad", threwUnchecked)
                var threwChecked = false
                try {
                    coord.completeWithRevisionChecked(txBad, bad, 0L)
                } catch (_: IllegalArgumentException) {
                    threwChecked = true
                }
                assertTrue("completeWithRevisionChecked must reject non-placeholder $bad", threwChecked)
            }

            // Stale revBefore rejected: activeTx intact, committedRevision unchanged.
            val tx1 = placeholderTx(coord, "phase4-checked-stale", completedOwner = true)
            coord.activeTx.set(tx1)
            val bumped = coord.bumpRevisionForTests()
            assertEquals(1L, bumped)
            val ph1 = PolicySettled(tx1.txId, tx1.key, emptySet(), false, MsgKind.CHANNEL, "", true)
            assertFalse(coord.completeWithRevisionChecked(tx1, ph1, 0L))
            assertTrue(coord.activeTx.get() === tx1)
            assertEquals(1L, coord.committedRevision.get())
            assertNull(coord.pendingPublish.get())

            // Matching revBefore succeeds: activeTx cleared, committedRevision bumped, placeholder staged.
            assertTrue(coord.completeWithRevisionChecked(tx1, ph1, 1L))
            assertNull(coord.activeTx.get())
            assertEquals(2L, coord.committedRevision.get())
            assertEquals(2L, coord.pendingPublish.get()?.rev)
        } finally {
            coord.close()
        }
    }

    @Test
    fun latest_negativeOneSentinel_neverOverwritesPositiveRev() = runBlocking {
        val coord = PolicyTxCoordinator()
        try {
            val first = PolicySettled(
                coord.nextTxId(), "phase4-pos-1",
                setOf("com.1"), true, MsgKind.INFO, "m1", false,
            )
            assertTrue(coord.tryEmitSettled(first))
            val posLatest = coord.latest.value
            assertNotNull(posLatest)
            assertTrue(posLatest!!.rev > 0L)

            // Verify stageRealPayload rejects -1L sentinel when _latest already holds positive rev.
            val txNeg = placeholderTx(coord, "phase4-neg-sentinel", completedOwner = true)
            val negPending = PolicySettled(
                txNeg.txId, txNeg.key, setOf("com.bad"), false, MsgKind.CHANNEL, "", false, rev = -1L,
            )
            coord.pendingPublish.set(negPending)
            val supersededBefore = coord.emitSuperseded.get()
            val stagedNeg = coord.stageRealPayload(
                PolicySettled(txNeg.txId, txNeg.key, setOf("com.bad"), true, MsgKind.INFO, "bad", false, rev = -1L),
            )
            assertNull(stagedNeg)
            assertTrue(coord.emitSuperseded.get() > supersededBefore)
            assertEquals(posLatest, coord.latest.value)

            // Verify tryUpdateLatestMonotonic itself rejects an incoming -1L sentinel over positive rev.
            val m = PolicyTxCoordinator::class.java.getDeclaredMethod(
                "tryUpdateLatestMonotonic",
                PolicySettled::class.java,
            ).apply { isAccessible = true }
            val negSentinel = PolicySettled(
                txNeg.txId, txNeg.key, setOf("com.bad"), true, MsgKind.INFO, "bad", false, rev = -1L,
            )
            assertFalse(m.invoke(coord, negSentinel) as Boolean)
            assertEquals(posLatest, coord.latest.value)

            // Also verify emitStaged with synthetic rev behind an explicit positive latest does not overwrite.
            val highPos = PolicySettled(
                coord.nextTxId(), "phase4-pos-high",
                setOf("com.high"), true, MsgKind.INFO, "high", false, rev = 10L,
            )
            assertTrue(coord.tryEmitSettled(highPos))
            assertEquals(10L, coord.latest.value!!.rev)
            coord.emitStaged(
                PolicySettled(txNeg.txId, txNeg.key, setOf("com.bad"), true, MsgKind.INFO, "bad", false, rev = -1L),
            )
            assertEquals(highPos, coord.latest.value)
        } finally {
            coord.close()
        }
    }

    @Test
    fun stageRealPayload_superseded_doesNotStrandPendingPublish() = runBlocking {
        val coord = PolicyTxCoordinator()
        try {
            val txA = placeholderTx(coord, "phase4-superseded-A", completedOwner = true)
            stagePlaceholder(coord, txA)
            assertNotNull(coord.pendingPublish.get())
            assertSame(txA, coord.pendingOwner.get())

            // Advance _latest beyond txA's claim rev (1) with a newer txB (rev=2).
            val txB = placeholderTx(coord, "phase4-newer-B", completedOwner = true)
            val newerB = PolicySettled(
                txB.txId, txB.key, setOf("com.B"), true, MsgKind.INFO, "okB", false, rev = 2L,
            )
            assertTrue(coord.tryEmitSettled(newerB))
            assertEquals(2L, coord.latest.value?.rev)

            val supersededBefore = coord.emitSuperseded.get()
            val stagedA = coord.stageRealPayload(
                PolicySettled(txA.txId, txA.key, setOf("com.A"), true, MsgKind.INFO, "okA", false),
            )
            assertNull(stagedA)
            assertNull("superseded stageRealPayload must not strand pendingPublish", coord.pendingPublish.get())
            assertNull("superseded stageRealPayload must clear matching pendingOwner", coord.pendingOwner.get())
            assertEquals(txB.txId, coord.latest.value?.txId)
            assertEquals(2L, coord.latest.value?.rev)
            assertTrue(coord.emitSuperseded.get() > supersededBefore)
        } finally {
            coord.close()
        }
    }
}
