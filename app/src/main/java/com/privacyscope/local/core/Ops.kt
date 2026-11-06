package com.privacyscope.local.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 改前先存、可回滚。
 *
 * 只碰 appops 里「允许/忽略/询问」这一层：它决定了 App 调某个能力时
 * 系统给不给、要不要弹窗。不 root、不动 dex、不卸载任何东西。
 */
object Ops {

    /**
     * 同一个「能力」在系统里可能叫不同名字（尤其 Android 13→16 改过几轮）。
     * appops 遇到不认识的名字会明确回 "Unknown operation string"，所以这里给候选名，
     * 让 AppOps.set 逐个试过去；全都不认识 = 这一项在这台系统上无法用 appops 关掉。
     */
    val ALIASES: Map<String, List<String>> = mapOf(
      "GET_INSTALLED_APPS" to listOf(
        "GET_INSTALLED_APPS", "READ_INSTALLED_APPS", "TARGETED_QUERY",
        "QUERY_ALL_PACKAGES", "GET_PACKAGE_INFO", "PACKAGE_USAGE_STATS"
      ),
      "RUN_IN_BACKGROUND" to listOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND"),
      "READ_CLIPBOARD" to listOf("READ_CLIPBOARD", "READ_CLIPBOARD_IN_BACKGROUND"),
      "BOOT_COMPLETED" to listOf("BOOT_COMPLETED", "RECEIVE_BOOT_COMPLETED"),
      "GET_USAGE_STATS" to listOf("GET_USAGE_STATS", "PACKAGE_USAGE_STATS")
    )


    /** appops 操作名 → 老实说的人话（界面上要能看懂） */
    val NAMES = linkedMapOf(
        "READ_DEVICE_IDENTIFIERS" to "读取设备标识（IMEI/OAID 类）",
        "GET_INSTALLED_APPS" to "读已安装应用列表",
        "RUN_IN_BACKGROUND" to "后台运行",
        "RUN_ANY_IN_BACKGROUND" to "任意后台运行",
        "GET_USAGE_STATS" to "看你的使用记录",
        "BOOT_COMPLETED" to "开机自启",
        "START_FOREGROUND" to "起前台服务常驻",
        "WAKE_LOCK" to "阻止休眠",
        "VIBRATE" to "震动",
        "CAMERA" to "相机",
        "RECORD_AUDIO" to "录音",
        "FINE_LOCATION" to "精确位置",
        "COARSE_LOCATION" to "大致位置",
        "READ_CONTACTS" to "读通讯录",
        "READ_SMS" to "读短信",
        "READ_CALL_LOG" to "读通话记录",
        "READ_CLIPBOARD" to "读剪贴板",
        "INSTALL_PACKAGES" to "装应用",
        "ACTIVATE_VPN" to "建 VPN"
    )

    /**
     * 实测在这台系统（Android 14+ / 16）上**改不动**的能力，界面上直接不列。
     *
     * 依据是用户真机日志（v0.9.x 的十二次尝试）：三种写法全试过
     *   · `GET_INSTALLED_APPS` → `Unknown operation string`（名字被删）
     *   · `BOOT_COMPLETED`     → `Unknown operation string`（名字被删）
     *   · `GET_USAGE_STATS`    → 退出码 0、无输出、回读不变（由 UsageStatsService 另管，appops 写它无效）
     * 把改不动的项留在界面里，用户只会反复点、反复看到"失败"，所以宁可不显示。
     * 旧版本系统（SDK < 34）上这些名字还在，仍然保留可用。
     */
    val RETIRED: Map<String, String> = mapOf(
        "GET_INSTALLED_APPS" to "Android 13 起并入权限组，appops 里没有这个名字了",
        "BOOT_COMPLETED" to "Android 14 起 appops 里没有这个名字了",
        "GET_USAGE_STATS" to "由「使用情况访问」单独管，必须在系统设置里授权，appops 写了无效"
    )

    /** 这台系统真正能改的能力清单（已剔除 RETIRED） */
    fun namesFor(sdk: Int): LinkedHashMap<String, String> {
        if (sdk < 34) return NAMES
        val m = LinkedHashMap<String, String>()
        for ((k, v) in NAMES) if (!RETIRED.containsKey(k)) m[k] = v
        return m
    }

    data class Preset(val id: String, val label: String, val ops: List<Pair<String, String>>, val hint: String)

    /**
     * 三档预设。全部是「忽略 = 调用时当作被拒」，不涉及隐藏/伪装。
     *
     * 只放**实测能改成功**的项：`GET_INSTALLED_APPS` / `BOOT_COMPLETED` / `GET_USAGE_STATS`
     * 在 Android 14+ 上改不动（见 [RETIRED]），放进来只会让批量结果多两条"没改成"。
     */
    val PRESETS = listOf(
        Preset(
            "soft", "轻度：只管设备标识",
            listOf("READ_DEVICE_IDENTIFIERS" to "ignore"),
            "最不容易出问题：只拦 IMEI/OAID 这类唯一标识，其余照旧。"
        ),
        Preset(
            "mid", "中度：标识 + 后台动作",
            listOf(
                "READ_DEVICE_IDENTIFIERS" to "ignore",
                "RUN_IN_BACKGROUND" to "ignore",
                "READ_CLIPBOARD" to "ignore"
            ),
            "日常推荐。可能影响：部分 App 消息推送变慢（后台被拦）。"
        ),
        Preset(
            "hard", "加强：再加熄屏与位置",
            listOf(
                "READ_DEVICE_IDENTIFIERS" to "ignore",
                "RUN_IN_BACKGROUND" to "ignore",
                "RUN_ANY_IN_BACKGROUND" to "ignore",
                "READ_CLIPBOARD" to "ignore",
                "WAKE_LOCK" to "ignore",
                "START_FOREGROUND" to "ignore",
                "FINE_LOCATION" to "ignore",
                "COARSE_LOCATION" to "ignore"
            ),
            "最严。可能影响：地图/外卖/打车不能用，聊天软件收不到推送。"
        )
    )

    private const val SNAP = "snapshot.json"

    /** 把一批 App 的当前 appops 存成快照，返回存了几个 */
    fun snapshot(ctx: Context, pkgs: Collection<String>): Int {
        val root = JSONObject()
        var n = 0
        for (p in pkgs) {
            val ops = AppOps.read(p)
            if (ops.isEmpty()) continue
            val o = JSONObject()
            for ((k, v) in ops) o.put(k, v)
            root.put(p, o)
            n++
        }
        val f = File(ctx.filesDir, SNAP)
        f.writeText(JSONObject().put("at", System.currentTimeMillis()).put("apps", root).toString())
        return n
    }

    /** 用最近一次快照恢复 */
    fun restore(ctx: Context): Pair<Int, Int> {
        val f = File(ctx.filesDir, SNAP)
        if (!f.exists()) return 0 to 0
        val root = runCatching { JSONObject(f.readText()).getJSONObject("apps") }.getOrNull() ?: return 0 to 0
        var ok = 0
        var fail = 0
        for (p in root.keys()) {
            val ops = root.getJSONObject(p)
            for (op in ops.keys()) {
                val mode = ops.optString(op)
                if (AppOps.set(p, op, mode)) ok++ else fail++
            }
        }
        return ok to fail
    }

    fun hasSnapshot(ctx: Context): Boolean = File(ctx.filesDir, SNAP).exists()

    /**
     * 一次批量改的结果。
     *
     * 为什么要分三类：用户看到「失败 3 项」会以为自己操作错了，而最常见的失败原因
     * 其实是**这台系统压根没有那个 appops 名字**（例如 Android 16 删了
     * GET_INSTALLED_APPS）。这种情况不该算「失败」，要单独说清楚。
     */
    data class PresetResult(
        val ok: Int,
        val fail: Int,
        /** 这台系统没有这个能力（名字已被移除），不算失败 */
        val unsupported: List<String>,
        /** 系统静默忽略：没报错、值没变（如 GET_USAGE_STATS，由系统服务另管），也不算失败 */
        val noEffect: List<String>,
        /** 真失败：写了但系统不认 / 没有通道。每条是「包名 · 能力 → 原文对话」 */
        val failures: List<String>
    )

    /** 应用某个预设到一批 App */
    fun applyPreset(pkgs: Collection<String>, preset: Preset): PresetResult {
        var ok = 0
        var fail = 0
        val unsupported = LinkedHashSet<String>()
        val noEffect = LinkedHashSet<String>()
        val failures = ArrayList<String>()
        for (p in pkgs) {
            for ((op, mode) in preset.ops) {
                if (AppOps.set(p, op, mode)) {
                    ok++
                    // 同一个名字在别的包上成功了，说明系统是认它的
                    unsupported.remove(op)
                    noEffect.remove(op)
                } else if (AppOps.lastUnsupported == op) {
                    unsupported.add(op)
                } else if (AppOps.lastNoEffect == op) {
                    noEffect.add(op)
                } else {
                    fail++
                    if (failures.size < 3) {
                        failures.add(
                            "$p · $op\n" + AppOps.lastTrace.lines()
                                .filter { it.isNotBlank() }
                                .joinToString("\n") { "    $it" }
                        )
                    }
                }
            }
        }
        return PresetResult(ok, fail, unsupported.toList(), noEffect.toList(), failures)
    }
}
