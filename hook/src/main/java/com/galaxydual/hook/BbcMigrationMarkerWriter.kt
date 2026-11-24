// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// PR5 Phase B1 (#80) review fix: framework-free, SINGLE-KEY migration marker
// write seam. No SharedPreferences / Android / I/O here — production adapts the
// real SharedPreferences.Editor once in DualHook, JVM tests supply a recording
// fake (BbcMigrationMarkerTest).
//
// ADR decision 2 ("不得刪除、清空或改寫既有政策規則") is enforced BY TYPE here:
// [BbcMigrationMarkerEditor] exposes ONLY putBoolean + commit. There is no
// remove / clear / generic Editor access anywhere on this write path, so it is
// compile-time impossible for this code to delete `extra_whitelist_packages`
// or `extra_whitelist_version`. The key and value are hardcoded below rather
// than caller-supplied: the only write this seam can perform is
// POLICY_KEY_BBC_LEGACY_MIGRATED = true.
//
// Returns the real commit() result (false = binder/persist failure). Never
// throws: a throw from the editor is mapped to false so the caller can count
// / log the failure without the write ever changing the gate decision.
internal interface BbcMigrationMarkerEditor {
    fun putBoolean(key: String, value: Boolean)
    fun commit(): Boolean
}

internal fun writeBbcLegacyMigrationMarker(editor: BbcMigrationMarkerEditor): Boolean = try {
    editor.putBoolean(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED, true)
    editor.commit()
} catch (_: Throwable) {
    false
}
