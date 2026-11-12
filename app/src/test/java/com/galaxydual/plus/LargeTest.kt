// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

// Marker for slow/stress tests excluded from default CI (opt-in via
// RUN_LARGE_TESTS=1). Phase7: the N=1000 TOCTOU hammer is marked here so the
// default suite stays fast; CI runs it only when explicitly requested.
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class LargeTest
