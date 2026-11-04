// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Privilege level selected by the router.
// NONE: read-only, no write channel.
// SHELL: Shizuku / ADB shell channel.
// ROOT: LSPosed hook or su channel (highest privilege).
enum class Privilege {
    NONE,
    SHELL,
    ROOT,
}

// Result of probing the device for available elevation channels.
// dualUserId is the Android user id hosting the dual clone, or -1 when unknown.
data class ProbeResult(
    val level: Privilege,
    val hasShizuku: Boolean,
    val hasRoot: Boolean,
    val dualUserId: Int = -1,
)

// Pure privilege selector, no side effects.
// LSPosed auto-mount and su root both grant ROOT; Shizuku grants SHELL.
fun routePrivilege(hasLsposed: Boolean, hasShizuku: Boolean, hasRoot: Boolean): Privilege {
    return if (hasLsposed || hasRoot) {
        Privilege.ROOT
    } else if (hasShizuku) {
        Privilege.SHELL
    } else {
        Privilege.NONE
    }
}

// Backward-compatible overload for callers using the older 2-arg form.
// hookActive maps to hasLsposed, shizukuAlive maps to hasShizuku.
fun routePrivilege(hookActive: Boolean, shizukuAlive: Boolean): Privilege {
    return routePrivilege(hasLsposed = hookActive, hasShizuku = shizukuAlive, hasRoot = false)
}

// Pure readiness gate: is the selected gateway's channel proven?
// H3 (F4b): a ROOT executor is satisfied by su OR the LSPosed self-scope
// mark (the hook carries no pm channel itself; install/remove still run
// through the su/Shizuku gateway and report failures via CmdResult).
// A NoOp (NONE) executor is never ready: hook-only devices stay read-only.
fun channelReadyFor(
    gateway: Privilege,
    hasRoot: Boolean,
    hasShizuku: Boolean,
    hasHook: Boolean,
): Boolean {
    return when (gateway) {
        Privilege.ROOT -> hasRoot || hasHook
        Privilege.SHELL -> hasShizuku
        Privilege.NONE -> false
    }
}
