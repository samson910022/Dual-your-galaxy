// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
// Hook policy editor — Dual Messenger「可用應用程式」清單（BBC 注入，union-only 白名單擴充）。
//
// The policy written here is the union-only whitelist seed the BBC injector
// appends to Samsung's own "可用的應用程式" list (extend-only; empty =
// observe-only; the hook's own effect switch is
// PolicySource.BBC_AVAILABLE_APPS_INJECTION — the Stage-1
// NON_EMPTY_POLICY_EFFECTIVE_GATE stays closed and is untouched).
//
// Writes NEVER go directly to storage from this screen. Every save goes
// through DualViewModel.replacePolicyDraft() → pending snapshot (QUEUED) →
// confirmation dialog (ack checkbox) → DualViewModel.confirmPolicyWrite() —
// the same two-phase pending/CAS/generation-guarded pipeline MainActivity's
// 「寫入政策」button uses; the only difference is the policy set is this
// screen's checked list instead of the catalog selection. The hook side
// (daagent) reads the draft from dual_policy/extra_whitelist_packages through
// EffectivePolicyCache (60s TTL / 5s failure cooldown / single-flight / 50ms
// budget); a non-empty draft may relax the whitelist on its next poll or
// after a reboot. NOT guaranteed to take effect. The 2026-11-17 device evidence
// (One UI 5 Tab S7 / One UI 9 S23 Ultra) was for the BBC injector effect on a
// PRE-UNIFICATION build — not for this editor / pre-clear / verified-uid path.
//
// UI is programmatic views, same style as the rest of the app (no Compose,
// no new dependencies). This screen replaces the free-text package input
// with a searchable installed-apps picker (row checkbox = in policy). It is
// opened only from MainActivity's techCard policy block and is not exported
// (manifest); it has no external callers.
package com.galaxydual.plus

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class PolicyActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var policyRepairHintView: TextView
    private lateinit var counterView: TextView
    private lateinit var writeBarBtn: Button
    private lateinit var searchInput: EditText
    private lateinit var includeSystemCheck: CheckBox
    private lateinit var listContainer: LinearLayout
    private lateinit var countHintView: TextView

    // PR #74 R7b-2 (M1)：與 MainActivity 共用同一個 app-private 一次性風險 ack store。
    private val riskAckStore: RiskAckStore by lazy {
        getSharedPreferences(RISK_ACK_PREFS_NAME, MODE_PRIVATE).riskAckStore()
    }

    // Same acquisition pattern as MainActivity (internal DualViewModelFactory,
    // see MainActivity.kt): one VM per screen, fully identical wiring — same
    // dual_policy store, owner-user gates, policyText/plurals resolution.
    private val vm: DualViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val injected = testViewModel
                if (injected != null && modelClass.isAssignableFrom(DualViewModel::class.java)) {
                    return injected as T
                }
                return DualViewModelFactory(applicationContext, packageName).create(modelClass)
            }
        }
    }

    private var iconJob: Job? = null

    // Local working set of the policy (package names). Row clicks mutate ONLY
    // this local set + the count label; the actual write goes through the VM
    // pending/confirm pipeline. Replaced by state emissions (see the collect
    // block for the exact sync discipline).
    private var ticks: MutableSet<String> = mutableSetOf()
    // Mirror of state.policyDraftKnown; false => the bar button shows the
    // main-style "Unknown — 請先重新整理草稿" guidance instead of writing.
    private var policyDraftKnown = false
    // Phase != IDLE => the screen is busy in the write pipeline (QUEUED /
    // SAVING / RECONCILING): rows and the bar button are disabled.
    private var phaseIsIdle = true
    // Draft last synced into `ticks` — only a real draft change replaces the
    // local set; unrelated emissions never wipe in-flight ticks.
    private var lastSyncedDraft: Set<String>? = null
    private var lastPhase: PolicyWritePhase? = null

    // Local cache of the catalog (PackageManager + labels) — populated on first
    // render only, reused for filter/search keystrokes. Reloaded on refresh().
    private var catalogCache: List<SourceAppEntry>? = null
    private var hintTotal = 0
    private var hintVisible = 0

    private var catalogLoading = false
    private val handler = Handler(Looper.getMainLooper())
    private var searchDebounce: Runnable? = null
    // Bounded icon cache (LRU-ish eviction of oldest entry).
    private val iconCache = object : LinkedHashMap<String, Drawable>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>?): Boolean = size > 64
    }

    // Phase D shared confirm-dialog state machine (Main/Editor共用同一 holder 類型；
    // pending+generation+ack(in-memory session-scoped)+expanded+SAVING/RECONCILING cancel語義+旋轉保留+onDestroy dismiss)。
    // Editor 至少 dialog 欄位化＋onDestroy dismiss＋onSaveInstanceState 世代保留（見下）。
    private var policyConfirmDialog: AlertDialog? = null
    private val policyConfirmState = PolicyConfirmDialogState()
    // PR-74 A3：pre-clear 警告／ROOT 二次確認／卸載驗證對話框 handle（onDestroy 釋放，
    // 與 MainActivity 同源；先前 show() 後丟棄 handle 致舊 Activity window 洩漏）。
    private val destructiveDialogHandles = DestructiveDialogHandles()
    // Issue #73：候選集清空＝整組清除持久化草稿＝ BBC6 風險（清除／空政策曾於 11-18 伴隨
    // Samsung 自動刪除 verified target 分身，觸發機制未證實，資料不可復原）。先探測分身安裝狀態並顯示警告對話框，
    // 通過後才執行實際寫入／保持未勾選；詳見 MainActivity 同名流程。
    // Phase C：
    // - N→M 覆寫：old-minus-candidate delta，對 installed dual 彈警告；
    //   proceed 攜警告綁定（rev/version/freshGen）進 VM，提交前比對失配作廢。
    // - N→0 採 A 案：禁清到底——空集連警告都不彈，導引去 Main 清除；
    //   刪現有「警告+恆拒」死路（pre_clear_editor_* 不再使用）。
    // - Unknown stale 非空不得當授權依據：!known 一律走 cannot-determine 對話框，
    //   不走空 fast-path。Activity probe 只告知不授權（VM 另做權威重讀）。
    // pendingClearConfirm：probe／警告對話框存活期忽略再次操作，避免重複彈窗。
    // 跨屏仍各私有（Main/Editor 各自持有），警告綁修訂後作廢機制覆蓋競態。
    private var pendingClearConfirm = false

    // D03a：第一階段警告捕獲的 consent；performWritePolicy 的 proceed closure
    // 讀出並透傳給 VM（proceed 簽名維持不變）。每次警告開頭清空。
    private var pendingWarningConsent: PolicyWriteConsent? = null

    // D03b：最近一次警告要求的 re-trigger；fail-closed abort 後強制全新警告。
    // Activity 重建後為 null（使用者須再點寫入入口）。
    // R4-1 (E01) 驗證：onRetry 先清 pendingClearConfirm 再 invoke 此 seam，
    // re-entry 通過 consentResetAllowedForNewWarning（=!pending）；seam 用
    // fromUserTap=false，autoRestartCount 不重置亦不遞增（重試非自動重啟）。
    private var pendingWarningRestart: (() -> Unit)? = null

    // R6-1 (G01): one re-entry per INSTALLED WARNING, not per Activity (see
    // MainActivity). Re-armed whenever a new warning is installed, including an
    // auto re-entry; a late/duplicate settle from an older warning is ignored.
    private val warningRetryOneShot = WarningRetryOneShot()

    // D03b fixer M5/M3：confirm 重入防護 + abort 重啟警告 one-shot 排程 guard。
    private val consentConfirmInFlight = InFlightGuard()
    private val warningRestartGuard = OneShotRestartGuard()
    // D03b fixer (availability)：每次使用者真正點入口重置；自動重啟最多 MAX 次。
    private val autoRestartCount = AtomicInteger(0)

    // R03：旗標翻轉時立即重建列，讓勾選控制項在 probe 啟動前就禁用；
    // 取消／完成／例外路徑同樣立刻恢復。
    private fun setPendingClearConfirm(value: Boolean) {
        pendingClearConfirm = value
        try { renderList() } catch (_: Exception) { /* Best effort. */ }
        try { updateCounter() } catch (_: Exception) { /* Best effort. */ }
    }

    // R5-0 (F01): non-recursive guidance after a settled-but-not-Known retry.
    private fun showWarningRetryGuidance(msgRes: Int) {
        setPendingClearConfirm(false)
        if (!isFinishing && !isDestroyed) {
            try {
                android.widget.Toast.makeText(this, msgRes, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }

    // R5-0 (F01): Unknown Retry = real authoritative re-read + one re-entry per
    // tap only on a settled Known snapshot; no destructive clear, no consent
    // manufacture, no restart for Corrupt/Unavailable.
    private fun retryUnknownWarning() {
        // R6-1 (G01): capture the token at the Retry tap, BEFORE the refresh.
        val token = warningRetryOneShot.capture()
        vm.refreshPolicyDraftForWarningRetry { settled ->
            applyWarningRetryDecision(
                decision = policyWarningRetryDecision(settled),
                hostAlive = !isFinishing && !isDestroyed,
                onContinue = {
                    if (warningRetryOneShot.tryConsume(token)) {
                        setPendingClearConfirm(false)
                        pendingWarningRestart?.invoke()
                    } else {
                        // LOG-ONLY: benign duplicates must not raise a toast.
                        preClearLog("unknown retry re-entry already consumed; dropping")
                    }
                },
                onCorrupt = { showWarningRetryGuidance(R.string.policy_corrupt_detected) },
                onUnavailable = { showWarningRetryGuidance(R.string.policy_refresh_failed) },
            )
        }
    }

    // Phase C A 案：編輯器空集導引（禁清到底）。
    // 空集連警告都不彈，改文案＋按鈕導引去 Main 清除。
    private fun showEditorEmptyGuide() {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.policy_editor_empty_guide_title)
                .setMessage(R.string.policy_editor_empty_guide_message)
                .setCancelable(true)
                .setPositiveButton(R.string.policy_editor_empty_guide_go_main) { _, _ -> finish() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } catch (_: RuntimeException) {
            // Best effort: 對話框失敗不影響 ticks（已還原／未寫入）。
        }
    }

    // 目前持久化草稿快照（Phase C：改走 VM 快照，含 Known＋綁定）。
    // 讀取失敗回 Unknown 空快照，呼叫端 fail-closed 走探測失敗警告。
    private fun policyWarningSnapshotOrUnknown(): PolicyWarningSnapshot = try {
        vm.snapshotPolicyForWarning()
    } catch (_: Exception) {
        PolicyWarningSnapshot(emptySet(), false, false, PolicyWarningBinding(-1L, -1L, -1L))
    }

    private fun requestEditorWriteWarning(
        candidate: Set<String>,
        proceed: (PolicyWarningBinding, Set<String>) -> Unit,
        onCancel: () -> Unit = {},
        // D03b fixer M2：ROOT 驗證對話框兩個繼續回呼（只跳 membership 比對）。
        onRootVerified: () -> Unit = {},
    ) {
        // D03b carry-forward #2：re-entrant 要求不得清空活躍警告捕獲的 consent。
        if (!consentResetAllowedForNewWarning(pendingClearConfirm)) return
        pendingWarningConsent = null
        val snap = policyWarningSnapshotOrUnknown()
        // Phase C：候選先消毒（與 VM 寫入同文法），delta=old-minus-消毒後候選。
        // R03：消毒結果即凍結候選，probe／對話框期間一律寫這一份。
        val sanitizedCandidate = try {
            sanitizeHookPolicy(candidate)
        } catch (_: Exception) {
            emptySet()
        }
        val warnedBinding = snap.binding.withWarning(snap.draft, sanitizedCandidate)
        // M1/M3：delta 先算，所有 fast path 共用真實 consent 建造器。
        val delta = policyRemovalDelta(snap.draft, sanitizedCandidate)
        // Phase C：Unknown 一律走 cannot-determine 對話框，不走空 fast-path。
        // R4-1 (E01/E02)：移除 force-proceed；這裡不建 consent／pending。若日後重建，
        // 必須傳 noRemovalKnown=false（Unknown 的 delta=∅ 是偽空）。
        if (!snap.known) {
            if (pendingClearConfirm) return
            setPendingClearConfirm(true)
            pendingWarningConsent = null
            showPreClearProbeFailedWarning(
                this@PolicyActivity,
                config = probeFailedDialogConfig(write = true, unknown = true),
                onRetry = { retryUnknownWarning() },
                onCancel = { setPendingClearConfirm(false); onCancel() },
                dialogHandles = destructiveDialogHandles,
            )
            return
        }
        // R4-1 (E02)：Known + delta 空 ⇒ 可證明不移除；noRemovalAllowed 綁 snap.known。
        if (delta.isEmpty()) {
            buildRealConsentThen(delta, noRemovalKnown = noRemovalAllowed(snap.known, delta)) {
                proceed(warnedBinding, sanitizedCandidate)
            }
            return
        }
        if (pendingClearConfirm) return
        setPendingClearConfirm(true)
        lifecycleScope.launch {
            // Phase B：與 MainActivity 同口徑——僅 Ok(empty) 直接放行，
            // Failed 一律走探測失敗警告；Ok(non-empty) 走雙開提醒。
            // Phase C：僅探 delta（old-minus-candidate）。
            // R05/NEW-1：uid 由 verified target 解析，unverified => Failed 警告。
            val verifiedTarget = resolvePreClearDualTarget(vm.state.value)
            val probe = try {
                probeDualApps(delta, verifiedTarget?.userId)
            } catch (e: CancellationException) {
                setPendingClearConfirm(false)
                throw e
            } catch (e: Exception) {
                preClearLog("pre-clear dual-profile probe failed", e)
                DualProbeResult.Failed("exec-failed: ${(e.message ?: e.javaClass.simpleName).take(200)}")
            }
            if (isFinishing || isDestroyed) {
                setPendingClearConfirm(false)
                return@launch
            }
            // R4-1 (E01)：Failed 不建 consent/pending，只給 retry dialog；consent
            // 僅在 Ok 時以真實 probe.installed 凍結（Ok(empty) 的空集合法是合法快照）。
            when (probe) {
                is DualProbeResult.Failed -> {
                    pendingWarningConsent = null
                    showPreClearProbeFailedWarning(
                        this@PolicyActivity,
                        config = probeFailedDialogConfig(write = true, unknown = false),
                        onRetry = {
                            setPendingClearConfirm(false)
                            pendingWarningRestart?.invoke()
                        },
                        onCancel = { setPendingClearConfirm(false); onCancel() },
                        dialogHandles = destructiveDialogHandles,
                    )
                    return@launch
                }
                is DualProbeResult.Ok -> {
                    pendingWarningConsent = buildPolicyWriteConsent(
                        delta = delta,
                        verifiedTarget = verifiedTarget,
                        installed = probe.installed,
                        // 只有 Ok 才到這裡，故永遠不是 failed-probe consent。
                        fromFailedProbe = false,
                    )
                    if (!shouldWarnForDelta(probe.installed)) {
                        setPendingClearConfirm(false)
                        proceed(warnedBinding, sanitizedCandidate)
                        return@launch
                    }
                    showPreClearWarning(
                        this@PolicyActivity,
                        probe.installed,
                        onProceed = { setPendingClearConfirm(false); proceed(warnedBinding, sanitizedCandidate) },
                        onCancel = { setPendingClearConfirm(false); onCancel() },
                        resolveLiveTarget = { resolvePreClearDualTarget(vm.state.value) },
                        fetchFreshVerifiedTarget = { vm.fetchFreshVerifiedTarget() },
                        dialogHandles = destructiveDialogHandles,
                        capturedTarget = verifiedTarget,
                        capturedInstalled = probe.installed.toSet(),
                        // R2-5 (N10)：同一 verified uid／同一 delta 的重探；身分先由
                        // revalidatePreClearContinue 與 live/fresh 比對。
                        reprobe = { probeDualApps(delta, verifiedTarget?.userId) },
                        rootUninstallAllowed = verifiedTarget != null,
                        onRootVerified = onRootVerified,
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase D：旋轉保留世代 (expanded/dismissed 世代；pending 本體由 retained VM 重播).
        // consent 為 session-scoped：ack 永不寫入 Bundle，還原強制清除
        // (見 PolicyConfirmDialogState 檔頭；與 Main 同源).
        policyConfirmState.restoreFromBundle(savedInstanceState)

        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(getColor(R.color.oneui_canvas))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, p)
        }

        // Top bar: back + title.
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            addView(Button(this@PolicyActivity).apply {
                text = getString(R.string.policy_editor_back)
                setOnClickListener { finish() }
            })
            addView(TextView(this@PolicyActivity).apply {
                text = getString(R.string.policy_editor_title)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.oneui_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            })
        })

        statusView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.oneui_text_secondary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(statusView)

        // R5-1 (F02): app-scoped hook-restart hint, near the status line. Reuses
        // the existing string (no new copy). Tapping it is the explicit ack.
        policyRepairHintView = TextView(this).apply {
            text = getString(R.string.policy_repair_hook_restart_required)
            textSize = 11f
            setTextColor(getColor(R.color.oneui_warning_text))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
            contentDescription = text
            isClickable = true
            setOnClickListener { vm.acknowledgeRepairHint() }
        }
        column.addView(policyRepairHintView)

        column.addView(TextView(this).apply {
            text = getString(R.string.policy_editor_intro)
            textSize = 11f
            setTextColor(getColor(R.color.oneui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); bottomMargin = dp(10) }
        })

        // PR #74 R7b-2 (M1)：editor intro 已帶短風險；此處僅補一個可再次開啟完整風險的入口。
        column.addView(TextView(this).apply {
            tag = "risk_details_entry"
            text = getString(R.string.risk_details_entry)
            textSize = 12f
            setTextColor(getColor(R.color.oneui_warning_text))
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { showRiskDetailsDialog(this@PolicyActivity) }
        })

        counterView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.oneui_text_primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        column.addView(counterView)

        // Search filter.
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            addTextChangedListener(SimpleTextWatcher {
                // Debounce: keystrokes burst-render the whole list O(n) per char.
                searchDebounce?.let { handler.removeCallbacks(it) }
                searchDebounce = Runnable { renderList() }
                searchDebounce?.let { handler.postDelayed(it, 200) }
            })
        }
        column.addView(searchInput)

        // "show system apps" toggle (default off).
        includeSystemCheck = CheckBox(this).apply {
            text = getString(R.string.policy_editor_show_system)
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            setOnCheckedChangeListener { _, _ ->
                searchDebounce?.let { handler.removeCallbacks(it) }
                searchDebounce = null
                renderList()
            }
        }
        column.addView(includeSystemCheck)

        countHintView = TextView(this).apply {
            textSize = 10f
            setTextColor(getColor(R.color.oneui_text_tertiary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        column.addView(countHintView)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(listContainer)

        // 「寫入政策草稿」bar button：顯示目前勾選數；on press 走 VM 兩階段流程。
        // Phase E：文案經 policy_editor_write_draft_format 資源化（三語系同鍵）。
        writeBarBtn = Button(this).apply {
            text = try {
                getString(R.string.policy_editor_write_draft_format, 0)
            } catch (_: Exception) {
                "寫入政策草稿（0）"
            }
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            setOnClickListener { onWritePolicyClicked() }
        }
        column.addView(writeBarBtn)

        root.addView(column)
        setContentView(root)

        // Drive check marks / status / phase-disable from VM state. The set is
        // REPLACED only when the persisted draft actually changed (unrelated
        // emissions never wipe in-flight ticks); the confirm path refreshes
        // ticks from state on the next emission after a successful write.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    policyDraftKnown = s.policyDraftKnown
                    applyStatus(s)
                    applyRepairHint(s)
                    phaseIsIdle = s.policyWritePhase == PolicyWritePhase.IDLE
                    if (s.policyDraft != lastSyncedDraft) {
                        lastSyncedDraft = s.policyDraft
                        ticks = s.policyDraft.toMutableSet()
                        renderList()
                    }
                    if (s.policyWritePhase != lastPhase) {
                        lastPhase = s.policyWritePhase
                        // Rebuild rows so enabled/disabled state matches the phase.
                        renderList()
                    }
                    updateCounter()
                    // Phase D：state-driven dialog sync（同 MainActivity syncPolicyWriteDialog
                    // 精簡版：旋轉由 retained VM 重播 pending 重建 dialog；終態/取消關閉）。
                    try {
                        syncPolicyConfirmDialog(s.pendingPolicyWrite, s.policyWritePhase)
                    } catch (_: Exception) {
                        // Best effort: sync 失敗不殺 collector。
                    }
                }
            }
        }
        // R5-1 (F02): a repair committed in the Main VM (or an ack there) changes
        // only the App-scoped signal, so observe it directly as well.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.repairHintFromApp.collect {
                    try { applyRepairHint(vm.state.value) } catch (_: Exception) { /* Best effort. */ }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Phase D：世代保留 (expanded/dismissed；pending 本體由 VM 保留；
        // ack session-scoped 不存、shown 不存，見 holder 檔頭).
        try {
            policyConfirmState.saveToBundle(outState)
        } catch (_: Exception) {
            // Best effort.
        }
    }

    override fun onDestroy() {
        // Phase D：onDestroy dismiss（避免舊 Activity 的 window 洩漏；VM fence 不受影響）。
        try {
            policyConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        policyConfirmDialog = null
        // PR-74 A3 / R4-5：破壞性路徑四個 handle 單次 best-effort dismiss + null
        // （含 probe-failed；與 MainActivity.onDestroy 同口徑，單一失敗不阻斷後續。
        // 用 dismiss() 而非 cancel()，避免快銷毀中的 Activity 仍觸發 onCancel 回呼）。
        dismissAllDestructiveHandles(destructiveDialogHandles) { it.dismiss() }
        policyConfirmState.onPendingCleared()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refresh() {
        // Direct renders shed any pending debounced rebuild (superseded caller wins).
        searchDebounce?.let { handler.removeCallbacks(it) }
        searchDebounce = null
        catalogCache = null
        renderList()
        updateCounter()
    }

    private fun applyStatus(s: UiState) {
        // Status line: VM message verbatim (single source of truth for wording,
        // incl. UNKNOWN_REFRESH guidance after a rejected write). Neutral by
        // default; INVALID/CHANNEL kinds render in the error color.
        statusView.text = s.msg
        statusView.setTextColor(
            if (s.msgKind == MsgKind.INVALID || s.msgKind == MsgKind.CHANNEL) {
                getColor(R.color.oneui_error)
            } else {
                getColor(R.color.oneui_text_secondary)
            },
        )
    }

    // R5-1 (F02): hint visibility = this VM's back-compat mirror OR the
    // App-scoped signal. No stronger copy; tapping the view acknowledges both.
    private fun applyRepairHint(s: UiState) {
        if (!::policyRepairHintView.isInitialized) return
        // R5-1 fixer (Option A): mask the mirror in production so an ack in the
        // other Activity cannot be overridden by this VM's stale flag.
        val vmFlag = s.policyRepairNeedsHookRestart && !vm.hasPolicyCoordinator
        val need = shouldShowRepairHint(
            vmFlag = vmFlag,
            appFlag = vm.repairHintFromApp.value,
        )
        val want = if (need) View.VISIBLE else View.GONE
        if (policyRepairHintView.visibility != want) {
            policyRepairHintView.visibility = want
            policyRepairHintView.accessibilityLiveRegion =
                if (need) View.ACCESSIBILITY_LIVE_REGION_POLITE
                else View.ACCESSIBILITY_LIVE_REGION_NONE
        }
    }

    // Phase E 孤兒條目：ticks 中不在 catalog 的包名（已解除安裝／篩選剔除後殘留）。
    // catalog 未載入（catalogCache==null）時回空集，避免載入中誤判全孤兒。
    // 隱私：呼叫端僅用 size 計數呈現＋一鍵移除；log 亦只記計數，不記包名原文
    // （與 MainActivity preClearLog 計數口徑一致）。
    internal fun orphanTicks(): Set<String> {
        val cache = catalogCache ?: return emptySet()
        if (cache.isEmpty()) return emptySet()
        return try {
            val names = cache.map { it.packageName }.toSet()
            orphanPackagesOf(ticks, names)
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun hintText(): String {
        val policySize = ticks.size
        return try {
            if (hintTotal > MAX_LIST_ROWS) {
                getString(
                    R.string.policy_editor_hint_capped_format,
                    MAX_LIST_ROWS,
                    hintTotal,
                    policySize,
                )
            } else {
                getString(R.string.policy_editor_hint_format, hintVisible, policySize)
            }
        } catch (_: Exception) {
            if (hintTotal > MAX_LIST_ROWS) {
                "顯示前 ${MAX_LIST_ROWS} 筆（共 $hintTotal 筆，已勾選 $policySize）；請用搜尋縮小範圍"
            } else {
                "共 $hintVisible 筆 · 已勾選 $policySize"
            }
        }
    }

    private fun updateCounter() {
        // Phase E：計數行經 policy_editor_counter plurals 資源化；R11：孤兒後綴改用
        // policy_orphan_pending（孤兒仍在政策草稿中，須點「一鍵移除」才會移除，不得稱「已捨去」）。
        // 三語系同鍵；JVM 無 Activity 資源時回退硬編碼。
        val orphanCount = try {
            orphanTicks().size
        } catch (_: Exception) {
            0
        }
        counterView.text = try {
            val base = resources.getQuantityString(
                R.plurals.policy_editor_counter,
                ticks.size,
                ticks.size,
            )
            if (orphanCount > 0) {
                val suffix = resources.getQuantityString(
                    R.plurals.policy_orphan_pending,
                    orphanCount,
                    orphanCount,
                )
                "$base\n$suffix"
            } else {
                base
            }
        } catch (_: Exception) {
            val base = "目前 ${ticks.size} 個包名"
            if (orphanCount > 0) {
                "$base\n有 $orphanCount 項勾選不在來源清單（仍在政策草稿中，須點「一鍵移除」才會移除）"
            } else {
                base
            }
        }
        if (::countHintView.isInitialized) {
            countHintView.text = hintText()
        }
        if (::writeBarBtn.isInitialized) {
            // R03：probe／警告對話框在途時連入口也鎖住（除了 phaseIsIdle）。
            writeBarBtn.isEnabled = phaseIsIdle && policyDraftKnown && !pendingClearConfirm
            writeBarBtn.text = if (policyDraftKnown) {
                try {
                    getString(R.string.policy_editor_write_draft_format, ticks.size)
                } catch (_: Exception) {
                    "寫入政策草稿（${ticks.size}）"
                }
            } else {
                getString(R.string.policy_unknown_refresh)
            }
        }
    }

    private fun showInfo(msg: String) {
        statusView.text = msg
        statusView.setTextColor(getColor(R.color.oneui_text_secondary))
    }

    // D03b fixer M3：abort 清理不再同步 invoke restart（避免遞迴／死路）。
    // R4-1 (E01)：寫入 abort 用寫入文案；清除路徑（Main）沿用既有清除文案。
    private fun consentAbortCleanup(msgRes: Int = R.string.pre_clear_continue_recheck_failed) {
        pendingWarningConsent = null
        if (!isFinishing && !isDestroyed) {
            try {
                android.widget.Toast.makeText(
                    this@PolicyActivity,
                    msgRes,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Exception) {
            }
        }
        scheduleWarningRestart()
    }

    // D03b fixer availability cap：自動重啟最多 MAX 次／每次使用者點入口；用罷不再
    // 排程（recheck 訊息已由 consentAbortCleanup 顯示），使用者須重新點入口。
    // 不影響 fail-closed 寫入（abort 依然零寫入）。
    private fun scheduleWarningRestart() {
        if (!warningRestartGuard.trySchedule()) {
            preClearLog("warning restart already scheduled; dropping re-entrant request")
            return
        }
        val previous = autoRestartCount.getAndIncrement()
        if (!canAutoRestart(previous)) {
            preClearLog("warning restart cap reached; user must re-tap entry")
            warningRestartGuard.reset() // 未 post，必須歸還 guard
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                pendingWarningRestart?.invoke()
            } catch (_: Exception) {
                preClearLog("warning restart failed status=exception")
            } finally {
                warningRestartGuard.reset()
            }
        }
    }

    // D03b fixer M1/M3：fast path 用真實 seam 現場建 consent 後才執行 action。
    // noRemovalKnown 排在 action 之前，保留既有 trailing-lambda 呼叫端（見 MainActivity）。
    private fun buildRealConsentThen(
        delta: Set<String>,
        noRemovalKnown: Boolean = false,
        action: (PolicyWriteConsent) -> Unit,
    ) {
        lifecycleScope.launch {
            val consent = buildFastPathConsent(
                delta = delta,
                resolveLiveTarget = { resolvePreClearDualTarget(vm.state.value) },
                reprobe = { pkgs, uid -> probeDualApps(pkgs, uid) },
                noRemovalKnown = noRemovalKnown,
            )
            if (isFinishing || isDestroyed) return@launch
            pendingWarningConsent = consent
            action(consent)
        }
    }

    // D03b fixer M2：ONLY ROOT 驗證對話框兩個繼續回呼標記 rootVerifiedEmpty
    // （onClearVerified 已驗證空／onForceClear 自擔風險覆寫，非驗證成功）。
    private val markRootVerifiedEmpty: () -> Unit = {
        pendingWarningConsent = pendingWarningConsent?.copy(rootVerifiedEmpty = true)
    }

    // fromUserTap=false 表示由 scheduleWarningRestart 的 post 呼叫：不得重置上限。
    private fun startEditorWriteWarning(candidate: Set<String>, fromUserTap: Boolean = true) {
        if (fromUserTap) {
            autoRestartCount.set(0)
        }
        // R6-1 (G01): re-arm on EVERY installed warning, including auto re-entry.
        warningRetryOneShot.onWarningInstalled()
        requestEditorWriteWarning(
            candidate = candidate,
            proceed = { binding, frozen -> performWritePolicy(binding, frozen) },
            onRootVerified = markRootVerifiedEmpty,
        )
    }

    // D03b：寫入最終 confirm 的 consent fence（policyConfirmDialog Positive）。
    // 四道關卡是唯一新鮮度權威；generation 只作 pending 身份比對（binding-only）。
    private fun confirmWriteWithConsent(pending: PendingPolicyWrite) {
        if (!consentConfirmInFlight.tryEnter()) {
            preClearLog("consent confirm dropped: write already in flight")
            return
        }
        lifecycleScope.launch {
            try {
                val livePending = try {
                    vm.state.value.pendingPolicyWrite
                } catch (_: Exception) {
                    null
                }
                if (livePending == null || livePending.generation != pending.generation) {
                    preClearLog("policy-write consent aborted: pending gone/changed at confirm")
                    try { vm.cancelPolicyWrite(pending) } catch (_: Exception) { }
                    consentAbortCleanup(R.string.pre_write_recheck_failed)
                    return@launch
                }
                val consent = livePending.consent
                if (consent == null) {
                    // carry-forward #4：Activity 重建／legacy 注入 ⇒ fail closed，零寫入。
                    preClearLog("policy-write consent aborted: missing at confirm")
                    try { vm.cancelPolicyWrite(pending) } catch (_: Exception) { }
                    consentAbortCleanup(R.string.pre_write_recheck_failed)
                    return@launch
                }
                val outcome: PreClearContinueOutcome = try {
                    revalidatePolicyWriteConsent(
                        consent = consent,
                        resolveLiveTarget = { resolvePreClearDualTarget(vm.state.value) },
                        fetchFreshVerifiedTarget = { vm.fetchFreshVerifiedTarget() },
                        reprobe = { probeDualApps(consent.delta, consent.capturedUserId) },
                    )
                } catch (_: Exception) {
                    PreClearContinueOutcome.CannotDetermine
                }
                // await 後再驗 pending 身份／generation（binding-only 身份比對，非新鮮度依據）。
                val afterPending = try {
                    vm.state.value.pendingPolicyWrite
                } catch (_: Exception) {
                    null
                }
                if (afterPending == null || afterPending.generation != pending.generation) {
                    preClearLog("policy-write consent aborted: pending changed after revalidation")
                    try { vm.cancelPolicyWrite(pending) } catch (_: Exception) { }
                    consentAbortCleanup(R.string.pre_write_recheck_failed)
                    return@launch
                }
                dispatchConsentGatedConfirm(
                    outcome = outcome,
                    confirm = { vm.confirmPolicyWrite(pending, pending.generation) },
                    revoke = { try { vm.cancelPolicyWrite(pending) } catch (_: Exception) { } },
                    notify = { consentAbortCleanup(R.string.pre_write_recheck_failed) },
                    hostAlive = { !isFinishing && !isDestroyed },
                )
            } finally {
                consentConfirmInFlight.exit()
            }
        }
    }

    private fun onWritePolicyClicked() {
        // PR #74 R7b-2 (M1)：首次政策寫入前一次性風險 gate（取消即零寫入、零 ack）。
        // gate 為附加關卡，通過後原階段檢查／probe 警告／政策確認完整不變。
        fun proceed() {
            if (!phaseIsIdle || pendingClearConfirm) return
            if (!policyDraftKnown) {
                // Main-style Unknown guidance: 不寫入（fail-closed）；使用者先
                // 在 MainActivity「重新整理草稿」後返回。
                showInfo(getString(R.string.policy_unknown_refresh))
                return
            }
            // Phase C A 案：編輯器禁清到底——空集連警告都不彈，導引去 Main 清除
            // （改文案＋按鈕）；刪現有「警告＋恆拒」死路。
            if (editorEmptyShouldGuide(ticks.isEmpty())) {
                showEditorEmptyGuide()
                return
            }
            // Phase C N→M：old-minus-candidate delta，對 installed user95 彈警告；
            // R03：proceed 攜「綁定＋警告前凍結候選」，寫入時不再重讀 ticks。
            val candidate = HashSet(ticks)
            pendingWarningRestart = { startEditorWriteWarning(HashSet(ticks), fromUserTap = false) }
            startEditorWriteWarning(candidate)
        }
        if (shouldShowRiskGate(riskAckStore.isAcknowledged())) {
            showRiskAckGate(this, riskAckStore) { proceed() }
        } else {
            proceed()
        }
    }

    private fun performWritePolicy(binding: PolicyWarningBinding?, candidate: Set<String>) {
        // The policy set is the editor's checked list snapshot taken before the
        // warning probe, never re-read from live ticks; selectedKeys is left
        // untouched by the VM. Phase C：綁定提交前比對，失配作廢要求重探。
        if (binding != null) {
            // R03 (PR #74) Phase 2：在真正持有 Editor live 候選處重算並比對指紋。
            // VM 的 isWarningBindingFresh 刻意不解指紋來源（Main selectedKeys 與
            // Editor ticks 天生不同），故此處是唯一可正確判定 Editor 候選漂移的
            // 位置。pendingClearConfirm 為主要 UI 凍結，此為 defense-in-depth。
            // 失配走 VM 單一訊息出口 UNKNOWN_REFRESH/CHANNEL，零 store 寫入。
            val liveEditorCandidate = try {
                sanitizeHookPolicy(HashSet(ticks))
            } catch (_: Exception) {
                emptySet()
            }
            if (!isWarningCandidateFresh(binding, liveEditorCandidate)) {
                vm.rejectStaleWarningBinding()
                return
            }
            vm.replacePolicyDraft(HashSet(candidate), binding, consent = pendingWarningConsent)
        } else {
            vm.replacePolicyDraft(HashSet(candidate), consent = pendingWarningConsent)
        }
        val cur = try {
            vm.state.value
        } catch (_: RuntimeException) {
            null
        }
        val pending = cur?.pendingPolicyWrite
        if (cur?.policyWritePhase == PolicyWritePhase.QUEUED && pending != null) {
            showConfirmDialog(pending)
        }
        // Not QUEUED (BUSY / Unknown / Corrupt / RECONCILING / …): the VM
        // already wrote the rejection reason into s.msg — it lands on the
        // status line via the collect block. No dialog, no write.
    }

    private fun showConfirmDialog(pending: PendingPolicyWrite) {
        if (isFinishing || isDestroyed) return
        // Phase D shared semantics: SAVING/RECONCILING both block confirm (QUEUED only);
        // cancel splits (SAVING no-op w/ toast, RECONCILING local-dismiss, else VM cancel).
        // confirmSaving 經 policyConfirmSaving (single source，與 Main 同源).
        val phase = try {
            vm.state.value.policyWritePhase
        } catch (_: RuntimeException) {
            PolicyWritePhase.RECONCILING
        }
        val reconciling = phase == PolicyWritePhase.RECONCILING
        val saving = phase == PolicyWritePhase.SAVING
        // Stage J4 local suppression: already dismissed this generation during
        // RECONCILING — do not rebuild (VM fence retained).
        if (policyConfirmState.shouldSuppressForReconciling(pending, reconciling)) return
        if (!reconciling) policyConfirmState.clearDismissIfNotReconciling(false)
        // Phase D single source：confirmSaving 經 policyConfirmSaving 映射 (SAVING||RECONCILING).
        val confirmSaving = policyConfirmSaving(saving, reconciling)
        val cancelable = policyDialogShouldBeCancelable(saving, reconciling)
        // Same-generation retains ack (rotation); cross-generation resets.
        if (policyAckShouldReset(policyConfirmState.ackGeneration, pending.generation)) {
            policyConfirmState.acknowledged = false
            policyConfirmState.ackGeneration = null
        }
        val total = pending.packages.size
        val sorted = pending.packages.sorted()
        val shown = sorted.take(POLICY_DIALOG_DISPLAY_MAX)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, 0, p, dp(4))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(240),
            )
        }
        scroll.addView(TextView(this).apply {
            text = shown.joinToString("\n")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.oneui_text_secondary))
        })
        container.addView(scroll)
        if (total > POLICY_DIALOG_DISPLAY_MAX) {
            container.addView(TextView(this).apply {
                text = getString(
                    R.string.policy_write_over_cap_warning,
                    total,
                    POLICY_DIALOG_DISPLAY_MAX,
                )
                textSize = 11f
                setTextColor(getColor(R.color.oneui_warning_text))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            })
        }
        val ack = CheckBox(this).apply {
            setText(R.string.policy_write_acknowledge)
            textSize = 12f
            minHeight = dp(48)
            isChecked = policyConfirmState.acknowledged &&
                policyConfirmState.ackGeneration == pending.generation
        }
        container.addView(ack)

        try {
            policyConfirmDialog?.dismiss()
        } catch (_: Exception) {
        }
        policyConfirmState.onDialogShown(pending, confirmSaving)
        ack.setOnCheckedChangeListener { _, checked ->
            policyConfirmState.onAckChanged(checked, pending.generation)
            policyConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                policyWriteCanConfirmInspectable(total, checked, confirmSaving)
        }
        policyConfirmDialog = AlertDialog.Builder(this)
            .setTitle(R.string.policy_write_title)
            .setMessage(policyEditorConfirmMessage(pending))
            .setView(container)
            .setCancelable(cancelable)
            // 「確認寫入」/「取消」：與主列表流程同鍵同義（VM 世代守衛再驗一次）。
            .setPositiveButton(R.string.policy_write_positive, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setOnCancelListener {
                val recNow = try {
                    vm.state.value.policyWritePhase == PolicyWritePhase.RECONCILING
                } catch (_: Exception) {
                    reconciling
                }
                if (recNow) {
                    policyConfirmState.onLocalDismissForReconciling(pending)
                    try { policyConfirmDialog?.dismiss() } catch (_: Exception) { }
                    policyConfirmDialog = null
                } else {
                    vm.cancelPolicyWrite(pending)
                }
            }
            .show()
        policyConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = policyWriteCanConfirmInspectable(total, ack.isChecked, confirmSaving)
            setOnClickListener {
                // Positive fires only with the ack checked (gate below + here);
                // VM re-validates everything else (generation CAS, RECONCILING,
                // in-flight, coordinator fences) — the dialog is not authority.
                // SAVING/RECONCILING both block confirm (re-read live phase;
                // Phase D single source：經 policyConfirmSaving 映射).
                val curSaving = try {
                    val ph = vm.state.value.policyWritePhase
                    policyConfirmSaving(ph == PolicyWritePhase.SAVING, ph == PolicyWritePhase.RECONCILING)
                } catch (_: RuntimeException) {
                    true
                }
                if (!ack.isChecked) return@setOnClickListener
                if (!policyWriteCanConfirmInspectable(total, ack.isChecked, curSaving)) return@setOnClickListener
                val live = try {
                    vm.state.value.pendingPolicyWrite
                } catch (_: RuntimeException) {
                    null
                } ?: run { try { policyConfirmDialog?.dismiss() } catch (_: Exception) { }; return@setOnClickListener }
                // D03b：最終 confirm 經 consent fence（警告↔confirm 期間的 clone TOCTOU）。
                confirmWriteWithConsent(live)
            }
        }
        policyConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            // SAVING keeps clickable for the toast (not silent no-op); RECONCILING
            // local-dismisses; else VM cancel. Mirrors MainActivity negative arm.
            isEnabled = true
            setOnClickListener {
                val phNow = try {
                    vm.state.value.policyWritePhase
                } catch (_: Exception) {
                    if (reconciling) PolicyWritePhase.RECONCILING
                    else if (saving) PolicyWritePhase.SAVING else PolicyWritePhase.IDLE
                }
                if (phNow == PolicyWritePhase.SAVING) {
                    try {
                        android.widget.Toast.makeText(
                            this@PolicyActivity,
                            getString(R.string.policy_write_cancel_denied),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } catch (_: Exception) {
                    }
                } else if (phNow == PolicyWritePhase.RECONCILING) {
                    policyConfirmState.onLocalDismissForReconciling(pending)
                    try { policyConfirmDialog?.dismiss() } catch (_: Exception) { }
                    policyConfirmDialog = null
                } else {
                    vm.cancelPolicyWrite(pending)
                }
            }
        }
        policyConfirmDialog?.setCancelable(cancelable)
        try {
            policyConfirmDialog?.setCanceledOnTouchOutside(cancelable)
        } catch (_: Exception) {
        }
    }

    // Phase E：編輯器確認訊息主模板 policy_write_message＋dropped 後綴（與 MainActivity.policyWriteMessage
    // 同口徑）；R11：確認對話框尚未寫入，後綴改用 policy_dropped_future 未來式（「將捨去」）。
    // 三語系同鍵；JVM 無資源時回退硬編碼。
    // docs/USAGE.md 政策 TTL 對照表為真相單一來源（App 側確認 5 分鐘 vs hook 側 60 秒），此處僅呈現，不重定義。
    internal fun policyEditorConfirmMessage(pending: PendingPolicyWrite): String {
        val total = pending.packages.size
        var message = try {
            resources.getQuantityString(R.plurals.policy_write_message, total, total)
        } catch (_: Exception) {
            "政策寫入（$total 項）"
        }
        if (pending.droppedCount > 0) {
            val suffix = try {
                resources.getQuantityString(
                    R.plurals.policy_dropped_future,
                    pending.droppedCount,
                    pending.droppedCount,
                )
            } catch (_: Exception) {
                "將捨去 ${pending.droppedCount} 項不符合寫入條件的項目（不在來源清單或格式無效）"
            }
            message += "\n$suffix"
        }
        return message
    }

    // Phase D: state-driven sync (Main syncPolicyWriteDialog 精簡版). Null pending
    // dismisses; same-instance showing only refreshes gates; RECONCILING local
    // suppression honored; rotation re-shows from retained VM pending.
    private fun syncPolicyConfirmDialog(pending: PendingPolicyWrite?, phase: PolicyWritePhase) {
        if (isFinishing || isDestroyed) return
        val reconciling = phase == PolicyWritePhase.RECONCILING
        if (pending == null) {
            try { policyConfirmDialog?.dismiss() } catch (_: Exception) { }
            policyConfirmDialog = null
            policyConfirmState.onPendingCleared()
            return
        }
        if (reconciling && policyConfirmState.shouldSuppressForReconciling(pending, true)) {
            try { policyConfirmDialog?.dismiss() } catch (_: Exception) { }
            policyConfirmDialog = null
            return
        }
        if (!reconciling) policyConfirmState.clearDismissIfNotReconciling(false)
        val saving = phase == PolicyWritePhase.SAVING
        // Phase D single source：confirmSaving 經 policyConfirmSaving 映射.
        val confirmSaving = policyConfirmSaving(saving, reconciling)
        if (policyConfirmDialog?.isShowing == true && policyConfirmState.shownPending == pending) {
            policyConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                policyWriteCanConfirmInspectable(pending.packages.size, policyConfirmState.acknowledged, confirmSaving)
            policyConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
            val cancelable = policyDialogShouldBeCancelable(saving, reconciling)
            policyConfirmDialog?.setCancelable(cancelable)
            try { policyConfirmDialog?.setCanceledOnTouchOutside(cancelable) } catch (_: Exception) { }
            policyConfirmState.onSavingFlip(confirmSaving)
            return
        }
        // Not showing (rotation / first show): rebuild with retained ack generation.
        showConfirmDialog(pending)
    }

    // Shared gate (Main single source): ack + total<=200 + not (SAVING||RECONCILING).
    // Kept as thin delegate so call sites read identically on both screens.
    // Phase D single source：saving 映射經 policyConfirmSaving.
    private fun confirmAllowed(total: Int, acknowledged: Boolean): Boolean {
        val savingNow = try {
            val ph = vm.state.value.policyWritePhase
            policyConfirmSaving(ph == PolicyWritePhase.SAVING, ph == PolicyWritePhase.RECONCILING)
        } catch (_: RuntimeException) {
            true
        }
        return policyWriteCanConfirmInspectable(total, acknowledged, savingNow)
    }

    private fun renderList() {
        if (!::listContainer.isInitialized) return
        val query = searchInput.text?.toString().orEmpty()
        val includeSystem = includeSystemCheck.isChecked
        // Catalog is enumerated off the main thread on first load per visit;
        // search keystrokes filter the cache.
        if (catalogCache == null) {
            if (catalogLoading) return
            catalogLoading = true
            listContainer.removeAllViews()
            listContainer.addView(TextView(this).apply {
                text = try {
                    getString(R.string.policy_editor_loading)
                } catch (_: Exception) {
                    "載入中…"
                }
                textSize = 13f
                setTextColor(getColor(R.color.oneui_text_secondary))
                setPadding(0, dp(16), 0, dp(16))
            })
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        loadSourceApps(packageManager, 0) ?: emptyList()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
                catalogCache = loaded
                catalogLoading = false
                renderList()
            }
            return
        }
        val catalog: List<SourceAppEntry> = catalogCache!!
        val sorted = sortSourceApps(filterSourceApps(catalog, query, includeSystem))
        val policy = ticks
        val visible = sorted.take(MAX_LIST_ROWS)
        hintVisible = visible.size
        hintTotal = sorted.size

        iconJob?.cancel()
        listContainer.removeAllViews()
        val iconRows = ArrayList<Pair<String, ImageView>>()

        if (visible.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = try {
                    when {
                        catalog.isEmpty() -> getString(R.string.policy_editor_empty_catalog)
                        query.isBlank() -> getString(R.string.policy_editor_empty_filtered)
                        else -> getString(R.string.policy_editor_empty_nomatch_format, query)
                    }
                } catch (_: Exception) {
                    when {
                        catalog.isEmpty() -> "無法列舉已安裝應用（PackageManager 回傳空或失敗）"
                        query.isBlank() -> "無可用的應用（會被目前的系統應用篩選剔除）；請勾選「顯示系統應用」試試"
                        else -> "無符合「$query」的應用"
                    }
                }
                textSize = 13f
                setTextColor(getColor(R.color.oneui_text_secondary))
                setPadding(0, dp(16), 0, dp(16))
            })
        } else {
            for (entry in visible) {
                val row = buildRow(entry, policy)
                listContainer.addView(row.first)
                iconRows.add(entry.packageName to row.second)
            }
        }

        // Phase E 孤兒區段：catalog 缺失 ticks（不在來源清單）獨立成段＋一鍵移除。
        // 標題／按鈕皆帶參資源化（三語系同鍵）；移除僅動本地 ticks，不碰 store，
        // 實際寫入仍走「寫入政策草稿」→pending→確認兩階段。
        val orphans = try {
            orphanTicks().sorted()
        } catch (_: Exception) {
            emptyList()
        }
        if (orphans.isNotEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = try {
                    getString(R.string.policy_editor_orphan_header_format, orphans.size)
                } catch (_: Exception) {
                    "不在來源清單（${orphans.size}）"
                }
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.oneui_text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12); bottomMargin = dp(4) }
            })
            for (pkg in orphans) {
                listContainer.addView(TextView(this).apply {
                    text = pkg
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(getColor(R.color.oneui_text_secondary))
                    contentDescription = try {
                        getString(R.string.policy_editor_orphan_item_cd_format, pkg)
                    } catch (_: Exception) {
                        "不在來源清單的勾選：$pkg"
                    }
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
            listContainer.addView(Button(this).apply {
                text = try {
                    getString(R.string.policy_editor_orphan_remove_format, orphans.size)
                } catch (_: Exception) {
                    "一鍵移除不在來源清單的勾選（${orphans.size}）"
                }
                // R03：警告在途時禁止改動候選（含一鍵移除孤兒勾選）。
                isEnabled = phaseIsIdle && !pendingClearConfirm
                minimumHeight = dp(48)
                setOnClickListener {
                    if (!phaseIsIdle || pendingClearConfirm) return@setOnClickListener
                    ticks.removeAll(orphans.toSet())
                    renderList()
                    updateCounter()
                }
            })
        }

        countHintView.text = hintText()

        kickIconLoad(iconRows)
    }

    private fun buildRow(entry: SourceAppEntry, policy: Set<String>): Pair<View, ImageView> {
        val pkg = entry.packageName
        val inPolicy = pkg in policy

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(8)
            setPadding(0, p, 0, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(12)
            }
            setImageDrawable(ColorDrawable(0x00000000))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        row.addView(icon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = entry.label
            textSize = 13f
            setTextColor(getColor(R.color.oneui_text_primary))
        })
        textCol.addView(TextView(this).apply {
            text = pkg
            textSize = 10f
            setTextColor(getColor(R.color.oneui_text_tertiary))
        })
        row.addView(textCol)

        val box = CheckBox(this).apply {
            isChecked = inPolicy
            // R03：警告 probe／對話框期間凍結勾選，無法在背後改動候選。
            isEnabled = phaseIsIdle && !pendingClearConfirm
            contentDescription = try {
                getString(R.string.policy_editor_toggle_cd_format, entry.label, pkg)
            } catch (_: Exception) {
                "切換 ${entry.label}（$pkg）的 Hook 政策勾選"
            }
            minHeight = dp(48)
            minWidth = dp(48)
            setOnCheckedChangeListener { _, checked ->
                // Local-only: no store write here. The write goes through
                // 「寫入政策草稿」→ pending → confirm dialog.
                if (checked) {
                    ticks.add(pkg)
                } else {
                    ticks.remove(pkg)
                    // Phase C A 案：編輯器禁清到底——單筆取消導致 ticks 清空時，
                    // 空集連警告都不彈，立即還原並導引去 Main 清除
                    // （改文案＋按鈕；刪「警告＋恆拒」死路）。
                    if (editorEmptyShouldGuide(ticks.isEmpty())) {
                        ticks.add(pkg)
                        renderList()
                        updateCounter()
                        showEditorEmptyGuide()
                    }
                }
                updateCounter()
            }
        }
        row.addView(box)
        row.setOnClickListener { if (phaseIsIdle && !pendingClearConfirm) box.toggle() }

        return row to icon
    }

    private fun kickIconLoad(rows: List<Pair<String, ImageView>>) {
        if (rows.isEmpty()) return
        iconJob?.cancel()
        iconJob = lifecycleScope.launch {
            val icons = withContext(Dispatchers.IO) {
                val map = LinkedHashMap<String, Drawable>()
                for ((pkg, _) in rows) {
                    if (!coroutineContext.isActive) return@withContext map
                    val cached = synchronized(iconCache) { iconCache[pkg] }
                    val icon: Drawable? = cached ?: try {
                        packageManager.getApplicationIcon(pkg)?.also { d ->
                            synchronized(iconCache) { iconCache[pkg] = d }
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (icon != null) map[pkg] = icon
                }
                map
            }
            for ((pkg, iv) in rows) {
                if (!iv.isAttachedToWindow) continue
                icons[pkg]?.let(iv::setImageDrawable)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class SimpleTextWatcher(private val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChange()
    }

    companion object {
        // Minimal test seam (mirrors MainActivity): instrumentation may inject
        // a fake-backed VM to drive the hint views without device writes.
        // Null in production.
        @Volatile
        internal var testViewModel: DualViewModel? = null

        private const val MAX_LIST_ROWS = 200
    }
}

// Phase E 孤兒判定純函數（JVM 可測，無 Android 依賴）：ticks 中不在 catalog 包名集合者。
// 空 catalog（尚未載入／確實無應用）回空集，避免載入中誤判全孤兒；呼叫端僅用 size
// 計數呈現＋一鍵移除，log 亦只記計數，不記包名原文（隱私：log 包名改計數）。
internal fun orphanPackagesOf(ticks: Set<String>, catalogPackageNames: Set<String>): Set<String> {
    if (catalogPackageNames.isEmpty()) return emptySet()
    if (ticks.isEmpty()) return emptySet()
    return try {
        ticks.filterTo(LinkedHashSet()) { it !in catalogPackageNames }
    } catch (_: Exception) {
        emptySet()
    }
}
