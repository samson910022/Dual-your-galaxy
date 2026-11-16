// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase3 JVM-pinnable truth table for the policy-write dialog's neutral
// (稍后刷新 / snooze) button gate. The button is the user-triggerable
// late-join recovery entry and must be enabled ONLY while the write fence is
// RECONCILING; every other phase keeps it disabled.
package com.galaxydual.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyWriteSnoozeGateTest {

    @Test
    fun reconcilingEnablesSnooze() {
        assertTrue(policyWriteSnoozeEnabled(PolicyWritePhase.RECONCILING))
    }

    @Test
    fun savingDisablesSnooze() {
        assertFalse(policyWriteSnoozeEnabled(PolicyWritePhase.SAVING))
    }

    @Test
    fun idleAndQueuedDisableSnooze() {
        assertFalse(policyWriteSnoozeEnabled(PolicyWritePhase.IDLE))
        assertFalse(policyWriteSnoozeEnabled(PolicyWritePhase.QUEUED))
    }
}
