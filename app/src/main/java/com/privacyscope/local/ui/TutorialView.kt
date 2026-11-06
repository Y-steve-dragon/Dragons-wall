package com.privacyscope.local.ui

import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * 教程页（v0.7 从首页长文里拆出来）
 *
 * 全部折叠：点开哪节看哪节。内容写成本文件里的常量，离线可用（不联网取文档）。
 * 只放"怎么用"，不放"怎么实现" —— 实现细节在 手机隐私\路线②-教程\ 里。
 */
class TutorialView(private val act: AppCompatActivity) {

    lateinit var root: View
        private set

    private val sections: List<Pair<String, String>> = listOf(

        "① 六个页签分别干什么" to
            "墙  ——  总览：本机能力、文件变化、拦截开关\n" +
            "流  ——  实时事件，一行一条，越往下越新（可自动跟随）\n" +
            "清单 —— 每个 App 装了什么、要了什么权限、打包了哪些 SDK\n" +
            "报告 —— 汇总与导出\n" +
            "VPN —— 开/关网络观测（单独一页，因为会与别的 VPN 抢通道）\n" +
            "教程 —— 就是你正在看的这一页",

        "② 三步上手" to
            "1) 清单页扫一遍：先知道谁要了位置、通讯录、设备标识；\n" +
            "2) VPN 页开启网络观测：之后谁在连哪个域名，都会落到「流」里；\n" +
            "3) 想改别人的权限：墙页「无线调试通道」配对连上（不连也能看，只是改不了）。",

        "③ 三种授权，各管什么" to
            "网络观测（VPN）：能看见域名去向、能拦域名。系统会弹窗确认一次。\n" +
            "使用情况访问：能知道前台是哪个 App（不给就只看网络与权限）。\n" +
            "无线调试 / root：能改别的 App 的权限开关、能冻结。不给就只能看。\n" +
            "全部文件访问（可选）：全盘模式才需要；受限模式只读媒体库。",

        "④ 白名单四张单子" to
            "① 放行的 App：域名不拦，但还走隧道（DNS 由我们转发一次）。\n" +
            "② 完全放行的 App：整个不进隧道，零额外一跳 —— 游戏、直播选这个。代价是我们看不见它的域名。\n" +
            "③ 白名单域名：只放过这几个域名，子域名也算。\n" +
            "④ 自定义拦截域名：内置 42 条之外你想拦的。\n" +
            "优先级：② > ① > ③ > ④ > 内置规则 > 分类开关。",

        "⑤ 流里的红字是什么意思" to
            "红字 = 这一条命中了拦截规则（内置追踪器域名），或这次请求被我们回了 0.0.0.0。\n" +
            "它只是「标记」，不参与任何决策：红字本身不会去拦什么，也不会去执行什么。\n" +
            "反过来，灰绿字表示看过、没命中、放过去了。",

        "⑥ 拦截到底怎么起作用的" to
            "App 要连广告服务器时，先问系统「xx.umeng.com 是多少」。这个问句（53 端口）会进到我们这里。\n" +
            "命中规则 → 我们回 0.0.0.0 → 它连不上 → 广告与统计发不出去。\n" +
            "所以：它不破解 HTTPS、不改你的流量、不中转内容；加密 DNS（DoH/DoT）它看不见，这是已知局限。",

        "⑦ 会 / 不会 做什么" to
            "会：列清单、看域名去向、按 App 关采集、给勾选的 App 打保护、记时间线、导出报告。\n" +
            "不会：刷机、写系统分区、改 IMEI、伪造指纹、解密别人的 HTTPS、把数据传到任何服务器。\n" +
            "所以它不可能把手机变砖 —— 变砖要动系统分区或 bootloader，那是它没有的权限。",

        "⑧ 常见情况对照" to
            "开了 VPN 后网页打不开 → 通知栏点一下重开，或 VPN 页关闭；\n" +
            "某个 App 登录/支付失败 → 白名单里把它放行（① 或 ②）；\n" +
            "某个 App 完全没网 → 清单页看是不是撤销了它的 INTERNET，点「恢复快照」；\n" +
            "看不到任何事件 → 先看墙页「实时观测」是否运行、VPN 是否开启。",

        "⑨ 数据在哪、怎么带走" to
            "全部在本机的 App 私有目录（privacy.db + 首选项），不联网、不上传。\n" +
            "报告页可以导出成本机文件；也可以用 adb 把 /data/data/com.privacyscope.local/ 里\n" +
            "databases/privacy.db 和 shared_prefs/ 拷出来。卸载即清空。",

        "⑩ 它做不到的事（照实说）" to
            "看不见 HTTPS 正文 —— 只能看见你连了哪个域名；\n" +
            "拦不到写死 IP 的 App —— 不查域名就没经过我们；\n" +
            "看不见加密 DNS（DoH/DoT）的域名；\n" +
            "不能阻止「App 把数据存本地再等以后发」—— 只能等它发的时候看见。"
    )

    fun build(): View {
        val host = Ui.column(act)
        val pad = Ui.dp(act, 16)
        host.setPadding(pad, pad, pad, pad)
        host.setBackgroundColor(Ui.C_BG)

        host.addView(Ui.mono(act, "▍MANUAL // 教程", 16f, Ui.C_SEED).also {
            it.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        host.addView(Ui.body(act, "点标题展开 · 全部离线 · 只用大白话"))
        host.addView(Ui.spacer(act, 8))

        for ((i, s) in sections.withIndex()) {
            host.addView(Ui.foldText(act, s.first, s.second, open = i == 0))
        }

        host.addView(
            Ui.foldText(
                act, "⑪ 术语速查（点开）",
                "AppOps —— 系统里「某个 App 有没有用某个权限」的开关表；改它等于改权限，不用重装。\n" +
                    "DNS  —— 把域名翻译成 IP 的服务，53 端口。\n" +
                    "沉洞 —— 把域名回成 0.0.0.0，让连接直接失败。\n" +
                    "无线调试 —— 手机自带的 adbd，连上就借到它的 shell 身份（uid 2000），不需要 root。\n" +
                    "快照 —— 改权限前把原状态存一份，随时能一键还原。"
            )
        )

        val sc = Ui.scroll(act)
        sc.addView(host)
        root = sc
        return sc
    }
}
