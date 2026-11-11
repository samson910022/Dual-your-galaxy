// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1, 階段 2):HookPolicyStore JVM 單測。
// - 寫後新 client 可重讀 (同一後端，新 HookPolicyStore 實例)。
// - 損壞恢復空 (拋出型損壞自清回空；非法 token 捨去不連坐)。
// - 取消保存不寫入 (未調 save 即為空；clear 只刪條目，不寫任何政策)。
//
// Harness:純記憶體 HashMap 後端，無 Android runtime、無協程；
// SharedPreferences 綁定 (remoteHookPolicyStore()) 與跨行程可見性
// (LSPosed RemotePreferences) 需 on-device 證明，見 HookPolicyStore 檔頭
// (DEVICE-UNVERIFIED)，此處不測。
package com.galaxydual.plus

import com.galaxydual.hook.PolicySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HookPolicyStoreTest {
    // 記憶體後端:key 缺席 = 無條目 (對應 getStringSet null 預設)。
    private class MemoryBackend {
        val backing = HashMap<String, Set<String>>()
        var clearCalls = 0
        fun store(): HookPolicyStore = HookPolicyStore(
            loadRaw = { backing[PolicySource.POLICY_KEY_EXTRA_PACKAGES] },
            saveRaw = { raw ->
                if (raw == null) {
                    clearCalls++
                    backing.remove(PolicySource.POLICY_KEY_EXTRA_PACKAGES)
                    true
                } else {
                    backing[PolicySource.POLICY_KEY_EXTRA_PACKAGES] = raw.toSet()
                    true
                }
            },
        )
        fun newClient(): HookPolicyStore = store()
    }

    @Test
    fun saveThenNewClientReadsSameDraft() {
        val backend = MemoryBackend()
        val draft = setOf("com.example.a", "com.example.b")
        assertTrue(backend.store().save(draft))
        // 新 client (新 HookPolicyStore 實例，同一後端) 重讀一致。
        assertEquals(draft, backend.newClient().load())
    }

    @Test
    fun freshStoreLoadsEmptyWithoutAnyWrite() {
        // 從未寫入：預設空 (不自動寫入，load 不產生條目)。
        val backend = MemoryBackend()
        assertTrue(backend.newClient().load().isEmpty())
        assertTrue(backend.backing.isEmpty())
    }

    @Test
    fun throwingBackendRecoversEmptyAndSelfClears() {
        // 型別錯亂 (如裝置上條目被誤寫成 String 致 ClassCastException)：
        // load 回空且主動清除損壞條目。
        var cleared = false
        val store = HookPolicyStore(
            loadRaw = { throw ClassCastException("String cannot be cast to Set") },
            saveRaw = { raw ->
                if (raw == null) cleared = true
                true
            },
        )
        assertTrue(store.load().isEmpty())
        assertTrue(cleared)
    }

    @Test
    fun invalidTokensDroppedWithoutPoisoningRest() {
        val backend = MemoryBackend()
        assertTrue(backend.store().save(setOf("com.ok.app", "bad pkg", "", "a/b")))
        // 非法行捨去，有效行保留 (不連坐整筆)。
        assertEquals(setOf("com.ok.app"), backend.newClient().load())
    }

    // 純函數直接測:非法 token（含空格、空字串）捨去,合法者保留。
    @Test
    fun sanitizeHookPolicy_dropsInvalidTokens() {
        assertEquals(
            setOf("com.ok.app"),
            sanitizeHookPolicy(setOf("com.ok.app", "bad pkg", "")),
        )
    }

    // 與 hook 側 PolicySource.sanitize 對齊:null token 一併捨去。
    @Test
    fun sanitizeHookPolicy_dropsNullTokens() {
        assertEquals(setOf("com.ok.app"), sanitizeHookPolicy(setOf("com.ok.app", null, "bad pkg")))
    }

    @Test
    fun clearRemovesDraftWithoutWritingAnything() {
        val backend = MemoryBackend()
        val store = backend.store()
        assertTrue(store.save(setOf("com.example.a")))
        // 取消保存：只刪條目；新 client 讀到空，且後端無殘留。
        assertTrue(store.clear())
        assertTrue(backend.newClient().load().isEmpty())
        assertTrue(backend.backing.isEmpty())
        // clear 計數：取消路徑只走刪除，不走寫入 (save 後 backing 只增不改)。
        assertEquals(1, backend.clearCalls)
    }


    @Test
    fun loadNonClassCastExceptionReturnsEmptyWithoutSelfClear() {
        var saveCalls = 0
        val store = HookPolicyStore(
            loadRaw = { throw RuntimeException("boom") },
            saveRaw = { _ ->
                saveCalls++
                true
            },
        )
        assertTrue(store.load().isEmpty())
        assertEquals(0, saveCalls)
    }

    @Test
    fun sanitizeHookPolicy_bounds() {
        val maxToken = "a".repeat(256)
        assertEquals(setOf(maxToken), sanitizeHookPolicy(setOf(maxToken)))
        val tooLong = "a".repeat(257)
        assertFalse(sanitizeHookPolicy(setOf(tooLong)).contains(tooLong))

        val many = (1..600).map { "p$it" }.toSet()
        assertEquals(512, sanitizeHookPolicy(many).size)

        assertEquals(setOf("a"), sanitizeHookPolicy(setOf("a", null)))
    }

    @Test
    fun nullProviderLoadResultIsUnknownNotKnownEmpty() {
        val store = HookPolicyStore(
            loadRaw = { null },
            saveRaw = { _ -> true },
        )
        assertTrue(store.loadResult() is LoadResult.Unknown)
        // Compat alias still returns empty for unknown.
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun confirmedEmptyDraftIsKnown() {
        val store = HookPolicyStore(
            loadRaw = { emptySet<String>() },
            saveRaw = { _ -> true },
        )
        assertTrue(store.loadResult() is LoadResult.Known)
    }

    @Test
    fun classCastLoadResultIsUnknown() {
        val store = HookPolicyStore(
            loadRaw = { throw ClassCastException("boom") },
            saveRaw = { _ -> true },
        )
        assertTrue(store.loadResult() is LoadResult.Unknown)
    }

    // CancellationException 透傳：loadRaw 拋 CE 時 loadResult/load 同步上拋
    //（store 非 suspend，不掛起，直接拋；不得被吞為 Unknown/空）。
    @Test
    fun loadResultCancellationExceptionRethrown() {
        val store = HookPolicyStore(
            loadRaw = { throw kotlinx.coroutines.CancellationException("cancel") },
            saveRaw = { _ -> true },
        )
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
    }

    // save 遇 CE 透傳（不轉 false）。
    @Test
    fun saveCancellationExceptionRethrown() {
        val store = HookPolicyStore(
            loadRaw = { emptySet<String>() },
            saveRaw = { _ -> throw kotlinx.coroutines.CancellationException("cancel") },
        )
        try {
            store.save(setOf("com.example.a"))
            org.junit.Assert.fail("save must rethrow CancellationException")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    // clear 遇 CE 透傳（不轉 false）。
    @Test
    fun clearCancellationExceptionRethrown() {
        val store = HookPolicyStore(
            loadRaw = { emptySet<String>() },
            saveRaw = { _ -> throw kotlinx.coroutines.CancellationException("cancel") },
        )
        try {
            store.clear()
            org.junit.Assert.fail("clear must rethrow CancellationException")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }
}
