// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1):owner-user 門控的 uid -> userId 拆解純函數
// JVM 測。UserHandle.PER_USER_RANGE 為 @hide,公開 SDK 僅能以 100_000 字面
// 計算 (見 MainActivity.userIdFromUid);此測試鎖住 AOSP 公式邊界。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class UserIdFromUidTest {
    @Test
    fun mapsUidToUserIdAcrossRangeBoundary() {
        assertEquals(0, userIdFromUid(10123))
        assertEquals(0, userIdFromUid(99999))
        assertEquals(1, userIdFromUid(100000))
        assertEquals(10, userIdFromUid(1010123))
    }

    // 極端 uid:0 仍為 user 0;Int.MAX_VALUE / 100_000（Kotlin 整數除法向零
    // 截斷）= 21474。
    @Test
    fun mapsExtremeUids() {
        assertEquals(0, userIdFromUid(0))
        assertEquals(21474, userIdFromUid(Int.MAX_VALUE))
        // 上半部邊界：Int.MAX_VALUE / 2 拆出 10737。
        assertEquals(10737, userIdFromUid(Int.MAX_VALUE / 2))
    }

    // Phase 4:負值守衛 (<0 回 -1):舊碼向零截斷會把 -1 誤判為 user 0 (owner)
    // 而 fail-open,現顯式拒絕。
    @Test
    fun negativeUidReturnsMinusOne() {
        assertEquals(-1, userIdFromUid(-1))
        assertEquals(-1, userIdFromUid(-100000))
        assertEquals(-1, userIdFromUid(Int.MIN_VALUE))
    }
}
