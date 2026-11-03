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

// Shizuku (SHELL) elevator placeholder.
// TODO: bind rikka.shizuku.Shizuku in the app module and forward its
// newProcess() output into a CmdGateway that is injected here.
class ShizukuElevator(
    private val gateway: CmdGateway = NoOpGateway(),
) : Elevator {
    override val level: Privilege = Privilege.SHELL

    // Command used to list profiles; kept as a constant for testability.
    val listUsersCmd: String = "pm list users"

    // Probe: `pm list users` must exit 0; dual id is the first non-owner profile.
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
        val dual = profiles.firstOrNull { it.label.contains("DUAL", ignoreCase = true) }
            ?: profiles.firstOrNull { it.userId != 0 }
        return ProbeResult(
            level = Privilege.SHELL,
            hasShizuku = true,
            hasRoot = false,
            dualUserId = dual?.userId ?: -1,
        )
    }
}

// Superuser (ROOT) elevator placeholder over a `su`-backed gateway.
// TODO: implement the injected gateway with `su -c <cmd>` (or the LSPosed
// hook channel) in the app module; this class only assembles command strings.
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
            (profiles.firstOrNull { it.label.contains("DUAL", ignoreCase = true) }
                ?: profiles.firstOrNull { it.userId != 0 })?.userId ?: -1
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
