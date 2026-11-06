package com.privacyscope.local.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.privacyscope.local.core.Blocker
import com.privacyscope.local.core.Bypass
import com.privacyscope.local.vpn.CaptureVpnService

/**
 * 白名单与放行（v0.6）
 *
 * 四张单子，全在本地：
 *   1. 放行的 App       —— 它的 DNS 仍经过我们（只是不拦），会有一次转发
 *   2. 完全放行的 App   —— 整个排除在隧道外，DNS 与业务流量都不经过我们，零额外延时
 *   3. 白名单域名       —— 这些域名永远放过，连「自定义拦截」都压不过它
 *   4. 自定义拦截域名   —— 内置 42 条规则之外，你想拦的
 */
object AllowDialog {

    fun show(act: Activity, onChanged: () -> Unit) {
        val ctx: Context = act

        val host = Ui.column(ctx)
        val pad = Ui.dp(ctx, 16)
        host.setPadding(pad, pad, pad, pad)

        val sc = ScrollView(ctx)
        sc.addView(host)

        lateinit var render: () -> Unit

        fun appLabel(pkg: String): String = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }

        fun rowLine(name: String, sub: String, action: String, onAction: () -> Unit) {
            val r = Ui.row(ctx)
            val col = Ui.column(ctx)
            col.addView(Ui.body(ctx, name))
            if (sub.isNotEmpty()) col.addView(Ui.chip(ctx, sub, Ui.C_DIM))
            r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val btn = Ui.button(ctx, action, false) {
                onAction()
                render()
                onChanged()
            }
            r.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            host.addView(r)
        }

        fun inputRow(hint: String, action: String, onSubmit: (String) -> Boolean) {
            val r = Ui.row(ctx)
            val ed = EditText(ctx)
            ed.hint = hint
            ed.inputType = InputType.TYPE_CLASS_TEXT
            ed.textSize = 14f
            r.addView(ed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(Ui.button(ctx, action, true) {
                if (onSubmit(ed.text.toString().trim())) {
                    render()
                    onChanged()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            host.addView(r)
        }

        /** 可直接点开的应用列表（按名字排序）——给「完全放行」挑人用 */
        fun pickApp(onPicked: (String) -> Unit) {
            val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val apps = try {
                la!!.getActivityList(null, Process.myUserHandle())
                    .map { it.applicationInfo }
                    .distinctBy { it.packageName }
            } catch (t: Throwable) {
                emptyList()
            }
            val pairs = apps.map { ai ->
                val name = try {
                    ctx.packageManager.getApplicationLabel(ai).toString()
                } catch (t: Throwable) {
                    ai.packageName
                }
                name to ai.packageName
            }.sortedBy { it.first }
            if (pairs.isEmpty()) {
                Toast2.short(ctx, "读不到应用列表")
                return
            }
            val names = pairs.map { it.first + "\n" + it.second }.toTypedArray()
            AlertDialog.Builder(act)
                .setTitle("选一个 App（完全放行）")
                .setItems(names) { _, i -> onPicked(pairs[i].second) }
                .setNegativeButton("取消", null)
                .show()
        }

        render = {
            host.removeAllViews()
            host.addView(
                Ui.note(
                    ctx, "这四张单子分别是什么",
                    "全部在本机，不上传。\n" +
                        "① 放行的 App：还在隧道里，只是它的域名不拦。\n" +
                        "② 完全放行的 App：整个不进隧道，和没装本软件一样。\n" +
                        "③ 白名单域名：这些域名永远不拦（子域名也算）。\n" +
                        "④ 自定义拦截：内置规则之外，还想额外拦的。\n" +
                        "改动什么时候生效：① ② 立刻（② 会重建一次隧道，几百毫秒）；③ ④ 立刻，不用重启。"
                )
            )

            // 1. 放行的 App（还在隧道里，只是不拦）
            val outs = Blocker.appsOut(ctx)
            host.addView(Ui.title(ctx, "① 放行的 App（${outs.size}）"))
            host.addView(Ui.note(ctx, "① 是什么意思", "它还走我们的隧道，只是它的域名不拦。DNS 仍由我们转发一次。"))
            if (outs.isEmpty()) {
                host.addView(Ui.note(ctx, "为什么是空的", "默认所有 App 都拦；个别想放行，去清单页长按那一行。"))
            } else {
                outs.sorted().forEach { p ->
                    rowLine(appLabel(p), p, "取消放行") {
                        Blocker.setAppOut(ctx, p, false)
                    }
                }
            }

            // 2. 完全放行的 App（不进隧道，零影响）
            val byp = Bypass.list(ctx)
            host.addView(Ui.title(ctx, "② 完全放行的 App（${byp.size}）"))
            host.addView(
                Ui.note(
                    ctx, "② 是什么意思",
                    "这些 App 根本不进隧道 —— 和没装本软件一样，不会多出任何一跳。" +
                        "游戏想绝对不影响网络，就放这里。代价：我们也看不见它的域名了。"
                )
            )
            if (byp.isEmpty()) {
                host.addView(Ui.body(ctx, "没有。"))
            } else {
                byp.sorted().forEach { p ->
                    rowLine(appLabel(p), p, "取消") {
                        if (Bypass.set(ctx, p, false)) CaptureVpnService.restart(ctx)
                    }
                }
            }
            val addB = Ui.button(ctx, "选择 App 加入完全放行", true) {
                pickApp { pkg ->
                    if (Bypass.set(ctx, pkg, true)) {
                        CaptureVpnService.restart(ctx)
                        Toast2.short(ctx, "已完全放行：" + appLabel(pkg) + "（隧道已重建）")
                    }
                    render()
                    onChanged()
                }
            }
            host.addView(addB)

            // 3. 白名单域名
            val allows = Blocker.allowDomains(ctx)
            host.addView(Ui.title(ctx, "③ 白名单域名（${allows.size}）"))
            host.addView(Ui.note(ctx, "③ 的规则", "永远不拦，子域名也算：写 example.com 会放过 a.example.com。"))
            if (allows.isEmpty()) {
                host.addView(Ui.body(ctx, "没有。"))
            } else {
                allows.sorted().forEach { d ->
                    rowLine(d, "永不拦", "删除") { Blocker.removeAllow(ctx, d) }
                }
            }
            inputRow("例如 pay.example.com", "加入白名单") { v ->
                if (v.isEmpty() || !v.contains('.')) false else { Blocker.addAllow(ctx, v); true }
            }

            // 4. 自定义拦截域名
            val customs = Blocker.custom(ctx)
            host.addView(Ui.title(ctx, "④ 自定义拦截域名（${customs.size}）"))
            if (customs.isEmpty()) {
                host.addView(Ui.note(ctx, "为什么是空的", "内置规则已覆盖 42 条常见追踪器域名，这里只放你想额外加的。"))
            } else {
                customs.sorted().forEach { d ->
                    rowLine(d, "拦截", "删除") { Blocker.removeCustom(ctx, d) }
                }
            }
            inputRow("例如 ads.example.com", "加入拦截") { v ->
                if (v.isEmpty() || !v.contains('.')) false else { Blocker.addCustom(ctx, v); true }
            }
        }

        render()
        AlertDialog.Builder(act)
            .setTitle("白名单与放行")
            .setView(sc)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 小工具：这里的弹窗不持有 Activity 引用，走系统 Toast */
    private object Toast2 {
        fun short(ctx: Context, msg: String) {
            try {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}
