// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Round 2 Phase A P1-1 回歸測試：FRESH 95 → rebuild 96 → successful
// clone/remove，斷言新 target 全量重查且舊包不混入。
//
// Harness 沿用 DiagnoseFreshnessPhase5Test：DualViewModel 跑在 viewModelScope
// (Dispatchers.Main 換成 Unconfined)，repo I/O 在 Dispatchers.IO，poll
// StateFlow + timeout 等待；exit-0 語意避 retry settle sleep。
package com.galaxydual.plus

import androidx.lifecycle.viewModelScope
import com.galaxydual.core.CmdGateway
import com.galaxydual.core.CmdResult
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.Privilege
import com.galaxydual.core.ShizukuElevator
import com.galaxydual.core.SuElevator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TargetRebuildRefreshTest {
    // 分目標 fake：identity 通道跟 dualId；各 user 獨立已安裝集合，
    // install/uninstall 只動當前 dualId 的集合；list 按 --user 參數回對應集合。
    private class RebuildFake(
        var dualId: Int = 95,
        val pkgsByUser: MutableMap<Int, MutableSet<String>> = mutableMapOf(
            95 to mutableSetOf("com.example.chat", "com.example.old"),
            96 to mutableSetOf("com.example.chat"),
        ),
        var rootOk: Boolean = true,
        // 本地可變 serial（預設 15，舊測試行為不變）：#68 需要「同 id 換 serial」
        // 的 delete+recreate 世界，不能改別的檔案的 RebuildFake（hardcoded 15）。
        var serial: Int = 15,
        // PR #88 F2: model evidence loss in the VM's post-Success identity
        // query. The 3rd identity triple AFTER the write is that extra fetch
        // (after-pin -> post-list fence -> VM extra query); subsequent triples
        // are the read-only refresh, which must also fail so the final
        // inventory stays non-FRESH. NONE keeps all legacy behavior.
        var postWriteIdentityMode: IdentityMode = IdentityMode.NONE,
        // PR #88 Phase 1 regression: a same-id rebuild that core CANNOT see
        // (its after-pin and post-list fences still report the OLD serial, so
        // Success.verifiedSerial is the old one) but the VM's extra
        // post-Success identity query CAN. Flip is deferred to the 3rd
        // identity triple after the write: after-pin -> post-list fence ->
        // VM extra query (verified against core's cloneVerified/
        // removeVerifiedExpected). This test COUPLES to that fetch count.
        var freshSerialAtExtraFetch: Int? = null,
        // Set by the test AFTER the initial refresh: identity triples report
        // no serial until that same extra fetch, modelling a core Success
        // whose verifiedSerial==null while the VM inventory pins a known one.
        var nullSerialBeforeExtraFetch: Boolean = false,
        // Full content swap at the rebuild instant so a stale splice (old
        // package list with a new serial, or vice versa) cannot look correct.
        var freshPkgsAtExtraFetch: Set<String>? = null,
    ) : CmdGateway {
        enum class IdentityMode { NONE, NULL_TARGET, NULL_SERIAL }

        override val privilege: Privilege = Privilege.ROOT

        var writeCount: Int = 0
            private set
        private var identityFetches = 0
        private var identityFetchesAtWrite = -1

        // Active only from the VM's extra post-Success query onward, so the
        // core's own after-pin and post-list fence still see a healthy target
        // and can honestly return Success.
        private fun activeMode(): IdentityMode {
            if (postWriteIdentityMode == IdentityMode.NONE || writeCount == 0) return IdentityMode.NONE
            val afterWriteFetches = identityFetches - identityFetchesAtWrite
            if (afterWriteFetches < 3) return IdentityMode.NONE
            // NULL_SERIAL is a one-shot at the extra query; the refresh that
            // follows loses the whole target (a known serial cannot be
            // recovered by a FRESH(id,null) listing in this scenario).
            return if (postWriteIdentityMode == IdentityMode.NULL_SERIAL && afterWriteFetches == 3) {
                IdentityMode.NULL_SERIAL
            } else {
                IdentityMode.NULL_TARGET
            }
        }

        // Applied by BOTH identity command handlers before rendering output.
        // dumpsys shares the triple with the verbose fetch (only verbose
        // increments identityFetches), so both channels of that triple see
        // the same new serial.
        private fun effectiveSerial(): Int? {
            val extraDue = writeCount > 0 && identityFetchesAtWrite >= 0 &&
                identityFetches - identityFetchesAtWrite >= 3
            if (extraDue && freshSerialAtExtraFetch != null) {
                serial = freshSerialAtExtraFetch!!
                freshPkgsAtExtraFetch?.let { replacement ->
                    pkgsByUser.getOrPut(dualId) { mutableSetOf() }.apply {
                        clear()
                        addAll(replacement)
                    }
                }
            }
            return if (nullSerialBeforeExtraFetch && !extraDue) null else serial
        }

        private fun usersOut(): String =
            "Users:\n\tUserInfo{0:Owner:13} running\n" +
                "\tUserInfo{$dualId:DualApp:400} running\n"

        private fun verboseOut(mode: IdentityMode, visibleSerial: Int?): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} running:\n" +
                "    Serial number: 0\n" +
                "    User type: android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400} running:\n" +
                (if (mode == IdentityMode.NULL_SERIAL || visibleSerial == null) "" else "    Serial: $visibleSerial\n") +
                "    parentId=0\n" +
                "    User type: android.os.usertype.profile.CLONE\n"

        private fun dumpsysOut(mode: IdentityMode, visibleSerial: Int?): String =
            "Users:\n" +
                "  UserInfo{0:Owner:13} serialNo=0 isPrimary=true:\n" +
                "    userType=android.os.usertype.full.SYSTEM\n" +
                "  UserInfo{$dualId:DualApp:400}" +
                (if (mode == IdentityMode.NULL_SERIAL || visibleSerial == null) "" else " serialNo=$visibleSerial") +
                ":\n" +
                "    parentId=0\n" +
                "    userType=android.os.usertype.profile.CLONE\n"

        private fun userOfListCmd(cmd: String): Int {
            val m = Regex("--user\\s+(\\d+)").find(cmd)
            return m?.groupValues?.get(1)?.toIntOrNull() ?: dualId
        }

        private fun recordWrite() {
            writeCount++
            if (identityFetchesAtWrite < 0) identityFetchesAtWrite = identityFetches
        }

        override suspend fun run(cmd: String): CmdResult = when {
            cmd.trim() == "id -u" ->
                if (rootOk) CmdResult(exit = 0, out = "0\n", err = "")
                else CmdResult(exit = 0, out = "1000\n", err = "")
            cmd == DualRepository.CMD_LIST_USERS ->
                CmdResult(exit = 0, out = usersOut(), err = "")
            cmd == DualRepository.CMD_USER_LIST_VERBOSE -> {
                identityFetches++
                val mode = activeMode()
                val visibleSerial = effectiveSerial()
                if (mode == IdentityMode.NULL_TARGET) {
                    CmdResult(exit = 1, out = "", err = "identity channel denied")
                } else {
                    CmdResult(exit = 0, out = verboseOut(mode, visibleSerial), err = "")
                }
            }
            cmd == DualRepository.CMD_DUMPSYS_USER -> {
                val mode = activeMode()
                val visibleSerial = effectiveSerial()
                if (mode == IdentityMode.NULL_TARGET) {
                    CmdResult(exit = 1, out = "", err = "identity channel denied")
                } else {
                    CmdResult(exit = 0, out = dumpsysOut(mode, visibleSerial), err = "")
                }
            }
            cmd == DualRepository.CMD_GETPROP_DUAL ->
                CmdResult(exit = 0, out = "$dualId\n", err = "")
            cmd.startsWith("pm install-existing") -> {
                recordWrite()
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                pkgsByUser.getOrPut(dualId) { mutableSetOf() }.add(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm uninstall") -> {
                recordWrite()
                val pkg = cmd.trim().split("\\s+".toRegex()).lastOrNull() ?: ""
                pkgsByUser[dualId]?.remove(pkg)
                CmdResult(exit = 0, out = "Success", err = "")
            }
            cmd.startsWith("pm list packages") -> {
                val uid = userOfListCmd(cmd)
                val pkgs = pkgsByUser[uid] ?: emptySet()
                CmdResult(exit = 0, out = pkgs.map { "package:$it" }.joinToString("\n"), err = "")
            }
            else -> CmdResult(exit = 0, out = "ok", err = "")
        }
    }

    private val vms = mutableListOf<DualViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        for (vm in vms) {
            try {
                vm.viewModelScope.cancel()
            } catch (_: Exception) {
            }
        }
        // CI #306: drain outstanding viewModelScope children before Main is reset.
        TestDrainSupport.drainBeforeResetMain(vms)
        vms.clear()
        Dispatchers.resetMain()
    }

    private class JumpClock(var tick: Long = 0L) {
        fun now(): Long {
            tick += 1
            return tick * 1000L
        }
    }

    private fun makeVm(gw: RebuildFake, clock: JumpClock): DualViewModel {
        val vm = DualViewModel(
            repo = DualRepository(gw),
            gatewayPrivilege = Privilege.ROOT,
            elevators = listOf(SuElevator(gw), ShizukuElevator(NoOpGateway())),
            preference = ChannelPreference.AUTO,
            appPackageName = "com.example.test",
            persistPreference = null,
            suPresentCheck = { true },
            shizukuAuthCheck = { false },
            suGateway = gw,
            shizukuGateway = ShizukuCmdGateway("com.example.test"),
            activeGateway = gw,
            currentChannel = ResolvedChannel.ROOT,
            hookDirectReads = { listOf(MarkerRead.Readable(null), MarkerRead.Readable(null)) },
            hookDiagnosticGateways = { emptyList() },
            // C1 雙時鐘測試同餵：wall/mono 共用同一 fake，沿既有 JumpClock/FakeClock 模式。
            nowWallMs = { clock.now() },
            nowMonoMs = { clock.now() },
        )
        vms.add(vm)
        return vm
    }

    private fun await(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "timeout waiting for condition: state=${vms.lastOrNull()?.state?.value}",
                )
            }
            Thread.sleep(20)
        }
    }

    // FRESH 95 → rebuild 96 → successful clone：新 target 全量重查，
    // 舊包（僅 95 有的 com.example.old）不混入新清單。
    @Test
    fun cloneAfterRebuild_refreshesNewTargetWithoutOldPkgs() {
        val gw = RebuildFake()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertTrue(vm.state.value.cloned.contains("com.example.old"))

        // target 重建：96 的清單沒有 com.example.old。
        gw.dualId = 96
        vm.clone("com.example.fresh")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.targetId == 96 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(96, s.lastOperation?.pinnedTarget)
        assertEquals(
            "rebuild refresh must publish the full new package set, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.fresh"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("success after refresh must clear diagnose", s.diagnose)
    }

    // FRESH 95 → rebuild 96 → successful remove：新 target 全量重查，
    // 舊包不混入，已移除包亦不在新清單。
    @Test
    fun removeAfterRebuild_refreshesNewTargetWithoutOldPkgs() {
        val gw = RebuildFake(
            pkgsByUser = mutableMapOf(
                95 to mutableSetOf("com.example.chat", "com.example.old"),
                96 to mutableSetOf("com.example.chat", "com.example.victim"),
            ),
        )
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)

        gw.dualId = 96
        vm.requestRemove("com.example.victim")
        await { vm.state.value.pendingRemove?.dualId == 96 }

        vm.confirmRemove()
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.targetId == 96 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(96, s.lastOperation?.pinnedTarget)
        assertEquals(
            "rebuild refresh must publish the full new package set, got: ${s.cloned}",
            setOf("com.example.chat"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("remove success after refresh must clear diagnose", s.diagnose)
        assertNull("confirm must drop pending", s.pendingRemove)
    }

    // Issue #68 regression (now fixed): same userId, fresh serial. A
    // delete+recreate of user 95 as (95,41) makes every identity channel
    // report the new serial; the VM must use the serial carried by the core
    // Success plus a fresh verified triple, degrade the old FRESH(95,15)
    // inventory and re-probe the WHOLE new incarnation (not splice the new
    // package into the old list). The package CONTENT is replaced as well as
    // the serial, so a partial merge cannot accidentally look correct.
    @Test
    fun cloneAfterSameIdSerialRebuild_doesNotKeepOldSerialFreshInventory() {
        val gw = RebuildFake()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)
        assertEquals(setOf("com.example.chat", "com.example.old"), gw.pkgsByUser[95])

        // The profile was deleted and recreated with the SAME id but a fresh
        // platform serial AND a different package set: (95,41) now carries
        // `com.example.rebuilt` instead of the old `com.example.old`.
        gw.serial = 41
        gw.pkgsByUser[95] = mutableSetOf("com.example.chat", "com.example.rebuilt")

        vm.clone("com.example.new")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.targetId == 95 &&
                vm.state.value.inventory.targetSerial == 41 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(95, s.lastOperation?.pinnedTarget)
        assertEquals(
            "rebuild refresh must publish the full new package set, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.rebuilt", "com.example.new"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("success after refresh must clear diagnose", s.diagnose)
    }

    // Same #68 regression through the remove Success branch: consent pins
    // (95,41), the core Success carries 41, and the VM must still notice that
    // the old FRESH(95,15) inventory is from a different incarnation. The
    // package CONTENT is replaced too, so the rebuilt list is unambiguous.
    @Test
    fun removeAfterSameIdSerialRebuild_doesNotKeepOldSerialFreshInventory() {
        val gw = RebuildFake()
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)
        assertEquals(setOf("com.example.chat", "com.example.old"), gw.pkgsByUser[95])

        // Same id, new serial and new content. `com.example.old` is kept so
        // the confirm-time install re-check still authorizes the uninstall.
        gw.serial = 41
        gw.pkgsByUser[95] = mutableSetOf("com.example.chat", "com.example.old", "com.example.rebuilt")

        vm.requestRemove("com.example.old")
        await { vm.state.value.pendingRemove?.dualId == 95 }
        assertEquals(41, vm.state.value.pendingRemove?.dualSerial)

        vm.confirmRemove()
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.pendingRemove == null &&
                vm.state.value.inventory.targetId == 95 &&
                vm.state.value.inventory.targetSerial == 41 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(95, s.lastOperation?.pinnedTarget)
        assertEquals(
            "rebuild refresh must publish the full new package set, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.rebuilt"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("remove success after refresh must clear diagnose", s.diagnose)
    }

    // PR #88 Phase 1 regression (the stale-FRESH splice the pairwise helper
    // fix closes): core sees NO rebuild -- both its after-pin and post-list
    // fences still report (95,15), so Success.verifiedSerial == 15. The
    // same-id rebuild to (95,41) happens AFTER those fences, before the VM's
    // extra post-Success identity query. Comparing only
    // {pinned=15, verified=15, consent=null} made [15,15,null] look agreed and
    // spliced the new write into the stale FRESH(95,15) inventory.
    // NOTE: this test COUPLES to core's post-write identity fetch count
    // (after-pin -> post-list fence -> VM extra query == 3). If core adds or
    // removes a post-write identity triple, update the fake's flip instant.
    @Test
    fun cloneAfterRebuildVisibleOnlyToVmExtraFetch_repinsFreshSerial41WithoutResend() {
        val gw = RebuildFake(
            freshSerialAtExtraFetch = 41,
            freshPkgsAtExtraFetch = setOf("com.example.chat", "com.example.rebuilt"),
        )
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)
        assertEquals(setOf("com.example.chat", "com.example.old"), gw.pkgsByUser[95])

        vm.clone("com.example.new")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.targetId == 95 &&
                vm.state.value.inventory.targetSerial == 41 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(95, s.lastOperation?.pinnedTarget)
        assertEquals(
            "refresh must publish the whole post-rebuild package set, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.rebuilt"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("success after refresh must clear diagnose", s.diagnose)
        assertEquals("no install resend may occur", 1, gw.writeCount)
    }

    // Same fail-open shape with core's verifiedSerial == null (serial not
    // observable during the operation): the old helper saw [15,null,null],
    // no pairwise disagreement, and again spliced into FRESH(95,15). With
    // fresh.serial in the comparison, 15 vs 41 must force the rebuild refresh.
    // The serial is suppressed only AFTER the initial refresh so the inventory
    // starts as a known FRESH(95,15) pin.
    @Test
    fun cloneSuccessWithUnobservableCoreSerial_thenFresh41_repinsWithoutResend() {
        val gw = RebuildFake(
            freshSerialAtExtraFetch = 41,
            freshPkgsAtExtraFetch = setOf("com.example.chat", "com.example.rebuilt"),
        )
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertEquals(15, vm.state.value.inventory.targetSerial)

        // Core's pre-write/after-pin/post-list triples now see no serial, so
        // its Success carries verifiedSerial=null; the VM's extra query sees 41.
        gw.nullSerialBeforeExtraFetch = true
        vm.clone("com.example.new")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.targetId == 95 &&
                vm.state.value.inventory.targetSerial == 41 &&
                vm.state.value.inventory.freshness == InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(
            "refresh must publish the whole post-rebuild package set, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.rebuilt"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("success after refresh must clear diagnose", s.diagnose)
        assertEquals("no install resend may occur", 1, gw.writeCount)
    }

    // PR #88 F2: if the post-Success identity query cannot confirm the target
    // (null triple), the old FRESH inventory must be degraded and a read-only
    // refresh attempted. No install/uninstall may be re-issued.
    @Test
    fun cloneSuccess_thenPostSuccessIdentityNull_degradesAndNeverResendsWrite() {
        val gw = RebuildFake(postWriteIdentityMode = RebuildFake.IdentityMode.NULL_TARGET)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(15, vm.state.value.inventory.targetSerial)

        vm.clone("com.example.new")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.freshness != InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertTrue("inventory must be STALE or UNKNOWN, got: ${s.inventory}", s.inventory.freshness != InventoryFreshness.FRESH)
        assertEquals(
            "no stale FRESH splice may retain the written package, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.old"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the retained list", s.cloned.size, s.inventory.count)
        assertEquals("no install resend may occur", 1, gw.writeCount)
    }

    @Test
    fun removeSuccess_thenPostSuccessIdentityNull_degradesAndNeverResendsWrite() {
        val gw = RebuildFake(postWriteIdentityMode = RebuildFake.IdentityMode.NULL_TARGET)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(15, vm.state.value.inventory.targetSerial)

        vm.requestRemove("com.example.old")
        await { vm.state.value.pendingRemove?.dualId == 95 }
        vm.confirmRemove()
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.pendingRemove == null &&
                vm.state.value.inventory.freshness != InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertTrue("inventory must be STALE or UNKNOWN, got: ${s.inventory}", s.inventory.freshness != InventoryFreshness.FRESH)
        assertEquals(
            "no stale FRESH splice may retain the removed package, got: ${s.cloned}",
            setOf("com.example.chat"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the retained list", s.cloned.size, s.inventory.count)
        assertEquals("no uninstall resend may occur", 1, gw.writeCount)
    }

    // A known serial becoming null is evidence loss too -- never "no drift".
    @Test
    fun cloneSuccess_thenPostSuccessSerialBecomesNull_degradesAndNeverResendsWrite() {
        val gw = RebuildFake(postWriteIdentityMode = RebuildFake.IdentityMode.NULL_SERIAL)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(15, vm.state.value.inventory.targetSerial)

        vm.clone("com.example.new")
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.inventory.freshness != InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertTrue("known -> unknown must degrade freshness", s.inventory.freshness != InventoryFreshness.FRESH)
        assertEquals(
            "no stale FRESH splice may retain the written package, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.old"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the retained list", s.cloned.size, s.inventory.count)
        assertEquals("no install resend may occur", 1, gw.writeCount)
    }

    @Test
    fun removeSuccess_thenPostSuccessSerialBecomesNull_degradesAndNeverResendsWrite() {
        val gw = RebuildFake(postWriteIdentityMode = RebuildFake.IdentityMode.NULL_SERIAL)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(15, vm.state.value.inventory.targetSerial)

        vm.requestRemove("com.example.old")
        await { vm.state.value.pendingRemove?.dualId == 95 }
        vm.confirmRemove()
        await {
            !vm.state.value.loading &&
                vm.state.value.lastOperation?.phase == OpPhase.SUCCESS &&
                vm.state.value.pendingRemove == null &&
                vm.state.value.inventory.freshness != InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertTrue("known -> unknown must degrade freshness", s.inventory.freshness != InventoryFreshness.FRESH)
        assertEquals(
            "no stale FRESH splice may retain the removed package, got: ${s.cloned}",
            setOf("com.example.chat"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the retained list", s.cloned.size, s.inventory.count)
        assertEquals("no uninstall resend may occur", 1, gw.writeCount)
    }

    // PR #88 Round 2 P2-R2-1 (clone). The all-null serial case is an accepted
    // capability limit, but only when the serial NEVER becomes readable. Here
    // the initial listing cannot see a serial, so the inventory is honestly
    // FRESH(95,null) with {chat,old}; before the clone the serial becomes
    // observable (41) and the profile content is replaced. Old pin has no
    // serial to disagree with the operation's 41, so the helper must treat
    // this as an EVIDENCE UPGRADE (not a proven rebuild): degrade to non-FRESH
    // and do ONE read-only refresh, without resending the install. The target
    // barrier waits for the complete FRESH(95,41) snapshot, not the first
    // non-FRESH frame.
    @Test
    fun cloneAfterSerialBecameObservable_refreshesIntoFresh95_41WithoutResend() {
        val gw = RebuildFake(nullSerialBeforeExtraFetch = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertNull("serial not observable at initial listing", vm.state.value.inventory.targetSerial)
        assertEquals(setOf("com.example.chat", "com.example.old"), vm.state.value.cloned.toSet())

        // The serial becomes readable and the profile content is swapped.
        gw.nullSerialBeforeExtraFetch = false
        gw.serial = 41
        gw.pkgsByUser[95] = mutableSetOf("com.example.chat", "com.example.rebuilt")

        vm.clone("com.example.new")
        val expected = setOf("com.example.chat", "com.example.rebuilt", "com.example.new")
        await {
            val s = vm.state.value
            !s.loading &&
                s.lastOperation?.phase == OpPhase.SUCCESS &&
                s.inventory.freshness == InventoryFreshness.FRESH &&
                s.inventory.targetId == 95 &&
                s.inventory.targetSerial == 41 &&
                s.inventory.count == s.cloned.size &&
                s.cloned.toSet() == expected
        }

        val s = vm.state.value
        assertEquals(95, s.lastOperation?.pinnedTarget)
        assertEquals(
            "refresh must publish the whole post-upgrade package set, got: ${s.cloned}",
            expected,
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("success after refresh must clear diagnose", s.diagnose)
        assertNull("clone must not leave a pending remove", s.pendingRemove)
        assertFalse(
            "evidence upgrade is not a proven target rebuild",
            s.lastOperation?.detail?.contains("target 已重建") == true,
        )
        assertEquals("no install resend may occur", 1, gw.writeCount)
    }

    // P2-R2-1 (remove). Same evidence-upgrade shape, but the consent pin
    // (pendingRemove.dualSerial) also carries 41. The old id-only FRESH(95,null)
    // inventory must not keep {chat,old} after the read-only refresh; the
    // complete FRESH(95,41) snapshot must contain only {rebuilt}.
    @Test
    fun removeAfterSerialBecameObservable_refreshesIntoFresh95_41WithoutResend() {
        val gw = RebuildFake(nullSerialBeforeExtraFetch = true)
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertNull("serial not observable at initial listing", vm.state.value.inventory.targetSerial)
        assertEquals(setOf("com.example.chat", "com.example.old"), vm.state.value.cloned.toSet())

        gw.nullSerialBeforeExtraFetch = false
        gw.serial = 41
        gw.pkgsByUser[95] = mutableSetOf("com.example.chat", "com.example.rebuilt")

        vm.requestRemove("com.example.chat")
        await {
            vm.state.value.pendingRemove?.dualId == 95 &&
                vm.state.value.pendingRemove?.dualSerial == 41
        }
        vm.confirmRemove()
        val expected = setOf("com.example.rebuilt")
        await {
            val s = vm.state.value
            !s.loading &&
                s.lastOperation?.phase == OpPhase.SUCCESS &&
                s.inventory.freshness == InventoryFreshness.FRESH &&
                s.inventory.targetId == 95 &&
                s.inventory.targetSerial == 41 &&
                s.inventory.count == s.cloned.size &&
                s.cloned.toSet() == expected
        }

        val s = vm.state.value
        assertEquals(95, s.lastOperation?.pinnedTarget)
        assertEquals(
            "refresh must publish the whole post-upgrade package set, got: ${s.cloned}",
            expected,
            s.cloned.toSet(),
        )
        assertEquals("count must match the rebuilt list", s.cloned.size, s.inventory.count)
        assertNull("success after refresh must clear diagnose", s.diagnose)
        assertNull("confirm must drop pending", s.pendingRemove)
        assertFalse(
            "evidence upgrade is not a proven target rebuild",
            s.lastOperation?.detail?.contains("target 已重建") == true,
        )
        assertEquals("no uninstall resend may occur", 1, gw.writeCount)
    }

    // P2-R2-1 failure path (clone): the read-only refresh triggered by the
    // evidence upgrade cannot confirm the target. The inventory must stay
    // non-FRESH with CHANNEL_ERROR/CHANNEL, no new package may be spliced, and
    // the install must not be re-issued.
    @Test
    fun cloneAfterSerialBecameObservable_whenRefreshFails_staysNonFreshWithoutResend() {
        val gw = RebuildFake(
            nullSerialBeforeExtraFetch = true,
            postWriteIdentityMode = RebuildFake.IdentityMode.NULL_TARGET,
        )
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertNull(vm.state.value.inventory.targetSerial)

        gw.nullSerialBeforeExtraFetch = false
        gw.serial = 41
        gw.pkgsByUser[95] = mutableSetOf("com.example.chat", "com.example.rebuilt")

        vm.clone("com.example.new")
        await {
            val s = vm.state.value
            !s.loading &&
                s.lastOperation?.phase == OpPhase.SUCCESS &&
                s.diagnose?.code == DiagnoseCode.CHANNEL_ERROR &&
                s.msgKind == MsgKind.CHANNEL &&
                s.inventory.freshness != InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(
            "failed refresh must not leave a mixed FRESH splice, got: ${s.cloned}",
            setOf("com.example.chat", "com.example.old"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the retained list", s.cloned.size, s.inventory.count)
        assertFalse(
            "evidence upgrade is not a proven target rebuild",
            s.lastOperation?.detail?.contains("target 已重建") == true,
        )
        assertEquals("no install resend may occur", 1, gw.writeCount)
    }

    // P2-R2-1 failure path (remove): same as above through confirmRemove().
    // The removed package is still dropped from the retained list, but no
    // post-upgrade content may be spliced and the inventory stays non-FRESH.
    @Test
    fun removeAfterSerialBecameObservable_whenRefreshFails_staysNonFreshWithoutResend() {
        val gw = RebuildFake(
            nullSerialBeforeExtraFetch = true,
            postWriteIdentityMode = RebuildFake.IdentityMode.NULL_TARGET,
        )
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertNull(vm.state.value.inventory.targetSerial)

        gw.nullSerialBeforeExtraFetch = false
        gw.serial = 41
        gw.pkgsByUser[95] = mutableSetOf("com.example.chat", "com.example.rebuilt")

        vm.requestRemove("com.example.chat")
        await {
            vm.state.value.pendingRemove?.dualId == 95 &&
                vm.state.value.pendingRemove?.dualSerial == 41
        }
        vm.confirmRemove()
        await {
            val s = vm.state.value
            !s.loading &&
                s.lastOperation?.phase == OpPhase.SUCCESS &&
                s.diagnose?.code == DiagnoseCode.CHANNEL_ERROR &&
                s.msgKind == MsgKind.CHANNEL &&
                s.inventory.freshness != InventoryFreshness.FRESH
        }

        val s = vm.state.value
        assertEquals(
            "failed refresh must not leave a mixed FRESH splice, got: ${s.cloned}",
            setOf("com.example.old"),
            s.cloned.toSet(),
        )
        assertEquals("count must match the retained list", s.cloned.size, s.inventory.count)
        assertNull("confirm must drop pending", s.pendingRemove)
        assertFalse(
            "evidence upgrade is not a proven target rebuild",
            s.lastOperation?.detail?.contains("target 已重建") == true,
        )
        assertEquals("no uninstall resend may occur", 1, gw.writeCount)
    }

    // Accepted capability limit: serial is NEVER observable (suppression is
    // kept on, and NULL_SERIAL drops it at the VM's extra query too). Every
    // serial is null, so the helper must not invent drift: no refresh, no
    // degrade, FRESH(95,null) survives with the new package simply spliced.
    @Test
    fun cloneAllNullSerials_keepsAllowedFreshIdOnlyLimit() {
        val gw = RebuildFake(
            nullSerialBeforeExtraFetch = true,
            postWriteIdentityMode = RebuildFake.IdentityMode.NULL_SERIAL,
        )
        val clock = JumpClock()
        val vm = makeVm(gw, clock)
        await { vm.state.value.channelReady }

        vm.refresh()
        await { vm.state.value.inventory.freshness == InventoryFreshness.FRESH }
        assertEquals(95, vm.state.value.inventory.targetId)
        assertNull(vm.state.value.inventory.targetSerial)

        // No world flip: suppression stays on through the clone.
        vm.clone("com.example.new")
        val expected = setOf("com.example.chat", "com.example.old", "com.example.new")
        await {
            val s = vm.state.value
            !s.loading &&
                s.lastOperation?.phase == OpPhase.SUCCESS &&
                s.inventory.freshness == InventoryFreshness.FRESH &&
                s.inventory.targetId == 95 &&
                s.inventory.targetSerial == null &&
                s.inventory.count == s.cloned.size &&
                s.cloned.toSet() == expected
        }

        val s = vm.state.value
        assertEquals(95, s.lastOperation?.pinnedTarget)
        assertEquals(
            "all-null serials keep the id-only FRESH splice, got: ${s.cloned}",
            expected,
            s.cloned.toSet(),
        )
        assertEquals("count must match the list", s.cloned.size, s.inventory.count)
        assertNull("all-null serials must not fabricate a drift diagnostic", s.diagnose)
        assertEquals(MsgKind.INFO, s.msgKind)
        assertFalse(
            "all-null serials must not claim a target rebuild",
            s.lastOperation?.detail?.contains("target 已重建") == true,
        )
        assertEquals("no install resend may occur", 1, gw.writeCount)
    }
}
