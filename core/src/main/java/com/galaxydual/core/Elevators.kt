// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Elevation channel: probes its privilege level and exposes command strings.
// Implementations stay API-free placeholders: they only run shell command
// strings through a CmdGateway and never link against vendor SDKs here.
interface Elevator {
    // Privilege this elevator provides when its probe succeeds.
    val level: Privilege

    // Probes the channel and resolves the dual profile id (-1 when unknown).
    suspend fun probe(): ProbeResult
}

// Shizuku (SHELL) elevator over an injected CmdGateway.
// App side provides ShizukuCmdGateway (UserService GalaxyExecService;
// Shizuku#newProcess was removed in 13.1.5, not used). DEVICE-UNVERIFIED.
class ShizukuElevator(
    private val gateway: CmdGateway = NoOpGateway(),
) : Elevator {
    override val level: Privilege = Privilege.SHELL

    // Command used to list profiles; kept as a constant for testability.
    val listUsersCmd: String = "pm list users"

    // Verified-identity channels (PR-1 stage 2, issue #21): shared with
    // DualRepository (same command strings, same adapter, no label read).
    val userListVerboseCmd: String = DualRepository.CMD_USER_LIST_VERBOSE
    val dumpsysUserCmd: String = DualRepository.CMD_DUMPSYS_USER
    val getpropDualCmd: String = DualRepository.CMD_GETPROP_DUAL

    // Probe: `pm list users` must exit 0; dual id resolved via the verified
    // cross-source boundary (exactly one CLONE-typed non-zero id agreeing
    // across `cmd user list -v` + `dumpsys user` (+ property when present),
    // else -1). The pm-list label is never read.
    override suspend fun probe(): ProbeResult {
        val res = gateway.run(listUsersCmd)
        if (res.exit != 0) {
            return ProbeResult(
                level = Privilege.NONE,
                hasShizuku = false,
                hasRoot = false,
                dualUserId = -1,
            )
        }
        val dualId = resolveVerifiedDualId()
        return ProbeResult(
            level = Privilege.SHELL,
            hasShizuku = true,
            hasRoot = false,
            dualUserId = dualId,
        )
    }

    // Shared identity source: fetches both verbose channels plus the dual
    // property and maps Confirmed -> id, Unknown/Refused/channel failure ->
    // -1 (fail closed). Never reads the pm-list label.
    private suspend fun resolveVerifiedDualId(): Int {
        val verboseRes = try {
            gateway.run(userListVerboseCmd)
        } catch (_: Exception) {
            return -1
        }
        val dumpsysRes = try {
            gateway.run(dumpsysUserCmd)
        } catch (_: Exception) {
            return -1
        }
        if (verboseRes.exit != 0 || dumpsysRes.exit != 0) return -1
        if (!PmOutputParser.isSuccess(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) return -1
        if (!PmOutputParser.isSuccess(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) return -1
        val propRes = try {
            gateway.run(getpropDualCmd)
        } catch (_: Exception) {
            null
        }
        val propertyId = propRes
            ?.takeIf { it.exit == 0 }
            ?.let { PmOutputParser.parseDualPropertyId(it.out) }
        return PmOutputParser.resolveDualUserIdVerified(verboseRes.out, dumpsysRes.out, propertyId)
    }
}

// Superuser (ROOT) elevator over an injected CmdGateway.
// App side provides SuCmdGateway (`su -c` argv single-arg passthrough).
// This class only assembles command strings. DEVICE-UNVERIFIED.
class SuElevator(
    private val gateway: CmdGateway = NoOpGateway(),
) : Elevator {
    override val level: Privilege = Privilege.ROOT

    // Commands used for probing; kept visible for tests and logging.
    val whoAmICmd: String = "id -u"
    val listUsersCmd: String = "pm list users"

    // Verified-identity channels (same shared source as ShizukuElevator and
    // DualRepository; see above).
    val userListVerboseCmd: String = DualRepository.CMD_USER_LIST_VERBOSE
    val dumpsysUserCmd: String = DualRepository.CMD_DUMPSYS_USER
    val getpropDualCmd: String = DualRepository.CMD_GETPROP_DUAL

    // Probe: `id -u` must print 0; then resolve the dual profile id via the
    // verified cross-source boundary (never the pm-list label).
    override suspend fun probe(): ProbeResult {
        val idRes = gateway.run(whoAmICmd)
        val isRoot = idRes.exit == 0 && idRes.out.trim() == "0"
        if (!isRoot) {
            return ProbeResult(
                level = Privilege.NONE,
                hasShizuku = false,
                hasRoot = false,
                dualUserId = -1,
            )
        }
        val usersRes = gateway.run(listUsersCmd)
        if (usersRes.exit != 0) {
            return ProbeResult(
                level = Privilege.ROOT,
                hasShizuku = false,
                hasRoot = true,
                dualUserId = -1,
            )
        }
        val dualId = resolveVerifiedDualId()
        return ProbeResult(
            level = Privilege.ROOT,
            hasShizuku = false,
            hasRoot = true,
            dualUserId = dualId,
        )
    }

    // Same shared identity source as ShizukuElevator (fail closed to -1).
    private suspend fun resolveVerifiedDualId(): Int {
        val verboseRes = try {
            gateway.run(userListVerboseCmd)
        } catch (_: Exception) {
            return -1
        }
        val dumpsysRes = try {
            gateway.run(dumpsysUserCmd)
        } catch (_: Exception) {
            return -1
        }
        if (verboseRes.exit != 0 || dumpsysRes.exit != 0) return -1
        if (!PmOutputParser.isSuccess(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) return -1
        if (!PmOutputParser.isSuccess(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) return -1
        val propRes = try {
            gateway.run(getpropDualCmd)
        } catch (_: Exception) {
            null
        }
        val propertyId = propRes
            ?.takeIf { it.exit == 0 }
            ?.let { PmOutputParser.parseDualPropertyId(it.out) }
        return PmOutputParser.resolveDualUserIdVerified(verboseRes.out, dumpsysRes.out, propertyId)
    }
}

// NOTE (H4 reconciliation with #19): an LSPosed Elevator was evaluated (H3:
// HookElevator over a self-scope prefs flag) and deliberately dropped. The
// F4b-P2 file marker (HookMarker, scopes system_server/daagent, proven by
// installed-hook counts) is the single hook-active signal, and by the #19
// principle it is DISPLAY-ONLY: it never enters privilege routing
// (hasLsposed stays false) and never satisfies channel readiness. A present
// marker is existence+scope evidence, not proof of a live, effective hook.
// Keeping a hook Elevator would risk granting privilege on that weaker claim.
