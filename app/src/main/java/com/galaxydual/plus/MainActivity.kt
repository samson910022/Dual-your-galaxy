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
import android.graphics.Typeface
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
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
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
import com.galaxydual.core.ShellEscaper
import com.galaxydual.core.SuElevator
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

// Observes DualViewModel.state, drives Shizuku binder/permission lifecycle,
// and renders the dual-app screen. All shell work stays in the ViewModel.
class MainActivity : ComponentActivity() {
    // ViewModel with the F4a real-channel repository. No hardcoded privilege
    // flags here: the ViewModel probes elevators and routes the decision.
    // DEVICE-UNVERIFIED: gateway selection/exec never ran on a device.
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
    // vm.cloneSelected (cloneVerifiedExpected 逐項 pin：非原子交易、逐包驗證，
    // 見 DualViewModel)。
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
    // p28-1：通道四按鈕改為互斥單選語意（RadioGroup/RadioButton），checked 狀態
    // 即選擇狀態（TalkBack 報讀「已選取」），不再只靠 channelView 文字行。
    // 版面主次順序不動（p28-2 範圍），群組仍居 hookView 之後、清單之前。
    private lateinit var channelGroup: RadioGroup
    // p28-1 review（窄螢幕）：群組外包橫滑容器（RadioGroup 仍為其唯一直屬子節點）。
    private lateinit var channelScroll: HorizontalScrollView
    private lateinit var autoBtn: RadioButton
    private lateinit var rootBtn: RadioButton
    private lateinit var shizukuBtn: RadioButton
    private lateinit var lsposedBtn: RadioButton
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
    // Phase C: default message color resolves the oneui_text_primary token
    // lazily (post-attach; replaces the old Color.BLACK literal so production
    // never bypasses the theme token; JVM tests never touch Activity state).
    private val defaultMsgColor: Int by lazy { getColor(R.color.oneui_text_primary) }
    // p28-1 禁用原因行：主操作（建立/移除分身）因通道不可寫被禁用時顯示
    // VM 拒絕理由（writeBlockReason 資源鍵經 Context 解析，與點按拒絕同文）；可寫或忙碌時隱藏。
    private lateinit var writeBlockView: TextView
    // P2-1 去重播報：上次已顯示的禁用原因文字。顯示新鮮度與播報去重分離：
    // 顯示比完整文字（lastWriteBlockText + View 實字），時間戳僅變亦刷新；
    // 播報僅比穩定去重鍵（lastWriteBlockDedupKey/BlockReason 層）控制 liveRegion，
    // 不阻止顯示刷新。msgView 同式（完整比顯示、去時間比播報）。
    // batch/catalog 無時間戳，保留完整文字去重。
    private var lastWriteBlockText: String? = null
    private var lastWriteBlockDedupKey: String? = null
    // B1 msg 守衛：顯示比完整文字（lastMsgText + View 實字），播報比去時間鍵
    // （stripWriteBlockTimeParams）；時間戳僅變刷新顯示、抑制播報。
    private var lastMsgText: String? = null
    // B：batchResult/catalogError 去重快取（比照 msg/writeBlock：shouldUpdate＋
    // View 實字雙比對，避免無關 render 重播 POLITE；旋轉重建以 View 為準）。
    private var lastBatchResultText: String? = null
    private var lastCatalogErrorText: String? = null

    // Two-phase remove dialog (PR-4 P1, issue #23): driven by
    // UiState.pendingRemove, never by a click counter. Non-null pending shows
    // the dialog; null dismisses it. Rotation re-shows from state (ViewModel
    // retained, no re-send). shownPending tracks the instance already on
    // screen so repeat renders do not rebuild the dialog.
    private var removeConfirmDialog: AlertDialog? = null
    private var removeLabelsDialog: AlertDialog? = null
    private var removeAcknowledged = false
    // 勾選綁定世代：同一 pending 世代（generation）內保留勾選，跨世代重置；
    // Neutral（標籤詳情）不解散父對話框、不重置此狀態；旋轉經 Bundle 保留。
    private var acknowledgedGeneration: Long? = null
    private var shownPending: PendingRemove? = null

    // 診斷匯出預覽對話框 (比照 removeConfirmDialog)：旋轉/銷毀時需主動
    // dismiss，否則舊 Activity 的 window 洩漏。
    private var exportDialog: AlertDialog? = null
    private var settingsDialog: AlertDialog? = null

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
    // Phase F F4：圖示 IO 併發上限。來源列表首輪 miss 最多 200 行（MAX_SOURCE_ROWS
    // 封頂）同時觸發 loadIcon；無上限時 200 個 coroutine 同時打 PackageManager
    //（binder 突發）。Semaphore(4) 節流，permit 只在 IO 等待期間持有；超額請求在
    // Activity 作用域排隊，銷毀隨 lifecycleScope 取消。在途去重仍由 iconInflight 承擔。
    private val iconLoadSemaphore = Semaphore(4)

    // PR-6 P2 (#27 後半)：診斷技術細節展開開關 + 最近一次渲染狀態
    // (點診斷行切換展開/收合，需重渲染同一狀態)。
    private var diagExpanded: Boolean = false
    private var lastState: UiState? = null

    // One UI 6 Navigation & Layout Fields
    private var currentTab: Int = TAB_DUAL_APPS
    private lateinit var tabAppsScroll: ScrollView
    private lateinit var tabEngineScroll: ScrollView
    private lateinit var heroCategoryView: TextView
    private lateinit var heroTitleView: TextView
    private lateinit var heroSubtitleView: TextView
    private lateinit var statusPillText: TextView
    private lateinit var statusPillDot: ImageView
    private lateinit var statusPillContainer: LinearLayout
    private lateinit var clonedCountStatView: TextView
    private lateinit var clonedCountBadge: TextView
    private lateinit var clonedAppsContainer: LinearLayout
    private lateinit var navTabApps: LinearLayout
    private lateinit var navTabEngine: LinearLayout
    private lateinit var navTabSettings: LinearLayout
    private lateinit var clearSearchBtn: View
    private lateinit var engineUidSlotView: TextView
    // Phase A 誠實狀態顯示：Hook stat cell（A1）與信號驅動徽章（A3）皆由 render()
    // 綁定 hookStatus / resolvedGateway；Shizuku 備援卡片（A4）由 updateShizukuLine()
    // 經 resolveShizukuCardStatus 同源驅動。
    private lateinit var hookStatusCell: LinearLayout
    private lateinit var hookStatusDot: ImageView
    private lateinit var hookStatusText: TextView
    private lateinit var hookBadgeChip: TextView
    private lateinit var hookSecPmTag: TextView
    private lateinit var hookUidSandboxTag: TextView
    private lateinit var injectedActiveLabel: TextView
    private lateinit var daemonFooter: LinearLayout
    private lateinit var residentBadge: TextView
    private lateinit var telemetryDot: ImageView
    private lateinit var telemetryStatusView: TextView
    // Phase G: telemetryLatencyView removed (dead "—" placeholder, no latency
    // source in repo; render never updated it).
    private lateinit var checklistSummaryView: TextView
    private lateinit var shizukuFallbackDescView: TextView
    private lateinit var shizukuFallbackBadgeView: TextView
    // Phase A A3：診斷清單逐項徽章由 hookLiveProven 驅動（render 內 GONE 門控；
    // Phase H：existence 不足，需 boot-fresh）。
    private val checklistItemBadges = mutableListOf<TextView>()
    // Phase G (honest icons)：診斷清單圖示建時綠/藍固定，未證實時須轉灰。
    // 與徽章同源 hookLiveProven 驅動（render 內 setColorFilter）；isGreen 記
    // 還原色（綠 secondary / 藍 primary），uidRow 另行綁定（見 engineUidIcon）。
    private val checklistItemIcons = mutableListOf<ImageView>()
    private val checklistItemIsGreen = mutableListOf<Boolean>()
    private var engineUidIcon: ImageView? = null

    // PR-37 P3 差量渲染：上次全量重建來源列表的指紋
    // (catalog 引用+內容 hash+query+includeSystem+selectedKeys hash)。
    // loading/msg/diagnose/batchReport 變化只更新對應文字區，不重建列表。
    private var lastSourceListKey: String? = null
    // catalog 載入失敗的獨立錯誤文案（與「尚未載入」區分；失敗保留舊列表）。
    private var catalogLoadError: String? = null
    // 啟動 double-load 守衛：onCreate+onResume 雙 reloadCatalog 取消前一個在途任務。
    private var catalogJob: Job? = null
    // Phase K K2：Shizuku presence 快取在途任務（比照 catalogJob 3536-3537）：
    // refresh/request 頭寫共用同一 Job 取消，避免 old-live 蓋 new-death。
    private var shizukuPresenceJob: Job? = null
    // P1：cancel 非真序列化（pingBinder blocking 不可取消，舊 IO 返回仍寫入
    // last-writer-wins）。單調世代（比照 clonedGen 2066）：發起時主線 ++ 取值，
    // IO 回主線後比對，過期丟棄；cancel 保留（快速釋放）＋gen 比對（正確性），雙保險。
    private var shizukuGen = 0
    // Phase B B4：分身列表指紋（cloned hash + targetSerial + loading）。
    // 未變只同步 Switch enabled（比照 renderSourceList 差量）。
    private var lastClonedListKey: String? = null
    // Phase B B2：分身列表世代 token。renderClonedApps 進入 ++，coroutine
    // capture gen，完成時比對過期丟棄（避免重建後舊圖貼到新行）。
    private var clonedGen = 0
    // Phase B B4：pkg→label 內存快取（catalog 變化失效，避免每次全量 PM IPC）。
    // Phase F F1/F2：同步路徑只讀快取（見 resolveAppLabelCached），miss 走
    // requestLabelAsync 非同步回填；catalogKey 由呼叫端迴圈外一次傳入
    //（見 syncLabelCache），不再每列重算。
    private val labelCache = LinkedHashMap<String, String>()
    private var labelCacheCatalogKey: Int? = null
    // Phase F F1：label 非同步回填在途集合（與 iconInflight 同式，同一 pkg 在途不重打 PM）。
    // Phase I I1：per-pkg 回調合併（Codex P2）。同一 pkg 在途時第二 waiter 不再丟棄，
    // 經 labelCallbacks 合併（各 waiter 舊門保留：行 rowGen/tag、dialog shownPending/isShowing）。
    // 全部主執行緒觸碰（head＋完成皆主線）；完成隨 inflight 清 map；syncLabelCache 清 cache 不清 callbacks。
    private val labelInflight = mutableSetOf<String>()
    private val labelCallbacks = mutableMapOf<String, MutableList<(String) -> Unit>>()
    // Phase B B4：Shizuku binder 存活 volatile 快取。listener/onResume/授權回調
    // 經 refreshShizukuPresenceCache 維護，render 經 cachedShizukuPresence 讀快取，
    // 不再每次 render 都 pingBinder。
    @Volatile private var cachedShizukuNoBinder: Boolean? = null
    @Volatile private var cachedShizukuAuthorized: Boolean = false

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
        // B1：lastWriteBlockText/lastMsgText 為顯示快取（完整文字），View 重建為空時
        // 以 View 實際文字為準補寫；lastWriteBlockDedupKey 為播報去重鍵（穩定去時間），亦隨存活。
        diagExpanded = savedInstanceState?.getBoolean(KEY_DIAG_EXPANDED) ?: false
        lastWriteBlockText = savedInstanceState?.getString(KEY_WRITE_BLOCK_TEXT)
        lastWriteBlockDedupKey = savedInstanceState?.getString(KEY_WRITE_BLOCK_DEDUP)
        lastMsgText = savedInstanceState?.getString(KEY_MSG_TEXT)
        // 移除確認勾選綁定世代保留：同世代旋轉保留勾選，跨世代重置。
        removeAcknowledged = savedInstanceState?.getBoolean(KEY_REMOVE_ACK) ?: false
        acknowledgedGeneration =
            if (savedInstanceState?.containsKey(KEY_REMOVE_ACK_GEN) == true) {
                savedInstanceState.getLong(KEY_REMOVE_ACK_GEN)
            } else {
                null
            }
        currentTab = savedInstanceState?.getInt(KEY_CURRENT_TAB) ?: TAB_DUAL_APPS
        buildUi()
        // Phase C: no announcement for the initial restore (avoids speaking on
        // launch/rotation); user taps announce via switchTab default.
        switchTab(currentTab, announce = false)
        // Shizuku lifecycle wiring; guarded so a device without the Shizuku
        // manager (or an unexpected API failure) never crashes the UI.
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler)
            Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
            Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler)
        } catch (e: Exception) {
            msgView.text = getString(R.string.shizuku_listener_failed, sanitizeForDisplay(e.message ?: "unknown"))
            msgView.setTextColor(msgColorFor(MsgKind.CHANNEL))
        }
        // Lifecycle-aware collection: renders every state update while STARTED.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s -> render(s) }
            }
        }
        refreshShizukuPresenceCache()
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
        refreshShizukuPresenceCache()
        updateShizukuLine()
        // PR-7 P2 (#26):前景重載來源 catalog (安裝/卸載後保持新鮮)。
        reloadCatalog()
        // B1 旋轉恢復：pkgInput 由 View 系統恢復（id=pkg_input）在 onRestoreInstanceState
        // 之後，render 可能已用空輸入跑過一次而卡 disabled；此處以後恢復文字重算一次。
        refreshManualEnablement()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // B1 旋轉恢復：View 系統剛回填 pkgInput 文字，顯式重算 enablement，
        // 避免 render 用恢復前空輸入卡 disabled（VM 守衛仍為縱深）。
        refreshManualEnablement()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋轉保留診斷展開開關 (純 UI 狀態，不經 ViewModel)。
        outState.putBoolean(KEY_DIAG_EXPANDED, diagExpanded)
        outState.putInt(KEY_CURRENT_TAB, currentTab)
        // B1：去重快取隨存活；新 View 重建為空時 render 以 View 實際文字為準補寫，
        // 故恢復值僅作顯示/去重參考，不會導致空 View 跳過首寫。
        // 去重鍵（lastWriteBlockDedupKey）亦隨存活，僅控制播報；B 的 batch/catalog 僅內存。
        if (lastWriteBlockText != null) outState.putString(KEY_WRITE_BLOCK_TEXT, lastWriteBlockText)
        if (lastWriteBlockDedupKey != null) outState.putString(KEY_WRITE_BLOCK_DEDUP, lastWriteBlockDedupKey)
        if (lastMsgText != null) outState.putString(KEY_MSG_TEXT, lastMsgText)
        // 移除確認勾選綁定世代保留（Neutral 不重置，同世代旋轉保留）。
        outState.putBoolean(KEY_REMOVE_ACK, removeAcknowledged)
        if (acknowledgedGeneration != null) outState.putLong(KEY_REMOVE_ACK_GEN, acknowledgedGeneration!!)
    }

    override fun onDestroy() {
        try {
            removeLabelsDialog?.dismiss()
            removeConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        removeLabelsDialog = null
        removeConfirmDialog = null
        shownPending = null
        try {
            exportDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        exportDialog = null
        try {
            settingsDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        settingsDialog = null
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
        refreshShizukuPresenceCache()
        updateShizukuLine()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Phase D: 內聯 divider 共用建構（hook 卡片 12dp／診斷清單預設 4dp 上下邊距）。
    private fun makeDivider(topDp: Int = 4, bottomDp: Int = 4): View = View(this).apply {
        background = getDrawable(R.drawable.divider_horizontal)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(0, dp(topDp), 0, dp(bottomDp))
        }
    }

    // Programmatic layout: One UI 6 Two-Tab System Utility Architecture.
    private fun buildUi() {
        // --- 1. Functional Views Initialization (Preserving all IDs and contracts) ---
        privilegeView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
        shizukuView = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
        channelView = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.oneui_text_primary))
        }
        hookView = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
        profilesView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
        clonedView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
        pkgInput = EditText(this).apply {
            id = R.id.pkg_input
            hint = getString(R.string.pkg_input_hint)
            background = getDrawable(R.drawable.bg_search_input)
            val p = dp(12)
            setPadding(p, p, p, p)
            setTextColor(getColor(R.color.oneui_text_primary))
            setHintTextColor(getColor(R.color.oneui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    error = packageInputError(s?.toString().orEmpty())?.let { resolvePackageInputError(it) }
                    refreshManualEnablement()
                }
            })
            setOnEditorActionListener { v, actionId, event ->
                val done = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (done) {
                    error = packageInputError(text?.toString().orEmpty())?.let { resolvePackageInputError(it) }
                    try {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(v.windowToken, 0)
                    } catch (_: Exception) {
                    }
                    true
                } else {
                    false
                }
            }
        }
        cloneBtn = Button(this).apply {
            id = R.id.clone_btn
            text = getString(R.string.action_clone)
            background = getDrawable(R.drawable.bg_pill_button)
            // Phase C: theme token (light #FFFFFF 5.42 on primary; night
            // #002958 4.52 on primary_container via drawable-night).
            setTextColor(getColor(R.color.oneui_button_text))
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(48)
            setOnClickListener { vm.clone(pkgInput.text.toString()) }
        }
        removeBtn = Button(this).apply {
            id = R.id.remove_btn
            text = getString(R.string.action_remove)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            contentDescription = getString(R.string.remove_cd)
            minimumHeight = dp(48)
            setOnClickListener { vm.requestRemove(pkgInput.text.toString()) }
        }
        refreshBtn = Button(this).apply {
            id = R.id.refresh_btn
            text = getString(R.string.action_reconnect)
            background = getDrawable(R.drawable.bg_pill_button)
            // Phase C: theme token (see cloneBtn).
            setTextColor(getColor(R.color.oneui_button_text))
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(48)
            setOnClickListener {
                vm.reconnectAndRefresh(allowPrivilegedHookDiagnosis = false)
                reloadCatalog()
            }
        }
        permBtn = Button(this).apply {
            id = R.id.perm_btn
            text = getString(R.string.action_request_shizuku)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            minimumHeight = dp(48)
            setOnClickListener { requestShizukuPermission() }
        }
        channelGroup = RadioGroup(this).apply {
            id = R.id.channel_group
            orientation = RadioGroup.HORIZONTAL
            contentDescription = getString(R.string.channel_group_cd)
            isScreenReaderFocusable = true
        }
        autoBtn = RadioButton(this).apply {
            id = R.id.channel_auto
            text = getString(R.string.channel_auto)
            setTextColor(getColor(R.color.oneui_text_primary))
            minimumWidth = dp(64)
            minimumHeight = dp(48)
            setOnClickListener { vm.selectChannel(ChannelPreference.AUTO, allowPrivilegedHookDiagnosis = false) }
        }
        rootBtn = RadioButton(this).apply {
            id = R.id.channel_root
            text = getString(R.string.channel_root)
            setTextColor(getColor(R.color.oneui_text_primary))
            minimumWidth = dp(64)
            minimumHeight = dp(48)
            setOnClickListener { vm.selectChannel(ChannelPreference.ROOT, allowPrivilegedHookDiagnosis = false) }
        }
        shizukuBtn = RadioButton(this).apply {
            id = R.id.channel_shizuku
            text = getString(R.string.channel_shizuku)
            setTextColor(getColor(R.color.oneui_text_primary))
            minimumWidth = dp(64)
            minimumHeight = dp(48)
            setOnClickListener { vm.selectChannel(ChannelPreference.SHIZUKU, allowPrivilegedHookDiagnosis = false) }
        }
        lsposedBtn = RadioButton(this).apply {
            id = R.id.channel_lsposed
            text = getString(R.string.channel_lsposed)
            setTextColor(getColor(R.color.oneui_text_primary))
            minimumWidth = dp(64)
            minimumHeight = dp(48)
            setOnClickListener { vm.selectChannel(ChannelPreference.LSPOSED, allowPrivilegedHookDiagnosis = false) }
        }
        channelGroup.addView(autoBtn, radioParams())
        channelGroup.addView(rootBtn, radioParams())
        channelGroup.addView(shizukuBtn, radioParams())
        channelGroup.addView(lsposedBtn, radioParams())

        channelScroll = HorizontalScrollView(this).apply {
            id = R.id.channel_scroll
            isHorizontalScrollBarEnabled = false
            addView(
                channelGroup,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        hookDiagBtn = Button(this).apply {
            text = getString(R.string.action_diagnose_hook)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            contentDescription = getString(R.string.diagnose_hook_cd)
            minimumHeight = dp(48)
            setOnClickListener { vm.requestHookRedDiagnosis() }
        }
        hookDiagHint = TextView(this).apply {
            text = getString(R.string.hook_diag_hint)
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_tertiary))
        }
        msgView = TextView(this).apply {
            id = R.id.msg_view
            textSize = 13f
            setTextColor(getColor(R.color.oneui_text_primary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        writeBlockView = TextView(this).apply {
            id = R.id.write_block_reason
            visibility = View.GONE
            textSize = 12f
            setTextColor(getColor(R.color.oneui_warning_text))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        inventoryView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
        lastOpView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_tertiary))
        }
        diagView = TextView(this).apply {
            isFocusable = true
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            contentDescription = getString(R.string.diag_loading_cd)
            setOnClickListener {
                diagExpanded = !diagExpanded
                lastState?.let { render(it) }
                try {
                    announceForAccessibility(contentDescription)
                } catch (_: Exception) {
                }
            }
        }
        exportBtn = Button(this).apply {
            text = getString(R.string.action_export_diagnosis)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            minimumHeight = dp(48)
            setOnClickListener { showDiagnosticExport() }
        }
        searchInput = EditText(this).apply {
            id = R.id.search_input
            hint = getString(R.string.search_hint)
            background = null
            setTextColor(getColor(R.color.oneui_text_primary))
            // Phase C: hint uses text_secondary (#475569 on #DFE7F0 = 6.07;
            // tertiary was 3.81, below AA 4.5).
            setHintTextColor(getColor(R.color.oneui_text_secondary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
            setText(vm.state.value.sourceQuery)
            try {
                setSelection(text?.length ?: 0)
            } catch (_: Exception) {
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    vm.setSourceQuery(s?.toString().orEmpty())
                    if (::clearSearchBtn.isInitialized) {
                        clearSearchBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    }
                }
            })
        }
        systemToggleBtn = Button(this).apply {
            text = getString(R.string.system_apps_hidden)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (height is
            // WRAP_CONTENT so large fonts grow instead of clipping).
            minimumHeight = dp(48)
            contentDescription = getString(R.string.system_toggle_cd_hidden)
            setOnClickListener { vm.setIncludeSystemApps(!vm.state.value.includeSystemApps) }
        }
        selectAllBtn = Button(this).apply {
            text = getString(R.string.action_select_visible)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (see systemToggleBtn).
            minimumHeight = dp(48)
            contentDescription = resources.getQuantityString(
                R.plurals.select_visible_cd,
                MAX_SOURCE_ROWS,
                MAX_SOURCE_ROWS,
            )
            setOnClickListener { vm.selectVisible(currentVisibleKeys()) }
        }
        clearSelBtn = Button(this).apply {
            text = getString(R.string.action_clear_selection)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (see systemToggleBtn).
            minimumHeight = dp(48)
            contentDescription = getString(R.string.clear_selection_cd)
            setOnClickListener { vm.clearSelectionLocal() }
        }
        saveSelBtn = Button(this).apply {
            text = getString(R.string.action_save_selection)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (see systemToggleBtn).
            minimumHeight = dp(48)
            contentDescription = getString(R.string.save_selection_cd)
            setOnClickListener { vm.persistSelection(vm.state.value.inventory.targetId) }
        }
        cancelSaveBtn = Button(this).apply {
            text = getString(R.string.action_unsave_selection)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (see systemToggleBtn).
            minimumHeight = dp(48)
            contentDescription = getString(R.string.unsave_selection_cd)
            setOnClickListener { vm.cancelSavedSelection() }
        }
        sourceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        catalogErrorView = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            setTextColor(getColor(R.color.oneui_error))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        batchBtn = Button(this).apply {
            id = R.id.batch_btn
            text = resources.getQuantityString(R.plurals.batch_create, 0, 0)
            background = getDrawable(R.drawable.bg_pill_button)
            // Phase C: theme token (see cloneBtn).
            setTextColor(getColor(R.color.oneui_button_text))
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(48)
            contentDescription = resources.getQuantityString(R.plurals.batch_create_cd, 0, 0)
            setOnClickListener { vm.cloneSelected() }
        }
        batchResultView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        hookRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hookDiagBtn, rowParams())
        }

        // --- 2. Build Root and Containers ---
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.oneui_canvas))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Top Fixed Header
        val headerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.oneui_surface))
            setPadding(dp(20), dp(16), dp(20), dp(12))
            elevation = dp(2).toFloat()
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val brandLogo = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_nav_dual_apps))
            setColorFilter(getColor(R.color.oneui_primary))
            // Phase C: decorative (brand text beside it carries meaning).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val brandText = TextView(this).apply {
            text = getString(R.string.hero_brand_title)
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        statusPillDot = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_dot_amber))
            // Phase C: decorative status dot (pill text carries the state).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                marginEnd = dp(6)
            }
        }
        statusPillText = TextView(this).apply {
            // Phase A：初值走資源的中性 loading（render 前無通道信號，不預設 LSPosed 綠）。
            text = getString(R.string.status_loading)
            textSize = 11f
            setTextColor(getColor(R.color.oneui_warning_text))
            typeface = Typeface.MONOSPACE
        }
        statusPillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_pill_badge_amber)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(statusPillDot)
            addView(statusPillText)
        }
        brandRow.addView(brandLogo)
        brandRow.addView(brandText)
        brandRow.addView(statusPillContainer)
        headerContainer.addView(brandRow)

        heroCategoryView = TextView(this).apply {
            text = getString(R.string.badge_samsung_hook)
            textSize = 11f
            setTextColor(getColor(R.color.oneui_primary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        heroTitleView = TextView(this).apply {
            text = getString(R.string.hero_title_apps)
            textSize = 24f
            setTextColor(getColor(R.color.oneui_text_primary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        }
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        }
        val subIcon = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_security))
            setColorFilter(getColor(R.color.oneui_primary))
            // Phase C: decorative (subtitle text beside it carries meaning).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                marginEnd = dp(4)
            }
        }
        heroSubtitleView = TextView(this).apply {
            text = getString(R.string.hero_subtitle_apps)
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
        subRow.addView(subIcon)
        subRow.addView(heroSubtitleView)

        headerContainer.addView(heroCategoryView)
        headerContainer.addView(heroTitleView)
        headerContainer.addView(subRow)
        root.addView(headerContainer)

        // Persistent Status & Feedback Banner
        val statusBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card_high)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val h = dp(16)
                setMargins(h, dp(6), h, dp(2))
            }
        }
        statusBanner.addView(msgView)
        statusBanner.addView(writeBlockView)
        root.addView(statusBanner)

        // Content Frame (Holds Tab 1 & Tab 2 ScrollViews)
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // ================= Tab 1: 雙開應用 (Dual Apps) =================
        tabAppsScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val tabAppsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(8), pad, dp(16))
        }

        tabAppsLayout.addView(buildHookModeCard())

        // Batch Action Button
        tabAppsLayout.addView(batchBtn)
        tabAppsLayout.addView(batchResultView)

        // Search Bar with Clear Button (Phase C: WRAP_CONTENT + minimumHeight so
        // large fonts grow instead of clipping in a fixed dp48 box).
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_search_input)
            val p = dp(10)
            setPadding(p, dp(4), p, dp(4))
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        val searchIcon = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_search))
            setColorFilter(getColor(R.color.oneui_text_tertiary))
            // Phase C: decorative (search field hint carries meaning).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(8)
            }
        }
        clearSearchBtn = ImageView(this).apply {
            contentDescription = getString(R.string.btn_clear_search)
            setImageDrawable(getDrawable(R.drawable.ic_clear))
            setColorFilter(getColor(R.color.oneui_text_tertiary))
            val cp = dp(14)
            setPadding(cp, cp, cp, cp)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            visibility = if (searchInput.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { searchInput.setText("") }
        }
        searchContainer.addView(searchIcon)
        searchContainer.addView(searchInput)
        searchContainer.addView(clearSearchBtn)
        tabAppsLayout.addView(searchContainer)

        // Catalog Filter Buttons (Horizontal Scroll)
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = dp(4)
            // Phase C: WRAP_CONTENT height + 48dp minimumHeight (was fixed dp48,
            // which clips large fonts). Touch target stays >= 48dp.
            addView(systemToggleBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(p, 0, p, 0) })
            addView(selectAllBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(p, 0, p, 0) })
            addView(clearSelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(p, 0, p, 0) })
            addView(saveSelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(p, 0, p, 0) })
            addView(cancelSaveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(p, 0, p, 0) })
        }
        filterScroll.addView(filterRow)
        tabAppsLayout.addView(filterScroll)

        // Section 1: Injected Bypass Apps
        val injectedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val injectedTitle = TextView(this).apply {
            text = getString(R.string.section_injected_apps)
            textSize = 14f
            setTextColor(getColor(R.color.oneui_text_primary))
            typeface = Typeface.DEFAULT_BOLD
        }
        clonedCountBadge = TextView(this).apply {
            // Phase C: initial count via resources (was hardcoded "0").
            text = getString(R.string.badge_number_format, 0)
            textSize = 11f
            // Phase C: blue-pill text (see hookSecPmTag).
            setTextColor(getColor(R.color.oneui_on_primary_container))
            background = getDrawable(R.drawable.bg_pill_badge_blue)
            val hp = dp(8)
            val vp = dp(2)
            setPadding(hp, vp, hp, vp)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        }
        injectedActiveLabel = TextView(this).apply {
            text = getString(R.string.status_hook_injected_active)
            textSize = 11f
            // Phase C: tint (light #046C4E on canvas #F0F4FA ≈ 5.8; #059669
            // was 3.60).
            setTextColor(getColor(R.color.oneui_secondary_tint))
            visibility = View.GONE
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        injectedHeader.addView(injectedTitle)
        injectedHeader.addView(clonedCountBadge)
        injectedHeader.addView(injectedActiveLabel)
        tabAppsLayout.addView(injectedHeader)

        val injectedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        clonedAppsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        injectedCard.addView(clonedAppsContainer)
        // Keep clonedView attached inside the card for accessibility/testing
        clonedView.visibility = View.GONE
        injectedCard.addView(clonedView)
        tabAppsLayout.addView(injectedCard)

        // Section 2: Pending Recommendation Injections
        val pendingHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val pendingTitle = TextView(this).apply {
            text = getString(R.string.section_pending_apps)
            textSize = 14f
            setTextColor(getColor(R.color.oneui_text_primary))
            typeface = Typeface.DEFAULT_BOLD
        }
        val pendingHint = TextView(this).apply {
            text = getString(R.string.section_pending_apps_hint)
            textSize = 11f
            setTextColor(getColor(R.color.oneui_text_tertiary))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        pendingHeader.addView(pendingTitle)
        pendingHeader.addView(pendingHint)
        tabAppsLayout.addView(pendingHeader)

        val pendingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        pendingCard.addView(sourceContainer)
        pendingCard.addView(catalogErrorView)
        tabAppsLayout.addView(pendingCard)

        // Section 3: Advanced Manual Entry
        val manualCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        val manualTitle = TextView(this).apply {
            text = getString(R.string.section_advanced_manual)
            textSize = 14f
            setTextColor(getColor(R.color.oneui_text_primary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        manualCard.addView(manualTitle)
        manualCard.addView(pkgInput)
        val writeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            addView(cloneBtn, rowParams())
            addView(removeBtn, rowParams())
        }
        manualCard.addView(writeRow)
        tabAppsLayout.addView(manualCard)

        // Daemon Footer Inset Card (Phase A: render() 僅在 LSPOSED + ACTIVE 時顯示，
        // 無 Hook 信號前隱藏，不再無條件宣稱攔截成功)。
        daemonFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = getDrawable(R.drawable.bg_island_card_high)
            val p = dp(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }
        val terminalIcon = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_terminal))
            setColorFilter(getColor(R.color.oneui_primary))
            // Phase C: decorative (daemon text beside it carries meaning).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginEnd = dp(8)
            }
        }
        val daemonTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = getString(R.string.status_hook_daemon_running)
                textSize = 12f
                setTextColor(getColor(R.color.oneui_text_primary))
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = getString(R.string.status_hook_daemon_desc)
                textSize = 10f
                setTextColor(getColor(R.color.oneui_text_tertiary))
            })
        }
        daemonFooter.addView(terminalIcon)
        daemonFooter.addView(daemonTextContainer)
        tabAppsLayout.addView(daemonFooter)

        tabAppsScroll.addView(tabAppsLayout)
        contentFrame.addView(tabAppsScroll)

        // ================= Tab 2: 核心引擎 (Core Engine) =================
        tabEngineScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        val tabEngineLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(8), pad, dp(16))
        }

        tabEngineLayout.addView(buildEngineMasterCard())

        // Island 2: Channel Preference & Execution Mode
        val channelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val channelCardTitle = TextView(this).apply {
            text = getString(R.string.section_channel_preference)
            textSize = 14f
            setTextColor(getColor(R.color.oneui_text_primary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        channelCard.addView(channelCardTitle)
        channelCard.addView(channelView)
        channelCard.addView(channelScroll)
        channelCard.addView(hookView)
        channelCard.addView(hookRow)
        channelCard.addView(hookDiagHint)
        tabEngineLayout.addView(channelCard)

        // Island 3: System Hook Diagnostics Checklist
        val checklistCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val checkHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            addView(TextView(context).apply {
                text = getString(R.string.diag_checklist_title)
                textSize = 13f
                setTextColor(getColor(R.color.oneui_primary))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Phase A：5/5 全綠僅在 LSPOSED + ACTIVE 時顯示；初值/無信號為未知。
            checklistSummaryView = TextView(context).apply {
                text = getString(R.string.engine_diag_unknown)
                textSize = 11f
                setTextColor(getColor(R.color.oneui_text_tertiary))
                typeface = Typeface.MONOSPACE
            }
            addView(checklistSummaryView)
        }
        checklistCard.addView(checkHeader)

        // 5 Diagnostic Items
        fun addChecklistItem(iconRes: Int, titleStr: String, descStr: String, badgeText: String, isGreen: Boolean = true) {
            val itemRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val ip = dp(6)
                setPadding(0, ip, 0, ip)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val itemIcon = ImageView(this).apply {
                setImageDrawable(getDrawable(iconRes))
                setColorFilter(getColor(if (isGreen) R.color.oneui_secondary else R.color.oneui_primary))
                // Phase C: decorative (item title/desc beside it carries meaning).
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = titleStr
                    textSize = 13f
                    setTextColor(getColor(R.color.oneui_text_primary))
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(context).apply {
                    text = descStr
                    textSize = 11f
                    setTextColor(getColor(R.color.oneui_text_tertiary))
                })
            }
            val itemBadge = TextView(this).apply {
                text = badgeText
                textSize = 10f
                // Phase C: green badge text uses the tint, blue badge text uses
                // on_primary_container (see hookBadgeChip/hookSecPmTag).
                setTextColor(getColor(if (isGreen) R.color.oneui_secondary_tint else R.color.oneui_on_primary_container))
                background = getDrawable(if (isGreen) R.drawable.bg_pill_badge_green else R.drawable.bg_pill_badge_blue)
                visibility = View.GONE
                val hp = dp(6)
                val vp = dp(2)
                setPadding(hp, vp, hp, vp)
            }
            checklistItemBadges.add(itemBadge)
            // Phase G: track icon + provenance color for the unproven-gray switch.
            checklistItemIcons.add(itemIcon)
            checklistItemIsGreen.add(isGreen)
            itemRow.addView(itemIcon)
            itemRow.addView(textCol)
            itemRow.addView(itemBadge)
            checklistCard.addView(itemRow)
        }

        checklistItemBadges.clear()
        checklistItemIcons.clear()
        checklistItemIsGreen.clear()
        // Phase G: bullet prefix via badge_dot_format (was hardcoded "● ").
        addChecklistItem(R.drawable.ic_layers, getString(R.string.diag_item_framework_title), getString(R.string.diag_item_framework_desc), getString(R.string.badge_dot_format, getString(R.string.badge_status_normal)), true)
        checklistCard.addView(makeDivider())
        addChecklistItem(R.drawable.ic_security, getString(R.string.diag_item_isolation_title), getString(R.string.diag_item_isolation_desc), getString(R.string.badge_status_bypassed), false)
        checklistCard.addView(makeDivider())

        // Item 3: Multi-User UID
        val uidRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val ip = dp(6)
            setPadding(0, ip, 0, ip)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Phase G: uid icon joins the unproven-gray switch (green provenance).
            addView(ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_multi_user))
                setColorFilter(getColor(R.color.oneui_secondary))
                // Phase C: decorative (multi-user title/desc beside it carries meaning).
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
            }.also { engineUidIcon = it })
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = getString(R.string.diag_item_multiuser_title)
                    textSize = 13f
                    setTextColor(getColor(R.color.oneui_text_primary))
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(context).apply {
                    text = getString(R.string.diag_item_multiuser_desc)
                    textSize = 11f
                    setTextColor(getColor(R.color.oneui_text_tertiary))
                })
            }
            addView(textCol)
            engineUidSlotView = TextView(context).apply {
                // Phase C: initial placeholder via resources (was "UID: —").
                text = getString(R.string.engine_uid_unknown)
                textSize = 11f
                // Phase C: primary on island_card_high is 4.35/4.23; tint is
                // 6.50/9.35.
                setTextColor(getColor(R.color.oneui_primary_tint))
                background = getDrawable(R.drawable.bg_island_card_high)
                val hp = dp(6)
                val vp = dp(2)
                setPadding(hp, vp, hp, vp)
                typeface = Typeface.MONOSPACE
            }
            addView(engineUidSlotView)
        }
        checklistCard.addView(uidRow)
        checklistCard.addView(makeDivider())
        addChecklistItem(R.drawable.ic_push_sync, getString(R.string.diag_item_push_title), getString(R.string.diag_item_push_desc), getString(R.string.badge_status_isolated), true)
        checklistCard.addView(makeDivider())
        // Phase G: count prefix via badge_dual_tag_format (was hardcoded "#2 ").
        addChecklistItem(R.drawable.ic_badge_style, getString(R.string.diag_item_badge_title), getString(R.string.diag_item_badge_desc), getString(R.string.badge_dual_tag_format, 2, getString(R.string.badge_status_dual_tag)), false)

        tabEngineLayout.addView(checklistCard)

        // Island 4: Snapshots & Technical Telemetry
        val techCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        techCard.addView(TextView(this).apply {
            text = getString(R.string.section_profiles_diagnostics)
            textSize = 14f
            setTextColor(getColor(R.color.oneui_text_primary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        })
        techCard.addView(privilegeView)
        techCard.addView(profilesView)
        techCard.addView(inventoryView)
        techCard.addView(lastOpView)
        techCard.addView(diagView)
        tabEngineLayout.addView(techCard)

        // Engine Action Buttons
        val engineActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
            addView(refreshBtn, rowParams())
            addView(exportBtn, rowParams())
        }
        tabEngineLayout.addView(engineActionRow)

        tabEngineScroll.addView(tabEngineLayout)
        contentFrame.addView(tabEngineScroll)
        root.addView(contentFrame)

        // ================= Bottom Navigation Bar =================
        // Phase C: WRAP_CONTENT + minimumHeight (was fixed dp60, clips large
        // fonts). Touch target stays >= 60dp.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.oneui_surface_container_lowest))
            minimumHeight = dp(60)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            elevation = dp(8).toFloat()
        }

        fun createNavItem(iconRes: Int, labelStr: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                // Phase C: the item carries the label (+selected state, synced
                // in updateNavTabStyles); children stay silent to avoid double
                // announcement.
                contentDescription = labelStr
                val p = dp(6)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                val icon = ImageView(context).apply {
                    setImageDrawable(getDrawable(iconRes))
                    setColorFilter(getColor(R.color.oneui_text_tertiary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                }
                val label = TextView(context).apply {
                    text = labelStr
                    textSize = 11f
                    setTextColor(getColor(R.color.oneui_text_tertiary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(2)
                    }
                }
                addView(icon)
                addView(label)
                setOnClickListener { onClick() }
            }
        }

        navTabApps = createNavItem(R.drawable.ic_nav_dual_apps, getString(R.string.tab_dual_apps)) {
            switchTab(TAB_DUAL_APPS)
        }
        navTabEngine = createNavItem(R.drawable.ic_nav_engine, getString(R.string.tab_core_engine)) {
            switchTab(TAB_CORE_ENGINE)
        }
        navTabSettings = createNavItem(R.drawable.ic_nav_settings, getString(R.string.tab_settings)) {
            showSettingsDialog()
        }

        bottomBar.addView(navTabApps)
        bottomBar.addView(navTabEngine)
        bottomBar.addView(navTabSettings)
        root.addView(bottomBar)

        setContentView(root)
    }

    // Phase D: buildUi 最小拆（純建構提取，行為不變）。兩卡片僅搬移程式碼位置：
    // lateinit 綁定、Phase A-D 語意、監聽器與初值皆保留原邏輯，不做重構。
    private fun buildHookModeCard(): LinearLayout {
        // Card 1: Hook Mode Status Card
        val hookModeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val hookTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val boltIcon = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_bolt))
            setColorFilter(getColor(R.color.oneui_secondary))
            // Phase C: decorative (card title beside it carries meaning).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(6)
            }
        }
        val hookModeTitle = TextView(this).apply {
            // Phase A：LSPosed 版本無運行時查詢源，不寫死 102，顯示版本未知。
            text = getString(R.string.hook_mode_title, getString(R.string.lsposed_version_unknown))
            textSize = 14f
            setTextColor(getColor(R.color.oneui_text_primary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        hookBadgeChip = TextView(this).apply {
            text = getString(R.string.hook_badge_sec_hook)
            textSize = 10f
            // Phase C: body green text uses the tint (light #046C4E on
            // #D1FAE5 = 5.68; #059669 was 3.32). Icons/strokes keep secondary.
            setTextColor(getColor(R.color.oneui_secondary_tint))
            background = getDrawable(R.drawable.bg_pill_badge_green)
            visibility = View.GONE
            val hp = dp(8)
            val vp = dp(2)
            setPadding(hp, vp, hp, vp)
        }
        hookTitleRow.addView(boltIcon)
        hookTitleRow.addView(hookModeTitle)
        hookTitleRow.addView(hookBadgeChip)
        hookModeCard.addView(hookTitleRow)

        val hookDesc = TextView(this).apply {
            text = getString(R.string.hook_mode_subtitle)
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        hookModeCard.addView(hookDesc)

        val hookTagsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        hookSecPmTag = TextView(this).apply {
            text = getString(R.string.hook_badge_sec_pm)
            textSize = 10f
            // Phase C: blue-pill text uses on_primary_container (light #001D4D
            // on #D6E4FF = 12.78; night #002958 on #418FFF = 4.52). primary was
            // 4.24 light and primary_tint (#AAC7FF night) is 1.87 on #418FFF.
            setTextColor(getColor(R.color.oneui_on_primary_container))
            background = getDrawable(R.drawable.bg_pill_badge_blue)
            visibility = View.GONE
            val hp = dp(8)
            val vp = dp(2)
            setPadding(hp, vp, hp, vp)
        }
        hookUidSandboxTag = TextView(this).apply {
            text = getString(R.string.hook_badge_uid_sandbox)
            textSize = 10f
            setTextColor(getColor(R.color.oneui_text_secondary))
            background = getDrawable(R.drawable.bg_island_card_high)
            visibility = View.GONE
            val hp = dp(8)
            val vp = dp(2)
            setPadding(hp, vp, hp, vp)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        }
        hookTagsRow.addView(hookSecPmTag)
        hookTagsRow.addView(hookUidSandboxTag)
        hookModeCard.addView(hookTagsRow)

        hookModeCard.addView(makeDivider(12, 12))

        // 3-Column Stats Row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card_high)
            val p = dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            addView(TextView(context).apply {
                text = getString(R.string.stats_native_whitelist)
                textSize = 10f
                setTextColor(getColor(R.color.oneui_text_tertiary))
            })
            addView(TextView(context).apply {
                // Phase A：官方白名單數無數據源（repo 從未讀取三星白名單），不寫死 5 款。
                text = getString(R.string.stats_value_unknown)
                textSize = 15f
                setTextColor(getColor(R.color.oneui_text_primary))
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card_high)
            val p = dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            addView(TextView(context).apply {
                text = getString(R.string.stats_injected_count)
                textSize = 10f
                // Phase C: primary on island_card_high (#DFE7F0) is 4.35 light
                // / 4.23 night; tint is 6.50 / 9.35.
                setTextColor(getColor(R.color.oneui_primary_tint))
            })
            clonedCountStatView = TextView(context).apply {
                // Phase C: initial value via resources (was hardcoded "0 …(Hook)").
                text = getString(R.string.stats_injected_format, 0, getString(R.string.stats_unit_apps))
                textSize = 15f
                // Phase G: tint (light #004DA3 on #DFE7F0 = 6.50; primary
                // #0066D6 was 4.35, failed 4.5).
                setTextColor(getColor(R.color.oneui_primary_tint))
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(clonedCountStatView)
        }
        // Phase A A1：第三格由 render() 綁定 hookStatus；初值為未知（amber），
        // 非 LSPOSED 通道時整格隱藏（對齊 renderHookText 回 null 條件）。
        hookStatusCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card_high)
            val p = dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
            addView(TextView(context).apply {
                text = getString(R.string.stats_hook_status)
                textSize = 10f
                setTextColor(getColor(R.color.oneui_text_tertiary))
            })
            val statusItem = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageDrawable(getDrawable(R.drawable.ic_dot_amber))
                    // Phase C: decorative (hook status text carries the state).
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(4) }
                }.also { hookStatusDot = it })
                addView(TextView(context).apply {
                    text = getString(R.string.stats_hook_unknown)
                    textSize = 13f
                    setTextColor(getColor(R.color.oneui_warning_text))
                    typeface = Typeface.DEFAULT_BOLD
                }.also { hookStatusText = it })
            }
            addView(statusItem)
        }
        statsRow.addView(col1)
        statsRow.addView(col2)
        statsRow.addView(hookStatusCell)
        hookModeCard.addView(statsRow)
        return hookModeCard
    }

    private fun buildEngineMasterCard(): LinearLayout {
        // Island 1: Master Visual Card
        val engineMasterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card)
            val p = dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val engineTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val engineIcon = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_nav_engine))
            setColorFilter(getColor(R.color.oneui_primary))
            // Phase C: decorative (engine title beside it carries meaning).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(8)
            }
        }
        val engineTitleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = getString(R.string.engine_status_title)
                textSize = 14f
                setTextColor(getColor(R.color.oneui_text_primary))
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = getString(R.string.engine_status_subtitle)
                textSize = 11f
                setTextColor(getColor(R.color.oneui_text_tertiary))
            })
        }
        residentBadge = TextView(this).apply {
            // Phase G: bullet prefix via badge_dot_format (was hardcoded "● ").
            text = getString(R.string.badge_dot_format, getString(R.string.engine_status_active))
            textSize = 10f
            // Phase C: green-pill text uses the tint (see hookBadgeChip).
            setTextColor(getColor(R.color.oneui_secondary_tint))
            background = getDrawable(R.drawable.bg_pill_badge_green)
            visibility = View.GONE
            val hp = dp(8)
            val vp = dp(2)
            setPadding(hp, vp, hp, vp)
        }
        engineTitleRow.addView(engineIcon)
        engineTitleRow.addView(engineTitleContainer)
        engineTitleRow.addView(residentBadge)
        engineMasterCard.addView(engineTitleRow)

        // Telemetry Inset Banner
        val telemetryBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card_high)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        }
        val telemetryTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gaugeTitles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = getString(R.string.engine_gauge_title)
                textSize = 16f
                // Phase G: tint (light #004DA3 on #DFE7F0 = 6.50; primary
                // #0066D6 was 4.35, failed 4.5).
                setTextColor(getColor(R.color.oneui_primary_tint))
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                // Phase A：版本無運行時查詢源，不寫死 102，顯示版本未知。
                text = getString(R.string.engine_gauge_subtitle, getString(R.string.lsposed_version_unknown))
                textSize = 11f
                setTextColor(getColor(R.color.oneui_text_tertiary))
                typeface = Typeface.MONOSPACE
            })
        }
        val gaugeIcon = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_nav_engine))
            setColorFilter(getColor(R.color.oneui_primary))
            // Phase C: decorative (gauge title beside it carries meaning).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        }
        telemetryTopRow.addView(gaugeTitles)
        telemetryTopRow.addView(gaugeIcon)
        telemetryBanner.addView(telemetryTopRow)

        val telemetryBottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            // Phase A：telemetry 行由 render() 綁定 hookStatus；初值未知（amber）。
            // Phase G：延遲 pill 已移除（全 repo 無延遲測量源，不偽造 0.42ms，
            // 死佔位「—」render 永不更新）。
            telemetryDot = ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_dot_amber))
                // Phase C: decorative (telemetry text carries the state).
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) }
            }
            addView(telemetryDot)
            telemetryStatusView = TextView(context).apply {
                text = getString(R.string.telemetry_status_unknown)
                textSize = 12f
                setTextColor(getColor(R.color.oneui_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(telemetryStatusView)
        }
        telemetryBanner.addView(telemetryBottomRow)
        engineMasterCard.addView(telemetryBanner)

        // Shizuku Fallback Inset Card
        val shizukuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_island_card_high)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val shizukuTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_terminal))
                setColorFilter(getColor(R.color.oneui_text_secondary))
                // Phase C: decorative (fallback title beside it carries meaning).
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
            })
            val titleCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = getString(R.string.engine_fallback_title)
                    textSize = 13f
                    setTextColor(getColor(R.color.oneui_text_primary))
                    typeface = Typeface.DEFAULT_BOLD
                })
                // Phase A A4：初值未知（updateShizukuLine 經同源 booleans 覆寫，
                // 不再預設已授權）；徽章同。
                shizukuFallbackDescView = TextView(context).apply {
                    text = getString(R.string.shizuku_state_unknown, getString(R.string.status_loading))
                    textSize = 11f
                    setTextColor(getColor(R.color.oneui_text_tertiary))
                }
                addView(shizukuFallbackDescView)
                addView(shizukuView)
            }
            addView(titleCol)
            shizukuFallbackBadgeView = TextView(context).apply {
                text = getString(R.string.status_loading)
                textSize = 10f
                setTextColor(getColor(R.color.oneui_warning_text))
                background = getDrawable(R.drawable.bg_pill_badge_amber)
                val hp = dp(6)
                val vp = dp(2)
                setPadding(hp, vp, hp, vp)
            }
            addView(shizukuFallbackBadgeView)
        }
        shizukuCard.addView(shizukuTopRow)
        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            // Phase C: WRAP_CONTENT height (permBtn already has 48dp
            // minimumHeight) so large fonts grow instead of clipping.
            addView(permBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        shizukuCard.addView(permRow)
        engineMasterCard.addView(shizukuCard)
        return engineMasterCard
    }

    private fun switchTab(tab: Int, announce: Boolean = true) {
        currentTab = tab
        if (!::tabAppsScroll.isInitialized || !::tabEngineScroll.isInitialized) return
        if (tab == TAB_DUAL_APPS) {
            tabAppsScroll.visibility = View.VISIBLE
            tabEngineScroll.visibility = View.GONE
            heroCategoryView.text = getString(R.string.badge_samsung_hook)
            heroTitleView.text = getString(R.string.hero_title_apps)
            heroSubtitleView.text = getString(R.string.hero_subtitle_apps)
            updateNavTabStyles(TAB_DUAL_APPS)
        } else {
            tabAppsScroll.visibility = View.GONE
            tabEngineScroll.visibility = View.VISIBLE
            heroCategoryView.text = getString(R.string.badge_oneui_sandbox)
            heroTitleView.text = getString(R.string.hero_title_engine)
            heroSubtitleView.text = getString(R.string.hero_subtitle_engine)
            updateNavTabStyles(TAB_CORE_ENGINE)
        }
        // Phase C: VISIBLE/GONE switches move no focus, so announce the new
        // hero title for screen readers (a no-op when a11y is off).
        if (announce) {
            try {
                heroTitleView.announceForAccessibility(heroTitleView.text)
            } catch (_: Exception) {
                // Best effort: announcement failure must not break tab switch.
            }
        }
    }

    private fun updateNavTabStyles(activeTab: Int) {
        if (!::navTabApps.isInitialized || !::navTabEngine.isInitialized || !::navTabSettings.isInitialized) return
        val activeColor = getColor(R.color.oneui_primary)
        val inactiveColor = getColor(R.color.oneui_text_tertiary)

        // Phase C: selection semantics for screen readers (isSelected +
        // "label, selected" description; unselected tabs expose the bare label).
        navTabApps.isSelected = activeTab == TAB_DUAL_APPS
        navTabApps.contentDescription = navTabDescription(getString(R.string.tab_dual_apps), activeTab == TAB_DUAL_APPS)
        navTabEngine.isSelected = activeTab == TAB_CORE_ENGINE
        navTabEngine.contentDescription = navTabDescription(getString(R.string.tab_core_engine), activeTab == TAB_CORE_ENGINE)
        navTabSettings.isSelected = false
        navTabSettings.contentDescription = getString(R.string.tab_settings)

        val appsIcon = navTabApps.getChildAt(0) as? ImageView
        val appsLabel = navTabApps.getChildAt(1) as? TextView
        appsIcon?.setColorFilter(if (activeTab == TAB_DUAL_APPS) activeColor else inactiveColor)
        appsLabel?.setTextColor(if (activeTab == TAB_DUAL_APPS) activeColor else inactiveColor)
        appsLabel?.typeface = if (activeTab == TAB_DUAL_APPS) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val engineIcon = navTabEngine.getChildAt(0) as? ImageView
        val engineLabel = navTabEngine.getChildAt(1) as? TextView
        engineIcon?.setColorFilter(if (activeTab == TAB_CORE_ENGINE) activeColor else inactiveColor)
        engineLabel?.setTextColor(if (activeTab == TAB_CORE_ENGINE) activeColor else inactiveColor)
        engineLabel?.typeface = if (activeTab == TAB_CORE_ENGINE) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val settingsIcon = navTabSettings.getChildAt(0) as? ImageView
        val settingsLabel = navTabSettings.getChildAt(1) as? TextView
        settingsIcon?.setColorFilter(inactiveColor)
        settingsLabel?.setTextColor(inactiveColor)
        settingsLabel?.typeface = Typeface.DEFAULT
    }

    // Phase C: bottom-nav content description ("label, selected" when active,
    // bare label otherwise). try/catch keeps the pre-buildUi test path safe.
    private fun navTabDescription(label: String, selected: Boolean): String =
        if (selected) {
            try {
                getString(R.string.nav_tab_cd_selected, label, getString(R.string.nav_tab_state_selected))
            } catch (_: Exception) {
                label
            }
        } else {
            label
        }

    private fun showSettingsDialog() {
        // Phase B B3：比照 syncRemoveDialog 的 isFinishing/isDestroyed 守衛，
        // 避免後台/銷毀時 show() 拋 WindowManager.BadTokenException。
        if (isFinishing || isDestroyed) return
        try {
            settingsDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort
        }
        try {
            settingsDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.tab_settings))
                // Phase C: about copy fully resourced (was hardcoded English
                // "Version 0.0.1 ..." + "MIT ..."); version is dynamic.
                // Phase H P1：%4$s live 宣稱經 hookLiveProven 門控，無 boot-fresh 證據時
                // 用中性 engine_diag_unknown（既有鍵，不新增綠文案），不直嵌 daemon_running.
                .setMessage(
                    getString(
                        R.string.settings_about_format,
                        getString(R.string.hero_title_engine),
                        appVersionName(),
                        getString(R.string.settings_about_feature),
                        getString(if (hookLiveProven(vm.state.value)) R.string.status_hook_daemon_running else R.string.engine_diag_unknown),
                        getString(R.string.hook_mode_subtitle),
                        getString(R.string.settings_license),
                    ),
                )
                .setPositiveButton(getString(R.string.dialog_close), null)
                .setNeutralButton(getString(R.string.action_request_shizuku)) { _, _ ->
                    requestShizukuPermission()
                }
                .setNegativeButton(getString(R.string.action_export_diagnosis)) { _, _ ->
                    showDiagnosticExport()
                }
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "showSettingsDialog failed", e)
            settingsDialog = null
        }
    }

    // Phase C: dynamic app version for the settings about dialog (PackageInfo
    // at runtime, localized "unknown" fallback). No BuildConfig dependency.
    // Phase L L2：one-shot 快取（設定對話框非 hot path；首次 PM IPC 後快取，
    // 後續零 binder；失敗回退為本地化佔位，不快取以免語系切換殘留）。
    private var cachedAppVersionName: String? = null

    private fun appVersionName(): String {
        cachedAppVersionName?.let { return it }
        try {
            @Suppress("DEPRECATION")
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            if (!v.isNullOrBlank()) {
                cachedAppVersionName = v
                return v
            }
        } catch (_: Exception) {
            // Fall through to the localized placeholder.
        }
        return try {
            getString(R.string.settings_version_unknown)
        } catch (_: Exception) {
            "unknown"
        }
    }

    // Phase B B1/B2/B4：分身列表渲染。
    // B1：Switch 為 momentary action（彈回 checked，防視覺翻轉）＋門控
    // isEnabled = !loading && blockKey == null（呼叫端傳 render 已算好的 blockKey，
    // 與 clone/remove/batch 同源）＋48dp 觸控目標＋移除語意 contentDescription。
    // B2：ImageView 先設 tag=appKey；coroutine capture clonedGen，過期丟棄。
    // B4：指紋未變只同步 Switch enabled，不重建（比照 renderSourceList）。
    private fun renderClonedApps(s: UiState, blockKey: BlockReason?, catalogKey: Int) {
        if (!::clonedAppsContainer.isInitialized) return
        // Phase E E2：逐行成功宣稱哨兵。tag 顯隱由 Hook live 證據驅動（與徽章區同源
        // hookLiveProven，Phase H：existence 不足，需 boot-fresh），無 live 證據時
        // GONE，不得無條件宣稱注入成功。
        val hookLiveProven = hookLiveProven(s)
        // Phase L L2：catalogKey 由 render 入口一次算好傳入（與 dialog 共用），
        // 此處只 sync＋比對指紋，不再重算 O(catalog)。
        syncLabelCache(catalogKey)
        val key = buildClonedListKey(s, catalogKey)
        if (key == lastClonedListKey) {
            val writable = blockKey == null
            for (i in 0 until clonedAppsContainer.childCount) {
                val row = clonedAppsContainer.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    if (child is Switch) child.isEnabled = !s.loading && writable
                }
            }
            return
        }
        // 全量重建才推進世代：在途舊圖完成時比對過期丟棄綁定（快取保留）。
        val gen = ++clonedGen
        lastClonedListKey = key
        clonedAppsContainer.removeAllViews()
        if (s.cloned.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.cloned_empty)
                textSize = 13f
                setTextColor(getColor(R.color.oneui_text_secondary))
                val p = dp(12)
                setPadding(p, p, p, p)
            }
            clonedAppsContainer.addView(emptyView)
            return
        }
        val writable = blockKey == null
        // Phase F F1：catalogLabels 本輪預取（reloadCatalog IO 路徑已查好的 label 就地取用）。
        // Phase L L1：sync 已在指紋比對前完成，此處不再重算 hash／重 sync。
        val catalogLabels = catalogLabelsFor(s.sourceCatalog)
        for (pkg in s.cloned) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = dp(8)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(6)
                }
                background = getDrawable(R.drawable.bg_island_card_high)
            }

            // Left icon container with #2 badge overlay
            val iconContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
            val appIconView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(36)).apply {
                    gravity = Gravity.CENTER
                }
                // Phase C: the row title/switch already names the app; the icon
                // itself is decorative.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // Phase E E5：serial null 用不可碰撞 token "na"（"na:$pkg"），
            // 不得 ?: 0（與真 serial "0" 碰撞，跨列表重綁錯圖）。
            val appKey = "${s.inventory.targetSerial ?: "na"}:$pkg"
            appIconView.tag = appKey
            requestIcon(pkg, appKey, appIconView, gen)
            val badgeView = TextView(this).apply {
                // Phase G: WRAP_CONTENT + min 18dp (was fixed 18dp, clipped
                // cloned>=100 at 10sp); large counts grow instead of clipping.
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                }
                minimumWidth = dp(18)
                minimumHeight = dp(18)
                background = getDrawable(R.drawable.bg_dual_badge)
                // Phase C: badge number via resources (was hardcoded "2") with
                // theme token text (see cloneBtn); context description is set
                // below once the app label is resolved.
                // Phase F F1：tag 供非同步 label 回填定位（rebindClonedLabel）。
                tag = "badge:$pkg"
                text = getString(R.string.badge_number_format, 2)
                textSize = 10f
                setTextColor(getColor(R.color.oneui_button_text))
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            iconContainer.addView(appIconView)
            iconContainer.addView(badgeView)
            row.addView(iconContainer)

            // Center details
            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                    marginEnd = dp(8)
                }
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // Phase F F1：同步只讀快取，miss 先顯示 pkg 佔位（catalog 預取次之），
            // 真標籤經 requestLabelAsync 回填（比照 loadIcon）；主執行緒不打 PM binder。
            var label = resolveAppLabelCached(pkg)
            if (label == pkg) {
                catalogLabels[pkg]?.let {
                    label = it
                    cacheLabel(pkg, it)
                }
            }
            // Phase C: the "2" badge alone is meaningless to screen readers;
            // name whose second copy it marks.
            try {
                badgeView.contentDescription = getString(R.string.dual_badge_cd, label)
            } catch (_: Exception) {
                // Best effort: label context is enhancement-only.
            }
            val titleView = TextView(this).apply {
                // Phase F F1：tag 供非同步回填的 tag 檢查＋重綁定位。
                tag = pkg
                text = label
                textSize = 14f
                setTextColor(getColor(R.color.oneui_text_primary))
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val tagView = TextView(this).apply {
                text = getString(R.string.tag_forced_injected)
                // Phase E E2：見函數入口 hookLiveProven（無 live 證據 GONE）。
                visibility = if (hookLiveProven) View.VISIBLE else View.GONE
                textSize = 10f
                // Phase C: blue-pill text (see hookSecPmTag).
                setTextColor(getColor(R.color.oneui_on_primary_container))
                background = getDrawable(R.drawable.bg_pill_badge_blue)
                val hp = dp(6)
                val vp = dp(2)
                setPadding(hp, vp, hp, vp)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6)
                }
            }
            titleRow.addView(titleView)
            titleRow.addView(tagView)

            val subtitleView = TextView(this).apply {
                // Phase E E1：targetId 未知時只顯示 desc，不把 -1 格式化進文案。
                text = if (s.inventory.targetId > NO_TARGET_ID) {
                    getString(R.string.app_dual_unlocked_format, getString(R.string.app_dual_unlocked_desc), s.inventory.targetId)
                } else {
                    getString(R.string.app_dual_unlocked_desc)
                }
                textSize = 12f
                // Phase C: tint (light #046C4E on #DFE7F0 = 5.16; #059669 was
                // 3.02).
                setTextColor(getColor(R.color.oneui_secondary_tint))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textContainer.addView(titleRow)
            textContainer.addView(subtitleView)
            row.addView(textContainer)

            // Switch toggle: momentary removal action, always visually checked.
            // Toggling initiates safe two-phase removal (dialog), never a direct
            // on/off state flip; disabled while busy or channel unwritable.
            val toggleSwitch = Switch(this).apply {
                isChecked = true
                isEnabled = !s.loading && writable
                minimumHeight = dp(48)
                minimumWidth = dp(48)
                // Phase F F1：tag 供非同步 label 回填定位（rebindClonedLabel）。
                tag = "switch:$pkg"
                // Phase G: full-width colon via remove_row_cd (was hardcoded ":").
                contentDescription = getString(R.string.remove_row_cd, getString(R.string.action_remove), label)
                setOnClickListener { v ->
                    try {
                        (v as? Switch)?.isChecked = true
                    } catch (_: Exception) {
                        // Best effort: 彈回失敗不阻塞移除請求。
                    }
                    vm.requestRemove(pkg)
                }
            }
            row.addView(toggleSwitch)
            clonedAppsContainer.addView(row)
            // Phase F F1：快取＋catalog 皆 miss 才起 IO（首輪每列連續 binder 的來源）。
            // 回填＋重綁（tag/世代檢查，過期丟棄，快取保留，下次同步命中）。
            if (!isLabelCached(pkg)) {
                val rowGen = gen
                requestLabelAsync(pkg) { fresh ->
                    try {
                        if (rowGen != clonedGen) return@requestLabelAsync
                        if (titleView.tag != pkg) return@requestLabelAsync
                        if (!isUnderClonedContainer(titleView)) {
                            rebindClonedLabel(pkg, fresh)
                            return@requestLabelAsync
                        }
                        try {
                            titleView.text = fresh
                        } catch (_: Exception) {
                            rebindClonedLabel(pkg, fresh)
                            return@requestLabelAsync
                        }
                        try {
                            badgeView.contentDescription = getString(R.string.dual_badge_cd, fresh)
                        } catch (_: Exception) {
                            // Best effort.
                        }
                        try {
                            // Phase G: full-width colon via remove_row_cd.
                            toggleSwitch.contentDescription = getString(R.string.remove_row_cd, getString(R.string.action_remove), fresh)
                        } catch (_: Exception) {
                            // Best effort.
                        }
                    } catch (_: Exception) {
                        // Best effort.
                    }
                }
            }
        }
    }

    // Phase B B4：分身列表指紋（cloned 內容＋目標 serial＋targetId＋loading
    // ＋hook 哨兵態）。Phase E E4：納入 targetId（subtitle 含 targetId，
    // 未納入則目標切換留殘留舊值）；E2 哨兵態亦須入指紋（tag 顯隱由 hook
    // live 證據驅動，未納入則 hook 翻轉時 early-return 留下殘留 VISIBLE）。
    // Phase H：哨兵升格為 hookLiveProven（含 hookBootFresh，boot-fresh 落地
    // 翻轉時重建）。
    // Phase L L1：追加 catalogKey（labelCacheKey／catalog.hashCode 同源，呼叫端
    // 單次計算傳入，sync＋key 共用一次 hash）。catalog label 變化即重建，
    // 舊快取不再經 !isLabelCached 命中殘留；同內容同 hash，不致過度重建。
    private fun buildClonedListKey(s: UiState, catalogKey: Int): String {
        val hookLiveProven = hookLiveProven(s)
        return "${s.cloned.hashCode()}:${s.inventory.targetSerial}:${s.inventory.targetId}:${s.loading}:$hookLiveProven:$catalogKey"
    }

    // PR-6 P2 (#27 後半)：診斷匯出預覽。顯示已遮蔽可辨識資料的本機文字
    // (buildDiagnosticExport 經四道遮蔽：先郵件後包名後 IP，最後路徑，大小受限)；只有使用者
    // 主動點按才顯示，無自動上傳、無網路呼叫、無後台傳送。
    // P2-4 誠實文案：遮蔽仍非全面保證，標題與按鈕須明示「分享前請人工確認」，
    // 不得只寫「已遮蔽」使人誤信絕對乾淨。
    // P2-7：對話框另附複製已遮蔽內容按鈕（資源 R.string.diagnostic_copy_button，
    // 中英＋繁中；JVM 常數 DIAGNOSTIC_COPY_BUTTON_TEXT 僅為測試 seam，文案以資源為準）：
    // 使用者主動觸發才經 ClipboardManager 複製已遮蔽文本到本機剪貼簿；複製內容
    // 與預覽同字串 (diagnosticExportCopyText)，不做自動傳輸/share。
    private fun showDiagnosticExport() {
        // Phase B B3：比照 syncRemoveDialog 的 isFinishing/isDestroyed 守衛＋
        // try/catch 包 show()，避免後台/銷毀時 BadTokenException。
        if (isFinishing || isDestroyed) return
        val state = lastState ?: vm.state.value
        val preview = buildDiagnosticExport(state)
        try {
            exportDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: 舊預覽關不掉也不阻塞新預覽。
        }
        try {
            exportDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.diagnostic_export_title))
                .setMessage(preview)
                .setCancelable(true)
                .setNeutralButton(getString(R.string.diagnostic_copy_button)) { _, _ ->
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "galaxy-dual-diagnostic",
                                diagnosticExportCopyText(state),
                            ),
                        )
                        msgView.text = getString(R.string.diagnostic_copy_success)
                        msgView.setTextColor(msgColorFor(MsgKind.INFO))
                    } catch (e: Exception) {
                        msgView.text = getString(R.string.copy_failed, sanitizeForDisplay(e.message ?: "unknown"))
                        msgView.setTextColor(msgColorFor(MsgKind.CHANNEL))
                    }
                }
                .setPositiveButton(getString(R.string.dialog_close), null)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "showDiagnosticExport failed", e)
            exportDialog = null
        }
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    // p28-1 通道 RadioButton 佈局（窄螢幕安全）：HorizontalScrollView 量測為
    // UNSPECIFIED，weight 權重（0 寬）在橫滑容器內會塌陷為零寬；此處改
    // WRAP_CONTENT＋dp margins（不使用 weight），互斥語意不變（RadioGroup
    // 仍為滑動區唯一直屬子，四鈕仍為群組直屬；切換走 selectChannel，busy
    // 僅禁點按，恢復路徑不變）。
    private fun radioParams(): RadioGroup.LayoutParams =
        RadioGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            val d = resources.displayMetrics.density
            val m = (8 * d).toInt()
            leftMargin = m
            rightMargin = m
        }

    // p28-1 訊息語意色（亮/暗 values 資源；數值僅為對假設背景
    // #FFFFFF/#121212 的數學檢查（目標 4.5:1），不聲稱實際主題對比。
    // Manifest 未宣告 android:theme，實際 surface 未鎖定（完整主題為 p28-3 範圍）。
    // NONE 回預設文字色。實例方法：需 Context 解析夜間資源。
    private fun msgColorFor(kind: MsgKind): Int = when (kind) {
        MsgKind.NONE -> defaultMsgColor
        MsgKind.INFO -> getColor(R.color.msg_info)
        MsgKind.INVALID -> getColor(R.color.msg_invalid)
        MsgKind.NO_DUAL -> getColor(R.color.msg_no_dual)
        MsgKind.VERIFY -> getColor(R.color.msg_verify)
        MsgKind.CHANNEL -> getColor(R.color.msg_channel)
        MsgKind.FAILED -> getColor(R.color.msg_failed)
    }

    // Permission button: no binder -> single reconnect entry (probe keeps the
    // honest NONE state); authorized -> single reconnect entry (probe+refresh);
    // otherwise ask Shizuku. The button text mirrors the same boolean split in
    // updateShizukuLine so 文案/動作一致（重連分支為「檢查 Shizuku 並更新」，
    // 與重新連線並更新按鈕「重新連線並更新」區分；未授權分支為「請求 Shizuku 授權」）.
    // Phase I I4：binder IPC（pingBinder＋checkSelfPermission）搬到 Dispatchers.IO
    //（refresh 模式：IO 讀＋回主更新／分支）。主執行緒只做分支＋requestPermission＋讀快取重繪，
    // 永不直呼 pingBinder（讀經 readShizukuPresence 僅 IO 執行緒）。
    private fun requestShizukuPermission() {
        // Phase K K2：頭寫與 refresh 共用同一 shizukuPresenceJob 協調，先取消在途 refresh。
        shizukuPresenceJob?.cancel()
        // P1：發起時主線取值（見 shizukuGen），IO 回主線後比對過期丟棄。
        val my = ++shizukuGen
        // Phase K review：頭寫亦賦值回 shizukuPresenceJob（與 catalogJob 範式一致），
        // 使 refresh 側 cancel 能取消在途頭寫，避免 old-live 蓋 new-death。
        shizukuPresenceJob = lifecycleScope.launch {
            val presence = try {
                withContext(Dispatchers.IO) { readShizukuPresence() }
            } catch (_: Exception) {
                null
            }
            if (my != shizukuGen) return@launch
            if (presence != null) {
                try {
                    cachedShizukuNoBinder = presence.first
                    cachedShizukuAuthorized = presence.second
                } catch (_: Exception) {
                    // Best effort.
                }
            }
            try {
                val (noBinder, authorized) = presence ?: cachedShizukuPresence()
                if (noBinder) {
                    // P2-1: permission-button tap re-probes/refreshes but is NOT
                    // consent for privileged `cat` (always false).
                    vm.reconnectAndRefresh(allowPrivilegedHookDiagnosis = false)
                } else if (authorized) {
                    vm.reconnectAndRefresh(allowPrivilegedHookDiagnosis = false)
                } else {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                }
            } catch (e: Exception) {
                try {
                    vm.recheckPrivilege(allowPrivilegedHookDiagnosis = false)
                } catch (_: Exception) {
                    // Best effort.
                }
            }
            refreshShizukuPresenceCache()
            try {
                updateShizukuLine()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    // Shizuku binder/authorization line, read from volatile cache (Phase B B4):
    // pingBinder guards checkSelfPermission so a dead binder shows 未連線, not a crash.
    // P25: the permission button text follows the same boolean binder/
    // permission state (never parsed from the rendered line): 未連線/已授權 ->
    // "檢查 Shizuku 並更新" (reconnectAndRefresh, 與重新連線並更新按鈕的
    // 「重新連線並更新」區分 TalkBack/視覺）; 未授權 -> "請求 Shizuku 授權"
    // (requestPermission).
    // Phase B B4: render() 不再每次 pingBinder；binder 存活經 listener/onResume/
    // 授權回調 refreshShizukuPresenceCache 維護，render 只讀快取。
    private fun updateShizukuLine() {
        // Boolean binder/permission state first (single cached read): the
        // button branch below keys on these, never on state.contains().
        // Phase A A4：同一組 booleans 經 cachedShizukuPresence 單次讀取，同時驅動
        // 備援卡片標題/徽章（resolveShizukuCardStatus 純函數），卡片永不另行判讀。
        val (noBinder, authorized) = cachedShizukuPresence()
        val state: String = try {
            if (noBinder) {
                getString(R.string.shizuku_state_no_binder)
            } else if (authorized) {
                getString(R.string.shizuku_state_ok)
            } else {
                getString(R.string.shizuku_state_unauth)
            }
        } catch (e: Exception) {
            getString(R.string.shizuku_state_unknown, sanitizeForDisplay(e.message ?: "unknown"))
        }
        shizukuView.text = state
        try {
            permBtn.text = if (!noBinder && !authorized) {
                getString(R.string.action_request_shizuku)
            } else {
                getString(R.string.action_check_shizuku)
            }
        } catch (_: Exception) {
            // Best effort: updateShizukuLine may run before buildUi in tests.
        }
        // Phase A A4：備援卡片與狀態行同源（同一組 noBinder/authorized），不再寫死已授權。
        try {
            if (::shizukuFallbackDescView.isInitialized && ::shizukuFallbackBadgeView.isInitialized) {
                when (resolveShizukuCardStatus(noBinder, authorized)) {
                    ShizukuCardStatus.NO_BINDER -> {
                        shizukuFallbackDescView.text = getString(R.string.shizuku_state_no_binder)
                        shizukuFallbackBadgeView.text = getString(R.string.engine_fallback_nobinder)
                        shizukuFallbackBadgeView.background = getDrawable(R.drawable.bg_pill_badge_gray)
                        // Phase G: secondary (light #475569 on #DFE7F0 = 6.07;
                        // tertiary was 3.81, failed 4.5).
                        shizukuFallbackBadgeView.setTextColor(getColor(R.color.oneui_text_secondary))
                    }
                    ShizukuCardStatus.UNAUTHORIZED -> {
                        shizukuFallbackDescView.text = getString(R.string.shizuku_state_unauth)
                        shizukuFallbackBadgeView.text = getString(R.string.engine_fallback_unauth)
                        shizukuFallbackBadgeView.background = getDrawable(R.drawable.bg_pill_badge_amber)
                        shizukuFallbackBadgeView.setTextColor(getColor(R.color.oneui_warning_text))
                    }
                    ShizukuCardStatus.AUTHORIZED -> {
                        shizukuFallbackDescView.text = getString(R.string.shizuku_state_ok)
                        shizukuFallbackBadgeView.text = getString(R.string.engine_fallback_standby)
                        shizukuFallbackBadgeView.background = getDrawable(R.drawable.bg_pill_badge_amber)
                        // Phase G: secondary (light #475569 on #FEF3C7 = 6.81;
                        // tertiary was 4.27, failed 4.5).
                        shizukuFallbackBadgeView.setTextColor(getColor(R.color.oneui_text_secondary))
                    }
                }
            }
        } catch (_: Exception) {
            // Best effort: updateShizukuLine may run before buildUi in tests.
        }
    }

    // Phase A A4：Shizuku binder/授權單次 live 讀取。Phase F F3＋Phase I I4：僅 IO 執行緒呼叫
    //（經 refreshShizukuPresenceCache／requestShizukuPermission 內 withContext(IO)）；主執行緒永不直呼
    //（binder IPC：pingBinder＋checkSelfPermission）。
    private fun readShizukuPresence(): Pair<Boolean, Boolean> {
        val noBinder: Boolean = try { !Shizuku.pingBinder() } catch (_: Exception) { true }
        val authorized: Boolean = try {
            !noBinder && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        return noBinder to authorized
    }

    // Phase B B4：binder 快取寫入點（listener/onResume/授權回調/按鈕）。
    // render 只讀 cachedShizukuPresence，不直接 pingBinder。
    // Phase F F3：binder IPC 搬到 Dispatchers.IO（refresh 內 launch IO，結果回主執行緒
    // 更新快取＋重繪狀態行）。呼叫端當幀的 updateShizukuLine 讀舊快取，IO 回來再刷一次。
    private fun refreshShizukuPresenceCache() {
        try {
            // Phase K K2：比照 catalogJob（3536-3537）取消前一個在途任務，避免疊加
            //（old-live 蓋 new-death）；與 requestShizukuPermission 頭寫共用同一 job。
            shizukuPresenceJob?.cancel()
            // P1：發起時主線取值（見 shizukuGen），IO 回主線後比對過期丟棄。
            val my = ++shizukuGen
            shizukuPresenceJob = lifecycleScope.launch {
                val next = withContext(Dispatchers.IO) {
                    try {
                        readShizukuPresence()
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@launch
                if (my != shizukuGen) return@launch
                try {
                    cachedShizukuNoBinder = next.first
                    cachedShizukuAuthorized = next.second
                } catch (_: Exception) {
                    return@launch
                }
                try {
                    updateShizukuLine()
                } catch (_: Exception) {
                    // Best effort: 容器未就緒（測試路徑）時不阻塞快取。
                }
            }
        } catch (_: Exception) {
            // Best effort: 快取失敗時讀路徑回保守預設（見 cachedShizukuPresence）。
        }
    }

    // Phase F F3：render 只讀快取，不做 live binder。快取未就緒（首幀 IO 未回）回保守
    // 預設（noBinder=true＝未連線），待 refresh 回填後重繪；不再主執行緒 pingBinder。
    private fun cachedShizukuPresence(): Pair<Boolean, Boolean> {
        val cached = cachedShizukuNoBinder
        return if (cached != null) {
            cached to cachedShizukuAuthorized
        } else {
            true to false
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
        // (cheap cached read inside updateShizukuLine, already try/catch
        // guarded for the pre-buildUi test path; live binder reads only happen
        // on Dispatchers.IO in refreshShizukuPresenceCache on binder/onResume/
        // permission paths, Phase F F3).
        // Double-guarded here so a Shizuku-less test harness calling render
        // can never crash on it.
        // PR-6 P2: 同時記住最近一次渲染狀態 (診斷展開/匯出共用)。
        lastState = s
        try {
            updateShizukuLine()
        } catch (_: Exception) {
            // Best effort: the state render must never depend on Shizuku.
        }
        // Phase B B1：寫入門控單一來源（busy 時 null＝不可寫語意由 enabled 式承載）。
        // renderClonedApps 的 Switch 與稍後按鈕/dialog 共用此 blockKey，不重算。
        val busy = s.loading
        // 保留 BlockReason 原鍵以計算穩定去重鍵（writeBlockDedupKey）；
        // 可見文字為完整 blockReason（含時間）：顯示比完整文字，播報比去重鍵。
        val blockKey = if (busy) null else writeBlockReason(s)
        // Phase L L2：catalogKey render 入口算一次，傳給 renderClonedApps 與
        // syncRemoveDialog 共用（各入口不再各自重算 O(catalog)）。
        val catalogKey = labelCacheKey()
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
        privilegeView.text = getString(
            R.string.privilege_line,
            s.execIdentity,
            s.resolvedGateway,
            readyMark,
            s.privilege,
            scopeLabel,
            probeMark,
            pmMark,
        )
        // Title mirrors the actual execution identity (not the routed
        // summary) for quick manual verification.
        // Phase C: Recents title fully resourced (was hardcoded English
        // "GalaxyDual Plus (...)"); product name stays English by-design
        // (matches the manifest label).
        title = try {
            getString(R.string.recents_title_format, getString(R.string.app_name), s.execIdentity, s.resolvedGateway)
        } catch (_: Exception) {
            // Phase G: fallback also resourced (was hardcoded English format);
            // app_name is the localized product name.
            try {
                "${getString(R.string.app_name)} (${s.execIdentity}/${s.resolvedGateway})"
            } catch (_: Exception) {
                "Galaxy Dual Plus (${s.execIdentity}/${s.resolvedGateway})"
            }
        }
        // Picker state: persisted preference + actual resolved gateway
        // (restart remembers the preference via SharedPreferences; see
        // factory). Always shown so the user can tell which channel the
        // lists/privilege above were produced with. The Auto degrade note
        // is intentionally persistent here (it survives msg clears), but
        // only as a short tag: the full note already rides in msg (INFO),
        // so appending it in full would wash the status line every render.
        // p28-1: the RadioButtons above carry the checked state themselves;
        // this line only names preference vs resolved (不再是唯一選擇依據）.
        channelView.text = if (s.degrade == null) {
            getString(R.string.channel_line, s.channel, s.resolvedGateway)
        } else {
            getString(R.string.channel_line_degraded, s.channel, s.resolvedGateway)
        }
        // Hook signal: LSPosed mode only. ACTIVE shows the recorded scopes as
        // a historical marker (existence evidence only, boot freshness
        // unconfirmed); INACTIVE shows 未偵測到 plus the enable hint; UNKNOWN shows
        // 狀態未知 (P1: unreadable / no diagnostic path is never reported
        // as "not detected") plus the true-channel hint. The placeholder
        // note stays visible so the mode is never mistaken for a working
        // channel. P4: 時間證據經 hookEvidence()+timeEvidenceText 統一
        // (狀態文案維持本頁提示，不動)。
        // Phase L L3：顯示行走 EN 分支（null 判定語意不變，另兩處 null 檢查沿用預設）。
        val hookText = renderHookText(s, isEnglishLocale())
        if (hookText != null) {
            hookView.visibility = View.VISIBLE
            hookView.text = hookText
            // LSPOSED 模式：手動診斷入口與可見同意說明同顯；按鈕 enable 仍走
            // 共用 busy 門控（下方 hookDiagBtn.isEnabled = !busy，不在此強制啟用）。
            try {
                hookRow.visibility = View.VISIBLE
                hookDiagHint.visibility = View.VISIBLE
            } catch (_: Exception) {
                // Best effort: render may run before buildUi in tests.
            }
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
            getString(R.string.profiles_empty)
        } else {
            getString(
                R.string.profiles_filled,
                s.profiles.size,
                s.profiles.joinToString("\n") { "  #${it.userId} ${it.label}" },
            )
        }
        clonedView.text = if (s.cloned.isEmpty()) {
            getString(R.string.cloned_empty)
        } else {
            getString(
                R.string.cloned_filled,
                s.cloned.size,
                s.cloned.joinToString("\n") { "  $it" },
            )
        }
        // PR-6 P2 (#27 後半)：清單新鮮度行 (STALE/UNKNOWN 明確標示，
        // FRESH 顯示目標/筆數/查詢時間)。P4：上次操作相位拆至獨立 lastOpView
        // 行 (renderLastOpText)，此處只剩清單；查詢時間經 formatTimeMs。
        // Phase L L3：EN 分支（diag/lastOp 仍繁中，為 P2 餘項）。
        inventoryView.text = renderInventoryText(s, isEnglishLocale())
        lastOpView.text = renderLastOpText(s)
        // PR-6 P2 (#27 後半)：診斷事件行。穩定碼 + 繁中摘要/下一步常顯；
        // 點一下展開 exit/技術 detail (大小受限)。無事件時顯示就緒。
        // P4 a11y：同步更新 contentDescription (含展開狀態)。
        diagView.text = renderDiagnoseText(s, diagExpanded)
        diagView.contentDescription = renderDiagnoseDescription(s, diagExpanded)

        // One UI 6 Modern Views dynamic update
        // Phase B B4：分身列表移出此 try（獨立指紋＋獨立 try/catch，下見），
        // 此段只留計數/狀態徽章；空吞改為 Log.w（測試環境外須可見）。
        try {
            if (::clonedCountStatView.isInitialized) {
                clonedCountStatView.text = getString(R.string.stats_injected_format, s.cloned.size, getString(R.string.stats_unit_apps))
            }
            if (::clonedCountBadge.isInitialized) {
                clonedCountBadge.text = getString(R.string.badge_number_format, s.cloned.size)
            }
            if (::engineUidSlotView.isInitialized) {
                // Phase E E1：targetId 未知（NO_TARGET_ID=-1）時顯示未知佔位，
                // 不得將 -1 格式化進 UID 文案（外洩＋TalkBack 誤讀）；a11y P0。
                engineUidSlotView.text = if (s.inventory.targetId > NO_TARGET_ID) {
                    getString(R.string.engine_uid_label, s.inventory.targetId)
                } else {
                    getString(R.string.engine_uid_unknown)
                }
            }
            // Phase A A1：Hero 第三格由 hookStatus 驅動；非 LSPOSED 通道整格隱藏
            // （對齊 renderHookText 回 null 條件：channel != LSPOSED）。
            if (::hookStatusCell.isInitialized && ::hookStatusDot.isInitialized && ::hookStatusText.isInitialized) {
                if (renderHookText(s) == null) {
                    hookStatusCell.visibility = View.GONE
                } else {
                    hookStatusCell.visibility = View.VISIBLE
                    // Phase H：ACTIVE 僅歷史 existence 證據；綠＋正常監聽只給
                    // boot-fresh 證實 (hookLiveDisplay LIVE)，歷史標記走 UNKNOWN。
                    when (hookLiveDisplay(s)) {
                        HookLiveDisplay.LIVE -> {
                            hookStatusDot.setImageDrawable(getDrawable(R.drawable.ic_dot_green))
                            hookStatusText.text = getString(R.string.stats_hook_active)
                            // Phase C: tint (see subtitleView).
                            hookStatusText.setTextColor(getColor(R.color.oneui_secondary_tint))
                        }
                        HookLiveDisplay.INACTIVE -> {
                            hookStatusDot.setImageDrawable(getDrawable(R.drawable.ic_dot_gray))
                            hookStatusText.text = getString(R.string.stats_hook_inactive)
                            hookStatusText.setTextColor(getColor(R.color.oneui_text_tertiary))
                        }
                        HookLiveDisplay.UNKNOWN -> {
                            hookStatusDot.setImageDrawable(getDrawable(R.drawable.ic_dot_amber))
                            hookStatusText.text = getString(R.string.stats_hook_unknown)
                            hookStatusText.setTextColor(getColor(R.color.oneui_warning_text))
                        }
                    }
                }
            }
            // Phase A A2：statusPill 誠實化。LSPosed 佔位永為唯讀（writeBlockReason
            // 恆非 null），只給灰＋佔位文案，永不給綠；ROOT/SHIZUKU 綠色只給
            // writeBlockReason(s) == null（真可寫）的通道；其餘走 amber 中性。
            if (::statusPillText.isInitialized && ::statusPillDot.isInitialized && ::statusPillContainer.isInitialized) {
                when (s.resolvedGateway) {
                    ResolvedChannel.LSPOSED_PLACEHOLDER -> {
                        statusPillText.text = getString(R.string.status_pill_lsposed_placeholder)
                        statusPillDot.setImageDrawable(getDrawable(R.drawable.ic_dot_gray))
                        statusPillDot.visibility = View.VISIBLE
                        statusPillContainer.background = getDrawable(R.drawable.bg_pill_badge_gray)
                        // Phase G: secondary (light #475569 on #DFE7F0 = 6.07;
                        // tertiary was 3.81, failed 4.5).
                        statusPillText.setTextColor(getColor(R.color.oneui_text_secondary))
                    }
                    ResolvedChannel.ROOT -> {
                        val writable = writeBlockReason(s) == null
                        statusPillText.text = getString(R.string.channel_root)
                        statusPillDot.setImageDrawable(
                            getDrawable(if (writable) R.drawable.ic_dot_green else R.drawable.ic_dot_amber),
                        )
                        statusPillDot.visibility = View.VISIBLE
                        statusPillContainer.background = getDrawable(
                            if (writable) R.drawable.bg_pill_badge_green else R.drawable.bg_pill_badge_amber,
                        )
                        statusPillText.setTextColor(
                            // Phase C: writable green text uses the tint (see hookBadgeChip).
                            getColor(if (writable) R.color.oneui_secondary_tint else R.color.oneui_warning_text),
                        )
                    }
                    ResolvedChannel.SHIZUKU -> {
                        val writable = writeBlockReason(s) == null
                        statusPillText.text = getString(R.string.channel_shizuku)
                        statusPillDot.setImageDrawable(
                            getDrawable(if (writable) R.drawable.ic_dot_green else R.drawable.ic_dot_amber),
                        )
                        statusPillDot.visibility = View.VISIBLE
                        statusPillContainer.background = getDrawable(
                            if (writable) R.drawable.bg_pill_badge_green else R.drawable.bg_pill_badge_amber,
                        )
                        statusPillText.setTextColor(
                            // Phase C: writable green text uses the tint (see hookBadgeChip).
                            getColor(if (writable) R.color.oneui_secondary_tint else R.color.oneui_warning_text),
                        )
                    }
                    else -> {
                        statusPillText.text = if (s.channel == ChannelPreference.AUTO) getString(R.string.channel_auto) else getString(R.string.status_ready)
                        statusPillDot.setImageDrawable(getDrawable(R.drawable.ic_dot_amber))
                        statusPillDot.visibility = View.VISIBLE
                        statusPillContainer.background = getDrawable(R.drawable.bg_pill_badge_amber)
                        statusPillText.setTextColor(getColor(R.color.oneui_warning_text))
                    }
                }
            }
            // Phase A A3：成功宣稱類徽章一律由 Hook live 信號驅動；僅
            // hookLiveProven (LSPOSED + ACTIVE + boot-fresh) 顯示，無 live 證據
            // 前隱藏，不再無條件全綠。Phase H：existence (ACTIVE 歷史標記) 不足，
            // daemonFooter 門控條件同升格 (原 ACTIVE 即顯，現需 live)。
            val hookLiveProven = hookLiveProven(s)
            if (::hookBadgeChip.isInitialized) {
                hookBadgeChip.visibility = if (hookLiveProven) View.VISIBLE else View.GONE
            }
            if (::hookSecPmTag.isInitialized) {
                hookSecPmTag.visibility = if (hookLiveProven) View.VISIBLE else View.GONE
            }
            if (::hookUidSandboxTag.isInitialized) {
                hookUidSandboxTag.visibility = if (hookLiveProven) View.VISIBLE else View.GONE
            }
            if (::injectedActiveLabel.isInitialized) {
                injectedActiveLabel.visibility = if (hookLiveProven) View.VISIBLE else View.GONE
            }
            if (::daemonFooter.isInitialized) {
                daemonFooter.visibility = if (hookLiveProven) View.VISIBLE else View.GONE
            }
            if (::residentBadge.isInitialized) {
                residentBadge.visibility = if (hookLiveProven) View.VISIBLE else View.GONE
            }
            if (::telemetryDot.isInitialized && ::telemetryStatusView.isInitialized) {
                if (renderHookText(s) == null) {
                    telemetryDot.setImageDrawable(getDrawable(R.drawable.ic_dot_gray))
                    telemetryStatusView.text = getString(R.string.telemetry_status_unknown)
                } else {
                    // Phase H：ACTIVE 綠＋運作中·已注入只給 boot-fresh 證實；
                    // 歷史標記走 UNKNOWN (amber＋狀態未知，永不給綠)。
                    when (hookLiveDisplay(s)) {
                        HookLiveDisplay.LIVE -> {
                            telemetryDot.setImageDrawable(getDrawable(R.drawable.ic_dot_green))
                            telemetryStatusView.text = getString(R.string.telemetry_status_active)
                        }
                        HookLiveDisplay.INACTIVE -> {
                            telemetryDot.setImageDrawable(getDrawable(R.drawable.ic_dot_gray))
                            telemetryStatusView.text = getString(R.string.telemetry_status_inactive)
                        }
                        HookLiveDisplay.UNKNOWN -> {
                            telemetryDot.setImageDrawable(getDrawable(R.drawable.ic_dot_amber))
                            telemetryStatusView.text = getString(R.string.telemetry_status_unknown)
                        }
                    }
                }
            }
            if (::checklistSummaryView.isInitialized) {
                // Phase H：5/5 模組運作正常只給 live 證實；ACTIVE 歷史標記走 unknown。
                checklistSummaryView.text = if (hookLiveProven) {
                    getString(R.string.engine_diag_all_normal)
                } else {
                    getString(R.string.engine_diag_unknown)
                }
            }
            for (badge in checklistItemBadges) {
                badge.visibility = if (hookLiveProven) View.VISIBLE else View.GONE
            }
            // Phase G: checklist icons are build-time green/blue; without Hook
            // live proof they must not signal success -- gray them via tertiary.
            // Tertiary as icon (non-text, 3:1) is 5.19 on island #E8EEF5.
            // Phase H: 門控由 existence 升格為 hookLiveProven。
            for (i in checklistItemIcons.indices) {
                try {
                    checklistItemIcons[i].setColorFilter(
                        getColor(
                            if (hookLiveProven) {
                                if (checklistItemIsGreen.getOrElse(i) { true }) R.color.oneui_secondary else R.color.oneui_primary
                            } else {
                                R.color.oneui_text_tertiary
                            },
                        ),
                    )
                } catch (_: Exception) {
                    // Best effort: render may run before buildUi in tests.
                }
            }
            try {
                engineUidIcon?.setColorFilter(
                    getColor(if (hookLiveProven) R.color.oneui_secondary else R.color.oneui_text_tertiary),
                )
            } catch (_: Exception) {
                // Best effort: render may run before buildUi in tests.
            }
        } catch (e: Exception) {
            Log.w(TAG, "render OneUI badges failed", e)
        }
        // Phase B B1/B4：分身列表獨立渲染（指紋差量＋Switch 寫入門控 blockKey）。
        // 移出上段 try：列表失敗不吞徽章更新，徽章失敗不吞列表。
        try {
            renderClonedApps(s, blockKey, catalogKey)
        } catch (e: Exception) {
            Log.w(TAG, "renderClonedApps failed", e)
        }
        // Writes queue in the ViewModel anyway; disabling while busy keeps
        // the read-only placeholder visibly read-only. Channel switches go
        // through the same mutex (selectChannel latest-wins), so the picker
        // is gated on busy only (never on writability: switching is the
        // recovery path out of an unwritable channel).
        // PR-37 P3 按鈕狀態：busy 時勾選相關一律禁用（批次執行中不可改勾選）；
        // batch 需非 busy 且有勾選；保存需 verified target 可用（見 E6，
        // 與 batchEnabled 同源 verifiedTargetAvailable）。
        // p28-1 enabled 反映可寫能力：clone/remove/batch 另須可寫
        // (writeBlockReason == null)；禁用原因由 writeBlockView 顯示
        // （VM 拒絕理由 single source，VM guard 保留在 isWritable 內）。
        // P2-2：clone/remove 另須手動輸入可用（非空且就地驗證通過，
        // 見 manualInputActionable；VM 空白/超長/非法仍 fail-closed 拒絕，
        // 此處只做 UI 層即時門控）；batch 另須目前 verified target 可用
        // （inventory.targetId 有效，與 cloneSelected 起始 pin fail-closed
        // 一致；VM 側 fetchDualTarget 守衛保留為縱深）。
        // （busy/blockKey 已於 render 入口計算，此處重用，不重算。）
        // p28-1 互斥單選同步：checked 即選擇狀態（視覺 + TalkBack）。
        autoBtn.isChecked = (s.channel == ChannelPreference.AUTO)
        rootBtn.isChecked = (s.channel == ChannelPreference.ROOT)
        shizukuBtn.isChecked = (s.channel == ChannelPreference.SHIZUKU)
        lsposedBtn.isChecked = (s.channel == ChannelPreference.LSPOSED)
        val blockReason = blockKey?.let { resolveBlockReason(it) }
        val blockDedupKey = writeBlockDedupKey(blockKey)
        // P2-2 輕量讀取：buildUi 前 render（測試路徑）視為不可用（fail-closed）。
        val manualActionable = try {
            if (!::pkgInput.isInitialized) false else manualInputActionable(pkgInput.text?.toString().orEmpty())
        } catch (_: Exception) {
            false
        }
        cloneBtn.isEnabled = cloneRemoveEnabled(s, manualActionable)
        removeBtn.isEnabled = cloneRemoveEnabled(s, manualActionable)
        // 空白禁用可感知理由（VM 守衛不動，僅 UI 層提示；通道阻塞/忙碌由 writeBlockView/loading 承載）。
        syncBlankInputA11y(s)
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
        // Phase E E6：save 門對齊 verifiedTargetAvailable（targetId>0 且
        // serial 非 null，與 batch 同源）；VM persistSelection 守衛不動。
        saveSelBtn.isEnabled = !busy && verifiedTargetAvailable(s)
        cancelSaveBtn.isEnabled = !busy
        batchBtn.isEnabled = batchEnabled(s)
        // Stage4 a11y：busy 時搜尋框禁用，避免 busy 期間輸入觸發無謂重渲染。
        searchInput.isEnabled = !busy
        // p28-1 禁用原因行：只在非 busy 且不可寫時顯示 VM 拒絕理由。
        // P2-1 去重播報：同一拒絕理由只由單一節點播報（傾向 msgView）。
        // 顯示新鮮度與播報去重分離：顯示比完整文字（時間戳僅變亦刷新 setText，
        // 避免可見時間證據陳舊）；播報僅比穩定去重鍵（writeBlockDedupKey +
        // 跨節點同文 isWriteBlockDuplicate）控制 liveRegion，不阻止顯示刷新。
        // B1：同文判定去時間（見 isWriteBlockDuplicate/stripWriteBlockTimeParams），
        // 解決 LSPosed displayText 時間戳漂移導致恆 false；可見文字不去時間。
        val duplicate = isWriteBlockDuplicate(blockReason, s.msg, busy)
        val writeAnnounce = shouldAnnounceWriteBlock(lastWriteBlockDedupKey, blockDedupKey, duplicate)
        try {
            writeBlockView.accessibilityLiveRegion =
                if (writeAnnounce) View.ACCESSIBILITY_LIVE_REGION_POLITE
                else View.ACCESSIBILITY_LIVE_REGION_NONE
        } catch (_: Exception) {
            // Best effort: 無障礙屬性失敗不影響顯示。
        }
        if (blockReason != null) {
            writeBlockView.visibility = View.VISIBLE
            // 顯示：快取（完整文字）+ View 實際文字雙重比對；旋轉重建 View 為空時
            // 以 View 為準補寫。時間戳僅變（完整不同、去重鍵同）仍刷新顯示，
            // 播報已由上式 liveRegion 抑制，不跳過 setText。
            val writeViewCurrent = try {
                writeBlockView.text.toString()
            } catch (_: Exception) {
                null
            }
            if (shouldUpdateWriteBlockText(lastWriteBlockText, blockReason) || writeViewCurrent != blockReason) {
                if (writeViewCurrent != blockReason) {
                    try {
                        writeBlockView.text = blockReason
                    } catch (_: Exception) {
                        // Best effort: render may run before buildUi in tests.
                    }
                }
                lastWriteBlockText = blockReason
                lastWriteBlockDedupKey = blockDedupKey
            }
        } else {
            if (lastWriteBlockText != null || lastWriteBlockDedupKey != null) {
                writeBlockView.visibility = View.GONE
                writeBlockView.text = ""
                lastWriteBlockText = null
                lastWriteBlockDedupKey = null
            } else {
                writeBlockView.visibility = View.GONE
                try {
                    if (writeBlockView.text.toString() != "") writeBlockView.text = ""
                } catch (_: Exception) {
                    // Best effort: render may run before buildUi in tests.
                }
            }
        }
        // PR-7 P2 (#26):catalog 區渲染 (搜尋/勾選/批次按鈕計數/逐項結果)。
        // PR-37 P3 差量：列表只在指紋變化時全量重建；其餘文字區各自更新。
        renderSourceList(s)
        // 與 writeBlockView 同式：顯示比完整文字（時間戳僅變亦刷新，避免陳舊），
        // 播報僅比去時間穩定鍵控制 liveRegion，不阻止顯示刷新。
        // 快取 + View 實際文字雙重比對；旋轉重建以 View 為準補寫。
        val newMsgText = if (busy) {
            getString(R.string.status_loading)
        } else {
            s.msg.ifEmpty { getString(R.string.status_ready) }
        }
        val msgViewCurrent = try {
            msgView.text.toString()
        } catch (_: Exception) {
            null
        }
        val lastMsgStripped = lastMsgText?.let { stripWriteBlockTimeParams(it) }
        val newMsgStripped = stripWriteBlockTimeParams(newMsgText)
        val msgAnnounce = shouldAnnounceMsg(lastMsgStripped, newMsgStripped)
        try {
            msgView.accessibilityLiveRegion =
                if (msgAnnounce) View.ACCESSIBILITY_LIVE_REGION_POLITE
                else View.ACCESSIBILITY_LIVE_REGION_NONE
        } catch (_: Exception) {
            // Best effort: 無障礙屬性失敗不影響顯示。
        }
        if (shouldUpdateMsgText(lastMsgText, newMsgText) || msgViewCurrent != newMsgText) {
            if (msgViewCurrent != newMsgText) {
                try {
                    msgView.text = newMsgText
                } catch (_: Exception) {
                    // Best effort: render may run before buildUi in tests.
                }
            }
            lastMsgText = newMsgText
        } else {
            lastMsgText = newMsgText
        }
        msgView.setTextColor(if (busy || (s.msg.isEmpty() && !busy)) defaultMsgColor else msgColorFor(s.msgKind))
        // Two-phase remove (PR-4 P1, issue #23): the dialog is state-driven.
        // A config rotation re-collects the same pendingRemove from the
        // retained ViewModel and re-shows the dialog; no click is re-sent.
        // Phase B B1：confirm 門控同 Switch（loading＋ack＋可寫 blockKey==null）。
        syncRemoveDialog(s.pendingRemove, s.loading, blockKey, catalogKey)
    }

    // P2-2 手動輸入即時門控（TextWatcher 輕量路徑）：只更新 clone/remove/
    // batch 的 enabled，不做全量 render（避免每鍵重建列表）。判定走同一純函數
    // cloneRemoveEnabled/batchEnabled（見檔案尾），與 render 一致；
    // VM 空白/超長/非法仍 fail-closed 拒絕，此處只做 UI 層即時禁用。
    // B1：讀 vm.state.value 最新狀態（不用 stale lastState）；busy 一律禁用三鈕，
    // 打字競態不得繞過 busy。
    private fun refreshManualEnablement() {
        try {
            if (!::cloneBtn.isInitialized || !::removeBtn.isInitialized ||
                !::batchBtn.isInitialized || !::pkgInput.isInitialized
            ) {
                return
            }
            val s = try {
                vm.state.value
            } catch (_: Exception) {
                return
            }
            if (s.loading) {
                cloneBtn.isEnabled = false
                removeBtn.isEnabled = false
                batchBtn.isEnabled = false
                syncBlankInputA11y(s)
                return
            }
            val manual = try {
                manualInputActionable(pkgInput.text?.toString().orEmpty())
            } catch (_: Exception) {
                false
            }
            cloneBtn.isEnabled = cloneRemoveEnabled(s, manual)
            removeBtn.isEnabled = cloneRemoveEnabled(s, manual)
            batchBtn.isEnabled = batchEnabled(s)
            // 空白禁用可感知理由（VM 守衛不動，僅 UI 層提示）。
            syncBlankInputA11y(s)
        } catch (_: Exception) {
            // Best effort: 輸入門控失敗不影響驗證顯示。
        }
    }

    // 空白禁用可感知理由（VM 守衛不動，僅 UI 層提示）：空白且非 busy 且通道可寫時，
    // clone/remove 禁用的唯一原因是輸入空白，給 pkgInput 可見 error＋contentDescription，
    // 使禁用原因可感知；通道阻塞/忙碌時禁用原因由 writeBlockView/loading 承載，不疊加。
    // 非法/超長的就地 error 由 TextWatcher 負責，此處不覆寫；有效輸入或阻塞/忙碌時清除
    // 空白專用提示（僅當目前 error 正是該提示才清除，避免误删就地驗證）。
    private fun syncBlankInputA11y(s: UiState) {
        try {
            if (!::pkgInput.isInitialized) return
            val raw = try {
                pkgInput.text?.toString().orEmpty()
            } catch (_: Exception) {
                return
            }
            val isBlank = raw.trim().isEmpty()
            val writeBlocked = try {
                writeBlockReason(s) != null
            } catch (_: Exception) {
                true
            }
            val wantBlankReason = shouldShowBlankInputReason(isBlank, s.loading, writeBlocked)
            val required = try {
                getString(R.string.pkg_input_required)
            } catch (_: Exception) {
                null
            } ?: return
            if (wantBlankReason) {
                try {
                    if (pkgInput.error?.toString() != required) pkgInput.error = required
                } catch (_: Exception) {
                    // Best effort.
                }
                try {
                    pkgInput.contentDescription = required
                } catch (_: Exception) {
                    // Best effort.
                }
            } else {
                try {
                    if (pkgInput.error?.toString() == required) pkgInput.error = null
                } catch (_: Exception) {
                    // Best effort.
                }
                try {
                    if (pkgInput.contentDescription?.toString() == required) pkgInput.contentDescription = null
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        } catch (_: Exception) {
            // Best effort: 無障礙提示失敗不影響門控。
        }
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
    // Phase B B1：confirm 另須通道可寫（blockKey == null，與 Switch/按鈕同源；
    // busy 時 blockKey 為 null 但 loading 仍禁用，不可繞過）。
    private fun syncRemoveDialog(pending: PendingRemove?, loading: Boolean, blockKey: BlockReason?, catalogKey: Int) {
        if (isFinishing || isDestroyed) return
        if (pending == null) {
            removeLabelsDialog?.dismiss()
            removeLabelsDialog = null
            try {
                removeConfirmDialog?.dismiss()
            } catch (_: Exception) {
                // Best effort: the window may already be gone.
            }
            removeConfirmDialog = null
            shownPending = null
            // 無待確認即無世代可綁定：勾選與世代一併清除，避免殘留污染下一世代。
            removeAcknowledged = false
            acknowledgedGeneration = null
            return
        }
        if (removeConfirmDialog?.isShowing == true && shownPending == pending) {
            removeConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = canConfirmRemove(loading, removeAcknowledged, blockKey == null)
            removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !loading
            removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !loading
            // 在途不可取消，關閉 cancel 窗的使用者路徑；程式化 cancel 由 repo isStillValid 兜底。
            removeConfirmDialog?.setCancelable(!loading)
            removeConfirmDialog?.setCanceledOnTouchOutside(!loading)
            return
        }
        try {
            removeLabelsDialog?.dismiss()
            removeLabelsDialog = null
            removeConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort.
        }
        shownPending = pending
        // 勾選綁定世代保留：同世代（Neutral 未解散/旋轉重建）保留勾選，跨世代重置。
        // Neutral 走 null listener＋手動 OnClick（下），不解散父對話框，故不經此重置。
        if (acknowledgedGeneration != pending.generation) {
            removeAcknowledged = false
            acknowledgedGeneration = null
        }
        // Phase F F1/F2：catalogKey 由 render 入口傳入（與 renderClonedApps 共用，
        // 不再此處重算）；同步只讀快取＋catalog 預取，
        // miss 顯示 pkg 佔位，dialog 顯示後非同步回填（同 pending 仍在屏才更新）。
        // 主執行緒不打 PM binder（原每 show 一次連續 binder）。
        syncLabelCache(catalogKey)
        var appLabel = resolveAppLabelCached(pending.pkg)
        if (appLabel == pending.pkg) {
            val prefilled = try {
                catalogLabelsFor(vm.state.value.sourceCatalog)[pending.pkg]
            } catch (_: Exception) {
                null
            }
            if (prefilled != null) {
                appLabel = prefilled
                cacheLabel(pending.pkg, prefilled)
            }
        }
        // 破壞性確認顯示原文以供核對，匯出才遮蔽（by-design 例外）：
        // 本地確認畫面顯示完整 package + user/serial，標籤則有明示的上限，
        // 不經 sanitizeForDisplay/redact，否則使用者無法核對卸載目標；
        // 診斷匯出/日誌仍經四道遮蔽（見 buildDiagnosticExport）。
        val message = buildRemoveConfirmMessage(
            template = getString(R.string.remove_confirm_message),
            pkg = pending.pkg,
            appLabel = appLabel,
            profileLabel = pending.profileLabel,
            dualId = pending.dualId,
            serial = pending.dualSerial,
        )
        val acknowledgement = CheckBox(this).apply {
            setText(R.string.remove_confirm_acknowledge)
            // 窄螢幕可見可操作：24px 改 dp 換算＋WRAP_CONTENT＋最小觸控高 48dp。
            val d = resources.displayMetrics.density
            val hPad = (24 * d).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = (48 * d).toInt()
            // 同世代重建（旋轉）恢復勾選；跨世代已在上重置為 false。
            isChecked = removeAcknowledged
        }
        try {
            removeConfirmDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.remove_confirm_title))
                .setMessage(message)
                .setView(acknowledgement)
                // Builder 僅有 setCancelable（無 setCanceledOnTouchOutside），touch-outside 由 show 後 dialog 層再設。
                .setCancelable(!loading)
                .setPositiveButton(getString(R.string.remove_confirm_positive), null)
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .setNeutralButton(getString(R.string.remove_confirm_view_labels), null)
                .setOnCancelListener { vm.cancelRemove(pending) }
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
        // Neutral（標籤詳情）同式：null listener＋手動 OnClick 只展開子對話框，
        // 不解散父確認框、不重置勾選（勾選綁定世代保留）。
        acknowledgement.setOnCheckedChangeListener { _, checked ->
            removeAcknowledged = checked
            acknowledgedGeneration = if (checked) pending.generation else null
            // 確定性路徑：用本次 sync 傳入的 loading/blockKey（render 每幀刷新，
            // early-return 路徑 3176 與初始值 3293 同源），不即時重算 live 狀態，
            // 避免背景任務（init probe/catalog 刷新）中途翻轉造成按鈕閃爍／測試
            // flaky；loading 變化由下一次 render 的 early-return 同步，真正寫入
            // 門控在 Positive 點擊即時重讀＋VM confirmRemove isWritable() 雙層
            // fail-closed，此處僅控按鈕顯示。
            removeConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = canConfirmRemove(loading, checked, blockKey == null)
        }
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val (curLoading, curWritable) = try {
                val c = vm.state.value
                c.loading to (!c.loading && writeBlockReason(c) == null)
            } catch (_: Exception) {
                loading to (blockKey == null)
            }
            if (canConfirmRemove(curLoading, removeAcknowledged, curWritable)) vm.confirmRemove(pending)
        }
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { vm.cancelRemove(pending) }
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            removeLabelsDialog = AlertDialog.Builder(this)
                .setTitle(R.string.remove_confirm_view_labels)
                // Phase K K1 加固：即時讀快取（回調已回填時取 fresh，未回仍為佔位，不差於 captured var）。
                .setMessage(buildRemoveLabelsMessage(getString(R.string.remove_confirm_labels), resolveAppLabelCached(pending.pkg).takeIf { it != pending.pkg } ?: appLabel, pending.profileLabel))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = canConfirmRemove(loading, removeAcknowledged, blockKey == null)
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !loading
        removeConfirmDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !loading
        // show 後再設一次以覆蓋 render 更新（在途不可取消，關閉 cancel 窗的使用者路徑）。
        removeConfirmDialog?.setCancelable(!loading)
        removeConfirmDialog?.setCanceledOnTouchOutside(!loading)
        // Phase F F1：快取＋catalog 皆 miss 才起 IO；回來時同 pending 仍在屏才更新
        // 訊息＋標籤詳情（buildRemove* 純函數重算，Phase E 誠實語意不變）。
        if (!isLabelCached(pending.pkg)) {
            requestLabelAsync(pending.pkg) { fresh ->
                try {
                    if (shownPending != pending) return@requestLabelAsync
                    val dlg = removeConfirmDialog ?: return@requestLabelAsync
                    if (dlg.isShowing != true) return@requestLabelAsync
                    // Phase K K1：回寫 captured var（NEUTRAL 閉包經 Ref 後續可見），
                    // NEUTRAL 後開 detail 不再讀佔位。
                    appLabel = fresh
                    val newMessage = buildRemoveConfirmMessage(
                        template = getString(R.string.remove_confirm_message),
                        pkg = pending.pkg,
                        appLabel = fresh,
                        profileLabel = pending.profileLabel,
                        dualId = pending.dualId,
                        serial = pending.dualSerial,
                    )
                    try {
                        dlg.setMessage(newMessage)
                    } catch (_: Exception) {
                        try {
                            dlg.findViewById<TextView>(android.R.id.message)?.text = newMessage
                        } catch (_: Exception) {
                            // Best effort.
                        }
                    }
                    try {
                        removeLabelsDialog?.setMessage(
                            buildRemoveLabelsMessage(
                                getString(R.string.remove_confirm_labels),
                                fresh,
                                pending.profileLabel,
                            ),
                        )
                    } catch (_: Exception) {
                        // Best effort: 標籤詳情未展開時無需更新。
                    }
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    // Best-effort display name for the dialog: the PackageManager label when
    // installed for the owner, else the raw package token. Never throws: a
    // missing/unreadable package falls back to the token so the dialog always
    // names the exact uninstall target.
    // Phase B B4：pkg→label 內存快取（有界 200，與圖示快取同級）。
    // catalog 引用變化即失效（重裝/卸載後標籤可能改變）；失敗不快取（包可能稍後出現）。
    // Phase F F1：主執行緒永不 PM IPC。同步路徑只讀快取（resolveAppLabelCached），
    // miss 回 pkg 佔位並經 requestLabelAsync 在 IO 查詢後回填＋重綁（比照 loadIcon：
    // tag/世代檢查，過期丟棄，快取保留）。catalog 自帶 label（IO 已查好）由呼叫端
    // 經 catalogLabelsFor 優先填入，等價於 reloadCatalog IO 路徑預取且無執行緒競爭。
    // Phase F F2：catalogKey 由 render 入口一次計算，傳給 renderClonedApps 與
    // syncRemoveDialog 共用（syncLabelCache＋指紋共用），不再各入口各自重算
    //（O(cloned×catalog) → O(catalog)）。命中移尾改 LRU（與圖示快取一致 remove+put，
    // 首項＝最久未用）。失效語義保留全清：label 僅顯示用，重裝/卸載後標籤可能改變，
    // 全清為正確性優先的簡單選擇（重填走非同步 IO，有界 200，不阻塞主執行緒）。
    private fun labelCacheKey(): Int = try {
        vm.state.value.sourceCatalog.hashCode()
    } catch (_: Exception) {
        0
    }

    // Phase I I1：只清 cache，不清 labelCallbacks（在途合併中回調仍有效；失效後重填走非同步 IO）。
    private fun syncLabelCache(catalogKey: Int) {
        try {
            if (labelCacheCatalogKey != catalogKey) {
                labelCache.clear()
                labelCacheCatalogKey = catalogKey
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    // 同步只讀快取（LRU 命中移尾），miss 回 pkg，不打 PM binder。主執行緒唯一同步入口。
    private fun resolveAppLabelCached(pkg: String): String {
        try {
            labelCache[pkg]?.let {
                labelCache.remove(pkg)
                labelCache.put(pkg, it)
                return it
            }
        } catch (_: Exception) {
            // Best effort.
        }
        return pkg
    }

    // LRU 寫入（有界 MAX_LABEL_CACHE，滿時淘汰最久未用首項；與圖示快取同式）。
    private fun cacheLabel(pkg: String, label: String) {
        try {
            labelCache.remove(pkg)
            if (labelCache.size >= MAX_LABEL_CACHE) {
                labelCache.keys.firstOrNull()?.let { labelCache.remove(it) }
            }
            labelCache[pkg] = label
        } catch (_: Exception) {
            // Best effort: 快取寫入失敗不影響顯示。
        }
    }

    // render 側 catalog 預取：catalog 每項 label 本就在 reloadCatalog IO 路徑查好，
    // 此處一次建表（O(catalog)）供本輪各列 O(1) 取用並順手灌快取；仍缺才走
    // requestLabelAsync。呼叫端（renderClonedApps/dialog）在同步綁定前呼叫。
    private fun catalogLabelsFor(catalog: List<SourceAppEntry>): Map<String, String> = try {
        val m = HashMap<String, String>(catalog.size)
        for (e in catalog) {
            try {
                if (e.label.isNotBlank() && e.label != e.packageName) m[e.packageName] = e.label
            } catch (_: Exception) {
                continue
            }
        }
        m
    } catch (_: Exception) {
        emptyMap()
    }

    // Phase F F1：miss 時 IO 查詢後回填。同一 pkg 在途合併（labelInflight＋labelCallbacks）；
    // Phase I I1：per-pkg 回調合併——head：cache 命中 return；inflight 命中則合併回調後 return；
    // 否則記 inflight＋註冊回調後起 IO。完成：remove inflight→cacheLabel→無條件 rebindClonedLabel
    // （I2，tag 精確只碰同 pkg 行，覆蓋防禦性 return 極窄窗口；dialog 無 tag 容器可掃，靠自有回調）。
    // IO 查 PM，主執行緒經各 onLoaded 回填（呼叫端各做 tag/世代檢查後重綁，舊門保留）。
    // 失敗不快取（包可能稍後出現）但仍清 inflight＋callbacks（drop 不回調，避免洩漏）。
    // 分身列表規模小（非 200 來源列），不另設 Semaphore。
    // 全部主執行緒觸碰（head 同步＋完成回主線）；syncLabelCache 清 cache 不清 callbacks。
    private fun requestLabelAsync(pkg: String, onLoaded: (String) -> Unit) {
        try {
            if (labelCache.containsKey(pkg)) return
            if (pkg in labelInflight) {
                labelCallbacks.getOrPut(pkg) { mutableListOf() }.add(onLoaded)
                return
            }
            labelInflight.add(pkg)
            labelCallbacks.getOrPut(pkg) { mutableListOf() }.add(onLoaded)
        } catch (_: Exception) {
            return
        }
        lifecycleScope.launch {
            val label: String? = withContext(Dispatchers.IO) {
                try {
                    val info = if (Build.VERSION.SDK_INT >= 33) {
                        packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getApplicationInfo(pkg, 0)
                    }
                    packageManager.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }
            try {
                labelInflight.remove(pkg)
            } catch (_: Exception) {
                // Best effort.
            }
            if (label == null || label == pkg) {
                try {
                    labelCallbacks.remove(pkg)
                } catch (_: Exception) {
                    // Best effort.
                }
                return@launch
            }
            cacheLabel(pkg, label)
            // Phase I I2：無條件重綁同 pkg 行（tag 精確），覆蓋各 waiter 防禦性 return 極窄窗口。
            try {
                rebindClonedLabel(pkg, label)
            } catch (_: Exception) {
                // Best effort: 重綁失敗不影響快取與各回調（下次同步命中）。
            }
            val waiters = try {
                labelCallbacks.remove(pkg)
            } catch (_: Exception) {
                null
            } ?: return@launch
            for (cb in waiters) {
                try {
                    cb(label)
                } catch (_: Exception) {
                    // Best effort: 單個重綁失敗不影響快取與其餘 waiter（下次同步命中）。
                }
            }
        }
    }

    private fun isLabelCached(pkg: String): Boolean = try {
        labelCache.containsKey(pkg)
    } catch (_: Exception) {
        false
    }

    // Phase F F1：分身行 label 回填 fallback（發起行已 detach 時走容器按 tag 查找）。
    // 同行內按 tag 更新：title（tag=pkg）設文字，badge（tag=badge:pkg）與
    // Switch（tag=switch:pkg）更新 contentDescription；找不到丟棄（快取保留）。
    // Switch 亦為 TextView 子類，故 title 查找另加型別守衛（title 建時只給純 TextView
    // 設 tag=pkg，Switch 的 tag 為 switch:pkg）。
    private fun rebindClonedLabel(pkg: String, label: String) {
        try {
            if (!::clonedAppsContainer.isInitialized) return
            for (i in 0 until clonedAppsContainer.childCount) {
                val row = clonedAppsContainer.getChildAt(i) as? ViewGroup ?: continue
                val title = try {
                    row.findViewWithTag<TextView>(pkg)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (title is Switch) continue
                try {
                    title.text = label
                } catch (_: Exception) {
                    continue
                }
                try {
                    row.findViewWithTag<TextView>("badge:$pkg")?.contentDescription =
                        getString(R.string.dual_badge_cd, label)
                } catch (_: Exception) {
                    // Best effort.
                }
                try {
                    // Phase G: full-width colon via remove_row_cd.
                    row.findViewWithTag<Switch>("switch:$pkg")?.contentDescription =
                        getString(R.string.remove_row_cd, getString(R.string.action_remove), label)
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    // 相容舊同步入口。主執行緒只讀快取（miss 回 pkg，不打 PM binder）；
    // 背景執行緒（已在 IO）保留同步 PM 查詢供非 UI 路徑（key 對齊＋hash 皆在 IO，無主執行緒成本）。
    private fun resolveAppLabel(pkg: String): String {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return resolveAppLabelCached(pkg)
        } catch (_: Exception) {
            return resolveAppLabelCached(pkg)
        }
        syncLabelCache(labelCacheKey())
        try {
            labelCache[pkg]?.let {
                labelCache.remove(pkg)
                labelCache.put(pkg, it)
                return it
            }
        } catch (_: Exception) {
            // Best effort: 快取失敗仍走 PM 查詢。
        }
        val label = try {
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
        if (label != pkg) cacheLabel(pkg, label)
        return label
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
                catalogLoadError = getString(R.string.catalog_load_failed)
                updateCatalogErrorView()
                return@launch
            }
            catalogLoadError = null
            updateCatalogErrorView()
            vm.setSourceCatalog(apps)
        }
    }

    // B 去重：比照 msg/writeBlock（shouldUpdate＋View 實字雙比對），
    // 無關 render 不重設相同文字，避免 POLITE 重播；旋轉重建以 View 為準。
    private fun updateCatalogErrorView() {
        try {
            if (!::catalogErrorView.isInitialized) return
            val err = catalogLoadError
            val newText = err.orEmpty()
            val viewCurrent = try {
                catalogErrorView.text.toString()
            } catch (_: Exception) {
                null
            }
            if (shouldUpdateCatalogErrorText(lastCatalogErrorText, newText) || viewCurrent != newText) {
                if (viewCurrent != newText) catalogErrorView.text = newText
                lastCatalogErrorText = newText
            }
            val wantVisible = !err.isNullOrEmpty()
            if ((catalogErrorView.visibility == View.VISIBLE) != wantVisible) {
                catalogErrorView.visibility = if (wantVisible) View.VISIBLE else View.GONE
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
        systemToggleBtn.text = if (s.includeSystemApps) {
            getString(R.string.system_apps_shown)
        } else {
            getString(R.string.system_apps_hidden)
        }
        systemToggleBtn.contentDescription = if (s.includeSystemApps) {
            getString(R.string.system_toggle_cd_shown)
        } else {
            getString(R.string.system_toggle_cd_hidden)
        }
        batchBtn.text = resources.getQuantityString(R.plurals.batch_create, s.selectedKeys.size, s.selectedKeys.size)
        batchBtn.contentDescription = resources.getQuantityString(
            R.plurals.batch_create_cd,
            s.selectedKeys.size,
            s.selectedKeys.size,
        )
        updateCatalogErrorView()
        // A：可寫但批次目標未確認（targetSerial null/UNKNOWN）時顯示可見原因，
        // 不可靜默（空報告＋就緒）；有逐項報告時優先顯示報告，無報告才顯示目標原因。
        // VM 守衛不動（batchEnabled/verifiedTargetAvailable 僅 UI 門控）。
        // B 去重：比照 msg/writeBlock（shouldUpdate＋View 實字雙比對，避免無關 render 重播）。
        val batchTargetReason = if (batchTargetUnavailable(s)) {
            try {
                getString(R.string.batch_target_unavailable)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val reportText = batchReportText(s.batchReport)
        val newBatchText = if (reportText.isNotEmpty()) reportText else (batchTargetReason ?: "")
        val batchViewCurrent = try {
            batchResultView.text.toString()
        } catch (_: Exception) {
            null
        }
        if (shouldUpdateBatchResultText(lastBatchResultText, newBatchText) || batchViewCurrent != newBatchText) {
            if (batchViewCurrent != newBatchText) {
                try {
                    batchResultView.text = newBatchText
                } catch (_: Exception) {
                    // Best effort: render may run before buildUi in tests.
                }
            }
            lastBatchResultText = newBatchText
        }
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
            sourceContainer.addView(TextView(this).apply { text = getString(R.string.source_empty_loading) })
        } else if (visible.isEmpty()) {
            sourceContainer.addView(TextView(this).apply { text = getString(R.string.source_empty_nomatch) })
        } else {
            for (entry in visible.take(MAX_SOURCE_ROWS)) {
                val box = CheckBox(this).apply {
                    // 可辨識列:名稱 + 包名；TalkBack 讀同一文字。
                    text = getString(R.string.source_row, entry.label, entry.packageName)
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
                        text = resources.getQuantityString(
                            R.plurals.source_truncated,
                            visible.size,
                            visible.size,
                            MAX_SOURCE_ROWS,
                        )
                    },
                )
            }
        }
    }

    // 來源列表指紋：catalog 內容 hash（與 labelCacheKey 同源，見 syncLabelCache）、query、系統開關、勾選集合 hash。
    private fun buildSourceListKey(s: UiState): String =
        "${s.sourceCatalog.hashCode()}:" +
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
    // Phase D 共用圖示載入核心（雙 requestIcon overload single source）：
    // 快取命中由 freshIconDrawable 同步供呼叫端綁定；未命中走 loadIcon
    //（memo/inflight/淘汰/回填），完成經 onLoaded 回主執行緒綁定。
    // LRU：命中移尾（LinkedHashMap 插入序，首項＝最久未用，
    // pickIconEvictionKey 照舊淘汰首項）。
    private fun freshIconDrawable(cached: Drawable): Drawable = try {
        cached.constantState?.newDrawable()?.mutate() ?: cached.mutate()
    } catch (_: Exception) {
        cached
    }

    private fun loadIcon(pkg: String, appKey: String, onLoaded: (Drawable) -> Unit) {
        if (iconMemo.isLoaded(appKey)) return
        if (appKey in iconInflight) return
        iconInflight.add(appKey)
        lifecycleScope.launch {
            // Phase F F4：併發上限（見 iconLoadSemaphore）。acquire 失敗（銷毀取消）
            // 即清 inflight 返回，允許下次列表重建時重試。
            try {
                iconLoadSemaphore.acquire()
            } catch (_: Exception) {
                try {
                    iconInflight.remove(appKey)
                } catch (_: Exception) {
                    // Best effort.
                }
                return@launch
            }
            try {
                val drawable = withContext(Dispatchers.IO) {
                    try {
                        packageManager.getApplicationIcon(pkg)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (drawable == null) return@launch
                val victim = pickIconEvictionKey(iconDrawables.keys.toList(), MAX_ICON_CACHE)
                if (victim != null) evictIcon(victim)
                iconMemo.markLoaded(appKey)
                iconDrawables[appKey] = drawable
                onLoaded(drawable)
            } finally {
                try {
                    iconLoadSemaphore.release()
                } catch (_: Exception) {
                    // Best effort.
                }
                iconInflight.remove(appKey)
            }
        }
    }

    private fun requestIcon(pkg: String, appKey: String, box: CheckBox) {
        val cached = iconDrawables[appKey]
        if (cached != null) {
            iconDrawables.remove(appKey)
            iconDrawables[appKey] = cached
            box.setCompoundDrawablesWithIntrinsicBounds(freshIconDrawable(cached), null, null, null)
            return
        }
        loadIcon(pkg, appKey) { drawable ->
            val fresh = freshIconDrawable(drawable)
            // 首選仍掛在容器下且綁定同一 appKey 的發起列；若該列已被重建取代
            // （parent 已非容器），則在當前容器內按 tag 找同一 key 的可見列重繪
            //（找不到＝已過濾掉／換 catalog，不貼）。
            try {
                if (box.tag == appKey && box.parent === sourceContainer) {
                    try {
                        box.setCompoundDrawablesWithIntrinsicBounds(fresh, null, null, null)
                    } catch (_: Exception) {
                        // Best effort: 舊列 detach 後貼圖失敗則走容器查找。
                    }
                } else {
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
                }
            } catch (_: Exception) {
                // Best effort: 容器未就緒（測試路徑）時不阻塞圖示快取。
            }
            // Phase B B2 跨列表共享 iconInflight：來源完成時分身容器也嘗試重綁，
            // 避免另一列表（同 appKey 先因 inflight 合併而未發起）永久無圖。
            try {
                rebindClonedIcon(appKey, drawable)
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    // Phase B B2：分身 ImageView 版。呼叫端先設 tag=appKey（此處再設一次兜底）；
    // 完成時照抄 CheckBox 版：tag＋容器歸屬雙比對，否則在容器內按 tag 找 ImageView
    // 重綁，找不到丟棄。gen 為 renderClonedApps 世代，過期丟棄綁定（快取保留，
    // 下次渲染同步路徑可直接命中）。
    // Phase I I3：跨容器重綁（rebindClonedIcon＋rebindSourceIcon）移到 gen 檢查前，
    // 鏡像來源路徑（CheckBox 版無 gen 門）；tag＋容器雙比對防貼錯，gen 只守直接
    // setImageDrawable（發起 View 仍掛載才直貼）。避免重建後舊回調因 capturedGen
    // 擋在重綁前，致新行（同 appKey）永久無圖。
    private fun requestIcon(pkg: String, appKey: String, imageView: ImageView, gen: Int) {
        try {
            imageView.tag = appKey
        } catch (_: Exception) {
            // Best effort.
        }
        val cached = iconDrawables[appKey]
        if (cached != null) {
            iconDrawables.remove(appKey)
            iconDrawables[appKey] = cached
            try {
                imageView.setImageDrawable(freshIconDrawable(cached))
            } catch (_: Exception) {
                // Best effort.
            }
            return
        }
        val capturedGen = gen
        loadIcon(pkg, appKey) { drawable ->
            // Phase I I3：先跨容器/同容器重綁（tag 精確，找不到丟棄），再做 gen 門。
            try {
                rebindClonedIcon(appKey, drawable)
            } catch (_: Exception) {
                // Best effort.
            }
            // Phase B B2 跨列表共享 iconInflight：分身完成時來源容器也嘗試重綁。
            try {
                rebindSourceIcon(appKey, drawable)
            } catch (_: Exception) {
                // Best effort.
            }
            if (capturedGen != clonedGen) return@loadIcon
            val fresh = freshIconDrawable(drawable)
            try {
                if (isUnderClonedContainer(imageView) && imageView.tag == appKey) {
                    try {
                        imageView.setImageDrawable(fresh)
                    } catch (_: Exception) {
                        // Best effort: 舊行 detach 後已由上重綁覆蓋。
                    }
                }
                // else 分支已由上重綁覆蓋（容器內按 tag 找同 key 行），此處不再重複。
            } catch (_: Exception) {
                // Best effort: 容器未就緒（測試路徑）時不阻塞圖示快取。
            }
        }
    }

    // Phase B B2：在分身容器內按 tag 找 ImageView 重綁（找不到丟棄）。
    // 行結構：row(LinearLayout)→iconContainer(FrameLayout)→ImageView＋badge；
    // 用遞迴兜底未來結構變化。
    private fun rebindClonedIcon(appKey: String, drawable: Drawable) {
        if (!::clonedAppsContainer.isInitialized) return
        val target = findImageViewWithTag(clonedAppsContainer, appKey) ?: return
        val perView = try {
            drawable.constantState?.newDrawable()?.mutate()
                ?: drawable.mutate()
        } catch (_: Exception) {
            drawable
        }
        target.setImageDrawable(perView)
    }

    // Phase B B2：在來源容器內按 tag 找 CheckBox 重綁（找不到丟棄）。
    private fun rebindSourceIcon(appKey: String, drawable: Drawable) {
        if (!::sourceContainer.isInitialized) return
        for (i in 0 until sourceContainer.childCount) {
            val child = sourceContainer.getChildAt(i)
            if (child is CheckBox && child.tag == appKey) {
                val perView = try {
                    drawable.constantState?.newDrawable()?.mutate()
                        ?: drawable.mutate()
                } catch (_: Exception) {
                    drawable
                }
                child.setCompoundDrawablesWithIntrinsicBounds(perView, null, null, null)
                break
            }
        }
    }

    private fun findImageViewWithTag(root: View, appKey: String): ImageView? {
        try {
            if (root is ImageView && root.tag == appKey) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val found = findImageViewWithTag(root.getChildAt(i), appKey)
                    if (found != null) return found
                }
            }
        } catch (_: Exception) {
            // Best effort.
        }
        return null
    }

    private fun isUnderClonedContainer(view: View): Boolean {
        try {
            if (!::clonedAppsContainer.isInitialized) return false
            var p = view.parent
            while (p != null) {
                if (p === clonedAppsContainer) return true
                p = (p as? View)?.parent
            }
        } catch (_: Exception) {
            // Best effort.
        }
        return false
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
    // Stage4：純渲染走頂層 formatBatchReportText（JVM 可測），此處僅委派。
    private fun batchReportText(report: BatchReport?): String = formatBatchReportText(report)

    companion object {
        // Test seam: allow instrumentation to inject a fake-backed ViewModel
        // (zero device writes) without changing production defaults. Null in
        // production.
        @Volatile
        internal var testViewModel: DualViewModel? = null

        const val TAB_DUAL_APPS = 0
        const val TAB_CORE_ENGINE = 1
        private const val KEY_CURRENT_TAB = "main.current_tab"
        private const val TAG = "MainActivity"

        // Shizuku permission request code; matched in both the Shizuku result
        // listener and the defensive onRequestPermissionsResult override.
        private const val SHIZUKU_REQUEST_CODE = 1001

        // 旋轉保留鍵：診斷技術細節展開開關。
        private const val KEY_DIAG_EXPANDED = "diagExpanded"
        // B1 旋轉保留鍵：去重快取（僅去重參考；新 View 重建為空時 render 以 View
        // 實際文字為準補寫，不會因快取命中而跳過首寫）。
        // C 去時間鍵亦隨存活（自更新判斷比鍵；B 的 batch/catalog 僅內存）。
        private const val KEY_WRITE_BLOCK_TEXT = "writeBlockText"
        private const val KEY_WRITE_BLOCK_DEDUP = "writeBlockDedup"
        private const val KEY_MSG_TEXT = "msgText"
        // 移除確認勾選綁定世代保留鍵：同世代旋轉保留，跨世代重置；Neutral 不重置。
        private const val KEY_REMOVE_ACK = "removeAck"
        private const val KEY_REMOVE_ACK_GEN = "removeAckGen"
        // PR-7 P2 (#26):單次渲染列數上限 (效能護欄,超出請用搜尋縮小範圍)。
        private const val MAX_SOURCE_ROWS = 200

        // PR-7 P2 (#26):圖示 Drawable 有界快取上限 (顯示用,不參與身分鍵)。
        private const val MAX_ICON_CACHE = 200

        // Phase B B4：pkg→label 內存快取上限（與圖示快取同級，顯示用）。
        private const val MAX_LABEL_CACHE = 200

        // PR-37 Phase C #5 圖示淘汰純決策 (JVM 可測 seam)：快取已滿
        // (orderedKeys.size >= maxSize) 時回傳最久未用 key（Phase D 起 LRU：
        // 命中移尾，故插入順序第一個＝最久未用），未滿回傳 null。只用 stdlib 集合、無 Android 依賴；呼叫方拿到 victim
        // 後經 evictIcon 同步清除 drawables＋memo 兩處。
        internal fun pickIconEvictionKey(orderedKeys: List<String>, maxSize: Int): String? =
            if (orderedKeys.size >= maxSize) orderedKeys.firstOrNull() else null
    }
}

// p28-1 手動包名就地驗證（純函數，JVM 可測；Activity TextWatcher/IME 共用）。
// p28-1 review（en 殘留）：回傳資源鍵（不碰 Context，JVM 可測）；文字見
// values/strings.xml + values-en/，由 Context.resolvePackageInputError 解析。
// - 空白不報錯（空欄位不打擾；送出時 VM 仍以 INVALID 拒絕）。
// - trim 後超長（MAX_PACKAGE_INPUT_CHARS）沿用 VM 拒絕同文（中文語系字面一致；
//   超長模板與 VM 共用同一上限數字，見 input_too_long）。
// - 非法字元就地提示已中文化（invalid_package）；VM 內同碼 msg 仍為英文原文
//   （VM msg 保留程式內字面，見 values/strings.xml 檔頭），英文語系兩側一致。
// - 字元集不自訂 regex：直接沿用 core ShellEscaper 政策（單一來源），此處僅
//   提示用 try/catch 轉為 INVALID 鍵；真正執法仍在 repo/VM。
internal enum class PackageInputProblem {
    TOO_LONG,
    INVALID,
}

internal fun packageInputError(raw: String): PackageInputProblem? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length > MAX_PACKAGE_INPUT_CHARS) return PackageInputProblem.TOO_LONG
    return try {
        ShellEscaper.escapePackage(trimmed)
        null
    } catch (_: IllegalArgumentException) {
        PackageInputProblem.INVALID
    }
}

// 資源鍵 -> string 資源（JVM 可測，不碰 Context；雙語鍵一致性見 UiSemanticsP28Test）。
internal fun PackageInputProblem.stringRes(): Int = when (this) {
    PackageInputProblem.TOO_LONG -> R.string.input_too_long
    PackageInputProblem.INVALID -> R.string.invalid_package
}

// Context 解析（Activity 側；TOO_LONG 帶上限參數）。
internal fun Context.resolvePackageInputError(problem: PackageInputProblem): String = when (problem) {
    PackageInputProblem.TOO_LONG -> getString(problem.stringRes(), MAX_PACKAGE_INPUT_CHARS)
    PackageInputProblem.INVALID -> getString(problem.stringRes())
}

// P2-2 手動輸入可用性（純函數，JVM 可測；clone/remove enablement 共用）。
// 非空（trim 後）且就地驗證通過（packageInputError == null）才可用。
// 空白不打擾（就地不報錯）但按鈕禁用；VM 空白/超長/非法仍 fail-closed 拒絕，
// 此處只做 UI 層即時門控，不取代 VM 守衛。
internal fun manualInputActionable(raw: String): Boolean {
    if (raw.trim().isEmpty()) return false
    return packageInputError(raw) == null
}

// 空白禁用可感知理由（純函數，JVM 可測；VM 守衛不動，僅 UI 層提示）。
// 空白（trim 後空）且非 busy 且通道可寫（writeBlocked == false）時回 true：
// 此時 clone/remove 禁用的唯一原因是輸入空白，須給 pkgInput 可見 error/helper＋
// contentDescription（見 syncBlankInputA11y），不可靜默禁用；通道阻塞/忙碌時
// 禁用原因由 writeBlockView/loading 承載，此處回 false（不疊加提示）。
internal fun shouldShowBlankInputReason(isBlank: Boolean, busy: Boolean, writeBlocked: Boolean): Boolean =
    isBlank && !busy && !writeBlocked

// P2-2 目前 verified target 可用性（純函數，JVM 可測；batch enablement 用）。
// UI 層近似門控：targetId > 0 且 targetSerial 非 null 才視為可用
// （0 永不可寫；-1=NO_TARGET_ID 表未知；serial null 表 SerialUnavailable，
// cloneSelected 起始 pin 需 Confirmed(id+serial) 必拒絕，故 UI 亦禁用）。
// 權威判定以 VM 起始 pin 為準（fetchDualTarget Confirmed 才執行），此處只做
// UI 層即時門控（fail-closed 對齊，不取代 VM 守衛）。
internal fun verifiedTargetAvailable(s: UiState): Boolean =
    s.inventory.targetId > 0 && s.inventory.targetSerial != null

// P2-2 clone/remove enablement（純函數，JVM 可測；render + TextWatcher 共用）。
// 三條件：非 busy、可寫（writeBlockReason == null）、手動輸入可用。
// VM isWritable/空白/超長守衛保留為縱深（點按仍 fail-closed）。
internal fun cloneRemoveEnabled(s: UiState, manualActionable: Boolean): Boolean =
    !s.loading && writeBlockReason(s) == null && manualActionable

// P2-2 batch enablement（純函數，JVM 可測；render + TextWatcher 共用）。
// 四條件：非 busy、有勾選、可寫、目前 verified target 可用。
// VM 側 isWritable + 起始 pin（SerialUnavailable/NoDual 拒絕）保留為縱深。
internal fun batchEnabled(s: UiState): Boolean =
    !s.loading && s.selectedKeys.isNotEmpty() &&
        writeBlockReason(s) == null && verifiedTargetAvailable(s)

// A 批次目標不可用可見原因（純函數，JVM 可測；render 顯示 batch_target_unavailable 用）。
// 可寫但 verified target 不可用（targetSerial null/UNKNOWN）時回 true，
// 呼叫端在 batchResultView 顯示可見原因（不可靜默 GONE+就緒）；
// VM 守衛不動（writeBlockReason/isWritable 起始 pin 保留為權威）。
internal fun batchTargetUnavailable(s: UiState): Boolean =
    !s.loading && writeBlockReason(s) == null && !verifiedTargetAvailable(s)

// B batch/catalog 更新守衛（純函數，JVM 可測；render 決定是否重設 text 用）。
// 比照 msg/writeBlock：old != new；render 另以 View 實際文字雙重比對
// （旋轉重建 View 為空時補寫，避免無關 render 重播 POLITE）。
internal fun shouldUpdateBatchResultText(old: String?, new: String?): Boolean = old != new

internal fun shouldUpdateCatalogErrorText(old: String?, new: String?): Boolean = old != new

// B1 去重鍵去時間（純函數，JVM 可測）：可見文字保留完整時間資訊，
// 僅去重比對前剝除時間關鍵參數，避免 LSPosed displayText 時間戳漂移導致恆 false。
// 雙源時間正規化（probe HookDiagnosis.displayText 無時間 vs writeBlock
// HookEvidence.displayText 有時間）：兩形經此剝除時間證據後綴收斂為同一基底，
// 使去重鍵有效。剝除項：格式化日期（yyyy-MM-dd HH:mm）、「從未查詢」空態、
// 重啟舊資料 stale 提示、經真通道標記（時間/通道衍生，隨 queriedAt 漂移
// 出現/消失），以及時間證據前綴「；marker時間=」「；marker 時間未知」
// 「；查詢於」（結構分隔符，基底文案不含「；」，安全）。
internal fun stripWriteBlockTimeParams(text: String): String {
    var k = text.replace("（可能為重啟前舊資料，僅存在性證據）", "")
    k = k.replace("（經真通道讀取）", "")
    k = k.replace(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), "")
    k = k.replace("從未查詢", "")
    k = k.replace("；marker時間=", "")
    k = k.replace("；marker 時間未知", "")
    k = k.replace("；查詢於 ", "")
    k = k.replace("；查詢於", "")
    return k
}

// B1 去重鍵（純函數，JVM 可測；WriteBlockKind 比對）：Keyed 以 kind + 去時間穩定參數
// 為鍵（LSPOSED 的 args[0] 為 Hook 證據含時間戳，去時間後只留狀態/範圍；
// P2-3 AUTO 降級亦為 Keyed，kind 即區分）。factory blockText 與
// Activity resolveBlockReason 經同一 WriteBlock.stringRes() 解析（見下），
// 此鍵鎖定 single source。
internal fun writeBlockDedupKey(reason: BlockReason?): String? = when (reason) {
    null -> null
    is BlockReason.Keyed -> when (reason.block.kind) {
        WriteBlockKind.LSPOSED_PLACEHOLDER ->
            "K:${reason.block.kind.name}:${stripWriteBlockTimeParams(reason.block.args.getOrElse(0) { "" })}"
        else -> "K:${reason.block.kind.name}:${reason.block.args.joinToString("|")}"
    }
}

// P2-1 同文去重判定（純函數，JVM 可測；render 決定 writeBlockView live 用）。
// 非 busy 且禁用原因文字非空且與 s.msg 同文（去時間後相等亦算同文）時回傳 true
//（呼叫端將 writeBlockView 降為 NONE，只留 msgView POLITE 播報，避免同一拒絕理由
// 雙節點重複播報）。可寫/忙碌/空訊息/異文一律 false（各播各的）。
// B1：保留可見文字完整時間資訊，僅去重鍵去時間（stripWriteBlockTimeParams），
// 解決 LSPosed displayText 時間戳漂移（msg 寫入時與 render 時 queriedAt 不同）恆 false。
internal fun isWriteBlockDuplicate(blockReasonText: String?, msg: String, busy: Boolean): Boolean {
    if (busy) return false
    if (blockReasonText == null) return false
    if (msg.isEmpty()) return false
    if (blockReasonText == msg) return true
    return stripWriteBlockTimeParams(blockReasonText) == stripWriteBlockTimeParams(msg)
}

// P2-1 文字更新守衛（純函數，JVM 可測；render 決定是否重設 text 用）。
// 顯示新鮮度：比完整文字（old != new）；時間戳僅變亦回傳 true，呼叫端刷新顯示。
// 播報抑制另見 shouldAnnounceWriteBlock/shouldAnnounceMsg（穩定去重鍵控制 liveRegion）。
internal fun shouldUpdateWriteBlockText(old: String?, new: String?): Boolean = old != new

// B1 msg 更新守衛（純函數，JVM 可測；render 決定是否重設 msgView.text 用）。
// 與 shouldUpdateWriteBlockText 同式：比完整文字（old != new）；
// 播報抑制另見 shouldAnnounceMsg（去時間鍵控制 liveRegion）。
internal fun shouldUpdateMsgText(old: String?, new: String?): Boolean = old != new

// P2 播報抑制（純函數，JVM 可測；render 決定 liveRegion 用，不阻止顯示刷新）。
// writeBlock：穩定去重鍵未變（時間戳僅變）或跨節點同文時不播報（NONE），
// 僅去重鍵變化且非跨節點同文才播報（POLITE）；顯示仍以 shouldUpdate* 全文比對刷新。
internal fun shouldAnnounceWriteBlock(oldDedupKey: String?, newDedupKey: String?, crossDuplicate: Boolean): Boolean =
    !crossDuplicate && oldDedupKey != newDedupKey

// msg 同式：去時間鍵未變（時間戳僅變）不播報，變化才播報；顯示仍全文比對刷新。
internal fun shouldAnnounceMsg(oldStripped: String?, newStripped: String?): Boolean = oldStripped != newStripped

// 寫入拒絕理由鍵 -> string 資源（JVM 可測；Activity/factory 以此解析）。
// P2-3 AUTO 降級亦經此解析（auto_degrade_*），主路徑無 Verbatim。
internal fun WriteBlock.stringRes(): Int = when (kind) {
    WriteBlockKind.LSPOSED_PLACEHOLDER -> R.string.write_block_lsposed
    WriteBlockKind.NOOP_FALLBACK -> R.string.write_block_noop
    WriteBlockKind.EXPLICIT_FAILURE -> R.string.write_block_explicit
    WriteBlockKind.PM_UNREADABLE -> R.string.write_block_pm
    WriteBlockKind.NO_PRIVILEGE -> R.string.write_block_readonly
    WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU -> R.string.auto_degrade_su_to_shizuku
    WriteBlockKind.AUTO_DEGRADE_BOTH_FAILED -> R.string.auto_degrade_both_failed
    WriteBlockKind.AUTO_DEGRADE_NO_CHANNEL -> R.string.auto_degrade_no_channel
}

// Context 解析（Activity 側顯示 + factory 供 VM 的 blockText）。
internal fun Context.resolveBlockReason(reason: BlockReason): String = when (reason) {
    is BlockReason.Keyed -> getString(reason.block.stringRes(), *reason.block.args.toTypedArray())
}

// PR-6 P4 (#27 架構整理)：render() 的純字串拆分 (頂層 internal，JVM 可測)。
// 有意變更 (非等價重構)，共四處，各有理由：
// - 0/無效 epoch 改經 formatTimeMs 顯示「從未查詢」(理由：空態明確化，原 raw 0/不顯示易誤讀為有效時間)；
// - marker 時間文案由 raw epoch 改為格式化時間 (理由：可讀性/可追溯性，與 HookEvidence.displayText 統一)；
// - 上次操作相位由清單行尾移至 renderLastOpText 獨立行 (理由：行職責單一/可讀性，無操作顯示「無」與匯出同詞)；
// - hook 時間證據經 hookEvidence()+timeEvidenceText 統一 (狀態文案維持本頁提示，不動；理由：單一來源免分叉)。

// 雙語範圍誠實化：以下 render*/formatBatchReport 皆保留繁中（scope-limited，
// 非全量雙語；僅寫入拒絕/輸入驗證/診斷複製短句已資源化中英＋繁中）。未來在地化
// 再抽資源，穩定碼/測試鎖定繁中字面不受影響。
// Phase L L3 最小 EN 分支：renderInventoryText＋renderHookText 支援 isEnglish
//（生產呼叫端傳 isEnglishLocale()，預設 false 保中文測試；英文文案為 scope-limited
// 硬編碼，參照 values-en 語氣，未新增資源鍵故三語系鍵集不變）。
// P2 餘項（本次未動，僅記錄）：renderDiagnoseText／renderDiagnoseDescription／
// renderLastOpText／formatBatchReportText／DiagnoseStrings 表／
// HookEvidence.timeEvidenceText（212-213／289-297）／
// DualViewModel probeResultMark・pmReadableText・readyMark（5852-5877）／
// formatTimeMs「從未查詢」；manifest theme 為 p28-3 範圍，本次不動。
internal fun isEnglishLocale(locale: Locale = Locale.getDefault()): Boolean =
    try {
        locale.language.equals("en", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

// 清單新鮮度行：STALE/UNKNOWN 明確標示，FRESH 顯示目標/筆數/查詢時間。
internal fun renderInventoryText(s: UiState, isEnglish: Boolean = false): String = buildString {
    if (isEnglish) {
        append("Inventory: ${s.inventory.freshness}")
        if (s.inventory.targetId > NO_TARGET_ID) {
            append(" (user ${s.inventory.targetId}, ${s.inventory.count} items)")
        }
        val q = if (s.inventory.queryTimeMs <= 0L) "Never queried" else formatTimeMs(s.inventory.queryTimeMs)
        append(" · queried at $q")
        if (s.inventory.freshness != InventoryFreshness.FRESH) {
            append(" (may be stale; auto-refreshed or tap \"Reconnect and refresh\")")
        }
        return@buildString
    }
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
// 口徑註記：批次明細 (formatBatchReportText) 顯示 raw pkg 是 by-design 例外——
// 該處列的是使用者剛勾選的包名，需可核對；此處 lastOp 顯示遮蔽版。
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

// Phase A A4：Shizuku 備援卡片狀態純函數（JVM 可測）。輸入即 updateShizukuLine()
// 的同一組 live booleans（noBinder 优先：binder 死時授權無意義），輸出卡片
// 三態；卡片標題/徽章只經此映射，不另行判讀 Shizuku。
internal enum class ShizukuCardStatus {
    NO_BINDER,
    UNAUTHORIZED,
    AUTHORIZED,
}

internal fun resolveShizukuCardStatus(noBinder: Boolean, authorized: Boolean): ShizukuCardStatus =
    if (noBinder) {
        ShizukuCardStatus.NO_BINDER
    } else if (authorized) {
        ShizukuCardStatus.AUTHORIZED
    } else {
        ShizukuCardStatus.UNAUTHORIZED
    }

// Phase H live-health 誠實門控 (Codex P1 #2732，JVM 可測；render 內三處
// hookProvenActive 同源改用 hookLiveProven)：
// - hookHistoryActive = existence 證據 (marker present，survives reboots，
//   未確認本次開機；僅驅動歷史文案 renderHookText，不驅動 live 宣稱)。
// - hookLiveProven = existence + hookBootFresh (本次開機存活證明；今日恆
//   false，因全 repo 無 BOOT_COUNT/boot_id/uptime 讀取，boot 比較未落地)。
// 六徽章/badges/icons/summary/telemetry 綠/ACTIVE 文案/逐行 tag 一律由
// hookLiveProven 驅動；hookHistoryActive 為真但 hookBootFresh 為 false 時
// 全 GONE/unknown/灰。boot-fresh 落地後自動點亮，無需改 render。
// 文案紀律：boot-fresh 前不得用 已注入/攔截中/運作中/常駐中/正常監聽/5/5/
// Active/Running/Intercepting/Injected 作 live 宣稱；歷史顯示重用
// telemetry_status_unknown/stats_hook_unknown/engine_diag_unknown，永不給綠。
internal fun hookHistoryActive(s: UiState): Boolean =
    renderHookText(s) != null && s.hookStatus == HookStatus.ACTIVE

internal fun hookLiveProven(s: UiState): Boolean =
    hookHistoryActive(s) && s.hookBootFresh

// 六徽章＋逐行 tag＋checklist badges＋icons 著色單一可見源 (JVM seam；
// render 直接以此作 visibility/color 條件，免三處各寫各的)。
internal fun hookLiveBadgesVisible(s: UiState): Boolean = hookLiveProven(s)

// Hero/telemetry/summary 誠實三態 (JVM seam；render 映射至資源，鍵集不變)：
// LIVE (boot-fresh 證實) 才給綠＋live 文案；INACTIVE 照舊灰＋未偵測；其餘
// (含 ACTIVE 但未 boot-fresh 的歷史標記) 一律 UNKNOWN (amber/灰＋狀態未知)。
internal enum class HookLiveDisplay {
    LIVE,
    INACTIVE,
    UNKNOWN,
}

internal fun hookLiveDisplay(s: UiState): HookLiveDisplay = when {
    hookLiveProven(s) -> HookLiveDisplay.LIVE
    s.hookStatus == HookStatus.INACTIVE -> HookLiveDisplay.INACTIVE
    else -> HookLiveDisplay.UNKNOWN
}

// Hook 行：非 LSPosed 模式回 null (呼叫端 Gone)；狀態文案維持本頁提示
// (ACTIVE 歷史證據措辭/INACTIVE 啟用提示/UNKNOWN 真通道提示，不動)，
// 時間證據經 hookEvidence()+timeEvidenceText 統一。
// Phase L L3：isEnglish 分支（ACTIVE 歷史措辭＋全行英文；時間後綴仍繁中為 P2 餘項）。
internal fun renderHookText(s: UiState, isEnglish: Boolean = false): String? {
    if (s.channel != ChannelPreference.LSPOSED) return null
    if (isEnglish) {
        val enStatus = when (s.hookStatus) {
            HookStatus.ACTIVE ->
                "Historical Hook marker detected (${s.hookScopes.sorted().joinToString(", ")}, existence-only evidence; not confirmed for this boot)"
            HookStatus.INACTIVE ->
                "Not detected (enable the module in LSPosed Manager and reboot)"
            HookStatus.UNKNOWN_UNREADABLE ->
                "Unknown (no read permission; full diagnosis needs a ROOT/Shizuku channel, see #27)"
            HookStatus.UNKNOWN_NO_DIAGNOSTIC ->
                "Unknown (no diagnostic channel; switch to a ROOT/Shizuku channel and re-probe, see #27)"
        }
        return "LSPosed Hook: $enStatus${s.hookEvidence().timeEvidenceText()}; write channel is a placeholder (NoOp), whitelist policy branch pending, writes disabled"
    }
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

// 診斷事件行：穩定碼 + 繁中摘要/下一步常顯（scope-limited 繁中，非雙語）；展開時加 exit/技術 detail。
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
// 生產 UI 以資源 R.string.diagnostic_copy_button 為準（中英＋繁中），此 const
// 僅為 JVM seam（與 zh 資源同字）；P2-4 誠實文案：遮蔽仍非全面保證，須附
// 「分享前請人工確認」，不得只寫已遮蔽。使用者主動觸發才複製，不做自動傳輸/share。
const val DIAGNOSTIC_COPY_BUTTON_TEXT = "複製已遮蔽內容（分享前請人工確認）"

// StageD D4 剪貼簿複製成功訊息（頂層 const，JVM 可測）：生產 UI 以資源
// R.string.diagnostic_copy_success 為準（中英＋繁中），此 const 僅為 JVM seam
// （與 zh 資源同字）；維持使用者主動觸發+警示，補「用後請清除剪貼簿」並保留
// 「僅本機剪貼簿」（不做自動傳輸/share）。
const val DIAGNOSTIC_COPY_SUCCESS_TEXT =
    "已複製已遮蔽的診斷內容（僅本機剪貼簿，無上傳，分享前請人工確認，用後請清除剪貼簿）"

// 診斷行無障礙描述 (含展開狀態，供 contentDescription + 切換宣告)。
internal fun renderDiagnoseDescription(s: UiState, expanded: Boolean): String {
    val diag = s.diagnose
    if (diag == null) return "診斷：就緒"
    val state = if (expanded) "已展開，點兩下收合" else "點兩下展開技術細節"
    return "診斷［${diag.code.stableId}］${diag.summaryZh}；下一步：${diag.nextStepZh}（$state）"
}

// 批次逐項結果文字上限（Stage4 效能護欄）：明細最多顯示前 N 項，超出以
// 「…等 M 項」收尾（與來源清單 MAX_SOURCE_ROWS 同式截斷提示）。
const val BATCH_REPORT_MAX_ROWS = 50

// 批次逐項結果文字 (MainActivity 頂層 internal，JVM 可測；render 僅委派；保留繁中 scope-limited，非雙語)。
// - 未知口徑與 DualViewModel.isBatchUnknownOutcome 一致：VerifyUnknown 與
//   ChannelError（身份暫時無法判定、零寫入，需重查）皆計未知；NoDual 計失敗
//   （真 Absent＋M13 短路語意），其餘非 Success 皆計失敗。彙總計數由呼叫端
//   BatchReport 攜帶，此處只渲染不重算；formatter 測試鎖定此口徑，防彙總/明細漂移。
// - 明細包名顯示 raw pkg 是 by-design（與 renderLastOpText 的 redactPackage
//   差異是故意的）：此處列的是使用者剛勾選、即將/已經逐項執行的包名，需可核對；
//   診斷匯出/日誌仍經兩道遮蔽（見 buildDiagnosticExport）。
internal fun formatBatchReportText(report: BatchReport?): String {
    if (report == null || report.items.isEmpty()) return ""
    return buildString {
        appendLine(
            "批次結果：成功 ${report.successCount} / 失敗 ${report.failedCount} " +
                "/ 未知 ${report.unknownCount}（共 ${report.items.size} 項）",
        )
        val shown = report.items.take(BATCH_REPORT_MAX_ROWS)
        for (item in shown) {
            val mark = when (item.outcome) {
                is CloneOutcome.Success -> "成功"
                is CloneOutcome.VerifyUnknown -> "未知（需重查）"
                is CloneOutcome.ChannelError -> "未知（通道無法查證，請重試）"
                else -> "失敗"
            }
            appendLine("  ${item.pkg}：$mark")
        }
        val rest = report.items.size - shown.size
        if (rest > 0) {
            appendLine("  …等 $rest 項（共 ${report.items.size} 項，僅顯示前 $BATCH_REPORT_MAX_ROWS 項）")
        }
    }.trimEnd()
}

// 移除確認文案純函數（MainActivity 頂層 internal，JVM 可測）。
// 破壞性確認顯示原文以供核對，匯出才遮蔽（by-design 例外）：
// 本地確認畫面顯示完整 package + user/serial，不經
// sanitizeForDisplay/redact，否則使用者無法核對卸載目標；
// 控制字元明確跳脫並隔離欄位；摘要與標籤詳情均有明示的長度上限。
// package 不截斷，以便核對完整身分。
// 診斷匯出/日誌仍經四道遮蔽（見 buildDiagnosticExport）。
// - pkg：待移除完整包名（raw，呼叫端傳 pending.pkg）。
// - appLabel：PackageManager 解析顯示名；失敗時呼叫端回退傳 pkg
//  （見 resolveAppLabel），兩種皆須含完整 pkg + user/serial。
// - profileLabel：已驗證目標顯示名（raw，pending.profileLabel）。
// - dualId/serial：已驗證目標 user/serial（數字身份可讀性）。
// Escape backslashes too, so an actual control cannot be confused with literal text.
// Trusted isolates keep normal RTL text from reordering the surrounding identity.
internal fun canConfirmRemove(loading: Boolean, acknowledged: Boolean): Boolean = !loading && acknowledged

// Phase B B1：confirm 門控同 Switch（loading＋ack＋可寫）。二參版保留供既有
// JVM 測試；生產呼叫端一律用三參版（writable 即 blockKey == null）。
internal fun canConfirmRemove(loading: Boolean, acknowledged: Boolean, writable: Boolean): Boolean =
    !loading && acknowledged && writable

internal fun sanitizeRemoveConfirmField(raw: String, maxCodePoints: Int? = null, maxOutput: Int = Int.MAX_VALUE): String {
    require(maxOutput >= TRUNCATION_MARKER.length + 2)
    val out = StringBuilder("\u2068")
    var i = 0
    var count = 0
    var markerSafeLength = out.length
    while (i < raw.length && (maxCodePoints == null || count < maxCodePoints)) {
        val cp = raw.codePointAt(i)
        val escaped = when {
            cp == 0x5c -> "\\\\"
            cp == 0x5b -> "\\["
            Character.getType(cp) == Character.CONTROL.toInt() ||
                (Character.getType(cp) == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D) ||
                Character.getType(cp) == Character.LINE_SEPARATOR.toInt() ||
                Character.getType(cp) == Character.PARAGRAPH_SEPARATOR.toInt() ||
                Character.getType(cp) == Character.SURROGATE.toInt() ->
                "\\u{" + cp.toString(16).uppercase(java.util.Locale.ROOT).padStart(4, '0') + "}"
            else -> String(Character.toChars(cp))
        }
        // First allow the complete escaped value to use the entire budget.
        if (out.length.toLong() + escaped.length + 1 > maxOutput) break
        out.append(escaped)
        if (out.length.toLong() + TRUNCATION_MARKER.length + 1 <= maxOutput) markerSafeLength = out.length
        i += Character.charCount(cp)
        count++
    }
    if (i < raw.length) {
        out.setLength(markerSafeLength)
        out.append(TRUNCATION_MARKER)
    }
    return out.append('\u2069').toString()
}

private const val TRUNCATION_MARKER = " [truncated]"

internal fun buildRemoveLabelsMessage(template: String, appLabel: String, profileLabel: String): String =
    String.format(java.util.Locale.ROOT, template, sanitizeRemoveConfirmField(appLabel, 256, 512), sanitizeRemoveConfirmField(profileLabel, 256, 512))

internal fun buildRemoveConfirmMessage(
    template: String,
    pkg: String,
    appLabel: String,
    profileLabel: String,
    dualId: Int,
    serial: Int,
): String = String.format(
    java.util.Locale.ROOT, template,
    sanitizeRemoveConfirmField(appLabel, 32, 96), sanitizeRemoveConfirmField(pkg), dualId.toString(),
    sanitizeRemoveConfirmField(profileLabel, 32, 96), serial.toString(),
)

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
            // Phase I I4：factory 跑主執行緒，永不直呼 binder IPC（shizukuAuthorizedNow）。
            // 啟動以 fail-closed false 組裝（明選 SHIZUKU 不受影響，仍直選 SHIZUKU；
            // AUTO 若真有 Shizuku 授權，VM init probeChannels 經 onIo 探測後由
            // degradeAutoLocked 按 live probe 糾正為 SHIZUKU）。
            val asm = assembleChannel(pref, suBinaryPresent(), false, appPackageName)
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
                // Phase I I4：此二 check 皆可能觸 I/O（su 檔探測／Shizuku binder），
                // VM 側 swapToLocked/degradeAutoLocked 經 onIo 包裝後呼叫，永不在主線直跑。
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
                // p28-1 review（en 殘留）：寫入拒絕理由經 Application Context
                // 解析資源（applicationContext，不持 Activity；JVM 測試注入假值）。
                blockText = { block ->
                    appContext.applicationContext.getString(
                        block.stringRes(),
                        *block.args.toTypedArray(),
                    )
                },
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
        // Phase I I4：僅 IO 執行緒呼叫（VM 經 onIo 包裝的 shizukuAuthCheck；
        // factory 啟動不再主線直呼，改 fail-closed false＋probe 糾正）。
        internal fun shizukuAuthorizedNow(): Boolean =
            try {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
    }
}
