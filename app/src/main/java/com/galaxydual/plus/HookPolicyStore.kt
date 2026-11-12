// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Stage2 (hook-policy-source P1, 階段 2):白名單政策草稿 store (原子單鍵)。
//
// 契約:
// - 保存內容:使用者勾選要放寬的包名集合 (plain package names,非 AppKey,
//   與 SelectionStore 的 "serial:package" 身分鍵分離；政策只關心包名)。
// - 預設空,不自動寫入:任何勾選變更 (toggle/selectVisible/clear) 永不觸碰
//   本 store；只有明確的寫入政策確認 (confirmPolicyWrite) 才存，且與
//   cloneSelected 的裝置寫入分離 (勾選≠安裝，寫草稿≠安裝)。
// - 寫入原子性:單一 string-set key 一次 commit (同步落盤,同行程/重啟後
//   皆可讀；集合僅數十包名，VM 側經 onIo 切 IO 線程，見 DualViewModel)。
// - 取消保存 (clear)/取消確認 (cancel):只刪持久化條目或什麼都不做，永不觸及
//   裝置寫入路徑 (≠卸載≠安裝)。
// - 損壞恢復:無法解析 (條目型別錯亂/ClassCastException 等) -> 視為 Unknown
//   (不拋出、不自清；Stage J3：永不 auto-delete，保留舊快照待顯式重讀)。
//
// 資料流 (Stage2 接線):
// - 本 store 由 remoteHookPolicyStore 綁定 LSPosed remote preferences (非
//   app-private SharedPreferences):app 經 GalaxyApp 取得 XposedService,呼叫
//   getRemotePreferences(group) 寫入/清除草稿;hook 側 DualHook 以
//   getRemotePreferences(同一 group/key) 讀取。
// - owner-user 需求:hook 讀 user 0;政策草稿必須由 owner user 寫入
//   (非 owner 用戶的寫入不會被 hook 讀到)。
// - 服務缺席 (未啟用 LSPosed/尚未取得 binder) 時 provider 回 null,load 回空、
//   save/clear 回 false,統一 fail-closed。
// - DEVICE-UNVERIFIED:JVM 測不到跨行程 LSPosed service 通道,app 寫入後 hook
//   是否真讀到,需 on-device 端到端證明;若讀不到,後續工作為確認 group/user
//   語意,而非改生效點 (WhitelistPatch/PolicySource 本階段不動)。
//
// JVM 可測:序列化/過濾皆為純函數；HookPolicyStore 只依賴注入的
// loadRaw/saveRaw lambdas (測試用 HashMap 後端,生產用
// remoteHookPolicyStore 綁定 LSPosed remote preferences)。
package com.galaxydual.plus

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.galaxydual.hook.PolicySource
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

// Stage L2 atomic poison fence (P2 #4 + P3a):single global AtomicReference<PoisonState>
// copy-on-write, weak keys, pre-poison seqlock.
//
// - 舊碼雙原子 (per-store failedPrefs + 全域強引用 set) 有發佈間隙:markFailed 先設
//   本地再加全域,兄弟 store 本地 null 可在間隙兩次 isGloballyPoisoned 皆命中前通過,
//   誤讀樂觀 mMap 為 Known。單 store barrier 測不到 distinct-store 交錯 (Factory
//   MainActivity 每 ViewModel 新建 remoteHookPolicyStore,跨 store 共享同 prefs
//   實例合理)。
// - 新碼單一全域 CAS:mark/ensure/clear 皆為一次 copy-on-write new map+gen+1,
//   無本地/全域兩步。讀者 seqlock:before=global.get();讀 mMap+快照拷貝;
//   after=global.get();若 after.gen!=before.gen 或 after 含 prefs 即 Unknown,
//   丟棄毒讀 (含併發失敗、併發成功清除、mid-getStringSet 突變)。
// - commit-vs-publish 窗:樂觀污染發生在第三方 commit.flush 內,早於舊 markFailed。
//   單 CAS 仍留「污染已發生、發佈未到」窗。故 save 採 pre-poison:resolve 後先 CAS
//   加毒 (gen+1,token=新 gen),再 commit;成功以 token 相等為條件清除 (防 lost-poison:
//   併發更新 token 更大即不清除),失敗再 ensure 加毒。重疊讀必見 gen 變化或毒而 Unknown
//   (成功併發的誤 Unknown 為 fail-closed 可接受)。
// - 弱鍵:WeakPrefKey (identity hash + WeakReference),map 強引 WeakKey、弱引 prefs;
//   GC 後條目 get()==null,永不命中活讀者 (活讀者必持強引),fresh resolve 重估。
//   每次寫入 purge stale (filterKeys get()!=null),讀者忽略 stale;無強引用洩漏 (P3a)。
// - 讀失敗 (含 CCE/暫時 IPC) 即 ensure 加毒後 Unknown (relatch):同實例短路零觸碰,
//   新身份探讀失敗續閂,皆 fail-closed。成功清除僅 token 相等才刪,避免蓋掉併發新毒。
// - resolvePrefs 在圍欄外 (provider 缺席未觸 prefs 即無污染可能,不設旗)。
// - CancellationException 透傳 (取消不得吞為 Unknown/false)。
internal data class PoisonState(val entries: Map<WeakPrefKey, Long>, val gen: Long)

internal class WeakPrefKey(referent: SharedPreferences) : WeakReference<SharedPreferences>(referent) {
    private val hash = System.identityHashCode(referent)
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WeakPrefKey) return false
        val a = this.get()
        val b = other.get()
        if (a == null || b == null) return false
        return a === b
    }
}

internal val remotePoison = AtomicReference(PoisonState(emptyMap(), 0))

// Injectable publish hook (tests only,生產 null):在每次 CAS 前調用,可供 latch-pause
// 模擬發佈間隙;生產路徑不依賴 (pre-poison 已關窗)。
internal var poisonPublishHook: (() -> Unit)? = null

internal fun resetRemotePoisonForTests() {
    remotePoison.set(PoisonState(emptyMap(), 0))
}

private fun isPoisoned(state: PoisonState, prefs: SharedPreferences): Boolean = try {
    state.entries.containsKey(WeakPrefKey(prefs))
} catch (_: Throwable) {
    false
}

// Pre-publish poison before commit (關 commit-vs-publish 窗)。永遠 bump gen 並以新 gen
// 為 token 覆寫 (即使已毒,亦更新為最新,保证重疊讀見 gen 變化)。回傳 ownToken 供成功清除比對。
private fun prePoison(prefs: SharedPreferences): Long {
    while (true) {
        val cur = remotePoison.get()
        val purged = if (cur.entries.isEmpty()) {
            cur.entries
        } else {
            try {
                cur.entries.filterKeys { it.get() != null }
            } catch (_: Throwable) {
                cur.entries
            }
        }
        val cleaned = if (purged.isEmpty()) {
            purged
        } else {
            try {
                purged.filterKeys { it.get() !== prefs }
            } catch (_: Throwable) {
                purged
            }
        }
        val newGen = cur.gen + 1
        val next = try {
            PoisonState(cleaned + (WeakPrefKey(prefs) to newGen), newGen)
        } catch (_: Throwable) {
            return cur.gen
        }
        try {
            poisonPublishHook?.invoke()
        } catch (_: Throwable) {
        }
        if (remotePoison.compareAndSet(cur, next)) return newGen
    }
}

private fun ensurePoisoned(prefs: SharedPreferences): Long = prePoison(prefs)

// 僅 token 相等才刪 (防 lost-poison:併發更新 token 更大即有更新毒,不清除)。
// 已乾淨時若有 stale 即 purge (bump 發佈),否則 no-op 不 bump。
private fun clearPoison(prefs: SharedPreferences, ownToken: Long): Boolean {
    while (true) {
        val cur = remotePoison.get()
        val curToken: Long? = try {
            cur.entries[WeakPrefKey(prefs)]
        } catch (_: Throwable) {
            null
        }
        if (curToken == null) {
            if (cur.entries.isEmpty()) return true
            val purged: Map<WeakPrefKey, Long> = try {
                cur.entries.filterKeys { it.get() != null }
            } catch (_: Throwable) {
                return true
            }
            if (purged.size == cur.entries.size) return true
            val next = PoisonState(purged, cur.gen + 1)
            try {
                poisonPublishHook?.invoke()
            } catch (_: Throwable) {
            }
            if (remotePoison.compareAndSet(cur, next)) return true
        } else {
            if (curToken != ownToken) return false
            val newMap: Map<WeakPrefKey, Long> = try {
                cur.entries.filterKeys { val r = it.get(); r != null && r !== prefs }
            } catch (_: Throwable) {
                return false
            }
            val next = PoisonState(newMap, cur.gen + 1)
            try {
                poisonPublishHook?.invoke()
            } catch (_: Throwable) {
            }
            if (remotePoison.compareAndSet(cur, next)) return true
        }
    }
}

// Phase 2 fail-closed 旗標：同實例失敗後不再試探（第 2 層已刪除，見下）。
// （曾有 REMOTE_POLICY_RETRY_AFTER_MS 同實例超時試探，現已移除以免誤用。）

// 過濾 (純函數):null 與非法 token 皆捨去 (不連坐整筆)，永不拋出;與 hook 側
// PolicySource.sanitize 的 null-token 語意對齊 (端到端快照可能跨行程帶入 null)。
// 文法單一來源:直接用 hook 側公開的 PolicySource.isValidPackage (同一
// [a-zA-Z0-9._-]+ 文法),避免 app/hook 各自維護 regex 而漂移。
fun sanitizeHookPolicy(raw: Set<String?>): Set<String> = PolicySource.sanitize(raw)

// 原子政策草稿 store。loadRaw/saveRaw 注入:
// - 生產:LSPosed remote preferences 單鍵 commit (見底部 remoteHookPolicyStore)。
// - 測試:HashMap 後端。
// saveRaw 回傳寫入是否成功；save/clear 自身捕捉例外並回傳 false，
// load 捕捉例外並自清 (見各方法註解)，故本類對呼叫端永不拋出。
// 讀取結果:區分「已確認值」(含確認空) 與「未知/無法讀取」(service 缺席、
// IPC 失敗) 與「損壞」(型別損壞 CCE)。UI/VM 不得把 Unknown/損壞當成確認空。
// Stage L4 P2#3 split: Unknown(interface) -> Unavailable(null/IPC/timeout/
// non-CCE) | Corrupt(CCE)。`is Unknown` 仍為「非 Known」總稱（保既有 fence 語義）；
// 值構造一律用 Unavailable/Corrupt（Unknown 本身不再有單例）。
sealed interface LoadResult {
    data class Known(val packages: Set<String>) : LoadResult
    sealed interface Unknown : LoadResult
    data object Unavailable : Unknown
    data object Corrupt : Unknown
}

// Stage L4 P2#3 repair primitive 結果（永不 auto-called，僅 VM 修復流程調用）。
sealed interface RepairResult {
    data object Repaired : RepairResult
    data object RepairFailed : RepairResult
    data object ServiceUnavailable : RepairResult
}

class HookPolicyStore(
    private val loadRaw: () -> Set<String>?,
    private val saveRaw: (Set<String>?) -> Boolean,
    // Stage L4: service 可用性探針（remote 傳入 provider!=null；測試預設 true）。
    // repairCorrupt 藉此區分 ServiceUnavailable vs RepairFailed。
    private val isServiceAvailable: () -> Boolean = { true },
) {
    // 讀草稿結果:損壞 (拋出，如條目型別錯亂的 ClassCastException) -> Corrupt，
    // 永不 auto-delete（Stage J3 P1：CCE 自清會在 untrusted type
    // confusion 下刪除有效草稿，fail-closed 要求保留舊快照、僅標 Corrupt；
    // 自癒僅經 repairCorrupt 備份＋清除，絕不直接刪主鍵）。
    // 與 remoteHookPolicyStore.loadRaw 的分工:provider() 解析失敗 (暫時性
    // IPC/service 缺席) 已在綁定層轉為 null (Unavailable、不自清,避免刪掉有效
    // 草稿);會走到 ClassCastException catch 的只剩值/型別損壞,亦不自清。
    // 其他例外 (例如 getStringSet 的暫時性 IPC 失敗) 視為 Unavailable,不清除
    // 有效草稿 (避免資料遺失)。
    // Phase 4:抓 Throwable (含 Error 如 AssertionError) 轉 Unavailable/false,
    // 但保留 CancellationException 透傳 (協程取消不得被吞為 Unavailable)。
    // 與 P1-2 對齊:store 層永不拋出 (取消除外)。
    // Stage L4: CCE（含其子類）一律 Corrupt；其餘 Throwable 一律 Unavailable。
    fun loadResult(): LoadResult = try {
        val raw = loadRaw()
        if (raw == null) LoadResult.Unavailable else LoadResult.Known(sanitizeHookPolicy(raw))
    } catch (_: ClassCastException) {
        // Stage J3:永不 auto-delete（見上）；保留舊快照，僅回 Corrupt。
        // Stage L4: CCE 即 Corrupt（非 Unavailable），觸發修復流程而非盲清。
        LoadResult.Corrupt
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        // 暫時性/其他非損壞錯誤 (含 Error):視為 Unavailable,不清除有效草稿。
        LoadResult.Unavailable
    }

    // 相容別名:回傳 Set (Unknown -> 空)。新程式請優先使用 loadResult()。
    // blind-overwrite 誤用防線:直接回空會把 Unknown (未確認) 偽裝成確認空，
    // 呼叫端若以此為基礎寫回即覆寫未見內容；故標 Deprecated,新程式一律用
    // loadResult() 區分 Known/Unknown。
    @Deprecated("Use loadResult()", ReplaceWith("loadResult()"))
    fun load(): Set<String> = when (val r = loadResult()) {
        is LoadResult.Known -> r.packages
        is LoadResult.Unknown -> emptySet()
    }

    // 保存 (原子單鍵 commit)。空集合亦寫出 (明確寫入空草稿)；非法 token
    // 在此過濾 (VM 層已按 catalog 映射，生產第二道，見 DualViewModel)。
    fun save(packages: Set<String>): Boolean = try {
        saveRaw(sanitizeHookPolicy(packages))
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        false
    }

    // 取消保存:只刪持久化條目，不碰任何裝置寫入路徑 (≠卸載≠安裝)。
    fun clear(): Boolean = try {
        saveRaw(null)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        false
    }

    // Stage L4 P2#3 顯式修復原語（永不 auto-called，僅 VM 修復流程調用）：
    // best-effort 備份至 dual_policy.bak.<ts>（writable 時，失敗忽略），
    // remove(KEY).commit()，post-read 驗證 Known(empty)。
    // - ServiceUnavailable：服務缺席（isServiceAvailable false 或 clear 回 false
    //   且當下不可寫）。
    // - Repaired：clear 成功＋post-read 為 Known(empty)。
    // - RepairFailed：其餘（clear 回 false/拋出、post-read 非空或仍 Unknown/Corrupt）。
    // CE 透傳（取消不得被吞為失敗）。
    fun repairCorrupt(): RepairResult {
        val available = try {
            isServiceAvailable()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
        if (!available) return RepairResult.ServiceUnavailable
        // Best-effort 備份（writable 時）：嘗試讀取原始物件字串化備份，失敗忽略。
        // 損壞值經 loadRaw 即拋 CCE，故此處僅能備份可讀值；損壞本體無法經型別
        // 安全通道備份，仍以清除＋驗證為主，備份失敗不擋修復。
        try {
            val ts = try { System.currentTimeMillis() } catch (_: Throwable) { 0L }
            backupCorruptBestEffort(ts)
        } catch (_: Throwable) {
            // Best effort: 備份失敗不擋修復。
        }
        val cleared: Boolean = try {
            saveRaw(null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return RepairResult.RepairFailed
        }
        if (!cleared) {
            val stillAvailable = try {
                isServiceAvailable()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                true
            }
            return if (!stillAvailable) RepairResult.ServiceUnavailable else RepairResult.RepairFailed
        }
        val after: LoadResult = try {
            loadResult()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return RepairResult.RepairFailed
        }
        return if (after is LoadResult.Known && after.packages.isEmpty()) {
            RepairResult.Repaired
        } else {
            RepairResult.RepairFailed
        }
    }

    // Best-effort 備份鉤子（預設 no-op；remote 覆寫經 prefs.all 備份）。
    // 失敗一律忽略，不擋修復。
    private var backupCorruptBestEffort: (Long) -> Unit = { _ -> }

    internal fun setBackupHook(hook: (Long) -> Unit) {
        backupCorruptBestEffort = hook
    }
}

// 生產綁定:LSPosed remote preferences (由 GalaxyApp 提供 XposedService)。
// provider 在每次操作時解析當今 service;service 缺席 (未啟用 LSPosed/尚未
// binder) 時 load 回空、save/clear 回 false,統一 fail-closed。
// 樂觀快取 (optimistic-cache) 風險與 Stage L2 單原子圍欄 (見上):
// RemotePreferences.Editor.commit() 先更新行程內 mMap (doUpdate) 再走 binder
// doCommit;doCommit 可因 RemoteException 回 false,此時 daemon 未落盤但同行程
// mMap 已被污染——同一 prefs 實例的後續 getStringSet 會讀到未確認的樂觀值，
// 若直接回 Known 即把「失敗的 save/clear」誤報為已確認。
// 圍欄為全域單原子 (無 per-store 本地旗,消除本地/全域發佈間隙 P2 #4)。
// 成功清除僅 token 相等才刪 (防 lost-poison 蓋掉併發新毒);讀者 seqlock 丟棄
// 任何 gen 變化期間的讀 (含 mid-getStringSet 突變與 clear 反向間隙,皆 fail-closed)。
// 有意用 commit() 而非 apply():需要同步落盤語意 (寫入/清除後立即一致);
// 集合僅數十包名,且 DualViewModel 經 onIo (Dispatchers.IO) 呼叫,不阻塞主執行緒。
@SuppressLint("ApplySharedPref")
fun remoteHookPolicyStore(
    provider: () -> SharedPreferences?,
): HookPolicyStore {
    fun resolvePrefs(): SharedPreferences? = try {
        provider()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }
    return HookPolicyStore(
        loadRaw = {
            // resolve 在圍欄外:provider 缺席未觸 prefs 即無污染可能。
            // Phase 4:provider 拋 Throwable (含 Error) 皆視為缺席 -> null
            // (Unavailable,不設毒);CancellationException 透傳。
            val prefs = resolvePrefs()
            // null = service/IPC unavailable (LoadResult.Unavailable);非 null 即
            // 已連上 service,鍵缺席為確認空 (Known 空),由外層 sanitize 區分。
            if (prefs == null) {
                null
            } else {
                val before = remotePoison.get()
                if (isPoisoned(before, prefs)) {
                    // 已毒短路 Unavailable,不讀毒 mMap,不觸碰 prefs (零 getStringSet)。
                    null
                } else {
                    try {
                        val v = prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, emptySet())
                            ?: emptySet()
                        // 快照拷貝後再驗證:併發 pre/ensure/clear 任一 bump 即丟棄,
                        // 含 mid-getStringSet 突變 (拷貝期 CME 即 catch 轉 Unavailable)。
                        val snap = v.toSet()
                        val after = remotePoison.get()
                        if (after.gen != before.gen || isPoisoned(after, prefs)) null else snap
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (cce: ClassCastException) {
                        // Stage L4: CCE 型別損壞即 Corrupt（非 Unavailable），加毒
                        // relatch 後重拋，由外層 loadResult 映射為 Corrupt；永不
                        // 吞為 Unavailable（免損壞走盲清），永不自清。
                        try {
                            ensurePoisoned(prefs)
                        } catch (_: Exception) {
                        }
                        throw cce
                    } catch (_: Throwable) {
                        // 暫時 IPC 失敗即加毒 relatch 後 Unavailable,
                        // 永不拋出觸自清 (J3 已無自清,此處統一吞為 Unavailable 並沾毒,
                        // 保證同實例短路、跨實例共毒 fail-closed)。
                        try {
                            ensurePoisoned(prefs)
                        } catch (_: Exception) {
                        }
                        null
                    }
                }
            }
        },
        saveRaw = { raw ->
            // provider 解析失敗 = 未觸及任何 prefs 實例 → 無 mMap 污染可能，
            // 不設毒、僅回 false (暫時性缺席經 load-only 恢復)。
            // Phase 4:含 Error 皆 fail-closed;取消透傳。
            val prefs: SharedPreferences? = resolvePrefs()
            if (prefs == null) {
                false
            } else {
                // 關 commit-vs-publish 窗:先 CAS 加毒再 commit,重疊讀必見 gen 變化。
                val ownToken = prePoison(prefs)
                try {
                    val ok: Boolean = if (raw == null) {
                        prefs.edit().remove(PolicySource.POLICY_KEY_EXTRA_PACKAGES).commit()
                    } else {
                        prefs.edit().putStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, raw.toSet())
                            .commit()
                    }
                    if (ok) {
                        clearPoison(prefs, ownToken)
                    } else {
                        try {
                            ensurePoisoned(prefs)
                        } catch (_: Exception) {
                        }
                    }
                    ok
                } catch (ce: CancellationException) {
                    try {
                        ensurePoisoned(prefs)
                    } catch (_: Exception) {
                    }
                    throw ce
                } catch (e: Throwable) {
                    // 已拿到實例後的 edit/commit 拋出 (如 binder RemoteException
                    // 或 Error):樂觀更新可能已污染 mMap，加毒後重拋由
                    // HookPolicyStore.save/clear 統一轉 false (取消除外)。
                    try {
                        ensurePoisoned(prefs)
                    } catch (_: Exception) {
                    }
                    throw e
                }
            }
        },
        isServiceAvailable = {
            try {
                provider() != null
            } catch (_: Throwable) {
                false
            }
        },
    ).also { store ->
        // Stage L4 best-effort 備份鉤子：writable 時嘗試將損壞值拷貝至
        // dual_policy.bak.<ts>，失敗忽略，不擋修復。
        store.setBackupHook { ts ->
            try {
                val prefs = try { provider() } catch (_: Throwable) { null } ?: return@setBackupHook
                val rawObj: Any? = try {
                    prefs.all[PolicySource.POLICY_KEY_EXTRA_PACKAGES]
                } catch (_: Throwable) {
                    null
                }
                if (rawObj != null) {
                    val bakKey = "dual_policy.bak.$ts"
                    try {
                        val asSet: Set<String>? = try {
                            @Suppress("UNCHECKED_CAST")
                            (rawObj as? Set<*>)?.mapNotNull { it?.toString() }?.toSet()
                        } catch (_: Throwable) {
                            null
                        }
                        if (asSet != null) {
                            prefs.edit().putStringSet(bakKey, asSet).commit()
                        } else {
                            prefs.edit().putStringSet(bakKey, setOf(rawObj.toString())).commit()
                        }
                    } catch (_: Throwable) {
                        // Best effort: 備份失敗不擋修復。
                    }
                }
            } catch (_: Throwable) {
                // Best effort.
            }
        }
    }
}
