// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// PR-37 Phase B (P1 #2) regression tests: cloneVerifiedExpected atomic pin.
// The expected (id, serial) is pinned inside a single flow (snapshot +
// resolve + serial-bound compare); any mismatch refuses with TargetMismatch
// and zero installs. Uses a FakeGateway with mutable dualId/serial (no device).
class CloneVerifiedExpectedTest {
    private class FakeGateway(
        var dualId: Int = 95,
        var serial: Int = 15,
        // When true, the verbose/dumpsys blocks keep the CLONE type but carry
        // no serial line (id confirms, serial does not -> SerialUnavailable).
        var omitSerial: Boolean = false,
        var listUsersExit: Int = 0,
        // PR-37 Phase C (review R4 #5) hooks: identity-channel failure modes.
        // verboseExit/dumpsysExit != 0 -> Unknown (command failure);
        // verboseNoisy -> exit-0 plus an error-semantic body line -> Unknown;
        // throwOnIdentity -> run() throws on the verbose channel -> Unknown;
        // absentIdentity -> owner-only blocks on both channels -> Absent.
        var verboseExit: Int = 0,
        var dumpsysExit: Int = 0,
        var verboseNoisy: Boolean = false,
        var throwOnIdentity: Boolean = false,
        var absentIdentity: Boolean = false,
        // 矛盾證據（負向測試專用）：verbose/dumpsys 無 clone（owner-only，
        // 同 absent），但 getprop 與 pm 快照仍指向 dualId（舊 absent fake 的
        // 矛盾狀態）。typed 必須判 Unknown（property/pm 反證），絕不放寬為
        // Absent；production 判定不動。
        var contradictoryEvidence: Boolean = false,
        // Stage2 身份診斷一致性：typed 確認 dualId，但 pm 快照只剩 owner
        //（snapshot/identity mismatch）。clone/remove 皆不得報 NoDual。
        var snapshotMissesDual: Boolean = false,
        var installOut: String = "Package com.example.chat installed for user: 95",
        var installExit: Int = 0,
        var installErr: String = "",
        var installTimedOut: Boolean = false,
        var installTransportUnknown: Boolean = false,
        var throwOnInstall: Boolean = false,
        var throwInstallMessage: String = "boom install",
        var serialAfterWrite: Int? = null,
        var listPackagesOut: String = "package:com.example.chat\n",
        var listPackagesExit: Int = 0,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()

        private fun effectiveSerial(): Int =
            if (commands.count { it.startsWith("pm install-existing") } > 0 && serialAfterWrite != null) {
                serialAfterWrite!!
            } else {
                serial
            }

        private fun verboseOut(): String {
            // absent 與 contradictory 的 typed 通道皆無 clone（owner-only）；
            // 差別在 getprop/pm 快照：absent 為乾淨空，contradictory 仍指向 dual。
            if (absentIdentity || contradictoryEvidence) {
                return "Users:\n" +
                    "  UserInfo{0:Owner:13} running:\n" +
                    "    Serial number: 0\n" +
                    "    User type: android.os.usertype.full.SYSTEM\n"
            }
            // verboseNoisy keeps the confirming blocks but appends an
            // error-semantic body line (exit stays 0): the typed gate must
            // refuse (Unknown), never confirm from noisy evidence.
            return "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                // omitSerial keeps the header + CLONE type but drops the
                // serial line (id confirms, serial does not).
                (if (omitSerial) "" else "    Serial: ${effectiveSerial()}\n") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n" +
                (if (verboseNoisy) "Failure [cannot list users]\n" else "")
        }

        private fun dumpsysOut(): String {
            if (absentIdentity || contradictoryEvidence) {
                return "Users:\n" +
                    "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                    "    userType=android.os.usertype.full.SYSTEM\n"
            }
            // omitSerial keeps the header + CLONE type but drops the serialNo
            // (id confirms, serial does not).
            val header95 = if (omitSerial) "  UserInfo{$dualId:DualApp:400}:\n"
            else "  UserInfo{$dualId:DualApp:400} serialNo=${effectiveSerial()}:\n"
            return "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                header95 +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"
        }

        private fun listUsersOut(): String {
            // absent 為乾淨無 dual（owner-only，無 95）；contradictory 刻意
            // 保留 95 以重現 pm 快照反證（typed 必須 Unknown）。
            // snapshotMissesDual 反向：typed 確認 dual 但 pm 快照只剩 owner
            //（snapshot/identity mismatch，絕非 NoDual）。
            if (absentIdentity || snapshotMissesDual) {
                return "Users:\n" +
                    "\tUserInfo{0:Owner:13} running\n"
            }
            return "Users:\n" +
                "\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DUAL_APP:400} running\n"
        }

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    if (listUsersExit != 0) CmdResult(exit = listUsersExit, out = "", err = "denied")
                    else CmdResult(exit = 0, out = listUsersOut(), err = "")
                cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                    if (throwOnIdentity) throw RuntimeException("boom identity")
                    CmdResult(
                        exit = verboseExit,
                        out = verboseOut(),
                        err = if (verboseExit != 0) "denied" else "",
                    )
                }
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(
                        exit = dumpsysExit,
                        out = dumpsysOut(),
                        err = if (dumpsysExit != 0) "denied" else "",
                    )
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    // absent 為乾淨無聲明（空字串，identity 閘通過＋解析為 null）；
                    // contradictory 刻意仍指向 dualId（property 反證 → Unknown）。
                    if (absentIdentity) CmdResult(exit = 0, out = "", err = "")
                    else CmdResult(exit = 0, out = "$dualId\n", err = "")
                cmd.startsWith("pm install-existing") -> {
                    if (throwOnInstall) throw RuntimeException(throwInstallMessage)
                    CmdResult(
                        exit = installExit,
                        out = installOut,
                        err = installErr,
                        timedOut = installTimedOut,
                        transportUnknown = installTransportUnknown,
                    )
                }
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = listPackagesExit, out = listPackagesOut, err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        fun installRuns(): Int = commands.count { it.startsWith("pm install-existing") }
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
        return (result ?: error("suspend block never resumed")).getOrThrow()
    }

    // Happy path: pinned (id, serial) agrees through the whole flow ->
    // one install on the pinned id, clean post-check -> Success.
    @Test
    fun happyPath_pinnedMatch_installsOnce() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(95, (outcome as CloneOutcome.Success).dualId)
        assertEquals(1, gateway.installRuns())
        assertEquals(
            "pm install-existing --user 95 com.example.chat",
            gateway.commands.single { it.startsWith("pm install-existing") },
        )
    }

    // Id moved (95 pinned, 96 live): TargetMismatch at the id gate,
    // zero installs.
    @Test
    fun idFlip_returnsTargetMismatchWithZeroInstall() {
        val gateway = FakeGateway(dualId = 96, serial = 15)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        val mismatch = outcome as CloneOutcome.TargetMismatch
        assertEquals(95, mismatch.expectedId)
        assertEquals(96, mismatch.actualId)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run on target mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Same id, fresh serial (recreated profile): TargetMismatch at the serial
    // gate, zero installs.
    @Test
    fun serialFlip_returnsTargetMismatchWithZeroInstall() {
        val gateway = FakeGateway(dualId = 95, serial = 41)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        val mismatch = outcome as CloneOutcome.TargetMismatch
        assertEquals(95, mismatch.expectedId)
        assertEquals(95, mismatch.actualId)
        assertEquals(15, mismatch.expectedSerial)
        assertEquals(41, mismatch.actualSerial)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run on serial mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Null expected serial (caller cannot verify): refused without installing.
    @Test
    fun nullExpectedSerial_refusesWithZeroInstall() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, null)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run without a verifiable expected serial, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Live serial unavailable (CLONE type confirmed, no serial line):
    // TargetMismatch with a null actual serial, zero installs.
    @Test
    fun serialUnavailable_returnsTargetMismatchWithZeroInstall() {
        val gateway = FakeGateway(omitSerial = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected TargetMismatch, got: $outcome", outcome is CloneOutcome.TargetMismatch)
        val mismatch = outcome as CloneOutcome.TargetMismatch
        assertEquals(95, mismatch.expectedId)
        assertEquals(null, mismatch.actualSerial)
        assertEquals(0, gateway.installRuns())
        assertTrue(
            "no install may run when the live serial is unavailable, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Channel failure short-circuits before any write.
    @Test
    fun channelError_runsNoWrite() {
        val gateway = FakeGateway(listUsersExit = 1)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError, got: $outcome", outcome is CloneOutcome.ChannelError)
        assertTrue(
            "no install may run on channel error, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Bad package token with a matching pin: InvalidPackage, never executed.
    @Test
    fun invalidPackage_neverExecutes() {
        val gateway = FakeGateway()
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("bad pkg; rm -rf", 95, 15)
        }
        assertTrue("expected InvalidPackage, got: $outcome", outcome is CloneOutcome.InvalidPackage)
        assertTrue(
            "no install may run for an invalid package, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // PR-37 Phase C (review R4 #5): identity channel command failure
    // (verbose exit != 0) is Unknown, not Absent -> ChannelError (never
    // NoDual), zero installs.
    @Test
    fun identityExitFailure_returnsChannelErrorWithZeroInstall() {
        val gateway = FakeGateway(verboseExit = 1)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError, got: $outcome", outcome is CloneOutcome.ChannelError)
        val detail = (outcome as CloneOutcome.ChannelError).detail
        assertTrue(
            "detail must name the unknown identity, got: $detail",
            detail.contains("unknown", ignoreCase = true),
        )
        assertTrue(
            "detail must never claim no dual, got: $detail",
            !detail.contains("no dual", ignoreCase = true),
        )
        assertTrue(
            "no install may run on an unconfirmable identity, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // PR-37 Phase C (review R4 #5): error-semantic output at exit 0 (noisy
    // channel) is Unknown, not Absent -> ChannelError, zero installs.
    @Test
    fun identityNoisyOutput_returnsChannelErrorWithZeroInstall() {
        val gateway = FakeGateway(verboseNoisy = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError, got: $outcome", outcome is CloneOutcome.ChannelError)
        val detail = (outcome as CloneOutcome.ChannelError).detail
        assertTrue(
            "detail must name the unknown identity, got: $detail",
            detail.contains("unknown", ignoreCase = true),
        )
        assertTrue(
            "detail must never claim no dual, got: $detail",
            !detail.contains("no dual", ignoreCase = true),
        )
        assertTrue(
            "no install may run on noisy identity evidence, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // PR-37 Phase C (review R4 #5): throwing identity channel is Unknown ->
    // ChannelError, zero installs.
    @Test
    fun identityThrow_returnsChannelErrorWithZeroInstall() {
        val gateway = FakeGateway(throwOnIdentity = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError, got: $outcome", outcome is CloneOutcome.ChannelError)
        assertTrue(
            "no install may run when the identity channel throws, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // PR-37 Phase C (review R4 #5): genuinely absent dual (clean channels,
    // real user blocks on both sources, no clone claim) stays NoDual --
    // the confirmed-absent basis for the M13 batch short-circuit.
    // Clean absent requires getprop empty AND pm snapshot owner-only (the
    // pmIds guard); see contradictoryEvidence below for the fail-closed arm.
    @Test
    fun absentIdentity_returnsNoDualWithZeroInstall() {
        val gateway = FakeGateway(absentIdentity = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected NoDual, got: $outcome", outcome is CloneOutcome.NoDual)
        assertTrue(
            "no install may run without a dual target, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // PR-37 Stage 1 (gh review #1): contradictory evidence -- typed channels
    // claim no clone yet pm/property still point at 95 (the old fake's mixed
    // state) -- must be Unknown/ChannelError with zero installs, never NoDual.
    // Guards the pmIds + property vetoes against future loosening.
    @Test
    fun contradictoryEvidence_returnsChannelErrorWithZeroInstall() {
        val gateway = FakeGateway(contradictoryEvidence = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError, got: $outcome", outcome is CloneOutcome.ChannelError)
        val detail = (outcome as CloneOutcome.ChannelError).detail
        assertTrue(
            "detail must name the unknown identity, got: $detail",
            detail.contains("unknown", ignoreCase = true),
        )
        assertTrue(
            "detail must never claim no dual, got: $detail",
            !detail.contains("no dual", ignoreCase = true),
        )
        assertTrue(
            "no install may run on contradictory identity evidence, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Stage2 身份診斷一致性：typed 已 Confirmed 95，但 pm 快照只剩 owner
    //（snapshot/identity mismatch）→ 非 NoDual（ChannelError）、零 install、
    // detail 不含 "no dual"。
    @Test
    fun snapshotMissesDual_returnsChannelErrorWithZeroInstall() {
        val gateway = FakeGateway(snapshotMissesDual = true)
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue(
            "expected ChannelError (snapshot/identity mismatch), never NoDual, got: $outcome",
            outcome is CloneOutcome.ChannelError,
        )
        val detail = (outcome as CloneOutcome.ChannelError).detail
        assertTrue(
            "detail must never claim no dual, got: $detail",
            !detail.contains("no dual", ignoreCase = true),
        )
        assertTrue(
            "no install may run on snapshot/identity mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm install-existing") },
        )
    }

    // Stage2 身份診斷一致性（remove 側）：identity 通道 exit!=0（不可判定）
    // → 非 NoDual（ChannelError）、零 uninstall。照 cloneVerifiedExpected＋
    // requestRemove M1/M2 的 typed 閘，legacy -1 不得折為 NoDual。
    @Test
    fun removeIdentityExitFailure_returnsChannelErrorWithZeroUninstall() {
        val gateway = FakeGateway(verboseExit = 1)
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue(
            "expected ChannelError (identity unknown), never NoDual, got: $outcome",
            outcome is RemoveOutcome.ChannelError,
        )
        val detail = (outcome as RemoveOutcome.ChannelError).detail
        assertTrue(
            "detail must name the unknown identity, got: $detail",
            detail.contains("unknown", ignoreCase = true),
        )
        assertTrue(
            "detail must never claim no dual, got: $detail",
            !detail.contains("no dual", ignoreCase = true),
        )
        assertTrue(
            "no uninstall may run on an unconfirmable identity, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Stage2 身份診斷一致性（remove 側）：typed 已 Confirmed 95，但 pm 快照
    // 只剩 owner → 非 NoDual（ChannelError）、零 uninstall。
    @Test
    fun removeSnapshotMissesDual_returnsChannelErrorWithZeroUninstall() {
        val gateway = FakeGateway(snapshotMissesDual = true)
        val outcome = runSuspend {
            DualRepository(gateway).removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue(
            "expected ChannelError (snapshot/identity mismatch), never NoDual, got: $outcome",
            outcome is RemoveOutcome.ChannelError,
        )
        assertTrue(
            "no uninstall may run on snapshot/identity mismatch, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // Stage3-1 (R5 write-side defense): install 分支 gateway 拋出 -> ChannelError
    // (fail closed)，批次側計 unknown+1/STALE，零 crash（永不 InstallFailed）。
    @Test
    fun installThrow_returnsChannelErrorWithSingleAttempt() {
        val gateway = FakeGateway(throwOnInstall = true, throwInstallMessage = "binder died")
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError, got: $outcome", outcome is CloneOutcome.ChannelError)
        val detail = (outcome as CloneOutcome.ChannelError).detail
        assertTrue(
            "detail must carry the channel error, got: $detail",
            detail.contains("channel error") && detail.contains("binder died"),
        )
        assertEquals("throwing install must be attempted once, got: ${gateway.commands}", 1, gateway.installRuns())
        assertTrue(
            "no probe may run after a throwing install, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm list packages") },
        )
    }

    // Stage3-2a (unknown-after-send same-direction): install timedOut 但唯讀
    // probe 確認 Installed -> Success（絕不判 InstallFailed）。
    @Test
    fun installTimedOut_probeInstalled_isSuccess() {
        val gateway = FakeGateway(
            installExit = 1,
            installOut = "",
            installErr = "shizuku: exec timed out after 60000ms",
            installTimedOut = true,
            listPackagesOut = "package:com.example.chat\n",
            listPackagesExit = 0,
        )
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected Success, got: $outcome", outcome is CloneOutcome.Success)
        assertEquals(95, (outcome as CloneOutcome.Success).dualId)
        assertEquals(1, gateway.installRuns())
        assertEquals(1, gateway.commands.count { it.startsWith("pm list packages") })
    }

    // Stage3-2b (unknown-after-send reverse): install timedOut 但唯讀 probe
    // 乾淨缺席 -> VerifyUnknown（絕不判 InstallFailed/VerifyFailed；寫入仍
    // 可能落地，下一輪 refresh 收斂）。
    @Test
    fun installTimedOut_probeAbsent_isVerifyUnknown() {
        val gateway = FakeGateway(
            installExit = 1,
            installOut = "",
            installErr = "shizuku: exec timed out after 60000ms",
            installTimedOut = true,
            listPackagesOut = "",
            listPackagesExit = 0,
        )
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected VerifyUnknown, got: $outcome", outcome is CloneOutcome.VerifyUnknown)
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must name the write timeout, got: \"$detail\"", detail.contains("write reply timed out"))
        assertTrue("detail must explain reverse is unknown, got: \"$detail\"", detail.contains("treated as unknown"))
        assertEquals(1, gateway.installRuns())
        assertEquals(1, gateway.commands.count { it.startsWith("pm list packages") })
    }

    // Stage3-3 (write 後 after-pin drift):install 與 list-packages 之間翻
    // serial (15 -> 41，重建同 id 新 incarnation) -> VerifyUnknown
    // (identity-changed，永不 Success/VerifyFailed），零額外 install。
    @Test
    fun installSuccess_serialFlipsAfterWrite_isVerifyUnknownWithSingleInstall() {
        val gateway = FakeGateway(
            installOut = "Package com.example.chat installed for user: 95",
            installExit = 0,
            // 即使 probe 本會命中，drift 仍優先：VerifyUnknown。
            listPackagesOut = "package:com.example.chat\n",
            listPackagesExit = 0,
            serialAfterWrite = 41,
        )
        val outcome = runSuspend {
            DualRepository(gateway).cloneVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue(
            "expected VerifyUnknown (identity drift), never Success/VerifyFailed, got: $outcome",
            outcome is CloneOutcome.VerifyUnknown,
        )
        val detail = (outcome as CloneOutcome.VerifyUnknown).detail
        assertTrue("detail must carry identity-changed, got: \"$detail\"", detail.contains("identity-changed"))
        assertEquals("drift must not resend the write, got: ${gateway.commands}", 1, gateway.installRuns())
        assertTrue(
            "drift short-circuits before any list probe, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm list packages") },
        )
    }
}
