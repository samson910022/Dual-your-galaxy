// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// DEVICE-UNVERIFIED：無裝置，未經實機驗證。
// Phase F4a (issue #6): SHELL execution channel over the Shizuku UserService API.
//
// API choice (evidence):
// - Shizuku 13.1.5 (this repo's pinned dev.rikka.shizuku:api, see
//   gradle/libs.versions.toml) REMOVED Shizuku#newProcess entirely: javap over
//   api-13.1.5.aar shows no newProcess method on rikka.shizuku.Shizuku, only
//   bindUserService / peekUserService / unbindUserService plus the
//   ShizukuRemoteProcess Parcelable carrier type.
// - The official guide (RikkaApps/Shizuku-API README, changelog 13.1.1)
//   states: "Prepare to remove Shizuku#newProcess, developers should have to
//   use UserService instead ... UserService can replace newProcess in all
//   cases." Deprecated/removed APIs are therefore NOT used here on purpose.
// - Pattern follows the official demo UserService
//   (demo/.../rikka/shizuku/demo/service/UserService.java): a service class
//   implementing IBinder (via Binder), default constructor plus the v13
//   Context constructor, and the reserved destroy transaction that calls
//   System.exit(0). The default ctor is a fail-closed sink (-1); pre-v13
//   servers are rejected pre-bind (Phase C3 v13 gate), not a backward-compat
//   path.
//
// Command path: DualRepository builds the pm string with core builders
// (PmOutputParser + ShellEscaper, C1-C3); the gateway ships the string
// verbatim to the privileged service, which runs `sh -c <cmd>` under the
// shell/root uid and returns exit/stdout/stderr. No hand-spliced pm syntax
// anywhere in this file.
package com.galaxydual.plus

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.Privilege
import com.galaxydual.core.TRUNCATION_MARKER
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

// Wire protocol between ShizukuCmdGateway (client) and GalaxyExecService.
// TRANSACTION_EXEC is a custom code on our own Binder subclass; it must never
// collide with the framework-reserved UserService destroy codes (16777114 in
// aidl spelling, 16777115 on the wire) -- enforced by unit test.
// Reply layout (FROZEN: int exit, String stdout, String stderr -- three fields
// only, no independent completeness bit; adding one is a wire break, so the
// truncated flag travels as marker text inside the streams).
// The client re-derives truncated from the core TRUNCATION_MARKER via
// decodeExecReply() below. Marker semantics v2 + v3 + v4 (SERVICE_VERSION = 4): the
// service appends the marker whenever the capture is truncated (cap, read
// error, or join expiry -- see capped() / buildResult() in SuCmdGateway.kt),
// so every truncated=true result carries detectable text (review Phase B,
// issue #22 P2-#3). v1 = cap-only marker (old semantics): an old service on
// a read-error / join-expiry path returned exit=0 partial package lines (or
// clean empty) with NO marker, which a new client decodes as truncated=false.
// v2 = whenever-truncated marker with ungated EXEC (prior contract, kept
// accurate here -- marker behavior unchanged in v3/v4). v3 = v2 marker plus
// owner-uid EXEC gate (below); v3 invalidates ungated v2 resident services.
// v4 = v3 marker+gate with appId normalization (auth code changed; C2):
// the gate now strips the user offset before the AID_APP_START comparison,
// so privileged appIds (0/1000/2000) stay denied regardless of user; v4
// invalidates v3 resident services (full-UID >=10000 gate).
// Upgrade gap closed at the deployment layer: UserServiceArgs
// .version(SERVICE_VERSION) forces Shizuku/Sui to rebuild the resident
// UserService on upgrade (official UserServiceArgs.version(int) contract:
// use a different version code whenever the service code is updated; daemon
// defaults to true), so the next bind with the new args rebuilds the
// resident UserService instead of reusing the old one. The
// bind tag is per-UID derived ("GalaxyDualExec-v1-u<callerUid>", see
// galaxyExecServiceTag) so each user instance gets its own service record;
// the legacy fixed tag "GalaxyDualExec-v1" no longer binds and its record
// orphans until Shizuku restart (daemon restart reaps). SERVICE_VERSION is
// 4 (C2 bumped it for the appId change); the version bump remains the
// invalidation signal.
// Device upgrade verification (S24 real-device item, NOT JVM-testable: the
// android.os.Parcel / Binder round-trip is a framework stub on the JVM):
//   1. install the old APK and start the resident UserService (v1);
//   2. upgrade the APK WITHOUT restarting Shizuku;
//   3. confirm the bound service runs the new capture/marker contract (an
//      exit=0 partial read-error / join-expired reply arrives WITH marker and
//      parses as Unknown on the new client);
//   4. (v2 -> v3) confirm owner app uid EXEC succeeds and a foreign uid is
//      denied (DENIED_FOREIGN_CALLER) without command execution.
//   5. (v3 -> v4) confirm secondary-user privileged uids are denied
//      (e.g. user 10 shell 1002000) and a secondary-user app uid succeeds
//      (e.g. user 10 app 1010234).
// Caller-identity gate (P0 security): the service runs under the shell/root
// uid, so it must never proxy an arbitrary foreign app's `sh -c` command.
// Shizuku per-app bind isolation is DEVICE-UNVERIFIED (never confirmed on a
// device), so fail closed: only the owner app uid (captured from the Context
// constructor, NOT the service process uid) may EXEC. Enforced in
// GalaxyExecService.onTransact via isAllowedCaller() below (pure,
// JVM-tested); foreign callers get the frozen denied reply and their command
// is never read nor executed. DESTROY is intentionally left ungated (see
// onTransact DESTROY): actual sender uid is DEVICE-UNVERIFIED, gating risks
// orphaned resident services defeating SERVICE_VERSION, and foreign DESTROY
// is availability-only DoS (Shizuku rebinds, no command execution). EXEC
// remains the P0-gated vector.
object ShizukuExecProtocol {
    const val TRANSACTION_EXEC = 1
    const val TRANSACTION_DESTROY = 16777115
    const val BIND_TIMEOUT_MS = 10_000L
    const val EXEC_TIMEOUT_MS = 60_000L
    // Minimum Shizuku server API supplying the Context constructor for
    // UserService (Changelog 13.0.0). Hardcoded requirement: the gateway
    // rejects older servers pre-bind via Shizuku.getVersion() (never
    // getLatestServiceVersion); below v13 the default ctor is a
    // fail-closed sink (expectedAppUid = -1, EXEC always denied).
    const val MIN_SHIZUKU_VERSION = 13
    // Wire-frozen denied reply for foreign callers (exit=1, out="", err=this).
    // The onTransact auth gate writes it without reading/executing the cmd.
    const val DENIED_FOREIGN_CALLER = "denied: foreign caller"
    // UserService implementation version. v1 = cap-only marker (old service
    // semantics); v2 = whenever-truncated marker (prior capture contract,
    // EXEC ungated); v3 = v2 marker plus owner-uid EXEC gate; v4 = v3
    // marker+gate with appId normalization (current contract). Bump whenever
    // the service-side capture/encode/auth code changes: Shizuku requires a
    // different version code on service-code updates so the daemon (default
    // true) rebuilds the UserService instead of reusing the resident old
    // one -- v4 therefore invalidates v3 resident services (full-UID gate),
    // as v3 invalidated ungated v2 resident services. The bind tag is
    // per-UID derived ("GalaxyDualExec-v1-u<callerUid>", see
    // galaxyExecServiceTag); the legacy fixed tag "GalaxyDualExec-v1" is
    // retained only as LEGACY_GALAXY_EXEC_TAG for cleanup/documentation and
    // is NOT bound. Its old service record orphans until Shizuku restart
    // (daemon restart reaps). SERVICE_VERSION is 4 (C2 bumped it for the
    // appId change; auth code changed so the daemon must rebuild).
    const val SERVICE_VERSION = 4
}

// UID decomposition consts (Phase C2). Literals, not android.os imports,
// for JVM purity: android.os.UserHandle.PER_USER_RANGE is 100000 and
// android.os.Process.FIRST_APPLICATION_UID (AID_APP_START) is 10000.
// Full uid = userId * UID_PER_USER_RANGE + appId.
internal const val UID_PER_USER_RANGE = 100_000
internal const val AID_APP_START = 10_000

// Strips the user offset: appId = uid % UID_PER_USER_RANGE.
// Kotlin `%` remainder sign follows the dividend, never floorMod: a
// negative uid yields a negative or zero remainder, which fails the
// >= AID_APP_START check below and stays denied (fail closed).
internal fun appIdOf(uid: Int): Int = uid % UID_PER_USER_RANGE

// True iff uid is a normal application uid: non-negative and its appId
// (full uid without the user offset) is >= AID_APP_START. Privileged appIds
// 0 (root), 1000 (system), 2000 (shell) are always < 10000 regardless of
// user, so secondary-user privileged uids (e.g. user 10 shell 1002000)
// are denied, while secondary-user app uids (e.g. 1010234) succeed.
internal fun isApplicationUid(uid: Int): Boolean = uid >= 0 && appIdOf(uid) >= AID_APP_START

// Fail-closed expected-uid resolution: keeps the full uid only when it is
// an application uid, else -1 (never matches, gate fails closed).
internal fun resolveExpectedAppUid(resolved: Int): Int = if (isApplicationUid(resolved)) resolved else -1

/**
 * Owner-uid caller gate for [GalaxyExecService].
 *
 * [expectedAppUid] is the owner app uid (validated from
 * `BuildConfig.APPLICATION_ID` via `packageManager.getPackageUid(...)` /
 * `Context.applicationInfo.uid` in the service's Context constructor), NOT the
 * service process uid: the service runs as shell (2000) / root (0) and those
 * uids are shared across processes, so `==` against `Process.myUid()` is NOT
 * authentication (it self-DoSes the legit app caller while letting any
 * shell-peer through). Root (0), system (1000), and shell (2000) are shared
 * privileged/system uids and are never legitimate expected owner uids, so only
 * an application uid (non-negative full uid whose appId >= 10000,
 * AID_APP_START) can ever match. A negative expected uid (daemon
 * default-constructor path, resolution failure, or a privileged/system
 * resolution) never matches and fails closed. Equality stays full-UID
 * (never appId equality): a same-appId uid from a different user (e.g.
 * owner 10234 vs user 10 1010234) never matches; only the exact full uid
 * plus the appId gate passes.
 * Shizuku per-app bind isolation is DEVICE-UNVERIFIED (never confirmed on a
 * device), so fail closed to the owner app uid instead of trusting the bind
 * layer.
 *
 * Do NOT add android:sharedUserId — uid-equality gate would admit sibling
 * packages; redesign gate first.
 *
 * Pure JVM-testable seam: [callingUid] is `Binder.getCallingUid()`; only
 * this comparison is unit-tested -- Binder/Parcel round-trips stay
 * device-only (android.jar stubs on the JVM).
 */
internal fun isAllowedCaller(callingUid: Int, expectedAppUid: Int): Boolean =
    callingUid == expectedAppUid && isApplicationUid(expectedAppUid)

// Pure Shizuku server version gate (Phase C3, P2 pre-v13 gate): only v13+
// supplies the Context constructor, so older servers are rejected pre-bind.
// Adjacent to isAllowedCaller for JVM coverage symmetry. -1/0/old all fail.
internal fun isSupportedShizukuVersion(version: Int): Boolean = version >= ShizukuExecProtocol.MIN_SHIZUKU_VERSION

// Legacy fixed bind tag (pre-Phase C1). Retained for cleanup/documentation
// only; it is NOT bound — galaxyExecServiceTag() below derives a per-UID tag
// for every bind. Its resident service record orphans until Shizuku restart
// (daemon restart reaps).
const val LEGACY_GALAXY_EXEC_TAG = "GalaxyDualExec-v1"

// Prefix for the per-UID derived bind tag (see galaxyExecServiceTag).
const val GALAXY_EXEC_TAG_PREFIX = "GalaxyDualExec-v1-u"

// Phase C1 (P1 multi-user tag): pure per-caller bind tag. Embeds the FULL
// caller uid (userId*100000 + appId, e.g. 1010234 for user 10), NOT just the
// appId, so the owner (10134) and secondary users (1010234, 9510234) get
// distinct tags and distinct Shizuku service records. Stable for a given uid
// across binds/restarts of the app process.
internal fun galaxyExecServiceTag(callerUid: Int): String = GALAXY_EXEC_TAG_PREFIX + callerUid

// Pure error mapping for every Shizuku-side failure. Tri-state contract
// (PR-32 P1-1 timeout, extended by P1-2 transport-unknown): the repository
// judges a write reply by three mutually exclusive knowledge states, and
// only the error factories below (plus decodeExecReply) produce them.
//   1. Explicit pm failure -- the service ran pm and answered: exit/out/err
//      come from decodeExecReply() and DualRepository judges them with
//      isSuccess (Failure[...] -> InstallFailed/RemoveFailed). Neither flag.
//   2. Definitely not sent (pre-dispatch) -- the command never reached the
//      service: noBinder / notAuthorized / bindFailed (all thrown before
//      transactExec's binder.transact), binderDead() on the pre-transact
//      isBinderAlive check, remoteFailed() on the pre-bind permission
//      check, and unsupportedVersion() as pre-dispatch refusal (v13 gate).
//      timedOut=false, transportUnknown=false -> the repository
//      claims definite failure (InstallFailed/RemoveFailed).
//   3. Possibly sent but reply lost (post-dispatch) -- transact threw
//      (RemoteException/binder death), the binder died mid-call, or the
//      reply decode failed: transportUnknown(), timedOut=false but
//      transportUnknown=true -> the repository reconciles read-only
//      (after-pin + single verifyListProbe, reverse -> Unknown, never an
//      auto resend), same rule as the timedOut() client-wait expiry.
// Only timedOut() sets timedOut; only transportUnknown() sets
// transportUnknown. All cases are exit != 0 with an explicit message; pure
// Kotlin so GatewayAssemblyTest covers it on the JVM.
// No truncated flag needed here: every case is exit != 0, which the trusted
// parser already maps to Unknown without consulting the flag.
object ShizukuExecErrors {
    fun noBinder(): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: binder not connected (install/start Shizuku first)")

    fun binderDead(): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: binder died mid-call")

    fun notAuthorized(): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: not authorized (grant access in Shizuku manager)")

    fun bindFailed(detail: String): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: bind failed: $detail")

    fun remoteFailed(detail: String): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: remote exec failed: $detail")

    // Pre-dispatch refusal (Phase C3 pre-v13 gate): the Shizuku server API
    // is below v13, so no Context constructor exists; definite failure.
    // Must not start with any backstop prefix ("service exec failed: ",
    // "interrupted: ", "timeout after ", "shizuku: exec timed out after ",
    // "may have executed") so the repository never treats it as possibly sent.
    fun unsupportedVersion(version: Int): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: unsupported Shizuku version $version (requires v13+; update Shizuku to v13 or newer)")

    // Post-dispatch reply loss (PR-32 P1-2): the write was (or may have been)
    // dispatched -- transact threw, the binder died mid-call, or the reply
    // decode failed -- but no reply arrived. timedOut=false (the client wait
    // did NOT expire; this is a transport loss, not a deadline) with
    // transportUnknown=true so the repository reconciles read-only instead
    // of claiming definite failure. Pre-dispatch refusals (noBinder /
    // notAuthorized / bindFailed / pre-transact binderDead / pre-bind
    // remoteFailed) must NOT use this: the command never reached the
    // service, so they stay definite failures.
    fun transportUnknown(detail: String): CmdResult =
        CmdResult(
            exit = 1,
            out = "",
            err = "shizuku: transport unknown (reply lost, may have executed): $detail",
            transportUnknown = true,
        )

    fun timedOut(): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after ${ShizukuExecProtocol.EXEC_TIMEOUT_MS}ms", timedOut = true)
}

// Pure wire mapping for the exec reply (JVM-testable): the Binder Parcel
// itself (android.os.Parcel) is a framework stub on the JVM and is NOT
// covered by unit tests -- only this mapping is (GatewayAssemblyTest
// round-trips encode->decode for cap, join-expiry, clean-empty, and
// non-zero-exit). The service encodes via encodeExecReply() before writing
// the Parcel; the client decodes via decodeExecReply() after reading it, so
// a truncated flag always survives as marker text in one of the streams.
// Internal so JVM unit tests cover it without a device.
internal fun encodeExecReply(res: CmdResult): Triple<Int, String, String> =
    Triple(res.exit, res.out, res.err)

internal fun decodeExecReply(exit: Int, out: String, err: String): CmdResult =
    CmdResult(
        exit = exit,
        out = out,
        err = err,
        truncated = out.contains(TRUNCATION_MARKER) || err.contains(TRUNCATION_MARKER),
    )

// Frozen denied-reply writer for the onTransact auth gate: exit=1, out="",
// err=DENIED_FOREIGN_CALLER. The uid is never echoed onto the wire (deny
// paths log it locally in DEBUG builds only). Delegates to
// encodeExecReply() so the frozen (int, String, String) layout stays
// single-sourced with the service reply path.
private fun writeDenied(reply: Parcel?) {
    val (exit, out, err) = encodeExecReply(
        CmdResult(1, "", ShizukuExecProtocol.DENIED_FOREIGN_CALLER),
    )
    reply?.writeInt(exit)
    reply?.writeString(out)
    reply?.writeString(err)
}

// Pure post-dispatch error mapping (E2 review P1-2 follow-up): transactExec
// needs a real IBinder (synchronous binder.transact on a Parcel pair), so its
// catch chain is not JVM-testable -- only this mapping is (GatewayAssemblyTest
// pins it for Cancellation / Interrupted / generic). Extracted 1:1 from
// transactExec's catch chain; current behavior preserved, no wire / timeout /
// retry change:
//   - CancellationException is NOT mapped: it is rethrown to stay
//     coroutine-correct (the selectChannel latest-wins handover depends on
//     it). This function rethrows it as well so a direct caller cannot
//     accidentally swallow cancellation as transportUnknown.
//   - InterruptedException (the block's own interrupt, unwrapped from the
//     future by awaitBounded) maps to transportUnknown(): post-dispatch by
//     construction -- the service may already have executed pm while the
//     client holds no reply, so the repository reconciles read-only instead
//     of claiming definite failure. timedOut stays false (a transport loss,
//     not a client-wait expiry).
//   - Every other Exception (transact RemoteException -- android.os.
//     RemoteException is an android.jar stub that throws "Stub!" when
//     instantiated on the JVM, so it cannot be constructed in a unit test;
//     it shares transactExec's generic catch (e: Exception) branch, pinned
//     here by the RuntimeException/IOException tests -- binder death
//     mid-call, reply readInt/readString decode failure) maps to
//     transportUnknown() for the same post-dispatch reason.
//     NOTE (generic-branch audit): the pre-extraction code already mapped the
//     generic branch to transportUnknown(), NOT to remoteFailed() -- no
//     definite-failure misclassification existed, so no behavior fix was
//     needed; the extraction preserves transportUnknown() as-is.
// Pure: no binder touch, no Thread.interrupted() consume, no dropBinding --
// those side effects stay in transactExec's catch blocks (the interrupt-flag
// consume in particular must run on the catching thread, not inside a pure
// mapper that unit tests call on arbitrary threads).
internal fun mapPostDispatchError(e: Exception): CmdResult {
    if (e is CancellationException) throw e
    if (e is InterruptedException) return ShizukuExecErrors.transportUnknown(e.message ?: e.javaClass.simpleName)
    return ShizukuExecErrors.transportUnknown(e.message ?: e.javaClass.simpleName)
}

// Privileged execution service, instantiated by the Shizuku server process
// (shell/root uid), NOT by this app's process. Must stay a public top-level
// class with both constructors: Shizuku v13+ tries the Context constructor
// first via reflection; pre-v13 servers are rejected pre-bind by the gateway
// (Phase C3 v13 gate), so the default ctor is a fail-closed sink (-1), never
// a backward-compat path. The Context is the
// package-scoped user context; Context used only to capture owner uid (no
// receivers/resolvers retained; not stored beyond construction).
// NOTE: minification is off for all build types (see app/build.gradle.kts),
// so no -keep rule is needed today. If minify is ever enabled, add:
//   -keep class com.galaxydual.plus.GalaxyExecService { <init>(...); }
// DEVICE-UNVERIFIED: bind/exec round-trip never ran on a device.
class GalaxyExecService : Binder {
    // Owner app uid captured from the Context constructor (the package-scoped
    // user context, validated against BuildConfig.APPLICATION_ID). The default
    // constructor is a fail-closed sink (no Context, stays -1, EXEC always
    // denied); pre-v13 servers never reach here (gateway rejects pre-bind).
    private val expectedAppUid: Int

    constructor() : super() {
        expectedAppUid = -1
    }

    constructor(context: Context) : super() {
        // Fail closed on a foreign context: do not trust a context whose
        // package is not this app's APPLICATION_ID. Primary resolution is the
        // current-user package uid; applicationInfo.uid is fallback only
        // after the package match. NOTE: getPackageUidAsUser would be
        // user-correct but is API 34+ (unavailable on minSdk 29), so
        // getPackageUid(..., 0) is kept despite deprecation / multi-user skew:
        // the user offset is in the same full-uid domain as
        // Binder.getCallingUid() (both carry userId*100000 + appId), so the
        // equality gate stays consistent. Resolved values whose appId
        // < 10000 (AID_APP_START) are privileged/shared uids (root 0,
        // system 1000, shell 2000, regardless of user offset) and are
        // never legitimate owners -> -1 via resolveExpectedAppUid().
        expectedAppUid = try {
            if (context.packageName != BuildConfig.APPLICATION_ID) {
                -1
            } else {
                val resolved = try {
                    context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
                } catch (_: Exception) {
                    try {
                        context.applicationInfo.uid
                    } catch (_: Exception) {
                        -1
                    }
                }
                resolveExpectedAppUid(resolved)
            }
        } catch (_: Exception) {
            -1
        }
        if (expectedAppUid == -1 && BuildConfig.DEBUG) {
            Log.w("GalaxyExec", "expectedAppUid=-1 context package=${context.packageName}")
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            ShizukuExecProtocol.TRANSACTION_DESTROY -> {
                // DESTROY is sent by Shizuku server on teardown/version-invalidation;
                // actual sender uid is DEVICE-UNVERIFIED (could be system 1000 / shell 2000 / root 0);
                // gating risks orphaned resident services defeating SERVICE_VERSION;
                // foreign DESTROY impact is negligible availability-only DoS (Shizuku rebinds, no command execution),
                // so leave ungated deliberately. EXEC remains the P0-gated vector.
                System.exit(0)
                return true
            }
            ShizukuExecProtocol.TRANSACTION_EXEC -> {
                // P0 gate: auth runs BEFORE reading the cmd (fail closed, do
                // no work for a foreign caller; Parcel position is left
                // unread on deny -- the reply is terminal either way).
                val callingUid = Binder.getCallingUid()
                if (!isAllowedCaller(callingUid, expectedAppUid)) {
                    if (BuildConfig.DEBUG) Log.w("GalaxyExec", "denied code=$code callingUid=$callingUid")
                    writeDenied(reply)
                    return true
                }
                val cmd = try {
                    data.readString() ?: ""
                } catch (e: Exception) {
                    reply?.writeInt(1)
                    reply?.writeString("")
                    reply?.writeString("bad request: ${(e.message ?: e.javaClass.simpleName)}")
                    return true
                }
                // Runs under the service uid (shell/root): captureProcess is
                // the same helper the su gateway uses; already off-main here
                // (binder thread), so call it directly.
                // Pre-spawn vs post-spawn (Phase A1): spawn failures
                // ("spawn failed: ...", exec threw, process never started)
                // are already returned INSIDE captureProcess as definite
                // failures and never throw here. Any exception ESCAPING
                // captureProcess therefore happened AFTER spawn started --
                // the remote pm may have executed -- so this catch is
                // post-spawn unknown-after-send by construction. The
                // "service exec failed: " prefix is the wire-frozen backstop:
                // the (exit, out, err) parcel drops the transportUnknown
                // flag, so DualRepository.isUnknownAfterSend matches this
                // anchored text via isPostSpawnTransportText (fail closed:
                // read-only verify, no resend). Anchoring is exact
                // (startsWith "service exec failed: ") so normal pm
                // "Failure [...]" lines (capital F) can never hit.
                val res = try {
                    captureProcess(arrayOf("sh", "-c", cmd), ShizukuExecProtocol.EXEC_TIMEOUT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    CmdResult(exit = 1, out = "", err = "service exec failed: interrupted: ${(e.message ?: e.javaClass.simpleName)}", truncated = true, transportUnknown = true)
                } catch (e: Exception) {
                    CmdResult(exit = 1, out = "", err = "service exec failed: ${(e.message ?: e.javaClass.simpleName)}", transportUnknown = true)
                }
                val (replyExit, replyOut, replyErr) = encodeExecReply(res)
                reply?.writeInt(replyExit)
                reply?.writeString(replyOut)
                reply?.writeString(replyErr)
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }
}

// Injectable bind seam (Phase B P2-2, issue #34 re-review #10): the Shizuku
// statics plus the Android ComponentName/UserServiceArgs construction cannot
// run on the JVM (android.jar stubs throw "Stub!"), so the bind-await-unbind
// lifecycle is driven through this driver. Production uses
// RealShizukuBindDriver below (identical behavior to the old inline code);
// JVM tests inject a fake that controls callback timing (never-complete for
// contention, manual complete for late-arrival) and counts releases.
// Contract: send() starts exactly one bind request and returns its handle;
// onResult fires at most once (binder or failure); a throwing send() means
// NO request was sent (nothing to release); Request.release() unbinds the
// sent request (production impl idempotent, fakes count every call so tests
// pin the gateway to at most one release per request).
interface ShizukuBindDriver {
    interface Request {
        fun release()
    }

    fun send(onResult: (Result<IBinder>) -> Unit): Request
}

// Production driver: identical bind/unbind behavior to the pre-seam inline
// code (same args chain, same unbindUserService(args, conn, true)).
private class RealShizukuBindDriver(
    private val packageName: String,
    private val serviceClassName: String,
    private val uidProvider: () -> Int = { Process.myUid() },
) : ShizukuBindDriver {
    override fun send(onResult: (Result<IBinder>) -> Unit): ShizukuBindDriver.Request {
        val callerUid = uidProvider()
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, serviceClassName),
            // Tag is per-UID derived via galaxyExecServiceTag(callerUid)
            // (full caller uid, stable); SERVICE_VERSION (4) remains the
            // invalidation signal that forces Shizuku/Sui to rebuild the
            // resident UserService on upgrade (see ShizukuExecProtocol).
        ).processNameSuffix("dualexec").version(ShizukuExecProtocol.SERVICE_VERSION).tag(galaxyExecServiceTag(callerUid))
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) onResult(Result.success(service))
                else onResult(Result.failure(IllegalStateException("null service binder")))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Server process died; next run() rebinds via isBinderAlive.
            }
        }
        // Throws IllegalStateException when the binder just died: caller
        // maps it to a CmdResult, never crashing the app. A throw here means
        // no request was sent, so the gateway releases nothing.
        Shizuku.bindUserService(args, conn)
        val released = AtomicBoolean(false)
        return object : ShizukuBindDriver.Request {
            override fun release() {
                if (released.compareAndSet(false, true)) {
                    try {
                        Shizuku.unbindUserService(args, conn, true)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}

// Independent cleanup scope (Phase B P2-2): deferred abandon() cleanup must
// not depend on viewModelScope (already cancelled in onCleared). One shared
// daemon scope for all gateways; Dispatchers.IO threads never block process
// exit. Never cancelled.
private val shizukuCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Bounded wait for the deferred abandon() cleanup: an in-flight bind holds
// bindMutex at most bindTimeoutMs, so bindTimeoutMs + margin always lets a
// cancelled in-flight attempt finish first (its own try/finally then
// releases, the deferred drop is a no-op). Derived from the instance timeout
// so a custom bindTimeoutMs > 15 s keeps the guarantee. If the wait still
// expires, the next lock holder drains via the closed check -- never a
// silent leak.
private const val SHIZUKU_CLEANUP_MARGIN_MS = 5_000L

// SHELL gateway: executes core-built pm commands inside GalaxyExecService via
// Shizuku.bindUserService. Constructor takes plain strings only (no Android
// objects) so it stays JVM-constructible for unit tests; the ComponentName is
// built lazily inside the production driver, never on a test thread.
// DEVICE-UNVERIFIED: no binder round-trip has ever run; every failure mode
// below returns CmdResult(exit != 0) instead of throwing.
class ShizukuCmdGateway(
    private val packageName: String,
    private val serviceClassName: String = GalaxyExecService::class.java.name,
    // Phase B P2-2 seams: bind timeout (default = wire budget) and the bind
    // driver (default = real Shizuku). Trailing params with defaults so every
    // existing call site (factory, ViewModel default, tests) keeps working.
    private val bindTimeoutMs: Long = ShizukuExecProtocol.BIND_TIMEOUT_MS,
    private val bindDriver: ShizukuBindDriver? = null,
    private val cleanupScope: CoroutineScope? = null,
    // Phase C1: caller-uid provider for the per-UID bind tag (default =
    // Process.myUid() at bind time inside RealShizukuBindDriver). Nullable
    // trailing param with default null so existing call sites keep working
    // and JVM construction stays stub-free (Process.myUid() is resolved
    // lazily only when the real driver is built).
    private val uidProvider: (() -> Int)? = null,
) : CmdGateway {
    override val privilege: Privilege = Privilege.SHELL

    private val resolvedDriver: ShizukuBindDriver by lazy {
        bindDriver ?: RealShizukuBindDriver(packageName, serviceClassName, uidProvider ?: { Process.myUid() })
    }
    private val resolvedCleanupScope: CoroutineScope by lazy {
        cleanupScope ?: shizukuCleanupScope
    }

    // Cached privileged binder + its owning request, guarded by bindMutex.
    // Bound lazily on first run(); process-lifetime cache (close() unbinds).
    // Once closed=true the gateway never caches again (fail closed).
    private val bindMutex = Mutex()
    private var cachedBinder: IBinder? = null
    private var cachedRequest: ShizukuBindDriver.Request? = null
    // Phase B P2-2: set by close()/abandon(), read by every lock holder.
    // A contended abandon() records a deferred cleanup instead of giving up.
    private val closed = AtomicBoolean(false)
    private val deferredCleanupScheduled = AtomicBoolean(false)

    override suspend fun run(cmd: String): CmdResult =
        withContext(Dispatchers.IO) {
            // Pre-flight: no binder -> explicit error, never IllegalStateException.
            val alive = try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withContext ShizukuExecErrors.noBinder()
            }
            if (!alive) return@withContext ShizukuExecErrors.noBinder()
            val granted = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withContext ShizukuExecErrors.remoteFailed(e.message ?: e.javaClass.simpleName)
            }
            if (!granted) return@withContext ShizukuExecErrors.notAuthorized()

            val version = try { Shizuku.getVersion() } catch (e: Exception) { if (e is CancellationException) throw e; return@withContext ShizukuExecErrors.remoteFailed(e.message ?: e.javaClass.simpleName) }
            if (!isSupportedShizukuVersion(version)) return@withContext ShizukuExecErrors.unsupportedVersion(version)
            // No dropBindingLocked here (nothing bound by this call; must not cache)
            val binder = try {
                boundBinder()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withContext ShizukuExecErrors.bindFailed(e.message ?: e.javaClass.simpleName)
            } ?: return@withContext ShizukuExecErrors.bindFailed("null binder")

            transactExec(binder, cmd)
        }

    // Returns the live service binder, binding on first use (mutex-guarded
    // single-flight, bounded wait). Must be called off the main thread
    // (blocking latch await); run() already dispatches to Dispatchers.IO.
    // Internal (not private) so JVM tests drive the bind lifecycle directly
    // through a fake driver (run()/transactExec need real Parcels and stay
    // device-only).
    //
    // Phase B P2-2 release guarantee: EVERY sent request is owned by a
    // try/finally. Only the success path promotes the request into the cache
    // (promoted=true); timeout, Job cancellation (CE out of latch.await),
    // callback failure, a send throw (no request sent -- nothing to release),
    // or an abandon()-during-bind (closed observed after the wait) all fall
    // through finally without promoting and release the request exactly
    // once. A late callback arriving after the release completes a discarded
    // latch and never triggers a second unbind. A promoted (cached) request
    // is released exactly once by the later close()/abandon()/drop path, so
    // success and failure releases never overlap (no double-unbind).
    internal suspend fun boundBinder(): IBinder? =
        bindMutex.withLock {
            if (closed.get()) {
                dropBindingLocked()
                throw IllegalStateException("shizuku gateway closed")
            }
            cachedBinder?.let { if (it.isBinderAlive) return@withLock it else dropBindingLocked() }
            val latch = CompletableDeferred<IBinder>()
            val request: ShizukuBindDriver.Request = try {
                resolvedDriver.send { result ->
                    val binder = result.getOrNull()
                    if (binder != null) latch.complete(binder)
                    else latch.completeExceptionally(
                        result.exceptionOrNull() ?: IllegalStateException("bind failed"),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw e
            }
            var promoted = false
            try {
                val binder = withTimeoutOrNull(bindTimeoutMs) {
                    latch.await()
                } ?: throw IllegalStateException("bind timed out after ${bindTimeoutMs}ms")
                if (closed.get()) throw IllegalStateException("shizuku gateway closed during bind")
                cachedRequest = request
                cachedBinder = binder
                promoted = true
                binder
            } finally {
                if (!promoted) {
                    try {
                        request.release()
                    } catch (e: RuntimeException) {
                        if (e is CancellationException) throw e
                    }
                }
            }
        }

    // One exec round-trip with a bounded client wait. Pre-dispatch refusals
    // (dead binder on the entry liveness check) stay definite failures via
    // binderDead(); post-dispatch reply loss maps to transportUnknown().
    // The stale cache is dropped on every failure path so the next call
    // rebinds instead of reusing a dead handle.
    //
    // Bounded deadline (PR-32 P1-1, P2 Job-cancellation-safe rework): the
    // Parcel transact below is a synchronous binder.transact(flags=0) that
    // Dispatchers.IO cannot interrupt, so the previous withTimeoutOrNull
    // wrapper did not bound the client wait (a hung transact kept the caller
    // suspended past the deadline). The block now runs via
    // awaitBounded(EXEC_TIMEOUT_MS) on the shared bounded "shizuku-transact"
    // pool while the caller suspends: the client wait always returns by the
    // deadline with null, Job cancellation (e.g. selectChannel latest-wins)
    // stops the wait at once with CancellationException, and the Binder call
    // itself is never force-stopped.
    //
    // Late-result isolation: after the deadline the caller stops waiting and
    // this returns timedOut() (exit != 0, "shizuku: exec timed out..."). That
    // outcome is UNKNOWN by construction, never authoritative: the repository
    // judges it through the trusted parse (#22 owns that rule -- a non-zero /
    // error-semantic capture is Unknown, never a confirmed empty), refresh
    // keeps the previous `cloned` instead of wiping it, and the bounded probe
    // retry (at most 2 settle waits, core delayFn) is the ONLY resend -- this
    // gateway itself never retries internally, so a timeout never triggers a
    // single delayed resend that may overlap a still-running service call.
    // The orphaned transact, if the binder thread is still blocked in it after
    // the deadline, owns its Parcel pair (recycled in finally below); its
    // late reply is discarded, never applied to state -- the await future's
    // late value is dropped and touches no shared holder.
    // dropBindingLocked() on the timeout path additionally guarantees the
    // next run() rebinds fresh instead of reusing the timed-out handle.
    // A delivered write is never blindly resent by this gateway.
    //
    // Tri-state dispatch rule (PR-32 P1-2): anything thrown at or after
    // binder.transact -- transact RemoteException/binder death, reply
    // readInt/readString decode failure, or the await interrupt -- means the
    // service may already have executed pm while the client holds no reply,
    // so it returns transportUnknown() (timedOut=false,
    // transportUnknown=true) for read-only reconciliation, NEVER a definite
    // failure. Only the pre-transact liveness refusal above (command never
    // sent) stays binderDead() -> definite failure.
    private suspend fun transactExec(binder: IBinder, cmd: String): CmdResult {
        if (!binder.isBinderAlive) {
            bindMutex.withLock { dropBindingLocked() }
            return ShizukuExecErrors.binderDead()
        }
        return try {
            val out = awaitBounded(ShizukuExecProtocol.EXEC_TIMEOUT_MS) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeString(cmd)
                    binder.transact(ShizukuExecProtocol.TRANSACTION_EXEC, data, reply, 0)
                    // No readException(): the service writes a raw int exit
                    // first, which readException would misread as an
                    // exception code on any non-zero exit.
                    // The service side truncates via buildResult/capped() in
                    // SuCmdGateway.kt: whenever truncated=true the marker
                    // text is appended to the streams, and the wire layout
                    // only carries (exit, stdout, stderr). Mirror the
                    // marker into CmdResult.truncated via decodeExecReply()
                    // so parsers can tell "clean empty" from "cut off"
                    // without changing any caller.
                    val execExit = reply.readInt()
                    val execOut = reply.readString() ?: ""
                    val execErr = reply.readString() ?: ""
                    decodeExecReply(execExit, execOut, execErr)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
            out ?: run {
                bindMutex.withLock { dropBindingLocked() }
                ShizukuExecErrors.timedOut()
            }
        } catch (e: CancellationException) {
            // Intentionally no dropBindingLocked() on the cancel path: the
            // orphan transact still owns the old handle but the driver serves
            // concurrent transacts, so the next call reuses it and only a real
            // failure rebuilds -- avoids rebind churn on latest-wins cancels.
            throw e
        } catch (e: InterruptedException) {
            // The block's own interrupt, unwrapped from the future by
            // awaitBounded (the caller's Dispatchers.IO thread suspended and
            // was never interrupted, so the consume below is a no-op unless
            // the pool worker's flag somehow leaked -- kept as hygiene).
            // Post-dispatch by construction (thrown at/after transact): the
            // service may have executed, so transportUnknown via the pure
            // mapPostDispatchError() (JVM-pinned), never a definite failure.
            Thread.interrupted()
            bindMutex.withLock { dropBindingLocked() }
            mapPostDispatchError(e)
        } catch (e: Exception) {
            // Post-dispatch by construction (transact RemoteException, binder
            // death mid-call, or reply readInt/readString decode failure):
            // the service may have executed pm with no reply to judge, so
            // reconcile read-only via the pure mapPostDispatchError()
            // (JVM-pinned) instead of claiming definite failure.
            bindMutex.withLock { dropBindingLocked() }
            mapPostDispatchError(e)
        }
    }

    // Drops the cached binding; caller must hold bindMutex, except close()
    // which takes it. Unbind is best-effort: the server side may be gone.
    // Idempotent: nulls the cache first, so a second drop releases nothing.
    private fun dropBindingLocked() {
        val request = cachedRequest
        cachedBinder = null
        cachedRequest = null
        if (request != null) {
            try {
                request.release()
            } catch (_: Exception) {
            }
        }
    }

    // Test seam: reports whether a binding is currently cached (acquires the
    // mutex, so call only when quiescent -- never from inside a bind).
    internal suspend fun hasCachedBindingForTest(): Boolean =
        bindMutex.withLock { cachedBinder != null }

    // Test seam: reports the terminal-closed flag (Phase C P1). Soft
    // releaseBinding() leaves this false (reusable); close()/abandon() set
    // it true permanently (fail closed, never rebinds).
    internal fun isClosedForTest(): Boolean = closed.get()

    // Soft release (Phase C P1, AUTO soft-degrade): drops the cached
    // UserService binding but leaves the gateway REUSABLE (closed stays
    // false), so a later probe may rebind the same instance. Swap paths call
    // this for instances still referenced by suGateway/shizukuGateway/
    // retained slots (AUTO holds the Shizuku instance across a SHIZUKU ->
    // NOOP degrade and must recover on the next reconnect without a picker
    // rebuild). Contrast with close()/abandon() below, which are TERMINAL:
    // they mark closed=true first so no later bind ever repopulates the
    // cache (ViewModel cleared / truly displaced instances only).
    // Idempotent: dropBindingLocked nulls the cache, so a second call
    // releases nothing.
    suspend fun releaseBinding() {
        bindMutex.withLock { dropBindingLocked() }
    }

    // TERMINAL release (e.g. from process teardown or a truly displaced
    // instance). The gateways are otherwise process-lifetime objects owned by
    // the ViewModel factory; not calling close() before process death is
    // fine. Marks the gateway closed first so no later bind reuses or
    // repopulates the cache; every lock holder observes closed and drops
    // instead of promoting. Never call this for an instance still referenced
    // by suGateway/shizukuGateway/retained slots -- use releaseBinding()
    // above so the next probe can rebind.
    suspend fun close() {
        closed.set(true)
        bindMutex.withLock { dropBindingLocked() }
    }

    // Non-suspend best-effort release for ViewModel.onCleared (Phase N2, hardened
    // by Phase B P2-2): onCleared cannot suspend and viewModelScope is already
    // cancelled, so a suspend close() (bindMutex.withLock) is unusable there.
    // This marks the gateway closed and guarantees eventual cleanup instead
    // of giving up after one contended tryLock:
    // - uncontended: takes the binding inline (same as before);
    // - contended (an in-flight boundBinder holds the mutex): records a
    //   deferred cleanup on the independent shizukuCleanupScope (never the
    //   ViewModel scope) which drops the cache under a bounded wait once the
    //   lock is free. The in-flight attempt itself also observes closed after
    //   its wait and releases its own request instead of caching it, so the
    //   contended window cannot leak even if the deferred wait expires -- the
    //   next boundBinder/close holder would drain via the same closed check.
    // Idempotent: closed CAS runs once; dropBindingLocked nulls the cache, so
    // a second abandon()/close() finds null and unbinds nothing (no
    // double-unbind).
    fun abandon() {
        if (!closed.compareAndSet(false, true)) return
        if (bindMutex.tryLock()) {
            try {
                dropBindingLocked()
            } finally {
                bindMutex.unlock()
            }
        } else {
            scheduleDeferredCleanup()
        }
    }

    private fun scheduleDeferredCleanup() {
        if (!deferredCleanupScheduled.compareAndSet(false, true)) return
        val scope = try {
            resolvedCleanupScope
        } catch (_: Exception) {
            return
        }
        try {
            scope.launch {
                try {
                    withTimeoutOrNull(bindTimeoutMs + SHIZUKU_CLEANUP_MARGIN_MS) {
                        bindMutex.withLock { dropBindingLocked() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort: the stale binding dies with the process
                    // anyway, and every later lock holder drains via closed.
                }
            }
        } catch (_: Exception) {
            deferredCleanupScheduled.set(false)
        }
    }
}
