// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage G2 (P2#4#5):跨 VM 最小 coordinator。App-scoped（Application 或
// Activity-retained holder），activeTx 為唯一跨 VM 真相；VM onCleared 不清除，
// 新 VM init 認領（繼承 fence+RECONCILING+pending+Unknown + 啟動 reconciler join
// 同一 deferred）。
package com.galaxydual.plus

import java.util.concurrent.atomic.AtomicBoolean
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

// A2-1: tombstone 表硬上限（claimTombstones 滿時逐出最舊-rev tombstone，見
// tryInsertClaimTombstone）。top-level internal：coordinator 與測試共用單一常數。
internal const val CLAIM_TOMBSTONE_MAX = 512

class PolicyTxCoordinator internal constructor(
    // PR #74 R6-2 (G02):app-private 維修提示持久化 store。
    // null (預設) = 純記憶體行為,保持既有 60+ 測試呼叫點完全不變；
    // 生產由 GalaxyApp 注入 SharedPreferences-backed store。
    private val repairHintStore: RepairHintStore? = null,
) {
    // 跨 VM 唯一真相：仍在途的逾時交易（null=無在途交易）。
    // VM handoverTx 登記時同步寫入本地 activePolicyTx＋本欄；終態發布前先經
    // tryClaim（見 DualViewModel.tryClaimTxForPublish）做 settle-CAS 先行、
    // 勝者才發布；onCleared/dispose 刻意不清除，新 VM init 認領。
    // Stage H1：所有權前置——confirm/clear 在 opMutex 內、store IO 前同步 CAS
    // claim（LAZY async，失敗即 BUSY/IN_PROGRESS 不啟 IO），使 pre-timeout
    // SAVING 即在 coordinator 可見，新 VM 可認領（RECONCILING＋Unknown＋
    // pending＋世代bump＋reconciler join 同一 deferred）。
    val activeTx = AtomicReference<DualViewModel.PolicyTx?>(null)

    // S4 process-lifetime fence (PR#60 fix round): process-lifetime sticky "曾經驗證
    // Known" indicator, owned by the app-scoped coordinator (GalaxyApp), NOT the
    // per-Activity ViewModel. MONOTONIC: never reset to null within the process;
    // a new process = a new coordinator = null (classic double-tap semantics restored).
    // S4 行程級圍欄（PR#60 修正輪）：行程級「曾經驗證 Known」黏性指示器，由
    // app-scoped coordinator（GalaxyApp）持有，而非每 Activity 的 ViewModel。
    // 單調（MONOTONIC）：行程內永不重置為 null；新行程＝新 coordinator＝null
    //（恢復經典雙擊語義）。
    val lastKnownPolicyDraft = AtomicReference<Set<String>?>(null)

    // Stage I1 (P1#1)：Tx 全域身份——App-scoped 單調計數器，跨 VM 唯一。
    // VM 本地 policyTxId 隨 VM 重建歸零（A→重建→B 皆取 1，碰撞致 settled 誤配），
    // 故 coordinator 側統一發號；DualViewModel 四處建 Tx 皆經 nextTxId()（無
    // coordinator 舊測試路徑才回退本地計數器）。
    private val globalTxId = AtomicLong(0L)

    fun nextTxId(): Long = globalTxId.incrementAndGet()

    // PR #74 R5-1 (F02): app-scoped, process-local hook-restart hint owned by
    // this App-scoped coordinator (GalaxyApp singleton in production), NOT by
    // any per-Activity ViewModel. Set by DualViewModel.noteInvalidVersionRepairAfterCommit
    // after a SUCCESSFUL commit repaired an Invalid version key (Invalid -> v1).
    // Durable until an explicit user acknowledgement (tap the hint TextView):
    // a later unrelated successful write never lowers it, and VM recreation /
    // Activity navigation never clears it. This is app-local ADVICE ONLY — never
    // proof that the hook process applied the repaired policy (DEVICE-UNVERIFIED).
    // PR #74 R6-2 (G02): the marker is mirrored into app-private prefs; a NEW
    // coordinator in a restarted app process seeds this live StateFlow from the
    // persisted marker AT CONSTRUCTION (before any VM attaches). No hook PID /
    // high-water re-derivation is attempted (no IPC surface; DEVICE-UNVERIFIED).
    // #76: in-memory `persisted.active` is NOT evidence of a durable save — the
    // separate [persistedDurable] flag gates the idempotent early-return.
    private val _repairHint = MutableStateFlow(false)
    val repairHint: StateFlow<Boolean> = _repairHint.asStateFlow()

    // In-process mirror of the persisted marker. generation is monotonic across
    // process restarts; repairedVersion records the version the repair commit wrote
    // (-1 when the caller does not supply one, as in current production).
    private var persisted: RepairHintState = RepairHintState(false, 0L, -1L)

    // #76: false = the last write's commit(false)/exception or a recovered load
    // left durability UNKNOWN. markRepairHint must then RETRY instead of returning.
    // PR #89: flipped to false INSIDE the same fused decision block that issues
    // the ticket, so a concurrent mark can never observe a stale
    // `active && durable` and early-return while an older write is still pending.
    private var persistedDurable = true

    // Monotonic ticket. PR #89: mutated ONLY inside the single fused decision
    // critical section (markRepairHint / applyRepairHintClear). No second monitor
    // ever issues a ticket, so a ticket can no longer be *later* than the intent
    // it represents.
    private var repairHintWriteSeq = 0L

    // Serializes hint commits without holding the coordinator monitor (#76).
    // Phase C will replace this with an async worker; the physical save stays
    // outside the coordinator monitor either way.
    private val repairHintIoLock = Any()

    // PR #89: immutable write intent returned by the fused decision. The ticket
    // and the candidate state are captured together and never re-read from
    // mutable coordinator fields by the writer that performs the IO.
    private data class RepairHintWrite(val ticket: Long, val state: RepairHintState)

    // F6: MutableStateFlow.value notifies collectors synchronously. Normally do
    // NOT write it while holding the coordinator monitor: a reentrant collector
    // could otherwise nest a decision (and its IO) under `this`. Publication
    // happens after the decision block, guarded by a SEPARATE lock with a
    // monotonic ticket check so an older ticket cannot overwrite a newer
    // publication. The only work done under this lock is the StateFlow write
    // itself; save() always runs later, under repairHintIoLock (never under this
    // lock).
    //
    // DELIBERATE CARVE-OUTS (both under `this`, neither goes through this lock):
    //  * the fail-safe re-raise in persistRepairHint's outcome fence, which must
    //    raise the flow when the NEWEST save reports unknown durability;
    //  * resetForTest(), a JVM-only process-lifetime reset that takes `this` then
    //    this lock in that fixed order (no path takes the publish lock then
    //    `this`, so there is no ABBA cycle).
    private val repairHintPublishLock = Any()
    private var lastPublishedRepairHintTicket = -1L

    // JVM-only hook at ENTRY, BEFORE taking repairHintPublishLock, so a parked
    // first publisher cannot block a newer publisher (which must still be able
    // to publish). The monotonic ticket check inside publishRepairHint is what
    // keeps the parked older publisher from overwriting it on release.
    internal var beforeRepairHintPublishSeamForTests: (() -> Unit)? = null

    private fun publishRepairHint(intent: RepairHintWrite) {
        beforeRepairHintPublishSeamForTests?.invoke()
        synchronized(repairHintPublishLock) {
            if (intent.ticket <= lastPublishedRepairHintTicket) return
            lastPublishedRepairHintTicket = intent.ticket
            _repairHint.value = intent.state.active
        }
    }

    // PR #89 regression seam: located in the CALLER, immediately after the fused
    // decision returns and BEFORE publish/save. If anyone reintroduces a split
    // where the ticket is issued later inside persistRepairHint, this seam once
    // again sits BEFORE ticket issuance and the two-thread ordering test that
    // uses it fails. null in production.
    internal var afterRepairHintDecisionSeamForTests: (() -> Unit)? = null

    // PR4 review (Required 1, follow-up): JVM-only hook parked AFTER the fused
    // decision (ticket issued) but BEFORE the IO lock is acquired. A two-thread
    // test uses it to pin the exact stale-write window the pre-save fence closes.
    // null in production.
    internal var beforeRepairHintIoLockSeamForTests: (() -> Unit)? = null

    init {
        val store = repairHintStore
        if (store == null) {
            // Explicit no-store mode (JVM tests / in-memory coordinator): nothing
            // was ever persisted, so there is nothing to verify. Keep the
            // historical behavior exactly — no warning, durable mirror=true.
            persistedDurable = true
        } else {
            val loaded = try {
                store.load()
            } catch (_: Throwable) {
                null
            }
            if (loaded != null) {
                // PR #89 Phase B (B2 hardening): UNKNOWN load => UNKNOWN-active,
                // even for a custom store that reports inactive + recovered (or
                // throws from loadWasRecovered()). Treat a throwing verdict as
                // recovered: absence of a durability certificate is never proof
                // of an inactive marker.
                val recovered = try {
                    store.loadWasRecovered()
                } catch (_: Throwable) {
                    true
                }
                if (recovered && !loaded.active) {
                    val seed = synchronized(this) {
                        persisted = loaded.copy(active = true)
                        persistedDurable = false
                        repairHintWriteSeq += 1L
                        RepairHintWrite(repairHintWriteSeq, persisted)
                    }
                    publishRepairHint(seed)
                } else {
                    persisted = loaded
                    persistedDurable = !recovered
                    if (loaded.active) _repairHint.value = true
                }
            } else {
                // PR #89 Phase B (B2): a NON-NULL store that threw on load is a
                // fail-closed condition, NOT the same as no store. The old code
                // left persisted=inactive and the flow hidden (fail-open) with
                // only a misleading "biased to warning" comment. Seed an
                // UNKNOWN-active mirror, mark durability UNKNOWN so later
                // mark/ack still retry, and publish the seed through the SAME
                // monotonic ticket mechanism as every other decision: bump the
                // seq under `this` so a later decision's ticket is strictly
                // newer and can still publish over this seed.
                val seed = synchronized(this) {
                    persisted = persisted.copy(active = true)
                    persistedDurable = false
                    repairHintWriteSeq += 1L
                    RepairHintWrite(repairHintWriteSeq, persisted)
                }
                publishRepairHint(seed)
            }
        }
    }

    // JVM-test seam: inspect the persisted marker without an injected store.
    @get:Synchronized
    internal val repairHintPersistedState: RepairHintState get() = persisted

    // JVM-test seam: real durability verdict (not the SharedPreferences fake's
    // loadWasRecovered() default). false after a failed/UNKNOWN save or a
    // recovered/corrupt load, true only once the newest write certified commit().
    @get:Synchronized
    internal val repairHintDurableForTests: Boolean get() = persistedDurable

    /**
     * Raise the app-scoped hint after a successful Invalid->v1 repair commit.
     * Idempotent: an ALREADY-DURABLE active marker never bumps
     * [RepairHintState.generation]; an active-but-unconfirmed marker retries the
     * same state instead of silently skipping the durable write (#76).
     *
     * PR #89: state mutation, ticket issuance, and the pending-durability flip
     * happen in ONE `synchronized(this)` block. The returned [RepairHintWrite]
     * is immutable: no later monitor can hand a *newer* ticket to an *older*
     * intent. The physical commit runs OUTSIDE the coordinator monitor, and
     * the live flow is published after the decision on a separate lock.
     */
    fun markRepairHint(repairedVersion: Long = -1L) {
        val intent = synchronized(this) {
            if (persisted.active && persistedDurable) {
                // Verified durable: keep the live truth raised, no generation
                // bump and no new write intent. The flow is already published
                // as active by the write that certified this state.
                null
            } else {
                val candidate = if (persisted.active) {
                    // Active in memory but never confirmed durable: retry verbatim.
                    persisted
                } else {
                    RepairHintState(
                        active = true,
                        generation = persisted.generation + 1L,
                        repairedVersion = repairedVersion,
                    )
                }
                // Fused decision: ticket, pending durability, and mirror all
                // change atomically with the candidate selection.
                repairHintWriteSeq += 1L
                val ticket = repairHintWriteSeq
                persistedDurable = false
                persisted = candidate
                RepairHintWrite(ticket, candidate)
            }
        }
        afterRepairHintDecisionSeamForTests?.invoke()
        if (intent == null) return
        publishRepairHint(intent)
        persistRepairHint(intent)
    }

    /** Explicit user acknowledgement. The ONLY production clear. */
    fun acknowledgeRepairHint() = applyRepairHintClear()

    // TEST-ONLY / FUTURE-IPC SEAM. Deliberately NEVER called in production.
    // A future binder contract would have to supply VERIFIED hook identity before
    // an identity-based auto-clear could be justified; nothing here claims any
    // on-device verification or observes the hook process's PID / high-water.
    internal fun clearOnVerifiedHookRebirth() = applyRepairHintClear()

    private fun applyRepairHintClear() {
        // Same fused decision as markRepairHint: the clear's ticket is issued
        // while its candidate is installed, never in a second monitor later.
        val intent = synchronized(this) {
            val candidate = persisted.copy(active = false)
            repairHintWriteSeq += 1L
            val ticket = repairHintWriteSeq
            persistedDurable = false
            persisted = candidate
            RepairHintWrite(ticket, candidate)
        }
        afterRepairHintDecisionSeamForTests?.invoke()
        publishRepairHint(intent)
        persistRepairHint(intent)
    }

    // #76: the durable write is deliberately OUTSIDE the coordinator monitor.
    // PR #89: the caller now supplies the immutable [intent] produced by the
    // fused decision; its ticket is used for BOTH the pre-save skip and the
    // post-save outcome fence. No ticket is ever issued here.
    private fun persistRepairHint(intent: RepairHintWrite) {
        val store = repairHintStore ?: return
        beforeRepairHintIoLockSeamForTests?.invoke()
        val durable = synchronized(repairHintIoLock) {
            // A writer whose ticket is already stale by the time it reaches this
            // IO lock must not touch the store at all — otherwise an older mark
            // could still land active=true ON TOP OF a newer ack's clear.
            // Re-read the seq under `this` for a happens-before-safe snapshot,
            // then release that monitor BEFORE save() so the physical commit
            // stays off it.
            if (synchronized(this) { intent.ticket != repairHintWriteSeq }) {
                false // stale: skip the physical commit entirely; do NOT touch store
            } else {
                try {
                    store.save(intent.state)
                } catch (_: Throwable) {
                    false
                }
            }
        }
        // A save result may arrive AFTER a newer write has already settled.
        // Applying such a stale result can resurrect an acknowledged hint
        // (A: mark/save(false) preempted after releasing the IO lock;
        // B: ack/save(true) settles; A then re-raises the warning).
        //
        // The ticket was issued under `this` in the fused decision, and the same
        // monitor is re-entered here to read the current seq and apply the
        // outcome as one atomic step. A stale result (ticket != seq) is ignored
        // in FULL: it touches neither [persisted], [persistedDurable], nor the
        // live flow that the newer write may already have published/certified.
        synchronized(this) {
            if (intent.ticket == repairHintWriteSeq) {
                if (!durable) {
                    // Unknown durability: never hide an already-raised warning,
                    // and keep the mirror active so the next mark retries. Only
                    // the newest ticket reaches this fence, so publishing here
                    // cannot overwrite a newer decision's flow value.
                    if (!persisted.active) {
                        persisted = persisted.copy(active = true)
                        // Documented carve-out to the "publish on publishLock"
                        // rule above: re-raise under `this` with the CURRENT
                        // ticket still installed. Routing this through
                        // publishRepairHint(intent) would be WRONG — the intent
                        // here is a failed clear (active=false), and its ticket
                        // was already published, so the monotonic check would
                        // skip it and an acknowledged hint would stay hidden.
                        _repairHint.value = true
                    }
                    persistedDurable = false
                } else {
                    // Only the newest write may certify the mirror as durable.
                    persistedDurable = true
                }
            }
        }
    }

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
    // Phase4 bound (hard cap): when the table is full, eviction of the
    // strictly-oldest-rev tombstone is preferred over a permanent write wedge
    // (A2-1). Rejections are reserved for the pathological case where no tombstone
    // older than the incoming rev exists (fence kept). Evicting an OLD entry can
    // never publish its stale identity: stageRealPayload rejects any claim whose
    // identity is not pendingPublish (tombstones only guard the synthetic-emit
    // path), and the surviving newest fences keep the recent drops protected.
    // Rev-gate: only a strictly older rev is evicted. Evictions counted in
    // [tombstoneEvictions]; un-evictable overflow rejections counted in
    // [tombstoneRejects]. Mutations happen EITHER under commitMutex (the
    // recoverStalePendingLocked / discardOverrunPlaceholder call sites run
    // with commitMutex held) OR via the lock-free drop path
    // dropStagedPlaceholderIfMatch (single pendingPublish CAS + the insert;
    // the oldest-rev eviction inside the insert uses a value-matched remove
    // so a lost eviction race is detected and refused — the table stays
    // consistent without the mutex). The map itself is a ConcurrentHashMap so
    // the lock-free isTombstoned fast-reject stays safe.
    // NOT @GuardedBy("commitMutex") exclusively: the lock-free drop path is
    // deliberately unlocked (see dropStagedPlaceholderIfMatch KDoc); reads
    // are lock-free everywhere.
    val claimTombstones = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Phase7 observability: tombstone bound-full rejections (drop refused,
    // fence kept). Monotonic, never reset except resetForTest.
    val tombstoneRejects = AtomicLong(0L)

    // A2-1 observability: tombstone evictions (oldest-rev evicted to make room).
    // Monotonic, never reset except resetForTest. See [tryInsertClaimTombstone].
    // Intended for metrics export (diagnostics surface) — see KDoc there.
    val tombstoneEvictions = AtomicLong(0L)

    // PR #60 C1.2 observability: tombstone rollbacks (lost pendingPublish CAS with
    // real payload staged in dropStagedPlaceholderIfMatch). Monotonic, never reset
    // except resetForTest. Intended for metrics export; never wired into correctness.
    val tombstoneRollbacks = AtomicLong(0L)

    // O1 (synthetic-emit fence): a monotonic count of UNSTAMPED (rev == -1)
    // synthetic revision bumps — emitStaged/tryEmitSettled taking their
    // synthetic-direct branch, or tryUpdateLatestMonotonic seeing a rev == -1
    // payload. No production caller may emit with rev == -1 (claimed payloads
    // only); this counter exists to detect contract violations and feed metrics
    // export. Log-once per process (stderr, tag only); stamped paths are
    // untouched.
    val syntheticUnstampedBumps = AtomicLong(0L)
    private val syntheticUnstampedBumpLogged = AtomicBoolean(false)

    private fun noteSyntheticUnstampedBump(where: String) {
        try {
            syntheticUnstampedBumps.incrementAndGet()
        } catch (_: Throwable) {
        }
        try {
            if (syntheticUnstampedBumpLogged.compareAndSet(false, true)) {
                logCoord(
                    "synthetic-unstamped-bump observed ($where): rev=-1 emit path taken — " +
                        "no production caller may emit/stage with rev == -1; claimed payloads only",
                )
            }
        } catch (_: Throwable) {
        }
    }

    // Phase4: bounded tombstone insert. True = recorded (drop may proceed);
    // false = table full AND no strictly-older tombstone to evict (caller keeps
    // fence / retries STALE). Re-recording an already-tombstoned identity only
    // refreshes its rev.
    // INVARIANT (no production caller may call emitStaged/tryEmitSettled with
    // rev == -1; claimed payloads only): the synthetic unstamped bump exists
    // solely so that out-of-band/test emitters and legacy paths stay ordered;
    // a rev == -1 payload reaching the emit path is off-contract and is counted
    // in [syntheticUnstampedBumps] (metrics-export intent, once-per-process log,
    // fail-safe ordering preserved). Tombstone eviction exists to bound the
    // drop fence set; tombstoneEvictions is INTENDED for metrics export (same
    // family: tombstoneRejects/emitSuperseded/syntheticUnstampedBumps) — export
    // it alongside poisonEvictCount/identityMismatchCount/backupFailureCount in
    // any diagnostics surface; it must never be wired into correctness.
    internal fun tryInsertClaimTombstone(txId: Long, key: String, rev: Long): Boolean {
        fun rejectWithCount(): Boolean {
            try {
                tombstoneRejects.incrementAndGet()
            } catch (_: Throwable) {
            }
            try {
                logCoord(
                    "tombstone bound full ($CLAIM_TOMBSTONE_MAX): no evictable (older) tombstone; " +
                        "reject drop txId=$txId (keep fence, pre-IO gate retries STALE)",
                )
            } catch (_: Throwable) {
            }
            return false
        }
        return try {
            val k = tombstoneKey(txId, key)
            // Single atomic lookup: if the identity is already tombstoned,
            // refresh its rev in place (same as the old containsKey+put, one
            // map traversal instead of two). putIfAbsent alone could not
            // refresh the rev, so computeIfPresent carries the refresh.
            if (claimTombstones.computeIfPresent(k) { _, _ -> rev } != null) {
                true
            } else if (claimTombstones.size >= CLAIM_TOMBSTONE_MAX) {
                val oldest = try {
                    claimTombstones.entries.minByOrNull { it.value }
                } catch (_: Throwable) {
                    null
                }
                if (oldest == null || oldest.value >= rev) {
                    // No strictly-older tombstone: keep the fence, reject.
                    rejectWithCount()
                } else {
                    val removed = try {
                        claimTombstones.remove(oldest.key, oldest.value)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!removed) {
                        // Lost a concurrent eviction race: fall back to reject (keep fence).
                        rejectWithCount()
                    } else {
                        try {
                            tombstoneEvictions.incrementAndGet()
                        } catch (_: Throwable) {
                        }
                        try {
                            logCoord(
                                "tombstone bound full ($CLAIM_TOMBSTONE_MAX): evicted oldest " +
                                    "${oldest.key} rev=${oldest.value} for txId=$txId rev=$rev",
                            )
                        } catch (_: Throwable) {
                        }
                        claimTombstones[k] = rev
                        true
                    }
                }
            } else {
                claimTombstones[k] = rev
                true
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    // PR #60 C1.2: value-matched tombstone rollback for the lock-free drop path.
    // Used when dropStagedPlaceholderIfMatch loses its pendingPublish CAS and
    // pendingPublish no longer holds the placeholder we tombstoned (a concurrent
    // stageRealPayload staged the real payload, or a recover/retire replaced or
    // cleared pending). Keeps the drop honest: the tombstone only fences the
    // identity we actually dropped a placeholder for. Same access discipline as
    // tryInsertClaimTombstone (lock-free ConcurrentHashMap, value-matched remove
    // so a concurrently refreshed rev survives); bounded (single map op).
    internal fun removeClaimTombstone(txId: Long, key: String, rev: Long): Boolean {
        return try {
            val removed = claimTombstones.remove(tombstoneKey(txId, key), rev)
            if (removed) {
                try {
                    tombstoneRollbacks.incrementAndGet()
                } catch (_: Throwable) {
                }
                try {
                    logCoord(
                        "tombstone rolled back txId=$txId rev=$rev " +
                            "(lost pendingPublish CAS with real payload staged; truth via _latest)",
                    )
                } catch (_: Throwable) {
                }
            }
            removed
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
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

    // Test-only seam, mirrors DualViewModel's internal test hooks: invoked
    // INSIDE commitMutex immediately AFTER the epoch + revision checks and
    // BEFORE the activeTx CAS. Production never sets it. JVM tests use it to
    // pin the linearization order of a claim against a concurrent adoption
    // bump. Keep null in production builds.
    internal var beforeClaimCasSeamForTests: (() -> Unit)? = null

    // Phase7 test seam (mirrors beforeClaimCasSeamForTests): invoked INSIDE
    // dropStagedPlaceholderIfMatch immediately AFTER the entry snapshot of
    // pendingPublish is read and BEFORE the placeholder check / tombstone
    // insert / pendingPublish CAS — JVM tests use it to pin the lost-CAS arm
    // deterministically against a concurrent placeholder→real (stage-won)
    // hand-off linearizing inside the drop's get→CAS window. Production never
    // sets it (null in production builds). Exceptions thrown by the seam are
    // swallowed; an Error is not (the outer catch in
    // dropStagedPlaceholderIfMatch is Exception-only) and propagates to the
    // caller.
    internal var beforeDropCasSeamForTests: (() -> Unit)? = null

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

    // Stage L3: monotonic _latest CAS; bounded retries (A3-12) — after 8
    // unsuccessful attempts (persistent concurrent CAS contention) the incoming
    // terminal is abandoned to the existing supersede/lossless-convergence path
    // (callers count emitSuperseded + log) instead of spinning forever.
    private fun tryUpdateLatestMonotonic(incoming: PolicySettled): Boolean {
        // O1 (synthetic-emit fence): an unstamped (rev == -1) payload reaching the
        // monotonic _latest CAS means a synthetic bump happened without a stamp
        // (or stamping failed) — count + log-once; stamped paths are untouched.
        if (incoming.rev == -1L) {
            noteSyntheticUnstampedBump("tryUpdateLatestMonotonic")
        }
        var guard = 0
        while (guard++ < 8) {
            val cur = _latest.value
            if (!canAdvanceLatest(cur, incoming)) return false
            if (_latest.compareAndSet(cur, incoming)) return true
        }
        try {
            logCoord(
                "tryUpdateLatestMonotonic abandoned after 8 CAS attempts txId=${incoming.txId} rev=${incoming.rev} (converge via latest/resync, never force-write)",
            )
        } catch (_: Exception) {
        }
        return false
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
    // Stage P2 (PR #60 NEW finding #2): expectedEpoch != -1L fences the claim on
    // the shared Tx.adoptionEpoch (the Tx-scoped cross-VM ownership token,
    // bumped inside commitMutex at every cross-VM adoption site, snapshotted at
    // attach); a mismatch consumes NOTHING (no CAS, no rev bump, no stage).
    // -1L = legacy/exempt (skip the epoch gate, e.g. same-VM handover /
    // fast-path claims).
    suspend fun completeWithRevision(
        tx: DualViewModel.PolicyTx,
        payload: PolicySettled,
        expectedEpoch: Long = -1L,
    ): Boolean {
        require(!payload.known && payload.msg.isEmpty()) {
            "completeWithRevision only accepts placeholder payloads (!known && msg.isEmpty())"
        }
        var ok = false
        commitMutex.withLock {
            try {
                if (expectedEpoch != -1L && tx.adoptionEpoch.get() != expectedEpoch) {
                    // EPOCH_STALE: no claim consumed — the adopter owns convergence.
                    return@withLock
                }
                // Seam exceptions are swallowed; a seam throwing Error maps to CAS_LOSS/no-consume upstream (fail-closed), never to a publish.
                try { beforeClaimCasSeamForTests?.invoke() } catch (_: Exception) { }
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

    // Stage L1 P1: verdict of the rev-gated atomic claim. CLAIMED = staged
    // under commitMutex (CAS won + revision bumped); REV_GATE_REJECTED = a
    // foreign B committed mid-IO (committedRevision != revBefore, observed
    // inside the same commitMutex critical section) so NO claim was consumed —
    // the terminal must settle as stage-rejected (fence/keys cleared, local
    // unknown), never publish; CAS_LOSS = the CAS to activeTx failed with the
    // rev gate satisfied — a live winner consumed the claim (loser handling
    // already applied inside the caller); the rev gate carries no information.
    // Stage P2 (PR #60 NEW finding #2): EPOCH_STALE = the Tx was ADOPTED by
    // another VM mid-IO (Tx.adoptionEpoch moved past expectedEpoch, observed
    // inside the same commitMutex critical section) so NO claim was consumed —
    // no CAS, no rev bump, no stage. Unlike REV_GATE_REJECTED an adopter IS a
    // live winner, so the dual-active caller maps EPOCH_STALE to the CAS_LOSS
    // arm (read-only loser-follow on the adopter's identity-matched terminal),
    // never publishes.
    enum class ClaimStagedVerdict { CLAIMED, REV_GATE_REJECTED, CAS_LOSS, EPOCH_STALE }

    // P1-1 repair atomic claim: rev re-check merged into the SAME commitMutex
    // critical section as the CAS+bump+stage (no withCommitLock suspension
    // window between a lock-free revMoved read and claimForPublish). Rejects
    // when committedRevision != revBefore (concurrent B committed mid-IO),
    // so the repair success below can never publish a stale Known(empty) over
    // unseen B. revBefore==-1L (legacy/no-coordinator) skips the rev gate.
    // Stage P2 (PR #60 NEW finding #2): expectedEpoch != -1L adds the Tx-scoped
    // adoption-epoch fence INSIDE the same critical section, alongside the rev
    // gate — an adopter that bumped Tx.adoptionEpoch after this worker's
    // attach-time snapshot makes the claim return EPOCH_STALE (consumes
    // nothing; the dual-active caller maps it to the CAS_LOSS/loser-follow
    // arm — no CAS, no rev bump, no publish). -1L skips the epoch gate.
    suspend fun completeWithRevisionCheckedVerdict(
        tx: DualViewModel.PolicyTx,
        payload: PolicySettled,
        revBefore: Long,
        expectedEpoch: Long = -1L,
    ): ClaimStagedVerdict {
        require(!payload.known && payload.msg.isEmpty()) {
            "completeWithRevisionChecked only accepts placeholder payloads (!known && msg.isEmpty())"
        }
        var verdict = ClaimStagedVerdict.CAS_LOSS
        commitMutex.withLock {
            try {
                if (expectedEpoch != -1L && tx.adoptionEpoch.get() != expectedEpoch) {
                    verdict = ClaimStagedVerdict.EPOCH_STALE
                    return@withLock
                }
                if (revBefore != -1L && committedRevision.get() != revBefore) {
                    verdict = ClaimStagedVerdict.REV_GATE_REJECTED
                    return@withLock
                }
                // Seam exceptions are swallowed; a seam throwing Error maps to CAS_LOSS/no-consume upstream (fail-closed), never to a publish.
                try { beforeClaimCasSeamForTests?.invoke() } catch (_: Exception) { }
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
                    verdict = ClaimStagedVerdict.CLAIMED
                } else {
                    verdict = ClaimStagedVerdict.CAS_LOSS
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                verdict = ClaimStagedVerdict.CAS_LOSS
            }
        }
        return verdict
    }

    // Boolean wrapper kept for existing call sites; new FAILED-terminal claim
    // paths use completeWithRevisionCheckedVerdict (the verdict carries the
    // reject reason from inside the lock — no post-hoc committedRevision
    // re-read is needed to distinguish rev-gate rejection from CAS loss).
    suspend fun completeWithRevisionChecked(
        tx: DualViewModel.PolicyTx,
        payload: PolicySettled,
        revBefore: Long,
        expectedEpoch: Long = -1L,
    ): Boolean =
        completeWithRevisionCheckedVerdict(tx, payload, revBefore, expectedEpoch) == ClaimStagedVerdict.CLAIMED

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
            // Phase4/A2-1: bound-full evicts the strictly-oldest-rev tombstone
            // (see tryInsertClaimTombstone); only when nothing older exists does
            // the caller keep the pending (null → gate marks STALE and retries).
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
    // Phase4/A2-1 bound: when the tombstone table is full the discard evicts the
    // strictly-oldest-rev tombstone (see tryInsertClaimTombstone) — strictly better
    // than a permanent fence; if nothing older exists the discard is REFUSED
    // (pending+owner kept, false returned).
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
                    // Tagging invariant: a terminal staged into _latest/pendingPublish
                    // must carry the claim's rev. The catch arm above passes the raw
                    // payload through; without this check an unstamped terminal could
                    // flow untagged (fail-closed via the outer catch — never publishes
                    // untagged).
                    require(stamped.rev == rev) {
                        "stageRealPayload: staged payload rev ${stamped.rev} != claim rev $rev (txId=${payload.txId})"
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
    // Error propagates intentionally (fail-loud): a JVM Error escaping this path
    // means an invariant broke and must crash the caller rather than be
    // silently absorbed (all Exception paths are already handled inside);
    // CancellationException is rethrown as before, never swallowed.
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
                noteSyntheticUnstampedBump("emitStaged")
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // A2-6: a failed async-launch must not be silent — count it as a drop
            // and log (lossless via _latest/resync either way; pending cleared by
            // the synchronous fast-path above when SharedFlow delivered, else the
            // identity stays fenced for recover/retire).
            try {
                emitTimeoutDrops.incrementAndGet()
            } catch (_: Exception) {
            }
            try {
                logCoord(
                    "emit launch failed txId=${finalEmit.txId} rev=${finalEmit.rev}: ${e.javaClass.name} " +
                        "(notifier dropped; converge via latest/resync)",
                )
            } catch (_: Exception) {
            }
        }
    }

    // A2-3: lock-free exact-identity placeholder drop for the VM's stage-rejected
    // terminal path (applyStageRejectedUnderLock), which runs under the VM's leaf
    // policySettleLock and may not suspend. Single-shot discipline, mirroring
    // discardOverrunPlaceholder exactly-once semantics without holding commitMutex:
    // - pendingPublish must hold EXACTLY (txId, key) and still be a placeholder
    //   (!known && msg empty) — foreign/real/cleared pending → false, untouched;
    // - tombstone is inserted first (capacity-gated, keeps the fence when full);
    // - the drop itself is a single pendingPublish CAS — a concurrent stageRealPayload
    //   /recover/retire winning the race invalidates our snapshot → false (its
    //   outcome stands; never two winners);
    // - on a won CAS: owner cleared, orphanDropRev/Gen bumped (paired order,
    //   rev before gen) — identical to the locked drop paths.
    // - on a LOST CAS: pendingPublish is re-read and _latest is sampled. The
    //   tombstone inserted above is rolled back ONLY on positive evidence that
    //   the placeholder→real hand-off won:
    //   (a) pendingPublish now holds a REAL payload (not placeholder shape:
    //       known || msg non-empty) with the same identity (txId/key/rev); or
    //   (b) _latest currently carries the same (txId, key, rev).
    //   A stage-won or _latest-matched rollback restores emit-ability. Why it
    //   matters: a concurrent stageRealPayload can stage the real payload S
    //   into _latest＋pendingPublish BEFORE our tombstone insert, so keeping
    //   the tombstone would make emitStaged(S) / tryEmitSettled(S) reject S as
    //   tombstoned and S would never reach the _settled notifier — S's truth
    //   would survive only via _latest / retireStagedRealPresentInLatestLocked
    //   (which clears pending while preserving _latest truth for an age-gated
    //   stale real payload), never via a successful synthetic emit.
    //   A null re-read keeps the tombstone unless _latest positively carries
    //   the same (txId,key,rev) — that combination is reachable only when the
    //   stage won and the real payload was subsequently retired (retire never
    //   tombstones), so rolling back our own insert is safe; the locked-path-
    //   keep guarantee applies when _latest does not match. A null re-read
    //   with a non-matching _latest is indistinguishable from a locked path
    //   (recoverStalePendingLocked /
    //   discardOverrunPlaceholder) having ALREADY tombstoned the identity —
    //   rolling back there would re-open it for the isTombstoned fast-reject
    //   in emitStaged/tryEmitSettled. A locked-path tombstone MUST stay. The
    //   stillPlaceholder arm likewise keeps the tombstone (an over-broad fence
    //   is the deliberate fail-closed bias, bounded by the claim-tombstone
    //   eviction cap); only the two positive-evidence arms roll back.
    //   latestMatches deliberately does not require curAfter != null for that
    //   reason.
    //   Rollback is a claimTombstones remove(key, rev), value-matched so a
    //   concurrently refreshed rev survives.
    // Placeholders carry no truth (_latest untouched), so a won lock-free drop
    // can never publish or destroy a real payload.
    internal fun dropStagedPlaceholderIfMatch(txId: Long, key: String): Boolean = try {
        val cur = try { pendingPublish.get() } catch (_: Exception) { null }
        // Phase7 test seam: invoked after the entry snapshot is read and
        // before any tombstone/CAS decision — models a concurrent
        // placeholder→real flip linearizing inside the get→CAS window (the
        // lost-CAS arm's reason for existing). Production never sets it.
        try { beforeDropCasSeamForTests?.invoke() } catch (_: Exception) { }
        if (cur == null || cur.txId != txId || cur.key != key) {
            false
        } else {
            val isPlaceholder = try { !cur.known && cur.msg.isEmpty() } catch (_: Exception) { false }
            if (!isPlaceholder) {
                false
            } else if (!tryInsertClaimTombstone(cur.txId, cur.key, cur.rev)) {
                // Capacity: keep the fence (STALE retry) — same as the locked path.
                false
            } else if (!pendingPublish.compareAndSet(cur, null)) {
                // Lost the race. Roll the tombstone back ONLY on positive
                // evidence the placeholder→real hand-off won: (a) pendingPublish
                // now holds a REAL payload (not placeholder shape) with the same
                // (txId,key,rev), or (b) _latest carries the same (txId,key,rev).
                // A null re-read keeps the tombstone unless _latest positively
                // carries the same (txId,key,rev) — that combination is reachable
                // only when the stage won and the real payload was subsequently
                // retired (retire never tombstones), so rolling back our own
                // insert is safe; the locked-path-keep guarantee applies when
                // _latest does not match. A null re-read with a non-matching
                // _latest is indistinguishable from a locked path
                // (recoverStalePendingLocked/discardOverrunPlaceholder) that
                // ALREADY tombstoned this identity — rolling back there would
                // re-open it for the isTombstoned fast-reject, so the tombstone
                // stays. The stillPlaceholder arm likewise keeps it (over-broad
                // fence; deliberate fail-closed bias). A stage-won or
                // _latest-matched rollback restores emit-ability so the real
                // payload can still reach the _settled notifier.
                // latestMatches deliberately does not require curAfter != null
                // for that reason.
                val curAfter = try { pendingPublish.get() } catch (_: Exception) { null }
                val stillPlaceholder = curAfter != null &&
                    curAfter.txId == cur.txId && curAfter.key == cur.key &&
                    (try { !curAfter.known && curAfter.msg.isEmpty() } catch (_: Exception) { false })
                val realHandOff = !stillPlaceholder && curAfter != null &&
                    curAfter.txId == cur.txId && curAfter.key == cur.key &&
                    curAfter.rev == cur.rev &&
                    (try { curAfter.known || curAfter.msg.isNotEmpty() } catch (_: Exception) { false })
                val latestMatches = !stillPlaceholder && (try {
                    val lat = _latest.value
                    lat != null && lat.txId == cur.txId && lat.key == cur.key && lat.rev == cur.rev
                } catch (_: Exception) { false })
                if (realHandOff || latestMatches) {
                    removeClaimTombstone(cur.txId, cur.key, cur.rev)
                }
                false
            } else {
                try {
                    val own = try { pendingOwner.get() } catch (_: Exception) { null }
                    if (own != null && own.txId == cur.txId && own.key == cur.key) {
                        pendingOwner.compareAndSet(own, null)
                    }
                } catch (_: Exception) {
                }
                try { orphanDropRev.updateAndGet { prev -> if (cur.rev > prev) cur.rev else prev } } catch (_: Exception) {
                }
                try { orphanDropGen.incrementAndGet() } catch (_: Exception) {
                }
                try {
                    logCoord(
                        "drop staged placeholder txId=${cur.txId} rev=${cur.rev} (stage rejected, no real payload)",
                    )
                } catch (_: Exception) {
                }
                true
            }
        }
    } catch (_: Exception) {
        false
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
                    noteSyntheticUnstampedBump("tryEmitSettled")
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
        } catch (ce: CancellationException) {
            throw ce
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
            tombstoneEvictions.set(0L)
        } catch (_: Exception) {
        }
        try {
            tombstoneRollbacks.set(0L)
        } catch (_: Exception) {
        }
        try {
            syntheticUnstampedBumps.set(0L)
        } catch (_: Exception) {
        }
        try {
            syntheticUnstampedBumpLogged.set(false)
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
        // test-only reset restores the fresh-process fence contract; production never
        // calls resetForTest (the S4 process-lifetime fence stays monotonic in production).
        try {
            lastKnownPolicyDraft.set(null)
        } catch (_: Exception) {
        }
        // PR #74 R6-2 (G02): reset the repair marker too, so JVM tests stay isolated
        // (generation back to 0, repairedVersion back to -1, live hint false).
        try {
            // PR #89 P3: the whole repair-hint reset must be atomic against an
            // in-flight writer. Hold `this` FIRST, then the publish lock, and
            // never take them in the opposite order anywhere else (the decision
            // block releases `this` before publishing; no path takes publishLock
            // then `this`). An in-flight writer's stale ticket then fails both
            // the pre-save and post-save seq fences against the reset seq=0.
            synchronized(this) {
                synchronized(repairHintPublishLock) {
                    persisted = RepairHintState(false, 0L, -1L)
                    lastPublishedRepairHintTicket = -1L
                    _repairHint.value = false
                    // PR4 review (Optional 3): a fresh test coordinator must also
                    // forget any prior write generation/durability verdict,
                    // otherwise a leftover writeId could make the next persist
                    // look stale (or look verified).
                    persistedDurable = true
                    repairHintWriteSeq = 0L
                    repairHintStore?.save(persisted)
                }
            }
        } catch (_: Exception) {
            // Best effort.
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
