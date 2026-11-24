// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase A (HIGH-1): JVM pin for the FRAMEWORK-FREE classifier and its
// read seam. Every branch (including hostile reads and cancellation) is driven
// directly, plus one production-boundary integration case proving an unknown
// classification present in prefs aborts the whole store.save().
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.hook.BbcPreactivationHookReader
import com.galaxydual.hook.PolicySource
import com.galaxydual.hook.VersionProbeResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BbcInstallClassificationTest {

    private class FakeReads(
        var classification: String? = null,
        var packagesNonEmpty: Boolean = false,
        var version: VersionProbeResult = VersionProbeResult.Absent,
        var classificationThrows: Throwable? = null,
        var packagesThrows: Throwable? = null,
        var versionThrows: Throwable? = null,
    ) : BbcClassificationReads {
        override fun classificationValue(): String? {
            classificationThrows?.let { throw it }
            return classification
        }

        override fun packagesNonEmpty(): Boolean {
            packagesThrows?.let { throw it }
            return packagesNonEmpty
        }

        override fun versionProbe(): VersionProbeResult {
            versionThrows?.let { throw it }
            return version
        }
    }

    // Minimal writable prefs for the integration case (fail closed before any
    // editor, so this fake deliberately counts editor/commit attempts).
    private class CountingPrefs(
        initial: Map<String, Any?> = emptyMap(),
    ) : SharedPreferences {
        val data = LinkedHashMap(initial)
        var editCalls = 0
            private set
        var commitCalls = 0
            private set
        val writtenKeys = mutableListOf<String>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor {
            editCalls++
            return object : SharedPreferences.Editor {
                private val pending = LinkedHashMap<String, Any?>()
                private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                    writtenKeys.add(key!!)
                    pending[key] = value
                    return this
                }
                override fun putString(key: String?, value: String?) = put(key, value)
                override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
                override fun putInt(key: String?, value: Int) = put(key, value)
                override fun putLong(key: String?, value: Long) = put(key, value)
                override fun putFloat(key: String?, value: Float) = put(key, value)
                override fun putBoolean(key: String?, value: Boolean) = put(key, value)
                override fun remove(key: String?): SharedPreferences.Editor = put(key, null).also { pending.remove(key) }
                override fun clear(): SharedPreferences.Editor {
                    pending.clear()
                    return this
                }
                override fun commit(): Boolean {
                    commitCalls++
                    data.putAll(pending)
                    return true
                }
                override fun apply() = Unit
            }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    @Before
    fun setUp() {
        resetRemotePoisonForTests()
    }

    private val preUpgrade = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING
    private val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED

    // ---- full branch matrix of the pure classifier ----

    @Test
    fun knownValue_returnsNoWrite() {
        for (value in PolicySource.INSTALL_CLASSIFICATION_VALUES) {
            assertEquals(BbcClassificationPlan.NoWrite, planBbcInstallClassification(FakeReads(classification = value)))
        }
    }

    @Test
    fun unknownPresentValue_failsClosed() {
        assertEquals(BbcClassificationPlan.FailClosed, planBbcInstallClassification(FakeReads(classification = "BOGUS")))
    }

    @Test
    fun absentWithNonEmptyPackages_writesPreUpgradeWithMarker() {
        assertEquals(
            BbcClassificationPlan.Write(preUpgrade, true),
            planBbcInstallClassification(FakeReads(packagesNonEmpty = true)),
        )
    }

    @Test
    fun absentWithPresentVersion_writesPreUpgradeWithMarker() {
        assertEquals(
            BbcClassificationPlan.Write(preUpgrade, true),
            planBbcInstallClassification(FakeReads(version = VersionProbeResult.Present(7L))),
        )
    }

    @Test
    fun absentWithNeitherEvidence_writesNewGenerationWithoutMarker() {
        assertEquals(
            BbcClassificationPlan.Write(newGeneration, false),
            planBbcInstallClassification(FakeReads()),
        )
    }

    @Test
    fun absentWithInvalidVersion_stillWritesNewGeneration() {
        // Invalid is corrupt local data, not a transport failure: it degrades to
        // "no version evidence" so the corrupt-version repair can proceed.
        assertEquals(
            BbcClassificationPlan.Write(newGeneration, false),
            planBbcInstallClassification(FakeReads(version = VersionProbeResult.Invalid)),
        )
    }

    @Test
    fun unavailableVersion_failsClosed() {
        assertEquals(
            BbcClassificationPlan.FailClosed,
            planBbcInstallClassification(FakeReads(packagesNonEmpty = true, version = VersionProbeResult.Unavailable)),
        )
    }

    @Test
    fun packagesReadClassCastException_isTreatedAsNoEvidence() {
        assertEquals(
            BbcClassificationPlan.Write(newGeneration, false),
            planBbcInstallClassification(FakeReads(packagesThrows = ClassCastException("corrupt draft"))),
        )
    }

    @Test
    fun packagesReadOtherThrowable_failsClosed() {
        assertEquals(
            BbcClassificationPlan.FailClosed,
            planBbcInstallClassification(FakeReads(packagesThrows = IllegalStateException("IPC failed"))),
        )
    }

    @Test
    fun classificationReadFailure_failsClosed() {
        assertEquals(
            BbcClassificationPlan.FailClosed,
            planBbcInstallClassification(FakeReads(classificationThrows = IllegalStateException("IPC failed"))),
        )
    }

    @Test
    fun versionReadThrowable_failsClosed() {
        assertEquals(
            BbcClassificationPlan.FailClosed,
            planBbcInstallClassification(FakeReads(versionThrows = IllegalStateException("IPC failed"))),
        )
    }

    @Test(expected = CancellationException::class)
    fun cancellationException_alwaysPropagates() {
        planBbcInstallClassification(FakeReads(versionThrows = CancellationException("cancelled")))
    }

    // ---- production boundary: a BOGUS persisted value aborts store.save ----

    @Test
    fun unknownPersistedClassification_abortsEntireSaveAndLeavesDraftIdentical() {
        val oldPackages = setOf("com.example.old")
        val prefs = CountingPrefs(
            mapOf(
                PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION to "BOGUS",
                PolicySource.POLICY_KEY_EXTRA_PACKAGES to oldPackages,
                PolicySource.POLICY_KEY_EXTRA_VERSION to 7L,
            ),
        )

        assertFalse(remoteHookPolicyStore { prefs }.save(setOf("com.example.new")))

        assertEquals(0, prefs.editCalls)
        assertEquals(0, prefs.commitCalls)
        assertTrue(prefs.writtenKeys.isEmpty())
        assertSame(oldPackages, prefs.data[PolicySource.POLICY_KEY_EXTRA_PACKAGES])
        assertEquals(7L, prefs.data[PolicySource.POLICY_KEY_EXTRA_VERSION])
        assertFalse(BbcPreactivationHookReader.effectOpen(true, prefs))
    }
}
