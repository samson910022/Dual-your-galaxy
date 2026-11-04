// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase C1 regression tests (issue #2, P0): dual-profile safety boundary.
// Uses a FakeGateway (no device) to prove writes are refused rather than
// mis-targeted. Three scenarios: no-DUAL fallback refusal, explicit user-0
// refusal, and ambiguous/stale-id refusal.
class DualSafetyTest {
    // In-memory CmdGateway: serves canned `pm list users` output and records
    // every command so tests can prove no write ever targeted the wrong user.
    private class FakeGateway(
        var listUsersOutput: String = "",
        var listUsersExit: Int = 0,
        var pathOutput: String = "ok",
    ) : CmdGateway {
        override val privilege: Privilege = Privilege.SHELL
        val commands = mutableListOf<String>()

        override suspend fun run(cmd: String): CmdResult {
            commands.add(cmd)
            return if (cmd == DualRepository.CMD_LIST_USERS) {
                CmdResult(exit = listUsersExit, out = listUsersOutput, err = "")
            } else if (cmd.startsWith("pm path")) {
                CmdResult(exit = 0, out = pathOutput, err = "")
            } else {
                CmdResult(exit = 0, out = "ok", err = "")
            }
        }

        fun writeCommands(): List<String> = commands.filter { it != DualRepository.CMD_LIST_USERS }
    }

    // Minimal runBlocking substitute (core has no coroutines dependency):
    // works because FakeGateway never actually suspends.
    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(r: Result<T>) {
                    result = r
                }
            },
        )
        return (result ?: error("suspend block never resumed")).getOrThrow()
    }

    // Scenario 1: `pm list users` has NO DUAL-labeled profile (Owner + an
    // unrelated non-zero user such as a Work profile). The old "first
    // non-zero user" fallback would have picked user 10; now resolution must
    // be -1 and clone/remove must refuse without issuing any write command.
    @Test
    fun noDualLabel_neverFallsBackToFirstNonZeroUser() {
        val gateway = FakeGateway(
            listUsersOutput = """
                Users:
                    UserInfo{0:Owner:13} running
                    UserInfo{10:Work profile:30} running
            """.trimIndent(),
        )
        val repo = DualRepository(gateway)

        assertEquals(-1, runSuspend { repo.resolveDualUserId() })

        val cloneRes = runSuspend { repo.clone("com.example.chat") }
        assertEquals(1, cloneRes.exit)
        assertEquals("no dual profile", cloneRes.err)

        val removeRes = runSuspend { repo.remove("com.example.chat") }
        assertEquals(1, removeRes.exit)
        assertEquals("no dual profile", removeRes.err)

        assertTrue(
            "no write command may run without a DUAL hit, got: ${gateway.writeCommands()}",
            gateway.writeCommands().isEmpty(),
        )

        // Same guarantee through both elevator probes sharing the pure function.
        val shizuku = ShizukuElevator(gateway)
        assertEquals(-1, runSuspend { shizuku.probe() }.dualUserId)
        val su = SuElevator(
            object : CmdGateway {
                override val privilege: Privilege = Privilege.ROOT
                override suspend fun run(cmd: String): CmdResult =
                    if (cmd == "id -u") CmdResult(0, "0", "") else gateway.run(cmd)
            },
        )
        assertEquals(-1, runSuspend { su.probe() }.dualUserId)
    }

    // Scenario 2: explicit dualId == 0 (owner) is refused on every entry, and
    // no command line containing the owner id is ever executed.
    @Test
    fun explicitUserZero_isRefusedOnAllEntries() {
        val gateway = FakeGateway(
            listUsersOutput = """
                Users:
                    UserInfo{0:Owner:13} running
                    UserInfo{95:DUAL_APP:400} running
            """.trimIndent(),
        )
        val repo = DualRepository(gateway)

        val cloneRes = runSuspend { repo.clone("com.example.chat", 0) }
        assertEquals(1, cloneRes.exit)
        assertTrue(cloneRes.err.startsWith("refused:"))

        val removeRes = runSuspend { repo.remove("com.example.chat", 0) }
        assertEquals(1, removeRes.exit)
        assertTrue(removeRes.err.startsWith("refused:"))

        assertTrue(
            "no command may target user 0, got: ${gateway.commands}",
            gateway.commands.none { it.contains("--user 0") },
        )

        // Belt and suspenders: the command builder layer rejects 0 as well.
        try {
            ShellEscaper.escapeUserId(0)
            throw AssertionError("escapeUserId(0) must throw")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // Scenario 3: ambiguous DUAL hits resolve to -1, and an explicit id not
    // present in the current snapshot (stale/mistyped) is refused.
    @Test
    fun ambiguousDualLabels_andStaleExplicitId_areRefused() {
        val gateway = FakeGateway(
            listUsersOutput = """
                Users:
                    UserInfo{0:Owner:13} running
                    UserInfo{95:DUAL_APP:400} running
                    UserInfo{96:dual_secondary:400} running
            """.trimIndent(),
        )
        val repo = DualRepository(gateway)

        // Two DUAL-labeled candidates -> ambiguous -> -1, no auto-clone.
        assertEquals(-1, runSuspend { repo.resolveDualUserId() })
        val autoRes = runSuspend { repo.clone("com.example.chat") }
        assertEquals(1, autoRes.exit)
        assertEquals("no dual profile", autoRes.err)

        // Explicit id 150 is not in this snapshot -> refused, never executed.
        val staleRes = runSuspend { repo.clone("com.example.chat", 150) }
        assertEquals(1, staleRes.exit)
        assertTrue(staleRes.err.startsWith("refused:"))

        val staleRemove = runSuspend { repo.remove("com.example.chat", 150) }
        assertEquals(1, staleRemove.exit)
        assertTrue(staleRemove.err.startsWith("refused:"))

        assertTrue(
            "stale id 150 must never be executed, got: ${gateway.writeCommands()}",
            gateway.writeCommands().none { it.contains("--user 150") },
        )

        // Sanity: the unambiguous single-DUAL case still resolves and clones.
        gateway.listUsersOutput = """
            Users:
                UserInfo{0:Owner:13} running
                UserInfo{95:DUAL_APP:400} running
        """.trimIndent()
        gateway.commands.clear()
        assertEquals(95, runSuspend { repo.resolveDualUserId() })
        val okRes = runSuspend { repo.clone("com.example.chat", 95) }
        assertEquals(0, okRes.exit)
        assertEquals(
            listOf("pm install-existing --user 95 com.example.chat"),
            gateway.writeCommands(),
        )
    }

    // Scenario 4 (review nit): verify(pkg, dualId) shares the C1 guard with
    // clone/remove. Explicit 0 and stale ids return false gracefully (no throw,
    // previously verify(pkg, 0) threw IllegalArgumentException from the command
    // builder) without ever probing the bad id; the happy path still runs
    // `pm path --user <dualId>` and parses the package: line.
    @Test
    fun explicitVerify_isGuardedLikeCloneRemove() {
        val gateway = FakeGateway(
            listUsersOutput = """
                Users:
                    UserInfo{0:Owner:13} running
                    UserInfo{95:DUAL_APP:400} running
            """.trimIndent(),
        )
        val repo = DualRepository(gateway)

        // dualId 0: graceful false, never probes user 0.
        assertFalse(runSuspend { repo.verify("com.example.chat", 0) })

        // Stale id 150 (not in snapshot): graceful false, never probed.
        assertFalse(runSuspend { repo.verify("com.example.chat", 150) })

        assertTrue(
            "verify must never probe user 0 or a stale id, got: ${gateway.commands}",
            gateway.commands.none { it.contains("--user 0") || it.contains("--user 150") },
        )

        // Happy path still works: `pm path` with a package: line verifies true.
        gateway.commands.clear()
        gateway.pathOutput = "package:/data/app/com.example.chat/base.apk"
        assertTrue(runSuspend { repo.verify("com.example.chat", 95) })
        assertEquals(
            listOf("pm path --user 95 com.example.chat"),
            gateway.writeCommands(),
        )
    }

    // parseUsers retains the flag/type field via the new parseUsersDetailed().
    @Test
    fun parseUsers_retainsFlagField() {
        val output = """
            Users:
                UserInfo{0:Owner:13} running
                UserInfo{95:DUAL_APP:400} running
        """.trimIndent()
        val detailed = PmOutputParser.parseUsersDetailed(output)
        assertEquals("13", detailed[0].flags)
        assertEquals("400", detailed[1].flags)
        // parseUsers delegates, so existing callers see the same data.
        assertEquals(detailed, PmOutputParser.parseUsers(output))
    }
}
