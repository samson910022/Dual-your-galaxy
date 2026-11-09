// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        parentId: Int? = if (userId == 0) null else 0,
        label: String = "",
    ) = ProfileIdentity(
        userId = userId,
        serial = serial,
        userType = userType,
        parentId = parentId,
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
        assertEquals(DualResolution.Confirmed(95, 15, 0), res)
        assertEquals(IdentityConfidence.HIGH, (res as DualResolution.Confirmed).confidence)
    }

    @Test
    fun resolveDual_twoChannelAgreementWithoutPropertyIsUnknown() {
        // Phase C P2-2: property is required corroboration; two CLONE channels
        // without a property id is Unknown, never Confirmed.
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates))
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
        assertEquals(DualResolution.Confirmed(95, null, 0), SamsungProfileAdapter.resolveDual(agree))
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
    fun sameBlockDualToken_contradictionInTypeLineIsUnknown() {
        // P1-B (issue #21 owner re-review): both tokens in one explicit
        // type line is an intra-line contradiction -> UNKNOWN, never
        // CLONE-by-priority. Flipped from the old CLONE-wins expectation.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: android.os.usertype.profile.MANAGED, android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
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
        // Phase C P2-2: without a property id the result is Unknown (property
        // required); PM_LIST still neither authorizes nor vetoes.
        val withPmOther = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
            claim(95, ProfileUserType.OTHER, IdentitySource.PM_LIST),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(withPmOther))
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
        // `myprofile.CLONE` has no separator before CLONE: the isSupportedCloneToken
        // allowlist must reject it (line still carries the type keyword,
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

    @Test
    fun underscoreSeparatedCloneSingletonYieldsClone() {
        // Phase A regression: `profile_CLONE` single token must be CLONE
        // (separator-tolerant PROFILE_TOKEN + isSupportedCloneToken).
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: profile_CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)
    }

    @Test
    fun mixedDotAndUnderscoreSeparatorsIsUnknown() {
        // Cross-separator contradiction: `profile.MANAGED` + `profile_CLONE`
        // in one mention -> UNKNOWN (never CLONE-by-priority).
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: profile.MANAGED profile_CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    // ---- Phase A P1-1: dual-CLONE tightening (issue #21 owner finding) ----
    // Two-channel Confirmed requires the SAME id to be typed CLONE on BOTH
    // USER_LIST_V and DUMPSYS. CLONE on one channel plus UNKNOWN on the
    // other is Unknown (insufficient evidence), never Confirmed.

    @Test
    fun resolveDual_cloneVerboseUnknownDumpsysIsUnknown() {
        // Owner repro: CLONE(VERBOSE) + UNKNOWN(DUMPSYS) on the same id.
        // With-property too: single CLONE channel never confirms.
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15),
            claim(95, ProfileUserType.UNKNOWN, IdentitySource.DUMPSYS, serial = 15),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates, propertyId = 95))
    }
    // PR-33 S1 (P1 cross-source CLONE consensus): USER_LIST_V CLONE on 95
    // plus DUMPSYS UNKNOWN on 95 (no type line, same serial 15) is
    // insufficient evidence -> Unknown, and the serial-bound target is null
    // (fail closed). Guards the old loose rule where any two-channel id
    // presence counted as Confirmed.
    @Test
    fun clonePlusUnknown_refusesDualTarget() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15),
            claim(95, ProfileUserType.UNKNOWN, IdentitySource.DUMPSYS, serial = 15),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates))
        assertNull(SamsungProfileAdapter.resolveDualTarget(candidates))
        assertEquals(
            -1,
            PmOutputParser.resolveDualUserIdVerified(
                userListVerbose = """
                    Users:
                      UserInfo{0:Owner:13} running:
                        Serial number: 0
                        User type: android.os.usertype.full.SYSTEM
                      UserInfo{95:DualApp:400} running:
                        Serial: 15
                        parentId=0
                        User type: android.os.usertype.profile.CLONE
                """.trimIndent(),
                dumpsysUser = """
                    Users:
                      UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                        userType=android.os.usertype.full.SYSTEM
                      UserInfo{95:DualApp:400} serialNo=15:
                        parentId=0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun resolveDual_unknownVerboseCloneDumpsysIsUnknown() {
        // Reverse direction: UNKNOWN(VERBOSE) + CLONE(DUMPSYS) is Unknown too.
        val candidates = listOf(
            claim(95, ProfileUserType.UNKNOWN, IdentitySource.USER_LIST_V, serial = 15),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates, propertyId = 95))
    }

    @Test
    fun resolveDual_cloneCloneWithoutPropertyIsUnknown() {
        // Phase C P2-2: CLONE + CLONE on the same id without a property id is
        // Unknown (property required), never Confirmed. The with-property
        // Confirm path is locked by the P2-1 header tests below.
        // (fixture parentId=0/serial consistent via claim() defaults).
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates))
    }

    // ---- Phase B P1-2 parent gate + P1-3 serial gate (issue #21) ----
    // Parent must be present on BOTH channels, equal, and == 0; serial
    // conflicts refuse while a missing serial is tolerated.

    @Test
    fun resolveDual_parentConflictAcrossChannelsRefused() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15, parentId = 0),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15, parentId = 10),
        )
        val res = SamsungProfileAdapter.resolveDual(candidates, propertyId = 95)
        assertTrue(res is DualResolution.Refused)
        assertTrue((res as DualResolution.Refused).reason.contains("conflicting parent"))
    }

    @Test
    fun resolveDual_parentNonZeroOnBothChannelsRefused() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15, parentId = 10),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15, parentId = 10),
        )
        val res = SamsungProfileAdapter.resolveDual(candidates, propertyId = 95)
        assertTrue(res is DualResolution.Refused)
        assertTrue((res as DualResolution.Refused).reason.contains("non-owner parent"))
    }

    @Test
    fun resolveDual_parentMissingOnOneChannelIsUnknown() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15, parentId = 0),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15, parentId = null),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates, propertyId = 95))
    }

    @Test
    fun resolveDual_parentZeroWithoutPropertyIsUnknown() {
        // Phase C P2-2: parent 0+0 without a property id is Unknown (property
        // required). The with-property Confirm path is locked by P2-1 tests.
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15, parentId = 0),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15, parentId = 0),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates))
    }

    @Test
    fun resolveDual_serialConflictRefused() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15, parentId = 0),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 99, parentId = 0),
        )
        val res = SamsungProfileAdapter.resolveDual(candidates, propertyId = 95)
        assertTrue(res is DualResolution.Refused)
        assertTrue((res as DualResolution.Refused).reason.contains("conflicting serial"))
    }

    @Test
    fun resolveDual_serialMissingWithoutPropertyIsUnknown() {
        // Phase C P2-2: serial-missing tolerance still needs a property id;
        // without it the result is Unknown.
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15, parentId = 0),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = null, parentId = 0),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates))
    }

    // ---- Phase C P2-1 header-aware gate (issue #21) ----
    // UserInfo{...} header labels are display-only: labels containing error
    // keywords must not trip the identity gate, while a genuine body
    // Failure line still fails.

    // Symmetric case: USER_LIST_V UNKNOWN plus DUMPSYS CLONE on the same id
    // (same serial) is likewise Unknown/null, never Confirmed.
    @Test
    fun unknownPlusClone_refusesDualTarget() {
        val candidates = listOf(
            claim(95, ProfileUserType.UNKNOWN, IdentitySource.USER_LIST_V, serial = 15),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15),
        )
        assertEquals(DualResolution.Unknown, SamsungProfileAdapter.resolveDual(candidates))
        assertNull(SamsungProfileAdapter.resolveDualTarget(candidates))
        assertNull(
            PmOutputParser.resolveDualTargetVerified(
                userListVerbose = """
                    Users:
                      UserInfo{0:Owner:13} running:
                        Serial number: 0
                        User type: android.os.usertype.full.SYSTEM
                      UserInfo{95:DualApp:400} running:
                        Serial: 15
                        parentId=0
                """.trimIndent(),
                dumpsysUser = """
                    Users:
                      UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                        userType=android.os.usertype.full.SYSTEM
                      UserInfo{95:DualApp:400} serialNo=15:
                        parentId=0
                        userType=android.os.usertype.profile.CLONE
                """.trimIndent(),
            ),
        )
    }

    // Both-CLONE success case (dual consensus + serial binding): retained and
    // strengthened to pin the serial-bound target, so the P1 gate keeps a
    // positive proof alongside the two refusal cases above.
    @Test
    fun bothClone_confirmsDualTargetWithSerial() {
        val candidates = listOf(
            claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15),
            claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 15),
        )
        // Base strict semantics (triple consensus): the Confirm needs
        // propertyId=95 alongside dual-CLONE + parentId=0 on both channels.
        assertEquals(DualResolution.Confirmed(95, 15, 0), SamsungProfileAdapter.resolveDual(candidates, propertyId = 95))
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 15, parentId = 0),
            SamsungProfileAdapter.resolveDualTarget(candidates, propertyId = 95),
        )
    }

    @Test
    fun identityGate_headerErrorLabelsDoNotFail() {
        val verbose = """
            Users:
              UserInfo{0:Owner:13} running:
                Serial number: 0
                User type: android.os.usertype.full.SYSTEM
              UserInfo{95:Error:400} running:
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{95:Failure:400} serialNo=15:
                parentId=0
                userType=android.os.usertype.profile.CLONE
        """.trimIndent()
        // Header labels alone must pass the gate.
        assertTrue(PmOutputParser.isIdentityChannelOk(verbose, 0))
        assertTrue(PmOutputParser.isIdentityChannelOk(dumpsys, 0))
        // A third header variant with a not-found label.
        val notFound = "Users:\n  UserInfo{10:not found:30} running:\n    User type: android.os.usertype.profile.MANAGED\n"
        assertTrue(PmOutputParser.isIdentityChannelOk(notFound, 0))
        // Contrast: a genuine body Failure line still fails the gate.
        assertTrue(!PmOutputParser.isIdentityChannelOk(verbose + "\nFailure [INSTALL_FAILED_INVALID_APK: bad]", 0))
        assertTrue(!PmOutputParser.isIdentityChannelOk(dumpsys + "\nFailure [INSTALL_FAILED_INVALID_APK: bad]", 0))
    }

    @Test
    fun identityGate_malformedHeaderIsNotStripped() {
        // Only well-formed `UserInfo{id:label:flags}` headers are stripped.
        // A malformed header carrying an error keyword must still fail.
        val malformed = "Users:\n  UserInfo{foo Failure}\n    User type: android.os.usertype.profile.CLONE\n"
        assertTrue(!PmOutputParser.isIdentityChannelOk(malformed, 0))
        val missingFlags = "Users:\n  UserInfo{95:Error}\n    User type: android.os.usertype.profile.CLONE\n"
        assertTrue(!PmOutputParser.isIdentityChannelOk(missingFlags, 0))
    }

    @Test
    fun identityGate_unclosedHeaderDoesNotSwallowFailure() {
        // The strip pattern never spans lines: an unclosed header must not
        // swallow a genuine Failure line below it (fail-open guard).
        val truncated = "Users:\n  UserInfo{95:Broken:400 running:\nFailure [INSTALL_FAILED_INVALID_APK: bad]\n"
        assertTrue(!PmOutputParser.isIdentityChannelOk(truncated, 0))
    }

    @Test
    fun resolveDual_headerErrorLabelsStillConfirmedWithProp() {
        // End-to-end: error-keyword header labels + dual CLONE + parent 0 +
        // serial consistent + prop 95 -> Confirmed(95,15,0).
        val userList = """
            Users:
              UserInfo{0:Owner:13} running:
                Serial number: 0
                User type: android.os.usertype.full.SYSTEM
              UserInfo{95:Error:400} running:
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{95:Failure:400} serialNo=15:
                parentId=0
                userType=android.os.usertype.profile.CLONE
        """.trimIndent()
        // Gate passes despite the labels.
        assertTrue(PmOutputParser.isIdentityChannelOk(userList, 0))
        assertTrue(PmOutputParser.isIdentityChannelOk(dumpsys, 0))
        val candidates = SamsungProfileAdapter.parseUserListVerbose(userList) +
            SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertEquals(DualResolution.Confirmed(95, 15, 0), SamsungProfileAdapter.resolveDual(candidates, propertyId = 95))
        // Parser-level mapping agrees.
        assertEquals(95, PmOutputParser.resolveDualUserIdVerified(userList, dumpsys, 95))
        // Contrast: body Failure line fails the identity gate (Unknown/Refused path).
        val badVerbose = userList + "\nFailure [INSTALL_FAILED_INVALID_APK: bad]\n"
        assertTrue(!PmOutputParser.isIdentityChannelOk(badVerbose, 0))
    }

    // ---- Phase A P1-1 token-level type uniqueness (issue #21 owner R3) ----
    // A single mention holding both `profile.MANAGED` and `profile.CLONE`
    // tokens is an intra-mention contradiction -> UNKNOWN, regardless of
    // separator (whitespace, `/`, `,`, `;`).

    @Test
    fun whitespaceSeparatedManagedAndClone_isUnknown() {
        // Owner counter-example: space-separated mixed tokens must refuse.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: android.os.usertype.profile.MANAGED android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun slashSeparatedManagedAndClone_isUnknown() {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: android.os.usertype.profile.MANAGED/android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun repeatedTypeLinesWithCloneAndManaged_isUnknown() {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: android.os.usertype.profile.CLONE
                User type: android.os.usertype.profile.MANAGED
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun repeatedSameCloneToken_isStillClone() {
        // Same token twice (deduped case-insensitively) is not a
        // contradiction: clone-only still classifies CLONE.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                User type: profile.CLONE profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)
    }

    @Test
    fun newFormatCommaFlagsTail_stillClone() {
        // Comma-form flags tail (raw S24 shape `type=profile.CLONE,
        // flags=A|B`) must not feed the type mention: the flags tail is
        // display-only metadata on its own line(s). Guards the
        // parseNewVerboseLines newline split.
        val text = """
            2 users:

            0: id=0, name=Owner, type=full.SYSTEM, flags=ADMIN (running)
            1: id=95, name=DUAL_APP, type=profile.CLONE, flags=DUALAPP_PROFILE|PROFILE (parentId=0) (running)
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(2, parsed.size)
        val clone = parsed.first { it.userId == 95 }
        assertEquals(ProfileUserType.CLONE, clone.userType)
        assertEquals(0, clone.parentId)
    }

    // End-to-end repository gate (PR-33 S1, Stage2 身份診斷一致性更新):
    // V=CLONE/serial15 plus D=UNKNOWN/serial15 resolves to Unknown (id never
    // Confirmed), so removeVerifiedExpected refuses as ChannelError (never
    // NoDual) with zero uninstalls (fail closed). Legacy resolveDualUserId/
    // fetchDualTarget still fold to -1/NoDual (簽名不變)；typed 閘才區分。
    @Test
    fun clonePlusUnknown_repoGateRefusesWithZeroUninstall() {
        val verboseClone = """
            Users:
              UserInfo{0:Owner:13} running:
                Serial number: 0
                User type: android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val dumpsysUnknown = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} serialNo=15:
                parentId=0
        """.trimIndent()
        val gateway = CloneUnknownFakeGateway(
            verboseOut = verboseClone,
            dumpsysOut = dumpsysUnknown,
        )
        val repo = DualRepository(gateway)
        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        assertTrue(runSuspend { repo.fetchDualTarget() } is DualTargetResult.NoDual)
        val outcome = runSuspend {
            repo.removeVerifiedExpected("com.example.chat", 95, 15)
        }
        assertTrue("expected ChannelError (identity unknown), never NoDual, got: $outcome", outcome is RemoveOutcome.ChannelError)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run on CLONE+UNKNOWN, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // PR-33 D2 (S24 實機格式): new-ROM `cmd user list -v` has no UserInfo
    // header and no serial line. Fixture embeds the S24 evidence verbatim
    // (3-user verbose block) plus the matching dumpsys 0/95/150 blocks.
    // Strict-semantics adaptation (base p21-review, fixture-only): the CLONE
    // line keeps its flags evidence but without the `, flags=` comma -- base
    // splits an explicit `userType=` value on `,`/`;`, so `profile.CLONE`
    // plus a second `flags=...` segment is an intra-line contradiction
    // (UNKNOWN, never CLONE). Flags are display-only; the parentId tail is
    // retained so the parent gate still sees parentId=0.
    private val S24_VERBOSE = """
        3 users:

        0: id=0, name=陳孝宣, type=full.SYSTEM, flags=ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM (running) (current) (visible)
        1: id=95, name=DUAL_APP, type=profile.CLONE (flags=DUALAPP_PROFILE|INITIALIZED|PROFILE) (parentId=0) (running) (visible)
        2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=INITIALIZED|PROFILE|SDP_NOT_SUPPORTED_SECURE_FOLDER|SECURE_FOLDER (parentId=0) (running) (profile-owner) (visible)
    """.trimIndent()

    // Strict-semantics adaptation (base p21-review, fixture-only): base
    // classifies a block only on a `user`-prefixed type keyword (`User type:`
    // / `userType=`); a bare `Type:` line carries no type evidence
    // (non-type lines never yield CLONE), so the S24 dumpsys blocks use the
    // base-recognized `User type:` keyword. Serial/parent stay on the header
    // remainder (`serialNo=` authoritative) exactly as on device.
    private val S24_DUMPSYS = """
        Users:
          UserInfo{0:xxx:4c13} serialNo=0 isPrimary=true
            User type: android.os.usertype.full.SYSTEM
            Flags: 19475 (ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM)
            State: RUNNING_UNLOCKED
          UserInfo{95:xxx:20001010} serialNo=95 isPrimary=false parentId=0
            User type: android.os.usertype.profile.CLONE
            Flags: 536875024 (DUALAPP_PROFILE|INITIALIZED|PROFILE)
            State: RUNNING_UNLOCKED
          UserInfo{150:xxx:61010} serialNo=150 isPrimary=false parentId=0
            User type: android.os.usertype.profile.PRIVATE
            Flags: 397328 (INITIALIZED|PROFILE|SDP_NOT_SUPPORTED_SECURE_FOLDER|SECURE_FOLDER)
            State: RUNNING_UNLOCKED
    """.trimIndent()

    @Test
    fun s24Verbose_parsesNewFormatWithoutUserInfoHeader() {
        val parsed = SamsungProfileAdapter.parseUserListVerbose(S24_VERBOSE)
        assertEquals(3, parsed.size)
        val byId = parsed.associateBy { it.userId }
        val owner = byId[0]!!
        assertEquals("陳孝宣", owner.label)
        assertEquals(ProfileUserType.OTHER, owner.userType)
        assertNull(owner.parentId)
        assertNull(owner.serial)
        assertEquals(IdentitySource.USER_LIST_V, owner.source)
        val clone = byId[95]!!
        assertEquals("DUAL_APP", clone.label)
        assertEquals(ProfileUserType.CLONE, clone.userType)
        assertEquals(0, clone.parentId)
        assertNull(clone.serial)
        assertEquals(IdentityConfidence.MED, clone.confidence)
        // Secure Folder (profile.PRIVATE, space in name) must never be CLONE.
        val secure = byId[150]!!
        assertEquals("Secure Folder", secure.label)
        assertEquals(ProfileUserType.OTHER, secure.userType)
        assertEquals(0, secure.parentId)
        assertNull(secure.serial)
    }

    @Test
    fun s24_endToEndConfirmsDualTargetWithDumpsysSerial() {
        val candidates = SamsungProfileAdapter.parseUserListVerbose(S24_VERBOSE) +
            SamsungProfileAdapter.parseDumpsysUser(S24_DUMPSYS)
        // Base strict semantics: triple consensus needs propertyId=95
        // (S24 verbose carries no serial; dumpsys serialNo=95 alone binds).
        assertEquals(DualResolution.Confirmed(95, 95, 0), SamsungProfileAdapter.resolveDual(candidates, propertyId = 95))
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 95, parentId = 0),
            SamsungProfileAdapter.resolveDualTarget(candidates, propertyId = 95),
        )
        assertEquals(
            95,
            PmOutputParser.resolveDualUserIdVerified(S24_VERBOSE, S24_DUMPSYS, 95),
        )
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 95, parentId = 0),
            PmOutputParser.resolveDualTargetVerified(S24_VERBOSE, S24_DUMPSYS, 95),
        )
    }

    // PR-33 D3 (S24 實機 blocking, hermetic 最小復現): dumpsys 尾部全域段
    // (User types 目錄含 android.os.usertype.profile.CLONE 字樣 +
    // Allowlisted 行) 不得污染最後一個 user 塊。Users 段 0/95/150 三塊，
    // 150 塊為 PRIVATE；舊 parseBlocks 讓末塊 body 延到 EOF，把目錄的 CLONE
    // 字樣算進 150 -> cloneIds={95,150} -> Refused -> 全機 NoDual。
    // Strict-semantics adaptation (fixture-only): verbose CLONE 行的 flags
    // 證據改用無逗號形式，否則 base 的行內段規則（`userType=` 值按 `,`/`;`
    // 分段，CLONE+他段=矛盾）會把 95 判 UNKNOWN，雙通道合議不足而 -1。
    @Test
    fun dumpsysTrailingCatalog_doesNotLeakIntoLastBlock() {
        val verbose = """
            3 users:

            0: id=0, name=Owner, type=full.SYSTEM, flags=ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM (running) (current) (visible)
            1: id=95, name=DUAL_APP, type=profile.CLONE (flags=DUALAPP_PROFILE|INITIALIZED|PROFILE) (parentId=0) (running) (visible)
            2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=INITIALIZED|PROFILE|SDP_NOT_SUPPORTED_SECURE_FOLDER|SECURE_FOLDER (parentId=0) (running) (profile-owner) (visible)
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} serialNo=15:
                parentId=0
                userType=android.os.usertype.profile.CLONE
              UserInfo{150:Secure Folder:400} serialNo=16:
                parentId=0
                userType=android.os.usertype.profile.PRIVATE
            User types (12 types):
                android.os.usertype.profile.CLONE:
                    mName: android.os.usertype.profile.CLONE
            Allowlisted packages per user type
                5 -> android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertEquals(3, parsed.size)
        val byId = parsed.associateBy { it.userId }
        assertEquals(ProfileUserType.CLONE, byId[95]!!.userType)
        // 150 is PRIVATE; the trailing catalog's CLONE token must not leak in.
        assertEquals(ProfileUserType.OTHER, byId[150]!!.userType)
        assertEquals(16, byId[150]!!.serial)
        assertEquals(
            95,
            PmOutputParser.resolveDualUserIdVerified(verbose, dumpsys, 95),
        )
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 15, parentId = 0),
            PmOutputParser.resolveDualTargetVerified(verbose, dumpsys, 95),
        )
    }

    // PR-33 D3 段界單元: 末塊在下一個行首非空白段頭 (此處 Device
    // properties:) 即截斷。經公開解析結果間接斷言 (type 不被尾部 CLONE 字樣
    // 翻轉、header 同行 serial/parent 照常解析)，不暴露 parseBlocks 內部。
    @Test
    fun dumpsysTrailingDeviceProperties_truncatesLastBlock() {
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                userType=android.os.usertype.full.SYSTEM
              UserInfo{150:Secure Folder:400} serialNo=16:
                parentId=0
                userType=android.os.usertype.profile.PRIVATE
            Device properties:
              Device policy global restrictions:
                none
                android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertEquals(2, parsed.size)
        val last = parsed.single { it.userId == 150 }
        assertEquals(ProfileUserType.OTHER, last.userType)
        assertEquals(16, last.serial)
        assertEquals(0, last.parentId)
    }

    @Test
    fun singleSourceSerial_confirmsButMismatchOrBothMissingRefuse() {
        // Verbose missing, dumpsys carrying: single source binds.
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 95, parentId = 0),
            SamsungProfileAdapter.resolveDualTarget(
                listOf(
                    claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
                    claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 95),
                ),
                propertyId = 95,
            ),
        )
        // Dumpsys missing, verbose carrying: symmetric single source binds.
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 15, parentId = 0),
            SamsungProfileAdapter.resolveDualTarget(
                listOf(
                    claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15),
                    claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
                ),
                propertyId = 95,
            ),
        )
        // Both sides carry but disagree (recreated profile, fresh serial):
        // still refused.
        assertNull(
            SamsungProfileAdapter.resolveDualTarget(
                listOf(
                    claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 95),
                    claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 96),
                ),
                propertyId = 95,
            ),
        )
        // Neither side carries a serial: still refused (no silent target).
        assertNull(
            SamsungProfileAdapter.resolveDualTarget(
                listOf(
                    claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
                    claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
                ),
                propertyId = 95,
            ),
        )
    }

    // Local fake gateway for the CLONE+UNKNOWN repository gate test above:
    // serves canned identity channels plus the uninstall/list probes and
    // records every command so the test can prove zero uninstalls.
    private class CloneUnknownFakeGateway(
        private val verboseOut: String,
        private val dumpsysOut: String,
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()
        var uninstallRuns = 0

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return when {
                cmd == DualRepository.CMD_LIST_USERS ->
                    CmdResult(
                        exit = 0,
                        out = "Users:\n" +
                            "\tUserInfo{0:Owner:13} running\n" +
                            "\tUserInfo{95:DualApp:400} running\n",
                        err = "",
                    )
                cmd == DualRepository.CMD_USER_LIST_VERBOSE ->
                    CmdResult(exit = 0, out = verboseOut, err = "")
                cmd == DualRepository.CMD_DUMPSYS_USER ->
                    CmdResult(exit = 0, out = dumpsysOut, err = "")
                cmd == DualRepository.CMD_GETPROP_DUAL ->
                    CmdResult(exit = 0, out = "", err = "")
                cmd.startsWith("pm uninstall") -> {
                    uninstallRuns++
                    CmdResult(exit = 0, out = "Success", err = "")
                }
                cmd.startsWith("pm list packages") ->
                    CmdResult(exit = 0, out = "", err = "")
                else -> CmdResult(exit = 0, out = "ok", err = "")
            }
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : kotlin.coroutines.Continuation<T> {
                override val context = kotlin.coroutines.EmptyCoroutineContext
                override fun resumeWith(r: Result<T>) {
                    result = r
                }
            },
        )
        return (result ?: error("suspend block never resumed")).getOrThrow()
    }

    // ---- Phase A P1 full-token CLONE allowlist (issue #21 owner BLOCKER) ----
    // `profile.CLONE_extra` / `profile.CLONE-EXTRA` / `profile.CLONE.X` /
    // `profile.CLONEable` must never authorize: suffix-bearing profile tokens
    // are OTHER (single-mention, no mixing). Exact allowlist members
    // (`profile.CLONE`, `profile_CLONE`, `profile-CLONE`, `profile CLONE`)
    // stay CLONE.

    private fun parseSingleVerboseType(value: String): ProfileUserType {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                User type: $value
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        return parsed[0].userType
    }

    @Test
    fun cloneSuffixVariants_areOtherNotClone() {
        // Parameterized over the BLOCKER suffix family: each value appears as
        // the ONLY type mention, so OTHER proves the suffix alone refuses
        // (not an UNKNOWN mixture).
        val cases = listOf(
            "android.os.usertype.profile.CLONE_extra",
            "android.os.usertype.profile.CLONE-EXTRA",
            "android.os.usertype.profile.CLONE.X",
            "android.os.usertype.profile.CLONEable",
            "profile.CLONE_extra",
            "profile.CLONE-EXTRA",
            "profile.CLONE.X",
            "profile.CLONEable",
        )
        for (value in cases) {
            assertEquals(
                "value must be OTHER, got CLONE/UNKNOWN: $value",
                ProfileUserType.OTHER,
                parseSingleVerboseType(value),
            )
        }
    }

    @Test
    fun cloneExactAllowlistVariants_areClone() {
        // Parameterized allowlist: dot/underscore/hyphen/space/Tab separators
        // plus case-insensitivity all stay CLONE. Consecutive enumerated
        // separators (double space) are documented leniency, still CLONE.
        val cases = listOf(
            "android.os.usertype.profile.CLONE",
            "android.os.usertype.profile.clone",
            "profile.CLONE",
            "profile_CLONE",
            "profile-CLONE",
            "profile CLONE",
            "profile\tCLONE",
            "profile  CLONE",
            "android.os.usertype.profile_CLONE",
            "android.os.usertype.profile-CLONE",
        )
        for (value in cases) {
            assertEquals(
                "value must be CLONE: $value",
                ProfileUserType.CLONE,
                parseSingleVerboseType(value),
            )
        }
    }

    // ---- Phase A P1-R6: enumerated-separator allowlist (issue #21 R6 BLOCKER) ----
    // Only `.`/`_`/`-`/whitespace (space/Tab) 1-3 chars separate `profile`
    // from `clone`. `!`/`?`/`:`/`/` and any other punctuation are NOT
    // separators: each value below is the ONLY type mention, so OTHER proves
    // the punctuation form alone refuses (not an UNKNOWN mixture).

    @Test
    fun punctuationSeparatedClone_isOtherNotClone() {
        val cases = listOf(
            "android.os.usertype.profile!CLONE",
            "profile!CLONE",
            "android.os.usertype.profile?CLONE",
            "profile?CLONE",
            "android.os.usertype.profile:::CLONE",
            "profile:::CLONE",
            "android.os.usertype.profile/CLONE",
            "profile/CLONE",
            "android.os.usertype.profile:CLONE",
            "profile:CLONE",
        )
        for (value in cases) {
            assertEquals(
                "punctuation must be OTHER, got CLONE/UNKNOWN: $value",
                ProfileUserType.OTHER,
                parseSingleVerboseType(value),
            )
        }
    }

    // ---- Phase A P1-R6: tainted CLONE tail (fail-closed) ----
    // A CLONE token followed on the same line by any `/` (arbitrary gap
    // words allowed, not opening another `profile` token) is tainted ->
    // OTHER, never CLONE. The `MANAGED/profile.CLONE` slash dual-token
    // form stays UNKNOWN (regression locked: slash opens another profile
    // token, untainted).

    @Test
    fun cloneSlashTail_isOtherNotClone() {
        val cases = listOf(
            "profile.CLONE/evil",
            "android.os.usertype.profile.CLONE/evil",
            "profile.CLONE/",
            "android.os.usertype.profile.CLONE/",
            "profile.CLONE / evil",
            "android.os.usertype.profile.CLONE / evil",
            "profile.CLONE - /evil",
            "profile.CLONE foo/evil",
            "profile.CLONE (/evil)",
        )
        for (value in cases) {
            assertEquals(
                "slash tail must be OTHER, got CLONE/UNKNOWN: $value",
                ProfileUserType.OTHER,
                parseSingleVerboseType(value),
            )
        }
    }

    @Test
    fun slashSeparatedManagedAndClone_staysUnknown() {
        // Explicit R6 regression lock: `MANAGED/profile.CLONE` slash
        // dual-token is an intra-mention contradiction -> UNKNOWN (the
        // taint guard explicitly exempts `/` followed by another profile
        // token, so this must NOT flip to OTHER).
        val cases = listOf(
            "android.os.usertype.profile.MANAGED/android.os.usertype.profile.CLONE",
            "profile.MANAGED/profile.CLONE",
            "profile.CLONE/profile.MANAGED",
        )
        for (value in cases) {
            assertEquals(
                "slash dual-token must stay UNKNOWN: $value",
                ProfileUserType.UNKNOWN,
                parseSingleVerboseType(value),
            )
        }
    }

    @Test
    fun cloneParenthesizedNoteWithoutSlash_staysClone() {
        // Slash-free parenthesized note keeps the tolerated CLONE verdict
        // (taint needs a `/` on the same line); slashed notes degrade to
        // OTHER (locked in cloneSlashTail_isOtherNotClone).
        assertEquals(
            ProfileUserType.CLONE,
            parseSingleVerboseType("profile.CLONE (default)"),
        )
    }

    // ---- Phase A P1-R6: trailing-punctuation tail (fail-closed, R7 T1) ----
    // A CLONE token followed by trailing punctuation + junk (`!evil`,
    // backslash, fullwidth slash, ...) leaves a non-allowlisted residual
    // (strict hasDisallowedResidual check) -> OTHER, never CLONE. Each
    // value below is the ONLY type mention, so OTHER proves the tail
    // alone refuses (not an UNKNOWN mixture).
    @Test
    fun cloneTrailingPunctTail_isOtherNotClone() {
        val cases = listOf(
            "profile.CLONE!evil",
            "profile.CLONE?evil",
            "profile.CLONE:evil",
            "profile.CLONE;evil",
            "profile.CLONE,evil",
            "profile.CLONE@evil",
            "profile.CLONE#evil",
            "profile.CLONE\$evil",
            "profile.CLONE%evil",
            "profile.CLONE^evil",
            "profile.CLONE&evil",
            "profile.CLONE*evil",
            "profile.CLONE=evil",
            "profile.CLONE|evil",
            "profile.CLONE+evil",
            "profile.CLONE(evil)",
            "profile.CLONE)evil",
            "profile.CLONE\\evil",
            "profile.CLONE／evil",
            "profile_CLONE!evil",
            "profile CLONE!evil",
            "profile-CLONE!evil",
            "profile\tCLONE!evil",
            "android.os.usertype.profile.CLONE!evil",
            "android.os.usertype.profile.CLONE?evil",
            "android.os.usertype.profile.CLONE:evil",
            "android.os.usertype.profile.CLONE/profile.CLONE/evil",
            "profile.CLONE (default) evil",
        )
        for (value in cases) {
            assertEquals(
                "trailing-punct tail must be OTHER, got CLONE/UNKNOWN: $value",
                ProfileUserType.OTHER,
                parseSingleVerboseType(value),
            )
        }
        // Locked fail-closed strictness (Phase A): even a repeated,
        // comma-joined CLONE token is OTHER, never CLONE-by-priority --
        // `,` is not allowlisted residual noise. Do NOT relax this to
        // CLONE without a spec change.
        assertEquals(
            ProfileUserType.OTHER,
            parseSingleVerboseType("profile.CLONE, profile.CLONE"),
        )
    }

    @Test
    fun cloneTrailingBangFallback_isOtherNotClone() {
        // Separator-less fallback path (`usertype <token>`, no `:`/`=`):
        // the same `!evil` residual fails closed to OTHER there too, as
        // does a `CLONE/profile!evil` remainder (fake second token taints
        // the fallback mention just like the explicit-value path).
        val remainders = listOf(
            "profile.CLONE!evil",
            "profile.CLONE/profile!evil",
        )
        for (remainder in remainders) {
            val text = """
                Users:
                  UserInfo{95:DualApp:400} running:
                    Serial: 15
                    parentId=0
                    usertype $remainder
            """.trimIndent()
            val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
            assertEquals(1, parsed.size)
            assertEquals(
                "fallback remainder must be OTHER: $remainder",
                ProfileUserType.OTHER,
                parsed[0].userType,
            )
        }
    }

    // ---- Phase A P1-R6: slash + fake second token (fail-closed, R7 T2) ----
    // A `/` opens a legal second token ONLY for `profile` + enumerated
    // separator + name (optional `android.os.usertype.` prefix). Anything
    // else (`/profile!evil`, `/profilegarbage`, bare `/profile`,
    // `/profiles`, `/profileMANAGED`, ...) taints the mention -> OTHER
    // for a single-CLONE mention. Note: this is OTHER, NOT UNKNOWN --
    // UNKNOWN is reserved for genuine two-token contradictions
    // (`profile.CLONE/profile.MANAGED`, locked below).
    @Test
    fun cloneSlashFakeSecondToken_isOtherNotClone() {
        val cases = listOf(
            "profile.CLONE/profile!evil",
            "profile.CLONE/profile?evil",
            "profile.CLONE/profilegarbage",
            "profile.CLONE/profile",
            "profile.CLONE/profiles",
            "profile.CLONE/profileMANAGED",
            "profile.CLONE/ profile!evil",
            "android.os.usertype.profile.CLONE/profile!evil",
        )
        for (value in cases) {
            assertEquals(
                "fake second token must be OTHER (not UNKNOWN): $value",
                ProfileUserType.OTHER,
                parseSingleVerboseType(value),
            )
        }
        // Probe-locked (Phase B fixer): a non-dot dual token is still a
        // genuine two-token contradiction -> UNKNOWN, not OTHER.
        // `profile_CLONE` is an allowlisted CLONE token (`_` is an
        // enumerated separator) and `profile_MANAGED` is an other token,
        // so the mention takes the contradiction rule (UNKNOWN), same as
        // the dotted `profile.CLONE/profile.MANAGED` form. Verified by
        // probe run (verbose + dumpsys both UNKNOWN) before locking.
        assertEquals(
            "non-dot dual token must stay UNKNOWN: profile_CLONE/profile_MANAGED",
            ProfileUserType.UNKNOWN,
            parseSingleVerboseType("profile_CLONE/profile_MANAGED"),
        )
    }

    // NOTE (R7 T3): no new legal-dual-token test added here --
    // `slashSeparatedManagedAndClone_staysUnknown` already locks both
    // slash directions (`profile.MANAGED/profile.CLONE` and
    // `profile.CLONE/profile.MANAGED`) plus the FQ form as UNKNOWN.

    // ---- Phase A `(default)` note: tolerated suffix vs slashed note ----
    // `(default)` (case-insensitive, optional inner/outer whitespace, slashes
    // absent) is the sole tolerated suffix -> CLONE. A slash or any other
    // note content (`(a/b)`, `(evil)`, `[evil]`) fails closed to OTHER.
    @Test
    fun cloneParenthesizedDefaultNote_staysClone_andSlashedNote_isOther() {
        val cloneCases = listOf(
            "profile.CLONE (default)",
            "profile.CLONE (DEFAULT)",
            "profile.CLONE(default)",
        )
        for (value in cloneCases) {
            assertEquals(
                "default note must stay CLONE: $value",
                ProfileUserType.CLONE,
                parseSingleVerboseType(value),
            )
        }
        val otherCases = listOf(
            "profile.CLONE (a/b)",
            "profile.CLONE (evil)",
            "profile.CLONE [evil]",
        )
        for (value in otherCases) {
            assertEquals(
                "non-default/slashed note must be OTHER: $value",
                ProfileUserType.OTHER,
                parseSingleVerboseType(value),
            )
        }
    }

    // ---- Issue #58 S24 real `Type:` format (Stage1) ----
    // S24 (SM-S9210, Android 17) dumpsys user emits bare `Type:` lines
    // (e.g. `Type: android.os.usertype.profile.CLONE`) with no `user`
    // keyword. TYPE_LINE alone misses them; BARE_TYPE_LINE (anchored to
    // line start) picks them up. Discrimination is by normalized post-colon
    // token tail (trim/case-insensitive): CLONE -> CLONE, known non-clone
    // (PRIVATE/SYSTEM/MANAGED/...) -> OTHER, unrecognized -> block UNKNOWN
    // (fail-closed, never OTHER/Absent).

    private fun parseSingleBareTypeLine(typeLine: String): ProfileIdentity {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                $typeLine
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        return parsed[0]
    }

    private fun parseSingleBareType(value: String): ProfileUserType =
        parseSingleBareTypeLine("Type: $value").userType

    @Test
    fun s24BareTypeClone_parsesAsCloneWithParentAndSerial() {
        val parsed = parseSingleBareTypeLine("Type: android.os.usertype.profile.CLONE")
        assertEquals(ProfileUserType.CLONE, parsed.userType)
        assertEquals(95, parsed.userId)
        assertEquals(0, parsed.parentId)
        assertEquals(15, parsed.serial)
        assertEquals(IdentityConfidence.MED, parsed.confidence)
    }

    @Test
    fun s24BareTypePrivateAndSystem_parseAsOther() {
        val cases = listOf(
            "android.os.usertype.profile.PRIVATE",
            "android.os.usertype.full.SYSTEM",
            "android.os.usertype.full.SECONDARY",
            "android.os.usertype.profile.MANAGED",
            "OTHER",
            "PRIVATE",
        )
        for (value in cases) {
            assertEquals(
                "bare Type value must be OTHER: $value",
                ProfileUserType.OTHER,
                parseSingleBareType(value),
            )
        }
    }

    @Test
    fun s24BareType_caseAndSpaceVariants() {
        // Key case + separator spacing variants (trim/case-insensitive).
        val cloneLines = listOf(
            "Type: android.os.usertype.profile.CLONE",
            "type: android.os.usertype.profile.clone",
            "TYPE: ANDROID.OS.USERTYPE.PROFILE.CLONE",
            "Type : android.os.usertype.profile.CLONE",
            "Type:   android.os.usertype.profile.CLONE  ",
            "Type=android.os.usertype.profile.CLONE",
            "Type = android.os.usertype.profile.CLONE",
            "  Type: android.os.usertype.profile.CLONE",
        )
        for (line in cloneLines) {
            assertEquals(
                "bare Type line must stay CLONE: [$line]",
                ProfileUserType.CLONE,
                parseSingleBareTypeLine(line).userType,
            )
        }
        val otherLines = listOf(
            "Type: android.os.usertype.profile.PRIVATE",
            "type: android.os.usertype.profile.private",
            "TYPE : android.os.usertype.profile.PRIVATE",
            "Type=android.os.usertype.profile.PRIVATE",
            "Type: PRIVATE",
            "type : other",
        )
        for (line in otherLines) {
            assertEquals(
                "bare Type line must stay OTHER: [$line]",
                ProfileUserType.OTHER,
                parseSingleBareTypeLine(line).userType,
            )
        }
    }

    @Test
    fun s24BareType_unknownToken_yieldsUnknownNotOther() {
        // Fail-closed: unrecognized tails must be UNKNOWN (block Absent),
        // never OTHER. Each is the ONLY type mention, so UNKNOWN proves the
        // tail alone refuses (not an OTHER mixture).
        val cases = listOf(
            "foo.BAR",
            "garbage",
            "android.os.usertype.foo.BAR",
            "unknown-stuff",
        )
        for (value in cases) {
            assertEquals(
                "bare Type unknown must be UNKNOWN (fail-closed, never OTHER): $value",
                ProfileUserType.UNKNOWN,
                parseSingleBareType(value),
            )
        }
    }

    @Test
    fun s24BareType_unknownProfileTail_yieldsUnknownNotOther() {
        // Fail-closed FQ guard: `full.`/`system.` short-circuit to OTHER,
        // but `profile.` must still validate the tail. An unknown profile
        // tail (FQ form) is UNKNOWN, never OTHER -- otherwise
        // `profile.FOO_BAR` would fail open to OTHER/Absent.
        assertEquals(
            ProfileUserType.UNKNOWN,
            parseSingleBareType("android.os.usertype.profile.FOO_BAR"),
        )
    }

    @Test
    fun s24BareType_leadingSpaceSuperuserTypeStaysUnknown() {
        // Line-start anchor: `  superuser type: profile.CLONE` (leading
        // whitespace, but `type` not at line start) must not hit
        // BARE_TYPE_LINE -- stays UNKNOWN via the existing left-boundary
        // guard, same as the legacy `superuser type:` behavior.
        // NOTE: trimIndent would strip the leading spaces under test, so
        // the block is built with explicit "\n" joins to keep them.
        val text = "Users:\n" +
            "  UserInfo{95:DualApp:400} running:\n" +
            "    Serial: 15\n" +
            "    parentId=0\n" +
            "  superuser type: profile.CLONE"
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun s24BareType_emptyValue_yieldsUnknown() {
        // Empty bare values carry no type evidence: skipped, block UNKNOWN.
        // BARE_TYPE_LINE `(.+)` does not even match a valueless line.
        val lines = listOf(
            "Type:",
            "Type:   ",
        )
        for (line in lines) {
            assertEquals(
                "empty bare Type line must stay UNKNOWN: [$line]",
                ProfileUserType.UNKNOWN,
                parseSingleBareTypeLine(line).userType,
            )
        }
    }

    @Test
    fun s24BareType_midLineTypeStaysUnknown() {
        // Anchored guard: bare `Type:` only counts at line start. Mid-line
        // `note=Type: profile.CLONE` (like the `superuser type:` guard) must
        // stay UNKNOWN, never CLONE. Short `profile.CLONE` form is used so
        // the separator-less fallback (which keys on a `user...type`
        // substring) does not trip on an FQ `usertype` token inside the
        // value; the point here is the line-start anchor, not the FQ tail.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                note=Type: profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun superuserFqCloneFallbackStaysUnknown() {
        // FQ gap: `superuser type: android.os.usertype.profile.CLONE` must
        // stay UNKNOWN, never OTHER. The fallback keyword must not trip on
        // the FQ-internal `usertype` (dot-guarded); the leading
        // `superuser type:` is already blocked by the left-boundary guard.
        // Explicit "\n" joins keep the leading spaces (trimIndent would
        // strip them, as in s24BareType_leadingSpaceSuperuserTypeStaysUnknown).
        val text = "Users:\n" +
            "  UserInfo{95:DualApp:400} running:\n" +
            "    Serial: 15\n" +
            "    parentId=0\n" +
            "  superuser type: android.os.usertype.profile.CLONE"
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun midLineNoteFqCloneFallbackStaysUnknown() {
        // FQ gap (companion): mid-line `note=Type: FQ.CLONE` must stay
        // UNKNOWN, never OTHER. Same dot-guard: the only `usertype`
        // substring sits inside the FQ value (`.usertype.`), so the
        // separator-less fallback carries no keyword.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                note=Type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun s24RealDumpsys_endToEndConfirmsWithSerial() {
        // S24 real shape: new-ROM verbose (type= + parentId, no serial) plus
        // dumpsys UserInfo blocks with bare `Type:` + header serial/parent.
        // Triple agreement (verbose CLONE + dumpsys CLONE + prop 95) confirms.
        val verbose = """
            3 users:

            0: id=0, name=Owner, type=full.SYSTEM, flags=ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM (running) (current) (visible)
            1: id=95, name=DUAL_APP, type=profile.CLONE (flags=DUALAPP_PROFILE|INITIALIZED|PROFILE) (parentId=0) (running) (visible)
            2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=INITIALIZED|PROFILE|SDP_NOT_SUPPORTED_SECURE_FOLDER|SECURE_FOLDER (parentId=0) (running) (profile-owner) (visible)
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{0:xxx:4c13} serialNo=0 isPrimary=true
                Type: android.os.usertype.full.SYSTEM
                Flags: 19475 (ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM)
                State: RUNNING_UNLOCKED
              UserInfo{95:xxx:20001010} serialNo=15 isPrimary=false parentId=0
                Type: android.os.usertype.profile.CLONE
                Flags: 536875024 (DUALAPP_PROFILE|INITIALIZED|PROFILE)
                State: RUNNING_UNLOCKED
              UserInfo{150:xxx:61010} serialNo=16 isPrimary=false parentId=0
                Type: android.os.usertype.profile.PRIVATE
                Flags: 397328 (INITIALIZED|PROFILE|SDP_NOT_SUPPORTED_SECURE_FOLDER|SECURE_FOLDER)
                State: RUNNING_UNLOCKED
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertEquals(3, parsed.size)
        val byId = parsed.associateBy { it.userId }
        assertEquals(ProfileUserType.OTHER, byId[0]!!.userType)
        assertEquals(ProfileUserType.CLONE, byId[95]!!.userType)
        assertEquals(0, byId[95]!!.parentId)
        assertEquals(15, byId[95]!!.serial)
        assertEquals(ProfileUserType.OTHER, byId[150]!!.userType)
        val candidates = SamsungProfileAdapter.parseUserListVerbose(verbose) +
            SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertEquals(DualResolution.Confirmed(95, 15, 0), SamsungProfileAdapter.resolveDual(candidates, propertyId = 95))
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 15, parentId = 0),
            SamsungProfileAdapter.resolveDualTarget(candidates, propertyId = 95),
        )
        assertEquals(95, PmOutputParser.resolveDualUserIdVerified(verbose, dumpsys, 95))
    }

    @Test
    fun s24RealDumpsys_conflictingPrivateRefused() {
        // Same id typed CLONE on verbose but PRIVATE (OTHER) on dumpsys via
        // bare `Type:` -> conflicting type evidence -> Refused (fail-closed).
        val verbose = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{95:DualApp:400} serialNo=15:
                parentId=0
                Type: android.os.usertype.profile.PRIVATE
        """.trimIndent()
        val candidates = SamsungProfileAdapter.parseUserListVerbose(verbose) +
            SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertTrue(candidates.any { it.userType == ProfileUserType.CLONE })
        assertTrue(candidates.any { it.userType == ProfileUserType.OTHER })
        assertTrue(SamsungProfileAdapter.resolveDual(candidates, propertyId = 95) is DualResolution.Refused)
    }

    @Test
    fun s24RealDumpsys_bothPrivateTypedAbsent() {
        // Clean no-dual via bare `Type:`: both channels OTHER (SYSTEM +
        // PRIVATE), identical sets, clean no-claim property -> Absent
        // (confirmed absent, maps to NoDual), never Unknown.
        val verbose = """
            Users:
              UserInfo{0:Owner:13} running:
                Serial number: 0
                Type: android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                Type: android.os.usertype.profile.PRIVATE
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                Type: android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} serialNo=15:
                parentId=0
                Type: android.os.usertype.profile.PRIVATE
        """.trimIndent()
        val gateway = CloneUnknownFakeGateway(verboseOut = verbose, dumpsysOut = dumpsys)
        val repo = DualRepository(gateway)
        assertEquals(DualIdResolution.Absent, runSuspend { repo.resolveDualUserIdTyped() })
    }

    @Test
    fun s24RealDumpsys_unknownTokenTypedUnknownNotAbsent() {
        // Fail-closed: unknown bare `Type:` tails stay UNKNOWN on both
        // channels, so typed resolution is Unknown (never Absent/NoDual).
        val verbose = """
            Users:
              UserInfo{0:Owner:13} running:
                Serial number: 0
                Type: android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                Type: foo.BAR
        """.trimIndent()
        val dumpsys = """
            Users:
              UserInfo{0:Owner:13} serialNo=0 isPrimary=true:
                Type: android.os.usertype.full.SYSTEM
              UserInfo{95:DualApp:400} serialNo=15:
                parentId=0
                Type: foo.BAR
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseDumpsysUser(dumpsys)
        assertEquals(ProfileUserType.UNKNOWN, parsed.single { it.userId == 95 }.userType)
        val gateway = CloneUnknownFakeGateway(verboseOut = verbose, dumpsysOut = dumpsys)
        val repo = DualRepository(gateway)
        val res = runSuspend { repo.resolveDualUserIdTyped() }
        assertTrue("unknown bare Type must be Unknown (never Absent/NoDual), got: $res", res is DualIdResolution.Unknown)
    }

    // ---- PR #59 review hardening: taint/category/interaction regressions ----

    @Test
    fun s24BareType_taintedCloneFailsClosedAsOther() {
        // Tainted/residual-demoted CLONE token on a bare `Type:` line must
        // fail closed (never CLONE) and, by the established TYPE_LINE
        // contract (cloneSlashTail_isOtherNotClone / trailing-punct), resolve
        // as OTHER for that mention -- not UNKNOWN. The bare path detects a
        // supported clone token demoted by classifyMention and treats it as
        // OTHER, consistent with the TYPE_LINE path.
        val cases = listOf(
            "profile.CLONE/evil",
            "android.os.usertype.profile.CLONE/evil",
            "profile.CLONE/",
            "android.os.usertype.profile.CLONE/",
            "profile.CLONE / evil",
            "profile.CLONE!evil",
            "android.os.usertype.profile.CLONE!evil",
            "profile_CLONE!evil",
            "profile-CLONE!evil",
        )
        for (value in cases) {
            assertEquals(
                "tainted bare Type must be OTHER (fail-closed, never CLONE): $value",
                ProfileUserType.OTHER,
                parseSingleBareType(value),
            )
        }
        // Parity: same tainted value on explicit TYPE_LINE path stays OTHER.
        assertEquals(ProfileUserType.OTHER, parseSingleVerboseType("profile.CLONE/evil"))
        assertEquals(ProfileUserType.OTHER, parseSingleVerboseType("android.os.usertype.profile.CLONE/evil"))
    }

    @Test
    fun s24BareType_embeddedFullSystemSubstringIsUnknown() {
        // Regression for prefix/boundary-anchored full/system category check:
        // an embedded `full.`/`system.` substring (not at category boundary)
        // must NOT classify as OTHER; with an unknown tail it fails closed
        // to UNKNOWN (never CLONE).
        val cases = listOf(
            "subfull.UNKNOWN",
            "mysystem.WEIRD",
            "xandroid.os.usertype.full.SECONDARY",
        )
        for (value in cases) {
            assertEquals(
                "embedded full/system substring must not be OTHER: $value",
                ProfileUserType.UNKNOWN,
                parseSingleBareType(value),
            )
        }
    }

    @Test
    fun s24BareType_fullAndSystemUnknownTailsAreOtherByCategory() {
        // `full.*`/`system.*` are known non-clone usertype categories: any
        // tail under these categories is OTHER (never CLONE). Pins the
        // intentional category short-circuit (authorization-safe); absence
        // certainty for an unknown tail in these categories is traded for
        // availability, as documented in isKnownOtherBareValue.
        val cases = listOf(
            "android.os.usertype.full.FOO_UNKNOWN",
            "full.WHATEVER",
            "system.weird",
            "android.os.usertype.system.STRANGE",
        )
        for (value in cases) {
            assertEquals(
                "full/system category tail must be OTHER (never CLONE): $value",
                ProfileUserType.OTHER,
                parseSingleBareType(value),
            )
        }
    }

    @Test
    fun s24BareType_knownBareTailsAndShortProfileForms() {
        // Bare known tails (no dots) resolve via tail allowlist; short
        // profile.* forms exercise classifyMention on the bare path.
        val otherValues = listOf(
            "GUEST", "demo", "restricted", "HEADLESS", "car",
            "profile.PRIVATE", "profile.private", "profile.MANAGED",
            "android.os.usertype.profile.PRIVATE (default)",
        )
        for (value in otherValues) {
            assertEquals(
                "known bare/short form must be OTHER: $value",
                ProfileUserType.OTHER,
                parseSingleBareType(value),
            )
        }
        assertEquals(ProfileUserType.CLONE, parseSingleBareType("profile.CLONE"))
        assertEquals(ProfileUserType.CLONE, parseSingleBareType("profile.clone"))
        assertEquals(
            ProfileUserType.CLONE,
            parseSingleBareType("android.os.usertype.profile.CLONE (default)"),
        )
    }

    @Test
    fun s24BareType_mixedCloneAndManagedInOneLineIsUnknown() {
        assertEquals(
            ProfileUserType.UNKNOWN,
            parseSingleBareType("android.os.usertype.profile.MANAGED android.os.usertype.profile.CLONE"),
        )
        assertEquals(
            ProfileUserType.UNKNOWN,
            parseSingleBareType("profile.MANAGED, profile.CLONE"),
        )
    }

    @Test
    fun repeatedBareTypeLinesWithCloneAndPrivate_isUnknown() {
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                Type: android.os.usertype.profile.CLONE
                Type: android.os.usertype.profile.PRIVATE
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.UNKNOWN, parsed[0].userType)
    }

    @Test
    fun mixedTypeLineAndBareType_contradictionIsUnknown() {
        // Step 1 accumulates across TYPE_LINE and BARE_TYPE_LINE: a CLONE on
        // one and a known-non-clone on the other is a cross-mention
        // contradiction -> UNKNOWN.
        val verboseThenBare = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
                Type: android.os.usertype.profile.PRIVATE
        """.trimIndent()
        assertEquals(
            ProfileUserType.UNKNOWN,
            SamsungProfileAdapter.parseUserListVerbose(verboseThenBare).single().userType,
        )
        val bareThenVerbose = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                Type: android.os.usertype.profile.CLONE
                User type: android.os.usertype.profile.PRIVATE
        """.trimIndent()
        assertEquals(
            ProfileUserType.UNKNOWN,
            SamsungProfileAdapter.parseUserListVerbose(bareThenVerbose).single().userType,
        )
        // Agreement across forms is still consistent.
        val agree = """
            Users:
              UserInfo{95:DualApp:400} running:
                Serial: 15
                parentId=0
                User type: android.os.usertype.profile.CLONE
                Type: android.os.usertype.profile.CLONE
        """.trimIndent()
        assertEquals(ProfileUserType.CLONE, SamsungProfileAdapter.parseUserListVerbose(agree).single().userType)
    }

    @Test
    fun separatorLessClone_withFqRemainder_yieldsClone() {
        // Regression for TYPE_KEYWORD dot-guard / FQ handling: separator-less
        // `usertype` keyword (step 2) with an FQ CLONE remainder must still
        // classify CLONE; the FQ-internal `usertype.` must not be re-matched.
        val text = """
            Users:
              UserInfo{95:DualApp:400} running:
                usertype android.os.usertype.profile.CLONE
                parentId=0
        """.trimIndent()
        val parsed = SamsungProfileAdapter.parseUserListVerbose(text)
        assertEquals(1, parsed.size)
        assertEquals(ProfileUserType.CLONE, parsed[0].userType)
        assertEquals(0, parsed[0].parentId)
        val managed = """
            Users:
              UserInfo{10:Work:1030} running:
                usertype android.os.usertype.profile.MANAGED
        """.trimIndent()
        assertEquals(
            ProfileUserType.OTHER,
            SamsungProfileAdapter.parseUserListVerbose(managed).single().userType,
        )
    }
}
