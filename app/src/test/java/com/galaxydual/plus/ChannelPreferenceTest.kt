// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase F4b-P1 (issue #6): JVM unit tests for the device-free channel
// selection mapping -- stored-string round-trip, Auto resolution order,
// explicit-choice override, the LSPosed placeholder, and gateway privilege
// declaration. Binder round-trips, su presence, and pm semantics need a real
// device (see ChannelPreference.kt / gateway file headers).
package com.galaxydual.plus

import com.galaxydual.core.Privilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPreferenceTest {
    // Every preference round-trips through its stored form.
    @Test fun stored_roundTripsAllValues() {
        for (pref in ChannelPreference.values()) {
            assertEquals(pref, channelPreferenceFromStored(pref.toStored()))
        }
    }

    // Unknown input falls back to AUTO: null (fresh install), blank,
    // future values, and "noop" (NoOp is a fallback, never a stored choice).
    @Test fun stored_unknownFallsBackToAuto() {
        assertEquals(ChannelPreference.AUTO, channelPreferenceFromStored(null))
        assertEquals(ChannelPreference.AUTO, channelPreferenceFromStored(""))
        assertEquals(ChannelPreference.AUTO, channelPreferenceFromStored("   "))
        assertEquals(ChannelPreference.AUTO, channelPreferenceFromStored("noop"))
        assertEquals(ChannelPreference.AUTO, channelPreferenceFromStored("hook-v99"))
    }

    // Stored parsing is case/whitespace tolerant.
    @Test fun stored_parsingIsCaseInsensitive() {
        assertEquals(ChannelPreference.ROOT, channelPreferenceFromStored("  ROOT "))
        assertEquals(ChannelPreference.SHIZUKU, channelPreferenceFromStored("ShiZuKu"))
        assertEquals(ChannelPreference.LSPOSED, channelPreferenceFromStored("LSPOSED"))
        assertEquals(ChannelPreference.AUTO, channelPreferenceFromStored("Auto"))
    }

    // The menu model is exactly four values: NoOp never enters it.
    @Test fun model_hasFourValuesWithoutNoOp() {
        assertEquals(4, ChannelPreference.values().size)
        for (pref in ChannelPreference.values()) {
            assertTrue(pref.toStored() != "noop")
        }
    }

    // AUTO keeps the F4a order: su first, then Shizuku, else NoOp fallback.
    @Test fun auto_followsSuThenShizukuThenNoOp() {
        assertEquals(
            ResolvedChannel.ROOT,
            ChannelPreference.AUTO.resolveChannel(suPresent = true, shizukuAuthorized = true),
        )
        assertEquals(
            ResolvedChannel.ROOT,
            ChannelPreference.AUTO.resolveChannel(suPresent = true, shizukuAuthorized = false),
        )
        assertEquals(
            ResolvedChannel.SHIZUKU,
            ChannelPreference.AUTO.resolveChannel(suPresent = false, shizukuAuthorized = true),
        )
        assertEquals(
            ResolvedChannel.NOOP,
            ChannelPreference.AUTO.resolveChannel(suPresent = false, shizukuAuthorized = false),
        )
    }

    // Explicit Root/Shizuku win regardless of environment probes (probes
    // fail closed later when the channel is actually unusable).
    @Test fun explicit_overridesEnvironment() {
        assertEquals(
            ResolvedChannel.ROOT,
            ChannelPreference.ROOT.resolveChannel(suPresent = false, shizukuAuthorized = false),
        )
        assertEquals(
            ResolvedChannel.SHIZUKU,
            ChannelPreference.SHIZUKU.resolveChannel(suPresent = true, shizukuAuthorized = false),
        )
    }

    // Explicit LSPosed is the P2 placeholder regardless of environment.
    @Test fun lsposed_resolvesToPlaceholder() {
        assertEquals(
            ResolvedChannel.LSPOSED_PLACEHOLDER,
            ChannelPreference.LSPOSED.resolveChannel(suPresent = true, shizukuAuthorized = true),
        )
        assertEquals(
            ResolvedChannel.LSPOSED_PLACEHOLDER,
            ChannelPreference.LSPOSED.resolveChannel(suPresent = false, shizukuAuthorized = false),
        )
    }

    // Built gateways declare the matching privilege; both NONE-privilege
    // resolutions stay read-only.
    @Test fun gateways_declareMatchingPrivilege() {
        assertEquals(Privilege.ROOT, buildGatewayForChannel(ResolvedChannel.ROOT, "com.example.test").privilege)
        assertEquals(
            Privilege.SHELL,
            buildGatewayForChannel(ResolvedChannel.SHIZUKU, "com.example.test").privilege,
        )
        assertEquals(Privilege.NONE, buildGatewayForChannel(ResolvedChannel.NOOP, "com.example.test").privilege)
        assertEquals(
            Privilege.NONE,
            buildGatewayForChannel(ResolvedChannel.LSPOSED_PLACEHOLDER, "com.example.test").privilege,
        )
    }

    // The assembly shares instances: the selected gateway IS the matching
    // elevator gateway, so probes verify the actual channel the repo uses.
    @Test fun assembly_sharesSelectedGatewayInstance() {
        val rootAsm = assembleChannel(ChannelPreference.AUTO, suPresent = true, shizukuAuthorized = true, packageName = "p")
        assertEquals(ResolvedChannel.ROOT, rootAsm.channel)
        assertSame(rootAsm.suGateway, rootAsm.gateway)

        val shellAsm = assembleChannel(
            ChannelPreference.AUTO,
            suPresent = false,
            shizukuAuthorized = true,
            packageName = "p",
        )
        assertEquals(ResolvedChannel.SHIZUKU, shellAsm.channel)
        assertSame(shellAsm.shizukuGateway, shellAsm.gateway)
    }

    // Startup and re-selection use one path: the factory-equivalent AUTO
    // assembly resolves identically to the ViewModel-equivalent call.
    @Test fun assembly_autoResolutionIsStable() {
        val first = assembleChannel(ChannelPreference.AUTO, suPresent = false, shizukuAuthorized = false, packageName = "p")
        val second = assembleChannel(ChannelPreference.AUTO, suPresent = false, shizukuAuthorized = false, packageName = "p")
        assertEquals(first.channel, second.channel)
        assertEquals(ResolvedChannel.NOOP, first.channel)
    }
}
