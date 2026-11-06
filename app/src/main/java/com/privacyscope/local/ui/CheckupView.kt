package com.privacyscope.local.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 体检页（v0.8.1 起独立成页签）——「一键风险检测」的落地页。
 *
 * 为什么单开一页：检测要跑几秒、结果很长，塞在「墙」页里会把总览挤走。
 * 本页只做三件事：说明查什么、跑检测、把结果按严重度分组展示。
 */
class CheckupView(private val act: Activity) {

    private var host: LinearLayout? = null
    private var card: View? = null

    fun build(): View {
        val sc = ScrollView(act)
        sc.setBackgroundColor(Ui.C_BG)
        val col = Ui.column(act)
        val pad = Ui.dp(act, 12)
        col.setPadding(pad, pad, pad, Ui.dp(act, 28))
        host = col

        col.addView(Ui.title(act, "体检 · 只读检查 21 个维度", 17f))
        col.addView(Ui.body(act, "想知道「这台手机上到底有什么风险」，就看这一页。全部在本机完成：不联网、不上传、不修改任何设置。"))

        val c = RiskCard.build(act)
        card = c
        col.addView(c)

        col.addView(Ui.button(act, "错误记录（被打断/被吞掉的异常）") {
            val q = com.privacyscope.local.core.Quiet
            android.app.AlertDialog.Builder(act)
                .setTitle("最近异常 " + q.count() + " 条")
                .setMessage(q.dump())
                .setPositiveButton("知道了", null)
                .setNeutralButton("清空", { _, _ -> q.clear() })
                .show()
        })

        col.addView(Ui.note(act, "为什么有「无法检测」", "厂商云账号同步状态、别家 VPN 是否在跑、某个 App 是不是侧载的 —— 这三类安卓没给第三方接口。报告里保留这些维度并写明原因，是为了让你知道「还有这种风险」，而不是假装查过了。"))
        col.addView(Ui.note(act, "体检不等于加固", "它负责「看见」，改不改是你的事：该关的开发者选项、该撤的无障碍、该换的输入法，报告里每条都写了路径。本 App 不会替你关 —— 关错了后果由你承担，这种事不能自动化。"))

        sc.addView(col)
        return sc
    }

    fun refresh() {
        // 无状态需要刷新：检测结果由按钮触发，离开页面再回来不重跑（避免误触）
    }
}
