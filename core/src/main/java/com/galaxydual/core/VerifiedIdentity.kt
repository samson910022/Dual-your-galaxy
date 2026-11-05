// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// Shared verified-identity fetch (Phase 2, issue #21; Phase C P2-2 property
// required): the single source of truth for the dual-profile id used by
// DualRepository.fetchVerifiedDualId() and both Elevator probes. Runs both
// verbose channels plus the dual property, then maps Confirmed -> id,
// Unknown/Refused/channel failure -> -1 (fail closed). A failed typed channel
// (non-zero exit or error-semantic output per isIdentityChannelOk) yields -1
// without guessing; a missing/unparseable property is "no claim" (null) and
// now yields Unknown (property is required corroboration, not optional;
// mismatch stays Refused). A version that cannot read the property refuses
// explicitly as an unsupported version (#21 acceptance). Never throws for
// gateway errors (they collapse to -1 / null claim); coroutine cancellation
// is always rethrown and must never be swallowed by callers.
// Phase D: fetchVerifiedTarget() is the single source; fetchVerifiedDualId()
// delegates to it (target?.userId ?: -1, behavior unchanged).
internal suspend fun CmdGateway.fetchVerifiedDualId(): Int =
    fetchVerifiedTarget()?.userId ?: -1

// Verified identity target carrying the cross-source agreed id plus the
// Phase B identity continuity signals (serial/parentId). The id alone is
// the write target; serial/parentId pin the *instance* behind a reused id
// (Phase D, issue #21; Phase A P1-A continuity, issue #21): an id that
// reappears with a different serial is a different profile incarnation and
// must be refused, never written; a known serial followed by a missing
// serial across fetches (expected.serial != null, fresh/after.serial ==
// null) is continuity lost and likewise refuses (fail closed, before any
// write/probe). Both are nullable because the single-fetch resolver policy
// tolerates absence (Phase B: one channel missing serial still Confirms
// with the deduplicated value or null, unchanged); the strict
// known-to-unknown rule applies only cross-fetch (expected-vs-fresh,
// before-vs-after), never intra-fetch. Parent needs no known-to-unknown
// cross-fetch rule: the resolver already requires both channels to carry a
// known, equal parentId == 0, so any Confirmed (non-null) target carries
// parentId non-null and == 0 by construction.
data class VerifiedTarget(val userId: Int, val serial: Int?, val parentId: Int?)

internal suspend fun CmdGateway.fetchVerifiedTarget(): VerifiedTarget? {
    val verboseRes = try {
        run(DualRepository.CMD_USER_LIST_VERBOSE)
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        return null
    }
    val dumpsysRes = try {
        run(DualRepository.CMD_DUMPSYS_USER)
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        return null
    }
    if (verboseRes.exit != 0 || dumpsysRes.exit != 0) return null
    if (!PmOutputParser.isIdentityChannelOk(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) return null
    if (!PmOutputParser.isIdentityChannelOk(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) return null
    val propRes: CmdResult? = try {
        run(DualRepository.CMD_GETPROP_DUAL)
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        null
    }
    val propertyId = propRes
        ?.takeIf { PmOutputParser.isIdentityChannelOk(it.out + "\n" + it.err, it.exit) }
        ?.let { PmOutputParser.parseDualPropertyId(it.out) }
    return PmOutputParser.resolveDualTarget(verboseRes.out, dumpsysRes.out, propertyId)
}
