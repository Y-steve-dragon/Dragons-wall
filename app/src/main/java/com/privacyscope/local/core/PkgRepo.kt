package com.privacyscope.local.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

/**
 * 应用清单 + 每个 App 的「危险权限 + 打包进来的 SDK」组合评分。
 *
 * 全部在本机完成：读 PackageManager 的元数据，再打开 APK 的 dex 扫类名前缀。
 * 不上传、不联网。
 */
object PkgRepo {

    data class AppEntry(
        val pkg: String,
        val label: String,
        val uid: Int,
        val system: Boolean,
        val versionName: String?,
        val apkPath: String,
        val apkSize: Long,
        val dangerousPerms: List<String>,
        val sdks: List<TrackerDb.Sdk>,
        val score: Int
    )

    /** 权限 → 人话（只收常见的，其余直接显示原名） */
    private val PERM_NAME = mapOf(
        "android.permission.READ_SMS" to "读短信",
        "android.permission.RECEIVE_SMS" to "收短信",
        "android.permission.SEND_SMS" to "发短信",
        "android.permission.READ_CONTACTS" to "读通讯录",
        "android.permission.READ_CALL_LOG" to "读通话记录",
        "android.permission.ACCESS_FINE_LOCATION" to "精确定位",
        "android.permission.ACCESS_COARSE_LOCATION" to "粗略定位",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "后台定位",
        "android.permission.CAMERA" to "相机",
        "android.permission.RECORD_AUDIO" to "录音",
        "android.permission.READ_PHONE_STATE" to "读电话状态",
        "android.permission.READ_PHONE_NUMBERS" to "读本机号码",
        "android.permission.BODY_SENSORS" to "身体传感器",
        "android.permission.READ_EXTERNAL_STORAGE" to "读外部存储",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "写外部存储",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "管理所有文件",
        "android.permission.GET_ACCOUNTS" to "读账户列表",
        "android.permission.QUERY_ALL_PACKAGES" to "读已安装应用",
        "android.permission.READ_MEDIA_IMAGES" to "读图片",
        "android.permission.READ_MEDIA_VIDEO" to "读视频",
        "android.permission.READ_MEDIA_AUDIO" to "读音频",
        "android.permission.SYSTEM_ALERT_WINDOW" to "悬浮窗",
        "android.permission.POST_NOTIFICATIONS" to "发通知",
        "android.permission.CALL_PHONE" to "直接拨号",
        "android.permission.PROCESS_OUTGOING_CALLS" to "监听去电",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "安装应用",
        "android.permission.PACKAGE_USAGE_STATS" to "看使用情况",
        "android.permission.WRITE_SETTINGS" to "改系统设置"
    )

    private val DANGEROUS = PERM_NAME.keys

    fun permName(p: String): String = PermZh.zh(p)

    /** 是不是「危险权限」：只认 PERM_NAME 里收的那批（能看你东西的） */
    fun isDangerous(p: String): Boolean = PERM_NAME.containsKey(p)

    /** 列出所有 App（含系统），并按风险分从高到低排序 */
    fun loadAll(ctx: Context, withSdkScan: Boolean = false): List<AppEntry> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        val list = ArrayList<AppEntry>()
        for (info in pm.getInstalledPackages(flags)) {
            val ai = info.applicationInfo ?: continue
            val requested = info.requestedPermissions?.toList() ?: emptyList()
            val danger = requested.filter { DANGEROUS.contains(it) }.distinct()
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val size = try {
                File(ai.sourceDir).length()
            } catch (t: Throwable) {
                0L
            }
            val sdks = if (withSdkScan) scanApkSdks(ai.sourceDir) else emptyList()
            list.add(
                AppEntry(
                    pkg = info.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                    uid = ai.uid,
                    system = isSystem,
                    versionName = info.versionName,
                    apkPath = ai.sourceDir,
                    apkSize = size,
                    dangerousPerms = danger,
                    sdks = sdks,
                    score = scoreOf(danger, sdks, requested)
                )
            )
        }
        return list.sortedWith(compareByDescending<AppEntry> { it.score }.thenBy { it.label })
    }

    /** 打开 APK，把 dex 里出现的 SDK 类名前缀找出来（这一步最慢，建议放后台） */
    fun scanApkSdks(apkPath: String): List<TrackerDb.Sdk> {
        val sb = StringBuilder()
        try {
            ZipFile(File(apkPath)).use { zip ->
                val names = zip.entries().toList()
                    .filter { e -> !e.isDirectory && e.name.matches(Regex("""classes\d*\.dex""")) }
                    .take(8)
                for (e in names) {
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    sb.append(extractAscii(bytes))
                    if (sb.length > 4_000_000) break
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return TrackerDb.hits(sb.toString())
    }

    /** dex 是二进制，只取其中可读的 ASCII 串（类名、域名都在这里） */
    private fun extractAscii(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size / 4)
        var start = -1
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            val printable = b in 0x20..0x7E
            if (printable) {
                if (start < 0) start = i
            } else {
                if (start >= 0 && i - start >= 8) {
                    sb.append(String(bytes, start, i - start, Charsets.US_ASCII)).append('\n')
                }
                start = -1
            }
        }
        return sb.toString()
    }

    private fun scoreOf(danger: List<String>, sdks: List<TrackerDb.Sdk>, requested: List<String>): Int {
        var s = 0
        s += (danger.size * 3).coerceAtMost(30)
        for (sdk in sdks) {
            s += when (sdk.category) {
                1 -> 6
                3 -> 4
                2 -> 2
                else -> 1
            }
        }
        if (requested.contains("android.permission.QUERY_ALL_PACKAGES")) s += 8
        if (requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE")) s += 10
        if (requested.contains("android.permission.READ_SMS") || requested.contains("android.permission.RECEIVE_SMS")) s += 8
        if (requested.contains("android.permission.SYSTEM_ALERT_WINDOW")) s += 4
        return s.coerceAtMost(100)
    }

    fun riskLabel(score: Int): String = when {
        score >= 60 -> "高"
        score >= 30 -> "中"
        score > 0 -> "低"
        else -> "干净"
    }
}
