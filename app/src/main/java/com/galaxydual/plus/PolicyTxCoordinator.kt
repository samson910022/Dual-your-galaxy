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
    // - Lock order: opMutex->writeMutex->policyRevisionMutex->commitMutex;
    //   never emit while holding mutex (stage under lock, emit after release).
    // Stage L3: committedRevision 兼作 publish seq（不另設 divergent counter）。
    // _latest StateFlow 為唯一真相（同步、單調、無逐出，publish order = claim
    // order）；_settled SharedFlow 僅為瞬態 notifier（相容保留）。
    val commitMutex = Mutex()
    val committedRevision = AtomicLong(0L)
    val pendingPublish = AtomicReference<PolicySettled?>(null)
    // Stage M3 (P2#5 orphan fence): staged-at wall clock for stale-placeholder
    // finalizer. Set atomically with pendingPublish under commitMutex (same
    // critical section); read under the same lock by pre-IO gates. 0 = never
    // staged (legacy / cleared). System.currentTimeMillis matches the VM-side
    // recovery clock (same source, no injectable skew).
    // Stage N3 P1#3: wall clock retained for compat/logging only; age decisions
    // use monotonic pendingPublishAtNanos (System.nanoTime, immune to wall
    // backwards jumps). Backwards nanos (now < staged) => not stale.
    val pendingPublishAtMs = AtomicLong(0L)
    val pendingPublishAtNanos = AtomicLong(0L)
    // Stage N3 P1#3: originating-claim owner for stronger-than-age death proof.
    // Set atomically with pendingPublish in completeWithRevision; cleared when
    // pending is emitted/cleared/dropped. Lets recover distinguish in-flight
    // save (deferred running) from dead/suspended owner (deferred done).
    val pendingOwner = AtomicReference<DualViewModel.PolicyTx?>(null)
    // Stage N3 P1#3: identity/revision tombstone for dropped placeholders.
    // Key "$txId:$key" -> rev. Persisted after placeholder drop so a late
    // resumer with the same identity is rejected in stage/emit even when
    // pending is null (never borrow current rev). Cleared only by resetForTest.
    val claimTombstones = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    // Stage N3 P1#3: tombstone key helper (exact originating-claim identity).
    internal fun tombstoneKey(txId: Long, key: String): String = "${txId}:${key}"

    internal fun isTombstoned(txId: Long, key: String): Boolean = try {
        claimTombstones.containsKey(tombstoneKey(txId, key))
    } catch (_: Exception) {
        false
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
                    // Stage M3: stamp fence time with the payload (same lock).
                    try { pendingPublishAtMs.set(System.currentTimeMillis()) } catch (_: Exception) { }
                    // Stage N3 P1#3: monotonic fence time + originating-claim owner.
                    try { pendingPublishAtNanos.set(System.nanoTime()) } catch (_: Exception) { }
                    try { pendingOwner.set(tx) } catch (_: Exception) { }
                    ok = true
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    // Stage M3 (P2#5 orphan fence) + N3 P1#3 hardening: coordinator-scoped
    // finalizer for PLACEHOLDERS ONLY. Never infer death from age alone and
    // never promote real payloads based on age.
    // Drop requires ALL of (stronger-than-age owner-gone proof):
    // - pending is a placeholder fence (Unknown + empty msg, never terminal);
    //   real terminal payloads are NEVER touched here (return null, owner must
    //   stage/emit via exact-identity stageRealPayload);
    // - owner not active in coordinator (activeTx null or different identity);
    // - monotonic age >= threshold (System.nanoTime; backwards now<staged => keep);
    // - recorded owner deferred done (isCompleted || isCancelled) when owner is
    //   known — proves the save IO finished and the owner should have proceeded
    //   to stageRealPayload; a still-running save (deferred incomplete) is
    //   in-flight, never orphan. Unknown owner (legacy) falls back to
    //   age+placeholder+inactive (logged).
    // On drop: persist identity/revision tombstone (txId:key->rev) so a late
    // resumer with the same identity is rejected by stage/emit even when
    // pending is null (never borrow current rev); clear pending+owner.
    // Returns the dropped placeholder, or null when nothing stale (fresh fence,
    // owner active/running, real payload, or no pending). Caller holds
    // commitMutex (pre-IO gates call this inside withCommitLock); suspend
    // wrapper below acquires the lock for out-of-gate callers/tests.
    // NOTE: nowNanos is monotonic System.nanoTime(); callers must pass
    // System.nanoTime(), never wall clock. staleThresholdMs in millis.
    fun recoverStalePendingLocked(nowNanos: Long, staleThresholdMs: Long): PolicySettled? {
        try {
            val cur = try { pendingPublish.get() } catch (_: Exception) { null } ?: return null
            val active = try { activeTx.get() } catch (_: Exception) { null }
            // Owner still active (pending belongs to the live Tx) -> in-flight, keep.
            if (active != null && active.txId == cur.txId && active.key == cur.key) return null
            val isPlaceholder = try { !cur.known && cur.msg.isEmpty() } catch (_: Exception) { false }
            if (!isPlaceholder) {
                // Stage N3 P1#3: real payloads are never aged-out here. Owner must
                // stage/emit via exact identity; age-based promotion could resurrect
                // stale truth over a newer latest. Keep pending, let gates abort.
                return null
            }
            val stagedAt = try { pendingPublishAtNanos.get() } catch (_: Exception) { 0L }
            if (stagedAt == 0L) return null
            // Backwards jump (now < staged): monotonic should not go backwards;
            // treat as not-stale (never infer death).
            if (nowNanos < stagedAt) return null
            val ageMs = try { (nowNanos - stagedAt) / 1_000_000L } catch (_: Exception) { 0L }
            if (ageMs < staleThresholdMs) return null
            // Stronger-than-age: recorded owner must be done (save finished).
            val owner = try { pendingOwner.get() } catch (_: Exception) { null }
            if (owner != null) {
                if (owner.txId != cur.txId || owner.key != cur.key) return null
                val done = try {
                    owner.deferred.isCompleted || owner.deferred.isCancelled
                } catch (_: Exception) {
                    false
                }
                if (!done) return null
            } else {
                // Legacy: no owner recorded — allow age+placeholder+inactive drop
                // (logged) to preserve M3 dead-owner recovery for old paths.
                logCoord(
                    "recover placeholder without owner record txId=${cur.txId} " +
                        "rev=${cur.rev} ageMs=${ageMs} (legacy fallback)",
                )
            }
            logCoord(
                "recover stale placeholder txId=${cur.txId} rev=${cur.rev} " +
                    "ageMs=${ageMs} (dropped, no truth)",
            )
            // Persist tombstone before clearing so late resumer is rejected.
            // Stage N3 bound: cap tombstones (practical DoS bound; evict one
            // arbitrary + log when full; resurrected identity still faces
            // cur==null reject + rev guard, tombstone is extra defense).
            try {
                if (claimTombstones.size >= 512) {
                    try {
                        val evict = claimTombstones.keys.firstOrNull()
                        if (evict != null) {
                            claimTombstones.remove(evict)
                            logCoord("tombstone bound evict $evict (size cap 512)")
                        }
                    } catch (_: Exception) {
                    }
                }
                claimTombstones[tombstoneKey(cur.txId, cur.key)] = cur.rev
            } catch (_: Exception) {
            }
            val cleared = try {
                pendingPublish.compareAndSet(cur, null)
            } catch (_: Exception) {
                false
            }
            if (cleared) {
                try {
                    val own = try { pendingOwner.get() } catch (_: Exception) { null }
                    if (own != null && own.txId == cur.txId && own.key == cur.key) {
                        pendingOwner.compareAndSet(own, null)
                    }
                } catch (_: Exception) {
                }
                return cur
            } else {
                // Lost race: another thread staged/cleared; roll back tombstone?
                // Keep tombstone (safe: exact identity now held by newer pending,
                // late old identity still correctly rejected). Do not return.
                return null
            }
        } catch (_: Exception) {
            return null
        }
    }

    suspend fun tryRecoverStalePending(nowNanos: Long, staleThresholdMs: Long): PolicySettled? =
        commitMutex.withLock { recoverStalePendingLocked(nowNanos, staleThresholdMs) }

    // Stage L3 + N3 P1#3: stage terminal payload with EXACT originating-claim
    // identity. Succeeds ONLY when pendingPublish holds the matching claim
    // (same txId+key; when caller passes a stamped rev it must equal the claim
    // rev). All other cases are explicitly superseded (metric+log, never silent):
    // - cur==null (no pending: dropped/cleared/never claimed) -> reject, NEVER
    //   borrow committedRevision/current rev for an unstamped payload. Owner must
    //   re-claim (new txId/rev) instead of resurrecting a dead identity.
    // - cur mismatch (foreign pending) -> reject, never overwrite newer fence.
    // - payload stamped rev != claim rev -> reject (divergent seq).
    // Tombstoned identities (previously dropped placeholder) are always rejected
    // even when pending is null. _latest/pending are untouched on reject.
    // Returns stamped to emit, or null if superseded (caller must skip emit).
    suspend fun stageRealPayload(payload: PolicySettled): PolicySettled? {
        var out: PolicySettled? = null
        commitMutex.withLock {
            try {
                // Tombstone fast-reject: dropped identity never stages again.
                if (isTombstoned(payload.txId, payload.key)) {
                    emitSuperseded.incrementAndGet()
                    logCoord(
                        "stage rejected (tombstoned) txId=${payload.txId} key=${payload.key} " +
                            "rev=${payload.rev} (dropped placeholder identity, must re-claim)",
                    )
                    out = null
                    return@withLock
                }
                val cur = try { pendingPublish.get() } catch (_: Exception) { null }
                if (cur != null && cur.txId == payload.txId && cur.key == payload.key) {
                    // Exact identity match; enforce rev equality when stamped.
                    if (payload.rev != -1L && payload.rev != cur.rev) {
                        emitSuperseded.incrementAndGet()
                        logCoord(
                            "stage rejected (rev mismatch) txId=${payload.txId} " +
                                "payloadRev=${payload.rev} claimRev=${cur.rev}",
                        )
                        out = null
                        return@withLock
                    }
                    val rev = cur.rev
                    val stamped = try {
                        if (payload.rev == -1L || payload.rev != rev) payload.copy(rev = rev)
                        else payload
                    } catch (_: Exception) {
                        payload
                    }
                    pendingPublish.set(stamped)
                    // Stage M3/N3: refresh fence time on overwrite (same lock).
                    try { pendingPublishAtMs.set(System.currentTimeMillis()) } catch (_: Exception) { }
                    try { pendingPublishAtNanos.set(System.nanoTime()) } catch (_: Exception) { }
                    val latestCur = try { _latest.value } catch (_: Exception) { null }
                    val canSetLatest = try {
                        if (latestCur == null || stamped.rev == -1L || latestCur.rev == -1L) true
                        else if (stamped.rev > latestCur.rev) true
                        else stamped.rev == latestCur.rev &&
                            stamped.txId == latestCur.txId && stamped.key == latestCur.key
                    } catch (_: Exception) { false }
                    if (canSetLatest) {
                        try { _latest.value = stamped } catch (_: Exception) { }
                        out = stamped
                    } else {
                        emitSuperseded.incrementAndGet()
                        logCoord(
                            "stage superseded txId=${payload.txId} rev=${stamped.rev} " +
                                "behind latest rev=${latestCur?.rev} txId=${latestCur?.txId}",
                        )
                        out = null
                    }
                } else if (cur == null) {
                    // Stage N3 P1#3: no matching pending claim -> reject. Never
                    // borrow committedRevision for an unstamped payload; that
                    // lets a stalled old Tx stamp OLD payload with NEWER revision
                    // and overwrite latest on equal rev.
                    emitSuperseded.incrementAndGet()
                    logCoord(
                        "stage rejected (no pending claim) txId=${payload.txId} key=${payload.key} " +
                            "rev=${payload.rev} (must re-claim, never borrow current rev)",
                    )
                    out = null
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
    // Stage N3 P1#3: tombstoned identities never emit (even via synthetic bump);
    // equal-rev different-identity never overwrites latest (borrowed-rev guard).
    fun emitStaged(payload: PolicySettled) {
        // Tombstone pre-check: dropped placeholder identity must never resurrect
        // via emit, even with a fresh synthetic rev.
        try {
            if (isTombstoned(payload.txId, payload.key)) {
                emitSuperseded.incrementAndGet()
                logCoord(
                    "emit rejected (tombstoned) txId=${payload.txId} key=${payload.key} " +
                        "rev=${payload.rev} (must re-claim)",
                )
                return
            }
        } catch (_: Exception) {
        }
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
        // Stage N3 P1#3: equal rev from a DIFFERENT identity is also stale
        // (borrowed-rev resurrection); only same-identity equal rev may refresh.
        try {
            val curLatest = try { _latest.value } catch (_: Exception) { null }
            if (curLatest != null && finalEmit.rev != -1L && curLatest.rev != -1L) {
                val sameIdentity = finalEmit.txId == curLatest.txId && finalEmit.key == curLatest.key
                if (finalEmit.rev < curLatest.rev || (finalEmit.rev == curLatest.rev && !sameIdentity)) {
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
            }
            // Ensure _latest holds it (idempotent when stageReal already set;
            // synthetic path sets here).
            // Stage N3: equal-rev different-identity never overwrites (borrowed rev).
            try {
                val cur2 = try { _latest.value } catch (_: Exception) { null }
                val canSet = try {
                    if (cur2 == null || finalEmit.rev == -1L || cur2.rev == -1L) true
                    else if (finalEmit.rev > cur2.rev) true
                    else finalEmit.rev == cur2.rev &&
                        finalEmit.txId == cur2.txId && finalEmit.key == cur2.key
                } catch (_: Exception) { false }
                if (canSet) {
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
                    if (pendingPublish.compareAndSet(curP, null)) {
                        try {
                            val own = try { pendingOwner.get() } catch (_: Exception) { null }
                            if (own != null && own.txId == curP.txId && own.key == curP.key) {
                                pendingOwner.compareAndSet(own, null)
                            }
                        } catch (_: Exception) {
                        }
                    }
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
                        if (pendingPublish.compareAndSet(curP2, null)) {
                            try {
                                val own2 = try { pendingOwner.get() } catch (_: Exception) { null }
                                if (own2 != null && own2.txId == curP2.txId && own2.key == curP2.key) {
                                    pendingOwner.compareAndSet(own2, null)
                                }
                            } catch (_: Exception) {
                            }
                        }
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
                if (pendingPublish.compareAndSet(cur, null)) {
                    try {
                        val own = try { pendingOwner.get() } catch (_: Exception) { null }
                        if (own != null && own.txId == cur.txId && own.key == cur.key) {
                            pendingOwner.compareAndSet(own, null)
                        }
                    } catch (_: Exception) {
                    }
                }
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
        // Stage N3: tombstoned identity never emits.
        try {
            if (isTombstoned(s.txId, s.key)) {
                emitSuperseded.incrementAndGet()
                logCoord(
                    "tryEmit rejected (tombstoned) txId=${s.txId} key=${s.key} rev=${s.rev}",
                )
                return false
            }
        } catch (_: Exception) {
        }
        var stamped = s
        try {
            if (stamped.rev == -1L) {
                // Synthetic direct (no claim): assign fresh seq. Claim-path callers
                // pass stamped (rev set) so no double-bump here.
                // Preserve pending claim rev when matching (avoid divergent seq).
                // Stage N3: mismatched pending or tombstoned -> do NOT borrow;
                // synthetic without exact claim is superseded (must re-claim).
                val cur = try { pendingPublish.get() } catch (_: Exception) { null }
                if (cur != null && cur.txId == stamped.txId && cur.key == stamped.key && cur.rev != -1L) {
                    try { stamped = stamped.copy(rev = cur.rev) } catch (_: Exception) { }
                } else if (cur != null) {
                    // Foreign pending in-flight: stale synthetic, reject.
                    emitSuperseded.incrementAndGet()
                    logCoord(
                        "tryEmit rejected (foreign pending) txId=${stamped.txId} " +
                            "by pending txId=${cur.txId} rev=${cur.rev}",
                    )
                    return false
                } else {
                    // No pending: only allow synthetic when not tombstoned (checked
                    // above). This preserves l3_overflow lossless test (pure
                    // synthetic, no claim). Claimed-path late resumers are already
                    // tombstoned and rejected above.
                    val rev = try { committedRevision.incrementAndGet() } catch (_: Exception) { -1L }
                    try { stamped = stamped.copy(rev = rev) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val curLatest = try { _latest.value } catch (_: Exception) { null }
            if (curLatest != null && stamped.rev != -1L && curLatest.rev != -1L) {
                val sameId = stamped.txId == curLatest.txId && stamped.key == curLatest.key
                if (stamped.rev < curLatest.rev || (stamped.rev == curLatest.rev && !sameId)) {
                    emitSuperseded.incrementAndGet()
                    logCoord(
                        "tryEmit superseded txId=${stamped.txId} rev=${stamped.rev} " +
                            "behind latest rev=${curLatest.rev}",
                    )
                    return false
                }
            }
            try {
                val cur2 = try { _latest.value } catch (_: Exception) { null }
                val canSet = try {
                    if (cur2 == null || stamped.rev == -1L || cur2.rev == -1L) true
                    else if (stamped.rev > cur2.rev) true
                    else stamped.rev == cur2.rev &&
                        stamped.txId == cur2.txId && stamped.key == cur2.key
                } catch (_: Exception) { false }
                if (canSet) {
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
            pendingPublishAtMs.set(0L)
        } catch (_: Exception) {
        }
        try {
            pendingPublishAtNanos.set(0L)
        } catch (_: Exception) {
        }
        try {
            pendingOwner.set(null)
        } catch (_: Exception) {
        }
        try {
            claimTombstones.clear()
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
