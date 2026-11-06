package com.privacyscope.local.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.privacyscope.local.core.Blocker
import com.privacyscope.local.core.FileWatch
import com.privacyscope.local.core.Monitor
import com.privacyscope.local.core.Shell
import com.privacyscope.local.core.Store
import com.privacyscope.local.core.WatchList
import com.privacyscope.local.vpn.CaptureVpnService

/** 首页：这台手机现在能做到什么、还差哪一步 */
class HomeView(private val act: AppCompatActivity) {

    private lateinit var host: LinearLayout
    private lateinit var monitorBtn: Button
    private lateinit var capCard: LinearLayout
    private lateinit var fileInfo: TextView

    /** 文件变化那张卡本身（切模式时要整张重画） */
    private lateinit var fileBox: LinearLayout

    /** 广告/追踪器拦截那张卡 */
    private lateinit var blockCard: LinearLayout
    private lateinit var blockInfo: TextView
    lateinit var root: View
        private set

    /** 页签跳转（由 MainActivity 注入）：0 墙 / 1 流 / 2 清单 / 3 报告 / 4 VPN / 5 教程 */
    var onNav: ((Int) -> Unit)? = null

    fun build(): View {
        host = Ui.column(act)
        val pad = Ui.dp(act, 16)
        host.setPadding(pad, pad, pad, pad)
        host.setBackgroundColor(Ui.C_BG)

        val h = Ui.title(act, "▍WALL // 阿龙的墙", 18f)
        host.addView(h)
        host.addView(Ui.body(act, "看谁在拿你的数据 · 全部在本机判断 · 不联网不上传"))

        host.addView(Ui.spacer(act, 14))

        val cap = Ui.card(act)
        cap.addView(Ui.title(act, "本机能力"))
        capCard = cap
        host.addView(cap)

        host.addView(buildActions())
        host.addView(AdbCard.build(act))
        host.addView(buildFiles())
        host.addView(buildBlock())
        host.addView(RiskCard.build(act))

        val about = Ui.card(act)
        about.addView(Ui.section(act, "它会做什么 / 不做什么"))
        about.addView(
            Ui.foldText(
                act, "会做的事（点开）",
                "列清单（谁装了什么、要了什么权限、打包了哪些 SDK）、" +
                    "看网络去向（DNS/SNI，只解析不拦截）、按 App 关采集、" +
                    "给勾选的 App 打保护、把这一切记成时间线并导出报告。"
            )
        )
        about.addView(
            Ui.foldText(
                act, "不会做的事（点开）",
                "改 IMEI、伪造设备指纹、解密别人的 HTTPS、把数据传到任何服务器。" +
                    "这些要么需要 root/厂商签名，要么属于绕过而不是保护。"
            )
        )
        host.addView(about)

        val sc = Ui.scroll(act)
        sc.addView(host)
        root = sc
        refresh(cap)
        return sc
    }

    private fun buildActions(): View {
        val box = Ui.card(act)
        box.addView(Ui.title(act, "下一步"))
        val r1 = Ui.row(act)
        r1.addView(Ui.button(act, "重新检测") { refresh(); toast("已重新检测") })
        r1.addView(Ui.button(act, "连无线调试", filled = false) { toast("往下滑到「无线调试通道」卡片，按三步填") })
        box.addView(r1)
        val r2 = Ui.row(act)
        r2.addView(Ui.button(act, "允许看使用情况", filled = false) { openUsageAccess() })
        monitorBtn = Ui.button(act, "开始实时观测") { toggleMonitor() }
        r2.addView(monitorBtn)
        box.addView(r2)
        val r3 = Ui.row(act)
        r3.addView(Ui.button(act, "去 VPN 页开关网络观测", filled = false) { onNav?.invoke(5) })
        r3.addView(Ui.button(act, "看教程", filled = false) { onNav?.invoke(6) })
        box.addView(r3)
        return box
    }

    fun refresh(cap: LinearLayout? = null) {
        val target = cap ?: capCard
        target.removeAllViews()
        target.addView(Ui.title(act, "本机能力"))

        val ver = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        target.addView(Ui.keyValue(act, "系统", ver))
        target.addView(Ui.keyValue(act, "特权通道", Shell.describe(), if (Shell.mode == Shell.MODE_NONE) Ui.C_WARN else Ui.C_SEED))
        target.addView(
            Ui.keyValue(
                act,
                "看使用情况",
                if (hasUsageAccess()) "已允许" else "未允许",
                if (hasUsageAccess()) Ui.C_SEED else Ui.C_WARN
            )
        )
        target.addView(
            Ui.keyValue(
                act,
                "网络观测",
                if (CaptureVpnService.running) "运行中" else "未开启",
                if (CaptureVpnService.running) Ui.C_SEED else Ui.C_DIM
            )
        )
        target.addView(Ui.keyValue(act, "实时观测", if (Monitor.running) "运行中" else "已停止"))
        target.addView(Ui.keyValue(act, "关注列表", "${WatchList.get(act).size} 个 App"))
        target.addView(
            Ui.keyValue(
                act,
                "文件观测",
                FileWatch.modeName(act),
                Ui.C_INK
            )
        )
        target.addView(
            Ui.keyValue(
                act,
                "文件权限",
                if (FileWatch.available(act)) "已具备" else "待授权",
                if (FileWatch.available(act)) Ui.C_SEED else Ui.C_WARN
            )
        )
        target.addView(Ui.spacer(act, 6))
        target.addView(
            Ui.body(
                act,
                when (Shell.mode) {
                    Shell.MODE_ROOT -> "有 root：可以改任意 App 的权限状态。"
                    Shell.MODE_ADB -> "无线调试已连上（uid 2000）：能改别人的权限。App 被系统杀掉后要重新点「③ 连接」（不用重新配对）。"
                    Shell.MODE_ROOT -> "root 已就绪：能改权限。"
                    else -> "还没有特权通道：现在只能看（清单 / SDK / 网络 / 体检），改不了别人的权限。" +
                        "往下滑到「无线调试通道」卡片，连接手机自带的 adb 即可。"
                }
            )
        )
        if (monitorBtn.text.toString().isEmpty()) monitorBtn.text = "开始实时观测"
        monitorBtn.text = if (Monitor.running) "停止实时观测" else "开始实时观测"
    }

    private fun hasUsageAccess(): Boolean = try {
        val ops = act.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow("android:get_usage_stats", Process.myUid(), act.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), act.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        false
    }

    private fun openUsageAccess() {
        try {
            act.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (t: Throwable) {
            toast("打不开设置：${t.message}")
        }
    }

    private fun toggleMonitor() {
        if (Monitor.running) {
            Monitor.stop()
            toast("已停止实时观测")
        } else {
            if (Shell.mode == Shell.MODE_NONE) {
                toast("没有特权通道，观测会拿不到前台信息")
            }
            Monitor.start(act)
            toast("已开始（每 2 秒看一次前台）")
        }
        refresh()
    }

    /** 文件变化卡片：模式二选一 + 授权 + 扫一次 + 结果摘要 */
    private fun buildFiles(): View {
        fileBox = Ui.card(act)
        fillFiles()
        return fileBox
    }

    /** 按当前模式重画这张卡（切模式时整体重画，避免按钮状态错乱） */
    private fun fillFiles() {
        fileBox.removeAllViews()
        val limited = FileWatch.mode(act) == FileWatch.MODE_LIMITED
        fileBox.addView(Ui.title(act, "文件变化（最近 24 小时）"))

        val mr = Ui.row(act)
        mr.addView(
            Ui.button(act, if (limited) "受限模式 ✓" else "受限模式", filled = limited) {
                switchMode(FileWatch.MODE_LIMITED)
            }
        )
        mr.addView(
            Ui.button(act, if (limited) "全盘模式" else "全盘模式 ✓", filled = !limited) {
                switchMode(FileWatch.MODE_FULL)
            }
        )
        fileBox.addView(mr)

        fileBox.addView(
            Ui.foldText(
                act, if (limited) "受限模式是什么（点开）" else "全盘模式是什么（点开）",
                if (limited)
                    "不用「所有文件访问」。只读系统媒体库（相册照片 / 视频 / 录音），" +
                        "要三个「读媒体」权限。看不到文档、压缩包、安装包。"
                else
                    "要「所有文件访问」。能看到公共目录里的全部文件" +
                        "（Download / Documents / DCIM / Pictures / Movies / Music / Android/media …）。"
            )
        )

        fileInfo = Ui.body(act, "上一轮：" + FileWatch.lastScanText(act))
        fileBox.addView(fileInfo)

        val r = Ui.row(act)
        r.addView(
            Ui.button(act, if (limited) "授权读媒体" else "授权全盘访问", filled = false) { authorize() }
        )
        r.addView(Ui.button(act, "扫一次") { scanFiles() })
        fileBox.addView(r)

        fileBox.addView(
            Ui.foldText(
                act, "两种模式的区别（点开）",
                "两种模式二选一，可见范围不同；都不联网、不改不删任何文件。" +
                    "「新增」指与上次扫描相比多出来的，首次扫描只记基线。"
            )
        )
    }

    /** 广告与追踪器拦截卡片：开关 + 今日计数 + 四个分类（DNS 沉洞，不接管全部流量） */
    private fun buildBlock(): View {
        blockCard = Ui.card(act)
        fillBlock()
        return blockCard
    }

    /** 按当前设置整卡重画（开关、分类按钮都在本卡内，重画最不容易出错） */
    private fun fillBlock() {
        blockCard.removeAllViews()
        blockCard.addView(Ui.title(act, "广告与追踪器拦截（DNS 层）"))

        val on = Blocker.enabled(act)
        blockCard.addView(
            Ui.button(act, if (on) "已开启 · 点此关闭" else "未开启 · 点此开启", filled = on) {
                Blocker.setEnabled(act, !on)
                fillBlock()
                toast(if (!on) "已开启：只对开着「网络观测」时的域名查询生效" else "已关闭拦截")
            }
        )
        blockInfo = Ui.body(act, "今日已拦截 " + Blocker.todayCount(act) + " 次")
        blockCard.addView(blockInfo)

        val cats = listOf(
            Blocker.CAT_AD to "统计/广告",
            Blocker.CAT_PUSH to "推送",
            Blocker.CAT_RISK to "风控/标识",
            Blocker.CAT_CRASH to "崩溃/性能"
        )
        for (grp in listOf(cats.take(2), cats.drop(2))) {
            val r = Ui.row(act)
            for ((c, nm) in grp) {
                val ce = Blocker.catEnabled(act, c)
                r.addView(
                    Ui.button(act, (if (ce) "✓ " else "") + nm, filled = ce) {
                        Blocker.setCatEnabled(act, c, !ce)
                        fillBlock()
                    }
                )
            }
            blockCard.addView(r)
        }

        blockCard.addView(
            Ui.foldText(
                act, "它是怎么拦的（点开）",
                "做法：某个 App 问「xx.umeng.com 的地址」时，直接回 0.0.0.0 —— 域名解不出来，" +
                    "广告和统计就发不出去。只在域名层拦，HTTPS 正文看不到，也没打算看。\n" +
                    "「推送」打开后可能收不到消息；支付/风控类建议一直关着。\n" +
                    "清单页长按某个 App 可以单独放行它的域名。"
            )
        )
        blockCard.addView(
            Ui.button(act, "清零今日计数", filled = false) {
                Blocker.resetCount(act)
                fillBlock()
            }
        )

        // 白名单与放行：三张本地单子（放行的 App / 白名单域名 / 自定义拦截域名）
        val nOut = Blocker.appsOut(act).size
        val nAllow = Blocker.allowDomains(act).size
        blockCard.addView(
            Ui.button(act, "白名单与放行（App $nOut · 域名 $nAllow）", filled = false) {
                AllowDialog.show(act) { fillBlock() }
            }
        )
    }

    private fun switchMode(m: String) {
        FileWatch.setMode(act, m)
        fillFiles()
        refresh()
        if (m == FileWatch.MODE_FULL && !FileWatch.fullAccess(act)) {
            toast("已切到全盘模式，还需要你打开「所有文件访问」")
        } else {
            toast("已切到" + FileWatch.modeName(act))
        }
    }

    private fun authorize() {
        if (FileWatch.mode(act) == FileWatch.MODE_FULL) {
            toast("在系统页面找到「阿龙的墙」，打开「允许访问所有文件」")
            FileWatch.openSettings(act)
        } else {
            val p = FileWatch.neededPermissions(act)
            toast("请允许读媒体（可只给图片/视频/音频中的一部分）")
            act.requestPermissions(p, 9101)
        }
    }

    /** 在后台线程扫，扫完回主线程改文字（绝不能在工作线程碰界面） */
    private fun scanFiles() {
        if (!FileWatch.available(act)) {
            toast(
                if (FileWatch.mode(act) == FileWatch.MODE_FULL) "还没授权：点「授权全盘访问」把开关打开"
                else "还没授权：点「授权读媒体」给权限"
            )
            authorize()
            return
        }
        toast("正在扫，稍等…")
        fileInfo.text = "正在扫…"
        val app = act.applicationContext
        val isFull = FileWatch.mode(act) == FileWatch.MODE_FULL
        Thread {
            var list: List<FileWatch.Item> = emptyList()
            var fresh: List<FileWatch.Item> = emptyList()
            var err = ""
            try {
                list = FileWatch.scan(app, 24)
                val seen = FileWatch.loadSeen(app)
                fresh = list.filter { !seen.contains(it.path) }
                val now = HashSet<String>(seen)
                for (i in list) now.add(i.path)
                FileWatch.saveSeen(app, now)
                FileWatch.saveLastScan(app)
                // 只有"新增"才写进时间线；首次扫描（seen 为空）不刷屏
                if (seen.isNotEmpty()) {
                    val store = Store(app)
                    val ts = System.currentTimeMillis()
                    for (i in fresh.take(50)) {
                        store.addEvent(
                            ts, "文件系统", if (isFull) "文件" else "媒体",
                            "新增 ${i.path}（${FileWatch.sizeText(i.bytes)}）"
                        )
                    }
                }
            } catch (t: Throwable) {
                err = t.message ?: t.toString()
            }
            val msg = when {
                err.isNotEmpty() -> "扫描出错：$err"
                list.isEmpty() -> if (FileWatch.mode(app) == FileWatch.MODE_FULL)
                    "最近 24 小时没有变化（或公共目录不可读）"
                else
                    "最近 24 小时媒体库没有新增（受限模式看不到文档与下载目录）"
                else -> "改动 ${list.size} 个 · 新增 ${fresh.size} 个 · 最新：" + nameOf(list[0].path) +
                    "\n上一轮：" + FileWatch.lastScanText(app)
            }
            val first = list.isNotEmpty() && fresh.size == list.size
            act.runOnUiThread {
                fileInfo.text = msg + if (first) "\n（首次扫描：把当前全记为已知，下次起才报新增）" else ""
                toast(if (err.isEmpty()) "扫完：改动 ${list.size}，新增 ${fresh.size}" else "扫描出错")
            }
        }.start()
    }

    private fun nameOf(path: String): String =
        if (path.startsWith("media://")) path.substringAfterLast('/') else path.substringAfterLast('/')

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
}
