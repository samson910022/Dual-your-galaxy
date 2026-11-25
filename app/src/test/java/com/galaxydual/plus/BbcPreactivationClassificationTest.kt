// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase A (P0) — CROSS-BOUNDARY regression: drive the PRODUCTION
// remoteHookPolicyStore save/clear/repair path against an in-memory fake, then
// feed the resulting prefs into the PUBLIC Hook reader + gate. This test FAILS
// on the old code (where packages/version alone opened the gate) and passes
// only with a durable App-owned classification + read-only Hook.
//
// Two fake views model the daemon honestly:
//  - PersistedPrefs: commit(false) flushes NOTHING (nothing was persisted).
//  - OptimisticPrefs: commit(false) flushes ONLY the caller's LOCAL mMap, never
//    the DURABLE view the Hook reads. This is the P0 poison/retry trap.
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.hook.BbcPreactivationHookReader
import com.galaxydual.hook.BbcPreactivationPrefs
import com.galaxydual.hook.PolicySource
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BbcPreactivationClassificationTest {

    private val classificationKey = PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION
    private val markerKey = PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED
    private val optinKey = PolicySource.POLICY_KEY_BBC_PREACTIVATION_OPTIN
    private val packagesKey = PolicySource.POLICY_KEY_EXTRA_PACKAGES
    private val versionKey = PolicySource.POLICY_KEY_EXTRA_VERSION
    private val preUpgrade = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING
    private val newGeneration = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED

    // commit(false) flushes nothing (models "the daemon persisted nothing").
    private class PersistedPrefs : SharedPreferences {
        val data = linkedMapOf<String, Any?>()
        var commitSucceeds = true
        var commitThrows = false
        var editThrows = false
        var versionReadThrowsCancellation = false

        var editCalls = 0
            private set
        var commitCalls = 0
            private set
        val writtenKeys = mutableListOf<String>()
        val removedKeys = mutableListOf<String>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long {
            if (key == PolicySource.POLICY_KEY_EXTRA_VERSION && versionReadThrowsCancellation) {
                throw CancellationException("cancelled version read")
            }
            return (data[key] as? Long) ?: defValue
        }
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor {
            editCalls++
            if (editThrows) throw IllegalStateException("remote prefs unavailable")
            val owner = this
            return object : SharedPreferences.Editor {
                private val pending = linkedMapOf<String, Any?>()
                private val removed = mutableSetOf<String>()
                private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                    owner.writtenKeys.add(key!!)
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
                    owner.removedKeys.add(key!!)
                    pending.remove(key)
                    removed.add(key)
                    return this
                }
                override fun clear(): SharedPreferences.Editor {
                    pending.clear()
                    removed.addAll(owner.data.keys)
                    return this
                }
                override fun commit(): Boolean {
                    owner.commitCalls++
                    if (owner.commitThrows) throw IllegalStateException("binder dead")
                    if (!owner.commitSucceeds) return false // no optimistic flush
                    for (k in removed) owner.data.remove(k)
                    for ((k, v) in pending) owner.data[k] = v
                    return true
                }
                override fun apply() {
                    for (k in removed) owner.data.remove(k)
                    for ((k, v) in pending) owner.data[k] = v
                }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    // commit(false) flushes ONLY the app's local optimistic mMap. Getters read
    // LOCAL; the Hook reads DURABLE. This is exactly the P0 retry trap: after a
    // failed commit classificationValue() reads a "known" local value.
    private class OptimisticPrefs(
        initial: Map<String, Any?> = emptyMap(),
    ) : SharedPreferences {
        val durable = LinkedHashMap(initial)
        val local = LinkedHashMap(initial)
        var commitSucceeds = true
        var editCalls = 0
            private set
        var commitCalls = 0
            private set
        val writtenKeys = mutableListOf<String>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(local)
        override fun getString(key: String?, defValue: String?): String? = local[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (local[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (local[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (local[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (local[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (local[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = local.containsKey(key)
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
                override fun remove(key: String?): SharedPreferences.Editor {
                    writtenKeys.add(key!!)
                    pending.remove(key)
                    return this
                }
                override fun clear(): SharedPreferences.Editor {
                    pending.clear()
                    return this
                }
                override fun commit(): Boolean {
                    commitCalls++
                    local.putAll(pending) // optimistic mMap is polluted even on failure
                    if (!commitSucceeds) return false
                    durable.putAll(pending) // only a confirmed commit reaches the daemon
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
        // Also clears the app-scoped pending classification intent (test isolation).
        resetRemotePoisonForTests()
    }

    private fun hookOpen(prefs: SharedPreferences?): Boolean =
        BbcPreactivationHookReader.effectOpen(true, prefs)

    // The Hook runs in another process: it can only see the DURABLE view. Build
    // the snapshot from that map, but still decide with the production gate.
    private fun hookOpenDurable(prefs: OptimisticPrefs): Boolean =
        BbcPreactivationHookReader.effectOpen(
            true,
            BbcPreactivationPrefs(
                optIn = prefs.durable[optinKey] as? Boolean,
                classification = prefs.durable[classificationKey] as? String,
                migrated = prefs.durable[markerKey] as? Boolean,
            ),
        )

    private fun assertNoMigrationEvidence(prefs: PersistedPrefs) {
        assertNull("no classification may be persisted on failure", prefs.data[classificationKey])
        assertNull("no migration marker may be persisted on failure", prefs.data[markerKey])
        assertFalse("Hook must stay closed", hookOpen(prefs))
    }

    @Test
    fun freshInstall_normalNonEmptySave_staysClosed() {
        val prefs = PersistedPrefs()
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.save(setOf("com.example.new")))

        assertEquals(newGeneration, prefs.data[classificationKey])
        assertNull(prefs.data[markerKey])
        assertEquals(setOf("com.example.new"), prefs.data[packagesKey])
        assertFalse(hookOpen(prefs))
    }

    @Test
    fun freshInstall_emptySaveClearAndRepair_stayClosed() {
        val emptySave = PersistedPrefs()
        assertTrue("empty save is a legitimate draft write", remoteHookPolicyStore { emptySave }.save(emptySet()))
        assertEquals(newGeneration, emptySave.data[classificationKey])
        assertFalse(hookOpen(emptySave))

        val cleared = PersistedPrefs()
        assertTrue(remoteHookPolicyStore { cleared }.clear())
        assertEquals(newGeneration, cleared.data[classificationKey])
        assertFalse(hookOpen(cleared))

        val repaired = PersistedPrefs()
        assertEquals(
            "repair routes through saveRaw too",
            RepairResult.Repaired,
            remoteHookPolicyStore { repaired }.repairCorrupt(),
        )
        assertEquals(newGeneration, repaired.data[classificationKey])
        assertNull(repaired.data[markerKey])
        assertFalse(hookOpen(repaired))
    }

    @Test
    fun genuineUpgrade_bindTimeClassification_opensAndKeepsOldDraftByteIdentical() {
        val oldPackages = setOf("com.example.old")
        val prefs = PersistedPrefs().apply {
            data[packagesKey] = oldPackages
            data[versionKey] = 7L
        }
        val oldPackagesIdentity = prefs.data[packagesKey]
        val oldVersion = prefs.data[versionKey]

        // Models GalaxyApp.onServiceBind: lock-guarded writer.
        assertTrue(ensureBbcInstallClassification { prefs })

        assertEquals(preUpgrade, prefs.data[classificationKey])
        assertEquals(true, prefs.data[markerKey])
        assertTrue("old draft packages object must not be rewritten", oldPackagesIdentity === prefs.data[packagesKey])
        assertEquals("old version bytes must be untouched", oldVersion, prefs.data[versionKey])
        assertTrue("no policy draft key may be touched by classification", oldPackages == prefs.data[packagesKey])
        assertFalse("putStringSet/putLong must never appear", prefs.writtenKeys.contains(packagesKey) || prefs.writtenKeys.contains(versionKey))
        assertTrue(hookOpen(prefs))

        // A later ordinary save keeps the durable classification authoritative.
        val store = remoteHookPolicyStore { prefs }
        assertTrue(store.save(setOf("com.example.old", "com.example.new")))
        assertEquals(preUpgrade, prefs.data[classificationKey])
        assertTrue(hookOpen(prefs))
    }

    @Test
    fun genuineUpgrade_firstSaveClassifiesBeforeWritingTheDraft() {
        val prefs = PersistedPrefs().apply {
            data[packagesKey] = setOf("com.example.legacy")
            data[versionKey] = 2L
        }
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.save(setOf("com.example.legacy", "com.example.added")))

        assertEquals(preUpgrade, prefs.data[classificationKey])
        assertEquals(true, prefs.data[markerKey])
        assertTrue(hookOpen(prefs))
    }

    @Test
    fun explicitOptInGranted_opens() {
        val prefs = PersistedPrefs()
        val store = remoteHookPolicyStore { prefs }
        assertTrue(store.save(setOf("com.example.new")))
        assertFalse(hookOpen(prefs))

        assertTrue(store.setBbcPreactivationOptIn(true))

        assertEquals(true, prefs.data[optinKey])
        assertTrue(hookOpen(prefs))
    }

    @Test
    fun explicitOptOutFalse_closesEvenForMigratedInstall() {
        val prefs = PersistedPrefs().apply {
            data[classificationKey] = preUpgrade
            data[markerKey] = true
            data[packagesKey] = setOf("com.example.old")
            data[versionKey] = 3L
        }
        assertTrue(hookOpen(prefs))

        assertTrue(remoteHookPolicyStore { prefs }.setBbcPreactivationOptIn(false))

        assertEquals(false, prefs.data[optinKey])
        assertFalse(hookOpen(prefs))
    }

    @Test
    fun binderNull_commitFalse_and_throws_stayClosedWithNoEvidence() {
        // binder null: no prefs at all.
        val absentStore = remoteHookPolicyStore { null }
        assertFalse(absentStore.save(setOf("com.example.new")))
        assertFalse(hookOpen(null))

        // MEDIUM-4: a failed commit MUST actually be attempted — otherwise the
        // "no evidence persisted" assertion is tautological. The classification
        // IS put into the editor (P0: it shares the draft's transaction) but the
        // daemon persists NOTHING, and this fresh install has no migration marker.
        val failing = OptimisticPrefs().apply { commitSucceeds = false }
        assertFalse(remoteHookPolicyStore { failing }.save(setOf("com.example.new")))
        assertEquals(1, failing.commitCalls)
        assertTrue(failing.editCalls > 0)
        assertTrue("classification is part of the failed transaction", failing.writtenKeys.contains(classificationKey))
        assertFalse("a fresh install never gets a migration marker", failing.writtenKeys.contains(markerKey))
        assertNull(failing.durable[packagesKey])
        assertNull(failing.durable[classificationKey])
        assertNull(failing.durable[markerKey])
        assertFalse(hookOpenDurable(failing))

        // edit() throws (binder/IPC failure): no commit may be attempted.
        val editThrowing = PersistedPrefs().apply { editThrows = true }
        assertFalse(remoteHookPolicyStore { editThrowing }.save(setOf("com.example.new")))
        assertEquals(1, editThrowing.editCalls)
        assertEquals(0, editThrowing.commitCalls)
        assertNoMigrationEvidence(editThrowing)

        // commit() throws: a real commit was attempted but nothing was persisted.
        val commitThrowing = PersistedPrefs().apply { commitThrows = true }
        assertFalse(remoteHookPolicyStore { commitThrowing }.save(setOf("com.example.new")))
        assertEquals(1, commitThrowing.commitCalls)
        assertNoMigrationEvidence(commitThrowing)
    }

    @Test(expected = CancellationException::class)
    fun cancellationFromVersionProbePropagatesAndWritesNothing() {
        val prefs = PersistedPrefs().apply {
            data[versionKey] = 1L // force the probe to read (and throw CE)
            versionReadThrowsCancellation = true
        }
        try {
            remoteHookPolicyStore { prefs }.save(setOf("com.example.new"))
        } finally {
            assertEquals("no editor may be created on cancellation", 0, prefs.editCalls)
            assertNoMigrationEvidence(prefs)
        }
    }

    // ---- P0 retry path (MEDIUM-5, highest priority) ----

    @Test
    fun retryAfterFailedCommit_freshInstall_getsDurableClassificationAndStaysClosed() {
        val prefs = OptimisticPrefs()
        val store = remoteHookPolicyStore { prefs }

        prefs.commitSucceeds = false
        assertFalse(store.save(setOf("com.example.new")))
        assertEquals("the daemon persisted nothing", 1, prefs.commitCalls)
        assertNull(prefs.durable[classificationKey])
        assertNull(prefs.durable[markerKey])
        assertEquals("the failed commit polluted the local mMap", newGeneration, prefs.local[classificationKey])
        assertFalse(hookOpenDurable(prefs))

        prefs.commitSucceeds = true
        assertTrue("retry must succeed", store.save(setOf("com.example.new")))
        assertEquals(2, prefs.commitCalls)
        assertEquals(newGeneration, prefs.durable[classificationKey])
        assertNull(prefs.durable[markerKey])
        assertFalse(hookOpenDurable(prefs))
    }

    @Test
    fun retryAfterFailedCommit_genuineUpgrade_reAddsClassificationAndMarkerAndOpensHook() {
        val oldPackages = setOf("com.example.old")
        val prefs = OptimisticPrefs(mapOf(packagesKey to oldPackages, versionKey to 7L))
        val oldPackagesIdentity = prefs.durable[packagesKey]
        val store = remoteHookPolicyStore { prefs }

        prefs.commitSucceeds = false
        assertFalse(store.save(setOf("com.example.old", "com.example.new")))
        assertEquals(1, prefs.commitCalls)
        assertNull(prefs.durable[classificationKey])
        assertNull(prefs.durable[markerKey])
        assertSame("old draft untouched by the failed commit", oldPackagesIdentity, prefs.durable[packagesKey])
        assertEquals(7L, prefs.durable[versionKey])
        assertFalse(hookOpenDurable(prefs))

        prefs.commitSucceeds = true
        assertTrue("retry must succeed", store.save(setOf("com.example.old", "com.example.new")))
        assertEquals(2, prefs.commitCalls)
        assertEquals(preUpgrade, prefs.durable[classificationKey])
        assertEquals(true, prefs.durable[markerKey])
        assertTrue("classification+marker must be re-added to the retry", hookOpenDurable(prefs))
    }

    @Test
    fun retryAfterFailedBindTimeCommit_preservesOldDraftAndOpensOnRetry() {
        val oldPackages = setOf("com.example.old")
        val prefs = OptimisticPrefs(mapOf(packagesKey to oldPackages, versionKey to 7L))
        val oldPackagesIdentity = prefs.durable[packagesKey]

        prefs.commitSucceeds = false
        assertFalse(ensureBbcInstallClassification { prefs })
        assertNull(prefs.durable[classificationKey])
        assertNull(prefs.durable[markerKey])
        assertSame(oldPackagesIdentity, prefs.durable[packagesKey])
        assertEquals(7L, prefs.durable[versionKey])
        assertFalse(hookOpenDurable(prefs))

        prefs.commitSucceeds = true
        assertTrue("bind-time retry must succeed", ensureBbcInstallClassification { prefs })
        assertEquals(preUpgrade, prefs.durable[classificationKey])
        assertEquals(true, prefs.durable[markerKey])
        assertSame("bind-time classification never rewrites the draft object", oldPackagesIdentity, prefs.durable[packagesKey])
        assertEquals(7L, prefs.durable[versionKey])
        assertTrue(hookOpenDurable(prefs))
        assertEquals(2, prefs.commitCalls)
        assertFalse(
            "bind-time writes never touch draft keys",
            prefs.writtenKeys.contains(packagesKey) || prefs.writtenKeys.contains(versionKey),
        )
    }

    // ---- HIGH-2 cross-boundary variant + MEDIUM-6 / MEDIUM-7 ----

    @Test
    fun staleMarkerWithoutClassification_keepsHookClosed() {
        // A marker left behind by an old Hook + a real old draft are NOT
        // authorization: only the App-owned durable classification opens.
        val prefs = PersistedPrefs().apply {
            data[markerKey] = true
            data[packagesKey] = setOf("com.example.old")
            data[versionKey] = 7L
        }
        assertNull(prefs.data[classificationKey])
        assertFalse(hookOpen(prefs))
    }

    @Test
    fun upgradeWithEmptyOldDraft_failsClosedAndPreservesOldDraft() {
        // Present-but-empty packages + no version is NOT a pre-existing draft.
        val emptyPackages = emptySet<String>()
        val prefs = PersistedPrefs().apply { data[packagesKey] = emptyPackages }
        val identity = prefs.data[packagesKey]

        assertTrue(ensureBbcInstallClassification { prefs })

        assertEquals(newGeneration, prefs.data[classificationKey])
        assertNull(prefs.data[markerKey])
        assertSame(identity, prefs.data[packagesKey])
        assertEquals(1, prefs.commitCalls)
        assertFalse(hookOpen(prefs))

        // The ordinary draft write keeps the same classification authoritative.
        assertTrue(remoteHookPolicyStore { prefs }.save(emptySet()))
        assertEquals(newGeneration, prefs.data[classificationKey])
        assertNull(prefs.data[markerKey])
        assertFalse(hookOpen(prefs))
    }

    @Test
    fun bindTimeClassification_isIdempotent() {
        val prefs = PersistedPrefs().apply {
            data[packagesKey] = setOf("com.example.old")
            data[versionKey] = 7L
        }
        assertTrue(ensureBbcInstallClassification { prefs })
        val commitsAfterFirst = prefs.commitCalls
        val keysAfterFirst = prefs.writtenKeys.toList()
        val dataAfterFirst = LinkedHashMap(prefs.data)

        assertTrue(ensureBbcInstallClassification { prefs })

        assertEquals("second bind-time attempt must issue zero writes", commitsAfterFirst, prefs.commitCalls)
        assertEquals(keysAfterFirst, prefs.writtenKeys)
        assertEquals(dataAfterFirst, LinkedHashMap(prefs.data))
        assertTrue(hookOpen(prefs))
    }

    @Test
    fun unknownInstallClassificationValue_isNeverOverwritten() {
        // A value this client does not understand is never replaced by a
        // guess (neither the bind-time durable confirmation nor the legacy
        // idempotent writer may touch it).
        val prefs = PersistedPrefs().apply {
            data[classificationKey] = "FUTURE_SCHEMA_UNKNOWN"
        }
        val before = LinkedHashMap(prefs.data)

        assertFalse(confirmBbcInstallClassificationDurable { prefs })
        assertFalse(ensureBbcInstallClassification { prefs })

        assertEquals("an unknown classification value must be preserved verbatim",
            before, LinkedHashMap(prefs.data))
        assertEquals("no durable commit may be attempted for an unknown value",
            0, prefs.commitCalls)
        assertTrue("no key may be rewritten for an unknown value", prefs.writtenKeys.isEmpty())
    }

    /**
     * PR #90 P1-2: the optimistic-local-mMap confirmation trap, exact sequence.
     * gen1 commit(PRE_UPGRADE, marker) fails -> daemon has neither key;
     * markInstallClassificationFailed(1) fences Unknown. Service death clears
     * the pending intent, and a duplicate/rebind returns the SAME prefs
     * instance (PendingBbcClassification.matches uses ===). A NoWrite inferred
     * from the polluted local mMap must NEVER confirm classification: the Hook
     * would stay OFF while the App skipped consent. Only a real successful
     * durable commit may authorize Legacy.
     */
    @Test
    fun failedCommitThenDeathClearedPending_sameInstanceRebind_neverConfirmsFromPollutedLocalMap() {
        val holder = BbcActivationConsentHolder()
        holder.onServiceGenerationChanged(1L)
        val prefs = OptimisticPrefs(mapOf(packagesKey to setOf("com.example.old"), versionKey to 7L))

        prefs.commitSucceeds = false
        assertFalse("gen1 bind-time commit fails", confirmBbcInstallClassificationDurable { prefs })
        assertNull("daemon persisted no classification", prefs.durable[classificationKey])
        assertNull("daemon persisted no marker", prefs.durable[markerKey])
        assertEquals(preUpgrade, prefs.local[classificationKey])
        assertEquals(true, prefs.local[markerKey])
        holder.markInstallClassificationFailed(1L)
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())

        // Service death clears the pending intent; a duplicate rebind returns
        // the SAME prefs instance (local map still polluted).
        clearPendingBbcClassification()
        holder.onServiceGenerationChanged(2L)
        assertFalse("retry commit still fails", confirmBbcInstallClassificationDurable { prefs })
        assertNull(prefs.durable[classificationKey])
        assertNull(prefs.durable[markerKey])
        holder.markInstallClassificationFailed(2L)

        // Feed the polluted local read: the current-generation Unknown fence
        // must hold, with no Legacy and no consent skip.
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 2L,
                optIn = BbcOptInValue.ABSENT,
                classification = prefs.local[classificationKey] as? String,
                migrated = prefs.local[markerKey] as? Boolean,
                legacyRuntimeEnabled = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Unknown, holder.currentState())
        assertTrue(holder.currentState().requiresConsentFor(true))
        assertFalse("Hook gate must be OFF — nothing durable was persisted", hookOpenDurable(prefs))

        // A subsequent SUCCESSFUL durable commit DOES authorize Legacy and
        // opens the Hook gate (on a newer service generation, so the old
        // Unknown fence no longer applies).
        prefs.commitSucceeds = true
        assertTrue("a real commit confirms", confirmBbcInstallClassificationDurable { prefs })
        assertEquals(preUpgrade, prefs.durable[classificationKey])
        assertEquals(true, prefs.durable[markerKey])
        holder.onServiceGenerationChanged(3L)
        holder.markInstallClassificationConfirmed(3L)
        holder.resolve(
            BbcConsentAuthoritativeRead(
                serviceGeneration = 3L,
                optIn = BbcOptInValue.ABSENT,
                classification = prefs.durable[classificationKey] as? String,
                migrated = prefs.durable[markerKey] as? Boolean,
                legacyRuntimeEnabled = true,
            ),
        )
        assertEquals(BbcActivationConsentState.Legacy(true), holder.currentState())
        assertFalse(holder.currentState().requiresConsentFor(true))
        assertTrue(hookOpenDurable(prefs))
    }
}
