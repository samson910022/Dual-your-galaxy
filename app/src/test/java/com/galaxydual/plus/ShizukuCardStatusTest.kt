// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase A (P0 correctness): JVM unit tests for the Shizuku fallback-card pure
// mapping -- the same (noBinder, authorized) booleans that drive
// updateShizukuLine() map to the card tri-state, so the card can never claim
// "authorized" on its own reading. Binder-dead wins over authorized (a dead
// binder makes any permission bit meaningless).
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuCardStatusTest {
    @Test fun noBinderWinsOverAuthorized() {
        assertEquals(ShizukuCardStatus.NO_BINDER, resolveShizukuCardStatus(true, true))
        assertEquals(ShizukuCardStatus.NO_BINDER, resolveShizukuCardStatus(true, false))
    }

    @Test fun binderAliveMapsAuthorization() {
        assertEquals(ShizukuCardStatus.AUTHORIZED, resolveShizukuCardStatus(false, true))
        assertEquals(ShizukuCardStatus.UNAUTHORIZED, resolveShizukuCardStatus(false, false))
    }
}
