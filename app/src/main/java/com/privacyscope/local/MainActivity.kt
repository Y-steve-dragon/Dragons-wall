package com.privacyscope.local

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.privacyscope.local.core.Adb
import com.privacyscope.local.core.RiskDb
import com.privacyscope.local.core.Quiet
import com.privacyscope.local.core.Shell
import com.privacyscope.local.ui.AppsView
import com.privacyscope.local.ui.FeedView
import com.privacyscope.local.ui.HomeView
import com.privacyscope.local.ui.ReportView
import com.privacyscope.local.ui.CheckupView
import com.privacyscope.local.ui.TutorialView
import com.privacyscope.local.ui.Ui
import com.privacyscope.local.ui.VpnView
import com.privacyscope.local.vpn.CaptureVpnService

/**
 * 六个页签（v0.7）：
 *   0 墙   总览与能力
 *   1 流   实时事件（终端尾巴式）
 *   2 清单 App 清单与权限
 *   3 报告 汇总与导出
 *   4 VPN  开/关网络观测（单独一页，因为会与别的 VPN 抢通道）
 *   5 教程 使用手册（折叠式）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var home: HomeView? = null
    private var feed: FeedView? = null
    private var apps: AppsView? = null
    private var checkup: CheckupView? = null
    private var report: ReportView? = null
    private var vpn: VpnView? = null
    private var tutorial: TutorialView? = null
    private var current = -1

    private val names = listOf("墙", "流", "清单", "体检", "报告", "VPN", "教程")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Adb.attach(this)
        RiskDb.preload(this)   // 23k 条高危域名，后台预热，别卡首屏
        Quiet.attach(this)
        installCrashLogger()
        Shell.detect()
        setContentView(buildRoot())
        show(0)
        autoReconnect()
    }

    /**
     * 把「未捕获异常」先记下来，再交回系统默认处理。
     * 为什么需要：崩溃后系统只弹「已停止」，用户能反馈的只有一句「闪退了」。
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Quiet.w("未捕获异常/" + (t?.name ?: "?"), e)
            if (prev != null) {
                prev.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * 进程被系统杀掉后，配对密钥还在、连接没了（socket 随进程消失）。
     * 所以启动时用上次的端口静默重连一次——能连上就不用用户再点一遍。
     * 注意：无线调试被关掉、或连接端口变了（重启/切换后端口会变），这一步就会失败，此时仍要手填。
     */
    private fun autoReconnect() {
        Thread {
            val err = runCatching { Adb.autoReconnect() }.getOrDefault("自动重连异常")
            if (err.isEmpty()) {
                Shell.detect()
                runOnUiThread {
                    refreshCurrent()
                    toast("已自动重连无线调试（免去手动连接）")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.C_BG)

        content = FrameLayout(this)
        content.setBackgroundColor(Ui.C_BG)
        content.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(content)

        root.addView(Ui.divider(this))

        tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.setBackgroundColor(Ui.C_CARD)
        tabBar.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8))
        for ((i, n) in names.withIndex()) {
            val t = TextView(this)
            t.text = n
            t.textSize = 10.5f
            t.typeface = Typeface.MONOSPACE
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7))
            t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            t.setOnClickListener { show(i) }
            tabBar.addView(t)
        }
        root.addView(tabBar)
        return root
    }

    fun goto(index: Int) = show(index)

    /** 按需构造页面；切回来时刷新数据 */
    private fun show(index: Int) {
        if (current == index) {
            refreshCurrent()
            return
        }
        current = index
        content.removeAllViews()
        val v: View = when (index) {
            0 -> (home ?: HomeView(this).also {
                it.onNav = { i -> runOnUiThread { show(i) } }
                home = it
            }).build()
            1 -> (feed ?: FeedView(this).also { feed = it }).build()
            2 -> (apps ?: AppsView(this).also { apps = it }).build()
            3 -> (checkup ?: CheckupView(this).also { checkup = it }).build()
            4 -> (report ?: ReportView(this).also { report = it }).build()
            5 -> (vpn ?: VpnView(this).also { vpn = it }).build()
            else -> (tutorial ?: TutorialView(this).also { tutorial = it }).build()
        }
        content.addView(v)
        paintTabs(index)
        refreshCurrent()
    }

    private fun paintTabs(active: Int) {
        for (i in 0 until tabBar.childCount) {
            val t = tabBar.getChildAt(i) as TextView
            val on = i == active
            t.setTextColor(if (on) Ui.C_SEED else Ui.C_DIM)
            t.setTypeface(Typeface.MONOSPACE, if (on) Typeface.BOLD else Typeface.NORMAL)
            t.text = if (on) "▸" + names[i] else names[i]
        }
    }

    private fun refreshCurrent() {
        when (current) {
            0 -> home?.refresh()
            1 -> feed?.reload()
            2 -> apps?.reload()
            3 -> checkup?.refresh()
            5 -> vpn?.refresh()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001) {
            if (resultCode == RESULT_OK) {
                CaptureVpnService.start(this)
                toast("网络观测已开启")
            } else {
                toast("你拒绝了 VPN 授权：网络观测不可用（其余功能照常）")
            }
            vpn?.refresh()
            home?.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        Shell.detect()
        if (current >= 0) refreshCurrent()
    }

    override fun onDestroy() {
        try {
                    } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
