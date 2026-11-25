// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #89 Round-3 (R3-1 / R3-3): framework-free truth tables for the predicates
// that gate action ownership revocation and orphan-pending invalidation. No
// android.* dependency, no coroutines, no sleeps.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyWriteAdmissionPredicatesTest {

    @Test
    fun cancelOwnershipAccepted_truthTable() {
        assertTrue(cancelOwnershipAccepted(vmCancelled = true, actionLive = true))
        assertFalse(cancelOwnershipAccepted(vmCancelled = true, actionLive = false))
        assertFalse(cancelOwnershipAccepted(vmCancelled = false, actionLive = true))
        assertFalse(cancelOwnershipAccepted(vmCancelled = false, actionLive = false))
    }

    @Test
    fun confirmRevocationAccepted_truthTable() {
        // Only a RETRYABLE_RETAINED refusal keeps the accepted action live;
        // a dispatched write or a terminal no-write path both revoke it.
        assertTrue(confirmRevocationAccepted(PolicyWriteConfirmAdmission.ACCEPTED))
        assertFalse(confirmRevocationAccepted(PolicyWriteConfirmAdmission.RETRYABLE_RETAINED))
        assertTrue(confirmRevocationAccepted(PolicyWriteConfirmAdmission.TERMINAL))
        assertEquals(3, PolicyWriteConfirmAdmission.values().size)
    }

    @Test
    fun shouldInvalidateOrphanPendingOnRebuild_truthTable() {
        var accepted = 0
        for (rebuilt in listOf(false, true)) {
            for (phase in PolicyWritePhase.values()) {
                for (live in listOf(false, true)) {
                    val expected = rebuilt && phase == PolicyWritePhase.QUEUED && !live
                    assertEquals(
                        "rebuilt=$rebuilt phase=$phase live=$live",
                        expected,
                        shouldInvalidateOrphanPendingOnRebuild(rebuilt, phase, live),
                    )
                    if (expected) accepted++
                }
            }
        }
        // Exactly one cell of the 2×4×2 table: rebuilt + QUEUED + no live action.
        assertEquals(1, accepted)
        assertTrue(shouldInvalidateOrphanPendingOnRebuild(true, PolicyWritePhase.QUEUED, false))
        assertFalse(shouldInvalidateOrphanPendingOnRebuild(true, PolicyWritePhase.SAVING, false))
        assertFalse(shouldInvalidateOrphanPendingOnRebuild(true, PolicyWritePhase.RECONCILING, false))
        assertFalse(shouldInvalidateOrphanPendingOnRebuild(true, PolicyWritePhase.QUEUED, true))
        assertFalse(shouldInvalidateOrphanPendingOnRebuild(false, PolicyWritePhase.QUEUED, false))
    }
}
