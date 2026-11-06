package com.privacyscope.local.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.webkit.WebView
import java.io.File
import java.util.Locale

/**
 * 一键风险检测（**只读**，不做任何修改）。
 *
 * 为什么叫「检测」而不是「修复」：这里面大多数维度，第三方 App 根本没有权限去改
 * （比如系统补丁、厂商账号）。我们的职责是**把它看不出来的东西告诉你**，
 * 能查的就给结论，查不到的**明确说查不到**，并说清为什么 + 你自己怎么核实。
 *
 * 每一维度四项：结论（level）/ 看到了什么（finding）/ 凭什么这么说（basis）/ 建议（advice）。
 */
object RiskScan {

    enum class Lv { RISK, WARN, NOTE, OK, UNKNOWN }

    data class Item(
        val dim: String,
        val level: Lv,
        val finding: String,
        val basis: String,
        val advice: String
    )

    fun run(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val pm = ctx.packageManager

        // ── 1. 系统安全补丁 ─────────────────────────────────────
        val patch = if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else ""
        val patchDays = patchAgeDays(patch)
        out.add(
            when {
                patch.isEmpty() -> Item(
                    "系统补丁", Lv.UNKNOWN, "读不到补丁日期",
                    "这个字段由厂商写入，有的 ROM 会留空",
                    "设置 → 关于手机 → Android 版本，看「安全补丁级别」"
                )
                patchDays > 365 -> Item(
                    "系统补丁", Lv.RISK,
                    "补丁停在 $patch（约 $patchDays 天前）",
                    "补丁超过一年没更新，已公开的提权漏洞大概率没修 —— 而提权是「跨空间感染」的唯一技术通路",
                    "设置里检查系统更新；厂商停更的机型，别在里面登重要账号"
                )
                patchDays > 180 -> Item(
                    "系统补丁", Lv.WARN,
                    "补丁停在 $patch（约 $patchDays 天前）",
                    "半年以上没打补丁，属于「已知漏洞未修」状态",
                    "去系统更新里查一次"
                )
                else -> Item(
                    "系统补丁", Lv.OK, "补丁 $patch（约 $patchDays 天前）",
                    "半年内更新过", "保持自动更新即可"
                )
            }
        )

        // ── 2. 系统身份（是否自签/测试版） ───────────────────────
        val tags = Build.TAGS ?: ""
        out.add(
            if (tags.contains("test-keys")) Item(
                "系统版本类型", Lv.WARN, "系统是测试签名（test-keys）",
                "零售机应为 release-keys；测试签名常见于工程机、第三方 ROM",
                "确认来源；非官方系统无法保证补丁质量"
            ) else Item(
                "系统版本类型", Lv.OK, "正式签名（release-keys）", "Build.TAGS = $tags", "无需处理"
            )
        )

        // ── 3. Root 迹象 ───────────────────────────────────────
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/su")
        val suFound = suPaths.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        val magisk = runCatching { File("/data/adb/magisk").exists() }.getOrDefault(false)
        out.add(
            if (suFound.isNotEmpty() || magisk) Item(
                "Root", Lv.RISK,
                "有 Root 痕迹：" + (suFound.joinToString(" ") + if (magisk) " /data/adb/magisk" else ""),
                "Root 等于给所有 App 一条「提权后门」，任何空间的隔离都失效",
                "不需要就彻底解除 Root；需要就只装可信模块，别在里面放银行类 App"
            ) else Item(
                "Root", Lv.OK, "没查到常见 Root 痕迹",
                "检查了常见 su 路径与 magisk 目录（隐藏得很深的仍然查不到）",
                "无需处理"
            )
        )

        // ── 4. 独立空间（分身 / 工作资料 / 多用户） ─────────────
        val users = runCatching {
            val um = ctx.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            um.userProfiles
        }.getOrNull()
        val nUsers = users?.size ?: -1
        out.add(
            when {
                nUsers < 0 -> Item(
                    "独立空间", Lv.UNKNOWN, "读不到用户/空间数量",
                    "部分 ROM 对第三方隐藏 getUsers 结果",
                    "设置里看有没有「隐私空间 / 手机分身 / 工作资料」"
                )
                nUsers > 1 -> Item(
                    "独立空间", Lv.NOTE, "存在 $nUsers 个用户/空间（可能是分身或工作资料）",
                    "多用户之间的数据目录与 uid 是分开的，普通恶意软件读不到对方；但内核、系统分区、共享公开目录、同一云账号是通的",
                    "别让两个空间登录同一个云账号；共享目录别留来历不明的 apk/exe"
                )
                else -> Item(
                    "独立空间", Lv.OK, "只有 1 个用户空间",
                    "系统报告只有主用户", "无需处理"
                )
            }
        )

        // ── 5. 云同步 / 账号（查不到，但要说） ───────────────────
        out.add(
            Item(
                "云同步与账号", Lv.UNKNOWN,
                "无法检测（这一项第三方 App 读不到）",
                "厂商账号的同步开关属于系统私有数据，安卓没有对第三方开放接口；但它恰好是最容易被忽略的「跨隔离通道」",
                "自己去：设置 → 账号/云空间，确认两个空间是否登录了同一个账号、" +
                    "相册/联系人/便签同步是否都开着；能关的关掉"
            )
        )

        // ── 6. 共享公开存储里有没有可执行文件 ───────────────────
        val riskyExt = listOf(".apk", ".exe", ".zip", ".rar", ".apks", ".xapk", ".dex")
        val hits = ArrayList<String>()
        for (dir in publicDirs()) {
            runCatching {
                val f = File(dir)
                if (!f.isDirectory) return@runCatching
                f.listFiles()?.take(400)?.forEach { c ->
                    if (c.isFile && riskyExt.any { c.name.lowercase(Locale.US).endsWith(it) }) {
                        hits.add(c.name)
                    }
                }
            }
        }
        out.add(
            when {
                hits.isEmpty() -> Item(
                    "共享存储", Lv.OK, "公开目录里没看到安装包/压缩包",
                    "查了「下载/文档/图片」等公共目录（只列顶层 400 个文件，深层目录没扫全）",
                    "无需处理"
                )
                else -> Item(
                    "共享存储", Lv.WARN,
                    "公开目录里有 ${hits.size} 个安装包/压缩包：" + hits.take(5).joinToString("、"),
                    "公共目录可能被另一个空间（或别的 App）读到 —— 恶意文件常靠这里跨空间落地",
                    "确认这些文件来源，不需要就移走；apk 只从官方商店装"
                )
            }
        )

        // ── 7. 开发者选项 / USB 调试 / 无线调试 ─────────────────
        val devOn = globalInt(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        val adbOn = globalInt(ctx, Settings.Global.ADB_ENABLED) == 1
        val wifiAdb = runCatching { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 }
            .getOrDefault(false)
        out.add(
            when {
                adbOn && wifiAdb -> Item(
                    "调试通道", Lv.RISK,
                    "USB 调试与无线调试都开着",
                    "开着 adb 等于把「shell 权限」挂在一个端口上，任何人拿到配对密钥就能完整控制手机",
                    "不用时关掉；无线调试尤其要关（它还能被局域网内尝试连接）"
                )
                adbOn -> Item(
                    "调试通道", Lv.WARN, "USB 调试开着",
                    "数据线一插，电脑就能拿到 shell；授权过的电脑会长期有效",
                    "不用时关掉；设置里可以「撤销 USB 调试授权」"
                )
                devOn -> Item(
                    "调试通道", Lv.NOTE, "开发者选项开着，但没开 adb",
                    "本身不影响安全，但容易被引导去开启 adb", "不需要就关掉开发者选项"
                )
                else -> Item("调试通道", Lv.OK, "调试通道（开发者选项/USB 调试）都关着", "系统设置里读到的状态", "无需处理")
            }
        )

        // ── 8. 无障碍服务（最常被滥用的权限） ────────────────────
        val a11y = secureString(ctx, "enabled_accessibility_services")
        val a11yPkgs = splitPkgs(a11y)
        val a11yThird = a11yPkgs.filter { !isSystem(pm, it) }
        out.add(
            when {
                a11yPkgs.isEmpty() -> Item("无障碍服务", Lv.OK, "没有启用任何无障碍服务", "读自系统设置", "无需处理")
                a11yThird.isNotEmpty() -> Item(
                    "无障碍服务", Lv.RISK,
                    "有 ${a11yThird.size} 个第三方 App 开了无障碍：" + a11yThird.joinToString("、"),
                    "无障碍能读到屏幕上的一切、能替你点按钮 —— 木马最爱的入口",
                    "逐个确认是不是自己主动开的；不认识的一律关掉"
                )
                else -> Item(
                    "无障碍服务", Lv.NOTE,
                    "只开了系统自带的无障碍：" + a11yPkgs.joinToString("、"),
                    "系统组件一般可信", "无需处理"
                )
            }
        )

        // ── 9. 通知读取权 ──────────────────────────────────────
        val listeners = splitPkgs(secureString(ctx, "enabled_notification_listeners"))
        out.add(
            if (listeners.isEmpty()) Item("通知读取权", Lv.OK, "没有 App 在读通知", "读自系统设置", "无需处理")
            else Item(
                "通知读取权", Lv.WARN,
                "${listeners.size} 个 App 能读全部通知：" + listeners.joinToString("、"),
                "通知里常有验证码、聊天内容、银行推送",
                "只留必要的（比如手环 App），其余关掉"
            )
        )

        // ── 10. 设备管理员 ─────────────────────────────────────
        val admins = runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.activeAdmins?.map { it.packageName } ?: emptyList()
        }.getOrDefault(emptyList())
        out.add(
            if (admins.isEmpty()) Item("设备管理权", Lv.OK, "没有 App 持有设备管理权", "读自系统", "无需处理")
            else Item(
                "设备管理权", Lv.WARN,
                "${admins.size} 个 App 有设备管理权：" + admins.joinToString("、"),
                "设备管理员能锁屏、改密码、擦除数据、禁止卸载",
                "确认来源；卸载前必须先取消设备管理权"
            )
        )

        // ── 11. 默认输入法 ─────────────────────────────────────
        val ime = secureString(ctx, "default_input_method") ?: ""
        val imePkg = ime.substringBefore('/')
        out.add(
            when {
                imePkg.isEmpty() -> Item("输入法", Lv.UNKNOWN, "读不到默认输入法", "系统设置里没有返回", "设置 → 系统 → 语言与输入法 自己看")
                isSystem(pm, imePkg) -> Item("输入法", Lv.OK, "默认输入法是系统自带（$imePkg）", "系统签名", "无需处理")
                else -> Item(
                    "输入法", Lv.NOTE, "默认输入法是第三方（$imePkg）",
                    "输入法能看到你打的每一个字，包括密码框以外的一切",
                    "选可信厂商的输入法，关掉它的「云同步/联想上报」"
                )
            }
        )

        // ── 12. 屏幕锁 ─────────────────────────────────────────
        val secure = runCatching {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.isDeviceSecure
        }.getOrDefault(false)
        out.add(
            if (secure) Item("屏幕锁", Lv.OK, "已设置锁屏密码/指纹", "系统报告设备是安全的", "无需处理")
            else Item(
                "屏幕锁", Lv.RISK, "没有设置任何锁屏",
                "没有锁屏 = 拿到手机的人可以直接用你的所有 App，也能进开发者选项",
                "设置一个密码或指纹"
            )
        )

        // ── 13. 存储加密 ───────────────────────────────────────
        val enc = runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.storageEncryptionStatus
        }.getOrDefault(-1)
        out.add(
            Item(
                "存储加密", if (enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
                    enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY ||
                    enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER) Lv.OK else Lv.UNKNOWN,
                when (enc) {
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> "已加密"
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "已加密（使用默认密钥）"
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "已加密（每用户密钥）"
                    DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "未加密"
                    else -> "读不到加密状态（$enc）"
                },
                "Android 7 起默认全盘/文件级加密；读不到时可能是 ROM 隐藏了接口",
                "无需手动处理，但换机/维修前记得备份"
            )
        )

        // ── 14. 可调试的 App ──────────────────────────────────
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val debuggable = apps.filter { (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
        out.add(
            if (debuggable.isEmpty()) Item("可调试应用", Lv.OK, "没装可调试（debuggable）的 App", "查了全部已安装应用", "无需处理")
            else Item(
                "可调试应用", Lv.WARN,
                "${debuggable.size} 个 App 是 debug 包：" + debuggable.take(5).map { it.packageName }.joinToString("、"),
                "debug 包能被 adb 挂调试器、读内部数据，也可能自带测试后门",
                "开发用的包用完就卸；日常别装来源不明的 debug 包"
            )
        )

        // ── 15. 危险权限授予情况 ───────────────────────────────
        var riskApps = 0
        var riskPermGrants = 0
        for (ai in apps) {
            runCatching {
                val pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS)
                val rp = pi.requestedPermissions ?: return@runCatching
                val rf = pi.requestedPermissionsFlags ?: return@runCatching
                var n = 0
                for (i in rp.indices) {
                    if (i < rf.size && (rf[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        if (PkgRepo.isDangerous(rp[i])) n++
                    }
                }
                if (n > 0) { riskApps++; riskPermGrants += n }
            }
        }
        out.add(
            Item(
                "危险权限授予", if (riskPermGrants > 120) Lv.WARN else Lv.NOTE,
                "$riskApps 个 App 被授予了共 $riskPermGrants 项危险权限（定位、相机、通讯录、短信等）",
                "统计口径：本机识别的那批危险权限里，当前处于「已授予」状态的项数",
                "去清单页按风险分排序，逐个看谁要得太多；能改成「仅使用时允许」的都改"
            )
        )

        // ── 16. 电池优化豁免 ──────────────────────────────────
        val pmPower = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = apps.count { runCatching { pmPower.isIgnoringBatteryOptimizations(it.packageName) }.getOrDefault(false) }
        out.add(
            Item(
                "后台常驻豁免", if (exempt > 20) Lv.WARN else Lv.NOTE,
                "$exempt 个 App 被允许「不受电池优化限制」",
                "这类 App 能在后台一直跑：既可能偷偷上传，也是本软件抓流量的前提",
                "别给不认识的 App 开这种豁免"
            )
        )

        // ── 17. 公共 Wi-Fi / 代理 ──────────────────────────────
        val proxy = runCatching {
            Settings.Global.getString(ctx.contentResolver, "http_proxy")
        }.getOrNull()
        out.add(
            if (stringIsEmpty(proxy)) Item("代理设置", Lv.OK, "没有配置全局 HTTP 代理", "读自系统设置", "无需处理")
            else Item(
                "代理设置", Lv.WARN, "配置了全局代理：$proxy",
                "全局代理会把流量交给指定服务器，等于把中间人放在半路",
                "确认是不是你自己配的；公共 Wi-Fi 的「描述文件/代理」尤其要小心"
            )
        )

        // ── 18. 外接设备 ───────────────────────────────────────
        val usb = runCatching {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            um.deviceList.size
        }.getOrDefault(0)
        out.add(
            if (usb > 0) Item(
                "外接设备", Lv.NOTE, "当前插着 $usb 个 USB 设备",
                "廉价的充电线/公共充电口可以做数据连接；有的还能注入按键",
                "公共场合用「只充电」线或充电宝"
            ) else Item("外接设备", Lv.OK, "没有外接 USB 设备", "UsbManager 报告为空", "无需处理")
        )

        // ── 19. WebView 版本 ──────────────────────────────────
        out.add(
            runCatching {
                val wv = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
                val vn = wv?.versionName ?: ""
                if (vn.isEmpty()) Item("内置浏览器内核", Lv.UNKNOWN, "读不到 WebView 版本", "旧系统不提供接口", "设置 → 应用 → Android System WebView 自己看版本")
                else Item(
                    "内置浏览器内核", Lv.NOTE, "WebView 版本 $vn",
                    "网页内容的渲染全靠它，内核旧 = 网页挂马的门开着",
                    "在应用商店更新「Android System WebView」"
                )
            }.getOrElse { Item("内置浏览器内核", Lv.UNKNOWN, "读取失败", it.toString(), "去应用商店更新 WebView") }
        )

        // ── 20. VPN / 流量中间人（查不到别人的，但要说） ────────
        out.add(
            Item(
                "代理类 App", Lv.UNKNOWN,
                "无法检测手机上是否装了别的 VPN/抓包工具（只能看见自己这条隧道）",
                "安卓不提供「列出所有正在运行的 VPN」的接口，这是平台限制",
                "自己去应用列表看有没有 Clash、抓包、\"加速器\"；本软件的墙页会显示自己这条隧道的状态，可作对照"
            )
        )

        // ── 21. 已装应用的来源（免安装/侧载） ──────────────────
        val side = runCatching {
            val installer = pm.getInstallerPackageName(apps.firstOrNull()?.packageName ?: "")
            installer
        }.getOrNull()
        out.add(
            Item(
                "应用来源", Lv.UNKNOWN,
                "无法逐个 App 判断是不是侧载（商店装的）",
                "安卓 11+ 收紧了来源查询接口；只能看到自己/系统应用（示例：${side ?: "null"}）",
                "回想最近是不是从浏览器/聊天里点过 apk；去应用列表按安装时间排一下"
            )
        )

        return out
    }

    // ── 小工具 ──────────────────────────────────────────────────

    private fun globalInt(ctx: Context, key: String): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, key, 0) }.getOrDefault(0)

    private fun secureString(ctx: Context, key: String): String? =
        runCatching { Settings.Secure.getString(ctx.contentResolver, key) }.getOrNull()

    private fun stringIsEmpty(s: String?): Boolean = s == null || s.trim().isEmpty() || s == "null" || s == ":0"

    private fun splitPkgs(s: String?): List<String> =
        if (s.isNullOrBlank()) emptyList()
        else s.split(':', ';').mapNotNull { seg ->
            val p = seg.substringBefore('/').trim()
            if (p.isEmpty() || !p.contains('.')) null else p
        }.distinct()

    private fun isSystem(pm: PackageManager, pkg: String): Boolean = runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    private fun publicDirs(): List<String> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath,
        Environment.getExternalStorageDirectory().absolutePath
    )

    private fun patchAgeDays(patch: String): Long {
        if (patch.isEmpty()) return -1
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.isLenient = false
            val d = fmt.parse(patch) ?: return -1
            (System.currentTimeMillis() - d.time) / 86400000L
        }.getOrDefault(-1)
    }

    fun levelText(l: Lv): String = when (l) {
        Lv.RISK -> "风险"
        Lv.WARN -> "注意"
        Lv.NOTE -> "信息"
        Lv.OK -> "正常"
        Lv.UNKNOWN -> "无法检测"
    }

    @Suppress("unused")
    private fun join(a: List<String>): String = TextUtils.join("、", a)
}
