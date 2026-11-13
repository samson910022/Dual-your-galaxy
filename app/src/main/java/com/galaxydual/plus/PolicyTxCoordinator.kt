// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage G2 (P2#4#5):跨 VM 最小 coordinator。App-scoped（Application 或
// Activity-retained holder），activeTx 為唯一跨 VM 真相；VM onCleared 不清除，
// 新 VM init 認領（繼承 fence+RECONCILING+pending+Unknown + 啟動 reconciler join
// 同一 deferred）。
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
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
    // Stage O1 P1#1 (orphan-drop invalidation): monotonic drop generation.
    // Incremented on every successful placeholder drop (recoverStalePendingLocked).
    // VMs compare at request time (authoritativeObservedRev vs currentRevision covers
    // rev advance; this gen covers the no-event drop itself so an already-open stale
    // VM cannot reuse a pre-drop base even when rev comparison is bypassed by legacy
    // -1). Observability only; never drives truth (truth stays in _latest).
    val orphanDropGen = AtomicLong(0L)
    // P3: drop revision paired with orphanDropGen. The gen alone over-fail-closes:
    // ANY drop since lastSeen (even a placeholder older than our observed rev)
    // would block request. The paired rev lets request block ONLY when the drop
    // is actually newer than what this VM authoritatively read
    // (dropRev > observedRev); older drops just advance lastSeenDropGen silently.
    // Max-drop rev (monotonic max, never regress): two placeholders may be
    // staged/dropped out of order across threads; keeping the max preserves
    // the newest invalidation (last-write-wins would let an older dropRev
    // mask a newer drop and reopen a stale-read window). The rev gate
    // (currentRevision != observed) independently covers rev advances.
    val orphanDropRev = AtomicLong(-1L)
    // Stage O1 P2#3 (stranded-real availability): retired-real counter.
    // Incremented when a stale real staged payload already present in _latest is
    // retired (pending cleared, truth preserved). Monotonic, observability only.
    val retiredRealCount = AtomicLong(0L)
    // Stage N3 P1#3: identity/revision tombstone for dropped placeholders.
    // Key "$txId:$key" -> rev. Persisted after placeholder drop so a late
    // resumer with the same identity is rejected in stage/emit even when
    // pending is null (never borrow current rev). Cleared only by resetForTest.
    // P3 tombstone discipline (locked invariant):
    // - placeholder drops tombstone (recoverStalePendingLocked, overrun discard);
    // - real retires NEVER tombstone (retireStagedRealPresentInLatestLocked clears
    //   pending but preserves truth; the completed identity stays valid in _latest).
    // Phase4 bound (hard cap, no eviction): when the table is full the new
    // placeholder drop is REJECTED (pending kept, caller returns null/false so
    // the pre-IO gate marks STALE and retries) instead of evicting an arbitrary
    // entry. Evicting (Phase2 #5 legacy keys.firstOrNull()) could resurrect a
    // live identity: the evicted tombstone's late resumer would pass the
    // tombstone check and borrow current rev. A placeholder carries no truth
    // (_latest untouched), so rejecting loses nothing.
    // Phase7: GuardedBy(commitMutex) — all mutating readers/writers run under
    // commitMutex.withLock (recover/discard paths) or are idempotent
    // concurrent reads (stage/emit fast-reject). The map itself is a
    // ConcurrentHashMap so the lock-free isTombstoned fast-reject stays safe;
    // the guard documents the mutation ordering, not a hard lock requirement
    // for reads. Rejections counted in [tombstoneRejects].
    // @GuardedBy("commitMutex") for mutations (see above; reads lock-free safe).
    val claimTombstones = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Phase7 observability: tombstone bound-full rejections (drop refused,
    // fence kept). Monotonic, never reset except resetForTest.
    val tombstoneRejects = AtomicLong(0L)

    // Phase4: bounded tombstone insert. True = recorded (drop may proceed);
    // false = table full (drop must abort, pending kept, STALE retry).
    // Re-recording an already-tombstoned identity only refreshes its rev.
    internal fun tryInsertClaimTombstone(txId: Long, key: String, rev: Long): Boolean = try {
        val k = tombstoneKey(txId, key)
        if (claimTombstones.containsKey(k)) {
            claimTombstones[k] = rev
            true
        } else if (claimTombstones.size >= 512) {
            try {
                tombstoneRejects.incrementAndGet()
            } catch (_: Throwable) {
            }
            logCoord(
                "tombstone bound full (512): reject drop txId=$txId " +
                    "(keep fence, pre-IO gate retries STALE)",
            )
            false
        } else {
            claimTombstones[k] = rev
            true
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        false
    }

    // Phase7 test seam: bump the authoritative revision without a full Tx
    // (simulates a concurrent B commit for TOCTOU barrier tests). Returns
    // the new revision.
    internal fun bumpRevisionForTests(): Long = try {
        committedRevision.incrementAndGet()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        -1L
    }

    // Phase4 test lock: canonical PolicyTx acquisition order. commitMutex is
    // the only coordinator-side lock; the full order (VM opMutex/writeMutex/
    // policyRevisionMutex first) is documented here so tests pin it and any
    // future coordinator lock joins this tail (never head).
    internal fun policyTxLockOrder(): String =
        "opMutex->writeMutex->policyRevisionMutex->commitMutex"

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
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        true
    }

    fun currentRevision(): Long = try {
        committedRevision.get()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        -1L
    }

    internal var commitLockFailForTests: (() -> Throwable)? = null

    suspend fun <T> withCommitLock(block: suspend () -> T): T {
        commitLockFailForTests?.let { throw it() }
        return commitMutex.withLock { block() }
    }

    private fun canAdvanceLatest(cur: PolicySettled?, incoming: PolicySettled): Boolean {
        if (cur == null) return true
        if (incoming.rev == -1L) {
            if (cur.rev != -1L) return false
            return incoming.txId > cur.txId ||
                (incoming.txId == cur.txId && incoming.key == cur.key)
        }
        if (cur.rev == -1L) return true
        if (incoming.rev > cur.rev) return true
        return incoming.rev == cur.rev &&
            incoming.txId == cur.txId &&
            incoming.key == cur.key
    }

    private fun tryUpdateLatestMonotonic(incoming: PolicySettled): Boolean {
        while (true) {
            val cur = _latest.value
            if (!canAdvanceLatest(cur, incoming)) return false
            if (_latest.compareAndSet(cur, incoming)) return true
        }
    }

    private fun clearPendingOwnerIfMatch(txId: Long, key: String) {
        try {
            val own = try { pendingOwner.get() } catch (_: Exception) { null }
            if (own != null && own.txId == txId && own.key == key) {
                pendingOwner.compareAndSet(own, null)
            }
        } catch (_: Exception) {
        }
    }

    private fun clearPendingAndOwnerIfMatch(
        txId: Long,
        key: String,
        rev: Long,
        fallbackA: PolicySettled? = null,
        fallbackB: PolicySettled? = null,
    ) {
        try {
            val curP = try { pendingPublish.get() } catch (_: Exception) { null }
            if (curP != null && curP.txId == txId && curP.key == key &&
                (curP.rev == rev || rev == -1L)
            ) {
                if (pendingPublish.compareAndSet(curP, null)) {
                    clearPendingOwnerIfMatch(curP.txId, curP.key)
                }
            } else {
                if (fallbackA != null) {
                    try {
                        if (pendingPublish.compareAndSet(fallbackA, null)) {
                            clearPendingOwnerIfMatch(fallbackA.txId, fallbackA.key)
                        }
                    } catch (_: Exception) { }
                }
                if (fallbackB != null) {
                    try {
                        if (pendingPublish.compareAndSet(fallbackB, null)) {
                            clearPendingOwnerIfMatch(fallbackB.txId, fallbackB.key)
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
        }
    }

    // Stage L1 P1: atomic complete (CAS + revision bump + stage) under commitMutex.
    // Returns true=winner (staged, caller must invoke afterClaimBeforeEmit hook
    // then emit after unlock); false=loser (no stage, no bump).
    // Stage L3: stamp rev (= committedRevision) into pending placeholder; _latest
    // 不寫 placeholder（非終態），終態經 stageRealPayload 寫入。
    // Placeholder-only invariant: payload must be (!known && msg.isEmpty()).
    suspend fun completeWithRevision(tx: DualViewModel.PolicyTx, payload: PolicySettled): Boolean {
        require(!payload.known && payload.msg.isEmpty()) {
            "completeWithRevision only accepts placeholder payloads (!known && msg.isEmpty())"
        }
        var ok = false
        commitMutex.withLock {
            try {
                if (activeTx.compareAndSet(tx, null)) {
                    val rev = committedRevision.incrementAndGet()
                    val stamped = try {
                        payload.copy(rev = rev)
                    } catch (_: Throwable) {
                        payload
                    }
                    pendingPublish.set(stamped)
                    // Stage M3: stamp fence time with the payload (same lock).
                    try { pendingPublishAtMs.set(System.currentTimeMillis()) } catch (_: Throwable) { }
                    // Stage N3 P1#3: monotonic fence time + originating-claim owner.
                    try { pendingPublishAtNanos.set(System.nanoTime()) } catch (_: Throwable) { }
                    try { pendingOwner.set(tx) } catch (_: Throwable) { }
                    ok = true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                ok = false
            }
        }
        return ok
    }

    // P1-1 repair atomic claim: rev re-check merged into the SAME commitMutex
    // critical section as the CAS+bump+stage (no withCommitLock suspension
    // window between a lock-free revMoved read and claimForPublish). Rejects
    // when committedRevision != revBefore (concurrent B committed mid-IO),
    // so the repair success below can never publish a stale Known(empty) over
    // unseen B. revBefore==-1L (legacy/no-coordinator) skips the rev gate.
    suspend fun completeWithRevisionChecked(
        tx: DualViewModel.PolicyTx,
        payload: PolicySettled,
        revBefore: Long,
    ): Boolean {
        require(!payload.known && payload.msg.isEmpty()) {
            "completeWithRevisionChecked only accepts placeholder payloads (!known && msg.isEmpty())"
        }
        var ok = false
        commitMutex.withLock {
            try {
                if (revBefore != -1L && committedRevision.get() != revBefore) {
                    ok = false
                    return@withLock
                }
                if (activeTx.compareAndSet(tx, null)) {
                    val rev = committedRevision.incrementAndGet()
                    val stamped = try {
                        payload.copy(rev = rev)
                    } catch (_: Throwable) {
                        payload
                    }
                    pendingPublish.set(stamped)
                    try { pendingPublishAtMs.set(System.currentTimeMillis()) } catch (_: Throwable) { }
                    try { pendingPublishAtNanos.set(System.nanoTime()) } catch (_: Throwable) { }
                    try { pendingOwner.set(tx) } catch (_: Throwable) { }
                    ok = true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
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
            // Phase4: bound-full rejects (no arbitrary eviction); pending is
            // kept and null returned so the gate marks STALE and retries.
            if (!tryInsertClaimTombstone(cur.txId, cur.key, cur.rev)) return null
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
    // P3: paired rev/gen ordering — rev FIRST then gen. Volatile transitively
                // guarantees any reader seeing the new gen必见 new rev (gen read after
                // rev read still sees rev because rev write precedes gen write).
                try { orphanDropRev.updateAndGet { prev -> if (cur.rev > prev) cur.rev else prev } } catch (_: Exception) { }
                try { orphanDropGen.incrementAndGet() } catch (_: Exception) { }
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

    // Stage O1 P2#3 (availability): retire a stale REAL staged payload already
    // present in _latest. Caller holds commitMutex (pre-IO gates call inside
    // withCommitLock); suspend wrapper acquires the lock for out-of-gate callers.
    // Conditions (ALL required):
    // - pending is real (known || msg non-empty; placeholders handled by recover);
    // - _latest holds the EXACT same identity+rev (stageRealPayload wrote truth
    //   synchronously under the same lock, so a staged real is always present;
    //   this check prevents resurrecting an old event over a newer latest);
    // - stale proof: monotonic age >= threshold OR recorded owner done
    //   (deferred completed/cancelled). A still-running owner may still emit
    //   imminently; retire is idempotent (emitStaged re-applies latest) but we
    //   still require death/age proof to avoid racing a live publisher.
    // On retire: clear pending+owner (no tombstone — real identity completed and
    // is now truth; future re-claims use new txIds), bump retiredRealCount, return
    // the retired payload for watermark advance. Returns null when nothing retired.
    // NOTE: nowNanos is monotonic System.nanoTime(), never wall.
    fun retireStagedRealPresentInLatestLocked(nowNanos: Long, staleThresholdMs: Long): PolicySettled? {
        try {
            val cur = try { pendingPublish.get() } catch (_: Exception) { null } ?: return null
            val isPlaceholder = try { !cur.known && cur.msg.isEmpty() } catch (_: Exception) { false }
            if (isPlaceholder) return null
            val latestCur = try { _latest.value } catch (_: Exception) { null } ?: return null
            if (latestCur.txId != cur.txId || latestCur.key != cur.key || latestCur.rev != cur.rev) return null
            val stagedAt = try { pendingPublishAtNanos.get() } catch (_: Exception) { 0L }
            if (stagedAt == 0L) return null
            if (nowNanos < stagedAt) return null
            val ageMs = try { (nowNanos - stagedAt) / 1_000_000L } catch (_: Exception) { 0L }
            var ownerDone = false
            try {
                val own = pendingOwner.get()
                if (own != null && own.txId == cur.txId && own.key == cur.key) {
                    ownerDone = try { own.deferred.isCompleted || own.deferred.isCancelled } catch (_: Exception) { false }
                } else if (own == null) {
                    // P2-2: unknown owner must NOT bypass age. Legacy (no owner
                    // record) still retires once monotonic age proves staleness;
                    // age gate below enforces this (ownerDone stays false).
                    try {
                        logCoord(
                            "retire staged real without owner record txId=${cur.txId} " +
                                "rev=${cur.rev} ageMs=${ageMs} (requires age proof)",
                        )
                    } catch (_: Exception) {
                    }
                    ownerDone = false
                }
            } catch (_: Exception) {
            }
            if (ageMs < staleThresholdMs && !ownerDone) return null
            val cleared = try { pendingPublish.compareAndSet(cur, null) } catch (_: Exception) { false }
            if (!cleared) return null
            try {
                val own = try { pendingOwner.get() } catch (_: Exception) { null }
                if (own != null && own.txId == cur.txId && own.key == cur.key) {
                    pendingOwner.compareAndSet(own, null)
                }
            } catch (_: Exception) {
            }
            try { retiredRealCount.incrementAndGet() } catch (_: Exception) { }
            try {
                logCoord(
                    "retire staged real present-in-latest txId=${cur.txId} rev=${cur.rev} ageMs=${ageMs}",
                )
            } catch (_: Exception) {
            }
            return cur
        } catch (_: Exception) {
            return null
        }
    }

    suspend fun tryRetireStagedReal(nowNanos: Long, staleThresholdMs: Long): PolicySettled? =
        commitMutex.withLock { retireStagedRealPresentInLatestLocked(nowNanos, staleThresholdMs) }

    // Stage O1 P2#2 (lifecycle): owner-liveness is decided inline under
    // commitMutex at each call site (single-snapshot activeTx+ownerAlive+
    // deferred+pending/age, see DualViewModel init/async-reclaim/busy-waiter).
    // The former isLiveOwner/isLiveOwnerAtomic/isAbandonedClaim helpers were
    // zero-call dead code (torn lock-free reads / decorative tryLock) and are
    // removed; the init inline snapshot is retained as the single source.

    // Stage O1 P1-2 (overrun discard): locked placeholder-only discard for the
    // reconciler budget-overrun path. Runs under commitMutex as ONE atomic
    // observation (get + isPlaceholder + identity + CAS clear): a concurrent
    // stageRealPayload overwriting the placeholder between a lock-free get and
    // clear must NOT be cleared (same txId/key/rev real would be destroyed by
    // the old placeholder object). Conditions (ALL required, checked under lock):
    // - pending holds EXACTLY txId+key and is still a placeholder
    //   (!known && msg empty); staged reals are NEVER touched here (left to
    //   retireStagedRealPresentInLatestLocked/recoverStalePendingLocked).
    // On discard: clear pending+owner, persist the placeholder identity tombstone
    // (same as recover: late resumers with this identity must re-claim, never
    // borrow current rev), bump orphanDropGen (no _latest event, readers must
    // fail closed), return true. False when nothing discarded (caller keeps its
    // local settle=false/unresolved semantics; the fence stays for retire/recover).
    // Phase4 bound: when the tombstone table is full the discard is REFUSED
    // (pending+owner kept, false returned, no arbitrary eviction) — a
    // tombstone-less drop would let the late resumer resurrect the identity.
    // Tombstone discipline: placeholder drops tombstone; real retires never
    // tombstone (real identity completed into truth).
    suspend fun discardOverrunPlaceholder(txId: Long, key: String): Boolean =
        commitMutex.withLock {
            val cur = try { pendingPublish.get() } catch (_: Exception) { null }
                ?: return@withLock false
            if (cur.txId != txId || cur.key != key) return@withLock false
            val isPlaceholder = try { !cur.known && cur.msg.isEmpty() } catch (_: Exception) { false }
            if (!isPlaceholder) return@withLock false
            // Capacity first (before the destructive CAS): bound-full keeps
            // the fence so a later retire/recover can still observe it.
            if (!tryInsertClaimTombstone(cur.txId, cur.key, cur.rev)) return@withLock false
            val cleared = try { pendingPublish.compareAndSet(cur, null) } catch (_: Exception) { false }
            if (!cleared) return@withLock false
            try {
                val own = try { pendingOwner.get() } catch (_: Exception) { null }
                if (own != null && own.txId == cur.txId && own.key == cur.key) {
                    pendingOwner.compareAndSet(own, null)
                }
            } catch (_: Exception) {
            }
            // Paired rev/gen ordering: rev FIRST then gen (volatile transitive:
            // seeing new gen必见 new rev).
            try { orphanDropRev.updateAndGet { prev -> if (cur.rev > prev) cur.rev else prev } } catch (_: Exception) { }
            try { orphanDropGen.incrementAndGet() } catch (_: Exception) { }
            try {
                logCoord(
                    "discard overrun placeholder txId=${cur.txId} rev=${cur.rev} (budget overrun, no real)",
                )
            } catch (_: Exception) {
            }
            true
        }

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
                // Check failure (isTombstoned throwing) is fail-closed: reject,
                // never pass (isTombstoned itself returns true on Throwable;
                // this explicit guard covers CE/throwing paths).
                val tombstoned: Boolean = try {
                    isTombstoned(payload.txId, payload.key)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    true
                }
                if (tombstoned) {
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
                    val canSetLatest = try {
                        tryUpdateLatestMonotonic(stamped)
                    } catch (_: Exception) { false }
                    if (canSetLatest) {
                        pendingPublish.set(stamped)
                        // Stage M3/N3: refresh fence time on overwrite (same lock).
                        try { pendingPublishAtMs.set(System.currentTimeMillis()) } catch (_: Exception) { }
                        try { pendingPublishAtNanos.set(System.nanoTime()) } catch (_: Exception) { }
                        out = stamped
                    } else {
                        try {
                            if (pendingPublish.compareAndSet(cur, null)) {
                                clearPendingOwnerIfMatch(cur.txId, cur.key)
                            }
                        } catch (_: Exception) {
                        }
                        val latestCur = try { _latest.value } catch (_: Exception) { null }
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
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
        // via emit, even with a fresh synthetic rev. Check failure is fail-closed
        // (reject, never pass): an unverifiable tombstone read must not emit.
        try {
            if (isTombstoned(payload.txId, payload.key)) {
                emitSuperseded.incrementAndGet()
                logCoord(
                    "emit rejected (tombstoned) txId=${payload.txId} key=${payload.key} " +
                        "rev=${payload.rev} (must re-claim)",
                )
                return
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emitSuperseded.incrementAndGet()
            logCoord(
                "emit rejected (tombstone check failure) txId=${payload.txId} key=${payload.key} " +
                    "rev=${payload.rev} (fail-closed, must re-claim)",
            )
            return
        }
        var toEmit = payload
        // Preserve claim rev when caller passed unstamped but pending holds it
        // (avoid double-bump via fresh increment).
        try {
            if (toEmit.rev == -1L) {
                val cur = try { pendingPublish.get() } catch (_: Exception) { null }
                if (cur != null && cur.txId == toEmit.txId && cur.key == toEmit.key && cur.rev != -1L) {
                    try { toEmit = toEmit.copy(rev = cur.rev) } catch (_: Exception) { }
                } else if (cur != null && (cur.txId != toEmit.txId || cur.key != toEmit.key)) {
                    emitSuperseded.incrementAndGet()
                    logCoord(
                        "emit rejected (foreign pending) txId=${toEmit.txId} " +
                            "by pending txId=${cur.txId} rev=${cur.rev}",
                    )
                    return
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
        // Overtake guard: stale (rev < latest) or -1L sentinel over positive
        // latest never overwrites _latest nor SharedFlow order — explicit
        // supersede, never silent. Stage N3 P1#3: equal rev from a DIFFERENT
        // identity is also stale (borrowed-rev resurrection). Atomic CAS loop.
        val updatedLatest = try {
            tryUpdateLatestMonotonic(finalEmit)
        } catch (_: Exception) {
            false
        }
        if (!updatedLatest) {
            val curLatest = try { _latest.value } catch (_: Exception) { null }
            emitSuperseded.incrementAndGet()
            logCoord(
                "emit superseded txId=${finalEmit.txId} rev=${finalEmit.rev} " +
                    "behind latest rev=${curLatest?.rev} txId=${curLatest?.txId}",
            )
            clearPendingAndOwnerIfMatch(finalEmit.txId, finalEmit.key, finalEmit.rev)
            return
        }
        var ok = false
        try {
            ok = tryEmitShared(finalEmit)
        } catch (_: Exception) {
            ok = false
        }
        if (ok) {
            clearPendingAndOwnerIfMatch(
                finalEmit.txId,
                finalEmit.key,
                finalEmit.rev,
                fallbackA = payload,
                fallbackB = finalEmit,
            )
            return
        }
        try {
            scope.launch {
                var delivered = false
                try {
                    delivered = withTimeoutOrNull(2_000L) { _settled.emit(finalEmit); true } ?: false
                } catch (ce: CancellationException) {
                    throw ce
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
                clearPendingAndOwnerIfMatch(
                    finalEmit.txId,
                    finalEmit.key,
                    finalEmit.rev,
                    fallbackA = payload,
                    fallbackB = finalEmit,
                )
            }
        } catch (_: Exception) {
        }
    }

    // Zero-call legacy: no production/test caller (overrun path uses
    // discardOverrunPlaceholder; normal paths settle via CAS). Kept for API
    // compatibility; do not use in new code.
    @Deprecated("Zero-call legacy; use discardOverrunPlaceholder/retire/recover instead.")
    fun clearPendingPublish(payload: PolicySettled) {
        // NOTE: no direct compareAndSet(payload, null) here — pendingPublish
        // holds the STAMPED copy (same txId/key/rev, different object identity
        // after completeWithRevision/stageRealPayload), so a reference-equality
        // CAS against the caller's unstamped payload would always fail. Clear
        // by identity below instead.
        // Rev-aware clear: pending may hold stamped (same txId/key/rev, different
        // draft/known). Clear when identity matches AND the current holder is
        // still a placeholder. P1-2: never clear a staged REAL by identity —
        // stageRealPayload overwrites the placeholder with a new object carrying
        // the SAME txId/key/rev; an overrun discard racing that overwrite must
        // abandon (leave to retire/recover), not destroy the real.
        try {
            val cur = try { pendingPublish.get() } catch (_: Exception) { null }
            if (cur != null && cur.txId == payload.txId && cur.key == payload.key &&
                (cur.rev == payload.rev || payload.rev == -1L)
            ) {
                val curIsPlaceholder = try { !cur.known && cur.msg.isEmpty() } catch (_: Exception) { false }
                if (!curIsPlaceholder) return
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
        // Stage N3: tombstoned identity never emits. Check failure is fail-closed
        // (reject, never pass): an unverifiable tombstone read must not emit.
        try {
            if (isTombstoned(s.txId, s.key)) {
                emitSuperseded.incrementAndGet()
                logCoord(
                    "tryEmit rejected (tombstoned) txId=${s.txId} key=${s.key} rev=${s.rev}",
                )
                return false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emitSuperseded.incrementAndGet()
            logCoord(
                "tryEmit rejected (tombstone check failure) txId=${s.txId} key=${s.key} rev=${s.rev}",
            )
            return false
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
        val updatedLatest = try {
            tryUpdateLatestMonotonic(stamped)
        } catch (_: Exception) {
            false
        }
        if (!updatedLatest) {
            val curLatest = try { _latest.value } catch (_: Exception) { null }
            emitSuperseded.incrementAndGet()
            logCoord(
                "tryEmit superseded txId=${stamped.txId} rev=${stamped.rev} " +
                    "behind latest rev=${curLatest?.rev}",
            )
            return false
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    // Stage I1 (P2#2)：exactly-once 結算 CAS——activeTx(tx→null) 恰一勝者。
    // 勝者才發布終態＋結清；敗者 early-return（不發布、不碰勝者 fence）。
    // settlePolicyTx 經此做 coordinator 側 claim（claim 先行，免先讀後 emit 窗口）。
    fun tryCompleteClaim(tx: DualViewModel.PolicyTx): Boolean = try {
        activeTx.compareAndSet(tx, null)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
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
            tombstoneRejects.set(0L)
        } catch (_: Exception) {
        }
        try {
            orphanDropGen.set(0L)
        } catch (_: Exception) {
        }
        try {
            orphanDropRev.set(-1L)
        } catch (_: Exception) {
        }
        try {
            retiredRealCount.set(0L)
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
        // P3: best-effort single release, never a while-unlock loop (a loop can
        // release locks held by a concurrent writer and corrupt the count).
        try {
            if (writeMutex.isLocked) {
                try { writeMutex.unlock() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            commitLockFailForTests = null
        } catch (_: Exception) {
        }
        try {
            if (commitMutex.isLocked) {
                try { commitMutex.unlock() } catch (_: Exception) { }
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
