// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RED regression test for issue #65: the new One UI verbose `cmd user list -v`
// format (`N: id=<id>, name=<label>, type=<token>, flags=... (parentId=0) ...`)
// has NO `UserInfo{...}` header, so a user-renamed profile whose `name=` label
// contains an error keyword (`Error`, `not found`, ...) used to be left
// untouched by the old global header strip and then matched by ERROR_LINE in
// checkStrictLines(). That wrongly failed isIdentityChannelOk()/the verified
// identity gate even though the label is display-only by contract.
//
// The label is display-only in SamsungProfileAdapter too (parseNewVerboseLines
// takes the type/parent evidence from `type=` and the flag tail, never from
// `name=`), so the correct fix is to strip/neutralize ONLY the `name=` segment
// of each new-format verbose line before the strict ERROR_LINE scan -- NOT the
// whole line. Stripping the whole line (or exempting all verbose lines) would
// let a genuine body error (`Failure [...]`, a non-zero exit, etc.) slip
// through. The negative-control tests below pin that and must keep passing.
//
// All rows are synthetic. The display-only name= assertions and the type-
// injection assertion cover the fix (neutralize only the name= span in
// PmOutputParser; take the LAST `, type=` token in SamsungProfileAdapter);
// the negative controls keep both gates fail-closed for genuine body errors
// and for structurally malformed / ambiguous rows.
class Issue65ParserRegressionTest {

    // Realistic synthetic new-ROM verbose output. u95 is the clone and its
    // label is the only error-semantic token on any line; u0/u150 use neutral
    // labels so a future failure can be attributed to the u95 name= segment.
    private val verboseWithErrorName = """
        Users:
          0: id=0, name=Owner, type=full.SYSTEM, flags=0x00000013 (parentId=0)
          1: id=95, name=Error, type=profile.CLONE, flags=0x00000190 (parentId=0)
          2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=0x00000030 (parentId=0)
    """.trimIndent()

    @Test
    fun newVerbose_errorLabelInNameIsDisplayOnly_gatePasses() {
        // name=Error is a display-only label, not a command error: the
        // structured sanitizer neutralizes only the name= span, so ERROR_LINE
        // can no longer match the label and the gate passes.
        assertTrue(
            "new-format name=Error is a display label and must not fail the identity gate",
            PmOutputParser.isIdentityChannelOk(verboseWithErrorName, 0),
        )
    }

    @Test
    fun newVerbose_notFoundLabelInNameIsDisplayOnly_gatePasses() {
        // Same root cause with a multi-word keyword matched by the
        // `not\s+found` pattern in ERROR_LINE (display-only after the fix).
        val verbose = verboseWithErrorName.replace("name=Error", "name=not found")
        assertTrue(
            "new-format name=not found is a display label and must not fail the identity gate",
            PmOutputParser.isIdentityChannelOk(verbose, 0),
        )
    }

    // Regression for the display-name comma/type injection variant: a greedy
    // old-style `name=([^,]*)` capture let a user-renamed label containing
    // `, type=profile.CLONE` put the injected token in the authorization-
    // relevant userType slot while the real `type=full.SYSTEM` was pushed
    // into the ignored tail. The label is display-only: the userType must come
    // from the LAST `, type=` declaration.
    @Test
    fun newVerbose_displayNameWithInjectedType_doesNotChangeUserType() {
        val verbose = """
            2 users:
              0: id=0, name=Owner, type=full.SYSTEM, flags=0x00000013 (parentId=0)
              1: id=95, name=foo, type=profile.CLONE, type=full.SYSTEM, flags=0x00000190 (parentId=0)
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(verbose)
        val injected = parsed.first { it.userId == 95 }
        assertEquals(
            "the real type=full.SYSTEM must win, not the type= smuggled inside name=",
            ProfileUserType.OTHER,
            injected.userType,
        )
    }

    @Test
    fun newVerbose_genuineBodyFailureStillFailsGate() {
        // PASSING CONTROL: a real error line appended to otherwise clean
        // new-format output must still fail. A future fix that strips whole
        // lines or exempts all verbose lines would break this assertion.
        val cleanVerbose = verboseWithErrorName.replace("name=Error", "name=DUAL_APP")
        assertFalse(
            "a genuine body Failure line must still fail the identity gate",
            PmOutputParser.isIdentityChannelOk(
                cleanVerbose + "\nFailure [INSTALL_FAILED_INVALID_APK: bad]",
                0,
            ),
        )
    }

    @Test
    fun newVerbose_errorTokenAfterTypeOnSameLineStillFailsGate() {
        // Regression guard for a "drop the whole verbose line" sanitizer: the
        // error token sits in the flags tail of a valid new-format line, so
        // only neutralizing the name= segment (not deleting the line) keeps
        // the gate fail-closed.
        assertFalse(
            "an error token in the flags tail of a new-format line must still fail the gate",
            PmOutputParser.isIdentityChannelOk(
                "1: id=95, name=OK, type=profile.CLONE, flags=Failure [INSTALL_FAILED_INVALID_APK: bad]",
                0,
            ),
        )
    }

    @Test
    fun newVerbose_errorLabelInNameStillResolvesDualTarget() {
        // PASSING CONTROL (display-only guarantee at the resolver level): the
        // same u95 name=Error verbose channel still resolves to the dual user
        // when the legacy dumpsys channel and the property agree. The resolver
        // path stays correct alongside the strict identity-gate fix.
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{95:DUAL_APP:400} serialNo=15:
                parentId=0
                userType=android.os.usertype.profile.CLONE
        """.trimIndent()
        assertEquals(
            95,
            PmOutputParser.resolveDualUserIdVerified(verboseWithErrorName, dumpsys, propertyId = 95),
        )
    }

    // P1a — the old greedy name segment ran up to the LAST `, type=`, so the
    // genuine `Failure [bad]` and the second (injected) type declaration were
    // swallowed into the label and the strict gate returned TRUE. Both type
    // declarations are followed by `flags=`, i.e. there is no unique real
    // type: the row is malformed and must fail the gate closed.
    @Test
    fun newVerbose_twoTypeDeclarationsWithSwallowedFailure_failsGateClosed() {
        val row = "1: id=95, name=OK, type=profile.PRIVATE, flags=Failure [bad] (parentId=10), " +
            "type=profile.CLONE, flags=X (parentId=0)"
        assertFalse(
            "a swallowed Failure plus two competing type declarations must fail the gate",
            PmOutputParser.isIdentityChannelOk("Users:\n$row", 0),
        )
        // Same ambiguity with NO error token: the structural malformation
        // alone must still fail closed (no token may authorize).
        val noErrorRow = row.replace("Failure [bad]", "OK")
        assertFalse(
            "a malformed row must fail the gate even when ERROR_LINE found nothing",
            PmOutputParser.isIdentityChannelOk("Users:\n$noErrorRow", 0),
        )
        val parsed = SamsungProfileAdapter.parseUserListVerbose(noErrorRow)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed.first().userType)
    }

    // P1b — the real `type=` value is EMPTY and the earlier `, type=profile.CLONE`
    // is only label text. The old "LAST , type=" regex skipped the empty real
    // field and selected the injected CLONE; the structured parser must refuse.
    @Test
    fun newVerbose_emptyRealTypeWithInjectedClone_isUnknown() {
        val row = "1: id=95, name=foo, type=profile.CLONE, type=, flags=0x190 (parentId=0)"
        val parsed = SamsungProfileAdapter.parseUserListVerbose(row)
        val claim = parsed.first { it.userId == 95 }
        assertEquals(ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
        assertNull("no parent/serial may be claimed from a malformed row", claim.parentId)
        assertNull(claim.serial)
        assertFalse(
            "a malformed row must fail the strict identity gate closed",
            PmOutputParser.isIdentityChannelOk(row, 0),
        )
    }

    // Security re-review PoCs A/B/G/H (verified against compiled classes).
    // All four smuggle a CANONICAL `, type=profile.CLONE, flags=` (or
    // ` (flags=)`) declaration into the display label while the real field is
    // empty or non-canonical. The old "ANY canonical-suffix declaration is
    // real" rule made the smuggled one the SOLE match -> gate passed and the
    // parser trusted a label-supplied CLONE. Every one must now fail closed.
    private fun assertSmuggledDeclarationFailsClosed(name: String, row: String) {
        assertFalse(
            "$name must fail the strict identity gate",
            PmOutputParser.isIdentityChannelOk(row, 0),
        )
        assertFalse(
            "$name must fail the pm-list-users gate too",
            PmOutputParser.isListUsersOutputOk(row, 0),
        )
        val claim = SamsungProfileAdapter.parseUserListVerbose(row).first { it.userId == 95 }
        assertEquals("$name must not claim CLONE", ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
        assertNull("$name must not claim a parent", claim.parentId)
        assertNull(claim.serial)
    }

    @Test
    fun newVerbose_pocA_emptyRealTypeWithSmuggledCanonicalFlags_failsClosed() {
        assertSmuggledDeclarationFailsClosed(
            "A",
            "1: id=95, name=foo, type=profile.CLONE, flags=X (parentId=0), " +
                "type=, flags=0x190 (parentId=0)",
        )
    }

    @Test
    fun newVerbose_pocB_smuggledCanonicalFlagsWithSpaceBeforeComma_failsClosed() {
        // The space before the comma is NOT the deciding reason any more:
        // the canonical grammar now tolerates space/tab on BOTH delimiter
        // spellings. This row still fails because an EARLIER declaration owns
        // a structural tail (`, flags=X`), which the structural invariant
        // refuses to let the later canonical-looking token overwrite.
        assertSmuggledDeclarationFailsClosed(
            "B",
            "1: id=95, name=foo, type=profile.CLONE, flags=X, " +
                "type=profile.PRIVATE , flags=0x190 (parentId=0)",
        )
    }

    @Test
    fun newVerbose_pocG_parenthesizedSmuggledFlagsWithEmptyRealType_failsClosed() {
        assertSmuggledDeclarationFailsClosed(
            "G",
            "1: id=95, name=foo, type=profile.CLONE (flags=X) (parentId=0), " +
                "type=, flags=0x190 (parentId=0)",
        )
    }

    @Test
    fun newVerbose_pocH_carriageReturnBeforeSmuggledDeclaration_failsClosed() {
        // A lone \r is NOT a row separator (VerboseUserRow.verboseLines): it
        // stays inside the row, where rule 1 (the LAST `, type=` declaration
        // must have a non-empty token) is violated by `type=`, so the row is
        // malformed and the gate fails closed. The injected CLONE is only
        // label text and is not eligible to authorize (the C0 control check is
        // an additional guard, not the deciding rule for this row).
        assertSmuggledDeclarationFailsClosed(
            "H",
            "1: id=95, name=foo, type=profile.CLONE, flags=X (parentId=0)\r" +
                ", type=, flags=0x190 (parentId=0)",
        )
    }

    // P2 — an inline `UserInfo{95:x:400}` inside a `name=` label used to be
    // found by the unanchored HEADER.findAll, creating a spurious legacy
    // UNKNOWN claim that overwrote the fresh CLONE at merge time.
    @Test
    fun newVerbose_inlineUserInfoHeaderInLabel_doesNotCreateLegacyClaim() {
        val verbose = "1: id=95, name=UserInfo{95:x:400}, type=profile.CLONE, flags=0x190 (parentId=0)"
        val parsed = SamsungProfileAdapter.parseUserListVerbose(verbose)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed.first().userType)

        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{95:DUAL_APP:400} serialNo=15:
                parentId=0
                userType=android.os.usertype.profile.CLONE
        """.trimIndent()
        assertEquals(95, PmOutputParser.resolveDualUserIdVerified(verbose, dumpsys, propertyId = 95))
        assertEquals(
            VerifiedDualTarget(95, 15, 0),
            PmOutputParser.resolveDualTargetVerified(verbose, dumpsys, propertyId = 95),
        )
    }

    // A genuine legacy block for the same id with a DIFFERENT type must not
    // silently overwrite a fresh claim. Issue #87 R2: BOTH claims are now
    // preserved (no merge/last-wins collapse) and the resolver refuses the
    // same-id type contradiction -- fail closed, never a Confirmed target.
    @Test
    fun legacyAndFreshTypeDisagreement_resolvesUnknown() {
        val text = """
            UserInfo{95:DualApp:400} serialNo=15:
              userType=android.os.usertype.full.SYSTEM
            1: id=95, name=DUAL_APP, type=profile.CLONE, flags=0x190 (parentId=0)
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        // R2 contract: one claim per parsed row/block, NOT one merged claim.
        assertEquals(2, parsed.size)
        val types = parsed.filter { it.userId == 95 }.map { it.userType }.toSet()
        assertEquals(setOf(ProfileUserType.OTHER, ProfileUserType.CLONE), types)
        val resolution = SamsungProfileAdapter.resolveDual(parsed, propertyId = 95)
        assertTrue("a same-id type contradiction must not Confirm: $resolution", resolution !is DualResolution.Confirmed)
        assertTrue("a same-id type contradiction must be Refused: $resolution", resolution is DualResolution.Refused)
    }

    // ---- R1: structural invariant (reverse PoC B) ----

    // An EARLIER structurally complete declaration must beat a later
    // canonical-looking one, whether its delimiter carries a space, a tab,
    // or only a parentId tail. Whitespace normalization alone cannot fix this:
    // the ambiguity detector looks for flags/parent/serial in each
    // declaration's own window, not for one literal space spelling.
    private fun assertEarlierStructuralDeclarationRejected(row: String, reason: String) {
        assertFalse(
            "$reason must fail the strict identity gate",
            PmOutputParser.isIdentityChannelOk("Users:\n$row", 0),
        )
        assertFalse(
            "$reason must fail the pm-list-users gate too",
            PmOutputParser.isListUsersOutputOk(row, 0),
        )
        val claim = SamsungProfileAdapter.parseUserListVerbose(row).single { it.userId == 95 }
        assertEquals("$reason must not claim CLONE", ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
        assertNull("$reason must not claim a parent", claim.parentId)
        assertNull(claim.serial)
    }

    @Test
    fun r1_reversePocEarlierStructuralDeclarationWithSpace_failsClosed() {
        assertEarlierStructuralDeclarationRejected(
            "1: id=95, name=OK, type=profile.PRIVATE , flags=X (parentId=10), " +
                "type=profile.CLONE, flags=X (parentId=0)",
            "earlier structural declaration with a space before its comma",
        )
    }

    @Test
    fun r1_reversePocEarlierStructuralDeclarationWithTab_failsClosed() {
        assertEarlierStructuralDeclarationRejected(
            "1: id=95, name=OK, type=profile.PRIVATE\t, flags=X (parentId=10), " +
                "type=profile.CLONE, flags=X (parentId=0)",
            "earlier structural declaration with a tab before its comma",
        )
    }

    @Test
    fun r1_reversePocEarlierParentOnlyDeclaration_failsClosed() {
        assertEarlierStructuralDeclarationRejected(
            "1: id=95, name=OK, type=profile.PRIVATE (parentId=10), " +
                "type=profile.CLONE, flags=X (parentId=0)",
            "earlier parent-only structural declaration",
        )
    }

    @Test
    fun r1_spaceBeforeCanonicalCommaStillParsesAsClone() {
        // Positive grammar guard against over-rejection: the SAME whitespace
        // that makes the earlier declaration detectable must not reject a
        // single, canonical, structural declaration.
        val row = "1: id=95, name=OK, type=profile.CLONE , flags=X (parentId=0)"
        val claim = SamsungProfileAdapter.parseUserListVerbose(row).single { it.userId == 95 }
        assertEquals(ProfileUserType.CLONE, claim.userType)
        assertEquals(0, claim.parentId)
        assertTrue("a lone canonical declaration with a spaced comma stays valid", PmOutputParser.isIdentityChannelOk(row, 0))

        // Raw-vs-sanitized validity consistency: replacing ONLY the raw name
        // span (exactly what the gate's sanitizer does) must not change the
        // shared parser's verdict or the authorized type token.
        val raw = VerboseUserRow.parse(row)!!
        assertFalse(raw.malformed)
        val sanitized = row.substring(0, raw.nameStart) + "<display-name>" + row.substring(raw.nameEnd)
        val after = VerboseUserRow.parse(sanitized)!!
        assertFalse("sanitizing only the name span must not change row validity", after.malformed)
        assertEquals(raw.typeToken, after.typeToken)
    }

    // ---- R5: one raw physical-line classification shared by gate/parser ----

    // The raw line is classified by the SAME object the gate uses, so a raw
    // invalid line (malformed NEW_ROW or REJECTED) must always fail closed.
    private fun assertRawInvalidLineFailsGate(raw: String, reason: String) {
        val physical = VerboseUserRow.verboseLines(raw).single { it.isNotBlank() }
        val classified = IdentityLineClassifier.classify(physical)
        val rawInvalid = when (classified.kind) {
            IdentityLineKind.REJECTED -> true
            IdentityLineKind.NEW_ROW -> classified.row!!.malformed
            else -> false
        }
        assertTrue("$reason must classify raw-invalid", rawInvalid)
        assertFalse("$reason must fail the identity gate", PmOutputParser.isIdentityChannelOk(raw, 0))
    }

    @Test
    fun r5_tabInsideLegacyShapedNameLabel_isMalformedNotLegacyHeader() {
        val row = "1: id=95, name=UserInfo{95:a\tb:400}, type=profile.CLONE, flags=X (parentId=0)"
        val claims = SamsungProfileAdapter.parseUserListVerbose(row)
        assertEquals(1, claims.size)
        val claim = claims.single()
        assertEquals(95, claim.userId)
        assertEquals(ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
        assertNull(claim.parentId)
        assertNull(claim.serial)
        assertRawInvalidLineFailsGate(row, "a real tab in a UserInfo-shaped name label")
    }

    @Test
    fun r5_headerLikeFailureInFlagsTailSurvivesSanitizer() {
        val row = "1: id=95, name=OK, type=profile.CLONE, flags=UserInfo{1:Failure:0} (parentId=0)"
        // The row itself is structurally valid: the gate fails because the
        // header-like token in the TAIL survives (no whole-output strip).
        val raw = VerboseUserRow.parse(row)!!
        assertFalse(raw.malformed)
        val sanitized = row.substring(0, raw.nameStart) + "<display-name>" + row.substring(raw.nameEnd)
        assertFalse(VerboseUserRow.parse(sanitized)!!.malformed)
        assertFalse(
            "Failure inside a flags-tail UserInfo token must not be deleted",
            PmOutputParser.isIdentityChannelOk(row, 0),
        )
        assertFalse(PmOutputParser.isListUsersOutputOk(row, 0))
    }

    @Test
    fun r5_unicodeSeparatorInNameIsRejectedNotLegacyHeader() {
        val row = "1: id=95, name=foo\u2028UserInfo{95:x:400}, type=profile.CLONE, flags=X (parentId=0)"
        val claims = SamsungProfileAdapter.parseUserListVerbose(row)
        assertEquals(1, claims.size)
        val claim = claims.single()
        assertEquals(95, claim.userId)
        assertEquals(ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
        assertNull("a rejected line must not claim a parent", claim.parentId)
        assertNull(claim.serial)
        assertRawInvalidLineFailsGate(row, "U+2028 in the name")
    }

    @Test
    fun r5_legacyOnlyCrLfFixtureStillParsesClone() {
        // Compat: CRLF is handled by the shared LF/CRLF splitter, so legacy
        // headers and their bodies are unchanged.
        val legacy = listOf(
            "Users:",
            "  UserInfo{0:Owner:13} running:",
            "    Serial number: 0",
            "    User type: android.os.usertype.full.SYSTEM",
            "  UserInfo{95:DualApp:400} running:",
            "    Serial: 15",
            "    parentId=0",
            "    User type: android.os.usertype.profile.CLONE",
        ).joinToString("\r\n")
        val clone = SamsungProfileAdapter.parseUserListVerbose(legacy).single { it.userId == 95 }
        assertEquals(ProfileUserType.CLONE, clone.userType)
        assertEquals(15, clone.serial)
        assertEquals(0, clone.parentId)
        assertTrue(PmOutputParser.isIdentityChannelOk(legacy, 0))
    }

    @Test
    fun r5_loneCrInLegacyHeaderLineCannotYieldClone() {
        val verbose = listOf(
            "Users:",
            "  UserInfo{95:DualApp:400} running:\rSerial: 15",
            "    parentId=0",
            "    User type: android.os.usertype.profile.CLONE",
        ).joinToString("\n")
        val claims = SamsungProfileAdapter.parseUserListVerbose(verbose)
        assertEquals(1, claims.size)
        val claim = claims.single()
        assertEquals(95, claim.userId)
        assertEquals(ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
        assertNull(claim.parentId)
        assertNull(claim.serial)
        assertFalse(PmOutputParser.isIdentityChannelOk(verbose, 0))
    }

    @Test
    fun r5_loneCrInLegacyBodyDoesNotContributeType() {
        // A lone CR inside a body line makes that whole physical line
        // REJECTED: it is excluded from the legacy block body, so its
        // `userType=...CLONE` cannot type the preceding header's claim.
        val verbose = listOf(
            "Users:",
            "  UserInfo{95:DualApp:400} running:",
            "    Serial: 15\ruserType=android.os.usertype.profile.CLONE",
            "    parentId=0",
        ).joinToString("\n")
        val claim = SamsungProfileAdapter.parseUserListVerbose(verbose).single { it.userId == 95 }
        assertEquals(ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
        assertFalse(PmOutputParser.isIdentityChannelOk(verbose, 0))
    }

    // ---- R2: same-id legacy/new duplicates must keep ALL evidence ----

    // The exact R2 PoC: a fresh CLONE row (parent 10, serial 99) and a legacy
    // CLONE block (parent 0, serial 15). Both are CLONE, so the OLD merge
    // compared only userType and let the legacy block ERASE the contradictory
    // parent/serial -- the resolver then confirmed u95 on clean dumpsys/prop.
    private val r2FreshCloneParent10Serial99 =
        "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=10) serialNo=99"
    private val r2FreshCloneParent0 =
        "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0)"
    private val r2MalformedRow = "1: id=95, name=OK, type=, flags=X (parentId=0)"

    private fun r2LegacyCloneBlock(serial: Int = 15) = listOf(
        "  UserInfo{95:DUAL_APP:400} serialNo=$serial:",
        "    parentId=0",
        "    userType=android.os.usertype.profile.CLONE",
    )

    // Clean dumpsys CLONE(parent 0, serial 15) + conventional others, so a
    // resolver Confirm would otherwise be possible; only the contradictory
    // verbose claims may veto it.
    private val r2Dumpsys = listOf(
        "Users:",
        "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:",
        "    userType=android.os.usertype.full.SYSTEM",
        "  UserInfo{95:DUAL_APP:400} serialNo=15:",
        "    parentId=0",
        "    userType=android.os.usertype.profile.CLONE",
    ).joinToString("\n")

    private fun r2Resolve(vararg claimLines: String): DualResolution {
        val parsed = SamsungProfileAdapter.parseUserListVerbose(claimLines.joinToString("\n"))
        return SamsungProfileAdapter.resolveDual(
            parsed + SamsungProfileAdapter.parseDumpsysUser(r2Dumpsys),
            propertyId = 95,
        )
    }

    private fun r2ClaimSets(vararg claimLines: String): Pair<Set<Int?>, Set<Int?>> {
        val claims = SamsungProfileAdapter.parseUserListVerbose(claimLines.joinToString("\n"))
            .filter { it.userId == 95 }
        return claims.map { it.parentId }.toSet() to claims.map { it.serial }.toSet()
    }

    @Test
    fun r2_newThenLegacySameTypePreservesContradictoryParentAndSerial() {
        val fresh = r2FreshCloneParent10Serial99
        val legacy = r2LegacyCloneBlock()
        val text = (listOf(fresh) + legacy).joinToString("\n")
        val claims = SamsungProfileAdapter.parseUserListVerbose(text).filter { it.userId == 95 }
        assertEquals("both same-id same-type claims must survive aggregation", 2, claims.size)
        assertEquals(
            setOf(ProfileUserType.CLONE, ProfileUserType.CLONE),
            claims.map { it.userType }.toSet(),
        )
        assertEquals(setOf<Int?>(10, 0), claims.map { it.parentId }.toSet())
        assertEquals(setOf<Int?>(99, 15), claims.map { it.serial }.toSet())
        val resolution = r2Resolve(fresh, *legacy.toTypedArray())
        assertTrue("explicit parent/serial contradiction must refuse: $resolution", resolution is DualResolution.Refused)
        assertFalse(resolution is DualResolution.Confirmed)
    }

    @Test
    fun r2_legacyThenNewSameTypeIsOrderIndependent() {
        val fresh = r2FreshCloneParent10Serial99
        val legacy = r2LegacyCloneBlock()
        // Reverse the two blocks: the claim SETS and the resolution must be
        // identical (no order-dependent last-wins overwrite).
        val forward = r2ClaimSets(fresh, *legacy.toTypedArray())
        val reverse = r2ClaimSets(*legacy.toTypedArray(), fresh)
        assertEquals(forward, reverse)
        assertEquals(setOf<Int?>(10, 0), forward.first)
        assertEquals(setOf<Int?>(99, 15), forward.second)
        val resolution = r2Resolve(*legacy.toTypedArray(), fresh)
        assertTrue("reversed contradictory claims must still refuse: $resolution", resolution is DualResolution.Refused)
        assertFalse(resolution is DualResolution.Confirmed)
    }

    @Test
    fun r2_twoNewRowsWithContradictoryParent_bothOrdersRefuse() {
        val parent10 = r2FreshCloneParent10Serial99
        val parent0 = "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=15"
        for (rows in listOf(listOf(parent10, parent0), listOf(parent0, parent10))) {
            val claims = SamsungProfileAdapter.parseUserListVerbose(rows.joinToString("\n"))
                .filter { it.userId == 95 }
            assertEquals("both new rows for 95 must be retained", 2, claims.size)
            assertEquals(setOf<Int?>(10, 0), claims.map { it.parentId }.toSet())
            val resolution = r2Resolve(*rows.toTypedArray())
            assertTrue("two CLONE rows with parent 10 vs 0 must refuse: $resolution", resolution is DualResolution.Refused)
            assertFalse(resolution is DualResolution.Confirmed)
        }
    }

    @Test
    fun r2_twoLegacyBlocksWithContradictorySerial_bothOrdersRefuse() {
        val serial99 = r2LegacyCloneBlock(serial = 99)
        val serial15 = r2LegacyCloneBlock(serial = 15)
        for (blocks in listOf(serial99 + serial15, serial15 + serial99)) {
            val claims = SamsungProfileAdapter.parseUserListVerbose(blocks.joinToString("\n"))
                .filter { it.userId == 95 }
            assertEquals("both legacy blocks for 95 must be retained", 2, claims.size)
            assertEquals(setOf<Int?>(99, 15), claims.map { it.serial }.toSet())
            val resolution = r2Resolve(*blocks.toTypedArray())
            assertTrue("two CLONE blocks with serial 99 vs 15 must refuse: $resolution", resolution is DualResolution.Refused)
            assertFalse(resolution is DualResolution.Confirmed)
        }
    }

    @Test
    fun r2_serialOnlyConflictWithAgreedParent_refuses() {
        // Parent agrees at 0 on both rows; only the serials contradict.
        val serial99 = "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=99"
        val serial15 = "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=15"
        val claims = SamsungProfileAdapter.parseUserListVerbose(listOf(serial99, serial15).joinToString("\n"))
            .filter { it.userId == 95 }
        assertEquals(2, claims.size)
        assertEquals(setOf<Int?>(0), claims.map { it.parentId }.toSet())
        assertEquals(setOf<Int?>(99, 15), claims.map { it.serial }.toSet())
        val resolution = r2Resolve(serial99, serial15)
        assertTrue("conflicting serials must refuse even when parents agree: $resolution", resolution is DualResolution.Refused)
    }

    @Test
    fun r2_malformedEvidenceIsNotOverwrittenByValidClone() {
        val malformed = r2MalformedRow
        val valid = r2FreshCloneParent0
        val text = listOf(malformed, valid).joinToString("\n")
        val claims = SamsungProfileAdapter.parseUserListVerbose(text).filter { it.userId == 95 }
        assertEquals("malformed and valid claims must both survive", 2, claims.size)
        val malformedClaim = claims.single { it.confidence == IdentityConfidence.LOW }
        assertEquals(ProfileUserType.UNKNOWN, malformedClaim.userType)
        assertNull(malformedClaim.parentId)
        assertNull(malformedClaim.serial)
        assertEquals(ProfileUserType.CLONE, claims.single { it.confidence == IdentityConfidence.MED }.userType)
        // Missing parent on the malformed claim is a distinct outcome from an
        // explicit contradiction: it is Unknown, never Refused/Confirmed.
        val resolution = r2Resolve(malformed, valid)
        assertTrue("a missing (not contradictory) parent must be Unknown: $resolution", resolution is DualResolution.Unknown)
        assertFalse(resolution is DualResolution.Refused)
        assertFalse(resolution is DualResolution.Confirmed)
    }

    @Test
    fun r2_missingSerialIsToleratedNotTreatedAsContradiction() {
        // Positive control: a fresh CLONE with parent 0 and NO serial, a
        // legacy CLONE with parent 0 / serial 15, plus clean dumpsys/prop.
        // The missing serial must be tolerated (the resolver's serial gate),
        // yielding Confirmed(95, 15, 0) -- not a Refused contradiction.
        val fresh = r2FreshCloneParent0
        val legacy = r2LegacyCloneBlock()
        val parsed = SamsungProfileAdapter.parseUserListVerbose((listOf(fresh) + legacy).joinToString("\n"))
        assertEquals(2, parsed.filter { it.userId == 95 }.size)
        assertEquals(setOf<Int?>(0), r2ClaimSets(fresh, *legacy.toTypedArray()).first)
        assertEquals(setOf<Int?>(null, 15), r2ClaimSets(fresh, *legacy.toTypedArray()).second)
        val resolution = r2Resolve(fresh, *legacy.toTypedArray())
        assertEquals(
            DualResolution.Confirmed(95, 15, 0),
            resolution,
        )
    }

    // ---- R9: contradictory/invalid evidence never authorizes a Confirm ----

    // The decisive R9 PoC: the FIRST row carries two different parents in one
    // block. parseOneBlock downgrades it to UNKNOWN but retains parent 0 /
    // serial 15 for diagnosis; the second row is a clean CLONE(0,15). Without
    // the evidenceInvalid flag the resolver sees UNKNOWN(0,15) + CLONE(0,15)
    // on verbose, CLONE(0,15) on dumpsys, agreeing parent/serial and prop 95
    // and would Confirm. The FIRST value must never authorize.
    private val r9ConflictingParentsRow =
        "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) (parentId=10) serialNo=15"
    private val r9GoodCloneRow =
        "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=15"

    private fun r9ResolveTarget(vararg claimLines: String): VerifiedDualTarget? =
        SamsungProfileAdapter.resolveDualTarget(
            SamsungProfileAdapter.parseUserListVerbose(claimLines.joinToString("\n")) +
                SamsungProfileAdapter.parseDumpsysUser(r2Dumpsys),
            propertyId = 95,
        )

    @Test
    fun r9_conflictingParentsInOneRowPlusGoodClone_flagsAndRefuses() {
        val claims = SamsungProfileAdapter.parseUserListVerbose(
            listOf(r9ConflictingParentsRow, r9GoodCloneRow).joinToString("\n"),
        ).filter { it.userId == 95 }
        assertEquals("both same-id claims must survive", 2, claims.size)
        val invalid = claims.single { it.evidenceInvalid }
        assertEquals(ProfileUserType.UNKNOWN, invalid.userType)
        assertEquals(IdentityConfidence.LOW, invalid.confidence)
        assertEquals("first parent retained for diagnosis only", 0, invalid.parentId)
        assertEquals("first serial retained for diagnosis only", 15, invalid.serial)
        val good = claims.single { !it.evidenceInvalid }
        assertEquals(ProfileUserType.CLONE, good.userType)
        assertEquals(0, good.parentId)
        assertEquals(15, good.serial)

        val resolution = r2Resolve(r9ConflictingParentsRow, r9GoodCloneRow)
        assertTrue("contradictory evidence must refuse: $resolution", resolution is DualResolution.Refused)
        assertTrue((resolution as DualResolution.Refused).reason.contains("contradictory evidence"))
        assertNull("no serial-bound target may be built", r9ResolveTarget(r9ConflictingParentsRow, r9GoodCloneRow))
        assertNull(
            "the public verified-target path must also stay null",
            PmOutputParser.resolveDualTargetVerified(
                listOf(r9ConflictingParentsRow, r9GoodCloneRow).joinToString("\n"),
                r2Dumpsys,
                propertyId = 95,
            ),
        )
    }

    @Test
    fun r9_conflictingParentsOrdersAreIndependent() {
        for (rows in listOf(
            listOf(r9ConflictingParentsRow, r9GoodCloneRow),
            listOf(r9GoodCloneRow, r9ConflictingParentsRow),
        )) {
            val parsed = SamsungProfileAdapter.parseUserListVerbose(rows.joinToString("\n"))
                .filter { it.userId == 95 }
            assertEquals(2, parsed.size)
            assertEquals("exactly one flagged claim in either order", 1, parsed.count { it.evidenceInvalid })
            // Only the FIRST value (0) is retained for diagnosis; the
            // conflicting 10 is deliberately dropped from the claim. That is
            // why the flag, not the value comparison, must veto.
            assertEquals(setOf<Int?>(0), parsed.map { it.parentId }.toSet())
            assertEquals(0, parsed.single { it.evidenceInvalid }.parentId)
            val resolution = r2Resolve(*rows.toTypedArray())
            assertTrue("both orders must refuse: $resolution", resolution !is DualResolution.Confirmed)
            assertNull("both orders must yield a null target", r9ResolveTarget(*rows.toTypedArray()))
        }
    }

    @Test
    fun r9_conflictingSerialsInOneRowPlusGoodClone_refuses() {
        val conflictingSerialRow =
            "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serialNo=15 serialNo=99"
        for (rows in listOf(
            listOf(conflictingSerialRow, r9GoodCloneRow),
            listOf(r9GoodCloneRow, conflictingSerialRow),
        )) {
            val claims = SamsungProfileAdapter.parseUserListVerbose(rows.joinToString("\n"))
                .filter { it.userId == 95 }
            assertEquals(2, claims.size)
            val invalid = claims.single { it.evidenceInvalid }
            assertEquals(ProfileUserType.UNKNOWN, invalid.userType)
            assertEquals("first serial retained for diagnosis only", 15, invalid.serial)
            assertEquals(0, invalid.parentId)
            val resolution = r2Resolve(*rows.toTypedArray())
            assertTrue("serial conflict must refuse: $resolution", resolution is DualResolution.Refused)
            assertFalse(resolution is DualResolution.Confirmed)
            assertNull(r9ResolveTarget(*rows.toTypedArray()))
        }
    }

    @Test
    fun r9_conflictingTypeInLegacyBlockPlusGoodClone_refuses() {
        val conflictingType = listOf(
            "  UserInfo{95:DUAL_APP:400} serialNo=15:",
            "    parentId=0",
            "    userType=android.os.usertype.profile.CLONE",
            "    userType=android.os.usertype.profile.PRIVATE",
        )
        for (rows in listOf(
            conflictingType + listOf(r9GoodCloneRow),
            listOf(r9GoodCloneRow) + conflictingType,
        )) {
            val claims = SamsungProfileAdapter.parseUserListVerbose(rows.joinToString("\n"))
                .filter { it.userId == 95 }
            assertEquals(2, claims.size)
            val invalid = claims.single { it.evidenceInvalid }
            assertEquals("ambiguous type must be UNKNOWN", ProfileUserType.UNKNOWN, invalid.userType)
            assertEquals(0, invalid.parentId)
            assertEquals(15, invalid.serial)
            val resolution = r2Resolve(*rows.toTypedArray())
            assertTrue("type conflict must refuse: $resolution", resolution !is DualResolution.Confirmed)
            assertFalse(resolution is DualResolution.Confirmed)
            assertNull(r9ResolveTarget(*rows.toTypedArray()))
        }
    }

    @Test
    fun r9_unknownClaimInEitherChannelVetoesConfirmed() {
        // verbose UNKNOWN (a legacy block with parent/serial but no type line)
        // alongside a clean CLONE row: the UNKNOWN carries plausible values
        // and must still veto.
        val unknownVerbose = listOf(
            "  UserInfo{95:DUAL_APP:400} serialNo=15:",
            "    parentId=0",
        )
        for (rows in listOf(
            r9GoodCloneRow + "\n" + unknownVerbose.joinToString("\n"),
            unknownVerbose.joinToString("\n") + "\n" + r9GoodCloneRow,
        )) {
            val resolution = r2Resolve(rows)
            assertTrue("UNKNOWN verbose must veto: $resolution", resolution !is DualResolution.Confirmed)
            assertNull(r9ResolveTarget(rows))
        }

        // dumpsys UNKNOWN on the target id vetoes a clean verbose CLONE.
        val unknownDumpsys = listOf(
            "Users:",
            "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:",
            "    userType=android.os.usertype.full.SYSTEM",
            "  UserInfo{95:DUAL_APP:400} serialNo=15:",
            "    parentId=0",
        ).joinToString("\n")
        val candidates = SamsungProfileAdapter.parseUserListVerbose(r9GoodCloneRow) +
            SamsungProfileAdapter.parseDumpsysUser(unknownDumpsys)
        val dumpsysResolution = SamsungProfileAdapter.resolveDual(candidates, propertyId = 95)
        assertTrue("UNKNOWN dumpsys must veto: $dumpsysResolution", dumpsysResolution !is DualResolution.Confirmed)
        assertNull(
            "the public verified-target path must be null with UNKNOWN dumpsys",
            PmOutputParser.resolveDualTargetVerified(r9GoodCloneRow, unknownDumpsys, propertyId = 95),
        )
    }

    // ---- R6: composite label / displayName scope ----

    @Test
    fun r6_compositeLabelWithEarlierNonCanonicalErrorDeclaration_gatesFailClosed() {
        // The row parser selects the LAST canonical declaration and considers
        // the row structurally valid, but `, type=Error` lies OUTSIDE the
        // neutralized name span (nameEnd = first `, type=`). Both strict gates
        // scan that un-neutralized remainder and reject on the Error keyword.
        // This is an intentional conservative trade-off, NOT a display-only
        // label (see VerboseUserRow KDoc).
        val row = "1: id=95, name=foo, type=Error, type=profile.CLONE, flags=X (parentId=0)"
        val parsed = VerboseUserRow.parse(row)!!
        assertEquals("foo", parsed.displayName)
        assertEquals("profile.CLONE", parsed.typeToken)
        assertFalse(parsed.malformed)
        assertFalse(
            "earlier type=Error survives outside the name span and must fail the strict identity gate",
            PmOutputParser.isIdentityChannelOk(row, 0),
        )
        assertFalse(
            "the pm-list-users strict gate must reject identically",
            PmOutputParser.isListUsersOutputOk(row, 0),
        )
    }

    @Test
    fun r6_flagsLikeTextBeforeRealTypeIsPartOfDisplayNameAndGatesPass() {
        // nameEnd stops at the FIRST `, type=`, so `, flags=X` before the real
        // declaration stays inside the neutralized name span. displayName is
        // ONLY that prefix (NOT the full raw name), the real CLONE token wins,
        // and no error token remains -> both strict gates pass.
        val row = "1: id=95, name=foo, flags=X, type=profile.CLONE, flags=0x190 (parentId=0)"
        val parsed = VerboseUserRow.parse(row)!!
        assertEquals("foo, flags=X", parsed.displayName)
        assertEquals("profile.CLONE", parsed.typeToken)
        assertFalse(parsed.malformed)
        assertTrue(
            "flags-like text inside the name span is display-only; identity gate must pass",
            PmOutputParser.isIdentityChannelOk(row, 0),
        )
        assertTrue(
            "flags-like text inside the name span is display-only; pm-list-users gate must pass",
            PmOutputParser.isListUsersOutputOk(row, 0),
        )
    }

    @Test
    fun r6_overflowIdPrefixIsNotParsableAndClaimsNoUser95() {
        // id= overflows Int: the shared parser returns null. R10a additionally
        // claims the `N: id=` shape as REJECTED so the strict gate fails closed
        // instead of silently ignoring the row; no u95 claim can be made.
        val row = "1: id=99999999999999999999, name=foo, type=profile.CLONE, flags=X (parentId=0)"
        assertNull(VerboseUserRow.parse(row))
        val claims = SamsungProfileAdapter.parseUserListVerbose(row)
        assertTrue("overflow id must not produce a u95 claim: $claims", claims.none { it.userId == 95 })
    }

    // ---- R8: one classified line owns its own evidence ----

    // Legacy u95 CLONE(serial 15) with NO parent of its own; the only
    // `parentId=0` lives in a DIFFERENT user's NEW_ROW (display name, real
    // tail, or a following indented line). None of it may authorize u95.
    private val r8LegacyNoParent = listOf(
        "  UserInfo{95:DUAL_APP:400} serialNo=15:",
        "    userType=android.os.usertype.profile.CLONE",
    )

    private fun r8NewRow(name: String, tail: String = "flags=X", indent: String = "  ") =
        indent + "2: id=150, name=$name, type=profile.PRIVATE, $tail"

    private fun r8Claim(vararg lines: String): ProfileIdentity =
        SamsungProfileAdapter.parseUserListVerbose(lines.joinToString("\n"))
            .single { it.userId == 95 }

    @Test
    fun r8_indentedNewRowDisplayNameCannotFillMissingLegacyParent() {
        val borrowed = r8Claim(*(r8LegacyNoParent + listOf(r8NewRow("parentId=0"))).toTypedArray())
        assertEquals(ProfileUserType.CLONE, borrowed.userType)
        assertNull("u95 must not borrow u150's display name as parent evidence", borrowed.parentId)
        assertEquals(15, borrowed.serial)

        val baseline = r8Claim(*(r8LegacyNoParent + listOf(r8NewRow("Neutral"))).toTypedArray())
        assertEquals(ProfileUserType.CLONE, baseline.userType)
        assertNull(baseline.parentId)
        assertEquals(15, baseline.serial)
    }

    @Test
    fun r8_newRowBeforeOrAfterLegacyHeader_isOrderIndependent() {
        for (lines in listOf(
            r8LegacyNoParent + listOf(r8NewRow("parentId=0")),
            listOf(r8NewRow("parentId=0")) + r8LegacyNoParent,
        )) {
            val claim = r8Claim(*lines.toTypedArray())
            assertNull("u95 parent must stay absent in both orders", claim.parentId)
            assertEquals(15, claim.serial)
        }
    }

    @Test
    fun r8_column0NewRowCannotFillMissingLegacyParent() {
        val claim = r8Claim(*(r8LegacyNoParent + listOf(r8NewRow("parentId=0", indent = ""))).toTypedArray())
        assertNull(claim.parentId)
    }

    @Test
    fun r8_anotherRowRealTailCannotPolluteLegacyClaim() {
        val row = r8NewRow("Neutral", tail = "flags=X (parentId=0) serialNo=99")
        val claims = SamsungProfileAdapter.parseUserListVerbose(
            (r8LegacyNoParent + listOf(row)).joinToString("\n"),
        )
        val u95 = claims.single { it.userId == 95 }
        assertNull("u150's real parent must not become u95's", u95.parentId)
        assertEquals("u150's serial must not become u95's", 15, u95.serial)
        val u150 = claims.single { it.userId == 150 }
        assertEquals(0, u150.parentId)
        assertEquals(99, u150.serial)
    }

    @Test
    fun r8_followUpParentLineAfterNewRowDoesNotReattach() {
        val text = (
            r8LegacyNoParent + listOf(r8NewRow("Neutral"), "    parentId=0")
            ).joinToString("\n")
        val claim = SamsungProfileAdapter.parseUserListVerbose(text).single { it.userId == 95 }
        assertNull("an indented line after a NEW_ROW belongs to nobody", claim.parentId)
    }

    @Test
    fun r8_parseDumpsysUserDoesNotAbsorbAnotherRowsNewRow() {
        val dumpsys = (listOf("Users:") + r8LegacyNoParent + listOf(r8NewRow("parentId=0")))
            .joinToString("\n")
        val claims = SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        val u95 = claims.single { it.userId == 95 }
        assertNull("dumpsys must not borrow the new row's parent", u95.parentId)
        assertEquals(15, u95.serial)
        assertTrue("dumpsys parsing must not emit a claim for the new row's user", claims.none { it.userId == 150 })
    }

    // ---- R10a: row-like attempts are OWNED and fail closed ----

    private val r10CleanVerbose = listOf(
        "Users:",
        "  0: id=0, name=Owner, type=full.SYSTEM, flags=X (parentId=0)",
        "  1: id=95, name=DUAL_APP, type=profile.CLONE, flags=X (parentId=0)",
        "  2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=X (parentId=0)",
    ).joinToString("\n")

    @Test
    fun r10a_missingNameRowLikeAttempt_isRejectedAndNeverClones() {
        val duplicate = "1: id=95, type=profile.CLONE, flags=X (parentId=0)"
        assertEquals(IdentityLineKind.REJECTED, IdentityLineClassifier.classify(duplicate).kind)
        assertNull(VerboseUserRow.parse(duplicate))

        val alone = SamsungProfileAdapter.parseUserListVerbose(duplicate).single()
        assertEquals(95, alone.userId)
        assertEquals(ProfileUserType.UNKNOWN, alone.userType)
        assertEquals(IdentityConfidence.LOW, alone.confidence)
        assertNull(alone.parentId)

        val snapshot = r10CleanVerbose + "\n" + duplicate
        assertEquals(
            "the malformed duplicate must not add a CLONE claim for u95",
            1,
            SamsungProfileAdapter.parseUserListVerbose(snapshot)
                .count { it.userId == 95 && it.userType == ProfileUserType.CLONE },
        )
        assertFalse(PmOutputParser.isIdentityChannelOk(snapshot, 0))
        assertFalse(PmOutputParser.isListUsersOutputOk(snapshot, 0))
    }

    @Test
    fun r10a_overflowAndNonNumericIdRowLikeAttemptsAreRejected() {
        val duplicates = listOf(
            "1: id=99999999999999999999, name=foo, type=profile.CLONE, flags=X (parentId=0)",
            "1: id=abc, name=foo, type=profile.CLONE, flags=X (parentId=0)",
        )
        for (duplicate in duplicates) {
            assertEquals("$duplicate must be REJECTED", IdentityLineKind.REJECTED, IdentityLineClassifier.classify(duplicate).kind)
            val snapshot = r10CleanVerbose + "\n" + duplicate
            assertEquals(1, SamsungProfileAdapter.parseUserListVerbose(snapshot).count { it.userId == 95 && it.userType == ProfileUserType.CLONE })
            assertFalse(PmOutputParser.isIdentityChannelOk(snapshot, 0))
            assertFalse(PmOutputParser.isListUsersOutputOk(snapshot, 0))
        }
    }

    @Test
    fun r10a_sectionHeadersProseAndCrLfStayCompatible() {
        for (line in listOf("Users:", "User types (12 types):", "  Device properties:", "just prose", "")) {
            assertFalse("$line must not look row-like", VerboseUserRow.ROW_LIKE.containsMatchIn(line))
            assertEquals(IdentityLineKind.OTHER, IdentityLineClassifier.classify(line).kind)
        }
        assertTrue(PmOutputParser.isIdentityChannelOk(r10CleanVerbose, 0))
        assertTrue(PmOutputParser.isIdentityChannelOk(r10CleanVerbose.replace("\n", "\r\n"), 0))
        assertEquals(1, SamsungProfileAdapter.parseUserListVerbose(r10CleanVerbose).count { it.userId == 95 && it.userType == ProfileUserType.CLONE })
    }

    @Test
    fun r10a_rejectedRowIsNotStitchedIntoLegacyBody() {
        val text = listOf(
            "  UserInfo{95:DUAL_APP:400} serialNo=15:",
            "    userType=android.os.usertype.profile.CLONE",
            "  1: id=95, type=profile.CLONE, flags=X (parentId=0)",
        ).joinToString("\n")
        val claims = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(2, claims.size)
        val legacy = claims.single { it.confidence == IdentityConfidence.MED }
        assertNull("the REJECTED row's parentId must not authorize u95", legacy.parentId)
        assertEquals(15, legacy.serial)
        val rejected = claims.single { it.confidence == IdentityConfidence.LOW }
        assertEquals(ProfileUserType.UNKNOWN, rejected.userType)
        assertNull(rejected.parentId)
        assertFalse(PmOutputParser.isIdentityChannelOk(text, 0))
    }

    // ---- R10b: one shared parent/serial grammar ----

    @Test
    fun r10b_serialNumberAliasInEarlierDeclarationFailsClosed() {
        val row = "1: id=95, name=OK, type=profile.PRIVATE serial number: 99, " +
            "type=profile.CLONE, flags=X (parentId=0)"
        assertTrue(VerboseUserRow.parse(row)!!.malformed)
        assertFalse(PmOutputParser.isIdentityChannelOk(row, 0))
        assertFalse(PmOutputParser.isListUsersOutputOk(row, 0))
        val claim = SamsungProfileAdapter.parseUserListVerbose(row).single()
        assertEquals(ProfileUserType.UNKNOWN, claim.userType)
        assertNull(claim.parentId)
        assertNull(claim.serial)
    }

    @Test
    fun r10b_allSharedSerialAndParentSpellingsAreStructural() {
        val tails = listOf(
            "serial number: 99", "serialnumber=99", "Serial Number : 99",
            "serialNo=99", "serial: 99", "parentId=10", "parent: 10",
        )
        for (tail in tails) {
            val row = "1: id=95, name=OK, type=profile.PRIVATE $tail, " +
                "type=profile.CLONE, flags=X (parentId=0)"
            assertTrue("$tail must be detected as a structural tail", VerboseUserRow.parse(row)!!.malformed)
            assertFalse("$tail must fail the gate", PmOutputParser.isIdentityChannelOk(row, 0))
            val claim = SamsungProfileAdapter.parseUserListVerbose(row).single()
            assertEquals(ProfileUserType.UNKNOWN, claim.userType)
            assertNull(claim.parentId)
            assertNull(claim.serial)
        }
    }

    @Test
    fun r10b_structuralAliasOrderSwapsStayMalformed() {
        val rows = listOf(
            "1: id=95, name=OK, type=profile.PRIVATE serial number: 99, type=profile.CLONE, flags=X (parentId=0)",
            "1: id=95, name=OK, type=profile.CLONE serial number: 99, type=profile.PRIVATE, flags=X (parentId=0)",
            "1: id=95, name=OK, type=profile.PRIVATE parentId=10, type=profile.CLONE, flags=X (parentId=0)",
            "1: id=95, name=OK, type=profile.CLONE parentId=10, type=profile.PRIVATE, flags=X (parentId=0)",
        )
        for (row in rows) {
            assertTrue("both orders must fail closed: $row", VerboseUserRow.parse(row)!!.malformed)
            assertFalse("both orders must fail the gate: $row", PmOutputParser.isIdentityChannelOk(row, 0))
        }
    }

    @Test
    fun r10b_legitimateSerialNumberInCanonicalTailParsesAsClone() {
        val row = "1: id=95, name=OK, type=profile.CLONE, flags=X (parentId=0) serial number: 15"
        assertFalse(VerboseUserRow.parse(row)!!.malformed)
        val claim = SamsungProfileAdapter.parseUserListVerbose(row).single()
        assertEquals(ProfileUserType.CLONE, claim.userType)
        assertEquals(15, claim.serial)
        assertEquals(0, claim.parentId)
        assertTrue(PmOutputParser.isIdentityChannelOk(row, 0))
    }

    @Test
    fun r10b_sharedFieldGrammarCoversEverySpellingAndBoundary() {
        for (text in listOf(
            "serial=99", "serialNo=99", "serialnumber=99", "serial number: 99",
            "Serial Number : 99", "serial: 99",
        )) {
            assertTrue("$text serial head", VerboseIdentityFields.SERIAL_HEAD.containsMatchIn("x $text"))
            assertTrue("$text serial value", VerboseIdentityFields.serialWithValue().containsMatchIn("x $text"))
        }
        for (text in listOf("parent=0", "parentId=0", "parent: 0", "parentId: -10000")) {
            assertTrue("$text parent head", VerboseIdentityFields.PARENT_HEAD.containsMatchIn("x $text"))
            assertTrue("$text parent value", VerboseIdentityFields.parentWithValue().containsMatchIn("x $text"))
        }
        for (text in listOf(", flags=X", " (flags=X", ",FLAGS = X")) {
            assertTrue("$text flags head", VerboseIdentityFields.FLAGS_HEAD.containsMatchIn("type=profile.CLONE$text"))
        }
        for (text in listOf("personalSerialNo=7", "mySerial=8", "grandparentId=3")) {
            assertFalse("$text must not match serial head", VerboseIdentityFields.SERIAL_HEAD.containsMatchIn(text))
            assertFalse("$text must not match serial value", VerboseIdentityFields.serialWithValue().containsMatchIn(text))
            assertFalse("$text must not match parent head", VerboseIdentityFields.PARENT_HEAD.containsMatchIn(text))
            assertFalse("$text must not match parent value", VerboseIdentityFields.parentWithValue().containsMatchIn(text))
        }
        assertEquals("99", VerboseIdentityFields.serialWithValue().find("serial number: 99")!!.groupValues[1])
        assertEquals("-10000", VerboseIdentityFields.parentWithValue().find("parentId: -10000")!!.groupValues[1])

        // R14: the value-bearing regex captures the WHOLE candidate token;
        // strict full-token parsing then decides valid vs invalid. A malformed
        // value is never truncated into a plausible decimal prefix.
        for (raw in listOf("0x10", "15oops", "2147483648", "+15", "", "15:400", "15::")) {
            assertNull("parent value '$raw' must not parse", VerboseIdentityFields.parseParentValue(raw))
            assertNull("serial value '$raw' must not parse", VerboseIdentityFields.parseSerialValue(raw))
        }
        assertNull("negative serial must not parse", VerboseIdentityFields.parseSerialValue("-15"))
        assertEquals(-10000, VerboseIdentityFields.parseParentValue("-10000"))
        assertEquals(0, VerboseIdentityFields.parseParentValue("0"))
        assertEquals(15, VerboseIdentityFields.parseParentValue("15:"))
        assertEquals(15, VerboseIdentityFields.parseSerialValue("15"))
        // Legacy `serialNo=15:` keeps its single optional trailing colon.
        assertEquals(15, VerboseIdentityFields.parseSerialValue("15:"))
    }

    // ---- R12: malformed record attempts are OWNED, never borrowed ----

    // Parent-less legacy u95 CLONE(serial 15). Any following malformed record
    // attempt must be REJECTED: its raw text is excluded from the legacy body,
    // so a `parentId=0` inside it cannot authorize u95.
    private val r12LegacyNoParent = listOf(
        "  UserInfo{95:DUAL_APP:400} serialNo=15:",
        "    userType=android.os.usertype.profile.CLONE",
    )

    private fun assertMalformedAttemptFailsClosed(attempt: String, reason: String) {
        assertEquals("$reason must be REJECTED", IdentityLineKind.REJECTED, IdentityLineClassifier.classify(attempt).kind)
        val text = (r12LegacyNoParent + listOf(attempt)).joinToString("\n")
        val u95 = SamsungProfileAdapter.parseUserListVerbose(text).single { it.userId == 95 }
        assertNull("$reason must not donate a parent to u95", u95.parentId)
        assertEquals("$reason must not change u95's serial", 15, u95.serial)
        assertFalse("$reason must fail the identity gate", PmOutputParser.isIdentityChannelOk(text, 0))
        assertFalse("$reason must fail the pm-list-users gate", PmOutputParser.isListUsersOutputOk(text, 0))
    }

    @Test
    fun r12_malformedLegacyAttemptsAreRejectedAndNeverBorrowParent() {
        for (attempt in listOf(
            "  UserInfo{150:parentId=0}", // review PoC: no flags delimiter
            "  UserInfo{150:Neutral}", // missing flags
            "  UserInfo{150:Broken:400", // unclosed header
            "  UserInfo{foo Failure}", // bad id head
            "  UserInfo{99999999999999999999:parentId=0}", // id overflow
            "  UserInfo{150:Work:{parentId=0:400}", // inner brace
            "  UserInfo{150:Work:parentId=0:400}}", // brace after close
            "  UserInfo{150:Work:parentId=0:400}{extra}",
        )) {
            assertMalformedAttemptFailsClosed(attempt, attempt)
        }
    }

    @Test
    fun r12_spacedIdEqualsAttemptsAreRejectedAndNeverBorrowParent() {
        for (attempt in listOf(
            "  2: id =150, name=parentId=0, type=profile.PRIVATE, flags=X",
            "2: id\t=150, name=parentId=0, type=profile.PRIVATE, flags=X",
            " 2: id  = 150, name=parentId=0, type=profile.PRIVATE, flags=X",
            "  2: id =99999999999999999999, name=parentId=0, type=profile.PRIVATE, flags=X",
        )) {
            assertNull("spaced id= is not the strict authorization row", VerboseUserRow.parse(attempt))
            assertMalformedAttemptFailsClosed(attempt, attempt)
        }
    }

    @Test
    fun r12_sectionHeadersProseAndInlineHeaderStayOtherAndClean() {
        for (line in listOf(
            "Users:", "User types (12 types):", "  Device properties:", "just prose", "",
            "see UserInfo{95:x:400} above",
        )) {
            assertEquals("$line must be OTHER", IdentityLineKind.OTHER, IdentityLineClassifier.classify(line).kind)
        }
        val inline = "1: id=95, name=UserInfo{95:x:400}, type=profile.CLONE, flags=X (parentId=0)"
        assertEquals(IdentityLineKind.NEW_ROW, IdentityLineClassifier.classify(inline).kind)
        assertTrue("clean inline-label snapshot must pass", PmOutputParser.isIdentityChannelOk(r10CleanVerbose, 0))
        assertTrue("clean inline-label snapshot must pass pm gate", PmOutputParser.isListUsersOutputOk(r10CleanVerbose, 0))
    }

    @Test
    fun r12_validExtraLegacyHeaderOwnsItsOwnEvidence() {
        val text = listOf(
            "  UserInfo{95:DUAL_APP:400} serialNo=15:",
            "    userType=android.os.usertype.profile.CLONE",
            "  UserInfo{150:Neutral:30} serialNo=99:",
            "    parentId=0",
            "    userType=android.os.usertype.profile.PRIVATE",
        ).joinToString("\n")
        val claims = SamsungProfileAdapter.parseUserListVerbose(text)
        val u95 = claims.single { it.userId == 95 }
        assertNull("the later header's parent belongs to u150 only", u95.parentId)
        assertEquals(15, u95.serial)
        val u150 = claims.single { it.userId == 150 }
        assertEquals(0, u150.parentId)
        assertEquals(99, u150.serial)
        assertEquals(ProfileUserType.OTHER, u150.userType)
        assertTrue("two well-formed headers keep the gate clean", PmOutputParser.isIdentityChannelOk(text, 0))
    }

    @Test
    fun r12_rejectedLegacyAttemptClaimIsBareUnknownLow() {
        for (attempt in listOf("  UserInfo{150:parentId=0}", "  2: id =150, name=parentId=0")) {
            val claim = SamsungProfileAdapter.parseUserListVerbose(attempt).single()
            assertEquals(150, claim.userId)
            assertEquals(ProfileUserType.UNKNOWN, claim.userType)
            assertEquals(IdentityConfidence.LOW, claim.confidence)
            assertNull("a REJECTED attempt may never claim a parent", claim.parentId)
            assertNull("a REJECTED attempt may never claim a serial", claim.serial)
            assertEquals("", claim.label)
            assertFalse(claim.evidenceInvalid)
        }
    }

    // ---- R13: colon-bearing legacy labels are display-only ----

    private fun r13LegacySnapshot(label: String, flags: String) = listOf(
        "Users:",
        "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:",
        "    userType=android.os.usertype.full.SYSTEM",
        "  UserInfo{95:$label:$flags} serialNo=15:",
        "    parentId=0",
        "    userType=android.os.usertype.profile.CLONE",
    ).joinToString("\n")

    @Test
    fun r13_colonLabelsWithRomanAndHexFlagsAreDisplayOnly() {
        val cases = listOf(
            "Work:Error" to "400",
            "a:Failure" to "30",
            "a:not found" to "30",
            "Work:Error" to "0x190",
            "a:not found" to "c13",
            "Work:Error" to "4c13",
            "a:Failure" to "20001010",
            "Work:Error" to "61010",
            "a:Failure" to "0x00000400",
        )
        for ((label, flags) in cases) {
            val text = r13LegacySnapshot(label, flags)
            val headerLine = VerboseUserRow.verboseLines(text).first { it.contains("UserInfo{95:") }
            val classified = IdentityLineClassifier.classify(headerLine)
            assertEquals("$label/$flags must be a legacy header", IdentityLineKind.LEGACY_HEADER, classified.kind)
            assertEquals(label, classified.fields!!.label)
            assertEquals(flags, classified.fields!!.flags)
            // The neutralized span is exactly the label, not the flags/tail.
            assertEquals(
                label,
                headerLine.substring(classified.fields!!.labelStart, classified.fields!!.labelEnd),
            )

            val claim = SamsungProfileAdapter.parseUserListVerbose(text).single { it.userId == 95 }
            assertEquals("type must come from the body only", ProfileUserType.CLONE, claim.userType)
            assertEquals(label, claim.label)
            assertEquals(0, claim.parentId)
            assertEquals(15, claim.serial)
            assertTrue("$label/$flags identity gate", PmOutputParser.isIdentityChannelOk(text, 0))
            assertTrue("$label/$flags pm-list gate", PmOutputParser.isListUsersOutputOk(text, 0))

            // pm-list diagnostics use the SAME label/flags split.
            val profiles = PmOutputParser.parseUsersDetailed(text)
            val diagnostic = profiles.single { it.userId == 95 }
            assertEquals(label, diagnostic.label)
            assertEquals(flags, diagnostic.flags)
        }
    }

    @Test
    fun r13_colonLabelsForOwnerDualAndOtherKeepTypeFromBodyOnly() {
        // owner / dual / other ids x colon-bearing names x the three decimal
        // flag spellings already present in ROM fixtures. The label (including
        // its error keyword) is never type evidence, and the strict gates pass.
        val cases = listOf(
            Triple(0, "Work:Error", "13"),
            Triple(95, "a:Failure", "400"),
            Triple(10, "a:not found", "30"),
        )
        for ((id, name, flag) in cases) {
            val text = listOf(
                "  UserInfo{$id:$name:$flag} serialNo=$id:",
                "    userType=android.os.usertype.profile.MANAGED",
            ).joinToString("\n")
            val headerLine = VerboseUserRow.verboseLines(text).first()
            val classified = IdentityLineClassifier.classify(headerLine)
            assertEquals("$name/$flag must classify as LEGACY_HEADER", IdentityLineKind.LEGACY_HEADER, classified.kind)
            assertEquals(name, classified.fields!!.label)
            assertEquals(flag, classified.fields!!.flags)
            val claim = SamsungProfileAdapter.parseUserListVerbose(text).single { it.userId == id }
            assertEquals("type must come from the body only", ProfileUserType.OTHER, claim.userType)
            assertEquals(name, claim.label)
            assertTrue("$name/$flag identity gate", PmOutputParser.isIdentityChannelOk(text, 0))
            assertTrue("$name/$flag pm-list gate", PmOutputParser.isListUsersOutputOk(text, 0))
        }
    }

    @Test
    fun r13_aospSpacedFlagsSuffixKeepsTheColonLabel() {
        val header = "  UserInfo{95:Work:Error 400} serialNo=15:"
        val fields = when (val parsed = LegacyHeaderParser.parse(header)) {
            is LegacyParseResult.Ok -> parsed.fields
            else -> error("AOSP spaced flags must parse: $parsed")
        }
        assertEquals(95, fields.id)
        assertEquals("Work:Error", fields.label)
        assertEquals("400", fields.flags)
        assertEquals("Work:Error", header.substring(fields.labelStart, fields.labelEnd))
        assertEquals(IdentityLineKind.LEGACY_HEADER, IdentityLineClassifier.classify(header).kind)
        val text = r13LegacySnapshot("Work:Error", "400")
        assertTrue(PmOutputParser.isIdentityChannelOk(text, 0))
    }

    @Test
    fun r13_malformedFlagsAreRejectedAndFailBothGates() {
        for (header in listOf(
            "  UserInfo{95:Work:Error:400oops} serialNo=15:",
            "  UserInfo{95:Error} serialNo=15:",
            "  UserInfo{95:Work:Error:400}} serialNo=15:",
            "  UserInfo{95:Work:{Error:400} serialNo=15:",
            "  UserInfo{95:Work:Error:400}{extra}",
        )) {
            assertEquals("$header must be REJECTED", IdentityLineKind.REJECTED, IdentityLineClassifier.classify(header).kind)
            val text = listOf("Users:", header, "    parentId=0", "    userType=android.os.usertype.profile.CLONE").joinToString("\n")
            assertFalse("$header must fail the identity gate", PmOutputParser.isIdentityChannelOk(text, 0))
            assertFalse("$header must fail the pm-list gate", PmOutputParser.isListUsersOutputOk(text, 0))
        }
    }

    @Test
    fun r13_genuineTailErrorAfterColonLabelStillFailsGate() {
        val text = "Users:\n  UserInfo{95:Work:Error:400} Failure [INSTALL_FAILED_INVALID_APK: bad]\n" +
            "    parentId=0\n    userType=android.os.usertype.profile.CLONE"
        val classified = IdentityLineClassifier.classify(VerboseUserRow.verboseLines(text)[1])
        assertEquals(IdentityLineKind.LEGACY_HEADER, classified.kind)
        assertEquals("Work:Error", classified.fields!!.label)
        assertFalse("neutralizing only the label must keep the tail error visible", PmOutputParser.isIdentityChannelOk(text, 0))
        assertFalse(PmOutputParser.isListUsersOutputOk(text, 0))
    }

    // ---- R14: recognized malformed/overflow numeric evidence stays invalid ----

    // A structurally valid CLONE row whose tail carries [tail]. The type is
    // canonical, so WITHOUT strict value validation these cases would stay
    // CLONE with a truncated/dropped parent or serial and could Confirm.
    private fun r14Row(tail: String) =
        "1: id=95, name=OK, type=profile.CLONE, flags=X $tail"

    private fun assertInvalidNumericField(tail: String, parent: Int?, serial: Int?) {
        val claim = SamsungProfileAdapter.parseUserListVerbose(r14Row(tail)).single()
        assertEquals("$tail: a malformed field must force UNKNOWN", ProfileUserType.UNKNOWN, claim.userType)
        assertTrue("$tail: a malformed field must be flagged invalid", claim.evidenceInvalid)
        assertEquals("$tail: parent (diagnosis only)", parent, claim.parentId)
        assertEquals("$tail: serial (diagnosis only)", serial, claim.serial)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
    }

    @Test
    fun r14_malformedParentValuesAreInvalidNotTruncated() {
        for (tail in listOf(
            "(parentId=0x10)", // hex was silently read as parent 0
            "(parentId=2147483648)", // single Int overflow
            "parentId=", // present but empty
        )) {
            assertInvalidNumericField(tail, parent = null, serial = null)
        }
    }

    @Test
    fun r14_malformedSerialValuesAreInvalidNotTruncated() {
        for (tail in listOf(
            "serialNo=15oops", // trailing word was silently read as serial 15
            "serialNo=2147483648", // single Int overflow
            "serialNo=-15", // serial is unsigned
            "serial=+15", // no leading '+'
            "serialNo=15:400", // extra colon segment is not a value
            "serialNo=", // present but empty
        )) {
            assertInvalidNumericField(tail, parent = null, serial = null)
        }
    }

    @Test
    fun r14_validPlusOverflowDuplicateKeepsFirstValidForDiagnosis() {
        // The overflow occurrence is NOT dropped: it invalidates the claim
        // while the first valid value is retained for diagnosis only.
        for ((tail, parent, serial) in listOf(
            Triple("(parentId=0) (parentId=2147483648)", 0, null),
            Triple("parentId=0 parentId=0x10", 0, null),
            Triple("serialNo=15 serialNo=2147483648", null, 15),
            Triple("serialNo=15 serialNo=15oops", null, 15),
        )) {
            assertInvalidNumericField(tail, parent = parent, serial = serial)
        }
    }

    @Test
    fun r14_positiveNumericSpellingsKeepExactValues() {
        for ((tail, parent, serial) in listOf(
            Triple("(parentId=0)", 0, null),
            Triple("parentId: -10000", -10000, null),
            Triple("serialNo=15:", null, 15),
            Triple("serial number: 15", null, 15),
            Triple("Serial Number : 15", null, 15),
            Triple("serialnumber=99", null, 99),
            Triple("parentId=0, serialNo=15", 0, 15),
            Triple("personalSerialNo=7 mySerial=8 grandparentId=3", null, null),
        )) {
            val claim = SamsungProfileAdapter.parseUserListVerbose(r14Row(tail)).single()
            assertEquals("$tail: clean value must keep CLONE", ProfileUserType.CLONE, claim.userType)
            assertFalse("$tail: clean value must not be flagged", claim.evidenceInvalid)
            assertEquals("$tail parent", parent, claim.parentId)
            assertEquals("$tail serial", serial, claim.serial)
        }
    }

    @Test
    fun r14_leftBoundaryGuardsContributeNoNumericEvidence() {
        for (tail in listOf("personalSerialNo=7", "mySerial=8", "grandparentId=3")) {
            val claim = SamsungProfileAdapter.parseUserListVerbose(r14Row(tail)).single()
            assertEquals("$tail must not change the type", ProfileUserType.CLONE, claim.userType)
            assertNull("$tail must not contribute a serial", claim.serial)
            assertNull("$tail must not contribute a parent", claim.parentId)
            assertFalse(claim.evidenceInvalid)
        }
    }
}
