// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 2 / N05 + round 3 / D05 + D04: BBC 開關連點併發收斂 / 失敗誠實.
//
// EN: rapid CheckBox taps used to launch one IO coroutine per tap, and round-2's
// single per-controller actor still died on every dialog rebuild. D05 promotes
// the serializer to the app-scoped [BbcToggleCoordinator]: the sequence is
// assigned at user-intent time from the shared state, and the single global
// consumer serializes commits across controller lifetimes. This controller now
// only owns UI state (lastGood / persistedValue / a local sequence fence) and
// detaches its callbacks on dispose; it never cancels an in-flight write.
// D04: a failed commit is UNKNOWN, not a rollback. Neither the local mMap nor
// persistedValue is an authority after commit(false)/throw, so the CheckBox is
// left at the user's last intent and onUnknown() marks the effect unverified.
// This is NOT a Samsung cold-start deletion guard (issue #75).
//
// 中文：連點曾經每次點擊都起一條 IO coroutine；round-2 的單一 actor 又會在對話框
// 重建時被銷毀。D05 把序列化器提升為 app 層級的 BbcToggleCoordinator：序號在使用者
// 意圖產生時由共享 state 指派，全域唯一 consumer 負責跨 controller 生命週期的寫入
// 順序。本控制器只保留 UI 狀態（lastGood / persistedValue / 本地序號柵欄），dispose
// 只切斷 UI 回呼，不再取消進行中的提交。D04：提交失敗即 UNKNOWN，不得謊稱回滾；
// 本地 mMap 與 persistedValue 都不可作為失敗後的權威，CheckBox 保持使用者最後意圖，
// 由 onUnknown() 標示未驗證。此開關不是 Samsung 冷啟動刪除防護（issue #75）。
//
// Framework-free on purpose (no Android imports): only kotlinx.coroutines,
// which the app already ships. Dispatchers are constructor seams so JVM tests
// can drive everything without Robolectric.
package com.galaxydual.plus

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class BbcInjectionToggleController(
    /** Process-wide serializer; NOT owned here — survives dialog/Activity rebuilds. */
    private val coordinator: BbcToggleCoordinator,
    /** Initial persisted value, also the first "last known good". */
    initialValue: Boolean,
    /** Result handlers (checkbox restore / toast) always run here; Main.immediate in prod. */
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Put the CheckBox back to the last confirmed value (Activity guards re-entry). */
    private val onRestored: (Boolean) -> Unit,
    /** Best-effort UI toast on an Unknown commit; never on cancellation. */
    private val onFailed: (() -> Unit)? = null,
    /** D04: mark the CheckBox/row UNKNOWN; the effect cannot be verified here. */
    private val onUnknown: (() -> Unit)? = null,
    /**
     * E04: app-level effect status (StateFlow replay). Separate from the
     * per-intent onUnknown/onFailed toast path: this only drives row styling.
     * Defaults to null so existing call sites stay source-compatible.
     */
    private val onEffectStatus: ((BbcEffectStatus) -> Unit)? = null,
) {
    // lastGood = last value the store confirmed for a NON-stale intent (or the
    // initial read); a failed commit must never advance it. persistedValue is now
    // ONLY bookkeeping of the ACTUAL disk truth after ANY successful commit,
    // including stale ones — a stale commit still landed in storage even though
    // its UI result is dropped.
    // NOTE (D04): persistedValue is NEVER used after a failed commit. commit()
    // returning false or throwing may have optimistically polluted the local
    // RemotePreferences mMap, so neither that mMap nor this bookkeeping value is
    // an authority for a rollback. Unknown is reported via onUnknown() instead.
    // This is NOT a Samsung cold-start deletion guard (issue #75).
    // @Volatile only to keep JVM test accessors honest; production taps come
    // from Main, results are applied on Main, and the single global coordinator
    // consumer serializes commits.
    @Volatile
    private var lastGood: Boolean = initialValue

    @Volatile
    private var persistedValue: Boolean = initialValue

    // Local, per-UI generation (observability only). The GLOBAL ordering fence
    // is the sequence from the app-scoped BbcToggleRuntimeState.
    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var lastSubmittedSequence: Long = 0L

    @Volatile
    private var alive: Boolean = true

    // UI delivery job is local: it is cancelled on dispose so a queued callback
    // cannot touch a dead View. It is a CHILD of the coordinator's app scope,
    // so cancelling it never affects the shared write consumer.
    private val parentJob: Job? = coordinator.scope.coroutineContext[Job]
    private val uiJob = SupervisorJob(parent = parentJob)
    private val uiScope = CoroutineScope(coordinator.scope.coroutineContext + uiJob + main)

    init {
        // E04: exactly ONE app-level collector per controller. StateFlow replays
        // the latest applied mark on subscribe (so a rebuilt dialog learns the
        // outcome of an earlier commit), then forwards every later mark — even
        // one produced by a commit this controller never submitted. No "pending"
        // value is ever emitted from submit()/onToggle(), so D05 ordering is
        // untouched. dispose() cancels uiJob; the alive guard drops any racing
        // emission so a dead View is never touched.
        uiScope.launch {
            coordinator.state.effectStatus.collect { status ->
                if (alive) onEffectStatus?.invoke(status)
            }
        }
    }

    /** Called from the CheckBox listener; order of calls is the user-intent order. */
    fun onToggle(checked: Boolean) {
        if (!alive) return
        generation++
        // Sequence is assigned at USER-INTENT time from app-scoped state, so it
        // keeps increasing even when this controller is rebuilt.
        val seq = coordinator.state.nextSequence()
        lastSubmittedSequence = seq
        coordinator.submit(seq, checked) { outcome ->
            // Coordinator consumer thread: bookkeeping advances on EVERY
            // successful commit, BEFORE the fence below — a stale commit really
            // did write to storage. D04: this value is NEVER consulted for a
            // failed commit (the local mMap may be polluted).
            if (outcome is BbcWriteOutcome.Confirmed) persistedValue = outcome.value
            uiScope.launch {
                if (!alive) return@launch
                // Per-controller fence: a result superseded by a newer tap on
                // this UI must not move the CheckBox or fire a toast.
                if (seq != lastSubmittedSequence) return@launch
                when (outcome) {
                    is BbcWriteOutcome.Confirmed -> lastGood = outcome.value
                    BbcWriteOutcome.Unknown -> {
                        // D04: do NOT call onRestored(persistedValue) and do NOT
                        // claim a rollback. The CheckBox stays at the user's last
                        // intent; the holder/UI marks it as UNKNOWN instead.
                        onUnknown?.invoke()
                        onFailed?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Detach UI callbacks only (Activity.onDestroy / dialog rebuild).
     * The shared coordinator consumer and any in-flight commit are untouched.
     */
    fun dispose() {
        alive = false
        uiJob.cancel()
    }

    // --- JVM-test seams (internal, same module) -------------------------------
    internal fun currentLastGood(): Boolean = lastGood

    internal fun currentGeneration(): Long = generation

    internal fun isAlive(): Boolean = alive
}
