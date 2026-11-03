package com.galaxydual.core

// Privilege level selected by PrivilegeRouter.
// NONE: read-only, SHELL: Shizuku/ADB shell, ROOT: LSPosed/su channel.
enum class Privilege {
    NONE,
    SHELL,
    ROOT,
}

// Pure selector: prefers ROOT over SHELL over NONE.
// hookActive means the LSPosed module is enabled; shizukuAlive means binder is reachable.
fun routePrivilege(hookActive: Boolean, shizukuAlive: Boolean): Privilege {
    // Highest privilege wins; no side effects in this function.
    return when {
        hookActive -> Privilege.ROOT
        shizukuAlive -> Privilege.SHELL
        else -> Privilege.NONE
    }
}
