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

    // Probe: `pm list users` must exit 0; dual id resolved via the shared
    // C1 safety boundary (exactly one DUAL-labeled non-zero profile, else -1).
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
        val profiles = PmOutputParser.parseUsers(res.out)
        val dualId = PmOutputParser.resolveDualUserId(profiles)
        return ProbeResult(
            level = Privilege.SHELL,
            hasShizuku = true,
            hasRoot = false,
            dualUserId = dualId,
        )
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

    // Probe: `id -u` must print 0; then resolve the dual profile id.
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
        val dualId = if (usersRes.exit == 0) {
            val profiles = PmOutputParser.parseUsers(usersRes.out)
            PmOutputParser.resolveDualUserId(profiles)
        } else {
            -1
        }
        return ProbeResult(
            level = Privilege.ROOT,
            hasShizuku = false,
            hasRoot = true,
            dualUserId = dualId,
        )
    }
}

// LSPosed hook (ROOT display) elevator over an injected active flag.
//
// Phase H3 (F4b): the flag comes from the app-side HookStatusProvider, which
// reads the self-scope prefs written by the hook's own onPackageLoaded branch
// (per-user, MODE_PRIVATE, no IPC). The hook channel carries no pm execution:
// install/remove still run through SuCmdGateway/ShizukuCmdGateway, so
// dualUserId stays -1 here and the ViewModel reuses the su/Shizuku gateway
// instance when this probe passes. Pure function of the flag: JVM-testable.
// DEVICE-UNVERIFIED: self-scope prefs round-trip never ran on a device.
class HookElevator(
    private val activeProvider: () -> Boolean = { false },
) : Elevator {
    override val level: Privilege = Privilege.ROOT

    override suspend fun probe(): ProbeResult {
        val active = try {
            activeProvider()
        } catch (_: Exception) {
            false
        }
        return if (active) {
            ProbeResult(
                level = Privilege.ROOT,
                hasShizuku = false,
                hasRoot = true,
                dualUserId = -1,
            )
        } else {
            ProbeResult(
                level = Privilege.NONE,
                hasShizuku = false,
                hasRoot = false,
                dualUserId = -1,
            )
        }
    }
}
