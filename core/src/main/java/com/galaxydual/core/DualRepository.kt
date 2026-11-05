// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.jvm.Volatile

// Serial-bound dual target fetch result (PR-33 R2, P1): distinguishes a
// confirmed (id, serial) target from "no dual" and from "id confirmed but
// the serial cannot be established", so the two-phase remove confirm can
// refuse a possibly-recreated profile instead of uninstalling it.
sealed interface DualTargetResult {
    // Both verbose channels agree on the id AND on one non-null serial.
    data class Confirmed(val target: VerifiedDualTarget) : DualTargetResult

    // The id itself is not confirmed (Unknown/Refused, or a failed channel).
    data object NoDual : DualTargetResult

    // The id is confirmed but no single agreed serial exists (no serial on
    // either verbose channel, or the two serials disagree).
    data object SerialUnavailable : DualTargetResult
}

// Typed dual-id resolution (Round 2 Phase B P2-5): tells "confirmed" apart
// from "confirmed absent" and from "unconfirmable", so callers never mistake
// a failed typed channel for a missing profile. The legacy Int entry below
// folds Absent and Unknown together (-1); it is kept unchanged for
// compatibility, new callers must use this typed entry.
sealed interface DualIdResolution {
    // Clean typed channels agree on one non-owner id: safe to target.
    data class Confirmed(val userId: Int) : DualIdResolution

    // Clean typed channels parsed fine but claim no dual profile: the device
    // genuinely has no usable dual target right now.
    data object Absent : DualIdResolution

    // The identity cannot be established: a typed channel threw/failed, or
    // the evidence contradicts itself / is too thin to confirm either way.
    // Callers must treat this as "unknown" (keep old data, report channel),
    // never as "no dual".
    data class Unknown(val detail: String = "") : DualIdResolution
}

// Profile-list snapshot distinguishing channel failure from an empty parse.
// Phase C3 (issue #9): listProfiles() collapses both to emptyList by design
// (C2 contract, kept); callers that must tell "channel failed" apart from
// "channel ok" use profilesSnapshot() instead.
sealed interface ProfilesSnapshot {
    // `pm list users` parsed successfully (may still be an empty list).
    data class Loaded(val profiles: List<DualProfile>) : ProfilesSnapshot

    // The listing command itself failed (exit != 0 or error-semantic output
    // per the pm-list dedicated isListUsersOutputOk gate); detail is the
    // stderr/out reason.
    data class ChannelError(val detail: String) : ProfilesSnapshot
}

// Tri-state install check for one package on a pinned dual profile.
// Phase C3 (issue #9): the old Boolean verify() folds NotInstalled and
// Unknown together (both false, kept for compatibility). Callers that must
// display "confirmed absent" differently from "cannot check" use verifyState.
sealed interface VerifyState {
    // `pm list packages` ground truth contains the package (exact match).
    data object Installed : VerifyState

    // The list probe ran cleanly but the package is absent: confirmed absent.
    data object NotInstalled : VerifyState

    // The probe could not run (non-zero exit, guard refusal, or channel
    // failure): no knowledge.
    // Display suggestion (PR-2 Phase 3): detail may surface in UI/log, so
    // the display layer should bound its length (e.g. take(N)); the same
    // suggestion applies to the VerifyUnknown details on
    // CloneOutcome/RemoveOutcome. Bounded in practice: the trusted parser
    // already caps its exit/truncation hints (take(200)); the current
    // ViewModel interpolates the full detail directly (VM change
    // out-of-scope here).
    data class Unknown(val detail: String = "") : VerifyState
}

// Outcome of a pinned clone: resolve once, install and verify on the same id.
// Only Success means the clone is confirmed present; every other outcome
// must NOT be added to the UI's cloned list.
sealed interface CloneOutcome {
    data class Success(val dualId: Int) : CloneOutcome
    data object NoDual : CloneOutcome
    data class ChannelError(val detail: String) : CloneOutcome
    data class InvalidPackage(val pkg: String) : CloneOutcome
    data class InstallFailed(val detail: String) : CloneOutcome

    // Install reported success but the pinned re-check shows the package
    // absent: suspected fake success, never treat as cloned.
    data class VerifyFailed(val dualId: Int) : CloneOutcome

    // Install reported success but the re-check channel failed: unknown.
    data class VerifyUnknown(val dualId: Int, val detail: String = "") : CloneOutcome

    /**
     * Pinned serial-bound target moved between the batch pin and this item's
     * own snapshot (PR-37 Phase B, P1 #2 atomic pin): the caller's expected
     * (id, serial) no longer matches the live verified target. Zero installs
     * ran for this item. Callers map this to the existing drift handling
     * (partial + VERIFY_MISMATCH + stop + STALE), never to a per-item
     * failed/unknown record. [actualSerial] is null when the mismatch was
     * caught at the id gate (serial not yet re-fetched) or when the live
     * target carries no confirmable serial.
     */
    data class TargetMismatch(
        val expectedId: Int,
        val actualId: Int,
        val expectedSerial: Int?,
        val actualSerial: Int?,
    ) : CloneOutcome
}

// Outcome of a pinned remove: resolve once, uninstall and re-check on the
// same id. Only Success (post-check confirms absent) counts as removed.
sealed interface RemoveOutcome {
    data class Success(val dualId: Int) : RemoveOutcome
    data object NoDual : RemoveOutcome
    data class ChannelError(val detail: String) : RemoveOutcome
    data class InvalidPackage(val pkg: String) : RemoveOutcome
    data class RemoveFailed(val detail: String) : RemoveOutcome

    // Uninstall reported success but the package is still present.
    data class StillInstalled(val dualId: Int) : RemoveOutcome

    // Uninstall reported success but the re-check channel failed: unknown,
    // must NOT be dropped from the UI's cloned list (fail closed).
    data class VerifyUnknown(val dualId: Int, val detail: String = "") : RemoveOutcome

    /**
     * pinned 期望與當下 verified 不一致，零卸載，呼叫方應視為 INVALID 請重新確認。
     */
    data class TargetMoved(val expected: Int, val verified: Int) : RemoveOutcome

    /**
     * pinned serial 與當下 verified serial 不一致（或當下 serial 不可用），
     * 零卸載，呼叫方應視為 INVALID 請重新確認（與 TargetMoved 同式）。
     * null expected 表示呼叫方根本不可驗證（VM 必傳，此分支只應出現在程式化
     * 誤用）；null actual 表示當下目標無可用 serial。
     */
    data class SerialMismatch(val expected: Int?, val actual: Int?) : RemoveOutcome

    /**
     * confirm 前被取消，零卸載，呼叫方只清 loading 不覆寫 msg。
     */
    data object Cancelled : RemoveOutcome
}

// Outcome of a single-target refresh (Phase D P2-D, issue #21): one
// `pm list users` snapshot plus one verified-target fetch, then a list
// probe on that same id (no re-resolution). GroundTruth carries the pinned
// target so callers display the same id they listed; cloned == null means
// the list channel failed (unknown, keep the old display list).
sealed interface RefreshOutcome {
    data class ChannelError(val detail: String) : RefreshOutcome
    data class NoDual(val profiles: List<DualProfile>) : RefreshOutcome
    data class GroundTruth(
        val profiles: List<DualProfile>,
        val target: VerifiedTarget,
        val cloned: List<String>?,
    ) : RefreshOutcome
}

// Repository for dual-profile app lifecycle; all commands go through PmOutputParser.
// Pure Kotlin with no Android dependency; execution is delegated to CmdGateway.
// NOTE (PR-32 P2-1): the settle-wait default is cancellable (delay); a custom
// blocking delayFn (e.g. Thread.sleep) still needs off-Main dispatch -- the VM
// already wraps every repo call in Dispatchers.IO, so that path stays covered.
class DualRepository(
    private val gateway: CmdGateway,
) {
    // Retry settle wait (PR-3 stage 1, issue #24; PR-32 P2-1 cancellable):
    // the list-probe retry below waits VERIFY_RETRY_DELAY_MS between
    // attempts. Core depends on kotlinx-coroutines-core (CancellationException
    // discipline, PR-2 Phase 3), so the default is cancellable delay() -- a
    // Job cancel while waiting ends the wait immediately instead of sleeping
    // through it. The slot stays injectable so tests can go virtual
    // (no-op/record) or gate on entry; callers keep repo calls off Main
    // (DualViewModel runs every repo call under Dispatchers.IO), which also
    // covers a custom blocking delayFn.
    // TEST-ONLY: production must not overwrite. @Volatile gives visibility
    // across threads (test thread writes, IO thread reads) but no atomicity;
    // concurrent overwrite is not safe -- set once before use, never at runtime.
    @Volatile
    internal var delayFn: suspend (Long) -> Unit = { delay(it) }

    companion object {
        // Command used to enumerate profiles.
        const val CMD_LIST_USERS = "pm list users"

        // Verified-identity channels (PR-1 stage 2, issue #21): the two verbose
        // sources whose CLONE type tokens must agree, plus the Samsung dual
        // property. All are shell-readable on supported builds; when any typed
        // channel is unavailable the resolver refuses (fail closed) instead of
        // falling back to the pm-list label.
        const val CMD_USER_LIST_VERBOSE = "cmd user list -v"
        const val CMD_DUMPSYS_USER = "dumpsys user"
        const val CMD_GETPROP_DUAL = "getprop sys.dualapp.profile_id"

        // Settle wait between list-probe retries (PR-3 stage 1, issue #24).
        // Part of the retry policy (bounded: at most 2 waits per probe); the
        // wait itself runs through delayFn so tests can go virtual.
        const val VERIFY_RETRY_DELAY_MS = 1500L

        // Total list-probe attempts per verify (PR-3 stage 2, issue #24):
        // 1 initial + 2 retries, i.e. at most 2 waits.
        const val VERIFY_MAX_ATTEMPTS = 3
    }

    // Lists all profiles from `pm list users`; empty when the channel fails.
    // Phase C2 (issue #8) + P2-C: the pm-list dedicated gate
    // (isListUsersOutputOk: exit-first, header-stripped, no install-command
    // exemptions), so a `pm list users` that exits 0 but reports "Failure [...]"
    // on stderr is treated as failed (empty) instead of parsed as profiles,
    // while an Error/Failure-labelled UserInfo header stays display-only.
    // UserInfo{...} headers never participate in error matching, so normal
    // listings are unaffected. Delegates to profilesSnapshot(); use that directly when
    // ChannelError must be told apart from a genuinely empty parse (C3).
    suspend fun listProfiles(): List<DualProfile> =
        when (val snap = profilesSnapshot()) {
            is ProfilesSnapshot.Loaded -> snap.profiles
            is ProfilesSnapshot.ChannelError -> emptyList()
        }

    // Same listing as listProfiles() but keeps the channel-failure signal:
    // ChannelError when the `pm list users` command itself fails the pm-list
    // dedicated gate (isListUsersOutputOk: exit-first, header-stripped,
    // no install-command exemptions, so an Error/Failure-labelled UserInfo
    // header is display-only and never fails the channel), Loaded(parse)
    // otherwise. Single gateway call.
    // Gateway defense (PR-2 Phase 3, R5): a throwing gateway (spawn failure,
    // SecurityException, binder death surfaced as RuntimeException) maps to
    // ChannelError (fail closed), never a crash; CancellationException is
    // rethrown to stay coroutine-correct.
    suspend fun profilesSnapshot(): ProfilesSnapshot {
        val res = try {
            gateway.run(CMD_LIST_USERS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return ProfilesSnapshot.ChannelError("channel error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            return ProfilesSnapshot.ChannelError("channel error: ${e.message ?: e.javaClass.simpleName}")
        }
        // Review follow-up (cross-PR risk, review lines 49-51): identity
        // evidence truncation must fail closed. isListUsersOutputOk() (pm-list
        // label-neutral gate over the shared C2 exit + error-line rule,
        // Phase C P2-C issue #21) only judges exit + error-semantic lines
        // and never sees the capture flag, so an exit-0 truncated
        // `pm list users` would otherwise parse as a partial list and could
        // hide a conflicting candidate. Any truncated identity capture is
        // therefore ChannelError without parsing (never an authoritative
        // Loaded). Relation to gates: #21 owns WHO may be probed (verified
        // cross-source identity); #22 owns HOW a listing capture is trusted
        // (truncated capture is untrusted, never absence/presence evidence).
        // isSuccess() itself is unchanged: install/uninstall outputs are
        // assumed small and keep the exit + error-line rule.
        if (res.truncated) {
            return ProfilesSnapshot.ChannelError("channel error: truncated identity evidence")
        }
        if (!PmOutputParser.isListUsersOutputOk(res.out + "\n" + res.err, res.exit)) {
            return ProfilesSnapshot.ChannelError(res.err.ifEmpty { res.out.ifEmpty { "channel failed" } })
        }
        return ProfilesSnapshot.Loaded(PmOutputParser.parseUsers(res.out))
    }

    // Resolves the dual profile id via the verified cross-source boundary
    // (PR-1 stage 2, issue #21): `cmd user list -v` + `dumpsys user` must agree
    // on one CLONE-typed non-zero id (SamsungProfileAdapter.resolveDual);
    // the `pm list users` label is never an authorization source. Returns the
    // Confirmed id, or -1 when Unknown/Refused (fail closed). No "first
    // non-zero user" fallback by design.
    suspend fun resolveDualUserId(): Int = fetchVerifiedDualId()

    // Explicit verified entry: compat alias for resolveDualUserId() (the
    // canonical entry). Both run the same shared fetch; this overload exists
    // only so existing callers keep compiling.
    // For pure parsing without I/O use
    // PmOutputParser.resolveDualUserIdVerified(userListVerbose, dumpsysUser,
    // propertyId) directly.
    suspend fun resolveDualUserIdVerified(): Int = resolveDualUserId()

    // Shared identity fetch: delegates to the Phase-2 shared fetcher
    // (see VerifiedIdentity.kt), the single source every resolver and both
    // Elevator probes share. Method name kept so callers keep compiling.
    // Never throws: gateway errors collapse to -1 (fail closed); only
    // coroutine cancellation is rethrown by the shared fetcher itself.
    private suspend fun fetchVerifiedDualId(): Int = gateway.fetchVerifiedDualId()

    // Typed verified entry (Round 2 Phase B P2-5): same three gateway runs
    // and the same fail-closed channel checks as the shared fetcher above,
    // but keeps the failure reason instead of folding everything to -1:
    // - Confirmed(id): clean channels whose CLONE claims agree on one id.
    // - Absent: 僅當兩來源對所有相關 user 給出明確且一致的非 CLONE 類型
    //   （每條 claim 皆為 OTHER、無 UNKNOWN、兩源 user 集合相同、無 CLONE、
    //   無衝突），且 property 為乾淨無聲明（白名單制三態，見下方 property 閘：
    //   Blank（out+err 皆 blank）或負值 sentinel（trim 後 toIntOrNull() 為負值，
    //   為 null，維持「負值=no claim」意圖）才算無聲明；Value（含 0）若無對應
    //   Confirmed 即 Unknown；Malformed（out 非空非 sentinel 但 parse 失敗，含溢
    //   位，或 stderr 殘留）直接 Unknown，絕不進入 Absent）。其餘一律 Unknown，
    //   絕不清空舊單。另收 pmIds 否決（見參數）：pm 快照多出的戶即 typed 漏戶，
    //   不得 Absent。與 canonical VerifiedIdentity.kt 一致：null 只通往 Unknown
    //  （canonical 要求 property corroboration，null 永不 Confirm）；typed 的
    //   Absent 是額外收斂，僅乾淨無聲明＋乾淨 OTHER 集合才可達，髒通道永不收斂。
    // - Unknown: 通道截斷/失敗（exit/identity 閘）、輸出不可信、證據矛盾
    //   （Refused）、單源 clone、型別缺失、集合不一致、property 指向不明
    //   （含拋出而無回應的 property 通道、非空但 parse 失敗的 malformed payload
    //   含溢位、stderr 殘留髒通道）、pm 快照否決。
    // Never throws except on coroutine cancellation. The legacy Int entries
    // above are untouched; this is purely additive.
    // NOTE (p25-rebase adapt): the Int path above stays delegated to the
    // shared fetcher (single source, no duplication); only this typed entry
    // runs its own three gateway calls because it needs per-channel failure
    // reasons the Int seam cannot carry.
    // @param pmIds pm 快照戶集合（VM 傳 profiles id 集合；null 表無快照不否決）：
    //   Absent 須另滿足 pmIds==null 或 pmIds 為 verboseIds 子集，否則 pm
    //   多出的戶即 typed 漏戶，一律 Unknown（雙 verbose 一致漏戶誤報修正）。
    //   加法參數（預設 null），舊呼叫端語意不變。
    suspend fun resolveDualUserIdTyped(pmIds: Set<Int>? = null): DualIdResolution {
        val verboseRes = try {
            gateway.run(CMD_USER_LIST_VERBOSE)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return DualIdResolution.Unknown("typed channel interrupted: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DualIdResolution.Unknown("typed channel threw: ${e.message ?: e.javaClass.simpleName}")
        }
        val dumpsysRes = try {
            gateway.run(CMD_DUMPSYS_USER)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return DualIdResolution.Unknown("typed channel interrupted: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DualIdResolution.Unknown("typed channel threw: ${e.message ?: e.javaClass.simpleName}")
        }
        // P1-2（與 canonical fetchVerifiedTarget 同驗證）：截斷一律拒絕，
        // 身分通道改用 isIdentityChannelOk（不用 isSuccess，避免
        // INSTALL_FAILED_ALREADY_EXISTS 例外放行身分證據）。
        if (verboseRes.truncated || dumpsysRes.truncated) {
            return DualIdResolution.Unknown("typed channel truncated identity evidence")
        }
        if (verboseRes.exit != 0 || dumpsysRes.exit != 0) {
            return DualIdResolution.Unknown(
                "typed channel exit (verbose=${verboseRes.exit} dumpsys=${dumpsysRes.exit})",
            )
        }
        if (!PmOutputParser.isIdentityChannelOk(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) {
            return DualIdResolution.Unknown("typed verbose channel reports failure")
        }
        if (!PmOutputParser.isIdentityChannelOk(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) {
            return DualIdResolution.Unknown("typed dumpsys channel reports failure")
        }
        val propRes = try {
            gateway.run(CMD_GETPROP_DUAL)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return DualIdResolution.Unknown("typed property channel interrupted: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // M1 拋出與乾淨空值區分：gateway 拋出（無回應）不得視為無聲明，
            // 直接 Unknown；只有「identity 閘通過＋解析為 null 的非 null
            // propRes」才是無聲明（見下方 propertyId==null 放行 Absent）。
            return DualIdResolution.Unknown("typed property channel threw: ${e.message ?: e.javaClass.simpleName}")
        }
        // 截斷 property 非「無聲明」：切掉的 payload 可能藏 mismatch，fail closed。
        if (propRes.truncated) {
            return DualIdResolution.Unknown("typed property channel truncated identity evidence")
        }
        // property 通道亦走身分閘：髒輸出即 Unknown（canonical 經缺 property
        // 走向 Unknown，此處直接 Unknown 以擋住後續誤判 Absent；拋出已在上
        // 方直接 Unknown，不再落到 null 視為無聲明）。
        if (!PmOutputParser.isIdentityChannelOk(propRes.out + "\n" + propRes.err, propRes.exit)) {
            return DualIdResolution.Unknown("typed property channel reports failure")
        }
        // 白名單制三態（P0 fail-closed）：property 解析後顯式區分 Blank /
        // Sentinel（無聲明，可往 Absent 收斂）vs Malformed（Unknown，擋 Absent）
        // vs Value（非 null，後續無 Confirmed 即 Unknown）。
        // - 乾淨無聲明僅指 out.isBlank() && err.isBlank()（乾淨空輸出），或明確
        //   sentinel（out.trim().toIntOrNull() 為負值，如 "-1"/"-5"/
        //   "-2147483648"，parser 本就歸一為 null，維持「負值=no claim」意圖，
        //   且須 err 全空，stderr 殘留即髒通道，對照 parsePackagesTrusted 的
        //   stderr 白名單；Int 溢位如 "-9999999999"/"-2147483649"（toIntOrNull
        //   為 null）不算 sentinel，落到下方 malformed → Unknown）。
        // - 其餘 propertyId==null（out 非空、非負值 sentinel、但 parse 失敗，含溢
        //   位如 "9999999999"/"2147483648"、多行/尾隨 token 如 "95\ntrailing" /
        //   "95\n96\n"、非數值如 "unknown"/"unsupported_value"/"+95"/"95.0"）
        //   直接 Unknown("typed property channel malformed...")，detail 截斷 200
        //   字（沿用 parsePackagesTrusted 的 take(200) 慣例），不得進入 Absent。
        // 與 canonical VerifiedIdentity.kt 一致：null 永遠只通往 Unknown（絕不
        // Confirm）；typed 的 Absent 僅乾淨無聲明才可達，髒通道永不收斂為 Absent。
        val propertyId = PmOutputParser.parseDualPropertyId(propRes.out)
        if (propertyId == null) {
            val propOutBlank = propRes.out.isBlank()
            val propErrBlank = propRes.err.isBlank()
            // 值域檢查（非每-call Regex 語法匹配）：僅 Int 範圍內負值為 sentinel；
            // 溢位（toIntOrNull null）不算 sentinel；"-0" parse 得 0 非 null，
            // 不進此分支，後續擋 Unknown。
            val isNegativeSentinel = propRes.out.trim().toIntOrNull()?.let { it < 0 } == true
            val cleanNoClaim = (propOutBlank && propErrBlank) || (isNegativeSentinel && propErrBlank)
            if (!cleanNoClaim) {
                val raw = if (!propOutBlank) propRes.out.trim() else propRes.err.trim()
                return DualIdResolution.Unknown("typed property channel malformed: ${raw.take(200)}")
            }
        }
        val verboseClaims = SamsungProfileAdapter.parseUserListVerbose(verboseRes.out)
        val dumpsysClaims = SamsungProfileAdapter.parseDumpsysUser(dumpsysRes.out)
        val candidates = verboseClaims + dumpsysClaims
        return when (val resolution = SamsungProfileAdapter.resolveDual(candidates, propertyId)) {
            is DualResolution.Confirmed -> DualIdResolution.Confirmed(resolution.userId)
            is DualResolution.Refused -> DualIdResolution.Unknown(resolution.reason)
            is DualResolution.Unknown -> {
                // P1-1（False Absent 修正）：Absent 僅當兩源皆有真實區塊、無
                // 任何 CLONE 聲稱、每條 claim 皆為明確 OTHER（無 UNKNOWN）、兩
                // 源 user 集合完全一致（無衝突/缺戶），且 property 無具體指向
                // （任何非 null 含 0 但無 Confirmed 即為仍可能存在的 clone）；其餘
                // （型別缺失、集合不一致、property 指向不明等）一律 Unknown。
                if (candidates.any { it.userType == ProfileUserType.CLONE }) {
                    return DualIdResolution.Unknown("insufficient identity evidence")
                }
                if (verboseClaims.isEmpty() || dumpsysClaims.isEmpty()) {
                    return DualIdResolution.Unknown("insufficient identity evidence")
                }
                if (verboseClaims.any { it.userType != ProfileUserType.OTHER } ||
                    dumpsysClaims.any { it.userType != ProfileUserType.OTHER }
                ) {
                    return DualIdResolution.Unknown("insufficient identity evidence")
                }
                val verboseIds = verboseClaims.map { it.userId }.toSet()
                val dumpsysIds = dumpsysClaims.map { it.userId }.toSet()
                if (verboseIds != dumpsysIds) {
                    return DualIdResolution.Unknown("insufficient identity evidence")
                }
                if (propertyId != null) {
                    return DualIdResolution.Unknown("insufficient identity evidence")
                }
                // M2 pm 否決：pm 快照多出的戶即雙 verbose 一致漏戶，不得 Absent。
                // null 表無快照不否決；否則須 pmIds 為 verboseIds 子集。
                if (pmIds != null && !verboseIds.containsAll(pmIds)) {
                    return DualIdResolution.Unknown("pm snapshot contains ids missing from typed channels")
                }
                DualIdResolution.Absent
            }
        }
    }

    // Phase D target fetch: same shared fetch as above but preserves the
    // serial/parentId continuity signals for ID-reuse protection. Null means
    // Unknown/Refused/channel failure (fail closed).
    private suspend fun fetchVerifiedTarget(): VerifiedTarget? = gateway.fetchVerifiedTarget()

    // Single shared serial-bound identity fetch (PR-33 R2, P1; single-source
    // policy PR-33 D2): same three gateway runs, CancellationException
    // rethrow, and fail-closed mapping as fetchVerifiedDualId, but keeps the
    // agreed serial. A confirmed id maps to Confirmed(target) only when the
    // verbose channels yield exactly one agreed non-null serial (at least
    // one side must carry it -- new-ROM verbose has no serial line, so a
    // dumpsys-only serial confirms; both-missing or disagreeing still
    // refuses); a confirmed id without an agreed serial maps
    // to SerialUnavailable; an unconfirmed id (or any failed channel) maps
    // to NoDual. Never throws except on coroutine cancellation.
    suspend fun fetchDualTarget(): DualTargetResult {
        val verboseRes = try {
            gateway.run(CMD_USER_LIST_VERBOSE)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DualTargetResult.NoDual
        }
        val dumpsysRes = try {
            gateway.run(CMD_DUMPSYS_USER)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return DualTargetResult.NoDual
        }
        if (verboseRes.truncated || dumpsysRes.truncated) return DualTargetResult.NoDual
        if (verboseRes.exit != 0 || dumpsysRes.exit != 0) return DualTargetResult.NoDual
        // Channel hygiene (Phase F review MAJOR-1): same three gates as
        // VerifiedIdentity.fetchVerifiedTarget (truncated + isIdentityChannelOk
        // + property identity gate). isIdentityChannelOk is the strict
        // identity rule (no install-only exemptions/header-strip), not the
        // pm-channel isSuccess used for install/uninstall outputs.
        if (!PmOutputParser.isIdentityChannelOk(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) {
            return DualTargetResult.NoDual
        }
        if (!PmOutputParser.isIdentityChannelOk(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) {
            return DualTargetResult.NoDual
        }
        val propRes = try {
            gateway.run(CMD_GETPROP_DUAL)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        // Truncated property is not "no claim": a cut payload may hide a
        // mismatch, so fail closed to NoDual (same as fetchVerifiedTarget).
        if (propRes != null && propRes.truncated) return DualTargetResult.NoDual
        val propertyId = propRes
            ?.takeIf { PmOutputParser.isIdentityChannelOk(it.out + "\n" + it.err, it.exit) }
            ?.let { PmOutputParser.parseDualPropertyId(it.out) }
        // Id first (same resolver as fetchVerifiedDualId): unconfirmed means
        // there is no target at all, regardless of serial lines.
        val verifiedId =
            PmOutputParser.resolveDualUserIdVerified(verboseRes.out, dumpsysRes.out, propertyId)
        if (verifiedId < 0) return DualTargetResult.NoDual
        val target =
            PmOutputParser.resolveDualTargetVerified(verboseRes.out, dumpsysRes.out, propertyId)
                ?: return DualTargetResult.SerialUnavailable
        return DualTargetResult.Confirmed(target)
    }

    // Clones a package into the dual profile via `pm install-existing`.
    // Auto path carries the resolve-time identity as `expected`: the fresh
    // re-check inside the write must still agree on serial/parentId
    // (both-present-and-unequal refuses, suspecting ID reuse; a known
    // expected.serial followed by a missing fresh.serial refuses as
    // continuity lost); a null resolve maps to the original
    // "no dual profile" refusal.
    suspend fun clone(pkg: String): CmdResult {
        val expected = fetchVerifiedTarget()
        if (expected == null) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return clone(pkg, expected.userId, expected)
    }

    // Clones a package into an explicit dual profile id.
    // Verified-dual guard: refuses dualId 0 (owner), ids absent from the
    // current `pm list users` snapshot, and ids that are not the Confirmed
    // cross-source target, so a stale, mistyped, or non-Dual id can never
    // redirect the write at the wrong profile.
    // Residual TOCTOU note: the explicit-id entry checks only fresh
    // self-consistency (expected = null); a profile deleted and recreated
    // under the same id between this check and the write is not detected
    // here. Auto/pinned flows carry `expected` (or re-check after the
    // write) to close that window; explicit callers accept the residual.
    suspend fun clone(pkg: String, dualId: Int): CmdResult = clone(pkg, dualId, null)

    // Private write carrying the resolve-time identity for ID-reuse
    // protection (Phase D): `expected` is the auto-path target; the fresh
    // target must still agree on serial/parentId (see checkExplicitDualId).
    private suspend fun clone(pkg: String, dualId: Int, expected: VerifiedTarget?): CmdResult {
        checkExplicitDualId(dualId, expected)?.let { return it }
        val cmd = PmOutputParser.buildInstallExisting(pkg, dualId)
        // Gateway defense (same R5 discipline as the verified paths): a
        // throwing gateway maps to a fail-closed CmdResult, never a crash;
        // CancellationException is rethrown to stay coroutine-correct.
        try {
            return gateway.run(cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CmdResult(exit = 1, out = "", err = "channel error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // Removes the clone from the dual profile via `pm uninstall --user`.
    // Auto path carries the resolve-time identity as `expected` (same
    // ID-reuse guard as clone(pkg) above).
    suspend fun remove(pkg: String): CmdResult {
        val expected = fetchVerifiedTarget()
        if (expected == null) return CmdResult(exit = 1, out = "", err = "no dual profile")
        return remove(pkg, expected.userId, expected)
    }

    // Removes the clone from an explicit dual profile id.
    // Same verified-dual guard as clone(pkg, dualId): see checkExplicitDualId.
    // Residual TOCTOU note: same as the explicit clone entry above
    // (expected = null, fresh self-consistency only).
    suspend fun remove(pkg: String, dualId: Int): CmdResult = remove(pkg, dualId, null)

    // Private write carrying the resolve-time identity (Phase D, same as
    // the private clone overload above).
    private suspend fun remove(pkg: String, dualId: Int, expected: VerifiedTarget?): CmdResult {
        checkExplicitDualId(dualId, expected)?.let { return it }
        val cmd = PmOutputParser.buildUninstall(pkg, dualId)
        // Gateway defense (same R5 discipline as the verified paths): a
        // throwing gateway maps to a fail-closed CmdResult, never a crash;
        // CancellationException is rethrown to stay coroutine-correct.
        try {
            return gateway.run(cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CmdResult(exit = 1, out = "", err = "channel error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // Validates an explicit dual profile id against the current snapshot AND the
    // verified cross-source identity (PR-1 stage 2, issue #21), plus Phase D
    // ID-reuse protection: when `expected` (the resolve-time target carried
    // by auto paths) is non-null and the fresh target still names the same
    // id, both-present-and-unequal serial/parentId refuses (the id was
    // reused by a different profile incarnation). Cross-fetch serial
    // continuity (Phase A P1-A, issue #21): a known expected.serial followed
    // by a missing fresh.serial also refuses (continuity/reuse suspected,
    // executed before any write command); expected==null or
    // expected.serial==null stays tolerated (Phase B upgrade path, same as
    // the single-fetch resolver tolerance). Only a positive disagreement or
    // a known-to-unknown serial loss refuses.
    // Parent needs no known-to-unknown rule: the resolver already requires
    // both verbose channels to carry a known, equal parentId == 0 before it
    // Confirms, so any non-null fresh target carries parentId non-null and
    // == 0 by construction -- a known-to-unknown parent transition is
    // unrepresentable while the id still Confirms (fresh==null or id drift
    // already refuses above).
    // Returns a refusal CmdResult, or null when the id is safe to target.
    // Refuses dualId 0 (owner profile, must never be written) and any id not
    // present in this call's `pm list users` snapshot (stale/mistyped id),
    // plus any id that is not the currently Confirmed dual target (exists but
    // non-Dual: Work/Secondary/Secure Folder, renamed DUAL-label, type
    // conflict, property mismatch, or unknown). Existence alone never suffices.
    // Phase D + Phase A P1-A continuity helper (issue #21): centralizes the
    // serial/parent cross-fetch comparison shared by checkExplicitDualId,
    // verifyOnProfiles, listOnProfiles and checkTargetDrift. userId is NOT
    // compared here (callers check id drift/vanish separately). Returns null
    // when every comparable signal agrees; otherwise returns the breach
    // reason (non-null means breach). Serial known->unknown is continuity
    // lost; both-present-and-unequal serial/parent each breach. Callers wrap
    // with "refused: ..." (auto paths) or "identity-changed: ..." (pinned
    // drift) so existing reason tokens ("reuse"/"continuity"/
    // "identity-changed") are preserved and external outcomes (refused exit
    // 1 / Unknown / null / VerifyUnknown) are unchanged.
    private fun continuityBreach(expected: VerifiedTarget, fresh: VerifiedTarget): String? {
        if (expected.serial != null && fresh.serial == null) {
            return "continuity lost: serial known expected=${expected.serial} but fresh missing"
        }
        if (expected.serial != null && fresh.serial != null && expected.serial != fresh.serial) {
            return "serial changed expected=${expected.serial} fresh=${fresh.serial}"
        }
        if (expected.parentId != null && fresh.parentId != null && expected.parentId != fresh.parentId) {
            return "parent changed expected=${expected.parentId} fresh=${fresh.parentId}"
        }
        return null
    }

    private suspend fun checkExplicitDualId(dualId: Int, expected: VerifiedTarget? = null): CmdResult? {
        if (dualId <= 0) {
            return CmdResult(
                exit = 1,
                out = "",
                err = "refused: invalid dual profile id ($dualId), owner profile is off-limits",
            )
        }
        val snapshot = listProfiles()
        if (snapshot.none { it.userId == dualId }) {
            return CmdResult(
                exit = 1,
                out = "",
                err = "refused: unknown dual profile id ($dualId), not in current snapshot",
            )
        }
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) {
            return CmdResult(
                exit = 1,
                out = "",
                err = "refused: id $dualId is not the verified dual target" +
                    " (verified=${fresh?.userId?.toString() ?: "none"})",
            )
        }
        if (expected != null) {
            continuityBreach(expected, fresh)?.let { breach ->
                return CmdResult(
                    exit = 1,
                    out = "",
                    err = "refused: id $dualId identity reuse suspected" +
                        " ($breach)",
                )
            }
        }
        return null
    }

    // Double-checks installation via `pm list packages --user <dualId>`
    // ground truth (exact membership). Returns true only when the probe
    // parses as TrustedFull containing the package. Auto path carries
    // the resolve-time identity as `expected` (Phase D ID-reuse guard);
    // a null resolve maps to false (same as the old -1 behavior).
    suspend fun verify(pkg: String): Boolean {
        val expected = fetchVerifiedTarget() ?: return false
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return false
        return verifyOnProfiles(pkg, expected.userId, (snap as ProfilesSnapshot.Loaded).profiles, expected) is VerifyState.Installed
    }

    // Verifies against an explicit dual profile id.
    // Same verified-dual guard as clone(pkg, dualId)/remove(pkg, dualId):
    // refuses dualId 0 (owner), ids absent from the current snapshot, and ids
    // that are not the currently Confirmed dual target (exists but non-Dual)
    // without running any command (returns false). Read-only path, but
    // consistency first: a stale, mistyped, or non-Dual id must never be
    // probed, and callers get graceful false instead of an
    // IllegalArgumentException from the command builder.
    // Residual TOCTOU note: explicit-id entries check only fresh
    // self-consistency (expected = null); see the explicit clone entry.
    //
    // DEPRECATED (PR-1 stage 2, issue #21): the Boolean folds NotInstalled and
    // Unknown together (both false). Callers that must tell "confirmed absent"
    // apart from "cannot check" must use verifyState(pkg, dualId) instead
    // (tri-state contract is owned by PR-2, issue #22, and is unchanged here).
    @Deprecated(
        message = "Boolean verify folds NotInstalled and Unknown; use verifyState for tri-state.",
        ReplaceWith("verifyState(pkg, dualId)"),
    )
    suspend fun verify(pkg: String, dualId: Int): Boolean =
        when (val state = verifyState(pkg, dualId)) {
            is VerifyState.Installed -> true
            is VerifyState.NotInstalled -> false
            is VerifyState.Unknown -> false
        }

    // Tri-state variant of verify(pkg, dualId): Installed only when the
    // `pm list packages` probe parses as TrustedFull containing the package
    // (exact match); NotInstalled on a whitelist-clean listing that lacks
    // it (TrustedFull of other packages -- every stdout line a legal
    // package: line and stderr blank -- or TrustedEmpty: confirmed absent);
    // Unknown when the id is guard-refused, the package token is invalid, or
    // the probe parses as Unknown (truncated capture, non-zero exit after
    // retries, error-semantic text per the shared C2 line rule, or any
    // unexpected-format stdout line / unexplained stderr residue per the
    // trusted structural whitelist). C1 guard is unchanged; the mapping
    // itself is owned by verifyListProbe (PR-2 stage 2, issue #22).
    // Residual TOCTOU note: explicit-id entries check only fresh
    // self-consistency (expected = null); auto verify(pkg) carries expected.
    suspend fun verifyState(pkg: String, dualId: Int): VerifyState {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return VerifyState.Unknown(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        return verifyOnProfiles(pkg, dualId, profiles, null)
    }

    // Lists the dual profile's actual packages (ground truth for refresh).
    // Null means unknown: no verified dual profile, channel failure, guard
    // refusal, or an untrusted listing (truncated / non-zero /
    // error-semantic / unexpected-format stdout / unexplained stderr:
    // never an authoritative empty). Non-null (possibly empty) is
    // authoritative: refresh rebuilds
    // `cloned` from it, so external installs/removals sync and a fresh
    // ViewModel discovers pre-existing clones. Uses `pm list packages
    // --user <dualId>` (see PmOutputParser.buildListPackages); per-package
    // `pm path` probing alone could never discover names nobody asked about.
    // Resolution is verified cross-source (stage 2): a DUAL-labeled but
    // untyped id never authorizes a listing. Auto path carries the
    // resolve-time identity as `expected` (Phase D ID-reuse guard).
    suspend fun listClonedPackages(): List<String>? {
        val expected = fetchVerifiedTarget() ?: return null
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return null
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        if (profiles.none { it.userId == expected.userId }) return null
        return listOnProfiles(expected.userId, profiles, expected)
    }

    // Explicit-id variant with the same verified-dual guard (stage 2) as clone(pkg, dualId):
    // dualId 0 and ids absent from this call's snapshot yield null (unknown)
    // without probing; an invalid builder token also yields null.
    // Residual TOCTOU note: explicit-id entries check only fresh
    // self-consistency (expected = null); auto listClonedPackages() carries expected.
    suspend fun listClonedPackages(dualId: Int): List<String>? {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return null
        return listOnProfiles(dualId, (snap as ProfilesSnapshot.Loaded).profiles, null)
    }

    // Pinned clone (Phase C3, issue #9; Phase D target pinning): captures the
    // verified target BEFORE the write, then re-fetches AFTER the write and
    // requires same id plus serial/parentId continuity (both-present-equal;
    // a known before.serial followed by a missing after.serial is drift)
    // before the `pm list packages` re-check. A pre-write fresh-vs-before re-check narrows
    // the before-to-write reuse window (a residual fresh-to-write micro-window
    // remains, fail closed): drift returns VerifyUnknown without
    // emitting any write command (fail closed). A null/drifted after-target maps to
    // VerifyUnknown("identity-changed ...") fail closed (never Success, never
    // counted as cloned). Success is reported only when the re-check then
    // confirms Installed; a clean-but-absent re-check is VerifyFailed
    // (fake success, issue #9), a failed re-check channel is VerifyUnknown.
    // Neither is added to cloned. Unknown/Refused identity maps to NoDual
    // (fail closed); the pinned re-check itself does not re-resolve identity
    // beyond the before/fresh/after pin so a write can never be redirected to
    // a different user (the after-to-probe window is read-only, see below).
    suspend fun cloneVerified(pkg: String): CloneOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return CloneOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val before = fetchVerifiedTarget()
        if (before == null) return CloneOutcome.NoDual
        val dualId = before.userId
        if (profiles.none { it.userId == dualId }) return CloneOutcome.NoDual
        val fresh = fetchVerifiedTarget()
        val preDrift = checkTargetDrift(before, fresh)
        if (preDrift != null) return CloneOutcome.VerifyUnknown(dualId, preDrift)
        val clean = pkg.trim()
        val installCmd = try {
            PmOutputParser.buildInstallExisting(clean, dualId)
        } catch (e: IllegalArgumentException) {
            return CloneOutcome.InvalidPackage(clean)
        }
        // Write-side gateway defense (R5, same as the probe path): a throwing
        // gateway (spawn failure, SecurityException, binder death) maps to
        // ChannelError (fail closed), never a crash; CancellationException
        // is rethrown to stay coroutine-correct.
        val res = try {
            gateway.run(installCmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CloneOutcome.ChannelError("channel error: ${e.message ?: e.javaClass.simpleName}")
        }
        // Unknown-after-send (PR-32 P1-1 timeout, extended by P1-2
        // transport-unknown, extended by Phase A1 post-spawn text): the reply
        // was abandoned or lost -- the remote pm may still have executed --
        // so exit/out/err are untrusted regardless of isSuccess. NEVER map
        // this to InstallFailed (that would claim "not cloned" while the
        // device may hold the clone, and the UI would drop it wrongly).
        // Instead run ONE bounded read-only verifyListProbe (same retry/
        // delayFn/thrown-defense as the normal path, no write resend) with
        // an after-pin first: Installed -> Success, clean-but-absent ->
        // VerifyUnknown (reverse, write may still land), Unknown ->
        // VerifyUnknown (nothing is added; the next refresh converges).
        // Trigger (isUnknownAfterSend): res.timedOut (client abandoned the
        // wait) OR res.transportUnknown (post-dispatch reply loss: transact
        // threw, binder died mid-call, reply decode failed, su wait/drain
        // interrupt -- timedOut=false with the flag set) OR
        // isServiceTimeoutText(res.err) -- the GalaxyExecService 60 s
        // captureProcess timeout arrives over the parcel as exit=1 + its
        // "timeout after ..." text with both flags false (only the marker
        // survives the (exit, out, err) reply) OR
        // isPostSpawnTransportText(res.err) -- the Phase A1 wire-frozen
        // backstop ("interrupted: ..." from a post-spawn wait/drain
        // interrupt, "service exec failed: ..." from the service catch after
        // spawn). Anchored prefixes exclude the bind-failure wrapper,
        // "spawn failed: ..." (pre-spawn definite: exec threw, process never
        // started), and ordinary "timeout"/"Failure" words in pm output.
        // Scope: only these four signals take this path. Pre-dispatch
        // refusals (command never sent: no binder, not authorized, bind
        // failure -- both flags false, plain err text), pre-spawn spawn
        // failures, and explicit pm failures (pm reports Failure[...]) stay
        // on the isSuccess judgement below -> definite InstallFailed.
        // After-pin (Phase E P1-1): the probe below is the report-only
        // evidence, but the serial may have changed while the client was
        // waiting -- re-fetch and require the before/after pin to still hold
        // before trusting a same-direction probe as Success, so a recreated
        // profile in the wait window is VerifyUnknown instead of Success.
        // Reverse observation (clean-but-absent here) is VerifyUnknown, NOT
        // VerifyFailed: the probe ran right after the timeout and the write
        // may still land after it (unknown-after-send) -- never claim
        // "not cloned" while the device may hold the clone. The next
        // refresh converges from ground truth.
        if (isUnknownAfterSend(res)) {
            val transportLost = res.transportUnknown
            val byText = !res.timedOut && !transportLost && isServiceTimeoutText(res.err)
            val postSpawnByText = !res.timedOut && !transportLost && isPostSpawnTransportText(res.err)
            val after = fetchVerifiedTarget()
            val drift = checkTargetDrift(before, after)
            if (drift != null) return CloneOutcome.VerifyUnknown(dualId, drift)
            return when (val state = verifyListProbe(clean, dualId)) {
                is VerifyState.Installed -> CloneOutcome.Success(dualId)
                is VerifyState.NotInstalled ->
                    CloneOutcome.VerifyUnknown(
                        dualId,
                        writeTimeoutReverseDetail(observed = "absent", intended = "clone", byText = byText, transportLost = transportLost, postSpawnByText = postSpawnByText, writeErr = res.err),
                    )
                is VerifyState.Unknown -> CloneOutcome.VerifyUnknown(dualId, writeTimeoutDetail(state.detail, byText = byText, transportLost = transportLost, postSpawnByText = postSpawnByText, writeErr = res.err))
            }
        }
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
            return CloneOutcome.InstallFailed(res.err.ifEmpty { res.out.ifEmpty { "clone failed" } })
        }
        val after = fetchVerifiedTarget()
        val drift = checkTargetDrift(before, after)
        if (drift != null) return CloneOutcome.VerifyUnknown(dualId, drift)
        return when (val state = verifyListProbe(clean, dualId)) {
            is VerifyState.Installed -> CloneOutcome.Success(dualId)
            is VerifyState.NotInstalled -> CloneOutcome.VerifyFailed(dualId)
            is VerifyState.Unknown -> CloneOutcome.VerifyUnknown(dualId, state.detail)
        }
    }

    /**
     * Pinned clone with an expected serial-bound target (PR-37 Phase B, P1 #2
     * atomic pin, issue #26): eliminates the preflight→clone TOCTOU by
     * pinning inside a single flow. The caller passes the batch-time pinned
     * ([expectedDualId], [expectedSerial]); this call snapshots, re-resolves
     * the verified id AND the serial-bound target, and refuses (fail closed,
     * zero installs) when either no longer matches the expectation:
     * a confirmed Absent (clean identity channels agree no dual exists)
     * maps to [CloneOutcome.NoDual]; an unconfirmable identity (failed or
     * noisy identity channel, contradiction, or too-thin evidence) maps to
     * [CloneOutcome.ChannelError] (never "no dual", never short-circuited);
     * a confirmed id that disagrees with [expectedDualId] maps to
     * [CloneOutcome.TargetMismatch];
     * serial mismatch -- including a null [expectedSerial] (the caller cannot
     * verify at all) or an unavailable live serial -- maps to
     * [CloneOutcome.TargetMismatch]. Only a full (id, serial) match runs the
     * original install + verify flow on [expectedDualId].
     *
     * PR-37 Phase C (review R4 #5): the id gate uses the typed identity
     * ([resolveDualUserIdTyped]) instead of the legacy Int fetch, so Absent
     * and Unknown no longer fold into one NoDual. Callers must not treat
     * ChannelError here as "no dual" (batch counts it as unknown).
     *
     * Additive only: [cloneVerified] is untouched and keeps its exact
     * behavior; single callers keep using it.
     */
    suspend fun cloneVerifiedExpected(
        pkg: String,
        expectedDualId: Int,
        expectedSerial: Int?,
    ): CloneOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return CloneOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        // PR-37 Phase C (review R4 #5): typed identity separates a confirmed
        // Absent (genuinely no dual: clean channels parsed to real user
        // blocks on both sources yet claim no dual) from Unknown (the channel
        // cannot decide right now: exit != 0, noisy error-semantic output,
        // or a throw). Absent stays NoDual (M13 short-circuit unchanged);
        // Unknown is ChannelError (fail closed, zero installs; the batch
        // counts it as unknown, never "no dual"). The legacy Int fetch above
        // folds both to -1 and must not be used here.
        if (expectedDualId < 0) return CloneOutcome.NoDual
        // M2 pm 反證守衛：Absent 另須 pm 快照無 typed 漏戶（null 不否決；
        // 此處快照可用故傳 profiles id 集合，與 refreshLocked/clone-NoDual/
        // requestRemove-NoDual 一致）。僅 Absent↔Unknown 受影響，Confirmed
        // 路徑不變；Absent 仍 NoDual、Unknown 仍 ChannelError。
        when (val idRes = resolveDualUserIdTyped(pmIds = profiles.map { it.userId }.toSet())) {
            is DualIdResolution.Absent -> return CloneOutcome.NoDual
            is DualIdResolution.Unknown ->
                return CloneOutcome.ChannelError(
                    "verified dual id unknown" +
                        (idRes.detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                )
            is DualIdResolution.Confirmed -> {
                if (idRes.userId != expectedDualId) {
                    return CloneOutcome.TargetMismatch(
                        expectedId = expectedDualId,
                        actualId = idRes.userId,
                        expectedSerial = expectedSerial,
                        // Id gate fires before the serial re-fetch below, so the
                        // live serial is not yet known here (null, documented).
                        actualSerial = null,
                    )
                }
            }
        }
        if (profiles.none { it.userId == expectedDualId }) return CloneOutcome.NoDual
        // Serial gate AFTER the id gate, BEFORE any install (mirrors
        // removeVerifiedExpected): a recreated profile (same id, fresh
        // serial) must never receive the install.
        val second = fetchDualTarget()
        if (second is DualTargetResult.Confirmed && second.target.userId != expectedDualId) {
            return CloneOutcome.TargetMismatch(
                expectedId = expectedDualId,
                actualId = second.target.userId,
                expectedSerial = expectedSerial,
                actualSerial = second.target.serial,
            )
        }
        val actualSerial = (second as? DualTargetResult.Confirmed)?.target?.serial
        if (expectedSerial == null || actualSerial != expectedSerial) {
            return CloneOutcome.TargetMismatch(
                expectedId = expectedDualId,
                actualId = expectedDualId,
                expectedSerial = expectedSerial,
                actualSerial = actualSerial,
            )
        }
        val clean = pkg.trim()
        val installCmd = try {
            PmOutputParser.buildInstallExisting(clean, expectedDualId)
        } catch (e: IllegalArgumentException) {
            return CloneOutcome.InvalidPackage(clean)
        }
        val res = gateway.run(installCmd)
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
            return CloneOutcome.InstallFailed(res.err.ifEmpty { res.out.ifEmpty { "clone failed" } })
        }
        return when (val state = verifyListProbe(clean, expectedDualId)) {
            is VerifyState.Installed -> CloneOutcome.Success(expectedDualId)
            is VerifyState.NotInstalled -> CloneOutcome.VerifyFailed(expectedDualId)
            is VerifyState.Unknown -> CloneOutcome.VerifyUnknown(expectedDualId, state.detail)
        }
    }

    // Pinned remove (Phase C3, issue #9; Phase D target pinning): same
    // before/fresh/after identity pin as cloneVerified. A pre-write fresh-vs-before
    // re-check narrows the before-to-write reuse window (residual micro-window
    // remains, fail closed): drift returns
    // VerifyUnknown without emitting any write command (fail closed).
    // Only a post-uninstall
    // re-check of NotInstalled counts as removed; still-present is
    // StillInstalled, an uncheckable channel or a drifted after-target is
    // VerifyUnknown (caller keeps the entry: fail closed). A failed uninstall
    // stays RemoveFailed by design (C2 scope: uninstall-side "Unknown
    // package" is not idempotent-success); refresh later syncs the display
    // from ground truth either way.
    suspend fun removeVerified(pkg: String): RemoveOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return RemoveOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val before = fetchVerifiedTarget()
        if (before == null) return RemoveOutcome.NoDual
        val dualId = before.userId
        if (profiles.none { it.userId == dualId }) return RemoveOutcome.NoDual
        val fresh = fetchVerifiedTarget()
        val preDrift = checkTargetDrift(before, fresh)
        if (preDrift != null) return RemoveOutcome.VerifyUnknown(dualId, preDrift)
        val clean = pkg.trim()
        val uninstallCmd = try {
            PmOutputParser.buildUninstall(clean, dualId)
        } catch (e: IllegalArgumentException) {
            return RemoveOutcome.InvalidPackage(clean)
        }
        // Write-side gateway defense (R5, same as the probe path): a throwing
        // gateway (spawn failure, SecurityException, binder death) maps to
        // ChannelError (fail closed), never a crash; CancellationException
        // is rethrown to stay coroutine-correct.
        val res = try {
            gateway.run(uninstallCmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RemoveOutcome.ChannelError("channel error: ${e.message ?: e.javaClass.simpleName}")
        }
        // Unknown-after-send (PR-32 P1-1 timeout, extended by P1-2
        // transport-unknown, extended by Phase A1 post-spawn text): same
        // contract as cloneVerified above -- an abandoned or lost reply
        // leaves exit/out/err untrusted either way, so NEVER map it to
        // RemoveFailed (that would claim "still installed" or "remove
        // failed" without knowledge). One bounded read-only verifyListProbe
        // instead (no write resend, after-pin first): NotInstalled ->
        // Success, clean present -> VerifyUnknown (reverse, write may still
        // land), Unknown -> VerifyUnknown (caller keeps the entry; the next
        // refresh converges).
        // Trigger (isUnknownAfterSend): res.timedOut OR res.transportUnknown
        // (post-dispatch reply loss incl. su wait/drain interrupt,
        // timedOut=false with the flag set) OR isServiceTimeoutText(res.err)
        // OR isPostSpawnTransportText(res.err) (see cloneVerified note --
        // same parcel flag-loss backstops for "timeout after ...",
        // "interrupted: ...", "service exec failed: ..."). Scope: only these
        // four signals take this path; pre-spawn spawn failures, pre-dispatch
        // refusals and explicit pm failures stay on the isSuccess judgement
        // below -> definite RemoveFailed.
        // After-pin (Phase E P1-1, same as cloneVerified): re-fetch and
        // require the before/after pin to hold before trusting a
        // same-direction probe as Success. Reverse observation (clean
        // present here) is VerifyUnknown, NOT StillInstalled: the probe ran
        // right after the timeout and the write may still land after it --
        // never claim "still installed" while the removal may be in flight.
        // The next refresh converges from ground truth.
        if (isUnknownAfterSend(res)) {
            val transportLost = res.transportUnknown
            val byText = !res.timedOut && !transportLost && isServiceTimeoutText(res.err)
            val postSpawnByText = !res.timedOut && !transportLost && isPostSpawnTransportText(res.err)
            val after = fetchVerifiedTarget()
            val drift = checkTargetDrift(before, after)
            if (drift != null) return RemoveOutcome.VerifyUnknown(dualId, drift)
            return when (val state = verifyListProbe(clean, dualId)) {
                is VerifyState.NotInstalled -> RemoveOutcome.Success(dualId)
                is VerifyState.Installed ->
                    RemoveOutcome.VerifyUnknown(
                        dualId,
                        writeTimeoutReverseDetail(observed = "present", intended = "remove", byText = byText, transportLost = transportLost, postSpawnByText = postSpawnByText, writeErr = res.err),
                    )
                is VerifyState.Unknown -> RemoveOutcome.VerifyUnknown(dualId, writeTimeoutDetail(state.detail, byText = byText, transportLost = transportLost, postSpawnByText = postSpawnByText, writeErr = res.err))
            }
        }
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
            return RemoveOutcome.RemoveFailed(res.err.ifEmpty { res.out.ifEmpty { "remove failed" } })
        }
        val after = fetchVerifiedTarget()
        val drift = checkTargetDrift(before, after)
        if (drift != null) return RemoveOutcome.VerifyUnknown(dualId, drift)
        return when (val state = verifyListProbe(clean, dualId)) {
            is VerifyState.NotInstalled -> RemoveOutcome.Success(dualId)
            is VerifyState.Installed -> RemoveOutcome.StillInstalled(dualId)
            is VerifyState.Unknown -> RemoveOutcome.VerifyUnknown(dualId, state.detail)
        }
    }

    // Phase D drift check for pinned flows: after must be non-null, name the
    // same id, and agree on serial/parentId on positive disagreement plus
    // serial known-to-unknown continuity. A known before.serial followed by
    // a missing after.serial drifts (identity-changed ... continuity lost,
    // fail closed). Either-side-missing is otherwise tolerated only for the
    // parent channel and for both-null serials (Phase B single-fetch
    // resolver tolerance, unchanged). Parent needs no known-to-unknown
    // rule: the resolver already requires both verbose channels to carry a
    // known, equal parentId == 0 before it Confirms, so any non-null after
    // target carries parentId non-null and == 0 by construction. Returns
    // the detail string (always containing "identity-changed") or null when
    // the pin holds.
    private fun checkTargetDrift(before: VerifiedTarget, after: VerifiedTarget?): String? {
        if (after == null) return "identity-changed: target vanished after write (before=${before.userId})"
        if (after.userId != before.userId) {
            return "identity-changed: id drifted before=${before.userId} after=${after.userId}"
        }
        continuityBreach(before, after)?.let { breach ->
            val detail = when {
                breach.startsWith("continuity lost") ->
                    "identity-changed: serial continuity lost before=${before.serial} after missing (reuse suspected)"
                breach.startsWith("serial changed") ->
                    "identity-changed: serial reuse suspected before=${before.serial} after=${after.serial}"
                else ->
                    "identity-changed: parent reuse suspected before=${before.parentId} after=${after.parentId}"
            }
            return detail
        }
        return null
    }

    /**
     * Pinned remove with an expected id (PR-4 Stage 1, issue #23): pin-style
     * execution for the two-phase confirm. The VM passes the request-time
     * dualId; this call snapshots, re-fetches the verified target, and
     * refuses (fail closed, no uninstall) when the verified target moved.
     *
     * PR-33 R2 (P1 serial binding): the VM additionally passes the
     * request-time serial ([expectedSerial], no default -- callers must pass
     * it). After the cancel check and before any uninstall, the serial-bound
     * target is re-fetched: a re-created profile (same id, fresh serial) or
     * an unavailable serial maps to [RemoveOutcome.SerialMismatch] with zero
     * uninstalls. A null [expectedSerial] means the caller cannot verify at
     * all and is refused directly as SerialMismatch(null, actual). An id
     * mismatch keeps the [RemoveOutcome.TargetMoved] mapping.
     */
    suspend fun removeVerifiedExpected(
        pkg: String,
        expectedDualId: Int,
        expectedSerial: Int?,
        isStillValid: () -> Boolean = { true },
    ): RemoveOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return RemoveOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val verified = fetchVerifiedDualId()
        if (verified < 0 || expectedDualId < 0) return RemoveOutcome.NoDual
        if (verified != expectedDualId) {
            return RemoveOutcome.TargetMoved(expectedDualId, verified)
        }
        if (profiles.none { it.userId == expectedDualId }) return RemoveOutcome.NoDual
        val clean = pkg.trim()
        val uninstallCmd = try {
            PmOutputParser.buildUninstall(clean, expectedDualId)
        } catch (e: IllegalArgumentException) {
            return RemoveOutcome.InvalidPackage(clean)
        }
        // 縮小 cancel 窗：uninstall 前再檢查一次 pending 是否仍有效，擋住
        // confirm 與 cancel 交錯的程式化路徑。仍非原子（檢查與執行之間仍有
        // 間隙），UI 層另用不可取消擋住使用者路徑（back/outside-tap）。
        if (!isStillValid()) return RemoveOutcome.Cancelled
        // P1 serial binding (PR-33 R2): re-fetch the serial-bound target
        // AFTER the cancel check, BEFORE any uninstall. A recreated profile
        // (same id+label, fresh serial) must never be uninstalled: any id
        // mismatch keeps TargetMoved, while a serial mismatch -- or no
        // confirmable serial at all (including a null expectedSerial, which
        // means the caller cannot verify) -- maps to SerialMismatch.
        // 零卸載 fail closed。
        val second = fetchDualTarget()
        if (second is DualTargetResult.Confirmed && second.target.userId != expectedDualId) {
            return RemoveOutcome.TargetMoved(expectedDualId, second.target.userId)
        }
        val actualSerial = (second as? DualTargetResult.Confirmed)?.target?.serial
        if (expectedSerial == null || actualSerial != expectedSerial) {
            return RemoveOutcome.SerialMismatch(expectedSerial, actualSerial)
        }
        // 第二道閘：縮小 serial-gate→uninstall 的 check-to-use 窗；仍非原子，UI 不可取消覆蓋使用者路徑。
        if (!isStillValid()) return RemoveOutcome.Cancelled
        val res = try {
            gateway.run(uninstallCmd)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return RemoveOutcome.VerifyUnknown(expectedDualId, e.message ?: e.javaClass.simpleName)
        }
        // Unknown-after-send (PR-32 P1-1 timeout, extended by P1-2
        // transport-unknown, extended by Phase A1 post-spawn text; same rule
        // as cloneVerified/removeVerified): an abandoned or lost write reply
        // proves nothing about the remote uninstall -- verify read-only
        // instead of claiming failure.
        // After-pin (Phase E P1-1): same synthetic-before pin as the normal
        // path below -- the serial may have changed while the client was
        // waiting, so re-fetch and refuse drift as VerifyUnknown before any
        // probe. Reverse observation (clean present) is VerifyUnknown, NOT
        // StillInstalled: the probe ran right after the timeout and the
        // uninstall may still land after it. No write resend on any arm.
        if (isUnknownAfterSend(res)) {
            val transportLost = res.transportUnknown
            val byText = !res.timedOut && !transportLost && isServiceTimeoutText(res.err)
            val postSpawnByText = !res.timedOut && !transportLost && isPostSpawnTransportText(res.err)
            val syntheticTimeoutBefore = VerifiedTarget(expectedDualId, expectedSerial, parentId = null)
            val timeoutAfter = fetchVerifiedTarget()
            val timeoutDrift = checkTargetDrift(syntheticTimeoutBefore, timeoutAfter)
            if (timeoutDrift != null) return RemoveOutcome.VerifyUnknown(expectedDualId, timeoutDrift)
            return when (val state = verifyListProbe(clean, expectedDualId)) {
                is VerifyState.NotInstalled -> RemoveOutcome.Success(expectedDualId)
                is VerifyState.Installed ->
                    RemoveOutcome.VerifyUnknown(
                        expectedDualId,
                        writeTimeoutReverseDetail(observed = "present", intended = "remove", byText = byText, transportLost = transportLost, postSpawnByText = postSpawnByText, writeErr = res.err),
                    )
                is VerifyState.Unknown ->
                    RemoveOutcome.VerifyUnknown(
                        expectedDualId,
                        writeTimeoutDetail(state.detail, byText = byText, transportLost = transportLost, postSpawnByText = postSpawnByText, writeErr = res.err),
                    )
            }
        }
        if (!PmOutputParser.isSuccess(res.out + "\n" + res.err, res.exit)) {
            return RemoveOutcome.RemoveFailed(res.err.ifEmpty { res.out.ifEmpty { "remove failed" } })
        }
        // Post-write after-pin (Phase F review MAJOR-2): the uninstall already
        // executed on the pinned id; re-fetch and require the identity pin to
        // still hold before the list re-check, so a profile recreated in the
        // write window is reported honestly as VerifyUnknown instead of a
        // clean Success. Synthetic before: removeVerifiedExpected carries only
        // (expectedDualId, expectedSerial), not a full resolve-time target, so
        // the before is reconstructed here with parentId=null; missing-tolerated
        // semantics (see checkTargetDrift) tolerates the absent parent while
        // still guarding id/serial reuse. Report-only: the write itself already
        // ran on the pre-write pinned target.
        val syntheticBefore = VerifiedTarget(expectedDualId, expectedSerial, parentId = null)
        val after = fetchVerifiedTarget()
        val drift = checkTargetDrift(syntheticBefore, after)
        if (drift != null) return RemoveOutcome.VerifyUnknown(expectedDualId, drift)
        return when (val state = verifyListProbe(clean, expectedDualId)) {
            is VerifyState.NotInstalled -> RemoveOutcome.Success(expectedDualId)
            is VerifyState.Installed -> RemoveOutcome.StillInstalled(expectedDualId)
            is VerifyState.Unknown -> RemoveOutcome.VerifyUnknown(expectedDualId, state.detail)
        }
    }

    // Runs the `pm list packages --user <dualId>` re-check for an
    // already-pinned id WITHOUT re-listing profiles: containment was
    // established by this call's own snapshot, so no second guess can drift
    // to another user. Invalid package tokens and builder refusals map to
    // Unknown (fail closed), never throw.
    //
    // Probe choice (third round, real-device proven): the re-check uses
    // `pm list packages --user <dualId>` (buildListPackages) plus exact
    // `pkg in parsePackages(out)` membership -- NOT `pm path --user N <pkg>`.
    // Right after `pm uninstall` succeeds, `pm path` can fail consecutively
    // with non-zero exit and empty out/err inside the teardown settling
    // window (three back-to-back probes all non-zero: no retry count saves
    // it), while `pm list packages` on the same window stays deterministic
    // ground truth (exit 0 with the package simply absent). Membership is an
    // exact set lookup so a superstring never mis-hits (com.example.app2
    // does not imply com.example.app).
    //
    // Trusted tri-state semantics (PR-2 stage 2, issue #22): the final probe
    // CmdResult is judged by parsePackagesTrusted. A whitelist-clean listing
    // decides presence by exact membership (Installed) or confirmed absence
    // (NotInstalled -- TrustedEmpty, or TrustedFull of other packages); an
    // untrusted capture is Unknown (detail kept: exit code, truncation, the
    // error-semantic line, or the unexpected-format/unexplained-stderr
    // line). In particular an exit-0 "Unknown package" /
    // SecurityException / Failure text is Unknown, NOT confirmed-absent: only
    // a format-clean listing is absence evidence. Install/uninstall-side
    // channels keep isSuccess; only this list probe and the refresh listing
    // use the trusted rule.
    //
    // Retry (PR-3 stage 1, issue #24 -- unfreezes the PR-2 frozen policy):
    // a non-zero list probe is retried at most twice with a
    // VERIFY_RETRY_DELAY_MS settle wait before the trusted parse, covering
    // transient binder/timeout blips. The wait runs through delayFn
    // (cancellable suspend slot, default delay(); PR-32 P2-1): a Job cancel
    // during the wait propagates as CancellationException instead of
    // sleeping through it. Callers keep repository calls off Main
    // (DualViewModel wraps every repo call in Dispatchers.IO), which also
    // covers a custom blocking delayFn.
    private suspend fun verifyOnProfiles(pkg: String, dualId: Int, profiles: List<DualProfile>, expected: VerifiedTarget? = null): VerifyState {
        if (dualId <= 0 || profiles.none { it.userId == dualId }) {
            return VerifyState.Unknown("refused: unknown dual profile id ($dualId)")
        }
        // Verified-dual gate (stage 2): existence alone never suffices. The id
        // must equal the currently Confirmed cross-source target; a present
        // but non-Dual id (Work/Secondary/Secure Folder, renamed DUAL-label,
        // type conflict, property mismatch) maps to Unknown (fail closed),
        // never to a probe of the wrong user.
        // Phase D ID-reuse protection: when `expected` is non-null, a fresh
        // target naming the same id must still agree on serial/parentId
        // (both-present-and-unequal -> Unknown reuse refusal; a known
        // expected.serial followed by a missing fresh.serial -> Unknown
        // continuity-lost refusal, no probe). expected==null or
        // expected.serial==null stays tolerated (Phase B single-fetch
        // resolver tolerance). Parent needs no known-to-unknown rule (see
        // checkExplicitDualId: a Confirmed fresh target always carries
        // parentId == 0).
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) {
            return VerifyState.Unknown(
                "refused: id $dualId is not the verified dual target" +
                    " (verified=${fresh?.userId?.toString() ?: "none"})",
            )
        }
        if (expected != null) {
            continuityBreach(expected, fresh)?.let { breach ->
                return VerifyState.Unknown(
                    "refused: id $dualId identity reuse suspected" +
                        " ($breach)",
                )
            }
        }
        return verifyListProbe(pkg, dualId)
    }

    // List-only re-check for an already-verified id (pinned flows): runs the
    // `pm list packages --user <dualId>` probe WITHOUT re-resolving identity.
    // The write is already pinned to the same id (before/fresh/after pin);
    // the probe itself performs no resolve, so a residual after-to-probe
    // window remains. It is read-only and fail-closed in the misread
    // direction: a drifted target can only misreport presence/absence, never
    // redirect a write to another user.
    // All tri-state/trusted-parse/retry semantics are identical to the explicit
    // path below (PR-2 owns that contract; #21 only changes WHO may be
    // probed, never HOW the probe is judged).
    private suspend fun verifyListProbe(pkg: String, dualId: Int): VerifyState {
        val clean = pkg.trim()
        try {
            ShellEscaper.escapePackage(clean)
        } catch (e: IllegalArgumentException) {
            return VerifyState.Unknown(e.message ?: "invalid package")
        }
        val cmd = try {
            PmOutputParser.buildListPackages(dualId)
        } catch (e: IllegalArgumentException) {
            // Unreachable in practice: dualId was already verified/pinned, so
            // only an invalid userId could throw here (never a package token).
            return VerifyState.Unknown(e.message ?: "invalid userId")
        }
        val res = try {
            var r = gateway.run(cmd)
            var attempts = 1
            while (r.exit != 0 && attempts < VERIFY_MAX_ATTEMPTS) {
                delayFn(VERIFY_RETRY_DELAY_MS)
                r = gateway.run(cmd)
                attempts++
            }
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            // Kept (PR-32 P2-1 decision: retain): the default delay() throws
            // CancellationException (rethrown above), so this arm can only
            // fire from a custom blocking delayFn (e.g. Thread.sleep under
            // thread interrupt). Restore the flag, then fail closed as a
            // channel error. Ordered before Exception (InterruptedException
            // is an Exception subclass; Exception last keeps it reachable).
            Thread.currentThread().interrupt()
            return VerifyState.Unknown("channel error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            return VerifyState.Unknown("channel error: ${e.message ?: e.javaClass.simpleName}")
        }
        // Trusted tri-state (PR-2 stage 2, issue #22): the final probe result
        // is judged by parsePackagesTrusted, not by exit code alone. A clean
        // full listing (buildListPackages has no filter parameter) that lacks
        // the package -- TrustedEmpty, or TrustedFull of other packages --
        // is confirmed absent (NotInstalled); only such a whitelist-clean
        // listing may use this mapping. An untrusted capture (truncated,
        // non-zero after the retries above, error-semantic text such as
        // "Unknown package"/SecurityException/Failure with exit 0, or any
        // unexpected-format stdout line / unexplained stderr residue per the
        // structural whitelist) is Unknown. Membership stays an exact set
        // lookup (superstrings never hit). Retry/sleep policy is PR-3 owned
        // (delayFn virtual wait, at most 2 retries; same policy as the
        // verifyOnProfiles note above).
        return PmOutputParser.parsePackagesTrusted(res).toVerify(clean)
    }

    // Runs `pm list packages --user <dualId>` for an already-pinned snapshot.
    // Guard-refused ids and untrusted listings yield null (unknown), never throw.
    // Stage 2 adds the verified-dual gate: existence alone never suffices;
    // the trusted parse (#22) is owned by listProbe.
    // Phase D ID-reuse protection: same serial/parent rule as
    // verifyOnProfiles above (both-present-and-unequal -> null; known
    // expected.serial followed by missing fresh.serial -> null continuity
    // loss, no probe). expected==null or expected.serial==null stays
    // tolerated. Parent needs no known-to-unknown rule (see
    // checkExplicitDualId).
    private suspend fun listOnProfiles(dualId: Int, profiles: List<DualProfile>, expected: VerifiedTarget? = null): List<String>? {
        if (dualId <= 0 || profiles.none { it.userId == dualId }) return null
        val fresh = fetchVerifiedTarget()
        if (fresh == null || fresh.userId != dualId) return null
        if (expected != null && continuityBreach(expected, fresh) != null) return null
        return listProbe(dualId)
    }

    // List-only probe for an already-verified id (auto listing path): no
    // identity re-resolution. Trusted tri-state (PR-2 stage 2, issue #22):
    // TrustedFull yields its packages, TrustedEmpty yields confirmed-empty
    // (emptyList, authoritative), and Unknown (truncated / non-zero /
    // error-semantic / unexpected-format stdout / unexplained stderr)
    // yields null -- never an authoritative empty list, so refresh keeps
    // the previous `cloned` instead of wiping it.
    // No retry here by design (frozen, PR-3 owns any change -- same policy
    // as verifyOnProfiles above): refresh maps null to "keep old value", so
    // a transient blip costs one stale frame, not data loss.
    // Gateway defense (PR-2 Phase 3, R5): a throwing gateway maps to null
    // (unknown, fail closed), never a crash; CancellationException is
    // rethrown to stay coroutine-correct.
    private suspend fun listProbe(dualId: Int): List<String>? {
        val cmd = try {
            PmOutputParser.buildListPackages(dualId)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val res = try {
            gateway.run(cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        }
        return PmOutputParser.parsePackagesTrusted(res).toPackageList()
    }

    // Shared TrustedList mappings (PR-2 Phase 2 dedup): single place owning
    // HOW a trusted parse becomes a probe result (#21 only changes WHO may be
    // probed). Pure, no I/O, no retry/sleep policy.
    // Trusted -> VerifyState: clean listing decides by exact membership
    // (Installed) or confirmed absence (NotInstalled); untrusted is Unknown.
    private fun TrustedList.toVerify(clean: String): VerifyState = when (this) {
        is TrustedList.TrustedFull ->
            if (clean in packages) VerifyState.Installed else VerifyState.NotInstalled
        is TrustedList.TrustedEmpty -> VerifyState.NotInstalled
        is TrustedList.Unknown -> VerifyState.Unknown(detail)
    }

    // Trusted -> authoritative list: clean listing yields its packages
    // (possibly empty); untrusted yields null (unknown, never empty).
    private fun TrustedList.toPackageList(): List<String>? = when (this) {
        is TrustedList.TrustedFull -> packages
        is TrustedList.TrustedEmpty -> emptyList()
        is TrustedList.Unknown -> null
    }

    // Single-target refresh (Phase D P2-D, issue #21): exactly one
    // profilesSnapshot() (1 cmd) then one fetchVerifiedTarget() (3 cmds:
    // verbose + dumpsys + property) then one listProbe(target.userId)
    // (1 cmd, no re-resolution) = 5 commands total (was 9 via the old
    // resolve-then-reverify listing path). Single target throughout, so
    // resolve-to-list drift is inexpressible in this API: the listed id is
    // always the resolved id. ChannelError early-returns; a null target or
    // a target absent from the snapshot maps to NoDual(profiles) (profiles
    // still carried for display); otherwise GroundTruth with cloned null
    // when the list channel fails.
    suspend fun refreshVerified(): RefreshOutcome {
        val snap = profilesSnapshot()
        if (snap is ProfilesSnapshot.ChannelError) return RefreshOutcome.ChannelError(snap.detail)
        val profiles = (snap as ProfilesSnapshot.Loaded).profiles
        val target = fetchVerifiedTarget() ?: return RefreshOutcome.NoDual(profiles)
        if (profiles.none { it.userId == target.userId }) return RefreshOutcome.NoDual(profiles)
        val cloned = listProbe(target.userId)
        return RefreshOutcome.GroundTruth(profiles, target, cloned)
    }

    // Unknown-after-send discriminator (PR-32 P1-1 timeout, extended by P1-2
    // transport-unknown, extended by Phase A1 post-spawn transport text):
    // a write reply is untrusted -- and must reconcile read-only instead of
    // claiming definite failure -- when the client abandoned the wait
    // (timedOut), the post-dispatch reply was lost (transportUnknown), the
    // frozen-wire service-side timeout text is recognized
    // (isServiceTimeoutText backstop), or the frozen-wire post-spawn
    // transport text is recognized (isPostSpawnTransportText backstop:
    // "interrupted: ..." from a su/service wait/drain interrupt after spawn,
    // "service exec failed: ..." from the GalaxyExecService catch after
    // spawn). Pre-spawn definite signals -- "spawn failed: ..." (exec threw,
    // process never started), pre-dispatch refusals (no binder, not
    // authorized, bind failure), and explicit pm failures (Failure[...]) --
    // carry none of these and stay on the isSuccess judgement -> definite
    // failure.
    private fun isUnknownAfterSend(res: CmdResult): Boolean =
        res.timedOut || res.transportUnknown || isServiceTimeoutText(res.err) || isPostSpawnTransportText(res.err)

    // Detail prefix for the unknown-after-send path (PR-32 P1-1 timeout,
    // extended by P1-2 transport-unknown, extended by Phase A1 post-spawn
    // text): the write reply is untrusted, so the VerifyUnknown detail names
    // that first and keeps the read-only probe detail after it. byText=true
    // marks the wire-frozen timeout backstop: the flag was lost on the parcel
    // and the timeout was recognized by anchored producer-prefix text
    // instead. transportLost=true marks the P1-2 post-dispatch reply loss
    // (transact threw / binder died mid-call / reply decode failed):
    // timedOut=false with transportUnknown=true. postSpawnByText=true marks
    // the Phase A1 wire-frozen post-spawn backstop ("interrupted: ..." /
    // "service exec failed: ..."): the flag was lost on the parcel and the
    // post-spawn transport failure was recognized by anchored text instead;
    // writeErr carries the original err snippet (bounded take(200)) so the
    // detail names the concrete signal. The ViewModel already renders
    // VerifyUnknown as "result unknown" (MsgKind.CHANNEL) and keeps the UI
    // list for the next refresh -- no VM/MainActivity change needed.
    private fun writeTimeoutDetail(probeDetail: String, byText: Boolean = false, transportLost: Boolean = false, postSpawnByText: Boolean = false, writeErr: String = ""): String {
        val head =
            if (transportLost) "write reply lost (transport unknown, may have executed)"
            else if (postSpawnByText) "write reply unknown post-spawn (may have executed; recognized by text; wire layout frozen; write err: ${writeErr.take(200)})"
            else if (byText) "write reply timed out (service-side timeout recognized by text; wire layout frozen)"
            else "write reply timed out"
        return if (probeDetail.isEmpty()) head else "$head; $probeDetail"
    }

    // Reverse-observation detail for the unknown-after-send path (Phase E
    // P1-1, extended by P1-2 transport-unknown, extended by Phase A1
    // post-spawn text): the write reply is untrusted, so an immediate
    // post-send probe showing the opposite of the intended write (absent
    // right after a clone, present right after a remove) proves nothing --
    // the remote write may still land after the probe completed. Treated as
    // unknown (never a terminal failure/success); the next refresh converges
    // from ground truth. Flag/text marking is the same tri-state-plus-text
    // rule as writeTimeoutDetail above.
    private fun writeTimeoutReverseDetail(observed: String, intended: String, byText: Boolean = false, transportLost: Boolean = false, postSpawnByText: Boolean = false, writeErr: String = ""): String {
        val head =
            if (transportLost) "write reply lost (transport unknown, may have executed)"
            else if (postSpawnByText) "write reply unknown post-spawn (may have executed; recognized by text; wire layout frozen; write err: ${writeErr.take(200)})"
            else if (byText) "write reply timed out (service-side timeout recognized by text; wire layout frozen)"
            else "write reply timed out"
        return "$head; immediate post-timeout probe shows $observed opposite the intended $intended -- " +
            "treated as unknown (write may still land), awaiting refresh convergence"
    }
}
