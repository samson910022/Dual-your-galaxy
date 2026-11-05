// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Round 2 Phase B P2-5: resolveDualUserIdTyped() tri-state mapping.
// Confirmed / Absent / Unknown must be told apart; the legacy Int entry
// keeps folding non-Confirmed to -1 (compat, pinned here).
class DualIdResolutionTest {
    private class FakeGateway(
        var verboseOut: String = VERBOSE_95,
        var verboseExit: Int = 0,
        var verboseErr: String = "",
        var verboseTruncated: Boolean = false,
        var dumpsysOut: String = DUMPSYS_95,
        var dumpsysExit: Int = 0,
        var dumpsysErr: String = "",
        var dumpsysTruncated: Boolean = false,
        var propOut: String = "95\n",
        var propExit: Int = 0,
        var propErr: String = "",
        var propTruncated: Boolean = false,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL

        override suspend fun run(cmd: String): CmdResult = when (cmd) {
            DualRepository.CMD_USER_LIST_VERBOSE ->
                CmdResult(exit = verboseExit, out = verboseOut, err = verboseErr, truncated = verboseTruncated)
            DualRepository.CMD_DUMPSYS_USER ->
                CmdResult(exit = dumpsysExit, out = dumpsysOut, err = dumpsysErr, truncated = dumpsysTruncated)
            DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = propExit, out = propOut, err = propErr, truncated = propTruncated)
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(r: Result<T>) {
                    result = r
                }
            },
        )
        return result!!.getOrThrow()
    }

    @Test
    fun agreeingChannels_confirm() {
        val repo = DualRepository(FakeGateway())
        assertEquals(DualIdResolution.Confirmed(95), runSuspend { repo.resolveDualUserIdTyped() })
        assertEquals("legacy Int entry keeps working", 95, runSuspend { repo.resolveDualUserId() })
    }

    @Test
    fun cleanChannelsWithoutClone_absent() {
        val gw = FakeGateway(
            verboseOut = NODUAL_VERBOSE,
            dumpsysOut = NODUAL_DUMPSYS,
            propOut = "",
        )
        val repo = DualRepository(gw)
        assertEquals(DualIdResolution.Absent, runSuspend { repo.resolveDualUserIdTyped() })
        assertEquals("legacy Int folds Absent to -1", -1, runSuspend { repo.resolveDualUserId() })
    }

    @Test
    fun failedTypedChannel_unknown() {
        val repo = DualRepository(FakeGateway(verboseExit = 1))
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("failed typed channel must be Unknown, got: $res", res is DualIdResolution.Unknown)
        assertEquals("legacy Int folds Unknown to -1", -1, runSuspend { repo.resolveDualUserId() })
    }

    @Test
    fun contradictoryChannels_unknown() {
        // verbose 稱 95、dumpsys 稱 96：CLONE 身份矛盾 -> Refused -> Unknown，
        // 絕不可猜成 Absent（會被呼叫端清單）。
        val repo = DualRepository(FakeGateway(dumpsysOut = DUMPSYS_96))
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("contradiction must be Unknown, got: $res", res is DualIdResolution.Unknown)
    }

    @Test
    fun unparseableTypedOutput_unknownNotAbsent() {
        // exit 0 但無任何 user 區塊：擷取不可信，不得判 Absent。
        val repo = DualRepository(FakeGateway(verboseOut = "garbage\n", dumpsysOut = "garbage\n", propOut = ""))
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("empty parse must be Unknown, got: $res", res is DualIdResolution.Unknown)
    }

    @Test
    fun singleSourceEmpty_unknownNotAbsent() {
        // 單源為空即不可信：verbose 有戶無 clone 但 dumpsys 擷取失敗 -> Unknown，不得判 Absent。
        val verboseOnly = DualRepository(
            FakeGateway(verboseOut = NODUAL_VERBOSE, dumpsysOut = "garbage\n", propOut = ""),
        )
        val resVerbose = runSuspend { verboseOnly.resolveDualUserIdTyped() }
        assertTrue("single non-empty source must be Unknown, got: $resVerbose", resVerbose is DualIdResolution.Unknown)
        // 反向：dumpsys 有戶無 clone 但 verbose 擷取失敗 -> 同樣 Unknown。
        val dumpsysOnly = DualRepository(
            FakeGateway(verboseOut = "garbage\n", dumpsysOut = NODUAL_DUMPSYS, propOut = ""),
        )
        val resDumpsys = runSuspend { dumpsysOnly.resolveDualUserIdTyped() }
        assertTrue("single non-empty source must be Unknown, got: $resDumpsys", resDumpsys is DualIdResolution.Unknown)
    }

    @Test
    fun bothNonemptyButTypeMissing_unknownNotAbsent() {
        // P1-1 反例：兩源都有 owner+user95，但 user95 型別行缺失→UNKNOWN；
        // 兩源非空＋無 CLONE 絕不可判 Absent（會清空舊單），一律 Unknown。
        val repo = DualRepository(
            FakeGateway(
                verboseOut = VERBOSE_TYPE_MISSING_95,
                dumpsysOut = DUMPSYS_TYPE_MISSING_95,
                propOut = "",
            ),
        )
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("type-missing on both sources must be Unknown, got: $res", res is DualIdResolution.Unknown)
        assertEquals("legacy Int folds Unknown to -1", -1, runSuspend { repo.resolveDualUserId() })
    }

    @Test
    fun propertyPointsToPossibleClone_withoutTypedClaim_unknownNotAbsent() {
        // P1-1：兩源乾淨無 clone（皆 OTHER、集合一致），但 property=95 指向
        // 仍可能存在的 clone；typed 無對應 Confirmed，一律 Unknown，不得 Absent。
        val repo = DualRepository(
            FakeGateway(
                verboseOut = NODUAL_VERBOSE,
                dumpsysOut = NODUAL_DUMPSYS,
                propOut = "95\n",
            ),
        )
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("positive property without typed clone must be Unknown, got: $res", res is DualIdResolution.Unknown)
    }

    @Test
    fun truncatedVerbose_unknown() {
        // P1-2：截斷 verbose 一律 Unknown（與 canonical 同拒絕）。
        val repo = DualRepository(FakeGateway(verboseTruncated = true))
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("truncated verbose must be Unknown, got: $res", res is DualIdResolution.Unknown)
        assertEquals("canonical must agree (-1)", -1, runSuspend { repo.resolveDualUserId() })
    }

    @Test
    fun truncatedDumpsys_unknown() {
        // P1-2：截斷 dumpsys 一律 Unknown（與 canonical 同拒絕）。
        val repo = DualRepository(FakeGateway(dumpsysTruncated = true))
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("truncated dumpsys must be Unknown, got: $res", res is DualIdResolution.Unknown)
        assertEquals("canonical must agree (-1)", -1, runSuspend { repo.resolveDualUserId() })
    }

    @Test
    fun truncatedProp_unknown() {
        // P1-2：截斷 property 非「無聲明」，一律 Unknown（與 canonical 同拒絕）。
        val repo = DualRepository(FakeGateway(propTruncated = true))
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("truncated prop must be Unknown, got: $res", res is DualIdResolution.Unknown)
        assertEquals("canonical must agree (-1)", -1, runSuspend { repo.resolveDualUserId() })
    }

    @Test
    fun alreadyExistsIdentityBody_unknownLikeCanonical() {
        // P1-2：身分 body 含 INSTALL_FAILED_ALREADY_EXISTS 即使 exit 0，
        // typed 亦須與 canonical 同判（Unknown/拒絕）；isSuccess 的冪等例外
        // 不得放行身分通道。
        val dirtyVerbose = VERBOSE_95 + "\nFailure [INSTALL_FAILED_ALREADY_EXISTS]\n"
        val repo = DualRepository(FakeGateway(verboseOut = dirtyVerbose))
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("ALREADY_EXISTS identity body must be Unknown, got: $res", res is DualIdResolution.Unknown)
        assertEquals("canonical must agree (-1)", -1, runSuspend { repo.resolveDualUserId() })
    }

    companion object {
        const val VERBOSE_95 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"
        const val DUMPSYS_95 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        const val DUMPSYS_96 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{96:DualApp:400} serialNo=16:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        const val NODUAL_VERBOSE =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{10:Work profile:30} running:\n" +
                "    Serial: 11\n" +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.MANAGED\n"
        const val NODUAL_DUMPSYS =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{10:Work profile:30} serialNo=11:\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.MANAGED\n"
        // P1-1 反例：兩源都有 owner+user95，但 user95 缺型別行→UNKNOWN。
        const val VERBOSE_TYPE_MISSING_95 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} running:\n" +
                "    Serial: 15\n" +
                "    parentId=0\n"
        const val DUMPSYS_TYPE_MISSING_95 =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{95:DualApp:400} serialNo=15:\n" +
                "    parentId=0\n"
    }
}
