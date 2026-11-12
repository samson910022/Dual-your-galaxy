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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
// Stage L3 (P2#5 lossless)：終態攜 rev（committedRevision 序號，publish order =
// claim order）。_latest StateFlow 為唯一真相（同步、單調、無逐出）；_settled
// SharedFlow 僅為瞬態 notifier（replay 仍保留相容，晚訂閱以 _latest 收斂）。
data class PolicySettled(
    val txId: Long,
    val key: String,
    val draft: Set<String>,
    val known: Boolean,
    val msgKind: MsgKind,
    val msg: String,
    val isClear: Boolean,
    val rev: Long = -1L,
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
    // Stage L3: committedRevision 兼作 publish seq（不另設 divergent counter）。
    // _latest StateFlow 為唯一真相（同步、單調、無逐出，publish order = claim
    // order）；_settled SharedFlow 僅為瞬態 notifier（相容保留）。
    val commitMutex = Mutex()
    val committedRevision = AtomicLong(0L)
    val pendingPublish = AtomicReference<PolicySettled?>(null)

    // Stage L3 lossless 真相：永為最新終態（無逐出，晚訂閱收斂最新；gap 經 rev
    // 檢出）。僅終態（real）寫入，placeholder（claim fence）不寫入，免 Unknown
    // 佔位污染觀察者。
    private val _latest = MutableStateFlow<PolicySettled?>(null)
    val latest: StateFlow<PolicySettled?> = _latest.asStateFlow()

    // Stage L3 可觀測：withTimeoutOrNull 到期永不視為送達；drop/supersede 計數＋
    // stderr log（JVM 可測，不依賴 android.util.Log）。
    val emitTimeoutDrops = AtomicLong(0L)
    val emitSuperseded = AtomicLong(0L)

    private fun logCoord(msg: String) {
        try {
            System.err.println("[PolicyTxCoordinator] $msg")
        } catch (_: Exception) {
        }
    }

    fun currentRevision(): Long = try {
        committedRevision.get()
    } catch (_: Exception) {
        0L
    }

    suspend fun <T> withCommitLock(block: suspend () -> T): T = commitMutex.withLock { block() }

    // Stage L1 P1: atomic complete (CAS + revision bump + stage) under commitMutex.
    // Returns true=winner (staged, caller must invoke afterClaimBeforeEmit hook
    // then emit after unlock); false=loser (no stage, no bump).
    // Stage L3: stamp rev (= committedRevision) into pending placeholder; _latest
    // 不寫 placeholder（非終態），終態經 stageRealPayload 寫入。
    suspend fun completeWithRevision(tx: DualViewModel.PolicyTx, payload: PolicySettled): Boolean {
        var ok = false
        commitMutex.withLock {
            try {
                if (activeTx.compareAndSet(tx, null)) {
                    val rev = committedRevision.incrementAndGet()
                    val stamped = try {
                        payload.copy(rev = rev)
                    } catch (_: Exception) {
                        payload
                    }
                    pendingPublish.set(stamped)
                    ok = true
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    // Stage L3: stage terminal payload (preserve claim rev, update _latest
    // monotonically). Call after commitMutex released? No — this takes the lock
    // itself (stage under lock, emit after release by caller via emitStaged).
    // Returns stamped to emit, or null if superseded (caller must skip SharedFlow
    // emit; metric+log already surfaced, never silent).
    suspend fun stageRealPayload(payload: PolicySettled): PolicySettled? {
        var out: PolicySettled? = null
        commitMutex.withLock {
            try {
                val cur = try { pendingPublish.get() } catch (_: Exception) { null }
                if (cur != null && cur.txId == payload.txId && cur.key == payload.key) {
                    val rev = cur.rev
                    val stamped = try {
                        if (payload.rev == -1L || payload.rev != rev) payload.copy(rev = rev)
                        else payload
                    } catch (_: Exception) {
                        payload
                    }
                    pendingPublish.set(stamped)
                    val latestCur = try { _latest.value } catch (_: Exception) { null }
                    if (latestCur == null || stamped.rev == -1L || latestCur.rev == -1L ||
                        stamped.rev >= latestCur.rev
                    ) {
                        try { _latest.value = stamped } catch (_: Exception) { }
                        out = stamped
                    } else {
                        emitSuperseded.incrementAndGet()
                        logCoord(
                            "stage superseded txId=${payload.txId} rev=${stamped.rev} " +
                                "behind latest rev=${latestCur.rev} txId=${latestCur.txId}",
                        )
                        out = null
                    }
                } else if (cur == null) {
                    val rev = if (payload.rev != -1L) payload.rev else try {
                        committedRevision.get()
                    } catch (_: Exception) { -1L }
                    val stamped = try { payload.copy(rev = rev) } catch (_: Exception) { payload }
                    pendingPublish.set(stamped)
                    val latestCur = try { _latest.value } catch (_: Exception) { null }
                    if (latestCur == null || stamped.rev == -1L || latestCur.rev == -1L ||
                        stamped.rev >= latestCur.rev
                    ) {
                        try { _latest.value = stamped } catch (_: Exception) { }
                        out = stamped
                    } else {
                        emitSuperseded.incrementAndGet()
                        logCoord(
                            "stage superseded (no placeholder) txId=${payload.txId} " +
                                "rev=${stamped.rev} behind latest ${latestCur.rev}",
                        )
                        try { pendingPublish.compareAndSet(stamped, null) } catch (_: Exception) { }
                        out = null
                    }
                } else {
                    emitSuperseded.incrementAndGet()
                    logCoord(
                        "stage superseded txId=${payload.txId} rev=${payload.rev} " +
                            "by pending txId=${cur.txId} rev=${cur.rev}",
                    )
                    out = null
                }
            } catch (_: Exception) {
                out = null
            }
        }
        return out
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
    // Stage L3 lossless: _latest 已於 stageRealPayload 同步更新（有序、無逐出），
    // 此處僅為 SharedFlow 瞬態 notifier；withTimeoutOrNull 到期永不視為送達
    // （計數＋log，pending 仍按 fence 清除，收斂經 _latest/resync，不靜默丟失）。
    // tryEmit hit -> clear pending (replayCache now carries it, union still sees it);
    // buffer-full -> keep pending until async emit lands, then clear.
    fun emitStaged(payload: PolicySettled) {
        var toEmit = payload
        // Preserve claim rev when caller passed unstamped but pending holds it
        // (avoid double-bump via fresh increment).
        try {
            if (toEmit.rev == -1L) {
                val cur = try { pendingPublish.get() } catch (_: Exception) { null }
                if (cur != null && cur.txId == toEmit.txId && cur.key == toEmit.key && cur.rev != -1L) {
                    try { toEmit = toEmit.copy(rev = cur.rev) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
        }
        // Synthetic direct (no claim, rev==-1): assign fresh seq via bump.
        try {
            if (toEmit.rev == -1L) {
                val rev = try { committedRevision.incrementAndGet() } catch (_: Exception) { -1L }
                try { toEmit = toEmit.copy(rev = rev) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
        val finalEmit = toEmit
        // Overtake guard: stale (rev < latest) never overwrites _latest nor
        // SharedFlow order — explicit supersede, never silent.
        try {
            val curLatest = try { _latest.value } catch (_: Exception) { null }
            if (curLatest != null && finalEmit.rev != -1L && curLatest.rev != -1L &&
                finalEmit.rev < curLatest.rev
            ) {
                emitSuperseded.incrementAndGet()
                logCoord(
                    "emit superseded txId=${finalEmit.txId} rev=${finalEmit.rev} " +
                        "behind latest rev=${curLatest.rev} txId=${curLatest.txId}",
                )
                try {
                    val curP = try { pendingPublish.get() } catch (_: Exception) { null }
                    if (curP != null && curP.txId == finalEmit.txId &&
                        curP.key == finalEmit.key && curP.rev == finalEmit.rev
                    ) {
                        pendingPublish.compareAndSet(curP, null)
                    }
                } catch (_: Exception) {
                }
                return
            }
            // Ensure _latest holds it (idempotent when stageReal already set;
            // synthetic path sets here).
            try {
                val cur2 = try { _latest.value } catch (_: Exception) { null }
                if (cur2 == null || finalEmit.rev == -1L || cur2.rev == -1L ||
                    finalEmit.rev >= cur2.rev
                ) {
                    _latest.value = finalEmit
                }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        var ok = false
        try {
            ok = tryEmitShared(finalEmit)
        } catch (_: Exception) {
            ok = false
        }
        if (ok) {
            try {
                val curP = try { pendingPublish.get() } catch (_: Exception) { null }
                if (curP != null && curP.txId == finalEmit.txId &&
                    curP.key == finalEmit.key && curP.rev == finalEmit.rev
                ) {
                    pendingPublish.compareAndSet(curP, null)
                } else {
                    // Legacy fallback: direct compare (synthetic without pending → no-op).
                    try { pendingPublish.compareAndSet(payload, null) } catch (_: Exception) { }
                    try { pendingPublish.compareAndSet(finalEmit, null) } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            }
            return
        }
        try {
            scope.launch {
                var delivered = false
                try {
                    delivered = withTimeoutOrNull(2_000L) { _settled.emit(finalEmit); true } ?: false
                } catch (_: Exception) {
                    delivered = false
                }
                if (!delivered) {
                    emitTimeoutDrops.incrementAndGet()
                    logCoord(
                        "emit timeout drop txId=${finalEmit.txId} rev=${finalEmit.rev} " +
                            "(lossless via latest rev=${try { _latest.value?.rev } catch (_: Exception) { null }}, " +
                            "SharedFlow notifier dropped, converge via latest/resync)",
                    )
                } else {
                    try {
                        val curAfter = try { _latest.value } catch (_: Exception) { null }
                        if (curAfter != null && finalEmit.rev != -1L && curAfter.rev != -1L &&
                            finalEmit.rev < curAfter.rev
                        ) {
                            emitSuperseded.incrementAndGet()
                            logCoord(
                                "overtake: stalled emit txId=${finalEmit.txId} rev=${finalEmit.rev} " +
                                    "landed after latest rev=${curAfter.rev} txId=${curAfter.txId} " +
                                    "(SharedFlow order violated, latest preserves order)",
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
                try {
                    val curP2 = try { pendingPublish.get() } catch (_: Exception) { null }
                    if (curP2 != null && curP2.txId == finalEmit.txId &&
                        curP2.key == finalEmit.key && curP2.rev == finalEmit.rev
                    ) {
                        pendingPublish.compareAndSet(curP2, null)
                    } else {
                        try { pendingPublish.compareAndSet(payload, null) } catch (_: Exception) { }
                        try { pendingPublish.compareAndSet(finalEmit, null) } catch (_: Exception) { }
                    }
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
        // Rev-aware clear: pending may hold stamped (same txId/key/rev, different
        // draft/known). Clear when identity matches to avoid fence leak.
        try {
            val cur = try { pendingPublish.get() } catch (_: Exception) { null }
            if (cur != null && cur.txId == payload.txId && cur.key == payload.key &&
                (cur.rev == payload.rev || payload.rev == -1L)
            ) {
                pendingPublish.compareAndSet(cur, null)
            }
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
    // Stage L3: _settled 僅為瞬態 notifier（相容保留，replay 仍 8＋8）；真相為
    // _latest StateFlow（無逐出、有序）。tryEmit 同步更新 _latest（單調）。
    private val _settled =
        MutableSharedFlow<PolicySettled>(replay = 8, extraBufferCapacity = 8)
    val settled: SharedFlow<PolicySettled> = _settled.asSharedFlow()

    private fun tryEmitShared(s: PolicySettled): Boolean = try {
        _settled.tryEmit(s)
    } catch (_: Exception) {
        false
    }

    fun tryEmitSettled(s: PolicySettled): Boolean {
        var stamped = s
        try {
            if (stamped.rev == -1L) {
                // Synthetic direct (no claim): assign fresh seq. Claim-path callers
                // pass stamped (rev set) so no double-bump here.
                // Preserve pending claim rev when matching (avoid divergent seq).
                val cur = try { pendingPublish.get() } catch (_: Exception) { null }
                if (cur != null && cur.txId == stamped.txId && cur.key == stamped.key && cur.rev != -1L) {
                    try { stamped = stamped.copy(rev = cur.rev) } catch (_: Exception) { }
                } else {
                    val rev = try { committedRevision.incrementAndGet() } catch (_: Exception) { -1L }
                    try { stamped = stamped.copy(rev = rev) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val curLatest = try { _latest.value } catch (_: Exception) { null }
            if (curLatest != null && stamped.rev != -1L && curLatest.rev != -1L &&
                stamped.rev < curLatest.rev
            ) {
                emitSuperseded.incrementAndGet()
                logCoord(
                    "tryEmit superseded txId=${stamped.txId} rev=${stamped.rev} " +
                        "behind latest rev=${curLatest.rev}",
                )
                return false
            }
            try {
                val cur2 = try { _latest.value } catch (_: Exception) { null }
                if (cur2 == null || stamped.rev == -1L || cur2.rev == -1L || stamped.rev >= cur2.rev) {
                    _latest.value = stamped
                }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        return tryEmitShared(stamped)
    }

    // Stage J4 lossless 發射：tryEmit 命中即回；失敗（buffer 滿）即經 scope 內
    // suspending emit（有界 2s）補發，不丟棄。呼叫端一律用此者（不再直呼 tryEmit）。
    // Stage L3: 委託 emitStaged（rev 守衛＋_latest 真相＋timeout 計數＋log）。
    fun emitSettledAsync(s: PolicySettled) {
        try {
            emitStaged(s)
        } catch (_: Exception) {
            // Best effort.
        }
    }

    // Stage L3: lossless await — _latest 快徑＋replay 快照＋merge 懸掛。
    // Timeout (null) 永不視為送達；呼叫端須走 boundedReconcileRead 顯式兜底。
    suspend fun awaitSettled(txId: Long, key: String, timeoutMs: Long): PolicySettled? {
        try {
            _latest.value?.takeIf { it.txId == txId && it.key == key }?.let { return it }
        } catch (_: Exception) {
        }
        try {
            _settled.replayCache.firstOrNull { it.txId == txId && it.key == key }?.let { return it }
        } catch (_: Exception) {
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                merge(latest.filterNotNull(), settled).first { it.txId == txId && it.key == key }
            }
        } catch (_: Exception) {
            null
        }
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
    @OptIn(ExperimentalCoroutinesApi::class)
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
            _latest.value = null
        } catch (_: Exception) {
        }
        try {
            emitTimeoutDrops.set(0L)
        } catch (_: Exception) {
        }
        try {
            emitSuperseded.set(0L)
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
