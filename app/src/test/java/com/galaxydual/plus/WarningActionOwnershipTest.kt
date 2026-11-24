// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #89 Round-2 P1 (G1): accepted-action OWNERSHIP, not just INSTALLED warnings.
//
// The controller's old model covered only warnings that were actually shown.
// Known / no-op fast paths install nothing, so a CANCELLED CLEAR request can
// stay live; a later WRITE abort calls schedule() which reads the CURRENT
// request (CLEAR) and can deliver the cancelled CLEAR callback — a destructive
// clear during a write. The fix has three parts, each pinned by a test here:
//   1. install() bumps tokenId so an old posted token can never deliver again,
//      even when the superseding action took a no-warning fast path.
//   2. revoke() is IDENTITY-conditional: a stale cancel never drops a newer
//      accepted action, and it never resets the auto-restart budget.
//   3. schedule(expectedRequestId = ...) binds an abort to the request that
//      actually failed, so a stale abort cannot schedule a new restart.
//
// Framework-free: captured `post` lambdas + explicit delivery, no sleeps, no
// threads, no android.* import.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningActionOwnershipTest {

    private data class Posted(
        val tokenId: Long,
        val requestId: Long,
        val body: () -> Unit,
    )

    private fun capture(into: MutableList<Posted>): (Long, Long, () -> Unit) -> Unit =
        { tokenId, requestId, body -> into.add(Posted(tokenId, requestId, body)) }

    // The regression itself: CLEAR accepted with a posted restart, then CANCELLED
    // via conditional revoke, then a WRITE accepted through a NO-WARNING fast
    // path (no tryAcceptWarning). The cancelled CLEAR token must be dead and a
    // fresh schedule must be WRITE-bound. The fast-path install must not bump
    // the WarningRetryOneShot generation (it is correlation-only).
    @Test
    fun clearRequest_cancelledThenWriteFastPathInstall_oldClearTokenCannotDeliver() {
        val controller = WarningRestartController()
        val oneShot = WarningRetryOneShot()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()
        // Effect ledger: controller-level proof that the stale CLEAR token
        // runs ZERO clear VM work and the fresh WRITE token runs exactly one.
        var clearVmCalls = 0
        var writeVmCalls = 0

        val clear = controller.install(
            action = WarningRestartAction.CLEAR,
            candidateKey = null,
            generation = oneShot.currentGeneration,
            callback = { clearVmCalls++; hits.add("clear") },
            resetBudget = true,
        )
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(1, posted.size)
        assertEquals(clear.identity.id, posted[0].requestId)

        // Cancel the CLEAR request CONDITIONALLY — a newer action must survive.
        assertTrue("live CLEAR must be revocable by its own id", controller.revoke(clear.identity.id))
        assertNull(controller.currentRequestId())

        // WRITE accepted via Known/no-op fast path: NO tryAcceptWarning call.
        // generation is passed only for correlation; oneShot must not move.
        val genBeforeFastPath = oneShot.currentGeneration
        val write = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.fast",
            generation = oneShot.currentGeneration,
            callback = { writeVmCalls++; hits.add("write:pkg.fast") },
            resetBudget = true,
        )
        assertEquals(
            "fast-path install must NOT touch WarningRetryOneShot",
            genBeforeFastPath,
            oneShot.currentGeneration,
        )
        assertEquals(genBeforeFastPath, write.identity.generation)

        // Explicitly deliver the old CLEAR token from the captured body, then
        // via deliver(): neither may invoke ANY callback (no destructive clear).
        posted[0].body()
        assertFalse(controller.deliver(posted[0].tokenId, posted[0].requestId, true))
        assertTrue("no destructive clear from a cancelled request", hits.isEmpty())
        assertEquals("stale CLEAR token must run ZERO clear VM effects", 0, clearVmCalls)
        assertEquals("stale CLEAR token must not run WRITE effects either", 0, writeVmCalls)

        // Current identity is WRITE and a fresh schedule is WRITE-bound.
        assertEquals(WarningRestartAction.WRITE, controller.currentIdentity?.action)
        assertEquals(write.identity.id, controller.currentIdentity?.id)
        assertTrue(controller.isLive(write.identity.id))
        assertFalse(controller.isLive(clear.identity.id))

        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(2, posted.size)
        assertEquals(write.identity.id, posted[1].requestId)
        assertNotEquals(posted[0].tokenId, posted[1].tokenId)
        // install() is a token-freshness seam too: revoke bumped once, the
        // fast-path install must bump once more, then schedule bumps again.
        // Dropping install's bump makes this reuse a token id (+2, not +3).
        assertEquals(posted[0].tokenId + 3, posted[1].tokenId)
        posted[1].body()
        assertEquals(listOf("write:pkg.fast"), hits)
        assertEquals("still ZERO clear VM effects after the WRITE settles", 0, clearVmCalls)
        assertEquals("fresh WRITE token runs exactly one WRITE VM effect", 1, writeVmCalls)
    }

    // revoke() must be identity-conditional: stale/null ids change NOTHING,
    // and a successful revoke invalidates only that request's scheduled token.
    @Test
    fun revokeIsIdentityConditional() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        val old = controller.install(
            action = WarningRestartAction.CLEAR,
            candidateKey = null,
            generation = 1,
            callback = { hits.add("clear") },
            resetBudget = true,
        )
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))

        // Newer accepted WRITE supersedes the old CLEAR (and its token).
        val live = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.live",
            generation = 2,
            callback = { hits.add("write:pkg.live") },
            resetBudget = true,
        )
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        val liveToken = posted[1].tokenId

        // Stale id: false, ZERO state mutation — the newer live request survives.
        assertFalse(controller.revoke(old.identity.id))
        assertEquals(live.identity.id, controller.currentRequestId())
        assertEquals(liveToken, controller.scheduledTokenId)
        assertTrue(controller.isLive(live.identity.id))

        // Null id: likewise a no-op.
        assertFalse(controller.revoke(null))
        assertEquals(live.identity.id, controller.currentRequestId())
        assertEquals(liveToken, controller.scheduledTokenId)

        // Current id: conditional revoke clears that request and its token only.
        assertTrue(controller.revoke(live.identity.id))
        assertNull(controller.currentIdentity)
        assertNull(controller.retryRequestId())
        assertNull(controller.scheduledTokenId)
        assertFalse(controller.deliver(liveToken, live.identity.id, true))
        assertFalse(controller.isLive(live.identity.id))

        // Posted body is a no-op too; no callback ran at any point.
        posted[1].body()
        assertTrue("no callback after conditional revoke", hits.isEmpty())

        // Re-revoking an already-revoked id is false (no live request).
        assertFalse(controller.revoke(live.identity.id))
    }

    // A cancel is NOT a genuine entry tap: revoke(A) must retain the budget A
    // already consumed. A carried-over re-install (resetBudget=false) still
    // hits the cap; only a later real tap (resetBudget=true) re-arms it.
    // This fails outright if revoke() resets autoRestartsUsed.
    @Test
    fun revokeRetainsAutoRestartBudget() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val budget = 1

        assertFalse("null is never a live request", controller.isLive(null))

        // A (genuine tap): spend the single allowed auto restart.
        val a = controller.install(
            action = WarningRestartAction.CLEAR,
            candidateKey = null,
            generation = 1,
            callback = {},
            resetBudget = true,
        )
        assertTrue(controller.isLive(a.identity.id))
        assertTrue(controller.schedule(true, budget, capture(posted)))
        assertEquals(1, posted.size)
        assertFalse("cap reached for A", controller.schedule(true, budget, capture(posted)))
        assertEquals("still one post at the cap", 1, posted.size)

        // Cancelling A must not hand the next action a fresh budget.
        assertTrue(controller.revoke(a.identity.id))
        assertFalse("revoked id is not live", controller.isLive(a.identity.id))
        assertFalse("null is still not live", controller.isLive(null))
        assertNull(controller.currentIdentity)

        // B (re-entry via auto restart / Retry settle): carries the budget.
        val b = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.carry",
            generation = 1,
            callback = {},
            resetBudget = false,
        )
        assertTrue(controller.isLive(b.identity.id))
        assertFalse("revoke must retain the consumed budget", controller.schedule(true, budget, capture(posted)))
        assertEquals("no post from an exhausted carried budget", 1, posted.size)

        // C (genuine tap): re-arm and schedule succeeds.
        val c = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.fresh",
            generation = 1,
            callback = {},
            resetBudget = true,
        )
        assertTrue(controller.isLive(c.identity.id))
        assertTrue("fresh tap must re-arm the budget", controller.schedule(true, budget, capture(posted)))
        assertEquals(2, posted.size)
        assertEquals(c.identity.id, posted[1].requestId)
    }

    // A stale abort (from the cancelled CLEAR) must not schedule a new restart
    // for the live WRITE, must not post twice, and must not change the original
    // scheduled token/request. A current-id abort with budget available works.
    @Test
    fun staleAbortCannotScheduleNewRequest() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()
        // Headroom so the stale attempt would actually post if the guard were
        // missing; a budget cap must not be what saves this test.
        val budget = 2

        installClear(controller, generation = 1, hits)
        val staleClearId = controller.retryRequestId()
        assertNotEquals(null, staleClearId)

        val live = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.abort",
            generation = 2,
            callback = { hits.add("write:pkg.abort") },
            resetBudget = true,
        )
        assertTrue(controller.schedule(true, budget, capture(posted)))
        assertEquals(1, posted.size)
        val originalToken = posted[0].tokenId
        assertEquals(originalToken, controller.scheduledTokenId)

        // Stale CLEAR abort: identity mismatch -> false, no post, no state change.
        assertFalse(
            controller.schedule(true, budget, capture(posted), expectedRequestId = staleClearId),
        )
        assertEquals("no second post from a stale abort", 1, posted.size)
        assertEquals(originalToken, controller.scheduledTokenId)

        // The original scheduled token still delivers to WRITE — proof that the
        // stale attempt left the scheduled REQUEST unchanged too.
        posted[0].body()
        assertEquals(listOf("write:pkg.abort"), hits)

        // A current-id abort with a fresh budget still posts, WRITE-bound.
        assertTrue(
            controller.schedule(true, budget, capture(posted), expectedRequestId = live.identity.id),
        )
        assertEquals(2, posted.size)
        assertEquals(live.identity.id, posted[1].requestId)
        assertNotEquals(originalToken, posted[1].tokenId)
    }

    // Provenance of resetBudget on the no-warning fast path: a genuine tap
    // resets it, a re-entry re-install carries it, a genuine tap resets again.
    @Test
    fun fastPathInstallBudget_provenance() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        // Genuine entry tap that took a no-warning fast path (no tryAcceptWarning).
        controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.fast1",
            generation = 0,
            callback = { hits.add("write:fast1") },
            resetBudget = true,
        )
        assertTrue("genuine tap gets a fresh budget", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertFalse("one auto restart per accepted action", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(1, posted.size)

        // Re-entry (auto restart / Retry settle) re-installs with false: carry.
        controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.fast1",
            generation = 0,
            callback = { hits.add("write:fast1:reentry") },
            resetBudget = false,
        )
        assertFalse("budget carried through re-install", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals("still no second post", 1, posted.size)

        // A subsequent genuine entry tap resets the budget again.
        controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.fast2",
            generation = 0,
            callback = { hits.add("write:fast2") },
            resetBudget = true,
        )
        assertTrue("fresh tap re-arms the budget", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(2, posted.size)
    }

    // Retry identity after a fast-path re-install: the CLEAR capture is stale
    // and must never invoke the WRITE callback; the fresh WRITE id is one-shot.
    @Test
    fun staleRetryAfterFastPath() {
        val controller = WarningRestartController()
        val hits = mutableListOf<String>()

        controller.install(
            action = WarningRestartAction.CLEAR,
            candidateKey = null,
            generation = 1,
            callback = { hits.add("clear") },
            resetBudget = true,
        )
        val staleClearRetryId = controller.retryRequestId()!!
        assertEquals(controller.currentRequestId(), staleClearRetryId)

        // WRITE accepted with NO warning (no tryAcceptWarning) — correlation gen only.
        val write = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.fastretry",
            generation = 1,
            callback = { hits.add("write:pkg.fastretry") },
            resetBudget = true,
        )

        assertFalse("stale CLEAR retry must not invoke WRITE callback", controller.consumeRetry(staleClearRetryId, true))
        assertTrue("no callback from stale retry", hits.isEmpty())

        val newRetryId = controller.retryRequestId()!!
        assertEquals(write.identity.id, newRetryId)
        assertTrue(controller.consumeRetry(newRetryId, true))
        assertEquals(listOf("write:pkg.fastretry"), hits)
        assertFalse("retry is one-shot per request", controller.consumeRetry(newRetryId, true))
        assertEquals(1, hits.size)
    }

    private fun installClear(
        controller: WarningRestartController,
        generation: Int,
        hits: MutableList<String>,
    ): WarningRestartRequest = controller.install(
        action = WarningRestartAction.CLEAR,
        candidateKey = null,
        generation = generation,
        callback = { hits.add("clear") },
        resetBudget = true,
    )
}
