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
// - 損壞恢復:無法解析 (條目型別錯亂/ClassCastException 等) -> 視為無草稿
//   (不拋出)；主動清除損壞條目，下一次 save 覆寫即恢復可用。
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

// Stage J2 cross-instance poison fence (shared mMap): see below.
private val remotePoisonedInstances: MutableSet<android.content.SharedPreferences> =
    java.util.concurrent.ConcurrentHashMap.newKeySet<android.content.SharedPreferences>()

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
// IPC 失敗、型別損壞)。UI/VM 不得把 Unknown 當成確認空。
sealed interface LoadResult {
    data class Known(val packages: Set<String>) : LoadResult
    object Unknown : LoadResult
}

class HookPolicyStore(
    private val loadRaw: () -> Set<String>?,
    private val saveRaw: (Set<String>?) -> Boolean,
) {
    // 讀草稿結果:損壞 (拋出，如條目型別錯亂的 ClassCastException) -> 視為
    // Unknown 並主動清除損壞條目 (fail closed；下次 save 覆寫即恢復)。
    // 與 remoteHookPolicyStore.loadRaw 的分工:provider() 解析失敗 (暫時性
    // IPC/service 缺席) 已在綁定層轉為 null (Unknown、不自清,避免刪掉有效
    // 草稿);會走到 ClassCastException catch 的只剩值/型別損壞,故自清。
    // 其他例外 (例如 getStringSet 的暫時性 IPC 失敗) 視為 Unknown,不清除
    // 有效草稿 (避免資料遺失)。
    // Phase 4:抓 Throwable (含 Error 如 AssertionError) 轉 Unknown/false,
    // 但保留 CancellationException 透傳 (協程取消不得被吞為 Unknown)。
    // 與 P1-2 對齊:store 層永不拋出 (取消除外)。
    fun loadResult(): LoadResult = try {
        val raw = loadRaw()
        if (raw == null) LoadResult.Unknown else LoadResult.Known(sanitizeHookPolicy(raw))
    } catch (e: ClassCastException) {
        try {
            saveRaw(null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Best effort:下次 save 覆寫即恢復。
        }
        LoadResult.Unknown
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        // 暫時性/其他非損壞錯誤 (含 Error):視為 Unknown,不清除有效草稿。
        LoadResult.Unknown
    }

    // 相容別名:回傳 Set (Unknown -> 空)。新程式請優先使用 loadResult()。
    // blind-overwrite 誤用防線:直接回空會把 Unknown (未確認) 偽裝成確認空，
    // 呼叫端若以此為基礎寫回即覆寫未見內容；故標 Deprecated,新程式一律用
    // loadResult() 區分 Known/Unknown。
    @Deprecated("Use loadResult()", ReplaceWith("loadResult()"))
    fun load(): Set<String> = when (val r = loadResult()) {
        is LoadResult.Known -> r.packages
        LoadResult.Unknown -> emptySet()
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
}

// 生產綁定:LSPosed remote preferences (由 GalaxyApp 提供 XposedService)。
// provider 在每次操作時解析當今 service;service 缺席 (未啟用 LSPosed/尚未
// binder) 時 load 回空、save/clear 回 false,統一 fail-closed。
// 樂觀快取 (optimistic-cache) 風險與單原子圍欄:
// RemotePreferences.Editor.commit() 先更新行程內 mMap (doUpdate) 再走 binder
// doCommit;doCommit 可因 RemoteException 回 false,此時 daemon 未落盤但同行程
// mMap 已被污染——同一 prefs 實例的後續 getStringSet 會讀到未確認的樂觀值，
// 若直接回 Known 即把「失敗的 save/clear」誤報為已確認。單純每次重解 provider
// 無法修復 (同一快取實例)。故綁定閉包內以單一 AtomicReference<SharedPreferences?>
// failedPrefs 追蹤 (null=乾淨，非 null=最後一次失敗的實例；取代舊 needsRefetch+
// failedPrefs 雙原子，消除 (true,null)/(false,non-null) 撕裂態)：
// 唯有「已拿到 prefs 實例且 commit 回 false 或 edit/commit 拋出」才標記
// (含 clear(false));唯有 success commit 才以 CAS 清旗，讀數重新可信。
// provider() 回 null 或拋出時未觸及任何 prefs 實例 → mMap 不可能被污染，故
// 不標記、僅回 false；暫時性缺席經 load-only 恢復 (下次 load 直接重解
// provider,無需先成功寫入)。
// 圍欄為 per-store-instance，provider 更替後仍保留 (by design，唯有成功 commit
// 才清除)，以涵蓋「同一快取實例被污染後 provider 重解仍指回它」的情形。
// Stage J2 新增：
// - 讀後再驗證 (post-read revalidation)：每次 getStringSet 成功後、回 Known 前
//   重檢圍欄；若期間併發失敗改寫圍欄（同一或他實例）即回 Unknown，不回毒值。
//   確保 refetch 成功不贏過併發同實例 failed commit；保存成功清旗僅 CAS 同實例
//   (compareAndSet(prefs,null))，避免蓋掉併發新失敗。
// - 跨實例全域毒集合 remotePoisonedInstances：同一 prefs 實例被任一 store 標記後，
//   全行程所有 store 讀到它皆 Unknown（含從未失敗的 B store），直到成功重同步
//   移出；解決共享 mMap 下「A 失敗、B 讀毒誤報 Known」。成功即移除，失敗即加入。
// Phase 2 恢復（僅第 1 層身份穿透；第 2 層同實例超時試探已刪除）：
// - 同實例 (prefs === failedPrefs) 在圍欄下永回 null/Unknown，直到一次成功
//   save/clear 清旗；不讀 prefs、不自清（fail-closed 優先，可用性讓位）。
// - 第 1 層（新身份穿透）為 DEVICE-UNVERIFIED 實驗路徑，生產行為保留穿透但加
//   讀後圍欄再驗證：新身份 === 比較僅假設「新 binder 即新 mMap」，未經 on-device
//   證明；若 LSPosed 同 group 永回同一快取實例則此層生產永不命中；若 wrapper
//   共享底層 mMap 則仍有污染風險，全域毒集合提供第二道防線。詳見 docs/HOOK.md。
//   TODO(on-device):以真機驗證 daemon 重建後新實例是否重同步權威值、
//   同 group 是否回同一實例、wrapper 是否共享 mMap；驗證前此層視為實驗。
// - 探讀失敗永不觸 CCE 自清：探讀內部吞掉所有例外回 null（Unknown），
//   外層 HookPolicyStore.loadResult 的 CCE 自清只走「無圍欄快照讀」路徑，
//   避免把有效草稿刪掉。
// 有意用 commit() 而非 apply():需要同步落盤語意 (寫入/清除後立即一致);
// 集合僅數十包名,且 DualViewModel 經 onIo (Dispatchers.IO) 呼叫,不阻塞主執行緒。
@SuppressLint("ApplySharedPref")
fun remoteHookPolicyStore(
    provider: () -> SharedPreferences?,
): HookPolicyStore {
    // Stage J2 單原子圍欄：null=乾淨，非 null=最後失敗實例。
    val failedPrefs = AtomicReference<SharedPreferences?>(null)
    fun markFailed(prefs: SharedPreferences?) {
        if (prefs == null) return
        failedPrefs.set(prefs)
        try {
            remotePoisonedInstances.add(prefs)
        } catch (_: Exception) {
            // Best effort.
        }
    }
    fun clearForSuccess(prefs: SharedPreferences) {
        try {
            remotePoisonedInstances.remove(prefs)
        } catch (_: Exception) {
            // Best effort.
        }
        // 僅同實例 CAS 清旗，避免蓋掉併發新失敗（不同實例失敗仍保留）。
        failedPrefs.compareAndSet(prefs, null)
    }
    fun isGloballyPoisoned(prefs: SharedPreferences): Boolean = try {
        remotePoisonedInstances.contains(prefs)
    } catch (_: Exception) {
        false
    }
    fun resolvePrefs(): SharedPreferences? = try {
        provider()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }
    return HookPolicyStore(
        // 防禦性拷貝由 HookPolicyStore.load 的 sanitizeHookPolicy 統一負責
        // (複製進新 LinkedHashSet),故此处不再另行 toSet (多餘拷貝且不可測)。
        // 只包覆 provider() 解析:service/IPC 暫時性失敗 (如 XposedService
        // .ServiceException) 視為「缺席」-> 回空且「不」觸發 load() 自清,才不會
        // 刪掉有效草稿;值/型別錯亂 (ClassCastException) 則向上拋,由 load() 統一
        // 損壞自清 (見 HookPolicyStore.load 註解)。
        loadRaw = {
            val observed = failedPrefs.get()
            if (observed == null) {
                // Phase 4:provider 拋 Throwable (含 Error) 皆視為缺席 -> null
                // (Unknown,不自清);CancellationException 透傳 (協程取消)。
                val prefs = resolvePrefs()
                // null = service/IPC unavailable (LoadResult.Unknown); a non-null
                // prefs means the key is confirmed, even when it is absent (empty).
                if (prefs == null) {
                    null
                } else if (isGloballyPoisoned(prefs)) {
                    // J2 跨實例毒：他 store 已標記同一實例，本 store 亦沾黏旗標後
                    // 短路 Unknown，不讀毒 mMap。
                    failedPrefs.compareAndSet(null, prefs)
                    null
                } else {
                    try {
                        val v = prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, emptySet())
                            ?: emptySet()
                        // J2 讀後再驗證：併發失敗（本地圍欄升起或全域毒新增）即
                        // 作廢本次讀，回 Unknown，不回毒值。
                        if (failedPrefs.get() != null || isGloballyPoisoned(prefs)) null else v
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (cce: ClassCastException) {
                        // 真損壞才上拋自清；若併發已升旗則吞為 Unknown，免刪有效草稿。
                        if (failedPrefs.get() != null || isGloballyPoisoned(prefs)) null else throw cce
                    } catch (_: Throwable) {
                        null
                    }
                }
            } else {
                // 圍欄下：provider 未解出（null/拋出）→ 仍 Unknown，不碰圍欄。
                val prefs = resolvePrefs()
                if (prefs == null) {
                    null
                } else if (prefs === observed) {
                    // 同實例：原則永回 Unknown；但若全域已解毒（他 store 同實例成功
                    // 重同步），則跟隨解毒（CAS 清旗後當乾淨讀，讀後仍再驗證）。
                    if (!isGloballyPoisoned(prefs)) {
                        failedPrefs.compareAndSet(observed, null)
                        try {
                            val v2 = prefs.getStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, emptySet()) ?: emptySet()
                            if (failedPrefs.get() != null || isGloballyPoisoned(prefs)) null else v2
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (cce: ClassCastException) {
                            if (failedPrefs.get() != null || isGloballyPoisoned(prefs)) null else throw cce
                        } catch (_: Throwable) {
                            null
                        }
                    } else null
                } else if (isGloballyPoisoned(prefs)) {
                    // 新身份但全域已毒（他 store 失敗過）：續閂到當今實例，不讀。
                    try {
                        failedPrefs.set(prefs)
                    } catch (_: Exception) {
                    }
                    null
                } else {
                    // 新身份穿透（DEVICE-UNVERIFIED 實驗路徑，見上）＋讀後再驗證：
                    // 成功（含確認空）且圍欄未變才 CAS 清旗回 Known；圍欄併發改變
                    // 即 Unknown；失敗仍 Unknown 且續閂到當今實例；一律吞掉回 null，
                    // 永不觸 CCE 自清。
                    try {
                        val v = prefs.getStringSet(
                            PolicySource.POLICY_KEY_EXTRA_PACKAGES,
                            emptySet(),
                        ) ?: emptySet()
                        val cur = failedPrefs.get()
                        if (cur !== observed) {
                            // 期間圍欄改變：併發成功已清旗 (null) 且無全域毒才可信，
                            // 否則（併發新失敗）一律 Unknown。
                            if (cur == null && !isGloballyPoisoned(prefs)) v else null
                        } else if (isGloballyPoisoned(prefs)) {
                            try {
                                failedPrefs.set(prefs)
                            } catch (_: Exception) {
                            }
                            null
                        } else {
                            if (failedPrefs.compareAndSet(observed, null)) v else null
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        try {
                            failedPrefs.set(prefs)
                        } catch (_: Exception) {
                        }
                        try {
                            remotePoisonedInstances.add(prefs)
                        } catch (_: Exception) {
                        }
                        null
                    }
                }
            }
        },
        saveRaw = { raw ->
            // provider 解析失敗 = 未觸及任何 prefs 實例 → 無 mMap 污染可能，
            // 不設圍欄、僅回 false (暫時性缺席經 load-only 恢復)。
            // Phase 4:含 Error 皆 fail-closed;取消透傳。
            val prefs: SharedPreferences? = resolvePrefs()
            if (prefs == null) {
                false
            } else {
                try {
                    val ok: Boolean = if (raw == null) {
                        prefs.edit().remove(PolicySource.POLICY_KEY_EXTRA_PACKAGES).commit()
                    } else {
                        prefs.edit().putStringSet(PolicySource.POLICY_KEY_EXTRA_PACKAGES, raw.toSet())
                            .commit()
                    }
                    if (ok) clearForSuccess(prefs) else markFailed(prefs)
                    ok
                } catch (ce: CancellationException) {
                    markFailed(prefs)
                    throw ce
                } catch (e: Throwable) {
                    // 已拿到實例後的 edit/commit 拋出 (如 binder RemoteException
                    // 或 Error):樂觀更新可能已污染 mMap，標記後重拋由
                    // HookPolicyStore.save/clear 統一轉 false (取消除外)。
                    markFailed(prefs)
                    throw e
                }
            }
        },
    )
}
