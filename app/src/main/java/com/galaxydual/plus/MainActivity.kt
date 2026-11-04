// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

// F3 entry point (issue #6): real dual-app screen bound to DualViewModel.
//
// UI is programmatic views (no Compose, no new dependencies): a privilege
// indicator, the profiles/cloned lists, a package input with clone/remove
// buttons, a refresh button, a Shizuku permission button, and a status line
// styled per MsgKind.
//
// Shizuku lifecycle: binder received/dead listeners are registered in onCreate
// (received is sticky, so an already-live binder fires immediately) and
// removed in onDestroy; the permission flow uses SHIZUKU_REQUEST_CODE with
// Shizuku.requestPermission plus an OnRequestPermissionResultListener, while
// the Activity onRequestPermissionsResult override is kept as a defensive seam
// (Shizuku delivers via its own listener; the override only reacts when our
// code matches). onResume re-probes privilege. Every binder/permission event
// routes into vm.recheckPrivilege() (plus vm.refresh() when the channel may
// be usable), so the state always tracks the live binder.
//
// Execution seam (F4a): the repository behind the ViewModel is the
// factory-selected real gateway (su/Shizuku UserService) with NoOp fallback.
// Probes verify each channel and channelReady gates writes; without a device
// the exec round-trip is DEVICE-UNVERIFIED (see gateway file headers).
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import java.io.File
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

// Observes DualViewModel.state, drives Shizuku binder/permission lifecycle,
// and renders the dual-app screen. All shell work stays in the ViewModel.
class MainActivity : ComponentActivity() {
    // ViewModel with the F4a real-channel repository. No hardcoded privilege
    // flags here: the ViewModel probes elevators and routes the decision.
    // DEVICE-UNVERIFIED: gateway selection/exec never ran on a device.
    private val vm: DualViewModel by viewModels { DualViewModelFactory(packageName) }

    private lateinit var privilegeView: TextView
    private lateinit var shizukuView: TextView
    private lateinit var profilesView: TextView
    private lateinit var clonedView: TextView
    private lateinit var pkgInput: EditText
    private lateinit var cloneBtn: Button
    private lateinit var removeBtn: Button
    private lateinit var refreshBtn: Button
    private lateinit var permBtn: Button
    private lateinit var msgView: TextView
    private var defaultMsgColor: Int = Color.BLACK

    // Main-thread delivery for binder/permission callbacks; the ViewModel
    // entry points are thread-safe anyway (viewModelScope), this only keeps
    // the shizuku status line update on the UI thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Binder attach (sticky: fires immediately when already connected).
    // The channel may be usable now: re-probe privilege, then reload lists.
    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener { onShizukuChanged(reread = true) }

    // Binder death: the channel is gone. Re-probe only (probe honestly
    // reports NONE + 通道未就緒); a refresh right now could only fail.
    private val binderDeadListener =
        Shizuku.OnBinderDeadListener { onShizukuChanged(reread = false) }

    // Canonical Shizuku permission result path. Only our request code is
    // honoured; anything else is ignored.
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_REQUEST_CODE) onShizukuChanged(reread = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        // Shizuku lifecycle wiring; guarded so a device without the Shizuku
        // manager (or an unexpected API failure) never crashes the UI.
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler)
            Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
            Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler)
        } catch (e: Exception) {
            msgView.text = "Shizuku 監聽註冊失敗：${e.message}"
            msgView.setTextColor(colorFor(MsgKind.CHANNEL))
        }
        // Lifecycle-aware collection: renders every state update while STARTED.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s -> render(s) }
            }
        }
        updateShizukuLine()
        // Initial load; queued behind the ViewModel's channel probe.
        vm.refresh()
    }

    override fun onResume() {
        super.onResume()
        // Re-probe privilege on every foreground return: the user may have
        // granted/revoked Shizuku (or started/stopped its service) while away.
        vm.recheckPrivilege()
        updateShizukuLine()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            // Best effort: listeners are already gone with the process anyway.
        }
        super.onDestroy()
    }

    // Defensive seam, NOT the Shizuku result path (Shizuku delivers through
    // permissionResultListener above). Reacts only when our request code
    // matches, so unrelated runtime-permission results are ignored.
    // Marked deprecated (per compiler guidance for overriding the deprecated
    // framework hook): Shizuku dispatches through its own listener, not the
    // Activity Result API, so this override is only a defensive seam.
    @Deprecated(
        "Shizuku delivers via OnRequestPermissionResultListener; " +
            "this override is only a defensive seam.",
    )
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SHIZUKU_REQUEST_CODE) onShizukuChanged(reread = true)
    }

    // Shared binder/permission event handling: re-probe privilege and update
    // state, reloading lists when the channel may be usable.
    private fun onShizukuChanged(reread: Boolean) {
        vm.recheckPrivilege()
        if (reread) vm.refresh()
        updateShizukuLine()
    }

    // Programmatic layout: ScrollView > vertical LinearLayout. Keeps the
    // dependency set minimal (no Compose, no XML resources to maintain).
    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        privilegeView = TextView(this).apply { textSize = 18f }
        shizukuView = TextView(this)
        profilesView = TextView(this)
        clonedView = TextView(this)
        pkgInput = EditText(this).apply {
            hint = "package name (e.g. com.example.app)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        cloneBtn = Button(this).apply {
            text = "Clone"
            setOnClickListener { vm.clone(pkgInput.text.toString()) }
        }
        removeBtn = Button(this).apply {
            text = "Remove"
            setOnClickListener { vm.remove(pkgInput.text.toString()) }
        }
        refreshBtn = Button(this).apply {
            text = "Refresh"
            setOnClickListener { vm.refresh() }
        }
        permBtn = Button(this).apply {
            text = "請求 Shizuku 授權"
            setOnClickListener { requestShizukuPermission() }
        }
        msgView = TextView(this).apply { textSize = 15f }
        defaultMsgColor = msgView.currentTextColor

        // Clone/remove share one row; refresh + permission share the next.
        val writeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cloneBtn, rowParams())
            addView(removeBtn, rowParams())
        }
        val readRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshBtn, rowParams())
            addView(permBtn, rowParams())
        }

        root.addView(privilegeView)
        root.addView(shizukuView)
        root.addView(profilesView)
        root.addView(clonedView)
        root.addView(pkgInput)
        root.addView(writeRow)
        root.addView(readRow)
        root.addView(msgView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    // Permission button: no binder -> nothing to request (re-probe keeps the
    // honest NONE state); authorized -> just reload; otherwise ask Shizuku.
    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                vm.recheckPrivilege()
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                vm.refresh()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        } catch (e: Exception) {
            vm.recheckPrivilege()
        }
        updateShizukuLine()
    }

    // Shizuku binder/authorization line, read live (never cached): pingBinder
    // guards checkSelfPermission so a dead binder shows 未連線, not a crash.
    private fun updateShizukuLine() {
        shizukuView.text = try {
            if (!Shizuku.pingBinder()) {
                "Shizuku：binder 未連線（需安裝並啟動 Shizuku）"
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Shizuku：已連線・已授權"
            } else {
                "Shizuku：已連線・未授權（請點下方授權）"
            }
        } catch (e: Exception) {
            "Shizuku：狀態未知（${e.message}）"
        }
    }

    // Renders one UiState: privilege indicator, both lists, button enabled
    // state, and the MsgKind-styled status line.
    private fun render(s: UiState) {
        val channel =
            if (!s.channelReady) "${s.privilege}（通道未就緒）" else s.privilege.toString()
        privilegeView.text = "權限：$channel"
        // Title mirrors the routed privilege for quick manual verification.
        title = "GalaxyDual Plus ($channel)"
        profilesView.text = if (s.profiles.isEmpty()) {
            "Profiles：—"
        } else {
            "Profiles (${s.profiles.size})：\n" +
                s.profiles.joinToString("\n") { "  #${it.userId} ${it.label}" }
        }
        clonedView.text = if (s.cloned.isEmpty()) {
            "Cloned：—"
        } else {
            "Cloned (${s.cloned.size})：\n" +
                s.cloned.joinToString("\n") { "  $it" }
        }
        // Writes queue in the ViewModel anyway; disabling while busy keeps
        // the read-only placeholder visibly read-only.
        val busy = s.loading
        cloneBtn.isEnabled = !busy
        removeBtn.isEnabled = !busy
        refreshBtn.isEnabled = !busy
        msgView.text = if (busy) "載入中…" else s.msg.ifEmpty { "就緒" }
        msgView.setTextColor(if (busy || (s.msg.isEmpty() && !busy)) defaultMsgColor else colorFor(s.msgKind))
    }

    companion object {
        // Shizuku permission request code; matched in both the Shizuku result
        // listener and the defensive onRequestPermissionsResult override.
        private const val SHIZUKU_REQUEST_CODE = 1001

        // Status-line color per message class; NONE falls back to default.
        private fun colorFor(kind: MsgKind): Int = when (kind) {
            MsgKind.NONE -> Color.BLACK
            MsgKind.INFO -> Color.rgb(0x1B, 0x7A, 0x2B)
            MsgKind.INVALID -> Color.rgb(0xB2, 0x6A, 0x00)
            MsgKind.NO_DUAL -> Color.rgb(0x9A, 0x6B, 0x00)
            MsgKind.VERIFY -> Color.rgb(0xB0, 0x00, 0x20)
            MsgKind.CHANNEL -> Color.rgb(0x6A, 0x1B, 0x9A)
            MsgKind.FAILED -> Color.rgb(0xB0, 0x00, 0x20)
        }
    }
}

// Builds the F4a ViewModel with the real-channel gateway selection.
// DEVICE-UNVERIFIED: selection/exec never ran on a device.
//
// Selection (synchronous, main-thread safe: stat + binder ping only, no
// exec): ROOT first per router priority, then SHELL, else fail closed.
// - su binary present (canonical paths) -> SuCmdGateway (ROOT).
// - else Shizuku binder alive AND authorized -> ShizukuCmdGateway (SHELL).
// - else NoOpGateway (NONE): read-only, channelReady stays false.
// The hook channel is never selected: there is no app-side evidence signal
// for LSPosed activation yet (DualViewModel keeps hasLsposed=false, F4b).
// The su check is a file-presence heuristic, NOT proof su works: the real
// SuElevator/ShizukuElevator probes (wired below with the same instances)
// verify each channel, and DualViewModel flips channelReady only when the
// selected channel's own probe succeeds -- a wrong guess stays read-only.
// TODO(F4b): dynamic re-selection (swap the repo gateway when later probes
// disagree with the startup pick, e.g. su guess wrong but Shizuku works).
private class DualViewModelFactory(private val appPackageName: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DualViewModel::class.java)) {
            val su = SuCmdGateway()
            val shizuku = ShizukuCmdGateway(appPackageName)
            val selected: CmdGateway = when {
                suBinaryPresent() -> su
                shizukuAuthorizedNow() -> shizuku
                else -> NoOpGateway()
            }
            return DualViewModel(
                DualRepository(selected),
                gatewayPrivilege = selected.privilege,
                elevators = listOf(SuElevator(su), ShizukuElevator(shizuku)),
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }

    companion object {
        // Canonical su locations; presence heuristic only (see header).
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")

        internal fun suBinaryPresent(): Boolean =
            SU_PATHS.any {
                try {
                    File(it).exists()
                } catch (_: Exception) {
                    false
                }
            }

        // Live binder + granted permission, read synchronously (never cached).
        internal fun shizukuAuthorizedNow(): Boolean =
            try {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
    }
}
