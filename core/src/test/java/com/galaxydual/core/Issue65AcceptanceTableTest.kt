// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// Issue #65 parameterized acceptance table: neither the renamed user's id nor
// the display-only `name=` label may affect authorization. 3 renamed users
// (owner 0 / dual 95 / other 150) x 11 labels (error keywords, a comma, an
// injected `type=` declaration, an inline UserInfo header, multiple commas,
// flags-like text BEFORE the real `, type=`, and three colon-bearing names
// from the R13 legacy-label compatibility matrix) = 33 cases. Only UNAMBIGUOUS
// compat labels are listed here; a composite label with an earlier
// error-semantic declaration (e.g. `, type=Error, ...`) is intentionally NOT
// display-only and must fail the strict gate -- see VerboseUserRow KDoc and
// Issue65RefreshVerifiedTest.compositeLabelWithEarlierErrorDeclaration_*.
// Every case is checked at three layers: the strict identity AND pm-list-users
// gates, the structured parser's userType against a clean-label baseline, and
// the FULL refreshVerified() result against that same baseline (with absolute
// target/listing values pinned so two wrong answers cannot agree).
@RunWith(Parameterized::class)
class Issue65AcceptanceTableTest(
    private val renamedUserId: Int,
    private val label: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "renamedUid={0}, label={1}")
        fun cases(): Collection<Array<Any>> {
            val users = listOf(0, 95, 150)
            val labels = listOf(
                "Error",
                "Failure",
                "not found",
                "Dual, App",
                "foo, type=profile.CLONE",
                "UserInfo{95:Error:400}",
                // Multiple commas: a rename may legally contain them; they are
                // display-only and must not perturb the type/tail parse.
                "Dual, App, Jr.",
                // flags-like text BEFORE the real `, type=`: it stays inside
                // the neutralized name span (nameEnd = first `, type=`), so it
                // is display-only and the real CLONE declaration still wins.
                "foo, flags=X",
                // R13: a user rename may legally contain a colon (AOSP does
                // not quote it). Display-only in both the new `name=` span
                // and the shared legacy UserInfo label parser.
                "Work:Error",
                "a:Failure",
                "a:not found",
            )
            return users.flatMap { uid ->
                labels.map { displayName -> arrayOf<Any>(uid, displayName) }
            }
        }
    }

    // Same synthetic snapshot as the other issue #65 tests; only the row for
    // [renamedId] gets its name= value swapped. Display indices are ignored by
    // the parser and kept stable across variants.
    private fun verbose(renamedId: Int, name: String): String {
        fun row(display: Int, id: Int, displayName: String, type: String, flags: String) =
            "  $display: id=$id, name=$displayName, type=$type, flags=$flags (parentId=0)"
        return "Users:\n" + listOf(
            row(0, 0, if (renamedId == 0) name else "Owner", "full.SYSTEM", "0x00000013"),
            row(1, 95, if (renamedId == 95) name else "DUAL_APP", "profile.CLONE", "0x00000190"),
            row(2, 150, if (renamedId == 150) name else "Secure Folder", "profile.PRIVATE", "0x00000030"),
        ).joinToString("\n")
    }

    @Test
    fun renamedUserAndLabelAreDisplayOnlyAtEveryLayer() {
        val withLabel = verbose(renamedUserId, label)
        val clean = verbose(renamedUserId, "NEUTRAL")
        val case = "renamedUid=$renamedUserId, label=$label"

        assertTrue(
            "identity channel must stay OK for a display-only label ($case): $withLabel",
            PmOutputParser.isIdentityChannelOk(withLabel, 0),
        )
        assertTrue(
            "pm-list-users channel must stay OK for a display-only label ($case): $withLabel",
            PmOutputParser.isListUsersOutputOk(withLabel, 0),
        )

        val dirtyType = SamsungProfileAdapter.parseUserListVerbose(withLabel)
            .first { it.userId == renamedUserId }.userType
        val cleanType = SamsungProfileAdapter.parseUserListVerbose(clean)
            .first { it.userId == renamedUserId }.userType
        assertEquals("userType must not depend on the display label ($case)", cleanType, dirtyType)

        // The existing fail-fast recording FakeGateway (unexpected commands
        // still throw) is reused for BOTH sides of the comparison.
        val actual = Issue65RefreshVerifiedTest.runSuspend {
            DualRepository(Issue65RefreshVerifiedTest.FakeGateway(withLabel)).refreshVerified()
        }
        val baseline = Issue65RefreshVerifiedTest.runSuspend {
            DualRepository(Issue65RefreshVerifiedTest.FakeGateway(clean)).refreshVerified()
        }
        assertEquals("full refreshVerified result must equal clean baseline ($case)", baseline, actual)
        assertTrue("expected GroundTruth ($case), got: $actual", actual is RefreshOutcome.GroundTruth)
        val truth = actual as RefreshOutcome.GroundTruth
        assertEquals(95, truth.target.userId)
        assertEquals(15, truth.target.serial)
        assertEquals(0, truth.target.parentId)
        assertEquals(listOf("com.example.chat", "com.example.mail"), truth.cloned)
    }
}
