package com.privacyscope.local.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext

/**
 * 实时观测。三条来源：
 *  1. 前台切换：`dumpsys activity activities` 取当前置顶的包名（2 秒一次）
 *  2. 网络去向：本地 VPN 解析出的 DNS 查询 / TLS SNI（由 CaptureVpnService 推过来）
 *  3. 权限状态变化：定时对「勾选的关注列表」做 appops 差分
 *
 * 诚实说明：Android 不给第三方 App「某时某刻调用了某权限」的原始事件
 * （那个 API 是系统签名的）。所以这里的「实时」是**近似**：
 * 前台+网络是真实的，权限调用是「状态变化」而不是「每一次调用」。
 */
object Monitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    var running: Boolean = false
        private set

    /** UI 用：有新事件时回调（主线程自己 post） */
    @Volatile
    var onEvent: ((Store.Event) -> Unit)? = null

    private const val TICK_MS = 2000L
    private const val PERM_EVERY = 5   // 每 5 圈做一次权限差分（约 10 秒）

    fun start(ctx: Context) {
        if (running) return
        running = true
        job = scope.launch { loop(ctx.applicationContext) }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    private suspend fun loop(ctx: Context) {
        val store = Store(ctx)
        var lastPkg = ""
        var tick = 0
        var permBase: Map<String, Map<String, String>> = emptyMap()

        while (currentCoroutineContext().isActive) {
            val out = Shell.exec(
                "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity|ResumedActivity'"
            )
            val pkg = Regex("""\b([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)/""").find(out)?.groupValues?.get(1)
            if (!pkg.isNullOrBlank() && pkg != lastPkg) {
                lastPkg = pkg
                push(store, pkg, "前台", "切到前台")
            }

            tick++
            if (tick % PERM_EVERY == 0) {
                try {
                    val watched = WatchList.get(ctx)
                    if (watched.isNotEmpty()) {
                        val now = HashMap<String, Map<String, String>>()
                        for (p in watched) now[p] = AppOps.read(p)
                        if (permBase.isNotEmpty()) {
                            for ((p, ops) in now) {
                                val old = permBase[p] ?: continue
                                for ((op, mode) in ops) {
                                    val prev = old[op]
                                    if (prev != null && prev != mode) {
                                        push(store, p, "权限状态", "$op: $prev → $mode")
                                    }
                                }
                            }
                        }
                        permBase = now
                    }
                } catch (e: Throwable) {
                    Quiet.w("Monitor.前台取包", e)
                }
            }

            delay(TICK_MS)
        }
    }

    /** 外部（VPN 服务）推一条事件 */
    fun report(ctx: Context, pkg: String, kind: String, detail: String) {
        push(Store(ctx), pkg, kind, detail)
    }

    private fun push(store: Store, pkg: String, kind: String, detail: String) {
        val ts = System.currentTimeMillis()
        try {
            store.addEvent(ts, pkg, kind, detail)
        } catch (e: Throwable) {
            Quiet.w("Monitor.记账", e)
            return
        }
        onEvent?.invoke(Store.Event(0, ts, pkg, kind, detail))
    }
}
