// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// DEVICE-UNVERIFIED：S24 未跑（[待實機驗證]）。本檔為 CI-run API29
// in-process fake suite（run 42582946410，locale green；不 spawn、零 pm
// 寫入），僅覆蓋行程內 Parcel/Binder wiring；真 service spawn、Shizuku
// daemon 重建與 S24 實機行為仍未驗，勿視為可用。
// 不測 DESTROY：TRANSACTION_DESTROY 會 System.exit(0)，永不呼叫。
// 零 pm 寫入：所有 wire cmd 都是唯讀字串（"pm list packages ..."），
// service 側 captureProcessFn 是計數假替身（不 spawn），client 側
// FakeExecBinder 是行程內假 binder（不經 Shizuku/真 service），
// 故全程不產生任何 pm 寫入。
//
// Phase B §2（Phase 4 instrumentation，API29 in-process fake，CI run 42582946410 green）：真
// android.os.Parcel 的 Binder 層測試，補 JVM 純函數測試（ShizukuExecHandleTest /
// GatewayAssemblyTest）覆蓋不到的 Parcel 讀/寫 wiring。
// - Service 側（5 測）：對 GalaxyExecService 經 binder.transact（分派到
//   onTransact）真寫 data Parcel、真讀 reply Parcel，用 captureProcessFn
//   計數器斷言 spawn==0/1 與 exact reply。owner service 用 Context ctor
//   建（target context）；行程內 transact 的 calling uid 為行程 uid，
//   與 Context ctor 解析出的 owner
//   uid 一致（DEVICE-UNVERIFIED：此假設需裝置實跑確認）。foreign 用
//   default ctor 的 fail-closed sink（expectedAppUid=-1，永遠 denied），
//   真正跨 uid IPC foreign（第二 app/程序）不在本輪範圍。
// - Client 側（4 測）：FakeExecBinder 真寫 reply Parcel（writeInt +
//   writeString，含 null string）+ FakeBindDriver（拷自
//   app/src/test/.../ShizukuTeardownTest.kt 的 FakeBindDriver，來源已註明；
//   本檔改為 send() 立即回呼以適用 run()，Teardown 版為手動 complete 以
//   驅動 boundBinder）。null out/err/both -> transportUnknown 且 drop
//   cache（hasCachedBindingForTest=false、releaseCount==1），下一次 run
//   重綁；normal reply 保持 cache（對照組）。
// - never-resend：核心的 read-only verify/probe 重試政策已由
//   core/src/test/.../VerifyRetryTest.kt 覆蓋（transportUnknown/timedOut
//   不重送 write、只做唯讀 verify）；本檔只證明 gateway 在 binder 層
//   不內部重送（每次 run transactCount 恰為 1），不另測 repository 層。
package com.galaxydual.plus

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxydual.core.CmdResult
import com.galaxydual.core.TRUNCATION_MARKER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ShizukuBinderParcelTest {

    // ---- Service 側 helpers -------------------------------------------------

    private class SpawnCounter {
        var calls = 0
        var lastArgv: Array<String>? = null
        var lastTimeoutMs: Long = -1L
        var result: CmdResult = CmdResult(exit = 0, out = "package:com.example.app\n", err = "")
        var toThrow: RuntimeException? = null

        val fn: (Array<String>, Long) -> CmdResult = { argv, timeoutMs ->
            calls++
            lastArgv = argv
            lastTimeoutMs = timeoutMs
            toThrow?.let { throw it }
            result
        }
    }

    private fun ownerService(counter: SpawnCounter): GalaxyExecService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return GalaxyExecService(context).also { it.captureProcessFn = counter.fn }
    }

    /** 真寫 data Parcel、經 binder.transact 分派到 onTransact、真讀 reply Parcel。
     * （Binder.onTransact 是 protected，不能直接呼叫；transact 是 production
     * client 真正走的路徑，calling uid 為行程 uid。） */
    private fun transactRaw(
        service: GalaxyExecService,
        rawCmd: String?,
    ): Triple<Int, String?, String?> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(rawCmd)
            val handled = service.transact(
                ShizukuExecProtocol.TRANSACTION_EXEC, data, reply, 0,
            )
            assertTrue("EXEC transact must handle the code", handled)
            reply.setDataPosition(0)
            Triple(reply.readInt(), reply.readString(), reply.readString())
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // Service 側 1/5：null / "" —— pre-spawn bad request，spawn==0，exact reply。
    @Test
    fun service_nullOrEmpty_noSpawn_exactBadRequest() {
        for (raw in listOf<String?>(null, "")) {
            val counter = SpawnCounter()
            val (exit, out, err) = transactRaw(ownerService(counter), raw)
            assertEquals("spawn must be 0 for rawCmd=$raw", 0, counter.calls)
            assertEquals(1, exit)
            assertEquals("", out)
            assertEquals("bad request: blank command", err)
        }
    }

    // Service 側 2/5：空白 —— 同上（混合空白）。
    @Test
    fun service_blank_noSpawn_exactBadRequest() {
        for (raw in listOf("   ", "\n\t", " \t ")) {
            val counter = SpawnCounter()
            val (exit, out, err) = transactRaw(ownerService(counter), raw)
            assertEquals("spawn must be 0 for blank cmd", 0, counter.calls)
            assertEquals(1, exit)
            assertEquals("", out)
            assertEquals("bad request: blank command", err)
        }
    }

    // Service 側 3/5：foreign —— default ctor fail-closed sink
    //（expectedAppUid=-1）：denied、spawn==0。真正跨 uid IPC 不在本輪。
    @Test
    fun service_foreignCaller_noSpawn_exactDenied() {
        val counter = SpawnCounter()
        val service = GalaxyExecService().also { it.captureProcessFn = counter.fn }
        val (exit, out, err) = transactRaw(service, "pm list packages --user 95")
        assertEquals(0, counter.calls)
        assertEquals(1, exit)
        assertEquals("", out)
        assertEquals(ShizukuExecProtocol.DENIED_FOREIGN_CALLER, err)
    }

    // Service 側 4/5：valid —— spawn 恰一次、argv 為 sh -c <cmd> verbatim、
    // exact reply 為 capture 結果。
    @Test
    fun service_validCmd_spawnOnce_exactReply() {
        val counter = SpawnCounter()
        val raw = "pm list packages --user 95"
        val (exit, out, err) = transactRaw(ownerService(counter), raw)
        assertEquals(1, counter.calls)
        assertEquals(
            listOf("sh", "-c", raw),
            counter.lastArgv?.toList(),
        )
        assertEquals(ShizukuExecProtocol.EXEC_TIMEOUT_MS, counter.lastTimeoutMs)
        assertEquals(0, exit)
        assertEquals("package:com.example.app\n", out)
        assertEquals("", err)
    }

    // Service 側 5/5：execThrows —— capture 拋出屬 post-spawn unknown：
    // spawn==1、exact backstop（錨定 prefix + marker tail），永不 TrustedFull
    // 形狀（exit=1、out=""）。
    @Test
    fun service_execThrows_postSpawnBackstop() {
        val counter = SpawnCounter().also { it.toThrow = RuntimeException("boom") }
        val (exit, out, err) = transactRaw(ownerService(counter), "pm list packages --user 95")
        assertEquals(1, counter.calls)
        assertEquals(1, exit)
        assertEquals("", out)
        assertTrue(
            "post-spawn err must start with the anchored backstop, got: $err",
            err!!.startsWith("service exec failed: "),
        )
        assertTrue("post-spawn err must carry the detail, got: $err", err.contains("boom"))
        assertTrue(
            "post-spawn err must end with the marker, got: $err",
            err.endsWith("\n$TRUNCATION_MARKER"),
        )
    }

    // ---- Client 側 helpers --------------------------------------------------

    // Fake binder：真寫 reply Parcel（含 null string），計數 transact。
    // reply 欄位可變，方便同一 gateway 測「下一次 run 重綁」時切回 normal。
    private class FakeExecBinder(
        @Volatile var replyExit: Int = 0,
        @Volatile var replyOut: String? = "package:com.example.app\n",
        @Volatile var replyErr: String? = "",
    ) : Binder() {
        val transactCount = AtomicInteger(0)
        @Volatile var lastCmd: String? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ShizukuExecProtocol.TRANSACTION_EXEC) {
                return super.onTransact(code, data, reply, flags)
            }
            transactCount.incrementAndGet()
            lastCmd = data.readString()
            reply?.writeInt(replyExit)
            reply?.writeString(replyOut)
            reply?.writeString(replyErr)
            return true
        }
    }

    // FakeBindDriver：拷自 ShizukuTeardownTest.FakeBindDriver（來源：
    // app/src/test/java/com/galaxydual/plus/ShizukuTeardownTest.kt，
    // 內含 FakeBindDriver + Sent/Request 計數 release 的形狀）。
    // 差異：Teardown 版 send() 只入隊、由測試手動 completeLatestWith 驅動
    // boundBinder；本版 send() 立即 onResult(success(binder))，以適用
    // gateway.run()（run 內 boundBinder 會等 latch，本版已即時完成）。
    private class FakeBindDriver(private val binder: IBinder) : ShizukuBindDriver {
        class Sent {
            val releaseCount = AtomicInteger(0)
            val request = object : ShizukuBindDriver.Request {
                // 計每次呼叫（非 idempotent），以釘住 gateway 至多 release 一次。
                override fun release() {
                    releaseCount.incrementAndGet()
                }
            }
        }

        private val sent = CopyOnWriteArrayList<Sent>()
        val sendCount: Int get() = sent.size
        val totalReleases: Int get() = sent.sumOf { it.releaseCount.get() }

        override fun send(onResult: (Result<IBinder>) -> Unit): ShizukuBindDriver.Request {
            val s = Sent()
            sent.add(s)
            onResult(Result.success(binder))
            return s.request
        }
    }

    private class FakeLegacyCleaner : ShizukuLegacyCleaner {
        var calls = 0
        override fun removeLegacy() {
            calls++
        }
    }

    private fun makeClientGateway(
        driver: FakeBindDriver,
        cleaner: FakeLegacyCleaner = FakeLegacyCleaner(),
    ): ShizukuCmdGateway = ShizukuCmdGateway(
        "com.example.test",
        bindDriver = driver,
        legacyCleaner = cleaner,
        preflightPing = { true },
        preflightPermission = { true },
        preflightVersion = { ShizukuExecProtocol.MIN_SHIZUKU_VERSION },
    )

    private fun assertTransportUnknownDrop(
        gw: ShizukuCmdGateway,
        driver: FakeBindDriver,
        binder: FakeExecBinder,
        res: CmdResult,
    ) {
        assertTrue("must be transportUnknown, got: $res", res.transportUnknown)
        assertFalse("must not be timedOut, got: $res", res.timedOut)
        assertEquals(1, res.exit)
        assertEquals("", res.out)
        assertEquals("binder layer must not resend", 1, binder.transactCount.get())
        assertEquals(1, driver.sendCount)
        assertEquals("stale cache must be dropped exactly once", 1, driver.totalReleases)
        assertEquals(
            "hasCachedBindingForTest must be false after drop",
            false,
            runBlocking { gw.hasCachedBindingForTest() },
        )
    }

    private fun rebindNextRunSucceeds(
        gw: ShizukuCmdGateway,
        driver: FakeBindDriver,
        binder: FakeExecBinder,
    ) {
        // 切回 normal reply：下一次 run 必須重新 bind（sendCount 2）且成功。
        binder.replyExit = 0
        binder.replyOut = "package:com.example.app\n"
        binder.replyErr = ""
        val res2 = runBlocking { gw.run("pm list packages --user 95") }
        assertEquals(0, res2.exit)
        assertEquals("package:com.example.app\n", res2.out)
        assertFalse(res2.transportUnknown)
        assertEquals("next run must rebind after the drop", 2, driver.sendCount)
        assertEquals(2, binder.transactCount.get())
        assertTrue(
            "normal reply must keep the cache",
            runBlocking { gw.hasCachedBindingForTest() },
        )
    }

    // Client 側 1/4：null out -> transportUnknown + drop + 下一次重綁。
    @Test
    fun client_nullOut_transportUnknown_dropsCache_rebindsNextRun() {
        val binder = FakeExecBinder(replyExit = 0, replyOut = null, replyErr = "")
        val driver = FakeBindDriver(binder)
        val gw = makeClientGateway(driver)
        try {
            val res = runBlocking { gw.run("pm list packages --user 95") }
            assertTransportUnknownDrop(gw, driver, binder, res)
            rebindNextRunSucceeds(gw, driver, binder)
        } finally {
            runBlocking { gw.close() }
        }
    }

    // Client 側 2/4：null err -> 同上。
    @Test
    fun client_nullErr_transportUnknown_dropsCache_rebindsNextRun() {
        val binder = FakeExecBinder(replyExit = 0, replyOut = "package:com.example.app\n", replyErr = null)
        val driver = FakeBindDriver(binder)
        val gw = makeClientGateway(driver)
        try {
            val res = runBlocking { gw.run("pm list packages --user 95") }
            assertTransportUnknownDrop(gw, driver, binder, res)
            rebindNextRunSucceeds(gw, driver, binder)
        } finally {
            runBlocking { gw.close() }
        }
    }

    // Client 側 3/4：null both -> 同上。
    @Test
    fun client_nullBoth_transportUnknown_dropsCache_rebindsNextRun() {
        val binder = FakeExecBinder(replyExit = 0, replyOut = null, replyErr = null)
        val driver = FakeBindDriver(binder)
        val gw = makeClientGateway(driver)
        try {
            val res = runBlocking { gw.run("pm list packages --user 95") }
            assertTransportUnknownDrop(gw, driver, binder, res)
            rebindNextRunSucceeds(gw, driver, binder)
        } finally {
            runBlocking { gw.close() }
        }
    }

    // Client 側 4/4：normal 對照組 —— 保持 cache：第二次 run 不重綁
    //（sendCount 仍 1）、不 release，且仍 transact 一次（binder 層不重送、
    // 也不多送；read-only verify 重試政策見 core VerifyRetryTest）。
    @Test
    fun client_normalReply_keepsCache_control() {
        val binder = FakeExecBinder(replyExit = 0, replyOut = "package:com.example.app\n", replyErr = "")
        val driver = FakeBindDriver(binder)
        val gw = makeClientGateway(driver)
        try {
            val res1 = runBlocking { gw.run("pm list packages --user 95") }
            assertEquals(0, res1.exit)
            assertEquals("package:com.example.app\n", res1.out)
            assertFalse(res1.transportUnknown)
            assertFalse(res1.timedOut)
            assertEquals(1, driver.sendCount)
            assertEquals(0, driver.totalReleases)
            assertEquals(1, binder.transactCount.get())
            assertTrue(runBlocking { gw.hasCachedBindingForTest() })

            val res2 = runBlocking { gw.run("pm list packages --user 95") }
            assertEquals(0, res2.exit)
            assertEquals("normal reply must reuse the cached binding", 1, driver.sendCount)
            assertEquals("normal reply must not release", 0, driver.totalReleases)
            assertEquals(2, binder.transactCount.get())
            assertTrue(runBlocking { gw.hasCachedBindingForTest() })
        } finally {
            runBlocking { gw.close() }
        }
    }
}
