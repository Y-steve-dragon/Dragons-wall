package com.privacyscope.local.core

import android.content.Context
import com.privacyscope.local.R

/**
 * 高危域名库（本地离线，只用于「标记」，绝不用于拦截）。
 *
 * 为什么要有它：TrackerDb 记的是「埋点/统计/推送」这类**商业追踪**域名，它是常态不是攻击；
 * 而这份库记的是**恶意分发站点与钓鱼站**——那个才是「可能让你中招」的。
 * 两类在界面上必须分开：红 = 会被拦（追踪器），黄 = 高危但本 App 不动它（恶意/钓鱼）。
 *
 * 数据从 res/raw/risk_domains.txt 读，格式 `<类别>|<域名>`，两条来源：
 *   - urlhaus-filter：恶意软件分发（abuse.ch URLhaus，CC0）
 *   - phishing-filter：钓鱼站点
 * 见 THIRD-PARTY.md 的许可说明。
 *
 * 匹配规则：**后缀匹配**。库里有 example.com，则 a.b.example.com 也命中——
 * 因为钓鱼站爱把站点挂在同一个二级域的子域上。
 */
object RiskDb {

    const val CAT_MAL = 1
    const val CAT_PHISH = 2

    @Volatile
    private var map: Map<String, Int>? = null

    @Volatile
    private var loading = false

    fun catName(cat: Int): String = when (cat) {
        CAT_MAL -> "恶意分发"
        CAT_PHISH -> "钓鱼站"
        else -> "高危"
    }

    /** 后台预热：首次调用会读 674KB 文本并切分，别放在 UI 线程做。 */
    fun preload(ctx: Context) {
        if (map != null || loading) return
        loading = true
        Thread {
            try {
                load(ctx)
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
        }.start()
    }

    @Synchronized
    private fun load(ctx: Context): Map<String, Int> {
        map?.let { return it }
        val out = HashMap<String, Int>(32768)
        ctx.resources.openRawResource(R.raw.risk_domains).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val cut = line.indexOf('|')
                if (cut <= 0 || cut + 1 >= line.length) continue
                val cat = line[0] - '0'
                out[line.substring(cut + 1)] = if (cat == CAT_MAL) CAT_MAL else CAT_PHISH
            }
        }
        map = out
        return out
    }

    /**
     * 命中就返回中文类别名（「恶意分发」/「钓鱼站」），没命中返回 null。
     * 库里没加载完时直接返回 null —— 宁可这一屏不标黄，也不卡住界面。
     */
    fun hit(ctx: Context, host: String): String? {
        val m = map ?: run {
            preload(ctx)
            return null
        }
        var h = host.lowercase().trimEnd('.')
        var guard = 0
        while (h.isNotEmpty() && guard < 8) {
            val c = m[h]
            if (c != null) return catName(c)
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
            guard++
        }
        return null
    }

    fun count(ctx: Context): Int = (map ?: load(ctx)).size
}
