package com.privacyscope.local.core

/**
 * 唯一的「特权动作」出口。全部走 appops：只改「允许 / 忽略 / 询问」这一层。
 *
 * 通道优先级：无线调试（adb）→ root。
 * 没有通道时 Shell.exec 返回空串，调用方必须自己判断，不能假装成功。
 */
object AppOps {

    /**
     * 已读到的 op 值缓存（key = "包名|op"）。
     *
     * 为什么要有它：一次详情页要显示 19 个 op，每个 op 单独查一次就是 19 次 adb 往返。
     * 这些查询如果在主线程做，界面会卡；如果在子线程做，列表会先空白再跳。
     * 所以规则是：**主线程只读缓存**，缓存由 [prefetch] 在后台填好；
     * 改动成功后立刻更新缓存，界面马上就能对上。
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun key(pkg: String, op: String) = pkg + "|" + op

    private fun onMain(): Boolean = try {
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    } catch (_: Throwable) {
        false
    }

    fun cached(pkg: String, op: String): String? = cache[key(pkg, op)]

    /** 后台把某个包的 19 个 op 全读一遍，填进缓存。 */
    fun prefetch(pkg: String) {
        if (Shell.mode == Shell.MODE_NONE) return
        for (op in Ops.NAMES.keys) getModeRemote(pkg, op)
    }

    fun isPrefetched(pkg: String): Boolean =
        Ops.NAMES.keys.all { cache.containsKey(key(pkg, it)) }

    /** 最近一次改动尝试的完整对话。失败时原样弹给用户看，不让他只看到「失败 1 项」。 */
    @Volatile
    var lastTrace: String = ""
        private set

    /** 非空 = 上一个失败原因是「这台系统根本没有这个能力名」，不是命令被拒 */
    @Volatile
    var lastUnsupported: String = ""
        private set

    /**
     * 非空 = 命令没报错、但写完回读没变（系统静默忽略）。
     *
     * 典型例子：`GET_USAGE_STATS` —— `appops set` 退出码 0、什么都不打印，
     * 再查还是 default。这种不是"名字不存在"，也不是"命令被拒"，
     * 而是这个能力被系统服务另管（使用情况访问得在设置里授权），appops 写它无效。
     * 单列一档，免得用户以为是自己操作错了。
     */
    @Volatile
    var lastNoEffect: String = ""
        private set

    /** 某个 op 已经试通的真实名字（op → 实际可用的 appops 名） */
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 读一个包「非默认」的 appops：op 名 → allow/ignore/ask/dangerous...
     *
     * 注意：`appops get <pkg>` 只打印**被显式改过**的项。全是默认时它什么都不打印，
     * 所以返回空 Map 不代表「改不了」，只代表「没有一项被单独记过」。界面要把这一点说清楚。
     */
    fun read(pkg: String): Map<String, String> {
        val out = Shell.exec("appops get $pkg")
        if (out.isBlank()) return emptyMap()
        // 解析搬到 Pure（纯函数、可单测）：uid 级优先于包级，`;` 之后的审计时间丢掉。
        return Pure.mergeOps(Pure.parseOpLines(out))
    }

    /** 单独查一个 op 的当前值。系统里没记录 → "default"。
     *
     * 单独查的价值：`appops get <pkg>` 看不到默认项，而写完 `default` 之后该项会从列表里消失，
     * 所以核对「有没有真的改回默认」只能靠这个函数，否则会误报失败。
     */
    fun getMode(pkg: String, op: String): String {
        // 主线程不碰 adb：直接给已知值（没有就走 default）。界面因此不会卡。
        if (onMain()) return cache[key(pkg, op)] ?: "default"
        return getModeRemote(pkg, op)
    }

    private fun getModeRemote(pkg: String, op: String): String {
        val a = parseOne(op, Shell.exec("appops get $pkg $op"))
        if (a != null) {
            cache[key(pkg, op)] = a
            return a
        }
        val b = parseOne(op, Shell.exec("cmd appops get $pkg $op"))
        if (b != null) {
            cache[key(pkg, op)] = b
            return b
        }
        cache[key(pkg, op)] = "default"
        return "default"
    }

    /** 解析 `OP: value[; time=...]` 这种行；没有该 op 的行返回 null（区别于「读到了 default」）。
     *
     * 关键：现代 Android 上 `appops set <包> <op> <mode>` 写的是 **uid 级**，输出是两行：
     * ```
     * Uid mode: COARSE_LOCATION: ignore     ← 我们写进去的
     * COARSE_LOCATION: allow                ← 包级默认值，一直都会是 allow
     * ```
     * 老代码只看第二行，于是永远判「写入了但回读不一致」。必须优先认 `Uid mode:` 那一行。
     * 具体解析在 [Pure.pickMode]，有单测。
     */
    private fun parseOne(op: String, out: String): String? = Pure.pickMode(op, out)

    /**
     * 设一个 op。写完立刻回读核对——不核对就报成功是自欺欺人。
     * mode: allow / ignore / default / ask / deny
     */
    fun set(pkg: String, op: String, mode: String): Boolean {
        if (Shell.mode == Shell.MODE_NONE) {
            lastTrace = "没有可用通道（Shell.mode=none）。先到「墙」页把无线调试连上。"
            return false
        }
        lastUnsupported = ""
        lastNoEffect = ""
        // 名字先定：上次试通的名字优先，其次是原名，最后是别名表里的候选。
        // Android 16 上 GET_INSTALLED_APPS 已经不在名单里，会回 "Unknown operation string"，
        // 所以「名字不对」「命令被拒」「系统静默忽略」三种必须分开，否则用户只会看到一句看不懂的英文。
        val names = ArrayList<String>()
        resolved[op]?.let { names.add(it) }
        if (!names.contains(op)) names.add(op)
        Ops.ALIASES[op]?.forEach { if (!names.contains(it)) names.add(it) }

        val trace = StringBuilder()
        trace.append("通道：").append(Shell.mode).append('\n')
        var allUnknown = true
        var anySpoke = false
        for (name in names) {
            val forms = listOf(
                "appops set $pkg $name $mode",
                "cmd appops set $pkg $name $mode",
                "appops set --uid $pkg $name $mode"
            )
            for (cmd in forms) {
                val out = Shell.exec(cmd)
                val unknown = out.contains("Unknown operation string") || out.contains("Unknown operation")
                trace.append("── 试：").append(cmd).append('\n')
                trace.append("   输出：").append(if (out.isBlank()) "（空）" else out.trim()).append('\n')
                if (unknown) {
                    trace.append("   （这台系统不认 ").append(name).append(" 这个名字）\n")
                    continue
                }
                allUnknown = false
                if (out.isNotBlank()) anySpoke = true
                val now = getModeRemote(pkg, name)
                cache[key(pkg, op)] = now
                trace.append("   回读：").append(now).append('\n')
                trace.append("   Shell.lastError：").append(Shell.lastError ?: "（无）").append('\n')
                if (Pure.same(now, mode)) {
                    resolved[op] = name
                    cache[key(pkg, op)] = mode
                    trace.append("结果：成功（用 $name）\n")
                    trace.append("Adb.lastRaw：\n").append(Adb.lastRaw.ifBlank { "（无）" })
                    lastTrace = trace.toString()
                    return true
                }
            }
        }
        // 三档归因交给 Pure（纯函数、有单测）：名字不存在 / 静默忽略 / 真被拒。
        when (Pure.verdict("", "x", mode, allUnknown, anySpoke)) {
            Pure.Verdict.UNSUPPORTED -> {
                lastUnsupported = op
                trace.append("结果：这台系统的 appops 里没有这个能力（名字已变或被移除）\n")
            }
            Pure.Verdict.NO_EFFECT -> {
                // 三种写法都退出码 0、一个字都不打印、回读没变 —— 系统静默忽略。
                // GET_USAGE_STATS 就是这一类：它由 UsageStatsService 另管，appops 写它无效。
                lastNoEffect = op
                trace.append("结果：系统静默忽略了这个写入（没报错，但值没变）。\n")
                trace.append("       这类能力一般在系统设置里有独立开关，appops 改不动它。\n")
            }
            else -> trace.append("结果：写入了但回读不一致（命令被系统忽略）\n")
        }
        trace.append("Adb.lastError：").append(Adb.lastError.ifBlank { "（无）" }).append('\n')
        trace.append("Adb.lastRaw：\n").append(Adb.lastRaw.ifBlank { "（无）" })
        lastTrace = trace.toString()
        return false
    }

    fun reset(pkg: String, op: String): Boolean = set(pkg, op, "default")

    /** 撤销一项运行时权限（pm revoke）。返回空串=成功，否则是失败原因。 */
    fun revokePermission(pkg: String, perm: String): String {
        if (Shell.mode == Shell.MODE_NONE) return "没有可用通道（root / 无线调试）"
        val out = Shell.exec("pm revoke $pkg $perm")
        val err = (Shell.lastError ?: "").trim()
        val bad = listOf("Error", "Exception", "Security", "not granted", "Unknown", "Failure").firstOrNull {
            out.contains(it, ignoreCase = true) || err.contains(it, ignoreCase = true)
        }
        return when {
            bad == null -> ""
            out.isNotBlank() -> out.trim()
            else -> err.ifBlank { "系统拒绝了这次撤销" }
        }
    }
}
