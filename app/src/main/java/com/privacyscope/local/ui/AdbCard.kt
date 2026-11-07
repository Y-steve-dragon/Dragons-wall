package com.privacyscope.local.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.privacyscope.local.core.Adb
import com.privacyscope.local.core.Shell

/**
 * 「无线调试」通道卡片（v0.9 起）。
 *
 * 三步，一步都不能省：
 *   ① 去系统设置打开「无线调试」——开关只能人来点，App 改不了（也不需要）。
 *   ② 在系统弹窗里读 6 位配对码，连同端口填进来 → 配对（建立信任）。
 *   ③ 填连接端口 → 连上 adbd。
 *
 * 设计原则：**配对与连接都是用户明确的动作**，不自动、不静默重试；
 * 出错时把系统原文丢给用户看，而不是编一句「请稍后再试」。
 */
object AdbCard {

    fun build(act: Activity): View {
        val card = Ui.card(act)
        card.addView(Ui.title(act, "无线调试通道（不用 root，能改别的 App 的权限）"))

        val status = Ui.mono(act, Adb.statusLine(), 12f, Ui.C_AMBER)
        card.addView(status)

        // 暴露计时（v0.9.17）：无线调试开着 = adbd 对外服务着，应该是一个短窗口，不是常开状态
        val (exposureText, exposureWarn) = Adb.exposureLine()
        val exposure = Ui.mono(act, "▸ 暴露时长：" + exposureText, 11.5f, if (exposureWarn) Ui.C_WARN else Ui.C_DIM)
        card.addView(exposure)

        // 当前网络性质（v0.9.17）：决定「这个通道此刻暴露在什么人面前」
        val net = com.privacyscope.local.core.NetAudit.inspect(act)
        val netLine = Ui.mono(
            act,
            "▸ 当前网络：" + net.transport + " " + net.detail,
            11.5f,
            if (net.risky) Ui.C_WARN else Ui.C_DIM
        )
        card.addView(netLine)
        card.addView(Ui.mono(act, "   " + net.advice, 11f, if (net.risky) Ui.C_AMBER else Ui.C_DIM))

        card.addView(
            Ui.note(
                act,
                "为什么显示计时和网络",
                "「无线调试」不是普通开关：打开后手机在本网络上开了一个 adb 服务（uid 2000 的 shell），" +
                    "同网设备多了一个可探测的目标。所以：\n" +
                    "① 计时：超过 10 分钟就提醒你断开 —— 它应该是一个几十秒的窗口，而不是常开状态；\n" +
                    "② 网络：开放网络（无密码）风险最高，公共热点次之，家庭加密网最低；\n" +
                    "③ 纯移动数据下多数机型的无线调试起不来，此时「看」类功能照常，改权限请回 Wi-Fi。\n" +
                    "本 App 只做提示与计时，不会自动改系统设置，也不扫描同网设备。"
            )
        )

        // 计时每 30 秒自己刷新一次；视图离开窗口就停，不在后台空转
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val (t, warn) = Adb.exposureLine()
                exposure.text = "▸ 暴露时长：" + t
                exposure.setTextColor(if (warn) Ui.C_WARN else Ui.C_DIM)
                h.postDelayed(this, 30_000L)
            }
        }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                h.removeCallbacks(tick)
                h.post(tick)
            }

            override fun onViewDetachedFromWindow(v: View) {
                h.removeCallbacks(tick)
            }
        })

        // 最容易卡住的一步：主界面上看不到 6 位码，必须点「使用配对码配对设备」才会弹出来
        card.addView(
            Ui.mono(
                act,
                "▸ 6 位码在哪：点系统页面里的「使用配对码配对设备」，弹出的框里才有\n" +
                    "   ① 6 位配对码  ② 配对端口（那个框别关，30 秒内填完）\n" +
                    "▸ 主界面上那串 IP:端口 里的端口，是第三步要填的「连接端口」",
                11.5f,
                Ui.C_SEED
            )
        )

        card.addView(
            Ui.note(
                act,
                "这条路是什么",
                "安卓 11 起，手机自己能跑一个 adb 服务（uid 2000 的 shell）。把它连上，我们就有了 uid 2000 的 shell 身份，可以替某个 App 关掉某些能力。\n" +
                    "代价：手机重启后无线调试通常会被关掉，需要再来配一次（约 30 秒）。"
            )
        )

        // ① 打开设置
        card.addView(
            Ui.button(act, "① 打开「无线调试」设置", filled = false) {
                val ok = runCatching {
                    act.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
                if (ok.isFailure) {
                    status.text = "打不开开发者选项：这台手机可能还没开启（设置→关于手机→连点版本号 7 次）"
                    status.setTextColor(Ui.C_WARN)
                }
            }
        )

        card.addView(Ui.spacer(act, 8))
        card.addView(Ui.mono(act, "② 把系统弹窗里的信息填进来", 12f, Ui.C_SEED))

        val pairPort = input(act, "配对端口（弹窗里的 IP:端口 的后半段）", InputType.TYPE_CLASS_NUMBER)
        val code = input(act, "6 位配对码", InputType.TYPE_CLASS_NUMBER)
        val connPort = input(act, "连接端口（无线调试主页上那个端口，与配对端口不同）", InputType.TYPE_CLASS_NUMBER)

        val sp = Adb.savedPairPort()
        if (sp > 0) pairPort.setText(sp.toString())
        val sc = Adb.savedConnPort()
        if (sc > 0) connPort.setText(sc.toString())

        card.addView(pairPort)
        card.addView(code)
        card.addView(connPort)

        val row = Ui.row(act)
        row.addView(
            Ui.button(act, "② 配对") {
                val p = pairPort.text.toString().trim().toIntOrNull()
                if (p == null) { fail(status, "配对端口要填数字"); return@button }
                run(act, status, "配对中…（这一步会与手机的 adbd 做加密握手，约 2~5 秒）") {
                    val err = Adb.pair(p, code.text.toString())
                    if (err.isEmpty()) "配对成功。接着填下面的连接端口，点「③ 连接」" else err
                }
            }
        )
        row.addView(
            Ui.button(act, "③ 连接", filled = true) {
                val p = connPort.text.toString().trim().toIntOrNull()
                if (p == null) { fail(status, "连接端口要填数字"); return@button }
                run(act, status, "连接中…") {
                    val err = Adb.connect(p)
                    if (err.isEmpty()) {
                        Shell.detect()
                        "已连上：" + Shell.describe() + "。现在回「清单」页，点开任意 App 就能关它的能力了"
                    } else err
                }
            }
        )
        row.addView(
            Ui.button(act, "断开", filled = false) {
                Adb.disconnect()
                Shell.detect()
                status.text = "已断开。当前通道：" + Shell.describe()
                status.setTextColor(Ui.C_AMBER)
            }
        )
        card.addView(row)

        card.addView(
            Ui.note(
                act,
                "填错了会怎样",
                "端口填错 → 「连接失败：connection refused」；码填错 → 「配对失败」；都没错但连不上 → 多半是无线调试被系统关了，回 ① 重开。\n" +
                    "配对只建立信任、不建立会话，所以配对成功后必须再点一次「③ 连接」。"
            )
        )
        return card
    }

    /** 后台跑，回到主线程更新状态；失败时把原文显示出来（不吞异常） */
    private fun run(act: Activity, status: TextView, busy: String, job: () -> String) {
        status.text = busy
        status.setTextColor(Ui.C_DIM)
        Thread {
            val msg = runCatching(job).getOrElse { e -> "出错了：" + (e.message ?: e.javaClass.simpleName) }
            act.runOnUiThread {
                status.text = msg
                val ok = msg.startsWith("配对成功") || msg.startsWith("已连上")
                status.setTextColor(if (ok) Ui.C_SEED else Ui.C_WARN)
            }
        }.start()
    }

    private fun fail(status: TextView, msg: String) {
        status.text = msg
        status.setTextColor(Ui.C_WARN)
    }

    private fun input(act: Activity, hint: String, type: Int): EditText {
        val e = EditText(act)
        e.hint = hint
        e.inputType = type
        e.textSize = 12.5f
        e.typeface = android.graphics.Typeface.MONOSPACE
        e.setTextColor(Ui.C_INK)
        e.setHintTextColor(Ui.C_DIM)
        e.setBackground(Ui.round(Ui.C_BG, Ui.dp(act, 8), Ui.dp(act, 1), Ui.C_LINE))
        e.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8))
        e.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Ui.dp(act, 6)
        e.layoutParams = lp
        e.setSingleLine(true)
        return e
    }
}
