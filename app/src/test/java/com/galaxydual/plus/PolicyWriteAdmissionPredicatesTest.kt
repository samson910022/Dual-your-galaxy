// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #89 Round-3 (R3-1 / R3-3): framework-free truth tables for the predicates
// that gate action ownership revocation and orphan-pending invalidation. No
// android.* dependency, no coroutines, no sleeps.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        // Phase 1: this covers SYNCHRONOUS verdicts only. The async outcome gate
        // (confirmAttemptRetiresAction) is wired in a later phase, so ACCEPTED
        // still retires here and only RETRYABLE_RETAINED keeps the action live.
        assertTrue(confirmRevocationAccepted(PolicyWriteConfirmAdmission.ACCEPTED))
        assertFalse(confirmRevocationAccepted(PolicyWriteConfirmAdmission.RETRYABLE_RETAINED))
        assertTrue(confirmRevocationAccepted(PolicyWriteConfirmAdmission.TERMINAL))
        assertEquals(3, PolicyWriteConfirmAdmission.values().size)
    }

    @Test
    fun confirmAttemptRetiresAction_truthTable() {
        val base = PolicyWriteConfirmAttemptOutcome.CONSUMED
        // Happy path: every conjunct true + a retiring outcome.
        assertTrue(
            confirmAttemptRetiresAction(
                outcome = base,
                callbackGeneration = 7L,
                expectedGeneration = 7L,
                attemptActive = true,
                actionLive = true,
                hostAlive = true,
            ),
        )
        assertTrue(
            confirmAttemptRetiresAction(
                outcome = PolicyWriteConfirmAttemptOutcome.HANDED_OFF,
                callbackGeneration = 7L,
                expectedGeneration = 7L,
                attemptActive = true,
                actionLive = true,
                hostAlive = true,
            ),
        )
        // Non-retiring outcomes never retire, even with every guard true.
        for (outcome in listOf(
            PolicyWriteConfirmAttemptOutcome.RETRYABLE_RETAINED,
            PolicyWriteConfirmAttemptOutcome.SUPERSEDED,
        )) {
            assertFalse("$outcome must never retire", confirmAttemptRetiresAction(outcome, 7L, 7L, true, true, true))
        }
        assertFalse(confirmAttemptMayRetire(PolicyWriteConfirmAttemptOutcome.RETRYABLE_RETAINED))
        assertFalse(confirmAttemptMayRetire(PolicyWriteConfirmAttemptOutcome.SUPERSEDED))
        assertTrue(confirmAttemptMayRetire(base))
        assertTrue(confirmAttemptMayRetire(PolicyWriteConfirmAttemptOutcome.HANDED_OFF))
        assertEquals(4, PolicyWriteConfirmAttemptOutcome.values().size)

        // Each dropped conjunct is individually fatal: gen mismatch, stale
        // token, dead action, dead host. Killing any of these clauses is a
        // mutation that this table must catch.
        assertFalse(
            "generation mismatch must not retire",
            confirmAttemptRetiresAction(base, 8L, 7L, true, true, true),
        )
        assertFalse(
            "inactive/stale attempt must not retire",
            confirmAttemptRetiresAction(base, 7L, 7L, false, true, true),
        )
        assertFalse(
            "dead action must not retire",
            confirmAttemptRetiresAction(base, 7L, 7L, true, false, true),
        )
        assertFalse(
            "dead host must not retire",
            confirmAttemptRetiresAction(base, 7L, 7L, true, true, false),
        )

        // Phase-1 hardening cells: a same-generation stale attempt token is
        // fatal even for CONSUMED, and HANDED_OFF mirrors the CONSUMED dead-host
        // cell above (host liveness is re-read at settlement for BOTH outcomes).
        assertFalse(
            "CONSUMED with a same-generation stale token must not retire",
            confirmAttemptRetiresAction(
                outcome = PolicyWriteConfirmAttemptOutcome.CONSUMED,
                callbackGeneration = 7L,
                expectedGeneration = 7L,
                attemptActive = false,
                actionLive = true,
                hostAlive = true,
            ),
        )
        assertFalse(
            "HANDED_OFF with a dead host must not retire (symmetry with CONSUMED)",
            confirmAttemptRetiresAction(
                outcome = PolicyWriteConfirmAttemptOutcome.HANDED_OFF,
                callbackGeneration = 7L,
                expectedGeneration = 7L,
                attemptActive = true,
                actionLive = true,
                hostAlive = false,
            ),
        )
    }

    @Test
    fun policyWriteAttemptTracker_isTokenizedAndStaleSafe() {
        val tracker = PolicyWriteAttemptTracker()
        val g = 42L

        // The tracker GENERATES the ids: a caller cannot choose or forge a
        // token, and each fresh attempt on one generation gets a new id.
        val token1 = checkNotNull(tracker.tryEnter(g))
        assertNull("duplicate attempt for this generation must be refused", tracker.tryEnter(g))
        assertTrue(tracker.isActive(g, token1))
        val forged = token1 + 100L
        assertFalse("forged token must not count as active", tracker.isActive(g, forged))

        // A forged token cannot release the live attempt: only the ATOMIC
        // exit(generation, attemptId) consume is allowed to retire it.
        assertFalse("forged exit must not release attempt 1", tracker.exit(g, forged))
        assertTrue("attempt 1 still active after forged exit", tracker.isActive(g, token1))

        // The real token releases; a fresh attempt may now enter with a NEW id.
        assertTrue("the live token releases its attempt", tracker.exit(g, token1))
        assertFalse(tracker.isActive(g, token1))
        val token2 = checkNotNull(tracker.tryEnter(g))
        assertNotEquals("id must be freshly generated, not reused", token1, token2)
        assertFalse("stale token must not be active", tracker.isActive(g, token1))
        assertTrue("fresh token is active", tracker.isActive(g, token2))
        assertFalse("stale token cannot release the newer attempt", tracker.exit(g, token1))
        assertTrue("attempt 2 survives the stale exit", tracker.isActive(g, token2))

        // Unrelated generations never block one another (no global boolean).
        val otherToken = checkNotNull(tracker.tryEnter(g + 1L))
        assertNotEquals("distinct generations get distinct tokens", token2, otherToken)
        assertTrue(tracker.isAnyActive(g))
        assertTrue(tracker.isAnyActive(g + 1L))

        // Lifecycle-only escape hatch (Activity onDestroy): clear releases all
        // and every late callback via a stale token is a no-op.
        tracker.clear()
        assertFalse(tracker.isAnyActive(g))
        assertFalse(tracker.isAnyActive(g + 1L))
        assertFalse(tracker.isActive(g, token2))
        assertFalse(tracker.isActive(g + 1L, otherToken))
        assertFalse("late consume after clear is a no-op", tracker.exit(g, token2))
        assertTrue("attempts can enter again after clear", tracker.tryEnter(g) != null)
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
