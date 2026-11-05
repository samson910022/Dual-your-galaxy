// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

// F3 entry point (issue #6): real dual-app screen bound to DualViewModel.
//
// UI is programmatic views (no Compose, no new dependencies): a privilege
// indicator, a Shizuku binder line, a channel picker (Auto/Root/Shizuku/
// LSPosed buttons + current-channel line + LSPosed hook-status line), the
// profiles/cloned lists, a package input with clone/remove buttons, a
// 重新連線並更新 button, a Shizuku permission button, and a status line styled per
// MsgKind. Persistence: the picker selection is stored in SharedPreferences
// by the factory/selectChannel, so a restart remembers it.
//
// Shizuku lifecycle: binder received/dead listeners are registered in onCreate
// (received is sticky, so an already-live binder fires immediately) and
// removed in onDestroy; the permission flow uses SHIZUKU_REQUEST_CODE with
// Shizuku.requestPermission plus an OnRequestPermissionResultListener, while
// the Activity onRequestPermissionsResult override is kept as a defensive seam
// (Shizuku delivers via its own listener; the override only reacts when our
// code matches). onResume routes into vm.onForeground(): 過期 FRESH 先補標
// STALE，inventory 非 FRESH 或通道改變 (resolved/ready 變化) 即自動重查
// (probe+refresh)，否則只輕探 (hook UNKNOWN 只重 probe，不觸發全量重查，
// 見 P2-6)，
// 否則只輕探 (PR-6 P2 #27 後半)。Every binder/permission event routes into
// vm.reconnectAndRefresh() (reread path: probe with Auto degrade +
// refresh) or vm.recheckPrivilege() (dead-binder path: probe only), so the
// state always tracks the live binder.
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.app.AlertDialog
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
import com.galaxydual.core.CloneOutcome
import com.galaxydual.core.DualRepository
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // PR-6 P2 (#27 後半)：清單新鮮度行 + 診斷事件行 (穩定碼/摘要/下一步，可點開展開技術細節)。
    // P4：上次操作相位拆成獨立 lastOpView 行 (原附於清單行尾)。
    private lateinit var inventoryView: TextView
    private lateinit var lastOpView: TextView
    private lateinit var diagView: TextView
    private lateinit var exportBtn: Button
    private lateinit var profilesView: TextView
    private lateinit var clonedView: TextView
    private lateinit var pkgInput: EditText
    private lateinit var cloneBtn: Button
    private lateinit var removeBtn: Button
    private lateinit var refreshBtn: Button
    // PR-7 P2 (#26, 階段 1):個人來源唯讀 catalog + 勾選。
    // searchInput/系統開關只改 UiState 搜尋狀態；清單列為 CheckBox (唯讀展示
    // + 勾選,取消勾選 ≠ 卸載)；batchBtn 顯示「建立 N 個分身」,走
    // vm.cloneSelected (既有單筆 cloneVerified 逐項,見 DualViewModel)。
    private lateinit var searchInput: EditText
    private lateinit var systemToggleBtn: Button
    private lateinit var selectAllBtn: Button
    private lateinit var clearSelBtn: Button
    private lateinit var saveSelBtn: Button
    private lateinit var cancelSaveBtn: Button
    private lateinit var sourceContainer: LinearLayout
    private lateinit var catalogErrorView: TextView
    private lateinit var batchBtn: Button
    private lateinit var batchResultView: TextView
    private lateinit var permBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var rootBtn: Button
    private lateinit var shizukuBtn: Button
    private lateinit var lsposedBtn: Button
    // P2-1: narrow-consent privileged hook re-diagnosis (retained `cat`).
    // Only this tap passes allowPrivilegedHookDiagnosis=true (explicit user
    // consent for a retained privileged read). Every other entry (picker
    // taps, reconnect taps, onResume/binder auto paths) passes false: a mode
    // switch / reconnect is NOT consent for privileged diagnosis.
    private lateinit var hookDiagBtn: Button
    // Phase C P1 P3: visible pre-tap explanation for sighted users. The button
    // contentDescription already states the privileged exec for TalkBack, but
    // visual users never hear it -- this line shows the same consent copy
    // ("will run ROOT/Shizuku diagnostic commands") next to the button.
    private lateinit var hookDiagHint: TextView
    // Row holding the manual hook-diagnosis button: visible only in LSPOSED
    // mode (same condition as hookView) so the opt-in entry never suggests
    // itself on a true channel. Enable still follows the shared busy gate.
    private lateinit var hookRow: LinearLayout
    private lateinit var msgView: TextView
    private var defaultMsgColor: Int = Color.BLACK

    // Two-phase remove dialog (PR-4 P1, issue #23): driven by
    // UiState.pendingRemove, never by a click counter. Non-null pending shows
    // the dialog; null dismisses it. Rotation re-shows from state (ViewModel
    // retained, no re-send). shownPending tracks the instance already on
    // screen so repeat renders do not rebuild the dialog.
    private var removeConfirmDialog: AlertDialog? = null
    private var shownPending: PendingRemove? = null

    // 診斷匯出預覽對話框 (比照 removeConfirmDialog)：旋轉/銷毀時需主動
    // dismiss，否則舊 Activity 的 window 洩漏。
    private var exportDialog: AlertDialog? = null

    // Main-thread delivery for binder/permission callbacks; the ViewModel
    // entry points are thread-safe anyway (viewModelScope), this only keeps
    // the shizuku status line update on the UI thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    // PR-7 P2 (#26):圖示快取 (appKey -> Drawable,有界)。
    // 只供清單列顯示用,永不參與身分鍵/選取/寫入決策 (見 SourceIconState)。
    // PR-37 Phase C #5: drawables 與 iconMemo 同進退——淘汰最舊 Drawable 時
    // 必須同步 evict memo 對應 key（見 evictIcon），否則殘留 loaded 會永久
    // 擋住該 App 的重載（快取 miss＋已 loaded → 直接 return，白列無圖）。
    private val iconMemo = SourceIconState()
    private val iconDrawables = LinkedHashMap<String, Drawable>()
    // PR-37 Phase C #4 圖示 in-flight 追蹤（與已載入分離）：IO 在途的 appKey，
    // 避免同一指紋重建/重繪重複打 PackageManager；成功才 markLoaded。
    private val iconInflight = mutableSetOf<String>()

    // PR-6 P2 (#27 後半)：診斷技術細節展開開關 + 最近一次渲染狀態
    // (點診斷行切換展開/收合，需重渲染同一狀態)。
    private var diagExpanded: Boolean = false
    private var lastState: UiState? = null

    // PR-37 P3 差量渲染：上次全量重建來源列表的指紋
    // (catalog 引用+內容 hash+query+includeSystem+selectedKeys hash)。
    // loading/msg/diagnose/batchReport 變化只更新對應文字區，不重建列表。
    private var lastSourceListKey: String? = null
    // catalog 載入失敗的獨立錯誤文案（與「尚未載入」區分；失敗保留舊列表）。
    private var catalogLoadError: String? = null
    // 啟動 double-load 守衛：onCreate+onResume 雙 reloadCatalog 取消前一個在途任務。
    private var catalogJob: Job? = null

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
        // 旋轉恢復：diagExpanded 純 UI 開關，隨 Activity 存活即可。
        diagExpanded = savedInstanceState?.getBoolean(KEY_DIAG_EXPANDED) ?: false
        buildUi()
        // Shizuku lifecycle wiring; guarded so a device without the Shizuku
        // manager (or an unexpected API failure) never crashes the UI.
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler)
            Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
            Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler)
        } catch (e: Exception) {
            msgView.text = "Shizuku 監聽註冊失敗：" +
                sanitizeForDisplay(e.message ?: "unknown")
            msgView.setTextColor(colorFor(MsgKind.CHANNEL))
        }
        // Lifecycle-aware collection: renders every state update while STARTED.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s -> render(s) }
            }
        }
        updateShizukuLine()
        // 冷啟動單一入口：初查由 onResume.onForeground() (probe+按需 refresh)
        // 覆蓋，此處不再直調 vm.refresh()，避免與 init probe/onForeground/
        // binder sticky reconnect 在 mutex 上三重排隊。
        // 覆蓋論證：冷啟動 ViewModel 為全新實例，inventory=UNKNOWN，
        // onForeground 判 UNKNOWN 必走 refreshLocked；binder 已連線時 sticky
        // 監聽另觸發一次 reconnectAndRefresh (probe+refresh)，初查只多不少。
        // PR-7 P2 (#26):載入個人來源 catalog (授權範圍唯讀,不跨 user)。
        reloadCatalog()
    }

    override fun onResume() {
        super.onResume()
        // PR-6 P2 (#27 後半)：前景不只 recheck。onForeground 先把過期
        // FRESH 補標 STALE，inventory 非 FRESH 或通道改變 (resolved/ready
        // 變化) 即自動重查 (probe+refresh)，否則只輕探。
        // P2-6：hook UNKNOWN 不再觸發全量重查（probe 本就重跑 hook 診斷）。
        // P2-1 (base)：auto 路徑預設 allowPrivilegedHookDiagnosis=false，
        // 不執行特權 cat，與直接調 recheckPrivilege(false) 同契約。
        vm.onForeground()
        updateShizukuLine()
        // PR-7 P2 (#26):前景重載來源 catalog (安裝/卸載後保持新鮮)。
        reloadCatalog()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋轉保留診斷展開開關 (純 UI 狀態，不經 ViewModel)。
        outState.putBoolean(KEY_DIAG_EXPANDED, diagExpanded)
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
            exportDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        exportDialog = null
        try {
            catalogJob?.cancel()
        } catch (_: Exception) {
            // Best effort.
        }
        catalogJob = null
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
        // P2-1: binder/permission callbacks are auto lifecycle, not user
        // taps -- privileged `cat` stays off (false) even on the reread path.
        // Only the dedicated 手動診斷 Hook button consents to privileged reads.
        if (reread) vm.reconnectAndRefresh(allowPrivilegedHookDiagnosis = false) else vm.recheckPrivilege(allowPrivilegedHookDiagnosis = false)
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
            // P25 單一入口：probe（含 Auto 降級）+ refresh；PR-37 Phase C #5：
            // catalog 失敗文案指向此按鈕，故同步重試 reloadCatalog（其內部先
            // cancel 舊 catalogJob，語義為「取消在途、重打一次」，不會疊加）。
            // P2-1: reconnect tap is NOT consent for privileged diagnosis --
            // always false (LSPOSED stays direct-only); only the dedicated
            // 手動診斷 Hook button passes true.
            text = "重新連線並更新"
            setOnClickListener {
                vm.reconnectAndRefresh(allowPrivilegedHookDiagnosis = false)
                reloadCatalog()
            }
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
            setOnClickListener { vm.selectChannel(ChannelPreference.AUTO, allowPrivilegedHookDiagnosis = false) }
        }
        rootBtn = Button(this).apply {
            text = "Root"
            setOnClickListener { vm.selectChannel(ChannelPreference.ROOT, allowPrivilegedHookDiagnosis = false) }
        }
        shizukuBtn = Button(this).apply {
            text = "Shizuku"
            setOnClickListener { vm.selectChannel(ChannelPreference.SHIZUKU, allowPrivilegedHookDiagnosis = false) }
        }
        lsposedBtn = Button(this).apply {
            text = "LSPosed"
            setOnClickListener { vm.selectChannel(ChannelPreference.LSPOSED, allowPrivilegedHookDiagnosis = false) }
        }
        // P2-1: narrow-consent privileged hook re-diagnosis. Only this tap
        // passes allowPrivilegedHookDiagnosis=true (explicit consent for a
        // retained privileged `cat`); picker taps, reconnect taps, and auto
        // lifecycle never `cat`. P3: the copy states the privileged exec
        // directly (button contentDescription + ViewModel docs).
        hookDiagBtn = Button(this).apply {
            text = "手動診斷 Hook"
            // P3: 明示此按鈕會執行特權診斷指令（TalkBack 朗讀；點按即明確同意）。
            contentDescription = "手動診斷 Hook（需要執行 ROOT/Shizuku 診斷指令，僅在此按鈕觸發）"
            setOnClickListener { vm.requestHookRedDiagnosis() }
        }
        // Phase C P1 P3: visible copy of the same consent (sighted users never
        // hear contentDescription). Shown only in LSPOSED mode with the button.
        hookDiagHint = TextView(this).apply {
            text = "說明：點按將執行 ROOT／Shizuku 特權診斷指令（單次，僅此按鈕觸發）"
            textSize = 12f
        }
        // 確認/取消結果靠 liveRegion 播報（TalkBack polite）。
        msgView = TextView(this).apply {
            textSize = 15f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        defaultMsgColor = msgView.currentTextColor
        // PR-6 P2 (#27 後半)：清單新鮮度行 + 診斷事件行 + 匯出按鈕。
        // diagView 點一下展開/收合技術細節；匯出只顯示已遮蔽的本機預覽，
        // 無自動上傳 (buildDiagnosticExport 為純字串，無網路呼叫)。
        // P4 a11y：可聚焦 + 狀態 contentDescription，切換時 announce。
        inventoryView = TextView(this)
        lastOpView = TextView(this)
        diagView = TextView(this).apply {
            isFocusable = true
            contentDescription = "診斷：載入中"
            setOnClickListener {
                diagExpanded = !diagExpanded
                lastState?.let { render(it) }
                try {
                    announceForAccessibility(contentDescription)
                } catch (_: Exception) {
                    // Best effort: 無障礙宣告失敗不影響顯示。
                }
            }
        }
        exportBtn = Button(this).apply {
            // P2-4 誠實文案：遮蔽仍非全面保證，不得只寫「已遮蔽」，
            // 須明示「已遮蔽可辨識資料，分享前請人工確認」。
            text = "匯出診斷（已遮蔽可辨識資料，分享前請人工確認）"
            setOnClickListener { showDiagnosticExport() }
        }
        // PR-7 P2 (#26, 階段 1):個人來源 catalog 搜尋/勾選區。
        // 唯讀展示 + 勾選；batchBtn 顯示「建立 N 個分身」,走既有單筆
        // cloneVerified 逐項 (見 DualViewModel.cloneSelected)。取消勾選 ≠ 卸載。
        searchInput = EditText(this).apply {
            hint = "搜尋應用名稱 / 包名"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // 旋轉回填：ViewModel retained 保留 sourceQuery，Activity 重建時先回填
            // 舊 query 再掛 TextWatcher，避免 setText 觸發 afterTextChanged 迴圈。
            setText(vm.state.value.sourceQuery)
            // 游標置尾，方便繼續編輯。
            try {
                setSelection(text?.length ?: 0)
            } catch (_: Exception) {
                // Best effort.
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    vm.setSourceQuery(s?.toString().orEmpty())
                }
            })
        }
        systemToggleBtn = Button(this).apply {
            text = "系統應用：已隱藏"
            // TalkBack：只切換顯示範圍，不卸載任何分身。
            contentDescription = "切換是否顯示系統應用（只切換顯示，不卸載任何分身）"
            setOnClickListener { vm.setIncludeSystemApps(!vm.state.value.includeSystemApps) }
        }
        selectAllBtn = Button(this).apply {
            text = "全選可見"
            // TalkBack：只勾選目前可見前 N 筆（與渲染同一截斷，見 currentVisibleKeys），
            // 不寫入分身；超出請用搜尋縮小範圍。
            contentDescription = "全選目前可見前 $MAX_SOURCE_ROWS 筆（只勾選，不寫入分身；超出請用搜尋縮小範圍）"
            setOnClickListener { vm.selectVisible(currentVisibleKeys()) }
        }
        clearSelBtn = Button(this).apply {
            text = "清除勾選"
            // TalkBack:只清勾選,永不卸載；描述寫明契約。
            contentDescription = "清除勾選（不卸載任何分身）"
            setOnClickListener { vm.clearSelectionLocal() }
        }
        saveSelBtn = Button(this).apply {
            text = "保存選取"
            // 只保存勾選,不寫入分身；重啟可恢復。
            contentDescription = "保存目前勾選（只保存勾選，不寫入分身）"
            setOnClickListener { vm.persistSelection(vm.state.value.inventory.targetId) }
        }
        cancelSaveBtn = Button(this).apply {
            text = "取消保存"
            // 只刪持久化條目,不卸載、不清目前勾選。
            contentDescription = "取消保存選取（不卸載分身，不清除目前勾選）"
            setOnClickListener { vm.cancelSavedSelection() }
        }
        sourceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        catalogErrorView = TextView(this).apply {
            visibility = View.GONE
        }
        batchBtn = Button(this).apply {
            text = "建立 0 個分身"
            // TalkBack：批次對已勾選逐項執行建立；勾選本身≠卸載，取消勾選≠卸載。
            contentDescription = "批次建立 0 個分身（對已勾選逐項執行；勾選本身不等於卸載）"
            setOnClickListener { vm.cloneSelected() }
        }
        batchResultView = TextView(this)

        // Clone/remove share one row; 重新連線並更新 + permission share the next.
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
        hookRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hookDiagBtn, rowParams())
        }
        // Catalog rows: search full width, then toggle/select/clear,
        // then save/cancel-save, then the list, batch button and results.
        val catalogRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(systemToggleBtn, rowParams())
            addView(selectAllBtn, rowParams())
            addView(clearSelBtn, rowParams())
        }
        val catalogRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(saveSelBtn, rowParams())
            addView(cancelSaveBtn, rowParams())
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
        root.addView(inventoryView)
        root.addView(lastOpView)
        root.addView(searchInput)
        root.addView(catalogRow1)
        root.addView(catalogRow2)
        root.addView(sourceContainer)
        root.addView(catalogErrorView)
        root.addView(batchBtn)
        root.addView(batchResultView)
        root.addView(pkgInput)
        root.addView(writeRow)
        root.addView(readRow)
        root.addView(hookRow)
        root.addView(hookDiagHint)
        root.addView(msgView)
        root.addView(diagView)
        root.addView(exportBtn)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // PR-6 P2 (#27 後半)：診斷匯出預覽。顯示已遮蔽可辨識資料的本機文字
    // (buildDiagnosticExport 經四道遮蔽：先郵件後包名後 IP，最後路徑，大小受限)；只有使用者
    // 主動點按才顯示，無自動上傳、無網路呼叫、無後台傳送。
    // P2-4 誠實文案：遮蔽仍非全面保證，標題與按鈕須明示「分享前請人工確認」，
    // 不得只寫「已遮蔽」使人誤信絕對乾淨。
    // P2-7：對話框另附「複製已遮蔽內容」按鈕（見 DIAGNOSTIC_COPY_BUTTON_TEXT）：
    // 使用者主動觸發才經 ClipboardManager 複製已遮蔽文本到本機剪貼簿；複製內容
    // 與預覽同字串 (diagnosticExportCopyText)，不做自動傳輸/share。
    private fun showDiagnosticExport() {
        val state = lastState ?: vm.state.value
        val preview = buildDiagnosticExport(state)
        try {
            exportDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: 舊預覽關不掉也不阻塞新預覽。
        }
        exportDialog = AlertDialog.Builder(this)
            .setTitle("診斷匯出預覽（已遮蔽可辨識資料，分享前請人工確認，無自動上傳）")
            .setMessage(preview)
            .setCancelable(true)
            .setNeutralButton(DIAGNOSTIC_COPY_BUTTON_TEXT) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText(
                            "galaxy-dual-diagnostic",
                            diagnosticExportCopyText(state),
                        ),
                    )
                    msgView.text = DIAGNOSTIC_COPY_SUCCESS_TEXT
                    msgView.setTextColor(colorFor(MsgKind.INFO))
                } catch (e: Exception) {
                    msgView.text = "複製失敗：${sanitizeForDisplay(e.message ?: "unknown")}"
                    msgView.setTextColor(colorFor(MsgKind.CHANNEL))
                }
            }
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    // Permission button: no binder -> single reconnect entry (probe keeps the
    // honest NONE state); authorized -> single reconnect entry (probe+refresh);
    // otherwise ask Shizuku. The button text mirrors the same boolean split in
    // updateShizukuLine so 文案/動作一致（重連分支為「檢查 Shizuku 並更新」，
    // 與重新連線並更新按鈕「重新連線並更新」區分；未授權分支為「請求 Shizuku 授權」）.
    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                // P2-1: permission-button tap re-probes/refreshes but is NOT
                // consent for privileged `cat` (always false).
                vm.reconnectAndRefresh(allowPrivilegedHookDiagnosis = false)
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                vm.reconnectAndRefresh(allowPrivilegedHookDiagnosis = false)
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        } catch (e: Exception) {
            vm.recheckPrivilege(allowPrivilegedHookDiagnosis = false)
        }
        updateShizukuLine()
    }

    // Shizuku binder/authorization line, read live (never cached): pingBinder
    // guards checkSelfPermission so a dead binder shows 未連線, not a crash.
    // P25: the permission button text follows the same boolean binder/
    // permission state (never parsed from the rendered line): 未連線/已授權 ->
    // "檢查 Shizuku 並更新" (reconnectAndRefresh, 與重新連線並更新按鈕的
    // 「重新連線並更新」區分 TalkBack/視覺）; 未授權 -> "請求 Shizuku 授權"
    // (requestPermission).
    private fun updateShizukuLine() {
        // Boolean binder/permission state first (single live read): the
        // button branch below keys on these, never on state.contains().
        val noBinder: Boolean = try { !Shizuku.pingBinder() } catch (_: Exception) { true }
        val authorized: Boolean = try {
            !noBinder && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        val state: String = try {
            if (noBinder) {
                "Shizuku：binder 未連線（需安裝並啟動 Shizuku）"
            } else if (authorized) {
                "Shizuku：已連線・已授權"
            } else {
                "Shizuku：已連線・未授權（請點下方授權）"
            }
        } catch (e: Exception) {
            "Shizuku：狀態未知（${sanitizeForDisplay(e.message ?: "unknown")}）"
        }
        shizukuView.text = state
        try {
            permBtn.text = if (!noBinder && !authorized) "請求 Shizuku 授權" else "檢查 Shizuku 並更新"
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
    // PR-6 P2 (#27 後半)：另渲染清單新鮮度行 (STALE/UNKNOWN 標示)、
    // Hook scope/time 證據、診斷事件行 (穩定碼/摘要/下一步，可點開)，
    // 以及匯出按鈕 (已遮蔽預覽，無自動上傳)。
    private fun render(s: UiState) {
        // P25: keep the Shizuku binder line in sync on every state render
        // (cheap live read inside updateShizukuLine, already try/catch
        // guarded for the pre-buildUi test path). Double-guarded here so a
        // Shizuku-less test harness calling render can never crash on it.
        // PR-6 P2: 同時記住最近一次渲染狀態 (診斷展開/匯出共用)。
        lastState = s
        try {
            updateShizukuLine()
        } catch (_: Exception) {
            // Best effort: the state render must never depend on Shizuku.
        }
        val readyMark = s.readyMark()
        // Phase C P2: 本次探測三態 + 範圍限定 -- privilege/anyProbePassed 只反映
        // 本次探測子集 (probeScope)，非裝置總能力。NONE（LSPOSED 零探測）顯示
        // 未探測，絕不顯示未通過；明選 SHIZUKU 成功不代表 ROOT 不存在。
        // 真可讀 = (pmReadable == true) + writeReady：probe 身分
        // (execIdentity/anyProbePassed) 與 PM 讀 (pmReadable) / 寫 (writeReady)
        // 絕不混為一布林 (P25 Item 4)。Phase 2 P3: readyMark 為雙條件
        // (writeReady + pmReadable)，探測通過但 PM 未確認/不可讀不顯示已就緒。
        val probeMark = s.probeResultMark()
        val scopeLabel = s.probeScope.displayLabel()
        val pmMark = s.pmReadableText()
        privilegeView.text = "權限：${s.execIdentity}（實際通道 ${s.resolvedGateway}" +
            "・$readyMark；本次路由 ${s.privilege}・本次探測[$scopeLabel]$probeMark・$pmMark）"
        // Title mirrors the actual execution identity (not the routed
        // summary) for quick manual verification.
        title = "GalaxyDual Plus (${s.execIdentity}/${s.resolvedGateway})"
        // Picker state: persisted preference + actual resolved gateway
        // (restart remembers the preference via SharedPreferences; see
        // factory). Always shown so the user can tell which channel the
        // lists/privilege above were produced with. The Auto degrade note
        // is intentionally persistent here (it survives msg clears), but
        // only as a short tag: the full note already rides in msg (INFO),
        // so appending it in full would wash the status line every render.
        channelView.text = buildString {
            append("通道偏好：${s.channel}・實際：${s.resolvedGateway}（選擇已持久化，重啟 App 記住）")
            if (s.degradeNote.isNotEmpty()) append("；已自 Auto 降級，詳見狀態列")
        }
        // Hook signal: LSPosed mode only. ACTIVE shows the recorded scopes as
        // a historical marker (existence evidence only, boot freshness
        // unconfirmed); INACTIVE shows 未偵測到 plus the enable hint; UNKNOWN shows
        // 狀態未知 (P1: unreadable / no diagnostic path is never reported
        // as "not detected") plus the true-channel hint. The placeholder
        // note stays visible so the mode is never mistaken for a working
        // channel. P4: 時間證據經 hookEvidence()+timeEvidenceText 統一
        // (狀態文案維持本頁提示，不動)。
        val hookText = renderHookText(s)
        if (hookText != null) {
            hookView.visibility = View.VISIBLE
            hookView.text = hookText
        } else {
            hookView.visibility = View.GONE
            try {
                hookRow.visibility = View.GONE
                hookDiagHint.visibility = View.GONE
            } catch (_: Exception) {
                // Best effort: render may run before buildUi in tests.
            }
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
        // PR-6 P2 (#27 後半)：清單新鮮度行 (STALE/UNKNOWN 明確標示，
        // FRESH 顯示目標/筆數/查詢時間)。P4：上次操作相位拆至獨立 lastOpView
        // 行 (renderLastOpText)，此處只剩清單；查詢時間經 formatTimeMs。
        inventoryView.text = renderInventoryText(s)
        lastOpView.text = renderLastOpText(s)
        // PR-6 P2 (#27 後半)：診斷事件行。穩定碼 + 繁中摘要/下一步常顯；
        // 點一下展開 exit/技術 detail (大小受限)。無事件時顯示就緒。
        // P4 a11y：同步更新 contentDescription (含展開狀態)。
        diagView.text = renderDiagnoseText(s, diagExpanded)
        diagView.contentDescription = renderDiagnoseDescription(s, diagExpanded)
        // Writes queue in the ViewModel anyway; disabling while busy keeps
        // the read-only placeholder visibly read-only. Channel switches go
        // through the same mutex (selectChannel latest-wins), so the picker
        // is gated too.
        // PR-37 P3 按鈕狀態：busy 時勾選相關一律禁用（批次執行中不可改勾選）；
        // batch 需非 busy 且有勾選；保存需已知目標 (targetId > 0)。
        val busy = s.loading
        cloneBtn.isEnabled = !busy
        removeBtn.isEnabled = !busy
        refreshBtn.isEnabled = !busy
        autoBtn.isEnabled = !busy
        rootBtn.isEnabled = !busy
        shizukuBtn.isEnabled = !busy
        lsposedBtn.isEnabled = !busy
        // N1 Phase 1: manual diagnosis shares the same mutex gate.
        try {
            hookDiagBtn.isEnabled = !busy
        } catch (_: Exception) {
            // Best effort: render may run before buildUi in tests.
        }
        systemToggleBtn.isEnabled = !busy
        selectAllBtn.isEnabled = !busy
        clearSelBtn.isEnabled = !busy
        saveSelBtn.isEnabled = !busy && s.inventory.targetId > 0
        cancelSaveBtn.isEnabled = !busy
        batchBtn.isEnabled = !busy && s.selectedKeys.isNotEmpty()
        // PR-7 P2 (#26):catalog 區渲染 (搜尋/勾選/批次按鈕計數/逐項結果)。
        // PR-37 P3 差量：列表只在指紋變化時全量重建；其餘文字區各自更新。
        renderSourceList(s)
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
        // 破壞性確認顯示原文以供核對，匯出才遮蔽（by-design 例外）：
        // 本地確認畫面必須顯示完整 package + label + user/serial，
        // 不經 sanitizeForDisplay/redact，否則使用者無法核對卸載目標；
        // 診斷匯出/日誌仍經四道遮蔽（見 buildDiagnosticExport）。
        val message = buildRemoveConfirmMessage(
            pkg = pending.pkg,
            appLabel = appLabel,
            profileLabel = pending.profileLabel,
            dualId = pending.dualId,
            serial = pending.dualSerial,
        )
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

    // PR-7 P2 (#26):來源 catalog 載入 (授權範圍唯讀)。
    // 包到 MainActivity 的生命週期:IO 執行緒讀 PackageManager,回主執行緒注入
    // ViewModel。serial 取自身 user (UserManager,失敗回退 0);AppKey =
    // serial:package (見 SourceInventory)。
    // PR-37 P3:失敗不清空（保留舊列表，不呼叫 setSourceCatalog），錯誤走獨立
    // 文案 catalogLoadError（與「尚未載入」區分）；onCreate+onResume 雙觸發以
    // catalogJob 取消前一個在途任務，避免疊加。
    private fun reloadCatalog() {
        catalogJob?.cancel()
        catalogJob = lifecycleScope.launch {
            val apps: List<SourceAppEntry>? = withContext(Dispatchers.IO) {
                try {
                    loadSourceApps(packageManager, selfSerial())
                } catch (_: Exception) {
                    null
                }
            }
            if (apps == null) {
                catalogLoadError = "來源清單載入失敗，已保留舊列表（請點「重新連線並更新」重試）"
                updateCatalogErrorView()
                return@launch
            }
            catalogLoadError = null
            updateCatalogErrorView()
            vm.setSourceCatalog(apps)
        }
    }

    private fun updateCatalogErrorView() {
        try {
            if (!::catalogErrorView.isInitialized) return
            val err = catalogLoadError
            if (err.isNullOrEmpty()) {
                catalogErrorView.visibility = View.GONE
                catalogErrorView.text = ""
            } else {
                catalogErrorView.visibility = View.VISIBLE
                catalogErrorView.text = err
            }
        } catch (_: Exception) {
            // Best effort: view may not be ready in tests.
        }
    }

    private fun selfSerial(): Int = try {
        val um = getSystemService(UserManager::class.java)
        um?.getSerialNumberForUser(Process.myUserHandle())?.toInt() ?: 0
    } catch (_: Exception) {
        0
    }

    // 目前可見列的 AppKey (全選可見用);與渲染同一過濾/排序/截斷,保證一致。
    // PR-37 P3:同樣 .take(MAX_SOURCE_ROWS)，「全選可見」只選可見前 N 筆；
    // 超出請用搜尋縮小範圍（截斷提示見 renderSourceList）。
    private fun currentVisibleKeys(): Set<String> {
        val s = vm.state.value
        return sortSourceApps(filterSourceApps(s.sourceCatalog, s.sourceQuery, s.includeSystemApps))
            .take(MAX_SOURCE_ROWS)
            .map { it.appKey }.toSet()
    }

    // PR-7 P2 (#26):catalog 區渲染。清單為唯讀展示 + CheckBox 勾選；
    // 取消勾選 ≠ 卸載 (listener 只調 toggleSelection,零裝置呼叫)。
    // 圖示非同步載入並以 appKey 快取 (顯示用,不參與身分鍵)。
    // PR-37 P3 差量更新：列表全量重建只在指紋變化時執行
    // (catalog 引用+內容+query+includeSystem+selectedKeys)；
    // loading/msg/diagnose/batchReport 變化只更新對應文字區，不重建列表。
    // 按鈕文字/勾選啟用狀態每輪都更新（busy 時勾選區不可改，見 render）。
    private fun renderSourceList(s: UiState) {
        val visible = sortSourceApps(filterSourceApps(s.sourceCatalog, s.sourceQuery, s.includeSystemApps))
        val busy = s.loading
        // PR-37 Phase C #6 a11y：文字與 contentDescription 每輪隨 state 同步
        //（按鈕類型不變，最小改動；開關語義靠文案「顯示中/已隱藏＋點兩下切換」傳達）。
        systemToggleBtn.text = if (s.includeSystemApps) "系統應用：顯示中" else "系統應用：已隱藏"
        systemToggleBtn.contentDescription = if (s.includeSystemApps) {
            "系統應用目前顯示中，點兩下隱藏（只切換顯示，不卸載任何分身）"
        } else {
            "系統應用目前已隱藏，點兩下顯示（只切換顯示，不卸載任何分身）"
        }
        batchBtn.text = "建立 ${s.selectedKeys.size} 個分身"
        batchBtn.contentDescription =
            "批次建立 ${s.selectedKeys.size} 個分身（對已勾選逐項執行；勾選本身不等於卸載）"
        updateCatalogErrorView()
        batchResultView.text = batchReportText(s.batchReport)
        val key = buildSourceListKey(s)
        if (key == lastSourceListKey) {
            // 指紋未變：不重建列表，只同步勾選區啟用狀態（busy 切換時）。
            for (i in 0 until sourceContainer.childCount) {
                val child = sourceContainer.getChildAt(i)
                if (child is CheckBox) child.isEnabled = !busy
            }
            return
        }
        lastSourceListKey = key
        sourceContainer.removeAllViews()
        if (s.sourceCatalog.isEmpty()) {
            sourceContainer.addView(TextView(this).apply { text = "應用清單：尚未載入" })
        } else if (visible.isEmpty()) {
            sourceContainer.addView(TextView(this).apply { text = "應用清單：無符合項目" })
        } else {
            for (entry in visible.take(MAX_SOURCE_ROWS)) {
                val box = CheckBox(this).apply {
                    // 可辨識列:名稱 + 包名；TalkBack 讀同一文字。
                    text = "${entry.label}\n${entry.packageName}"
                    tag = entry.appKey
                    isChecked = entry.appKey in s.selectedKeys
                    // 批次執行中不可改勾選。
                    isEnabled = !busy
                }
                requestIcon(entry.packageName, entry.appKey, box)
                box.setOnCheckedChangeListener { _, checked ->
                    vm.setSelected(entry.appKey, checked)
                }
                sourceContainer.addView(box)
            }
            if (visible.size > MAX_SOURCE_ROWS) {
                sourceContainer.addView(
                    TextView(this).apply {
                        // 全選可見同樣只取前 N 筆（見 currentVisibleKeys），此處列明契約。
                        text = "…等共 ${visible.size} 筆，僅顯示前 $MAX_SOURCE_ROWS 筆，" +
                            "全選可見只選前 $MAX_SOURCE_ROWS 筆（請用搜尋縮小範圍）"
                    },
                )
            }
        }
    }

    // 來源列表指紋：catalog 引用+內容 hash、query、系統開關、勾選集合 hash。
    private fun buildSourceListKey(s: UiState): String =
        "${System.identityHashCode(s.sourceCatalog)}:${s.sourceCatalog.hashCode()}:" +
            "${s.sourceQuery}:${s.includeSystemApps}:${s.selectedKeys.hashCode()}"

    // 圖示非同步載入:IO 讀取,主執行緒貼到仍綁定同一 appKey 的列。
    // memo 記「已成功」避免重打 PackageManager；drawables 有界快取。
    // PR-37 P3:共用 Drawable 取用時經 constantState 新實例 + mutate()，
    // 避免多個舊 View 共用同一 Drawable 回呼；memo 只記成功、inflight 擋併發（見下）。
    // PR-37 Phase C #4 競態修正：in-flight 與 loaded 分離。發起時先記
    // iconInflight（同一 key 在途不重打），IO 完成（成功）後才 markLoaded；
    // 失敗不清 loaded（本來就沒記）、只清 inflight，允許下次列表重建時重試。
    // 重試節流：重試只發生在 renderSourceList 指紋變化觸發全量重建時（指紋未變
    // 直接 return，不會每幀重打），加上 inflight 擋併發，故無重試風暴；完成後
    // 只綁定仍掛在 sourceContainer 下同一 appKey 的可見列（catalog 已換 key
    // 不在時不貼，避免貼到錯行）。
    // PR-37 Phase C #5 淘汰同步：drawables 滿時淘汰最舊項必須同步 evict memo
    // 對應 key（見 evictIcon／pickIconEvictionKey）。之後回到該 App：快取 miss
    // ＋未 loaded → 重新請求圖示。淘汰永不碰 iconInflight（在途 key 本就不在
    // 快取內，被害者不可能是自己）；完成寫入時即使快取已滿也正常寫入＋淘汰最舊。
    private fun requestIcon(pkg: String, appKey: String, box: CheckBox) {
        val cached = iconDrawables[appKey]
        if (cached != null) {
            val fresh = try {
                cached.constantState?.newDrawable()?.mutate() ?: cached.mutate()
            } catch (_: Exception) {
                cached
            }
            box.setCompoundDrawablesWithIntrinsicBounds(fresh, null, null, null)
            return
        }
        if (iconMemo.isLoaded(appKey)) return
        if (appKey in iconInflight) return
        iconInflight.add(appKey)
        lifecycleScope.launch {
            val drawable = withContext(Dispatchers.IO) {
                try {
                    packageManager.getApplicationIcon(pkg)
                } catch (_: Exception) {
                    null
                }
            }
            iconInflight.remove(appKey)
            if (drawable == null) return@launch
            val victim = pickIconEvictionKey(iconDrawables.keys.toList(), MAX_ICON_CACHE)
            if (victim != null) evictIcon(victim)
            iconMemo.markLoaded(appKey)
            iconDrawables[appKey] = drawable
            val fresh = try {
                drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
            } catch (_: Exception) {
                drawable
            }
            // 首選仍掛在容器下且綁定同一 appKey 的發起列；若該列已被重建取代
            // （parent 已非容器），則在當前容器內按 tag 找同一 key 的可見列重繪
            //（找不到＝已過濾掉／換 catalog，不貼）。
            try {
                if (box.tag == appKey && box.parent === sourceContainer) {
                    try {
                        box.setCompoundDrawablesWithIntrinsicBounds(fresh, null, null, null)
                        return@launch
                    } catch (_: Exception) {
                        // Best effort: 舊列 detach 後貼圖失敗則走容器查找。
                    }
                }
                for (i in 0 until sourceContainer.childCount) {
                    val child = sourceContainer.getChildAt(i)
                    if (child is CheckBox && child.tag == appKey) {
                        val perView = try {
                            drawable.constantState?.newDrawable()?.mutate() ?: fresh
                        } catch (_: Exception) {
                            fresh
                        }
                        child.setCompoundDrawablesWithIntrinsicBounds(perView, null, null, null)
                        break
                    }
                }
            } catch (_: Exception) {
                // Best effort: 容器未就緒（測試路徑）時不阻塞圖示快取。
            }
        }
    }

    // PR-37 Phase C #5 淘汰同步點：同時清除 drawables 與 memo 兩處。
    // JVM 可測性說明：Activity 私有函數無法被既有 JVM 測試覆蓋，故淘汰邏輯
    // 拆成兩步——純決策 pickIconEvictionKey（伴隨對象，只用 stdlib 集合，無
    // Android 依賴）＋本函數做同步清除（底層 SourceIconState.evict 為純狀態
    // 操作，JVM 可直接測）。在途集合 iconInflight 永不經手此處，完成回填不
    // 受淘汰干擾；若 victim 恰為本次 appKey（理論上不會發生，見上），先清後
    // 寫的順序仍保證最終快取＋memo 一致。
    private fun evictIcon(appKey: String) {
        iconDrawables.remove(appKey)
        iconMemo.evict(appKey)
    }

    // 批次逐項結果文字:成功/失敗/未知分開列,部分成功如實呈現。
    private fun batchReportText(report: BatchReport?): String {
        if (report == null || report.items.isEmpty()) return ""
        return buildString {
            appendLine(
                "批次結果：成功 ${report.successCount} / 失敗 ${report.failedCount} " +
                    "/ 未知 ${report.unknownCount}（共 ${report.items.size} 項）",
            )
            for (item in report.items) {
                val mark = when (item.outcome) {
                    is CloneOutcome.Success -> "成功"
                    is CloneOutcome.VerifyUnknown -> "未知（需重查）"
                    else -> "失敗"
                }
                appendLine("  ${item.pkg}：$mark")
            }
        }.trimEnd()
    }

    companion object {
        // Shizuku permission request code; matched in both the Shizuku result
        // listener and the defensive onRequestPermissionsResult override.
        private const val SHIZUKU_REQUEST_CODE = 1001

        // 旋轉保留鍵：診斷技術細節展開開關。
        private const val KEY_DIAG_EXPANDED = "diagExpanded"
        // PR-7 P2 (#26):單次渲染列數上限 (效能護欄,超出請用搜尋縮小範圍)。
        private const val MAX_SOURCE_ROWS = 200

        // PR-7 P2 (#26):圖示 Drawable 有界快取上限 (顯示用,不參與身分鍵)。
        private const val MAX_ICON_CACHE = 200

        // PR-37 Phase C #5 圖示淘汰純決策 (JVM 可測 seam)：快取已滿
        // (orderedKeys.size >= maxSize) 時回傳最舊 key（插入順序第一個），
        // 未滿回傳 null。只用 stdlib 集合、無 Android 依賴；呼叫方拿到 victim
        // 後經 evictIcon 同步清除 drawables＋memo 兩處。
        internal fun pickIconEvictionKey(orderedKeys: List<String>, maxSize: Int): String? =
            if (orderedKeys.size >= maxSize) orderedKeys.firstOrNull() else null

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

// PR-6 P4 (#27 架構整理)：render() 的純字串拆分 (頂層 internal，JVM 可測)。
// 有意變更 (非等價重構)，共四處，各有理由：
// - 0/無效 epoch 改經 formatTimeMs 顯示「從未查詢」(理由：空態明確化，原 raw 0/不顯示易誤讀為有效時間)；
// - marker 時間文案由 raw epoch 改為格式化時間 (理由：可讀性/可追溯性，與 HookEvidence.displayText 統一)；
// - 上次操作相位由清單行尾移至 renderLastOpText 獨立行 (理由：行職責單一/可讀性，無操作顯示「無」與匯出同詞)；
// - hook 時間證據經 hookEvidence()+timeEvidenceText 統一 (狀態文案維持本頁提示，不動；理由：單一來源免分叉)。

// 清單新鮮度行：STALE/UNKNOWN 明確標示，FRESH 顯示目標/筆數/查詢時間。
internal fun renderInventoryText(s: UiState): String = buildString {
    append("清單：${s.inventory.freshness}")
    if (s.inventory.targetId > NO_TARGET_ID) {
        append("（user ${s.inventory.targetId}・${s.inventory.count} 筆）")
    }
    append("・查詢於 ${formatTimeMs(s.inventory.queryTimeMs)}")
    if (s.inventory.freshness != InventoryFreshness.FRESH) {
        append("（資料可能過期，已自動重查或請點「重新連線並更新」）")
    }
}

// 上次操作獨立行 (原附於清單行尾)；無操作顯示「無」(與匯出同詞)。
// 理由 (security-low)：不再顯示 raw opId (含完整包名與世代序號)，改顯示
// redactPackage(appKey)+phase (+targetId 若有效)，避免個資回音；儲存的
// opId/appKey 保持 raw (VM 狀態/測試仍直讀)。
internal fun renderLastOpText(s: UiState): String {
    val last = s.lastOperation
    return if (last != null) {
        val targetPart =
            if (last.pinnedTarget > NO_TARGET_ID) " target=${last.pinnedTarget}" else ""
        "上次操作：${redactPackage(last.appKey)}$targetPart 相位 ${last.phase}"
    } else {
        "上次操作：無"
    }
}

// Hook 行：非 LSPosed 模式回 null (呼叫端 Gone)；狀態文案維持本頁提示
// (ACTIVE 歷史證據措辭/INACTIVE 啟用提示/UNKNOWN 真通道提示，不動)，
// 時間證據經 hookEvidence()+timeEvidenceText 統一。
internal fun renderHookText(s: UiState): String? {
    if (s.channel != ChannelPreference.LSPOSED) return null
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
    return "LSPosed Hook：$status${s.hookEvidence().timeEvidenceText()}；寫入通道為佔位（NoOp），" +
        "待 whitelist 政策分支，寫入已禁用"
}

// 診斷事件行：穩定碼 + 繁中摘要/下一步常顯；展開時加 exit/技術 detail。
internal fun renderDiagnoseText(s: UiState, expanded: Boolean): String = buildString {
    val diag = s.diagnose
    if (diag == null) {
        append("診斷：就緒")
    } else {
        append("診斷［${diag.code.stableId}］${diag.summaryZh}；下一步：${diag.nextStepZh}")
        if (expanded) {
            if (diag.exit != null) append("\n  exit=${diag.exit}")
            // 本機顯示經 sanitizeForDisplay (control 壓空白+四道遮蔽+take500，
            // 與其他顯示一致；匯出為四道遮蔽全量，見 buildDiagnosticExport)。
            if (diag.techDetail.isNotEmpty()) {
                append("\n  細節（本機顯示遮蔽版，與匯出一致）：")
                append("${sanitizeForDisplay(diag.techDetail)}（點此收合）")
            } else append("（點此收合）")
        } else {
            append("（點此展開技術細節）")
        }
    }
}

// P2-7 匯出複製按鈕文案（頂層 const，JVM 可測；測試鎖定繁中字面）。
// P2-4 誠實文案：遮蔽仍非全面保證，須附「分享前請人工確認」，不得只寫已遮蔽。
// 使用者主動觸發才複製，不做自動傳輸/share。
const val DIAGNOSTIC_COPY_BUTTON_TEXT = "複製已遮蔽內容（分享前請人工確認）"

// StageD D4 剪貼簿複製成功訊息（頂層 const，JVM 可測）：維持使用者主動觸發+
// 警示，補「用後請清除剪貼簿」並保留「僅本機剪貼簿」（不做自動傳輸/share）。
const val DIAGNOSTIC_COPY_SUCCESS_TEXT =
    "已複製已遮蔽的診斷內容（僅本機剪貼簿，無上傳，分享前請人工確認，用後請清除剪貼簿）"

// 診斷行無障礙描述 (含展開狀態，供 contentDescription + 切換宣告)。
internal fun renderDiagnoseDescription(s: UiState, expanded: Boolean): String {
    val diag = s.diagnose
    if (diag == null) return "診斷：就緒"
    val state = if (expanded) "已展開，點兩下收合" else "點兩下展開技術細節"
    return "診斷［${diag.code.stableId}］${diag.summaryZh}；下一步：${diag.nextStepZh}（$state）"
}

// 移除確認文案純函數（MainActivity 頂層 internal，JVM 可測）。
// 破壞性確認顯示原文以供核對，匯出才遮蔽（by-design 例外）：
// 本地確認畫面顯示完整 package + label + user/serial，不經
// sanitizeForDisplay/redact，否則使用者無法核對卸載目標；
// m3 行注入防護：pkg/appLabel/profileLabel 先過 sanitizeUserEcho
// (保原文、去換行，不 redact)，結構換行保留，欄位內換行壓空白；
// 診斷匯出/日誌仍經四道遮蔽（見 buildDiagnosticExport）。
// - pkg：待移除完整包名（raw，呼叫端傳 pending.pkg）。
// - appLabel：PackageManager 解析顯示名；失敗時呼叫端回退傳 pkg
//  （見 resolveAppLabel），兩種皆須含完整 pkg + user/serial。
// - profileLabel：已驗證目標顯示名（raw，pending.profileLabel）。
// - dualId/serial：已驗證目標 user/serial（數字身份可讀性）。
internal fun buildRemoveConfirmMessage(
    pkg: String,
    appLabel: String,
    profileLabel: String,
    dualId: Int,
    serial: Int,
): String =
    "應用：${sanitizeUserEcho(appLabel)}（${sanitizeUserEcho(pkg)}）\n" +
        "已驗證目標：user $dualId（${sanitizeUserEcho(profileLabel)}，serial $serial）\n" +
        "警告：將刪除該分身的使用者資料且無法復原；" +
        "僅影響分身（user $dualId），不影響主 profile。"

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
            // PR-7 P2 (#26):選取持久化綁定 app-private SharedPreferences
            // (SELECTION_PREFS_NAME,MODE_PRIVATE);與通道偏好不同檔,互不干擾。
            val selPrefs = appContext.applicationContext.getSharedPreferences(
                SELECTION_PREFS_NAME,
                Context.MODE_PRIVATE,
            )
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
                // C1 雙時鐘：wall 供清單/hook/操作時間證據 (顯示用)，
                // mono 供同意時效 (TTL，免 wall 回撥延長有效期)；
                // pending 不跨重啟 (process death 清除)，reboot 重置無影響。
                // JVM 預設皆 currentTimeMillis，測試注入可控 fake。
                nowWallMs = { System.currentTimeMillis() },
                nowMonoMs = { SystemClock.elapsedRealtime() },
                selectionStore = selPrefs.selectionStore(),
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
