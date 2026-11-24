// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// PR5 Phase B1 (#80) review fix: JVM pins for the one-key migration marker
// writer. A recording editor proves the ONLY mutation this path can request is
// `POLICY_KEY_BBC_LEGACY_MIGRATED = true` followed by one commit(). There is no
// remove/clear on [BbcMigrationMarkerEditor] at all — ADR decision 2
// ("不得刪除、清空或改寫既有政策規則") is therefore a TYPE-level guarantee, not a
// comment. editorInterface_exposesNoRemoveOrClear() pins that shape so adding a
// destructive method later fails a test, and the mutation run documents that a
// policy-key remove inside the seam cannot even compile.
class BbcMigrationMarkerTest {

    private class RecordingEditor(
        private val commitResult: Boolean = true,
        private val throwOnPut: Boolean = false,
        private val throwOnCommit: Boolean = false,
    ) : BbcMigrationMarkerEditor {
        val puts = mutableListOf<Pair<String, Boolean>>()
        var commits = 0

        override fun putBoolean(key: String, value: Boolean) {
            if (throwOnPut) throw IllegalStateException("simulated put failure")
            puts += key to value
        }

        override fun commit(): Boolean {
            commits++
            if (throwOnCommit) throw IllegalStateException("simulated binder failure")
            return commitResult
        }
    }

    @Test
    fun writesOnlyMigrationMarkerKeyWithTrueValue() {
        val editor = RecordingEditor()

        val ok = writeBbcLegacyMigrationMarker(editor)

        assertTrue(ok)
        // Exactly one write, with the pinned key and value — nothing else is
        // offered by the interface, so no policy draft key can appear here.
        assertEquals(listOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true), editor.puts)
        assertEquals(1, editor.commits)
    }

    @Test
    fun editorInterface_exposesNoRemoveOrClear() {
        // Shape pin: no remove/clear/apply/generic-editor escape hatch exists.
        // A future mutation that widens the interface to delete policy keys is
        // killed by this assertion (and, for the seam itself, by the compiler).
        assertEquals(
            setOf("putBoolean", "commit"),
            BbcMigrationMarkerEditor::class.java.declaredMethods.map { it.name }.toSet(),
        )
    }

    @Test
    fun commitFalse_returnsFalseButKeepsTheSingleWrite() {
        val editor = RecordingEditor(commitResult = false)

        assertFalse(writeBbcLegacyMigrationMarker(editor))

        assertEquals(listOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true), editor.puts)
        assertEquals(1, editor.commits)
    }

    @Test
    fun commitThrow_returnsFalseAndNeverThrows() {
        val editor = RecordingEditor(throwOnCommit = true)

        assertFalse(writeBbcLegacyMigrationMarker(editor))

        assertEquals(listOf(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED to true), editor.puts)
        assertEquals(1, editor.commits)
    }

    @Test
    fun putThrow_returnsFalseAndSkipsCommit() {
        val editor = RecordingEditor(throwOnPut = true)

        assertFalse(writeBbcLegacyMigrationMarker(editor))

        assertTrue(editor.puts.isEmpty())
        assertEquals(0, editor.commits)
    }

    @Test
    fun failedMarkerWrite_doesNotFlipGateDecision() {
        // Wires the seam's Boolean result into the existing injected writer of
        // bbcPreactivationDecisionOpen: a failed persist is observable/countable
        // but the legacy-install effect stays OPEN for this call (ADR decision 2).
        var sawFailure = false
        val open = bbcPreactivationDecisionOpen(
            compileTimeGate = true,
            prefs = BbcPreactivationPrefs(null, true, null),
            writeMigrationMarker = {
                sawFailure = !writeBbcLegacyMigrationMarker(
                    RecordingEditor(commitResult = false),
                )
            },
        )

        assertTrue(open)
        assertTrue(sawFailure)
    }
}
