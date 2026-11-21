// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase1 review(P2)：既有 JVM 測試使用的 *InUser95 包裝從生產源碼搬到此處。
// 兩者只是以 device hypothesis 95 呼叫正式 API 的測試 helper；生產源碼中不再
// 存在任何可呼叫的硬編碼 95 路徑（正式 API 必須由 resolvePreClearDualUserId 供 uid）。
package com.galaxydual.plus

import com.galaxydual.core.CmdGateway

internal suspend fun probeDualAppsInUser95(
    pkgs: Collection<String>,
    gateway: CmdGateway = SuCmdGateway(),
): DualProbeResult = probeDualApps(pkgs, 95, gateway)

internal suspend fun uninstallDualAppsInUser95(
    pkgs: Collection<String>,
    gateway: CmdGateway = SuCmdGateway(),
): Boolean = uninstallDualApps(pkgs, 95, gateway)
