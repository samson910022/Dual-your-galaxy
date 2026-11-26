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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PR #90 R5-03: one immutable app-side I/O session. It is created and published
 * atomically at `onServiceBind` and binds a generation to the service instance
 * captured at that bind. [prefs] is resolved once SUCCESSFULLY (a failed
 * resolution is retried), FROM THAT CAPTURED SERVICE, on the app's IO lane —
 * never by re-sampling the global serviceRef at execution time.
 *
 * A generation NUMBER is not an I/O identity: a repeated bind of the same
 * service / same cached SharedPreferences with a higher generation is a
 * DIFFERENT session but NOT fresh, clean evidence. The session object identity
 * (and its generation + service + prefs triple) is what every read/write stamp
 * and every currency check uses.
 */
internal class BbcIoSession(
    val generation: Long,
    val service: XposedService,
    private val resolver: (XposedService) -> SharedPreferences?,
) {
    /** Resolved on IO; a failed resolution is retried, a success is kept. */
    @Volatile
    var prefs: SharedPreferences? = null
        private set

    fun resolvePrefs(): SharedPreferences? {
        prefs?.let { return it }
        val resolved = try {
            resolver(service)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
        if (resolved != null) prefs = resolved
        return resolved
    }

    /** Non-null only once prefs have been resolved from the captured service. */
    fun identity(): BbcConsentSessionIdentity? = prefs?.let {
        BbcConsentSessionIdentity(generation, service, it)
    }

    /**
     * Same session INSTANCE (which fixes generation and service) and the same
     * prefs identity. An unresolved prefs (`null === null`) is still the same
     * session: a failed bind must be allowed to fence its own generation.
     */
    fun isCurrent(current: BbcIoSession?): Boolean =
        current === this && prefs === current.prefs
}

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
        buildBbcToggleCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    /**
     * R5-03: every BBC app I/O seam is bound to the CURRENT immutable session.
     * No consent/effect/classification/enable path re-samples the global
     * serviceRef at execution time. Extracted from the lazy so JVM tests can
     * build the SAME production seams on a virtual dispatcher.
     */
    internal fun buildBbcToggleCoordinator(
        scope: CoroutineScope,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): BbcToggleCoordinator = BbcToggleCoordinator(
        scope = scope,
        io = io,
        state = bbcToggleRuntimeState,
        commit = { enabled -> performBbcRuntimeCommit(enabled, null) },
        commitWithSession = { enabled, authorizer ->
            performBbcRuntimeCommit(enabled, authorizer)
        },
        // PR #90 Phase C: consent grant + authoritative resync share the
        // SAME app-lifetime consumer / IO lane as the runtime opt-out. No
        // binder or versionCommitLock wait ever happens on the Main thread.
        // R5-03: the generation-aware write is now SESSION-aware — prefs are
        // resolved from the service captured at bind and verified before and
        // after the blocking commit.
        consentCommitAt = { capturedGeneration ->
            performBbcConsentWrite(capturedGeneration)
        },
        consentRead = { generation -> performBbcConsentRead(generation) },
        // R5-02: the persisted effect value is read on this SAME app IO lane,
        // never on Main. R5-03: from the current session's prefs instance.
        effectRead = { performBbcEffectRead() },
        currentSession = { currentBbcSessionIdentity() },
        consentHolder = bbcActivationConsentHolder,
    )

    /** Current session identity, or null before prefs are resolved / after death. */
    private fun currentBbcSessionIdentity(): BbcConsentSessionIdentity? =
        bbcIoSession.get()?.identity()

    /** Every runtime write clears the app-only enable-pending marker atomically. */
    private fun commitBbcRuntime(prefs: SharedPreferences, enabled: Boolean): BbcWriteOutcome =
        try {
            remoteHookPolicyStore { prefs }
                .setBbcRuntimeAndEnablePendingDetailed(enabled, pending = false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            BbcWriteOutcome.Unknown
        }

    /**
     * Phase 2 cold-start fix: EVERY runtime commit clears the app-only
     * enable-pending marker in the SAME atomic edit. R5-03: the write targets
     * the CURRENT session's prefs; an auto-enable [authorizer] may only write
     * when that session is still current and is NEVER retried after a failed
     * currency check (fail-closed rejection is correct there).
     *
     * R5-03 review R2: a USER OFF (null authorizer) is highest priority. If a
     * rebind landed inside its commit, the old prefs may now hold the OFF while
     * the new session still carries a durable enablePending marker. On the
     * failed currency check we re-read the session and retry the SAME OFF write
     * ONCE against the genuinely current prefs. A second rebind, an absent
     * session or an unresolvable prefs instance reports Unknown honestly.
     */
    private fun performBbcRuntimeCommit(
        enabled: Boolean,
        authorizer: BbcConsentSessionIdentity?,
    ): BbcWriteOutcome {
        val session = bbcIoSession.get() ?: return BbcWriteOutcome.Unknown
        val prefs = session.resolvePrefs() ?: return BbcWriteOutcome.Unknown
        if (authorizer != null && !(authorizer isSameSession session.identity())) {
            return BbcWriteOutcome.Unknown
        }
        val outcome = commitBbcRuntime(prefs, enabled)
        if (session.isCurrent(bbcIoSession.get())) return outcome
        if (authorizer != null) return BbcWriteOutcome.Unknown // never retry an enabled grant

        val current = bbcIoSession.get() ?: return BbcWriteOutcome.Unknown
        if (current === session) return BbcWriteOutcome.Unknown
        val currentPrefs = current.resolvePrefs() ?: return BbcWriteOutcome.Unknown
        val retry = commitBbcRuntime(currentPrefs, enabled)
        return if (current.isCurrent(bbcIoSession.get())) retry else BbcWriteOutcome.Unknown
    }

    /**
     * R5-03: authoritative consent read from the CURRENT session's prefs. The
     * full session identity is stamped on the result; a rebind before/after the
     * coherent read forces fail-closed and never certifies the new session.
     */
    private fun performBbcConsentRead(generation: Long): BbcConsentAuthoritativeRead {
        val session = bbcIoSession.get()
        if (session == null || session.generation != generation) {
            return BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
        }
        val prefs = session.resolvePrefs()
        val identity = session.identity()
        if (prefs == null || identity == null || !session.isCurrent(bbcIoSession.get())) {
            return BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
        }
        // ONE coherent snapshot of every consent-authoritative key, mirroring
        // the Hook reader: explicit-FALSE / ABSENT / UNAVAILABLE, contains()-based
        // runtime seed (never default-true) and one all-or-nothing failure flag.
        val snapshot = try {
            remoteHookPolicyStore { prefs }.readBbcConsentAuthoritative()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            BbcConsentDurableSnapshot.failClosed()
        }
        if (!session.isCurrent(bbcIoSession.get())) {
            return BbcConsentDurableSnapshot.failClosed().forGeneration(generation)
        }
        return snapshot.forSession(identity)
    }

    /** R5-03: authoritative four-state effect read from the session's prefs. */
    private fun performBbcEffectRead(): BbcRuntimePrefValue {
        val session = bbcIoSession.get() ?: return BbcRuntimePrefValue.UNAVAILABLE
        val prefs = session.resolvePrefs() ?: return BbcRuntimePrefValue.UNAVAILABLE
        if (!session.isCurrent(bbcIoSession.get())) return BbcRuntimePrefValue.UNAVAILABLE
        val value = try {
            remoteHookPolicyStore { prefs }.readBbcInjectionEnabledAuthoritative()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            BbcRuntimePrefValue.UNAVAILABLE
        }
        return if (session.isCurrent(bbcIoSession.get())) value else BbcRuntimePrefValue.UNAVAILABLE
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
     * R5-03: the ONE published immutable I/O session. Created and atomically
     * replaced at bind entry; invalidated on death only for its owning service.
     * Every BBC consent/effect/classification/enable path reads sessions from
     * here instead of re-sampling the global serviceRef at execution time.
     */
    private val bbcIoSession = AtomicReference<BbcIoSession?>(null)

    /**
     * R5-03: the session-aware consent commit seam, executed on the app IO lane.
     * The prefs instance is resolved FROM THE SERVICE CAPTURED AT BIND inside
     * [session]; the whole (generation, service, prefs) identity is verified
     * BEFORE and AFTER the blocking commit and stamped on the result:
     *  - no session / wrong generation / prefs absent / stale before-check: the
     *    NO-GENERATION sentinel 0L — no local pollution exists, so no spurious
     *    CURRENT-generation fence is installed;
     *  - commit entered but the REPLY is stale: outcome forced to Unknown, yet
     *    the fence is attributed to the instance ACTUALLY touched (its session
     *    identity), never to the new generation.
     */
    private fun performBbcConsentWrite(capturedGeneration: Long): BbcConsentWriteResult {
        val session = bbcIoSession.get()
        if (session == null || session.generation != capturedGeneration) {
            return BbcConsentWriteResult(BbcWriteOutcome.Unknown, NO_CONSENT_COMMIT_GENERATION)
        }
        val prefs = session.resolvePrefs()
        val identity = session.identity()
        if (prefs == null || identity == null || !session.isCurrent(bbcIoSession.get())) {
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
        // Verify AFTER the blocking commit. A rebind in between only invalidates
        // the REPLY; the fence still belongs to the instance actually touched.
        val stillCurrent = session.isCurrent(bbcIoSession.get())
        return BbcConsentWriteResult(
            if (stillCurrent) outcome else BbcWriteOutcome.Unknown,
            session.generation,
            identity,
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
     * PR #90 P1-1 / R5-03 test seam: resolves the writable remote prefs FROM
     * THE SERVICE CAPTURED AT BIND. The argument is the bound service — never
     * the global serviceRef at execution time — so a stale I/O attempt cannot
     * pair its generation with a newer binder's prefs. Production uses the
     * app's XposedService channel; JVM tests inject an in-memory resolver
     * because `Process.myUid()` is an Android stub there (so the production
     * channel always fails closed). The returned instance is bound into the
     * [BbcIoSession] and its identity is stamped on every later read/write.
     */
    internal var bbcClassificationPrefsProvider: (XposedService) -> SharedPreferences? = { service ->
        val owner = try {
            userIdFromUid(Process.myUid()) == 0
        } catch (_: Throwable) {
            false
        }
        if (!owner) null
        else try {
            service.getRemotePreferences(PolicySource.POLICY_PREFS_NAME)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceRef.set(service)
        // PR #90 Phase C: a newly bound service is a NEW prefs instance. Drop
        // cached Granted/Legacy trust (but keep the durable Unknown fence) and
        // resolve again from durable keys once classification is in place.
        val generation = bumpBbcConsentServiceGeneration()
        // R5-03: publish ONE immutable session atomically at bind entry. It
        // captures the ACTUAL service (not the global serviceRef) together with
        // the generation and the resolver; prefs are resolved FROM THAT SERVICE
        // on the IO lane below. No BBC I/O re-samples the global at execution.
        val session = BbcIoSession(generation, service, bbcClassificationPrefsProvider)
        bbcIoSession.set(session)
        // PR #90 P1-1: confirm classification on EVERY bind, including a rebind
        // that arrives without a death callback (or whose stale death callback
        // lost the CAS). A rebind bumps the generation, which resets
        // classificationConfirmedGeneration; a one-shot flag used to leave
        // legacy installs stuck at Initializing. Confirmation always runs for
        // the current bind's generation; a failed attempt is retried later.
        bbcClassificationScope.launch {
            // Resolve prefs on IO FROM THE CAPTURED SERVICE. CancellationException
            // always propagates; every other throwable fails closed.
            val prefs = try {
                session.resolvePrefs()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            // R5-03: a newer bind already published a different session — skip
            // EVERYTHING, leaving confirm/fence to the newer bind's own attempt.
            if (!session.isCurrent(bbcIoSession.get())) return@launch
            // P1-2: only a SUCCESSFUL durable commit may confirm classification.
            // A NoWrite inferred from a polluted local mMap is never enough.
            // R5 Phase 3a: a fail-closed plan (unknown/unreadable value, poison
            // latch, Unavailable version, resolver error) creates NO editor and
            // writes NOTHING — it is NOT pollution and must leave the holder
            // neutral for a later bind's authoritative resync.
            val confirmation = if (prefs == null) {
                BbcClassificationConfirmation.NoCommitFailClosed
            } else try {
                confirmBbcInstallClassificationDurable { prefs }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                BbcClassificationConfirmation.NoCommitFailClosed
            }
            // R5-03: verify the FULL session identity AGAIN before applying ANY
            // outcome. A rebind during the blocking commit must leave both
            // confirmation and the Unknown fence to the newer bind's attempt.
            if (!session.isCurrent(bbcIoSession.get())) return@launch
            when (confirmation) {
                BbcClassificationConfirmation.Confirmed -> {
                    // Review A6: record the CONFIRMED fact BEFORE enqueuing the
                    // resync. A queued read can otherwise observe an optimistic
                    // local mMap polluted by a failed classification commit and
                    // resolve Legacy before the failure fence is installed.
                    bbcActivationConsentHolder.markInstallClassificationConfirmed(generation)
                    // Phase C: resolve authorization from durable keys only after
                    // the classification is confirmed durable, on the app IO lane.
                    refreshBbcActivationConsent()
                }
                BbcClassificationConfirmation.CommitFailedPollution -> {
                    // Only a commit that ACTUALLY ENTERED can pollute an
                    // optimistic local mMap. This outcome means an edit()/commit()
                    // was attempted and failed, possibly leaving PRE_UPGRADE_*
                    // + marker in THIS instance's local mMap while the daemon
                    // persisted NOTHING. Do NOT resync from that read: fence this
                    // generation as Unknown, attributed to the exact prefs
                    // instance actually touched (R5-03).
                    bbcActivationConsentHolder.markInstallClassificationFailed(
                        generation, session.identity(),
                    )
                }
                BbcClassificationConfirmation.NoCommitFailClosed -> {
                    // No editor was created: prefs unresolved, or the planner
                    // failed closed without writing. There is no optimistic map
                    // to fence. Stay Initializing/neutral and let the next
                    // bind's authoritative resync decide from durable keys.
                    // A same-prefs stamped fence here can NEVER be cleared
                    // (only a Confirmed grant or a process restart), which
                    // would lock out the user until a manual re-grant.
                }
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

            // R5-03: invalidate the session ONLY for the owning service identity.
            // A stale death callback for an old binder never clears a newer
            // bind's session (the serviceRef CAS above already guarantees this,
            // but the session CAS makes the invariant explicit). An in-flight
            // I/O attempt on this session then fails every currency check.
            val deadSession = bbcIoSession.get()
            val bindAttemptInvalidated = deadSession != null &&
                deadSession.service === service &&
                bbcIoSession.compareAndSet(deadSession, null)
            // A bind may have published a session older than this bump; its IO
            // coroutine will skip its own refresh, so this generation needs a
            // resync. No loop: a read never bumps the generation.
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
