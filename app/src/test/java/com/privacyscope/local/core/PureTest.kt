package com.privacyscope.local.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑单测：把 `Pure` 里那五块判断钉死。
 *
 * 为什么只测这些：它们是「字符串进、布尔/字节出」的判断，出错时**不会崩**，
 * 只会静默给出错结论——比如把 uid 级的 ignore 读成包级 allow，
 * 界面就会一直说"改失败"，而人永远查不出来。这种 bug 只能用测试挡。
 */
class PureTest {

    // ───────────── 一、appops 输出解析 ─────────────

    @Test
    fun parseOpLines_stripsUidPrefixAndTimestamp() {
        val out = """
            Uid mode: COARSE_LOCATION: ignore
            COARSE_LOCATION: allow
            READ_CLIPBOARD: ignore; time=+1d2h3m4s5ms ago
            No operations.
        """.trimIndent()

        val lines = Pure.parseOpLines(out)

        assertEquals(3, lines.size)
        assertTrue(lines[0].uidLevel)
        assertEquals("COARSE_LOCATION", lines[0].op)
        assertEquals("ignore", lines[0].mode)
        assertFalse(lines[1].uidLevel)
        assertEquals("allow", lines[1].mode)
        // 带时间戳的行只留模式
        assertEquals("READ_CLIPBOARD", lines[2].op)
        assertEquals("ignore", lines[2].mode)
    }

    @Test
    fun parseOpLines_ignoresGarbageLines() {
        val out = "No operations.\n\nsome sentence without colon\n: emptykey"
        assertTrue(Pure.parseOpLines(out).isEmpty())
    }

    @Test
    fun mergeOps_prefersUidLevel() {
        val out = """
            Uid mode: COARSE_LOCATION: ignore
            COARSE_LOCATION: allow
            READ_CLIPBOARD: ignore
        """.trimIndent()

        val m = Pure.mergeOps(Pure.parseOpLines(out))

        // 这条断言就是 v0.9.9 修的那个真 bug：uid 级必须盖过包级
        assertEquals("ignore", m["COARSE_LOCATION"])
        assertEquals("ignore", m["READ_CLIPBOARD"])
        assertEquals(2, m.size)
    }

    @Test
    fun pickMode_returnsUidValueNotPackageOne() {
        val out = "Uid mode: FINE_LOCATION: ignore\nFINE_LOCATION: allow"
        assertEquals("ignore", Pure.pickMode("FINE_LOCATION", out))
    }

    @Test
    fun pickMode_returnsNullWhenOpAbsent() {
        assertNull(Pure.pickMode("CAMERA", "READ_CLIPBOARD: ignore"))
        assertNull(Pure.pickMode("CAMERA", ""))
    }

    // ───────────── 二、写入结果判决 ─────────────

    @Test
    fun same_acceptsDenyWhenIgnoreWasWritten() {
        assertTrue(Pure.same("ignore", "deny"))
        assertTrue(Pure.same("ignore", "ignore"))
        assertTrue(Pure.same("default", "default"))
        assertFalse(Pure.same("allow", "ignore"))
    }

    @Test
    fun isOff_treatsIgnoreAndDenyAsClosed() {
        assertTrue(Pure.isOff("ignore"))
        assertTrue(Pure.isOff("deny"))
        assertFalse(Pure.isOff("allow"))
        assertFalse(Pure.isOff("default"))
        assertFalse(Pure.isOff("ask"))
    }

    @Test
    fun verdict_okWhenValueMatches() {
        assertEquals(
            Pure.Verdict.OK,
            Pure.verdict("default", "ignore", "ignore", sawUnknown = false, spoke = true)
        )
    }

    @Test
    fun verdict_unsupportedWhenAllNamesUnknown() {
        assertEquals(
            Pure.Verdict.UNSUPPORTED,
            Pure.verdict("default", "default", "ignore", sawUnknown = true, spoke = true)
        )
    }

    /**
     * 用户 m08791 贴的那段真机日志就是这一条：GET_USAGE_STATS
     * 三条命令都 exit=0、stdout/stderr 全空、回读仍是 default。
     */
    @Test
    fun verdict_noEffectWhenSilent() {
        assertEquals(
            Pure.Verdict.NO_EFFECT,
            Pure.verdict("default", "default", "ignore", sawUnknown = false, spoke = false)
        )
    }

    @Test
    fun verdict_ignoredWhenSystemSpeaksButRefuses() {
        assertEquals(
            Pure.Verdict.IGNORED,
            Pure.verdict("default", "default", "ignore", sawUnknown = false, spoke = true)
        )
    }

    // ───────────── 三、adb 命令白名单 ─────────────

    @Test
    fun checkCommand_allowsLegitShapes() {
        assertNull(Pure.checkCommand("appops set com.a.b READ_DEVICE_IDENTIFIERS ignore"))
        assertNull(Pure.checkCommand("appops set --uid com.a.b COARSE_LOCATION ignore"))
        assertNull(Pure.checkCommand("appops get com.a.b"))
        assertNull(Pure.checkCommand("appops get com.a.b CAMERA"))
        assertNull(Pure.checkCommand("pm revoke com.a.b android.permission.CAMERA"))
        assertNull(Pure.checkCommand("pm list packages"))
        assertNull(Pure.checkCommand("id"))
        assertNull(Pure.checkCommand("getprop ro.build.version.sdk"))
    }

    @Test
    fun checkCommand_rejectsCommandInjection() {
        val bad = listOf(
            "appops get com.a.b; rm -rf /sdcard",
            "appops get com.a.b | sh",
            "appops get com.a.b && reboot",
            "appops get com.a.b `id`",
            "appops get com.a.b \$HOME",
            "appops get com.a.b\nrm -rf /sdcard"
        )
        for (cmd in bad) {
            val why = Pure.checkCommand(cmd)
            assertNotNull("应当被拒：$cmd", why)
            assertTrue("应说明是连接符问题：$cmd → $why", why!!.contains("连接符"))
        }
    }

    @Test
    fun checkCommand_rejectsUnknownShapes() {
        assertNotNull(Pure.checkCommand("rm -rf /sdcard"))
        assertNotNull(Pure.checkCommand("su"))
        assertNotNull(Pure.checkCommand("appops set com.a.b CAMERA"))          // 少一个值
        assertNotNull(Pure.checkCommand("appops set com.a.b CAMERA overwrite")) // 值不在枚举里
        assertNotNull(Pure.checkCommand("pm install /sdcard/x.apk"))
        assertEquals("空命令", Pure.checkCommand("   "))
    }

    // ───────────── 四、域名匹配 ─────────────

    @Test
    fun matchesDomain_exactAndSubdomain() {
        assertTrue(Pure.matchesDomain("a.event.qq.com", "event.qq.com"))
        assertTrue(Pure.matchesDomain("event.qq.com", "event.qq.com"))
        // 大小写与结尾点都要吃掉
        assertTrue(Pure.matchesDomain("EVENT.QQ.COM.", "event.qq.com"))
    }

    @Test
    fun matchesDomain_doesNotMatchLookalikes() {
        // 关键：不能用 contains —— 否则 hello-tracking.cn 会被 tracking.cn 误杀
        assertFalse(Pure.matchesDomain("hello-tracking.cn", "tracking.cn"))
        assertFalse(Pure.matchesDomain("tracking.cn.evil.com", "tracking.cn"))
        assertFalse(Pure.matchesDomain("", "tracking.cn"))
        assertFalse(Pure.matchesDomain("tracking.cn", ""))
    }

    @Test
    fun firstDomainHit_returnsTheHitSoCallerCanBypass() {
        // 语义：命中「白名单」= 放行。所以这里要返回命中的规则名，让调用方据此 return null。
        val allow = setOf("qq.com")
        assertEquals("qq.com", Pure.firstDomainHit("a.event.qq.com", allow))
        // 不在名单里的返回 null
        assertNull(Pure.firstDomainHit("example.com", allow))
        // 自定义黑名单同理，精确命中规则本身
        val custom = setOf("a.event.qq.com")
        assertEquals("a.event.qq.com", Pure.firstDomainHit("a.event.qq.com", custom))
        assertNull(Pure.firstDomainHit("b.event.qq.com", custom))
    }

    // ───────────── 五、DNS 报文 ─────────────

    /** 拼一条标准的 A 记录查询：example.com */
    private fun query(name: String, id: Int = 0x1234): ByteArray {
        val out = ArrayList<Byte>()
        out.add((id shr 8).toByte()); out.add((id and 0xFF).toByte())
        out.add(0x01); out.add(0x00)              // flags: RD=1
        out.add(0x00); out.add(0x01)              // QDCOUNT=1
        out.add(0x00); out.add(0x00)              // ANCOUNT=0
        out.add(0x00); out.add(0x00)              // NSCOUNT=0
        out.add(0x00); out.add(0x00)              // ARCOUNT=0
        for (part in name.split(".")) {
            out.add(part.length.toByte())
            for (c in part) out.add(c.code.toByte())
        }
        out.add(0x00)                             // 名字结束
        out.add(0x00); out.add(0x01)              // QTYPE=A
        out.add(0x00); out.add(0x01)              // QCLASS=IN
        return out.toByteArray()
    }

    @Test
    fun qname_readsTheName() {
        assertEquals("example.com", Pure.qname(query("example.com")))
        assertEquals("a.b.c.d.example.org", Pure.qname(query("a.b.c.d.example.org")))
    }

    @Test
    fun qname_rejectsAnswersAndTruncatedPackets() {
        val q = query("example.com")
        q[2] = (q[2].toInt() or 0x80).toByte()    // QR=1：这是应答，不是查询
        assertNull(Pure.qname(q))
        assertNull(Pure.qname(ByteArray(8)))
    }

    @Test
    fun buildSinkhole_turnsQueryIntoZeroAddressAnswer() {
        val q = query("metrics.example.com")
        val a = Pure.buildSinkhole(q)
        assertNotNull(a)
        a!!

        // QR=1（应答）、RA=1、RCODE=0
        assertTrue("QR 位应为 1", (a[2].toInt() and 0x80) != 0)
        assertTrue("RA 位应为 1", (a[3].toInt() and 0x80) != 0)
        assertEquals(0, a[3].toInt() and 0x0F)
        // ANCOUNT=1，其余为 0
        assertEquals(1, ((a[6].toInt() and 0xFF) shl 8) or (a[7].toInt() and 0xFF))
        assertEquals(0, ((a[8].toInt() and 0xFF) shl 8) or (a[9].toInt() and 0xFF))
        // ID 照抄、问题段（从第 12 字节起到末尾）原样保留；flags 与计数被改写是应该的
        assertEquals(q[0], a[0])
        assertEquals(q[1], a[1])
        assertEquals(q.copyOfRange(12, q.size).toList(), a.copyOfRange(12, q.size).toList())
        // 答案 16 字节：指针 0xC00C / A / IN / TTL 60 / RDLENGTH 4 / 0.0.0.0
        val qEnd = q.size
        val ans = a.copyOfRange(qEnd, qEnd + 16).map { it.toInt() and 0xFF }
        assertEquals(listOf(0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01), ans.subList(0, 6))
        assertEquals(listOf(0, 0, 0, 60), ans.subList(6, 10))
        assertEquals(listOf(0x00, 0x04, 0, 0, 0, 0), ans.subList(10, 16))
        assertEquals(qEnd + 16, a.size)
    }

    @Test
    fun buildSinkhole_rejectsShortOrBadPackets() {
        assertNull(Pure.buildSinkhole(ByteArray(10)))
        // 标签长度 > 63 是压缩指针/垃圾数据，不能当名字解析
        val bad = query("example.com")
        bad[12] = 0xC0.toByte()
        assertNull(Pure.buildSinkhole(bad))
    }
}
