package com.privacyscope.local.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.privacyscope.local.MainActivity
import com.privacyscope.local.core.AppOps
import com.privacyscope.local.core.Ops
import com.privacyscope.local.core.PermZh
import com.privacyscope.local.core.PkgRepo
import com.privacyscope.local.core.Shell
import com.privacyscope.local.core.Store
import android.widget.Toast
import com.privacyscope.local.core.Scanner
import java.util.Locale

/**
 * 清单页点一下某个 App → 展开它到底申请了什么。
 *
 * 全部读自本机 PackageManager（离线、不上传）：
 *  - 权限：名字 + 人话 + 是否已授予 + 是否危险
 *  - 广播接收器 / 服务 / 内容提供者 / 页面：名字 + 是否对外（exported）+ 有没有权限保护
 * 「对外」是最该注意的一栏：别人（或别的 App）可以直接唤起它。
 */
object AppDetail {

    private const val FLAGS = PackageManager.GET_PERMISSIONS or
        PackageManager.GET_RECEIVERS or
        PackageManager.GET_SERVICES or
        PackageManager.GET_PROVIDERS or
        PackageManager.GET_ACTIVITIES

    fun show(act: Activity, item: Scanner.Item) {
        // 主线程不做 adb 往返（会抛 NetworkOnMainThreadException）。
        // 第一次打开某 App：先在后台把 19 个 op 读进缓存，再真正画界面。
        if (Shell.mode != Shell.MODE_NONE && !AppOps.isPrefetched(item.pkg)) {
            Thread {
                AppOps.prefetch(item.pkg)
                act.runOnUiThread { show(act, item) }
            }.start()
            return
        }
        val pm = act.packageManager
        val pi = runCatching { pm.getPackageInfo(item.pkg, FLAGS) }.getOrNull()

        val col = Ui.column(act)
        val pad = Ui.dp(act, 14)
        col.setPadding(pad, pad, pad, pad)

        // ── ① 这是什么 ─────────────────────────────────────────────
        col.addView(Ui.section(act, "这是什么"))
        col.addView(Ui.keyValue(act, "包名", item.pkg))
        if (pi != null) {
            col.addView(Ui.keyValue(act, "编号(uid)", (pi.applicationInfo?.uid ?: -1).toString()))
            val ver = pi.versionName ?: "?"
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            col.addView(Ui.keyValue(act, "版本", "$ver ($code)"))
            val tgt = pi.applicationInfo?.targetSdkVersion ?: 0
            col.addView(Ui.keyValue(act, "目标 SDK", tgt.toString()))
        }
        col.addView(Ui.keyValue(act, "安装包", fmt(item.apkBytes)))
        col.addView(Ui.keyValue(act, "装在哪", if (item.system) "系统分区（预装）" else "数据分区（自己装的）"))
        col.addView(Ui.keyValue(act, "风险分", item.score.toString(), Ui.warnColor(item.score)))
        if (item.tags.isNotEmpty()) {
            col.addView(Ui.keyValue(act, "打包进来", item.tags.joinToString("、")))
        }
        var dlgRef: AlertDialog? = null
        // 这台系统真能改的项（Android 14+ 会剔掉 appops 里已消失的名字）
        val opsList = Ops.namesFor(android.os.Build.VERSION.SDK_INT)

        // ── ①b 能改的能力（AppOps 开关）── v0.9.5 移到权限前面 ──────────
        val changedOps = opsList.keys.mapNotNull { op ->
            val v = AppOps.cached(item.pkg, op)
            if (v != null && v != "default") op to v else null
        }.toMap()
        col.addView(Ui.spacer(act, 10))
        col.addView(Ui.section(act, "能改的能力（${opsList.size} 项，可逐项关掉）"))
        col.addView(
            Ui.note(
                act,
                "这是什么 / 为什么只有 ${opsList.size} 项",
                "系统给每个「能力」记一个开关：允许 / 忽略 / 默认。忽略 = 它调到这个能力时被当成拒绝，一般不会崩。\n" +
                    "以前显示 0 项，是因为 `appops get <包名>` 只打印被单独改过的项；干净的 App 一项都没改过，列表就是空的。\n\n" +
                    "为什么少了 3 项：读已安装应用列表、开机自启、使用记录这三项在 Android 14 之后已经不能用 appops 改" +
                    "（前两个名字被删，第三个由系统另管）。实测改不动的东西留在界面上，只会让你反复点、反复看到失败，所以直接不列。\n\n" +
                    "「开启 / 关闭」两个词就是全部：显示「关闭」= 系统会当拒绝；显示「开启」= 跟系统策略走（含系统默认与允许）。"
            )
        )
        col.addView(
            Ui.mono(
                act,
                "当前通道：" + Shell.describe(),
                11.5f,
                if (Shell.mode != Shell.MODE_NONE) Ui.C_SEED else Ui.C_AMBER
            )
        )
        if (Shell.mode == Shell.MODE_NONE) {
            col.addView(
                Ui.mono(
                    act,
                    "! 现在没有通道：下面点「关掉」会失败。先去「墙」页把无线调试连上。",
                    11.5f,
                    Ui.C_WARN
                )
            )
            col.addView(
                Ui.button(act, "知道了，去「墙」页连通道", filled = true) {
                    dlgRef?.dismiss()
                    (act as? MainActivity)?.goto(0)
                }
            )
        }
        for ((opName, human) in opsList) {
            val modeVal = AppOps.cached(item.pkg, opName) ?: changedOps[opName] ?: "default"
            val row = Ui.row(act)
            // 不写「关掉 / 恢复」这种动作词，直接显示它现在是开还是关；
            // 按钮上给的是「下一步能做的那个动作」，一眼就能看出改完会变什么。
            var cur = modeVal
            val label = Ui.mono(act, stateMark(cur) + human + "  " + stateText(cur), 11.5f, modeColor(cur))
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val btn: Button = Ui.button(act, if (isOff(cur)) "开启" else "关闭", filled = false) {}
            // 注意：不能在构造 btn 的那个 lambda 里引用 btn 自己（Kotlin 会报 Unresolved reference），
            // 所以先建按钮、再挂监听。
            btn.setOnClickListener {
                val target = if (isOff(cur)) "allow" else "ignore"
                askChange(act, item.pkg, opName, target, human) {
                    cur = target
                    label.text = stateMark(cur) + human + "  " + stateText(cur)
                    label.setTextColor(modeColor(cur))
                    btn.text = if (isOff(cur)) "开启" else "关闭"
                }
            }
            row.addView(btn)
            col.addView(row)
        }
        // 只保留一个「全部恢复默认」入口：每行都挂个「默认」按钮太吵，
        // 而真会用到还原的场景，几乎都是「整套改完出问题了」。
        col.addView(Ui.spacer(act, 6))
        col.addView(Ui.button(act, "全部恢复默认（交还给系统策略）", filled = false) {
            askResetAll(act, item.pkg) {
                dlgRef?.dismiss()
                show(act, item)
            }
        })
        col.addView(Ui.fold(act, "这 ${opsList.size} 项分别是什么意思（含系统原名）") { body ->
            for ((opName, human) in opsList) {
                body.addView(Ui.mono(act, human + "\n    系统原名：$opName", 10.5f, Ui.C_DIM))
            }
        })

        // ── ② 权限（折叠，默认收起）── v0.9.5 折起来，把上面那块让出来 ──
        val perms = pi?.requestedPermissions ?: item.perms.toTypedArray()
        val flags = pi?.requestedPermissionsFlags
        val granted = perms.indices.count { i ->
            flags != null && i < flags.size &&
                (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }
        col.addView(Ui.spacer(act, 10))
        col.addView(Ui.fold(act, "权限详情（${perms.size} 项，已授予 $granted 项）") { body ->
            if (perms.isEmpty()) {
                body.addView(Ui.body(act, "它一项权限都没申请。"))
            } else {
                body.addView(
                    Ui.mono(
                        act,
                        "红 = 危险权限（能看你的东西）  ✔ = 已授予  ○ = 已申请但没给\n" +
                            "已授予的点「撤销」= pm revoke，和系统设置里点「不允许」等价。",
                        10.5f,
                        Ui.C_DIM
                    )
                )
                val pairs = perms.mapIndexed { i, p -> Pair(p, grantedAt(flags, i)) }
                    .sortedByDescending { pr ->
                        (if (PkgRepo.isDangerous(pr.first)) 2 else 0) + (if (pr.second) 1 else 0)
                    }
                for (pr in pairs) {
                    val p = pr.first
                    val g = pr.second
                    val danger = PkgRepo.isDangerous(p)
                    val human = PermZh.zhWithRaw(p)
                    val color = when {
                        danger && g -> Ui.C_WARN
                        danger -> Ui.C_AMBER
                        else -> Ui.C_DIM
                    }
                    if (g) {
                        val row = Ui.row(act)
                        val label = Ui.mono(act, "✔ " + human, 11.5f, color)
                        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(
                            Ui.button(act, "撤销", filled = false) {
                                askRevoke(act, item.pkg, p, human) { dlgRef?.dismiss() }
                            }
                        )
                        body.addView(row)
                    } else {
                        body.addView(Ui.mono(act, "○ " + human, 11.5f, color))
                    }
                }
            }
        })

        // ── ③ 广播接收器 ───────────────────────────────────────────
        val recs = pi?.receivers ?: emptyArray()
        col.addView(Ui.spacer(act, 10))
        col.addView(Ui.fold(act, "广播接收器（${recs.size} 个，对外 ${recs.count { it.exported }} 个）") { body ->
            body.addView(Ui.note(act, "这是什么", "别人喊一句话（比如“开机了”“插上充电器了”），它就能被叫醒。"))
            if (recs.isEmpty()) body.addView(Ui.body(act, "没有。"))
            for (r in recs) {
                body.addView(Ui.mono(act, compLine(r.name, r.exported, r.permission), 11.5f, compColor(r.exported)))
            }
        })

        // ── ④ 服务 ─────────────────────────────────────────────────
        val svcs = pi?.services ?: emptyArray()
        col.addView(Ui.spacer(act, 10))
        col.addView(Ui.fold(act, "服务（${svcs.size} 个，对外 ${svcs.count { it.exported }} 个）") { body ->
            body.addView(Ui.note(act, "这是什么", "没人点它也能在后台长时间干活（放音乐、上传、推送都在这里）。"))
            if (svcs.isEmpty()) body.addView(Ui.body(act, "没有。"))
            for (s in svcs) {
                body.addView(Ui.mono(act, compLine(s.name, s.exported, s.permission), 11.5f, compColor(s.exported)))
            }
        })

        // ── ⑤ 内容提供者 ───────────────────────────────────────────
        val provs: Array<android.content.pm.ProviderInfo> = pi?.providers ?: emptyArray()
        col.addView(Ui.spacer(act, 10))
        col.addView(Ui.fold(act, "内容提供者（${provs.size} 个，对外 ${provs.count { it.exported }} 个）") { body ->
            body.addView(Ui.note(act, "这是什么", "把自家数据摊开给别的 App 读（通讯录、相册都是这么提供的）。"))
            if (provs.isEmpty()) body.addView(Ui.body(act, "没有。"))
            for (pv in provs) {
                body.addView(Ui.mono(act, compLine(pv.name, pv.exported, pv.readPermission), 11.5f, compColor(pv.exported)))
                if (!pv.authority.isNullOrEmpty()) {
                    body.addView(Ui.mono(act, "    " + pv.authority, 10f, Ui.C_DIM))
                }
            }
        })

        // ── ⑥ 页面 ─────────────────────────────────────────────────
        val acts = pi?.activities ?: emptyArray()
        val open = acts.count { it.exported }
        col.addView(Ui.spacer(act, 10))
        col.addView(Ui.fold(act, "页面（${acts.size} 个，其中对外 $open 个）") { body ->
            body.addView(Ui.note(act, "「对外」是什么意思", "对外页面能被别的 App 直接打开；不需要对外的应该关掉。"))
            for (a in acts) {
                body.addView(Ui.mono(act, compLine(a.name, a.exported, a.permission), 11.5f, compColor(a.exported)))
            }
        })

        col.addView(Ui.spacer(act, 12))
        col.addView(
            Ui.note(
                act,
                "说明",
                "这里只列出「写进安装包清单」的东西，读自系统记录，不联网。\n" +
                    "这里的权限和「墙」页里能改的那几项不是一回事：能改的是运行时的开关，这里是它声明要什么。"
            )
        )

        val scroll = Ui.scroll(act)
        scroll.addView(col)
        val dlg = AlertDialog.Builder(act)
            .setTitle(item.label)
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .create()
        dlgRef = dlg
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(Ui.round(Ui.C_CARD, Ui.dp(act, 14)))
        }
        dlg.show()
        // AlertDialog 默认宽度偏窄，这里撑到接近满屏，长类名才不至于被挤成两行
        dlg.window?.setLayout(
            (act.resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }


    /** allow / ignore / default / ask / deny → 人话 */
    private fun modeText(m: String): String = when (m) {
        "allow" -> "允许"
        "ignore" -> "已忽略（调用被拒）"
        "default" -> "默认"
        "ask" -> "询问"
        "deny" -> "拒绝"
        else -> m
    }

    /** 这项能力现在是不是「关着」。ignore / deny 都算关。 */
    private fun isOff(m: String): Boolean = m == "ignore" || m == "deny"

    /**
     * 只用两个词：开 / 关。
     *
     * 原来这里写「关（调用被拒）」「默认（跟系统策略）」——用户 m08791 的反馈是
     * 「不要用关闭或默认这种东西，直接显示状态开启和关闭」，所以统一成两态：
     * ignore/deny 都算关，其余（allow/default/ask）都算开。
     */
    private fun stateText(m: String): String = if (isOff(m)) "关闭" else "开启"

    /** 前面那个小圆点：关 = 实心（强调它现在真的被关了），开 = 空心。 */
    private fun stateMark(m: String): String = if (isOff(m)) "● " else "○ "

    private fun modeColor(m: String): Int = when (m) {
        "ignore" -> Ui.C_SEED
        "allow" -> Ui.C_INK
        "default" -> Ui.C_DIM
        else -> Ui.C_AMBER
    }

    /**
     * 一次性把 19 项全部交还给系统策略（`appops set <pkg> <op> default`）。
     * 逐项点「默认」太累，而真会用到的场景几乎都是「整套改完 App 不正常了，全部还原」。
     */
    private fun askResetAll(act: Activity, pkg: String, done: () -> Unit) {
        AlertDialog.Builder(act)
            .setTitle("全部恢复默认")
            .setMessage(
                "将对 $pkg 的 ${Ops.namesFor(android.os.Build.VERSION.SDK_INT).size} 项能力逐条执行：\n" +
                    "appops set $pkg <能力> default\n\n" +
                    "效果：全部交还给系统原有策略（原来允许的还是允许，原来询问的还是询问）。\n" +
                    "已撤销的运行时权限不会被这一操作恢复，需要去系统设置里重新授予。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("全部恢复") { _, _ ->
                Thread {
                    var ok = 0
                    var fail = 0
                    for ((opName, _) in Ops.namesFor(android.os.Build.VERSION.SDK_INT)) {
                        if (AppOps.set(pkg, opName, "default")) ok++
                        else if (AppOps.lastUnsupported.isEmpty() && AppOps.lastNoEffect.isEmpty()) fail++
                    }
                    val msg = "已恢复 $ok 项" + if (fail > 0) "，$fail 项没恢复（系统不接受）" else ""
                    act.runOnUiThread {
                        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                        done()
                    }
                }.start()
            }
            .show()
    }

    /**
     * 每一次修改都要人点一下「确定改」——这是唯一的护栏。
     * 之所以不批量、不自动：改错了 App 会「看着正常但拿不到数据」，很难查。
     */
    private fun askChange(
        act: Activity,
        pkg: String,
        op: String,
        mode: String,
        human: String,
        done: () -> Unit
    ) {
        val verb = if (mode == "ignore") "关掉" else "恢复"
        val effect = if (mode == "ignore") {
            "以后它调到这个能力，系统都当作拒绝。部分 App 可能因此少功能（不会崩）。"
        } else {
            "回到系统默认值（通常是允许或询问）。"
        }
        AlertDialog.Builder(act)
            .setTitle(verb + "「" + human + "」")
            .setMessage("将对 $pkg 执行：\nappops set $pkg $op $mode\n\n影响：$effect")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定改") { _, _ ->
                Thread {
                    val ok = AppOps.set(pkg, op, mode)
                    act.runOnUiThread {
                        if (ok) {
                            Toast.makeText(act, "已改：" + human + " → " + modeText(mode), Toast.LENGTH_LONG).show()
                            done()
                        } else if (AppOps.lastUnsupported.isNotEmpty()) {
                            // 安卓 16 把部分 op 名改掉了：这不是「失败」，是这台系统没有这一项。
                            showTrace(
                                act,
                                "「" + human + "」在你这一版安卓上没有对应的开关",
                                "系统返回：Unknown operation string\n\n" +
                                    "意思是这台手机的 appops 里没有这个能力名（安卓 13 之后改过几轮名字，部分旧名被移除）。\n" +
                                    "这一项无法用 appops 关掉，其他项不受影响。\n\n" +
                                    "原始对话如下：\n" + AppOps.lastTrace
                            )
                        } else {
                            showTrace(act, "没改成「" + human + "」", AppOps.lastTrace)
                        }
                    }
                }.start()
            }
            .show()
    }

    /**
     * 失败时把「命令 / 输出 / 回读 / 通道状态」原样摊开，可长按复制。
     * 只弹一句「失败 1 项」等于把排错的路堵死。
     */
    private fun showTrace(act: Activity, head: String, trace: String) {
        val box = android.widget.ScrollView(act)
        val tv = Ui.mono(act, head + "\n\n" + trace.ifBlank { "（没有记录到任何对话）" }, 10.5f, Ui.C_INK)
        tv.setTextIsSelectable(true)
        val pad = Ui.dp(act, 14)
        tv.setPadding(pad, pad, pad, pad)
        box.addView(tv)
        AlertDialog.Builder(act)
            .setTitle("没改成：这里是原文")
            .setView(box)
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 撤销一项运行时权限（pm revoke），做完用本机 PackageManager 回读是否真的掉了。
     * 不看回读就报成功，等于骗人。
     */
    private fun askRevoke(
        act: Activity,
        pkg: String,
        perm: String,
        human: String,
        done: () -> Unit
    ) {
        AlertDialog.Builder(act)
            .setTitle("撤销「" + human + "」？")
            .setMessage("将对 $pkg 执行：\npm revoke $pkg $perm\n\n影响：它再调用这项权限会被拒（不会崩），随时能在系统设置里重新授予。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定撤销") { _, _ ->
                Thread {
                    val err = AppOps.revokePermission(pkg, perm)
                    val stillGranted = runCatching {
                        @Suppress("DEPRECATION")
                        val pi = act.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                        val fl = pi.requestedPermissionsFlags ?: IntArray(0)
                        val idx = pi.requestedPermissions?.indexOf(perm) ?: -1
                        idx >= 0 && idx < fl.size && (fl[idx] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    }.getOrNull()
                    act.runOnUiThread {
                        if (err.isEmpty() && stillGranted == false) {
                            Toast.makeText(act, "已撤销：" + human, Toast.LENGTH_LONG).show()
                            Store(act).addEvent(System.currentTimeMillis(), pkg, "撤回权限", human)
                            done()
                        } else {
                            Toast.makeText(
                                act,
                                "没撤成：" + (err.ifBlank { "系统报告已执行，但权限仍是授予状态（多见于系统应用或非运行时权限）" }),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun grantedAt(flags: IntArray?, i: Int): Boolean {
        if (flags == null || i >= flags.size) return false
        return (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
    }

    private fun compLine(name: String, exported: Boolean, permission: String?): String {
        val short = name.substringAfterLast('.')
        val tag = if (exported) "【对外】" else "【仅自己】"
        val guard = if (permission.isNullOrEmpty()) "" else "  受 $permission 保护"
        return "$tag $short$guard"
    }

    private fun compColor(exported: Boolean): Int = if (exported) Ui.C_AMBER else Ui.C_DIM

    private fun fmt(b: Long): String = when {
        b >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
