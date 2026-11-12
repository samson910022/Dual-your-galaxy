// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage G2 (P2#4#5):跨 VM 最小 coordinator。App-scoped（Application 或
// Activity-retained holder），activeTx 為唯一跨 VM 真相；VM onCleared 不清除，
// 新 VM init 認領（繼承 fence+RECONCILING+pending+Unknown + 啟動 reconciler join
// 同一 deferred）。
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// Stage H2 (P2#3#4)：敗者同步 settled 終態廣播。
// tryClaim 勝者在 settle 前 tryEmit 終態；VM init/認領後訂閱，收到
// txId==本地即應用 IDLE+draft/Known/msg。replay=1 使晚訂閱仍可見最後終態。
// 終態攜 msg 字面（勝者當下 _state 快照），敗者直接應用不經 policyText 二次解析
//（同行程同語系，字面一致；免 textKind 映射漂移）。
// Stage I1 (P1#1+P2#2)：終態攜 key（Tx 全域身份 txId+key 雙元；awaitSettled 精確
// 比對，免跨 VM 計數器碰撞導致的 stale 誤同步）。
data class PolicySettled(
    val txId: Long,
    val key: String,
    val draft: Set<String>,
    val known: Boolean,
    val msgKind: MsgKind,
    val msg: String,
    val isClear: Boolean,
)

class PolicyTxCoordinator {
    // 跨 VM 唯一真相：仍在途的逾時交易（null=無在途交易）。
    // VM handoverTx 登記時同步寫入本地 activePolicyTx＋本欄；終態發布前先經
    // tryClaim（見 DualViewModel.tryClaimTxForPublish）做 settle-CAS 先行、
    // 勝者才發布；onCleared/dispose 刻意不清除，新 VM init 認領。
    // Stage H1：所有權前置——confirm/clear 在 opMutex 內、store IO 前同步 CAS
    // claim（LAZY async，失敗即 BUSY/IN_PROGRESS 不啟 IO），使 pre-timeout
    // SAVING 即在 coordinator 可見，新 VM 可認領（RECONCILING＋Unknown＋
    // pending＋世代bump＋reconciler join 同一 deferred）。
    val activeTx = AtomicReference<DualViewModel.PolicyTx?>(null)

    // Stage I1 (P1#1)：Tx 全域身份——App-scoped 單調計數器，跨 VM 唯一。
    // VM 本地 policyTxId 隨 VM 重建歸零（A→重建→B 皆取 1，碰撞致 settled 誤配），
    // 故 coordinator 側統一發號；DualViewModel 四處建 Tx 皆經 nextTxId()（無
    // coordinator 舊測試路徑才回退本地計數器）。
    private val globalTxId = AtomicLong(0L)

    fun nextTxId(): Long = globalTxId.incrementAndGet()

    // Stage H1：App-scoped 單寫者鎖（fail-fast 經 tryLock，不排隊）。
    // - IO 在途（SAVING）期間持有，併發第二寫經 tryLock 失敗即 BUSY/
    //   IN_PROGRESS，不啟 IO（與 CAS 雙層：writeMutex 擋在途 IO，activeTx CAS
    //   擋逾時後 fence；逾時即釋放 writeMutex 但保留 activeTx，故第二寫仍經
    //   CAS 被擋；結清（settle）後雙清，後續可新寫）。
    // - finally 只解鎖（best effort），不清除 activeTx（僅 settle 清；見
    //   DualViewModel confirm/clear「finally 不碰 coordinator」）。
    val writeMutex = Mutex()

    // Stage L1 P1: commit revision + staged publish (CAS visible before event).
    // - committedRevision: completed-publish monotonic revision (bumped atomically
    //   with activeTx(tx->null) inside commitMutex). Pre-IO gate compares
    //   pending.baseCoordinatorRevision against this (authoritative, not VM-local
    //   baseDraftVersion alone), so a CAS that has not yet emitted still blocks
    //   a fresh C (pendingPublish!=null-or-self or revision mismatch).
    // - pendingPublish: staged but not yet emitted payload (set under commitMutex,
    //   cleared after emit leaves replayCache). Gate drains union
    //   replayCache + pendingPublish, so collector lag cannot hide B.
    // - Lock order: opMutex->policyRevisionMutex->commitMutex->writeMutex leaf;
    //   never emit while holding mutex (stage under lock, emit after release).
    val commitMutex = Mutex()
    val committedRevision = AtomicLong(0L)
    val pendingPublish = AtomicReference<PolicySettled?>(null)

    fun currentRevision(): Long = try {
        committedRevision.get()
    } catch (_: Exception) {
        0L
    }

    suspend fun <T> withCommitLock(block: suspend () -> T): T = commitMutex.withLock { block() }

    // Stage L1 P1: atomic complete (CAS + revision bump + stage) under commitMutex.
    // Returns true=winner (staged, caller must invoke afterClaimBeforeEmit hook
    // then emit after unlock); false=loser (no stage, no bump).
    suspend fun completeWithRevision(tx: DualViewModel.PolicyTx, payload: PolicySettled): Boolean {
        var ok = false
        commitMutex.withLock {
            try {
                if (activeTx.compareAndSet(tx, null)) {
                    committedRevision.incrementAndGet()
                    pendingPublish.set(payload)
                    ok = true
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    // Stage L1 P1: atomic pre-IO claim (Busy/Stale/claimed) under commitMutex.
    // - Busy: activeTx!=null (in-flight or staged fence).
    // - Stale: committedRevision!=expectedBaseRev (unseen B).
    // - expectedBaseRev==-1L (legacy injection) skips revision check.
    enum class ClaimWriteResult { CLAIMED, BUSY, STALE }

    suspend fun tryClaimWrite(tx: DualViewModel.PolicyTx, expectedBaseRev: Long): ClaimWriteResult {
        var res: ClaimWriteResult = ClaimWriteResult.BUSY
        commitMutex.withLock {
            try {
                if (activeTx.get() != null) {
                    res = ClaimWriteResult.BUSY
                } else if (expectedBaseRev != -1L && committedRevision.get() != expectedBaseRev) {
                    res = ClaimWriteResult.STALE
                } else {
                    activeTx.set(tx)
                    res = ClaimWriteResult.CLAIMED
                }
            } catch (_: Exception) {
                res = ClaimWriteResult.BUSY
            }
        }
        return res
    }

    // Stage L1 P1: staged emit (call after commitMutex released).
    // tryEmit hit -> clear pending (replayCache now carries it, union still sees it);
    // buffer-full -> keep pending until async emit lands, then clear.
    fun emitStaged(payload: PolicySettled) {
        try {
            if (tryEmitSettled(payload)) {
                try {
                    pendingPublish.compareAndSet(payload, null)
                } catch (_: Exception) {
                }
                return
            }
        } catch (_: Exception) {
        }
        try {
            scope.launch {
                try {
                    withTimeoutOrNull(2_000L) { _settled.emit(payload) }
                } catch (_: Exception) {
                }
                try {
                    pendingPublish.compareAndSet(payload, null)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun clearPendingPublish(payload: PolicySettled) {
        try {
            pendingPublish.compareAndSet(payload, null)
        } catch (_: Exception) {
        }
    }

    // Stage H2 settled 廣播：勝者終態（IDLE+draft/Known/msg）replay=8。
    // 勝者在 tryClaim 成功後、settle 前 tryEmit；敗者以有界 withTimeout 訂閱
    // 同一 txId，收到即應用本地 IDLE 終態；逾時則走顯式 reconcile 兜底，不永久卡死。
    // Stage J4 (P2#5 tryEmit drop)：replay=1＋extra=1 下 tryEmit 失敗即丟棄，
    // 連發終態覆寫 replay 使跟隨者等滿超時再降級。改 replay=8＋extra=8 並經
    // emitSettledAsync 兜底（tryEmit 失敗即 coordinator.scope 內 suspending emit，
    // 有界 2s），免掉終態 strands claimed VM。
    private val _settled =
        MutableSharedFlow<PolicySettled>(replay = 8, extraBufferCapacity = 8)
    val settled: SharedFlow<PolicySettled> = _settled.asSharedFlow()

    fun tryEmitSettled(s: PolicySettled): Boolean = try {
        _settled.tryEmit(s)
    } catch (_: Exception) {
        false
    }

    // Stage J4 lossless 發射：tryEmit 命中即回；失敗（buffer 滿）即經 scope 內
    // suspending emit（有界 2s）補發，不丟棄。呼叫端一律用此者（不再直呼 tryEmit）。
    fun emitSettledAsync(s: PolicySettled) {
        if (tryEmitSettled(s)) return
        try {
            scope.launch {
                try {
                    withTimeoutOrNull(2_000L) { _settled.emit(s) }
                } catch (_: Exception) {
                    // Best effort: 兜底失敗不影響本地終態。
                }
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    suspend fun awaitSettled(txId: Long, key: String, timeoutMs: Long): PolicySettled? = try {
        withTimeoutOrNull(timeoutMs) { settled.first { it.txId == txId && it.key == key } }
    } catch (_: Exception) {
        null
    }

    // Stage I1 (P2#2)：exactly-once 結算 CAS——activeTx(tx→null) 恰一勝者。
    // 勝者才發布終態＋結清；敗者 early-return（不發布、不碰勝者 fence）。
    // settlePolicyTx 經此做 coordinator 側 claim（claim 先行，免先讀後 emit 窗口）。
    fun tryCompleteClaim(tx: DualViewModel.PolicyTx): Boolean = try {
        activeTx.compareAndSet(tx, null)
    } catch (_: Exception) {
        false
    }

    // App-scoped save IO 域：逾時 save 的 deferred 跑在此域（SupervisorJob＋IO），
    // 使慢 save 存活過 VM 重建（viewModelScope 隨 VM 清除而取消，deferred 若掛其下
    // 即被取消，新 VM join 同一 deferred 將見 CE 而非真值）。無 coordinator 的舊
    // 測試路徑仍走 viewModelScope，行為不變。
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Stage G2 P2-2：測試重置入口（靜態 fallback 跨用例共享 activeTx＋scope
    // 永不關；測試/極罕 fallback 路徑經此重置，生產 GalaxyApp 單例生命週期
    // 與 Application 同，不另關）。
    // Stage H1：同清 writeMutex（best effort 解鎖，免跨用例殘留鎖死；activeTx
    // 清空＋鎖釋放同組，後續可新寫）。
    fun resetForTest() {
        activeTx.set(null)
        globalTxId.set(0L)
        try {
            committedRevision.set(0L)
        } catch (_: Exception) {
        }
        try {
            pendingPublish.set(null)
        } catch (_: Exception) {
        }
        try {
            _settled.resetReplayCache()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            while (writeMutex.isLocked) {
                writeMutex.unlock()
            }
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            while (commitMutex.isLocked) {
                commitMutex.unlock()
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    fun close() {
        try {
            scope.cancel()
        } catch (_: Exception) {
            // Best effort.
        }
    }
}
