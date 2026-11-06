package com.privacyscope.local.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 统一的「特权命令通道」。
 *
 * App 自己是普通身份，要看/改别的 App 的权限，必须借一个更高的身份：
 *  - root（su）：全功能，但多数手机没有；
 *  - 无线调试（adb，uid 2000）：Android 11 起手机自带 adbd，配对连上就能用，
 *    和电脑上敲 `adb shell` 是同一个身份。由 core/Adb.kt 提供。
 *
 * 通道优先级：无线调试 → root → 无。
 * 无通道时只能「只看自己 + 静态分析 APK」，界面上不提供改动入口。
 */
object Shell {

    const val MODE_NONE = "none"
    const val MODE_ROOT = "root"
    /** 无线调试（手机自带 adbd，uid 2000）。由 core/Adb.kt 提供。 */
    const val MODE_ADB = "adb"

    @Volatile
    private var _mode: String = MODE_NONE

    /**
     * 当前通道。
     *
     * 读的时候顺带对一下 Adb：只要无线调试已连上就以它为准。
     * 这样即使某处忘了调 detect()，也不会出现「明明连上了、界面却说没通道」的假象。
     */
    val mode: String
        get() {
            if (_mode != MODE_ADB && Adb.isConnected()) _mode = MODE_ADB
            return _mode
        }

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var connectedAt: Long = 0L

    /** 无线调试连上/断开时由 Adb 调用，让界面能显示「已连上多久」。 */
    fun markConnected(on: Boolean) {
        connectedAt = if (on) System.currentTimeMillis() else 0L
        _mode = if (on) MODE_ADB else MODE_NONE
    }

    fun connectedSince(): Long = connectedAt

    fun resetMode() {
        _mode = MODE_NONE
        connectedAt = 0L
    }

    fun describe(): String = when (mode) {
        MODE_ROOT -> "root（su）"
        MODE_ADB -> "无线调试（adb，uid 2000）"
        else -> "无（只能看自己，改不了别人）"
    }

    fun detect(): String {
        if (Adb.isConnected()) {
            _mode = MODE_ADB
            return mode
        }
        if (tryRoot()) {
            _mode = MODE_ROOT
            return mode
        }
        _mode = MODE_NONE
        return mode
    }

    private fun tryRoot(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.contains("uid=0")
    } catch (t: Throwable) {
        false
    }

    /** 同步执行一条 shell 命令，返回 stdout+stderr 合并文本；无通道时返回空串并在 lastError 里说明。 */
    fun exec(cmd: String, timeoutMs: Long = 20000): String {
        when (mode) {
            MODE_ROOT -> return runLocal(arrayOf("su", "-c", cmd), timeoutMs)
            MODE_ADB -> return Adb.exec(cmd, timeoutMs.toInt()) ?: ""
            else -> {
                lastError = "没有特权通道（既没有 root，也没有连上无线调试）"
                return ""
            }
        }
    }

    private fun runLocal(argv: Array<String>, timeoutMs: Long): String = try {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        val worker = Thread {
            try {
                reader.forEachLine { out.append(it).append('\n') }
            } catch (_: Throwable) {
            }
        }
        worker.isDaemon = true
        worker.start()
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            p.destroy()
            lastError = "命令超时（${timeoutMs}ms）：" + argv.joinToString(" ")
        }
        worker.join(2000)
        out.toString()
    } catch (t: Throwable) {
        lastError = t.message ?: t.javaClass.simpleName
        ""
    }
}
