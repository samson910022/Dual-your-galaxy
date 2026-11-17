// MIT License - Original code by Galaxy-Dual-app-Plus contributors.
package com.galaxydual.plus

// Policy editor for the Hook RemotePreferences whitelist seed.
//
// Programmatic views (no Compose, no new UI dependencies), consistent with
// MainActivity's no-Compose style. Writes go through AppPolicyWriter:
// group=galaxydual_policy, key=packages, StringSet, wholesale replacement
// per edit. Empty policy = observe-only. Fail-closed: when Vector is not
// bound this page is read-only; write failures surface in the status line.
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.galaxydual.hook.PolicySource

class PolicyActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var counterView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var packageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.oneui_canvas))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(12), pad, dp(24))
        }

        // Title row: back button top-left + screen title.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
        titleRow.addView(Button(this).apply {
            text = "返回"
            contentDescription = "返回主頁"
            setOnClickListener { finish() }
        })
        titleRow.addView(TextView(this).apply {
            text = "Hook 政策（RemotePreferences）"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.oneui_text_primary))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) }
        })
        column.addView(titleRow)

        // Status line (mono): connection state / last write failure.
        statusView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.oneui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
        column.addView(statusView)

        // Subtitle: policy semantics, pinned group/key.
        column.addView(TextView(this).apply {
            text = "政策=白名單種子（union-only union with Samsung whitelist）；空政策 = observe-only；本頁即時寫入 RemotePreferences（group=galaxydual_policy, key=packages）。"
            textSize = 11f
            setTextColor(getColor(R.color.oneui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        })

        counterView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.oneui_text_primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        column.addView(counterView)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        column.addView(listContainer)

        // Add row: package input + 新增 button.
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        packageInput = EditText(this).apply {
            hint = "com.example.app"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        val addBtn = Button(this).apply {
            text = "新增"
            setOnClickListener { onAddClicked() }
        }
        inputRow.addView(packageInput)
        inputRow.addView(addBtn)
        column.addView(inputRow)

        // Destructive escape hatch: clear the whole policy (back to observe-only).
        column.addView(Button(this).apply {
            text = "清除全部"
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
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun onAddClicked() {
        val pkg = packageInput.text?.toString()?.trim().orEmpty()
        if (!PolicySource.isValidPackage(pkg)) {
            showError("包名格式錯誤：$pkg")
            return
        }
        val current = AppPolicyWriter.currentPackages()
        if (current.contains(pkg)) {
            showInfo("已在政策中：$pkg")
            return
        }
        val result = AppPolicyWriter.setPackages(current + pkg)
        refresh()
        if (result == null) {
            showError(writeFailureMessage())
        } else {
            packageInput.text?.clear()
            showInfo("已寫入政策：$pkg")
        }
    }

    private fun onRemoveClicked(pkg: String, snapshot: Set<String>) {
        val result = AppPolicyWriter.setPackages(snapshot - pkg)
        refresh()
        if (result == null) showError(writeFailureMessage())
    }

    // Re-read the local snapshot and rebind rows + counter + status line.
    private fun refresh() {
        listContainer.removeAllViews()
        val pkgs = AppPolicyWriter.currentPackages()
        counterView.text = "目前 ${pkgs.size} 個包名"
        if (pkgs.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "（空政策 = observe-only）"
                textSize = 11f
                setTextColor(getColor(R.color.oneui_text_tertiary))
                setPadding(0, dp(4), 0, dp(4))
            })
        } else {
            for (p in pkgs.sorted()) {
                listContainer.addView(buildRow(p, pkgs))
            }
        }
        showBaselineStatus()
    }

    private fun buildRow(pkg: String, snapshot: Set<String>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(TextView(this).apply {
            text = pkg
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.oneui_text_primary))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        })
        row.addView(Button(this).apply {
            text = "移除"
            contentDescription = "從政策移除 $pkg"
            setOnClickListener { onRemoveClicked(pkg, snapshot) }
        })
        return row
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
