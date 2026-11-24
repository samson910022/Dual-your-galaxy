// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #65 acceptance AC2/AC4: verify the FULL repository resolver with a
// fake CmdGateway (not just a regex unit). Only the display-only `name=`
// label changes between the two gateways; target identity (id/serial/parent)
// and package listing must be identical, and a label containing an error
// keyword must not turn a legal target into Unknown/ChannelError. A separate
// case re-grounds after the gateway's package output changes, pinning listing
// FRESHNESS (no stale package list carried across refreshes).
// Issue #65 review hardening: malformed rows / dirty identity channels must
// fail closed with NO `pm list packages` probe for the unconfirmed target,
// and the fake gateway fails fast (instead of silently answering "ok") for
// any command the production resolver is not expected to issue.
class Issue65RefreshVerifiedTest {

    // internal so the parameterized #65 acceptance table can reuse the SAME
    // fail-fast recording gateway (no permissive fallback introduced).
    internal class FakeGateway(
        verbose: String,
        // Override the whole verbose-channel result (exit code / truncation).
        private val verboseResult: CmdResult? = null,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        var verboseOutput: String = verbose
        // Mutable so a second ground() can prove the package listing is
        // re-read instead of a stale list surviving across refreshes.
        var packagesOutput: String = "package:com.example.chat\npackage:com.example.mail\n"

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
                    out = packagesOutput,
                    err = "",
                )
                else -> error("unexpected cmd: $cmd")
            }
        }
    }

    companion object {
        // Companion so the parameterized #65 acceptance table can reuse the
        // same blocking bridge (and the same fail-fast FakeGateway above).
        internal fun <T> runSuspend(block: suspend () -> T): T {
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

        // Pin absolute values too: a cross-comparison alone would pass even if
        // BOTH gateways resolved the wrong id/serial/parent or a stale listing.
        assertEquals(clean.target, notFound.target)
        assertEquals(95, notFound.target.userId)
        assertEquals(15, notFound.target.serial)
        assertEquals(0, notFound.target.parentId)
        assertEquals(clean.cloned, notFound.cloned)
        assertEquals(listOf("com.example.chat", "com.example.mail"), notFound.cloned)
    }

    // Listing freshness: ground with [chat, mail], change the gateway's
    // package output, re-ground, and require the NEW list. R7: BOTH refreshes
    // go through ONE DualRepository instance (the ground() helper creates a
    // fresh one per call and so cannot catch a future stale per-instance
    // cache). The exact `pm list packages --user 95` command count == 2 proves
    // the listing read was actually RE-ISSUED on the second ground, not just
    // that the gateway was capable of serving new data. The ground() helpers
    // above are kept unchanged for the other tests.
    @Test
    fun packageListingRefresh_sameRepositoryReGroundTracksChangedPackages() {
        val gateway = FakeGateway(verboseWithName("DUAL_APP"))
        val repository = DualRepository(gateway)

        val first = runSuspend { repository.refreshVerified() }
        assertTrue("expected GroundTruth, got: $first", first is RefreshOutcome.GroundTruth)
        val firstTruth = first as RefreshOutcome.GroundTruth
        assertEquals(95, firstTruth.target.userId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), firstTruth.cloned)

        gateway.packagesOutput = "package:com.example.chat\npackage:com.example.new\n"
        val second = runSuspend { repository.refreshVerified() }
        assertTrue("expected GroundTruth, got: $second", second is RefreshOutcome.GroundTruth)
        val secondTruth = second as RefreshOutcome.GroundTruth
        assertEquals(95, secondTruth.target.userId)
        assertEquals(15, secondTruth.target.serial)
        assertEquals(0, secondTruth.target.parentId)
        assertEquals(listOf("com.example.chat", "com.example.new"), secondTruth.cloned)

        assertEquals(
            "the second ground must re-issue the package listing read on the SAME repository: ${gateway.commands}",
            2,
            gateway.commands.count { it == "pm list packages --user 95" },
        )
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
        // The space before the comma is no longer the deciding reason: the
        // canonical grammar now tolerates it. The earlier declaration fails
        // because it is STRUCTURAL (owns a flags/parent tail), so the later
        // canonical-looking token may not overwrite it.
        val row = "1: id=95, name=foo, type=profile.CLONE, flags=X, " +
            "type=profile.PRIVATE , flags=0x190 (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "PoC B: earlier structural declaration")
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

    // ---- R1: reverse PoC B at the full refreshVerified() level ----

    private fun assertReversePocBWithoutPackageRead(row: String, reason: String) {
        assertFailClosed(FakeGateway(verboseWithRow(row)), reason)
    }

    @Test
    fun r1_reversePocEarlierStructuralDeclarationWithSpace_failsClosedWithoutPackageRead() {
        assertReversePocBWithoutPackageRead(
            "1: id=95, name=OK, type=profile.PRIVATE , flags=X (parentId=10), " +
                "type=profile.CLONE, flags=X (parentId=0)",
            "R1 reverse PoC B: earlier structural declaration (space)",
        )
    }

    @Test
    fun r1_reversePocEarlierStructuralDeclarationWithTab_failsClosedWithoutPackageRead() {
        assertReversePocBWithoutPackageRead(
            "1: id=95, name=OK, type=profile.PRIVATE\t, flags=X (parentId=10), " +
                "type=profile.CLONE, flags=X (parentId=0)",
            "R1 reverse PoC B: earlier structural declaration (tab)",
        )
    }

    @Test
    fun r1_reversePocEarlierParentOnlyDeclaration_failsClosedWithoutPackageRead() {
        assertReversePocBWithoutPackageRead(
            "1: id=95, name=OK, type=profile.PRIVATE (parentId=10), " +
                "type=profile.CLONE, flags=X (parentId=0)",
            "R1 reverse PoC B: earlier parent-only declaration",
        )
    }

    @Test
    fun r1_singleSpacedCanonicalCloneStillResolvesGroundTruth() {
        // Positive grammar guard at the full level: a single declaration with
        // space before its canonical comma must still resolve u95.
        val row = "1: id=95, name=OK, type=profile.CLONE , flags=X (parentId=0)"
        val result = ground(FakeGateway(verboseWithRow(row)))
        assertEquals(95, result.target.userId)
        assertEquals(15, result.target.serial)
        assertEquals(0, result.target.parentId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), result.cloned)
    }

    // ---- R5: raw-line classification at the full refresh level ----

    @Test
    fun r5_tabInUserInfoShapedNameLabel_failsClosedWithoutPackageRead() {
        val row = "1: id=95, name=UserInfo{95:a\tb:400}, type=profile.CLONE, flags=X (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "R5: real tab in UserInfo-shaped name label")
    }

    @Test
    fun r5_headerLikeFailureInFlagsTail_failsClosedWithoutPackageRead() {
        val row = "1: id=95, name=OK, type=profile.CLONE, flags=UserInfo{1:Failure:0} (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "R5: Failure in flags-tail header token")
    }

    @Test
    fun r5_unicodeSeparatorInName_failsClosedWithoutPackageRead() {
        val row = "1: id=95, name=foo\u2028UserInfo{95:x:400}, type=profile.CLONE, flags=X (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "R5: U+2028 in the name")
    }

    @Test
    fun r5_legacyOnlyCrLfFixtureStillResolvesGroundTruth() {
        // Compat: CRLF is handled by the shared LF/CRLF splitter, so a
        // legacy-only verbose snapshot still confirms the dual target.
        val verbose = listOf(
            "Users:",
            "  UserInfo{0:Owner:13} running:",
            "    Serial number: 0",
            "    User type: android.os.usertype.full.SYSTEM",
            "  UserInfo{95:DualApp:400} running:",
            "    Serial: 15",
            "    parentId=0",
            "    User type: android.os.usertype.profile.CLONE",
        ).joinToString("\r\n")
        val result = ground(FakeGateway(verbose))
        assertEquals(95, result.target.userId)
        assertEquals(15, result.target.serial)
        assertEquals(0, result.target.parentId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), result.cloned)
    }

    @Test
    fun r5_unicodeSeparatorOnOtherRows_failsClosedWithoutPackageRead() {
        // u95's own row stays clean; a Unicode separator on owner/other rows
        // must still fail the channel closed (no hidden record separator may
        // split a different row into a fresh legacy/new claim).
        val verbose = listOf(
            "Users:",
            "  0: id=0, name=Owner\u2028UserInfo{95:DUAL_APP:400}, type=full.SYSTEM, flags=X (parentId=0)",
            "  1: id=95, name=DUAL_APP, type=profile.CLONE, flags=0x00000190 (parentId=0)",
            "  2: id=150, name=Secure\u2028Folder, type=profile.PRIVATE, flags=X (parentId=0)",
        ).joinToString("\n")
        assertFailClosed(FakeGateway(verbose), "R5: Unicode separator on owner/other rows")
    }

    // ---- R2: same-id duplicate evidence at the FULL refreshVerified() level ----

    // Extra claim lines are appended AFTER a clean owner/other snapshot. Since
    // R8 every NEW_ROW owns its line and TERMINATES an earlier legacy body, so
    // a legacy block is fenced explicitly (not merely by EOF or a column-0
    // section) and no snapshot row can be borrowed as another user's evidence.
    private fun verboseWithTrailingClaims(claims: List<String>) = (
        listOf(
            "Users:",
            "  0: id=0, name=Owner, type=full.SYSTEM, flags=0x00000013 (parentId=0)",
            "  2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=0x00000030 (parentId=0)",
        ) + claims
    ).joinToString("\n")

    private val r2FreshParent10Serial99 =
        "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=10) serialNo=99"
    private val r2FreshParent0NoSerial =
        "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0)"
    private fun r2LegacyClone(serial: Int = 15) = listOf(
        "  UserInfo{95:DUAL_APP:400} serialNo=$serial:",
        "    parentId=0",
        "    userType=android.os.usertype.profile.CLONE",
    )

    @Test
    fun r2_newThenLegacySameTypeContradiction_failsClosedWithoutPackageRead() {
        val verbose = verboseWithTrailingClaims(listOf(r2FreshParent10Serial99) + r2LegacyClone())
        assertFailClosed(FakeGateway(verbose), "R2: new(parent10,serial99) then legacy(parent0,serial15)")
    }

    @Test
    fun r2_legacyThenNewSameTypeContradiction_failsClosedWithoutPackageRead() {
        val verbose = verboseWithTrailingClaims(r2LegacyClone() + listOf(r2FreshParent10Serial99))
        assertFailClosed(FakeGateway(verbose), "R2: legacy(parent0,serial15) then new(parent10,serial99)")
    }

    @Test
    fun r2_twoNewRowsParentConflict_failsClosedWithoutPackageRead() {
        val parent10 = r2FreshParent10Serial99
        val parent0 = "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=15"
        for (rows in listOf(listOf(parent10, parent0), listOf(parent0, parent10))) {
            assertFailClosed(FakeGateway(verboseWithTrailingClaims(rows)), "R2: two new rows parent 10 vs 0")
        }
    }

    @Test
    fun r2_twoLegacyBlocksSerialConflict_failsClosedWithoutPackageRead() {
        val serial99 = r2LegacyClone(99)
        val serial15 = r2LegacyClone(15)
        for (blocks in listOf(serial99 + serial15, serial15 + serial99)) {
            assertFailClosed(FakeGateway(verboseWithTrailingClaims(blocks)), "R2: two legacy blocks serial 99 vs 15")
        }
    }

    @Test
    fun r2_serialOnlyConflictWithAgreedParent_failsClosedWithoutPackageRead() {
        val serial99 = "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=99"
        val serial15 = "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=15"
        assertFailClosed(
            FakeGateway(verboseWithTrailingClaims(listOf(serial99, serial15))),
            "R2: serial-only conflict with parent 0 on both rows",
        )
    }

    @Test
    fun r2_malformedEvidenceNotOverwritten_failsClosedWithoutPackageRead() {
        // A malformed UNKNOWN/LOW row must stay alongside the valid CLONE row
        // and veto confirmation through the resolver's missing-parent gate.
        val malformed = "1: id=95, name=OK, type=, flags=X (parentId=0)"
        val verbose = verboseWithTrailingClaims(listOf(malformed, r2FreshParent0NoSerial))
        assertFailClosed(FakeGateway(verbose), "R2: malformed evidence not overwritten by valid CLONE")
    }

    @Test
    fun r2_missingSerialIsTolerated_resolvesGroundTruth() {
        // Positive control: fresh CLONE(parent 0, no serial) + legacy
        // CLONE(parent 0, serial 15) agree; dumpsys CLONE(0,15) and prop 95
        // supply the second channel/property. Missing serial is tolerated, not
        // a contradiction -- the same target must still resolve to GroundTruth.
        val verbose = verboseWithTrailingClaims(listOf(r2FreshParent0NoSerial) + r2LegacyClone())
        val result = ground(FakeGateway(verbose))
        assertEquals(95, result.target.userId)
        assertEquals(15, result.target.serial)
        assertEquals(0, result.target.parentId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), result.cloned)
    }

    // ---- R6: composite label / display-name scope at the FULL refresh level ----

    @Test
    fun compositeLabelWithEarlierErrorDeclaration_failsClosedWithoutPackageRead() {
        // The shared row parser selects the LAST canonical declaration (CLONE)
        // and reports malformed=false, but `, type=Error` sits OUTSIDE the
        // neutralized name span (nameEnd = first `, type=`). The strict gate
        // scans that un-neutralized remainder, ERROR_LINE matches "Error", and
        // the whole channel fails closed. This is an INTENTIONAL conservative
        // trade-off, NOT a display-only label (see VerboseUserRow KDoc).
        val row = "1: id=95, name=foo, type=Error, type=profile.CLONE, flags=X (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "composite label with earlier type=Error")
    }

    @Test
    fun overflowIdPrefix_failsClosedWithoutPackageRead() {
        // id= does not fit an Int, so parse() returns null. Since R10a the
        // `N: id=` shape still OWNS the line: it is classified REJECTED and the
        // strict identity gate fails closed on malformedRow (no ERROR_LINE hit
        // is needed). The fail-fast gateway therefore never reaches a package
        // read for the unconfirmed id; the parser likewise emits no CLONE.
        val row = "1: id=99999999999999999999, name=foo, type=profile.CLONE, flags=X (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "overflow id prefix")
    }

    // ---- R8: one classified line owns its own evidence (full flow) ----

    // Legacy u95 CLONE(serial 15) with NO parent of its own; the only
    // `parentId=0` lives in a DIFFERENT user's NEW_ROW. u95 must stay Unknown
    // (missing parent) with no package read, at either indentation/order.
    private val r8LegacyNoParent = listOf(
        "  UserInfo{95:DUAL_APP:400} serialNo=15:",
        "    userType=android.os.usertype.profile.CLONE",
    )

    private fun r8NewRow(name: String, tail: String = "flags=X", indent: String = "  ") =
        indent + "2: id=150, name=$name, type=profile.PRIVATE, $tail"

    private fun r8Verbose(vararg lines: String) = (listOf("Users:") + lines).joinToString("\n")

    @Test
    fun r8_indentedNewRowNameCannotAuthorizeLegacyClone_failsClosedWithoutPackageRead() {
        val verbose = r8Verbose(*(r8LegacyNoParent + listOf(r8NewRow("parentId=0"))).toTypedArray())
        val u95 = SamsungProfileAdapter.parseUserListVerbose(verbose).single { it.userId == 95 }
        assertNull("u95 must not borrow u150's display name as parent", u95.parentId)
        assertEquals(15, u95.serial)
        assertFailClosed(FakeGateway(verbose), "R8: u150 display name borrowed as u95 parent")
    }

    @Test
    fun r8_neutralNameBaseline_failsClosedWithoutPackageRead() {
        val verbose = r8Verbose(*(r8LegacyNoParent + listOf(r8NewRow("Neutral"))).toTypedArray())
        assertFailClosed(FakeGateway(verbose), "R8: u150 neutral name baseline")
    }

    @Test
    fun r8_reverseOrderAndColumn0Row_failsClosedWithoutPackageRead() {
        for (lines in listOf(
            listOf(r8NewRow("parentId=0")) + r8LegacyNoParent,
            r8LegacyNoParent + listOf(r8NewRow("parentId=0", indent = "")),
        )) {
            val verbose = r8Verbose(*lines.toTypedArray())
            val u95 = SamsungProfileAdapter.parseUserListVerbose(verbose).single { it.userId == 95 }
            assertNull("u95 parent must stay absent in both orders/indents", u95.parentId)
            assertFailClosed(FakeGateway(verbose), "R8: reversed / column-0 new row")
        }
    }

    @Test
    fun r8_otherRowTailAndFollowUpLineCannotReattach_failsClosedWithoutPackageRead() {
        for (lines in listOf(
            r8LegacyNoParent + listOf(r8NewRow("Neutral", "flags=X (parentId=0) serialNo=99")),
            r8LegacyNoParent + listOf(r8NewRow("Neutral"), "    parentId=0"),
        )) {
            val verbose = r8Verbose(*lines.toTypedArray())
            val u95 = SamsungProfileAdapter.parseUserListVerbose(verbose).single { it.userId == 95 }
            assertNull("another row's parent must not attach to u95", u95.parentId)
            assertEquals(15, u95.serial)
            assertFailClosed(FakeGateway(verbose), "R8: other row's tail / follow-up parent line")
        }
    }

    @Test
    fun r8_consistentMixedEvidenceStillResolvesGroundTruthInBothOrders() {
        for (claims in listOf(
            listOf(r2FreshParent0NoSerial) + r2LegacyClone(),
            r2LegacyClone() + listOf(r2FreshParent0NoSerial),
        )) {
            val verbose = verboseWithTrailingClaims(claims)
            val result = ground(FakeGateway(verbose))
            assertEquals(95, result.target.userId)
            assertEquals(15, result.target.serial)
            assertEquals(0, result.target.parentId)
            assertEquals(listOf("com.example.chat", "com.example.mail"), result.cloned)
        }
    }

    // ---- R10: malformed attempts / shared grammar at the full flow ----

    @Test
    fun r10a_missingNameDuplicate_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("DUAL_APP") + "\n" +
            "1: id=95, type=profile.CLONE, flags=X (parentId=0)"
        assertFailClosed(FakeGateway(verbose), "R10a: missing name= duplicate row")
    }

    @Test
    fun r10a_overflowIdDuplicate_failsClosedWithoutPackageRead() {
        val verbose = verboseWithName("DUAL_APP") + "\n" +
            "1: id=99999999999999999999, name=foo, type=profile.CLONE, flags=X (parentId=0)"
        assertFailClosed(FakeGateway(verbose), "R10a: overflow id duplicate row")
    }

    @Test
    fun r10b_serialNumberAliasEarlierDeclaration_failsClosedWithoutPackageRead() {
        val row = "1: id=95, name=OK, type=profile.PRIVATE serial number: 99, " +
            "type=profile.CLONE, flags=X (parentId=0)"
        assertFailClosed(FakeGateway(verboseWithRow(row)), "R10b: serial number structural alias")
    }
}
