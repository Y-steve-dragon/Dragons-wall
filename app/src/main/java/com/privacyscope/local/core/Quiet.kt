package com.privacyscope.local.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 静默异常的记录器（v0.9.14 新增）。
 *
 * 为什么需要它：
 *   工程里有 40 多处 `catch (_: Throwable) { }`。它们让界面不至于崩，代价是「出错时什么都不知道」。
 *   v0.8~v0.9 的连环 bug（清单空白、改权限永远失败、连接误判）全都是被这样吞掉的异常，
 *   最后只能靠用户截图一句「失败了」来猜。这个类的作用只有一个：把「被吞掉的东西」留下来。
 *
 * 设计取舍：
 *   - 只在内存留最近 [MAX] 条（界面上给用户看），同时**追加**写一个 quiet.log（给排障用）；
 *   - 写盘失败绝不抛异常（否则就成了「为了记错误而制造错误」）；
 *   - quiet.log 超过 [MAX_BYTES] 就清空重写，避免无限增长。
 */
object Quiet {

    private const val TAG = "阿龙的墙"
    private const val MAX = 120
    private const val MAX_BYTES = 200_000L
    private const val FILE = "quiet.log"

    private val lines = ArrayDeque<String>()

    @Volatile
    private var dir: File? = null

    /** 在 Activity.onCreate / Application 里调一次，才具备写盘能力。 */
    fun attach(ctx: Context) {
        dir = ctx.applicationContext.filesDir
    }

    /** 记一条。where 用「类.方法」的形式，方便以后 grep。 */
    fun w(where: String, e: Throwable?) {
        val one = where + " · " + (e?.javaClass?.simpleName ?: "?") + "：" + (e?.message ?: "无消息")
        try {
            Log.w(TAG, one, e)
        } catch (_: Throwable) {
        }
        synchronized(lines) {
            lines.addLast(stamp() + "  " + one)
            while (lines.size > MAX) lines.removeFirst()
        }
        val d = dir ?: return
        try {
            val f = File(d, FILE)
            if (f.length() > MAX_BYTES) f.writeText("")
            val body = stamp() + "  " + one + "\n" + (e?.stackTraceToString() ?: "") + "\n"
            f.appendText(body)
        } catch (_: Throwable) {
        }
    }

    fun count(): Int = synchronized(lines) { lines.size }

    /** 给界面用：最近的记录，新的在最后。 */
    fun dump(): String = synchronized(lines) {
        if (lines.isEmpty()) "（没有记录）" else lines.joinToString("\n")
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        val d = dir ?: return
        try {
            File(d, FILE).writeText("")
        } catch (_: Throwable) {
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
