// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Follow-up A (PR #89): REAL controller behavior, not a source-shape check.
// The old Activities stored one mutable `pendingWarningRestart` callback slot
// written at every start*Warning ENTRY. A late settle for action A could then
// invoke action B's callback. These tests drive the framework-free controller
// directly with captured `post` lambdas and explicit delivery (no sleeps).
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningRestartControllerTest {

    private data class Posted(
        val tokenId: Long,
        val requestId: Long,
        val body: () -> Unit,
    )

    private fun capture(into: MutableList<Posted>): (Long, Long, () -> Unit) -> Unit =
        { tokenId, requestId, body -> into.add(Posted(tokenId, requestId, body)) }

    // Helpers model genuine entry taps: a fresh request gets a fresh budget.
    private fun installWrite(
        controller: WarningRestartController,
        key: String,
        generation: Int,
        hits: MutableList<String>,
    ): WarningRestartRequest = controller.install(
        action = WarningRestartAction.WRITE,
        candidateKey = key,
        generation = generation,
        callback = { hits.add("write:$key") },
        resetBudget = true,
    )

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

    // (a) The destructive misroute: WRITE warning queued, CLEAR accepted before
    // delivery. The old write token must not invoke the clear callback — and
    // crucially the old write callback must not run either (the settled retry
    // belonged to the write action, which is now superseded by an accepted
    // clear request). A fresh clear restart still works.
    @Test
    fun writeThenClear_newTapInvalidatesQueuedWriteToken() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        val write = installWrite(controller, "pkg.a,pkg.b", generation = 3, hits)
        assertEquals(WarningRestartAction.WRITE, write.identity.action)
        assertEquals("pkg.a,pkg.b", write.identity.candidateKey)
        assertEquals(3, write.identity.generation)
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(1, posted.size)

        val clear = installClear(controller, generation = 4, hits)
        assertEquals(WarningRestartAction.CLEAR, clear.identity.action)
        assertNull(clear.identity.candidateKey)
        assertTrue("monotonic request ids", clear.identity.id > write.identity.id)
        assertEquals(clear.identity.id, controller.retryRequestId())

        // Explicit delivery of the stale token via the captured body: no-op.
        posted[0].body()
        assertTrue("stale write token must not invoke any callback", hits.isEmpty())
        assertFalse(controller.deliver(posted[0].tokenId, posted[0].requestId, true))

        // A new restart for the live clear request works and routes to CLEAR.
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(2, posted.size)
        assertEquals(clear.identity.id, posted[1].requestId)
        posted[1].body()
        assertEquals(listOf("clear"), hits)
    }

    // (b) Reverse order: an accepted CLEAR is superseded by an accepted WRITE.
    @Test
    fun clearThenWrite_newTapInvalidatesQueuedClearToken() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        val clear = installClear(controller, generation = 1, hits)
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        val write = installWrite(controller, "pkg.x", generation = 2, hits)

        posted[0].body()
        assertTrue(hits.isEmpty())
        assertFalse(controller.deliver(posted[0].tokenId, posted[0].requestId, true))

        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        posted[1].body()
        assertEquals(listOf("write:pkg.x"), hits)
        assertEquals(write.identity.id, posted[1].requestId)
        assertNotEquals(clear.identity.id, write.identity.id)
    }

    // (c) A rejected entry must not install at all. The controller has no
    // guard: the Activities call install() ONLY after tryAcceptWarning succeeds.
    // Not calling install is exactly what keeps the old live request intact.
    @Test
    fun rejectedEntry_doesNotInstall_oldIdentityAndCallbackSurvive() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        val live = installWrite(controller, "pkg.keep", generation = 5, hits)
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))

        // Capture the id a Retry tap would bind to BEFORE simulating the
        // rejected entry. A mutable callback slot would have been overwritten
        // by that entry; an identity-bound controller cannot be.
        val rid = controller.retryRequestId()
        assertEquals(live.identity.id, rid)

        // Simulate `if (!tryAcceptWarning(...)) return` at start* entry: no
        // install call, no callback overwrite, no token bump.
        assertEquals(live.identity, controller.currentIdentity)
        assertEquals(live.identity.id, controller.retryRequestId())
        assertEquals(posted[0].tokenId, controller.scheduledTokenId)

        posted[0].body()
        assertEquals(listOf("write:pkg.keep"), hits)

        // The settled Retry still routes to the ORIGINAL WRITE callback — no
        // clear callback and no global/mutable retry slot — and is one-shot.
        assertTrue("original write request's retry must be consumable", controller.consumeRetry(rid!!, true))
        assertEquals(listOf("write:pkg.keep", "write:pkg.keep"), hits)
        assertFalse("retry is one-shot per request", controller.consumeRetry(rid, true))
        assertEquals(2, hits.size)
    }

    // (d) Editor candidate change is a new request identity; the old queued
    // restart is invalidated (the "sanitizedCandidate sorted key" contract).
    @Test
    fun editorCandidateChange_installsNewRequestAndInvalidatesOldToken() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()
        val installEditor = { key: String, callbackKey: String ->
            controller.install(
                action = WarningRestartAction.EDITOR_WRITE,
                candidateKey = key,
                generation = 7,
                callback = { hits.add("editor:$callbackKey") },
                resetBudget = true,
            )
        }

        val first = installEditor("a,b", "a,b")
        assertEquals(WarningRestartAction.EDITOR_WRITE, first.identity.action)
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        val second = installEditor("b,c", "b,c")
        assertEquals("b,c", second.identity.candidateKey)
        assertTrue(second.identity.id > first.identity.id)
        assertEquals(second.identity.id, controller.retryRequestId())

        posted[0].body()
        assertTrue(hits.isEmpty())
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        posted[1].body()
        assertEquals(listOf("editor:b,c"), hits)
    }

    // (e) Known / empty fast paths never install a request at all.
    @Test
    fun knownAndEmptyFastPath_neverInstalls() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        assertNull(controller.currentIdentity)
        assertNull(controller.retryRequestId())
        assertNull(controller.scheduledTokenId)
        assertFalse(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertFalse(controller.consumeRetry(1L, true))
        assertFalse(controller.deliver(1L, 1L, true))
        assertTrue(posted.isEmpty())
        assertTrue(hits.isEmpty())
    }

    // (f) onDestroy clear() drops the live request and invalidates the token.
    @Test
    fun destroyClear_preventsDeliveryAndScheduling() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        installClear(controller, generation = 2, hits)
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        val token = posted[0].tokenId
        val requestId = posted[0].requestId

        controller.clear()
        assertNull(controller.currentIdentity)
        assertNull(controller.retryRequestId())
        assertNull(controller.scheduledTokenId)

        posted[0].body() // the posted Runnable's body after onDestroy
        assertFalse(controller.deliver(token, requestId, true))
        assertFalse(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertTrue("no callback after destroy", hits.isEmpty())
        assertEquals(1, posted.size)
    }

    // (g) Budget is per accepted request (one auto restart), not global.
    @Test
    fun autoRestartBudget_perRequest_oneThenFreshOnNewTap() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        assertTrue("first auto restart allowed", canAutoRestart(0))
        assertFalse("one auto restart per request", canAutoRestart(1))
        assertEquals(1, MAX_AUTO_WARNING_RESTARTS)

        val first = installClear(controller, generation = 1, hits)
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertFalse("cap reached for this request", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(1, posted.size)

        // Genuine new entry tap -> accepted -> install resets the budget.
        val second = installClear(controller, generation = 2, hits)
        assertTrue(second.identity.id > first.identity.id)
        assertTrue("fresh request has fresh budget", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(2, posted.size)
        posted[1].body()
        assertEquals(1, hits.size)
    }

    // (g2) HIGH regression: the INSTALLED callback re-enters the warning
    // pipeline with fromUserTap=false for BOTH an auto-restart delivery and a
    // Retry settle. The re-install still makes a NEW identity (so old tokens
    // stay stale), but it MUST carry the spent budget. Otherwise a structural
    // fail-closed reason re-prompts forever: MAX_AUTO_WARNING_RESTARTS=1 is
    // defeated by the callback's own re-install.
    @Test
    fun autoRestartReinstall_carriesBudget_untilGenuineUserTap() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()

        val first = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.loop",
            generation = 1,
            callback = {
                hits.add("write:first")
                // What every Activity installs: the re-entry is NOT a tap.
                controller.install(
                    action = WarningRestartAction.WRITE,
                    candidateKey = "pkg.loop",
                    generation = 2,
                    callback = { hits.add("write:reentered") },
                    resetBudget = false,
                )
            },
            resetBudget = true,
        )

        assertTrue("first auto restart allowed", controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(1, posted.size)
        assertTrue(controller.deliver(posted[0].tokenId, posted[0].requestId, true))
        assertEquals(listOf("write:first"), hits)

        val reenteredId = controller.retryRequestId()!!
        assertNotEquals("re-entry installs a fresh identity", first.identity.id, reenteredId)

        // The re-installed request inherited the consumed budget, so a second
        // schedule for the SAME genuine tap must be rejected.
        assertFalse(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals("no second post", 1, posted.size)

        // A subsequent genuine user entry tap restores the budget.
        val fresh = controller.install(
            action = WarningRestartAction.WRITE,
            candidateKey = "pkg.loop",
            generation = 3,
            callback = { hits.add("write:fresh") },
            resetBudget = true,
        )
        assertTrue(fresh.identity.id > reenteredId)
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(2, posted.size)
        posted[1].body()
        assertEquals(listOf("write:first", "write:fresh"), hits)
    }

    // (h) Retry taps are identity-bound: a stale captured id is rejected after
    // another request was installed; a dying host is rejected; a live current
    // id is consumed exactly once and routes to the right callback.
    @Test
    fun retryIdentityBinding_staleRequestAndDeadHostRejected() {
        val controller = WarningRestartController()
        val hits = mutableListOf<String>()

        val write = installWrite(controller, "pkg.old", generation = 2, hits)
        val stale = controller.retryRequestId()
        assertEquals(write.identity.id, stale)

        // A different action is accepted before the settle arrives.
        installClear(controller, generation = 3, hits)
        assertFalse("stale retry id must not invoke the CLEAR callback", controller.consumeRetry(stale!!, true))
        assertTrue("clear callback untouched", hits.isEmpty())

        val live = controller.retryRequestId()!!
        assertFalse("dead host cannot consume retry", controller.consumeRetry(live, false))
        assertTrue(hits.isEmpty())
        assertTrue("live retry consumes and invokes CLEAR", controller.consumeRetry(live, true))
        assertEquals(listOf("clear"), hits)
        assertFalse("retry is one-shot per request", controller.consumeRetry(live, true))
        assertEquals(1, hits.size)
    }

    @Test
    fun scheduleDeadHost_neverPostsAndNeverConsumesBudget() {
        val controller = WarningRestartController()
        val posted = mutableListOf<Posted>()
        val hits = mutableListOf<String>()
        installClear(controller, generation = 1, hits)

        assertFalse(controller.schedule(false, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertTrue(posted.isEmpty())
        assertNull(controller.scheduledTokenId)

        // A dead-host schedule did not spend the budget.
        assertTrue(controller.schedule(true, MAX_AUTO_WARNING_RESTARTS, capture(posted)))
        assertEquals(1, posted.size)
        // Delivery with a dead host consumes the token but invokes nothing.
        assertFalse(controller.deliver(posted[0].tokenId, posted[0].requestId, false))
        assertTrue(hits.isEmpty())
        assertNull(controller.scheduledTokenId)
    }
}
