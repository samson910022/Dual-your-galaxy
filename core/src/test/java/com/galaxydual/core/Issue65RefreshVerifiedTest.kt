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
// Issue #65 review hardening: malformed rows / dirty identity channels must
// fail closed with NO `pm list packages` probe for the unconfirmed target,
// and the fake gateway fails fast (instead of silently answering "ok") for
// any command the production resolver is not expected to issue.
class Issue65RefreshVerifiedTest {

    private class FakeGateway(
        verbose: String,
        // Override the whole verbose-channel result (exit code / truncation).
        private val verboseResult: CmdResult? = null,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        var verboseOutput: String = verbose

        // Real issued commands, so "no package read on the unconfirmed
        // target" is an assertion, not an assumption.
        val commands = mutableListOf<String>()

        override suspend fun run(cmd: String): CmdResult {
            commands += cmd
            return when (cmd) {
                DualRepository.CMD_LIST_USERS -> CmdResult(
                    exit = 0,
                    out = "Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{95:DUAL_APP:400} running\n",
                    err = "",
                )
                DualRepository.CMD_USER_LIST_VERBOSE ->
                    verboseResult ?: CmdResult(exit = 0, out = verboseOutput, err = "")
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
                PmOutputParser.buildListPackages(95) -> CmdResult(
                    exit = 0,
                    out = "package:com.example.chat\npackage:com.example.mail\n",
                    err = "",
                )
                else -> error("unexpected cmd: $cmd")
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

    private fun ground(gateway: FakeGateway): RefreshOutcome.GroundTruth {
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("expected GroundTruth, got: $outcome", outcome is RefreshOutcome.GroundTruth)
        return outcome as RefreshOutcome.GroundTruth
    }

    private fun ground(name: String): RefreshOutcome.GroundTruth =
        ground(FakeGateway(verboseWithName(name)))

    // Any unconfirmed identity must end as Unknown BEFORE the package listing
    // runs: a `pm list packages` command here would read packages of a target
    // that was never authorized.
    private fun assertFailClosed(gateway: FakeGateway, reason: String) {
        val outcome = runSuspend { DualRepository(gateway).refreshVerified() }
        assertTrue("$reason must yield Unknown, got: $outcome", outcome is RefreshOutcome.Unknown)
        assertTrue(
            "no pm list packages may be issued for an unconfirmed target ($reason): ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm list packages") },
        )
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

    // P2 — an inline `UserInfo{95:x:400}` label used to create a legacy
    // claim that overwrote the fresh CLONE, producing Unknown / no GroundTruth.
    @Test
    fun inlineUserInfoLabelInName_stillResolvesGroundTruth() {
        val verbose = verboseWithName("UserInfo{95:x:400}")
        val result = ground(FakeGateway(verbose))

        assertEquals(95, result.target.userId)
        assertEquals(15, result.target.serial)
        assertEquals(0, result.target.parentId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), result.cloned)
    }

    // P1b — the real `type=` is empty and the label carries the CLONE token.
    // No GroundTruth, and no package read for the unconfirmed id.
    @Test
    fun emptyRealTypeWithInjectedClone_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("foo").replace(
            "name=foo, type=profile.CLONE,",
            "name=foo, type=profile.CLONE, type=,",
        )
        assertFailClosed(FakeGateway(verbose), "empty real type with injected CLONE")
    }

    // P1a — two real type declarations with a Failure swallowed between them.
    @Test
    fun twoTypeDeclarationsWithSwallowedFailure_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("OK").replace(
            "name=OK, type=profile.CLONE, flags=0x00000190 (parentId=0)",
            "name=OK, type=profile.PRIVATE, flags=Failure [bad] (parentId=10), " +
                "type=profile.CLONE, flags=X (parentId=0)",
        )
        assertFailClosed(FakeGateway(verbose), "two type declarations / swallowed Failure")
    }

    // Security re-review PoCs A/B/G/H at the FULL refreshVerified() level.
    // The row is embedded unchanged in an otherwise healthy verbose snapshot;
    // a label-supplied canonical CLONE declaration must never confirm u95.
    // Joined with explicit \n (NOT a triple-quoted trimIndent()): trimIndent
    // normalizes lone \r into \n, which would erase the very control char PoC H
    // is about. The raw CR must reach the parser unchanged.
    private fun verboseWithRow(row: String) = listOf(
        "Users:",
        "  0: id=0, name=Owner, type=full.SYSTEM, flags=0x00000013 (parentId=0)",
        "  $row",
        "  2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=0x00000030 (parentId=0)",
    ).joinToString("\n")

    @Test
    fun pocA_emptyRealTypeWithSmuggledCanonicalFlags_failsClosedWithoutPackageRead() {
        val row = "1: id=95, name=foo, type=profile.CLONE, flags=X (parentId=0), " +
            "type=, flags=0x190 (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "PoC A: empty real type + smuggled flags")
    }

    @Test
    fun pocB_smuggledCanonicalFlagsWithSpaceBeforeComma_failsClosedWithoutPackageRead() {
        val row = "1: id=95, name=foo, type=profile.CLONE, flags=X, " +
            "type=profile.PRIVATE , flags=0x190 (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "PoC B: space before comma")
    }

    @Test
    fun pocG_parenthesizedSmuggledFlagsWithEmptyRealType_failsClosedWithoutPackageRead() {
        val row = "1: id=95, name=foo, type=profile.CLONE (flags=X) (parentId=0), " +
            "type=, flags=0x190 (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "PoC G: parenthesized smuggled flags")
    }

    @Test
    fun pocH_carriageReturnBeforeSmuggledDeclaration_failsClosedWithoutPackageRead() {
        // Lone \r must stay inside the row (not become a line separator) so
        // the row is malformed instead of yielding a clean CLONE on the first
        // physical segment.
        val row = "1: id=95, name=foo, type=profile.CLONE, flags=X (parentId=0)\r" +
            ", type=, flags=0x190 (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "PoC H: CR before smuggled declaration")
    }

    @Test
    fun genuineBodyFailure_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("DUAL_APP") + "\nFailure [INSTALL_FAILED_INVALID_APK: bad]"
        assertFailClosed(FakeGateway(verbose), "genuine body Failure")
    }

    @Test
    fun errorTokenAfterRealTypeInTail_failsClosedWithoutPackageRead() {
        // The label is neutral; the error token sits AFTER the real type
        // token in the flags tail. Only a name-span-only sanitizer keeps the
        // gate fail-closed here.
        val verbose = """
            Users:
              0: id=0, name=Owner, type=full.SYSTEM, flags=0x00000013 (parentId=0)
              1: id=95, name=OK, type=profile.CLONE, flags=Failure [bad] (parentId=0)
              2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=0x00000030 (parentId=0)
        """.trimIndent()
        assertFailClosed(FakeGateway(verbose), "error token in the flags tail")
    }

    @Test
    fun nonZeroVerboseExit_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("DUAL_APP")
        assertFailClosed(
            FakeGateway(verbose, verboseResult = CmdResult(exit = 1, out = verbose, err = "")),
            "non-zero verbose exit",
        )
    }

    @Test
    fun truncatedVerboseCapture_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("DUAL_APP")
        assertFailClosed(
            FakeGateway(
                verbose,
                verboseResult = CmdResult(exit = 0, out = verbose, err = "", truncated = true),
            ),
            "truncated verbose capture",
        )
    }

    @Test
    fun securityExceptionInTail_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("DUAL_APP").replace(
            "flags=0x00000190 (parentId=0)",
            "flags=0x00000190 (parentId=0) java.lang.SecurityException: denied",
        )
        assertFailClosed(FakeGateway(verbose), "SecurityException in the flags tail")
    }
}
