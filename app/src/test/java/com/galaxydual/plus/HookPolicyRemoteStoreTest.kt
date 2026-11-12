// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1):remoteHookPolicyStore 綁定語意 JVM 測。
// 以手寫 in-memory SharedPreferences (比照 ChannelPreferenceInMemoryStoreTest)
// 鎖定:group/key 字面值、save/clear 走 commit (非 apply)、同 provider 新
// store 可重讀、provider 回 null 時 fail-closed、回傳集合防禦性拷貝。
// 這只證明 app 側綁定;app 寫入後 hook 是否真讀到 (LSPosed remote
// preferences 跨行程通道) 仍需 on-device 端到端證明 (DEVICE-UNVERIFIED)。
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.hook.PolicySource
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HookPolicyRemoteStoreTest {

    @Before
    fun setUp() {
        // Phase2 #2 身份自檢 + 全域毒為全域狀態，跨測必須隔離。
        resetRemotePoisonForTests()
    }

    // Minimal in-memory SharedPreferences with an Editor that records explicit
    // commit()/apply() calls so the binding's synchronous-commit contract is
    // observable (the production binding must use commit(), never apply()).
    //
    // Fidelity notes (本假實現與真實 SharedPreferences/RemotePreferences 的差異):
    // - getStringSet 回傳後方 live set (與真實 RemotePreferences 一致);防禦性
    //   拷貝由 HookPolicyStore.load 的 sanitizeHookPolicy 統一負責,故
    //   mutatingLoadedSetDoesNotCorruptStore 才真的測到該防線。
    // - commit() 為同步原子 flush 且一律成功;只有在專用的 failing-commit 測試
    //   以 commitSucceeds=false 模擬失敗 (真實 RemotePreferences.commit 可能因
    //   binder 失敗回 false)。
    // - listener 註冊/反註冊為 no-op (綁定不依賴 listener)。
    private class RecordingPrefs : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()
        var commitCalls = 0
            private set
        var applyCalls = 0
            private set
        var removeCalls = 0
            private set
        var getStringSetCalls = 0
            private set
        // 測試開關:模擬 commit 失敗 / commit 拋出 / 型別錯亂 (見各專用測試)。
        var commitSucceeds = true
        var commitThrows = false
        var corruptType = false

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            if (data[key] is String) data[key] as? String else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            getStringSetCalls++
            if (corruptType && data.containsKey(key)) {
                // 模擬條目型別錯亂 (如被誤寫成 String):真實 RemotePreferences
                // 會在內部轉型時擲出 ClassCastException。僅當鍵存在時拋出，
                // 使 repair 清除後 post-read 可回 Known(empty)（鍵缺席不拋）。
                throw ClassCastException("java.lang.String cannot be cast to java.util.Set")
            }
            @Suppress("UNCHECKED_CAST")
            val stored = data[key] as? MutableSet<String>
            return stored ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = RecordingEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class RecordingEditor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (value == null) {
                    removed.add(key!!)
                    pending.remove(key)
                } else {
                    pending[key!!] = value
                    removed.remove(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key!!] = values?.toMutableSet()
                removed.remove(key)
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                removeCalls++
                removed.add(key!!)
                pending.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                // The remote binding never calls clear(); kept for completeness.
                removed.addAll(data.keys)
                pending.clear()
                return this
            }

            // 模擬 RemotePreferences 的樂觀更新:先寫進行程內快取 (flush) 再走
            // binder,並回傳 commitSucceeds;測試只斷言該布林,持久化與否非重點。
            // commitThrows=true 時模擬 binder RemoteException (flush 已發生、
            // mMap 已污染後再拋出)，與真實 doUpdate-先於-doCommit 一致。
            override fun commit(): Boolean {
                flush()
                commitCalls++
                if (commitThrows) throw RuntimeException("commit binder dead")
                return commitSucceeds
            }

            override fun apply() {
                flush()
                applyCalls++
            }

            private fun flush() {
                for (k in removed) data.remove(k)
                for ((k, v) in pending) data[k] = v
                pending.clear()
                removed.clear()
            }
        }
    }

    @Test
    fun groupAndKeyLiteralsAreStable() {
        assertEquals("dual_policy", PolicySource.POLICY_PREFS_NAME)
        assertEquals("extra_whitelist_packages", PolicySource.POLICY_KEY_EXTRA_PACKAGES)
    }

    @Test
    fun saveWritesStringSetViaCommitNotApply() {
        val prefs = RecordingPrefs()
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.save(setOf("com.example.a", "com.example.b")))

        assertEquals(1, prefs.commitCalls)
        assertEquals(0, prefs.applyCalls)
        val persisted = prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)
        assertEquals(setOf("com.example.a", "com.example.b"), persisted?.toSet())
    }

    @Test
    fun newStoreInstanceReadsSameValuesThroughSameProvider() {
        val prefs = RecordingPrefs()
        val provider = { prefs }
        val draft = setOf("com.example.a", "com.example.b")
        assertTrue(remoteHookPolicyStore(provider).save(draft))

        // Same provider, brand-new store instance must observe the write.
        assertEquals(draft, remoteHookPolicyStore(provider).load())
    }

    @Test
    fun clearRemovesKeyViaCommitNotApply() {
        val prefs = RecordingPrefs()
        val store = remoteHookPolicyStore { prefs }
        assertTrue(store.save(setOf("com.example.a")))
        assertEquals(1, prefs.commitCalls)
        // 哨兵鍵:clear 只能刪政策鍵,不得清空整個 prefs (殺死 remove->clear 變種)。
        assertTrue(prefs.edit().putString("sentinel", "keep").commit())
        assertEquals(2, prefs.commitCalls)

        assertTrue(store.clear())

        assertEquals(3, prefs.commitCalls)
        assertEquals(0, prefs.applyCalls)
        assertFalse(prefs.contains(PolicySource.POLICY_KEY_EXTRA_PACKAGES))
        assertEquals("keep", prefs.getString("sentinel", null))
        assertTrue(remoteHookPolicyStore { prefs }.load().isEmpty())
    }

    @Test
    fun transientProviderFailureReadsEmptyWithoutSelfClear() {
        val prefs = RecordingPrefs()
        // 先寫入一筆有效草稿,模擬已存在的 draft。
        assertTrue(remoteHookPolicyStore { prefs }.save(setOf("com.example.keep")))

        // provider() 第一次解析拋出 (模擬 XposedService.ServiceException 等暫時性
        // IPC 失敗):應視為「缺席」-> load 回空,但絕對不得自清 (刪掉有效草稿),
        // 且不得為自清再解析一次 provider (否則會改寫草稿)。
        var calls = 0
        val store = remoteHookPolicyStore {
            calls++
            if (calls == 1) throw RuntimeException("transient") else prefs
        }

        assertTrue(store.load().isEmpty())
        assertEquals(
            setOf("com.example.keep"),
            prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)?.toSet(),
        )
        assertEquals(1, calls) // only the load resolution; no self-clear resolution
    }

    @Test
    fun corruptTypeSelfClearsThroughRemoteBinding() {
        // Stage J3:型別損壞永不自清——load 回空（Unknown 的相容別名），但金鑰必須保留。
        val prefs = RecordingPrefs()
        assertTrue(remoteHookPolicyStore { prefs }.save(setOf("com.example.a")))
        assertTrue(prefs.contains(PolicySource.POLICY_KEY_EXTRA_PACKAGES))
        // 模擬型別錯亂:getStringSet 擲出 ClassCastException。
        prefs.corruptType = true
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.load().isEmpty())
        assertTrue(store.loadResult() is LoadResult.Unknown)
        // 與暫時性 IPC 失敗同：型別損壞亦不自清，key 仍在，待顯式重讀恢復。
        assertTrue(prefs.contains(PolicySource.POLICY_KEY_EXTRA_PACKAGES))
    }

    @Test
    fun failingCommitPropagatesAsFalse() {
        val prefs = RecordingPrefs()
        prefs.commitSucceeds = false
        val store = remoteHookPolicyStore { prefs }

        assertFalse(store.save(setOf("com.example.a")))
        assertFalse(store.clear())
    }

    @Test
    fun nullProviderFailsClosed() {
        val store = remoteHookPolicyStore { null }

        assertTrue(store.load().isEmpty())
        assertFalse(store.save(setOf("com.example.a")))
        assertFalse(store.clear())
    }

    @Test
    fun throwingProviderFailsSaveAndClearClosed() {
        // provider() 每次解析都拋出 (模擬 service binder 完全死亡):
        // load 回空,save/clear 都必須回 false (不可拋出、不可回報成功)。
        val store = remoteHookPolicyStore { throw RuntimeException("service dead") }

        assertTrue(store.load().isEmpty())
        assertFalse(store.save(setOf("com.example.a")))
        assertFalse(store.clear())
    }

    // Phase 4:provider 拋 Error (如 AssertionError) 亦 fail-closed
    // (store 層抓 Throwable 對齊 P1-2;取消除外,此處無協程故僅驗 fail-closed)。
    @Test
    fun errorThrowingProviderFailsClosed() {
        val store = remoteHookPolicyStore { throw AssertionError("binder dead") }

        assertTrue(store.load().isEmpty())
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertFalse(store.save(setOf("com.example.a")))
        assertFalse(store.clear())
    }

    @Test
    fun providerResolvedPerOperationRecoversWhenServiceAppears() {
        var prefs: RecordingPrefs? = null
        val store = remoteHookPolicyStore { prefs }

        assertTrue(store.load().isEmpty())
        assertFalse(store.save(setOf("com.example.a")))

        // Service (binder) arrives later; the same store instance re-resolves
        // the provider per operation and starts persisting.
        prefs = RecordingPrefs()
        assertTrue(store.save(setOf("com.example.a")))
        assertEquals(setOf("com.example.a"), store.load())
    }

    @Test
    fun mutatingLoadedSetDoesNotCorruptStore() {
        val prefs = RecordingPrefs()
        val store = remoteHookPolicyStore { prefs }
        val draft = setOf("com.example.a", "com.example.b")
        assertTrue(store.save(draft))

        // Phase 5：防禦性拷貝非 vacuous——兩次 load 必須是不同實例（assertNotSame），
        // 否則「變動不污染」斷言對同一實例恆真。載出集合的變動不得污染持久化草稿。
        val loaded1 = store.load()
        val loaded2 = store.load()
        assertNotSame(loaded1, loaded2)
        assertEquals(draft, loaded1)
        assertEquals(draft, loaded2)
        // 不鎖實作是否回傳可變集合:try/catch 涵蓋不可變/不可新增的副本,
        // 重點是載出集合的變動不得污染持久化草稿。
        var mutated = false
        try {
            (loaded1 as? MutableSet<String>)?.add("com.example.evil")
            mutated = (loaded1 as? Set<String>)?.contains("com.example.evil") == true
        } catch (_: UnsupportedOperationException) {
            // Immutable copy: nothing to mutate; store integrity is the point.
        }

        // The mutation must not reach the persisted draft (nor the other snapshot).
        assertEquals(draft, store.load())
        assertEquals(draft, loaded2)
        if (mutated) {
            assertEquals(draft, store.load())
        }
    }

    @Test
    fun absentKeyIsConfirmedEmptyNotUnknown() {
        val prefs = RecordingPrefs()
        val store = remoteHookPolicyStore { prefs }
        // Connected service with no draft key is a confirmed empty, not unknown.
        assertTrue(store.loadResult() is LoadResult.Known)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun failedSaveMasksOptimisticCacheAsUnknownUntilSuccessfulSave() {
        // RecordingEditor.commit() 先 flush (樂觀寫進行程內 mMap) 再回
        // commitSucceeds,與真實 RemotePreferences (doUpdate 先於 binder
        // doCommit) 一致:commit false 時底層 data 已被污染。
        val prefs = RecordingPrefs()
        prefs.commitSucceeds = false
        val store = remoteHookPolicyStore { prefs }

        assertFalse(store.save(setOf("com.example.a")))
        // 樂觀值已在本地 mMap,但 daemon 未落盤:必須回 Unknown,不可把樂觀值
        // 誤報為 Known。
        assertTrue(store.loadResult() is LoadResult.Unknown)
        // 污染確實存在 (證明此測試真的踩到樂觀快取,而非單純空鍵)。
        assertEquals(
            setOf("com.example.a"),
            prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)?.toSet(),
        )

        // 成功 commit 重同步本地+daemon 後,讀數重新可信。
        prefs.commitSucceeds = true
        assertTrue(store.save(setOf("com.example.a")))
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertEquals(setOf("com.example.a"), (result as LoadResult.Known).packages)
    }

    @Test
    fun failedClearMasksOptimisticCacheAsUnknownUntilSuccessfulClear() {
        val prefs = RecordingPrefs()
        val store = remoteHookPolicyStore { prefs }
        assertTrue(store.save(setOf("com.example.a")))

        // clear 的 commit 回 false:本地已樂觀刪鍵,但 daemon 仍有值;若直接讀會
        // 誤報「已確認空」,必須回 Unknown。
        prefs.commitSucceeds = false
        assertFalse(store.clear())
        assertTrue(store.loadResult() is LoadResult.Unknown)

        // 成功 clear(true) 重同步後恢復 Known 空 (確認空,非 Unknown)。
        prefs.commitSucceeds = true
        assertTrue(store.clear())
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertTrue((result as LoadResult.Known).packages.isEmpty())
        assertFalse(prefs.contains(PolicySource.POLICY_KEY_EXTRA_PACKAGES))
    }

    @Test
    fun throwingProviderDoesNotMaskSubsequentReads() {
        // provider() 拋出 = 未觸及任何 prefs 實例 → 無 mMap 污染可能，不設旗標；
        // 恢復後直接 load 即為 Known (load-only 恢復，無需先成功寫入)。
        val prefs = RecordingPrefs()
        var throwOnce = true
        val store = remoteHookPolicyStore {
            if (throwOnce) throw RuntimeException("binder dead") else prefs
        }

        // provider 拋出 -> HookPolicyStore.save 轉 false，但旗標不得升起。
        assertFalse(store.save(setOf("com.example.a")))
        throwOnce = false
        // provider 已恢復且底層實際為空：無污染，直接讀可信 -> Known 空。
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertTrue((result as LoadResult.Known).packages.isEmpty())
    }

    @Test
    fun throwingCommitMasksSubsequentReadsAsUnknownUntilSuccessfulSave() {
        // commit() 拋出 (RemoteException 風格)：已拿到 prefs 實例且樂觀 flush
        // 已發生，必須回 Unknown；成功 save 後恢復 Known。
        val prefs = RecordingPrefs()
        prefs.commitThrows = true
        val store = remoteHookPolicyStore { prefs }

        // HookPolicyStore.save 捕捉 commit 拋出轉 false，且旗標必須升起。
        assertFalse(store.save(setOf("com.example.a")))
        assertTrue(store.loadResult() is LoadResult.Unknown)
        // 污染確實存在 (flush-then-throw，與真實 doUpdate-先於-doCommit 一致)。
        assertEquals(
            setOf("com.example.a"),
            prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)?.toSet(),
        )

        prefs.commitThrows = false
        assertTrue(store.save(setOf("com.example.a")))
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertEquals(setOf("com.example.a"), (result as LoadResult.Known).packages)
    }

    @Test
    fun nullProviderSaveDoesNotMaskSubsequentReadsAfterRecovery() {
        // null-provider save：未觸及任何 prefs 實例 → 不設旗標；provider 恢復為
        // 乾淨 prefs 後直接 load 即為 Known (load-only 恢復，證明無 sticky
        // Unknown)。
        var prefs: RecordingPrefs? = null
        val store = remoteHookPolicyStore { prefs }

        assertFalse(store.save(setOf("com.example.a")))

        // provider 恢復，底層為乾淨空 prefs。
        prefs = RecordingPrefs()
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertTrue((result as LoadResult.Known).packages.isEmpty())
    }

    @Test
    fun flaggedUnknownDoesNotSelfClearCorruptType() {
        // 失敗 save (旗標 true) 後即使底層型別損壞，loadRaw 短路不碰 prefs，
        // 故不得觸發 CCE 自清 (key 仍在)；成功 save 後恢復 Known。
        val prefs = RecordingPrefs()
        prefs.commitSucceeds = false
        val store = remoteHookPolicyStore { prefs }

        assertFalse(store.save(setOf("com.example.a")))
        assertTrue(store.loadResult() is LoadResult.Unknown)

        // 此時再標記型別損壞：仍為 Unknown，且 key 不得被自清刪除。
        prefs.corruptType = true
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertTrue(prefs.contains(PolicySource.POLICY_KEY_EXTRA_PACKAGES))

        // 成功 save 覆寫即恢復可信讀數。
        prefs.corruptType = false
        prefs.commitSucceeds = true
        assertTrue(store.save(setOf("com.example.a")))
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertEquals(setOf("com.example.a"), (result as LoadResult.Known).packages)
    }

    @Test
    fun swappedCleanInstanceRecoversKnownWithoutSave() {
        // Phase2 #2 身份自檢：換身份首讀 fail-closed（Unknown，不靜默信任），
        // 次讀穩定即穿透 Known（確認空）。DEVICE-UNVERIFIED 實驗路徑保留，
        // 但首讀不再靜默穿透。
        val polluted = RecordingPrefs()
        polluted.commitSucceeds = false
        val clean = RecordingPrefs()
        val current = AtomicReference<SharedPreferences?>(polluted)
        val store = remoteHookPolicyStore { current.get() }

        assertFalse(store.save(setOf("com.example.a")))
        assertTrue(store.loadResult() is LoadResult.Unknown)

        // Phase2 #2：新身份首讀 fail-closed（身份不穩定 + 有毒）。
        current.set(clean)
        assertTrue(store.loadResult() is LoadResult.Unknown)
        // 次讀身份已穩定，穿透讀 Known（確認空）。
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertTrue((result as LoadResult.Known).packages.isEmpty())
    }

    @Test
    fun newPrefsInstanceRecoversWithoutClearThenSaveSucceeds() {
        // Phase2 #2：transient commit-false → 換新 prefs 實例（含舊值）→ 首讀
        // fail-closed Unknown（身份不穩定），次讀穩定即 Known（舊值）→ 可直接
        // save 新值免 clear。
        val polluted = RecordingPrefs()
        polluted.commitSucceeds = false
        val current = AtomicReference<SharedPreferences?>(polluted)
        val store = remoteHookPolicyStore { current.get() }

        assertFalse(store.save(setOf("com.example.a")))
        assertTrue(store.loadResult() is LoadResult.Unknown)

        // 新實例已重同步舊值（模擬 daemon 真值）。
        val recovered = RecordingPrefs()
        assertTrue(
            recovered.edit().putStringSet(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                mutableSetOf("com.example.keep"),
            ).commit(),
        )
        current.set(recovered)

        // 首讀 fail-closed（身份不穩定），次讀穩定即 Known（舊值），且可直接 save 新值。
        assertTrue(store.loadResult() is LoadResult.Unknown)
        val probed = store.loadResult()
        assertTrue(probed is LoadResult.Known)
        assertEquals(setOf("com.example.keep"), (probed as LoadResult.Known).packages)

        assertTrue(store.save(setOf("com.example.b")))
        val after = store.loadResult()
        assertTrue(after is LoadResult.Known)
        assertEquals(setOf("com.example.b"), (after as LoadResult.Known).packages)
    }

    @Test
    fun sameInstanceStaysFailClosedWithoutTouchingPrefs() {
        // 第 2 層同實例超時試探已刪除：同實例在旗標下永回 Unknown，直到一次
        // 成功 save/clear 清旗；不更新時間、不讀 prefs、不自清。
        // 超時推進後仍 Unknown（此處以重複讀模擬時間推進語意）。
        val prefs = RecordingPrefs()
        prefs.commitSucceeds = false
        val store = remoteHookPolicyStore { prefs }

        assertFalse(store.save(setOf("com.example.a")))
        assertTrue(store.loadResult() is LoadResult.Unknown)
        // 同實例短路不碰 prefs：首次旗標下讀即零 getStringSet。
        val callsAfterFirst = prefs.getStringSetCalls
        assertEquals(0, callsAfterFirst)

        // 重複讀仍 Unknown，且未新增 prefs 觸碰（續閂節流證明）。
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertEquals(callsAfterFirst, prefs.getStringSetCalls)
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertEquals(callsAfterFirst, prefs.getStringSetCalls)

        // 不自清：樂觀值仍在（未被刪除），避免刪掉有效草稿。
        assertTrue(prefs.contains(PolicySource.POLICY_KEY_EXTRA_PACKAGES))

        // 成功 save 清旗後恢復 Known。
        prefs.commitSucceeds = true
        assertTrue(store.save(setOf("com.example.a")))
        val result = store.loadResult()
        assertTrue(result is LoadResult.Known)
        assertEquals(setOf("com.example.a"), (result as LoadResult.Known).packages)
    }

    @Test
    fun flaggedNewIdentityProbeFailureRelatchesWithoutSelfClear() {
        // Phase2 #2 身份自檢：新身份首讀 fail-closed 不觸碰 prefs（零 getStringSet，
        // 不靜默信任）；次讀穩定才探讀（CCE 失敗 → Unknown + 續閂到新實例）；
        // 續閂後同實例短路不再碰 prefs。
        val polluted = RecordingPrefs()
        polluted.commitSucceeds = false
        val current = AtomicReference<SharedPreferences?>(polluted)
        val store = remoteHookPolicyStore { current.get() }

        assertFalse(store.save(setOf("com.example.a")))
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertEquals(0, polluted.getStringSetCalls)

        // 新實例帶型別損壞：首讀身份不穩定 → Unknown 且零觸碰（不探讀、不自清）。
        val bad = RecordingPrefs()
        assertTrue(
            bad.edit().putStringSet(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                mutableSetOf("com.example.keep"),
            ).commit(),
        )
        bad.corruptType = true
        current.set(bad)

        val callsBefore = bad.getStringSetCalls
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertEquals(callsBefore, bad.getStringSetCalls)
        assertTrue(bad.contains(PolicySource.POLICY_KEY_EXTRA_PACKAGES))

        // 次讀身份已穩定，探讀失敗（CCE）→ Unknown，觸碰 +1，續閂到新實例。
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertEquals(callsBefore + 1, bad.getStringSetCalls)

        // 續閂：同實例第三次讀短路，不再碰 prefs。
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertEquals(callsBefore + 1, bad.getStringSetCalls)

        // 同實例修復損壞仍不恢復（fail-closed：同實例永 Unknown）。
        bad.corruptType = false
        assertTrue(store.loadResult() is LoadResult.Unknown)
        assertEquals(callsBefore + 1, bad.getStringSetCalls)
    }

    // provider 拋 CancellationException 時 load/save/clear 同步上拋
    //（store 非 suspend，直接拋；取消不得被吞為 Unknown/false）。
    @Test
    fun providerCancellationExceptionRethrownForLoadSaveClear() {
        val store = remoteHookPolicyStore {
            throw kotlinx.coroutines.CancellationException("cancel")
        }
        try {
            store.loadResult()
            org.junit.Assert.fail("loadResult must rethrow CancellationException")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // expected
        }
        try {
            store.load()
            org.junit.Assert.fail("load must rethrow CancellationException")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // expected
        }
        try {
            store.save(setOf("com.example.a"))
            org.junit.Assert.fail("save must rethrow CancellationException")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // expected
        }
        try {
            store.clear()
            org.junit.Assert.fail("clear must rethrow CancellationException")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    // saveRaw 的 edit/commit 拋 CE 時 markFailed + 重拋：後續讀為 Unknown
    //（旗標已升），成功 save 後清旗回 Known。
    @Test
    fun saveCommitCancellationMarksFailedAndRethrows() {
        // 最小 stub：commit 可拋 CE（RecordingPrefs.commitThrows 僅拋
        // RuntimeException；且 Editor 裝飾器會因 putStringSet 回傳 inner 而
        // 繞過 commit 覆寫，故此處用完整 stub）。
        var throwCe = true
        var getCalls = 0
        val data = mutableMapOf<String, Any?>()
        val cePrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = HashMap(data)
            override fun getString(key: String?, defValue: String?): String? = defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
                getCalls++
                @Suppress("UNCHECKED_CAST")
                return (data[key] as? Set<String>)?.toMutableSet() ?: defValues
            }
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
            override fun contains(key: String?): Boolean = data.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private var pending: MutableSet<String>? = null
                private var removed = false
                override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                    throw UnsupportedOperationException()
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                    apply { pending = values?.toMutableSet(); removed = false }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                    throw UnsupportedOperationException()
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                    throw UnsupportedOperationException()
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                    throw UnsupportedOperationException()
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                    throw UnsupportedOperationException()
                override fun remove(key: String?): SharedPreferences.Editor =
                    apply { removed = true; pending = null }
                override fun clear(): SharedPreferences.Editor =
                    throw UnsupportedOperationException()
                override fun commit(): Boolean {
                    if (throwCe) throw kotlinx.coroutines.CancellationException("cancel")
                    if (removed) data.remove(PolicySource.POLICY_KEY_EXTRA_PACKAGES)
                    if (pending != null) {
                        data[PolicySource.POLICY_KEY_EXTRA_PACKAGES] = pending!!
                    }
                    return true
                }
                override fun apply() = throw UnsupportedOperationException()
            }
            override fun registerOnSharedPreferenceChangeListener(
                listener: SharedPreferences.OnSharedPreferenceChangeListener?,
            ) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(
                listener: SharedPreferences.OnSharedPreferenceChangeListener?,
            ) = Unit
        }
        val ceStore = remoteHookPolicyStore { cePrefs }
        try {
            ceStore.save(setOf("com.example.a"))
            org.junit.Assert.fail("save must rethrow CancellationException")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // expected: markFailed + rethrow
        }
        // 旗標已升：同一實例短路 Unknown（不碰底層 getStringSet）。
        assertTrue(ceStore.loadResult() is LoadResult.Unknown)
        assertEquals(0, getCalls)

        // 成功 save 清旗：同一實例恢復 Known。
        throwCe = false
        assertTrue(ceStore.save(setOf("com.example.a")))
        val result = ceStore.loadResult()
        assertTrue(result is LoadResult.Known)
        assertEquals(setOf("com.example.a"), (result as LoadResult.Known).packages)
    }
    // Stage J2: 跨實例毒——同一 prefs 被 A 失敗污染後，從未失敗的 B store 讀到它
    // 亦須 Unknown（不得把樂觀毒值誤報 Known）。成功重同步後雙方恢復 Known。
    @Test
    fun crossInstancePoisonedReadIsUnknownUntilResync() {
        val prefs = RecordingPrefs()
        prefs.commitSucceeds = false
        val storeA = remoteHookPolicyStore { prefs }
        val storeB = remoteHookPolicyStore { prefs }

        assertFalse(storeA.save(setOf("com.example.a")))
        assertTrue(storeA.loadResult() is LoadResult.Unknown)
        // B 從未失敗，但同一 mMap 已毒：必須 Unknown，不可 Known(B)。
        assertTrue(storeB.loadResult() is LoadResult.Unknown)
        // 污染確實存在（證明此測試踩到樂觀快取）。
        assertEquals(
            setOf("com.example.a"),
            prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, null)?.toSet(),
        )

        // 成功重同步（任一 store 成功即全域解毒）後雙方恢復 Known。
        prefs.commitSucceeds = true
        assertTrue(storeA.save(setOf("com.example.a")))
        val ra = storeA.loadResult()
        assertTrue(ra is LoadResult.Known)
        assertEquals(setOf("com.example.a"), (ra as LoadResult.Known).packages)
        val rb = storeB.loadResult()
        assertTrue(rb is LoadResult.Known)
        assertEquals(setOf("com.example.a"), (rb as LoadResult.Known).packages)
    }

    // Stage J2: clear(false) 跨實例毒——A clear 失敗（樂觀刪鍵）後，B 讀亦 Unknown。
    @Test
    fun crossInstanceFailedClearIsUnknown() {
        val prefs = RecordingPrefs()
        val seedStore = remoteHookPolicyStore { prefs }
        assertTrue(seedStore.save(setOf("com.example.keep")))

        prefs.commitSucceeds = false
        val storeA = remoteHookPolicyStore { prefs }
        val storeB = remoteHookPolicyStore { prefs }
        assertFalse(storeA.clear())
        assertTrue(storeA.loadResult() is LoadResult.Unknown)
        assertTrue(storeB.loadResult() is LoadResult.Unknown)

        prefs.commitSucceeds = true
        assertTrue(storeA.clear())
        assertTrue(storeA.loadResult() is LoadResult.Known)
        assertTrue((storeA.loadResult() as LoadResult.Known).packages.isEmpty())
    }

    // Stage J2: 讀後再驗證——fail-mark 與 loadResult 併發時，毒讀不得勝出。
    // 讀線程阻塞在 getStringSet 內時，主線程標記失敗；放行後讀必須 Unknown。
    @Test
    fun barrierFailMarkConcurrentWithLoadReturnsUnknown() {
        val readEntered = java.util.concurrent.CountDownLatch(1)
        val readRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        var backing: MutableSet<String>? = mutableSetOf("com.seed")
        val blockingPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>()
            override fun getString(key: String?, defValue: String?): String? = defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
                if (key == PolicySource.POLICY_KEY_EXTRA_PACKAGES) {
                    readEntered.countDown()
                    while (!readRelease.get()) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                        }
                    }
                    // 放行時底層已被樂觀污染為 B（由他線程 save 失敗寫入）。
                    return backing?.toMutableSet() ?: defValues
                }
                return defValues
            }
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
            override fun contains(key: String?): Boolean = key == PolicySource.POLICY_KEY_EXTRA_PACKAGES && backing != null
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                    if (key == PolicySource.POLICY_KEY_EXTRA_PACKAGES) {
                        backing = values?.toMutableSet()
                    }
                    return this
                }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key == PolicySource.POLICY_KEY_EXTRA_PACKAGES) backing = null
                    return this
                }
                override fun clear(): SharedPreferences.Editor = this
                // 先樂觀污染 backing 再回 false（模擬 doUpdate 先於 doCommit）。
                override fun commit(): Boolean {
                    return false
                }
                override fun apply(): Unit = Unit
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        }
        val store = remoteHookPolicyStore { blockingPrefs }
        // 讀線程先進入並阻塞。
        var loadResult: LoadResult? = null
        val reader = Thread {
            loadResult = store.loadResult()
        }
        reader.start()
        assertTrue(readEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // 主線程併發標記失敗（樂觀污染 backing 為 B，commit 回 false）。
        backing = mutableSetOf("com.example.B")
        assertFalse(store.save(setOf("com.example.B")))
        // 放行讀：即使底層是 B，也必須 Unknown（讀後再驗證作廢毒讀）。
        readRelease.set(true)
        reader.join(5_000)
        assertTrue(loadResult is LoadResult.Unknown)
    }

    // Stage L2 P2 #4:distinct-store 原子發佈間隙——A/B 共享同 prefs 實例 (樂觀
    // commit=false,先 flush 污染 mMap 再回 false)。A 的 save 阻塞在 commit 內
    // (污染已發生、commit 未回、ensure 未到) 時,B 的 loadResult 必須 Unknown
    // (pre-poison 已在 commit 前發佈,gen 已 bump);放行 A 後 B 仍 Unknown,直到
    // 成功重同步才回 Known。舊雙原子 (本地先設、全域後加) 在此窗兩次全域檢查皆
    // 乾淨而誤回 Known(B),單 store barrier 測不到此交錯。
    @Test
    fun distinctStoresCommitGapIsFailClosedUntilResync() {
        resetRemotePoisonForTests()
        try {
            val prefs = GapCommitPrefs(commitSucceeds = false)
            val storeA = remoteHookPolicyStore { prefs }
            val storeB = remoteHookPolicyStore { prefs }

            prefs.armBlock()
            var saveResult: Boolean? = null
            val writer = Thread {
                saveResult = storeA.save(setOf("com.example.B"))
            }
            writer.start()
            assertTrue(prefs.commitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // 間隙:B 必須 Unknown (不可把樂觀 B 誤報 Known)。
            assertTrue(storeB.loadResult() is LoadResult.Unknown)
            // 污染確實存在 (證明踩到樂觀快取,而非空鍵)。
            assertEquals(
                setOf("com.example.B"),
                prefs.peekDirect()?.toSet(),
            )

            prefs.release()
            writer.join(5_000)
            assertEquals(false, saveResult)
            // 放行後仍 Unknown (毒未解),直到成功重同步。
            assertTrue(storeB.loadResult() is LoadResult.Unknown)
            assertTrue(storeA.loadResult() is LoadResult.Unknown)

            // 成功重同步後雙方 Known。
            prefs.commitSucceeds = true
            assertTrue(storeA.save(setOf("com.example.B")))
            val ra = storeA.loadResult()
            assertTrue(ra is LoadResult.Known)
            assertEquals(setOf("com.example.B"), (ra as LoadResult.Known).packages)
            val rb = storeB.loadResult()
            assertTrue(rb is LoadResult.Known)
            assertEquals(setOf("com.example.B"), (rb as LoadResult.Known).packages)
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Stage L2 clear 反向間隙:成功 clear 的 commit 阻塞時 (pre-poison 已發佈、
    // clear 未發佈),B 讀必須 Unknown (fail-closed 誤 Unknown 可接受),放行成功後
    // 才回 Known 空。驗證 token 條件清除不丟失併發毒 (此處無併發失敗,僅驗反向窗)。
    @Test
    fun distinctStoresClearReverseGapStaysFailClosed() {
        resetRemotePoisonForTests()
        try {
            val prefs = GapCommitPrefs(commitSucceeds = true)
            val seed = remoteHookPolicyStore { prefs }
            assertTrue(seed.save(setOf("com.example.keep")))
            val storeA = remoteHookPolicyStore { prefs }
            val storeB = remoteHookPolicyStore { prefs }

            prefs.armBlock()
            var clearResult: Boolean? = null
            val writer = Thread {
                clearResult = storeA.clear()
            }
            writer.start()
            assertTrue(prefs.commitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // 反向間隙:樂觀已刪鍵但未確認,必須 Unknown (不可 premature Known 空)。
            assertTrue(storeB.loadResult() is LoadResult.Unknown)

            prefs.release()
            writer.join(5_000)
            assertEquals(true, clearResult)
            val ra = storeA.loadResult()
            assertTrue(ra is LoadResult.Known)
            assertTrue((ra as LoadResult.Known).packages.isEmpty())
            val rb = storeB.loadResult()
            assertTrue(rb is LoadResult.Known)
            assertTrue((rb as LoadResult.Known).packages.isEmpty())
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Stage L2 distinct-store mid-getStringSet 突變丟棄:讀者 A 阻塞在 getStringSet
    // 內時,寫者 B 失敗污染 (pre+commit flush+ensure,gen 兩次 bump) 並完成;放行 A
    // 後 A 必須 Unknown (after.gen!=before.gen 或毒命中),且雙方直到重同步皆 Unknown。
    @Test
    fun distinctStoresMidReadMutationDiscardedUntilResync() {
        resetRemotePoisonForTests()
        try {
            val readEntered = java.util.concurrent.CountDownLatch(1)
            val readRelease = java.util.concurrent.atomic.AtomicBoolean(false)
            var backing: MutableSet<String>? = mutableSetOf("com.seed")
            var commitSucceeds = false
            val prefs = object : SharedPreferences {
                override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>()
                override fun getString(key: String?, defValue: String?): String? = defValue
                override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
                    if (key == PolicySource.POLICY_KEY_EXTRA_PACKAGES) {
                        readEntered.countDown()
                        while (!readRelease.get()) {
                            try {
                                Thread.sleep(5)
                            } catch (_: InterruptedException) {
                            }
                        }
                        return backing?.toMutableSet() ?: defValues
                    }
                    return defValues
                }
                override fun getInt(key: String?, defValue: Int): Int = defValue
                override fun getLong(key: String?, defValue: Long): Long = defValue
                override fun getFloat(key: String?, defValue: Float): Float = defValue
                override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
                override fun contains(key: String?): Boolean =
                    key == PolicySource.POLICY_KEY_EXTRA_PACKAGES && backing != null
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                        if (key == PolicySource.POLICY_KEY_EXTRA_PACKAGES) {
                            backing = values?.toMutableSet()
                        }
                        return this
                    }
                    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
                    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
                    override fun remove(key: String?): SharedPreferences.Editor {
                        if (key == PolicySource.POLICY_KEY_EXTRA_PACKAGES) backing = null
                        return this
                    }
                    override fun clear(): SharedPreferences.Editor = this
                    override fun commit(): Boolean = commitSucceeds
                    override fun apply(): Unit = Unit
                }
                override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
                override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
            }
            val storeA = remoteHookPolicyStore { prefs }
            val storeB = remoteHookPolicyStore { prefs }

            var loadResult: LoadResult? = null
            val reader = Thread {
                loadResult = storeA.loadResult()
            }
            reader.start()
            assertTrue(readEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // 寫者 B 在讀者阻塞期間失敗 (污染 backing 為 B)。
            assertFalse(storeB.save(setOf("com.example.B")))
            // 放行讀:即使底層是 B,也必須 Unknown。
            readRelease.set(true)
            reader.join(5_000)
            assertTrue(loadResult is LoadResult.Unknown)
            assertTrue(storeA.loadResult() is LoadResult.Unknown)
            assertTrue(storeB.loadResult() is LoadResult.Unknown)

            // 成功重同步後雙方 Known。
            commitSucceeds = true
            assertTrue(storeB.save(setOf("com.example.B")))
            val ra = storeA.loadResult()
            assertTrue(ra is LoadResult.Known)
            assertEquals(setOf("com.example.B"), (ra as LoadResult.Known).packages)
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #1 備份：單一固定鍵覆寫，連續 3 次 repair 只剩 ≤1 bak 鍵（含遷移清舊鍵）。
    @Test
    fun backup_singleFixedKey_threeRepairsAtMostOneBakKey() {
        resetRemotePoisonForTests()
        try {
            val prefs = RecordingPrefs()
            // 種下舊無界鍵（遷移前殘留），修復時必須清除。
            assertTrue(
                prefs.edit().putStringSet("dual_policy.bak.111", mutableSetOf("stale1")).commit(),
            )
            assertTrue(
                prefs.edit().putStringSet("dual_policy.bak.222", mutableSetOf("stale2")).commit(),
            )
            // 種下損壞值（Set 型，讀即 CCE）。
            assertTrue(prefs.edit().putStringSet(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                mutableSetOf("com.example.a"),
            ).commit())
            prefs.corruptType = true
            val store = remoteHookPolicyStore { prefs }
            assertTrue(store.loadResult() is LoadResult.Corrupt)

            repeat(3) {
                val r = store.repairCorrupt()
                assertTrue(r is RepairResult.Repaired)
            }
            val keys = prefs.all.keys.filter { it == BACKUP_KEY || it.startsWith(BACKUP_LEGACY_PREFIX) }
            assertEquals(1, keys.size)
            assertEquals(BACKUP_KEY, keys.single())
            assertTrue(prefs.all.keys.none { it.startsWith(BACKUP_LEGACY_PREFIX) })
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #1 備份成功寫入斷言（Set 型損壞值備份內容一致）。
    @Test
    fun backup_successWritesSetValue() {
        resetRemotePoisonForTests()
        try {
            val prefs = RecordingPrefs()
            assertTrue(prefs.edit().putStringSet(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                mutableSetOf("com.example.a", "com.example.b"),
            ).commit())
            prefs.corruptType = true
            val store = remoteHookPolicyStore { prefs }
            assertTrue(store.repairCorrupt() is RepairResult.Repaired)
            @Suppress("UNCHECKED_CAST")
            val bak = prefs.all[BACKUP_KEY] as? Set<String>
            assertTrue(bak != null)
            assertEquals(setOf("com.example.a", "com.example.b"), bak!!.toSet())
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #1 String 型錯亂走 toString 分支。
    @Test
    fun backup_stringConfusionUsesToStringBranch() {
        resetRemotePoisonForTests()
        try {
            val prefs = RecordingPrefs()
            assertTrue(prefs.edit().putString(PolicySource.POLICY_KEY_EXTRA_PACKAGES, "oops-string").commit())
            prefs.corruptType = true
            val store = remoteHookPolicyStore { prefs }
            assertTrue(store.loadResult() is LoadResult.Corrupt)
            assertTrue(store.repairCorrupt() is RepairResult.Repaired)
            @Suppress("UNCHECKED_CAST")
            val bak = prefs.all[BACKUP_KEY] as? Set<String>
            assertEquals(setOf("oops-string"), bak?.toSet())
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #1 value 截斷：超 4KB 只存類型+長度。
    @Test
    fun backup_truncatesOversizeValue() {
        resetRemotePoisonForTests()
        try {
            val prefs = RecordingPrefs()
            val big = (1..200).map { "com.example." + "x".repeat(100) + it }.toSet()
            assertTrue(prefs.edit().putStringSet(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                big.toMutableSet(),
            ).commit())
            prefs.corruptType = true
            val store = remoteHookPolicyStore { prefs }
            assertTrue(store.repairCorrupt() is RepairResult.Repaired)
            @Suppress("UNCHECKED_CAST")
            val bak = prefs.all[BACKUP_KEY] as? Set<String>
            assertTrue(bak != null)
            assertEquals(1, bak!!.size)
            assertTrue(bak.single().contains("200"))
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #2 身份脆弱：provider 每次回 new wrapper（同底層 map），
    // save-false 後 load 仍須 Unknown（身分自檢 fail-closed）。
    @Test
    fun wrapperChurn_sameBackingNewWrapperStaysUnknownAfterSaveFalse() {
        resetRemotePoisonForTests()
        try {
            val backing = linkedMapOf<String, Any?>()
            var commitSucceeds = false
            fun newWrapper(): SharedPreferences = object : SharedPreferences {
                override fun getAll(): MutableMap<String, *> = LinkedHashMap(backing)
                override fun getString(k: String?, d: String?): String? = d
                override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? {
                    @Suppress("UNCHECKED_CAST")
                    return (backing[k] as? MutableSet<String>) ?: d
                }
                override fun getInt(k: String?, d: Int): Int = d
                override fun getLong(k: String?, d: Long): Long = d
                override fun getFloat(k: String?, d: Float): Float = d
                override fun getBoolean(k: String?, d: Boolean): Boolean = d
                override fun contains(k: String?): Boolean = backing.containsKey(k)
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                    private val pending = linkedMapOf<String, Any?>()
                    private val removed = mutableSetOf<String>()
                    override fun putString(k: String?, v: String?): SharedPreferences.Editor = this
                    override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor {
                        pending[k!!] = v?.toMutableSet(); removed.remove(k); return this
                    }
                    override fun putInt(k: String?, v: Int): SharedPreferences.Editor = this
                    override fun putLong(k: String?, v: Long): SharedPreferences.Editor = this
                    override fun putFloat(k: String?, v: Float): SharedPreferences.Editor = this
                    override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor = this
                    override fun remove(k: String?): SharedPreferences.Editor {
                        removed.add(k!!); pending.remove(k); return this
                    }
                    override fun clear(): SharedPreferences.Editor = this
                    override fun commit(): Boolean {
                        for (k in removed) backing.remove(k)
                        for ((k, v) in pending) backing[k] = v
                        pending.clear(); removed.clear()
                        return commitSucceeds
                    }
                    override fun apply(): Unit = Unit
                }
                override fun registerOnSharedPreferenceChangeListener(
                    l: SharedPreferences.OnSharedPreferenceChangeListener?,
                ) = Unit
                override fun unregisterOnSharedPreferenceChangeListener(
                    l: SharedPreferences.OnSharedPreferenceChangeListener?,
                ) = Unit
            }
            val store = remoteHookPolicyStore { newWrapper() }
            assertFalse(store.save(setOf("com.example.a")))
            // 每次新 wrapper（同底層樂觀污染 map）仍須 Unknown，不可誤讀樂觀值為 Known。
            assertTrue(store.loadResult() is LoadResult.Unknown)
            assertEquals(
                setOf("com.example.a"),
                backing[PolicySource.POLICY_KEY_EXTRA_PACKAGES]?.let {
                    @Suppress("UNCHECKED_CAST")
                    (it as? Set<String>)?.toSet()
                },
            )
            // 第二次新 wrapper 亦 Unknown（永不穩定）。
            assertTrue(store.loadResult() is LoadResult.Unknown)
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #7 first-wins：第二次 CCE 不覆寫首次 observedGen。
    @Test
    fun evidence_firstWinsRetainsObservedGen() {
        resetRemotePoisonForTests()
        try {
            val prefs = RecordingPrefs()
            assertTrue(prefs.edit().putStringSet(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                mutableSetOf("com.example.a"),
            ).commit())
            prefs.corruptType = true
            val store = remoteHookPolicyStore { prefs }
            assertTrue(store.loadResult() is LoadResult.Corrupt)
            val first = remoteCorruptEvidence.get().entries.firstOrNull { it.key.get() === prefs }?.value
            assertTrue(first != null)
            val gen1 = first!!.observedGen
            // 推進 gen（失敗寫入 bump），再經 repairProbe 觸發第二次 CCE
            //（loadResult 已毒短路不再探讀，故用 loadForRepair 直探）。
            prefs.corruptType = false
            prefs.commitSucceeds = false
            assertFalse(store.save(setOf("com.example.b")))
            prefs.corruptType = true
            prefs.commitSucceeds = true
            assertTrue(store.loadForRepair() is LoadResult.Corrupt)
            val second = remoteCorruptEvidence.get().entries.firstOrNull { it.key.get() === prefs }?.value
            assertTrue(second != null)
            assertEquals(gen1, second!!.observedGen)
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #7 transient 不清除已有 evidence。
    @Test
    fun evidence_transientFailureRetainsPriorEvidence() {
        resetRemotePoisonForTests()
        try {
            val prefs = RecordingPrefs()
            assertTrue(prefs.edit().putStringSet(
                PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                mutableSetOf("com.example.a"),
            ).commit())
            prefs.corruptType = true
            val store = remoteHookPolicyStore { prefs }
            assertTrue(store.loadResult() is LoadResult.Corrupt)
            assertTrue(hasCorruptEvidence(prefs))
            // transient 寫失敗（commit false）不得清除 CCE 證據。
            prefs.corruptType = false
            prefs.commitSucceeds = false
            assertFalse(store.save(setOf("com.example.b")))
            assertTrue(hasCorruptEvidence(prefs))
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Phase2 #8 全域 cap：相異實例風暴有界。
    @Test
    fun poison_capBoundedUnderDistinctInstanceStorm() {
        resetRemotePoisonForTests()
        try {
            // 持有強引用免 GC（否則 stale purge 即有界，測不到 cap 逐出）。
            val holders = mutableListOf<RecordingPrefs>()
            val evictBase = poisonEvictCount.get()
            repeat(150) { i ->
                val p = RecordingPrefs()
                holders.add(p)
                p.commitSucceeds = false
                val s = remoteHookPolicyStore { p }
                assertFalse(s.save(setOf("com.example.$i")))
            }
            assertTrue(remotePoison.get().entries.size <= POISON_MAX_ENTRIES)
            assertTrue(poisonEvictCount.get() - evictBase >= 150 - POISON_MAX_ENTRIES)
        } finally {
            resetRemotePoisonForTests()
        }
    }

    // Gap 測試用 prefs:commit 先樂觀 flush 再 (可選) 阻塞,最後回 commitSucceeds。
    // armBlock()/release() 控制阻塞;peekDirect() 直讀底層驗污染存在。
    private class GapCommitPrefs(var commitSucceeds: Boolean) : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()
        @Volatile var blockCommit = false
        @Volatile var commitEntered = java.util.concurrent.CountDownLatch(1)
        private val commitRelease = java.util.concurrent.atomic.AtomicBoolean(true)

        fun armBlock() {
            commitEntered = java.util.concurrent.CountDownLatch(1)
            commitRelease.set(false)
            blockCommit = true
        }

        fun release() {
            blockCommit = false
            commitRelease.set(true)
        }

        fun peekDirect(): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[PolicySource.POLICY_KEY_EXTRA_PACKAGES] as? MutableSet<String>)
        }

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            if (data[key] is String) data[key] as? String else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            val stored = data[key] as? MutableSet<String>
            return stored ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = GapEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class GapEditor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (value == null) {
                    removed.add(key!!)
                    pending.remove(key)
                } else {
                    pending[key!!] = value
                    removed.remove(key)
                }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key!!] = values?.toMutableSet()
                removed.remove(key)
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key!!] = value
                removed.remove(key)
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                removed.add(key!!)
                pending.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                removed.addAll(data.keys)
                pending.clear()
                return this
            }
            override fun commit(): Boolean {
                // 樂觀更新先行 (與真實 RemotePreferences doUpdate 先於 doCommit 一致)。
                for (k in removed) data.remove(k)
                for ((k, v) in pending) data[k] = v
                pending.clear()
                removed.clear()
                if (blockCommit) {
                    commitEntered.countDown()
                    while (!commitRelease.get()) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                return commitSucceeds
            }
            override fun apply() = throw UnsupportedOperationException("must use commit")
        }
    }

}
