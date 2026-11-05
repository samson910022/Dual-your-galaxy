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
internal suspend fun CmdGateway.fetchVerifiedDualId(): Int {
    val verboseRes = try {
        run(DualRepository.CMD_USER_LIST_VERBOSE)
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        return -1
    }
    val dumpsysRes = try {
        run(DualRepository.CMD_DUMPSYS_USER)
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        return -1
    }
    if (verboseRes.exit != 0 || dumpsysRes.exit != 0) return -1
    if (!PmOutputParser.isIdentityChannelOk(verboseRes.out + "\n" + verboseRes.err, verboseRes.exit)) return -1
    if (!PmOutputParser.isIdentityChannelOk(dumpsysRes.out + "\n" + dumpsysRes.err, dumpsysRes.exit)) return -1
    val propRes: CmdResult? = try {
        run(DualRepository.CMD_GETPROP_DUAL)
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        null
    }
    val propertyId = propRes
        ?.takeIf { PmOutputParser.isIdentityChannelOk(it.out + "\n" + it.err, it.exit) }
        ?.let { PmOutputParser.parseDualPropertyId(it.out) }
    return PmOutputParser.resolveDualUserIdVerified(verboseRes.out, dumpsysRes.out, propertyId)
}
