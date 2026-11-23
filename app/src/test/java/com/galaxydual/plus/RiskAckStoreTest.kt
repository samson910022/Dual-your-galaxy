// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-1 (M1): JVM 測試一次風險揭露 acknowledgement。
// - RiskAckStore / shouldShowRiskGate 為純 Kotlin seam，Map-backed fake 可测。
// - 讀取失敗 (任何 Throwable) -> false (fail-safe:重新顯示風險)，不拋出例外。
// - 生產綁定以 in-memory SharedPreferences 驗證單鍵 commit() (非 apply())。
//   此為路徑模擬，不證明 Android 磁碟持久化時序。
package com.galaxydual.plus

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAckStoreTest {

    private class MapBackedRiskAckStore : RiskAckStore {
        private val acks = linkedMapOf<String, Boolean>()
        override fun isAcknowledged(): Boolean = acks[RISK_ACK_KEY] ?: false
        override fun markAcknowledged() {
            acks[RISK_ACK_KEY] = true
        }
    }

    // 最小 SharedPreferences：唯讀 getter 回 default，edit 不應被前述 fake 呼叫。
    private open class BasePrefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = linkedMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("not used in this fake")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class ThrowingReadPrefs : BasePrefs() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            throw IllegalStateException("simulated corrupt SharedPreferences read")
    }

    private class RecordingPrefs : BasePrefs() {
        val values = linkedMapOf<String, Any?>()
        var commitCount = 0
            private set
        var applyCount = 0
            private set
        val writes = mutableListOf<Pair<String?, Any?>>()

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (values[key!!] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key!!)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key!!] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                pending.remove(key!!)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                return this
            }
            override fun commit(): Boolean {
                commitCount++
                writes.addAll(pending.map { it.key to it.value })
                values.putAll(pending)
                pending.clear()
                return true
            }
            override fun apply() {
                applyCount++
                values.putAll(pending)
                pending.clear()
            }
        }
    }

    @Test
    fun riskAckStore_constantsAreStable() {
        assertEquals("dual_policy_risk_disclosure", RISK_ACK_PREFS_NAME)
        assertEquals("acknowledged", RISK_ACK_KEY)
    }

    @Test
    fun shouldShowRiskGate_fullTruthTable() {
        assertTrue("unacknowledged must show the gate", shouldShowRiskGate(false))
        assertFalse("acknowledged must not show the gate", shouldShowRiskGate(true))
    }

    @Test
    fun mapBackedStore_ackThenReentryGateStaysClosed() {
        val store = MapBackedRiskAckStore()
        assertTrue("first entry must show the gate", shouldShowRiskGate(store.isAcknowledged()))
        store.markAcknowledged()
        assertFalse("re-entry must not show the gate again", shouldShowRiskGate(store.isAcknowledged()))
        store.markAcknowledged() // idempotent
        assertFalse("re-ack must stay closed", shouldShowRiskGate(store.isAcknowledged()))
    }

    @Test
    fun riskAckStore_marksAcknowledgedWithSingleCommittedBoolean() {
        val prefs = RecordingPrefs()
        val store = prefs.riskAckStore()
        assertFalse("fresh prefs must be unacknowledged", store.isAcknowledged())
        store.markAcknowledged()
        assertTrue(store.isAcknowledged())
        assertEquals("must use commit() for immediate read-after-write", 1, prefs.commitCount)
        assertEquals("must not use apply()", 0, prefs.applyCount)
        assertEquals("single boolean write to the ack key", listOf(RISK_ACK_KEY to true), prefs.writes)
        assertFalse("no other key may be written", prefs.values.any { it.key != RISK_ACK_KEY })
    }

    @Test
    fun riskAckStore_throwingReadFailsSafeToFalse() {
        val store = ThrowingReadPrefs().riskAckStore()
        assertFalse("read failure must mean show risk again", store.isAcknowledged())
    }

    @Test
    fun riskAckStore_throwingWriteIsSwallowedAndGateReopens() {
        val prefs = object : BasePrefs() {
            override fun edit(): SharedPreferences.Editor = throw IllegalStateException("simulated corrupt write")
        }
        val store = prefs.riskAckStore()
        store.markAcknowledged() // best effort: must not throw
        assertTrue("failed write must keep the gate open", shouldShowRiskGate(store.isAcknowledged()))
    }
}
