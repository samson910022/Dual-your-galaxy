// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase P29 (issue #29): instrumentation harness shared fakes.
//
// Every androidTest in this directory runs zero device writes by construction:
// - ViewModel-level tests drive DualViewModel through FakeHarnessGateway (an
//   in-memory CmdGateway: identity channels agree on dualId 95, uninstalls
//   are counted but only mutate the in-memory set). No su/Shizuku/UserService
//   is ever touched.
// - Activity-level tests only launch MainActivity with the production
//   ViewModel and never tap Clone/確認移除; they assert rendering and
//   state restoration (rotation / background-return / input restore) without
//   issuing any write.
// - HarnessPoll replaces an IdlingResource with a bounded poll loop (same
//   shape as the JVM unit tests): waits on StateFlow/activity conditions with
//   an explicit timeout instead of Espresso idling.
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

// Bounded poll helper for instrumentation threads (UI thread must never
// block on it; call from the test thread only).
object HarnessPoll {
    fun await(timeoutMs: Long = 10_000L, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!cond()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("harness timeout waiting for condition")
            }
            Thread.sleep(50)
        }
    }
}

// In-memory repo gateway for instrumentation: same contract as the JVM
// RemoveConfirmTest fake (verified-identity channels agree on dualId, the
// uninstall path is stateful but memory-only). uninstallCalls is the
// zero-write assertion source: cancel/back must leave it at 0.
class FakeHarnessGateway(var dualId: Int = 95) : CmdGateway {
    override val privilege: Privilege = Privilege.ROOT
    var uninstallCalls = 0
    var installCalls = 0
    var writeCalls = 0
    val commandLog = mutableListOf<String>()
    var serial: Int = 15
    var serialAvailable: Boolean = true
    val installedPkgs = mutableSetOf("com.example.chat")

    private fun usersOut(): String =
        "Users:\n\tUserInfo{0:Owner:13} running\n" +
            (if (dualId < 0) "" else "\tUserInfo{$dualId:DualApp:400} running\n")

    private fun verboseOut(): String =
        "Users:\n" +
            "  UserInfo{0:Owner:13} running:\n" +
            "    Serial number: 0\n" +
            "    User type: android.os.usertype.full.SYSTEM\n" +
            (if (dualId < 0) "" else "  UserInfo{$dualId:DualApp:400} running:\n" +
                (if (serialAvailable) "    Serial: $serial\n" else "") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n")

    private fun dumpsysOut(): String =
        "Users:\n" +
            "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
            "    userType=android.os.usertype.full.SYSTEM\n" +
            (if (dualId < 0) "" else "  UserInfo{$dualId:DualApp:400}" +
                (if (serialAvailable) " serialNo=$serial" else "") + ":\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n")

    override suspend fun run(cmd: String): CmdResult {
        commandLog.add(cmd)
        // Read-only commands (pm list, cmd user list -v, dumpsys, id) are
        // NOT counted; only known write verbs are.
        if (cmd.startsWith("pm uninstall") || cmd.startsWith("pm install") ||
            cmd.startsWith("pm grant") || cmd.startsWith("am ") ||
            (cmd.startsWith("cmd ") && !cmd.startsWith("cmd user list"))) {
            writeCalls++
        }
        return when {
            cmd.trim() == "id -u" ->
                CmdResult(exit = 0, out = "0\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS ->
                CmdResult(exit = 0, out = usersOut(), err = "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = 0, out = verboseOut(), err = "")
            cmd == DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = 0, out = dumpsysOut(), err = "")
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm uninstall") -> {
                uninstallCalls++
                val tokens = cmd.trim().split("\\s+".toRegex())
                val pkg = run {
                    val idx = tokens.indexOf("--user")
                    if (idx >= 0 && idx + 2 < tokens.size) tokens[idx + 2]
                    else tokens.lastOrNull() ?: ""
                }
                installedPkgs.remove(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm install-existing") -> {
                installCalls++
                val tokens = cmd.trim().split("\\s+".toRegex())
                val pkg = run {
                    val idx = tokens.indexOf("--user")
                    if (idx >= 0 && idx + 2 < tokens.size) tokens[idx + 2]
                    else tokens.lastOrNull() ?: ""
                }
                if (pkg.isNotBlank()) installedPkgs.add(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val base = installedPkgs.map { "package:$it" }.joinToString("\n")
                CmdResult(exit = 0, out = base, err = "")
            }
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }
}

// ViewModel factory for instrumentation: probes run on the fake (real
// SuElevator over FakeHarnessGateway proves ROOT through the real path, same
// as the JVM tests); diagnosis paths are stubbed to direct reads only so no
// real gateway is ever executed.
fun newHarnessViewModel(
    gw: FakeHarnessGateway,
    preference: ChannelPreference = ChannelPreference.AUTO,
): DualViewModel =
    DualViewModel(
        repo = DualRepository(gw),
        gatewayPrivilege = Privilege.ROOT,
        elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
        preference = preference,
        appPackageName = "com.galaxydual.plus.debug.test",
        persistPreference = null,
        suPresentCheck = { true },
        shizukuAuthCheck = { false },
        suGateway = NoOpGateway(),
        shizukuGateway = NoOpGateway(),
        gatewayTeardown = { _ -> },
        activeGateway = gw,
        currentChannel = ResolvedChannel.ROOT,
        hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
        hookDiagnosticGateways = { emptyList() },
    )

fun awaitHarnessReady(vm: DualViewModel, timeoutMs: Long = 15_000L) {
    HarnessPoll.await(timeoutMs) { vm.state.value.channelReady }
}

// Focus-independent view traversal helpers for Activity-level assertions.
// Avoids Espresso's RootViewWithoutFocusException on CI emulator by walking
// the activity's decor view tree directly instead of relying on Espresso's
// focused-root resolution.
object HarnessViews {
    fun containsText(root: View?, text: CharSequence): Boolean {
        if (root == null) return false
        if (root is TextView && root.text?.toString()?.contains(text) == true) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsText(root.getChildAt(i), text)) return true
            }
        }
        return false
    }

    fun hasEditTextText(root: View?, text: CharSequence): Boolean {
        if (root == null) return false
        if (root is EditText && root.text?.toString() == text.toString()) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (hasEditTextText(root.getChildAt(i), text)) return true
            }
        }
        return false
    }

    fun isRemoveDialogShowing(activity: MainActivity): Boolean {
        return try {
            val field = MainActivity::class.java.getDeclaredField("removeConfirmDialog")
            field.isAccessible = true
            val dialog = field.get(activity) as? AlertDialog
            dialog?.isShowing == true
        } catch (e: Exception) {
            false
        }
    }
}
