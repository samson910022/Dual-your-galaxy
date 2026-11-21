// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 round 2 / N05: BBC 開關連點併發收斂 (single latest-intent actor).
//
// EN: rapid CheckBox taps used to launch one IO coroutine per tap. The store's
// internal lock serialises *commits*, but not *user-intent order*, so an older
// write could land last and the UI could show OFF while storage was ON. This
// controller funnels every tap through ONE conflated channel + ONE consumer,
// so commits follow intent order for the surviving intents, and a generation
// fence drops the result of any intent superseded while its commit was in
// flight. Intermediate intents may be coalesced — that is the point.
//
// 中文：連點曾經每次點擊都起一條 IO coroutine；store 的鎖只保證「寫入」互斥，
// 不保證「使用者意圖順序」，舊寫入可能最後落地，造成 UI 顯示 OFF、儲存卻是 ON。
// 本控制器把每次點擊送進單一 CONFLATED channel + 單一 consumer，讓「存活下來的
// 意圖」依序提交；generation 柵欄則作廢提交期間被新意圖取代的舊結果。中間意圖
// 被合併是預期行為。
//
// Framework-free on purpose (no Android imports): only kotlinx.coroutines,
// which the app already ships. Dispatchers are constructor seams so JVM tests
// can drive everything on a single TestScheduler with no Robolectric.
package com.galaxydual.plus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BbcInjectionToggleController(
    private val scope: CoroutineScope,
    /** Commit seam; returns true iff the store accepted the value (failure authority). */
    private val store: suspend (Boolean) -> Boolean,
    /** Initial persisted value, also the first "last known good". */
    initialValue: Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Result handlers (checkbox restore / toast) always run here; Main.immediate in prod. */
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Put the CheckBox back to the last confirmed value (Activity guards re-entry). */
    private val onRestored: (Boolean) -> Unit,
    /** Best-effort UI toast on a rejected/throwing commit; never on cancellation. */
    private val onFailed: (() -> Unit)? = null,
) {
    // lastGood = last value the store confirmed for a NON-stale intent (or the
    // initial read); a failed commit must never advance it. persistedValue = the
    // ACTUAL disk truth after ANY successful commit, including stale ones — a
    // stale commit still landed in storage even though its UI result is dropped.
    // A failure rollback must use persistedValue, not lastGood, or the CheckBox
    // would be restored to a value that disagrees with storage.
    // @Volatile only to keep the JVM test accessors honest; production writes are
    // serialised on the single actor (results applied on main, taps from main).
    @Volatile
    private var lastGood: Boolean = initialValue

    @Volatile
    private var persistedValue: Boolean = initialValue

    // Monotonically increasing, bumped ONLY by user intents (never by commits).
    @Volatile
    private var generation: Long = 0L

    // CONFLATED: a burst keeps just the newest tap; one consumer drains it.
    private val intents = Channel<Boolean>(Channel.CONFLATED)

    private val job: Job = scope.launch {
        for (intent in intents) {
            // Capture the generation of the *surviving* intent. If several taps
            // were coalesced, this equals the newest tap's generation.
            val gen = generation

            val ok = try {
                withContext(io) { store(intent) }
            } catch (ce: CancellationException) {
                // dispose()/scope cancellation: propagate and kill the actor.
                // Do NOT report a failure toast or restore a dying Activity.
                throw ce
            } catch (_: Throwable) {
                // Non-cancellation store failure (e.g. binder RemoteException)
                // is the same as commit(false): take the failure path below.
                false
            }

            // Disk truth advances on EVERY successful commit, BEFORE the
            // generation fence below. A stale commit was dropped for the UI,
            // but it really did write to storage; a later failure rollback
            // must reflect that value.
            if (ok) persistedValue = intent

            withContext(main) {
                // Stale result: a newer tap arrived while this commit ran.
                // Dropping it is what stops an older write from winning the UI.
                if (gen != generation) return@withContext

                if (ok) {
                    lastGood = intent
                } else {
                    // Restore the ACTUAL persisted value (which may come from a
                    // stale-but-successful commit), not lastGood and not the
                    // value that just failed.
                    onRestored(persistedValue)
                    onFailed?.invoke()
                }
            }
        }
    }

    /** Called from the CheckBox listener; order of calls is the user-intent order. */
    fun onToggle(checked: Boolean) {
        generation++
        intents.trySend(checked)
    }

    /** Cancel the single consumer (Activity.onDestroy, or dialog rebuild). */
    fun dispose() {
        job.cancel()
        intents.close()
    }

    // --- JVM-test seams (internal, same module) -------------------------------
    internal fun currentLastGood(): Boolean = lastGood

    internal fun currentGeneration(): Long = generation

    internal fun isActorActive(): Boolean = job.isActive
}
