// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase 1b：private Activity seam 存在性鎖定 (JVM 可跑，不碰 emulator)。
// 背景：RemoveConfirmationUiTest 經反射呼叫 private syncRemoveDialog 4-tuple +
// labelCacheKey，與生產零編譯期耦合 — 下次改名即 deterministic
// NoSuchMethodException，只在 emulator 才爆。此檔把簽名釘在 JVM 單測：
// 簽名漂移 (改名/改參/改回傳型別) 即紅，不必等 emulator。
package com.galaxydual.plus

import com.galaxydual.core.Privilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionSeamTest {

    // syncRemoveDialog(PendingRemove?, Boolean, BlockReason?, Int) 4-tuple：
    // Kotlin 可空擦除後即 (PendingRemove, boolean, BlockReason, int)，回傳 Unit。
    @Test
    fun syncRemoveDialog_fourTupleSignatureExists() {
        val m = MainActivity::class.java.getDeclaredMethod(
            "syncRemoveDialog",
            PendingRemove::class.java,
            Boolean::class.javaPrimitiveType,
            BlockReason::class.java,
            Int::class.javaPrimitiveType,
        )
        assertEquals("syncRemoveDialog must return Unit", Void.TYPE, m.returnType)
    }

    // labelCacheKey() 無參回傳 Int (catalog hash；sync＋指紋共用)。
    @Test
    fun labelCacheKey_noArgIntSignatureExists() {
        val m = MainActivity::class.java.getDeclaredMethod("labelCacheKey")
        assertEquals("labelCacheKey must return Int", Int::class.javaPrimitiveType, m.returnType)
    }

    // 指紋/快取 builder 只鎖「存在」(by-name)：Phase 1a 正在改其實作
    // (loading 移出指紋、epoch 守衛)，arity 鎖死會與平行修正打架；
    // 改名仍會紅 (存在性即本 seam 的 emulator 風險)。
    @Test
    fun keyBuilders_presentByName() {
        val names = MainActivity::class.java.declaredMethods.map { it.name }.toSet()
        for (name in listOf("buildClonedListKey", "buildSourceListKey", "syncLabelCache")) {
            assertTrue("MainActivity must keep a $name seam (rename breaks dialog/cache tests)", names.contains(name))
        }
    }

    // 非同步標籤回填 seam：首參 pkg (String)＋尾參回調。arity 不鎖死
    // (Phase 1a 可能追加 catalog epoch)，但 seam 消失/首參改型別即紅。
    @Test
    fun requestLabelAsync_takesPkgAndCallback() {
        val candidates = MainActivity::class.java.declaredMethods.filter { it.name == "requestLabelAsync" }
        assertTrue("MainActivity must keep a requestLabelAsync seam", candidates.isNotEmpty())
        assertTrue(
            "requestLabelAsync must keep (String, callback) shape",
            candidates.any { m ->
                m.parameterTypes.firstOrNull() == String::class.java &&
                    m.parameterTypes.size >= 2
            },
        )
    }

    // Single-source 政策釘 (JVM 可測層)：blocked-dialog 不得手造 BlockReason，
    // 唯一來源 writeBlockReason 必須對「被擋狀態全集」回非 null、可寫狀態回 null。
    // fail-closed：任何被擋 UiState 都拿得到拒絕理由，對話框無理由可手造。
    // 全集＝ writeBlockReason 每一回非 null 分支各一代表：NoOp 回退、明選失敗、
    // PM 不可讀、LSPOSED 佔位短路、AUTO 降級註記、就緒但無權限；分支增刪即改此表。
    // writeBlockReason 為同包頂層 internal 函數，JVM 直呼，無需 ViewModel 實例。
    @Test
    fun writeBlockReason_isTotalOverBlockedStates() {
        val blocked = listOf(
            "default NoOp fallback" to UiState(),
            "explicit channel unproven" to UiState(
                channel = ChannelPreference.ROOT,
                resolvedGateway = ResolvedChannel.ROOT,
                execIdentity = Privilege.ROOT,
                writeReady = false,
            ),
            "pm unreadable despite ready channel" to UiState(
                channel = ChannelPreference.ROOT,
                resolvedGateway = ResolvedChannel.ROOT,
                execIdentity = Privilege.ROOT,
                privilege = Privilege.ROOT,
                writeReady = true,
                pmReadable = false,
            ),
            "lsposed placeholder short-circuits everything" to UiState(
                resolvedGateway = ResolvedChannel.LSPOSED_PLACEHOLDER,
            ),
            "auto degrade explanation wins while unready" to UiState(
                channel = ChannelPreference.AUTO,
                writeReady = false,
                degrade = WriteBlock(WriteBlockKind.AUTO_DEGRADE_SU_TO_SHIZUKU),
            ),
            "ready channel but no privilege" to UiState(
                channel = ChannelPreference.ROOT,
                resolvedGateway = ResolvedChannel.ROOT,
                execIdentity = Privilege.ROOT,
                privilege = Privilege.NONE,
                writeReady = true,
                pmReadable = true,
            ),
        )
        for ((name, s) in blocked) {
            assertNotNull("blocked state [$name] must yield a BlockReason via writeBlockReason", writeBlockReason(s))
        }
        val writable = UiState(
            channel = ChannelPreference.ROOT,
            resolvedGateway = ResolvedChannel.ROOT,
            execIdentity = Privilege.ROOT,
            privilege = Privilege.ROOT,
            writeReady = true,
            pmReadable = true,
        )
        assertNull("fully ready state must yield no BlockReason", writeBlockReason(writable))
    }
}
