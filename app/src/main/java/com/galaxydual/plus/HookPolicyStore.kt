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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

// Phase2 hardening bounds (fail-closed, bounded memory).
// - 備份單一固定鍵覆寫，不無界增長；value 截斷 ≤4KB；遷移批量刪上限
//   BACKUP_MIGRATION_MAX_KEYS/次（殘留下次再清）。
// - 毒/證據全域 cap：滿時不逐舊、只 bump gen + log + metric 並丟新（fail-closed；
//   毒另置 overflow 閂使次讀強制 Unknown）；讀寫皆 purge stale。
// - stale（GC 後）條目至多殘留至下次寫入 purge（P3a），接受殘留，不長期持有
//   prefs 強引用（WeakPrefKey 弱引）。
// - EVIDENCE_GEN_TTL：observedGen 過期即視為無證據並 best-effort 清除。
internal const val BACKUP_KEY = "dual_policy.bak"
internal const val BACKUP_LEGACY_PREFIX = "dual_policy.bak."
internal const val BACKUP_MAX_CHARS = 4096
internal const val BACKUP_MIGRATION_MAX_KEYS = 512
internal const val POISON_MAX_ENTRIES = 128
internal const val EVIDENCE_MAX_ENTRIES = 128
internal const val EVIDENCE_GEN_TTL = 32L
internal const val FALLBACK_CORRUPT_TYPE = "ClassCastException"

internal val poisonEvictCount = AtomicLong(0L)
internal val evidenceEvictCount = AtomicLong(0L)
internal val identityMismatchCount = AtomicLong(0L)
// 上次見到的 provider 實例身份 (identityHashCode)。0 = 無。
// 用於 wrapper-churn 自檢：連續兩次 resolve 身份不同即視為不穩定。
internal val lastPrefsHash = AtomicLong(0L)

internal fun logStore(msg: String) {
    try {
        System.err.println("[HookPolicyStore] $msg")
    } catch (_: Throwable) {
    }
}

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
//   每次寫入 purge stale (filterKeys get()!=null),讀者忽略 stale;stale 條目至多
//   殘留至下次寫入 purge (P3a)，不長期持有 prefs 強引用。
// - 讀失敗 (含 CCE/暫時 IPC) 即 ensure 加毒後 Unknown (relatch):同實例短路零觸碰,
//   新身份探讀失敗續閂,皆 fail-closed。成功清除僅 token 相等才刪,避免蓋掉併發新毒。
// - resolvePrefs 在圍欄外 (provider 缺席未觸 prefs 即無污染可能,不設旗)。
// - CancellationException 透傳 (取消不得吞為 Unknown/false)。
internal data class PoisonState(
    val entries: Map<WeakPrefKey, Long>,
    val gen: Long,
    // Fail-closed overflow latch: cap 滿丟新時置 true，此後一切讀強制 Unknown
    //（含未來新 before 的讀），僅 reset 清除。生產單實例永不觸發（cap 128）。
    val overflow: Boolean = false,
)

internal class WeakPrefKey(referent: SharedPreferences) : WeakReference<SharedPreferences>(referent) {
    // Note on identityHash collisions: two distinct prefs instances may share
    // the same identityHashCode (32-bit hash, birthday collisions possible).
    // Correctness never relies on the hash alone: equals() requires a === b
    // (referential identity on live referents, GC'd entries never match), so
    // a collision only costs a hash-bucket probe, never a false poison/evidence
    // hit. hashCode is snapshotted at construction so a GC'd key stays stable.
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

// Phase2 #3: poisonPublishHook seam 已刪除（GapCommitPrefs 已可模擬
// commit-vs-publish 窗；可注入全域 var 在 JVM 多執行緒下有 data race，
// @Volatile 亦不如刪除乾淨）。如需重演發佈間隙，一律用 GapCommitPrefs
// 阻塞 commit（見 HookPolicyRemoteStoreTest）。

internal fun resetRemotePoisonForTests() {
    remotePoison.set(PoisonState(emptyMap(), 0))
    try {
        remoteCorruptEvidence.set(emptyMap())
    } catch (_: Throwable) {
    }
    try {
        lastPrefsHash.set(0L)
        globalLastPrefsIdentity.set(null)
        testIdentityCheckHook = null
    } catch (_: Throwable) {
    }
    try {
        poisonEvictCount.set(0L)
    } catch (_: Throwable) {
    }
    try {
        evidenceEvictCount.set(0L)
    } catch (_: Throwable) {
    }
    try {
        identityMismatchCount.set(0L)
    } catch (_: Throwable) {
    }
}

@Volatile
internal var testIdentityCheckHook: (() -> Unit)? = null

internal val globalLastPrefsIdentity = AtomicReference<SharedPreferences?>(null)

// Phase2 #2 身份穩定自檢：provider() 連續兩次身份不同即視為不穩定
// （libxposed 承諾同一 group 永久同一實例；每次 new wrapper 即違約）。
// - 回傳 true = 穩定（或無毒可信），可繼續正常讀；false = 當次必須
//   fail-closed（回 Unavailable + log + 指標），不靜默信任。
// - 僅當毒/證據非空才 fail-closed（無毒時的合法 swap 如 service 初到
//   不擋）；當次僅 fail-closed，不新增毒（免讀風暴無界），但更新 last
//   使下一穩定讀可恢復（wrapper-churn 永不穩定故永 Unknown；合法 swap
//   首讀 Unknown、次讀穩定即 Known）。
// - 寫路徑不經此門（寫是重同步機會，必須放行以清毒），僅更新 last。
// - 使用強引用（prev === prefs）追蹤實體參照，消除 32-bit hash 碰撞風險與 GC 引發的 wrapper-churn 逃逸；
//   支援 per-store 實例隔離，避免多 store 交互干擾。
internal fun checkPrefsIdentityStable(
    prefs: SharedPreferences,
    lastRef: AtomicReference<SharedPreferences?> = globalLastPrefsIdentity,
): Boolean {
    testIdentityCheckHook?.invoke()
    val curHash = try {
        System.identityHashCode(prefs)
    } catch (_: Throwable) {
        0
    }
    try {
        lastPrefsHash.set(curHash.toLong())
    } catch (_: Throwable) {
    }

    val prev = try {
        lastRef.get()
    } catch (_: Throwable) {
        null
    }
    try {
        lastRef.set(prefs)
    } catch (_: Throwable) {
    }

    if (curHash == 0) {
        try {
            identityMismatchCount.incrementAndGet()
        } catch (_: Throwable) {
        }
        try {
            logStore("provider identity unverifiable (hash 0, fail-closed)")
        } catch (_: Throwable) {
        }
        val poisonedZero = try {
            val s = remotePoison.get()
            s.overflow || s.entries.isNotEmpty()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            true
        }
        val hasEvidenceZero = try {
            remoteCorruptEvidence.get().isNotEmpty()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            true
        }
        if (!poisonedZero && !hasEvidenceZero) return true
        return false
    }

    // 參照身分全等（或首讀無前值）即判定穩定
    if (prev == null || prev === prefs) return true

    try {
        identityMismatchCount.incrementAndGet()
    } catch (_: Throwable) {
    }
    try {
        val prevH = try { System.identityHashCode(prev) } catch (_: Throwable) { 0 }
        val curH = try { System.identityHashCode(prefs) } catch (_: Throwable) { 0 }
        logStore("provider identity changed prevHash=$prevH curHash=$curH (unstable wrapper?)")
    } catch (_: Throwable) {
    }
    val poisoned = try {
        val s = remotePoison.get()
        s.overflow || s.entries.isNotEmpty()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        true
    }
    val hasEvidence = try {
        remoteCorruptEvidence.get().isNotEmpty()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        true
    }
    // 無毒無證據時的不穩定為合法演進（如 service 初到），放行。
    if (!poisoned && !hasEvidence) return true
    return false
}

internal fun notePrefsIdentity(
    prefs: SharedPreferences,
    lastRef: AtomicReference<SharedPreferences?> = globalLastPrefsIdentity,
) {
    try {
        lastPrefsHash.set(System.identityHashCode(prefs).toLong())
    } catch (_: Throwable) {
    }
    try {
        lastRef.set(prefs)
    } catch (_: Throwable) {
    }
}

// 讀路徑 best-effort purge stale（同 gen，不 bump，不影響活讀者）。
internal fun tryPurgeStalePoisonNoBump() {
    try {
        while (true) {
            val cur = try {
                remotePoison.get()
            } catch (_: Throwable) {
                return
            }
            if (cur.entries.isEmpty()) return
            val purged: Map<WeakPrefKey, Long> = try {
                cur.entries.filterKeys { it.get() != null }
            } catch (_: Throwable) {
                return
            }
            if (purged.size == cur.entries.size) return
            val next = PoisonState(purged, cur.gen, cur.overflow)
            if (remotePoison.compareAndSet(cur, next)) return
        }
    } catch (_: Throwable) {
    }
}

// Stage N1 P1: per-instance corruption evidence (trusted CCE diagnosis) stored
// alongside (not instead of) the optimistic-value poison fence.
//
// - poison fence (remotePoison) quarantines the optimistic mMap value: once set,
//   normal reads short-circuit to Unavailable without touching prefs (fail-closed,
//   zero-touch). It does NOT distinguish CCE (Corrupt) from transient IPC
//   (Unavailable) — both set the same fence.
// - evidence (remoteCorruptEvidence) records that THIS prefs instance's fence was
//   caused by a trusted ClassCastException (first CCE key/type observed). Normal
//   reads still return Unavailable for value purposes, but the repair path can
//   consult evidence via loadForRepair()'s authoritative probe (live getStringSet
//   that re-throws CCE as Corrupt, never re-trusting a poisoned Set value).
// - cleared only on verified Known (unpoisoned successful read) or successful
//   save/clear resync (clearPoison token match). Transient failures retain prior
//   evidence; CCE re-records (first-wins per instance).
internal data class CorruptEvidence(val key: String, val typeName: String, val observedGen: Long)

internal val remoteCorruptEvidence = AtomicReference<Map<WeakPrefKey, CorruptEvidence>>(emptyMap())

private fun recordCorruptEvidence(prefs: SharedPreferences, cce: ClassCastException) {
    val keyName = try {
        PolicySource.POLICY_KEY_EXTRA_PACKAGES
    } catch (_: Throwable) {
        // 硬編 fallback：PolicySource 類初始化失敗時再取常量亦拋，免重拋。
        "extra_whitelist_packages"
    }
    val typeName = try {
        cce.javaClass.name
    } catch (_: Throwable) {
        FALLBACK_CORRUPT_TYPE
    }
    while (true) {
        val cur = try {
            remoteCorruptEvidence.get()
        } catch (_: Throwable) {
            return
        }
        val purged: Map<WeakPrefKey, CorruptEvidence> = if (cur.isEmpty()) {
            cur
        } else {
            try {
                cur.filterKeys { it.get() != null }
            } catch (_: Throwable) {
                cur
            }
        }
        val probeKey = try {
            WeakPrefKey(prefs)
        } catch (_: Throwable) {
            return
        }
        // First-wins: retain the original diagnosis for this instance.
        if (purged.containsKey(probeKey)) {
            if (purged.size == cur.size) return
            val next = purged
            if (remoteCorruptEvidence.compareAndSet(cur, next)) return
        } else {
            // 全域 cap：滿時不逐舊（fail-closed，保留舊診斷 first-wins）、丟新 +
            // metric + log。stale 已在 purged 中處理；若仍滿則直接丟新（有 stale
            // 才落盤 purge 結果），新實例本次無證據（值仍經毒圍欄 Unknown）。
            val base = purged
            if (base.size >= EVIDENCE_MAX_ENTRIES) {
                try {
                    evidenceEvictCount.incrementAndGet()
                } catch (_: Throwable) {
                }
                try {
                    logStore("evidence bound drop-new (size cap $EVIDENCE_MAX_ENTRIES)")
                } catch (_: Throwable) {
                }
                if (purged.size == cur.size) return
                if (remoteCorruptEvidence.compareAndSet(cur, purged)) return
            } else {
                val poisonGen = try {
                    remotePoison.get().gen
                } catch (_: Throwable) {
                    0L
                }
                val next = try {
                    base + (WeakPrefKey(prefs) to CorruptEvidence(keyName, typeName, poisonGen))
                } catch (_: Throwable) {
                    return
                }
                if (remoteCorruptEvidence.compareAndSet(cur, next)) return
            }
        }
    }
}

private fun clearCorruptEvidence(prefs: SharedPreferences) {
    while (true) {
        val cur = try {
            remoteCorruptEvidence.get()
        } catch (_: Throwable) {
            return
        }
        if (cur.isEmpty()) return
        val probeKey = try {
            WeakPrefKey(prefs)
        } catch (_: Throwable) {
            return
        }
        val next: Map<WeakPrefKey, CorruptEvidence> = try {
            cur.filterKeys { val r = it.get(); r != null && r !== prefs }
        } catch (_: Throwable) {
            return
        }
        if (next.size == cur.size) {
            // No entry for this instance, but purge stale if any.
            return
        }
        if (remoteCorruptEvidence.compareAndSet(cur, next)) return
    }
}

internal fun hasCorruptEvidence(prefs: SharedPreferences): Boolean {
    try {
        val cur = remoteCorruptEvidence.get()
        if (cur.isEmpty()) return false
        val ev = cur[WeakPrefKey(prefs)] ?: return false
        // Phase2 #7: observedGen 過期 — gen 推進 N 後失效（免陳舊證據永毒）。
        // 過期視為無證據並 best-effort 清除。
        val genNow = try {
            remotePoison.get().gen
        } catch (_: Throwable) {
            ev.observedGen
        }
        if (genNow - ev.observedGen > EVIDENCE_GEN_TTL) {
            try {
                while (true) {
                    val c2 = remoteCorruptEvidence.get()
                    if (c2.isEmpty()) break
                    val n2 = try {
                        c2.filterKeys { val r = it.get(); r != null && r !== prefs }
                    } catch (_: Throwable) {
                        break
                    }
                    if (n2.size == c2.size) break
                    if (remoteCorruptEvidence.compareAndSet(c2, n2)) break
                }
            } catch (_: Throwable) {
            }
            return false
        }
        return true
    } catch (_: Throwable) {
        return false
    }
}

private fun isPoisoned(state: PoisonState, prefs: SharedPreferences): Boolean = try {
    state.entries.containsKey(WeakPrefKey(prefs))
} catch (_: Throwable) {
    false
}

// Pre-publish poison before commit (關 commit-vs-publish 窗)。永遠 bump gen 並以新 gen
// 為 token 覆寫 (即使已毒,亦更新為最新,保证重疊讀見 gen 變化)。回傳 ownToken 供成功清除比對。
// 全域 cap：滿時不逐舊（fail-closed，保留舊毒）、只 bump gen + log + metric 並丟新，
// 另置 overflow 閂使次讀（含未來新 before 的讀）強制 Unknown。
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
        // 全域 cap：滿時不逐舊、丟新 + overflow 閂（fail-closed，免逐出舊毒致舊
        // 實例樂觀污染被誤信）。重疊讀見 gen 變化而 Unknown；未來讀見 overflow
        // 亦 Unknown（零觸碰短路）。僅 reset 清閂（生產單實例永不滿）。
        if (cleaned.size >= POISON_MAX_ENTRIES) {
            try {
                poisonEvictCount.incrementAndGet()
            } catch (_: Throwable) {
            }
            try {
                logStore("poison bound drop-new + overflow latch (size cap $POISON_MAX_ENTRIES)")
            } catch (_: Throwable) {
            }
            val newGen = cur.gen + 1
            val next = try {
                PoisonState(cleaned, newGen, overflow = true)
            } catch (_: Throwable) {
                return cur.gen
            }
            if (remotePoison.compareAndSet(cur, next)) return newGen
        } else {
            val newGen = cur.gen + 1
            val next = try {
                PoisonState(cleaned + (WeakPrefKey(prefs) to newGen), newGen, cur.overflow)
            } catch (_: Throwable) {
                return cur.gen
            }
            if (remotePoison.compareAndSet(cur, next)) return newGen
        }
    }
}

private fun ensurePoisoned(prefs: SharedPreferences): Long = prePoison(prefs)

// 僅 token 相等才刪 (防 lost-poison:併發更新 token 更大即有更新毒,不清除)。
// 已乾淨時若僅 stale 即 purge-only 不 bump（不影響活讀者）；僅真正增刪活條目才 bump。
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
            // Phase2 #4: 僅 stale purge，不 bump gen（活讀者不受影響）；保留 overflow 閂。
            val next = PoisonState(purged, cur.gen, cur.overflow)
            if (remotePoison.compareAndSet(cur, next)) return true
        } else {
            if (curToken != ownToken) return false
            val newMap: Map<WeakPrefKey, Long> = try {
                cur.entries.filterKeys { val r = it.get(); r != null && r !== prefs }
            } catch (_: Throwable) {
                return false
            }
            val next = PoisonState(newMap, cur.gen + 1, cur.overflow)
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
// load 捕捉例外回 Unknown 不刪鍵 (Unavailable/Corrupt，見各方法註解；
// Stage J3 永不 auto-delete，CCE 亦僅回 Corrupt 不自清)，故本類對呼叫端永不拋出。
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
    // Stage N1 P1: repair-specific authoritative probe (remote 傳入繞毒直探；
    // 直接注入後端預設 null → loadForRepair() 即 loadResult())。VM 修復 pre/final
    // 讀一律經 loadForRepair()，正常 refresh/save/clear 仍經 loadResult()。
    private val repairProbe: (() -> LoadResult)? = null,
) {
    // Stage N1 P1 repair-aware read: Corrupt evidence retained while value stays
    // fail-closed. Direct backends (repairProbe null) delegate to loadResult()
    // (no poison → already authoritative). Remote probe bypasses the poison
    // short-circuit with a live getStringSet: CCE → Corrupt (trusted), poisoned
    // Set success → Unavailable (never re-trust optimistic mMap), verified
    // Known → Known (+clears evidence). CE 透傳，其餘轉 Unavailable。
    fun loadForRepair(): LoadResult {
        val probe = repairProbe ?: return loadResult()
        try {
            return probe()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return LoadResult.Unavailable
        }
    }

    // Test/VM hook: whether THIS store's remote backend currently holds trusted
    // Corrupt evidence (poisoned by CCE). Direct backends (no probe) report
    // whether the latest load was Corrupt via a fresh loadResult() check? No —
    // to avoid extra IO, direct stores return false; callers use loadForRepair()
    // (== loadResult()) for the authoritative answer. Remote overrides via probe.
    fun hasCorruptEvidence(): Boolean {
        val probeHas: (() -> Boolean)? = corruptEvidenceProbe
        if (probeHas != null) {
            try {
                return probeHas()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                return false
            }
        }
        return false
    }

    @Volatile
    private var corruptEvidenceProbe: (() -> Boolean)? = null

    internal fun setCorruptEvidenceProbe(probe: () -> Boolean) {
        corruptEvidenceProbe = probe
    }
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

    // 保存 (原子單鍵 commit，全量取代 REPLACEMENT：putStringSet 整組覆寫，
    // 非合併累加；先寫 200 再寫 112 最終僅剩 112)。空集合亦寫出 (明確寫入空草稿)；非法 token
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

    // Stage L4 P2#3 顯式修復原語（永不 auto-called，僅 VM 修復流程調用；M4 P3）：
    // best-effort 備份至 dual_policy.bak.<ts>（writable 時；prefs.all 或型別錯亂值
    // 皆可能拋出，備份僅 best-effort、可能失敗、無法保證可恢復；備份失敗仍繼續
    // 刪除，不擋修復），remove(KEY).commit()，post-read 驗證 Known(empty)。
    // - ServiceUnavailable：服務缺席（isServiceAvailable false 或 clear 回 false
    //   且當下不可寫）。
    // - Repaired：clear 成功＋post-read 為 Known(empty)（備份不保證成功）。
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
        // Best-effort 備份（writable 時）：嘗試讀取原始物件字串化備份，prefs.all
        // 或型別錯亂值皆可能拋出；備份僅 best-effort、可能失敗、無法保證可恢復，
        // 失敗仍繼續刪除（不擋修復）。故損壞修復為破壞性操作，備份不能視為保證。
        // 損壞值經 loadRaw 即拋 CCE，故此處僅能備份可讀值；損壞本體無法經型別
        // 安全通道備份，仍以清除＋驗證為主，備份失敗不擋修復。
        try {
            val ts = try { System.currentTimeMillis() } catch (_: Throwable) { 0L }
            backupCorruptBestEffort(ts)
        } catch (ce: CancellationException) {
            throw ce
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
//
// provider() 身分快取要求 (identity-caching requirement):毒圍欄與損壞證據皆以
// SharedPreferences 實例身分為鍵 (WeakPrefKey = identity hash + ===)。provider()
// 必須對同一 group 永遠回傳同一實例 —— 生產經 GalaxyApp.remotePreferences 直通
// libxposed XposedService.getRemotePreferences,而該 API 為 computeIfAbsent 永久
// 快取,同一行程內同一 group 保證同一實例,故身分鍵有效。若 libxposed 未來改為每次
// 回傳新的 wrapper 實例,身分鍵將永遠 miss (毒圍欄漏毒、證據錯位),此時必須改以
// group 字串為鍵,而非實例身分。
@SuppressLint("ApplySharedPref")
fun remoteHookPolicyStore(
    provider: () -> SharedPreferences?,
): HookPolicyStore {
    val storeIdentityRef = AtomicReference<SharedPreferences?>(null)
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
                // Phase2 #2: 身份穩定自檢 — wrapper-churn 當次 fail-closed。
                try {
                    if (!checkPrefsIdentityStable(prefs, storeIdentityRef)) return@HookPolicyStore null
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    return@HookPolicyStore null // Fail closed!
                }
                // Phase2 #8: 讀路徑亦 purge stale（同 gen 不 bump）。
                try {
                    tryPurgeStalePoisonNoBump()
                } catch (_: Throwable) {
                }
                val before = remotePoison.get()
                if (before.overflow || isPoisoned(before, prefs)) {
                    // 已毒/溢出短路 Unavailable,不讀毒 mMap,不觸碰 prefs (零 getStringSet)。
                    // Stage N1 P1: value fail-closed，但 Corrupt evidence 保留供修復路徑。
                    null
                } else {
                    try {
                        val v = prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, emptySet())
                            ?: emptySet()
                        // 快照拷貝後再驗證:併發 pre/ensure/clear 任一 bump 即丟棄,
                        // 含 mid-getStringSet 突變 (拷貝期 CME 即 catch 轉 Unavailable)。
                        val snap = v.toSet()
                        val after = remotePoison.get()
                        if (after.gen != before.gen || after.overflow || isPoisoned(after, prefs)) {
                            null
                        } else {
                            // Verified Known:後端已證可信，洗白 Corrupt evidence。
                            // (Stage N1 P1: evidence cleared only on verified Known.)
                            try {
                                clearCorruptEvidence(prefs)
                            } catch (_: Throwable) {
                            }
                            snap
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (cce: ClassCastException) {
                        // Stage L4: CCE 型別損壞即 Corrupt（非 Unavailable），加毒
                        // relatch 後重拋，由外層 loadResult 映射為 Corrupt；永不
                        // 吞為 Unavailable（免損壞走盲清），永不自清。
                        // Stage N1 P1: 同時記錄 trusted corruption evidence (first-wins)，
                        // 供修復路徑在毒短路下仍能辨識 Corrupt vs transient。
                        try {
                            ensurePoisoned(prefs)
                        } catch (_: Exception) {
                        }
                        try {
                            recordCorruptEvidence(prefs, cce)
                        } catch (_: Exception) {
                        }
                        throw cce
                    } catch (_: Throwable) {
                        // 暫時 IPC 失敗即加毒 relatch 後 Unavailable,
                        // 永不拋出觸自清 (J3 已無自清,此處統一吞為 Unavailable 並沾毒,
                        // 保證同實例短路、跨實例共毒 fail-closed)。
                        // Stage N1 P1: transient 不記錄/不清除 Corrupt evidence
                        // (保留先前 CCE 診斷；若本無 evidence 則仍無)。
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
                // 寫是重同步機會：僅更新身份（不 fail-closed），使合法新實例
                // 的後續讀可穩定恢復。
                try {
                    notePrefsIdentity(prefs, storeIdentityRef)
                } catch (_: Throwable) {
                }
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
                        val cleared = clearPoison(prefs, ownToken)
                        if (cleared) {
                            // Stage N1 P1:成功重同步即洗白 evidence (verified resync)。
                            // Token 失配 (併發新毒) 則保留，不蓋掉併發診斷。
                            try {
                                clearCorruptEvidence(prefs)
                            } catch (_: Exception) {
                            }
                        }
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
        },
        repairProbe = {
            // Stage N1 P1 authoritative repair probe:繞過毒短路，直探後端型別。
            // - CCE → Corrupt (trusted，不回 Unavailable；record+relatch)。
            // - 成功但毒 (before/after 任一毒或 gen 變) → Unavailable (絕不重信毒 mMap)。
            // - 成功且無毒 → Known (verified，洗白 evidence)。
            // - 其他 Throwable → Unavailable (+relatch，保留既有 evidence)。
            // - null provider → Unavailable。CE 透傳。
            // Phase2 #2: 身份不穩定當次亦 Unavailable（wrapper-churn 不信）。
            // Phase2 #6: 空快照 + gen 推進但無毒時，以 prefs.all 二次確認
            // （成功 clear 的反向間隙不應誤判污染；二次仍空且無毒才 Known）。
            val prefs = try {
                provider()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            if (prefs == null) {
                LoadResult.Unavailable
            } else {
                val identityOk = try {
                    checkPrefsIdentityStable(prefs, storeIdentityRef)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    false // Fail closed!
                }
                if (!identityOk) {
                    LoadResult.Unavailable
                } else {
                    val before = try {
                        remotePoison.get()
                    } catch (_: Throwable) {
                        return@HookPolicyStore LoadResult.Unavailable
                    }
                    try {
                        val v = prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, emptySet())
                            ?: emptySet()
                        val snap = v.toSet()
                        val after = try {
                            remotePoison.get()
                        } catch (_: Throwable) {
                            return@HookPolicyStore LoadResult.Unavailable
                        }
                        val poisonedNow = try {
                            before.overflow || after.overflow ||
                                isPoisoned(before, prefs) || isPoisoned(after, prefs)
                        } catch (_: Throwable) {
                            true
                        }
                        if (after.gen != before.gen || after.overflow || poisonedNow) {
                            // Phase2 #6: 空快照 + 無毒（含無溢出）+ 僅 gen 推進 → 可能是成功
                            // clear 的反向間隙（pre→commit→clear 兩次 bump），以
                            // prefs.all 二次確認；二次仍空且當下無毒才信為 Known，
                            // 否則維持 Unavailable（fail-closed）。
                            val genOnlyNoPoison = try {
                                !poisonedNow && !after.overflow && snap.isEmpty()
                            } catch (_: Throwable) {
                                false
                            }
                            if (genOnlyNoPoison) {
                                // String 型錯亂（cur 非 Set 非 null）不得確認為空；
                                // 僅 null（鍵缺席）或空 Set 才確認。
                                val confirmedEmpty: Boolean = try {
                                    val all = prefs.all
                                    val cur = all[PolicySource.POLICY_KEY_EXTRA_PACKAGES]
                                    when {
                                        cur == null -> true
                                        cur is Set<*> -> cur.isEmpty()
                                        else -> false
                                    }
                                } catch (_: Throwable) {
                                    false
                                }
                                // prefs.all 與 stillClean 間的併發 prePoison 只 bump
                                // gen（成功 clear 反向間隙外的真污染）亦須 Unknown。
                                val stillClean: Boolean = try {
                                    val now = remotePoison.get()
                                    !now.overflow && now.gen == after.gen && !isPoisoned(now, prefs)
                                } catch (_: Throwable) {
                                    false
                                }
                                if (confirmedEmpty && stillClean) {
                                    try {
                                        clearCorruptEvidence(prefs)
                                    } catch (_: Throwable) {
                                    }
                                    LoadResult.Known(sanitizeHookPolicy(snap))
                                } else {
                                    LoadResult.Unavailable
                                }
                            } else {
                                LoadResult.Unavailable
                            }
                        } else {
                            try {
                                clearCorruptEvidence(prefs)
                            } catch (_: Throwable) {
                            }
                            LoadResult.Known(sanitizeHookPolicy(snap))
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (cce: ClassCastException) {
                        try {
                            ensurePoisoned(prefs)
                        } catch (_: Exception) {
                        }
                        try {
                            recordCorruptEvidence(prefs, cce)
                        } catch (_: Exception) {
                        }
                        LoadResult.Corrupt
                    } catch (_: Throwable) {
                        try {
                            ensurePoisoned(prefs)
                        } catch (_: Exception) {
                        }
                        LoadResult.Unavailable
                    }
                }
            }
        },
    ).also { store ->
        // Stage N1 P1: evidence probe for hasCorruptEvidence() (zero-touch,
        // 僅查 evidence map，不碰 prefs getStringSet)。
        store.setCorruptEvidenceProbe {
            val prefs = try {
                provider()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } ?: return@setCorruptEvidenceProbe false
            hasCorruptEvidence(prefs)
        }
        // Stage L4 best-effort 備份鉤子（Phase2 #1：單一固定鍵覆寫，不無界增長）。
        // - 固定鍵 dual_policy.bak 覆寫（不再 dual_policy.bak.<ts> 無界增長）；
        //   每次修復先遷移清除舊 bak.* 鍵（至多殘留 1 個新鍵）。
        // - value 截斷 ≤4KB：超限只存「類型名+長度」單條（免大 value 撐爆 prefs）。
        // - 僅 best-effort：prefs.all 或型別錯亂值可能拋出，備份可能失敗、
        //   無法保證可恢復；失敗忽略仍刪除，不擋修復。
        store.setBackupHook { _ ->
            try {
                val prefs = try {
                    provider()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                } ?: return@setBackupHook
                // 單快照供遷移 + 備份共用（免兩次 prefs.all 全掃）；all 失敗即跳過
                // 遷移/備份，不擋修復（best-effort）。
                val snapshot: Map<String, *> = try {
                    prefs.all
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    return@setBackupHook
                }
                // 遷移：舊 bak.* 鍵單 edit 批量刪，上限前 N/次（殘留下次再清）。
                val legacy: List<String> = try {
                    snapshot.keys.filter { it.startsWith(BACKUP_LEGACY_PREFIX) }
                        .take(BACKUP_MIGRATION_MAX_KEYS)
                } catch (_: Throwable) {
                    emptyList()
                }
                val rawObj: Any? = try {
                    snapshot[PolicySource.POLICY_KEY_EXTRA_PACKAGES]
                } catch (_: Throwable) {
                    null
                }
                val backupSet: Set<String>? = if (rawObj != null) {
                    try {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val asSet: Set<String>? = try {
                                (rawObj as? Set<*>)?.mapNotNull { it?.toString() }?.toSet()
                            } catch (_: Throwable) {
                                null
                            }
                            if (asSet != null) {
                                // 截斷：總字元超限只存類型+大小。
                                var total = 0
                                for (s in asSet) total += s.length
                                if (total > BACKUP_MAX_CHARS) {
                                    setOf("${rawObj.javaClass.name}:${asSet.size}")
                                } else {
                                    // 逐條亦截斷單條超長（防單包名異常大）。
                                    asSet.map { if (it.length > BACKUP_MAX_CHARS) it.take(BACKUP_MAX_CHARS) else it }.toSet()
                                }
                            } else {
                                // 非 Set（String 型錯亂等）：走 toString 分支。
                                val s = try { rawObj.toString() } catch (_: Throwable) { null }
                                if (s == null) {
                                    setOf("${rawObj.javaClass.name}:unreadable")
                                } else if (s.length > BACKUP_MAX_CHARS) {
                                    setOf("${rawObj.javaClass.name}:${s.length}")
                                } else {
                                    setOf(s)
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                            null
                        } ?: setOf("${rawObj.javaClass.name}:unreadable")
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        // Best effort: 備份失敗不擋修復。
                        null
                    }
                } else {
                    null
                }
                if (legacy.isEmpty() && backupSet == null) return@setBackupHook
                try {
                    val ed = prefs.edit()
                    for (k in legacy) {
                        try {
                            ed.remove(k)
                        } catch (_: Throwable) {
                        }
                    }
                    if (backupSet != null) {
                        try {
                            ed.putStringSet(BACKUP_KEY, backupSet)
                        } catch (_: Throwable) {
                        }
                    }
                    // 單次 commit 批量落盤（遷移 + 備份同一事務）。
                    try {
                        ed.commit()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Best effort: 備份失敗不擋修復。
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Best effort.
            }
        }
    }
}
