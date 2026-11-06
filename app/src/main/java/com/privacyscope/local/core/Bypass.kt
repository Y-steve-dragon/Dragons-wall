package com.privacyscope.local.core

import android.content.Context

/**
 * 「完全放行」名单 —— v0.6
 *
 * 和 `Blocker.isAppOut()`（网络放行）不是一回事：
 *
 * | | 网络放行（第 1 张单子） | 完全放行（这个文件） |
 * |---|---|---|
 * | 还进我们的隧道吗 | 进（DNS 仍经过我们转发） | **不进**（系统级按 App 绕过） |
 * | 会不会多一跳 | 会，我们代它转发一次 DNS | **不会，直连系统解析器** |
 * | 我们还能看见它的域名吗 | 能（只是不拦） | **看不见**（这就是代价） |
 * | 适合谁 | 只是别拦我 | 游戏、直播、对延迟敏感、要求零干扰 |
 *
 * 实现方式：`VpnService.Builder.addDisallowedApplication(pkg)` —— 系统会把该 App 的
 * 全部流量（含 DNS）排除在 VPN 之外，等于它完全没被这个软件碰过。
 * 代价是：**改了这份名单必须重建一次隧道**（几百毫秒），见 CaptureVpnService.restart()。
 */
object Bypass {

    private const val PREFS = "bypass"
    private const val KEY = "pkgs"

    fun list(ctx: Context): Set<String> =
        HashSet(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())!!)

    fun has(ctx: Context, pkg: String): Boolean = list(ctx).contains(pkg)

    /** 返回 true 表示确实变了（没变就不用重建隧道） */
    fun set(ctx: Context, pkg: String, on: Boolean): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY, emptySet())!!)
        val changed = if (on) cur.add(pkg) else cur.remove(pkg)
        if (changed) sp.edit().putStringSet(KEY, cur).apply()
        return changed
    }
}
