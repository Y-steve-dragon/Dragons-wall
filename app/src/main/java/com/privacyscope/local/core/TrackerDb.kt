package com.privacyscope.local.core

/**
 * 本地 SDK / 追踪域名库（内置，不联网）。
 *
 * 说明：这不是「完整宇宙」。它是我按公开资料整理的一批常见 SDK 的**类名前缀**与**网络域名**，
 * 命中只代表「这个 App 里打包了这套 SDK」，不代表它一定在传数据——判断要看时间线里的实际连接。
 */
object TrackerDb {

    data class Sdk(
        val name: String,
        /** dex 里出现的类名前缀（用于扫 APK） */
        val prefixes: List<String>,
        /** 这条 SDK 常见的上报域名关键字（用于匹配 DNS/SNI 观测） */
        val domains: List<String>,
        /** 1=统计/广告 2=推送 3=支付/风控 4=崩溃/性能 */
        val category: Int
    )

    val ALL: List<Sdk> = listOf(
        Sdk("友盟+", listOf("com.umeng", "com.uc.crashsdk"), listOf("umeng.com", "umengcloud.com", "umeng.co"), 1),
        Sdk("腾讯移动分析", listOf("com.tencent.stat", "com.tencent.beacon", "com.tencent.matrix"), listOf("pingjs.qq.com", "beacon.qq.com", "sngapm.qq.com"), 1),
        Sdk("腾讯广告", listOf("com.qq.e.ads", "com.qq.e.comm"), listOf("gdt.qq.com", "e.qq.com"), 1),
        Sdk("穿山甲/字节", listOf("com.bytedance.sdk", "com.ss.android", "com.bytedance.applog"), listOf("pangolin-sdk-toutiao.com", "snssdk.com", "bytedance.com", "toutiao.com"), 1),
        Sdk("快手联盟", listOf("com.kwad"), listOf("kwad.com", "kuaishou.com"), 1),
        Sdk("百度统计/广告", listOf("com.baidu.mobstat", "com.baidu.mobads"), listOf("hm.baidu.com", "mobads.baidu.com"), 1),
        Sdk("阿里/友盟统计", listOf("com.alibaba.sdk.android", "com.alipay.android.phone"), listOf("alog.umeng.com", "mob.alipay.com"), 1),
        Sdk("神策数据", listOf("com.sensorsdata.analytics"), listOf("sensorsdata.cn", "shence.com"), 1),
        Sdk("GrowingIO", listOf("com.growingio"), listOf("growingio.com"), 1),
        Sdk("数美反欺诈", listOf("com.ishumei"), listOf("ishumei.com", "fengkongcloud.com"), 3),
        Sdk("同盾科技", listOf("com.trustdecision", "cn.tongdun"), listOf("tongdun.cn", "trustdecision.com"), 3),
        Sdk("极光推送", listOf("cn.jiguang", "cn.jpush"), listOf("jpush.cn", "jiguang.cn", "jpush.io"), 2),
        Sdk("个推", listOf("com.igexin"), listOf("igexin.com", "getui.com"), 2),
        Sdk("小米推送", listOf("com.xiaomi.mipush", "com.xiaomi.push"), listOf("mipush.xiaomi.com"), 2),
        Sdk("华为推送/HMS", listOf("com.huawei.hms", "com.huawei.agconnect"), listOf("huawei.com", "hicloud.com"), 2),
        Sdk("OPPO 推送", listOf("com.heytap.msp", "com.coloros.mcssdk"), listOf("push.oppo.com"), 2),
        Sdk("vivo 推送", listOf("com.vivo.push"), listOf("push.vivo.com.cn"), 2),
        Sdk("魅族推送", listOf("com.meizu.cloud.pushsdk"), listOf("push.meizu.com"), 2),
        Sdk("Firebase/FCM", listOf("com.google.firebase", "com.google.android.gms.measurement"), listOf("firebaseinstallations.googleapis.com", "fcm.googleapis.com", "app-measurement.com"), 1),
        Sdk("Google Play 服务", listOf("com.google.android.gms"), listOf("google-analytics.com", "gstatic.com"), 1),
        Sdk("Google Ads", listOf("com.google.android.gms.ads"), listOf("googleads.g.doubleclick.net", "pagead2.googlesyndication.com"), 1),
        Sdk("Facebook SDK", listOf("com.facebook.appevents", "com.facebook.login", "com.facebook.ads"), listOf("graph.facebook.com", "facebook.com"), 1),
        Sdk("AppsFlyer", listOf("com.appsflyer"), listOf("appsflyer.com"), 1),
        Sdk("Adjust", listOf("com.adjust.sdk"), listOf("adjust.com"), 1),
        Sdk("AppLovin", listOf("com.applovin"), listOf("applovin.com"), 1),
        Sdk("Unity Ads", listOf("com.unity3d.ads", "com.unity3d.services"), listOf("unityads.unity3d.com"), 1),
        Sdk("Mintegral/汇量", listOf("com.mbridge", "com.mintegral"), listOf("mintegral.com"), 1),
        Sdk("Vungle/Liftoff", listOf("com.vungle"), listOf("vungle.com"), 1),
        Sdk("IronSource", listOf("com.ironsource"), listOf("ironsrc.com", "supersonicads.com"), 1),
        Sdk("AdMob 中介", listOf("com.google.ads.mediation"), listOf("admob.com"), 1),
        Sdk("Bugly 崩溃", listOf("com.tencent.bugly"), listOf("bugly.qq.com"), 4),
        Sdk("Sentry", listOf("io.sentry"), listOf("sentry.io"), 4),
        Sdk("Tingyun/听云", listOf("com.networkbench"), listOf("tingyun.com"), 4),
        Sdk("阿里云移动", listOf("com.alibaba.sdk.android.push"), listOf("aliyuncs.com"), 2),
        Sdk("微信 SDK", listOf("com.tencent.mm.opensdk"), listOf("weixin.qq.com", "mm.tenpay.com"), 1),
        Sdk("支付宝 SDK", listOf("com.alipay.sdk"), listOf("alipay.com", "alipayobjects.com"), 1),
        Sdk("极验验证", listOf("com.geetest"), listOf("geetest.com"), 3),
        Sdk("中国信通院 OAID", listOf("com.bun.miitmdid", "com.asus.msa"), listOf("msa-alliance.cn"), 3),
        Sdk("ReactNative 桥", listOf("com.facebook.react"), listOf("reactnative.dev"), 4),
        Sdk("Flutter 引擎", listOf("io.flutter"), listOf("flutter.dev"), 4),
        Sdk("ExoPlayer/媒体", listOf("com.google.android.exoplayer"), listOf("googlevideo.com"), 4)
    )

    private val prefixIndex: List<Pair<String, Sdk>> =
        ALL.flatMap { s -> s.prefixes.map { it to s } }.sortedByDescending { it.first.length }

    /** 在一段文本（例如 dex 的字符串池）里找命中的 SDK */
    fun hits(text: String): List<Sdk> {
        val found = LinkedHashMap<String, Sdk>()
        for ((prefix, sdk) in prefixIndex) {
            if (text.contains(prefix)) found.putIfAbsent(sdk.name, sdk)
        }
        return found.values.toList()
    }

    /** 域名 → SDK（反查，用于解释「这条连接是谁发的」） */
    fun byDomain(host: String): Sdk? {
        val h = host.lowercase()
        return ALL.firstOrNull { sdk -> sdk.domains.any { h == it || h.endsWith(".$it") } }
    }

    fun categoryName(c: Int): String = when (c) {
        1 -> "统计/广告"
        2 -> "推送"
        3 -> "风控/标识"
        else -> "崩溃/性能"
    }
}
