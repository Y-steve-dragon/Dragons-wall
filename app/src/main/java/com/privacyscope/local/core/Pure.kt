package com.privacyscope.local.core

/**
 * 纯逻辑：不碰 Android API、不跑线程、不发命令。
 *
 * 为什么单独抽出来：这些东西全是「字符串进、布尔/字节出」的判断，
 * 是整台机器上最容易出错、也最容易被改坏的部分（`Uid mode:` 解析、命令白名单、
 * 域名匹配、DNS 应答报文），但它们原来散在 `AppOps` / `Adb` / `Blocker` / `CaptureVpnService`
 * 里，全部依赖 Context 与 shell，**一行都测不了**。
 *
 * 现在它们在这里，`app/src/test` 下的单测可以直接调，不需要手机、不需要 adb。
 */
object Pure {

    // ────────────────────────── 一、appops 输出解析 ──────────────────────────

    /**
     * 一行 appops 输出。
     *
     * @param op   能力名，例如 `COARSE_LOCATION`
     * @param mode 模式，例如 `ignore` / `allow` / `default`
     * @param uidLevel true = 这行带 `Uid mode:` 前缀（真正写进去的值）
     */
    data class OpLine(val op: String, val mode: String, val uidLevel: Boolean)

    /**
     * 解析 `appops get <包名>` / `appops get <包名> <op>` 的整段输出。
     *
     * 真实输出长这样（现代 Android 上一条 set 会同时出现两行）：
     * ```
     * Uid mode: COARSE_LOCATION: ignore
     * COARSE_LOCATION: allow
     * READ_CLIPBOARD: ignore; time=+1d2h3m4s5ms ago
     * ```
     * 规则：
     * - `;` 之后是审计时间戳，丢掉；
     * - 值里带空格的（`allow; time=...`）只取第一段；
     * - 不是 `KEY: VALUE` 形状的行直接忽略，绝不猜。
     */
    fun parseOpLines(out: String): List<OpLine> {
        val list = ArrayList<OpLine>()
        for (raw in out.split("\n")) {
            var s = raw.trim()
            if (s.isEmpty()) continue
            var uid = false
            if (s.startsWith("Uid mode:")) {
                uid = true
                s = s.removePrefix("Uid mode:").trim()
            }
            val i = s.indexOf(':')
            if (i <= 0) continue
            val k = s.substring(0, i).trim()
            if (k.isEmpty() || k.contains(' ')) continue
            var v = s.substring(i + 1).trim()
            val semi = v.indexOf(';')
            if (semi >= 0) v = v.substring(0, semi).trim()
            if (v.isEmpty()) continue
            list.add(OpLine(k, v, uid))
        }
        return list
    }

    /**
     * 合并成 `op → mode`，**uid 级优先于包级**。
     *
     * 这是 v0.9.9 修掉的那个真 bug：`appops set` 写 uid 级，包级那行一直是 `allow`。
     * 老代码按出现顺序后写覆盖前写 → 界面永远显示 allow → 判「写了但回读不一致」。
     */
    fun mergeOps(lines: List<OpLine>): Map<String, String> {
        val uid = LinkedHashMap<String, String>()
        val pkg = LinkedHashMap<String, String>()
        for (l in lines) {
            if (l.uidLevel) uid[l.op] = l.mode else pkg[l.op] = l.mode
        }
        val out = LinkedHashMap<String, String>()
        out.putAll(uid)
        for ((k, v) in pkg) if (!out.containsKey(k)) out[k] = v
        return out
    }

    /** 从单查输出里取某个 op 的值；没有这一项返回 null（区别于「读到了 default」）。 */
    fun pickMode(op: String, out: String): String? {
        var hit: String? = null
        for (l in parseOpLines(out)) {
            if (!l.op.equals(op, ignoreCase = true)) continue
            if (l.uidLevel) return l.mode
            if (hit == null) hit = l.mode
        }
        return hit
    }

    // ────────────────────────── 二、写入结果判决 ──────────────────────────

    enum class Verdict {
        /** 回读等于目标值，或系统等价（allow→deny 视为已关） */
        OK,
        /** 三种写法都回 `Unknown operation string`：这台系统没有这个名字 */
        UNSUPPORTED,
        /** 命令没报错、一个字没说、回读也没变：系统静默忽略（如 GET_USAGE_STATS） */
        NO_EFFECT,
        /** 说了一堆但值没变：真的被拒 */
        IGNORED
    }

    fun verdict(before: String, after: String, target: String, sawUnknown: Boolean, spoke: Boolean): Verdict {
        if (same(after, target)) return Verdict.OK
        if (sawUnknown) return Verdict.UNSUPPORTED
        if (!spoke) return Verdict.NO_EFFECT
        return Verdict.IGNORED
    }

    /** 目标值算不算「已经生效」。default 与 default 也相等；deny 与 ignore 都算已关。 */
    fun same(now: String, target: String): Boolean =
        now == target || (target == "deny" && now == "ignore")

    /** 「已关闭」：ignore / deny 都算关，其余（allow / default / ask）算开。 */
    fun isOff(mode: String): Boolean = mode == "ignore" || mode == "deny"

    // ────────────────────────── 三、adb 命令白名单 ──────────────────────────

    private val SHAPES = listOf(
        Regex("^appops get [A-Za-z0-9_.]+( [A-Za-z0-9_]+)?$"),
        Regex("^appops set [A-Za-z0-9_.]+ [A-Za-z0-9_]+ (allow|ignore|default|deny)$"),
        Regex("^appops set --uid [A-Za-z0-9_.]+ [A-Za-z0-9_]+ (allow|ignore|default|deny)$"),
        Regex("^appops reset [A-Za-z0-9_.]+$"),
        Regex("^cmd appops get [A-Za-z0-9_.]+( [A-Za-z0-9_]+)?$"),
        Regex("^cmd appops set [A-Za-z0-9_.]+ [A-Za-z0-9_]+ (allow|ignore|default|deny)$"),
        Regex("^pm revoke [A-Za-z0-9_.]+ [A-Za-z0-9_.]+$"),
        Regex("^pm grant [A-Za-z0-9_.]+ [A-Za-z0-9_.]+$"),
        Regex("^pm list packages$"),
        Regex("^cmd package list packages$"),
        Regex("^id$"),
        Regex("^getprop ro\\.build\\.version\\.sdk$")
    )

    /**
     * 返回 null = 放行；否则是拒绝原因。
     *
     * 两段式：先挡连接符（`;` `|` `&` `` ` `` `$` 换行）——这是防命令注入的第一道门；
     * 再说「形状不对」。顺序不能反：`appops get a; rm -rf /` 撞不上形状表，
     * 但一定要报「不允许连接符」，而不是含糊地说「不在白名单」。
     */
    fun checkCommand(cmd: String): String? {
        val s = cmd.trim()
        if (s.isEmpty()) return "空命令"
        for (bad in listOf(';', '|', '&', '`', '$', '\n', '\r')) {
            if (s.indexOf(bad) >= 0) return "命令里不允许出现连接符（; | & ` $ 换行）"
        }
        for (re in SHAPES) if (re.matches(s)) return null
        return "这条命令不在白名单里：" + s.take(60)
    }

    // ────────────────────────── 四、域名匹配 ──────────────────────────

    /**
     * 域名列表匹配：`host` 等于规则，或是规则的子域。
     *
     * 为什么不直接 `contains`：`evil-tracking.cn` 里含 `tracking.cn`，
     * 用 contains 会误杀；只认 `h == rule` 或 `h.endsWith(".$rule")` 才对。
     */
    fun matchesDomain(host: String, rule: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        val r = rule.lowercase().trim().trimStart('.').trimEnd('.')
        if (h.isEmpty() || r.isEmpty()) return false
        return h == r || h.endsWith(".$r")
    }

    /** 返回第一条命中的规则（null = 没命中）。顺序重要：调用方按优先级给列表。 */
    fun firstDomainHit(host: String, rules: Collection<String>): String? =
        rules.firstOrNull { matchesDomain(host, it) }

    // ────────────────────────── 五、DNS 报文 ──────────────────────────

    /** 从查询报文里取 QNAME；不是查询、或名字不合法 → null。 */
    fun qname(dns: ByteArray): String? {
        if (dns.size < 13) return null
        if ((dns[2].toInt() and 0x80) != 0) return null // QR=1 → 这是应答，不是查询
        var i = 12
        val sb = StringBuilder()
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) break
            if (len > 63 || i + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until len) {
                val c = dns[i + 1 + k].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '?')
            }
            i += 1 + len
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * 把一条 DNS 查询改造成「应答 0.0.0.0」的报文，用来沉洞。
     *
     * 只回一条 A 记录（RDLENGTH=4）：
     * 客户端拿到 0.0.0.0 之后连不上任何服务 —— 这比「不回包」更干脆，
     * 后者会让客户端等 5 秒超时再重试三次，反而更慢、更显眼。
     */
    fun buildSinkhole(query: ByteArray): ByteArray? {
        if (query.size < 17) return null
        var i = 12
        while (i < query.size) {
            val len = query[i].toInt() and 0xFF
            if (len == 0) {
                i += 1
                break
            }
            if (len > 63 || i + 1 + len > query.size) return null
            i += 1 + len
        }
        if (i + 4 > query.size) return null
        val qEnd = i + 4
        val out = ByteArray(qEnd + 16)
        System.arraycopy(query, 0, out, 0, qEnd)
        out[2] = ((query[2].toInt() or 0x80) and 0xFF).toByte()  // QR = 1
        out[3] = ((query[3].toInt() and 0x70) or 0x80).toByte()  // RA = 1, RCODE = 0
        out[6] = 0; out[7] = 1                                   // ANCOUNT = 1
        out[8] = 0; out[9] = 0                                   // NSCOUNT
        out[10] = 0; out[11] = 0                                 // ARCOUNT
        val ans = byteArrayOf(
            0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C, 0x00, 0x04,
            0, 0, 0, 0
        )
        System.arraycopy(ans, 0, out, qEnd, ans.size)
        return out
    }
}
