// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #77 (H02) RED contract: the per-warning retry generation must advance ONLY
// when a new warning is actually ACCEPTED by the re-entry guard. A stale
// auto-restart whose request is rejected must not burn the generation — doing
// so invalidates the live Retry tap's captured token and silently drops the
// user's legitimate re-entry. Framework-free (pure JVM), no Robolectric.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningRetryAcceptTest {

    @Test
    fun rejectedAutoRestart_doesNotAdvanceGeneration_liveRetryTokenStillConsumable() {
        val oneShot = WarningRetryOneShot()

        // A genuine entry tap is accepted => warning #1 installed, token armed.
        assertTrue("accepted new warning must re-arm", tryAcceptWarning(oneShot, newWarningAllowed = true))
        val live = oneShot.capture()
        assertEquals(1, live.generation)
        assertFalse(live.consumed)

        // A stale auto-restart is rejected by consentResetAllowedForNewWarning.
        // It must NOT touch the generation nor the armed token.
        assertFalse("rejected restart must not arm", tryAcceptWarning(oneShot, newWarningAllowed = false))
        assertEquals("rejected restart must not burn generation", 1, oneShot.currentGeneration)
        assertEquals("live token must be untouched", live, oneShot.capture())

        // The Retry tap that actually belongs to warning #1 still wins.
        assertTrue("live Retry settle must still match", oneShot.tryConsume(live))
        assertFalse("duplicate settle still dropped", oneShot.tryConsume(live))
        assertEquals(1, oneShot.currentGeneration)
    }

    @Test
    fun acceptedWarning_advancesGenerationAndRearms_afterConsumedToken() {
        val oneShot = WarningRetryOneShot()
        assertTrue(tryAcceptWarning(oneShot, newWarningAllowed = true))
        val first = oneShot.capture()
        assertTrue(oneShot.tryConsume(first))

        // A rejected request in between changes nothing.
        assertFalse(tryAcceptWarning(oneShot, newWarningAllowed = false))
        assertEquals(1, oneShot.currentGeneration)

        // The next genuine warning is accepted: new generation, fresh token.
        assertTrue(tryAcceptWarning(oneShot, newWarningAllowed = true))
        val second = oneShot.capture()
        assertEquals(2, second.generation)
        assertFalse(second.consumed)
        assertTrue("new token must differ from consumed one", second != first)
        assertFalse("stale settle must stay ignored", oneShot.tryConsume(first))
        assertTrue("second warning's Retry must work", oneShot.tryConsume(second))
        assertEquals(2, oneShot.currentGeneration)
    }
}
