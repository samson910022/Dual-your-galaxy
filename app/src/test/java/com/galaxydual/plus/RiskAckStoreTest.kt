// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-1 (M1): JVM 測試一次風險揭露 acknowledgement。
// - RiskAckStore / shouldShowRiskGate 為純 Kotlin seam，Map-backed fake 可测。
// - 讀取失敗 (任何 Throwable) -> false (fail-safe:重新顯示風險)，不拋出例外。
// - 生產綁定以 in-memory SharedPreferences 驗證單鍵 commit() (非 apply())，
//   並如實回報 commit 結果；測試用 dispatch seam 同步化背景寫入。
//   此為路徑模擬，不證明 Android 磁碟持久化時序。
package com.galaxydual.plus

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAckStoreTest {

    private class MapBackedRiskAckStore : RiskAckStore {
        private val acks = linkedMapOf<String, Boolean>()
        override fun isAcknowledged(): Boolean = acks[RISK_ACK_KEY] ?: false
        override fun markAcknowledged(): Boolean {
            acks[RISK_ACK_KEY] = true
            return true
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
        // #76: commit 仍為唯一寫入方式，但現在必須如實回報 durable=true。
        val prefs = RecordingPrefs()
        val store = prefs.riskAckStore(dispatch = { it() }) // 同步化背景 IO seam
        assertFalse("fresh prefs must be unacknowledged", store.isAcknowledged())
        assertTrue("verified commit must be reported durable", store.markAcknowledged())
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
    fun riskAckStore_throwingWriteReportsNotDurableAndGateReopens() {
        // #76:寫入例外不再被無聲吞掉 —— 呼叫端必須收到 false，且 gate 保持開啟。
        val prefs = object : BasePrefs() {
            override fun edit(): SharedPreferences.Editor = throw IllegalStateException("simulated corrupt write")
        }
        val store = prefs.riskAckStore(dispatch = { it() })
        assertFalse("failed write must be reported NOT durable", store.markAcknowledged())
        assertTrue("failed write must keep the gate open", shouldShowRiskGate(store.isAcknowledged()))
    }

    // =====================================================================
    // #76 RED REGRESSION TESTS - RiskAckStore durability / IO ownership (historical)
    //
    // Historical note: these were authored as RED tests at 5621769a. Against the
    // PRE-FIX API/behavior they failed on assertion because:
    //   * markAcknowledged(): Unit ignored commit(false) - the UI caller had no
    //     way to know the acknowledgement was not durable, so it could not retry.
    //   * markAcknowledged() called synchronous commit() on the CALLER (UI) thread.
    // With the #76 fix applied they are now GREEN; the current API already
    // returns Boolean and dispatches the write to RiskAckIoOwner.
    //
    // #76 fix (now implemented): report the truthful durability outcome so the
    // caller can retry, and run commit() on a single-thread IO owner. The caller
    // still does a BOUNDED synchronous wait (RISK_ACK_IO_WAIT_MS = 400ms) for the
    // real result — this is NOT zero-blocking, just capped UI jank.
    // =====================================================================

    // SharedPreferences fake whose commit() result is controlled by the test.
    // commitOk=false discards the pending write, exactly like a real
    // SharedPreferences whose disk write failed (commit(false)).
    private class ControlledCommitPrefs(var commitOk: Boolean = false) : BasePrefs() {
        val values = linkedMapOf<String, Any?>()
        var commitCount = 0
            private set
        var applyCount = 0
            private set

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (values[key!!] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key!!)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { pending.remove(key!!); return this }
            override fun clear(): SharedPreferences.Editor { pending.clear(); return this }
            override fun commit(): Boolean {
                commitCount++
                if (commitOk) values.putAll(pending)
                pending.clear()
                return commitOk
            }
            override fun apply() {
                applyCount++
                values.putAll(pending)
                pending.clear()
            }
        }
    }

    @Test
    fun ackCommitFailure_doesNotTreatAsDurable() {
        // #76 RED: the production binding calls edit().putBoolean(...).commit()
        // and IGNORES the Boolean. markAcknowledged() is `Unit`, so the caller
        // (showRiskAckGate positive button) cannot tell that commit(false) meant
        // "not durable" and cannot retry. FIX: return/report the commit outcome
        // (Boolean or Result<Unit>) and let the caller decide; verified durability.
        //
        // Reflection keeps this compiling against the CURRENT (Unit) API while
        // still failing TODAY, because the method currently returns void. After
        // production changes the signature, replace the reflection block with a
        // direct `val durable: Boolean = store.markAcknowledged()` assertion.
        val prefs = ControlledCommitPrefs(commitOk = false)
        val store = prefs.riskAckStore()

        val method = RiskAckStore::class.java.getMethod("markAcknowledged")
        assertTrue(
            "#76 RED: markAcknowledged must report the commit outcome (Boolean/Result), not void/Unit",
            method.returnType != java.lang.Void.TYPE,
        )

        val outcome = method.invoke(store)
        if (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java) {
            assertFalse(
                "#76 RED: commit(false) must be reported as NOT durable",
                outcome as Boolean,
            )
        }
        assertEquals("a commit was attempted", 1, prefs.commitCount)
        assertFalse(
            "commit(false) left nothing durable",
            prefs.values[RISK_ACK_KEY] as? Boolean ?: false,
        )
    }

    @Test
    fun ackCommitRunsOffCallingThreadWithBoundedWait() {
        // Historical RED test (5621769a): pre-fix markAcknowledged() performed a
        // synchronous commit() on the caller (the UI thread). Here commit() blocks
        // on a latch: the durable write now runs on RiskAckIoOwner and the caller
        // returns after a BOUNDED synchronous wait (RISK_ACK_IO_WAIT_MS ~400ms),
        // NOT immediately and NOT never-blocking. The 600ms observation window
        // only proves the wait is capped well below the 5s blocked commit.
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val prefs = object : BasePrefs() {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val pending = linkedMapOf<String, Any?>()
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun remove(key: String?): SharedPreferences.Editor { pending.remove(key!!); return this }
                override fun clear(): SharedPreferences.Editor { pending.clear(); return this }
                override fun commit(): Boolean {
                    commitEntered.countDown()
                    try {
                        releaseCommit.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    return true
                }
                override fun apply() = Unit
            }
        }
        val store = prefs.riskAckStore()

        val returned = CountDownLatch(1)
        val caller = Thread {
            try {
                store.markAcknowledged()
            } finally {
                returned.countDown()
            }
        }
        caller.isDaemon = true
        caller.start()
        try {
            assertTrue("commit must have been entered", commitEntered.await(3, TimeUnit.SECONDS))
            val returnedBeforeRelease = returned.await(600, TimeUnit.MILLISECONDS)
            assertTrue(
                "commit runs off the calling thread; the caller returns after at most " +
                    "RISK_ACK_IO_WAIT_MS (~400ms), well before the 5s blocked commit",
                returnedBeforeRelease,
            )
        } finally {
            releaseCommit.countDown()
            caller.join(2_000L)
        }
    }
}
