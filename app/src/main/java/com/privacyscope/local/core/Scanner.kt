package com.privacyscope.local.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 清单与打分。
 *
 * 打分不是「安全评级」，而是「这个 App 有多想要你的数据」的一个排序键：
 * 危险权限越重、偷偷跑的入口越多、打包的采集类 SDK 越多，分越高。
 * 算法全部写在这里，可复核、可改。
 */
object Scanner {

    data class Item(
        val pkg: String,
        val label: String,
        val system: Boolean,
        val perms: List<String>,
        val receivers: Int,
        val services: Int,
        val score: Int,
        val tags: List<String>,
        val apkBytes: Long
    )

    /** 危险权限权重表（按「能拿到什么」排，不按官方分组） */
    private val PERM_W = linkedMapOf(
        "android.permission.READ_SMS" to 20,
        "android.permission.RECEIVE_SMS" to 16,
        "android.permission.READ_CALL_LOG" to 18,
        "android.permission.ACCESS_FINE_LOCATION" to 18,
        "android.permission.ACCESS_BACKGROUND_LOCATION" to 14,
        "android.permission.RECORD_AUDIO" to 16,
        "android.permission.CAMERA" to 14,
        "android.permission.READ_CONTACTS" to 14,
        "android.permission.READ_PHONE_STATE" to 10,
        "android.permission.READ_PHONE_NUMBERS" to 8,
        "android.permission.GET_ACCOUNTS" to 8,
        "android.permission.READ_CALENDAR" to 6,
        "android.permission.BODY_SENSORS" to 8,
        "android.permission.QUERY_ALL_PACKAGES" to 6,
        "android.permission.PACKAGE_USAGE_STATS" to 6,
        "android.permission.SYSTEM_ALERT_WINDOW" to 4,
        "android.permission.REQUEST_INSTALL_PACKAGES" to 6,
        "android.permission.ACCESS_WIFI_STATE" to 2,
        "android.permission.BLUETOOTH_SCAN" to 4
    )

    /** 采集类 SDK：统一用 TrackerDb（30+ 家，类名前缀 + 上报域名都有） */
    private val SDK_TAGS: Map<String, String> = TrackerDb.ALL
        .flatMap { s -> s.prefixes.map { p -> p to "${TrackerDb.categoryName(s.category)}:${s.name}" } }
        .toMap()
    private const val CACHE = "sdk_cache.json"

    fun scan(ctx: Context): List<Item> {
        val pm = ctx.packageManager
        val cache = readCache(ctx)
        val list = ArrayList<Item>()
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (ai in apps) {
            val pkg = ai.packageName
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            val perms = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions?.toList() ?: emptyList()
            }.getOrDefault(emptyList())

            val tags = cache.optJSONArray(pkg)?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            } ?: emptyList()

            val receivers = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_RECEIVERS).receivers?.size ?: 0
            }.getOrDefault(0)
            val services = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services?.size ?: 0
            }.getOrDefault(0)

            var score = 0
            for ((p, w) in PERM_W) if (perms.contains(p)) score += w
            score += minOf(receivers, 10)
            score += minOf(services, 8)
            for (t in tags) score += when { t.startsWith("统计") -> 6; t.startsWith("推送") -> 4; t.startsWith("风控") -> 5; else -> 3 }
            if (!system) {
                if (perms.contains("android.permission.RECEIVE_BOOT_COMPLETED")) score += 6
                if (perms.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) score += 3
            }
            // 封顶 100：不封顶的话系统壳（com.android.shell 那种 500+ 权限）会算出 197 这种没有信息量的数字
            score = score.coerceIn(0, 100)

            list.add(Item(pkg, label, system, perms, receivers, services, score, tags, ai.sourceDir?.let { File(it).length() } ?: 0L))
        }
        return list.sortedWith(compareByDescending<Item> { it.score }.thenBy { it.label })
    }

    /**
     * 扫 APK 内部的类路径，给每个 App 打「打包了哪些采集 SDK」的标签。
     * 只读 zip 目录，不执行、不联网、不上传。大工程里这一步会慢（IO 密集）。
     */
    fun scanSdk(ctx: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
        val out = JSONObject()
        var done = 0
        var tagged = 0
        for (ai in apps) {
            val apk = ai.sourceDir ?: continue
            val hits = LinkedHashSet<String>()
            try {
                ZipFile(apk).use { z ->
                    val names = z.entries()
                    while (names.hasMoreElements()) {
                        val n = names.nextElement().name
                        if (!n.endsWith(".dex") && !n.startsWith("classes")) continue
                        // 只靠 dex 里的字符串表太复杂，这里用「有没有这个路径前缀」的近似：
                        // 读 dex 的字符串常量池代价高，改为读条目名不可行；
                        // 因此实际判断放到 readDexStrings。
                        hits.addAll(readDexStrings(z, n))
                    }
                }
            } catch (e: Throwable) {
                Quiet.w("Scanner.读dex", e)
            }
            val matched = ArrayList<String>()
            if (hits.isNotEmpty()) {
                for (sdk in TrackerDb.hits(hits.joinToString("\n"))) {
                    matched.add("${TrackerDb.categoryName(sdk.category)}:${sdk.name}")
                }
            }
            if (matched.isNotEmpty()) {
                out.put(ai.packageName, org.json.JSONArray(matched))
                tagged++
            }
            done++
            onProgress(done, apps.size)
        }
        File(ctx.filesDir, CACHE).writeText(out.toString())
        return tagged
    }

    /** 从 dex 里粗读字符串模板（只认 MUTF-8 里的 ASCII/点斜杠路径） */
    private fun readDexStrings(z: ZipFile, entry: String): List<String> {
        val res = ArrayList<String>()
        try {
            val ins = z.getInputStream(z.getEntry(entry))
            val bytes = ins.readBytes()
            // 顺序扫：找连续的 [A-Za-z0-9_./$] 且长度 >= 8 的片段
            var i = 0
            val sb = StringBuilder()
            while (i < bytes.size) {
                val c = bytes[i].toInt() and 0xFF
                val ok = (c in 0x61..0x7A) || (c in 0x41..0x5A) || (c in 0x30..0x39) ||
                    c == 0x2F || c == 0x2E || c == 0x5F || c == 0x24
                if (ok) {
                    sb.append(c.toChar())
                    if (sb.length > 120) { sb.setLength(0) }
                } else {
                    if (sb.length >= 8) res.add(sb.toString())
                    sb.setLength(0)
                }
                i++
            }
            if (sb.length >= 8) res.add(sb.toString())
        } catch (e: Throwable) {
            Quiet.w("Scanner.readDexStrings", e)
        }
        return res
    }

    private fun readCache(ctx: Context): JSONObject = try {
        val f = File(ctx.filesDir, CACHE)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }
}
