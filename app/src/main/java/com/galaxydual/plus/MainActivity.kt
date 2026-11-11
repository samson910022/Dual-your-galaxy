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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
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
import com.galaxydual.hook.PolicySource
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

// Stage1 批次建立兩步確認請求快照（Activity 本地狀態，不經 VmState）。
// 沿用 remove ack 的世代契約：每次點擊 batchBtn 產生新世代，ack 綁世代保留
// （同世代旋轉保留、跨世代重置）；confirm 才呼叫 vm.cloneSelected(approved)，
// VM 在鎖內驗證「核准快照(keys+target) == live/pin」後才寫入（PR64 P1）。
// P2（review #4）：ack/請求亦綁定 FRESH vs 非-FRESH 警告語境，避免跨 recreate/click 沿用舊 ack。
internal data class BatchConfirmRequest(
    val selectedKeys: Set<String>,
    val count: Int,
    val targetId: Int,
    val targetSerial: Int,
    val generation: Long,
    val wasFresh: Boolean,
)

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
    // + 勾選,取消勾選 ≠ 卸載)；batchBtn 顯示「建立 N 個分身」。
    // Stage1：batchBtn 移到 sticky bottom bar（貼 bottomBar 上方），點擊先開
    // 「確認建立」對話框（世代＋ack＋目標摘要＋風險），確認後才 vm.cloneSelected()
    // (cloneVerifiedExpected 逐項 pin：非原子交易、逐包驗證，見 DualViewModel)。
    private lateinit var searchInput: EditText
    private lateinit var systemToggleBtn: Button
    private lateinit var selectAllBtn: Button
    private lateinit var clearSelBtn: Button
    private lateinit var saveSelBtn: Button
    private lateinit var cancelSaveBtn: Button
    // Stage2 (hook-policy-source P1)：白名單政策（草稿）最小入口（核心引擎頁
    // 技術卡一節＋兩階段寫入對話框，沿用 remove ack 模式；不改首頁大佈局）。
    private lateinit var policyDraftView: TextView
    private lateinit var writePolicyBtn: Button
    private lateinit var clearPolicyBtn: Button
    private lateinit var refreshPolicyBtn: Button
    private var policyWriteDialog: AlertDialog? = null
    private var serviceBindListener: (() -> Unit)? = null
    private var shownPolicyWrite: PendingPolicyWrite? = null
    // Phase 5：已顯示 dialog 的 saving 相位快照（early-return 偵測 QUEUED→SAVING
    // 翻轉，翻轉沿播報一次；僅 UI 播報用，不參與門控）。
    private var shownPolicyWriteSaving: Boolean = false
    private var policyAcknowledged = false
    private var policyAckGeneration: Long? = null
    // Stage B P2 dialog 完整清單（方案 A Toggle）：展開/收合純 UI 開關。
    // rest>0 才顯示 Toggle；in-place 切換保 ack；世代重置同步重置 expanded；
    // 旋轉經 KEY_POLICY_EXPANDED(/GEN) 保留，同世代保留、跨世代重置。
    private var policyWriteExpanded = false
    private var policyWriteExpandedGeneration: Long? = null
    private lateinit var sourceContainer: LinearLayout
    private lateinit var catalogErrorView: TextView
    private lateinit var batchBtn: Button
    // Stage1：sticky bottom bar 的勾選數文字（「已選 N 項」），與主 CTA 永遠可見。
    private lateinit var batchSelCountView: TextView
    // P2：sticky bar 內簡短狀態/原因（捲動時仍可見），點擊可捲至詳細結果。
    private lateinit var batchBarStatusView: TextView
    // Stage1：sticky bottom bar 容器（只在雙開應用頁顯示；Engine 頁隱藏，見 switchTab）。
    private lateinit var batchBar: LinearLayout
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

    // Stage1 批次建立兩步確認（Activity 本地，沿用 remove ack 模式）：
    // 點擊 batchBtn 只建立請求快照（世代 + 目標摘要 + 數量）並開對話框，
    // 勾選_ack 且非 busy/可寫才啟用「確認建立」；confirm 才 cloneSelected()。
    // pendingBatchRequest 為當前待確認快照（旋轉經 Bundle 保留，render 重新顯示）；
    // shownBatchRequest/shownBatchWasFresh 追蹤已在屏實例，避免無謂重建/漏更新。
    private var batchConfirmDialog: AlertDialog? = null
    private var pendingBatchRequest: BatchConfirmRequest? = null
    private var shownBatchRequest: BatchConfirmRequest? = null
    private var shownBatchWasFresh: Boolean? = null
    private var batchAcknowledged = false
    private var batchAcknowledgedGeneration: Long? = null
    private var batchGeneration = 0L

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
    // Phase B P2-2：Hook 卡片副標題 channel-aware（render 內 writeBlockReason
    // 門控切換 hook_mode_subtitle / _ops；初值白名單文案，fail-closed）。
    private lateinit var hookDescView: TextView
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
    // Phase 3 P2 perf: catalog hash 快取 (referential identity)。
    // sourceCatalog.hashCode() 為 O(catalog)；render 每幀經 catalogHashCached
    // 計算 labelCacheKey/buildSourceListKey 指紋時，若引用未變直接回快取，
    // 重算只在 !== 時發生。syncRemoveDialog 簽名被 seam 測試鎖定 (4-tuple)，
    // 故其內 catalogLabelsFor 仍讀 vm.state.value (非 trivially safe，不動)。
    private var lastCatalogHashRef: List<SourceAppEntry>? = null
    private var lastCatalogHash: Int = 0
    // 啟動 double-load 守衛：onCreate+onResume 雙 reloadCatalog 遇在途直接共用，不取消重掃。
    private var catalogJob: Job? = null
    // Phase K K2：Shizuku presence 快取在途任務（比照 catalogJob 3536-3537）：
    // refresh/request 頭寫共用同一 Job 取消，避免 old-live 蓋 new-death。
    private var shizukuPresenceJob: Job? = null
    // P1：cancel 非真序列化（pingBinder blocking 不可取消，舊 IO 返回仍寫入
    // last-writer-wins）。單調世代（比照 clonedGen 2066）：發起時主線 ++ 取值，
    // IO 回主線後比對，過期丟棄；cancel 保留（快速釋放）＋gen 比對（正確性），雙保險。
    private var shizukuGen = 0
    // Phase B B4：分身列表指紋（cloned hash + targetSerial + loading）。
    // 未變只同步移除鈕 enabled（比照 renderSourceList 差量）。
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
    // Phase 1a：waiter 攜註冊時 catalog epoch（Pair.first）；完成時 head epoch 失配則整批結果丟棄
    //（不 cache／不 rebind），僅喚醒註冊 epoch 與當代一致的 waiter（見 requestLabelAsync 完成段守衛）。
    // 全部主執行緒觸碰（head＋完成皆主線）；完成隨 inflight 清 map；syncLabelCache 清 cache 不清 callbacks。
    private val labelInflight = mutableSetOf<String>()
    private val labelCallbacks = mutableMapOf<String, MutableList<Pair<Int?, (String) -> Unit>>>()
    // Phase B B4：Shizuku binder 存活 volatile 快取。listener/onResume/授權回調
    // 經 refreshShizukuPresenceCache 維護，render 經 cachedShizukuPresence 讀快取，
    // 不再每次 render 都 pingBinder。
    // Phase 1a（P3 N2）：雙 volatile 兩步讀寫可撕裂，改單一 holder 原子寫入／原子讀取。
    private data class CachedShizukuPresence(val noBinder: Boolean?, val authorized: Boolean)
    @Volatile private var cachedShizukuState: CachedShizukuPresence? = null

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
        // Stage1 批次建立確認：請求快照隨 Activity 存活保留（同世代旋轉重現對話框）。
        // PR64 P1：keys 缺失（舊狀態）fail-closed，不恢復可授權的快照。
        // Review #4：freshness 語境亦必須保存，否則跨 recreate 可能沿用舊 ack。
        if (savedInstanceState != null &&
            savedInstanceState.containsKey(KEY_BATCH_REQ_GEN) &&
            savedInstanceState.containsKey(KEY_BATCH_REQ_WAS_FRESH)
        ) {
            val generation = savedInstanceState.getLong(KEY_BATCH_REQ_GEN)
            val restoredKeys = savedInstanceState.getStringArrayList(KEY_BATCH_REQ_KEYS)?.toSet()
            val restoredCount = savedInstanceState.getInt(KEY_BATCH_REQ_COUNT)
            val restoredWasFresh = savedInstanceState.getBoolean(KEY_BATCH_REQ_WAS_FRESH)
            if (restoredKeys != null && restoredKeys.isNotEmpty() && restoredKeys.size == restoredCount) {
                pendingBatchRequest = BatchConfirmRequest(
                    selectedKeys = restoredKeys,
                    count = restoredCount,
                    targetId = savedInstanceState.getInt(KEY_BATCH_REQ_TARGET_ID),
                    targetSerial = savedInstanceState.getInt(KEY_BATCH_REQ_TARGET_SERIAL),
                    generation = generation,
                    wasFresh = restoredWasFresh,
                )
                batchGeneration = generation
                batchAcknowledged = savedInstanceState.getBoolean(KEY_BATCH_ACK)
                batchAcknowledgedGeneration =
                    if (savedInstanceState.containsKey(KEY_BATCH_ACK_GEN)) {
                        savedInstanceState.getLong(KEY_BATCH_ACK_GEN)
                    } else {
                        null
                    }
            }
        }
        // Stage2：政策寫入確認勾選綁定世代保留（同 remove ack 模式）。
        policyAcknowledged = savedInstanceState?.getBoolean(KEY_POLICY_ACK) ?: false
        policyAckGeneration =
            if (savedInstanceState?.containsKey(KEY_POLICY_ACK_GEN) == true) {
                savedInstanceState.getLong(KEY_POLICY_ACK_GEN)
            } else {
                null
            }
        // Stage B P2：完整清單展開開關保留（同世代旋轉保留，跨世代重置見 sync）。
        policyWriteExpanded = savedInstanceState?.getBoolean(KEY_POLICY_EXPANDED) ?: false
        policyWriteExpandedGeneration =
            if (savedInstanceState?.containsKey(KEY_POLICY_EXPANDED_GEN) == true) {
                savedInstanceState.getLong(KEY_POLICY_EXPANDED_GEN)
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
        // 5.1.5:service 綁定時刷新政策草稿 (避免晚綁定導致草稿看似空)。
        val serviceBindListener = { vm.refreshPolicyDraft() }
        this.serviceBindListener = serviceBindListener
        GalaxyApp.addServiceBindListener(serviceBindListener)
        // 補發：若服務在訂閱前已綁定，listener 不會收到 onServiceBind，
        // 此處主動觸發一次刷新，避免 lost-event 競態。
        if (GalaxyApp.isServiceConnected()) serviceBindListener()
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
        // Stage1 批次建立確認：請求快照隨旋轉保留（同世代重現對話框，跨世代重置）。
        pendingBatchRequest?.let { req ->
            outState.putStringArrayList(KEY_BATCH_REQ_KEYS, ArrayList(req.selectedKeys))
            outState.putInt(KEY_BATCH_REQ_COUNT, req.count)
            outState.putInt(KEY_BATCH_REQ_TARGET_ID, req.targetId)
            outState.putInt(KEY_BATCH_REQ_TARGET_SERIAL, req.targetSerial)
            outState.putLong(KEY_BATCH_REQ_GEN, req.generation)
            outState.putBoolean(KEY_BATCH_REQ_WAS_FRESH, req.wasFresh)
        }
        outState.putBoolean(KEY_BATCH_ACK, batchAcknowledged)
        if (batchAcknowledgedGeneration != null) outState.putLong(KEY_BATCH_ACK_GEN, batchAcknowledgedGeneration!!)
        // Stage2：政策寫入確認勾選綁定世代保留。
        outState.putBoolean(KEY_POLICY_ACK, policyAcknowledged)
        if (policyAckGeneration != null) outState.putLong(KEY_POLICY_ACK_GEN, policyAckGeneration!!)
        // Stage B P2：完整清單展開開關保留（同世代保留，跨世代重置）。
        outState.putBoolean(KEY_POLICY_EXPANDED, policyWriteExpanded)
        if (policyWriteExpandedGeneration != null) outState.putLong(KEY_POLICY_EXPANDED_GEN, policyWriteExpandedGeneration!!)
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
            batchConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        batchConfirmDialog = null
        pendingBatchRequest = null
        shownBatchRequest = null
        shownBatchWasFresh = null
        try {
            policyWriteDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window is going away anyway.
        }
        policyWriteDialog = null
        shownPolicyWrite = null
        shownPolicyWriteSaving = false
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
        serviceBindListener?.let { GalaxyApp.removeServiceBindListener(it) }
        serviceBindListener = null
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

    // WHY 四捨五入：非標準 density（如 2.625）下直接 toInt() 截斷系統性偏小約 1px；
    // v 均為正數，+0.5f 等價 roundToInt，阈值僅 ±1px。已 grep 全檔 dp() 約 160 處
    // （最小 dp(1) 分隔線除外皆 >=2dp），無 Math 依賴需同步，無行為陷阱。
    // Phase 4：換算委派 IconSizingPolicy.dpPx（公式一字不差，見該檔，JVM 可測）。
    private fun dp(v: Int): Int = dpPx(v, resources.displayMetrics.density)

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
            setTextColor(getColorStateList(R.color.pill_button_text))
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(48)
            setOnClickListener { vm.clone(pkgInput.text.toString()) }
        }
        removeBtn = Button(this).apply {
            id = R.id.remove_btn
            text = getString(R.string.action_remove)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
            contentDescription = getString(R.string.remove_cd)
            minimumHeight = dp(48)
            setOnClickListener { vm.requestRemove(pkgInput.text.toString()) }
        }
        refreshBtn = Button(this).apply {
            id = R.id.refresh_btn
            text = getString(R.string.action_reconnect)
            background = getDrawable(R.drawable.bg_pill_button)
            // Phase C: theme token (see cloneBtn).
            setTextColor(getColorStateList(R.color.pill_button_text))
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
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
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
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
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
            // Phase 3 P2 a11y: 48dp touch target via minimumHeight (height
            // stays WRAP_CONTENT so large fonts grow instead of clipping).
            minimumHeight = dp(48)
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
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
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
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
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
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
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
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (see systemToggleBtn).
            minimumHeight = dp(48)
            contentDescription = getString(R.string.clear_selection_cd)
            setOnClickListener { vm.clearSelectionLocal() }
        }
        saveSelBtn = Button(this).apply {
            text = getString(R.string.action_save_selection)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (see systemToggleBtn).
            minimumHeight = dp(48)
            contentDescription = getString(R.string.save_selection_cd)
            setOnClickListener { vm.persistSelection(vm.state.value.inventory.targetId) }
        }
        cancelSaveBtn = Button(this).apply {
            text = getString(R.string.action_unsave_selection)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColorStateList(R.color.pill_button_secondary_text))
            textSize = 12f
            // Phase C: 48dp touch target via minimumHeight (see systemToggleBtn).
            minimumHeight = dp(48)
            contentDescription = getString(R.string.unsave_selection_cd)
            setOnClickListener { vm.cancelSavedSelection() }
        }
        // Stage2：白名單政策（草稿）最小入口。寫入需二次確認
        // (requestPolicyWrite 建 pending，dialog 勾選後 confirm)；勾選≠安裝、
        // 寫草稿≠安裝 (見 DualViewModel/confirmPolicyWrite)。
        policyDraftView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        writePolicyBtn = Button(this).apply {
            text = getString(R.string.action_write_policy)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            minimumHeight = dp(48)
            contentDescription = getString(R.string.write_policy_cd)
            setOnClickListener { vm.requestPolicyWrite() }
        }
        clearPolicyBtn = Button(this).apply {
            text = getString(R.string.action_clear_policy_draft)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            minimumHeight = dp(48)
            contentDescription = getString(R.string.clear_policy_draft_cd)
            setOnClickListener { vm.clearPolicyDraft() }
        }
        // Phase 2：手動重新整理草稿（唯讀，不觸發寫入；Unknown 時計數行保留
        // 未知提示，不改 dialog 主流程）。
        refreshPolicyBtn = Button(this).apply {
            text = getString(R.string.action_refresh_policy_draft)
            background = getDrawable(R.drawable.bg_pill_button_secondary)
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 12f
            minimumHeight = dp(48)
            contentDescription = getString(R.string.refresh_policy_draft_cd)
            setOnClickListener { vm.refreshPolicyDraft() }
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
            setTextColor(getColorStateList(R.color.pill_button_text))
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(48)
            contentDescription = resources.getQuantityString(R.plurals.batch_create_cd, 0, 0)
            // Stage1：點擊不直接 clone，先開「確認建立」對話框（見 requestBatchConfirm）。
            setOnClickListener { requestBatchConfirm() }
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

        // Batch Action Button：Stage1 已移至底部 sticky bar（見下方），
        // 此處只保留批次結果/原因文字區（batchResultView）。
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
            addView(cloneBtn, spacedRowButtonParams())
            addView(removeBtn, spacedRowButtonParams())
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
            // Phase A：4/4 全綠僅在 LSPOSED + ACTIVE 時顯示；初值/無信號為未知。
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
                // Phase 3 P3 a11y: badge content = title + badge text (reuses
                // remove_row_cd so the separator stays locale-correct:
                // full-width colon in zh, half-width in en; no new key needed).
                try {
                    contentDescription = getString(R.string.remove_row_cd, titleStr, badgeText)
                } catch (_: Exception) {
                    contentDescription = "$titleStr: $badgeText"
                }
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

        // Item 2: Multi-User UID
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
        // Stage2：白名單政策（草稿）一節（最小入口：計數行＋寫入/清除/重新整理列；
        // 寫入走兩階段確認 dialog，見 syncPolicyWriteDialog）。
        techCard.addView(policyDraftView)
        val policyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(writePolicyBtn, rowParams())
            addView(clearPolicyBtn, rowParams())
            addView(refreshPolicyBtn, rowParams())
        }
        techCard.addView(policyRow)
        tabEngineLayout.addView(techCard)

        // Engine Action Buttons
        val engineActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
            addView(refreshBtn, spacedRowButtonParams())
            addView(exportBtn, spacedRowButtonParams())
        }
        tabEngineLayout.addView(engineActionRow)

        tabEngineScroll.addView(tabEngineLayout)
        contentFrame.addView(tabEngineScroll)
        root.addView(contentFrame)

        // ================= Sticky Batch Bar (Stage1) =================
        // 貼 bottomBar 上方：勾選數（「已選 N 項」）＋主 CTA 永遠可見，不隨清單
        // 捲動。門控沿用 batchEnabled(s)，禁用原因仍由 batchResultView/writeBlockView
        // 顯示（見 renderSourceList/render）。
        batchBar = LinearLayout(this).apply {
            id = R.id.batch_bar
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.oneui_surface))
            minimumHeight = dp(56)
            val pad = dp(12)
            setPadding(pad, dp(6), pad, dp(6))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val batchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        batchSelCountView = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.oneui_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        batchBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        batchBarRow.addView(batchSelCountView)
        batchBarRow.addView(batchBtn)
        batchBarStatusView = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 不設 POLITE：避免與 batchResultView 雙重播報；仍可聚焦/點擊前往詳情。
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) }
            setOnClickListener {
                if (::tabAppsScroll.isInitialized && ::batchResultView.isInitialized) {
                    tabAppsScroll.post { tabAppsScroll.smoothScrollTo(0, batchResultView.top) }
                }
            }
        }
        batchBar.addView(batchBarRow)
        batchBar.addView(batchBarStatusView)
        root.addView(batchBar)

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
                    tag = iconRes
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
        navTabSettings = createNavItem(R.drawable.ic_nav_about, getString(R.string.tab_settings)) {
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

        hookDescView = TextView(this).apply {
            text = getString(R.string.hook_mode_subtitle)
            textSize = 12f
            setTextColor(getColor(R.color.oneui_text_secondary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        hookModeCard.addView(hookDescView)

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

    @VisibleForTesting
    internal fun switchTab(tab: Int, announce: Boolean = true) {
        currentTab = tab
        if (!::tabAppsScroll.isInitialized || !::tabEngineScroll.isInitialized) return
        // Stage1：sticky batch bar 只屬雙開應用頁；切到 Engine 頁隱藏，避免命令列
        // 引擎頁出現不相干的批次 CTA（batchBtn 禁用原因仍在各頁主區顯示）。
        if (::batchBar.isInitialized) {
            batchBar.visibility = if (tab == TAB_DUAL_APPS) View.VISIBLE else View.GONE
        }
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
        // Phase 3 P3 B7: 設定開啟的是對話框 (非切頁)，CD 明示開啟對話框
        // (settings_tab_cd: EN "opens dialog" / zh "開啟對話框"，三語系 parity)。
        navTabSettings.contentDescription = getString(R.string.settings_tab_cd)

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
                // Phase C：%5$s 與卡片副標題單一來源 hookSubtitleResFor，點擊時取
                // live snapshot（與 %4$s hookLiveProven(vm.state.value) 同 staleness 類）。
                .setMessage(
                    getString(
                        R.string.settings_about_format,
                        getString(R.string.hero_title_engine),
                        appVersionName(),
                        getString(R.string.settings_about_feature),
                        getString(if (hookLiveProven(vm.state.value)) R.string.status_hook_daemon_running else R.string.engine_diag_unknown),
                        getString(hookSubtitleResFor(writeBlockReason(vm.state.value) == null)),
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
            // Phase 3 P3 B6: catch-path 不再硬編碼英文 "unknown"——資源不可用時
            // 回 locale-neutral 佔位 (與 stats_value_unknown 同字 "—")。
            "—"
        }
    }

    // Phase B B1/B2/B4：分身列表渲染。
    // B1：明確「移除」文字鈕（48dp）為 momentary action＋門控
    // isEnabled = !loading && blockKey == null（呼叫端傳 render 已算好的 blockKey，
    // 與 clone/remove/batch 同源）＋48dp 觸控目標＋移除語意 contentDescription。
    // B2：ImageView 先設 tag=appKey；coroutine capture clonedGen，過期丟棄。
    // B4：指紋未變只同步移除鈕 enabled，不重建（比照 renderSourceList）。
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
            fun syncRemoveButtons(root: android.view.View) {
                if (root is Button && (root.tag as? String)?.startsWith("remove:") == true) {
                    root.isEnabled = !s.loading && writable
                    return
                }
                if (root is ViewGroup) {
                    for (j in 0 until root.childCount) syncRemoveButtons(root.getChildAt(j))
                }
            }
            for (i in 0 until clonedAppsContainer.childCount) {
                syncRemoveButtons(clonedAppsContainer.getChildAt(i))
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
            // PR #63 re-review #4 P2：外層改垂直堆疊（每 app 一個 row，childCount/key 語意不變）；
            // 上方 icon+文字佔滿寬度，下方移除鈕獨立一行可自然伸展，避免 320dp/大字體英文截斷。
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
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
            val topContent = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            // Left icon container with #2 badge overlay
            val iconContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ICON_CONTAINER_DP), dp(ICON_CONTAINER_DP))
            }
            val appIconView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(CLONED_ICON_DP), dp(CLONED_ICON_DP)).apply {
                    gravity = Gravity.CENTER
                }
                // p26-2 Phase 1 / Phase 3 註解修正：ImageView 預設即 FIT_CENTER /
                // adjustViewBounds=false / cropToPadding=false，三行顯式聲明防未來誤改 wrap；
                // layout 固定 36dp 方形框。方形 view + 方形 intrinsic 時 FIT==CROP，本處無裁切；
                // mask／圓角不自動套用；adaptive 安全區名義上約 SAFEZONE_SCALE=66/72
                // （24dp 外框約 22dp），可見 glyph 大小仍須視覺驗證，不宜一律放大。
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                cropToPadding = false
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

            // Center details
            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumWidth = dp(96)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
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
                // Phase E E1：targetId 未知時只顯示 desc，不把未知 id（<=0）格式化進文案。
                text = if (s.inventory.targetId > 0) {
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
            topContent.addView(iconContainer)
            topContent.addView(textContainer)
            row.addView(topContent)

            // 明確「移除」文字鈕（48dp）：點按仍走 requestRemove 二階段確認
            // （對話框），行為與改版前相同；不再以開關偽裝成移除操作
            // （取消勾選 ≠ 移除）。disabled while busy or channel unwritable.
            val removeRowBtn = Button(this).apply {
                // PR #63 re-review #3/#4：視覺用短標籤；完整動作語意保留於 contentDescription。
                // 按鈕置於獨立底列（全寬）可避免 320dp/大字體截斷，不再以 90dp 硬上限擠壓。
                text = getString(R.string.action_remove_row)
                background = getDrawable(R.drawable.bg_pill_button_secondary)
                setTextColor(getColorStateList(R.color.pill_button_secondary_text))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isEnabled = !s.loading && writable
                minimumHeight = dp(48)
                minimumWidth = dp(48)
                // Phase F F1：tag 供非同步 label 回填定位（rebindClonedLabel）。
                tag = "remove:$pkg"
                // Phase G: full-width colon via remove_row_cd (was hardcoded ":").
                // CD action 保持完整語意（R.string.action_remove），不隨視覺短標籤縮短。
                contentDescription = getString(R.string.remove_row_cd, getString(R.string.action_remove), label)
                setOnClickListener { v ->
                    // 僅以 live View.isEnabled 門控：early-return 路徑每輪 render 皆同步
                    // enabled，此處 captured `writable`（重建時快照）可能陳舊——fingerprint
                    // 未變不重建時，writable 翻轉只經 live sync 反映到 View。陳舊 !writable
                    // 會誤擋有效點按；VM requestRemove 內含 fail-closed isWritable() 複查
                    // （權威門控，與 a28b56c 行為一致），此處不再用 captured 值攔截。
                    if (v?.isEnabled != true) return@setOnClickListener
                    vm.requestRemove(pkg)
                }
            }
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                }
            }
            actionRow.addView(
                removeRowBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            row.addView(actionRow)
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
                            // CD action 保持完整語意（R.string.action_remove）。
                            removeRowBtn.contentDescription = getString(R.string.remove_row_cd, getString(R.string.action_remove), fresh)
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

    // Phase B B4：分身列表指紋（cloned 內容＋目標 serial＋targetId
    // ＋hook 哨兵態；刻意不含 loading）。Phase E E4：納入 targetId（subtitle 含 targetId，
    // 未納入則目標切換留殘留舊值）；E2 哨兵態亦須入指紋（tag 顯隱由 hook
    // live 證據驅動，未納入則 hook 翻轉時 early-return 留下殘留 VISIBLE）。
    // Phase H：哨兵升格為 hookLiveProven（含 hookBootFresh，boot-fresh 落地
    // 翻轉時重建）。
    // Phase L L1：追加 catalogKey（labelCacheKey／catalog.hashCode 同源，呼叫端
    // 單次計算傳入，sync＋key 共用一次 hash）。catalog label 變化即重建，
    // 舊快取不再經 !isLabelCached 命中殘留；同內容同 hash，不致過度重建。
    // Phase 1a：移除 loading——busy 邊沿只走 early-return 同步移除鈕 enabled；
    // 納入指紋會致每次 op 全量 removeAllViews＋重建＋圖示/label 重發兩次，並推進
    // clonedGen 使在途回調失效。
    private fun buildClonedListKey(s: UiState, catalogKey: Int): String =
        clonedListKeyFor(s.cloned, s.inventory.targetSerial, s.inventory.targetId, hookLiveProven(s), catalogKey)

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
                        // Phase 3 P3 B4: catch 內再寫 msgView 須有內層守衛——
                        // msgView 未就緒/getString 失敗時的外拋不得逃出 listener。
                        try {
                            msgView.text = getString(R.string.copy_failed, sanitizeForDisplay(e.message ?: "unknown"))
                            msgView.setTextColor(msgColorFor(MsgKind.CHANNEL))
                        } catch (e2: Exception) {
                            Log.w(TAG, "showDiagnosticExport copy-failed status failed", e2)
                        }
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

    // PR #63 review: spacing only for paired pill-button rows. Leaking margins
    // from rowParams() inset single-button rows (hookDiag) and added extra width.
    private fun spacedRowButtonParams(): LinearLayout.LayoutParams =
        rowParams().apply {
            val m = dp(6)
            marginStart = m
            marginEnd = m
        }

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
                    // Phase 1a（P3 N2）：holder 單次原子寫入。
                    cachedShizukuState = CachedShizukuPresence(presence.first, presence.second)
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
                    // Phase 1a（P3 N2）：holder 單次原子寫入。
                    cachedShizukuState = CachedShizukuPresence(next.first, next.second)
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
        // Phase 1a（P3 N2）：holder 單次原子讀取，不再兩步讀雙 volatile（防撕裂）。
        val snapshot = cachedShizukuState
        val noBinder = snapshot?.noBinder
        return if (noBinder != null && snapshot != null) {
            noBinder to snapshot.authorized
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
        // renderClonedApps 的移除鈕與稍後按鈕/dialog 共用此 blockKey，不重算。
        val busy = s.loading
        // 保留 BlockReason 原鍵以計算穩定去重鍵（writeBlockDedupKey）；
        // 可見文字為完整 blockReason（含時間）：顯示比完整文字，播報比去重鍵。
        val blockKey = if (busy) null else writeBlockReason(s)
        // Phase L L2：catalogKey render 入口算一次，傳給 renderClonedApps 與
        // syncRemoveDialog 共用（各入口不再各自重算 O(catalog)）。
        // Phase 3 P2 perf: 直接以 s.sourceCatalog 經 referential 快取計算，
        // 不再重讀 vm.state.value (labelCacheKey no-arg seam 保留供 dialog/測試)。
        val catalogKey = catalogHashCached(s.sourceCatalog)
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
        // Phase 3 P3 B6: 最內層不再硬編碼英文產品名——資源不可用時改用
        // packageName (runtime，不經資源)，仍不可用才保留現標題。
        try {
            title = getString(R.string.recents_title_format, getString(R.string.app_name), s.execIdentity, s.resolvedGateway)
        } catch (_: Exception) {
            // Phase G: fallback also resourced (was hardcoded English format);
            // app_name is the localized product name.
            try {
                title = "${getString(R.string.app_name)} (${s.execIdentity}/${s.resolvedGateway})"
            } catch (_: Exception) {
                try {
                    title = "$packageName (${s.execIdentity}/${s.resolvedGateway})"
                } catch (_: Exception) {
                    // Best effort: resources unavailable, keep current title.
                }
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
        // Phase 3 P2 perf: hook 三態 render 內一次算好重用 (hookText/hookLive/
        // hookDisplay 皆為 s 的純函數；null 語意恆等於 channel != LSPOSED)，
        // 不再每分支重算 renderHookText/hookLiveProven/hookLiveDisplay (~6x)。
        val hookText = renderHookText(s, isEnglishLocale())
        val hookLive = hookLiveProven(s)
        val hookDisplay = hookLiveDisplay(s)
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
        // Stage2：白名單政策（草稿）計數行（最小入口；buildUi 前 render 走守衛）。
        try {
            if (::policyDraftView.isInitialized) {
                policyDraftView.text = if (s.policyDraftKnown) {
                    getString(R.string.policy_draft_format, s.policyDraft.size)
                } else {
                    getString(R.string.policy_draft_unknown)
                }
            }
        } catch (_: Exception) {
            // Best effort: render may run before buildUi in tests.
        }

        // One UI 6 Modern Views dynamic update
        // Phase B B4：分身列表移出此 try（獨立指紋＋獨立 try/catch，下見），
        // 此段只留計數/狀態徽章；空吞改為 Log.w（測試環境外須可見）。
        try {
            if (::clonedCountStatView.isInitialized) {
                clonedCountStatView.text = getString(R.string.stats_injected_format, s.cloned.size, getString(R.string.stats_unit_apps))
            }
            // Phase B P2-2：Hook 卡片副標題 channel-aware。真可寫
            //（writeBlockReason == null）走 _ops（特權通道直寫、無需 Hook）；
            // 其餘（loading→writeReady=false 亦含）走白名單原文案。初建即白名單
            // 文案，首幀前 fail-closed；顯式 ROOT 拒絕等不可寫態不誇大可寫。
            if (::hookDescView.isInitialized) {
                hookDescView.text = getString(hookSubtitleResFor(writeBlockReason(s) == null))
            }
            if (::clonedCountBadge.isInitialized) {
                clonedCountBadge.text = getString(R.string.badge_number_format, s.cloned.size)
            }
            if (::engineUidSlotView.isInitialized) {
                // Phase E E1：targetId 未知（<=0；-1=NO_TARGET_ID 表未知，0 無合法顯示語意）時顯示未知佔位，
                // 不得將未知 id 格式化進 UID 文案（外洩＋TalkBack 誤讀）；a11y P0。
                engineUidSlotView.text = if (s.inventory.targetId > 0) {
                    getString(R.string.engine_uid_label, s.inventory.targetId)
                } else {
                    getString(R.string.engine_uid_unknown)
                }
            }
            // Phase A A1：Hero 第三格由 hookStatus 驅動；非 LSPOSED 通道整格隱藏
            // （對齊 renderHookText 回 null 條件：channel != LSPOSED）。
            if (::hookStatusCell.isInitialized && ::hookStatusDot.isInitialized && ::hookStatusText.isInitialized) {
                // Phase 3 P2 perf: 沿用 render 入口 hookText (null 語意不變)。
                if (hookText == null) {
                    hookStatusCell.visibility = View.GONE
                } else {
                    hookStatusCell.visibility = View.VISIBLE
                    // Phase H：ACTIVE 僅歷史 existence 證據；綠＋正常監聽只給
                    // boot-fresh 證實 (hookLiveDisplay LIVE)，歷史標記走 UNKNOWN。
                    // Phase 3 P2 perf: 沿用 render 入口 hookDisplay。
                    when (hookDisplay) {
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
            // Phase 3 P2 perf: 沿用 render 入口 hookLive (同 s 純函數，語意不變)。
            val hookLiveProven = hookLive
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
                // Phase 3 P2 perf: 沿用 render 入口 hookText (null 語意不變)。
                if (hookText == null) {
                    telemetryDot.setImageDrawable(getDrawable(R.drawable.ic_dot_gray))
                    telemetryStatusView.text = getString(R.string.telemetry_status_unknown)
                } else {
                    // Phase H：ACTIVE 綠＋運作中·已注入只給 boot-fresh 證實；
                    // 歷史標記走 UNKNOWN (amber＋狀態未知，永不給綠)。
                    // Phase 3 P2 perf: 沿用 render 入口 hookDisplay。
                    when (hookDisplay) {
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
                // Phase H：4/4 模組運作正常只給 live 證實；ACTIVE 歷史標記走 unknown。
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
        // Phase B B1/B4：分身列表獨立渲染（指紋差量＋移除鈕寫入門控 blockKey）。
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
        // Phase 2 政策重新整理可觀測：刷新在途時禁用按鈕並帶狀態 CD，
        // 避免連打誤解；buildUi 前 render 走守衛（測試路徑）。
        // 政策寫入/清除鈕比照 refreshBtn：任一寫入相位在途（QUEUED/SAVING）即禁用。
        try {
            if (::refreshPolicyBtn.isInitialized) {
                refreshPolicyBtn.isEnabled = !s.policyRefreshing
                refreshPolicyBtn.contentDescription = getString(
                    if (s.policyRefreshing) R.string.refresh_policy_draft_cd_refreshing
                    else R.string.refresh_policy_draft_cd,
                )
            }
            if (::writePolicyBtn.isInitialized) {
                writePolicyBtn.isEnabled = s.policyWritePhase == PolicyWritePhase.IDLE
            }
            if (::clearPolicyBtn.isInitialized) {
                clearPolicyBtn.isEnabled = s.policyWritePhase == PolicyWritePhase.IDLE
            }
        } catch (_: Exception) {
            // Best effort: render may run before buildUi in tests.
        }
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
            localizeVmMessage(s.msg).ifEmpty { getString(R.string.status_ready) }
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
        // Phase B B1：confirm 門控同移除鈕（loading＋ack＋可寫 blockKey==null）。
        // try 包裝比照 renderClonedApps：未來契約 misfire 時降級，不殺 StateFlow collector。
        try {
            syncRemoveDialog(s.pendingRemove, s.loading, blockKey, catalogKey)
        } catch (e: Exception) {
            Log.w(TAG, "syncRemoveDialog failed", e)
        }
        // Stage1 批次建立兩步確認：請求存在即顯示/同步對話框（旋轉同世代保留 ack）。
        try {
            syncBatchDialog(pendingBatchRequest, s)
        } catch (e: Exception) {
            Log.w(TAG, "syncBatchDialog failed", e)
        }
        // Stage2：兩階段政策寫入 dialog（state-driven，同 pendingRemove 模式；
        // 旋轉由 retained ViewModel 重播 state，無需重送）。
        // Phase 3：唯一真相為 state（s.policyWritePhase）；不再讀
        // vm.policyOperationInProgress / policyWriteCommitInProgress getter
        //（相容保留，見 DualViewModel）。四參：pending＋全域 loading（僅顯示用，
        // 不門控確認，見 syncPolicyWriteDialog）＋state 派生的 policyOp/policySaving。
        try {
            syncPolicyWriteDialog(
                s.pendingPolicyWrite,
                s.loading,
                s.policyWritePhase != PolicyWritePhase.IDLE,
                // Stage A：核對中視同寫入中（禁用確認/取消，顯示忙碌；dialog 以
                // pending==null 關閉，confirm 核對中 pending 保留故 dialog 續留）。
                s.policyWritePhase == PolicyWritePhase.SAVING ||
                    s.policyWritePhase == PolicyWritePhase.RECONCILING,
            )
        } catch (e: Exception) {
            Log.w(TAG, "syncPolicyWriteDialog failed", e)
        }
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
    // Phase B B1：confirm 另須通道可寫（blockKey == null，與移除鈕/其他寫入按鈕同源；
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
        // Phase 1a：sync 上提到 early-return 之前——對話框顯示中 catalog 變化亦能刷新快取，
        // 避免殘留舊 cache／舊文案（idempotent＋cheap，見 syncLabelCache）。
        syncLabelCache(catalogKey)
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
        // Phase 1a：sync 已在 early-return 前完成（見上），此處不再重複。
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
            val hPad = (CONFIRM_ACK_HPAD_DP * d).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = (CONFIRM_ACK_MIN_HEIGHT_DP * d).toInt()
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
            // Phase 3 P3: Neutral 子對話框比照 settingsDialog/syncRemoveDialog
            // 的 BadToken 守衛 (isFinishing/isDestroyed + try/catch 包 show)。
            if (isFinishing || isDestroyed) return@setOnClickListener
            try {
                removeLabelsDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.remove_confirm_view_labels)
                    // Phase K K1 加固：即時讀快取（回調已回填時取 fresh，未回仍為佔位，不差於 captured var）。
                    .setMessage(buildRemoveLabelsMessage(getString(R.string.remove_confirm_labels), resolveAppLabelCached(pending.pkg).takeIf { it != pending.pkg } ?: appLabel, pending.profileLabel))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: Exception) {
                Log.w(TAG, "removeLabelsDialog failed", e)
                removeLabelsDialog = null
            }
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
            // Phase 1a 世代守衛：回填時 catalog 世代已變則丟棄（dialog 文案以下次 render 重建為準）。
            val dialogEpoch = labelCacheCatalogKey
            requestLabelAsync(pending.pkg) { fresh ->
                try {
                    if (shownPending != pending) return@requestLabelAsync
                    if (dialogEpoch != labelCacheCatalogKey) return@requestLabelAsync
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

    private fun requestBatchConfirm() {
        if (isFinishing || isDestroyed) return
        val s = try {
            vm.state.value
        } catch (_: Exception) {
            return
        }
        // 與 batchEnabled 同一 UI 門控（fail-closed）；按鈕本身已禁用，此為縱深。
        if (!batchEnabled(s)) return
        val serial = s.inventory.targetSerial ?: return
        val approvedKeys = s.selectedKeys.toSet()
        if (approvedKeys.isEmpty()) return
        batchGeneration += 1
        val request = BatchConfirmRequest(
            selectedKeys = approvedKeys,
            count = approvedKeys.size,
            targetId = s.inventory.targetId,
            targetSerial = serial,
            generation = batchGeneration,
            wasFresh = (s.inventory.freshness == InventoryFreshness.FRESH),
        )
        // 跨世代一律重置勾選（同 remove：新請求不得沿用舊世代的 ack）。
        batchAcknowledged = false
        batchAcknowledgedGeneration = null
        pendingBatchRequest = request
        showBatchConfirmDialog(request, s)
    }

    // 顯示/重建對話框。呼叫端需先建立 request（點擊或旋轉恢復）。
    private fun showBatchConfirmDialog(request: BatchConfirmRequest, s: UiState) {
        if (isFinishing || isDestroyed) return
        try {
            batchConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window may already be gone.
        }
        shownBatchRequest = request
        // Review #4：ack/文案語境綁定請求快照（非 live），避免跨 recreate 沿用舊 ack。
        shownBatchWasFresh = request.wasFresh
        // 同世代重建（旋轉）保留勾選；跨世代已先在請求端重置。
        if (batchAcknowledgedGeneration != request.generation) {
            batchAcknowledged = false
            batchAcknowledgedGeneration = null
        }
        // P2：STALE/非即時驗證時不得宣稱「已驗證目標」；VM 執行前仍會再次 pin。
        val messageTemplate = if (request.wasFresh) {
            R.string.batch_confirm_message
        } else {
            R.string.batch_confirm_message_stale
        }
        val message = buildBatchConfirmMessage(
            template = getString(messageTemplate),
            count = request.selectedKeys.size,
            targetId = request.targetId,
            targetSerial = request.targetSerial,
        )
        val acknowledgement = CheckBox(this).apply {
            setText(R.string.batch_confirm_acknowledge)
            // 窄螢幕可見可操作：24px 改 dp 換算＋WRAP_CONTENT＋最小觸控高 48dp。
            val d = resources.displayMetrics.density
            val hPad = (24 * d).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = (48 * d).toInt()
            isChecked = batchAcknowledged
        }
        try {
            batchConfirmDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.batch_confirm_title))
                .setMessage(message)
                .setView(acknowledgement)
                // 預確認請求可隨時中止（避免 busy/loading 卡死）；確認仍受 canConfirmBatch 門控。
                .setCancelable(true)
                .setPositiveButton(getString(R.string.batch_confirm_positive), null)
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .setOnCancelListener { cancelBatchConfirm() }
                .show()
        } catch (_: Exception) {
            batchConfirmDialog = null
            shownBatchRequest = null
            shownBatchWasFresh = null
            pendingBatchRequest = null
            return
        }
        acknowledgement.setOnCheckedChangeListener { _, checked ->
            batchAcknowledged = checked
            batchAcknowledgedGeneration = if (checked) request.generation else null
            // P2：勿用建構當下的 stale UiState；以 live 狀態重算 enablement。
            val live = try { vm.state.value } catch (_: Exception) { s }
            updateBatchConfirmEnabled(live)
        }
        batchConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            // 確定性路徑：重讀 live 狀態並核對「已確認快照」(keys+target+世代+語境)，再交由 VM 鎖內二次驗證。
            val cur = try {
                vm.state.value
            } catch (_: Exception) {
                null
            }
            val pending = pendingBatchRequest
            val snapshotMatches = cur != null && pending != null &&
                pending.generation == request.generation &&
                batchRequestMatchesLive(request, cur)
            if (cur != null && snapshotMatches && canConfirmBatch(cur, batchAcknowledged)) {
                val approved = ApprovedBatchCloneRequest(
                    targetId = request.targetId,
                    targetSerial = request.targetSerial,
                    selectedKeys = request.selectedKeys,
                )
                // 確認後立即關閉，由狀態列/清單呈現執行進度；VM 結果不復委對話框。
                cancelBatchConfirm()
                vm.cloneSelected(approved)
            } else if (cur != null) {
                // 快照/狀態不符：fail-closed，同步對話框（重建或取消），不執行。
                syncBatchDialog(pendingBatchRequest, cur)
                updateBatchConfirmEnabled(cur)
            }
        }
        batchConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            cancelBatchConfirm()
        }
        updateBatchConfirmEnabled(try { vm.state.value } catch (_: Exception) { s })
    }

    // render 每輪同步：request 為空即關閉；已顯示且同一實例只更新按鈕 enablement
    // （比照 syncRemoveDialog 的 early-return，不重建對話框）。目標或數量變化時
    // 建立新世代（ack 重置）並重建，避免摘要與實際執行目標漂移。
    private fun syncBatchDialog(request: BatchConfirmRequest?, s: UiState) {
        if (isFinishing || isDestroyed) return
        if (request == null) {
            if (batchConfirmDialog != null) {
                try {
                    batchConfirmDialog?.dismiss()
                } catch (_: Exception) {
                    // Best effort: the window may already be gone.
                }
            }
            batchConfirmDialog = null
            shownBatchRequest = null
            shownBatchWasFresh = null
            batchAcknowledged = false
            batchAcknowledgedGeneration = null
            return
        }
        // 目標/勾選不再可確認（refresh 降級/漂移、勾選清空）：請求失效，關閉對話框；
        // 禁用/目標原因由 batchResultView 顯示（不可靜默），不得殘留「建立 0 個」摘要。
        if (!verifiedTargetAvailable(s) || s.selectedKeys.isEmpty()) {
            cancelBatchConfirm()
            return
        }
        val liveSerial = s.inventory.targetSerial
        val liveKeys = s.selectedKeys.toSet()
        val liveFresh = s.inventory.freshness == InventoryFreshness.FRESH
        // PR64 P1：任一 key/目標變動（含同數量換選取）即重建並清 ack，不得沿用舊核准。
        if (s.inventory.targetId != request.targetId ||
            liveSerial != request.targetSerial ||
            liveKeys != request.selectedKeys
        ) {
            // verifiedTargetAvailable 已保證 serial 非 null 且勾選非空；此處顯式不變式。
            val verifiedSerial = checkNotNull(liveSerial) { "verified target missing serial" }
            val regenerated = request.copy(
                selectedKeys = liveKeys,
                count = liveKeys.size,
                targetId = s.inventory.targetId,
                targetSerial = verifiedSerial,
                generation = request.generation + 1,
                wasFresh = liveFresh,
            )
            batchGeneration = regenerated.generation
            batchAcknowledged = false
            batchAcknowledgedGeneration = null
            pendingBatchRequest = regenerated
            try {
                batchConfirmDialog?.dismiss()
            } catch (_: Exception) {
                // Best effort.
            }
            batchConfirmDialog = null
            shownBatchRequest = null
            shownBatchWasFresh = null
            showBatchConfirmDialog(regenerated, s)
            return
        }
        // Review #4：freshness 語境跨越 FRESH/非FRESH（含跨 recreate/尚未同步顯示）即失效，清 ack 並重建。
        if (request.wasFresh != liveFresh) {
            val regenerated = request.copy(generation = request.generation + 1, wasFresh = liveFresh)
            batchGeneration = regenerated.generation
            batchAcknowledged = false
            batchAcknowledgedGeneration = null
            pendingBatchRequest = regenerated
            try {
                batchConfirmDialog?.dismiss()
            } catch (_: Exception) {
                // Best effort.
            }
            batchConfirmDialog = null
            shownBatchRequest = null
            shownBatchWasFresh = null
            showBatchConfirmDialog(regenerated, s)
            return
        }
        if (batchConfirmDialog?.isShowing == true && shownBatchRequest == request) {
            updateBatchConfirmEnabled(s)
            return
        }
        showBatchConfirmDialog(request, s)
    }

    private fun cancelBatchConfirm() {
        try {
            batchConfirmDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort: the window may already be gone.
        }
        batchConfirmDialog = null
        pendingBatchRequest = null
        shownBatchRequest = null
        shownBatchWasFresh = null
        batchAcknowledged = false
        batchAcknowledgedGeneration = null
    }

    // 按鈕 enablement 以傳入的 live 快照為準（batchEnabled 門控＋ack）。
    private fun updateBatchConfirmEnabled(s: UiState) {
        try {
            batchConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                canConfirmBatch(s, batchAcknowledged)
            // 預確認請求可隨時中止（避免 busy/loading 卡死）；確認仍受 canConfirmBatch 門控。
            batchConfirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
            batchConfirmDialog?.setCancelable(true)
            batchConfirmDialog?.setCanceledOnTouchOutside(true)
        } catch (_: Exception) {
            // Best effort: 無障礙屬性失敗不影響顯示。
        }
    }

    private fun syncPolicyWriteDialog(
        pending: PendingPolicyWrite?,
        globalLoading: Boolean,
        policyOp: Boolean,
        policySaving: Boolean,
    ) {
        if (isFinishing || isDestroyed) return
        if (pending == null) {
            try {
                policyWriteDialog?.dismiss()
            } catch (_: Exception) {
                // Best effort: the window may already be gone.
            }
            policyWriteDialog = null
            shownPolicyWrite = null
            shownPolicyWriteSaving = false
            // 無待確認即無世代可綁定：勾選與世代一併清除，避免殘留污染下一世代。
            policyAcknowledged = false
            policyAckGeneration = null
            // Stage B P2：展開開關同步重置（同 ack，避免殘留污染下一世代）。
            policyWriteExpanded = false
            policyWriteExpandedGeneration = null
            return
        }
        if (policyWriteDialog?.isShowing == true && shownPolicyWrite == pending) {
            policyWriteDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                policyDialogCanConfirm(policySaving, policyAcknowledged)
            // Phase 5：negative 保持可點（SAVING 中點按 toast 而非靜默 no-op）；
            // Back/觸外仍以 !policySaving 門控。
            policyWriteDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
            policyWriteDialog?.setCancelable(policyDialogShouldBeCancelable(policySaving))
            policyWriteDialog?.setCanceledOnTouchOutside(policyDialogShouldBeCancelable(policySaving))
            // queued/saving 忙碌文案：全量重建（冪等），render 每幀同步。
            // Phase 5：SAVING 翻轉播報寫入中（POLITE，不洗掉 dialog 內容）。
            val wasSaving = shownPolicyWriteSaving
            try {
                policyWriteDialog?.setMessage(policyWriteMessage(pending, policyOp, policySaving))
            } catch (_: Exception) {
                // Best effort: 文案更新失敗不影響門控。
            }
            if (policySaving && !wasSaving) {
                shownPolicyWriteSaving = true
                try {
                    val announce = try {
                        getString(R.string.policy_write_saving_announce)
                    } catch (_: Exception) {
                        "政策寫入中"
                    }
                    policyWriteDialog?.window?.decorView?.announceForAccessibility(announce)
                } catch (_: Exception) {
                    // Best effort: 播報失敗不影響門控。
                }
            } else if (!policySaving) {
                shownPolicyWriteSaving = false
            }
            return
        }
        try {
            policyWriteDialog?.dismiss()
        } catch (_: Exception) {
            // Best effort.
        }
        shownPolicyWrite = pending
        shownPolicyWriteSaving = policySaving
        // 同世代（旋轉重建）保留勾選，跨世代重置（見 policyAckShouldReset 純函數；
        // 此處為 Activity 側實現，JVM 以該純函數鎖定語義）。
        if (policyAckShouldReset(policyAckGeneration, pending.generation)) {
            policyAcknowledged = false
            policyAckGeneration = null
        }
        // Stage B P2：世代重置同步重置 expanded（同 ack 契約，見
        // policyWriteExpandedShouldReset；early-return 分支不動，此處僅新 dialog）。
        if (policyWriteExpandedShouldReset(policyWriteExpandedGeneration, pending.generation)) {
            policyWriteExpanded = false
            policyWriteExpandedGeneration = null
        }
        val message = policyWriteMessage(pending, policyOp, policySaving)
        // 破壞性確認顯示完整包名以供核對（政策寫入放寬白名單，需明示對象；
        // 包名來自 catalog/PM，非使用者隱私輸入；匯出/日誌仍經遮蔽）。
        // Stage B P2（方案 A Toggle）：預設收合前 50 項＋溢出行（全量 200 行會擠出
        // ack）；rest>0 才顯示 Toggle，in-place 切換完整/收合，保 ack，播報；
        // 寫入仍用完整 pending.packages（截斷僅顯示）。
        val sorted = pending.packages.sorted()
        val hasOverflow = sorted.size > POLICY_DIALOG_PREVIEW_MAX
        // 溢出不存在時強制收合（expanded 無意義，Toggle 隱藏）。
        val effectiveExpanded = policyWriteExpanded && hasOverflow
        val listViews = buildPolicyListView(sorted, effectiveExpanded)
        val packagesText = listViews.packagesText
        val scrollView = listViews.scrollView
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Phase 5 a11y：dialog 內容區 POLITE（TalkBack 跟隨訊息/相位變化）。
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // Stage B P2：包名超過 POLICY_DIALOG_LIST_INLINE_MAX 項時 ScrollView 限高
            // policyDialogListMaxHeightDp（小螢幕回 SMALL，否則 NORMAL），
            // ack checkbox 永遠可見；少量時 WRAP_CONTENT 不留空。
            val maxHeightDp = currentPolicyDialogListMaxHeightDp()
            val listParams = if (sorted.size > POLICY_DIALOG_LIST_INLINE_MAX) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(maxHeightDp),
                )
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            addView(scrollView, listParams)
        }
        // Stage B P2 Toggle：僅 rest>0（即 hasOverflow）顯示；in-place 切換保 ack。
        var toggleBtn: Button? = null
        if (hasOverflow) {
            val initialLabel = policyWriteToggleLabel(effectiveExpanded, sorted.size, POLICY_DIALOG_PREVIEW_MAX)
            toggleBtn = Button(this).apply {
                text = initialLabel
                minimumHeight = dp(48)
                contentDescription = initialLabel
            }
            container.addView(
                toggleBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            val toggleRef = toggleBtn
            toggleRef.setOnClickListener {
                val newExpanded = !policyWriteExpanded
                policyWriteExpanded = newExpanded
                policyWriteExpandedGeneration = if (newExpanded) pending.generation else null
                // in-place 切換：重算可見列＋CD＋按鈕文案＋播報，不重建 dialog，保 ack。
                try {
                    val (newShown, _) = policyWriteVisibleList(sorted, newExpanded)
                    packagesText.text = buildPolicyListText(sorted, newShown)
                    scrollView.contentDescription = policyWriteListSummary(sorted.size, newShown.size, newExpanded)
                    // Toggle 文案的計數語義：show_less 的 %1$d 為收合預覽數（50），
                    // show_all/expanded 播報的 %1$d 為總數，collapsed 播報為預覽數。
                    val previewSize = minOf(sorted.size, POLICY_DIALOG_PREVIEW_MAX)
                    val newLabel = policyWriteToggleLabel(newExpanded, sorted.size, previewSize)
                    toggleRef.text = newLabel
                    try {
                        toggleRef.contentDescription = newLabel
                    } catch (_: Exception) {
                    }
                    try {
                        scrollView.scrollTo(0, 0)
                    } catch (_: Exception) {
                    }
                    val announce = policyWriteToggleAnnounce(newExpanded, sorted.size, previewSize)
                    try {
                        scrollView.announceForAccessibility(announce)
                    } catch (_: Exception) {
                        // Best effort.
                    }
                } catch (_: Exception) {
                    // Best effort: 切換失敗不影響門控與 ack。
                }
            }
        }
        val acknowledgement = CheckBox(this).apply {
            setText(R.string.policy_write_acknowledge)
            val d = resources.displayMetrics.density
            val hPad = (CONFIRM_ACK_HPAD_DP * d).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = (CONFIRM_ACK_MIN_HEIGHT_DP * d).toInt()
            isChecked = policyAcknowledged
        }
        // 把 ack checkbox 放在 scrollable list 之下。
        container.addView(
            acknowledgement,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        try {
            policyWriteDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.policy_write_title))
                .setMessage(message)
                .setView(container)
                .setCancelable(!policySaving)
                .setPositiveButton(getString(R.string.policy_write_positive), null)
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .setOnCancelListener { vm.cancelPolicyWrite(pending) }
                .show()
        } catch (_: Exception) {
            policyWriteDialog = null
            shownPolicyWrite = null
            shownPolicyWriteSaving = false
            return
        }
        acknowledgement.setOnCheckedChangeListener { _, checked ->
            policyAcknowledged = checked
            policyAckGeneration = if (checked) pending.generation else null
            // 確定性路徑：用本次 sync 傳入的 render 幀 policySaving（同 remove dialog
            // 的 loading/blockKey 模式），不即時重讀 live 狀態；loading 變化由
            // 下一次 render 的 early-return 同步，真正寫入門控在 Positive 點擊
            // 即時重讀＋VM confirmPolicyWrite 世代守衛雙層 fail-closed。
            policyWriteDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                policyDialogCanConfirm(policySaving, checked)
        }
        policyWriteDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            // 點擊瞬間值：即時重讀 live state（非 render 幀 stale 值），SAVING/
            // RECONCILING 才禁用（QUEUED 可確認，避免 request 即 QUEUED 導致恆
            // 禁用死鎖；核對中請勿重試）；世代守衛在 VM。
            val curPolicySaving = try {
                val ph = vm.state.value.policyWritePhase
                ph == PolicyWritePhase.SAVING || ph == PolicyWritePhase.RECONCILING
            } catch (_: Exception) {
                policySaving
            }
            if (policyDialogCanConfirm(curPolicySaving, policyAcknowledged)) vm.confirmPolicyWrite(pending)
        }
        // Phase 5：SAVING 中取消改為可感知 toast（前身靜默 no-op）：按鈕保持可點，
        // 點按時即時重讀 phase，SAVING/RECONCILING 即 toast「寫入中，無法取消」
        // 且不調 VM；queued 才調 vm.cancelPolicyWrite（維持 P1 #1 語義）。
        policyWriteDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            val savingNow = try {
                val ph = vm.state.value.policyWritePhase
                ph == PolicyWritePhase.SAVING || ph == PolicyWritePhase.RECONCILING
            } catch (_: Exception) {
                policySaving
            }
            if (savingNow) {
                val denied = try {
                    getString(R.string.policy_write_cancel_denied)
                } catch (_: Exception) {
                    "寫入中，無法取消"
                }
                try {
                    Toast.makeText(this, denied, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    // Best effort: toast 失敗不影響門控。
                }
            } else {
                vm.cancelPolicyWrite(pending)
            }
        }
        policyWriteDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            policyDialogCanConfirm(policySaving, policyAcknowledged)
        // Phase 5：negative 保持可點（見上 toast），Back/觸外仍以 !saving 門控。
        policyWriteDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
        policyWriteDialog?.setCancelable(policyDialogShouldBeCancelable(policySaving))
        policyWriteDialog?.setCanceledOnTouchOutside(policyDialogShouldBeCancelable(policySaving))
        // Phase 5：初建即 SAVING 時播報一次（early-return 分支覆蓋後續翻轉）。
        if (policySaving) {
            try {
                val announce = try {
                    getString(R.string.policy_write_saving_announce)
                } catch (_: Exception) {
                    "政策寫入中"
                }
                policyWriteDialog?.window?.decorView?.announceForAccessibility(announce)
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    // Stage B P2 完整清單視圖（方案 A Toggle 抽取）：依 expanded 決定可見列＋CD。
    // - 收合：前 POLICY_DIALOG_PREVIEW_MAX 項＋溢出行，CD 走 list_cd_collapsed；
    // - 展開：完整清單無溢出行，CD 走 list_cd（完整）。
    // 資源缺失 fail-closed 回中文 fallback（見純函數 policyWriteListCdFallback）。
    private data class PolicyListViews(
        val scrollView: ScrollView,
        val packagesText: TextView,
    )

    private fun buildPolicyListView(sorted: List<String>, expanded: Boolean): PolicyListViews {
        val (shown, _) = policyWriteVisibleList(sorted, expanded)
        val packagesText = TextView(this).apply {
            text = buildPolicyListText(sorted, shown)
            // 無 contentDescription：摘要由外層 ScrollView 承載，內外重複會致
            // TalkBack 朗讀兩次。
            setTextIsSelectable(true)
        }
        val summary = policyWriteListSummary(sorted.size, shown.size, expanded)
        val scrollView = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = summary
            addView(
                packagesText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return PolicyListViews(scrollView, packagesText)
    }

    private fun buildPolicyListText(sorted: List<String>, shown: List<String>): String {
        val rest = sorted.size - shown.size
        val lines = ArrayList<String>(shown.size + 1)
        lines.addAll(shown)
        if (rest > 0) {
            val more = try {
                resources.getQuantityString(
                    R.plurals.policy_write_preview_more,
                    sorted.size,
                    sorted.size,
                    shown.size,
                )
            } catch (_: Exception) {
                "…等共 ${sorted.size} 項，僅顯示前 ${shown.size} 項"
            }
            lines.add(more)
        }
        return lines.joinToString("\n")
    }

    private fun policyWriteListSummary(total: Int, shownSize: Int, expanded: Boolean): String {
        val rest = total - shownSize
        return if (expanded || rest <= 0) {
            try {
                getString(R.string.policy_write_list_cd, total)
            } catch (_: Exception) {
                policyWriteListCdFallback(total, shownSize, true)
            }
        } else {
            try {
                getString(R.string.policy_write_list_cd_collapsed, total, shownSize)
            } catch (_: Exception) {
                policyWriteListCdFallback(total, shownSize, false)
            }
        }
    }

    private fun policyWriteToggleLabel(expanded: Boolean, total: Int, shownSize: Int): String {
        return try {
            if (expanded) getString(R.string.policy_write_show_less, shownSize)
            else getString(R.string.policy_write_show_all, total)
        } catch (_: Exception) {
            policyWriteToggleLabelFallback(expanded, total, shownSize)
        }
    }

    private fun policyWriteToggleAnnounce(expanded: Boolean, total: Int, shownSize: Int): String {
        return try {
            if (expanded) getString(R.string.policy_write_expanded_announce, total)
            else getString(R.string.policy_write_collapsed_announce, shownSize)
        } catch (_: Exception) {
            policyWriteToggleAnnounceFallback(expanded, total, shownSize)
        }
    }

    private fun currentPolicyDialogListMaxHeightDp(): Int {
        return try {
            val cfg = resources.configuration
            policyDialogListMaxHeightDp(cfg.smallestScreenWidthDp, cfg.screenHeightDp)
        } catch (_: Exception) {
            POLICY_DIALOG_LIST_MAX_HEIGHT_DP
        }
    }

    // 政策寫入 dialog 訊息（含 dropped 與 busy 後綴；皆經資源解析，JVM 縫合語義
    // 由純函數 policyWriteBusySuffix/truncatePolicyPreview 鎖定）。
    // - 主模板走 R.plurals.policy_write_message（Phase 5：前身 <string> 帶無用 %2$s，
    //   現移除 %2$s，清單另以 ScrollView 顯示，僅留計數 %1$d；中英 plurals 化）。
    // - dropped 後綴走 R.plurals.policy_dropped（前身硬編碼拼接，現資源化）。
    // - busy 後綴走 R.string.policy_write_busy_*（前身僅純函數硬編碼，生產改資源；
    //   純函數保留為 JVM seam，文案與 zh 資源同字）。
    private fun policyWriteMessage(
        pending: PendingPolicyWrite,
        policyOp: Boolean,
        policySaving: Boolean,
    ): String {
        var message = try {
            resources.getQuantityString(
                R.plurals.policy_write_message,
                pending.packages.size,
                pending.packages.size,
            )
        } catch (_: Exception) {
            "政策寫入（${pending.packages.size} 項）"
        }
        if (pending.droppedCount > 0) {
            val droppedSuffix = try {
                resources.getQuantityString(
                    R.plurals.policy_dropped,
                    pending.droppedCount,
                    pending.droppedCount,
                )
            } catch (_: Exception) {
                "已捨去 ${pending.droppedCount} 項不在來源清單的勾選"
            }
            message += "\n$droppedSuffix"
        }
        val busySuffix = try {
            if (policySaving) getString(R.string.policy_write_busy_saving)
            else if (policyOp) getString(R.string.policy_write_busy_queued)
            else ""
        } catch (_: Exception) {
            policyWriteBusySuffix(policyOp, policySaving)
        }
        message += busySuffix
        return message
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
    // Phase 3 P2 perf: catalog 內容 hash 快取 (referential identity; 同一實例
    // O(1))。labelCacheKey (no-arg seam 供 dialog/測試) 與 buildSourceListKey/
    // render 共用；引用未變直接回快取，行為不變 (hashCode 值語意相同)。
    private fun catalogHashCached(catalog: List<SourceAppEntry>): Int {
        return try {
            val ref = lastCatalogHashRef
            if (ref !== null && ref === catalog) return lastCatalogHash
            val h = catalog.hashCode()
            lastCatalogHashRef = catalog
            lastCatalogHash = h
            h
        } catch (_: Exception) {
            0
        }
    }

    private fun labelCacheKey(): Int = try {
        catalogHashCached(vm.state.value.sourceCatalog)
    } catch (_: Exception) {
        0
    }

    // Phase I I1：只清 cache，不清 labelCallbacks（在途合併中回調仍有效；失效後重填走非同步 IO）。
    // Phase 1a（P3 N4）：主執行緒專用契約（labelCache／labelInflight／labelCallbacks 皆主線觸碰）。
    private fun syncLabelCache(catalogKey: Int) {
        check(Looper.getMainLooper().isCurrentThread) { "syncLabelCache must run on main thread" }
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
    // Phase 1a（P3 N4）：主執行緒專用契約。
    private fun cacheLabel(pkg: String, label: String) {
        check(Looper.getMainLooper().isCurrentThread) { "cacheLabel must run on main thread" }
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
    // 失敗不快取（包可能稍後出現）；同 epoch 失敗清 inflight＋callbacks（drop 不回調，避免洩漏），
    // 在途 catalog 變化且有新世代 waiter 則重掛＋重發一次 IO（見完成段 RELAUNCH_FRESH 臂）。
    // 分身列表規模小（非 200 來源列），不另設 Semaphore。
    // 全部主執行緒觸碰（head 同步＋完成回主線）；syncLabelCache 清 cache 不清 callbacks。
    // Phase 1a：waiter 攜註冊 epoch；完成經 arbitrateLabelCompletion 統一仲裁
    // （見完成段：同 epoch 成功 FANOUT／其餘 DROP・SERVE 快取・RELAUNCH fresh 重發；
    // stale 路徑永不派發舊 pm label）。同 epoch 成功語義不變（無條件 rebind＋全量 fanout）。
    private fun requestLabelAsync(pkg: String, onLoaded: (String) -> Unit) {
        // Phase 1a（P3 N4）：主執行緒專用契約（head＋完成皆主線觸碰 labelCache／inflight／callbacks）。
        check(Looper.getMainLooper().isCurrentThread) { "requestLabelAsync must run on main thread" }
        // Phase 1a（P2 世代守衛）：head 在主線 capture catalog epoch，與 pkg 一併作為在途鍵。
        val reqEpoch = labelCacheCatalogKey
        try {
            if (labelCache.containsKey(pkg)) return
            if (pkg in labelInflight) {
                labelCallbacks.getOrPut(pkg) { mutableListOf() }.add(reqEpoch to onLoaded)
                return
            }
            labelInflight.add(pkg)
            labelCallbacks.getOrPut(pkg) { mutableListOf() }.add(reqEpoch to onLoaded)
        } catch (_: Exception) {
            return
        }
        // Phase 1a（CHANGES-REQUESTED）：IO 發起段抽為 local fun，launch 時重取當代 epoch；
        // 失敗路徑若在途發生 catalog 變化且有新世代 waiter，重掛＋重發一次 IO，避免新世代
        // waiter 被舊失敗靜默丟棄。同 epoch 失敗語義不變（全丟，下次 render 重註冊）。
        fun launchLabelLookup() {
            val launchEpoch = labelCacheCatalogKey
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
            // Phase A (P2-1 stale-success)：統一仲裁——快照 waiter、在移除後取樣
            // freshCacheHit（B-render 可能經 catalogLabelsFor＋cacheLabel 在 IO 與
            // 完成之間預填），再執行恰一臂。stale 路徑永不派發舊 pm label：
            // 無 fresh→DROP；有 fresh 且快取命中→SERVE 快取；否則重掛 fresh＋重發
            // 一次 IO（RELAUNCH，launchLabelLookup 內重捕 fresh epoch）。
            // 同 epoch 成功路徑語義不變（FANOUT：cache＋rebind＋全量 fanout）。
            val pmSuccess = label != null && label != pkg
            val currentEpoch = labelCacheCatalogKey
            val snapshot: List<Pair<Int?, (String) -> Unit>> = try {
                labelCallbacks.remove(pkg) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val waiterEpochs: List<Int?> = try {
                snapshot.map { it.first }
            } catch (_: Exception) {
                emptyList()
            }
            val freshCacheHit: Boolean = try {
                labelCache.containsKey(pkg)
            } catch (_: Exception) {
                false
            }
            when (arbitrateLabelCompletion(launchEpoch, currentEpoch, waiterEpochs, pmSuccess, freshCacheHit)) {
                LabelCompletionAction.FANOUT_SAME_EPOCH -> {
                    val ok = label
                    if (ok == null || ok == pkg) return@launch
                    cacheLabel(pkg, ok)
                    // Phase I I2：無條件重綁同 pkg 行（tag 精確），覆蓋各 waiter 防禦性 return 極窄窗口。
                    try {
                        rebindClonedLabel(pkg, ok)
                    } catch (_: Exception) {
                        // Best effort: 重綁失敗不影響快取與各回調（下次同步命中）。
                    }
                    for ((_, cb) in snapshot) {
                        try {
                            cb(ok)
                        } catch (_: Exception) {
                            // Best effort: 單個重綁失敗不影響快取與其餘 waiter（下次同步命中）。
                        }
                    }
                }
                LabelCompletionAction.DROP_ALL -> {
                    // 同 epoch 失敗沿舊語義全丟（cache 仍 miss，下次 render 重註冊）；
                    // stale 無 fresh waiter 亦全丟：不觸 cache／rebind／回調／重發。
                    return@launch
                }
                LabelCompletionAction.SERVE_FROM_CACHE -> {
                    val cached: String? = try {
                        labelCache[pkg]
                    } catch (_: Exception) {
                        null
                    }
                    val fresh = try {
                        snapshot.filter { it.first == currentEpoch }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (cached == null || cached == pkg || fresh.isEmpty()) {
                        // 快取競失（主線無掛起理論不可達；防禦）：保活重發 fresh，
                        // 永不回退為派發舊 pm label。
                        if (fresh.isNotEmpty()) {
                            try {
                                labelInflight.add(pkg)
                            } catch (_: Exception) {
                                // Best effort.
                            }
                            try {
                                labelCallbacks.getOrPut(pkg) { mutableListOf() }.addAll(fresh)
                            } catch (_: Exception) {
                                try {
                                    labelInflight.remove(pkg)
                                } catch (_: Exception) {
                                    // Best effort.
                                }
                                return@launch
                            }
                            launchLabelLookup()
                        }
                        return@launch
                    }
                    try {
                        rebindClonedLabel(pkg, cached)
                    } catch (_: Exception) {
                        // Best effort: 重綁失敗不影響快取與各回調（下次同步命中）。
                    }
                    for ((_, cb) in fresh) {
                        try {
                            cb(cached)
                        } catch (_: Exception) {
                            // Best effort: 單個重綁失敗不影響快取與其餘 waiter（下次同步命中）。
                        }
                    }
                }
                LabelCompletionAction.RELAUNCH_FRESH -> {
                    // 在途 catalog 變化（A→B）且有新世代 waiter：重掛 fresh waiter
                    // 並重發一次 IO；舊 pm label 丟棄（不 cache／不 rebind／不回調）。
                    val fresh = try {
                        snapshot.filter { it.first == currentEpoch }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (fresh.isEmpty()) return@launch
                    try {
                        labelInflight.add(pkg)
                    } catch (_: Exception) {
                        // Best effort.
                    }
                    try {
                        labelCallbacks.getOrPut(pkg) { mutableListOf() }.addAll(fresh)
                    } catch (_: Exception) {
                        try {
                            labelInflight.remove(pkg)
                        } catch (_: Exception) {
                            // Best effort.
                        }
                        return@launch
                    }
                    launchLabelLookup()
                }
            }
            }
        }
        launchLabelLookup()
    }

    private fun isLabelCached(pkg: String): Boolean = try {
        labelCache.containsKey(pkg)
    } catch (_: Exception) {
        false
    }

    // Phase F F1：分身行 label 回填 fallback（發起行已 detach 時走容器按 tag 查找）。
    // 同行內按 tag 更新：title（tag=pkg）設文字，badge（tag=badge:pkg）與
    // 移除鈕（tag=remove:pkg）更新 contentDescription；找不到丟棄（快取保留）。
    // 移除鈕為 Button（TextView 子類），故 title 查找另加型別守衛（title 建時只給
    // 純 TextView 設 tag=pkg，移除鈕的 tag 為 remove:pkg）。
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
                if (title is Button) continue
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
                    // CD action 保持完整語意（R.string.action_remove）。
                    row.findViewWithTag<Button>("remove:$pkg")?.contentDescription =
                        getString(R.string.remove_row_cd, getString(R.string.action_remove), label)
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    // 相容舊同步入口：僅主執行緒快取快讀（miss 回 pkg，不打 PM binder）。
    // Phase 1a（CHANGES-REQUESTED）：背景同步 PM 分支已刪除——syncLabelCache／cacheLabel
    // 皆有主執行緒契約，背景直呼必拋（且全 repo 零呼叫端）；保留簽名供未來主線呼叫。
    private fun resolveAppLabel(pkg: String): String = resolveAppLabelCached(pkg)

    // PR-7 P2 (#26):來源 catalog 載入 (授權範圍唯讀)。
    // 包到 MainActivity 的生命週期:IO 執行緒讀 PackageManager,回主執行緒注入
    // ViewModel。serial 取自身 user (UserManager,失敗回退 0);AppKey =
    // serial:package (見 SourceInventory)。
    // PR-37 P3:失敗不清空（保留舊列表，不呼叫 setSourceCatalog），錯誤走獨立
    // 文案 catalogLoadError（與「尚未載入」區分）。
    // Phase 3 P3 N5: 在途守衛——onCreate+onResume 連續觸發時若已有掃描在途
    // 直接共用，不取消重掃 (避免兩次全量 PM 掃描併發；同範圍重載結果等價，
    // 寫入仍 last-writer-wins，語義不變)。
    private fun reloadCatalog() {
        if (catalogJob?.isActive == true) return
        catalogJob = lifecycleScope.launch {
            val apps: List<SourceAppEntry>? = withContext(Dispatchers.IO) {
                try {
                    loadSourceApps(packageManager, selfSerial())
                } catch (e: CancellationException) {
                    // catalogJob 取消透傳 (loadSourceApps 內層同語義,不吞為 null)。
                    throw e
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
        // Stage1 sticky bar：勾選數永遠可見（含 0 項）。
        batchSelCountView.text = resources.getQuantityString(
            R.plurals.batch_selected_count,
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
        val hasReport = s.batchReport?.items?.isNotEmpty() == true
        val reportText = if (hasReport) batchReportTextLocalized(s.batchReport) else ""
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
        // P2/P3：sticky bar 顯示簡短原因/摘要（捲動時仍可見）；完整明細僅在 batchResultView。
        // a11y：避免朗讀 50 筆逐項明細，改為單句摘要/原因 + 前往詳情提示。
        if (::batchBarStatusView.isInitialized) {
            try {
                if (newBatchText.isBlank()) {
                    batchBarStatusView.visibility = View.GONE
                    batchBarStatusView.text = ""
                    batchBarStatusView.contentDescription = null
                } else {
                    val stickyText = if (hasReport) {
                        batchReportSummaryLocalized(s.batchReport)
                    } else {
                        newBatchText
                    }
                    val detailHint = try { getString(R.string.batch_bar_go_to_detail) } catch (_: Exception) { "" }
                    batchBarStatusView.text = stickyText
                    batchBarStatusView.contentDescription =
                        if (detailHint.isNotEmpty()) "$stickyText。$detailHint" else stickyText
                    batchBarStatusView.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                // Best effort.
            }
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
                    // Phase 3 P2 a11y: 48dp touch target via minimumHeight
                    // (height stays WRAP_CONTENT so large fonts grow).
                    minimumHeight = dp(48)
                    // p26-2 Phase 3：顯式 8dp compound padding，固定 24dp 圖示與文字／勾選框間距，
                    // 避免 OEM 預設漂移；只改 compound padding，不動行高／基線。
                    setCompoundDrawablePadding(dp(8))
                    // TODO(RTL, pre-existing)：現用絕對 setCompoundDrawables（左圖），未來如需鏡像可改
                    // setCompoundDrawablesRelative；本 PR 不改。
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
    // Phase 3 P2 perf: catalog hash 經 referential 快取 (catalogHashCached)，
    // 引用未變 O(1)，行為不變。
    private fun buildSourceListKey(s: UiState): String =
        "${catalogHashCached(s.sourceCatalog)}:" +
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
    // p26-2 Phase 1（4106 修補）：透明占位，永不回傳快取實例本身；新 ColorDrawable
    // 本身即獨立實例（每次新建 ColorDrawable 即獨立實例，無需 mutate），sizePx 經 maxOf 1 保證可見（非 0 尺寸）。
    private fun transparentPlaceholder(sizePx: Int): Drawable {
        val safePx = safeIconPx(sizePx)
        val ph = ColorDrawable(0) // 0 = 完全透明。
        try {
            ph.setBounds(0, 0, safePx, safePx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "transparentPlaceholder bounds failed", e)
        }
        return ph
    }

    // p26-2 Phase 1（4106 修補）：取 fresh copy（constantState.newDrawable().mutate()，
    // mutate 僅在 newDrawable 新實例上做狀態解離；newDrawable 失敗直接佔位，
    // 不經 mutate 共用實例）。永不回傳快取實例本身，失敗直接以透明占位兜底；
    // ImageView 呼叫端由固定 layout 尺寸縮放，不經 sizedIcon setBounds。
    // 為何不快取 sized 版：快取 sized 結果需 appKey+sizePx 聯合鍵，避免雙尺寸（如 24/36）
    // 共用實例覆蓋 bounds；目前每次 newDrawable 共享 Bitmap 不翻倍，無需快取。
    private fun freshIconDrawable(cached: Drawable): Drawable {
        val fresh: Drawable? = try {
            cached.constantState?.newDrawable()?.mutate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "freshIconDrawable failed", e)
            null
        }
        if (!shouldUsePlaceholder(fresh != null)) return fresh!!
        // 最後防線：方形 max(w,h) 透明占位（intrinsic 尺寸，maxOf 1；讀取異常以 -1
        // 補齊（舊以 1 補齊，經 placeholderDim 的 maxOf(1,…) 後等價）；
        // 每次新建 ColorDrawable 即獨立實例，無需 mutate）。尺寸委派
        // IconSizingPolicy.placeholderDim（方形 max 保底，JVM 可測）。
        val w = try {
            cached.intrinsicWidth
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "freshIconDrawable intrinsicWidth failed", e)
            -1
        }
        val h = try {
            cached.intrinsicHeight
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "freshIconDrawable intrinsicHeight failed", e)
            -1
        }
        return transparentPlaceholder(placeholderDim(w, h)) // 方形 max(w,h) 占位。
    }

    // p26-2 Phase 1：供 CheckBox 左圖用的統一尺寸 helper。取 fresh copy
    // （constantState.newDrawable().mutate()，mutate 僅在 newDrawable 新實例上做狀態解離；
    // newDrawable 失敗直接佔位，不經 mutate 共用實例），再 setBounds 強制
    // 為 sizePx x sizePx；呼叫端用 setCompoundDrawables（非 IntrinsicBounds 版），
    // 避免直接吃各 App getApplicationIcon() 的原生 intrinsic 尺寸。
    // fallback 語義：永不回傳快取實例本身——copy 失敗回 sizePx x sizePx 透明占位；
    // setBounds 拋出時直接回 sizePx 透明占位（保持統一尺寸），絕不回傳 0 尺寸不可見圖。
    // 為何不快取 sized 版：需 appKey+sizePx 聯合鍵，避免雙尺寸共用實例覆蓋 bounds；
    // 目前每次 newDrawable 共享 Bitmap 不翻倍，故不快取。
    // adaptive 局限：bounds 統一（24/36）≠ 可見 glyph 統一；AdaptiveIconDrawable 以
    // SAFEZONE_SCALE=66/72 把 108dp 圖層縮到 viewport 內，24dp 外框對應安全區名義上約 22dp；
    // 可見 glyph 仍由圖檔、留白與 mask 決定，不能由此固定折算成更小的確定百分比，
    // 也不宜一律額外放大。保留「統一 bounds 不保證可見 glyph 等大，需視覺驗證」，
    // 目前按 bounds 統一收斂，視覺走查確認。24 vs 36 取捨：來源 24dp 密度高、分身 36dp 獨立位圖、
    // 行高 48dp／44dp 容器兜底。
    private fun sizedIcon(drawable: Drawable, sizePx: Int): Drawable {
        if (sizePx <= 0) return freshIconDrawable(drawable)
        val copied: Drawable? = try {
            drawable.constantState?.newDrawable()?.mutate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sizedIcon copy failed", e)
            null
        }
        if (shouldUsePlaceholder(copied != null)) return transparentPlaceholder(sizePx)
        val fresh: Drawable = copied!!
        return try {
            fresh.setBounds(0, 0, sizePx, sizePx)
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sizedIcon setBounds failed", e)
            transparentPlaceholder(sizePx)
        }
    }

    // Phase A（P2-1/P2-2 快照根治，Hybrid）：以受控尺寸位圖快照根治
    // 「無 ConstantState 直接透明」（P2-1）與「只 setBounds 拉伸」（P2-2）：在
    // IO 執行緒把需快照的圖示以等比置中繪製到獨立方形位圖，產出普通
    // BitmapDrawable 使後續 freshIconDrawable／sizedIcon 的 newDrawable 必
    // 成功。dstPx 先以 clampSnapshotPx(dp(36), ICON_SNAPSHOT_CAP_PX) 規範化
    //（dp 經 dpPx，CAP 常量 192）；intrinsic<=0 以 dst 兜底為 effW/effH（P3-1），
    // 建 ARGB_8888 方形位圖後以 Canvas translate/scale（用 fitScale／
    // fitCenterRect）做等比，src 以 setBounds(0,0,effW,effH) 繪製（勿只
    // setBounds），最後 BitmapDrawable + setBounds(0,0,dst,dst) 回傳；畫前存
    // Rect(src.bounds)，finally 一律還原（含 CE／OOM／異常，還原自帶 CE 重拋＋
    // Log.w），成功亦不影響出口 bounds（P3-2）；全程 CE 重拋，捕 OOM 回
    // null+Log.w，失敗回 null 由呼叫端按 hasConstantState 分流（無 CS 記占位、
    // 其餘記 raw）。Canvas draw 必須在 IO 執行緒且未掛 View，呼叫端已在
    // withContext(IO) 內呼叫。
    private fun bitmapSnapshotDrawable(src: Drawable, dstPx: Int): Drawable? {
        val dst = clampSnapshotPx(dp(36), ICON_SNAPSHOT_CAP_PX)
        if (dstPx > 0 && dstPx != dp(36)) {
            Log.w(TAG, "bitmapSnapshotDrawable dstPx drift")
        }
        val srcW = try {
            src.intrinsicWidth
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bitmapSnapshotDrawable intrinsicWidth failed", e)
            return null
        }
        val srcH = try {
            src.intrinsicHeight
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bitmapSnapshotDrawable intrinsicHeight failed", e)
            return null
        }
        val effW = if (srcW <= 0) dst else srcW
        val effH = if (srcH <= 0) dst else srcH
        val bmp: Bitmap = try {
            Bitmap.createBitmap(dst, dst, Bitmap.Config.ARGB_8888)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "bitmapSnapshotDrawable OOM", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "bitmapSnapshotDrawable createBitmap failed", e)
            return null
        }
        val savedBounds: Rect? = try {
            Rect(src.bounds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bitmapSnapshotDrawable bounds read failed", e)
            null
        }
        try {
            val canvas = Canvas(bmp)
            val rect = fitCenterRect(effW, effH, dst)
            val scale = fitScale(effW, effH, dst)
            canvas.translate(rect.left.toFloat(), rect.top.toFloat())
            canvas.scale(scale, scale)
            src.setBounds(0, 0, effW, effH)
            src.draw(canvas)
            val out = BitmapDrawable(resources, bmp)
            out.setBounds(0, 0, dst, dst)
            return out
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "bitmapSnapshotDrawable OOM", e)
            try {
                bmp.recycle()
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                Log.w(TAG, "bitmapSnapshotDrawable recycle failed", e2)
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "bitmapSnapshotDrawable draw failed", e)
            try {
                bmp.recycle()
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                Log.w(TAG, "bitmapSnapshotDrawable recycle failed", e2)
            }
            return null
        } finally {
            if (savedBounds != null) {
                try {
                    src.setBounds(savedBounds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "bitmapSnapshotDrawable restore bounds failed", e)
                }
            }
        }
    }

    // p26-2 Phase 2：合併 loadIcon acquire 失敗雙分支共用的 inflight 清理
    // （CE 重拋供協作取消、其餘 Log.w 不吞），避免兩份 try/catch 重複。
    private fun removeIconInflight(appKey: String) {
        try {
            iconInflight.remove(appKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "loadIcon inflight remove failed", e)
        }
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
            } catch (e: CancellationException) {
                removeIconInflight(appKey)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadIcon acquire failed", e)
                removeIconInflight(appKey)
                return@launch
            }
            try {
                val drawable = withContext(Dispatchers.IO) {
                    val rawDrawable = try {
                        packageManager.getApplicationIcon(pkg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "loadIcon getApplicationIcon failed", e)
                        null
                    }
                    if (rawDrawable == null) {
                        null
                    } else {
                        // Phase A 快照根治：IO 執行緒內以 shouldUseSnapshot(normalized)
                        // 判斷是否快照；normalized 為 hasConstantState（constantState
                        // 安全讀取）、intrinsicW/H（讀取異常以 -1 補齊，CE 重拋）與
                        // isBitmapNonFillGravity（raw is BitmapDrawable && raw.gravity
                        // != Gravity.FILL 安全讀取，CE 重拋、其餘 Log.w 以 false 補齊）。
                        // 需快照時在 IO 執行緒以 bitmapSnapshotDrawable 繪製獨立位圖
                        //（Canvas 未掛 View，快照接線不改 Canvas 變換：bounds==bitmap 大小
                        // 時 CENTER 與 FILL 同 rect）；快照成功快取快照值。取捨：無
                        // ConstantState 的 raw 經 fresh／sized 必走占位，快照失敗記
                        // transparentPlaceholder(dp(36)) 而非 raw，避免快取永遠無法
                        // 複製的 raw；其餘快照失敗快取原始 drawable（不直接透明）。
                        // 既有 evict/markLoaded/onLoaded 不變。
                        val hasConstantState = try {
                            rawDrawable.constantState != null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "loadIcon constantState check failed", e)
                            false
                        }
                        val intrinsicW = try {
                            rawDrawable.intrinsicWidth
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "loadIcon intrinsicWidth failed", e)
                            -1
                        }
                        val intrinsicH = try {
                            rawDrawable.intrinsicHeight
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "loadIcon intrinsicHeight failed", e)
                            -1
                        }
                        val isBitmapNonFillGravity = try {
                            if (rawDrawable is BitmapDrawable) rawDrawable.gravity != Gravity.FILL else false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "loadIcon gravity check failed", e)
                            false
                        }
                        if (shouldUseSnapshot(hasConstantState, intrinsicW, intrinsicH, isBitmapNonFillGravity)) {
                            bitmapSnapshotDrawable(rawDrawable, dp(36)) ?: if (!hasConstantState) transparentPlaceholder(dp(36)) else rawDrawable
                        } else {
                            rawDrawable
                        }
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "loadIcon release failed", e)
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
            try {
                box.setCompoundDrawables(sizedIcon(cached, dp(SOURCE_ICON_DP)), null, null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "requestIcon checkbox paste failed", e)
            }
            return
        }
        loadIcon(pkg, appKey) { drawable ->
            // 先定位目標列，再產生 sizedIcon（sizedIcon 內部保證永不回傳快取實例／非空）；
            // 發起列仍掛載（tag＋parent 雙符合）則直貼；直貼拋出或該列已重建時，
            // 都在當前容器內按 tag 找同一 key 的可見列重繪（找不到＝已過濾掉／換 catalog，不貼）。
            var attached = false
            try {
                if (box.tag == appKey && box.parent === sourceContainer) {
                    try {
                        box.setCompoundDrawables(sizedIcon(drawable, dp(SOURCE_ICON_DP)), null, null, null)
                        attached = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "requestIcon direct paste failed", e)
                        // 直貼失敗繼續走容器掃描找新行補圖，不吞掉。
                    }
                }
                if (!attached) {
                    for (i in 0 until sourceContainer.childCount) {
                        val child = sourceContainer.getChildAt(i)
                        if (child is CheckBox && child.tag == appKey) {
                            // sizedIcon 自帶 fallback，無需呼叫端再包 dead try/catch。
                            try {
                                child.setCompoundDrawables(sizedIcon(drawable, dp(SOURCE_ICON_DP)), null, null, null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "requestIcon container paste failed", e)
                            }
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "requestIcon refill failed", e)
                // Best effort: 容器未就緒（測試路徑）時不阻塞圖示快取。
            }
            // Phase B B2 跨列表共享 iconInflight：來源完成時分身容器也嘗試重綁，
            // 避免另一列表（同 appKey 先因 inflight 合併而未發起）永久無圖。
            try {
                rebindClonedIcon(appKey, drawable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "requestIcon rebindCloned failed", e)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "requestIcon tag failed", e)
        }
        val cached = iconDrawables[appKey]
        if (cached != null) {
            iconDrawables.remove(appKey)
            iconDrawables[appKey] = cached
            try {
                imageView.setImageDrawable(freshIconDrawable(cached))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "requestIcon image paste failed", e)
            }
            return
        }
        val capturedGen = gen
        loadIcon(pkg, appKey) { drawable ->
            // Phase I I3：先跨容器/同容器重綁（tag 精確，找不到丟棄），再做 gen 門。
            try {
                rebindClonedIcon(appKey, drawable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "requestIcon rebindCloned failed", e)
            }
            // Phase B B2 跨列表共享 iconInflight：分身完成時來源容器也嘗試重綁。
            try {
                rebindSourceIcon(appKey, drawable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "requestIcon rebindSource failed", e)
            }
            if (capturedGen != clonedGen) return@loadIcon
            try {
                if (isUnderClonedContainer(imageView) && imageView.tag == appKey) {
                    // 僅在 attach 時複製，避免 detach 時浪費一次複製。
                    val fresh = freshIconDrawable(drawable)
                    try {
                        imageView.setImageDrawable(fresh)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "requestIcon image paste failed", e)
                        // Best effort: 舊行 detach 後已由上重綁覆蓋。
                    }
                }
                // else 分支已由上重綁覆蓋（容器內按 tag 找同 key 行），此處不再重複。
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "requestIcon refill failed", e)
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
        // p26-2 Phase 1：ImageView 由固定 36dp layout 統一尺寸，此處保持
        // freshIconDrawable（不經 sizedIcon）；rebind/呼叫端僅取 fresh 實例避免共用。
        target.setImageDrawable(freshIconDrawable(drawable))
    }

    // Phase B B2：在來源容器內按 tag 找 CheckBox 重綁（找不到丟棄）。
    private fun rebindSourceIcon(appKey: String, drawable: Drawable) {
        if (!::sourceContainer.isInitialized) return
        for (i in 0 until sourceContainer.childCount) {
            val child = sourceContainer.getChildAt(i)
            if (child is CheckBox && child.tag == appKey) {
                val perView = sizedIcon(drawable, dp(SOURCE_ICON_DP))
                child.setCompoundDrawables(perView, null, null, null)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "findImageViewWithTag failed", e)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "isUnderClonedContainer failed", e)
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

    // UI 用：依目前 locale 解析批次結果字串（P3 三語）。
    private fun batchReportStrings(): BatchReportStrings = BatchReportStrings(
        summary = { s, f, u, t -> getString(R.string.batch_report_summary, s, f, u, t) },
        itemSuccess = getString(R.string.batch_report_item_success),
        itemFailed = getString(R.string.batch_report_item_failed),
        itemUnknownRecheck = getString(R.string.batch_report_item_unknown_recheck),
        itemUnknownChannel = getString(R.string.batch_report_item_unknown_channel),
        truncated = { rest, total, max -> getString(R.string.batch_report_truncated, rest, total, max) },
    )

    private fun batchReportTextLocalized(report: BatchReport?): String =
        formatBatchReportText(report, batchReportStrings())

    private fun batchReportSummaryLocalized(report: BatchReport?): String =
        formatBatchReportSummary(report, batchReportStrings())

    // PR64 P3：VM 無 Context，核准失效訊息以常數/前綴標記，UI 依 locale 本地化顯示。
    private fun localizeVmMessage(msg: String): String {
        if (msg.isEmpty()) return msg
        return try {
            when {
                msg == BATCH_APPROVAL_MISMATCH_SELECTION_MSG ->
                    getString(R.string.batch_approval_mismatch_selection)
                msg.startsWith(BATCH_APPROVAL_MISMATCH_PIN_PREFIX) -> {
                    val suffix = msg.removePrefix(BATCH_APPROVAL_MISMATCH_PIN_PREFIX)
                    val parts = suffix.removeSuffix("）").split(" → ", limit = 2)
                    if (parts.size == 2) {
                        getString(R.string.batch_approval_mismatch_pin, parts[0], parts[1])
                    } else {
                        msg
                    }
                }
                else -> msg
            }
        } catch (_: Exception) {
            msg
        }
    }

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
        // Stage1 批次建立兩步確認旋轉保留鍵：請求快照（keys/數量/目標/世代）＋勾選世代。
        private const val KEY_BATCH_REQ_KEYS = "batchReqKeys"
        private const val KEY_BATCH_REQ_COUNT = "batchReqCount"
        private const val KEY_BATCH_REQ_TARGET_ID = "batchReqTargetId"
        private const val KEY_BATCH_REQ_TARGET_SERIAL = "batchReqTargetSerial"
        private const val KEY_BATCH_REQ_GEN = "batchReqGen"
        private const val KEY_BATCH_REQ_WAS_FRESH = "batchReqWasFresh"
        private const val KEY_BATCH_ACK = "batchAck"
        private const val KEY_BATCH_ACK_GEN = "batchAckGen"
        // Stage2：政策寫入確認勾選綁定世代（同 remove ack 模式，旋轉保留）。
        private const val KEY_POLICY_ACK = "policyAck"
        private const val KEY_POLICY_ACK_GEN = "policyAckGen"
        // Stage B P2：完整清單展開開關（同世代旋轉保留，跨世代重置）。
        private const val KEY_POLICY_EXPANDED = "policyExpanded"
        private const val KEY_POLICY_EXPANDED_GEN = "policyExpandedGen"
        // PR-7 P2 (#26):單次渲染列數上限 (效能護欄,超出請用搜尋縮小範圍)。
        private const val MAX_SOURCE_ROWS = 200

        // PR-7 P2 (#26):圖示 Drawable 有界快取上限 (顯示用,不參與身分鍵)。
        private const val MAX_ICON_CACHE = 200

        // p26-2 統一圖示尺寸（圖形尺寸與容器尺寸分開定義，勿混用）：
        // SOURCE_ICON_DP = 來源 CheckBox 左圖圖形 24dp（setBounds 強制）、
        // CLONED_ICON_DP = 分身 ImageView 圖形 36dp（layout 固定）、
        // ICON_CONTAINER_DP = 2144 行分身列左圖外框 FrameLayout 44dp（容器，非圖形）。
        // 圖形尺寸都經 dp() 轉 px；CheckBox 另以 setBounds 強制，避免各 App 原生
        // intrinsic 尺寸（density/adaptive icon）不一導致忽大忽小。
        // adaptive 局限：bounds 統一（24/36）≠ 可見 glyph 統一；AdaptiveIconDrawable 以
        // SAFEZONE_SCALE=66/72 把 108dp 圖層縮到 viewport 內，24dp 外框對應安全區名義上約 22dp；
        // 可見 glyph 仍由圖檔、留白與 mask 決定，不能由此固定折算成更小的確定百分比，
        // 也不宜一律額外放大。保留「統一 bounds 不保證可見 glyph 等大，需視覺驗證」，
        // 目前按 bounds 統一收斂，視覺走查確認。24 vs 36 取捨：來源 24dp 密度高、分身 36dp 獨立位圖、
        // 行高 48dp／44dp 容器兜底。
        private const val SOURCE_ICON_DP = 24
        private const val CLONED_ICON_DP = 36
        private const val ICON_CONTAINER_DP = 44

        // Phase A 快照 OOM cap：位圖快照獨立位圖以 36dp 規範化後上限 192px，
        // 避免高密度下建全尺寸位圖致 OOM（實際尺寸經 clampSnapshotPx 限位）。
        private const val ICON_SNAPSHOT_CAP_PX = 192

        // Phase B B4：pkg→label 內存快取上限（與圖示快取同級，顯示用）。
        private const val MAX_LABEL_CACHE = 200

        // Phase 5 政策寫入 dialog 尺寸閾值（等值提取字面量，行為不變）：
        // POLICY_DIALOG_LIST_INLINE_MAX = 清單超過此項數才限高（少量
        // WRAP_CONTENT 不留空，ack 永遠可見）；
        // POLICY_DIALOG_LIST_MAX_HEIGHT_DP = 限高（經 dp() 轉 px）；
        // POLICY_DIALOG_LIST_MAX_HEIGHT_SMALL_DP = 小螢幕限高（smallestWidth<360
        // 或 screenHeight<640 經 dp() 轉 px；見 policyDialogListMaxHeightDp）；
        // CONFIRM_ACK_HPAD_DP／CONFIRM_ACK_MIN_HEIGHT_DP = 確認勾選
        // 水平內邊距／最小觸控高（remove／policy 兩 dialog 共用，48dp 觸控目標）。
        private const val POLICY_DIALOG_LIST_INLINE_MAX = 10
        private const val POLICY_DIALOG_LIST_MAX_HEIGHT_DP = 320
        private const val POLICY_DIALOG_LIST_MAX_HEIGHT_SMALL_DP = 240
        private const val CONFIRM_ACK_HPAD_DP = 24
        private const val CONFIRM_ACK_MIN_HEIGHT_DP = 48

        // PR-37 Phase C #5 圖示淘汰純決策 (JVM 可測 seam)：快取已滿
        // (orderedKeys.size >= maxSize) 時回傳最久未用 key（Phase D 起 LRU：
        // 命中移尾，故插入順序第一個＝最久未用），未滿回傳 null。只用 stdlib 集合、無 Android 依賴；呼叫方拿到 victim
        // 後經 evictIcon 同步清除 drawables＋memo 兩處。
        internal fun pickIconEvictionKey(orderedKeys: List<String>, maxSize: Int): String? =
            if (orderedKeys.size >= maxSize) orderedKeys.firstOrNull() else null
    }
}

// Phase A (P2-1 stale-success)：label 補查完成仲裁（純函數，JVM 可測；
// Activity 完成段快照 waiter＋取樣 freshCacheHit 後呼叫，執行恰一臂，
// 副作用留 Activity；呼叫端 when 窮舉無 else，新臂加入即編譯失敗）。
// 行覆寫引理：B-epoch waiter 的行 rowGen／tag／dialog 守衛對當代 waiter 合法
// 通過——若 stale 成功仍把舊 pm label 派發給當代 waiter，會以舊資料覆寫
// title／badge CD／移除鈕 CD 且不排重查；故唯有 FANOUT_SAME_EPOCH（同 epoch
// 成功）可派發本次 pm label，stale 完成只允許 DROP（丟棄）、SERVE_FROM_CACHE
//（派發 B-render 已預填的當代快取，永非舊 label）、RELAUNCH_FRESH（重掛 fresh
// waiter＋重發一次 IO）。same-epoch 失敗語義不變（DROP 全丟，下次 render 重註冊）。
internal enum class LabelCompletionAction {
    FANOUT_SAME_EPOCH,
    DROP_ALL,
    SERVE_FROM_CACHE,
    RELAUNCH_FRESH,
}

internal fun arbitrateLabelCompletion(
    launchEpoch: Int?,
    currentEpoch: Int?,
    waiterEpochs: List<Int?>,
    pmSuccess: Boolean,
    freshCacheHit: Boolean,
): LabelCompletionAction {
    if (launchEpoch == currentEpoch) {
        return if (pmSuccess) LabelCompletionAction.FANOUT_SAME_EPOCH else LabelCompletionAction.DROP_ALL
    }
    if (waiterEpochs.none { it == currentEpoch }) {
        return LabelCompletionAction.DROP_ALL
    }
    if (!pmSuccess && !freshCacheHit) {
        return LabelCompletionAction.RELAUNCH_FRESH
    }
    if (freshCacheHit) {
        return LabelCompletionAction.SERVE_FROM_CACHE
    }
    return LabelCompletionAction.RELAUNCH_FRESH
}

// Phase 1a 分身列表指紋鍵（純函數，JVM 可測；buildClonedListKey 委派，
// 刻意不含 loading——busy 邊沿只走 early-return 同步移除鈕 enabled，納入
// 指紋會致每次 op 全量重建＋圖示／label 重發兩次並推進 clonedGen 使在途回調失效）。
internal fun clonedListKeyFor(
    cloned: List<String>,
    targetSerial: Any?,
    targetId: Int,
    hookLiveProven: Boolean,
    catalogKey: Int,
): String = "${cloned.hashCode()}:$targetSerial:$targetId:$hookLiveProven:$catalogKey"

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
// 使去重鍵有效。剝除項：格式化日期（yyyy-MM-dd HH:mm）、NEVER_QUERIED_TEXT 空態、
// STALE_EVIDENCE_HINT 重啟舊資料提醒、VIA_GATEWAY_SUFFIX 經真通道標記（時間/通道衍生，
// 隨 queriedAt 漂移出現/消失），以及時間證據前綴 MARKER_TIME_PREFIX／
// MARKER_TIME_UNKNOWN_TEXT／QUERY_TIME_PREFIX（結構分隔符，基底文案不含「；」，安全）。
// Phase 3 P2 B2: 字面改引 DiagnoseFreshness 共享 const (single source)，一字不差，行為不變。
internal fun stripWriteBlockTimeParams(text: String): String {
    var k = text.replace(STALE_EVIDENCE_HINT, "")
    k = k.replace(VIA_GATEWAY_SUFFIX, "")
    k = k.replace(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), "")
    k = k.replace(NEVER_QUERIED_TEXT, "")
    k = k.replace(MARKER_TIME_PREFIX, "")
    k = k.replace(MARKER_TIME_UNKNOWN_TEXT, "")
    k = k.replace("$QUERY_TIME_PREFIX ", "")
    k = k.replace(QUERY_TIME_PREFIX, "")
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
        if (s.inventory.targetId > 0) {
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
    if (s.inventory.targetId > 0) {
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
            if (last.pinnedTarget > 0) " target=${last.pinnedTarget}" else ""
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
// 文案紀律：boot-fresh 前不得用 已注入/攔截中/運作中/常駐中/正常監聽/4/4/
// Active/Running/Intercepting/Injected 作 live 宣稱；歷史顯示重用
// telemetry_status_unknown/stats_hook_unknown/engine_diag_unknown，永不給綠。
internal fun hookHistoryActive(s: UiState): Boolean =
    renderHookText(s) != null && s.hookStatus == HookStatus.ACTIVE

internal fun hookLiveProven(s: UiState): Boolean =
    hookHistoryActive(s) && s.hookBootFresh

// Phase C channel-aware subtitle single source for card + About (JVM seam；
// 真可寫走 _ops，特權通道直寫、無需 Hook；其餘走白名單原文案)。
internal fun hookSubtitleResFor(writable: Boolean): Int =
    if (writable) R.string.hook_mode_subtitle_ops else R.string.hook_mode_subtitle

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
        return "LSPosed Hook: $enStatus${s.hookEvidence().timeEvidenceText()}; write channel is a placeholder (NoOp), writes disabled; whitelist policy source wired (end-to-end DEVICE-UNVERIFIED)"
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
        "寫入已禁用；白名單政策來源已接線（端到端 DEVICE-UNVERIFIED）"
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

// 批次逐項結果文字 (MainActivity 頂層 internal，JVM 可測；render 僅委派)。
// Phase 5 polish：政策寫入 dialog 包名預覽上限（效能＋a11y 護欄）：清單最多
// 顯示前 N 項，超出以「…等共 M 項」收尾（與批次報告同式）；完整集合仍在
// pending.packages 內，寫入數不受截斷影響。contentDescription 另給摘要
// （見 syncPolicyWriteDialog），TalkBack 不逐項朗讀 200 行。
const val POLICY_DIALOG_PREVIEW_MAX = 50

// 政策寫入 dialog 包名預覽截斷（MainActivity 頂層 internal，JVM 可測）：回傳
// （顯示列，溢出數）。呼叫端顯示列 join＋溢出行（資源
// policy_write_preview_more），寫入仍用完整 pending.packages。
internal fun truncatePolicyPreview(
    sorted: List<String>,
    max: Int = POLICY_DIALOG_PREVIEW_MAX,
): Pair<List<String>, Int> {
    if (sorted.size <= max) return sorted to 0
    return sorted.take(max) to (sorted.size - max)
}

// 批次逐項結果文字 (MainActivity 頂層 internal，JVM 可測；render 僅委派；保留繁中 scope-limited，非雙語)。
// - 未知口徑與 DualViewModel.isBatchUnknownOutcome 一致：VerifyUnknown 與
//   ChannelError（身份暫時無法判定、零寫入，需重查）皆計未知；NoDual 計失敗
//   （真 Absent＋M13 短路語意），其餘非 Success 皆計失敗。彙總計數由呼叫端
//   BatchReport 攜帶，此處只渲染不重算；formatter 測試鎖定此口徑，防彙總/明細漂移。
// - 明細包名顯示 raw pkg 是 by-design（與 renderLastOpText 的 redactPackage
//   差異是故意的）：此處列的是使用者剛勾選、即將/已經逐項執行的包名，需可核對；
//   診斷匯出/日誌仍經兩道遮蔽（見 buildDiagnosticExport）。

data class BatchReportStrings(
    val summary: (success: Int, failed: Int, unknown: Int, total: Int) -> String,
    val itemSuccess: String,
    val itemFailed: String,
    val itemUnknownRecheck: String,
    val itemUnknownChannel: String,
    val truncated: (rest: Int, total: Int, max: Int) -> String,
)

private val DEFAULT_BATCH_REPORT_STRINGS = BatchReportStrings(
    summary = { s, f, u, t -> "批次結果：成功 $s / 失敗 $f / 未知 $u（共 $t 項）" },
    itemSuccess = "成功",
    itemFailed = "失敗",
    itemUnknownRecheck = "未知（需重查）",
    itemUnknownChannel = "未知（通道無法查證，請重試）",
    truncated = { rest, total, max -> "  …等 $rest 項（共 $total 項，僅顯示前 $max 項）" },
)

internal fun formatBatchReportText(
    report: BatchReport?,
    strings: BatchReportStrings,
): String {
    if (report == null || report.items.isEmpty()) return ""
    return buildString {
        appendLine(formatBatchReportSummary(report, strings))
        val shown = report.items.take(BATCH_REPORT_MAX_ROWS)
        for (item in shown) {
            val mark = when (item.outcome) {
                is CloneOutcome.Success -> strings.itemSuccess
                is CloneOutcome.VerifyUnknown -> strings.itemUnknownRecheck
                is CloneOutcome.ChannelError -> strings.itemUnknownChannel
                else -> strings.itemFailed
            }
            appendLine("  ${item.pkg}：$mark")
        }
        val rest = report.items.size - shown.size
        if (rest > 0) {
            appendLine(strings.truncated(rest, report.items.size, BATCH_REPORT_MAX_ROWS))
        }
    }.trimEnd()
}

// Sticky bar 用單句摘要（避免 TalkBack 朗讀逐項明細）；完整明細保留於 batchResultView。
internal fun formatBatchReportSummary(
    report: BatchReport?,
    strings: BatchReportStrings,
): String {
    if (report == null || report.items.isEmpty()) return ""
    return strings.summary(report.successCount, report.failedCount, report.unknownCount, report.items.size)
}

// 向後相容：既有 JVM 測試/呼叫點沿用預設（繁中）字串。
internal fun formatBatchReportText(report: BatchReport?): String =
    formatBatchReportText(report, DEFAULT_BATCH_REPORT_STRINGS)

internal fun formatBatchReportSummary(report: BatchReport?): String =
    formatBatchReportSummary(report, DEFAULT_BATCH_REPORT_STRINGS)

// PR64：VM 核准失效訊息（VM 無 Context；UI 依此常數映射本地化字串）。
const val BATCH_APPROVAL_MISMATCH_SELECTION_MSG = "批次請求已過期：勾選或目標已變更，請重新確認"
const val BATCH_APPROVAL_MISMATCH_PIN_PREFIX = "批次請求已過期：目標已變更，已清空舊勾選，請重新確認（user "

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

// Phase B B1：confirm 門控同移除鈕（loading＋ack＋可寫）。二參版保留供既有
// JVM 測試；生產呼叫端一律用三參版（writable 即 blockKey == null）。
internal fun canConfirmRemove(loading: Boolean, acknowledged: Boolean, writable: Boolean): Boolean =
    !loading && acknowledged && writable

// Stage1 批次建立 confirm 門控（純函數，JVM 可測）：沿用 batchEnabled 門控
// （非 busy、有勾選、可寫、verified target 可用）再叠加 ack 勾選。VM isWritable/
// 起始 pin 守衛保留為縱深。
internal fun canConfirmBatch(s: UiState, acknowledged: Boolean): Boolean =
    batchEnabled(s) && acknowledged

// PR64 P1：已確認快照與 live 狀態不一致（同數量換選取亦算）即視為失效，須重建/清 ack。
// Review #4：ack 同時綁定 FRESH vs 非-FRESH 警告語境（requestWasFresh 預設 FRESH 以相容既有 4 參呼叫）。
internal fun batchRequestMatchesLive(
    requestKeys: Set<String>,
    requestTargetId: Int,
    requestTargetSerial: Int,
    live: UiState,
    requestWasFresh: Boolean = (live.inventory.freshness == InventoryFreshness.FRESH),
): Boolean =
    requestKeys.isNotEmpty() &&
        requestKeys == live.selectedKeys &&
        requestTargetId == live.inventory.targetId &&
        requestTargetSerial == live.inventory.targetSerial &&
        requestWasFresh == (live.inventory.freshness == InventoryFreshness.FRESH)

// 以請求快照驗證（含 freshness 語境）。
internal fun batchRequestMatchesLive(request: BatchConfirmRequest, live: UiState): Boolean =
    batchRequestMatchesLive(
        request.selectedKeys,
        request.targetId,
        request.targetSerial,
        live,
        request.wasFresh,
    )
// Phase 3（hook-policy-source P1，階段 3）：政策寫入 dialog 純門控（JVM 可測，
// 無需 Robolectric；Activity 側 syncPolicyWriteDialog 只呼叫此三函數）。
// - positive：僅寫入中（SAVING）禁用＋需 ack 勾選；排隊（QUEUED）可確認/可取消。
// - negative/Back：僅 SAVING 禁用（queued 仍可取消，P1 #1 語義）。
// - busy 後綴：SAVING 顯示寫入中，QUEUED（op 非 saving）顯示等待中，皆附換行。
internal fun policyDialogCanConfirm(policySaving: Boolean, acknowledged: Boolean): Boolean =
    !policySaving && acknowledged

internal fun policyDialogShouldBeCancelable(policySaving: Boolean): Boolean = !policySaving

internal fun policyAckShouldReset(storedGeneration: Long?, pendingGeneration: Long): Boolean =
    storedGeneration != pendingGeneration

internal fun policyWriteBusySuffix(policyOp: Boolean, policySaving: Boolean): String = when {
    policySaving -> "\n（寫入中…）"
    policyOp -> "\n（等待中…）"
    else -> ""
}

// Stage B P2 dialog 完整清單（方案 A Toggle）純函數（JVM 可測，無 Android 依賴）：
// - policyDialogListMaxHeightDp：小螢幕（smallestWidth<360 或 screenHeight<640）
//   回 SMALL（240dp），否則回 NORMAL（320dp）；非法輸入（<=0）fail-closed 回 SMALL。
// - policyWriteVisibleList：expanded 即完整清單（rest=0），否則走 truncate 预览。
// - policyWriteExpandedShouldReset：同 ack 世代契約（stored != pending 即重置為收合）。
// - policyWriteListCdFallback / ToggleLabel/AnnounceFallback：資源缺失時的
//   中文 fallback（生產經 getString，catch 即回此；JVM 以此鎖定算術與文案）。
internal fun policyDialogListMaxHeightDp(smallestWidthDp: Int, screenHeightDp: Int): Int =
    if (smallestWidthDp <= 0 || screenHeightDp <= 0) {
        240
    } else if (smallestWidthDp < 360 || screenHeightDp < 640) {
        240
    } else {
        320
    }

internal fun policyWriteVisibleList(
    sorted: List<String>,
    expanded: Boolean,
    max: Int = POLICY_DIALOG_PREVIEW_MAX,
): Pair<List<String>, Int> =
    if (expanded) sorted to 0 else truncatePolicyPreview(sorted, max)

internal fun policyWriteExpandedShouldReset(storedGeneration: Long?, pendingGeneration: Long): Boolean =
    storedGeneration != pendingGeneration

internal fun policyWriteListCdFallback(total: Int, shown: Int, expanded: Boolean): String =
    if (expanded || total <= shown) "政策包名列表，共 $total 項"
    else "政策包名列表，共 $total 項，僅顯示前 $shown 項"

internal fun policyWriteToggleLabelFallback(expanded: Boolean, total: Int, shown: Int): String =
    if (expanded) "收合（僅顯示前 $shown 項）" else "顯示全部（共 $total 項）"

internal fun policyWriteToggleAnnounceFallback(expanded: Boolean, total: Int, shown: Int): String =
    if (expanded) "已展開完整清單，共 $total 項" else "已收合，僅顯示前 $shown 項"

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

// Stage1 批次建立確認文案（純函數，JVM 可測）：數字欄位（數量/user/serial）
// 不含使用者可控字串，無需控制字元跳脫；模板經資源雙語對齊（見 UiSemantics）。
internal fun buildBatchConfirmMessage(
    template: String,
    count: Int,
    targetId: Int,
    targetSerial: Int,
): String = String.format(java.util.Locale.ROOT, template, count, targetId, targetSerial)
// owner-user 門控用:由 uid 拆出 userId。
// uid = userId * UserHandle.PER_USER_RANGE + appId (AOSP 公式);
// PER_USER_RANGE 為 @hide,公開 SDK 無法引用,故 100_000 字面單點存放在此。
// Phase 4:負值守衛 (<0 回 -1):Process.myUid() 恆非負,負值僅出現在測試/
// 異常路徑;舊碼整數除法向零截斷會把負值誤判為 user 0 (owner),直接 fail-open,
// 故顯式回 -1 拒絕。
internal fun userIdFromUid(uid: Int): Int = if (uid < 0) -1 else uid / 100_000

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
// signal for LSPosed activation yet (DualViewModel keeps hook signal out of routing).
// The su check is a file-presence heuristic, NOT proof su works: the real
// SuElevator/ShizukuElevator probes (wired below with the same instances)
// verify each channel, and DualViewModel flips channelReady only when the
// selected channel's own probe succeeds -- a wrong guess stays read-only.
// P1 closes the F4a "static startup pick" gap: selectChannel() swaps the
// repo gateway under opMutex, and recheckPrivilege() re-resolves AUTO.
// F4b-P2 (issue #6): the picker UI above calls selectChannel() for all four
// preferences; the LSPosed hook-enable signal comes from the marker file
// (HookStateProvider, refreshed on every probe) and is display-only. The
// LSPosed gateway stays the NoOp placeholder: the whitelist policy source is
// wired (end-to-end DEVICE-UNVERIFIED), but the real hook execution channel
// remains a NoOp placeholder.
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
                // Stage2:政策草稿 store 綁定 LSPosed remote preferences
                // (dual_policy/extra_whitelist_packages,與 hook 側同一 group)。
                // service 缺席時 store 仍有 instance,但 save/clear 回 false、
                // load 回空 (fail-closed),見 remoteHookPolicyStore。
                hookPolicyStore = remoteHookPolicyStore {
                    GalaxyApp.remotePreferences(PolicySource.POLICY_PREFS_NAME)
                },
                // Stage2:owner-user 門控 (hook 讀 user 0;非 owner 寫入無效)。
                // UserHandle.myUserId()/getUserId() 皆 @hide 公開 SDK 無法引用;
                // 以公開的 Process.myUid() 拆解 (見 userIdFromUid)。
                currentUserId = { userIdFromUid(Process.myUid()) },
                // p28-1 review（en 殘留）：寫入拒絕理由經 Application Context
                // 解析資源（applicationContext，不持 Activity；JVM 測試注入假值）。
                blockText = { block ->
                    appContext.applicationContext.getString(
                        block.stringRes(),
                        *block.args.toTypedArray(),
                    )
                },
                // Phase 5 polish：政策 VM 訊息經 Application Context 解析資源
                // （applicationContext，不持 Activity；JVM 預設繁中 defaultText，
                // 測試可注入假值）。plurals（DROPPED/WRITE_SUCCESS）走
                // getQuantityString；其餘走 getString。
                policyText = { pt ->
                    val res = appContext.applicationContext.resources
                    when (pt.kind) {
                        PolicyTextKind.BUSY -> res.getString(R.string.policy_busy)
                        PolicyTextKind.OWNER_WRITE -> res.getString(R.string.policy_owner_write)
                        PolicyTextKind.OWNER_OPERATE -> res.getString(R.string.policy_owner_operate)
                        PolicyTextKind.UNKNOWN_REFRESH -> res.getString(R.string.policy_unknown_refresh)
                        PolicyTextKind.EMPTY_SELECTION -> res.getString(R.string.policy_empty_selection)
                        PolicyTextKind.CATALOG_LOADING -> res.getString(R.string.policy_catalog_loading)
                        PolicyTextKind.CATALOG_MISSING -> res.getString(R.string.policy_catalog_missing)
                        PolicyTextKind.DROPPED -> res.getQuantityString(R.plurals.policy_dropped, pt.count, pt.count)
                        PolicyTextKind.CANCELLED -> res.getString(R.string.policy_cancelled)
                        PolicyTextKind.NO_PENDING -> res.getString(R.string.policy_no_pending)
                        PolicyTextKind.IN_PROGRESS -> res.getString(R.string.policy_in_progress)
                        PolicyTextKind.TTL_EXPIRED -> res.getString(R.string.policy_ttl_expired)
                        PolicyTextKind.NO_STORE_WRITE -> res.getString(R.string.policy_no_store_write)
                        PolicyTextKind.NO_STORE_CLEAR -> res.getString(R.string.policy_no_store_clear)
                        PolicyTextKind.NO_STORE_READ -> res.getString(R.string.policy_no_store_read)
                        PolicyTextKind.WRITE_SUCCESS -> res.getQuantityString(R.plurals.policy_written, pt.count, pt.count)
                        PolicyTextKind.WRITE_FAILED -> res.getString(R.string.policy_write_failed)
                        PolicyTextKind.CLEAR_SUCCESS -> res.getString(R.string.policy_cleared)
                        PolicyTextKind.CLEAR_FAILED -> res.getString(R.string.policy_clear_failed)
                        PolicyTextKind.REFRESHED -> res.getString(R.string.policy_refreshed)
                        PolicyTextKind.REFRESH_FAILED -> res.getString(R.string.policy_refresh_failed)
                        PolicyTextKind.BLIND_CLEAR_FIRST -> res.getString(R.string.policy_blind_clear_first)
                        PolicyTextKind.BLIND_CLEAR_EXPIRED -> res.getString(R.string.policy_blind_clear_expired)
                        PolicyTextKind.WRITE_TIMEOUT -> res.getString(R.string.policy_write_timeout)
                        PolicyTextKind.CLEAR_TIMEOUT -> res.getString(R.string.policy_clear_timeout)
                    }
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
