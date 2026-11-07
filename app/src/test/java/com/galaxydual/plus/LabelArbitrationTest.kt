// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase A (P2-1 stale-success)：label 補查完成仲裁鎖定（純 JVM，無 Activity/Looper）。
//
// 行覆寫引理（見 MainActivity.arbitrateLabelCompletion 檔頭）：B-epoch waiter 的
// 行 rowGen／tag／dialog 守衛對當代 waiter 合法通過——若 stale 成功仍把舊 pm label
// 派發給當代 waiter，會以舊資料覆寫 title／badge contentDescription／Switch
// contentDescription 且不排重查（確認缺陷：舊 cb(label) 直寫）。故唯有
// FANOUT_SAME_EPOCH（同 epoch 成功）可派發本次 pm label；stale 完成只允許
// DROP（丟棄，下次 render 重註冊）、SERVE_FROM_CACHE（派發 B-render 已預填的
// 當代快取，永非舊 label）、RELAUNCH_FRESH（重掛 fresh waiter＋重發一次 IO）。
// 下述 stale 斷言即鎖定「無任何臂派發 stale pmLabel」：stale 輸入永不回 FANOUT
//（FANOUT 為唯一派發本次 pm label 之臂；SERVE 派發的是快取當代值）。
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelArbitrationTest {

    // stale 成功＋有當代 waiter＋快取 miss→重發（舊 label 丟棄，不 cache／不回調）。
    @Test
    fun staleSuccess_withCurrentWaiter_andCacheMiss_relaunchesFresh() {
        assertEquals(
            LabelCompletionAction.RELAUNCH_FRESH,
            arbitrateLabelCompletion(
                launchEpoch = 7,
                currentEpoch = 8,
                waiterEpochs = listOf(7, 8),
                pmSuccess = true,
                freshCacheHit = false,
            ),
        )
    }

    // stale 成功＋有當代 waiter＋B-render 已預填快取→服務當代快取（永非舊 label）。
    @Test
    fun staleSuccess_withCurrentWaiter_andCacheHit_servesCache() {
        assertEquals(
            LabelCompletionAction.SERVE_FROM_CACHE,
            arbitrateLabelCompletion(
                launchEpoch = 7,
                currentEpoch = 8,
                waiterEpochs = listOf(8),
                pmSuccess = true,
                freshCacheHit = true,
            ),
        )
    }

    // stale 成功＋無當代 waiter→全丟（不觸 cache／rebind／回調／重發）。
    @Test
    fun staleSuccess_withNoCurrentWaiter_dropsAll() {
        assertEquals(
            LabelCompletionAction.DROP_ALL,
            arbitrateLabelCompletion(
                launchEpoch = 7,
                currentEpoch = 8,
                waiterEpochs = listOf(7),
                pmSuccess = true,
                freshCacheHit = false,
            ),
        )
    }

    // 同 epoch 成功→同舊語義（cache＋rebind＋全量 fanout）。
    @Test
    fun sameEpoch_success_fanouts() {
        assertEquals(
            LabelCompletionAction.FANOUT_SAME_EPOCH,
            arbitrateLabelCompletion(
                launchEpoch = 8,
                currentEpoch = 8,
                waiterEpochs = listOf(8),
                pmSuccess = true,
                freshCacheHit = true,
            ),
        )
    }

    // 同 epoch 失敗→沿舊語義全丟（cache 仍 miss，下次 render 重註冊）。
    @Test
    fun sameEpoch_failure_dropsAll() {
        assertEquals(
            LabelCompletionAction.DROP_ALL,
            arbitrateLabelCompletion(
                launchEpoch = 8,
                currentEpoch = 8,
                waiterEpochs = listOf(8),
                pmSuccess = false,
                freshCacheHit = false,
            ),
        )
    }

    // stale 失敗＋有當代 waiter＋快取 miss→重掛 fresh＋重發（舊失敗不靜默丟棄新世代）。
    @Test
    fun staleFailure_withCurrentWaiter_andCacheMiss_relaunchesFresh() {
        assertEquals(
            LabelCompletionAction.RELAUNCH_FRESH,
            arbitrateLabelCompletion(
                launchEpoch = 7,
                currentEpoch = 8,
                waiterEpochs = listOf(8),
                pmSuccess = false,
                freshCacheHit = false,
            ),
        )
    }

    // stale 失敗＋有當代 waiter＋B-render 已預填快取→服務當代快取（永非舊 label；
    // 與 stale 成功＋hit 同臂：freshCacheHit 即 SERVE，與 pmSuccess 無關）。
    @Test
    fun staleFailure_withCurrentWaiter_andCacheHit_servesCache() {
        assertEquals(
            LabelCompletionAction.SERVE_FROM_CACHE,
            arbitrateLabelCompletion(
                launchEpoch = 7,
                currentEpoch = 8,
                waiterEpochs = listOf(8),
                pmSuccess = false,
                freshCacheHit = true,
            ),
        )
    }

    // launch 缺失視為 stale（null ≠ 當代）：成功＋當代 waiter＋miss→重發而非 FANOUT。
    @Test
    fun nullLaunch_treatedAsStale() {
        assertEquals(
            LabelCompletionAction.RELAUNCH_FRESH,
            arbitrateLabelCompletion(
                launchEpoch = null,
                currentEpoch = 8,
                waiterEpochs = listOf(8),
                pmSuccess = true,
                freshCacheHit = false,
            ),
        )
    }

    // 核心不變式：launch != current 時永不回 FANOUT——FANOUT 為唯一派發本次
    // pm label 之臂；stale 路徑下任何 waiter/tag 守衛通過都不得拿到舊 label。
    @Test
    fun staleNever_fanoutsStalePmLabel() {
        val staleCases = listOf(
            Triple(listOf(8), true, false),
            Triple(listOf(8), true, true),
            Triple(listOf(7, 8), true, false),
            Triple(listOf(7, 8), true, true),
            Triple(listOf(7), true, false),
            Triple(listOf(7), true, true),
            Triple(emptyList<Int?>(), true, false),
            Triple(emptyList<Int?>(), true, true),
            Triple(listOf(8), false, false),
            Triple(listOf(8), false, true),
            Triple(listOf(7), false, false),
            Triple(listOf<Int?>(null), true, false),
        )
        for ((waiters, success, hit) in staleCases) {
            val action = arbitrateLabelCompletion(
                launchEpoch = 7,
                currentEpoch = 8,
                waiterEpochs = waiters,
                pmSuccess = success,
                freshCacheHit = hit,
            )
            org.junit.Assert.assertNotEquals(
                "stale completion must never FANOUT the old pm label (waiters=$waiters success=$success hit=$hit)",
                LabelCompletionAction.FANOUT_SAME_EPOCH,
                action,
            )
        }
        // null launch 同為 stale。
        org.junit.Assert.assertNotEquals(
            LabelCompletionAction.FANOUT_SAME_EPOCH,
            arbitrateLabelCompletion(null, 8, listOf(8), true, true),
        )
    }
}
