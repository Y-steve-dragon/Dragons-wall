package com.privacyscope.local.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * 网络性质体检（v0.9.17 起）。
 *
 * 为什么要这个：无线调试通道会把手机的 adbd（shell 服务）暴露在**当前这个网络**上。
 * 同一个 WiFi 里的人因此多了一个可探测、可尝试配对的目标。
 * 所以「现在这个网是什么网」不是背景信息，而是这条通道的风险前提：
 *
 *   · 开放网络（无密码 / OWE）→ 同网任何人都能与你通信，风险最高；
 *   · 公共 WiFi（CMCC / ChinaNet / 商家热点）→ 同网人流量大，风险偏高；
 *   · 家庭 / 公司加密 WiFi → 风险低，仍建议用完就关；
 *   · 纯移动数据 → 多数机型的无线调试根本起不来，等于是「不可用」。
 *
 * 边界（照实说）：这里只做「判断与提示」，不改任何系统设置、不扫描同网设备、不探测端口。
 * 本文件不联网、不上传，读的都是系统公开接口。
 *
 * 兼容性备注：`WifiInfo.getCapabilities()` 在 android-36 的公开接口里已经没有了，
 * 所以加密方式统一走 API 31+ 的 `getCurrentSecurityType()`；12 以下显示「读不到」，
 * 不猜、不硬编码。
 */
object NetAudit {

    /** 一行结论 + 是否需要红字警告 */
    data class Info(
        val transport: String,   // WiFi / 移动数据 / 以太网 / 无网络
        val detail: String,      // SSID + 加密方式
        val risky: Boolean,      // 是否值得红字提醒
        val advice: String       // 一句话建议
    )

    /** 常见的公共/运营商热点关键词（只用于提示，不做任何评定） */
    private val PUBLIC_TOKENS = listOf(
        "cmcc", "chinanet", "chinaunicom", "campus", "eduroam", "starbucks", "mcdonald",
        "kfc", "free", "public", "guest", "airport", "hotel", "railway", "metro", "wifi"
    )

    fun inspect(ctx: Context): Info {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Info("未知", "取不到网络状态", true, "先确认网络正常")
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
            ?: return Info("无网络", "当前没有可用网络", true, "连上网络后再用无线调试")

        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val eth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (cell && !wifi && !eth) {
            return Info(
                "移动数据", "当前没有 Wi-Fi",
                risky = true,
                advice = "多数机型的无线调试要求 WLAN，纯流量下通常连不上。看类功能不受影响，改权限请回到 Wi-Fi 下做。"
            )
        }
        if (eth && !wifi) {
            return Info("以太网", "有线/共享网络", risky = false, advice = "以太网同网设备较少，风险低于公共 Wi-Fi。")
        }
        if (!wifi) {
            return Info("其他网络", "未识别传输方式", risky = true, advice = "无法判断风险，建议只在可信网络下使用无线调试。")
        }

        // —— 是 Wi-Fi，继续读 SSID 与加密方式 ——
        var ssid = ""
        var secType = -1
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info: WifiInfo? = wm?.connectionInfo
            if (info != null) {
                @Suppress("DEPRECATION")
                val raw = info.ssid ?: ""
                ssid = raw.trim().trim('"')
                if (ssid == "<unknown ssid>" || ssid.isBlank()) ssid = ""
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    secType = info.currentSecurityType
                }
            }
        }

        val open = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && secType >= 0) {
            secType == WifiInfo.SECURITY_TYPE_OPEN || secType == WifiInfo.SECURITY_TYPE_OWE
        } else {
            false
        }

        val secText = securityName(secType)
        val ssidText = if (ssid.isBlank()) "（读不到名称：安卓 10 起要看 SSID 得给定位权限）" else "「$ssid」"
        val hitToken = PUBLIC_TOKENS.firstOrNull { t -> ssid.isNotBlank() && ssid.lowercase().contains(t) }
        val isPublic = hitToken != null

        val risky = open || isPublic
        val advice = when {
            open ->
                "这是开放网络（没有真正加密），同一网络的人都能看到你的设备。无线调试期间风险最高：用完立刻断开。"
            isPublic ->
                "这个 Wi-Fi 看起来是公共热点（名称含「$hitToken」），同网人流量大。改完权限就断开。"
            else ->
                "加密网络，风险低于公共 Wi-Fi；但无线调试开着期间仍然是一个对外服务，用完就断开。"
        }
        return Info("Wi-Fi", "$ssidText · $secText", risky, advice)
    }

    private fun securityName(secType: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return "加密方式读不到（系统低于 Android 12）"
        }
        if (secType < 0) return "加密方式未知"
        return when (secType) {
            WifiInfo.SECURITY_TYPE_OPEN -> "开放（无密码）"
            WifiInfo.SECURITY_TYPE_OWE -> "OWE（增强开放）"
            WifiInfo.SECURITY_TYPE_WEP -> "WEP（已过时）"
            WifiInfo.SECURITY_TYPE_PSK -> "WPA2/WPA3 个人"
            WifiInfo.SECURITY_TYPE_SAE -> "WPA3 个人"
            WifiInfo.SECURITY_TYPE_EAP -> "WPA2/WPA3 企业"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3 企业"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3 企业（192 位）"
            WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
            WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
            WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI 个人"
            WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI 证书"
            WifiInfo.SECURITY_TYPE_DPP -> "DPP（二维码配置）"
            WifiInfo.SECURITY_TYPE_OSEN -> "OSEN"
            WifiInfo.SECURITY_TYPE_UNKNOWN -> "未知加密方式"
            else -> "未知加密方式（代码 $secType）"
        }
    }
}
