// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase C: framework-free JVM harness that mirrors the MainActivity
// consent decision flow against the real app-scoped holder/coordinator/controller.
// No Android, no Robolectric: every callback is driven by the injected
// dispatcher/scheduler so ordering is deterministic.
package com.galaxydual.plus

import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Test-only model of MainActivity's BBC toggle + consent dialog wiring. It uses
 * the PRODUCTION state machine, holder, coordinator and controller; only the
 * Android View/dialog plumbing is replaced by counters. `userToggle`, `accept`
 * and `cancel` reproduce the exact branch order from MainActivity.
 *
 * Both app collectors (holder state and effect status) feed ONE shared state
 * and call ONE projection -> ONE checkbox writer, exactly like MainActivity's
 * `renderToggle`/`setToggleChecked`. This is what makes the dual-collector bug
 * ("last emitter wins" / rebind restoring a user's OFF) observable here.
 *
 * [alive] models Activity/controller teardown: once false, late consent-write
 * callbacks and state emissions are DROPPED and cannot mutate UI state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BbcConsentTestHarness(
    val holder: BbcActivationConsentHolder,
    val coordinator: BbcToggleCoordinator,
    main: CoroutineDispatcher,
) {
    /** Effect-toggle intents that actually reached the controller (ON accept / OFF). */
    val toggleCalls: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    /** Consent dialog intents shown, in user-intent order. */
    val dialogsShown: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    /** Intent sequences whose consent write failed (dedupe target in Main). */
    val failureToasts: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    /** Inherited-Confirmed CheckBox rewrites observed on this UI. */
    val restored: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var alive: Boolean = true
        private set

    @Volatile
    var activeSeq: Long = 0L
        private set

    @Volatile
    var dialogPending: Boolean = false
        private set

    /** Counts Main's authorisation-state lookup (the blocking pref read in the old bug). */
    @Volatile
    var consentStateReads: Int = 0
        private set

    /** The last UI value we could honestly settle before the current dialog. */
    @Volatile
    var lastSettledChecked: Boolean = holder.currentState().toggleUi().checked ?: false
        private set

    /** R5-02: whether the latest projection rendered the row UNVERIFIED/neutral. */
    @Volatile
    var lastProjectionNeutral: Boolean = false
        private set

    // Shared axes fed by BOTH collectors; neither writes the checkbox directly.
    @Volatile
    private var currentAuthorization: BbcActivationConsentState = holder.currentState()

    @Volatile
    private var currentEffect: BbcEffectStatus = coordinator.state.effectStatus.value

    @Volatile
    private var pendingIntentChecked: Boolean? = null

    /** The app-consumer-owned enable this UI has already observed, at most once. */
    @Volatile
    private var recordedEnableSequence: Long = -1L

    /** The ONE checkbox write path, mirroring MainActivity's setToggleChecked. */
    private fun setToggleChecked(value: Boolean) {
        lastSettledChecked = value
    }

    /** The ONE projection, mirroring MainActivity's renderToggle. */
    private fun renderToggle() {
        if (!alive) return
        // P1-3: consume the APP-SCOPED latest user intent, not this View's
        // per-View local. A rebuild must keep an in-flight OFF even while the
        // effect axis replays an older Confirmed(true).
        val pendingIntent = coordinator.state.latestIntentProjectedValue() ?: pendingIntentChecked
        val projection = bbcToggleUiProjection(currentAuthorization, currentEffect, pendingIntent)
        lastProjectionNeutral = projection.neutral
        if (!dialogPending) projection.checked?.let(::setToggleChecked)
    }

    /** Mirrors MainActivity's renderToggle() call after recording a user intent. */
    fun render() {
        if (alive) renderToggle()
    }

    val controller = BbcInjectionToggleController(
        coordinator = coordinator,
        initialValue = lastSettledChecked,
        main = main,
        // F03: onRestored is NOT a no-op any more — it feeds the SAME projection
        // as the effect collector. The checkbox still has only one writer.
        onRestored = { confirmed ->
            restored.add(confirmed)
            renderToggle()
        },
        onFailed = {},
        onUnknown = {},
        onEffectStatus = { status ->
            currentEffect = status
            if (status is BbcEffectStatus.Confirmed && pendingIntentChecked == status.value) {
                pendingIntentChecked = null
            }
            renderToggle()
        },
    )

    private val uiScope = CoroutineScope(SupervisorJob() + main)

    init {
        // Mirrors MainActivity's lifecycle-aware consent-state collector. The
        // app-scoped StateFlow replays the current state to a rebuilt UI, which
        // is exactly how a rotated Activity learns about a grant that landed
        // while its callback was already dropped.
        uiScope.launch {
            holder.state.collect { state -> renderConsentState(state) }
        }
    }

    private fun renderConsentState(state: BbcActivationConsentState) {
        if (!alive) return
        currentAuthorization = state
        // Phase 2: the app-scoped consumer that confirmed the opt-in key owns
        // the phase-2 effect write. The UI only OBSERVES it (exactly once); it
        // never dispatches a second toggle from a StateFlow replay or a dead
        // Activity callback. The fallback token covers a holder used directly
        // without the coordinator consumer (legacy handoff tests).
        val alreadyEnabled = (currentEffect as? BbcEffectStatus.Confirmed)?.value == true
        if (state is BbcActivationConsentState.Granted && !dialogPending) {
            // The effect status may already have been marked Confirmed(true) by
            // the consumer's enable commit before this collector runs, so the
            // app-owned observation must NOT be gated on alreadyEnabled.
            if (holder.isEnableHandledBy(activeSeq) && recordedEnableSequence != activeSeq) {
                recordedEnableSequence = activeSeq
                toggleCalls.add(true)
            } else if (!alreadyEnabled &&
                holder.consumePendingEnableForReplay(holder.currentGeneration())
            ) {
                toggleCalls.add(true)
                controller.onToggle(true)
            }
        }
        renderToggle()
    }

    /** Rebuild a UI from a fresh View/Activity while keeping the app-scoped holder. */
    fun seedFromHolder() {
        currentAuthorization = holder.currentState()
        currentEffect = coordinator.state.effectStatus.value
        pendingIntentChecked = null
        setToggleChecked(currentAuthorization.toggleUi().checked ?: false)
        // P1-3: the rebuilt View must immediately project the app-scoped intent
        // (if any) before any Confirmed replay can repaint the box.
        renderToggle()
    }

    fun userToggle(checked: Boolean) {
        if (!alive) return
        // OFF short-circuits BEFORE any consent state read.
        if (!checked) {
            // Phase 2: unconditional APP-SCOPED supersession — a rebuilt UI
            // cannot lose the OFF just because its local sequence was 0.
            activeSeq = holder.supersedeActiveIntent()
            dialogPending = false
            controller.setConsentPending(false)
            pendingIntentChecked = false
            // P1-A: same ordering as MainActivity's OFF branch — the newest
            // value is recorded app-scoped BEFORE the first render.
            coordinator.state.noteLatestUserIntent(false)
            renderToggle()
            controller.onToggle(false)
            toggleCalls.add(false)
            return
        }
        consentStateReads++
        val state = holder.currentState()
        if (state.requiresConsentFor(true)) {
            val seq = holder.startConsentIntent()
            activeSeq = seq
            dialogPending = true
            controller.setConsentPending(true)
            dialogsShown.add(seq)
        } else {
            pendingIntentChecked = true
            coordinator.state.noteLatestUserIntent(true)
            renderToggle()
            controller.onToggle(true)
            toggleCalls.add(true)
        }
    }

    /** Positive dialog callback; a superseded/abandoned intent is dropped. */
    fun accept(seq: Long = activeSeq) {
        if (!alive || seq != activeSeq || !holder.isActiveIntent(seq)) return
        // P2: note the enable app-scoped BEFORE enqueueing the consent write.
        val generation = holder.currentGeneration()
        holder.notePendingEnable(seq, generation)
        dialogPending = false
        controller.setConsentPending(false)
        pendingIntentChecked = true
        coordinator.state.noteLatestUserIntent(true)
        renderToggle()
        coordinator.submitConsentWrite(seq, generation, onResult = { outcome ->
            // Mirrors the Main-thread result hop: still sequence/disposal guarded.
            if (!alive || seq != activeSeq) return@submitConsentWrite
            when (outcome) {
                is BbcWriteOutcome.Confirmed -> Unit // no UI write: consumer owns it
                BbcWriteOutcome.Unknown -> failureToasts.add(seq)
                // A superseded intent was rejected before any commit: it is not
                // a failed write, so no toast and no UI state change.
                BbcWriteOutcome.Skipped -> Unit
            }
        })
    }

    /** Cancel/back/outside/show-failure: zero writes, prior value restored. */
    fun cancel(seq: Long = activeSeq) {
        if (!alive || !holder.isActiveIntent(seq) || seq == 0L) return
        activeSeq = holder.supersedeActiveIntent()
        dialogPending = false
        controller.setConsentPending(false)
        pendingIntentChecked = lastSettledChecked
        // MainActivity writes the restored value through the ONE writer
        // directly (ZERO effect writes, NO projection render) and drops any
        // stale app-scoped intent that could repaint it on a later render.
        coordinator.state.abandonLatestUserIntent()
        setToggleChecked(lastSettledChecked)
        controller.restoreConsentUi(lastSettledChecked)
    }

    /**
     * R5-02: the always-reachable explicit-OFF control, mirroring MainActivity's
     * disableButton action exactly. ZERO consent demand, ZERO onToggle(true),
     * app-scoped intent/enable abandoned, and ONE checkbox writer path.
     */
    fun explicitOff() {
        if (!alive) return
        activeSeq = holder.supersedeActiveIntent()
        dialogPending = false
        controller.setConsentPending(false)
        pendingIntentChecked = false
        coordinator.state.abandonLatestUserIntent()
        coordinator.state.noteLatestUserIntent(false)
        renderToggle()
        controller.onToggle(false)
        toggleCalls.add(false)
    }

    /** Activity/UI teardown: late callbacks and emissions after this are no-ops. */
    fun dispose() {
        alive = false
        dialogPending = false
        uiScope.cancel()
        controller.dispose()
    }
}
