package com.privacyscope.local.ui

import android.content.Intent
import android.net.VpnService
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.privacyscope.local.core.Blocker
import com.privacyscope.local.core.Bypass
import com.privacyscope.local.vpn.CaptureVpnService

/**
 * VPN 页（v0.7 独立出来）
 *
 * 为什么单独一页：开 VPN 是有副作用、且会和别的 VPN 抢通道的操作，
 * 不能藏在首页一堆按钮里让人误点。这一页只有"现在什么状态 + 一个开关 + 后果说明"。
 */
class VpnView(private val act: AppCompatActivity) {

    lateinit var root: View
        private set

    private lateinit var host: LinearLayout
    private lateinit var state: TextView
    private lateinit var big: TextView
    private lateinit var power: android.widget.Button

    fun build(): View {
        host = Ui.column(act)
        val pad = Ui.dp(act, 16)
        host.setPadding(pad, pad, pad, pad)
        host.setBackgroundColor(Ui.C_BG)

        host.addView(Ui.mono(act, "▍NET // 网络观测", 16f, Ui.C_SEED).also {
            it.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        host.addView(Ui.body(act, "本地 DNS 旁听 · 不上传 · 不接管大流量"))

        val card = Ui.card(act, 14)
        state = Ui.dot(act, false, "RUNNING", "STOPPED", 12f)
        big = Ui.mono(act, "NET  OFF", 30f, Ui.C_DIM)
        big.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 4))
        power = Ui.button(act, "开启网络观测", true) { toggle() }
        card.addView(state)
        card.addView(big)
        card.addView(power)
        val r = Ui.row(act)
        r.addView(Ui.button(act, "重建隧道", false) { rebuild() })
        r.addView(Ui.button(act, "白名单与放行", false) { AllowDialog.show(act) { refresh() } })
        card.addView(r)
        host.addView(card)

        host.addView(buildStats())

        // 红字警告：这一条必须一直看得见，不能折叠
        val warn = Ui.card(act, 12)
        warn.background = Ui.round(Ui.C_CARD, Ui.dp(act, 4), Ui.dp(act, 1), Ui.C_WARN)
        warn.addView(Ui.mono(act, "! 通道冲突", 12.5f, Ui.C_WARN).also {
            it.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        warn.addView(
            Ui.note(
                act, "为什么有时开不起来",
                "系统同一时间只允许一个 VPN：开启这里会顶掉 Clash / 其它加速器；反过来先开了它们，这里会失败。"
            )
        )
        host.addView(warn)

        host.addView(
            Ui.foldText(
                act, "开启之后网络会怎样（点开）",
                "只截 DNS（问「这个地址是多少」那一步），网页、视频、游戏的大流量不经过这里，带宽与 Ping 不变。\n" +
                    "域名被规则命中时回 0.0.0.0，对方连接失败 —— 这是拦截生效的样子，不是「回空数据」。\n" +
                    "不想被任何规则影响：在「白名单与放行」里把它加进 ② 完全放行（整个不进隧道，零一跳）。"
            )
        )
        host.addView(
            Ui.foldText(
                act, "出问题三步复原（点开）",
                "1) 这一页点「关闭网络观测」→ 立刻恢复；\n" +
                    "2) 还不行：系统设置 → 网络 → VPN，把「阿龙的墙」断开；\n" +
                    "3) 彻底：长按图标卸载，VPN 配置与本地记录一并清掉。"
            )
        )
        host.addView(
            Ui.foldText(
                act, "为什么它不是「翻墙工具」（点开）",
                "它只回答域名、不回内容；没有服务器、没有出口节点、不代理任何流量。\n" +
                    "所以它既不能改你的 IP，也不能访问被墙的站点。"
            )
        )

        val sc = Ui.scroll(act)
        sc.addView(host)
        root = sc
        refresh()
        return sc
    }

    private fun buildStats(): View {
        val c = Ui.card(act)
        c.addView(Ui.section(act, "本次会话"))
        c.addView(Ui.keyValue(act, "隧道地址", "10.111.222.1/32"))
        c.addView(Ui.keyValue(act, "DNS 端口", "10.111.222.2:53"))
        c.addView(Ui.keyValue(act, "今日拦截", Blocker.todayCount(act).toString(), Ui.C_SEED))
        c.addView(Ui.keyValue(act, "完全放行", Bypass.list(act).size.toString() + " 个 App", Ui.C_AMBER))
        return c
    }

    fun refresh() {
        val on = CaptureVpnService.running
        state.text = ((if (on) "● RUNNING" else "○ STOPPED"))
        state.setTextColor(if (on) Ui.C_SEED else Ui.C_DIM)
        big.text = if (on) "NET  ON" else "NET  OFF"
        big.setTextColor(if (on) Ui.C_SEED else Ui.C_DIM)
        power.text = if (on) "关闭网络观测" else "开启网络观测"
    }

    private fun toggle() {
        if (CaptureVpnService.running) {
            CaptureVpnService.stop(act)
            toast("已发送关闭指令")
            act.window.decorView.postDelayed({ refresh() }, 400)
        } else {
            try {
                val prep = VpnService.prepare(act)
                if (prep != null) {
                    act.startActivityForResult(prep, 9001)
                    toast("先点允许：系统在问「允许本 App 建本地 VPN」")
                } else {
                    CaptureVpnService.start(act)
                    toast("网络观测已开启")
                    act.window.decorView.postDelayed({ refresh() }, 600)
                }
            } catch (t: Throwable) {
                toast("开启失败：${t.message}")
            }
        }
    }

    private fun rebuild() {
        if (!CaptureVpnService.running) {
            toast("还没开，先开启")
            return
        }
        CaptureVpnService.restart(act)
        toast("隧道重建中…")
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
}
