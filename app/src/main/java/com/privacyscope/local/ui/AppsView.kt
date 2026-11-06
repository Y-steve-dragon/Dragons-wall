package com.privacyscope.local.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import com.privacyscope.local.core.AppOps
import com.privacyscope.local.core.Ops
import com.privacyscope.local.core.Scanner
import com.privacyscope.local.core.Shell
import com.privacyscope.local.core.Blocker
import com.privacyscope.local.core.Bypass
import com.privacyscope.local.core.WatchList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ArrayAdapter
import android.widget.Spinner

/** 清单页：谁要了什么、打包了什么 SDK、勾选后一处改权限 */
class AppsView(private val act: AppCompatActivity) {

    lateinit var root: View
        private set

    private val adapter = Adapter()
    private var all: List<Scanner.Item> = emptyList()
    private var filter = 0 // 0 全部 1 第三方 2 已勾选
    private var busy = false
    private lateinit var status: TextView
    private lateinit var presetSpinner: Spinner
    private val scope = CoroutineScope(Dispatchers.IO)
    private val filterBtns = mutableListOf<Button>()

    /** 把三个过滤按钮画成「当前选中 = 绿底实心」 */
    private fun paintFilter() {
        filterBtns.forEachIndexed { i, b ->
            val on = i == filter
            b.setTextColor(if (on) 0xFF04120A.toInt() else Ui.C_SEED)
            b.background = Ui.round(if (on) Ui.C_SEED else 0x00000000, Ui.dp(act, 3), Ui.dp(act, 1), Ui.C_SEED)
        }
    }

    fun build(): View {
        val col = Ui.column(act)
        val pad = Ui.dp(act, 12)
        col.setPadding(pad, pad, pad, 0)
        col.setBackgroundColor(Ui.C_BG)

        col.addView(Ui.title(act, "清单", 22f))
        col.addView(Ui.body(act, "点一行看详情 · 点方块勾关注 · 长按放行网络"))
        col.addView(
            Ui.note(
                act, "这三种操作分别做什么",
                "点一行：弹出它申请了哪些权限、广播、服务、提供者、页面（离线读，不联网）。\n" +
                    "点右边方块：勾上 = 加入「关注」名单，底部那些改权限的按钮只对勾选的 App 生效。\n" +
                    "长按一行：放行它的网络（它的域名不再被拦）；再长按可改成「完全放行」（连隧道都不进）。"
            )
        )

        val bar = Ui.row(act)
        // 选中项必须有明确的视觉状态：填色 = 当前页签，空框 = 未选（之前点了不变色，看不出选没选中）
        filterBtns.clear()
        for ((i, label) in listOf("全部", "第三方", "已勾选").withIndex()) {
            val b = Ui.button(act, label, filled = (i == 0)) {
                filter = i
                paintFilter()
                applyFilter()
            }
            filterBtns.add(b)
            bar.addView(b)
        }
        col.addView(bar)

        val bar2 = Ui.row(act)
        bar2.addView(Ui.button(act, "扫 SDK 并评分", filled = false) { scanSdk() })
        bar2.addView(Ui.button(act, "存快照", filled = false) { snap() })
        bar2.addView(Ui.button(act, "恢复快照", filled = false) { restore() })
        bar2.addView(Ui.button(act, "自检", filled = false) { diag() })
        col.addView(bar2)

        status = Ui.body(act, "还没扫过 SDK：先点「扫 SDK 并评分」（只读本机 APK，不联网）")
        col.addView(status)

        val rv = RecyclerView(act)
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = Ui.dp(act, 6)
        rv.layoutParams = lp
        col.addView(rv)

        val bottom = Ui.card(act, 10)
        val pr = Ui.row(act)
        presetSpinner = Spinner(act)
        val names = Ops.PRESETS.map { it.label }
        presetSpinner.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, names)
        presetSpinner.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        pr.addView(presetSpinner)
        pr.addView(Ui.button(act, "应用") { applyPreset() })
        bottom.addView(pr)
        bottom.addView(Ui.note(act, "改坏了怎么办", "应用前会自动存快照；想退回点「恢复快照」。"))
        col.addView(bottom)

        root = col
        reload()
        return col
    }

    fun reload() {
        scope.launch {
            val r = runCatching { Scanner.scan(act) }
            withContext(Dispatchers.Main) {
                val e = r.exceptionOrNull()
                if (e == null) {
                    all = r.getOrThrow()
                    applyFilter()
                } else {
                    // 绝不吞异常：否则页面一片空白，你根本不知道是「没应用」还是「扫失败了」
                    all = emptyList()
                    adapter.submit(emptyList())
                    status.setTextColor(Ui.C_WARN)
                    status.text = "扫描失败：" + (e.message ?: e.javaClass.simpleName) +
                        "\n点「自检」看详细原因"
                }
            }
        }
    }

    /** 自检：把「系统说装了多少 App」和「我们扫到多少」摊开来，空列表也能定位原因 */
    private fun diag() {
        val pm = act.packageManager
        val sb = StringBuilder()
        val n0 = runCatching { pm.getInstalledPackages(0).size }.getOrElse { -1 }
        val n1 = runCatching {
            pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS).size
        }.getOrElse { -1 }
        sb.append("① 系统说已装包数：").append(n0)
        sb.append("（带权限查询：").append(n1).append("）\n")
        sb.append("② 本页扫到的：").append(all.size).append(" 个\n")
        val t0 = System.currentTimeMillis()
        val s = runCatching { Scanner.scan(act) }
        val ms = System.currentTimeMillis() - t0
        sb.append("③ 现场重扫：")
        val e = s.exceptionOrNull()
        if (e == null) sb.append(s.getOrThrow().size).append(" 个")
        else sb.append("失败 ").append(e.javaClass.simpleName).append("：").append(e.message)
        sb.append("（").append(ms).append("ms）\n")
        val q = runCatching {
            pm.getPackageInfo(act.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains("android.permission.QUERY_ALL_PACKAGES") == true
        }.getOrDefault(false)
        sb.append("④ 本 App 声明了 QUERY_ALL_PACKAGES：").append(q).append("\n")
        sb.append("⑤ 系统版本：Android ").append(android.os.Build.VERSION.RELEASE)
            .append("（SDK ").append(android.os.Build.VERSION.SDK_INT).append("）")
        status.setTextColor(Ui.C_INK)
        status.text = sb.toString()
    }

    private fun applyFilter() {
        val watched = WatchList.get(act)
        val filtered = when (filter) {
            1 -> all.filter { !it.system }
            2 -> all.filter { watched.contains(it.pkg) }
            else -> all
        }
        // 系统自带的一律沉到底部（同分时第三方在前），免得 Shell 那种 567 项权限的
        // 系统壳永远霸占第一屏；勾选过的仍然优先浮到前面，方便回头找。
        val list = filtered.sortedWith(
            compareBy<Scanner.Item> { if (it.system) 1 else 0 }
                .thenByDescending { watched.contains(it.pkg) }
                .thenByDescending { it.score }
        )
        adapter.submit(list)
        status.setTextColor(Ui.C_DIM)
        status.text = if (all.isEmpty())
            "一个应用都没扫到 · 点上面的「自检」看是系统限制还是扫描失败"
        else
            "共 ${all.size} 个应用（第三方 ${all.count { !it.system }}），已关注 ${watched.size} 个"
    }

    private fun scanSdk() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val n = Scanner.scanSdk(act) { done, total ->
                    act.runOnUiThread { status.text = "正在读 APK 里的 SDK… $done/$total" }
                }
                withContext(Dispatchers.Main) {
                    status.text = "扫描完成：$n 个 App 里识别到采集类 SDK（结果存在本机）"
                    reload()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { toast("扫描失败：${t.message}") }
            } finally {
                busy = false
            }
        }
    }

    private fun snap() {
        val watched = WatchList.get(act)
        if (watched.isEmpty()) { toast("先去勾几个 App"); return }
        scope.launch {
            val n = Ops.snapshot(act, watched)
            withContext(Dispatchers.Main) { toast("已存快照：$n 个 App") }
        }
    }

    private fun restore() {
        if (!Ops.hasSnapshot(act)) { toast("还没有快照"); return }
        if (Shell.mode == Shell.MODE_NONE) { toast("没有特权通道，改不了"); return }
        scope.launch {
            val (ok, fail) = Ops.restore(act)
            withContext(Dispatchers.Main) { toast("已恢复 $ok 项，失败 $fail 项") }
        }
    }

    private fun applyPreset() {
        val watched = WatchList.get(act)
        if (watched.isEmpty()) { toast("先去勾几个 App"); return }
        if (Shell.mode == Shell.MODE_NONE) {
            toast("没有特权通道：先去「墙」页把无线调试连上（三步：配对 / 连接）")
            return
        }
        val preset = Ops.PRESETS[presetSpinner.selectedItemPosition]
        scope.launch {
            Ops.snapshot(act, watched)
            val r = Ops.applyPreset(watched, preset)
            withContext(Dispatchers.Main) {
                val head = buildString {
                    append(preset.label).append("：成功 ").append(r.ok).append(" 项")
                    if (r.unsupported.isNotEmpty()) {
                        append(" · 系统没有的 ").append(r.unsupported.size).append(" 项（不算失败）")
                    }
                    if (r.noEffect.isNotEmpty()) {
                        append(" · 系统不接受的 ").append(r.noEffect.size).append(" 项（不算失败）")
                    }
                    if (r.fail > 0) append(" · 失败 ").append(r.fail).append(" 项")
                }
                if (r.fail == 0) {
                    toast(head + "（已存快照可回滚）")
                    if (r.unsupported.isNotEmpty() || r.noEffect.isNotEmpty()) {
                        showFailure(
                            head,
                            buildString {
                                if (r.unsupported.isNotEmpty()) {
                                    append("① 这台系统根本没有这些能力名（Android 把它们删了/并进权限组了）：\n")
                                    append(r.unsupported.joinToString("\n") {
                                        "  · " + (Ops.NAMES[it] ?: it) + "（$it）"
                                    })
                                    append("\n\n")
                                }
                                if (r.noEffect.isNotEmpty()) {
                                    append("② 命令没报错、但系统静默忽略（这类得去系统设置里关）：\n")
                                    append(r.noEffect.joinToString("\n") {
                                        "  · " + (Ops.NAMES[it] ?: it) + "（$it）"
                                    })
                                    append("\n\n")
                                }
                                append("其余项都已改好，回清单页点开 App 能看到每一项现在是开还是关。")
                            }
                        )
                    }
                } else {
                    showFailure(head, r.failures.joinToString("\n\n"))
                }
                reload()
            }
        }
    }

    /** 失败不能只说「失败 1 项」——要把命令、输出、回读原样摊开，否则没法排错。 */
    private fun showFailure(head: String, trace: String) {
        val box = android.widget.ScrollView(act)
        val tv = Ui.mono(act, head + "\n\n" + trace.ifBlank { "（没有记录到任何对话）" }, 11f, Ui.C_INK)
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

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    /**
     * 选项格（带勾选框）——和清单页那个自绘勾选框同一种样子。
     *
     * 为什么不用系统的 setItems 菜单：那个只有纯文字，看不出「现在是开着的」，
     * 用户点完之后还要自己判断这是"加上"还是"取消"。这里每一项左边都有方框，
     * 勾着 = 现在已生效，点一下是切换。
     */
    private fun optionSheet(
        ctx: android.content.Context,
        title: String,
        items: List<Pair<String, Boolean>>,
        onPick: (Int) -> Unit
    ) {
        val col = Ui.column(ctx)
        val pad = Ui.dp(ctx, 14)
        col.setPadding(pad, Ui.dp(ctx, 6), pad, Ui.dp(ctx, 6))
        items.forEachIndexed { i, (text, checked) ->
            val row = Ui.row(ctx)
            val cb = Ui.check(ctx, 20)
            val lp = LinearLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20))
            lp.marginEnd = Ui.dp(ctx, 10)
            cb.layoutParams = lp
            Ui.paintCheck(ctx, cb, checked)
            row.addView(cb)
            val label = Ui.mono(ctx, text, 12f, if (checked) Ui.C_SEED else Ui.C_INK)
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10))
            row.setOnClickListener {
                onPick(i)
                (col.tag as? android.app.AlertDialog)?.dismiss()
            }
            col.addView(row)
        }
        val dlg = android.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(col)
            .setNegativeButton("取消", null)
            .create()
        col.tag = dlg
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(Ui.round(Ui.C_CARD, Ui.dp(ctx, 14)))
        }
        dlg.show()
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var list: List<Scanner.Item> = emptyList()

        fun submit(l: List<Scanner.Item>) {
            list = l
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(act, 12)
            row.setPadding(p, p, p, p)
            row.background = Ui.round(Ui.C_CARD, Ui.dp(act, 12))

            val col = Ui.column(act)
            col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val name = TextView(act)
            name.setTextColor(Ui.C_INK)
            name.textSize = 15f
            name.setTypeface(null, android.graphics.Typeface.BOLD)
            val sub = TextView(act)
            sub.setTextColor(Ui.C_DIM)
            sub.textSize = 11f
            val tags = TextView(act)
            tags.textSize = 10f
            tags.setTextColor(Ui.C_SEED)
            col.addView(name)
            col.addView(sub)
            col.addView(tags)
            row.addView(col)

            val score = TextView(act)
            score.textSize = 16f
            score.setTypeface(null, android.graphics.Typeface.BOLD)
            score.gravity = Gravity.CENTER
            row.addView(score)

            val cb = Ui.check(act, 20)
            val cblp = LinearLayout.LayoutParams(Ui.dp(act, 20), Ui.dp(act, 20))
            cblp.marginStart = Ui.dp(act, 8)
            cb.layoutParams = cblp
            row.addView(cb)

            val holder = VH(row, name, sub, tags, score, cb)
            val lp = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = Ui.dp(act, 8)
            row.layoutParams = lp
            return holder
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val it = list[position]
            val item = it
            val out = Blocker.isAppOut(act, item.pkg)
            holder.name.text = item.label + if (out) "（网络放行）" else ""
            // 长按 → 选项格（带勾选框，和清单页勾选同一种视觉），而不是系统那种纯文字菜单：
            //   ① 网络放行：域名不拦了，但它还在隧道里
            //   ② 完全放行：整个不进隧道，零额外延时（游戏/直播选这个）
            holder.itemView.setOnLongClickListener { _ ->
                val byp = Bypass.has(act, item.pkg)
                optionSheet(
                    act, item.label,
                    listOf(
                        Pair("网络放行（不拦它的域名，但仍走隧道）", out),
                        Pair("完全放行（整个不进隧道，零额外延时）", byp)
                    )
                ) { i ->
                    if (i == 0) {
                        Blocker.setAppOut(act, item.pkg, !out)
                        toast(if (!out) "已网络放行：${item.label}（域名不再被拦）" else "已恢复拦截：${item.label}")
                    } else {
                        if (Bypass.set(act, item.pkg, !byp)) {
                            com.privacyscope.local.vpn.CaptureVpnService.restart(act)
                            toast(
                                if (!byp) "已完全放行：${item.label}（隧道已重建）"
                                else "已重新纳入隧道：${item.label}"
                            )
                        } else {
                            toast("没有变化")
                        }
                    }
                    notifyItemChanged(position)
                }
                true
            }
            holder.sub.text = it.pkg
            // v0.9.9：把「系统自带」直接写在每行上。以前只能靠切「第三方」按钮才分得出来，
            // 而系统应用的权限项本来就多（Shell 567 项），不标出来会被误当成"可疑"。
            holder.sub.text = "${it.pkg}\n" +
                (if (it.system) "系统自带 · " else "第三方 · ") +
                "权限 ${it.perms.size} 项 · 广播 ${it.receivers} · 服务 ${it.services} · ${fmt(it.apkBytes)}"
            holder.sub.setTextColor(if (it.system) Ui.C_AMBER else Ui.C_DIM)
            // 单点 → 看它到底申请了什么（权限 / 广播 / 服务 / 提供者 / 页面）
            holder.itemView.setOnClickListener { AppDetail.show(act, item) }
            holder.tags.text = if (it.tags.isEmpty()) "" else "打包：" + it.tags.joinToString(" ")
            // 系统自带的应用不打分：它们权限多是出厂就这样（Shell 567 项），
            // 给个数字反而制造噪音、还把真正要看的第三方 App 挤到后面。
            if (it.system) {
                holder.score.text = "系统"
                holder.score.textSize = 11f
                holder.score.setTextColor(Ui.C_DIM)
            } else {
                holder.score.text = it.score.toString()
                holder.score.textSize = 16f
                holder.score.setTextColor(Ui.warnColor(it.score))
            }
            val watched = WatchList.get(act).contains(it.pkg)
            // 勾上的行整块高亮（绿边 + 深绿底），右侧那个小方块不再孤零零地挂着
            holder.itemView.background = if (watched)
                Ui.round(0xFF0C2218.toInt(), Ui.dp(act, 12), Ui.dp(act, 1), Ui.C_SEED)
            else
                Ui.round(Ui.C_CARD, Ui.dp(act, 12))
            Ui.paintCheck(act, holder.cb, watched)
            holder.cb.setOnClickListener { _ ->
                val to = !WatchList.get(act).contains(item.pkg)
                WatchList.toggle(act, item.pkg, to)
                Ui.paintCheck(act, holder.cb, to)
                holder.itemView.background = if (to)
                    Ui.round(0xFF0C2218.toInt(), Ui.dp(act, 12), Ui.dp(act, 1), Ui.C_SEED)
                else
                    Ui.round(Ui.C_CARD, Ui.dp(act, 12))
                status.text = "已关注 ${WatchList.get(act).size} 个"
            }
        }

        override fun getItemCount(): Int = list.size

        inner class VH(
            v: View, val name: TextView, val sub: TextView,
            val tags: TextView, val score: TextView, val cb: TextView
        ) : RecyclerView.ViewHolder(v)
    }

    private fun fmt(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format("%.1f GB", b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> String.format("%.0f MB", b / 1024.0 / 1024)
        b >= 1024 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
