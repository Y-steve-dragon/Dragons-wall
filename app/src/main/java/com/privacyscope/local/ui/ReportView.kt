package com.privacyscope.local.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.privacyscope.local.core.Ops
import com.privacyscope.local.core.Scanner
import com.privacyscope.local.core.Shell
import com.privacyscope.local.core.Store
import com.privacyscope.local.core.WatchList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 报告页：把清单 / 关注 / 时间线压成一页纸，可保存、可发给同学 */
class ReportView(private val act: AppCompatActivity) {

    lateinit var root: View
        private set

    private lateinit var out: TextView
    private val scope = CoroutineScope(Dispatchers.IO)

    fun build(): View {
        val col = Ui.column(act)
        val pad = Ui.dp(act, 12)
        col.setPadding(pad, pad, pad, pad)
        col.setBackgroundColor(Ui.C_BG)

        col.addView(Ui.title(act, "报告", 22f))
        col.addView(Ui.body(act, "生成一页总结（本机文件，不上传）"))

        val bar = Ui.row(act)
        bar.addView(Ui.button(act, "生成") { gen() })
        bar.addView(Ui.button(act, "保存到本机", filled = false) { save() })
        bar.addView(Ui.button(act, "分享文本", filled = false) { share() })
        col.addView(bar)

        out = TextView(act)
        out.setTextColor(Ui.C_INK)
        out.textSize = 12f
        out.setLineSpacing(Ui.dp(act, 2).toFloat(), 1f)
        val sc = Ui.scroll(act)
        val wrap = LinearLayout(act)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10))
        wrap.background = Ui.round(Ui.C_CARD, Ui.dp(act, 12))
        wrap.addView(out)
        sc.addView(wrap)
        sc.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        col.addView(sc)

        root = col
        return col
    }

    fun gen() {
        scope.launch {
            val items = runCatching { Scanner.scan(act) }.getOrDefault(emptyList())
            val watched = WatchList.get(act)
            val events = Store(act).recentEvents(200)
            val sb = StringBuilder()
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sb.append("阿龙的墙 · 本机数据保护报告\n")
            sb.append("生成时间：").append(df.format(Date())).append("\n")
            sb.append("设备：Android ").append(android.os.Build.VERSION.RELEASE)
                .append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n")
            sb.append("特权通道：").append(Shell.describe()).append("\n")
            sb.append("应用总数：").append(items.size)
                .append("（第三方 ").append(items.count { !it.system }).append("）\n")
            sb.append("已关注：").append(watched.size).append(" 个\n")
            sb.append("本地事件：").append(events.size).append(" 条\n")
            sb.append("快照：").append(if (Ops.hasSnapshot(act)) "有（可回滚）" else "无").append("\n")
            sb.append("\n【最想要数据的 10 个】\n")
            items.take(10).forEachIndexed { i, item ->
                sb.append(i + 1).append(". ").append(item.label)
                    .append("  分 ").append(item.score)
                    .append("  权限 ").append(item.perms.size)
                    .append(if (item.tags.isEmpty()) "" else "  " + item.tags.joinToString(" "))
                    .append("\n")
            }
            val top = events.groupBy { it.pkg }.entries.sortedByDescending { it.value.size }.take(10)
            if (top.isNotEmpty()) {
                sb.append("\n【时间线里最活跃的 App】\n")
                top.forEach { (p, l) -> sb.append(p).append("  ").append(l.size).append(" 条\n") }
            }
            val net = events.filter { it.kind.contains("网络") }
            if (net.isNotEmpty()) {
                sb.append("\n【最近发出去的域名（前 20）】\n")
                net.groupBy { it.detail.trim().substringBefore(" ") }
                    .entries.sortedByDescending { it.value.size }.take(20).forEach {
                        sb.append(it.key).append("  x").append(it.value.size).append("\n")
                    }
            }
            sb.append("\n说明：分值是「索取度」排序键，不是安全评级。\n")
            sb.append("本报告完全由手机本地数据生成，未上传任何服务器。\n")
            val text = sb.toString()
            withContext(Dispatchers.Main) { out.text = text }
        }
    }

    private fun save() {
        val t = out.text?.toString() ?: ""
        if (t.isBlank()) {
            toast("先点「生成」")
            return
        }
        scope.launch {
            val f = File(act.filesDir, "report.txt")
            f.writeText(t)
            val ext = File(act.getExternalFilesDir(null), "阿龙的墙报告.txt")
            runCatching { ext.writeText(t) }
            withContext(Dispatchers.Main) {
                toast("已保存：${ext.absolutePath}")
            }
        }
    }

    private fun share() {
        val t = out.text?.toString() ?: ""
        if (t.isBlank()) {
            toast("先点「生成」")
            return
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t)
        }
        act.startActivity(Intent.createChooser(i, "分享报告"))
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
}
