package com.privacyscope.local.ui

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privacyscope.local.core.Blocker
import com.privacyscope.local.core.Monitor
import com.privacyscope.local.core.Shell
import com.privacyscope.local.core.Store
import com.privacyscope.local.core.RiskDb
import com.privacyscope.local.core.TrackerDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 流（v0.7 改成"终端尾巴"式）
 *
 * 和旧「时间线」的区别：
 * 1. 越往下越新，像 tail -f；默认自动跟随最新一条（往上翻会自动暂停跟随，点「跟随」恢复）；
 * 2. 每 2 秒自己拉一次新事件，不用手点刷新；
 * 3. 等宽单栏，一行一条，不搞卡片；
 * 4. 命中黑名单的行整行标红 —— **只标不拦**，标记不参与任何决策。
 */
/** 一条事件的判定结果：红 = 本项目会拦下的追踪域名；黄 = 高危域名（恶意/钓鱼），只标记、不拦截。 */
class Mark(val text: String, val color: Int)

class FeedView(private val act: AppCompatActivity) {

    lateinit var root: View
        private set

    private val adapter = Adapter()
    private lateinit var status: TextView
    private lateinit var followBtn: android.widget.Button
    private lateinit var rv: RecyclerView
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var follow = true
    private var polling = false

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            reload()
            handler.postDelayed(this, 2000)
        }
    }

    fun build(): View {
        val col = Ui.column(act)
        val pad = Ui.dp(act, 12)
        col.setPadding(pad, pad, pad, 0)
        col.setBackgroundColor(Ui.C_BG)

        col.addView(Ui.mono(act, "▍FLOW // 实时流", 16f, Ui.C_SEED).also {
            it.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        })
        col.addView(Ui.body(act, "一行一条 · 越往下越新 · 红字 = 命中黑名单（只标记）"))

        val bar = Ui.row(act)
        followBtn = Ui.button(act, "⏸ 跟随中", true) { toggleFollow() }
        bar.addView(followBtn)
        bar.addView(Ui.button(act, "停止观测", false) { toggleMonitor() })
        bar.addView(Ui.button(act, "清空", false) { clear() })
        col.addView(bar)

        status = Ui.mono(act, "", 11f, Ui.C_DIM)
        status.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 6))
        col.addView(status)

        rv = RecyclerView(act)
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        rv.setBackgroundColor(Ui.C_BG)
        rv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                val atBottom = !r.canScrollVertically(1)
                if (atBottom != follow) {
                    follow = atBottom
                    paintFollow()
                }
            }
        })
        col.addView(rv)

        root = col
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                polling = true
                handler.post(poll)
            }

            override fun onViewDetachedFromWindow(v: View) {
                polling = false
                handler.removeCallbacks(poll)
            }
        })
        reload()
        return col
    }

    fun refresh() = reload()

    private fun paintFollow() {
        followBtn.text = if (follow) "⏸ 跟随中" else "⏵ 已暂停 · 点此跟随"
        followBtn.alpha = if (follow) 1f else 0.7f
    }

    private fun toggleFollow() {
        follow = !follow
        paintFollow()
        if (follow) scrollBottom()
    }

    private fun scrollBottom() {
        if (adapter.itemCount == 0) return
        rv.scrollToPosition(adapter.itemCount - 1)
    }

    /** 拉一次数据库；跟随状态下贴到底 */
    fun reload() {
        scope.launch {
            try {
                RiskDb.count(act)   // 首次进「流」页时在 IO 线程把它读进来，免得标黄要等下一轮
            } catch (_: Throwable) {
            }
            val list = try {
                Store(act).recentEvents(400).reversed()   // 数据库给的是最新在前 → 翻成尾巴式
            } catch (t: Throwable) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                adapter.submit(list)
                val risk = list.count { adapter.riskOf(it) != null }
                status.text = (if (Monitor.running) "● LIVE" else "○ IDLE") +
                    " · ${list.size} 条 · 风险 $risk 条 · 库上限 2000"
                status.setTextColor(if (Monitor.running) Ui.C_SEED else Ui.C_DIM)
                if (follow) scrollBottom()
            }
        }
    }

    private fun toggleMonitor() {
        if (Monitor.running) {
            Monitor.stop()
        } else {
            if (Shell.mode == Shell.MODE_NONE) toast("没有特权通道，只能拿到网络事件")
            Monitor.start(act)
        }
        reload()
        toast(if (Monitor.running) "已开始" else "已停止")
    }

    private fun clear() {
        scope.launch {
            try {
                Store(act).clearEvents()
            } catch (_: Throwable) {
            }
            withContext(Dispatchers.Main) {
                reload()
                toast("已清空")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val list = ArrayList<Store.Event>()

        fun submit(l: List<Store.Event>) {
            val atBottom = !rv.canScrollVertically(1)
            list.clear()
            list.addAll(l)
            notifyDataSetChanged()
            if (follow && atBottom) scrollBottom()
        }

        /** 命中黑名单就返回一段说明；不命中返回 null（说明只是"看见"，不是"拦了"） */
        /** 红 = 本项目会拦下的追踪域名；黄 = 高危域名（恶意/钓鱼），只标记、不拦截。 */
        fun riskOf(e: Store.Event): Mark? {
            if (e.kind != "网络") return null
            val host = Regex("查域名\\s+([^\\s（(]+)").find(e.detail)?.groupValues?.get(1) ?: return null
            if (Blocker.isAppOut(act, e.pkg)) return null
            val h = host.lowercase().trimEnd('.')
            if (Blocker.allowDomains(act).any { h == it || h.endsWith(".$it") }) return null
            if (Blocker.custom(act).any { h == it || h.endsWith(".$it") }) return Mark("自定义黑名单", Ui.C_WARN)
            val sdk = TrackerDb.byDomain(h)
            if (sdk != null) return Mark(sdk.name + " · " + TrackerDb.categoryName(sdk.category), Ui.C_WARN)
            val high = RiskDb.hit(act, h)
            if (high != null) return Mark("高危 · " + high, Ui.C_AMBER)
            return null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val t = TextView(act)
            t.typeface = Typeface.MONOSPACE
            t.textSize = 11.5f
            t.setLineSpacing(Ui.dp(act, 2).toFloat(), 1f)
            t.setPadding(Ui.dp(act, 4), Ui.dp(act, 5), Ui.dp(act, 4), Ui.dp(act, 5))
            t.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return VH(t)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = list[position]
            val mark = riskOf(e)
            val sb = SpannableStringBuilder()
            val t0 = fmt.format(Date(e.ts))
            sb.append(t0)
            sb.setSpan(ForegroundColorSpan(Ui.C_DIM), 0, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val head = (when (mark?.color) { Ui.C_WARN -> " !"; Ui.C_AMBER -> " ▲"; else -> "  " }) +
                e.pkg + " · " + e.kind
            val hStart = sb.length
            sb.append(head)
            sb.setSpan(
                ForegroundColorSpan(mark?.color ?: Ui.C_INK),
                hStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append("\n   ")
            val dStart = sb.length
            sb.append(e.detail)
            if (mark != null) sb.append(if (mark.color == Ui.C_AMBER) "  [高危 " + mark.text + "]" else "  [命中 " + mark.text + "]")
            sb.setSpan(
                ForegroundColorSpan(mark?.color ?: Ui.C_DIM),
                dStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.t.text = sb
            holder.t.setBackgroundColor(
                when (mark?.color) {
                    Ui.C_WARN -> 0x14FF3B30
                    Ui.C_AMBER -> 0x1CFFC44D
                    else -> 0x00000000
                }
            )
        }

        override fun getItemCount(): Int = list.size

        inner class VH(val t: TextView) : RecyclerView.ViewHolder(t)
    }
}
