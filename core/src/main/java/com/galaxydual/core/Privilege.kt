// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Privilege level selected by the router.
// NONE: read-only, no write channel.
// SHELL: Shizuku / ADB shell channel.
// ROOT: su channel (highest privilege). LSPosed is display-only and never grants privilege.
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
// su root grants ROOT; Shizuku grants SHELL. LSPosed is display-only and never
// grants privilege (hook signal must never be fed as an authorization input).
fun routePrivilege(hasShizuku: Boolean, hasRoot: Boolean): Privilege {
    return if (hasRoot) {
        Privilege.ROOT
    } else if (hasShizuku) {
        Privilege.SHELL
    } else {
        Privilege.NONE
    }
}

// Pure readiness gate: is the selected gateway's channel proven?
// H4 reconciliation with #19 (F4b-P2 principle, locked by ChannelReadyTest):
// the hook-enable signal is DISPLAY-ONLY and never satisfies readiness.
// A ROOT executor needs its own su probe (hasRoot); SHELL needs Shizuku;
// a NoOp (NONE) executor is never ready. Rationale: a present marker is
// existence+scope evidence from a file that survives reboots, not proof of
// a live, effective hook, so it must never grant privilege (or readiness).
fun channelReadyFor(
    gateway: Privilege,
    hasRoot: Boolean,
    hasShizuku: Boolean,
): Boolean {
    return when (gateway) {
        Privilege.ROOT -> hasRoot
        Privilege.SHELL -> hasShizuku
        Privilege.NONE -> false
    }
}
