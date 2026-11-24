// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #74 R7b-1 (M1): JVM 測試一次風險揭露 acknowledgement。
// - RiskAckStore / shouldShowRiskGate 為純 Kotlin seam，Map-backed fake 可测。
// - 讀取失敗 (任何 Throwable) -> false (fail-safe:重新顯示風險)，不拋出例外。
// - 生產綁定以 in-memory SharedPreferences 驗證單鍵 commit() (非 apply())，
//   並如實回報 commit 結果；測試用 dispatch seam 同步化背景寫入。
// - PR #89 D1 (R2, fail-open):SplitCommitPrefs 忠實模擬 AOSP commitToMemory()
//   (memory 先更新、disk 只於成功寫入、失敗不回滾)，驗證失敗 ack 之後的 gate
//   由行程級 ProcessRiskAckState 決定，而不是被 mMap 污染的 getBoolean()。
package com.galaxydual.plus

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RiskAckStoreTest {

    // The ack owner is process-scoped (that is the whole point of the R2 fix),
    // so every test must start and end with a pristine owner.
    @Before
    fun resetProcessOwnerBefore() {
        ProcessRiskAckState.resetForTests()
    }

    @After
    fun resetProcessOwnerAfter() {
        ProcessRiskAckState.resetForTests()
    }

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

    // PR #89 D1:FAITHFUL split of AOSP SharedPreferencesImpl, unlike the old
    // ControlledCommitPrefs (which discarded pending on commit(false) and so
    // hid the exact fail-open bug under test):
    //   - commitToMemory() ALWAYS applies pending to the process-local mMap
    //     (`memory`) FIRST, even when the disk write fails;
    //   - the disk file (`disk`) is written ONLY on success and is never rolled
    //     back — so memory=true / disk=false is a legal post-failure state;
    //   - a cold start rebuilds `memory` from `disk`.
    // `beforeDiskWrite` models the disk-write step: it may block on a latch and
    // its Boolean return is the commit() result.
    private class SplitCommitPrefs(
        initialDisk: Map<String, Any?> = emptyMap(),
    ) : BasePrefs() {
        val disk = linkedMapOf<String, Any?>().apply { putAll(initialDisk) }
        val memory = linkedMapOf<String, Any?>().apply { putAll(disk) }

        @Volatile
        var beforeDiskWrite: () -> Boolean = { true }

        var commitCount = 0
            private set
        var applyCount = 0
            private set
        val writes = mutableListOf<Pair<String?, Any?>>()

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (memory[key!!] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = memory.containsKey(key!!)

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
                writes.addAll(pending.map { it.key to it.value })
                memory.putAll(pending) // AOSP commitToMemory: ALWAYS first, never rolled back.
                val ok = beforeDiskWrite()
                if (ok) disk.putAll(pending) // disk only on success
                pending.clear()
                return ok
            }
            override fun apply() {
                applyCount++
                memory.putAll(pending)
                pending.clear()
            }
        }
    }

    // =====================================================================
    // PR #89 Phase D1 (R2, fail-open):AOSP commitToMemory() updates the
    // process-local mMap BEFORE the disk write and never reverts on failure.
    // The old ControlledCommitPrefs discarded pending on commit(false), which
    // hid the bug: a failed ack left mMap=true, and the next entry read
    // getBoolean()==true and skipped the risk gate. These tests use the
    // faithful SplitCommitPrefs and a process-scoped owner instead.
    // =====================================================================

    @Test
    fun failedCommit_memoryTrueOwnerUnknown_gateStaysOpen() {
        val prefs = SplitCommitPrefs().apply { beforeDiskWrite = { false } }
        val store = prefs.riskAckStore(dispatch = { it() })

        assertFalse("failed commit must be reported NOT durable", store.markAcknowledged())
        assertEquals("AOSP commitToMemory updates memory even on commit(false)", true, prefs.memory[RISK_ACK_KEY])
        assertFalse("disk must NOT contain the ack", prefs.disk.containsKey(RISK_ACK_KEY))
        assertFalse("process owner must stay unacknowledged", store.isAcknowledged())
        assertTrue("failed commit must keep the gate open", shouldShowRiskGate(store.isAcknowledged()))
    }

    @Test
    fun secondWrapperAfterFailedCommit_gateStaysOpen() {
        val prefs = SplitCommitPrefs().apply { beforeDiskWrite = { false } }
        val first = prefs.riskAckStore(dispatch = { it() })
        assertFalse(first.markAcknowledged())
        assertEquals(true, prefs.memory[RISK_ACK_KEY])

        // A new wrapper re-reads the polluted mMap at construction, but the
        // durable seed was latched at the FIRST construction (before any commit)
        // and isAcknowledged() never reads prefs. The gate must stay open.
        val second = prefs.riskAckStore(dispatch = { it() })
        assertFalse("owner is process-scoped, not per-prefs-wrapper", second.isAcknowledged())
        assertTrue("a second wrapper must not re-open a failed ack", shouldShowRiskGate(second.isAcknowledged()))
    }

    @Test
    fun timeoutWhileCommitBlocked_gateStaysOpen() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val diskDone = CountDownLatch(1)
        val prefs = SplitCommitPrefs().apply {
            beforeDiskWrite = {
                commitEntered.countDown()
                releaseCommit.await(3, TimeUnit.SECONDS)
                diskDone.countDown()
                true
            }
        }
        // Fresh thread per test so a stuck commit cannot leak into other tests.
        // Keep a handle so the finally block can JOIN it: diskDone counts down
        // before commit() returns, and confirm()/fail() runs after that. Joining
        // prevents a late confirm() from landing after @After resets the owner.
        var commitThread: Thread? = null
        val store = prefs.riskAckStore(
            dispatch = { task ->
                Thread(task).apply {
                    isDaemon = true
                    commitThread = this
                }.start()
            },
            ioWaitMs = 25L,
        )
        try {
            assertFalse("blocked commit must time out to NOT durable", store.markAcknowledged())
            assertTrue("commit must have entered", commitEntered.await(2, TimeUnit.SECONDS))
            assertEquals("memory is already true after commitToMemory", true, prefs.memory[RISK_ACK_KEY])
            assertFalse("disk write is still blocked", prefs.disk.containsKey(RISK_ACK_KEY))
            assertFalse(store.isAcknowledged())
            assertTrue("gate must stay open while the commit is blocked", shouldShowRiskGate(store.isAcknowledged()))
        } finally {
            releaseCommit.countDown()
            assertTrue("blocked commit must finish", diskDone.await(3, TimeUnit.SECONDS))
            commitThread?.join(3_000L)
            assertFalse("commit task must return before @After resets the owner", commitThread?.isAlive == true)
        }
    }

    @Test
    fun lateSuccessAfterTimeout_confirmsOnlyIfLatest() {
        val prefs = SplitCommitPrefs()
        val tasks = mutableListOf<() -> Unit>()
        // Capture (do not run) the commit task; ioWaitMs=0 forces an immediate
        // timeout so the commit can be replayed later, deterministically.
        val store = prefs.riskAckStore(dispatch = { tasks += it }, ioWaitMs = 0L)

        // Write 1: timed out -> fail(1) -> UNKNOWN, gate open.
        assertFalse(store.markAcknowledged())
        assertTrue(shouldShowRiskGate(store.isAcknowledged()))
        assertEquals(1L, ProcessRiskAckState.currentWriteIdForTests())

        // Late success #1 with NO newer write: disk is truly true now -> CONFIRMED.
        tasks[0].invoke()
        assertEquals(true, prefs.disk[RISK_ACK_KEY])
        assertFalse("late success with no newer write must confirm", shouldShowRiskGate(store.isAcknowledged()))

        // Write 2 starts, then the newer write 3 starts (and later fails).
        assertFalse(store.markAcknowledged()) // id=2, timed out -> fail(2)
        assertTrue(shouldShowRiskGate(store.isAcknowledged()))
        assertFalse(store.markAcknowledged()) // id=3, timed out -> fail(3)
        assertEquals(3L, ProcessRiskAckState.currentWriteIdForTests())

        // Write 3's commit really fails; then write 2's success arrives late.
        // currentWriteId is 3, so confirm(2) MUST be ignored.
        prefs.beforeDiskWrite = { false }
        tasks[2].invoke() // write 3 -> fail(3) again (no-op)
        assertTrue(shouldShowRiskGate(store.isAcknowledged()))
        prefs.beforeDiskWrite = { true }
        tasks[1].invoke() // write 2's late success -> confirm(2) ignored
        assertTrue("late success must not confirm over a newer write", shouldShowRiskGate(store.isAcknowledged()))
        assertFalse("no stale success may flip the owner", store.isAcknowledged())
    }

    @Test
    fun coldStartSeedFromDurableDisk_whenDiskAcked_gateClosed() {
        // @Before already reset the owner; reset again to pin "cold start".
        ProcessRiskAckState.resetForTests()
        val prefs = SplitCommitPrefs(initialDisk = mapOf(RISK_ACK_KEY to true))
        assertEquals("cold start must rebuild memory from disk", true, prefs.memory[RISK_ACK_KEY])
        val store = prefs.riskAckStore(dispatch = { it() })
        assertTrue("durable ack must seed CONFIRMED at cold start", store.isAcknowledged())
        assertFalse(shouldShowRiskGate(store.isAcknowledged()))
        assertEquals("a durable seed needs no commit", 0, prefs.commitCount)
    }

    @Test
    fun reseedForTests_reopensThenReclosesGateWithoutNewCommit() {
        val prefs = RecordingPrefs()
        val store = prefs.riskAckStore(dispatch = { it() })
        assertTrue("confirmed ack must close the gate", store.markAcknowledged())
        assertTrue(store.isAcknowledged())
        assertEquals("ack committed exactly once", 1, prefs.commitCount)

        // androidTest helpers mutate prefs directly and never construct another
        // store, so only an explicit reseed can move the process-global owner.
        // If the seam failed to reset the latch, the assertion below fails.
        ProcessRiskAckState.reseedForTests(false)
        assertFalse("reseed(false) must reopen the gate", store.isAcknowledged())
        assertTrue(shouldShowRiskGate(store.isAcknowledged()))
        assertEquals(0L, ProcessRiskAckState.currentWriteIdForTests())
        assertEquals("reseed must not commit", 1, prefs.commitCount)

        ProcessRiskAckState.reseedForTests(true)
        assertTrue("reseed(true) must close the gate with no new commit", store.isAcknowledged())
        assertFalse(shouldShowRiskGate(store.isAcknowledged()))
        assertEquals(0L, ProcessRiskAckState.currentWriteIdForTests())
        assertEquals("reseed must not commit", 1, prefs.commitCount)
    }

    @Test
    fun cancelZeroAckZeroProceed() {
        val prefs = SplitCommitPrefs()
        val store = prefs.riskAckStore(dispatch = { it() })
        // The negative button is a null listener: cancel closes the dialog and
        // runs NEITHER markAcknowledged() NOR onContinue (see
        // RiskDisclosureSourceShapeTest.cancelPath_doesNotAcknowledgeOrContinue
        // for the source-shape half of this contract).
        assertFalse("cancel must not acknowledge", store.isAcknowledged())
        assertEquals("cancel must not commit", 0, prefs.commitCount)
        assertEquals("cancel must not allocate a write id", 0L, ProcessRiskAckState.currentWriteIdForTests())
        // Even a stale success token cannot flip an owner that never wrote.
        ProcessRiskAckState.confirm(1L)
        assertFalse("cancel must leave the owner untouched", store.isAcknowledged())
        assertEquals("stale token must not allocate a write id", 0L, ProcessRiskAckState.currentWriteIdForTests())
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
            // The commit task calls confirm() AFTER commit() returns. Queue a
            // barrier on the same single-thread IO owner so the whole task (including
            // confirm) has settled before @After resets the process-global owner;
            // otherwise a late confirm can leak into the next test.
            val ioOwnerSettled = CountDownLatch(1)
            RiskAckIoOwner.executor.execute { ioOwnerSettled.countDown() }
            assertTrue("IO owner must settle before the owner is reset", ioOwnerSettled.await(3, TimeUnit.SECONDS))
            caller.join(2_000L)
        }
    }

    // =====================================================================
    // PR #89 Phase D2a (R4/queue backlog):the UI gate uses the non-blocking
    // markAcknowledgedAsync(). Every test here uses latches (never sleeps) and
    // drains the single-thread IO owner before @After resets the process-global
    // owner, so a late confirm/fail cannot leak into the next test.
    // =====================================================================

    private fun settleIoOwner() {
        val settled = CountDownLatch(1)
        RiskAckIoOwner.executor.execute { settled.countDown() }
        assertTrue("IO owner must settle before the owner is reset", settled.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun asyncDefault_delegatesToSyncForFakes() {
        // The interface default is what keeps every existing fake/caller compiling.
        val store = MapBackedRiskAckStore()
        val reported = AtomicBoolean(false)
        store.markAcknowledgedAsync { ok -> reported.set(ok) }
        assertTrue("default async must delegate and forward the result", reported.get())
        assertFalse("default async must still persist the ack", shouldShowRiskGate(store.isAcknowledged()))
    }

    @Test
    fun asyncAck_returnsBeforeCommitCompletes() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val callback = CountDownLatch(1)
        val callbackOk = AtomicBoolean(false)
        val returned = CountDownLatch(1)
        val prefs = SplitCommitPrefs().apply {
            beforeDiskWrite = {
                commitEntered.countDown()
                releaseCommit.await(5, TimeUnit.SECONDS)
                true
            }
        }
        // Default dispatch => the real RiskAckIoOwner single-thread executor.
        val store = prefs.riskAckStore()
        val caller = Thread {
            try {
                store.markAcknowledgedAsync { ok ->
                    callbackOk.set(ok)
                    callback.countDown()
                }
            } finally {
                returned.countDown()
            }
        }
        caller.isDaemon = true
        caller.start()
        try {
            assertTrue("commit must be running on the IO owner", commitEntered.await(3, TimeUnit.SECONDS))
            assertTrue("async ack must return before the blocked commit completes",
                returned.await(1, TimeUnit.SECONDS))
            assertFalse("callback must not fire before durability is known",
                callback.await(100, TimeUnit.MILLISECONDS))
            assertFalse("gate must stay open while the commit is blocked", store.isAcknowledged())
        } finally {
            releaseCommit.countDown()
        }
        try {
            assertTrue("callback must fire after commit", callback.await(3, TimeUnit.SECONDS))
            assertTrue("durable async ack must report true", callbackOk.get())
            assertTrue("confirmed ack must close the gate", store.isAcknowledged())
            assertFalse(shouldShowRiskGate(store.isAcknowledged()))
        } finally {
            settleIoOwner()
            caller.join(2_000L)
        }
    }

    @Test
    fun asyncAckFailure_callbackFalse_gateStaysOpen() {
        val prefs = SplitCommitPrefs().apply { beforeDiskWrite = { false } }
        val store = prefs.riskAckStore()
        val callback = CountDownLatch(1)
        val callbackOk = AtomicBoolean(true)
        store.markAcknowledgedAsync { ok ->
            callbackOk.set(ok)
            callback.countDown()
        }
        try {
            assertTrue("callback must fire on commit(false)", callback.await(3, TimeUnit.SECONDS))
            assertFalse("failed commit must report false via callback", callbackOk.get())
            assertFalse("owner must stay UNKNOWN", store.isAcknowledged())
            assertTrue("failed async ack must keep the gate open", shouldShowRiskGate(store.isAcknowledged()))
            assertEquals(1L, ProcessRiskAckState.currentWriteIdForTests())
        } finally {
            settleIoOwner()
        }
    }

    @Test
    fun asyncAckSubmitRejection_failsClosed() {
        val prefs = RecordingPrefs()
        val callback = CountDownLatch(1)
        val callbackOk = AtomicBoolean(true)
        val store = prefs.riskAckStore(
            dispatch = { throw RejectedExecutionException("simulated executor rejection") },
        )
        store.markAcknowledgedAsync { ok ->
            callbackOk.set(ok)
            callback.countDown()
        }
        assertTrue("callback must fire on submit rejection", callback.await(1, TimeUnit.SECONDS))
        assertFalse("submit rejection must report false", callbackOk.get())
        assertFalse("owner must stay UNKNOWN", store.isAcknowledged())
        assertTrue("submit rejection must keep the gate open", shouldShowRiskGate(store.isAcknowledged()))
        assertEquals("no commit may be attempted", 0, prefs.commitCount)
        assertEquals(1L, ProcessRiskAckState.currentWriteIdForTests())
    }

    @Test
    fun asyncAckDoesNotWaitOnQueueBacklog() {
        val firstCommitEntered = CountDownLatch(1)
        val releaseFirstCommit = CountDownLatch(1)
        val firstCallback = CountDownLatch(1)
        val secondCallback = CountDownLatch(1)
        val firstOk = AtomicBoolean(false)
        val secondOk = AtomicBoolean(false)
        val firstWrite = AtomicBoolean(true)
        val prefs = SplitCommitPrefs().apply {
            beforeDiskWrite = {
                if (firstWrite.compareAndSet(true, false)) {
                    firstCommitEntered.countDown()
                    releaseFirstCommit.await(5, TimeUnit.SECONDS)
                }
                true
            }
        }
        val store = prefs.riskAckStore()
        store.markAcknowledgedAsync { ok ->
            firstOk.set(ok)
            firstCallback.countDown()
        }
        assertTrue("first commit must be pinned on the IO owner", firstCommitEntered.await(3, TimeUnit.SECONDS))

        val secondReturned = CountDownLatch(1)
        val caller = Thread {
            try {
                store.markAcknowledgedAsync { ok ->
                    secondOk.set(ok)
                    secondCallback.countDown()
                }
            } finally {
                secondReturned.countDown()
            }
        }
        caller.isDaemon = true
        caller.start()
        try {
            assertTrue("second async ack must return despite queue backlog",
                secondReturned.await(1, TimeUnit.SECONDS))
            assertFalse("second write is still queued behind the pinned commit",
                secondCallback.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirstCommit.countDown()
        }
        try {
            assertTrue("first callback must fire", firstCallback.await(3, TimeUnit.SECONDS))
            assertTrue("second callback must fire", secondCallback.await(3, TimeUnit.SECONDS))
            assertTrue(firstOk.get())
            assertTrue(secondOk.get())
            assertTrue("latest async ack must close the gate", store.isAcknowledged())
            assertEquals(2L, ProcessRiskAckState.currentWriteIdForTests())
        } finally {
            settleIoOwner()
            caller.join(2_000L)
        }
    }
}
