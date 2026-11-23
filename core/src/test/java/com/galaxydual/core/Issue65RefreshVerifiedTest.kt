// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #65 acceptance AC2/AC4: verify the FULL repository resolver with a
// fake CmdGateway (not just a regex unit). Only the display-only `name=`
// label changes between the two gateways; target identity (id/serial/parent),
// package listing and freshness must be identical, and a label containing an
// error keyword must not turn a legal target into Unknown/ChannelError.
class Issue65RefreshVerifiedTest {

    private class FakeGateway(verbose: String) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        var verboseOutput: String = verbose

        override suspend fun run(cmd: String): CmdResult = when (cmd) {
            DualRepository.CMD_LIST_USERS -> CmdResult(
                exit = 0,
                out = "Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{95:DUAL_APP:400} running\n",
                err = "",
            )
            DualRepository.CMD_USER_LIST_VERBOSE -> CmdResult(exit = 0, out = verboseOutput, err = "")
            DualRepository.CMD_DUMPSYS_USER -> CmdResult(
                exit = 0,
                out = """
                    Users:
                      UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                        userType=android.os.usertype.full.SYSTEM
                      UserInfo{95:DUAL_APP:400} serialNo=15:
                        parentId=0
                        userType=android.os.usertype.profile.CLONE
                """.trimIndent(),
                err = "",
            )
            DualRepository.CMD_GETPROP_DUAL -> CmdResult(exit = 0, out = "95\n", err = "")
            else -> if (cmd.startsWith("pm list packages")) {
                CmdResult(exit = 0, out = "package:com.example.chat\npackage:com.example.mail\n", err = "")
            } else {
                CmdResult(exit = 0, out = "ok", err = "")
            }
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
        return (result ?: error("suspend block never resumed")).getOrThrow()
    }

    private fun verboseWithName(name: String) = """
        Users:
          0: id=0, name=Owner, type=full.SYSTEM, flags=0x00000013 (parentId=0)
          1: id=95, name=$name, type=profile.CLONE, flags=0x00000190 (parentId=0)
          2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=0x00000030 (parentId=0)
    """.trimIndent()

    private fun ground(name: String): RefreshOutcome.GroundTruth {
        val outcome = runSuspend { DualRepository(FakeGateway(verboseWithName(name))).refreshVerified() }
        assertTrue("name=$name must yield GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        return outcome as RefreshOutcome.GroundTruth
    }

    @Test
    fun errorKeywordInNewFormatName_resolvesSameTargetAsCleanName() {
        val clean = ground("DUAL_APP")
        val error = ground("Error")

        assertEquals(95, clean.target.userId)
        assertEquals(95, error.target.userId)
        assertEquals(clean.target.serial, error.target.serial)
        assertEquals(15, error.target.serial)
        assertEquals(clean.target.parentId, error.target.parentId)
        assertEquals(0, error.target.parentId)
        assertEquals(clean.cloned, error.cloned)
        assertEquals(listOf("com.example.chat", "com.example.mail"), error.cloned)
    }

    @Test
    fun notFoundKeywordInNewFormatName_resolvesSameTargetAsCleanName() {
        val clean = ground("DUAL_APP")
        val notFound = ground("not found")

        assertEquals(clean.target, notFound.target)
        assertEquals(clean.cloned, notFound.cloned)
    }
}
