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
// after a reboot. NOT guaranteed to take effect; device-verified on One UI 5
// (Tab S7) and One UI 9 (S23 Ultra) on 2026-11-17.
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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class PolicyActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var counterView: TextView
    private lateinit var writeBarBtn: Button
    private lateinit var searchInput: EditText
    private lateinit var includeSystemCheck: CheckBox
    private lateinit var listContainer: LinearLayout
    private lateinit var countHintView: TextView

    // Same acquisition pattern as MainActivity (internal DualViewModelFactory,
    // see MainActivity.kt): one VM per screen, fully identical wiring — same
    // dual_policy store, owner-user gates, policyText/plurals resolution.
    private val vm: DualViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
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

    // Issue #73：候選集清空＝整組清除持久化草稿＝ BBC6 風險（政策清空＝Samsung 自動
    // 刪除 user-95 分身，資料不可復原）。先探測分身安裝狀態並顯示警告對話框，
    // 通過後才執行實際寫入／保持未勾選；詳見 MainActivity 同名流程。
    // pendingClearConfirm：probe／警告對話框存活期忽略再次操作，避免重複彈窗。
    private var pendingClearConfirm = false

    // 目前持久化草稿（UiState.policyDraft 即已保存之草稿）；讀取失敗回 null，
    // 呼叫端 fail-closed 走探測失敗警告，不信任 lastSyncedDraft 鏡像。
    private fun persistedPolicyDraftOrNull(): Set<String>? = try {
        vm.state.value.policyDraft
    } catch (_: RuntimeException) {
        null
    }

    private fun requestPreClearWarning(proceed: () -> Unit, onCancel: () -> Unit = {}, proceedLabelRes: Int = R.string.pre_clear_confirm_proceed) {
        val persisted = persistedPolicyDraftOrNull()
        if (persisted == null) {
            if (pendingClearConfirm) return
            pendingClearConfirm = true
            showPreClearProbeFailedWarning(
                this@PolicyActivity,
                onProceed = { pendingClearConfirm = false; proceed() },
                onCancel = { pendingClearConfirm = false; onCancel() },
                proceedLabelRes = proceedLabelRes,
            )
            return
        }
        if (persisted.isEmpty()) {
            proceed()
            return
        }
        if (pendingClearConfirm) return
        pendingClearConfirm = true
        lifecycleScope.launch {
            // Phase B：與 MainActivity 同口徑——僅 Ok(empty) 直接放行，
            // Failed 一律走探測失敗警告；Ok(non-empty) 走雙開提醒。
            val probe = try {
                probeDualAppsInUser95(persisted)
            } catch (e: CancellationException) {
                pendingClearConfirm = false
                throw e
            } catch (e: Exception) {
                preClearLog("pre-clear dual-profile probe failed", e)
                DualProbeResult.Failed("exec-failed: ${(e.message ?: e.javaClass.simpleName).take(200)}")
            }
            if (isFinishing || isDestroyed) {
                pendingClearConfirm = false
                return@launch
            }
            when (probe) {
                is DualProbeResult.Failed -> {
                    showPreClearProbeFailedWarning(
                        this@PolicyActivity,
                        onProceed = { pendingClearConfirm = false; proceed() },
                        onCancel = { pendingClearConfirm = false; onCancel() },
                        proceedLabelRes = proceedLabelRes,
                    )
                    return@launch
                }
                is DualProbeResult.Ok -> {
                    if (probe.installed.isEmpty()) {
                        pendingClearConfirm = false
                        proceed()
                        return@launch
                    }
                    showPreClearWarning(
                        this@PolicyActivity,
                        probe.installed,
                        onProceed = { pendingClearConfirm = false; proceed() },
                        onCancel = { pendingClearConfirm = false; onCancel() },
                        proceedLabelRes = proceedLabelRes,
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                text = "返回"
                setOnClickListener { finish() }
            })
            addView(TextView(this@PolicyActivity).apply {
                text = "Hook 政策（Dual Messenger 可用應用程式）"
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

        column.addView(TextView(this).apply {
            text = getString(R.string.policy_editor_intro)
            textSize = 11f
            setTextColor(getColor(R.color.oneui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); bottomMargin = dp(10) }
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
            text = "顯示系統應用（政策通常只需用於第三方）"
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
        writeBarBtn = Button(this).apply {
            text = "寫入政策草稿（0）"
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
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
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

    private fun hintText(): String {
        val policySize = ticks.size
        return if (hintTotal > MAX_LIST_ROWS) {
            "顯示前 ${MAX_LIST_ROWS} 筆（共 $hintTotal 筆，已勾選 $policySize）；請用搜尋縮小範圍"
        } else {
            "共 $hintVisible 筆 · 已勾選 $policySize"
        }
    }

    private fun updateCounter() {
        counterView.text = "目前 ${ticks.size} 個包名"
        if (::countHintView.isInitialized) {
            countHintView.text = hintText()
        }
        if (::writeBarBtn.isInitialized) {
            writeBarBtn.isEnabled = phaseIsIdle && policyDraftKnown
            writeBarBtn.text = if (policyDraftKnown) {
                "寫入政策草稿（${ticks.size}）"
            } else {
                getString(R.string.policy_unknown_refresh)
            }
        }
    }

    private fun showInfo(msg: String) {
        statusView.text = msg
        statusView.setTextColor(getColor(R.color.oneui_text_secondary))
    }

    private fun onWritePolicyClicked() {
        if (!phaseIsIdle) return
        if (!policyDraftKnown) {
            // Main-style Unknown guidance: 不寫入（fail-closed）；使用者先
            // 在 MainActivity「重新整理草稿」後返回。
            showInfo(getString(R.string.policy_unknown_refresh))
            return
        }
        // Issue #73：候選集空且持久化草稿非空＝整組清除（BBC6 風險）→
        // 先探測 user-95 分身安裝並顯示警告；取消則不寫入。
        // Unknown persisted content (null) also triggers the warning (fail-closed).
        val persistedForEmptyCheck = persistedPolicyDraftOrNull()
        if (ticks.isEmpty() && (persistedForEmptyCheck == null || persistedForEmptyCheck.isNotEmpty())) {
            // VM 永遠拒絕空集合寫入（CATALOG_MISSING／Two-phase 管線的整組契約），
            // 因此編輯器路徑的「繼續」不會真的清除——只是帶使用者看完警告後，
            // performWritePolicy() 仍走 VM 並失敗落在狀態列。按鈕標籤必須如實說明。
            requestPreClearWarning(proceed = { performWritePolicy() }, proceedLabelRes = R.string.pre_clear_editor_try_anyway)
            return
        }
        performWritePolicy()
    }

    private fun performWritePolicy() {
        // The policy set comes from the editor's checked list, never from the
        // catalog selection; selectedKeys is left untouched by the VM.
        vm.replacePolicyDraft(HashSet(ticks))
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
            isChecked = false
        }
        container.addView(ack)

        var dialog: AlertDialog? = null
        ack.setOnCheckedChangeListener { _, checked ->
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                confirmAllowed(total, checked)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.policy_write_title)
            .setMessage(resources.getQuantityString(R.plurals.policy_write_message, total, total))
            .setView(container)
            .setCancelable(true)
            // 「確認寫入」/「取消」：與主列表流程同鍵同義（VM 世代守衛再驗一次）。
            .setPositiveButton(R.string.policy_write_positive, null)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> vm.cancelPolicyWrite(pending) }
            .setOnCancelListener { vm.cancelPolicyWrite(pending) }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = false
            setOnClickListener {
                // Positive fires only with the ack checked (gate below + here);
                // VM re-validates everything else (generation CAS, RECONCILING,
                // in-flight, coordinator fences) — the dialog is not authority.
                if (!ack.isChecked) return@setOnClickListener
                val live = try {
                    vm.state.value.pendingPolicyWrite
                } catch (_: RuntimeException) {
                    null
                } ?: run { dialog?.dismiss(); return@setOnClickListener }
                vm.confirmPolicyWrite(live, live.generation)
            }
        }
    }

    // Minimal replica of MainActivity's policyWriteCanConfirmInspectable gate:
    // ack checked + total <= 200 + phase != RECONCILING. The VM re-validates
    // everything else (SAVING / BUSY / revision fences) on confirm.
    private fun confirmAllowed(total: Int, acknowledged: Boolean): Boolean {
        if (!acknowledged) return false
        if (total > POLICY_DIALOG_DISPLAY_MAX) return false
        val phase = try {
            vm.state.value.policyWritePhase
        } catch (_: RuntimeException) {
            PolicyWritePhase.RECONCILING
        }
        return phase != PolicyWritePhase.RECONCILING
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
                text = "載入中…"
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
                text = when {
                    catalog.isEmpty() -> "無法列舉已安裝應用（PackageManager 回傳空或失敗）"
                    query.isBlank() -> "無可用的應用（會被目前的系統應用篩選剔除）；請勾選「顯示系統應用」試試"
                    else -> "無符合「$query」的應用"
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
            isEnabled = phaseIsIdle
            contentDescription = "切換 ${entry.label}（$pkg）的 Hook 政策勾選"
            minHeight = dp(48)
            minWidth = dp(48)
            setOnCheckedChangeListener { _, checked ->
                // Local-only: no store write here. The write goes through
                // 「寫入政策草稿」→ pending → confirm dialog.
                if (checked) {
                    ticks.add(pkg)
                } else {
                    ticks.remove(pkg)
                    // Issue #73：單筆取消導致 ticks 清空＝整組清除的前置狀態
                    // （持久化草稿將被寫成空＝BBC6 風險）→ 先探測 user-95 分身並警告；
                    // 繼續＝保持未勾選，取消＝還原此勾選（行內恢復）。
                    // Unknown persisted content (null) also triggers the warning (fail-closed).
                    val persistedForUncheck = persistedPolicyDraftOrNull()
                    if (ticks.isEmpty() && (persistedForUncheck == null || persistedForUncheck.isNotEmpty())) {
                        // proceed 為 no-op（僅保持未勾選；真正的「清空政策」只在
                        // 「寫入政策草稿（0）」或 MainActivity 清除草稿時發生）——按鈕必須如實說明。
                        requestPreClearWarning(
                            proceed = {},
                            onCancel = {
                                ticks.add(pkg)
                                renderList()
                                updateCounter()
                            },
                            proceedLabelRes = R.string.pre_clear_editor_keep_unticked,
                        )
                    }
                }
                updateCounter()
            }
        }
        row.addView(box)
        row.setOnClickListener { if (phaseIsIdle) box.toggle() }

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
        private const val MAX_LIST_ROWS = 200
    }
}
