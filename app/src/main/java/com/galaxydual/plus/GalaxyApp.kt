// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1):module-app 側的 LSPosed remote preferences
// 通道。LSPosed 會為模組行程綁定 XposedService;此處持有 binder 並暴露
// remotePreferences(group),讓 app 寫入白名單政策草稿。service 可能缺席
// (模組未啟用/binder 尚未取得),所有呼叫端以 null 統一 fail-closed
// (見 remoteHookPolicyStore)。
package com.galaxydual.plus

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import com.galaxydual.hook.PolicySource
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GalaxyApp : Application(), XposedServiceHelper.OnServiceListener {

    // Stage G2：App-scoped PolicyTxCoordinator（跨 VM 最小 coordinator；
    // MainActivity factory 經 applicationContext 取用；VM onCleared 不清除）。
    // PR #74 R6-2 (G02): MUST stay lazy — Application field initializers run before
    // attachBaseContext(), so getSharedPreferences() here would crash on device.
    // The repair-hint marker lives in app-private prefs (NOT dual_policy remote
    // prefs) and is seeded into the coordinator at construction, so a restarted
    // app process immediately warns again until explicit acknowledgement.
    // PR #89 Phase B (B3): failure to ACQUIRE the prefs must NOT degrade to a null
    // store — that would make persistRepairHint early-return and silently drop the
    // hint on every restart (fail-open). Inject the fail-closed stub instead: it
    // warns, reports unconfirmed saves, and never touches disk. A genuinely null
    // store remains only the explicit JVM/in-memory no-store mode.
    val policyTxCoordinator: PolicyTxCoordinator by lazy {
        // Single source of truth for the fail-closed acquisition decision
        // (JVM-testable in RepairHintStore.kt): null/throw -> fail-closed stub,
        // never a null store / in-memory-only coordinator.
        val hintStore = repairHintStoreOrFailClosed {
            getSharedPreferences(REPAIR_HINT_PREFS_NAME, Context.MODE_PRIVATE).repairHintStore()
        }
        PolicyTxCoordinator(repairHintStore = hintStore)
    }

    // PR #90 Phase 2 (A2/E1): ONE app-scoped sequence domain for effect toggles
    // and consent dialog intents. A rebuilt Activity's OFF must be comparable
    // with (and newer than) a grant accepted before the rotation.
    private val bbcConsentIntentSequence = AtomicLong(0L)

    // PR #74 R3-1 + PR #90 Phase 3 (R3-06a/E2): app-process-local BBC toggle
    // state (last KNOWN write outcome + monotonic intent sequence). Seeded
    // NEUTRAL on purpose: the lazy initializer performs NO binder call, so a
    // bind-time IO coroutine can never hold this lazy's monitor while Main opens
    // Settings and blocks on it. The persisted value is instead read on the app
    // IO lane (see [bbcToggleCoordinator]'s effectRead seam) and folded in as a
    // PROVISIONAL read — never as a Confirmed write acknowledgement. Dialog/
    // Activity rebuilds MUST reuse this instance so the sequence stays monotonic.
    // NOT persisted, NOT the hook process's bbcConfirmedOffAtMs, and NOT a
    // Samsung cold-start deletion guard (issue #75).
    internal val bbcToggleRuntimeState: BbcToggleRuntimeState by lazy {
        BbcToggleRuntimeState(sequence = bbcConsentIntentSequence)
    }

    // PR #90 Phase C: the ONE app-scoped activation/consent holder. Seeded
    // NEUTRAL (Initializing); only an authoritative bind/resync read or a
    // Confirmed consent commit may grant. Deliberately NOT seeded from the
    // legacy default-true runtime flag — that was the fresh-install ON bug.
    // Survives every store/controller/Activity rebuild in this process.
    internal val bbcActivationConsentHolder: BbcActivationConsentHolder by lazy {
        BbcActivationConsentHolder(sequence = bbcConsentIntentSequence)
    }

    // PR #74 R3 D05: the ONE BBC write serializer for this app process. Built
    // once on the Application lifetime (NOT lifecycleScope) so a controller
    // rebuilt by a later dialog/Activity shares the same single consumer: an
    // old synchronous commit cannot be abandoned and land after a newer intent.
    // The scope is never cancelled by UI teardown; it ends with the process.
    // commit uses the SAME remote store seam MainActivity uses. NOT a Samsung
    // cold-start deletion guard (issue #75).
    internal val bbcToggleCoordinator: BbcToggleCoordinator by lazy {
        BbcToggleCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            state = bbcToggleRuntimeState,
            commit = { enabled ->
                // Phase 2 cold-start fix: EVERY runtime commit clears the
                // app-only enable-pending marker in the SAME atomic edit. This
                // is what makes a durable user OFF cancel a pending enable.
                remoteHookPolicyStore {
                    remotePreferences(PolicySource.POLICY_PREFS_NAME)
                }.setBbcRuntimeAndEnablePendingDetailed(enabled, pending = false)
            },
            // PR #90 Phase C: consent grant + authoritative resync share the
            // SAME app-lifetime consumer / IO lane as the runtime opt-out. No
            // binder or versionCommitLock wait ever happens on the Main thread.
            // Phase 2 (B3): generation-aware. The snapshot captured at bind
            // entry pairs (generation, service, provider); prefs are resolved
            // on this IO lane and the identity is verified BEFORE and AFTER the
            // commit. A reply from a dead generation can never grant, and the
            // fence is attributed to the instance actually touched.
            consentCommitAt = { capturedGeneration ->
                performBbcConsentWrite(capturedGeneration)
            },
            consentRead = { generation ->
                val store = remoteHookPolicyStore {
                    remotePreferences(PolicySource.POLICY_PREFS_NAME)
                }
                // ONE coherent snapshot of every consent-authoritative key,
                // mirroring the Hook reader: distinct explicit-FALSE / ABSENT /
                // UNAVAILABLE opt-in states, a contains()-based runtime seed
                // (never the default-true legacy read), and a single all-or-
                // nothing failure flag. The holder still fences by generation.
                store.readBbcConsentAuthoritative().forGeneration(generation)
            },
            // R5-02: the persisted effect value is read on this SAME app IO lane,
            // never on Main. The four-state read distinguishes ABSENT (no
            // explicit opt-out: authorized installs may seed checked, UNVERIFIED)
            // from UNAVAILABLE (failure: never ON), with no default-true. It is
            // folded in as a PROVISIONAL value, not a write outcome.
            effectRead = {
                remoteHookPolicyStore {
                    remotePreferences(PolicySource.POLICY_PREFS_NAME)
                }.readBbcInjectionEnabledAuthoritative()
            },
            consentHolder = bbcActivationConsentHolder,
        )
    }

    // PR #90 Phase C: monotonic service-instance generation for consent trust.
    // A rebound binder exposes a different remote-prefs instance, so cached
    // Granted/Legacy must be re-resolved; the durable Unknown fence survives.
    private val bbcConsentServiceGeneration = AtomicLong(0L)

    private fun bumpBbcConsentServiceGeneration(): Long {
        val generation = bbcConsentServiceGeneration.incrementAndGet()
        bbcActivationConsentHolder.onServiceGenerationChanged(generation)
        return generation
    }

    /**
     * P1-B: ONE atomic bind snapshot. Generation, bound service identity and
     * the prefs provider are captured TOGETHER on Main at bind entry (no binder
     * call here). The IO coroutine uses this snapshot and NEVER re-samples the
     * generation after resolving prefs: a stale attempt that outlives a rebind
     * must skip ALL state effects (confirm AND Unknown fence), leaving them to
     * the newer bind's own attempt.
     *
     * [resolvedPrefs] is filled in on the IO lane and participates in the
     * pre-apply check, so a failed commit made against a prefs instance that
     * is no longer the bound one cannot fence the new generation.
     */
    private class BbcClassificationAttempt(
        val generation: Long,
        val service: XposedService?,
        val provider: () -> SharedPreferences?,
    ) {
        @Volatile
        var resolvedPrefs: SharedPreferences? = null
    }

    /** Guards snapshot publication so a stale attempt cannot clobber it. */
    private val bbcClassificationSnapshotLock = Any()

    /** The current bind's snapshot; replaced atomically by every new bind. */
    private val bbcClassificationAttempt = AtomicReference<BbcClassificationAttempt?>(null)

    /** The prefs instance the CURRENT attempt resolved on the IO lane. */
    private val bbcClassificationBoundPrefs = AtomicReference<SharedPreferences?>(null)

    /**
     * True only while the captured generation, service/provider identity AND
     * the resolved prefs instance are all still the ones bound for this
     * attempt. Called twice in the IO coroutine: before committing and before
     * applying ANY outcome.
     */
    private fun isBindSnapshotCurrent(attempt: BbcClassificationAttempt): Boolean {
        val current = bbcClassificationAttempt.get() ?: return false
        return current === attempt &&
            bbcConsentServiceGeneration.get() == attempt.generation &&
            serviceRef.get() === attempt.service &&
            bbcClassificationPrefsProvider === attempt.provider
    }

    /** Full check, including the prefs identity resolved by this attempt. */
    private fun isBbcClassificationAttemptCurrent(attempt: BbcClassificationAttempt): Boolean =
        isBindSnapshotCurrent(attempt) &&
            bbcClassificationBoundPrefs.get() === attempt.resolvedPrefs

    // PR #90 Phase 2 (B3): the same atomic bind-snapshot discipline for the
    // consent OPT-IN commit. A user accepts at generation N; the commit may run
    // after binder death/rebind. Capturing `serviceRef` at execution time would
    // pair gen N with gen N+1 prefs, report a false grant, and let a gen N+1
    // resync clear the fence at gen N (minOf(current, captured)). Instead the
    // (generation, service, provider) triple is published atomically at bind
    // entry, checked before AND after the blocking commit, and the touched
    // generation is what the holder fences.
    private class BbcConsentWriteAttempt(
        val generation: Long,
        val service: XposedService?,
        val provider: () -> SharedPreferences?,
    ) {
        @Volatile
        var resolvedPrefs: SharedPreferences? = null
    }

    private val bbcConsentWriteSnapshotLock = Any()
    private val bbcConsentWriteAttempt = AtomicReference<BbcConsentWriteAttempt?>(null)
    private val bbcConsentWriteBoundPrefs = AtomicReference<SharedPreferences?>(null)

    private fun isBbcConsentWriteSnapshotCurrent(attempt: BbcConsentWriteAttempt): Boolean {
        val current = bbcConsentWriteAttempt.get() ?: return false
        return current === attempt &&
            bbcConsentServiceGeneration.get() == attempt.generation &&
            serviceRef.get() === attempt.service &&
            bbcClassificationPrefsProvider === attempt.provider
    }

    private fun isBbcConsentWriteAttemptCurrent(attempt: BbcConsentWriteAttempt): Boolean =
        isBbcConsentWriteSnapshotCurrent(attempt) &&
            bbcConsentWriteBoundPrefs.get() === attempt.resolvedPrefs

    /**
     * The generation-aware consent commit seam, executed on the app IO lane.
     * The generation comes from the user-intent snapshot; prefs are resolved
     * from that SAME snapshot. On any mismatch the outcome is forced to Unknown:
     *  - if NO commit entered (stale snapshot / prefs null / failed pre-check),
     *    the NO-GENERATION sentinel 0L is reported — no local pollution exists,
     *    so no fence (especially not a spurious CURRENT-generation one) is
     *    installed;
     *  - if the commit did enter and only the REPLY is stale, the holder fences
     *    the instance actually touched, never a re-sampled current generation.
     */
    private fun performBbcConsentWrite(capturedGeneration: Long): BbcConsentWriteResult {
        val attempt = bbcConsentWriteAttempt.get()
        if (attempt == null ||
            attempt.generation != capturedGeneration ||
            !isBbcConsentWriteSnapshotCurrent(attempt)
        ) {
            // NO-GENERATION sentinel (0L): no commit entered, so NO local mMap
            // was polluted and there is nothing to fence. Reporting the current
            // generation here used to install a spurious current-gen Unknown
            // fence that blocked that very generation's authoritative resync
            // until gen 3. `fenceForTouchedGeneration` keeps the old fence on <=0.
            return BbcConsentWriteResult(BbcWriteOutcome.Unknown, NO_CONSENT_COMMIT_GENERATION)
        }
        val prefs = try {
            attempt.provider()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
        if (prefs == null) {
            // Still no commit: no pollution, no fence (see above).
            return BbcConsentWriteResult(BbcWriteOutcome.Unknown, NO_CONSENT_COMMIT_GENERATION)
        }
        attempt.resolvedPrefs = prefs
        val safeToCommit = synchronized(bbcConsentWriteSnapshotLock) {
            if (!isBbcConsentWriteSnapshotCurrent(attempt)) false else {
                bbcConsentWriteBoundPrefs.set(prefs)
                isBbcConsentWriteAttemptCurrent(attempt)
            }
        }
        if (!safeToCommit) {
            // A rebind won the race BEFORE the write: never commit against a
            // serviceRef whose generation differs from the captured one, and
            // never fence the NEW generation with an outcome that never ran.
            return BbcConsentWriteResult(BbcWriteOutcome.Unknown, NO_CONSENT_COMMIT_GENERATION)
        }
        // ONE commit persists optin=true AND the app-only enable-pending marker.
        // A durable OFF clears that marker in its own runtime commit, so a
        // cold-start resync can never reverse it. The Hook ignores the marker.
        val outcome = try {
            remoteHookPolicyStore { prefs }.setBbcConsentGrantDetailed()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            BbcWriteOutcome.Unknown
        }
        // Verify AFTER the blocking commit. The write really landed on the
        // captured generation's prefs instance; a rebind in between only
        // invalidates the reply (Unknown), it does not re-label the fence.
        val stillCurrent = isBbcConsentWriteAttemptCurrent(attempt)
        return BbcConsentWriteResult(
            if (stillCurrent) outcome else BbcWriteOutcome.Unknown,
            attempt.generation,
        )
    }

    /** Resolve the consent state from durable keys on the app IO lane. */
    private fun refreshBbcActivationConsent() {
        bbcToggleCoordinator.submitConsentRead()
        // Phase 3 (R3-06a/E2): the effect value also comes from a durable read
        // on the IO lane — no binder call on Main, provisional (not Confirmed).
        bbcToggleCoordinator.submitEffectRead()
    }

    override fun onCreate() {
        super.onCreate()
        // S2: hook 只讀 user 0；非 owner 進程（如 work profile）即使註冊 binder
        // 也只會讀寫到非本人的草稿通道，永遠不會生效 —— 不註冊（fail-closed，
        // 也省一個跨進程監聽器）。uid 拆解失敗一律視為非 owner（fail-closed）。
        // SEC-2: 這裡的 userIdFromUid 分解依賴 AOSP user-range shift
        // （userId*100_000 + appId，100_000 為 @hide 的 UserHandle.PER_USER_RANGE
        // 字面）。fail-closed 性質是方向依存的，並非無條件：shift 誤差下
        // 過大 shift 會把 / 100_000 分解縮退為 userId 0（owner 判定放寬給次級
        // user——fail-closed ではない）；過小 shift 則可能把 owner 誤判為非 owner
        // （可用性損失）。uid==0 與（trust gate 的）uid==myUid 等価路徑不受 shift
        // 影響。詳述與 DEVICE-UNVERIFIED 狀態（on-device assertion 保留中、非儀礼
        // 但書，不主張無條件 fail-closed）見
        // GuardXposedProvider.isTrustedXposedCaller 上的 SEC-2 コメント。
        val owner = try {
            userIdFromUid(Process.myUid()) == 0
        } catch (t: Throwable) {
            try {
                System.err.println("[GalaxyApp] owner-user check failed (${t.javaClass.name}); skipping XposedServiceHelper registration")
            } catch (_: Throwable) {
            }
            false
        }
        if (owner) {
            XposedServiceHelper.registerListener(this)
        }
    }

    // PR #90 P0: bind-time classification performs a BLOCKING binder commit.
    // Run it on the app's IO scope so it can never delay bind notification;
    // HookPolicyStore.saveRaw remains the correctness gate (it re-plans and
    // re-adds any pending intent before EVERY draft write).
    internal val bbcClassificationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * PR #90 P1-1 test seam: the writable remote-prefs provider used by the
     * bind-time durable-classification confirmation. Production always uses
     * the app's XposedService channel; JVM tests inject an in-memory prefs
     * because `Process.myUid()` is an Android stub there (so [remotePreferences]
     * always fails closed). Defaults to the production channel.
     */
    internal var bbcClassificationPrefsProvider: () -> SharedPreferences? = {
        remotePreferences(PolicySource.POLICY_PREFS_NAME)
    }

    override fun onServiceBind(service: XposedService) {
        serviceRef.set(service)
        // PR #90 Phase C: a newly bound service is a NEW prefs instance. Drop
        // cached Granted/Legacy trust (but keep the durable Unknown fence) and
        // resolve again from durable keys once classification is in place.
        val generation = bumpBbcConsentServiceGeneration()
        // P1-B: capture generation + bound service + provider identity in ONE
        // atomic snapshot at bind entry. These are in-memory reads on Main —
        // no binder call. The IO coroutine uses ONLY this snapshot; its prefs
        // resolution can no longer be paired with a newer generation.
        val attempt = BbcClassificationAttempt(
            generation = generation,
            service = serviceRef.get(),
            provider = bbcClassificationPrefsProvider,
        )
        bbcClassificationAttempt.set(attempt)
        // Phase 2 B3: publish the consent-write snapshot atomically with the
        // same bind entry. Generation, service identity and provider are fixed
        // before any IO coroutine can capture them.
        synchronized(bbcConsentWriteSnapshotLock) {
            bbcConsentWriteAttempt.set(
                BbcConsentWriteAttempt(
                    generation = generation,
                    service = serviceRef.get(),
                    provider = bbcClassificationPrefsProvider,
                ),
            )
            bbcConsentWriteBoundPrefs.set(null)
        }
        // PR #90 P1-1: confirm classification on EVERY bind, including a rebind
        // that arrives without a death callback (or whose stale death callback
        // lost the CAS). A rebind bumps the consent generation, which resets
        // classificationConfirmedGeneration; short-circuiting on a one-shot flag
        // left legacy installs stuck at Initializing (App demanding consent
        // while the Hook gate was open). Confirmation therefore always runs for
        // the current bind's generation; a failed attempt is retried on a later
        // bind.
        bbcClassificationScope.launch {
            // Resolve prefs on IO with the CAPTURED provider. The generation is
            // deliberately NOT re-sampled here: the earlier code paired prefs1
            // with gen2 after a rebind and let that stale failure fence gen2.
            // CancellationException always propagates; every other throwable
            // fails closed as binder-absent.
            val prefs = try {
                attempt.provider()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            attempt.resolvedPrefs = prefs
            // P1-B: a newer bind already owns this state — skip EVERYTHING.
            // Publishing the prefs identity is atomic with the snapshot check
            // so a stale attempt can never clobber the new bind's identity.
            synchronized(bbcClassificationSnapshotLock) {
                if (!isBindSnapshotCurrent(attempt)) return@launch
                bbcClassificationBoundPrefs.set(prefs)
            }
            // P1-2: only a SUCCESSFUL durable commit may confirm classification.
            // A NoWrite inferred from a polluted local mMap is never enough.
            val ok = if (prefs == null) false else try {
                confirmBbcInstallClassificationDurable { prefs }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
            // P1-B: verify the snapshot AGAIN before applying ANY outcome. A
            // rebind during the blocking commit must leave both confirmation
            // and the Unknown fence to the newer bind's own attempt.
            if (!isBbcClassificationAttemptCurrent(attempt)) return@launch
            if (ok) {
                // Review A6: record the CONFIRMED fact BEFORE enqueuing the
                // resync. A queued read can otherwise observe an optimistic
                // local mMap polluted by a failed classification commit and
                // resolve Legacy before the failure fence is installed.
                bbcActivationConsentHolder.markInstallClassificationConfirmed(attempt.generation)
                // Phase C: resolve authorization from durable keys only after
                // the classification is confirmed durable, on the app IO lane
                // (never on Main, never from a cached local mMap).
                refreshBbcActivationConsent()
            } else {
                // A FAILED bind-time classification may have left
                // PRE_UPGRADE_EXISTING + marker in THIS instance's local mMap
                // while the daemon persisted NOTHING. Do NOT resync from that
                // read: fence this generation as Unknown so no legacy checkbox
                // or consent skip can be authorized by optimistic pollution.
                bbcActivationConsentHolder.markInstallClassificationFailed(attempt.generation)
            }
        }
        // 5.1.5:通知所有綁定監聽者 (例如刷新政策草稿)。
        // Never blocked by the binder commit above.
        notifyServiceListeners()
    }

    override fun onServiceDied(service: XposedService) {
        // 原子 CAS：只清同一 instance，避免新 binder 被舊的 death 回調清掉。
        // A stale death callback does NOTHING (no generation bump, no resync).
        if (serviceRef.compareAndSet(service, null)) {
            // PR #90 Phase 3 (R3-06b/E3 + E5): BUMP FIRST, on the callback
            // thread, as an in-memory atomic. No versionCommitLock is acquired
            // here — that monitor is held around blocking binder commits and
            // must never stall death bookkeeping. Granted/Legacy degrade to
            // Initializing immediately; a durable Unknown fence is preserved.
            val newGeneration = bumpBbcConsentServiceGeneration()

            // E5: a bind may have captured an attempt for an older generation
            // between this CAS and the bump. That attempt will now deliberately
            // skip its own refresh, so record that a resync for THIS generation
            // is required. No loop: a read never bumps the generation.
            val bindAttemptInvalidated = synchronized(bbcClassificationSnapshotLock) {
                val attempt = bbcClassificationAttempt.get()
                val invalidated = attempt != null && attempt.generation != newGeneration
                // P1-B: invalidate the dead bind's snapshot; its in-flight
                // attempt must not apply a confirm/fence to the new instance.
                bbcClassificationAttempt.set(null)
                bbcClassificationBoundPrefs.set(null)
                invalidated
            }
            // Phase 2 B3: invalidate the consent-write snapshot too. An in-flight
            // write must see the mismatch both before and after its commit.
            synchronized(bbcConsentWriteSnapshotLock) {
                bbcConsentWriteAttempt.set(null)
                bbcConsentWriteBoundPrefs.set(null)
            }
            // Capture the pending classification intent identity WITHOUT a lock.
            // The deferred clear below must never drop a NEWER bind's retry
            // intent; a stale pending entry is keyed by prefs identity and is
            // self-clearing via PendingBbcClassification.matches() anyway.
            val pendingAtDeath = pendingBbcClassification.get()

            // 通知監聽者服務已斷線,讓呼叫端重新評估 (fail-closed)。
            notifyServiceListeners()

            // PR #90 Phase 3 (R3-06b/E3): all pending-intent cleanup moves to
            // the app IO scope UNDER versionCommitLock — the same serializer as
            // the blocking binder commits, never the service callback thread.
            bbcClassificationScope.launch {
                synchronized(versionCommitLock) {
                    if (pendingBbcClassification.get() === pendingAtDeath) {
                        clearPendingBbcClassification()
                    }
                }
                if (bindAttemptInvalidated) {
                    // E5: the invalidated bind will never refresh; a resync for
                    // the new generation is the only way out of Initializing.
                    bbcToggleCoordinator.submitConsentRead()
                    bbcToggleCoordinator.submitEffectRead()
                }
            }
        }
    }

    private fun notifyServiceListeners() {
        for (listener in GalaxyApp.serviceBindListeners) {
            try {
                listener()
            } catch (e: Throwable) {
                // A3-11: 監聽器例外不得靜默吞掉 —— 單行 log（僅 tag＋異常類別名，
                // 絕不含 listener 內容／政策資料），並繼續通知其餘監聽者。
                try {
                    System.err.println("[GalaxyApp] service bind listener threw ${e.javaClass.name}; continuing")
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        /**
         * NO-GENERATION sentinel for a consent commit that never entered: 0L is
         * treated by [BbcActivationConsentHolder.fenceForTouchedGeneration] as
         * "keep the existing fence", so no spurious current-gen fence is set.
         */
        const val NO_CONSENT_COMMIT_GENERATION = 0L

        private val serviceRef = AtomicReference<XposedService?>(null)

        // 5.1.5:service 綁定監聽者 (CopyOnWriteArrayList 供併發安全)。
        // Phase 4:改 private + add/remove 封裝,避免外部直接 mutable 存取。
        private val serviceBindListeners = CopyOnWriteArrayList<() -> Unit>()

        fun addServiceBindListener(listener: () -> Unit) {
            serviceBindListeners.add(listener)
        }

        fun removeServiceBindListener(listener: () -> Unit) {
            serviceBindListeners.remove(listener)
        }

        // 讀/寫指定 group 的 remote preferences;service 缺席時回 null
        // (fail-closed:呼叫端視為無持久化草稿)。
        // S2: 非 owner 進程回 null（hook 只讀 user 0；非 owner 寫入不會被看到，
        // 故直接 fail-closed，與 GalaxyApp.onCreate 不註冊綁定同策）。
        // Phase 4:包 try/catch -> null (fail-closed 內聚):binder 死亡/拋出
        // (含 Error) 不得越過此邊界,呼叫端統一視為無持久化草稿。
        // 與 HookPolicyStore 全鏈一致：CancellationException 透傳（協程取消
        // 不得被吞為缺席），其餘 Throwable（含 Error）才轉 null。
        fun remotePreferences(group: String): SharedPreferences? {
            val owner = try {
                userIdFromUid(Process.myUid()) == 0
            } catch (_: Throwable) {
                // uid 拆解失敗 → fail-closed（視為非 owner）。
                false
            }
            if (!owner) return null
            return try {
                serviceRef.get()?.getRemotePreferences(group)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
        }

        // 供呼叫端在訂閱後補發已綁定狀態 (避免 lost-event 競態)。
        fun isServiceConnected(): Boolean = serviceRef.get() != null
    }
}
