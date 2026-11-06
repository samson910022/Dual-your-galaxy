// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// p28-1 review（en 殘留）：JVM 測試用繁中假解析——逐字鏡像 values/strings.xml 的
// 寫入拒絕理由模板（生產由 Application Context 解析資源，見
// MainActivity.resolveBlockReason 與 DualViewModelFactory 的 blockText）。
// 模板漂移由 UiSemanticsP28Test.strings_blockAndInputKeys_bilingual 鎖定
// （關鍵字雙語斷言）；此 fake 只供既有 VM msg 斷言沿用，不新增語意。
package com.galaxydual.plus

// 與 values/strings.xml 同文（%1$s 佔位由 args 代入）；en 版見 values-en/strings.xml。
internal fun zhTestBlockText(): (WriteBlock) -> String = { block ->
    when (block.kind) {
        WriteBlockKind.LSPOSED_PLACEHOLDER ->
            "LSPosed 通道為佔位（NoOp）：whitelist 政策分支待實作，" +
                "寫入已拒絕；Hook 狀態：${block.args.getOrElse(0) { "" }}；" +
                "如需寫入請切到 Auto/Root/Shizuku 後點「重新連線並更新」"
        WriteBlockKind.NOOP_FALLBACK ->
            "通道未就緒：真通道已接線但未選中（NoOp 回退），目前為唯讀，" +
                "寫入已禁用；請檢查授權後點「重新連線並更新」"
        WriteBlockKind.EXPLICIT_FAILURE ->
            "所選模式 ${block.args.getOrElse(0) { "?" }}（${block.args.getOrElse(1) { "?" }} 通道）探測未通過，" +
                "寫入已禁用（明選不跨通道）；可切到 Auto 或點「重新連線並更新」重試"
        WriteBlockKind.PM_UNREADABLE ->
            "探測通過但 PM 不可讀（pm list users 失敗），寫入已禁用；" +
                "請檢查授權後點「重新連線並更新」"
        WriteBlockKind.NO_PRIVILEGE ->
            "唯讀：無可用權限通道，寫入已禁用；" +
                "請檢查授權後點「重新連線並更新」"
    }
}

// 測試側 single-source 解析：Keyed 走繁中假值，Verbatim（AUTO 降級註記）逐字沿用。
internal fun resolveZhTest(reason: BlockReason): String = when (reason) {
    is BlockReason.Keyed -> zhTestBlockText()(reason.block)
    is BlockReason.Verbatim -> reason.text
}
