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
        assertEquals(DualResolution.Confirmed(95), SamsungProfileAdapter.resolveDual(candidates))
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 15),
            SamsungProfileAdapter.resolveDualTarget(candidates),
        )
    }

    // End-to-end repository gate (PR-33 S1): V=CLONE/serial15 plus
    // D=UNKNOWN/serial15 resolves to NoDual (id never Confirmed), so
    // removeVerifiedExpected runs zero uninstalls (fail closed). Uses a
    // local fake gateway in the DualSafetyTest style (core has no
    // coroutines dependency, hence the minimal runSuspend helper).
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
        assertTrue("expected NoDual, got: $outcome", outcome is RemoveOutcome.NoDual)
        assertEquals(0, gateway.uninstallRuns)
        assertTrue(
            "no uninstall may run on CLONE+UNKNOWN, got: ${gateway.commands}",
            gateway.commands.none { it.startsWith("pm uninstall") },
        )
    }

    // PR-33 D2 (S24 實機格式): new-ROM `cmd user list -v` has no UserInfo
    // header and no serial line. Fixture embeds the S24 evidence verbatim
    // (3-user verbose block) plus the matching dumpsys 0/95/150 blocks.
    private val S24_VERBOSE = """
        3 users:

        0: id=0, name=陳孝宣, type=full.SYSTEM, flags=ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM (running) (current) (visible)
        1: id=95, name=DUAL_APP, type=profile.CLONE, flags=DUALAPP_PROFILE|INITIALIZED|PROFILE (parentId=0) (running) (visible)
        2: id=150, name=Secure Folder, type=profile.PRIVATE, flags=INITIALIZED|PROFILE|SDP_NOT_SUPPORTED_SECURE_FOLDER|SECURE_FOLDER (parentId=0) (running) (profile-owner) (visible)
    """.trimIndent()

    private val S24_DUMPSYS = """
        Users:
          UserInfo{0:xxx:4c13} serialNo=0 isPrimary=true
            Type: android.os.usertype.full.SYSTEM
            Flags: 19475 (ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM)
            State: RUNNING_UNLOCKED
          UserInfo{95:xxx:20001010} serialNo=95 isPrimary=false parentId=0
            Type: android.os.usertype.profile.CLONE
            Flags: 536875024 (DUALAPP_PROFILE|INITIALIZED|PROFILE)
            State: RUNNING_UNLOCKED
          UserInfo{150:xxx:61010} serialNo=150 isPrimary=false parentId=0
            Type: android.os.usertype.profile.PRIVATE
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
        assertEquals(DualResolution.Confirmed(95), SamsungProfileAdapter.resolveDual(candidates))
        // Verbose carries no serial; the dumpsys serialNo=95 alone binds.
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 95),
            SamsungProfileAdapter.resolveDualTarget(candidates),
        )
        assertEquals(
            95,
            PmOutputParser.resolveDualUserIdVerified(S24_VERBOSE, S24_DUMPSYS),
        )
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 95),
            PmOutputParser.resolveDualTargetVerified(S24_VERBOSE, S24_DUMPSYS),
        )
    }

    // PR-33 D3 (S24 實機 blocking, hermetic 最小復現): dumpsys 尾部全域段
    // (User types 目錄含 android.os.usertype.profile.CLONE 字樣 +
    // Allowlisted 行) 不得污染最後一個 user 塊。Users 段 0/95/150 三塊，
    // 150 塊為 PRIVATE；舊 parseBlocks 讓末塊 body 延到 EOF，把目錄的 CLONE
    // 字樣算進 150 -> cloneIds={95,150} -> Refused -> 全機 NoDual。
    @Test
    fun dumpsysTrailingCatalog_doesNotLeakIntoLastBlock() {
        val verbose = """
            3 users:

            0: id=0, name=Owner, type=full.SYSTEM, flags=ADMIN|FULL|INITIALIZED|MAIN|PRIMARY|SYSTEM (running) (current) (visible)
            1: id=95, name=DUAL_APP, type=profile.CLONE, flags=DUALAPP_PROFILE|INITIALIZED|PROFILE (parentId=0) (running) (visible)
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
            VerifiedDualTarget(userId = 95, serial = 15),
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
            VerifiedDualTarget(userId = 95, serial = 95),
            SamsungProfileAdapter.resolveDualTarget(
                listOf(
                    claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
                    claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS, serial = 95),
                ),
            ),
        )
        // Dumpsys missing, verbose carrying: symmetric single source binds.
        assertEquals(
            VerifiedDualTarget(userId = 95, serial = 15),
            SamsungProfileAdapter.resolveDualTarget(
                listOf(
                    claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V, serial = 15),
                    claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
                ),
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
            ),
        )
        // Neither side carries a serial: still refused (no silent target).
        assertNull(
            SamsungProfileAdapter.resolveDualTarget(
                listOf(
                    claim(95, ProfileUserType.CLONE, IdentitySource.USER_LIST_V),
                    claim(95, ProfileUserType.CLONE, IdentitySource.DUMPSYS),
                ),
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
}
