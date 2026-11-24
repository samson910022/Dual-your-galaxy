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
    // silently overwrite a fresh claim: the merged claim is UNKNOWN.
    @Test
    fun legacyAndFreshTypeDisagreement_resolvesUnknown() {
        val text = """
            UserInfo{95:DualApp:400} serialNo=15:
              userType=android.os.usertype.full.SYSTEM
            1: id=95, name=DUAL_APP, type=profile.CLONE, flags=0x190 (parentId=0)
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        val claim = parsed.first { it.userId == 95 }
        assertEquals(ProfileUserType.UNKNOWN, claim.userType)
        assertEquals(IdentityConfidence.LOW, claim.confidence)
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
}
