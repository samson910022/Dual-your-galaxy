// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

// JVM-pinnable predicate for the "稍后刷新" (snooze) neutral button in the
// policy-write dialog: enabled ONLY while the write fence is RECONCILING
// (the user-triggerable late-join recovery entry). SAVING / QUEUED / IDLE
// (and every other phase, present or future) keep it disabled — the button
// must never admit a confirm while a write is in flight, nor a no-op tap on
// an already-settled fence. Extracted so a plain JVM unit test can pin the
// truth table without an Activity.
internal fun policyWriteSnoozeEnabled(phase: PolicyWritePhase): Boolean =
    phase == PolicyWritePhase.RECONCILING
