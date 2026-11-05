// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

// F3 entry point (issue #6): real dual-app screen bound to DualViewModel.
//
// UI is programmatic views (no Compose, no new dependencies): a privilege
// indicator, a Shizuku binder line, a channel picker (Auto/Root/Shizuku/
// LSPosed buttons + current-channel line + LSPosed hook-status line), the
// profiles/cloned lists, a package input with clone/remove buttons, a
// refresh button, a Shizuku permission button, and a status line styled per
// MsgKind. Persistence: the picker selection is stored in SharedPreferences
// by the factory/selectChannel, so a restart remembers it.
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
//
// PR-4 P1 (issue #23): Remove is two-phase. The Remove button only calls
// vm.requestRemove() (no uninstall); the confirmation AlertDialog is driven
// by UiState.pendingRemove (syncRemoveDialog): confirm calls
// vm.confirmRemove(), cancel/back calls vm.cancelRemove(). Rotation re-shows
// the same pending from the retained ViewModel with no re-send.
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.app.AlertDialog
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
    private val vm: DualViewModel by viewModels { DualViewModelFactory(applicationContext, packageName) }

    private lateinit var privilegeView: TextView
    private lateinit var shizukuView: TextView
    private lateinit var channelView: TextView
    private lateinit var hookView: TextView
    private lateinit var profilesView: TextView
    private lateinit var clonedView: TextView
    private lateinit var pkgInput: EditText
    private lateinit var cloneBtn: Button
    private lateinit var removeBtn: Button
    private lateinit var refreshBtn: Button
    private lateinit var permBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var rootBtn: Button
    private lateinit var shizukuBtn: Button
    private lateinit var lsposedBtn: Button
    private lateinit var msgView: TextView
    private var defaultMsgColor: Int = Color.BLACK

    // Two-phase remove dialog (PR-4 P1, issue #23): driven by
    // UiState.pendingRemove, never by a click counter. Non-null pending shows
    // the dialog; null dismisses it. Rotation re-shows from state (ViewModel
    // retained, no re-send). shownPending tracks the instance already on
    // screen so repeat renders do not rebuild the dialog.
    private var removeConfirmDialog: AlertDialog? = null
    private var shownPending: PendingRemove? = null

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
            removeConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        removeConfirmDialog = null
        shownPending = null
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
    // state, reloading lists when the channel may be usable. P25: the
    // reread path uses the single reconnectAndRefresh entry (probe with Auto
    // degrade + refresh); the dead-binder path only re-probes (a refresh
    // right now could only fail).
    private fun onShizukuChanged(reread: Boolean) {
        if (reread) vm.reconnectAndRefresh() else vm.recheckPrivilege()
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
        // Phase F4b-P2 (issue #6): channel picker display. channelView shows
        // the persisted selection (SharedPreferences via the factory, so a
        // restart remembers it); hookView shows the hook-enable signal only
        // in LSPosed mode (GONE otherwise). The four buttons call
        // vm.selectChannel(pref), which persists + swaps + re-probes.
        channelView = TextView(this)
        hookView = TextView(this)
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
            // TalkBack: the first tap only opens the confirmation dialog, it
            // never uninstalls; the description states the two-phase contract.
            contentDescription = "移除分身（需二次確認，第一步僅開啟確認對話框）"
            setOnClickListener { vm.requestRemove(pkgInput.text.toString()) }
        }
        refreshBtn = Button(this).apply {
            // P25 single entry: probe (with Auto degrade) + refresh in one tap.
            text = "重新連線並更新"
            setOnClickListener { vm.reconnectAndRefresh() }
        }
        permBtn = Button(this).apply {
            text = "請求 Shizuku 授權"
            setOnClickListener { requestShizukuPermission() }
        }
        // Channel picker: exactly the four ChannelPreference values (NoOp is
        // a fail-closed fallback, never a menu entry). Programmatic buttons
        // in the existing style; labels stay short for one horizontal row.
        autoBtn = Button(this).apply {
            text = "Auto"
            setOnClickListener { vm.selectChannel(ChannelPreference.AUTO) }
        }
        rootBtn = Button(this).apply {
            text = "Root"
            setOnClickListener { vm.selectChannel(ChannelPreference.ROOT) }
        }
        shizukuBtn = Button(this).apply {
            text = "Shizuku"
            setOnClickListener { vm.selectChannel(ChannelPreference.SHIZUKU) }
        }
        lsposedBtn = Button(this).apply {
            text = "LSPosed"
            setOnClickListener { vm.selectChannel(ChannelPreference.LSPOSED) }
        }
        // 確認/取消結果靠 liveRegion 播報（TalkBack polite）。
        msgView = TextView(this).apply {
            textSize = 15f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
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
        // Picker row 1: Auto + Root; row 2: Shizuku + LSPosed (two rows keep
        // labels legible on narrow screens, same weight style as above).
        val channelRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(autoBtn, rowParams())
            addView(rootBtn, rowParams())
        }
        val channelRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(shizukuBtn, rowParams())
            addView(lsposedBtn, rowParams())
        }

        root.addView(privilegeView)
        root.addView(shizukuView)
        root.addView(channelView)
        root.addView(hookView)
        root.addView(channelRow1)
        root.addView(channelRow2)
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

    // Permission button: no binder -> single reconnect entry (probe keeps the
    // honest NONE state); authorized -> single reconnect entry (probe+refresh);
    // otherwise ask Shizuku. The button text mirrors the same split in
    // updateShizukuLine so文案/動作一致.
    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                vm.reconnectAndRefresh()
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                vm.reconnectAndRefresh()
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
    // P25: the permission button text follows the same state so文案/動作一致:
    // 未連線/已授權 -> "重新連線並更新" (reconnectAndRefresh); 未授權 ->
    // "請求 Shizuku 授權" (requestPermission).
    private fun updateShizukuLine() {
        val state: String = try {
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
        shizukuView.text = state
        try {
            permBtn.text = if (state.contains("未授權")) "請求 Shizuku 授權" else "重新連線並更新"
        } catch (_: Exception) {
            // Best effort: updateShizukuLine may run before buildUi in tests.
        }
    }

    // Renders one UiState: privilege indicator, Shizuku line (live), channel
    // picker state + hook signal, both lists, button enabled state, and the
    // MsgKind-styled status line. P25: shows the resolved gateway (actual
    // channel backing the repo) with its exec identity, not just the routed
    // device summary + preference -- so an explicit SHIZUKU pick with ROOT
    // available reports SHELL/SHIZUKU, never "正在使用 ROOT".
    private fun render(s: UiState) {
        val readyMark = if (s.writeReady) "已就緒" else "未就緒（唯讀）"
        val readMark = if (s.readCap) "可用" else "不可用"
        privilegeView.text = "權限：${s.execIdentity}（實際通道 ${s.resolvedGateway}" +
            "・$readyMark；裝置路由 ${s.privilege}・讀取$readMark）"
        // Title mirrors the actual execution identity (not the routed
        // summary) for quick manual verification.
        title = "GalaxyDual Plus (${s.execIdentity}/${s.resolvedGateway})"
        // Picker state: persisted preference + actual resolved gateway
        // (restart remembers the preference via SharedPreferences; see
        // factory). Always shown so the user can tell which channel the
        // lists/privilege above were produced with. The Auto degrade note
        // rides here too so the reason survives even when msg is cleared.
        channelView.text = buildString {
            append("通道偏好：${s.channel}・實際：${s.resolvedGateway}（選擇已持久化，重啟 App 記住）")
            if (s.degradeNote.isNotEmpty()) append("；${s.degradeNote}")
        }
        // Hook signal: LSPosed mode only. ACTIVE shows the recorded scopes as
        // a historical marker (existence evidence only, boot freshness
        // unconfirmed); INACTIVE shows 未偵測到 plus the enable hint; UNKNOWN shows
        // 狀態未知 (P1: unreadable / no diagnostic path is never reported
        // as "not detected") plus the true-channel hint. The placeholder
        // note stays visible so the mode is never mistaken for a working
        // channel.
        if (s.channel == ChannelPreference.LSPOSED) {
            hookView.visibility = View.VISIBLE
            // ACTIVE is historical existence+scope evidence only (the marker
            // survives reboots; S24 evidence shows it predating boot by ~5.5h
            // with no boot-identity/freshness check): the text names that
            // limit instead of claiming the current boot is hooked ("已啟用"
            // would over-claim). Diagnosis stays display-only and hook-neutral.
            val status = when (s.hookStatus) {
                HookStatus.ACTIVE ->
                    "偵測到歷史 Hook 標記（${s.hookScopes.sorted().joinToString(", ")}，僅存在性證據，未確認本次開機是否生效）"
                HookStatus.INACTIVE ->
                    "未偵測到（需在 LSPosed 管理器啟用模組並重啟）"
                HookStatus.UNKNOWN_UNREADABLE ->
                    "狀態未知（無權限讀取；需 ROOT／Shizuku 真通道才能完整診斷，見 #27）"
                HookStatus.UNKNOWN_NO_DIAGNOSTIC ->
                    "狀態未知（無可用診斷通道；切到 ROOT／Shizuku 真通道後重探，見 #27）"
            }
            hookView.text = "LSPosed Hook：$status；寫入通道為佔位（NoOp），" +
                "待 whitelist 政策分支，寫入已禁用"
        } else {
            hookView.visibility = View.GONE
        }
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
        // the read-only placeholder visibly read-only. Channel switches go
        // through the same mutex (selectChannel latest-wins), so the picker
        // is gated too.
        val busy = s.loading
        cloneBtn.isEnabled = !busy
        removeBtn.isEnabled = !busy
        refreshBtn.isEnabled = !busy
        autoBtn.isEnabled = !busy
        rootBtn.isEnabled = !busy
        shizukuBtn.isEnabled = !busy
        lsposedBtn.isEnabled = !busy
        msgView.text = if (busy) "載入中…" else s.msg.ifEmpty { "就緒" }
        msgView.setTextColor(if (busy || (s.msg.isEmpty() && !busy)) defaultMsgColor else colorFor(s.msgKind))
        // Two-phase remove (PR-4 P1, issue #23): the dialog is state-driven.
        // A config rotation re-collects the same pendingRemove from the
        // retained ViewModel and re-shows the dialog; no click is re-sent.
        syncRemoveDialog(s.pendingRemove, s.loading)
    }

    // State-driven confirmation dialog (PR-4 P1, issue #23). Null pending
    // dismisses; a changed pending rebuilds with the fresh target. The first
    // Remove tap only reaches requestRemove() (no uninstall); only the
    // dialog's 確認移除 button calls confirmRemove(). TalkBack reads the
    // title + message + both buttons (standard AlertDialog semantics); the
    // Remove button itself carries a contentDescription stating the
    // two-phase contract (see buildUi).
    // 在途 loading 時對話框保留但按鈕禁用，靠 VM dedup + 按鈕禁用雙保險：
    // 同一 pending 只更新按鈕 enabled（不重建）；confirm 點下後下一幀
    // render(loading=true) 會自動 disable，不用在 click 內手動 disable。
    private fun syncRemoveDialog(pending: PendingRemove?, loading: Boolean) {
        if (isFinishing || isDestroyed) return
        if (pending == null) {
            try {
                removeConfirmDialog?.dismiss()
            } catch (_: Exception) {
                // Best effort: the window may already be gone.
            }
            removeConfirmDialog = null
            shownPending = null
            return
        }
        if (removeConfirmDialog?.isShowing == true && shownPending == pending) {
            removeConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !loading
            removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !loading
            // 在途不可取消，關閉 cancel 窗的使用者路徑；程式化 cancel 由 repo isStillValid 兜底。
            removeConfirmDialog?.setCancelable(!loading)
            removeConfirmDialog?.setCanceledOnTouchOutside(!loading)
            return
        }
        try {
            removeConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort.
        }
        shownPending = pending
        val appLabel = resolveAppLabel(pending.pkg)
        val message = "應用：$appLabel（${pending.pkg}）\n" +
            "已驗證目標：user ${pending.dualId}（${pending.profileLabel}）\n" +
            "警告：將刪除該分身的使用者資料且無法復原；" +
            "僅影響分身（user ${pending.dualId}），不影響主 profile。"
        try {
            removeConfirmDialog = AlertDialog.Builder(this)
                .setTitle("確認移除分身")
                .setMessage(message)
                // Builder 僅有 setCancelable（無 setCanceledOnTouchOutside），touch-outside 由 show 後 dialog 層再設。
                .setCancelable(!loading)
                .setPositiveButton("確認移除", null)
                .setNegativeButton("取消", null)
                .setOnCancelListener { vm.cancelRemove() }
                .show()
        } catch (_: Exception) {
            removeConfirmDialog = null
            shownPending = null
            return
        }
        // 擋自動 dismiss (PR-33 R3, P2): 傳 null listener + show 後掛手動 OnClick，
        // 只調 VM 不 dismiss，對話框在 VM 結果回來前保持開啟；拒絕路徑 VM 清
        // pendingRemove，dialog 隨狀態關閉（需重新請求）；僅 overwrite/race 的
        // preserve 路徑（新 pending 保留）才不關。
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { vm.confirmRemove() }
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { vm.cancelRemove() }
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !loading
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !loading
        // show 後再設一次以覆蓋 render 更新（在途不可取消，關閉 cancel 窗的使用者路徑）。
        removeConfirmDialog?.setCancelable(!loading)
        removeConfirmDialog?.setCanceledOnTouchOutside(!loading)
    }

    // Best-effort display name for the dialog: the PackageManager label when
    // installed for the owner, else the raw package token. Never throws: a
    // missing/unreadable package falls back to the token so the dialog always
    // names the exact uninstall target.
    private fun resolveAppLabel(pkg: String): String =
        try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
        } catch (_: Exception) {
            pkg
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

// Builds the F4b-P1 ViewModel with the persisted channel selection.
// DEVICE-UNVERIFIED: selection/exec never ran on a device.
//
// Selection reuses assembleChannel (ChannelPreference.kt), the same path
// DualViewModel.selectChannel() uses, so startup and re-selection can never
// disagree: the persisted preference (SharedPreferences, default AUTO) is
// resolved with the live environment -- AUTO keeps the F4a order (su file
// presence first, else Shizuku binder alive + authorized, else NoOp
// fail-closed); explicit Root/Shizuku win regardless of probes (probes fail
// closed later); explicit LSPosed is still the NoOp placeholder.
// The hook channel is never auto-selected: there is no app-side evidence
// signal for LSPosed activation yet (DualViewModel keeps hasLsposed=false).
// The su check is a file-presence heuristic, NOT proof su works: the real
// SuElevator/ShizukuElevator probes (wired below with the same instances)
// verify each channel, and DualViewModel flips channelReady only when the
// selected channel's own probe succeeds -- a wrong guess stays read-only.
// P1 closes the F4a "static startup pick" gap: selectChannel() swaps the
// repo gateway under opMutex, and recheckPrivilege() re-resolves AUTO.
// F4b-P2 (issue #6): the picker UI above calls selectChannel() for all four
// preferences; the LSPosed hook-enable signal comes from the marker file
// (HookStateProvider, refreshed on every probe) and is display-only. The
// LSPosed gateway stays the NoOp placeholder: the real hook gateway (plus
// the whitelist policy branch) is still future work.
// DEVICE-UNVERIFIED: marker read + LSPosed placeholder behavior never ran
// on a device (staleness/SELinux limits documented in HookMarker).
private class DualViewModelFactory(
    private val appContext: Context,
    private val appPackageName: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DualViewModel::class.java)) {
            // Application context: the factory is Activity-scoped but the
            // prefs handle must not pin the Activity.
            val prefs = appContext.applicationContext.getSharedPreferences(
                CHANNEL_PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            val pref = loadChannelPreference(prefs)
            val asm = assembleChannel(pref, suBinaryPresent(), shizukuAuthorizedNow(), appPackageName)
            return DualViewModel(
                repo = DualRepository(asm.gateway),
                gatewayPrivilege = asm.gateway.privilege,
                elevators = listOf(SuElevator(asm.suGateway), ShizukuElevator(asm.shizukuGateway)),
                preference = pref,
                appPackageName = appPackageName,
                suGateway = asm.suGateway,
                shizukuGateway = asm.shizukuGateway,
                persistPreference = { p ->
                    try {
                        prefs.saveChannelPreference(p)
                    } catch (_: Exception) {
                        // Best effort: the in-memory swap already happened.
                    }
                },
                suPresentCheck = { suBinaryPresent() },
                shizukuAuthCheck = { shizukuAuthorizedNow() },
                activeGateway = asm.gateway,
                currentChannel = asm.channel,
                // PR-33 S3: TTL 用 monotonic（boot 起算），免 wall-clock 回撥延長
                // 有效期；pending 不跨重啟（process death 清除），reboot 重置無影響。
                // JVM 預設（currentTimeMillis）保留給測試注入。
                nowMs = { SystemClock.elapsedRealtime() },
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
