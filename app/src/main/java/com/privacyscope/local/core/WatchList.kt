package com.privacyscope.local.core

import android.content.Context

/**
 * 「关注列表」：用户勾选要保护的 App。存本机 SharedPreferences，不联网。
 * 只有列表里的 App 才会被定时做权限差分（否则 300 个 App 每 10 秒全查一遍太重）。
 */
object WatchList {

    private const val PREF = "watchlist"
    private const val KEY = "pkgs"

    fun get(ctx: Context): Set<String> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun set(ctx: Context, pkgs: Set<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, pkgs).apply()
    }

    fun toggle(ctx: Context, pkg: String, on: Boolean): Set<String> {
        val cur = get(ctx).toMutableSet()
        if (on) cur.add(pkg) else cur.remove(pkg)
        set(ctx, cur)
        return cur
    }
}
