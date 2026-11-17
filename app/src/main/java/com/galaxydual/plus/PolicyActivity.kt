// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
// Hook policy editor (RemotePreferences remote policy, "galaxydual_policy" /
// "packages" / StringSet, group semantics in docs/TAB7_BBC_INJECTION_PLAN.md).
//
// The policy here is the union-only whitelist seed the BBC injector appends
// to Samsung's own "可用的應用程式" list (extend-only, empty = observe-only,
// gate still closed on the hook side until device verification). The app
// process is the only writer to the group; the injected processes read a
// volatile snapshot and never pay IPC.
//
// UI is programmatic views, same style as the rest of the app (no Compose,
// no new dependencies). This screen replaces the old free-text package input
// with a searchable installed-apps picker (row checkbox = in policy); writes
// go straight to RemotePreferences via AppPolicyWriter, which is the only
// owner of the XposedService binding (GalaxyDualApplication registers it).
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
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PolicyActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var counterView: TextView
    private lateinit var searchInput: EditText
    private lateinit var includeSystemCheck: CheckBox
    private lateinit var listContainer: LinearLayout
    private lateinit var countHintView: TextView

    private var iconJob: Job? = null
    // Main-thread confined (all listeners fire on the UI thread); re-entrant
    // programmatic flips are swallowed by the checkbox's checked-change listener.
    private var toggleBusy = false

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
                text = "Hook 政策（RemotePreferences）"
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
        }
        column.addView(statusView)

        column.addView(TextView(this).apply {
            text = "政策=白名單種子（納入 Samsung 白名單的union-only 擴充）；預設空政策＝ observe-only；" +
                "變更即時寫入 RemotePreferences（group=galaxydual_policy, key=packages）。已停用的 App 會被BBC hook 過濾。"
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

        // Destructive escape hatch: clear the whole policy (back to observe-only).
        column.addView(Button(this).apply {
            text = "清除全部"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                AlertDialog.Builder(this@PolicyActivity)
                    .setTitle("清除全部政策？")
                    .setMessage("清除後恢復 observe-only（空白名單）。確定清除嗎？")
                    .setPositiveButton("清除") { _, _ ->
                        val result = AppPolicyWriter.setPackages(emptySet())
                        refresh()
                        if (result == null) showError(writeFailureMessage())
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })

        root.addView(column)
        setContentView(root)
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
        showBaselineStatus()
        renderList()
        updateCounter()
    }

    private fun showBaselineStatus() {
        if (AppPolicyWriter.isAvailable()) {
            statusView.text = "Vector 服務：已連線"
            statusView.setTextColor(getColor(R.color.oneui_secondary_tint))
        } else {
            val reason = AppPolicyWriter.unavailableReason()
            statusView.text = "Vector 服務：未連線（唯讀 fail-closed；政策寫入無法生效）" +
                (reason?.takeIf { it.isNotBlank() }?.let { "（原因：$it）" } ?: "")
            statusView.setTextColor(getColor(R.color.oneui_warning_text))
        }
    }

    private fun hintText(): String {
        val policySize = AppPolicyWriter.currentPackages().size
        return if (hintTotal > MAX_LIST_ROWS) {
            "顯示前 ${MAX_LIST_ROWS} 筆（共 $hintTotal 筆，已勾選 $policySize）；請用搜尋縮小範圍"
        } else {
            "共 $hintVisible 筆 · 已勾選 $policySize"
        }
    }

    private fun updateCounter() {
        counterView.text = "目前 ${AppPolicyWriter.currentPackages().size} 個包名"
        if (::countHintView.isInitialized) {
            countHintView.text = hintText()
        }
    }

    private fun showError(msg: String) {
        statusView.text = msg
        statusView.setTextColor(getColor(R.color.oneui_error))
    }

    private fun showInfo(msg: String) {
        statusView.text = msg
        statusView.setTextColor(getColor(R.color.oneui_text_secondary))
    }

    private fun writeFailureMessage(): String {
        val reason = AppPolicyWriter.unavailableReason()
            ?.takeIf { it.isNotBlank() }
            ?.let { "（原因：$it）" }
            .orEmpty()
        return if (AppPolicyWriter.isAvailable()) {
            "寫入失敗：Vector 未確認寫入（commit 回傳 false）$reason"
        } else {
            "寫入失敗：Vector 未連線（唯讀 fail-closed）$reason"
        }
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
        val policy = AppPolicyWriter.currentPackages()
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
            contentDescription = "切換 ${entry.label}（$pkg）的 Hook 政策勾選"
            minHeight = dp(48)
            minWidth = dp(48)
            setOnCheckedChangeListener { button, _ ->
                if (toggleBusy) return@setOnCheckedChangeListener
                onToggle(pkg, button)
            }
        }
        row.addView(box)
        row.setOnClickListener { if (!toggleBusy) box.toggle() }

        return row to icon
    }

    private fun onToggle(pkg: String, box: android.widget.CompoundButton) {
        if (toggleBusy) {
            // A write is already in flight; swallowing this event keeps the
            // row self-consistent with the committed policy state.
            return
        }
        toggleBusy = true
        try {
            val current = AppPolicyWriter.currentPackages().toMutableSet()
            if (box.isChecked) current.add(pkg) else current.remove(pkg)
            val result = AppPolicyWriter.setPackages(current)
            if (result == null) {
                // The checkbox listener ignores programmatic flips while
                // toggleBusy is true, so this cannot re-enter onToggle.
                box.isChecked = !box.isChecked
                showError(writeFailureMessage())
            } else {
                showInfo("已寫入 RemotePreferences（${result.size} 個包名）")
                updateCounter()
            }
        } finally {
            toggleBusy = false
        }
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
