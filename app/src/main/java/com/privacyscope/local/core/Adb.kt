package com.privacyscope.local.core

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 无线调试通道（Android 11+ 自带的 adbd）。
 *
 * 为什么需要它：普通 App 没有权限改别的 App 的权限，而这个身份能。
 * 而「无线调试」让手机自己跑着一个 adbd（uid 2000 的 shell），只要我们能像电脑那样连上去，
 * 就拿到了同样的身份。本文件就是把「像电脑那样连上去」这件事做出来。
 *
 * 底层用 Kadb（Apache-2.0，Maven Central），它实现了 adb 协议与 SPAKE2 配对。
 *
 * 三条硬规矩（都在这里体现）：
 *  1. **配对必须由人来做**：配对码只能在系统那个弹窗里看到，我们读不到，也不该读。
 *  2. **密钥要留在本机**：配对成功后安卓记住的是「我们这把钥匙」，钥匙丢了就得重配。
 *     所以私钥存进 filesDir/adbkey/adbkey.pem，绝不外传。
 *  3. **只跑白名单命令**：exec 前面有 checkCommand()，只允许 appops / pm 的固定形态。
 */
object Adb {

    private const val PREF = "adb_wireless"
    private const val K_PAIR_PORT = "pair_port"
    private const val K_CONN_PORT = "conn_port"

    private var kadb: Kadb? = null
    private var ctx: Context? = null
    private var keyReady = false

    /**
     * 「已经验证过身份」的标记。
     *
     * 为什么不干脆每次调 connectionCheck()：它是懒连接检查，握手还没发生时会返回 false。
     * 后果很严重 —— Shell.detect() 会因此把刚连好的通道误判成「无通道」，
     * 于是改权限全部失败、界面显示「没有通道」。所以连上（真跑过 id 见到 uid=2000）之后，
     * 就以这个 flag 为准，直到 close() 或某条命令抛异常才复位。
     */
    @Volatile
    private var linked = false

    /** 上次的连接目标（只存本机回环地址与端口，不存任何凭据） */
    var host: String = "127.0.0.1"
        private set
    var connPort: Int = 0
        private set

    /** 最近一次错误原文（给用户看，不加工、不吞掉） */
    var lastError: String = ""
        private set

    /** 最近一条命令的完整对话（命令 / 退出码 / stdout / stderr）。排错时原样给用户看。 */
    @Volatile
    var lastRaw: String = ""
        private set

    fun attach(context: Context) {
        if (ctx == null) ctx = context.applicationContext
        ensureKeyStore()
        val sp = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        connPort = sp.getInt(K_CONN_PORT, 0)
    }

    /** 配对端口与连接端口都记住，省得每次重填（但它们每次重启后会变，所以只是默认值） */
    fun rememberPorts(pairPort: Int, connPort: Int) {
        val c = ctx ?: return
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(K_PAIR_PORT, pairPort)
            .putInt(K_CONN_PORT, connPort)
            .apply()
        this.connPort = connPort
    }

    fun savedPairPort(): Int =
        ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.getInt(K_PAIR_PORT, 0) ?: 0

    fun savedConnPort(): Int = connPort

    /** 私钥落盘：配对成功后，安卓记住的就是这把钥匙 */
    private fun ensureKeyStore() {
        if (keyReady) return
        val c = ctx ?: return
        runCatching {
            val dir = File(c.filesDir, "adbkey")
            if (!dir.exists()) dir.mkdirs()
            val path = File(dir, "adbkey.pem").absolutePath.toPath()
            KadbCert.configure(OkioFilePrivateKeyStore(path, FileSystem.SYSTEM))
            keyReady = true
        }.onFailure {
            lastError = "初始化本机密钥失败：" + (it.message ?: it.javaClass.simpleName)
        }
    }

    /** 是否处于「已验证身份」的连接状态。**不触发任何网络动作**，可以在主线程安全调用。 */
    fun isConnected(): Boolean = linked && kadb != null

    fun statusLine(): String {
        if (isConnected()) return "已连接 $host:$connPort（shell 身份 uid 2000）"
        return if (lastError.isEmpty()) "未连接" else "未连接 · " + lastError
    }

    /**
     * 配对。**必须由用户从系统弹窗里读出 6 位码后手动输入**——这是安全设计，不是缺陷。
     * @return 空串表示成功，否则是错误原文
     */
    fun pair(pairPort: Int, code: String): String {
        ensureKeyStore()
        val bad = validatePort(pairPort) ?: validateCode(code)
        if (bad != null) return bad
        return try {
            runBlocking(Dispatchers.IO) { Kadb.pair(host, pairPort, code.trim()) }
            lastError = ""
            ""
        } catch (e: Throwable) {
            lastError = "配对失败：" + (e.message ?: e.javaClass.simpleName)
            lastError
        }
    }

    /**
     * 连上 adbd。
     *
     * 两个坑都在这里处理：
     *  1. 有些机型 adbd 只监听某个网卡，所以 127.0.0.1 不行就换本机局域网 IP 再试；
     *  2. **不要用 connectionCheck() 当判据** —— 连接是懒建立的，握手还没发生它就返回 false，
     *     会把「其实能用」误判成失败。改成真跑一条 `id`：输出里有 uid=2000 才算通。
     */
    fun connect(port: Int): String {
        val bad = validatePort(port)
        if (bad != null) return bad
        close()
        val hosts = candidateHosts()
        val log = StringBuilder()
        var lastMsg = ""
        for (h in hosts) {
            val r = runCatching {
                val k = Kadb.create(h, port)
                val id = runBlocking(Dispatchers.IO) { k.shell("id") }
                val out = (id.output + " " + id.errorOutput).trim()
                if (!out.contains("uid=2000")) {
                    runCatching { k.close() }
                    throw IllegalStateException("握手通了但身份不对：" + out.take(80))
                }
                k
            }
            if (r.isSuccess) {
                kadb = r.getOrThrow()
                linked = true
                Shell.markConnected(true)
                host = h
                connPort = port
                rememberPorts(savedPairPort(), port)
                lastError = ""
                return ""
            }
            val e = r.exceptionOrNull()
            lastMsg = (e?.message ?: e?.javaClass?.simpleName ?: "未知错误")
            if (log.isNotEmpty()) log.append("；")
            log.append(h).append(" → ").append(lastMsg)
        }
        lastError = "连接失败（试了 " + hosts.size + " 个地址）：" + log
        return lastError
    }

    /** 连上之后跑一条 id + SDK，用来证明「真的拿到了 shell 身份」 */
    fun diagnose(): String {
        val k = kadb ?: return "还没连上，先点「③ 连接」"
        val id = runCatching { runBlocking(Dispatchers.IO) { k.shell("id") }.output.trim() }.getOrNull()
        val sdk = runCatching { runBlocking(Dispatchers.IO) { k.shell("getprop ro.build.version.sdk") }.output.trim() }.getOrNull()
        return "id：" + (id ?: "取不到") + "\nSDK：" + (sdk ?: "取不到")
    }

    /** 本机可能的监听地址：先回环，再各个网卡的 IPv4 */
    private fun candidateHosts(): List<String> {
        val out = mutableListOf("127.0.0.1")
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (a is Inet4Address && !h.startsWith("127.") && !out.contains(h)) out.add(h)
                }
            }
        }
        return out
    }

    fun disconnect() {
        close()
        lastError = ""
    }

    private fun close() {
        linked = false
        Shell.markConnected(false)
        runCatching { kadb?.close() }
        kadb = null
    }

    /**
     * 用上次记住的端口静默重连一次（App 被系统杀掉后，进程里的 socket 没了，但配对密钥还在）。
     * @return 空串=重连成功；否则是原因
     */
    fun autoReconnect(): String {
        if (isConnected()) return ""
        val p = savedConnPort()
        if (p < 1024 || p > 65535) return "没有记住过连接端口"
        val err = connect(p)
        return if (err.isEmpty()) "" else "自动重连失败：" + err
    }

    /** 跑一条命令；未连接返回 null（调用方据此提示，而不是假装成功） */
    fun exec(cmd: String, timeoutMs: Int = 20000): String? {
        val k = kadb ?: return null
        val refused = checkCommand(cmd)
        if (refused != null) {
            lastError = refused
            return null
        }
        return try {
            // 必须切到 IO 线程：Kadb 的 shell() 是同步阻塞的 socket 调用，
            // 在主线程直接调会抛 NetworkOnMainThreadException（v0.9.5 就是这么全失败的）。
            val r = runBlocking(Dispatchers.IO) { k.shell(cmd) }
            lastError = ""
            lastRaw = buildString {
                append("$ ").append(cmd).append('\n')
                append("exit=").append(r.exitCode).append('\n')
                append("stdout: ").append(if (r.output.isBlank()) "（空）" else r.output.trim()).append('\n')
                append("stderr: ").append(if (r.errorOutput.isBlank()) "（空）" else r.errorOutput.trim())
            }
            if (r.exitCode != 0 && r.errorOutput.isNotBlank()) r.errorOutput else r.output
        } catch (e: Throwable) {
            lastError = "命令执行失败：" + (e.message ?: e.javaClass.simpleName)
            lastRaw = "$ " + cmd + "\n异常：" + lastError
            close()
            null
        }
    }

    /**
     * 白名单：只允许读 AppOps、改 AppOps、撤销/授予运行时权限。
     * 这不是「防君子」，是防我们自己：一旦这里放宽，App 就成了一个能执行任意 shell 的壳。
     */
    /** 白名单判定搬到 [Pure.checkCommand]：纯函数，有单测钉住「哪些必须被拒」。 */
    private fun checkCommand(cmd: String): String? = Pure.checkCommand(cmd)

    private fun validatePort(p: Int): String? {
        if (p < 1024 || p > 65535) return "端口不合法（应在 1024~65535，例如 37755）"
        return null
    }

    private fun validateCode(code: String): String? {
        val c = code.trim()
        if (c.length != 6 || !c.all { it.isDigit() }) return "配对码是 6 位数字，请照抄系统弹窗里的那 6 位"
        return null
    }
}
