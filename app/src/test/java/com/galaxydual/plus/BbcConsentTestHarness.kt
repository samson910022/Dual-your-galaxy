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
 * and `cancel` reproduce the exact branch order from MainActivity, and a
 * holder-state collector reproduces `renderConsentState` (including the P2
 * exactly-once replay of an enable intent after a UI rebuild).
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

    val controller = BbcInjectionToggleController(
        coordinator = coordinator,
        initialValue = lastSettledChecked,
        main = main,
        onRestored = { restored.add(it) },
        onFailed = {},
        onUnknown = {},
        onEffectStatus = {},
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
        val ui = state.toggleUi()
        if (!dialogPending) ui.checked?.let { lastSettledChecked = it }
        // P2: Granted is AUTHORIZATION only. If this UI (or its destroyed
        // predecessor) never handed the enable to the effect controller, replay
        // it exactly once. The holder's consume is atomic, so a racing live
        // callback can never produce a second toggle.
        val effectStatus = coordinator.state.effectStatus.value
        val alreadyEnabled = effectStatus is BbcEffectStatus.Confirmed && effectStatus.value
        if (state is BbcActivationConsentState.Granted &&
            !dialogPending &&
            !alreadyEnabled &&
            holder.consumePendingEnableForReplay(holder.currentGeneration())
        ) {
            toggleCalls.add(true)
            controller.onToggle(true)
        }
    }

    /** Rebuild a UI from a fresh View/Activity while keeping the app-scoped holder. */
    fun seedFromHolder() {
        lastSettledChecked = holder.currentState().toggleUi().checked ?: false
    }

    fun userToggle(checked: Boolean) {
        if (!alive) return
        // OFF short-circuits BEFORE any consent state read.
        if (!checked) {
            val stale = activeSeq
            if (stale != 0L) {
                activeSeq = holder.nextIntentSequence()
                holder.abandonIntent(stale)
            }
            dialogPending = false
            controller.setConsentPending(false)
            lastSettledChecked = false
            controller.onToggle(false)
            toggleCalls.add(false)
            return
        }
        consentStateReads++
        val state = holder.currentState()
        if (state.requiresConsentFor(true)) {
            val seq = holder.nextIntentSequence()
            activeSeq = seq
            dialogPending = true
            controller.setConsentPending(true)
            dialogsShown.add(seq)
        } else {
            lastSettledChecked = true
            controller.onToggle(true)
            toggleCalls.add(true)
        }
    }

    /** Positive dialog callback; a superseded/abandoned intent is dropped. */
    fun accept(seq: Long = activeSeq) {
        if (!alive || seq != activeSeq || seq == 0L) return
        // P2: note the enable app-scoped BEFORE enqueueing the consent write.
        val generation = holder.currentGeneration()
        holder.notePendingEnable(seq, generation)
        dialogPending = false
        controller.setConsentPending(false)
        coordinator.submitConsentWrite(seq, generation) { outcome ->
            // Mirrors the Main-thread result hop: still sequence/disposal guarded.
            if (!alive || seq != activeSeq) return@submitConsentWrite
            when (outcome) {
                is BbcWriteOutcome.Confirmed -> {
                    // Exactly-once: a rebuilt UI may already have replayed it.
                    if (holder.consumePendingEnable(seq, generation)) {
                        toggleCalls.add(true)
                        controller.onToggle(true)
                    }
                }
                BbcWriteOutcome.Unknown -> failureToasts.add(seq)
            }
        }
    }

    /** Cancel/back/outside/show-failure: zero writes, prior value restored. */
    fun cancel(seq: Long = activeSeq) {
        if (!alive || seq != activeSeq || seq == 0L) return
        activeSeq = holder.nextIntentSequence()
        holder.abandonIntent(seq)
        dialogPending = false
        controller.setConsentPending(false)
        controller.restoreConsentUi(lastSettledChecked)
    }

    /** Activity/UI teardown: late callbacks and emissions after this are no-ops. */
    fun dispose() {
        alive = false
        dialogPending = false
        uiScope.cancel()
        controller.dispose()
    }
}
