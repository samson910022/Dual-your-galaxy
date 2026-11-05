// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Stage-1 tests for the additive platform-identity adapter (PR-1, issue
// #21). These cover ONLY the new SamsungProfileAdapter/ProfileIdentity and
// never touch the legacy PmOutputParser.resolveDualUserId() boundary.
class ProfileIdentityTest {
    // ---- parseUserListVerbose ----

    @Test
    fun userListVerbose_parsesDistinctIdsSerialsAndParents() {
        val text = """
            Users:
              UserInfo{0:Owner:13} running:
                Flags: 0x00000013
                Serial number: 0
                User type: android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} running:
                Flags: 0x00000190
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(2, parsed.size)
        val owner = parsed[0]
        assertEquals(0, owner.userId)
        assertEquals(ProfileUserType.OTHER, owner.userType)
        assertEquals(IdentitySource.USER_LIST_V, owner.source)
        val clone = parsed[1]
        assertEquals(95, clone.userId)
        // serial lives in its own namespace: 15 != 95 must be preserved.
        assertEquals(15, clone.serial)
        assertEquals(0, clone.parentId)
        assertEquals(ProfileUserType.CLONE, clone.userType)
        assertEquals(IdentitySource.USER_LIST_V, clone.source)
        assertEquals(IdentityConfidence.MED, clone.confidence)
    }

    @Test
    fun userListVerbose_serialNeverConfusedWithUserId() {
        val text = """
            Users:
              UserInfo{10:Work:1030} running:
                SerialNo=11
                parentId = 0
                userType = android.os.usertype.profile.MANAGED
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        // 11 is the serial, 10 stays the identity key.
        assertEquals(10, parsed[0].userId)
        assertEquals(11, parsed[0].serial)
        assertEquals(0, parsed[0].parentId)
    }

    @Test
    fun userListVerbose_reusedIdKeepsSerialPerSnapshot() {
        // Android may reuse a userId after profile removal; the serial
        // distinguishes incarnations while the id stays the lookup key.
        val before = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val after = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 16
                parentId=0
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val first = SamsungProfileAdapter.parseUserListVerbose(before).single()
        val second = SamsungProfileAdapter.parseUserListVerbose(after).single()
        assertEquals(95, first.userId)
        assertEquals(95, second.userId)
        assertEquals(15, first.serial)
        assertEquals(16, second.serial)
    }

    @Test
    fun userListVerbose_flagVariantsDoNotAffectType() {
        // Header flag fields (c13 vs 13 vs hex vs multi-bit) are display
        // only and must never change the type verdict.
        val text = """
            Users:
              UserInfo{0:Owner:c13} running:
                User type: android.os.usertype.full.SYSTEM
              UserInfo{95:A:400} running:
                User type: android.os.usertype.profile.CLONE
              UserInfo{96:B:0x00000400} running:
                User type: android.os.usertype.profile.CLONE
              UserInfo{10:C:1030} running:
                User type: android.os.usertype.profile.MANAGED
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(
            listOf(ProfileUserType.OTHER, ProfileUserType.CLONE, ProfileUserType.CLONE, ProfileUserType.OTHER),
            parsed.map { it.userType },
        )
    }

    @Test
    fun userListVerbose_renamedDualLabelWithoutCloneTypeIsOther() {
        // A user-renamed label containing DUAL proves nothing: the type
        // line says SECONDARY, so this is OTHER, never CLONE.
        val text = """
            Users:
              UserInfo{97:MY_DUAL_SPACE:400} running:
                Serial: 20
                parentId=0
                User type: android.os.usertype.full.SECONDARY
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals("MY_DUAL_SPACE", parsed[0].label)
        assertEquals(ProfileUserType.OTHER, parsed[0].userType)
    }

    @Test
    fun userListVerbose_toleratesCaseOrderAndSeparators() {
        // Parent line before type line, SCREAMING key, `=` separator,
        // lowercase clone token: all must still classify CLONE.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                PARENTID=0
                SERIALNO = 15
                USER TYPE = android.os.usertype.profile.clone
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)
        assertEquals(0, parsed[0].parentId)
        assertEquals(15, parsed[0].serial)
    }

    @Test
    fun userListVerbose_missingTypeLinesYieldUnknown() {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
        assertEquals(IdentityConfidence.LOW, parsed[0].confidence)
        assertEquals(15, parsed[0].serial)
    }

    @Test
    fun userListVerbose_emptyOrGarbageYieldsEmpty() {
        assertTrue(SamsungProfileAdapter.parseUserListVerbose("").isEmpty())
        assertTrue(SamsungProfileAdapter.parseUserListVerbose("Users:\n  nothing here\n").isEmpty())
    }

    // ---- parseDumpsysUser ----

    @Test
    fun dumpsys_parsesCloneParentAndSerial() {
        val text = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} serialNo=15:
                parentId=0
                userType=android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseDumpsysUser(text)
        assertEquals(2, parsed.size)
        assertEquals(ProfileUserType.OTHER, parsed[0].userType)
        assertEquals(ProfileUserType.CLONE, parsed[1].userType)
        assertEquals(95, parsed[1].userId)
        assertEquals(15, parsed[1].serial)
        assertEquals(0, parsed[1].parentId)
        assertEquals(IdentitySource.DUMPSYS, parsed[1].source)
    }

    @Test
    fun dumpsys_cloneMarkerIsCaseInsensitive() {
        val text = """
            UserInfo{95:DualApp:400} serialNo=15:
              Usertype Profile.Clone
              Parent=0
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseDumpsysUser(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)
        assertEquals(0, parsed[0].parentId)
    }

    @Test
    fun dumpsys_missingTypeLineYieldsUnknown() {
        val text = """
            UserInfo{95:DualApp:400} serialNo=15:
              parentId=0
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseDumpsysUser(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
        assertEquals(0, parsed[0].parentId)
    }

    // ---- resolveDual ----

    private fun claim(
        userId: Int,
        userType: ProfileUserType,
        source: IdentitySource,
        serial: Int? = null,
        label: String = "",
    ) = ProfileIdentity(
        userId = userId,
        serial = serial,
        userType = userType,
        parentId = if (userId == 0) null else 0,
        source = source,
        confidence = IdentityConfidence.MED,
        label = label,
    )

    @Test
    fun resolveDual_tripleAgreementYieldsHigh() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15),
        )
        val res = SamsungProfileAdapter.resolveDual(candidates, propertyId = 95)
        assertEquals(DualResolution.Confirmed(95), res)
        assertEquals(IdentityConfidence.HIGH, (res as DualResolution.Confirmed).confidence)
    }

    @Test
    fun resolveDual_twoChannelAgreementWithoutPropertyYieldsHigh() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
        )
        assertEquals(DualResolution.Confirmed(95), SamsungProfileAdapter.resolveDual(candidates))
    }

    @Test
    fun resolveDual_singleSourceIsUnknownNeverHigh() {
        val only = listOf(claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V))
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(only))
    }

    @Test
    fun resolveDual_multipleCloneCandidatesRefused() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(96, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
        )
        val res = SamsungProfileAdapter.resolveDual(candidates)
        assertTrue(res is DualResolution.Refused)
    }

    @Test
    fun resolveDual_conflictingTypeOnSameIdRefused() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.OTHER, IdentitySource.DUMPSYS),
        )
        assertTrue(SamsungProfileAdapter.resolveDual(candidates) is DualResolution.Refused)
    }

    @Test
    fun resolveDual_propertyMismatchRefused() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
        )
        assertTrue(SamsungProfileAdapter.resolveDual(candidates, propertyId = 96) is DualResolution.Refused)
    }

    @Test
    fun resolveDual_propSourceEntriesConstrainLikePropertyId() {
        val agree = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
            claim(95, ProfileUserType.UNKNOWN, IdentitySource.PROP),
        )
        assertEquals(DualResolution.Confirmed(95), SamsungProfileAdapter.resolveDual(agree))
        val disagree = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
            claim(96, ProfileUserType.UNKNOWN, IdentitySource.PROP),
        )
        assertTrue(SamsungProfileAdapter.resolveDual(disagree) is DualResolution.Refused)
    }

    @Test
    fun resolveDual_dualLabelWithoutCloneTypeNeverAuthorizes() {
        // End-to-end proof of the display-only label rule: both channels see
        // a DUAL-named profile, but neither types it CLONE.
        val userList = """
            Users:
              UserInfo{97:MY_DUAL_SPACE:400} running:
                Serial: 20
                parentId=0
                User type: android.os.usertype.full.SECONDARY
        """.trimIndent()
        val dumpsys = """
            UserInfo{97:MY_DUAL_SPACE:400} serialNo=20:
              parentId=0
              userType=android.os.usertype.full.SECONDARY
        """.trimIndent()
        val candidates = SamsungProfileAdapter.parseUserListVerbose(userList) +
            SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.userType == ProfileUserType.OTHER })
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates))
    }

    @Test
    fun resolveDual_ownerCloneRefused() {
        val candidates = listOf(
            claim(0, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(0, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
        )
        assertTrue(SamsungProfileAdapter.resolveDual(candidates) is DualResolution.Refused)
    }

    @Test
    fun resolveDual_emptyCandidatesIsUnknown() {
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(emptyList()))
        // A property id alone (single source) never authorizes either.
        assertEquals(
            DualResolution.Unknown,
            SamsungProfileAdapter.resolveDual(emptyList(), propertyId = 95),
        )
    }

    // ---- Phase 1 hardening (locked) ----

    @Test
    fun nonTypeLinesWithCloneTokenNeverYieldClone() {
        // `profileCloneable` lacks the right boundary; `Failure: profile
        // clone` lives on a non-type line. Neither may yield CLONE.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                note=profileCloneable
                Failure: profile clone not found
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
        assertTrue(parsed[0].userType != ProfileUserType.CLONE)
    }

    @Test
    fun bareUsertypeWithoutSeparatorYieldsOther() {
        val text = """
            Users:
              UserInfo{10:Work:1030} running:
                usertype profile.MANAGED
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.OTHER, parsed[0].userType)
    }

    @Test
    fun sameBlockDualToken_clonePriorityOverManagedInTypeLine() {
        // Both tokens in one explicit type line: CLONE wins over MANAGED.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: android.os.usertype.profile.MANAGED, android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)
    }

    @Test
    fun emptyTypeValueYieldsUnknown() {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type:
                Serial: 15
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
        assertEquals(IdentityConfidence.LOW, parsed[0].confidence)
    }

    @Test
    fun pmListSourcedCloneDoesNotAuthorize() {
        // PM_LIST CLONE + single true-channel CLONE must stay Unknown:
        // display/diagnostic sources never join the two-channel rule.
        val singlePlusPm = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.PM_LIST),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(singlePlusPm))
        val singlePlusXml = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.XML_DIAG),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(singlePlusXml))
        // Even with both true channels, a PM_LIST OTHER must not veto.
        val withPmOther = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
            claim(95, ProfileUserType.OTHER, IdentitySource.PM_LIST),
        )
        assertEquals(DualResolution.Confirmed(95), SamsungProfileAdapter.resolveDual(withPmOther))
    }

    @Test
    fun parseDualPropertyId_strictSingleLine() {
        assertEquals(95, PmOutputParser.parseDualPropertyId("95"))
        assertEquals(95, PmOutputParser.parseDualPropertyId("  95  "))
        assertEquals(95, PmOutputParser.parseDualPropertyId("95\n"))
        assertEquals(95, PmOutputParser.parseDualPropertyId("95\r"))
        assertEquals(95, PmOutputParser.parseDualPropertyId("95\r\n"))
        assertEquals(0, PmOutputParser.parseDualPropertyId("0"))
        assertEquals(null, PmOutputParser.parseDualPropertyId(""))
        assertEquals(null, PmOutputParser.parseDualPropertyId("   "))
        assertEquals(null, PmOutputParser.parseDualPropertyId("95\n96"))
        assertEquals(null, PmOutputParser.parseDualPropertyId("95\n96\n"))
        assertEquals(null, PmOutputParser.parseDualPropertyId("-1"))
        assertEquals(null, PmOutputParser.parseDualPropertyId("-5"))
        assertEquals(null, PmOutputParser.parseDualPropertyId("unknown"))
        assertEquals(null, PmOutputParser.parseDualPropertyId("unknown\n"))
        assertEquals(null, PmOutputParser.parseDualPropertyId("95abc"))
    }

    @Test
    fun misleadingIdsDoNotMisparseParentOrSerial() {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                transparentId=99
                grandparentId=98
                personalSerialNo=77
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(null, parsed[0].parentId)
        assertEquals(null, parsed[0].serial)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)

        val withReal = """
            Users:
              UserInfo{95:DualApp:400} running:
                transparentId=99
                parentId=0
                personalSerialNo=77
                Serial: 15
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed2 = SamsungProfileAdapter.parseUserListVerbose(withReal)
        assertEquals(1, parsed2.size)
        assertEquals(0, parsed2[0].parentId)
        assertEquals(15, parsed2[0].serial)
    }

    @Test
    fun separatorLessCloneWithTrailingParentIdYieldsClone() {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                usertype profile.CLONE parentId=0
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)
    }

    @Test
    fun separatorLessManagedWithTrailingFlagsYieldsOther() {
        val text = """
            Users:
              UserInfo{10:Work:1030} running:
                usertype profile.MANAGED (flags: 0x400)
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.OTHER, parsed[0].userType)
    }

    @Test
    fun embeddedSuperuserTypeDoesNotYieldClone() {
        // `superuser type:` must not match TYPE_LINE's left boundary.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                note=superuser type: profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
        assertTrue(parsed[0].userType != ProfileUserType.CLONE)
    }

    @Test
    fun gluedProfileCloneWithoutSeparatorIsNotClone() {
        // `myprofile.CLONE` has no separator before CLONE: the CLONE_MARKER
        // left boundary must reject it (line still carries the type keyword,
        // so the verdict is OTHER, never CLONE).
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                usertype myprofile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.OTHER, parsed[0].userType)
    }
}
