package com.privacyscope.local.core

import android.content.Context
import java.util.Calendar

/**
 * DNS 级拦截器（v0.4）
 *
 * 只做一件事：某个 App 问「xxx.umeng.com 的 IP 是多少」时，如果这个域名属于
 * 统计/广告类 SDK，就直接回一个 0.0.0.0，不让它拿到真实地址 —— 这就是
 * AdAway / TrackerControl 的「DNS 沉洞」思路。
 *
 * 边界（写死在代码里，不是"以后再说"）：
 *  - 只在 DNS 这一层拦；它要是把 IP 写死在代码里、或者用 DoH/DoT 加密查询，
 *    这里拦不到（这类情况在界面上如实显示"未拦截"）。
 *  - 不接管全部流量，所以不会拖慢网速，也不会因为你开了它而上不了网。
 *  - 不记录域名以外的任何内容（看不到 HTTPS 正文，加密的）。
 */
object Blocker {

    private const val PREFS = "blocker"
    private const val KEY_ON = "on"
    private const val KEY_CAT = "cat_"
    private const val KEY_APP_OUT = "app_out"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_COUNT = "count"
    private const val KEY_DAY = "day"

    /** 分类编号与 TrackerDb 保持一致：1 统计/广告 2 推送 3 风控/标识 4 崩溃/性能 */
    const val CAT_AD = 1
    const val CAT_PUSH = 2
    const val CAT_RISK = 3
    const val CAT_CRASH = 4

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, v).apply()
    }

    /** 某一类要不要拦。默认只拦第 1 类（统计/广告），其它三类默认放过 —— 免得把推送和支付风控搞坏 */
    fun catEnabled(ctx: Context, cat: Int): Boolean {
        val d = cat == CAT_AD
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAT + cat, d)
    }

    fun setCatEnabled(ctx: Context, cat: Int, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CAT + cat, v).apply()
    }

    /** 这个 App 被用户放行（不拦）—— 放行名单比黑名单更符合直觉：默认全拦，个别放行 */
    fun isAppOut(ctx: Context, pkg: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_APP_OUT, emptySet())!!.contains(pkg)

    fun setAppOut(ctx: Context, pkg: String, out: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY_APP_OUT, emptySet())!!)
        if (out) cur.add(pkg) else cur.remove(pkg)
        sp.edit().putStringSet(KEY_APP_OUT, cur).apply()
    }

    /** 放行的 App（网络完全不被拦）—— 给界面用 */
    fun appsOut(ctx: Context): Set<String> =
        HashSet(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_APP_OUT, emptySet())!!)

    /** 白名单域名：这些域名的请求一律放过（优先级最高，连自定义黑名单也压过它） */
    fun allowDomains(ctx: Context): Set<String> =
        HashSet(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ALLOW, emptySet())!!)

    fun addAllow(ctx: Context, host: String) {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h.contains(' ')) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY_ALLOW, emptySet())!!)
        cur.add(h)
        sp.edit().putStringSet(KEY_ALLOW, cur).apply()
    }

    fun removeAllow(ctx: Context, host: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY_ALLOW, emptySet())!!)
        cur.remove(host.trim().lowercase())
        sp.edit().putStringSet(KEY_ALLOW, cur).apply()
    }

    /** 用户自己加的域名（一行一个，黑名单） */
    fun custom(ctx: Context): Set<String> =
        HashSet(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CUSTOM, emptySet())!!)

    fun addCustom(ctx: Context, host: String) {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h.contains(' ')) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY_CUSTOM, emptySet())!!)
        cur.add(h)
        sp.edit().putStringSet(KEY_CUSTOM, cur).apply()
    }

    fun removeCustom(ctx: Context, host: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY_CUSTOM, emptySet())!!)
        cur.remove(host.trim().lowercase())
        sp.edit().putStringSet(KEY_CUSTOM, cur).apply()
    }

    /**
     * 核心判定：这个 App 问这个域名，要不要拦？
     * 返回命中的 SDK（用来给用户解释"拦了谁"），不拦就返回 null。
     */
    fun hit(ctx: Context, pkg: String, host: String): TrackerDb.Sdk? {
        if (!enabled(ctx)) return null
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h == "localhost") return null
        if (isAppOut(ctx, pkg)) return null

        // 白名单最高优先：用户明确放行的域名，一律绕过（含自定义黑名单）
        if (Pure.firstDomainHit(h, allowDomains(ctx)) != null) return null

        // 用户自加的黑名单优先
        if (Pure.firstDomainHit(h, custom(ctx)) != null) {
            return TrackerDb.Sdk("自定义规则", emptyList(), listOf(h), CAT_AD)
        }

        val sdk = TrackerDb.byDomain(h) ?: return null
        if (!catEnabled(ctx, sdk.category)) return null
        return sdk
    }

    /** 拦一次记一笔（按天清零） */
    fun note(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = dayIndex()
        val lastDay = sp.getInt(KEY_DAY, 0)
        val cur = if (lastDay == today) sp.getInt(KEY_COUNT, 0) else 0
        sp.edit().putInt(KEY_DAY, today).putInt(KEY_COUNT, cur + 1).apply()
    }

    fun todayCount(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (sp.getInt(KEY_DAY, 0) == dayIndex()) sp.getInt(KEY_COUNT, 0) else 0
    }

    fun resetCount(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DAY, dayIndex()).putInt(KEY_COUNT, 0).apply()
    }

    private const val KEY_ALLOW = "allow_domains"

    private fun dayIndex(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
