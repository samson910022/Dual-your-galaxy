// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) daagent read-query hook candidates.
//
// Kept free of android.* / LibXposed types so plain JVM unit tests can lock
// the contract without loading DualHook (which extends XposedModule and is
// compileOnly at test runtime). Source of truth for the Stage1 DEVICE-
// UNVERIFIED candidates consumed by DualHook.hookDaagentQueryMethods().
// Read-query candidates only; install/remove paths are never listed.
object DaagentQueryHooks {
    // Stage1 daagent read-query class candidates (DEVICE-UNVERIFIED).
    // S24 evidence pins the WhiteListApps holder (static-final => ObserveOnly)
    // and names these query holders (DualAppDBHelper / DAUtility /
    // DualAppManager) without pinning their package layout, so subpackage
    // variants are all tried; every loadable class is hooked.
    internal val DAAGENT_QUERY_CLASS_CANDIDATES = listOf(
        "com.samsung.android.da.daagent.provider.DualAppDBHelper",
        "com.samsung.android.da.daagent.db.DualAppDBHelper",
        "com.samsung.android.da.daagent.DualAppDBHelper",
        "com.samsung.android.da.daagent.provider.DAUtility",
        "com.samsung.android.da.daagent.util.DAUtility",
        "com.samsung.android.da.daagent.DAUtility",
        "com.samsung.android.da.daagent.provider.DualAppManager",
        "com.samsung.android.da.daagent.DualAppManager",
    )

    // Read-query method names only (extend-only WhitelistPolicy.apply).
    //
    // Stage 1 (safest): installed-and-whitelist composite queries
    // (getAllInstalledWhitelistedPackages, isInstalledWhitelistedPackage) are
    // intentionally EXCLUDED from rewrite/hook eligibility due to install-state
    // and userId ambiguity; fail-closed until device-verified. See
    // WhitelistSignatureGuard.isInstalledWhitelistCompositeQuery and
    // WhitelistHookEligibility.
    internal val DAAGENT_QUERY_METHOD_NAMES = listOf(
        "getWhitelistedPackages",
        "getWhitelistPackages",
        "getWhiteListPkgs",
        "getWhiteListPkgsInSystemServer",
        "isWhitelistedPackage",
        "isWhiteListedPackage",
    )
}
