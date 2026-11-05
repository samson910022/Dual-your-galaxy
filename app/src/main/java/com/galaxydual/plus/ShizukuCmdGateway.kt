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
//   System.exit(0).
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
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.Privilege
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

// Wire protocol between ShizukuCmdGateway (client) and GalaxyExecService.
// TRANSACTION_EXEC is a custom code on our own Binder subclass; it must never
// collide with the framework-reserved UserService destroy codes (16777114 in
// aidl spelling, 16777115 on the wire) -- enforced by unit test.
// Reply layout (no exception header on purpose): int exit, String stdout,
// String stderr. The client must NOT call reply.readException(): a non-zero
// exit word would be misread as an exception code.
// PR-2 stage 1 (issue #22, additive only): the reply does NOT carry
// CmdResult.truncated explicitly (layout frozen); the client re-derives it
// from TRUNCATION_MARKER below. Service-side captureProcess already sets the
// flag, but only the marker survives the parcel.
internal const val TRUNCATION_MARKER = "[truncated]"
object ShizukuExecProtocol {
    const val TRANSACTION_EXEC = 1
    const val TRANSACTION_DESTROY = 16777115
    const val BIND_TIMEOUT_MS = 10_000L
    const val EXEC_TIMEOUT_MS = 60_000L
}

// Pure error mapping for every Shizuku-side failure: binder dead, missing
// authorization, bind failure, remote failure, timeout. All exit != 0 with an
// explicit message; pure Kotlin so GatewayAssemblyTest covers it on the JVM.
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

    fun timedOut(): CmdResult =
        CmdResult(exit = 1, out = "", err = "shizuku: exec timed out after ${ShizukuExecProtocol.EXEC_TIMEOUT_MS}ms")
}

// Privileged execution service, instantiated by the Shizuku server process
// (shell/root uid), NOT by this app's process. Must stay a public top-level
// class with both constructors: Shizuku v13 tries the Context constructor
// first via reflection, older servers use the default one. The Context is the
// package-scoped user context; it is intentionally unused (documented by the
// official guide as limited: no receivers/resolvers).
// NOTE: minification is off for all build types (see app/build.gradle.kts),
// so no -keep rule is needed today. If minify is ever enabled, add:
//   -keep class com.galaxydual.plus.GalaxyExecService { <init>(...); }
// DEVICE-UNVERIFIED: bind/exec round-trip never ran on a device.
class GalaxyExecService : Binder {
    constructor() : super()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            ShizukuExecProtocol.TRANSACTION_DESTROY -> {
                // Reserved destroy entry: cleanup then exit, per official guide.
                System.exit(0)
                return true
            }
            ShizukuExecProtocol.TRANSACTION_EXEC -> {
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
                val res = try {
                    captureProcess(arrayOf("sh", "-c", cmd), ShizukuExecProtocol.EXEC_TIMEOUT_MS)
                } catch (e: Exception) {
                    CmdResult(exit = 1, out = "", err = "service exec failed: ${(e.message ?: e.javaClass.simpleName)}")
                }
                reply?.writeInt(res.exit)
                reply?.writeString(res.out)
                reply?.writeString(res.err)
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }
}

// SHELL gateway: executes core-built pm commands inside GalaxyExecService via
// Shizuku.bindUserService. Constructor takes plain strings only (no Android
// objects) so it stays JVM-constructible for unit tests; the ComponentName is
// built lazily inside run(), never on a test thread.
// DEVICE-UNVERIFIED: no binder round-trip has ever run; every failure mode
// below returns CmdResult(exit != 0) instead of throwing.
class ShizukuCmdGateway(
    private val packageName: String,
    private val serviceClassName: String = GalaxyExecService::class.java.name,
) : CmdGateway {
    override val privilege: Privilege = Privilege.SHELL

    // Cached privileged binder + its connection, guarded by bindMutex. Bound
    // lazily on first run(); process-lifetime cache (close() unbinds).
    private val bindMutex = Mutex()
    private var cachedBinder: IBinder? = null
    private var cachedArgs: Shizuku.UserServiceArgs? = null
    private var cachedConn: ServiceConnection? = null

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
    private suspend fun boundBinder(): IBinder? =
        bindMutex.withLock {
            cachedBinder?.let { if (it.isBinderAlive) return@withLock it else dropBindingLocked() }
            val latch = CompletableDeferred<IBinder>()
            val args = Shizuku.UserServiceArgs(
                ComponentName(packageName, serviceClassName),
            ).processNameSuffix("dualexec").version(1).tag("GalaxyDualExec-v1")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service != null) latch.complete(service)
                    else latch.completeExceptionally(IllegalStateException("null service binder"))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // Server process died; next run() rebinds via isBinderAlive.
                }
            }
            // Throws IllegalStateException when the binder just died: caller
            // maps it to a CmdResult, never crashing the app.
            Shizuku.bindUserService(args, conn)
            val binder = withTimeoutOrNull(ShizukuExecProtocol.BIND_TIMEOUT_MS) {
                latch.await()
            } ?: run {
                try {
                    Shizuku.unbindUserService(args, conn, true)
                } catch (_: Exception) {
                }
                throw IllegalStateException("bind timed out after ${ShizukuExecProtocol.BIND_TIMEOUT_MS}ms")
            }
            cachedArgs = args
            cachedConn = conn
            cachedBinder = binder
            binder
        }

    // One exec round-trip with a bounded wait. Dead binder / RemoteException /
    // timeout all map to explicit CmdResult errors; the stale cache is dropped
    // so the next call rebinds instead of reusing a dead handle.
    private suspend fun transactExec(binder: IBinder, cmd: String): CmdResult {
        if (!binder.isBinderAlive) {
            bindMutex.withLock { dropBindingLocked() }
            return ShizukuExecErrors.binderDead()
        }
        return try {
            val out = withTimeoutOrNull(ShizukuExecProtocol.EXEC_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeString(cmd)
                        binder.transact(ShizukuExecProtocol.TRANSACTION_EXEC, data, reply, 0)
                        // No readException(): the service writes a raw int exit
                        // first, which readException would misread as an
                        // exception code on any non-zero exit.
                        // PR-2 stage 1 (issue #22, additive only): the service
                        // side caps via captureProcess and appends the
                        // "\n[truncated]" marker, but the wire layout only
                        // carries (exit, stdout, stderr). Mirror the marker
                        // into CmdResult.truncated so parsers can tell "clean
                        // empty" from "cut off" without changing any caller.
                        val execExit = reply.readInt()
                        val execOut = reply.readString() ?: ""
                        val execErr = reply.readString() ?: ""
                        CmdResult(
                            exit = execExit,
                            out = execOut,
                            err = execErr,
                            truncated = execOut.contains(TRUNCATION_MARKER) ||
                                execErr.contains(TRUNCATION_MARKER),
                        )
                    } finally {
                        reply.recycle()
                        data.recycle()
                    }
                }
            }
            out ?: run {
                bindMutex.withLock { dropBindingLocked() }
                ShizukuExecErrors.timedOut()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            bindMutex.withLock { dropBindingLocked() }
            ShizukuExecErrors.remoteFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    // Drops the cached binding; caller must hold bindMutex, except close()
    // which takes it. Unbind is best-effort: the server side may be gone.
    private fun dropBindingLocked() {
        val args = cachedArgs
        val conn = cachedConn
        cachedBinder = null
        cachedArgs = null
        cachedConn = null
        try {
            if (args != null && conn != null) Shizuku.unbindUserService(args, conn, true)
        } catch (_: Exception) {
        }
    }

    // Releases the cached binding (e.g. from process teardown). The gateways
    // are otherwise process-lifetime objects owned by the ViewModel factory;
    // not calling close() before process death is fine.
    suspend fun close() {
        bindMutex.withLock { dropBindingLocked() }
    }
}
