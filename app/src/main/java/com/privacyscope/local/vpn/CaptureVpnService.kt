package com.privacyscope.local.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.privacyscope.local.MainActivity
import com.privacyscope.local.core.Bypass
import com.privacyscope.local.core.Monitor
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 网络观测。设计上刻意「只截 DNS，不放默认路由」：
 *
 *  - 只把 10.111.222.2/32 这一条路引到隧道里，其余流量照常走系统网卡
 *    → 不开这个 App 也能上网，开了也不会突然断网
 *  - 应用发出的 DNS 查询落到隧道里，我们用**自己的 socket**（走真实网络）转发给
 *    系统当前 DNS，再把答复写回隧道
 *    → 域名照常解析，同时我们看到了「谁要去哪」
 *
 * 为什么不截 TLS：那需要完整的 TCP 栈或中间人证书，属于「解密别人的连接」，
 * 本项目不做。所以这里只能看到域名，看不到路径与内容——这一点在界面上也写明。
 */
class CaptureVpnService : VpnService() {

    companion object {
        @Volatile
        var running = false
            private set

        private const val ACTION_START = "ps.start"
        private const val ACTION_STOP = "ps.stop"
        private const val ACTION_RESTART = "ps.restart"
        private const val CH_ID = "ps_vpn"
        private const val NOTI_ID = 4101
        private const val VDNS = "10.111.222.2"

        fun start(ctx: Context) {
            val i = Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }

        /**
         * 重建隧道：改「完全放行」名单后必须调它。
         * 因为 addDisallowedApplication 是建隧道时一次性生效的，运行中改不了。
         * 重建期间（几百毫秒）域名解析会短暂失败一下，App 自己会重试，不影响正在连的 TCP 会话
         * （那些会话本来就不经过我们）。
         */
        fun restart(ctx: Context) {
            if (!running) return
            val i = Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile
    private var alive = false
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopTunnel()
                startTunnel()
                return START_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun notifyMe(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "网络观测", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CH_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setContentTitle("阿龙的墙正在观测网络去向")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            startForeground(NOTI_ID, n)
        } catch (_: Throwable) {
        }
    }

    private fun startTunnel() {
        if (alive) return
        notifyMe("只解析域名，不拦截内容")
        val b = Builder()
        b.setSession("阿龙的墙网络观测")
        b.addAddress("10.111.222.1", 32)
        b.addDnsServer(VDNS)
        b.addRoute(VDNS, 32)
        // ★ v0.6「完全放行」：把名单里的 App 整个排除在隧道外（系统级按 App 绕过）。
        //   效果 = 它的 DNS 和业务流量都不经过我们，与没装这个软件一样，零额外延时。
        //   代价 = 我们也看不见它的域名了（这正是用户要的「完全不受影响」）。
        for (p in Bypass.list(this)) {
            try {
                b.addDisallowedApplication(p)
            } catch (_: Throwable) {
                // App 已卸载 / 名字非法 → 跳过，不让整个隧道起不来
            }
        }
        b.setBlocking(true)
        if (Build.VERSION.SDK_INT >= 29) {
            b.setMetered(false)
        }
        try {
            tun = b.establish()
        } catch (t: Throwable) {
            tun = null
        }
        if (tun == null) {
            running = false
            stopSelf()
            return
        }
        running = true
        alive = true
        reader = Thread { loop() }.also { it.start() }
    }

    private fun stopTunnel() {
        alive = false
        running = false
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        try { stopForeground(true) } catch (_: Throwable) {}
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun loop() {
        val fd = tun?.fileDescriptor ?: return
        val ins = FileInputStream(fd)
        val buf = ByteArray(4096)
        while (alive) {
            val n = try {
                ins.read(buf)
            } catch (t: Throwable) {
                break
            }
            if (n < 28) continue
            try {
                handlePacket(buf, n)
            } catch (_: Throwable) {
            }
        }
        try { ins.close() } catch (_: Throwable) {}
    }

    /** 只处理 IPv4 + UDP + 目的端口 53；其它包直接丢弃（因为不放默认路由，本来也收不到） */
    private fun handlePacket(p: ByteArray, n: Int) {
        val ver = (p[0].toInt() and 0xF0) shr 4
        if (ver != 4) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < 20 || n < ihl + 8) return
        if ((p[9].toInt() and 0xFF) != 17) return // UDP
        val dstPort = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsLen = (((p[ihl + 4].toInt() and 0xFF) shl 8) or (p[ihl + 5].toInt() and 0xFF)) - 8
        if (dnsLen <= 0) return
        val start = ihl + 8
        if (start + dnsLen > n) return
        val query = p.copyOfRange(start, start + dnsLen)

        val name = parseQName(query)
        val pkg = ownerOf(p) ?: "unknown"
        if (name != null) {
            // ① 先问拦截器：这个域名要不要沉洞？（返回命中的 SDK，不拦就是 null）
            val hit = try {
                com.privacyscope.local.core.Blocker.hit(this, pkg, name)
            } catch (e: Throwable) {
                com.privacyscope.local.core.Quiet.w("Blocker.hit", e)
                null
            }
            if (hit != null) {
                val sink = sinkhole(query)
                if (sink != null) {
                    try {
                        com.privacyscope.local.core.Blocker.note(this)
                    } catch (_: Throwable) {
                    }
                    Monitor.report(this, pkg, "网络", "已拦截 $name（${hit.name}）")
                    writeBack(p, ihl, sink)
                    return
                }
            }
            netLog(pkg, name)
        }

        // 用真实网络转发（系统 DNS 优先）
        val reply = relayDns(query) ?: run {
            return
        }
        writeBack(p, ihl, reply)
    }

    /**
     * 造一个「域名指向 0.0.0.0」的 DNS 应答（沉洞）。
     * 不改原查询字节，只把 header 的 QR/ANCOUNT 改掉，并在问题段后面跟一条 A 记录。
     * 解析不出问题段就返回 null —— 宁可不拦，也不回一个坏包把 App 弄晕。
     */
    /** 沉洞报文构造在 [com.privacyscope.local.core.Pure.buildSinkhole]，有单测。 */
    private fun sinkhole(query: ByteArray): ByteArray? =
        com.privacyscope.local.core.Pure.buildSinkhole(query)

    private fun relayDns(query: ByteArray): ByteArray? {
        val servers = ArrayList<InetAddress>()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.forEach { servers.add(it) }
        } catch (_: Throwable) {
        }
        if (servers.isEmpty()) {
            for (ip in listOf("223.5.5.5", "119.29.29.29")) {
                try { servers.add(InetAddress.getByName(ip)) } catch (_: Throwable) {}
            }
        }
        for (s in servers) {
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 2500
                    sock.send(DatagramPacket(query, query.size, InetSocketAddress(s, 53)))
                    val rb = ByteArray(1500)
                    val rp = DatagramPacket(rb, rb.size)
                    sock.receive(rp)
                    return rb.copyOfRange(0, rp.length)
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 把答复包回写给发起方：交换地址、重算 IPv4 头校验和，UDP 校验和置 0 */
    private fun writeBack(orig: ByteArray, ihl: Int, payload: ByteArray) {
        val fd = tun?.fileDescriptor ?: return
        val total = ihl + 8 + payload.size
        val out = ByteArray(total)
        System.arraycopy(orig, 0, out, 0, ihl)
        // 交换 src/dst
        System.arraycopy(orig, 16, out, 12, 4) // dst -> 新 src
        System.arraycopy(orig, 12, out, 16, 4) // src -> 新 dst
        // 交换端口
        val srcPort = ((orig[ihl].toInt() and 0xFF) shl 8) or (orig[ihl + 1].toInt() and 0xFF)
        val dstPort = ((orig[ihl + 2].toInt() and 0xFF) shl 8) or (orig[ihl + 3].toInt() and 0xFF)
        out[ihl] = (dstPort ushr 8).toByte()
        out[ihl + 1] = dstPort.toByte()
        out[ihl + 2] = (srcPort ushr 8).toByte()
        out[ihl + 3] = srcPort.toByte()
        val udpLen = 8 + payload.size
        out[ihl + 4] = (udpLen ushr 8).toByte()
        out[ihl + 5] = udpLen.toByte()
        out[ihl + 6] = 0
        out[ihl + 7] = 0
        System.arraycopy(payload, 0, out, ihl + 8, payload.size)
        out[2] = (total ushr 8).toByte()
        out[3] = total.toByte()
        // IP 头校验和
        out[10] = 0
        out[11] = 0
        var sum = 0
        var i = 0
        while (i < ihl) {
            sum += ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ck = sum.inv() and 0xFFFF
        out[10] = (ck ushr 8).toByte()
        out[11] = ck.toByte()
        try {
            val os = java.io.FileOutputStream(fd)
            os.write(out)
            os.flush()
        } catch (_: Throwable) {
        }
    }

    /** 解析在 [com.privacyscope.local.core.Pure.qname]，有单测。 */
    private fun parseQName(dns: ByteArray): String? =
        com.privacyscope.local.core.Pure.qname(dns)

    /** 尽力把「这个包属于哪个 App」查出来；查不到就 unknown，不编 */
    private fun ownerOf(p: ByteArray): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val src = InetAddress.getByAddress(p.copyOfRange(12, 16))
            val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))
            val ihl = (p[0].toInt() and 0x0F) * 4
            val srcPort = ((p[ihl].toInt() and 0xFF) shl 8) or (p[ihl + 1].toInt() and 0xFF)
            val dstPort = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
            val uid = cm.getConnectionOwnerUid(17, InetSocketAddress(src, srcPort), InetSocketAddress(dst, dstPort))
            if (uid < 0) return null
            packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        } catch (_: Throwable) {
            null
        }
    }

    private fun netLog(pkg: String, domain: String) {
        try {
            val sdk = com.privacyscope.local.core.TrackerDb.byDomain(domain)
            val detail = if (sdk != null) {
                "查域名 $domain（${sdk.name} · ${com.privacyscope.local.core.TrackerDb.categoryName(sdk.category)}）"
            } else {
                "查域名 $domain"
            }
            Monitor.report(this, pkg, "网络", detail)
        } catch (_: Throwable) {
        }
    }
}
