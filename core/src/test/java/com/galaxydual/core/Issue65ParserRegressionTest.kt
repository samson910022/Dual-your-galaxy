// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// RED regression test for issue #65: the new One UI verbose `cmd user list -v`
// format (`N: id=<id>, name=<label>, type=<token>, flags=... (parentId=0) ...`)
// has NO `UserInfo{...}` header, so a user-renamed profile whose `name=` label
// contains an error keyword (`Error`, `not found`, ...) is left untouched by
// PmOutputParser.IDENTITY_HEADER_STRIP and is then matched by ERROR_LINE in
// checkStrictLines(). That wrongly fails isIdentityChannelOk()/the verified
// identity gate even though the label is display-only by contract.
//
// The label is display-only in SamsungProfileAdapter too (parseNewVerboseLines
// takes the type/parent evidence from `type=` and the flag tail, never from
// `name=`), so the correct fix is to strip/neutralize ONLY the `name=` segment
// of each new-format verbose line before the strict ERROR_LINE scan -- NOT the
// whole line. Stripping the whole line (or exempting all verbose lines) would
// let a genuine body error (`Failure [...]`, a non-zero exit, etc.) slip
// through. The negative-control tests below pin that: they PASS today and must
// keep passing after the fix.
//
// RED evidence (before the fix): the two `name=` gate assertions and the
// display-name type-injection assertion failed; the two control tests passed.
// After neutralizing only the `name=` segment (PmOutputParser) and taking the
// LAST `, type=` token (SamsungProfileAdapter), all five pass. All data is synthetic.
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
        // RED: name=Error is a display-only label, not a command error. Today
        // IDENTITY_HEADER_STRIP strips nothing (no UserInfo header), ERROR_LINE
        // matches "Error" on the u95 line, and the gate returns false.
        assertTrue(
            "new-format name=Error is a display label and must not fail the identity gate",
            PmOutputParser.isIdentityChannelOk(verboseWithErrorName, 0),
        )
    }

    @Test
    fun newVerbose_notFoundLabelInNameIsDisplayOnly_gatePasses() {
        // RED: same root cause with a multi-word keyword matched by the
        // `not\s+found` pattern in ERROR_LINE.
        val verbose = verboseWithErrorName.replace("name=Error", "name=not found")
        assertTrue(
            "new-format name=not found is a display label and must not fail the identity gate",
            PmOutputParser.isIdentityChannelOk(verbose, 0),
        )
    }

    // RED regression for the display-name comma/type injection variant:
    // `NEW_VERBOSE_LINE`'s `name=([^,]*)` stops at the first comma, so a
    // user-renamed label containing `, type=profile.CLONE` makes the injected
    // token land in group 3 (the authorization-relevant userType) while the
    // real `type=full.SYSTEM` token is pushed into the ignored tail. The
    // label is display-only: group 3 must come from the LAST `, type=`.
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
        // when the legacy dumpsys channel and the property agree. This already
        // passes today -- the bug is confined to the strict identity gate.
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
}
