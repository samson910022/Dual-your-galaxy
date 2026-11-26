// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// PR #90 Phase A (P0): durable install classification — OWNER APP ONLY.
//
// The Hook's remote preferences are READ-ONLY in hooked apps (libxposed API
// 102), so the migration marker can only be persisted through the App's
// writable XposedService.getRemotePreferences channel. Current packages/version
// bytes are written by ordinary fresh-install saves and can NEVER be used as
// proof of consent; a durable classification must be established at/before the
// first new-generation draft write.
//
// Entry points (ONE decision, shared with the Hook's saveRaw path):
//  1. [planBbcInstallClassificationForWrite] (start of HookPolicyStore.saveRaw,
//     inside versionCommitLock): decides BEFORE any draft write; the caller
//     adds the same key/value (+ marker) to the SAME editor/commit as
//     packages/version. Single transaction = no window where a draft exists
//     without a classification. A FAILED commit may pollute the daemon's
//     optimistic local mMap (classification included) while persisting nothing;
//     the app-scoped [pendingBbcClassification] then forces the retry to re-add
//     the SAME key/value (+ marker) even when classificationValue() reads that
//     polluted local value as "known". Cleared only on a CONFIRMED commit.
//  2. [confirmBbcInstallClassificationDurable] (GalaxyApp.onServiceBind): the
//     ONLY bind-time trust path. It forces a real commit even when the key
//     appears present, because a NoWrite inferred from the local mMap is not
//     durable proof. Only a successful commit authorizes confirmation. Its
//     [BbcClassificationConfirmation] tri-state separates a FAILED COMMIT (the
//     only case with a possibly polluted local map) from a NO-COMMIT fail-closed
//     plan (unknown/unreadable value, poison latch, Unavailable version, read
//     error), which must leave the holder neutral instead of fenced.
//
// [ensureBbcInstallClassification] is retained as a TEST-ONLY idempotent-writer
// helper: it commits the classification (+ marker for a genuine upgrade) in
// its own edit when the key is absent (no packages/version key is touched, so
// an old draft stays byte-identical) and returns true on NoWrite. It MUST NOT
// be used for bind-time confirmation — NoWrite is not durable proof, since a
// failed commit can pollute the optimistic local mMap while the daemon persists
// nothing. Production bind-time confirmation always goes through
// [confirmBbcInstallClassificationDurable].
//
// Framework-free core: [planBbcInstallClassification] only sees the small
// [BbcClassificationReads] seam; JVM tests can fake every failure mode.
package com.galaxydual.plus

import android.content.SharedPreferences
import com.galaxydual.hook.PolicySource
import com.galaxydual.hook.VersionPrefsView
import com.galaxydual.hook.VersionProbeResult
import com.galaxydual.hook.probeVersion
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

// The value vocabulary lives in PolicySource (hook) so both sides share one
// source of truth. Aliases keep app-side code readable.
internal val BbcInstallClassificationValues: Set<String> = PolicySource.INSTALL_CLASSIFICATION_VALUES
internal const val PRE_UPGRADE_EXISTING: String = PolicySource.INSTALL_CLASSIFICATION_PRE_UPGRADE_EXISTING
internal const val NEW_GENERATION_NOT_CONSENTED: String = PolicySource.INSTALL_CLASSIFICATION_NEW_GENERATION_NOT_CONSENTED

// Exactly the reads the decision needs. Throws signal a read failure; a null
// classification value means the key is ABSENT. versionProbe() uses the shared
// probeVersion vocabulary; its adapter MUST rethrow CancellationException
// (probeVersion itself folds CE into Unavailable).
internal interface BbcClassificationReads {
    fun classificationValue(): String?
    fun packagesNonEmpty(): Boolean
    fun versionProbe(): VersionProbeResult
}

// Outcome of the decision. The caller owns the editor/commit (so it can share
// one transaction with the draft write). NoWrite means "key already present
// with a trusted value — leave it unchanged" (idempotent). FailClosed means
// "write nothing"; the caller must fail closed rather than invent evidence.
internal sealed interface BbcClassificationPlan {
    data object NoWrite : BbcClassificationPlan
    data class Write(val value: String, val marker: Boolean) : BbcClassificationPlan
    data object FailClosed : BbcClassificationPlan
}

// Bind-time confirmation is TRI-STATE: `false` alone cannot distinguish "a
// commit entered and may have polluted this prefs instance's optimistic local
// mMap" from "no editor was ever created and nothing was written". Fencing the
// latter installs a same-prefs Unknown fence that no later bind of the cached
// instance can clear (a permanent in-process lockout). CE still propagates as
// an interruption, never as one of these outcomes.
internal sealed interface BbcClassificationConfirmation {
    /** A real successful `commit()` (including a forced NoWrite re-put). */
    data object Confirmed : BbcClassificationConfirmation

    /** An `edit()`/`commit()` was attempted and returned false or threw. */
    data object CommitFailedPollution : BbcClassificationConfirmation

    /** Fail-closed plan / unreadable value / poison latch / resolver error: no editor, no write. */
    data object NoCommitFailClosed : BbcClassificationConfirmation
}

// PR #90 P0 retry safety: the optimistic-local-mMap trap.
//
// A false/throwing commit can flush classification (+ marker) into THIS
// process's local SharedPreferences mMap while the daemon persists nothing.
// On the retry, classificationValue() then reads that local value as "known"
// and a naive planner returns NoWrite — the retry commits packages/version
// WITHOUT classification/marker, leaving an unclassified durable draft and a
// permanently CLOSED Hook. Remember the intent in process memory instead.
//
// Keyed by prefs IDENTITY (weak ref + ===): a rebound service gets a different
// prefs instance and must NOT inherit a stale intent. All access is confined to
// callers holding versionCommitLock. Cleared ONLY after a confirmed successful
// commit, on service death, or implicitly on process restart (new reference).
internal data class PendingBbcClassification(
    private val prefsRef: WeakReference<SharedPreferences>,
    val value: String,
    val marker: Boolean,
) {
    fun matches(prefs: SharedPreferences): Boolean = prefsRef.get() === prefs

    companion object {
        fun of(prefs: SharedPreferences, plan: BbcClassificationPlan.Write) =
            PendingBbcClassification(WeakReference(prefs), plan.value, plan.marker)
    }
}

internal val pendingBbcClassification = AtomicReference<PendingBbcClassification?>(null)

/** Drops the intent (service death / process restart / test isolation). */
internal fun clearPendingBbcClassification() {
    pendingBbcClassification.set(null)
}

// Only the PERMANENT, process-level overflow latch aborts every write. A normal
// failed commit latches a per-instance poison fence (to hide the optimistic
// mMap) but writes MUST stay possible so the pending intent can be re-committed.
private fun poisonOverflowLatched(): Boolean = try {
    remotePoison.get().overflow
} catch (_: Throwable) {
    true // cannot verify the permanent latch => fail closed
}

// The plan used by BOTH the bind-time writer and every draft write. A pending
// intent for this exact prefs instance overrides the local-mMap-based read:
// re-add the SAME key/value (+ marker) so a retry cannot silently drop it.
internal fun planBbcInstallClassificationForWrite(prefs: SharedPreferences): BbcClassificationPlan {
    if (poisonOverflowLatched()) return BbcClassificationPlan.FailClosed
    pendingBbcClassification.get()?.let { pending ->
        if (pending.matches(prefs)) return BbcClassificationPlan.Write(pending.value, pending.marker)
        clearPendingBbcClassification() // stale intent from a dead/rebound service
    }
    return planBbcInstallClassificationForPrefs(prefs).also { plan ->
        if (plan is BbcClassificationPlan.Write) {
            pendingBbcClassification.set(PendingBbcClassification.of(prefs, plan))
        }
    }
}

// Writes ONLY the classification key (+ marker for a genuine upgrade) into an
// already-created editor — never the policy draft keys.
internal fun applyBbcClassificationPlan(
    editor: SharedPreferences.Editor,
    plan: BbcClassificationPlan.Write,
) {
    editor.putString(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION, plan.value)
    if (plan.marker) {
        editor.putBoolean(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED, true)
    }
}

// Pure classifier. Rules:
//  - classification key present with a KNOWN value => NoWrite (idempotent;
//    an unknown value is FailClosed but is never overwritten).
//  - absent + pre-existing draft (packages non-empty OR version Present) =>
//    Write(PRE_UPGRADE_EXISTING, marker=true).
//  - absent + no pre-existing draft => Write(NEW_GENERATION_NOT_CONSENTED, false).
//  - any read failure / Unavailable version / unknown present value =>
//    FailClosed (write nothing). An Invalid (CCE/short) version key is not a
//    usable draft proof and is NOT a transport failure: it degrades to no
//    version evidence so the existing corrupt-version repair can proceed.
//  - CancellationException always propagates (never a non-committal outcome).
internal fun planBbcInstallClassification(reads: BbcClassificationReads): BbcClassificationPlan = try {
    val existing = reads.classificationValue()
    if (existing != null) {
        if (existing in BbcInstallClassificationValues) BbcClassificationPlan.NoWrite
        else BbcClassificationPlan.FailClosed
    } else {
        // A corrupt package-set value (ClassCastException) is not usable draft
        // evidence, not a transport failure: degrade to "no packages evidence"
        // so the corrupt-version repair can still proceed. Matches the real
        // SharedPreferences adapter; defended here too for the framework-free seam.
        val packagesNonEmpty = try {
            reads.packagesNonEmpty()
        } catch (_: ClassCastException) {
            false
        }
        val version = reads.versionProbe()
        if (version is VersionProbeResult.Unavailable) {
            BbcClassificationPlan.FailClosed
        } else {
            val preUpgrade = packagesNonEmpty || version is VersionProbeResult.Present
            if (preUpgrade) {
                BbcClassificationPlan.Write(PRE_UPGRADE_EXISTING, marker = true)
            } else {
                BbcClassificationPlan.Write(NEW_GENERATION_NOT_CONSENTED, marker = false)
            }
        }
    }
} catch (ce: CancellationException) {
    throw ce
} catch (_: Throwable) {
    BbcClassificationPlan.FailClosed
}

// Shared adapter from real SharedPreferences to the framework-free reads.
// CancellationException from the version probe is captured and rethrown;
// a ClassCastException on the package set means "no usable draft evidence"
// (repair must still be possible), every other throwable is a read failure.
private fun classificationReads(prefs: SharedPreferences): BbcClassificationReads {
    val versionCancellation = AtomicReference<CancellationException?>(null)
    return object : BbcClassificationReads {
        override fun classificationValue(): String? {
            if (!prefs.contains(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION)) return null
            // Present but unreadable / wrong type => throw => fail closed.
            return prefs.getString(PolicySource.POLICY_KEY_INSTALL_CLASSIFICATION, null)
                ?: throw IllegalStateException("classification key present but unreadable")
        }

        override fun packagesNonEmpty(): Boolean = try {
            // getAll() is a single copy (no extra getStringSet trip through the
            // poison-fenced draft reader) — classification must NEVER be
            // inferred from a torn/fenced draft read.
            val value = prefs.all[PolicySource.POLICY_KEY_EXTRA_PACKAGES]
            value is Set<*> && value.isNotEmpty()
        } catch (cce: ClassCastException) {
            false // corrupt draft value: not usable as migration evidence
        }

        override fun versionProbe(): VersionProbeResult {
            val probe = probeVersion(object : VersionPrefsView {
                override fun containsVersion(): Boolean = try {
                    prefs.contains(PolicySource.POLICY_KEY_EXTRA_VERSION)
                } catch (ce: CancellationException) {
                    versionCancellation.set(ce)
                    throw ce
                }

                override fun readVersion(defValue: Long): Long = try {
                    prefs.getLong(PolicySource.POLICY_KEY_EXTRA_VERSION, defValue)
                } catch (ce: CancellationException) {
                    versionCancellation.set(ce)
                    throw ce
                }
            })
            versionCancellation.get()?.let { throw it }
            return probe
        }
    }
}

/** Plan for the classification write from the raw prefs reads. */
internal fun planBbcInstallClassificationForPrefs(prefs: SharedPreferences): BbcClassificationPlan {
    // Permanent process-level latch: no classification decision is trustworthy.
    if (poisonOverflowLatched()) return BbcClassificationPlan.FailClosed
    return planBbcInstallClassification(classificationReads(prefs))
}

// TEST-ONLY idempotent-writer helper (NOT the bind-time trust path). Kept for
// JVM tests that need to seed a classification via its own edit/commit; it MUST
// NOT be used for bind-time confirmation, because NoWrite is not durable proof.
// Production bind-time confirmation always goes through
// [confirmBbcInstallClassificationDurable]. Uses the same process-wide commit
// lock as the policy writer and the other BBC single-key writers. CE from
// provider or any read propagates; every other Throwable maps to binder-absent
// / fail-closed. A NoWrite (key already present with a trusted value) is a
// legitimate answer for this WRITER, but it is NOT durable confirmation.
internal fun ensureBbcInstallClassification(provider: () -> SharedPreferences?): Boolean {
    val prefs: SharedPreferences = try {
        provider()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    } ?: return false

    return synchronized(versionCommitLock) {
        when (val plan = planBbcInstallClassificationForWrite(prefs)) {
            BbcClassificationPlan.NoWrite -> true
            BbcClassificationPlan.FailClosed -> false
            is BbcClassificationPlan.Write -> try {
                val editor = prefs.edit()
                applyBbcClassificationPlan(editor, plan)
                val committed = editor.commit()
                // Keep the pending intent until the daemon CONFIRMS persistence.
                if (committed) clearPendingBbcClassification()
                committed
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * PR #90 P1-2: bind-time DURABLE CONFIRMATION. This is the ONLY evidence that
 * may authorize `markInstallClassificationConfirmed()`: a successful
 * `commit()`. A `NoWrite` inferred from `contains()`/`getString()` can be a
 * LIE — a failed commit flushes its keys into this process's optimistic local
 * mMap while the daemon persists nothing. Treating that as confirmation opens
 * the App's gate while the Hook (which reads the durable map) stays OFF.
 *
 * Therefore, unlike [ensureBbcInstallClassification], this ALWAYS performs a
 * real commit when a trusted classification value is known:
 *  - [BbcClassificationPlan.Write]: commit the planned value + marker exactly
 *    as the draft-write path does (a pending intent for this instance wins).
 *  - [BbcClassificationPlan.NoWrite]: re-put the ALREADY-KNOWN value and
 *    marker in a fresh editor and commit. A successful commit restores parity
 *    (durable map now matches local) and returns
 *    [BbcClassificationConfirmation.Confirmed]; a failed one returns
 *    [BbcClassificationConfirmation.CommitFailedPollution] so the caller fences
 *    the prefs instance that may be polluted.
 *  - [BbcClassificationPlan.FailClosed] / unknown / unreadable value / poison
 *    latch / Unavailable version / resolver error: no editor is created and
 *    nothing is written, so [BbcClassificationConfirmation.NoCommitFailClosed]
 *    is returned and the caller must leave the holder NEUTRAL. An unknown value
 *    is NEVER overwritten.
 *
 * The pending intent is set for the forced re-put too, so a failed bind-time
 * commit still forces later draft writes to re-add the same keys.
 */
internal fun confirmBbcInstallClassificationDurable(
    provider: () -> SharedPreferences?,
): BbcClassificationConfirmation {
    val prefs: SharedPreferences = try {
        provider()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    } ?: return BbcClassificationConfirmation.NoCommitFailClosed

    return synchronized(versionCommitLock) {
        // Resolving the write creates NO editor. A fail-closed plan (poison
        // latch, unknown/unreadable value, Unavailable version, read throw) is
        // therefore provably not pollution and must never be fenced.
        val plan = try {
            resolveDurableClassificationWrite(prefs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
        if (plan == null) {
            BbcClassificationConfirmation.NoCommitFailClosed
        } else {
            commitClassificationDurable(prefs, plan)
        }
    }
}

/**
 * Resolve the value/marker to force-commit WITHOUT creating an editor.
 * `null` means fail closed: no write of any kind is justified.
 */
private fun resolveDurableClassificationWrite(
    prefs: SharedPreferences,
): BbcClassificationPlan.Write? = when (val plan = planBbcInstallClassificationForWrite(prefs)) {
    BbcClassificationPlan.FailClosed -> null
    is BbcClassificationPlan.Write -> plan
    BbcClassificationPlan.NoWrite -> {
        // Trust only a KNOWN value; a present-but-unreadable / unknown value is
        // fail-closed and is never overwritten.
        val reads = classificationReads(prefs)
        val value = reads.classificationValue()
        if (value == null || value !in BbcInstallClassificationValues) {
            null
        } else {
            val marker = try {
                if (prefs.contains(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED)) {
                    prefs.getBoolean(PolicySource.POLICY_KEY_BBC_LEGACY_MIGRATED, false)
                } else false
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Present but unreadable marker: never invent a durable proof.
                null
            }
            if (marker == null) null else BbcClassificationPlan.Write(value, marker)
        }
    }
}

/** The single forced commit shared by both plan outcomes above. */
private fun commitClassificationDurable(
    prefs: SharedPreferences,
    plan: BbcClassificationPlan.Write,
): BbcClassificationConfirmation {
    // Keep the pending intent until the daemon CONFIRMS persistence: a failed
    // commit must still be re-added by every later draft write.
    pendingBbcClassification.set(PendingBbcClassification.of(prefs, plan))
    return try {
        val editor = prefs.edit()
        applyBbcClassificationPlan(editor, plan)
        val committed = editor.commit()
        if (committed) {
            clearPendingBbcClassification()
            BbcClassificationConfirmation.Confirmed
        } else {
            BbcClassificationConfirmation.CommitFailedPollution
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        // An edit()/commit() that entered may have polluted the optimistic
        // local mMap even though the daemon persisted nothing: conservative.
        BbcClassificationConfirmation.CommitFailedPollution
    }
}
