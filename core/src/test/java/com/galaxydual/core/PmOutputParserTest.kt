// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Simple unit tests for PmOutputParser: pure Kotlin, no device required.
class PmOutputParserTest {
    @Test
    fun parseUsers_parsesOwnerAndDual() {
        val output = """
            Users:
                UserInfo{0:Owner:13} running
                UserInfo{95:DUAL_APP:400} running
        """.trimIndent()
        val profiles = PmOutputParser.parseUsers(output)
        assertEquals(2, profiles.size)
        assertEquals(DualProfile(userId = 0, label = "Owner"), profiles[0])
        assertEquals(DualProfile(userId = 95, label = "DUAL_APP"), profiles[1])
    }

    @Test
    fun parseUsers_ignoresGarbageAndBlankLines() {
        val output = """
            Users:
              some unrelated line

                UserInfo{150:DUAL_APP:400}
              package:com.example.chat
        """.trimIndent()
        val profiles = PmOutputParser.parseUsers(output)
        assertEquals(1, profiles.size)
        assertEquals(150, profiles[0].userId)
    }

    @Test
    fun parseUsers_emptyOutputReturnsEmpty() {
        assertTrue(PmOutputParser.parseUsers("").isEmpty())
        assertTrue(PmOutputParser.parseUsers("Users:\n").isEmpty())
    }

    @Test
    fun buildInstallExisting_formatsPmCommand() {
        assertEquals(
            "pm install-existing --user 95 com.example.chat",
            PmOutputParser.buildInstallExisting("com.example.chat", 95),
        )
    }
}
