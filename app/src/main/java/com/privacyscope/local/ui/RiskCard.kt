package com.privacyscope.local.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.privacyscope.local.core.RiskScan

/**
 * 「一键风险检测」卡片 + 结果弹窗。
 *
 * 只有两种动作：检测（读）和展示。**不改任何设置** —— 很多维度第三方 App 也改不了，
 * 与其假装能修，不如把「你自己去哪看」写清楚。
 */
object RiskCard {

    fun build(act: Activity): View {
        val card = Ui.card(act)
        card.addView(Ui.title(act, "一键风险检测"))
        card.addView(Ui.body(act, "从系统补丁、Root、独立空间、共享存储、调试通道、无障碍、通知、设备管理、输入法、锁屏、加密、代理…等维度查一遍"))
        card.addView(Ui.note(act, "这个检测是什么性质", "只读：只读系统设置与已安装应用的信息，不改任何东西、不联网、不上传。查不到的维度会明确写「无法检测」并说明原因，不会用猜测填满报告。"))

        val state = Ui.body(act, "还没检测过")
        card.addView(state)

        val row = Ui.row(act)
        row.addView(
            Ui.button(act, "开始检测") {
                state.text = "正在检测…（读应用清单，约 2~5 秒）"
                Thread {
                    val items = runCatching { RiskScan.run(act) }.getOrElse { err ->
                        listOf(
                            RiskScan.Item(
                                "检测器", RiskScan.Lv.UNKNOWN,
                                "检测过程出错：${err.message}",
                                err.javaClass.simpleName,
                                "换个版本再试；若反复出错，把这句话发给维护者"
                            )
                        )
                    }
                    act.runOnUiThread { state.text = summary(items); showReport(act, items) }
                }.start()
            }
        )
        row.addView(
            Ui.button(act, "维度说明", filled = false) {
                AlertDialog.Builder(act)
                    .setTitle("检测哪些维度")
                    .setMessage(DIMENSIONS)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        )
        card.addView(row)
        return card
    }

    private fun summary(items: List<RiskScan.Item>): String {
        val r = items.count { it.level == RiskScan.Lv.RISK }
        val w = items.count { it.level == RiskScan.Lv.WARN }
        val n = items.count { it.level == RiskScan.Lv.NOTE }
        val u = items.count { it.level == RiskScan.Lv.UNKNOWN }
        val o = items.count { it.level == RiskScan.Lv.OK }
        return "上轮结果：风险 $r · 注意 $w · 信息 $n · 无法检测 $u · 正常 $o（共 ${items.size} 项）"
    }

    private fun showReport(act: Activity, items: List<RiskScan.Item>) {
        val host = Ui.column(act)
        val pad = Ui.dp(act, 14)
        host.setPadding(pad, pad, pad, pad)

        host.addView(Ui.mono(act, summary(items), 12f, Ui.C_INK))
        host.addView(Ui.note(act, "怎么读这份报告", "风险 = 现在就该处理；注意 = 值得改；信息 = 知道即可；无法检测 = 平台不给接口，后面写了「你自己去哪看」；正常 = 这一项没问题。本页只读，不修改任何设置。"))

        // 按严重程度分组；每组内逐个折叠
        val groups = listOf(
            RiskScan.Lv.RISK to "风险",
            RiskScan.Lv.WARN to "注意",
            RiskScan.Lv.UNKNOWN to "无法检测（平台不给接口）",
            RiskScan.Lv.NOTE to "信息",
            RiskScan.Lv.OK to "正常"
        )
        for ((lv, label) in groups) {
            val list = items.filter { it.level == lv }
            if (list.isEmpty()) continue
            host.addView(Ui.spacer(act, 10))
            host.addView(Ui.section(act, "$label（${list.size}）"))
            for (it in list) {
                val color = colorOf(lv)
                val head = (if (lv == RiskScan.Lv.OK) "✔ " else if (lv == RiskScan.Lv.UNKNOWN) "？ " else "● ") +
                    it.dim + "：" + it.finding
                host.addView(
                    Ui.fold(act, head, false) { body ->
                        body.addView(Ui.mono(act, "看到了什么", 11.5f, color))
                        body.addView(Ui.mono(act, it.finding, 12f, Ui.C_INK))
                        body.addView(Ui.mono(act, "凭什么这么说", 11.5f, color))
                        body.addView(Ui.mono(act, it.basis, 12f, Ui.C_DIM))
                        body.addView(Ui.mono(act, "你能做什么", 11.5f, color))
                        body.addView(Ui.mono(act, it.advice, 12f, Ui.C_DIM))
                    }
                )
            }
        }

        host.addView(Ui.spacer(act, 12))
        host.addView(Ui.note(act, "这张表查不到的东西", "厂商云账号的同步状态、别家 VPN 是否在跑、某个 App 是不是侧载的、剪贴板历史 —— 这些安卓都没给第三方接口。报告里保留了这些维度并写明原因，是为了让你知道「有这种风险」，而不是让你以为已经查过了。"))

        val sc = Ui.scroll(act)
        sc.addView(host)

        val dlg = AlertDialog.Builder(act)
            .setTitle("风险检测报告")
            .setView(sc)
            .setPositiveButton("关闭", null)
            .create()
        dlg.setOnShowListener { dlg.window?.setBackgroundDrawable(Ui.round(Ui.C_CARD, Ui.dp(act, 14))) }
        dlg.show()
        dlg.window?.setLayout(
            (act.resources.displayMetrics.widthPixels * 0.96f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun colorOf(l: RiskScan.Lv): Int = when (l) {
        RiskScan.Lv.RISK -> Ui.C_WARN
        RiskScan.Lv.WARN -> Ui.C_AMBER
        RiskScan.Lv.NOTE -> Ui.C_SEED
        RiskScan.Lv.UNKNOWN -> Ui.C_DIM
        RiskScan.Lv.OK -> Ui.C_SEED
    }

    private val DIMENSIONS = """
        共 21 个维度（能查的给结论，查不到的写明原因）：

        1. 系统安全补丁（多久没更新）
        2. 系统签名类型（正式 / 测试）
        3. Root 痕迹（su 路径、magisk 目录）
        4. 独立空间（分身 / 工作资料 / 多用户数量）
        5. 云同步与账号（平台不开放 → 无法检测，但保留该维度）
        6. 共享公开存储里的安装包/压缩包
        7. 开发者选项 / USB 调试 / 无线调试
        8. 无障碍服务（最常被滥用）
        9. 通知读取权
        10. 设备管理权
        11. 默认输入法（系统 / 第三方）
        12. 屏幕锁（有没有设密码/指纹）
        13. 存储加密状态
        14. 可调试（debuggable）的 App
        15. 危险权限授予总数
        16. 电池优化豁免（后台常驻）
        17. 全局 HTTP 代理
        18. 外接 USB 设备
        19. 内置浏览器内核（WebView）版本
        20. 代理类 App / 别家 VPN（平台不开放 → 无法检测）
        21. 应用来源（是否侧载，安卓 11+ 接口收紧 → 无法逐个判断）
    """.trimIndent()
}
