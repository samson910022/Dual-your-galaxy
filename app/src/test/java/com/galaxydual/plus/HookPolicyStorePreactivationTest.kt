// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR5 Phase B2 (#80): App 端 pre-activation opt-in 寫入 API 的 JVM 綁定測試。
// - 缺席/讀取失敗 -> false（未 opt-in，fail-closed）。
// - 單鍵 commit -> Confirmed(true)；commit(false)/throw -> Unknown（不謊稱成功）。
// - 寫入只碰 POLICY_KEY_BBC_PREACTIVATION_OPTIN，絕不碰 packages/version/migration marker。
// - 同步 commit、不是 apply()；與既有 BBC opt-out 同一 dual_policy group/鎖。
// 以太坊後端為 in-memory SharedPreferences fake（JVM 無 Robolectric）；跨行程可見性
// 仍需 on-device 證明（DEVICE-UNVERIFIED，見 HookPolicyStore 檔頭）。
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.hook.PolicySource
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookPolicyStorePreactivationTest {

    private companion object {
        const val OPTIN_KEY = "bbc_preactivation_optin"
    }

    // 與 HookPolicyRemoteStoreTest.RecordingPrefs 同類型的精簡 fake：
    // commit() 先 flush 樂觀 mMap 再回 success/throw（模擬 RemotePreferences），
    // 並記錄所有 put*/remove/clear，用來證明 opt-in 寫入是真正的單鍵寫入。
    private class OptinPrefs : SharedPreferences {
        val data = linkedMapOf<String, Any?>()
        var commitCalls = 0
            private set
        var applyCalls = 0
            private set
        var commitSucceeds = true
        var commitThrows = false
        /**
         * A daemon may persist and then lose the reply (pinned by
         * persistedThenReplyThrows_isUnknownEvenThoughKeyIsVisible). false models
         * the opposite case: a commit the daemon REFUSED, so no key is durable.
         */
        var flushOnFailedCommit = true
        var booleanReadThrows = false
        val writtenKeys = mutableListOf<String>()
        val removedKeys = mutableListOf<String>()
        var clearCalls = 0
            private set

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            if (data[key] is String) data[key] as String else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return data[key] as? MutableSet<String> ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            if (booleanReadThrows) throw RuntimeException("boolean read failed")
            return (data[key] as? Boolean) ?: defValue
        }
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                writtenKeys.add(key!!)
                pending[key] = value
                removed.remove(key)
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor {
                removedKeys.add(key!!)
                pending.remove(key)
                removed.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearCalls++
                clearAll = true
                pending.clear()
                return this
            }
            override fun commit(): Boolean {
                if (commitSucceeds || flushOnFailedCommit) flush()
                commitCalls++
                if (commitThrows) throw RuntimeException("commit binder dead")
                return commitSucceeds
            }
            override fun apply() {
                flush()
                applyCalls++
            }

            private fun flush() {
                if (clearAll) data.clear()
                for (k in removed) data.remove(k)
                for ((k, v) in pending) data[k] = v
                pending.clear()
                removed.clear()
                clearAll = false
            }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    @Test
    fun optinKeyLiteralIsStable() {
        assertEquals("dual_policy", PolicySource.POLICY_PREFS_NAME)
        assertEquals(OPTIN_KEY, PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN)
    }

    @Test
    fun absentOptinReadsFalseWithoutAnyWrite() {
        val prefs = OptinPrefs()
        val store = remoteHookPolicyStore { prefs }
        assertFalse("absent opt-in key must mean NOT opted in", store.isBbcPreactivationOptIn())
        assertEquals(0, prefs.commitCalls)
        assertTrue(prefs.writtenKeys.isEmpty())
    }

    @Test
    fun confirmedOptinWriteIsSingleCommitAndVisibleToNewStore() {
        val prefs = OptinPrefs()
        val store = remoteHookPolicyStore { prefs }
        val outcome = store.setBbcPreactivationOptInDetailed(true)
        assertEquals(BbcWriteOutcome.Confirmed(true), outcome)
        assertTrue("legacy Boolean wrapper must agree with Confirmed", store.setBbcPreactivationOptIn(true))
        assertEquals(2, prefs.commitCalls)
        assertEquals("must use synchronous commit, never apply", 0, prefs.applyCalls)
        assertEquals(listOf(OPTIN_KEY, OPTIN_KEY), prefs.writtenKeys)
        assertTrue(store.isBbcPreactivationOptIn())
        assertTrue("a fresh store over the same group must see the opt-in",
            remoteHookPolicyStore { prefs }.isBbcPreactivationOptIn())
    }

    // --- Phase 2 marker invariant: BEHAVIOUR, not source text ----------------

    @Test
    fun consentGrantWritesOptinAndMarkerInOneCommit() {
        val prefs = OptinPrefs()
        val store = remoteHookPolicyStore { prefs }

        assertEquals(BbcWriteOutcome.Confirmed(true), store.setBbcConsentGrantDetailed())

        assertEquals("one edit, one synchronous commit", 1, prefs.commitCalls)
        assertEquals("never apply(): the grant must be durable before its callback",
            0, prefs.applyCalls)
        assertEquals(
            "the opt-in and app-only marker must be written in the SAME commit",
            listOf(OPTIN_KEY, BBC_ENABLE_PENDING_PREF_KEY),
            prefs.writtenKeys,
        )
        assertEquals(true, prefs.data[OPTIN_KEY])
        assertEquals(true, prefs.data[BBC_ENABLE_PENDING_PREF_KEY])
    }

    @Test
    fun runtimeWriteClearsMarkerInOneCommit() {
        val prefs = OptinPrefs()
        prefs.data[OPTIN_KEY] = true
        prefs.data[BBC_ENABLE_PENDING_PREF_KEY] = true // enable still owed after grant
        prefs.data[PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED] = true
        val store = remoteHookPolicyStore { prefs }

        assertEquals(
            BbcWriteOutcome.Confirmed(false),
            store.setBbcRuntimeAndEnablePendingDetailed(false),
        )

        assertEquals(1, prefs.commitCalls)
        assertEquals(0, prefs.applyCalls)
        assertEquals(
            "runtime value and marker must be written in the SAME commit",
            listOf(PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED, BBC_ENABLE_PENDING_PREF_KEY),
            prefs.writtenKeys,
        )
        assertEquals(false, prefs.data[PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED])
        assertEquals(false, prefs.data[BBC_ENABLE_PENDING_PREF_KEY])
        assertEquals("the opt-in grant is untouched by a runtime OFF", true, prefs.data[OPTIN_KEY])
    }

    @Test
    fun refusedConsentGrantCommitLeavesNeitherKeyDurable() {
        val prefs = OptinPrefs()
        prefs.commitSucceeds = false
        prefs.flushOnFailedCommit = false // the daemon refused the write entirely
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.setBbcConsentGrantDetailed() is BbcWriteOutcome.Unknown)
        assertEquals(1, prefs.commitCalls)
        assertFalse("no opt-in may be durable after a refused commit", prefs.data.containsKey(OPTIN_KEY))
        assertFalse("no enable marker may be durable after a refused commit",
            prefs.data.containsKey(BBC_ENABLE_PENDING_PREF_KEY))
    }

    @Test
    fun refusedRuntimeCommitDoesNotClearTheMarker() {
        val prefs = OptinPrefs()
        prefs.data[PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED] = true
        prefs.data[BBC_ENABLE_PENDING_PREF_KEY] = true
        prefs.commitSucceeds = false
        prefs.flushOnFailedCommit = false
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.setBbcRuntimeAndEnablePendingDetailed(false) is BbcWriteOutcome.Unknown)
        assertEquals(1, prefs.commitCalls)
        assertEquals(true, prefs.data[PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED])
        assertEquals("the still-owed enable must survive a refused runtime write",
            true, prefs.data[BBC_ENABLE_PENDING_PREF_KEY])
    }

    @Test
    fun failedOptinCommitReturnsUnknownNeverConfirmed() {
        val prefs = OptinPrefs()
        prefs.commitSucceeds = false
        val store = remoteHookPolicyStore { prefs }
        val outcome = store.setBbcPreactivationOptInDetailed(true)
        assertTrue("commit(false) must be Unknown", outcome is BbcWriteOutcome.Unknown)
        assertFalse("Unknown must never be reported as Confirmed", outcome is BbcWriteOutcome.Confirmed)
        assertFalse("Boolean wrapper must not lie about a failed write",
            store.setBbcPreactivationOptIn(true))
    }

    @Test
    fun throwingOptinCommitReturnsUnknownNeverPropagates() {
        val prefs = OptinPrefs()
        prefs.commitThrows = true
        val store = remoteHookPolicyStore { prefs }
        val outcome = store.setBbcPreactivationOptInDetailed(true)
        assertTrue("throwing commit must map to Unknown without propagating",
            outcome is BbcWriteOutcome.Unknown)
        assertFalse(store.setBbcPreactivationOptIn(true))
    }

    @Test
    fun readFailuresFailClosedToNotOptedIn() {
        val throwingRead = OptinPrefs()
        throwingRead.booleanReadThrows = true
        assertFalse(remoteHookPolicyStore { throwingRead }.isBbcPreactivationOptIn())
        assertFalse("provider absent must mean NOT opted in",
            remoteHookPolicyStore { null }.isBbcPreactivationOptIn())
        assertFalse("provider throwing must mean NOT opted in",
            remoteHookPolicyStore { throw RuntimeException("service dead") }.isBbcPreactivationOptIn())
    }

    @Test
    fun optinWriteNeverTouchesPackagesVersionOrMigrationMarker() {
        val prefs = OptinPrefs()
        val draft = setOf("com.example.keep")
        prefs.data[PolicySource.POLICY_KEY_EXTRA_PACKAGES] = draft
        prefs.data[PolicySource.POLICY_KEY_EXTRA_VERSION] = 7L
        prefs.data[PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED] = true
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.setBbcPreactivationOptIn(true))
        assertTrue(store.setBbcPreactivationOptInDetailed(true) is BbcWriteOutcome.Confirmed)

        assertEquals("only the opt-in key may be written", setOf(OPTIN_KEY), prefs.writtenKeys.toSet())
        assertTrue("no key may be removed", prefs.removedKeys.isEmpty())
        assertEquals(0, prefs.clearCalls)
        assertEquals(draft, prefs.data[PolicySource.POLICY_KEY_EXTRA_PACKAGES])
        assertEquals(7L, prefs.data[PolicySource.POLICY_KEY_EXTRA_VERSION])
        assertEquals(true, prefs.data[PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED])
    }

    @Test(expected = CancellationException::class)
    fun optinReadPropagatesCancellationLikeTheExistingBbcReader() {
        remoteHookPolicyStore { throw CancellationException("cancelled") }.isBbcPreactivationOptIn()
    }

    @Test
    fun persistedThenReplyThrows_isUnknownEvenThoughKeyIsVisible() {
        // The fake flushes the optimistic map BEFORE the reply throws: a real
        // daemon may have persisted the key while the binder reply was lost.
        // The commit outcome must stay honest (Unknown / ambiguous), and a
        // follow-up read is NOT an acknowledgement.
        val prefs = OptinPrefs()
        prefs.commitThrows = true
        val store = remoteHookPolicyStore { prefs }
        val outcome = store.setBbcPreactivationOptInDetailed(true)
        assertTrue("a lost reply must be Unknown, never Confirmed", outcome is BbcWriteOutcome.Unknown)
        assertTrue("the optimistic map may already show the key", prefs.data[OPTIN_KEY] == true)
        assertTrue("a read of the optimistic map is not confirmation", store.isBbcPreactivationOptIn())
        assertEquals(true, store.readBbcPreactivationOptInAuthoritative())
        assertEquals("ambiguous commit: still only Unknown", BbcWriteOutcome.Unknown, outcome)
        assertFalse("the Boolean wrapper must not lie either", store.setBbcPreactivationOptIn(true))
    }

    @Test
    fun migrationMarkerAuthoritativeRead_usesContainsAndFailsClosed() {
        val prefs = OptinPrefs()
        val store = remoteHookPolicyStore { prefs }
        assertEquals(false, store.readBbcLegacyMigratedAuthoritative()) // definite absent
        prefs.data[PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED] = true
        assertEquals(true, store.readBbcLegacyMigratedAuthoritative())
        prefs.data[PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED] = false
        assertEquals(false, store.readBbcLegacyMigratedAuthoritative())
        prefs.booleanReadThrows = true
        assertNull("a failed marker read must be null, never a grant",
            store.readBbcLegacyMigratedAuthoritative())
        assertNull("no service instance means unreadable",
            remoteHookPolicyStore { null }.readBbcLegacyMigratedAuthoritative())
    }
}
