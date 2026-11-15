// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

// Reserved marker for slow/stress tests. NOTE: the RUN_LARGE_TESTS=1 opt-in
// exclusion is NOT wired up anywhere (unreferenced in gradle/CI/any test) —
// a future @LargeTest-annotated test would run in the default suite. The
// former N=1000 TOCTOU hammer was removed (vacuous: constant-revision
// sequential calls could not exercise the read-then-act interleaving); the
// annotation remains for future large tests, pending a wired-up exclusion.
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class LargeTest
